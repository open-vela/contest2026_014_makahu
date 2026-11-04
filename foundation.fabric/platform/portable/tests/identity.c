#define _POSIX_C_SOURCE 200809L
#include "fabric_identity_posix.h"
#include "fabric_pairing.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifdef __linux__
#include <fcntl.h>
#include <poll.h>
#include <sys/file.h>
#include <sys/wait.h>
#endif
#include <unistd.h>
#ifndef FABRIC_TEST_TMP
#define FABRIC_TEST_TMP "/tmp"
#endif
static int entropy(void *ctx, uint8_t *out, size_t n) {
  memset(out, *(uint8_t *)ctx, n);
  return 0;
}
int main(void) {
  char base[] = FABRIC_TEST_TMP "/fabric-identity-XXXXXX";
  assert(mkdtemp(base));
  char dir[256];
  snprintf(dir, sizeof(dir), "%s/private", base);
  struct fabric_identity_posix store = {.directory = dir};
  uint8_t seed[32], other[32], public[32], id[32], reload[32], r = 9;
  assert(fabric_identity_posix_load(&store, entropy, &r, seed) == 0);
  r = 10;
  assert(fabric_identity_posix_load(&store, entropy, &r, reload) == 0);
  assert(memcmp(seed, reload, 32) == 0);
  memset(other, 2, 32);
  assert(fabric_identity_public(other, public, id) == 0);
  assert(fabric_identity_posix_trusted(&store, public) == 0);
  assert(fabric_identity_posix_peer(&store, id, reload) < 0);
  assert(fabric_identity_posix_persist_peer(&store, public, id) == 1);
  assert(fabric_identity_posix_trusted(&store, public) == 1);
  assert(fabric_identity_posix_persist_peer(&store, public, id) == 1);
  assert(fabric_identity_posix_peer(&store, id, reload) == 0);
  assert(memcmp(public, reload, 32) == 0);
  // Repeated reopen observes the same identity and trust, not regenerated keys.
  for (int i = 0; i < 3; i++) {
    struct fabric_identity_posix reopened = {.directory = dir};
    assert(fabric_identity_posix_load(&reopened, entropy, &r, reload) == 0);
    assert(memcmp(seed, reload, 32) == 0);
    assert(fabric_identity_posix_trusted(&reopened, public) == 1);
  }
#ifdef __linux__
  // A lock acquired by another runtime must serialize the portable writer too.
  char lock_path[512];
  snprintf(lock_path, sizeof(lock_path), "%s/trusted-devices.txt.lock", dir);
  int lock_fd = open(lock_path, O_RDWR);
  assert(lock_fd >= 0 && flock(lock_fd, LOCK_EX) == 0);
  int pipefd[2];
  assert(pipe(pipefd) == 0);
  pid_t child = fork();
  assert(child >= 0);
  if (child == 0) {
    close(lock_fd);
    close(pipefd[0]);
    int32_t result = fabric_identity_posix_persist_peer(&store, public, id);
    assert(write(pipefd[1], &result, sizeof(result)) == sizeof(result));
    _exit(0);
  }
  close(pipefd[1]);
  struct pollfd waitfd = {.fd = pipefd[0], .events = POLLIN};
  assert(poll(&waitfd, 1, 100) == 0);
  assert(flock(lock_fd, LOCK_UN) == 0);
  close(lock_fd);
  assert(poll(&waitfd, 1, 5000) == 1);
  int32_t stored = 0;
  assert(read(pipefd[0], &stored, sizeof(stored)) == sizeof(stored));
  assert(stored == 1);
  close(pipefd[0]);
  int status = 0;
  assert(waitpid(child, &status, 0) == child && WIFEXITED(status) &&
         WEXITSTATUS(status) == 0);
#endif
  // Existing explicit block is never upgraded by first-pair persistence.
  char trust_path[512];
  snprintf(trust_path, sizeof(trust_path), "%s/trusted-devices.txt", dir);
  FILE *blocked = fopen(trust_path, "wb");
  assert(blocked);
  for (unsigned i = 0; i < 32; ++i)
    assert(fprintf(blocked, "%02x", id[i]) == 2);
  assert(fputs(" blocked\n", blocked) >= 0 && fclose(blocked) == 0);
  assert(fabric_identity_posix_persist_peer(&store, public, id) == 0);
  assert(fabric_identity_posix_trusted(&store, public) == 0);
  char file[512];
  snprintf(file, sizeof(file), "%s/fabric-device.key", dir);
  assert(chmod(file, 0644) == 0);
  assert(fabric_identity_posix_load(&store, entropy, &r, reload) < 0);
  for (unsigned i = 0; i < 32; i++)
    assert(reload[i] == 0);
  assert(chmod(file, 0600) == 0);
  FILE *f = fopen(file, "wb");
  assert(f);
  assert(fwrite("partial", 1, 7, f) == 7);
  fclose(f);
  assert(fabric_identity_posix_load(&store, entropy, &r, reload) <
         0); // crash-partial seed never replaced
  assert(chmod(dir, 0777) == 0);
  assert(fabric_identity_posix_load(&store, entropy, &r, reload) < 0);
  puts("Fabric POSIX identity/trust durable reopen + private-mode + "
       "partial-key fail-closed passed");
  return 0;
}
