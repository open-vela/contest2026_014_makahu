//! Monotonic group clock estimation and generic synchronization barriers.

use fabric_core::{
    BarrierId, ClockDomainId, DeviceId, GroupInstant, LocalInstant, ParticipantId, SessionId,
};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use thiserror::Error;

#[derive(Clone, Debug, PartialEq)]
pub struct Estimated<T> {
    pub value: T,
    pub uncertainty_ns: u64,
    pub measured_at_local: LocalInstant,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ClockDomainSpec {
    pub id: ClockDomainId,
    pub master: DeviceId,
    pub epoch: u64,
    pub target_uncertainty_ns: u64,
    pub probe_interval_ms: u32,
    pub stale_after_ms: u32,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ClockSample {
    pub t1: u64,
    pub t2: i128,
    pub t3: i128,
    pub t4: u64,
    pub rtt_ns: u64,
    pub offset_ns: i128,
}

impl ClockSample {
    pub fn from_timestamps(t1: u64, t2: i128, t3: i128, t4: u64) -> Result<Self, ClockError> {
        if t4 < t1 || t3 < t2 {
            return Err(ClockError::InvalidSample);
        }
        let elapsed = i128::from(t4 - t1);
        let remote = t3 - t2;
        let network = elapsed - remote;
        if network < 0 {
            return Err(ClockError::InvalidSample);
        }
        let offset_ns = i128::midpoint(t2 - i128::from(t1), t3 - i128::from(t4));
        Ok(Self {
            t1,
            t2,
            t3,
            t4,
            rtt_ns: u64::try_from(network).map_err(|_| ClockError::InvalidSample)?,
            offset_ns,
        })
    }
    const fn local_midpoint(&self) -> u64 {
        self.t1 + (self.t4 - self.t1) / 2
    }
    const fn group_midpoint(&self) -> i128 {
        self.t2 + (self.t3 - self.t2) / 2
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ClockMapping {
    pub local_ref_ns: u64,
    pub group_ref_ns: i128,
    pub rate: f64,
    pub uncertainty_ns: u64,
    pub valid_until_local_ns: u64,
}
impl ClockMapping {
    pub fn local_to_group(
        &self,
        local: LocalInstant,
    ) -> Result<Estimated<GroupInstant>, ClockError> {
        if local.0 > self.valid_until_local_ns {
            return Err(ClockError::Stale);
        }
        #[allow(clippy::cast_precision_loss)]
        let delta = (local.0 as f64 - self.local_ref_ns as f64) * self.rate;
        #[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
        let value = self.group_ref_ns + delta.round() as i128;
        Ok(Estimated {
            value: GroupInstant(value),
            uncertainty_ns: self.uncertainty_ns,
            measured_at_local: local,
        })
    }
    pub fn group_to_local(
        &self,
        group: GroupInstant,
    ) -> Result<Estimated<LocalInstant>, ClockError> {
        if !self.rate.is_finite() || self.rate <= 0.0 {
            return Err(ClockError::InvalidMapping);
        }
        #[allow(clippy::cast_precision_loss)]
        let delta = (group.0 - self.group_ref_ns) as f64 / self.rate;
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let local = self
            .local_ref_ns
            .checked_add_signed(delta.round() as i64)
            .ok_or(ClockError::InvalidMapping)?;
        if local > self.valid_until_local_ns {
            return Err(ClockError::Stale);
        }
        Ok(Estimated {
            value: LocalInstant(local),
            uncertainty_ns: self.uncertainty_ns,
            measured_at_local: LocalInstant(self.local_ref_ns),
        })
    }
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum ClockError {
    #[error("invalid four-timestamp sample")]
    InvalidSample,
    #[error("not enough accepted samples")]
    InsufficientSamples,
    #[error("clock mapping is invalid")]
    InvalidMapping,
    #[error("clock mapping is stale")]
    Stale,
    #[error("clock uncertainty exceeds the contract")]
    Uncertain,
    #[error("Barrier is in an invalid state")]
    InvalidBarrierState,
    #[error("Barrier participant is unknown")]
    UnknownParticipant,
    #[error("Barrier epoch is stale")]
    StaleEpoch,
    #[error("Barrier activation was missed")]
    BarrierMissed,
}

pub struct ClockEstimator {
    samples: VecDeque<ClockSample>,
    capacity: usize,
    stale_after_ns: u64,
}
impl ClockEstimator {
    #[must_use]
    pub fn new(capacity: usize, stale_after_ns: u64) -> Self {
        Self {
            samples: VecDeque::new(),
            capacity: capacity.max(2),
            stale_after_ns,
        }
    }
    pub fn add_sample(&mut self, sample: ClockSample) -> bool {
        let mut rtts: Vec<_> = self.samples.iter().map(|item| item.rtt_ns).collect();
        rtts.sort_unstable();
        if let Some(median) = rtts.get(rtts.len() / 2).copied()
            && sample.rtt_ns > median.saturating_mul(3).max(median + 1_000_000)
        {
            return false;
        }
        if self.samples.len() == self.capacity {
            self.samples.pop_front();
        }
        self.samples.push_back(sample);
        true
    }
    pub fn mapping(&self) -> Result<ClockMapping, ClockError> {
        if self.samples.len() < 2 {
            return Err(ClockError::InsufficientSamples);
        }
        #[allow(clippy::cast_precision_loss)]
        let points: Vec<_> = self
            .samples
            .iter()
            .map(|sample| {
                (
                    sample.local_midpoint() as f64,
                    sample.group_midpoint() as f64,
                )
            })
            .collect();
        #[allow(clippy::cast_precision_loss)]
        let count = points.len() as f64;
        let mean_x = points.iter().map(|point| point.0).sum::<f64>() / count;
        let mean_y = points.iter().map(|point| point.1).sum::<f64>() / count;
        let covariance = points
            .iter()
            .map(|point| (point.0 - mean_x) * (point.1 - mean_y))
            .sum::<f64>();
        let variance = points
            .iter()
            .map(|point| (point.0 - mean_x).powi(2))
            .sum::<f64>();
        if variance == 0.0 {
            return Err(ClockError::InvalidMapping);
        }
        let rate = covariance / variance;
        if !rate.is_finite() || rate <= 0.0 {
            return Err(ClockError::InvalidMapping);
        }
        let reference = self.samples.back().ok_or(ClockError::InsufficientSamples)?;
        let local_ref_ns = reference.local_midpoint();
        #[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
        let group_ref_ns = (mean_y + rate * (local_ref_ns as f64 - mean_x)).round() as i128;
        let residual = points
            .iter()
            .map(|point| (point.1 - (mean_y + rate * (point.0 - mean_x))).abs())
            .fold(0.0, f64::max);
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let uncertainty_ns = (residual.ceil() as u64).saturating_add(reference.rtt_ns / 2);
        Ok(ClockMapping {
            local_ref_ns,
            group_ref_ns,
            rate,
            uncertainty_ns,
            valid_until_local_ns: local_ref_ns.saturating_add(self.stale_after_ns),
        })
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BarrierPolicy {
    AllRequired,
    Quorum { minimum: u16 },
    RequiredAndOptional,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BarrierSpec {
    pub id: BarrierId,
    pub session_id: SessionId,
    pub epoch: u64,
    pub participants: Vec<ParticipantId>,
    pub activate_at: GroupInstant,
    pub ready_deadline: GroupInstant,
    pub context: Vec<u8>,
    pub policy: BarrierPolicy,
    pub max_lateness_ns: u64,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BarrierState {
    Created,
    Preparing,
    Committed,
    Aborted,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum BarrierResult {
    Commit { activate_at: GroupInstant },
    Abort { reason: String },
}

pub struct Barrier {
    spec: BarrierSpec,
    state: BarrierState,
    ready: BTreeMap<ParticipantId, u64>,
    rejected: BTreeSet<ParticipantId>,
}
impl Barrier {
    #[must_use]
    pub fn new(spec: BarrierSpec) -> Self {
        Self {
            spec,
            state: BarrierState::Created,
            ready: BTreeMap::new(),
            rejected: BTreeSet::new(),
        }
    }
    pub fn prepare(
        &mut self,
        current_epoch: u64,
        clock_uncertainty_ns: u64,
        maximum_uncertainty_ns: u64,
    ) -> Result<(), ClockError> {
        if self.state != BarrierState::Created {
            return Err(ClockError::InvalidBarrierState);
        }
        if current_epoch != self.spec.epoch {
            return Err(ClockError::StaleEpoch);
        }
        if clock_uncertainty_ns > maximum_uncertainty_ns {
            return Err(ClockError::Uncertain);
        }
        self.state = BarrierState::Preparing;
        Ok(())
    }
    pub fn ready(
        &mut self,
        participant: ParticipantId,
        uncertainty_ns: u64,
    ) -> Result<(), ClockError> {
        self.ensure_preparing(participant)?;
        self.ready.insert(participant, uncertainty_ns);
        Ok(())
    }
    pub fn reject(&mut self, participant: ParticipantId) -> Result<(), ClockError> {
        self.ensure_preparing(participant)?;
        self.rejected.insert(participant);
        Ok(())
    }
    pub fn decide(&mut self, now: GroupInstant) -> Result<BarrierResult, ClockError> {
        if self.state != BarrierState::Preparing {
            return Err(ClockError::InvalidBarrierState);
        }
        let required = match self.spec.policy {
            BarrierPolicy::AllRequired | BarrierPolicy::RequiredAndOptional => {
                self.spec.participants.len()
            }
            BarrierPolicy::Quorum { minimum } => usize::from(minimum),
        };
        if !self.rejected.is_empty() && matches!(self.spec.policy, BarrierPolicy::AllRequired) {
            self.state = BarrierState::Aborted;
            return Ok(BarrierResult::Abort {
                reason: "participant rejected".into(),
            });
        }
        if self.ready.len() >= required {
            let lateness = now.0.saturating_sub(self.spec.activate_at.0);
            if lateness > i128::from(self.spec.max_lateness_ns) {
                self.state = BarrierState::Aborted;
                return Err(ClockError::BarrierMissed);
            }
            self.state = BarrierState::Committed;
            return Ok(BarrierResult::Commit {
                activate_at: self.spec.activate_at,
            });
        }
        if now >= self.spec.ready_deadline {
            self.state = BarrierState::Aborted;
            return Ok(BarrierResult::Abort {
                reason: "ready deadline expired".into(),
            });
        }
        Err(ClockError::InvalidBarrierState)
    }
    fn ensure_preparing(&self, participant: ParticipantId) -> Result<(), ClockError> {
        if self.state != BarrierState::Preparing {
            return Err(ClockError::InvalidBarrierState);
        }
        if !self.spec.participants.contains(&participant) {
            return Err(ClockError::UnknownParticipant);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn estimates_drift_and_ignores_wall_clock() {
        let mut estimator = ClockEstimator::new(8, 1_000_000_000);
        estimator.add_sample(ClockSample::from_timestamps(0, 1_000, 1_100, 200).unwrap());
        estimator.add_sample(
            ClockSample::from_timestamps(1_000_000, 1_001_100, 1_001_200, 1_000_200).unwrap(),
        );
        let mapping = estimator.mapping().unwrap();
        assert!((mapping.rate - 1.0001).abs() < 0.001);
        assert!(mapping.local_to_group(LocalInstant(500_000)).is_ok());
    }
    #[test]
    fn four_participant_barrier_commits_same_group_instant_and_reject_aborts() {
        let participants: Vec<_> = (0..4).map(|_| ParticipantId::new()).collect();
        let spec = BarrierSpec {
            id: BarrierId::new(),
            session_id: SessionId::new(),
            epoch: 1,
            participants: participants.clone(),
            activate_at: GroupInstant(1_000),
            ready_deadline: GroupInstant(900),
            context: vec![],
            policy: BarrierPolicy::AllRequired,
            max_lateness_ns: 10,
        };
        let mut barrier = Barrier::new(spec.clone());
        barrier.prepare(1, 10, 20).unwrap();
        for participant in &participants {
            barrier.ready(*participant, 10).unwrap();
        }
        assert_eq!(
            barrier.decide(GroupInstant(900)).unwrap(),
            BarrierResult::Commit {
                activate_at: GroupInstant(1_000)
            }
        );
        let mut rejected = Barrier::new(spec);
        rejected.prepare(1, 10, 20).unwrap();
        rejected.reject(participants[0]).unwrap();
        assert!(matches!(
            rejected.decide(GroupInstant(800)).unwrap(),
            BarrierResult::Abort { .. }
        ));
    }
}
