use std::collections::{BTreeMap, BTreeSet, VecDeque};

use fabric_core::{DeviceId, OperationId, ProtocolErrorCode, SessionId};
use sha2::{Digest, Sha256};
use thiserror::Error;

use crate::{MESSAGE_NONCE_BYTES, SessionControlHeader};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ControlSecurityConfig {
    pub maximum_operations: usize,
    pub maximum_nonces_per_peer: usize,
    pub retry_window_ms: u64,
    pub maximum_cached_result_bytes: usize,
}

impl Default for ControlSecurityConfig {
    fn default() -> Self {
        Self {
            maximum_operations: 4096,
            maximum_nonces_per_peer: 4096,
            retry_window_ms: 5 * 60 * 1000,
            maximum_cached_result_bytes: 1024 * 1024,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EpochPolicy {
    New,
    Exact,
    Next,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum IngressDecision {
    Execute,
    Cached(Vec<u8>),
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum SecurityError {
    #[error("control security limits must be nonzero")]
    InvalidConfiguration,
    #[error("sender device does not match the authenticated peer")]
    SenderMismatch,
    #[error("message nonce was already used")]
    Replay,
    #[error("Session already exists")]
    SessionAlreadyExists,
    #[error("Session is unknown")]
    UnknownSession,
    #[error("stale or future Session epoch: expected {expected}, got {actual}")]
    StaleEpoch { expected: u64, actual: u64 },
    #[error("Session epoch cannot be incremented")]
    EpochOverflow,
    #[error("operation ID was reused with a different payload")]
    OperationIdReused,
    #[error("operation is already executing")]
    OperationInFlight,
    #[error("operation result exceeds the configured cache limit")]
    ResultTooLarge,
    #[error("operation is not registered")]
    UnknownOperation,
}

impl SecurityError {
    #[must_use]
    pub const fn protocol_code(&self) -> ProtocolErrorCode {
        match self {
            Self::SenderMismatch => ProtocolErrorCode::Unauthenticated,
            Self::Replay | Self::OperationIdReused => ProtocolErrorCode::InvalidArgument,
            Self::SessionAlreadyExists => ProtocolErrorCode::AlreadyExists,
            Self::UnknownSession | Self::UnknownOperation => ProtocolErrorCode::NotFound,
            Self::StaleEpoch { .. } => ProtocolErrorCode::StaleEpoch,
            Self::OperationInFlight => ProtocolErrorCode::Backpressure,
            Self::InvalidConfiguration | Self::ResultTooLarge | Self::EpochOverflow => {
                ProtocolErrorCode::ResourceExhausted
            }
        }
    }
}

#[derive(Clone, Debug)]
struct OperationEntry {
    payload_hash: [u8; 32],
    result: Option<Vec<u8>>,
    expires_at_ms: u64,
}

#[derive(Default)]
struct PeerNonces {
    values: BTreeSet<[u8; MESSAGE_NONCE_BYTES]>,
    order: VecDeque<([u8; MESSAGE_NONCE_BYTES], u64)>,
}

pub struct ControlSecurity {
    config: ControlSecurityConfig,
    session_epochs: BTreeMap<SessionId, u64>,
    operations: BTreeMap<OperationId, OperationEntry>,
    operation_order: VecDeque<OperationId>,
    nonces: BTreeMap<DeviceId, PeerNonces>,
}

impl ControlSecurity {
    pub fn new(config: ControlSecurityConfig) -> Result<Self, SecurityError> {
        if config.maximum_operations == 0
            || config.maximum_nonces_per_peer == 0
            || config.retry_window_ms == 0
            || config.maximum_cached_result_bytes == 0
        {
            return Err(SecurityError::InvalidConfiguration);
        }
        Ok(Self {
            config,
            session_epochs: BTreeMap::new(),
            operations: BTreeMap::new(),
            operation_order: VecDeque::new(),
            nonces: BTreeMap::new(),
        })
    }

    pub fn set_session_epoch(&mut self, session_id: SessionId, epoch: u64) {
        self.session_epochs.insert(session_id, epoch);
    }

    pub fn remove_session(&mut self, session_id: SessionId) {
        self.session_epochs.remove(&session_id);
    }

    pub fn advance_session_epoch(
        &mut self,
        session_id: SessionId,
        expected_current: u64,
    ) -> Result<u64, SecurityError> {
        let current = self
            .session_epochs
            .get_mut(&session_id)
            .ok_or(SecurityError::UnknownSession)?;
        if *current != expected_current {
            return Err(SecurityError::StaleEpoch {
                expected: *current,
                actual: expected_current,
            });
        }
        *current = current.checked_add(1).ok_or(SecurityError::EpochOverflow)?;
        Ok(*current)
    }

    pub fn authorize(
        &mut self,
        header: &SessionControlHeader,
        authenticated_peer: DeviceId,
        semantic_payload: &[u8],
        epoch_policy: EpochPolicy,
        now_ms: u64,
    ) -> Result<IngressDecision, SecurityError> {
        if header.sender_device_id != authenticated_peer {
            return Err(SecurityError::SenderMismatch);
        }
        self.expire(now_ms);
        self.check_and_record_nonce(authenticated_peer, header.message_nonce, now_ms)?;

        let payload_hash = Sha256::digest(semantic_payload).into();
        if let Some(entry) = self.operations.get(&header.operation_id) {
            if entry.payload_hash != payload_hash {
                return Err(SecurityError::OperationIdReused);
            }
            return entry
                .result
                .clone()
                .map(IngressDecision::Cached)
                .ok_or(SecurityError::OperationInFlight);
        }

        self.check_epoch(header, epoch_policy)?;

        self.evict_operation_if_full();
        self.operation_order.push_back(header.operation_id);
        self.operations.insert(
            header.operation_id,
            OperationEntry {
                payload_hash,
                result: None,
                expires_at_ms: now_ms.saturating_add(self.config.retry_window_ms),
            },
        );
        Ok(IngressDecision::Execute)
    }

    pub fn complete(
        &mut self,
        operation_id: OperationId,
        encoded_result: Vec<u8>,
    ) -> Result<(), SecurityError> {
        if encoded_result.len() > self.config.maximum_cached_result_bytes {
            return Err(SecurityError::ResultTooLarge);
        }
        let operation = self
            .operations
            .get_mut(&operation_id)
            .ok_or(SecurityError::UnknownOperation)?;
        operation.result = Some(encoded_result);
        Ok(())
    }

    pub fn abandon(&mut self, operation_id: OperationId) {
        self.operations.remove(&operation_id);
        self.operation_order.retain(|id| *id != operation_id);
    }

    fn check_epoch(
        &self,
        header: &SessionControlHeader,
        policy: EpochPolicy,
    ) -> Result<(), SecurityError> {
        match (policy, self.session_epochs.get(&header.session_id).copied()) {
            (EpochPolicy::New, None) if header.expected_epoch == 0 => Ok(()),
            (EpochPolicy::New, None) => Err(SecurityError::StaleEpoch {
                expected: 0,
                actual: header.expected_epoch,
            }),
            (EpochPolicy::New, Some(_)) => Err(SecurityError::SessionAlreadyExists),
            (EpochPolicy::Exact | EpochPolicy::Next, None) => Err(SecurityError::UnknownSession),
            (EpochPolicy::Exact, Some(current)) => {
                check_expected_epoch(current, header.expected_epoch)
            }
            (EpochPolicy::Next, Some(current)) => check_expected_epoch(
                current.checked_add(1).ok_or(SecurityError::EpochOverflow)?,
                header.expected_epoch,
            ),
        }
    }

    fn check_and_record_nonce(
        &mut self,
        peer: DeviceId,
        nonce: [u8; MESSAGE_NONCE_BYTES],
        now_ms: u64,
    ) -> Result<(), SecurityError> {
        let peer_nonces = self.nonces.entry(peer).or_default();
        if peer_nonces.values.contains(&nonce) {
            return Err(SecurityError::Replay);
        }
        while peer_nonces.order.len() >= self.config.maximum_nonces_per_peer {
            if let Some((oldest, _)) = peer_nonces.order.pop_front() {
                peer_nonces.values.remove(&oldest);
            }
        }
        peer_nonces.values.insert(nonce);
        peer_nonces
            .order
            .push_back((nonce, now_ms.saturating_add(self.config.retry_window_ms)));
        Ok(())
    }

    fn expire(&mut self, now_ms: u64) {
        let expired_operations: Vec<_> = self
            .operations
            .iter()
            .filter_map(|(id, entry)| (entry.expires_at_ms <= now_ms).then_some(*id))
            .collect();
        for operation_id in expired_operations {
            self.operations.remove(&operation_id);
        }
        self.operation_order
            .retain(|id| self.operations.contains_key(id));

        self.nonces.retain(|_, peer| {
            while peer
                .order
                .front()
                .is_some_and(|(_, expires_at)| *expires_at <= now_ms)
            {
                if let Some((nonce, _)) = peer.order.pop_front() {
                    peer.values.remove(&nonce);
                }
            }
            !peer.values.is_empty()
        });
    }

    fn evict_operation_if_full(&mut self) {
        while self.operations.len() >= self.config.maximum_operations {
            let Some(oldest) = self.operation_order.pop_front() else {
                break;
            };
            self.operations.remove(&oldest);
        }
    }
}

fn check_expected_epoch(expected: u64, actual: u64) -> Result<(), SecurityError> {
    if expected == actual {
        Ok(())
    } else {
        Err(SecurityError::StaleEpoch { expected, actual })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(operation_id: OperationId, nonce: u8) -> SessionControlHeader {
        SessionControlHeader {
            operation_id,
            session_id: SessionId(uuid::Uuid::from_u128(1)),
            expected_epoch: 3,
            sender_device_id: DeviceId([1; 32]),
            message_nonce: [nonce; MESSAGE_NONCE_BYTES],
        }
    }

    #[test]
    fn authenticates_peer_epoch_nonce_and_idempotent_result() {
        let mut guard = ControlSecurity::new(ControlSecurityConfig::default()).unwrap();
        let operation = OperationId::new();
        let first = header(operation, 1);
        guard.set_session_epoch(first.session_id, 3);
        assert_eq!(
            guard.authorize(&first, DeviceId([1; 32]), b"close", EpochPolicy::Exact, 10),
            Ok(IngressDecision::Execute)
        );
        guard.complete(operation, b"closed".to_vec()).unwrap();
        guard.set_session_epoch(first.session_id, 4);

        let retry = header(operation, 2);
        assert_eq!(
            guard.authorize(&retry, DeviceId([1; 32]), b"close", EpochPolicy::Exact, 11),
            Ok(IngressDecision::Cached(b"closed".to_vec()))
        );
    }

    #[test]
    fn rejects_replay_reuse_sender_and_epoch_mismatch() {
        let mut guard = ControlSecurity::new(ControlSecurityConfig::default()).unwrap();
        let operation = OperationId::new();
        let value = header(operation, 1);
        guard.set_session_epoch(value.session_id, 3);

        assert_eq!(
            guard.authorize(&value, DeviceId([2; 32]), b"x", EpochPolicy::Exact, 10),
            Err(SecurityError::SenderMismatch)
        );
        assert_eq!(
            guard.authorize(&value, DeviceId([1; 32]), b"x", EpochPolicy::Exact, 10),
            Ok(IngressDecision::Execute)
        );
        assert_eq!(
            guard.authorize(&value, DeviceId([1; 32]), b"x", EpochPolicy::Exact, 10),
            Err(SecurityError::Replay)
        );

        let reused = header(operation, 2);
        assert_eq!(
            guard.authorize(
                &reused,
                DeviceId([1; 32]),
                b"different",
                EpochPolicy::Exact,
                11
            ),
            Err(SecurityError::OperationIdReused)
        );

        let mut stale = header(OperationId::new(), 3);
        stale.expected_epoch = 2;
        assert_eq!(
            guard.authorize(&stale, DeviceId([1; 32]), b"x", EpochPolicy::Exact, 11),
            Err(SecurityError::StaleEpoch {
                expected: 3,
                actual: 2
            })
        );
    }

    #[test]
    fn supports_new_and_next_epoch_policies_and_expiry() {
        let config = ControlSecurityConfig {
            maximum_operations: 2,
            maximum_nonces_per_peer: 2,
            retry_window_ms: 5,
            maximum_cached_result_bytes: 16,
        };
        let mut guard = ControlSecurity::new(config).unwrap();
        let mut new = header(OperationId::new(), 1);
        new.expected_epoch = 0;
        assert_eq!(
            guard.authorize(&new, DeviceId([1; 32]), b"new", EpochPolicy::New, 10),
            Ok(IngressDecision::Execute)
        );

        guard.set_session_epoch(new.session_id, 3);
        let mut next = header(OperationId::new(), 2);
        next.expected_epoch = 4;
        assert_eq!(
            guard.authorize(&next, DeviceId([1; 32]), b"next", EpochPolicy::Next, 10),
            Ok(IngressDecision::Execute)
        );
        assert_eq!(guard.advance_session_epoch(next.session_id, 3), Ok(4));

        let mut expired = header(OperationId::new(), 1);
        expired.expected_epoch = 4;
        assert_eq!(
            guard.authorize(
                &expired,
                DeviceId([1; 32]),
                b"after-expiry",
                EpochPolicy::Exact,
                16
            ),
            Ok(IngressDecision::Execute)
        );
    }

    #[test]
    fn rejects_epoch_overflow() {
        let mut guard = ControlSecurity::new(ControlSecurityConfig::default()).unwrap();
        let value = header(OperationId::new(), 1);
        guard.set_session_epoch(value.session_id, u64::MAX);
        assert_eq!(
            guard.advance_session_epoch(value.session_id, u64::MAX),
            Err(SecurityError::EpochOverflow)
        );
        let mut next = header(OperationId::new(), 2);
        next.expected_epoch = u64::MAX;
        assert_eq!(
            guard.authorize(&next, DeviceId([1; 32]), b"next", EpochPolicy::Next, 10),
            Err(SecurityError::EpochOverflow)
        );
    }
}
