//! The relay's network layer: a running server and a client connection.
//!
//! The framing, handshake, and routing are all transport-agnostic, so the
//! connection core is generic over its stream halves: [`RelayServer::bind`] /
//! [`RelayConnection::connect`] run over plain TCP, while [`RelayServer::bind_tls`]
//! / [`RelayConnection::connect_tls`] wrap the same core in TLS (server
//! authentication + metadata confidentiality) with nothing else changing. The
//! relayed payloads are already end-to-end encrypted QUIC either way.

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        Arc, Mutex, MutexGuard, PoisonError,
        atomic::{AtomicU64, Ordering},
    },
    task::{Context, Poll},
};

use fabric_core::DeviceId;
use fabric_identity::DeviceIdentity;
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{TcpListener, TcpStream},
    sync::mpsc,
    task::JoinHandle,
};
use tokio_rustls::{TlsAcceptor, TlsConnector};

use crate::{
    framing::{FramingError, read_frame, write_frame},
    handshake::{RelayChallenge, client_auth},
    server::{ConnId, RelayRouter},
    tls::{self, TlsError},
    wire::RelayFrame,
};

/// Bounded per-connection send queue depth; when full, packets are dropped
/// (best-effort forwarding, preserving the tunnelled QUIC's own loss recovery).
const OUTBOUND_QUEUE: usize = 256;
/// Bounded inbound delivery queue depth for a client.
const INBOUND_QUEUE: usize = 256;

/// Errors from the relay network layer.
#[derive(Debug, thiserror::Error)]
pub enum RelayNetError {
    #[error("relay network i/o failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("relay framing failed: {0}")]
    Framing(#[from] FramingError),
    #[error("relay authentication failed")]
    Auth,
    #[error("relay protocol violation")]
    Protocol,
    #[error("relay connection is closed")]
    Closed,
    #[error("relay TLS setup failed: {0}")]
    Tls(#[from] TlsError),
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

type ConnTable = Arc<Mutex<HashMap<ConnId, mpsc::Sender<RelayFrame>>>>;

/// A running relay server. Dropping it stops accepting new connections.
#[derive(Debug)]
pub struct RelayServer {
    local_addr: SocketAddr,
    accept_task: JoinHandle<()>,
}

impl RelayServer {
    /// Binds a plain-TCP relay at `address` (use port 0 for an ephemeral port)
    /// and starts accepting clients in the background.
    pub async fn bind(address: SocketAddr) -> Result<Self, RelayNetError> {
        Self::spawn(address, None).await
    }

    /// Binds a TLS relay, returning the server and the SHA-256 certificate
    /// fingerprint clients must pin (via [`RelayConnection::connect_tls`]).
    pub async fn bind_tls(address: SocketAddr) -> Result<(Self, [u8; 32]), RelayNetError> {
        let (config, fingerprint) = tls::server_config()?;
        let server = Self::spawn(address, Some(TlsAcceptor::from(config))).await?;
        Ok((server, fingerprint))
    }

    async fn spawn(
        address: SocketAddr,
        acceptor: Option<TlsAcceptor>,
    ) -> Result<Self, RelayNetError> {
        let listener = TcpListener::bind(address).await?;
        let local_addr = listener.local_addr()?;
        let accept_task = tokio::spawn(accept_loop(listener, acceptor));
        Ok(Self {
            local_addr,
            accept_task,
        })
    }

    /// The address the relay is listening on.
    #[must_use]
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }
}

impl Drop for RelayServer {
    fn drop(&mut self) {
        self.accept_task.abort();
    }
}

async fn accept_loop(listener: TcpListener, acceptor: Option<TlsAcceptor>) {
    let router = Arc::new(Mutex::new(RelayRouter::default()));
    let conns: ConnTable = Arc::new(Mutex::new(HashMap::new()));
    let next_id = AtomicU64::new(0);
    while let Ok((stream, _)) = listener.accept().await {
        let conn_id = next_id.fetch_add(1, Ordering::Relaxed);
        let router = Arc::clone(&router);
        let conns = Arc::clone(&conns);
        let acceptor = acceptor.clone();
        tokio::spawn(async move {
            let _ = handle_accepted(stream, acceptor, conn_id, router, conns).await;
        });
    }
}

/// Splits an accepted TCP (or upgraded TLS) stream and serves it.
async fn handle_accepted(
    stream: TcpStream,
    acceptor: Option<TlsAcceptor>,
    conn_id: ConnId,
    router: Arc<Mutex<RelayRouter>>,
    conns: ConnTable,
) -> Result<(), RelayNetError> {
    stream.set_nodelay(true)?;
    if let Some(acceptor) = acceptor {
        let (reader, writer) = tokio::io::split(acceptor.accept(stream).await?);
        serve_connection(reader, writer, conn_id, router, conns).await
    } else {
        let (reader, writer) = stream.into_split();
        serve_connection(reader, writer, conn_id, router, conns).await
    }
}

async fn serve_connection<R, W>(
    mut reader: R,
    mut writer: W,
    conn_id: ConnId,
    router: Arc<Mutex<RelayRouter>>,
    conns: ConnTable,
) -> Result<(), RelayNetError>
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    // Handshake: challenge → verify signed reply → register before acking, so a
    // client that has seen RegisterOk is guaranteed routable server-side.
    let challenge = RelayChallenge::random();
    write_frame(&mut writer, &challenge.frame()).await?;
    let RelayFrame::ClientAuth {
        public_key,
        signature,
    } = read_frame(&mut reader).await?
    else {
        return Err(RelayNetError::Protocol);
    };
    let device = challenge
        .verify(public_key, signature)
        .map_err(|_| RelayNetError::Auth)?;

    let (tx, rx) = mpsc::channel::<RelayFrame>(OUTBOUND_QUEUE);
    lock(&conns).insert(conn_id, tx);
    lock(&router).register(conn_id, device);
    write_frame(&mut writer, &RelayFrame::RegisterOk).await?;

    let write_task = tokio::spawn(drain_to_stream(rx, writer));
    let result = serve_frames(&mut reader, conn_id, &router, &conns).await;

    lock(&conns).remove(&conn_id);
    lock(&router).disconnect(conn_id);
    write_task.abort();
    result
}

async fn serve_frames<R: AsyncRead + Unpin>(
    reader: &mut R,
    conn_id: ConnId,
    router: &Arc<Mutex<RelayRouter>>,
    conns: &ConnTable,
) -> Result<(), RelayNetError> {
    loop {
        let frame = match read_frame(reader).await {
            Ok(frame) => frame,
            // A closed connection is a normal end, not an error.
            Err(FramingError::Io(_)) => return Ok(()),
            Err(other) => return Err(other.into()),
        };
        let outbounds = lock(router).handle(conn_id, frame);
        for outbound in outbounds {
            if let Some(tx) = lock(conns).get(&outbound.connection) {
                // Drop on a full queue rather than block the whole relay.
                let _ = tx.try_send(outbound.frame);
            }
        }
    }
}

async fn drain_to_stream<W: AsyncWrite + Unpin>(mut rx: mpsc::Receiver<RelayFrame>, mut writer: W) {
    while let Some(frame) = rx.recv().await {
        if write_frame(&mut writer, &frame).await.is_err() {
            break;
        }
    }
}

/// A client's authenticated connection to a relay.
#[derive(Debug)]
pub struct RelayConnection {
    device: DeviceId,
    outbound: mpsc::Sender<RelayFrame>,
    inbound: mpsc::Receiver<(DeviceId, Vec<u8>)>,
    reader_task: JoinHandle<()>,
    writer_task: JoinHandle<()>,
}

impl RelayConnection {
    /// Connects to a plain-TCP relay at `address` and authenticates as `identity`.
    pub async fn connect(
        address: SocketAddr,
        identity: &DeviceIdentity,
    ) -> Result<Self, RelayNetError> {
        let stream = TcpStream::connect(address).await?;
        stream.set_nodelay(true)?;
        let (reader, writer) = stream.into_split();
        Self::from_parts(reader, writer, identity).await
    }

    /// Connects to a TLS relay at `address`, verifying the server certificate by
    /// its pinned SHA-256 `fingerprint` (from [`RelayServer::bind_tls`]).
    pub async fn connect_tls(
        address: SocketAddr,
        fingerprint: [u8; 32],
        identity: &DeviceIdentity,
    ) -> Result<Self, RelayNetError> {
        let stream = TcpStream::connect(address).await?;
        stream.set_nodelay(true)?;
        let connector = TlsConnector::from(tls::client_config(fingerprint)?);
        let stream = connector.connect(tls::server_name(), stream).await?;
        let (reader, writer) = tokio::io::split(stream);
        Self::from_parts(reader, writer, identity).await
    }

    /// Runs the client handshake over already-split stream halves and spawns the
    /// read/write pumps. Generic so plain-TCP and TLS share one implementation.
    async fn from_parts<R, W>(
        mut reader: R,
        mut writer: W,
        identity: &DeviceIdentity,
    ) -> Result<Self, RelayNetError>
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let RelayFrame::ServerChallenge { challenge } = read_frame(&mut reader).await? else {
            return Err(RelayNetError::Protocol);
        };
        write_frame(&mut writer, &client_auth(identity, challenge)).await?;
        if !matches!(read_frame(&mut reader).await?, RelayFrame::RegisterOk) {
            return Err(RelayNetError::Protocol);
        }

        let (out_tx, out_rx) = mpsc::channel::<RelayFrame>(OUTBOUND_QUEUE);
        let writer_task = tokio::spawn(drain_to_stream(out_rx, writer));

        let (in_tx, in_rx) = mpsc::channel::<(DeviceId, Vec<u8>)>(INBOUND_QUEUE);
        let keepalive = out_tx.clone();
        let reader_task = tokio::spawn(async move {
            loop {
                match read_frame(&mut reader).await {
                    Ok(RelayFrame::Deliver { source, payload }) => {
                        if in_tx.send((source, payload)).await.is_err() {
                            break;
                        }
                    }
                    Ok(RelayFrame::Ping { nonce }) => {
                        let _ = keepalive.try_send(RelayFrame::Pong { nonce });
                    }
                    // Unreachable/Pong/others are not surfaced yet.
                    Ok(_) => {}
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            device: identity.device_id(),
            outbound: out_tx,
            inbound: in_rx,
            reader_task,
            writer_task,
        })
    }

    /// This connection's own `DeviceId`.
    #[must_use]
    pub fn device(&self) -> DeviceId {
        self.device
    }

    /// Sends an opaque `payload` to `destination` via the relay.
    pub async fn send(&self, destination: DeviceId, payload: Vec<u8>) -> Result<(), RelayNetError> {
        self.outbound
            .send(RelayFrame::Forward {
                destination,
                payload,
            })
            .await
            .map_err(|_| RelayNetError::Closed)
    }

    /// Awaits the next payload delivered from a peer, or `None` if the relay closed.
    pub async fn recv(&mut self) -> Option<(DeviceId, Vec<u8>)> {
        self.inbound.recv().await
    }

    /// A cloneable non-blocking sender, for driving the relay from a poll context
    /// (e.g. a magic socket's send half).
    #[must_use]
    pub fn sender(&self) -> RelaySender {
        RelaySender(self.outbound.clone())
    }

    /// Polls for the next delivered payload, for driving the relay from a poll
    /// context (e.g. a magic socket's receive half). `Ready(None)` means closed.
    pub fn poll_recv(&mut self, cx: &mut Context<'_>) -> Poll<Option<(DeviceId, Vec<u8>)>> {
        self.inbound.poll_recv(cx)
    }
}

/// A cloneable, non-blocking handle for tunnelling payloads through the relay.
#[derive(Clone, Debug)]
pub struct RelaySender(mpsc::Sender<RelayFrame>);

impl RelaySender {
    /// Queues `payload` for delivery to `destination`. Best-effort: if the send
    /// queue is full the payload is dropped, just as the relay itself drops.
    pub fn try_send(&self, destination: DeviceId, payload: Vec<u8>) {
        let _ = self.0.try_send(RelayFrame::Forward {
            destination,
            payload,
        });
    }
}

impl Drop for RelayConnection {
    fn drop(&mut self) {
        self.reader_task.abort();
        self.writer_task.abort();
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[tokio::test]
    async fn two_clients_exchange_a_payload_through_the_relay() {
        let server = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let address = server.local_addr();

        let id_a = DeviceIdentity::from_seed([1; 32]);
        let id_b = DeviceIdentity::from_seed([2; 32]);
        let conn_a = RelayConnection::connect(address, &id_a).await.unwrap();
        let mut conn_b = RelayConnection::connect(address, &id_b).await.unwrap();

        conn_a
            .send(id_b.device_id(), b"hello-via-relay".to_vec())
            .await
            .unwrap();

        let (source, payload) = tokio::time::timeout(Duration::from_secs(5), conn_b.recv())
            .await
            .expect("relay delivery timed out")
            .expect("relay closed");
        assert_eq!(source, id_a.device_id());
        assert_eq!(payload, b"hello-via-relay");
    }

    #[tokio::test]
    async fn delivery_is_bidirectional() {
        let server = RelayServer::bind("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let address = server.local_addr();

        let id_a = DeviceIdentity::from_seed([3; 32]);
        let id_b = DeviceIdentity::from_seed([4; 32]);
        let mut conn_a = RelayConnection::connect(address, &id_a).await.unwrap();
        let mut conn_b = RelayConnection::connect(address, &id_b).await.unwrap();

        conn_a.send(id_b.device_id(), vec![1, 2, 3]).await.unwrap();
        conn_b.send(id_a.device_id(), vec![4, 5, 6]).await.unwrap();

        let at_b = tokio::time::timeout(Duration::from_secs(5), conn_b.recv())
            .await
            .unwrap()
            .unwrap();
        let at_a = tokio::time::timeout(Duration::from_secs(5), conn_a.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(at_b, (id_a.device_id(), vec![1, 2, 3]));
        assert_eq!(at_a, (id_b.device_id(), vec![4, 5, 6]));
    }

    #[tokio::test]
    async fn tls_relay_forwards_and_rejects_a_wrong_fingerprint() {
        let (server, fingerprint) = RelayServer::bind_tls("127.0.0.1:0".parse().unwrap())
            .await
            .unwrap();
        let address = server.local_addr();

        let id_a = DeviceIdentity::from_seed([30; 32]);
        let id_b = DeviceIdentity::from_seed([31; 32]);
        let conn_a = RelayConnection::connect_tls(address, fingerprint, &id_a)
            .await
            .unwrap();
        let mut conn_b = RelayConnection::connect_tls(address, fingerprint, &id_b)
            .await
            .unwrap();

        conn_a
            .send(id_b.device_id(), b"over-tls".to_vec())
            .await
            .unwrap();
        let (source, payload) = tokio::time::timeout(Duration::from_secs(5), conn_b.recv())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(source, id_a.device_id());
        assert_eq!(payload, b"over-tls");

        // A client that pins the wrong fingerprint must not connect.
        let wrong = [0xff; 32];
        assert!(
            RelayConnection::connect_tls(address, wrong, &id_a)
                .await
                .is_err()
        );
    }
}
