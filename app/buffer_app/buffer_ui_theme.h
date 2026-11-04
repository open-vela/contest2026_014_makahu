/* Material 3 color roles, aligned with the Android Buffer theme. */
#ifndef BUFFER_UI_THEME_H
#define BUFFER_UI_THEME_H
#include "buffer_ui_units.h"

#define BUFFER_BG 0xF6FAF6
#define BUFFER_PANEL 0xEEEEE6
#define BUFFER_ACCENT 0x396552
#define BUFFER_MUTED 0x414942
#define BUFFER_WARNING 0x705D00
#define BUFFER_ON_SURFACE 0x181C18
#define BUFFER_PRIMARY_CONTAINER 0xBDEED5
#define BUFFER_PRIMARY_CONTAINER_PRESSED 0xA9D4BA
#define BUFFER_ERROR 0xBA1A1A
#define BUFFER_ERROR_PRESSED 0x930D0D
#define BUFFER_ON_PRIMARY 0xFFFFFF
#define BUFFER_PRIMARY_PRESSED 0x2C523E
#define BUFFER_DISABLED_CONTAINER BUFFER_PANEL
#define BUFFER_ON_DISABLED BUFFER_MUTED
#define BUFFER_TYPE_H1 (buffer_ui_sp(32))
#define BUFFER_TYPE_H2 (buffer_ui_sp(22))
#define BUFFER_TYPE_H3 (buffer_ui_sp(16))
#define BUFFER_TYPE_BODY (buffer_ui_sp(16))
#define BUFFER_TYPE_CAPTION (buffer_ui_sp(14))
#define BUFFER_PRESSED_RADIUS (buffer_ui_dp(12))
#define BUFFER_OOBE_DECOR 0xEFF5EF
#define BUFFER_OOBE_ARC 0xD7E6DA

/* User SVGs use a 320x240 S1 reference at 130 DPI. Convert once to dp:
 * 8px -> 10dp, 16px -> 20dp, 24px glyph -> 30dp. Other densities
 * apply buffer_ui_dp at the rendering boundary, never fixed pixel sizes. */
#define BUFFER_GLYPH_DP 30
#define BUFFER_INNER_RADIUS (buffer_ui_dp(10))
#define BUFFER_PAGE_PADDING (buffer_ui_dp(10))
#define BUFFER_SECTION_GAP (buffer_ui_dp(10))
#define BUFFER_PANEL_GAP (buffer_ui_dp(10))
#define BUFFER_CARD_PADDING (buffer_ui_dp(10))
#define BUFFER_CARD_RADIUS (buffer_ui_dp(20))
#define BUFFER_BUTTON_RADIUS (buffer_ui_dp(20))
#define BUFFER_RECORD_RADIUS (buffer_ui_dp(20))

#endif
