use std::{fmt, sync::Arc};

use fabric_core::DeviceId;
use fabric_identity::{DeviceIdentity, device_id_from_public_key};
use noq::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rcgen::{CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, KeyPair};
use rustls::{
    CertificateError, DigitallySignedStruct, DistinguishedName as RustlsDistinguishedName,
    SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
    server::danger::{ClientCertVerified, ClientCertVerifier},
};
use thiserror::Error;
use x509_parser::{parse_x509_certificate, time::ASN1Time};

const ALPN: &[u8] = b"device-fabric/1";
const ED25519_OID: &str = "1.3.101.112";

#[derive(Debug, Error)]
pub enum TlsError {
    #[error("device certificate generation failed: {0}")]
    CertificateGeneration(String),
    #[error("TLS configuration failed: {0}")]
    Configuration(String),
    #[error("peer certificate is missing")]
    MissingPeerCertificate,
    #[error("peer certificate is malformed or unsupported")]
    InvalidPeerCertificate,
    #[error("peer certificate identity does not match DeviceId")]
    DeviceIdMismatch,
}

pub struct DeviceCertificate {
    certificate: CertificateDer<'static>,
    private_key: PrivatePkcs8KeyDer<'static>,
    public_key: [u8; 32],
    device_id: DeviceId,
}

impl DeviceCertificate {
    pub fn from_identity(identity: &DeviceIdentity) -> Result<Self, TlsError> {
        let pkcs8 = ed25519_pkcs8(identity.seed_for_secure_storage());
        let private_key = PrivatePkcs8KeyDer::from(pkcs8);
        let key_pair = KeyPair::from_pkcs8_der_and_sign_algo(&private_key, &rcgen::PKCS_ED25519)
            .map_err(|error| TlsError::CertificateGeneration(error.to_string()))?;
        let public_key: [u8; 32] = key_pair
            .public_key_raw()
            .try_into()
            .map_err(|_| TlsError::CertificateGeneration("invalid Ed25519 public key".into()))?;
        if public_key != identity.public_key() {
            return Err(TlsError::DeviceIdMismatch);
        }

        let mut params = CertificateParams::new(vec!["device-fabric.local".into()])
            .map_err(|error| TlsError::CertificateGeneration(error.to_string()))?;
        let mut distinguished_name = DistinguishedName::new();
        distinguished_name.push(DnType::CommonName, "Device Fabric Hub");
        params.distinguished_name = distinguished_name;
        params.extended_key_usages = vec![
            ExtendedKeyUsagePurpose::ServerAuth,
            ExtendedKeyUsagePurpose::ClientAuth,
        ];
        let certificate = params
            .self_signed(&key_pair)
            .map_err(|error| TlsError::CertificateGeneration(error.to_string()))?
            .der()
            .clone();

        Ok(Self {
            certificate,
            private_key,
            public_key,
            device_id: identity.device_id(),
        })
    }

    #[must_use]
    pub const fn device_id(&self) -> DeviceId {
        self.device_id
    }

    #[must_use]
    pub const fn public_key(&self) -> [u8; 32] {
        self.public_key
    }

    #[must_use]
    pub fn certificate(&self) -> CertificateDer<'static> {
        self.certificate.clone()
    }

    pub fn tls_configs(&self) -> Result<(noq::ServerConfig, noq::ClientConfig), TlsError> {
        let verifier = SelfSignedCertificateVerifier::new();
        let mut server_crypto = rustls::ServerConfig::builder()
            .with_client_cert_verifier(verifier.clone())
            .with_single_cert(
                vec![self.certificate.clone()],
                PrivateKeyDer::Pkcs8(self.private_key.clone_key()),
            )
            .map_err(|error| TlsError::Configuration(error.to_string()))?;
        server_crypto.alpn_protocols = vec![ALPN.to_vec()];

        let mut client_crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_client_auth_cert(
                vec![self.certificate.clone()],
                PrivateKeyDer::Pkcs8(self.private_key.clone_key()),
            )
            .map_err(|error| TlsError::Configuration(error.to_string()))?;
        client_crypto.alpn_protocols = vec![ALPN.to_vec()];

        let server = QuicServerConfig::try_from(server_crypto)
            .map_err(|error| TlsError::Configuration(error.to_string()))?;
        let client = QuicClientConfig::try_from(client_crypto)
            .map_err(|error| TlsError::Configuration(error.to_string()))?;
        Ok((
            noq::ServerConfig::with_crypto(Arc::new(server)),
            noq::ClientConfig::new(Arc::new(client)),
        ))
    }
}

#[derive(Clone)]
struct SelfSignedCertificateVerifier {
    provider: Arc<rustls::crypto::CryptoProvider>,
    root_hints: Arc<Vec<RustlsDistinguishedName>>,
}

impl SelfSignedCertificateVerifier {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            provider: Arc::new(rustls::crypto::ring::default_provider()),
            root_hints: Arc::new(Vec::new()),
        })
    }

    fn verify_certificate(
        certificate: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        now: UnixTime,
    ) -> Result<(), rustls::Error> {
        if !intermediates.is_empty() {
            return Err(CertificateError::UnknownIssuer.into());
        }
        validate_device_certificate(certificate, now)
            .map(|_| ())
            .map_err(|error| match error {
                CertificateValidationError::Expired => CertificateError::Expired.into(),
                CertificateValidationError::NotValidYet => CertificateError::NotValidYet.into(),
                CertificateValidationError::BadSignature => CertificateError::BadSignature.into(),
                CertificateValidationError::BadEncoding => CertificateError::BadEncoding.into(),
            })
    }

    fn verify_tls12(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }
}

impl fmt::Debug for SelfSignedCertificateVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("SelfSignedCertificateVerifier")
    }
}

impl ServerCertVerifier for SelfSignedCertificateVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Self::verify_certificate(end_entity, intermediates, now)?;
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify_tls12(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify_tls13(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

impl ClientCertVerifier for SelfSignedCertificateVerifier {
    fn root_hint_subjects(&self) -> &[RustlsDistinguishedName] {
        self.root_hints.as_slice()
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        Self::verify_certificate(end_entity, intermediates, now)?;
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify_tls12(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify_tls13(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Clone, Copy, Debug)]
enum CertificateValidationError {
    BadEncoding,
    BadSignature,
    Expired,
    NotValidYet,
}

fn validate_device_certificate(
    certificate: &CertificateDer<'_>,
    now: UnixTime,
) -> Result<[u8; 32], CertificateValidationError> {
    let (remainder, parsed) = parse_x509_certificate(certificate.as_ref())
        .map_err(|_| CertificateValidationError::BadEncoding)?;
    if !remainder.is_empty()
        || parsed.subject() != parsed.issuer()
        || parsed.public_key().algorithm.algorithm.to_id_string() != ED25519_OID
        || parsed.signature_algorithm.algorithm.to_id_string() != ED25519_OID
        || parsed.is_ca()
    {
        return Err(CertificateValidationError::BadEncoding);
    }

    let now_seconds =
        i64::try_from(now.as_secs()).map_err(|_| CertificateValidationError::BadEncoding)?;
    let now = ASN1Time::from_timestamp(now_seconds)
        .map_err(|_| CertificateValidationError::BadEncoding)?;
    if now < parsed.validity().not_before {
        return Err(CertificateValidationError::NotValidYet);
    }
    if now > parsed.validity().not_after {
        return Err(CertificateValidationError::Expired);
    }
    parsed
        .verify_signature(None)
        .map_err(|_| CertificateValidationError::BadSignature)?;
    parsed
        .public_key()
        .subject_public_key
        .data
        .as_ref()
        .try_into()
        .map_err(|_| CertificateValidationError::BadEncoding)
}

pub fn peer_public_key(connection: &noq::Connection) -> Result<[u8; 32], TlsError> {
    let identity = connection
        .peer_identity()
        .ok_or(TlsError::MissingPeerCertificate)?;
    let certificates = identity
        .downcast::<Vec<CertificateDer<'static>>>()
        .map_err(|_| TlsError::InvalidPeerCertificate)?;
    let [certificate] = certificates.as_slice() else {
        return Err(TlsError::InvalidPeerCertificate);
    };
    validate_device_certificate(certificate, UnixTime::now())
        .map_err(|_| TlsError::InvalidPeerCertificate)
}

pub fn verify_certificate_device_id(
    public_key: [u8; 32],
    claimed_device_id: DeviceId,
) -> Result<(), TlsError> {
    if device_id_from_public_key(&public_key) == claimed_device_id {
        Ok(())
    } else {
        Err(TlsError::DeviceIdMismatch)
    }
}

fn ed25519_pkcs8(seed: [u8; 32]) -> Vec<u8> {
    let mut der = Vec::with_capacity(48);
    der.extend_from_slice(&[
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04,
        0x20,
    ]);
    der.extend_from_slice(&seed);
    der
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn certificate_uses_the_device_identity_key_and_is_self_signed() {
        let identity = DeviceIdentity::from_seed([7; 32]);
        let certificate = DeviceCertificate::from_identity(&identity).unwrap();
        assert_eq!(certificate.public_key(), identity.public_key());
        assert_eq!(certificate.device_id(), identity.device_id());
        let parsed =
            validate_device_certificate(&certificate.certificate(), UnixTime::now()).unwrap();
        assert_eq!(parsed, identity.public_key());
        certificate.tls_configs().unwrap();
    }
}
