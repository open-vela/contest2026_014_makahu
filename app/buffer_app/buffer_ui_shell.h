/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_SHELL_H
#define BUFFER_UI_SHELL_H
#include <lvgl/lvgl.h>
#include <stdbool.h>
#include <stdint.h>

enum buffer_ui_surface_kind { BUFFER_UI_APP, BUFFER_UI_SYSTEM_OVERLAY };
enum buffer_ui_capture_mode {BUFFER_UI_CAPTURE_BOTTOM,BUFFER_UI_CAPTURE_FLOATING,BUFFER_UI_CAPTURE_HIDDEN};
typedef void (*buffer_ui_surface_closed)(void *context);
typedef bool (*buffer_ui_surface_back)(void *context);
typedef int (*buffer_ui_surface_prepare)(const char *object,void *context);
typedef void (*buffer_ui_surface_activated)(bool was_visible,void *context);
#define BUFFER_UI_SURFACE_TEXT 64
#define BUFFER_UI_SURFACE_ACTIONS 16
struct buffer_ui_surface_declaration {
  const char *id,*title,*entry_route;
  enum buffer_ui_surface_kind kind;
  enum buffer_ui_capture_mode capture_mode;
  uint16_t min_width_dp,min_height_dp,preferred_width_dp;
  const char *const *actions;unsigned action_count;
  buffer_ui_surface_closed closed;
  buffer_ui_surface_back back;
  buffer_ui_surface_prepare prepare_open;
  buffer_ui_surface_activated activated;
  void *context;
};
struct buffer_ui_surface_info {
  char id[BUFFER_UI_SURFACE_TEXT],title[BUFFER_UI_SURFACE_TEXT],entry_route[BUFFER_UI_SURFACE_TEXT];
  char actions[BUFFER_UI_SURFACE_ACTIONS][32];unsigned action_count;
  enum buffer_ui_surface_kind kind;
  uint16_t min_width_dp,min_height_dp,preferred_width_dp;
  int32_t width_px,height_px;bool constrained;
};

/* LVGL owner-thread only. Apps declare their identity/content; only Shell
 * reparents, positions and layers global surfaces. Dialogs remain app-local. */
void buffer_ui_shell_init(lv_obj_t *root, lv_obj_t *desktop);
void buffer_ui_shell_shutdown(void);
/* Copies semantic declarations; constraints never enlarge beyond the host.
 * A constrained viewport is reported so the app can use its compact layout. */
bool buffer_ui_shell_declare(const struct buffer_ui_surface_declaration *declaration,lv_obj_t *content);
bool buffer_ui_shell_info(const char *id,struct buffer_ui_surface_info *info);
bool buffer_ui_shell_mount(const char *id, enum buffer_ui_surface_kind kind,
                           lv_obj_t *content, buffer_ui_surface_closed closed,
                           void *context);
/* Apps consume one local route/menu/dialog before Shell closes the surface. */
void buffer_ui_shell_on_back(const char *id,buffer_ui_surface_back handler,void *context);
void buffer_ui_shell_show(const char *id);
/* Prepare/validate a stable object before changing the foreground. The
 * activation callback runs for every accepted open, without replaying the
 * global transition when already visible. Callbacks perform no blocking I/O. */
int buffer_ui_shell_open(const char *entry_route,const char *object);
void buffer_ui_shell_hide(const char *id);
bool buffer_ui_shell_back(void);
void buffer_ui_shell_home(void);
bool buffer_ui_shell_is_open(const char *id);
bool buffer_ui_shell_has_app(void);
const char *buffer_ui_shell_foreground(void);
void buffer_ui_shell_layout(void);
void buffer_ui_shell_capture(lv_obj_t *capture,lv_obj_t *preview);
void buffer_ui_shell_capture_dock(lv_obj_t *dock);
void buffer_ui_shell_capture_expanded(bool expanded);
void buffer_ui_shell_capture_mode(const char *id,enum buffer_ui_capture_mode mode);
/* Persistent system status strip. Shell reserves its height above desktop
 * and apps and owns top-edge pull-down; overlays cover it. A pointer tap is
 * passive, while keypad/encoder activation provides an accessible entry. */
bool buffer_ui_shell_statusbar(lv_obj_t *bar,const char *overlay_route);
void buffer_ui_shell_focus_refresh(void);
/* App-local route/menu focus bookmarks. Apps retain layout/lifecycle
 * ownership; Shell keeps weak return targets and restores only foreground
 * controls. An override resolves a stable object after list reordering. */
bool buffer_ui_shell_local_begin(const char *id,lv_obj_t *return_focus);
void buffer_ui_shell_local_end(const char *id,lv_obj_t *override_focus);
#endif
