//! The magic socket: a `noq::AsyncUdpSocket` that addresses peers by synthetic
//! [`MappedAddr`](crate::mapped_addrs::MappedAddr) and translates to/from real
//! transport paths underneath, so QUIC can dial by `DeviceId` and stay pinned to
//! one logical peer as its path changes.
//!
//! Address translation is *consistent*: for a peer we dialed, both the packets
//! we send and the packets we receive from it map to the same synthetic address,
//! which is what lets QUIC treat the exchange as one connection. Registering a
//! peer's direct address (via [`MagicHandle::add_direct_addr`]) binds its
//! `DeviceId`'s synthetic address to that real address in both directions.
//!
//! This is the UDP-only stage; a relay transport and biased-RTT path selection
//! slot in as later tasks without changing this translation contract.

use std::{
    collections::{BTreeMap, BTreeSet},
    io,
    net::{Ipv6Addr, SocketAddr, SocketAddrV6},
    num::NonZeroUsize,
    sync::{Arc, Mutex},
    task::{Context, Poll},
    time::{Duration, Instant},
};

use fabric_core::DeviceId;
use fabric_relay::{RelayConnection, RelaySender};
use noq::{AsyncUdpSocket, UdpSender};
use noq_udp::{RecvMeta, Transmit};

use crate::{
    disco::DiscoMessage,
    mapped_addrs::{MappedAddr, MappedAddrs, MappedKey},
    udp::UdpTransport,
};

/// Where an outbound QUIC packet should actually go.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SendTarget {
    /// Over the direct UDP transport to this real address.
    Udp(SocketAddr),
    /// Tunnelled through the relay to this peer.
    Relay(DeviceId),
    /// No usable path for this peer yet — drop (QUIC will retransmit).
    Drop,
}

/// A direct path is considered alive if it produced inbound traffic this recently.
const DIRECT_LIVENESS: Duration = Duration::from_secs(6);
/// How long to keep trying an unproven direct path before falling back to relay.
const DIRECT_PROBE_GRACE: Duration = Duration::from_secs(3);
/// How long to stay on the relay before re-probing a direct path that went dead.
const DIRECT_RETRY_INTERVAL: Duration = Duration::from_secs(15);

/// Passive-liveness health of a peer's direct path (iroh's `Primary`/`Backup`
/// idea, judged from observed traffic rather than an explicit disco probe).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum DirectHealth {
    /// Never sent on; try it optimistically.
    Unknown,
    /// Sent on since this time but not yet confirmed by inbound traffic.
    Probing(Instant),
    /// Confirmed by inbound traffic at this time.
    Alive(Instant),
    /// Gave up at this time after no reply; relay is used until a retry.
    Dead(Instant),
}

/// The routing tables shared between the receive driver and every sender.
///
/// A peer keeps one synthetic address (`MappedKey::Device`) regardless of whether
/// it is reachable directly, via relay, or both; the path is chosen per packet by
/// a biased-RTT rule: prefer a live direct path, fall back to the relay when it
/// goes silent, and re-probe direct periodically.
#[derive(Debug, Default)]
struct Routes {
    mapped: MappedAddrs,
    /// A device's real direct UDP address, used to translate outbound packets.
    direct: BTreeMap<DeviceId, SocketAddr>,
    /// The device that owns a direct address, so inbound marks the path alive.
    direct_owner: BTreeMap<SocketAddr, DeviceId>,
    /// Passive-liveness health of each device's direct path.
    direct_health: BTreeMap<DeviceId, DirectHealth>,
    /// Devices reachable through the relay.
    via_relay: BTreeSet<DeviceId>,
    /// Hole-punch candidates we are currently pinging: candidate → device.
    /// A disco packet arriving *from* one of these addresses confirms it.
    pending_punch: BTreeMap<SocketAddr, DeviceId>,
    /// A real source address to its synthetic address, keeping inbound and
    /// outbound translation consistent for the same peer.
    real_to_mapped: BTreeMap<SocketAddr, MappedAddr>,
}

impl Routes {
    /// Binds `device`'s synthetic address to its real direct `address`, both ways.
    fn add_direct(&mut self, device: DeviceId, address: SocketAddr) -> MappedAddr {
        let mapped = self.mapped.get_or_insert(MappedKey::Device(device));
        self.direct.insert(device, address);
        self.direct_owner.insert(address, device);
        self.real_to_mapped.insert(address, mapped);
        mapped
    }

    /// Binds a bare transport address to a synthetic address, both ways, for
    /// dialing a peer by address rather than identity (e.g. a LAN address).
    fn map_ip(&mut self, address: SocketAddr) -> MappedAddr {
        let mapped = self.mapped.get_or_insert(MappedKey::Ip(address));
        self.real_to_mapped.insert(address, mapped);
        mapped
    }

    /// Marks `device` as reachable through the relay, returning its synthetic address.
    fn add_relay(&mut self, device: DeviceId) -> MappedAddr {
        let mapped = self.mapped.get_or_insert(MappedKey::Device(device));
        self.via_relay.insert(device);
        mapped
    }

    /// Resolves an outbound QUIC destination to the path it should take, biased
    /// toward a direct path that is (or might still be) alive and falling back to
    /// the relay when direct goes silent. Mutates the direct-path health as a
    /// side effect, so it needs `&mut self`.
    fn resolve_send(&mut self, destination: SocketAddr, now: Instant) -> SendTarget {
        let Some(mapped) = MappedAddr::from_socket(destination) else {
            return SendTarget::Udp(destination);
        };
        match self.mapped.key_for(mapped) {
            Some(MappedKey::Device(device)) => self.select_device_path(device, now),
            Some(MappedKey::Ip(address)) => SendTarget::Udp(address),
            None => SendTarget::Drop,
        }
    }

    /// Chooses direct-vs-relay for a device and advances its direct-path health.
    fn select_device_path(&mut self, device: DeviceId, now: Instant) -> SendTarget {
        let Some(&direct) = self.direct.get(&device) else {
            // No direct path known; relay if we can, else nothing.
            return if self.via_relay.contains(&device) {
                SendTarget::Relay(device)
            } else {
                SendTarget::Drop
            };
        };
        let has_relay = self.via_relay.contains(&device);
        let health = self
            .direct_health
            .entry(device)
            .or_insert(DirectHealth::Unknown);
        let use_direct = match *health {
            DirectHealth::Unknown => {
                *health = DirectHealth::Probing(now);
                true
            }
            DirectHealth::Alive(seen) => {
                if now.duration_since(seen) < DIRECT_LIVENESS {
                    true
                } else {
                    // Went quiet — re-probe before giving up on it.
                    *health = DirectHealth::Probing(now);
                    true
                }
            }
            DirectHealth::Probing(started) => {
                if now.duration_since(started) < DIRECT_PROBE_GRACE || !has_relay {
                    true
                } else {
                    *health = DirectHealth::Dead(now);
                    false
                }
            }
            DirectHealth::Dead(since) => {
                if now.duration_since(since) < DIRECT_RETRY_INTERVAL {
                    false
                } else {
                    *health = DirectHealth::Probing(now);
                    true
                }
            }
        };
        if use_direct || !has_relay {
            SendTarget::Udp(direct)
        } else {
            SendTarget::Relay(device)
        }
    }

    /// Resolves an inbound real UDP source to the synthetic address QUIC should
    /// see, interning a fresh one for peers we have never seen. Inbound on a
    /// device's direct address marks that path alive.
    fn resolve_recv(&mut self, source: SocketAddr, now: Instant) -> MappedAddr {
        if let Some(&device) = self.direct_owner.get(&source) {
            self.direct_health.insert(device, DirectHealth::Alive(now));
        }
        if let Some(mapped) = self.real_to_mapped.get(&source) {
            return *mapped;
        }
        let mapped = self.mapped.get_or_insert(MappedKey::Ip(source));
        self.real_to_mapped.insert(source, mapped);
        mapped
    }

    /// Resolves a payload delivered via relay from `device` to that peer's
    /// synthetic address, and enables replying to it over the relay.
    fn resolve_recv_relay(&mut self, device: DeviceId) -> MappedAddr {
        self.via_relay.insert(device);
        self.mapped.get_or_insert(MappedKey::Device(device))
    }

    /// Records `device`'s punch candidates so a disco packet from any of them
    /// confirms that address as the peer's direct path.
    fn register_punch(&mut self, device: DeviceId, candidates: &[SocketAddr]) {
        for &candidate in candidates {
            self.pending_punch.insert(candidate, device);
        }
    }

    /// Confirms a punch: a disco packet arrived from `source`, so if it is a
    /// pending candidate, bind it as its device's live direct path.
    fn confirm_punch(&mut self, source: SocketAddr, now: Instant) {
        if let Some(&device) = self.pending_punch.get(&source) {
            self.add_direct(device, source);
            self.direct_health.insert(device, DirectHealth::Alive(now));
        }
    }
}

/// Monotonic disco ping nonce (only correlates pings to pongs in logs).
static NEXT_PING_NONCE: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Fires a short burst of disco pings at each candidate to open NAT mappings
/// from our side; the peer bursts toward us simultaneously.
fn spawn_ping_burst(udp: UdpTransport, targets: Vec<SocketAddr>) {
    tokio::spawn(async move {
        for _round in 0..3 {
            for &target in &targets {
                let nonce = NEXT_PING_NONCE.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let ping = DiscoMessage::Ping { nonce }.encode();
                let _ = udp.try_send(&Transmit {
                    destination: target,
                    ecn: None,
                    contents: &ping,
                    segment_size: None,
                    src_ip: None,
                });
            }
            tokio::time::sleep(Duration::from_millis(150)).await;
        }
    });
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// A cloneable handle for registering peer paths on a [`MagicSocket`] after it
/// has been handed to a QUIC endpoint (which takes ownership of the socket).
#[derive(Clone, Debug)]
pub struct MagicHandle {
    routes: Arc<Mutex<Routes>>,
    relay: Option<RelaySender>,
}

impl MagicHandle {
    /// Registers a peer's direct UDP address and returns the synthetic address to
    /// dial it by. Idempotent: re-registering keeps the same synthetic address.
    #[must_use]
    pub fn add_direct_addr(&self, device: DeviceId, address: SocketAddr) -> SocketAddr {
        lock(&self.routes).add_direct(device, address).socket_addr()
    }

    /// The synthetic address already allocated for `device`, if any.
    #[must_use]
    pub fn mapped_for_device(&self, device: DeviceId) -> Option<SocketAddr> {
        lock(&self.routes)
            .mapped
            .mapped_for(&MappedKey::Device(device))
            .map(MappedAddr::socket_addr)
    }

    /// Maps a bare transport address to the synthetic address to dial it by, so
    /// QUIC addresses even address-only peers through the synthetic space.
    #[must_use]
    pub fn map_ip(&self, address: SocketAddr) -> SocketAddr {
        lock(&self.routes).map_ip(address).socket_addr()
    }

    /// Marks `device` as reachable through the relay and returns the synthetic
    /// address to dial it by. The socket must have been bound with a relay.
    #[must_use]
    pub fn add_relay_addr(&self, device: DeviceId) -> SocketAddr {
        lock(&self.routes).add_relay(device).socket_addr()
    }

    /// Starts a hole punch toward `device`: advertises our direct-address
    /// `candidates` to it through the relay (a disco call-me-maybe). The peer
    /// pings them, we pong; whichever disco packet survives both NATs confirms a
    /// direct path, and the path selector migrates traffic to it. For a mutual
    /// punch the peer calls this too, with its own candidates.
    pub fn punch_via_relay(&self, device: DeviceId, candidates: Vec<SocketAddr>) {
        if let Some(relay) = &self.relay {
            let message = DiscoMessage::CallMeMaybe { candidates };
            relay.try_send(device, message.encode());
        }
    }

    /// Whether this socket was bound with a relay attached.
    #[must_use]
    pub fn has_relay(&self) -> bool {
        self.relay.is_some()
    }

    /// The peer's confirmed direct path, if any: `(address, currently_alive)`.
    #[must_use]
    pub fn direct_path(&self, device: DeviceId) -> Option<(SocketAddr, bool)> {
        let routes = lock(&self.routes);
        let address = routes.direct.get(&device).copied()?;
        let alive = matches!(
            routes.direct_health.get(&device),
            Some(DirectHealth::Alive(seen)) if seen.elapsed() < DIRECT_LIVENESS
        );
        Some((address, alive))
    }
}

/// A `noq::AsyncUdpSocket` that translates between synthetic peer addresses and
/// real transport paths — a direct UDP path and, optionally, a relay.
#[derive(Debug)]
pub struct MagicSocket {
    udp: UdpTransport,
    routes: Arc<Mutex<Routes>>,
    relay: Option<RelayConnection>,
    relay_sender: Option<RelaySender>,
}

impl MagicSocket {
    /// Binds the underlying UDP transport (use port 0 for an ephemeral port).
    pub fn bind(address: SocketAddr) -> io::Result<Self> {
        Ok(Self {
            udp: UdpTransport::bind(address)?,
            routes: Arc::new(Mutex::new(Routes::default())),
            relay: None,
            relay_sender: None,
        })
    }

    /// Binds the UDP transport and attaches an authenticated relay connection as a
    /// second path, so peers registered via [`MagicHandle::add_relay_addr`] are
    /// reached by tunnelling through the relay when no direct path exists.
    pub fn bind_with_relay(address: SocketAddr, relay: RelayConnection) -> io::Result<Self> {
        let relay_sender = Some(relay.sender());
        Ok(Self {
            udp: UdpTransport::bind(address)?,
            routes: Arc::new(Mutex::new(Routes::default())),
            relay: Some(relay),
            relay_sender,
        })
    }

    /// A handle for registering peer paths after this socket is moved into an endpoint.
    #[must_use]
    pub fn handle(&self) -> MagicHandle {
        MagicHandle {
            routes: Arc::clone(&self.routes),
            relay: self.relay_sender.clone(),
        }
    }

    /// The real local address the UDP transport is bound to.
    pub fn real_local_addr(&self) -> io::Result<SocketAddr> {
        self.udp.local_addr()
    }
}

impl AsyncUdpSocket for MagicSocket {
    fn create_sender(&self) -> std::pin::Pin<Box<dyn UdpSender>> {
        Box::pin(MagicSender {
            udp: self.udp.clone(),
            routes: Arc::clone(&self.routes),
            relay: self.relay_sender.clone(),
        })
    }

    fn poll_recv(
        &mut self,
        cx: &mut Context<'_>,
        bufs: &mut [io::IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        // Relay inbound first. Disco payloads (call-me-maybe) are consumed here;
        // a QUIC payload is presented as one received datagram from the peer's
        // synthetic address, so QUIC cannot tell it from a direct one.
        while let Some(relay) = self.relay.as_mut() {
            match relay.poll_recv(cx) {
                Poll::Ready(Some((device, payload))) => {
                    if DiscoMessage::is_disco(&payload) {
                        if let Some(DiscoMessage::CallMeMaybe { candidates }) =
                            DiscoMessage::decode(&payload)
                        {
                            lock(&self.routes).register_punch(device, &candidates);
                            spawn_ping_burst(self.udp.clone(), candidates);
                        }
                        continue; // consumed; poll the relay again
                    }
                    if let (Some(buf), Some(slot)) = (bufs.first_mut(), meta.first_mut()) {
                        let len = payload.len().min(buf.len());
                        buf[..len].copy_from_slice(&payload[..len]);
                        let mapped = lock(&self.routes).resolve_recv_relay(device);
                        // `RecvMeta` is non-exhaustive, so start from default and set fields.
                        let mut recv_meta = RecvMeta::default();
                        recv_meta.addr = mapped.socket_addr();
                        recv_meta.len = len;
                        recv_meta.stride = len;
                        *slot = recv_meta;
                        return Poll::Ready(Ok(1));
                    }
                    return Poll::Ready(Ok(0));
                }
                Poll::Ready(None) => {
                    self.relay = None; // relay closed
                    break;
                }
                Poll::Pending => break,
            }
        }

        // UDP inbound. Disco ping/pongs are answered and consumed; surviving
        // entries are compacted forward and translated for QUIC.
        loop {
            let count = std::task::ready!(self.udp.poll_recv(cx, bufs, meta))?;
            let now = Instant::now();
            let mut kept = 0;
            for index in 0..count {
                let len = meta[index].len;
                let source = meta[index].addr;
                if DiscoMessage::is_disco(&bufs[index][..len]) {
                    match DiscoMessage::decode(&bufs[index][..len]) {
                        Some(DiscoMessage::Ping { nonce }) => {
                            lock(&self.routes).confirm_punch(source, now);
                            let pong = DiscoMessage::Pong { nonce }.encode();
                            let _ = self.udp.try_send(&Transmit {
                                destination: source,
                                ecn: None,
                                contents: &pong,
                                segment_size: None,
                                src_ip: None,
                            });
                        }
                        Some(DiscoMessage::Pong { .. }) => {
                            lock(&self.routes).confirm_punch(source, now);
                        }
                        // Call-me-maybe only rides the relay; anything else is dropped.
                        Some(DiscoMessage::CallMeMaybe { .. }) | None => {}
                    }
                    continue; // consumed; not for QUIC
                }
                if kept != index {
                    let (head, tail) = bufs.split_at_mut(index);
                    head[kept][..len].copy_from_slice(&tail[0][..len]);
                    meta[kept] = meta[index];
                }
                kept += 1;
            }
            if kept == 0 {
                continue; // everything was disco; poll the socket again
            }
            let mut routes = lock(&self.routes);
            for entry in meta.iter_mut().take(kept) {
                entry.addr = routes.resolve_recv(entry.addr, now).socket_addr();
            }
            return Poll::Ready(Ok(kept));
        }
    }

    fn local_addr(&self) -> io::Result<SocketAddr> {
        // Present an IPv6 address to QUIC so it treats every peer's synthetic
        // (IPv6 ULA) address as reachable. noq refuses to dial an IPv6 peer from
        // a non-IPv6 endpoint, and it derives that family flag from this call.
        // The real socket family lives below the translation boundary and never
        // reaches QUIC.
        let port = self.udp.local_addr()?.port();
        Ok(SocketAddr::V6(SocketAddrV6::new(
            Ipv6Addr::LOCALHOST,
            port,
            0,
            0,
        )))
    }

    fn max_receive_segments(&self) -> NonZeroUsize {
        self.udp.gro_segments()
    }

    fn may_fragment(&self) -> bool {
        self.udp.may_fragment()
    }
}

/// The send half of a [`MagicSocket`]; noq creates one per sending task.
#[derive(Debug)]
struct MagicSender {
    udp: UdpTransport,
    routes: Arc<Mutex<Routes>>,
    relay: Option<RelaySender>,
}

impl UdpSender for MagicSender {
    fn poll_send(
        self: std::pin::Pin<&mut Self>,
        transmit: &Transmit<'_>,
        cx: &mut Context<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let destination =
            match lock(&this.routes).resolve_send(transmit.destination, Instant::now()) {
                SendTarget::Udp(destination) => destination,
                SendTarget::Relay(device) => {
                    if let Some(relay) = &this.relay {
                        // Best-effort tunnel; a dropped packet is retransmitted by QUIC.
                        relay.try_send(device, transmit.contents.to_vec());
                    }
                    return Poll::Ready(Ok(()));
                }
                // No path to this peer yet — drop; QUIC retransmits once one is known.
                SendTarget::Drop => return Poll::Ready(Ok(())),
            };
        loop {
            std::task::ready!(this.udp.poll_send_ready(cx))?;
            let translated = Transmit {
                destination,
                ecn: transmit.ecn,
                contents: transmit.contents,
                segment_size: transmit.segment_size,
                src_ip: None,
            };
            match this.udp.try_send(&translated) {
                Ok(()) => return Poll::Ready(Ok(())),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => {}
                Err(error) => return Poll::Ready(Err(error)),
            }
        }
    }

    fn max_transmit_segments(&self) -> NonZeroUsize {
        self.udp.max_gso_segments()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn translates_mapped_destination_and_rewrites_source() {
        let client = MagicSocket::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let mut server = MagicSocket::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let server_real = server.real_local_addr().unwrap();

        // Client dials the server's device by its synthetic address.
        let device_b = DeviceId([2; 32]);
        let mapped_b = client.handle().add_direct_addr(device_b, server_real);
        assert!(
            MappedAddr::from_socket(mapped_b).is_some(),
            "dial address is synthetic, not the real one"
        );

        let mut sender = client.create_sender();
        let payload = b"through-the-magic-socket";
        std::future::poll_fn(|cx| {
            sender.as_mut().poll_send(
                &Transmit {
                    destination: mapped_b,
                    ecn: None,
                    contents: payload,
                    segment_size: None,
                    src_ip: None,
                },
                cx,
            )
        })
        .await
        .unwrap();

        let mut buffer = [0u8; 128];
        let mut meta = [RecvMeta::default()];
        let count = std::future::poll_fn(|cx| {
            let mut bufs = [io::IoSliceMut::new(&mut buffer)];
            server.poll_recv(cx, &mut bufs, &mut meta)
        })
        .await
        .unwrap();

        assert_eq!(count, 1);
        assert_eq!(&buffer[..meta[0].len], payload);
        // The server never registered the client, so it interns a fresh synthetic
        // source address rather than exposing the client's real ip:port to QUIC.
        assert!(
            MappedAddr::from_socket(meta[0].addr).is_some(),
            "inbound source is rewritten to a synthetic address"
        );
    }

    #[test]
    fn outbound_and_inbound_map_to_the_same_synthetic_address() {
        // A peer we dialed must look identical whether we're sending to it or
        // receiving from it, or QUIC would see two different connections.
        let mut routes = Routes::default();
        let device = DeviceId([9; 32]);
        let real: SocketAddr = "192.0.2.7:44330".parse().unwrap();
        let mapped = routes.add_direct(device, real);
        let now = Instant::now();
        assert_eq!(
            routes.resolve_send(mapped.socket_addr(), now),
            SendTarget::Udp(real)
        );
        assert_eq!(routes.resolve_recv(real, now), mapped);
    }

    #[test]
    fn non_mapped_destinations_pass_through() {
        let mut routes = Routes::default();
        let real: SocketAddr = "198.51.100.1:5000".parse().unwrap();
        assert_eq!(
            routes.resolve_send(real, Instant::now()),
            SendTarget::Udp(real)
        );
    }

    #[test]
    fn a_relay_only_peer_resolves_to_the_relay_path() {
        let mut routes = Routes::default();
        let now = Instant::now();
        let device = DeviceId([8; 32]);
        let mapped = routes.add_relay(device);
        assert_eq!(
            routes.resolve_send(mapped.socket_addr(), now),
            SendTarget::Relay(device)
        );

        // Once a direct address is known it is tried optimistically over the relay.
        let direct: SocketAddr = "192.0.2.9:44330".parse().unwrap();
        routes.add_direct(device, direct);
        assert_eq!(
            routes.resolve_send(mapped.socket_addr(), now),
            SendTarget::Udp(direct)
        );
    }

    #[test]
    fn a_silent_direct_path_falls_back_to_the_relay_then_retries() {
        let mut routes = Routes::default();
        let device = DeviceId([12; 32]);
        let direct: SocketAddr = "192.0.2.1:44330".parse().unwrap();
        let mapped = routes.add_direct(device, direct);
        routes.add_relay(device);
        let key = mapped.socket_addr();
        let t0 = Instant::now();

        // Optimistically tries direct, and keeps trying within the probe grace.
        assert_eq!(routes.resolve_send(key, t0), SendTarget::Udp(direct));
        assert_eq!(
            routes.resolve_send(key, t0 + Duration::from_secs(1)),
            SendTarget::Udp(direct)
        );
        // After the grace with no inbound, direct is declared dead → relay.
        assert_eq!(
            routes.resolve_send(key, t0 + Duration::from_secs(4)),
            SendTarget::Relay(device)
        );
        // Stays on the relay during the retry interval.
        assert_eq!(
            routes.resolve_send(key, t0 + Duration::from_secs(10)),
            SendTarget::Relay(device)
        );
        // After the retry interval, direct is probed again.
        assert_eq!(
            routes.resolve_send(key, t0 + Duration::from_secs(30)),
            SendTarget::Udp(direct)
        );
    }

    #[test]
    fn a_live_direct_path_is_preferred_over_the_relay() {
        let mut routes = Routes::default();
        let device = DeviceId([13; 32]);
        let direct: SocketAddr = "192.0.2.2:44330".parse().unwrap();
        let mapped = routes.add_direct(device, direct);
        routes.add_relay(device);
        let key = mapped.socket_addr();
        let t0 = Instant::now();

        // Inbound on the direct address confirms it alive...
        routes.resolve_recv(direct, t0);
        // ...so even long after the probe grace it is preferred over the relay.
        assert_eq!(
            routes.resolve_send(key, t0 + Duration::from_secs(5)),
            SendTarget::Udp(direct)
        );
    }

    #[tokio::test]
    async fn tunnels_a_datagram_through_the_relay() {
        use std::time::Duration;

        use fabric_identity::DeviceIdentity;
        use fabric_relay::{RelayConnection, RelayServer};

        let server = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let relay_addr = server.local_addr();

        let id_a = DeviceIdentity::from_seed([10; 32]);
        let id_b = DeviceIdentity::from_seed([11; 32]);
        let conn_a = RelayConnection::connect(relay_addr, &id_a).await.unwrap();
        let conn_b = RelayConnection::connect(relay_addr, &id_b).await.unwrap();

        let socket_a =
            MagicSocket::bind_with_relay("127.0.0.1:0".parse().unwrap(), conn_a).unwrap();
        let mut socket_b =
            MagicSocket::bind_with_relay("127.0.0.1:0".parse().unwrap(), conn_b).unwrap();

        // A can only reach B via the relay (no direct address registered).
        let mapped_b = socket_a.handle().add_relay_addr(id_b.device_id());

        let mut sender = socket_a.create_sender();
        let payload = b"quic-over-relay";
        std::future::poll_fn(|cx| {
            sender.as_mut().poll_send(
                &Transmit {
                    destination: mapped_b,
                    ecn: None,
                    contents: payload,
                    segment_size: None,
                    src_ip: None,
                },
                cx,
            )
        })
        .await
        .unwrap();

        let mut buffer = [0u8; 128];
        let mut meta = [RecvMeta::default()];
        let count = tokio::time::timeout(
            Duration::from_secs(5),
            std::future::poll_fn(|cx| {
                let mut bufs = [io::IoSliceMut::new(&mut buffer)];
                socket_b.poll_recv(cx, &mut bufs, &mut meta)
            }),
        )
        .await
        .expect("relayed datagram never arrived")
        .unwrap();

        assert_eq!(count, 1);
        assert_eq!(&buffer[..meta[0].len], payload);
        // B sees the payload as coming from A's synthetic address.
        assert!(MappedAddr::from_socket(meta[0].addr).is_some());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn punch_upgrades_relay_peers_to_a_live_direct_path() {
        use std::time::Duration;

        use fabric_identity::DeviceIdentity;
        use fabric_relay::{RelayConnection, RelayServer};

        let server = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let relay_addr = server.local_addr();

        let id_a = DeviceIdentity::from_seed([20; 32]);
        let id_b = DeviceIdentity::from_seed([21; 32]);
        let conn_a = RelayConnection::connect(relay_addr, &id_a).await.unwrap();
        let conn_b = RelayConnection::connect(relay_addr, &id_b).await.unwrap();

        let mut socket_a =
            MagicSocket::bind_with_relay("127.0.0.1:0".parse().unwrap(), conn_a).unwrap();
        let mut socket_b =
            MagicSocket::bind_with_relay("127.0.0.1:0".parse().unwrap(), conn_b).unwrap();
        let addr_a = socket_a.real_local_addr().unwrap();
        let addr_b = socket_b.real_local_addr().unwrap();
        let handle_a = socket_a.handle();
        let handle_b = socket_b.handle();

        // Peers only know each other via the relay; no direct paths registered.
        let _ = handle_a.add_relay_addr(id_b.device_id());
        let _ = handle_b.add_relay_addr(id_a.device_id());
        assert_eq!(handle_a.direct_path(id_b.device_id()), None);

        // Drive both sockets' receive paths, as a QUIC endpoint would.
        let driver_a = tokio::spawn(async move {
            let mut buffer = [0u8; 1500];
            let mut meta = [RecvMeta::default()];
            std::future::poll_fn(|cx| {
                let mut bufs = [io::IoSliceMut::new(&mut buffer)];
                socket_a.poll_recv(cx, &mut bufs, &mut meta)
            })
            .await
        });
        let driver_b = tokio::spawn(async move {
            let mut buffer = [0u8; 1500];
            let mut meta = [RecvMeta::default()];
            std::future::poll_fn(|cx| {
                let mut bufs = [io::IoSliceMut::new(&mut buffer)];
                socket_b.poll_recv(cx, &mut bufs, &mut meta)
            })
            .await
        });

        // Mutual call-me-maybe through the relay triggers simultaneous bursts.
        handle_a.punch_via_relay(id_b.device_id(), vec![addr_a]);
        handle_b.punch_via_relay(id_a.device_id(), vec![addr_b]);

        // Wait for the punch to confirm on both sides.
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        loop {
            let a_done = handle_a.direct_path(id_b.device_id()) == Some((addr_b, true));
            let b_done = handle_b.direct_path(id_a.device_id()) == Some((addr_a, true));
            if a_done && b_done {
                break;
            }
            assert!(
                tokio::time::Instant::now() < deadline,
                "punch did not confirm: a={:?} b={:?}",
                handle_a.direct_path(id_b.device_id()),
                handle_b.direct_path(id_a.device_id()),
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }

        driver_a.abort();
        driver_b.abort();
    }
}
