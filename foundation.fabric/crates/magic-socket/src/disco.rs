//! Disco: the tiny side-protocol that bootstraps a direct path between two
//! relay-connected peers (iroh's "call-me-maybe" idea, hand-rolled for fabric).
//!
//! Flow: each side sends [`DiscoMessage::CallMeMaybe`] with its direct-address
//! candidates through the relay; on receipt, each side fires a burst of UDP
//! [`DiscoMessage::Ping`]s at the other's candidates. The simultaneous bursts
//! open both NATs' mappings; whichever ping (or its [`DiscoMessage::Pong`])
//! arrives confirms — by its *source address* — a working direct path, which is
//! then registered and taken over by the biased-RTT selector.
//!
//! Disco packets travel on the same UDP socket and relay tunnel as QUIC, so
//! every message starts with [`DISCO_MAGIC`]; the magic socket consumes them
//! before QUIC ever sees them. (QUIC packets cannot collide with the prefix:
//! their first byte always has the QUIC fixed bit / form bits set differently
//! than 0xF7, and the full 8-byte magic makes an accidental match negligible.)
//!
//! v1 messages are unauthenticated: a spoofed pong could at worst steer traffic
//! onto a black-hole path, which the liveness machinery detects and abandons
//! within seconds, falling back to the relay; the QUIC payload itself stays
//! end-to-end authenticated regardless.

use std::net::SocketAddr;

/// Marks a datagram or relay payload as disco rather than QUIC.
pub const DISCO_MAGIC: [u8; 8] = *b"\xf7fabdsc1";

const KIND_CALL_ME_MAYBE: u8 = 1;
const KIND_PING: u8 = 2;
const KIND_PONG: u8 = 3;

/// Maximum candidates accepted in one call-me-maybe (bounds hostile input).
pub const MAX_CANDIDATES: usize = 8;

/// A disco message.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DiscoMessage {
    /// "Here are my direct-address candidates — ping me."
    CallMeMaybe { candidates: Vec<SocketAddr> },
    /// A hole-punching probe.
    Ping { nonce: u64 },
    /// Echo of a received ping's nonce.
    Pong { nonce: u64 },
}

impl DiscoMessage {
    /// Serializes the message, prefixed with [`DISCO_MAGIC`].
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut buffer = Vec::with_capacity(32);
        buffer.extend_from_slice(&DISCO_MAGIC);
        match self {
            Self::CallMeMaybe { candidates } => {
                buffer.push(KIND_CALL_ME_MAYBE);
                let count = candidates.len().min(MAX_CANDIDATES);
                buffer.push(u8::try_from(count).unwrap_or(u8::MAX));
                for candidate in candidates.iter().take(MAX_CANDIDATES) {
                    let encoded = encode_addr(*candidate);
                    // An encoded socket address is 6 or 18 bytes.
                    buffer.push(u8::try_from(encoded.len()).unwrap_or(u8::MAX));
                    buffer.extend_from_slice(&encoded);
                }
            }
            Self::Ping { nonce } => {
                buffer.push(KIND_PING);
                buffer.extend_from_slice(&nonce.to_be_bytes());
            }
            Self::Pong { nonce } => {
                buffer.push(KIND_PONG);
                buffer.extend_from_slice(&nonce.to_be_bytes());
            }
        }
        buffer
    }

    /// Parses a disco message if `bytes` carries the magic; `None` means "not
    /// disco — hand it to QUIC" and malformed-after-magic is also `None` (drop).
    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let rest = bytes.strip_prefix(&DISCO_MAGIC)?;
        let (&kind, rest) = rest.split_first()?;
        match kind {
            KIND_CALL_ME_MAYBE => {
                let (&count, mut rest) = rest.split_first()?;
                let count = usize::from(count).min(MAX_CANDIDATES);
                let mut candidates = Vec::with_capacity(count);
                for _ in 0..count {
                    let (&len, tail) = rest.split_first()?;
                    let len = usize::from(len);
                    let encoded = tail.get(..len)?;
                    candidates.push(decode_addr(encoded)?);
                    rest = &tail[len..];
                }
                Some(Self::CallMeMaybe { candidates })
            }
            KIND_PING => Some(Self::Ping {
                nonce: u64::from_be_bytes(rest.try_into().ok()?),
            }),
            KIND_PONG => Some(Self::Pong {
                nonce: u64::from_be_bytes(rest.try_into().ok()?),
            }),
            _ => None,
        }
    }

    /// Whether `bytes` is a disco payload at all (cheap prefix check).
    #[must_use]
    pub fn is_disco(bytes: &[u8]) -> bool {
        bytes.starts_with(&DISCO_MAGIC)
    }
}

/// 6-byte v4 / 18-byte v6 compact socket-address encoding.
fn encode_addr(addr: SocketAddr) -> Vec<u8> {
    // The STUN module's helpers are private; the layout here matches
    // fabric-discovery's `encode_quic_socket_hint` (ip octets ‖ be16 port).
    let mut encoded = Vec::with_capacity(18);
    match addr.ip() {
        std::net::IpAddr::V4(ip) => encoded.extend_from_slice(&ip.octets()),
        std::net::IpAddr::V6(ip) => encoded.extend_from_slice(&ip.octets()),
    }
    encoded.extend_from_slice(&addr.port().to_be_bytes());
    encoded
}

fn decode_addr(encoded: &[u8]) -> Option<SocketAddr> {
    match encoded.len() {
        6 => {
            let ip: [u8; 4] = encoded[..4].try_into().ok()?;
            let port = u16::from_be_bytes([encoded[4], encoded[5]]);
            Some(SocketAddr::new(std::net::IpAddr::V4(ip.into()), port))
        }
        18 => {
            let ip: [u8; 16] = encoded[..16].try_into().ok()?;
            let port = u16::from_be_bytes([encoded[16], encoded[17]]);
            Some(SocketAddr::new(std::net::IpAddr::V6(ip.into()), port))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip() {
        let messages = [
            DiscoMessage::CallMeMaybe {
                candidates: vec![
                    "192.168.1.5:44330".parse().unwrap(),
                    "[fe80::1]:44330".parse().unwrap(),
                ],
            },
            DiscoMessage::CallMeMaybe { candidates: vec![] },
            DiscoMessage::Ping { nonce: 7 },
            DiscoMessage::Pong { nonce: u64::MAX },
        ];
        for message in &messages {
            assert_eq!(
                DiscoMessage::decode(&message.encode()).as_ref(),
                Some(message)
            );
        }
    }

    #[test]
    fn non_disco_bytes_are_ignored() {
        assert!(!DiscoMessage::is_disco(b"\x40quic-ish-packet"));
        assert_eq!(DiscoMessage::decode(b"\x40quic-ish-packet"), None);
        // Magic but garbage after it: drop, don't panic.
        let mut bad = DISCO_MAGIC.to_vec();
        bad.push(99);
        assert_eq!(DiscoMessage::decode(&bad), None);
    }

    #[test]
    fn candidate_count_is_bounded() {
        let candidates: Vec<SocketAddr> = (0..20)
            .map(|i| format!("10.0.0.{i}:1000").parse().unwrap())
            .collect();
        let message = DiscoMessage::CallMeMaybe { candidates };
        let DiscoMessage::CallMeMaybe { candidates } =
            DiscoMessage::decode(&message.encode()).unwrap()
        else {
            panic!("wrong variant");
        };
        assert_eq!(candidates.len(), MAX_CANDIDATES);
    }
}
