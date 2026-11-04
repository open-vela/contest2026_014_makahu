/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_VOICE_H
#define BUFFER_VOICE_H
#include "buffer_app.h"
#include <stdio.h>
#include <string.h>
/* Caller already owns runtime.lock at capture creation. Old jobs cannot
 * overwrite the next capture's presentation, including on delayed retries. */
static inline void buffer_voice_begin_locked(void)
{
 memset(&g_buffer.voice,0,sizeof(g_buffer.voice));
 snprintf(g_buffer.voice.id,sizeof(g_buffer.voice.id),"%s",g_buffer.record_id);
 g_buffer.voice.phase=BUFFER_VOICE_RECORDING;
}
static inline void buffer_voice_update(const char *id,enum buffer_voice_phase phase,const char *transcript,int error)
{
 if(!id||!*id)return;
 pthread_mutex_lock(&g_buffer.lock);
 if(!strcmp(id,g_buffer.voice.id)){
  g_buffer.voice.phase=phase;g_buffer.voice.error=error;
  if(transcript)snprintf(g_buffer.voice.transcript,sizeof(g_buffer.voice.transcript),"%s",transcript);
 }
 pthread_mutex_unlock(&g_buffer.lock);
}
/* UI must retain its last coherent view when runtime is busy. */
static inline bool buffer_voice_snapshot(struct buffer_voice_status *snapshot)
{
 if(pthread_mutex_trylock(&g_buffer.lock))return false;
 *snapshot=g_buffer.voice;pthread_mutex_unlock(&g_buffer.lock);return true;
}
/* Background history work yields while a foreground capture is in flight.
 * Never wait on the runtime mutex just to decide whether to start NAND I/O. */
static inline bool buffer_voice_foreground_busy(void)
{
 if(pthread_mutex_trylock(&g_buffer.lock))return true;
 bool busy=g_buffer.recording||g_buffer.touch_requested||
   (g_buffer.voice.id[0]&&g_buffer.voice.phase<=BUFFER_VOICE_EXECUTING);
 pthread_mutex_unlock(&g_buffer.lock);return busy;
}
#endif
