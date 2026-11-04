/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef FOUNDATION_FABRIC_PAIRING_HOST_H
#define FOUNDATION_FABRIC_PAIRING_HOST_H
#include "fabric_pairing.h"
typedef struct fabric_pairing_host fabric_pairing_host;
struct fabric_pairing_host_config {
  void *ctx;
  int (*send_packet)(void *, const uint8_t *, size_t);
  fabric_persist_peer persist_peer;
  size_t packet_bytes;
};
/* Create ONLY in a user-authorized pairing window with rate limits. Initiator
 * expected_peer pins QR device public key, responder passes NULL. Never auto
 * confirm. One executor serializes feed/poll/UI confirm; callbacks copy
 * packets. Identity seed must already be durably loaded/created by Fabric
 * backend. */
fabric_pairing_host *
fabric_pairing_host_create(const struct fabric_pairing_host_config *,
                           const uint8_t seed[32], const uint8_t entropy[32],
                           const uint8_t *expected_peer, uint64_t now_ms);
void fabric_pairing_host_destroy(fabric_pairing_host *);
int fabric_pairing_host_feed(fabric_pairing_host *, const uint8_t *, size_t,
                             uint64_t);
int fabric_pairing_host_poll(fabric_pairing_host *, uint64_t);
/* Negative NotReady until both signed commitments and encrypted challenge
 * reveals are exchanged. UI displays exactly %06u after code >= 0. */
int fabric_pairing_host_code(fabric_pairing_host *);
int fabric_pairing_host_confirm(fabric_pairing_host *, uint32_t, int, uint64_t);
int fabric_pairing_host_phase(fabric_pairing_host *);
int fabric_pairing_host_peer(fabric_pairing_host *, uint8_t public_key[32],
                             uint8_t device_id[32]);
/* phase4 and flushed()==1 permit disconnect/reconnect for paired Session. */
int fabric_pairing_host_flushed(fabric_pairing_host *);
#endif
