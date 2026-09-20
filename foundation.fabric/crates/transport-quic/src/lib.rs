//! noq-backed authenticated link adapter (noq = n0's Quinn fork with QUIC
//! multipath / address-discovery / NAT-traversal extensions). TLS configuration
//! is supplied by `fabric-identity` composition.

mod tls;
pub use tls::{DeviceCertificate, TlsError, peer_public_key, verify_certificate_device_id};
mod handshake;
pub use handshake::{HandshakeError, HandshakeResult, LinkHandshakeConfig};
mod endpoint;
pub use endpoint::{EndpointError, QuicEndpoint};

use std::{
    pin::Pin,
    sync::Arc,
    task::{Context, Poll},
};

use async_trait::async_trait;
use bytes::Bytes;
use fabric_core::DeviceId;
use fabric_link::{
    BiStream, FabricLink, IncomingStream, LinkCloseReason, LinkError, LinkState, SendStream,
    StreamOpen,
};
use noq::{Connection, RecvStream};
use tokio::{
    io::{AsyncRead, AsyncWrite, ReadBuf},
    sync::{Mutex, watch},
};

const STREAM_MAGIC: u32 = 0x4446_4142;
const MAX_CONTROL_MESSAGE: usize = fabric_protocol::MAX_CONTROL_FRAME_BYTES + 32;

pub struct QuicLink {
    peer: DeviceId,
    connection: Connection,
    control_send: Mutex<noq::SendStream>,
    control_receive: Mutex<noq::RecvStream>,
    state_tx: watch::Sender<LinkState>,
}

impl QuicLink {
    /// Constructs a link after mutual TLS and the application handshake have authenticated the peer.
    #[must_use]
    pub fn from_authenticated_connection(
        connection: Connection,
        peer: DeviceId,
        control_send: noq::SendStream,
        control_receive: noq::RecvStream,
    ) -> Arc<Self> {
        let (state_tx, _) = watch::channel(LinkState::Ready);
        Arc::new(Self {
            peer,
            connection,
            control_send: Mutex::new(control_send),
            control_receive: Mutex::new(control_receive),
            state_tx,
        })
    }
}

/// Deterministic simultaneous-dial rule: the lower `DeviceId` retains outbound.
#[must_use]
pub fn retain_outbound(local: DeviceId, peer: DeviceId) -> bool {
    local < peer
}

#[async_trait]
impl FabricLink for QuicLink {
    fn peer(&self) -> DeviceId {
        self.peer
    }
    fn state(&self) -> watch::Receiver<LinkState> {
        self.state_tx.subscribe()
    }

    async fn send_control(&self, message: Bytes) -> Result<(), LinkError> {
        if message.len() > MAX_CONTROL_MESSAGE {
            return Err(LinkError::Backpressure);
        }
        fabric_protocol::ControlFrame::decode(&message)
            .map_err(|_| LinkError::ProtocolViolation)?;
        let mut stream = self.control_send.lock().await;
        stream
            .write_all(&message)
            .await
            .map_err(|_| LinkError::Closed)?;
        Ok(())
    }

    async fn receive_control(&self) -> Result<Bytes, LinkError> {
        let mut stream = self.control_receive.lock().await;
        read_control_frame_bytes(&mut stream).await
    }

    async fn open_uni(&self, header: StreamOpen) -> Result<SendStream, LinkError> {
        let mut stream = self
            .connection
            .open_uni()
            .await
            .map_err(map_connection_error)?;
        write_stream_header(&mut stream, &header).await?;
        Ok(Box::pin(stream))
    }

    async fn open_bi(&self, header: StreamOpen) -> Result<BiStream, LinkError> {
        let (mut send, receive) = self
            .connection
            .open_bi()
            .await
            .map_err(map_connection_error)?;
        write_stream_header(&mut send, &header).await?;
        Ok(Box::pin(QuicBiStream { send, receive }))
    }

    async fn accept_stream(&self) -> Result<IncomingStream, LinkError> {
        tokio::select! {
            result = self.connection.accept_uni() => {
                let mut receive = result.map_err(map_connection_error)?;
                let header = read_stream_header(&mut receive).await?;
                Ok(IncomingStream::Uni(header, Box::pin(receive)))
            }
            result = self.connection.accept_bi() => {
                let (send, mut receive) = result.map_err(map_connection_error)?;
                let header = read_stream_header(&mut receive).await?;
                Ok(IncomingStream::Bi(
                    header,
                    Box::pin(QuicBiStream { send, receive }),
                ))
            }
        }
    }

    fn try_send_datagram(&self, datagram: Bytes) -> Result<(), LinkError> {
        self.connection
            .send_datagram(datagram)
            .map_err(|error| match error {
                noq::SendDatagramError::TooLarge => LinkError::Backpressure,
                noq::SendDatagramError::UnsupportedByPeer | noq::SendDatagramError::Disabled => {
                    LinkError::ReceiverGone
                }
                noq::SendDatagramError::ConnectionLost(_) => LinkError::Closed,
            })
    }

    async fn receive_datagram(&self) -> Result<Bytes, LinkError> {
        self.connection
            .read_datagram()
            .await
            .map_err(map_connection_error)
    }

    async fn close(&self, _reason: LinkCloseReason) {
        self.connection.close(0_u32.into(), b"fabric link closed");
        self.state_tx.send_replace(LinkState::Closed);
    }
}

async fn read_control_frame_bytes(stream: &mut noq::RecvStream) -> Result<Bytes, LinkError> {
    let mut encoded = Vec::with_capacity(256);
    let frame_length = read_varint(stream, &mut encoded).await?;
    let frame_length = usize::try_from(frame_length).map_err(|_| LinkError::ProtocolViolation)?;
    if frame_length > fabric_protocol::MAX_CONTROL_FRAME_BYTES {
        return Err(LinkError::ProtocolViolation);
    }
    let mut body = vec![0; frame_length];
    stream
        .read_exact(&mut body)
        .await
        .map_err(|_| LinkError::Closed)?;
    encoded.extend_from_slice(&body);
    fabric_protocol::ControlFrame::decode(&encoded).map_err(|_| LinkError::ProtocolViolation)?;
    Ok(Bytes::from(encoded))
}

async fn read_varint(
    stream: &mut noq::RecvStream,
    encoded: &mut Vec<u8>,
) -> Result<u64, LinkError> {
    let mut value = 0_u64;
    for shift in (0..=63).step_by(7) {
        let byte = read_exact::<1>(stream).await?[0];
        encoded.push(byte);
        if shift == 63 && byte > 1 {
            return Err(LinkError::ProtocolViolation);
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(LinkError::ProtocolViolation)
}

async fn write_stream_header(
    stream: &mut noq::SendStream,
    header: &StreamOpen,
) -> Result<(), LinkError> {
    if header.e2ee_header.len() > fabric_protocol::MAX_E2EE_HEADER_BYTES {
        return Err(LinkError::Backpressure);
    }
    let e2ee_length =
        u32::try_from(header.e2ee_header.len()).map_err(|_| LinkError::Backpressure)?;
    stream
        .write_all(&STREAM_MAGIC.to_be_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(header.session_id.0.as_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(&header.epoch.to_be_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(header.channel_id.0.as_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(header.sender.0.as_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(&header.destination_binding)
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(&header.flags.to_be_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(&e2ee_length.to_be_bytes())
        .await
        .map_err(|_| LinkError::Closed)?;
    stream
        .write_all(&header.e2ee_header)
        .await
        .map_err(|_| LinkError::Closed)?;
    Ok(())
}

async fn read_stream_header(stream: &mut noq::RecvStream) -> Result<StreamOpen, LinkError> {
    let mut magic = [0; 4];
    stream
        .read_exact(&mut magic)
        .await
        .map_err(|_| LinkError::Closed)?;
    if u32::from_be_bytes(magic) != STREAM_MAGIC {
        return Err(LinkError::ProtocolViolation);
    }
    let session_id = read_uuid(stream).await?;
    let epoch = read_u64(stream).await?;
    let channel_id = read_uuid(stream).await?;
    let sender = read_uuid(stream).await?;
    let destination_binding = read_exact::<16>(stream).await?;
    let flags = read_u64(stream).await?;
    let e2ee_length = read_u32(stream).await? as usize;
    if e2ee_length > fabric_protocol::MAX_E2EE_HEADER_BYTES {
        return Err(LinkError::ProtocolViolation);
    }
    let mut e2ee_header = vec![0; e2ee_length];
    stream
        .read_exact(&mut e2ee_header)
        .await
        .map_err(|_| LinkError::Closed)?;
    Ok(StreamOpen {
        session_id: fabric_core::SessionId(session_id),
        epoch,
        channel_id: fabric_core::ChannelId(channel_id),
        sender: fabric_core::ParticipantId(sender),
        destination_binding,
        flags,
        e2ee_header: Bytes::from(e2ee_header),
    })
}

async fn read_uuid(stream: &mut noq::RecvStream) -> Result<uuid::Uuid, LinkError> {
    Ok(uuid::Uuid::from_bytes(read_exact::<16>(stream).await?))
}

async fn read_u64(stream: &mut noq::RecvStream) -> Result<u64, LinkError> {
    Ok(u64::from_be_bytes(read_exact::<8>(stream).await?))
}

async fn read_u32(stream: &mut noq::RecvStream) -> Result<u32, LinkError> {
    Ok(u32::from_be_bytes(read_exact::<4>(stream).await?))
}

async fn read_exact<const N: usize>(stream: &mut noq::RecvStream) -> Result<[u8; N], LinkError> {
    let mut value = [0; N];
    stream
        .read_exact(&mut value)
        .await
        .map_err(|_| LinkError::Closed)?;
    Ok(value)
}

fn map_connection_error(_error: noq::ConnectionError) -> LinkError {
    LinkError::Closed
}

struct QuicBiStream {
    send: noq::SendStream,
    receive: RecvStream,
}

impl AsyncRead for QuicBiStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        buffer: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.receive).poll_read(context, buffer)
    }
}

impl AsyncWrite for QuicBiStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        buffer: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        AsyncWrite::poll_write(Pin::new(&mut self.send), context, buffer)
    }
    fn poll_flush(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
    ) -> Poll<std::io::Result<()>> {
        AsyncWrite::poll_flush(Pin::new(&mut self.send), context)
    }
    fn poll_shutdown(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
    ) -> Poll<std::io::Result<()>> {
        AsyncWrite::poll_shutdown(Pin::new(&mut self.send), context)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn duplicate_connection_rule_is_complementary() {
        let a = DeviceId([1; 32]);
        let b = DeviceId([2; 32]);
        assert!(retain_outbound(a, b));
        assert!(!retain_outbound(b, a));
    }
}
