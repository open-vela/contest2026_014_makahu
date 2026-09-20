//! Wi-Fi Aware candidate discovery adapter.

use async_trait::async_trait;
use fabric_discovery::{
    CandidateSink, ConnectionHint, DiscoveryBackend, DiscoveryError, DiscoveryKey, DiscoverySource,
    PeerCandidate, ProximityEvidence,
};
use std::{
    collections::BTreeMap,
    sync::{Arc, OnceLock, RwLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{sync::Mutex, task::JoinHandle};

mod codec;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WifiAwareAdvertisement {
    pub key: DiscoveryKey,
    pub rotating_hint: [u8; 16],
    pub label: String,
    pub connection_hint: Option<Vec<u8>>,
    pub expires_at_ms: u64,
}

#[async_trait]
pub trait WifiAwarePlatform: Send + Sync {
    async fn publish(&self, advertisement: WifiAwareAdvertisement) -> Result<(), DiscoveryError>;
    async fn scan(&self) -> Result<Vec<WifiAwareAdvertisement>, DiscoveryError>;
    async fn stop(&self) -> Result<(), DiscoveryError> {
        Ok(())
    }
}

pub struct WifiAwareDiscovery<P> {
    platform: Arc<P>,
    local_advertisement: Option<WifiAwareAdvertisement>,
    scan_interval: Duration,
    state: Mutex<Option<WifiAwareState>>,
}

impl<P> WifiAwareDiscovery<P> {
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
    pub fn with_advertisement(mut self, advertisement: WifiAwareAdvertisement) -> Self {
        self.local_advertisement = Some(advertisement);
        self
    }
}

struct WifiAwareState {
    task: JoinHandle<()>,
    known: Arc<Mutex<BTreeMap<DiscoveryKey, u64>>>,
    sink: Arc<dyn CandidateSink>,
}

#[async_trait]
impl<P: WifiAwarePlatform + 'static> DiscoveryBackend for WifiAwareDiscovery<P> {
    async fn start(&self, sink: Arc<dyn CandidateSink>) -> Result<(), DiscoveryError> {
        let mut state = self.state.lock().await;
        if state.is_some() {
            return Err(DiscoveryError::AlreadyRunning);
        }
        if let Some(advertisement) = self.local_advertisement.clone() {
            let _ = self.platform.publish(advertisement).await;
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
        *state = Some(WifiAwareState { task, known, sink });
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

pub trait AndroidWifiAwareBridge: Send + Sync {
    fn publish(&self, payload: Vec<u8>) -> Result<(), DiscoveryError>;
    fn scan(&self, scan_duration: Duration) -> Result<Vec<Vec<u8>>, DiscoveryError>;
    fn stop(&self) -> Result<(), DiscoveryError>;
}

static ANDROID_BRIDGE: OnceLock<RwLock<Option<Arc<dyn AndroidWifiAwareBridge>>>> = OnceLock::new();

pub fn set_android_wifi_aware_bridge(bridge: Arc<dyn AndroidWifiAwareBridge>) {
    let lock = ANDROID_BRIDGE.get_or_init(|| RwLock::new(None));
    if let Ok(mut active) = lock.write() {
        *active = Some(bridge);
    }
}

#[derive(Clone)]
pub struct AndroidWifiAwarePlatform {
    scan_duration: Duration,
    candidate_ttl: Duration,
}

impl Default for AndroidWifiAwarePlatform {
    fn default() -> Self {
        Self::new(Duration::from_millis(1_500), Duration::from_secs(15))
    }
}

impl AndroidWifiAwarePlatform {
    #[must_use]
    pub const fn new(scan_duration: Duration, candidate_ttl: Duration) -> Self {
        Self {
            scan_duration,
            candidate_ttl,
        }
    }
}

#[async_trait]
impl WifiAwarePlatform for AndroidWifiAwarePlatform {
    async fn publish(&self, advertisement: WifiAwareAdvertisement) -> Result<(), DiscoveryError> {
        let bridge = active_android_bridge()?;
        let payload = codec::encode_discovery(&advertisement)?;
        tokio::task::spawn_blocking(move || bridge.publish(payload))
            .await
            .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }

    async fn scan(&self) -> Result<Vec<WifiAwareAdvertisement>, DiscoveryError> {
        let bridge = active_android_bridge()?;
        let scan_duration = self.scan_duration;
        let candidate_ttl = self.candidate_ttl;
        tokio::task::spawn_blocking(move || {
            let expires_at_ms = now_ms().saturating_add(duration_ms(candidate_ttl));
            let mut advertisements = Vec::new();
            for payload in bridge.scan(scan_duration)? {
                let Some(decoded) = codec::decode_discovery(&payload) else {
                    continue;
                };
                advertisements.push(WifiAwareAdvertisement {
                    key: rotating_hint_key(&decoded.rotating_hint),
                    rotating_hint: decoded.rotating_hint,
                    label: decoded.label,
                    connection_hint: decoded.connection_hint,
                    expires_at_ms,
                });
            }
            Ok(advertisements)
        })
        .await
        .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }

    async fn stop(&self) -> Result<(), DiscoveryError> {
        let bridge = active_android_bridge()?;
        tokio::task::spawn_blocking(move || bridge.stop())
            .await
            .map_err(|error| DiscoveryError::Platform(error.to_string()))?
    }
}

pub struct UnsupportedWifiAwarePlatform;

#[async_trait]
impl WifiAwarePlatform for UnsupportedWifiAwarePlatform {
    async fn publish(&self, _advertisement: WifiAwareAdvertisement) -> Result<(), DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
    }

    async fn scan(&self) -> Result<Vec<WifiAwareAdvertisement>, DiscoveryError> {
        Err(DiscoveryError::UnsupportedCapability)
    }
}

async fn publish_scan(
    adverts: Vec<WifiAwareAdvertisement>,
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
                method: "wifi-aware".into(),
                confidence: 75,
            }),
            sources: vec![DiscoverySource::WifiAware],
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

fn active_android_bridge() -> Result<Arc<dyn AndroidWifiAwareBridge>, DiscoveryError> {
    ANDROID_BRIDGE
        .get()
        .and_then(|lock| lock.read().ok().and_then(|guard| guard.clone()))
        .ok_or(DiscoveryError::UnsupportedCapability)
}

fn rotating_hint_key(rotating_hint: &[u8; 16]) -> DiscoveryKey {
    let mut output = String::with_capacity(43);
    output.push_str("wifi-aware:");
    for byte in rotating_hint {
        use std::fmt::Write as _;
        let _ = write!(output, "{byte:02x}");
    }
    DiscoveryKey(output)
}

fn duration_ms(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct MemoryWifiAware;

    #[async_trait]
    impl WifiAwarePlatform for MemoryWifiAware {
        async fn publish(
            &self,
            _advertisement: WifiAwareAdvertisement,
        ) -> Result<(), DiscoveryError> {
            Ok(())
        }

        async fn scan(&self) -> Result<Vec<WifiAwareAdvertisement>, DiscoveryError> {
            Ok(vec![WifiAwareAdvertisement {
                key: DiscoveryKey("nearby".into()),
                rotating_hint: [2; 16],
                label: "nearby speaker".into(),
                connection_hint: Some(vec![1, 2]),
                expires_at_ms: 10,
            }])
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
    async fn wifi_aware_only_produces_candidate_not_bulk_transport() {
        let sink = Arc::new(Sink::default());
        let backend = WifiAwareDiscovery::new(MemoryWifiAware);
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
    }

    #[tokio::test]
    async fn unavailable_wifi_aware_does_not_block_other_discovery_backends() {
        let sink = Arc::new(Sink::default());
        let backend = WifiAwareDiscovery::new(UnsupportedWifiAwarePlatform).with_advertisement(
            WifiAwareAdvertisement {
                key: DiscoveryKey("local".into()),
                rotating_hint: [1; 16],
                label: "local speaker".into(),
                connection_hint: None,
                expires_at_ms: u64::MAX,
            },
        );

        backend.start(sink).await.unwrap();
        backend.stop().await.unwrap();
    }
}
