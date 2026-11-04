/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_ARTWORK_H
#define BUFFER_UI_ARTWORK_H
#include <lvgl/lvgl.h>
/* The fixed artwork lookup is a visual-fixture helper only. */
const lv_image_dsc_t *buffer_ui_recipe_artwork(const char *image_id);
/* The frame owns rounded child clipping; the image may stretch independently. */
lv_obj_t *buffer_ui_recipe_image_create(lv_obj_t *parent,int32_t radius,lv_obj_t **frame);
bool buffer_ui_recipe_image_bind(lv_obj_t *image,const char *image_id);
bool buffer_ui_recipe_image_ready(lv_obj_t *image);
#endif
