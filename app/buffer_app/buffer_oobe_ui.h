/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_OOBE_UI_H
#define BUFFER_OOBE_UI_H

#include <stdbool.h>
#include <stdint.h>

#include <lvgl/lvgl.h>

enum buffer_oobe_ui_page_e
{
  BUFFER_OOBE_UI_WELCOME = 0,
  BUFFER_OOBE_UI_VERIFY,
  BUFFER_OOBE_UI_NETWORK,
  BUFFER_OOBE_UI_SERVICE,
  BUFFER_OOBE_UI_READY,
  BUFFER_OOBE_UI_BLOCKED,
};

enum buffer_oobe_ui_hint_e
{
  BUFFER_OOBE_UI_HINT_NONE = 0,
  BUFFER_OOBE_UI_HINT_WAITING_PHONE,
  BUFFER_OOBE_UI_HINT_NETWORK_CONNECTING,
  BUFFER_OOBE_UI_HINT_NETWORK_RETRY,
  BUFFER_OOBE_UI_HINT_SERVICE_CONFIGURING,
  BUFFER_OOBE_UI_HINT_SERVICE_VALIDATING,
  BUFFER_OOBE_UI_HINT_SERVICE_RETRY,
  BUFFER_OOBE_UI_HINT_START_RETRY,
};

enum buffer_oobe_ui_action_e
{
  BUFFER_OOBE_UI_ACTION_RETRY = 0,
  BUFFER_OOBE_UI_ACTION_CONFIRM,
  BUFFER_OOBE_UI_ACTION_REJECT,
  BUFFER_OOBE_UI_ACTION_START,
};

struct buffer_oobe_ui_state_s
{
  enum buffer_oobe_ui_page_e page;
  enum buffer_oobe_ui_hint_e hint;
  const char *qr_payload;
  const char *pairing_code;
  uint64_t request_epoch;
  bool retry_visible;
  bool retry_busy;
  bool confirm_visible;
  bool reject_visible;
  bool start_busy;
  bool english_fallback;
};

struct buffer_oobe_ui_s;
typedef void (*buffer_oobe_ui_action_fn)(enum buffer_oobe_ui_action_e action,
                                         void *context);

struct buffer_oobe_ui_s *buffer_oobe_ui_create(
    lv_obj_t *screen, const lv_font_t *hero_font,
    const lv_font_t *body_font, const lv_font_t *small_font,
    buffer_oobe_ui_action_fn action, void *context);
void buffer_oobe_ui_render(struct buffer_oobe_ui_s *ui,
                           const struct buffer_oobe_ui_state_s *state);
void buffer_oobe_ui_set_visible(struct buffer_oobe_ui_s *ui, bool visible);
void buffer_oobe_ui_destroy(struct buffer_oobe_ui_s *ui);

#endif /* BUFFER_OOBE_UI_H */
