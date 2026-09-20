use std::{
    sync::{
        Arc, Mutex as StdMutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[cfg(any(
    feature = "quic",
    all(feature = "ble", any(windows, target_os = "android")),
    all(feature = "wifi-aware", target_os = "android")
))]
use std::net::UdpSocket;
#[cfg(feature = "quic")]
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use async_trait::async_trait;
use fabric_core::AppPrincipal;
use fabric_discovery::{
    CandidateCache, CandidateSink, DiscoveryBackend, DiscoveryError, DiscoveryKey, PeerCandidate,
};
use fabric_identity::{DeviceTrustStore, IdentityError, OsCredentialAdapter, PeerCredentials};
use fabric_ipc::{ConnectionQuota, IpcServer, LocalIpcListener, default_local_ipc_endpoint};
use fabric_registry::Registry;
#[cfg(any(feature = "mdns", feature = "ble", feature = "wifi-aware"))]
use rand_core::RngCore;
use tokio::task::JoinHandle;

use crate::{Hub, HubComponent, HubError, StartupStage};

#[derive(Clone)]
pub struct SharedCredentialAdapter(Arc<dyn OsCredentialAdapter>);

impl SharedCredentialAdapter {
    #[must_use]
    pub fn new(adapter: Arc<dyn OsCredentialAdapter>) -> Self {
        Self(adapter)
    }
}

impl OsCredentialAdapter for SharedCredentialAdapter {
    fn authenticate(&self, credentials: &PeerCredentials) -> Result<AppPrincipal, IdentityError> {
        self.0.authenticate(credentials)
    }
}

pub type HubIpcServer = IpcServer<SharedCredentialAdapter>;

pub struct IpcRuntime {
    server: Arc<HubIpcServer>,
    registry: Arc<Registry>,
    listener: Option<LocalIpcListener<SharedCredentialAdapter>>,
    listener_task: Option<JoinHandle<Result<(), fabric_ipc::LocalIpcError>>>,
    maintenance_task: Option<JoinHandle<()>>,
    shutdown: Option<tokio::sync::watch::Sender<bool>>,
}

impl IpcRuntime {
    #[must_use]
    pub fn new(
        server: Arc<HubIpcServer>,
        registry: Arc<Registry>,
        listener: LocalIpcListener<SharedCredentialAdapter>,
    ) -> Self {
        Self {
            server,
            registry,
            listener: Some(listener),
            listener_task: None,
            maintenance_task: None,
            shutdown: None,
        }
    }

    /// Creates an IPC runtime whose clients are admitted by an embedding
    /// platform bridge instead of a local socket or named pipe.
    #[must_use]
    pub fn embedded(server: Arc<HubIpcServer>, registry: Arc<Registry>) -> Self {
        Self {
            server,
            registry,
            listener: None,
            listener_task: None,
            maintenance_task: None,
            shutdown: None,
        }
    }

    #[must_use]
    pub fn server(&self) -> Arc<HubIpcServer> {
        Arc::clone(&self.server)
    }
}

#[async_trait]
impl HubComponent for IpcRuntime {
    fn stage(&self) -> StartupStage {
        StartupStage::IpcReady
    }

    async fn start(&mut self) -> Result<(), String> {
        if self.listener_task.is_some() || self.maintenance_task.is_some() {
            return Err("IPC runtime is already running".into());
        }
        let (shutdown, shutdown_rx) = tokio::sync::watch::channel(false);
        self.shutdown = Some(shutdown);
        if let Some(listener) = self.listener.take() {
            self.listener_task = Some(tokio::spawn(listener.run(shutdown_rx)));
        }
        let registry = Arc::clone(&self.registry);
        self.maintenance_task = Some(tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(1));
            loop {
                tick.tick().await;
                let _ = registry.expire(now_ms());
            }
        }));
        Ok(())
    }

    async fn shutdown(&mut self) -> Result<(), String> {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(true);
        }
        let listener_result = if let Some(task) = self.listener_task.take() {
            task.await
                .map_err(|error| error.to_string())?
                .map_err(|error| error.to_string())
        } else {
            Ok(())
        };
        if let Some(task) = self.maintenance_task.take() {
            task.abort();
            let _ = task.await;
        }
        listener_result
    }
}

pub struct CandidateStore {
    cache: StdMutex<CandidateCache>,
    revision: AtomicU64,
    changes: tokio::sync::watch::Sender<u64>,
}

impl Default for CandidateStore {
    fn default() -> Self {
        let (changes, _) = tokio::sync::watch::channel(0);
        Self {
            cache: StdMutex::new(CandidateCache::default()),
            revision: AtomicU64::new(0),
            changes,
        }
    }
}

impl CandidateStore {
    pub fn values(&self) -> Result<Vec<PeerCandidate>, DiscoveryError> {
        self.cache
            .lock()
            .map_err(|_| DiscoveryError::SinkFailed)
            .map(|cache| cache.values())
    }

    #[must_use]
    pub fn subscribe(&self) -> tokio::sync::watch::Receiver<u64> {
        self.changes.subscribe()
    }

    fn notify_changed(&self) {
        let revision = self
            .revision
            .fetch_add(1, Ordering::Relaxed)
            .saturating_add(1);
        self.changes.send_replace(revision);
    }
}

#[async_trait]
impl CandidateSink for CandidateStore {
    async fn upsert(&self, candidate: PeerCandidate) -> Result<(), DiscoveryError> {
        self.cache
            .lock()
            .map_err(|_| DiscoveryError::SinkFailed)?
            .merge(candidate);
        self.notify_changed();
        Ok(())
    }

    async fn remove(&self, key: &DiscoveryKey) -> Result<(), DiscoveryError> {
        self.cache
            .lock()
            .map_err(|_| DiscoveryError::SinkFailed)?
            .remove(key);
        self.notify_changed();
        Ok(())
    }
}

pub struct DiscoveryRuntime {
    backends: Vec<Arc<dyn DiscoveryBackend>>,
    candidates: Arc<CandidateStore>,
    started: usize,
}

impl DiscoveryRuntime {
    #[must_use]
    pub fn new(backends: Vec<Arc<dyn DiscoveryBackend>>, candidates: Arc<CandidateStore>) -> Self {
        Self {
            backends,
            candidates,
            started: 0,
        }
    }
}

#[async_trait]
impl HubComponent for DiscoveryRuntime {
    fn stage(&self) -> StartupStage {
        StartupStage::DiscoveryReady
    }

    async fn start(&mut self) -> Result<(), String> {
        for backend in &self.backends {
            if let Err(error) = backend.start(self.candidates.clone()).await {
                for started in self.backends[..self.started].iter().rev() {
                    let _ = started.stop().await;
                }
                self.started = 0;
                return Err(error.to_string());
            }
            self.started += 1;
        }
        Ok(())
    }

    async fn shutdown(&mut self) -> Result<(), String> {
        let mut first_error = None;
        for backend in self.backends[..self.started].iter().rev() {
            if let Err(error) = backend.stop().await
                && first_error.is_none()
            {
                first_error = Some(error.to_string());
            }
        }
        self.started = 0;
        first_error.map_or(Ok(()), Err)
    }
}

#[cfg(feature = "quic")]
mod quic_runtime {
    #[cfg(feature = "clock")]
    use std::time::Instant;
    use std::{
        collections::{BTreeMap, BTreeSet},
        net::SocketAddr,
        sync::{
            Arc,
            atomic::{AtomicU64, Ordering},
        },
        time::Duration,
    };

    use async_trait::async_trait;
    use bytes::Bytes;
    #[cfg(feature = "clock")]
    use fabric_clock::{ClockEstimator, ClockMapping, ClockSample};
    use fabric_core::{
        AbilityInstanceId, AbilityOffer, DeviceId, OfferVisibility, OperationId, SessionId,
        SessionPlan, SessionState,
    };
    use fabric_identity::DeviceTrust;
    use fabric_link::{FabricLink, IncomingStream, LinkCloseReason};
    #[cfg(feature = "clock")]
    use fabric_protocol::{ClockProbe, ClockReply};
    use fabric_protocol::{
        ControlFrame, ControlMessage, ControlSecurity, ControlSecurityConfig, EpochPolicy,
        IngressDecision, MESSAGE_NONCE_BYTES, OfferRemove, OfferUpsert, PairingComplete,
        PairingStart, RegistryAck, RegistryResyncRequest, RegistrySnapshotBegin,
        RegistrySnapshotEnd, SecurityError, SessionAccept, SessionCommit, SessionControlHeader,
        SessionPrepare, SessionPropose, SessionReady, wire,
    };
    use fabric_registry::{PeerOfferDelta, PeerRegistry, Registry, RegistryRevision, SyncError};
    use fabric_session::{SessionError, SessionEvent, SessionMachine};
    use fabric_transport_quic::{HandshakeResult, QuicEndpoint, QuicLink, retain_outbound};
    use rand_core::{OsRng, RngCore};
    use thiserror::Error;
    #[cfg(feature = "clock")]
    use tokio::sync::oneshot;
    use tokio::sync::{Mutex, RwLock, broadcast, mpsc};
    use tokio::task::JoinHandle;

    use super::{CandidateStore, now_ms};
    use crate::{HubComponent, StartupStage};

    #[derive(Clone, Debug)]
    pub struct InboundControl {
        pub peer: DeviceId,
        pub trust: DeviceTrust,
        pub message: ControlMessage,
    }

    #[derive(Clone, Debug)]
    pub struct InboundDatagram {
        pub peer: DeviceId,
        pub payload: Bytes,
    }

    pub struct InboundStream {
        pub peer: DeviceId,
        pub stream: IncomingStream,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub struct ActivePeer {
        pub device_id: DeviceId,
        pub trust: DeviceTrust,
    }

    pub struct LinkServices {
        endpoint: Arc<QuicEndpoint>,
        registry: Arc<Registry>,
        links: RwLock<BTreeMap<DeviceId, ActiveLink>>,
        /// Last authenticated direct address explicitly dialed for each peer.
        ///
        /// This must remain separate from the QUIC connection's remote path:
        /// magic-socket paths expose a synthetic mapped address which is not a
        /// stable address another process can dial after restart.
        direct_addresses: RwLock<BTreeMap<DeviceId, SocketAddr>>,
        last_dial_error: RwLock<Option<String>>,
        incoming_registries: RwLock<BTreeMap<DeviceId, IncomingRegistryState>>,
        outgoing_registries: Mutex<BTreeMap<DeviceId, OutgoingRegistryState>>,
        sessions: Mutex<BTreeMap<SessionId, NetworkSession>>,
        control_tx: mpsc::Sender<InboundControl>,
        control_rx: Mutex<mpsc::Receiver<InboundControl>>,
        datagram_tx: broadcast::Sender<InboundDatagram>,
        stream_tx: mpsc::Sender<InboundStream>,
        stream_rx: Mutex<mpsc::Receiver<InboundStream>>,
        security: Mutex<ControlSecurity>,
        request_id: AtomicU64,
        #[cfg(feature = "clock")]
        clock_epoch: Instant,
        #[cfg(feature = "clock")]
        clock_sequence: AtomicU64,
        #[cfg(feature = "clock")]
        clock_pending: Mutex<BTreeMap<(DeviceId, u64), oneshot::Sender<ClockReply>>>,
        #[cfg(feature = "clock")]
        clocks: Mutex<BTreeMap<DeviceId, PeerClockState>>,
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    enum LinkDirection {
        Inbound,
        Outbound,
    }

    struct ActiveLink {
        link: Arc<QuicLink>,
        trust: DeviceTrust,
        direction: LinkDirection,
        registry_send: Arc<Mutex<()>>,
    }

    #[derive(Default)]
    struct IncomingRegistryState {
        registry: PeerRegistry,
        snapshot: Option<IncomingSnapshot>,
    }

    struct IncomingSnapshot {
        revision: RegistryRevision,
        offers: BTreeMap<AbilityInstanceId, AbilityOffer>,
    }

    #[derive(Default)]
    struct OutgoingRegistryState {
        revision: RegistryRevision,
        offers: BTreeMap<AbilityInstanceId, AbilityOffer>,
    }

    enum RegistryHandling {
        Forward,
        Consumed,
    }

    struct NetworkSession {
        machine: SessionMachine,
        coordinator_device: DeviceId,
        proposal: Option<(DeviceId, OperationId)>,
    }

    #[cfg(feature = "clock")]
    struct PeerClockState {
        estimator: ClockEstimator,
        mapping: Option<ClockMapping>,
    }

    #[cfg(feature = "clock")]
    impl PeerClockState {
        fn new() -> Self {
            Self {
                estimator: ClockEstimator::new(
                    CLOCK_ESTIMATOR_CAPACITY,
                    CLOCK_MAPPING_STALE_AFTER_NS,
                ),
                mapping: None,
            }
        }
    }

    #[derive(Debug, Error)]
    pub enum NetworkSessionError {
        #[error("Session plan is invalid: {0}")]
        Session(#[from] SessionError),
        #[error("Session is not known")]
        NotFound,
        #[error("local device is not the Session coordinator")]
        NotCoordinator,
        #[error("Session has no participant on the required device")]
        MissingParticipant,
        #[error("Session already exists")]
        AlreadyExists,
        #[error("authenticated link is unavailable")]
        LinkUnavailable,
        #[error("Session control message is invalid")]
        InvalidControl,
        #[error("Session security state rejected the transition: {0}")]
        Security(#[from] SecurityError),
    }

    enum OutgoingRegistryDelta {
        Upsert(OfferUpsert),
        Remove(OfferRemove),
    }

    const PEER_REGISTRY_CACHE_TTL_MS: u64 = 30_000;
    #[cfg(feature = "clock")]
    const CLOCK_INITIAL_SAMPLE_COUNT: usize = 16;
    #[cfg(feature = "clock")]
    const CLOCK_INITIAL_DROP_MAX_RTT: usize = 2;
    #[cfg(feature = "clock")]
    const CLOCK_PERIODIC_SAMPLE_COUNT: usize = 4;
    #[cfg(feature = "clock")]
    const CLOCK_PERIODIC_DROP_MAX_RTT: usize = 1;
    #[cfg(feature = "clock")]
    const CLOCK_PERIODIC_INTERVAL: Duration = Duration::from_secs(45);
    #[cfg(feature = "clock")]
    const CLOCK_SAMPLE_TIMEOUT: Duration = Duration::from_millis(500);
    #[cfg(feature = "clock")]
    const CLOCK_ESTIMATOR_CAPACITY: usize = 32;
    #[cfg(feature = "clock")]
    const CLOCK_MAPPING_STALE_AFTER_NS: u64 = 90_000_000_000;

    impl LinkServices {
        pub fn new(
            endpoint: Arc<QuicEndpoint>,
            event_capacity: usize,
        ) -> Result<Arc<Self>, SecurityError> {
            Self::with_registry(endpoint, Arc::new(Registry::new()), event_capacity)
        }

        pub fn with_registry(
            endpoint: Arc<QuicEndpoint>,
            registry: Arc<Registry>,
            event_capacity: usize,
        ) -> Result<Arc<Self>, SecurityError> {
            let capacity = event_capacity.max(1);
            let (control_tx, control_rx) = mpsc::channel(capacity);
            let (datagram_tx, _) = broadcast::channel(capacity);
            let (stream_tx, stream_rx) = mpsc::channel(capacity);
            Ok(Arc::new(Self {
                endpoint,
                registry,
                links: RwLock::new(BTreeMap::new()),
                direct_addresses: RwLock::new(BTreeMap::new()),
                last_dial_error: RwLock::new(None),
                incoming_registries: RwLock::new(BTreeMap::new()),
                outgoing_registries: Mutex::new(BTreeMap::new()),
                sessions: Mutex::new(BTreeMap::new()),
                control_tx,
                control_rx: Mutex::new(control_rx),
                datagram_tx,
                stream_tx,
                stream_rx: Mutex::new(stream_rx),
                security: Mutex::new(ControlSecurity::new(ControlSecurityConfig::default())?),
                request_id: AtomicU64::new(4),
                #[cfg(feature = "clock")]
                clock_epoch: Instant::now(),
                #[cfg(feature = "clock")]
                clock_sequence: AtomicU64::new(1),
                #[cfg(feature = "clock")]
                clock_pending: Mutex::new(BTreeMap::new()),
                #[cfg(feature = "clock")]
                clocks: Mutex::new(BTreeMap::new()),
            }))
        }

        pub fn local_addr(&self) -> Result<SocketAddr, fabric_transport_quic::EndpointError> {
            self.endpoint.local_addr()
        }

        #[must_use]
        pub fn local_device_id(&self) -> DeviceId {
            self.endpoint.device_id()
        }

        pub async fn next_control(&self) -> Option<InboundControl> {
            self.control_rx.lock().await.recv().await
        }

        #[must_use]
        pub fn subscribe_datagrams(&self) -> broadcast::Receiver<InboundDatagram> {
            self.datagram_tx.subscribe()
        }

        pub async fn next_stream(&self) -> Option<InboundStream> {
            self.stream_rx.lock().await.recv().await
        }

        pub async fn active_links(&self) -> usize {
            self.links.read().await.len()
        }

        pub async fn active_peers(&self) -> Vec<ActivePeer> {
            self.links
                .read()
                .await
                .iter()
                .map(|(device_id, link)| ActivePeer {
                    device_id: *device_id,
                    trust: link.trust,
                })
                .collect()
        }

        pub async fn peer_offers(&self, peer: DeviceId) -> Vec<AbilityOffer> {
            let mut registries = self.incoming_registries.write().await;
            let Some(state) = registries.get_mut(&peer) else {
                return Vec::new();
            };
            state.registry.expire_cache(now_ms());
            state.registry.offers.values().cloned().collect()
        }

        pub async fn propose_network_session(
            self: &Arc<Self>,
            plan: SessionPlan,
        ) -> Result<SessionId, NetworkSessionError> {
            let local = self.endpoint.device_id();
            let coordinator_device = coordinator_device(&plan)?;
            if coordinator_device != local {
                return Err(NetworkSessionError::NotCoordinator);
            }
            let peers = participant_devices(&plan, local);
            if peers.is_empty() {
                return Err(NetworkSessionError::MissingParticipant);
            }
            let mut machine = SessionMachine::new(plan.clone(), 128)?;
            machine.apply(SessionEvent::BeginNegotiation {
                operation_id: OperationId::new(),
            })?;
            accept_device_participants(&mut machine, &plan, local, None)?;
            let session_id = plan.session_id;
            {
                let mut sessions = self.sessions.lock().await;
                if sessions.contains_key(&session_id) {
                    return Err(NetworkSessionError::AlreadyExists);
                }
                sessions.insert(
                    session_id,
                    NetworkSession {
                        machine,
                        coordinator_device,
                        proposal: None,
                    },
                );
            }
            self.security.lock().await.set_session_epoch(session_id, 0);

            let proposal = SessionPropose {
                header: self.session_header(session_id, 0),
                contract: plan.contract.clone(),
                encoded_draft_plan: serde_json::to_vec(&plan)
                    .map_err(|_| NetworkSessionError::InvalidControl)?,
                opaque_ability_config: plan.extensions.opaque_ability_config.clone(),
            };
            let message = wire::SessionPropose::from(&proposal);
            for peer in peers {
                if self
                    .send_to_peer(peer, wire::MessageType::SessionPropose, &message)
                    .await
                    .is_err()
                {
                    self.sessions.lock().await.remove(&session_id);
                    self.security.lock().await.remove_session(session_id);
                    return Err(NetworkSessionError::LinkUnavailable);
                }
            }
            Ok(session_id)
        }

        pub async fn accept_network_session(
            self: &Arc<Self>,
            session_id: SessionId,
        ) -> Result<(), NetworkSessionError> {
            let local = self.endpoint.device_id();
            let (coordinator, proposal_operation, response) = {
                let mut sessions = self.sessions.lock().await;
                let session = sessions
                    .get_mut(&session_id)
                    .ok_or(NetworkSessionError::NotFound)?;
                if session.coordinator_device == local {
                    return Err(NetworkSessionError::NotCoordinator);
                }
                let plan = session.machine.plan().clone();
                accept_device_participants(&mut session.machine, &plan, local, None)?;
                let (proposal_peer, proposal_operation) = session
                    .proposal
                    .ok_or(NetworkSessionError::InvalidControl)?;
                if proposal_peer != session.coordinator_device {
                    return Err(NetworkSessionError::InvalidControl);
                }
                let accept = SessionAccept {
                    header: self.session_header(session_id, 0),
                    acceptance: b"accepted".to_vec(),
                };
                let wire = wire::SessionAccept::from(&accept);
                let encoded = self.encode_wire(wire::MessageType::SessionAccept, &wire)?;
                (
                    session.coordinator_device,
                    proposal_operation,
                    (wire, encoded),
                )
            };
            self.security
                .lock()
                .await
                .complete(proposal_operation, response.1.clone())?;
            self.send_to_peer(coordinator, wire::MessageType::SessionAccept, &response.0)
                .await?;
            if let Some(session) = self.sessions.lock().await.get_mut(&session_id) {
                session.proposal = None;
            }
            Ok(())
        }

        pub async fn network_session_state(&self, session_id: SessionId) -> Option<SessionState> {
            self.sessions
                .lock()
                .await
                .get(&session_id)
                .map(|session| session.machine.state())
        }

        pub async fn link(&self, peer: DeviceId) -> Option<Arc<QuicLink>> {
            self.links
                .read()
                .await
                .get(&peer)
                .map(|active| Arc::clone(&active.link))
        }

        /// Last physical discovery/manual address that authenticated as `peer`.
        pub async fn direct_address(&self, peer: DeviceId) -> Option<SocketAddr> {
            self.direct_addresses.read().await.get(&peer).copied()
        }

        pub async fn last_dial_error(&self) -> Option<String> {
            self.last_dial_error.read().await.clone()
        }

        pub async fn note_link_error(&self, error: impl Into<String>) {
            *self.last_dial_error.write().await = Some(error.into());
        }

        #[cfg(test)]
        pub(crate) async fn retained_outbound(&self, peer: DeviceId) -> Option<bool> {
            self.links
                .read()
                .await
                .get(&peer)
                .map(|active| active.direction == LinkDirection::Outbound)
        }

        pub async fn connect(
            self: &Arc<Self>,
            address: SocketAddr,
        ) -> Result<DeviceId, fabric_transport_quic::EndpointError> {
            let result = match self.endpoint.connect(address).await {
                Ok(result) => result,
                Err(error) => {
                    *self.last_dial_error.write().await = Some(error.to_string());
                    return Err(error);
                }
            };
            *self.last_dial_error.write().await = None;
            let peer = result.peer_device_id;
            self.direct_addresses.write().await.insert(peer, address);
            self.install(result, LinkDirection::Outbound).await;
            Ok(peer)
        }

        /// Dial a direct address while pinning it to an expected trusted
        /// identity. The address is retained only after the TLS/device
        /// handshake proves that the expected peer answered.
        pub async fn connect_known(
            self: &Arc<Self>,
            device: DeviceId,
            address: SocketAddr,
        ) -> Result<(), fabric_transport_quic::EndpointError> {
            let result = match self.endpoint.connect_by_device(device, address).await {
                Ok(result) => result,
                Err(error) => {
                    *self.last_dial_error.write().await = Some(error.to_string());
                    return Err(error);
                }
            };
            *self.last_dial_error.write().await = None;
            self.direct_addresses.write().await.insert(device, address);
            self.install(result, LinkDirection::Outbound).await;
            Ok(())
        }

        /// Dials `device` through the configured relay and, once linked, starts a
        /// hole punch advertising `punch_candidates` (our direct addresses) so
        /// the relayed path can upgrade to a direct one.
        pub async fn connect_by_device_via_relay(
            self: &Arc<Self>,
            device: DeviceId,
            punch_candidates: Vec<SocketAddr>,
        ) -> Result<(), fabric_transport_quic::EndpointError> {
            let result = self.endpoint.connect_by_device_via_relay(device).await?;
            self.install(result, LinkDirection::Outbound).await;
            if !punch_candidates.is_empty() {
                self.endpoint.punch_via_relay(device, punch_candidates);
            }
            Ok(())
        }

        /// Whether the underlying endpoint was bound with a relay attached.
        #[must_use]
        pub fn has_relay(&self) -> bool {
            self.endpoint.has_relay()
        }

        pub async fn request_pairing(
            &self,
            peer: DeviceId,
            device_label: &str,
        ) -> Result<(), NetworkSessionError> {
            let mut nonce = [0_u8; MESSAGE_NONCE_BYTES];
            OsRng.fill_bytes(&mut nonce);
            let request = PairingStart {
                nonce,
                device_proof: self.endpoint.sign_device_proof(&nonce),
                device_label: device_label.to_owned(),
            };
            self.send_to_peer(
                peer,
                wire::MessageType::PairingStart,
                &wire::PairingStart::from(&request),
            )
            .await
        }

        pub async fn complete_pairing(
            &self,
            peer: DeviceId,
            device_label: &str,
        ) -> Result<(), NetworkSessionError> {
            self.send_to_peer(
                peer,
                wire::MessageType::PairingComplete,
                &wire::PairingComplete::from(PairingComplete {
                    device_id: self.endpoint.device_id(),
                    device_label: device_label.to_owned(),
                }),
            )
            .await
        }

        pub async fn disconnect_peer(&self, peer: DeviceId) {
            let link = self
                .links
                .write()
                .await
                .remove(&peer)
                .map(|active| active.link);
            if let Some(link) = link {
                link.close(LinkCloseReason::Normal).await;
            }
            if let Some(state) = self.incoming_registries.write().await.get_mut(&peer) {
                state
                    .registry
                    .disconnected(now_ms(), PEER_REGISTRY_CACHE_TTL_MS);
            }
        }

        pub async fn complete_operation(
            &self,
            operation_id: fabric_core::OperationId,
            encoded_result: Vec<u8>,
        ) -> Result<(), SecurityError> {
            self.security
                .lock()
                .await
                .complete(operation_id, encoded_result)
        }

        pub async fn abandon_operation(&self, operation_id: fabric_core::OperationId) {
            self.security.lock().await.abandon(operation_id);
        }

        pub async fn advance_session_epoch(
            &self,
            session_id: fabric_core::SessionId,
            expected_current: u64,
        ) -> Result<u64, SecurityError> {
            self.security
                .lock()
                .await
                .advance_session_epoch(session_id, expected_current)
        }

        pub async fn remove_session(&self, session_id: fabric_core::SessionId) {
            self.security.lock().await.remove_session(session_id);
        }

        async fn install(self: &Arc<Self>, result: HandshakeResult, direction: LinkDirection) {
            let peer = result.peer_device_id;
            let link = result.link;
            let registry_send = Arc::new(Mutex::new(()));
            let preferred = if retain_outbound(self.endpoint.device_id(), peer) {
                LinkDirection::Outbound
            } else {
                LinkDirection::Inbound
            };
            let previous = {
                let mut links = self.links.write().await;
                if let Some(existing) = links.get(&peer)
                    && (existing.direction == preferred || direction != preferred)
                {
                    drop(links);
                    link.close(LinkCloseReason::DuplicateConnection).await;
                    return;
                }
                links
                    .insert(
                        peer,
                        ActiveLink {
                            link: Arc::clone(&link),
                            trust: result.peer_trust,
                            direction,
                            registry_send: Arc::clone(&registry_send),
                        },
                    )
                    .map(|active| active.link)
            };
            if let Some(previous) = previous {
                previous.close(LinkCloseReason::DuplicateConnection).await;
            }
            self.spawn_control(peer, result.peer_trust, Arc::clone(&link));
            self.spawn_datagrams(peer, result.peer_trust, Arc::clone(&link));
            self.spawn_streams(peer, result.peer_trust, Arc::clone(&link));
            if result.peer_trust == DeviceTrust::Trusted {
                #[cfg(feature = "clock")]
                self.spawn_clock_sync(peer, Arc::clone(&link));
                self.spawn_registry_sync(peer, link, registry_send);
            }
        }

        fn spawn_control(
            self: &Arc<Self>,
            peer: DeviceId,
            trust: DeviceTrust,
            link: Arc<QuicLink>,
        ) {
            let services = Arc::clone(self);
            tokio::spawn(async move {
                loop {
                    let Ok(encoded) = link.receive_control().await else {
                        break;
                    };
                    let message = ControlFrame::decode(&encoded)
                        .and_then(|frame| ControlMessage::decode(&frame));
                    let Ok(message) = message else {
                        link.close(LinkCloseReason::ProtocolError).await;
                        break;
                    };
                    if trust != DeviceTrust::Trusted && !is_pairing_message(&message) {
                        link.close(LinkCloseReason::ProtocolError).await;
                        break;
                    }
                    #[cfg(feature = "clock")]
                    match services
                        .process_clock_message(peer, &link, services.local_clock_ns(), &message)
                        .await
                    {
                        Ok(true) => continue,
                        Ok(false) => {}
                        Err(()) => {
                            link.close(LinkCloseReason::ProtocolError).await;
                            break;
                        }
                    }
                    match services
                        .process_registry_message(peer, trust, &link, &message)
                        .await
                    {
                        Ok(RegistryHandling::Consumed) => continue,
                        Ok(RegistryHandling::Forward) => {}
                        Err(()) => {
                            link.close(LinkCloseReason::ProtocolError).await;
                            break;
                        }
                    }
                    if let Some(ingress) = message.session_ingress() {
                        let decision = {
                            let mut security = services.security.lock().await;
                            let decision = security.authorize(
                                &ingress.header,
                                peer,
                                &ingress.semantic_payload,
                                ingress.epoch_policy,
                                now_ms(),
                            );
                            if matches!(decision, Ok(IngressDecision::Execute))
                                && ingress.epoch_policy == EpochPolicy::New
                            {
                                security.set_session_epoch(ingress.header.session_id, 0);
                            }
                            decision
                        };
                        match decision {
                            Ok(IngressDecision::Execute) => {}
                            Ok(IngressDecision::Cached(encoded_result)) => {
                                if encoded_result.is_empty() {
                                    continue;
                                }
                                if link
                                    .send_control(Bytes::from(encoded_result))
                                    .await
                                    .is_err()
                                {
                                    break;
                                }
                                continue;
                            }
                            Err(_) => {
                                link.close(LinkCloseReason::ProtocolError).await;
                                break;
                            }
                        }
                        if services
                            .process_session_message(peer, &message)
                            .await
                            .is_err()
                        {
                            let mut security = services.security.lock().await;
                            security.abandon(ingress.header.operation_id);
                            if ingress.epoch_policy == EpochPolicy::New {
                                security.remove_session(ingress.header.session_id);
                            }
                            drop(security);
                            link.close(LinkCloseReason::ProtocolError).await;
                            break;
                        }
                    }
                    if services
                        .control_tx
                        .send(InboundControl {
                            peer,
                            trust,
                            message,
                        })
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                services.remove_if_current(peer, &link).await;
            });
        }

        fn spawn_datagrams(
            self: &Arc<Self>,
            peer: DeviceId,
            trust: DeviceTrust,
            link: Arc<QuicLink>,
        ) {
            let services = Arc::clone(self);
            tokio::spawn(async move {
                while let Ok(payload) = link.receive_datagram().await {
                    if trust != DeviceTrust::Trusted {
                        link.close(LinkCloseReason::ProtocolError).await;
                        break;
                    }
                    let _ = services.datagram_tx.send(InboundDatagram { peer, payload });
                }
                services.remove_if_current(peer, &link).await;
            });
        }

        fn spawn_streams(
            self: &Arc<Self>,
            peer: DeviceId,
            trust: DeviceTrust,
            link: Arc<QuicLink>,
        ) {
            let services = Arc::clone(self);
            tokio::spawn(async move {
                while let Ok(stream) = link.accept_stream().await {
                    if trust != DeviceTrust::Trusted {
                        link.close(LinkCloseReason::ProtocolError).await;
                        break;
                    }
                    if services
                        .stream_tx
                        .send(InboundStream { peer, stream })
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
                services.remove_if_current(peer, &link).await;
            });
        }

        #[allow(clippy::too_many_lines)]
        async fn process_session_message(
            &self,
            peer: DeviceId,
            message: &ControlMessage,
        ) -> Result<(), NetworkSessionError> {
            match message {
                ControlMessage::SessionPropose(value) => {
                    let plan: SessionPlan = serde_json::from_slice(&value.encoded_draft_plan)
                        .map_err(|_| NetworkSessionError::InvalidControl)?;
                    if plan.session_id != value.header.session_id
                        || plan.contract != value.contract
                        || coordinator_device(&plan)? != peer
                        || !plan
                            .participants
                            .iter()
                            .any(|participant| participant.device_id == self.endpoint.device_id())
                    {
                        return Err(NetworkSessionError::InvalidControl);
                    }
                    let mut machine = SessionMachine::new(plan, 128)?;
                    machine.apply(SessionEvent::BeginNegotiation {
                        operation_id: value.header.operation_id,
                    })?;
                    let mut sessions = self.sessions.lock().await;
                    if sessions.contains_key(&value.header.session_id) {
                        return Err(NetworkSessionError::AlreadyExists);
                    }
                    sessions.insert(
                        value.header.session_id,
                        NetworkSession {
                            machine,
                            coordinator_device: peer,
                            proposal: Some((peer, value.header.operation_id)),
                        },
                    );
                }
                ControlMessage::SessionAccept(value) => {
                    let prepare = {
                        let mut sessions = self.sessions.lock().await;
                        let session = sessions
                            .get_mut(&value.header.session_id)
                            .ok_or(NetworkSessionError::NotFound)?;
                        if session.coordinator_device != self.endpoint.device_id() {
                            return Err(NetworkSessionError::NotCoordinator);
                        }
                        let plan = session.machine.plan().clone();
                        accept_device_participants(
                            &mut session.machine,
                            &plan,
                            peer,
                            Some(value.header.operation_id),
                        )?;
                        match session.machine.apply(SessionEvent::Prepare {
                            operation_id: OperationId::new(),
                            plan: plan.clone(),
                        }) {
                            Ok(_) => {
                                ready_device_participants(
                                    &mut session.machine,
                                    &plan,
                                    self.endpoint.device_id(),
                                    plan.canonical_hash(),
                                    None,
                                )?;
                                Some(plan)
                            }
                            Err(SessionError::ParticipantsNotReady) => None,
                            Err(error) => return Err(error.into()),
                        }
                    };
                    self.security
                        .lock()
                        .await
                        .complete(value.header.operation_id, Vec::new())?;
                    if let Some(plan) = prepare {
                        let hash = plan.canonical_hash();
                        let prepare = SessionPrepare {
                            header: self.session_header(plan.session_id, plan.epoch),
                            encoded_final_plan: serde_json::to_vec(&plan)
                                .map_err(|_| NetworkSessionError::InvalidControl)?,
                            plan_hash: hash,
                        };
                        let wire = wire::SessionPrepare::from(&prepare);
                        for destination in participant_devices(&plan, self.endpoint.device_id()) {
                            self.send_to_peer(
                                destination,
                                wire::MessageType::SessionPrepare,
                                &wire,
                            )
                            .await?;
                        }
                    }
                }
                ControlMessage::SessionPrepare(value) => {
                    let plan: SessionPlan = serde_json::from_slice(&value.encoded_final_plan)
                        .map_err(|_| NetworkSessionError::InvalidControl)?;
                    if plan.session_id != value.header.session_id
                        || plan.canonical_hash() != value.plan_hash
                    {
                        return Err(NetworkSessionError::InvalidControl);
                    }
                    let ready = {
                        let mut sessions = self.sessions.lock().await;
                        let session = sessions
                            .get_mut(&value.header.session_id)
                            .ok_or(NetworkSessionError::NotFound)?;
                        if session.coordinator_device != peer || session.machine.plan() != &plan {
                            return Err(NetworkSessionError::InvalidControl);
                        }
                        for device in participant_devices_including_local(&plan) {
                            accept_device_participants(&mut session.machine, &plan, device, None)?;
                        }
                        session.machine.apply(SessionEvent::Prepare {
                            operation_id: value.header.operation_id,
                            plan: plan.clone(),
                        })?;
                        ready_device_participants(
                            &mut session.machine,
                            &plan,
                            self.endpoint.device_id(),
                            value.plan_hash,
                            None,
                        )?;
                        SessionReady {
                            header: self.session_header(plan.session_id, plan.epoch),
                            plan_hash: value.plan_hash,
                        }
                    };
                    let wire = wire::SessionReady::from(&ready);
                    let encoded = self.encode_wire(wire::MessageType::SessionReady, &wire)?;
                    self.security
                        .lock()
                        .await
                        .complete(value.header.operation_id, encoded)?;
                    self.send_to_peer(peer, wire::MessageType::SessionReady, &wire)
                        .await?;
                }
                ControlMessage::SessionReady(value) => {
                    let commit = {
                        let mut sessions = self.sessions.lock().await;
                        let session = sessions
                            .get_mut(&value.header.session_id)
                            .ok_or(NetworkSessionError::NotFound)?;
                        if session.coordinator_device != self.endpoint.device_id() {
                            return Err(NetworkSessionError::NotCoordinator);
                        }
                        let plan = session.machine.plan().clone();
                        if plan.canonical_hash() != value.plan_hash {
                            return Err(NetworkSessionError::InvalidControl);
                        }
                        ready_device_participants(
                            &mut session.machine,
                            &plan,
                            peer,
                            value.plan_hash,
                            Some(value.header.operation_id),
                        )?;
                        match session.machine.apply(SessionEvent::Commit {
                            operation_id: OperationId::new(),
                            plan_hash: value.plan_hash,
                        }) {
                            Ok(_) => Some(plan),
                            Err(SessionError::ParticipantsNotReady) => None,
                            Err(error) => return Err(error.into()),
                        }
                    };
                    self.security
                        .lock()
                        .await
                        .complete(value.header.operation_id, Vec::new())?;
                    if let Some(plan) = commit {
                        self.security
                            .lock()
                            .await
                            .advance_session_epoch(plan.session_id, 0)?;
                        let commit = SessionCommit {
                            header: self.session_header(plan.session_id, plan.epoch),
                            plan_hash: plan.canonical_hash(),
                        };
                        let wire = wire::SessionCommit::from(&commit);
                        for destination in participant_devices(&plan, self.endpoint.device_id()) {
                            self.send_to_peer(destination, wire::MessageType::SessionCommit, &wire)
                                .await?;
                        }
                    }
                }
                ControlMessage::SessionCommit(value) => {
                    {
                        let mut sessions = self.sessions.lock().await;
                        let session = sessions
                            .get_mut(&value.header.session_id)
                            .ok_or(NetworkSessionError::NotFound)?;
                        if session.coordinator_device != peer
                            || session.machine.plan().canonical_hash() != value.plan_hash
                        {
                            return Err(NetworkSessionError::InvalidControl);
                        }
                        let plan = session.machine.plan().clone();
                        for device in participant_devices_including_local(&plan) {
                            ready_device_participants(
                                &mut session.machine,
                                &plan,
                                device,
                                value.plan_hash,
                                None,
                            )?;
                        }
                        session.machine.apply(SessionEvent::Commit {
                            operation_id: value.header.operation_id,
                            plan_hash: value.plan_hash,
                        })?;
                    }
                    let mut security = self.security.lock().await;
                    security.advance_session_epoch(value.header.session_id, 0)?;
                    security.complete(value.header.operation_id, Vec::new())?;
                }
                _ => {}
            }
            Ok(())
        }

        #[cfg(feature = "clock")]
        fn spawn_clock_sync(self: &Arc<Self>, peer: DeviceId, link: Arc<QuicLink>) {
            let services = Arc::clone(self);
            tokio::spawn(async move {
                let _ = services
                    .synchronize_clock(
                        peer,
                        &link,
                        CLOCK_INITIAL_SAMPLE_COUNT,
                        CLOCK_INITIAL_DROP_MAX_RTT,
                    )
                    .await;
                let mut interval = tokio::time::interval(CLOCK_PERIODIC_INTERVAL);
                loop {
                    interval.tick().await;
                    if services
                        .link(peer)
                        .await
                        .is_none_or(|active| !Arc::ptr_eq(&active, &link))
                    {
                        break;
                    }
                    let _ = services
                        .synchronize_clock(
                            peer,
                            &link,
                            CLOCK_PERIODIC_SAMPLE_COUNT,
                            CLOCK_PERIODIC_DROP_MAX_RTT,
                        )
                        .await;
                }
            });
        }

        #[cfg(feature = "clock")]
        async fn process_clock_message(
            &self,
            peer: DeviceId,
            link: &Arc<QuicLink>,
            received_at_local_ns: u64,
            message: &ControlMessage,
        ) -> Result<bool, ()> {
            match message {
                ControlMessage::ClockProbe(probe) => {
                    let reply = ClockReply {
                        clock_domain_id: probe.clock_domain_id,
                        sequence: probe.sequence,
                        t1_local_ns: probe.t1_local_ns,
                        t2_group_ns: i64_saturating_from_u64(received_at_local_ns),
                        t3_group_ns: i64_saturating_from_u64(self.local_clock_ns()),
                    };
                    let wire = wire::ClockReply::from(reply);
                    self.send_wire(link, wire::MessageType::ClockReply, &wire)
                        .await?;
                    Ok(true)
                }
                ControlMessage::ClockReply(reply) => {
                    if let Some(sender) = self
                        .clock_pending
                        .lock()
                        .await
                        .remove(&(peer, reply.sequence))
                    {
                        let _ = sender.send(*reply);
                    }
                    Ok(true)
                }
                _ => Ok(false),
            }
        }

        #[cfg(feature = "clock")]
        async fn synchronize_clock(
            &self,
            peer: DeviceId,
            link: &Arc<QuicLink>,
            sample_count: usize,
            drop_max_rtt: usize,
        ) -> Result<(), ()> {
            let mut samples = Vec::with_capacity(sample_count);
            for _ in 0..sample_count {
                if let Ok(sample) = self.clock_sample(peer, link).await {
                    samples.push(sample);
                }
            }
            let samples = accepted_clock_samples(samples, drop_max_rtt);
            if samples.is_empty() {
                return Err(());
            }
            let mut clocks = self.clocks.lock().await;
            let state = clocks.entry(peer).or_insert_with(PeerClockState::new);
            for sample in samples {
                let _ = state.estimator.add_sample(sample);
            }
            if let Ok(mapping) = state.estimator.mapping() {
                state.mapping = Some(mapping);
            }
            Ok(())
        }

        #[cfg(feature = "clock")]
        async fn clock_sample(
            &self,
            peer: DeviceId,
            link: &Arc<QuicLink>,
        ) -> Result<ClockSample, ()> {
            let sequence = self.clock_sequence.fetch_add(1, Ordering::Relaxed);
            if sequence == u64::MAX {
                return Err(());
            }
            let t1 = self.local_clock_ns();
            let probe = ClockProbe {
                clock_domain_id: fabric_core::ClockDomainId::new(),
                sequence,
                t1_local_ns: t1,
            };
            let wire = wire::ClockProbe::from(probe);
            let (sender, receiver) = oneshot::channel();
            self.clock_pending
                .lock()
                .await
                .insert((peer, sequence), sender);
            if self
                .send_wire(link, wire::MessageType::ClockProbe, &wire)
                .await
                .is_err()
            {
                self.clock_pending.lock().await.remove(&(peer, sequence));
                return Err(());
            }
            let reply = match tokio::time::timeout(CLOCK_SAMPLE_TIMEOUT, receiver).await {
                Ok(Ok(reply)) => reply,
                Ok(Err(_)) | Err(_) => {
                    self.clock_pending.lock().await.remove(&(peer, sequence));
                    return Err(());
                }
            };
            let t4 = self.local_clock_ns();
            if reply.sequence != sequence || reply.t1_local_ns != t1 {
                return Err(());
            }
            ClockSample::from_timestamps(
                t1,
                i128::from(reply.t2_group_ns),
                i128::from(reply.t3_group_ns),
                t4,
            )
            .map_err(|_| ())
        }

        #[cfg(feature = "clock")]
        fn local_clock_ns(&self) -> u64 {
            u64::try_from(self.clock_epoch.elapsed().as_nanos()).unwrap_or(u64::MAX)
        }

        #[cfg(feature = "clock")]
        pub async fn peer_clock_mapping(&self, peer: DeviceId) -> Option<ClockMapping> {
            self.clocks
                .lock()
                .await
                .get(&peer)
                .and_then(|state| state.mapping)
        }

        /// Ensure a peer has a fresh fabric-clock mapping (initial sample set).
        #[cfg(feature = "clock")]
        pub async fn ensure_peer_clock(&self, peer: DeviceId) -> Result<(), ()> {
            let link = self.link(peer).await.ok_or(())?;
            self.synchronize_clock(
                peer,
                &link,
                CLOCK_INITIAL_SAMPLE_COUNT,
                CLOCK_INITIAL_DROP_MAX_RTT,
            )
            .await
        }

        #[cfg(feature = "clock")]
        pub async fn peer_group_to_local(
            &self,
            peer: DeviceId,
            group_ns: i128,
        ) -> Option<(u64, u64)> {
            use fabric_core::GroupInstant;
            let mapping = self.peer_clock_mapping(peer).await?;
            let estimated = mapping.group_to_local(GroupInstant(group_ns)).ok()?;
            Some((estimated.value.0, estimated.uncertainty_ns))
        }

        #[cfg(feature = "clock")]
        pub async fn peer_local_to_group(
            &self,
            peer: DeviceId,
            local_ns: u64,
        ) -> Option<(i128, u64)> {
            use fabric_core::LocalInstant;
            let mapping = self.peer_clock_mapping(peer).await?;
            let estimated = mapping.local_to_group(LocalInstant(local_ns)).ok()?;
            Some((estimated.value.0, estimated.uncertainty_ns))
        }

        #[cfg(feature = "clock")]
        pub fn hub_local_now_ns(&self) -> u64 {
            self.local_clock_ns()
        }

        fn spawn_registry_sync(
            self: &Arc<Self>,
            peer: DeviceId,
            link: Arc<QuicLink>,
            send_lock: Arc<Mutex<()>>,
        ) {
            let services = Arc::clone(self);
            tokio::spawn(async move {
                if services
                    .send_registry_snapshot(peer, &link, &send_lock)
                    .await
                    .is_err()
                {
                    link.close(LinkCloseReason::ProtocolError).await;
                    return;
                }
                let mut interval = tokio::time::interval(Duration::from_millis(100));
                loop {
                    interval.tick().await;
                    let _guard = send_lock.lock().await;
                    let Ok(deltas) = services.reconcile_outgoing_registry(peer).await else {
                        break;
                    };
                    let mut failed = false;
                    for delta in deltas {
                        let result = match delta {
                            OutgoingRegistryDelta::Upsert(value) => {
                                let message = wire::OfferUpsert::from(&value);
                                services
                                    .send_wire(&link, wire::MessageType::OfferUpsert, &message)
                                    .await
                            }
                            OutgoingRegistryDelta::Remove(value) => {
                                let message = wire::OfferRemove::from(&value);
                                services
                                    .send_wire(&link, wire::MessageType::OfferRemove, &message)
                                    .await
                            }
                        };
                        if result.is_err() {
                            failed = true;
                            break;
                        }
                    }
                    if failed {
                        link.close(LinkCloseReason::ProtocolError).await;
                        break;
                    }
                }
            });
        }

        async fn process_registry_message(
            &self,
            peer: DeviceId,
            trust: DeviceTrust,
            link: &Arc<QuicLink>,
            message: &ControlMessage,
        ) -> Result<RegistryHandling, ()> {
            match message {
                ControlMessage::RegistrySnapshotBegin(value) => {
                    let mut registries = self.incoming_registries.write().await;
                    let state = registries.entry(peer).or_default();
                    if state.snapshot.is_some() {
                        return Err(());
                    }
                    state.snapshot = Some(IncomingSnapshot {
                        revision: RegistryRevision(value.revision),
                        offers: BTreeMap::new(),
                    });
                    Ok(RegistryHandling::Consumed)
                }
                ControlMessage::OfferUpsert(value) => {
                    let offer: AbilityOffer =
                        serde_json::from_slice(&value.encoded_offer).map_err(|_| ())?;
                    offer.validate().map_err(|_| ())?;
                    if offer.visibility == OfferVisibility::LocalOnly {
                        return Err(());
                    }
                    let outcome = {
                        let mut registries = self.incoming_registries.write().await;
                        let state = registries.entry(peer).or_default();
                        if let Some(snapshot) = state.snapshot.as_mut() {
                            if snapshot.revision != RegistryRevision(value.revision)
                                || snapshot.offers.insert(offer.instance_id, offer).is_some()
                            {
                                return Err(());
                            }
                            None
                        } else {
                            Some(state.registry.apply_delta(
                                trust == DeviceTrust::Trusted,
                                PeerOfferDelta::Upsert {
                                    revision: RegistryRevision(value.revision),
                                    offer: Box::new(offer),
                                },
                            ))
                        }
                    };
                    self.finish_delta(peer, link, outcome).await?;
                    Ok(RegistryHandling::Consumed)
                }
                ControlMessage::OfferRemove(value) => {
                    let outcome = {
                        let mut registries = self.incoming_registries.write().await;
                        let state = registries.entry(peer).or_default();
                        if state.snapshot.is_some() {
                            return Err(());
                        }
                        state.registry.apply_delta(
                            trust == DeviceTrust::Trusted,
                            PeerOfferDelta::Remove {
                                revision: RegistryRevision(value.revision),
                                instance_id: value.ability_instance_id,
                            },
                        )
                    };
                    self.finish_delta(peer, link, Some(outcome)).await?;
                    Ok(RegistryHandling::Consumed)
                }
                ControlMessage::RegistrySnapshotEnd(value) => {
                    let revision = RegistryRevision(value.revision);
                    {
                        let mut registries = self.incoming_registries.write().await;
                        let state = registries.entry(peer).or_default();
                        let snapshot = state.snapshot.take().ok_or(())?;
                        if snapshot.revision != revision {
                            return Err(());
                        }
                        state
                            .registry
                            .apply_snapshot(
                                trust == DeviceTrust::Trusted,
                                revision,
                                snapshot.offers.into_values().collect(),
                            )
                            .map_err(|_| ())?;
                    }
                    self.send_registry_ack(link, revision).await?;
                    Ok(RegistryHandling::Consumed)
                }
                ControlMessage::RegistryResyncRequest(_) => {
                    let send_lock = {
                        let links = self.links.read().await;
                        links
                            .get(&peer)
                            .filter(|active| Arc::ptr_eq(&active.link, link))
                            .map(|active| Arc::clone(&active.registry_send))
                    }
                    .ok_or(())?;
                    self.send_registry_snapshot(peer, link, &send_lock).await?;
                    Ok(RegistryHandling::Forward)
                }
                _ => Ok(RegistryHandling::Forward),
            }
        }

        async fn finish_delta(
            &self,
            peer: DeviceId,
            link: &Arc<QuicLink>,
            outcome: Option<Result<(), SyncError>>,
        ) -> Result<(), ()> {
            match outcome {
                None => Ok(()),
                Some(Ok(())) => {
                    let revision = {
                        let registries = self.incoming_registries.read().await;
                        registries
                            .get(&peer)
                            .map_or(RegistryRevision(0), |state| state.registry.applied_revision)
                    };
                    self.send_registry_ack(link, revision).await
                }
                Some(Err(SyncError::ResyncRequired { applied, .. })) => {
                    let request = RegistryResyncRequest {
                        applied_revision: applied.0,
                    };
                    let message = wire::RegistryResyncRequest::from(request);
                    self.send_wire(link, wire::MessageType::RegistryResyncRequest, &message)
                        .await
                }
                Some(Err(SyncError::UntrustedPeer)) => Err(()),
            }
        }

        async fn send_registry_ack(
            &self,
            link: &Arc<QuicLink>,
            revision: RegistryRevision,
        ) -> Result<(), ()> {
            let message = wire::RegistryAck::from(RegistryAck {
                revision: revision.0,
            });
            self.send_wire(link, wire::MessageType::RegistryAck, &message)
                .await
        }

        async fn send_registry_snapshot(
            &self,
            peer: DeviceId,
            link: &Arc<QuicLink>,
            send_lock: &Arc<Mutex<()>>,
        ) -> Result<(), ()> {
            let _guard = send_lock.lock().await;
            let _ = self.reconcile_outgoing_registry(peer).await?;
            let (revision, offers) = {
                let states = self.outgoing_registries.lock().await;
                let state = states.get(&peer).ok_or(())?;
                (
                    state.revision,
                    state.offers.values().cloned().collect::<Vec<_>>(),
                )
            };
            let begin = wire::RegistrySnapshotBegin::from(RegistrySnapshotBegin {
                revision: revision.0,
            });
            self.send_wire(link, wire::MessageType::RegistrySnapshotBegin, &begin)
                .await?;
            for offer in offers {
                let encoded_offer = serde_json::to_vec(&offer).map_err(|_| ())?;
                let upsert = wire::OfferUpsert::from(&OfferUpsert {
                    revision: revision.0,
                    encoded_offer,
                });
                self.send_wire(link, wire::MessageType::OfferUpsert, &upsert)
                    .await?;
            }
            let end = wire::RegistrySnapshotEnd::from(RegistrySnapshotEnd {
                revision: revision.0,
            });
            self.send_wire(link, wire::MessageType::RegistrySnapshotEnd, &end)
                .await
        }

        async fn reconcile_outgoing_registry(
            &self,
            peer: DeviceId,
        ) -> Result<Vec<OutgoingRegistryDelta>, ()> {
            let desired = self
                .registry
                .snapshot()
                .map_err(|_| ())?
                .offers
                .into_iter()
                .filter(|offer| offer.visibility != OfferVisibility::LocalOnly)
                .map(|offer| (offer.instance_id, offer))
                .collect::<BTreeMap<_, _>>();
            let mut states = self.outgoing_registries.lock().await;
            let state = states.entry(peer).or_default();
            let mut deltas = Vec::new();
            let removed = state
                .offers
                .keys()
                .filter(|id| !desired.contains_key(id))
                .copied()
                .collect::<Vec<_>>();
            for instance_id in removed {
                state.revision.0 = state.revision.0.checked_add(1).ok_or(())?;
                state.offers.remove(&instance_id);
                deltas.push(OutgoingRegistryDelta::Remove(OfferRemove {
                    revision: state.revision.0,
                    ability_instance_id: instance_id,
                }));
            }
            for (instance_id, offer) in desired {
                if state.offers.get(&instance_id) == Some(&offer) {
                    continue;
                }
                state.revision.0 = state.revision.0.checked_add(1).ok_or(())?;
                let encoded_offer = serde_json::to_vec(&offer).map_err(|_| ())?;
                state.offers.insert(instance_id, offer);
                deltas.push(OutgoingRegistryDelta::Upsert(OfferUpsert {
                    revision: state.revision.0,
                    encoded_offer,
                }));
            }
            Ok(deltas)
        }

        async fn send_wire<M: prost::Message>(
            &self,
            link: &Arc<QuicLink>,
            message_type: wire::MessageType,
            message: &M,
        ) -> Result<(), ()> {
            let encoded = self.encode_wire(message_type, message).map_err(|_| ())?;
            link.send_control(Bytes::from(encoded))
                .await
                .map_err(|_| ())
        }

        fn encode_wire<M: prost::Message>(
            &self,
            message_type: wire::MessageType,
            message: &M,
        ) -> Result<Vec<u8>, NetworkSessionError> {
            let request_id = self.request_id.fetch_add(1, Ordering::Relaxed);
            if request_id == u64::MAX {
                return Err(NetworkSessionError::InvalidControl);
            }
            ControlFrame::new(message_type, request_id, message)
                .map(|frame| frame.encode())
                .map_err(|_| NetworkSessionError::InvalidControl)
        }

        async fn send_to_peer<M: prost::Message>(
            &self,
            peer: DeviceId,
            message_type: wire::MessageType,
            message: &M,
        ) -> Result<(), NetworkSessionError> {
            let link = self
                .link(peer)
                .await
                .ok_or(NetworkSessionError::LinkUnavailable)?;
            let encoded = self.encode_wire(message_type, message)?;
            link.send_control(Bytes::from(encoded))
                .await
                .map_err(|_| NetworkSessionError::LinkUnavailable)
        }

        fn session_header(
            &self,
            session_id: SessionId,
            expected_epoch: u64,
        ) -> SessionControlHeader {
            let mut message_nonce = [0; MESSAGE_NONCE_BYTES];
            OsRng.fill_bytes(&mut message_nonce);
            SessionControlHeader {
                operation_id: OperationId::new(),
                session_id,
                expected_epoch,
                sender_device_id: self.endpoint.device_id(),
                message_nonce,
            }
        }

        async fn remove_if_current(&self, peer: DeviceId, link: &Arc<QuicLink>) {
            let removed = {
                let mut links = self.links.write().await;
                if links
                    .get(&peer)
                    .is_some_and(|current| Arc::ptr_eq(&current.link, link))
                {
                    links.remove(&peer);
                    true
                } else {
                    false
                }
            };
            if removed && let Some(state) = self.incoming_registries.write().await.get_mut(&peer) {
                state
                    .registry
                    .disconnected(now_ms(), PEER_REGISTRY_CACHE_TTL_MS);
            }
        }

        async fn close(&self) {
            self.endpoint.close();
            let links: Vec<_> = {
                let mut active = self.links.write().await;
                std::mem::take(&mut *active)
                    .into_values()
                    .map(|active| active.link)
                    .collect()
            };
            for link in links {
                link.close(LinkCloseReason::Normal).await;
            }
            self.endpoint.wait_idle().await;
        }

        pub async fn shutdown(&self) {
            self.close().await;
        }
    }

    fn coordinator_device(plan: &SessionPlan) -> Result<DeviceId, NetworkSessionError> {
        plan.participants
            .iter()
            .find(|participant| participant.id == plan.coordinator)
            .map(|participant| participant.device_id)
            .ok_or(NetworkSessionError::MissingParticipant)
    }

    fn participant_devices(plan: &SessionPlan, excluding: DeviceId) -> BTreeSet<DeviceId> {
        plan.participants
            .iter()
            .filter_map(|participant| {
                (participant.device_id != excluding).then_some(participant.device_id)
            })
            .collect()
    }

    fn participant_devices_including_local(plan: &SessionPlan) -> BTreeSet<DeviceId> {
        plan.participants
            .iter()
            .map(|participant| participant.device_id)
            .collect()
    }

    fn accept_device_participants(
        machine: &mut SessionMachine,
        plan: &SessionPlan,
        device: DeviceId,
        first_operation: Option<OperationId>,
    ) -> Result<(), NetworkSessionError> {
        let participants = plan
            .participants
            .iter()
            .filter(|participant| participant.device_id == device)
            .collect::<Vec<_>>();
        if participants.is_empty() {
            return Err(NetworkSessionError::MissingParticipant);
        }
        for (index, participant) in participants.into_iter().enumerate() {
            machine.apply(SessionEvent::Accept {
                operation_id: if index == 0 {
                    first_operation.unwrap_or_default()
                } else {
                    OperationId::new()
                },
                participant: participant.id,
            })?;
        }
        Ok(())
    }

    fn ready_device_participants(
        machine: &mut SessionMachine,
        plan: &SessionPlan,
        device: DeviceId,
        plan_hash: [u8; 32],
        first_operation: Option<OperationId>,
    ) -> Result<(), NetworkSessionError> {
        let participants = plan
            .participants
            .iter()
            .filter(|participant| participant.device_id == device)
            .collect::<Vec<_>>();
        if participants.is_empty() {
            return Err(NetworkSessionError::MissingParticipant);
        }
        for (index, participant) in participants.into_iter().enumerate() {
            machine.apply(SessionEvent::Ready {
                operation_id: if index == 0 {
                    first_operation.unwrap_or_default()
                } else {
                    OperationId::new()
                },
                participant: participant.id,
                plan_hash,
            })?;
        }
        Ok(())
    }

    #[cfg(feature = "clock")]
    pub(super) fn accepted_clock_samples(
        mut samples: Vec<ClockSample>,
        drop_max_rtt: usize,
    ) -> Vec<ClockSample> {
        samples.sort_by_key(|sample| sample.rtt_ns);
        let keep = samples.len().saturating_sub(drop_max_rtt);
        samples.truncate(keep.max(1));
        samples
    }

    #[cfg(feature = "clock")]
    fn i64_saturating_from_u64(value: u64) -> i64 {
        i64::try_from(value).unwrap_or(i64::MAX)
    }

    fn is_pairing_message(message: &ControlMessage) -> bool {
        matches!(
            message,
            ControlMessage::PairingStart(_)
                | ControlMessage::PairingChallenge(_)
                | ControlMessage::PairingConfirm(_)
                | ControlMessage::PairingComplete(_)
        )
    }

    pub struct QuicRuntime {
        services: Arc<LinkServices>,
        candidates: Option<Arc<CandidateStore>>,
        relay_trust: Option<Arc<dyn fabric_identity::DeviceTrustStore>>,
        accept_task: Option<JoinHandle<()>>,
        dial_task: Option<JoinHandle<()>>,
        relay_task: Option<JoinHandle<()>>,
    }

    impl QuicRuntime {
        #[must_use]
        pub fn new(services: Arc<LinkServices>, candidates: Option<Arc<CandidateStore>>) -> Self {
            Self {
                services,
                candidates,
                relay_trust: None,
                accept_task: None,
                dial_task: None,
                relay_task: None,
            }
        }

        /// Enables the relay dialer: trusted devices without an active link are
        /// periodically dialed through the relay (no-op when the endpoint was
        /// bound without one).
        #[must_use]
        pub fn with_relay_dialer(
            mut self,
            trust: Arc<dyn fabric_identity::DeviceTrustStore>,
        ) -> Self {
            self.relay_trust = Some(trust);
            self
        }
    }

    /// How often the relay dialer sweeps for trusted-but-unlinked devices.
    const RELAY_DIAL_INTERVAL: Duration = Duration::from_secs(10);
    /// Per-attempt timeout for a relay dial.
    const RELAY_DIAL_TIMEOUT: Duration = Duration::from_secs(8);

    /// One relay-dialer sweep: dial every trusted device that has no active
    /// link, and start a punch toward each so the path can upgrade to direct.
    pub(crate) async fn relay_dial_trusted(
        services: &Arc<LinkServices>,
        trust: &Arc<dyn fabric_identity::DeviceTrustStore>,
    ) {
        use fabric_identity::DeviceTrust;

        let own = services.endpoint.device_id();
        let entries = trust.entries().unwrap_or_default();
        for (device, level) in entries {
            if device == own || level != DeviceTrust::Trusted {
                continue;
            }
            if services.link(device).await.is_some() {
                continue;
            }
            let candidates =
                super::local_ipv4_address(services.endpoint.local_addr().map_or(0, |a| a.port()))
                    .into_iter()
                    .collect();
            let _ = tokio::time::timeout(
                RELAY_DIAL_TIMEOUT,
                services.connect_by_device_via_relay(device, candidates),
            )
            .await;
        }
    }

    #[async_trait]
    impl HubComponent for QuicRuntime {
        fn stage(&self) -> StartupStage {
            StartupStage::LinksReady
        }

        async fn start(&mut self) -> Result<(), String> {
            if self.accept_task.is_some() || self.dial_task.is_some() {
                return Err("QUIC runtime is already running".into());
            }
            let services = Arc::clone(&self.services);
            self.accept_task = Some(tokio::spawn(async move {
                loop {
                    match services.endpoint.accept().await {
                        Ok(result) => services.install(result, LinkDirection::Inbound).await,
                        Err(fabric_transport_quic::EndpointError::Closed) => break,
                        Err(error) => {
                            services.note_link_error(format!("accept: {error}")).await;
                            tokio::time::sleep(Duration::from_millis(50)).await;
                        }
                    }
                }
            }));
            if let Some(candidates) = self.candidates.clone() {
                let services = Arc::clone(&self.services);
                let trust = self.relay_trust.clone();
                self.dial_task = Some(tokio::spawn(async move {
                    dial_candidates(services, candidates, trust).await;
                }));
            }
            if let Some(trust) = self.relay_trust.clone()
                && self.services.has_relay()
            {
                let services = Arc::clone(&self.services);
                self.relay_task = Some(tokio::spawn(async move {
                    let mut interval = tokio::time::interval(RELAY_DIAL_INTERVAL);
                    loop {
                        interval.tick().await;
                        relay_dial_trusted(&services, &trust).await;
                    }
                }));
            }
            Ok(())
        }

        async fn shutdown(&mut self) -> Result<(), String> {
            self.services.close().await;
            if let Some(task) = self.accept_task.take() {
                task.abort();
                let _ = task.await;
            }
            if let Some(task) = self.dial_task.take() {
                task.abort();
                let _ = task.await;
            }
            if let Some(task) = self.relay_task.take() {
                task.abort();
                let _ = task.await;
            }
            Ok(())
        }
    }

    async fn dial_candidates(
        services: Arc<LinkServices>,
        candidates: Arc<CandidateStore>,
        trust: Option<Arc<dyn fabric_identity::DeviceTrustStore>>,
    ) {
        use fabric_discovery::{ConnectionHint, decode_quic_socket_hint};
        use fabric_identity::DeviceTrust;

        let mut changes = candidates.subscribe();
        let mut retry = tokio::time::interval(Duration::from_secs(2));
        let mut backoff = BTreeMap::<SocketAddr, tokio::time::Instant>::new();
        let mut first_seen = BTreeMap::<SocketAddr, tokio::time::Instant>::new();
        let mut connected = BTreeMap::<SocketAddr, DeviceId>::new();
        loop {
            tokio::select! {
                event = changes.changed() => {
                    if event.is_err() { break; }
                }
                _ = retry.tick() => {}
            }
            let Ok(values) = candidates.values() else {
                continue;
            };
            let now = tokio::time::Instant::now();
            let addresses = values
                .into_iter()
                .filter(|candidate| candidate.expires_at_ms > now_ms())
                .flat_map(|candidate| candidate.connection_hints)
                .filter_map(|hint| match hint {
                    ConnectionHint::Quic { host, port } => host
                        .parse::<std::net::IpAddr>()
                        .ok()
                        .map(|host| SocketAddr::new(host, port)),
                    ConnectionHint::Opaque(value) => decode_quic_socket_hint(&value),
                })
                .collect::<BTreeSet<_>>();
            first_seen.retain(|address, _| addresses.contains(address));
            for address in &addresses {
                first_seen.entry(*address).or_insert(now);
            }
            let trusted = trust
                .as_ref()
                .and_then(|store| store.entries().ok())
                .unwrap_or_default()
                .into_iter()
                .filter_map(|(device, level)| (level == DeviceTrust::Trusted).then_some(device))
                .collect::<Vec<_>>();
            connected.retain(|address, _| addresses.contains(address));
            for address in addresses {
                if let Some(peer) = connected.get(&address).copied() {
                    if services.link(peer).await.is_some() {
                        continue;
                    }
                    connected.remove(&address);
                }
                if backoff
                    .get(&address)
                    .is_some_and(|deadline| *deadline > now)
                {
                    continue;
                }
                let mut should_dial = false;
                for device in &trusted {
                    if services.link(*device).await.is_none()
                        && (services.endpoint.device_id() > *device
                            || first_seen.get(&address).is_some_and(|seen| {
                                now.duration_since(*seen) >= Duration::from_secs(12)
                            }))
                    {
                        should_dial = true;
                        break;
                    }
                }
                let mut peer = None;
                if should_dial {
                    if let Ok(Ok(discovered)) =
                        tokio::time::timeout(Duration::from_secs(20), services.connect(address))
                            .await
                    {
                        peer = Some(discovered);
                    }
                }
                if let Some(peer) = peer {
                    backoff.remove(&address);
                    connected.insert(address, peer);
                } else {
                    backoff.insert(address, now + Duration::from_secs(5));
                }
            }
            backoff.retain(|_, deadline| *deadline > now);
        }
    }
}

#[cfg(feature = "quic")]
pub use quic_runtime::*;

pub struct PlatformServices {
    pub ipc: Arc<HubIpcServer>,
    pub ipc_endpoint: std::path::PathBuf,
    pub candidates: Option<Arc<CandidateStore>>,
    #[cfg(feature = "quic")]
    pub links: Arc<LinkServices>,
}

#[cfg(feature = "quic")]
/// How long to wait for the configured relay before starting without it.
const RELAY_CONNECT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

#[cfg(feature = "quic")]
async fn build_link_services(
    hub: &Hub,
    trust_store: Arc<dyn DeviceTrustStore>,
) -> Result<Arc<LinkServices>, HubError> {
    let bind_address = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), hub.config().quic_port);
    let handshake = fabric_transport_quic::LinkHandshakeConfig::default();

    // A configured relay is attached at bind time; an unreachable relay must not
    // prevent hub startup (LAN operation continues), so fall back to plain bind.
    let relay_connection = match &hub.config().relay {
        Some(relay) => tokio::time::timeout(RELAY_CONNECT_TIMEOUT, async {
            match relay.tls_fingerprint {
                Some(fingerprint) => {
                    fabric_relay::RelayConnection::connect_tls(
                        relay.address,
                        fingerprint,
                        &hub.identity(),
                    )
                    .await
                }
                None => {
                    fabric_relay::RelayConnection::connect(relay.address, &hub.identity()).await
                }
            }
        })
        .await
        .ok()
        .and_then(Result::ok),
        None => None,
    };

    let endpoint = match relay_connection {
        Some(relay) => fabric_transport_quic::QuicEndpoint::bind_with_relay(
            bind_address,
            relay,
            hub.identity(),
            trust_store,
            handshake,
        ),
        None => fabric_transport_quic::QuicEndpoint::bind(
            bind_address,
            hub.identity(),
            trust_store,
            handshake,
        ),
    }
    .map_err(|error| HubError::Component(error.to_string()))?;

    LinkServices::with_registry(Arc::new(endpoint), hub.registry(), 1_024)
        .map_err(|error| HubError::Component(error.to_string()))
}

#[cfg(any(feature = "mdns", feature = "ble", feature = "wifi-aware"))]
fn build_discovery_runtime(hub: &Hub) -> (Arc<CandidateStore>, DiscoveryRuntime) {
    let candidates = Arc::new(CandidateStore::default());
    let mut backends: Vec<Arc<dyn DiscoveryBackend>> = Vec::new();
    let mut rotating_hint = [0; 16];
    let mut random = rand_core::OsRng;
    random.fill_bytes(&mut rotating_hint);

    #[cfg(feature = "mdns")]
    {
        use fabric_discovery_mdns::{MdnsAdvertisement, MdnsConfig, MdnsDiscovery};
        let id = hub.identity().device_id().0;
        let suffix = format!("{:02x}{:02x}{:02x}{:02x}", id[0], id[1], id[2], id[3]);
        backends.push(Arc::new(MdnsDiscovery::new(MdnsConfig {
            instance_name: format!("fabric-{suffix}"),
            host_name: format!("fabric-{suffix}.local."),
            advertisement: MdnsAdvertisement {
                protocol_version: 1,
                port: hub.config().quic_port,
                rotating_hint,
                label: hub.config().device_label.clone(),
                accepts_pairing: true,
                features: discovery_features(),
            },
            candidate_ttl_ms: 15_000,
            daemon_port: None,
        })));
    }

    #[cfg(all(feature = "ble", windows))]
    {
        use fabric_discovery::encode_quic_socket_hint;
        use fabric_discovery_ble::{BleAdvertisement, BleDiscovery, WindowsBlePlatform};
        let connection_hint =
            local_ipv4_address(hub.config().quic_port).map(encode_quic_socket_hint);
        backends.push(Arc::new(
            BleDiscovery::new(WindowsBlePlatform::default()).with_advertisement(BleAdvertisement {
                key: DiscoveryKey("local".into()),
                rotating_hint,
                label: hub.config().device_label.clone(),
                connection_hint,
                expires_at_ms: u64::MAX,
            }),
        ));
    }

    #[cfg(all(feature = "ble", target_os = "android"))]
    {
        use fabric_discovery::encode_quic_socket_hint;
        use fabric_discovery_ble::{AndroidBlePlatform, BleAdvertisement, BleDiscovery};
        let connection_hint =
            local_ipv4_address(hub.config().quic_port).map(encode_quic_socket_hint);
        backends.push(Arc::new(
            BleDiscovery::new(AndroidBlePlatform::default()).with_advertisement(BleAdvertisement {
                key: DiscoveryKey("local".into()),
                rotating_hint,
                label: hub.config().device_label.clone(),
                connection_hint,
                expires_at_ms: u64::MAX,
            }),
        ));
    }

    // Desktop is scan-only (btleplug has no peripheral role): it discovers phones
    // and Windows hubs that advertise, but does not advertise itself.
    #[cfg(all(feature = "ble", any(target_os = "macos", target_os = "linux")))]
    {
        use fabric_discovery_ble::{BleDiscovery, DesktopBlePlatform};
        backends.push(Arc::new(BleDiscovery::new(DesktopBlePlatform::default())));
    }

    #[cfg(all(feature = "wifi-aware", target_os = "android"))]
    {
        use fabric_discovery::encode_quic_socket_hint;
        use fabric_discovery_wifi_aware::{
            AndroidWifiAwarePlatform, WifiAwareAdvertisement, WifiAwareDiscovery,
        };
        let connection_hint =
            local_ipv4_address(hub.config().quic_port).map(encode_quic_socket_hint);
        backends.push(Arc::new(
            WifiAwareDiscovery::new(AndroidWifiAwarePlatform::default()).with_advertisement(
                WifiAwareAdvertisement {
                    key: DiscoveryKey("local".into()),
                    rotating_hint,
                    label: hub.config().device_label.clone(),
                    connection_hint,
                    expires_at_ms: u64::MAX,
                },
            ),
        ));
    }

    (
        Arc::clone(&candidates),
        DiscoveryRuntime::new(backends, candidates),
    )
}

#[cfg(any(
    feature = "quic",
    all(feature = "ble", any(windows, target_os = "android")),
    all(feature = "wifi-aware", target_os = "android")
))]
fn local_ipv4_address(port: u16) -> Option<SocketAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    let local = socket.local_addr().ok()?;
    Some(SocketAddr::new(local.ip(), port))
}

#[cfg(any(feature = "mdns", feature = "ble", feature = "wifi-aware"))]
fn discovery_features() -> String {
    let mut features = vec!["q"];
    if cfg!(all(feature = "ble", any(windows, target_os = "android"))) {
        features.push("b");
    }
    if cfg!(all(feature = "wifi-aware", target_os = "android")) {
        features.push("w");
    }
    features.join(",")
}

impl Hub {
    pub async fn start_platform_services(
        &mut self,
        credentials: Arc<dyn OsCredentialAdapter>,
        trust_store: Arc<dyn DeviceTrustStore>,
    ) -> Result<PlatformServices, HubError> {
        self.start_platform_services_inner(credentials, trust_store, true)
            .await
    }

    /// Starts platform services without exposing the local socket/named-pipe
    /// listener. Embedded bridges can use the returned IPC server directly.
    pub async fn start_embedded_platform_services(
        &mut self,
        credentials: Arc<dyn OsCredentialAdapter>,
        trust_store: Arc<dyn DeviceTrustStore>,
    ) -> Result<PlatformServices, HubError> {
        self.start_platform_services_inner(credentials, trust_store, false)
            .await
    }

    async fn start_platform_services_inner(
        &mut self,
        credentials: Arc<dyn OsCredentialAdapter>,
        trust_store: Arc<dyn DeviceTrustStore>,
        expose_local_ipc: bool,
    ) -> Result<PlatformServices, HubError> {
        let registry = self.registry();
        let ipc = Arc::new(IpcServer::new(
            SharedCredentialAdapter::new(credentials),
            Arc::clone(&registry),
        ));
        let device_id = self.identity().device_id().0;
        let device_tag = format!(
            "{:02x}{:02x}{:02x}{:02x}",
            device_id[0], device_id[1], device_id[2], device_id[3]
        );
        let state_directory = self
            .config()
            .database_path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or_else(|| std::path::Path::new("."));
        let ipc_endpoint = default_local_ipc_endpoint(&device_tag, state_directory);
        let mut quota = ConnectionQuota::default();
        quota.maximum_sessions = quota
            .maximum_sessions
            .min(usize::try_from(self.config().maximum_sessions).unwrap_or(usize::MAX));
        quota.maximum_buffered_bytes = quota
            .maximum_buffered_bytes
            .min(self.config().maximum_buffered_bytes);
        let listener = expose_local_ipc
            .then(|| {
                LocalIpcListener::bind(
                    ipc_endpoint.clone(),
                    Arc::clone(&ipc),
                    quota,
                    usize::try_from(self.config().maximum_ipc_connections).unwrap_or(usize::MAX),
                )
            })
            .transpose()
            .map_err(|error| HubError::Component(error.to_string()))?;

        #[cfg(feature = "quic")]
        let links = build_link_services(self, Arc::clone(&trust_store)).await?;
        #[cfg(not(feature = "quic"))]
        let _ = trust_store;

        let ipc_runtime = listener.map_or_else(
            || IpcRuntime::embedded(Arc::clone(&ipc), Arc::clone(&registry)),
            |listener| IpcRuntime::new(Arc::clone(&ipc), Arc::clone(&registry), listener),
        );
        if let Err(error) = self.attach_component(Box::new(ipc_runtime)).await {
            let _ = self.shutdown().await;
            return Err(error);
        }

        #[cfg(any(feature = "mdns", feature = "ble", feature = "wifi-aware"))]
        let candidates = {
            let (candidates, runtime) = build_discovery_runtime(self);
            if let Err(error) = self.attach_component(Box::new(runtime)).await {
                let _ = self.shutdown().await;
                return Err(error);
            }
            Some(candidates)
        };
        #[cfg(not(any(feature = "mdns", feature = "ble", feature = "wifi-aware")))]
        let candidates = None;

        #[cfg(feature = "quic")]
        if let Err(error) = self
            .attach_component(Box::new(
                QuicRuntime::new(Arc::clone(&links), candidates.clone())
                    .with_relay_dialer(trust_store),
            ))
            .await
        {
            let _ = self.shutdown().await;
            return Err(error);
        }

        Ok(PlatformServices {
            ipc,
            ipc_endpoint,
            candidates,
            #[cfg(feature = "quic")]
            links,
        })
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct RejectingCredentials;

    impl OsCredentialAdapter for RejectingCredentials {
        fn authenticate(
            &self,
            _credentials: &PeerCredentials,
        ) -> Result<AppPrincipal, IdentityError> {
            Err(IdentityError::AppIdentityMismatch)
        }
    }

    #[tokio::test]
    async fn embedded_ipc_runtime_starts_without_a_local_listener() {
        let registry = Arc::new(Registry::new());
        let server = Arc::new(IpcServer::new(
            SharedCredentialAdapter::new(Arc::new(RejectingCredentials)),
            Arc::clone(&registry),
        ));
        let mut runtime = IpcRuntime::embedded(server, registry);

        runtime.start().await.unwrap();
        assert!(runtime.listener_task.is_none());
        assert!(runtime.maintenance_task.is_some());
        runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    fn endpoint(
        seed: u8,
    ) -> (
        Arc<fabric_transport_quic::QuicEndpoint>,
        Arc<fabric_identity::DeviceIdentity>,
        Arc<fabric_identity::MemoryDeviceTrustStore>,
    ) {
        use fabric_transport_quic::{LinkHandshakeConfig, QuicEndpoint};

        let identity = Arc::new(fabric_identity::DeviceIdentity::from_seed([seed; 32]));
        let trust = Arc::new(fabric_identity::MemoryDeviceTrustStore::default());
        let endpoint = Arc::new(
            QuicEndpoint::bind(
                "127.0.0.1:0".parse().unwrap(),
                Arc::clone(&identity),
                trust.clone(),
                LinkHandshakeConfig::default(),
            )
            .unwrap(),
        );
        (endpoint, identity, trust)
    }

    #[cfg(feature = "quic")]
    async fn wait_for_link_count(services: &LinkServices, expected: usize) {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if services.active_links().await == expected {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn relay_dialer_links_trusted_devices_across_the_relay() {
        use bytes::Bytes;
        use fabric_identity::{DeviceTrust, DeviceTrustStore};
        use fabric_link::FabricLink;
        use fabric_protocol::{ControlFrame, RegistryAck, wire};
        use fabric_relay::{RelayConnection, RelayServer};
        use fabric_transport_quic::{LinkHandshakeConfig, QuicEndpoint};

        // A live relay; neither hub knows the other's transport address.
        let relay = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let relay_addr = relay.local_addr();

        let a_identity = Arc::new(fabric_identity::DeviceIdentity::from_seed([61; 32]));
        let b_identity = Arc::new(fabric_identity::DeviceIdentity::from_seed([62; 32]));
        let a_trust = Arc::new(fabric_identity::MemoryDeviceTrustStore::default());
        let b_trust = Arc::new(fabric_identity::MemoryDeviceTrustStore::default());
        a_trust
            .set_trust(b_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        b_trust
            .set_trust(a_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();

        let a_relay = RelayConnection::connect(relay_addr, &a_identity)
            .await
            .unwrap();
        let b_relay = RelayConnection::connect(relay_addr, &b_identity)
            .await
            .unwrap();
        let a_endpoint = Arc::new(
            QuicEndpoint::bind_with_relay(
                "127.0.0.1:0".parse().unwrap(),
                a_relay,
                Arc::clone(&a_identity),
                a_trust.clone(),
                LinkHandshakeConfig::default(),
            )
            .unwrap(),
        );
        let b_endpoint = Arc::new(
            QuicEndpoint::bind_with_relay(
                "127.0.0.1:0".parse().unwrap(),
                b_relay,
                Arc::clone(&b_identity),
                b_trust.clone(),
                LinkHandshakeConfig::default(),
            )
            .unwrap(),
        );
        let a = LinkServices::new(a_endpoint, 8).unwrap();
        let b = LinkServices::new(b_endpoint, 8).unwrap();
        assert!(a.has_relay() && b.has_relay());

        // B accepts through its runtime; A runs one relay-dialer sweep.
        let mut b_runtime = QuicRuntime::new(Arc::clone(&b), None);
        b_runtime.start().await.unwrap();
        let a_trust_dyn: Arc<dyn DeviceTrustStore> = a_trust.clone();
        relay_dial_trusted(&a, &a_trust_dyn).await;

        wait_for_link_count(&a, 1).await;
        wait_for_link_count(&b, 1).await;

        // Control traffic flows across the relayed hub link.
        let link = a.link(b_identity.device_id()).await.unwrap();
        let encoded = ControlFrame::new(
            wire::MessageType::RegistryAck,
            21,
            &wire::RegistryAck::from(RegistryAck { revision: 3 }),
        )
        .unwrap()
        .encode();
        link.send_control(Bytes::from(encoded)).await.unwrap();
        let event = tokio::time::timeout(Duration::from_secs(5), b.next_control())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(event.peer, a_identity.device_id());

        // A second sweep is a no-op: the link exists, nothing re-dials.
        relay_dial_trusted(&a, &a_trust_dyn).await;
        assert_eq!(a.active_links().await, 1);

        b_runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    fn candidate(key: &str, address: SocketAddr) -> PeerCandidate {
        use fabric_discovery::{ConnectionHint, DiscoverySource};

        PeerCandidate {
            discovery_key: DiscoveryKey(key.into()),
            label: "test peer".into(),
            connection_hints: vec![ConnectionHint::Quic {
                host: address.ip().to_string(),
                port: address.port(),
            }],
            device_hint: Some([1; 16]),
            proximity: None,
            sources: vec![DiscoverySource::Mdns],
            expires_at_ms: now_ms().saturating_add(30_000),
        }
    }

    #[cfg(all(feature = "quic", feature = "clock"))]
    #[test]
    fn clock_burst_filter_drops_highest_rtt_outliers() {
        let sample = |rtt_ns| fabric_clock::ClockSample {
            t1: 0,
            t2: 0,
            t3: 0,
            t4: rtt_ns,
            rtt_ns,
            offset_ns: 0,
        };
        let accepted = super::quic_runtime::accepted_clock_samples(
            vec![
                sample(100),
                sample(10_000),
                sample(75),
                sample(50),
                sample(9_000),
            ],
            2,
        );

        assert_eq!(
            accepted
                .into_iter()
                .map(|item| item.rtt_ns)
                .collect::<Vec<_>>(),
            vec![50, 75, 100]
        );
    }

    #[cfg(feature = "quic")]
    fn offer(visibility: fabric_core::OfferVisibility) -> fabric_core::AbilityOffer {
        use fabric_core::{
            AbilityContractRef, AbilityKey, AbilityName, AppPrincipal, LeaseSpec, Namespace,
            OsSubject, Platform, PolicyRef, PropertyMap, RoleId,
        };

        fabric_core::AbilityOffer {
            instance_id: fabric_core::AbilityInstanceId::new(),
            contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: [3; 32],
            },
            app: AppPrincipal {
                platform: Platform::Test,
                stable_app_id: "test.registry".into(),
                publisher_id: None,
                signing_digest: None,
                os_subject: OsSubject("test:registry".into()),
            },
            roles: vec![RoleId::new("provider").unwrap()],
            properties: PropertyMap::new(),
            visibility,
            access_policy: PolicyRef("default".into()),
            lease: LeaseSpec::ConnectionBound,
        }
    }

    #[cfg(feature = "quic")]
    fn network_plan(
        coordinator: fabric_core::DeviceId,
        participant: fabric_core::DeviceId,
    ) -> fabric_core::SessionPlan {
        use fabric_core::{AppPrincipal, Participant, PolicySnapshotId, RoleId, SessionExtensions};

        let coordinator_id = fabric_core::ParticipantId::new();
        let participant_id = fabric_core::ParticipantId::new();
        let template = offer(fabric_core::OfferVisibility::TrustedDevices);
        let app = |id: &str| AppPrincipal {
            stable_app_id: id.into(),
            ..template.app.clone()
        };
        fabric_core::SessionPlan {
            session_id: fabric_core::SessionId::new(),
            contract: template.contract,
            epoch: 1,
            coordinator: coordinator_id,
            participants: vec![
                Participant {
                    id: coordinator_id,
                    device_id: coordinator,
                    app_principal: app("test.coordinator"),
                    ability_instance: fabric_core::AbilityInstanceId::new(),
                    role: RoleId::new("coordinator").unwrap(),
                    port_bindings: Vec::new(),
                },
                Participant {
                    id: participant_id,
                    device_id: participant,
                    app_principal: app("test.participant"),
                    ability_instance: fabric_core::AbilityInstanceId::new(),
                    role: RoleId::new("participant").unwrap(),
                    port_bindings: Vec::new(),
                },
            ],
            channels: Vec::new(),
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        }
    }

    #[tokio::test]
    async fn candidate_store_applies_removals() {
        use fabric_discovery::{ConnectionHint, DiscoverySource};

        let store = CandidateStore::default();
        let key = DiscoveryKey("peer".into());
        store
            .upsert(PeerCandidate {
                discovery_key: key.clone(),
                label: "test peer".into(),
                connection_hints: vec![ConnectionHint::Quic {
                    host: "127.0.0.1".into(),
                    port: 44_330,
                }],
                device_hint: Some([1; 16]),
                proximity: None,
                sources: vec![DiscoverySource::Mdns],
                expires_at_ms: 10,
            })
            .await
            .unwrap();
        assert_eq!(store.values().unwrap().len(), 1);
        store.remove(&key).await.unwrap();
        assert!(store.values().unwrap().is_empty());
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn quic_runtime_delivers_authenticated_control_messages() {
        use bytes::Bytes;
        use fabric_identity::{DeviceTrust, DeviceTrustStore};
        use fabric_link::FabricLink;
        use fabric_protocol::{ControlFrame, ControlMessage, RegistryAck, wire};

        let (server_endpoint, server_identity, server_trust) = endpoint(21);
        let (client_endpoint, client_identity, client_trust) = endpoint(22);
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        let server = LinkServices::new(server_endpoint, 8).unwrap();
        let client = LinkServices::new(client_endpoint, 8).unwrap();
        let mut runtime = QuicRuntime::new(Arc::clone(&server), None);
        runtime.start().await.unwrap();
        client.connect(server.local_addr().unwrap()).await.unwrap();
        let link = client.link(server_identity.device_id()).await.unwrap();
        let encoded = ControlFrame::new(
            wire::MessageType::RegistryAck,
            7,
            &wire::RegistryAck::from(RegistryAck { revision: 9 }),
        )
        .unwrap()
        .encode();
        link.send_control(Bytes::from(encoded)).await.unwrap();

        let event = tokio::time::timeout(Duration::from_secs(2), server.next_control())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(event.peer, client_identity.device_id());
        assert_eq!(event.trust, DeviceTrust::Trusted);
        assert!(matches!(
            event.message,
            ControlMessage::RegistryAck(RegistryAck { revision: 9 })
        ));

        client.shutdown().await;
        runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn discovery_candidates_trigger_authenticated_quic_dial() {
        use fabric_identity::{DeviceTrust, DeviceTrustStore};

        let (server_endpoint, server_identity, server_trust) = endpoint(31);
        let (client_endpoint, client_identity, client_trust) = endpoint(32);
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        let server = LinkServices::new(server_endpoint, 8).unwrap();
        let client = LinkServices::new(client_endpoint, 8).unwrap();
        let candidates = Arc::new(CandidateStore::default());
        let mut server_runtime = QuicRuntime::new(Arc::clone(&server), None);
        let mut client_runtime =
            QuicRuntime::new(Arc::clone(&client), Some(Arc::clone(&candidates)));
        server_runtime.start().await.unwrap();
        client_runtime.start().await.unwrap();

        candidates
            .upsert(candidate("server", server.local_addr().unwrap()))
            .await
            .unwrap();
        wait_for_link_count(&server, 1).await;
        wait_for_link_count(&client, 1).await;
        assert!(client.link(server_identity.device_id()).await.is_some());
        assert!(server.link(client_identity.device_id()).await.is_some());

        client_runtime.shutdown().await.unwrap();
        server_runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn trusted_peer_registry_sync_filters_resyncs_and_propagates_removal() {
        use bytes::Bytes;
        use fabric_core::{ConnectionId, OfferVisibility};
        use fabric_identity::{DeviceTrust, DeviceTrustStore};
        use fabric_link::FabricLink;
        use fabric_protocol::{ControlFrame, ControlMessage, OfferRemove, wire};

        let (server_endpoint, server_identity, server_trust) = endpoint(35);
        let (client_endpoint, client_identity, client_trust) = endpoint(36);
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();

        let owner = ConnectionId::new();
        let visible = offer(OfferVisibility::TrustedDevices);
        let local_only = offer(OfferVisibility::LocalOnly);
        let server_registry = Arc::new(Registry::new());
        server_registry
            .upsert_offer(owner, visible.clone(), now_ms())
            .unwrap();
        server_registry
            .upsert_offer(owner, local_only, now_ms())
            .unwrap();
        let server =
            LinkServices::with_registry(server_endpoint, Arc::clone(&server_registry), 16).unwrap();
        let client =
            LinkServices::with_registry(client_endpoint, Arc::new(Registry::new()), 16).unwrap();
        let mut server_runtime = QuicRuntime::new(Arc::clone(&server), None);
        let mut client_runtime = QuicRuntime::new(Arc::clone(&client), None);
        server_runtime.start().await.unwrap();
        client_runtime.start().await.unwrap();
        client.connect(server.local_addr().unwrap()).await.unwrap();

        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if client.peer_offers(server_identity.device_id()).await == vec![visible.clone()] {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();

        let link = server.link(client_identity.device_id()).await.unwrap();
        let gap = OfferRemove {
            revision: 100,
            ability_instance_id: fabric_core::AbilityInstanceId::new(),
        };
        let encoded = ControlFrame::new(
            wire::MessageType::OfferRemove,
            100,
            &wire::OfferRemove::from(&gap),
        )
        .unwrap()
        .encode();
        link.send_control(Bytes::from(encoded)).await.unwrap();
        let applied_revision = tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                let event = server.next_control().await.unwrap();
                if let ControlMessage::RegistryResyncRequest(request) = event.message {
                    break request.applied_revision;
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(applied_revision, 1);
        assert_eq!(
            client.peer_offers(server_identity.device_id()).await,
            vec![visible]
        );

        server_registry.disconnect(owner).unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if client
                    .peer_offers(server_identity.device_id())
                    .await
                    .is_empty()
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();

        client_runtime.shutdown().await.unwrap();
        server_runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn two_hubs_complete_network_session_over_real_quic() {
        use bytes::Bytes;
        use fabric_identity::{DeviceTrust, DeviceTrustStore};
        use fabric_link::FabricLink;
        use fabric_protocol::{ControlFrame, ControlMessage, MESSAGE_NONCE_BYTES, wire};

        let (left_endpoint, left_identity, left_trust) = endpoint(37);
        let (right_endpoint, right_identity, right_trust) = endpoint(38);
        left_trust
            .set_trust(right_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        right_trust
            .set_trust(left_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        let left = LinkServices::new(left_endpoint, 32).unwrap();
        let right = LinkServices::new(right_endpoint, 32).unwrap();
        let mut left_runtime = QuicRuntime::new(Arc::clone(&left), None);
        let mut right_runtime = QuicRuntime::new(Arc::clone(&right), None);
        left_runtime.start().await.unwrap();
        right_runtime.start().await.unwrap();
        left.connect(right.local_addr().unwrap()).await.unwrap();

        let plan = network_plan(left_identity.device_id(), right_identity.device_id());
        let session_id = left.propose_network_session(plan).await.unwrap();
        tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if right.network_session_state(session_id).await
                    == Some(fabric_core::SessionState::Negotiating)
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await
        .unwrap();
        right.accept_network_session(session_id).await.unwrap();
        let completion = tokio::time::timeout(Duration::from_secs(3), async {
            loop {
                if left.network_session_state(session_id).await
                    == Some(fabric_core::SessionState::Active)
                    && right.network_session_state(session_id).await
                        == Some(fabric_core::SessionState::Active)
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await;
        assert!(
            completion.is_ok(),
            "session stalled: left={:?}, right={:?}, left_links={}, right_links={}",
            left.network_session_state(session_id).await,
            right.network_session_state(session_id).await,
            left.active_links().await,
            right.active_links().await
        );

        let mut accepted = tokio::time::timeout(Duration::from_secs(2), async {
            loop {
                let event = left.next_control().await.unwrap();
                if let ControlMessage::SessionAccept(value) = event.message {
                    break value;
                }
            }
        })
        .await
        .unwrap();
        accepted.header.message_nonce = [99; MESSAGE_NONCE_BYTES];
        let retry = ControlFrame::new(
            wire::MessageType::SessionAccept,
            999,
            &wire::SessionAccept::from(&accepted),
        )
        .unwrap()
        .encode();
        right
            .link(left_identity.device_id())
            .await
            .unwrap()
            .send_control(Bytes::from(retry))
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert_eq!(
            left.network_session_state(session_id).await,
            Some(fabric_core::SessionState::Active)
        );
        assert_eq!(left.active_links().await, 1);
        assert_eq!(right.active_links().await, 1);

        left_runtime.shutdown().await.unwrap();
        right_runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn simultaneous_dials_converge_on_deterministic_link_direction() {
        use fabric_identity::{DeviceTrust, DeviceTrustStore};
        use fabric_transport_quic::retain_outbound;

        let (left_endpoint, left_identity, left_trust) = endpoint(41);
        let (right_endpoint, right_identity, right_trust) = endpoint(42);
        left_trust
            .set_trust(right_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        right_trust
            .set_trust(left_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        let left = LinkServices::new(left_endpoint, 8).unwrap();
        let right = LinkServices::new(right_endpoint, 8).unwrap();
        let left_candidates = Arc::new(CandidateStore::default());
        let right_candidates = Arc::new(CandidateStore::default());
        let mut left_runtime =
            QuicRuntime::new(Arc::clone(&left), Some(Arc::clone(&left_candidates)));
        let mut right_runtime =
            QuicRuntime::new(Arc::clone(&right), Some(Arc::clone(&right_candidates)));
        left_runtime.start().await.unwrap();
        right_runtime.start().await.unwrap();

        left_candidates
            .upsert(candidate("right", right.local_addr().unwrap()))
            .await
            .unwrap();
        right_candidates
            .upsert(candidate("left", left.local_addr().unwrap()))
            .await
            .unwrap();
        wait_for_link_count(&left, 1).await;
        wait_for_link_count(&right, 1).await;
        tokio::time::sleep(Duration::from_millis(300)).await;

        assert_eq!(left.active_links().await, 1);
        assert_eq!(right.active_links().await, 1);
        assert_eq!(
            left.retained_outbound(right_identity.device_id()).await,
            Some(retain_outbound(
                left_identity.device_id(),
                right_identity.device_id()
            ))
        );
        assert_eq!(
            right.retained_outbound(left_identity.device_id()).await,
            Some(retain_outbound(
                right_identity.device_id(),
                left_identity.device_id()
            ))
        );

        left_runtime.shutdown().await.unwrap();
        right_runtime.shutdown().await.unwrap();
    }

    #[cfg(feature = "quic")]
    #[tokio::test]
    async fn untrusted_peer_is_limited_to_pairing_control() {
        use bytes::Bytes;
        use fabric_link::FabricLink;
        use fabric_protocol::{
            ControlFrame, ControlMessage, DEVICE_PROOF_BYTES, MESSAGE_NONCE_BYTES, PairingStart,
            RegistryAck, wire,
        };

        let (server_endpoint, server_identity, _server_trust) = endpoint(51);
        let (client_endpoint, client_identity, _client_trust) = endpoint(52);
        let server = LinkServices::new(server_endpoint, 8).unwrap();
        let client = LinkServices::new(client_endpoint, 8).unwrap();
        let mut runtime = QuicRuntime::new(Arc::clone(&server), None);
        runtime.start().await.unwrap();
        client.connect(server.local_addr().unwrap()).await.unwrap();
        let link = client.link(server_identity.device_id()).await.unwrap();

        let pairing = PairingStart {
            nonce: [7; MESSAGE_NONCE_BYTES],
            device_proof: [8; DEVICE_PROOF_BYTES],
            device_label: "test peer".into(),
        };
        let encoded = ControlFrame::new(
            wire::MessageType::PairingStart,
            7,
            &wire::PairingStart::from(&pairing),
        )
        .unwrap()
        .encode();
        link.send_control(Bytes::from(encoded)).await.unwrap();
        let event = tokio::time::timeout(Duration::from_secs(2), server.next_control())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(event.peer, client_identity.device_id());
        assert!(matches!(event.message, ControlMessage::PairingStart(value) if value == pairing));

        let encoded = ControlFrame::new(
            wire::MessageType::RegistryAck,
            8,
            &wire::RegistryAck::from(RegistryAck { revision: 1 }),
        )
        .unwrap()
        .encode();
        link.send_control(Bytes::from(encoded)).await.unwrap();
        assert!(
            tokio::time::timeout(Duration::from_millis(200), server.next_control())
                .await
                .is_err()
        );
        wait_for_link_count(&server, 0).await;

        client.shutdown().await;
        runtime.shutdown().await.unwrap();
    }
}
