//! The fabric "magic socket": a `noq::AsyncUdpSocket` that lets QUIC dial a peer
//! by identity (`DeviceId`) instead of by a routable address, and transparently
//! moves that peer's traffic across whatever path is currently best — a direct
//! UDP path, or a relay — while noq's multipath / NAT-traversal extensions
//! upgrade relay paths to direct ones underneath.
//!
//! Modeled on iroh's `socket` module, reimplemented for fabric on noq.
//!
//! ## Build order
//! - [`mapped_addrs`]: the synthetic-address bijection QUIC addresses peers by.
//! - UDP transport leaf, then the `MagicSocket` itself, then the relay transport
//!   and biased-RTT path selection (later tasks).

pub mod disco;
pub mod mapped_addrs;
pub mod socket;
pub mod udp;

pub use socket::{MagicHandle, MagicSocket};
