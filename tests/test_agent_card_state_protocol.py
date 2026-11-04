"""Run the production Vela card-state sender/receipt code against private files."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC
from pathlib import Path
import os
import ctypes.util
import subprocess
import tempfile

ROOT = PROJECT_ROOT
SDK = SDK_ROOT
MODEL = (ROOT / "app/buffer_app/buffer_agent_model.c").read_text()
STORE = (ROOT / "app/buffer_app/buffer_agent_store.c").read_text()


def function(source, signature):
    return extract_c(source, signature, last=True)


harness = r'''
#include <assert.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "buffer_agent.h"
#include "cJSON.h"
#include "mbedtls/sha256.h"

#define RECORD_DIR CONFIG_BUFFER_VELA_DATA_DIR "/records"
static char *card_state_cached_message;
static uint64_t card_state_retry_at;
static char card_state_capture[BUFFER_ID_SIZE];
static char card_state_card[BUFFER_ID_SIZE];
static char card_state_action[BUFFER_ACTION_ID_SIZE];
static uint64_t card_state_revision;
pthread_mutex_t g_buffer_capture_lock = PTHREAD_MUTEX_INITIALIZER;
bool buffer_replica_crdt_contains(const char *id) { (void)id; return false; }
static bool fail_atomic;
static unsigned status_count;
void buffer_set_status(const char *format, ...)
{ (void)format; status_count++; }

int buffer_agent_atomic_json(const char *path, const cJSON *json)
{
  if (fail_atomic) { fail_atomic = false; return -EIO; }
  char *text = cJSON_PrintUnformatted(json);
  if (!text) return -ENOMEM;
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
  if (fd < 0) { free(text); return -errno; }
  if (fchmod(fd, 0600)) { int error = -errno; close(fd); free(text); return error; }
  size_t length = strlen(text), offset = 0;
  while (offset < length) {
    ssize_t written = write(fd, text + offset, length - offset);
    if (written < 0 && errno == EINTR) continue;
    if (written <= 0) { int error = written < 0 ? -errno : -EIO; close(fd); free(text); return error; }
    offset += (size_t)written;
  }
  int result = fsync(fd) ? -errno : 0;
  if (close(fd) && !result) result = -errno;
  free(text);
  return result;
}

cJSON *buffer_agent_read_json(const char *path, size_t limit)
{
  struct stat st;
  int fd = open(path, O_RDONLY);
  if (fd < 0) return NULL;
  if (fstat(fd, &st) || st.st_size < 1 || st.st_size > (off_t)limit || (st.st_mode & 0077))
    { close(fd); return NULL; }
  size_t length = (size_t)st.st_size;
  char *bytes = calloc(1, length + 1);
  if (!bytes) { close(fd); return NULL; }
  size_t offset = 0;
  while (offset < length) {
    ssize_t read_count = read(fd, bytes + offset, length - offset);
    if (read_count < 0 && errno == EINTR) continue;
    if (read_count <= 0) { free(bytes); close(fd); return NULL; }
    offset += (size_t)read_count;
  }
  close(fd);
  cJSON *json = cJSON_ParseWithLengthOpts(bytes, length + 1, NULL, true);
  free(bytes);
  return json;
}
'''

for src, signature in (
    (MODEL, "static const char *str("),
    (MODEL, "static bool valid_sha256_hex("),
    (MODEL, "static bool set_json_string("),
    (MODEL, "static bool record_filename("),
    (MODEL, "static void sha256_hex(const unsigned char *bytes,size_t length,char hex[65])"),
    (STORE, "bool buffer_agent_valid_id("),
    (STORE, "int buffer_agent_record_path("),
    (MODEL, "static void card_state_cache_clear_locked("),
    (MODEL, "void buffer_agent_card_state_reset("),
    (MODEL, "static int card_state_receipt_path("),
    (MODEL, "static int card_state_file_hash("),
    (MODEL, "static bool card_state_name_valid("),
    (MODEL, "static int card_state_action_id("),
    (MODEL, "static int card_state_set_number("),
    (MODEL, "static int card_state_migrate_legacy("),
    (MODEL, "int buffer_agent_card_state_next("),
    (MODEL, "int buffer_agent_card_state_ack("),
):
    harness += function(src, signature) + "\n"

harness += r'''
static char *read_text(const char *path)
{
  FILE *file = fopen(path, "r"); assert(file);
  assert(fseek(file, 0, SEEK_END) == 0); long size = ftell(file); assert(size > 0);
  rewind(file); char *text = calloc(1, (size_t)size + 1); assert(text);
  assert(fread(text, 1, (size_t)size, file) == (size_t)size); fclose(file); return text;
}
static cJSON *record_json(const char *state, unsigned revision, unsigned synced,
                          unsigned conflict, const char *previous)
{
  cJSON *record = cJSON_CreateObject(), *segments = cJSON_CreateArray();
  cJSON *segment = cJSON_CreateObject(); assert(record && segments && segment);
  cJSON_AddStringToObject(record, "capture_id", "capture_state_1");
  cJSON_AddStringToObject(record, "state", "ready");
  cJSON_AddStringToObject(segment, "card_id", "vla_card_state");
  cJSON_AddStringToObject(segment, "state", state);
  if (revision) {
    char action[BUFFER_ACTION_ID_SIZE];
    snprintf(action, sizeof(action), "vla_card_state_r%u", revision);
    cJSON_AddNumberToObject(segment, "action_revision", revision);
    cJSON_AddNumberToObject(segment, "action_synced_revision", synced);
    cJSON_AddNumberToObject(segment, "action_conflict_revision", conflict);
    cJSON_AddStringToObject(segment, "action_id", action);
    cJSON_AddStringToObject(segment, "previous_state", previous);
    cJSON_AddBoolToObject(segment, "user_modified", true);
  }
  cJSON_AddItemToArray(segments, segment);
  cJSON_AddItemToObject(record, "segments", segments);
  return record;
}
static int create_receipt(const char *snapshot_path, const char *receipt_path)
{
  char *raw = read_text(snapshot_path); char digest[65];
  sha256_hex((unsigned char *)raw, strlen(raw), digest); free(raw);
  assert(digest[0]);
  cJSON *receipt = cJSON_CreateObject(); assert(receipt);
  cJSON_AddStringToObject(receipt, "record_sha256", digest);
  cJSON_AddStringToObject(receipt, "state", "imported");
  int result = buffer_agent_atomic_json(receipt_path, receipt); cJSON_Delete(receipt); return result;
}
static cJSON *reply(const char *card, unsigned revision, const char *state)
{
  char action[BUFFER_ACTION_ID_SIZE];
  snprintf(action, sizeof(action), "%s_r%u", card, revision);
  cJSON *value = cJSON_CreateObject(); assert(value);
  cJSON_AddStringToObject(value, "type", "buffer.card.state.reply");
  cJSON_AddNumberToObject(value, "protocol", 1);
  cJSON_AddStringToObject(value, "capture_id", "capture_state_1");
  cJSON_AddStringToObject(value, "card_id", card);
  cJSON_AddNumberToObject(value, "revision", revision);
  cJSON_AddStringToObject(value, "action_id", action);
  cJSON_AddStringToObject(value, "state", state);
  return value;
}
static int ack(unsigned revision, const char *state)
{
  cJSON *value = reply("vla_card_state", revision, state);
  char *text = cJSON_PrintUnformatted(value); assert(text);
  int result = buffer_agent_card_state_ack((unsigned char *)text, strlen(text));
  free(text); cJSON_Delete(value); return result;
}
static cJSON *load_record(void)
{
  char path[320]; assert(buffer_agent_record_path(path, sizeof(path), "capture_state_1") == 0);
  return buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
}
static int queue_state(uint64_t now, char **message)
{ return buffer_agent_card_state_next(now, 1024, message); }

int main(void)
{
  char record_path[320], snapshot_path[320], receipt_path[320];
  assert(buffer_agent_record_path(record_path, sizeof(record_path), "capture_state_1") == 0);
  snprintf(snapshot_path, sizeof(snapshot_path), "%s/%s.processed", RECORD_DIR, "capture_state_1");
  snprintf(receipt_path, sizeof(receipt_path), "%s/%s.receipt", RECORD_DIR, "capture_state_1");
  cJSON *snapshot = record_json("active", 0, 0, 0, "");
  assert(buffer_agent_atomic_json(snapshot_path, snapshot) == 0);
  assert(create_receipt(snapshot_path, receipt_path) == 0);
  cJSON_Delete(snapshot);
  cJSON *current = record_json("archived", 1, 0, 0, "active");
  assert(buffer_agent_atomic_json(record_path, current) == 0); cJSON_Delete(current);

  char *message = NULL;
  assert(queue_state(1000, &message) == 1 && message);
  cJSON *request = cJSON_Parse(message); assert(request && cJSON_GetArraySize(request) == 10);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "type")->valuestring,
                 "buffer.card.state.change"));
  assert(cJSON_GetObjectItemCaseSensitive(request, "protocol")->valuedouble == 1);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "capture_id")->valuestring,
                 "capture_state_1"));
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "card_id")->valuestring,
                 "vla_card_state"));
  assert(cJSON_GetObjectItemCaseSensitive(request, "revision")->valuedouble == 1);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "action_id")->valuestring,
                 "vla_card_state_r1"));
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "previous_state")->valuestring,
                 "active"));
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "state")->valuestring, "archived"));
  assert(!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(request, "deleted")));
  assert(cJSON_GetObjectItemCaseSensitive(request, "review_at")->valuedouble == 0);
  cJSON_Delete(request);
  char first[1024]; snprintf(first, sizeof(first), "%s", message); free(message); message = NULL;
  assert(queue_state(5999, &message) == 0 && !message);
  assert(queue_state(6000, &message) == 1 && !strcmp(first, message)); free(message); message = NULL;
  buffer_agent_card_state_reset();
  assert(queue_state(6001, &message) == 1 && !strcmp(first, message)); free(message); message = NULL;
  assert(ack(1, "applied") == 0);
  cJSON *saved = load_record(); assert(saved);
  cJSON *segment = cJSON_GetObjectItemCaseSensitive(saved, "segments")->child;
  assert(cJSON_GetObjectItemCaseSensitive(segment, "action_synced_revision")->valuedouble == 1);
  cJSON_Delete(saved);
  assert(queue_state(7000, &message) == 0 && !message);

  current = record_json("done", 2, 1, 0, "archived");
  assert(buffer_agent_atomic_json(record_path, current) == 0); cJSON_Delete(current);
  assert(queue_state(8000, &message) == 1 && message);
  request = cJSON_Parse(message); assert(request);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "state")->valuestring, "done"));
  cJSON_Delete(request); char second[1024]; snprintf(second, sizeof(second), "%s", message);
  free(message); message = NULL;
  fail_atomic = true;
  assert(ack(2, "applied") == -EIO);
  assert(queue_state(9000, &message) == 1 && !strcmp(second, message)); free(message); message = NULL;
  assert(ack(2, "applied") == 0);
  saved = load_record(); assert(saved);
  segment = cJSON_GetObjectItemCaseSensitive(saved, "segments")->child;
  assert(cJSON_GetObjectItemCaseSensitive(segment, "action_synced_revision")->valuedouble == 2);
  cJSON_Delete(saved);

  current = record_json("deleted", 3, 2, 0, "done");
  assert(buffer_agent_atomic_json(record_path, current) == 0); cJSON_Delete(current);
  assert(queue_state(10000, &message) == 1 && message);
  request = cJSON_Parse(message); assert(request);
  assert(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(request, "deleted")));
  cJSON_Delete(request); free(message); message = NULL;
  assert(ack(3, "conflict") == -EEXIST && status_count == 1);
  assert(queue_state(11000, &message) == 0 && !message);
  saved = load_record(); assert(saved);
  segment = cJSON_GetObjectItemCaseSensitive(saved, "segments")->child;
  assert(cJSON_GetObjectItemCaseSensitive(segment, "action_conflict_revision")->valuedouble == 3);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(segment, "action_conflict_state")->valuestring,
                 "conflict"));
  cJSON_Delete(saved);
  puts("PASS: immutable capture baseline, versioned card state changes, retries, durable ACKs and conflict tombstones");
  return 0;
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-agent-card-state-") as temporary:
    work = Path(temporary)
    data = work / "data"
    (data / "records").mkdir(parents=True, mode=0o700)
    include = work / "include"
    (include / "nuttx").mkdir(parents=True)
    (include / "nuttx/config.h").write_text("")
    (include / "netutils").mkdir()
    cjson = SDK / "apps/netutils/cjson/cJSON"
    (include / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
    source = work / "card_state.c"
    executable = work / "card-state"
    source.write_text(harness)
    mbedtls = SDK / "apps/crypto/mbedtls/mbedtls/include"
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
