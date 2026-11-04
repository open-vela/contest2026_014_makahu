//! Two-member, one ReliableMessages-channel embedded Session profile.
//! This is Fabric Session control (no provisioning/application fields). The host
//! policy callback must bind both participants/ports to registered principals and
//! approve the exact plan fingerprint; a peer key by itself is never a grant.
use super::*;

pub const CONTEXT_BYTES: usize = 140;
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ChannelContext(pub [u8; CONTEXT_BYTES]);
impl ChannelContext {
    pub fn fingerprint(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        hash.update(b"foundation-fabric/portable-session-plan/v1");
        hash.update(&self.0[..104]);
        hash.update(&self.0[136..140]);
        hash.finalize().into()
    }
    pub fn finalize(&mut self) {
        let hash = self.fingerprint();
        self.0[104..136].copy_from_slice(&hash);
    }
    pub fn validate(&self) -> Result<(), Error> {
        let b = &self.0;
        if b[104..136] != self.fingerprint() {
            return Err(Error::Invalid);
        }
        let epoch = u64::from_be_bytes(b[16..24].try_into().map_err(|_| Error::Invalid)?);
        let max = u32::from_be_bytes(b[136..140].try_into().map_err(|_| Error::Invalid)?) as usize;
        if epoch != 1
            || max == 0
            || max > MAX_PAYLOAD - 84
            || b[40..56] == b[56..72]
            || b[..16] == [0; 16]
            || b[24..40] == [0; 16]
        {
            return Err(Error::Invalid);
        }
        Ok(())
    }
    pub fn max_payload(&self) -> usize {
        u32::from_be_bytes(self.0[136..140].try_into().unwrap_or([0; 4])) as usize
    }
    fn envelope(
        &self,
        initiator: bool,
        sending: bool,
        payload: &[u8],
        seq: u64,
        out: &mut [u8],
    ) -> Result<usize, Error> {
        if payload.len() > self.max_payload() || out.len() < 84 + payload.len() {
            return Err(Error::TooLarge);
        }
        out[..40].copy_from_slice(&self.0[..40]);
        let from_initiator = initiator == sending;
        let (sender, dest) = if from_initiator {
            (40..56, 56..72)
        } else {
            (56..72, 40..56)
        };
        out[40..56].copy_from_slice(&self.0[sender]);
        out[56..72].copy_from_slice(&self.0[dest]);
        out[72..80].copy_from_slice(&seq.to_be_bytes());
        out[80..84].copy_from_slice(&(payload.len() as u32).to_be_bytes());
        out[84..84 + payload.len()].copy_from_slice(payload);
        Ok(84 + payload.len())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum Phase {
    Idle,
    Proposed,
    Accepted,
    Preparing,
    Ready,
    Committing,
    Active,
    Suspended,
}

/// `authorize` is called for Open(0), Send(1), Receive(2), including at commit and
/// every app operation. It must recheck expiry/revocation and live app ownership.
pub type Authorize = fn(&ChannelContext, u8) -> bool;

pub struct MessageSession {
    link: DeviceLink,
    context: Option<ChannelContext>,
    phase: Phase,
    initiator: bool,
    app_sequence: u64,
    received_app_sequence: u64,
    // Exactly one outstanding reliable app message, no unbounded queues.
    waiting_receipt: Option<u64>,
    last_response: [u8; CONTEXT_BYTES + 2 + RECORD_OVERHEAD],
    last_response_len: usize,
    last_response_to: u64,
    pending: [u8; MAX_RECORD],
    pending_len: usize,
    retry_at: u64,
    expires_at: u64,
    retries: u8,
}
impl MessageSession {
    pub fn new(link: DeviceLink) -> Self {
        Self {
            link,
            context: None,
            phase: Phase::Idle,
            initiator: false,
            app_sequence: 0,
            received_app_sequence: 0,
            waiting_receipt: None,
            last_response: [0; CONTEXT_BYTES + 2 + RECORD_OVERHEAD],
            last_response_len: 0,
            last_response_to: 0,
            pending: [0; MAX_RECORD],
            pending_len: 0,
            retry_at: 0,
            expires_at: 0,
            retries: 0,
        }
    }
    pub fn phase(&self) -> Phase {
        self.phase
    }
    pub fn context(&self) -> Option<&ChannelContext> {
        self.context.as_ref()
    }
    pub fn hello(&self) -> &[u8; HELLO_BYTES] {
        self.link.hello()
    }
    pub fn establish(&mut self, peer: &[u8]) -> Result<(), Error> {
        self.link.establish(peer)
    }
    pub fn is_authorized(
        &self,
        action: u8,
        authorize: impl Fn(&ChannelContext, u8) -> bool,
    ) -> bool {
        self.phase == Phase::Active && self.context.as_ref().is_some_and(|c| authorize(c, action))
    }
    pub fn propose(
        &mut self,
        context: ChannelContext,
        now: u64,
        out: &mut [u8],
        authorize: impl Fn(&ChannelContext, u8) -> bool,
    ) -> Result<usize, Error> {
        if self.phase != Phase::Idle {
            return Err(Error::Stale);
        }
        context.validate()?;
        if context.max_payload() > self.link.negotiated_app_bytes() {
            return Err(Error::TooLarge);
        }
        if !authorize(&context, 0) {
            return Err(Error::Unauthorized);
        }
        self.context = Some(context);
        self.initiator = true;
        self.phase = Phase::Proposed;
        self.control(1, now, out)
    }
    fn queue(
        &mut self,
        kind: u8,
        payload: &[u8],
        now: u64,
        out: &mut [u8],
    ) -> Result<usize, Error> {
        let n = self.link.seal(kind, payload, out)?;
        self.pending[..n].copy_from_slice(&out[..n]);
        self.pending_len = n;
        let retry_interval = 1000u64.saturating_add((n.div_ceil(15) as u64).saturating_mul(50));
        self.retry_at = now.saturating_add(retry_interval);
        self.expires_at = now.saturating_add(retry_interval.saturating_mul(6).max(10000));
        self.retries = 0;
        Ok(n)
    }
    fn control(&mut self, op: u8, now: u64, out: &mut [u8]) -> Result<usize, Error> {
        let mut payload = [0u8; CONTEXT_BYTES + 2];
        payload[0] = 1;
        payload[1] = op;
        payload[2..].copy_from_slice(&self.context.as_ref().ok_or(Error::NotReady)?.0);
        self.queue(0, &payload, now, out)
    }
    pub fn send(
        &mut self,
        payload: &[u8],
        now: u64,
        out: &mut [u8],
        authorize: impl Fn(&ChannelContext, u8) -> bool,
    ) -> Result<usize, Error> {
        if payload.is_empty() {
            return Err(Error::Invalid);
        }
        if !self.is_authorized(1, &authorize) {
            return Err(Error::Unauthorized);
        }
        if self.waiting_receipt.is_some() {
            return Err(Error::Busy);
        }
        let context = self.context.as_ref().ok_or(Error::NotReady)?;
        let sequence = self.app_sequence.checked_add(1).ok_or(Error::Stale)?;
        let mut envelope = [0u8; MAX_PAYLOAD];
        let n = context.envelope(self.initiator, true, payload, sequence, &mut envelope)?;
        let n = self.queue(1, &envelope[..n], now, out)?;
        envelope.zeroize();
        self.app_sequence = sequence;
        self.waiting_receipt = Some(sequence);
        Ok(n)
    }
    /// `reply` is control/receipt output; `application` receives only authorized
    /// complete app payload. The host enqueues this bounded output to its app
    /// executor BEFORE emitting the receipt. A receipt is delivery, not execution.
    pub fn receive(
        &mut self,
        record: &mut [u8],
        now: u64,
        reply: &mut [u8],
        application: &mut [u8],
        authorize: impl Fn(&ChannelContext, u8) -> bool,
    ) -> Result<(usize, usize), Error> {
        if record.len() < RECORD_OVERHEAD {
            return Err(Error::Invalid);
        }
        let wire_seq = u64::from_be_bytes(record[1..9].try_into().map_err(|_| Error::Invalid)?);
        let (kind, n) = match self.link.open(record) {
            Ok(v) => v,
            Err(Error::Duplicate) => {
                if wire_seq == self.last_response_to && reply.len() >= self.last_response_len {
                    reply[..self.last_response_len]
                        .copy_from_slice(&self.last_response[..self.last_response_len]);
                    return Ok((self.last_response_len, 0));
                }
                return Err(Error::Duplicate);
            }
            Err(e) => return Err(e),
        };
        let payload = &record[9..9 + n];
        let (reply_len, app_len) = match kind {
            0 => {
                if n != CONTEXT_BYTES + 2 || payload[0] != 1 {
                    return Err(Error::Unsupported);
                }
                let context = ChannelContext(payload[2..].try_into().map_err(|_| Error::Invalid)?);
                context.validate()?;
                if context.max_payload() > self.link.negotiated_app_bytes() {
                    return Err(Error::TooLarge);
                }
                if !authorize(&context, 0) {
                    self.phase = Phase::Suspended;
                    return Err(Error::Unauthorized);
                }
                let op = payload[1];
                if op == 1 && self.phase == Phase::Idle {
                    self.context = Some(context.clone());
                    self.initiator = false;
                }
                if self.context.as_ref() != Some(&context) {
                    return Err(Error::Stale);
                }
                let next = match (self.phase, op, self.initiator) {
                    (Phase::Idle, 1, false) => {
                        self.phase = Phase::Accepted;
                        2
                    }
                    (Phase::Proposed, 2, true) => {
                        self.phase = Phase::Preparing;
                        3
                    }
                    (Phase::Accepted, 3, false) => {
                        self.phase = Phase::Ready;
                        4
                    }
                    (Phase::Preparing, 4, true) => {
                        self.phase = Phase::Committing;
                        5
                    }
                    (Phase::Ready, 5, false) => {
                        self.phase = Phase::Active;
                        6
                    }
                    (Phase::Committing, 6, true) => {
                        self.phase = Phase::Active;
                        0
                    }
                    _ => return Err(Error::Stale),
                };
                self.pending_len = 0;
                if next == 0 {
                    (0, 0)
                } else {
                    let length = self.control(next, now, reply)?;
                    // Final Committed response is cached for retransmitted
                    // Commit, but needs no further receipt/retry of its own.
                    if next == 6 {
                        self.pending_len = 0;
                    }
                    (length, 0)
                }
            }
            1 => {
                if !self.is_authorized(2, &authorize) {
                    self.phase = Phase::Suspended;
                    return Err(Error::Unauthorized);
                }
                if n < 84 {
                    return Err(Error::Invalid);
                }
                let context = self.context.as_ref().ok_or(Error::NotReady)?;
                let seq =
                    u64::from_be_bytes(payload[72..80].try_into().map_err(|_| Error::Invalid)?);
                let size =
                    u32::from_be_bytes(payload[80..84].try_into().map_err(|_| Error::Invalid)?)
                        as usize;
                if size == 0
                    || n != 84 + size
                    || size > context.max_payload()
                    || application.len() < size
                {
                    return Err(Error::TooLarge);
                }
                let mut expected = [0u8; 84];
                context.envelope(self.initiator, false, &[], seq, &mut expected)?;
                if payload[..72] != expected[..72] || seq == 0 {
                    return Err(Error::Unauthorized);
                }
                if seq <= self.received_app_sequence {
                    return Err(Error::Duplicate);
                }
                application[..size].copy_from_slice(&payload[84..]);
                self.received_app_sequence = seq;
                (self.link.seal(2, &seq.to_be_bytes(), reply)?, size)
            }
            2 => {
                if n != 8 {
                    return Err(Error::Invalid);
                }
                let seq = u64::from_be_bytes(payload.try_into().map_err(|_| Error::Invalid)?);
                if self.waiting_receipt != Some(seq) {
                    return Err(Error::Stale);
                }
                self.waiting_receipt = None;
                self.pending_len = 0;
                (0, 0)
            }
            _ => return Err(Error::Unsupported),
        };
        if reply_len > 0 {
            self.last_response
                .get_mut(..reply_len)
                .ok_or(Error::TooLarge)?
                .copy_from_slice(&reply[..reply_len]);
            self.last_response_len = reply_len;
            self.last_response_to = wire_seq;
        }
        record[9..9 + n].zeroize();
        Ok((reply_len, app_len))
    }
    /// Retry exact ciphertext (never reseal with a reused nonce). On exhausted
    /// timeout, suspend and require a fresh link + policy admission; do not
    /// silently claim delivery or switch carriers with in-flight requests.
    pub fn tick(&mut self, now: u64, out: &mut [u8]) -> Result<usize, Error> {
        if self.pending_len == 0 || now < self.retry_at {
            return Ok(0);
        }
        if now >= self.expires_at || self.retries >= 5 {
            self.phase = Phase::Suspended;
            self.pending_len = 0;
            return Err(Error::Stale);
        }
        if out.len() < self.pending_len {
            return Err(Error::TooLarge);
        }
        out[..self.pending_len].copy_from_slice(&self.pending[..self.pending_len]);
        self.retry_at = now.saturating_add(1000 + (self.pending_len.div_ceil(15) as u64) * 50);
        self.retries += 1;
        Ok(self.pending_len)
    }
}
impl Drop for MessageSession {
    fn drop(&mut self) {
        self.pending.zeroize();
        self.last_response.zeroize();
    }
}
