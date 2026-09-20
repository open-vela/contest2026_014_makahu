//! Device keys, OS-authenticated application principals, and signed remote app attestations.

use std::{
    collections::BTreeMap,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::RwLock,
};

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use fabric_core::{
    AbilityContractRef, AbilityInstanceId, AppPrincipal, DeviceId, OsSubject, Platform,
};
use rand_core::OsRng;
use sha2::{Digest, Sha256};
use thiserror::Error;

#[derive(Debug, Error, Eq, PartialEq)]
pub enum IdentityError {
    #[error("declared application identity does not match OS credentials")]
    AppIdentityMismatch,
    #[error("invalid public key")]
    InvalidPublicKey,
    #[error("device ID is not bound to the supplied public key")]
    DeviceIdMismatch,
    #[error("signature verification failed")]
    InvalidSignature,
    #[error("attestation is not currently valid")]
    AttestationExpired,
    #[error("device trust store is unavailable")]
    TrustStoreUnavailable,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum DeviceTrust {
    #[default]
    Unknown,
    PendingUserConfirmation,
    Trusted,
    Blocked,
}

pub trait DeviceTrustStore: Send + Sync {
    fn trust(&self, device_id: DeviceId) -> Result<DeviceTrust, IdentityError>;
    fn set_trust(&self, device_id: DeviceId, trust: DeviceTrust) -> Result<(), IdentityError>;

    fn entries(&self) -> Result<Vec<(DeviceId, DeviceTrust)>, IdentityError> {
        Ok(Vec::new())
    }

    fn remove(&self, device_id: DeviceId) -> Result<(), IdentityError> {
        self.set_trust(device_id, DeviceTrust::Unknown)
    }
}

#[derive(Default)]
pub struct MemoryDeviceTrustStore {
    entries: RwLock<BTreeMap<DeviceId, DeviceTrust>>,
}

impl DeviceTrustStore for MemoryDeviceTrustStore {
    fn trust(&self, device_id: DeviceId) -> Result<DeviceTrust, IdentityError> {
        Ok(self
            .entries
            .read()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .get(&device_id)
            .copied()
            .unwrap_or_default())
    }

    fn set_trust(&self, device_id: DeviceId, trust: DeviceTrust) -> Result<(), IdentityError> {
        let mut entries = self
            .entries
            .write()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        if trust == DeviceTrust::Unknown {
            entries.remove(&device_id);
        } else {
            entries.insert(device_id, trust);
        }
        Ok(())
    }

    fn entries(&self) -> Result<Vec<(DeviceId, DeviceTrust)>, IdentityError> {
        Ok(self
            .entries
            .read()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .iter()
            .map(|(device, trust)| (*device, *trust))
            .collect())
    }

    fn remove(&self, device_id: DeviceId) -> Result<(), IdentityError> {
        self.entries
            .write()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .remove(&device_id);
        Ok(())
    }
}

pub struct FileDeviceTrustStore {
    path: PathBuf,
    entries: RwLock<BTreeMap<DeviceId, DeviceTrust>>,
}

impl FileDeviceTrustStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self, IdentityError> {
        let path = path.into();
        let entries = if path.exists() {
            parse_trust_file(
                &fs::read_to_string(&path).map_err(|_| IdentityError::TrustStoreUnavailable)?,
            )?
        } else {
            BTreeMap::new()
        };
        Ok(Self {
            path,
            entries: RwLock::new(entries),
        })
    }

    fn persist(&self, entries: &BTreeMap<DeviceId, DeviceTrust>) -> Result<(), IdentityError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
        {
            fs::create_dir_all(parent).map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        let temporary = temporary_path(&self.path);
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temporary)
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        for (device, trust) in entries {
            writeln!(file, "{} {}", encode_device_id(*device), trust_name(*trust))
                .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        file.sync_all()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        if self.path.exists() {
            fs::remove_file(&self.path).map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        fs::rename(&temporary, &self.path).map_err(|_| IdentityError::TrustStoreUnavailable)
    }
}

impl DeviceTrustStore for FileDeviceTrustStore {
    fn trust(&self, device_id: DeviceId) -> Result<DeviceTrust, IdentityError> {
        Ok(self
            .entries
            .read()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .get(&device_id)
            .copied()
            .unwrap_or_default())
    }

    fn set_trust(&self, device_id: DeviceId, trust: DeviceTrust) -> Result<(), IdentityError> {
        let mut entries = self
            .entries
            .write()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        if trust == DeviceTrust::Unknown {
            entries.remove(&device_id);
        } else {
            entries.insert(device_id, trust);
        }
        self.persist(&entries)
    }

    fn entries(&self) -> Result<Vec<(DeviceId, DeviceTrust)>, IdentityError> {
        Ok(self
            .entries
            .read()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .iter()
            .map(|(device, trust)| (*device, *trust))
            .collect())
    }

    fn remove(&self, device_id: DeviceId) -> Result<(), IdentityError> {
        self.set_trust(device_id, DeviceTrust::Unknown)
    }
}

pub struct FileDeviceLabelStore {
    path: PathBuf,
    entries: RwLock<BTreeMap<DeviceId, String>>,
}

impl FileDeviceLabelStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self, IdentityError> {
        let path = path.into();
        let entries = if path.exists() {
            parse_label_file(
                &fs::read_to_string(&path).map_err(|_| IdentityError::TrustStoreUnavailable)?,
            )?
        } else {
            BTreeMap::new()
        };
        Ok(Self {
            path,
            entries: RwLock::new(entries),
        })
    }

    pub fn label(&self, device_id: DeviceId) -> Result<Option<String>, IdentityError> {
        Ok(self
            .entries
            .read()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?
            .get(&device_id)
            .cloned())
    }

    pub fn set_label(&self, device_id: DeviceId, label: &str) -> Result<(), IdentityError> {
        let label = sanitize_device_label(label);
        let mut entries = self
            .entries
            .write()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        if label.is_empty() {
            entries.remove(&device_id);
        } else {
            entries.insert(device_id, label);
        }
        self.persist(&entries)
    }

    pub fn remove(&self, device_id: DeviceId) -> Result<(), IdentityError> {
        let mut entries = self
            .entries
            .write()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        entries.remove(&device_id);
        self.persist(&entries)
    }

    fn persist(&self, entries: &BTreeMap<DeviceId, String>) -> Result<(), IdentityError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
        {
            fs::create_dir_all(parent).map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        let temporary = temporary_path(&self.path);
        let mut file = OpenOptions::new()
            .create(true)
            .truncate(true)
            .write(true)
            .open(&temporary)
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        for (device, label) in entries {
            writeln!(file, "{} {}", encode_device_id(*device), label)
                .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        file.sync_all()
            .map_err(|_| IdentityError::TrustStoreUnavailable)?;
        if self.path.exists() {
            fs::remove_file(&self.path).map_err(|_| IdentityError::TrustStoreUnavailable)?;
        }
        fs::rename(&temporary, &self.path).map_err(|_| IdentityError::TrustStoreUnavailable)
    }
}

fn parse_trust_file(contents: &str) -> Result<BTreeMap<DeviceId, DeviceTrust>, IdentityError> {
    let mut entries = BTreeMap::new();
    for line in contents.lines().filter(|line| !line.trim().is_empty()) {
        let (device, trust) = line
            .split_once(' ')
            .ok_or(IdentityError::TrustStoreUnavailable)?;
        let device = decode_device_id(device)?;
        let trust = match trust {
            "pending" => DeviceTrust::PendingUserConfirmation,
            "trusted" => DeviceTrust::Trusted,
            "blocked" => DeviceTrust::Blocked,
            _ => return Err(IdentityError::TrustStoreUnavailable),
        };
        entries.insert(device, trust);
    }
    Ok(entries)
}

fn parse_label_file(contents: &str) -> Result<BTreeMap<DeviceId, String>, IdentityError> {
    let mut entries = BTreeMap::new();
    for line in contents.lines().filter(|line| !line.trim().is_empty()) {
        let (device, label) = line
            .split_once(' ')
            .ok_or(IdentityError::TrustStoreUnavailable)?;
        let label = sanitize_device_label(label);
        if !label.is_empty() {
            entries.insert(decode_device_id(device)?, label);
        }
    }
    Ok(entries)
}

#[must_use]
pub fn sanitize_device_label(label: &str) -> String {
    label
        .chars()
        .map(|ch| {
            if ch == '|' || ch == '\r' || ch == '\n' {
                ' '
            } else {
                ch
            }
        })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(64)
        .collect()
}

#[must_use]
pub fn encode_device_id(device: DeviceId) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(64);
    for byte in device.0 {
        encoded.push(char::from(HEX[usize::from(byte >> 4)]));
        encoded.push(char::from(HEX[usize::from(byte & 0x0f)]));
    }
    encoded
}

pub fn decode_device_id(encoded: &str) -> Result<DeviceId, IdentityError> {
    if encoded.len() != 64 || !encoded.is_ascii() {
        return Err(IdentityError::TrustStoreUnavailable);
    }
    let mut bytes = [0_u8; 32];
    for (index, pair) in encoded.as_bytes().chunks_exact(2).enumerate() {
        bytes[index] = (decode_hex(pair[0])? << 4) | decode_hex(pair[1])?;
    }
    Ok(DeviceId(bytes))
}

fn decode_hex(value: u8) -> Result<u8, IdentityError> {
    match value {
        b'0'..=b'9' => Ok(value - b'0'),
        b'a'..=b'f' => Ok(value - b'a' + 10),
        b'A'..=b'F' => Ok(value - b'A' + 10),
        _ => Err(IdentityError::TrustStoreUnavailable),
    }
}

fn trust_name(trust: DeviceTrust) -> &'static str {
    match trust {
        DeviceTrust::Unknown => "unknown",
        DeviceTrust::PendingUserConfirmation => "pending",
        DeviceTrust::Trusted => "trusted",
        DeviceTrust::Blocked => "blocked",
    }
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut value = path.as_os_str().to_os_string();
    value.push(".tmp");
    PathBuf::from(value)
}

pub trait DeviceKeyStore {
    fn load_seed(&self) -> Result<Option<[u8; 32]>, IdentityError>;
    fn store_seed(&self, seed: &[u8; 32]) -> Result<(), IdentityError>;
}

pub struct DeviceIdentity {
    signing_key: SigningKey,
    device_id: DeviceId,
}

impl DeviceIdentity {
    #[must_use]
    pub fn generate() -> Self {
        Self::from_signing_key(SigningKey::generate(&mut OsRng))
    }

    #[must_use]
    pub fn from_seed(seed: [u8; 32]) -> Self {
        Self::from_signing_key(SigningKey::from_bytes(&seed))
    }

    fn from_signing_key(signing_key: SigningKey) -> Self {
        let device_id = device_id_from_public_key(&signing_key.verifying_key().to_bytes());
        Self {
            signing_key,
            device_id,
        }
    }

    #[must_use]
    pub const fn device_id(&self) -> DeviceId {
        self.device_id
    }

    #[must_use]
    pub fn public_key(&self) -> [u8; 32] {
        self.signing_key.verifying_key().to_bytes()
    }

    #[must_use]
    pub fn seed_for_secure_storage(&self) -> [u8; 32] {
        self.signing_key.to_bytes()
    }

    #[must_use]
    pub fn sign_device_proof(&self, transcript_hash: &[u8; 32]) -> [u8; 64] {
        self.signing_key.sign(transcript_hash).to_bytes()
    }

    #[must_use]
    pub fn attest_app(&self, claims: AttestationClaims) -> SignedAppAttestation {
        let signature = self.signing_key.sign(&claims.canonical_bytes()).to_bytes();
        SignedAppAttestation { claims, signature }
    }
}

#[must_use]
pub fn device_id_from_public_key(public_key: &[u8; 32]) -> DeviceId {
    DeviceId(Sha256::digest(public_key).into())
}

pub fn verify_device_proof(
    device_id: DeviceId,
    public_key: [u8; 32],
    transcript_hash: &[u8; 32],
    signature: &[u8; 64],
) -> Result<(), IdentityError> {
    if device_id_from_public_key(&public_key) != device_id {
        return Err(IdentityError::DeviceIdMismatch);
    }
    let key = VerifyingKey::from_bytes(&public_key).map_err(|_| IdentityError::InvalidPublicKey)?;
    key.verify(transcript_hash, &Signature::from_bytes(signature))
        .map_err(|_| IdentityError::InvalidSignature)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PeerCredentials {
    pub platform: Platform,
    pub stable_app_id: String,
    pub publisher_id: Option<String>,
    pub signing_digest: Option<[u8; 32]>,
    pub os_subject: OsSubject,
}

pub trait OsCredentialAdapter: Send + Sync {
    fn authenticate(&self, credentials: &PeerCredentials) -> Result<AppPrincipal, IdentityError>;
}

pub fn bind_application(
    authenticated: AppPrincipal,
    declared_stable_app_id: Option<&str>,
) -> Result<AppPrincipal, IdentityError> {
    if declared_stable_app_id.is_some_and(|declared| declared != authenticated.stable_app_id) {
        return Err(IdentityError::AppIdentityMismatch);
    }
    Ok(authenticated)
}

#[must_use]
pub fn app_principal_digest(principal: &AppPrincipal) -> [u8; 32] {
    let mut hash = Sha256::new();
    put_bytes(&mut hash, &[principal.platform as u8]);
    put_bytes(&mut hash, principal.stable_app_id.as_bytes());
    put_option(
        &mut hash,
        principal.publisher_id.as_deref().map(str::as_bytes),
    );
    put_option(
        &mut hash,
        principal.signing_digest.as_ref().map(<[u8; 32]>::as_slice),
    );
    put_bytes(&mut hash, principal.os_subject.0.as_bytes());
    hash.finalize().into()
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AttestationClaims {
    pub remote_device_id: DeviceId,
    pub app_principal_digest: [u8; 32],
    pub ability_instance_id: AbilityInstanceId,
    pub ability_contract: AbilityContractRef,
    pub issued_at_ms: u64,
    pub expires_at_ms: u64,
    pub nonce: [u8; 32],
}

impl AttestationClaims {
    fn canonical_bytes(&self) -> Vec<u8> {
        let mut output = Vec::new();
        output.extend_from_slice(&self.remote_device_id.0);
        output.extend_from_slice(&self.app_principal_digest);
        output.extend_from_slice(self.ability_instance_id.0.as_bytes());
        put_vec(
            &mut output,
            self.ability_contract.key.namespace.as_str().as_bytes(),
        );
        put_vec(
            &mut output,
            self.ability_contract.key.name.as_str().as_bytes(),
        );
        output.extend_from_slice(&self.ability_contract.key.major.to_be_bytes());
        output.extend_from_slice(&self.ability_contract.protocol_hash);
        output.extend_from_slice(&self.issued_at_ms.to_be_bytes());
        output.extend_from_slice(&self.expires_at_ms.to_be_bytes());
        output.extend_from_slice(&self.nonce);
        output
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SignedAppAttestation {
    pub claims: AttestationClaims,
    pub signature: [u8; 64],
}

pub fn verify_app_attestation(
    attestation: &SignedAppAttestation,
    public_key: [u8; 32],
    now_ms: u64,
) -> Result<(), IdentityError> {
    if attestation.claims.remote_device_id != device_id_from_public_key(&public_key) {
        return Err(IdentityError::DeviceIdMismatch);
    }
    if now_ms < attestation.claims.issued_at_ms || now_ms >= attestation.claims.expires_at_ms {
        return Err(IdentityError::AttestationExpired);
    }
    let key = VerifyingKey::from_bytes(&public_key).map_err(|_| IdentityError::InvalidPublicKey)?;
    key.verify(
        &attestation.claims.canonical_bytes(),
        &Signature::from_bytes(&attestation.signature),
    )
    .map_err(|_| IdentityError::InvalidSignature)
}

fn put_bytes(hash: &mut Sha256, value: &[u8]) {
    hash.update((value.len() as u64).to_be_bytes());
    hash.update(value);
}

fn put_option(hash: &mut Sha256, value: Option<&[u8]>) {
    match value {
        Some(value) => {
            hash.update([1]);
            put_bytes(hash, value);
        }
        None => hash.update([0]),
    }
}

fn put_vec(output: &mut Vec<u8>, value: &[u8]) {
    output.extend_from_slice(&(value.len() as u64).to_be_bytes());
    output.extend_from_slice(value);
}

#[cfg(test)]
mod tests {
    use super::*;
    use fabric_core::*;

    fn principal() -> AppPrincipal {
        AppPrincipal {
            platform: Platform::Test,
            stable_app_id: "trusted.app".into(),
            publisher_id: Some("trusted.publisher".into()),
            signing_digest: Some([3; 32]),
            os_subject: OsSubject("pid:42".into()),
        }
    }

    #[test]
    fn declared_app_cannot_override_os_identity() {
        assert_eq!(
            bind_application(principal(), Some("evil.app")),
            Err(IdentityError::AppIdentityMismatch)
        );
        assert_eq!(
            bind_application(principal(), Some("trusted.app")).unwrap(),
            principal()
        );
    }

    #[test]
    fn attestation_is_signed_bound_and_expires() {
        let identity = DeviceIdentity::from_seed([7; 32]);
        let claims = AttestationClaims {
            remote_device_id: identity.device_id(),
            app_principal_digest: app_principal_digest(&principal()),
            ability_instance_id: AbilityInstanceId::new(),
            ability_contract: AbilityContractRef {
                key: AbilityKey {
                    namespace: Namespace::new("com.example").unwrap(),
                    name: AbilityName::new("echo").unwrap(),
                    major: 1,
                },
                protocol_hash: [1; 32],
            },
            issued_at_ms: 100,
            expires_at_ms: 200,
            nonce: [9; 32],
        };
        let attestation = identity.attest_app(claims);
        assert!(verify_app_attestation(&attestation, identity.public_key(), 150).is_ok());
        assert_eq!(
            verify_app_attestation(&attestation, identity.public_key(), 200),
            Err(IdentityError::AttestationExpired)
        );
    }

    #[test]
    fn file_trust_store_persists_and_removes_entries() {
        let path = std::env::temp_dir().join(format!(
            "fabric-trust-{}-{}.txt",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let device = DeviceId([9; 32]);
        let store = FileDeviceTrustStore::open(&path).unwrap();
        store.set_trust(device, DeviceTrust::Trusted).unwrap();
        drop(store);

        let reopened = FileDeviceTrustStore::open(&path).unwrap();
        assert_eq!(reopened.trust(device), Ok(DeviceTrust::Trusted));
        reopened.remove(device).unwrap();
        drop(reopened);
        assert_eq!(
            FileDeviceTrustStore::open(&path).unwrap().trust(device),
            Ok(DeviceTrust::Unknown)
        );
        let _ = std::fs::remove_file(path);
    }
}
