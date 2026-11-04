/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef FOUNDATION_FABRIC_PAIRING_H
#define FOUNDATION_FABRIC_PAIRING_H
#include "fabric_ble.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct fabric_pairing fabric_pairing;
/* Called only after BOTH explicit local confirmation and authenticated remote
 * confirmation. Persist public key cache + DeviceId trust=Trusted atomically
 * and durably in the Fabric identity backend; return 1 only after success.
 * An app-owned phone.conf / enrollment token is not this trust backend. */
typedef int32_t (*fabric_persist_peer)(void *, const uint8_t public_key[32],
                                       const uint8_t device_id[32]);
size_t fabric_pairing_size(void);
size_t fabric_pairing_align(void);
/* request_pairing semantics: initiator expected_peer is device public from QR /
 * checked discovery locator (NOT trusted just by scanning); responder passes
 * NULL, then learns an untrusted phone key from signed hello. Fresh entropy per
 * attempt. No Session or app message APIs exist on a pairing object. */
int32_t fabric_request_pairing(fabric_pairing *, size_t, const uint8_t seed[32],
                               const uint8_t entropy[32],
                               const uint8_t *expected_peer, uint64_t now_ms);
int32_t fabric_pairing_hello(fabric_pairing *, uint8_t *, size_t);
int32_t fabric_pairing_receive_hello(fabric_pairing *, const uint8_t *, size_t,
                                     uint64_t);
/* Display %06u on both devices, or enter device code on phone and explicitly
 * confirm on device. UI must compare actual transcript code and enforce local
 * user presence/rate limiting. No auto-confirm on BLE bond / socket connection.
 */
int32_t fabric_pairing_code(fabric_pairing *);
int32_t fabric_pairing_peer(fabric_pairing *, uint8_t public_key[32],
                            uint8_t device_id[32]);
int32_t fabric_pairing_confirm(fabric_pairing *, uint32_t displayed_code,
                               int32_t accepted, uint64_t now_ms);
/* Pump encrypted PairingConfirm/Complete output over same fragment codec.
 * Call after confirm/receive and every <=100ms. Retry exact ciphertext at 1s;
 * entire attempt expires after 120s. Persist callback is mandatory. */
int32_t fabric_complete_pairing(fabric_pairing *, uint64_t, uint8_t *, size_t,
                                fabric_persist_peer, void *);
int32_t fabric_pairing_receive(fabric_pairing *, uint8_t *, size_t, uint64_t);
/* 0 WaitingHello,1 AwaitingUser,2 AwaitingPeer,3 Persisting,4 Complete,5
 * Rejected. Only phase4 permits a fresh paired Session host, using persisted
 * trust + fresh entropy. Flush final PairingComplete before reconnecting
 * GATT/new link hello. */
int32_t fabric_pairing_phase(fabric_pairing *);
void fabric_pairing_destroy(fabric_pairing *);
#ifdef __cplusplus
}
#endif
#endif
