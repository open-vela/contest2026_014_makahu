/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_FOCUS_H
#define BUFFER_FOCUS_H
#include "buffer_agent.h"
/* Caller owns snapshots. NULL with errno==0 means no session; other NULL
 * results are read/allocation failures. I/O belongs on an application worker. */
cJSON *buffer_focus_session_snapshot(void);
void buffer_focus_request_mode(const char *mode);
int buffer_focus_validate(const cJSON *session, const char *mode);
int buffer_focus_import(const cJSON *session);
cJSON *buffer_focus_execute(const cJSON *arguments, const char *operation_id);
#endif
