/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_PREFERENCES_H
#define BUFFER_UI_PREFERENCES_H
#include <stdbool.h>
#include <stdint.h>
enum buffer_ui_preference {BUFFER_UI_PREF_FONT=1,BUFFER_UI_PREF_PRIVATE=2,BUFFER_UI_PREF_REDUCED=4};
struct buffer_ui_preferences {
 unsigned font_scale;bool private_previews,reduced_motion;
 uint64_t revision,persisted_revision;
 bool loaded,pending,saving;int error;
};
/* All file/mount operations belong to the private worker. Settings made
 * during startup win over older disk values, independently for each field. */
int buffer_ui_preferences_start(unsigned default_font_scale);
int buffer_ui_preferences_set(enum buffer_ui_preference field,unsigned value);
int buffer_ui_preferences_snapshot(struct buffer_ui_preferences *out);
void buffer_ui_preferences_retry(void);
void buffer_ui_preferences_shutdown(void);
#endif
