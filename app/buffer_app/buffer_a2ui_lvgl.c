/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>

#include "buffer_a2ui_internal.h"
#include "buffer_app.h"
#include "buffer_voice.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_decor.h"
#ifndef BUFFER_UI_TEST_BUILD
#include "buffer_images.h"
#endif
#include "buffer_ui_layout.h"
#include "buffer_ui_shell.h"
#include "buffer_ui_icons.h"
#include "buffer_ui_notifications.h"
#include "buffer_ui_motion.h"
#include "buffer_todo.h"

#include <errno.h>
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define A2UI_DYNAMIC_LABELS BUFFER_A2UI_COMPONENT_COUNT

enum ui_action_kind_e
{
  UI_ACTION_GENERIC = 1,
  UI_ACTION_TIMER_TOGGLE,
  UI_ACTION_TIMER_CANCEL,
  UI_ACTION_ALARM_CANCEL,
  UI_ACTION_RECIPE_START,
  UI_ACTION_CLOSE
};

struct dynamic_label_s
{
  lv_obj_t *label;
  lv_obj_t *toggle;
  lv_obj_t *cancel;
  enum buffer_assistant_event_e type;
  bool inverse;
  char object_id[BUFFER_ASSISTANT_ID_SIZE];
};

static lv_obj_t *g_inline_host;
static lv_obj_t *g_overlay;
static lv_obj_t *g_overlay_content;
static lv_obj_t *g_overlay_heading;
static lv_obj_t *g_render_parent;
static lv_obj_t *g_record_button;
static lv_obj_t *g_recipe_preview_image,*g_recipe_preview_frame,*g_recipe_preview_panel,*g_session_response,*g_recipe_save_retry;
static int g_recipe_save_error;
static struct buffer_a2ui_surface_s g_surface_snapshot;
static struct buffer_a2ui_surface_s g_incoming;
static bool g_open_snapshot_ready;
static struct buffer_a2ui_surface_s g_previous;
static lv_obj_t *g_nodes[BUFFER_A2UI_COMPONENT_COUNT];
static lv_obj_t *g_old_nodes[BUFFER_A2UI_COMPONENT_COUNT];
static lv_obj_t *g_result_notice;
static lv_obj_t *g_home_result_notice;
static lv_obj_t *g_feedback,*g_empty_hint,*g_empty_host,*g_capture_request;
static bool g_generic_reply;
static lv_obj_t *g_inline_error;
static char g_pending_id[BUFFER_A2UI_SURFACE_ID_SIZE];
static bool g_accept_incoming;
static bool g_reconcile;
static unsigned g_depth;
/* LVGL dispatches widget events serially on its owner thread. Keep the large
 * tool request off that thread's default 8 KiB stack. */
static struct buffer_assistant_request_s g_ui_request;
static struct dynamic_label_s g_dynamic_labels[A2UI_DYNAMIC_LABELS];
static size_t g_dynamic_label_count;
static char g_rendered_surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
static uint64_t g_rendered_sequence;
static bool g_rendered_full_screen;
static bool g_attached;
static lv_obj_t *g_page_host;
static bool g_page_browsing;
static bool g_capture_pending,g_recipe_expanded=true,g_presentation_dirty,g_timer_expanded;
static bool g_recipe_return_preview;
static bool g_capture_auto_open;
static char g_capture_origin[BUFFER_UI_SURFACE_TEXT];
static char g_capture_id[BUFFER_ASSISTANT_ID_SIZE];
static char g_capture_prompt[1025],g_prompt_surface[BUFFER_A2UI_SURFACE_ID_SIZE];
static uint32_t g_ui_operation_sequence;

static void render_node(const struct buffer_a2ui_surface_s *surface,
                        uint8_t component_index, lv_obj_t *parent,
                        bool parent_is_row);

static void ui_inline_error_deleted(lv_event_t *event)
{ (void)event; g_inline_error = NULL; }
static void ui_failure(const char *message, int error)
{
  (void)error;
  char text[128]; snprintf(text,sizeof(text),"%s",message);
  if (pthread_mutex_trylock(&g_buffer.lock) == 0)
    { snprintf(g_buffer.status, sizeof(g_buffer.status), "%s", text); pthread_mutex_unlock(&g_buffer.lock); }
  if (g_rendered_full_screen && g_feedback)
    { buffer_ui_label_set_text(g_feedback,text); buffer_ui_remove_flag(g_feedback,LV_OBJ_FLAG_HIDDEN); }
  else if (g_inline_host)
    {
      if (!g_inline_error)
        {
          g_inline_error = lv_label_create(g_inline_host);
          lv_obj_set_width(g_inline_error,LV_PCT(100));
          lv_label_set_long_mode(g_inline_error,LV_LABEL_LONG_WRAP);
          buffer_ui_text_role(g_inline_error,BUFFER_TEXT_LABEL_LARGE);
          buffer_ui_style_text_color(g_inline_error,lv_color_hex(BUFFER_ERROR),0);
          lv_obj_add_event_cb(g_inline_error,ui_inline_error_deleted,LV_EVENT_DELETE,NULL);
        }
      buffer_ui_label_set_text(g_inline_error,text);
      buffer_ui_remove_flag(g_inline_error,LV_OBJ_FLAG_HIDDEN);
    }
}
static void ui_close_event(lv_event_t *event)
{
  (void)event;
  struct buffer_ui_surface_info info;
  if(g_page_host&&buffer_ui_shell_info("assistant",&info))(void)buffer_ui_shell_back();
  else{
    if(g_page_host){g_page_browsing=true;g_open_snapshot_ready=false;}
    else buffer_a2ui_model_close(g_rendered_surface_id);
    buffer_ui_add_flag(g_overlay,LV_OBJ_FLAG_HIDDEN);
  }
  buffer_ui_add_flag(g_inline_host, LV_OBJ_FLAG_HIDDEN);
  buffer_ui_motion_cancel(g_render_parent);
  if(g_page_host)lv_obj_send_event(g_page_host,LV_EVENT_VALUE_CHANGED,NULL);
}

struct ui_action_job_s
{
  enum ui_action_kind_e action;
  char surface_id[BUFFER_A2UI_SURFACE_ID_SIZE];
  uint64_t revision;
  struct buffer_a2ui_component_s component;
};
static pthread_mutex_t g_action_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_action_surface[BUFFER_A2UI_SURFACE_ID_SIZE], g_action_message[128];
static int g_action_error;
static void ui_worker_failure(const struct ui_action_job_s *job, const char *message, int error)
{
  pthread_mutex_lock(&g_action_lock);
  snprintf(g_action_surface, sizeof(g_action_surface), "%s", job->surface_id);
  snprintf(g_action_message, sizeof(g_action_message), "%s", message);
  g_action_error = error;
  pthread_mutex_unlock(&g_action_lock);
}
static struct buffer_a2ui_surface_s g_action_validation;
static int ui_action_fence(const struct ui_action_job_s *job)
{
 if(buffer_a2ui_model_get_surface(job->surface_id,&g_action_validation)||
    g_action_validation.task_revision!=job->revision)return -ESTALE;
 const struct buffer_a2ui_component_s *current=NULL;
 for(unsigned i=0;i<g_action_validation.component_count;i++)if(!strcmp(g_action_validation.components[i].id,job->component.id)){current=&g_action_validation.components[i];break;}
 if(!current||current->type!=job->component.type)return -ESTALE;
 if(job->action==UI_ACTION_GENERIC)return strcmp(current->action,job->component.action)||strcmp(current->context_json,job->component.context_json)?-ESTALE:0;
 if(current->type==BUFFER_A2UI_RECIPE_STEP){
  if(strcmp(current->payload.recipe_step.request_id,job->component.payload.recipe_step.request_id)||
     current->payload.recipe_step.timer_ms!=job->component.payload.recipe_step.timer_ms)return -ESTALE;
  if(job->action==UI_ACTION_RECIPE_START&&current->payload.recipe_step.timer_id[0])return 1;
  return strcmp(current->payload.recipe_step.timer_id,job->component.payload.recipe_step.timer_id)?-ESTALE:0;
 }
 if(current->type==BUFFER_A2UI_TIMER_CARD)return strcmp(current->payload.timer.id,job->component.payload.timer.id)?-ESTALE:0;
 if(current->type==BUFFER_A2UI_ALARM_CARD)return strcmp(current->payload.alarm.id,job->component.payload.alarm.id)?-ESTALE:0;
 return -EINVAL;
}
static void ui_action_run(void *context)
{
  const struct ui_action_job_s *job = context;
  int fence=ui_action_fence(job);
  if(fence<0){ui_worker_failure(job,"内容已更新，请重新操作",fence);return;}
  if(fence>0)return;
  enum ui_action_kind_e action = job->action;
  const struct buffer_a2ui_component_s *component =
      &job->component;
  if (action == UI_ACTION_GENERIC)
    {
      int ret = buffer_a2ui_model_emit_action(job->surface_id, component->id,
                                          component->action, component->context_json);
      if (ret < 0 && ret != -EALREADY) ui_worker_failure(job, "操作未提交，请重试", ret);
      return;
    }

  struct buffer_assistant_result_s result;
  memset(&g_ui_request, 0, sizeof(g_ui_request));
  if (action == UI_ACTION_RECIPE_START)
    {
      if (component->type != BUFFER_A2UI_RECIPE_STEP ||
          component->payload.recipe_step.timer_ms == 0 ||
          component->payload.recipe_step.timer_id[0] != 0) return;
      g_ui_request.operation = BUFFER_ASSISTANT_TIMER_CREATE;
      snprintf(g_ui_request.operation_id, sizeof(g_ui_request.operation_id), "%s",
               component->payload.recipe_step.request_id);
      snprintf(g_ui_request.label, sizeof(g_ui_request.label), "%s",
               component->payload.recipe_step.title);
      g_ui_request.duration_ms = component->payload.recipe_step.timer_ms;
      int ret = buffer_assistant_execute(&g_ui_request, &result);
      if (ret == 0 && result.object_id[0] != 0)
        {
          if (buffer_a2ui_model_bind_timer(job->surface_id,
                                           component->id,
                                           result.object_id,job->revision,
                                           component->payload.recipe_step.request_id) == 0)
            (void)buffer_a2ui_model_activate(job->surface_id);
          else ui_worker_failure(job,"计时已开始，菜谱已更新，请在通知中查看",-ESTALE);
        }
      if (ret < 0) ui_worker_failure(job, "计时创建失败，请重试", ret);
      return;
    }

  enum buffer_assistant_event_e event_type;
  if (action == UI_ACTION_TIMER_TOGGLE || action == UI_ACTION_TIMER_CANCEL)
    {
      if (component->type == BUFFER_A2UI_TIMER_CARD)
        snprintf(g_ui_request.object_id, sizeof(g_ui_request.object_id), "%s",
                 component->payload.timer.id);
      else if (component->type == BUFFER_A2UI_RECIPE_STEP)
        snprintf(g_ui_request.object_id, sizeof(g_ui_request.object_id), "%s",
                 component->payload.recipe_step.timer_id);
      else return;
      event_type = BUFFER_ASSISTANT_EVENT_TIMER;
    }
  else if (action == UI_ACTION_ALARM_CANCEL &&
           component->type == BUFFER_A2UI_ALARM_CARD)
    {
      snprintf(g_ui_request.object_id, sizeof(g_ui_request.object_id), "%s",
               component->payload.alarm.id);
      event_type = BUFFER_ASSISTANT_EVENT_ALARM;
    }
  else return;

  struct buffer_assistant_item_s item;
  if (buffer_assistant_get(event_type, g_ui_request.object_id, &item) < 0) return;
  if (action == UI_ACTION_TIMER_TOGGLE)
    {
      if (item.state == BUFFER_ASSISTANT_RUNNING)
        g_ui_request.operation = BUFFER_ASSISTANT_TIMER_PAUSE;
      else if (item.state == BUFFER_ASSISTANT_PAUSED)
        g_ui_request.operation = BUFFER_ASSISTANT_TIMER_RESUME;
      else return;
    }
  else if (action == UI_ACTION_TIMER_CANCEL)
    g_ui_request.operation = BUFFER_ASSISTANT_TIMER_CANCEL;
  else
    g_ui_request.operation = BUFFER_ASSISTANT_ALARM_CANCEL;

  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  g_ui_operation_sequence++;
  snprintf(g_ui_request.operation_id, sizeof(g_ui_request.operation_id),
           "ui-%08lx-%lld-%u",
           (unsigned long)getpid(),
           (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000,
           (unsigned)g_ui_operation_sequence);
  int ret = buffer_assistant_execute(&g_ui_request, &result);
  if(ret==0&&component->type==BUFFER_A2UI_RECIPE_STEP&&ui_action_fence(job)==0)
    (void)buffer_a2ui_model_activate(job->surface_id);
  if (ret < 0) ui_worker_failure(job, "操作失败，任务已保留，请重试", ret);
}

static void ui_action_event(lv_event_t *event)
{
  if(lv_obj_has_state(lv_event_get_current_target(event),LV_STATE_DISABLED))return;
  if (g_feedback) buffer_ui_add_flag(g_feedback,LV_OBJ_FLAG_HIDDEN);
  if (g_inline_error) buffer_ui_add_flag(g_inline_error,LV_OBJ_FLAG_HIDDEN);
  uintptr_t encoded = (uintptr_t)lv_event_get_user_data(event);
  enum ui_action_kind_e action = (enum ui_action_kind_e)(encoded & 7u);
  size_t index = encoded >> 3;
  if (action == UI_ACTION_CLOSE)
    {
      ui_close_event(event);
      return;
    }
  if (index >= g_surface_snapshot.component_count) return;

  struct ui_action_job_s *job = calloc(1, sizeof(*job));
  if (!job) { ui_failure("内存不足，请稍后重试", -ENOMEM); return; }
  job->action = action;job->revision=g_surface_snapshot.task_revision;
  job->component = g_surface_snapshot.components[index];
  snprintf(job->surface_id, sizeof(job->surface_id), "%s", g_surface_snapshot.id);
  int ret = buffer_ui_work_submit(ui_action_run, job);
  if (ret < 0) { free(job); ui_failure("操作繁忙，请稍后重试", ret); }
}

static void ui_assistant_event(enum buffer_assistant_event_e event,
                               const char *object_id, const char *label,
                               void *context)
{
  (void)label;
  (void)context;
  /* Domain callback may be on the clock worker. The UI polls a notice;
   * never activate another surface over a held recorder/read operation. */
  (void)event;
  (void)object_id;
}

static lv_obj_t *ui_panel_create(lv_obj_t *parent)
{
  lv_obj_t *panel = lv_obj_create(parent);
  lv_obj_remove_style_all(panel);
  lv_obj_set_size(panel, LV_PCT(100), LV_SIZE_CONTENT);
  buffer_ui_style_bg_color(panel, lv_color_hex(BUFFER_PANEL), 0);
  buffer_ui_style_bg_opa(panel, LV_OPA_COVER, 0);
  lv_obj_set_style_border_width(panel, 0, 0);
  lv_obj_set_style_radius(panel, BUFFER_CARD_RADIUS, 0);
  lv_obj_set_style_pad_all(panel, g_depth > 2 ? buffer_ui_dp(4) : BUFFER_CARD_PADDING, 0);
  if (g_depth > 2 || g_generic_reply) buffer_ui_style_bg_opa(panel, LV_OPA_TRANSP, 0);
  lv_obj_set_style_pad_row(panel, BUFFER_SECTION_GAP, 0);
  lv_obj_set_flex_flow(panel, LV_FLEX_FLOW_COLUMN);
  buffer_ui_remove_flag(panel, LV_OBJ_FLAG_SCROLLABLE);
  return panel;
}

static lv_obj_t *ui_text_create(lv_obj_t *parent, const char *text,
                                bool prominent, bool in_row)
{
  lv_obj_t *label = lv_label_create(parent);
  lv_obj_set_width(label, LV_PCT(100));
  (void)in_row;
  lv_obj_set_style_max_width(label, LV_PCT(100), 0);
  lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_LEFT, 0);
  lv_obj_set_style_text_line_space(label, buffer_ui_dp(6), 0);
  buffer_ui_type(label, prominent ? 2 : 0);
  lv_label_set_long_mode(label, LV_LABEL_LONG_WRAP);
  buffer_ui_label_set_text(label, text != NULL ? text : "");
  if (prominent)
    {
      buffer_ui_style_text_color(label, lv_color_hex(BUFFER_ACCENT), 0);
    }
  if(g_generic_reply)buffer_ui_style_text_color(label,lv_color_hex(BUFFER_ON_PRIMARY),0);
  return label;
}

static void ui_button_style(lv_obj_t *button)
{
  buffer_ui_button_style(button, false);
}

static lv_obj_t *ui_button_create(lv_obj_t *parent, const char *text,
                                  enum ui_action_kind_e action,
                                  size_t component_index)
{
  lv_obj_t *button = lv_button_create(parent);
  lv_obj_set_size(button, LV_PCT(100), buffer_ui_dp(48));
  lv_obj_set_style_min_width(button, buffer_ui_dp(48), 0);
  ui_button_style(button);
  lv_obj_set_style_pad_hor(button, buffer_ui_dp(12), 0);
  lv_obj_t *label = lv_label_create(button);
  buffer_ui_button_label(label);
  lv_obj_set_width(label,LV_PCT(100));
  lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);
  lv_label_set_long_mode(label, LV_LABEL_LONG_DOT);
  buffer_ui_label_set_text(label, text);
  lv_obj_center(label);
  uintptr_t data = ((uintptr_t)component_index << 3) | (uintptr_t)action;
  lv_obj_add_event_cb(button, ui_action_event, LV_EVENT_CLICKED, (void *)data);
  return button;
}

static void ui_dynamic_add(lv_obj_t *label,
                           enum buffer_assistant_event_e type,
                           const char *object_id)
{
  size_t slot;
  for (slot = 0; slot < g_dynamic_label_count; slot++)
    if (g_dynamic_labels[slot].label == NULL) break;
  if (slot >= A2UI_DYNAMIC_LABELS) return;
  if (slot == g_dynamic_label_count) g_dynamic_label_count++;
  struct dynamic_label_s *entry = &g_dynamic_labels[slot];
  memset(entry, 0, sizeof(*entry));
  entry->label = label;
  entry->type = type;
  snprintf(entry->object_id, sizeof(entry->object_id), "%s", object_id);
}

static void ui_timer_controls(const struct buffer_a2ui_surface_s *surface,
                              uint8_t index, lv_obj_t *parent,
                              const char *timer_id, const char *label,bool recipe)
{
  lv_obj_t *panel = ui_panel_create(parent);
  ui_text_create(panel, label, true, false);
  lv_obj_t *remaining = ui_text_create(panel, "读取计时状态…", true, false);
  ui_dynamic_add(remaining, BUFFER_ASSISTANT_EVENT_TIMER, timer_id);
  lv_obj_t *actions = lv_obj_create(panel);
  lv_obj_remove_style_all(actions);
  lv_obj_set_size(actions, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(actions, LV_FLEX_FLOW_ROW_WRAP);
  lv_obj_set_style_pad_column(actions, buffer_ui_dp(8), 0);
  lv_obj_set_style_pad_row(actions, buffer_ui_dp(6), 0);
  buffer_ui_remove_flag(actions, LV_OBJ_FLAG_SCROLLABLE);
  struct dynamic_label_s *dynamic = &g_dynamic_labels[g_dynamic_label_count - 1];
  /* dynamic_add can reuse an earlier free slot. */
  for (size_t i = 0; i < g_dynamic_label_count; i++)
    if (g_dynamic_labels[i].label == remaining) dynamic = &g_dynamic_labels[i];
  dynamic->toggle = ui_button_create(actions, "暂停", UI_ACTION_TIMER_TOGGLE, index);
  dynamic->cancel = ui_button_create(actions, "结束计时", UI_ACTION_TIMER_CANCEL, index);
  if(g_page_host){
    buffer_ui_text_role(remaining,BUFFER_TEXT_DISPLAY_MEDIUM);lv_obj_set_style_pad_all(panel,buffer_ui_dp(10),0);
    buffer_ui_icon_button(dynamic->toggle,&buffer_pause_icon);lv_obj_set_size(dynamic->toggle,buffer_ui_dp(48),buffer_ui_dp(48));
    if(recipe){buffer_ui_style_bg_color(panel,lv_color_white(),0);buffer_ui_add_flag(dynamic->cancel,LV_OBJ_FLAG_HIDDEN);buffer_ui_style_text_color(remaining,lv_color_hex(BUFFER_ON_SURFACE),0);}
  }
  (void)surface;
}

static void ui_timer_preview_open(lv_event_t *event)
{(void)event;g_timer_expanded=!g_timer_expanded;g_presentation_dirty=true;(void)buffer_a2ui_ui_open_surface(g_rendered_surface_id);}
static void ui_render_timer(const struct buffer_a2ui_component_s *component,
                            uint8_t index, lv_obj_t *parent)
{
  uint32_t before=lv_obj_get_child_count(parent);
  ui_timer_controls(&g_surface_snapshot, index, parent,
                   component->payload.timer.id,
                   component->payload.timer.label,false);
  if(g_page_host){
    lv_obj_t *panel=lv_obj_get_child(parent,before);int width=buffer_ui_dp(144)*(int)buffer_ui_font_scale()/100;
    int available=lv_obj_get_content_width(g_overlay);if(width>available)width=available;
    lv_obj_set_width(panel,width);lv_obj_set_style_pad_all(panel,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(panel,0,0);
    buffer_ui_style_bg_color(panel,lv_color_hex(BUFFER_ACCENT),0);lv_obj_set_style_radius(panel,buffer_ui_dp(20),0);
    for(unsigned i=0;i<2;i++)buffer_ui_style_text_color(lv_obj_get_child(panel,i),lv_color_white(),0);
    buffer_ui_label_set_text(lv_obj_get_child(panel,0),"计时器");buffer_ui_text_role(lv_obj_get_child(panel,0),BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
    buffer_ui_text_role(lv_obj_get_child(panel,1),BUFFER_TEXT_DISPLAY_MEDIUM);
    buffer_ui_bubble_corner(panel,BUFFER_BUBBLE_CONTINUATION);
    for(size_t i=0;i<g_dynamic_label_count;i++)if(g_dynamic_labels[i].label==lv_obj_get_child(panel,1))g_dynamic_labels[i].inverse=true;
    lv_obj_add_event_cb(panel,ui_timer_preview_open,LV_EVENT_CLICKED,NULL);
    if(!g_timer_expanded)buffer_ui_add_flag(lv_obj_get_child(panel,2),LV_OBJ_FLAG_HIDDEN);
  }
}

static void ui_render_alarm(const struct buffer_a2ui_component_s *component,
                            uint8_t index, lv_obj_t *parent)
{
  lv_obj_t *panel = ui_panel_create(parent);
  ui_text_create(panel, component->payload.alarm.label, true, false);
  lv_obj_t *when = ui_text_create(panel, "读取闹钟状态…", false, false);
  ui_dynamic_add(when, BUFFER_ASSISTANT_EVENT_ALARM,
                 component->payload.alarm.id);
  lv_obj_t *cancel = ui_button_create(panel, "取消闹钟", UI_ACTION_ALARM_CANCEL, index);
  for (size_t i = 0; i < g_dynamic_label_count; i++)
    if (g_dynamic_labels[i].label == when) g_dynamic_labels[i].cancel = cancel;
}

static void ui_render_translation(
    const struct buffer_a2ui_component_s *component, lv_obj_t *parent)
{
  lv_obj_t *panel = ui_panel_create(parent);
  char line[72];
  snprintf(line, sizeof(line), "%s · %s",
           component->payload.translation.source_language,
           component->payload.translation.target_language);
  ui_text_create(panel, line, false, false);
  ui_text_create(panel, "原文", false, false);
  ui_text_create(panel, component->payload.translation.original, false, false);
  ui_text_create(panel, "译文", false, false);
  ui_text_create(panel, component->payload.translation.translated, true, false);
}

static void ui_render_recipe_step(
    const struct buffer_a2ui_component_s *component, uint8_t index,
    lv_obj_t *parent)
{
  lv_obj_t *panel = ui_panel_create(parent);
  ui_text_create(panel, component->payload.recipe_step.title, true, false);
  ui_text_create(panel, component->payload.recipe_step.detail, false, false);
  if (component->payload.recipe_step.timer_id[0] != 0)
    {
      ui_timer_controls(&g_surface_snapshot, index, panel,
          component->payload.recipe_step.timer_id,
          component->payload.recipe_step.title,true);
    }
  else if (component->payload.recipe_step.timer_ms > 0)
    {
      ui_button_create(panel, "开始本步计时",
                       UI_ACTION_RECIPE_START, index);
    }
}

static void render_node(const struct buffer_a2ui_surface_s *surface,
                        uint8_t component_index, lv_obj_t *parent,
                        bool parent_is_row)
{
  if (component_index >= surface->component_count) return;
  const struct buffer_a2ui_component_s *component =
      &surface->components[component_index];
  lv_obj_t *reuse = NULL;
  int previous_index = -1;
  for (unsigned i = 0; g_reconcile && i < g_previous.component_count; i++)
    if (g_old_nodes[i] && strcmp(g_previous.components[i].id, component->id) == 0 &&
        g_previous.components[i].type == component->type)
      { reuse = g_old_nodes[i]; previous_index = i; break; }
  bool is_container = component->type == BUFFER_A2UI_COLUMN ||
                   component->type == BUFFER_A2UI_ROW || component->type == BUFFER_A2UI_CARD;
  if (reuse && !is_container && component->type != BUFFER_A2UI_TEXT &&
      component->type != BUFFER_A2UI_BUTTON && component->type != BUFFER_A2UI_DIVIDER &&
      (previous_index != component_index || memcmp(&g_previous.components[previous_index].payload,
                                                  &component->payload, sizeof(component->payload)) != 0))
    {
      lv_obj_delete(reuse);
      reuse = NULL;
    }
  if (previous_index >= 0) g_old_nodes[previous_index] = NULL;
  if (reuse)
    {
      lv_obj_set_parent(reuse, parent);
      lv_obj_move_to_index(reuse, -1);
      g_nodes[component_index] = reuse;
      if (is_container)
        {
          g_depth++;
          for (unsigned i = 0; i < component->child_count; i++)
            render_node(surface, component->children[i], reuse, component->type == BUFFER_A2UI_ROW);
          g_depth--;
        }
      else if (component->type == BUFFER_A2UI_TEXT)
        {
          const char *text = buffer_a2ui_component_text(surface, component);
          if (strcmp(lv_label_get_text(reuse), text) != 0) buffer_ui_label_set_text(reuse, text);
          buffer_ui_type(reuse, component->variant);
        }
      else if (component->type == BUFFER_A2UI_BUTTON)
        {
          buffer_ui_label_set_text(lv_obj_get_child(reuse, 0), buffer_a2ui_component_text(surface, component));
          lv_obj_remove_event_cb(reuse, ui_action_event);
          uintptr_t data = ((uintptr_t)component_index << 3) | UI_ACTION_GENERIC;
          lv_obj_add_event_cb(reuse, ui_action_event, LV_EVENT_CLICKED, (void *)data);
        }
      return;
    }
  uint32_t before = lv_obj_get_child_count(parent);
  switch (component->type)
    {
      case BUFFER_A2UI_COLUMN:
      case BUFFER_A2UI_ROW:
      case BUFFER_A2UI_CARD:
        {
          lv_obj_t *container = component->type == BUFFER_A2UI_CARD ?
                                ui_panel_create(parent) : lv_obj_create(parent);
          if (component->type != BUFFER_A2UI_CARD)
            {
              lv_obj_remove_style_all(container);
              lv_obj_set_size(container, LV_PCT(100), LV_SIZE_CONTENT);
              lv_obj_set_flex_flow(container,
                  component->type == BUFFER_A2UI_ROW ? LV_FLEX_FLOW_ROW_WRAP :
                                                       LV_FLEX_FLOW_COLUMN);
              lv_obj_set_style_pad_column(container, BUFFER_SECTION_GAP, 0);
              lv_obj_set_style_pad_row(container, BUFFER_SECTION_GAP, 0);
            }
          buffer_ui_remove_flag(container, LV_OBJ_FLAG_SCROLLABLE);
          g_depth++;
          for (size_t i = 0; i < component->child_count; i++)
            render_node(surface, component->children[i], container,
                        component->type == BUFFER_A2UI_ROW);
          g_depth--;
        }
        break;
      case BUFFER_A2UI_TEXT:{
        lv_obj_t *text=ui_text_create(parent,buffer_a2ui_component_text(surface,component),component->variant!=0&&component->variant!=4,parent_is_row);
        buffer_ui_type(text,component->variant);
        if(g_generic_reply)buffer_ui_style_text_color(text,lv_color_hex(BUFFER_ON_PRIMARY),0);
        break;
      }
      case BUFFER_A2UI_BUTTON:
        ui_button_create(parent,
                         buffer_a2ui_component_text(surface, component),
                         UI_ACTION_GENERIC, component_index);
        break;
      case BUFFER_A2UI_DIVIDER:
        {
          lv_obj_t *divider = lv_obj_create(parent);
          lv_obj_remove_style_all(divider);
          lv_obj_set_size(divider, LV_PCT(100), buffer_ui_dp(1));
          buffer_ui_style_bg_color(divider, lv_color_hex(BUFFER_MUTED), 0);
          buffer_ui_style_bg_opa(divider, LV_OPA_30, 0);
        }
        break;
      case BUFFER_A2UI_TIMER_CARD:
        ui_render_timer(component, component_index, parent);
        break;
      case BUFFER_A2UI_ALARM_CARD:
        ui_render_alarm(component, component_index, parent);
        break;
      case BUFFER_A2UI_TRANSLATION_RESULT:
        ui_render_translation(component, parent);
        break;
      case BUFFER_A2UI_RECIPE_STEP:
        ui_render_recipe_step(component, component_index, parent);
        break;
      default:
        break;
    }
  if (lv_obj_get_child_count(parent) > before)
    g_nodes[component_index] = lv_obj_get_child(parent, before);
}

static void ui_node_deleted(lv_event_t *event)
{
  lv_obj_t *obj = lv_event_get_target(event);
  if(g_recipe_preview_image==obj)g_recipe_preview_image=NULL;
  if(g_recipe_preview_frame==obj)g_recipe_preview_frame=NULL;
  if(g_recipe_preview_panel==obj)g_recipe_preview_panel=NULL;
  if(g_session_response==obj)g_session_response=NULL;
  for (unsigned i = 0; i < BUFFER_A2UI_COMPONENT_COUNT; i++)
    {
      if (g_nodes[i] == obj) g_nodes[i] = NULL;
      if (g_old_nodes[i] == obj) g_old_nodes[i] = NULL;
      if (g_dynamic_labels[i].label == obj) memset(&g_dynamic_labels[i], 0, sizeof(g_dynamic_labels[i]));
    }
}
static void watch_tree(lv_obj_t *obj)
{
  lv_obj_remove_event_cb(obj, ui_node_deleted);
  lv_obj_add_event_cb(obj, ui_node_deleted, LV_EVENT_DELETE, NULL);
  for (unsigned i = 0; i < lv_obj_get_child_count(obj); i++) watch_tree(lv_obj_get_child(obj, i));
}
#include "buffer_ui_artwork.h"
#include "buffer_a2ui_recipe.inc"
struct recipe_recovery_slot {char surface[64],operation[64];bool pending,complete;unsigned generation;int result;uint32_t retry_at;};
struct recipe_recovery_job {struct buffer_ui_recipe_progress progress;unsigned slot,generation,epoch;};
static struct recipe_recovery_slot g_recipe_recovery[BUFFER_RECIPE_SAVED];
static pthread_mutex_t g_recipe_recovery_lock=PTHREAD_MUTEX_INITIALIZER;
static unsigned g_recipe_recovery_epoch;
static bool g_recipe_recovery_failed;
static void ui_recipe_recovery_run(void *context)
{
 const struct recipe_recovery_job *job=context;
 pthread_mutex_lock(&g_recipe_recovery_lock);bool current=job->epoch==g_recipe_recovery_epoch&&job->generation==g_recipe_recovery[job->slot].generation;pthread_mutex_unlock(&g_recipe_recovery_lock);
 int result=-ECANCELED;char image_id[65]="";
 if(current)result=buffer_assistant_restore_recipe(job->progress.operation_id,job->progress.surface,image_id);
#ifndef BUFFER_UI_TEST_BUILD
 if(!result&&image_id[0]&&job->progress.capture_id[0])(void)buffer_images_request(job->progress.capture_id,image_id);
#endif
 pthread_mutex_lock(&g_recipe_recovery_lock);
 if(job->epoch==g_recipe_recovery_epoch&&job->generation==g_recipe_recovery[job->slot].generation){g_recipe_recovery[job->slot].pending=false;g_recipe_recovery[job->slot].result=result;g_recipe_recovery[job->slot].complete=result!=-EAGAIN;if(result!=-EAGAIN)g_recipe_recovery[job->slot].retry_at=0;}
 pthread_mutex_unlock(&g_recipe_recovery_lock);
}
static void ui_recipe_recovery_refresh(void)
{
 bool failed=false;
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++){
  struct buffer_ui_recipe_progress progress;
  int saved=buffer_ui_recipe_state_slot(i,&progress);if(saved||!progress.operation_id[0])continue;
  uint64_t revision;int exists=buffer_a2ui_model_surface_revision(progress.surface,&revision);
  if(exists==-EAGAIN)continue;
  if(pthread_mutex_trylock(&g_recipe_recovery_lock))continue;
  struct recipe_recovery_slot *slot=&g_recipe_recovery[i];
  if(strcmp(slot->surface,progress.surface)||strcmp(slot->operation,progress.operation_id)){
    unsigned generation=slot->generation+1;memset(slot,0,sizeof(*slot));slot->generation=generation;strcpy(slot->surface,progress.surface);strcpy(slot->operation,progress.operation_id);
  }
  if(!exists){slot->complete=true;slot->result=0;}
  if(slot->pending||slot->complete){if(slot->complete&&slot->result&&slot->result!=-ENOTSUP)failed=true;pthread_mutex_unlock(&g_recipe_recovery_lock);continue;}
  if(slot->retry_at&&(int32_t)(lv_tick_get()-slot->retry_at)<0){pthread_mutex_unlock(&g_recipe_recovery_lock);continue;}
  struct recipe_recovery_job *job=calloc(1,sizeof(*job));
  if(!job){pthread_mutex_unlock(&g_recipe_recovery_lock);continue;}
  *job=(struct recipe_recovery_job){.progress=progress,.slot=i,.generation=slot->generation,.epoch=g_recipe_recovery_epoch};slot->pending=true;slot->retry_at=lv_tick_get()+1000;
  pthread_mutex_unlock(&g_recipe_recovery_lock);
  int submitted=buffer_ui_work_submit(ui_recipe_recovery_run,job);
  if(submitted){free(job);pthread_mutex_lock(&g_recipe_recovery_lock);slot->pending=false;slot->result=submitted;slot->retry_at=lv_tick_get()+1000;pthread_mutex_unlock(&g_recipe_recovery_lock);}
 }
 if(failed&&!g_recipe_recovery_failed&&!g_page_browsing)ui_failure("部分菜谱暂未恢复，可重试",-EIO);
 g_recipe_recovery_failed=failed;
}
static void ui_recipe_save_retry(lv_event_t *event)
{
 (void)event;if(g_recipe.session)ui_recipe_save(g_recipe.session);buffer_ui_recipe_state_retry();
 pthread_mutex_lock(&g_recipe_recovery_lock);for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(!g_recipe_recovery[i].pending){g_recipe_recovery[i].complete=false;g_recipe_recovery[i].retry_at=0;}pthread_mutex_unlock(&g_recipe_recovery_lock);g_recipe_recovery_failed=false;
}

static bool ui_input_busy(void)
{
  for (lv_indev_t *input = lv_indev_get_next(NULL); input; input = lv_indev_get_next(input))
    if (lv_indev_get_state(input) == LV_INDEV_STATE_PRESSED) return true;
  return (g_recipe.root&&(lv_obj_is_scrolling(g_recipe.reader)||lv_obj_is_scrolling(g_recipe.meta)||lv_obj_is_scrolling(g_recipe.footer))) ||
         lv_obj_is_scrolling(g_overlay_content) ||
         lv_obj_is_scrolling(lv_obj_get_parent(g_inline_host));
}
static void ui_accept_result(lv_event_t *event)
{
  (void)event;
  if(g_pending_id[0]){
    int result=buffer_a2ui_ui_open_surface(g_pending_id);
    if(result)ui_failure("新结果暂时无法打开，请重试",result);
  }
}

static struct {char id[BUFFER_A2UI_SURFACE_ID_SIZE];uint64_t sequence;} g_seen_results[BUFFER_A2UI_SURFACE_COUNT];
static unsigned g_seen_next;
bool buffer_a2ui_ui_result_unread(const char *id,uint64_t sequence)
{
 for(unsigned i=0;i<BUFFER_A2UI_SURFACE_COUNT;i++)if(!strcmp(g_seen_results[i].id,id))return g_seen_results[i].sequence!=sequence;
 return true;
}
static void ui_result_seen(void)
{
 unsigned slot=BUFFER_A2UI_SURFACE_COUNT;
 for(unsigned i=0;i<BUFFER_A2UI_SURFACE_COUNT;i++)if(!strcmp(g_seen_results[i].id,g_surface_snapshot.id)){slot=i;break;}
 if(slot==BUFFER_A2UI_SURFACE_COUNT)slot=g_seen_next++%BUFFER_A2UI_SURFACE_COUNT;
 snprintf(g_seen_results[slot].id,sizeof(g_seen_results[slot].id),"%s",g_surface_snapshot.id);g_seen_results[slot].sequence=g_surface_snapshot.sequence;
}
static void ui_session_bubble(const char *text,bool request)
{
 lv_obj_t *row=lv_obj_create(g_render_parent);lv_obj_remove_style_all(row);lv_obj_set_size(row,LV_PCT(100),LV_SIZE_CONTENT);
 lv_obj_set_flex_flow(row,LV_FLEX_FLOW_ROW);lv_obj_set_flex_align(row,request?LV_FLEX_ALIGN_END:LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_START);buffer_ui_remove_flag(row,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_t *bubble=lv_obj_create(row);lv_obj_remove_style_all(bubble);lv_obj_set_size(bubble,LV_SIZE_CONTENT,LV_SIZE_CONTENT);lv_obj_set_style_max_width(bubble,LV_PCT(100),0);
 lv_obj_set_style_pad_all(bubble,buffer_ui_dp(10),0);lv_obj_set_style_radius(bubble,buffer_ui_dp(20),0);
 buffer_ui_bubble_corner(bubble,request?BUFFER_BUBBLE_REQUEST:BUFFER_BUBBLE_REPLY);
 buffer_ui_style_bg_color(bubble,lv_color_hex(request?BUFFER_PRIMARY_CONTAINER:BUFFER_ACCENT),0);buffer_ui_style_bg_opa(bubble,LV_OPA_COVER,0);buffer_ui_remove_flag(bubble,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_t *label=lv_label_create(bubble);buffer_ui_text_role(label,BUFFER_TEXT_HEADLINE_SMALL);buffer_ui_label_set_text(label,text);
 int available=lv_obj_get_content_width(g_overlay)-buffer_ui_dp(20);if(available<1)available=1;
 lv_obj_set_style_max_width(label,available,0);lv_label_set_long_mode(label,LV_LABEL_LONG_WRAP);
 buffer_ui_style_text_color(label,lv_color_hex(request?BUFFER_ON_SURFACE:BUFFER_ON_PRIMARY),0);
}
static void ui_recipe_preview_open(lv_event_t *event)
{(void)event;g_recipe_expanded=g_recipe_return_preview=true;g_presentation_dirty=true;(void)buffer_a2ui_ui_open_surface(g_rendered_surface_id);}
static bool ui_recipe_preview(unsigned root)
{
 g_recipe_scan_count=0;g_recipe_has_title=false;g_recipe_materials[0]=g_recipe_title[0]=0;
 if(!ui_recipe_collect(root,0)||!g_recipe_scan_count)return false;
 lv_obj_t *panel=g_recipe_preview_panel;
 if(!panel){
  panel=ui_panel_create(g_render_parent);g_recipe_preview_panel=panel;lv_obj_set_width(panel,buffer_ui_dp(206));
  lv_obj_set_style_pad_all(panel,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(panel,0,0);lv_obj_set_style_radius(panel,buffer_ui_dp(20),0);
  buffer_ui_style_bg_color(panel,lv_color_hex(BUFFER_ACCENT),0);buffer_ui_bubble_corner(panel,BUFFER_BUBBLE_CONTINUATION);lv_obj_add_event_cb(panel,ui_recipe_preview_open,LV_EVENT_CLICKED,NULL);
  lv_obj_t *kind=ui_text_create(panel,"菜谱",false,false);buffer_ui_text_role(kind,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);buffer_ui_style_text_color(kind,lv_color_white(),0);
  lv_obj_t *title=ui_text_create(panel,"",true,false);buffer_ui_text_role(title,BUFFER_TEXT_HEADLINE_SMALL);buffer_ui_style_text_color(title,lv_color_white(),0);
 }
 buffer_ui_label_set_text(lv_obj_get_child(panel,1),g_recipe_has_title?g_recipe_title:"烹饪步骤");
 if(!g_recipe_preview_image){
  g_recipe_preview_image=buffer_ui_recipe_image_create(panel,BUFFER_INNER_RADIUS,&g_recipe_preview_frame);lv_obj_set_size(g_recipe_preview_frame,LV_PCT(100),buffer_ui_dp(151));
 }
 if(buffer_ui_recipe_image_bind(g_recipe_preview_image,g_surface_snapshot.image_id)){
  lv_image_set_inner_align(g_recipe_preview_image,LV_IMAGE_ALIGN_STRETCH);buffer_ui_remove_flag(g_recipe_preview_frame,LV_OBJ_FLAG_HIDDEN);
 }else buffer_ui_add_flag(g_recipe_preview_frame,LV_OBJ_FLAG_HIDDEN);
 return true;
}
static void ui_render_surface(void)
{
  if (g_surface_snapshot.full_screen)
    {
      if (!g_reconcile) lv_obj_clean(g_overlay_content);
      g_render_parent = g_overlay_content;
      if(!g_page_host)buffer_ui_remove_flag(g_overlay, LV_OBJ_FLAG_HIDDEN);
      buffer_ui_add_flag(g_inline_host, LV_OBJ_FLAG_HIDDEN);
    }
  else
    {
      if (!g_reconcile) lv_obj_clean(g_inline_host);
      g_render_parent = g_inline_host;
      buffer_ui_remove_flag(g_inline_host, LV_OBJ_FLAG_HIDDEN);
      if(!g_page_host)buffer_ui_add_flag(g_overlay, LV_OBJ_FLAG_HIDDEN);
    }
  if (!g_reconcile) g_dynamic_label_count = 0;

  if (!g_reconcile && !g_surface_snapshot.full_screen)
    {
      lv_obj_t *bar = lv_obj_create(g_inline_host);
      lv_obj_remove_style_all(bar);
      lv_obj_set_size(bar, LV_PCT(100), LV_SIZE_CONTENT);
      lv_obj_set_flex_flow(bar, LV_FLEX_FLOW_ROW);
      lv_obj_set_flex_align(bar, LV_FLEX_ALIGN_END, LV_FLEX_ALIGN_CENTER,
                            LV_FLEX_ALIGN_CENTER);
      lv_obj_t *close = lv_button_create(bar);
      lv_obj_set_size(close, buffer_ui_dp(48), buffer_ui_dp(48));
      ui_button_style(close);
      lv_obj_set_style_pad_hor(close, buffer_ui_dp(12), 0);
      lv_obj_add_event_cb(close, ui_close_event, LV_EVENT_CLICKED, NULL);
      lv_obj_t *label = lv_label_create(close);
      buffer_ui_button_label(label);
      buffer_ui_label_set_text(label, "关闭计时卡片");
      lv_obj_center(label);
    }

  int root = -1;
  for (size_t i = 0; i < g_surface_snapshot.component_count; i++)
    if (strcmp(g_surface_snapshot.components[i].id, "root") == 0)
      { root = (int)i; break; }
  bool has_recipe=false,has_timer=false;
  for(unsigned i=0;i<g_surface_snapshot.component_count;i++){if(g_surface_snapshot.components[i].type==BUFFER_A2UI_RECIPE_STEP)has_recipe=true;if(g_surface_snapshot.components[i].type==BUFFER_A2UI_TIMER_CARD)has_timer=true;}
  bool compact_recipe=g_page_host&&has_recipe&&!g_recipe_expanded;
  lv_obj_set_style_pad_all(g_overlay_content,g_page_host&&has_recipe&&!compact_recipe?0:buffer_ui_dp(10),0);
  bool response_group=g_page_host&&(compact_recipe||!has_recipe);
  bool old_recipe=false,old_timer=false;
  for(unsigned i=0;i<g_previous.component_count;i++){old_recipe|=g_previous.components[i].type==BUFFER_A2UI_RECIPE_STEP;old_timer|=g_previous.components[i].type==BUFFER_A2UI_TIMER_CARD;}
  if(g_reconcile&&(response_group!=(g_session_response!=NULL)||has_recipe!=old_recipe||has_timer!=old_timer)){
    lv_obj_clean(g_render_parent);memset(g_old_nodes,0,sizeof(g_old_nodes));memset(g_nodes,0,sizeof(g_nodes));g_dynamic_label_count=0;g_reconcile=false;
  }
  lv_obj_t *conversation=g_render_parent;
  if(g_page_host){
    buffer_ui_add_flag(lv_obj_get_child(g_overlay,0),LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_bg_grad_dir(g_overlay,LV_GRAD_DIR_NONE,0);
    buffer_ui_surface_decor(g_overlay,has_recipe&&!compact_recipe?BUFFER_DECOR_NONE:BUFFER_DECOR_SESSION,buffer_ui_dp(10));
    buffer_ui_shell_capture_mode("assistant",has_recipe&&!compact_recipe?BUFFER_UI_CAPTURE_HIDDEN:BUFFER_UI_CAPTURE_FLOATING);
    if(!g_reconcile&&(!has_recipe||compact_recipe)){
      if(!strcmp(g_prompt_surface,g_surface_snapshot.id)&&g_capture_prompt[0])ui_session_bubble(g_capture_prompt,true);
      if(response_group){
        g_session_response=lv_obj_create(conversation);lv_obj_remove_style_all(g_session_response);lv_obj_set_size(g_session_response,LV_PCT(100),LV_SIZE_CONTENT);
        lv_obj_set_flex_flow(g_session_response,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(g_session_response,buffer_ui_dp(3),0);buffer_ui_remove_flag(g_session_response,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE);
        g_render_parent=g_session_response;
      }
      if(has_recipe)ui_session_bubble("好的，让我们开始制作！",false);
      else if(has_timer){
        struct buffer_assistant_item_s item;int result=-ENOENT;
        for(unsigned i=0;i<g_surface_snapshot.component_count;i++)if(g_surface_snapshot.components[i].type==BUFFER_A2UI_TIMER_CARD){result=buffer_assistant_ui_get(BUFFER_ASSISTANT_EVENT_TIMER,g_surface_snapshot.components[i].payload.timer.id,&item);break;}
        if(!result)ui_session_bubble(item.state==BUFFER_ASSISTANT_RUNNING?"收到，计时器已开始。":item.state==BUFFER_ASSISTANT_PAUSED?"计时器已暂停。":item.state==BUFFER_ASSISTANT_FIRED?"计时器已结束。":"计时器已取消。",false);
      }
    }
  }
  if(response_group&&g_session_response)g_render_parent=g_session_response;
  g_generic_reply=g_page_host&&!has_recipe&&!has_timer;
  if(g_generic_reply&&g_session_response){
    buffer_ui_style_bg_color(g_session_response,lv_color_hex(BUFFER_ACCENT),0);buffer_ui_style_bg_opa(g_session_response,LV_OPA_COVER,0);
    buffer_ui_bubble_corner(g_session_response,BUFFER_BUBBLE_REPLY);
    lv_obj_set_style_radius(g_session_response,buffer_ui_dp(20),0);lv_obj_set_style_pad_all(g_session_response,buffer_ui_dp(10),0);
  }
  bool recipe=root>=0&&(compact_recipe?ui_recipe_preview((unsigned)root):ui_recipe_render((unsigned)root));
  if(!recipe){
    buffer_ui_label_set_text(g_overlay_heading,"助手");
    if(g_recipe.root)lv_obj_delete(g_recipe.root);
    buffer_ui_add_flag(g_render_parent,LV_OBJ_FLAG_SCROLLABLE);
    if(root>=0)render_node(&g_surface_snapshot,(uint8_t)root,g_render_parent,false);
  }

  if(g_generic_reply&&buffer_ui_shell_is_open("assistant"))ui_result_seen();
  g_generic_reply=false;
  g_render_parent=conversation;
  /* Remove only absent stable IDs; content changes retain widgets and scroll. */
  for (unsigned i = 0; i < BUFFER_A2UI_COMPONENT_COUNT; i++)
    if (g_old_nodes[i]) lv_obj_delete(g_old_nodes[i]);
  watch_tree(g_render_parent);
  if (!g_reconcile)
    {
      lv_obj_scroll_to_y(g_render_parent, 0, LV_ANIM_OFF);
      buffer_ui_motion_enter(g_render_parent, 1, g_surface_snapshot.full_screen);
    }
  g_previous = g_surface_snapshot;
  snprintf(g_rendered_surface_id, sizeof(g_rendered_surface_id), "%s",
           g_surface_snapshot.id);
  g_rendered_sequence = g_surface_snapshot.sequence;
  g_rendered_full_screen = g_surface_snapshot.full_screen;
}

static void ui_dynamic_refresh(void)
{
  if(!ui_input_busy()){
    if(g_recipe.root&&ui_recipe_restore(g_recipe.session))ui_recipe_update(false);
    if(g_recipe_preview_image&&lv_obj_has_flag(g_recipe_preview_frame,LV_OBJ_FLAG_HIDDEN)&&buffer_ui_recipe_image_bind(g_recipe_preview_image,g_surface_snapshot.image_id)){
      lv_image_set_inner_align(g_recipe_preview_image,LV_IMAGE_ALIGN_STRETCH);buffer_ui_remove_flag(g_recipe_preview_frame,LV_OBJ_FLAG_HIDDEN);
    }
    if(g_recipe.root&&!buffer_ui_recipe_image_ready(g_recipe.image)&&buffer_ui_recipe_image_bind(g_recipe.image,g_recipe.session->image_id)){
      lv_image_set_inner_align(g_recipe.image,LV_IMAGE_ALIGN_STRETCH);ui_recipe_update(false);
    }
  }
  struct buffer_ui_recipe_store_status saving;
  if(!buffer_ui_recipe_state_status(&saving)){
    bool failed=g_recipe.root&&saving.loaded&&saving.error!=0;bool show_retry=failed||g_recipe_recovery_failed;
    if(failed&&saving.error!=g_recipe_save_error)ui_failure("菜谱进度尚未保存，请重试",saving.error);
    if(g_recipe_save_retry){if(show_retry)buffer_ui_remove_flag(g_recipe_save_retry,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe_save_retry,LV_OBJ_FLAG_HIDDEN);}
    if(!failed&&g_recipe_save_error&&g_feedback&&!strncmp(buffer_ui_label_source_text(g_feedback),"菜谱进度",strlen("菜谱进度")))buffer_ui_add_flag(g_feedback,LV_OBJ_FLAG_HIDDEN);
    g_recipe_save_error=failed?saving.error:0;
  }
  char text[128];
  for (size_t i = 0; i < g_dynamic_label_count; i++)
    {
      struct dynamic_label_s *entry = &g_dynamic_labels[i];
      struct buffer_assistant_item_s item;
      if (entry->label == NULL) continue;
      int snapshot_result = buffer_assistant_ui_get(entry->type, entry->object_id, &item);
      if (snapshot_result == -EAGAIN) continue;
      if (snapshot_result < 0)
        {
          buffer_ui_label_set_text(entry->label, "任务已失效，可关闭卡片");
          if (entry->toggle) lv_obj_add_state(entry->toggle, LV_STATE_DISABLED);
          if (entry->cancel) lv_obj_add_state(entry->cancel, LV_STATE_DISABLED);
          continue;
        }
      if (entry->type == BUFFER_ASSISTANT_EVENT_TIMER)
        {
          if(g_page_host){
            unsigned seconds=(unsigned)((item.remaining_ms+999)/1000);snprintf(text,sizeof(text),"%u:%02u",seconds/60,seconds%60);
            lv_obj_t *panel=lv_obj_get_parent(entry->label),*heading=lv_obj_get_child(panel,0);
            buffer_ui_label_set_text(heading,item.state==BUFFER_ASSISTANT_PAUSED?"计时器 · 已暂停":item.state==BUFFER_ASSISTANT_FIRED?"计时结束":item.state==BUFFER_ASSISTANT_CANCELLED?"计时已取消":"计时器");
          }
          else
          {
          if (item.state == BUFFER_ASSISTANT_RUNNING)
            {
              unsigned seconds = (unsigned)((item.remaining_ms + 999) / 1000);
              if (seconds >= 3600)
                snprintf(text, sizeof(text), "剩余 %02u:%02u:%02u",
                         seconds / 3600, (seconds / 60) % 60, seconds % 60);
              else
                snprintf(text, sizeof(text), "剩余 %02u:%02u",
                         seconds / 60, seconds % 60);
            }
          else if (item.state == BUFFER_ASSISTANT_PAUSED)
            snprintf(text, sizeof(text), "已暂停 · 剩余 %u:%02u",
                     (unsigned)(item.remaining_ms / 60000),
                     (unsigned)((item.remaining_ms / 1000) % 60));
          else if (item.state == BUFFER_ASSISTANT_FIRED)
            snprintf(text, sizeof(text), "计时结束 · 提醒仍有效");
          else
            snprintf(text, sizeof(text), "已取消");
          }
        }
      else
        {
          struct tm local;
          time_t seconds = (time_t)(item.scheduled_at_unix_ms / 1000);
          char date[48] = "时间无效";
          if (localtime_r(&seconds, &local) != NULL)
            strftime(date, sizeof(date), "%Y-%m-%d %H:%M", &local);
          if (item.state == BUFFER_ASSISTANT_RUNNING)
            snprintf(text, sizeof(text), "已设定 · %s", date);
          else if (item.state == BUFFER_ASSISTANT_FIRED)
            snprintf(text, sizeof(text), "闹钟已触发 · %s", date);
          else
            snprintf(text, sizeof(text), "已取消 · %s", date);
        }
      if (entry->toggle)
        {
          bool active = item.state == BUFFER_ASSISTANT_RUNNING || item.state == BUFFER_ASSISTANT_PAUSED;
          if (active) lv_obj_remove_state(entry->toggle, LV_STATE_DISABLED);
          else lv_obj_add_state(entry->toggle, LV_STATE_DISABLED);
          buffer_ui_label_set_text(lv_obj_get_child(entry->toggle, 0),
              item.state == BUFFER_ASSISTANT_PAUSED ? "继续" : "暂停");
          if(g_page_host&&lv_obj_get_child_count(entry->toggle)>1){
            lv_obj_t *icon=lv_obj_get_child(entry->toggle,1);
            if(lv_obj_check_type(icon,&lv_image_class))lv_image_set_src(icon,buffer_ui_icon_source(item.state==BUFFER_ASSISTANT_PAUSED?&buffer_play_arrow_icon:&buffer_pause_icon,lv_obj_get_width(icon)));
          }
        }
      if (entry->cancel)
        {
          if (item.state == BUFFER_ASSISTANT_CANCELLED) lv_obj_add_state(entry->cancel, LV_STATE_DISABLED);
          else lv_obj_remove_state(entry->cancel, LV_STATE_DISABLED);
          buffer_ui_label_set_text(lv_obj_get_child(entry->cancel, 0),
              item.state == BUFFER_ASSISTANT_FIRED ? "关闭提醒" :
              entry->type == BUFFER_ASSISTANT_EVENT_TIMER ? "结束计时" : "取消闹钟");
        }
      if (strcmp(lv_label_get_text(entry->label),text)) buffer_ui_label_set_text(entry->label, text);
      if(entry->inverse){
        /* Size the preview from the actual digits/font; a fixed width can
         * wrap 10:00 at CJK font metrics or enlarged accessibility text. */
        lv_obj_t *panel=lv_obj_get_parent(entry->label);lv_point_t measured;
        lv_text_get_size(&measured,text,lv_obj_get_style_text_font(entry->label,0),lv_obj_get_style_text_letter_space(entry->label,0),0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
        int width=measured.x+2*buffer_ui_dp(10),minimum=buffer_ui_dp(128)*(int)buffer_ui_font_scale()/100;
        if(width<minimum)width=minimum;
        int available=lv_obj_get_content_width(g_overlay);if(width>available)width=available;
        lv_obj_set_width(panel,width);
      }
      buffer_ui_style_text_color(entry->label,
          lv_color_hex(entry->inverse ? BUFFER_ON_PRIMARY : item.state == BUFFER_ASSISTANT_FIRED ?
                       BUFFER_WARNING : BUFFER_ACCENT), 0);
    }
}

int buffer_a2ui_ui_attach(void *home_body, void *record_button)
{
  if (home_body == NULL || record_button == NULL) return -EINVAL;
  if (g_attached) return 0;
  lv_obj_t *body = (lv_obj_t *)home_body;
  lv_obj_t *mic = (lv_obj_t *)record_button;
  lv_obj_t *root = lv_obj_get_parent(mic);
  if (root == NULL) return -EINVAL;

  g_inline_host = lv_obj_create(body);
  lv_obj_remove_style_all(g_inline_host);
  lv_obj_set_size(g_inline_host, LV_PCT(100), LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(g_inline_host, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(g_inline_host, BUFFER_SECTION_GAP, 0);
  buffer_ui_add_flag(g_inline_host, LV_OBJ_FLAG_HIDDEN);
  lv_obj_move_to_index(g_inline_host, 0);

  g_home_result_notice = ui_button_create(body, "查看新结果", UI_ACTION_CLOSE, 0);
  /* Use the reserved activity slot below the 48 px todo entry. A notification
   * must never become another flex row and push the current card downward. */
  buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_FLOATING);
  lv_obj_set_pos(g_home_result_notice, 0, buffer_ui_dp(48) + BUFFER_PANEL_GAP);
  buffer_ui_style_bg_opa(g_home_result_notice, LV_OPA_COVER, 0);
  lv_obj_remove_event_cb(g_home_result_notice, ui_action_event);
  lv_obj_add_event_cb(g_home_result_notice, ui_accept_result, LV_EVENT_CLICKED, NULL);

  buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);
  g_pending_id[0] = 0;
  g_overlay = lv_obj_create(root);
  lv_obj_remove_style_all(g_overlay);
  lv_obj_set_size(g_overlay, LV_PCT(100), LV_PCT(100));
  lv_obj_set_pos(g_overlay, 0, 0);
  buffer_ui_add_flag(g_overlay, LV_OBJ_FLAG_FLOATING);
  buffer_ui_style_bg_color(g_overlay, lv_color_hex(BUFFER_BG), 0);
  buffer_ui_style_bg_opa(g_overlay, LV_OPA_COVER, 0);
  lv_obj_set_style_pad_all(g_overlay, BUFFER_PAGE_PADDING, 0);
  lv_obj_set_style_text_font(g_overlay,
      lv_obj_get_style_text_font(root, LV_PART_MAIN), LV_PART_MAIN);
  lv_obj_set_flex_flow(g_overlay, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(g_overlay, BUFFER_SECTION_GAP, 0);
  buffer_ui_remove_flag(g_overlay, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(g_overlay, LV_DIR_VER);
  /* The overlay blocks interaction with the home screen beneath it. The
   * recorder is a later sibling and remains above it for press/release. */
  buffer_ui_add_flag(g_overlay, LV_OBJ_FLAG_CLICKABLE);
  buffer_ui_add_flag(g_overlay, LV_OBJ_FLAG_HIDDEN);
  g_record_button = mic;
  /* Keep the fixed recorder above full-screen surfaces in hit testing and
   * draw order; recipe navigation must never cover the hold-to-record input. */
  lv_obj_move_to_index(g_overlay, lv_obj_get_index(g_record_button));

  lv_obj_t *header = lv_obj_create(g_overlay);
  lv_obj_remove_style_all(header);
  lv_obj_set_size(header, LV_PCT(100), LV_SIZE_CONTENT);lv_obj_set_style_min_height(header,buffer_ui_dp(48),0);
  lv_obj_set_flex_flow(header, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(header, LV_FLEX_ALIGN_SPACE_BETWEEN,
                        LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_t *heading = ui_text_create(header, "助手", true, false);
  g_overlay_heading=heading;
  lv_obj_set_width(heading, 0); lv_obj_set_flex_grow(heading, 1);
  lv_obj_t *close = lv_button_create(header);
  lv_obj_set_size(close, buffer_ui_dp(48), buffer_ui_dp(48));
  ui_button_style(close);
  lv_obj_add_event_cb(close, ui_close_event, LV_EVENT_CLICKED, NULL);
  lv_obj_t *close_label = lv_label_create(close);
  buffer_ui_button_label(close_label);
  buffer_ui_label_set_text(close_label, "关闭");
  lv_obj_center(close_label);
  buffer_ui_icon_button(close,&buffer_back_icon);
  lv_obj_move_to_index(close,0);

  g_result_notice = ui_button_create(g_overlay, "查看新结果", UI_ACTION_CLOSE, 0);
  lv_obj_remove_event_cb(g_result_notice, ui_action_event);
  lv_obj_add_event_cb(g_result_notice, ui_accept_result, LV_EVENT_CLICKED, NULL);
  buffer_ui_add_flag(g_result_notice, LV_OBJ_FLAG_HIDDEN);
  g_feedback = ui_text_create(g_overlay, "", false, false);
  buffer_ui_text_role(g_feedback,BUFFER_TEXT_LABEL_LARGE);
  buffer_ui_style_text_color(g_feedback,lv_color_hex(BUFFER_ERROR),0);
  buffer_ui_add_flag(g_feedback,LV_OBJ_FLAG_HIDDEN);
  g_recipe_save_error=0;
  g_recipe_save_retry=ui_button_create(g_overlay,"重试保存菜谱进度",UI_ACTION_GENERIC,0);
  lv_obj_remove_event_cb(g_recipe_save_retry,ui_action_event);lv_obj_add_event_cb(g_recipe_save_retry,ui_recipe_save_retry,LV_EVENT_CLICKED,NULL);buffer_ui_add_flag(g_recipe_save_retry,LV_OBJ_FLAG_HIDDEN);
  g_empty_host=lv_obj_create(g_overlay);lv_obj_remove_style_all(g_empty_host);
  lv_obj_set_size(g_empty_host,LV_PCT(100),0);lv_obj_set_flex_grow(g_empty_host,1);
  lv_obj_set_style_min_height(g_empty_host,0,0);lv_obj_set_scroll_dir(g_empty_host,LV_DIR_VER);
  lv_obj_set_flex_flow(g_empty_host,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_all(g_empty_host,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_empty_host,buffer_ui_dp(10),0);
  g_capture_request=ui_text_create(g_empty_host,"",false,false);
  lv_obj_set_style_pad_all(g_capture_request,buffer_ui_dp(10),0);lv_obj_set_style_radius(g_capture_request,buffer_ui_dp(20),0);
  buffer_ui_style_bg_color(g_capture_request,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(g_capture_request,LV_OPA_COVER,0);
  buffer_ui_style_text_color(g_capture_request,lv_color_hex(BUFFER_ON_SURFACE),0);buffer_ui_add_flag(g_capture_request,LV_OBJ_FLAG_HIDDEN);
  g_empty_hint=ui_text_create(g_empty_host,"按住下方按钮说话，或长按 Home 唤醒助手。",false,false);
  buffer_ui_add_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN);
  g_overlay_content = lv_obj_create(g_overlay);
  lv_obj_remove_style_all(g_overlay_content);
  lv_obj_set_size(g_overlay_content, LV_PCT(100), 0);
  lv_obj_set_style_min_height(g_overlay_content, 0, 0);
  lv_obj_set_flex_grow(g_overlay_content, 1);
  lv_obj_set_flex_flow(g_overlay_content, LV_FLEX_FLOW_COLUMN);
  lv_obj_set_style_pad_row(g_overlay_content, BUFFER_SECTION_GAP, 0);
  lv_obj_set_scroll_dir(g_overlay_content, LV_DIR_VER);
  lv_obj_set_scrollbar_mode(g_overlay_content, LV_SCROLLBAR_MODE_AUTO);
  buffer_ui_remove_flag(g_overlay_content, LV_OBJ_FLAG_SCROLL_CHAIN);

  pthread_mutex_lock(&g_recipe_recovery_lock);g_recipe_recovery_epoch++;memset(g_recipe_recovery,0,sizeof(g_recipe_recovery));g_recipe_recovery_failed=false;pthread_mutex_unlock(&g_recipe_recovery_lock);
  (void)buffer_ui_recipe_state_start();
  g_attached = true;
  g_rendered_surface_id[0] = 0;
  g_rendered_sequence = 0;
  (void)buffer_a2ui_model_set_refresh_hook(NULL);
  buffer_assistant_set_event_handler(ui_assistant_event, NULL);
  return 0;
}

static void ui_shell_closed(void *context)
{
 (void)context;g_page_browsing=true;g_open_snapshot_ready=false;g_accept_incoming=false;
 g_timer_expanded=g_recipe_return_preview=false;
 if(g_render_parent){
  buffer_ui_motion_cancel(g_render_parent);lv_obj_set_style_translate_x(g_render_parent,0,0);
  lv_obj_set_style_translate_y(g_render_parent,0,0);lv_obj_set_style_opa(g_render_parent,LV_OPA_COVER,0);
 }
}
static int ui_shell_prepare(const char *id,void *context)
{
  (void)context;
  if(!id||!*id){g_open_snapshot_ready=false;return 0;}
  int result=buffer_a2ui_model_try_activate(id,&g_incoming);
  if(!result)g_open_snapshot_ready=true;
  return result;
}
static void ui_shell_activated(bool was_visible,void *context)
{
  (void)was_visible;(void)context;g_page_browsing=false;g_accept_incoming=true;
  buffer_a2ui_ui_refresh();
  if(g_page_host)lv_obj_send_event(g_page_host,LV_EVENT_VALUE_CHANGED,NULL);
}
static bool ui_shell_back(void *context)
{
 (void)context;
 if(g_recipe.root){
  if(!g_recipe_return_preview)return false;
  g_recipe_expanded=g_recipe_return_preview=false;
 }
 else if(g_timer_expanded)g_timer_expanded=false;
 else return false;
 g_presentation_dirty=true;(void)buffer_a2ui_ui_open_surface(g_rendered_surface_id);return true;
}
void buffer_a2ui_ui_dock_page(void *page)
{
  if (!g_overlay || !page) return;
  g_page_host = page;
  g_page_browsing = true;
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="assistant",.title="助手",.entry_route="assistant",.kind=BUFFER_UI_APP,.capture_mode=BUFFER_UI_CAPTURE_FLOATING,.min_width_dp=160,.min_height_dp=96,
    .actions=(const char *const[]){"open","record","perform","start-timer","toggle-timer","cancel-timer","cancel-alarm","back"},.action_count=8,.closed=ui_shell_closed,.back=ui_shell_back,.prepare_open=ui_shell_prepare,.activated=ui_shell_activated},g_overlay);
  buffer_ui_remove_flag(g_overlay_content, LV_OBJ_FLAG_SCROLL_CHAIN);
  lv_obj_t *header = lv_obj_get_child(g_overlay, 0);
  lv_obj_t *close = lv_obj_get_child(header, 0);
  buffer_ui_label_set_text(lv_obj_get_child(close, 0), "返回");
  lv_obj_set_height(header, LV_SIZE_CONTENT);lv_obj_set_style_min_height(header,buffer_ui_dp(48),0);
  lv_obj_set_height(close, buffer_ui_dp(48));
  buffer_ui_add_flag(header,LV_OBJ_FLAG_HIDDEN);lv_obj_set_style_pad_all(g_overlay,0,0);
}
bool buffer_a2ui_ui_open(void)
{return g_page_host&&g_overlay&&!g_page_browsing&&!lv_obj_has_flag(g_overlay,LV_OBJ_FLAG_HIDDEN);}
void buffer_a2ui_ui_open_current(void)
{
  if(g_capture_pending)g_capture_auto_open=true;
  struct buffer_ui_surface_info info;
  if(buffer_ui_shell_info("assistant",&info)){(void)buffer_ui_shell_open(info.entry_route,NULL);return;}
  (void)ui_shell_prepare(NULL,NULL);ui_shell_activated(false,NULL);
  if(g_overlay)buffer_ui_remove_flag(g_overlay,LV_OBJ_FLAG_HIDDEN);
}
int buffer_a2ui_ui_open_surface(const char *id)
{
  if(!id||!*id)return -EINVAL;
  struct buffer_ui_surface_info info;
  if(buffer_ui_shell_info("assistant",&info))return buffer_ui_shell_open(info.entry_route,id);
  int ret=ui_shell_prepare(id,NULL);
  if(!ret){ui_shell_activated(false,NULL);if(g_overlay)buffer_ui_remove_flag(g_overlay,LV_OBJ_FLAG_HIDDEN);}
  return ret;
}
int buffer_a2ui_ui_open_recipe_surface(const char *id)
{
 bool expanded=g_recipe_expanded,dirty=g_presentation_dirty,parent=g_recipe_return_preview;
 g_recipe_expanded=true;g_recipe_return_preview=false;g_presentation_dirty=true;
 int result=buffer_a2ui_ui_open_surface(id);
 if(result){g_recipe_expanded=expanded;g_presentation_dirty=dirty;g_recipe_return_preview=parent;}
 return result;
}
void buffer_a2ui_ui_capture_prompt(const char *text,const char *capture_id)
{
 buffer_notification_text(g_capture_prompt,sizeof(g_capture_prompt),text?text:"");
 g_capture_pending=true;g_recipe_expanded=false;g_timer_expanded=g_recipe_return_preview=false;g_prompt_surface[0]=0;
 buffer_notification_text(g_capture_id,sizeof(g_capture_id),capture_id?capture_id:"");
 g_capture_pending=g_capture_auto_open=true;
 buffer_notification_text(g_capture_origin,sizeof(g_capture_origin),"assistant");
 buffer_a2ui_ui_open_current();
}

void buffer_a2ui_ui_dock_notice(void *slot)
{
  if (!g_home_result_notice || !slot) return;
  lv_obj_set_parent(g_home_result_notice, slot);
  lv_obj_set_pos(g_home_result_notice, 0, 0);
  lv_obj_set_size(g_home_result_notice, LV_PCT(100), buffer_ui_dp(48));
  lv_obj_set_style_pad_hor(g_home_result_notice, buffer_ui_dp(8), 0);
  lv_obj_t *label = lv_obj_get_child(g_home_result_notice, 0);
  buffer_ui_label_set_text(label, "新回答");
  lv_label_set_long_mode(label, LV_LABEL_LONG_DOT);
}

void buffer_a2ui_ui_detach(void)
{
  if (!g_attached) return;
  pthread_mutex_lock(&g_recipe_recovery_lock);g_recipe_recovery_epoch++;pthread_mutex_unlock(&g_recipe_recovery_lock);
  buffer_ui_recipe_state_shutdown();
  g_attached = false;memset(g_seen_results,0,sizeof(g_seen_results));g_seen_next=0;
  g_page_host = NULL; g_page_browsing = false;
  g_capture_pending=g_presentation_dirty=g_recipe_return_preview=g_timer_expanded=false;g_recipe_expanded=true;g_capture_prompt[0]=g_prompt_surface[0]=0;
  g_open_snapshot_ready=false;
  g_record_button = NULL;
  buffer_assistant_set_event_handler(NULL, NULL);
  (void)buffer_a2ui_model_set_refresh_hook(NULL);
  if (g_inline_host != NULL) lv_obj_del(g_inline_host);
  if (g_overlay != NULL) lv_obj_del(g_overlay);
  if (g_home_result_notice) lv_obj_delete(g_home_result_notice);
  g_home_result_notice = NULL; g_feedback = g_empty_hint = g_empty_host = g_capture_request = NULL;
  g_inline_host = NULL;
  g_overlay = NULL;
  g_overlay_content = NULL;
  g_overlay_heading = NULL;g_recipe_save_retry=NULL;g_recipe_save_error=0;
  g_render_parent = NULL;
  g_dynamic_label_count = 0;
  g_rendered_surface_id[0] = 0;
  g_rendered_sequence = 0;
  memset(g_recipes,0,sizeof(g_recipes));memset(g_recovered_recipe_live,0,sizeof(g_recovered_recipe_live));
}

static char g_generic_error_seen[BUFFER_A2UI_COMPONENT_COUNT][64];
static void ui_generic_actions_refresh(void)
{
 for(unsigned i=0;i<g_surface_snapshot.component_count;i++){
  const struct buffer_a2ui_component_s *component=&g_surface_snapshot.components[i];lv_obj_t *button=g_nodes[i];
  if(!button||component->type!=BUFFER_A2UI_BUTTON||!lv_obj_get_child_count(button))continue;
  struct buffer_a2ui_action_status_s status;
  int result=buffer_a2ui_model_action_status(g_surface_snapshot.id,component->id,&status);
  if(result==-EAGAIN)continue;
  bool pending=!result&&status.pending;int error=!result?status.result:0;
  const char *caption=buffer_a2ui_component_text(&g_surface_snapshot,component);char retry[BUFFER_A2UI_COMPONENT_TEXT_MAX+32];
  if(pending){caption=status.accepted?"处理中…":"正在提交…";g_generic_error_seen[i][0]=0;}
  else if(error<0){
   snprintf(retry,sizeof(retry),"重试 · %s",caption);caption=retry;
   if(strcmp(g_generic_error_seen[i],status.id)){strcpy(g_generic_error_seen[i],status.id);ui_failure("操作未完成，可重试",error);}
  }
  if(pending)lv_obj_add_state(button,LV_STATE_DISABLED);else lv_obj_remove_state(button,LV_STATE_DISABLED);
  buffer_ui_label_set_text(lv_obj_get_child(button,0),caption);
 }
}
static void ui_capture_waiting(void)
{
 static struct buffer_voice_status voice;
 struct buffer_voice_status latest;bool fresh=buffer_voice_snapshot(&latest);if(fresh)voice=latest;
 if(fresh&&!g_capture_id[0]&&voice.id[0])buffer_notification_text(g_capture_id,sizeof(g_capture_id),voice.id);
 bool matched=g_capture_id[0]&&!strcmp(g_capture_id,voice.id);
 if(matched&&voice.transcript[0])buffer_notification_text(g_capture_prompt,sizeof(g_capture_prompt),voice.transcript);
 const char *caption="正在结束本次录音…";
 if(matched)switch(voice.phase){
  case BUFFER_VOICE_RECORDING:case BUFFER_VOICE_CLOSING:break;
  case BUFFER_VOICE_QUEUED:caption="等待识别服务处理本次录音…";break;
  case BUFFER_VOICE_ASR:caption="正在识别语音…";break;
  case BUFFER_VOICE_INTENT:caption="正在理解请求并生成回答…";break;
  case BUFFER_VOICE_EXECUTING:caption="正在执行本次指令…";break;
  case BUFFER_VOICE_READY:caption="本次请求已处理完成。";break;
  case BUFFER_VOICE_BUFFER_PENDING:caption="已转为记录，等待整理与同步。";break;
  case BUFFER_VOICE_RETRY:caption="本次请求暂未完成，已保留并等待重试。";break;
  case BUFFER_VOICE_ERROR:caption="本次录音未完成，请重新录制。";break;
  case BUFFER_VOICE_CANCELLED:caption="本次录音已取消。";break;
 }
 buffer_ui_add_flag(g_overlay_content,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN);
 buffer_ui_surface_decor(g_overlay,BUFFER_DECOR_SESSION,buffer_ui_dp(10));
 buffer_ui_shell_capture_mode("assistant",BUFFER_UI_CAPTURE_FLOATING);
 buffer_ui_label_set_text(g_capture_request,g_capture_prompt);
 if(g_capture_prompt[0])buffer_ui_remove_flag(g_capture_request,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_capture_request,LV_OBJ_FLAG_HIDDEN);
 buffer_ui_label_set_text(g_empty_hint,caption);
 buffer_ui_style_bg_color(g_empty_hint,lv_color_hex(BUFFER_ACCENT),0);buffer_ui_style_bg_opa(g_empty_hint,LV_OPA_COVER,0);
 buffer_ui_style_text_color(g_empty_hint,lv_color_hex(BUFFER_ON_PRIMARY),0);lv_obj_set_style_pad_all(g_empty_hint,buffer_ui_dp(10),0);lv_obj_set_style_radius(g_empty_hint,buffer_ui_dp(20),0);
}
void buffer_a2ui_ui_refresh(void)
{
  if (!g_attached || g_inline_host == NULL || g_overlay == NULL) return;
  ui_recipe_recovery_refresh();
  ui_generic_actions_refresh();
  char error_message[128];
  pthread_mutex_lock(&g_action_lock);
  int action_error = g_action_error;
  bool current_error = !strcmp(g_action_surface, g_rendered_surface_id);
  snprintf(error_message, sizeof(error_message), "%s", g_action_message);
  g_action_error = 0;
  pthread_mutex_unlock(&g_action_lock);
  if (action_error && current_error) ui_failure(error_message, action_error);
  struct buffer_voice_status voice;if(g_capture_pending&&buffer_voice_snapshot(&voice)){
    if(!g_capture_id[0])buffer_notification_text(g_capture_id,sizeof(g_capture_id),voice.id);
    if(!strcmp(g_capture_id,voice.id)&&voice.transcript[0])buffer_notification_text(g_capture_prompt,sizeof(g_capture_prompt),voice.transcript);
  }
  if(g_capture_pending&&strcmp(g_capture_origin,buffer_ui_shell_foreground()))g_capture_auto_open=false;

  if(g_capture_pending&&g_capture_auto_open&&!g_page_browsing){
    int current=buffer_a2ui_model_get_active(&g_incoming);
    if(current||!g_capture_id[0]||strcmp(g_capture_id,g_incoming.capture_id)){ui_capture_waiting();return;}
  }
  if(g_capture_pending&&!g_open_snapshot_ready&&!buffer_a2ui_model_get_active(&g_incoming)&&!strcmp(g_capture_id,g_incoming.capture_id)){
    buffer_notification_text(g_prompt_surface,sizeof(g_prompt_surface),g_incoming.id);
    if(g_capture_auto_open||(!g_page_browsing&&!strcmp(g_rendered_surface_id,g_incoming.id))){g_presentation_dirty=true;g_accept_incoming=true;}
    if(!g_page_browsing||!g_capture_auto_open)g_capture_pending=false;
  }

  if (g_page_host && g_page_browsing)
    {
      buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);
      if(g_capture_pending&&g_capture_auto_open&&!buffer_a2ui_model_get_active(&g_incoming)&&!strcmp(g_capture_id,g_incoming.capture_id)){
        buffer_notification_text(g_prompt_surface,sizeof(g_prompt_surface),g_incoming.id);g_presentation_dirty=true;
        if(!buffer_a2ui_ui_open_surface(g_prompt_surface))g_capture_pending=false;
      }
      return;
    }
  /* Explicit navigation owns this snapshot until its first render. A model
   * producer may publish another active surface while a finger is held. */
  int incoming_result=g_open_snapshot_ready?0:buffer_a2ui_model_get_active(&g_incoming);
  if(incoming_result==-EAGAIN)return;
  if(incoming_result<0 && incoming_result!=-ENOENT){ui_failure("内容暂时无法读取，请稍后重试",incoming_result);return;}
  if (incoming_result==-ENOENT)
    {
      buffer_ui_label_set_text(g_overlay_heading,"助手");
      buffer_ui_add_flag(g_inline_host, LV_OBJ_FLAG_HIDDEN);
      if(g_page_host&&!g_page_browsing){
        buffer_ui_remove_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN);buffer_ui_add_flag(g_overlay_content,LV_OBJ_FLAG_HIDDEN);
      }else buffer_ui_add_flag(g_overlay, LV_OBJ_FLAG_HIDDEN);
      if(g_rendered_surface_id[0]){
        lv_obj_clean(g_overlay_content);lv_obj_clean(g_inline_host);
        memset(g_nodes,0,sizeof(g_nodes));memset(g_old_nodes,0,sizeof(g_old_nodes));
      }
      g_dynamic_label_count = 0;
      g_rendered_surface_id[0] = 0;
      g_rendered_sequence = 0;
      g_accept_incoming = false;
      buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);
      return;
    }

  buffer_ui_add_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN);
  buffer_ui_remove_flag(g_overlay_content,LV_OBJ_FLAG_HIDDEN);
  /* Presentation-only policy: even a compact timer surface opens a dedicated
   * assistant page. The home activity slot supplies the compact entry instead
   * of inserting an A2UI tree among Buffer reading content. */
  g_incoming.full_screen = true;
  static bool home_notice_visible;
  if (pthread_mutex_trylock(&g_buffer.lock) == 0)
    {
      home_notice_visible = !buffer_todo_ui_open() && strcmp(g_buffer.mode, "focus");
      pthread_mutex_unlock(&g_buffer.lock);
    }
  if (!home_notice_visible) buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);

  /* The result page occupies the area above the shared capture button.
   * Reserve its full touch target instead of depending on overlapping sibling
   * draw order; flex reflow can otherwise leave only a strip of it visible. */
  if (g_record_button != NULL && !g_page_host)
    {
      lv_obj_t *root = lv_obj_get_parent(g_record_button);
      lv_obj_update_layout(root);
      int32_t available = lv_obj_get_content_height(root) -
                          lv_obj_get_height(g_record_button) - BUFFER_SECTION_GAP;
      if (available > 0 && lv_obj_get_style_height(g_overlay, 0) != available)
        lv_obj_set_height(g_overlay, available);
    }

  bool new_task = strcmp(g_incoming.id, g_rendered_surface_id) != 0 ||
                  g_incoming.task_revision != g_surface_snapshot.task_revision;
  bool pending_other=false;
  if (new_task && g_rendered_surface_id[0] && g_rendered_full_screen && !g_accept_incoming)
    {
      snprintf(g_pending_id,sizeof(g_pending_id),"%s",g_incoming.id);
      buffer_ui_remove_flag(g_result_notice, LV_OBJ_FLAG_HIDDEN);
      if (home_notice_visible) buffer_ui_remove_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);
      /* A background result does not freeze updates to the visible recipe
       * (for example, a just-bound timer). Read that stable surface while
       * retaining the unrelated result's explicit entry. */
      if(buffer_a2ui_model_get_surface(g_rendered_surface_id,&g_incoming)||
         g_incoming.task_revision!=g_surface_snapshot.task_revision){ui_dynamic_refresh();return;}
      g_incoming.full_screen=true;new_task=false;pending_other=true;
    }
  bool changed = g_presentation_dirty||g_incoming.sequence != g_rendered_sequence || new_task ||
                 g_incoming.full_screen != g_rendered_full_screen;
  if (changed && !ui_input_busy())
    {
      if(strcmp(g_incoming.id,g_rendered_surface_id))g_timer_expanded=g_recipe_return_preview=false;
      g_reconcile = !g_presentation_dirty&&!new_task && g_incoming.full_screen == g_rendered_full_screen;
      g_presentation_dirty=false;
      memcpy(g_old_nodes, g_nodes, sizeof(g_nodes));
      if (!g_reconcile)
        {
          /* No old task node or callback is eligible for reuse. */
          memset(g_old_nodes, 0, sizeof(g_old_nodes));
          lv_obj_clean(g_overlay_content); lv_obj_clean(g_inline_host);
        }
      memset(g_nodes, 0, sizeof(g_nodes));
      g_surface_snapshot = g_incoming;
      g_open_snapshot_ready=false;
      g_accept_incoming = false;
      if(!pending_other){
        g_pending_id[0] = 0;
        buffer_ui_add_flag(g_result_notice, LV_OBJ_FLAG_HIDDEN);
        buffer_ui_add_flag(g_home_result_notice, LV_OBJ_FLAG_HIDDEN);
      }
      buffer_ui_add_flag(g_feedback, LV_OBJ_FLAG_HIDDEN);
      ui_render_surface();
    }
  else if(!changed){g_open_snapshot_ready=false;g_accept_incoming=false;}
  ui_dynamic_refresh();
  ui_generic_actions_refresh();
}
