/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela app
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#ifndef __CONTEST2026_014_BUFFER_APP_H
#define __CONTEST2026_014_BUFFER_APP_H

#include <nuttx/config.h>

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <pthread.h>

#define BUFFER_CARD_LIMIT 5
#define BUFFER_ID_SIZE 80
#define BUFFER_TEXT_SIZE 192
#define BUFFER_STATUS_SIZE 128
#define BUFFER_MODE_SIZE 20
#define BUFFER_ACTION_SIZE 32
#define BUFFER_ACTION_ID_SIZE BUFFER_ID_SIZE
#define BUFFER_CARD_ACTIONS_SIZE 128
#define BUFFER_PAIRING_CODE_SIZE 7
#define BUFFER_CARD_ACTION_BUTTONS 5

#ifndef CONFIG_BUFFER_VELA_DATA_DIR
#  define CONFIG_BUFFER_VELA_DATA_DIR "/data/buffer"
#endif
#ifndef CONFIG_BUFFER_VELA_PHONE_HOST
#  define CONFIG_BUFFER_VELA_PHONE_HOST "192.168.1.100"
#endif
#ifndef CONFIG_BUFFER_VELA_PHONE_PORT
#  define CONFIG_BUFFER_VELA_PHONE_PORT 48888
#endif
#ifndef CONFIG_BUFFER_VELA_WIFI_IFNAME
#  define CONFIG_BUFFER_VELA_WIFI_IFNAME "wlan0"
#endif
#ifndef CONFIG_BUFFER_VELA_DISCOVERY_PORT
#  define CONFIG_BUFFER_VELA_DISCOVERY_PORT 48886
#endif
#ifndef CONFIG_BUFFER_VELA_PAIRING_PORT
#  define CONFIG_BUFFER_VELA_PAIRING_PORT 48887
#endif
#ifndef CONFIG_BUFFER_VELA_MAX_RECORD_MS
#  define CONFIG_BUFFER_VELA_MAX_RECORD_MS 8000
#endif
#ifndef CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES
#  define CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES (512 * 1024)
#endif
#ifndef CONFIG_BUFFER_VELA_QUEUE_LIMIT
#  define CONFIG_BUFFER_VELA_QUEUE_LIMIT 8
#endif
#ifndef CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS
#  define CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS (50 * 60 * 1000)
#endif

struct buffer_card_s
{
  char card_id[BUFFER_ID_SIZE];
  char capture_id[BUFFER_ID_SIZE];
  char kind[32];
  char title[BUFFER_TEXT_SIZE];
  char summary[BUFFER_TEXT_SIZE];
  char answer[BUFFER_TEXT_SIZE];
  char state[32];
  char actions[BUFFER_CARD_ACTIONS_SIZE];
  int64_t created_at;
};

#define BUFFER_FOCUS_INTERVAL_MIN_MS 300000u
#define BUFFER_FOCUS_INTERVAL_MAX_MS 10800000u

struct buffer_focus_config_s
{
  bool active;
  bool clear_notice;
  uint32_t interval_ms;
  uint32_t remaining_ms;
  uint32_t notice_remaining_ms;
  char revision[BUFFER_ID_SIZE];
};

struct buffer_research_s
{
  bool present;
  bool active;
  uint32_t remaining_ms;
  char id[BUFFER_ID_SIZE];
  char title[BUFFER_TEXT_SIZE];
  char reason[32];
};

struct buffer_runtime_s
{
  pthread_mutex_t lock;
  bool initialized;
  bool stopping;
  bool recording;
  bool touch_requested;
  bool touch_released;
  volatile bool record_stop;
  bool input_thread_started;
  bool sync_thread_started;
  bool record_thread_started;
  bool pairing_thread_started;
  bool timer_thread_started;
  bool sync_requested;
  bool action_requested;
  bool mode_dirty;
  bool cards_stale;
  bool phone_synced;
  bool focus_reminder_due;
  pthread_t input_thread;
  pthread_t sync_thread;
  pthread_t record_thread;
  pthread_t pairing_thread;
  pthread_t timer_thread;
  char record_id[BUFFER_ID_SIZE];
  char record_mode[BUFFER_MODE_SIZE];
  char pairing_code[BUFFER_PAIRING_CODE_SIZE];
  unsigned int pairing_failures;
  int64_t pairing_block_until;
  char action_card_id[BUFFER_ID_SIZE];
  char action_id[BUFFER_ACTION_ID_SIZE];
  char action[BUFFER_ACTION_SIZE];
  char mode[BUFFER_MODE_SIZE];
  char mode_id[BUFFER_ACTION_ID_SIZE];
  char device_id[BUFFER_ID_SIZE];
  char status[BUFFER_STATUS_SIZE];
  int pending_count;
  int card_index;
  struct buffer_research_s research;
  int64_t research_deadline;
  uint32_t focus_interval_ms;
  char focus_revision[BUFFER_ID_SIZE];
  int64_t focus_started_at;
  int64_t next_care_at;
  int64_t focus_reminder_until;
  struct buffer_card_s cards[BUFFER_CARD_LIMIT];
  int card_count;
};

extern struct buffer_runtime_s g_buffer;

int buffer_touch_record_start(void);
void buffer_touch_record_stop(void);
bool buffer_touch_record_active(void);

void buffer_set_status(const char *format, ...);
void buffer_request_sync(void);
void buffer_request_action(const char *action);
void buffer_request_mode(const char *mode);
void buffer_request_card_step(int delta);
int buffer_update_cards(const struct buffer_card_s *cards, int count);
void buffer_sync_remote_mode(const char *mode);
void buffer_mark_cards_stale(void);
bool buffer_cards_are_stale(void);
bool buffer_card_supports_action(const struct buffer_card_s *card,
                                 const char *action);
int buffer_current_card_index(void);
int buffer_sync_research(const struct buffer_research_s *research);
bool buffer_research_snapshot(char *text, size_t text_size);
bool buffer_focus_snapshot(char *text, size_t text_size);
void buffer_snapshot(char *status, size_t status_size,
                     char *mode, size_t mode_size,
                     struct buffer_card_s *cards, int *card_count,
                     int *pending_count);

int buffer_ble_start(void);
void buffer_ble_stop(void);

int buffer_store_init(void);
int buffer_store_load_focus_interval(uint32_t *interval);
int buffer_store_save_focus_interval(uint32_t interval);
int buffer_sync_focus_config(const struct buffer_focus_config_s *config);
int buffer_store_pending_count(void);
int buffer_store_queue_limit_reached(void);
int buffer_store_list_pending(char ids[][BUFFER_ID_SIZE], int max_ids);
int buffer_store_read_audio(const char *capture_id,
                            unsigned char **data, size_t *length);
int buffer_store_read_metadata(const char *capture_id, int64_t *created_at,
                               uint32_t *duration_ms, char *mode,
                               size_t mode_size);
int buffer_store_set_capture_state(const char *capture_id, const char *state);
int buffer_store_ack(const char *capture_id);
int buffer_store_save_enrollment(const char *host, const char *token,
                                 const char *phone_id, const char *request_id);
int buffer_store_load_enrollment(char *host, size_t host_size,
                                 char *token, size_t token_size,
                                 char *phone_id, size_t phone_id_size,
                                 char *request_id, size_t request_id_size);
int buffer_store_save_pairing(const char *host, const char *token,
                              const char *phone_device_id);
int buffer_store_load_pairing(char *host, size_t host_size,
                              char *token, size_t token_size,
                              char *phone_device_id,
                              size_t phone_device_id_size);
int buffer_store_load_device_id(char *device_id, size_t device_id_size);
int buffer_store_load_pairing_code(char *pairing_code,
                                   size_t pairing_code_size);
int buffer_store_load_mode(char *mode, size_t mode_size);
int buffer_store_save_mode(const char *mode);
int buffer_store_save_pending_action(const char *card_id,
                                     const char *action_id,
                                     const char *action);
int buffer_store_load_pending_action(char *card_id, size_t card_id_size,
                                     char *action_id, size_t action_id_size,
                                     char *action, size_t action_size);
void buffer_store_clear_pending_action(void);
int buffer_store_save_pending_mode(const char *mode, const char *mode_id);
int buffer_store_load_pending_mode(char *mode, size_t mode_size,
                                   char *mode_id, size_t mode_id_size);
void buffer_store_clear_pending_mode(void);
int buffer_store_load_cards(struct buffer_card_s *cards, int max_cards);
int buffer_store_save_cards(const struct buffer_card_s *cards, int count);

int buffer_wifi_configure(const char *ssid, const char *passphrase);
int buffer_wifi_reconnect(void);

int buffer_audio_record(const char *capture_id, const char *mode,
                        volatile bool *stop, uint32_t *duration_ms);

int buffer_wire_sync_once(void);
void *buffer_wire_thread_main(void *arg);
void *buffer_pairing_thread_main(void *arg);

void buffer_pairing_snapshot(char *device_id, size_t device_id_size,
                             char *pairing_code, size_t pairing_code_size);

int buffer_ui_init(bool defer_to_lvgl_thread);
void buffer_ui_shutdown(void);

#endif /* __CONTEST2026_014_BUFFER_APP_H */
