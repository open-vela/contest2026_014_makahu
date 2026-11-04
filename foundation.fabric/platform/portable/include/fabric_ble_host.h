/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef FOUNDATION_FABRIC_BLE_HOST_H
#define FOUNDATION_FABRIC_BLE_HOST_H
#include "fabric_ble.h"
#define FABRIC_BLE_SERVICE_UUID "7f6d0001-6b4e-4a30-9e8f-464142524943"
#define FABRIC_BLE_RX_UUID "7f6d0002-6b4e-4a30-9e8f-464142524943"
#define FABRIC_BLE_TX_UUID "7f6d0003-6b4e-4a30-9e8f-464142524943"
#define FABRIC_BLE_IDENTITY_UUID "7f6d0004-6b4e-4a30-9e8f-464142524943"
/* IDENTITY read value is publicKey32 || SHA256(publicKey)32; locator only. */
/* Single executor, no calls from concurrent GATT/app threads. GATT callbacks
 * post copied packets to this executor. The host owns a one-message inbound
 * slot; pending app data backpressures incoming records until take_message.
 * No LVGL/UI callbacks execute here. Use at least 64KiB worker task stack. */
typedef struct fabric_ble_host fabric_ble_host;
struct fabric_ble_host_config {
  void *ctx;
  /* Return 0 once packet copied to bounded platform queue, -9 if full.
   * Serialized by poll; platform must preserve write/notification ordering. */
  int (*send_packet)(void *, const uint8_t *, size_t);
  fabric_ble_authorize authorize;
  /* 20..512 actual ATT_MTU-3, not requested MTU. */
  size_t packet_bytes;
};
/* Paired-peer mode ONLY: seed/public are loaded from Fabric identity/trust
 * backend. Creator must already verify peer DeviceId/trust and local app
 * principal before construction. No BLE bond/legacy token fallback. */
fabric_ble_host *fabric_ble_host_create(const struct fabric_ble_host_config *,
                                        const uint8_t seed[32],
                                        const uint8_t peer[32],
                                        const uint8_t entropy[32]);
void fabric_ble_host_destroy(fabric_ble_host *);
/* Only initiating peer calls propose; responder host policy sees exact context
 * and approves its app/principal/port binding. Call before or after link hello.
 */
int fabric_ble_host_propose(fabric_ble_host *, const uint8_t context[140]);
int fabric_ble_host_feed(fabric_ble_host *, const uint8_t *, size_t,
                         uint64_t now_ms);
int fabric_ble_host_poll(fabric_ble_host *, uint64_t now_ms);
int fabric_ble_host_send(fabric_ble_host *, const uint8_t *, size_t,
                         uint64_t now_ms);
/* Copies and clears pending application message. 0 means none. */
int fabric_ble_host_take_message(fabric_ble_host *, uint8_t *, size_t);
int fabric_ble_host_authorized(fabric_ble_host *, uint8_t action);
int fabric_ble_host_phase(fabric_ble_host *);
int fabric_ble_host_max_payload(fabric_ble_host *);
#endif
