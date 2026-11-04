// Included inside quic_runtime: all admission uses its private committed sessions.
use fabric_core::{CapabilityGrantId, ChannelId, ConnectionId, ParticipantId, PortId};
use fabric_platform::{AuthorizedMessageChannel, QuicMessageTransport, ReliableMessageTransport};
use fabric_policy::{CapabilityAction, PolicyEngine};
use fabric_sdk::{FabricError, MessagePort, ReceivedMessage};
use std::sync::Mutex as PolicyMutex;

/// Local authority comes from the live IPC connection and a Hub-issued grant.
/// The caller cannot supply a SessionPlan or an application principal here.
#[derive(Clone)]
pub struct MessagePortRequest {
    pub connection: ConnectionId,
    pub session: SessionId,
    pub channel: ChannelId,
    pub participant: ParticipantId,
    pub port: PortId,
    pub grant: CapabilityGrantId,
    pub peer: DeviceId,
}

struct MessageAdmission {
    services: Arc<LinkServices>,
    ipc: Arc<crate::HubIpcServer>,
    policy: Arc<PolicyMutex<PolicyEngine>>,
    request: MessagePortRequest,
    plan_hash: [u8; 32],
}

impl MessageAdmission {
    async fn check(&self, action: CapabilityAction) -> Result<SessionPlan, FabricError> {
        let sessions = self.services.sessions.lock().await;
        let session = sessions
            .get(&self.request.session)
            .ok_or(FabricError::StaleSession)?;
        if session.machine.state() != SessionState::Active {
            return Err(FabricError::StaleSession);
        }
        let plan = session.machine.plan();
        if plan.canonical_hash() != self.plan_hash {
            return Err(FabricError::StaleSession);
        }
        let participant = plan
            .participants
            .iter()
            .find(|p| {
                p.id == self.request.participant && p.device_id == self.services.local_device_id()
            })
            .ok_or(FabricError::PermissionDenied)?;
        self.ipc
            .authorize_participant(self.request.connection, participant)
            .map_err(|_| FabricError::PermissionDenied)?;
        let channel = plan
            .channels
            .iter()
            .find(|c| c.id == self.request.channel)
            .ok_or(FabricError::PermissionDenied)?;
        let is_source = channel
            .sources
            .iter()
            .any(|p| p.participant == participant.id && p.port == self.request.port);
        let is_destination = channel
            .destinations
            .iter()
            .any(|p| p.participant == participant.id && p.port == self.request.port);
        if !participant
            .port_bindings
            .iter()
            .any(|p| p.id == self.request.port)
            || !(match action {
                CapabilityAction::Open => is_source || is_destination,
                CapabilityAction::Send => is_source,
                CapabilityAction::Receive => is_destination,
            })
        {
            return Err(FabricError::PermissionDenied);
        }
        let policy = self
            .policy
            .lock()
            .map_err(|_| FabricError::PermissionDenied)?;
        let grant = policy
            .verify_grant(
                self.request.grant,
                self.request.port,
                action,
                plan.epoch,
                now_ms(),
            )
            .map_err(|_| FabricError::PermissionDenied)?;
        if grant.session_id != plan.session_id
            || grant.participant_id != participant.id
            || grant.policy_snapshot != plan.policy_snapshot
            || grant.maximum_buffered_bytes < channel.contract.max_buffered_bytes
        {
            return Err(FabricError::PermissionDenied);
        }
        Ok(plan.clone())
    }
}

struct HubMessagePort {
    admission: MessageAdmission,
    transport: Arc<dyn ReliableMessageTransport>,
    destinations: Vec<ParticipantId>,
    _listener: Option<MessageListenerLease>,
}

struct MessageListenerLease {
    services: Arc<LinkServices>,
    key: (SessionId, ChannelId, DeviceId),
    sender: mpsc::Sender<IncomingStream>,
    _receiver: mpsc::Receiver<IncomingStream>,
}

impl Drop for MessageListenerLease {
    fn drop(&mut self) {
        let services = Arc::clone(&self.services);
        let key = self.key;
        let sender = self.sender.clone();
        tokio::spawn(async move {
            let mut listeners = services.message_listeners.lock().await;
            if listeners
                .get(&key)
                .is_some_and(|current| current.same_channel(&sender))
            {
                listeners.remove(&key);
            }
        });
    }
}

#[async_trait]
impl MessagePort for HubMessagePort {
    async fn send(&self, payload: Bytes) -> Result<(), FabricError> {
        self.admission.check(CapabilityAction::Send).await?;
        if self.destinations.is_empty() {
            return Err(FabricError::PermissionDenied);
        }
        // A directed peer port fans out only to that peer's contracted destinations.
        // A failure may follow earlier successful destinations; applications retain
        // their request IDs for retry/idempotency.
        for destination in &self.destinations {
            self.transport
                .send(*destination, payload.clone())
                .await
                .map_err(message_error)?;
        }
        Ok(())
    }

    async fn receive(&self) -> Result<ReceivedMessage, FabricError> {
        self.admission.check(CapabilityAction::Receive).await?;
        let message = self.transport.recv().await.map_err(message_error)?;
        self.admission.check(CapabilityAction::Receive).await?;
        if message.destination != self.admission.request.participant {
            return Err(FabricError::PermissionDenied);
        }
        Ok(ReceivedMessage {
            sender: message.sender,
            port: self.admission.request.port,
            payload: message.payload,
        })
    }
}

impl LinkServices {
    async fn admit_message_port(
        self: &Arc<Self>,
        ipc: Arc<crate::HubIpcServer>,
        policy: Arc<PolicyMutex<PolicyEngine>>,
        request: MessagePortRequest,
    ) -> Result<
        (
            MessageAdmission,
            AuthorizedMessageChannel,
            Vec<ParticipantId>,
        ),
        FabricError,
    > {
        let plan_hash = self
            .sessions
            .lock()
            .await
            .get(&request.session)
            .ok_or(FabricError::StaleSession)?
            .machine
            .plan()
            .canonical_hash();
        let admission = MessageAdmission {
            services: Arc::clone(self),
            ipc,
            policy,
            request,
            plan_hash,
        };
        let plan = admission.check(CapabilityAction::Open).await?;
        let request = &admission.request;
        let channel = AuthorizedMessageChannel::from_plan(
            &plan,
            request.channel,
            self.local_device_id(),
            request.participant,
            request.peer,
        )
        .map_err(message_error)?;
        let destinations = plan
            .channels
            .iter()
            .find(|c| c.id == request.channel)
            .ok_or(FabricError::PermissionDenied)?
            .destinations
            .iter()
            .filter(|e| {
                plan.participants
                    .iter()
                    .any(|p| p.id == e.participant && p.device_id == request.peer)
            })
            .map(|e| e.participant)
            .collect();
        Ok((admission, channel, destinations))
    }

    /// Open a directed peer SDK message port on an active, authenticated Session.
    /// Start the accepting peer first (`initiate = false`), then the initiating
    /// peer. Both operations are bounded by 10 seconds. One port per peer/channel
    /// is supported; multi-device routing opens one directed port for each peer.
    pub async fn open_message_port(
        self: &Arc<Self>,
        ipc: Arc<crate::HubIpcServer>,
        policy: Arc<PolicyMutex<PolicyEngine>>,
        request: MessagePortRequest,
        initiate: bool,
    ) -> Result<Box<dyn MessagePort>, FabricError> {
        let (admission, channel, destinations) =
            self.admit_message_port(ipc, policy, request).await?;
        let peer = admission.request.peer;
        let link = {
            let links = self.links.read().await;
            let active = links.get(&peer).ok_or(FabricError::Disconnected)?;
            if active.trust != DeviceTrust::Trusted {
                return Err(FabricError::PermissionDenied);
            }
            Arc::clone(&active.link)
        };
        let key = (admission.request.session, admission.request.channel, peer);
        let (tx, rx) = mpsc::channel(1);
        {
            let mut listeners = self.message_listeners.lock().await;
            if listeners.contains_key(&key) {
                return Err(FabricError::ResourceExhausted);
            }
            listeners.insert(key, tx.clone());
        }
        let mut lease = MessageListenerLease {
            services: Arc::clone(self),
            key,
            sender: tx,
            _receiver: rx,
        };
        let result = tokio::time::timeout(Duration::from_secs(10), async {
            if initiate {
                QuicMessageTransport::open(link.as_ref(), channel, 8)
                    .await
                    .map_err(message_error)
            } else {
                match lease._receiver.recv().await {
                    Some(IncomingStream::Bi(open, stream)) => {
                        QuicMessageTransport::accept(peer, channel, open, stream, 8)
                            .map_err(message_error)
                    }
                    _ => Err(FabricError::Disconnected),
                }
            }
        })
        .await
        .map_err(|_| FabricError::Disconnected)
        .and_then(|r| r);
        // Retain the sender as a lease while this port exists, preventing a second
        // reader from replacing a live channel; its queue cannot grow beyond one.
        match result {
            Ok(transport) => {
                admission.check(CapabilityAction::Open).await?;
                Ok(Box::new(HubMessagePort {
                    admission,
                    transport: Arc::new(transport),
                    destinations,
                    _listener: Some(lease),
                }))
            }
            Err(error) => {
                self.message_listeners.lock().await.remove(&key);
                Err(error)
            }
        }
    }

    /// BLE uses the same admission/grant/SDK path. The supplied platform must
    /// provide a Fabric-identity-authenticated encrypted packet bearer; an OS
    /// Bluetooth bond alone is insufficient. No stream/datagram API is added.
    pub async fn open_ble_message_port(
        self: &Arc<Self>,
        ipc: Arc<crate::HubIpcServer>,
        policy: Arc<PolicyMutex<PolicyEngine>>,
        request: MessagePortRequest,
        platform: &dyn fabric_platform::BleMessagePlatform,
        config: fabric_platform::BleMessageConfig,
    ) -> Result<Box<dyn MessagePort>, FabricError> {
        let (admission, channel, destinations) =
            self.admit_message_port(ipc, policy, request).await?;
        let transport = fabric_platform::BleMessageTransport::connect(platform, channel, config)
            .await
            .map_err(message_error)?;
        admission.check(CapabilityAction::Open).await?;
        Ok(Box::new(HubMessagePort {
            admission,
            transport: Arc::new(transport),
            destinations,
            _listener: None,
        }))
    }
}

fn message_error(error: fabric_platform::TransportError) -> FabricError {
    use fabric_platform::TransportError as E;
    match error {
        E::UnsupportedCapability | E::UnsupportedMode => FabricError::UnsupportedCapability,
        E::StaleSession => FabricError::StaleSession,
        E::Backpressure | E::MessageTooLarge => FabricError::ResourceExhausted,
        E::Closed | E::ReceiverGone | E::Timeout => FabricError::Disconnected,
        _ => FabricError::PermissionDenied,
    }
}
