//! Protobuf wire types, bounded control framing, and explicit domain conversions.

use bytes::{Buf, Bytes};
use fabric_core::{
    AbilityContractRef, AbilityInstanceId, AbilityKey, AbilityName, BarrierId, CapabilityGrantId,
    ChannelId, ClockDomainId, ConnectionId, DeviceId, InvitationId, Namespace, OperationId,
    ParticipantId, PolicySnapshotId, PortId, ProtocolErrorCode, RequirementId, SessionId,
    ValidationError,
};
use prost::Message;
use thiserror::Error;
use uuid::Uuid;

#[allow(clippy::doc_markdown, clippy::must_use_candidate)]
pub mod wire {
    include!(concat!(env!("OUT_DIR"), "/device.fabric.v1.rs"));
}

mod messages;
pub use messages::*;
mod dispatch;
pub use dispatch::{ControlMessage, SessionIngress};
mod security;
pub use security::{
    ControlSecurity, ControlSecurityConfig, EpochPolicy, IngressDecision, SecurityError,
};

pub const MAX_CONTROL_FRAME_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_COLLECTION_ITEMS: usize = 4096;
pub const DEVICE_PROOF_BYTES: usize = 64;
pub const MAX_FEATURE_TOKEN_BYTES: usize = 128;
pub const MESSAGE_NONCE_BYTES: usize = 32;
pub const TRANSCRIPT_HASH_BYTES: usize = 32;
pub const PLAN_HASH_BYTES: usize = 32;
pub const MAX_OPAQUE_BYTES: usize = 1024 * 1024;
pub const MAX_SAFE_MESSAGE_BYTES: usize = 1024;
pub const MAX_REASON_BYTES: usize = 1024;
pub const MAX_RESUME_TOKEN_BYTES: usize = 4096;
pub const MAX_KEY_MATERIAL_BYTES: usize = 64 * 1024;
pub const MAX_E2EE_HEADER_BYTES: usize = 64 * 1024;

#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("control frame exceeds the {MAX_CONTROL_FRAME_BYTES} byte limit")]
    FrameTooLarge,
    #[error("truncated control frame")]
    Truncated,
    #[error("invalid varint")]
    InvalidVarint,
    #[error("control frame contains trailing bytes")]
    TrailingData,
    #[error("protobuf decode failed: {0}")]
    Decode(#[from] prost::DecodeError),
    #[error("invalid domain value: {0}")]
    Domain(#[from] ValidationError),
    #[error("missing field: {0}")]
    MissingField(&'static str),
    #[error("{field} must contain exactly {expected} bytes, got {actual}")]
    InvalidLength {
        field: &'static str,
        expected: usize,
        actual: usize,
    },
    #[error("collection {field} exceeds {MAX_COLLECTION_ITEMS} items")]
    CollectionTooLarge { field: &'static str },
    #[error("{field} must not be empty")]
    EmptyField { field: &'static str },
    #[error("{field} exceeds {max} bytes: got {actual}")]
    FieldTooLarge {
        field: &'static str,
        max: usize,
        actual: usize,
    },
    #[error("{field} must not be UNSPECIFIED")]
    UnspecifiedEnum { field: &'static str },
    #[error("{field} has unknown enum value {value}")]
    UnknownEnumValue { field: &'static str, value: i32 },
    #[error("unknown message type {0}")]
    UnknownMessageType(u64),
}

/// A decoded outer frame. The Protobuf bytes remain untouched, preserving unknown fields.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ControlFrame {
    pub message_type: u64,
    pub request_id: u64,
    pub payload: Bytes,
}

impl ControlFrame {
    pub fn new<M: Message>(
        message_type: wire::MessageType,
        request_id: u64,
        message: &M,
    ) -> Result<Self, ProtocolError> {
        let payload = message.encode_to_vec();
        if payload.len() > MAX_CONTROL_FRAME_BYTES {
            return Err(ProtocolError::FrameTooLarge);
        }
        Ok(Self {
            message_type: message_type as u64,
            request_id,
            payload: Bytes::from(payload),
        })
    }

    /// Decodes a typed message while the frame retains its original payload for lossless relay.
    pub fn decode_message<M: Message + Default>(&self) -> Result<M, ProtocolError> {
        Ok(M::decode(self.payload.clone())?)
    }

    pub fn validated_message_type(&self) -> Result<wire::MessageType, ProtocolError> {
        let value = i32::try_from(self.message_type)
            .map_err(|_| ProtocolError::UnknownMessageType(self.message_type))?;
        message_type_from_i32(value)
    }

    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let body_len =
            varint_len(self.message_type) + varint_len(self.request_id) + self.payload.len();
        let mut output = Vec::with_capacity(varint_len(body_len as u64) + body_len);
        put_varint(&mut output, body_len as u64);
        put_varint(&mut output, self.message_type);
        put_varint(&mut output, self.request_id);
        output.extend_from_slice(&self.payload);
        output
    }

    pub fn decode(mut input: &[u8]) -> Result<Self, ProtocolError> {
        let frame_length = read_varint(&mut input)?;
        let frame_length =
            usize::try_from(frame_length).map_err(|_| ProtocolError::FrameTooLarge)?;
        if frame_length > MAX_CONTROL_FRAME_BYTES {
            return Err(ProtocolError::FrameTooLarge);
        }
        if input.len() < frame_length {
            return Err(ProtocolError::Truncated);
        }
        if input.len() != frame_length {
            return Err(ProtocolError::TrailingData);
        }
        let mut body = &input[..frame_length];
        let message_type = read_varint(&mut body)?;
        let request_id = read_varint(&mut body)?;
        Ok(Self {
            message_type,
            request_id,
            payload: Bytes::copy_from_slice(body),
        })
    }
}

fn put_varint(output: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        output.push(u8::try_from(value & 0x7f).expect("masked varint byte fits") | 0x80);
        value >>= 7;
    }
    output.push(u8::try_from(value).expect("final varint byte fits"));
}

fn read_varint(input: &mut &[u8]) -> Result<u64, ProtocolError> {
    let mut value = 0_u64;
    for shift in (0..=63).step_by(7) {
        let Some(byte) = input.first().copied() else {
            return Err(ProtocolError::Truncated);
        };
        input.advance(1);
        if shift == 63 && byte > 1 {
            return Err(ProtocolError::InvalidVarint);
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(ProtocolError::InvalidVarint)
}

const fn varint_len(mut value: u64) -> usize {
    let mut length = 1;
    while value >= 0x80 {
        value >>= 7;
        length += 1;
    }
    length
}

macro_rules! id128_conversions {
    ($($name:ident => $field:literal),+ $(,)?) => {
        $(
            impl From<$name> for wire::Id128 {
                fn from(value: $name) -> Self {
                    Self {
                        value: value.0.as_bytes().to_vec(),
                    }
                }
            }

            impl From<&$name> for wire::Id128 {
                fn from(value: &$name) -> Self {
                    Self {
                        value: value.0.as_bytes().to_vec(),
                    }
                }
            }

            impl TryFrom<wire::Id128> for $name {
                type Error = ProtocolError;

                fn try_from(value: wire::Id128) -> Result<Self, Self::Error> {
                    Ok(Self(Uuid::from_bytes(exact_array::<16>(value.value, $field)?)))
                }
            }
        )+
    };
}

id128_conversions!(
    SessionId => "session_id",
    AbilityInstanceId => "ability_instance_id",
    RequirementId => "requirement_id",
    ParticipantId => "participant_id",
    ChannelId => "channel_id",
    PortId => "port_id",
    PolicySnapshotId => "policy_snapshot_id",
    ClockDomainId => "clock_domain_id",
    BarrierId => "barrier_id",
    InvitationId => "invitation_id",
    ConnectionId => "connection_id",
    OperationId => "operation_id",
    CapabilityGrantId => "capability_grant_id",
);

impl From<DeviceId> for wire::DeviceId {
    fn from(value: DeviceId) -> Self {
        Self {
            sha256_public_key: value.0.to_vec(),
        }
    }
}

impl From<&DeviceId> for wire::DeviceId {
    fn from(value: &DeviceId) -> Self {
        Self {
            sha256_public_key: value.0.to_vec(),
        }
    }
}

impl TryFrom<wire::DeviceId> for DeviceId {
    type Error = ProtocolError;

    fn try_from(value: wire::DeviceId) -> Result<Self, Self::Error> {
        Ok(Self(exact_array::<32>(
            value.sha256_public_key,
            "device_id.sha256_public_key",
        )?))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HubHello {
    pub supported_protocol_versions: Vec<u32>,
    pub device_id: DeviceId,
    pub device_proof: [u8; DEVICE_PROOF_BYTES],
    pub nonce: [u8; MESSAGE_NONCE_BYTES],
    pub features: Vec<String>,
}

impl From<&HubHello> for wire::HubHello {
    fn from(value: &HubHello) -> Self {
        Self {
            supported_protocol_versions: value.supported_protocol_versions.clone(),
            device_id: Some((&value.device_id).into()),
            device_proof: value.device_proof.to_vec(),
            nonce: value.nonce.to_vec(),
            features: value.features.clone(),
        }
    }
}

impl TryFrom<wire::HubHello> for HubHello {
    type Error = ProtocolError;

    fn try_from(value: wire::HubHello) -> Result<Self, Self::Error> {
        check_collection_len(
            "hub_hello.supported_protocol_versions",
            value.supported_protocol_versions.len(),
        )?;
        if value.supported_protocol_versions.is_empty() {
            return Err(ProtocolError::Domain(ValidationError::Empty {
                field: "hub_hello.supported_protocol_versions",
            }));
        }
        if value.supported_protocol_versions.contains(&0) {
            return Err(ProtocolError::Domain(ValidationError::InvalidValue {
                field: "hub_hello.supported_protocol_versions",
                reason: "protocol version 0 is reserved",
            }));
        }
        check_collection_len("hub_hello.features", value.features.len())?;
        for feature in &value.features {
            validate_feature_token(feature)?;
        }
        Ok(Self {
            supported_protocol_versions: value.supported_protocol_versions,
            device_id: value
                .device_id
                .ok_or(ProtocolError::MissingField("hub_hello.device_id"))?
                .try_into()?,
            device_proof: exact_array::<DEVICE_PROOF_BYTES>(
                value.device_proof,
                "hub_hello.device_proof",
            )?,
            nonce: exact_array::<MESSAGE_NONCE_BYTES>(value.nonce, "hub_hello.nonce")?,
            features: value.features,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LinkReady {
    pub selected_protocol_version: u32,
    pub transcript_hash: [u8; TRANSCRIPT_HASH_BYTES],
    pub link_generation: u64,
}

impl From<&LinkReady> for wire::LinkReady {
    fn from(value: &LinkReady) -> Self {
        Self {
            selected_protocol_version: value.selected_protocol_version,
            transcript_hash: value.transcript_hash.to_vec(),
            link_generation: value.link_generation,
        }
    }
}

impl TryFrom<wire::LinkReady> for LinkReady {
    type Error = ProtocolError;

    fn try_from(value: wire::LinkReady) -> Result<Self, Self::Error> {
        if value.selected_protocol_version == 0 {
            return Err(ProtocolError::Domain(ValidationError::InvalidValue {
                field: "link_ready.selected_protocol_version",
                reason: "protocol version 0 is reserved",
            }));
        }
        Ok(Self {
            selected_protocol_version: value.selected_protocol_version,
            transcript_hash: exact_array::<TRANSCRIPT_HASH_BYTES>(
                value.transcript_hash,
                "link_ready.transcript_hash",
            )?,
            link_generation: value.link_generation,
        })
    }
}

impl From<&AbilityKey> for wire::AbilityKey {
    fn from(value: &AbilityKey) -> Self {
        Self {
            namespace: value.namespace.as_str().to_owned(),
            name: value.name.as_str().to_owned(),
            major: value.major,
        }
    }
}

impl TryFrom<wire::AbilityKey> for AbilityKey {
    type Error = ProtocolError;

    fn try_from(value: wire::AbilityKey) -> Result<Self, Self::Error> {
        Ok(Self {
            namespace: Namespace::new(value.namespace)?,
            name: AbilityName::new(value.name)?,
            major: value.major,
        })
    }
}

impl From<&AbilityContractRef> for wire::AbilityContractRef {
    fn from(value: &AbilityContractRef) -> Self {
        Self {
            key: Some((&value.key).into()),
            protocol_hash: value.protocol_hash.to_vec(),
        }
    }
}

impl TryFrom<wire::AbilityContractRef> for AbilityContractRef {
    type Error = ProtocolError;

    fn try_from(value: wire::AbilityContractRef) -> Result<Self, Self::Error> {
        let key = value
            .key
            .ok_or(ProtocolError::MissingField("contract.key"))?
            .try_into()?;
        let protocol_hash = exact_array::<32>(value.protocol_hash, "contract.protocol_hash")?;
        Ok(Self { key, protocol_hash })
    }
}

macro_rules! revision_message {
    ($name:ident, $wire:ident, $field:ident) => {
        #[derive(Clone, Copy, Debug, Eq, PartialEq)]
        pub struct $name {
            pub $field: u64,
        }

        impl From<$name> for wire::$wire {
            fn from(value: $name) -> Self {
                Self {
                    $field: value.$field,
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    $field: value.$field,
                })
            }
        }
    };
}

revision_message!(RegistrySnapshotBegin, RegistrySnapshotBegin, revision);
revision_message!(RegistrySnapshotEnd, RegistrySnapshotEnd, revision);
revision_message!(RegistryAck, RegistryAck, revision);
revision_message!(
    RegistryResyncRequest,
    RegistryResyncRequest,
    applied_revision
);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OfferRemove {
    pub revision: u64,
    pub ability_instance_id: AbilityInstanceId,
}

impl From<&OfferRemove> for wire::OfferRemove {
    fn from(value: &OfferRemove) -> Self {
        Self {
            revision: value.revision,
            ability_instance_id: Some((&value.ability_instance_id).into()),
        }
    }
}

impl TryFrom<wire::OfferRemove> for OfferRemove {
    type Error = ProtocolError;

    fn try_from(value: wire::OfferRemove) -> Result<Self, Self::Error> {
        Ok(Self {
            revision: value.revision,
            ability_instance_id: value
                .ability_instance_id
                .ok_or(ProtocolError::MissingField(
                    "offer_remove.ability_instance_id",
                ))?
                .try_into()?,
        })
    }
}

impl From<ProtocolErrorCode> for wire::ErrorCode {
    fn from(value: ProtocolErrorCode) -> Self {
        match value {
            ProtocolErrorCode::InvalidArgument => Self::InvalidArgument,
            ProtocolErrorCode::UnsupportedVersion => Self::UnsupportedVersion,
            ProtocolErrorCode::ContractMismatch => Self::ContractMismatch,
            ProtocolErrorCode::NotFound => Self::NotFound,
            ProtocolErrorCode::AlreadyExists => Self::AlreadyExists,
            ProtocolErrorCode::Unauthenticated => Self::Unauthenticated,
            ProtocolErrorCode::PermissionDenied => Self::PermissionDenied,
            ProtocolErrorCode::PolicyRequiresUserAction => Self::PolicyRequiresUserAction,
            ProtocolErrorCode::ResourceExhausted => Self::ResourceExhausted,
            ProtocolErrorCode::StaleEpoch => Self::StaleEpoch,
            ProtocolErrorCode::SessionNotActive => Self::SessionNotActive,
            ProtocolErrorCode::ParticipantOffline => Self::ParticipantOffline,
            ProtocolErrorCode::ChannelNotBound => Self::ChannelNotBound,
            ProtocolErrorCode::MessageTooLarge => Self::MessageTooLarge,
            ProtocolErrorCode::Backpressure => Self::Backpressure,
            ProtocolErrorCode::ClockUnavailable => Self::ClockUnavailable,
            ProtocolErrorCode::ClockUncertain => Self::ClockUncertain,
            ProtocolErrorCode::E2eeRequired => Self::E2eeRequired,
            ProtocolErrorCode::E2eeNegotiationFailed => Self::E2eeNegotiationFailed,
            ProtocolErrorCode::Timeout => Self::Timeout,
            ProtocolErrorCode::Internal => Self::Internal,
        }
    }
}

impl TryFrom<wire::ErrorCode> for ProtocolErrorCode {
    type Error = ProtocolError;

    fn try_from(value: wire::ErrorCode) -> Result<Self, Self::Error> {
        match value {
            wire::ErrorCode::Unspecified => Err(ProtocolError::UnspecifiedEnum {
                field: "error.code",
            }),
            wire::ErrorCode::InvalidArgument => Ok(Self::InvalidArgument),
            wire::ErrorCode::UnsupportedVersion => Ok(Self::UnsupportedVersion),
            wire::ErrorCode::ContractMismatch => Ok(Self::ContractMismatch),
            wire::ErrorCode::NotFound => Ok(Self::NotFound),
            wire::ErrorCode::AlreadyExists => Ok(Self::AlreadyExists),
            wire::ErrorCode::Unauthenticated => Ok(Self::Unauthenticated),
            wire::ErrorCode::PermissionDenied => Ok(Self::PermissionDenied),
            wire::ErrorCode::PolicyRequiresUserAction => Ok(Self::PolicyRequiresUserAction),
            wire::ErrorCode::ResourceExhausted => Ok(Self::ResourceExhausted),
            wire::ErrorCode::StaleEpoch => Ok(Self::StaleEpoch),
            wire::ErrorCode::SessionNotActive => Ok(Self::SessionNotActive),
            wire::ErrorCode::ParticipantOffline => Ok(Self::ParticipantOffline),
            wire::ErrorCode::ChannelNotBound => Ok(Self::ChannelNotBound),
            wire::ErrorCode::MessageTooLarge => Ok(Self::MessageTooLarge),
            wire::ErrorCode::Backpressure => Ok(Self::Backpressure),
            wire::ErrorCode::ClockUnavailable => Ok(Self::ClockUnavailable),
            wire::ErrorCode::ClockUncertain => Ok(Self::ClockUncertain),
            wire::ErrorCode::E2eeRequired => Ok(Self::E2eeRequired),
            wire::ErrorCode::E2eeNegotiationFailed => Ok(Self::E2eeNegotiationFailed),
            wire::ErrorCode::Timeout => Ok(Self::Timeout),
            wire::ErrorCode::Internal => Ok(Self::Internal),
        }
    }
}

pub fn protocol_error_code_from_i32(value: i32) -> Result<ProtocolErrorCode, ProtocolError> {
    let code = wire::ErrorCode::try_from(value).map_err(|_| ProtocolError::UnknownEnumValue {
        field: "error.code",
        value,
    })?;
    code.try_into()
}

pub fn message_type_from_i32(value: i32) -> Result<wire::MessageType, ProtocolError> {
    let message_type =
        wire::MessageType::try_from(value).map_err(|_| ProtocolError::UnknownEnumValue {
            field: "message_type",
            value,
        })?;
    if message_type == wire::MessageType::Unspecified {
        return Err(ProtocolError::UnspecifiedEnum {
            field: "message_type",
        });
    }
    Ok(message_type)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionControlHeader {
    pub operation_id: OperationId,
    pub session_id: SessionId,
    pub expected_epoch: u64,
    pub sender_device_id: DeviceId,
    pub message_nonce: [u8; MESSAGE_NONCE_BYTES],
}

impl From<&SessionControlHeader> for wire::SessionControlHeader {
    fn from(value: &SessionControlHeader) -> Self {
        Self {
            operation_id: Some((&value.operation_id).into()),
            session_id: Some((&value.session_id).into()),
            expected_epoch: value.expected_epoch,
            sender_device_id: Some((&value.sender_device_id).into()),
            message_nonce: value.message_nonce.to_vec(),
        }
    }
}

impl TryFrom<wire::SessionControlHeader> for SessionControlHeader {
    type Error = ProtocolError;

    fn try_from(value: wire::SessionControlHeader) -> Result<Self, Self::Error> {
        Ok(Self {
            operation_id: value
                .operation_id
                .ok_or(ProtocolError::MissingField("session.header.operation_id"))?
                .try_into()?,
            session_id: value
                .session_id
                .ok_or(ProtocolError::MissingField("session.header.session_id"))?
                .try_into()?,
            expected_epoch: value.expected_epoch,
            sender_device_id: value
                .sender_device_id
                .ok_or(ProtocolError::MissingField(
                    "session.header.sender_device_id",
                ))?
                .try_into()?,
            message_nonce: exact_array::<MESSAGE_NONCE_BYTES>(
                value.message_nonce,
                "session.header.message_nonce",
            )?,
        })
    }
}

pub fn exact_array<const N: usize>(
    value: Vec<u8>,
    field: &'static str,
) -> Result<[u8; N], ProtocolError> {
    let actual = value.len();
    value.try_into().map_err(|_| ProtocolError::InvalidLength {
        field,
        expected: N,
        actual,
    })
}

pub fn check_collection_len(field: &'static str, length: usize) -> Result<(), ProtocolError> {
    if length > MAX_COLLECTION_ITEMS {
        return Err(ProtocolError::CollectionTooLarge { field });
    }
    Ok(())
}

pub(crate) fn bounded_bytes(
    value: Vec<u8>,
    field: &'static str,
    max: usize,
    allow_empty: bool,
) -> Result<Vec<u8>, ProtocolError> {
    if !allow_empty && value.is_empty() {
        return Err(ProtocolError::EmptyField { field });
    }
    if value.len() > max {
        return Err(ProtocolError::FieldTooLarge {
            field,
            max,
            actual: value.len(),
        });
    }
    Ok(value)
}

pub(crate) fn bounded_string(
    value: String,
    field: &'static str,
    max: usize,
    allow_empty: bool,
) -> Result<String, ProtocolError> {
    if !allow_empty && value.is_empty() {
        return Err(ProtocolError::EmptyField { field });
    }
    if value.len() > max {
        return Err(ProtocolError::FieldTooLarge {
            field,
            max,
            actual: value.len(),
        });
    }
    if value.chars().any(char::is_control) {
        return Err(ProtocolError::Domain(ValidationError::InvalidCharacters {
            field,
        }));
    }
    Ok(value)
}

fn validate_feature_token(value: &str) -> Result<(), ProtocolError> {
    if value.is_empty() {
        return Err(ProtocolError::Domain(ValidationError::Empty {
            field: "hub_hello.features[]",
        }));
    }
    if value.len() > MAX_FEATURE_TOKEN_BYTES {
        return Err(ProtocolError::Domain(ValidationError::TooLong {
            field: "hub_hello.features[]",
            max: MAX_FEATURE_TOKEN_BYTES,
        }));
    }
    if !value.bytes().all(|byte| {
        byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'-' | b'_')
    }) {
        return Err(ProtocolError::Domain(ValidationError::InvalidCharacters {
            field: "hub_hello.features[]",
        }));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contract_round_trip() {
        let domain = AbilityContractRef {
            key: AbilityKey {
                namespace: Namespace::new("com.example").unwrap(),
                name: AbilityName::new("echo").unwrap(),
                major: 1,
            },
            protocol_hash: [42; 32],
        };
        let wire = wire::AbilityContractRef::from(&domain);
        assert_eq!(AbilityContractRef::try_from(wire).unwrap(), domain);
    }

    #[test]
    fn primitive_wire_types_round_trip() {
        let session_id = SessionId::new();
        assert_eq!(
            SessionId::try_from(wire::Id128::from(session_id)).unwrap(),
            session_id
        );

        let device_id = DeviceId([7; 32]);
        assert_eq!(
            DeviceId::try_from(wire::DeviceId::from(device_id)).unwrap(),
            device_id
        );

        assert!(matches!(
            DeviceId::try_from(wire::DeviceId {
                sha256_public_key: vec![0; 31],
            }),
            Err(ProtocolError::InvalidLength {
                field: "device_id.sha256_public_key",
                expected: 32,
                actual: 31
            })
        ));
    }

    #[test]
    fn link_handshake_messages_validate_required_wire_fields() {
        let hello = HubHello {
            supported_protocol_versions: vec![1],
            device_id: DeviceId([1; 32]),
            device_proof: [2; DEVICE_PROOF_BYTES],
            nonce: [3; MESSAGE_NONCE_BYTES],
            features: vec!["quic".into(), "registry-sync".into()],
        };
        let wire = wire::HubHello::from(&hello);
        assert_eq!(HubHello::try_from(wire.clone()).unwrap(), hello);

        let mut missing_device = wire.clone();
        missing_device.device_id = None;
        assert!(matches!(
            HubHello::try_from(missing_device),
            Err(ProtocolError::MissingField("hub_hello.device_id"))
        ));

        let mut invalid_proof = wire.clone();
        invalid_proof.device_proof.pop();
        assert!(matches!(
            HubHello::try_from(invalid_proof),
            Err(ProtocolError::InvalidLength {
                field: "hub_hello.device_proof",
                expected: DEVICE_PROOF_BYTES,
                actual
            }) if actual == DEVICE_PROOF_BYTES - 1
        ));

        let mut invalid_version = wire;
        invalid_version.supported_protocol_versions = vec![0];
        assert!(matches!(
            HubHello::try_from(invalid_version),
            Err(ProtocolError::Domain(ValidationError::InvalidValue {
                field: "hub_hello.supported_protocol_versions",
                ..
            }))
        ));

        let ready = LinkReady {
            selected_protocol_version: 1,
            transcript_hash: [4; TRANSCRIPT_HASH_BYTES],
            link_generation: 9,
        };
        assert_eq!(
            LinkReady::try_from(wire::LinkReady::from(&ready)).unwrap(),
            ready
        );
    }

    #[test]
    fn registry_control_messages_round_trip() {
        let begin = RegistrySnapshotBegin { revision: 7 };
        assert_eq!(
            RegistrySnapshotBegin::try_from(wire::RegistrySnapshotBegin::from(begin)).unwrap(),
            begin
        );
        let end = RegistrySnapshotEnd { revision: 8 };
        assert_eq!(
            RegistrySnapshotEnd::try_from(wire::RegistrySnapshotEnd::from(end)).unwrap(),
            end
        );
        let ack = RegistryAck { revision: 9 };
        assert_eq!(
            RegistryAck::try_from(wire::RegistryAck::from(ack)).unwrap(),
            ack
        );
        let resync = RegistryResyncRequest {
            applied_revision: 10,
        };
        assert_eq!(
            RegistryResyncRequest::try_from(wire::RegistryResyncRequest::from(resync)).unwrap(),
            resync
        );

        let remove = OfferRemove {
            revision: 11,
            ability_instance_id: AbilityInstanceId::new(),
        };
        assert_eq!(
            OfferRemove::try_from(wire::OfferRemove::from(&remove)).unwrap(),
            remove
        );

        assert!(matches!(
            OfferRemove::try_from(wire::OfferRemove {
                revision: 11,
                ability_instance_id: None,
            }),
            Err(ProtocolError::MissingField(
                "offer_remove.ability_instance_id"
            ))
        ));
    }

    #[test]
    fn protocol_error_code_matches_wire_numbers() {
        let domain = ProtocolErrorCode::PolicyRequiresUserAction;
        let wire = wire::ErrorCode::from(domain);
        assert_eq!(
            wire as i32,
            wire::ErrorCode::PolicyRequiresUserAction as i32
        );
        assert_eq!(ProtocolErrorCode::try_from(wire).unwrap(), domain);
        assert!(matches!(
            ProtocolErrorCode::try_from(wire::ErrorCode::Unspecified),
            Err(ProtocolError::UnspecifiedEnum {
                field: "error.code"
            })
        ));
        assert!(matches!(
            protocol_error_code_from_i32(999),
            Err(ProtocolError::UnknownEnumValue {
                field: "error.code",
                value: 999
            })
        ));
    }

    #[test]
    fn session_control_header_round_trip_and_rejects_bad_nonce() {
        let domain = SessionControlHeader {
            operation_id: OperationId::new(),
            session_id: SessionId::new(),
            expected_epoch: 3,
            sender_device_id: DeviceId([9; 32]),
            message_nonce: [4; MESSAGE_NONCE_BYTES],
        };
        let wire = wire::SessionControlHeader::from(&domain);
        assert_eq!(
            SessionControlHeader::try_from(wire.clone()).unwrap(),
            domain
        );

        let mut missing = wire.clone();
        missing.operation_id = None;
        assert!(matches!(
            SessionControlHeader::try_from(missing),
            Err(ProtocolError::MissingField("session.header.operation_id"))
        ));

        let mut invalid_nonce = wire;
        invalid_nonce.message_nonce.pop();
        assert!(matches!(
            SessionControlHeader::try_from(invalid_nonce),
            Err(ProtocolError::InvalidLength {
                field: "session.header.message_nonce",
                expected: MESSAGE_NONCE_BYTES,
                actual
            }) if actual == MESSAGE_NONCE_BYTES - 1
        ));
    }

    #[test]
    fn unknown_fields_survive_frame_round_trip() {
        // Valid HubHello plus unknown field 99 containing one byte.
        let payload = Bytes::from_static(&[0x08, 0x01, 0x9a, 0x06, 0x01, 0xff]);
        let frame = ControlFrame {
            message_type: wire::MessageType::HubHello as u64,
            request_id: 7,
            payload,
        };
        let encoded = frame.encode();
        let decoded = ControlFrame::decode(&encoded).unwrap();
        let _: wire::HubHello = decoded.decode_message().unwrap();
        assert_eq!(decoded.encode(), encoded);
    }

    #[test]
    fn rejects_oversized_frames_and_arrays() {
        let mut encoded = Vec::new();
        put_varint(&mut encoded, (MAX_CONTROL_FRAME_BYTES + 1) as u64);
        assert!(matches!(
            ControlFrame::decode(&encoded),
            Err(ProtocolError::FrameTooLarge)
        ));
        assert!(exact_array::<32>(vec![0; 31], "hash").is_err());
        assert!(check_collection_len("offers", MAX_COLLECTION_ITEMS + 1).is_err());
    }
}
