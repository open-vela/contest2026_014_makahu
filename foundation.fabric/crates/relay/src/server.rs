//! The relay's transport-agnostic forwarding core.
//!
//! [`RelayRouter`] is the "brain": it maps each registered `DeviceId` to the
//! connection that owns it and, for every frame a connection sends, decides which
//! frames to send back and to whom. It touches no sockets, so it is exhaustively
//! unit-testable; the QUIC server is a thin loop that feeds it received frames and
//! ships the [`Outbound`]s it returns.

use std::collections::BTreeMap;

use fabric_core::DeviceId;

use crate::wire::RelayFrame;

/// An opaque identifier the QUIC server assigns to each live connection.
pub type ConnId = u64;

/// A frame the router wants delivered to a specific connection.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Outbound {
    /// The connection to send `frame` on.
    pub connection: ConnId,
    /// The frame to send.
    pub frame: RelayFrame,
}

/// Maps registered devices to connections and forwards opaque payloads between them.
#[derive(Debug, Default)]
pub struct RelayRouter {
    device_to_conn: BTreeMap<DeviceId, ConnId>,
    conn_to_device: BTreeMap<ConnId, DeviceId>,
}

impl RelayRouter {
    /// Processes one `frame` received on `connection`, returning the frames to send.
    pub fn handle(&mut self, connection: ConnId, frame: RelayFrame) -> Vec<Outbound> {
        match frame {
            RelayFrame::Register { device } => {
                self.register(connection, device);
                vec![Outbound {
                    connection,
                    frame: RelayFrame::RegisterOk,
                }]
            }
            RelayFrame::Forward {
                destination,
                payload,
            } => {
                let Some(&source) = self.conn_to_device.get(&connection) else {
                    // A connection that never registered has no source identity.
                    return Vec::new();
                };
                self.device_to_conn.get(&destination).map_or_else(
                    || {
                        vec![Outbound {
                            connection,
                            frame: RelayFrame::Unreachable { destination },
                        }]
                    },
                    |&destination_conn| {
                        vec![Outbound {
                            connection: destination_conn,
                            frame: RelayFrame::Deliver { source, payload },
                        }]
                    },
                )
            }
            RelayFrame::Ping { nonce } => vec![Outbound {
                connection,
                frame: RelayFrame::Pong { nonce },
            }],
            // Server-bound frames a well-behaved client never sends, plus the
            // auth frames the handshake consumes before steady state, are ignored.
            RelayFrame::RegisterOk
            | RelayFrame::Deliver { .. }
            | RelayFrame::Unreachable { .. }
            | RelayFrame::Pong { .. }
            | RelayFrame::ServerChallenge { .. }
            | RelayFrame::ClientAuth { .. } => Vec::new(),
        }
    }

    /// Registers `device` on `connection` directly, as the auth handshake does
    /// once it has cryptographically verified the device's identity.
    pub fn register(&mut self, connection: ConnId, device: DeviceId) {
        self.device_to_conn.insert(device, connection);
        self.conn_to_device.insert(connection, device);
    }

    /// Drops a closed connection and, if it still owned its device mapping, that too.
    pub fn disconnect(&mut self, connection: ConnId) {
        if let Some(device) = self.conn_to_device.remove(&connection)
            && self.device_to_conn.get(&device) == Some(&connection)
        {
            self.device_to_conn.remove(&device);
        }
    }

    /// The connection currently registered for `device`, if any.
    #[must_use]
    pub fn connection_for(&self, device: DeviceId) -> Option<ConnId> {
        self.device_to_conn.get(&device).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(seed: u8) -> DeviceId {
        DeviceId([seed; 32])
    }

    fn register(router: &mut RelayRouter, connection: ConnId, seed: u8) {
        let out = router.handle(
            connection,
            RelayFrame::Register {
                device: device(seed),
            },
        );
        assert_eq!(
            out,
            vec![Outbound {
                connection,
                frame: RelayFrame::RegisterOk
            }]
        );
    }

    #[test]
    fn registration_maps_device_to_connection() {
        let mut router = RelayRouter::default();
        register(&mut router, 10, 1);
        assert_eq!(router.connection_for(device(1)), Some(10));
    }

    #[test]
    fn forward_reaches_the_registered_destination_with_the_real_source() {
        let mut router = RelayRouter::default();
        register(&mut router, 10, 1); // sender is device 1 on conn 10
        register(&mut router, 20, 2); // receiver is device 2 on conn 20

        let out = router.handle(
            10,
            RelayFrame::Forward {
                destination: device(2),
                payload: vec![1, 2, 3],
            },
        );
        assert_eq!(
            out,
            vec![Outbound {
                connection: 20,
                frame: RelayFrame::Deliver {
                    source: device(1),
                    payload: vec![1, 2, 3],
                },
            }]
        );
    }

    #[test]
    fn forward_to_unknown_destination_reports_unreachable_to_sender() {
        let mut router = RelayRouter::default();
        register(&mut router, 10, 1);
        let out = router.handle(
            10,
            RelayFrame::Forward {
                destination: device(9),
                payload: vec![7],
            },
        );
        assert_eq!(
            out,
            vec![Outbound {
                connection: 10,
                frame: RelayFrame::Unreachable {
                    destination: device(9)
                },
            }]
        );
    }

    #[test]
    fn forward_from_an_unregistered_connection_is_ignored() {
        let mut router = RelayRouter::default();
        register(&mut router, 20, 2);
        let out = router.handle(
            99, // never registered
            RelayFrame::Forward {
                destination: device(2),
                payload: vec![7],
            },
        );
        assert!(out.is_empty());
    }

    #[test]
    fn ping_is_answered_with_a_matching_pong() {
        let mut router = RelayRouter::default();
        let out = router.handle(10, RelayFrame::Ping { nonce: 42 });
        assert_eq!(
            out,
            vec![Outbound {
                connection: 10,
                frame: RelayFrame::Pong { nonce: 42 }
            }]
        );
    }

    #[test]
    fn reregistering_a_device_moves_it_to_the_new_connection() {
        let mut router = RelayRouter::default();
        register(&mut router, 10, 1);
        register(&mut router, 11, 1); // same device, new connection
        assert_eq!(router.connection_for(device(1)), Some(11));

        // Disconnecting the stale connection must not evict the fresh mapping.
        router.disconnect(10);
        assert_eq!(router.connection_for(device(1)), Some(11));
    }

    #[test]
    fn disconnect_removes_the_device_mapping() {
        let mut router = RelayRouter::default();
        register(&mut router, 10, 1);
        router.disconnect(10);
        assert_eq!(router.connection_for(device(1)), None);
    }
}
