//! `SQLite` metadata storage, transactional revisions, and restart-safe recovery.

use std::path::Path;
use std::sync::Mutex;

use fabric_core::{
    AbilityOffer, AbilityRequirement, OperationId, SessionId, SessionPlan, SessionState,
};
use fabric_session::{ElectionCertificate, ElectionError, ElectionPersistentState, ElectionStore};
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
CREATE TABLE IF NOT EXISTS coordinator_elections (
  session_id BLOB PRIMARY KEY CHECK(length(session_id) = 16),
  membership_hash BLOB NOT NULL CHECK(length(membership_hash) = 32),
  term INTEGER NOT NULL CHECK(term >= 0),
  voted_for BLOB CHECK(voted_for IS NULL OR length(voted_for) = 16),
  committed_epoch INTEGER NOT NULL CHECK(committed_epoch >= 1),
  committed_plan_hash BLOB NOT NULL CHECK(length(committed_plan_hash) = 32)
);
CREATE TABLE IF NOT EXISTS coordinator_election_certificates (
  session_id BLOB PRIMARY KEY CHECK(length(session_id) = 16),
  term INTEGER NOT NULL CHECK(term > 0),
  committed_epoch INTEGER NOT NULL CHECK(committed_epoch >= 1),
  committed_plan_hash BLOB NOT NULL CHECK(length(committed_plan_hash) = 32),
  candidate BLOB NOT NULL CHECK(length(candidate) = 16),
  encoded_certificate BLOB NOT NULL
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
    #[error("Session snapshot and durable election checkpoint are inconsistent")]
    InconsistentSessionCheckpoint,
    #[error("election checkpoint failure: {0}")]
    Election(#[from] ElectionError),
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
    AfterSessionSnapshot,
}

/// Always restored suspended; a consistent snapshot is not a current leader
/// certificate, application acceptance, or permission to resume data channels.
pub struct SessionCheckpoint {
    pub plan: SessionPlan,
    pub election: ElectionPersistentState,
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
        connection.busy_timeout(std::time::Duration::from_secs(5))?;
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

    /// Initialize the committed plan snapshot and election fingerprint together.
    /// Existing higher terms/votes are retained; this cannot reset a vote.
    pub fn initialize_session_checkpoint(
        &mut self,
        plan: &SessionPlan,
        now_ms: u64,
    ) -> Result<(), StorageError> {
        plan.validate()
            .map_err(|_| StorageError::InvalidStoredValue)?;
        let membership = fabric_session::membership_config_hash(plan);
        let hash = plan.canonical_hash();
        let tx = self.connection.transaction()?;
        if let Some(state) = read_election_state(&tx, plan.session_id)? {
            if state.membership_hash != membership
                || state.committed_epoch != plan.epoch
                || state.committed_plan_hash != hash
            {
                return Err(StorageError::InconsistentSessionCheckpoint);
            }
        } else {
            tx.execute("INSERT INTO coordinator_elections(session_id,membership_hash,term,voted_for,committed_epoch,committed_plan_hash) VALUES(?1,?2,0,NULL,?3,?4)",
                params![plan.session_id.0.as_bytes(),membership.as_slice(),to_i64(plan.epoch)?,hash.as_slice()])?;
        }
        // Initialization is idempotent and cannot replace an existing snapshot.
        tx.execute("INSERT OR IGNORE INTO session_records(session_id,epoch,state,encoded_plan,updated_at) VALUES(?1,?2,?3,?4,?5)",
            params![plan.session_id.0.as_bytes(),to_i64(plan.epoch)?,StoredSessionState::Suspended as i64,serde_json::to_vec(plan)?,to_i64(now_ms)?])?;
        let encoded: Vec<u8> = tx.query_row(
            "SELECT encoded_plan FROM session_records WHERE session_id=?1",
            [plan.session_id.0.as_bytes()],
            |row| row.get(0),
        )?;
        let existing: SessionPlan = serde_json::from_slice(&encoded)?;
        if existing != *plan {
            return Err(StorageError::InconsistentSessionCheckpoint);
        }
        tx.commit()?;
        Ok(())
    }

    /// Commit a fully Ready reconfiguration only after the Session driver has
    /// accepted it. Storage verifies its majority certificate/current checkpoint
    /// and atomically advances BOTH records. This does not replace Ready checks.
    pub fn commit_session_checkpoint(
        &mut self,
        previous: &SessionPlan,
        next: &SessionPlan,
        certificate: &fabric_session::ElectionCertificate,
        now_ms: u64,
        fault: FaultPoint,
    ) -> Result<(), StorageError> {
        certificate.verify(previous)?;
        let encoded_certificate = certificate.encode_for_storage()?;
        next.validate()
            .map_err(|_| StorageError::InvalidStoredValue)?;
        let membership = fabric_session::membership_config_hash(previous);
        if next.session_id != previous.session_id
            || next.epoch
                != previous
                    .epoch
                    .checked_add(1)
                    .ok_or(StorageError::IntegerRange)?
            || fabric_session::membership_config_hash(next) != membership
            || next.coordinator != certificate.candidate
        {
            return Err(StorageError::InconsistentSessionCheckpoint);
        }
        let tx = self.connection.transaction()?;
        let state = read_election_state(&tx, previous.session_id)?
            .ok_or(StorageError::InconsistentSessionCheckpoint)?;
        let encoded: Vec<u8> = tx.query_row(
            "SELECT encoded_plan FROM session_records WHERE session_id=?1",
            [previous.session_id.0.as_bytes()],
            |r| r.get(0),
        )?;
        let snapshot: SessionPlan = serde_json::from_slice(&encoded)?;
        // Safe repeated commit after a lost local completion is idempotent.
        if snapshot == *next
            && state.committed_epoch == next.epoch
            && state.committed_plan_hash == next.canonical_hash()
            && state.term == certificate.term
        {
            persist_election_certificate(&tx, previous, certificate, &encoded_certificate)?;
            tx.commit()?;
            return Ok(());
        }
        if snapshot != *previous
            || state.committed_epoch != previous.epoch
            || state.committed_plan_hash != previous.canonical_hash()
            || state.membership_hash != membership
            || state.term != certificate.term
        {
            return Err(StorageError::InconsistentSessionCheckpoint);
        }
        tx.execute("UPDATE session_records SET epoch=?1,state=?2,encoded_plan=?3,resume_token_hash=NULL,updated_at=?4 WHERE session_id=?5",
            params![to_i64(next.epoch)?,StoredSessionState::Active as i64,serde_json::to_vec(next)?,to_i64(now_ms)?,next.session_id.0.as_bytes()])?;
        if fault == FaultPoint::AfterSessionSnapshot {
            return Err(StorageError::InjectedFailure);
        }
        tx.execute("UPDATE coordinator_elections SET committed_epoch=?1,committed_plan_hash=?2 WHERE session_id=?3",
            params![to_i64(next.epoch)?,next.canonical_hash().as_slice(),next.session_id.0.as_bytes()])?;
        persist_election_certificate(&tx, previous, certificate, &encoded_certificate)?;
        tx.commit()?;
        Ok(())
    }

    /// Load the majority proof committed with the latest epoch transition.
    /// A restored Session remains suspended and still requires fresh recovery.
    pub fn load_election_certificate(
        &self,
        id: SessionId,
    ) -> Result<Option<ElectionCertificate>, StorageError> {
        let row: Option<(i64, i64, Vec<u8>, Vec<u8>, Vec<u8>)> = self
            .connection
            .query_row(
                "SELECT term,committed_epoch,committed_plan_hash,candidate,encoded_certificate
                 FROM coordinator_election_certificates WHERE session_id=?1",
                [id.0.as_bytes()],
                |row| {
                    Ok((
                        row.get(0)?,
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                    ))
                },
            )
            .optional()?;
        let Some((term, epoch, plan_hash, candidate, encoded)) = row else {
            return Ok(None);
        };
        let certificate = ElectionCertificate::decode_from_storage(&encoded)?;
        if certificate.session_id != id
            || certificate.term != election_u64(term)?
            || certificate.committed_epoch != election_u64(epoch)?
            || certificate.committed_plan_hash.as_slice() != plan_hash
            || certificate.candidate.0.as_bytes().as_slice() != candidate
        {
            return Err(StorageError::InconsistentSessionCheckpoint);
        }
        Ok(Some(certificate))
    }

    pub fn load_session_checkpoint(
        &self,
        id: SessionId,
    ) -> Result<Option<SessionCheckpoint>, StorageError> {
        let row: Option<(i64, Vec<u8>)> = self
            .connection
            .query_row(
                "SELECT epoch, encoded_plan FROM session_records WHERE session_id=?1",
                [id.0.as_bytes()],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .optional()?;
        let Some((stored_epoch, encoded)) = row else {
            return Ok(None);
        };
        let plan: SessionPlan = serde_json::from_slice(&encoded)?;
        plan.validate()
            .map_err(|_| StorageError::InconsistentSessionCheckpoint)?;
        let election = read_election_state(&self.connection, id)?
            .ok_or(StorageError::InconsistentSessionCheckpoint)?;
        if plan.session_id != id
            || election_u64(stored_epoch)
                .map_err(|_| StorageError::InconsistentSessionCheckpoint)?
                != plan.epoch
            || election.committed_epoch != plan.epoch
            || election.committed_plan_hash != plan.canonical_hash()
            || election.membership_hash != fabric_session::membership_config_hash(&plan)
        {
            return Err(StorageError::InconsistentSessionCheckpoint);
        }
        Ok(Some(SessionCheckpoint { plan, election }))
    }

    /// Load only snapshots whose stored state proves that a plan was committed.
    /// Legacy Active/Suspended rows are initialized at term zero. Transient
    /// Proposed/Negotiating/Preparing rows are excluded because they may contain
    /// an uncommitted plan. Every returned snapshot is durably marked suspended.
    pub fn load_recoverable_session_checkpoints(
        &mut self,
        now_ms: u64,
    ) -> Result<Vec<SessionCheckpoint>, StorageError> {
        let plans = {
            let mut statement = self.connection.prepare(
                "SELECT s.encoded_plan FROM session_records s
                 LEFT JOIN coordinator_elections e ON e.session_id=s.session_id
                 WHERE s.encoded_plan IS NOT NULL AND
                   (s.state IN (?1, ?2) OR
                    (s.state=?3 AND e.session_id IS NOT NULL))",
            )?;
            statement
                .query_map(
                    params![
                        StoredSessionState::Active as i64,
                        StoredSessionState::Suspended as i64,
                        StoredSessionState::SuspendedAfterRestart as i64
                    ],
                    |row| row.get::<_, Vec<u8>>(0),
                )?
                .collect::<Result<Vec<_>, _>>()?
        };
        let mut checkpoints = Vec::with_capacity(plans.len());
        for encoded in plans {
            let plan: SessionPlan = serde_json::from_slice(&encoded)?;
            plan.validate()
                .map_err(|_| StorageError::InconsistentSessionCheckpoint)?;
            self.initialize_session_checkpoint(&plan, now_ms)?;
            let checkpoint = self
                .load_session_checkpoint(plan.session_id)?
                .ok_or(StorageError::InconsistentSessionCheckpoint)?;
            self.connection.execute(
                "UPDATE session_records SET state=?1,updated_at=?2 WHERE session_id=?3",
                params![
                    StoredSessionState::SuspendedAfterRestart as i64,
                    to_i64(now_ms)?,
                    plan.session_id.0.as_bytes()
                ],
            )?;
            checkpoints.push(checkpoint);
        }
        Ok(checkpoints)
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

fn persist_election_certificate(
    transaction: &Transaction<'_>,
    previous: &SessionPlan,
    certificate: &ElectionCertificate,
    encoded_certificate: &[u8],
) -> Result<(), StorageError> {
    transaction.execute(
        "INSERT INTO coordinator_election_certificates
         (session_id,term,committed_epoch,committed_plan_hash,candidate,encoded_certificate)
         VALUES(?1,?2,?3,?4,?5,?6)
         ON CONFLICT(session_id) DO UPDATE SET
           term=excluded.term,
           committed_epoch=excluded.committed_epoch,
           committed_plan_hash=excluded.committed_plan_hash,
           candidate=excluded.candidate,
           encoded_certificate=excluded.encoded_certificate
         WHERE excluded.term >= coordinator_election_certificates.term",
        params![
            previous.session_id.0.as_bytes(),
            to_i64(certificate.term)?,
            to_i64(previous.epoch)?,
            previous.canonical_hash().as_slice(),
            certificate.candidate.0.as_bytes(),
            encoded_certificate,
        ],
    )?;
    Ok(())
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

/// SQLite-backed durable votes. Open this over the Hub's existing database path.
/// Each term/vote and committed-plan update uses a write transaction.
pub struct SqliteElectionStore {
    connection: Mutex<Connection>,
}

impl SqliteElectionStore {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, ElectionError> {
        let connection = Connection::open(path).map_err(map_election_sql_error)?;
        connection
            .busy_timeout(std::time::Duration::from_secs(5))
            .map_err(map_election_sql_error)?;
        connection
            .execute_batch(
                "CREATE TABLE IF NOT EXISTS coordinator_elections (
                   session_id BLOB PRIMARY KEY CHECK(length(session_id) = 16),
                   membership_hash BLOB NOT NULL CHECK(length(membership_hash) = 32),
                   term INTEGER NOT NULL CHECK(term >= 0),
                   voted_for BLOB CHECK(voted_for IS NULL OR length(voted_for) = 16),
                   committed_epoch INTEGER NOT NULL CHECK(committed_epoch >= 1),
                   committed_plan_hash BLOB NOT NULL CHECK(length(committed_plan_hash) = 32)
                 );",
            )
            .map_err(map_election_sql_error)?;
        Ok(Self {
            connection: Mutex::new(connection),
        })
    }
}

impl ElectionStore for SqliteElectionStore {
    fn initialize(
        &self,
        session_id: SessionId,
        initial: ElectionPersistentState,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let transaction = connection.transaction().map_err(map_election_sql_error)?;
        transaction
            .execute(
                "INSERT OR IGNORE INTO coordinator_elections
                 (session_id, membership_hash, term, voted_for, committed_epoch, committed_plan_hash)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    session_id.0.as_bytes(),
                    initial.membership_hash.as_slice(),
                    election_i64(initial.term)?,
                    initial.voted_for.map(|participant| participant.0.as_bytes().to_vec()),
                    election_i64(initial.committed_epoch)?,
                    initial.committed_plan_hash.as_slice(),
                ],
            )
            .map_err(map_election_sql_error)?;
        let state = read_election_state(&transaction, session_id)?
            .ok_or(ElectionError::PersistenceUnavailable)?;
        if state.membership_hash != initial.membership_hash
            || state.committed_epoch != initial.committed_epoch
            || state.committed_plan_hash != initial.committed_plan_hash
        {
            return Err(ElectionError::StateMismatch);
        }
        transaction.commit().map_err(map_election_sql_error)?;
        Ok(state)
    }

    fn load(
        &self,
        session_id: SessionId,
    ) -> Result<Option<ElectionPersistentState>, ElectionError> {
        let connection = self
            .connection
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        read_election_state(&connection, session_id)
    }

    fn observe_term(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        term: u64,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let transaction = connection.transaction().map_err(map_election_sql_error)?;
        let mut state =
            read_election_state(&transaction, session_id)?.ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash {
            return Err(ElectionError::StateMismatch);
        }
        if term < state.term {
            return Err(ElectionError::StaleTerm);
        }
        if term > state.term {
            transaction
                .execute(
                    "UPDATE coordinator_elections SET term=?1, voted_for=NULL WHERE session_id=?2",
                    params![election_i64(term)?, session_id.0.as_bytes()],
                )
                .map_err(map_election_sql_error)?;
            state.term = term;
            state.voted_for = None;
        }
        transaction.commit().map_err(map_election_sql_error)?;
        Ok(state)
    }

    fn vote_once(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        committed_epoch: u64,
        committed_plan_hash: [u8; 32],
        term: u64,
        candidate: fabric_core::ParticipantId,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let transaction = connection.transaction().map_err(map_election_sql_error)?;
        let mut state =
            read_election_state(&transaction, session_id)?.ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash
            || state.committed_epoch != committed_epoch
            || state.committed_plan_hash != committed_plan_hash
        {
            return Err(ElectionError::StateMismatch);
        }
        if term < state.term {
            return Err(ElectionError::StaleTerm);
        }
        if state
            .voted_for
            .is_some_and(|voted_for| voted_for != candidate)
            && term == state.term
        {
            return Err(ElectionError::AlreadyVoted);
        }
        transaction
            .execute(
                "UPDATE coordinator_elections SET term=?1, voted_for=?2 WHERE session_id=?3",
                params![
                    election_i64(term)?,
                    candidate.0.as_bytes(),
                    session_id.0.as_bytes()
                ],
            )
            .map_err(map_election_sql_error)?;
        state.term = term;
        state.voted_for = Some(candidate);
        transaction.commit().map_err(map_election_sql_error)?;
        Ok(state)
    }

    fn record_committed_plan(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        previous_epoch: u64,
        previous_hash: [u8; 32],
        next_epoch: u64,
        next_hash: [u8; 32],
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut connection = self
            .connection
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let transaction = connection.transaction().map_err(map_election_sql_error)?;
        let mut state =
            read_election_state(&transaction, session_id)?.ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash {
            return Err(ElectionError::MembershipChangeRequiresTransition);
        }
        if state.committed_epoch == next_epoch && state.committed_plan_hash == next_hash {
            transaction.commit().map_err(map_election_sql_error)?;
            return Ok(state);
        }
        if state.committed_epoch != previous_epoch
            || state.committed_plan_hash != previous_hash
            || next_epoch != previous_epoch.saturating_add(1)
        {
            return Err(ElectionError::CommittedPlanConflict);
        }
        transaction
            .execute(
                "UPDATE coordinator_elections
                 SET committed_epoch=?1, committed_plan_hash=?2 WHERE session_id=?3",
                params![
                    election_i64(next_epoch)?,
                    next_hash.as_slice(),
                    session_id.0.as_bytes()
                ],
            )
            .map_err(map_election_sql_error)?;
        state.committed_epoch = next_epoch;
        state.committed_plan_hash = next_hash;
        transaction.commit().map_err(map_election_sql_error)?;
        Ok(state)
    }
}

fn read_election_state(
    connection: &Connection,
    session_id: SessionId,
) -> Result<Option<ElectionPersistentState>, ElectionError> {
    let row = connection
        .query_row(
            "SELECT membership_hash, term, voted_for, committed_epoch, committed_plan_hash
             FROM coordinator_elections WHERE session_id=?1",
            [session_id.0.as_bytes()],
            |row| {
                Ok((
                    row.get::<_, Vec<u8>>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, Option<Vec<u8>>>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, Vec<u8>>(4)?,
                ))
            },
        )
        .optional()
        .map_err(map_election_sql_error)?;
    row.map(|(membership, term, voted_for, epoch, plan_hash)| {
        Ok(ElectionPersistentState {
            membership_hash: election_array(membership)?,
            term: election_u64(term)?,
            voted_for: voted_for
                .map(|bytes| {
                    uuid::Uuid::from_slice(&bytes)
                        .map(fabric_core::ParticipantId)
                        .map_err(|_| ElectionError::PersistenceUnavailable)
                })
                .transpose()?,
            committed_epoch: election_u64(epoch)?,
            committed_plan_hash: election_array(plan_hash)?,
        })
    })
    .transpose()
}

fn election_array<const N: usize>(bytes: Vec<u8>) -> Result<[u8; N], ElectionError> {
    bytes
        .try_into()
        .map_err(|_| ElectionError::PersistenceUnavailable)
}

fn election_i64(value: u64) -> Result<i64, ElectionError> {
    i64::try_from(value).map_err(|_| ElectionError::PersistenceUnavailable)
}

fn election_u64(value: i64) -> Result<u64, ElectionError> {
    u64::try_from(value).map_err(|_| ElectionError::PersistenceUnavailable)
}

fn map_election_sql_error(_error: rusqlite::Error) -> ElectionError {
    ElectionError::PersistenceUnavailable
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
    fn coordinator_recovery_skips_transient_sessions_and_fences_committed_snapshots() {
        let mut storage = Storage::open_in_memory().unwrap();
        let active = plan();
        let suspended = plan();
        let preparing = plan();
        storage
            .save_session(&active, SessionState::Active, None, 1)
            .unwrap();
        storage
            .save_session(&suspended, SessionState::Suspended, None, 2)
            .unwrap();
        storage
            .save_session(&preparing, SessionState::Preparing, None, 3)
            .unwrap();

        let checkpoints = storage.load_recoverable_session_checkpoints(10).unwrap();
        let recovered: std::collections::BTreeSet<_> = checkpoints
            .iter()
            .map(|checkpoint| checkpoint.plan.session_id)
            .collect();
        assert_eq!(recovered.len(), 2);
        assert!(recovered.contains(&active.session_id));
        assert!(recovered.contains(&suspended.session_id));
        assert!(!recovered.contains(&preparing.session_id));
        assert_eq!(
            storage.session_state(active.session_id).unwrap(),
            Some(StoredSessionState::SuspendedAfterRestart)
        );
        assert_eq!(
            storage.session_state(suspended.session_id).unwrap(),
            Some(StoredSessionState::SuspendedAfterRestart)
        );
        assert_eq!(
            storage.session_state(preparing.session_id).unwrap(),
            Some(StoredSessionState::Preparing)
        );
        assert!(
            checkpoints
                .iter()
                .all(|checkpoint| checkpoint.election.term == 0)
        );
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

    #[test]
    fn session_and_election_checkpoint_commit_rolls_back_and_recovers_repeatedly() {
        use std::sync::Arc;
        let path =
            std::env::temp_dir().join(format!("fabric-checkpoint-{}.sqlite", uuid::Uuid::new_v4()));
        let identity = fabric_identity::DeviceIdentity::from_seed([91; 32]);
        let mut previous = plan();
        previous.participants[0].device_id = identity.device_id();
        let mut storage = Storage::open(&path).unwrap();
        storage.initialize_session_checkpoint(&previous, 0).unwrap();
        let store = Arc::new(SqliteElectionStore::open(&path).unwrap());
        let mut election =
            fabric_session::CoordinatorElection::new(previous.clone(), previous.coordinator, store)
                .unwrap();
        election.begin_election(&identity).unwrap();
        let certificate = election.certificate().unwrap();
        let mut next = previous.clone();
        next.epoch += 1;
        assert!(matches!(
            storage.commit_session_checkpoint(
                &previous,
                &next,
                &certificate,
                1,
                FaultPoint::AfterSessionSnapshot
            ),
            Err(StorageError::InjectedFailure)
        ));
        drop(storage);
        for index in 0..3 {
            let mut reopened = Storage::open(&path).unwrap();
            reopened.startup_recovery(index + 2).unwrap();
            let checkpoint = reopened
                .load_session_checkpoint(previous.session_id)
                .unwrap()
                .unwrap();
            assert_eq!(checkpoint.plan, previous);
            assert_eq!(checkpoint.election.committed_epoch, previous.epoch);
            assert_eq!(checkpoint.election.term, 1);
            assert_eq!(checkpoint.election.voted_for, Some(previous.coordinator));
        }
        let mut storage = Storage::open(&path).unwrap();
        storage
            .commit_session_checkpoint(&previous, &next, &certificate, 6, FaultPoint::None)
            .unwrap();
        storage
            .commit_session_checkpoint(&previous, &next, &certificate, 7, FaultPoint::None)
            .unwrap();
        drop(storage);
        for index in 0..3 {
            let mut reopened = Storage::open(&path).unwrap();
            reopened.startup_recovery(index + 8).unwrap();
            let checkpoint = reopened
                .load_session_checkpoint(next.session_id)
                .unwrap()
                .unwrap();
            assert_eq!(checkpoint.plan, next);
            assert_eq!(
                checkpoint.election.committed_plan_hash,
                next.canonical_hash()
            );
            let certificate = reopened
                .load_election_certificate(next.session_id)
                .unwrap()
                .unwrap();
            certificate.verify(&previous).unwrap();
            assert_eq!(certificate.term, 1);
            assert_eq!(
                reopened.session_state(next.session_id).unwrap(),
                Some(StoredSessionState::SuspendedAfterRestart)
            );
        }
        // Simulate a legacy two-write crash: a newer Session snapshot alone is
        // never resumed/used to vote; consistent-checkpoint loading rejects it.
        let mut torn = next.clone();
        torn.epoch += 1;
        let mut storage = Storage::open(&path).unwrap();
        storage
            .save_session(&torn, SessionState::Active, None, 20)
            .unwrap();
        assert!(matches!(
            storage.load_session_checkpoint(torn.session_id),
            Err(StorageError::InconsistentSessionCheckpoint)
        ));
        drop(storage);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn sqlite_election_vote_survives_reopen_and_higher_term_fences_it() {
        let path =
            std::env::temp_dir().join(format!("fabric-election-{}.sqlite", uuid::Uuid::new_v4()));
        let session_id = SessionId::new();
        let candidate_a = fabric_core::ParticipantId::new();
        let candidate_b = fabric_core::ParticipantId::new();
        let initial = ElectionPersistentState {
            membership_hash: [4; 32],
            term: 0,
            voted_for: None,
            committed_epoch: 1,
            committed_plan_hash: [5; 32],
        };
        {
            let store = SqliteElectionStore::open(&path).unwrap();
            store.initialize(session_id, initial.clone()).unwrap();
            store
                .vote_once(
                    session_id,
                    initial.membership_hash,
                    initial.committed_epoch,
                    initial.committed_plan_hash,
                    1,
                    candidate_a,
                )
                .unwrap();
        }
        {
            let store = SqliteElectionStore::open(&path).unwrap();
            assert_eq!(
                store.load(session_id).unwrap().unwrap().voted_for,
                Some(candidate_a)
            );
            assert_eq!(
                store.vote_once(
                    session_id,
                    initial.membership_hash,
                    initial.committed_epoch,
                    initial.committed_plan_hash,
                    1,
                    candidate_b,
                ),
                Err(ElectionError::AlreadyVoted)
            );
            store
                .observe_term(session_id, initial.membership_hash, 2)
                .unwrap();
            assert_eq!(
                store
                    .vote_once(
                        session_id,
                        initial.membership_hash,
                        initial.committed_epoch,
                        initial.committed_plan_hash,
                        2,
                        candidate_b,
                    )
                    .unwrap()
                    .voted_for,
                Some(candidate_b)
            );
        }
        std::fs::remove_file(path).unwrap();
    }
}
