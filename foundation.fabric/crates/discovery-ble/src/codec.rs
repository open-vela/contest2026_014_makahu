#[cfg(any(windows, target_os = "android", test))]
use fabric_discovery::DiscoveryError;

#[cfg(any(windows, target_os = "android", test))]
use crate::BleAdvertisement;

#[cfg(any(windows, target_os = "macos", target_os = "linux"))]
pub(crate) const COMPANY_ID: u16 = 0xfffe;
const DISCOVERY_MAGIC: &[u8; 4] = b"DFB2";
#[cfg(any(windows, test))]
const PAIRING_MAGIC: &[u8; 4] = b"DFP1";
const MAX_CONNECTION_HINT_BYTES: usize = 192;

#[derive(Clone)]
pub(crate) enum ObservationPayload {
    Discovery {
        rotating_hint: [u8; 16],
        label: String,
        connection_hint: Option<Vec<u8>>,
    },
    #[cfg(any(windows, test))]
    Pairing([u8; 32]),
}

#[cfg(any(windows, target_os = "android", test))]
pub(crate) fn encode_discovery(
    advertisement: &BleAdvertisement,
) -> Result<Vec<u8>, DiscoveryError> {
    let connection_hint = advertisement.connection_hint.as_deref().unwrap_or_default();
    if connection_hint.len() > MAX_CONNECTION_HINT_BYTES {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    let label = advertisement.label.as_bytes();
    if label.is_empty()
        || label.len() > 64
        || label
            .iter()
            .any(|byte| matches!(byte, b'|' | b'\r' | b'\n'))
    {
        return Err(DiscoveryError::InvalidAdvertisement);
    }
    let hint_len =
        u8::try_from(connection_hint.len()).map_err(|_| DiscoveryError::InvalidAdvertisement)?;
    let label_len = u8::try_from(label.len()).map_err(|_| DiscoveryError::InvalidAdvertisement)?;
    let mut payload = Vec::with_capacity(22 + label.len() + connection_hint.len());
    payload.extend_from_slice(DISCOVERY_MAGIC);
    payload.extend_from_slice(&advertisement.rotating_hint);
    payload.push(label_len);
    payload.extend_from_slice(label);
    payload.push(hint_len);
    payload.extend_from_slice(connection_hint);
    Ok(payload)
}

#[cfg(any(windows, test))]
pub(crate) fn encode_pairing(nonce: [u8; 32]) -> Vec<u8> {
    let mut payload = Vec::with_capacity(36);
    payload.extend_from_slice(PAIRING_MAGIC);
    payload.extend_from_slice(&nonce);
    payload
}

pub(crate) fn decode_payload(payload: &[u8]) -> Option<ObservationPayload> {
    if let Some(body) = payload.strip_prefix(DISCOVERY_MAGIC) {
        let rotating_hint: [u8; 16] = body.get(..16)?.try_into().ok()?;
        let label_len = usize::from(*body.get(16)?);
        let label_end = 17 + label_len;
        let label = String::from_utf8(body.get(17..label_end)?.to_vec()).ok()?;
        if label.is_empty() || label.len() > 64 || label.contains(['|', '\r', '\n']) {
            return None;
        }
        let connection_hint_len = usize::from(*body.get(label_end)?);
        if connection_hint_len > MAX_CONNECTION_HINT_BYTES
            || body.len() != label_end + 1 + connection_hint_len
        {
            return None;
        }
        let connection_hint = (connection_hint_len != 0).then(|| body[label_end + 1..].to_vec());
        return Some(ObservationPayload::Discovery {
            rotating_hint,
            label,
            connection_hint,
        });
    }
    #[cfg(any(windows, test))]
    if let Some(body) = payload.strip_prefix(PAIRING_MAGIC) {
        return Some(ObservationPayload::Pairing(body.try_into().ok()?));
    }
    None
}

#[cfg(test)]
mod tests {
    use fabric_discovery::DiscoveryKey;

    use super::*;

    #[test]
    fn payload_codecs_are_strict() {
        let advertisement = BleAdvertisement {
            key: DiscoveryKey("local".into()),
            rotating_hint: [7; 16],
            label: "local speaker".into(),
            connection_hint: Some(vec![1, 2, 3]),
            expires_at_ms: 1,
        };
        let encoded = encode_discovery(&advertisement).unwrap();
        let Some(ObservationPayload::Discovery {
            rotating_hint,
            label,
            connection_hint,
        }) = decode_payload(&encoded)
        else {
            panic!("discovery payload was not decoded");
        };
        assert_eq!(rotating_hint, [7; 16]);
        assert_eq!(label, "local speaker");
        assert_eq!(connection_hint, Some(vec![1, 2, 3]));
        assert!(decode_payload(&[encoded, vec![0]].concat()).is_none());

        let nonce = [9; 32];
        assert!(matches!(
            decode_payload(&encode_pairing(nonce)),
            Some(ObservationPayload::Pairing(value)) if value == nonce
        ));
        assert!(decode_payload(&encode_pairing(nonce)[..35]).is_none());
    }
}
