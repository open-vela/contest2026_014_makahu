/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef FOUNDATION_FABRIC_BLE_H
#define FOUNDATION_FABRIC_BLE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define FABRIC_BLE_MAX_APP_PAYLOAD 12288u
#define FABRIC_BLE_MAX_PAYLOAD (12288u + 84u)
#define FABRIC_BLE_MAX_RECORD (FABRIC_BLE_MAX_PAYLOAD + 25u)
#define FABRIC_BLE_HELLO_BYTES 204u
#define FABRIC_BLE_INVALID (-1)
#define FABRIC_BLE_UNAUTHORIZED (-2)
#define FABRIC_BLE_UNSUPPORTED (-3)
#define FABRIC_BLE_TOO_LARGE (-4)
#define FABRIC_BLE_STALE (-5)
#define FABRIC_BLE_DUPLICATE (-6)
#define FABRIC_BLE_NOT_READY (-7)
#define FABRIC_BLE_CRYPTO (-8)
#define FABRIC_BLE_BUSY (-9)
/* Opaque state: allocate size/align below. No malloc, std, Tokio or OS APIs in
 * the bare-metal library. One owner serializes calls; callbacks must not
 * reenter. Only init accepts uninitialized storage. Destroy exactly once after
 * success. Keys/buffers are never logged. Inputs and outputs must not overlap.
 */
typedef struct fabric_ble_link fabric_ble_link;
int32_t fabric_device_id(const uint8_t public_key[32], uint8_t device_id[32]);
int32_t fabric_identity_public(const uint8_t seed[32], uint8_t public_key[32],
                               uint8_t device_id[32]);
size_t fabric_ble_link_size(void);
size_t fabric_ble_link_align(void);
/* seed is existing Fabric Ed25519 seed; peer is trusted Fabric public key,
 * obtained from pairing/permission policy, NOT supplied by an untrusted GATT
 * packet. entropy is fresh 32-byte CSPRNG output per connection/restart.
 * No legacy enrollment token / six-digit code may replace either key. */
int32_t fabric_ble_link_init(fabric_ble_link *, size_t, const uint8_t seed[32],
                             const uint8_t peer[32], const uint8_t entropy[32]);
int32_t fabric_ble_link_hello(fabric_ble_link *, uint8_t *, size_t);
int32_t fabric_ble_link_establish(fabric_ble_link *, const uint8_t *, size_t);
/* Device-link codec ONLY. Session host must validate typed framework controls
 * (kind 0) and admit kind 1 ONLY after committed Session + channel grants.
 * kind 2 is encrypted key confirmation / delivery receipt, not app execution.
 * EndToEnd contracts are Unsupported until app-level E2EE is implemented. */
int32_t fabric_ble_link_seal(fabric_ble_link *, uint8_t kind, const uint8_t *,
                             size_t, uint8_t *, size_t);
/* Decrypt in place; returns payload length, payload at +9, kind at +0.
 * Duplicate authenticates first and returns -6 without delivering twice. */
int32_t fabric_ble_link_open(fabric_ble_link *, uint8_t *, size_t);
void fabric_ble_link_destroy(fabric_ble_link *);
/* Fragment record into negotiated (ATT_MTU-3) bytes. Header is 5 bytes;
 * advances offset by return_value-5. GATT callbacks deliver these opaque bytes.
 * send success means queued/write acknowledged, not remote app executed. */
int32_t fabric_ble_fragment(const uint8_t *, size_t, size_t, uint8_t *, size_t);
/* Embedded profile: two approved participants, one HubReadable reliable
 * channel, initial epoch only. No voting, membership change, stream/datagram,
 * cross-profile resume or E2EE claim. Wire is portable-v1 (not desktop
 * protobuf). Context bytes are big endian: session UUID[16], epoch u64, channel
 * UUID[16], initiator Participant UUID[16], responder Participant UUID[16],
 * initiator Port UUID[16], responder Port UUID[16], approved plan
 * fingerprint[32], max payload u32 (1..12288). Policy MUST bind these fields to
 * local registered app/ability/port and the trusted remote Fabric identity;
 * return 1 only for currently allowed access. Actions: 0 Open/commit, 1 Send, 2
 * Receive. Rechecked for every operation. Never use an unconditional allow
 * callback in a production host. */
#define FABRIC_BLE_CONTEXT_BYTES 140u
/* Finalize canonical portable-profile plan fingerprint after filling fields.
 * Structural hashing is not authorization; policy must approve the full tuple.
 */
int32_t fabric_ble_context_finalize(uint8_t context[140]);
typedef struct fabric_ble_session fabric_ble_session;
typedef int32_t (*fabric_ble_authorize)(void *, const uint8_t context[140],
                                        uint8_t action);
size_t fabric_ble_session_size(void);
size_t fabric_ble_session_align(void);
int32_t fabric_ble_session_init(fabric_ble_session *, size_t, const uint8_t[32],
                                const uint8_t[32], const uint8_t[32]);
int32_t fabric_ble_session_hello(fabric_ble_session *, uint8_t *, size_t);
int32_t fabric_ble_session_establish(fabric_ble_session *, const uint8_t *,
                                     size_t);
int32_t fabric_ble_session_propose(fabric_ble_session *, const uint8_t[140],
                                   uint64_t, uint8_t *, size_t,
                                   fabric_ble_authorize, void *);
int32_t fabric_ble_session_send(fabric_ble_session *, const uint8_t *, size_t,
                                uint64_t, uint8_t *, size_t,
                                fabric_ble_authorize, void *);
/* Reserve app executor capacity before calling receive. On success enqueue app
 * bytes before transmitting reply. reply >= 0 is number of encrypted response
 * bytes; *app_length is separate (zero for controls/duplicates). No callback is
 * invoked on GATT thread except synchronous policy. Record is decrypted in
 * place. Validated output goes to the app's existing protocol handler
 * unchanged. */
int32_t fabric_ble_session_receive(fabric_ble_session *, uint8_t *, size_t,
                                   uint64_t, uint8_t *, size_t, uint8_t *,
                                   size_t, size_t *, fabric_ble_authorize,
                                   void *);
int32_t fabric_ble_session_authorized(fabric_ble_session *, uint8_t,
                                      fabric_ble_authorize, void *);
/* 0 Idle,1 Proposed,2 Accepted,3 Preparing,4 Ready,5 Committing,6 Active,7
 * Suspended */
int32_t fabric_ble_session_phase(fabric_ble_session *);
int32_t fabric_ble_session_max_payload(fabric_ble_session *);
/* Call every <=100ms using monotonic milliseconds; transmit any returned
 * record. Whole-record retry budgets MTU20 at 50ms/fragment plus 1s; 5 retries,
 * deadline at least 10s (max ~254s for 12KiB), then explicit Suspended. */
int32_t fabric_ble_session_tick(fabric_ble_session *, uint64_t, uint8_t *,
                                size_t);
void fabric_ble_session_destroy(fabric_ble_session *);
#ifdef __cplusplus
}
#endif
#endif
