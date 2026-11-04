/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_MOTION_H
#define BUFFER_UI_MOTION_H
#include <lvgl/lvgl.h>
#include "buffer_motion.h"
#include "buffer_ui_typography.h"
void buffer_ui_motion_init(void);
void buffer_ui_motion_shutdown(void);
void buffer_ui_motion_policy(bool reduced, bool low_load);
bool buffer_ui_motion_reduced(void);
void buffer_ui_motion_cancel(lv_obj_t *object);
void buffer_ui_motion_enter(lv_obj_t *object, int direction, bool page);
void buffer_ui_motion_enter_vertical(lv_obj_t *object, int direction);
void buffer_ui_motion_height(lv_obj_t *object, int from, int to);
void buffer_ui_motion_effect(lv_obj_t *object);
void buffer_ui_button_style(lv_obj_t *object, bool primary);
void buffer_ui_button_shape(lv_obj_t *object, unsigned radius_dp, unsigned pressed_dp);
void buffer_ui_segment_shape(lv_obj_t *object, bool first, bool last);
void buffer_ui_corners(lv_obj_t *object,unsigned top_dp,unsigned bottom_dp);
void buffer_ui_launcher_style(lv_obj_t *object);
#endif
