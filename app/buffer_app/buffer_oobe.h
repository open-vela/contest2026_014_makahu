/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_OOBE_H
#define BUFFER_OOBE_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#define BUFFER_OOBE_PROTOCOL_VERSION 1
#define BUFFER_OOBE_MAX_MESSAGE_BYTES (12 * 1024)
#define BUFFER_OOBE_REQUEST_ID_SIZE 33
#define BUFFER_OOBE_BASE_URL_SIZE 257
#define BUFFER_OOBE_MODEL_SIZE 201
#define BUFFER_OOBE_API_KEY_SIZE 8193
#define BUFFER_OOBE_TIME_ZONE_SIZE 81
#define BUFFER_OOBE_LANGUAGE_SIZE 41

enum buffer_oobe_operation_e
{
  BUFFER_OOBE_GET_STATE = 0,
  BUFFER_OOBE_CONFIGURE_NETWORK,
  BUFFER_OOBE_CONFIGURE_AGENT,
  BUFFER_OOBE_SKIP_AGENT,
};

enum buffer_oobe_page_e
{
  BUFFER_OOBE_PAGE_HOME = 0,
  BUFFER_OOBE_PAGE_WELCOME,
  BUFFER_OOBE_PAGE_SERVICES,
  BUFFER_OOBE_PAGE_BINDING_ERROR,
  BUFFER_OOBE_PAGE_READY,
};

struct buffer_oobe_presentation_s
{
  enum buffer_oobe_page_e page;
  bool pairing_code_visible;
  bool wifi_ready;
  bool retry_visible;
};

struct buffer_oobe_request_s
{
  enum buffer_oobe_operation_e operation;
  uint64_t revision;
  char request_id[BUFFER_OOBE_REQUEST_ID_SIZE];
  char ssid[33];
  char wifi_password[65];
  char base_url[BUFFER_OOBE_BASE_URL_SIZE];
  char completion_model[BUFFER_OOBE_MODEL_SIZE];
  char transcription_model[BUFFER_OOBE_MODEL_SIZE];
  char api_key[BUFFER_OOBE_API_KEY_SIZE];
  char time_zone[BUFFER_OOBE_TIME_ZONE_SIZE];
  char language[BUFFER_OOBE_LANGUAGE_SIZE];
};

/* Decode the app-owned OOBE contract. The caller must invoke this only for a
 * message delivered by the authenticated Fabric ReliableMessages adapter.
 * Parsing does not itself authenticate a peer or apply the request. */
int buffer_oobe_decode(const unsigned char *json, size_t length,
                       struct buffer_oobe_request_s *request);
void buffer_oobe_request_reset(struct buffer_oobe_request_s *request);

/* Build the screen state from durable setup completion, binding and live
 * service readiness. legacy_deferred is accepted for call-site migration but
 * never grants access. Existing page values remain stable; READY is appended. */
void buffer_oobe_present(int binding_record_status, bool paired,
                         bool wifi_ready, bool agent_ready, bool agent_skipped,
                         bool legacy_deferred, bool setup_complete,
                         struct buffer_oobe_presentation_s *presentation);

/* Complete setup only after rechecking binding, Wi-Fi, and validated
 * non-skipped Agent state, then durably saving an identity-bound record. */
int buffer_oobe_finish(void);

/* A conservative query for authenticated capabilities and business-entry
 * gates. Store errors, missing/stale records, and binding errors are false. */
bool buffer_oobe_setup_complete(void);

/* Explicit OOBE retry: invalidate a cached completion read error and wake the
 * background Agent worker to reload it without performing I/O on the caller. */
void buffer_oobe_retry(void);

#endif /* BUFFER_OOBE_H */
