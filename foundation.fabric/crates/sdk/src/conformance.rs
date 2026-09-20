//! Ability contract parser, canonical hash, binding helper, and deterministic mock participant.

use fabric_core::{AbilityName, Namespace};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, VecDeque};

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct AbilityManifest {
    pub namespace: String,
    pub name: String,
    pub major: u32,
    pub protocol_hash_algorithm: String,
    pub roles: Vec<RoleManifest>,
    #[serde(default)]
    pub requirements: BTreeMap<String, toml::Value>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct RoleManifest {
    pub id: String,
    pub min_instances: u16,
    pub max_instances: Option<u16>,
    pub ports: Vec<PortManifest>,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct PortManifest {
    pub id: String,
    pub direction: String,
    pub mode: String,
    pub schema: Option<String>,
    pub required: bool,
}

#[derive(Debug, thiserror::Error)]
pub enum ContractError {
    #[error("ability.toml is invalid: {0}")]
    Toml(#[from] toml::de::Error),
    #[error("ability contract field is invalid: {0}")]
    Invalid(&'static str),
    #[error("referenced schema is missing: {0}")]
    MissingSchema(String),
}

pub struct AbilityPackage {
    pub manifest: AbilityManifest,
    pub protocol_hash: [u8; 32],
}
impl AbilityPackage {
    pub fn parse(
        manifest_text: &str,
        schemas: &BTreeMap<String, String>,
    ) -> Result<Self, ContractError> {
        let manifest: AbilityManifest = toml::from_str(manifest_text)?;
        Namespace::new(&manifest.namespace).map_err(|_| ContractError::Invalid("namespace"))?;
        AbilityName::new(&manifest.name).map_err(|_| ContractError::Invalid("name"))?;
        if manifest.major == 0
            || manifest.protocol_hash_algorithm != "sha256"
            || manifest.roles.is_empty()
        {
            return Err(ContractError::Invalid("version, algorithm, or roles"));
        }
        let mut role_ids = BTreeSet::new();
        for role in &manifest.roles {
            if role.min_instances == 0
                || role
                    .max_instances
                    .is_some_and(|max| max < role.min_instances)
                || !role_ids.insert(&role.id)
            {
                return Err(ContractError::Invalid("role bounds or duplicate"));
            }
            let mut port_ids = BTreeSet::new();
            for port in &role.ports {
                if !port_ids.insert(&port.id)
                    || !matches!(
                        port.direction.as_str(),
                        "send" | "receive" | "bidirectional"
                    )
                    || !matches!(
                        port.mode.as_str(),
                        "reliable_messages" | "reliable_stream" | "datagram"
                    )
                {
                    return Err(ContractError::Invalid("port"));
                }
                if let Some(schema) = &port.schema {
                    let path = schema.split('#').next().unwrap_or_default();
                    if path.contains("..") || !schemas.contains_key(path) {
                        return Err(ContractError::MissingSchema(path.into()));
                    }
                }
            }
        }
        let canonical_manifest =
            toml::to_string(&manifest).map_err(|_| ContractError::Invalid("canonical manifest"))?;
        let mut hash = Sha256::new();
        hash.update(canonical_manifest.replace("\r\n", "\n"));
        for (path, schema) in schemas {
            hash.update((path.len() as u64).to_be_bytes());
            hash.update(path);
            hash.update(schema.replace("\r\n", "\n"));
        }
        Ok(Self {
            manifest,
            protocol_hash: hash.finalize().into(),
        })
    }

    #[must_use]
    pub fn rust_binding_constants(&self) -> String {
        format!(
            "pub const ABILITY_NAMESPACE: &str = {:?};\npub const ABILITY_NAME: &str = {:?};\npub const ABILITY_MAJOR: u32 = {};\npub const PROTOCOL_HASH: [u8; 32] = {:?};\n",
            self.manifest.namespace, self.manifest.name, self.manifest.major, self.protocol_hash
        )
    }

    #[must_use]
    pub fn security_warnings(&self) -> Vec<&'static str> {
        let mut warnings = Vec::new();
        if !self
            .manifest
            .requirements
            .contains_key("minimum_device_trust")
        {
            warnings.push("minimum_device_trust is not declared");
        }
        if !self.manifest.requirements.contains_key("payload_security") {
            warnings.push("payload_security is not declared");
        }
        warnings
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MockDelivery {
    pub epoch: u64,
    pub sequence: u64,
    pub payload: Vec<u8>,
}
pub struct MockParticipant {
    epoch: u64,
    seen: BTreeSet<u64>,
    reorder: bool,
    pending: VecDeque<MockDelivery>,
}
impl MockParticipant {
    #[must_use]
    pub fn new(epoch: u64, reorder: bool) -> Self {
        Self {
            epoch,
            seen: BTreeSet::new(),
            reorder,
            pending: VecDeque::new(),
        }
    }
    pub fn receive(&mut self, delivery: MockDelivery) -> bool {
        if delivery.epoch != self.epoch || !self.seen.insert(delivery.sequence) {
            return false;
        }
        if self.reorder {
            self.pending.push_front(delivery);
        } else {
            self.pending.push_back(delivery);
        }
        true
    }
    pub fn reconfigure(&mut self, epoch: u64) {
        self.epoch = epoch;
        self.seen.clear();
        self.pending.clear();
    }
    pub fn drain(&mut self) -> Vec<MockDelivery> {
        self.pending.drain(..).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn contract_hash_is_deterministic_and_mock_rejects_stale_epoch() {
        let text = r#"namespace="com.example"
name="echo"
major=1
protocol_hash_algorithm="sha256"
[[roles]]
id="peer"
min_instances=1
[[roles.ports]]
id="messages"
direction="bidirectional"
mode="reliable_messages"
required=true
[requirements]
minimum_device_trust="trusted"
payload_security="link_encrypted"
"#;
        let first = AbilityPackage::parse(text, &BTreeMap::new()).unwrap();
        let second = AbilityPackage::parse(text, &BTreeMap::new()).unwrap();
        assert_eq!(first.protocol_hash, second.protocol_hash);
        let mut mock = MockParticipant::new(2, true);
        assert!(!mock.receive(MockDelivery {
            epoch: 1,
            sequence: 1,
            payload: vec![]
        }));
        assert!(mock.receive(MockDelivery {
            epoch: 2,
            sequence: 1,
            payload: vec![]
        }));
        assert!(!mock.receive(MockDelivery {
            epoch: 2,
            sequence: 1,
            payload: vec![]
        }));
    }
}
