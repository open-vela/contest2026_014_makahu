/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_LAYOUT_H
#define BUFFER_UI_LAYOUT_H

#include <lvgl/lvgl.h>
#include <string.h>
#include "buffer_ui_units.h"
#include "buffer_ui_motion.h"

/* LVGL's flag setters invalidate even when HIDDEN was already in the
 * requested state. Polling views must avoid that full layout/draw churn. */
static inline void buffer_ui_add_flag(lv_obj_t *obj, lv_obj_flag_t flags)
{
  if (!lv_obj_has_flag(obj, flags)) lv_obj_add_flag(obj, flags);
}
static inline void buffer_ui_remove_flag(lv_obj_t *obj, lv_obj_flag_t flags)
{
  if (lv_obj_has_flag_any(obj, flags)) lv_obj_remove_flag(obj, flags);
}

#define BUFFER_UI_COLOR_SETTER(name, prop) \
static inline void buffer_ui_style_##name(lv_obj_t *obj, lv_color_t value, lv_style_selector_t selector) \
{ \
  lv_style_value_t old; \
  if (lv_obj_get_local_style_prop(obj, prop, &old, selector) == LV_STYLE_RES_FOUND && \
      lv_color_eq(old.color, value)) return; \
  lv_obj_set_style_##name(obj, value, selector); \
}
BUFFER_UI_COLOR_SETTER(bg_color, LV_STYLE_BG_COLOR)
BUFFER_UI_COLOR_SETTER(text_color, LV_STYLE_TEXT_COLOR)
BUFFER_UI_COLOR_SETTER(image_recolor, LV_STYLE_IMAGE_RECOLOR)
#undef BUFFER_UI_COLOR_SETTER

static inline void buffer_ui_style_bg_opa(lv_obj_t *obj, lv_opa_t value, lv_style_selector_t selector)
{
  lv_style_value_t old;
  if (lv_obj_get_local_style_prop(obj, LV_STYLE_BG_OPA, &old, selector) == LV_STYLE_RES_FOUND &&
      old.num == value) return;
  lv_obj_set_style_bg_opa(obj, value, selector);
}

/* Keep unchanged labels out of LVGL's allocation, measurement and draw paths.
 * lv_label_set_text itself unconditionally invalidates and reallocates. */
void buffer_ui_label_set_text(lv_obj_t *label, const char *text);
const char *buffer_ui_label_source_text(lv_obj_t *label);

struct buffer_ui_viewport
{
  int32_t width, height, padding, gap, control;
  bool short_screen, landscape;
};

static inline struct buffer_ui_viewport buffer_ui_viewport(lv_obj_t *root)
{
  int32_t w = lv_obj_get_width(root), h = lv_obj_get_height(root);
  bool compact = w < buffer_ui_dp(280) || h < buffer_ui_dp(360);
  return (struct buffer_ui_viewport){w, h, buffer_ui_dp(compact ? 6 : 12),
      buffer_ui_dp(compact ? 4 : 8), buffer_ui_dp(48), h < buffer_ui_dp(360), w > h};
}

/* One launcher structure owns icon/label spacing and alignment on every page. */
bool buffer_ui_launcher_identity(lv_obj_t *root,const char *id);
const char *buffer_ui_launcher_id(lv_obj_t *root);
static inline lv_obj_t *buffer_ui_launcher_attach(lv_obj_t *parent,
                                                  lv_obj_t *button,
                                                  const char *id,
                                                  const char *caption)
{
  lv_obj_t *column=lv_obj_create(parent);lv_obj_remove_style_all(column);
  (void)buffer_ui_launcher_identity(column,id);
  lv_obj_set_size(column,buffer_ui_dp(80),LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(column,LV_FLEX_FLOW_COLUMN);
  lv_obj_set_flex_align(column,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_row(column,buffer_ui_dp(8),0);
  lv_obj_remove_flag(column,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_parent(button,column);buffer_ui_launcher_style(button);
  lv_obj_t *old=lv_obj_get_child(button,0);
  if(old&&lv_obj_check_type(old,&lv_label_class))lv_obj_add_flag(old,LV_OBJ_FLAG_HIDDEN);
  lv_obj_t *label=lv_label_create(column);buffer_ui_text_role(label,BUFFER_TEXT_LABEL_LARGE);
  buffer_ui_label_set_text(label,caption);
  return column;
}
#endif
