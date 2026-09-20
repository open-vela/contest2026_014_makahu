//! Transport-agnostic relay client logic.
//!
//! [`RelayClient`] builds the frames a hub sends to the relay and interprets the
//! ones it receives, without owning a transport — the QUIC glue drives it. Keeping
//! this logic separate makes the frame handling testable without a live server.

use fabric_core::DeviceId;

use crate::wire::RelayFrame;

/// Builds and interprets relay frames on behalf of one hub identity.
#[derive(Clone, Copy, Debug)]
pub struct RelayClient {
    device: DeviceId,
}

impl RelayClient {
    /// Creates a client that registers and forwards as `device`.
    #[must_use]
    pub fn new(device: DeviceId) -> Self {
        Self { device }
    }

    /// The frame to send on connect so the relay routes inbound traffic here.
    #[must_use]
    pub fn register(&self) -> RelayFrame {
        RelayFrame::Register {
            device: self.device,
        }
    }

    /// Wraps an opaque `payload` bound for `destination` for the relay to forward.
    #[must_use]
    pub fn forward(&self, destination: DeviceId, payload: Vec<u8>) -> RelayFrame {
        RelayFrame::Forward {
            destination,
            payload,
        }
    }

    /// Interprets a frame received from the relay.
    #[must_use]
    pub fn on_frame(&self, frame: RelayFrame) -> RelayEvent {
        match frame {
            RelayFrame::RegisterOk => RelayEvent::Registered,
            RelayFrame::Deliver { source, payload } => RelayEvent::Received { source, payload },
            RelayFrame::Unreachable { destination } => RelayEvent::Unreachable { destination },
            RelayFrame::Pong { nonce } => RelayEvent::Pong { nonce },
            // Server-bound requests and the auth frames (handled during the
            // handshake, not steady state) are not events here.
            RelayFrame::Register { .. }
            | RelayFrame::Forward { .. }
            | RelayFrame::Ping { .. }
            | RelayFrame::ServerChallenge { .. }
            | RelayFrame::ClientAuth { .. } => RelayEvent::Ignored,
        }
    }
}

/// The meaning of a frame the relay sent to a client.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RelayEvent {
    /// The relay accepted our registration.
    Registered,
    /// An opaque payload arrived from `source`.
    Received { source: DeviceId, payload: Vec<u8> },
    /// A peer we tried to reach is not registered with the relay.
    Unreachable { destination: DeviceId },
    /// A keepalive echo.
    Pong { nonce: u64 },
    /// A frame a client should not receive; dropped.
    Ignored,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(seed: u8) -> DeviceId {
        DeviceId([seed; 32])
    }

    #[test]
    fn builds_register_and_forward_frames() {
        let client = RelayClient::new(device(1));
        assert_eq!(
            client.register(),
            RelayFrame::Register { device: device(1) }
        );
        assert_eq!(
            client.forward(device(2), vec![5, 6]),
            RelayFrame::Forward {
                destination: device(2),
                payload: vec![5, 6],
            }
        );
    }

    #[test]
    fn interprets_inbound_frames() {
        let client = RelayClient::new(device(1));
        assert_eq!(
            client.on_frame(RelayFrame::RegisterOk),
            RelayEvent::Registered
        );
        assert_eq!(
            client.on_frame(RelayFrame::Deliver {
                source: device(3),
                payload: vec![9],
            }),
            RelayEvent::Received {
                source: device(3),
                payload: vec![9],
            }
        );
        assert_eq!(
            client.on_frame(RelayFrame::Unreachable {
                destination: device(4)
            }),
            RelayEvent::Unreachable {
                destination: device(4)
            }
        );
        assert_eq!(
            client.on_frame(RelayFrame::Ping { nonce: 1 }),
            RelayEvent::Ignored
        );
    }
}
