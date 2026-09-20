use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    fs::OpenOptions,
    io::{Read, Write},
    net::{SocketAddr, UdpSocket},
    path::{Path, PathBuf},
    sync::{Arc, mpsc},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use async_trait::async_trait;
use bytes::Bytes;
use eframe::egui::{self, Color32, RichText};
use fabric_app_broker::{
    Broker, BrokerByteStream, BrokerError, DEFAULT_BROKER_ADDRESS, DeviceInfo, RemoteBackend,
    invoke_remote_stream, open_remote_stream, serve_remote_stream,
};
use fabric_core::{ChannelId, DeviceId, ParticipantId, Platform, SessionId};
use fabric_discovery::{DiscoverySource, encode_rotating_hint, quic_socket_addresses};
use fabric_hub::{HealthStatus, Hub, HubConfig, LinkServices, RelayEndpointConfig};
use fabric_identity::{
    DeviceIdentity, DeviceTrust, DeviceTrustStore, FileDeviceLabelStore, FileDeviceTrustStore,
    IdentityError, OsCredentialAdapter, PeerCredentials, decode_device_id, encode_device_id,
};
use fabric_link::{FabricLink, IncomingStream, StreamOpen};
use fabric_protocol::ControlMessage;

const BROKER_STREAM_HEADER: &[u8] = b"fabric-app-broker-v1";

struct DesktopCredentials;

impl OsCredentialAdapter for DesktopCredentials {
    fn authenticate(
        &self,
        credentials: &PeerCredentials,
    ) -> Result<fabric_core::AppPrincipal, IdentityError> {
        let desktop_platform = matches!(
            credentials.platform,
            Platform::Windows | Platform::MacOs | Platform::Linux
        );
        if !desktop_platform
            || credentials.stable_app_id.is_empty()
            || credentials.os_subject.0.is_empty()
        {
            return Err(IdentityError::AppIdentityMismatch);
        }
        let principal = fabric_core::AppPrincipal {
            platform: credentials.platform,
            stable_app_id: credentials.stable_app_id.clone(),
            publisher_id: credentials.publisher_id.clone(),
            signing_digest: credentials.signing_digest,
            os_subject: credentials.os_subject.clone(),
        };
        principal
            .validate()
            .map_err(|_| IdentityError::AppIdentityMismatch)?;
        Ok(principal)
    }
}

struct HubBackend {
    links: Arc<LinkServices>,
    trust: Arc<FileDeviceTrustStore>,
    labels: Arc<FileDeviceLabelStore>,
}

#[async_trait]
impl RemoteBackend for HubBackend {
    async fn devices(&self) -> Result<Vec<DeviceInfo>, BrokerError> {
        let online = self
            .links
            .active_peers()
            .await
            .into_iter()
            .map(|peer| peer.device_id)
            .collect::<BTreeSet<_>>();
        self.trust
            .entries()
            .map_err(|error| BrokerError::Rejected(error.to_string()))
            .map(|entries| {
                entries
                    .into_iter()
                    .filter(|(_, trust)| *trust == DeviceTrust::Trusted)
                    .map(|(device, _)| DeviceInfo {
                        id: encode_device_id(device),
                        label: self
                            .labels
                            .label(device)
                            .ok()
                            .flatten()
                            .unwrap_or_else(|| format!("Device {}", short_device(device))),
                        paired: true,
                        online: online.contains(&device),
                    })
                    .collect()
            })
    }

    async fn invoke(
        &self,
        target_device: &str,
        request_id: u64,
        ability: &str,
        payload: Vec<u8>,
    ) -> Result<Vec<u8>, BrokerError> {
        let peer = decode_device_id(target_device).map_err(|_| BrokerError::DeviceUnavailable)?;
        let trusted = self
            .trust
            .trust(peer)
            .map_err(|error| BrokerError::Rejected(error.to_string()))?
            == DeviceTrust::Trusted;
        if !trusted {
            return Err(BrokerError::DeviceUnavailable);
        }
        let link = self
            .links
            .link(peer)
            .await
            .ok_or(BrokerError::DeviceUnavailable)?;
        let mut stream = link
            .open_bi(broker_stream_open())
            .await
            .map_err(|_| BrokerError::DeviceUnavailable)?;
        invoke_remote_stream(&mut stream, request_id, ability, payload).await
    }

    async fn open_stream(
        &self,
        target_device: &str,
        request_id: u64,
        ability: &str,
        stream_name: &str,
    ) -> Result<Box<dyn BrokerByteStream>, BrokerError> {
        let peer = decode_device_id(target_device).map_err(|_| BrokerError::DeviceUnavailable)?;
        let trusted = self
            .trust
            .trust(peer)
            .map_err(|error| BrokerError::Rejected(error.to_string()))?
            == DeviceTrust::Trusted;
        if !trusted {
            return Err(BrokerError::DeviceUnavailable);
        }
        let link = self
            .links
            .link(peer)
            .await
            .ok_or(BrokerError::DeviceUnavailable)?;
        let mut stream = link
            .open_bi(broker_stream_open())
            .await
            .map_err(|_| BrokerError::DeviceUnavailable)?;
        open_remote_stream(&mut stream, request_id, ability, stream_name).await?;
        Ok(Box::new(stream))
    }
}

fn broker_stream_open() -> StreamOpen {
    StreamOpen {
        session_id: SessionId::new(),
        epoch: 0,
        channel_id: ChannelId::new(),
        sender: ParticipantId::new(),
        destination_binding: [0; 16],
        flags: 0,
        e2ee_header: Bytes::from_static(BROKER_STREAM_HEADER),
    }
}

#[derive(Clone, Debug)]
struct DeviceRow {
    id: String,
    label: String,
    trust: DeviceTrust,
    online: bool,
}

#[derive(Clone, Debug)]
struct DiscoveredRow {
    label: String,
    address: SocketAddr,
    sources: String,
}

#[derive(Clone)]
enum RuntimeEvent {
    Starting,
    Healthy {
        device: String,
        device_full: String,
        ipc: String,
        quic: String,
        relay: Option<String>,
    },
    Devices(Vec<DeviceRow>),
    Candidates(Vec<DiscoveredRow>),
    Notice(String),
    Stopped,
    Failed(String),
}

enum RuntimeCommand {
    Pair(String),
    PairRemote(String),
    Accept(String),
    Remove(String),
    Stop,
}

struct RuntimeHandle {
    commands: tokio::sync::mpsc::Sender<RuntimeCommand>,
    join: Option<thread::JoinHandle<()>>,
}

impl RuntimeHandle {
    fn start(events: mpsc::Sender<RuntimeEvent>) -> Self {
        let (commands, command_rx) = tokio::sync::mpsc::channel(32);
        let join = thread::spawn(move || {
            let _ = events.send(RuntimeEvent::Starting);
            let result = tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .map_err(|error| error.to_string())
                .and_then(|runtime| runtime.block_on(run_hub(command_rx, events.clone())));
            if let Err(error) = result {
                let _ = events.send(RuntimeEvent::Failed(error));
            }
        });
        Self {
            commands,
            join: Some(join),
        }
    }

    fn send(&self, command: RuntimeCommand) {
        let _ = self.commands.blocking_send(command);
    }

    fn stop(&mut self) {
        let _ = self.commands.blocking_send(RuntimeCommand::Stop);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

impl Drop for RuntimeHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

#[allow(clippy::too_many_lines)]
async fn run_hub(
    mut commands: tokio::sync::mpsc::Receiver<RuntimeCommand>,
    events: mpsc::Sender<RuntimeEvent>,
) -> Result<(), String> {
    let state_dir = default_state_dir().map_err(|error| error.to_string())?;
    let identity = DeviceIdentity::from_seed(
        load_or_create_seed(&state_dir.join("fabric-device.key"))
            .map_err(|error| error.to_string())?,
    );
    let device_full = encode_device_id(identity.device_id());
    let device_label = local_device_label();
    let relay_config = load_relay_config(&state_dir);
    let relay_display = relay_config.as_ref().map(|relay| {
        let security = if relay.tls_fingerprint.is_some() {
            "tls"
        } else {
            "tcp"
        };
        format!("{} ({security})", relay.address)
    });
    let mut hub = Hub::start(
        HubConfig {
            database_path: state_dir.join("fabric.sqlite"),
            device_label: device_label.clone(),
            quic_port: 44_330,
            maximum_ipc_connections: 256,
            maximum_sessions: 1_024,
            maximum_buffered_bytes: 256 * 1_024 * 1_024,
            graceful_shutdown_ms: 5_000,
            relay: relay_config,
        },
        identity,
        now_ms()?,
    )
    .map_err(|error| error.to_string())?;
    let trust = Arc::new(
        FileDeviceTrustStore::open(state_dir.join("trusted-devices.txt"))
            .map_err(|error| error.to_string())?,
    );
    let labels = Arc::new(
        FileDeviceLabelStore::open(state_dir.join("device-labels.txt"))
            .map_err(|error| error.to_string())?,
    );
    let services = hub
        .start_platform_services(Arc::new(DesktopCredentials), trust.clone())
        .await
        .map_err(|error| error.to_string())?;
    let backend = Arc::new(HubBackend {
        links: Arc::clone(&services.links),
        trust: Arc::clone(&trust),
        labels: Arc::clone(&labels),
    });
    let broker = Broker::bind_with_backend(DEFAULT_BROKER_ADDRESS, backend)
        .await
        .map_err(|error| error.to_string())?;
    let (broker_shutdown, broker_shutdown_rx) = tokio::sync::watch::channel(false);
    let broker_task = tokio::spawn(broker.run(broker_shutdown_rx));
    let incoming_links = Arc::clone(&services.links);
    let incoming_task = tokio::spawn(async move {
        while let Some(incoming) = incoming_links.next_stream().await {
            if let IncomingStream::Bi(header, stream) = incoming.stream
                && header.e2ee_header.as_ref() == BROKER_STREAM_HEADER
            {
                tokio::spawn(async move {
                    let _ = serve_remote_stream(stream, DEFAULT_BROKER_ADDRESS).await;
                });
            }
        }
    });
    let diagnostics = hub.diagnostics();
    if diagnostics.status != HealthStatus::Healthy {
        return Err(format!(
            "Hub did not become healthy: {:?}",
            diagnostics.status
        ));
    }
    let relay_active = services.links.has_relay();
    let _ = events.send(RuntimeEvent::Healthy {
        device: diagnostics.device_id_short,
        device_full,
        ipc: services.ipc_endpoint.display().to_string(),
        quic: reachable_address(
            services
                .links
                .local_addr()
                .map_err(|error| error.to_string())?,
        ),
        relay: match (relay_display, relay_active) {
            (Some(display), true) => Some(display),
            (Some(display), false) => Some(format!("{display} — unreachable")),
            (None, _) => None,
        },
    });

    let mut addresses = BTreeMap::<DeviceId, SocketAddr>::new();
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    loop {
        tokio::select! {
            command = commands.recv() => match command {
                Some(RuntimeCommand::Pair(address)) => {
                    match address.parse::<SocketAddr>() {
                        Ok(address) => match services.links.connect(address).await {
                            Ok(peer) => {
                                addresses.insert(peer, address);
                                if let Some(label) = services.candidates.as_ref()
                                    .and_then(|store| label_for_candidate_address(store, address).ok().flatten())
                                {
                                    labels.set_label(peer, &label).map_err(|error| error.to_string())?;
                                }
                                if trust.trust(peer).map_err(|error| error.to_string())? == DeviceTrust::Trusted {
                                    let _ = events.send(RuntimeEvent::Notice(format!("Connected to {}", short_device(peer))));
                                } else {
                                    trust.set_trust(peer, DeviceTrust::PendingUserConfirmation).map_err(|error| error.to_string())?;
                                    services.links.request_pairing(peer, &device_label).await.map_err(|error| error.to_string())?;
                                    let _ = events.send(RuntimeEvent::Notice(format!("Pairing request sent to {}", short_device(peer))));
                                }
                            }
                            Err(error) => { let _ = events.send(RuntimeEvent::Notice(format!("Connection failed: {error}"))); }
                        },
                        Err(_) => { let _ = events.send(RuntimeEvent::Notice("Enter an IP address and port, for example 192.168.1.20:44330".into())); }
                    }
                }
                Some(RuntimeCommand::PairRemote(encoded)) => {
                    match decode_device_id(encoded.trim()) {
                        Ok(peer) => {
                            if services.links.has_relay() {
                                match services.links.connect_by_device_via_relay(peer, local_candidates()).await {
                                    Ok(()) => {
                                        if trust.trust(peer).map_err(|error| error.to_string())? == DeviceTrust::Trusted {
                                            let _ = events.send(RuntimeEvent::Notice(format!("Connected to {} via relay", short_device(peer))));
                                        } else {
                                            trust.set_trust(peer, DeviceTrust::PendingUserConfirmation).map_err(|error| error.to_string())?;
                                            services.links.request_pairing(peer, &device_label).await.map_err(|error| error.to_string())?;
                                            let _ = events.send(RuntimeEvent::Notice(format!("Pairing request sent to {} via relay", short_device(peer))));
                                        }
                                    }
                                    Err(error) => {
                                        let _ = events.send(RuntimeEvent::Notice(format!("Relay connection failed: {error}")));
                                    }
                                }
                            } else {
                                let _ = events.send(RuntimeEvent::Notice(
                                    "No relay configured: set FABRIC_RELAY or relay.conf in the data folder".into(),
                                ));
                            }
                        }
                        Err(_) => {
                            let _ = events.send(RuntimeEvent::Notice("Enter the peer's full device ID as shown on its hub".into()));
                        }
                    }
                }
                Some(RuntimeCommand::Accept(encoded)) => {
                    if let Ok(peer) = decode_device_id(&encoded) {
                        services.links.complete_pairing(peer, &device_label).await.map_err(|error| error.to_string())?;
                        trust.set_trust(peer, DeviceTrust::Trusted).map_err(|error| error.to_string())?;
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        services.links.disconnect_peer(peer).await;
                        let _ = events.send(RuntimeEvent::Notice(format!("Paired with {}", short_device(peer))));
                    }
                }
                Some(RuntimeCommand::Remove(encoded)) => {
                    if let Ok(peer) = decode_device_id(&encoded) {
                        trust.remove(peer).map_err(|error| error.to_string())?;
                        labels.remove(peer).map_err(|error| error.to_string())?;
                        addresses.remove(&peer);
                        services.links.disconnect_peer(peer).await;
                        let _ = events.send(RuntimeEvent::Notice(format!("Removed {}", short_device(peer))));
                    }
                }
                Some(RuntimeCommand::Stop) | None => break,
            },
            control = services.links.next_control() => {
                if let Some(control) = control {
                    match control.message {
                        ControlMessage::PairingStart(start) => {
                            labels.set_label(control.peer, &start.device_label).map_err(|error| error.to_string())?;
                            trust.set_trust(control.peer, DeviceTrust::PendingUserConfirmation).map_err(|error| error.to_string())?;
                            let _ = events.send(RuntimeEvent::Notice(format!("Pairing request from {}", short_device(control.peer))));
                        }
                        ControlMessage::PairingComplete(complete) if complete.device_id == control.peer => {
                            labels.set_label(control.peer, &complete.device_label).map_err(|error| error.to_string())?;
                            trust.set_trust(control.peer, DeviceTrust::Trusted).map_err(|error| error.to_string())?;
                            services.links.disconnect_peer(control.peer).await;
                            if let Some(address) = addresses.get(&control.peer).copied() {
                                let _ = services.links.connect(address).await;
                            } else if services.links.has_relay() {
                                // Relay-paired peers have no direct address yet.
                                let _ = services
                                    .links
                                    .connect_by_device_via_relay(control.peer, local_candidates())
                                    .await;
                            }
                            let _ = events.send(RuntimeEvent::Notice(format!("Paired with {}", short_device(control.peer))));
                        }
                        _ => {}
                    }
                }
            },
            _ = tick.tick() => {
                let online = services.links.active_peers().await.into_iter()
                    .map(|peer| peer.device_id).collect::<BTreeSet<_>>();
                let mut rows = Vec::new();
                for (device, stored) in trust.entries().map_err(|error| error.to_string())? {
                    rows.push(DeviceRow {
                        id: encode_device_id(device),
                        label: labels
                            .label(device)
                            .map_err(|error| error.to_string())?
                            .unwrap_or_else(|| format!("Device {}", short_device(device))),
                        trust: stored,
                        online: online.contains(&device),
                    });
                }
                let _ = events.send(RuntimeEvent::Devices(rows));
                if let Some(store) = &services.candidates {
                    let mut merged = BTreeMap::<String, DiscoveredRow>::new();
                    for candidate in store.values().map_err(|error| error.to_string())? {
                        let Some(address) = quic_socket_addresses(&candidate).into_iter().next() else {
                            continue;
                        };
                        let key = candidate.device_hint.map_or_else(
                            || candidate.discovery_key.0.clone(),
                            |hint| encode_rotating_hint(&hint),
                        );
                        let label = candidate.label;
                        let sources = candidate.sources.into_iter().map(|source| match source {
                            DiscoverySource::Mdns => "mDNS",
                            DiscoverySource::Ble => "BLE",
                            DiscoverySource::WifiAware => "Wi-Fi Aware",
                        }).collect::<Vec<_>>().join(" + ");
                        merged.entry(key).and_modify(|row| {
                            if !row.sources.contains(&sources) {
                                row.sources = format!("{} + {sources}", row.sources);
                            }
                        }).or_insert(DiscoveredRow { label, address, sources });
                    }
                    let _ = events.send(RuntimeEvent::Candidates(merged.into_values().collect()));
                }
            }
        }
    }
    let _ = broker_shutdown.send(true);
    let _ = broker_task.await;
    incoming_task.abort();
    let _ = incoming_task.await;
    hub.shutdown().await.map_err(|error| error.to_string())?;
    let _ = events.send(RuntimeEvent::Stopped);
    Ok(())
}

struct HubApp {
    runtime: Option<RuntimeHandle>,
    events: mpsc::Receiver<RuntimeEvent>,
    event_sender: mpsc::Sender<RuntimeEvent>,
    status: RuntimeEvent,
    devices: Vec<DeviceRow>,
    candidates: Vec<DiscoveredRow>,
    address: String,
    show_manual_pairing: bool,
    remote_device: String,
    show_remote_pairing: bool,
    log: Vec<String>,
}

impl HubApp {
    fn new() -> Self {
        let (event_sender, events) = mpsc::channel();
        let runtime = RuntimeHandle::start(event_sender.clone());
        Self {
            runtime: Some(runtime),
            events,
            event_sender,
            status: RuntimeEvent::Starting,
            devices: Vec::new(),
            candidates: Vec::new(),
            address: String::new(),
            show_manual_pairing: false,
            remote_device: String::new(),
            show_remote_pairing: false,
            log: vec!["Starting Hub runtime".into()],
        }
    }

    fn poll_events(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            match event {
                RuntimeEvent::Devices(devices) => self.devices = devices,
                RuntimeEvent::Candidates(candidates) => self.candidates = candidates,
                RuntimeEvent::Notice(message) => self.log.push(message),
                status => {
                    self.log.push(match &status {
                        RuntimeEvent::Starting => "Starting Hub runtime".into(),
                        RuntimeEvent::Healthy { device, .. } => {
                            format!("Hub healthy on device {device}")
                        }
                        RuntimeEvent::Stopped => "Hub stopped".into(),
                        RuntimeEvent::Failed(error) => format!("Hub failed: {error}"),
                        RuntimeEvent::Devices(_)
                        | RuntimeEvent::Candidates(_)
                        | RuntimeEvent::Notice(_) => unreachable!(),
                    });
                    self.status = status;
                }
            }
        }
    }
}

impl eframe::App for HubApp {
    #[allow(clippy::too_many_lines)]
    fn update(&mut self, context: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll_events();
        context.request_repaint_after(Duration::from_millis(250));
        egui::TopBottomPanel::top("header").show(context, |ui| {
            ui.add_space(12.0);
            ui.horizontal(|ui| {
                ui.heading("Device Fabric Hub");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (label, color) = match &self.status {
                        RuntimeEvent::Starting => ("STARTING", Color32::from_rgb(188, 122, 24)),
                        RuntimeEvent::Healthy { .. } => ("HEALTHY", Color32::from_rgb(38, 130, 87)),
                        RuntimeEvent::Stopped => ("STOPPED", Color32::from_gray(110)),
                        RuntimeEvent::Failed(_) => ("FAILED", Color32::from_rgb(180, 54, 54)),
                        RuntimeEvent::Devices(_)
                        | RuntimeEvent::Candidates(_)
                        | RuntimeEvent::Notice(_) => unreachable!(),
                    };
                    ui.label(RichText::new(label).strong().color(color));
                });
            });
            ui.add_space(8.0);
        });
        egui::CentralPanel::default().show(context, |ui| {
            let mut relay_available = false;
            if let RuntimeEvent::Healthy {
                device,
                device_full,
                ipc,
                quic,
                relay,
            } = &self.status
            {
                relay_available = relay.as_ref().is_some_and(|r| !r.ends_with("unreachable"));
                egui::Grid::new("runtime_details")
                    .num_columns(2)
                    .spacing([24.0, 8.0])
                    .show(ui, |ui| {
                        detail_row(ui, "Device", device);
                        detail_row(ui, "Device ID", device_full);
                        detail_row(ui, "Local IPC", ipc);
                        detail_row(ui, "QUIC", quic);
                        detail_row(
                            ui,
                            "Relay",
                            relay.as_deref().unwrap_or("not configured"),
                        );
                    });
            }
            ui.add_space(14.0);
            ui.strong("Nearby devices");
            if self.candidates.is_empty() {
                ui.label(
                    RichText::new("Searching on the local network...")
                        .color(Color32::from_gray(110)),
                );
            } else {
                egui::Grid::new("candidates")
                    .num_columns(4)
                    .striped(true)
                    .spacing([18.0, 8.0])
                    .show(ui, |ui| {
                        for candidate in self.candidates.clone() {
                            ui.label(candidate.label);
                            ui.monospace(candidate.address.to_string());
                            ui.label(candidate.sources);
                            if ui.button("Pair").clicked()
                                && let Some(runtime) = &self.runtime
                            {
                                runtime.send(RuntimeCommand::Pair(candidate.address.to_string()));
                            }
                            ui.end_row();
                        }
                    });
            }
            ui.horizontal(|ui| {
                if ui.button("Pair by IP address").clicked() {
                    self.show_manual_pairing = !self.show_manual_pairing;
                }
                if ui
                    .add_enabled(
                        relay_available,
                        egui::Button::new("Pair by device ID (relay)"),
                    )
                    .on_disabled_hover_text(
                        "Configure a relay first: FABRIC_RELAY env or relay.conf in the data folder",
                    )
                    .clicked()
                {
                    self.show_remote_pairing = !self.show_remote_pairing;
                }
            });
            if self.show_manual_pairing {
                ui.horizontal(|ui| {
                    ui.add(
                        egui::TextEdit::singleline(&mut self.address)
                            .hint_text("192.168.1.20:44330")
                            .desired_width(300.0),
                    );
                    if ui
                        .add_enabled(
                            self.runtime.is_some() && !self.address.is_empty(),
                            egui::Button::new("Pair"),
                        )
                        .clicked()
                        && let Some(runtime) = &self.runtime
                    {
                        runtime.send(RuntimeCommand::Pair(self.address.trim().to_owned()));
                    }
                });
            }
            if self.show_remote_pairing {
                ui.horizontal(|ui| {
                    ui.add(
                        egui::TextEdit::singleline(&mut self.remote_device)
                            .hint_text("peer device ID (from its hub screen)")
                            .desired_width(420.0),
                    );
                    if ui
                        .add_enabled(
                            self.runtime.is_some() && !self.remote_device.is_empty(),
                            egui::Button::new("Pair via relay"),
                        )
                        .clicked()
                        && let Some(runtime) = &self.runtime
                    {
                        runtime
                            .send(RuntimeCommand::PairRemote(self.remote_device.trim().to_owned()));
                    }
                });
            }
            ui.add_space(14.0);
            ui.strong("Devices");
            egui::Grid::new("devices")
                .num_columns(5)
                .striped(true)
                .spacing([18.0, 8.0])
                .show(ui, |ui| {
                    for device in self.devices.clone() {
                        ui.label(device.label);
                        ui.monospace(&device.id[..12]);
                        let state = if device.trust == DeviceTrust::PendingUserConfirmation {
                            "Approval required"
                        } else if device.online {
                            "Online"
                        } else {
                            "Offline"
                        };
                        ui.label(state);
                        if device.trust == DeviceTrust::PendingUserConfirmation {
                            if ui.button("Accept").clicked()
                                && let Some(runtime) = &self.runtime
                            {
                                runtime.send(RuntimeCommand::Accept(device.id.clone()));
                            }
                        } else {
                            ui.label("");
                        }
                        if ui.button("Delete").clicked()
                            && let Some(runtime) = &self.runtime
                        {
                            runtime.send(RuntimeCommand::Remove(device.id.clone()));
                        }
                        ui.end_row();
                    }
                });
            ui.add_space(14.0);
            ui.horizontal(|ui| {
                ui.strong("Runtime log");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_enabled(self.runtime.is_some(), egui::Button::new("Stop"))
                        .clicked()
                        && let Some(mut runtime) = self.runtime.take()
                    {
                        runtime.stop();
                    }
                    if ui
                        .add_enabled(self.runtime.is_none(), egui::Button::new("Start"))
                        .clicked()
                    {
                        self.runtime = Some(RuntimeHandle::start(self.event_sender.clone()));
                    }
                });
            });
            egui::ScrollArea::vertical()
                .max_height(130.0)
                .stick_to_bottom(true)
                .show(ui, |ui| {
                    for entry in &self.log {
                        ui.monospace(entry);
                    }
                });
        });
    }
}

fn detail_row(ui: &mut egui::Ui, name: &str, value: &str) {
    ui.label(RichText::new(name).color(Color32::from_gray(110)));
    ui.monospace(value);
    ui.end_row();
}

fn label_for_candidate_address(
    store: &fabric_hub::CandidateStore,
    address: SocketAddr,
) -> Result<Option<String>, String> {
    Ok(store
        .values()
        .map_err(|error| error.to_string())?
        .into_iter()
        .find(|candidate| quic_socket_addresses(candidate).contains(&address))
        .map(|candidate| candidate.label))
}

/// Loads relay settings from `FABRIC_RELAY` / `FABRIC_RELAY_FINGERPRINT`
/// environment variables, falling back to `relay.conf` in the data folder
/// (line 1: `host:port`, optional line 2: 64-hex-char TLS fingerprint).
fn load_relay_config(state_dir: &Path) -> Option<RelayEndpointConfig> {
    let (address_text, fingerprint_text) = if let Ok(address) = env::var("FABRIC_RELAY") {
        (address, env::var("FABRIC_RELAY_FINGERPRINT").ok())
    } else {
        let content = std::fs::read_to_string(state_dir.join("relay.conf")).ok()?;
        let mut lines = content
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with('#'));
        (lines.next()?.to_owned(), lines.next().map(str::to_owned))
    };
    Some(RelayEndpointConfig {
        address: address_text.trim().parse().ok()?,
        tls_fingerprint: fingerprint_text.as_deref().and_then(parse_fingerprint_hex),
    })
}

/// Parses a 64-character hex string into a pinned certificate fingerprint.
fn parse_fingerprint_hex(text: &str) -> Option<[u8; 32]> {
    let text = text.trim();
    if text.len() != 64 {
        return None;
    }
    let mut fingerprint = [0u8; 32];
    for (index, chunk) in text.as_bytes().chunks_exact(2).enumerate() {
        let hex = std::str::from_utf8(chunk).ok()?;
        fingerprint[index] = u8::from_str_radix(hex, 16).ok()?;
    }
    Some(fingerprint)
}

/// Our directly-reachable addresses to advertise as hole-punch candidates.
fn local_candidates() -> Vec<SocketAddr> {
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|socket| {
            socket.connect("8.8.8.8:80")?;
            socket.local_addr()
        })
        .map(|local| vec![SocketAddr::new(local.ip(), 44_330)])
        .unwrap_or_default()
}

fn local_device_label() -> String {
    // COMPUTERNAME is Windows-only and HOSTNAME is rarely exported to GUI
    // processes on macOS/Linux, so fall back to the hostname utility.
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|name| !name.trim().is_empty())
        .or_else(|| {
            std::process::Command::new("hostname")
                .output()
                .ok()
                .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_owned())
                .filter(|name| !name.is_empty())
        })
        .unwrap_or_else(|| "Desktop hub".into())
}

fn short_device(device: DeviceId) -> String {
    encode_device_id(device)[..12].to_owned()
}

fn reachable_address(bound: SocketAddr) -> String {
    if !bound.ip().is_unspecified() {
        return bound.to_string();
    }
    UdpSocket::bind("0.0.0.0:0")
        .and_then(|socket| {
            socket.connect("8.8.8.8:80")?;
            socket.local_addr()
        })
        .map_or_else(
            |_| bound.to_string(),
            |local| SocketAddr::new(local.ip(), bound.port()).to_string(),
        )
}

fn now_ms() -> Result<u64, String> {
    u64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_millis(),
    )
    .map_err(|error| error.to_string())
}

fn default_state_dir() -> Result<PathBuf, Box<dyn std::error::Error>> {
    // Platform-appropriate application data directory; the app previously
    // required LOCALAPPDATA and could not start anywhere but Windows.
    let base = if cfg!(windows) {
        env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .ok_or("LOCALAPPDATA is unavailable")?
    } else if cfg!(target_os = "macos") {
        PathBuf::from(env::var_os("HOME").ok_or("HOME is unavailable")?)
            .join("Library/Application Support")
    } else {
        env::var_os("XDG_DATA_HOME").map_or_else(
            || {
                env::var_os("HOME")
                    .map(|home| PathBuf::from(home).join(".local/share"))
                    .ok_or("HOME is unavailable")
            },
            |xdg| Ok(PathBuf::from(xdg)),
        )?
    };
    let directory = base.join("DeviceFabric");
    std::fs::create_dir_all(&directory)?;
    Ok(directory)
}

fn load_or_create_seed(path: &Path) -> Result<[u8; 32], Box<dyn std::error::Error>> {
    match OpenOptions::new().read(true).open(path) {
        Ok(mut file) => {
            let mut seed = [0; 32];
            file.read_exact(&mut seed)?;
            let mut extra = [0; 1];
            if file.read(&mut extra)? != 0 {
                return Err("device key file must contain exactly 32 bytes".into());
            }
            Ok(seed)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let seed = DeviceIdentity::generate().seed_for_secure_storage();
            let mut file = OpenOptions::new().write(true).create_new(true).open(path)?;
            file.write_all(&seed)?;
            file.sync_all()?;
            Ok(seed)
        }
        Err(error) => Err(error.into()),
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([820.0, 650.0])
            .with_min_inner_size([680.0, 520.0]),
        ..Default::default()
    };
    eframe::run_native(
        "Device Fabric Hub",
        options,
        Box::new(|_| Ok(Box::new(HubApp::new()))),
    )
}
