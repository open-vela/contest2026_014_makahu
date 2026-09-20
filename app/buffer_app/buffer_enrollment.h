/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_ENROLLMENT_H
#define BUFFER_ENROLLMENT_H
#include <stddef.h>
#include <stdint.h>
#define BUFFER_ENROLLMENT_MAX_JSON 768
struct buffer_enrollment_request_s {
  int use_existing_wifi;
  char request_id[33];
  char phone_id[80];
  char token[65];
  char ssid[33];
  char password[65];
};
struct buffer_enrollment_frame_s {
  size_t total;
  size_t received;
  unsigned char data[BUFFER_ENROLLMENT_MAX_JSON + 1];
};
/* A write carries [offset:u16be][total:u16be][payload]. Returns 1 when
 * complete, 0 for accepted partial/repeated data, negative errno on failure.
 * Caller must authenticate the connection BEFORE feeding any fragment and
 * reset at timeout/disconnect. Only a separate result acknowledges applying
 * the request; the platform's GATT write ACK does not do that. */
int buffer_enrollment_feed(struct buffer_enrollment_frame_s *frame,
                           const unsigned char *fragment, size_t length);
void buffer_enrollment_reset(struct buffer_enrollment_frame_s *frame);
int buffer_enrollment_decode(const unsigned char *json, size_t length,
                             struct buffer_enrollment_request_s *request);
int buffer_wifi_credentials_valid(const char *ssid, const char *password);
#endif
