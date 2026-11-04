/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_controls.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_typography.h"
#include "buffer_ui_theme.h"
struct switch_control {lv_obj_t *track;buffer_ui_switch_changed changed;void *context;};
static void dispose(lv_event_t *event);
static struct switch_control *control(lv_obj_t *row)
{
 for(uint32_t i=0;i<lv_obj_get_event_count(row);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(row,i);
  if(event&&lv_event_dsc_get_cb(event)==dispose)return lv_event_dsc_get_user_data(event);
 }
 return NULL;
}
static void dispose(lv_event_t *event){lv_free(lv_event_get_user_data(event));}
static void toggle(lv_event_t *event)
{
 lv_obj_t *row=lv_event_get_current_target(event);struct switch_control *item=lv_event_get_user_data(event);
 if(lv_obj_has_state(row,LV_STATE_DISABLED))return;
 lv_event_code_t code=lv_event_get_code(event);
 if(code==LV_EVENT_KEY){uint32_t key=lv_event_get_key(event);/* Enter also produces CLICKED on release; handle it only there. */if(key!=' ')return;}
 else if(code!=LV_EVENT_CLICKED)return;
 if(lv_obj_has_state(item->track,LV_STATE_CHECKED))lv_obj_remove_state(item->track,LV_STATE_CHECKED);
 else lv_obj_add_state(item->track,LV_STATE_CHECKED);
 if(lv_obj_has_state(item->track,LV_STATE_CHECKED))lv_obj_add_state(row,LV_STATE_CHECKED);else lv_obj_remove_state(row,LV_STATE_CHECKED);
 if(item->changed)item->changed(lv_obj_has_state(item->track,LV_STATE_CHECKED),item->context);
}
lv_obj_t *buffer_ui_switch_create(lv_obj_t *parent,const char *caption,bool checked,
                                  buffer_ui_switch_changed changed,void *context)
{
 struct switch_control *item=lv_malloc(sizeof(*item));if(!item)return NULL;
 lv_obj_t *row=lv_obj_create(parent);lv_obj_remove_style_all(row);
 lv_obj_set_size(row,LV_PCT(100),LV_SIZE_CONTENT);lv_obj_set_style_min_height(row,buffer_ui_dp(48),0);
 lv_obj_set_style_pad_ver(row,buffer_ui_dp(8),0);lv_obj_set_style_pad_column(row,buffer_ui_dp(12),0);
 lv_obj_set_flex_flow(row,LV_FLEX_FLOW_ROW);
 lv_obj_set_flex_align(row,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
 lv_obj_set_style_bg_opa(row,LV_OPA_30,LV_STATE_PRESSED);
 lv_obj_set_style_bg_color(row,lv_color_hex(BUFFER_PRIMARY_CONTAINER),LV_STATE_PRESSED);
 lv_obj_set_style_radius(row,buffer_ui_dp(12),0);
 lv_obj_add_flag(row,LV_OBJ_FLAG_CLICKABLE);lv_obj_remove_flag(row,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_set_style_outline_width(row,buffer_ui_dp(2),LV_STATE_FOCUS_KEY);
 lv_obj_set_style_outline_color(row,lv_color_hex(BUFFER_ACCENT),LV_STATE_FOCUS_KEY);
 lv_obj_t *label=lv_label_create(row);buffer_ui_text_role(label,BUFFER_TEXT_BODY_LARGE);
 lv_obj_set_width(label,0);lv_obj_set_flex_grow(label,1);lv_label_set_long_mode(label,LV_LABEL_LONG_WRAP);
 buffer_ui_label_set_text(label,caption);
 item->track=lv_switch_create(row);item->changed=changed;item->context=context;
 lv_obj_set_size(item->track,buffer_ui_dp(52),buffer_ui_dp(32));
 /* Pointer and keyboard input go to the full row, never the smaller track. */
 lv_obj_remove_flag(item->track,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_CLICK_FOCUSABLE);
 lv_group_t *group=lv_obj_get_group(item->track);if(group)lv_group_remove_obj(item->track);
 group=lv_group_get_default();if(group)lv_group_add_obj(group,row);
 lv_obj_set_style_bg_color(item->track,lv_color_hex(BUFFER_PANEL),0);
 lv_obj_set_style_bg_color(item->track,lv_color_hex(BUFFER_ACCENT),LV_PART_INDICATOR|LV_STATE_CHECKED);
 if(checked){lv_obj_add_state(item->track,LV_STATE_CHECKED);lv_obj_add_state(row,LV_STATE_CHECKED);}
 lv_obj_add_event_cb(row,toggle,LV_EVENT_CLICKED,item);lv_obj_add_event_cb(row,toggle,LV_EVENT_KEY,item);
 lv_obj_add_event_cb(row,dispose,LV_EVENT_DELETE,item);
 return row;
}
void buffer_ui_switch_set(lv_obj_t *row,bool checked)
{
 struct switch_control *item=control(row);if(!item)return;
 if(checked){lv_obj_add_state(item->track,LV_STATE_CHECKED);lv_obj_add_state(row,LV_STATE_CHECKED);}
 else {lv_obj_remove_state(item->track,LV_STATE_CHECKED);lv_obj_remove_state(row,LV_STATE_CHECKED);}
}
void buffer_ui_switch_disable(lv_obj_t *row,bool disabled)
{
 struct switch_control *item=control(row);if(!item)return;
 if(disabled){lv_obj_add_state(row,LV_STATE_DISABLED);lv_obj_add_state(item->track,LV_STATE_DISABLED);}
 else {lv_obj_remove_state(row,LV_STATE_DISABLED);lv_obj_remove_state(item->track,LV_STATE_DISABLED);}
 lv_obj_set_style_text_color(row,lv_color_hex(disabled?BUFFER_MUTED:BUFFER_ON_SURFACE),0);
}

/* M3 Expressive single-value slider: thick track, tall narrow handle and
 * discrete stop indicators. The enclosing 48dp row reserves the touch area. */
static void slider_stops(lv_event_t *event)
{
 lv_obj_t *slider=lv_event_get_current_target(event);
 unsigned count=(unsigned)(uintptr_t)lv_event_get_user_data(event);
 if(count<2)return;
 lv_area_t area;lv_obj_get_content_coords(slider,&area);
 int value=lv_slider_get_value(slider),minimum=lv_slider_get_min_value(slider);
 lv_draw_rect_dsc_t dot;lv_draw_rect_dsc_init(&dot);dot.radius=LV_RADIUS_CIRCLE;dot.bg_opa=LV_OPA_COVER;
 int radius=buffer_ui_dp(2),middle=(area.y1+area.y2)/2;
 bool rtl=lv_obj_get_style_base_dir(slider,0)==LV_BASE_DIR_RTL;
 for(unsigned i=0;i<count;i++){
  int inset=(area.y2-area.y1+1)/2;
  int x=area.x1+inset+LV_MAX(0,area.x2-area.x1-2*inset)*(rtl?(int)(count-1-i):(int)i)/(int)(count-1);
  int stop=minimum+(lv_slider_get_max_value(slider)-minimum)*(int)i/(int)(count-1);
  if(stop==value)continue;
  dot.bg_color=lv_color_hex(stop<=value?BUFFER_ON_PRIMARY:BUFFER_ACCENT);
  lv_area_t point={x-radius,middle-radius,x+radius,middle+radius};lv_draw_rect(lv_event_get_layer(event),&dot,&point);
 }
}
lv_obj_t *buffer_ui_slider_create(lv_obj_t *parent,int minimum,int maximum,int value,bool discrete)
{
 lv_obj_t *row=lv_obj_create(parent);lv_obj_remove_style_all(row);
 lv_obj_set_size(row,LV_PCT(100),buffer_ui_dp(48));lv_obj_set_style_pad_hor(row,buffer_ui_dp(12),0);
 lv_obj_remove_flag(row,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_t *slider=lv_slider_create(row);lv_obj_remove_style_all(slider);
 lv_obj_set_size(slider,LV_PCT(100),buffer_ui_dp(16));lv_obj_center(slider);lv_obj_set_ext_click_area(slider,buffer_ui_dp(16));
 lv_slider_set_range(slider,minimum,maximum);lv_slider_set_value(slider,value,LV_ANIM_OFF);
 lv_obj_set_style_bg_color(slider,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);lv_obj_set_style_bg_opa(slider,LV_OPA_COVER,0);lv_obj_set_style_radius(slider,buffer_ui_dp(8),0);
 lv_obj_set_style_bg_color(slider,lv_color_hex(BUFFER_ACCENT),LV_PART_INDICATOR);lv_obj_set_style_bg_opa(slider,LV_OPA_COVER,LV_PART_INDICATOR);lv_obj_set_style_radius(slider,buffer_ui_dp(8),LV_PART_INDICATOR);
 lv_obj_set_style_bg_color(slider,lv_color_hex(BUFFER_ACCENT),LV_PART_KNOB);lv_obj_set_style_bg_opa(slider,LV_OPA_COVER,LV_PART_KNOB);lv_obj_set_style_radius(slider,buffer_ui_dp(2),LV_PART_KNOB);
 lv_obj_set_style_pad_hor(slider,-buffer_ui_dp(6),LV_PART_KNOB);lv_obj_set_style_pad_ver(slider,buffer_ui_dp(14),LV_PART_KNOB);
 lv_obj_set_style_outline_color(slider,lv_color_hex(BUFFER_BG),LV_PART_KNOB);lv_obj_set_style_outline_width(slider,buffer_ui_dp(4),LV_PART_KNOB);lv_obj_set_style_outline_pad(slider,0,LV_PART_KNOB);
 lv_obj_set_style_outline_color(slider,lv_color_hex(BUFFER_ACCENT),LV_STATE_FOCUS_KEY);lv_obj_set_style_outline_width(slider,buffer_ui_dp(2),LV_STATE_FOCUS_KEY);lv_obj_set_style_outline_pad(slider,buffer_ui_dp(6),LV_STATE_FOCUS_KEY);
 lv_obj_set_style_opa(slider,LV_OPA_40,LV_STATE_DISABLED);
 lv_obj_add_event_cb(slider,slider_stops,LV_EVENT_DRAW_MAIN_END,(void *)(uintptr_t)(discrete?maximum-minimum+1:2));
 return slider;
}
