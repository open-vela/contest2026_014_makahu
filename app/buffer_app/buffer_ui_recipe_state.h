/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_RECIPE_STATE_H
#define BUFFER_UI_RECIPE_STATE_H
#include <stdbool.h>
#include <stdint.h>
#define BUFFER_RECIPE_SAVED 3
struct buffer_ui_recipe_progress {
 char surface[64],fingerprint[17],operation_id[64],capture_id[64];
 unsigned count,current,done;bool started,overview;
};
struct buffer_ui_recipe_store_status {bool loaded,pending;int error;};
/* File I/O belongs exclusively to the store worker. UI calls never wait for
 * filesystem access; exact semantic fingerprints fence obsolete progress. */
int buffer_ui_recipe_state_start(void);
int buffer_ui_recipe_state_get(const char *surface,const char *fingerprint,struct buffer_ui_recipe_progress *out);
int buffer_ui_recipe_state_slot(unsigned index,struct buffer_ui_recipe_progress *out);
int buffer_ui_recipe_state_set(const struct buffer_ui_recipe_progress *progress);
int buffer_ui_recipe_state_status(struct buffer_ui_recipe_store_status *out);
void buffer_ui_recipe_state_retry(void);
void buffer_ui_recipe_state_shutdown(void);
#endif
