# Fabric portable BLE profile

This is an allocation-free `no_std` core with C ABI, not the desktop Tokio runtime. C hosts allocate bounded state and serialize GATT callbacks on a worker. Android uses exactly the same C host and wire through `platform/android`. The portable profile is limited to two participants, one bidirectional **ReliableMessages** channel, initial epoch **1**, and application payloads **1..12288 bytes**. There is no BLE stream/datagram API.

An offset-zero GATT fragment with a different prefix begins a new bounded record, including when its total length matches an incomplete delayed retry. Identical prefixes retain reassembly progress. This only resynchronizes framing: complete records must still pass AEAD, replay, Session context and authorization checks; conflicting nonzero overlaps remain invalid. The C host regression covers a delayed first fragment from an acknowledged record followed by a new equal-length record.

The signed 204-byte hello explicitly selects `FBP1`, advertises max application bytes and ReliableMessages-only capability. Unknown profiles/modes are rejected. The peer minimum is enforced on Session proposal; oversized messages return `TooLarge` without truncation. This profile currently does **not** speak desktop protobuf Session controls or the original `crates/platform/src/ble.rs` prototype packet codec. Sharing an application protocol or envelope does not make those transport profiles interoperable. Reconnect/carrier changes require fresh admission and application request IDs; no seamless in-flight migration is promised.

## Identity and first pairing

Use `fabric_identity_posix_load` with real CSPRNG to load/create `fabric-device.key` (raw32, same Ed25519 seed format as `fabric-identity`). DeviceId is SHA256(public key). `trusted-devices.txt` uses the existing framework's `<DeviceId hex> trusted|pending|blocked` format. `.pub` files cache public keys; they are not independent trust authority. An existing blocked peer cannot be approved by this pairing callback. Never import old OOBE tokens or derive trust from an OS Bluetooth bond.

`fabric_pairing_host_create` is called only inside an explicitly opened, rate-limited pairing window. Initiator pins the target device public key from QR or the GATT identity locator; responder initially has no trusted phone key. `feed`/`poll` perform signed ephemeral X25519 exchange, HKDF/ChaCha20Poly1305 and encrypted Challenge reveals. Nonces are committed in signed hellos before reveal, so the six-digit SAS cannot be selected after learning the other peer's nonce. `code()` stays NotReady until the challenge exchange is complete.

Display the actual code with leading zeroes and require local user confirmation on both sides. `confirm(code,accepted)` does not persist trust by itself. The core calls `persist_peer(public,DeviceId)` only after local confirmation and authenticated remote confirmation. Return 1 only after durable private storage succeeds. Reject/wrong code/timeout/storage failure never completes pairing. After both Complete records and flushed output, reconnect with fresh entropy and the persisted trusted key for an application Session. Pairing objects have no application-message API.

The POSIX backend requires private directory 0700 and seed 0600 with correct owner. Partial/corrupt keys fail closed and are never silently replaced. Generic POSIX uses file and directory fsync plus atomic rename. Linux/Android C and Rust writers share `trusted-devices.txt.lock` using flock and reload records inside the write transaction; Rust readers reload to observe portable pairing and revocations. NuttX uses one designated executor for all trust writes. The NuttX adapter requires `CONFIG_FS_LITTLEFS_ATTR_UPDATE`, real LittleFS `statfs`, and a block driver implementing durable BIOC_FLUSH: this SDK's LittleFS rename/mkdir commits synchronously, while directory descriptors cannot be passed to file fsync. File fsync errors remain fatal. FAT, tmpfs and ARM64 semihost hostfs with synthetic 0777 attributes are not verified private backends. Do not generalize simulator LittleFS results to physical flash power-loss safety without device validation.

## Session and application integration

Fill the 140-byte context described in `fabric_ble.h`; call `fabric_ble_context_finalize` for its portable canonical plan fingerprint. The policy callback must independently bind the tuple to live local app/ability/ports, the trusted remote Fabric identity and current grants. Structural hashing, a peer key and a caller-supplied tuple are not authorization.

The Session host runs Propose/Accept/Prepare/Ready/Commit/Committed as typed framework controls. It checks the exact context at open/commit and grants at each send/receive. Application bytes use a separate message record with the common Session/epoch/channel/sender/destination/sequence envelope. Business data cannot substitute for a framework control. This profile is explicitly HubReadable over an encrypted direct device link; application EndToEnd/recipient-key-binding contracts are unsupported. Do not route credentials through a Hub relay expecting application-level E2EE.

Create the host only after pairing/trust validation. GATT callbacks post copied packets to a bounded executor. `host_poll` produces fragments via `send_packet`; a successful callback means copied to the platform queue, not app execution. `host_feed` reassembles into a bounded slot. Drain `host_take_message` and run the application's existing handler on its executor; pass an authorization callback that uses `host_authorized` plus the host's live policy. Queue the application result with `host_send`, retrying explicit Busy. Transport receipts mean delivery to the bounded local slot; the application result/request ID is the execution acknowledgement.

One outstanding app message and one inbound slot bound memory. Exact ciphertext is retransmitted after a size-aware deadline (MTU20 at 50ms/fragment plus 1s), with five retries, then Suspended. Duplicates authenticate before receiving a repeated receipt. Fragment limits, strict prefix matching and reassembly deadlines reject conflicting/unbounded input. Destroy on disconnect; never reuse entropy, keys or sequence state. State buffers are heap allocated by the C host; provision a 64KiB worker stack and measure actual high-water usage with the final app/crypto call chain.

A separate 204-byte control/receipt slot lets a full-duplex peer acknowledge an incoming message while its own data record is being fragmented. New app sends wait until that slot is drained. Incoming receipts can be processed while the single app slot awaits its consumer; they never clear or overwrite the pending app payload. Replay protection authenticates first, then admits each sequence once within a 64-record window. This permits a lost data record to be retried after a newer receipt without admitting a replay; forged records cannot advance the window, and old records outside it are rejected. Host regression covers asymmetric message sizes, one dropped data fragment, delayed consumers, both directions, and subsequent revocation.

## GATT contract

- Service: `7f6d0001-6b4e-4a30-9e8f-464142524943`
- RX, Write With Response: `7f6d0002-6b4e-4a30-9e8f-464142524943`
- TX, Notify + standard CCC2902: `7f6d0003-6b4e-4a30-9e8f-464142524943`
- Identity, Read/Read Blob: `7f6d0004-6b4e-4a30-9e8f-464142524943`, public32 || DeviceId32, locator only.

Packet budget is actual `ATT_MTU-3`, 20..512; the fragment header uses five bytes. Characteristics carry opaque framework records, never a parallel OOBE protocol. The platform must order writes/notifications and report disconnects. Missing controller, GATT capability, private storage or authorization is an explicit failure; no mock/legacy fallback.

## Build and validation

```sh
platform/portable/test-host.sh
cargo +1.94.1 build --locked --manifest-path platform/portable/Cargo.toml \
  --release --no-default-features --target aarch64-unknown-none
ANDROID_NDK_HOME=/path/to/ndk platform/android/build-native.sh /tmp/fabric-jniLibs
ANDROID_HOME=/path/to/sdk bash apps/android/gradlew -p platform/android \
  -PfabricJniLibs=/tmp/fabric-jniLibs assembleDebug
```

The no_std archive links to C/NuttX without std, Tokio or an allocator in the Rust core. C hosts need malloc/calloc/free. Compile C with the actual NuttX config/includes, not only bare-metal newlib headers. Android shared-library linking rejects unresolved symbols (`--no-undefined`); panic abort never unwinds across C.

Tests cover first pairing without pretrusted keys, both confirmations before persistence, commitment/challenge/SAS flow, loss/retry, rejection, storage failure, private modes and repeated reopen, Session controls, MTU20 fragmentation, 12KiB payload, deduplication, revocation and timeout. Host tests and cross-compilation are not RF validation. Android instrumentation and actual NuttX integration are separate checks; report their real results.
