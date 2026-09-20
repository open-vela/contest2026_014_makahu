use super::{
    BleAdvertisement, BlePlatform, PairingAdvertisement, PairingRequest, PairingResponse, now_ms,
};
use crate::codec::{
    COMPANY_ID, ObservationPayload, decode_payload, encode_discovery, encode_pairing,
};
use async_trait::async_trait;
use fabric_discovery::{DiscoveryError, DiscoveryKey};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex as StdMutex},
    time::Duration,
};
use windows::{
    Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisementPublisher, BluetoothLEAdvertisementReceivedEventArgs,
        BluetoothLEAdvertisementWatcher, BluetoothLEManufacturerData, BluetoothLEScanningMode,
    },
    Foundation::TypedEventHandler,
    Storage::Streams::{DataReader, DataWriter, IBuffer},
};

pub struct WindowsBlePlatform {
    scan_duration: Duration,
    candidate_ttl: Duration,
    publisher: StdMutex<Option<BluetoothLEAdvertisementPublisher>>,
}

impl Default for WindowsBlePlatform {
    fn default() -> Self {
        Self::new(Duration::from_millis(1_500), Duration::from_secs(15))
    }
}

impl WindowsBlePlatform {
    #[must_use]
    pub const fn new(scan_duration: Duration, candidate_ttl: Duration) -> Self {
        Self {
            scan_duration,
            candidate_ttl,
            publisher: StdMutex::new(None),
        }
    }

    fn publish(&self, payload: &[u8]) -> Result<(), DiscoveryError> {
        let writer = DataWriter::new().map_err(platform_error)?;
        writer.WriteBytes(payload).map_err(platform_error)?;
        let buffer = writer.DetachBuffer().map_err(platform_error)?;
        let manufacturer =
            BluetoothLEManufacturerData::Create(COMPANY_ID, &buffer).map_err(platform_error)?;
        let publisher = BluetoothLEAdvertisementPublisher::new().map_err(platform_error)?;
        publisher
            .SetUseExtendedAdvertisement(true)
            .map_err(platform_error)?;
        publisher
            .Advertisement()
            .and_then(|advertisement| advertisement.ManufacturerData())
            .and_then(|items| items.Append(&manufacturer))
            .map_err(platform_error)?;
        publisher.Start().map_err(platform_error)?;

        let mut active = self
            .publisher
            .lock()
            .map_err(|_| DiscoveryError::Platform("BLE publisher lock was poisoned".into()))?;
        if let Some(previous) = active.replace(publisher) {
            previous.Stop().map_err(platform_error)?;
        }
        Ok(())
    }

    async fn observations(&self) -> Result<Vec<Observation>, DiscoveryError> {
        tokio::task::spawn_blocking({
            let scan_duration = self.scan_duration;
            move || observations_blocking(scan_duration)
        })
        .await
        .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }
}

fn observations_blocking(scan_duration: Duration) -> Result<Vec<Observation>, DiscoveryError> {
    let observations = Arc::new(StdMutex::new(Vec::new()));
    let callback_observations = Arc::clone(&observations);
    let watcher = BluetoothLEAdvertisementWatcher::new().map_err(platform_error)?;
    watcher
        .SetScanningMode(BluetoothLEScanningMode::Active)
        .map_err(platform_error)?;
    watcher
        .SetAllowExtendedAdvertisements(true)
        .map_err(platform_error)?;
    let handler = TypedEventHandler::<
        BluetoothLEAdvertisementWatcher,
        BluetoothLEAdvertisementReceivedEventArgs,
    >::new(move |_, arguments| {
        let Some(arguments) = arguments.as_ref() else {
            return Ok(());
        };
        if let Ok(mut parsed) = parse_event(arguments)
            && !parsed.is_empty()
            && let Ok(mut collected) = callback_observations.lock()
        {
            collected.append(&mut parsed);
        }
        Ok(())
    });
    let token = watcher.Received(&handler).map_err(platform_error)?;
    watcher.Start().map_err(platform_error)?;
    std::thread::sleep(scan_duration);
    let stop_result = watcher.Stop();
    let remove_result = watcher.RemoveReceived(token);
    stop_result.map_err(platform_error)?;
    remove_result.map_err(platform_error)?;

    observations
        .lock()
        .map(|items| items.clone())
        .map_err(|_| DiscoveryError::Platform("BLE observation lock was poisoned".into()))
}

#[async_trait]
impl BlePlatform for WindowsBlePlatform {
    async fn advertise_discovery(
        &self,
        advertisement: BleAdvertisement,
    ) -> Result<(), DiscoveryError> {
        self.publish(&encode_discovery(&advertisement)?)
    }

    async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError> {
        let expires_at_ms = now_ms().saturating_add(duration_ms(self.candidate_ttl));
        let mut candidates = BTreeMap::new();
        for observation in self.observations().await? {
            if let ObservationPayload::Discovery {
                rotating_hint,
                label,
                connection_hint,
            } = observation.payload
            {
                let key = address_key(observation.address);
                candidates.insert(
                    key.clone(),
                    BleAdvertisement {
                        key,
                        rotating_hint,
                        label,
                        connection_hint,
                        expires_at_ms,
                    },
                );
            }
        }
        Ok(candidates.into_values().collect())
    }

    async fn advertise_pairing(
        &self,
        advertisement: PairingAdvertisement,
    ) -> Result<(), DiscoveryError> {
        self.publish(&encode_pairing(advertisement.nonce))
    }

    async fn exchange(
        &self,
        peer: &DiscoveryKey,
        request: PairingRequest,
    ) -> Result<PairingResponse, DiscoveryError> {
        let peer_address = parse_address(peer)?;
        self.publish(&encode_pairing(request.nonce))?;
        self.observations()
            .await?
            .into_iter()
            .find_map(|observation| match observation {
                Observation {
                    address,
                    payload: ObservationPayload::Pairing(peer_nonce),
                } if address == peer_address => Some(PairingResponse {
                    peer_nonce,
                    connection_hint: None,
                }),
                _ => None,
            })
            .ok_or(DiscoveryError::InvalidAdvertisement)
    }

    async fn stop(&self) -> Result<(), DiscoveryError> {
        let publisher = self
            .publisher
            .lock()
            .map_err(|_| DiscoveryError::Platform("BLE publisher lock was poisoned".into()))?
            .take();
        if let Some(publisher) = publisher {
            publisher.Stop().map_err(platform_error)?;
        }
        Ok(())
    }
}

#[derive(Clone)]
struct Observation {
    address: u64,
    payload: ObservationPayload,
}

fn parse_event(
    arguments: &BluetoothLEAdvertisementReceivedEventArgs,
) -> windows::core::Result<Vec<Observation>> {
    let address = arguments.BluetoothAddress()?;
    let advertisement = arguments.Advertisement()?;
    let items = advertisement.GetManufacturerDataByCompanyId(COMPANY_ID)?;
    let mut observations = Vec::new();
    for index in 0..items.Size()? {
        let payload = buffer_bytes(&items.GetAt(index)?.Data()?)?;
        if let Some(payload) = decode_payload(&payload) {
            observations.push(Observation { address, payload });
        }
    }
    Ok(observations)
}

fn buffer_bytes(buffer: &IBuffer) -> windows::core::Result<Vec<u8>> {
    let reader = DataReader::FromBuffer(buffer)?;
    let mut bytes = vec![0; usize::try_from(buffer.Length()?).unwrap_or(usize::MAX)];
    reader.ReadBytes(&mut bytes)?;
    Ok(bytes)
}

fn address_key(address: u64) -> DiscoveryKey {
    DiscoveryKey(format!("ble:{address:012x}"))
}

fn parse_address(key: &DiscoveryKey) -> Result<u64, DiscoveryError> {
    let value = key
        .0
        .strip_prefix("ble:")
        .ok_or(DiscoveryError::InvalidAdvertisement)?;
    if value.len() != 12 {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    u64::from_str_radix(value, 16).map_err(|_| DiscoveryError::InvalidAdvertisement)
}

fn duration_ms(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn platform_error(error: impl std::fmt::Display) -> DiscoveryError {
    DiscoveryError::Platform(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn address_keys_round_trip() {
        let key = address_key(0x1122_3344_5566);
        assert_eq!(parse_address(&key).unwrap(), 0x1122_3344_5566);
        assert!(parse_address(&DiscoveryKey("ble:bad".into())).is_err());
    }
}
