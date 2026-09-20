//! Deterministic policy evaluation, immutable snapshots, revocation, and scoped grants.

use std::collections::{BTreeMap, BTreeSet};

use fabric_core::{
    AbilityContractRef, AbilityKey, AppPrincipal, CapabilityGrantId, ChannelContract, DeviceId,
    ParticipantId, PolicySnapshotId, PortId, RoleId, SessionId,
};
use thiserror::Error;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UserPresence {
    Absent,
    Confirmed,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AttestedAppPrincipal {
    pub digest: [u8; 32],
    pub publisher_digest: Option<[u8; 32]>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PolicyInput {
    pub local_app: AppPrincipal,
    pub remote_device: DeviceId,
    pub remote_app: Option<AttestedAppPrincipal>,
    pub ability: AbilityContractRef,
    pub requested_role: RoleId,
    pub requested_channels: Vec<ChannelContract>,
    pub user_presence: UserPresence,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PolicyRule {
    pub ability: AbilityKey,
    pub remote_device: Option<DeviceId>,
    pub allowed_roles: BTreeSet<RoleId>,
    pub exact_remote_app: Option<[u8; 32]>,
    pub require_user_presence: bool,
    pub max_total_buffered_bytes: u64,
    pub allow: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PolicyGrant {
    pub snapshot: PolicySnapshotId,
    pub max_total_buffered_bytes: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UserActionRequest {
    pub safe_reason: &'static str,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DenyReason {
    NoMatchingRule,
    ExplicitDeny,
    RemoteAppRequired,
    RemoteAppMismatch,
    ResourceLimit,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PolicyDecision {
    Allow(PolicyGrant),
    RequireUserAction(UserActionRequest),
    Deny(DenyReason),
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum CapabilityAction {
    Open,
    Send,
    Receive,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CapabilityGrant {
    pub id: CapabilityGrantId,
    pub session_id: SessionId,
    pub participant_id: ParticipantId,
    pub ports: BTreeSet<PortId>,
    pub actions: BTreeSet<CapabilityAction>,
    pub epoch: u64,
    pub expires_at_ms: u64,
    pub maximum_buffered_bytes: u64,
    pub policy_snapshot: PolicySnapshotId,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PolicyEvent {
    SnapshotChanged(PolicySnapshotId),
    GrantRevoked(CapabilityGrantId),
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum GrantError {
    #[error("grant is not known or has been revoked")]
    Revoked,
    #[error("grant has expired")]
    Expired,
    #[error("grant epoch does not match")]
    StaleEpoch,
    #[error("grant does not permit the requested port or action")]
    NotPermitted,
}

pub struct PolicyEngine {
    rules: Vec<PolicyRule>,
    snapshot: PolicySnapshotId,
    grants: BTreeMap<CapabilityGrantId, CapabilityGrant>,
}

impl PolicyEngine {
    #[must_use]
    pub fn new(mut rules: Vec<PolicyRule>) -> Self {
        sort_rules(&mut rules);
        Self {
            rules,
            snapshot: PolicySnapshotId::new(),
            grants: BTreeMap::new(),
        }
    }

    #[must_use]
    pub const fn snapshot(&self) -> PolicySnapshotId {
        self.snapshot
    }

    #[must_use]
    pub fn evaluate(&self, input: &PolicyInput) -> PolicyDecision {
        let Some(rule) = self.rules.iter().find(|rule| rule_matches(rule, input)) else {
            return PolicyDecision::Deny(DenyReason::NoMatchingRule);
        };
        if !rule.allow {
            return PolicyDecision::Deny(DenyReason::ExplicitDeny);
        }
        if rule.require_user_presence && input.user_presence != UserPresence::Confirmed {
            return PolicyDecision::RequireUserAction(UserActionRequest {
                safe_reason: "user confirmation required",
            });
        }
        if let Some(expected) = rule.exact_remote_app {
            let Some(remote_app) = &input.remote_app else {
                return PolicyDecision::Deny(DenyReason::RemoteAppRequired);
            };
            if remote_app.digest != expected {
                return PolicyDecision::Deny(DenyReason::RemoteAppMismatch);
            }
        }
        let requested = input
            .requested_channels
            .iter()
            .map(|channel| channel.max_buffered_bytes)
            .try_fold(0_u64, u64::checked_add);
        if requested.is_none_or(|total| total > rule.max_total_buffered_bytes) {
            return PolicyDecision::Deny(DenyReason::ResourceLimit);
        }
        PolicyDecision::Allow(PolicyGrant {
            snapshot: self.snapshot,
            max_total_buffered_bytes: rule.max_total_buffered_bytes,
        })
    }

    pub fn issue_grant(&mut self, grant: CapabilityGrant) {
        self.grants.insert(grant.id, grant);
    }

    pub fn verify_grant(
        &self,
        id: CapabilityGrantId,
        port: PortId,
        action: CapabilityAction,
        epoch: u64,
        now_ms: u64,
    ) -> Result<&CapabilityGrant, GrantError> {
        let grant = self.grants.get(&id).ok_or(GrantError::Revoked)?;
        if now_ms >= grant.expires_at_ms {
            return Err(GrantError::Expired);
        }
        if epoch != grant.epoch {
            return Err(GrantError::StaleEpoch);
        }
        if !grant.ports.contains(&port) || !grant.actions.contains(&action) {
            return Err(GrantError::NotPermitted);
        }
        Ok(grant)
    }

    pub fn replace_rules(&mut self, mut rules: Vec<PolicyRule>) -> Vec<PolicyEvent> {
        sort_rules(&mut rules);
        self.rules = rules;
        self.snapshot = PolicySnapshotId::new();
        let mut events = vec![PolicyEvent::SnapshotChanged(self.snapshot)];
        events.extend(self.grants.keys().copied().map(PolicyEvent::GrantRevoked));
        self.grants.clear();
        events
    }
}

fn rule_matches(rule: &PolicyRule, input: &PolicyInput) -> bool {
    rule.ability == input.ability.key
        && rule
            .remote_device
            .is_none_or(|device| device == input.remote_device)
        && rule.allowed_roles.contains(&input.requested_role)
}

fn sort_rules(rules: &mut [PolicyRule]) {
    rules.sort_by(|left, right| {
        right
            .remote_device
            .is_some()
            .cmp(&left.remote_device.is_some())
            .then_with(|| left.ability.cmp(&right.ability))
            .then_with(|| left.remote_device.cmp(&right.remote_device))
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;

    fn ability() -> AbilityContractRef {
        AbilityContractRef {
            key: AbilityKey {
                namespace: Namespace::new("com.example").unwrap(),
                name: AbilityName::new("echo").unwrap(),
                major: 1,
            },
            protocol_hash: [1; 32],
        }
    }
    fn channel() -> ChannelContract {
        ChannelContract {
            mode: PortMode::ReliableMessages,
            delivery: DeliverySemantics::ReliableOrdered,
            priority: 0,
            max_message_bytes: Some(1024),
            max_buffered_bytes: 2048,
            latency_target_ms: None,
            idle_timeout_ms: None,
            fanout: FanoutPolicy::All,
            payload_security: PayloadSecurity::HubReadable,
            timing: TimingPolicy::None,
        }
    }

    #[test]
    fn policy_change_revokes_grants_and_expiry_is_enforced() {
        let role = RoleId::new("provider").unwrap();
        let policy_rule = PolicyRule {
            ability: ability().key,
            remote_device: None,
            allowed_roles: BTreeSet::from([role]),
            exact_remote_app: None,
            require_user_presence: false,
            max_total_buffered_bytes: 4096,
            allow: true,
        };
        let mut engine = PolicyEngine::new(vec![policy_rule]);
        let id = CapabilityGrantId::new();
        let port = PortId::new();
        engine.issue_grant(CapabilityGrant {
            id,
            session_id: SessionId::new(),
            participant_id: ParticipantId::new(),
            ports: BTreeSet::from([port]),
            actions: BTreeSet::from([CapabilityAction::Open]),
            epoch: 1,
            expires_at_ms: 100,
            maximum_buffered_bytes: 2048,
            policy_snapshot: engine.snapshot(),
        });
        assert!(
            engine
                .verify_grant(id, port, CapabilityAction::Open, 1, 99)
                .is_ok()
        );
        assert_eq!(
            engine.verify_grant(id, port, CapabilityAction::Open, 1, 100),
            Err(GrantError::Expired)
        );
        let events = engine.replace_rules(Vec::new());
        assert!(events.contains(&PolicyEvent::GrantRevoked(id)));
        assert_eq!(
            engine.verify_grant(id, port, CapabilityAction::Open, 1, 0),
            Err(GrantError::Revoked)
        );
    }

    #[test]
    fn user_presence_cannot_be_downgraded() {
        let role = RoleId::new("provider").unwrap();
        let engine = PolicyEngine::new(vec![PolicyRule {
            ability: ability().key.clone(),
            remote_device: None,
            allowed_roles: BTreeSet::from([role.clone()]),
            exact_remote_app: None,
            require_user_presence: true,
            max_total_buffered_bytes: 4096,
            allow: true,
        }]);
        let input = PolicyInput {
            local_app: AppPrincipal {
                platform: Platform::Test,
                stable_app_id: "test.app".into(),
                publisher_id: None,
                signing_digest: None,
                os_subject: OsSubject("test:1".into()),
            },
            remote_device: DeviceId([1; 32]),
            remote_app: None,
            ability: ability(),
            requested_role: role,
            requested_channels: vec![channel()],
            user_presence: UserPresence::Absent,
        };
        assert!(matches!(
            engine.evaluate(&input),
            PolicyDecision::RequireUserAction(_)
        ));
    }
}
