/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_A2UI_H
#define BUFFER_A2UI_H

#include "buffer_assistant.h"

#define BUFFER_A2UI_REPLY_TEXT_MAX 1024
/* Unstarted recipes may have a result tile; started/completed recipes use
 * their activity lifecycle. Pending private-state load keeps the tile hidden. */
bool buffer_a2ui_ui_result_unread(const char *id,uint64_t sequence);
bool buffer_a2ui_ui_recipe_result_visible(const char *id,const char *fingerprint);

struct buffer_a2ui_action_s
{
  char id[64],surface_id[64],component_id[32],name[48],context_json[192];
  uint64_t revision,sequence;
};
/* Worker callback: zero means durably accepted, not execution success.
 * Complete the same action id when its local Agent request finishes. */
typedef int (*buffer_a2ui_action_fn)(const struct buffer_a2ui_action_s *,void *);
void buffer_a2ui_action_complete(const char *id,int result);
void buffer_a2ui_action_processing(const char *id);
void buffer_a2ui_actions_unavailable(int error);

/* Accepts one bounded A2UI v0.9.1 server-to-client envelope. task_revision
 * and explicit_show belong to the Vela transport adapter, not the A2UI JSON. */
int buffer_a2ui_apply_message(const char *json, size_t length,
                              uint64_t task_revision, bool explicit_show);
void buffer_a2ui_set_task_revision(uint64_t task_revision);
int buffer_a2ui_set_action_handler(buffer_a2ui_action_fn handler,
                                   void *context);
int buffer_a2ui_present_timer(const char *timer_id);
int buffer_a2ui_present_alarm(const char *alarm_id);
int buffer_a2ui_present_translation(const char *operation_id,
                                    const char *original,
                                    const char *translated,
                                    const char *source_language,
                                    const char *target_language);
int buffer_a2ui_present_recipe(const char *operation_id,
                              const char *title,
                              const char *ingredients,
                              const struct buffer_assistant_recipe_step_s *steps,
                              size_t step_count);
/* Present an UTF-8 assistant clarification/receipt as a scrollable, full-screen
 * Text surface. text is limited to 1024 UTF-8 bytes (excluding NUL). */
int buffer_a2ui_present_reply(const char *operation_id, const char *text);
int buffer_a2ui_delete_surface(const char *surface_id,
                               uint64_t task_revision);
void buffer_a2ui_close_active(void);

/* UI adapter lifecycle; call these only from the LVGL owner thread. */
int buffer_a2ui_ui_attach(void *home_body, void *record_button);
void buffer_a2ui_ui_detach(void);
void buffer_a2ui_ui_dock_notice(void *slot);
void buffer_a2ui_ui_dock_page(void *page);
void buffer_a2ui_ui_open_current(void);
/* Owner-thread handoff of the actual live transcript. A later new result
 * opens its session; an older active task never inherits the new prompt. */
void buffer_a2ui_ui_capture_prompt(const char *text,const char *capture_id);
/* Worker-local correlation scope; local presentation builders attach the
 * capture identity atomically to their result. End on every exit path. */
int buffer_a2ui_capture_context_begin(const char *capture_id);
void buffer_a2ui_capture_context_end(void);
bool buffer_a2ui_ui_open(void);
int buffer_a2ui_ui_open_surface(const char *id);
int buffer_a2ui_ui_open_recipe_surface(const char *id);
/* Recovery only: installs a missing recipe in a free slot, never activates,
 * replaces current content or executes timer operations. */
int buffer_a2ui_recipe_timer_request(char *out,size_t capacity,const char *operation_id,unsigned step);
int buffer_a2ui_restore_recipe(const struct buffer_assistant_request_s *request,const char timer_ids[][BUFFER_ASSISTANT_ID_SIZE],const char *expected_surface);
int buffer_a2ui_present_recipe_image(const char *operation_id,const char *title,const char *ingredients,const struct buffer_assistant_recipe_step_s *steps,size_t count,const char *image_id);
#define BUFFER_ASSISTANT_SURFACE_CARDS 3
struct buffer_assistant_surface_card_s
{
  bool recipe;
  uint64_t task_revision,sequence;
  char recipe_operation_id[64],recipe_fingerprint[17];
  char image_id[65];
  char id[BUFFER_ASSISTANT_ID_SIZE];
  char title[BUFFER_ASSISTANT_LABEL_SIZE];
  char preview[BUFFER_ASSISTANT_DISPLAY_TEXT_SIZE];
};
int buffer_a2ui_surface_cards(struct buffer_assistant_surface_card_s *cards,
                              size_t capacity);
void buffer_a2ui_ui_refresh(void);
void buffer_a2ui_ui_publish_activities(uint64_t now_ms);
bool buffer_a2ui_ui_recipe_running(const char *surface_id);

#endif
