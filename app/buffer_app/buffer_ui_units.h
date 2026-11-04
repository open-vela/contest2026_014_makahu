/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_UNITS_H
#define BUFFER_UI_UNITS_H
#include <lvgl/lvgl.h>
#include <stdint.h>

/* Layout tokens are dp (160 dpi baseline). LVGL coordinates, measured object
 * sizes, pointer positions and animation values remain pixels at its boundary.
 * Use the display's configured logical DPI; do not infer physical size from
 * resolution, or apply density again to measured coordinates/percentages. */
static inline int32_t buffer_ui_dp(int32_t value)
{
  lv_display_t *display = lv_display_get_default();
  uint32_t dpi = display ? lv_display_get_dpi(display) : LV_DPI_DEF;
  if (!dpi) dpi = 160;
  int64_t scaled = (int64_t)value * dpi;
  int32_t pixels = (int32_t)((scaled + (scaled < 0 ? -80 : 80)) / 160);
  return value && !pixels ? (value < 0 ? -1 : 1) : pixels;
}

/* sp applies text scaling independently; layout dp and pixel buffers do not. */
int32_t buffer_ui_scaled_sp(int32_t value);
static inline int32_t buffer_ui_sp(int32_t value)
{ return buffer_ui_scaled_sp(value); }
#endif
