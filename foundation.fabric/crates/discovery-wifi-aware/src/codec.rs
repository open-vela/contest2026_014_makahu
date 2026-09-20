use crate::WifiAwareAdvertisement;
use fabric_discovery::DiscoveryError;

const DISCOVERY_MAGIC: &[u8; 4] = b"DFW2";
const MAX_CONNECTION_HINT_BYTES: usize = 512;

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct DecodedAdvertisement {
    pub rotating_hint: [u8; 16],
    pub label: String,
    pub connection_hint: Option<Vec<u8>>,
}

pub(crate) fn encode_discovery(
    advertisement: &WifiAwareAdvertisement,
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
    let label_len = u8::try_from(label.len()).map_err(|_| DiscoveryError::InvalidAdvertisement)?;
    let hint_len =
        u16::try_from(connection_hint.len()).map_err(|_| DiscoveryError::InvalidAdvertisement)?;
    let mut payload = Vec::with_capacity(23 + label.len() + connection_hint.len());
    payload.extend_from_slice(DISCOVERY_MAGIC);
    payload.extend_from_slice(&advertisement.rotating_hint);
    payload.push(label_len);
    payload.extend_from_slice(label);
    payload.extend_from_slice(&hint_len.to_be_bytes());
    payload.extend_from_slice(connection_hint);
    Ok(payload)
}

pub(crate) fn decode_discovery(payload: &[u8]) -> Option<DecodedAdvertisement> {
    let body = payload.strip_prefix(DISCOVERY_MAGIC)?;
    let rotating_hint: [u8; 16] = body.get(..16)?.try_into().ok()?;
    let label_len = usize::from(*body.get(16)?);
    let label_end = 17 + label_len;
    let label = String::from_utf8(body.get(17..label_end)?.to_vec()).ok()?;
    if label.is_empty() || label.len() > 64 || label.contains(['|', '\r', '\n']) {
        return None;
    }
    let hint_len = usize::from(u16::from_be_bytes(
        body.get(label_end..label_end + 2)?.try_into().ok()?,
    ));
    if hint_len > MAX_CONNECTION_HINT_BYTES || body.len() != label_end + 2 + hint_len {
        return None;
    }
    let connection_hint = (hint_len != 0).then(|| body[label_end + 2..].to_vec());
    Some(DecodedAdvertisement {
        rotating_hint,
        label,
        connection_hint,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_discovery::DiscoveryKey;

    #[test]
    fn wifi_aware_payload_round_trips() {
        let advertisement = WifiAwareAdvertisement {
            key: DiscoveryKey("local".into()),
            rotating_hint: [3; 16],
            label: "local speaker".into(),
            connection_hint: Some(vec![1, 2, 3, 4]),
            expires_at_ms: 1,
        };
        let encoded = encode_discovery(&advertisement).unwrap();
        let decoded = decode_discovery(&encoded).unwrap();
        assert_eq!(decoded.rotating_hint, [3; 16]);
        assert_eq!(decoded.label, "local speaker");
        assert_eq!(decoded.connection_hint, Some(vec![1, 2, 3, 4]));
        assert!(decode_discovery(&[encoded, vec![0]].concat()).is_none());
    }
}
