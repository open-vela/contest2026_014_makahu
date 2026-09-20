//! Opaque byte routing with graph compilation, bounded queues, and weighted fairness.

use std::collections::{BTreeMap, VecDeque};

use bytes::Bytes;
use fabric_core::{
    ChannelContract, ChannelId, DeviceId, ParticipantId, PortId, PortMode, PortRef, SessionPlan,
};
use thiserror::Error;

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct LocalPortEndpoint {
    pub participant: ParticipantId,
    pub port: PortId,
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct RemoteEndpoint {
    pub device: DeviceId,
    pub participant: ParticipantId,
    pub port: PortId,
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum RouteEndpoint {
    Local(LocalPortEndpoint),
    Remote(RemoteEndpoint),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CompiledRoute {
    pub channel_id: ChannelId,
    pub local_sources: Vec<LocalPortEndpoint>,
    pub remote_sources: Vec<RemoteEndpoint>,
    pub local_destinations: Vec<LocalPortEndpoint>,
    pub remote_destinations: Vec<RemoteEndpoint>,
    pub contract: ChannelContract,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DatagramOverflow {
    DropNewest,
    DropOldest,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Delivery {
    pub channel_id: ChannelId,
    pub sender: ParticipantId,
    pub destination: RouteEndpoint,
    pub payload: Bytes,
}

impl Delivery {
    #[must_use]
    pub fn byte_len(&self) -> u64 {
        self.payload.len() as u64
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct RouteStats {
    pub buffered_bytes: u64,
    pub datagrams_dropped: u64,
    pub messages_enqueued: u64,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum RouterError {
    #[error("Session plan is invalid: {0}")]
    InvalidPlan(#[from] fabric_core::ValidationError),
    #[error("channel is not compiled")]
    ChannelNotBound,
    #[error("sender is not a source for this channel")]
    InvalidSource,
    #[error("stale epoch: expected {expected}, got {actual}")]
    StaleEpoch { expected: u64, actual: u64 },
    #[error("message exceeds the negotiated size limit")]
    MessageTooLarge,
    #[error("reliable destination queue is full")]
    Backpressure,
    #[error("buffer accounting overflow")]
    AccountingOverflow,
}

#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct QueueKey {
    channel: ChannelId,
    destination: RouteEndpoint,
}

#[derive(Default)]
struct DestinationQueue {
    deliveries: VecDeque<Delivery>,
    buffered_bytes: u64,
    deficit: u64,
}

pub struct InMemoryRouter {
    epoch: u64,
    routes: BTreeMap<ChannelId, CompiledRoute>,
    queues: BTreeMap<QueueKey, DestinationQueue>,
    control: VecDeque<Bytes>,
    datagram_overflow: DatagramOverflow,
    stats: RouteStats,
    cursor: usize,
}

impl InMemoryRouter {
    pub fn compile(
        plan: &SessionPlan,
        local_device: DeviceId,
        datagram_overflow: DatagramOverflow,
    ) -> Result<Self, RouterError> {
        plan.validate()?;
        let devices: BTreeMap<_, _> = plan
            .participants
            .iter()
            .map(|item| (item.id, item.device_id))
            .collect();
        let mut routes = BTreeMap::new();
        let mut queues = BTreeMap::new();
        for channel in &plan.channels {
            let (local_sources, remote_sources) =
                classify(&channel.sources, &devices, local_device)?;
            let (local_destinations, remote_destinations) =
                classify(&channel.destinations, &devices, local_device)?;
            let route = CompiledRoute {
                channel_id: channel.id,
                local_sources,
                remote_sources,
                local_destinations,
                remote_destinations,
                contract: channel.contract.clone(),
            };
            for destination in destinations(&route) {
                queues.insert(
                    QueueKey {
                        channel: channel.id,
                        destination,
                    },
                    DestinationQueue::default(),
                );
            }
            routes.insert(channel.id, route);
        }
        Ok(Self {
            epoch: plan.epoch,
            routes,
            queues,
            control: VecDeque::new(),
            datagram_overflow,
            stats: RouteStats::default(),
            cursor: 0,
        })
    }

    #[must_use]
    pub const fn epoch(&self) -> u64 {
        self.epoch
    }

    #[must_use]
    pub const fn stats(&self) -> RouteStats {
        self.stats
    }

    pub fn enqueue_control(&mut self, payload: Bytes) -> Result<(), RouterError> {
        let length = payload.len() as u64;
        self.stats.buffered_bytes = self
            .stats
            .buffered_bytes
            .checked_add(length)
            .ok_or(RouterError::AccountingOverflow)?;
        self.control.push_back(payload);
        Ok(())
    }

    #[allow(clippy::needless_pass_by_value)]
    pub fn enqueue(
        &mut self,
        epoch: u64,
        channel_id: ChannelId,
        sender: ParticipantId,
        payload: Bytes,
    ) -> Result<(), RouterError> {
        if epoch != self.epoch {
            return Err(RouterError::StaleEpoch {
                expected: self.epoch,
                actual: epoch,
            });
        }
        let route = self
            .routes
            .get(&channel_id)
            .ok_or(RouterError::ChannelNotBound)?;
        if !route
            .local_sources
            .iter()
            .any(|item| item.participant == sender)
            && !route
                .remote_sources
                .iter()
                .any(|item| item.participant == sender)
        {
            return Err(RouterError::InvalidSource);
        }
        let byte_len = payload.len() as u64;
        if route.contract.mode == PortMode::ReliableMessages
            && route
                .contract
                .max_message_bytes
                .is_some_and(|limit| byte_len > limit)
        {
            return Err(RouterError::MessageTooLarge);
        }
        let destinations = destinations(route);
        let contract = route.contract.clone();
        if contract.mode != PortMode::Datagram {
            self.preflight_reliable(channel_id, &destinations, &contract, byte_len)?;
        }
        for destination in destinations {
            let key = QueueKey {
                channel: channel_id,
                destination: destination.clone(),
            };
            self.enqueue_destination(&key, &contract, sender, &payload)?;
        }
        Ok(())
    }

    fn preflight_reliable(
        &self,
        channel: ChannelId,
        destinations: &[RouteEndpoint],
        contract: &ChannelContract,
        byte_len: u64,
    ) -> Result<(), RouterError> {
        for destination in destinations {
            let key = QueueKey {
                channel,
                destination: destination.clone(),
            };
            let queue = self.queues.get(&key).ok_or(RouterError::ChannelNotBound)?;
            let buffered = queue
                .buffered_bytes
                .checked_add(byte_len)
                .ok_or(RouterError::AccountingOverflow)?;
            if buffered > contract.max_buffered_bytes {
                return Err(RouterError::Backpressure);
            }
        }
        let destination_count =
            u64::try_from(destinations.len()).map_err(|_| RouterError::AccountingOverflow)?;
        let added = byte_len
            .checked_mul(destination_count)
            .ok_or(RouterError::AccountingOverflow)?;
        self.stats
            .buffered_bytes
            .checked_add(added)
            .ok_or(RouterError::AccountingOverflow)?;
        Ok(())
    }

    fn enqueue_destination(
        &mut self,
        key: &QueueKey,
        contract: &ChannelContract,
        sender: ParticipantId,
        payload: &Bytes,
    ) -> Result<(), RouterError> {
        let byte_len = payload.len() as u64;
        let queue = self
            .queues
            .get_mut(key)
            .ok_or(RouterError::ChannelNotBound)?;
        if queue.buffered_bytes.saturating_add(byte_len) > contract.max_buffered_bytes {
            if contract.mode != PortMode::Datagram
                || self.datagram_overflow == DatagramOverflow::DropNewest
            {
                if contract.mode == PortMode::Datagram {
                    self.stats.datagrams_dropped += 1;
                    return Ok(());
                }
                return Err(RouterError::Backpressure);
            }
            while queue.buffered_bytes.saturating_add(byte_len) > contract.max_buffered_bytes {
                let Some(dropped) = queue.deliveries.pop_front() else {
                    break;
                };
                let dropped_len = dropped.byte_len();
                queue.buffered_bytes -= dropped_len;
                self.stats.buffered_bytes -= dropped_len;
                self.stats.datagrams_dropped += 1;
            }
            if byte_len > contract.max_buffered_bytes {
                self.stats.datagrams_dropped += 1;
                return Ok(());
            }
        }
        queue.buffered_bytes = queue
            .buffered_bytes
            .checked_add(byte_len)
            .ok_or(RouterError::AccountingOverflow)?;
        self.stats.buffered_bytes = self
            .stats
            .buffered_bytes
            .checked_add(byte_len)
            .ok_or(RouterError::AccountingOverflow)?;
        self.stats.messages_enqueued += 1;
        queue.deliveries.push_back(Delivery {
            channel_id: key.channel,
            sender,
            destination: key.destination.clone(),
            payload: payload.clone(),
        });
        Ok(())
    }

    /// Control data has a reserved service opportunity before weighted data queues.
    pub fn dequeue_next(&mut self) -> Option<Result<Delivery, Bytes>> {
        if let Some(payload) = self.control.pop_front() {
            self.stats.buffered_bytes -= payload.len() as u64;
            return Some(Err(payload));
        }
        let keys: Vec<_> = self.queues.keys().cloned().collect();
        if keys.is_empty() {
            return None;
        }
        for offset in 0..keys.len() {
            let index = (self.cursor + offset) % keys.len();
            let key = &keys[index];
            let Some(route) = self.routes.get(&key.channel) else {
                continue;
            };
            let priority = route.contract.priority;
            let Some(queue) = self.queues.get_mut(key) else {
                continue;
            };
            queue.deficit = queue.deficit.saturating_add(priority_quantum(priority));
            let Some(front) = queue.deliveries.front() else {
                continue;
            };
            if front.byte_len() > queue.deficit {
                continue;
            }
            let Some(delivery) = queue.deliveries.pop_front() else {
                continue;
            };
            let byte_len = delivery.byte_len();
            queue.deficit -= byte_len;
            queue.buffered_bytes -= byte_len;
            self.stats.buffered_bytes -= byte_len;
            self.cursor = (index + 1) % keys.len();
            return Some(Ok(delivery));
        }
        None
    }
}

fn priority_quantum(priority: u8) -> u64 {
    u64::from(256 - u16::from(priority)) * 1024
}

fn destinations(route: &CompiledRoute) -> Vec<RouteEndpoint> {
    route
        .local_destinations
        .iter()
        .cloned()
        .map(RouteEndpoint::Local)
        .chain(
            route
                .remote_destinations
                .iter()
                .cloned()
                .map(RouteEndpoint::Remote),
        )
        .collect()
}

fn classify(
    endpoints: &[PortRef],
    devices: &BTreeMap<ParticipantId, DeviceId>,
    local_device: DeviceId,
) -> Result<(Vec<LocalPortEndpoint>, Vec<RemoteEndpoint>), RouterError> {
    let mut local = Vec::new();
    let mut remote = Vec::new();
    for endpoint in endpoints {
        let device = *devices
            .get(&endpoint.participant)
            .ok_or(RouterError::InvalidSource)?;
        if device == local_device {
            local.push(LocalPortEndpoint {
                participant: endpoint.participant,
                port: endpoint.port,
            });
        } else {
            remote.push(RemoteEndpoint {
                device,
                participant: endpoint.participant,
                port: endpoint.port,
            });
        }
    }
    local.sort();
    remote.sort();
    Ok((local, remote))
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;

    fn plan(
        destinations_count: usize,
        mode: PortMode,
        max_buffered_bytes: u64,
    ) -> (SessionPlan, ParticipantId, ChannelId) {
        let source = ParticipantId::new();
        let source_port = PortId::new();
        let channel = ChannelId::new();
        let principal = AppPrincipal {
            platform: Platform::Test,
            stable_app_id: "test.app".into(),
            publisher_id: None,
            signing_digest: None,
            os_subject: OsSubject("test:1".into()),
        };
        let mut participants = vec![Participant {
            id: source,
            device_id: DeviceId([1; 32]),
            app_principal: principal.clone(),
            ability_instance: AbilityInstanceId::new(),
            role: RoleId::new("source").unwrap(),
            port_bindings: vec![PortBinding {
                id: source_port,
                declaration_id: "out".into(),
            }],
        }];
        let mut destination_refs = Vec::new();
        for index in 0..destinations_count {
            let id = ParticipantId::new();
            let port = PortId::new();
            participants.push(Participant {
                id,
                device_id: DeviceId([u8::try_from(index + 2).unwrap(); 32]),
                app_principal: principal.clone(),
                ability_instance: AbilityInstanceId::new(),
                role: RoleId::new("destination").unwrap(),
                port_bindings: vec![PortBinding {
                    id: port,
                    declaration_id: "in".into(),
                }],
            });
            destination_refs.push(PortRef {
                participant: id,
                port,
            });
        }
        (
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
                coordinator: source,
                participants,
                channels: vec![ChannelBinding {
                    id: channel,
                    sources: vec![PortRef {
                        participant: source,
                        port: source_port,
                    }],
                    destinations: destination_refs,
                    contract: ChannelContract {
                        mode,
                        delivery: if mode == PortMode::Datagram {
                            DeliverySemantics::BestEffort
                        } else {
                            DeliverySemantics::ReliableOrdered
                        },
                        priority: 10,
                        max_message_bytes: (mode == PortMode::ReliableMessages).then_some(1024),
                        max_buffered_bytes,
                        latency_target_ms: None,
                        idle_timeout_ms: None,
                        fanout: FanoutPolicy::All,
                        payload_security: PayloadSecurity::HubReadable,
                        timing: TimingPolicy::None,
                    },
                }],
                extensions: SessionExtensions::default(),
                policy_snapshot: PolicySnapshotId::new(),
            },
            source,
            channel,
        )
    }

    #[test]
    fn fans_out_one_source_to_many_destinations() {
        let (plan, sender, channel) = plan(4, PortMode::ReliableMessages, 4096);
        let mut router =
            InMemoryRouter::compile(&plan, DeviceId([1; 32]), DatagramOverflow::DropNewest)
                .unwrap();
        router
            .enqueue(1, channel, sender, Bytes::from_static(b"opaque"))
            .unwrap();
        let deliveries: Vec<_> = std::iter::from_fn(|| router.dequeue_next()).collect();
        assert_eq!(deliveries.len(), 4);
    }

    #[test]
    fn queues_remain_bounded_and_stale_epoch_is_rejected() {
        let (plan, sender, channel) = plan(1, PortMode::Datagram, 4);
        let mut router =
            InMemoryRouter::compile(&plan, DeviceId([1; 32]), DatagramOverflow::DropOldest)
                .unwrap();
        router
            .enqueue(1, channel, sender, Bytes::from_static(b"1234"))
            .unwrap();
        router
            .enqueue(1, channel, sender, Bytes::from_static(b"5678"))
            .unwrap();
        assert_eq!(router.stats().buffered_bytes, 4);
        assert_eq!(router.stats().datagrams_dropped, 1);
        assert!(matches!(
            router.enqueue(0, channel, sender, Bytes::new()),
            Err(RouterError::StaleEpoch { .. })
        ));
    }

    #[test]
    fn control_has_reserved_service() {
        let (plan, sender, channel) = plan(1, PortMode::ReliableMessages, 4096);
        let mut router =
            InMemoryRouter::compile(&plan, DeviceId([1; 32]), DatagramOverflow::DropNewest)
                .unwrap();
        router
            .enqueue(1, channel, sender, Bytes::from_static(b"data"))
            .unwrap();
        router
            .enqueue_control(Bytes::from_static(b"control"))
            .unwrap();
        assert!(
            matches!(router.dequeue_next(), Some(Err(bytes)) if bytes == Bytes::from_static(b"control"))
        );
    }

    #[test]
    fn compiles_many_to_one_and_many_to_many_without_payload_schema() {
        let (mut plan, first_sender, channel) = plan(2, PortMode::ReliableMessages, 4096);
        let second_sender = plan.participants[1].id;
        let second_port = plan.participants[1].port_bindings[0].id;
        plan.channels[0].sources.push(PortRef {
            participant: second_sender,
            port: second_port,
        });
        let mut router =
            InMemoryRouter::compile(&plan, DeviceId([1; 32]), DatagramOverflow::DropNewest)
                .unwrap();
        router
            .enqueue(1, channel, first_sender, Bytes::from_static(b"a"))
            .unwrap();
        router
            .enqueue(1, channel, second_sender, Bytes::from_static(b"b"))
            .unwrap();
        let deliveries: Vec<_> = std::iter::from_fn(|| router.dequeue_next()).collect();
        assert_eq!(deliveries.len(), 4);
        assert!(deliveries.into_iter().all(|delivery| delivery.is_ok()));
    }

    #[test]
    fn reliable_fanout_backpressure_is_atomic() {
        let (mut plan, sender, channel) = plan(2, PortMode::ReliableMessages, 4);
        plan.channels[0].contract.max_message_bytes = Some(4);
        let mut router =
            InMemoryRouter::compile(&plan, DeviceId([1; 32]), DatagramOverflow::DropNewest)
                .unwrap();
        let keys: Vec<_> = router.queues.keys().cloned().collect();
        router.queues.get_mut(&keys[1]).unwrap().buffered_bytes = 4;
        router.stats.buffered_bytes = 4;

        assert_eq!(
            router.enqueue(1, channel, sender, Bytes::from_static(b"x")),
            Err(RouterError::Backpressure)
        );
        assert!(router.queues.get(&keys[0]).unwrap().deliveries.is_empty());
        assert_eq!(router.stats.buffered_bytes, 4);
    }
}
