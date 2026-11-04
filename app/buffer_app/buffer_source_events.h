/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_EVENTS_H
#define BUFFER_SOURCE_EVENTS_H
#include "buffer_agent.h"
/* Distinguish a committed local projection removal from a missing cache.
 * Reads the authoritative local event; never infers deletion from ENOENT. */
int buffer_source_projection_local_deleted(const char *path,bool *deleted);
/* Authoritative local observation without merging/restoring remote caches.
 * ENOENT also covers a committed local removal; query local_deleted first. */
cJSON *buffer_source_projection_local_read(const char *path,size_t limit);
#define BUFFER_SOURCE_INPUT_RESULT_BYTES (3 * BUFFER_AGENT_JSON_BYTES)
#define BUFFER_SOURCE_EVENT_BYTES (1024 * 1024)
#define BUFFER_SOURCE_INTENT_BYTES (2 * BUFFER_SOURCE_EVENT_BYTES)
/* One local immutable source stream. Radio/LVGL callbacks must not do this I/O. */
/* Initialize base storage. Derived migration may still be pending; ordinary
 * journal operations retain their own readiness checks. */
int buffer_source_events_init(void);
/* Full derived readiness; EAGAIN is retryable and does not grant authority. */
int buffer_source_events_ready(void);
/* Nonblocking background continuation; 1 when queued index work completes. */
int buffer_source_events_maintenance(void);
bool buffer_source_is_record_path(const char *path);
int buffer_source_record_save(const char *path, const cJSON *record);
cJSON *buffer_source_record_read(const char *path, size_t limit);
/* Typed local projections. Recovery writes files only; it never executes a
 * card action, mode request, notification or session operation. */
bool buffer_source_is_projection_path(const char *path);
int buffer_source_projection_save(const char *path, const char *raw,
                                  const char *event_type, const char *action);
int buffer_source_projection_json(const char *path, const cJSON *value,
                                  const char *event_type, const char *action);
int buffer_source_projection_legacy(const char *path, const cJSON *value);
cJSON *buffer_source_projection_read(const char *path, size_t limit);
int buffer_source_projection_read_bytes(const char *path, size_t limit,
                                        char **raw, size_t *length);
int buffer_source_projection_remove(const char *path, const char *event_type);
/* Enumerate committed local entity identities from source history, including
 * tombstones. Each subsequent read decides whether that entity is live. */
int buffer_source_projection_ids(const char *kind, const char *after,
                                 unsigned limit, cJSON **ids);
/* Local historical idempotency lookup, independent of the small runtime ring.
 * Returned JSON is data owned by the caller; this never executes an action. */
int buffer_source_assistant_operation(const char *operation_id,
                                      cJSON **operation, cJSON **request);
/* Exact focus intent/result independent of the bounded runtime cache. */
int buffer_source_focus_operation(const char *operation_id,cJSON **request,cJSON **result);
int buffer_source_capture_ensure(const char *capture_id);
/* Exact persisted UTF-8, never regenerated JSON. Caller owns *raw. The source
 * stream is local only; receiving/forwarding authorization is separate. */
int buffer_source_local_head(char *source, size_t capacity, uint64_t *sequence);
int buffer_source_local_read(uint64_t sequence, char **raw, size_t *length);
/* Local cache repair from committed snapshots, including vanished capture
 * and typed entity files, stale pointers and committed removals. Scans up to
 * 16 normal events per call. Missing/corrupt capture and typed indexes use
 * independent 64-event/soft-byte/time slices, shared with historical operation
 * index lookup, and return EAGAIN while pending;
 * no partial scan proves absence. Caller holds the capture lock. Cursor
 * advances after a successful check/restore and restarts after process exit;
 * no source fact is added and no delivery receipt depends on this cursor.
 * Returns 2=cache restored, 1=snapshot checked, 0=caught up, or negative errno. */
int buffer_source_local_repair_step(uint64_t *cursor);
/* Read-only archived WAV pinned to an exact committed envelope. Returns a
 * verified descriptor owned by the caller, positioned at EOF. It is not a
 * general filesystem/blob-hash oracle; channel/sharing checks are separate. */
int buffer_source_blob_open(uint64_t sequence, const char *record_fingerprint,
                            const char *blob_fingerprint, uint64_t bytes,
                            int *descriptor);
int buffer_source_blob_accept(const char *peer, const char *source,
                              uint64_t sequence, const char *record_fingerprint,
                              const char *blob_fingerprint, uint64_t bytes,
                              bool (*authorize)(void *), void *context);
/* Sender-side transport metadata. A contiguous receipt cursor never deletes
 * source facts and never substitutes for the receiver's current progress.
 * authorize must not acquire Fabric/journal locks. */
int buffer_source_delivery_progress(const char *peer, bool (*authorize)(void *),
                                    void *context, uint64_t *next);
int buffer_source_delivery_accept(const char *peer, const char *source,
                                  uint64_t sequence, const char *fingerprint,
                                  size_t length, bool (*authorize)(void *),
                                  void *context, uint64_t *next);
/* visibility-v1 sender ledger. These are historical receipt prefixes, never
 * the receiver's current cursor or file-health proof. checking means the
 * bounded check has not yet proved its prefix boundary; query again rather
 * than declaring that the receiver lacks the remaining originals.
 * All functions require neither journal lock on entry. Authorization callbacks
 * inspect live channel state only. The wire owner MUST separately recheck
 * sharing before marking an original offered and before each payload send. */
struct buffer_source_visible_delivery_s {
  uint64_t covered_next,original_next;
  bool checking;
};
int buffer_source_visible_progress(const char *peer,bool (*authorize)(void *),void *context,
                                   struct buffer_source_visible_delivery_s *progress);
int buffer_source_visible_prepare(const char *peer,const char *source,uint64_t sequence,
                                  bool (*authorize)(void *),void *context,char token[33]);
int buffer_source_visible_offered(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,size_t length,
                                  bool (*authorize)(void *),void *context,char token[33]);
int buffer_source_visible_accept_slot(const char *peer,const char *source,uint64_t sequence,const char *token,
                                      bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *progress);
int buffer_source_visible_accept_original(const char *peer,const char *source,uint64_t sequence,const char *token,
                                          const char *fingerprint,size_t length,bool (*authorize)(void *),void *context,
                                          struct buffer_source_visible_delivery_s *progress);
#endif
