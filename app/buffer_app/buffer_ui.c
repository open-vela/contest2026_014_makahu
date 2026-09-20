/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela card shelf UI
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_ble_service.h"

#include <errno.h>
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "buffer_ui_theme.h"
#include "buffer_ui_icons.h"

static lv_obj_t *g_root;
static lv_obj_t *g_record_button;
static lv_obj_t *g_record_label;
static uint32_t g_record_tick;
static bool g_touch_recording;
static bool g_paired;
static lv_obj_t *g_status_label;
static lv_obj_t *g_mode_label;
static lv_obj_t *g_pairing_label;
static lv_obj_t *g_connection_label;
static lv_obj_t *g_card_title;
static lv_obj_t *g_card_summary;
static lv_obj_t *g_queue_label;
static lv_obj_t *g_action_row;
static lv_obj_t *g_card_panel;
static lv_obj_t *g_controls;
static lv_obj_t *g_focus_panel;
static lv_obj_t *g_focus_arc;
static lv_obj_t *g_focus_time;
static lv_obj_t *g_focus_next;
static lv_obj_t *g_action_buttons[BUFFER_CARD_ACTION_BUTTONS];
static const char *g_action_names[BUFFER_CARD_ACTION_BUTTONS] = {
  "later", "done", "archive", "remind", "rabbit_hole"
};
static lv_timer_t *g_refresh_timer;
static bool g_init_pending;

#if LV_USE_FREETYPE
static lv_font_t *g_font_large;
static lv_font_t *g_font_small;
static lv_font_t *g_font_timer;

static void buffer_fonts_init(void)
{
  const char *path = "/data/fonts/MiSansVF.ttf";

  g_font_large = lv_freetype_font_create(path,
      LV_FREETYPE_FONT_RENDER_MODE_BITMAP, 20, LV_FREETYPE_FONT_STYLE_NORMAL);
  g_font_small = lv_freetype_font_create(path,
      LV_FREETYPE_FONT_RENDER_MODE_BITMAP, 14, LV_FREETYPE_FONT_STYLE_NORMAL);
  g_font_timer = lv_freetype_font_create(path,
      LV_FREETYPE_FONT_RENDER_MODE_BITMAP, 42, LV_FREETYPE_FONT_STYLE_NORMAL);
  if (g_font_large == NULL || g_font_small == NULL)
    {
      printf("Buffer: unable to load MiSans VF: %s\n", path);
    }
}
#endif

static const lv_font_t *buffer_font_large(void)
{
#if LV_USE_FREETYPE
  return g_font_large != NULL ? g_font_large : LV_FONT_DEFAULT;
#elif LV_FONT_MONTSERRAT_20
  return &lv_font_montserrat_20;
#else
  return LV_FONT_DEFAULT;
#endif
}

static const lv_font_t *buffer_font_small(void)
{
#if LV_USE_FREETYPE
  return g_font_small != NULL ? g_font_small : LV_FONT_DEFAULT;
#elif LV_FONT_MONTSERRAT_12
  return &lv_font_montserrat_12;
#else
  return LV_FONT_DEFAULT;
#endif
}

static void buffer_record_event(lv_event_t *event)
{
  lv_event_code_t code = lv_event_get_code(event);
  if (code == LV_EVENT_PRESSED && !g_touch_recording)
    {
      if (buffer_touch_record_start() == 0)
        {
          g_touch_recording = true;
          g_record_tick = lv_tick_get();
          lv_label_set_text(g_record_label, "松开保存 · 0 秒");
        }
    }
  else if ((code == LV_EVENT_RELEASED || code == LV_EVENT_PRESS_LOST) &&
           g_touch_recording)
    {
      buffer_touch_record_stop();
      g_touch_recording = false;
      lv_label_set_text(g_record_label, "正在保存…");
    }
}

static void buffer_action_event(lv_event_t *event)
{
  const char *action = lv_event_get_user_data(event);
  buffer_request_action(action);
}

static void buffer_sync_event(lv_event_t *event)
{
  (void)event;
  buffer_request_sync();
  buffer_set_status("已请求同步");
}

static void buffer_mode_event(lv_event_t *event)
{
  static const char *modes[] = {"normal", "focus", "bedtime", "rabbit_hole"};
  char mode[BUFFER_MODE_SIZE];
  int index = 0;
  int i;

  (void)event;
  buffer_snapshot(NULL, 0, mode, sizeof(mode), NULL, NULL, NULL);
  for (i = 0; i < 4; i++)
    {
      if (strcmp(mode, modes[i]) == 0)
        {
          index = (i + 1) % 4;
          break;
        }
    }

  buffer_request_mode(modes[index]);
}

static void buffer_previous_card_event(lv_event_t *event)
{
  (void)event;
  buffer_request_card_step(-1);
}

static void buffer_next_card_event(lv_event_t *event)
{
  (void)event;
  buffer_request_card_step(1);
}

static lv_obj_t *buffer_button(lv_obj_t *parent, const char *text,
                               lv_event_cb_t callback, void *user_data)
{
  lv_obj_t *button = lv_button_create(parent);
  lv_obj_t *label = lv_label_create(button);

  lv_obj_set_size(button, 64, 48);
  lv_obj_set_style_radius(button, 24, 0);
  lv_obj_set_style_shadow_width(button, 0, 0);
  lv_obj_set_style_bg_color(button, lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  lv_obj_set_style_bg_color(button, lv_color_hex(0xA9D4BA), LV_STATE_PRESSED);
  lv_obj_set_style_text_color(button, lv_color_hex(0x062114), 0);
  lv_obj_set_style_text_font(label, buffer_font_small(), 0);
  lv_label_set_text(label, text);
  lv_obj_center(label);
  if (callback != NULL)
    lv_obj_add_event_cb(button, callback, LV_EVENT_CLICKED, user_data);
  return button;
}

static const char *buffer_mode_label(const char *mode)
{
  if (!strcmp(mode, "focus")) return "专注";
  if (!strcmp(mode, "bedtime")) return "睡前";
  if (!strcmp(mode, "rabbit_hole")) return "研究";
  return "此刻";
}

static void buffer_visible(lv_obj_t *obj, bool visible)
{
  if (visible) lv_obj_remove_flag(obj, LV_OBJ_FLAG_HIDDEN);
  else lv_obj_add_flag(obj, LV_OBJ_FLAG_HIDDEN);
}

static void buffer_focus_refresh(void)
{
  struct timespec ts;
  char text[80];
  int64_t started, next, interval, now, remaining, elapsed;
  bool due;
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return;
  now = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
  pthread_mutex_lock(&g_buffer.lock);
  started = g_buffer.focus_started_at;
  next = g_buffer.next_care_at;
  interval = g_buffer.focus_interval_ms;
  due = g_buffer.focus_reminder_due;
  pthread_mutex_unlock(&g_buffer.lock);
  if (interval <= 0) interval = CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
  elapsed = started > 0 && now > started ? (now - started) / 1000 : 0;
  remaining = next > now ? next - now : 0;
  if (elapsed >= 3600)
    snprintf(text, sizeof(text), "%02lld:%02lld:%02lld",
             (long long)(elapsed / 3600), (long long)(elapsed / 60 % 60),
             (long long)(elapsed % 60));
  else
    snprintf(text, sizeof(text), "%02lld:%02lld",
             (long long)(elapsed / 60), (long long)(elapsed % 60));
  lv_label_set_text(g_focus_time, text);
  if (started <= 0 || next <= 0)
    snprintf(text, sizeof(text), "正在准备专注计时");
  else if (due)
    snprintf(text, sizeof(text), "到休息时间了");
  else
    {
      int64_t seconds = (remaining + 999) / 1000;
      snprintf(text, sizeof(text), "下次休息 %02lld:%02lld 后",
               (long long)(seconds / 60), (long long)(seconds % 60));
    }
  lv_label_set_text(g_focus_next, text);
  int progress = interval > 0 ? (int)((interval - remaining) * 1000 / interval) : 0;
  if (progress < 0 || started <= 0) progress = 0;
  if (progress > 1000 || due) progress = 1000;
  lv_arc_set_value(g_focus_arc, progress);
}

static void buffer_ui_refresh(lv_timer_t *timer)
{
  static struct buffer_card_s cards[BUFFER_CARD_LIMIT];
  char status[BUFFER_STATUS_SIZE];
  char mode[BUFFER_MODE_SIZE];
  char pairing_code[BUFFER_PAIRING_CODE_SIZE];
  char line[BUFFER_TEXT_SIZE * 2 + 64];
  char focus_status[BUFFER_TEXT_SIZE + 80];
  int card_count;
  int pending_count;
  int card_index;
  bool cards_stale;

  (void)timer;
  if (g_root == NULL)
    {
      return;
    }

  buffer_snapshot(status, sizeof(status), mode, sizeof(mode), cards,
                  &card_count, &pending_count);
  bool focus = strcmp(mode, "focus") == 0;
  buffer_visible(g_focus_panel, focus);
  buffer_visible(g_card_panel, !focus);
  buffer_visible(g_queue_label, !focus);
  buffer_visible(g_controls, !focus);
  buffer_visible(g_status_label, !focus);
  if (focus) buffer_focus_refresh();
  buffer_pairing_snapshot(NULL, 0, pairing_code, sizeof(pairing_code));
  card_index = buffer_current_card_index();
  cards_stale = buffer_cards_are_stale();
  lv_label_set_text(g_status_label, status);
  if (g_touch_recording && buffer_touch_record_active())
    {
      snprintf(line, sizeof(line), "松开保存 · %lu 秒",
               (unsigned long)(lv_tick_elaps(g_record_tick) / 1000));
      lv_label_set_text(g_record_label, line);
    }
  else if (!buffer_touch_record_active())
    {
      g_touch_recording = false;
      lv_label_set_text(g_record_label, "按住，说点什么");
    }
  lv_obj_set_style_bg_color(g_record_button,
      lv_color_hex(g_touch_recording ? BUFFER_ERROR : BUFFER_ACCENT), 0);
  if (!focus && buffer_research_snapshot(focus_status, sizeof(focus_status)))
    {
      snprintf(line, sizeof(line), "%s · %s", buffer_mode_label(mode),
               focus_status);
    }
  else
    {
      snprintf(line, sizeof(line), "%s", buffer_mode_label(mode));
    }
  lv_label_set_text(g_mode_label, line);
  /* Pairing is durable: temporary loss of Wi-Fi must not reveal the code.
   * Latch successful pairing to avoid flickering during an atomic file update.
   */
  if (!g_paired)
    {
      char host[80], token[128], phone[BUFFER_ID_SIZE];
      g_paired = buffer_store_load_pairing(host, sizeof(host), token,
          sizeof(token), phone, sizeof(phone)) == 0;
      memset(token, 0, sizeof(token));
    }
  pthread_mutex_lock(&g_buffer.lock);
  bool synced = g_buffer.phone_synced;
  pthread_mutex_unlock(&g_buffer.lock);
  lv_label_set_text(g_connection_label, g_paired ?
      (synced ? "已连接" : "等待同步") : "待配对");
  if (g_paired)
    {
      lv_obj_add_flag(g_pairing_label, LV_OBJ_FLAG_HIDDEN);
    }
  else
    {
      char ble_code[BUFFER_PAIRING_CODE_SIZE];
      buffer_ble_service_code(ble_code, sizeof(ble_code));
      snprintf(line, sizeof(line), "%s %s", ble_code[0] ? "蓝牙验证码" : "配对码",
               ble_code[0] ? ble_code : pairing_code);
      lv_label_set_text(g_pairing_label, line);
      lv_obj_remove_flag(g_pairing_label, LV_OBJ_FLAG_HIDDEN);
    }
  snprintf(line, sizeof(line), "卡片 %d/%d%s",
           card_count > 0 ? card_index + 1 : 0, card_count,
           cards_stale ? "  ·  离线缓存" : "");
  lv_label_set_text(g_queue_label, line);
  if (card_count == 0 || focus)
    lv_obj_add_flag(g_action_row, LV_OBJ_FLAG_HIDDEN);
  else
    lv_obj_remove_flag(g_action_row, LV_OBJ_FLAG_HIDDEN);

  if (card_count == 0)
    {
      lv_label_set_text(g_card_title, "还没有卡片");
      lv_label_set_text(g_card_summary, "按住屏幕上的录音按钮，松开收好。手机连接后会整理成卡片。");
    }
  else
    {
      if (card_index < 0 || card_index >= card_count)
        {
          card_index = 0;
        }
      lv_label_set_text(g_card_title, cards[card_index].title);
      if (cards[card_index].answer[0] != '\0')
        {
          snprintf(line, sizeof(line), "问题：%s\n答案：%s",
                   cards[card_index].summary, cards[card_index].answer);
          lv_label_set_text(g_card_summary, line);
        }
      else
        {
          lv_label_set_text(g_card_summary, cards[card_index].summary);
        }
    }

  for (int index = 0; index < BUFFER_CARD_ACTION_BUTTONS; index++)
    {
      bool visible = card_count > 0 &&
                     buffer_card_supports_action(&cards[card_index],
                                                 g_action_names[index]);
      if (visible)
        {
          lv_obj_clear_flag(g_action_buttons[index], LV_OBJ_FLAG_HIDDEN);
        }
      else
        {
          lv_obj_add_flag(g_action_buttons[index], LV_OBJ_FLAG_HIDDEN);
        }
    }
}

static int buffer_ui_build(void)
{
  lv_obj_t *header;
  lv_obj_t *buttons;
  lv_obj_t *controls;
  lv_obj_t *title;
  lv_obj_t *card_panel;
  lv_obj_t *body;
  lv_obj_t *mode_row;
  lv_obj_t *mode_button;

  if (g_root != NULL)
    {
      return 0;
    }

#if LV_USE_FREETYPE
  buffer_fonts_init();
#endif
  g_root = lv_obj_create(lv_scr_act());
  if (g_root == NULL)
    {
      return -ENOMEM;
    }

  lv_obj_set_size(g_root, LV_PCT(100), LV_PCT(100));
  lv_obj_set_style_bg_color(g_root, lv_color_hex(BUFFER_BG), 0);
  lv_obj_set_style_bg_opa(g_root, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(g_root, 0, 0);
  lv_obj_set_style_radius(g_root, 0, 0);
  lv_obj_set_style_pad_all(g_root, 12, 0);
  lv_obj_set_style_text_color(g_root, lv_color_hex(BUFFER_ON_SURFACE), 0);
  lv_obj_set_style_text_font(g_root, buffer_font_small(), 0);
  lv_obj_set_flex_flow(g_root, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(g_root, LV_FLEX_ALIGN_START,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_row(g_root, 8, 0);
  lv_obj_clear_flag(g_root, LV_OBJ_FLAG_SCROLLABLE);

  header = lv_obj_create(g_root);
  lv_obj_remove_style_all(header);
  lv_obj_set_size(header, LV_PCT(100), 40);
  lv_obj_set_style_bg_color(header, lv_color_hex(BUFFER_BG), 0);
  lv_obj_set_style_bg_opa(header, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(header, 0, 0);
  lv_obj_set_style_pad_all(header, 0, 0);
  lv_obj_set_flex_flow(header, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(header, LV_FLEX_ALIGN_SPACE_BETWEEN,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_clear_flag(header, LV_OBJ_FLAG_SCROLLABLE);

  title = lv_label_create(header);
  lv_label_set_text(title, "Buffer");
  lv_obj_set_style_text_font(title, buffer_font_large(), 0);
  lv_obj_set_style_text_color(title, lv_color_hex(BUFFER_ACCENT), 0);

  g_connection_label = lv_label_create(header);
  lv_obj_set_style_text_color(g_connection_label, lv_color_hex(BUFFER_ACCENT), 0);

  /* Only this middle pane scrolls. Header and bottom controls occupy their
   * own flex rows, never floating above card text. */
  body = lv_obj_create(g_root);
  lv_obj_remove_style_all(body);
  lv_obj_set_size(body, LV_PCT(100), 0);
  lv_obj_set_flex_grow(body, 1);
  lv_obj_set_flex_flow(body, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(body, 10, 0);
  lv_obj_set_scroll_dir(body, LV_DIR_VER);
  lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_AUTO);
  lv_obj_clear_flag(body, LV_OBJ_FLAG_SCROLL_CHAIN);

  mode_row = lv_obj_create(body);
  lv_obj_remove_style_all(mode_row);
  lv_obj_set_size(mode_row, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(mode_row, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(mode_row, LV_FLEX_ALIGN_SPACE_BETWEEN,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(mode_row, 8, 0);
  lv_obj_clear_flag(mode_row, LV_OBJ_FLAG_SCROLLABLE);
  g_mode_label = lv_label_create(mode_row);
  lv_obj_set_width(g_mode_label, 0);
  lv_obj_set_flex_grow(g_mode_label, 1);
  lv_label_set_long_mode(g_mode_label, LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_font(g_mode_label, buffer_font_small(), 0);
  lv_obj_set_style_text_color(g_mode_label, lv_color_hex(BUFFER_MUTED), 0);

  mode_button = buffer_button(mode_row, "切换模式", buffer_mode_event, NULL);
  lv_obj_set_size(mode_button, 88, 40);

  g_pairing_label = lv_label_create(body);
  lv_obj_set_width(g_pairing_label, LV_PCT(100));
  lv_obj_set_style_text_font(g_pairing_label, buffer_font_large(), 0);
  lv_obj_set_style_text_color(g_pairing_label, lv_color_hex(BUFFER_ACCENT), 0);
  lv_label_set_long_mode(g_pairing_label, LV_LABEL_LONG_DOT);

  g_status_label = lv_label_create(body);
  lv_obj_set_width(g_status_label, LV_PCT(100));
  lv_obj_set_style_text_font(g_status_label, buffer_font_small(), 0);
  lv_obj_set_style_text_color(g_status_label, lv_color_hex(BUFFER_MUTED), 0);
  lv_label_set_long_mode(g_status_label, LV_LABEL_LONG_WRAP);

  g_record_button = buffer_button(g_root, "按住，说点什么", NULL, NULL);
  lv_obj_set_size(g_record_button, LV_PCT(100), 56);
  lv_obj_set_style_radius(g_record_button, 20, 0);
  lv_obj_set_style_bg_color(g_record_button, lv_color_hex(BUFFER_ACCENT), 0);
  lv_obj_set_style_text_color(g_record_button, lv_color_white(), 0);
  lv_obj_remove_flag(g_record_button, LV_OBJ_FLAG_SCROLL_CHAIN);
  g_record_label = lv_obj_get_child(g_record_button, 0);
  lv_obj_t *mic = lv_image_create(g_record_button);
  lv_image_set_src(mic, &buffer_mic_icon);
  lv_obj_set_style_image_recolor(mic, lv_color_white(), 0);
  lv_obj_set_style_image_recolor_opa(mic, LV_OPA_COVER, 0);
  lv_obj_move_to_index(mic, 0);
  lv_obj_set_flex_flow(g_record_button, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(g_record_button, LV_FLEX_ALIGN_CENTER,
                       LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(g_record_button, 8, 0);
  lv_obj_add_event_cb(g_record_button, buffer_record_event, LV_EVENT_ALL, NULL);

  g_queue_label = lv_label_create(body);
  lv_obj_set_width(g_queue_label, LV_PCT(100));
  lv_obj_set_style_text_color(g_queue_label, lv_color_hex(BUFFER_MUTED), 0);

  card_panel = lv_obj_create(body);
  g_card_panel = card_panel;
  lv_obj_remove_style_all(card_panel);
  lv_obj_set_size(card_panel, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_style_bg_color(card_panel, lv_color_hex(BUFFER_PANEL), 0);
  lv_obj_set_style_bg_opa(card_panel, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(card_panel, 0, 0);
  lv_obj_set_style_radius(card_panel, 16, 0);
  lv_obj_set_style_pad_all(card_panel, 16, 0);
  lv_obj_set_style_pad_row(card_panel, 8, 0);
  lv_obj_set_flex_flow(card_panel, LV_FLEX_FLOW_COLUMN);
  lv_obj_clear_flag(card_panel, LV_OBJ_FLAG_SCROLLABLE);
  g_card_title = lv_label_create(card_panel);
  lv_obj_set_width(g_card_title, LV_PCT(100));
  lv_obj_set_style_text_font(g_card_title, buffer_font_large(), 0);
  lv_obj_set_style_text_color(g_card_title, lv_color_hex(BUFFER_ON_SURFACE), 0);
  lv_label_set_long_mode(g_card_title, LV_LABEL_LONG_WRAP);

  g_card_summary = lv_label_create(card_panel);
  lv_obj_set_width(g_card_summary, LV_PCT(100));
  lv_obj_set_style_text_font(g_card_summary, buffer_font_small(), 0);
  lv_obj_set_style_text_color(g_card_summary, lv_color_hex(BUFFER_MUTED), 0);
  lv_label_set_long_mode(g_card_summary, LV_LABEL_LONG_WRAP);

  buttons = lv_obj_create(body);
  g_action_row = buttons;
  lv_obj_remove_style_all(buttons);
  lv_obj_set_size(buttons, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_style_bg_opa(buttons, LV_OPA_TRANSP, 0);
  lv_obj_set_style_border_width(buttons, 0, 0);
  lv_obj_set_style_pad_all(buttons, 0, 0);
  lv_obj_set_style_pad_column(buttons, 5, 0);
  lv_obj_set_flex_flow(buttons, LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_style_pad_row(buttons, 8, 0);
  lv_obj_set_flex_align(buttons, LV_FLEX_ALIGN_CENTER,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_clear_flag(buttons, LV_OBJ_FLAG_SCROLLABLE);

  g_action_buttons[0] = buffer_button(buttons, "稍后", buffer_action_event,
                                      (void *)"later");
  g_action_buttons[1] = buffer_button(buttons, "完成", buffer_action_event,
                                      (void *)"done");
  g_action_buttons[2] = buffer_button(buttons, "归档", buffer_action_event,
                                      (void *)"archive");
  g_action_buttons[3] = buffer_button(buttons, "提醒", buffer_action_event,
                                      (void *)"remind");
  g_action_buttons[4] = buffer_button(buttons, "深挖", buffer_action_event,
                                      (void *)"rabbit_hole");

  g_focus_panel = lv_obj_create(body);
  lv_obj_remove_style_all(g_focus_panel);
  lv_obj_set_size(g_focus_panel, LV_PCT(100), 252);
  lv_obj_clear_flag(g_focus_panel, LV_OBJ_FLAG_SCROLLABLE);
  g_focus_arc = lv_arc_create(g_focus_panel);
  lv_obj_set_size(g_focus_arc, 240, 240);
  lv_obj_center(g_focus_arc);
  lv_arc_set_rotation(g_focus_arc, 270);
  lv_arc_set_bg_angles(g_focus_arc, 0, 360);
  lv_arc_set_range(g_focus_arc, 0, 1000);
  lv_arc_set_value(g_focus_arc, 0);
  lv_obj_remove_style(g_focus_arc, NULL, LV_PART_KNOB);
  lv_obj_clear_flag(g_focus_arc, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(g_focus_arc, 10, LV_PART_MAIN);
  lv_obj_set_style_arc_width(g_focus_arc, 10, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(g_focus_arc, lv_color_hex(BUFFER_PANEL), LV_PART_MAIN);
  lv_obj_set_style_arc_color(g_focus_arc, lv_color_hex(BUFFER_ACCENT), LV_PART_INDICATOR);
  lv_obj_t *focus_caption = lv_label_create(g_focus_panel);
  lv_label_set_text(focus_caption, "已专注");
  lv_obj_set_style_text_color(focus_caption, lv_color_hex(BUFFER_MUTED), 0);
  lv_obj_align(focus_caption, LV_ALIGN_CENTER, 0, -50);
  g_focus_time = lv_label_create(g_focus_panel);
#if LV_USE_FREETYPE
  lv_obj_set_style_text_font(g_focus_time,
      g_font_timer != NULL ? g_font_timer : buffer_font_large(), 0);
#else
  lv_obj_set_style_text_font(g_focus_time, buffer_font_large(), 0);
#endif
  lv_label_set_text(g_focus_time, "00:00");
  lv_obj_align(g_focus_time, LV_ALIGN_CENTER, 0, -6);
  g_focus_next = lv_label_create(g_focus_panel);
  lv_label_set_text(g_focus_next, "正在准备专注计时");
  lv_obj_set_style_text_color(g_focus_next, lv_color_hex(BUFFER_MUTED), 0);
  lv_obj_align(g_focus_next, LV_ALIGN_CENTER, 0, 42);

  controls = lv_obj_create(g_root);
  g_controls = controls;
  lv_obj_remove_style_all(controls);
  lv_obj_set_size(controls, LV_PCT(100), 48);
  lv_obj_set_style_bg_color(controls, lv_color_hex(BUFFER_BG), 0);
  lv_obj_set_style_bg_opa(controls, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(controls, 0, 0);
  lv_obj_set_style_pad_all(controls, 0, 0);
  lv_obj_set_style_pad_column(controls, 5, 0);
  lv_obj_set_flex_flow(controls, LV_FLEX_FLOW_ROW);
  lv_obj_set_style_pad_row(controls, 8, 0);
  lv_obj_set_flex_align(controls, LV_FLEX_ALIGN_CENTER,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_clear_flag(controls, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_flex_grow(buffer_button(controls, "上一张", buffer_previous_card_event, NULL), 1);
  lv_obj_set_flex_grow(buffer_button(controls, "下一张", buffer_next_card_event, NULL), 1);
  lv_obj_set_flex_grow(buffer_button(controls, "同步", buffer_sync_event, NULL), 1);
  /* Recording stays at the bottom in every mode. */
  lv_obj_move_to_index(g_record_button, -1);

  g_refresh_timer = lv_timer_create(buffer_ui_refresh, 500, NULL);
  buffer_ui_refresh(g_refresh_timer);
  return 0;
}

static void buffer_ui_init_async(void *arg)
{
  (void)arg;
  g_init_pending = false;
  (void)buffer_ui_build();
}

int buffer_ui_init(bool defer_to_lvgl_thread)
{
  if (g_root != NULL || g_init_pending)
    {
      return 0;
    }

  if (defer_to_lvgl_thread)
    {
      g_init_pending = true;
      if (lv_async_call(buffer_ui_init_async, NULL) != LV_RESULT_OK)
        {
          g_init_pending = false;
          return -ENOMEM;
        }

      return 0;
    }

  return buffer_ui_build();
}

void buffer_ui_shutdown(void)
{
  if (g_touch_recording) buffer_touch_record_stop();
  g_touch_recording = false;
  g_paired = false;
  g_record_button = NULL;
  g_record_label = NULL;
  if (g_refresh_timer != NULL)
    {
      lv_timer_delete(g_refresh_timer);
      g_refresh_timer = NULL;
    }
  if (g_root != NULL)
    {
      lv_obj_del(g_root);
      g_root = NULL;
    }
  g_status_label = NULL;
  g_mode_label = NULL;
  g_pairing_label = NULL;
  g_connection_label = NULL;
  g_card_title = NULL;
  g_card_summary = NULL;
  g_queue_label = NULL;
  g_action_row = NULL;
  g_card_panel = NULL;
  g_controls = NULL;
  g_focus_panel = NULL;
  g_focus_arc = NULL;
  g_focus_time = NULL;
  g_focus_next = NULL;
  memset(g_action_buttons, 0, sizeof(g_action_buttons));
#if LV_USE_FREETYPE
  if (g_font_timer != NULL)
    {
      lv_freetype_font_delete(g_font_timer);
      g_font_timer = NULL;
    }
  if (g_font_large != NULL)
    {
      lv_freetype_font_delete(g_font_large);
      g_font_large = NULL;
    }
  if (g_font_small != NULL)
    {
      lv_freetype_font_delete(g_font_small);
      g_font_small = NULL;
    }
#endif
}
