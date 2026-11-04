/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_AGENT_H
#define BUFFER_AGENT_H
#include "buffer_app.h"
#include "buffer_oobe.h"
#include <netutils/cJSON.h>

#define BUFFER_AGENT_TEXT_BYTES 8192
#define BUFFER_AGENT_JSON_BYTES (64 * 1024)
#define BUFFER_AGENT_RECORDS_LIMIT 256
#define BUFFER_AGENT_SEGMENTS_LIMIT 8

struct buffer_agent_config_s
{
  struct buffer_oobe_request_s request;
  bool validated;
  int last_error;
};

struct buffer_agent_status_s
{
  uint64_t revision;
  bool configured;
  bool ready;
  bool skipped;
  bool validating;
  int error;
};

/* Auth callback belongs to Fabric's committed session/principal/grant. No
 * application peer-trust database is created here. It must reject stale or
 * unauthorized channels. Invoke the handler on an application worker, not a
 * radio callback/LVGL thread. It can perform filesystem and Wi-Fi operations. */
struct buffer_oobe_authorization_s
{
  int (*authorize)(void *context, enum buffer_oobe_operation_e operation);
  void *context;
};
int buffer_oobe_handle_message(const struct buffer_oobe_authorization_s *auth,
                               const unsigned char *data, size_t length,
                               char **reply);

int buffer_agent_store_init(void);
int buffer_agent_config_load(struct buffer_agent_config_s *config);
int buffer_agent_config_apply(const struct buffer_oobe_request_s *request);
int buffer_agent_config_result(const struct buffer_agent_config_s *config,
                               bool validated, int error);
void buffer_agent_config_wipe(struct buffer_agent_config_s *config);
void buffer_agent_snapshot(struct buffer_agent_status_s *status);
void buffer_agent_set_validating(bool validating);
void buffer_agent_report_error(int error);
const char *buffer_agent_error_code(int error);
int buffer_agent_atomic_json(const char *path, const cJSON *json);
cJSON *buffer_agent_read_json(const char *path, size_t limit);
/* Private-store primitives for the source journal. Bypass record hooks only
 * while replaying its durable intent; never use them for ordinary record edits. */
int buffer_agent_private_directory(const char *path);
int buffer_agent_sync_parent(const char *path);
int buffer_agent_atomic_bytes(const char *path, const char *data, size_t length, size_t limit);
int buffer_agent_read_bytes(const char *path, size_t limit, char **data, size_t *length);
cJSON *buffer_agent_read_json_plain(const char *path, size_t limit);
cJSON *buffer_agent_parse_json(const char *raw);
int buffer_agent_record_path(char *path, size_t size, const char *capture_id);
bool buffer_agent_valid_id(const char *id);

/* Pump only on an authorized Fabric channel; reset on each new Session. */
int buffer_agent_outbox_next(size_t max_payload, char **message);
int buffer_agent_outbox_ack(const unsigned char *data, size_t length);
void buffer_agent_outbox_reset(void);
int buffer_agent_card_state_next(uint64_t now_ms, size_t max_payload,
                                 char **message);
int buffer_agent_card_state_ack(const unsigned char *data, size_t length);
void buffer_agent_card_state_reset(void);

int buffer_agent_command(int argc, char **argv);
int buffer_agent_start(void);
void buffer_agent_stop(void);
void buffer_agent_retry(void);
void buffer_agent_wake(void);
/* Prioritize one explicitly accepted local interaction; durable queue remains authoritative. */
void buffer_agent_prioritize(const char *capture_id);
int buffer_agent_submit_text(const char *capture_id, const char *text,
                             const char *mode, int64_t created_at);
/* Submit synthetic/local text using the same semantic routing as microphone
 * captures. The legacy `agent text` command remains Buffer-only. */
int buffer_agent_submit_auto_text(const char *capture_id,const char *text,
                                  const char *mode,int64_t created_at);
int buffer_agent_submit_assistant_text(const char *capture_id,const char *text,int64_t created_at);
/* One actual bounded worker cycle, also used by the standalone HTTPS test
 * fixture. It calls mbedTLS and the real store/tools; no model implementation
 * or transport is injected here. */
/* One bounded in-memory utterance; submission transfers PCM only on success. */
struct buffer_memory_capture_s {
  char id[BUFFER_ID_SIZE],mode[BUFFER_MODE_SIZE];
  enum buffer_capture_target_e target;
  unsigned char *pcm;
  uint32_t bytes,duration_ms;
  int64_t created_at;
};
int buffer_agent_submit_audio(const struct buffer_memory_capture_s *job);
int buffer_agent_process_audio(const struct buffer_agent_config_s *config,
                               const struct buffer_memory_capture_s *job,bool *consumed);
int buffer_audio_store_pcm(const struct buffer_memory_capture_s *job);
int buffer_agent_process_once(void);
int buffer_agent_validate(const struct buffer_agent_config_s *config);
int buffer_agent_process_capture(const struct buffer_agent_config_s *config,
                                 const char *capture_id);
/* Remove the durable assistant-tool resume log after its final capture record
 * has been committed. Safe to call when no checkpoint exists. */
int buffer_agent_assistant_checkpoint_clear(const char *capture_id);
int buffer_agent_refresh_cards(void);
int buffer_agent_apply_action(const char *card_id, const char *action);

/* Serializes ownership of queued captures with the existing phone uploader.
 * Phone availability is an app policy; local timers remain independent. */
extern pthread_mutex_t g_buffer_capture_lock;
#endif
