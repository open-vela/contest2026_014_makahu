/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_INDEX_H
#define BUFFER_SOURCE_INDEX_H
#include "buffer_agent.h"
/* Internal derived index. All calls require the remote journal lock; no
 * callbacks and no calls back into the local/remote journal are made here.
 * Missing referenced nodes are corruption, never evidence of absence. get
 * returns EAGAIN until the initial committed-history coverage is complete. */
int buffer_source_index_open(bool *ready, uint64_t *count);
int buffer_source_index_get(const char *event_id, cJSON **entry);
int buffer_source_index_put(const cJSON *entry);
int buffer_source_index_finish(uint64_t expected_count);
#endif
