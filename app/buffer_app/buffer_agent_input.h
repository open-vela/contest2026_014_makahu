/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_AGENT_INPUT_H
#define BUFFER_AGENT_INPUT_H
#include "buffer_agent.h"
int buffer_agent_input_next_for_peer(const char *peer,uint64_t term,uint64_t now,char **message);
int buffer_agent_input_ack_for_peer(const char *peer,uint64_t term,const cJSON *reply);
void buffer_agent_input_reset(void);
int buffer_agent_input_digest(const char *capture_id,char hash[65]);
int buffer_agent_input_delegate_proof(const char *capture_id,const char *peer,uint64_t term,char hash[65]);
int buffer_agent_input_finish_accepted(const char *capture_id);
void buffer_agent_input_resolved(const char *capture_id);
bool buffer_agent_input_assistant(const char *capture_id);
int buffer_agent_input_handle_assistant_request(
    const struct buffer_oobe_authorization_s *auth,
    const char *authenticated_phone, const unsigned char *data,
    size_t length, char **reply);
#endif
