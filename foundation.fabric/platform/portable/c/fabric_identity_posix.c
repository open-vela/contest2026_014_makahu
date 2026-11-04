/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#define _POSIX_C_SOURCE 200809L
#include "fabric_identity_posix.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#if defined(__linux__)
#include <sys/file.h>
#endif
#include <unistd.h>
#ifdef __NuttX__
#include <nuttx/config.h>
#endif
#if defined(__NuttX__) || defined(__linux__)
#include <sys/statfs.h>
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW                                                             \
  0 /* private caller-owned parent + lstat checks remain required */
#endif
#define TRUST_LIMIT 16384
static void wipe(void *p, size_t n) {
  volatile uint8_t *v = p;
  while (n--)
    *v++ = 0;
}
static int path(struct fabric_identity_posix *s, const char *name,
                char out[512]) {
  if (!s || !s->directory || strlen(s->directory) > 400 || !name)
    return -1;
  int n = snprintf(out, 512, "%s/%s", s->directory, name);
  return n > 0 && n < 512 ? 0 : -1;
}
static int directory(struct fabric_identity_posix *s) {
  struct stat st;
  if (!s || !s->directory)
    return -1;
  if (mkdir(s->directory, 0700) < 0 && errno != EEXIST)
    return -1;
  if (lstat(s->directory, &st) < 0 || !S_ISDIR(st.st_mode) ||
      (st.st_mode & 077) != 0 || st.st_uid != geteuid())
    return -1;
#ifdef __NuttX__
#ifdef CONFIG_FS_LITTLEFS_ATTR_UPDATE
  struct statfs fs;
  if (statfs(s->directory, &fs) != 0 || fs.f_type != LITTLEFS_SUPER_MAGIC)
    return -1;
#else
  return -1;
#endif
#elif defined(__linux__)
  struct statfs fs;
  if (statfs(s->directory, &fs) != 0 || fs.f_type == 0x01021994)
    return -1; /* tmpfs is not durable */
#endif
  return 0;
}
static int read_file(struct fabric_identity_posix *s, const char *name,
                     uint8_t *out, size_t capacity, int secret) {
  char p[512];
  struct stat st;
  if (path(s, name, p))
    return -1;
  if (lstat(p, &st) < 0)
    return errno == ENOENT ? -2 : -1;
  if (!S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
      (st.st_mode & (secret ? 077 : 022)) != 0)
    return -1;
  int fd = open(p, O_RDONLY | O_NOFOLLOW);
  if (fd < 0)
    return -1;
  if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_size < 0 ||
      (size_t)st.st_size > capacity) {
    close(fd);
    return -1;
  }
  size_t n = 0;
  while (n < capacity) {
    ssize_t r = read(fd, out + n, capacity - n);
    if (r < 0 && errno == EINTR)
      continue;
    if (r < 0) {
      close(fd);
      return -1;
    }
    if (r == 0)
      break;
    n += (size_t)r;
  }
  uint8_t extra;
  ssize_t rest = read(fd, &extra, 1);
  close(fd);
  return rest == 0 ? (int)n : -1;
}
static int write_all(int fd, const uint8_t *p, size_t n) {
  while (n) {
    ssize_t r = write(fd, p, n);
    if (r < 0 && errno == EINTR)
      continue;
    if (r <= 0)
      return -1;
    p += r;
    n -= (size_t)r;
  }
  return fsync(fd);
}
static int sync_directory(struct fabric_identity_posix *s) {
#ifdef __NuttX__
#ifdef CONFIG_FS_LITTLEFS_ATTR_UPDATE
  /* This NuttX LittleFS port synchronously commits lfs_rename/mkdir metadata
   * through cfg.sync (BIOC_FLUSH). A directory fd is a DIR, NOT lfs_file_t;
   * fsync on it would be invalid. Restrict this adapter to verified LittleFS
   * with real attributes. The configured block driver must support flush. */
  struct statfs fs;
  return statfs(s->directory, &fs) == 0 && fs.f_type == LITTLEFS_SUPER_MAGIC
             ? 0
             : -1;
#else
  return -1;
#endif
#else
  int fd = open(s->directory, O_RDONLY | O_NOFOLLOW);
  if (fd < 0)
    return -1;
  int r = fsync(fd);
  close(fd);
  return r;
#endif
}
static int atomic_file(struct fabric_identity_posix *s, const char *name,
                       const uint8_t *p, size_t n) {
  char target[512], tmp[512], tmp_name[96];
  struct stat st;
  if (snprintf(tmp_name, sizeof(tmp_name), "%s.pending", name) >=
          (int)sizeof(tmp_name) ||
      path(s, name, target) || path(s, tmp_name, tmp))
    return -1;
  int fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
  if (fd < 0)
    return -1;
  if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || (st.st_mode & 077) != 0 ||
      write_all(fd, p, n) < 0) {
    close(fd);
    return -1;
  }
  if (close(fd) < 0 || rename(tmp, target) < 0)
    return -1;
  return sync_directory(s);
}
int fabric_identity_posix_load(struct fabric_identity_posix *s,
                               int (*random)(void *, uint8_t *, size_t),
                               void *ctx, uint8_t seed[32]) {
  if (!seed)
    return -1;
  if (directory(s)) {
    wipe(seed, 32);
    return -1;
  }
  int n = read_file(s, "fabric-device.key", seed, 32, 1);
  if (n == 32)
    return 0;
  if (n != -2 || !random) {
    wipe(seed, 32);
    return -1;
  }
  if (random(ctx, seed, 32) != 0) {
    wipe(seed, 32);
    return -1;
  }
  uint8_t zero[32] = {0};
  if (memcmp(seed, zero, 32) == 0) {
    wipe(seed, 32);
    return -1;
  }
  /* Initial identity creation never replaces an existing seed. A crash-partial
   * seed fails subsequent reads instead of silently generating a new identity.
   */
  char p[512];
  struct stat st;
  if (path(s, "fabric-device.key", p)) {
    wipe(seed, 32);
    return -1;
  }
  int fd = open(p, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
  if (fd < 0) {
    wipe(seed, 32);
    return -1;
  }
  int ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) &&
           (st.st_mode & 077) == 0 && write_all(fd, seed, 32) == 0;
  if (close(fd) < 0)
    ok = 0;
  if (!ok || sync_directory(s) < 0) {
    wipe(seed, 32);
    return -1;
  }
  return 0;
}
static void hex(const uint8_t id[32], char out[65]) {
  const char *d = "0123456789abcdef";
  for (size_t i = 0; i < 32; i++) {
    out[2 * i] = d[id[i] >> 4];
    out[2 * i + 1] = d[id[i] & 15];
  }
  out[64] = 0;
}
/* Returns 1 trusted, 2 blocked, 0 unknown/pending, -1 malformed. */
static int trust_record(const uint8_t *data, size_t size, const char id[65],
                        size_t *start, size_t *end) {
  int found = 0;
  for (size_t i = 0; i < size;) {
    size_t j = i;
    while (j < size && data[j] != '\n')
      j++;
    if (j == i) {
      i = j + 1;
      continue;
    }
    if (j - i < 66 || data[i + 64] != ' ')
      return -1;
    for (size_t k = i; k < i + 64; k++)
      if (!((data[k] >= '0' && data[k] <= '9') ||
            (data[k] >= 'a' && data[k] <= 'f')))
        return -1;
    size_t state = j - i - 65;
    const uint8_t *value = data + i + 65;
    int kind = state == 7 && memcmp(value, "trusted", 7) == 0   ? 1
               : state == 7 && memcmp(value, "blocked", 7) == 0 ? 2
               : state == 7 && memcmp(value, "pending", 7) == 0 ? 0
                                                                : -1;
    if (kind < 0)
      return -1;
    if (memcmp(data + i, id, 64) == 0) {
      if (found)
        return -1;
      found = kind + 3;
      if (start)
        *start = i;
      if (end)
        *end = j < size ? j + 1 : j;
    }
    i = j < size ? j + 1 : j;
  }
  return found ? found - 3 : 0;
}
int fabric_identity_posix_trusted(struct fabric_identity_posix *s,
                                  const uint8_t public_key[32]) {
  if (!public_key || directory(s))
    return 0;
  uint8_t id[32];
  char encoded[65];
  if (fabric_device_id(public_key, id) < 0)
    return 0;
  hex(id, encoded);
  uint8_t *records = malloc(TRUST_LIMIT);
  if (!records)
    return 0;
  int n = read_file(s, "trusted-devices.txt", records, TRUST_LIMIT, 0);
  int result =
      n >= 0 && trust_record(records, (size_t)n, encoded, NULL, NULL) == 1;
  free(records);
  return result;
}
static int32_t persist_peer_locked(void *ctx, const uint8_t public_key[32],
                                   const uint8_t id[32]) {
  struct fabric_identity_posix *s = ctx;
  uint8_t actual[32];
  char encoded[65], name[80];
  size_t start = 0, end = 0;
  if (!public_key || !id || directory(s) ||
      fabric_device_id(public_key, actual) < 0 || memcmp(actual, id, 32) != 0)
    return 0;
  hex(id, encoded);
  snprintf(name, sizeof(name), "%s.pub", encoded);
  uint8_t existing[32];
  int cached = read_file(s, name, existing, 32, 0);
  if (cached == -2) {
    if (atomic_file(s, name, public_key, 32) < 0)
      return 0;
  } else if (cached != 32 || memcmp(existing, public_key, 32) != 0)
    return 0;
  uint8_t *records = malloc(TRUST_LIMIT);
  if (!records)
    return 0;
  int n = read_file(s, "trusted-devices.txt", records, TRUST_LIMIT, 0);
  if (n == -2)
    n = 0;
  if (n < 0) {
    free(records);
    return 0;
  }
  int prior = trust_record(records, (size_t)n, encoded, &start, &end);
  if (prior < 0 || prior == 2) {
    free(records);
    return 0;
  }
  if (prior == 1) {
    free(records);
    return 1;
  }
  size_t length = (size_t)n;
  if (end > start) {
    memmove(records + start, records + end, length - end);
    length -= end - start;
  }
  if (length && records[length - 1] != '\n') {
    if (length >= TRUST_LIMIT) {
      free(records);
      return 0;
    }
    records[length++] = '\n';
  }
  if (length + 73 > TRUST_LIMIT) {
    free(records);
    return 0;
  }
  memcpy(records + length, encoded, 64);
  memcpy(records + length + 64, " trusted\n", 9);
  length += 73;
  int ok = atomic_file(s, "trusted-devices.txt", records, length) == 0;
  free(records);
  return ok;
}
int fabric_identity_posix_peer(struct fabric_identity_posix *s,
                               const uint8_t id[32], uint8_t public_key[32]) {
  char encoded[65], name[80];
  uint8_t actual[32];
  if (!s || !id || !public_key || directory(s))
    return -1;
  hex(id, encoded);
  snprintf(name, sizeof(name), "%s.pub", encoded);
  if (read_file(s, name, public_key, 32, 0) != 32 ||
      fabric_device_id(public_key, actual) < 0 || memcmp(actual, id, 32) != 0 ||
      !fabric_identity_posix_trusted(s, public_key)) {
    wipe(public_key, 32);
    return -1;
  }
  return 0;
}

/* Same sidecar and flock primitive as Rust FileDeviceTrustStore. NuttX hosts
 * have one executor owning all trust writes; do not instantiate parallel
 * writers.
 */
int32_t fabric_identity_posix_persist_peer(void *ctx,
                                           const uint8_t public_key[32],
                                           const uint8_t id[32]) {
  struct fabric_identity_posix *s = ctx;
  if (directory(s))
    return 0;
#if defined(__linux__)
  char name[512];
  struct stat st;
  if (path(s, "trusted-devices.txt.lock", name))
    return 0;
  int fd = open(name, O_RDWR | O_CREAT | O_NOFOLLOW, 0600);
  if (fd < 0)
    return 0;
  if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_uid != geteuid() ||
      (st.st_mode & 077) != 0 || flock(fd, LOCK_EX) < 0) {
    close(fd);
    return 0;
  }
#endif
  int result = persist_peer_locked(ctx, public_key, id);
#if defined(__linux__)
  close(fd);
#endif
  return result;
}
