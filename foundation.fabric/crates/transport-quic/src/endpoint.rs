use std::{
    net::SocketAddr,
    sync::{Arc, Mutex},
};

use fabric_core::DeviceId;
use fabric_identity::{DeviceIdentity, DeviceTrustStore};
use fabric_magic_socket::{MagicHandle, MagicSocket};
use thiserror::Error;

use crate::handshake::{HandshakeReplayGuard, perform_handshake};
use crate::{DeviceCertificate, HandshakeError, HandshakeResult, LinkHandshakeConfig};

#[derive(Debug, Error)]
pub enum EndpointError {
    #[error("failed to bind QUIC endpoint: {0}")]
    Bind(#[from] std::io::Error),
    #[error("failed to configure device TLS: {0}")]
    Tls(#[from] crate::TlsError),
    #[error("failed to start QUIC connection: {0}")]
    Connect(#[from] noq::ConnectError),
    #[error("QUIC connection failed: {0}")]
    Connection(#[from] noq::ConnectionError),
    #[error("QUIC endpoint is closed")]
    Closed,
    #[error("peer authenticated as a different device than the one dialed")]
    PeerIdentityMismatch,
    #[error("device handshake failed: {0}")]
    Handshake(#[from] HandshakeError),
}

pub struct QuicEndpoint {
    endpoint: noq::Endpoint,
    identity: Arc<DeviceIdentity>,
    trust_store: Arc<dyn DeviceTrustStore>,
    replay_guard: Mutex<HandshakeReplayGuard>,
    handshake: LinkHandshakeConfig,
    local: SocketAddr,
    magic: MagicHandle,
}

impl QuicEndpoint {
    pub fn bind(
        address: SocketAddr,
        identity: Arc<DeviceIdentity>,
        trust_store: Arc<dyn DeviceTrustStore>,
        handshake: LinkHandshakeConfig,
    ) -> Result<Self, EndpointError> {
        Self::from_socket(
            MagicSocket::bind(address)?,
            identity,
            trust_store,
            handshake,
        )
    }

    /// Binds like [`Self::bind`], additionally attaching an authenticated relay
    /// connection so peers without a reachable direct address can be dialed
    /// through the relay via [`Self::connect_by_device_via_relay`].
    pub fn bind_with_relay(
        address: SocketAddr,
        relay: fabric_relay::RelayConnection,
        identity: Arc<DeviceIdentity>,
        trust_store: Arc<dyn DeviceTrustStore>,
        handshake: LinkHandshakeConfig,
    ) -> Result<Self, EndpointError> {
        Self::from_socket(
            MagicSocket::bind_with_relay(address, relay)?,
            identity,
            trust_store,
            handshake,
        )
    }

    fn from_socket(
        socket: MagicSocket,
        identity: Arc<DeviceIdentity>,
        trust_store: Arc<dyn DeviceTrustStore>,
        handshake: LinkHandshakeConfig,
    ) -> Result<Self, EndpointError> {
        let certificate = DeviceCertificate::from_identity(&identity)?;
        let (server, client) = certificate.tls_configs()?;
        let local = socket.real_local_addr()?;
        let magic = socket.handle();
        let endpoint = noq::Endpoint::new_with_abstract_socket(
            noq::EndpointConfig::default(),
            Some(server),
            Box::new(socket),
            Arc::new(noq::TokioRuntime),
        )?;
        endpoint.set_default_client_config(client);
        Ok(Self {
            endpoint,
            identity,
            trust_store,
            replay_guard: Mutex::new(HandshakeReplayGuard::default()),
            handshake,
            local,
            magic,
        })
    }

    /// The real, reachable local address other devices should dial — not the
    /// synthetic IPv6 address QUIC sees through the magic socket.
    pub fn local_addr(&self) -> Result<SocketAddr, EndpointError> {
        Ok(self.local)
    }

    #[must_use]
    pub fn device_id(&self) -> DeviceId {
        self.identity.device_id()
    }

    #[must_use]
    pub fn sign_device_proof(&self, transcript_hash: &[u8; 32]) -> [u8; 64] {
        self.identity.sign_device_proof(transcript_hash)
    }

    /// Dials a peer by a bare transport address (e.g. a LAN address). The magic
    /// socket routes it through the synthetic address space so QUIC never handles
    /// a real address directly.
    pub async fn connect(&self, address: SocketAddr) -> Result<HandshakeResult, EndpointError> {
        let synthetic = self.magic.map_ip(address);
        self.connect_synthetic(synthetic).await
    }

    /// Performs the QUIC dial and application handshake to an already-synthetic
    /// address the magic socket knows how to route.
    async fn connect_synthetic(
        &self,
        synthetic: SocketAddr,
    ) -> Result<HandshakeResult, EndpointError> {
        let connection = self
            .endpoint
            .connect(synthetic, "device-fabric.local")?
            .await?;
        let (send, receive) = connection.open_bi().await?;
        self.finish_handshake(connection, send, receive).await
    }

    /// Dials a peer by its `DeviceId`, reaching it at `direct` (a real UDP
    /// address supplied by discovery). The magic socket pins the peer to one
    /// synthetic address so the connection can later migrate paths without QUIC
    /// noticing. The device that answers must authenticate as `device`, otherwise
    /// the connection is rejected as a wrong-peer / man-in-the-middle attempt.
    pub async fn connect_by_device(
        &self,
        device: DeviceId,
        direct: SocketAddr,
    ) -> Result<HandshakeResult, EndpointError> {
        let synthetic = self.magic.add_direct_addr(device, direct);
        let result = self.connect_synthetic(synthetic).await?;
        if result.peer_device_id != device {
            return Err(EndpointError::PeerIdentityMismatch);
        }
        Ok(result)
    }

    /// Dials a peer by its `DeviceId` through the relay this endpoint was bound
    /// with — the path of last resort when no direct address is reachable. The
    /// magic socket tunnels the QUIC packets as opaque relay payloads; if a live
    /// direct path for the peer later becomes known, traffic migrates to it
    /// transparently.
    pub async fn connect_by_device_via_relay(
        &self,
        device: DeviceId,
    ) -> Result<HandshakeResult, EndpointError> {
        let synthetic = self.magic.add_relay_addr(device);
        let result = self.connect_synthetic(synthetic).await?;
        if result.peer_device_id != device {
            return Err(EndpointError::PeerIdentityMismatch);
        }
        Ok(result)
    }

    /// Whether this endpoint was bound with a relay attached.
    #[must_use]
    pub fn has_relay(&self) -> bool {
        self.magic.has_relay()
    }

    /// Starts a hole punch toward `device`: advertises our direct-address
    /// `candidates` through the relay so the peer pings them; a confirmed path
    /// upgrades the relayed connection to direct transparently.
    pub fn punch_via_relay(&self, device: DeviceId, candidates: Vec<SocketAddr>) {
        self.magic.punch_via_relay(device, candidates);
    }

    pub async fn accept(&self) -> Result<HandshakeResult, EndpointError> {
        let incoming = self.endpoint.accept().await.ok_or(EndpointError::Closed)?;
        let connection = incoming.await?;
        let (send, receive) = connection.accept_bi().await?;
        self.finish_handshake(connection, send, receive).await
    }

    pub fn close(&self) {
        self.endpoint.close(0_u32.into(), b"fabric endpoint closed");
    }

    pub async fn wait_idle(&self) {
        self.endpoint.wait_idle().await;
    }

    async fn finish_handshake(
        &self,
        connection: noq::Connection,
        send: noq::SendStream,
        receive: noq::RecvStream,
    ) -> Result<HandshakeResult, EndpointError> {
        match perform_handshake(
            connection.clone(),
            send,
            receive,
            &self.identity,
            self.trust_store.as_ref(),
            &self.replay_guard,
            &self.handshake,
        )
        .await
        {
            Ok(result) => Ok(result),
            Err(error) => {
                connection.close(1_u32.into(), b"device handshake failed");
                Err(error.into())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use bytes::Bytes;
    use fabric_core::{ChannelId, ParticipantId, SessionId};
    use fabric_identity::{DeviceTrust, MemoryDeviceTrustStore};
    use fabric_link::{FabricLink, IncomingStream, StreamOpen};
    use fabric_protocol::{ControlFrame, RegistryAck, wire};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;

    fn endpoint(
        seed: u8,
    ) -> (
        QuicEndpoint,
        Arc<DeviceIdentity>,
        Arc<MemoryDeviceTrustStore>,
    ) {
        let identity = Arc::new(DeviceIdentity::from_seed([seed; 32]));
        let trust = Arc::new(MemoryDeviceTrustStore::default());
        let endpoint = QuicEndpoint::bind(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0),
            Arc::clone(&identity),
            trust.clone(),
            LinkHandshakeConfig::default(),
        )
        .unwrap();
        (endpoint, identity, trust)
    }

    #[tokio::test]
    async fn mutual_tls_handshake_and_all_link_receive_paths_work() {
        let (server, server_identity, server_trust) = endpoint(1);
        let (client, client_identity, client_trust) = endpoint(2);
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();

        let server_address = server.local_addr().unwrap();
        let (accepted, connected) = tokio::join!(server.accept(), client.connect(server_address));
        let accepted = accepted.unwrap();
        let connected = connected.unwrap();
        assert_eq!(accepted.peer_device_id, client_identity.device_id());
        assert_eq!(connected.peer_device_id, server_identity.device_id());
        assert_eq!(accepted.peer_trust, DeviceTrust::Trusted);
        assert_eq!(accepted.transcript_hash, connected.transcript_hash);

        let control = ControlFrame::new(
            wire::MessageType::RegistryAck,
            9,
            &wire::RegistryAck::from(RegistryAck { revision: 4 }),
        )
        .unwrap()
        .encode();
        connected
            .link
            .send_control(Bytes::from(control.clone()))
            .await
            .unwrap();
        assert_eq!(accepted.link.receive_control().await.unwrap(), control);

        let stream_header = StreamOpen {
            session_id: SessionId::new(),
            epoch: 1,
            channel_id: ChannelId::new(),
            sender: ParticipantId::new(),
            destination_binding: [3; 16],
            flags: 0,
            e2ee_header: Bytes::new(),
        };
        let mut send = connected
            .link
            .open_uni(stream_header.clone())
            .await
            .unwrap();
        send.write_all(b"payload").await.unwrap();
        send.shutdown().await.unwrap();
        let IncomingStream::Uni(received_header, mut receive) =
            accepted.link.accept_stream().await.unwrap()
        else {
            panic!("expected unidirectional stream");
        };
        let mut payload = Vec::new();
        receive.read_to_end(&mut payload).await.unwrap();
        assert_eq!(received_header, stream_header);
        assert_eq!(payload, b"payload");

        connected
            .link
            .try_send_datagram(Bytes::from_static(b"datagram"))
            .unwrap();
        assert_eq!(
            accepted.link.receive_datagram().await.unwrap(),
            Bytes::from_static(b"datagram")
        );

        client.close();
        server.close();
    }

    #[tokio::test]
    async fn handshake_by_device_id_over_magic_socket() {
        let (server, server_identity, server_trust) = endpoint(3);
        let (client, client_identity, client_trust) = endpoint(4);
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();

        // Dial by identity; the magic socket resolves the DeviceId to the real
        // address and pins the peer to one synthetic address end to end.
        let server_address = server.local_addr().unwrap();
        let (accepted, connected) =
            tokio::time::timeout(std::time::Duration::from_secs(15), async {
                tokio::join!(
                    server.accept(),
                    client.connect_by_device(server_identity.device_id(), server_address),
                )
            })
            .await
            .expect("connect-by-device did not complete: magic socket path is broken");
        let accepted = accepted.unwrap();
        let connected = connected.unwrap();
        assert_eq!(connected.peer_device_id, server_identity.device_id());
        assert_eq!(accepted.peer_device_id, client_identity.device_id());
        assert_eq!(accepted.transcript_hash, connected.transcript_hash);

        // The authenticated link carries control traffic over the magic socket.
        let control = ControlFrame::new(
            wire::MessageType::RegistryAck,
            9,
            &wire::RegistryAck::from(RegistryAck { revision: 7 }),
        )
        .unwrap()
        .encode();
        connected
            .link
            .send_control(Bytes::from(control.clone()))
            .await
            .unwrap();
        assert_eq!(accepted.link.receive_control().await.unwrap(), control);

        client.close();
        server.close();
    }

    #[tokio::test]
    async fn handshake_by_device_id_through_the_relay() {
        use fabric_relay::{RelayConnection, RelayServer};

        // A live relay both endpoints authenticate to; neither registers a
        // direct address for the other, so every QUIC packet — including the
        // whole TLS + HubHello handshake — tunnels through it.
        let relay = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let relay_addr = relay.local_addr();

        let server_identity = Arc::new(DeviceIdentity::from_seed([5; 32]));
        let client_identity = Arc::new(DeviceIdentity::from_seed([6; 32]));
        let server_trust = Arc::new(MemoryDeviceTrustStore::default());
        let client_trust = Arc::new(MemoryDeviceTrustStore::default());
        server_trust
            .set_trust(client_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();
        client_trust
            .set_trust(server_identity.device_id(), DeviceTrust::Trusted)
            .unwrap();

        let server_relay = RelayConnection::connect(relay_addr, &server_identity)
            .await
            .unwrap();
        let client_relay = RelayConnection::connect(relay_addr, &client_identity)
            .await
            .unwrap();

        let server = QuicEndpoint::bind_with_relay(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0),
            server_relay,
            Arc::clone(&server_identity),
            server_trust,
            LinkHandshakeConfig::default(),
        )
        .unwrap();
        let client = QuicEndpoint::bind_with_relay(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0),
            client_relay,
            Arc::clone(&client_identity),
            client_trust,
            LinkHandshakeConfig::default(),
        )
        .unwrap();

        let (accepted, connected) =
            tokio::time::timeout(std::time::Duration::from_secs(20), async {
                tokio::join!(
                    server.accept(),
                    client.connect_by_device_via_relay(server_identity.device_id()),
                )
            })
            .await
            .expect("relay handshake did not complete: relay path is broken");
        let accepted = accepted.unwrap();
        let connected = connected.unwrap();
        assert_eq!(connected.peer_device_id, server_identity.device_id());
        assert_eq!(accepted.peer_device_id, client_identity.device_id());
        assert_eq!(accepted.transcript_hash, connected.transcript_hash);

        // Control traffic flows over the relayed link too.
        let control = ControlFrame::new(
            wire::MessageType::RegistryAck,
            11,
            &wire::RegistryAck::from(RegistryAck { revision: 12 }),
        )
        .unwrap()
        .encode();
        connected
            .link
            .send_control(Bytes::from(control.clone()))
            .await
            .unwrap();
        assert_eq!(accepted.link.receive_control().await.unwrap(), control);

        client.close();
        server.close();
    }
}
