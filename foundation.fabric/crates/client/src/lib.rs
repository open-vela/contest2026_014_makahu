//! Ergonomic Rust client for a Device Fabric hub.
//!
//! A Rust application talks to a running hub through the hub's loopback
//! *app-broker*. This crate wraps that broker's wire protocol in one small
//! client so apps can list devices, invoke abilities on them, and open streams
//! without hand-rolling request framing — the exact glue that desktop and
//! embedded consumers used to copy.
//!
//! ```no_run
//! # async fn demo() -> Result<(), fabric_client::BrokerError> {
//! use fabric_client::{FabricClient, LOCAL_DEVICE_ID};
//!
//! let fabric = FabricClient::local(); // the hub on this machine
//! for device in fabric.devices().await? {
//!     println!("{} ({})", device.label, if device.online { "online" } else { "offline" });
//! }
//! let reply = fabric
//!     .invoke(LOCAL_DEVICE_ID, "com.example.echo", b"ping".to_vec())
//!     .await?;
//! # let _ = reply;
//! # Ok(())
//! # }
//! ```
//!
//! This is a **client** SDK — it connects to an already-running hub (the desktop
//! hub, the Android hub, or an embedded one). Serving an ability from Rust is a
//! separate concern handled by the broker's provider API.

use std::sync::atomic::{AtomicU64, Ordering};

use tokio::net::TcpStream;

pub use fabric_app_broker::{BrokerError, DEFAULT_BROKER_ADDRESS, DeviceInfo, LOCAL_DEVICE_ID};

/// A handle to a hub's broker. Each call opens its own short-lived connection,
/// so a `FabricClient` is cheap to clone-by-address and safe to share.
#[derive(Debug)]
pub struct FabricClient {
    broker_address: String,
    next_request_id: AtomicU64,
}

impl FabricClient {
    /// Connects to the hub whose broker listens at `broker_address`
    /// (`host:port`).
    #[must_use]
    pub fn connect(broker_address: impl Into<String>) -> Self {
        Self {
            broker_address: broker_address.into(),
            next_request_id: AtomicU64::new(1),
        }
    }

    /// Connects to the hub on this machine at [`DEFAULT_BROKER_ADDRESS`].
    #[must_use]
    pub fn local() -> Self {
        Self::connect(DEFAULT_BROKER_ADDRESS)
    }

    /// The broker address this client dials.
    #[must_use]
    pub fn broker_address(&self) -> &str {
        &self.broker_address
    }

    fn request_id(&self) -> u64 {
        self.next_request_id.fetch_add(1, Ordering::Relaxed)
    }

    /// Lists the devices the hub knows about, with pairing and online status.
    pub async fn devices(&self) -> Result<Vec<DeviceInfo>, BrokerError> {
        fabric_app_broker::list_devices(&self.broker_address).await
    }

    /// Invokes `ability` on `device` with `payload` and returns the reply.
    ///
    /// Use [`LOCAL_DEVICE_ID`] for an ability served on this hub, or a device id
    /// from [`Self::devices`] to reach a paired remote device.
    pub async fn invoke(
        &self,
        device: &str,
        ability: &str,
        payload: Vec<u8>,
    ) -> Result<Vec<u8>, BrokerError> {
        fabric_app_broker::invoke_on(
            &self.broker_address,
            self.request_id(),
            device,
            ability,
            payload,
        )
        .await
    }

    /// Opens a named, bidirectional byte stream to `ability` on `device`, for
    /// bulk transfers that would not fit an invoke's message framing.
    pub async fn open_stream(
        &self,
        device: &str,
        ability: &str,
        stream_name: &str,
    ) -> Result<TcpStream, BrokerError> {
        fabric_app_broker::open_stream_on(
            &self.broker_address,
            self.request_id(),
            device,
            ability,
            stream_name,
        )
        .await
    }
}

#[cfg(test)]
mod tests {
    use fabric_app_broker::{Broker, EchoProvider};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        sync::watch,
    };

    use super::*;

    /// Spins a broker with an echo ability and returns a client for it.
    async fn echo_fabric() -> (FabricClient, watch::Sender<bool>) {
        let broker = Broker::bind("127.0.0.1:0").await.unwrap();
        let address = broker.local_addr().unwrap().to_string();
        let (shutdown, shutdown_rx) = watch::channel(false);
        tokio::spawn(broker.run(shutdown_rx));
        let provider = EchoProvider::connect(&address, "com.example.echo")
            .await
            .unwrap();
        tokio::spawn(provider.serve());
        (FabricClient::connect(address), shutdown)
    }

    #[tokio::test]
    async fn invoke_reaches_a_local_ability() {
        let (fabric, shutdown) = echo_fabric().await;
        let reply = fabric
            .invoke(LOCAL_DEVICE_ID, "com.example.echo", b"ping".to_vec())
            .await
            .unwrap();
        assert_eq!(reply, b"ping");
        // Request ids advance, so a second invoke is a distinct request.
        let reply = fabric
            .invoke(LOCAL_DEVICE_ID, "com.example.echo", b"pong".to_vec())
            .await
            .unwrap();
        assert_eq!(reply, b"pong");
        let _ = shutdown.send(true);
    }

    #[tokio::test]
    async fn open_stream_carries_bulk_data() {
        let (fabric, shutdown) = echo_fabric().await;
        let mut stream = fabric
            .open_stream(LOCAL_DEVICE_ID, "com.example.echo", "bulk")
            .await
            .unwrap();
        let payload = vec![9u8; 256 * 1024];
        stream.write_all(&payload).await.unwrap();
        stream.shutdown().await.unwrap();
        let mut echoed = Vec::new();
        stream.read_to_end(&mut echoed).await.unwrap();
        assert_eq!(echoed, payload);
        let _ = shutdown.send(true);
    }

    #[tokio::test]
    async fn devices_query_succeeds() {
        let (fabric, shutdown) = echo_fabric().await;
        // The device list content depends on the hub's backend; what this client
        // guarantees is that the query round-trips through the broker.
        fabric.devices().await.unwrap();
        let _ = shutdown.send(true);
    }
}
