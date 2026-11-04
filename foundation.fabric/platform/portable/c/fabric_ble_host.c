/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#include "fabric_ble_host.h"
#include <stdlib.h>
#include <string.h>

struct fabric_ble_host {
  struct fabric_ble_host_config config;
  fabric_ble_session *session;
  uint8_t rx[FABRIC_BLE_MAX_RECORD];
  size_t rx_size, rx_total;
  uint64_t rx_deadline;
  uint8_t tx[FABRIC_BLE_MAX_RECORD];
  size_t tx_size, tx_offset;
  /* One bounded control/receipt response, independent of an outgoing record.
   * Full-duplex peers must receive receipts while their data is in flight. */
  uint8_t response[FABRIC_BLE_HELLO_BYTES];
  size_t response_size;
  uint8_t app[FABRIC_BLE_MAX_PAYLOAD];
  size_t app_size;
  uint64_t app_wire_sequence;
  uint8_t context[FABRIC_BLE_CONTEXT_BYTES];
  int propose, established, failed;
  uint8_t peer_hello[FABRIC_BLE_HELLO_BYTES];
  uint64_t hello_retry, hello_deadline;
};
static void wipe(void *p, size_t n) {
  volatile uint8_t *q = p;
  while (n--)
    *q++ = 0;
}
static int fail(fabric_ble_host *h, int error) {
  h->failed = 1;
  wipe(h->rx, sizeof(h->rx));
  wipe(h->tx, sizeof(h->tx));
  wipe(h->response, sizeof(h->response));
  wipe(h->app, sizeof(h->app));
  h->app_size = 0;
  return error;
}
fabric_ble_host *fabric_ble_host_create(const struct fabric_ble_host_config *c,
                                        const uint8_t seed[32],
                                        const uint8_t peer[32],
                                        const uint8_t entropy[32]) {
  fabric_ble_host *h;
  int n;
  if (!c || !c->send_packet || !c->authorize || c->packet_bytes < 20 ||
      c->packet_bytes > 512 || !seed || !peer || !entropy)
    return NULL;
  h = calloc(1, sizeof(*h));
  if (!h)
    return NULL;
  h->config = *c;
  /* malloc is max_align_t aligned; ABI currently requires <=8-byte alignment.
   */
  if (fabric_ble_session_align() > _Alignof(max_align_t)) {
    free(h);
    return NULL;
  }
  h->session = malloc(fabric_ble_session_size());
  if (!h->session) {
    free(h);
    return NULL;
  }
  n = fabric_ble_session_init(h->session, fabric_ble_session_size(), seed, peer,
                              entropy);
  if (n < 0) {
    free(h->session);
    free(h);
    return NULL;
  }
  n = fabric_ble_session_hello(h->session, h->tx, sizeof(h->tx));
  if (n < 0) {
    fabric_ble_host_destroy(h);
    return NULL;
  }
  h->tx_size = (size_t)n;
  return h;
}
void fabric_ble_host_destroy(fabric_ble_host *h) {
  if (!h)
    return;
  fabric_ble_session_destroy(h->session);
  wipe(h->session, fabric_ble_session_size());
  free(h->session);
  wipe(h, sizeof(*h));
  free(h);
}
int fabric_ble_host_propose(fabric_ble_host *h, const uint8_t c[140]) {
  if (!h || !c || h->failed)
    return FABRIC_BLE_INVALID;
  if (h->propose || fabric_ble_session_phase(h->session) != 0)
    return FABRIC_BLE_STALE;
  if (h->config.authorize(h->config.ctx, c, 0) != 1)
    return FABRIC_BLE_UNAUTHORIZED;
  memcpy(h->context, c, 140);
  h->propose = 1;
  return 0;
}
int fabric_ble_host_poll(fabric_ble_host *h, uint64_t now) {
  uint8_t packet[512];
  int n, r;
  if (!h || h->failed)
    return FABRIC_BLE_STALE;
  if (h->rx_total && now >= h->rx_deadline) {
    wipe(h->rx, sizeof(h->rx));
    h->rx_total = h->rx_size = 0;
  }
  if (!h->tx_size && h->response_size) {
    memcpy(h->tx, h->response, h->response_size);
    h->tx_size = h->response_size;
    h->tx_offset = 0;
    wipe(h->response, h->response_size);
    h->response_size = 0;
  }
  /* Copy one fragment per poll, allowing platform write callbacks to free their
   * bounded queue before the next. Busy retains the exact offset and
   * ciphertext. */
  if (h->tx_offset < h->tx_size) {
    n = fabric_ble_fragment(h->tx, h->tx_size, h->tx_offset, packet,
                            h->config.packet_bytes);
    if (n < 0)
      return fail(h, n);
    r = h->config.send_packet(h->config.ctx, packet, (size_t)n);
    if (r == FABRIC_BLE_BUSY)
      return r;
    if (r < 0)
      return fail(h, r);
    h->tx_offset += (size_t)n - 5;
    if (h->tx_offset == h->tx_size) {
      wipe(h->tx, h->tx_size);
      h->tx_size = h->tx_offset = 0;
      h->hello_retry = now + 1000;
    }
    return 0;
  }
  if (!h->established) {
    if (!h->hello_deadline)
      h->hello_deadline = now + 10000;
    if (now >= h->hello_deadline)
      return fail(h, FABRIC_BLE_STALE);
    if (now >= h->hello_retry) {
      n = fabric_ble_session_hello(h->session, h->tx, sizeof(h->tx));
      if (n < 0)
        return fail(h, n);
      h->tx_size = (size_t)n;
    }
    return 0;
  }
  if (h->propose) {
    n = fabric_ble_session_propose(h->session, h->context, now, h->tx,
                                   sizeof(h->tx), h->config.authorize,
                                   h->config.ctx);
    if (n < 0)
      return fail(h, n);
    h->propose = 0;
    h->tx_size = (size_t)n;
    return 0;
  }
  n = fabric_ble_session_tick(h->session, now, h->tx, sizeof(h->tx));
  if (n < 0)
    return fail(h, n);
  h->tx_size = (size_t)n;
  return 0;
}
int fabric_ble_host_feed(fabric_ble_host *h, const uint8_t *p, size_t n,
                         uint64_t now) {
  size_t offset, total, body, overlap, app_size = 0;
  int r;
  if (!h || !p || h->failed)
    return FABRIC_BLE_STALE;
  if (n < 6 || n > h->config.packet_bytes || p[0] != 1)
    return FABRIC_BLE_INVALID;
  /* Keep receiving authenticated receipts even while an app message awaits
   * its consumer. New app payloads remain bounded to the one-message slot. */
  offset = ((size_t)p[1] << 8) | p[2];
  total = ((size_t)p[3] << 8) | p[4];
  body = n - 5;
  if (total == 0 || total > sizeof(h->rx) || offset + body > total)
    return FABRIC_BLE_TOO_LARGE;
  if (h->rx_total && now >= h->rx_deadline) {
    wipe(h->rx, sizeof(h->rx));
    h->rx_total = h->rx_size = 0;
  }
  /* An acknowledged record's delayed retry prefix may still be queued by
   * GATT when a later record of the same size begins. Offset zero starts a
   * new frame when its prefix differs; record authentication and replay
   * checks still happen only after full reassembly. Exact-prefix retries
   * retain progress. Nonzero conflicting fragments remain invalid. */
  size_t prefix = h->rx_size < body ? h->rx_size : body;
  if (offset == 0 && (!h->rx_total || h->rx_total != total ||
                     memcmp(h->rx, p + 5, prefix) != 0)) {
    h->rx_total = total;
    h->rx_size = 0;
    h->rx_deadline =
        now + 5000 +
        ((total + h->config.packet_bytes - 6) / (h->config.packet_bytes - 5)) *
            50;
  }
  if (total != h->rx_total || offset > h->rx_size)
    return FABRIC_BLE_BUSY;
  overlap = h->rx_size - offset;
  if (overlap > body)
    overlap = body;
  if (memcmp(h->rx + offset, p + 5, overlap) != 0)
    return fail(h, FABRIC_BLE_INVALID);
  memcpy(h->rx + offset, p + 5, body);
  if (h->rx_size < offset + body)
    h->rx_size = offset + body;
  if (h->rx_size < total)
    return 0;
  if (!h->established) {
    if (total != FABRIC_BLE_HELLO_BYTES) {
      wipe(h->rx, total);
      h->rx_total = h->rx_size = 0;
      return FABRIC_BLE_BUSY;
    }
    r = fabric_ble_session_establish(h->session, h->rx, total);
    if (r < 0)
      return fail(h, r);
    memcpy(h->peer_hello, h->rx, FABRIC_BLE_HELLO_BYTES);
    h->established = 1;
  } else if (total == FABRIC_BLE_HELLO_BYTES &&
             memcmp(h->peer_hello, h->rx, FABRIC_BLE_HELLO_BYTES) == 0) {
    /* Lost hello notification: retransmit our signed hello, never reset keys.
     */
    if (fabric_ble_session_phase(h->session) <= 1) {
      if (h->tx_size)
        return FABRIC_BLE_BUSY;
      r = fabric_ble_session_hello(h->session, h->tx, sizeof(h->tx));
      if (r < 0)
        return fail(h, r);
      h->tx_size = (size_t)r;
    }
  } else {
    /* The record kind is authenticated by session_receive below. Inspect it
     * only to reserve queue capacity, never to grant access or deliver data. */
    uint64_t wire_sequence=0;
    for(unsigned i=1;i<9 && i<total;i++)wire_sequence=(wire_sequence<<8)|h->rx[i];
    /* Keep fresh app data backpressured, but authenticate retries of the
     * occupied slot so Session can replay its receipt without redelivery.
     * Header equality only reserves capacity; AEAD still authenticates first. */
    int retry=h->app_size && h->rx[0]==1 && wire_sequence==h->app_wire_sequence;
    if ((h->app_size && h->rx[0] == 1 && !retry) ||
        (h->response_size && h->rx[0] != 2))
      return FABRIC_BLE_BUSY;
    uint8_t response[FABRIC_BLE_HELLO_BYTES];
    r = fabric_ble_session_receive(
        h->session, h->rx, total, now, response, sizeof(response), h->app,
        sizeof(h->app), &app_size, h->config.authorize, h->config.ctx);
    if (r == FABRIC_BLE_DUPLICATE)
      r = 0;
    if (r < 0)
      return fail(h, r);
    if (r > 0) {
      if (h->response_size)
        return fail(h, FABRIC_BLE_BUSY);
      memcpy(h->response, response, (size_t)r);
      h->response_size = (size_t)r;
    }
    wipe(response, sizeof(response));
    if (app_size){
      h->app_size = app_size;
      h->app_wire_sequence=wire_sequence;
    }
  }
  wipe(h->rx, total);
  h->rx_total = h->rx_size = 0;
  return 0;
}
int fabric_ble_host_send(fabric_ble_host *h, const uint8_t *p, size_t n,
                         uint64_t now) {
  int r;
  if (!h || !p || h->failed)
    return FABRIC_BLE_STALE;
  if (n == 0)
    return FABRIC_BLE_INVALID;
  if (n > FABRIC_BLE_MAX_APP_PAYLOAD)
    return FABRIC_BLE_TOO_LARGE;
  if (h->tx_size || h->response_size)
    return FABRIC_BLE_BUSY;
  r = fabric_ble_session_send(h->session, p, n, now, h->tx, sizeof(h->tx),
                              h->config.authorize, h->config.ctx);
  if (r >= 0) {
    h->tx_size = (size_t)r;
    h->tx_offset = 0;
    return 0;
  }
  return r;
}
int fabric_ble_host_take_message(fabric_ble_host *h, uint8_t *out, size_t cap) {
  size_t n;
  if (!h || !out || h->failed)
    return FABRIC_BLE_STALE;
  if (!h->app_size)
    return 0;
  if (fabric_ble_host_authorized(h, 2) != 1)
    return fail(h, FABRIC_BLE_UNAUTHORIZED);
  n = h->app_size;
  if (cap < n)
    return FABRIC_BLE_TOO_LARGE;
  memcpy(out, h->app, n);
  wipe(h->app, n);
  h->app_size = 0;
  return (int)n;
}
int fabric_ble_host_authorized(fabric_ble_host *h, uint8_t action) {
  return !h || h->failed ? 0
                         : fabric_ble_session_authorized(h->session, action,
                                                         h->config.authorize,
                                                         h->config.ctx);
}
int fabric_ble_host_phase(fabric_ble_host *h) {
  return !h || h->failed ? 7 : fabric_ble_session_phase(h->session);
}

int fabric_ble_host_max_payload(fabric_ble_host *h) {
  return !h || h->failed ? 0 : fabric_ble_session_max_payload(h->session);
}
