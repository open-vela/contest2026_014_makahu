//! Hub-to-Hub link abstraction and deterministic in-memory fault transport.

use std::{
    pin::Pin,
    sync::{Arc, Mutex},
};

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::{ChannelId, DeviceId, ParticipantId, SessionId};
use thiserror::Error;
use tokio::{
    io::{AsyncRead, AsyncWrite},
    sync::{mpsc, watch},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LinkState {
    Connecting,
    Ready,
    Closed,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StreamOpen {
    pub session_id: SessionId,
    pub epoch: u64,
    pub channel_id: ChannelId,
    pub sender: ParticipantId,
    pub destination_binding: [u8; 16],
    pub flags: u64,
    pub e2ee_header: Bytes,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LinkCloseReason {
    Normal,
    DuplicateConnection,
    AuthenticationFailed,
    ProtocolError,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum LinkError {
    #[error("link is closed")]
    Closed,
    #[error("link queue is full")]
    Backpressure,
    #[error("link receiver disappeared")]
    ReceiverGone,
    #[error("link lock was poisoned")]
    Poisoned,
    #[error("peer violated the Link protocol")]
    ProtocolViolation,
}

pub trait AsyncReadWrite: AsyncRead + AsyncWrite {}
impl<T: AsyncRead + AsyncWrite + ?Sized> AsyncReadWrite for T {}

pub type SendStream = Pin<Box<dyn AsyncWrite + Send>>;
pub type ReceiveStream = Pin<Box<dyn AsyncRead + Send>>;
pub type BiStream = Pin<Box<dyn AsyncReadWrite + Send>>;

#[async_trait]
pub trait FabricLink: Send + Sync {
    fn peer(&self) -> DeviceId;
    fn state(&self) -> watch::Receiver<LinkState>;
    async fn send_control(&self, message: Bytes) -> Result<(), LinkError>;
    async fn receive_control(&self) -> Result<Bytes, LinkError>;
    async fn open_uni(&self, header: StreamOpen) -> Result<SendStream, LinkError>;
    async fn open_bi(&self, header: StreamOpen) -> Result<BiStream, LinkError>;
    async fn accept_stream(&self) -> Result<IncomingStream, LinkError>;
    fn try_send_datagram(&self, datagram: Bytes) -> Result<(), LinkError>;
    async fn receive_datagram(&self) -> Result<Bytes, LinkError>;
    async fn close(&self, reason: LinkCloseReason);
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FaultConfig {
    pub seed: u64,
    pub latency_ticks: u64,
    pub loss_per_million: u32,
    pub duplicate_per_million: u32,
    pub reorder: bool,
    pub disconnect_after_sends: Option<u64>,
    pub queue_capacity: usize,
}

impl Default for FaultConfig {
    fn default() -> Self {
        Self {
            seed: 1,
            latency_ticks: 0,
            loss_per_million: 0,
            duplicate_per_million: 0,
            reorder: false,
            disconnect_after_sends: None,
            queue_capacity: 256,
        }
    }
}

pub enum IncomingStream {
    Uni(StreamOpen, ReceiveStream),
    Bi(StreamOpen, BiStream),
}

enum ScheduledKind {
    Control(Bytes),
    Datagram(Bytes),
}
struct Scheduled {
    due: u64,
    serial: u64,
    kind: ScheduledKind,
}

struct Outbound {
    config: FaultConfig,
    random: u64,
    tick: u64,
    sends: u64,
    serial: u64,
    scheduled: Vec<Scheduled>,
    control: mpsc::Sender<Bytes>,
    datagram: mpsc::Sender<Bytes>,
    streams: mpsc::Sender<IncomingStream>,
}

struct MemoryLinkInner {
    peer: DeviceId,
    state_tx: watch::Sender<LinkState>,
    outbound: Mutex<Outbound>,
    control_rx: tokio::sync::Mutex<mpsc::Receiver<Bytes>>,
    datagram_rx: tokio::sync::Mutex<mpsc::Receiver<Bytes>>,
    streams_rx: tokio::sync::Mutex<mpsc::Receiver<IncomingStream>>,
}

#[derive(Clone)]
pub struct MemoryLink {
    inner: Arc<MemoryLinkInner>,
}

impl MemoryLink {
    #[must_use]
    pub fn pair(
        a: DeviceId,
        b: DeviceId,
        a_to_b: FaultConfig,
        b_to_a: FaultConfig,
    ) -> (Self, Self) {
        let capacity = a_to_b.queue_capacity.max(b_to_a.queue_capacity).max(1);
        let (a_control_tx, a_control_rx) = mpsc::channel(capacity);
        let (b_control_tx, b_control_rx) = mpsc::channel(capacity);
        let (a_datagram_tx, a_datagram_rx) = mpsc::channel(capacity);
        let (b_datagram_tx, b_datagram_rx) = mpsc::channel(capacity);
        let (a_stream_tx, a_stream_rx) = mpsc::channel(capacity);
        let (b_stream_tx, b_stream_rx) = mpsc::channel(capacity);
        let (a_state, _) = watch::channel(LinkState::Ready);
        let (b_state, _) = watch::channel(LinkState::Ready);
        let a_link = Self {
            inner: Arc::new(MemoryLinkInner {
                peer: b,
                state_tx: a_state,
                outbound: Mutex::new(Outbound::new(
                    a_to_b,
                    b_control_tx,
                    b_datagram_tx,
                    b_stream_tx,
                )),
                control_rx: tokio::sync::Mutex::new(a_control_rx),
                datagram_rx: tokio::sync::Mutex::new(a_datagram_rx),
                streams_rx: tokio::sync::Mutex::new(a_stream_rx),
            }),
        };
        let b_link = Self {
            inner: Arc::new(MemoryLinkInner {
                peer: a,
                state_tx: b_state,
                outbound: Mutex::new(Outbound::new(
                    b_to_a,
                    a_control_tx,
                    a_datagram_tx,
                    a_stream_tx,
                )),
                control_rx: tokio::sync::Mutex::new(b_control_rx),
                datagram_rx: tokio::sync::Mutex::new(b_datagram_rx),
                streams_rx: tokio::sync::Mutex::new(b_stream_rx),
            }),
        };
        (a_link, b_link)
    }

    pub fn advance(&self, ticks: u64) -> Result<(), LinkError> {
        let mut outbound = self
            .inner
            .outbound
            .lock()
            .map_err(|_| LinkError::Poisoned)?;
        outbound.tick = outbound.tick.saturating_add(ticks);
        outbound.flush_due()
    }

    fn ensure_ready(&self) -> Result<(), LinkError> {
        if *self.inner.state_tx.borrow() == LinkState::Closed {
            Err(LinkError::Closed)
        } else {
            Ok(())
        }
    }
}

impl Outbound {
    fn new(
        config: FaultConfig,
        control: mpsc::Sender<Bytes>,
        datagram: mpsc::Sender<Bytes>,
        streams: mpsc::Sender<IncomingStream>,
    ) -> Self {
        Self {
            config,
            random: config.seed.max(1),
            tick: 0,
            sends: 0,
            serial: 0,
            scheduled: Vec::new(),
            control,
            datagram,
            streams,
        }
    }

    fn random_million(&mut self) -> u32 {
        let mut value = self.random;
        value ^= value << 13;
        value ^= value >> 7;
        value ^= value << 17;
        self.random = value;
        (value % 1_000_000) as u32
    }

    fn schedule(&mut self, kind: ScheduledKind, allow_loss: bool) -> Result<(), LinkError> {
        self.sends += 1;
        if self
            .config
            .disconnect_after_sends
            .is_some_and(|limit| self.sends > limit)
        {
            return Err(LinkError::Closed);
        }
        if allow_loss && self.random_million() < self.config.loss_per_million {
            return Ok(());
        }
        self.serial += 1;
        let due = self.tick.saturating_add(self.config.latency_ticks);
        let duplicate = self.random_million() < self.config.duplicate_per_million;
        self.scheduled.push(Scheduled {
            due,
            serial: self.serial,
            kind: clone_kind(&kind),
        });
        if duplicate {
            self.serial += 1;
            self.scheduled.push(Scheduled {
                due,
                serial: self.serial,
                kind,
            });
        }
        self.flush_due()
    }

    fn flush_due(&mut self) -> Result<(), LinkError> {
        let mut due = Vec::new();
        let mut pending = Vec::new();
        for item in self.scheduled.drain(..) {
            if item.due <= self.tick {
                due.push(item);
            } else {
                pending.push(item);
            }
        }
        due.sort_by_key(|item| (item.due, item.serial));
        if self.config.reorder {
            due.reverse();
        }
        self.scheduled = pending;
        for item in due {
            let result = match item.kind {
                ScheduledKind::Control(value) => self.control.try_send(value),
                ScheduledKind::Datagram(value) => self.datagram.try_send(value),
            };
            result.map_err(map_send_error)?;
        }
        Ok(())
    }
}

fn clone_kind(kind: &ScheduledKind) -> ScheduledKind {
    match kind {
        ScheduledKind::Control(value) => ScheduledKind::Control(value.clone()),
        ScheduledKind::Datagram(value) => ScheduledKind::Datagram(value.clone()),
    }
}

#[async_trait]
impl FabricLink for MemoryLink {
    fn peer(&self) -> DeviceId {
        self.inner.peer
    }
    fn state(&self) -> watch::Receiver<LinkState> {
        self.inner.state_tx.subscribe()
    }
    async fn send_control(&self, message: Bytes) -> Result<(), LinkError> {
        self.ensure_ready()?;
        self.inner
            .outbound
            .lock()
            .map_err(|_| LinkError::Poisoned)?
            .schedule(ScheduledKind::Control(message), false)
    }
    async fn receive_control(&self) -> Result<Bytes, LinkError> {
        self.inner
            .control_rx
            .lock()
            .await
            .recv()
            .await
            .ok_or(LinkError::ReceiverGone)
    }
    async fn open_uni(&self, header: StreamOpen) -> Result<SendStream, LinkError> {
        self.ensure_ready()?;
        let (local, remote) = tokio::io::duplex(64 * 1024);
        self.inner
            .outbound
            .lock()
            .map_err(|_| LinkError::Poisoned)?
            .streams
            .try_send(IncomingStream::Uni(header, Box::pin(remote)))
            .map_err(map_send_error)?;
        Ok(Box::pin(local))
    }
    async fn open_bi(&self, header: StreamOpen) -> Result<BiStream, LinkError> {
        self.ensure_ready()?;
        let (local, remote) = tokio::io::duplex(64 * 1024);
        self.inner
            .outbound
            .lock()
            .map_err(|_| LinkError::Poisoned)?
            .streams
            .try_send(IncomingStream::Bi(header, Box::pin(remote)))
            .map_err(map_send_error)?;
        Ok(Box::pin(local))
    }
    async fn accept_stream(&self) -> Result<IncomingStream, LinkError> {
        self.inner
            .streams_rx
            .lock()
            .await
            .recv()
            .await
            .ok_or(LinkError::ReceiverGone)
    }
    fn try_send_datagram(&self, datagram: Bytes) -> Result<(), LinkError> {
        self.ensure_ready()?;
        self.inner
            .outbound
            .lock()
            .map_err(|_| LinkError::Poisoned)?
            .schedule(ScheduledKind::Datagram(datagram), true)
    }
    async fn receive_datagram(&self) -> Result<Bytes, LinkError> {
        self.inner
            .datagram_rx
            .lock()
            .await
            .recv()
            .await
            .ok_or(LinkError::ReceiverGone)
    }
    async fn close(&self, _reason: LinkCloseReason) {
        self.inner.state_tx.send_replace(LinkState::Closed);
    }
}

fn map_send_error<T>(error: mpsc::error::TrySendError<T>) -> LinkError {
    match error {
        mpsc::error::TrySendError::Full(value) => {
            drop(value);
            LinkError::Backpressure
        }
        mpsc::error::TrySendError::Closed(value) => {
            drop(value);
            LinkError::ReceiverGone
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn latency_duplicate_and_seed_are_deterministic() {
        let config = FaultConfig {
            seed: 42,
            latency_ticks: 2,
            loss_per_million: 0,
            duplicate_per_million: 1_000_000,
            reorder: true,
            disconnect_after_sends: None,
            queue_capacity: 8,
        };
        let (a, b) = MemoryLink::pair(
            DeviceId([1; 32]),
            DeviceId([2; 32]),
            config,
            FaultConfig::default(),
        );
        a.try_send_datagram(Bytes::from_static(b"one")).unwrap();
        assert!(b.inner.datagram_rx.lock().await.try_recv().is_err());
        a.advance(2).unwrap();
        assert_eq!(
            b.receive_datagram().await.unwrap(),
            Bytes::from_static(b"one")
        );
        assert_eq!(
            b.receive_datagram().await.unwrap(),
            Bytes::from_static(b"one")
        );
    }

    #[tokio::test]
    async fn control_is_not_lost_by_packet_loss_fault() {
        let config = FaultConfig {
            loss_per_million: 1_000_000,
            ..FaultConfig::default()
        };
        let (a, b) = MemoryLink::pair(
            DeviceId([1; 32]),
            DeviceId([2; 32]),
            config,
            FaultConfig::default(),
        );
        a.send_control(Bytes::from_static(b"control"))
            .await
            .unwrap();
        assert_eq!(
            b.receive_control().await.unwrap(),
            Bytes::from_static(b"control")
        );
    }
}
