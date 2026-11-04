/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_oobe.h"
#include "buffer_app.h"
#include "buffer_agent.h"
#include "buffer_fabric.h"

#include <netutils/cJSON.h>

#include <errno.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

void buffer_oobe_present(int binding_record_status, bool paired,
                         bool wifi_ready, bool agent_ready, bool agent_skipped,
                         bool legacy_deferred, bool setup_complete,
                         struct buffer_oobe_presentation_s *presentation)
{
  (void)legacy_deferred;
  if (presentation == NULL) return;
  memset(presentation, 0, sizeof(*presentation));
  presentation->wifi_ready = wifi_ready;

  if (binding_record_status != 0 && binding_record_status != -ENOENT)
    {
      presentation->page = BUFFER_OOBE_PAGE_BINDING_ERROR;
    }
  else if (setup_complete && paired && binding_record_status == 0)
    {
      presentation->page = BUFFER_OOBE_PAGE_HOME;
    }
  else if (paired && binding_record_status == 0 && wifi_ready &&
           agent_ready && !agent_skipped)
    {
      presentation->page = BUFFER_OOBE_PAGE_READY;
    }
  else if (paired && binding_record_status == 0)
    {
      presentation->page = BUFFER_OOBE_PAGE_SERVICES;
      presentation->retry_visible = true;
    }
  else if (!paired && binding_record_status == -ENOENT)
    {
      presentation->page = BUFFER_OOBE_PAGE_WELCOME;
      presentation->pairing_code_visible = true;
    }
  else
    {
      presentation->page = BUFFER_OOBE_PAGE_BINDING_ERROR;
    }
}

static void wipe(void *memory, size_t length)
{
  volatile unsigned char *p = memory;
  while (length-- > 0) *p++ = 0;
}

int buffer_oobe_finish(void)
{
  struct buffer_agent_status_s agent = {0};
  char local_id[65] = {0};
  char peer_id[65] = {0};

  /* Fabric owns the binding state. The legacy LAN phone.conf is absent for
   * newly paired devices and must not gate explicit setup completion. */
  int ret = buffer_fabric_identity_pair(local_id, peer_id);
  if (ret < 0) return ret;
  wipe(local_id, sizeof(local_id));
  wipe(peer_id, sizeof(peer_id));
  if (!buffer_wifi_is_ready())
    {
      return -ENETUNREACH;
    }

  buffer_agent_snapshot(&agent);
  if (!agent.ready || agent.skipped)
    {
      wipe(&agent, sizeof(agent));
      return -EAGAIN;
    }

  /* Saving is the durable commit and updates the identity-keyed RAM cache. */
  ret = buffer_store_save_oobe_completed(true);
  wipe(&agent, sizeof(agent));
  if (!ret) buffer_agent_wake();
  return ret;
}

bool buffer_oobe_setup_complete(void)
{
  /* UI and input paths call this frequently; cache lookup is memory-only and
   * validates the current Fabric identity pair without touching storage. */
  return buffer_store_oobe_completed_cached();
}

void buffer_oobe_retry(void)
{
  buffer_store_oobe_completion_invalidate();
  buffer_agent_retry();
}

void buffer_oobe_request_reset(struct buffer_oobe_request_s *request)
{
  if (request != NULL) wipe(request, sizeof(*request));
}

static bool valid_text(const char *value, size_t min_length, size_t max_length)
{
  if (value == NULL) return false;
  size_t length = strlen(value);
  if (length < min_length || length > max_length) return false;
  for (size_t i = 0; i < length; i++)
    if ((unsigned char)value[i] < 0x20 || (unsigned char)value[i] == 0x7f)
      return false;
  return true;
}

static bool valid_request_id(const char *value)
{
  if (value == NULL || strlen(value) != 32) return false;
  for (size_t i = 0; i < 32; i++)
    if (!((value[i] >= '0' && value[i] <= '9') ||
          (value[i] >= 'a' && value[i] <= 'f'))) return false;
  return true;
}

static bool valid_wifi(const char *ssid, const char *password)
{
  if (!valid_text(ssid, 1, 32) || !valid_text(password, 8, 64)) return false;
  if (strlen(password) == 64)
    for (size_t i = 0; i < 64; i++)
      if (!((password[i] >= '0' && password[i] <= '9') ||
            (password[i] >= 'a' && password[i] <= 'f') ||
            (password[i] >= 'A' && password[i] <= 'F'))) return false;
  return true;
}

static bool valid_https_url(const char *url)
{
  const char *authority;
  const char *end;
  const char *colon = NULL;
  size_t host_length;

  if (url == NULL || strncmp(url, "https://", 8) != 0 ||
      strchr(url, '?') != NULL || strchr(url, '#') != NULL ||
      strpbrk(url, "\r\n\t ")) return false;
  authority = url + 8;
  end = strpbrk(authority, "/?#");
  if (end == NULL) end = authority + strlen(authority);
  if (end == authority || memchr(authority, '@', (size_t)(end - authority)) != NULL)
    return false;
  for (const char *p = authority; p < end; p++)
    if (*p == ':') colon = p;
  host_length = (size_t)((colon == NULL ? end : colon) - authority);
  if (host_length == 0 || host_length >= 64 || authority[0] == '.' ||
      authority[host_length - 1] == '.') return false;
  for (size_t i = 0; i < host_length; i++)
    if (!((authority[i] >= 'a' && authority[i] <= 'z') ||
          (authority[i] >= 'A' && authority[i] <= 'Z') ||
          (authority[i] >= '0' && authority[i] <= '9') ||
          authority[i] == '-' || authority[i] == '.')) return false;
  if (colon != NULL)
    {
      unsigned long port = 0;
      if (colon + 1 == end) return false;
      for (const char *p = colon + 1; p < end; p++)
        {
          if (*p < '0' || *p > '9') return false;
          port = port * 10 + (unsigned long)(*p - '0');
          if (port > 65535) return false;
        }
      if (port == 0) return false;
    }
  return true;
}

static int copy_string(const cJSON *object, const char *name, char *out,
                       size_t out_size, size_t min_length, size_t max_length)
{
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(object, name);
  if (!cJSON_IsString(item) || item->valuestring == NULL ||
      !valid_text(item->valuestring, min_length, max_length) ||
      strlen(item->valuestring) >= out_size) return -EINVAL;
  strcpy(out, item->valuestring);
  return 0;
}

static int exact_keys(const cJSON *object, const char *const *keys, size_t count)
{
  if (!cJSON_IsObject(object) || (size_t)cJSON_GetArraySize(object) != count)
    return -EINVAL;
  for (const cJSON *child = object->child; child != NULL; child = child->next)
    {
      bool found = false;
      if (child->string == NULL) return -EINVAL;
      for (const cJSON *prior = object->child; prior != child; prior = prior->next)
        if (prior->string != NULL && strcmp(prior->string, child->string) == 0)
          return -EINVAL;
      for (size_t i = 0; i < count; i++)
        if (strcmp(child->string, keys[i]) == 0) { found = true; break; }
      if (!found) return -EINVAL;
    }
  return 0;
}

static bool contains_escaped_nul(const unsigned char *json, size_t length)
{
  for (size_t i = 0; i + 5 < length; i++)
    if (json[i] == '\\')
      {
        if (memcmp(json + i + 1, "u0000", 5) == 0) return true;
        i++;
      }
  return false;
}

static void wipe_secret_fields(cJSON *object)
{
  if (object == NULL) return;
  if ((cJSON_IsObject(object) || cJSON_IsArray(object)))
    for (cJSON *child = object->child; child != NULL; child = child->next)
      {
        if (child->string != NULL &&
            (strcmp(child->string, "api_key") == 0 ||
             strcmp(child->string, "password") == 0) &&
            cJSON_IsString(child) && child->valuestring != NULL)
          wipe(child->valuestring, strlen(child->valuestring));
        wipe_secret_fields(child);
      }
}

int buffer_oobe_decode(const unsigned char *json, size_t length,
                       struct buffer_oobe_request_s *request)
{
  static const char *const root_keys[] =
    {"type", "protocol", "request_id", "revision", "operation", "payload"};
  static const char *const network_keys[] = {"ssid", "password"};
  static const char *const agent_keys[] =
    {"base_url", "completion_model", "transcription_model", "api_key",
     "time_zone", "language"};
  cJSON *root = NULL;
  cJSON *payload;
  cJSON *value;
  int result = -EINVAL;

  if (request == NULL) return -EINVAL;
  buffer_oobe_request_reset(request);
  if (json == NULL || length == 0 || length > BUFFER_OOBE_MAX_MESSAGE_BYTES ||
      memchr(json, 0, length) != NULL || contains_escaped_nul(json, length))
    return -EMSGSIZE;

  /* Configuration reload also runs during BLE recovery. Do not put the
   * maximum wire frame on a default-sized service thread's stack. */
  char *input = malloc(length + 1);
  if (input == NULL) return -ENOMEM;
  memcpy(input, json, length);
  input[length] = 0;
  const char *end = NULL;
  root = cJSON_ParseWithOpts(input, &end, true);
  if (!cJSON_IsObject(root) || exact_keys(root, root_keys,
                                          sizeof(root_keys) / sizeof(root_keys[0])) < 0)
    goto done;

  value = cJSON_GetObjectItemCaseSensitive(root, "type");
  if (!cJSON_IsString(value) || strcmp(value->valuestring, "vela.oobe.request") != 0)
    goto done;
  value = cJSON_GetObjectItemCaseSensitive(root, "protocol");
  if (!cJSON_IsNumber(value) || value->valuedouble != BUFFER_OOBE_PROTOCOL_VERSION)
    goto done;
  value = cJSON_GetObjectItemCaseSensitive(root, "revision");
  if (!cJSON_IsNumber(value) || value->valuedouble < 1 ||
      value->valuedouble > 9007199254740991.0) goto done;
  request->revision = (uint64_t)value->valuedouble;
  if ((double)request->revision != value->valuedouble) goto done;
  if (copy_string(root, "request_id", request->request_id,
                  sizeof(request->request_id), 32, 32) < 0 ||
      !valid_request_id(request->request_id)) goto done;
  if (copy_string(root, "operation", input, 64, 1, 32) < 0) goto done;
  payload = cJSON_GetObjectItemCaseSensitive(root, "payload");
  if (!cJSON_IsObject(payload)) goto done;

  if (strcmp(input, "oobe.status") == 0)
    {
      request->operation = BUFFER_OOBE_GET_STATE;
      result = exact_keys(payload, NULL, 0);
    }
  else if (strcmp(input, "agent.skip") == 0)
    {
      request->operation = BUFFER_OOBE_SKIP_AGENT;
      result = exact_keys(payload, NULL, 0);
    }
  else if (strcmp(input, "network.configure") == 0)
    {
      request->operation = BUFFER_OOBE_CONFIGURE_NETWORK;
      result = exact_keys(payload, network_keys, 2);
      if (result == 0 &&
          (copy_string(payload, "ssid", request->ssid, sizeof(request->ssid), 1, 32) < 0 ||
           copy_string(payload, "password", request->wifi_password,
                       sizeof(request->wifi_password), 8, 64) < 0 ||
           !valid_wifi(request->ssid, request->wifi_password))) result = -EINVAL;
    }
  else if (strcmp(input, "agent.configure") == 0)
    {
      request->operation = BUFFER_OOBE_CONFIGURE_AGENT;
      result = exact_keys(payload, agent_keys, 6);
      if (result == 0 &&
          (copy_string(payload, "base_url", request->base_url,
                       sizeof(request->base_url), 9, 256) < 0 ||
           !valid_https_url(request->base_url) ||
           copy_string(payload, "completion_model", request->completion_model,
                       sizeof(request->completion_model), 1, 200) < 0 ||
           copy_string(payload, "transcription_model", request->transcription_model,
                       sizeof(request->transcription_model), 1, 200) < 0 ||
           strcmp(request->transcription_model, "mimo-v2.5-asr") != 0 ||
           copy_string(payload, "api_key", request->api_key,
                       sizeof(request->api_key), 0, 8192) < 0 ||
           copy_string(payload, "time_zone", request->time_zone,
                       sizeof(request->time_zone), 1, 80) < 0 ||
           copy_string(payload, "language", request->language,
                       sizeof(request->language), 1, 40) < 0)) result = -EINVAL;
    }
  else
    {
      result = -ENOTSUP;
    }

done:
  wipe_secret_fields(root);
  cJSON_Delete(root);
  wipe(input, length + 1);
  free(input);
  if (result < 0) buffer_oobe_request_reset(request);
  return result;
}
