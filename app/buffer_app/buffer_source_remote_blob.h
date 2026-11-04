/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_REMOTE_BLOB_H
#define BUFFER_SOURCE_REMOTE_BLOB_H
#include "buffer_agent.h"
/* Application worker only. Partial prefixes are volatile transfer state;
 * only a complete hash-verified private archive earns a durable receipt. */
int buffer_source_remote_blob_handle(const struct buffer_oobe_authorization_s *auth,
                                     const char *peer,unsigned generation,size_t budget,
                                     const cJSON *request,char **reply);
/* Reset only the currently selected transfer; worker shutdown uses reset_all. */
void buffer_source_remote_blob_reset(void);
/* Compatibility for single-link callers; use session_idle for several links. */
bool buffer_source_remote_blob_idle(unsigned generation,bool active);
void buffer_source_remote_blob_close_session(const char *peer,unsigned generation);
void buffer_source_remote_blob_reset_all(void);
bool buffer_source_remote_blob_session_idle(const char *peer,unsigned generation,bool active);
#endif
