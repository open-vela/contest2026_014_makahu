//! Ability payload E2EE using standard HKDF-SHA256 and ChaCha20-Poly1305.

use chacha20poly1305::{
    ChaCha20Poly1305, KeyInit,
    aead::{Aead, Payload},
};
use fabric_core::{ChannelId, ParticipantId, SessionId};
use hkdf::Hkdf;
use sha2::Sha256;
use std::collections::BTreeSet;
use thiserror::Error;
use zeroize::Zeroizing;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CiphertextEnvelope {
    pub sequence: u64,
    pub ciphertext: Vec<u8>,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum CryptoError {
    #[error("key derivation failed")]
    KeyDerivation,
    #[error("sequence space exhausted")]
    SequenceExhausted,
    #[error("ciphertext authentication failed")]
    AuthenticationFailed,
    #[error("sequence was replayed or is outside the replay window")]
    Replay,
    #[error("participant is not a member of the current key epoch")]
    NotMember,
}

#[derive(Clone, Debug)]
struct KeyContext {
    session: SessionId,
    channel: ChannelId,
    epoch: u64,
    sender: ParticipantId,
}
impl KeyContext {
    fn bytes(&self) -> Vec<u8> {
        let mut output = b"device-fabric/e2ee/v1".to_vec();
        output.extend_from_slice(self.session.0.as_bytes());
        output.extend_from_slice(self.channel.0.as_bytes());
        output.extend_from_slice(&self.epoch.to_be_bytes());
        output.extend_from_slice(self.sender.0.as_bytes());
        output
    }
    fn aad(&self, sequence: u64) -> Vec<u8> {
        let mut value = self.bytes();
        value.extend_from_slice(&sequence.to_be_bytes());
        value
    }
}

pub struct SenderCipher {
    key: Zeroizing<[u8; 32]>,
    context: KeyContext,
    next_sequence: u64,
}
impl SenderCipher {
    pub fn new(
        master_key: &[u8; 32],
        session: SessionId,
        channel: ChannelId,
        epoch: u64,
        sender: ParticipantId,
    ) -> Result<Self, CryptoError> {
        let context = KeyContext {
            session,
            channel,
            epoch,
            sender,
        };
        let key = derive(master_key, &context.bytes())?;
        Ok(Self {
            key: Zeroizing::new(key),
            context,
            next_sequence: 1,
        })
    }
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<CiphertextEnvelope, CryptoError> {
        let sequence = self.next_sequence;
        self.next_sequence = self
            .next_sequence
            .checked_add(1)
            .ok_or(CryptoError::SequenceExhausted)?;
        let cipher = ChaCha20Poly1305::new((&*self.key).into());
        let ciphertext = cipher
            .encrypt(
                &nonce(sequence),
                Payload {
                    msg: plaintext,
                    aad: &self.context.aad(sequence),
                },
            )
            .map_err(|_| CryptoError::AuthenticationFailed)?;
        Ok(CiphertextEnvelope {
            sequence,
            ciphertext,
        })
    }
}

pub struct ReceiverCipher {
    key: Zeroizing<[u8; 32]>,
    context: KeyContext,
    replay: ReplayWindow,
}
impl ReceiverCipher {
    pub fn new(
        master_key: &[u8; 32],
        session: SessionId,
        channel: ChannelId,
        epoch: u64,
        sender: ParticipantId,
    ) -> Result<Self, CryptoError> {
        let context = KeyContext {
            session,
            channel,
            epoch,
            sender,
        };
        let key = derive(master_key, &context.bytes())?;
        Ok(Self {
            key: Zeroizing::new(key),
            context,
            replay: ReplayWindow::default(),
        })
    }
    pub fn decrypt(&mut self, envelope: &CiphertextEnvelope) -> Result<Vec<u8>, CryptoError> {
        if !self.replay.accepts(envelope.sequence) {
            return Err(CryptoError::Replay);
        }
        let cipher = ChaCha20Poly1305::new((&*self.key).into());
        let plaintext = cipher
            .decrypt(
                &nonce(envelope.sequence),
                Payload {
                    msg: &envelope.ciphertext,
                    aad: &self.context.aad(envelope.sequence),
                },
            )
            .map_err(|_| CryptoError::AuthenticationFailed)?;
        self.replay.mark(envelope.sequence);
        Ok(plaintext)
    }
}

#[derive(Default)]
struct ReplayWindow {
    highest: u64,
    bitmap: u64,
}
impl ReplayWindow {
    fn accepts(&self, sequence: u64) -> bool {
        if sequence == 0 {
            return false;
        }
        if sequence > self.highest {
            return true;
        }
        let distance = self.highest - sequence;
        distance < 64 && self.bitmap & (1 << distance) == 0
    }
    fn mark(&mut self, sequence: u64) {
        if sequence > self.highest {
            let shift = sequence - self.highest;
            self.bitmap = if shift >= 64 {
                1
            } else {
                (self.bitmap << shift) | 1
            };
            self.highest = sequence;
        } else {
            self.bitmap |= 1 << (self.highest - sequence);
        }
    }
}

pub struct GroupKeys {
    epoch: u64,
    master: Zeroizing<[u8; 32]>,
    members: BTreeSet<ParticipantId>,
}
impl GroupKeys {
    #[must_use]
    pub fn new(epoch: u64, master: [u8; 32], members: BTreeSet<ParticipantId>) -> Self {
        Self {
            epoch,
            master: Zeroizing::new(master),
            members,
        }
    }
    pub fn rotate(
        &mut self,
        epoch: u64,
        master: [u8; 32],
        members: BTreeSet<ParticipantId>,
    ) -> Result<(), CryptoError> {
        if epoch <= self.epoch {
            return Err(CryptoError::KeyDerivation);
        }
        self.master = Zeroizing::new(master);
        self.epoch = epoch;
        self.members = members;
        Ok(())
    }
    pub fn member_key(
        &self,
        participant: ParticipantId,
    ) -> Result<Zeroizing<[u8; 32]>, CryptoError> {
        if !self.members.contains(&participant) {
            return Err(CryptoError::NotMember);
        }
        let key = derive(&self.master, &self.epoch.to_be_bytes())?;
        Ok(Zeroizing::new(key))
    }
}

fn derive(master: &[u8; 32], info: &[u8]) -> Result<[u8; 32], CryptoError> {
    let hkdf = Hkdf::<Sha256>::new(Some(b"device-fabric/e2ee"), master);
    let mut output = [0; 32];
    hkdf.expand(info, &mut output)
        .map_err(|_| CryptoError::KeyDerivation)?;
    Ok(output)
}
fn nonce(sequence: u64) -> chacha20poly1305::Nonce {
    let mut nonce = [0; 12];
    nonce[4..].copy_from_slice(&sequence.to_be_bytes());
    nonce.into()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ciphertext_is_opaque_and_replay_is_rejected() {
        let session = SessionId::new();
        let channel = ChannelId::new();
        let sender = ParticipantId::new();
        let mut encryptor = SenderCipher::new(&[7; 32], session, channel, 1, sender).unwrap();
        let mut decryptor = ReceiverCipher::new(&[7; 32], session, channel, 1, sender).unwrap();
        let envelope = encryptor.encrypt(b"secret ability payload").unwrap();
        assert!(!envelope.ciphertext.windows(6).any(|part| part == b"secret"));
        assert_eq!(
            decryptor.decrypt(&envelope).unwrap(),
            b"secret ability payload"
        );
        assert_eq!(decryptor.decrypt(&envelope), Err(CryptoError::Replay));
    }
    #[test]
    fn removed_member_cannot_receive_new_epoch_key() {
        let retained = ParticipantId::new();
        let removed = ParticipantId::new();
        let mut keys = GroupKeys::new(1, [1; 32], BTreeSet::from([retained, removed]));
        assert!(keys.member_key(removed).is_ok());
        keys.rotate(2, [2; 32], BTreeSet::from([retained])).unwrap();
        assert_eq!(keys.member_key(removed), Err(CryptoError::NotMember));
    }
}
