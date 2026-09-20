use fabric_core::{
    AbilityContractRef, AbilityInstanceId, BarrierId, ChannelId, ClockDomainId, DeviceId,
    ParticipantId, ProtocolErrorCode, SessionId,
};
use uuid::Uuid;

use super::{
    DEVICE_PROOF_BYTES, MAX_E2EE_HEADER_BYTES, MAX_KEY_MATERIAL_BYTES, MAX_OPAQUE_BYTES,
    MAX_REASON_BYTES, MAX_RESUME_TOKEN_BYTES, MAX_SAFE_MESSAGE_BYTES, MESSAGE_NONCE_BYTES,
    PLAN_HASH_BYTES, ProtocolError, SessionControlHeader, TRANSCRIPT_HASH_BYTES, bounded_bytes,
    bounded_string, check_collection_len, exact_array, wire,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ControlStreamHello {
    pub link_generation: u64,
}

impl From<ControlStreamHello> for wire::ControlStreamHello {
    fn from(value: ControlStreamHello) -> Self {
        Self {
            link_generation: value.link_generation,
        }
    }
}

impl TryFrom<wire::ControlStreamHello> for ControlStreamHello {
    type Error = ProtocolError;

    fn try_from(value: wire::ControlStreamHello) -> Result<Self, Self::Error> {
        if value.link_generation == 0 {
            return Err(ProtocolError::EmptyField {
                field: "control_stream_hello.link_generation",
            });
        }
        Ok(Self {
            link_generation: value.link_generation,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OfferUpsert {
    pub revision: u64,
    pub encoded_offer: Vec<u8>,
}

impl From<&OfferUpsert> for wire::OfferUpsert {
    fn from(value: &OfferUpsert) -> Self {
        Self {
            revision: value.revision,
            encoded_offer: value.encoded_offer.clone(),
        }
    }
}

impl TryFrom<wire::OfferUpsert> for OfferUpsert {
    type Error = ProtocolError;

    fn try_from(value: wire::OfferUpsert) -> Result<Self, Self::Error> {
        Ok(Self {
            revision: value.revision,
            encoded_offer: bounded_bytes(
                value.encoded_offer,
                "offer_upsert.encoded_offer",
                MAX_OPAQUE_BYTES,
                false,
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProtocolFailure {
    pub code: ProtocolErrorCode,
    pub safe_message: String,
    pub retry_after_ms: Option<u64>,
    pub related_ids: Vec<Uuid>,
}

impl From<&ProtocolFailure> for wire::ProtocolError {
    fn from(value: &ProtocolFailure) -> Self {
        Self {
            code: wire::ErrorCode::from(value.code) as i32,
            safe_message: value.safe_message.clone(),
            retry_after_ms: value.retry_after_ms,
            related_ids: value
                .related_ids
                .iter()
                .map(|id| wire::Id128 {
                    value: id.as_bytes().to_vec(),
                })
                .collect(),
        }
    }
}

impl TryFrom<wire::ProtocolError> for ProtocolFailure {
    type Error = ProtocolError;

    fn try_from(value: wire::ProtocolError) -> Result<Self, Self::Error> {
        check_collection_len("error.related_ids", value.related_ids.len())?;
        let related_ids = value
            .related_ids
            .into_iter()
            .map(|id| exact_array::<16>(id.value, "error.related_ids[]").map(Uuid::from_bytes))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Self {
            code: super::protocol_error_code_from_i32(value.code)?,
            safe_message: bounded_string(
                value.safe_message,
                "error.safe_message",
                MAX_SAFE_MESSAGE_BYTES,
                false,
            )?,
            retry_after_ms: value.retry_after_ms,
            related_ids,
        })
    }
}

fn header_to_parts(
    header: &SessionControlHeader,
) -> (
    Option<wire::Id128>,
    Option<wire::Id128>,
    u64,
    Option<wire::DeviceId>,
    Vec<u8>,
) {
    (
        Some((&header.operation_id).into()),
        Some((&header.session_id).into()),
        header.expected_epoch,
        Some((&header.sender_device_id).into()),
        header.message_nonce.to_vec(),
    )
}

fn header_from_parts(
    operation_id: Option<wire::Id128>,
    session_id: Option<wire::Id128>,
    expected_epoch: u64,
    sender_device_id: Option<wire::DeviceId>,
    message_nonce: Vec<u8>,
    prefix: &'static str,
) -> Result<SessionControlHeader, ProtocolError> {
    Ok(SessionControlHeader {
        operation_id: operation_id
            .ok_or(ProtocolError::MissingField(match prefix {
                "session_propose" => "session_propose.operation_id",
                "session_accept" => "session_accept.operation_id",
                "session_reject" => "session_reject.operation_id",
                "session_prepare" => "session_prepare.operation_id",
                "session_ready" => "session_ready.operation_id",
                "session_commit" => "session_commit.operation_id",
                "session_abort" => "session_abort.operation_id",
                _ => "session.operation_id",
            }))?
            .try_into()?,
        session_id: session_id
            .ok_or(ProtocolError::MissingField(match prefix {
                "session_propose" => "session_propose.session_id",
                "session_accept" => "session_accept.session_id",
                "session_reject" => "session_reject.session_id",
                "session_prepare" => "session_prepare.session_id",
                "session_ready" => "session_ready.session_id",
                "session_commit" => "session_commit.session_id",
                "session_abort" => "session_abort.session_id",
                _ => "session.session_id",
            }))?
            .try_into()?,
        expected_epoch,
        sender_device_id: sender_device_id
            .ok_or(ProtocolError::MissingField(match prefix {
                "session_propose" => "session_propose.sender_device_id",
                "session_accept" => "session_accept.sender_device_id",
                "session_reject" => "session_reject.sender_device_id",
                "session_prepare" => "session_prepare.sender_device_id",
                "session_ready" => "session_ready.sender_device_id",
                "session_commit" => "session_commit.sender_device_id",
                "session_abort" => "session_abort.sender_device_id",
                _ => "session.sender_device_id",
            }))?
            .try_into()?,
        message_nonce: exact_array::<MESSAGE_NONCE_BYTES>(
            message_nonce,
            match prefix {
                "session_propose" => "session_propose.message_nonce",
                "session_accept" => "session_accept.message_nonce",
                "session_reject" => "session_reject.message_nonce",
                "session_prepare" => "session_prepare.message_nonce",
                "session_ready" => "session_ready.message_nonce",
                "session_commit" => "session_commit.message_nonce",
                "session_abort" => "session_abort.message_nonce",
                _ => "session.message_nonce",
            },
        )?,
    })
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionPropose {
    pub header: SessionControlHeader,
    pub contract: AbilityContractRef,
    pub encoded_draft_plan: Vec<u8>,
    pub opaque_ability_config: Vec<u8>,
}

impl From<&SessionPropose> for wire::SessionPropose {
    fn from(value: &SessionPropose) -> Self {
        let (operation_id, session_id, expected_epoch, sender_device_id, message_nonce) =
            header_to_parts(&value.header);
        Self {
            operation_id,
            session_id,
            expected_epoch,
            contract: Some((&value.contract).into()),
            encoded_draft_plan: value.encoded_draft_plan.clone(),
            opaque_ability_config: value.opaque_ability_config.clone(),
            sender_device_id,
            message_nonce,
        }
    }
}

impl TryFrom<wire::SessionPropose> for SessionPropose {
    type Error = ProtocolError;

    fn try_from(value: wire::SessionPropose) -> Result<Self, Self::Error> {
        Ok(Self {
            header: header_from_parts(
                value.operation_id,
                value.session_id,
                value.expected_epoch,
                value.sender_device_id,
                value.message_nonce,
                "session_propose",
            )?,
            contract: value
                .contract
                .ok_or(ProtocolError::MissingField("session_propose.contract"))?
                .try_into()?,
            encoded_draft_plan: bounded_bytes(
                value.encoded_draft_plan,
                "session_propose.encoded_draft_plan",
                MAX_OPAQUE_BYTES,
                false,
            )?,
            opaque_ability_config: bounded_bytes(
                value.opaque_ability_config,
                "session_propose.opaque_ability_config",
                MAX_OPAQUE_BYTES,
                true,
            )?,
        })
    }
}

macro_rules! direct_bytes_message {
    ($name:ident, $wire:ident, $field:ident, $prefix:literal, $max:expr, $allow_empty:expr) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub $field: Vec<u8>,
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                let (operation_id, session_id, expected_epoch, sender_device_id, message_nonce) =
                    header_to_parts(&value.header);
                Self {
                    operation_id,
                    session_id,
                    expected_epoch,
                    $field: value.$field.clone(),
                    sender_device_id,
                    message_nonce,
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: header_from_parts(
                        value.operation_id,
                        value.session_id,
                        value.expected_epoch,
                        value.sender_device_id,
                        value.message_nonce,
                        $prefix,
                    )?,
                    $field: bounded_bytes(
                        value.$field,
                        concat!($prefix, ".", stringify!($field)),
                        $max,
                        $allow_empty,
                    )?,
                })
            }
        }
    };
}

direct_bytes_message!(
    SessionAccept,
    SessionAccept,
    acceptance,
    "session_accept",
    MAX_OPAQUE_BYTES,
    false
);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionReject {
    pub header: SessionControlHeader,
    pub error: ProtocolFailure,
}

impl From<&SessionReject> for wire::SessionReject {
    fn from(value: &SessionReject) -> Self {
        let (operation_id, session_id, expected_epoch, sender_device_id, message_nonce) =
            header_to_parts(&value.header);
        Self {
            operation_id,
            session_id,
            error: Some((&value.error).into()),
            expected_epoch,
            sender_device_id,
            message_nonce,
        }
    }
}

impl TryFrom<wire::SessionReject> for SessionReject {
    type Error = ProtocolError;

    fn try_from(value: wire::SessionReject) -> Result<Self, Self::Error> {
        Ok(Self {
            header: header_from_parts(
                value.operation_id,
                value.session_id,
                value.expected_epoch,
                value.sender_device_id,
                value.message_nonce,
                "session_reject",
            )?,
            error: value
                .error
                .ok_or(ProtocolError::MissingField("session_reject.error"))?
                .try_into()?,
        })
    }
}

macro_rules! direct_plan_hash_message {
    ($name:ident, $wire:ident, $epoch:ident, $prefix:literal $(, $plan:ident)?) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            $(pub $plan: Vec<u8>,)?
            pub plan_hash: [u8; PLAN_HASH_BYTES],
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                let (operation_id, session_id, _, sender_device_id, message_nonce) =
                    header_to_parts(&value.header);
                Self {
                    operation_id,
                    session_id,
                    $epoch: value.header.expected_epoch,
                    $($plan: value.$plan.clone(),)?
                    plan_hash: value.plan_hash.to_vec(),
                    sender_device_id,
                    message_nonce,
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: header_from_parts(
                        value.operation_id,
                        value.session_id,
                        value.$epoch,
                        value.sender_device_id,
                        value.message_nonce,
                        $prefix,
                    )?,
                    $($plan: bounded_bytes(
                        value.$plan,
                        concat!($prefix, ".", stringify!($plan)),
                        MAX_OPAQUE_BYTES,
                        false,
                    )?,)?
                    plan_hash: exact_array::<PLAN_HASH_BYTES>(
                        value.plan_hash,
                        concat!($prefix, ".plan_hash"),
                    )?,
                })
            }
        }
    };
}

direct_plan_hash_message!(
    SessionPrepare,
    SessionPrepare,
    next_epoch,
    "session_prepare",
    encoded_final_plan
);
direct_plan_hash_message!(SessionReady, SessionReady, next_epoch, "session_ready");
direct_plan_hash_message!(SessionCommit, SessionCommit, epoch, "session_commit");

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionAbort {
    pub header: SessionControlHeader,
    pub error: ProtocolFailure,
}

impl From<&SessionAbort> for wire::SessionAbort {
    fn from(value: &SessionAbort) -> Self {
        let (operation_id, session_id, expected_epoch, sender_device_id, message_nonce) =
            header_to_parts(&value.header);
        Self {
            operation_id,
            session_id,
            error: Some((&value.error).into()),
            expected_epoch,
            sender_device_id,
            message_nonce,
        }
    }
}

impl TryFrom<wire::SessionAbort> for SessionAbort {
    type Error = ProtocolError;

    fn try_from(value: wire::SessionAbort) -> Result<Self, Self::Error> {
        Ok(Self {
            header: header_from_parts(
                value.operation_id,
                value.session_id,
                value.expected_epoch,
                value.sender_device_id,
                value.message_nonce,
                "session_abort",
            )?,
            error: value
                .error
                .ok_or(ProtocolError::MissingField("session_abort.error"))?
                .try_into()?,
        })
    }
}

macro_rules! header_bytes_message {
    ($name:ident, $wire:ident, $field:ident, $max:expr, $allow_empty:expr) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub $field: Vec<u8>,
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                Self {
                    header: Some((&value.header).into()),
                    $field: value.$field.clone(),
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: value
                        .header
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($field),
                            ".header"
                        )))?
                        .try_into()?,
                    $field: bounded_bytes(
                        value.$field,
                        concat!(stringify!($name), ".", stringify!($field)),
                        $max,
                        $allow_empty,
                    )?,
                })
            }
        }
    };
}

header_bytes_message!(
    SessionCounterOffer,
    SessionCounterOffer,
    encoded_plan,
    MAX_OPAQUE_BYTES,
    false
);
header_bytes_message!(
    SessionResume,
    SessionResume,
    resume_token,
    MAX_RESUME_TOKEN_BYTES,
    false
);
header_bytes_message!(
    SessionReconfigurePropose,
    SessionReconfigurePropose,
    encoded_plan,
    MAX_OPAQUE_BYTES,
    false
);
header_bytes_message!(
    E2eeKeyOffer,
    E2eeKeyOffer,
    key_offer,
    MAX_KEY_MATERIAL_BYTES,
    false
);
header_bytes_message!(
    E2eeKeyAccept,
    E2eeKeyAccept,
    key_accept,
    MAX_KEY_MATERIAL_BYTES,
    false
);
header_bytes_message!(
    GroupKeyRotate,
    GroupKeyRotate,
    key_material,
    MAX_KEY_MATERIAL_BYTES,
    false
);

macro_rules! header_error_message {
    ($name:ident, $wire:ident) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub error: ProtocolFailure,
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                Self {
                    header: Some((&value.header).into()),
                    error: Some((&value.error).into()),
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: value
                        .header
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".header"
                        )))?
                        .try_into()?,
                    error: value
                        .error
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".error"
                        )))?
                        .try_into()?,
                })
            }
        }
    };
}

header_error_message!(SessionNotReady, SessionNotReady);
header_error_message!(PolicyDenied, PolicyDenied);

macro_rules! header_reason_message {
    ($name:ident, $wire:ident) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub reason: String,
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                Self {
                    header: Some((&value.header).into()),
                    reason: value.reason.clone(),
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: value
                        .header
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".header"
                        )))?
                        .try_into()?,
                    reason: bounded_string(
                        value.reason,
                        concat!(stringify!($name), ".reason"),
                        MAX_REASON_BYTES,
                        false,
                    )?,
                })
            }
        }
    };
}

header_reason_message!(SessionSuspend, SessionSuspend);
header_reason_message!(SessionClose, SessionClose);

macro_rules! header_hash_message {
    ($name:ident, $wire:ident) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub plan_hash: [u8; PLAN_HASH_BYTES],
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                Self {
                    header: Some((&value.header).into()),
                    plan_hash: value.plan_hash.to_vec(),
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: value
                        .header
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".header"
                        )))?
                        .try_into()?,
                    plan_hash: exact_array::<PLAN_HASH_BYTES>(
                        value.plan_hash,
                        concat!(stringify!($name), ".plan_hash"),
                    )?,
                })
            }
        }
    };
}

header_hash_message!(SessionReconfigureAccept, SessionReconfigureAccept);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SessionReconfigureCommit {
    pub header: SessionControlHeader,
    pub plan_hash: [u8; PLAN_HASH_BYTES],
    pub epoch: u64,
}

impl From<&SessionReconfigureCommit> for wire::SessionReconfigureCommit {
    fn from(value: &SessionReconfigureCommit) -> Self {
        Self {
            header: Some((&value.header).into()),
            plan_hash: value.plan_hash.to_vec(),
            epoch: value.epoch,
        }
    }
}

impl TryFrom<wire::SessionReconfigureCommit> for SessionReconfigureCommit {
    type Error = ProtocolError;

    fn try_from(value: wire::SessionReconfigureCommit) -> Result<Self, Self::Error> {
        Ok(Self {
            header: value
                .header
                .ok_or(ProtocolError::MissingField(
                    "session_reconfigure_commit.header",
                ))?
                .try_into()?,
            plan_hash: exact_array::<PLAN_HASH_BYTES>(
                value.plan_hash,
                "session_reconfigure_commit.plan_hash",
            )?,
            epoch: value.epoch,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ClockProbe {
    pub clock_domain_id: ClockDomainId,
    pub sequence: u64,
    pub t1_local_ns: u64,
}

impl From<ClockProbe> for wire::ClockProbe {
    fn from(value: ClockProbe) -> Self {
        Self {
            clock_domain_id: Some(value.clock_domain_id.into()),
            sequence: value.sequence,
            t1_local_ns: value.t1_local_ns,
        }
    }
}

impl TryFrom<wire::ClockProbe> for ClockProbe {
    type Error = ProtocolError;

    fn try_from(value: wire::ClockProbe) -> Result<Self, Self::Error> {
        Ok(Self {
            clock_domain_id: value
                .clock_domain_id
                .ok_or(ProtocolError::MissingField("clock_probe.clock_domain_id"))?
                .try_into()?,
            sequence: value.sequence,
            t1_local_ns: value.t1_local_ns,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ClockReply {
    pub clock_domain_id: ClockDomainId,
    pub sequence: u64,
    pub t1_local_ns: u64,
    pub t2_group_ns: i64,
    pub t3_group_ns: i64,
}

impl From<ClockReply> for wire::ClockReply {
    fn from(value: ClockReply) -> Self {
        Self {
            clock_domain_id: Some(value.clock_domain_id.into()),
            sequence: value.sequence,
            t1_local_ns: value.t1_local_ns,
            t2_group_ns: value.t2_group_ns,
            t3_group_ns: value.t3_group_ns,
        }
    }
}

impl TryFrom<wire::ClockReply> for ClockReply {
    type Error = ProtocolError;

    fn try_from(value: wire::ClockReply) -> Result<Self, Self::Error> {
        Ok(Self {
            clock_domain_id: value
                .clock_domain_id
                .ok_or(ProtocolError::MissingField("clock_reply.clock_domain_id"))?
                .try_into()?,
            sequence: value.sequence,
            t1_local_ns: value.t1_local_ns,
            t2_group_ns: value.t2_group_ns,
            t3_group_ns: value.t3_group_ns,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ClockStatus {
    pub header: SessionControlHeader,
    pub uncertainty_ns: u64,
}

impl From<&ClockStatus> for wire::ClockStatus {
    fn from(value: &ClockStatus) -> Self {
        Self {
            header: Some((&value.header).into()),
            uncertainty_ns: value.uncertainty_ns,
        }
    }
}

impl TryFrom<wire::ClockStatus> for ClockStatus {
    type Error = ProtocolError;

    fn try_from(value: wire::ClockStatus) -> Result<Self, Self::Error> {
        Ok(Self {
            header: value
                .header
                .ok_or(ProtocolError::MissingField("clock_status.header"))?
                .try_into()?,
            uncertainty_ns: value.uncertainty_ns,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BarrierPrepare {
    pub barrier_id: BarrierId,
    pub session_id: SessionId,
    pub epoch: u64,
    pub activate_at_group_ns: i64,
    pub ready_deadline_group_ns: i64,
    pub participants: Vec<ParticipantId>,
    pub opaque_context: Vec<u8>,
}

impl From<&BarrierPrepare> for wire::BarrierPrepare {
    fn from(value: &BarrierPrepare) -> Self {
        Self {
            barrier_id: Some(value.barrier_id.into()),
            session_id: Some(value.session_id.into()),
            epoch: value.epoch,
            activate_at_group_ns: value.activate_at_group_ns,
            ready_deadline_group_ns: value.ready_deadline_group_ns,
            participants: value.participants.iter().map(Into::into).collect(),
            opaque_context: value.opaque_context.clone(),
        }
    }
}

impl TryFrom<wire::BarrierPrepare> for BarrierPrepare {
    type Error = ProtocolError;

    fn try_from(value: wire::BarrierPrepare) -> Result<Self, Self::Error> {
        check_collection_len("barrier_prepare.participants", value.participants.len())?;
        if value.participants.is_empty() {
            return Err(ProtocolError::EmptyField {
                field: "barrier_prepare.participants",
            });
        }
        Ok(Self {
            barrier_id: value
                .barrier_id
                .ok_or(ProtocolError::MissingField("barrier_prepare.barrier_id"))?
                .try_into()?,
            session_id: value
                .session_id
                .ok_or(ProtocolError::MissingField("barrier_prepare.session_id"))?
                .try_into()?,
            epoch: value.epoch,
            activate_at_group_ns: value.activate_at_group_ns,
            ready_deadline_group_ns: value.ready_deadline_group_ns,
            participants: value
                .participants
                .into_iter()
                .map(TryInto::try_into)
                .collect::<Result<Vec<_>, _>>()?,
            opaque_context: bounded_bytes(
                value.opaque_context,
                "barrier_prepare.opaque_context",
                MAX_OPAQUE_BYTES,
                true,
            )?,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BarrierReady {
    pub barrier_id: BarrierId,
    pub participant_id: ParticipantId,
    pub estimated_uncertainty_ns: u64,
}

impl From<BarrierReady> for wire::BarrierReady {
    fn from(value: BarrierReady) -> Self {
        Self {
            barrier_id: Some(value.barrier_id.into()),
            participant_id: Some(value.participant_id.into()),
            estimated_uncertainty_ns: value.estimated_uncertainty_ns,
        }
    }
}

impl TryFrom<wire::BarrierReady> for BarrierReady {
    type Error = ProtocolError;

    fn try_from(value: wire::BarrierReady) -> Result<Self, Self::Error> {
        Ok(Self {
            barrier_id: value
                .barrier_id
                .ok_or(ProtocolError::MissingField("barrier_ready.barrier_id"))?
                .try_into()?,
            participant_id: value
                .participant_id
                .ok_or(ProtocolError::MissingField("barrier_ready.participant_id"))?
                .try_into()?,
            estimated_uncertainty_ns: value.estimated_uncertainty_ns,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BarrierCommit {
    pub barrier_id: BarrierId,
    pub session_id: SessionId,
    pub epoch: u64,
}

impl From<BarrierCommit> for wire::BarrierCommit {
    fn from(value: BarrierCommit) -> Self {
        Self {
            barrier_id: Some(value.barrier_id.into()),
            session_id: Some(value.session_id.into()),
            epoch: value.epoch,
        }
    }
}

impl TryFrom<wire::BarrierCommit> for BarrierCommit {
    type Error = ProtocolError;

    fn try_from(value: wire::BarrierCommit) -> Result<Self, Self::Error> {
        Ok(Self {
            barrier_id: value
                .barrier_id
                .ok_or(ProtocolError::MissingField("barrier_commit.barrier_id"))?
                .try_into()?,
            session_id: value
                .session_id
                .ok_or(ProtocolError::MissingField("barrier_commit.session_id"))?
                .try_into()?,
            epoch: value.epoch,
        })
    }
}

macro_rules! barrier_error_message {
    ($name:ident, $wire:ident) => {
        #[derive(Clone, Debug, Eq, PartialEq)]
        pub struct $name {
            pub header: SessionControlHeader,
            pub barrier_id: BarrierId,
            pub error: ProtocolFailure,
        }

        impl From<&$name> for wire::$wire {
            fn from(value: &$name) -> Self {
                Self {
                    header: Some((&value.header).into()),
                    barrier_id: Some(value.barrier_id.into()),
                    error: Some((&value.error).into()),
                }
            }
        }

        impl TryFrom<wire::$wire> for $name {
            type Error = ProtocolError;

            fn try_from(value: wire::$wire) -> Result<Self, Self::Error> {
                Ok(Self {
                    header: value
                        .header
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".header"
                        )))?
                        .try_into()?,
                    barrier_id: value
                        .barrier_id
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".barrier_id"
                        )))?
                        .try_into()?,
                    error: value
                        .error
                        .ok_or(ProtocolError::MissingField(concat!(
                            stringify!($name),
                            ".error"
                        )))?
                        .try_into()?,
                })
            }
        }
    };
}

barrier_error_message!(BarrierReject, BarrierReject);
barrier_error_message!(BarrierAbort, BarrierAbort);

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingStart {
    pub nonce: [u8; MESSAGE_NONCE_BYTES],
    pub device_proof: [u8; DEVICE_PROOF_BYTES],
    pub device_label: String,
}

impl From<&PairingStart> for wire::PairingStart {
    fn from(value: &PairingStart) -> Self {
        Self {
            nonce: value.nonce.to_vec(),
            device_proof: value.device_proof.to_vec(),
            device_label: value.device_label.clone(),
        }
    }
}

impl TryFrom<wire::PairingStart> for PairingStart {
    type Error = ProtocolError;

    fn try_from(value: wire::PairingStart) -> Result<Self, Self::Error> {
        Ok(Self {
            nonce: exact_array::<MESSAGE_NONCE_BYTES>(value.nonce, "pairing_start.nonce")?,
            device_proof: exact_array::<DEVICE_PROOF_BYTES>(
                value.device_proof,
                "pairing_start.device_proof",
            )?,
            device_label: bounded_string(
                value.device_label,
                "pairing_start.device_label",
                64,
                false,
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingChallenge {
    pub nonce: [u8; MESSAGE_NONCE_BYTES],
    pub transcript_hash: [u8; TRANSCRIPT_HASH_BYTES],
}

impl From<&PairingChallenge> for wire::PairingChallenge {
    fn from(value: &PairingChallenge) -> Self {
        Self {
            nonce: value.nonce.to_vec(),
            transcript_hash: value.transcript_hash.to_vec(),
        }
    }
}

impl TryFrom<wire::PairingChallenge> for PairingChallenge {
    type Error = ProtocolError;

    fn try_from(value: wire::PairingChallenge) -> Result<Self, Self::Error> {
        Ok(Self {
            nonce: exact_array::<MESSAGE_NONCE_BYTES>(value.nonce, "pairing_challenge.nonce")?,
            transcript_hash: exact_array::<TRANSCRIPT_HASH_BYTES>(
                value.transcript_hash,
                "pairing_challenge.transcript_hash",
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingConfirm {
    pub transcript_hash: [u8; TRANSCRIPT_HASH_BYTES],
    pub user_confirmed: bool,
}

impl From<&PairingConfirm> for wire::PairingConfirm {
    fn from(value: &PairingConfirm) -> Self {
        Self {
            transcript_hash: value.transcript_hash.to_vec(),
            user_confirmed: value.user_confirmed,
        }
    }
}

impl TryFrom<wire::PairingConfirm> for PairingConfirm {
    type Error = ProtocolError;

    fn try_from(value: wire::PairingConfirm) -> Result<Self, Self::Error> {
        Ok(Self {
            transcript_hash: exact_array::<TRANSCRIPT_HASH_BYTES>(
                value.transcript_hash,
                "pairing_confirm.transcript_hash",
            )?,
            user_confirmed: value.user_confirmed,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingComplete {
    pub device_id: DeviceId,
    pub device_label: String,
}

impl From<PairingComplete> for wire::PairingComplete {
    fn from(value: PairingComplete) -> Self {
        Self {
            device_id: Some(value.device_id.into()),
            device_label: value.device_label,
        }
    }
}

impl TryFrom<wire::PairingComplete> for PairingComplete {
    type Error = ProtocolError;

    fn try_from(value: wire::PairingComplete) -> Result<Self, Self::Error> {
        Ok(Self {
            device_id: value
                .device_id
                .ok_or(ProtocolError::MissingField("pairing_complete.device_id"))?
                .try_into()?,
            device_label: bounded_string(
                value.device_label,
                "pairing_complete.device_label",
                64,
                false,
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppAttestationRequest {
    pub ability_instance_id: AbilityInstanceId,
    pub nonce: [u8; MESSAGE_NONCE_BYTES],
}

impl From<&AppAttestationRequest> for wire::AppAttestationRequest {
    fn from(value: &AppAttestationRequest) -> Self {
        Self {
            ability_instance_id: Some(value.ability_instance_id.into()),
            nonce: value.nonce.to_vec(),
        }
    }
}

impl TryFrom<wire::AppAttestationRequest> for AppAttestationRequest {
    type Error = ProtocolError;

    fn try_from(value: wire::AppAttestationRequest) -> Result<Self, Self::Error> {
        Ok(Self {
            ability_instance_id: value
                .ability_instance_id
                .ok_or(ProtocolError::MissingField(
                    "app_attestation_request.ability_instance_id",
                ))?
                .try_into()?,
            nonce: exact_array::<MESSAGE_NONCE_BYTES>(
                value.nonce,
                "app_attestation_request.nonce",
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AppAttestationResponse {
    pub signed_attestation: Vec<u8>,
}

impl From<&AppAttestationResponse> for wire::AppAttestationResponse {
    fn from(value: &AppAttestationResponse) -> Self {
        Self {
            signed_attestation: value.signed_attestation.clone(),
        }
    }
}

impl TryFrom<wire::AppAttestationResponse> for AppAttestationResponse {
    type Error = ProtocolError;

    fn try_from(value: wire::AppAttestationResponse) -> Result<Self, Self::Error> {
        Ok(Self {
            signed_attestation: bounded_bytes(
                value.signed_attestation,
                "app_attestation_response.signed_attestation",
                MAX_OPAQUE_BYTES,
                false,
            )?,
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StreamOpen {
    pub protocol_magic: u32,
    pub session_id: SessionId,
    pub epoch: u64,
    pub channel_id: ChannelId,
    pub sender_participant: ParticipantId,
    pub destination_binding_id: Uuid,
    pub stream_flags: u64,
    pub e2ee_header: Vec<u8>,
}

impl From<&StreamOpen> for wire::StreamOpen {
    fn from(value: &StreamOpen) -> Self {
        Self {
            protocol_magic: value.protocol_magic,
            session_id: Some(value.session_id.into()),
            epoch: value.epoch,
            channel_id: Some(value.channel_id.into()),
            sender_participant: Some(value.sender_participant.into()),
            destination_binding_id: Some(wire::Id128 {
                value: value.destination_binding_id.as_bytes().to_vec(),
            }),
            stream_flags: value.stream_flags,
            e2ee_header: value.e2ee_header.clone(),
        }
    }
}

impl TryFrom<wire::StreamOpen> for StreamOpen {
    type Error = ProtocolError;

    fn try_from(value: wire::StreamOpen) -> Result<Self, Self::Error> {
        Ok(Self {
            protocol_magic: value.protocol_magic,
            session_id: value
                .session_id
                .ok_or(ProtocolError::MissingField("stream_open.session_id"))?
                .try_into()?,
            epoch: value.epoch,
            channel_id: value
                .channel_id
                .ok_or(ProtocolError::MissingField("stream_open.channel_id"))?
                .try_into()?,
            sender_participant: value
                .sender_participant
                .ok_or(ProtocolError::MissingField(
                    "stream_open.sender_participant",
                ))?
                .try_into()?,
            destination_binding_id: Uuid::from_bytes(exact_array::<16>(
                value
                    .destination_binding_id
                    .ok_or(ProtocolError::MissingField(
                        "stream_open.destination_binding_id",
                    ))?
                    .value,
                "stream_open.destination_binding_id",
            )?),
            stream_flags: value.stream_flags,
            e2ee_header: bounded_bytes(
                value.e2ee_header,
                "stream_open.e2ee_header",
                MAX_E2EE_HEADER_BYTES,
                true,
            )?,
        })
    }
}

#[cfg(test)]
mod tests {
    use fabric_core::{AbilityKey, AbilityName, Namespace};

    use super::*;

    fn header() -> SessionControlHeader {
        SessionControlHeader {
            operation_id: fabric_core::OperationId::new(),
            session_id: SessionId::new(),
            expected_epoch: 4,
            sender_device_id: DeviceId([9; 32]),
            message_nonce: [7; MESSAGE_NONCE_BYTES],
        }
    }

    fn failure() -> ProtocolFailure {
        ProtocolFailure {
            code: ProtocolErrorCode::StaleEpoch,
            safe_message: "stale session epoch".into(),
            retry_after_ms: Some(50),
            related_ids: vec![Uuid::new_v4()],
        }
    }

    fn contract() -> AbilityContractRef {
        AbilityContractRef {
            key: AbilityKey {
                namespace: Namespace::new("com.example").unwrap(),
                name: AbilityName::new("echo").unwrap(),
                major: 1,
            },
            protocol_hash: [3; 32],
        }
    }

    #[test]
    fn protocol_failure_round_trips_and_bounds_remote_text() {
        let domain = failure();
        assert_eq!(
            ProtocolFailure::try_from(wire::ProtocolError::from(&domain)).unwrap(),
            domain
        );

        let mut wire = wire::ProtocolError::from(&domain);
        wire.safe_message = "x".repeat(MAX_SAFE_MESSAGE_BYTES + 1);
        assert!(matches!(
            ProtocolFailure::try_from(wire),
            Err(ProtocolError::FieldTooLarge {
                field: "error.safe_message",
                ..
            })
        ));
    }

    #[test]
    fn direct_session_messages_round_trip() {
        let propose = SessionPropose {
            header: header(),
            contract: contract(),
            encoded_draft_plan: vec![1, 2, 3],
            opaque_ability_config: Vec::new(),
        };
        assert_eq!(
            SessionPropose::try_from(wire::SessionPropose::from(&propose)).unwrap(),
            propose
        );

        let reject = SessionReject {
            header: header(),
            error: failure(),
        };
        assert_eq!(
            SessionReject::try_from(wire::SessionReject::from(&reject)).unwrap(),
            reject
        );

        let prepare = SessionPrepare {
            header: header(),
            encoded_final_plan: vec![4, 5],
            plan_hash: [6; PLAN_HASH_BYTES],
        };
        assert_eq!(
            SessionPrepare::try_from(wire::SessionPrepare::from(&prepare)).unwrap(),
            prepare
        );
    }

    #[test]
    fn header_session_messages_round_trip_and_bound_payloads() {
        let counter = SessionCounterOffer {
            header: header(),
            encoded_plan: vec![1, 2],
        };
        assert_eq!(
            SessionCounterOffer::try_from(wire::SessionCounterOffer::from(&counter)).unwrap(),
            counter
        );

        let close = SessionClose {
            header: header(),
            reason: "finished".into(),
        };
        assert_eq!(
            SessionClose::try_from(wire::SessionClose::from(&close)).unwrap(),
            close
        );

        let oversized = wire::E2eeKeyOffer {
            header: Some((&header()).into()),
            key_offer: vec![0; MAX_KEY_MATERIAL_BYTES + 1],
        };
        assert!(matches!(
            E2eeKeyOffer::try_from(oversized),
            Err(ProtocolError::FieldTooLarge { .. })
        ));
    }

    #[test]
    fn clock_barrier_and_security_messages_round_trip() {
        let clock = ClockReply {
            clock_domain_id: ClockDomainId::new(),
            sequence: 3,
            t1_local_ns: 10,
            t2_group_ns: -20,
            t3_group_ns: 30,
        };
        assert_eq!(
            ClockReply::try_from(wire::ClockReply::from(clock)).unwrap(),
            clock
        );

        let barrier = BarrierPrepare {
            barrier_id: BarrierId::new(),
            session_id: SessionId::new(),
            epoch: 2,
            activate_at_group_ns: 100,
            ready_deadline_group_ns: 90,
            participants: vec![ParticipantId::new(), ParticipantId::new()],
            opaque_context: vec![4],
        };
        assert_eq!(
            BarrierPrepare::try_from(wire::BarrierPrepare::from(&barrier)).unwrap(),
            barrier
        );

        let pairing = PairingChallenge {
            nonce: [1; MESSAGE_NONCE_BYTES],
            transcript_hash: [2; TRANSCRIPT_HASH_BYTES],
        };
        assert_eq!(
            PairingChallenge::try_from(wire::PairingChallenge::from(&pairing)).unwrap(),
            pairing
        );
    }

    #[test]
    fn stream_open_round_trips_and_bounds_e2ee_header() {
        let stream = StreamOpen {
            protocol_magic: 0x4446_4142,
            session_id: SessionId::new(),
            epoch: 8,
            channel_id: ChannelId::new(),
            sender_participant: ParticipantId::new(),
            destination_binding_id: Uuid::new_v4(),
            stream_flags: 5,
            e2ee_header: vec![1, 2, 3],
        };
        assert_eq!(
            StreamOpen::try_from(wire::StreamOpen::from(&stream)).unwrap(),
            stream
        );
    }
}
