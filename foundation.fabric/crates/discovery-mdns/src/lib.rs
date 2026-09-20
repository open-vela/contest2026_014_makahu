//! Privacy-bounded mDNS/DNS-SD advertisement parsing.

use async_trait::async_trait;
use fabric_discovery::{
    CandidateSink, ConnectionHint, DiscoveryBackend, DiscoveryError, DiscoveryKey, DiscoverySource,
    PeerCandidate,
};
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
#[cfg(test)]
use std::time::{SystemTime, UNIX_EPOCH};
use std::{
    collections::{BTreeMap, HashMap},
    fmt::Write,
    sync::Arc,
};
use tokio::{sync::Mutex, task::JoinHandle};

pub const SERVICE_TYPE: &str = "_device-fabric._udp.local.";
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MdnsAdvertisement {
    pub protocol_version: u8,
    pub port: u16,
    pub rotating_hint: [u8; 16],
    pub label: String,
    pub accepts_pairing: bool,
    pub features: String,
}
impl MdnsAdvertisement {
    pub fn parse(values: &BTreeMap<String, String>) -> Result<Self, DiscoveryError> {
        if values.keys().any(|key| {
            !matches!(
                key.as_str(),
                "pv" | "port" | "hint" | "label" | "pair" | "features"
            )
        }) {
            return Err(DiscoveryError::InvalidAdvertisement);
        }
        let protocol_version = values
            .get("pv")
            .ok_or(DiscoveryError::InvalidAdvertisement)?
            .parse()
            .map_err(|_| DiscoveryError::InvalidAdvertisement)?;
        let port = values
            .get("port")
            .ok_or(DiscoveryError::InvalidAdvertisement)?
            .parse()
            .map_err(|_| DiscoveryError::InvalidAdvertisement)?;
        let rotating_hint = decode_hex_16(
            values
                .get("hint")
                .ok_or(DiscoveryError::InvalidAdvertisement)?,
        )?;
        let label = sanitize_label(
            values
                .get("label")
                .ok_or(DiscoveryError::InvalidAdvertisement)?,
        )?;
        let accepts_pairing = match values.get("pair").map(String::as_str) {
            Some("0") => false,
            Some("1") => true,
            _ => return Err(DiscoveryError::InvalidAdvertisement),
        };
        let features = values.get("features").cloned().unwrap_or_default();
        if !features
            .bytes()
            .all(|value| value.is_ascii_lowercase() || value == b',')
        {
            return Err(DiscoveryError::InvalidAdvertisement);
        }
        Ok(Self {
            protocol_version,
            port,
            rotating_hint,
            label,
            accepts_pairing,
            features,
        })
    }
    #[must_use]
    pub fn to_txt(&self) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("pv".into(), self.protocol_version.to_string()),
            ("port".into(), self.port.to_string()),
            ("hint".into(), hex_16(&self.rotating_hint)),
            ("label".into(), self.label.clone()),
            ("pair".into(), u8::from(self.accepts_pairing).to_string()),
            ("features".into(), self.features.clone()),
        ])
    }
    #[must_use]
    pub fn candidate(&self, instance: &str, host: &str, expires_at_ms: u64) -> PeerCandidate {
        PeerCandidate {
            discovery_key: DiscoveryKey(instance.into()),
            label: self.label.clone(),
            connection_hints: vec![ConnectionHint::Quic {
                host: host.into(),
                port: self.port,
            }],
            device_hint: Some(self.rotating_hint),
            proximity: None,
            sources: vec![DiscoverySource::Mdns],
            expires_at_ms,
        }
    }
}
fn decode_hex_16(value: &str) -> Result<[u8; 16], DiscoveryError> {
    if value.len() != 32 {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    let mut output = [0; 16];
    for (index, byte) in output.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| DiscoveryError::InvalidAdvertisement)?;
    }
    Ok(output)
}

fn sanitize_label(value: &str) -> Result<String, DiscoveryError> {
    if value.is_empty() || value.len() > 64 || value.contains(['|', '\r', '\n']) {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    Ok(value.trim().to_owned())
}
fn hex_16(value: &[u8; 16]) -> String {
    value
        .iter()
        .fold(String::with_capacity(32), |mut output, byte| {
            let _ = write!(output, "{byte:02x}");
            output
        })
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MdnsConfig {
    pub instance_name: String,
    pub host_name: String,
    pub advertisement: MdnsAdvertisement,
    pub candidate_ttl_ms: u64,
    pub daemon_port: Option<u16>,
}

impl MdnsConfig {
    fn validate(&self) -> Result<(), DiscoveryError> {
        if self.instance_name.is_empty()
            || self.host_name.is_empty()
            || self.candidate_ttl_ms == 0
            || self.advertisement.port == 0
        {
            return Err(DiscoveryError::InvalidAdvertisement);
        }
        MdnsAdvertisement::parse(&self.advertisement.to_txt())?;
        Ok(())
    }
}

struct MdnsState {
    daemon: ServiceDaemon,
    fullname: String,
    task: JoinHandle<()>,
    known: Arc<Mutex<BTreeMap<DiscoveryKey, u64>>>,
    sink: Arc<dyn CandidateSink>,
}

pub struct MdnsDiscovery {
    config: MdnsConfig,
    state: Mutex<Option<MdnsState>>,
}

impl MdnsDiscovery {
    #[must_use]
    pub const fn new(config: MdnsConfig) -> Self {
        Self {
            config,
            state: Mutex::const_new(None),
        }
    }
}

#[async_trait]
impl DiscoveryBackend for MdnsDiscovery {
    async fn start(&self, sink: Arc<dyn CandidateSink>) -> Result<(), DiscoveryError> {
        self.config.validate()?;
        let mut state = self.state.lock().await;
        if state.is_some() {
            return Err(DiscoveryError::AlreadyRunning);
        }
        let properties: HashMap<_, _> = self.config.advertisement.to_txt().into_iter().collect();
        let service = ServiceInfo::new(
            SERVICE_TYPE,
            &self.config.instance_name,
            &self.config.host_name,
            (),
            self.config.advertisement.port,
            properties,
        )
        .map_err(platform_error)?
        .enable_addr_auto();
        let fullname = service.get_fullname().to_owned();
        let daemon = match self.config.daemon_port {
            Some(port) => ServiceDaemon::new_with_port(port),
            None => ServiceDaemon::new(),
        }
        .map_err(platform_error)?;
        if let Err(error) = daemon.register(service) {
            let _ = daemon.shutdown();
            return Err(platform_error(error));
        }
        let events = match daemon.browse(SERVICE_TYPE) {
            Ok(events) => events,
            Err(error) => {
                let _ = daemon.unregister(&fullname);
                let _ = daemon.shutdown();
                return Err(platform_error(error));
            }
        };
        let own_fullname = fullname.clone();
        let known = Arc::new(Mutex::new(BTreeMap::<DiscoveryKey, u64>::new()));
        let task_known = Arc::clone(&known);
        let task_sink = Arc::clone(&sink);
        let task = tokio::spawn(async move {
            loop {
                let Ok(event) = events.recv_async().await else {
                    break;
                };
                match event {
                    ServiceEvent::ServiceResolved(service)
                        if service.get_fullname() != own_fullname =>
                    {
                        if let Ok(candidate) = resolved_candidate(&service) {
                            task_known
                                .lock()
                                .await
                                .insert(candidate.discovery_key.clone(), candidate.expires_at_ms);
                            let _ = task_sink.upsert(candidate).await;
                        }
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        let key = DiscoveryKey(fullname);
                        task_known.lock().await.remove(&key);
                        let _ = task_sink.remove(&key).await;
                    }
                    _ => {}
                }
            }
        });
        *state = Some(MdnsState {
            daemon,
            fullname,
            task,
            known,
            sink,
        });
        Ok(())
    }

    async fn stop(&self) -> Result<(), DiscoveryError> {
        let Some(state) = self.state.lock().await.take() else {
            return Ok(());
        };
        state.task.abort();
        let keys: Vec<_> = state.known.lock().await.keys().cloned().collect();
        let mut first_error = None;
        for key in keys {
            if let Err(error) = state.sink.remove(&key).await
                && first_error.is_none()
            {
                first_error = Some(error);
            }
        }
        for result in [
            state.daemon.stop_browse(SERVICE_TYPE),
            state.daemon.unregister(&state.fullname).map(|_| ()),
            state.daemon.shutdown().map(|_| ()),
        ] {
            if let Err(error) = result
                && first_error.is_none()
            {
                first_error = Some(platform_error(error));
            }
        }
        first_error.map_or(Ok(()), Err)
    }
}

fn resolved_candidate(service: &mdns_sd::ResolvedService) -> Result<PeerCandidate, DiscoveryError> {
    let values = service
        .get_properties()
        .iter()
        .map(|property| (property.key().to_owned(), property.val_str().to_owned()))
        .collect();
    let advertisement = MdnsAdvertisement::parse(&values)?;
    if advertisement.port != service.get_port() {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    let connection_hints = service
        .get_addresses()
        .iter()
        .map(|address| ConnectionHint::Quic {
            host: address.to_ip_addr().to_string(),
            port: service.get_port(),
        })
        .collect();
    Ok(PeerCandidate {
        discovery_key: DiscoveryKey(service.get_fullname().to_owned()),
        label: advertisement.label,
        connection_hints,
        device_hint: Some(advertisement.rotating_hint),
        proximity: None,
        sources: vec![DiscoverySource::Mdns],
        // mdns_sd emits ServiceRemoved when the record leaves the network.
        // A one-shot ServiceResolved event is not periodically refreshed, so a
        // wall-clock TTL here made live peers disappear after 15 seconds and
        // stopped the authenticated dialer's retry loop.
        expires_at_ms: u64::MAX,
    })
}

#[cfg(test)]
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

fn platform_error(error: impl std::fmt::Display) -> DiscoveryError {
    DiscoveryError::Platform(error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;
    #[test]
    fn advertisement_never_contains_ability_data() {
        let advert = MdnsAdvertisement {
            protocol_version: 1,
            port: 44330,
            rotating_hint: [1; 16],
            label: "test device".into(),
            accepts_pairing: true,
            features: "q,b".into(),
        };
        let txt = advert.to_txt();
        assert_eq!(MdnsAdvertisement::parse(&txt).unwrap(), advert);
        assert!(!txt.keys().any(|key| key.contains("ability")));
        let mut malicious = txt;
        malicious.insert("ability".into(), "com.example.echo".into());
        assert!(MdnsAdvertisement::parse(&malicious).is_err());
    }

    #[derive(Default)]
    struct Sink {
        removed: StdMutex<Vec<DiscoveryKey>>,
    }

    #[async_trait]
    impl CandidateSink for Sink {
        async fn upsert(&self, _candidate: PeerCandidate) -> Result<(), DiscoveryError> {
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
    async fn lifecycle_rejects_duplicate_start_and_stops_cleanly() {
        let backend = MdnsDiscovery::new(MdnsConfig {
            instance_name: format!("fabric-test-{}", now_ms()),
            host_name: "fabric-test.local.".into(),
            advertisement: MdnsAdvertisement {
                protocol_version: 1,
                port: 44_330,
                rotating_hint: [1; 16],
                label: "test device".into(),
                accepts_pairing: false,
                features: "q".into(),
            },
            candidate_ttl_ms: 100,
            daemon_port: Some(0),
        });
        let sink = Arc::new(Sink::default());
        backend.start(sink.clone()).await.unwrap();
        assert_eq!(
            backend.start(sink).await.unwrap_err(),
            DiscoveryError::AlreadyRunning
        );
        backend.stop().await.unwrap();
        backend.stop().await.unwrap();
    }
}
