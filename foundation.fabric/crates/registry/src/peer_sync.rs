use std::collections::BTreeMap;

use fabric_core::{AbilityInstanceId, AbilityOffer};

use crate::{RegistryRevision, RegistrySnapshot};

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PeerOfferDelta {
    Upsert {
        revision: RegistryRevision,
        offer: Box<AbilityOffer>,
    },
    Remove {
        revision: RegistryRevision,
        instance_id: AbilityInstanceId,
    },
}

impl PeerOfferDelta {
    const fn revision(&self) -> RegistryRevision {
        match self {
            Self::Upsert { revision, .. } | Self::Remove { revision, .. } => *revision,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum SyncError {
    UntrustedPeer,
    ResyncRequired {
        applied: RegistryRevision,
        received: RegistryRevision,
    },
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PeerRegistry {
    pub applied_revision: RegistryRevision,
    pub offers: BTreeMap<AbilityInstanceId, AbilityOffer>,
    pub expires_at_ms: Option<u64>,
}

impl PeerRegistry {
    pub fn apply_snapshot(
        &mut self,
        trusted: bool,
        revision: RegistryRevision,
        offers: Vec<AbilityOffer>,
    ) -> Result<(), SyncError> {
        if !trusted {
            return Err(SyncError::UntrustedPeer);
        }
        self.offers = offers
            .into_iter()
            .map(|offer| (offer.instance_id, offer))
            .collect();
        self.applied_revision = revision;
        self.expires_at_ms = None;
        Ok(())
    }

    pub fn apply_delta(&mut self, trusted: bool, delta: PeerOfferDelta) -> Result<(), SyncError> {
        if !trusted {
            return Err(SyncError::UntrustedPeer);
        }
        let received = delta.revision();
        if received.0 != self.applied_revision.0 + 1 {
            return Err(SyncError::ResyncRequired {
                applied: self.applied_revision,
                received,
            });
        }
        match delta {
            PeerOfferDelta::Upsert { offer, .. } => {
                self.offers.insert(offer.instance_id, *offer);
            }
            PeerOfferDelta::Remove { instance_id, .. } => {
                self.offers.remove(&instance_id);
            }
        }
        self.applied_revision = received;
        Ok(())
    }

    pub fn disconnected(&mut self, now_ms: u64, cache_ttl_ms: u64) {
        self.expires_at_ms = Some(now_ms.saturating_add(cache_ttl_ms));
    }
    pub fn expire_cache(&mut self, now_ms: u64) -> bool {
        if self
            .expires_at_ms
            .is_some_and(|deadline| deadline <= now_ms)
        {
            self.offers.clear();
            self.expires_at_ms = None;
            return true;
        }
        false
    }
}

#[must_use]
pub fn filtered_snapshot(
    snapshot: &RegistrySnapshot,
    trusted: bool,
    permits: impl Fn(&AbilityOffer) -> bool,
) -> (RegistryRevision, Vec<AbilityOffer>) {
    if !trusted {
        return (snapshot.revision, Vec::new());
    }
    (
        snapshot.revision,
        snapshot
            .offers
            .iter()
            .filter(|offer| permits(offer))
            .cloned()
            .collect(),
    )
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

    #[test]
    fn untrusted_peer_receives_zero_offers_and_gap_requests_resync() {
        let snapshot = RegistrySnapshot {
            revision: RegistryRevision(4),
            offers: vec![offer()],
            requirements: vec![],
        };
        assert!(filtered_snapshot(&snapshot, false, |_| true).1.is_empty());
        let mut peer = PeerRegistry::default();
        peer.apply_snapshot(true, RegistryRevision(4), snapshot.offers)
            .unwrap();
        let error = peer
            .apply_delta(
                true,
                PeerOfferDelta::Remove {
                    revision: RegistryRevision(6),
                    instance_id: AbilityInstanceId::new(),
                },
            )
            .unwrap_err();
        assert_eq!(
            error,
            SyncError::ResyncRequired {
                applied: RegistryRevision(4),
                received: RegistryRevision(6)
            }
        );
    }
}
