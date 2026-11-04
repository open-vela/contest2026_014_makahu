//! Pure Session control state machine. It emits commands and performs no I/O.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use fabric_core::{
    DeviceId, OperationId, ParticipantId, SessionPlan, SessionState, ValidationError,
};
use sha2::{Digest, Sha256};
use thiserror::Error;

mod election;
pub use election::{
    CoordinatorElection, ElectionCertificate, ElectionError, ElectionPersistentState,
    ElectionStore, ElectionVote, MemoryElectionStore, VoteRequest, membership_config_hash,
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SessionEvent {
    BeginNegotiation {
        operation_id: OperationId,
    },
    Accept {
        operation_id: OperationId,
        participant: ParticipantId,
    },
    CounterOffer {
        operation_id: OperationId,
        plan: SessionPlan,
    },
    Reject {
        operation_id: OperationId,
        participant: ParticipantId,
        reason: String,
    },
    Prepare {
        operation_id: OperationId,
        term: u64,
        plan: SessionPlan,
    },
    Ready {
        operation_id: OperationId,
        participant: ParticipantId,
        term: u64,
        plan_hash: [u8; 32],
    },
    Commit {
        operation_id: OperationId,
        term: u64,
        plan_hash: [u8; 32],
    },
    Suspend {
        operation_id: OperationId,
    },
    Resume {
        operation_id: OperationId,
    },
    Reconfigure {
        operation_id: OperationId,
        term: u64,
        plan: SessionPlan,
    },
    CoordinatorElected {
        operation_id: OperationId,
        certificate: ElectionCertificate,
    },
    Close {
        operation_id: OperationId,
        reason: String,
    },
    Closed {
        operation_id: OperationId,
    },
    Fail {
        operation_id: OperationId,
        reason: String,
    },
}

impl SessionEvent {
    const fn operation_id(&self) -> OperationId {
        match self {
            Self::BeginNegotiation { operation_id }
            | Self::Accept { operation_id, .. }
            | Self::CounterOffer { operation_id, .. }
            | Self::Reject { operation_id, .. }
            | Self::Prepare { operation_id, .. }
            | Self::Ready { operation_id, .. }
            | Self::Commit { operation_id, .. }
            | Self::Suspend { operation_id }
            | Self::Resume { operation_id }
            | Self::Reconfigure { operation_id, .. }
            | Self::CoordinatorElected { operation_id, .. }
            | Self::Close { operation_id, .. }
            | Self::Closed { operation_id }
            | Self::Fail { operation_id, .. } => *operation_id,
        }
    }

    fn fingerprint(&self) -> [u8; 32] {
        Sha256::digest(format!("{self:?}").as_bytes()).into()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AppEvent {
    StateChanged(SessionState),
    InvitationAccepted(ParticipantId),
    Rejected {
        participant: ParticipantId,
        reason: String,
    },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SessionCommand {
    SendControl {
        peer: DeviceId,
        kind: &'static str,
    },
    ReserveChannels {
        epoch: u64,
        plan_hash: [u8; 32],
    },
    ReleaseEpoch(u64),
    NotifyApp(AppEvent),
    Persist {
        state: SessionState,
        plan: Option<Box<SessionPlan>>,
        coordinator: ParticipantId,
        coordinator_term: u64,
    },
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ApplyOutcome {
    pub state: SessionState,
    pub commands: Vec<SessionCommand>,
    pub duplicate: bool,
}

#[derive(Clone, Debug)]
struct OperationResult {
    fingerprint: [u8; 32],
    state: SessionState,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum SessionError {
    #[error("event is invalid while Session is {state:?}")]
    InvalidTransition { state: SessionState },
    #[error("operation ID was reused with a different payload")]
    OperationIdReused,
    #[error("participant is not in the proposed plan")]
    UnknownParticipant,
    #[error("plan has an invalid epoch; expected {expected}, got {actual}")]
    InvalidEpoch { expected: u64, actual: u64 },
    #[error("plan hash does not match")]
    PlanHashMismatch,
    #[error("not all participants accepted or became ready")]
    ParticipantsNotReady,
    #[error("coordinator term is stale; expected {expected}, got {actual}")]
    StaleCoordinatorTerm { expected: u64, actual: u64 },
    #[error("the elected coordinator does not match the proposed plan")]
    CoordinatorMismatch,
    #[error("membership changes require a separate authorized membership transition")]
    MembershipChangeRequiresTransition,
    #[error("coordinator election authorization is invalid: {0}")]
    Election(#[from] ElectionError),
    #[error("a coordinator election certificate is required before this transition")]
    ElectionCertificateRequired,
    #[error("invalid plan: {0}")]
    InvalidPlan(#[from] ValidationError),
}

pub struct SessionMachine {
    state: SessionState,
    committed: SessionPlan,
    pending: Option<SessionPlan>,
    accepted: BTreeSet<ParticipantId>,
    ready: BTreeSet<ParticipantId>,
    operations: BTreeMap<OperationId, OperationResult>,
    operation_order: VecDeque<OperationId>,
    operation_limit: usize,
    coordinator_term: u64,
    coordinator: ParticipantId,
    coordinator_fenced: bool,
}

impl SessionMachine {
    pub fn new(proposal: SessionPlan, operation_limit: usize) -> Result<Self, SessionError> {
        proposal.validate()?;
        let coordinator = proposal.coordinator;
        Ok(Self {
            state: SessionState::Proposed,
            committed: proposal,
            pending: None,
            accepted: BTreeSet::new(),
            ready: BTreeSet::new(),
            operations: BTreeMap::new(),
            operation_order: VecDeque::new(),
            operation_limit: operation_limit.max(1),
            coordinator_term: 0,
            coordinator,
            coordinator_fenced: false,
        })
    }

    /// Restores a durable committed snapshot in a fenced state. A restored
    /// coordinator must obtain a fresh majority certificate before the Hub can
    /// reconfigure the Session or resume its data plane.
    pub fn restore_suspended(
        committed: SessionPlan,
        coordinator_term: u64,
        operation_limit: usize,
    ) -> Result<Self, SessionError> {
        committed.validate()?;
        let coordinator = committed.coordinator;
        Ok(Self {
            state: SessionState::Suspended,
            committed,
            pending: None,
            accepted: BTreeSet::new(),
            ready: BTreeSet::new(),
            operations: BTreeMap::new(),
            operation_order: VecDeque::new(),
            operation_limit: operation_limit.max(1),
            coordinator_term,
            coordinator,
            coordinator_fenced: true,
        })
    }

    #[must_use]
    pub const fn state(&self) -> SessionState {
        self.state
    }

    #[must_use]
    pub const fn plan(&self) -> &SessionPlan {
        &self.committed
    }

    #[must_use]
    pub const fn pending_plan(&self) -> Option<&SessionPlan> {
        self.pending.as_ref()
    }

    #[must_use]
    pub const fn coordinator_term(&self) -> u64 {
        self.coordinator_term
    }

    #[must_use]
    pub const fn coordinator(&self) -> ParticipantId {
        self.coordinator
    }

    #[must_use]
    pub const fn coordinator_fenced(&self) -> bool {
        self.coordinator_fenced
    }

    /// Fence the current coordinator after a higher election term has been
    /// durably observed. A certificate at that term is still required before
    /// installing a replacement coordinator or resuming the Session.
    pub fn fence_after_higher_term(&mut self, observed_term: u64) -> Result<(), SessionError> {
        if observed_term <= self.coordinator_term {
            return Err(SessionError::StaleCoordinatorTerm {
                expected: self.coordinator_term.saturating_add(1),
                actual: observed_term,
            });
        }
        match self.state {
            SessionState::Active | SessionState::Reconfiguring => {
                self.apply(SessionEvent::Suspend {
                    operation_id: OperationId::new(),
                })?;
            }
            SessionState::Suspended => {}
            state => return Err(SessionError::InvalidTransition { state }),
        }
        self.coordinator_fenced = true;
        Ok(())
    }

    /// Persist the verified election term before applying it to this Session.
    pub fn apply_election_certificate(
        &mut self,
        election: &mut CoordinatorElection,
        operation_id: OperationId,
        certificate: ElectionCertificate,
    ) -> Result<ApplyOutcome, SessionError> {
        certificate.verify(&self.committed)?;
        election.authorize_certificate(&certificate)?;
        self.apply_inner(SessionEvent::CoordinatorElected {
            operation_id,
            certificate,
        })
    }

    pub fn apply(&mut self, event: SessionEvent) -> Result<ApplyOutcome, SessionError> {
        if matches!(event, SessionEvent::CoordinatorElected { .. }) {
            return Err(SessionError::ElectionCertificateRequired);
        }
        self.apply_inner(event)
    }

    fn apply_inner(&mut self, event: SessionEvent) -> Result<ApplyOutcome, SessionError> {
        let operation_id = event.operation_id();
        let fingerprint = event.fingerprint();
        if let Some(result) = self.operations.get(&operation_id) {
            if result.fingerprint != fingerprint {
                return Err(SessionError::OperationIdReused);
            }
            return Ok(ApplyOutcome {
                state: result.state,
                commands: Vec::new(),
                duplicate: true,
            });
        }

        let commands = self.transition(event)?;
        let outcome = ApplyOutcome {
            state: self.state,
            commands,
            duplicate: false,
        };
        if self.operations.len() == self.operation_limit
            && let Some(oldest) = self.operation_order.pop_front()
        {
            self.operations.remove(&oldest);
        }
        self.operation_order.push_back(operation_id);
        self.operations.insert(
            operation_id,
            OperationResult {
                fingerprint,
                state: self.state,
            },
        );
        Ok(outcome)
    }

    #[allow(clippy::too_many_lines)]
    fn transition(&mut self, event: SessionEvent) -> Result<Vec<SessionCommand>, SessionError> {
        if matches!(event, SessionEvent::Fail { .. })
            && !matches!(self.state, SessionState::Closed | SessionState::Failed)
        {
            self.state = SessionState::Failed;
            return Ok(self.state_commands(None));
        }
        if matches!(event, SessionEvent::Close { .. })
            && !matches!(
                self.state,
                SessionState::Closed | SessionState::Failed | SessionState::Closing
            )
        {
            self.state = SessionState::Closing;
            return Ok(self.state_commands(None));
        }

        match (self.state, event) {
            (SessionState::Proposed, SessionEvent::BeginNegotiation { .. }) => {
                self.state = SessionState::Negotiating;
                Ok(self.state_commands(None))
            }
            (SessionState::Negotiating, SessionEvent::Accept { participant, .. }) => {
                self.ensure_participant(participant)?;
                self.accepted.insert(participant);
                Ok(vec![SessionCommand::NotifyApp(
                    AppEvent::InvitationAccepted(participant),
                )])
            }
            (SessionState::Negotiating, SessionEvent::CounterOffer { plan, .. }) => {
                Self::ensure_same_epoch(&plan, self.committed.epoch)?;
                if plan.coordinator != self.coordinator {
                    return Err(SessionError::CoordinatorMismatch);
                }
                plan.validate()?;
                self.committed = plan;
                self.accepted.clear();
                Ok(vec![SessionCommand::Persist {
                    state: self.state,
                    plan: Some(Box::new(self.committed.clone())),
                    coordinator: self.coordinator,
                    coordinator_term: self.coordinator_term,
                }])
            }
            (
                SessionState::Negotiating,
                SessionEvent::Reject {
                    participant,
                    reason,
                    ..
                },
            ) => {
                self.ensure_participant(participant)?;
                self.state = SessionState::Closing;
                Ok(vec![
                    SessionCommand::NotifyApp(AppEvent::Rejected {
                        participant,
                        reason,
                    }),
                    SessionCommand::Persist {
                        state: self.state,
                        plan: None,
                        coordinator: self.coordinator,
                        coordinator_term: self.coordinator_term,
                    },
                ])
            }
            (SessionState::Negotiating, SessionEvent::Prepare { term, plan, .. }) => {
                self.ensure_term(term)?;
                if plan.coordinator != self.coordinator {
                    return Err(SessionError::CoordinatorMismatch);
                }
                self.ensure_all_accepted()?;
                Self::ensure_same_epoch(&plan, self.committed.epoch)?;
                plan.validate()?;
                let hash = plan.canonical_hash();
                self.pending = Some(plan);
                self.ready.clear();
                self.state = SessionState::Preparing;
                Ok(vec![
                    SessionCommand::ReserveChannels {
                        epoch: self.committed.epoch,
                        plan_hash: hash,
                    },
                    SessionCommand::NotifyApp(AppEvent::StateChanged(self.state)),
                ])
            }
            (
                SessionState::Preparing | SessionState::Reconfiguring,
                SessionEvent::Ready {
                    participant,
                    term,
                    plan_hash,
                    ..
                },
            ) => {
                self.ensure_term(term)?;
                self.ensure_pending_participant(participant)?;
                if self.pending_hash() != Some(plan_hash) {
                    return Err(SessionError::PlanHashMismatch);
                }
                self.ready.insert(participant);
                Ok(Vec::new())
            }
            (
                SessionState::Preparing | SessionState::Reconfiguring,
                SessionEvent::Commit {
                    term, plan_hash, ..
                },
            ) => {
                self.ensure_term(term)?;
                self.commit(plan_hash)
            }
            (SessionState::Active, SessionEvent::Suspend { .. }) => {
                self.state = SessionState::Suspended;
                Ok(self.state_commands(None))
            }
            (SessionState::Reconfiguring, SessionEvent::Suspend { .. }) => {
                let epoch = self.pending.take().map(|plan| plan.epoch);
                self.ready.clear();
                self.state = SessionState::Suspended;
                let mut commands = self.state_commands(None);
                if let Some(epoch) = epoch {
                    commands.push(SessionCommand::ReleaseEpoch(epoch));
                }
                Ok(commands)
            }
            (SessionState::Suspended, SessionEvent::Resume { .. }) => {
                if self.coordinator_fenced {
                    return Err(SessionError::ElectionCertificateRequired);
                }
                if self.coordinator != self.committed.coordinator {
                    return Err(SessionError::CoordinatorMismatch);
                }
                self.state = SessionState::Active;
                Ok(self.state_commands(None))
            }
            (SessionState::Active, SessionEvent::CoordinatorElected { certificate, .. })
            | (SessionState::Suspended, SessionEvent::CoordinatorElected { certificate, .. }) => {
                certificate.verify(&self.committed)?;
                if certificate.term <= self.coordinator_term {
                    return Err(SessionError::StaleCoordinatorTerm {
                        expected: self.coordinator_term.saturating_add(1),
                        actual: certificate.term,
                    });
                }
                self.coordinator_term = certificate.term;
                self.coordinator = certificate.candidate;
                self.coordinator_fenced = false;
                if self.coordinator != self.committed.coordinator {
                    self.state = SessionState::Suspended;
                }
                Ok(self.state_commands(Some(Box::new(self.committed.clone()))))
            }
            (
                SessionState::Active | SessionState::Suspended,
                SessionEvent::Reconfigure { term, plan, .. },
            ) => {
                self.ensure_term(term)?;
                if plan.coordinator != self.coordinator {
                    return Err(SessionError::CoordinatorMismatch);
                }
                if membership_config_hash(&self.committed) != membership_config_hash(&plan) {
                    return Err(SessionError::MembershipChangeRequiresTransition);
                }
                Self::ensure_same_epoch(&plan, self.committed.epoch + 1)?;
                plan.validate()?;
                let hash = plan.canonical_hash();
                self.pending = Some(plan);
                self.ready.clear();
                self.state = SessionState::Reconfiguring;
                Ok(vec![
                    SessionCommand::ReserveChannels {
                        epoch: self.committed.epoch + 1,
                        plan_hash: hash,
                    },
                    SessionCommand::NotifyApp(AppEvent::StateChanged(self.state)),
                ])
            }
            (SessionState::Closing, SessionEvent::Closed { .. }) => {
                self.state = SessionState::Closed;
                Ok(self.state_commands(None))
            }
            _ => Err(SessionError::InvalidTransition { state: self.state }),
        }
    }

    fn commit(&mut self, hash: [u8; 32]) -> Result<Vec<SessionCommand>, SessionError> {
        if self.pending_hash() != Some(hash) {
            return Err(SessionError::PlanHashMismatch);
        }
        let pending = self
            .pending
            .as_ref()
            .ok_or(SessionError::InvalidTransition { state: self.state })?;
        if !pending
            .participants
            .iter()
            .all(|participant| self.ready.contains(&participant.id))
        {
            return Err(SessionError::ParticipantsNotReady);
        }
        let previous_epoch = self.committed.epoch;
        self.committed = self
            .pending
            .take()
            .ok_or(SessionError::InvalidTransition { state: self.state })?;
        self.state = SessionState::Active;
        self.accepted.clear();
        self.ready.clear();
        Ok(vec![
            SessionCommand::ReleaseEpoch(previous_epoch),
            SessionCommand::NotifyApp(AppEvent::StateChanged(self.state)),
            SessionCommand::Persist {
                state: self.state,
                plan: Some(Box::new(self.committed.clone())),
                coordinator: self.coordinator,
                coordinator_term: self.coordinator_term,
            },
        ])
    }

    fn ensure_participant(&self, participant: ParticipantId) -> Result<(), SessionError> {
        self.committed
            .participants
            .iter()
            .any(|item| item.id == participant)
            .then_some(())
            .ok_or(SessionError::UnknownParticipant)
    }

    fn ensure_pending_participant(&self, participant: ParticipantId) -> Result<(), SessionError> {
        self.pending
            .as_ref()
            .is_some_and(|plan| plan.participants.iter().any(|item| item.id == participant))
            .then_some(())
            .ok_or(SessionError::UnknownParticipant)
    }

    fn ensure_all_accepted(&self) -> Result<(), SessionError> {
        self.committed
            .participants
            .iter()
            .all(|participant| self.accepted.contains(&participant.id))
            .then_some(())
            .ok_or(SessionError::ParticipantsNotReady)
    }

    fn ensure_same_epoch(plan: &SessionPlan, expected: u64) -> Result<(), SessionError> {
        if plan.epoch != expected {
            return Err(SessionError::InvalidEpoch {
                expected,
                actual: plan.epoch,
            });
        }
        Ok(())
    }

    fn ensure_term(&self, actual: u64) -> Result<(), SessionError> {
        if actual != self.coordinator_term {
            return Err(SessionError::StaleCoordinatorTerm {
                expected: self.coordinator_term,
                actual,
            });
        }
        Ok(())
    }

    fn pending_hash(&self) -> Option<[u8; 32]> {
        self.pending.as_ref().map(SessionPlan::canonical_hash)
    }

    fn state_commands(&self, plan: Option<Box<SessionPlan>>) -> Vec<SessionCommand> {
        vec![
            SessionCommand::NotifyApp(AppEvent::StateChanged(self.state)),
            SessionCommand::Persist {
                state: self.state,
                plan,
                coordinator: self.coordinator,
                coordinator_term: self.coordinator_term,
            },
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;

    fn plan() -> SessionPlan {
        let participant = ParticipantId::new();
        SessionPlan {
            session_id: SessionId::new(),
            contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: [1; 32],
            },
            epoch: 1,
            coordinator: participant,
            participants: vec![Participant {
                id: participant,
                device_id: DeviceId([1; 32]),
                app_principal: AppPrincipal {
                    platform: Platform::Test,
                    stable_app_id: "test.app".into(),
                    publisher_id: None,
                    signing_digest: None,
                    os_subject: OsSubject("test:1".into()),
                },
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
    fn election_certificate_authorizes_reconfigure_and_fences_old_term() {
        let identity = fabric_identity::DeviceIdentity::from_seed([31; 32]);
        let mut committed = plan();
        committed.participants[0].device_id = identity.device_id();
        let participant = committed.coordinator;
        let store = std::sync::Arc::new(election::MemoryElectionStore::default());
        let mut election =
            CoordinatorElection::new(committed.clone(), participant, store.clone()).unwrap();
        let (_, _) = election.begin_election(&identity).unwrap();
        let certificate = election.certificate().unwrap();

        let mut machine = SessionMachine::new(committed, 32).unwrap();
        machine.state = SessionState::Active;
        assert_eq!(
            machine.apply(SessionEvent::CoordinatorElected {
                operation_id: OperationId::new(),
                certificate: certificate.clone(),
            }),
            Err(SessionError::ElectionCertificateRequired)
        );
        machine
            .apply_election_certificate(&mut election, OperationId::new(), certificate)
            .unwrap();
        assert_eq!(machine.coordinator_term(), 1);
        assert_eq!(machine.coordinator(), participant);

        let mut next = machine.plan().clone();
        next.epoch += 1;
        assert_eq!(
            machine.apply(SessionEvent::Reconfigure {
                operation_id: OperationId::new(),
                term: 0,
                plan: next.clone(),
            }),
            Err(SessionError::StaleCoordinatorTerm {
                expected: 1,
                actual: 0,
            })
        );
        let outcome = machine
            .apply(SessionEvent::Reconfigure {
                operation_id: OperationId::new(),
                term: 1,
                plan: next,
            })
            .unwrap();
        assert_eq!(outcome.state, SessionState::Reconfiguring);
    }

    #[test]
    fn restored_and_higher_term_sessions_require_a_durable_certificate_to_resume() {
        use std::sync::Arc;

        let identity = fabric_identity::DeviceIdentity::from_seed([42; 32]);
        let mut committed = plan();
        committed.participants[0].device_id = identity.device_id();
        let participant = committed.coordinator;
        let store = Arc::new(MemoryElectionStore::default());
        store
            .initialize(
                committed.session_id,
                ElectionPersistentState {
                    membership_hash: membership_config_hash(&committed),
                    term: 4,
                    voted_for: None,
                    committed_epoch: committed.epoch,
                    committed_plan_hash: committed.canonical_hash(),
                },
            )
            .unwrap();
        let mut election = CoordinatorElection::new(committed.clone(), participant, store).unwrap();
        let mut restored = SessionMachine::restore_suspended(committed.clone(), 4, 32).unwrap();
        assert!(restored.coordinator_fenced());
        assert_eq!(
            restored.apply(SessionEvent::Resume {
                operation_id: OperationId::new(),
            }),
            Err(SessionError::ElectionCertificateRequired)
        );

        let (request, _) = election.begin_election(&identity).unwrap();
        assert_eq!(request.term, 5);
        let certificate = election.certificate().unwrap();
        restored
            .apply_election_certificate(&mut election, OperationId::new(), certificate)
            .unwrap();
        assert!(!restored.coordinator_fenced());
        assert_eq!(restored.coordinator_term(), 5);
        restored
            .apply(SessionEvent::Resume {
                operation_id: OperationId::new(),
            })
            .unwrap();
        assert_eq!(restored.state(), SessionState::Active);

        let mut active = SessionMachine::new(committed, 32).unwrap();
        active.state = SessionState::Active;
        active.fence_after_higher_term(1).unwrap();
        assert_eq!(active.state(), SessionState::Suspended);
        assert!(active.coordinator_fenced());
    }

    #[test]
    fn four_members_leader_offline_majority_election_does_not_fake_commit() {
        use std::sync::Arc;
        let identities: Vec<_> = (70..74)
            .map(|seed| fabric_identity::DeviceIdentity::from_seed([seed; 32]))
            .collect();
        let mut committed = plan();
        let template = committed.participants[0].clone();
        committed.participants = identities
            .iter()
            .map(|identity| Participant {
                id: ParticipantId::new(),
                device_id: identity.device_id(),
                ability_instance: AbilityInstanceId::new(),
                ..template.clone()
            })
            .collect();
        committed.coordinator = committed.participants[0].id;
        let mut candidate = CoordinatorElection::new(
            committed.clone(),
            committed.participants[1].id,
            Arc::new(MemoryElectionStore::default()),
        )
        .unwrap();
        let (request, _) = candidate.begin_election(&identities[1]).unwrap();
        for index in [2, 3] {
            let mut voter = CoordinatorElection::new(
                committed.clone(),
                committed.participants[index].id,
                Arc::new(MemoryElectionStore::default()),
            )
            .unwrap();
            candidate
                .add_vote(
                    voter
                        .cast_vote(&request, identities[1].device_id(), &identities[index])
                        .unwrap(),
                )
                .unwrap();
        }
        let certificate = candidate.certificate().unwrap();
        assert_eq!(certificate.votes.len(), 3);
        let mut machine = SessionMachine::new(committed.clone(), 32).unwrap();
        machine.state = SessionState::Active;
        machine
            .apply_election_certificate(&mut candidate, OperationId::new(), certificate)
            .unwrap();
        assert_eq!(machine.state(), SessionState::Suspended);
        assert_eq!(machine.coordinator(), committed.participants[1].id);
        assert_eq!(
            machine.apply(SessionEvent::Resume {
                operation_id: OperationId::new()
            }),
            Err(SessionError::CoordinatorMismatch)
        );
        let mut next = committed.clone();
        next.epoch += 1;
        next.coordinator = machine.coordinator();
        let hash = next.canonical_hash();
        machine
            .apply(SessionEvent::Reconfigure {
                operation_id: OperationId::new(),
                term: 1,
                plan: next,
            })
            .unwrap();
        for index in [1, 2, 3] {
            machine
                .apply(SessionEvent::Ready {
                    operation_id: OperationId::new(),
                    participant: committed.participants[index].id,
                    term: 1,
                    plan_hash: hash,
                })
                .unwrap();
        }
        assert_eq!(
            machine.apply(SessionEvent::Commit {
                operation_id: OperationId::new(),
                term: 1,
                plan_hash: hash
            }),
            Err(SessionError::ParticipantsNotReady)
        );
        machine
            .apply(SessionEvent::Suspend {
                operation_id: OperationId::new(),
            })
            .unwrap();
        assert_eq!(machine.state(), SessionState::Suspended);
        assert_eq!(machine.plan(), &committed);
    }

    #[test]
    fn negotiates_and_commits_once() {
        let plan = plan();
        let participant = plan.participants[0].id;
        let hash = plan.canonical_hash();
        let mut machine = SessionMachine::new(plan.clone(), 32).unwrap();
        machine
            .apply(SessionEvent::BeginNegotiation {
                operation_id: OperationId::new(),
            })
            .unwrap();
        machine
            .apply(SessionEvent::Accept {
                operation_id: OperationId::new(),
                participant,
            })
            .unwrap();
        machine
            .apply(SessionEvent::Prepare {
                operation_id: OperationId::new(),
                term: 0,
                plan,
            })
            .unwrap();
        machine
            .apply(SessionEvent::Ready {
                operation_id: OperationId::new(),
                participant,
                term: 0,
                plan_hash: hash,
            })
            .unwrap();
        let commit_id = OperationId::new();
        let first = machine
            .apply(SessionEvent::Commit {
                operation_id: commit_id,
                term: 0,
                plan_hash: hash,
            })
            .unwrap();
        let duplicate = machine
            .apply(SessionEvent::Commit {
                operation_id: commit_id,
                term: 0,
                plan_hash: hash,
            })
            .unwrap();
        assert_eq!(first.state, SessionState::Active);
        assert!(!first.commands.is_empty());
        assert!(duplicate.duplicate);
        assert!(duplicate.commands.is_empty());
    }

    #[test]
    fn rejects_invalid_transition_and_stale_reconfiguration() {
        let mut machine = SessionMachine::new(plan(), 32).unwrap();
        assert!(matches!(
            machine.apply(SessionEvent::Commit {
                operation_id: OperationId::new(),
                term: 0,
                plan_hash: [0; 32]
            }),
            Err(SessionError::InvalidTransition { .. })
        ));
        machine.state = SessionState::Active;
        let stale = machine.plan().clone();
        assert!(matches!(
            machine.apply(SessionEvent::Reconfigure {
                operation_id: OperationId::new(),
                term: 0,
                plan: stale
            }),
            Err(SessionError::InvalidEpoch { .. })
        ));
    }

    #[test]
    fn operation_cache_evicts_in_insertion_order() {
        let mut machine = SessionMachine::new(plan(), 2).unwrap();
        let first = OperationId::new();
        let second = OperationId::new();
        let third = OperationId::new();
        machine
            .apply(SessionEvent::BeginNegotiation {
                operation_id: first,
            })
            .unwrap();
        let participant = machine.plan().participants[0].id;
        machine
            .apply(SessionEvent::Accept {
                operation_id: second,
                participant,
            })
            .unwrap();
        machine
            .apply(SessionEvent::Accept {
                operation_id: third,
                participant,
            })
            .unwrap();
        assert!(!machine.operations.contains_key(&first));
        assert!(machine.operations.contains_key(&second));
        assert!(machine.operations.contains_key(&third));
    }
}
