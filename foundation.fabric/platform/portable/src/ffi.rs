//! C ABI: caller owns aligned storage, synchronization, entropy and trust policy.
use super::*;
use core::{mem, ptr, slice};

#[unsafe(no_mangle)]
pub extern "C" fn fabric_ble_link_size() -> usize {
    mem::size_of::<DeviceLink>()
}
#[unsafe(no_mangle)]
pub extern "C" fn fabric_ble_link_align() -> usize {
    mem::align_of::<DeviceLink>()
}

unsafe fn link<'a>(state: *mut DeviceLink) -> Result<&'a mut DeviceLink, Error> {
    if state.is_null() || (state as usize) % mem::align_of::<DeviceLink>() != 0 {
        return Err(Error::Invalid);
    }
    Ok(unsafe { &mut *state })
}
unsafe fn array32<'a>(p: *const u8) -> Result<&'a [u8; 32], Error> {
    if p.is_null() {
        return Err(Error::Invalid);
    }
    Ok(unsafe { &*p.cast::<[u8; 32]>() })
}
unsafe fn input<'a>(p: *const u8, n: usize) -> Result<&'a [u8], Error> {
    if p.is_null() || n > MAX_RECORD {
        return Err(Error::Invalid);
    }
    Ok(unsafe { slice::from_raw_parts(p, n) })
}
unsafe fn output<'a>(p: *mut u8, n: usize) -> Result<&'a mut [u8], Error> {
    if p.is_null() || n > MAX_RECORD {
        return Err(Error::Invalid);
    }
    Ok(unsafe { slice::from_raw_parts_mut(p, n) })
}
fn result(value: Result<usize, Error>) -> i32 {
    match value {
        Ok(n) => n as i32,
        Err(e) => e as i32,
    }
}

/// All buffers must be valid, nonoverlapping, exclusively owned for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_init(
    state: *mut DeviceLink,
    capacity: usize,
    seed: *const u8,
    peer: *const u8,
    entropy: *const u8,
) -> i32 {
    let run = || -> Result<usize, Error> {
        if capacity < mem::size_of::<DeviceLink>()
            || state.is_null()
            || (state as usize) % mem::align_of::<DeviceLink>() != 0
        {
            return Err(Error::Invalid);
        }
        let value = DeviceLink::new(
            unsafe { array32(seed)? },
            unsafe { array32(peer)? },
            unsafe { array32(entropy)? },
        )?;
        unsafe { ptr::write(state, value) };
        Ok(0)
    };
    result(run())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_hello(
    state: *mut DeviceLink,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result((|| {
        let state = unsafe { link(state)? };
        let out = unsafe { output(out, capacity)? };
        if out.len() < HELLO_BYTES {
            return Err(Error::TooLarge);
        }
        out[..HELLO_BYTES].copy_from_slice(state.hello());
        Ok(HELLO_BYTES)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_establish(
    state: *mut DeviceLink,
    hello: *const u8,
    length: usize,
) -> i32 {
    result((|| {
        unsafe { link(state)? }.establish(unsafe { input(hello, length)? })?;
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_seal(
    state: *mut DeviceLink,
    kind: u8,
    payload: *const u8,
    length: usize,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result(unsafe { link(state) }.and_then(|state| {
        state.seal(kind, unsafe { input(payload, length)? }, unsafe {
            output(out, capacity)?
        })
    }))
}
/// Decrypts in place. Returns payload length; kind is record[0], payload at +9.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_open(
    state: *mut DeviceLink,
    record: *mut u8,
    length: usize,
) -> i32 {
    result(unsafe { link(state) }.and_then(|state| {
        state
            .open(unsafe { output(record, length)? })
            .map(|(_, n)| n)
    }))
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_link_destroy(state: *mut DeviceLink) {
    if let Ok(state) = unsafe { link(state) } {
        unsafe { ptr::drop_in_place(state) };
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_fragment(
    record: *const u8,
    length: usize,
    offset: usize,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result((|| {
        fragment(unsafe { input(record, length)? }, offset, unsafe {
            output(out, capacity)?
        })
    })())
}

use crate::session::{CONTEXT_BYTES, ChannelContext, MessageSession};
use core::ffi::c_void;
pub type PolicyCallback = Option<unsafe extern "C" fn(*mut c_void, *const u8, u8) -> i32>;
fn policy(callback: PolicyCallback, ctx: *mut c_void, c: &ChannelContext, action: u8) -> bool {
    callback.is_some_and(|f| unsafe { f(ctx, c.0.as_ptr(), action) } == 1)
}
unsafe fn session<'a>(p: *mut MessageSession) -> Result<&'a mut MessageSession, Error> {
    if p.is_null() || (p as usize) % mem::align_of::<MessageSession>() != 0 {
        return Err(Error::Invalid);
    }
    Ok(unsafe { &mut *p })
}
#[unsafe(no_mangle)]
pub extern "C" fn fabric_ble_session_size() -> usize {
    mem::size_of::<MessageSession>()
}
#[unsafe(no_mangle)]
pub extern "C" fn fabric_ble_session_align() -> usize {
    mem::align_of::<MessageSession>()
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_init(
    p: *mut MessageSession,
    capacity: usize,
    seed: *const u8,
    peer: *const u8,
    entropy: *const u8,
) -> i32 {
    result((|| {
        if capacity < mem::size_of::<MessageSession>()
            || p.is_null()
            || (p as usize) % mem::align_of::<MessageSession>() != 0
        {
            return Err(Error::Invalid);
        }
        let link = DeviceLink::new(
            unsafe { array32(seed)? },
            unsafe { array32(peer)? },
            unsafe { array32(entropy)? },
        )?;
        unsafe { ptr::write(p, MessageSession::new(link)) };
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_hello(
    p: *mut MessageSession,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result((|| {
        let state = unsafe { session(p)? };
        let out = unsafe { output(out, capacity)? };
        if out.len() < HELLO_BYTES {
            return Err(Error::TooLarge);
        }
        out[..HELLO_BYTES].copy_from_slice(state.hello());
        Ok(HELLO_BYTES)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_establish(
    p: *mut MessageSession,
    hello: *const u8,
    length: usize,
) -> i32 {
    result((|| {
        unsafe { session(p)? }.establish(unsafe { input(hello, length)? })?;
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_propose(
    p: *mut MessageSession,
    context: *const u8,
    now: u64,
    out: *mut u8,
    capacity: usize,
    auth: PolicyCallback,
    ctx: *mut c_void,
) -> i32 {
    result((|| {
        let c = ChannelContext(
            unsafe { input(context, CONTEXT_BYTES)? }
                .try_into()
                .map_err(|_| Error::Invalid)?,
        );
        unsafe { session(p)? }.propose(c, now, unsafe { output(out, capacity)? }, |c, a| {
            policy(auth, ctx, c, a)
        })
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_send(
    p: *mut MessageSession,
    payload: *const u8,
    length: usize,
    now: u64,
    out: *mut u8,
    capacity: usize,
    auth: PolicyCallback,
    ctx: *mut c_void,
) -> i32 {
    result((|| {
        unsafe { session(p)? }.send(
            unsafe { input(payload, length)? },
            now,
            unsafe { output(out, capacity)? },
            |c, a| policy(auth, ctx, c, a),
        )
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_receive(
    p: *mut MessageSession,
    record: *mut u8,
    length: usize,
    now: u64,
    reply: *mut u8,
    reply_capacity: usize,
    app: *mut u8,
    app_capacity: usize,
    app_length: *mut usize,
    auth: PolicyCallback,
    ctx: *mut c_void,
) -> i32 {
    result((|| {
        if app_length.is_null() {
            return Err(Error::Invalid);
        }
        unsafe { *app_length = 0 };
        let (r, n) = unsafe { session(p)? }.receive(
            unsafe { output(record, length)? },
            now,
            unsafe { output(reply, reply_capacity)? },
            unsafe { output(app, app_capacity)? },
            |c, a| policy(auth, ctx, c, a),
        )?;
        unsafe { *app_length = n };
        Ok(r)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_authorized(
    p: *mut MessageSession,
    action: u8,
    auth: PolicyCallback,
    ctx: *mut c_void,
) -> i32 {
    match unsafe { session(p) } {
        Ok(s) => i32::from(s.is_authorized(action, |c, a| policy(auth, ctx, c, a))),
        Err(e) => e as i32,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_phase(p: *mut MessageSession) -> i32 {
    match unsafe { session(p) } {
        Ok(s) => s.phase() as i32,
        Err(e) => e as i32,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_tick(
    p: *mut MessageSession,
    now: u64,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result((|| {
        unsafe { session(p)? }.tick(now, unsafe { output(out, capacity)? })
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_destroy(p: *mut MessageSession) {
    if let Ok(s) = unsafe { session(p) } {
        unsafe { ptr::drop_in_place(s) };
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_identity_public(
    seed: *const u8,
    public: *mut u8,
    device_id: *mut u8,
) -> i32 {
    result((|| {
        let key = SigningKey::from_bytes(unsafe { array32(seed)? });
        let public_bytes = key.verifying_key().to_bytes();
        unsafe { output(public, 32)? }.copy_from_slice(&public_bytes);
        unsafe { output(device_id, 32)? }.copy_from_slice(&Sha256::digest(public_bytes));
        Ok(0)
    })())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_device_id(public: *const u8, id: *mut u8) -> i32 {
    result((|| {
        let public = unsafe { array32(public)? };
        unsafe { output(id, 32)? }.copy_from_slice(&Sha256::digest(public));
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_session_max_payload(p: *mut MessageSession) -> i32 {
    match unsafe { session(p) } {
        Ok(s) => s.context().map(|c| c.max_payload() as i32).unwrap_or(0),
        Err(e) => e as i32,
    }
}

use crate::pairing::Pairing;
pub type PersistPeerCallback =
    Option<unsafe extern "C" fn(*mut c_void, *const u8, *const u8) -> i32>;
unsafe fn pairing<'a>(p: *mut Pairing) -> Result<&'a mut Pairing, Error> {
    if p.is_null() || (p as usize) % mem::align_of::<Pairing>() != 0 {
        return Err(Error::Invalid);
    }
    Ok(unsafe { &mut *p })
}
#[unsafe(no_mangle)]
pub extern "C" fn fabric_pairing_size() -> usize {
    mem::size_of::<Pairing>()
}
#[unsafe(no_mangle)]
pub extern "C" fn fabric_pairing_align() -> usize {
    mem::align_of::<Pairing>()
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_request_pairing(
    p: *mut Pairing,
    capacity: usize,
    seed: *const u8,
    entropy: *const u8,
    expected: *const u8,
    now: u64,
) -> i32 {
    result((|| {
        if capacity < mem::size_of::<Pairing>()
            || p.is_null()
            || (p as usize) % mem::align_of::<Pairing>() != 0
        {
            return Err(Error::Invalid);
        }
        let expected = if expected.is_null() {
            None
        } else {
            Some(*unsafe { array32(expected)? })
        };
        let state = Pairing::request(
            *unsafe { array32(seed)? },
            *unsafe { array32(entropy)? },
            expected,
            now,
        )?;
        unsafe { ptr::write(p, state) };
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_hello(
    p: *mut Pairing,
    out: *mut u8,
    capacity: usize,
) -> i32 {
    result((|| {
        unsafe { pairing(p)? }.hello(unsafe { output(out, capacity)? })
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_receive_hello(
    p: *mut Pairing,
    hello: *const u8,
    length: usize,
    now: u64,
) -> i32 {
    result((|| {
        unsafe { pairing(p)? }.receive_hello(unsafe { input(hello, length)? }, now)?;
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_code(p: *mut Pairing) -> i32 {
    result(unsafe { pairing(p) }.and_then(|p| p.code().map(|n| n as usize)))
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_peer(p: *mut Pairing, public: *mut u8, id: *mut u8) -> i32 {
    result((|| {
        let peer = unsafe { pairing(p)? }
            .peer_public()
            .ok_or(Error::NotReady)?;
        unsafe { output(public, 32)? }.copy_from_slice(&peer);
        unsafe { output(id, 32)? }.copy_from_slice(&Sha256::digest(peer));
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_confirm(
    p: *mut Pairing,
    code: u32,
    accepted: i32,
    now: u64,
) -> i32 {
    result((|| {
        unsafe { pairing(p)? }.confirm(code, accepted == 1, now)?;
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_complete_pairing(
    p: *mut Pairing,
    now: u64,
    out: *mut u8,
    capacity: usize,
    persist: PersistPeerCallback,
    ctx: *mut c_void,
) -> i32 {
    result((|| {
        unsafe { pairing(p)? }.next(now, unsafe { output(out, capacity)? }, |public, id| {
            persist.is_some_and(|f| unsafe { f(ctx, public.as_ptr(), id.as_ptr()) } == 1)
        })
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_receive(
    p: *mut Pairing,
    record: *mut u8,
    length: usize,
    now: u64,
) -> i32 {
    result((|| {
        unsafe { pairing(p)? }.receive(unsafe { output(record, length)? }, now)?;
        Ok(0)
    })())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_phase(p: *mut Pairing) -> i32 {
    match unsafe { pairing(p) } {
        Ok(p) => p.phase() as i32,
        Err(e) => e as i32,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_pairing_destroy(p: *mut Pairing) {
    if let Ok(p) = unsafe { pairing(p) } {
        unsafe { ptr::drop_in_place(p) };
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn fabric_ble_context_finalize(context: *mut u8) -> i32 {
    result((|| {
        let bytes = unsafe { output(context, CONTEXT_BYTES)? };
        let mut c = ChannelContext(bytes[..].try_into().map_err(|_| Error::Invalid)?);
        c.finalize();
        c.validate()?;
        bytes.copy_from_slice(&c.0);
        Ok(0)
    })())
}
