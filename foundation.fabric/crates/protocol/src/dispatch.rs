use super::{
    AppAttestationRequest, AppAttestationResponse, BarrierAbort, BarrierCommit, BarrierPrepare,
    BarrierReady, BarrierReject, ClockProbe, ClockReply, ClockStatus, ControlFrame,
    ControlStreamHello, E2eeKeyAccept, E2eeKeyOffer, GroupKeyRotate, HubHello, LinkReady,
    OfferRemove, OfferUpsert, PairingChallenge, PairingComplete, PairingConfirm, PairingStart,
    PolicyDenied, ProtocolError, RegistryAck, RegistryResyncRequest, RegistrySnapshotBegin,
    RegistrySnapshotEnd, SessionAbort, SessionAccept, SessionClose, SessionCommit,
    SessionCounterOffer, SessionNotReady, SessionPrepare, SessionPropose, SessionReady,
    SessionReconfigureAccept, SessionReconfigureCommit, SessionReconfigurePropose, SessionReject,
    SessionResume, SessionSuspend, StreamOpen, wire,
};
use crate::{EpochPolicy, MESSAGE_NONCE_BYTES, SessionControlHeader};
use fabric_core::OperationId;
use prost::Message;
use uuid::Uuid;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ControlMessage {
    ControlStreamHello(ControlStreamHello),
    HubHello(HubHello),
    LinkReady(LinkReady),
    RegistrySnapshotBegin(RegistrySnapshotBegin),
    OfferUpsert(OfferUpsert),
    OfferRemove(OfferRemove),
    RegistrySnapshotEnd(RegistrySnapshotEnd),
    RegistryAck(RegistryAck),
    RegistryResyncRequest(RegistryResyncRequest),
    SessionPropose(SessionPropose),
    SessionCounterOffer(SessionCounterOffer),
    SessionAccept(SessionAccept),
    SessionReject(SessionReject),
    SessionPrepare(SessionPrepare),
    SessionReady(SessionReady),
    SessionNotReady(SessionNotReady),
    SessionCommit(SessionCommit),
    SessionAbort(SessionAbort),
    SessionSuspend(SessionSuspend),
    SessionResume(SessionResume),
    SessionReconfigurePropose(SessionReconfigurePropose),
    SessionReconfigureAccept(SessionReconfigureAccept),
    SessionReconfigureCommit(SessionReconfigureCommit),
    SessionClose(SessionClose),
    ClockProbe(ClockProbe),
    ClockReply(ClockReply),
    ClockStatus(ClockStatus),
    BarrierPrepare(BarrierPrepare),
    BarrierReady(BarrierReady),
    BarrierReject(BarrierReject),
    BarrierCommit(BarrierCommit),
    BarrierAbort(BarrierAbort),
    PairingStart(PairingStart),
    PairingChallenge(PairingChallenge),
    PairingConfirm(PairingConfirm),
    PairingComplete(PairingComplete),
    AppAttestationRequest(AppAttestationRequest),
    AppAttestationResponse(AppAttestationResponse),
    E2eeKeyOffer(E2eeKeyOffer),
    E2eeKeyAccept(E2eeKeyAccept),
    GroupKeyRotate(GroupKeyRotate),
    PolicyDenied(PolicyDenied),
    StreamOpen(StreamOpen),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionIngress {
    pub header: SessionControlHeader,
    pub epoch_policy: EpochPolicy,
    pub semantic_payload: Vec<u8>,
}

macro_rules! decode_variant {
    ($frame:expr, $wire:ty, $domain:ty, $variant:path) => {{
        let wire = $frame.decode_message::<$wire>()?;
        Ok($variant(<$domain>::try_from(wire)?))
    }};
}

impl ControlMessage {
    #[allow(clippy::too_many_lines)]
    pub fn decode(frame: &ControlFrame) -> Result<Self, ProtocolError> {
        match frame.validated_message_type()? {
            wire::MessageType::ControlStreamHello => decode_variant!(
                frame,
                wire::ControlStreamHello,
                ControlStreamHello,
                Self::ControlStreamHello
            ),
            wire::MessageType::HubHello => {
                decode_variant!(frame, wire::HubHello, HubHello, Self::HubHello)
            }
            wire::MessageType::LinkReady => {
                decode_variant!(frame, wire::LinkReady, LinkReady, Self::LinkReady)
            }
            wire::MessageType::RegistrySnapshotBegin => decode_variant!(
                frame,
                wire::RegistrySnapshotBegin,
                RegistrySnapshotBegin,
                Self::RegistrySnapshotBegin
            ),
            wire::MessageType::OfferUpsert => {
                decode_variant!(frame, wire::OfferUpsert, OfferUpsert, Self::OfferUpsert)
            }
            wire::MessageType::OfferRemove => {
                decode_variant!(frame, wire::OfferRemove, OfferRemove, Self::OfferRemove)
            }
            wire::MessageType::RegistrySnapshotEnd => decode_variant!(
                frame,
                wire::RegistrySnapshotEnd,
                RegistrySnapshotEnd,
                Self::RegistrySnapshotEnd
            ),
            wire::MessageType::RegistryAck => {
                decode_variant!(frame, wire::RegistryAck, RegistryAck, Self::RegistryAck)
            }
            wire::MessageType::RegistryResyncRequest => decode_variant!(
                frame,
                wire::RegistryResyncRequest,
                RegistryResyncRequest,
                Self::RegistryResyncRequest
            ),
            wire::MessageType::SessionPropose => decode_variant!(
                frame,
                wire::SessionPropose,
                SessionPropose,
                Self::SessionPropose
            ),
            wire::MessageType::SessionCounterOffer => decode_variant!(
                frame,
                wire::SessionCounterOffer,
                SessionCounterOffer,
                Self::SessionCounterOffer
            ),
            wire::MessageType::SessionAccept => decode_variant!(
                frame,
                wire::SessionAccept,
                SessionAccept,
                Self::SessionAccept
            ),
            wire::MessageType::SessionReject => decode_variant!(
                frame,
                wire::SessionReject,
                SessionReject,
                Self::SessionReject
            ),
            wire::MessageType::SessionPrepare => decode_variant!(
                frame,
                wire::SessionPrepare,
                SessionPrepare,
                Self::SessionPrepare
            ),
            wire::MessageType::SessionReady => {
                decode_variant!(frame, wire::SessionReady, SessionReady, Self::SessionReady)
            }
            wire::MessageType::SessionNotReady => decode_variant!(
                frame,
                wire::SessionNotReady,
                SessionNotReady,
                Self::SessionNotReady
            ),
            wire::MessageType::SessionCommit => decode_variant!(
                frame,
                wire::SessionCommit,
                SessionCommit,
                Self::SessionCommit
            ),
            wire::MessageType::SessionAbort => {
                decode_variant!(frame, wire::SessionAbort, SessionAbort, Self::SessionAbort)
            }
            wire::MessageType::SessionSuspend => decode_variant!(
                frame,
                wire::SessionSuspend,
                SessionSuspend,
                Self::SessionSuspend
            ),
            wire::MessageType::SessionResume => decode_variant!(
                frame,
                wire::SessionResume,
                SessionResume,
                Self::SessionResume
            ),
            wire::MessageType::SessionReconfigurePropose => decode_variant!(
                frame,
                wire::SessionReconfigurePropose,
                SessionReconfigurePropose,
                Self::SessionReconfigurePropose
            ),
            wire::MessageType::SessionReconfigureAccept => decode_variant!(
                frame,
                wire::SessionReconfigureAccept,
                SessionReconfigureAccept,
                Self::SessionReconfigureAccept
            ),
            wire::MessageType::SessionReconfigureCommit => decode_variant!(
                frame,
                wire::SessionReconfigureCommit,
                SessionReconfigureCommit,
                Self::SessionReconfigureCommit
            ),
            wire::MessageType::SessionClose => {
                decode_variant!(frame, wire::SessionClose, SessionClose, Self::SessionClose)
            }
            wire::MessageType::ClockProbe => {
                decode_variant!(frame, wire::ClockProbe, ClockProbe, Self::ClockProbe)
            }
            wire::MessageType::ClockReply => {
                decode_variant!(frame, wire::ClockReply, ClockReply, Self::ClockReply)
            }
            wire::MessageType::ClockStatus => {
                decode_variant!(frame, wire::ClockStatus, ClockStatus, Self::ClockStatus)
            }
            wire::MessageType::BarrierPrepare => decode_variant!(
                frame,
                wire::BarrierPrepare,
                BarrierPrepare,
                Self::BarrierPrepare
            ),
            wire::MessageType::BarrierReady => {
                decode_variant!(frame, wire::BarrierReady, BarrierReady, Self::BarrierReady)
            }
            wire::MessageType::BarrierReject => decode_variant!(
                frame,
                wire::BarrierReject,
                BarrierReject,
                Self::BarrierReject
            ),
            wire::MessageType::BarrierCommit => decode_variant!(
                frame,
                wire::BarrierCommit,
                BarrierCommit,
                Self::BarrierCommit
            ),
            wire::MessageType::BarrierAbort => {
                decode_variant!(frame, wire::BarrierAbort, BarrierAbort, Self::BarrierAbort)
            }
            wire::MessageType::PairingStart => {
                decode_variant!(frame, wire::PairingStart, PairingStart, Self::PairingStart)
            }
            wire::MessageType::PairingChallenge => decode_variant!(
                frame,
                wire::PairingChallenge,
                PairingChallenge,
                Self::PairingChallenge
            ),
            wire::MessageType::PairingConfirm => decode_variant!(
                frame,
                wire::PairingConfirm,
                PairingConfirm,
                Self::PairingConfirm
            ),
            wire::MessageType::PairingComplete => decode_variant!(
                frame,
                wire::PairingComplete,
                PairingComplete,
                Self::PairingComplete
            ),
            wire::MessageType::AppAttestationRequest => decode_variant!(
                frame,
                wire::AppAttestationRequest,
                AppAttestationRequest,
                Self::AppAttestationRequest
            ),
            wire::MessageType::AppAttestationResponse => decode_variant!(
                frame,
                wire::AppAttestationResponse,
                AppAttestationResponse,
                Self::AppAttestationResponse
            ),
            wire::MessageType::E2eeKeyOffer => {
                decode_variant!(frame, wire::E2eeKeyOffer, E2eeKeyOffer, Self::E2eeKeyOffer)
            }
            wire::MessageType::E2eeKeyAccept => decode_variant!(
                frame,
                wire::E2eeKeyAccept,
                E2eeKeyAccept,
                Self::E2eeKeyAccept
            ),
            wire::MessageType::GroupKeyRotate => decode_variant!(
                frame,
                wire::GroupKeyRotate,
                GroupKeyRotate,
                Self::GroupKeyRotate
            ),
            wire::MessageType::PolicyDenied => {
                decode_variant!(frame, wire::PolicyDenied, PolicyDenied, Self::PolicyDenied)
            }
            wire::MessageType::Unspecified => Err(ProtocolError::UnspecifiedEnum {
                field: "message_type",
            }),
        }
    }

    #[must_use]
    pub fn session_ingress(&self) -> Option<SessionIngress> {
        macro_rules! ingress {
            ($value:expr, $wire:ident, $policy:expr) => {{
                let mut sanitized = $value.clone();
                let header = sanitized.header.clone();
                sanitize_retry_fields(&mut sanitized.header);
                Some(SessionIngress {
                    header,
                    epoch_policy: $policy,
                    semantic_payload: wire::$wire::from(&sanitized).encode_to_vec(),
                })
            }};
        }

        match self {
            Self::SessionPropose(value) => {
                ingress!(value, SessionPropose, EpochPolicy::New)
            }
            Self::SessionCounterOffer(value) => {
                ingress!(value, SessionCounterOffer, EpochPolicy::Exact)
            }
            Self::SessionAccept(value) => ingress!(value, SessionAccept, EpochPolicy::Exact),
            Self::SessionReject(value) => ingress!(value, SessionReject, EpochPolicy::Exact),
            Self::SessionPrepare(value) => ingress!(value, SessionPrepare, EpochPolicy::Next),
            Self::SessionReady(value) => ingress!(value, SessionReady, EpochPolicy::Next),
            Self::SessionNotReady(value) => {
                ingress!(value, SessionNotReady, EpochPolicy::Next)
            }
            Self::SessionCommit(value) => ingress!(value, SessionCommit, EpochPolicy::Next),
            Self::SessionAbort(value) => ingress!(value, SessionAbort, EpochPolicy::Exact),
            Self::SessionSuspend(value) => ingress!(value, SessionSuspend, EpochPolicy::Exact),
            Self::SessionResume(value) => ingress!(value, SessionResume, EpochPolicy::Exact),
            Self::SessionReconfigurePropose(value) => {
                ingress!(value, SessionReconfigurePropose, EpochPolicy::Next)
            }
            Self::SessionReconfigureAccept(value) => {
                ingress!(value, SessionReconfigureAccept, EpochPolicy::Next)
            }
            Self::SessionReconfigureCommit(value) => {
                ingress!(value, SessionReconfigureCommit, EpochPolicy::Next)
            }
            Self::SessionClose(value) => ingress!(value, SessionClose, EpochPolicy::Exact),
            _ => None,
        }
    }
}

fn sanitize_retry_fields(header: &mut SessionControlHeader) {
    header.operation_id = OperationId(Uuid::nil());
    header.message_nonce = [0; MESSAGE_NONCE_BYTES];
}

#[cfg(test)]
mod tests {
    use fabric_core::{DeviceId, OperationId, SessionId};

    use super::*;
    use crate::{DEVICE_PROOF_BYTES, MESSAGE_NONCE_BYTES};

    #[test]
    fn dispatches_validated_payload_and_rejects_unknown_types() {
        let hello = crate::HubHello {
            supported_protocol_versions: vec![1],
            device_id: DeviceId([1; 32]),
            device_proof: [2; DEVICE_PROOF_BYTES],
            nonce: [3; MESSAGE_NONCE_BYTES],
            features: vec!["quic".into()],
        };
        let frame = ControlFrame::new(
            wire::MessageType::HubHello,
            4,
            &wire::HubHello::from(&hello),
        )
        .unwrap();
        assert_eq!(
            ControlMessage::decode(&frame).unwrap(),
            ControlMessage::HubHello(hello)
        );

        let unknown = ControlFrame {
            message_type: u64::MAX,
            request_id: 1,
            payload: bytes::Bytes::new(),
        };
        assert!(matches!(
            ControlMessage::decode(&unknown),
            Err(ProtocolError::UnknownMessageType(u64::MAX))
        ));
    }

    #[test]
    fn session_semantics_ignore_retry_fields() {
        let session_id = SessionId::new();
        let message = |nonce, operation_id| {
            ControlMessage::SessionAccept(crate::SessionAccept {
                header: SessionControlHeader {
                    operation_id,
                    session_id,
                    expected_epoch: 3,
                    sender_device_id: DeviceId([4; 32]),
                    message_nonce: [nonce; MESSAGE_NONCE_BYTES],
                },
                acceptance: vec![1, 2, 3],
            })
        };
        let first = message(1, OperationId::new()).session_ingress().unwrap();
        let retry = message(2, OperationId::new()).session_ingress().unwrap();
        assert_eq!(first.semantic_payload, retry.semantic_payload);
        assert_eq!(first.epoch_policy, EpochPolicy::Exact);
    }
}
