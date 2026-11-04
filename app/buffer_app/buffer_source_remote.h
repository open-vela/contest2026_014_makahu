/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_REMOTE_H
#define BUFFER_SOURCE_REMOTE_H
#include "buffer_agent.h"
/* Revisit older invalid projections after accepting signed/point calendar instants. */
#define BUFFER_SOURCE_PROJECTION_VERSION 4
#define BUFFER_SOURCE_VIEW_APPLIED "v4_applied"
#define BUFFER_SOURCE_VIEW_UNSUPPORTED "v4_unsupported"
#define BUFFER_SOURCE_VIEW_INVALID "v4_invalid"
#define BUFFER_SOURCE_VIEW_CONFLICT "v4_conflict"
enum buffer_source_import_state_e {
  BUFFER_SOURCE_STORED, BUFFER_SOURCE_DUPLICATE,
  BUFFER_SOURCE_GAP, BUFFER_SOURCE_CONFLICT
};
struct buffer_source_import_s {
  enum buffer_source_import_state_e state;
  uint64_t next_sequence;
};
struct buffer_source_visibility_progress_s {
  uint64_t covered_next,original_next;
};
enum buffer_source_visibility_state_e {
  BUFFER_VISIBILITY_HELD,BUFFER_VISIBILITY_HELD_DUPLICATE,
  BUFFER_VISIBILITY_ORIGINAL_PRESENT,BUFFER_VISIBILITY_STORED,
  BUFFER_VISIBILITY_DUPLICATE,BUFFER_VISIBILITY_GAP,BUFFER_VISIBILITY_CONFLICT
};
struct buffer_source_visibility_import_s {
  enum buffer_source_visibility_state_e state;
  struct buffer_source_visibility_progress_s progress;
};
struct buffer_source_projection_input_s {
  char peer[65],source[97],event_id[161],fingerprint[65];
  uint64_t sequence;
  char *raw;
  size_t length;
  bool applied;
  char projection_state[32];
};
/* Offline, data-only materializer. Fetch releases the remote lock before any
 * business merge. Complete rechecks exact source identity, bytes and queue.
 * next: 1=input, 0=end of pass, EAGAIN=bounded scan continuation. Caller wipes
 * and frees raw. Accepted local history does not need a live network grant. */
/* Offline, bounded direct-parent assessment. Cached transitive verdicts are
 * tied to verified original fingerprints. Unresolved ancestors are revisited
 * on the next source pass; no recursive history scan or command replay. */
int buffer_source_remote_parent_requests(const char *peer,const char *after,unsigned offset,
  bool (*authorize)(void *),void *context,char next[65],unsigned *next_offset,cJSON **ids);
int buffer_source_remote_causal_check(const struct buffer_source_projection_input_s *input,char state[24]);
int buffer_source_remote_projection_restart(void);
int buffer_source_remote_projection_next(struct buffer_source_projection_input_s *input);
int buffer_source_remote_projection_complete(const struct buffer_source_projection_input_s *input,const char *state);
/* Read-only local history, independent of projection progress. Empty hash is
 * the start of source enumeration. A record anchor of zero selects a boundary;
 * otherwise direction +/-1 selects the next earlier/later original, excluding
 * held visibility reservations. No original or delivery state is changed. */
int buffer_source_remote_history_source(const char *after_hash,char next_hash[65]);
int buffer_source_remote_history_read(const char *source_hash,uint64_t anchor,int direction,
                                      struct buffer_source_projection_input_s *input,bool *earlier,bool *later);
/* Explicit visibility-v1 storage entries, not enabled by legacy negotiation.
 * A slot receipt never confirms original bytes or grants replay permission. */
int buffer_source_remote_visibility_progress(const char *peer,const char *source,bool (*authorize)(void *),void *context,
                                              struct buffer_source_visibility_progress_s *progress);
int buffer_source_remote_visibility_reserve(const char *peer,const char *source,uint64_t sequence,const char *token,
                                             bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *result);
int buffer_source_remote_visibility_receive(const char *peer,const char *raw,size_t length,const char *token,
                                             bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *result);
/* cursor=0 starts a pass; success returns the next link. EAGAIN retains a
 * continuation after at most 16 filled tombstones, not a received slot. */
int buffer_source_remote_visibility_next(const char *peer,const char *source,uint64_t *cursor,bool (*authorize)(void *),void *context,
                                          uint64_t *sequence,char token[33]);
/* Internal lock order: local journal -> remote journal. This entry point must
 * be called with neither lock held. authorize must only inspect live channel
 * authorization, never call journal/store APIs. A success is a storage receipt,
 * not a projection result or permission to execute a historical operation.
 * EAGAIN means a bounded derived-index rebuild is still running. Retry on the
 * application worker; this call has not accepted the incoming event. */
int buffer_source_remote_receive(const char *peer, const char *raw, size_t length,
                                 bool (*authorize)(void *), void *context,
                                 struct buffer_source_import_s *result);
/* Used while holding the local journal lock. Recovers an already accepted
 * receive intent and returns the durable maximum observed remote clock. */
int buffer_source_remote_clock(const char *local_source, uint64_t *clock);
/* Handshake progress for this exact authenticated owner, not a general query
 * of other peers' private history. Unknown streams begin at sequence one.
 * May return EAGAIN while advancing the same bounded index rebuild. */
int buffer_source_remote_progress(const char *peer, const char *source,
                                  bool (*authorize)(void *), void *context,
                                  uint64_t *next_sequence);
/* Local projection input only; exact committed bytes, never a command replay.
 * Missing/corrupt committed records fail, rather than reporting end-of-stream.
 * A visibility reservation without original bytes returns EAGAIN. */
int buffer_source_remote_read(const char *source, uint64_t sequence,
                              char **raw, size_t *length);
/* Exact committed bytes belonging to this authenticated, pinned owner.
 * Use for binary bindings; an unowned local read is not authorization. */
int buffer_source_remote_owned_read(const char *peer, const char *source,
                                    uint64_t sequence, bool (*authorize)(void *),
                                    void *context, char **raw, size_t *length);
#endif
