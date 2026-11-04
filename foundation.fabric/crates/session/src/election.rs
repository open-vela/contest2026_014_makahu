//! Durable, fixed-membership Coordinator election primitives.
//!
//! Callers own timers and message delivery. A vote is persisted before it is
//! signed, and a leader is authorized only by a verifiable strict majority.

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, Mutex},
};

use fabric_core::{DeviceId, ParticipantId, SessionId, SessionPlan};
use fabric_identity::{DeviceIdentity, IdentityError, verify_device_proof};
use sha2::{Digest, Sha256};
use thiserror::Error;
use uuid::Uuid;

const REQUEST_DOMAIN: &[u8] = b"foundation-fabric/coordinator-vote-request/v1";
const VOTE_DOMAIN: &[u8] = b"foundation-fabric/coordinator-vote/v1";
const CERTIFICATE_STORAGE_MAGIC: &[u8; 4] = b"FEC1";
const CERTIFICATE_STORAGE_HEADER_BYTES: usize = 120;
const CERTIFICATE_STORAGE_VOTE_BYTES: usize = 256;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ElectionPersistentState {
    pub membership_hash: [u8; 32],
    pub term: u64,
    pub voted_for: Option<ParticipantId>,
    pub committed_epoch: u64,
    pub committed_plan_hash: [u8; 32],
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum ElectionError {
    #[error("election persistence is unavailable")]
    PersistenceUnavailable,
    #[error("election state does not match this committed Session")]
    StateMismatch,
    #[error("election term is stale")]
    StaleTerm,
    #[error("election term overflow")]
    TermOverflow,
    #[error("this member already voted for another candidate in this term")]
    AlreadyVoted,
    #[error("candidate or voter is not a committed Session member")]
    NotMember,
    #[error("vote signature or identity binding is invalid: {0}")]
    Identity(#[from] IdentityError),
    #[error("vote request does not match the committed Session plan")]
    PlanMismatch,
    #[error("vote certificate does not contain a strict member majority")]
    NoMajority,
    #[error("certificate contains conflicting or duplicate votes")]
    ConflictingVotes,
    #[error("committed membership changes require an explicit membership transition")]
    MembershipChangeRequiresTransition,
    #[error("committed plan is stale or conflicts at the same epoch")]
    CommittedPlanConflict,
    #[error("stored election certificate encoding is invalid")]
    InvalidCertificateEncoding,
}

/// Implementations must make each operation atomic and durable before return.
pub trait ElectionStore: Send + Sync {
    fn initialize(
        &self,
        session_id: SessionId,
        initial: ElectionPersistentState,
    ) -> Result<ElectionPersistentState, ElectionError>;

    fn load(&self, session_id: SessionId)
    -> Result<Option<ElectionPersistentState>, ElectionError>;

    fn observe_term(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        term: u64,
    ) -> Result<ElectionPersistentState, ElectionError>;

    fn vote_once(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        committed_epoch: u64,
        committed_plan_hash: [u8; 32],
        term: u64,
        candidate: ParticipantId,
    ) -> Result<ElectionPersistentState, ElectionError>;

    fn record_committed_plan(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        previous_epoch: u64,
        previous_hash: [u8; 32],
        next_epoch: u64,
        next_hash: [u8; 32],
    ) -> Result<ElectionPersistentState, ElectionError>;
}

#[derive(Default)]
pub struct MemoryElectionStore {
    states: Mutex<BTreeMap<SessionId, ElectionPersistentState>>,
}

impl ElectionStore for MemoryElectionStore {
    fn initialize(
        &self,
        session_id: SessionId,
        initial: ElectionPersistentState,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut states = self
            .states
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        if let Some(existing) = states.get(&session_id) {
            check_state_identity(existing, &initial)?;
            return Ok(existing.clone());
        }
        states.insert(session_id, initial.clone());
        Ok(initial)
    }

    fn load(
        &self,
        session_id: SessionId,
    ) -> Result<Option<ElectionPersistentState>, ElectionError> {
        Ok(self
            .states
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?
            .get(&session_id)
            .cloned())
    }

    fn observe_term(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        term: u64,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut states = self
            .states
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let state = states
            .get_mut(&session_id)
            .ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash {
            return Err(ElectionError::StateMismatch);
        }
        if term < state.term {
            return Err(ElectionError::StaleTerm);
        }
        if term > state.term {
            state.term = term;
            state.voted_for = None;
        }
        Ok(state.clone())
    }

    fn vote_once(
        &self,
        session_id: SessionId,
        membership_hash: [u8; 32],
        committed_epoch: u64,
        committed_plan_hash: [u8; 32],
        term: u64,
        candidate: ParticipantId,
    ) -> Result<ElectionPersistentState, ElectionError> {
        let mut states = self
            .states
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let state = states
            .get_mut(&session_id)
            .ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash
            || state.committed_epoch != committed_epoch
            || state.committed_plan_hash != committed_plan_hash
        {
            return Err(ElectionError::StateMismatch);
        }
        if term < state.term {
            return Err(ElectionError::StaleTerm);
        }
        if term > state.term {
            state.term = term;
            state.voted_for = None;
        }
        if state
            .voted_for
            .is_some_and(|voted_for| voted_for != candidate)
        {
            return Err(ElectionError::AlreadyVoted);
        }
        state.voted_for = Some(candidate);
        Ok(state.clone())
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
        let mut states = self
            .states
            .lock()
            .map_err(|_| ElectionError::PersistenceUnavailable)?;
        let state = states
            .get_mut(&session_id)
            .ok_or(ElectionError::StateMismatch)?;
        if state.membership_hash != membership_hash {
            return Err(ElectionError::MembershipChangeRequiresTransition);
        }
        if state.committed_epoch == next_epoch && state.committed_plan_hash == next_hash {
            return Ok(state.clone());
        }
        if state.committed_epoch != previous_epoch
            || state.committed_plan_hash != previous_hash
            || next_epoch != previous_epoch.saturating_add(1)
        {
            return Err(ElectionError::CommittedPlanConflict);
        }
        state.committed_epoch = next_epoch;
        state.committed_plan_hash = next_hash;
        Ok(state.clone())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct VoteRequest {
    pub session_id: SessionId,
    pub term: u64,
    pub membership_hash: [u8; 32],
    pub committed_epoch: u64,
    pub committed_plan_hash: [u8; 32],
    pub candidate: ParticipantId,
    pub candidate_device: DeviceId,
    pub candidate_public_key: [u8; 32],
    pub signature: [u8; 64],
}

impl VoteRequest {
    fn signing_hash(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        hash.update(REQUEST_DOMAIN);
        hash.update(self.session_id.0.as_bytes());
        hash.update(self.term.to_be_bytes());
        hash.update(self.membership_hash);
        hash.update(self.committed_epoch.to_be_bytes());
        hash.update(self.committed_plan_hash);
        hash.update(self.candidate.0.as_bytes());
        hash.update(self.candidate_device.0);
        hash.update(self.candidate_public_key);
        hash.finalize().into()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ElectionVote {
    pub session_id: SessionId,
    pub term: u64,
    pub membership_hash: [u8; 32],
    pub committed_epoch: u64,
    pub committed_plan_hash: [u8; 32],
    pub candidate: ParticipantId,
    pub voter: ParticipantId,
    pub voter_device: DeviceId,
    pub voter_public_key: [u8; 32],
    pub signature: [u8; 64],
}

impl ElectionVote {
    fn signing_hash(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        hash.update(VOTE_DOMAIN);
        hash.update(self.session_id.0.as_bytes());
        hash.update(self.term.to_be_bytes());
        hash.update(self.membership_hash);
        hash.update(self.committed_epoch.to_be_bytes());
        hash.update(self.committed_plan_hash);
        hash.update(self.candidate.0.as_bytes());
        hash.update(self.voter.0.as_bytes());
        hash.update(self.voter_device.0);
        hash.update(self.voter_public_key);
        hash.finalize().into()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ElectionCertificate {
    pub session_id: SessionId,
    pub term: u64,
    pub membership_hash: [u8; 32],
    pub committed_epoch: u64,
    pub committed_plan_hash: [u8; 32],
    pub candidate: ParticipantId,
    pub votes: Vec<ElectionVote>,
}

impl ElectionCertificate {
    /// Encode the complete signed majority proof in a stable, versioned binary
    /// form for atomic storage alongside the Session checkpoint it authorizes.
    pub fn encode_for_storage(&self) -> Result<Vec<u8>, ElectionError> {
        let vote_count = u32::try_from(self.votes.len())
            .map_err(|_| ElectionError::InvalidCertificateEncoding)?;
        let mut encoded = Vec::with_capacity(
            CERTIFICATE_STORAGE_HEADER_BYTES.saturating_add(
                self.votes
                    .len()
                    .saturating_mul(CERTIFICATE_STORAGE_VOTE_BYTES),
            ),
        );
        encoded.extend_from_slice(CERTIFICATE_STORAGE_MAGIC);
        encoded.extend_from_slice(self.session_id.0.as_bytes());
        encoded.extend_from_slice(&self.term.to_be_bytes());
        encoded.extend_from_slice(&self.membership_hash);
        encoded.extend_from_slice(&self.committed_epoch.to_be_bytes());
        encoded.extend_from_slice(&self.committed_plan_hash);
        encoded.extend_from_slice(self.candidate.0.as_bytes());
        encoded.extend_from_slice(&vote_count.to_be_bytes());
        for vote in &self.votes {
            encoded.extend_from_slice(vote.session_id.0.as_bytes());
            encoded.extend_from_slice(&vote.term.to_be_bytes());
            encoded.extend_from_slice(&vote.membership_hash);
            encoded.extend_from_slice(&vote.committed_epoch.to_be_bytes());
            encoded.extend_from_slice(&vote.committed_plan_hash);
            encoded.extend_from_slice(vote.candidate.0.as_bytes());
            encoded.extend_from_slice(vote.voter.0.as_bytes());
            encoded.extend_from_slice(&vote.voter_device.0);
            encoded.extend_from_slice(&vote.voter_public_key);
            encoded.extend_from_slice(&vote.signature);
        }
        Ok(encoded)
    }

    /// Decode a proof read from durable storage. Call `verify` against the
    /// corresponding committed plan before treating it as authorization.
    pub fn decode_from_storage(encoded: &[u8]) -> Result<Self, ElectionError> {
        if encoded.len() < CERTIFICATE_STORAGE_HEADER_BYTES {
            return Err(ElectionError::InvalidCertificateEncoding);
        }
        let mut cursor = CertificateStorageCursor::new(encoded);
        if cursor.take::<4>()? != *CERTIFICATE_STORAGE_MAGIC {
            return Err(ElectionError::InvalidCertificateEncoding);
        }
        let session_id = SessionId(Uuid::from_bytes(cursor.take::<16>()?));
        let term = cursor.u64()?;
        let membership_hash = cursor.take::<32>()?;
        let committed_epoch = cursor.u64()?;
        let committed_plan_hash = cursor.take::<32>()?;
        let candidate = ParticipantId(Uuid::from_bytes(cursor.take::<16>()?));
        let vote_count = usize::try_from(cursor.u32()?)
            .map_err(|_| ElectionError::InvalidCertificateEncoding)?;
        let expected_length = CERTIFICATE_STORAGE_HEADER_BYTES
            .checked_add(
                vote_count
                    .checked_mul(CERTIFICATE_STORAGE_VOTE_BYTES)
                    .ok_or(ElectionError::InvalidCertificateEncoding)?,
            )
            .ok_or(ElectionError::InvalidCertificateEncoding)?;
        if encoded.len() != expected_length {
            return Err(ElectionError::InvalidCertificateEncoding);
        }
        let mut votes = Vec::with_capacity(vote_count);
        for _ in 0..vote_count {
            votes.push(ElectionVote {
                session_id: SessionId(Uuid::from_bytes(cursor.take::<16>()?)),
                term: cursor.u64()?,
                membership_hash: cursor.take::<32>()?,
                committed_epoch: cursor.u64()?,
                committed_plan_hash: cursor.take::<32>()?,
                candidate: ParticipantId(Uuid::from_bytes(cursor.take::<16>()?)),
                voter: ParticipantId(Uuid::from_bytes(cursor.take::<16>()?)),
                voter_device: DeviceId(cursor.take::<32>()?),
                voter_public_key: cursor.take::<32>()?,
                signature: cursor.take::<64>()?,
            });
        }
        if !cursor.is_at_end() {
            return Err(ElectionError::InvalidCertificateEncoding);
        }
        Ok(Self {
            session_id,
            term,
            membership_hash,
            committed_epoch,
            committed_plan_hash,
            candidate,
            votes,
        })
    }

    pub fn verify(&self, committed: &SessionPlan) -> Result<(), ElectionError> {
        committed
            .validate()
            .map_err(|_| ElectionError::PlanMismatch)?;
        let expected_membership = membership_config_hash(committed);
        if self.session_id != committed.session_id
            || self.membership_hash != expected_membership
            || self.committed_epoch != committed.epoch
            || self.committed_plan_hash != committed.canonical_hash()
            || self.term == 0
        {
            return Err(ElectionError::PlanMismatch);
        }
        let members: BTreeMap<_, _> = committed
            .participants
            .iter()
            .map(|member| (member.id, member.device_id))
            .collect();
        if !members.contains_key(&self.candidate) {
            return Err(ElectionError::NotMember);
        }
        let mut voters = BTreeSet::<DeviceId>::new();
        for vote in &self.votes {
            if vote.session_id != self.session_id
                || vote.term != self.term
                || vote.membership_hash != self.membership_hash
                || vote.committed_epoch != self.committed_epoch
                || vote.committed_plan_hash != self.committed_plan_hash
                || vote.candidate != self.candidate
                || members.get(&vote.voter) != Some(&vote.voter_device)
                || canonical_member(&members, vote.voter_device) != Some(vote.voter)
            {
                return Err(ElectionError::ConflictingVotes);
            }
            verify_device_proof(
                vote.voter_device,
                vote.voter_public_key,
                &vote.signing_hash(),
                &vote.signature,
            )?;
            if !voters.insert(vote.voter_device) {
                return Err(ElectionError::ConflictingVotes);
            }
        }
        let member_devices: BTreeSet<_> = members.values().copied().collect();
        if voters.len() <= member_devices.len() / 2 {
            return Err(ElectionError::NoMajority);
        }
        Ok(())
    }
}

struct CertificateStorageCursor<'a> {
    encoded: &'a [u8],
    offset: usize,
}

impl<'a> CertificateStorageCursor<'a> {
    const fn new(encoded: &'a [u8]) -> Self {
        Self { encoded, offset: 0 }
    }

    fn take<const N: usize>(&mut self) -> Result<[u8; N], ElectionError> {
        let end = self
            .offset
            .checked_add(N)
            .ok_or(ElectionError::InvalidCertificateEncoding)?;
        let value = self
            .encoded
            .get(self.offset..end)
            .ok_or(ElectionError::InvalidCertificateEncoding)?;
        self.offset = end;
        value
            .try_into()
            .map_err(|_| ElectionError::InvalidCertificateEncoding)
    }

    fn u32(&mut self) -> Result<u32, ElectionError> {
        Ok(u32::from_be_bytes(self.take()?))
    }

    fn u64(&mut self) -> Result<u64, ElectionError> {
        Ok(u64::from_be_bytes(self.take()?))
    }

    const fn is_at_end(&self) -> bool {
        self.offset == self.encoded.len()
    }
}

pub struct CoordinatorElection {
    plan: SessionPlan,
    local_participant: ParticipantId,
    membership_hash: [u8; 32],
    store: Arc<dyn ElectionStore>,
    active_request: Option<VoteRequest>,
    votes: BTreeMap<DeviceId, ElectionVote>,
}

impl CoordinatorElection {
    pub fn new(
        plan: SessionPlan,
        local_participant: ParticipantId,
        store: Arc<dyn ElectionStore>,
    ) -> Result<Self, ElectionError> {
        plan.validate().map_err(|_| ElectionError::PlanMismatch)?;
        let membership_hash = membership_config_hash(&plan);
        if !plan
            .participants
            .iter()
            .any(|member| member.id == local_participant)
        {
            return Err(ElectionError::NotMember);
        }
        store.initialize(
            plan.session_id,
            ElectionPersistentState {
                membership_hash,
                term: 0,
                voted_for: None,
                committed_epoch: plan.epoch,
                committed_plan_hash: plan.canonical_hash(),
            },
        )?;
        Ok(Self {
            plan,
            local_participant,
            membership_hash,
            store,
            active_request: None,
            votes: BTreeMap::new(),
        })
    }

    pub fn begin_election(
        &mut self,
        identity: &DeviceIdentity,
    ) -> Result<(VoteRequest, ElectionVote), ElectionError> {
        let member_device = self.member_device(self.local_participant)?;
        if identity.device_id() != member_device {
            return Err(ElectionError::NotMember);
        }
        let previous = self
            .store
            .load(self.plan.session_id)?
            .ok_or(ElectionError::StateMismatch)?;
        let term = previous
            .term
            .checked_add(1)
            .ok_or(ElectionError::TermOverflow)?;
        let mut request = VoteRequest {
            session_id: self.plan.session_id,
            term,
            membership_hash: self.membership_hash,
            committed_epoch: self.plan.epoch,
            committed_plan_hash: self.plan.canonical_hash(),
            candidate: self.local_participant,
            candidate_device: identity.device_id(),
            candidate_public_key: identity.public_key(),
            signature: [0; 64],
        };
        request.signature = identity.sign_device_proof(&request.signing_hash());
        let local_voter = self.canonical_member(identity.device_id())?;
        let vote =
            self.persist_and_sign_vote(term, self.local_participant, local_voter, identity)?;
        self.active_request = Some(request.clone());
        self.votes.clear();
        self.votes.insert(vote.voter_device, vote.clone());
        Ok((request, vote))
    }

    /// Read the durable election term and vote state associated with this
    /// committed plan. Runtime drivers use this to fence lower-term control as
    /// soon as a higher term has been observed, even before a certificate.
    pub fn persistent_state(&self) -> Result<ElectionPersistentState, ElectionError> {
        self.store
            .load(self.plan.session_id)?
            .ok_or(ElectionError::StateMismatch)
    }

    /// Whether this election instance currently has an outstanding request for
    /// `candidate` at `term`. Runtime drivers use this to accept votes while a
    /// non-incumbent is campaigning; the committed coordinator remains the
    /// old one until a majority certificate is installed.
    #[must_use]
    pub fn is_active_candidate(&self, term: u64, candidate: ParticipantId) -> bool {
        self.active_request
            .as_ref()
            .is_some_and(|request| request.term == term && request.candidate == candidate)
    }

    pub fn cast_vote(
        &mut self,
        request: &VoteRequest,
        authenticated_candidate: DeviceId,
        identity: &DeviceIdentity,
    ) -> Result<ElectionVote, ElectionError> {
        self.verify_request(request, authenticated_candidate)?;
        if identity.device_id() != self.member_device(self.local_participant)? {
            return Err(ElectionError::NotMember);
        }
        let local_voter = self.canonical_member(identity.device_id())?;
        let vote =
            self.persist_and_sign_vote(request.term, request.candidate, local_voter, identity)?;
        if self
            .active_request
            .as_ref()
            .is_some_and(|active| active.term < request.term)
        {
            self.active_request = None;
            self.votes.clear();
        }
        Ok(vote)
    }

    /// Validate a candidate request without changing persistent state. A
    /// runtime can fence its current coordinator after authenticating a higher
    /// term even when the subsequent durable vote write is unavailable.
    pub fn validate_request(
        &self,
        request: &VoteRequest,
        authenticated_candidate: DeviceId,
    ) -> Result<(), ElectionError> {
        self.verify_request(request, authenticated_candidate)
    }

    pub fn add_vote(&mut self, vote: ElectionVote) -> Result<bool, ElectionError> {
        let request = self
            .active_request
            .as_ref()
            .ok_or(ElectionError::StaleTerm)?;
        let persisted = self
            .store
            .load(self.plan.session_id)?
            .ok_or(ElectionError::StateMismatch)?;
        if persisted.term != request.term || persisted.voted_for != Some(request.candidate) {
            return Err(ElectionError::StaleTerm);
        }
        self.verify_vote(&vote, request)?;
        if let Some(existing) = self.votes.get(&vote.voter_device) {
            if existing != &vote {
                return Err(ElectionError::ConflictingVotes);
            }
            return Ok(self.has_majority());
        }
        self.votes.insert(vote.voter_device, vote);
        Ok(self.has_majority())
    }

    pub fn certificate(&self) -> Result<ElectionCertificate, ElectionError> {
        if !self.has_majority() {
            return Err(ElectionError::NoMajority);
        }
        let request = self
            .active_request
            .as_ref()
            .ok_or(ElectionError::PlanMismatch)?;
        let persisted = self
            .store
            .load(self.plan.session_id)?
            .ok_or(ElectionError::StateMismatch)?;
        if persisted.term != request.term || persisted.voted_for != Some(request.candidate) {
            return Err(ElectionError::StaleTerm);
        }
        let certificate = ElectionCertificate {
            session_id: request.session_id,
            term: request.term,
            membership_hash: request.membership_hash,
            committed_epoch: request.committed_epoch,
            committed_plan_hash: request.committed_plan_hash,
            candidate: request.candidate,
            votes: self.votes.values().cloned().collect(),
        };
        certificate.verify(&self.plan)?;
        Ok(certificate)
    }

    /// Verify a majority certificate and durably observe its term before the
    /// caller installs it into `SessionMachine`.
    pub fn authorize_certificate(
        &mut self,
        certificate: &ElectionCertificate,
    ) -> Result<(), ElectionError> {
        certificate.verify(&self.plan)?;
        self.store
            .observe_term(self.plan.session_id, self.membership_hash, certificate.term)?;
        self.active_request = None;
        self.votes.clear();
        Ok(())
    }

    /// Persist only after SessionMachine has accepted the corresponding Commit.
    pub fn record_committed_plan(&mut self, next: SessionPlan) -> Result<(), ElectionError> {
        next.validate().map_err(|_| ElectionError::PlanMismatch)?;
        if membership_config_hash(&next) != self.membership_hash {
            return Err(ElectionError::MembershipChangeRequiresTransition);
        }
        let current = self
            .store
            .load(self.plan.session_id)?
            .ok_or(ElectionError::StateMismatch)?;
        if current.committed_epoch == next.epoch
            && current.committed_plan_hash == next.canonical_hash()
        {
            self.plan = next;
            self.active_request = None;
            self.votes.clear();
            return Ok(());
        }
        if current.committed_epoch != self.plan.epoch
            || current.committed_plan_hash != self.plan.canonical_hash()
        {
            return Err(ElectionError::CommittedPlanConflict);
        }
        self.store.record_committed_plan(
            self.plan.session_id,
            self.membership_hash,
            current.committed_epoch,
            current.committed_plan_hash,
            next.epoch,
            next.canonical_hash(),
        )?;
        self.plan = next;
        self.active_request = None;
        self.votes.clear();
        Ok(())
    }

    fn verify_request(
        &self,
        request: &VoteRequest,
        authenticated_candidate: DeviceId,
    ) -> Result<(), ElectionError> {
        if request.session_id != self.plan.session_id
            || request.membership_hash != self.membership_hash
            || request.committed_epoch != self.plan.epoch
            || request.committed_plan_hash != self.plan.canonical_hash()
            || request.term == 0
            || self.member_device(request.candidate)? != request.candidate_device
            || request.candidate_device != authenticated_candidate
        {
            return Err(ElectionError::PlanMismatch);
        }
        verify_device_proof(
            request.candidate_device,
            request.candidate_public_key,
            &request.signing_hash(),
            &request.signature,
        )?;
        Ok(())
    }

    fn persist_and_sign_vote(
        &self,
        term: u64,
        candidate: ParticipantId,
        voter: ParticipantId,
        identity: &DeviceIdentity,
    ) -> Result<ElectionVote, ElectionError> {
        let voter_device = self.member_device(voter)?;
        if identity.device_id() != voter_device {
            return Err(ElectionError::NotMember);
        }
        // This durable single-vote write completes before any signature is returned.
        self.store.vote_once(
            self.plan.session_id,
            self.membership_hash,
            self.plan.epoch,
            self.plan.canonical_hash(),
            term,
            candidate,
        )?;
        let mut vote = ElectionVote {
            session_id: self.plan.session_id,
            term,
            membership_hash: self.membership_hash,
            committed_epoch: self.plan.epoch,
            committed_plan_hash: self.plan.canonical_hash(),
            candidate,
            voter,
            voter_device,
            voter_public_key: identity.public_key(),
            signature: [0; 64],
        };
        vote.signature = identity.sign_device_proof(&vote.signing_hash());
        Ok(vote)
    }

    fn verify_vote(&self, vote: &ElectionVote, request: &VoteRequest) -> Result<(), ElectionError> {
        if vote.term != request.term {
            return Err(ElectionError::StaleTerm);
        }
        if vote.session_id != request.session_id
            || vote.membership_hash != request.membership_hash
            || vote.committed_epoch != request.committed_epoch
            || vote.committed_plan_hash != request.committed_plan_hash
            || vote.candidate != request.candidate
            || self.member_device(vote.voter)? != vote.voter_device
        {
            return Err(ElectionError::ConflictingVotes);
        }
        verify_device_proof(
            vote.voter_device,
            vote.voter_public_key,
            &vote.signing_hash(),
            &vote.signature,
        )?;
        Ok(())
    }

    fn member_device(&self, participant: ParticipantId) -> Result<DeviceId, ElectionError> {
        self.plan
            .participants
            .iter()
            .find(|member| member.id == participant)
            .map(|member| member.device_id)
            .ok_or(ElectionError::NotMember)
    }

    fn canonical_member(&self, device: DeviceId) -> Result<ParticipantId, ElectionError> {
        self.plan
            .participants
            .iter()
            .filter(|member| member.device_id == device)
            .map(|member| member.id)
            .min()
            .ok_or(ElectionError::NotMember)
    }

    fn has_majority(&self) -> bool {
        let members: BTreeSet<_> = self
            .plan
            .participants
            .iter()
            .map(|member| member.device_id)
            .collect();
        self.votes.len() > members.len() / 2
    }
}

fn canonical_member(
    members: &BTreeMap<ParticipantId, DeviceId>,
    device: DeviceId,
) -> Option<ParticipantId> {
    members
        .iter()
        .filter_map(|(participant, member_device)| {
            (*member_device == device).then_some(*participant)
        })
        .min()
}

fn check_state_identity(
    existing: &ElectionPersistentState,
    requested: &ElectionPersistentState,
) -> Result<(), ElectionError> {
    if existing.membership_hash != requested.membership_hash
        || existing.committed_epoch != requested.committed_epoch
        || existing.committed_plan_hash != requested.committed_plan_hash
    {
        return Err(ElectionError::StateMismatch);
    }
    Ok(())
}

#[must_use]
pub fn membership_config_hash(plan: &SessionPlan) -> [u8; 32] {
    let mut members: Vec<_> = plan
        .participants
        .iter()
        .map(|member| (member.id, member.device_id))
        .collect();
    members.sort_unstable();
    let mut hash = Sha256::new();
    hash.update(b"foundation-fabric/session-membership/v1");
    hash.update(plan.session_id.0.as_bytes());
    hash.update(
        u64::try_from(members.len())
            .unwrap_or(u64::MAX)
            .to_be_bytes(),
    );
    for (participant, device) in members {
        hash.update(participant.0.as_bytes());
        hash.update(device.0);
    }
    hash.finalize().into()
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use fabric_core::{
        AbilityContractRef, AbilityInstanceId, AbilityKey, AbilityName, AppPrincipal, Namespace,
        OsSubject, Participant, Platform, PolicySnapshotId, RoleId, SessionExtensions,
    };

    use super::*;
    use crate::MemoryElectionStore;

    fn plan(identities: &[DeviceIdentity]) -> (SessionPlan, Vec<ParticipantId>) {
        let ids: Vec<_> = identities.iter().map(|_| ParticipantId::new()).collect();
        let participants = identities
            .iter()
            .zip(&ids)
            .enumerate()
            .map(|(index, (identity, participant))| Participant {
                id: *participant,
                device_id: identity.device_id(),
                app_principal: AppPrincipal {
                    platform: Platform::Test,
                    stable_app_id: format!("test.app.{index}"),
                    publisher_id: None,
                    signing_digest: None,
                    os_subject: OsSubject(format!("test:{index}")),
                },
                ability_instance: AbilityInstanceId::new(),
                role: RoleId::new("worker").unwrap(),
                port_bindings: vec![],
            })
            .collect();
        (
            SessionPlan {
                session_id: SessionId::new(),
                contract: AbilityContractRef {
                    key: AbilityKey {
                        namespace: Namespace::new("com.example").unwrap(),
                        name: AbilityName::new("work").unwrap(),
                        major: 1,
                    },
                    protocol_hash: [7; 32],
                },
                epoch: 1,
                coordinator: ids[0],
                participants,
                channels: vec![],
                extensions: SessionExtensions::default(),
                policy_snapshot: PolicySnapshotId::new(),
            },
            ids,
        )
    }

    fn stores(count: usize) -> Vec<Arc<dyn ElectionStore>> {
        (0..count)
            .map(|_| Arc::new(MemoryElectionStore::default()) as Arc<dyn ElectionStore>)
            .collect()
    }

    #[test]
    fn election_requires_signed_strict_majority_and_binds_committed_plan() {
        let identities = vec![
            DeviceIdentity::from_seed([1; 32]),
            DeviceIdentity::from_seed([2; 32]),
            DeviceIdentity::from_seed([3; 32]),
        ];
        let (plan, participants) = plan(&identities);
        let stores = stores(3);
        let mut candidate =
            CoordinatorElection::new(plan.clone(), participants[1], Arc::clone(&stores[1]))
                .unwrap();
        let (request, own_vote) = candidate.begin_election(&identities[1]).unwrap();
        assert_eq!(own_vote.voter, participants[1]);
        assert!(candidate.is_active_candidate(request.term, participants[1]));
        assert!(!candidate.is_active_candidate(request.term, participants[0]));

        let mut voter =
            CoordinatorElection::new(plan.clone(), participants[0], Arc::clone(&stores[0]))
                .unwrap();
        voter
            .validate_request(&request, identities[1].device_id())
            .unwrap();
        let before_vote = voter.persistent_state().unwrap();
        assert_eq!(before_vote.term, 0);
        assert_eq!(before_vote.voted_for, None);
        let vote = voter
            .cast_vote(&request, identities[1].device_id(), &identities[0])
            .unwrap();
        assert!(candidate.add_vote(vote).unwrap());
        let certificate = candidate.certificate().unwrap();
        certificate.verify(&plan).unwrap();
        let mut observer =
            CoordinatorElection::new(plan.clone(), participants[2], Arc::clone(&stores[2]))
                .unwrap();
        observer.authorize_certificate(&certificate).unwrap();
        candidate.authorize_certificate(&certificate).unwrap();
        assert!(!candidate.is_active_candidate(request.term, participants[1]));
        let observed = observer.store.load(plan.session_id).unwrap().unwrap();
        assert_eq!(observed.term, certificate.term);
        assert_eq!(observed.voted_for, None);

        let mut conflicting_plan = plan;
        conflicting_plan.epoch += 1;
        assert_eq!(
            certificate.verify(&conflicting_plan),
            Err(ElectionError::PlanMismatch)
        );
    }

    #[test]
    fn multiple_participants_on_one_device_count_as_one_voter() {
        let identities = vec![
            DeviceIdentity::from_seed([4; 32]),
            DeviceIdentity::from_seed([5; 32]),
            DeviceIdentity::from_seed([6; 32]),
        ];
        let (mut plan, participants) = plan(&identities);
        let mut same_device_participant = plan.participants[0].clone();
        same_device_participant.id = ParticipantId::new();
        same_device_participant.ability_instance = AbilityInstanceId::new();
        same_device_participant.app_principal.stable_app_id = "test.app.duplicate".into();
        same_device_participant.app_principal.os_subject = OsSubject("test:duplicate".into());
        same_device_participant.role = RoleId::new("secondary").unwrap();
        let duplicate_id = same_device_participant.id;
        plan.participants.push(same_device_participant);

        let all_stores = stores(3);
        let mut candidate =
            CoordinatorElection::new(plan.clone(), participants[1], Arc::clone(&all_stores[1]))
                .unwrap();
        let (request, candidate_vote) = candidate.begin_election(&identities[1]).unwrap();
        let mut voter =
            CoordinatorElection::new(plan.clone(), participants[0], Arc::clone(&all_stores[0]))
                .unwrap();
        let vote = voter
            .cast_vote(&request, identities[1].device_id(), &identities[0])
            .unwrap();
        assert_eq!(vote.voter, participants[0].min(duplicate_id));
        assert!(candidate.add_vote(vote.clone()).unwrap());
        candidate.certificate().unwrap().verify(&plan).unwrap();

        let mut duplicate_device_vote = vote;
        duplicate_device_vote.voter = participants[0].max(duplicate_id);
        let forged_certificate = ElectionCertificate {
            session_id: request.session_id,
            term: request.term,
            membership_hash: request.membership_hash,
            committed_epoch: request.committed_epoch,
            committed_plan_hash: request.committed_plan_hash,
            candidate: request.candidate,
            votes: vec![candidate_vote, duplicate_device_vote],
        };
        assert_eq!(
            forged_certificate.verify(&plan),
            Err(ElectionError::ConflictingVotes)
        );
    }

    #[test]
    fn persisted_vote_rejects_competing_candidate_and_survives_reopen() {
        let identities = vec![
            DeviceIdentity::from_seed([11; 32]),
            DeviceIdentity::from_seed([12; 32]),
            DeviceIdentity::from_seed([13; 32]),
        ];
        let (plan, participants) = plan(&identities);
        let stores = stores(3);
        let mut first =
            CoordinatorElection::new(plan.clone(), participants[0], Arc::clone(&stores[0]))
                .unwrap();
        let (request_a, _) = first.begin_election(&identities[0]).unwrap();
        let mut second =
            CoordinatorElection::new(plan.clone(), participants[1], Arc::clone(&stores[1]))
                .unwrap();
        let (request_b, _) = second.begin_election(&identities[1]).unwrap();
        let mut voter =
            CoordinatorElection::new(plan.clone(), participants[2], Arc::clone(&stores[2]))
                .unwrap();
        voter
            .cast_vote(&request_a, identities[0].device_id(), &identities[2])
            .unwrap();
        assert_eq!(
            voter.cast_vote(&request_b, identities[1].device_id(), &identities[2]),
            Err(ElectionError::AlreadyVoted)
        );

        drop(voter);
        let reopened =
            CoordinatorElection::new(plan, participants[2], Arc::clone(&stores[2])).unwrap();
        assert_eq!(
            reopened
                .store
                .load(request_a.session_id)
                .unwrap()
                .unwrap()
                .voted_for,
            Some(participants[0])
        );
    }

    #[test]
    fn higher_term_fences_old_votes_and_membership_is_fixed() {
        let identities = vec![
            DeviceIdentity::from_seed([21; 32]),
            DeviceIdentity::from_seed([22; 32]),
            DeviceIdentity::from_seed([23; 32]),
        ];
        let (plan, participants) = plan(&identities);
        let store = Arc::new(MemoryElectionStore::default());
        let mut election =
            CoordinatorElection::new(plan.clone(), participants[0], store.clone()).unwrap();
        let (request, _) = election.begin_election(&identities[0]).unwrap();
        let mut higher_request = VoteRequest {
            session_id: request.session_id,
            term: request.term + 1,
            membership_hash: request.membership_hash,
            committed_epoch: request.committed_epoch,
            committed_plan_hash: request.committed_plan_hash,
            candidate: participants[1],
            candidate_device: identities[1].device_id(),
            candidate_public_key: identities[1].public_key(),
            signature: [0; 64],
        };
        higher_request.signature = identities[1].sign_device_proof(&higher_request.signing_hash());
        election
            .cast_vote(&higher_request, identities[1].device_id(), &identities[0])
            .unwrap();
        assert_eq!(
            store.load(request.session_id).unwrap().unwrap().term,
            higher_request.term
        );
        assert_eq!(
            election.cast_vote(&request, identities[0].device_id(), &identities[0]),
            Err(ElectionError::StaleTerm)
        );

        let mut changed = plan;
        changed.participants.pop();
        assert_eq!(
            election.record_committed_plan(changed),
            Err(ElectionError::MembershipChangeRequiresTransition)
        );
    }
}
