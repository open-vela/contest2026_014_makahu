/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_TYPOGRAPHY_H
#define BUFFER_UI_TYPOGRAPHY_H
#include <lvgl/lvgl.h>
#include <stdbool.h>
#include <stdint.h>

enum buffer_ui_type_role {
  BUFFER_TEXT_DISPLAY_LARGE, BUFFER_TEXT_DISPLAY_MEDIUM, BUFFER_TEXT_DISPLAY_SMALL,
  BUFFER_TEXT_HEADLINE_LARGE, BUFFER_TEXT_HEADLINE_MEDIUM, BUFFER_TEXT_HEADLINE_SMALL,
  BUFFER_TEXT_TITLE_LARGE, BUFFER_TEXT_TITLE_MEDIUM, BUFFER_TEXT_TITLE_SMALL,
  BUFFER_TEXT_BODY_LARGE, BUFFER_TEXT_BODY_MEDIUM, BUFFER_TEXT_BODY_SMALL,
  BUFFER_TEXT_LABEL_LARGE, BUFFER_TEXT_LABEL_MEDIUM, BUFFER_TEXT_LABEL_SMALL,
  BUFFER_TEXT_BASE_COUNT,
  BUFFER_TEXT_DISPLAY_LARGE_EMPHASIZED=BUFFER_TEXT_BASE_COUNT,
  BUFFER_TEXT_DISPLAY_MEDIUM_EMPHASIZED, BUFFER_TEXT_DISPLAY_SMALL_EMPHASIZED,
  BUFFER_TEXT_HEADLINE_LARGE_EMPHASIZED, BUFFER_TEXT_HEADLINE_MEDIUM_EMPHASIZED,
  BUFFER_TEXT_HEADLINE_SMALL_EMPHASIZED, BUFFER_TEXT_TITLE_LARGE_EMPHASIZED,
  BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED, BUFFER_TEXT_TITLE_SMALL_EMPHASIZED,
  BUFFER_TEXT_BODY_LARGE_EMPHASIZED, BUFFER_TEXT_BODY_MEDIUM_EMPHASIZED,
  BUFFER_TEXT_BODY_SMALL_EMPHASIZED, BUFFER_TEXT_LABEL_LARGE_EMPHASIZED,
  BUFFER_TEXT_LABEL_MEDIUM_EMPHASIZED, BUFFER_TEXT_LABEL_SMALL_EMPHASIZED,
  BUFFER_TEXT_ROLE_COUNT
};
struct buffer_ui_type_metrics {
  uint8_t size_sp, line_sp;
  uint16_t weight;
  int16_t tracking_hundredths_sp;
};
const struct buffer_ui_type_metrics *buffer_ui_type_metrics(enum buffer_ui_type_role role);
/* Owner-thread APIs. Font scale changes are transactional: on a load failure,
 * the existing fonts and published scale remain valid. */
int buffer_ui_typography_init(void);
void buffer_ui_typography_shutdown(void);
bool buffer_ui_typography_ready(void);
int buffer_ui_typography_scale(unsigned percent);
unsigned buffer_ui_font_scale(void);
int32_t buffer_ui_scaled_sp(int32_t value);
const lv_font_t *buffer_ui_font(enum buffer_ui_type_role role);
void buffer_ui_text_role(lv_obj_t *object, enum buffer_ui_type_role role);
/* Compatibility for small standalone fixtures and custom font providers. */
void buffer_ui_type_fonts(const lv_font_t *h1,const lv_font_t *h2,
                          const lv_font_t *h3,const lv_font_t *body,const lv_font_t *label);
void buffer_ui_type(lv_obj_t *object,unsigned legacy_variant);
void buffer_ui_button_label(lv_obj_t *label);
void buffer_ui_control_label_reflow(lv_obj_t *label);
#endif
