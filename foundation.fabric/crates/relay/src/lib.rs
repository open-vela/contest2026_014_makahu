//! The fabric rendezvous/relay protocol.
//!
//! A relay is a public-internet meeting point: hubs that cannot reach each other
//! directly each register with it by `DeviceId`, and the relay forwards opaque
//! bytes between them. Those bytes are whole QUIC datagrams, so a relayed path is
//! just another transport under the magic socket — noq's NAT-traversal extension
//! then tries to upgrade it to a direct path, with the relay as the always-works
//! fallback.
//!
//! This module is the wire protocol only; the client transport and the server
//! live alongside it (later tasks).

pub mod client;
pub mod framing;
pub mod handshake;
pub mod net;
pub mod server;
pub mod tls;
pub mod wire;

pub use client::{RelayClient, RelayEvent};
pub use framing::{FramingError, read_frame, write_frame};
pub use handshake::{RelayAuthError, RelayChallenge, client_auth};
pub use net::{RelayConnection, RelayNetError, RelaySender, RelayServer};
pub use server::{ConnId, Outbound, RelayRouter};
pub use tls::TlsError;
pub use wire::{RelayCodecError, RelayFrame};
