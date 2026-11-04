/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_BACKLIGHT_H
#define BUFFER_BACKLIGHT_H
#include <stddef.h>
#include <stdbool.h>
/* Tick/shutdown belong to the service timer worker, never the LVGL thread. */
void buffer_backlight_tick(void);
void buffer_backlight_shutdown(void);
void buffer_backlight_snapshot(char *text,size_t capacity);
/* Thread-safe UI request; hardware I/O is consumed by the service worker. */
int buffer_backlight_request(int percent);
int buffer_backlight_level(int *percent,bool *pending);
#endif
