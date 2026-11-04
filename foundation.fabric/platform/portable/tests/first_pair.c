#define _POSIX_C_SOURCE 200809L
#include "fabric_identity_posix.h"
#include "fabric_pairing_host.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef FABRIC_TEST_TMP
#define FABRIC_TEST_TMP "/tmp"
#endif
struct side {
  fabric_pairing_host *host;
  struct side *peer;
  struct fabric_identity_posix store;
  uint8_t packet[512];
  size_t size;
  int writes, dropped;
};
static int random_bytes(void *ctx, uint8_t *out, size_t n) {
  memset(out, *(uint8_t *)ctx, n);
  return 0;
}
static int packet(void *ctx, const uint8_t *p, size_t n) {
  struct side *s = ctx;
  if (s->size)
    return FABRIC_BLE_BUSY;
  memcpy(s->packet, p, n);
  s->size = n;
  return 0;
}
static int32_t persist(void *ctx, const uint8_t *p, const uint8_t *id) {
  struct side *s = ctx;
  int r = fabric_identity_posix_persist_peer(&s->store, p, id);
  if (r)
    s->writes++;
  return r;
}
static void step(struct side *a, struct side *b, uint64_t t) {
  struct side *sides[] = {a, b};
  for (unsigned i = 0; i < 2; i++) {
    int r = fabric_pairing_host_poll(sides[i]->host, t);
    if (r != 0 && r != FABRIC_BLE_BUSY)
      fprintf(stderr, "poll side%u t=%llu error%d phase%d\n", i,
              (unsigned long long)t, r,
              fabric_pairing_host_phase(sides[i]->host));
    assert(r == 0 || r == FABRIC_BLE_BUSY);
  }
  for (unsigned i = 0; i < 2; i++) {
    struct side *s = sides[i];
    if (!s->size)
      continue;
    if (!s->dropped) {
      s->dropped = 1;
      s->size = 0;
      continue;
    }
    int r = fabric_pairing_host_feed(s->peer->host, s->packet, s->size, t);
    if (r != 0 && r != FABRIC_BLE_BUSY)
      fprintf(stderr, "feed side%u t=%llu error%d phase%d\n", i,
              (unsigned long long)t, r,
              fabric_pairing_host_phase(s->peer->host));
    assert(r == 0 || r == FABRIC_BLE_BUSY);
    s->size = 0;
  }
}
int main(void) {
  char dir[] = FABRIC_TEST_TMP "/fabric-first-pair-XXXXXX";
  assert(mkdtemp(dir));
  char ad[256], bd[256];
  snprintf(ad, sizeof(ad), "%s/a", dir);
  snprintf(bd, sizeof(bd), "%s/b", dir);
  struct side a = {.store = {.directory = ad}},
              b = {.store = {.directory = bd}};
  a.peer = &b;
  b.peer = &a;
  uint8_t as[32], bs[32], ap[32], bp[32], id[32], ae[32], be[32], r = 1;
  assert(fabric_identity_posix_load(&a.store, random_bytes, &r, as) == 0);
  r = 2;
  assert(fabric_identity_posix_load(&b.store, random_bytes, &r, bs) == 0);
  assert(fabric_identity_public(as, ap, id) == 0);
  assert(fabric_identity_public(bs, bp, id) == 0);
  memset(ae, 3, 32);
  memset(be, 4, 32);
  assert(!fabric_identity_posix_trusted(&a.store, bp));
  assert(!fabric_identity_posix_trusted(&b.store, ap));
  struct fabric_pairing_host_config ac = {.ctx = &a,
                                          .send_packet = packet,
                                          .persist_peer = persist,
                                          .packet_bytes = 20},
                                    bc = ac;
  bc.ctx = &b;
  a.host = fabric_pairing_host_create(&ac, as, ae, bp, 0);
  b.host = fabric_pairing_host_create(&bc, bs, be, NULL, 0);
  assert(a.host && b.host);
  uint64_t t;
  int code = -1;
  for (t = 1; t < 20000; t += 10) {
    step(&a, &b, t);
    code = fabric_pairing_host_code(a.host);
    if (code >= 0 && fabric_pairing_host_code(b.host) >= 0)
      break;
  }
  assert(t < 20000 && code == fabric_pairing_host_code(b.host));
  assert(a.writes == 0 && b.writes == 0);
  assert(fabric_pairing_host_confirm(a.host, (uint32_t)code, 1, t) == 0);
  for (unsigned i = 0; i < 50; i++) {
    t += 10;
    step(&a, &b, t);
  }
  assert(a.writes == 0 &&
         b.writes == 0); // phone confirmation alone never persists
  assert(fabric_pairing_host_confirm(b.host, (uint32_t)code, 1, t) == 0);
  for (t += 10; t < 40000; t += 10) {
    step(&a, &b, t);
    if (fabric_pairing_host_phase(a.host) == 4 &&
        fabric_pairing_host_phase(b.host) == 4 &&
        fabric_pairing_host_flushed(a.host) &&
        fabric_pairing_host_flushed(b.host) && !a.size && !b.size)
      break;
  }
  assert(t < 40000 && a.writes == 1 && b.writes == 1);
  assert(fabric_identity_posix_trusted(&a.store, bp) &&
         fabric_identity_posix_trusted(&b.store, ap));
  uint8_t restored[32];
  assert(fabric_device_id(bp, id) == 0);
  assert(fabric_identity_posix_peer(&a.store, id, restored) == 0 &&
         memcmp(restored, bp, 32) == 0);
  fabric_pairing_host_destroy(a.host);
  fabric_pairing_host_destroy(b.host);
  puts("First Fabric pairing over lossy MTU20 GATT: identity creation, "
       "Challenge/SAS, both explicit confirms, durable trust/reopen passed");
  return 0;
}
