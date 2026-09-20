//! STUN-based reflexive-address probing.
//!
//! Sends a STUN binding request to one or more servers and collects the
//! server-reflexive address each observes into a [`NetReport`]. This is fabric's
//! real public-address discovery: QUIC's own address-discovery extension only
//! observes the magic socket's *synthetic* addresses, so a STUN server (or, later,
//! the relay acting as one) is what reveals the real `ip:port`.

use std::{net::SocketAddr, time::Duration};

use thiserror::Error;
use tokio::net::UdpSocket;

use crate::{
    report::NetReport,
    stun::{self, TransactionId},
};

/// Errors from a single reflexive-address query.
#[derive(Debug, Error)]
pub enum ProbeError {
    #[error("probe i/o failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("probe timed out waiting for a matching stun response")]
    TimedOut,
}

/// Sends one STUN binding request to `server` and returns the reflexive address
/// it reports. Ignores stray or non-matching datagrams until the response whose
/// transaction id matches our request arrives, or the timeout elapses.
pub async fn query_reflexive_address(
    socket: &UdpSocket,
    server: SocketAddr,
    timeout: Duration,
) -> Result<SocketAddr, ProbeError> {
    let transaction_id = TransactionId::random();
    socket
        .send_to(&stun::encode_binding_request(transaction_id), server)
        .await?;

    let deadline = tokio::time::Instant::now() + timeout;
    let mut buffer = [0u8; 512];
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err(ProbeError::TimedOut);
        }
        let received = match tokio::time::timeout(remaining, socket.recv_from(&mut buffer)).await {
            Ok(result) => result?,
            Err(_) => return Err(ProbeError::TimedOut),
        };
        let (len, from) = received;
        if from != server {
            continue;
        }
        let Ok(response) = stun::decode_binding_response(&buffer[..len]) else {
            continue;
        };
        if response.transaction_id == transaction_id {
            return Ok(response.mapped_address);
        }
    }
}

/// Probes each server in turn and assembles a [`NetReport`]. Servers that fail
/// or time out are skipped, so the report reflects whatever succeeded — probing
/// two or more servers is what lets it detect a varying (symmetric-NAT) mapping.
pub async fn probe(
    socket: &UdpSocket,
    servers: &[SocketAddr],
    per_server_timeout: Duration,
) -> NetReport {
    let mut report = NetReport::default();
    for &server in servers {
        if let Ok(reflexive) = query_reflexive_address(socket, server, per_server_timeout).await {
            report.record(reflexive);
        }
    }
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Spawns a loopback STUN responder that echoes each request's source
    /// address back in an XOR-MAPPED-ADDRESS, and returns its address.
    async fn spawn_stun_responder() -> SocketAddr {
        let responder = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let address = responder.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buffer = [0u8; 512];
            while let Ok((len, from)) = responder.recv_from(&mut buffer).await {
                if len < 20 {
                    continue;
                }
                let raw: [u8; 12] = buffer[8..20].try_into().unwrap();
                let reply = stun::encode_binding_response(TransactionId::from_bytes(raw), from);
                let _ = responder.send_to(&reply, from).await;
            }
        });
        address
    }

    #[tokio::test]
    async fn learns_reflexive_address_from_a_responder() {
        let server = spawn_stun_responder().await;
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let client_address = client.local_addr().unwrap();

        let reflexive = query_reflexive_address(&client, server, Duration::from_secs(2))
            .await
            .unwrap();
        // On loopback the responder sees the client at its own bound address.
        assert_eq!(reflexive, client_address);
    }

    #[tokio::test]
    async fn probe_assembles_a_report_and_times_out_on_dead_servers() {
        let server = spawn_stun_responder().await;
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let client_address = client.local_addr().unwrap();
        // A port nobody is listening on, to exercise the skip-on-timeout path.
        let dead: SocketAddr = "127.0.0.1:9".parse().unwrap();

        let report = probe(&client, &[server, dead], Duration::from_millis(300)).await;
        assert_eq!(report.reflexive_v4, Some(client_address));
        assert!(report.has_reflexive());
    }
}
