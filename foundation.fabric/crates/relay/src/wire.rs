//! The relay wire protocol: one flat, self-delimiting frame per transport
//! datagram (a QUIC datagram or a length-delimited stream chunk carries exactly
//! one frame, so frames do not embed their own length).

use fabric_core::DeviceId;
use thiserror::Error;

const TAG_REGISTER: u8 = 1;
const TAG_REGISTER_OK: u8 = 2;
const TAG_FORWARD: u8 = 3;
const TAG_DELIVER: u8 = 4;
const TAG_UNREACHABLE: u8 = 5;
const TAG_PING: u8 = 6;
const TAG_PONG: u8 = 7;
const TAG_SERVER_CHALLENGE: u8 = 8;
const TAG_CLIENT_AUTH: u8 = 9;

const DEVICE_ID_LEN: usize = 32;
const NONCE_LEN: usize = 8;
const CHALLENGE_LEN: usize = 16;
const PUBLIC_KEY_LEN: usize = 32;
const SIGNATURE_LEN: usize = 64;

/// A single message exchanged between a hub and the relay.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RelayFrame {
    /// Hub → relay: "route traffic addressed to this `DeviceId` to me".
    Register { device: DeviceId },
    /// Relay → hub: registration accepted.
    RegisterOk,
    /// Hub → relay: deliver `payload` (an opaque QUIC datagram) to `destination`.
    Forward {
        destination: DeviceId,
        payload: Vec<u8>,
    },
    /// Relay → hub: `payload` arrived from `source`.
    Deliver { source: DeviceId, payload: Vec<u8> },
    /// Relay → hub: `destination` is not currently registered.
    Unreachable { destination: DeviceId },
    /// Keepalive request; the relay also uses the arriving datagram to observe
    /// the sender's reflexive address.
    Ping { nonce: u64 },
    /// Keepalive response echoing a `Ping`'s nonce.
    Pong { nonce: u64 },
    /// Relay → hub: sign this challenge to prove ownership of your `DeviceId`.
    ServerChallenge { challenge: [u8; CHALLENGE_LEN] },
    /// Hub → relay: an ed25519 public key and a signature over the challenge,
    /// proving the hub owns the `DeviceId` derived from that key.
    ClientAuth {
        public_key: [u8; PUBLIC_KEY_LEN],
        signature: [u8; SIGNATURE_LEN],
    },
}

/// Errors decoding a [`RelayFrame`].
#[derive(Clone, Copy, Debug, Eq, PartialEq, Error)]
pub enum RelayCodecError {
    #[error("relay frame is empty")]
    Empty,
    #[error("relay frame has an unknown tag: {0}")]
    UnknownTag(u8),
    #[error("relay frame is truncated or over-long for its tag")]
    Truncated,
}

impl RelayFrame {
    /// Serializes the frame to bytes.
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        match self {
            Self::Register { device } => encode_device(TAG_REGISTER, device),
            Self::RegisterOk => vec![TAG_REGISTER_OK],
            Self::Forward {
                destination,
                payload,
            } => encode_addressed(TAG_FORWARD, destination, payload),
            Self::Deliver { source, payload } => encode_addressed(TAG_DELIVER, source, payload),
            Self::Unreachable { destination } => encode_device(TAG_UNREACHABLE, destination),
            Self::Ping { nonce } => encode_nonce(TAG_PING, *nonce),
            Self::Pong { nonce } => encode_nonce(TAG_PONG, *nonce),
            Self::ServerChallenge { challenge } => {
                let mut buffer = Vec::with_capacity(1 + CHALLENGE_LEN);
                buffer.push(TAG_SERVER_CHALLENGE);
                buffer.extend_from_slice(challenge);
                buffer
            }
            Self::ClientAuth {
                public_key,
                signature,
            } => {
                let mut buffer = Vec::with_capacity(1 + PUBLIC_KEY_LEN + SIGNATURE_LEN);
                buffer.push(TAG_CLIENT_AUTH);
                buffer.extend_from_slice(public_key);
                buffer.extend_from_slice(signature);
                buffer
            }
        }
    }

    /// Parses one frame from bytes.
    pub fn decode(bytes: &[u8]) -> Result<Self, RelayCodecError> {
        let (&tag, rest) = bytes.split_first().ok_or(RelayCodecError::Empty)?;
        match tag {
            TAG_REGISTER => Ok(Self::Register {
                device: decode_device(rest)?,
            }),
            TAG_REGISTER_OK => {
                if rest.is_empty() {
                    Ok(Self::RegisterOk)
                } else {
                    Err(RelayCodecError::Truncated)
                }
            }
            TAG_FORWARD => {
                let (destination, payload) = decode_addressed(rest)?;
                Ok(Self::Forward {
                    destination,
                    payload,
                })
            }
            TAG_DELIVER => {
                let (source, payload) = decode_addressed(rest)?;
                Ok(Self::Deliver { source, payload })
            }
            TAG_UNREACHABLE => Ok(Self::Unreachable {
                destination: decode_device(rest)?,
            }),
            TAG_PING => Ok(Self::Ping {
                nonce: decode_nonce(rest)?,
            }),
            TAG_PONG => Ok(Self::Pong {
                nonce: decode_nonce(rest)?,
            }),
            TAG_SERVER_CHALLENGE => {
                let challenge = rest.try_into().map_err(|_| RelayCodecError::Truncated)?;
                Ok(Self::ServerChallenge { challenge })
            }
            TAG_CLIENT_AUTH => {
                let public_key: [u8; PUBLIC_KEY_LEN] = rest
                    .get(..PUBLIC_KEY_LEN)
                    .and_then(|slice| slice.try_into().ok())
                    .ok_or(RelayCodecError::Truncated)?;
                let signature: [u8; SIGNATURE_LEN] = rest
                    .get(PUBLIC_KEY_LEN..)
                    .and_then(|slice| slice.try_into().ok())
                    .ok_or(RelayCodecError::Truncated)?;
                Ok(Self::ClientAuth {
                    public_key,
                    signature,
                })
            }
            other => Err(RelayCodecError::UnknownTag(other)),
        }
    }
}

fn encode_device(tag: u8, device: &DeviceId) -> Vec<u8> {
    let mut buffer = Vec::with_capacity(1 + DEVICE_ID_LEN);
    buffer.push(tag);
    buffer.extend_from_slice(&device.0);
    buffer
}

fn encode_addressed(tag: u8, device: &DeviceId, payload: &[u8]) -> Vec<u8> {
    let mut buffer = Vec::with_capacity(1 + DEVICE_ID_LEN + payload.len());
    buffer.push(tag);
    buffer.extend_from_slice(&device.0);
    buffer.extend_from_slice(payload);
    buffer
}

fn encode_nonce(tag: u8, nonce: u64) -> Vec<u8> {
    let mut buffer = Vec::with_capacity(1 + NONCE_LEN);
    buffer.push(tag);
    buffer.extend_from_slice(&nonce.to_be_bytes());
    buffer
}

/// Decodes exactly one `DeviceId` (rejecting trailing bytes).
fn decode_device(bytes: &[u8]) -> Result<DeviceId, RelayCodecError> {
    let raw: [u8; DEVICE_ID_LEN] = bytes.try_into().map_err(|_| RelayCodecError::Truncated)?;
    Ok(DeviceId(raw))
}

/// Decodes a `DeviceId` followed by a (possibly empty) opaque payload.
fn decode_addressed(bytes: &[u8]) -> Result<(DeviceId, Vec<u8>), RelayCodecError> {
    let raw: [u8; DEVICE_ID_LEN] = bytes
        .get(..DEVICE_ID_LEN)
        .and_then(|slice| slice.try_into().ok())
        .ok_or(RelayCodecError::Truncated)?;
    Ok((DeviceId(raw), bytes[DEVICE_ID_LEN..].to_vec()))
}

fn decode_nonce(bytes: &[u8]) -> Result<u64, RelayCodecError> {
    let raw: [u8; NONCE_LEN] = bytes.try_into().map_err(|_| RelayCodecError::Truncated)?;
    Ok(u64::from_be_bytes(raw))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(seed: u8) -> DeviceId {
        DeviceId([seed; 32])
    }

    fn assert_round_trip(frame: &RelayFrame) {
        assert_eq!(RelayFrame::decode(&frame.encode()).as_ref(), Ok(frame));
    }

    #[test]
    fn every_frame_round_trips() {
        assert_round_trip(&RelayFrame::Register { device: device(1) });
        assert_round_trip(&RelayFrame::RegisterOk);
        assert_round_trip(&RelayFrame::Forward {
            destination: device(2),
            payload: vec![9, 8, 7, 6],
        });
        assert_round_trip(&RelayFrame::Deliver {
            source: device(3),
            payload: Vec::new(),
        });
        assert_round_trip(&RelayFrame::Unreachable {
            destination: device(4),
        });
        assert_round_trip(&RelayFrame::Ping { nonce: 0xdead_beef });
        assert_round_trip(&RelayFrame::Pong { nonce: u64::MAX });
        assert_round_trip(&RelayFrame::ServerChallenge {
            challenge: [0xab; 16],
        });
        assert_round_trip(&RelayFrame::ClientAuth {
            public_key: [1; 32],
            signature: [2; 64],
        });
    }

    #[test]
    fn empty_input_is_rejected() {
        assert_eq!(RelayFrame::decode(&[]), Err(RelayCodecError::Empty));
    }

    #[test]
    fn unknown_tag_is_rejected() {
        assert_eq!(
            RelayFrame::decode(&[0xff, 1, 2]),
            Err(RelayCodecError::UnknownTag(0xff))
        );
    }

    #[test]
    fn wrong_length_frames_are_rejected() {
        // Register with a short DeviceId.
        assert_eq!(
            RelayFrame::decode(&[TAG_REGISTER, 1, 2, 3]),
            Err(RelayCodecError::Truncated)
        );
        // Ping with the wrong nonce width.
        assert_eq!(
            RelayFrame::decode(&[TAG_PING, 0, 0]),
            Err(RelayCodecError::Truncated)
        );
        // RegisterOk must be bare.
        assert_eq!(
            RelayFrame::decode(&[TAG_REGISTER_OK, 0]),
            Err(RelayCodecError::Truncated)
        );
        // Forward needs at least a full DeviceId before its payload.
        assert_eq!(
            RelayFrame::decode(&[TAG_FORWARD, 0, 0, 0]),
            Err(RelayCodecError::Truncated)
        );
    }

    #[test]
    fn forward_frame_supports_an_empty_payload() {
        let frame = RelayFrame::Forward {
            destination: device(5),
            payload: Vec::new(),
        };
        assert_eq!(frame.encode().len(), 1 + DEVICE_ID_LEN);
        assert_round_trip(&frame);
    }
}
