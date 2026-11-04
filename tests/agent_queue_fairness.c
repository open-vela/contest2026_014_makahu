/* SPDX-License-Identifier: Apache-2.0 */
/* Host regression for the production queue scheduler in buffer_agent.c. */
#include "../app/buffer_app/buffer_agent.c"

#include <assert.h>
#include <ctype.h>
#include <dirent.h>
#include <stdarg.h>
#include <sys/stat.h>
#include <unistd.h>

struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};

void buffer_images_shutdown(void){}
static bool bad_audio_fails = true;
static char attempted[8][BUFFER_ID_SIZE];
static unsigned attempt_count;

static void write_json(const char *path, const cJSON *json)
{
  char *encoded = cJSON_PrintUnformatted(json);
  assert(encoded);
  FILE *file = fopen(path, "w");
  assert(file);
  assert(fputs(encoded, file) >= 0);
  assert(fclose(file) == 0);
  free(encoded);
}

static void make_record(const char *id, const char *state)
{
  char path[320];
  assert(buffer_agent_record_path(path, sizeof(path), id) == 0);
  cJSON *record = cJSON_CreateObject();
  assert(record);
  cJSON_AddStringToObject(record, "capture_id", id);
  cJSON_AddStringToObject(record, "state", state);
  cJSON_AddStringToObject(record, "target", "buffer");
  write_json(path, record);
  cJSON_Delete(record);
}

static void make_audio(const char *id)
{
  char path[320];
  snprintf(path, sizeof(path), "%s/queue/%s.wav",
           CONFIG_BUFFER_VELA_DATA_DIR, id);
  FILE *file = fopen(path, "w");
  assert(file);
  assert(fputs("fixture", file) >= 0);
  assert(fclose(file) == 0);
}

int buffer_agent_store_init(void) { return 0; }
int buffer_agent_config_load(struct buffer_agent_config_s *config)
{
  config->validated = true;
  config->request.operation = BUFFER_OOBE_CONFIGURE_AGENT;
  return 0;
}
void buffer_agent_config_wipe(struct buffer_agent_config_s *config)
{ memset(config, 0, sizeof(*config)); }
int buffer_agent_config_result(const struct buffer_agent_config_s *config,
                               bool validated, int error)
{ (void)config; (void)validated; (void)error; return 0; }
int buffer_agent_validate(const struct buffer_agent_config_s *config)
{ (void)config; return 0; }
void buffer_agent_set_validating(bool validating) { (void)validating; }
void buffer_agent_report_error(int error) { (void)error; }
const char *buffer_agent_error_code(int error) { (void)error; return "fixture"; }
bool buffer_wifi_is_ready(void) { return true; }
int buffer_wifi_reconnect(void) { return 0; }
void buffer_set_status(const char *format, ...) { (void)format; }
bool buffer_coordinator_local(void) { return true; }
bool buffer_agent_input_assistant(const char *id) { (void)id; return false; }
static int projection_result;
int buffer_agent_refresh_cards(void) { return projection_result; }

bool buffer_agent_valid_id(const char *id)
{
  size_t length = id ? strlen(id) : 0;
  if (!length || length >= BUFFER_ID_SIZE) return false;
  for (size_t i = 0; i < length; i++)
    if (!isalnum((unsigned char)id[i]) && id[i] != '_' && id[i] != '-')
      return false;
  return true;
}

int buffer_agent_record_path(char *path, size_t size, const char *id)
{
  if (!buffer_agent_valid_id(id)) return -EINVAL;
  int length = snprintf(path, size, "%s/records/%s.json",
                        CONFIG_BUFFER_VELA_DATA_DIR, id);
  return length < 0 || (size_t)length >= size ? -ENAMETOOLONG : 0;
}

cJSON *buffer_agent_read_json(const char *path, size_t limit)
{
  FILE *file = fopen(path, "r");
  if (!file) return NULL;
  char *bytes = malloc(limit + 1);
  if (!bytes) { fclose(file); return NULL; }
  size_t count = fread(bytes, 1, limit, file);
  bool failed = ferror(file) || count == limit;
  fclose(file);
  bytes[count] = 0;
  cJSON *json = failed ? NULL : cJSON_Parse(bytes);
  free(bytes);
  return json;
}

int buffer_store_capture_target(const char *id, char *target, size_t size)
{
  char path[320];
  if (!target || !size || buffer_agent_record_path(path, sizeof(path), id))
    return -EINVAL;
  cJSON *record = buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
  if (!record) return -ENOENT;
  const cJSON *value = cJSON_GetObjectItemCaseSensitive(record, "target");
  const char *name = cJSON_IsString(value) ? value->valuestring : "buffer";
  if (strcmp(name, "buffer") && strcmp(name, "assistant") && strcmp(name, "auto"))
    { cJSON_Delete(record); return -EINVAL; }
  int written = snprintf(target, size, "%s", name);
  cJSON_Delete(record);
  return written < 0 || (size_t)written >= size ? -ENOSPC : 0;
}

bool buffer_oobe_setup_complete(void) { return true; }

int buffer_store_load_oobe_completed(bool *completed)
{
  if (!completed) return -EINVAL;
  *completed = true;
  return 0;
}

int buffer_agent_atomic_json(const char *path, const cJSON *json)
{ write_json(path, json); return 0; }

void buffer_agent_snapshot(struct buffer_agent_status_s *status){memset(status,0,sizeof(*status));status->ready=true;}
int buffer_audio_store_pcm(const struct buffer_memory_capture_s *job){(void)job;return 0;}
int buffer_agent_process_audio(const struct buffer_agent_config_s *config,const struct buffer_memory_capture_s *job,bool *consumed)
{(void)config;(void)job;*consumed=true;return 0;}
int buffer_agent_process_capture(const struct buffer_agent_config_s *config,
                                 const char *id)
{
  (void)config;
  assert(attempt_count < sizeof(attempted) / sizeof(attempted[0]));
  snprintf(attempted[attempt_count++], BUFFER_ID_SIZE, "%s", id);
  if (!strcmp(id, "bad-audio") && bad_audio_fails) return -EPROTO;

  char path[320];
  if (buffer_agent_record_path(path, sizeof(path), id) == 0)
    {
      cJSON *record = buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
      if (record)
        {
          cJSON_ReplaceItemInObjectCaseSensitive(record, "state",
                                                  cJSON_CreateString("ready"));
          write_json(path, record);
          cJSON_Delete(record);
        }
    }
  if (strcmp(id, "later-text"))
    {
      snprintf(path, sizeof(path), "%s/queue/%s.wav",
               CONFIG_BUFFER_VELA_DATA_DIR, id);
      (void)unlink(path);
    }
  return 0;
}

int buffer_store_list_pending(char ids[][BUFFER_ID_SIZE], int max_ids)
{
  char directory[320];
  snprintf(directory, sizeof(directory), "%s/queue",
           CONFIG_BUFFER_VELA_DATA_DIR);
  DIR *dir = opendir(directory);
  if (!dir) return -errno;
  int count = 0;
  struct dirent *entry;
  while ((entry = readdir(dir)) && count < max_ids)
    {
      size_t length = strlen(entry->d_name);
      if (length < 5 || strcmp(entry->d_name + length - 4, ".wav")) continue;
      size_t id_length = length - 4;
      if (!id_length || id_length >= BUFFER_ID_SIZE) continue;
      memcpy(ids[count], entry->d_name, id_length);
      ids[count][id_length] = 0;
      count++;
    }
  closedir(dir);
  return count;
}

int buffer_store_pending_count(void)
{
  char ids[CONFIG_BUFFER_VELA_QUEUE_LIMIT][BUFFER_ID_SIZE];
  return buffer_store_list_pending(ids, CONFIG_BUFFER_VELA_QUEUE_LIMIT);
}

int main(void)
{
  char path[320];
  snprintf(path, sizeof(path), "%s/records", CONFIG_BUFFER_VELA_DATA_DIR);
  assert(mkdir(path, 0700) == 0);
  snprintf(path, sizeof(path), "%s/queue", CONFIG_BUFFER_VELA_DATA_DIR);
  assert(mkdir(path, 0700) == 0);

  make_record("bad-audio", "pending_classification");
  make_audio("bad-audio"); /* simulates the permanently silent 320 ms clip */
  make_record("later-text", "pending_classification");
  make_record("later-voice", "pending_classification");
  make_audio("later-voice");

  bool attempted_capture = false;
  unsigned wait_ms = 0;
  /* A deck invalidated by startup/history recovery must not gate captures. */
  projection_result = -EAGAIN;
  assert(agent_process_once(true, false, &attempted_capture, &wait_ms) == 0);
  assert(attempted_capture && attempt_count == 2);
  assert(!strcmp(attempted[0], "bad-audio"));
  assert(!strcmp(attempted[1], "later-text"));
  projection_result = 0;

  assert(agent_process_once(true, false, &attempted_capture, &wait_ms) == 0);
  assert(attempt_count == 3 && !strcmp(attempted[2], "later-voice"));
  snprintf(path, sizeof(path), "%s/queue/bad-audio.wav",
           CONFIG_BUFFER_VELA_DATA_DIR);
  assert(access(path, F_OK) == 0);

  /* The public process_once path remains an immediate manual retry despite
   * the automatic worker's newly recorded backoff. */
  assert(buffer_agent_process_once() == -EPROTO);
  assert(attempt_count == 4 && !strcmp(attempted[3], "bad-audio"));
  bad_audio_fails = false;
  assert(buffer_agent_process_once() == 0);
  assert(attempt_count == 5 && !strcmp(attempted[4], "bad-audio"));
  snprintf(path, sizeof(path), "%s/queue/bad-audio.wav",
           CONFIG_BUFFER_VELA_DATA_DIR);
  assert(access(path, F_OK) != 0);
  return 0;
}

void buffer_a2ui_actions_unavailable(int error){(void)error;}
