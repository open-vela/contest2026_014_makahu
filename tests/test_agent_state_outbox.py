"""Verify the production capture outbox stays immutable after card actions."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = PROJECT_ROOT
SDK = SDK_ROOT
SYNC = (ROOT / "app/buffer_app/buffer_agent_sync.c").read_text()
STORE = (ROOT / "app/buffer_app/buffer_agent_store.c").read_text()


def function(source, signature):
    return extract_c(source, signature, last=True)


harness = r'''
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "buffer_agent.h"
#include "buffer_scan_clock.h"
#include "cJSON.h"
#include "mbedtls/base64.h"
#include "mbedtls/sha256.h"

static pthread_mutex_t sync_lock = PTHREAD_MUTEX_INITIALIZER;
static char *snapshot;
static size_t snapshot_bytes, cursor, sent_end;
static char current_id[BUFFER_ID_SIZE], digest[65];
static time_t next_send;
static uint64_t next_scan;
static unsigned status_count;
void buffer_set_status(const char *format, ...) { (void)format; status_count++; }

cJSON *buffer_agent_read_json(const char *path, size_t limit)
{
  struct stat st; int fd = open(path, O_RDONLY);
  if (fd < 0) return NULL;
  if (fstat(fd, &st) || st.st_size < 1 || st.st_size > (off_t)limit || (st.st_mode & 0077))
    { close(fd); return NULL; }
  size_t length = (size_t)st.st_size; char *bytes = calloc(1, length + 1);
  if (!bytes) { close(fd); return NULL; }
  size_t offset = 0;
  while (offset < length) {
    ssize_t n = read(fd, bytes + offset, length - offset);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) { close(fd); free(bytes); return NULL; }
    offset += (size_t)n;
  }
  close(fd); cJSON *value = cJSON_ParseWithLengthOpts(bytes, length + 1, NULL, true);
  free(bytes); return value;
}
'''

for source, signature in (
    (STORE, "bool buffer_agent_valid_id("),
    (STORE, "int buffer_agent_record_path("),
    (SYNC, "static uint64_t monotonic_ms("),
    (SYNC, "static void reset_locked("),
    (SYNC, "static int receipt_path("),
    (SYNC, "static int select_record("),
    (SYNC, "int buffer_agent_outbox_next("),
):
    harness += function(source, signature) + "\n"

harness += r'''
static void write_text(const char *path, const char *text)
{
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600); assert(fd >= 0);
  assert(fchmod(fd, 0600) == 0); size_t length = strlen(text), offset = 0;
  while (offset < length) {
    ssize_t n = write(fd, text + offset, length - offset);
    if (n < 0 && errno == EINTR) continue;
    assert(n > 0); offset += (size_t)n;
  }
  assert(fsync(fd) == 0); assert(close(fd) == 0);
}
static void write_json(const char *path, const cJSON *value)
{ char *text = cJSON_PrintUnformatted(value); assert(text); write_text(path, text); free(text); }
static void sha256_hex(const char *text, char output[65])
{
  unsigned char bytes[32]; assert(mbedtls_sha256((const unsigned char *)text, strlen(text), bytes, 0) == 0);
  for (size_t i = 0; i < sizeof(bytes); i++) snprintf(output + i * 2, 3, "%02x", bytes[i]);
}
static cJSON *record(const char *state, bool modified)
{
  cJSON *root = cJSON_CreateObject(), *segments = cJSON_CreateArray(), *segment = cJSON_CreateObject();
  assert(root && segments && segment);
  cJSON_AddStringToObject(root, "capture_id", "capture_state_1");
  cJSON_AddStringToObject(root, "state", "ready");
  cJSON_AddStringToObject(root, "transcript", "immutable original bytes");
  cJSON_AddStringToObject(segment, "card_id", "vla_card_state");
  cJSON_AddStringToObject(segment, "state", state);
  if (modified) {
    cJSON_AddBoolToObject(segment, "user_modified", true);
    cJSON_AddNumberToObject(segment, "action_revision", 1);
  }
  cJSON_AddItemToArray(segments, segment); cJSON_AddItemToObject(root, "segments", segments);
  return root;
}
static void receipt(const char *path, const char *hash, const char *state)
{
  cJSON *value = cJSON_CreateObject(); assert(value);
  cJSON_AddStringToObject(value, "record_sha256", hash);
  cJSON_AddStringToObject(value, "state", state);
  write_json(path, value); cJSON_Delete(value);
}

int main(void)
{
  char record_path[320], original_path[320], receipt_file[320];
  assert(buffer_agent_record_path(record_path, sizeof(record_path), "capture_state_1") == 0);
  snprintf(original_path, sizeof(original_path), "%s/records/capture_state_1.processed", CONFIG_BUFFER_VELA_DATA_DIR);
  snprintf(receipt_file, sizeof(receipt_file), "%s/records/capture_state_1.receipt", CONFIG_BUFFER_VELA_DATA_DIR);
  cJSON *original = record("active", false); write_json(original_path, original);
  char *original_bytes = cJSON_PrintUnformatted(original); assert(original_bytes);
  char original_hash[65]; sha256_hex(original_bytes, original_hash);
  assert(strlen(original_bytes) > 0);
  cJSON *changed = record("archived", true); write_json(record_path, changed);
  cJSON_Delete(changed);

  char *message = NULL;
  assert(buffer_agent_outbox_next(1024, &message) == 1 && message);
  cJSON *part = cJSON_Parse(message); assert(part);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(part, "type")->valuestring,
                 "vela.capture.processed.part"));
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(part, "record_sha256")->valuestring,
                 original_hash));
  assert(strlen(cJSON_GetObjectItemCaseSensitive(part, "request_id")->valuestring) == 32);
  assert(!strncmp(cJSON_GetObjectItemCaseSensitive(part, "request_id")->valuestring,
                  original_hash, 32));
  const char *encoded = cJSON_GetObjectItemCaseSensitive(part, "record_base64")->valuestring;
  size_t decoded_size = strlen(original_bytes) + 4, decoded = 0;
  unsigned char *raw = calloc(1, decoded_size); assert(raw);
  assert(mbedtls_base64_decode(raw, decoded_size, &decoded,
      (const unsigned char *)encoded, strlen(encoded)) == 0);
  assert(decoded == strlen(original_bytes) && !memcmp(raw, original_bytes, decoded));
  assert(cJSON_GetObjectItemCaseSensitive(part, "total")->valuedouble == (double)decoded);
  cJSON_Delete(part); free(message); free(raw); free(original_bytes); cJSON_Delete(original);

  /* Once the immutable capture is imported, a changed mutable card record is
   * never sent as a second capture, whether the receipt is imported or conflict. */
  reset_locked(); receipt(receipt_file, original_hash, "imported"); message = NULL;
  assert(buffer_agent_outbox_next(1024, &message) == 0 && !message);
  reset_locked(); receipt(receipt_file, original_hash, "conflict");
  assert(buffer_agent_outbox_next(1024, &message) == 0 && !message);
  assert(status_count >= 1);

  /* Legacy receipt hashes may be stale after in-record mutation. Presence of
   * action metadata routes it to the versioned card-state event, not re-import. */
  reset_locked();
  snprintf(receipt_file, sizeof(receipt_file), "%s/records/legacy_state.receipt", CONFIG_BUFFER_VELA_DATA_DIR);
  snprintf(record_path, sizeof(record_path), "%s/records/legacy_state.json", CONFIG_BUFFER_VELA_DATA_DIR);
  cJSON *legacy_base = record("active", false);
  char *legacy_bytes = cJSON_PrintUnformatted(legacy_base); assert(legacy_bytes);
  char legacy_hash[65]; sha256_hex(legacy_bytes, legacy_hash); free(legacy_bytes); cJSON_Delete(legacy_base);
  cJSON *legacy_changed = record("done", true);
  cJSON_ReplaceItemInObjectCaseSensitive(legacy_changed, "capture_id", cJSON_CreateString("legacy_state"));
  write_json(record_path, legacy_changed); cJSON_Delete(legacy_changed);
  receipt(receipt_file, legacy_hash, "imported");
  assert(buffer_agent_outbox_next(1024, &message) == 0 && !message);
  puts("PASS: immutable capture bytes stay separate from local card-state changes and conflicts");
  return 0;
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-agent-state-outbox-") as temporary:
    work = Path(temporary)
    data = work / "data"
    (data / "records").mkdir(parents=True, mode=0o700)
    include = work / "include"
    (include / "nuttx").mkdir(parents=True)
    (include / "nuttx/config.h").write_text("")
    (include / "netutils").mkdir()
    cjson = SDK / "apps/netutils/cjson/cJSON"
    (include / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
    source = work / "state_outbox.c"
    executable = work / "state-outbox"
    source.write_text(harness)
    mbedtls = SDK / "apps/crypto/mbedtls/mbedtls/include"
    import ctypes.util
    mbedcrypto = ctypes.util.find_library("mbedcrypto")
    if not mbedcrypto:
        raise RuntimeError("host mbedcrypto library is required for the production SHA-256 helper")
    subprocess.run([
        *CC, "-std=c11", "-D_DEFAULT_SOURCE",
        "-D_POSIX_C_SOURCE=200809L", "-pthread", "-I" + str(include),
        "-I" + str(ROOT / "app/buffer_app"), "-I" + str(cjson), "-I" + str(mbedtls),
        "-DCONFIG_BUFFER_VELA_DATA_DIR=\"" + str(data) + "\"",
        str(source), str(cjson / "cJSON.c"), "-l:" + mbedcrypto, "-lm", "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
