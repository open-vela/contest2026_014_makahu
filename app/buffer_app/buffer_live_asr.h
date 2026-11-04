/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_LIVE_ASR_H
#define BUFFER_LIVE_ASR_H
#include <stddef.h>
void buffer_live_asr_begin(void);
void buffer_live_asr_feed(const void *pcm, size_t bytes);
void buffer_live_asr_end(void);
void buffer_live_asr_snapshot(char *text, size_t capacity);
void buffer_live_asr_shutdown(void);
#endif
