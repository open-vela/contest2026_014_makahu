/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela card shelf UI
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_live_asr.h"
#include "buffer_ble_service.h"
#include "buffer_oobe.h"
#include "buffer_oobe_ui.h"
#include "buffer_agent.h"
#include "buffer_a2ui.h"
#include "buffer_todo.h"
#include "buffer_history.h"
#include "buffer_fabric.h"
#include "buffer_replica.h"
#include "buffer_followup.h"
#include "buffer_focus.h"
#include "buffer_calendar.h"
#include "buffer_backlight.h"

#include <errno.h>
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <syslog.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_motion.h"
#include "buffer_ui_icons.h"
#include "buffer_ui_artwork.h"
#include "buffer_ui_shell.h"
#include "buffer_ui_preferences.h"
#include "buffer_ui_controls.h"
#include "buffer_ui_notifications.h"
#include "buffer_ui_desktop.h"

/* Paint before opening private stores/replaying source history. The brand
 * uses the built-in font, so first paint does not depend on NAND font loading. */
static lv_obj_t *g_startup_surface;
void buffer_ui_startup_show(void)
{
  if(g_startup_surface)return;
  g_startup_surface=lv_obj_create(lv_scr_act());
  lv_obj_set_size(g_startup_surface,LV_PCT(100),LV_PCT(100));
  lv_obj_set_style_bg_color(g_startup_surface,lv_color_hex(BUFFER_BG),0);
  lv_obj_set_style_bg_opa(g_startup_surface,LV_OPA_COVER,0);
  lv_obj_set_style_border_width(g_startup_surface,0,0);
  lv_obj_set_style_radius(g_startup_surface,0,0);
  lv_obj_remove_flag(g_startup_surface,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_t *brand=lv_label_create(g_startup_surface);
  lv_label_set_text(brand,"Buffer");
  lv_obj_set_style_text_color(brand,lv_color_hex(BUFFER_ON_SURFACE),0);
  lv_obj_center(brand);
  lv_refr_now(NULL);
  syslog(LOG_INFO,"Buffer startup: first frame displayed\n");
}
static void buffer_ui_startup_hide(void)
{
  if(g_startup_surface){lv_obj_delete(g_startup_surface);g_startup_surface=NULL;}
}

static struct buffer_ui_runtime_s g_ui_state;
static unsigned g_ui_heartbeat;
static unsigned g_ui_progress_sequence;
static const char *g_ui_progress_stage="startup";
void buffer_ui_progress(const char *stage)
{
 __atomic_store_n(&g_ui_progress_stage,stage,__ATOMIC_RELEASE);
 __atomic_add_fetch(&g_ui_progress_sequence,1,__ATOMIC_RELEASE);
}
unsigned buffer_ui_progress_sequence(void){return __atomic_load_n(&g_ui_progress_sequence,__ATOMIC_ACQUIRE);}
const char *buffer_ui_progress_stage(void){return __atomic_load_n(&g_ui_progress_stage,__ATOMIC_ACQUIRE);}
static int g_home_command;
void buffer_ui_home_command(int command){if(command>=1&&command<=3)__atomic_store_n(&g_home_command,command,__ATOMIC_RELEASE);}
static int g_calendar_command;
static int g_menu_command;
static unsigned g_menu_open;
void buffer_ui_menu_command(int command){if(command==1||command==2)__atomic_store_n(&g_menu_command,command,__ATOMIC_RELEASE);}
unsigned buffer_ui_menu_state(void){return __atomic_load_n(&g_menu_open,__ATOMIC_ACQUIRE);}
unsigned buffer_ui_heartbeat(void){return __atomic_load_n(&g_ui_heartbeat,__ATOMIC_ACQUIRE);}
void buffer_ui_calendar_command(int command){if(command>=1&&command<=4)__atomic_store_n(&g_calendar_command,command,__ATOMIC_RELEASE);}
static lv_obj_t *g_root;
static lv_obj_t *g_header;
static pthread_mutex_t g_wifi_poll_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_wifi_poll_wake = PTHREAD_COND_INITIALIZER;
static pthread_t g_wifi_poll_thread;
static bool g_wifi_poll_started, g_wifi_poll_stop, g_wifi_poll_ready;
#define BUFFER_UI_WORK_LIMIT 8
struct buffer_ui_work_s { void (*run)(void *); void *context; };
static struct buffer_ui_work_s g_ui_work[BUFFER_UI_WORK_LIMIT];
static unsigned g_ui_work_head, g_ui_work_count;

int buffer_ui_work_submit(void (*run)(void *), void *context)
{
  if (!run || !context) return -EINVAL;
  pthread_mutex_lock(&g_wifi_poll_lock);
  int ret = !g_wifi_poll_started || g_wifi_poll_stop ? -ESHUTDOWN :
            g_ui_work_count == BUFFER_UI_WORK_LIMIT ? -EAGAIN : 0;
  if (!ret)
    {
      unsigned tail = (g_ui_work_head + g_ui_work_count) % BUFFER_UI_WORK_LIMIT;
      g_ui_work[tail] = (struct buffer_ui_work_s){run, context};
      g_ui_work_count++;
      pthread_cond_signal(&g_wifi_poll_wake);
    }
  pthread_mutex_unlock(&g_wifi_poll_lock);
  return ret;
}

static void buffer_ui_work_run(void)
{
  pthread_mutex_lock(&g_wifi_poll_lock);
  if (!g_ui_work_count)
    { pthread_mutex_unlock(&g_wifi_poll_lock); return; }
  struct buffer_ui_work_s work = g_ui_work[g_ui_work_head];
  g_ui_work_head = (g_ui_work_head + 1) % BUFFER_UI_WORK_LIMIT;
  g_ui_work_count--;
  pthread_mutex_unlock(&g_wifi_poll_lock);
  work.run(work.context);
  free(work.context);
}
static char g_detail_job_id[BUFFER_ID_SIZE];
static char g_detail_fallback[BUFFER_TEXT_SIZE*2+8];
static uint64_t g_detail_job_revision, g_detail_job_conversation, g_detail_job_epoch;
static bool g_detail_requested, g_detail_working, g_detail_ready;
static char *g_detail_result;

static void buffer_ui_detail_load(void)
{
  char id[BUFFER_ID_SIZE], fallback[sizeof(g_detail_fallback)];
  pthread_mutex_lock(&g_wifi_poll_lock);
  if (!g_detail_requested)
    { pthread_mutex_unlock(&g_wifi_poll_lock); return; }
  uint64_t epoch = g_detail_job_epoch;
  snprintf(id, sizeof(id), "%s", g_detail_job_id);
  snprintf(fallback, sizeof(fallback), "%s", g_detail_fallback);
  g_detail_requested = false;
  g_detail_working = true;
  pthread_mutex_unlock(&g_wifi_poll_lock);
  char *full = buffer_replica_detail(id);
  char *history = buffer_followup_history(id);
  const char *text = full ? full : fallback;
  size_t length = strlen(text) + (history ? strlen(history) : 0) + 3;
  char *result = malloc(length);
  if (result) snprintf(result, length, "%s%s%s", text,
                       history && history[0] ? "\n\n" : "", history ? history : "");
  free(full);
  free(history);
  pthread_mutex_lock(&g_wifi_poll_lock);
  g_detail_working = false;
  if (epoch == g_detail_job_epoch && !g_wifi_poll_stop)
    {
      free(g_detail_result);
      g_detail_result = result;
      g_detail_ready = true;
    }
  else free(result);
  pthread_mutex_unlock(&g_wifi_poll_lock);
}

static void *buffer_ui_wifi_poll(void *unused)
{
  (void)unused;
  (void)buffer_assistant_init();
  pthread_mutex_lock(&g_wifi_poll_lock);
  while (!g_wifi_poll_stop || g_ui_work_count)
    {
      bool stopping = g_wifi_poll_stop;
      pthread_mutex_unlock(&g_wifi_poll_lock);
      buffer_ui_work_run();
      if (!stopping) buffer_ui_detail_load();
      /* Wireless ioctls can wait for the radio. Never run them in LVGL. */
      bool ready = !stopping && buffer_wifi_is_ready();
      struct timespec until;
      clock_gettime(CLOCK_REALTIME, &until);
      until.tv_sec += 2;
      pthread_mutex_lock(&g_wifi_poll_lock);
      g_wifi_poll_ready = ready;
      while (!g_wifi_poll_stop && !g_detail_requested && !g_ui_work_count)
        if (pthread_cond_timedwait(&g_wifi_poll_wake, &g_wifi_poll_lock,
                                  &until) == ETIMEDOUT) break;
    }
  pthread_mutex_unlock(&g_wifi_poll_lock);
  return NULL;
}

static bool buffer_ui_wifi_ready(void)
{
  pthread_mutex_lock(&g_wifi_poll_lock);
  bool ready = g_wifi_poll_ready;
  pthread_mutex_unlock(&g_wifi_poll_lock);
  return ready;
}
static lv_obj_t *g_body;
static lv_obj_t *g_home_anchor,*g_home_rail,*g_home_capture;
static struct buffer_ui_desktop *g_desktop;
static lv_obj_t *g_shortcuts[4];
static lv_obj_t *g_pages, *g_buffer_page, *g_assistant_page, *g_page_switch;
static bool g_initial_page_pending = true;
static lv_obj_t *g_assistant_list, *g_assistant_empty;
#define BUFFER_ASSISTANT_HOME_CARDS (BUFFER_ASSISTANT_MAX_TIMERS + BUFFER_ASSISTANT_MAX_ALARMS + BUFFER_ASSISTANT_SURFACE_CARDS)
struct assistant_home_row_s
{
  lv_obj_t *root, *kind, *title, *summary;
  char id[BUFFER_ID_SIZE];
  unsigned type;
};
static struct buffer_notification_view g_activity_views[BUFFER_NOTIFICATION_LIMIT];
static lv_obj_t *g_activity_images[BUFFER_NOTIFICATION_LIMIT];
static lv_obj_t *g_activity_image_frames[BUFFER_NOTIFICATION_LIMIT];
static unsigned g_timer_command_pending;
static struct buffer_notification_view g_notification_views[BUFFER_NOTIFICATION_LIMIT];
static lv_obj_t *g_notification_list,*g_notification_empty;
static bool g_notification_private_previews;
static lv_obj_t *g_preference_status,*g_preference_retry,*g_private_option,*g_motion_option;
static uint64_t g_preference_applied;
static int g_preference_font_error;
static lv_obj_t *g_home_tools, *g_activity_slot, *g_card_kind;
static lv_point_t g_record_origin;
static char g_detail_id[BUFFER_ID_SIZE];
static uint64_t g_detail_revision, g_detail_conversation_revision;
static bool g_detail_loaded;
static lv_obj_t *g_live_panel, *g_live_label;
static lv_obj_t *g_record_button;
static lv_obj_t *g_record_label;
static lv_obj_t *g_mic;
static lv_obj_t *g_clock_label;
static lv_obj_t *g_detail_title, *g_detail_summary, *g_detail_reader, *g_detail_notice, *g_detail_state;
static lv_obj_t *g_launchers, *g_clock_tile, *g_desktop_time, *g_desktop_date;
static struct assistant_home_row_s g_deck_tiles[BUFFER_CARD_LIMIT];
static lv_obj_t *g_page_label;
static struct buffer_assistant_item_s
  g_notice_timers[BUFFER_ASSISTANT_MAX_TIMERS];
static struct buffer_assistant_item_s
  g_notice_alarms[BUFFER_ASSISTANT_MAX_ALARMS];
static uint32_t g_record_tick;
static bool g_record_timer_started;
static bool g_touch_recording;
static bool g_paired;
static lv_obj_t *g_status_label;
static lv_obj_t *g_backlight_label;
static lv_obj_t *g_mode_label;
static lv_obj_t *g_pairing_label;
static lv_obj_t *g_connection_label;
static lv_obj_t *g_connection_detail;
static struct buffer_oobe_ui_s *g_oobe_ui;
#ifdef CONFIG_BUFFER_VELA_FABRIC
static struct buffer_fabric_status_s g_oobe_fabric_status;
static lv_obj_t *g_members_panel,*g_members_state,*g_members_prompt,*g_members_accept,*g_members_reject;
static lv_obj_t *g_members_body,*g_members_feedback,*g_members_add,*g_members_stop,*g_members_back;
static lv_obj_t *g_member_remove_panel,*g_member_remove_copy,*g_member_remove_commit;
static char g_member_remove_pending[65],g_member_removing[65],g_member_announced[65];
static uint64_t g_member_revision,g_member_pressed_revision,g_member_remove_revision,g_member_remove_started;
static unsigned g_member_announced_generation;static int g_member_announced_code;static bool g_member_announcement_valid;
static lv_obj_t *g_member_labels[BUFFER_FABRIC_MAX_MEMBERS],*g_member_remove[BUFFER_FABRIC_MAX_MEMBERS];
static char g_member_ids[BUFFER_FABRIC_MAX_MEMBERS][65],g_member_pressed[65],g_member_pending[65];
static int g_member_code=-1;
static char g_member_confirm_pressed[65];
static int g_member_confirm_code;
static unsigned g_member_generation,g_member_confirm_generation;
#endif
static lv_obj_t *g_mode_menu;
static lv_obj_t *g_mode_options[4],*g_font_slider,*g_font_value,*g_font_preview,*g_brightness_slider,*g_brightness_value;
static const unsigned g_font_sizes[]={100,125,150,200};
static lv_obj_t *g_status_wifi,*g_status_alert;
static lv_obj_t *g_menu_columns,*g_menu_left,*g_menu_right,*g_menu_quick,*g_menu_modes,*g_menu_actions,*g_menu_sync,*g_menu_devices;
static lv_obj_t *g_previous_button, *g_next_button;
static int g_card_direction;
static char g_presented_card[BUFFER_ID_SIZE];
static char g_presented_mode[BUFFER_MODE_SIZE];
static lv_obj_t *g_card_title;
static lv_obj_t *g_card_summary;
static lv_obj_t *g_queue_label;
static lv_obj_t *g_card_more_panel;
static lv_obj_t *g_card_panel;
static lv_obj_t *g_controls;
static lv_obj_t *g_focus_panel;
static lv_obj_t *g_focus_arc;
static lv_obj_t *g_focus_time;
static lv_obj_t *g_focus_caption;
static lv_obj_t *g_focus_next;
static lv_obj_t *g_research_panel;
static lv_obj_t *g_research_label;
static lv_obj_t *g_action_buttons[BUFFER_CARD_ACTION_BUTTONS];
static const char *g_action_names[BUFFER_CARD_ACTION_BUTTONS] = {
  "later", "done", "archive", "remind", "rabbit_hole"
};
static lv_timer_t *g_refresh_timer;
static bool g_init_pending;
static bool g_pairing_state_known;
static bool g_wifi_ready;
static pthread_mutex_t g_oobe_retry_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_oobe_retry_running;
static bool g_oobe_retry_complete;
static bool g_oobe_retry_attempted;
static int g_oobe_retry_result;
static pthread_mutex_t g_oobe_finish_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_oobe_finish_running;
static bool g_oobe_finish_complete;
static int g_oobe_finish_result;
static bool g_oobe_finish_attempted;
static bool g_focus_mode;

static void buffer_visible(lv_obj_t *obj, bool visible);
static void buffer_ui_refresh(lv_timer_t *timer);
static void buffer_pages_show(unsigned page);
static void buffer_child_page_layout(void);
static void buffer_assistant_home_refresh(void);
static void buffer_desktop_refresh(void);
static void buffer_desktop_layout(void);
static uint64_t buffer_ui_now_ms(void);
static void buffer_header_layout(void);
static void buffer_desktop_content_layout(void *context);
static void buffer_ui_layout(lv_event_t *event);
static void buffer_menu_layout(void);

static const lv_font_t *buffer_font_small(void)
{ return buffer_ui_font(BUFFER_TEXT_LABEL_LARGE); }
static const lv_font_t *buffer_font_oobe_hero(void)
{ return buffer_ui_font(BUFFER_TEXT_HEADLINE_LARGE); }

static void buffer_record_cancel_gesture(void)
{
  int ret = buffer_touch_record_cancel();
  if (g_live_panel) buffer_visible(g_live_panel, false);
  g_touch_recording = false;
  buffer_ui_shell_capture_expanded(false);
  buffer_ui_add_flag(g_record_label, LV_OBJ_FLAG_HIDDEN);
  buffer_ui_style_bg_opa(g_record_button, LV_OPA_TRANSP, 0);
  buffer_ui_style_image_recolor(g_mic, lv_color_hex(BUFFER_ACCENT), 0);
  buffer_set_status(ret == 0 ? "已取消录音" : "录音已在保存中");
  /* This physical press is consumed: returning to the button must never
   * start another capture, and releasing must never submit this one. */
  lv_indev_t *indev = lv_indev_active();
  if (indev) lv_indev_wait_release(indev);
}

static void buffer_record_event(lv_event_t *event)
{
  lv_event_code_t code = lv_event_get_code(event);
  if (code == LV_EVENT_PRESSED && !g_touch_recording)
    {
      bool in_detail = g_card_more_panel && !lv_obj_has_flag(g_card_more_panel, LV_OBJ_FLAG_HIDDEN);
      if (buffer_touch_record_start_for_card(in_detail ? g_detail_id : NULL) == 0)
        {
          g_touch_recording = true;
          buffer_ui_shell_capture_expanded(true);
          g_record_tick = lv_tick_get();
          g_record_timer_started = false;
          lv_indev_t *indev = lv_indev_active();
          if (indev) lv_indev_get_point(indev, &g_record_origin);
          buffer_ui_style_bg_color(g_record_button,lv_color_hex(BUFFER_ACCENT),0);
          buffer_ui_style_bg_color(g_record_button,lv_color_hex(BUFFER_PRIMARY_PRESSED),LV_STATE_PRESSED);
          buffer_ui_remove_flag(g_record_label, LV_OBJ_FLAG_HIDDEN);
          buffer_ui_style_bg_opa(g_record_button, LV_OPA_COVER, 0);
          buffer_ui_style_text_color(g_record_label, lv_color_hex(BUFFER_ON_PRIMARY), 0);
          buffer_ui_style_image_recolor(g_mic, lv_color_hex(BUFFER_ON_PRIMARY), 0);
          buffer_ui_label_set_text(g_record_label, "准备录音…");
        }
    }
  else if (code == LV_EVENT_PRESSING && g_touch_recording)
    {
      lv_indev_t *indev = lv_indev_active();
      if (indev && lv_indev_get_type(indev) == LV_INDEV_TYPE_POINTER)
        {
          lv_point_t point;
          lv_indev_get_point(indev, &point);
          int dx = point.x - g_record_origin.x;
          int dy = point.y - g_record_origin.y;
          /* The recorder spans the screen: leaving its bounds is not a
           * usable horizontal gesture. Track actual travel instead. */
          if (dx >= buffer_ui_dp(48) || dx <= -buffer_ui_dp(48) || dy <= -buffer_ui_dp(40) || dy >= buffer_ui_dp(48))
            buffer_record_cancel_gesture();
        }
    }
  else if (code == LV_EVENT_PRESS_LOST && g_touch_recording)
    buffer_record_cancel_gesture();
  else if (code == LV_EVENT_RELEASED && g_touch_recording)
    {
      static char transcript[1025];char capture[BUFFER_ID_SIZE]="";buffer_live_asr_snapshot(transcript,sizeof(transcript));
      if(!pthread_mutex_trylock(&g_buffer.lock)){snprintf(capture,sizeof(capture),"%s",g_buffer.record_id);pthread_mutex_unlock(&g_buffer.lock);}
      buffer_touch_record_stop();
      g_touch_recording = false;
      buffer_ui_shell_capture_expanded(false);
      buffer_ui_add_flag(g_record_label, LV_OBJ_FLAG_HIDDEN);
      buffer_a2ui_ui_capture_prompt(transcript,capture);
    }
}

static void buffer_card_more_event(lv_event_t *event)
{
  (void)event;
  if(buffer_ui_shell_is_open("record-detail")){buffer_ui_shell_hide("record-detail");return;}
  int selected=g_ui_state.card_index;
  if(selected<0||selected>=g_ui_state.card_count)selected=0;
  if(g_ui_state.card_count)(void)buffer_ui_shell_open("record-detail",g_ui_state.cards[selected].card_id);
}
static bool buffer_detail_action_visible(const struct buffer_card_s *card,
                                         const char *action)
{
  if (!card || !buffer_card_supports_action(card, action)) return false;
  if (!strcmp(action, "archive")) return true;
  if (!strcmp(action, "done"))
    return !strcmp(card->kind, "task_card") && strcmp(card->state, "done") &&
           strcmp(card->state, "archived");
  if (!strcmp(action, "rabbit_hole")) return !strcmp(card->kind, "question_card") &&
      strcmp(card->state, "researched");
  return false;
}

enum buffer_ui_command_e { UI_COMMAND_ACTION, UI_COMMAND_MODE, UI_COMMAND_SYNC, UI_COMMAND_STEP };
struct buffer_ui_command_s
{
  enum buffer_ui_command_e kind;
  char id[BUFFER_ID_SIZE], value[BUFFER_ACTION_SIZE];
  int step;
};
static unsigned g_ui_command_pending;
static unsigned g_mode_generation;
static int g_mode_pending,g_mode_failed,g_mode_result;
static void buffer_sync_perform(void);
static void buffer_ui_command_run(void *context)
{
  struct buffer_ui_command_s *command = context;
  switch (command->kind)
    {
      case UI_COMMAND_ACTION: buffer_request_card_action(command->id, command->value); break;
      case UI_COMMAND_MODE:
        buffer_focus_request_mode(command->value);
        pthread_mutex_lock(&g_buffer.lock);
        bool matched=!strcmp(g_buffer.mode,command->value);
        pthread_mutex_unlock(&g_buffer.lock);
        if((unsigned)command->step==__atomic_load_n(&g_mode_generation,__ATOMIC_ACQUIRE))
          __atomic_store_n(&g_mode_result,matched?1:-1,__ATOMIC_RELEASE);
        break;
      case UI_COMMAND_SYNC: buffer_sync_perform(); break;
      case UI_COMMAND_STEP: buffer_request_card_step(command->step); break;
    }
  __atomic_sub_fetch(&g_ui_command_pending, 1, __ATOMIC_RELAXED);
}
static bool buffer_ui_command_submit(enum buffer_ui_command_e kind,
                                     const char *id, const char *value, int step)
{
  struct buffer_ui_command_s *command = calloc(1, sizeof(*command));
  if (!command) return false;
  command->kind = kind;
  command->step = step;
  snprintf(command->id, sizeof(command->id), "%s", id ? id : "");
  snprintf(command->value, sizeof(command->value), "%s", value ? value : "");
  __atomic_add_fetch(&g_ui_command_pending, 1, __ATOMIC_RELAXED);
  if (buffer_ui_work_submit(buffer_ui_command_run, command) == 0) return true;
  __atomic_sub_fetch(&g_ui_command_pending, 1, __ATOMIC_RELAXED);
  free(command);
  buffer_ui_label_set_text(g_status_label, "操作繁忙，请稍后重试");
  return false;
}

static void buffer_action_event(lv_event_t *event)
{
  const char *action = lv_event_get_user_data(event);
  if (!buffer_ui_command_submit(UI_COMMAND_ACTION, g_detail_id, action, 0)) return;
  buffer_ui_label_set_text(g_detail_state,"操作已提交");
}

static void buffer_sync_perform(void)
{
#ifdef CONFIG_BUFFER_VELA_FABRIC
  int ret = buffer_fabric_request_sync();
  if (ret == 0)
    buffer_set_status("正在同步卡片");
  else if (ret == -ENOTCONN)
    buffer_set_status("手机未连接，请连接后重试");
  else if (ret == -ENOENT)
    buffer_set_status("请先绑定手机再同步");
  else
    buffer_set_status("同步请求失败，请重试");
#else
  buffer_request_sync();
  buffer_set_status("已请求同步");
#endif
}

static void buffer_sync_event(lv_event_t *event)
{
  (void)event;
  (void)buffer_ui_command_submit(UI_COMMAND_SYNC, NULL, NULL, 0);
}

static void buffer_mode_close(void)
{
  buffer_ui_shell_hide("control-center");
}
static void buffer_mode_select(lv_event_t *event)
{
  const char *mode = lv_event_get_user_data(event);
  if(g_mode_pending)return;
  static const char *const modes[]={"normal","focus","bedtime","rabbit_hole"};
  for(unsigned i=0;i<4;i++)if(!strcmp(mode,modes[i]))g_mode_pending=(int)i+1;
  g_mode_failed=0;
  unsigned generation=__atomic_add_fetch(&g_mode_generation,1,__ATOMIC_ACQ_REL);
  if(!buffer_ui_command_submit(UI_COMMAND_MODE,NULL,mode,(int)generation)){
    g_mode_failed=g_mode_pending;g_mode_pending=0;return;
  }
  for(unsigned i=0;i<4;i++)lv_obj_add_state(g_mode_options[i],LV_STATE_DISABLED);
  if(g_mode_pending)buffer_ui_label_set_text(lv_obj_get_child(g_mode_options[g_mode_pending-1],0),"切换中");
}
static void buffer_motion_preference(bool checked,void *context)
{ (void)context;buffer_ui_motion_policy(checked,false);(void)buffer_ui_preferences_set(BUFFER_UI_PREF_REDUCED,checked); }
static void buffer_motion_tile(lv_event_t *event)
{
 lv_obj_t *tile=lv_event_get_current_target(event);if(lv_obj_has_state(tile,LV_STATE_DISABLED))return;
 bool checked=!buffer_ui_motion_reduced();buffer_motion_preference(checked,NULL);
 if(checked)lv_obj_add_state(tile,LV_STATE_CHECKED);else lv_obj_remove_state(tile,LV_STATE_CHECKED);
}
static void buffer_private_preference(bool checked,void *context)
{(void)context;g_notification_private_previews=checked;(void)buffer_ui_preferences_set(BUFFER_UI_PREF_PRIVATE,checked);}
static void buffer_font_slider_sync(void)
{
 unsigned index=0;for(unsigned i=0;i<4;i++)if(g_font_sizes[i]==buffer_ui_font_scale())index=i;
 lv_slider_set_value(g_font_slider,index,LV_ANIM_OFF);
 char caption[32];snprintf(caption,sizeof(caption),"文字大小 · %u%%",g_font_sizes[index]);buffer_ui_label_set_text(g_font_value,caption);
}
static void buffer_font_preference(lv_event_t *event)
{
 unsigned index=(unsigned)lv_slider_get_value(g_font_slider);if(index>=4)return;
 char caption[32];snprintf(caption,sizeof(caption),"文字大小 · %u%%",g_font_sizes[index]);buffer_ui_label_set_text(g_font_value,caption);
 /* Preview only the sample. Changing global fonts during a drag moves the
  * target under the pointer and can repeatedly load font files. */
 lv_obj_set_style_transform_scale_x(g_font_preview,256*g_font_sizes[index]/buffer_ui_font_scale(),0);
 lv_obj_set_style_transform_scale_y(g_font_preview,256*g_font_sizes[index]/buffer_ui_font_scale(),0);
 if(lv_event_get_code(event)!=LV_EVENT_RELEASED&&lv_event_get_code(event)!=LV_EVENT_KEY)return;
 if(buffer_ui_typography_scale(g_font_sizes[index])){
   buffer_ui_label_set_text(g_status_label,"字体调整失败，已保留原字号");buffer_font_slider_sync();
 }else (void)buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,g_font_sizes[index]);
 lv_obj_set_style_transform_scale_x(g_font_preview,256,0);lv_obj_set_style_transform_scale_y(g_font_preview,256,0);
 buffer_ui_layout(NULL);
}
static void buffer_brightness_change(lv_event_t *event)
{
 int value=lv_slider_get_value(g_brightness_slider);char caption[40];snprintf(caption,sizeof(caption),"屏幕亮度 · %d%%",value);buffer_ui_label_set_text(g_brightness_value,caption);
 if(lv_event_get_code(event)==LV_EVENT_RELEASED||lv_event_get_code(event)==LV_EVENT_KEY){
  if(buffer_backlight_request(value))buffer_ui_label_set_text(g_brightness_value,"亮度调整暂不可用");
 }
}
static void buffer_preferences_retry(lv_event_t *event)
{(void)event;g_preference_applied=0;g_preference_font_error=0;buffer_ui_preferences_retry();}
static void buffer_preferences_refresh(void)
{
 if(!g_preference_status)return;
 struct buffer_ui_preferences preferences;if(buffer_ui_preferences_snapshot(&preferences))return;
 if(preferences.loaded&&preferences.revision!=g_preference_applied){
  bool input_busy=g_touch_recording;
  for(lv_indev_t *i=lv_indev_get_next(NULL);i;i=lv_indev_get_next(i))if(lv_indev_get_state(i)==LV_INDEV_STATE_PRESSED)input_busy=true;
  if(!input_busy){
   g_preference_font_error=0;
   if(buffer_ui_font_scale()!=preferences.font_scale){g_preference_font_error=buffer_ui_typography_scale(preferences.font_scale);if(!g_preference_font_error)buffer_ui_layout(NULL);}
   g_notification_private_previews=preferences.private_previews;buffer_ui_switch_set(g_private_option,preferences.private_previews);
   buffer_ui_motion_policy(preferences.reduced_motion,false);
   if(preferences.reduced_motion)lv_obj_add_state(g_motion_option,LV_STATE_CHECKED);else lv_obj_remove_state(g_motion_option,LV_STATE_CHECKED);
   buffer_font_slider_sync();
   g_preference_applied=preferences.revision;
  }
 }
 const char *message=!preferences.loaded?"正在恢复设置…":g_preference_font_error?"文字设置暂无法加载，已保留当前大小。":preferences.error?"设置仅本次生效，暂未保存，可重试。":preferences.pending||preferences.saving?"正在保存设置…":"";
 buffer_ui_label_set_text(g_preference_status,message);buffer_visible(g_preference_status,*message!=0);
 buffer_visible(g_preference_retry,preferences.error||g_preference_font_error);
}
static void buffer_mode_event(lv_event_t *event)
{
  (void)event;
  if (!buffer_ui_shell_is_open("control-center"))
    (void)buffer_ui_shell_open("control-center",NULL);
  else buffer_mode_close();
}

static void buffer_previous_card_event(lv_event_t *event)
{
  (void)event;
  g_card_direction = -1;
  (void)buffer_ui_command_submit(UI_COMMAND_STEP, NULL, NULL, -1);
}

static void buffer_next_card_event(lv_event_t *event)
{
  (void)event;
  g_card_direction = 1;
  (void)buffer_ui_command_submit(UI_COMMAND_STEP, NULL, NULL, 1);
}

static lv_obj_t *buffer_button(lv_obj_t *parent, const char *text,
                               lv_event_cb_t callback, void *user_data)
{
  lv_obj_t *button = lv_button_create(parent);
  lv_obj_t *label = lv_label_create(button);
  lv_obj_set_width(label,LV_PCT(100));
  lv_label_set_long_mode(label,LV_LABEL_LONG_DOT);
  lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);

  lv_obj_set_size(button, buffer_ui_dp(64), buffer_ui_dp(48));
  buffer_ui_button_style(button, false);
  /* LVGL's default horizontal padding left only a few pixels for short
   * Chinese labels. Fix the content width and constrain labels to one line. */
  lv_obj_set_style_pad_hor(button, buffer_ui_dp(8), 0);
  lv_obj_set_style_pad_ver(button, 0, 0);
  buffer_ui_button_label(label);
  lv_obj_set_height(label, LV_SIZE_CONTENT);
  buffer_ui_label_set_text(label, text);
  lv_obj_center(label);
  if (callback != NULL)
    lv_obj_add_event_cb(button, callback, LV_EVENT_CLICKED, user_data);
  return button;
}

static lv_obj_t *buffer_card_panel_create(lv_obj_t *parent)
{
  lv_obj_t *panel = lv_obj_create(parent);
  lv_obj_remove_style_all(panel);
  lv_obj_set_size(panel, LV_PCT(100), LV_SIZE_CONTENT);
  buffer_ui_style_bg_color(panel, lv_color_hex(BUFFER_PANEL), 0);
  buffer_ui_style_bg_opa(panel, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(panel, 0, 0);
  lv_obj_set_style_radius(panel, BUFFER_CARD_RADIUS, 0);
  lv_obj_set_style_pad_all(panel, BUFFER_CARD_PADDING, 0);
  lv_obj_set_style_pad_row(panel, BUFFER_SECTION_GAP, 0);
  lv_obj_set_flex_flow(panel, LV_FLEX_FLOW_COLUMN);
  buffer_ui_remove_flag(panel, LV_OBJ_FLAG_SCROLLABLE);
  return panel;
}

#ifdef CONFIG_BUFFER_VELA_FABRIC
static void buffer_members_notice(const char *text)
{buffer_ui_label_set_text(g_members_feedback,text);buffer_visible(g_members_feedback,*text!=0);}
static bool buffer_members_back(void *unused)
{
 (void)unused;if(!g_member_remove_pending[0])return false;
 lv_obj_t *target=g_members_back;
 for(unsigned i=0;i<BUFFER_FABRIC_MAX_MEMBERS;i++)if(!strcmp(g_member_remove_pending,g_member_ids[i])){target=g_member_remove[i];break;}
 g_member_remove_pending[0]=0;buffer_ui_add_flag(g_member_remove_panel,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(g_members_body,LV_OBJ_FLAG_HIDDEN);
 buffer_ui_shell_local_end("members",target);return true;
}
static void buffer_members_closed(void *unused)
{(void)buffer_members_back(unused);}
static void buffer_member_remove_confirm(lv_event_t *event)
{
 if(!g_member_remove_pending[0])return;
 bool confirm=(intptr_t)lv_event_get_user_data(event)!=0;
 if(confirm){
  struct buffer_fabric_status_s latest;buffer_fabric_snapshot(&latest);
  if(latest.member_revision!=g_member_remove_revision){
   buffer_ui_label_set_text(g_member_remove_copy,"设备列表已变化，请返回后重新选择。");lv_obj_add_state(g_member_remove_commit,LV_STATE_DISABLED);return;
  }
  int result=buffer_fabric_remove_member(g_member_remove_pending);
  if(!result){snprintf(g_member_removing,sizeof(g_member_removing),"%s",g_member_remove_pending);g_member_remove_started=buffer_ui_now_ms();}
  buffer_members_notice(result?"移除请求未提交，请重试。":"正在提交移除请求，等待设备确认。");
 }
 (void)buffer_members_back(NULL);
}
static void buffer_members_event(lv_event_t *event)
{
  intptr_t action=(intptr_t)lv_event_get_user_data(event);
  if ((action==3 || action==4) && lv_event_get_code(event)==LV_EVENT_PRESSED) {
    memcpy(g_member_confirm_pressed,g_member_pending,65);g_member_confirm_code=g_member_code;g_member_confirm_generation=g_member_generation;return;
  }
  if (action==1) {
    int result=buffer_fabric_add_member();
    buffer_members_notice(result== -ENOSPC?"协作设备已满，请先移除旧设备":result?"暂时无法添加，请重试":"请在手机或平板中搜索本设备");
  } else if (action==2){buffer_fabric_cancel_add_member();buffer_members_notice("已请求停止添加，等待设备确认。");}
  else if (action==3 || action==4) {
    if (strcmp(g_member_confirm_pressed,g_member_pending) || g_member_confirm_code!=g_member_code || g_member_confirm_generation!=g_member_generation) {
      buffer_members_notice("连接已变化，请重新核对");return;
    }
    buffer_fabric_confirm_member(g_member_confirm_pressed,g_member_confirm_code,g_member_confirm_generation,action==3);
  }
  else if (action==5) { if(!buffer_members_back(NULL))buffer_ui_shell_hide("members");return; }
  buffer_ui_shell_show("members");
}
static void buffer_member_remove_event(lv_event_t *event)
{
  const char *id=lv_event_get_user_data(event);
  if (lv_event_get_code(event)==LV_EVENT_PRESSED) { snprintf(g_member_pressed,sizeof(g_member_pressed),"%s",id);g_member_pressed_revision=g_member_revision;return; }
  if (lv_event_get_code(event)!=LV_EVENT_CLICKED) return;
  if (!*id||strcmp(g_member_pressed,id)||g_member_pressed_revision!=g_member_revision) { buffer_members_notice("设备列表已变化，请重试");return; }
  if(g_member_remove_pending[0])return;
  (void)buffer_ui_shell_local_begin("members",lv_event_get_current_target(event));
  snprintf(g_member_remove_pending,sizeof(g_member_remove_pending),"%s",id);g_member_remove_revision=g_member_revision;
  char text[256];snprintf(text,sizeof(text),"移除这台设备的 Buffer 访问？\n\n%s\n\n移除后，该设备需要重新获准才能访问 Buffer。",id);
  buffer_ui_label_set_text(g_member_remove_copy,text);lv_obj_remove_state(g_member_remove_commit,LV_STATE_DISABLED);
  buffer_ui_add_flag(g_members_body,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(g_member_remove_panel,LV_OBJ_FLAG_HIDDEN);
}
static void buffer_members_create(void)
{
  g_member_announcement_valid=false;g_member_remove_pending[0]=g_member_removing[0]=g_member_pressed[0]=0;
  g_members_panel=buffer_card_panel_create(g_root);
  lv_obj_set_style_pad_all(g_members_panel,0,0);
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="members",.title="协作设备",.entry_route="members",.kind=BUFFER_UI_APP,.min_width_dp=160,.min_height_dp=128,
    .actions=(const char *const[]){"open","back","add","stop-add","confirm","reject","remove"},.action_count=7,.closed=buffer_members_closed,.back=buffer_members_back},g_members_panel);
  lv_obj_t *member_header=buffer_card_panel_create(g_members_panel);
  buffer_ui_style_bg_opa(member_header,LV_OPA_TRANSP,0);
  lv_obj_set_style_pad_all(member_header,0,0);lv_obj_set_height(member_header,LV_SIZE_CONTENT);lv_obj_set_style_min_height(member_header,buffer_ui_dp(48),0);
  lv_obj_set_flex_flow(member_header,LV_FLEX_FLOW_ROW);
  lv_obj_t *member_back=buffer_button(member_header,"返回",buffer_members_event,(void *)(intptr_t)5);
  g_members_back=member_back;
  lv_obj_set_size(member_back,buffer_ui_dp(48),buffer_ui_dp(48));buffer_ui_icon_button(member_back,&buffer_back_icon);
  lv_obj_t *title=lv_label_create(member_header);buffer_ui_text_role(title,BUFFER_TEXT_TITLE_LARGE);buffer_ui_label_set_text(title,"协作设备");lv_obj_set_width(title,0);lv_obj_set_flex_grow(title,1);lv_label_set_long_mode(title,LV_LABEL_LONG_WRAP);
  lv_obj_set_flex_align(member_header,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
  lv_obj_t *member_body=buffer_card_panel_create(g_members_panel);
  buffer_ui_style_bg_opa(member_body,LV_OPA_TRANSP,0);
  g_members_body=member_body;
  lv_obj_set_height(member_body,0);lv_obj_set_style_min_height(member_body,0,0);lv_obj_set_flex_grow(member_body,1);
  buffer_ui_add_flag(member_body,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(member_body,LV_DIR_VER);
  lv_obj_set_style_pad_all(member_body,buffer_ui_dp(10),0);
  g_members_state=lv_label_create(member_body);lv_obj_set_width(g_members_state,LV_PCT(100));lv_label_set_long_mode(g_members_state,LV_LABEL_LONG_WRAP);
  g_members_add=buffer_button(member_body,"添加手机或平板",buffer_members_event,(void *)(intptr_t)1);lv_obj_set_width(g_members_add,LV_PCT(100));
  g_members_stop=buffer_button(member_body,"停止添加",buffer_members_event,(void *)(intptr_t)2);lv_obj_set_width(g_members_stop,LV_PCT(100));
  g_members_feedback=lv_label_create(member_body);lv_obj_set_width(g_members_feedback,LV_PCT(100));lv_label_set_long_mode(g_members_feedback,LV_LABEL_LONG_WRAP);buffer_visible(g_members_feedback,false);
  g_members_prompt=lv_label_create(member_body);lv_obj_set_width(g_members_prompt,LV_PCT(100));lv_label_set_long_mode(g_members_prompt,LV_LABEL_LONG_WRAP);
  lv_obj_t *confirm_group=buffer_card_panel_create(member_body);lv_obj_set_style_pad_all(confirm_group,0,0);lv_obj_set_flex_flow(confirm_group,LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_style_pad_column(confirm_group,buffer_ui_dp(4),0);
  g_members_accept=buffer_button(confirm_group,"确认",buffer_members_event,(void *)(intptr_t)3);lv_obj_set_width(g_members_accept,LV_PCT(48));
  g_members_reject=buffer_button(confirm_group,"拒绝",buffer_members_event,(void *)(intptr_t)4);lv_obj_set_width(g_members_reject,LV_PCT(48));
  /* Press snapshots the displayed identity; Click commits only if unchanged. */
  lv_obj_add_event_cb(g_members_accept,buffer_members_event,LV_EVENT_PRESSED,(void *)(intptr_t)3);
  lv_obj_add_event_cb(g_members_reject,buffer_members_event,LV_EVENT_PRESSED,(void *)(intptr_t)4);


  buffer_ui_add_flag(g_members_accept,LV_OBJ_FLAG_HIDDEN);buffer_ui_add_flag(g_members_reject,LV_OBJ_FLAG_HIDDEN);
  for (unsigned i=0;i<BUFFER_FABRIC_MAX_MEMBERS;i++) {
    g_member_labels[i]=lv_label_create(member_body);lv_obj_set_width(g_member_labels[i],LV_PCT(100));lv_label_set_long_mode(g_member_labels[i],LV_LABEL_LONG_WRAP);
    g_member_remove[i]=buffer_button(member_body,"移除 Buffer 访问",buffer_member_remove_event,g_member_ids[i]);lv_obj_set_width(g_member_remove[i],LV_PCT(100));
    buffer_ui_add_flag(g_member_labels[i],LV_OBJ_FLAG_HIDDEN);buffer_ui_add_flag(g_member_remove[i],LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_event_cb(g_member_remove[i],buffer_member_remove_event,LV_EVENT_PRESSED,g_member_ids[i]);

  }
  g_member_remove_panel=buffer_card_panel_create(g_members_panel);
  buffer_ui_style_bg_opa(g_member_remove_panel,LV_OPA_TRANSP,0);
  lv_obj_set_height(g_member_remove_panel,0);lv_obj_set_style_min_height(g_member_remove_panel,0,0);lv_obj_set_flex_grow(g_member_remove_panel,1);
  buffer_ui_add_flag(g_member_remove_panel,LV_OBJ_FLAG_HIDDEN);lv_obj_set_style_pad_all(g_member_remove_panel,0,0);
  lv_obj_t *explanation=buffer_card_panel_create(g_member_remove_panel);lv_obj_set_style_pad_all(explanation,0,0);lv_obj_set_height(explanation,0);lv_obj_set_style_min_height(explanation,0,0);lv_obj_set_flex_grow(explanation,1);
  buffer_ui_style_bg_opa(explanation,LV_OPA_TRANSP,0);
  lv_obj_set_style_pad_all(explanation,buffer_ui_dp(10),0);
  buffer_ui_add_flag(explanation,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(explanation,LV_DIR_VER);
  g_member_remove_copy=lv_label_create(explanation);lv_obj_set_width(g_member_remove_copy,LV_PCT(100));lv_label_set_long_mode(g_member_remove_copy,LV_LABEL_LONG_WRAP);
  lv_obj_t *remove_actions=buffer_card_panel_create(g_member_remove_panel);lv_obj_set_style_pad_all(remove_actions,0,0);lv_obj_set_flex_flow(remove_actions,LV_FLEX_FLOW_ROW_WRAP);lv_obj_set_style_pad_column(remove_actions,buffer_ui_dp(4),0);
  buffer_ui_style_bg_opa(remove_actions,LV_OPA_TRANSP,0);
  lv_obj_t *cancel=buffer_button(remove_actions,"取消",buffer_member_remove_confirm,NULL);lv_obj_set_width(cancel,LV_PCT(48));
  g_member_remove_commit=buffer_button(remove_actions,"移除",buffer_member_remove_confirm,(void *)1);lv_obj_set_width(g_member_remove_commit,LV_PCT(48));
  buffer_ui_button_style(g_member_remove_commit,true);
  buffer_ui_style_bg_color(g_member_remove_commit,lv_color_hex(BUFFER_ERROR),0);
  buffer_ui_style_bg_color(g_member_remove_commit,lv_color_hex(BUFFER_ERROR_PRESSED),LV_STATE_PRESSED);
  buffer_ui_add_flag(g_members_panel,LV_OBJ_FLAG_HIDDEN);
}
static void buffer_members_refresh(void)
{
  if (!g_members_panel || g_touch_recording) return;
  struct buffer_fabric_status_s state;static struct buffer_fabric_member_s members[BUFFER_FABRIC_MAX_MEMBERS];
  buffer_fabric_snapshot(&state);size_t size=buffer_fabric_members(members,BUFFER_FABRIC_MAX_MEMBERS);
  if(size>BUFFER_FABRIC_MAX_MEMBERS)size=BUFFER_FABRIC_MAX_MEMBERS;
  g_member_revision=state.member_revision;
  char text[224];
  snprintf(text,sizeof(text),"已批准 %u/%u 台设备。%s%s",(unsigned)size,BUFFER_FABRIC_MAX_MEMBERS,
    state.adding_member?"正在等待新设备，请在两端确认。":"同一时间连接一台控制设备。",
    state.error?" 操作未完成，请检查连接或重试。":"");
  buffer_ui_label_set_text(g_members_state,text);
  bool confirming=state.member_confirmation || (state.binding==0 && state.code>=0);
  snprintf(g_member_pending,sizeof(g_member_pending),"%s",state.pending_member);g_member_code=state.code;
  g_member_generation=state.member_generation;
  bool new_confirmation=confirming&&(!g_member_announcement_valid||strcmp(g_member_announced,state.pending_member)||g_member_announced_code!=state.code||g_member_announced_generation!=state.member_generation);
  if (confirming) {
    if (state.member_confirmation) snprintf(text,sizeof(text),"允许已信任设备 %.12s… 访问 Buffer？",state.pending_member);
    else snprintf(text,sizeof(text),"请核对两端验证码：%06d",state.code);
    buffer_ui_label_set_text(g_members_prompt,text);
    if(new_confirmation&&!buffer_ui_shell_is_open("members"))buffer_ui_shell_show("members");
  } else buffer_ui_label_set_text(g_members_prompt,"");
  buffer_visible(g_members_accept,confirming);buffer_visible(g_members_reject,confirming);
  if(confirming){snprintf(g_member_announced,sizeof(g_member_announced),"%s",state.pending_member);g_member_announced_code=state.code;g_member_announced_generation=state.member_generation;}
  g_member_announcement_valid=confirming;
  buffer_visible(g_members_stop,state.adding_member);
  if(size>=BUFFER_FABRIC_MAX_MEMBERS||confirming||state.adding_member)lv_obj_add_state(g_members_add,LV_STATE_DISABLED);else lv_obj_remove_state(g_members_add,LV_STATE_DISABLED);
  bool removing_present=false;
  for (unsigned i=0;i<BUFFER_FABRIC_MAX_MEMBERS;i++) {
    if (i<size) {
      memcpy(g_member_ids[i],members[i].device_id,65);
      if(!strcmp(g_member_removing,members[i].device_id))removing_present=true;
      snprintf(text,sizeof(text),"设备 %.12s…%s",members[i].device_id,members[i].selected?(state.active?" · 已连接":" · 最近使用"):"");
      buffer_ui_label_set_text(g_member_labels[i],text);
    } else g_member_ids[i][0]=0;
    buffer_visible(g_member_labels[i],i<size);buffer_visible(g_member_remove[i],i<size);
    if(g_member_removing[0]&&!strcmp(g_member_removing,g_member_ids[i]))lv_obj_add_state(g_member_remove[i],LV_STATE_DISABLED);else lv_obj_remove_state(g_member_remove[i],LV_STATE_DISABLED);
  }
  if(g_member_removing[0]&&(!removing_present||buffer_ui_now_ms()-g_member_remove_started>10000)){
    buffer_members_notice(removing_present?"尚未确认移除，请检查连接后重试。":"已移除此设备的 Buffer 访问。");g_member_removing[0]=0;
  }
  if(g_member_remove_pending[0]&&g_member_remove_revision!=state.member_revision){buffer_ui_label_set_text(g_member_remove_copy,"设备列表已变化，请返回后重新选择。");lv_obj_add_state(g_member_remove_commit,LV_STATE_DISABLED);}
}
#endif

static void *buffer_oobe_retry_worker(void *arg)
{
  int ret;
  (void)arg;

  ret = buffer_wifi_is_ready() ? 0 : buffer_wifi_reconnect();
  /* Retry also refreshes a failed completion read, even if Wi-Fi is still down. */
  buffer_oobe_retry();
  pthread_mutex_lock(&g_oobe_retry_lock);
  g_oobe_retry_result = ret;
  g_oobe_retry_complete = true;
  pthread_mutex_unlock(&g_oobe_retry_lock);
  return NULL;
}

static void buffer_oobe_retry_request(void)
{
  pthread_t worker;
  int ret;

#ifdef CONFIG_BUFFER_VELA_FABRIC
  struct buffer_fabric_status_s fabric;
  buffer_fabric_snapshot(&fabric);
  if (fabric.binding != 0)
    {
      buffer_fabric_request_retry();
      return;
  }
#endif
  pthread_mutex_lock(&g_oobe_retry_lock);
  if (g_oobe_retry_running)
    {
      pthread_mutex_unlock(&g_oobe_retry_lock);
      return;
    }

  g_oobe_retry_running = true;
  g_oobe_retry_complete = false;
  g_oobe_retry_attempted = false;
  pthread_mutex_unlock(&g_oobe_retry_lock);

  ret = buffer_thread_create(&worker, buffer_oobe_retry_worker, NULL);
  if (ret != 0)
    {
      pthread_mutex_lock(&g_oobe_retry_lock);
      g_oobe_retry_running = false;
      g_oobe_retry_result = -ret;
      g_oobe_retry_complete = true;
      pthread_mutex_unlock(&g_oobe_retry_lock);
      return;
    }

  pthread_detach(worker);
}

static void *buffer_oobe_finish_worker(void *arg)
{
  int ret;
  (void)arg;

  ret = buffer_oobe_finish();
  if (ret == 0) buffer_agent_wake();
  pthread_mutex_lock(&g_oobe_finish_lock);
  g_oobe_finish_result = ret;
  g_oobe_finish_complete = true;
  pthread_mutex_unlock(&g_oobe_finish_lock);
  return NULL;
}

static void buffer_oobe_finish_request(void)
{
  pthread_t worker;
  int ret;

  pthread_mutex_lock(&g_oobe_finish_lock);
  if (g_oobe_finish_running)
    {
      pthread_mutex_unlock(&g_oobe_finish_lock);
      return;
    }
  g_oobe_finish_running = true;
  g_oobe_finish_complete = false;
  g_oobe_finish_attempted = false;
  pthread_mutex_unlock(&g_oobe_finish_lock);

  ret = buffer_thread_create(&worker, buffer_oobe_finish_worker, NULL);
  if (ret != 0)
    {
      pthread_mutex_lock(&g_oobe_finish_lock);
      g_oobe_finish_running = false;
      g_oobe_finish_result = -ret;
      g_oobe_finish_complete = true;
      pthread_mutex_unlock(&g_oobe_finish_lock);
      return;
    }
  pthread_detach(worker);
}

static void buffer_oobe_action(enum buffer_oobe_ui_action_e action,
                               void *context)
{
  (void)context;
  switch (action)
    {
      case BUFFER_OOBE_UI_ACTION_RETRY:
        buffer_oobe_retry_request();
        break;
      case BUFFER_OOBE_UI_ACTION_CONFIRM:
#ifdef CONFIG_BUFFER_VELA_FABRIC
        buffer_fabric_confirm_epoch(g_oobe_fabric_status.code,g_oobe_fabric_status.member_generation,true);
#endif
        break;
      case BUFFER_OOBE_UI_ACTION_REJECT:
#ifdef CONFIG_BUFFER_VELA_FABRIC
        buffer_fabric_confirm_epoch(g_oobe_fabric_status.code,g_oobe_fabric_status.member_generation,false);
#endif
        break;
      case BUFFER_OOBE_UI_ACTION_START:
        buffer_oobe_finish_request();
        break;
      default:
        break;
    }
}

static bool buffer_oobe_refresh(void)
{
  struct buffer_oobe_presentation_s presentation;
  struct buffer_agent_status_s agent;
  struct buffer_oobe_ui_state_s ui_state;
#ifdef CONFIG_BUFFER_VELA_FABRIC
  char code[BUFFER_PAIRING_CODE_SIZE] = "";
  char qr_payload[96] = "";
#endif
  int binding_status;
  int retry_result;
  int finish_result;
  bool retry_running;
  bool retry_complete;
  bool finish_running;
  bool finish_complete;
  bool setup_complete;
#ifdef CONFIG_BUFFER_VELA_FABRIC
  bool confirming = false;
#endif

  if (g_oobe_ui == NULL) return false;

  g_wifi_ready = buffer_ui_wifi_ready();
  buffer_agent_snapshot(&agent);
#ifdef CONFIG_BUFFER_VELA_FABRIC
  buffer_fabric_snapshot(&g_oobe_fabric_status);
  binding_status = g_oobe_fabric_status.binding;
  g_paired = binding_status == 0;
  g_pairing_state_known = binding_status == 0 || binding_status == -ENOENT;
  confirming = binding_status == -ENOENT && g_oobe_fabric_status.code >= 0;
#else
  if (!g_paired)
    {
      char host[80], token[128], phone[BUFFER_ID_SIZE];
      int ret = buffer_store_load_pairing(host, sizeof(host), token,
          sizeof(token), phone, sizeof(phone));
      if (ret == 0) { g_paired = true; g_pairing_state_known = true; }
      else if (ret == -ENOENT) g_pairing_state_known = true;
      else g_pairing_state_known = false;
      memset(token, 0, sizeof(token));
    }
  binding_status = g_paired ? 0 : g_pairing_state_known ? -ENOENT : -EIO;
#endif
  setup_complete = buffer_oobe_setup_complete();
  buffer_oobe_present(binding_status, g_paired, g_wifi_ready, agent.ready,
                      agent.skipped, false, setup_complete, &presentation);

  if (presentation.page == BUFFER_OOBE_PAGE_HOME)
    {
      buffer_oobe_ui_set_visible(g_oobe_ui, false);
      buffer_visible(g_root, true);
      return false;
    }

  buffer_visible(g_root, false);
  memset(&ui_state, 0, sizeof(ui_state));
#ifdef CONFIG_BUFFER_VELA_FABRIC
  if (confirming)
    {
      ui_state.page = BUFFER_OOBE_UI_VERIFY;
      snprintf(code, sizeof(code), "%06d", g_oobe_fabric_status.code);
      ui_state.pairing_code = code;
      ui_state.request_epoch=g_oobe_fabric_status.member_generation;
      ui_state.confirm_visible = true;
      ui_state.reject_visible = true;
    }
  else if (presentation.page == BUFFER_OOBE_PAGE_WELCOME)
    {
      ui_state.page = BUFFER_OOBE_UI_WELCOME;
      if (buffer_fabric_qr_locator(qr_payload, sizeof(qr_payload)) == 0)
        ui_state.qr_payload = qr_payload;
      ui_state.hint = BUFFER_OOBE_UI_HINT_WAITING_PHONE;
      ui_state.retry_visible = g_oobe_fabric_status.error != 0 ||
                               qr_payload[0] == '\0';
    }
  else
#endif
#ifndef CONFIG_BUFFER_VELA_FABRIC
  if (presentation.page == BUFFER_OOBE_PAGE_WELCOME)
    {
      ui_state.page = BUFFER_OOBE_UI_WELCOME;
      ui_state.hint = BUFFER_OOBE_UI_HINT_WAITING_PHONE;
      ui_state.retry_visible = true;
    }
  else
#endif
  if (presentation.page == BUFFER_OOBE_PAGE_SERVICES)
    {
      if (!g_wifi_ready)
        {
          ui_state.page = BUFFER_OOBE_UI_NETWORK;
          ui_state.retry_visible = true;
        }
      else
        {
          ui_state.page = BUFFER_OOBE_UI_SERVICE;
          ui_state.retry_visible = true;
          if (agent.error != 0)
            ui_state.hint = BUFFER_OOBE_UI_HINT_SERVICE_RETRY;
          else if (agent.validating)
            ui_state.hint = BUFFER_OOBE_UI_HINT_SERVICE_VALIDATING;
          else
            ui_state.hint = BUFFER_OOBE_UI_HINT_SERVICE_CONFIGURING;
        }
    }
  else if (presentation.page == BUFFER_OOBE_PAGE_READY)
    {
      ui_state.page = BUFFER_OOBE_UI_READY;
    }
  else
    {
      ui_state.page = BUFFER_OOBE_UI_BLOCKED;
      ui_state.retry_visible = true;
    }

  pthread_mutex_lock(&g_oobe_retry_lock);
  retry_running = g_oobe_retry_running;
  retry_complete = g_oobe_retry_complete;
  retry_result = g_oobe_retry_result;
  if (retry_complete)
    {
      g_oobe_retry_complete = false;
      g_oobe_retry_running = false;
      g_oobe_retry_attempted = true;
    }
  pthread_mutex_unlock(&g_oobe_retry_lock);
  ui_state.retry_busy = retry_running;
  if (!g_wifi_ready)
    {
      if (retry_running) ui_state.hint = BUFFER_OOBE_UI_HINT_NETWORK_CONNECTING;
      else if (g_oobe_retry_attempted && retry_result < 0)
        ui_state.hint = BUFFER_OOBE_UI_HINT_NETWORK_RETRY;
    }

  pthread_mutex_lock(&g_oobe_finish_lock);
  finish_running = g_oobe_finish_running;
  finish_complete = g_oobe_finish_complete;
  finish_result = g_oobe_finish_result;
  if (finish_complete)
    {
      g_oobe_finish_complete = false;
      g_oobe_finish_running = false;
      g_oobe_finish_attempted = true;
    }
  pthread_mutex_unlock(&g_oobe_finish_lock);
  ui_state.start_busy = finish_running;
  if (finish_complete && finish_result < 0)
    ui_state.hint = BUFFER_OOBE_UI_HINT_START_RETRY;

  buffer_oobe_ui_render(g_oobe_ui, &ui_state);
  (void)retry_complete;
  (void)g_oobe_finish_attempted;
  return true;
}

static const char *buffer_mode_label(const char *mode)
{
  if (!strcmp(mode, "focus")) return "专注";
  if (!strcmp(mode, "bedtime")) return "睡前";
  if (!strcmp(mode, "rabbit_hole")) return "深挖";
  return "此刻";
}

static void buffer_visible(lv_obj_t *obj, bool visible)
{
  if (visible) buffer_ui_remove_flag(obj, LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_add_flag(obj, LV_OBJ_FLAG_HIDDEN);
}

static void buffer_focus_refresh(void)
{
  struct timespec ts;
  char text[80];
  int64_t started, next, interval, now, remaining, elapsed;
  bool due;
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return;
  now = (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
  started = g_ui_state.focus_started_at;
  next = g_ui_state.next_care_at;
  interval = g_ui_state.focus_interval_ms;
  due = g_ui_state.focus_reminder_due;
  char title[161];
  snprintf(title,sizeof(title),"%s",g_ui_state.focus_title);
  buffer_ui_label_set_text(g_focus_caption,title[0] ? title : "已专注");
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
  buffer_ui_label_set_text(g_focus_time, text);
  if (started <= 0 || next <= 0)
    snprintf(text, sizeof(text), "正在准备计时");
  else if (due)
    snprintf(text, sizeof(text), "到休息时间了");
  else
    {
      int64_t seconds = (remaining + 999) / 1000;
      snprintf(text, sizeof(text), "下次休息 %02lld:%02lld 后",
               (long long)(seconds / 60), (long long)(seconds % 60));
    }
  buffer_ui_label_set_text(g_focus_next, text);
  int progress = interval > 0 ? (int)((interval - remaining) * 1000 / interval) : 0;
  if (progress < 0 || started <= 0) progress = 0;
  if (progress > 1000 || due) progress = 1000;
  lv_arc_set_value(g_focus_arc, progress);
}

static uint64_t buffer_ui_now_ms(void)
{struct timespec now;return clock_gettime(CLOCK_MONOTONIC,&now)?0:(uint64_t)now.tv_sec*1000+now.tv_nsec/1000000;}
struct buffer_timer_command {enum buffer_assistant_operation_e operation;char object[BUFFER_ASSISTANT_ID_SIZE];};
static void buffer_timer_command_run(void *context)
{
 struct buffer_timer_command *command=context;
 struct buffer_assistant_request_s *request=calloc(1,sizeof(*request));int result=-ENOMEM;
 if(request){
  uint32_t nonce[2];arc4random_buf(nonce,sizeof(nonce));
  snprintf(request->operation_id,sizeof(request->operation_id),"ui-timer-%08lx-%08lx",(unsigned long)nonce[0],(unsigned long)nonce[1]);
  request->operation=command->operation;snprintf(request->object_id,sizeof(request->object_id),"%s",command->object);
  struct buffer_assistant_result_s reply;
  for(unsigned attempt=0;attempt<4;attempt++){result=buffer_assistant_execute(request,&reply);if(result!=-EAGAIN)break;usleep(20000);}
  free(request);
 }
 if(result)buffer_set_status("计时器操作未完成，请重试");
 __atomic_store_n(&g_timer_command_pending,0,__ATOMIC_RELEASE);
}
static void buffer_timer_command_submit(const struct buffer_notification_action *action)
{
 unsigned expected=0;if(!__atomic_compare_exchange_n(&g_timer_command_pending,&expected,1,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return;
 struct buffer_timer_command *command=calloc(1,sizeof(*command));
 if(command){
  command->operation=!strcmp(action->id,"pause")?BUFFER_ASSISTANT_TIMER_PAUSE:!strcmp(action->id,"resume")?BUFFER_ASSISTANT_TIMER_RESUME:BUFFER_ASSISTANT_TIMER_RESTART;
  snprintf(command->object,sizeof(command->object),"%.*s",(int)sizeof(command->object)-1,action->object);
  if(!buffer_ui_work_submit(buffer_timer_command_run,command))return;
  free(command);
 }
 __atomic_store_n(&g_timer_command_pending,0,__ATOMIC_RELEASE);buffer_set_status("操作繁忙，请稍后重试");
}
static void buffer_notification_open(const struct buffer_notification_action *action,void *context)
{
 (void)context;int result=0;
 if(!strcmp(action->route,"timer")&&(!strcmp(action->id,"pause")||!strcmp(action->id,"resume")||!strcmp(action->id,"restart"))){buffer_timer_command_submit(action);return;}
 if(!strcmp(action->route,"timer"))result=buffer_a2ui_present_timer(action->object);
 else if(!strcmp(action->route,"alarm"))result=buffer_a2ui_present_alarm(action->object);
 else if(!strcmp(action->route,"assistant")){result=buffer_a2ui_ui_open_surface(action->object);if(!result)return;}
 else if(!strcmp(action->route,"recipe")){result=buffer_a2ui_ui_open_recipe_surface(action->object);if(!result)return;}
 else return;
 if(!result)buffer_a2ui_ui_open_current();
 else buffer_set_status("此内容已变化，请刷新后重试");
}
static void buffer_notification_action_set(struct buffer_notification *notice,const char *route,const char *object)
{
 notice->action_count=1;struct buffer_notification_action *action=&notice->actions[0];
 strcpy(action->id,"open");strcpy(action->label,"打开");
 buffer_notification_text(action->route,sizeof(action->route),route);
 buffer_notification_text(action->object,sizeof(action->object),object);
}
static void buffer_assistant_notice_refresh(void)
{
 uint64_t now=buffer_ui_now_ms();
 for(unsigned type=BUFFER_ASSISTANT_EVENT_TIMER;type<=BUFFER_ASSISTANT_EVENT_ALARM;type++){
  const char *owner=type==BUFFER_ASSISTANT_EVENT_TIMER?"assistant.timer":"assistant.alarm";
  const char *route=type==BUFFER_ASSISTANT_EVENT_TIMER?"timer":"alarm";
  struct buffer_assistant_item_s *items=type==BUFFER_ASSISTANT_EVENT_TIMER?g_notice_timers:g_notice_alarms;
  size_t count=0;unsigned limit=type==BUFFER_ASSISTANT_EVENT_TIMER?BUFFER_ASSISTANT_MAX_TIMERS:BUFFER_ASSISTANT_MAX_ALARMS;
  if(buffer_assistant_ui_snapshot(type,items,limit,&count))continue;
  unsigned batch=buffer_notification_begin(owner);
  if(count>limit)count=limit;
  for(size_t i=0;i<count;i++){
   const struct buffer_assistant_item_s *item=&items[i];if(item->state==BUFFER_ASSISTANT_CANCELLED)continue;
   char key[BUFFER_NOTIFICATION_KEY],text[128];snprintf(key,sizeof(key),"%s:%s",route,item->object_id);
   if(item->state==BUFFER_ASSISTANT_FIRED)snprintf(text,sizeof(text),"%s",type==BUFFER_ASSISTANT_EVENT_TIMER?"计时结束":"已到提醒时间");
   else if(type==BUFFER_ASSISTANT_EVENT_TIMER){
    uint32_t seconds=(item->remaining_ms+999)/1000;
    snprintf(text,sizeof(text),"%02lu:%02lu%s",(unsigned long)(seconds/60),(unsigned long)(seconds%60),item->state==BUFFER_ASSISTANT_PAUSED?" · 暂停":"");
   }else{
    time_t at=item->scheduled_at_unix_ms/1000;struct tm local;
    if(localtime_r(&at,&local))snprintf(text,sizeof(text),"%02d:%02d",local.tm_hour,local.tm_min);
    else snprintf(text,sizeof(text),"等待校准时间");
   }
   struct buffer_notification notice;
   if(buffer_notification_build(&notice,key,owner,item->label[0]?item->label:type==BUFFER_ASSISTANT_EVENT_TIMER?"计时器":"闹钟",text))continue;
   strcpy(notice.channel,type==BUFFER_ASSISTANT_EVENT_TIMER?"timers":"alarms");
   notice.phase=item->state==BUFFER_ASSISTANT_FIRED?BUFFER_NOTICE_COMPLETE:item->state==BUFFER_ASSISTANT_PAUSED?BUFFER_NOTICE_PAUSED:BUFFER_NOTICE_RUNNING;
   notice.live=item->state!=BUFFER_ASSISTANT_FIRED;
   notice.desktop=type!=BUFFER_ASSISTANT_EVENT_TIMER||(item->state!=BUFFER_ASSISTANT_FIRED&&!item->recipe_step);
   notice.importance=notice.phase==BUFFER_NOTICE_COMPLETE?BUFFER_NOTICE_HIGH:BUFFER_NOTICE_NORMAL;
   if(notice.phase==BUFFER_NOTICE_COMPLETE)notice.expires_after_ms=300000;
   buffer_notification_action_set(&notice,route,item->object_id);
   if(type==BUFFER_ASSISTANT_EVENT_TIMER){
    notice.open_action=notice.actions[0];notice.action_count=item->state==BUFFER_ASSISTANT_FIRED?1:2;
    for(unsigned a=0;a<notice.action_count;a++){
     struct buffer_notification_action *action=&notice.actions[a];
     strcpy(action->route,"timer");strcpy(action->object,item->object_id);
     bool restart=a||item->state==BUFFER_ASSISTANT_FIRED;
     strcpy(action->id,restart?"restart":item->state==BUFFER_ASSISTANT_PAUSED?"resume":"pause");
     strcpy(action->label,restart?"重新计时":item->state==BUFFER_ASSISTANT_PAUSED?"继续":"暂停");
    }
   }
   (void)buffer_notification_publish(&notice,now);
  }
  buffer_notification_end(owner,batch);
 }
 buffer_a2ui_ui_publish_activities(now);
 static struct buffer_assistant_surface_card_s surfaces[BUFFER_ASSISTANT_SURFACE_CARDS];
 int count=buffer_a2ui_surface_cards(surfaces,BUFFER_ASSISTANT_SURFACE_CARDS);
 if(count<0)return;
 unsigned batch=buffer_notification_begin("assistant.result");
 for(unsigned i=0;i<(unsigned)count&&i<BUFFER_ASSISTANT_SURFACE_CARDS;i++){
  if(!surfaces[i].recipe&&!buffer_a2ui_ui_result_unread(surfaces[i].id,surfaces[i].sequence))continue;
  if(surfaces[i].recipe&&!buffer_a2ui_ui_recipe_result_visible(surfaces[i].id,surfaces[i].recipe_fingerprint))continue;
  char key[BUFFER_NOTIFICATION_KEY];snprintf(key,sizeof(key),"surface:%s",surfaces[i].id);
  struct buffer_notification notice;
  if(buffer_notification_build(&notice,key,"assistant.result",surfaces[i].title,surfaces[i].preview))continue;
  strcpy(notice.channel,"results");strcpy(notice.status,"助手结果");
  if(surfaces[i].recipe){strcpy(notice.channel,"cooking");strcpy(notice.status,"菜谱");buffer_notification_text(notice.artwork,sizeof(notice.artwork),surfaces[i].image_id);}
  notice.phase=BUFFER_NOTICE_COMPLETE;notice.desktop=true;notice.privacy=BUFFER_NOTICE_SENSITIVE;
  buffer_notification_action_set(&notice,"assistant",surfaces[i].id);
  if(surfaces[i].recipe)strcpy(notice.actions[0].route,"recipe");
  (void)buffer_notification_publish(&notice,now);
 }
 buffer_notification_end("assistant.result",batch);
}

static void buffer_status_text(lv_obj_t *label, const char *text)
{
  if (strcmp(lv_label_get_text(label),text))
    {
      buffer_ui_label_set_text(label,text);
      if (!g_touch_recording) buffer_ui_motion_effect(label);
    }
}
static bool buffer_ui_research_text(char *text, size_t text_size)
{
  struct buffer_research_s research;
  int64_t left;
  if (text == NULL || text_size == 0) return false;
  text[0] = 0;
  if (!g_ui_state.research.present || g_ui_state.mode_dirty ||
      strcmp(g_ui_state.mode, "bedtime") == 0 || strcmp(g_ui_state.mode, "focus") == 0 ||
      (g_ui_state.research.active && strcmp(g_ui_state.mode, "rabbit_hole") != 0))
    { return false; }
  research = g_ui_state.research;
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now)) return false;
  left = g_ui_state.research_deadline - ((int64_t)now.tv_sec*1000 + now.tv_nsec/1000000);
  if (research.active && left > 0)
    snprintf(text, text_size, "研究：%s · 剩余 %lld 分钟", research.title,
             (long long)((left + 59999) / 60000));
  else
    snprintf(text, text_size, "研究：%s · %s", research.title,
             research.active || strcmp(research.reason, "time_up") == 0 ? "时间到" :
             strcmp(research.reason, "mode_changed") == 0 ? "已中断" : "已结束");
  return true;
}
#include "buffer_ui_settings.inc"

static void buffer_ui_refresh(lv_timer_t *timer)
{
  const struct buffer_card_s *cards = g_ui_state.cards;
  char status[BUFFER_STATUS_SIZE];
  char mode[BUFFER_MODE_SIZE];
  char pairing_code[BUFFER_PAIRING_CODE_SIZE];
  char line[BUFFER_TEXT_SIZE * 2 + 64];

  int card_count;
  int card_index;
  bool cards_stale;

  (void)timer;
  settings_refresh();
  if (g_root == NULL)
    {
      return;
    }

  __atomic_add_fetch(&g_ui_heartbeat,1,__ATOMIC_RELEASE);
  buffer_preferences_refresh();
  buffer_ui_progress("oobe");
  bool oobe_active=buffer_oobe_refresh();
  buffer_ui_shell_focus_refresh();
#ifdef CONFIG_BUFFER_VELA_FABRIC
  buffer_ui_progress("members");
  buffer_members_refresh();
#endif
  if (oobe_active)
    {
      return;
    }

  if (g_initial_page_pending)
    {
      buffer_pages_show(0);
      g_initial_page_pending = false;
    }
  int home_command=__atomic_exchange_n(&g_home_command,0,__ATOMIC_ACQ_REL);
  if(home_command){
    if(home_command==3){
      g_touch_recording=false;buffer_ui_shell_capture_expanded(false);buffer_ui_add_flag(g_record_label,LV_OBJ_FLAG_HIDDEN);
      char transcript[1025],capture[BUFFER_ID_SIZE]="";buffer_live_asr_snapshot(transcript,sizeof(transcript));
      if(!pthread_mutex_trylock(&g_buffer.lock)){snprintf(capture,sizeof(capture),"%s",g_buffer.record_id);pthread_mutex_unlock(&g_buffer.lock);}
      buffer_a2ui_ui_capture_prompt(transcript,capture);
    }else{
      buffer_pages_show(0);
      if(home_command==2){g_touch_recording=true;g_record_timer_started=false;}
    }
  }
  int menu_command=__atomic_exchange_n(&g_menu_command,0,__ATOMIC_ACQ_REL);
  if(menu_command && (menu_command==1)==lv_obj_has_flag(g_mode_menu,LV_OBJ_FLAG_HIDDEN))
    buffer_mode_event(NULL);
  int calendar_command=__atomic_exchange_n(&g_calendar_command,0,__ATOMIC_ACQ_REL);
  if(calendar_command){
    if(calendar_command<3)buffer_calendar_ui_request(calendar_command==1);
    else buffer_calendar_ui_request_month(calendar_command==3?1:-1);
  }
  buffer_ui_progress("a2ui");
  buffer_a2ui_ui_refresh();
  buffer_ui_progress("todo");
  buffer_todo_ui_refresh();
  buffer_ui_progress("calendar");
  buffer_calendar_ui_refresh();
  buffer_ui_progress("history");
  buffer_history_ui_refresh();
  /* This snapshot is taken after the renderer releases its model lock; the
   * assistant domain owns its own lock and never performs LVGL work here. */
  buffer_ui_progress("notices");
  buffer_assistant_notice_refresh();
  buffer_ui_progress("assistant");
  buffer_assistant_home_refresh();
  buffer_child_page_layout();

  buffer_ui_progress("runtime");
  bool runtime_fresh = buffer_ui_runtime_snapshot(&g_ui_state);
  snprintf(status, sizeof(status), "%s", g_ui_state.status);
  snprintf(mode, sizeof(mode), "%s", g_ui_state.mode[0] ? g_ui_state.mode : "normal");
  card_count = g_ui_state.card_count;
  bool focus = strcmp(mode, "focus") == 0;
  if (strcmp(g_presented_mode, mode) != 0)
    {
      snprintf(g_presented_mode, sizeof(g_presented_mode), "%s", mode);

    }
  static const char *const modes[] = {"normal", "focus", "bedtime", "rabbit_hole"};
  static const char *const mode_names[] = {"日常", "专注", "睡前", "深挖"};
  int mode_result=__atomic_exchange_n(&g_mode_result,0,__ATOMIC_ACQ_REL);
  if(mode_result&&g_mode_pending){g_mode_failed=mode_result<0?g_mode_pending:0;g_mode_pending=0;}
  for (unsigned i = 0; i < 4; i++)
    {
      if (strcmp(mode, modes[i]) == 0) lv_obj_add_state(g_mode_options[i], LV_STATE_CHECKED);
      else lv_obj_remove_state(g_mode_options[i], LV_STATE_CHECKED);
      char title[32];snprintf(title,sizeof(title),"%s%s",mode_names[i],g_mode_failed==(int)i+1?"·重试":"");
      buffer_ui_label_set_text(lv_obj_get_child(g_mode_options[i],0),g_mode_pending==(int)i+1?"切换中":title);
      if(g_mode_pending)lv_obj_add_state(g_mode_options[i],LV_STATE_DISABLED);
      else lv_obj_remove_state(g_mode_options[i],LV_STATE_DISABLED);
    }
  g_focus_mode = focus;
  buffer_desktop_refresh();
  buffer_todo_ui_set_visible(true);
  buffer_visible(g_assistant_list,false);
  buffer_visible(g_focus_panel, focus);
  buffer_visible(g_card_panel,false);
  buffer_visible(g_queue_label, false);
  buffer_visible(g_controls,false);
  buffer_visible(g_home_tools, false);
  buffer_visible(g_activity_slot, false);

  /* Diagnostic text is available inside More, never in the reading pane. */
  if (focus) buffer_focus_refresh();
  snprintf(pairing_code, sizeof(pairing_code), "%s", g_ui_state.pairing_code);
  card_index = g_ui_state.card_index;
  cards_stale = g_ui_state.cards_stale;
  buffer_status_text(g_status_label, status);
  char backlight[160];
  buffer_backlight_snapshot(backlight,sizeof(backlight));
  if(strcmp(lv_label_get_text(g_backlight_label),backlight)) buffer_ui_label_set_text(g_backlight_label,backlight);
  buffer_visible(g_backlight_label,backlight[0]!=0);
  if (g_touch_recording && (!runtime_fresh || g_ui_state.recording))
    {
      buffer_ui_remove_flag(g_record_label, LV_OBJ_FLAG_HIDDEN);
      enum buffer_audio_phase_e phase = buffer_audio_phase();
      if (phase == BUFFER_AUDIO_CAPTURING)
        {
          if (!g_record_timer_started)
            { g_record_tick = lv_tick_get(); g_record_timer_started = true; }
          buffer_ui_label_set_text(g_record_label,"我在听，上滑取消");
        }
      else buffer_ui_label_set_text(g_record_label,
               phase == BUFFER_AUDIO_SAVING ? "正在保存录音…" : "准备录音…");
    }
  else if (g_ui_state.recording || buffer_audio_phase()!=BUFFER_AUDIO_IDLE)
    {
      buffer_ui_remove_flag(g_record_label,LV_OBJ_FLAG_HIDDEN);
      buffer_ui_label_set_text(g_record_label,buffer_audio_phase()==BUFFER_AUDIO_SAVING?
          "正在保存录音…":"正在结束录音…");
    }
  else if (runtime_fresh && !g_ui_state.recording)
    {
      g_touch_recording = false;
      buffer_ui_label_set_text(g_record_label,"");
      buffer_visible(g_record_label,false);
    }
  bool audio_busy=g_ui_state.recording||buffer_audio_phase()!=BUFFER_AUDIO_IDLE;
  buffer_ui_shell_capture_expanded(g_touch_recording);
  /* Reparenting the dock changes its allocated width. Resolve that before
   * measuring the instruction, rather than clipping it to the old home tile. */
  lv_obj_update_layout(g_record_button);
  if(audio_busy&&!g_touch_recording)lv_obj_add_state(g_record_button,LV_STATE_DISABLED);
  else lv_obj_remove_state(g_record_button,LV_STATE_DISABLED);
  buffer_ui_style_bg_color(g_record_button,
      lv_color_hex(g_touch_recording ? BUFFER_ACCENT : BUFFER_PRIMARY_CONTAINER), 0);
  buffer_ui_style_bg_opa(g_record_button,LV_OPA_COVER,0);
  buffer_ui_style_text_color(g_record_label, lv_color_hex(g_touch_recording ? BUFFER_ON_PRIMARY : BUFFER_MUTED), 0);
  buffer_ui_style_image_recolor(g_mic, lv_color_hex(g_touch_recording ? BUFFER_ON_PRIMARY : BUFFER_ACCENT), 0);
  buffer_ui_style_bg_opa(g_mic,LV_OPA_TRANSP,0);
  buffer_ui_style_bg_color(g_record_button,
      lv_color_hex(g_touch_recording ? BUFFER_PRIMARY_PRESSED : BUFFER_PRIMARY_CONTAINER_PRESSED), LV_STATE_PRESSED);
  char draft[4096];
  buffer_live_asr_snapshot(draft, sizeof(draft));
  buffer_visible(g_live_panel, g_touch_recording);
  if (g_touch_recording)
    buffer_ui_label_set_text(g_live_label, draft[0] ? draft :
        buffer_audio_phase() == BUFFER_AUDIO_CAPTURING ? "正在听…" :
        buffer_audio_phase() == BUFFER_AUDIO_SAVING ? "正在保存录音…" : "开始计时后说话");
  /* A research snapshot carries its real topic and monotonic countdown.
   * Updating this bounded label never starts a session or changes pages.
   * Keep the layout stable until an in-progress recording is released. */
  if (!g_touch_recording)
    {
      char research[BUFFER_TEXT_SIZE + 128];
      bool present = buffer_ui_research_text(research, sizeof(research));
      if (present && strcmp(lv_label_get_text(g_research_label), research))
        buffer_ui_label_set_text(g_research_label, research);
      buffer_visible(g_research_panel, present);
    }
  if (!audio_busy && !g_touch_recording && (buffer_todo_ui_open()||!lv_obj_has_flag(g_card_more_panel, LV_OBJ_FLAG_HIDDEN)))
    {
      lv_obj_set_width(g_record_label, LV_SIZE_CONTENT);
      buffer_ui_label_set_text(g_record_label,buffer_todo_ui_open()?"添加":"追问");
      buffer_visible(g_record_label, true);
    }
  else if (g_touch_recording||audio_busy||status[0]) {
    int width=lv_obj_get_content_width(g_record_button)-lv_obj_get_width(g_mic)-buffer_ui_dp(8);
    lv_point_t natural;lv_text_get_size(&natural,buffer_ui_label_source_text(g_record_label),lv_obj_get_style_text_font(g_record_label,0),lv_obj_get_style_text_letter_space(g_record_label,0),0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
    if(width>natural.x)width=natural.x;
    lv_obj_set_width(g_record_label,width>0?width:0);
  }
  time_t now = time(NULL);
  struct tm local;
  if (now>=1704067200 && localtime_r(&now, &local))
    {
      snprintf(line, sizeof(line), "%02d:%02d", local.tm_hour, local.tm_min);
      buffer_ui_label_set_text(g_clock_label, line);
      buffer_ui_label_set_text(g_desktop_time,line);
      snprintf(line,sizeof(line),"%d月%d日\n周%s",local.tm_mon+1,local.tm_mday,
          (const char *const[]){"日","一","二","三","四","五","六"}[local.tm_wday]);
      lv_point_t date_width;lv_text_get_size(&date_width,line,buffer_ui_font(BUFFER_TEXT_LABEL_LARGE),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
      if(date_width.x>lv_obj_get_content_width(g_clock_tile))snprintf(line,sizeof(line),"%d/%d\n周%s",local.tm_mon+1,local.tm_mday,
          (const char *const[]){"日","一","二","三","四","五","六"}[local.tm_wday]);
      buffer_ui_label_set_text(g_desktop_date,line);
    }
  else {buffer_ui_label_set_text(g_clock_label,"--:--");buffer_ui_label_set_text(g_desktop_time,"--:--");buffer_ui_label_set_text(g_desktop_date,"等待校时");}
  buffer_ui_label_set_text(g_mode_label, buffer_mode_label(mode));
  /* Pairing is durable: temporary loss of Wi-Fi must not reveal the code.
   * Latch successful pairing to avoid flickering during an atomic file update.
   */
#ifdef CONFIG_BUFFER_VELA_FABRIC
  struct buffer_fabric_status_s binding;
  buffer_fabric_snapshot(&binding);
  g_paired = binding.binding == 0;
  g_pairing_state_known = binding.binding == 0 || binding.binding == -ENOENT;
#else
  if (!g_paired)
    {
      char host[80], token[128], phone[BUFFER_ID_SIZE];
      int pairing_ret = buffer_store_load_pairing(host, sizeof(host), token,
          sizeof(token), phone, sizeof(phone));
      if (pairing_ret == 0)
        {
          g_paired = true;
          g_pairing_state_known = true;
        }
      else if (pairing_ret == -ENOENT)
        {
          g_pairing_state_known = true;
        }
      else
        {
          /* Never reveal the claim code when the durable binding is unreadable. */
          g_pairing_state_known = false;
        }
      memset(token, 0, sizeof(token));
    }
#endif
#ifdef CONFIG_BUFFER_VELA_FABRIC
  const char *connection_state = !g_paired ?
      (g_pairing_state_known ? "待配对" : "检查绑定") :
      (binding.sync_conflict ? "需核对" :
       binding.active ? "" :
       binding.connected ? "连接中" : "离线");
#else
  bool synced = g_ui_state.phone_synced;
  const char *connection_state = g_paired ?
      (synced ? "" : "离线") :
      (g_pairing_state_known ? "待配对" : "检查绑定");
#endif
  buffer_status_text(g_connection_label,connection_state);
  char connection_detail[80];snprintf(connection_detail,sizeof(connection_detail),"连接：%s",connection_state[0]?connection_state:"已同步");
  buffer_ui_label_set_text(g_connection_detail,connection_detail);
  buffer_header_layout();
  if (g_paired || !g_pairing_state_known)
    {
      buffer_ui_add_flag(g_pairing_label, LV_OBJ_FLAG_HIDDEN);
    }
  else
    {
      char ble_code[BUFFER_PAIRING_CODE_SIZE];
      buffer_ble_service_code(ble_code, sizeof(ble_code));
      snprintf(line, sizeof(line), "%s %s", ble_code[0] ? "蓝牙验证码" : "配对码",
#ifdef CONFIG_BUFFER_VELA_FABRIC
               ble_code[0] ? ble_code : "请在手机查找设备");
#else
               ble_code[0] ? ble_code : pairing_code);
#endif
      buffer_ui_label_set_text(g_pairing_label, line);
      buffer_ui_remove_flag(g_pairing_label, LV_OBJ_FLAG_HIDDEN);
    }
  (void)cards_stale;
  if (card_index < 0 || card_index >= card_count) card_index = 0;
  if (card_count > 1)
    {
      snprintf(line, sizeof(line), "%d / %d", card_index + 1, card_count);
      buffer_ui_label_set_text(g_page_label, line);
    }
  const char *presented = card_count > 0 ? cards[card_index].card_id : "";
  if (strcmp(presented, g_presented_card) != 0 && !g_touch_recording)
    {
      snprintf(g_presented_card, sizeof(g_presented_card), "%.*s", BUFFER_ID_SIZE-1, presented);
      buffer_ui_motion_enter(g_card_panel, g_card_direction ? g_card_direction : 1, false);
      g_card_direction = 0;
    }
  if (card_index <= 0) lv_obj_add_state(g_previous_button, LV_STATE_DISABLED);
  else lv_obj_remove_state(g_previous_button, LV_STATE_DISABLED);
  if (card_index + 1 >= card_count) lv_obj_add_state(g_next_button, LV_STATE_DISABLED);
  else lv_obj_remove_state(g_next_button, LV_STATE_DISABLED);

  if (card_count == 0 && buffer_ui_shell_is_open("record-detail"))
    buffer_ui_shell_hide("record-detail");

  if (card_count == 0)
    {
      buffer_ui_label_set_text(g_card_kind, !strcmp(mode,"bedtime") ? "睡前" : "");
      buffer_ui_label_set_text(g_card_title, !strcmp(mode,"bedtime") ? "今晚先收束" : "");
      buffer_ui_label_set_text(g_card_summary, !strcmp(mode,"bedtime") ? "暂无已确认的轻量内容，白天可以继续整理。" : "");
    }
  else
    {
      if (card_index < 0 || card_index >= card_count)
        {
          card_index = 0;
        }
      static const char *const kinds[] = {"spark_card", "question_card", "task_card", "care_card", "weekly_card"};
      static const char *const labels[] = {"灵感", "问题", "待办", "关怀", "周回顾"};
      const char *kind_label = "记录";
      for (unsigned i = 0; i < 5; i++)
        if (!strcmp(cards[card_index].kind, kinds[i])) { kind_label = labels[i]; break; }
      if (!strcmp(cards[card_index].kind, "question_card") && cards[card_index].answer[0])
        kind_label = !strcmp(cards[card_index].state, "researched") ? "已深挖" : "已有结果";
      buffer_ui_label_set_text(g_card_kind, kind_label);
      buffer_ui_label_set_text(g_card_title, cards[card_index].title);
      if (cards[card_index].answer[0] != '\0')
        {
          /* Results own the preview once ready; do not bury them below the
           * old summary where the bounded home label clips them away. */
          buffer_ui_label_set_text(g_card_summary, cards[card_index].answer);
        }
      else
        {
          buffer_ui_label_set_text(g_card_summary, cards[card_index].summary);
        }
    }

  /* An open detail is pinned to its card ID, never to a deck position.
   * Refresh it only from that card; a removed card closes the page. */
  const struct buffer_card_s *detail = NULL;
  for (int i = 0; i < card_count; i++)
    if (!strcmp(cards[i].card_id, g_detail_id)) { detail = &cards[i]; break; }
  if (!lv_obj_has_flag(g_card_more_panel, LV_OBJ_FLAG_HIDDEN))
    {
      if (!detail) buffer_ui_shell_hide("record-detail");
      else
        {
          buffer_ui_label_set_text(g_detail_title, detail->title);
          uint64_t conversation = buffer_followup_revision();
          pthread_mutex_lock(&g_wifi_poll_lock);
          bool matches = !strcmp(g_detail_job_id, g_detail_id) &&
              g_detail_job_revision == detail->content_revision &&
              g_detail_job_conversation == conversation;
          char *text = NULL;
          bool new_reply = false;
          if (matches && g_detail_ready)
            {
              new_reply = g_detail_loaded &&
                  g_detail_conversation_revision != g_detail_job_conversation;
              text = g_detail_result;
              g_detail_result = NULL;
              g_detail_ready = false;
              g_detail_loaded = true;
              g_detail_revision = g_detail_job_revision;
              g_detail_conversation_revision = g_detail_job_conversation;
            }
          bool needs_load = !g_detail_loaded ||
              g_detail_revision != detail->content_revision ||
              g_detail_conversation_revision != conversation;
          if (needs_load && (!matches || (!g_detail_requested && !g_detail_working)))
            {
              g_detail_job_epoch++;
              snprintf(g_detail_job_id, sizeof(g_detail_job_id), "%s", g_detail_id);
              snprintf(g_detail_fallback, sizeof(g_detail_fallback), "%s%s%s",
                  detail->answer, detail->answer[0] ? "\n\n" : "", detail->summary);
              g_detail_job_revision = detail->content_revision;
              g_detail_job_conversation = conversation;
              g_detail_requested = true;
              g_detail_ready = false;
              free(g_detail_result);
              g_detail_result = NULL;
              pthread_cond_signal(&g_wifi_poll_wake);
            }
          pthread_mutex_unlock(&g_wifi_poll_lock);
          if (text)
            {
              bool follow = lv_obj_get_scroll_bottom(g_detail_reader) <= buffer_ui_dp(8);
              bool changed = strcmp(lv_label_get_text(g_detail_summary), text) != 0;
              buffer_ui_label_set_text(g_detail_summary, text);
              free(text);
              if (new_reply && changed)
                {
                  lv_obj_update_layout(g_detail_reader);
                  if (follow) lv_obj_scroll_to_y(g_detail_reader,
                      lv_obj_get_scroll_y(g_detail_reader)+lv_obj_get_scroll_bottom(g_detail_reader),LV_ANIM_OFF);
                  else buffer_visible(g_detail_notice,true);
                }
            }
        }
    }
  for (int index = 0; index < BUFFER_CARD_ACTION_BUTTONS; index++)
    {
      bool visible = buffer_detail_action_visible(detail, g_action_names[index]);
      buffer_visible(g_action_buttons[index], visible);
    }

  bool action_busy = g_ui_state.action_requested ||
      __atomic_load_n(&g_ui_command_pending, __ATOMIC_RELAXED) != 0;
  for (int index = 0; index < BUFFER_CARD_ACTION_BUTTONS; index++)
    {
      if (action_busy) lv_obj_add_state(g_action_buttons[index],LV_STATE_DISABLED);
      else lv_obj_remove_state(g_action_buttons[index],LV_STATE_DISABLED);
    }

  buffer_desktop_layout();
}

static void buffer_section_changed(lv_event_t *event)
{
  (void)event;
  /* Switching lists updates sibling visibility in this event, not half a
   * second later at the polling tick. */
  buffer_ui_refresh(NULL);
}

static void buffer_child_page_layout(void)
{
  buffer_ui_shell_layout();
}
static void buffer_pages_show(unsigned page)
{
  if (!g_pages) return;
  if (!page) buffer_ui_shell_home();
  else buffer_a2ui_ui_open_current();
}
static void buffer_page_switch_event(lv_event_t *event)
{
  (void)event;buffer_a2ui_ui_open_current();
}
static void buffer_detail_latest(lv_event_t *event)
{
  (void)event;lv_obj_update_layout(g_detail_reader);
  lv_obj_scroll_to_y(g_detail_reader,lv_obj_get_scroll_y(g_detail_reader)+lv_obj_get_scroll_bottom(g_detail_reader),LV_ANIM_OFF);
  buffer_visible(g_detail_notice,false);
}
static void buffer_menu_closed(void *context)
{ (void)context;__atomic_store_n(&g_menu_open,0,__ATOMIC_RELEASE); }
static void buffer_menu_activated(bool was_visible,void *context)
{
 (void)was_visible;(void)context;__atomic_store_n(&g_menu_open,1,__ATOMIC_RELEASE);
 buffer_menu_layout();
}
static int buffer_detail_prepare(const char *id,void *context)
{
  (void)context;if(!id||!*id)id=g_detail_id;
  if(!*id)return -ENOENT;
  const struct buffer_card_s *card=NULL;
  for(int i=0;i<g_ui_state.card_count;i++)if(!strcmp(id,g_ui_state.cards[i].card_id)){card=&g_ui_state.cards[i];break;}
  if(!card)return -ENOENT;
  if(strcmp(g_detail_id,id)){
    g_detail_loaded=false;snprintf(g_detail_id,sizeof(g_detail_id),"%s",card->card_id);
    buffer_ui_label_set_text(g_detail_title,card->title);
    buffer_ui_label_set_text(g_detail_summary,card->summary);
    lv_obj_scroll_to_y(g_detail_reader,0,LV_ANIM_OFF);buffer_visible(g_detail_notice,false);
    buffer_ui_label_set_text(g_detail_state,!strcmp(card->kind,"spark_card")?"灵感":!strcmp(card->kind,"question_card")?"问题":!strcmp(card->kind,"task_card")?"待办":"记录");
  }
  return 0;
}
static void buffer_detail_activated(bool was_visible,void *context)
{(void)was_visible;(void)context;buffer_ui_refresh(NULL);}
static void buffer_deck_open(lv_event_t *event)
{
  struct assistant_home_row_s *row=lv_event_get_user_data(event);
  (void)buffer_ui_shell_open("record-detail",row->id);
}
static void buffer_desktop_refresh(void)
{
  /* Stable slots preserve object identity and scroll anchors across reorders.
   * Never replace an action target while a finger is down. */
  for(lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input))
    if(lv_indev_get_state(input)==LV_INDEV_STATE_PRESSED)return;
  if(lv_obj_is_scrolling(g_body))return;
  bool used[BUFFER_CARD_LIMIT]={false};
  for(int i=0;i<g_ui_state.card_count && i<BUFFER_CARD_LIMIT;i++){
    const struct buffer_card_s *card=&g_ui_state.cards[i];unsigned slot=BUFFER_CARD_LIMIT;
    for(unsigned j=0;j<BUFFER_CARD_LIMIT;j++)if(!used[j]&&!strcmp(g_deck_tiles[j].id,card->card_id)){slot=j;break;}
    if(slot==BUFFER_CARD_LIMIT)for(unsigned j=0;j<BUFFER_CARD_LIMIT;j++){
      bool needed=false;for(int k=i+1;k<g_ui_state.card_count;k++)if(!strcmp(g_deck_tiles[j].id,g_ui_state.cards[k].card_id))needed=true;
      if(!used[j]&&!needed){slot=j;break;}
    }
    if(slot==BUFFER_CARD_LIMIT)continue;
    used[slot]=true;struct assistant_home_row_s *row=&g_deck_tiles[slot];
    snprintf(row->id,sizeof(row->id),"%s",card->card_id);
    if(g_desktop)(void)buffer_ui_desktop_bind(g_desktop,row->root,row->id,!strcmp(card->kind,"weekly_card")?2:1,2);
    const char *kind=!strcmp(card->kind,"task_card")?"待办":!strcmp(card->kind,"spark_card")?"灵感":!strcmp(card->kind,"question_card")?"问题":!strcmp(card->kind,"care_card")?"关怀":!strcmp(card->kind,"weekly_card")?"周回顾":"记录";
    buffer_ui_label_set_text(row->kind,kind);
    buffer_ui_style_bg_color(row->root,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
    buffer_ui_label_set_text(row->title,card->title);
    buffer_ui_label_set_text(row->summary,card->answer[0]?card->answer:card->summary);
    buffer_visible(row->root,!g_focus_mode);
  }
  for(unsigned i=0;i<BUFFER_CARD_LIMIT;i++)if(!used[i])buffer_visible(g_deck_tiles[i].root,false);
}
static void buffer_shortcut_event(lv_event_t *event)
{
 lv_obj_t *root=lv_event_get_current_target(event);
 if(lv_event_get_target(event)!=root)return;
 lv_obj_t *entry=lv_obj_get_child(root,0);
 if(entry)lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);
}
static void buffer_desktop_content_layout(void *context)
{
 (void)context;
 if(g_home_anchor){
  int w=lv_obj_get_content_width(g_home_anchor),h=lv_obj_get_content_height(g_home_anchor),gap=buffer_ui_dp(10);
  int rail=buffer_ui_dp(70)*(int)buffer_ui_font_scale()/100;if(rail>w/2)rail=w/2;
  int left=w-gap-rail,mic=buffer_ui_dp(48);
  lv_obj_set_pos(g_clock_tile,0,0);lv_obj_set_size(g_clock_tile,left,h-mic-gap);
  lv_obj_set_pos(g_home_capture,0,h-mic);lv_obj_set_size(g_home_capture,left,mic);
  lv_obj_set_pos(g_home_rail,left+gap,0);lv_obj_set_size(g_home_rail,rail,h);
  int row=(h-2*gap)/3;
  for(unsigned i=0;i<3;i++)if(g_shortcuts[i]){
   lv_obj_t *tile=g_shortcuts[i],*entry=lv_obj_get_child(tile,0),*label=lv_obj_get_child(tile,1);
   lv_obj_set_size(tile,rail,row);lv_obj_set_style_pad_all(tile,0,0);lv_obj_set_layout(tile,LV_LAYOUT_NONE);
   buffer_ui_add_flag(label,LV_OBJ_FLAG_HIDDEN);lv_obj_set_size(entry,rail,row);lv_obj_set_pos(entry,0,0);
   lv_obj_set_style_radius(entry,buffer_ui_dp(20),0);buffer_ui_style_bg_opa(entry,LV_OPA_COVER,0);
  }
 }
 lv_obj_update_layout(g_clock_tile);
 int h=lv_obj_get_height(g_clock_tile),w=lv_obj_get_width(g_clock_tile);
 int padding=buffer_ui_dp(6),available=w-2*padding;if(available<1)available=1;
 buffer_ui_text_role(g_desktop_date,BUFFER_TEXT_LABEL_LARGE);
 lv_point_t date_size;lv_text_get_size(&date_size,"12/31\n周三",
   buffer_ui_font(BUFFER_TEXT_LABEL_LARGE),0,lv_obj_get_style_text_line_space(g_desktop_date,0),available,LV_TEXT_FLAG_NONE);
 static const enum buffer_ui_type_role clock_roles[]={BUFFER_TEXT_DISPLAY_MEDIUM,BUFFER_TEXT_HEADLINE_SMALL,BUFFER_TEXT_TITLE_LARGE,BUFFER_TEXT_TITLE_MEDIUM,BUFFER_TEXT_LABEL_LARGE,BUFFER_TEXT_LABEL_MEDIUM};
 enum buffer_ui_type_role clock_role=BUFFER_TEXT_LABEL_MEDIUM;
 for(unsigned i=0;i<sizeof(clock_roles)/sizeof(clock_roles[0]);i++){
  lv_point_t size;lv_text_get_size(&size,"00:00",buffer_ui_font(clock_roles[i]),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  if(size.x<=available&&size.y+date_size.y+2*padding<=h){clock_role=clock_roles[i];break;}
 }
 buffer_ui_text_role(g_desktop_time,clock_role);
 lv_obj_set_style_pad_all(g_clock_tile,padding,0);
 buffer_visible(g_desktop_date,true);lv_obj_set_height(g_desktop_date,LV_SIZE_CONTENT);
 lv_obj_set_width(g_desktop_date,LV_PCT(100));lv_label_set_long_mode(g_desktop_date,LV_LABEL_LONG_WRAP);
 lv_obj_set_width(g_desktop_time,LV_PCT(100));
 if(g_shortcuts[3]){
  lv_obj_t *tile=g_shortcuts[3],*entry=lv_obj_get_child(tile,0),*label=lv_obj_get_child(tile,1);
  int width=lv_obj_get_content_width(lv_obj_get_parent(tile));
  int height=g_shortcuts[0]?lv_obj_get_height(g_shortcuts[0]):buffer_ui_dp(48);
  lv_obj_set_size(tile,width,height);lv_obj_set_pos(tile,0,0);
  buffer_ui_add_flag(label,LV_OBJ_FLAG_HIDDEN);lv_obj_set_style_pad_all(tile,0,0);lv_obj_set_layout(tile,LV_LAYOUT_NONE);
  lv_obj_set_size(entry,LV_PCT(100),LV_PCT(100));lv_obj_set_pos(entry,0,0);
  lv_obj_set_style_radius(entry,buffer_ui_dp(20),0);buffer_ui_style_bg_opa(entry,LV_OPA_COVER,0);
 }
 for(unsigned i=0;i<BUFFER_CARD_LIMIT;i++){
  struct assistant_home_row_s *row=&g_deck_tiles[i];if(!row->root)continue;
  int height=lv_obj_get_height(row->root),width=lv_obj_get_width(row->root);
  enum buffer_ui_type_role title_role=width<buffer_ui_dp(160)?BUFFER_TEXT_TITLE_MEDIUM:BUFFER_TEXT_HEADLINE_SMALL;
  buffer_ui_text_role(row->title,title_role);
  int title=lv_font_get_line_height(buffer_ui_font(title_role));
  int kind=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED));
  int body=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_BODY_MEDIUM));
  int pad=height<title+buffer_ui_dp(32)?buffer_ui_dp(2):buffer_ui_dp(10),gap=buffer_ui_dp(2);
  bool show_kind=height>=title+kind+2*pad+gap;
  lv_point_t measured;lv_text_get_size(&measured,buffer_ui_label_source_text(row->title),buffer_ui_font(title_role),0,0,width-2*pad,LV_TEXT_FLAG_NONE);
  if(measured.y>title&&height>2*title+kind+body+2*pad+2*gap)title*=2;
  int remaining=height-title-2*pad-(show_kind?kind+gap:0)-gap;
  lv_obj_set_style_pad_all(row->root,pad,0);lv_obj_set_style_pad_row(row->root,gap,0);
  buffer_visible(row->kind,show_kind);buffer_visible(row->summary,remaining>=body);
  buffer_ui_text_role(row->kind,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
  buffer_ui_text_role(row->summary,BUFFER_TEXT_BODY_MEDIUM);
  lv_obj_set_height(row->kind,kind);lv_obj_set_height(row->title,title);
  lv_obj_set_flex_grow(row->summary,0);lv_obj_set_height(row->summary,remaining>0?remaining:0);
 }
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
  struct buffer_notification_view *view=&g_activity_views[i];
  bool timer=view->bound_open.id[0]&&!strcmp(view->bound_open.route,"timer");
  bool photo=g_activity_image_frames[i]&&!lv_obj_has_flag(g_activity_image_frames[i],LV_OBJ_FLAG_HIDDEN);
  if(timer||photo){
   int pad=buffer_ui_dp(10),gap=buffer_ui_dp(2),height=lv_obj_get_height(view->root);
   lv_obj_set_style_pad_all(view->root,pad,0);lv_obj_set_style_pad_row(view->root,gap,0);
   buffer_visible(view->status,photo);buffer_visible(view->title,true);buffer_visible(view->progress,false);
   buffer_ui_style_bg_color(view->root,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
   buffer_ui_text_role(view->title,BUFFER_TEXT_LABEL_LARGE_EMPHASIZED);
   int title=lv_font_get_line_height(lv_obj_get_style_text_font(view->title,0));lv_obj_set_height(view->title,title);
   if(photo){
    buffer_ui_text_role(view->status,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);int status=lv_font_get_line_height(lv_obj_get_style_text_font(view->status,0));lv_obj_set_height(view->status,status);
    buffer_visible(view->text,false);int image_height=height-2*pad-title-status-2*gap;if(image_height<1)image_height=1;
    lv_obj_set_height(g_activity_image_frames[i],image_height);
   }else{
    buffer_visible(view->text,true);buffer_visible(view->actions,true);buffer_ui_text_role(view->text,BUFFER_TEXT_DISPLAY_SMALL);
    int actions=buffer_ui_dp(48),remaining=height-2*pad-title-actions-2*gap;if(remaining<1)remaining=1;
    lv_obj_set_style_max_height(view->text,LV_COORD_MAX,0);lv_obj_set_height(view->text,remaining);lv_obj_set_height(view->actions,actions);
    lv_obj_set_style_pad_column(view->actions,buffer_ui_dp(2),0);lv_obj_set_flex_flow(view->actions,LV_FLEX_FLOW_ROW);
    lv_obj_set_style_radius(view->actions,BUFFER_INNER_RADIUS,0);lv_obj_set_style_clip_corner(view->actions,true,0);
    for(unsigned a=0;a<2;a++){lv_obj_set_size(view->buttons[a],0,actions);lv_obj_set_flex_grow(view->buttons[a],1);}
   }
   continue;
  }
  buffer_ui_text_role(view->title,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);buffer_ui_text_role(view->text,BUFFER_TEXT_BODY_LARGE);buffer_ui_text_role(view->status,BUFFER_TEXT_LABEL_LARGE);
  int height=lv_obj_get_height(view->root),title=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED));
  int status=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_LABEL_LARGE));
  int body=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_BODY_LARGE));
  int pad=height<body+buffer_ui_dp(24)?buffer_ui_dp(2):buffer_ui_dp(12),gap=buffer_ui_dp(8);
  bool show_title=height>=title+body+2*pad+gap;
  bool show_status=show_title&&height>=title+body+status+2*pad+2*gap;
  lv_obj_set_style_pad_all(view->root,pad,0);lv_obj_set_style_pad_row(view->root,gap,0);
  buffer_visible(view->status,show_status);buffer_visible(view->title,show_title);
  lv_obj_set_height(view->status,status);lv_obj_set_height(view->title,title);
  int remaining=height-2*pad-(show_title?title+gap:0)-(show_status?status+gap:0);
  lv_obj_set_height(view->text,remaining>0?remaining:0);
 }
 int diameter=lv_obj_get_width(g_focus_panel);if(diameter>lv_obj_get_height(g_focus_panel))diameter=lv_obj_get_height(g_focus_panel);
 if(diameter<1)diameter=1;
 lv_obj_set_size(g_focus_arc,diameter,diameter);lv_obj_center(g_focus_arc);
 enum buffer_ui_type_role focus_role=diameter<buffer_ui_dp(180)?BUFFER_TEXT_TITLE_LARGE:BUFFER_TEXT_DISPLAY_MEDIUM;
 if(lv_font_get_line_height(buffer_ui_font(focus_role))>diameter)focus_role=BUFFER_TEXT_TITLE_MEDIUM;
 const enum buffer_ui_type_role focus_fallbacks[]={BUFFER_TEXT_DISPLAY_SMALL,BUFFER_TEXT_HEADLINE_MEDIUM,BUFFER_TEXT_TITLE_LARGE,BUFFER_TEXT_TITLE_MEDIUM};
 for(unsigned i=0;i<sizeof(focus_fallbacks)/sizeof(focus_fallbacks[0]);i++){
  lv_point_t measured;lv_text_get_size(&measured,buffer_ui_label_source_text(g_focus_time),buffer_ui_font(focus_role),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  if(measured.x<=diameter&&measured.y<=diameter)break;
  if(buffer_ui_type_metrics(focus_fallbacks[i])->size_sp<buffer_ui_type_metrics(focus_role)->size_sp)focus_role=focus_fallbacks[i];
 }
 buffer_ui_text_role(g_focus_time,focus_role);
 int caption=lv_font_get_line_height(lv_obj_get_style_text_font(g_focus_caption,0));
 int timer=lv_font_get_line_height(buffer_ui_font(focus_role));
 int next=lv_font_get_line_height(lv_obj_get_style_text_font(g_focus_next,0));
 bool full=diameter>=caption+timer+next+buffer_ui_dp(32);
 buffer_visible(g_focus_caption,full);buffer_visible(g_focus_next,full);buffer_visible(g_focus_arc,full);
 lv_obj_set_height(g_focus_caption,caption);lv_obj_set_height(g_focus_next,next);
 lv_label_set_long_mode(g_focus_caption,LV_LABEL_LONG_DOT);lv_label_set_long_mode(g_focus_next,LV_LABEL_LONG_DOT);
 lv_obj_align(g_focus_time,LV_ALIGN_CENTER,0,0);
 lv_obj_align(g_focus_caption,LV_ALIGN_CENTER,0,-(timer+caption)/2-buffer_ui_dp(8));
 lv_obj_align(g_focus_next,LV_ALIGN_CENTER,0,(timer+next)/2+buffer_ui_dp(8));
 int research_height=lv_obj_get_height(g_research_panel)-buffer_ui_dp(24);
 lv_obj_set_height(g_research_label,research_height>0?research_height:0);
 lv_label_set_long_mode(g_research_label,LV_LABEL_LONG_DOT);
}
static void buffer_desktop_layout(void)
{if(g_desktop)(void)buffer_ui_desktop_reflow(g_desktop);}
static void buffer_assistant_home_refresh(void)
{
 for(lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input))
  if(lv_indev_get_state(input)==LV_INDEV_STATE_PRESSED)return;
 if(lv_obj_is_scrolling(g_body))return;
 static struct buffer_notification snapshot[BUFFER_NOTIFICATION_LIMIT];
 size_t count=buffer_notification_snapshot(snapshot,BUFFER_NOTIFICATION_LIMIT,true,g_notification_private_previews,buffer_ui_now_ms());
 bool used[BUFFER_NOTIFICATION_LIMIT]={false};
 for(size_t i=0;i<count;i++){
  unsigned slot=BUFFER_NOTIFICATION_LIMIT;
  for(unsigned j=0;j<BUFFER_NOTIFICATION_LIMIT;j++)if(!used[j]&&!strcmp(g_activity_views[j].key,snapshot[i].key)){slot=j;break;}
  if(slot==BUFFER_NOTIFICATION_LIMIT)for(unsigned j=0;j<BUFFER_NOTIFICATION_LIMIT;j++){
   bool needed=false;for(size_t k=i+1;k<count;k++)if(!strcmp(g_activity_views[j].key,snapshot[k].key))needed=true;
   if(!used[j]&&!needed){slot=j;break;}
  }
  if(slot==BUFFER_NOTIFICATION_LIMIT)continue;
  used[slot]=true;buffer_notification_view_bind(&g_activity_views[slot],&snapshot[i]);
  bool timer=snapshot[i].open_action.id[0]&&!strcmp(snapshot[i].channel,"timers");
  bool art=buffer_ui_recipe_image_bind(g_activity_images[slot],snapshot[i].artwork);
  char time[128];buffer_notification_text(time,sizeof(time),snapshot[i].text);char *state=strstr(time," · ");if(state)*state=0;
  unsigned width=timer?128:art?188:192;
  if(timer){
   lv_point_t natural;lv_text_get_size(&natural,time,buffer_ui_font(BUFFER_TEXT_DISPLAY_SMALL),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
   unsigned dpi=lv_display_get_dpi(lv_obj_get_display(g_body));if(!dpi)dpi=160;
   unsigned divisor=dpi*buffer_ui_font_scale();unsigned measured=((unsigned)(natural.x+2*buffer_ui_dp(10))*16000+divisor-1)/divisor;
   if(width<measured)width=measured;
   if(width>1024)width=1024;
  }
  if(g_desktop)(void)buffer_ui_desktop_panel(g_desktop,g_activity_views[slot].root,snapshot[i].key,width);
  buffer_visible(g_activity_image_frames[slot],art);
  if(art)lv_image_set_inner_align(g_activity_images[slot],LV_IMAGE_ALIGN_STRETCH);
  buffer_visible(g_activity_views[slot].text,!art);buffer_visible(g_activity_views[slot].actions,timer);
  if(timer){
   buffer_ui_label_set_text(g_activity_views[slot].text,time);
  }
  if(timer)for(unsigned a=0;a<snapshot[i].action_count;a++){
   lv_obj_t *button=g_activity_views[slot].buttons[a];const lv_image_dsc_t *symbol=!strcmp(snapshot[i].actions[a].id,"restart")?&buffer_restart_alt_icon:!strcmp(snapshot[i].actions[a].id,"resume")?&buffer_play_arrow_icon:&buffer_pause_icon;
   if(lv_obj_get_child_count(button)>1)buffer_ui_icon_set_src(lv_obj_get_child(button,1),symbol);else buffer_ui_icon_button(button,symbol);
   buffer_ui_style_bg_color(button,lv_color_white(),0);lv_obj_set_style_radius(button,buffer_ui_dp(3),0);lv_obj_set_style_pad_all(button,0,0);
   if(__atomic_load_n(&g_timer_command_pending,__ATOMIC_ACQUIRE))lv_obj_add_state(button,LV_STATE_DISABLED);else lv_obj_remove_state(button,LV_STATE_DISABLED);
  }
 }
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(!used[i]){buffer_notification_view_bind(&g_activity_views[i],NULL);if(buffer_ui_recipe_image_ready(g_activity_images[i]))(void)buffer_ui_recipe_image_bind(g_activity_images[i],"");}
 buffer_visible(g_assistant_empty,false);
 count=buffer_notification_snapshot(snapshot,BUFFER_NOTIFICATION_LIMIT,false,g_notification_private_previews,buffer_ui_now_ms());
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
  struct buffer_notification_view *view=&g_notification_views[i];buffer_notification_view_bind(view,i<count?&snapshot[i]:NULL);
  buffer_ui_style_bg_color(view->root,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
  lv_obj_set_style_radius(view->root,buffer_ui_dp(10),0);lv_obj_set_style_pad_all(view->root,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(view->root,0,0);
  bool timer=i<count&&(!strcmp(snapshot[i].channel,"timers")||!strcmp(snapshot[i].channel,"alarms"));
  buffer_ui_text_role(view->title,BUFFER_TEXT_LABEL_LARGE_EMPHASIZED);
  buffer_ui_text_role(view->text,timer?BUFFER_TEXT_TITLE_LARGE:BUFFER_TEXT_BODY_LARGE);
  lv_obj_set_style_max_height(view->text,LV_COORD_MAX,0);lv_label_set_long_mode(view->text,LV_LABEL_LONG_WRAP);
  buffer_visible(view->status,false);
  if(i<count&&snapshot[i].action_count<=1)buffer_ui_add_flag(view->actions,LV_OBJ_FLAG_HIDDEN);
 }
 buffer_visible(g_notification_empty,count==0);
}

/* A passive phone-style system strip: clock and truthful connection state.
 * Actions live in the desktop/apps; Shell owns the top-edge pull gesture. */
static void buffer_header_layout(void)
{
  if(!g_header||!g_connection_detail)return;
  struct buffer_ui_viewport v=buffer_ui_viewport(g_root);
  lv_point_t clock,status;
  lv_text_get_size(&clock,buffer_ui_label_source_text(g_clock_label),buffer_ui_font(BUFFER_TEXT_TITLE_LARGE_EMPHASIZED),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  const char *text=buffer_ui_label_source_text(g_connection_label);
  lv_text_get_size(&status,text,buffer_ui_font(BUFFER_TEXT_LABEL_SMALL),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
  bool wifi=g_wifi_ready,alert=*text!=0;
  bool offline=!strcmp(text,"离线");
  buffer_ui_icon_set_src(g_status_alert,offline?&buffer_devices_off_icon:&buffer_warning_icon);
  buffer_ui_style_image_recolor(g_status_alert,lv_color_hex(offline?BUFFER_ON_SURFACE:BUFFER_WARNING),0);
  buffer_visible(g_status_wifi,wifi);buffer_visible(g_status_alert,alert);
  int available=v.width-2*v.padding-clock.x-buffer_ui_dp(8);
  if(wifi)available-=buffer_ui_dp(18)+buffer_ui_dp(4);
  if(alert)available-=buffer_ui_dp(18)+buffer_ui_dp(4);
  buffer_visible(g_connection_label,!offline&&*text&&available>=status.x);
  lv_obj_set_width(g_connection_label,status.x);
  lv_obj_set_height(g_connection_label,status.y);
  int height=buffer_ui_scaled_sp(28)+buffer_ui_dp(4);
  if(height<buffer_ui_dp(36))height=buffer_ui_dp(36);
  lv_obj_set_height(g_header,height);
}

static void buffer_menu_layout(void)
{
 if(!g_menu_columns)return;
 int gap=buffer_ui_dp(10),target=buffer_ui_dp(48);
 int w=lv_obj_get_content_width(g_mode_menu)-2*gap,h=lv_obj_get_content_height(g_mode_menu)-2*gap;
 int line=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_LABEL_LARGE))+buffer_ui_dp(8);if(target<line)target=line;
 if(h<target)h=target;
 bool stack=w<buffer_ui_dp(280);int left=stack?w:((w-gap)*47/100);
 if(lv_obj_get_style_flex_flow(g_menu_columns,0)!=(stack?LV_FLEX_FLOW_COLUMN:LV_FLEX_FLOW_ROW)){
  lv_obj_scroll_to_y(g_menu_columns,0,LV_ANIM_OFF);lv_obj_scroll_to_y(g_menu_left,0,LV_ANIM_OFF);
 }
 lv_obj_set_flex_flow(g_menu_columns,stack?LV_FLEX_FLOW_COLUMN:LV_FLEX_FLOW_ROW);
 lv_obj_set_style_pad_hor(g_menu_columns,gap,0);lv_obj_set_style_pad_top(g_menu_columns,gap,0);lv_obj_set_style_pad_bottom(g_menu_columns,0,0);
 lv_obj_set_style_pad_ver(g_menu_left,0,0);
 if(stack)buffer_ui_add_flag(g_menu_columns,LV_OBJ_FLAG_SCROLLABLE);else buffer_ui_remove_flag(g_menu_columns,LV_OBJ_FLAG_SCROLLABLE);
 /* Quick controls stay fixed; notifications own the scrolling viewport. */
 buffer_ui_remove_flag(g_menu_left,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_set_scroll_dir(g_menu_left,LV_DIR_NONE);
 lv_obj_scroll_to_y(g_menu_left,0,LV_ANIM_OFF);
 buffer_ui_remove_flag(g_menu_right,LV_OBJ_FLAG_SCROLLABLE);
 /* Menu.svg: separate 2px inner corners/gaps, 8px end corners and
  * 5px horizontal / 8px vertical group insets at the S1 design density.
  * Preserve 48dp action targets rather than shrinking the touch regions. */
 int quick=4*target+buffer_ui_dp(20)+3*buffer_ui_dp(3),reduced=h-quick-gap;if(reduced<target)reduced=target;
 lv_obj_set_size(g_menu_left,left,stack?quick+gap+reduced:h);
 lv_obj_set_width(g_menu_right,stack?LV_PCT(100):0);lv_obj_set_height(g_menu_right,stack?h:LV_PCT(100));lv_obj_set_flex_grow(g_menu_right,stack?0:1);
 lv_obj_set_height(g_menu_quick,quick);
 int half=(left-gap)/2;lv_obj_set_width(g_menu_modes,half);lv_obj_set_width(g_menu_actions,half);
 for(unsigned i=0;i<4;i++){lv_obj_set_size(g_mode_options[i],LV_PCT(100),target);lv_obj_set_style_pad_hor(g_mode_options[i],buffer_ui_dp(4),0);}
 lv_obj_set_size(g_menu_sync,LV_PCT(100),(quick-gap)/2);lv_obj_set_size(g_menu_devices,LV_PCT(100),(quick-gap)/2);
 lv_obj_set_height(g_motion_option,reduced);
 lv_obj_set_width(g_motion_option,LV_PCT(100));
 lv_obj_t *motion_text=lv_obj_get_child(g_motion_option,1);lv_point_t measured;
 lv_text_get_size(&measured,buffer_ui_label_source_text(motion_text),lv_obj_get_style_text_font(motion_text,0),0,0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
 bool motion_stack=measured.x+buffer_ui_dp(24+12+24)>left;
 lv_obj_set_flex_flow(g_motion_option,motion_stack?LV_FLEX_FLOW_COLUMN:LV_FLEX_FLOW_ROW);
 lv_obj_set_style_pad_row(g_motion_option,buffer_ui_dp(4),0);
 if(motion_stack&&reduced<measured.y+buffer_ui_dp(48))lv_obj_set_height(g_motion_option,measured.y+buffer_ui_dp(48));
}
static void buffer_ui_layout(lv_event_t *event)
{
  (void)event;
  struct buffer_ui_viewport v = buffer_ui_viewport(g_root);
  lv_obj_set_style_pad_ver(g_header, buffer_ui_dp(2), 0);
  lv_obj_set_style_pad_hor(g_header, v.padding, 0);
  lv_obj_set_style_pad_column(g_header,buffer_ui_dp(4),0);
  buffer_ui_text_role(g_clock_label,BUFFER_TEXT_TITLE_LARGE_EMPHASIZED);
  buffer_ui_text_role(g_connection_label,BUFFER_TEXT_LABEL_SMALL);
  lv_obj_set_size(g_status_wifi,buffer_ui_dp(20),buffer_ui_dp(20));
  lv_obj_set_size(g_status_alert,buffer_ui_dp(20),buffer_ui_dp(20));
  buffer_header_layout();
  /* Keep the scrolling viewport at the desktop edges. Padding belongs to
   * its content: moving tiles can paint and receive input in that inset. */
  lv_obj_set_style_pad_all(g_buffer_page,0,0);
  lv_obj_set_style_pad_all(g_body,buffer_ui_dp(10),0);
  lv_obj_set_style_pad_row(g_buffer_page, v.gap, 0);
  lv_obj_set_style_pad_row(g_body, buffer_ui_dp(12), 0);
  lv_obj_set_style_pad_column(g_body, buffer_ui_dp(12), 0);
  int capture_height=v.control;
  int label_height=lv_font_get_line_height(buffer_ui_font(BUFFER_TEXT_LABEL_LARGE))+buffer_ui_dp(8);
  if(capture_height<label_height)capture_height=label_height;
  lv_obj_set_height(g_record_button,capture_height);
  lv_obj_set_style_pad_all(g_record_button,0,0);
  lv_obj_set_size(g_mic,buffer_ui_dp(BUFFER_GLYPH_DP),buffer_ui_dp(BUFFER_GLYPH_DP));
  lv_image_set_src(g_mic,buffer_ui_icon_source(&buffer_mic_icon,buffer_ui_dp(BUFFER_GLYPH_DP)));
  lv_image_set_scale(g_mic,LV_SCALE_NONE);
  lv_image_set_inner_align(g_mic,LV_IMAGE_ALIGN_CENTER);
  lv_obj_set_style_pad_all(g_mic,0,0);
  int record_text_width=v.width-2*v.padding-buffer_ui_dp(v.short_screen?40:56)-buffer_ui_dp(8);
  if(record_text_width<0)record_text_width=0;
  lv_obj_set_width(g_record_label,record_text_width);
  int preview_height=v.height-lv_obj_get_style_height(g_header,0)-capture_height-2*v.padding-2*v.gap;
  if(preview_height>buffer_ui_dp(144))preview_height=buffer_ui_dp(144);
  if(preview_height<buffer_ui_dp(32))preview_height=buffer_ui_dp(32);
  lv_obj_set_height(g_live_panel,preview_height);

  lv_obj_set_style_pad_all(g_live_panel,v.padding,0);
  lv_obj_set_height(g_live_label,LV_PCT(100));
  lv_obj_set_style_pad_all(g_card_panel, v.padding, 0);
  lv_obj_set_style_pad_row(g_card_panel, v.gap, 0);
  lv_obj_set_style_min_height(g_card_panel, v.short_screen ? buffer_ui_dp(108) : buffer_ui_dp(244), 0);
  lv_obj_set_height(g_card_panel, LV_SIZE_CONTENT);
  lv_obj_set_height(g_card_title, v.short_screen ? buffer_ui_dp(28) : buffer_ui_dp(48));
  lv_obj_set_height(g_card_summary, v.short_screen ? buffer_ui_dp(36) : buffer_ui_dp(66));
  lv_obj_set_style_text_line_space(g_card_summary, buffer_ui_dp(2), 0);
  lv_obj_set_height(g_controls, v.control);
  lv_obj_set_size(g_previous_button, v.control, v.control);
  lv_obj_set_size(g_next_button, v.control, v.control);
  buffer_ui_shell_layout();
  if(g_card_more_panel&&g_detail_reader){
    int w=lv_obj_get_content_width(g_card_more_panel),h=lv_obj_get_content_height(g_card_more_panel);
    int preferred=buffer_ui_dp(232)*(int)buffer_ui_font_scale()/100;bool narrow=w<preferred+buffer_ui_dp(142);
    lv_obj_set_pos(g_detail_reader,0,0);lv_obj_set_size(g_detail_reader,narrow?w:preferred,narrow?h-buffer_ui_dp(58):h);
    lv_obj_set_style_min_height(g_detail_reader,0,0);
    lv_obj_set_pos(g_detail_notice,narrow?0:preferred+buffer_ui_dp(10),0);
  }
  lv_obj_set_style_pad_all(g_mode_menu,0,0);
  buffer_menu_layout();
#ifdef CONFIG_BUFFER_VELA_FABRIC
  if(g_members_panel)lv_obj_set_style_pad_all(g_members_panel,0,0);
#endif
  lv_obj_update_layout(g_body);
}

static int buffer_ui_build(void)
{
  lv_obj_t *header;
  lv_obj_t *buttons;
  lv_obj_t *controls;
  lv_obj_t *card_panel;
  lv_obj_t *body;
  if (g_root != NULL)
    {
      return 0;
    }

  int font_result=buffer_ui_typography_init();
  if(font_result && font_result!=-ENOTSUP)
    syslog(LOG_ERR,"Buffer typography: no usable Chinese font (%d)\n",font_result);
#ifndef BUFFER_UI_TEST_BUILD
  if(font_result){
    buffer_ui_startup_show();
    lv_obj_t *notice=lv_obj_get_child(g_startup_surface,0);
    lv_obj_set_width(notice,LV_PCT(90));lv_label_set_long_mode(notice,LV_LABEL_LONG_WRAP);
    lv_label_set_text(notice,"Chinese font unavailable.\nInstall the MiSans font resource and restart.");
    lv_obj_center(notice);return font_result;
  }
#endif
  buffer_ui_motion_init();
  g_preference_applied=0;g_preference_font_error=0;
  (void)buffer_ui_preferences_start(buffer_ui_font_scale());
  g_root = lv_obj_create(lv_scr_act());
  if (g_root == NULL)
    {
      return -ENOMEM;
    }
  buffer_ui_add_flag(g_root, LV_OBJ_FLAG_HIDDEN);

  lv_obj_set_size(g_root, LV_PCT(100), LV_PCT(100));
  buffer_ui_style_bg_color(g_root, lv_color_hex(BUFFER_BG), 0);
  buffer_ui_style_bg_opa(g_root, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(g_root, 0, 0);
  lv_obj_set_style_radius(g_root, 0, 0);
  lv_obj_set_style_pad_all(g_root, 0, 0);
  buffer_ui_style_text_color(g_root, lv_color_hex(BUFFER_ON_SURFACE), 0);
  buffer_ui_text_role(g_root,BUFFER_TEXT_BODY_LARGE);
  lv_obj_set_flex_flow(g_root, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(g_root, LV_FLEX_ALIGN_START,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_row(g_root, 0, 0);
  buffer_ui_remove_flag(g_root, LV_OBJ_FLAG_SCROLLABLE);

  header = lv_obj_create(g_root);
  g_header = header;
  lv_obj_remove_style_all(header);
  lv_obj_set_size(header, LV_PCT(100), buffer_ui_dp(24));
  lv_obj_set_style_pad_hor(header, BUFFER_PAGE_PADDING, 0);
  lv_obj_set_style_pad_ver(header, buffer_ui_dp(8), 0);
  lv_obj_set_flex_flow(header, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(header, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(header, LV_OBJ_FLAG_SCROLLABLE);
  g_clock_label = lv_label_create(header);
  buffer_ui_label_set_text(g_clock_label, "--:--");
  buffer_ui_text_role(g_clock_label,BUFFER_TEXT_LABEL_MEDIUM);
  lv_obj_set_width(g_clock_label, 0);
  lv_obj_set_flex_grow(g_clock_label, 1);
  g_status_wifi=buffer_ui_icon_create(header,&buffer_wifi_icon);
  g_status_alert=buffer_ui_icon_create(header,&buffer_warning_icon);
  g_connection_label = lv_label_create(header);
  buffer_ui_text_role(g_connection_label,BUFFER_TEXT_LABEL_SMALL);
  buffer_ui_style_text_color(g_connection_label, lv_color_hex(BUFFER_WARNING), 0);
  lv_label_set_long_mode(g_connection_label, LV_LABEL_LONG_CLIP);
  g_pages = lv_obj_create(g_root);lv_obj_remove_style_all(g_pages);
  lv_obj_set_size(g_pages,LV_PCT(100),0);lv_obj_set_flex_grow(g_pages,1);
  buffer_ui_remove_flag(g_pages,LV_OBJ_FLAG_SCROLLABLE);
  buffer_ui_text_role(g_pages,BUFFER_TEXT_BODY_LARGE);
  g_buffer_page=lv_obj_create(g_pages);lv_obj_remove_style_all(g_buffer_page);
  lv_obj_set_size(g_buffer_page,LV_PCT(100),LV_PCT(100));
  lv_obj_set_flex_flow(g_buffer_page,LV_FLEX_FLOW_COLUMN);
  buffer_ui_remove_flag(g_buffer_page,LV_OBJ_FLAG_SCROLLABLE);
  g_assistant_page=g_buffer_page;
  buffer_ui_shell_init(g_root,g_pages);
  body = lv_obj_create(g_buffer_page);
  g_body = body;
  lv_obj_remove_style_all(body);
  lv_obj_set_size(body, LV_PCT(100), 0);
  lv_obj_set_flex_grow(body, 1);
  lv_obj_set_layout(body,LV_LAYOUT_NONE);
  lv_obj_set_style_pad_row(body, BUFFER_PANEL_GAP, 0);
  lv_obj_add_event_cb(body, buffer_section_changed, LV_EVENT_VALUE_CHANGED, NULL);
  lv_obj_set_scroll_dir(body, LV_DIR_VER);
  lv_obj_set_scrollbar_mode(body, LV_SCROLLBAR_MODE_AUTO);
  buffer_ui_remove_flag(body, LV_OBJ_FLAG_SCROLL_CHAIN_VER);
  buffer_ui_remove_flag(body, LV_OBJ_FLAG_SCROLL_CHAIN);

  g_home_anchor=lv_obj_create(body);lv_obj_remove_style_all(g_home_anchor);lv_obj_set_layout(g_home_anchor,LV_LAYOUT_NONE);
  buffer_ui_remove_flag(g_home_anchor,LV_OBJ_FLAG_SCROLLABLE);
  g_home_capture=lv_obj_create(g_home_anchor);lv_obj_remove_style_all(g_home_capture);buffer_ui_remove_flag(g_home_capture,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE);
  g_home_rail=lv_obj_create(g_home_anchor);lv_obj_remove_style_all(g_home_rail);lv_obj_set_flex_flow(g_home_rail,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(g_home_rail,buffer_ui_dp(10),0);buffer_ui_remove_flag(g_home_rail,LV_OBJ_FLAG_SCROLLABLE);
  g_clock_tile=buffer_card_panel_create(g_home_anchor);
  lv_obj_set_style_radius(g_clock_tile,buffer_ui_dp(20),0);lv_obj_set_style_pad_row(g_clock_tile,0,0);
  buffer_ui_style_bg_color(g_clock_tile,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
  g_desktop_time=lv_label_create(g_clock_tile);buffer_ui_text_role(g_desktop_time,BUFFER_TEXT_HEADLINE_LARGE);
  buffer_ui_label_set_text(g_desktop_time,"--:--");
  g_desktop_date=lv_label_create(g_clock_tile);buffer_ui_text_role(g_desktop_date,BUFFER_TEXT_BODY_LARGE);
  buffer_ui_label_set_text(g_desktop_date,"等待校时");

  /* Stable auxiliary row: a task entry and a shared activity/result slot.
   * Status changes never insert a row into the card reading area. */
  g_home_tools = lv_obj_create(body);
  lv_obj_remove_style_all(g_home_tools);
  lv_obj_set_size(g_home_tools, LV_PCT(100), buffer_ui_dp(48));
  lv_obj_set_flex_flow(g_home_tools, LV_FLEX_FLOW_ROW);
  lv_obj_set_style_pad_column(g_home_tools, buffer_ui_dp(8), 0);
  buffer_ui_remove_flag(g_home_tools, LV_OBJ_FLAG_SCROLLABLE);
  g_activity_slot = lv_obj_create(g_home_tools);
  lv_obj_remove_style_all(g_activity_slot);
  lv_obj_set_size(g_activity_slot, 0, buffer_ui_dp(48));
  lv_obj_set_flex_grow(g_activity_slot, 1);
  buffer_ui_remove_flag(g_activity_slot, LV_OBJ_FLAG_SCROLLABLE);
  g_research_panel = buffer_card_panel_create(body);
  lv_obj_set_height(g_research_panel, LV_SIZE_CONTENT);
  buffer_ui_remove_flag(g_research_panel, LV_OBJ_FLAG_SCROLLABLE);
  g_research_label = lv_label_create(g_research_panel);
  buffer_ui_text_role(g_research_label,BUFFER_TEXT_BODY_LARGE);
  lv_obj_set_width(g_research_label, LV_PCT(100));
  lv_label_set_long_mode(g_research_label, LV_LABEL_LONG_WRAP);
  buffer_ui_label_set_text(g_research_label, "");
  buffer_ui_add_flag(g_research_panel, LV_OBJ_FLAG_HIDDEN);

  card_panel = buffer_card_panel_create(body);
  g_card_panel = card_panel;
  /* A filled reading card is the primary content surface. The preview is
   * bounded; the dedicated detail page retains the complete text. */
  buffer_ui_button_style(card_panel, false);
  buffer_ui_style_bg_color(card_panel, lv_color_hex(BUFFER_PANEL), 0);
  buffer_ui_style_bg_color(card_panel, lv_color_hex(BUFFER_PRIMARY_CONTAINER_PRESSED), LV_STATE_PRESSED);
  lv_obj_set_style_radius(card_panel, buffer_ui_dp(24), 0);
  lv_obj_set_height(card_panel, 0);
  lv_obj_set_flex_grow(card_panel, 1);
  lv_obj_set_style_min_height(card_panel, buffer_ui_dp(244), 0);
  g_card_kind = lv_label_create(card_panel);
  buffer_ui_text_role(g_card_kind,BUFFER_TEXT_LABEL_LARGE);
  buffer_ui_style_text_color(g_card_kind, lv_color_hex(BUFFER_ACCENT), 0);
  buffer_ui_add_flag(card_panel, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_event_cb(card_panel, buffer_card_more_event, LV_EVENT_CLICKED, NULL);
  g_card_title = lv_label_create(card_panel);
  lv_obj_set_width(g_card_title, LV_PCT(100));
  buffer_ui_text_role(g_card_title,BUFFER_TEXT_TITLE_LARGE);
  lv_obj_set_height(g_card_title, buffer_ui_dp(48));
  lv_label_set_long_mode(g_card_title, LV_LABEL_LONG_DOT);
  g_card_summary = lv_label_create(card_panel);
  lv_obj_set_width(g_card_summary, LV_PCT(100));
  buffer_ui_text_role(g_card_summary,BUFFER_TEXT_BODY_LARGE);
  lv_obj_set_height(g_card_summary, buffer_ui_dp(66));
  lv_obj_set_style_text_line_space(g_card_summary, buffer_ui_dp(6), 0);
  lv_label_set_long_mode(g_card_summary, LV_LABEL_LONG_DOT);

  controls = lv_obj_create(card_panel);
  g_controls = controls;
  lv_obj_remove_style_all(controls);
  lv_obj_set_size(controls, LV_PCT(100), buffer_ui_dp(48));
  lv_obj_set_flex_flow(controls, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(controls, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  buffer_ui_remove_flag(controls, LV_OBJ_FLAG_SCROLLABLE);
  g_previous_button = buffer_button(controls, "", buffer_previous_card_event, NULL);
  lv_obj_set_size(g_previous_button, buffer_ui_dp(48), buffer_ui_dp(48));
  buffer_ui_icon_button(g_previous_button,&buffer_previous_icon);
  buffer_ui_style_bg_opa(g_previous_button, LV_OPA_TRANSP, 0);
  g_page_label = lv_label_create(controls);
  g_next_button = buffer_button(controls, "", buffer_next_card_event, NULL);
  lv_obj_set_size(g_next_button, buffer_ui_dp(48), buffer_ui_dp(48));
  buffer_ui_icon_button(g_next_button,&buffer_next_icon);
  buffer_ui_style_bg_opa(g_next_button, LV_OPA_TRANSP, 0);

  g_focus_panel = lv_obj_create(body);
  lv_obj_remove_style_all(g_focus_panel);
  lv_obj_set_size(g_focus_panel, LV_PCT(100), buffer_ui_dp(252));
  lv_obj_set_flex_grow(g_focus_panel, 1);
  buffer_ui_remove_flag(g_focus_panel, LV_OBJ_FLAG_SCROLLABLE);
  g_focus_arc = lv_arc_create(g_focus_panel);
  int focus_size = LV_HOR_RES <= buffer_ui_dp(260) ? buffer_ui_dp(196) : buffer_ui_dp(240);
  lv_obj_set_size(g_focus_arc, focus_size, focus_size);
  lv_obj_center(g_focus_arc);
  lv_arc_set_rotation(g_focus_arc, 270);
  lv_arc_set_bg_angles(g_focus_arc, 0, 360);
  lv_arc_set_range(g_focus_arc, 0, 1000);
  lv_arc_set_value(g_focus_arc, 0);
  lv_obj_remove_style(g_focus_arc, NULL, LV_PART_KNOB);
  buffer_ui_remove_flag(g_focus_arc, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(g_focus_arc, buffer_ui_dp(10), LV_PART_MAIN);
  lv_obj_set_style_arc_width(g_focus_arc, buffer_ui_dp(10), LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(g_focus_arc, lv_color_hex(BUFFER_PANEL), LV_PART_MAIN);
  lv_obj_set_style_arc_color(g_focus_arc, lv_color_hex(BUFFER_ACCENT), LV_PART_INDICATOR);
  g_focus_caption = lv_label_create(g_focus_panel);
  buffer_ui_label_set_text(g_focus_caption, "已专注");
  lv_obj_set_width(g_focus_caption,LV_PCT(80));
  lv_label_set_long_mode(g_focus_caption,LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(g_focus_caption,LV_TEXT_ALIGN_CENTER,0);
  buffer_ui_style_text_color(g_focus_caption, lv_color_hex(BUFFER_MUTED), 0);
  lv_obj_align(g_focus_caption, LV_ALIGN_CENTER, 0, -buffer_ui_dp(58));
  g_focus_time = lv_label_create(g_focus_panel);
  buffer_ui_text_role(g_focus_time,BUFFER_TEXT_DISPLAY_MEDIUM);
  buffer_ui_label_set_text(g_focus_time, "00:00");
  lv_obj_align(g_focus_time, LV_ALIGN_CENTER, 0, -buffer_ui_dp(6));
  g_focus_next = lv_label_create(g_focus_panel);
  buffer_ui_label_set_text(g_focus_next, "正在准备计时");
  lv_obj_set_width(g_focus_next,LV_PCT(100));
  lv_label_set_long_mode(g_focus_next,LV_LABEL_LONG_WRAP);
  lv_obj_set_style_text_align(g_focus_next,LV_TEXT_ALIGN_CENTER,0);
  buffer_ui_style_text_color(g_focus_next, lv_color_hex(BUFFER_MUTED), 0);
  lv_obj_align(g_focus_next, LV_ALIGN_CENTER, 0, buffer_ui_dp(42));


  /* A fixed-height capture row. Only the Material microphone is shown at
   * rest; recording instructions use the reserved space beside it. */
  g_record_button = buffer_button(g_buffer_page, "", NULL, NULL);
  lv_obj_set_size(g_record_button, LV_PCT(100), buffer_ui_dp(64));
  buffer_ui_style_bg_opa(g_record_button, LV_OPA_TRANSP, 0);
  lv_obj_set_style_shadow_width(g_record_button, 0, 0);
  g_record_label = lv_obj_get_child(g_record_button, 0);
  buffer_ui_text_role(g_record_label,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
  lv_obj_set_width(g_record_label, buffer_ui_dp(180));
  lv_label_set_long_mode(g_record_label, LV_LABEL_LONG_DOT);
  buffer_ui_style_text_color(g_record_label, lv_color_hex(BUFFER_MUTED), 0);
  buffer_ui_add_flag(g_record_label, LV_OBJ_FLAG_HIDDEN);
  lv_obj_t *mic = lv_image_create(g_record_button);
  g_mic = mic;
  lv_image_set_src(mic, &buffer_mic_icon);
  lv_image_set_inner_align(mic,LV_IMAGE_ALIGN_CENTER);
  buffer_ui_style_image_recolor(mic, lv_color_hex(BUFFER_ACCENT), 0);
  lv_obj_set_style_image_recolor_opa(mic, LV_OPA_COVER, 0);
  lv_obj_set_style_pad_all(mic, buffer_ui_dp(16), 0);
  buffer_ui_style_bg_color(mic, lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  buffer_ui_style_bg_opa(mic, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(mic, buffer_ui_dp(16), 0);
  lv_obj_move_to_index(mic, 0);
  lv_obj_set_flex_flow(g_record_button, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(g_record_button, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(g_record_button, buffer_ui_dp(8), 0);
  buffer_ui_remove_flag(g_record_button, LV_OBJ_FLAG_SCROLL_CHAIN | LV_OBJ_FLAG_SCROLLABLE);
  buffer_ui_add_flag(g_record_button, LV_OBJ_FLAG_PRESS_LOCK);
  lv_obj_add_event_cb(g_record_button, buffer_record_event, LV_EVENT_ALL, NULL);

  g_live_panel = buffer_card_panel_create(g_buffer_page);
  buffer_ui_add_flag(g_live_panel, LV_OBJ_FLAG_FLOATING);
  lv_obj_set_size(g_live_panel, LV_PCT(100), buffer_ui_dp(144));
  lv_obj_align(g_live_panel, LV_ALIGN_BOTTOM_MID, 0, -buffer_ui_dp(72));
  buffer_ui_style_bg_color(g_live_panel,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
  lv_obj_set_style_radius(g_live_panel,buffer_ui_dp(20),0);
  lv_obj_set_style_pad_all(g_live_panel,buffer_ui_dp(10),0);
  g_live_label = lv_label_create(g_live_panel);
  buffer_ui_text_role(g_live_label,BUFFER_TEXT_TITLE_LARGE);
  lv_obj_set_size(g_live_label, LV_PCT(100), buffer_ui_dp(112));
  lv_label_set_long_mode(g_live_label, LV_LABEL_LONG_DOT);
  buffer_ui_add_flag(g_live_panel, LV_OBJ_FLAG_HIDDEN);

  /* More and card detail are floating surfaces, so opening them cannot
   * resize or push the reading area. The recorder remains above both. */
  g_mode_menu = buffer_card_panel_create(g_root);
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="control-center",.title="控制中心",.entry_route="control-center",.kind=BUFFER_UI_SYSTEM_OVERLAY,.min_width_dp=160,.min_height_dp=128,.preferred_width_dp=416,
    .actions=(const char *const[]){"open","close","set-mode","set-motion","sync","open-members","dismiss-notification"},.action_count=7,.closed=buffer_menu_closed,.activated=buffer_menu_activated},g_mode_menu);
  buffer_ui_add_flag(g_mode_menu, LV_OBJ_FLAG_FLOATING);

  buffer_ui_remove_flag(g_mode_menu, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(g_mode_menu, LV_DIR_VER);
  buffer_ui_style_bg_color(g_mode_menu,lv_color_hex(BUFFER_PANEL),0);lv_obj_set_style_radius(g_mode_menu,0,0);
  g_menu_columns=lv_obj_create(g_mode_menu);lv_obj_remove_style_all(g_menu_columns);lv_obj_set_size(g_menu_columns,LV_PCT(100),0);lv_obj_set_flex_grow(g_menu_columns,1);
  lv_obj_set_style_min_height(g_menu_columns,0,0);lv_obj_set_style_pad_column(g_menu_columns,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_menu_columns,buffer_ui_dp(10),0);
  lv_obj_set_flex_flow(g_menu_columns,LV_FLEX_FLOW_ROW);
  lv_obj_t *menu_body=buffer_card_panel_create(g_menu_columns);g_menu_left=menu_body;
  buffer_ui_style_bg_opa(menu_body,LV_OPA_TRANSP,0);lv_obj_set_height(menu_body,LV_PCT(100));lv_obj_set_style_pad_all(menu_body,0,0);lv_obj_set_style_pad_row(menu_body,buffer_ui_dp(10),0);
  buffer_ui_remove_flag(menu_body,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(menu_body,LV_DIR_NONE);
  g_menu_right=buffer_card_panel_create(g_menu_columns);lv_obj_set_width(g_menu_right,0);lv_obj_set_flex_grow(g_menu_right,1);lv_obj_set_height(g_menu_right,LV_PCT(100));
  buffer_ui_style_bg_color(g_menu_right,lv_color_white(),0);lv_obj_set_style_pad_all(g_menu_right,0,0);buffer_ui_corners(g_menu_right,20,0);buffer_ui_remove_flag(g_menu_right,LV_OBJ_FLAG_SCROLLABLE);
  g_menu_quick=buffer_card_panel_create(menu_body);lv_obj_set_style_pad_all(g_menu_quick,0,0);buffer_ui_style_bg_opa(g_menu_quick,LV_OPA_TRANSP,0);lv_obj_set_flex_flow(g_menu_quick,LV_FLEX_FLOW_ROW);lv_obj_set_style_pad_column(g_menu_quick,buffer_ui_dp(10),0);
  static const char *const modes[] = {"normal", "focus", "bedtime", "rabbit_hole"};
  static const char *const names[] = {"日常", "专注", "睡前", "深挖"};
  lv_obj_t *mode_group=buffer_card_panel_create(g_menu_quick);g_menu_modes=mode_group;
  buffer_ui_style_bg_color(mode_group,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
  lv_obj_set_style_pad_hor(mode_group,buffer_ui_dp(6),0);lv_obj_set_style_pad_ver(mode_group,buffer_ui_dp(10),0);
  lv_obj_set_style_pad_row(mode_group,buffer_ui_dp(3),0);lv_obj_set_height(mode_group,LV_PCT(100));lv_obj_set_flex_flow(mode_group,LV_FLEX_FLOW_COLUMN);
  for (unsigned i = 0; i < 4; i++)
    {
      g_mode_options[i] = buffer_button(mode_group, names[i], buffer_mode_select, (void *)modes[i]);
      buffer_ui_segment_shape(g_mode_options[i],i==0,i==3);buffer_ui_style_bg_color(g_mode_options[i],lv_color_white(),0);
    }
  g_menu_actions=buffer_card_panel_create(g_menu_quick);lv_obj_set_style_pad_all(g_menu_actions,0,0);lv_obj_set_style_pad_row(g_menu_actions,buffer_ui_dp(10),0);lv_obj_set_height(g_menu_actions,LV_PCT(100));buffer_ui_style_bg_opa(g_menu_actions,LV_OPA_TRANSP,0);
  g_menu_sync=buffer_button(g_menu_actions,"同步",buffer_sync_event,NULL);buffer_ui_icon_button(g_menu_sync,&buffer_autorenew_icon);
#ifdef CONFIG_BUFFER_VELA_FABRIC
  buffer_members_create();
  g_menu_devices=buffer_button(g_menu_actions,"协作设备",buffer_members_event,NULL);
#else
  g_menu_devices=buffer_button(g_menu_actions,"协作设备",NULL,NULL);lv_obj_add_state(g_menu_devices,LV_STATE_DISABLED);
#endif
  buffer_ui_icon_button(g_menu_devices,&buffer_devices_other_icon);
  g_motion_option=buffer_button(menu_body,"减少动态",buffer_motion_tile,NULL);
  lv_obj_t *motion_label=lv_obj_get_child(g_motion_option,0);buffer_ui_text_role(motion_label,BUFFER_TEXT_LABEL_LARGE_EMPHASIZED);
  lv_obj_t *motion_icon=buffer_ui_icon_create(g_motion_option,&buffer_animation_icon);lv_obj_move_to_index(motion_icon,0);
  lv_obj_set_flex_align(g_motion_option,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);lv_obj_set_style_pad_hor(g_motion_option,buffer_ui_dp(12),0);lv_obj_set_width(motion_label,LV_SIZE_CONTENT);lv_obj_set_flex_grow(motion_label,0);
  buffer_ui_style_bg_color(g_motion_option,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(g_motion_option,LV_OPA_COVER,0);lv_obj_set_style_radius(g_motion_option,buffer_ui_dp(20),0);
  buffer_ui_style_bg_color(g_motion_option,lv_color_hex(BUFFER_ACCENT),LV_STATE_CHECKED);buffer_ui_style_text_color(g_motion_option,lv_color_white(),LV_STATE_CHECKED);
  buffer_ui_style_text_color(g_motion_option,lv_color_hex(BUFFER_ON_SURFACE),0);
  g_mode_label=lv_label_create(menu_body);buffer_ui_add_flag(g_mode_label,LV_OBJ_FLAG_HIDDEN);
  lv_obj_t *settings_body=settings_create();
  lv_obj_t *privacy_group=settings_section(settings_body,"通知与隐私");
  g_private_option=buffer_ui_switch_create(privacy_group,"显示私密通知预览",g_notification_private_previews,buffer_private_preference,NULL);
  lv_obj_t *display_group=settings_section(settings_body,"显示与文字");
  g_brightness_value=settings_text(display_group,"屏幕亮度",BUFFER_TEXT_TITLE_MEDIUM);
  g_brightness_slider=buffer_ui_slider_create(display_group,1,100,100,false);
  lv_obj_add_event_cb(g_brightness_slider,buffer_brightness_change,LV_EVENT_VALUE_CHANGED,NULL);
  lv_obj_add_event_cb(g_brightness_slider,buffer_brightness_change,LV_EVENT_RELEASED,NULL);
  lv_obj_add_event_cb(g_brightness_slider,buffer_brightness_change,LV_EVENT_KEY,NULL);
  lv_obj_move_to_index(display_group,0);
  g_font_value=settings_text(display_group,"文字大小",BUFFER_TEXT_TITLE_MEDIUM);
  g_font_slider=buffer_ui_slider_create(display_group,0,3,0,true);
  g_font_preview=settings_text(display_group,"让每一天，从容一点。",BUFFER_TEXT_BODY_LARGE);
  lv_obj_set_style_min_height(g_font_preview,buffer_ui_dp(52),0);
  lv_obj_add_event_cb(g_font_slider,buffer_font_preference,LV_EVENT_VALUE_CHANGED,NULL);
  lv_obj_add_event_cb(g_font_slider,buffer_font_preference,LV_EVENT_RELEASED,NULL);
  lv_obj_add_event_cb(g_font_slider,buffer_font_preference,LV_EVENT_KEY,NULL);
  buffer_font_slider_sync();
  g_preference_status=lv_label_create(settings_body);buffer_ui_text_role(g_preference_status,BUFFER_TEXT_BODY_MEDIUM);lv_obj_set_width(g_preference_status,LV_PCT(100));lv_label_set_long_mode(g_preference_status,LV_LABEL_LONG_WRAP);
  g_preference_retry=buffer_button(settings_body,"重试保留设置",buffer_preferences_retry,NULL);lv_obj_set_width(g_preference_retry,LV_PCT(100));
  buffer_visible(g_preference_retry,false);
  g_status_label = lv_label_create(settings_body);
  lv_obj_set_width(g_status_label, LV_PCT(100));
  lv_label_set_long_mode(g_status_label, LV_LABEL_LONG_WRAP);
  g_connection_detail=lv_label_create(settings_body);buffer_ui_text_role(g_connection_detail,BUFFER_TEXT_BODY_MEDIUM);
  lv_obj_set_width(g_connection_detail,LV_PCT(100));lv_label_set_long_mode(g_connection_detail,LV_LABEL_LONG_WRAP);
  g_backlight_label = lv_label_create(settings_body);
  lv_obj_set_width(g_backlight_label,LV_PCT(100));
  lv_label_set_long_mode(g_backlight_label,LV_LABEL_LONG_WRAP);
  buffer_ui_label_set_text(g_backlight_label,"");
  buffer_ui_add_flag(g_backlight_label,LV_OBJ_FLAG_HIDDEN);
  g_pairing_label = lv_label_create(settings_body);
  lv_obj_set_width(g_pairing_label, LV_PCT(100));
  lv_label_set_long_mode(g_pairing_label, LV_LABEL_LONG_WRAP);
  g_queue_label = lv_label_create(settings_body);
  buffer_ui_add_flag(g_queue_label, LV_OBJ_FLAG_HIDDEN);
  buffer_ui_add_flag(g_mode_menu, LV_OBJ_FLAG_HIDDEN);

  g_card_more_panel=buffer_card_panel_create(g_root);
  lv_obj_set_layout(g_card_more_panel,LV_LAYOUT_NONE);lv_obj_set_style_pad_all(g_card_more_panel,0,0);
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="record-detail",.title="记录详情",.entry_route="record-detail",.kind=BUFFER_UI_APP,.capture_mode=BUFFER_UI_CAPTURE_FLOATING,.min_width_dp=160,.min_height_dp=96,
    .actions=(const char *const[]){"open","back","record","perform","latest"},.action_count=5,.prepare_open=buffer_detail_prepare,.activated=buffer_detail_activated},g_card_more_panel);
  lv_obj_t *detail_header=buffer_card_panel_create(g_card_more_panel);
  buffer_ui_add_flag(detail_header,LV_OBJ_FLAG_HIDDEN);
  lv_obj_set_style_pad_all(detail_header,0,0);lv_obj_set_height(detail_header,buffer_ui_dp(48));
  lv_obj_set_flex_flow(detail_header,LV_FLEX_FLOW_ROW);
  lv_obj_t *detail_back=buffer_button(detail_header,"",buffer_card_more_event,NULL);
  lv_obj_set_size(detail_back,buffer_ui_dp(48),buffer_ui_dp(48));buffer_ui_icon_button(detail_back,&buffer_back_icon);
  g_detail_state=lv_label_create(detail_header);buffer_ui_text_role(g_detail_state,BUFFER_TEXT_LABEL_LARGE);
  buffer_ui_label_set_text(g_detail_state,"记录");lv_obj_set_width(g_detail_state,0);lv_obj_set_flex_grow(g_detail_state,1);
  lv_label_set_long_mode(g_detail_state,LV_LABEL_LONG_DOT);
  lv_obj_set_flex_align(detail_header,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
  g_detail_notice=buffer_button(detail_header,"新内容",buffer_detail_latest,NULL);lv_obj_set_width(g_detail_notice,buffer_ui_dp(88));
  buffer_visible(g_detail_notice,false);
  g_detail_reader=buffer_card_panel_create(g_card_more_panel);
  lv_obj_set_style_pad_all(g_detail_reader,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_detail_reader,buffer_ui_dp(2),0);
  buffer_ui_style_bg_color(g_detail_reader,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);lv_obj_set_style_radius(g_detail_reader,buffer_ui_dp(20),0);
  lv_obj_set_style_min_height(g_detail_reader,0,0);lv_obj_set_flex_grow(g_detail_reader,0);
  lv_obj_set_parent(g_detail_state,g_detail_reader);buffer_ui_text_role(g_detail_state,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
  lv_obj_set_flex_grow(g_detail_state,0);lv_obj_set_width(g_detail_state,LV_PCT(100));lv_obj_set_height(g_detail_state,LV_SIZE_CONTENT);
  lv_obj_set_parent(g_detail_notice,g_card_more_panel);buffer_ui_add_flag(g_detail_notice,LV_OBJ_FLAG_FLOATING);
  buffer_ui_add_flag(g_detail_reader,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_detail_reader,LV_DIR_VER);
  g_detail_title = lv_label_create(g_detail_reader);
  lv_obj_set_width(g_detail_title, LV_PCT(100));
  buffer_ui_text_role(g_detail_title,BUFFER_TEXT_HEADLINE_SMALL);
  lv_label_set_long_mode(g_detail_title, LV_LABEL_LONG_WRAP);
  g_detail_summary = lv_label_create(g_detail_reader);
  lv_obj_set_width(g_detail_summary, LV_PCT(100));
  buffer_ui_text_role(g_detail_summary,BUFFER_TEXT_BODY_MEDIUM);
  lv_obj_set_style_text_line_space(g_detail_summary,0,0);
  lv_label_set_long_mode(g_detail_summary, LV_LABEL_LONG_WRAP);
  buttons = lv_obj_create(g_detail_reader);
  lv_obj_remove_style_all(buttons);
  lv_obj_set_size(buttons, LV_PCT(100), buffer_ui_dp(48));
  lv_obj_set_flex_flow(buttons, LV_FLEX_FLOW_ROW);
  lv_obj_set_scroll_dir(buttons,LV_DIR_HOR);
  lv_obj_set_style_pad_row(buttons, buffer_ui_dp(8), 0);
  lv_obj_set_style_pad_column(buttons, buffer_ui_dp(8), 0);
  static const char *const action_titles[] = {"稍后", "完成", "归档", "提醒", "深挖"};
  for (int i = 0; i < BUFFER_CARD_ACTION_BUTTONS; i++)
    {
      g_action_buttons[i] = buffer_button(buttons, action_titles[i], buffer_action_event, (void *)g_action_names[i]);
      if (strcmp(g_action_names[i], "done") && strcmp(g_action_names[i], "rabbit_hole"))
        buffer_ui_style_bg_opa(g_action_buttons[i], LV_OPA_TRANSP, 0);
    }
  buffer_ui_add_flag(g_card_more_panel, LV_OBJ_FLAG_HIDDEN);
  lv_obj_move_to_index(g_live_panel, -1);
  lv_obj_move_to_index(g_record_button, -1);
  (void)buffer_todo_ui_attach(body);
  buffer_todo_ui_dock_entry(g_home_tools);
  (void)buffer_a2ui_ui_attach(body, g_record_button);
  buffer_a2ui_ui_dock_notice(g_activity_slot);
  g_assistant_list = buffer_card_panel_create(body);
  lv_obj_remove_style_all(g_assistant_list);
  lv_obj_set_size(g_assistant_list,buffer_ui_dp(240),LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(g_assistant_list, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(g_assistant_list, buffer_ui_dp(12), 0);
  lv_obj_set_scroll_dir(g_assistant_list, LV_DIR_VER);
  buffer_ui_remove_flag(g_assistant_list, LV_OBJ_FLAG_SCROLL_CHAIN);
  lv_obj_t *launchers = lv_obj_create(g_assistant_list);g_launchers=launchers;
  lv_obj_remove_style_all(launchers);
  lv_obj_set_size(launchers, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(launchers, LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_style_pad_all(launchers, 0, 0);
  buffer_ui_remove_flag(launchers, LV_OBJ_FLAG_SCROLLABLE);
  buffer_todo_ui_dock_launcher(g_assistant_page, launchers);
  (void)buffer_calendar_ui_attach(g_assistant_page, launchers);
  (void)buffer_history_ui_attach(g_assistant_page, launchers);
  g_page_switch=buffer_button(launchers,"",buffer_page_switch_event,NULL);
  (void)buffer_ui_launcher_attach(launchers,g_page_switch,"app.assistant","助手");
  buffer_ui_icon_button(g_page_switch,&buffer_mic_icon);
  lv_obj_add_event_cb(g_assistant_page, buffer_section_changed, LV_EVENT_VALUE_CHANGED, NULL);
  g_assistant_empty = lv_label_create(g_assistant_list);
  buffer_ui_label_set_text(g_assistant_empty, "没有进行中的任务");
  buffer_ui_text_role(g_assistant_empty,BUFFER_TEXT_TITLE_LARGE);
  lv_obj_set_width(g_assistant_empty, LV_PCT(100));
  lv_obj_set_style_text_align(g_assistant_empty, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_style_pad_top(g_assistant_empty, buffer_ui_dp(8), 0);
  buffer_ui_style_text_color(g_assistant_empty, lv_color_hex(BUFFER_MUTED), 0);
  for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
    buffer_notification_view_create(&g_activity_views[i],body,buffer_notification_open,NULL);
    g_activity_views[i].desktop=true;
    lv_obj_set_height(g_activity_views[i].title,buffer_ui_sp(28));
    lv_label_set_long_mode(g_activity_views[i].title,LV_LABEL_LONG_DOT);
    lv_obj_set_height(g_activity_views[i].text,buffer_ui_sp(48));
    lv_label_set_long_mode(g_activity_views[i].text,LV_LABEL_LONG_DOT);
    g_activity_images[i]=buffer_ui_recipe_image_create(g_activity_views[i].root,BUFFER_INNER_RADIUS,&g_activity_image_frames[i]);
    buffer_ui_add_flag(g_activity_image_frames[i],LV_OBJ_FLAG_HIDDEN);
  }
  g_notification_list=buffer_card_panel_create(g_menu_right);
  lv_obj_set_style_pad_all(g_notification_list,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_notification_list,buffer_ui_dp(5),0);
  lv_obj_set_height(g_notification_list,0);lv_obj_set_style_min_height(g_notification_list,0,0);lv_obj_set_flex_grow(g_notification_list,1);
  buffer_ui_style_bg_opa(g_notification_list,LV_OPA_TRANSP,0);buffer_ui_add_flag(g_notification_list,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_notification_list,LV_DIR_VER);
  for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)buffer_notification_view_create(&g_notification_views[i],g_notification_list,buffer_notification_open,NULL);
  g_notification_empty=lv_label_create(g_menu_right);buffer_ui_text_role(g_notification_empty,BUFFER_TEXT_BODY_MEDIUM);buffer_ui_label_set_text(g_notification_empty,"暂无通知");lv_obj_add_flag(g_notification_empty,LV_OBJ_FLAG_FLOATING);lv_obj_center(g_notification_empty);
  for(unsigned i=0;i<BUFFER_CARD_LIMIT;i++){
    struct assistant_home_row_s *row=&g_deck_tiles[i];
    row->root=buffer_card_panel_create(body);lv_obj_add_flag(row->root,LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_radius(row->root,buffer_ui_dp(20),0);
    lv_obj_add_event_cb(row->root,buffer_deck_open,LV_EVENT_CLICKED,row);
    row->kind=lv_label_create(row->root);buffer_ui_text_role(row->kind,BUFFER_TEXT_LABEL_LARGE);
    row->title=lv_label_create(row->root);buffer_ui_text_role(row->title,BUFFER_TEXT_TITLE_LARGE);
    lv_obj_set_width(row->title,LV_PCT(100));lv_label_set_long_mode(row->title,LV_LABEL_LONG_DOT);
    lv_obj_set_height(row->title,buffer_ui_dp(28));
    row->summary=lv_label_create(row->root);buffer_ui_text_role(row->summary,BUFFER_TEXT_BODY_LARGE);
    lv_obj_set_width(row->summary,LV_PCT(100));lv_label_set_long_mode(row->summary,LV_LABEL_LONG_DOT);
    buffer_visible(row->root,false);
  }
  buffer_a2ui_ui_dock_page(g_assistant_page);
  buffer_ui_shell_capture(g_record_button,g_live_panel);

  g_oobe_ui = buffer_oobe_ui_create(lv_scr_act(), buffer_font_oobe_hero(),
                                    buffer_font_small(), buffer_font_small(),
                                    buffer_oobe_action, NULL);
  if (g_oobe_ui == NULL)
    {
      /* Keep home and its controls inaccessible if the full-screen setup
       * surface could not be allocated. */
      buffer_ui_add_flag(g_root, LV_OBJ_FLAG_HIDDEN);
      return -ENOMEM;
    }

  g_wifi_poll_stop = false;
  g_wifi_poll_ready = false;
  if (buffer_thread_create(&g_wifi_poll_thread, buffer_ui_wifi_poll, NULL) == 0)
    g_wifi_poll_started = true;
  else
    buffer_set_status("网络状态监测启动失败");
  g_desktop=buffer_ui_desktop_create(g_body,g_root,buffer_desktop_content_layout,NULL);
  if(!g_desktop)return -ENOMEM;
  buffer_ui_desktop_strip(g_desktop,true);
  (void)buffer_ui_desktop_panel(g_desktop,g_home_anchor,"home",212);
  for(unsigned i=0;i<3&&lv_obj_get_child_count(g_launchers);i++){
    lv_obj_t *tile=lv_obj_get_child(g_launchers,0);g_shortcuts[i]=tile;lv_obj_set_parent(tile,g_home_rail);
    buffer_ui_style_bg_color(tile,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(tile,LV_OPA_COVER,0);
    lv_obj_set_style_radius(tile,BUFFER_CARD_RADIUS,0);lv_obj_add_flag(tile,LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_flex_align(tile,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
    lv_obj_add_event_cb(tile,buffer_shortcut_event,LV_EVENT_CLICKED,NULL);
    if(!i){lv_obj_t *entry=lv_obj_get_child(tile,0);lv_obj_t *icon=lv_obj_get_child(entry,lv_obj_get_child_count(entry)-1);lv_image_set_src(icon,&buffer_ready_icon);}
  }
  lv_obj_t *settings_slot=lv_obj_create(g_body);lv_obj_remove_style_all(settings_slot);buffer_ui_remove_flag(settings_slot,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_t *settings_entry=buffer_button(settings_slot,"设置",settings_open,NULL);
  lv_obj_t *settings_tile=buffer_ui_launcher_attach(settings_slot,settings_entry,"app.settings","设置");buffer_ui_icon_button(settings_entry,&buffer_settings_icon);
  g_shortcuts[3]=settings_tile;
  buffer_ui_style_bg_color(settings_tile,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(settings_tile,LV_OPA_COVER,0);
  lv_obj_set_style_radius(settings_tile,BUFFER_CARD_RADIUS,0);lv_obj_add_flag(settings_tile,LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_flex_align(settings_tile,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
  lv_obj_add_event_cb(settings_tile,buffer_shortcut_event,LV_EVENT_CLICKED,NULL);
  (void)buffer_ui_desktop_panel(g_desktop,settings_slot,"app.settings",70);
  buffer_ui_add_flag(g_assistant_list,LV_OBJ_FLAG_HIDDEN);
  (void)buffer_ui_desktop_bind(g_desktop,g_research_panel,"research",2,1);
  (void)buffer_ui_desktop_bind(g_desktop,g_focus_panel,"focus",2,2);
  for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)(void)buffer_ui_desktop_bind(g_desktop,g_activity_views[i].root,"",1,1);
  for(unsigned i=0;i<BUFFER_CARD_LIMIT;i++)(void)buffer_ui_desktop_bind(g_desktop,g_deck_tiles[i].root,"",1,2);
  if(!buffer_ui_shell_statusbar(g_header,"control-center"))return -EINVAL;
  buffer_ui_shell_capture_dock(g_home_capture);
  lv_obj_add_event_cb(g_root, buffer_ui_layout, LV_EVENT_SIZE_CHANGED, NULL);
  lv_obj_update_layout(g_root);
  buffer_ui_layout(NULL);
  g_refresh_timer = lv_timer_create(buffer_ui_refresh, 500, NULL);
  buffer_ui_refresh(g_refresh_timer);
  buffer_ui_startup_hide();
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
  buffer_ui_preferences_shutdown();
  buffer_ui_desktop_destroy(g_desktop);g_desktop=NULL;memset(g_shortcuts,0,sizeof(g_shortcuts));
  pthread_mutex_lock(&g_wifi_poll_lock);
  g_wifi_poll_stop = true;
  pthread_cond_signal(&g_wifi_poll_wake);
  pthread_mutex_unlock(&g_wifi_poll_lock);
  if (g_wifi_poll_started) pthread_join(g_wifi_poll_thread, NULL);
  g_wifi_poll_started = false;
  free(g_detail_result); g_detail_result = NULL;
  g_detail_requested = g_detail_working = g_detail_ready = false;
  g_detail_job_id[0] = 0;
  memset(&g_ui_state, 0, sizeof(g_ui_state));
  g_header = NULL;
  if (g_touch_recording) (void)buffer_touch_record_cancel();
  buffer_live_asr_shutdown();
  buffer_ui_motion_shutdown();
  g_presented_card[0] = 0; g_presented_mode[0] = 0;
  g_touch_recording = false;
  g_paired = false;
  g_pairing_state_known = false;
  g_wifi_ready = false;
  pthread_mutex_lock(&g_oobe_retry_lock);
  g_oobe_retry_running = false;
  g_oobe_retry_complete = false;
  g_oobe_retry_attempted = false;
  g_oobe_retry_result = 0;
  pthread_mutex_unlock(&g_oobe_retry_lock);
  pthread_mutex_lock(&g_oobe_finish_lock);
  g_oobe_finish_running = false;
  g_oobe_finish_complete = false;
  g_oobe_finish_attempted = false;
  g_oobe_finish_result = 0;
  pthread_mutex_unlock(&g_oobe_finish_lock);
  g_focus_mode = false;
  g_initial_page_pending = true;
  g_pages = g_buffer_page = g_assistant_page = g_page_switch = NULL;
  g_assistant_list = g_assistant_empty = NULL;
  memset(g_activity_views,0,sizeof(g_activity_views));memset(g_notification_views,0,sizeof(g_notification_views));memset(g_activity_images,0,sizeof(g_activity_images));
  memset(g_activity_image_frames,0,sizeof(g_activity_image_frames));
  g_notification_list=g_notification_empty=NULL;g_settings=g_settings_body=g_settings_preferences=g_settings_feedback=g_settings_confirm=g_settings_keyboard=g_settings_ssid=g_settings_password=g_settings_connect=g_settings_reset_copy=NULL;g_settings_reset_kind=0;g_settings_epoch++;g_notification_private_previews=false;buffer_notification_reset();
  g_preference_status=g_preference_retry=g_private_option=g_motion_option=NULL;g_preference_applied=0;g_preference_font_error=0;
  g_body = NULL;
  g_menu_columns=g_menu_left=g_menu_right=g_menu_quick=g_menu_modes=g_menu_actions=g_menu_sync=g_menu_devices=NULL;
  g_home_anchor=g_home_rail=g_home_capture=NULL;
  g_home_tools = g_activity_slot = g_card_kind = NULL;
  g_detail_id[0] = 0;
  g_detail_revision = 0; g_detail_loaded = false;
  g_live_panel = g_live_label = NULL;
  g_record_button = NULL;
  g_record_label = NULL;
  g_mic = NULL;
  if (g_refresh_timer != NULL)
    {
      lv_timer_delete(g_refresh_timer);
      g_refresh_timer = NULL;
    }
  buffer_calendar_ui_detach();
  buffer_history_ui_detach();
  buffer_todo_ui_detach();
  buffer_a2ui_ui_detach();
  buffer_ui_shell_shutdown();
  if (g_oobe_ui != NULL)
    {
      buffer_oobe_ui_destroy(g_oobe_ui);
      g_oobe_ui = NULL;
    }
  if (g_root != NULL)
    {
      lv_obj_del(g_root);
      g_root = NULL;
    }
  g_clock_label = g_detail_title = g_detail_summary = g_page_label = NULL;
  g_detail_reader=g_detail_notice=g_detail_state=g_launchers=NULL;
  g_clock_tile=g_desktop_time=g_desktop_date=NULL;memset(g_deck_tiles,0,sizeof(g_deck_tiles));
  g_status_label = NULL;
  g_backlight_label = NULL;
  g_mode_label = NULL;
  g_mode_pending=g_mode_failed=0;__atomic_store_n(&g_mode_result,0,__ATOMIC_RELEASE);
  __atomic_add_fetch(&g_mode_generation,1,__ATOMIC_ACQ_REL);
  g_pairing_label = NULL;
  g_connection_label = NULL;
  g_status_wifi=g_status_alert=NULL;
  g_connection_detail = NULL;
#ifdef CONFIG_BUFFER_VELA_FABRIC
  g_members_panel=g_members_state=g_members_prompt=g_members_accept=g_members_reject=NULL;
  g_members_body=g_members_feedback=g_members_add=g_members_stop=g_member_remove_panel=g_member_remove_copy=g_member_remove_commit=NULL;
  g_member_remove_pending[0]=g_member_removing[0]=0;g_member_announcement_valid=false;
#endif
  g_card_title = NULL;
  g_card_summary = NULL;
  g_queue_label = NULL;
  g_card_panel = NULL;
  g_controls = NULL;
  g_focus_panel = NULL;
  g_focus_arc = NULL;
  g_focus_time = NULL;
  g_focus_next = NULL;
  g_research_panel = NULL;
  g_research_label = NULL;
  memset(g_action_buttons, 0, sizeof(g_action_buttons));g_font_slider=g_font_value=g_font_preview=g_brightness_slider=g_brightness_value=NULL;
  buffer_ui_typography_shutdown();
}
