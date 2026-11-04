/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_A2UI_INTERNAL_H
#define BUFFER_A2UI_INTERNAL_H

#include "buffer_a2ui.h"

#define BUFFER_A2UI_MESSAGE_MAX (64u * 1024u)
#define BUFFER_A2UI_SURFACE_COUNT 3
#define BUFFER_A2UI_COMPONENT_COUNT 128
#define BUFFER_A2UI_CHILD_COUNT 16
#define BUFFER_A2UI_DATA_COUNT 32
#define BUFFER_A2UI_COMPONENT_TEXT_MAX BUFFER_ASSISTANT_DISPLAY_TEXT_SIZE
#define BUFFER_A2UI_STRING_BUDGET 8192
#define BUFFER_A2UI_DATA_BUDGET 4096
#define BUFFER_A2UI_DEPTH_MAX 12
#define BUFFER_A2UI_SURFACE_ID_SIZE 64
#define BUFFER_A2UI_COMPONENT_ID_SIZE 32
#define BUFFER_A2UI_BINDING_SIZE 96
#define BUFFER_A2UI_ACTION_SIZE 48
#define BUFFER_A2UI_CONTEXT_SIZE 192
#define BUFFER_A2UI_CATALOG_SIZE 128
#define BUFFER_A2UI_CLOSED_IDS 8

enum buffer_a2ui_component_type_e
{
  BUFFER_A2UI_COLUMN = 1,
  BUFFER_A2UI_ROW,
  BUFFER_A2UI_CARD,
  BUFFER_A2UI_TEXT,
  BUFFER_A2UI_BUTTON,
  BUFFER_A2UI_DIVIDER,
  BUFFER_A2UI_TIMER_CARD,
  BUFFER_A2UI_ALARM_CARD,
  BUFFER_A2UI_TRANSLATION_RESULT,
  BUFFER_A2UI_RECIPE_STEP
};

struct buffer_a2ui_component_s
{
  char id[BUFFER_A2UI_COMPONENT_ID_SIZE];
  enum buffer_a2ui_component_type_e type;
  char text[BUFFER_A2UI_COMPONENT_TEXT_MAX];
  char binding[BUFFER_A2UI_BINDING_SIZE];
  char action[BUFFER_A2UI_ACTION_SIZE];
  char context_json[BUFFER_A2UI_CONTEXT_SIZE];
  uint8_t child_count;
  uint8_t children[BUFFER_A2UI_CHILD_COUNT];
  uint8_t variant;
  union
  {
    struct
    {
      char id[BUFFER_ASSISTANT_ID_SIZE];
      char label[BUFFER_ASSISTANT_LABEL_SIZE];
    } timer;
    struct
    {
      char id[BUFFER_ASSISTANT_ID_SIZE];
      char label[BUFFER_ASSISTANT_LABEL_SIZE];
      enum buffer_assistant_state_e state;
    } alarm;
    struct
    {
      char original[BUFFER_A2UI_COMPONENT_TEXT_MAX];
      char translated[BUFFER_A2UI_COMPONENT_TEXT_MAX];
      char source_language[32];
      char target_language[32];
    } translation;
    struct
    {
      char title[BUFFER_ASSISTANT_LABEL_SIZE];
      char detail[BUFFER_ASSISTANT_TEXT_SIZE];
      char timer_id[BUFFER_ASSISTANT_ID_SIZE];
      char request_id[BUFFER_ASSISTANT_ID_SIZE];
      uint32_t timer_ms;
    } recipe_step;
  } payload;
};

struct buffer_a2ui_data_s
{
  bool json_value;
  char path[BUFFER_A2UI_BINDING_SIZE];
  char value[BUFFER_A2UI_COMPONENT_TEXT_MAX];
};

struct buffer_a2ui_surface_s
{
  bool used;
  bool has_tree;
  bool local_surface;
  bool full_screen;
  char id[BUFFER_A2UI_SURFACE_ID_SIZE];
  char catalog_id[BUFFER_A2UI_CATALOG_SIZE];
  uint64_t task_revision;
  uint64_t sequence;
  char capture_id[BUFFER_ASSISTANT_ID_SIZE];
  char image_id[65];
  char recipe_operation_id[BUFFER_ASSISTANT_ID_SIZE];
  uint8_t component_count;
  uint8_t data_count;
  struct buffer_a2ui_component_s components[BUFFER_A2UI_COMPONENT_COUNT];
  struct buffer_a2ui_data_s data[BUFFER_A2UI_DATA_COUNT];
};

typedef void (*buffer_a2ui_refresh_hook_fn)(void);

int buffer_a2ui_model_get_active(struct buffer_a2ui_surface_s *surface);
int buffer_a2ui_model_surface_revision(const char *id,uint64_t *revision);
int buffer_a2ui_model_get_surface(const char *surface_id,
                                  struct buffer_a2ui_surface_s *surface);
int buffer_a2ui_model_bind_timer(const char *surface_id,
                                 const char *component_id,
                                 const char *timer_id,
                                 uint64_t revision,const char *request_id);
int buffer_a2ui_model_activate(const char *surface_id);
/* UI navigation: validate, activate and copy one coherent target without
 * waiting for model writers. The caller retains its current page on failure. */
int buffer_a2ui_model_try_activate(const char *surface_id,struct buffer_a2ui_surface_s *surface);
void buffer_a2ui_model_close(const char *surface_id);
int buffer_a2ui_model_set_refresh_hook(buffer_a2ui_refresh_hook_fn hook);
struct buffer_a2ui_action_status_s {bool pending,accepted;int result;char id[64];};
int buffer_a2ui_model_action_status(const char *surface,const char *component,
                                  struct buffer_a2ui_action_status_s *status);
int buffer_a2ui_model_emit_action(const char *surface_id,
                                  const char *component_id,
                                  const char *action_name,
                                  const char *context_json);
const struct buffer_a2ui_component_s *buffer_a2ui_find_component(
    const struct buffer_a2ui_surface_s *surface, const char *component_id);
const char *buffer_a2ui_component_text(
    const struct buffer_a2ui_surface_s *surface,
    const struct buffer_a2ui_component_s *component);

#endif
