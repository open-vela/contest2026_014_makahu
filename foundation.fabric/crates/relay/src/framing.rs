//! Length-delimited framing of [`RelayFrame`]s over a byte stream.
//!
//! The relay runs over TCP+TLS (not QUIC — a QUIC relay would be blocked by the
//! same networks that block direct UDP; TCP/443 traverses them). A reliable byte
//! stream has no datagram boundaries, so each frame is written as a big-endian
//! `u32` length prefix followed by its encoded bytes.

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::wire::{RelayCodecError, RelayFrame};

/// Maximum accepted on-wire frame size, guarding against a hostile length prefix.
pub const MAX_FRAME_LEN: usize = 64 * 1024;

/// Errors reading or writing a framed relay stream.
#[derive(Debug, thiserror::Error)]
pub enum FramingError {
    #[error("relay stream i/o failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("relay frame exceeds the {MAX_FRAME_LEN}-byte maximum")]
    TooLarge,
    #[error("relay frame decode failed: {0}")]
    Codec(#[from] RelayCodecError),
}

/// Writes one frame as a length-prefixed record and flushes.
pub async fn write_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    frame: &RelayFrame,
) -> Result<(), FramingError> {
    let bytes = frame.encode();
    let len = u32::try_from(bytes.len()).map_err(|_| FramingError::TooLarge)?;
    if bytes.len() > MAX_FRAME_LEN {
        return Err(FramingError::TooLarge);
    }
    writer.write_all(&len.to_be_bytes()).await?;
    writer.write_all(&bytes).await?;
    writer.flush().await?;
    Ok(())
}

/// Reads one length-prefixed frame.
pub async fn read_frame<R: AsyncRead + Unpin>(reader: &mut R) -> Result<RelayFrame, FramingError> {
    let mut length = [0u8; 4];
    reader.read_exact(&mut length).await?;
    let len = usize::try_from(u32::from_be_bytes(length)).map_err(|_| FramingError::TooLarge)?;
    if len > MAX_FRAME_LEN {
        return Err(FramingError::TooLarge);
    }
    let mut body = vec![0u8; len];
    reader.read_exact(&mut body).await?;
    Ok(RelayFrame::decode(&body)?)
}

#[cfg(test)]
mod tests {
    use fabric_core::DeviceId;

    use super::*;

    #[tokio::test]
    async fn frames_round_trip_over_a_stream() {
        let (mut client, mut server) = tokio::io::duplex(4096);
        let sent = [
            RelayFrame::ServerChallenge { challenge: [7; 16] },
            RelayFrame::Forward {
                destination: DeviceId([9; 32]),
                payload: vec![1, 2, 3, 4],
            },
            RelayFrame::Pong { nonce: 12345 },
        ];

        let to_send = sent.clone();
        let writer = tokio::spawn(async move {
            for frame in &to_send {
                write_frame(&mut client, frame).await.unwrap();
            }
        });

        for expected in &sent {
            assert_eq!(read_frame(&mut server).await.unwrap(), *expected);
        }
        writer.await.unwrap();
    }

    #[tokio::test]
    async fn oversize_length_prefix_is_rejected() {
        let (mut client, mut server) = tokio::io::duplex(64);
        tokio::spawn(async move {
            // A length prefix far beyond MAX_FRAME_LEN, with no body to follow.
            let _ = client.write_all(&u32::MAX.to_be_bytes()).await;
        });
        assert!(matches!(
            read_frame(&mut server).await,
            Err(FramingError::TooLarge)
        ));
    }
}
