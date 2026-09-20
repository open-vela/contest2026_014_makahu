use super::{
    BleAdvertisement, BlePlatform, PairingAdvertisement, PairingRequest, PairingResponse, now_ms,
};
use crate::codec::{ObservationPayload, decode_payload, encode_discovery};
use async_trait::async_trait;
use fabric_discovery::{DiscoveryError, DiscoveryKey};
use std::{
    sync::{Arc, OnceLock, RwLock},
    time::Duration,
};

pub trait AndroidBleBridge: Send + Sync {
    fn start_advertising(&self, payload: Vec<u8>) -> Result<(), DiscoveryError>;
    fn stop_advertising(&self) -> Result<(), DiscoveryError>;
    fn scan(&self, scan_duration: Duration) -> Result<Vec<Vec<u8>>, DiscoveryError>;
}

static BRIDGE: OnceLock<RwLock<Option<Arc<dyn AndroidBleBridge>>>> = OnceLock::new();

pub fn set_android_ble_bridge(bridge: Arc<dyn AndroidBleBridge>) {
    let lock = BRIDGE.get_or_init(|| RwLock::new(None));
    if let Ok(mut active) = lock.write() {
        *active = Some(bridge);
    }
}

#[derive(Clone)]
pub struct AndroidBlePlatform {
    scan_duration: Duration,
    candidate_ttl: Duration,
}

impl Default for AndroidBlePlatform {
    fn default() -> Self {
        Self::new(Duration::from_millis(1_500), Duration::from_secs(15))
    }
}

impl AndroidBlePlatform {
    #[must_use]
    pub const fn new(scan_duration: Duration, candidate_ttl: Duration) -> Self {
        Self {
            scan_duration,
            candidate_ttl,
        }
    }
}

#[async_trait]
impl BlePlatform for AndroidBlePlatform {
    async fn advertise_discovery(
        &self,
        advertisement: BleAdvertisement,
    ) -> Result<(), DiscoveryError> {
        let bridge = active_bridge()?;
        let payload = encode_discovery(&advertisement)?;
        tokio::task::spawn_blocking(move || bridge.start_advertising(payload))
            .await
            .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }

    async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError> {
        let bridge = active_bridge()?;
        let scan_duration = self.scan_duration;
        let candidate_ttl = self.candidate_ttl;
        tokio::task::spawn_blocking(move || {
            let expires_at_ms = now_ms().saturating_add(duration_ms(candidate_ttl));
            let mut advertisements = Vec::new();
            for payload in bridge.scan(scan_duration)? {
                if let Some(ObservationPayload::Discovery {
                    rotating_hint,
                    label,
                    connection_hint,
                }) = decode_payload(&payload)
                {
                    advertisements.push(BleAdvertisement {
                        key: rotating_hint_key(&rotating_hint),
                        rotating_hint,
                        label,
                        connection_hint,
                        expires_at_ms,
                    });
                }
            }
            Ok(advertisements)
        })
        .await
        .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }

    async fn advertise_pairing(
        &self,
        _advertisement: PairingAdvertisement,
    ) -> Result<(), DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
    }

    async fn exchange(
        &self,
        _peer: &DiscoveryKey,
        _request: PairingRequest,
    ) -> Result<PairingResponse, DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
    }

    async fn stop(&self) -> Result<(), DiscoveryError> {
        let bridge = active_bridge()?;
        tokio::task::spawn_blocking(move || bridge.stop_advertising())
            .await
            .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }
}

fn active_bridge() -> Result<Arc<dyn AndroidBleBridge>, DiscoveryError> {
    BRIDGE
        .get()
        .and_then(|lock| lock.read().ok().and_then(|guard| guard.clone()))
        .ok_or(DiscoveryError::UnsupportedCapability)
}

fn rotating_hint_key(rotating_hint: &[u8; 16]) -> DiscoveryKey {
    let mut output = String::with_capacity(36);
    output.push_str("ble:");
    for byte in rotating_hint {
        use std::fmt::Write as _;
        let _ = write!(output, "{byte:02x}");
    }
    DiscoveryKey(output)
}

fn duration_ms(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}
