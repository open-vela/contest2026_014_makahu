/* Generated from pinned Material Symbols Rounded SVGs. Apache-2.0. */
#ifndef BUFFER_UI_ICONS_H
#define BUFFER_UI_ICONS_H
#include <lvgl/lvgl.h>
#include "buffer_ui_theme.h"
struct buffer_ui_symbol_s;
struct buffer_ui_symbol_variant_s { lv_image_dsc_t image; const struct buffer_ui_symbol_s *symbol; };
struct buffer_ui_symbol_s { const struct buffer_ui_symbol_variant_s *variants; unsigned count; };
const lv_image_dsc_t *buffer_ui_icon_source(const lv_image_dsc_t *source,int pixels);
extern const struct buffer_ui_symbol_variant_s buffer_mic_variants[];
#define buffer_mic_icon (buffer_mic_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_more_variants[];
#define buffer_more_icon (buffer_more_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_checklist_variants[];
#define buffer_checklist_icon (buffer_checklist_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_calendar_variants[];
#define buffer_calendar_icon (buffer_calendar_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_history_variants[];
#define buffer_history_icon (buffer_history_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_settings_variants[];
#define buffer_settings_icon (buffer_settings_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_wifi_variants[];
#define buffer_wifi_icon (buffer_wifi_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_warning_variants[];
#define buffer_warning_icon (buffer_warning_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_ready_variants[];
#define buffer_ready_icon (buffer_ready_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_unchecked_variants[];
#define buffer_unchecked_icon (buffer_unchecked_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_pending_variants[];
#define buffer_pending_icon (buffer_pending_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_previous_variants[];
#define buffer_previous_icon (buffer_previous_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_next_variants[];
#define buffer_next_icon (buffer_next_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_up_variants[];
#define buffer_up_icon (buffer_up_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_down_variants[];
#define buffer_down_icon (buffer_down_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_back_variants[];
#define buffer_back_icon (buffer_back_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_close_variants[];
#define buffer_close_icon (buffer_close_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_sync_variants[];
#define buffer_sync_icon (buffer_sync_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_devices_variants[];
#define buffer_devices_icon (buffer_devices_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_motion_photos_on_variants[];
#define buffer_motion_photos_on_icon (buffer_motion_photos_on_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_bluetooth_variants[];
#define buffer_bluetooth_icon (buffer_bluetooth_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_pause_variants[];
#define buffer_pause_icon (buffer_pause_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_play_arrow_variants[];
#define buffer_play_arrow_icon (buffer_play_arrow_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_restart_alt_variants[];
#define buffer_restart_alt_icon (buffer_restart_alt_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_check_box_variants[];
#define buffer_check_box_icon (buffer_check_box_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_autorenew_variants[];
#define buffer_autorenew_icon (buffer_autorenew_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_devices_other_variants[];
#define buffer_devices_other_icon (buffer_devices_other_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_animation_variants[];
#define buffer_animation_icon (buffer_animation_variants[12].image)
extern const struct buffer_ui_symbol_variant_s buffer_devices_off_variants[];
#define buffer_devices_off_icon (buffer_devices_off_variants[12].image)
/* Resolve the owning control's on-color in the draw descriptor. LVGL can
 * change combined states without delivering STYLE_CHANGED to children (for
 * example a pressed shadow), so draw-time resolution covers every state and
 * needs no timer, style mutation, or callback retaining a deleted child. */
static void buffer_ui_icon_draw(lv_event_t *event)
{
 lv_draw_image_dsc_t *draw=lv_draw_task_get_image_dsc(lv_event_get_draw_task(event));
 if(!draw)return;
 lv_obj_t *image=lv_event_get_target(event);
 draw->recolor=lv_obj_get_style_text_color(lv_obj_get_parent(image),0);
 draw->recolor_opa=LV_OPA_COVER;
}
/* Native masks are selected after integer dp layout has resolved. */
static inline void buffer_ui_icon_set_src(lv_obj_t *image,const lv_image_dsc_t *source)
{
 int pixels=lv_obj_get_width(image),height=lv_obj_get_height(image);
 if(height<pixels)pixels=height;
 if(pixels<1)pixels=buffer_ui_dp(BUFFER_GLYPH_DP);
 const lv_image_dsc_t *native=buffer_ui_icon_source(source,pixels);
 if(lv_image_get_src(image)!=native)lv_image_set_src(image,native);
 lv_image_set_scale(image,LV_SCALE_NONE);
 lv_image_set_inner_align(image,native&&native->header.w==(unsigned)pixels?LV_IMAGE_ALIGN_CENTER:LV_IMAGE_ALIGN_STRETCH);
}
static void buffer_ui_icon_size_changed(lv_event_t *event)
{
 lv_obj_t *image=lv_event_get_target(event);
 buffer_ui_icon_set_src(image,lv_image_get_src(image));
}
static inline lv_obj_t *buffer_ui_icon_create(lv_obj_t *parent,const lv_image_dsc_t *source)
{
 lv_obj_t *image=lv_image_create(parent);lv_image_set_src(image,source);
 lv_image_set_inner_align(image,LV_IMAGE_ALIGN_CENTER);
 lv_obj_add_event_cb(image,buffer_ui_icon_size_changed,LV_EVENT_SIZE_CHANGED,NULL);
 lv_obj_set_size(image,buffer_ui_dp(BUFFER_GLYPH_DP),buffer_ui_dp(BUFFER_GLYPH_DP));
 buffer_ui_icon_set_src(image,source);
 lv_obj_remove_flag(image,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_add_flag(image,LV_OBJ_FLAG_SEND_DRAW_TASK_EVENTS);
 lv_obj_add_event_cb(image,buffer_ui_icon_draw,LV_EVENT_DRAW_TASK_ADDED,NULL);
 return image;
}
static inline lv_obj_t *buffer_ui_icon_button(lv_obj_t *button,const lv_image_dsc_t *source)
{
 lv_obj_t *label=lv_obj_get_child(button,0);
 if(label && lv_obj_check_type(label,&lv_label_class)) lv_obj_add_flag(label,LV_OBJ_FLAG_HIDDEN);
 /* A hidden text caption must not retain a large-text minimum width. */
 lv_obj_set_style_min_width(button,buffer_ui_dp(48),0);
 lv_obj_set_style_min_height(button,buffer_ui_dp(48),0);
 lv_obj_set_style_pad_all(button,0,0);
 lv_obj_t *image=buffer_ui_icon_create(button,source);lv_obj_center(image);return image;
}

#endif
