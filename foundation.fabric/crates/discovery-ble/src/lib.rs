//! BLE candidate and pairing exchange adapter. Bulk Session data is intentionally absent.

use async_trait::async_trait;
use fabric_discovery::{
    CandidateSink, ConnectionHint, DiscoveryBackend, DiscoveryError, DiscoveryKey, DiscoverySource,
    PeerCandidate, ProximityEvidence,
};
use std::{
    collections::BTreeMap,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{sync::Mutex, task::JoinHandle};

#[cfg(target_os = "android")]
mod android;
mod codec;
#[cfg(any(target_os = "macos", target_os = "linux"))]
mod desktop;
#[cfg(target_os = "android")]
pub use android::{AndroidBleBridge, AndroidBlePlatform, set_android_ble_bridge};
#[cfg(any(target_os = "macos", target_os = "linux"))]
pub use desktop::DesktopBlePlatform;
#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::WindowsBlePlatform;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BleAdvertisement {
    pub key: DiscoveryKey,
    pub rotating_hint: [u8; 16],
    pub label: String,
    pub connection_hint: Option<Vec<u8>>,
    pub expires_at_ms: u64,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingAdvertisement {
    pub nonce: [u8; 32],
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingRequest {
    pub nonce: [u8; 32],
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingResponse {
    pub peer_nonce: [u8; 32],
    pub connection_hint: Option<Vec<u8>>,
}

#[async_trait]
pub trait BlePlatform: Send + Sync {
    async fn advertise_discovery(
        &self,
        advertisement: BleAdvertisement,
    ) -> Result<(), DiscoveryError>;
    async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError>;
    async fn advertise_pairing(
        &self,
        advertisement: PairingAdvertisement,
    ) -> Result<(), DiscoveryError>;
    async fn exchange(
        &self,
        peer: &DiscoveryKey,
        request: PairingRequest,
    ) -> Result<PairingResponse, DiscoveryError>;
    async fn stop(&self) -> Result<(), DiscoveryError> {
        Ok(())
    }
}

pub struct BleDiscovery<P> {
    platform: Arc<P>,
    local_advertisement: Option<BleAdvertisement>,
    scan_interval: Duration,
    state: Mutex<Option<BleState>>,
}
impl<P> BleDiscovery<P> {
    #[must_use]
    pub fn new(platform: P) -> Self {
        Self {
            platform: Arc::new(platform),
            local_advertisement: None,
            scan_interval: Duration::from_secs(5),
            state: Mutex::new(None),
        }
    }

    #[must_use]
    pub fn with_scan_interval(mut self, scan_interval: Duration) -> Self {
        self.scan_interval = scan_interval.max(Duration::from_millis(100));
        self
    }

    #[must_use]
    pub fn with_advertisement(mut self, advertisement: BleAdvertisement) -> Self {
        self.local_advertisement = Some(advertisement);
        self
    }
}

struct BleState {
    task: JoinHandle<()>,
    known: Arc<Mutex<BTreeMap<DiscoveryKey, u64>>>,
    sink: Arc<dyn CandidateSink>,
}

#[async_trait]
impl<P: BlePlatform + 'static> DiscoveryBackend for BleDiscovery<P> {
    async fn start(&self, sink: Arc<dyn CandidateSink>) -> Result<(), DiscoveryError> {
        let mut state = self.state.lock().await;
        if state.is_some() {
            return Err(DiscoveryError::AlreadyRunning);
        }
        if let Some(advertisement) = self.local_advertisement.clone() {
            let _ = self.platform.advertise_discovery(advertisement).await;
        }
        let known = Arc::new(Mutex::new(BTreeMap::new()));
        let adverts = self.platform.scan().await.unwrap_or_default();
        publish_scan(adverts, sink.as_ref(), known.as_ref()).await?;
        let platform = Arc::clone(&self.platform);
        let task_sink = Arc::clone(&sink);
        let task_known = Arc::clone(&known);
        let scan_interval = self.scan_interval;
        let task = tokio::spawn(async move {
            let mut interval = tokio::time::interval(scan_interval);
            interval.tick().await;
            loop {
                interval.tick().await;
                expire_candidates(task_sink.as_ref(), task_known.as_ref()).await;
                let Ok(adverts) = platform.scan().await else {
                    continue;
                };
                let _ = publish_scan(adverts, task_sink.as_ref(), task_known.as_ref()).await;
            }
        });
        *state = Some(BleState { task, known, sink });
        Ok(())
    }
    async fn stop(&self) -> Result<(), DiscoveryError> {
        let Some(state) = self.state.lock().await.take() else {
            return Ok(());
        };
        state.task.abort();
        self.platform.stop().await?;
        let keys: Vec<_> = state.known.lock().await.keys().cloned().collect();
        for key in keys {
            state.sink.remove(&key).await?;
        }
        Ok(())
    }
}

async fn publish_scan(
    adverts: Vec<BleAdvertisement>,
    sink: &dyn CandidateSink,
    known: &Mutex<BTreeMap<DiscoveryKey, u64>>,
) -> Result<(), DiscoveryError> {
    for advert in adverts {
        let hints = advert
            .connection_hint
            .into_iter()
            .map(ConnectionHint::Opaque)
            .collect();
        known
            .lock()
            .await
            .insert(advert.key.clone(), advert.expires_at_ms);
        sink.upsert(PeerCandidate {
            discovery_key: advert.key,
            label: advert.label,
            connection_hints: hints,
            device_hint: Some(advert.rotating_hint),
            proximity: Some(ProximityEvidence {
                method: "ble".into(),
                confidence: 80,
            }),
            sources: vec![DiscoverySource::Ble],
            expires_at_ms: advert.expires_at_ms,
        })
        .await?;
    }
    Ok(())
}

async fn expire_candidates(sink: &dyn CandidateSink, known: &Mutex<BTreeMap<DiscoveryKey, u64>>) {
    let now = now_ms();
    let expired: Vec<_> = known
        .lock()
        .await
        .iter()
        .filter_map(|(key, expires_at)| (*expires_at <= now).then_some(key.clone()))
        .collect();
    for key in expired {
        known.lock().await.remove(&key);
        let _ = sink.remove(&key).await;
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

pub struct UnsupportedBlePlatform;
#[async_trait]
impl BlePlatform for UnsupportedBlePlatform {
    async fn advertise_discovery(
        &self,
        _advertisement: BleAdvertisement,
    ) -> Result<(), DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
    }
    async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct MemoryBle;
    #[async_trait]
    impl BlePlatform for MemoryBle {
        async fn advertise_discovery(
            &self,
            _advertisement: BleAdvertisement,
        ) -> Result<(), DiscoveryError> {
            Ok(())
        }
        async fn scan(&self) -> Result<Vec<BleAdvertisement>, DiscoveryError> {
            Ok(vec![BleAdvertisement {
                key: DiscoveryKey("nearby".into()),
                rotating_hint: [1; 16],
                label: "nearby speaker".into(),
                connection_hint: Some(vec![1, 2]),
                expires_at_ms: 10,
            }])
        }
        async fn advertise_pairing(
            &self,
            _advertisement: PairingAdvertisement,
        ) -> Result<(), DiscoveryError> {
            Ok(())
        }
        async fn exchange(
            &self,
            _peer: &DiscoveryKey,
            request: PairingRequest,
        ) -> Result<PairingResponse, DiscoveryError> {
            Ok(PairingResponse {
                peer_nonce: request.nonce,
                connection_hint: None,
            })
        }
    }
    #[derive(Default)]
    struct Sink {
        upserted: Mutex<Vec<PeerCandidate>>,
        removed: Mutex<Vec<DiscoveryKey>>,
    }
    #[async_trait]
    impl CandidateSink for Sink {
        async fn upsert(&self, candidate: PeerCandidate) -> Result<(), DiscoveryError> {
            self.upserted
                .lock()
                .map_err(|_| DiscoveryError::SinkFailed)?
                .push(candidate);
            Ok(())
        }
        async fn remove(&self, key: &DiscoveryKey) -> Result<(), DiscoveryError> {
            self.removed
                .lock()
                .map_err(|_| DiscoveryError::SinkFailed)?
                .push(key.clone());
            Ok(())
        }
    }
    #[tokio::test]
    async fn ble_only_produces_candidate_not_bulk_transport() {
        let sink = Arc::new(Sink::default());
        let backend = BleDiscovery::new(MemoryBle);
        backend.start(sink.clone()).await.unwrap();
        assert_eq!(sink.upserted.lock().unwrap().len(), 1);
        assert_eq!(
            backend.start(sink.clone()).await.unwrap_err(),
            DiscoveryError::AlreadyRunning
        );
        backend.stop().await.unwrap();
        assert_eq!(
            sink.removed.lock().unwrap().as_slice(),
            [DiscoveryKey("nearby".into())]
        );
        backend.stop().await.unwrap();
    }

    #[tokio::test]
    async fn unavailable_ble_does_not_block_other_discovery_backends() {
        let sink = Arc::new(Sink::default());
        let backend =
            BleDiscovery::new(UnsupportedBlePlatform).with_advertisement(BleAdvertisement {
                key: DiscoveryKey("local".into()),
                rotating_hint: [1; 16],
                label: "local speaker".into(),
                connection_hint: None,
                expires_at_ms: u64::MAX,
            });

        backend.start(sink).await.unwrap();
        backend.stop().await.unwrap();
    }
}
