//! Authenticated application identities.

use crate::ValidationError;

#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum Platform {
    Linux,
    MacOs,
    Windows,
    Android,
    Ios,
    Test,
}

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct OsSubject(pub String);

#[derive(Clone, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct AppPrincipal {
    pub platform: Platform,
    pub stable_app_id: String,
    pub publisher_id: Option<String>,
    pub signing_digest: Option<[u8; 32]>,
    pub os_subject: OsSubject,
}

impl AppPrincipal {
    pub fn validate(&self) -> Result<(), ValidationError> {
        validate_identity(&self.stable_app_id, "stable_app_id")?;
        if let Some(publisher) = &self.publisher_id {
            validate_identity(publisher, "publisher_id")?;
        }
        validate_identity(&self.os_subject.0, "os_subject")
    }
}

fn validate_identity(value: &str, field: &'static str) -> Result<(), ValidationError> {
    if value.is_empty() {
        return Err(ValidationError::Empty { field });
    }
    if value.len() > 255 {
        return Err(ValidationError::TooLong { field, max: 255 });
    }
    if value.chars().any(char::is_control) {
        return Err(ValidationError::InvalidCharacters { field });
    }
    Ok(())
}
