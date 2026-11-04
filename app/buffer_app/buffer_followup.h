/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_FOLLOWUP_H
#define BUFFER_FOLLOWUP_H
#include "buffer_agent.h"
int buffer_followup_prepare(const char *capture,const char *card);
cJSON *buffer_followup_context(const char *capture);
cJSON *buffer_followup_result(const char *capture);
/* 1 means ordinary assistant capture; 0 means saved to the card conversation. */
int buffer_followup_answer(const char *capture,const char *answer);
char *buffer_followup_history(const char *card);
uint64_t buffer_followup_revision(void);
int buffer_followup_cancel(const char *capture);
#endif
