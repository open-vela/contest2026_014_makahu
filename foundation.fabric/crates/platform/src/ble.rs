use std::{
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::{DeviceId, ParticipantId};
use tokio::{
    sync::{Mutex, OwnedSemaphorePermit, Semaphore, mpsc, oneshot},
    task::JoinHandle,
    time::{Instant, sleep_until, timeout},
};

use crate::{
    AuthorizedMessageChannel, CarrierKind, MessageEnvelope, ReliableMessageTransport,
    TransportError, decode_message, encode_message,
};

const PROTOCOL_VERSION: u16 = 1;
const HELLO_KIND: u8 = 0;
const DATA_KIND: u8 = 1;
const ACK_KIND: u8 = 2;
const BUSY_KIND: u8 = 3;
const DATA_HEADER_BYTES: usize = 1 + 8 + 2 + 2 + 4;
const ACK_PACKET_BYTES: usize = 1 + 8 + 2;
const HELLO_FRAME_HEADER_BYTES: usize = 1 + 2 + 2;
const ENVELOPE_HEADER_BYTES: usize = 16 + 8 + 16 + 16 + 16 + 8 + 4;
const HELLO_BYTES: usize = 1 + 2 + 16 + 8 + 16 + 32 + 32 + 2 + 4;
const MAX_HELLO_BYTES: usize = 128;
const MAX_SUPPORTED_BLE_MESSAGE_BYTES: usize = 64 * 1024;
const MAX_BLE_FRAGMENTS: usize = 4096;
const DEFAULT_INBOUND_QUEUE_CAPACITY: usize = 8;
const DEFAULT_OUTBOUND_QUEUE_CAPACITY: usize = 8;
const DEFAULT_MAX_MESSAGE_BYTES: usize = 64 * 1024;

#[async_trait]
pub trait BlePacketIo: Send + Sync {
    /// Must provide confidentiality/integrity and reuse the existing paired identity.
    fn authenticated_peer(&self) -> DeviceId;
    /// Maximum bytes accepted by one platform packet write, including framing.
    fn max_packet_bytes(&self) -> usize;
    async fn send_packet(&self, packet: Bytes) -> Result<(), TransportError>;
    /// Must be cancellation safe; the transport runs one dedicated reader task.
    async fn receive_packet(&self) -> Result<Bytes, TransportError>;
}

#[async_trait]
pub trait BleMessagePlatform: Send + Sync {
    async fn open_message_bearer(
        &self,
        peer: DeviceId,
    ) -> Result<Arc<dyn BlePacketIo>, TransportError>;
}

/// Explicit fallback for targets without an implemented GATT data adapter.
pub struct UnsupportedBleMessagePlatform;

#[async_trait]
impl BleMessagePlatform for UnsupportedBleMessagePlatform {
    async fn open_message_bearer(
        &self,
        _peer: DeviceId,
    ) -> Result<Arc<dyn BlePacketIo>, TransportError> {
        Err(TransportError::UnsupportedCapability)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct BleMessageConfig {
    pub max_message_bytes: usize,
    pub inbound_queue_capacity: usize,
    pub outbound_queue_capacity: usize,
    pub acknowledgement_timeout: Duration,
    pub message_timeout: Duration,
    pub max_retries: u8,
    pub connect_timeout: Duration,
}

impl Default for BleMessageConfig {
    fn default() -> Self {
        Self {
            max_message_bytes: DEFAULT_MAX_MESSAGE_BYTES,
            inbound_queue_capacity: DEFAULT_INBOUND_QUEUE_CAPACITY,
            outbound_queue_capacity: DEFAULT_OUTBOUND_QUEUE_CAPACITY,
            acknowledgement_timeout: Duration::from_secs(1),
            message_timeout: Duration::from_secs(30),
            max_retries: 5,
            connect_timeout: Duration::from_secs(10),
        }
    }
}

struct QueuedSend {
    encoded: Bytes,
    sequence: u64,
    completion: oneshot::Sender<Result<(), TransportError>>,
    _buffer_permit: OwnedSemaphorePermit,
}

struct ReceivedMessage {
    envelope: MessageEnvelope,
    _buffer_permit: OwnedSemaphorePermit,
}

pub struct BleMessageTransport {
    channel: AuthorizedMessageChannel,
    outbound: mpsc::Sender<QueuedSend>,
    inbound: Mutex<mpsc::Receiver<Result<ReceivedMessage, TransportError>>>,
    send_lock: Mutex<()>,
    outbound_budget: Arc<Semaphore>,
    next_sequence: AtomicU64,
    driver: JoinHandle<()>,
    _reader: JoinHandle<()>,
}

impl BleMessageTransport {
    pub async fn connect(
        platform: &dyn BleMessagePlatform,
        channel: AuthorizedMessageChannel,
        config: BleMessageConfig,
    ) -> Result<Self, TransportError> {
        let io = timeout(
            config.connect_timeout,
            platform.open_message_bearer(channel.peer_device()),
        )
        .await
        .map_err(|_| TransportError::Timeout)??;
        Self::connect_io(io, channel, config).await
    }

    /// Connect over an already established, identity-bound BLE bearer.
    pub async fn connect_io(
        io: Arc<dyn BlePacketIo>,
        channel: AuthorizedMessageChannel,
        config: BleMessageConfig,
    ) -> Result<Self, TransportError> {
        if config.acknowledgement_timeout.is_zero()
            || config.connect_timeout.is_zero()
            || config.message_timeout < config.acknowledgement_timeout
        {
            return Err(TransportError::CapabilityMismatch);
        }
        if io.authenticated_peer() != channel.peer_device() {
            return Err(TransportError::PeerMismatch);
        }
        let packet_limit = io.max_packet_bytes();
        if packet_limit <= DATA_HEADER_BYTES || packet_limit > usize::from(u16::MAX) {
            return Err(TransportError::CapabilityMismatch);
        }
        let configured_max = config
            .max_message_bytes
            .min(channel.max_message_bytes())
            .min(channel.max_buffered_bytes())
            .min(MAX_SUPPORTED_BLE_MESSAGE_BYTES)
            .min(Semaphore::MAX_PERMITS)
            .min(u32::MAX as usize)
            .min(
                (packet_limit - DATA_HEADER_BYTES)
                    .saturating_mul(MAX_BLE_FRAGMENTS)
                    .saturating_sub(ENVELOPE_HEADER_BYTES),
            );
        if configured_max == 0 || channel.max_buffered_bytes() == 0 {
            return Err(TransportError::CapabilityMismatch);
        }
        let local_hello = encode_hello(&channel, packet_limit, configured_max)?;
        let remote_hello = timeout(config.connect_timeout, async {
            for frame in local_hello {
                io.send_packet(frame).await?;
            }
            receive_hello(io.as_ref(), packet_limit).await
        })
        .await
        .map_err(|_| TransportError::Timeout)??;
        let negotiated_packet_limit = validate_hello(&remote_hello, &channel)?.min(packet_limit);
        if negotiated_packet_limit <= DATA_HEADER_BYTES {
            return Err(TransportError::CapabilityMismatch);
        }
        let negotiated_fragment_capacity = (negotiated_packet_limit - DATA_HEADER_BYTES)
            .saturating_mul(MAX_BLE_FRAGMENTS)
            .saturating_sub(ENVELOPE_HEADER_BYTES);
        let negotiated_max = configured_max
            .min(read_hello_message_limit(&remote_hello)?)
            .min(negotiated_fragment_capacity);
        if negotiated_max == 0 {
            return Err(TransportError::CapabilityMismatch);
        }

        let outbound_limit = channel
            .max_buffered_bytes()
            .min(Semaphore::MAX_PERMITS)
            .max(1);
        let inbound_limit = outbound_limit;
        let outbound_budget = Arc::new(Semaphore::new(outbound_limit));
        let inbound_budget = Arc::new(Semaphore::new(inbound_limit));
        let (outbound, outbound_rx) = mpsc::channel(config.outbound_queue_capacity.max(1));
        let (inbound_tx, inbound) = mpsc::channel(config.inbound_queue_capacity.max(1));
        let (packet_tx, packet_rx) = mpsc::channel(8);
        let reader_io = Arc::clone(&io);
        let reader = tokio::spawn(async move {
            loop {
                match reader_io.receive_packet().await {
                    Ok(packet) => {
                        if packet_tx.send(Ok(packet)).await.is_err() {
                            break;
                        }
                    }
                    Err(error) => {
                        let _ = packet_tx.send(Err(error)).await;
                        break;
                    }
                }
            }
        });
        let driver_io = Arc::clone(&io);
        let driver_channel = channel.clone();
        let driver = tokio::spawn(async move {
            run_driver(
                driver_io,
                driver_channel,
                config,
                negotiated_packet_limit,
                negotiated_max,
                outbound_rx,
                inbound_tx,
                inbound_budget,
                packet_rx,
            )
            .await;
        });
        Ok(Self {
            channel,
            outbound,
            inbound: Mutex::new(inbound),
            send_lock: Mutex::new(()),
            outbound_budget,
            next_sequence: AtomicU64::new(1),
            driver,
            _reader: reader,
        })
    }
}

#[async_trait]
impl ReliableMessageTransport for BleMessageTransport {
    fn carrier(&self) -> CarrierKind {
        CarrierKind::Ble
    }

    async fn send(&self, destination: ParticipantId, payload: Bytes) -> Result<(), TransportError> {
        self.channel.validate_outbound(destination, payload.len())?;
        let _send_guard = self.send_lock.lock().await;
        let permits =
            u32::try_from(payload.len().max(1)).map_err(|_| TransportError::MessageTooLarge)?;
        let permit = Arc::clone(&self.outbound_budget)
            .acquire_many_owned(permits)
            .await
            .map_err(|_| TransportError::Closed)?;
        let sequence = self
            .next_sequence
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |current| {
                current.checked_add(1)
            })
            .map_err(|_| TransportError::Closed)?;
        let envelope = MessageEnvelope {
            session_id: self.channel.session_id(),
            epoch: self.channel.epoch(),
            channel_id: self.channel.channel_id(),
            sender: self.channel.local_participant(),
            destination,
            sequence,
            payload,
        };
        let encoded = encode_message(&envelope)?;
        let (completion, completed) = oneshot::channel();
        self.outbound
            .send(QueuedSend {
                encoded,
                sequence,
                completion,
                _buffer_permit: permit,
            })
            .await
            .map_err(|_| TransportError::Closed)?;
        completed.await.map_err(|_| TransportError::Closed)?
    }

    async fn recv(&self) -> Result<MessageEnvelope, TransportError> {
        let received = self
            .inbound
            .lock()
            .await
            .recv()
            .await
            .ok_or(TransportError::ReceiverGone)??;
        Ok(received.envelope)
    }
}

impl Drop for BleMessageTransport {
    fn drop(&mut self) {
        self.driver.abort();
        self._reader.abort();
    }
}

struct ActiveSend {
    request: QueuedSend,
    frames: Vec<Bytes>,
    fragment_index: usize,
    retry_count: u8,
    deadline: Instant,
    expires_at: Instant,
}

struct Reassembly {
    sequence: u64,
    fragment_count: u16,
    total_bytes: usize,
    fragments: Vec<Option<Bytes>>,
    received_bytes: usize,
    last_progress: Instant,
}

#[allow(clippy::too_many_arguments)]
async fn run_driver(
    io: Arc<dyn BlePacketIo>,
    channel: AuthorizedMessageChannel,
    config: BleMessageConfig,
    packet_limit: usize,
    max_message_bytes: usize,
    mut outbound: mpsc::Receiver<QueuedSend>,
    inbound: mpsc::Sender<Result<ReceivedMessage, TransportError>>,
    inbound_budget: Arc<Semaphore>,
    mut packets: mpsc::Receiver<Result<Bytes, TransportError>>,
) {
    let mut active: Option<ActiveSend> = None;
    let mut reassembly: Option<Reassembly> = None;
    let mut last_completed_sequence = 0_u64;
    loop {
        let deadline = active
            .as_ref()
            .map_or_else(Instant::now, |current| current.deadline);
        tokio::select! {
            request = outbound.recv(), if active.is_none() => {
                let Some(request) = request else { break };
                let frames = match make_data_frames(
                    request.sequence,
                    &request.encoded,
                    packet_limit,
                    max_message_bytes.saturating_add(ENVELOPE_HEADER_BYTES),
                ) {
                    Ok(frames) => frames,
                    Err(error) => {
                        let _ = request.completion.send(Err(error));
                        continue;
                    }
                };
                let Some(first) = frames.first().cloned() else {
                    let _ = request.completion.send(Err(TransportError::ProtocolViolation));
                    continue;
                };
                if let Err(error) = write_packet(
                    io.as_ref(),
                    first,
                    config.acknowledgement_timeout,
                )
                .await
                {
                    let _ = request.completion.send(Err(error));
                    break;
                }
                active = Some(ActiveSend {
                    request,
                    frames,
                    fragment_index: 0,
                    retry_count: 0,
                    deadline: Instant::now() + config.acknowledgement_timeout,
                    expires_at: Instant::now() + config.message_timeout,
                });
            }
            packet = packets.recv() => {
                let Some(packet) = packet else { break };
                let packet = match packet {
                    Ok(packet) => packet,
                    Err(error) => {
                        if let Some(current) = active.take() {
                            let _ = current.request.completion.send(Err(error));
                        }
                        let _ = inbound.try_send(Err(error));
                        break;
                    }
                };
                if packet.len() > packet_limit {
                    let error = TransportError::ProtocolViolation;
                    if let Some(current) = active.take() {
                        let _ = current.request.completion.send(Err(error));
                    }
                    let _ = inbound.try_send(Err(error));
                    break;
                }
                match decode_frame(&packet) {
                    Ok(Frame::Ack { sequence, fragment_index }) => {
                        let Some(current) = active.as_mut() else { continue };
                        let expected = u16::try_from(current.fragment_index).unwrap_or(u16::MAX);
                        if sequence != current.request.sequence || fragment_index != expected {
                            continue;
                        }
                        current.fragment_index += 1;
                        current.retry_count = 0;
                        if current.fragment_index == current.frames.len() {
                            let current = active.take().expect("active send present");
                            let _ = current.request.completion.send(Ok(()));
                        } else {
                            let next = current.frames[current.fragment_index].clone();
                            if let Err(error) = write_packet(
                                io.as_ref(),
                                next,
                                config.acknowledgement_timeout,
                            )
                            .await
                            {
                                if let Some(current) = active.take() {
                                    let _ = current.request.completion.send(Err(error));
                                }
                                let _ = inbound.try_send(Err(error));
                                break;
                            }
                            let now = Instant::now();
                            current.deadline = now + config.acknowledgement_timeout;
                            current.expires_at = now + config.message_timeout;
                        }
                    }
                    Ok(Frame::Busy { sequence, fragment_index }) => {
                        if let Some(current) = active.as_mut().filter(|current| {
                            current.request.sequence == sequence
                                && u16::try_from(current.fragment_index).ok() == Some(fragment_index)
                        }) {
                            current.retry_count = 0;
                            current.deadline = (Instant::now() + config.acknowledgement_timeout)
                                .min(current.expires_at);
                        }
                    }
                    Ok(Frame::Data(data)) => {
                        let (response, delivered) = accept_fragment(
                            data,
                            &channel,
                            max_message_bytes,
                            packet_limit,
                            &inbound,
                            &inbound_budget,
                            &mut reassembly,
                            &mut last_completed_sequence,
                        );
                        if let Some(response) = response {
                            if let Err(error) = write_packet(
                                io.as_ref(),
                                encode_frame(&response),
                                config.acknowledgement_timeout,
                            )
                            .await
                            {
                                if let Some(current) = active.take() {
                                    let _ = current.request.completion.send(Err(error));
                                }
                                let _ = inbound.try_send(Err(error));
                                break;
                            }
                        }
                        if let Err(error) = delivered {
                            let _ = inbound.try_send(Err(error));
                            break;
                        }
                    }
                    Err(_) => {
                        let error = TransportError::ProtocolViolation;
                        if let Some(current) = active.take() {
                            let _ = current.request.completion.send(Err(error));
                        }
                        let _ = inbound.try_send(Err(error));
                        break;
                    }
                }
            }
            _ = sleep_until(deadline), if active.is_some() => {
                let Some(current) = active.as_mut() else { continue };
                if Instant::now() >= current.expires_at || current.retry_count >= config.max_retries {
                    let current = active.take().expect("active send present");
                    let _ = current.request.completion.send(Err(TransportError::Timeout));
                    let _ = inbound.try_send(Err(TransportError::Timeout));
                    break;
                }
                let packet = current.frames[current.fragment_index].clone();
                if let Err(error) = write_packet(
                    io.as_ref(),
                    packet,
                    config.acknowledgement_timeout,
                )
                .await
                {
                    let current = active.take().expect("active send present");
                    let _ = current.request.completion.send(Err(error));
                    let _ = inbound.try_send(Err(error));
                    break;
                }
                current.retry_count += 1;
                current.deadline = Instant::now() + config.acknowledgement_timeout;
            }
            _ = tokio::time::sleep(Duration::from_millis(250)), if reassembly.is_some() => {
                if reassembly.as_ref().is_some_and(|item| {
                    Instant::now().duration_since(item.last_progress) >= config.message_timeout
                }) {
                    reassembly = None;
                }
            }
        }
    }
    if let Some(current) = active {
        let _ = current.request.completion.send(Err(TransportError::Closed));
    }
}

async fn write_packet(
    io: &dyn BlePacketIo,
    packet: Bytes,
    packet_timeout: Duration,
) -> Result<(), TransportError> {
    timeout(packet_timeout, io.send_packet(packet))
        .await
        .map_err(|_| TransportError::Timeout)?
}

enum Frame {
    Data(DataFragment),
    Ack { sequence: u64, fragment_index: u16 },
    Busy { sequence: u64, fragment_index: u16 },
}

struct DataFragment {
    sequence: u64,
    fragment_index: u16,
    fragment_count: u16,
    total_bytes: usize,
    payload: Bytes,
}

fn encode_hello(
    channel: &AuthorizedMessageChannel,
    packet_limit: usize,
    max_message_bytes: usize,
) -> Result<Vec<Bytes>, TransportError> {
    let packet_limit =
        u16::try_from(packet_limit).map_err(|_| TransportError::CapabilityMismatch)?;
    let max_message_bytes =
        u32::try_from(max_message_bytes).map_err(|_| TransportError::CapabilityMismatch)?;
    let mut hello = Vec::with_capacity(HELLO_BYTES);
    hello.push(HELLO_KIND);
    hello.extend_from_slice(&PROTOCOL_VERSION.to_be_bytes());
    hello.extend_from_slice(channel.session_id().0.as_bytes());
    hello.extend_from_slice(&channel.epoch().to_be_bytes());
    hello.extend_from_slice(channel.channel_id().0.as_bytes());
    hello.extend_from_slice(&channel.plan_hash());
    hello.extend_from_slice(&channel.local_device().0);
    hello.extend_from_slice(&packet_limit.to_be_bytes());
    hello.extend_from_slice(&max_message_bytes.to_be_bytes());
    let chunk_size = usize::from(packet_limit)
        .checked_sub(HELLO_FRAME_HEADER_BYTES)
        .filter(|size| *size > 0)
        .ok_or(TransportError::CapabilityMismatch)?;
    let part_count = hello.len().div_ceil(chunk_size);
    let part_count = u16::try_from(part_count).map_err(|_| TransportError::CapabilityMismatch)?;
    let mut frames = Vec::with_capacity(usize::from(part_count));
    for index in 0..part_count {
        let start = usize::from(index) * chunk_size;
        let end = hello.len().min(start.saturating_add(chunk_size));
        let mut frame = Vec::with_capacity(HELLO_FRAME_HEADER_BYTES + end - start);
        frame.push(HELLO_KIND);
        frame.extend_from_slice(&index.to_be_bytes());
        frame.extend_from_slice(&part_count.to_be_bytes());
        frame.extend_from_slice(&hello[start..end]);
        frames.push(Bytes::from(frame));
    }
    Ok(frames)
}

async fn receive_hello(io: &dyn BlePacketIo, packet_limit: usize) -> Result<Bytes, TransportError> {
    let mut parts: Option<Vec<Option<Bytes>>> = None;
    let mut received = 0_usize;
    loop {
        let packet = io.receive_packet().await?;
        if packet.len() > packet_limit || packet.len() <= HELLO_FRAME_HEADER_BYTES {
            return Err(TransportError::ProtocolViolation);
        }
        if packet[0] != HELLO_KIND {
            return Err(TransportError::ProtocolViolation);
        }
        let index = usize::from(read_u16(&packet, 1)?);
        let count = usize::from(read_u16(&packet, 3)?);
        if count == 0 || count > MAX_HELLO_BYTES || index >= count {
            return Err(TransportError::ProtocolViolation);
        }
        let fragments = parts.get_or_insert_with(|| vec![None; count]);
        if fragments.len() != count {
            return Err(TransportError::ProtocolViolation);
        }
        let fragment = Bytes::copy_from_slice(&packet[HELLO_FRAME_HEADER_BYTES..]);
        if fragments[index].is_some() {
            return Err(TransportError::ProtocolViolation);
        }
        received = received
            .checked_add(fragment.len())
            .filter(|total| *total <= MAX_HELLO_BYTES)
            .ok_or(TransportError::ProtocolViolation)?;
        fragments[index] = Some(fragment);
        if fragments.iter().all(Option::is_some) {
            let mut hello = Vec::with_capacity(received);
            for part in fragments.iter() {
                let Some(part) = part else {
                    return Err(TransportError::ProtocolViolation);
                };
                hello.extend_from_slice(part);
            }
            return Ok(Bytes::from(hello));
        }
    }
}

fn validate_hello(
    hello: &[u8],
    channel: &AuthorizedMessageChannel,
) -> Result<usize, TransportError> {
    if hello.len() != HELLO_BYTES
        || hello[0] != HELLO_KIND
        || u16::from_be_bytes(
            hello[1..3]
                .try_into()
                .map_err(|_| TransportError::ProtocolViolation)?,
        ) != PROTOCOL_VERSION
        || &hello[3..19] != channel.session_id().0.as_bytes()
        || u64::from_be_bytes(
            hello[19..27]
                .try_into()
                .map_err(|_| TransportError::ProtocolViolation)?,
        ) != channel.epoch()
        || &hello[27..43] != channel.channel_id().0.as_bytes()
        || hello[43..75] != channel.plan_hash()
        || hello[75..107] != channel.peer_device().0
    {
        return Err(TransportError::CapabilityMismatch);
    }
    let packet_limit = usize::from(u16::from_be_bytes(
        hello[107..109]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ));
    let remote_max = usize::try_from(u32::from_be_bytes(
        hello[109..113]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
    .map_err(|_| TransportError::CapabilityMismatch)?;
    if remote_max == 0 || remote_max > channel.max_message_bytes() {
        return Err(TransportError::CapabilityMismatch);
    }
    Ok(packet_limit)
}

fn read_hello_message_limit(hello: &[u8]) -> Result<usize, TransportError> {
    if hello.len() != 113 {
        return Err(TransportError::ProtocolViolation);
    }
    usize::try_from(u32::from_be_bytes(
        hello[109..113]
            .try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
    .map_err(|_| TransportError::CapabilityMismatch)
}

fn make_data_frames(
    sequence: u64,
    encoded: &[u8],
    packet_limit: usize,
    max_encoded_bytes: usize,
) -> Result<Vec<Bytes>, TransportError> {
    if encoded.len() > max_encoded_bytes {
        return Err(TransportError::MessageTooLarge);
    }
    let chunk_size = packet_limit
        .checked_sub(DATA_HEADER_BYTES)
        .filter(|value| *value > 0)
        .ok_or(TransportError::CapabilityMismatch)?;
    let fragment_count = encoded.len().div_ceil(chunk_size).max(1);
    if fragment_count > MAX_BLE_FRAGMENTS {
        return Err(TransportError::MessageTooLarge);
    }
    let fragment_count =
        u16::try_from(fragment_count).map_err(|_| TransportError::MessageTooLarge)?;
    let total_bytes = u32::try_from(encoded.len()).map_err(|_| TransportError::MessageTooLarge)?;
    let mut frames = Vec::with_capacity(usize::from(fragment_count));
    for index in 0..fragment_count {
        let start = usize::from(index) * chunk_size;
        let end = encoded.len().min(start.saturating_add(chunk_size));
        let mut frame = Vec::with_capacity(DATA_HEADER_BYTES + end.saturating_sub(start));
        frame.push(DATA_KIND);
        frame.extend_from_slice(&sequence.to_be_bytes());
        frame.extend_from_slice(&index.to_be_bytes());
        frame.extend_from_slice(&fragment_count.to_be_bytes());
        frame.extend_from_slice(&total_bytes.to_be_bytes());
        frame.extend_from_slice(&encoded[start..end]);
        frames.push(Bytes::from(frame));
    }
    Ok(frames)
}

fn encode_frame(frame: &Frame) -> Bytes {
    match frame {
        Frame::Ack {
            sequence,
            fragment_index,
        } => {
            let mut packet = Vec::with_capacity(ACK_PACKET_BYTES);
            packet.push(ACK_KIND);
            packet.extend_from_slice(&sequence.to_be_bytes());
            packet.extend_from_slice(&fragment_index.to_be_bytes());
            Bytes::from(packet)
        }
        Frame::Busy {
            sequence,
            fragment_index,
        } => {
            let mut packet = Vec::with_capacity(ACK_PACKET_BYTES);
            packet.push(BUSY_KIND);
            packet.extend_from_slice(&sequence.to_be_bytes());
            packet.extend_from_slice(&fragment_index.to_be_bytes());
            Bytes::from(packet)
        }
        Frame::Data(_) => Bytes::new(),
    }
}

fn decode_frame(packet: &[u8]) -> Result<Frame, TransportError> {
    let kind = *packet.first().ok_or(TransportError::ProtocolViolation)?;
    match kind {
        DATA_KIND if packet.len() >= DATA_HEADER_BYTES => {
            let sequence = read_u64(packet, 1)?;
            let fragment_index = read_u16(packet, 9)?;
            let fragment_count = read_u16(packet, 11)?;
            let total_bytes = usize::try_from(read_u32(packet, 13)?)
                .map_err(|_| TransportError::ProtocolViolation)?;
            if fragment_count == 0
                || sequence == 0
                || fragment_index >= fragment_count
                || total_bytes == 0
                || usize::from(fragment_count) > total_bytes
            {
                return Err(TransportError::ProtocolViolation);
            }
            Ok(Frame::Data(DataFragment {
                sequence,
                fragment_index,
                fragment_count,
                total_bytes,
                payload: Bytes::copy_from_slice(&packet[DATA_HEADER_BYTES..]),
            }))
        }
        ACK_KIND | BUSY_KIND if packet.len() == ACK_PACKET_BYTES => {
            let sequence = read_u64(packet, 1)?;
            let fragment_index = read_u16(packet, 9)?;
            if kind == ACK_KIND {
                Ok(Frame::Ack {
                    sequence,
                    fragment_index,
                })
            } else {
                Ok(Frame::Busy {
                    sequence,
                    fragment_index,
                })
            }
        }
        _ => Err(TransportError::ProtocolViolation),
    }
}

fn accept_fragment(
    fragment: DataFragment,
    channel: &AuthorizedMessageChannel,
    max_message_bytes: usize,
    packet_limit: usize,
    inbound: &mpsc::Sender<Result<ReceivedMessage, TransportError>>,
    inbound_budget: &Arc<Semaphore>,
    reassembly: &mut Option<Reassembly>,
    last_completed_sequence: &mut u64,
) -> (Option<Frame>, Result<(), TransportError>) {
    let busy = Some(Frame::Busy {
        sequence: fragment.sequence,
        fragment_index: fragment.fragment_index,
    });
    let ack = Some(Frame::Ack {
        sequence: fragment.sequence,
        fragment_index: fragment.fragment_index,
    });
    if fragment.sequence <= *last_completed_sequence {
        return (ack, Ok(()));
    }
    if fragment.sequence != last_completed_sequence.saturating_add(1) {
        return (busy, Ok(()));
    }
    if fragment.total_bytes > max_message_bytes.saturating_add(ENVELOPE_HEADER_BYTES) {
        return (None, Err(TransportError::MessageTooLarge));
    }
    let chunk_size = packet_limit.saturating_sub(DATA_HEADER_BYTES);
    let expected_count = fragment.total_bytes.div_ceil(chunk_size.max(1));
    let fragment_start = usize::from(fragment.fragment_index) * chunk_size.max(1);
    let expected_fragment_bytes = fragment
        .total_bytes
        .saturating_sub(fragment_start)
        .min(chunk_size);
    if expected_count != usize::from(fragment.fragment_count)
        || expected_count > MAX_BLE_FRAGMENTS
        || fragment.payload.is_empty()
        || fragment.payload.len() != expected_fragment_bytes
    {
        return (None, Err(TransportError::ProtocolViolation));
    }
    if reassembly.is_none() {
        reassembly.replace(Reassembly {
            sequence: fragment.sequence,
            fragment_count: fragment.fragment_count,
            total_bytes: fragment.total_bytes,
            fragments: vec![None; usize::from(fragment.fragment_count)],
            received_bytes: 0,
            last_progress: Instant::now(),
        });
    }
    let Some(current) = reassembly.as_mut() else {
        return (busy, Err(TransportError::ProtocolViolation));
    };
    if current.sequence != fragment.sequence
        || current.fragment_count != fragment.fragment_count
        || current.total_bytes != fragment.total_bytes
    {
        return (busy, Ok(()));
    }
    let Some(slot) = current
        .fragments
        .get_mut(usize::from(fragment.fragment_index))
    else {
        return (None, Err(TransportError::ProtocolViolation));
    };
    if let Some(existing) = slot {
        if existing != &fragment.payload {
            return (None, Err(TransportError::ProtocolViolation));
        }
    } else {
        current.received_bytes = current
            .received_bytes
            .saturating_add(fragment.payload.len());
        if current.received_bytes > current.total_bytes {
            return (None, Err(TransportError::ProtocolViolation));
        }
        current.last_progress = Instant::now();
        *slot = Some(fragment.payload);
    }
    if current.fragments.iter().any(Option::is_none) {
        return (ack, Ok(()));
    }
    if current.received_bytes != current.total_bytes {
        return (None, Err(TransportError::ProtocolViolation));
    }
    let mut encoded = Vec::with_capacity(current.total_bytes);
    for part in &current.fragments {
        let Some(part) = part else {
            return (None, Err(TransportError::ProtocolViolation));
        };
        encoded.extend_from_slice(part);
    }
    let message = match decode_message(&encoded) {
        Ok(message) => message,
        Err(error) => return (None, Err(error)),
    };
    if message.sequence != fragment.sequence || channel.verify_inbound(&message).is_err() {
        return (None, Err(TransportError::UnauthorizedDestination));
    }
    let permits = u32::try_from(message.payload.len().max(1));
    let Ok(permits) = permits else {
        return (None, Err(TransportError::MessageTooLarge));
    };
    let Ok(permit) = Arc::clone(inbound_budget).try_acquire_many_owned(permits) else {
        return (busy, Ok(()));
    };
    let received = ReceivedMessage {
        envelope: message,
        _buffer_permit: permit,
    };
    match inbound.try_send(Ok(received)) {
        Ok(()) => {
            *last_completed_sequence = fragment.sequence;
            *reassembly = None;
            (ack, Ok(()))
        }
        Err(mpsc::error::TrySendError::Full(received)) => {
            drop(received);
            (busy, Ok(()))
        }
        Err(mpsc::error::TrySendError::Closed(received)) => {
            drop(received);
            (None, Err(TransportError::ReceiverGone))
        }
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, TransportError> {
    let part = bytes
        .get(offset..offset + 2)
        .ok_or(TransportError::ProtocolViolation)?;
    Ok(u16::from_be_bytes(
        part.try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, TransportError> {
    let part = bytes
        .get(offset..offset + 4)
        .ok_or(TransportError::ProtocolViolation)?;
    Ok(u32::from_be_bytes(
        part.try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, TransportError> {
    let part = bytes
        .get(offset..offset + 8)
        .ok_or(TransportError::ProtocolViolation)?;
    Ok(u64::from_be_bytes(
        part.try_into()
            .map_err(|_| TransportError::ProtocolViolation)?,
    ))
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicBool, Ordering};

    use fabric_core::{
        AbilityContractRef, AbilityInstanceId, AbilityKey, AbilityName, AppPrincipal,
        ChannelBinding, ChannelContract, ChannelId, DeliverySemantics, FanoutPolicy, Namespace,
        OsSubject, Participant, PayloadSecurity, Platform, PolicySnapshotId, PortBinding, PortMode,
        PortRef, RoleId, SessionExtensions, TimingPolicy,
    };
    use fabric_link::{FabricLink, FaultConfig, IncomingStream, MemoryLink};

    use super::*;
    use crate::{CarrierCapabilities, CarrierKind, QuicMessageTransport, select_carrier};

    struct MemoryBlePacketIo {
        peer: DeviceId,
        packet_limit: usize,
        outgoing: mpsc::Sender<Bytes>,
        incoming: Mutex<mpsc::Receiver<Bytes>>,
        drop_first_data: AtomicBool,
        drop_first_ack: AtomicBool,
        duplicate_data: bool,
    }

    #[async_trait]
    impl BlePacketIo for MemoryBlePacketIo {
        fn authenticated_peer(&self) -> DeviceId {
            self.peer
        }

        fn max_packet_bytes(&self) -> usize {
            self.packet_limit
        }

        async fn send_packet(&self, packet: Bytes) -> Result<(), TransportError> {
            if packet.len() > self.packet_limit {
                return Err(TransportError::CapabilityMismatch);
            }
            match packet.first().copied() {
                Some(DATA_KIND) if self.drop_first_data.swap(false, Ordering::Relaxed) => {
                    return Ok(());
                }
                Some(ACK_KIND) if self.drop_first_ack.swap(false, Ordering::Relaxed) => {
                    return Ok(());
                }
                Some(DATA_KIND) if self.duplicate_data => {
                    self.outgoing
                        .send(packet.clone())
                        .await
                        .map_err(|_| TransportError::Closed)?;
                }
                _ => {}
            }
            self.outgoing
                .send(packet)
                .await
                .map_err(|_| TransportError::Closed)
        }

        async fn receive_packet(&self) -> Result<Bytes, TransportError> {
            self.incoming
                .lock()
                .await
                .recv()
                .await
                .ok_or(TransportError::Closed)
        }
    }

    fn plan() -> (
        fabric_core::SessionPlan,
        ParticipantId,
        ParticipantId,
        ChannelId,
        DeviceId,
        DeviceId,
    ) {
        let local = ParticipantId::new();
        let remote = ParticipantId::new();
        let local_device = DeviceId([1; 32]);
        let peer_device = DeviceId([2; 32]);
        let local_port = fabric_core::PortId::new();
        let remote_port = fabric_core::PortId::new();
        let channel_id = fabric_core::ChannelId::new();
        let participants = [
            Participant {
                id: local,
                device_id: local_device,
                app_principal: AppPrincipal {
                    platform: Platform::Test,
                    stable_app_id: "test.local".into(),
                    publisher_id: None,
                    signing_digest: None,
                    os_subject: OsSubject("test:local".into()),
                },
                ability_instance: AbilityInstanceId::new(),
                role: RoleId::new("local").unwrap(),
                port_bindings: vec![PortBinding {
                    id: local_port,
                    declaration_id: "messages".into(),
                }],
            },
            Participant {
                id: remote,
                device_id: peer_device,
                app_principal: AppPrincipal {
                    platform: Platform::Test,
                    stable_app_id: "test.remote".into(),
                    publisher_id: None,
                    signing_digest: None,
                    os_subject: OsSubject("test:remote".into()),
                },
                ability_instance: AbilityInstanceId::new(),
                role: RoleId::new("remote").unwrap(),
                port_bindings: vec![PortBinding {
                    id: remote_port,
                    declaration_id: "messages".into(),
                }],
            },
        ];
        let session = fabric_core::SessionPlan {
            session_id: fabric_core::SessionId::new(),
            contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("messages").unwrap(),
                    major: 1,
                },
                protocol_hash: [9; 32],
            },
            epoch: 1,
            coordinator: local,
            participants: participants.to_vec(),
            channels: vec![ChannelBinding {
                id: channel_id,
                sources: vec![
                    PortRef {
                        participant: local,
                        port: local_port,
                    },
                    PortRef {
                        participant: remote,
                        port: remote_port,
                    },
                ],
                destinations: vec![
                    PortRef {
                        participant: local,
                        port: local_port,
                    },
                    PortRef {
                        participant: remote,
                        port: remote_port,
                    },
                ],
                contract: ChannelContract {
                    mode: PortMode::ReliableMessages,
                    delivery: DeliverySemantics::ReliableOrdered,
                    priority: 1,
                    max_message_bytes: Some(1024),
                    max_buffered_bytes: 4096,
                    latency_target_ms: None,
                    idle_timeout_ms: None,
                    fanout: FanoutPolicy::All,
                    payload_security: PayloadSecurity::HubReadable,
                    timing: TimingPolicy::None,
                },
            }],
            extensions: SessionExtensions::default(),
            policy_snapshot: PolicySnapshotId::new(),
        };
        (
            session,
            local,
            remote,
            channel_id,
            local_device,
            peer_device,
        )
    }

    fn packet_pair(
        a_device: DeviceId,
        b_device: DeviceId,
        mtu: usize,
        a_drop_data: bool,
        b_drop_ack: bool,
        duplicate_data: bool,
    ) -> (Arc<MemoryBlePacketIo>, Arc<MemoryBlePacketIo>) {
        let (a_tx, a_rx) = mpsc::channel(256);
        let (b_tx, b_rx) = mpsc::channel(256);
        (
            Arc::new(MemoryBlePacketIo {
                peer: b_device,
                packet_limit: mtu,
                outgoing: b_tx,
                incoming: Mutex::new(a_rx),
                drop_first_data: AtomicBool::new(a_drop_data),
                drop_first_ack: AtomicBool::new(false),
                duplicate_data,
            }),
            Arc::new(MemoryBlePacketIo {
                peer: a_device,
                packet_limit: mtu,
                outgoing: a_tx,
                incoming: Mutex::new(b_rx),
                drop_first_data: AtomicBool::new(false),
                drop_first_ack: AtomicBool::new(b_drop_ack),
                duplicate_data: false,
            }),
        )
    }

    async fn connect_pair(
        session: &fabric_core::SessionPlan,
        local: ParticipantId,
        remote: ParticipantId,
        channel_id: ChannelId,
        local_device: DeviceId,
        peer_device: DeviceId,
        left_io: Arc<MemoryBlePacketIo>,
        right_io: Arc<MemoryBlePacketIo>,
    ) -> (BleMessageTransport, BleMessageTransport) {
        let left_channel = AuthorizedMessageChannel::from_plan(
            session,
            channel_id,
            local_device,
            local,
            peer_device,
        )
        .unwrap();
        let right_channel = AuthorizedMessageChannel::from_plan(
            session,
            channel_id,
            peer_device,
            remote,
            local_device,
        )
        .unwrap();
        let left_io: Arc<dyn BlePacketIo> = left_io;
        let right_io: Arc<dyn BlePacketIo> = right_io;
        let (left, right) = tokio::join!(
            BleMessageTransport::connect_io(left_io, left_channel, test_config()),
            BleMessageTransport::connect_io(right_io, right_channel, test_config()),
        );
        (left.unwrap(), right.unwrap())
    }

    fn test_config() -> BleMessageConfig {
        BleMessageConfig {
            max_message_bytes: 1024,
            inbound_queue_capacity: 4,
            outbound_queue_capacity: 4,
            acknowledgement_timeout: Duration::from_millis(20),
            message_timeout: Duration::from_secs(2),
            max_retries: 5,
            connect_timeout: Duration::from_secs(1),
        }
    }

    #[test]
    fn encrypted_link_does_not_satisfy_end_to_end_contract() {
        let (mut session, local, _, channel, local_device, peer_device) = plan();
        for membership in [
            fabric_core::KeyMembershipPolicy::Session,
            fabric_core::KeyMembershipPolicy::Channel,
        ] {
            session.channels[0].contract.payload_security = PayloadSecurity::EndToEnd {
                suite: fabric_core::CryptoSuiteId("test-e2ee".into()),
                membership,
            };
            assert!(matches!(
                AuthorizedMessageChannel::from_plan(
                    &session,
                    channel,
                    local_device,
                    local,
                    peer_device
                ),
                Err(TransportError::UnsupportedCapability)
            ));
        }
    }

    #[tokio::test]
    async fn fragmented_delivery_retries_lost_ack_and_preserves_envelope() {
        let (session, local, remote, channel, a_device, b_device) = plan();
        let (a_io, b_io) = packet_pair(a_device, b_device, 20, false, true, false);
        let (left, right) = connect_pair(
            &session, local, remote, channel, a_device, b_device, a_io, b_io,
        )
        .await;
        let payload = Bytes::from(vec![0x5a; 300]);
        let (sent, received) = tokio::join!(left.send(remote, payload.clone()), right.recv());
        sent.unwrap();
        let received = received.unwrap();
        assert_eq!(received.payload, payload);
        assert_eq!(received.sender, local);
        assert_eq!(received.destination, remote);
        assert_eq!(received.sequence, 1);
    }

    #[tokio::test]
    async fn duplicate_fragments_do_not_deliver_duplicate_messages() {
        let (session, local, remote, channel, a_device, b_device) = plan();
        let (a_io, b_io) = packet_pair(a_device, b_device, 20, false, false, true);
        let (left, right) = connect_pair(
            &session, local, remote, channel, a_device, b_device, a_io, b_io,
        )
        .await;
        let payload = Bytes::from_static(b"network configuration payload");
        let (sent, received) = tokio::join!(left.send(remote, payload.clone()), right.recv());
        sent.unwrap();
        assert_eq!(received.unwrap().payload, payload);
        assert!(
            timeout(Duration::from_millis(75), right.recv())
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn inbound_queue_backpressure_retries_after_consumer_drains() {
        let (session, local, remote, channel, a_device, b_device) = plan();
        let (a_io, b_io) = packet_pair(a_device, b_device, 20, false, false, false);
        let left_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, a_device, local, b_device)
                .unwrap();
        let right_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, b_device, remote, a_device)
                .unwrap();
        let left_io: Arc<dyn BlePacketIo> = a_io;
        let right_io: Arc<dyn BlePacketIo> = b_io;
        let config = BleMessageConfig {
            inbound_queue_capacity: 1,
            ..test_config()
        };
        let (left, right) = tokio::join!(
            BleMessageTransport::connect_io(left_io, left_channel, config),
            BleMessageTransport::connect_io(right_io, right_channel, config),
        );
        let left = left.unwrap();
        let right = right.unwrap();
        let first_payload = Bytes::from_static(b"queued first");
        left.send(remote, first_payload.clone()).await.unwrap();

        let second_payload = Bytes::from_static(b"backpressured second");
        let (sent, received) = tokio::join!(left.send(remote, second_payload.clone()), async {
            tokio::time::sleep(Duration::from_millis(75)).await;
            let first = right.recv().await.unwrap();
            let second = right.recv().await.unwrap();
            (first, second)
        });
        sent.unwrap();
        let (first, second) = received;
        assert_eq!(first.payload, first_payload);
        assert_eq!(second.payload, second_payload);
    }

    #[tokio::test]
    async fn missing_ack_exhaustion_returns_timeout() {
        let (session, local, remote, channel, a_device, b_device) = plan();
        let (a_io, b_io) = packet_pair(a_device, b_device, 32, true, false, false);
        let left_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, a_device, local, b_device)
                .unwrap();
        let right_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, b_device, remote, a_device)
                .unwrap();
        let left_io: Arc<dyn BlePacketIo> = a_io;
        let right_io: Arc<dyn BlePacketIo> = b_io;
        let config = BleMessageConfig {
            max_retries: 0,
            ..test_config()
        };
        let (left, right) = tokio::join!(
            BleMessageTransport::connect_io(left_io, left_channel, config),
            BleMessageTransport::connect_io(right_io, right_channel, config),
        );
        assert_eq!(
            left.unwrap()
                .send(remote, Bytes::from_static(b"will time out"))
                .await,
            Err(TransportError::Timeout)
        );
        drop(right);
    }

    #[tokio::test]
    async fn unsupported_radio_backend_is_reported_as_unsupported() {
        let (session, local, _, channel, local_device, peer_device) = plan();
        let context = AuthorizedMessageChannel::from_plan(
            &session,
            channel,
            local_device,
            local,
            peer_device,
        )
        .unwrap();
        let result =
            BleMessageTransport::connect(&UnsupportedBleMessagePlatform, context, test_config())
                .await;
        assert!(matches!(result, Err(TransportError::UnsupportedCapability)));
    }

    #[tokio::test]
    async fn quic_and_ble_share_the_authorized_message_envelope() {
        let (session, local, remote, channel, a_device, b_device) = plan();
        let (left_link, right_link) = MemoryLink::pair(
            a_device,
            b_device,
            FaultConfig::default(),
            FaultConfig::default(),
        );
        let left_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, a_device, local, b_device)
                .unwrap();
        let right_channel =
            AuthorizedMessageChannel::from_plan(&session, channel, b_device, remote, a_device)
                .unwrap();
        let (left, right) = tokio::join!(
            QuicMessageTransport::open(&left_link, left_channel, 4),
            async {
                match right_link.accept_stream().await.unwrap() {
                    IncomingStream::Bi(header, stream) => QuicMessageTransport::accept(
                        right_link.peer(),
                        right_channel,
                        header,
                        stream,
                        4,
                    ),
                    IncomingStream::Uni(_, _) => Err(TransportError::ProtocolViolation),
                }
            }
        );
        let left = left.unwrap();
        let right = right.unwrap();
        let payload = Bytes::from_static(b"same application message over QUIC");
        let (sent, received) = tokio::join!(left.send(remote, payload.clone()), right.recv());
        sent.unwrap();
        let received = received.unwrap();
        assert_eq!(received.payload, payload);
        assert_eq!(received.sender, local);
        assert_eq!(received.destination, remote);
    }

    #[test]
    fn ble_selection_is_message_only_and_quic_wins_when_available() {
        let ble_only = CarrierCapabilities {
            ble_messages: true,
            ..CarrierCapabilities::default()
        };
        let allowed = [CarrierKind::Quic, CarrierKind::Ble].into_iter().collect();
        assert_eq!(
            select_carrier(PortMode::ReliableMessages, ble_only, ble_only, &allowed),
            Some(CarrierKind::Ble)
        );
        assert_eq!(
            select_carrier(PortMode::ReliableStream, ble_only, ble_only, &allowed),
            None
        );
        assert_eq!(
            select_carrier(PortMode::Datagram, ble_only, ble_only, &allowed),
            None
        );
        let quic = CarrierCapabilities {
            quic_messages: true,
            ble_messages: true,
            ..CarrierCapabilities::default()
        };
        assert_eq!(
            select_carrier(PortMode::ReliableMessages, quic, quic, &allowed),
            Some(CarrierKind::Quic)
        );
    }
}
