//! Discovery candidates, backend interfaces, deterministic merge, and expiry.

use async_trait::async_trait;
use std::{
    collections::BTreeMap,
    fmt::Write as _,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    sync::Arc,
};
use thiserror::Error;

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct DiscoveryKey(pub String);
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum ConnectionHint {
    Quic { host: String, port: u16 },
    Opaque(Vec<u8>),
}

#[must_use]
pub fn encode_quic_socket_hint(address: SocketAddr) -> Vec<u8> {
    let mut encoded = Vec::with_capacity(if address.is_ipv4() { 6 } else { 18 });
    match address.ip() {
        IpAddr::V4(ip) => encoded.extend_from_slice(&ip.octets()),
        IpAddr::V6(ip) => encoded.extend_from_slice(&ip.octets()),
    }
    encoded.extend_from_slice(&address.port().to_be_bytes());
    encoded
}

#[must_use]
pub fn encode_rotating_hint(hint: &[u8; 16]) -> String {
    hint.iter()
        .fold(String::with_capacity(32), |mut output, byte| {
            let _ = write!(output, "{byte:02x}");
            output
        })
}

#[must_use]
pub fn decode_quic_socket_hint(encoded: &[u8]) -> Option<SocketAddr> {
    match encoded.len() {
        6 => Some(SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(
                encoded[0], encoded[1], encoded[2], encoded[3],
            )),
            u16::from_be_bytes([encoded[4], encoded[5]]),
        )),
        18 => {
            let octets: [u8; 16] = encoded[..16].try_into().ok()?;
            Some(SocketAddr::new(
                IpAddr::V6(Ipv6Addr::from(octets)),
                u16::from_be_bytes([encoded[16], encoded[17]]),
            ))
        }
        _ => None,
    }
}

#[must_use]
pub fn quic_socket_addresses(candidate: &PeerCandidate) -> Vec<SocketAddr> {
    let mut addresses = candidate
        .connection_hints
        .iter()
        .filter_map(|hint| match hint {
            ConnectionHint::Quic { host, port } => host
                .parse::<IpAddr>()
                .ok()
                .map(|ip| SocketAddr::new(ip, *port)),
            ConnectionHint::Opaque(encoded) => decode_quic_socket_hint(encoded),
        })
        .collect::<Vec<_>>();
    addresses.sort();
    addresses.dedup();
    addresses
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProximityEvidence {
    pub method: String,
    pub confidence: u8,
}
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum DiscoverySource {
    Mdns,
    Ble,
    WifiAware,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PeerCandidate {
    pub discovery_key: DiscoveryKey,
    pub label: String,
    pub connection_hints: Vec<ConnectionHint>,
    pub device_hint: Option<[u8; 16]>,
    pub proximity: Option<ProximityEvidence>,
    pub sources: Vec<DiscoverySource>,
    pub expires_at_ms: u64,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum DiscoveryError {
    #[error("discovery backend is unavailable on this platform")]
    UnsupportedCapability,
    #[error("discovery record is invalid")]
    InvalidAdvertisement,
    #[error("discovery sink failed")]
    SinkFailed,
    #[error("discovery backend is already running")]
    AlreadyRunning,
    #[error("discovery platform failure: {0}")]
    Platform(String),
}

#[async_trait]
pub trait CandidateSink: Send + Sync {
    async fn upsert(&self, candidate: PeerCandidate) -> Result<(), DiscoveryError>;
    async fn remove(&self, key: &DiscoveryKey) -> Result<(), DiscoveryError>;
}
#[async_trait]
pub trait DiscoveryBackend: Send + Sync {
    async fn start(&self, sink: Arc<dyn CandidateSink>) -> Result<(), DiscoveryError>;
    async fn stop(&self) -> Result<(), DiscoveryError>;
}

#[derive(Default)]
pub struct CandidateCache {
    candidates: BTreeMap<DiscoveryKey, PeerCandidate>,
}
impl CandidateCache {
    pub fn merge(&mut self, mut candidate: PeerCandidate) {
        if let Some(existing) = self.candidates.get_mut(&candidate.discovery_key) {
            existing
                .connection_hints
                .append(&mut candidate.connection_hints);
            existing.connection_hints.sort();
            existing.connection_hints.dedup();
            existing.sources.append(&mut candidate.sources);
            existing.sources.sort();
            existing.sources.dedup();
            existing.device_hint = candidate.device_hint.or(existing.device_hint);
            if !candidate.label.is_empty() {
                existing.label = candidate.label;
            }
            existing.proximity = candidate.proximity.or_else(|| existing.proximity.clone());
            existing.expires_at_ms = existing.expires_at_ms.max(candidate.expires_at_ms);
        } else {
            candidate.connection_hints.sort();
            candidate.connection_hints.dedup();
            candidate.sources.sort();
            candidate.sources.dedup();
            self.candidates
                .insert(candidate.discovery_key.clone(), candidate);
        }
    }
    pub fn expire(&mut self, now_ms: u64) -> Vec<DiscoveryKey> {
        let expired: Vec<_> = self
            .candidates
            .iter()
            .filter(|(_, item)| item.expires_at_ms <= now_ms)
            .map(|(key, _)| key.clone())
            .collect();
        for key in &expired {
            self.candidates.remove(key);
        }
        expired
    }
    pub fn remove(&mut self, key: &DiscoveryKey) -> Option<PeerCandidate> {
        self.candidates.remove(key)
    }
    #[must_use]
    pub fn values(&self) -> Vec<PeerCandidate> {
        self.candidates.values().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn merges_sources_and_expires_once() {
        let mut cache = CandidateCache::default();
        let key = DiscoveryKey("same-peer".into());
        cache.merge(PeerCandidate {
            discovery_key: key.clone(),
            label: "first".into(),
            connection_hints: vec![ConnectionHint::Quic {
                host: "127.0.0.1".into(),
                port: 1,
            }],
            device_hint: None,
            proximity: None,
            sources: vec![DiscoverySource::Mdns],
            expires_at_ms: 10,
        });
        cache.merge(PeerCandidate {
            discovery_key: key.clone(),
            label: "second".into(),
            connection_hints: vec![],
            device_hint: None,
            proximity: None,
            sources: vec![DiscoverySource::Ble],
            expires_at_ms: 20,
        });
        assert_eq!(cache.values()[0].sources.len(), 2);
        assert!(cache.expire(19).is_empty());
        assert_eq!(cache.expire(20), vec![key]);
    }

    #[test]
    fn compact_quic_hints_round_trip_ipv4_and_ipv6() {
        for address in ["192.168.1.20:44330", "[fe80::1]:44330"] {
            let address = address.parse().unwrap();
            assert_eq!(
                decode_quic_socket_hint(&encode_quic_socket_hint(address)),
                Some(address)
            );
        }
        assert_eq!(decode_quic_socket_hint(&[1, 2]), None);
        assert_eq!(encode_rotating_hint(&[0xab; 16]), "ab".repeat(16));
    }

    #[test]
    fn candidate_addresses_accept_resolved_and_compact_hints() {
        let candidate = PeerCandidate {
            discovery_key: DiscoveryKey("peer".into()),
            label: "peer".into(),
            connection_hints: vec![
                ConnectionHint::Quic {
                    host: "192.168.1.20".into(),
                    port: 44_330,
                },
                ConnectionHint::Opaque(encode_quic_socket_hint(
                    "192.168.1.20:44330".parse().unwrap(),
                )),
                ConnectionHint::Quic {
                    host: "unresolved.local".into(),
                    port: 44_330,
                },
            ],
            device_hint: None,
            proximity: None,
            sources: vec![DiscoverySource::Mdns],
            expires_at_ms: 1,
        };
        assert_eq!(
            quic_socket_addresses(&candidate),
            vec!["192.168.1.20:44330".parse().unwrap()]
        );
    }
}
