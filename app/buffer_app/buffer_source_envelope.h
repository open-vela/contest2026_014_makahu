/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_ENVELOPE_H
#define BUFFER_SOURCE_ENVELOPE_H
#include "buffer_agent.h"
/* Validate the wire-v1 contract without reserializing its immutable bytes.
 * Caller owns *envelope. Payload is data: escaped NUL is retained in raw even
 * though cJSON strings cannot represent its full length. Never project such
 * payload strings through strlen or execute imported payloads. */
int buffer_source_envelope_decode(const char *raw, size_t length,
                                  cJSON **envelope);
/* Same bounded JSON/Unicode/duplicate-key checks for protocol objects. */
int buffer_source_json_object(const char *raw, size_t length, size_t maximum,
                              cJSON **object);
#endif
