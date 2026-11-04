/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_CRDT_H
#define BUFFER_CRDT_H
#include "buffer_agent.h"
/* Per-field Lamport LWW registers; deletion is a monotone tombstone.
 * Clocks are logical counters, never wall time. Caller owns returned JSON. */
int buffer_crdt_validate(const cJSON *document);
cJSON *buffer_crdt_values(const cJSON *document);
cJSON *buffer_crdt_merge(const cJSON *left, const cJSON *right);
cJSON *buffer_crdt_update(const cJSON *previous, const char *id,
                           const char *kind, const cJSON *changes,
                           const char *actor, bool deleted);
uint64_t buffer_crdt_clock(const cJSON *document);
#endif
