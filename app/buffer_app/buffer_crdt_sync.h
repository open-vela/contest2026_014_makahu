/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_CRDT_SYNC_H
#define BUFFER_CRDT_SYNC_H
#include "buffer_crdt.h"
/* Caller supplies the actual authenticated channel peer and generation, never
 * wire fields. Recheck the live grant before sending a queued part. These
 * routines do not acquire Fabric locks or infer authorization from binding. */
int buffer_crdt_sync_next(const char *peer,unsigned generation,uint64_t now,size_t budget,char **message);
int buffer_crdt_sync_ack(const char *peer,unsigned generation,const cJSON *reply);
/* RAM-only policy check: -EAGAIN means a storage worker owns the transfer. */
int buffer_crdt_sync_pending_check(const char *peer,unsigned generation,uint64_t now);
bool buffer_crdt_sync_pending_allowed(const char *peer,unsigned generation,uint64_t now);
void buffer_crdt_sync_reset(void);
void buffer_crdt_sync_close_session(const char *peer,unsigned generation);
int buffer_replica_crdt_action(const char *id,const char *action);
int buffer_replica_publish_local_action(const char *id,const char *action);
bool buffer_replica_crdt_contains(const char *id);
cJSON *buffer_replica_crdt_record(const cJSON *document,const cJSON *previous);
#endif
