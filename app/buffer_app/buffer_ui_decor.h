/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_DECOR_H
#define BUFFER_UI_DECOR_H
#include <lvgl/lvgl.h>
enum buffer_ui_surface_decor {BUFFER_DECOR_NONE,BUFFER_DECOR_SESSION,BUFFER_DECOR_LISTENING};
enum buffer_ui_bubble_corner {BUFFER_BUBBLE_REQUEST,BUFFER_BUBBLE_REPLY,BUFFER_BUBBLE_CONTINUATION};
/* Coordinates are measured pixels. These paint adapters create no input or
 * layout objects and keep the SVG's artwork separate from app navigation. */
void buffer_ui_surface_decor(lv_obj_t *object,enum buffer_ui_surface_decor decor,int top_inset);
void buffer_ui_bubble_corner(lv_obj_t *object,enum buffer_ui_bubble_corner corner);
#endif
