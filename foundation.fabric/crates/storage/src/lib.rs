//! `SQLite` metadata storage, transactional revisions, and restart-safe recovery.

use std::path::Path;

use fabric_core::{
    AbilityOffer, AbilityRequirement, OperationId, SessionId, SessionPlan, SessionState,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use sha2::{Digest, Sha256};
use thiserror::Error;

const SCHEMA: &str = r"
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS registry_meta (id INTEGER PRIMARY KEY CHECK(id = 1), revision INTEGER NOT NULL);
INSERT OR IGNORE INTO registry_meta(id, revision) VALUES(1, 0);
CREATE TABLE IF NOT EXISTS offers (
  instance_id BLOB PRIMARY KEY, owner_principal BLOB NOT NULL, contract_key TEXT NOT NULL,
  protocol_hash BLOB NOT NULL, encoded_offer BLOB NOT NULL, lease_kind INTEGER NOT NULL,
  lease_expires_at INTEGER, revision INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS requirements (
  requirement_id BLOB PRIMARY KEY, owner_principal BLOB NOT NULL, encoded_requirement BLOB NOT NULL,
  lease_kind INTEGER NOT NULL, lease_expires_at INTEGER, revision INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS session_records (
  session_id BLOB PRIMARY KEY, epoch INTEGER NOT NULL, state INTEGER NOT NULL,
  encoded_plan BLOB, resume_token_hash BLOB, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS operation_results (
  operation_id BLOB PRIMARY KEY, payload_hash BLOB NOT NULL, encoded_result BLOB NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS registry_outbox (
  revision INTEGER PRIMARY KEY, record_kind INTEGER NOT NULL, object_id BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS peer_registry_revisions (
  peer_device_id BLOB PRIMARY KEY, local_sent_revision INTEGER NOT NULL,
  remote_applied_revision INTEGER NOT NULL
);
";

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("SQLite operation failed: {0}")]
    Sql(#[from] rusqlite::Error),
    #[error("domain encoding failed: {0}")]
    Encode(#[from] serde_json::Error),
    #[error("integer is outside SQLite's signed range")]
    IntegerRange,
    #[error("injected transaction failure")]
    InjectedFailure,
    #[error("operation ID was reused with a different payload")]
    OperationIdReused,
    #[error("database contains an invalid enum value")]
    InvalidStoredValue,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i64)]
pub enum StoredSessionState {
    Proposed = 1,
    Negotiating = 2,
    Preparing = 3,
    Active = 4,
    Reconfiguring = 5,
    Suspended = 6,
    Closing = 7,
    Closed = 8,
    Failed = 9,
    SuspendedAfterRestart = 10,
}

impl From<SessionState> for StoredSessionState {
    fn from(value: SessionState) -> Self {
        match value {
            SessionState::Proposed => Self::Proposed,
            SessionState::Negotiating => Self::Negotiating,
            SessionState::Preparing => Self::Preparing,
            SessionState::Active => Self::Active,
            SessionState::Reconfiguring => Self::Reconfiguring,
            SessionState::Suspended => Self::Suspended,
            SessionState::Closing => Self::Closing,
            SessionState::Closed => Self::Closed,
            SessionState::Failed => Self::Failed,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FaultPoint {
    None,
    AfterRevision,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i64)]
pub enum RegistryRecordKind {
    Offer = 1,
    Requirement = 2,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegistryRemoval {
    pub revision: u64,
    pub kind: RegistryRecordKind,
    pub object_id: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PutOperationResult {
    Inserted,
    Cached(Vec<u8>),
}

pub struct Storage {
    connection: Connection,
}

impl Storage {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, StorageError> {
        let connection = Connection::open(path)?;
        let mut storage = Self { connection };
        storage.migrate()?;
        Ok(storage)
    }

    pub fn open_in_memory() -> Result<Self, StorageError> {
        let connection = Connection::open_in_memory()?;
        let mut storage = Self { connection };
        storage.migrate()?;
        Ok(storage)
    }

    pub fn migrate(&mut self) -> Result<(), StorageError> {
        self.connection.execute_batch(SCHEMA)?;
        Ok(())
    }

    pub fn registry_revision(&self) -> Result<u64, StorageError> {
        let value: i64 = self.connection.query_row(
            "SELECT revision FROM registry_meta WHERE id=1",
            [],
            |row| row.get(0),
        )?;
        u64::try_from(value).map_err(|_| StorageError::IntegerRange)
    }

    pub fn upsert_offer(
        &mut self,
        owner_digest: &[u8; 32],
        offer: &AbilityOffer,
        lease_expires_at: Option<u64>,
    ) -> Result<u64, StorageError> {
        self.upsert_offer_with_fault(owner_digest, offer, lease_expires_at, FaultPoint::None)
    }

    pub fn upsert_offer_with_fault(
        &mut self,
        owner_digest: &[u8; 32],
        offer: &AbilityOffer,
        lease_expires_at: Option<u64>,
        fault: FaultPoint,
    ) -> Result<u64, StorageError> {
        let transaction = self.connection.transaction()?;
        let revision = increment_revision(&transaction)?;
        if fault == FaultPoint::AfterRevision {
            return Err(StorageError::InjectedFailure);
        }
        let encoded = serde_json::to_vec(offer)?;
        let contract_key = format!(
            "{}.{}@{}",
            offer.contract.key.namespace.as_str(),
            offer.contract.key.name.as_str(),
            offer.contract.key.major
        );
        transaction.execute(
            "INSERT INTO offers(instance_id,owner_principal,contract_key,protocol_hash,encoded_offer,lease_kind,lease_expires_at,revision) VALUES(?1,?2,?3,?4,?5,?6,?7,?8) ON CONFLICT(instance_id) DO UPDATE SET owner_principal=excluded.owner_principal,contract_key=excluded.contract_key,protocol_hash=excluded.protocol_hash,encoded_offer=excluded.encoded_offer,lease_kind=excluded.lease_kind,lease_expires_at=excluded.lease_expires_at,revision=excluded.revision",
            params![offer.instance_id.0.as_bytes(), owner_digest, contract_key, offer.contract.protocol_hash, encoded, lease_kind(offer.lease), optional_i64(lease_expires_at)?, to_i64(revision)?],
        )?;
        transaction.commit()?;
        Ok(revision)
    }

    pub fn upsert_requirement(
        &mut self,
        owner_digest: &[u8; 32],
        requirement: &AbilityRequirement,
        lease_expires_at: Option<u64>,
    ) -> Result<u64, StorageError> {
        let transaction = self.connection.transaction()?;
        let revision = increment_revision(&transaction)?;
        let encoded = serde_json::to_vec(requirement)?;
        transaction.execute(
            "INSERT INTO requirements(requirement_id,owner_principal,encoded_requirement,lease_kind,lease_expires_at,revision) VALUES(?1,?2,?3,?4,?5,?6) ON CONFLICT(requirement_id) DO UPDATE SET owner_principal=excluded.owner_principal,encoded_requirement=excluded.encoded_requirement,lease_kind=excluded.lease_kind,lease_expires_at=excluded.lease_expires_at,revision=excluded.revision",
            params![requirement.requirement_id.0.as_bytes(), owner_digest, encoded, lease_kind(requirement.lease), optional_i64(lease_expires_at)?, to_i64(revision)?],
        )?;
        transaction.commit()?;
        Ok(revision)
    }

    pub fn save_session(
        &mut self,
        plan: &SessionPlan,
        state: SessionState,
        resume_token: Option<&[u8]>,
        updated_at_ms: u64,
    ) -> Result<(), StorageError> {
        let encoded = serde_json::to_vec(plan)?;
        let token_hash: Option<[u8; 32]> = resume_token.map(|token| Sha256::digest(token).into());
        self.connection.execute(
            "INSERT INTO session_records(session_id,epoch,state,encoded_plan,resume_token_hash,updated_at) VALUES(?1,?2,?3,?4,?5,?6) ON CONFLICT(session_id) DO UPDATE SET epoch=excluded.epoch,state=excluded.state,encoded_plan=excluded.encoded_plan,resume_token_hash=excluded.resume_token_hash,updated_at=excluded.updated_at",
            params![plan.session_id.0.as_bytes(), to_i64(plan.epoch)?, StoredSessionState::from(state) as i64, encoded, token_hash, to_i64(updated_at_ms)?],
        )?;
        Ok(())
    }

    pub fn startup_recovery(&mut self, now_ms: u64) -> Result<usize, StorageError> {
        let changed = self.connection.execute(
            "UPDATE session_records SET state=?1, updated_at=?2 WHERE state NOT IN (?3,?4,?5)",
            params![
                StoredSessionState::SuspendedAfterRestart as i64,
                to_i64(now_ms)?,
                StoredSessionState::Closed as i64,
                StoredSessionState::Failed as i64,
                StoredSessionState::SuspendedAfterRestart as i64
            ],
        )?;
        let _ = self.gc_expired(now_ms)?;
        Ok(changed)
    }

    pub fn gc_expired(&mut self, now_ms: u64) -> Result<Vec<RegistryRemoval>, StorageError> {
        let transaction = self.connection.transaction()?;
        let now = to_i64(now_ms)?;
        let offer_ids = expired_ids(&transaction, "offers", "instance_id", now)?;
        let requirement_ids = expired_ids(&transaction, "requirements", "requirement_id", now)?;
        let mut removals = Vec::with_capacity(offer_ids.len() + requirement_ids.len());
        for object_id in offer_ids {
            transaction.execute("DELETE FROM offers WHERE instance_id=?1", [&object_id])?;
            removals.push(record_removal(
                &transaction,
                RegistryRecordKind::Offer,
                object_id,
            )?);
        }
        for object_id in requirement_ids {
            transaction.execute(
                "DELETE FROM requirements WHERE requirement_id=?1",
                [&object_id],
            )?;
            removals.push(record_removal(
                &transaction,
                RegistryRecordKind::Requirement,
                object_id,
            )?);
        }
        transaction.execute(
            "DELETE FROM operation_results WHERE expires_at <= ?1",
            [now],
        )?;
        transaction.commit()?;
        Ok(removals)
    }

    pub fn put_operation_result(
        &mut self,
        id: OperationId,
        payload: &[u8],
        result: &[u8],
        expires_at_ms: u64,
    ) -> Result<PutOperationResult, StorageError> {
        let payload_hash: [u8; 32] = Sha256::digest(payload).into();
        let transaction = self.connection.transaction()?;
        let existing: Option<(Vec<u8>, Vec<u8>)> = transaction
            .query_row(
                "SELECT payload_hash,encoded_result FROM operation_results WHERE operation_id=?1",
                [id.0.as_bytes()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        if let Some((existing_hash, existing_result)) = existing {
            if existing_hash == payload_hash {
                return Ok(PutOperationResult::Cached(existing_result));
            }
            return Err(StorageError::OperationIdReused);
        }
        transaction.execute(
            "INSERT INTO operation_results(operation_id,payload_hash,encoded_result,expires_at) VALUES(?1,?2,?3,?4)",
            params![id.0.as_bytes(), payload_hash, result, to_i64(expires_at_ms)?],
        )?;
        transaction.commit()?;
        Ok(PutOperationResult::Inserted)
    }

    pub fn pending_registry_removals(&self) -> Result<Vec<RegistryRemoval>, StorageError> {
        let mut statement = self.connection.prepare(
            "SELECT revision,record_kind,object_id FROM registry_outbox ORDER BY revision",
        )?;
        let rows = statement.query_map([], |row| {
            let revision: i64 = row.get(0)?;
            let kind: i64 = row.get(1)?;
            let object_id: Vec<u8> = row.get(2)?;
            Ok((revision, kind, object_id))
        })?;
        rows.map(|row| {
            let (revision, kind, object_id) = row?;
            Ok(RegistryRemoval {
                revision: u64::try_from(revision).map_err(|_| StorageError::IntegerRange)?,
                kind: decode_record_kind(kind)?,
                object_id,
            })
        })
        .collect()
    }

    pub fn session_state(&self, id: SessionId) -> Result<Option<StoredSessionState>, StorageError> {
        let raw: Option<i64> = self
            .connection
            .query_row(
                "SELECT state FROM session_records WHERE session_id=?1",
                [id.0.as_bytes()],
                |row| row.get(0),
            )
            .optional()?;
        Ok(raw.and_then(decode_state))
    }

    pub fn resume_token_hash(&self, id: SessionId) -> Result<Option<Vec<u8>>, StorageError> {
        Ok(self
            .connection
            .query_row(
                "SELECT resume_token_hash FROM session_records WHERE session_id=?1",
                [id.0.as_bytes()],
                |row| row.get(0),
            )
            .optional()?
            .flatten())
    }
}

fn increment_revision(transaction: &Transaction<'_>) -> Result<u64, StorageError> {
    transaction.execute(
        "UPDATE registry_meta SET revision=revision+1 WHERE id=1",
        [],
    )?;
    let revision: i64 =
        transaction.query_row("SELECT revision FROM registry_meta WHERE id=1", [], |row| {
            row.get(0)
        })?;
    u64::try_from(revision).map_err(|_| StorageError::IntegerRange)
}

fn expired_ids(
    transaction: &Transaction<'_>,
    table: &'static str,
    id_column: &'static str,
    now: i64,
) -> Result<Vec<Vec<u8>>, StorageError> {
    let sql = format!(
        "SELECT {id_column} FROM {table} WHERE lease_expires_at IS NOT NULL AND lease_expires_at <= ?1 ORDER BY {id_column}"
    );
    let mut statement = transaction.prepare(&sql)?;
    let rows = statement.query_map([now], |row| row.get(0))?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(StorageError::from)
}

fn record_removal(
    transaction: &Transaction<'_>,
    kind: RegistryRecordKind,
    object_id: Vec<u8>,
) -> Result<RegistryRemoval, StorageError> {
    let revision = increment_revision(transaction)?;
    transaction.execute(
        "INSERT INTO registry_outbox(revision,record_kind,object_id) VALUES(?1,?2,?3)",
        params![to_i64(revision)?, kind as i64, &object_id],
    )?;
    Ok(RegistryRemoval {
        revision,
        kind,
        object_id,
    })
}

fn decode_record_kind(value: i64) -> Result<RegistryRecordKind, StorageError> {
    match value {
        1 => Ok(RegistryRecordKind::Offer),
        2 => Ok(RegistryRecordKind::Requirement),
        _ => Err(StorageError::InvalidStoredValue),
    }
}

fn lease_kind(lease: fabric_core::LeaseSpec) -> i64 {
    i64::from(!matches!(lease, fabric_core::LeaseSpec::ConnectionBound))
}
fn to_i64(value: u64) -> Result<i64, StorageError> {
    i64::try_from(value).map_err(|_| StorageError::IntegerRange)
}
fn optional_i64(value: Option<u64>) -> Result<Option<i64>, StorageError> {
    value.map(to_i64).transpose()
}
fn decode_state(value: i64) -> Option<StoredSessionState> {
    Some(match value {
        1 => StoredSessionState::Proposed,
        2 => StoredSessionState::Negotiating,
        3 => StoredSessionState::Preparing,
        4 => StoredSessionState::Active,
        5 => StoredSessionState::Reconfiguring,
        6 => StoredSessionState::Suspended,
        7 => StoredSessionState::Closing,
        8 => StoredSessionState::Closed,
        9 => StoredSessionState::Failed,
        10 => StoredSessionState::SuspendedAfterRestart,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;

    fn offer() -> AbilityOffer {
        AbilityOffer {
            instance_id: AbilityInstanceId::new(),
            contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: [1; 32],
            },
            app: AppPrincipal {
                platform: Platform::Test,
                stable_app_id: "test.app".into(),
                publisher_id: None,
                signing_digest: None,
                os_subject: OsSubject("test:1".into()),
            },
            roles: vec![RoleId::new("echo").unwrap()],
            properties: PropertyMap::new(),
            visibility: OfferVisibility::TrustedDevices,
            access_policy: PolicyRef("default".into()),
            lease: LeaseSpec::ConnectionBound,
        }
    }
    fn plan() -> SessionPlan {
        let id = ParticipantId::new();
        SessionPlan {
            session_id: SessionId::new(),
            contract: offer().contract,
            epoch: 1,
            coordinator: id,
            participants: vec![Participant {
                id,
                device_id: DeviceId([1; 32]),
                app_principal: offer().app,
                ability_instance: AbilityInstanceId::new(),
                role: RoleId::new("echo").unwrap(),
                port_bindings: vec![],
            }],
            channels: vec![],
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        }
    }

    #[test]
    fn failed_transaction_does_not_advance_revision() {
        let mut storage = Storage::open_in_memory().unwrap();
        assert!(matches!(
            storage.upsert_offer_with_fault(&[1; 32], &offer(), None, FaultPoint::AfterRevision),
            Err(StorageError::InjectedFailure)
        ));
        assert_eq!(storage.registry_revision().unwrap(), 0);
    }

    #[test]
    fn active_session_recovers_suspended_and_only_token_hash_is_stored() {
        let mut storage = Storage::open_in_memory().unwrap();
        let plan = plan();
        storage
            .save_session(
                &plan,
                SessionState::Active,
                Some(b"high entropy resume token"),
                1,
            )
            .unwrap();
        storage.startup_recovery(2).unwrap();
        assert_eq!(
            storage.session_state(plan.session_id).unwrap(),
            Some(StoredSessionState::SuspendedAfterRestart)
        );
        let hash = storage.resume_token_hash(plan.session_id).unwrap().unwrap();
        assert_ne!(hash, b"high entropy resume token");
        assert_eq!(hash.len(), 32);
    }

    #[test]
    fn operation_id_reuse_returns_cached_result_or_conflict() {
        let mut storage = Storage::open_in_memory().unwrap();
        let id = OperationId::new();
        assert_eq!(
            storage
                .put_operation_result(id, b"payload", b"result", 100)
                .unwrap(),
            PutOperationResult::Inserted
        );
        assert_eq!(
            storage
                .put_operation_result(id, b"payload", b"ignored", 100)
                .unwrap(),
            PutOperationResult::Cached(b"result".to_vec())
        );
        assert!(matches!(
            storage.put_operation_result(id, b"different", b"result", 100),
            Err(StorageError::OperationIdReused)
        ));
    }

    #[test]
    fn lease_gc_advances_revision_and_persists_remove_outbox() {
        let mut storage = Storage::open_in_memory().unwrap();
        let mut value = offer();
        value.lease = LeaseSpec::Renewable { ttl_ms: 10 };
        storage.upsert_offer(&[1; 32], &value, Some(10)).unwrap();
        let removals = storage.gc_expired(10).unwrap();
        assert_eq!(removals.len(), 1);
        assert_eq!(removals[0].kind, RegistryRecordKind::Offer);
        assert_eq!(removals[0].revision, 2);
        assert_eq!(storage.registry_revision().unwrap(), 2);
        assert_eq!(storage.pending_registry_removals().unwrap(), removals);
    }
}
