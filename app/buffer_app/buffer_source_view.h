/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_VIEW_H
#define BUFFER_SOURCE_VIEW_H
#include "buffer_agent.h"
/* Derived view generations. A failed/unfinished rebuild never publishes;
 * invalid causal data fences the old overlay before its replacement starts. */
int buffer_source_view_rebuild_needed(bool *needed,bool *building);
int buffer_source_view_rebuild_begin(void);
int buffer_source_view_rebuild_request(void);
int buffer_source_view_causal_changed(bool invalid);
int buffer_source_view_rebuild_finish(void);
int buffer_source_view_cleanup(void);
/* Derived remote view only. No local source event, executor or session change.
 * Caller holds the capture lock. Input peer comes from the durable source pin.
 * Returns 1 for visible cache changes, 0 for an unchanged view or shadow
 * write, negative on error. A shadow is visible only after its generation commits. */
int buffer_source_view_publish(const char *peer,const cJSON *document,bool already_applied);
int buffer_source_view_validate(const cJSON *document);
/* Local-journal lock may be held. Reads only private derived files, never the
 * remote journal. ENOENT means no overlay; other errors must not be ignored. */
int buffer_source_view_overlay(const char *path,const char *local,size_t limit,char **raw,size_t *length,bool *changed);
int buffer_source_view_exists(const char *path);
int buffer_source_projector_start(void);
void buffer_source_projector_stop(void);
#endif
