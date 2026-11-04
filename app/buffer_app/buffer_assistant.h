/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_ASSISTANT_H
#define BUFFER_ASSISTANT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define BUFFER_ASSISTANT_ID_SIZE 64
#define BUFFER_ASSISTANT_LABEL_SIZE 96
#define BUFFER_ASSISTANT_TEXT_SIZE 256
#define BUFFER_ASSISTANT_DISPLAY_TEXT_SIZE 128
#define BUFFER_ASSISTANT_MAX_TIMERS 8
#define BUFFER_ASSISTANT_MAX_ALARMS 8
#define BUFFER_ASSISTANT_MAX_RECIPE_STEPS 8

enum buffer_assistant_operation_e
{
  BUFFER_ASSISTANT_TIMER_CREATE = 1,
  BUFFER_ASSISTANT_TIMER_PAUSE,
  BUFFER_ASSISTANT_TIMER_RESUME,
  BUFFER_ASSISTANT_TIMER_CANCEL,
  BUFFER_ASSISTANT_ALARM_CREATE,
  BUFFER_ASSISTANT_ALARM_CANCEL,
  BUFFER_ASSISTANT_TRANSLATION_SHOW,
  BUFFER_ASSISTANT_RECIPE_SHOW,
  /* Append to preserve persisted operation values. */
  BUFFER_ASSISTANT_TIMER_RESTART
};

enum buffer_assistant_state_e
{
  BUFFER_ASSISTANT_RUNNING = 1,
  BUFFER_ASSISTANT_PAUSED,
  BUFFER_ASSISTANT_FIRED,
  BUFFER_ASSISTANT_CANCELLED
};

struct buffer_assistant_recipe_step_s
{
  char title[BUFFER_ASSISTANT_LABEL_SIZE];
  char detail[BUFFER_ASSISTANT_TEXT_SIZE];
  uint32_t timer_ms;
};

/* All strings are bounded, NUL-terminated UTF-8. operation_id must remain
 * unchanged when the caller retries the same logical action. */
struct buffer_assistant_request_s
{
  enum buffer_assistant_operation_e operation;
  char operation_id[BUFFER_ASSISTANT_ID_SIZE];
  char object_id[BUFFER_ASSISTANT_ID_SIZE];
  char label[BUFFER_ASSISTANT_LABEL_SIZE];
  uint32_t duration_ms;
  int64_t scheduled_at_unix_ms;
  char original[BUFFER_ASSISTANT_TEXT_SIZE];
  char translated[BUFFER_ASSISTANT_TEXT_SIZE];
  char source_language[32];
  char target_language[32];
  char image_id[65];
  char recipe_title[BUFFER_ASSISTANT_LABEL_SIZE];
  char ingredients[BUFFER_ASSISTANT_TEXT_SIZE];
  uint8_t step_count;
  struct buffer_assistant_recipe_step_s steps[BUFFER_ASSISTANT_MAX_RECIPE_STEPS];
};

struct buffer_assistant_result_s
{
  enum buffer_assistant_state_e state;
  char object_id[BUFFER_ASSISTANT_ID_SIZE];
  uint32_t remaining_ms;
  bool duplicate;
};

struct buffer_assistant_item_s
{
  enum buffer_assistant_state_e state;
  char object_id[BUFFER_ASSISTANT_ID_SIZE];
  char label[BUFFER_ASSISTANT_LABEL_SIZE];
  uint32_t remaining_ms;
  int64_t scheduled_at_unix_ms;
  bool recipe_step;
};

enum buffer_assistant_event_e
{
  BUFFER_ASSISTANT_EVENT_TIMER = 1,
  BUFFER_ASSISTANT_EVENT_ALARM = 2
};

typedef void (*buffer_assistant_event_fn)(enum buffer_assistant_event_e event,
                                           const char *object_id,
                                           const char *label,
                                           void *context);

int buffer_assistant_init(void);
int buffer_assistant_execute(const struct buffer_assistant_request_s *request,
                             struct buffer_assistant_result_s *result);
int buffer_assistant_snapshot(enum buffer_assistant_event_e type,
                              struct buffer_assistant_item_s *items,
                              size_t capacity, size_t *count);
int buffer_assistant_get(enum buffer_assistant_event_e type,
                         const char *object_id,
                         struct buffer_assistant_item_s *item);
void buffer_assistant_set_event_handler(buffer_assistant_event_fn handler,
                                        void *context);

/* Last committed in-memory state; -EAGAIN leaves the caller's view intact. */
/* Worker-only, reads immutable committed requests and restores presentation.
 * Never executes a timer operation or invokes the model API provider. */
int buffer_assistant_restore_recipe(const char *operation_id,const char *surface_id,char image_id[65]);
int buffer_assistant_ui_snapshot(enum buffer_assistant_event_e type,
                                 struct buffer_assistant_item_s *items,
                                 size_t capacity, size_t *count);
int buffer_assistant_ui_get(enum buffer_assistant_event_e type,
                            const char *object_id, struct buffer_assistant_item_s *item);

#endif
