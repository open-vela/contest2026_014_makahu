//! Device Fabric composition root, lifecycle, configuration, health, and bounded metrics.

mod runtime;
pub use runtime::*;

use async_trait::async_trait;
use fabric_identity::DeviceIdentity;
use fabric_registry::Registry;
use fabric_storage::{Storage, StorageError};
use std::{fmt::Write, path::PathBuf, sync::Arc};
use thiserror::Error;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HubConfig {
    pub database_path: PathBuf,
    pub device_label: String,
    pub quic_port: u16,
    pub maximum_ipc_connections: u32,
    pub maximum_sessions: u32,
    pub maximum_buffered_bytes: u64,
    pub graceful_shutdown_ms: u64,
    /// Rendezvous/relay server for cross-network reachability, if configured.
    pub relay: Option<RelayEndpointConfig>,
}

/// How to reach (and authenticate) the configured relay server.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RelayEndpointConfig {
    /// The relay's TCP address.
    pub address: std::net::SocketAddr,
    /// Pinned SHA-256 fingerprint of the relay's TLS certificate. `None` dials
    /// plain TCP (development only); `Some` requires the TLS relay.
    pub tls_fingerprint: Option<[u8; 32]>,
}

impl HubConfig {
    pub fn validate(&self) -> Result<(), HubError> {
        if self.quic_port == 0 {
            return Err(HubError::InvalidConfig("quic_port must be nonzero"));
        }
        if self.maximum_ipc_connections == 0
            || self.maximum_sessions == 0
            || self.maximum_buffered_bytes == 0
        {
            return Err(HubError::InvalidConfig(
                "all resource limits must be bounded and nonzero",
            ));
        }
        if let Some(relay) = &self.relay
            && relay.address.port() == 0
        {
            return Err(HubError::InvalidConfig("relay address must have a port"));
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StartupStage {
    StorageMigrated,
    IdentityLoaded,
    RuntimeSuspended,
    IpcReady,
    DiscoveryReady,
    LinksReady,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum HealthStatus {
    Starting,
    Healthy,
    ShuttingDown,
    Stopped,
}
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct MetricsSnapshot {
    pub fabric_links_active: u64,
    pub fabric_registry_revision: u64,
    pub fabric_channels_active: u64,
    pub fabric_channel_buffered_bytes: u64,
    pub fabric_datagrams_dropped_total: u64,
    pub fabric_ipc_connections: u64,
    pub fabric_protocol_errors_total: u64,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HealthDiagnostics {
    pub status: HealthStatus,
    pub device_id_short: String,
    pub startup: Vec<StartupStage>,
    pub metrics: MetricsSnapshot,
}

#[derive(Debug, Error)]
pub enum HubError {
    #[error("invalid Hub configuration: {0}")]
    InvalidConfig(&'static str),
    #[error("storage startup failed: {0}")]
    Storage(#[from] StorageError),
    #[error("Hub component failed to start: {0}")]
    Component(String),
    #[error("Hub component stage is already active")]
    DuplicateComponent,
    #[error("Hub component started out of order: expected {expected:?}, got {actual:?}")]
    ComponentOrder {
        expected: StartupStage,
        actual: StartupStage,
    },
}

#[async_trait]
pub trait HubComponent: Send {
    fn stage(&self) -> StartupStage;
    async fn start(&mut self) -> Result<(), String>;
    async fn shutdown(&mut self) -> Result<(), String>;
}

pub struct Hub {
    config: HubConfig,
    identity: Arc<DeviceIdentity>,
    registry: Arc<Registry>,
    storage: Storage,
    startup: Vec<StartupStage>,
    status: HealthStatus,
    metrics: MetricsSnapshot,
    components: Vec<Box<dyn HubComponent>>,
}

impl Hub {
    pub fn start(
        config: HubConfig,
        identity: DeviceIdentity,
        now_ms: u64,
    ) -> Result<Self, HubError> {
        config.validate()?;
        let mut storage = Storage::open(&config.database_path)?;
        let mut startup = vec![StartupStage::StorageMigrated, StartupStage::IdentityLoaded];
        storage.startup_recovery(now_ms)?;
        startup.push(StartupStage::RuntimeSuspended);
        let registry = Arc::new(Registry::new());
        Ok(Self {
            config,
            identity: Arc::new(identity),
            registry,
            storage,
            startup,
            status: HealthStatus::Starting,
            metrics: MetricsSnapshot::default(),
            components: Vec::new(),
        })
    }
    pub async fn attach_component(
        &mut self,
        mut component: Box<dyn HubComponent>,
    ) -> Result<(), HubError> {
        let stage = component.stage();
        if self.startup.contains(&stage) {
            return Err(HubError::DuplicateComponent);
        }
        if let Some(expected) = self.next_component_stage()
            && stage != expected
        {
            return Err(HubError::ComponentOrder {
                expected,
                actual: stage,
            });
        }
        component.start().await.map_err(HubError::Component)?;
        self.startup.push(stage);
        self.components.push(component);
        self.status = if self.required_components_ready() {
            HealthStatus::Healthy
        } else {
            HealthStatus::Starting
        };
        Ok(())
    }

    fn next_component_stage(&self) -> Option<StartupStage> {
        if !self.startup.contains(&StartupStage::IpcReady) {
            return Some(StartupStage::IpcReady);
        }
        if cfg!(any(feature = "mdns", feature = "ble"))
            && !self.startup.contains(&StartupStage::DiscoveryReady)
        {
            return Some(StartupStage::DiscoveryReady);
        }
        if cfg!(feature = "quic") && !self.startup.contains(&StartupStage::LinksReady) {
            return Some(StartupStage::LinksReady);
        }
        None
    }

    fn required_components_ready(&self) -> bool {
        self.startup.contains(&StartupStage::IpcReady)
            && (!cfg!(any(feature = "mdns", feature = "ble"))
                || self.startup.contains(&StartupStage::DiscoveryReady))
            && (!cfg!(feature = "quic") || self.startup.contains(&StartupStage::LinksReady))
    }
    #[must_use]
    pub fn registry(&self) -> Arc<Registry> {
        Arc::clone(&self.registry)
    }
    #[must_use]
    pub fn identity(&self) -> Arc<DeviceIdentity> {
        Arc::clone(&self.identity)
    }
    #[must_use]
    pub fn diagnostics(&self) -> HealthDiagnostics {
        let full = self.identity.device_id().0;
        let device_id_short =
            full[..6]
                .iter()
                .fold(String::with_capacity(12), |mut output, byte| {
                    let _ = write!(output, "{byte:02x}");
                    output
                });
        HealthDiagnostics {
            status: self.status,
            device_id_short,
            startup: self.startup.clone(),
            metrics: self.metrics.clone(),
        }
    }
    pub fn refresh_metrics(&mut self) -> Result<(), HubError> {
        self.metrics.fabric_registry_revision = self
            .registry
            .snapshot()
            .map_err(|_| HubError::InvalidConfig("registry unavailable"))?
            .revision
            .0;
        Ok(())
    }
    pub async fn shutdown(&mut self) -> Result<(), HubError> {
        self.status = HealthStatus::ShuttingDown;
        let mut first_error = None;
        for component in self.components.iter_mut().rev() {
            if let Err(error) = component.shutdown().await
                && first_error.is_none()
            {
                first_error = Some(HubError::Component(error));
            }
        }
        self.status = HealthStatus::Stopped;
        first_error.map_or(Ok(()), Err)
    }
    #[must_use]
    pub const fn config(&self) -> &HubConfig {
        &self.config
    }
    #[must_use]
    pub const fn storage(&self) -> &Storage {
        &self.storage
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestComponent(StartupStage);
    #[async_trait]
    impl HubComponent for TestComponent {
        fn stage(&self) -> StartupStage {
            self.0
        }
        async fn start(&mut self) -> Result<(), String> {
            Ok(())
        }
        async fn shutdown(&mut self) -> Result<(), String> {
            Ok(())
        }
    }

    #[tokio::test]
    async fn startup_order_and_restart_rules_are_visible() {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "fabric-hub-{}.sqlite",
            fabric_core::SessionId::new().0
        ));
        let config = HubConfig {
            database_path: path.clone(),
            device_label: "test hub".into(),
            quic_port: 44330,
            maximum_ipc_connections: 10,
            maximum_sessions: 10,
            maximum_buffered_bytes: 1024,
            graceful_shutdown_ms: 100,
            relay: None,
        };
        let mut hub = Hub::start(config, DeviceIdentity::from_seed([1; 32]), 0).unwrap();
        assert_eq!(
            hub.diagnostics().startup[..3],
            [
                StartupStage::StorageMigrated,
                StartupStage::IdentityLoaded,
                StartupStage::RuntimeSuspended
            ]
        );
        assert_eq!(hub.diagnostics().status, HealthStatus::Starting);
        if cfg!(any(feature = "mdns", feature = "ble")) {
            assert!(matches!(
                hub.attach_component(Box::new(TestComponent(StartupStage::DiscoveryReady)))
                    .await,
                Err(HubError::ComponentOrder {
                    expected: StartupStage::IpcReady,
                    actual: StartupStage::DiscoveryReady
                })
            ));
        }
        hub.attach_component(Box::new(TestComponent(StartupStage::IpcReady)))
            .await
            .unwrap();
        if cfg!(any(feature = "mdns", feature = "ble")) {
            hub.attach_component(Box::new(TestComponent(StartupStage::DiscoveryReady)))
                .await
                .unwrap();
        }
        if cfg!(feature = "quic") {
            hub.attach_component(Box::new(TestComponent(StartupStage::LinksReady)))
                .await
                .unwrap();
        }
        assert_eq!(hub.diagnostics().status, HealthStatus::Healthy);
        hub.shutdown().await.unwrap();
        assert_eq!(hub.diagnostics().status, HealthStatus::Stopped);
        drop(hub);
        std::fs::remove_file(path).unwrap();
    }
}
