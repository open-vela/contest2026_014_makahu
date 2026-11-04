/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_CONTROLS_H
#define BUFFER_UI_CONTROLS_H
#include <lvgl/lvgl.h>
#include <stdbool.h>
typedef void (*buffer_ui_switch_changed)(bool checked,void *context);
/* A 48dp-or-taller row owns the hit target; its 52x32dp switch is visual. */
lv_obj_t *buffer_ui_switch_create(lv_obj_t *parent,const char *caption,bool checked,
                                  buffer_ui_switch_changed changed,void *context);
void buffer_ui_switch_set(lv_obj_t *row,bool checked);
void buffer_ui_switch_disable(lv_obj_t *row,bool disabled);
lv_obj_t *buffer_ui_slider_create(lv_obj_t *parent,int minimum,int maximum,int value,bool discrete);
#endif
