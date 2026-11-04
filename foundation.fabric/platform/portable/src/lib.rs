//! Allocation-free device-link security and bounded BLE record codec.
//! The C host owns GATT scheduling and Session/policy authorization. An OS bond
//! does not authorize a Fabric peer, and an authenticated peer is not itself an
//! application-channel grant.
#![cfg_attr(not(feature = "std"), no_std)]

use chacha20poly1305::{ChaCha20Poly1305, KeyInit, Nonce, Tag, aead::AeadInPlace};
use curve25519_dalek::montgomery::MontgomeryPoint;
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use zeroize::Zeroize;

pub const MAX_APP_PAYLOAD: usize = 12288;
pub const MAX_PAYLOAD: usize = MAX_APP_PAYLOAD + 84;
pub const HELLO_BYTES: usize = 204;
pub const RECORD_OVERHEAD: usize = 25;
pub const MAX_RECORD: usize = MAX_PAYLOAD + RECORD_OVERHEAD;
const HELLO_DOMAIN: &[u8] = b"foundation-fabric/ble-device-hello/v1";
const KEY_DOMAIN: &[u8] = b"foundation-fabric/ble-device-keys/v1";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(i32)]
pub enum Error {
    Invalid = -1,
    Unauthorized = -2,
    Unsupported = -3,
    TooLarge = -4,
    Stale = -5,
    Duplicate = -6,
    NotReady = -7,
    Crypto = -8,
    Busy = -9,
}

/// Local identity seed is the same Ed25519 seed as fabric-identity. Peer public
/// key must come from its trusted Fabric pairing record, never from GATT alone.
/// entropy MUST be fresh CSPRNG output for EVERY connection (including restart).
/// State must be reset on disconnect; no key/sequence reuse or resume is allowed.
pub struct DeviceLink {
    local_public: [u8; 32],
    peer_public: [u8; 32],
    ephemeral_secret: [u8; 32],
    hello: [u8; HELLO_BYTES],
    tx_key: [u8; 32],
    rx_key: [u8; 32],
    tx_sequence: u64,
    rx_sequence: u64,
    rx_window: u64,
    ready: bool,
    negotiated_app_bytes: usize,
    transcript: [u8; 32],
    pairing_nonce: [u8; 32],
    peer_nonce_commit: [u8; 32],
}

impl DeviceLink {
    pub fn new(
        seed: &[u8; 32],
        trusted_peer: &[u8; 32],
        entropy: &[u8; 32],
    ) -> Result<Self, Error> {
        let identity = SigningKey::from_bytes(seed);
        let public = identity.verifying_key().to_bytes();
        if public == *trusted_peer || *entropy == [0; 32] {
            return Err(Error::Invalid);
        }
        VerifyingKey::from_bytes(trusted_peer).map_err(|_| Error::Unauthorized)?;
        let ephemeral_public = MontgomeryPoint::mul_base_clamped(*entropy).to_bytes();
        let mut hello = [0u8; HELLO_BYTES];
        hello[..32].copy_from_slice(&public);
        hello[32..64].copy_from_slice(&Sha256::digest(trusted_peer));
        hello[64..96].copy_from_slice(&ephemeral_public);
        // The nonce is independent in domain from the X25519 scalar; hashing
        // avoids exposing secret scalar bits in the public handshake.
        let mut nonce_hash = Sha256::new();
        nonce_hash.update(b"fabric-ble-nonce");
        nonce_hash.update(entropy);
        let pairing_nonce: [u8; 32] = nonce_hash.finalize().into();
        hello[96..128].copy_from_slice(&Sha256::digest(pairing_nonce));
        hello[128..132].copy_from_slice(b"FBP1");
        hello[132..136].copy_from_slice(&(MAX_APP_PAYLOAD as u32).to_be_bytes());
        hello[136..140].copy_from_slice(&1u32.to_be_bytes()); // ReliableMessages only
        let mut hash = Sha256::new();
        hash.update(HELLO_DOMAIN);
        hash.update(&hello[..140]);
        let digest: [u8; 32] = hash.finalize().into();
        hello[140..].copy_from_slice(&identity.sign(&digest).to_bytes());
        Ok(Self {
            local_public: public,
            peer_public: *trusted_peer,
            ephemeral_secret: *entropy,
            hello,
            tx_key: [0; 32],
            rx_key: [0; 32],
            tx_sequence: 0,
            rx_sequence: 0,
            rx_window: 0,
            ready: false,
            negotiated_app_bytes: 0,
            transcript: [0; 32],
            pairing_nonce,
            peer_nonce_commit: [0; 32],
        })
    }
    pub fn hello(&self) -> &[u8; HELLO_BYTES] {
        &self.hello
    }
    pub fn establish(&mut self, peer: &[u8]) -> Result<(), Error> {
        if self.ready {
            return Err(Error::Stale);
        }
        if peer.len() != HELLO_BYTES
            || peer[..32] != self.peer_public
            || peer[32..64] != Sha256::digest(self.local_public)[..]
        {
            return Err(Error::Unauthorized);
        }
        let mut hash = Sha256::new();
        hash.update(HELLO_DOMAIN);
        hash.update(&peer[..140]);
        let digest: [u8; 32] = hash.finalize().into();
        let signature: [u8; 64] = peer[140..].try_into().map_err(|_| Error::Invalid)?;
        VerifyingKey::from_bytes(&self.peer_public)
            .map_err(|_| Error::Unauthorized)?
            .verify_strict(&digest, &Signature::from_bytes(&signature))
            .map_err(|_| Error::Unauthorized)?;
        if &peer[128..132] != b"FBP1" || peer[136..140] != 1u32.to_be_bytes() {
            return Err(Error::Unsupported);
        }
        let remote_max =
            u32::from_be_bytes(peer[132..136].try_into().map_err(|_| Error::Invalid)?) as usize;
        if remote_max == 0 {
            return Err(Error::Unsupported);
        }
        self.negotiated_app_bytes = MAX_APP_PAYLOAD.min(remote_max);
        self.peer_nonce_commit.copy_from_slice(&peer[96..128]);
        let remote_point = MontgomeryPoint(peer[64..96].try_into().map_err(|_| Error::Invalid)?);
        let mut shared = remote_point.mul_clamped(self.ephemeral_secret).to_bytes();
        if shared == [0; 32] {
            return Err(Error::Unauthorized);
        }
        let mut transcript = Sha256::new();
        transcript.update(KEY_DOMAIN);
        let local_first = self.local_public < self.peer_public;
        if local_first {
            transcript.update(self.hello);
            transcript.update(peer);
        } else {
            transcript.update(peer);
            transcript.update(self.hello);
        }
        let salt = transcript.finalize();
        self.transcript.copy_from_slice(&salt);
        let mut keys = [0u8; 64];
        Hkdf::<Sha256>::new(Some(&salt), &shared)
            .expand(KEY_DOMAIN, &mut keys)
            .map_err(|_| Error::Crypto)?;
        if local_first {
            self.tx_key.copy_from_slice(&keys[..32]);
            self.rx_key.copy_from_slice(&keys[32..]);
        } else {
            self.tx_key.copy_from_slice(&keys[32..]);
            self.rx_key.copy_from_slice(&keys[..32]);
        }
        shared.zeroize();
        keys.zeroize();
        self.ephemeral_secret.zeroize();
        self.ready = true;
        Ok(())
    }
    pub fn negotiated_app_bytes(&self) -> usize {
        self.negotiated_app_bytes
    }

    /// type 0 = framework control, 1 = app message, 2 = receipt. Data authorization
    /// is checked by the Session host before seal and after open; no business
    /// payload is admitted through type 0 by this codec itself.
    pub fn seal(&mut self, kind: u8, payload: &[u8], out: &mut [u8]) -> Result<usize, Error> {
        if !self.ready {
            return Err(Error::NotReady);
        }
        if kind > 2 {
            return Err(Error::Unsupported);
        }
        let size = payload
            .len()
            .checked_add(RECORD_OVERHEAD)
            .ok_or(Error::TooLarge)?;
        if payload.len() > MAX_PAYLOAD || out.len() < size {
            return Err(Error::TooLarge);
        }
        self.tx_sequence = self.tx_sequence.checked_add(1).ok_or(Error::Stale)?;
        out[0] = kind;
        out[1..9].copy_from_slice(&self.tx_sequence.to_be_bytes());
        out[9..9 + payload.len()].copy_from_slice(payload);
        let mut nonce = [0u8; 12];
        nonce[4..].copy_from_slice(&self.tx_sequence.to_be_bytes());
        let mut aad = [0u8; 9];
        aad.copy_from_slice(&out[..9]);
        let tag = ChaCha20Poly1305::new((&self.tx_key).into())
            .encrypt_in_place_detached(
                Nonce::from_slice(&nonce),
                &aad,
                &mut out[9..9 + payload.len()],
            )
            .map_err(|_| Error::Crypto)?;
        out[9 + payload.len()..size].copy_from_slice(&tag);
        Ok(size)
    }
    pub fn open(&mut self, record: &mut [u8]) -> Result<(u8, usize), Error> {
        if !self.ready {
            return Err(Error::NotReady);
        }
        if record.len() < RECORD_OVERHEAD || record.len() > MAX_RECORD || record[0] > 2 {
            return Err(Error::Invalid);
        }
        let seq = u64::from_be_bytes(record[1..9].try_into().map_err(|_| Error::Invalid)?);
        let payload_len = record.len() - RECORD_OVERHEAD;
        let mut nonce = [0u8; 12];
        nonce[4..].copy_from_slice(&seq.to_be_bytes());
        let mut aad = [0u8; 9];
        aad.copy_from_slice(&record[..9]);
        let tag: [u8; 16] = record[9 + payload_len..]
            .try_into()
            .map_err(|_| Error::Invalid)?;
        ChaCha20Poly1305::new((&self.rx_key).into())
            .decrypt_in_place_detached(
                Nonce::from_slice(&nonce),
                &aad,
                &mut record[9..9 + payload_len],
                Tag::from_slice(&tag),
            )
            .map_err(|_| Error::Unauthorized)?;
        // Authenticate before reporting duplicate so a forged sequence never gets a receipt.
        // A receipt can overtake a data record that was lost/backpressured.
        // Admit each authenticated sequence once within a bounded replay
        // window; advancing a single watermark would discard its retry.
        if seq == 0 {
            record[9..9 + payload_len].zeroize();
            return Err(Error::Invalid);
        }
        if seq > self.rx_sequence {
            let advance = seq - self.rx_sequence;
            self.rx_window = if advance >= 64 { 1 } else { (self.rx_window << advance) | 1 };
            self.rx_sequence = seq;
        } else {
            let age = self.rx_sequence - seq;
            if age >= 64 || self.rx_window & (1u64 << age) != 0 {
                record[9..9 + payload_len].zeroize();
                return Err(Error::Duplicate);
            }
            self.rx_window |= 1u64 << age;
        }
        Ok((record[0], payload_len))
    }
}
impl Drop for DeviceLink {
    fn drop(&mut self) {
        self.ephemeral_secret.zeroize();
        self.pairing_nonce.zeroize();
        self.tx_key.zeroize();
        self.rx_key.zeroize();
    }
}

/// GATT fragment wire: version(1), offset:u16be, total:u16be, payload. Strict
/// contiguous receive plus identical-prefix duplicate handling; caller resets
/// on deadline/disconnect. Entire AEAD record is retried after receipt timeout.
pub struct Reassembly {
    bytes: [u8; MAX_RECORD],
    received: usize,
    total: usize,
}
impl Default for Reassembly {
    fn default() -> Self {
        Self {
            bytes: [0; MAX_RECORD],
            received: 0,
            total: 0,
        }
    }
}
impl Reassembly {
    pub fn reset(&mut self) {
        self.bytes.zeroize();
        self.received = 0;
        self.total = 0;
    }
    pub fn feed(&mut self, fragment: &[u8]) -> Result<Option<&[u8]>, Error> {
        if fragment.len() < 6 || fragment[0] != 1 {
            return Err(Error::Invalid);
        }
        let offset = usize::from(u16::from_be_bytes([fragment[1], fragment[2]]));
        let total = usize::from(u16::from_be_bytes([fragment[3], fragment[4]]));
        let body = &fragment[5..];
        if total == 0 || total > MAX_RECORD || offset + body.len() > total {
            return Err(Error::TooLarge);
        }
        if offset == 0 && (self.total != total || self.received == self.total) {
            self.reset();
            self.total = total;
        }
        if total != self.total || offset > self.received {
            return Err(Error::Busy);
        }
        let overlap = (self.received - offset).min(body.len());
        if self.bytes[offset..offset + overlap] != body[..overlap] {
            return Err(Error::Invalid);
        }
        self.bytes[offset..offset + body.len()].copy_from_slice(body);
        self.received = self.received.max(offset + body.len());
        if self.received == self.total {
            Ok(Some(&self.bytes[..self.total]))
        } else {
            Ok(None)
        }
    }
}

pub fn fragment(record: &[u8], offset: usize, out: &mut [u8]) -> Result<usize, Error> {
    if record.is_empty() || record.len() > MAX_RECORD || offset >= record.len() || out.len() < 6 {
        return Err(Error::Invalid);
    }
    let count = (out.len() - 5).min(record.len() - offset);
    out[0] = 1;
    out[1..3].copy_from_slice(&(offset as u16).to_be_bytes());
    out[3..5].copy_from_slice(&(record.len() as u16).to_be_bytes());
    out[5..5 + count].copy_from_slice(&record[offset..offset + count]);
    Ok(5 + count)
}

mod ffi;
pub mod pairing;
pub mod session;

#[cfg(not(feature = "std"))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    loop {
        core::hint::spin_loop();
    }
}

// Android's prebuilt core carries a DW.ref to this symbol even for panic=abort.
// This library never unwinds across C. Unexpected external unwinding is fatal
// (_URC_FATAL_PHASE1_ERROR); exporting this avoids a lazy unresolved .so symbol.
#[cfg(all(not(feature = "std"), target_os = "android"))]
#[unsafe(no_mangle)]
pub extern "C" fn rust_eh_personality() -> i32 {
    3
}
