/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_enrollment.h"
#include <netutils/cJSON.h>
#include <errno.h>
#include <stdbool.h>
#include <string.h>

static bool printable(const char *s, size_t min, size_t max)
{
  if (s == NULL) return false;
  size_t n = strlen(s);
  if (n < min || n > max) return false;
  for (size_t i = 0; i < n; i++)
    if ((unsigned char)s[i] < 0x20 || (unsigned char)s[i] == 0x7f) return false;
  return true;
}

static bool hex(const char *s, size_t length)
{
  if (s == NULL || strlen(s) != length) return false;
  for (size_t i = 0; i < length; i++)
    if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return false;
  return true;
}

int buffer_wifi_credentials_valid(const char *ssid, const char *password)
{
  if (!printable(ssid, 1, 32) || !printable(password, 8, 64)) return -EINVAL;
  if (strlen(password) == 64)
    for (size_t i = 0; i < 64; i++)
      if (!((password[i] >= '0' && password[i] <= '9') ||
            (password[i] >= 'a' && password[i] <= 'f') ||
            (password[i] >= 'A' && password[i] <= 'F'))) return -EINVAL;
  return 0;
}

void buffer_enrollment_reset(struct buffer_enrollment_frame_s *frame)
{
  if (frame == NULL) return;
  volatile unsigned char *p = (volatile unsigned char *)frame;
  for (size_t i = 0; i < sizeof(*frame); i++) p[i] = 0;
}

int buffer_enrollment_feed(struct buffer_enrollment_frame_s *frame,
                           const unsigned char *fragment, size_t length)
{
  if (frame == NULL || fragment == NULL || length <= 4) return -EINVAL;
  size_t offset = ((size_t)fragment[0] << 8) | fragment[1];
  size_t total = ((size_t)fragment[2] << 8) | fragment[3];
  size_t n = length - 4;
  if (total == 0 || total > BUFFER_ENROLLMENT_MAX_JSON ||
      offset > total || n > total - offset) return -EMSGSIZE;
  if (frame->total != 0 && frame->total != total) return -EBUSY;
  if (offset > frame->received) return -EINVAL;
  if (offset < frame->received)
    {
      if (n > frame->received - offset || memcmp(frame->data + offset, fragment + 4, n) != 0)
        return -EINVAL;
      return 0; /* Never execute a complete request again on retransmission. */
    }
  frame->total = total;
  memcpy(frame->data + offset, fragment + 4, n);
  frame->received += n;
  frame->data[frame->received] = 0;
  return frame->received == total ? 1 : 0;
}

int buffer_enrollment_decode(const unsigned char *json, size_t length,
                             struct buffer_enrollment_request_s *request)
{
  if (request == NULL) return -EINVAL;
  memset(request, 0, sizeof(*request));
  if (json == NULL || length == 0 || length > BUFFER_ENROLLMENT_MAX_JSON ||
      memchr(json, 0, length) != NULL) return -EINVAL;
  /* cJSON stores decoded strings as C strings. Reject embedded escaped NUL
   * rather than silently accepting only the prefix of a credential or ID. */
  for (size_t i = 0; i + 1 < length; i++)
    if (json[i] == '\\')
      {
        if (i + 5 < length && memcmp(json + i + 1, "u0000", 5) == 0) return -EINVAL;
        i++;
      }
  char input[BUFFER_ENROLLMENT_MAX_JSON + 1];
  memcpy(input, json, length);
  input[length] = 0;
  const char *end = NULL;
  cJSON *root = cJSON_ParseWithOpts(input, &end, true);
  int result = -EINVAL;
  if (!cJSON_IsObject(root)) goto done;
  const char *keys[] = {"type", "protocol", "request_id", "phone_device_id", "token", "ssid", "password"};
  const cJSON *fields[7] = {0};
  const cJSON *child;
  cJSON_ArrayForEach(child, root)
    {
      size_t index;
      for (index = 0; index < 7; index++)
        if (child->string != NULL && strcmp(child->string, keys[index]) == 0) break;
      if (index == 7 || fields[index] != NULL) goto done;
      fields[index] = child;
    }
  for (size_t i = 0; i < 5; i++)
    if (i != 1 && !cJSON_IsString(fields[i])) goto done;
  bool existing = strcmp(fields[0]->valuestring, "buffer.bind") == 0;
  if (!cJSON_IsNumber(fields[1]) || fields[1]->valuedouble != 1 ||
      (!existing && strcmp(fields[0]->valuestring, "buffer.configure") != 0) ||
      !hex(fields[2]->valuestring, 32) || !hex(fields[4]->valuestring, 64)) goto done;
  const char *id = fields[3]->valuestring;
  if (!printable(id, 1, 79)) goto done;
  for (size_t i = 0; id[i]; i++)
    if (!((id[i] >= 'a' && id[i] <= 'z') || (id[i] >= 'A' && id[i] <= 'Z') ||
          (id[i] >= '0' && id[i] <= '9') || id[i] == '.' || id[i] == '_' || id[i] == '-')) goto done;
  if (existing) {
    if (fields[5] != NULL || fields[6] != NULL) goto done;
  } else {
    if (!cJSON_IsString(fields[5]) || !cJSON_IsString(fields[6]) ||
        buffer_wifi_credentials_valid(fields[5]->valuestring, fields[6]->valuestring) < 0) goto done;
  }
  strcpy(request->request_id, fields[2]->valuestring);
  strcpy(request->phone_id, id);
  strcpy(request->token, fields[4]->valuestring);
  request->use_existing_wifi = existing;
  if (!existing) {
    strcpy(request->ssid, fields[5]->valuestring);
    strcpy(request->password, fields[6]->valuestring);
  }
  result = 0;
done:
  cJSON_Delete(root);
  volatile char *wipe = input;
  for (size_t i = 0; i < sizeof(input); i++) wipe[i] = 0;
  return result;
}
