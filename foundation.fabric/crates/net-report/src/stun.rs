//! Minimal STUN (RFC 8489 / RFC 5389) binding codec.
//!
//! We only need one STUN transaction: send a `Binding` request to a server and
//! read the server-reflexive transport address it echoes back in an
//! `XOR-MAPPED-ADDRESS` (or legacy `MAPPED-ADDRESS`) attribute. So rather than
//! pull in a full STUN stack we hand-roll exactly that, keeping the surface tiny
//! and dependency-light — in the spirit of iroh's own small STUN helper.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use rand_core::{OsRng, RngCore};
use thiserror::Error;

/// STUN magic cookie (RFC 8489 §5).
const MAGIC_COOKIE: u32 = 0x2112_A442;
/// The top 16 bits of the magic cookie, used to de-XOR the port.
const XOR_PORT_KEY: u16 = 0x2112;
/// Fixed STUN header length in bytes.
const HEADER_LEN: usize = 20;

/// Message type for a Binding request (class = request, method = binding).
const BINDING_REQUEST: u16 = 0x0001;
/// Message type for a Binding success response.
const BINDING_SUCCESS: u16 = 0x0101;

const ATTR_MAPPED_ADDRESS: u16 = 0x0001;
const ATTR_XOR_MAPPED_ADDRESS: u16 = 0x0020;

const FAMILY_IPV4: u8 = 0x01;
const FAMILY_IPV6: u8 = 0x02;

/// A 96-bit STUN transaction id.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TransactionId([u8; 12]);

impl TransactionId {
    /// Generates a fresh cryptographically-random transaction id.
    #[must_use]
    pub fn random() -> Self {
        let mut id = [0u8; 12];
        OsRng.fill_bytes(&mut id);
        Self(id)
    }

    /// Constructs a transaction id from raw bytes.
    #[must_use]
    pub fn from_bytes(bytes: [u8; 12]) -> Self {
        Self(bytes)
    }

    /// Returns the raw transaction id bytes.
    #[must_use]
    pub fn as_bytes(&self) -> &[u8; 12] {
        &self.0
    }
}

/// Errors decoding a STUN binding response.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum StunError {
    #[error("stun message is shorter than its declared length")]
    Truncated,
    #[error("stun magic cookie mismatch")]
    BadCookie,
    #[error("stun message is not a binding success response")]
    NotBindingSuccess,
    #[error("stun attribute is malformed")]
    BadAttribute,
    #[error("no mapped-address attribute present")]
    NoMappedAddress,
    #[error("unknown address family in mapped-address")]
    BadFamily,
}

/// A decoded STUN binding success response.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct BindingResponse {
    /// Transaction id echoed by the server; the caller must match it against the
    /// request it sent to reject stale or spoofed responses.
    pub transaction_id: TransactionId,
    /// Our server-reflexive transport address as seen by the STUN server.
    pub mapped_address: SocketAddr,
}

/// Encodes a STUN Binding request with the given transaction id and no attributes.
#[must_use]
pub fn encode_binding_request(transaction_id: TransactionId) -> [u8; HEADER_LEN] {
    let mut buf = [0u8; HEADER_LEN];
    buf[0..2].copy_from_slice(&BINDING_REQUEST.to_be_bytes());
    buf[2..4].copy_from_slice(&0u16.to_be_bytes());
    buf[4..8].copy_from_slice(&MAGIC_COOKIE.to_be_bytes());
    buf[8..20].copy_from_slice(transaction_id.as_bytes());
    buf
}

/// Decodes a STUN Binding success response, returning the reflexive address.
///
/// Prefers `XOR-MAPPED-ADDRESS` and falls back to the legacy `MAPPED-ADDRESS`.
pub fn decode_binding_response(bytes: &[u8]) -> Result<BindingResponse, StunError> {
    if bytes.len() < HEADER_LEN {
        return Err(StunError::Truncated);
    }
    let message_type = u16::from_be_bytes([bytes[0], bytes[1]]);
    if message_type != BINDING_SUCCESS {
        return Err(StunError::NotBindingSuccess);
    }
    let message_len = usize::from(u16::from_be_bytes([bytes[2], bytes[3]]));
    let cookie = u32::from_be_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    if cookie != MAGIC_COOKIE {
        return Err(StunError::BadCookie);
    }
    let mut raw_txid = [0u8; 12];
    raw_txid.copy_from_slice(&bytes[8..HEADER_LEN]);
    let transaction_id = TransactionId(raw_txid);

    let body = bytes
        .get(HEADER_LEN..HEADER_LEN + message_len)
        .ok_or(StunError::Truncated)?;

    let mut xor_mapped: Option<SocketAddr> = None;
    let mut mapped: Option<SocketAddr> = None;
    let mut offset = 0usize;
    while offset + 4 <= body.len() {
        let attr_type = u16::from_be_bytes([body[offset], body[offset + 1]]);
        let attr_len = usize::from(u16::from_be_bytes([body[offset + 2], body[offset + 3]]));
        let value_start = offset + 4;
        let value_end = value_start
            .checked_add(attr_len)
            .ok_or(StunError::BadAttribute)?;
        let value = body
            .get(value_start..value_end)
            .ok_or(StunError::BadAttribute)?;
        match attr_type {
            ATTR_XOR_MAPPED_ADDRESS => {
                xor_mapped = Some(parse_mapped_address(value, &transaction_id, true)?);
            }
            ATTR_MAPPED_ADDRESS => {
                mapped = Some(parse_mapped_address(value, &transaction_id, false)?);
            }
            _ => {}
        }
        // Attributes are padded to a 4-byte boundary.
        let padded_len = (attr_len + 3) & !3;
        offset = value_start
            .checked_add(padded_len)
            .ok_or(StunError::BadAttribute)?;
    }

    let mapped_address = xor_mapped.or(mapped).ok_or(StunError::NoMappedAddress)?;
    Ok(BindingResponse {
        transaction_id,
        mapped_address,
    })
}

/// Encodes a STUN Binding success response echoing `mapped` in an
/// `XOR-MAPPED-ADDRESS` attribute. Used by our own STUN/relay responder.
///
/// # Panics
/// Never in practice: the single mapped-address attribute is at most 24 bytes,
/// so the `u16` length conversions cannot overflow.
#[must_use]
pub fn encode_binding_response(transaction_id: TransactionId, mapped: SocketAddr) -> Vec<u8> {
    let mut value = Vec::with_capacity(20);
    value.push(0); // reserved
    let port = mapped.port() ^ XOR_PORT_KEY;
    match mapped.ip() {
        IpAddr::V4(ip) => {
            value.push(FAMILY_IPV4);
            value.extend_from_slice(&port.to_be_bytes());
            let mut octets = ip.octets();
            let key = MAGIC_COOKIE.to_be_bytes();
            for (byte, mask) in octets.iter_mut().zip(key.iter()) {
                *byte ^= *mask;
            }
            value.extend_from_slice(&octets);
        }
        IpAddr::V6(ip) => {
            value.push(FAMILY_IPV6);
            value.extend_from_slice(&port.to_be_bytes());
            let mut octets = ip.octets();
            let mut key = [0u8; 16];
            key[0..4].copy_from_slice(&MAGIC_COOKIE.to_be_bytes());
            key[4..16].copy_from_slice(transaction_id.as_bytes());
            for (byte, mask) in octets.iter_mut().zip(key.iter()) {
                *byte ^= *mask;
            }
            value.extend_from_slice(&octets);
        }
    }
    let value_len = u16::try_from(value.len()).expect("stun attribute value fits in u16");
    let body_len = u16::try_from(value.len() + 4).expect("stun body fits in u16");

    let mut message = Vec::with_capacity(HEADER_LEN + 4 + value.len());
    message.extend_from_slice(&BINDING_SUCCESS.to_be_bytes());
    message.extend_from_slice(&body_len.to_be_bytes());
    message.extend_from_slice(&MAGIC_COOKIE.to_be_bytes());
    message.extend_from_slice(transaction_id.as_bytes());
    message.extend_from_slice(&ATTR_XOR_MAPPED_ADDRESS.to_be_bytes());
    message.extend_from_slice(&value_len.to_be_bytes());
    message.extend_from_slice(&value);
    message
}

/// Parses a (`XOR-`)`MAPPED-ADDRESS` attribute value into a socket address.
fn parse_mapped_address(
    value: &[u8],
    transaction_id: &TransactionId,
    xored: bool,
) -> Result<SocketAddr, StunError> {
    // Layout: reserved(1) family(1) port(2) address(4 or 16).
    if value.len() < 4 {
        return Err(StunError::BadAttribute);
    }
    let family = value[1];
    let raw_port = u16::from_be_bytes([value[2], value[3]]);
    let port = if xored {
        raw_port ^ XOR_PORT_KEY
    } else {
        raw_port
    };

    match family {
        FAMILY_IPV4 => {
            let raw = value.get(4..8).ok_or(StunError::BadAttribute)?;
            let mut octets = [0u8; 4];
            octets.copy_from_slice(raw);
            if xored {
                let key = MAGIC_COOKIE.to_be_bytes();
                for (byte, mask) in octets.iter_mut().zip(key.iter()) {
                    *byte ^= *mask;
                }
            }
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), port))
        }
        FAMILY_IPV6 => {
            let raw = value.get(4..20).ok_or(StunError::BadAttribute)?;
            let mut octets = [0u8; 16];
            octets.copy_from_slice(raw);
            if xored {
                let mut key = [0u8; 16];
                key[0..4].copy_from_slice(&MAGIC_COOKIE.to_be_bytes());
                key[4..16].copy_from_slice(transaction_id.as_bytes());
                for (byte, mask) in octets.iter_mut().zip(key.iter()) {
                    *byte ^= *mask;
                }
            }
            Ok(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), port))
        }
        _ => Err(StunError::BadFamily),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Transaction id from the RFC 5769 §2.1 sample request.
    const SAMPLE_TXID: [u8; 12] = [
        0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae,
    ];

    #[test]
    fn binding_request_header_is_well_formed() {
        let txid = TransactionId::from_bytes(SAMPLE_TXID);
        let request = encode_binding_request(txid);
        assert_eq!(
            u16::from_be_bytes([request[0], request[1]]),
            BINDING_REQUEST
        );
        assert_eq!(u16::from_be_bytes([request[2], request[3]]), 0);
        assert_eq!(
            u32::from_be_bytes([request[4], request[5], request[6], request[7]]),
            MAGIC_COOKIE
        );
        assert_eq!(&request[8..20], &SAMPLE_TXID);
    }

    #[test]
    fn decodes_rfc5769_ipv4_xor_mapped_address() {
        // RFC 5769 §2.1: reflexive address 192.0.2.1:32853.
        let message = [
            0x01, 0x01, 0x00, 0x0c, // type = binding success, length = 12
            0x21, 0x12, 0xa4, 0x42, // magic cookie
            0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae, // txid
            0x00, 0x20, 0x00, 0x08, // XOR-MAPPED-ADDRESS, length 8
            0x00, 0x01, 0xa1, 0x47, // reserved, family v4, x-port
            0xe1, 0x12, 0xa6, 0x43, // x-address
        ];
        let response = decode_binding_response(&message).unwrap();
        assert_eq!(response.transaction_id.as_bytes(), &SAMPLE_TXID);
        assert_eq!(response.mapped_address, "192.0.2.1:32853".parse().unwrap());
    }

    #[test]
    fn decodes_rfc5769_ipv6_xor_mapped_address() {
        // RFC 5769 §2.3: reflexive address [2001:db8:1234:5678:11:2233:4455:6677]:32853.
        let message = [
            0x01, 0x01, 0x00, 0x18, // type = binding success, length = 24
            0x21, 0x12, 0xa4, 0x42, // magic cookie
            0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae, // txid
            0x00, 0x20, 0x00, 0x14, // XOR-MAPPED-ADDRESS, length 20
            0x00, 0x02, 0xa1, 0x47, // reserved, family v6, x-port
            0x01, 0x13, 0xa9, 0xfa, 0xa5, 0xd3, 0xf1, 0x79, // x-address (16 bytes)
            0xbc, 0x25, 0xf4, 0xb5, 0xbe, 0xd2, 0xb9, 0xd9,
        ];
        let response = decode_binding_response(&message).unwrap();
        assert_eq!(
            response.mapped_address,
            "[2001:db8:1234:5678:11:2233:4455:6677]:32853"
                .parse()
                .unwrap()
        );
    }

    #[test]
    fn falls_back_to_legacy_mapped_address() {
        // Legacy MAPPED-ADDRESS (not XORed): 192.0.2.1:32853.
        let message = [
            0x01, 0x01, 0x00, 0x0c, //
            0x21, 0x12, 0xa4, 0x42, //
            0xb7, 0xe7, 0xa7, 0x01, 0xbc, 0x34, 0xd6, 0x86, 0xfa, 0x87, 0xdf, 0xae, //
            0x00, 0x01, 0x00, 0x08, // MAPPED-ADDRESS, length 8
            0x00, 0x01, 0x80, 0x55, // reserved, family v4, port 32853
            0xc0, 0x00, 0x02, 0x01, // 192.0.2.1
        ];
        let response = decode_binding_response(&message).unwrap();
        assert_eq!(response.mapped_address, "192.0.2.1:32853".parse().unwrap());
    }

    #[test]
    fn encode_decode_round_trips_both_families() {
        for sample in ["203.0.113.7:51820", "[2001:db8::dead:beef]:44330"] {
            let addr: SocketAddr = sample.parse().unwrap();
            let txid = TransactionId::random();
            let message = encode_binding_response(txid, addr);
            let decoded = decode_binding_response(&message).unwrap();
            assert_eq!(decoded.transaction_id, txid);
            assert_eq!(decoded.mapped_address, addr);
        }
    }

    #[test]
    fn rejects_non_success_and_truncated_messages() {
        assert_eq!(
            decode_binding_response(&[0u8; 8]),
            Err(StunError::Truncated)
        );
        let request = encode_binding_request(TransactionId::random());
        assert_eq!(
            decode_binding_response(&request),
            Err(StunError::NotBindingSuccess)
        );
    }

    #[test]
    fn round_trips_random_transaction_ids() {
        let txid = TransactionId::random();
        let request = encode_binding_request(txid);
        assert_eq!(&request[8..20], txid.as_bytes());
    }
}
