//! Pure Session control state machine. It emits commands and performs no I/O.

use std::collections::{BTreeMap, BTreeSet, VecDeque};

use fabric_core::{
    DeviceId, OperationId, ParticipantId, SessionPlan, SessionState, ValidationError,
};
use sha2::{Digest, Sha256};
use thiserror::Error;

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
        plan: SessionPlan,
    },
    Ready {
        operation_id: OperationId,
        participant: ParticipantId,
        plan_hash: [u8; 32],
    },
    Commit {
        operation_id: OperationId,
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
        plan: SessionPlan,
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
}

impl SessionMachine {
    pub fn new(proposal: SessionPlan, operation_limit: usize) -> Result<Self, SessionError> {
        proposal.validate()?;
        Ok(Self {
            state: SessionState::Proposed,
            committed: proposal,
            pending: None,
            accepted: BTreeSet::new(),
            ready: BTreeSet::new(),
            operations: BTreeMap::new(),
            operation_order: VecDeque::new(),
            operation_limit: operation_limit.max(1),
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

    pub fn apply(&mut self, event: SessionEvent) -> Result<ApplyOutcome, SessionError> {
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
                plan.validate()?;
                self.committed = plan;
                self.accepted.clear();
                Ok(vec![SessionCommand::Persist {
                    state: self.state,
                    plan: Some(Box::new(self.committed.clone())),
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
                    },
                ])
            }
            (SessionState::Negotiating, SessionEvent::Prepare { plan, .. }) => {
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
                    plan_hash,
                    ..
                },
            ) => {
                self.ensure_pending_participant(participant)?;
                if self.pending_hash() != Some(plan_hash) {
                    return Err(SessionError::PlanHashMismatch);
                }
                self.ready.insert(participant);
                Ok(Vec::new())
            }
            (
                SessionState::Preparing | SessionState::Reconfiguring,
                SessionEvent::Commit { plan_hash, .. },
            ) => self.commit(plan_hash),
            (SessionState::Active, SessionEvent::Suspend { .. }) => {
                self.state = SessionState::Suspended;
                Ok(self.state_commands(None))
            }
            (SessionState::Suspended, SessionEvent::Resume { .. }) => {
                self.state = SessionState::Active;
                Ok(self.state_commands(None))
            }
            (SessionState::Active, SessionEvent::Reconfigure { plan, .. }) => {
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

    fn pending_hash(&self) -> Option<[u8; 32]> {
        self.pending.as_ref().map(SessionPlan::canonical_hash)
    }

    fn state_commands(&self, plan: Option<Box<SessionPlan>>) -> Vec<SessionCommand> {
        vec![
            SessionCommand::NotifyApp(AppEvent::StateChanged(self.state)),
            SessionCommand::Persist {
                state: self.state,
                plan,
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
                plan,
            })
            .unwrap();
        machine
            .apply(SessionEvent::Ready {
                operation_id: OperationId::new(),
                participant,
                plan_hash: hash,
            })
            .unwrap();
        let commit_id = OperationId::new();
        let first = machine
            .apply(SessionEvent::Commit {
                operation_id: commit_id,
                plan_hash: hash,
            })
            .unwrap();
        let duplicate = machine
            .apply(SessionEvent::Commit {
                operation_id: commit_id,
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
                plan_hash: [0; 32]
            }),
            Err(SessionError::InvalidTransition { .. })
        ));
        machine.state = SessionState::Active;
        let stale = machine.plan().clone();
        assert!(matches!(
            machine.apply(SessionEvent::Reconfigure {
                operation_id: OperationId::new(),
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
