/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_DESKTOP_H
#define BUFFER_UI_DESKTOP_H
#include <lvgl/lvgl.h>
#include <stdbool.h>
#include <stdint.h>
#define BUFFER_DESKTOP_TILES 40
#define BUFFER_DESKTOP_KEY 80
struct buffer_ui_desktop;
typedef void (*buffer_desktop_laid_out)(void *context);
/* The Shell-owned desktop allocates grid positions. Content declares identity
 * and preferred cell footprint, never screen coordinates. All calls run on
 * the LVGL owner thread. The handle expires when its viewport is deleted. */
struct buffer_ui_desktop *buffer_ui_desktop_create(lv_obj_t *viewport,lv_obj_t *window,
                        buffer_desktop_laid_out laid_out,void *context);
void buffer_ui_desktop_destroy(struct buffer_ui_desktop *desktop);
int buffer_ui_desktop_bind(struct buffer_ui_desktop *desktop,lv_obj_t *content,
                           const char *key,unsigned columns,unsigned rows);
/* Full-height horizontal panels / full-width vertical panels. Preferred
 * widths remain logical dp; large text can expand a panel up to the viewport. */
void buffer_ui_desktop_strip(struct buffer_ui_desktop *desktop,bool enabled);
int buffer_ui_desktop_panel(struct buffer_ui_desktop *desktop,lv_obj_t *content,
                            const char *key,unsigned preferred_width_dp);
bool buffer_ui_desktop_reflow(struct buffer_ui_desktop *desktop);
bool buffer_ui_desktop_reveal(struct buffer_ui_desktop *desktop,const char *key);
const char *buffer_ui_desktop_anchor(struct buffer_ui_desktop *desktop);
#endif
