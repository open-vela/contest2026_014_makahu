//! Deterministic, side-effect-free Offer matching.

use fabric_core::{
    AbilityInstanceId, AbilityOffer, AbilityRequirement, DeviceId, DeviceSelector, PropertyMap,
    PropertyPredicate, PropertyValue,
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CandidateOffer {
    pub device_id: DeviceId,
    pub trusted: bool,
    pub offer: AbilityOffer,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RejectionReason {
    AbilityKey,
    ProtocolHash,
    Role,
    PropertyPredicate,
    DeviceSelector,
    Policy,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RejectedCandidate {
    pub device_id: DeviceId,
    pub instance_id: AbilityInstanceId,
    pub reason: RejectionReason,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MatchReport {
    pub candidates: Vec<CandidateOffer>,
    pub rejected: Vec<RejectedCandidate>,
}

pub trait PolicyPrefilter {
    fn permits(&self, requirement: &AbilityRequirement, candidate: &CandidateOffer) -> bool;
}

impl<F> PolicyPrefilter for F
where
    F: Fn(&AbilityRequirement, &CandidateOffer) -> bool,
{
    fn permits(&self, requirement: &AbilityRequirement, candidate: &CandidateOffer) -> bool {
        self(requirement, candidate)
    }
}

#[must_use]
pub fn match_offers(
    requirement: &AbilityRequirement,
    offers: impl IntoIterator<Item = CandidateOffer>,
    local_device: DeviceId,
    policy: &impl PolicyPrefilter,
) -> MatchReport {
    let mut report = MatchReport::default();
    for candidate in offers {
        if let Some(reason) = reject_reason(requirement, &candidate, local_device, policy) {
            report.rejected.push(RejectedCandidate {
                device_id: candidate.device_id,
                instance_id: candidate.offer.instance_id,
                reason,
            });
        } else {
            report.candidates.push(candidate);
        }
    }
    report
        .candidates
        .sort_by_key(|candidate| (candidate.device_id, candidate.offer.instance_id));
    report
        .rejected
        .sort_by_key(|candidate| (candidate.device_id, candidate.instance_id));
    report
}

fn reject_reason(
    requirement: &AbilityRequirement,
    candidate: &CandidateOffer,
    local_device: DeviceId,
    policy: &impl PolicyPrefilter,
) -> Option<RejectionReason> {
    if candidate.offer.contract.key != requirement.selector.key {
        return Some(RejectionReason::AbilityKey);
    }
    if requirement
        .selector
        .protocol_hash
        .is_some_and(|hash| hash != candidate.offer.contract.protocol_hash)
    {
        return Some(RejectionReason::ProtocolHash);
    }
    if !candidate.offer.roles.contains(&requirement.desired_role) {
        return Some(RejectionReason::Role);
    }
    if !predicate_matches(&requirement.property_predicate, &candidate.offer.properties) {
        return Some(RejectionReason::PropertyPredicate);
    }
    let device_matches = match &requirement.device_selector {
        DeviceSelector::AnyTrusted => candidate.trusted,
        DeviceSelector::Exact(device) => candidate.device_id == *device,
        DeviceSelector::OneOf(devices) => devices.contains(&candidate.device_id),
        DeviceSelector::LocalOnly => candidate.device_id == local_device,
    };
    if !device_matches {
        return Some(RejectionReason::DeviceSelector);
    }
    if !policy.permits(requirement, candidate) {
        return Some(RejectionReason::Policy);
    }
    None
}

#[must_use]
pub fn predicate_matches(predicate: &PropertyPredicate, properties: &PropertyMap) -> bool {
    match predicate {
        PropertyPredicate::Any => true,
        PropertyPredicate::Exists(key) => properties.contains_key(key),
        PropertyPredicate::Equals(key, value) => properties.get(key) == Some(value),
        PropertyPredicate::ContainsString(key, expected) => {
            matches!(properties.get(key), Some(PropertyValue::StringSet(values)) if values.contains(expected))
        }
        PropertyPredicate::ContainsU64(key, expected) => {
            matches!(properties.get(key), Some(PropertyValue::U64Set(values)) if values.contains(expected))
        }
        PropertyPredicate::All(predicates) => predicates
            .iter()
            .all(|item| predicate_matches(item, properties)),
        PropertyPredicate::AnyOf(predicates) => predicates
            .iter()
            .any(|item| predicate_matches(item, properties)),
        PropertyPredicate::Not(inner) => !predicate_matches(inner, properties),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;
    use std::collections::BTreeSet;

    fn fixture(id: AbilityInstanceId, device_id: DeviceId) -> CandidateOffer {
        let key = AbilityKey {
            namespace: Namespace::new("com.example").unwrap(),
            name: AbilityName::new("echo").unwrap(),
            major: 1,
        };
        let mut properties = PropertyMap::new();
        properties.insert(
            PropertyKey::new("codec").unwrap(),
            PropertyValue::StringSet(BTreeSet::from(["opus".into()])),
        );
        CandidateOffer {
            device_id,
            trusted: true,
            offer: AbilityOffer {
                instance_id: id,
                contract: AbilityContractRef {
                    key,
                    protocol_hash: [1; 32],
                },
                app: AppPrincipal {
                    platform: Platform::Test,
                    stable_app_id: "test.app".into(),
                    publisher_id: None,
                    signing_digest: None,
                    os_subject: OsSubject("test:1".into()),
                },
                roles: vec![RoleId::new("provider").unwrap()],
                properties,
                visibility: OfferVisibility::TrustedDevices,
                access_policy: PolicyRef("default".into()),
                lease: LeaseSpec::ConnectionBound,
            },
        }
    }

    fn requirement() -> AbilityRequirement {
        AbilityRequirement {
            requirement_id: RequirementId::new(),
            selector: AbilitySelector {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: Some([1; 32]),
            },
            desired_role: RoleId::new("provider").unwrap(),
            property_predicate: PropertyPredicate::ContainsString(
                PropertyKey::new("codec").unwrap(),
                "opus".into(),
            ),
            device_selector: DeviceSelector::AnyTrusted,
            session_policy: SessionPolicy {
                minimum_participants: 1,
                maximum_participants: 4,
                allow_reconfiguration: true,
            },
            lease: LeaseSpec::ConnectionBound,
        }
    }

    #[test]
    fn output_is_deterministic_across_input_order() {
        let local = DeviceId([0; 32]);
        let a = fixture(AbilityInstanceId::new(), DeviceId([1; 32]));
        let b = fixture(AbilityInstanceId::new(), DeviceId([2; 32]));
        let allow = |_: &AbilityRequirement, _: &CandidateOffer| true;
        let first = match_offers(&requirement(), vec![a.clone(), b.clone()], local, &allow);
        let second = match_offers(&requirement(), vec![b, a], local, &allow);
        assert_eq!(first, second);
    }

    #[test]
    fn reports_policy_rejection() {
        let local = DeviceId([0; 32]);
        let candidate = fixture(AbilityInstanceId::new(), DeviceId([1; 32]));
        let deny = |_: &AbilityRequirement, _: &CandidateOffer| false;
        let report = match_offers(&requirement(), [candidate], local, &deny);
        assert_eq!(report.rejected[0].reason, RejectionReason::Policy);
    }
}
