/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_VISIBILITY_H
#define BUFFER_SOURCE_VISIBILITY_H
#include "buffer_source_remote.h"
/* Internal store bridge. Every call requires the remote journal lock; clock
 * recovery may already hold the local lock in local -> remote order. Callbacks
 * never acquire either lock or perform network/executor work. Public receive
 * entries read the local head before taking remote. Legacy prefixes freeze. */
struct buffer_source_visibility_store_s {
  const char *local_source;
  int (*base)(const char *source,const char *peer,bool create,uint64_t *sequence);
  int (*read_base)(const char *source,uint64_t sequence,char **raw,size_t *length);
  int (*find)(const char *event_id,cJSON **entry);
  int (*put)(const char *source,uint64_t sequence,const char *event_id,const char *hash);
  int (*observe)(uint64_t clock);
};
int buffer_source_visibility_recover(const struct buffer_source_visibility_store_s *store);
int buffer_source_visibility_total(const struct buffer_source_visibility_store_s *store,uint64_t observed,uint64_t *count);
int buffer_source_visibility_active(const char *source,bool *active);
int buffer_source_visibility_progress(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                      struct buffer_source_visibility_progress_s *progress);
int buffer_source_visibility_reserve(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                     uint64_t sequence,const char *token,bool (*authorize)(void *),void *context,
                                     struct buffer_source_visibility_import_s *result);
int buffer_source_visibility_receive(const struct buffer_source_visibility_store_s *store,const char *peer,const char *raw,size_t length,
                                     const char *token,bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *result);
int buffer_source_visibility_read(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,uint64_t sequence,char **raw,size_t *length);
/* Local projection enumeration under the remote lock. Range is committed;
 * reservations still require an actual original before any view is built. */
int buffer_source_visibility_projection_range(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                               uint64_t *base,uint64_t *covered,uint64_t *records,char directory[320]);
int buffer_source_visibility_next(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                  uint64_t *cursor,uint64_t *sequence,char token[33]);
/* Bounded continuation of the common ID index rebuild, after legacy prefixes.
 * The expected count counts originals, never reservations. */
int buffer_source_visibility_build(const struct buffer_source_visibility_store_s *store,uint64_t expected,bool *done);
int buffer_source_visibility_build_clear(void);
#endif
