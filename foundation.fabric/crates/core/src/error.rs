//! Stable domain and protocol error categories.

use thiserror::Error;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[repr(u16)]
pub enum ProtocolErrorCode {
    InvalidArgument = 1,
    UnsupportedVersion = 2,
    ContractMismatch = 3,
    NotFound = 4,
    AlreadyExists = 5,
    Unauthenticated = 6,
    PermissionDenied = 7,
    PolicyRequiresUserAction = 8,
    ResourceExhausted = 9,
    StaleEpoch = 10,
    SessionNotActive = 11,
    ParticipantOffline = 12,
    ChannelNotBound = 13,
    MessageTooLarge = 14,
    Backpressure = 15,
    ClockUnavailable = 16,
    ClockUncertain = 17,
    E2eeRequired = 18,
    E2eeNegotiationFailed = 19,
    Timeout = 20,
    Internal = 21,
}

#[derive(Clone, Debug, Error, Eq, PartialEq)]
pub enum ValidationError {
    #[error("{field} must not be empty")]
    Empty { field: &'static str },
    #[error("{field} exceeds {max} bytes")]
    TooLong { field: &'static str, max: usize },
    #[error("{field} contains invalid characters")]
    InvalidCharacters { field: &'static str },
    #[error("{field} contains an invalid segment")]
    InvalidSegment { field: &'static str },
    #[error("{field} must be bounded and greater than zero")]
    Unbounded { field: &'static str },
    #[error("{field} has an invalid value: {reason}")]
    InvalidValue {
        field: &'static str,
        reason: &'static str,
    },
}
