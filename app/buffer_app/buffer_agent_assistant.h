/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_AGENT_ASSISTANT_H
#define BUFFER_AGENT_ASSISTANT_H
#include "buffer_agent.h"
#include "buffer_a2ui.h"
cJSON *buffer_agent_assistant_tools(void);
bool buffer_agent_assistant_tool_known(const char *name);
cJSON *buffer_agent_assistant_tool(const char *name, const cJSON *arguments,
                                   const char *capture_id, unsigned slot);
int buffer_agent_assistant_ui_action(const struct buffer_a2ui_action_s *event,void *context);
int buffer_agent_assistant_reply(const char *capture_id, const char *text);
#endif
