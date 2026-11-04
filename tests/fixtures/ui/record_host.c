#include "buffer_ui_layout.h"
/* Real LVGL pointer dispatch to extracted production handlers. */
#include <assert.h>
#include <stdarg.h>
#include <string.h>
#include <stdio.h>
#include <lvgl/lvgl.h>
#include "buffer_app.h"
#include "buffer_ui_theme.h"
static lv_obj_t *g_record_button, *g_record_label, *g_mic, *g_live_panel;
static void buffer_visible(lv_obj_t *obj, bool visible)
{ if (visible) lv_obj_remove_flag(obj, LV_OBJ_FLAG_HIDDEN); else lv_obj_add_flag(obj, LV_OBJ_FLAG_HIDDEN); }
static lv_obj_t *g_card_more_panel;
static char g_detail_id[BUFFER_ID_SIZE];
static bool g_touch_recording;
static uint32_t g_record_tick;
static bool g_record_timer_started;
static lv_point_t g_record_origin;
static unsigned starts, stops, cancels;
int buffer_touch_record_start(void) { starts++; return 0; }
int buffer_touch_record_start_for_card(const char *id) { (void)id;return buffer_touch_record_start(); }
void buffer_touch_record_stop(void) { stops++; }
int buffer_touch_record_cancel(void) { cancels++; return 0; }
void buffer_set_status(const char *format, ...) { (void)format; }
void buffer_ui_shell_capture_expanded(bool expanded){(void)expanded;}
void buffer_a2ui_ui_capture_prompt(const char *text,const char *capture){(void)text;(void)capture;}
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
void buffer_live_asr_snapshot(char *text,size_t capacity){if(capacity)text[0]=0;}
#include "record_production.inc"
static lv_indev_data_t input;
static void read_pointer(lv_indev_t *indev, lv_indev_data_t *data)
{ (void)indev; *data = input; }
static void flush(lv_display_t *disp, const lv_area_t *area, uint8_t *pixels)
{ (void)area; (void)pixels; lv_display_flush_ready(disp); }
static void point(lv_indev_t *indev, int x, int y, bool down)
{
  input.point.x=x; input.point.y=y;
  input.state=down ? LV_INDEV_STATE_PRESSED : LV_INDEV_STATE_RELEASED;
  lv_tick_inc(30); lv_indev_read(indev); lv_timer_handler();
}
int main(void)
{
  lv_init(); lv_display_t *display=lv_display_create(320,480);
  static uint8_t pixels[320*32*4];
  lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(display,flush);
  g_record_button=lv_button_create(lv_screen_active());
  lv_obj_set_pos(g_record_button,12,404);lv_obj_set_size(g_record_button,296,64);
  lv_obj_remove_flag(g_record_button,LV_OBJ_FLAG_SCROLL_CHAIN|LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_add_flag(g_record_button,LV_OBJ_FLAG_PRESS_LOCK);
  g_record_label=lv_label_create(g_record_button);g_mic=lv_image_create(g_record_button);
  lv_obj_add_event_cb(g_record_button,buffer_record_event,LV_EVENT_ALL,NULL);
  lv_obj_update_layout(lv_screen_active());
  lv_indev_t *indev=lv_indev_create();lv_indev_set_type(indev,LV_INDEV_TYPE_POINTER);
  lv_indev_set_read_cb(indev,read_pointer);
  point(indev,160,436,true);point(indev,168,432,true);point(indev,168,432,false);
  assert(starts==1 && stops==1 && cancels==0 && !g_touch_recording);
  /* Horizontal movement within the wide button still cancels. */
  point(indev,160,436,true);point(indev,220,436,true);
  assert(cancels==1 && !g_touch_recording);
  point(indev,160,436,true);point(indev,160,436,false);
  assert(starts==2 && stops==1 && cancels==1);
  /* Moving upward, returning or releasing cannot submit the capture. */
  point(indev,160,436,true);point(indev,160,380,true);point(indev,160,300,true);
  point(indev,160,300,false);
  assert(starts==3 && stops==1 && cancels==2);
  point(indev,160,436,true);point(indev,160,436,false);
  assert(starts==4 && stops==2 && cancels==2);
  struct buffer_card_s card={0};strcpy(card.kind,"spark_card");
  assert(buffer_detail_action_visible(&card,"archive"));
  assert(!buffer_detail_action_visible(&card,"done"));
  assert(!buffer_detail_action_visible(&card,"remind"));
  assert(!buffer_detail_action_visible(&card,"rabbit_hole"));
  strcpy(card.kind,"task_card");assert(buffer_detail_action_visible(&card,"done"));
  strcpy(card.state,"done");assert(!buffer_detail_action_visible(&card,"done"));
  strcpy(card.kind,"question_card");assert(buffer_detail_action_visible(&card,"rabbit_hole"));
  strcpy(card.actions,"archive");assert(!buffer_detail_action_visible(&card,"rabbit_hole"));
  assert(!buffer_detail_action_visible(NULL,"archive"));
  lv_indev_delete(indev);lv_obj_delete(g_record_button);lv_display_delete(display);lv_deinit();
  puts("PASS: pointer hold/jitter/slide/release, sticky cancellation, contextual actions");
}
