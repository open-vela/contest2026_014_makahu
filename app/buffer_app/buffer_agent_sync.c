/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent.h"
#include "buffer_scan_clock.h"
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* Records are the durable outbox. Transport receipts never remove them. Only
 * a matching application import receipt records delivery in a separate file,
 * so the hash always covers the original, unchanged UTF-8 record bytes. */
static pthread_mutex_t sync_lock = PTHREAD_MUTEX_INITIALIZER;
static char *snapshot;
static size_t snapshot_bytes, cursor, sent_end;
static char current_id[BUFFER_ID_SIZE], digest[65];
static time_t next_send;
static uint64_t next_scan;
static uint64_t monotonic_ms(void)
{
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now)) return 0;
  return (uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void reset_locked(void)
{
  if (snapshot) { memset(snapshot, 0, snapshot_bytes); free(snapshot); }
  snapshot = NULL; snapshot_bytes = cursor = sent_end = 0;
  current_id[0] = digest[0] = 0; next_send = 0; next_scan = 0;
}

void buffer_agent_outbox_reset(void)
{
  pthread_mutex_lock(&sync_lock); reset_locked(); pthread_mutex_unlock(&sync_lock);
  buffer_agent_card_state_reset();
}

static int receipt_path(char *out, size_t size, const char *id)
{
  int n = snprintf(out, size, "%s/records/%s.receipt", CONFIG_BUFFER_VELA_DATA_DIR, id);
  return n < 0 || (size_t)n >= size ? -ENAMETOOLONG : 0;
}

static int select_record(void)
{
  DIR *directory = opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");
  if (!directory) return -errno;
  struct dirent *entry;
  int ret = 0, count = 0;
  while ((entry = readdir(directory)))
    {
      size_t length = strlen(entry->d_name);
      if (length < 6 || strcmp(entry->d_name + length - 5, ".json")) continue;
      if (++count > BUFFER_AGENT_RECORDS_LIMIT) { ret = -EFBIG; break; }
      char id[BUFFER_ID_SIZE], path[320];
      if (length - 5 >= sizeof(id)) continue;
      memcpy(id, entry->d_name, length - 5); id[length - 5] = 0;
      if (buffer_agent_record_path(path, sizeof(path), id)) continue;
      /* Validate private mode and record shape before reading exact bytes. */
      cJSON *record = buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
      const cJSON *state = record ? cJSON_GetObjectItemCaseSensitive(record, "state") : NULL;
      const cJSON *identity = record ? cJSON_GetObjectItemCaseSensitive(record, "capture_id") : NULL;
      bool ready = cJSON_IsString(state) && (!strcmp(state->valuestring, "ready") ||
          (!strcmp(state->valuestring, "assistant_complete") &&
           (cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(record,"followup")) ||
            cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(record,"focus_session"))))) &&
                   cJSON_IsString(identity) && !strcmp(identity->valuestring, id);
      cJSON_Delete(record);
      if (!ready) continue;
      char snapshot_path[320];
      int snapshot_length = snprintf(snapshot_path, sizeof(snapshot_path),
                                     "%s/records/%s.processed",
                                     CONFIG_BUFFER_VELA_DATA_DIR, id);
      if (snapshot_length < 0 || (size_t)snapshot_length >= sizeof(snapshot_path))
        continue;
      const char *export_path = access(snapshot_path, F_OK) == 0
                                ? snapshot_path : path;
      int flags = O_RDONLY;
#ifdef O_NOFOLLOW
      flags |= O_NOFOLLOW;
#endif
      int fd = open(export_path, flags);
      if (fd < 0) { ret = -errno; break; }
      struct stat st;
      if (fstat(fd, &st) || !S_ISREG(st.st_mode) || (st.st_mode & 0077) ||
          st.st_size < 1 || st.st_size > BUFFER_AGENT_JSON_BYTES)
        { close(fd); ret = -EACCES; break; }
      size_t n = (size_t)st.st_size, offset = 0;
      char *raw = calloc(1, n + 1);
      if (!raw) { close(fd); ret = -ENOMEM; break; }
      while (offset < n)
        {
          ssize_t got = read(fd, raw + offset, n - offset);
          if (got < 0 && errno == EINTR) continue;
          if (got <= 0) break;
          offset += (size_t)got;
        }
      close(fd);
      unsigned char hash[32]; char hex[65];
      if (offset != n || memchr(raw, 0, n) || mbedtls_sha256((unsigned char *)raw, n, hash, 0))
        { free(raw); ret = -EIO; break; }
      for (size_t i = 0; i < 32; i++) snprintf(hex + i * 2, 3, "%02x", hash[i]);
      receipt_path(path, sizeof(path), id);
      cJSON *receipt = buffer_agent_read_json(path, 1024);
      const cJSON *saved = receipt ? cJSON_GetObjectItemCaseSensitive(receipt, "record_sha256") : NULL;
      const cJSON *saved_state = receipt ? cJSON_GetObjectItemCaseSensitive(receipt, "state") : NULL;
      const char *receipt_state = cJSON_IsString(saved_state) ? saved_state->valuestring : "";
      bool known_receipt = cJSON_IsString(saved) &&
          (!strcmp(receipt_state, "imported") || !strcmp(receipt_state, "duplicate") ||
           !strcmp(receipt_state, "conflict"));
      bool state_change = false;
      cJSON *saved_record = buffer_agent_read_json(buffer_agent_record_path(path, sizeof(path), id) == 0
                                                    ? path : "", BUFFER_AGENT_JSON_BYTES);
      cJSON *saved_segments = saved_record
          ? cJSON_GetObjectItemCaseSensitive(saved_record, "segments") : NULL;
      cJSON *saved_segment;
      cJSON_ArrayForEach(saved_segment, saved_segments)
        {
          const cJSON *modified = cJSON_GetObjectItemCaseSensitive(saved_segment,
                                                                   "user_modified");
          const cJSON *revision = cJSON_GetObjectItemCaseSensitive(saved_segment,
                                                                   "action_revision");
          if (cJSON_IsTrue(modified) || (cJSON_IsNumber(revision) && revision->valuedouble > 0))
            { state_change = true; break; }
        }
      cJSON_Delete(saved_record);
      bool delivered = known_receipt && !strcmp(saved->valuestring, hex) &&
                       (!strcmp(receipt_state, "imported") ||
                        !strcmp(receipt_state, "duplicate"));
      bool conflict = known_receipt && !strcmp(saved->valuestring, hex) &&
                      !strcmp(receipt_state, "conflict");
      bool mutation_already_imported = known_receipt &&
          strcmp(saved->valuestring, hex) && state_change;
      cJSON_Delete(receipt);
      if (delivered || conflict || mutation_already_imported)
        {
          if (conflict)
            buffer_set_status("capture 导入存在冲突；原记录保留，卡片变更需单独核对");
          memset(raw, 0, n); free(raw); continue;
        }
      snapshot = raw; snapshot_bytes = n; strcpy(current_id, id); strcpy(digest, hex);
      ret = 1; break;
    }
  closedir(directory); return ret;
}

int buffer_agent_outbox_next(size_t max_payload, char **message)
{
  if (!message || max_payload < 1024 || max_payload > 12288) return -EINVAL;
  *message = NULL;
  pthread_mutex_lock(&sync_lock);
  int ret = 0;
  if (time(NULL) < next_send) goto done;
  if (!snapshot)
    {
      uint64_t now = monotonic_ms();
      if (now < next_scan) goto done;
      uint64_t began=buffer_scan_clock_ms();
      ret = select_record();
      /* No pending record: bound discovery I/O independently of BLE polls.
       * A queued snapshot still advances immediately on each valid ACK. */
      if (ret <= 0) { next_scan = buffer_scan_retry_after(now,began,1000); goto done; }
      next_scan = 0;
    }
  size_t bytes = ((max_payload - 512) / 4) * 3;
  if (bytes > 3072) bytes = 3072;
  if (bytes > snapshot_bytes - cursor) bytes = snapshot_bytes - cursor;
  size_t encoded_size = ((bytes + 2) / 3) * 4 + 1, written;
  unsigned char *encoded = calloc(1, encoded_size);
  cJSON *part = cJSON_CreateObject();
  if (!encoded || !part) { free(encoded); cJSON_Delete(part); ret = -ENOMEM; goto done; }
  if (mbedtls_base64_encode(encoded, encoded_size, &written,
                           (unsigned char *)snapshot + cursor, bytes))
    { free(encoded); cJSON_Delete(part); ret = -EIO; goto done; }
  char request[33]; memcpy(request, digest, 32); request[32] = 0;
  cJSON_AddStringToObject(part, "type", "vela.capture.processed.part");
  cJSON_AddNumberToObject(part, "protocol", 1);
  cJSON_AddStringToObject(part, "request_id", request);
  cJSON_AddStringToObject(part, "record_sha256", digest);
  cJSON_AddNumberToObject(part, "offset", cursor);
  cJSON_AddNumberToObject(part, "total", snapshot_bytes);
  cJSON_AddStringToObject(part, "record_base64", (char *)encoded);
  *message = cJSON_PrintUnformatted(part); free(encoded); cJSON_Delete(part);
  if (!*message) { ret = -ENOMEM; goto done; }
  if (strlen(*message) > max_payload) { free(*message); *message = NULL; ret = -EFBIG; goto done; }
  sent_end = cursor + bytes; next_send = time(NULL) + 30; ret = 1;
done:
  pthread_mutex_unlock(&sync_lock); return ret;
}

/* Caller MUST check live Fabric receive authorization before entering. A
 * receipt is additionally tied to exactly the outstanding immutable snapshot. */
int buffer_agent_outbox_ack(const unsigned char *data, size_t length)
{
  if (!data || !length || length > 1024 || memchr(data, 0, length)) return -EINVAL;
  char *encoded = calloc(1,length+1);
  if (!encoded) return -ENOMEM;
  memcpy(encoded,data,length);
  cJSON *reply = cJSON_ParseWithLengthOpts(encoded,length+1,NULL,true);
  free(encoded);
  if (!reply) return -EINVAL;
  pthread_mutex_lock(&sync_lock);
  int ret = -ESTALE;
  cJSON *type = cJSON_GetObjectItemCaseSensitive(reply, "type");
  cJSON *protocol = cJSON_GetObjectItemCaseSensitive(reply, "protocol");
  cJSON *hash = cJSON_GetObjectItemCaseSensitive(reply, "record_sha256");
  cJSON *request = cJSON_GetObjectItemCaseSensitive(reply, "request_id");
  if (!snapshot || !cJSON_IsString(type) || !cJSON_IsNumber(protocol) || protocol->valuedouble != 1 ||
      !cJSON_IsString(hash) || strcmp(hash->valuestring, digest) ||
      !cJSON_IsString(request) || strlen(request->valuestring) != 32 ||
      strncmp(request->valuestring, digest, 32)) goto done;
  if (!strcmp(type->valuestring, "vela.capture.processed.part.reply"))
    {
      cJSON *next = cJSON_GetObjectItemCaseSensitive(reply, "next_offset");
      if (!cJSON_IsNumber(next) || next->valuedouble != (double)sent_end || sent_end >= snapshot_bytes) goto done;
      cursor = sent_end; next_send = 0; ret = 0;
    }
  else if (!strcmp(type->valuestring, "vela.capture.processed.reply"))
    {
      cJSON *id = cJSON_GetObjectItemCaseSensitive(reply, "capture_id");
      cJSON *state = cJSON_GetObjectItemCaseSensitive(reply, "state");
      if (!cJSON_IsString(id) || strcmp(id->valuestring, current_id) || !cJSON_IsString(state)) goto done;
      bool conflict = !strcmp(state->valuestring, "conflict");
      if (!conflict && strcmp(state->valuestring, "imported") && strcmp(state->valuestring, "duplicate")) goto done;
      char path[320]; receipt_path(path, sizeof(path), current_id);
      cJSON *receipt = cJSON_CreateObject();
      if (!receipt) { ret = -ENOMEM; goto done; }
      cJSON_AddStringToObject(receipt, "record_sha256", digest);
      cJSON_AddStringToObject(receipt, "state", state->valuestring);
      ret = buffer_agent_atomic_json(path, receipt); cJSON_Delete(receipt);
      if (!ret)
        {
          reset_locked();
          if (conflict)
            {
              buffer_set_status("手机保留了编辑或删除；设备原记录已保留待核对");
              ret = -EEXIST;
            }
        }
    }
done:
  pthread_mutex_unlock(&sync_lock); cJSON_Delete(reply); return ret;
}
