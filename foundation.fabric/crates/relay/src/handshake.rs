//! The relay authentication handshake.
//!
//! Registration and authentication are one step, as in iroh's relay: the relay
//! must be sure a connection that claims a `DeviceId` really holds that identity's
//! key, or a malicious client could register a victim's `DeviceId` and black-hole
//! its relayed traffic. On connect the relay sends a random [`RelayChallenge`];
//! the hub replies with its public key and an ed25519 signature over the
//! challenge, which the relay verifies before routing anything to that `DeviceId`.
//!
//! (The tunnelled QUIC is still end-to-end authenticated on top of this, so relay
//! auth is defence-in-depth against relay-level hijacking, not confidentiality.)

use fabric_core::DeviceId;
use fabric_identity::{DeviceIdentity, device_id_from_public_key, verify_device_proof};
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};

use crate::wire::RelayFrame;

/// Domain-separation prefix so a relay-challenge signature can never be replayed
/// as any other fabric signature.
const CHALLENGE_DOMAIN: &[u8] = b"fabric-relay-challenge-v1";

/// A server-issued challenge awaiting the client's signed proof.
#[derive(Clone, Copy, Debug)]
pub struct RelayChallenge {
    challenge: [u8; 16],
}

impl RelayChallenge {
    /// Generates a fresh random challenge.
    #[must_use]
    pub fn random() -> Self {
        let mut challenge = [0u8; 16];
        OsRng.fill_bytes(&mut challenge);
        Self { challenge }
    }

    /// The frame to send to the connecting client.
    #[must_use]
    pub fn frame(&self) -> RelayFrame {
        RelayFrame::ServerChallenge {
            challenge: self.challenge,
        }
    }

    /// Verifies a client's auth against this challenge, returning the
    /// cryptographically-authenticated `DeviceId` to register.
    pub fn verify(
        &self,
        public_key: [u8; 32],
        signature: [u8; 64],
    ) -> Result<DeviceId, RelayAuthError> {
        let device = device_id_from_public_key(&public_key);
        let hash = challenge_hash(&self.challenge);
        verify_device_proof(device, public_key, &hash, &signature)
            .map_err(|_| RelayAuthError::BadSignature)?;
        Ok(device)
    }
}

/// Builds the client's [`RelayFrame::ClientAuth`] reply for a received challenge.
#[must_use]
pub fn client_auth(identity: &DeviceIdentity, challenge: [u8; 16]) -> RelayFrame {
    let hash = challenge_hash(&challenge);
    RelayFrame::ClientAuth {
        public_key: identity.public_key(),
        signature: identity.sign_device_proof(&hash),
    }
}

fn challenge_hash(challenge: &[u8; 16]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(CHALLENGE_DOMAIN);
    hasher.update(challenge);
    hasher.finalize().into()
}

/// Errors authenticating a relay client.
#[derive(Clone, Copy, Debug, thiserror::Error, Eq, PartialEq)]
pub enum RelayAuthError {
    #[error("relay auth signature is invalid")]
    BadSignature,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn extract_challenge(frame: &RelayFrame) -> [u8; 16] {
        match frame {
            RelayFrame::ServerChallenge { challenge } => *challenge,
            _ => panic!("expected a ServerChallenge frame"),
        }
    }

    fn extract_auth(frame: &RelayFrame) -> ([u8; 32], [u8; 64]) {
        match frame {
            RelayFrame::ClientAuth {
                public_key,
                signature,
            } => (*public_key, *signature),
            _ => panic!("expected a ClientAuth frame"),
        }
    }

    #[test]
    fn valid_signature_authenticates_the_expected_device() {
        let identity = DeviceIdentity::from_seed([5; 32]);
        let challenge = RelayChallenge::random();
        let (public_key, signature) = extract_auth(&client_auth(
            &identity,
            extract_challenge(&challenge.frame()),
        ));
        assert_eq!(
            challenge.verify(public_key, signature).unwrap(),
            identity.device_id()
        );
    }

    #[test]
    fn a_tampered_signature_is_rejected() {
        let identity = DeviceIdentity::from_seed([5; 32]);
        let challenge = RelayChallenge::random();
        let (public_key, mut signature) = extract_auth(&client_auth(
            &identity,
            extract_challenge(&challenge.frame()),
        ));
        signature[0] ^= 0x01;
        assert_eq!(
            challenge.verify(public_key, signature),
            Err(RelayAuthError::BadSignature)
        );
    }

    #[test]
    fn a_signature_for_a_different_challenge_is_rejected() {
        let identity = DeviceIdentity::from_seed([5; 32]);
        let issued = RelayChallenge::random();
        let other = RelayChallenge::random();
        // Client signs `other`, but the server verifies against `issued`.
        let (public_key, signature) =
            extract_auth(&client_auth(&identity, extract_challenge(&other.frame())));
        assert_eq!(
            issued.verify(public_key, signature),
            Err(RelayAuthError::BadSignature)
        );
    }
}
