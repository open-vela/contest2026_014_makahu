use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::{DeviceId, ParticipantId};
use fabric_link::{BiStream, FabricLink, LinkError, StreamOpen};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf},
    sync::{Mutex, OwnedSemaphorePermit, Semaphore, mpsc},
    task::JoinHandle,
};

use crate::{
    AuthorizedMessageChannel, CarrierKind, MessageEnvelope, ReliableMessageTransport,
    TransportError, decode_message, encode_message,
};

pub const RELIABLE_MESSAGES_STREAM_FLAG: u64 = 0x4641_424d_5347_0001;
const MESSAGE_HEADER_BYTES: usize = 84;

#[must_use]
pub fn is_reliable_message_stream(open: &StreamOpen) -> bool {
    open.flags == RELIABLE_MESSAGES_STREAM_FLAG
}

struct ReceivedMessage {
    envelope: MessageEnvelope,
    _buffer_permit: OwnedSemaphorePermit,
}

/// `ReliableMessages` over one authenticated QUIC bidirectional stream.
///
/// The caller routes accepted `StreamOpen` values to `accept`; the stream
/// header and every message are checked against the already-authorized plan.
pub struct QuicMessageTransport {
    channel: AuthorizedMessageChannel,
    writer: Mutex<WriteHalf<BiStream>>,
    inbound: Mutex<mpsc::Receiver<Result<ReceivedMessage, TransportError>>>,
    send_lock: Mutex<()>,
    outbound_budget: Arc<Semaphore>,
    next_sequence: AtomicU64,
    _reader: JoinHandle<()>,
}

impl QuicMessageTransport {
    pub async fn open(
        link: &dyn FabricLink,
        channel: AuthorizedMessageChannel,
        inbound_queue_capacity: usize,
    ) -> Result<Self, TransportError> {
        if link.peer() != channel.peer_device() {
            return Err(TransportError::PeerMismatch);
        }
        let open = StreamOpen {
            session_id: channel.session_id(),
            epoch: channel.epoch(),
            channel_id: channel.channel_id(),
            sender: channel.local_participant(),
            destination_binding: [0; 16],
            flags: RELIABLE_MESSAGES_STREAM_FLAG,
            e2ee_header: Bytes::new(),
        };
        let stream = link.open_bi(open).await.map_err(map_link_error)?;
        Self::start(channel, stream, inbound_queue_capacity)
    }

    pub fn accept(
        peer_device: DeviceId,
        channel: AuthorizedMessageChannel,
        open: StreamOpen,
        stream: BiStream,
        inbound_queue_capacity: usize,
    ) -> Result<Self, TransportError> {
        if !is_reliable_message_stream(&open)
            || open.destination_binding != [0; 16]
            || !open.e2ee_header.is_empty()
        {
            return Err(TransportError::ProtocolViolation);
        }
        channel.validate_stream_open(peer_device, &open)?;
        Self::start(channel, stream, inbound_queue_capacity)
    }

    fn start(
        channel: AuthorizedMessageChannel,
        stream: BiStream,
        inbound_queue_capacity: usize,
    ) -> Result<Self, TransportError> {
        let buffer_limit = channel.max_buffered_bytes().min(Semaphore::MAX_PERMITS);
        if buffer_limit == 0 {
            return Err(TransportError::CapabilityMismatch);
        }
        let (reader, writer) = tokio::io::split(stream);
        let (inbound_tx, inbound_rx) = mpsc::channel(inbound_queue_capacity.max(1));
        let inbound_budget = Arc::new(Semaphore::new(buffer_limit));
        let reader_channel = channel.clone();
        let reader = tokio::spawn(async move {
            read_messages(reader, reader_channel, inbound_tx, inbound_budget).await;
        });
        Ok(Self {
            channel,
            writer: Mutex::new(writer),
            inbound: Mutex::new(inbound_rx),
            send_lock: Mutex::new(()),
            outbound_budget: Arc::new(Semaphore::new(buffer_limit)),
            next_sequence: AtomicU64::new(1),
            _reader: reader,
        })
    }
}

#[async_trait]
impl ReliableMessageTransport for QuicMessageTransport {
    fn carrier(&self) -> CarrierKind {
        CarrierKind::Quic
    }

    async fn send(&self, destination: ParticipantId, payload: Bytes) -> Result<(), TransportError> {
        self.channel.validate_outbound(destination, payload.len())?;
        let _send_guard = self.send_lock.lock().await;
        let permits =
            u32::try_from(payload.len().max(1)).map_err(|_| TransportError::MessageTooLarge)?;
        let _buffer_permit = Arc::clone(&self.outbound_budget)
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
        let frame_len =
            u32::try_from(encoded.len()).map_err(|_| TransportError::MessageTooLarge)?;
        let mut writer = self.writer.lock().await;
        writer
            .write_u32(frame_len)
            .await
            .map_err(|_| TransportError::Closed)?;
        writer
            .write_all(&encoded)
            .await
            .map_err(|_| TransportError::Closed)?;
        writer.flush().await.map_err(|_| TransportError::Closed)
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

impl Drop for QuicMessageTransport {
    fn drop(&mut self) {
        self._reader.abort();
    }
}

async fn read_messages(
    mut reader: ReadHalf<BiStream>,
    channel: AuthorizedMessageChannel,
    inbound: mpsc::Sender<Result<ReceivedMessage, TransportError>>,
    inbound_budget: Arc<Semaphore>,
) {
    let max_encoded = channel
        .max_message_bytes()
        .saturating_add(MESSAGE_HEADER_BYTES);
    let mut last_sequence = 0_u64;
    loop {
        let frame_len = match reader.read_u32().await {
            Ok(length) => usize::try_from(length).unwrap_or(usize::MAX),
            Err(_) => {
                let _ = inbound.send(Err(TransportError::Closed)).await;
                break;
            }
        };
        if !(MESSAGE_HEADER_BYTES..=max_encoded).contains(&frame_len) {
            let _ = inbound.send(Err(TransportError::ProtocolViolation)).await;
            break;
        }
        let mut encoded = vec![0; frame_len];
        if reader.read_exact(&mut encoded).await.is_err() {
            let _ = inbound.send(Err(TransportError::Closed)).await;
            break;
        }
        let message = match decode_message(&encoded) {
            Ok(message) => message,
            Err(error) => {
                let _ = inbound.send(Err(error)).await;
                break;
            }
        };
        if channel.verify_inbound(&message).is_err()
            || message.sequence != last_sequence.saturating_add(1)
        {
            let _ = inbound
                .send(Err(TransportError::UnauthorizedDestination))
                .await;
            break;
        }
        let permits = match u32::try_from(message.payload.len().max(1)) {
            Ok(permits) => permits,
            Err(_) => {
                let _ = inbound.send(Err(TransportError::MessageTooLarge)).await;
                break;
            }
        };
        let permit = match Arc::clone(&inbound_budget)
            .acquire_many_owned(permits)
            .await
        {
            Ok(permit) => permit,
            Err(_) => break,
        };
        let sequence = message.sequence;
        if inbound
            .send(Ok(ReceivedMessage {
                envelope: message,
                _buffer_permit: permit,
            }))
            .await
            .is_err()
        {
            break;
        }
        last_sequence = sequence;
    }
}

fn map_link_error(error: LinkError) -> TransportError {
    match error {
        LinkError::Backpressure => TransportError::Backpressure,
        LinkError::ProtocolViolation => TransportError::ProtocolViolation,
        LinkError::Closed | LinkError::ReceiverGone | LinkError::Poisoned => TransportError::Closed,
    }
}
