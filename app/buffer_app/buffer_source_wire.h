/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_WIRE_H
#define BUFFER_SOURCE_WIRE_H
#include "buffer_agent.h"
/* Source pull/push service, owned exclusively by the application worker.
 * Call without the Fabric/transport lock. Each reply has its own live grant
 * check; a carrier ACK is never treated as a source receive receipt. */
int buffer_source_wire_handle(const struct buffer_oobe_authorization_s *auth,
                               const char *peer, unsigned generation,
                               size_t budget, const cJSON *request, char **reply);
/* Application worker only, including shutdown; never from an I/O callback. */
void buffer_source_wire_reset(void);
/* Legacy single-link maintenance. Several links must use session_idle below.
 * Returns whether retained data requires an idle maintenance deadline. */
bool buffer_source_wire_idle(unsigned generation, bool active);
/* Explicit lifecycle for several authenticated links. Only the named peer /
 * generation is revoked; maintenance never takes the transport lock. */
void buffer_source_wire_close_session(const char *peer,unsigned generation);
bool buffer_source_wire_session_idle(const char *peer,unsigned generation,bool active);
#endif
