//! Platform carrier contracts for Session data.
//!
//! BLE in this crate is restricted to authorized `ReliableMessages` channels.
//! The packet engine is runtime-backed by Tokio; embedded `no_std` runtimes need
//! a separate adapter and are not supported by this crate yet.

mod ble;
mod messages;
mod quic;

pub(crate) use messages::{decode_message, encode_message};

pub use ble::{
    BleMessageConfig, BleMessagePlatform, BleMessageTransport, BlePacketIo,
    UnsupportedBleMessagePlatform,
};
pub use messages::{
    AuthorizedMessageChannel, CarrierCapabilities, CarrierKind, MessageEnvelope,
    ReliableMessageTransport, TransportError, select_carrier,
};
pub use quic::{QuicMessageTransport, RELIABLE_MESSAGES_STREAM_FLAG, is_reliable_message_stream};
