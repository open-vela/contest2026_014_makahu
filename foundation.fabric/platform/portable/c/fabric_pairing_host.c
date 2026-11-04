/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#include "fabric_pairing_host.h"
#include <stdlib.h>
#include <string.h>
struct fabric_pairing_host {
  struct fabric_pairing_host_config config;
  fabric_pairing *pairing;
  uint8_t tx[FABRIC_BLE_HELLO_BYTES], rx[FABRIC_BLE_HELLO_BYTES],
      peer_hello[FABRIC_BLE_HELLO_BYTES];
  size_t tx_size, tx_offset, rx_total, rx_size;
  uint64_t deadline, hello_retry;
  int established, failed, send_hello, hello_sent;
};
static void wipe(void *p, size_t n) {
  volatile uint8_t *v = p;
  while (n--)
    *v++ = 0;
}
static int fail(fabric_pairing_host *h, int e) {
  h->failed = 1;
  wipe(h->rx, sizeof(h->rx));
  wipe(h->tx, sizeof(h->tx));
  h->tx_size = 0;
  return e;
}
fabric_pairing_host *
fabric_pairing_host_create(const struct fabric_pairing_host_config *c,
                           const uint8_t seed[32], const uint8_t entropy[32],
                           const uint8_t *expected, uint64_t now) {
  if (!c || !c->send_packet || !c->persist_peer || c->packet_bytes < 20 ||
      c->packet_bytes > 512 || !seed || !entropy ||
      fabric_pairing_align() > _Alignof(max_align_t))
    return NULL;
  fabric_pairing_host *h = calloc(1, sizeof(*h));
  if (!h)
    return NULL;
  h->config = *c;
  h->deadline = now + 120000;
  h->pairing = malloc(fabric_pairing_size());
  if (!h->pairing) {
    free(h);
    return NULL;
  }
  if (fabric_request_pairing(h->pairing, fabric_pairing_size(), seed, entropy,
                             expected, now) < 0) {
    free(h->pairing);
    free(h);
    return NULL;
  }
  if (expected) {
    int n = fabric_pairing_hello(h->pairing, h->tx, sizeof(h->tx));
    if (n < 0) {
      fabric_pairing_host_destroy(h);
      return NULL;
    }
    h->tx_size = (size_t)n;
  }
  return h;
}
void fabric_pairing_host_destroy(fabric_pairing_host *h) {
  if (!h)
    return;
  fabric_pairing_destroy(h->pairing);
  wipe(h->pairing, fabric_pairing_size());
  free(h->pairing);
  wipe(h, sizeof(*h));
  free(h);
}
int fabric_pairing_host_poll(fabric_pairing_host *h, uint64_t now) {
  if (!h || h->failed)
    return FABRIC_BLE_STALE;
  if (now >= h->deadline)
    return fail(h, FABRIC_BLE_STALE);
  if (h->tx_size) {
    uint8_t p[512];
    int n = fabric_ble_fragment(h->tx, h->tx_size, h->tx_offset, p,
                                h->config.packet_bytes);
    if (n < 0)
      return fail(h, n);
    int r = h->config.send_packet(h->config.ctx, p, (size_t)n);
    if (r == FABRIC_BLE_BUSY)
      return r;
    if (r < 0)
      return fail(h, r);
    h->tx_offset += (size_t)n - 5;
    if (h->tx_offset == h->tx_size) {
      if (h->tx_size == FABRIC_BLE_HELLO_BYTES)
        h->hello_sent = 1;
      wipe(h->tx, h->tx_size);
      h->tx_size = h->tx_offset = 0;
      h->hello_retry = now + 1000;
    }
    return 0;
  }
  if (h->send_hello || (!h->established && now >= h->hello_retry)) {
    int n = fabric_pairing_hello(h->pairing, h->tx, sizeof(h->tx));
    if (n == FABRIC_BLE_NOT_READY)
      return 0;
    if (n < 0)
      return fail(h, n);
    h->tx_size = (size_t)n;
    h->send_hello = 0;
    return 0;
  }
  if (!h->established)
    return 0;
  int n = fabric_complete_pairing(h->pairing, now, h->tx, sizeof(h->tx),
                                  h->config.persist_peer, h->config.ctx);
  if (n < 0)
    return fail(h, n);
  h->tx_size = (size_t)n;
  return 0;
}
int fabric_pairing_host_feed(fabric_pairing_host *h, const uint8_t *p, size_t n,
                             uint64_t now) {
  if (!h || !p || h->failed)
    return FABRIC_BLE_STALE;
  if (now >= h->deadline)
    return fail(h, FABRIC_BLE_STALE);
  if (n < 6 || n > h->config.packet_bytes || p[0] != 1)
    return FABRIC_BLE_INVALID;
  size_t offset = ((size_t)p[1] << 8) | p[2],
         total = ((size_t)p[3] << 8) | p[4], body = n - 5;
  if (!total || total > sizeof(h->rx) || offset + body > total)
    return FABRIC_BLE_TOO_LARGE;
  if (offset == 0 && (!h->rx_total || h->rx_total != total)) {
    h->rx_total = total;
    h->rx_size = 0;
  }
  if (h->rx_total != total || offset > h->rx_size)
    return FABRIC_BLE_BUSY;
  size_t overlap = h->rx_size - offset;
  if (overlap > body)
    overlap = body;
  if (memcmp(h->rx + offset, p + 5, overlap) != 0)
    return fail(h, FABRIC_BLE_INVALID);
  memcpy(h->rx + offset, p + 5, body);
  if (h->rx_size < offset + body)
    h->rx_size = offset + body;
  if (h->rx_size < total)
    return 0;
  int r = 0;
  if (!h->established) {
    if (total != FABRIC_BLE_HELLO_BYTES) {
      wipe(h->rx, total);
      h->rx_size = h->rx_total = 0;
      return FABRIC_BLE_BUSY;
    }
    r = fabric_pairing_receive_hello(h->pairing, h->rx, total, now);
    if (r < 0)
      return fail(h, r);
    memcpy(h->peer_hello, h->rx, sizeof(h->peer_hello));
    h->established = 1;
    if (!h->hello_sent)
      h->send_hello = 1;
  } else if (total == sizeof(h->peer_hello) &&
             memcmp(h->rx, h->peer_hello, total) == 0) {
    /* Retry hello only before challenge exchange; never reset agreed keys. */
    if (fabric_pairing_code(h->pairing) < 0)
      h->send_hello = 1;
  } else {
    r = fabric_pairing_receive(h->pairing, h->rx, total, now);
    if (r < 0)
      return fail(h, r);
  }
  wipe(h->rx, total);
  h->rx_size = h->rx_total = 0;
  return 0;
}
int fabric_pairing_host_code(fabric_pairing_host *h) {
  return !h || h->failed ? FABRIC_BLE_STALE : fabric_pairing_code(h->pairing);
}
int fabric_pairing_host_confirm(fabric_pairing_host *h, uint32_t code,
                                int accepted, uint64_t now) {
  if (!h || h->failed)
    return FABRIC_BLE_STALE;
  int r = fabric_pairing_confirm(h->pairing, code, accepted, now);
  if (r < 0)
    return fail(h, r);
  return r;
}
int fabric_pairing_host_phase(fabric_pairing_host *h) {
  return !h || h->failed ? 5 : fabric_pairing_phase(h->pairing);
}
int fabric_pairing_host_peer(fabric_pairing_host *h, uint8_t public[32],
                             uint8_t id[32]) {
  return !h || h->failed ? FABRIC_BLE_STALE
                         : fabric_pairing_peer(h->pairing, public, id);
}
int fabric_pairing_host_flushed(fabric_pairing_host *h) {
  return h && !h->failed && !h->tx_size;
}
