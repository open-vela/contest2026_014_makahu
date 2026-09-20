//! TLS for the relay: a self-signed server certificate pinned by its SHA-256
//! fingerprint on the client.
//!
//! The relay is not part of any PKI — a hub reaches it at a configured address
//! and pins its fingerprint (published alongside that address), so no CA is
//! involved. TLS here provides server authentication (you reached the relay you
//! meant to) and metadata confidentiality (an observer can't see which
//! `DeviceId`s are talking); the relayed payloads are already end-to-end
//! encrypted QUIC, and the client still proves its own identity via the
//! challenge-signature handshake inside the tunnel.

use std::sync::Arc;

use rcgen::{CertificateParams, KeyPair};
use rustls::{
    DigitallySignedStruct, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use thiserror::Error;

/// The DNS name the self-signed relay certificate is issued for. The client
/// pins by fingerprint, so this only has to be a syntactically valid name.
pub const RELAY_SERVER_NAME: &str = "fabric-relay";

/// Errors building relay TLS configuration.
#[derive(Debug, Error)]
pub enum TlsError {
    #[error("relay certificate generation failed: {0}")]
    Certificate(String),
    #[error("relay TLS configuration failed: {0}")]
    Configuration(String),
}

/// A SHA-256 fingerprint of a certificate's DER encoding.
#[must_use]
pub fn fingerprint(certificate: &CertificateDer<'_>) -> [u8; 32] {
    Sha256::digest(certificate.as_ref()).into()
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// Builds a server config with a fresh self-signed certificate, returning it and
/// the certificate fingerprint clients must pin.
pub fn server_config() -> Result<(Arc<rustls::ServerConfig>, [u8; 32]), TlsError> {
    let key_pair = KeyPair::generate().map_err(|error| TlsError::Certificate(error.to_string()))?;
    let params = CertificateParams::new(vec![RELAY_SERVER_NAME.to_string()])
        .map_err(|error| TlsError::Certificate(error.to_string()))?;
    let certificate = params
        .self_signed(&key_pair)
        .map_err(|error| TlsError::Certificate(error.to_string()))?;
    let certificate_der = certificate.der().clone();
    let fingerprint = fingerprint(&certificate_der);
    let key_der = PrivatePkcs8KeyDer::from(key_pair.serialize_der());

    let config = rustls::ServerConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .map_err(|error| TlsError::Configuration(error.to_string()))?
        .with_no_client_auth()
        .with_single_cert(vec![certificate_der], PrivateKeyDer::Pkcs8(key_der))
        .map_err(|error| TlsError::Configuration(error.to_string()))?;
    Ok((Arc::new(config), fingerprint))
}

/// Builds a client config that accepts exactly the server whose certificate
/// matches `pinned` (by SHA-256 fingerprint).
pub fn client_config(pinned: [u8; 32]) -> Result<Arc<rustls::ClientConfig>, TlsError> {
    let verifier = Arc::new(PinnedCertVerifier {
        pinned,
        provider: provider(),
    });
    let config = rustls::ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .map_err(|error| TlsError::Configuration(error.to_string()))?
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth();
    Ok(Arc::new(config))
}

/// A `ServerName` for dialing the relay.
///
/// # Panics
/// Never: [`RELAY_SERVER_NAME`] is a compile-time constant valid DNS name.
#[must_use]
pub fn server_name() -> ServerName<'static> {
    ServerName::try_from(RELAY_SERVER_NAME).expect("RELAY_SERVER_NAME is a valid DNS name")
}

/// Verifies the relay's certificate purely by pinned fingerprint.
#[derive(Debug)]
struct PinnedCertVerifier {
    pinned: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if intermediates.is_empty() && fingerprint(end_entity) == self.pinned {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(
                "relay certificate fingerprint does not match the pinned value".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(
            message,
            cert,
            dss,
            &self.provider.signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_config_and_pinned_client_config_build() {
        let (_server, fingerprint) = server_config().unwrap();
        // Every generated certificate is fresh, so two servers differ.
        let (_other, other_fingerprint) = server_config().unwrap();
        assert_ne!(fingerprint, other_fingerprint);
        client_config(fingerprint).unwrap();
    }
}
