# Android Fabric BLE backend

Build native libraries with `build-native.sh /absolute/jniLibs` (`ANDROID_NDK_HOME` required), then build this standalone Android library with `-PfabricJniLibs=/absolute/jniLibs`. This does not change the Android application's Gradle files. The AAR packages arm64-v8a, armeabi-v7a and x86_64 `libfabric_ble_android.so`, `FabricBleNative`, and the real `FabricGattClient` central backend. Consumer rules retain JNI policy methods under R8.

`FabricGattClient` requests MTU, discovers the Fabric data service, subscribes CCC, reads/binds the public identity locator, and serializes Write With Response through bounded queues/retries. It returns real failure on permissions, missing characteristics, timeouts and overflow. The caller owns discovery/QR scanning and runtime permission UI. Pass a QR public-key pin if present; a mismatch rejects. The GATT identity read is a locator, never trust. Feed raw fragments into the native host; onReady is not paired/authorized.

Use high-level `identityInfo(directory)`, `requestPairingHost`, and `createFromIdentity`. IdentityInfo returns public key + DeviceId, never the seed. Pairing host uses the framework-owned POSIX backend directly; no app SharedPreferences trust store is needed. A peer becomes trusted only after both real user confirmations and durable persistence. `feed/poll/takePacket/phase/close` work for paired and pairing hosts; use `pairingHostCode/Confirm/Peer/Flushed` for UI integration. A pairing handle has no message Send API. After Complete and flushed output, reconnect using fresh entropy and `createFromIdentity`; the native backend checks trust on admission and every app operation in addition to the supplied app/port policy.

The directory is the SAME explicit `state_directory` passed to an embedded NativeHub, with `fabric-device.key` and `trusted-devices.txt`. An existing Hub app uses its filesDir. The host must arrange verified private 0700/0600 storage; an insecure existing seed is rejected. Separate Android UIDs must use authenticated Hub IPC instead of copying private keys or silently creating a second authority. Linux/Android C and Rust trust writers share the same flock sidecar, reload before modifications, and replace records atomically. Rust reads refresh the canonical file so revocation is observed without restarting. NuttX requires one designated trust executor. This portable profile does not interoperate with desktop protobuf sessions.

All calls belong on a serialized worker; policy callbacks are synchronous, bounded, and must not reenter native mutation APIs. Pump at <=100ms with `SystemClock.elapsedRealtime`, pass actual negotiated ATT_MTU-3, retain a native packet until `sendPacket` accepts it, and drain app messages only into a bounded executor. App payload maximum is negotiated and reported by maxPayload after admission; compare against the app's own protocol limit and reject oversize, never truncate. Message queueing/write/transport receipts are not application execution success.

`PortableInteropTest` is a native/Session instrumentation fixture on Android, not a radio test. `tests/PortableJniSmoke.java` runs against the production Kotlin classes and JNI in a host JVM. The portable C tests can also be built as Android ELF fixtures (`FABRIC_TEST_TMP=/data/local/tmp`) to validate Bionic/CPU execution separately from app startup. Neither result proves BLE radio operation. See `../portable/README.md` for first pairing, wire, NuttX backend and profile limits.


Build the complete framework delivery from source with:

```sh
ANDROID_HOME=/path/to/Android/Sdk bash platform/android/build-framework.sh
```

This builds native libraries for all three ABIs, then the portable AAR, SDK AAR,
and Hub APK; it preserves application Gradle settings. Artifacts are
`platform/android/build/outputs/aar/fabric-ble-android-debug.aar`,
`apps/android/fabric-sdk/build/outputs/aar/fabric-sdk-debug.aar`, and
`apps/android/hub-app/build/outputs/apk/debug/hub-app-debug.apk`.

External applications use `FabricClient.openBleMessages`, `confirmBlePairing`,
`sendBleMessage` and `closeBleMessages`; they must hold CONNECT_HUB and own a live
registered ability. The Hub owns its existing state directory, GATT executor,
first pairing, trust and Session context. Applications receive public peer IDs,
explicit short-code confirmation, admission state and ordinary payloads only.
The context is constructed inside the Hub, not accepted as a caller's plan.
Callbacks and quotas are bounded and revoked on ability removal or Binder death.
The embedded profile remains two participants / one HubReadable channel / epoch1.

`BleBinderAdmissionTest` in fabric-sdk runs as an independent Android UID against
an installed Hub APK. Grant its test package CONNECT_HUB before running. It
covers package spoofing, unregistered/revoked abilities, malformed budgets/IDs
and fabricated connection handles. It does not supply a radio peer or claim
that an admitted open has completed GATT/pairing/Session negotiation.


Windows runtime validation (2026-10-16): the existing Android17 R7 AVD at5582
ran the native portable interoperability test and both independent-UID Binder
admission tests successfully. Linux is used only for builds/host tests; keep
Android and Vela simulators on Windows. `windows/start-framework-android.ps1`
starts an existing AVD with a visible window and preserves userdata. It refuses
an already-running AVD/port. `windows/test-framework-android.ps1` installs the
three test artifacts with `-r` and exercises the real installed Hub across UIDs.
Only the framework test package's CONNECT_HUB permission is changed.

The startup script enables netsim HCI6402 for a Vela H4 UART client. A real TCP
H4 Reset probe returned successful CommandComplete on Windows, establishing
controller IO readiness. This is a simulated Bluetooth controller, not physical
RF validation and not proof of completed Android–Vela GATT/OOBE. Keep the user's
visible emulator running; do not restart it for nonessential configuration.


Use `windows/check-hci.ps1` for a read-only endpoint/owner/connection report.
Its optional `-ProbeReset` creates then removes one temporary controller and
checks a real H4 Reset response. It does not pair or provision an application.
Vela must independently attach its configured `/dev/ttyHCI0` via raw H4 socket
to6402; enabling a flag on Android alone cannot attach the device. Keep that
virtio-serial bus separate from the LittleFS block device (the current Windows
fixture uses serial bus6 and private block bus3).

`GattCarrierTest` is a separate opt-in actual-peer check. Run the Windows
`test-gatt-carrier.ps1 -PeerAddress <actual advertising Vela address>` once the
device controller/daemon/GATT service are running. The script updates only the
framework test APK and its Bluetooth permission. The production Android GATT
backend must complete MTU, CCC subscription and64-byte Identity Read/ReadBlob.
No pretrusted key, automatic pairing confirmation or app credential is supplied.
Passing it proves GATT setup only; first pairing and application Session exchange
remain separate acceptance steps. Missing peer arguments skip the test and are
not accepted by the mandatory-argument Windows test script.
