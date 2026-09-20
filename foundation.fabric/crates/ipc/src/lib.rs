//! Authenticated local IPC framing, quotas, connection leases, and test transport.

mod local;
pub use local::*;
mod wire;
pub use wire::*;

use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{Arc, Mutex},
};

use bytes::Bytes;
use fabric_core::{
    AbilityInstanceId, AbilityOffer, AppPrincipal, ConnectionId, OperationId, ParticipantId,
    RequirementId, SessionId, SessionPlan, SessionState,
};
use fabric_identity::{IdentityError, OsCredentialAdapter, PeerCredentials, bind_application};
use fabric_registry::{Registry, RegistryError};
use fabric_session::{SessionEvent, SessionMachine};
use thiserror::Error;

pub const MAX_CONTROL_FRAME_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum FrameKind {
    ClientHello = 1,
    ServerHello = 2,
    BindApplication = 3,
    ApplicationBound = 4,
    Request = 5,
    Response = 6,
    Event = 7,
}

impl TryFrom<u8> for FrameKind {
    type Error = IpcError;
    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::ClientHello),
            2 => Ok(Self::ServerHello),
            3 => Ok(Self::BindApplication),
            4 => Ok(Self::ApplicationBound),
            5 => Ok(Self::Request),
            6 => Ok(Self::Response),
            7 => Ok(Self::Event),
            _ => Err(IpcError::UnsupportedMessage),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Frame {
    pub kind: FrameKind,
    pub id: u64,
    pub payload: Bytes,
}

impl Frame {
    pub fn encode(&self) -> Result<Vec<u8>, IpcError> {
        let length = 9_usize
            .checked_add(self.payload.len())
            .ok_or(IpcError::FrameTooLarge)?;
        if length > MAX_CONTROL_FRAME_BYTES {
            return Err(IpcError::FrameTooLarge);
        }
        let length = u32::try_from(length).map_err(|_| IpcError::FrameTooLarge)?;
        let mut output = Vec::with_capacity(4 + length as usize);
        output.extend_from_slice(&length.to_be_bytes());
        output.push(self.kind as u8);
        output.extend_from_slice(&self.id.to_be_bytes());
        output.extend_from_slice(&self.payload);
        Ok(output)
    }

    pub fn decode(input: &[u8]) -> Result<Self, IpcError> {
        if input.len() < 4 {
            return Err(IpcError::TruncatedFrame);
        }
        let length = u32::from_be_bytes(
            input[..4]
                .try_into()
                .map_err(|_| IpcError::TruncatedFrame)?,
        ) as usize;
        if length > MAX_CONTROL_FRAME_BYTES {
            return Err(IpcError::FrameTooLarge);
        }
        if length < 9 || input.len() < 4 + length {
            return Err(IpcError::TruncatedFrame);
        }
        if input.len() != 4 + length {
            return Err(IpcError::TrailingFrameData);
        }
        let kind = FrameKind::try_from(input[4])?;
        let id = u64::from_be_bytes(
            input[5..13]
                .try_into()
                .map_err(|_| IpcError::TruncatedFrame)?,
        );
        Ok(Self {
            kind,
            id,
            payload: Bytes::copy_from_slice(&input[13..4 + length]),
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ConnectionQuota {
    pub maximum_offers: usize,
    pub maximum_requirements: usize,
    pub maximum_sessions: usize,
    pub maximum_buffered_bytes: u64,
}

impl Default for ConnectionQuota {
    fn default() -> Self {
        Self {
            maximum_offers: 128,
            maximum_requirements: 128,
            maximum_sessions: 32,
            maximum_buffered_bytes: 16 * 1024 * 1024,
        }
    }
}

#[derive(Debug, Error)]
pub enum IpcError {
    #[error("control frame is too large")]
    FrameTooLarge,
    #[error("control frame is truncated or malformed")]
    TruncatedFrame,
    #[error("frame kind is unsupported")]
    UnsupportedMessage,
    #[error("control frame contains trailing data")]
    TrailingFrameData,
    #[error("connection was not found")]
    ConnectionNotFound,
    #[error("authenticated principal does not own this object")]
    PrincipalMismatch,
    #[error("connection quota exceeded")]
    QuotaExceeded,
    #[error("Session invitation was not found")]
    InvitationNotFound,
    #[error("connection is not invited to this Session")]
    NotInvited,
    #[error("connection lock was poisoned")]
    Poisoned,
    #[error("identity failure: {0}")]
    Identity(#[from] IdentityError),
    #[error("registry failure: {0}")]
    Registry(#[from] RegistryError),
    #[error("Session negotiation failed: {0}")]
    Session(#[from] fabric_session::SessionError),
}

struct Connection {
    principal: AppPrincipal,
    quota: ConnectionQuota,
    offers: Vec<AbilityInstanceId>,
    requirements: Vec<RequirementId>,
    sessions: usize,
}

struct PendingSession {
    plan: SessionPlan,
    participant_connections: BTreeMap<ParticipantId, ConnectionId>,
    accepted_connections: BTreeSet<ConnectionId>,
}

pub struct IpcServer<A> {
    credentials: A,
    registry: Arc<Registry>,
    connections: Mutex<BTreeMap<ConnectionId, Connection>>,
    pending_sessions: Mutex<BTreeMap<SessionId, PendingSession>>,
    active_sessions: Mutex<BTreeMap<SessionId, ActiveSession>>,
}

struct ActiveSession {
    machine: SessionMachine,
    owners: BTreeSet<ConnectionId>,
}

impl<A: OsCredentialAdapter> IpcServer<A> {
    #[must_use]
    pub fn new(credentials: A, registry: Arc<Registry>) -> Self {
        Self {
            credentials,
            registry,
            connections: Mutex::new(BTreeMap::new()),
            pending_sessions: Mutex::new(BTreeMap::new()),
            active_sessions: Mutex::new(BTreeMap::new()),
        }
    }

    pub fn connect(
        &self,
        peer: &PeerCredentials,
        declared_app_id: Option<&str>,
        quota: ConnectionQuota,
    ) -> Result<(ConnectionId, AppPrincipal), IpcError> {
        let principal = bind_application(self.credentials.authenticate(peer)?, declared_app_id)?;
        self.insert_connection(principal, quota)
    }

    pub fn connect_authenticated(
        &self,
        peer: &PeerCredentials,
        quota: ConnectionQuota,
    ) -> Result<(ConnectionId, AppPrincipal), IpcError> {
        let principal = self.credentials.authenticate(peer)?;
        self.insert_connection(principal, quota)
    }

    pub fn bind_connection(
        &self,
        id: ConnectionId,
        declared_app_id: Option<&str>,
    ) -> Result<AppPrincipal, IpcError> {
        let connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        let principal = connections
            .get(&id)
            .ok_or(IpcError::ConnectionNotFound)?
            .principal
            .clone();
        Ok(bind_application(principal, declared_app_id)?)
    }

    fn insert_connection(
        &self,
        principal: AppPrincipal,
        quota: ConnectionQuota,
    ) -> Result<(ConnectionId, AppPrincipal), IpcError> {
        let id = ConnectionId::new();
        self.connections
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .insert(
                id,
                Connection {
                    principal: principal.clone(),
                    quota,
                    offers: Vec::new(),
                    requirements: Vec::new(),
                    sessions: 0,
                },
            );
        Ok((id, principal))
    }

    pub fn publish_offer(
        &self,
        connection_id: ConnectionId,
        mut offer: AbilityOffer,
        now_ms: u64,
    ) -> Result<AbilityInstanceId, IpcError> {
        let mut connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        let connection = connections
            .get_mut(&connection_id)
            .ok_or(IpcError::ConnectionNotFound)?;
        if connection.offers.len() >= connection.quota.maximum_offers {
            return Err(IpcError::QuotaExceeded);
        }
        offer.app = connection.principal.clone();
        offer.validate().map_err(|_| IpcError::PrincipalMismatch)?;
        let id = offer.instance_id;
        self.registry.upsert_offer(connection_id, offer, now_ms)?;
        connection.offers.push(id);
        Ok(id)
    }

    pub fn register_requirement(
        &self,
        connection_id: ConnectionId,
        requirement: fabric_core::AbilityRequirement,
        now_ms: u64,
    ) -> Result<RequirementId, IpcError> {
        let mut connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        let connection = connections
            .get_mut(&connection_id)
            .ok_or(IpcError::ConnectionNotFound)?;
        if connection.requirements.len() >= connection.quota.maximum_requirements {
            return Err(IpcError::QuotaExceeded);
        }
        requirement
            .validate()
            .map_err(|_| IpcError::PrincipalMismatch)?;
        let id = requirement.requirement_id;
        self.registry
            .upsert_requirement(connection_id, requirement, now_ms)?;
        connection.requirements.push(id);
        Ok(id)
    }

    pub fn propose_local_session(
        &self,
        proposer: ConnectionId,
        plan: SessionPlan,
    ) -> Result<SessionId, IpcError> {
        plan.validate()
            .map_err(fabric_session::SessionError::from)?;
        let connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        let proposer_connection = connections
            .get(&proposer)
            .ok_or(IpcError::ConnectionNotFound)?;
        if proposer_connection.sessions >= proposer_connection.quota.maximum_sessions {
            return Err(IpcError::QuotaExceeded);
        }
        let coordinator = plan
            .participants
            .iter()
            .find(|participant| participant.id == plan.coordinator)
            .ok_or(IpcError::PrincipalMismatch)?;
        if coordinator.app_principal != proposer_connection.principal
            || !proposer_connection
                .offers
                .contains(&coordinator.ability_instance)
        {
            return Err(IpcError::PrincipalMismatch);
        }

        let mut participant_connections = BTreeMap::new();
        for participant in &plan.participants {
            let owners: Vec<_> = connections
                .iter()
                .filter(|(_, connection)| {
                    connection.principal == participant.app_principal
                        && connection.offers.contains(&participant.ability_instance)
                })
                .map(|(id, _)| *id)
                .collect();
            let [owner] = owners.as_slice() else {
                return Err(IpcError::PrincipalMismatch);
            };
            let connection = connections.get(owner).ok_or(IpcError::ConnectionNotFound)?;
            if connection.sessions >= connection.quota.maximum_sessions {
                return Err(IpcError::QuotaExceeded);
            }
            participant_connections.insert(participant.id, *owner);
        }
        let session_id = plan.session_id;
        let mut accepted_connections = BTreeSet::new();
        accepted_connections.insert(proposer);
        let pending = PendingSession {
            plan,
            participant_connections,
            accepted_connections,
        };
        let mut pending_sessions = self
            .pending_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?;
        if pending_sessions.contains_key(&session_id)
            || self
                .active_sessions
                .lock()
                .map_err(|_| IpcError::Poisoned)?
                .contains_key(&session_id)
        {
            return Err(IpcError::PrincipalMismatch);
        }
        pending_sessions.insert(session_id, pending);
        Ok(session_id)
    }

    pub fn accept_local_session(
        &self,
        connection_id: ConnectionId,
        session_id: SessionId,
    ) -> Result<Option<SessionPlan>, IpcError> {
        let mut connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        if !connections.contains_key(&connection_id) {
            return Err(IpcError::ConnectionNotFound);
        }
        let mut pending_sessions = self
            .pending_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?;
        let pending = pending_sessions
            .get_mut(&session_id)
            .ok_or(IpcError::InvitationNotFound)?;
        if !pending
            .participant_connections
            .values()
            .any(|owner| *owner == connection_id)
        {
            return Err(IpcError::NotInvited);
        }
        pending.accepted_connections.insert(connection_id);
        let required_connections: BTreeSet<_> =
            pending.participant_connections.values().copied().collect();
        if pending.accepted_connections != required_connections {
            return Ok(None);
        }
        for owner in &required_connections {
            let connection = connections.get(owner).ok_or(IpcError::ConnectionNotFound)?;
            if connection.sessions >= connection.quota.maximum_sessions {
                return Err(IpcError::QuotaExceeded);
            }
        }
        let pending = pending_sessions
            .remove(&session_id)
            .ok_or(IpcError::InvitationNotFound)?;
        let plan = pending.plan;
        let mut machine = SessionMachine::new(plan.clone(), 128)?;
        machine.apply(SessionEvent::BeginNegotiation {
            operation_id: OperationId::new(),
        })?;
        for participant in &plan.participants {
            machine.apply(SessionEvent::Accept {
                operation_id: OperationId::new(),
                participant: participant.id,
            })?;
        }
        machine.apply(SessionEvent::Prepare {
            operation_id: OperationId::new(),
            plan: plan.clone(),
        })?;
        let hash = plan.canonical_hash();
        for participant in &plan.participants {
            machine.apply(SessionEvent::Ready {
                operation_id: OperationId::new(),
                participant: participant.id,
                plan_hash: hash,
            })?;
        }
        machine.apply(SessionEvent::Commit {
            operation_id: OperationId::new(),
            plan_hash: hash,
        })?;
        if machine.state() != SessionState::Active {
            return Err(IpcError::Session(
                fabric_session::SessionError::InvalidTransition {
                    state: machine.state(),
                },
            ));
        }
        for owner in &required_connections {
            connections
                .get_mut(owner)
                .ok_or(IpcError::ConnectionNotFound)?
                .sessions += 1;
        }
        let committed_plan = machine.plan().clone();
        self.active_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .insert(
                session_id,
                ActiveSession {
                    machine,
                    owners: required_connections,
                },
            );
        Ok(Some(committed_plan))
    }

    pub fn active_session_state(
        &self,
        session_id: SessionId,
    ) -> Result<Option<SessionState>, IpcError> {
        Ok(self
            .active_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .get(&session_id)
            .map(|session| session.machine.state()))
    }

    pub fn active_session_count(&self) -> Result<usize, IpcError> {
        Ok(self
            .active_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .len())
    }

    pub fn connection_count(&self) -> Result<usize, IpcError> {
        Ok(self
            .connections
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .len())
    }

    pub fn disconnect(&self, id: ConnectionId) -> Result<(), IpcError> {
        let mut connections = self.connections.lock().map_err(|_| IpcError::Poisoned)?;
        let existed = connections.remove(&id).is_some();
        if !existed {
            return Err(IpcError::ConnectionNotFound);
        }
        self.pending_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?
            .retain(|_, pending| {
                !pending
                    .participant_connections
                    .values()
                    .any(|owner| *owner == id)
            });
        let mut active_sessions = self
            .active_sessions
            .lock()
            .map_err(|_| IpcError::Poisoned)?;
        let removed_owners: Vec<_> = active_sessions
            .extract_if(.., |_, session| session.owners.contains(&id))
            .flat_map(|(_, session)| session.owners)
            .collect();
        for owner in removed_owners {
            if let Some(connection) = connections.get_mut(&owner) {
                connection.sessions = connection.sessions.saturating_sub(1);
            }
        }
        drop(active_sessions);
        drop(connections);
        self.registry.disconnect(id)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;
    use fabric_identity::PeerCredentials;

    struct TestCredentials;
    impl OsCredentialAdapter for TestCredentials {
        fn authenticate(
            &self,
            credentials: &PeerCredentials,
        ) -> Result<AppPrincipal, IdentityError> {
            Ok(AppPrincipal {
                platform: credentials.platform,
                stable_app_id: credentials.stable_app_id.clone(),
                publisher_id: credentials.publisher_id.clone(),
                signing_digest: credentials.signing_digest,
                os_subject: credentials.os_subject.clone(),
            })
        }
    }
    fn peer(id: &str, subject: &str) -> PeerCredentials {
        PeerCredentials {
            platform: Platform::Test,
            stable_app_id: id.into(),
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject(subject.into()),
        }
    }
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
                stable_app_id: "ignored".into(),
                publisher_id: None,
                signing_digest: None,
                os_subject: OsSubject("ignored".into()),
            },
            roles: vec![RoleId::new("echo").unwrap()],
            properties: PropertyMap::new(),
            visibility: OfferVisibility::TrustedDevices,
            access_policy: PolicyRef("default".into()),
            lease: LeaseSpec::ConnectionBound,
        }
    }

    #[test]
    fn malicious_length_and_unknown_kind_are_rejected() {
        assert_eq!(
            Frame::decode(&u32::MAX.to_be_bytes())
                .unwrap_err()
                .to_string(),
            "control frame is too large"
        );
        let mut frame = vec![0, 0, 0, 9, 255];
        frame.extend_from_slice(&0_u64.to_be_bytes());
        assert!(matches!(
            Frame::decode(&frame),
            Err(IpcError::UnsupportedMessage)
        ));
    }

    #[test]
    fn app_disconnect_removes_connection_bound_offers() {
        let registry = Arc::new(Registry::new());
        let server = IpcServer::new(TestCredentials, Arc::clone(&registry));
        let (connection, _) = server
            .connect(
                &peer("app.a", "pid:1"),
                Some("app.a"),
                ConnectionQuota::default(),
            )
            .unwrap();
        server.publish_offer(connection, offer(), 0).unwrap();
        assert_eq!(registry.snapshot().unwrap().offers.len(), 1);
        server.disconnect(connection).unwrap();
        assert!(registry.snapshot().unwrap().offers.is_empty());
    }

    #[test]
    fn two_authenticated_apps_establish_local_session() {
        let registry = Arc::new(Registry::new());
        let server = IpcServer::new(TestCredentials, registry);
        let (a_connection, a_principal) = server
            .connect(
                &peer("app.a", "pid:1"),
                Some("app.a"),
                ConnectionQuota::default(),
            )
            .unwrap();
        let (b_connection, b_principal) = server
            .connect(
                &peer("app.b", "pid:2"),
                Some("app.b"),
                ConnectionQuota::default(),
            )
            .unwrap();
        let a_instance = server.publish_offer(a_connection, offer(), 0).unwrap();
        let b_instance = server.publish_offer(b_connection, offer(), 0).unwrap();
        let a = ParticipantId::new();
        let b = ParticipantId::new();
        let plan = SessionPlan {
            session_id: SessionId::new(),
            contract: offer().contract,
            epoch: 1,
            coordinator: a,
            participants: vec![
                Participant {
                    id: a,
                    device_id: DeviceId([1; 32]),
                    app_principal: a_principal,
                    ability_instance: a_instance,
                    role: RoleId::new("peer").unwrap(),
                    port_bindings: vec![],
                },
                Participant {
                    id: b,
                    device_id: DeviceId([1; 32]),
                    app_principal: b_principal,
                    ability_instance: b_instance,
                    role: RoleId::new("peer").unwrap(),
                    port_bindings: vec![],
                },
            ],
            channels: vec![],
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        };
        let session_id = server
            .propose_local_session(a_connection, plan.clone())
            .unwrap();
        assert!(
            server
                .accept_local_session(a_connection, session_id)
                .unwrap()
                .is_none()
        );
        let committed = server
            .accept_local_session(b_connection, session_id)
            .unwrap()
            .unwrap();
        assert_eq!(committed.canonical_hash(), plan.canonical_hash());
        assert_eq!(
            server.active_session_state(session_id).unwrap(),
            Some(SessionState::Active)
        );
        assert_eq!(server.active_session_count().unwrap(), 1);
        server.disconnect(a_connection).unwrap();
        assert_eq!(server.active_session_count().unwrap(), 0);
    }

    #[test]
    fn uninvited_connection_cannot_accept_another_apps_session() {
        let registry = Arc::new(Registry::new());
        let server = IpcServer::new(TestCredentials, Arc::clone(&registry));
        let (proposer, proposer_principal) = server
            .connect(
                &peer("app.a", "pid:1"),
                Some("app.a"),
                ConnectionQuota::default(),
            )
            .unwrap();
        let (invitee, invitee_principal) = server
            .connect(
                &peer("app.b", "pid:2"),
                Some("app.b"),
                ConnectionQuota::default(),
            )
            .unwrap();
        let (attacker, _) = server
            .connect(
                &peer("app.c", "pid:3"),
                Some("app.c"),
                ConnectionQuota::default(),
            )
            .unwrap();
        let proposer_ability = server.publish_offer(proposer, offer(), 0).unwrap();
        let invitee_ability = server.publish_offer(invitee, offer(), 0).unwrap();
        let proposer_participant = ParticipantId::new();
        let invitee_participant = ParticipantId::new();
        let plan = SessionPlan {
            session_id: SessionId::new(),
            contract: offer().contract,
            epoch: 1,
            coordinator: proposer_participant,
            participants: vec![
                Participant {
                    id: proposer_participant,
                    device_id: DeviceId([1; 32]),
                    app_principal: proposer_principal,
                    ability_instance: proposer_ability,
                    role: RoleId::new("echo").unwrap(),
                    port_bindings: vec![],
                },
                Participant {
                    id: invitee_participant,
                    device_id: DeviceId([1; 32]),
                    app_principal: invitee_principal,
                    ability_instance: invitee_ability,
                    role: RoleId::new("echo").unwrap(),
                    port_bindings: vec![],
                },
            ],
            channels: vec![],
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        };
        let session_id = server.propose_local_session(proposer, plan).unwrap();
        assert!(matches!(
            server.accept_local_session(attacker, session_id),
            Err(IpcError::NotInvited)
        ));
        assert!(
            server
                .accept_local_session(invitee, session_id)
                .unwrap()
                .is_some()
        );
    }
}
