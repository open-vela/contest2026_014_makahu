use std::collections::BTreeSet;

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::{ChannelId, DeviceId, ParticipantId, PortMode, SessionId, SessionPlan};
use fabric_link::StreamOpen;
use thiserror::Error;
use tokio::sync::Semaphore;

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum CarrierKind {
    Quic,
    Ble,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CarrierCapabilities {
    pub quic_messages: bool,
    pub quic_streams: bool,
    pub quic_datagrams: bool,
    pub ble_messages: bool,
}

/// QUIC is preferred when available. BLE is considered only for reliable messages.
#[must_use]
pub fn select_carrier(
    mode: PortMode,
    local: CarrierCapabilities,
    remote: CarrierCapabilities,
    allowed: &BTreeSet<CarrierKind>,
) -> Option<CarrierKind> {
    match mode {
        PortMode::ReliableMessages => {
            if allowed.contains(&CarrierKind::Quic) && local.quic_messages && remote.quic_messages {
                Some(CarrierKind::Quic)
            } else if allowed.contains(&CarrierKind::Ble)
                && local.ble_messages
                && remote.ble_messages
            {
                Some(CarrierKind::Ble)
            } else {
                None
            }
        }
        PortMode::ReliableStream
            if allowed.contains(&CarrierKind::Quic)
                && local.quic_streams
                && remote.quic_streams =>
        {
            Some(CarrierKind::Quic)
        }
        PortMode::Datagram
            if allowed.contains(&CarrierKind::Quic)
                && local.quic_datagrams
                && remote.quic_datagrams =>
        {
            Some(CarrierKind::Quic)
        }
        PortMode::ReliableStream | PortMode::Datagram => None,
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MessageEnvelope {
    pub session_id: SessionId,
    pub epoch: u64,
    pub channel_id: ChannelId,
    pub sender: ParticipantId,
    pub destination: ParticipantId,
    pub sequence: u64,
    pub payload: Bytes,
}

/// A structurally checked channel view. This does not prove commit or policy
/// authorization: Hub admission must verify those against its internal state.
#[derive(Clone, Debug)]
pub struct AuthorizedMessageChannel {
    session_id: SessionId,
    epoch: u64,
    channel_id: ChannelId,
    plan_hash: [u8; 32],
    local_device: DeviceId,
    peer_device: DeviceId,
    local_participant: ParticipantId,
    local_sources: BTreeSet<ParticipantId>,
    local_destinations: BTreeSet<ParticipantId>,
    peer_sources: BTreeSet<ParticipantId>,
    peer_destinations: BTreeSet<ParticipantId>,
    max_message_bytes: usize,
    max_buffered_bytes: usize,
}

impl AuthorizedMessageChannel {
    pub fn from_plan(
        plan: &SessionPlan,
        channel_id: ChannelId,
        local_device: DeviceId,
        local_participant: ParticipantId,
        peer_device: DeviceId,
    ) -> Result<Self, TransportError> {
        plan.validate()
            .map_err(|_| TransportError::InvalidChannel)?;
        let channel = plan
            .channels
            .iter()
            .find(|channel| channel.id == channel_id)
            .ok_or(TransportError::InvalidChannel)?;
        if channel.contract.mode != PortMode::ReliableMessages {
            return Err(TransportError::UnsupportedMode);
        }
        // These carriers currently provide device-link encryption only. Never
        // silently satisfy an application E2EE contract with a Hub-readable link.
        if channel.contract.payload_security != fabric_core::PayloadSecurity::HubReadable {
            return Err(TransportError::UnsupportedCapability);
        }
        let participant_devices: std::collections::BTreeMap<_, _> = plan
            .participants
            .iter()
            .map(|participant| (participant.id, participant.device_id))
            .collect();
        if participant_devices.get(&local_participant) != Some(&local_device) {
            return Err(TransportError::InvalidChannel);
        }
        if local_device == peer_device {
            return Err(TransportError::InvalidChannel);
        }
        let local_sources: BTreeSet<_> = channel
            .sources
            .iter()
            .filter_map(|endpoint| {
                (participant_devices.get(&endpoint.participant) == Some(&local_device))
                    .then_some(endpoint.participant)
            })
            .collect();
        let local_destinations: BTreeSet<_> = channel
            .destinations
            .iter()
            .filter_map(|endpoint| {
                (participant_devices.get(&endpoint.participant) == Some(&local_device))
                    .then_some(endpoint.participant)
            })
            .collect();
        let peer_sources: BTreeSet<_> = channel
            .sources
            .iter()
            .filter_map(|endpoint| {
                (participant_devices.get(&endpoint.participant) == Some(&peer_device))
                    .then_some(endpoint.participant)
            })
            .collect();
        let peer_destinations: BTreeSet<_> = channel
            .destinations
            .iter()
            .filter_map(|endpoint| {
                (participant_devices.get(&endpoint.participant) == Some(&peer_device))
                    .then_some(endpoint.participant)
            })
            .collect();
        if local_sources.is_empty() && local_destinations.is_empty()
            || peer_sources.is_empty() && peer_destinations.is_empty()
            || (!local_sources.contains(&local_participant)
                && !local_destinations.contains(&local_participant))
        {
            return Err(TransportError::InvalidChannel);
        }
        let max_buffered_bytes = usize::try_from(channel.contract.max_buffered_bytes)
            .map_err(|_| TransportError::InvalidChannel)?;
        let max_message_bytes = usize::try_from(
            channel
                .contract
                .max_message_bytes
                .ok_or(TransportError::InvalidChannel)?,
        )
        .map_err(|_| TransportError::InvalidChannel)?
        .min(max_buffered_bytes)
        .min(Semaphore::MAX_PERMITS)
        .min(u32::MAX as usize);
        if max_message_bytes == 0 || max_buffered_bytes == 0 {
            return Err(TransportError::CapabilityMismatch);
        }
        Ok(Self {
            session_id: plan.session_id,
            epoch: plan.epoch,
            channel_id,
            plan_hash: plan.canonical_hash(),
            local_device,
            peer_device,
            local_participant,
            local_sources,
            local_destinations,
            peer_sources,
            peer_destinations,
            max_message_bytes,
            max_buffered_bytes,
        })
    }

    pub fn validate_outbound(
        &self,
        destination: ParticipantId,
        payload_len: usize,
    ) -> Result<(), TransportError> {
        if !self.local_sources.contains(&self.local_participant)
            || !self.peer_destinations.contains(&destination)
        {
            return Err(TransportError::UnauthorizedDestination);
        }
        if payload_len > self.max_message_bytes {
            return Err(TransportError::MessageTooLarge);
        }
        Ok(())
    }

    fn validate_inbound(&self, message: &MessageEnvelope) -> Result<(), TransportError> {
        if message.session_id != self.session_id
            || message.epoch != self.epoch
            || message.channel_id != self.channel_id
        {
            return Err(TransportError::StaleSession);
        }
        if !self.peer_sources.contains(&message.sender)
            || !self.local_destinations.contains(&message.destination)
        {
            return Err(TransportError::UnauthorizedDestination);
        }
        if message.payload.len() > self.max_message_bytes {
            return Err(TransportError::MessageTooLarge);
        }
        Ok(())
    }

    pub(crate) fn validate_stream_open(
        &self,
        peer_device: DeviceId,
        open: &StreamOpen,
    ) -> Result<(), TransportError> {
        if peer_device != self.peer_device
            || open.session_id != self.session_id
            || open.epoch != self.epoch
            || open.channel_id != self.channel_id
            || !self.peer_sources.contains(&open.sender)
        {
            return Err(TransportError::PeerMismatch);
        }
        Ok(())
    }

    pub(crate) fn session_id(&self) -> SessionId {
        self.session_id
    }

    pub(crate) fn epoch(&self) -> u64 {
        self.epoch
    }

    pub(crate) fn channel_id(&self) -> ChannelId {
        self.channel_id
    }

    pub(crate) fn plan_hash(&self) -> [u8; 32] {
        self.plan_hash
    }

    pub(crate) fn local_device(&self) -> DeviceId {
        self.local_device
    }

    pub(crate) fn peer_device(&self) -> DeviceId {
        self.peer_device
    }

    pub(crate) fn local_participant(&self) -> ParticipantId {
        self.local_participant
    }

    pub(crate) fn max_message_bytes(&self) -> usize {
        self.max_message_bytes
    }

    pub(crate) fn max_buffered_bytes(&self) -> usize {
        self.max_buffered_bytes
    }

    pub(crate) fn verify_inbound(&self, message: &MessageEnvelope) -> Result<(), TransportError> {
        self.validate_inbound(message)
    }
}

#[async_trait]
pub trait ReliableMessageTransport: Send + Sync {
    fn carrier(&self) -> CarrierKind;
    /// Completes when the reliable carrier accepts the message, not when the
    /// receiving application has processed it; use an application response for
    /// that result.
    async fn send(&self, destination: ParticipantId, payload: Bytes) -> Result<(), TransportError>;
    async fn recv(&self) -> Result<MessageEnvelope, TransportError>;
}

#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum TransportError {
    #[error("BLE message transport is unsupported on this platform")]
    UnsupportedCapability,
    #[error("channel is not a valid authorized ReliableMessages channel")]
    InvalidChannel,
    #[error("transport does not support this channel mode")]
    UnsupportedMode,
    #[error("peer identity does not match the committed Session participant")]
    PeerMismatch,
    #[error("carrier capability negotiation failed")]
    CapabilityMismatch,
    #[error("message is larger than the channel contract allows")]
    MessageTooLarge,
    #[error("message queue is full")]
    Backpressure,
    #[error("message delivery timed out")]
    Timeout,
    #[error("message belongs to a stale Session or epoch")]
    StaleSession,
    #[error("sender or destination is not authorized for this channel")]
    UnauthorizedDestination,
    #[error("peer violated the BLE message protocol")]
    ProtocolViolation,
    #[error("transport is closed")]
    Closed,
    #[error("receiver has been dropped")]
    ReceiverGone,
}

pub(crate) fn encode_message(message: &MessageEnvelope) -> Result<Bytes, TransportError> {
    let length =
        u32::try_from(message.payload.len()).map_err(|_| TransportError::MessageTooLarge)?;
    let mut output = Vec::with_capacity(16 + 8 + 16 + 16 + 16 + 8 + 4 + message.payload.len());
    output.extend_from_slice(message.session_id.0.as_bytes());
    output.extend_from_slice(&message.epoch.to_be_bytes());
    output.extend_from_slice(message.channel_id.0.as_bytes());
    output.extend_from_slice(message.sender.0.as_bytes());
    output.extend_from_slice(message.destination.0.as_bytes());
    output.extend_from_slice(&message.sequence.to_be_bytes());
    output.extend_from_slice(&length.to_be_bytes());
    output.extend_from_slice(&message.payload);
    Ok(Bytes::from(output))
}

pub(crate) fn decode_message(bytes: &[u8]) -> Result<MessageEnvelope, TransportError> {
    const HEADER: usize = 16 + 8 + 16 + 16 + 16 + 8 + 4;
    if bytes.len() < HEADER {
        return Err(TransportError::ProtocolViolation);
    }
    let session_id = SessionId(
        uuid::Uuid::from_slice(&bytes[..16]).map_err(|_| TransportError::ProtocolViolation)?,
    );
    let epoch = u64::from_be_bytes(
        bytes[16..24]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    );
    let channel_id = ChannelId(
        uuid::Uuid::from_slice(&bytes[24..40]).map_err(|_| TransportError::ProtocolViolation)?,
    );
    let sender = ParticipantId(
        uuid::Uuid::from_slice(&bytes[40..56]).map_err(|_| TransportError::ProtocolViolation)?,
    );
    let destination = ParticipantId(
        uuid::Uuid::from_slice(&bytes[56..72]).map_err(|_| TransportError::ProtocolViolation)?,
    );
    let sequence = u64::from_be_bytes(
        bytes[72..80]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    );
    let payload_len = usize::try_from(u32::from_be_bytes(
        bytes[80..84]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
    .map_err(|_| TransportError::ProtocolViolation)?;
    if bytes.len() != HEADER.saturating_add(payload_len) {
        return Err(TransportError::ProtocolViolation);
    }
    Ok(MessageEnvelope {
        session_id,
        epoch,
        channel_id,
        sender,
        destination,
        sequence,
        payload: Bytes::copy_from_slice(&bytes[HEADER..]),
    })
}
