//! Ability contracts, offers, requirements, and bounded matching properties.

use std::collections::{BTreeMap, BTreeSet};

use crate::{AbilityInstanceId, AppPrincipal, DeviceId, RequirementId, ValidationError};

pub const MAX_NAMESPACE_BYTES: usize = 255;
pub const MAX_ABILITY_NAME_BYTES: usize = 255;
pub const MAX_PROPERTY_KEY_BYTES: usize = 128;
pub const MAX_PROPERTY_STRING_BYTES: usize = 4096;
pub const MAX_PROPERTY_BYTES: usize = 65_536;
pub const MAX_PROPERTY_SET_ITEMS: usize = 1024;

macro_rules! validated_name {
    ($name:ident, $field:literal, $max:expr) => {
        #[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
        #[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
        pub struct $name(String);

        impl $name {
            pub fn new(value: impl Into<String>) -> Result<Self, ValidationError> {
                let value = value.into();
                validate_dotted_name(&value, $field, $max)?;
                Ok(Self(value))
            }

            #[must_use]
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }
    };
}

validated_name!(Namespace, "namespace", MAX_NAMESPACE_BYTES);
validated_name!(AbilityName, "ability_name", MAX_ABILITY_NAME_BYTES);
validated_name!(PropertyKey, "property_key", MAX_PROPERTY_KEY_BYTES);

fn validate_dotted_name(
    value: &str,
    field: &'static str,
    max: usize,
) -> Result<(), ValidationError> {
    if value.is_empty() {
        return Err(ValidationError::Empty { field });
    }
    if value.len() > max {
        return Err(ValidationError::TooLong { field, max });
    }
    if !value.bytes().all(|byte| {
        byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'.' || byte == b'-'
    }) {
        return Err(ValidationError::InvalidCharacters { field });
    }
    if value.split('.').any(|part| {
        part.is_empty()
            || part.starts_with('-')
            || part.ends_with('-')
            || !part.bytes().any(|byte| byte.is_ascii_lowercase())
    }) {
        return Err(ValidationError::InvalidSegment { field });
    }
    Ok(())
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AbilityKey {
    pub namespace: Namespace,
    pub name: AbilityName,
    pub major: u32,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AbilityContractRef {
    pub key: AbilityKey,
    pub protocol_hash: [u8; 32],
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct RoleId(pub String);

impl RoleId {
    pub fn new(value: impl Into<String>) -> Result<Self, ValidationError> {
        let value = value.into();
        validate_dotted_name(&value, "role_id", 128)?;
        Ok(Self(value))
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct RoleDeclaration {
    pub id: RoleId,
    pub min_instances: u16,
    pub max_instances: Option<u16>,
    pub ports: Vec<PortDeclaration>,
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PortDirection {
    Send,
    Receive,
    Bidirectional,
}

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PortMode {
    ReliableMessages,
    ReliableStream,
    Datagram,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct SchemaId(pub String);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PortMultiplicity {
    One,
    Many { max: u16 },
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct PortDeclaration {
    pub id: String,
    pub direction: PortDirection,
    pub mode: PortMode,
    pub schema: Option<SchemaId>,
    pub required: bool,
    pub multiplicity: PortMultiplicity,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PropertyValue {
    Bool(bool),
    I64(i64),
    U64(u64),
    String(String),
    Bytes(Vec<u8>),
    StringSet(BTreeSet<String>),
    U64Set(BTreeSet<u64>),
}

impl PropertyValue {
    pub fn validate(&self) -> Result<(), ValidationError> {
        match self {
            Self::String(value) if value.len() > MAX_PROPERTY_STRING_BYTES => {
                Err(ValidationError::TooLong {
                    field: "property_string",
                    max: MAX_PROPERTY_STRING_BYTES,
                })
            }
            Self::Bytes(value) if value.len() > MAX_PROPERTY_BYTES => {
                Err(ValidationError::TooLong {
                    field: "property_bytes",
                    max: MAX_PROPERTY_BYTES,
                })
            }
            Self::StringSet(values) if values.len() > MAX_PROPERTY_SET_ITEMS => {
                Err(ValidationError::TooLong {
                    field: "property_string_set",
                    max: MAX_PROPERTY_SET_ITEMS,
                })
            }
            Self::U64Set(values) if values.len() > MAX_PROPERTY_SET_ITEMS => {
                Err(ValidationError::TooLong {
                    field: "property_u64_set",
                    max: MAX_PROPERTY_SET_ITEMS,
                })
            }
            Self::StringSet(values)
                if values
                    .iter()
                    .any(|value| value.len() > MAX_PROPERTY_STRING_BYTES) =>
            {
                Err(ValidationError::TooLong {
                    field: "property_string_set_item",
                    max: MAX_PROPERTY_STRING_BYTES,
                })
            }
            _ => Ok(()),
        }
    }
}

pub type PropertyMap = BTreeMap<PropertyKey, PropertyValue>;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum OfferVisibility {
    LocalOnly,
    TrustedDevices,
    PairedDevices,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct PolicyRef(pub String);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum LeaseSpec {
    ConnectionBound,
    Renewable { ttl_ms: u64 },
}

impl LeaseSpec {
    pub fn validate(self) -> Result<(), ValidationError> {
        if matches!(self, Self::Renewable { ttl_ms: 0 }) {
            return Err(ValidationError::Unbounded {
                field: "lease.ttl_ms",
            });
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AbilityOffer {
    pub instance_id: AbilityInstanceId,
    pub contract: AbilityContractRef,
    pub app: AppPrincipal,
    pub roles: Vec<RoleId>,
    pub properties: PropertyMap,
    pub visibility: OfferVisibility,
    pub access_policy: PolicyRef,
    pub lease: LeaseSpec,
}

impl AbilityOffer {
    pub fn validate(&self) -> Result<(), ValidationError> {
        self.app.validate()?;
        self.lease.validate()?;
        for value in self.properties.values() {
            value.validate()?;
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AbilitySelector {
    pub key: AbilityKey,
    pub protocol_hash: Option<[u8; 32]>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum PropertyPredicate {
    Any,
    Exists(PropertyKey),
    Equals(PropertyKey, PropertyValue),
    ContainsString(PropertyKey, String),
    ContainsU64(PropertyKey, u64),
    All(Vec<PropertyPredicate>),
    AnyOf(Vec<PropertyPredicate>),
    Not(Box<PropertyPredicate>),
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum DeviceSelector {
    AnyTrusted,
    Exact(DeviceId),
    OneOf(BTreeSet<DeviceId>),
    LocalOnly,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct SessionPolicy {
    pub minimum_participants: u16,
    pub maximum_participants: u16,
    pub allow_reconfiguration: bool,
}

impl SessionPolicy {
    pub fn validate(self) -> Result<(), ValidationError> {
        if self.minimum_participants == 0 || self.maximum_participants == 0 {
            return Err(ValidationError::Unbounded {
                field: "session participants",
            });
        }
        if self.minimum_participants > self.maximum_participants {
            return Err(ValidationError::InvalidValue {
                field: "session participants",
                reason: "minimum exceeds maximum",
            });
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AbilityRequirement {
    pub requirement_id: RequirementId,
    pub selector: AbilitySelector,
    pub desired_role: RoleId,
    pub property_predicate: PropertyPredicate,
    pub device_selector: DeviceSelector,
    pub session_policy: SessionPolicy,
    pub lease: LeaseSpec,
}

impl AbilityRequirement {
    pub fn validate(&self) -> Result<(), ValidationError> {
        self.session_policy.validate()?;
        self.lease.validate()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_names() {
        assert!(Namespace::new("com.mocharealm").is_ok());
        assert!(AbilityName::new("media.audio.render").is_ok());
        assert!(AbilityName::new("Media.Audio").is_err());
        assert!(Namespace::new("com..example").is_err());
    }

    #[test]
    fn rejects_unbounded_configuration() {
        assert!(LeaseSpec::Renewable { ttl_ms: 0 }.validate().is_err());
        assert!(
            SessionPolicy {
                minimum_participants: 1,
                maximum_participants: 0,
                allow_reconfiguration: true
            }
            .validate()
            .is_err()
        );
    }
}
