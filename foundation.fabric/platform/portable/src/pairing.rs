//! Fabric first-pairing device authentication and explicit mutual confirmation.
//! No application or provisioning payload exists in this state machine.
use super::*;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum PairingPhase {
    WaitingHello,
    AwaitingUser,
    AwaitingPeer,
    Persisting,
    Complete,
    Rejected,
}

pub struct Pairing {
    seed: [u8; 32],
    entropy: [u8; 32],
    expected: Option<[u8; 32]>,
    link: Option<DeviceLink>,
    sent_challenge: bool,
    peer_nonce: Option<[u8; 32]>,
    local_confirm: bool,
    remote_confirm: bool,
    persisted: bool,
    remote_complete: bool,
    sent_confirm: bool,
    sent_complete: bool,
    phase: PairingPhase,
    deadline: u64,
    retry: [u8; HELLO_BYTES],
    retry_len: usize,
    retry_at: u64,
    repeat_due: bool,
}
impl Pairing {
    /// Initiator pins the device public key from QR/discovery. Responder may use
    /// None; it learns the phone key cryptographically but grants it no trust.
    pub fn request(
        seed: [u8; 32],
        entropy: [u8; 32],
        expected: Option<[u8; 32]>,
        now: u64,
    ) -> Result<Self, Error> {
        if entropy == [0; 32] {
            return Err(Error::Invalid);
        }
        let link = expected
            .as_ref()
            .map(|peer| DeviceLink::new(&seed, peer, &entropy))
            .transpose()?;
        Ok(Self {
            seed,
            entropy,
            expected,
            link,
            sent_challenge: false,
            peer_nonce: None,
            local_confirm: false,
            remote_confirm: false,
            persisted: false,
            remote_complete: false,
            sent_confirm: false,
            sent_complete: false,
            phase: PairingPhase::WaitingHello,
            deadline: now.saturating_add(120000),
            retry: [0; HELLO_BYTES],
            retry_len: 0,
            retry_at: 0,
            repeat_due: false,
        })
    }
    fn check(&mut self, now: u64) -> Result<(), Error> {
        if now >= self.deadline || self.phase == PairingPhase::Rejected {
            self.reject();
            return Err(Error::Stale);
        }
        Ok(())
    }
    fn reject(&mut self) {
        self.phase = PairingPhase::Rejected;
        self.link = None;
        self.seed.zeroize();
        self.entropy.zeroize();
        self.retry.zeroize();
        self.retry_len = 0;
    }
    pub fn phase(&self) -> PairingPhase {
        self.phase
    }
    pub fn peer_public(&self) -> Option<[u8; 32]> {
        self.link
            .as_ref()
            .filter(|l| l.ready)
            .map(|l| l.peer_public)
    }
    pub fn code(&self) -> Result<u32, Error> {
        let link = self
            .link
            .as_ref()
            .filter(|l| l.ready)
            .ok_or(Error::NotReady)?;
        let mut h = Sha256::new();
        h.update(b"foundation-fabric/pairing-short-code/v1");
        if !self.sent_challenge {
            return Err(Error::NotReady);
        }
        let peer = self.peer_nonce.ok_or(Error::NotReady)?;
        h.update(link.transcript);
        if link.local_public < link.peer_public {
            h.update(link.pairing_nonce);
            h.update(peer);
        } else {
            h.update(peer);
            h.update(link.pairing_nonce);
        }
        let hash = h.finalize();
        Ok(u32::from_be_bytes(hash[..4].try_into().map_err(|_| Error::Invalid)?) % 1000000)
    }
    pub fn hello(&self, out: &mut [u8]) -> Result<usize, Error> {
        let l = self.link.as_ref().ok_or(Error::NotReady)?;
        if out.len() < HELLO_BYTES {
            return Err(Error::TooLarge);
        }
        out[..HELLO_BYTES].copy_from_slice(l.hello());
        Ok(HELLO_BYTES)
    }
    pub fn receive_hello(&mut self, hello: &[u8], now: u64) -> Result<(), Error> {
        self.check(now)?;
        if hello.len() != HELLO_BYTES {
            return Err(Error::Invalid);
        }
        if self.phase != PairingPhase::WaitingHello {
            return Err(Error::Stale);
        }
        let peer: [u8; 32] = hello[..32].try_into().map_err(|_| Error::Invalid)?;
        if self.expected.is_some_and(|p| p != peer) {
            self.reject();
            return Err(Error::Unauthorized);
        }
        if self.link.is_none() {
            self.link = Some(DeviceLink::new(&self.seed, &peer, &self.entropy)?);
        }
        let result = self.link.as_mut().ok_or(Error::NotReady)?.establish(hello);
        if result.is_err() {
            self.reject();
            return result;
        }
        self.seed.zeroize();
        self.entropy.zeroize();
        self.retry_len = 0;
        self.phase = PairingPhase::WaitingHello;
        Ok(())
    }
    /// Host invokes only after real local user confirmation (or entering the
    /// matching displayed code). Wrong code/rejection aborts this attempt. A
    /// physical-presence/rate-limit policy belongs to the existing pairing host.
    pub fn confirm(&mut self, displayed_code: u32, accepted: bool, now: u64) -> Result<(), Error> {
        self.check(now)?;
        if self.phase != PairingPhase::AwaitingUser {
            return Err(Error::Stale);
        }
        if !accepted || self.code()? != displayed_code {
            self.reject();
            return Err(Error::Unauthorized);
        }
        self.local_confirm = true;
        self.phase = PairingPhase::AwaitingPeer;
        Ok(())
    }
    /// Encrypted PairingConfirm / PairingComplete bind the whole signed key
    /// exchange. Persistence is attempted only after BOTH confirmations; false
    /// means durable storage failed and must never become a completed pairing.
    pub fn next(
        &mut self,
        now: u64,
        out: &mut [u8],
        persist: impl Fn(&[u8; 32], &[u8; 32]) -> bool,
    ) -> Result<usize, Error> {
        self.check(now)?;
        let link = self
            .link
            .as_mut()
            .filter(|l| l.ready)
            .ok_or(Error::NotReady)?;
        if !self.sent_challenge {
            let mut payload = [0u8; 33];
            payload[0] = 3;
            payload[1..].copy_from_slice(&link.pairing_nonce);
            let n = link.seal(0, &payload, out)?;
            self.sent_challenge = true;
            self.retry[..n].copy_from_slice(&out[..n]);
            self.retry_len = n;
            self.retry_at = now.saturating_add(1000);
            if self.peer_nonce.is_some() {
                self.phase = PairingPhase::AwaitingUser;
            }
            return Ok(n);
        }
        let op = if self.local_confirm && !self.sent_confirm {
            self.sent_confirm = true;
            1
        } else if self.local_confirm && self.remote_confirm && !self.sent_complete {
            self.phase = PairingPhase::Persisting;
            let id: [u8; 32] = Sha256::digest(link.peer_public).into();
            if !persist(&link.peer_public, &id) {
                self.reject();
                return Err(Error::Unauthorized);
            }
            self.persisted = true;
            self.sent_complete = true;
            2
        } else {
            0
        };
        if op != 0 {
            let mut payload = [0; 33];
            payload[0] = op;
            payload[1..].copy_from_slice(&link.transcript);
            let n = link.seal(0, &payload, out)?;
            self.retry[..n].copy_from_slice(&out[..n]);
            self.retry_len = n;
            self.retry_at = now.saturating_add(1000);
            if self.persisted && self.remote_complete {
                self.phase = PairingPhase::Complete;
            }
            Ok(n)
        } else if self.retry_len > 0
            && (self.repeat_due || (now >= self.retry_at && self.phase != PairingPhase::Complete))
        {
            if out.len() < self.retry_len {
                return Err(Error::TooLarge);
            }
            out[..self.retry_len].copy_from_slice(&self.retry[..self.retry_len]);
            self.retry_at = now.saturating_add(1000);
            self.repeat_due = false;
            Ok(self.retry_len)
        } else {
            Ok(0)
        }
    }
    pub fn receive(&mut self, record: &mut [u8], now: u64) -> Result<(), Error> {
        self.check(now)?;
        let link = self
            .link
            .as_mut()
            .filter(|l| l.ready)
            .ok_or(Error::NotReady)?;
        let (kind, n) = match link.open(record) {
            Ok(v) => v,
            Err(Error::Duplicate) => {
                // A lost Complete is recovered when peer retries Confirm/Complete.
                self.retry_at = now;
                self.repeat_due = true;
                return Ok(());
            }
            Err(e) => return Err(e),
        };
        if kind != 0 || n != 33 {
            return Err(Error::Unauthorized);
        }
        if record[9] == 3 {
            let nonce: [u8; 32] = record[10..42].try_into().map_err(|_| Error::Invalid)?;
            if Sha256::digest(nonce)[..] != link.peer_nonce_commit {
                self.reject();
                return Err(Error::Unauthorized);
            }
            self.peer_nonce = Some(nonce);
            if self.sent_challenge {
                self.phase = PairingPhase::AwaitingUser;
            }
            record[9..42].zeroize();
            return Ok(());
        }
        if self.peer_nonce.is_none() || !self.sent_challenge || record[10..42] != link.transcript {
            return Err(Error::Unauthorized);
        }
        match record[9] {
            1 => self.remote_confirm = true,
            2 => {
                if !self.local_confirm {
                    return Err(Error::Unauthorized);
                }
                self.remote_confirm = true;
                self.remote_complete = true;
                if self.persisted {
                    self.phase = PairingPhase::Complete;
                }
            }
            _ => return Err(Error::Unsupported),
        }
        record[9..42].zeroize();
        Ok(())
    }
}
impl Drop for Pairing {
    fn drop(&mut self) {
        self.seed.zeroize();
        self.entropy.zeroize();
        self.retry.zeroize();
    }
}
