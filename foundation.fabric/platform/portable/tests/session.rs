use ed25519_dalek::SigningKey;
use fabric_portable::{DeviceLink, Error, MAX_RECORD, Reassembly, fragment, session::*};

fn pair() -> (MessageSession, MessageSession, ChannelContext) {
    let b = SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes();
    let mut a = MessageSession::new(DeviceLink::new(&[1; 32], &b, &[3; 32]).unwrap());
    let mut b = MessageSession::new(
        DeviceLink::new(&[2; 32], &a.hello()[..32].try_into().unwrap(), &[4; 32]).unwrap(),
    );
    let ah = *a.hello();
    let bh = *b.hello();
    a.establish(&bh).unwrap();
    b.establish(&ah).unwrap();
    let mut c = [0; CONTEXT_BYTES];
    c[..16].fill(1);
    c[16..24].copy_from_slice(&1u64.to_be_bytes());
    c[24..40].fill(2);
    c[40..56].fill(3);
    c[56..72].fill(4);
    c[72..88].fill(5);
    c[88..104].fill(6);
    c[104..136].fill(7);
    c[136..].copy_from_slice(&4096u32.to_be_bytes());
    let mut context = ChannelContext(c);
    context.finalize();
    (a, b, context)
}
fn allow(expected: &ChannelContext) -> impl Fn(&ChannelContext, u8) -> bool + '_ {
    move |c, _| c == expected
}
fn commit(a: &mut MessageSession, b: &mut MessageSession, c: &ChannelContext) {
    let mut wire = [0; MAX_RECORD];
    let mut reply = [0; MAX_RECORD];
    let mut app = [0; 8192];
    let mut n = a.propose(c.clone(), 0, &mut wire, allow(c)).unwrap();
    for i in 0..6 {
        let (r, p) = if i % 2 == 0 {
            b.receive(&mut wire[..n], 0, &mut reply, &mut app, allow(c))
        } else {
            a.receive(&mut wire[..n], 0, &mut reply, &mut app, allow(c))
        }
        .unwrap();
        assert_eq!(p, 0);
        wire[..r].copy_from_slice(&reply[..r]);
        n = r;
    }
    assert_eq!(a.phase(), Phase::Active);
    assert_eq!(b.phase(), Phase::Active);
}
#[test]
fn encrypted_session_app_roundtrip_retries_receipt_and_revocation() {
    let (mut a, mut b, c) = pair();
    commit(&mut a, &mut b, &c);
    let mut wire = [0; MAX_RECORD];
    let mut reply = [0; MAX_RECORD];
    let mut app = [0; 8192];
    let text = b"same application protocol: synthetic network and model configuration";
    let n = a.send(text, 1, &mut wire, allow(&c)).unwrap();
    let ciphertext = wire[..n].to_vec();
    assert!(!wire[..n].windows(text.len()).any(|w| w == text));
    let (r, p) = b
        .receive(&mut wire[..n], 2, &mut reply, &mut app, allow(&c))
        .unwrap();
    assert_eq!(&app[..p], text);
    assert!(r > 0);
    // Drop delivery receipt, retry exact ciphertext and never deliver twice.
    let retry = a.tick(5000, &mut wire).unwrap();
    assert_eq!(&wire[..retry], &ciphertext);
    let (r, p) = b
        .receive(&mut wire[..retry], 5001, &mut reply, &mut app, allow(&c))
        .unwrap();
    assert_eq!(p, 0);
    let (r, p) = a
        .receive(&mut reply[..r], 5002, &mut wire, &mut app, allow(&c))
        .unwrap();
    assert_eq!((r, p), (0, 0));
    assert_eq!(a.tick(2001, &mut wire).unwrap(), 0);
    assert_eq!(
        a.send(b"revoked", 2002, &mut wire, |_, _| false),
        Err(Error::Unauthorized)
    );
    assert!(!a.is_authorized(1, |_, _| false));
}
#[test]
fn missing_ready_never_commits_and_timeout_suspends() {
    let (mut a, mut b, c) = pair();
    let mut wire = [0; MAX_RECORD];
    let mut reply = [0; MAX_RECORD];
    let mut app = [0; 8192];
    let n = a.propose(c.clone(), 0, &mut wire, allow(&c)).unwrap();
    let (r, _) = b
        .receive(&mut wire[..n], 0, &mut reply, &mut app, allow(&c))
        .unwrap();
    let (n, _) = a
        .receive(&mut reply[..r], 0, &mut wire, &mut app, allow(&c))
        .unwrap();
    let (_r, _) = b
        .receive(&mut wire[..n], 0, &mut reply, &mut app, allow(&c))
        .unwrap();
    // READY lost forever; initiator has no active app grant.
    assert_eq!(a.phase(), Phase::Preparing);
    assert!(!a.is_authorized(1, allow(&c)));
    assert_eq!(a.tick(10001, &mut wire), Err(Error::Stale));
    assert_eq!(a.phase(), Phase::Suspended);
}
#[test]
fn mtu20_reassembly_duplicates_and_conflicts() {
    let record = [42; 333];
    let mut r = Reassembly::default();
    let mut offset = 0;
    let mut f = [0; 20];
    while offset < record.len() {
        let n = fragment(&record, offset, &mut f).unwrap();
        let done = r.feed(&f[..n]).unwrap().map(|b| b.to_vec());
        if offset == 0 {
            assert!(r.feed(&f[..n]).unwrap().is_none());
        }
        offset += n - 5;
        if offset == record.len() {
            assert_eq!(done.unwrap(), record);
        }
    }
    r.reset();
    let n = fragment(&record, 0, &mut f).unwrap();
    r.feed(&f[..n]).unwrap();
    f[6] ^= 1;
    assert_eq!(r.feed(&f[..n]), Err(Error::Invalid));
}
#[test]
fn peer_identity_tamper_fresh_link_and_context_rejection() {
    let peer = SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes();
    let mut a = DeviceLink::new(&[1; 32], &peer, &[3; 32]).unwrap();
    let mut b = DeviceLink::new(&[2; 32], &a.hello()[..32].try_into().unwrap(), &[4; 32]).unwrap();
    let mut hello = *b.hello();
    hello[99] ^= 1;
    assert_eq!(a.establish(&hello), Err(Error::Unauthorized));
    let ah = *a.hello();
    a.establish(b.hello()).unwrap();
    b.establish(&ah).unwrap();
    let mut record = [0; MAX_RECORD];
    let n = a.seal(0, b"framework controls only", &mut record).unwrap();
    record[n - 1] ^= 1;
    assert_eq!(b.open(&mut record[..n]), Err(Error::Unauthorized));
    let (mut a, mut b, c) = pair();
    let n = a.propose(c.clone(), 0, &mut record, allow(&c)).unwrap();
    assert_eq!(
        b.receive(
            &mut record[..n],
            0,
            &mut [0; MAX_RECORD],
            &mut [0; 8192],
            |_, _| false
        ),
        Err(Error::Unauthorized)
    );
    assert_eq!(b.phase(), Phase::Suspended);
}

#[test]
fn authenticated_replay_window_allows_lost_data_after_receipt() {
    let peer = SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes();
    let mut a = DeviceLink::new(&[1; 32], &peer, &[3; 32]).unwrap();
    let mut b = DeviceLink::new(&[2; 32], &a.hello()[..32].try_into().unwrap(), &[4; 32]).unwrap();
    let ah = *a.hello();
    let bh = *b.hello();
    a.establish(&bh).unwrap();
    b.establish(&ah).unwrap();
    let mut records = Vec::new();
    for index in 0..70 {
        let mut out = vec![0; MAX_RECORD];
        let len = a.seal(if index == 1 { 2 } else { 1 }, b"test", &mut out).unwrap();
        out.truncate(len);
        records.push(out);
    }
    assert_eq!(b.open(&mut records[1].clone()).unwrap().0, 2);
    assert_eq!(b.open(&mut records[0].clone()).unwrap().0, 1);
    assert_eq!(b.open(&mut records[0].clone()), Err(Error::Duplicate));
    let mut forged = records[69].clone();
    forged[9] ^= 1;
    assert_eq!(b.open(&mut forged), Err(Error::Unauthorized));
    // The forged future nonce must not advance or poison the window.
    assert_eq!(b.open(&mut records[2].clone()).unwrap().0, 1);
    assert_eq!(b.open(&mut records[69].clone()).unwrap().0, 1);
    assert_eq!(b.open(&mut records[3].clone()), Err(Error::Duplicate));
    assert_eq!(b.open(&mut records[68].clone()).unwrap().0, 1);
    assert_eq!(b.open(&mut records[68].clone()), Err(Error::Duplicate));
}
