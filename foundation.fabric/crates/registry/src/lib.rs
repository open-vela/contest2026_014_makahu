//! Atomic in-memory Offer/Requirement registry with revisions and leases.

mod peer_sync;
pub use peer_sync::*;

use std::{collections::BTreeMap, sync::Mutex};

use fabric_core::{
    AbilityInstanceId, AbilityOffer, AbilityRequirement, ConnectionId, LeaseSpec, RequirementId,
};
use thiserror::Error;

#[derive(Clone, Copy, Debug, Default, Eq, Ord, PartialEq, PartialOrd)]
pub struct RegistryRevision(pub u64);

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RegistryEvent {
    OfferUpsert {
        revision: RegistryRevision,
        offer: AbilityOffer,
    },
    OfferRemove {
        revision: RegistryRevision,
        instance_id: AbilityInstanceId,
    },
    RequirementUpsert {
        revision: RegistryRevision,
        requirement: AbilityRequirement,
    },
    RequirementRemove {
        revision: RegistryRevision,
        requirement_id: RequirementId,
    },
}

impl RegistryEvent {
    #[must_use]
    pub const fn revision(&self) -> RegistryRevision {
        match self {
            Self::OfferUpsert { revision, .. }
            | Self::OfferRemove { revision, .. }
            | Self::RequirementUpsert { revision, .. }
            | Self::RequirementRemove { revision, .. } => *revision,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RegistrySnapshot {
    pub revision: RegistryRevision,
    pub offers: Vec<AbilityOffer>,
    pub requirements: Vec<AbilityRequirement>,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum RegistryError {
    #[error("record not found")]
    NotFound,
    #[error("record belongs to another connection")]
    WrongOwner,
    #[error("registry lock was poisoned")]
    Poisoned,
    #[error("revision overflow")]
    RevisionOverflow,
}

#[derive(Clone)]
struct Record<T> {
    value: T,
    owner: ConnectionId,
    expires_at_ms: Option<u64>,
}

#[derive(Default)]
struct State {
    revision: RegistryRevision,
    offers: BTreeMap<AbilityInstanceId, Record<AbilityOffer>>,
    requirements: BTreeMap<RequirementId, Record<AbilityRequirement>>,
    events: Vec<RegistryEvent>,
}

#[derive(Default)]
pub struct Registry {
    state: Mutex<State>,
}

impl Registry {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn upsert_offer(
        &self,
        owner: ConnectionId,
        offer: AbilityOffer,
        now_ms: u64,
    ) -> Result<RegistryRevision, RegistryError> {
        let mut state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        if state
            .offers
            .get(&offer.instance_id)
            .is_some_and(|record| record.owner != owner)
        {
            return Err(RegistryError::WrongOwner);
        }
        let revision = next_revision(&mut state)?;
        let expires_at_ms = expiry(offer.lease, now_ms);
        state.offers.insert(
            offer.instance_id,
            Record {
                value: offer.clone(),
                owner,
                expires_at_ms,
            },
        );
        state
            .events
            .push(RegistryEvent::OfferUpsert { revision, offer });
        Ok(revision)
    }

    pub fn upsert_requirement(
        &self,
        owner: ConnectionId,
        requirement: AbilityRequirement,
        now_ms: u64,
    ) -> Result<RegistryRevision, RegistryError> {
        let mut state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        if state
            .requirements
            .get(&requirement.requirement_id)
            .is_some_and(|record| record.owner != owner)
        {
            return Err(RegistryError::WrongOwner);
        }
        let revision = next_revision(&mut state)?;
        let expires_at_ms = expiry(requirement.lease, now_ms);
        state.requirements.insert(
            requirement.requirement_id,
            Record {
                value: requirement.clone(),
                owner,
                expires_at_ms,
            },
        );
        state.events.push(RegistryEvent::RequirementUpsert {
            revision,
            requirement,
        });
        Ok(revision)
    }

    pub fn renew_offer(
        &self,
        owner: ConnectionId,
        id: AbilityInstanceId,
        now_ms: u64,
    ) -> Result<RegistryRevision, RegistryError> {
        let mut state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        let record = state.offers.get(&id).ok_or(RegistryError::NotFound)?;
        if record.owner != owner {
            return Err(RegistryError::WrongOwner);
        }
        let offer = record.value.clone();
        let expires_at_ms = expiry(offer.lease, now_ms);
        let revision = next_revision(&mut state)?;
        state
            .offers
            .get_mut(&id)
            .ok_or(RegistryError::NotFound)?
            .expires_at_ms = expires_at_ms;
        state
            .events
            .push(RegistryEvent::OfferUpsert { revision, offer });
        Ok(revision)
    }

    pub fn disconnect(&self, owner: ConnectionId) -> Result<Vec<RegistryEvent>, RegistryError> {
        let mut state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        let offer_ids: Vec<_> = state
            .offers
            .iter()
            .filter(|(_, record)| {
                record.owner == owner && record.value.lease == LeaseSpec::ConnectionBound
            })
            .map(|(id, _)| *id)
            .collect();
        let requirement_ids: Vec<_> = state
            .requirements
            .iter()
            .filter(|(_, record)| {
                record.owner == owner && record.value.lease == LeaseSpec::ConnectionBound
            })
            .map(|(id, _)| *id)
            .collect();
        remove_records(&mut state, &offer_ids, &requirement_ids)
    }

    pub fn expire(&self, now_ms: u64) -> Result<Vec<RegistryEvent>, RegistryError> {
        let mut state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        let offer_ids: Vec<_> = state
            .offers
            .iter()
            .filter(|(_, record)| {
                record
                    .expires_at_ms
                    .is_some_and(|deadline| deadline <= now_ms)
            })
            .map(|(id, _)| *id)
            .collect();
        let requirement_ids: Vec<_> = state
            .requirements
            .iter()
            .filter(|(_, record)| {
                record
                    .expires_at_ms
                    .is_some_and(|deadline| deadline <= now_ms)
            })
            .map(|(id, _)| *id)
            .collect();
        remove_records(&mut state, &offer_ids, &requirement_ids)
    }

    pub fn snapshot(&self) -> Result<RegistrySnapshot, RegistryError> {
        let state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        Ok(RegistrySnapshot {
            revision: state.revision,
            offers: state
                .offers
                .values()
                .map(|record| record.value.clone())
                .collect(),
            requirements: state
                .requirements
                .values()
                .map(|record| record.value.clone())
                .collect(),
        })
    }

    pub fn delta_since(
        &self,
        revision: RegistryRevision,
    ) -> Result<Vec<RegistryEvent>, RegistryError> {
        let state = self.state.lock().map_err(|_| RegistryError::Poisoned)?;
        Ok(state
            .events
            .iter()
            .filter(|event| event.revision() > revision)
            .cloned()
            .collect())
    }
}

fn expiry(lease: LeaseSpec, now_ms: u64) -> Option<u64> {
    match lease {
        LeaseSpec::ConnectionBound => None,
        LeaseSpec::Renewable { ttl_ms } => Some(now_ms.saturating_add(ttl_ms)),
    }
}

fn next_revision(state: &mut State) -> Result<RegistryRevision, RegistryError> {
    state.revision.0 = state
        .revision
        .0
        .checked_add(1)
        .ok_or(RegistryError::RevisionOverflow)?;
    Ok(state.revision)
}

fn remove_records(
    state: &mut State,
    offers: &[AbilityInstanceId],
    requirements: &[RequirementId],
) -> Result<Vec<RegistryEvent>, RegistryError> {
    let start = state.events.len();
    for id in offers {
        state.offers.remove(id);
        let revision = next_revision(state)?;
        state.events.push(RegistryEvent::OfferRemove {
            revision,
            instance_id: *id,
        });
    }
    for id in requirements {
        state.requirements.remove(id);
        let revision = next_revision(state)?;
        state.events.push(RegistryEvent::RequirementRemove {
            revision,
            requirement_id: *id,
        });
    }
    Ok(state.events[start..].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;
    use std::sync::Arc;

    fn offer(lease: LeaseSpec) -> AbilityOffer {
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
            lease,
        }
    }

    #[test]
    fn expiration_emits_remove_event() {
        let registry = Registry::new();
        let owner = ConnectionId::new();
        let offer = offer(LeaseSpec::Renewable { ttl_ms: 10 });
        let id = offer.instance_id;
        registry.upsert_offer(owner, offer, 100).unwrap();
        let events = registry.expire(110).unwrap();
        assert!(
            matches!(events.as_slice(), [RegistryEvent::OfferRemove { instance_id, .. }] if *instance_id == id)
        );
        assert!(registry.snapshot().unwrap().offers.is_empty());
    }

    #[test]
    fn concurrent_updates_never_lose_revisions() {
        let registry = Arc::new(Registry::new());
        let threads: Vec<_> = (0..16)
            .map(|_| {
                let registry = Arc::clone(&registry);
                std::thread::spawn(move || {
                    registry
                        .upsert_offer(ConnectionId::new(), offer(LeaseSpec::ConnectionBound), 0)
                        .unwrap()
                })
            })
            .collect();
        for thread in threads {
            thread.join().unwrap();
        }
        let snapshot = registry.snapshot().unwrap();
        assert_eq!(snapshot.revision, RegistryRevision(16));
        assert_eq!(snapshot.offers.len(), 16);
        let revisions: Vec<_> = registry
            .delta_since(RegistryRevision(0))
            .unwrap()
            .into_iter()
            .map(|event| event.revision().0)
            .collect();
        assert_eq!(revisions, (1..=16).collect::<Vec<_>>());
    }
}
