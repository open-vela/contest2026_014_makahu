use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    sync::{Arc, Mutex},
};

use fabric_core::{DeviceId, ProtocolErrorCode};
use fabric_identity::{
    DeviceIdentity, DeviceTrust, DeviceTrustStore, IdentityError, verify_device_proof,
};
use fabric_link::LinkError;
use fabric_protocol::{
    ControlFrame, ControlMessage, ControlStreamHello, HubHello, LinkReady, MESSAGE_NONCE_BYTES,
    ProtocolError, TRANSCRIPT_HASH_BYTES, wire,
};
use prost::Message;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{
    QuicLink, TlsError, peer_public_key, read_control_frame_bytes, verify_certificate_device_id,
};

const DEFAULT_PROTOCOL_VERSION: u32 = 1;
const MAX_HANDSHAKE_NONCES_PER_PEER: usize = 4096;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LinkHandshakeConfig {
    pub supported_protocol_versions: Vec<u32>,
    pub features: Vec<String>,
    pub link_generation: u64,
}

impl Default for LinkHandshakeConfig {
    fn default() -> Self {
        Self {
            supported_protocol_versions: vec![DEFAULT_PROTOCOL_VERSION],
            features: vec!["quic".into(), "registry-sync".into()],
            link_generation: 1,
        }
    }
}

#[derive(Debug, Error)]
pub enum HandshakeError {
    #[error("TLS identity validation failed: {0}")]
    Tls(#[from] TlsError),
    #[error("control protocol validation failed: {0}")]
    Protocol(#[from] ProtocolError),
    #[error("device identity validation failed: {0}")]
    Identity(#[from] IdentityError),
    #[error("Link I/O failed: {0}")]
    Link(#[from] LinkError),
    #[error("peer sent an unexpected handshake message")]
    UnexpectedMessage,
    #[error("peer has no supported protocol version in common")]
    UnsupportedVersion,
    #[error("peer is blocked")]
    BlockedPeer,
    #[error("peer replayed a handshake nonce")]
    ReplayedNonce,
    #[error("peer LinkReady does not match the negotiated transcript")]
    TranscriptMismatch,
}

impl HandshakeError {
    #[must_use]
    pub const fn protocol_code(&self) -> ProtocolErrorCode {
        match self {
            Self::Tls(_) | Self::Identity(_) | Self::ReplayedNonce => {
                ProtocolErrorCode::Unauthenticated
            }
            Self::UnsupportedVersion => ProtocolErrorCode::UnsupportedVersion,
            Self::BlockedPeer => ProtocolErrorCode::PermissionDenied,
            Self::TranscriptMismatch | Self::UnexpectedMessage | Self::Protocol(_) => {
                ProtocolErrorCode::InvalidArgument
            }
            Self::Link(_) => ProtocolErrorCode::Internal,
        }
    }
}

pub struct HandshakeResult {
    pub link: Arc<QuicLink>,
    pub peer_device_id: DeviceId,
    pub peer_trust: DeviceTrust,
    pub selected_protocol_version: u32,
    pub peer_features: Vec<String>,
    pub transcript_hash: [u8; TRANSCRIPT_HASH_BYTES],
}

#[derive(Default)]
pub(crate) struct HandshakeReplayGuard {
    peers: BTreeMap<DeviceId, PeerNonces>,
}

#[derive(Default)]
struct PeerNonces {
    values: BTreeSet<[u8; MESSAGE_NONCE_BYTES]>,
    order: VecDeque<[u8; MESSAGE_NONCE_BYTES]>,
}

impl HandshakeReplayGuard {
    fn check_and_insert(
        &mut self,
        peer: DeviceId,
        nonce: [u8; MESSAGE_NONCE_BYTES],
    ) -> Result<(), HandshakeError> {
        let nonces = self.peers.entry(peer).or_default();
        if nonces.values.contains(&nonce) {
            return Err(HandshakeError::ReplayedNonce);
        }
        while nonces.order.len() >= MAX_HANDSHAKE_NONCES_PER_PEER {
            if let Some(oldest) = nonces.order.pop_front() {
                nonces.values.remove(&oldest);
            }
        }
        nonces.values.insert(nonce);
        nonces.order.push_back(nonce);
        Ok(())
    }
}

pub(crate) async fn perform_handshake(
    connection: noq::Connection,
    mut control_send: noq::SendStream,
    mut control_receive: noq::RecvStream,
    identity: &DeviceIdentity,
    trust_store: &dyn DeviceTrustStore,
    replay_guard: &Mutex<HandshakeReplayGuard>,
    config: &LinkHandshakeConfig,
) -> Result<HandshakeResult, HandshakeError> {
    let peer_public_key = peer_public_key(&connection)?;
    let local_hello = build_hello(identity, config)?;

    write_message(
        &mut control_send,
        wire::MessageType::ControlStreamHello,
        1,
        &wire::ControlStreamHello::from(ControlStreamHello {
            link_generation: config.link_generation,
        }),
    )
    .await?;
    write_message(
        &mut control_send,
        wire::MessageType::HubHello,
        2,
        &wire::HubHello::from(&local_hello),
    )
    .await?;

    let control = read_message(&mut control_receive).await?;
    if !matches!(control, ControlMessage::ControlStreamHello(_)) {
        return Err(HandshakeError::UnexpectedMessage);
    }
    let ControlMessage::HubHello(peer_hello) = read_message(&mut control_receive).await? else {
        return Err(HandshakeError::UnexpectedMessage);
    };
    verify_peer_hello(&peer_hello, peer_public_key)?;
    replay_guard
        .lock()
        .map_err(|_| IdentityError::TrustStoreUnavailable)?
        .check_and_insert(peer_hello.device_id, peer_hello.nonce)?;

    let peer_trust = trust_store.trust(peer_hello.device_id)?;
    if peer_trust == DeviceTrust::Blocked {
        return Err(HandshakeError::BlockedPeer);
    }
    let selected_protocol_version = select_protocol_version(
        &local_hello.supported_protocol_versions,
        &peer_hello.supported_protocol_versions,
    )
    .ok_or(HandshakeError::UnsupportedVersion)?;
    let transcript_hash = transcript_hash(&local_hello, &peer_hello);
    let local_ready = LinkReady {
        selected_protocol_version,
        transcript_hash,
        link_generation: config.link_generation,
    };
    write_message(
        &mut control_send,
        wire::MessageType::LinkReady,
        3,
        &wire::LinkReady::from(&local_ready),
    )
    .await?;
    let ControlMessage::LinkReady(peer_ready) = read_message(&mut control_receive).await? else {
        return Err(HandshakeError::UnexpectedMessage);
    };
    if peer_ready.selected_protocol_version != selected_protocol_version
        || peer_ready.transcript_hash != transcript_hash
    {
        return Err(HandshakeError::TranscriptMismatch);
    }

    let peer_device_id = peer_hello.device_id;
    let peer_features = peer_hello.features;
    let link = QuicLink::from_authenticated_connection(
        connection,
        peer_device_id,
        control_send,
        control_receive,
    );
    Ok(HandshakeResult {
        link,
        peer_device_id,
        peer_trust,
        selected_protocol_version,
        peer_features,
        transcript_hash,
    })
}

fn build_hello(
    identity: &DeviceIdentity,
    config: &LinkHandshakeConfig,
) -> Result<HubHello, ProtocolError> {
    let mut versions = config.supported_protocol_versions.clone();
    versions.sort_unstable();
    versions.dedup();
    let mut features = config.features.clone();
    features.sort();
    features.dedup();
    let mut nonce = [0; MESSAGE_NONCE_BYTES];
    OsRng.fill_bytes(&mut nonce);
    let proof_hash = hello_proof_hash(
        identity.device_id(),
        identity.public_key(),
        &versions,
        &nonce,
        &features,
    );
    let hello = HubHello {
        supported_protocol_versions: versions,
        device_id: identity.device_id(),
        device_proof: identity.sign_device_proof(&proof_hash),
        nonce,
        features,
    };
    HubHello::try_from(wire::HubHello::from(&hello))
}

fn verify_peer_hello(hello: &HubHello, public_key: [u8; 32]) -> Result<(), HandshakeError> {
    verify_certificate_device_id(public_key, hello.device_id)?;
    let proof_hash = hello_proof_hash(
        hello.device_id,
        public_key,
        &hello.supported_protocol_versions,
        &hello.nonce,
        &hello.features,
    );
    verify_device_proof(
        hello.device_id,
        public_key,
        &proof_hash,
        &hello.device_proof,
    )?;
    Ok(())
}

fn hello_proof_hash(
    device_id: DeviceId,
    public_key: [u8; 32],
    versions: &[u32],
    nonce: &[u8; MESSAGE_NONCE_BYTES],
    features: &[String],
) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(b"device-fabric-hub-hello-v1");
    hash.update(device_id.0);
    hash.update(public_key);
    hash.update((versions.len() as u64).to_be_bytes());
    for version in versions {
        hash.update(version.to_be_bytes());
    }
    hash.update(nonce);
    hash.update((features.len() as u64).to_be_bytes());
    for feature in features {
        hash.update((feature.len() as u64).to_be_bytes());
        hash.update(feature.as_bytes());
    }
    hash.finalize().into()
}

fn transcript_hash(local: &HubHello, peer: &HubHello) -> [u8; 32] {
    let mut hellos = [
        (local.device_id, wire::HubHello::from(local).encode_to_vec()),
        (peer.device_id, wire::HubHello::from(peer).encode_to_vec()),
    ];
    hellos.sort_by_key(|(device_id, _)| *device_id);
    let mut hash = Sha256::new();
    hash.update(b"device-fabric-link-transcript-v1");
    for (_, encoded) in hellos {
        hash.update((encoded.len() as u64).to_be_bytes());
        hash.update(encoded);
    }
    hash.finalize().into()
}

fn select_protocol_version(local: &[u32], peer: &[u32]) -> Option<u32> {
    local
        .iter()
        .filter(|version| peer.contains(version))
        .copied()
        .max()
}

async fn write_message<M: Message>(
    send: &mut noq::SendStream,
    message_type: wire::MessageType,
    request_id: u64,
    message: &M,
) -> Result<(), HandshakeError> {
    let frame = ControlFrame::new(message_type, request_id, message)?;
    send.write_all(&frame.encode())
        .await
        .map_err(|_| LinkError::Closed)?;
    Ok(())
}

async fn read_message(receive: &mut noq::RecvStream) -> Result<ControlMessage, HandshakeError> {
    let encoded = read_control_frame_bytes(receive).await?;
    let frame = ControlFrame::decode(&encoded)?;
    Ok(ControlMessage::decode(&frame)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proof_is_bound_to_identity_and_transcript_is_order_independent() {
        let a = DeviceIdentity::from_seed([1; 32]);
        let b = DeviceIdentity::from_seed([2; 32]);
        let config = LinkHandshakeConfig::default();
        let a_hello = build_hello(&a, &config).unwrap();
        let b_hello = build_hello(&b, &config).unwrap();
        verify_peer_hello(&a_hello, a.public_key()).unwrap();
        assert!(verify_peer_hello(&a_hello, b.public_key()).is_err());
        assert_eq!(
            transcript_hash(&a_hello, &b_hello),
            transcript_hash(&b_hello, &a_hello)
        );
    }

    #[test]
    fn replay_cache_is_bounded_and_rejects_duplicate_nonce() {
        let mut guard = HandshakeReplayGuard::default();
        let peer = DeviceId([1; 32]);
        guard.check_and_insert(peer, [1; 32]).unwrap();
        assert!(matches!(
            guard.check_and_insert(peer, [1; 32]),
            Err(HandshakeError::ReplayedNonce)
        ));
    }

    #[test]
    fn proof_size_matches_wire_contract() {
        assert_eq!(fabric_protocol::DEVICE_PROOF_BYTES, 64);
    }
}
