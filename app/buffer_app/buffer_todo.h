/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_TODO_H
#define BUFFER_TODO_H
#include "buffer_app.h"
#define BUFFER_TODO_PAGE 8
struct buffer_todo_item { struct buffer_card_s card; bool pending; bool local; };
/* Stable lexicographic ID cursor, bounded memory; direction +/-1. Empty cursor
 * starts at the corresponding boundary. Every matching task is reachable. */
int buffer_todo_page(const char *cursor, int direction,
                     struct buffer_todo_item *items, unsigned capacity,
                     bool *more);
int buffer_todo_get(const char *id, struct buffer_todo_item *item);
int buffer_request_action_id(const char *card_id, const char *action);
int buffer_todo_ui_attach(void *body);
void buffer_todo_ui_refresh(void);
bool buffer_todo_ui_open(void);
void buffer_todo_ui_set_visible(bool visible);
void buffer_todo_ui_dock_entry(void *toolbar);
void buffer_todo_ui_dock_launcher(void *page, void *launchers);
void buffer_todo_ui_detach(void);
#endif
