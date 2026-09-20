//! Synthetic address mapping.
//!
//! QUIC identifies connections by a routable `SocketAddr`, but fabric wants to
//! dial by `DeviceId` and to keep one logical peer stable even as its real path
//! changes (direct UDP today, relay tomorrow, a different NAT binding after
//! that). We bridge the two by handing QUIC a *synthetic* address — a
//! non-routable IPv6 ULA drawn from `fd15:70a:510b::/48` — that stands in for a
//! peer. The magic socket translates that synthetic address to the real current
//! path on send, and translates a real source address back to the synthetic one
//! on receive, so QUIC always sees a single stable address per peer.
//!
//! The `fd15:70a:510b::/48` prefix is the same one iroh uses; these addresses
//! never touch the wire, so the choice only needs to be collision-free with real
//! traffic, which a ULA prefix guarantees.

use std::{
    collections::BTreeMap,
    net::{Ipv6Addr, SocketAddr, SocketAddrV6},
};

use fabric_core::DeviceId;

/// The `/48` ULA prefix (three leading 16-bit groups) reserved for mapped addresses.
const MAPPED_PREFIX: [u16; 3] = [0xfd15, 0x070a, 0x510b];
/// Sentinel port every mapped address carries; uniqueness lives in the host bits.
const MAPPED_PORT: u16 = 0xF00D;

/// What a mapped address stands in for.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum MappedKey {
    /// A peer identified by its `DeviceId` — used when we dial by identity.
    Device(DeviceId),
    /// A concrete transport address not yet associated with a `DeviceId` — e.g.
    /// an inbound peer during `accept`, before its TLS identity is known.
    Ip(SocketAddr),
}

/// A synthetic, non-routable address that QUIC treats as a peer's address.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct MappedAddr(SocketAddrV6);

impl MappedAddr {
    /// Builds the mapped address for a dense allocation `index`.
    #[must_use]
    fn from_index(index: u32) -> Self {
        let bytes = index.to_be_bytes();
        let group6 = u16::from_be_bytes([bytes[0], bytes[1]]);
        let group7 = u16::from_be_bytes([bytes[2], bytes[3]]);
        let ip = Ipv6Addr::new(
            MAPPED_PREFIX[0],
            MAPPED_PREFIX[1],
            MAPPED_PREFIX[2],
            0,
            0,
            0,
            group6,
            group7,
        );
        Self(SocketAddrV6::new(ip, MAPPED_PORT, 0, 0))
    }

    /// The address as a `SocketAddr` for handing to QUIC.
    #[must_use]
    pub const fn socket_addr(self) -> SocketAddr {
        SocketAddr::V6(self.0)
    }

    /// Recognizes an address QUIC handed back to us as one of ours.
    #[must_use]
    pub fn from_socket(addr: SocketAddr) -> Option<Self> {
        let SocketAddr::V6(v6) = addr else {
            return None;
        };
        let segments = v6.ip().segments();
        if segments[0..3] == MAPPED_PREFIX && v6.port() == MAPPED_PORT {
            Some(Self(v6))
        } else {
            None
        }
    }
}

/// A bidirectional, append-only registry between peer keys and their mapped
/// addresses. Entries are stable for the life of the socket.
#[derive(Debug, Default)]
pub struct MappedAddrs {
    forward: BTreeMap<MappedKey, MappedAddr>,
    reverse: BTreeMap<MappedAddr, MappedKey>,
    next_index: u32,
}

impl MappedAddrs {
    /// Returns the mapped address for `key`, allocating a fresh one on first use.
    pub fn get_or_insert(&mut self, key: MappedKey) -> MappedAddr {
        if let Some(addr) = self.forward.get(&key) {
            return *addr;
        }
        let addr = MappedAddr::from_index(self.next_index);
        self.next_index = self.next_index.wrapping_add(1);
        self.forward.insert(key, addr);
        self.reverse.insert(addr, key);
        addr
    }

    /// The key a mapped address stands for, if known.
    #[must_use]
    pub fn key_for(&self, addr: MappedAddr) -> Option<MappedKey> {
        self.reverse.get(&addr).copied()
    }

    /// The mapped address already allocated for `key`, if any.
    #[must_use]
    pub fn mapped_for(&self, key: &MappedKey) -> Option<MappedAddr> {
        self.forward.get(key).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(seed: u8) -> MappedKey {
        MappedKey::Device(DeviceId([seed; 32]))
    }

    #[test]
    fn allocation_is_stable_and_reversible() {
        let mut addrs = MappedAddrs::default();
        let first = addrs.get_or_insert(device(1));
        assert_eq!(first, addrs.get_or_insert(device(1)), "same key is stable");
        assert_eq!(addrs.key_for(first), Some(device(1)));
        assert_eq!(addrs.mapped_for(&device(1)), Some(first));
    }

    #[test]
    fn distinct_keys_get_distinct_addresses() {
        let mut addrs = MappedAddrs::default();
        let a = addrs.get_or_insert(device(1));
        let b = addrs.get_or_insert(device(2));
        let ip = addrs.get_or_insert(MappedKey::Ip("203.0.113.5:44330".parse().unwrap()));
        assert_ne!(a, b);
        assert_ne!(a, ip);
        assert_ne!(b, ip);
    }

    #[test]
    fn device_and_ip_keys_do_not_collide() {
        let mut addrs = MappedAddrs::default();
        let by_device = addrs.get_or_insert(device(7));
        let by_ip = addrs.get_or_insert(MappedKey::Ip("198.51.100.9:1".parse().unwrap()));
        assert_ne!(by_device, by_ip);
        assert_eq!(addrs.key_for(by_device), Some(device(7)));
    }

    #[test]
    fn mapped_addresses_round_trip_through_socket_form() {
        let mut addrs = MappedAddrs::default();
        let mapped = addrs.get_or_insert(device(3));
        let socket = mapped.socket_addr();
        assert_eq!(MappedAddr::from_socket(socket), Some(mapped));
    }

    #[test]
    fn real_addresses_are_not_mistaken_for_mapped() {
        assert_eq!(
            MappedAddr::from_socket("192.168.1.4:44330".parse().unwrap()),
            None
        );
        assert_eq!(
            MappedAddr::from_socket("[2001:db8::1]:44330".parse().unwrap()),
            None
        );
        // Right prefix but wrong sentinel port is still rejected.
        assert_eq!(
            MappedAddr::from_socket("[fd15:70a:510b::1]:443".parse().unwrap()),
            None
        );
    }
}
