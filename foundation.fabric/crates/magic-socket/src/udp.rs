//! The plain-UDP transport leaf.
//!
//! Wraps a dual-purpose (v4 or v6) UDP socket plus `noq-udp`'s platform socket
//! state (which enables GSO/GRO and ECN where available), exposing exactly the
//! send/receive primitives the magic socket drives. This is the direct path; the
//! relay transport is a sibling leaf added later. Structure mirrors noq's own
//! tokio `AsyncUdpSocket` implementation so behaviour matches the reference.

use std::{
    io,
    net::SocketAddr,
    num::NonZeroUsize,
    sync::Arc,
    task::{Context, Poll},
};

use noq_udp::{RecvMeta, Transmit, UdpSocketState};
use tokio::{io::Interest, net::UdpSocket};

/// A bound UDP socket with the batched send/recv primitives QUIC needs.
///
/// Cheaply clonable: every clone shares one underlying socket, so a sender clone
/// and the receiving driver operate on the same file descriptor.
#[derive(Clone, Debug)]
pub struct UdpTransport {
    io: Arc<UdpSocket>,
    state: Arc<UdpSocketState>,
}

impl UdpTransport {
    /// Binds a non-blocking UDP socket at `address` (use port 0 for ephemeral).
    pub fn bind(address: SocketAddr) -> io::Result<Self> {
        let socket = std::net::UdpSocket::bind(address)?;
        socket.set_nonblocking(true)?;
        let state = UdpSocketState::new((&socket).into())?;
        let io = UdpSocket::from_std(socket)?;
        Ok(Self {
            io: Arc::new(io),
            state: Arc::new(state),
        })
    }

    /// The local address the socket is bound to.
    pub fn local_addr(&self) -> io::Result<SocketAddr> {
        self.io.local_addr()
    }

    /// Receives one or more datagrams, filling `bufs`/`meta`, or registers for wakeup.
    pub fn poll_recv(
        &self,
        cx: &mut Context<'_>,
        bufs: &mut [io::IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        loop {
            std::task::ready!(self.io.poll_recv_ready(cx))?;
            if let Ok(count) = self.io.try_io(Interest::READABLE, || {
                self.state.recv((&*self.io).into(), bufs, meta)
            }) {
                return Poll::Ready(Ok(count));
            }
        }
    }

    /// Attempts to send `transmit` now; returns `WouldBlock` if the socket is full.
    pub fn try_send(&self, transmit: &Transmit<'_>) -> io::Result<()> {
        self.io.try_io(Interest::WRITABLE, || {
            self.state.send((&*self.io).into(), transmit)
        })
    }

    /// Waits until the socket is writable again.
    pub async fn writable(&self) -> io::Result<()> {
        self.io.writable().await
    }

    /// Registers for wakeup when the socket becomes writable.
    pub fn poll_send_ready(&self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        self.io.poll_send_ready(cx)
    }

    /// Maximum number of datagrams a single GSO transmit may encode.
    #[must_use]
    pub fn max_gso_segments(&self) -> NonZeroUsize {
        self.state.max_gso_segments()
    }

    /// Maximum number of datagrams a single GRO receive may coalesce.
    #[must_use]
    pub fn gro_segments(&self) -> NonZeroUsize {
        self.state.gro_segments()
    }

    /// Whether sent datagrams may be fragmented in flight.
    #[must_use]
    pub fn may_fragment(&self) -> bool {
        self.state.may_fragment()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn datagram_round_trips_over_loopback() {
        let sender = UdpTransport::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let receiver = UdpTransport::bind("127.0.0.1:0".parse().unwrap()).unwrap();
        let destination = receiver.local_addr().unwrap();
        let payload = b"fabric-udp-leaf";

        sender.writable().await.unwrap();
        sender
            .try_send(&Transmit {
                destination,
                ecn: None,
                contents: payload,
                segment_size: None,
                src_ip: None,
            })
            .unwrap();

        let mut buffer = [0u8; 128];
        let mut meta = [RecvMeta::default()];
        let count = std::future::poll_fn(|cx| {
            let mut bufs = [io::IoSliceMut::new(&mut buffer)];
            receiver.poll_recv(cx, &mut bufs, &mut meta)
        })
        .await
        .unwrap();

        assert_eq!(count, 1);
        assert_eq!(&buffer[..meta[0].len], payload);
    }
}
