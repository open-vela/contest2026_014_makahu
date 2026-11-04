/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_motion.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include <string.h>
#include <stdlib.h>
#define TRACKS 24
struct track { lv_obj_t *obj; struct buffer_spring spring;
               enum buffer_motion_role role; unsigned property; };
static struct track tracks[TRACKS];
static lv_timer_t *frame;
static uint32_t last_tick;
static bool reduced, low_load;
static unsigned late_frames;
static void apply(struct track *t)
{
  int value = (int)t->spring.value;
  if (t->property == 0) lv_obj_set_style_translate_x(t->obj, value, 0);
  else if (t->property == 1) lv_obj_set_style_opa(t->obj, value < 0 ? 0 : value > 255 ? 255 : value, 0);
  else if (t->property == 2) lv_obj_set_style_radius(t->obj, value < 0 ? 0 : value, 0);
  else if (t->property == 3) lv_obj_set_height(t->obj, value < 0 ? 0 : value);
  else lv_obj_set_style_translate_y(t->obj, value, 0);
}
void buffer_ui_motion_cancel(lv_obj_t *object)
{
  for (unsigned i = 0; i < TRACKS; i++)
    if (tracks[i].obj == object) tracks[i].obj = NULL;
}
static void lifecycle(lv_event_t *e)
{
  lv_obj_t *obj = lv_event_get_target(e);
  if (lv_event_get_code(e) == LV_EVENT_DELETE) buffer_ui_motion_cancel(obj);
  else if (lv_event_get_code(e) == LV_EVENT_SCROLL_BEGIN ||
           lv_event_get_code(e) == LV_EVENT_PRESSED)
    for (unsigned i = 0; i < TRACKS; i++)
      if (tracks[i].obj == obj && tracks[i].property != 2)
        { tracks[i].spring.value = tracks[i].spring.target; apply(&tracks[i]); tracks[i].obj = NULL; }
}
static void target(lv_obj_t *obj, unsigned property, float from, float to,
                   enum buffer_motion_role role)
{
  struct track *free_track = NULL;
  for (unsigned i = 0; i < TRACKS; i++)
    {
      if (tracks[i].obj == obj && tracks[i].property == property)
        { buffer_spring_target(&tracks[i].spring, to); return; }
      if (!tracks[i].obj && !free_track) free_track = &tracks[i];
    }
  struct track immediate = {obj, {to, 0, to, false}, role, property};
  if (reduced || low_load || !free_track) { apply(&immediate); return; }
  if (frame) { last_tick = lv_tick_get(); lv_timer_resume(frame); }
  *free_track = (struct track){obj, {from, 0, to, true}, role, property};
  apply(free_track);
  /* One delete callback per object, shared by all its property tracks. */
  lv_obj_remove_event_cb(obj, lifecycle);
  lv_obj_add_event_cb(obj, lifecycle, LV_EVENT_ALL, NULL);
}
static void tick(lv_timer_t *timer)
{
  (void)timer;
  uint32_t now = lv_tick_get(), elapsed = now - last_tick;
  last_tick = now;
  if (elapsed > 40) late_frames++; else if (late_frames) late_frames--;
  bool busy = false;
  for (unsigned i = 0; i < TRACKS; i++) if (tracks[i].obj)
    {
      buffer_spring_step(&tracks[i].spring, tracks[i].role, elapsed, reduced || low_load || late_frames >= 3);
      apply(&tracks[i]);
      if (!tracks[i].spring.active) tracks[i].obj = NULL;
      else busy = true;
    }
  if (!busy) lv_timer_pause(frame);
}
bool buffer_ui_motion_reduced(void) { return reduced; }
void buffer_ui_motion_policy(bool reduce, bool low)
{ reduced = reduce; low_load = low; }
void buffer_ui_motion_init(void)
{
  const char *policy = getenv("BUFFER_REDUCED_MOTION");
  reduced = policy && strcmp(policy, "1") == 0;
  policy = getenv("BUFFER_UI_LOW_LOAD");
  low_load = policy && strcmp(policy, "1") == 0;
  last_tick = lv_tick_get();
  if (!frame) frame = lv_timer_create(tick, 16, NULL);
  lv_timer_pause(frame);
}
void buffer_ui_motion_shutdown(void)
{
  if (frame) lv_timer_delete(frame);
  frame = NULL; memset(tracks, 0, sizeof(tracks));
}
void buffer_ui_motion_enter(lv_obj_t *obj, int direction, bool page)
{
  target(obj, 0, direction * buffer_ui_dp(page ? 28 : 14), 0, page ? BUFFER_SL : BUFFER_SD);
  target(obj, 1, 180, 255, page ? BUFFER_EL : BUFFER_ED);
}
void buffer_ui_motion_effect(lv_obj_t *obj)
{ target(obj, 1, 200, 255, BUFFER_ED); }
void buffer_ui_motion_enter_vertical(lv_obj_t *obj, int direction)
{
  target(obj, 4, direction * buffer_ui_dp(14), 0, BUFFER_SD);
  target(obj, 1, 180, 255, BUFFER_ED);
}
void buffer_ui_motion_height(lv_obj_t *obj, int from, int to)
{ target(obj, 3, from, to, BUFFER_SD); }
static void press(lv_event_t *e)
{
  lv_event_code_t code = lv_event_get_code(e);
  lv_obj_t *obj = lv_event_get_target(e);
  uintptr_t shape=(uintptr_t)lv_event_get_user_data(e);
  if (code == LV_EVENT_PRESSED || code == LV_EVENT_RELEASED || code == LV_EVENT_PRESS_LOST)
    target(obj, 2, lv_obj_get_style_radius(obj, 0),
           buffer_ui_dp(code == LV_EVENT_PRESSED ? shape&0xffff : shape>>16), BUFFER_SF);
}
void buffer_ui_button_shape(lv_obj_t *obj,unsigned normal,unsigned pressed)
{
  buffer_ui_motion_cancel(obj);
  lv_obj_set_style_radius(obj,buffer_ui_dp(normal),0);
  lv_obj_remove_event_cb(obj,press);
  lv_obj_add_event_cb(obj,press,LV_EVENT_ALL,(void *)(uintptr_t)((normal<<16)|pressed));
}
static void segment_draw(lv_event_t *event)
{
  lv_draw_task_t *task=lv_event_get_draw_task(event);
  lv_draw_fill_dsc_t *fill=lv_draw_task_get_fill_dsc(task);
  if(!fill||fill->base.part!=LV_PART_MAIN)return;
  uintptr_t radii=(uintptr_t)lv_event_get_user_data(event);
  /* Two clipped native fills preserve per-corner shape without raster masks,
   * extra hit targets, or replacing the checked/pressed theme colours. */
  lv_area_t clip=task->clip_area;
  int32_t middle=(task->area.y1+task->area.y2)/2;
  fill->radius=buffer_ui_dp(radii>>16);
  task->clip_area.y2=LV_MIN(task->clip_area.y2,middle);
  lv_draw_rect_dsc_t lower;lv_draw_rect_dsc_init(&lower);
  lower.base=fill->base;lower.base.dsc_size=sizeof(lower);
  lower.bg_color=fill->color;lower.bg_opa=fill->opa;lower.bg_grad=fill->grad;
  lower.radius=buffer_ui_dp(radii&0xffff);
  lv_layer_t *layer=fill->base.layer;lv_area_t saved=layer->_clip_area;
  clip.y1=LV_MAX(clip.y1,middle+1);layer->_clip_area=clip;
  if(clip.y1<=clip.y2)lv_draw_rect(layer,&lower,&task->area);
  layer->_clip_area=saved;
}
void buffer_ui_corners(lv_obj_t *obj,unsigned top,unsigned bottom)
{
  lv_obj_set_style_radius(obj,buffer_ui_dp(bottom),0);
  lv_obj_remove_event_cb(obj,segment_draw);
  lv_obj_add_flag(obj,LV_OBJ_FLAG_SEND_DRAW_TASK_EVENTS);
  lv_obj_add_event_cb(obj,segment_draw,LV_EVENT_DRAW_TASK_ADDED,(void *)(uintptr_t)((top<<16)|bottom));
}
void buffer_ui_segment_shape(lv_obj_t *obj,bool first,bool last)
{
  buffer_ui_button_shape(obj,3,3);
  buffer_ui_corners(obj,first?10:3,last?10:3);
}
void buffer_ui_button_style(lv_obj_t *obj, bool primary)
{
  buffer_ui_text_role(obj,BUFFER_TEXT_LABEL_LARGE);
  lv_obj_set_style_min_width(obj,buffer_ui_dp(48),0);
  lv_obj_set_style_min_height(obj,buffer_ui_dp(48),0);
  lv_obj_set_style_radius(obj, BUFFER_BUTTON_RADIUS, 0);
  lv_obj_set_style_shadow_width(obj, 0, 0);
  lv_obj_set_style_border_width(obj, 0, 0);
  lv_obj_set_style_bg_color(obj, lv_color_hex(primary ? BUFFER_ACCENT : BUFFER_PRIMARY_CONTAINER), 0);
  lv_obj_set_style_text_color(obj, lv_color_hex(primary ? BUFFER_ON_PRIMARY : BUFFER_ON_SURFACE), 0);
  lv_obj_set_style_bg_color(obj, lv_color_hex(primary ? BUFFER_PRIMARY_PRESSED : BUFFER_PRIMARY_CONTAINER_PRESSED), LV_STATE_PRESSED);
  lv_obj_set_style_text_color(obj, lv_color_hex(primary ? BUFFER_ON_PRIMARY : BUFFER_ON_SURFACE), LV_STATE_PRESSED);
  lv_obj_set_style_bg_color(obj, lv_color_hex(BUFFER_PANEL), LV_STATE_DISABLED);
  lv_obj_set_style_text_color(obj, lv_color_hex(BUFFER_MUTED), LV_STATE_DISABLED);
  lv_obj_set_style_opa(obj, LV_OPA_COVER, LV_STATE_DISABLED);
  lv_obj_set_style_bg_color(obj, lv_color_hex(BUFFER_ACCENT), LV_STATE_CHECKED);
  lv_obj_set_style_text_color(obj, lv_color_white(), LV_STATE_CHECKED);
  lv_obj_set_style_bg_color(obj,lv_color_hex(BUFFER_PRIMARY_PRESSED),LV_STATE_CHECKED|LV_STATE_PRESSED);
  lv_obj_set_style_text_color(obj,lv_color_hex(BUFFER_ON_PRIMARY),LV_STATE_CHECKED|LV_STATE_PRESSED);
  lv_obj_set_style_outline_color(obj, lv_color_hex(BUFFER_ACCENT), LV_STATE_FOCUS_KEY);
  lv_obj_set_style_outline_width(obj, buffer_ui_dp(2), LV_STATE_FOCUS_KEY);
  lv_obj_set_style_outline_pad(obj, buffer_ui_dp(2), LV_STATE_FOCUS_KEY);
  buffer_ui_button_shape(obj,20,12);
}
static void launcher_shape(lv_event_t *event)
{
  lv_event_code_t code = lv_event_get_code(event);
  if (code != LV_EVENT_PRESSED && code != LV_EVENT_RELEASED && code != LV_EVENT_PRESS_LOST) return;
  lv_obj_t *obj = lv_event_get_target(event);
  buffer_ui_motion_cancel(obj);
  lv_obj_set_style_radius(obj, buffer_ui_dp(32), 0);
}
void buffer_ui_launcher_style(lv_obj_t *obj)
{
  buffer_ui_button_style(obj, false);
  lv_obj_set_size(obj, buffer_ui_dp(64), buffer_ui_dp(64));
  lv_obj_set_style_radius(obj, buffer_ui_dp(32), 0);
  lv_obj_remove_event_cb(obj,press);
  lv_obj_set_style_pad_all(obj, 0, 0);
  lv_obj_set_style_bg_color(obj, lv_color_hex(BUFFER_PRIMARY_CONTAINER), 0);
  lv_obj_remove_event_cb(obj, launcher_shape);
  lv_obj_add_event_cb(obj, launcher_shape, LV_EVENT_ALL, NULL);
}
