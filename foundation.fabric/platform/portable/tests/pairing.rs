use ed25519_dalek::SigningKey;
use fabric_portable::{Error, HELLO_BYTES, pairing::*};
use std::cell::Cell;
fn pair() -> (Pairing, Pairing) {
    let pub_b = SigningKey::from_bytes(&[2; 32]).verifying_key().to_bytes();
    let mut a = Pairing::request([1; 32], [3; 32], Some(pub_b), 0).unwrap();
    let mut b = Pairing::request([2; 32], [4; 32], None, 0).unwrap();
    let mut h = [0; HELLO_BYTES];
    a.hello(&mut h).unwrap();
    b.receive_hello(&h, 1).unwrap();
    b.hello(&mut h).unwrap();
    a.receive_hello(&h, 2).unwrap();
    let mut wire = [0u8; HELLO_BYTES];
    assert_eq!(a.code(), Err(Error::NotReady));
    let n = a.next(2, &mut wire, |_, _| false).unwrap();
    b.receive(&mut wire[..n], 2).unwrap();
    let n = b.next(2, &mut wire, |_, _| false).unwrap();
    a.receive(&mut wire[..n], 2).unwrap();
    assert_eq!(a.code(), b.code());
    (a, b)
}
#[test]
fn first_pair_requires_both_user_confirmations_and_durable_storage() {
    let (mut a, mut b) = pair();
    let writes = Cell::new(0);
    let persist = |_: &[u8; 32], _: &[u8; 32]| {
        writes.set(writes.get() + 1);
        true
    };
    let mut record = [0; HELLO_BYTES];
    assert_eq!(a.next(3, &mut record, persist).unwrap(), 0);
    assert_eq!(writes.get(), 0);
    a.confirm(a.code().unwrap(), true, 4).unwrap();
    let n = a.next(5, &mut record, persist).unwrap();
    b.receive(&mut record[..n], 6).unwrap();
    assert_eq!(b.next(7, &mut record, persist).unwrap(), 0);
    assert_eq!(writes.get(), 0);
    b.confirm(b.code().unwrap(), true, 8).unwrap();
    let n = b.next(9, &mut record, persist).unwrap();
    a.receive(&mut record[..n], 10).unwrap();
    let n = a.next(11, &mut record, persist).unwrap();
    assert_eq!(writes.get(), 1);
    b.receive(&mut record[..n], 12).unwrap();
    let n = b.next(13, &mut record, persist).unwrap();
    assert_eq!(writes.get(), 2);
    a.receive(&mut record[..n], 14).unwrap();
    assert_eq!(a.phase(), PairingPhase::Complete);
    assert_eq!(b.phase(), PairingPhase::Complete);
}
#[test]
fn rejection_wrong_code_timeout_and_storage_failure_never_pair() {
    let (mut a, _) = pair();
    assert_eq!(
        a.confirm((a.code().unwrap() + 1) % 1000000, true, 3),
        Err(Error::Unauthorized)
    );
    assert_eq!(a.phase(), PairingPhase::Rejected);
    let (mut a, _) = pair();
    assert_eq!(
        a.confirm(a.code().unwrap(), false, 3),
        Err(Error::Unauthorized)
    );
    let (mut a, _) = pair();
    assert_eq!(
        a.confirm(a.code().unwrap(), true, 120000),
        Err(Error::Stale)
    );
    let (mut a, mut b) = pair();
    a.confirm(a.code().unwrap(), true, 3).unwrap();
    b.confirm(b.code().unwrap(), true, 3).unwrap();
    let mut wire = [0; HELLO_BYTES];
    let n = a.next(4, &mut wire, |_, _| true).unwrap();
    b.receive(&mut wire[..n], 5).unwrap();
    let n = b.next(6, &mut wire, |_, _| true).unwrap();
    a.receive(&mut wire[..n], 7).unwrap();
    assert_eq!(a.next(8, &mut wire, |_, _| false), Err(Error::Unauthorized));
    assert_eq!(a.phase(), PairingPhase::Rejected);
}
#[test]
fn lost_confirm_and_final_complete_recover_without_second_persistence() {
    let (mut a, mut b) = pair();
    a.confirm(a.code().unwrap(), true, 3).unwrap();
    b.confirm(b.code().unwrap(), true, 3).unwrap();
    let writes_a = Cell::new(0);
    let writes_b = Cell::new(0);
    let pa = |_: &[u8; 32], _: &[u8; 32]| {
        writes_a.set(writes_a.get() + 1);
        true
    };
    let pb = |_: &[u8; 32], _: &[u8; 32]| {
        writes_b.set(writes_b.get() + 1);
        true
    };
    let mut wire = [0; HELLO_BYTES];
    let n = a.next(4, &mut wire, pa).unwrap();
    b.receive(&mut wire[..n], 5).unwrap();
    let _lost = b.next(6, &mut wire, pb).unwrap(); // B Confirm lost.
    let n = b.next(7, &mut wire, pb).unwrap();
    a.receive(&mut wire[..n], 8).unwrap(); // B Complete confirms implicitly.
    let _lost = a.next(9, &mut wire, pa).unwrap(); // A final Complete lost.
    assert_eq!(a.phase(), PairingPhase::Complete);
    let n = b.next(1008, &mut wire, pb).unwrap();
    a.receive(&mut wire[..n], 1009).unwrap();
    let n = a.next(1010, &mut wire, pa).unwrap();
    assert!(n > 0);
    b.receive(&mut wire[..n], 1011).unwrap();
    assert_eq!(b.phase(), PairingPhase::Complete);
    assert_eq!(writes_a.get(), 1);
    assert_eq!(writes_b.get(), 1);
}
