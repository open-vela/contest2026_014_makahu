//! Session graph, channel contracts, timing, and deterministic plan hashing.

use sha2::{Digest, Sha256};

use crate::{
    AbilityContractRef, AbilityInstanceId, AppPrincipal, ChannelId, ClockDomainId, DeviceId,
    ParticipantId, PolicySnapshotId, PortId, PortMode, RoleId, SessionId, ValidationError,
};

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct PortBinding {
    pub id: PortId,
    pub declaration_id: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct Participant {
    pub id: ParticipantId,
    pub device_id: DeviceId,
    pub app_principal: AppPrincipal,
    pub ability_instance: AbilityInstanceId,
    pub role: RoleId,
    pub port_bindings: Vec<PortBinding>,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct PortRef {
    pub participant: ParticipantId,
    pub port: PortId,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum DeliverySemantics {
    ReliableOrdered,
    BestEffort,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum FanoutPolicy {
    All,
    DropSlowDestinations,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct CryptoSuiteId(pub String);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum KeyMembershipPolicy {
    Session,
    Channel,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PayloadSecurity {
    HubReadable,
    EndToEnd {
        suite: CryptoSuiteId,
        membership: KeyMembershipPolicy,
    },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum TimingPolicy {
    None,
    Timestamped,
    Deadline,
    PresentationTime,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ChannelContract {
    pub mode: PortMode,
    pub delivery: DeliverySemantics,
    pub priority: u8,
    pub max_message_bytes: Option<u64>,
    pub max_buffered_bytes: u64,
    pub latency_target_ms: Option<u32>,
    pub idle_timeout_ms: Option<u64>,
    pub fanout: FanoutPolicy,
    pub payload_security: PayloadSecurity,
    pub timing: TimingPolicy,
}

impl ChannelContract {
    pub fn validate(&self) -> Result<(), ValidationError> {
        if self.max_buffered_bytes == 0 {
            return Err(ValidationError::Unbounded {
                field: "channel.max_buffered_bytes",
            });
        }
        if self.mode == PortMode::ReliableMessages
            && self.max_message_bytes.is_none_or(|limit| limit == 0)
        {
            return Err(ValidationError::Unbounded {
                field: "channel.max_message_bytes",
            });
        }
        if self
            .max_message_bytes
            .is_some_and(|limit| limit > self.max_buffered_bytes)
        {
            return Err(ValidationError::InvalidValue {
                field: "channel.max_message_bytes",
                reason: "exceeds max_buffered_bytes",
            });
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ChannelBinding {
    pub id: ChannelId,
    pub sources: Vec<PortRef>,
    pub destinations: Vec<PortRef>,
    pub contract: ChannelContract,
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct LocalInstant(pub u64);

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct GroupInstant(pub i128);

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ClockDomainRef {
    pub id: ClockDomainId,
    pub epoch: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct SessionExtensions {
    pub clock: Option<ClockDomainRef>,
    pub barrier_enabled: bool,
    pub opaque_ability_config: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct SessionPlan {
    pub session_id: SessionId,
    pub contract: AbilityContractRef,
    pub epoch: u64,
    pub coordinator: ParticipantId,
    pub participants: Vec<Participant>,
    pub channels: Vec<ChannelBinding>,
    pub extensions: SessionExtensions,
    pub policy_snapshot: PolicySnapshotId,
}

impl SessionPlan {
    pub fn validate(&self) -> Result<(), ValidationError> {
        if self.epoch == 0 {
            return Err(ValidationError::InvalidValue {
                field: "session.epoch",
                reason: "must start at one",
            });
        }
        if !self
            .participants
            .iter()
            .any(|participant| participant.id == self.coordinator)
        {
            return Err(ValidationError::InvalidValue {
                field: "session.coordinator",
                reason: "not a participant",
            });
        }
        for channel in &self.channels {
            if channel.sources.is_empty() || channel.destinations.is_empty() {
                return Err(ValidationError::InvalidValue {
                    field: "session.channel",
                    reason: "sources and destinations are required",
                });
            }
            channel.contract.validate()?;
            for endpoint in channel.sources.iter().chain(&channel.destinations) {
                let bound = self.participants.iter().any(|participant| {
                    participant.id == endpoint.participant
                        && participant
                            .port_bindings
                            .iter()
                            .any(|port| port.id == endpoint.port)
                });
                if !bound {
                    return Err(ValidationError::InvalidValue {
                        field: "session.channel.endpoint",
                        reason: "port is not bound",
                    });
                }
            }
        }
        Ok(())
    }

    /// Hashes a canonical ordering without mutating the plan or relying on serializer details.
    #[must_use]
    pub fn canonical_hash(&self) -> [u8; 32] {
        let mut out = Canonical::default();
        out.uuid(self.session_id.0.as_bytes());
        out.string(self.contract.key.namespace.as_str());
        out.string(self.contract.key.name.as_str());
        out.u64(u64::from(self.contract.key.major));
        out.bytes(&self.contract.protocol_hash);
        out.u64(self.epoch);
        out.uuid(self.coordinator.0.as_bytes());

        let mut participants: Vec<_> = self.participants.iter().collect();
        participants.sort_by_key(|participant| participant.id);
        out.u64(participants.len() as u64);
        for participant in participants {
            encode_participant(&mut out, participant);
        }

        let mut channels: Vec<_> = self.channels.iter().collect();
        channels.sort_by_key(|channel| channel.id);
        out.u64(channels.len() as u64);
        for channel in channels {
            encode_channel(&mut out, channel);
        }

        match &self.extensions.clock {
            Some(clock) => {
                out.u8(1);
                out.uuid(clock.id.0.as_bytes());
                out.u64(clock.epoch);
            }
            None => out.u8(0),
        }
        out.u8(u8::from(self.extensions.barrier_enabled));
        out.bytes(&self.extensions.opaque_ability_config);
        out.uuid(self.policy_snapshot.0.as_bytes());
        out.finish()
    }
}

fn encode_participant(out: &mut Canonical, participant: &Participant) {
    out.uuid(participant.id.0.as_bytes());
    out.bytes(&participant.device_id.0);
    out.u8(participant.app_principal.platform as u8);
    out.string(&participant.app_principal.stable_app_id);
    out.option_string(participant.app_principal.publisher_id.as_deref());
    match participant.app_principal.signing_digest {
        Some(value) => {
            out.u8(1);
            out.bytes(&value);
        }
        None => out.u8(0),
    }
    out.string(&participant.app_principal.os_subject.0);
    out.uuid(participant.ability_instance.0.as_bytes());
    out.string(&participant.role.0);
    let mut ports: Vec<_> = participant.port_bindings.iter().collect();
    ports.sort_by_key(|port| port.id);
    out.u64(ports.len() as u64);
    for port in ports {
        out.uuid(port.id.0.as_bytes());
        out.string(&port.declaration_id);
    }
}

fn encode_channel(out: &mut Canonical, channel: &ChannelBinding) {
    out.uuid(channel.id.0.as_bytes());
    let mut sources = channel.sources.clone();
    let mut destinations = channel.destinations.clone();
    sources.sort();
    destinations.sort();
    encode_ports(out, &sources);
    encode_ports(out, &destinations);
    let contract = &channel.contract;
    out.u8(contract.mode as u8);
    out.u8(contract.delivery as u8);
    out.u8(contract.priority);
    out.option_u64(contract.max_message_bytes);
    out.u64(contract.max_buffered_bytes);
    out.option_u64(contract.latency_target_ms.map(u64::from));
    out.option_u64(contract.idle_timeout_ms);
    out.u8(contract.fanout as u8);
    match &contract.payload_security {
        PayloadSecurity::HubReadable => out.u8(0),
        PayloadSecurity::EndToEnd { suite, membership } => {
            out.u8(1);
            out.string(&suite.0);
            out.u8(*membership as u8);
        }
    }
    out.u8(contract.timing as u8);
}

fn encode_ports(out: &mut Canonical, ports: &[PortRef]) {
    out.u64(ports.len() as u64);
    for port in ports {
        out.uuid(port.participant.0.as_bytes());
        out.uuid(port.port.0.as_bytes());
    }
}

#[derive(Default)]
struct Canonical(Sha256);

impl Canonical {
    fn u8(&mut self, value: u8) {
        self.0.update([value]);
    }
    fn u64(&mut self, value: u64) {
        self.0.update(value.to_be_bytes());
    }
    fn bytes(&mut self, value: &[u8]) {
        self.u64(value.len() as u64);
        self.0.update(value);
    }
    fn uuid(&mut self, value: &[u8; 16]) {
        self.0.update(value);
    }
    fn string(&mut self, value: &str) {
        self.bytes(value.as_bytes());
    }
    fn option_string(&mut self, value: Option<&str>) {
        match value {
            Some(value) => {
                self.u8(1);
                self.string(value);
            }
            None => self.u8(0),
        }
    }
    fn option_u64(&mut self, value: Option<u64>) {
        match value {
            Some(value) => {
                self.u8(1);
                self.u64(value);
            }
            None => self.u8(0),
        }
    }
    fn finish(self) -> [u8; 32] {
        self.0.finalize().into()
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum SessionState {
    Proposed,
    Negotiating,
    Preparing,
    Active,
    Reconfiguring,
    Suspended,
    Closing,
    Closed,
    Failed,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct EnvelopeFlags(pub u16);

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct EnvelopeHeader {
    pub session_id: SessionId,
    pub epoch: u64,
    pub channel_id: ChannelId,
    pub sender: ParticipantId,
    pub sequence: Option<u64>,
    pub created_at: Option<GroupInstant>,
    pub deadline: Option<GroupInstant>,
    pub presentation_at: Option<GroupInstant>,
    pub flags: EnvelopeFlags,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AbilityKey, AbilityName, Namespace, OsSubject, Platform};

    fn plan() -> SessionPlan {
        let a = ParticipantId::new();
        let b = ParticipantId::new();
        let a_port = PortId::new();
        let b_port = PortId::new();
        let principal = AppPrincipal {
            platform: Platform::Test,
            stable_app_id: "test.app".into(),
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject("test:1".into()),
        };
        SessionPlan {
            session_id: SessionId::new(),
            contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: [7; 32],
            },
            epoch: 1,
            coordinator: a,
            participants: vec![
                Participant {
                    id: a,
                    device_id: DeviceId([1; 32]),
                    app_principal: principal.clone(),
                    ability_instance: AbilityInstanceId::new(),
                    role: RoleId::new("sender").unwrap(),
                    port_bindings: vec![PortBinding {
                        id: a_port,
                        declaration_id: "out".into(),
                    }],
                },
                Participant {
                    id: b,
                    device_id: DeviceId([2; 32]),
                    app_principal: principal,
                    ability_instance: AbilityInstanceId::new(),
                    role: RoleId::new("receiver").unwrap(),
                    port_bindings: vec![PortBinding {
                        id: b_port,
                        declaration_id: "in".into(),
                    }],
                },
            ],
            channels: vec![ChannelBinding {
                id: ChannelId::new(),
                sources: vec![PortRef {
                    participant: a,
                    port: a_port,
                }],
                destinations: vec![PortRef {
                    participant: b,
                    port: b_port,
                }],
                contract: ChannelContract {
                    mode: PortMode::ReliableMessages,
                    delivery: DeliverySemantics::ReliableOrdered,
                    priority: 1,
                    max_message_bytes: Some(1024),
                    max_buffered_bytes: 4096,
                    latency_target_ms: None,
                    idle_timeout_ms: None,
                    fanout: FanoutPolicy::All,
                    payload_security: PayloadSecurity::HubReadable,
                    timing: TimingPolicy::None,
                },
            }],
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        }
    }

    #[test]
    fn canonical_hash_ignores_collection_insertion_order() {
        let first = plan();
        let mut second = first.clone();
        second.participants.reverse();
        second.channels[0].sources.reverse();
        assert_eq!(first.canonical_hash(), second.canonical_hash());
    }

    #[test]
    fn validates_bounded_channels() {
        let mut value = plan();
        value.channels[0].contract.max_buffered_bytes = 0;
        assert!(value.validate().is_err());
    }
}
