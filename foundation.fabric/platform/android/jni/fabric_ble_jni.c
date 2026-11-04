/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#include "fabric_ble_host.h"
#include "fabric_identity_posix.h"
#include "fabric_pairing.h"
#include "fabric_pairing_host.h"
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define PREFIX Java_com_mocharealm_foundation_fabric_ble_FabricBleNative_
#define JNI_NAME_(a, b) a##b
#define JNI_NAME(a, b) JNI_NAME_(a, b)
#define METHOD(n) JNI_NAME(PREFIX, n)
struct slot {
  jlong id;
  fabric_ble_host *host;
  fabric_pairing *pairing;
  fabric_pairing_host *pair_host;
  char directory[401];
  uint8_t peer_public[32];
  struct fabric_identity_posix store;
  jobject policy;
  jmethodID check;
  JNIEnv *env;
  uint8_t packet[512];
  size_t length;
};
static struct slot slots[64];
static jlong sequence;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local int entered;
static struct slot *acquire(JNIEnv *env, jlong id) {
  if (entered)
    return NULL;
  pthread_mutex_lock(&mutex);
  entered = 1;
  for (size_t i = 0; i < 64; i++)
    if (slots[i].id == id && id) {
      slots[i].env = env;
      return &slots[i];
    }
  entered = 0;
  pthread_mutex_unlock(&mutex);
  return NULL;
}
static void release(struct slot *s) {
  s->env = NULL;
  entered = 0;
  pthread_mutex_unlock(&mutex);
}
static int packet(void *ctx, const uint8_t *p, size_t n) {
  struct slot *s = ctx;
  if (s->length)
    return FABRIC_BLE_BUSY;
  if (n > 512)
    return FABRIC_BLE_TOO_LARGE;
  memcpy(s->packet, p, n);
  s->length = n;
  return 0;
}
static int32_t authorize(void *ctx, const uint8_t c[140], uint8_t action) {
  struct slot *s = ctx;
  JNIEnv *e = s->env;
  if (!e || (*e)->ExceptionCheck(e))
    return 0;
  if (s->directory[0] &&
      !fabric_identity_posix_trusted(&s->store, s->peer_public))
    return 0;
  jbyteArray bytes = (*e)->NewByteArray(e, 140);
  if (!bytes)
    return 0;
  (*e)->SetByteArrayRegion(e, bytes, 0, 140, (const jbyte *)c);
  jboolean ok =
      (*e)->CallBooleanMethod(e, s->policy, s->check, bytes, (jint)action);
  (*e)->DeleteLocalRef(e, bytes);
  return !(*e)->ExceptionCheck(e) && ok == JNI_TRUE;
}
static int copy32(JNIEnv *e, jbyteArray b, uint8_t out[32]) {
  if (!b || (*e)->GetArrayLength(e, b) != 32)
    return -1;
  (*e)->GetByteArrayRegion(e, b, 0, 32, (jbyte *)out);
  return (*e)->ExceptionCheck(e) ? -1 : 0;
}
JNIEXPORT jlong JNICALL METHOD(create)(JNIEnv *e, jclass cls, jbyteArray seed,
                                       jbyteArray peer, jbyteArray entropy,
                                       jobject policy, jint mtu) {
  (void)cls;
  uint8_t a[32], b[32], c[32];
  jlong result = 0;
  if (entered || !policy || copy32(e, seed, a) || copy32(e, peer, b) ||
      copy32(e, entropy, c))
    return 0;
  pthread_mutex_lock(&mutex);
  entered = 1;
  for (size_t i = 0; i < 64; i++)
    if (!slots[i].id) {
      struct slot *s = &slots[i];
      s->policy = (*e)->NewGlobalRef(e, policy);
      if (!s->policy)
        break;
      jclass type = (*e)->GetObjectClass(e, policy);
      s->check = (*e)->GetMethodID(e, type, "authorize", "([BI)Z");
      (*e)->DeleteLocalRef(e, type);
      if (!s->check) {
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      struct fabric_ble_host_config config = {.ctx = s,
                                              .send_packet = packet,
                                              .authorize = authorize,
                                              .packet_bytes = (size_t)mtu};
      s->host = fabric_ble_host_create(&config, a, b, c);
      if (!s->host) {
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      if (sequence == INT64_MAX) {
        fabric_ble_host_destroy(s->host);
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      s->id = ++sequence;
      result = s->id;
      break;
    }
  volatile uint8_t *v = a;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
  v = c;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
  entered = 0;
  pthread_mutex_unlock(&mutex);
  return result;
}
JNIEXPORT jint JNICALL METHOD(propose)(JNIEnv *e, jclass cls, jlong id,
                                       jbyteArray context) {
  (void)cls;
  uint8_t c[140];
  if (!context || (*e)->GetArrayLength(e, context) != 140)
    return -1;
  (*e)->GetByteArrayRegion(e, context, 0, 140, (jbyte *)c);
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_ble_host_propose(s->host, c);
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(feed)(JNIEnv *e, jclass cls, jlong id,
                                    jbyteArray fragment, jlong now) {
  (void)cls;
  uint8_t p[512];
  if (!fragment || now < 0)
    return -1;
  jsize n = (*e)->GetArrayLength(e, fragment);
  if (n < 6 || n > 512)
    return -1;
  (*e)->GetByteArrayRegion(e, fragment, 0, n, (jbyte *)p);
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r =
      (s->pair_host
           ? fabric_pairing_host_feed(s->pair_host, p, (size_t)n, (uint64_t)now)
           : fabric_ble_host_feed(s->host, p, (size_t)n, (uint64_t)now));
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(poll)(JNIEnv *e, jclass cls, jlong id,
                                    jlong now) {
  (void)cls;
  if (now < 0)
    return -1;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = (s->pair_host ? fabric_pairing_host_poll(s->pair_host, (uint64_t)now)
                        : fabric_ble_host_poll(s->host, (uint64_t)now));
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(send)(JNIEnv *e, jclass cls, jlong id,
                                    jbyteArray payload, jlong now) {
  (void)cls;
  uint8_t p[FABRIC_BLE_MAX_APP_PAYLOAD];
  if (!payload || now < 0)
    return -1;
  jsize n = (*e)->GetArrayLength(e, payload);
  if (n < 1 || n > (jsize)FABRIC_BLE_MAX_APP_PAYLOAD)
    return -4;
  (*e)->GetByteArrayRegion(e, payload, 0, n, (jbyte *)p);
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_ble_host_send(s->host, p, (size_t)n, (uint64_t)now);
  volatile uint8_t *v = p;
  for (jsize i = 0; i < n; i++)
    v[i] = 0;
  release(s);
  return r;
}
JNIEXPORT jbyteArray JNICALL METHOD(takePacket)(JNIEnv *e, jclass cls,
                                                jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  jbyteArray out = NULL;
  if (s->length) {
    out = (*e)->NewByteArray(e, (jsize)s->length);
    if (out) {
      (*e)->SetByteArrayRegion(e, out, 0, (jsize)s->length,
                               (const jbyte *)s->packet);
      s->length = 0;
    }
  }
  release(s);
  return out;
}
JNIEXPORT jbyteArray JNICALL METHOD(takeMessage)(JNIEnv *e, jclass cls,
                                                 jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  uint8_t p[FABRIC_BLE_MAX_APP_PAYLOAD];
  int n = fabric_ble_host_take_message(s->host, p, sizeof(p));
  jbyteArray out = NULL;
  if (n > 0) {
    out = (*e)->NewByteArray(e, n);
    if (out)
      (*e)->SetByteArrayRegion(e, out, 0, n, (const jbyte *)p);
    volatile uint8_t *v = p;
    for (int i = 0; i < n; i++)
      v[i] = 0;
  }
  release(s);
  return out;
}
JNIEXPORT jint JNICALL METHOD(phase)(JNIEnv *e, jclass cls, jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = s->pair_host ? fabric_pairing_host_phase(s->pair_host)
                       : fabric_ble_host_phase(s->host);
  release(s);
  return r;
}
JNIEXPORT void JNICALL METHOD(close)(JNIEnv *e, jclass cls, jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return;
  fabric_ble_host_destroy(s->host);
  fabric_pairing_host_destroy(s->pair_host);
  if (s->pairing) {
    fabric_pairing_destroy(s->pairing);
    free(s->pairing);
  }
  (*e)->DeleteGlobalRef(e, s->policy);
  memset(s, 0, sizeof(*s));
  release(s);
}

JNIEXPORT jint JNICALL METHOD(maxPayload)(JNIEnv *e, jclass cls, jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_ble_host_max_payload(s->host);
  release(s);
  return r;
}
JNIEXPORT jbyteArray JNICALL METHOD(finalizeContext)(JNIEnv *e, jclass cls,
                                                     jbyteArray input) {
  (void)cls;
  uint8_t c[140];
  if (!input || (*e)->GetArrayLength(e, input) != 140)
    return NULL;
  (*e)->GetByteArrayRegion(e, input, 0, 140, (jbyte *)c);
  if (fabric_ble_context_finalize(c) < 0)
    return NULL;
  jbyteArray out = (*e)->NewByteArray(e, 140);
  if (out)
    (*e)->SetByteArrayRegion(e, out, 0, 140, (const jbyte *)c);
  return out;
}
JNIEXPORT jbyteArray JNICALL METHOD(identityPublic)(JNIEnv *e, jclass cls,
                                                    jbyteArray seed) {
  (void)cls;
  uint8_t s[32], p[64];
  if (copy32(e, seed, s))
    return NULL;
  int r = fabric_identity_public(s, p, p + 32);
  volatile uint8_t *v = s;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
  if (r < 0)
    return NULL;
  jbyteArray out = (*e)->NewByteArray(e, 64);
  if (out)
    (*e)->SetByteArrayRegion(e, out, 0, 64, (const jbyte *)p);
  return out;
}
static int32_t persist_peer(void *ctx, const uint8_t public[32],
                            const uint8_t id[32]) {
  struct slot *s = ctx;
  JNIEnv *e = s->env;
  if (!e || (*e)->ExceptionCheck(e))
    return 0;
  jbyteArray p = (*e)->NewByteArray(e, 32), d = (*e)->NewByteArray(e, 32);
  if (!p || !d) {
    if (p)
      (*e)->DeleteLocalRef(e, p);
    if (d)
      (*e)->DeleteLocalRef(e, d);
    return 0;
  }
  (*e)->SetByteArrayRegion(e, p, 0, 32, (const jbyte *)public);
  (*e)->SetByteArrayRegion(e, d, 0, 32, (const jbyte *)id);
  jboolean ok = (*e)->CallBooleanMethod(e, s->policy, s->check, p, d);
  (*e)->DeleteLocalRef(e, p);
  (*e)->DeleteLocalRef(e, d);
  return !(*e)->ExceptionCheck(e) && ok == JNI_TRUE;
}
JNIEXPORT jlong JNICALL METHOD(requestPairing)(JNIEnv *e, jclass cls,
                                               jbyteArray seed,
                                               jbyteArray entropy,
                                               jbyteArray expected,
                                               jobject persist, jlong now) {
  (void)cls;
  uint8_t a[32], b[32], c[32];
  if (entered || !persist || now < 0 || copy32(e, seed, a) ||
      copy32(e, entropy, b) || (expected && copy32(e, expected, c)))
    return 0;
  pthread_mutex_lock(&mutex);
  entered = 1;
  jlong id = 0;
  for (size_t i = 0; i < 64; i++)
    if (!slots[i].id) {
      struct slot *s = &slots[i];
      s->policy = (*e)->NewGlobalRef(e, persist);
      if (!s->policy)
        break;
      jclass type = (*e)->GetObjectClass(e, persist);
      s->check = (*e)->GetMethodID(e, type, "persistPeer", "([B[B)Z");
      (*e)->DeleteLocalRef(e, type);
      if (!s->check) {
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      s->pairing = malloc(fabric_pairing_size());
      if (!s->pairing ||
          fabric_request_pairing(s->pairing, fabric_pairing_size(), a, b,
                                 expected ? c : NULL, (uint64_t)now) < 0 ||
          sequence == INT64_MAX) {
        free(s->pairing);
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      s->id = ++sequence;
      id = s->id;
      break;
    }
  volatile uint8_t *v = a;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
  v = b;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
  entered = 0;
  pthread_mutex_unlock(&mutex);
  return id;
}
JNIEXPORT jbyteArray JNICALL METHOD(pairingHello)(JNIEnv *e, jclass cls,
                                                  jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  uint8_t p[FABRIC_BLE_HELLO_BYTES];
  int n = fabric_pairing_hello(s->pairing, p, sizeof(p));
  jbyteArray out = NULL;
  if (n > 0) {
    out = (*e)->NewByteArray(e, n);
    if (out)
      (*e)->SetByteArrayRegion(e, out, 0, n, (const jbyte *)p);
  }
  release(s);
  return out;
}
JNIEXPORT jint JNICALL METHOD(pairingReceiveHello)(JNIEnv *e, jclass cls,
                                                   jlong id, jbyteArray hello,
                                                   jlong now) {
  (void)cls;
  uint8_t p[FABRIC_BLE_HELLO_BYTES];
  if (!hello || now < 0 ||
      (*e)->GetArrayLength(e, hello) != FABRIC_BLE_HELLO_BYTES)
    return -1;
  (*e)->GetByteArrayRegion(e, hello, 0, FABRIC_BLE_HELLO_BYTES, (jbyte *)p);
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_receive_hello(s->pairing, p, sizeof(p), (uint64_t)now);
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(pairingCode)(JNIEnv *e, jclass cls, jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_code(s->pairing);
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(pairingPhase)(JNIEnv *e, jclass cls, jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_phase(s->pairing);
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(pairingConfirm)(JNIEnv *e, jclass cls, jlong id,
                                              jint code, jboolean accepted,
                                              jlong now) {
  (void)cls;
  if (now < 0 || code < 0 || code > 999999)
    return -1;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_confirm(s->pairing, (uint32_t)code, accepted ? 1 : 0,
                                 (uint64_t)now);
  release(s);
  return r;
}
JNIEXPORT jbyteArray JNICALL METHOD(completePairing)(JNIEnv *e, jclass cls,
                                                     jlong id, jlong now) {
  (void)cls;
  if (now < 0)
    return NULL;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  uint8_t p[FABRIC_BLE_HELLO_BYTES];
  int n = fabric_complete_pairing(s->pairing, (uint64_t)now, p, sizeof(p),
                                  persist_peer, s);
  jbyteArray out = NULL;
  if (n > 0) {
    out = (*e)->NewByteArray(e, n);
    if (out)
      (*e)->SetByteArrayRegion(e, out, 0, n, (const jbyte *)p);
  }
  release(s);
  return out;
}
JNIEXPORT jint JNICALL METHOD(pairingReceive)(JNIEnv *e, jclass cls, jlong id,
                                              jbyteArray record, jlong now) {
  (void)cls;
  uint8_t p[FABRIC_BLE_HELLO_BYTES];
  if (!record || now < 0)
    return -1;
  jsize n = (*e)->GetArrayLength(e, record);
  if (n < 25 || n > (jsize)sizeof(p))
    return -1;
  (*e)->GetByteArrayRegion(e, record, 0, n, (jbyte *)p);
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_receive(s->pairing, p, (size_t)n, (uint64_t)now);
  release(s);
  return r;
}
JNIEXPORT jbyteArray JNICALL METHOD(pairingPeer)(JNIEnv *e, jclass cls,
                                                 jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  uint8_t p[64];
  int r = fabric_pairing_peer(s->pairing, p, p + 32);
  jbyteArray out = NULL;
  if (r == 0) {
    out = (*e)->NewByteArray(e, 64);
    if (out)
      (*e)->SetByteArrayRegion(e, out, 0, 64, (const jbyte *)p);
  }
  release(s);
  return out;
}
static int random_bytes(void *ctx, uint8_t *out, size_t n) {
  (void)ctx;
  int fd = open("/dev/urandom", O_RDONLY);
  if (fd < 0)
    return -1;
  while (n) {
    ssize_t r = read(fd, out, n);
    if (r < 0 && errno == EINTR)
      continue;
    if (r <= 0) {
      close(fd);
      return -1;
    }
    out += r;
    n -= (size_t)r;
  }
  close(fd);
  return 0;
}
static int directory_string(JNIEnv *e, jstring path, char out[401]) {
  if (!path)
    return -1;
  const char *p = (*e)->GetStringUTFChars(e, path, NULL);
  if (!p)
    return -1;
  size_t n = strlen(p);
  int ok = n > 0 && n <= 400 && p[0] == '/';
  if (ok)
    memcpy(out, p, n + 1);
  (*e)->ReleaseStringUTFChars(e, path, p);
  return ok ? 0 : -1;
}
static void clear_secret(uint8_t s[32]) {
  volatile uint8_t *v = s;
  for (size_t i = 0; i < 32; i++)
    v[i] = 0;
}
JNIEXPORT jbyteArray JNICALL METHOD(identityInfo)(JNIEnv *e, jclass cls,
                                                  jstring directory) {
  (void)cls;
  char root[401];
  uint8_t seed[32], p[64];
  if (directory_string(e, directory, root))
    return NULL;
  struct fabric_identity_posix store = {.directory = root};
  if (fabric_identity_posix_load(&store, random_bytes, NULL, seed) < 0)
    return NULL;
  int r = fabric_identity_public(seed, p, p + 32);
  clear_secret(seed);
  if (r < 0)
    return NULL;
  jbyteArray out = (*e)->NewByteArray(e, 64);
  if (out)
    (*e)->SetByteArrayRegion(e, out, 0, 64, (const jbyte *)p);
  return out;
}
JNIEXPORT jboolean JNICALL METHOD(isTrusted)(JNIEnv *e, jclass cls,
                                             jstring directory,
                                             jbyteArray public) {
  (void)cls;
  char root[401];
  uint8_t p[32];
  if (directory_string(e, directory, root) || copy32(e, public, p))
    return JNI_FALSE;
  struct fabric_identity_posix store = {.directory = root};
  return fabric_identity_posix_trusted(&store, p) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jbyteArray JNICALL METHOD(trustedPeer)(JNIEnv *e, jclass cls,
                                                 jstring directory,
                                                 jbyteArray id) {
  (void)cls;
  char root[401];
  uint8_t d[32], p[32];
  if (directory_string(e, directory, root) || copy32(e, id, d))
    return NULL;
  struct fabric_identity_posix store = {.directory = root};
  if (fabric_identity_posix_peer(&store, d, p) < 0)
    return NULL;
  jbyteArray out = (*e)->NewByteArray(e, 32);
  if (out)
    (*e)->SetByteArrayRegion(e, out, 0, 32, (const jbyte *)p);
  return out;
}
static int32_t persist_host_peer(void *ctx, const uint8_t p[32],
                                 const uint8_t id[32]) {
  struct slot *s = ctx;
  return fabric_identity_posix_persist_peer(&s->store, p, id);
}
JNIEXPORT jlong JNICALL METHOD(requestPairingHost)(JNIEnv *e, jclass cls,
                                                   jstring directory,
                                                   jbyteArray entropy,
                                                   jbyteArray expected,
                                                   jint mtu, jlong now) {
  (void)cls;
  char root[401];
  uint8_t seed[32], ent[32], peer[32];
  if (entered || now < 0 || directory_string(e, directory, root) ||
      copy32(e, entropy, ent) || (expected && copy32(e, expected, peer)))
    return 0;
  struct fabric_identity_posix store = {.directory = root};
  if (fabric_identity_posix_load(&store, random_bytes, NULL, seed) < 0)
    return 0;
  pthread_mutex_lock(&mutex);
  entered = 1;
  jlong id = 0;
  for (size_t i = 0; i < 64; i++)
    if (!slots[i].id) {
      struct slot *s = &slots[i];
      memcpy(s->directory, root, strlen(root) + 1);
      s->store.directory = s->directory;
      struct fabric_pairing_host_config config = {.ctx = s,
                                                  .send_packet = packet,
                                                  .persist_peer =
                                                      persist_host_peer,
                                                  .packet_bytes = (size_t)mtu};
      s->pair_host = fabric_pairing_host_create(
          &config, seed, ent, expected ? peer : NULL, (uint64_t)now);
      if (!s->pair_host || sequence == INT64_MAX) {
        fabric_pairing_host_destroy(s->pair_host);
        memset(s, 0, sizeof(*s));
        break;
      }
      s->id = ++sequence;
      id = s->id;
      break;
    }
  clear_secret(seed);
  clear_secret(ent);
  entered = 0;
  pthread_mutex_unlock(&mutex);
  return id;
}
JNIEXPORT jint JNICALL METHOD(pairingHostCode)(JNIEnv *e, jclass cls,
                                               jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_host_code(s->pair_host);
  release(s);
  return r;
}
JNIEXPORT jint JNICALL METHOD(pairingHostConfirm)(JNIEnv *e, jclass cls,
                                                  jlong id, jint code,
                                                  jboolean accepted,
                                                  jlong now) {
  (void)cls;
  if (now < 0 || code < 0 || code > 999999)
    return -1;
  struct slot *s = acquire(e, id);
  if (!s)
    return -1;
  int r = fabric_pairing_host_confirm(s->pair_host, (uint32_t)code,
                                      accepted ? 1 : 0, (uint64_t)now);
  release(s);
  return r;
}
JNIEXPORT jboolean JNICALL METHOD(pairingHostFlushed)(JNIEnv *e, jclass cls,
                                                      jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return JNI_FALSE;
  int r = fabric_pairing_host_flushed(s->pair_host) && s->length == 0;
  release(s);
  return r ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jbyteArray JNICALL METHOD(pairingHostPeer)(JNIEnv *e, jclass cls,
                                                     jlong id) {
  (void)cls;
  struct slot *s = acquire(e, id);
  if (!s)
    return NULL;
  uint8_t p[64];
  int r = fabric_pairing_host_peer(s->pair_host, p, p + 32);
  jbyteArray out = NULL;
  if (r == 0) {
    out = (*e)->NewByteArray(e, 64);
    if (out)
      (*e)->SetByteArrayRegion(e, out, 0, 64, (const jbyte *)p);
  }
  release(s);
  return out;
}
JNIEXPORT jlong JNICALL METHOD(createFromIdentity)(JNIEnv *e, jclass cls,
                                                   jstring directory,
                                                   jbyteArray peer,
                                                   jbyteArray entropy,
                                                   jobject policy, jint mtu) {
  (void)cls;
  char root[401];
  uint8_t seed[32], p[32], ent[32];
  if (entered || !policy || directory_string(e, directory, root) ||
      copy32(e, peer, p) || copy32(e, entropy, ent))
    return 0;
  struct fabric_identity_posix store = {.directory = root};
  if (!fabric_identity_posix_trusted(&store, p) ||
      fabric_identity_posix_load(&store, random_bytes, NULL, seed) < 0)
    return 0;
  pthread_mutex_lock(&mutex);
  entered = 1;
  jlong id = 0;
  for (size_t i = 0; i < 64; i++)
    if (!slots[i].id) {
      struct slot *s = &slots[i];
      memcpy(s->directory, root, strlen(root) + 1);
      s->store.directory = s->directory;
      s->policy = (*e)->NewGlobalRef(e, policy);
      if (!s->policy)
        break;
      jclass type = (*e)->GetObjectClass(e, policy);
      s->check = (*e)->GetMethodID(e, type, "authorize", "([BI)Z");
      (*e)->DeleteLocalRef(e, type);
      if (!s->check) {
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      struct fabric_ble_host_config config = {.ctx = s,
                                              .send_packet = packet,
                                              .authorize = authorize,
                                              .packet_bytes = (size_t)mtu};
      memcpy(s->peer_public, p, 32);
      s->host = fabric_ble_host_create(&config, seed, p, ent);
      if (!s->host || sequence == INT64_MAX) {
        fabric_ble_host_destroy(s->host);
        (*e)->DeleteGlobalRef(e, s->policy);
        memset(s, 0, sizeof(*s));
        break;
      }
      s->id = ++sequence;
      id = s->id;
      break;
    }
  clear_secret(seed);
  clear_secret(ent);
  entered = 0;
  pthread_mutex_unlock(&mutex);
  return id;
}
