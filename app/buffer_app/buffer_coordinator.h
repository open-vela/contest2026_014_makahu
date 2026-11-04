/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_COORDINATOR_H
#define BUFFER_COORDINATOR_H
#include "buffer_agent.h"
/* Application job ownership, separate from Fabric Session epoch/leadership. */
int buffer_coordinator_bind(const char *local_device,const char *phone_device);
/* The device's own approved-controller roster, separate from the Android
 * model-task group and from the individual Fabric bearer Session. */
#define BUFFER_COORDINATOR_MAX_MEMBERS 65
int buffer_coordinator_view_digest(const char *local,const char members[][65],unsigned count,uint64_t revision,char digest[65]);
int buffer_coordinator_bind_view(const char *local,const char *peer,const char members[][65],unsigned count,uint64_t revision);
void buffer_coordinator_connected(bool connected);
void buffer_coordinator_peer_connected(const char *peer,bool connected);
int buffer_coordinator_peer_heartbeat(const char *peer,uint64_t now,char **message);
int buffer_coordinator_receive(const char *authenticated_device,const cJSON *heartbeat);
int buffer_coordinator_heartbeat(uint64_t now,char **message);
bool buffer_coordinator_local(void);
uint64_t buffer_coordinator_fence(void);
bool buffer_coordinator_fence_current(uint64_t token);
/* The caller holds the capture lock. Keep the owner fence through a short
 * local result/checkpoint write; never run a model or external tool here. */
int buffer_coordinator_local_commit(uint64_t token,int (*commit)(void *context),void *context);
/* Atomically verify a live authenticated controller owner and its current
 * term. The legacy phone API name also covers tablet/Vela/other controllers.
 * Used before accepting delegated device-assistant work. */
bool buffer_coordinator_phone_owner_current(const char *authenticated_phone,
                                            uint64_t expected_term);
/* Serialize the final owner/term check with a short durable acceptance
 * callback, so a newer heartbeat cannot fence the capture between check/write. */
int buffer_coordinator_phone_owner_commit(const char *authenticated_phone,
                                          uint64_t expected_term,
                                          int (*commit)(void *context),
                                          void *context);
void buffer_coordinator_status(char *owner,size_t size,uint64_t *term);
/* Radio-only observation: never waits for durable owner commits or runs an
 * election. EAGAIN means defer owner-dependent work, not transport polling.
 * Actual application commits must still use the authoritative APIs above. */
int buffer_coordinator_transport_view(char *owner,size_t size,uint64_t *term,bool *remote_live);
#endif
