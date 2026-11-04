/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_REPLICA_H
#define BUFFER_REPLICA_H
#include "buffer_agent.h"
/* Full phone card replicas are distinct from the five-card screen projection.
 * Only the authenticated application worker may receive/commit a replica. */
int buffer_replica_receive(const struct buffer_oobe_authorization_s *auth,
                           const char *peer, const unsigned char *data,
                           size_t length, char **reply);
void buffer_replica_reset(void);
/* Recovery executes no task. Mutating calls require g_buffer_capture_lock. */
int buffer_replica_input_result_pending(const char *capture_id);
int buffer_replica_recover_input_result(const char *capture_id);
int buffer_replica_recover_results_step(void);
void buffer_replica_close_session(const char *peer,unsigned generation);
int buffer_replica_receive_session(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,
                                   const unsigned char *data,size_t length,char **reply);
bool buffer_replica_contains(const char *id);
/* A live logical view or durable local tombstone, excluding staged caches. */
bool buffer_replica_available(const char *id);
int buffer_replica_cards(struct buffer_card_s *cards, int maximum);
/* Caller owns the complete UTF-8 detail text; never sourced from the tiny shelf preview. */
char *buffer_replica_detail(const char *id);
char *buffer_replica_model_detail(const char *id);
cJSON *buffer_replica_conversation(const char *id);
cJSON *buffer_replica_search(const char *query, const char *read_id,
                             const char *current_capture);
int buffer_replica_action_next(uint64_t now, char **message);
int buffer_replica_action_next_for_peer(const char *peer,uint64_t now,char **message);
int buffer_replica_action_ack_for_peer(const char *peer,const cJSON *reply);
int buffer_replica_action_ack(const cJSON *reply);
void buffer_agent_projection_changed(void);
#endif
