#include "fabric_ble_host.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
struct side {
  fabric_ble_host *host;
  struct side *peer;
  uint8_t packet[512];
  size_t size;
  int enabled;
  int dropped;
  int drop_next_app;
  int drop_next_receipt;
  int capture_first;
  uint8_t saved_first[512];
  size_t saved_size;
};
static int packet(void *ctx, const uint8_t *p, size_t n) {
  struct side *s = ctx;
  if (s->size)
    return FABRIC_BLE_BUSY;
  memcpy(s->packet, p, n);
  s->size = n;
  if (s->capture_first && !s->saved_size && p[1] == 0 && p[2] == 0 && p[5] == 1) {
    memcpy(s->saved_first, p, n);
    s->saved_size = n;
  }
  return 0;
}
static uint8_t approved[140];
static int32_t authorize(void *ctx, const uint8_t *c, uint8_t action) {
  struct side *s = ctx;
  return s->enabled && action <= 2 && memcmp(c, approved, 140) == 0;
}
static void step(struct side *a, struct side *b, uint64_t t) {
  int r = fabric_ble_host_poll(a->host, t);
  if (r != 0 && r != FABRIC_BLE_BUSY) fprintf(stderr, "poll failure t=%llu r=%d phases=%d/%d\n", (unsigned long long)t, r, fabric_ble_host_phase(a->host), fabric_ble_host_phase(b->host));
  assert(r == 0 || r == FABRIC_BLE_BUSY);
  r = fabric_ble_host_poll(b->host, t);
  if (r != 0 && r != FABRIC_BLE_BUSY) fprintf(stderr, "poll failure t=%llu r=%d phases=%d/%d\n", (unsigned long long)t, r, fabric_ble_host_phase(a->host), fabric_ble_host_phase(b->host));
  assert(r == 0 || r == FABRIC_BLE_BUSY);
  struct side *sides[] = {a, b};
  for (unsigned i = 0; i < 2; i++) {
    struct side *s = sides[i];
    if (!s->size)
      continue;
    /* Drop one notification including handshake coverage, just as RF can. */
    if (!s->dropped) {
      s->dropped = 1;
      s->size = 0;
      continue;
    }
    if(s->drop_next_receipt && s->packet[1]==0 && s->packet[2]==0 && s->packet[5]==2){s->drop_next_receipt=0;s->size=0;continue;}
    if (s->drop_next_app && s->packet[1] == 0 && s->packet[2] == 0 && s->packet[5] == 1) {
      s->drop_next_app = 0; s->size = 0; continue;
    }
    r = fabric_ble_host_feed(s->peer->host, s->packet, s->size, t);
    assert(r == 0 || r == FABRIC_BLE_BUSY);
    s->size = 0;
  }
}
int main(void) {
  uint8_t as[32], bs[32], ap[32], bp[32], id[32], ae[32], be[32];
  memset(as, 1, 32);
  memset(bs, 2, 32);
  memset(ae, 3, 32);
  memset(be, 4, 32);
  assert(fabric_identity_public(as, ap, id) == 0);
  assert(fabric_identity_public(bs, bp, id) == 0);
  memset(approved, 7, sizeof(approved));
  approved[23] = 1;
  approved[16] = approved[17] = approved[18] = approved[19] = approved[20] =
      approved[21] = approved[22] = 0;
  memset(approved + 40, 3, 16);
  memset(approved + 56, 4, 16);
  approved[136] = 0;
  approved[137] = 0;
  approved[138] = 48;
  approved[139] = 0;
  assert(fabric_ble_context_finalize(approved) == 0);
  struct side a = {.enabled = 1}, b = {.enabled = 1};
  a.peer = &b;
  b.peer = &a;
  struct fabric_ble_host_config ac = {.ctx = &a,
                                      .send_packet = packet,
                                      .authorize = authorize,
                                      .packet_bytes = 20},
                                bc = ac;
  bc.ctx = &b;
  a.host = fabric_ble_host_create(&ac, as, bp, ae);
  b.host = fabric_ble_host_create(&bc, bs, ap, be);
  assert(a.host && b.host);
  assert(fabric_ble_host_propose(a.host, approved) == 0);
  uint64_t t;
  for (t = 1; t < 15000; t += 10) {
    step(&a, &b, t);
    if (fabric_ble_host_phase(a.host) == 6 &&
        fabric_ble_host_phase(b.host) == 6 && !a.size && !b.size)
      break;
  }
  assert(t < 15000);
  assert(fabric_ble_host_authorized(a.host, 1) == 1);
  const uint8_t message[] = "synthetic app configuration, no real credential";
  assert(fabric_ble_host_send(a.host, message, sizeof(message), t) == 0);
  uint8_t out[FABRIC_BLE_MAX_APP_PAYLOAD];
  int n = 0;
  for (t += 10; t < 25000; t += 10) {
    step(&a, &b, t);
    n = fabric_ble_host_take_message(b.host, out, sizeof(out));
    if (n > 0)
      break;
    assert(n == 0);
  }
  assert(n == (int)sizeof(message));
  assert(memcmp(out, message, sizeof(message)) == 0);
  int sent;
  for (t += 10; t < 26000; t += 10) {
    step(&a, &b, t);
    sent = fabric_ble_host_send(b.host, (const uint8_t *)"result", 6, t);
    if (sent == 0)
      break;
    assert(sent == FABRIC_BLE_BUSY);
  }
  assert(sent == 0);
  n = 0;
  for (t += 10; t < 30000; t += 10) {
    step(&a, &b, t);
    n = fabric_ble_host_take_message(a.host, out, sizeof(out));
    if (n > 0)
      break;
    assert(n == 0);
  }
  assert(n == 6 && memcmp(out, "result", 6) == 0);
  uint8_t full[FABRIC_BLE_MAX_APP_PAYLOAD + 1];
  for (size_t i = 0; i < sizeof(full); i++)
    full[i] = (uint8_t)(i * 17u);
  for (t += 10; t < 100000; t += 10) {
    step(&a, &b, t);
    sent = fabric_ble_host_send(a.host, full, FABRIC_BLE_MAX_APP_PAYLOAD, t);
    if (sent == 0)
      break;
    assert(sent == FABRIC_BLE_BUSY);
  }
  assert(sent == 0);
  n = 0;
  for (t += 10; t < 130000; t += 10) {
    step(&a, &b, t);
    n = fabric_ble_host_take_message(b.host, out, sizeof(out));
    if (n > 0)
      break;
    assert(n == 0);
  }
  assert(n == (int)FABRIC_BLE_MAX_APP_PAYLOAD &&
         memcmp(full, out, FABRIC_BLE_MAX_APP_PAYLOAD) == 0);
  assert(fabric_ble_host_send(a.host, full, sizeof(full), t) ==
         FABRIC_BLE_TOO_LARGE);
  /* Both peers send while the other's delivery receipt is still pending. */
  a.drop_next_app = 1;
  unsigned a_sent = 0, b_sent = 0, a_received = 0, b_received = 0;
  uint8_t duplex[180], large_duplex[896];
  memset(duplex, 0x42, sizeof(duplex));
  memset(large_duplex, 0x73, sizeof(large_duplex));
  uint64_t duplex_end = t + 60000;
  for (t += 10; t < duplex_end; t += 10) {
    if (a_sent < 12) {
      int result = fabric_ble_host_send(a.host, duplex, sizeof(duplex), t);
      assert(result == 0 || result == FABRIC_BLE_BUSY);
      if (!result) a_sent++;
    }
    if (b_sent < 12) {
      int result = fabric_ble_host_send(b.host, large_duplex, sizeof(large_duplex), t);
      assert(result == 0 || result == FABRIC_BLE_BUSY);
      if (!result) b_sent++;
    }
    step(&a, &b, t);
    int received = ((t / 10) % 17 == 0) ? fabric_ble_host_take_message(a.host, out, sizeof(out)) : 0;
    if (received) { assert(received == (int)sizeof(large_duplex)); a_received++; }
    received = ((t / 10) % 19 == 0) ? fabric_ble_host_take_message(b.host, out, sizeof(out)) : 0;
    if (received) { assert(received == (int)sizeof(duplex)); b_received++; }
    if (a_received == 12 && b_received == 12) break;
  }
  assert(a_received == 12 && b_received == 12);
  /* An already acknowledged record can still have a delayed retry fragment
   * in the platform queue. A fresh offset-zero frame of the same length must
   * replace that incomplete prefix and still pass Session AEAD/sequence checks. */
  uint8_t replay_probe[100];
  memset(replay_probe, 0x31, sizeof(replay_probe));
  a.capture_first = 1;
  int probe_sent = 0, probe_received = 0;
  uint64_t probe_end = t + 10000;
  for (t += 10; t < probe_end; t += 10) {
    if (!probe_sent) {
      int result = fabric_ble_host_send(a.host, replay_probe, sizeof(replay_probe), t);
      assert(result == 0 || result == FABRIC_BLE_BUSY);
      probe_sent = result == 0;
    }
    step(&a, &b, t);
    n = fabric_ble_host_take_message(b.host, out, sizeof(out));
    if (n) { assert(n == sizeof(replay_probe)); probe_received++; }
    if (probe_received && !a.size && !b.size) break;
  }
  assert(probe_received == 1 && a.saved_size);
  for (unsigned i = 0; i < 100; i++, t += 10) step(&a, &b, t);
  assert(fabric_ble_host_feed(b.host, a.saved_first, a.saved_size, t) == 0);
  memset(replay_probe, 0x32, sizeof(replay_probe));
  assert(fabric_ble_host_send(a.host, replay_probe, sizeof(replay_probe), t) == 0);
  probe_received = 0;
  probe_end = t + 10000;
  for (t += 10; t < probe_end; t += 10) {
    step(&a, &b, t);
    n = fabric_ble_host_take_message(b.host, out, sizeof(out));
    if (n) { assert(n == sizeof(replay_probe)); assert(!memcmp(out, replay_probe, n)); probe_received++; break; }
  }
  assert(probe_received == 1);
  /* A slow app consumer must not block a lost transport receipt's replay.
   * The sender is live even while the receiver keeps its single app slot. */
  for(unsigned i=0;i<100;i++,t+=10)step(&a,&b,t);
  uint8_t ack_probe[200];memset(ack_probe,0x71,sizeof(ack_probe));
  b.drop_next_receipt=1;
  assert(fabric_ble_host_send(a.host,ack_probe,sizeof(ack_probe),t)==0);
  uint64_t hold_until=t+16000;
  for(t+=10;t<hold_until;t+=10)step(&a,&b,t);
  n=fabric_ble_host_take_message(b.host,out,sizeof(out));
  assert(n==sizeof(ack_probe)&&!memcmp(out,ack_probe,sizeof(ack_probe)));
  assert(!fabric_ble_host_take_message(b.host,out,sizeof(out)));
  assert(fabric_ble_host_send(a.host,message,sizeof(message),t)==0);
  a.enabled = 0;
  assert(fabric_ble_host_authorized(a.host, 1) == 0);
  int revoked = fabric_ble_host_send(a.host, message, sizeof(message), t);
  assert(revoked == FABRIC_BLE_UNAUTHORIZED || revoked == FABRIC_BLE_BUSY);
  fabric_ble_host_destroy(a.host);
  fabric_ble_host_destroy(b.host);
  puts("C ABI encrypted Session + MTU20 lossy GATT + application roundtrip + "
       "revocation passed");
  return 0;
}
