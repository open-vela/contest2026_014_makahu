/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_HISTORY_H
#define BUFFER_HISTORY_H
#include <stdbool.h>
/* LVGL owner thread only. All archive reads and formatting run on a worker.
 * This is a local, read-only view of accepted originals, not command replay. */
int buffer_history_ui_attach(void *page,void *launchers);
void buffer_history_ui_refresh(void);
bool buffer_history_ui_open(void);
void buffer_history_ui_detach(void);
#endif
