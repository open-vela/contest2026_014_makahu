//! Desktop (macOS/Linux) BLE near-field discovery via `btleplug`.
//!
//! `btleplug` exposes only the central role, so a desktop hub can **discover**
//! peers that advertise (Android/Windows) but cannot advertise itself — two
//! desktops won't find each other over BLE, and the pairing-nonce exchange (a
//! peripheral / GATT-server role) is likewise unavailable here. This adapter
//! therefore implements the scan half and reports the rest as unsupported, which
//! `BleDiscovery` handles gracefully (it never advertises unless a local
//! advertisement is configured, and it swallows scan errors).
//!
//! A live scan needs a Bluetooth adapter — and, on macOS, the app's Bluetooth
//! permission — so it is not exercised in unit tests; the payload decoding it
//! relies on is covered by the codec's own tests.

use std::time::Duration;

use async_trait::async_trait;
use btleplug::{
    api::{Central, Manager as _, Peripheral as _, ScanFilter},
    platform::Manager,
};
use fabric_discovery::{DiscoveryError, DiscoveryKey};

use crate::{
    BleAdvertisement, BlePlatform, PairingAdvertisement, PairingRequest, PairingResponse,
    codec::{COMPANY_ID, ObservationPayload, decode_payload},
    now_ms,
};

/// How long to let a scan gather advertisements before reading the results.
const DEFAULT_SCAN_DURATION: Duration = Duration::from_secs(4);
/// How long a scanned advertisement's candidate remains valid.
const DEFAULT_CANDIDATE_TTL: Duration = Duration::from_secs(30);

/// A central-only (scan) BLE platform for desktop hubs.
#[derive(Clone, Debug)]
pub struct DesktopBlePlatform {
    scan_duration: Duration,
    candidate_ttl: Duration,
}

impl Default for DesktopBlePlatform {
    fn default() -> Self {
        Self {
            scan_duration: DEFAULT_SCAN_DURATION,
            candidate_ttl: DEFAULT_CANDIDATE_TTL,
        }
    }
}

impl DesktopBlePlatform {
    /// A desktop BLE platform with default scan timing.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }
}

fn platform_error(error: &btleplug::Error) -> DiscoveryError {
    DiscoveryError::Platform(error.to_string())
}

fn duration_ms(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

#[async_trait]
impl BlePlatform for DesktopBlePlatform {
    async fn advertise_discovery(
        &self,
        _advertisement: BleAdvertisement,
    ) -> Result<(), DiscoveryError> {
        // btleplug has no peripheral role: desktop cannot advertise.
        Err(DiscoveryError::UnsupportedCapability)
    }

    async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError> {
        let manager = Manager::new()
            .await
            .map_err(|error| platform_error(&error))?;
        let adapter = manager
            .adapters()
            .await
            .map_err(|error| platform_error(&error))?
            .into_iter()
            .next()
            .ok_or(DiscoveryError::UnsupportedCapability)?;

        adapter
            .start_scan(ScanFilter::default())
            .await
            .map_err(|error| platform_error(&error))?;
        tokio::time::sleep(self.scan_duration).await;
        let peripherals = adapter
            .peripherals()
            .await
            .map_err(|error| platform_error(&error))?;
        let _ = adapter.stop_scan().await;

        let expires_at_ms = now_ms().saturating_add(duration_ms(self.candidate_ttl));
        let mut adverts = Vec::new();
        for peripheral in peripherals {
            let Ok(Some(properties)) = peripheral.properties().await else {
                continue;
            };
            let Some(payload) = properties.manufacturer_data.get(&COMPANY_ID) else {
                continue;
            };
            let Some(ObservationPayload::Discovery {
                rotating_hint,
                label,
                connection_hint,
            }) = decode_payload(payload)
            else {
                continue;
            };
            adverts.push(BleAdvertisement {
                key: DiscoveryKey(format!("ble:{}", properties.address)),
                rotating_hint,
                label,
                connection_hint,
                expires_at_ms,
            });
        }
        Ok(adverts)
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
}
