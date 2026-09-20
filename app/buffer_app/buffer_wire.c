/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela LAN data plane
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"

#include <mbedtls/md.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netutils/cJSON.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define BUFFER_WIRE_MAX_LINE (1200 * 1024)
#define BUFFER_WIRE_RECONNECT_AFTER_FAILURES 3
#define BUFFER_WIRE_DISCOVERY_LINE 1024
#define BUFFER_WIRE_AUTH_PROOF_SIZE 65
#define BUFFER_WIRE_DISCOVERY_TIMEOUT_MS 250
#define BUFFER_WIRE_CONNECT_TIMEOUT_MS 5000

static bool buffer_wire_should_reconnect(int ret)
{
  return ret < 0 && ret != -EACCES && ret != -ENOMEM;
}

static void buffer_wire_refresh_pending_count(void)
{
  int pending_count = buffer_store_pending_count();

  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pending_count = pending_count;
  pthread_mutex_unlock(&g_buffer.lock);
}

static bool buffer_wire_valid_device_id(const char *device_id)
{
  size_t length;
  size_t index;

  if (device_id == NULL)
    {
      return false;
    }

  length = strlen(device_id);
  if (length == 0 || length >= BUFFER_ID_SIZE)
    {
      return false;
    }

  for (index = 0; index < length; index++)
    {
      unsigned char character = (unsigned char)device_id[index];
      if (!((character >= 'a' && character <= 'z') ||
            (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') ||
            character == '.' || character == '_' || character == '-'))
        {
          return false;
        }
    }

  return true;
}

static void buffer_wire_load_standalone_state(void)
{
  char mode[BUFFER_MODE_SIZE];
  char pending_mode[BUFFER_MODE_SIZE];
  char pending_mode_id[BUFFER_ACTION_ID_SIZE];
  char pending_card_id[BUFFER_ID_SIZE];
  char pending_action_id[BUFFER_ACTION_ID_SIZE];
  char pending_action[BUFFER_ACTION_SIZE];
  bool initialized;

  pthread_mutex_lock(&g_buffer.lock);
  initialized = g_buffer.initialized;
  pthread_mutex_unlock(&g_buffer.lock);
  if (initialized)
    {
      return;
    }

  if (buffer_store_load_mode(mode, sizeof(mode)) < 0)
    {
      snprintf(mode, sizeof(mode), "normal");
    }
  if (buffer_store_load_pending_mode(pending_mode, sizeof(pending_mode),
                                     pending_mode_id,
                                     sizeof(pending_mode_id)) < 0)
    {
      pending_mode[0] = '\0';
      pending_mode_id[0] = '\0';
    }
  if (buffer_store_load_pending_action(pending_card_id, sizeof(pending_card_id),
                                       pending_action_id,
                                       sizeof(pending_action_id),
                                       pending_action, sizeof(pending_action)) < 0)
    {
      pending_card_id[0] = '\0';
      pending_action_id[0] = '\0';
      pending_action[0] = '\0';
    }

  pthread_mutex_lock(&g_buffer.lock);
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s",
           pending_mode[0] != '\0' ? pending_mode : mode);
  if (pending_mode[0] != '\0')
    {
      snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "%s",
               pending_mode_id);
      g_buffer.mode_dirty = true;
    }
  if (pending_card_id[0] != '\0')
    {
      snprintf(g_buffer.action_card_id, sizeof(g_buffer.action_card_id), "%s",
               pending_card_id);
      snprintf(g_buffer.action_id, sizeof(g_buffer.action_id), "%s",
               pending_action_id);
      snprintf(g_buffer.action, sizeof(g_buffer.action), "%s", pending_action);
      g_buffer.action_requested = true;
    }
  pthread_mutex_unlock(&g_buffer.lock);
}

static int buffer_wire_discover_phone(const char *expected_device_id,
                                      char *host, size_t host_size,
                                      char *device_id, size_t device_id_size)
{
  static const char request[] =
    "{\"type\":\"buffer.phone.discover\",\"protocol\":1}";
  struct sockaddr_in target;
  struct sockaddr_in peer;
  struct timespec started;
  char line[BUFFER_WIRE_DISCOVERY_LINE];
  socklen_t peer_length;
  int fd;
  int yes = 1;
  int ret = -ETIMEDOUT;

  if (host == NULL || host_size == 0 || device_id == NULL ||
      device_id_size == 0)
    {
      return -EINVAL;
    }

  fd = socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0)
    {
      return -errno;
    }

  (void)setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
  if (clock_gettime(CLOCK_MONOTONIC, &started) < 0)
    {
      ret = -errno;
      close(fd);
      return ret;
    }
  memset(&target, 0, sizeof(target));
  target.sin_family = AF_INET;
  target.sin_addr.s_addr = htonl(INADDR_BROADCAST);
  target.sin_port = htons((uint16_t)CONFIG_BUFFER_VELA_DISCOVERY_PORT);
  if (sendto(fd, request, sizeof(request) - 1, 0,
             (struct sockaddr *)&target, sizeof(target)) < 0)
    {
      ret = -errno;
      close(fd);
      return ret;
    }

  while (true)
    {
      ssize_t received;
      cJSON *response;
      cJSON *type;
      cJSON *protocol;
      cJSON *device_id_item;
      struct timespec now;
      struct pollfd ready = {.fd = fd, .events = POLLIN};
      int64_t elapsed_ms;
      int wait_ret;

      if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
        {
          ret = -errno;
          break;
        }

      elapsed_ms = (int64_t)(now.tv_sec - started.tv_sec) * 1000 +
                   (now.tv_nsec - started.tv_nsec) / 1000000;
      if (elapsed_ms >= BUFFER_WIRE_DISCOVERY_TIMEOUT_MS)
        {
          break;
        }

      wait_ret = poll(&ready, 1,
                      BUFFER_WIRE_DISCOVERY_TIMEOUT_MS - (int)elapsed_ms);
      if (wait_ret < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          ret = -errno;
          break;
        }
      if (wait_ret == 0)
        {
          break;
        }
      if ((ready.revents & POLLIN) == 0)
        {
          ret = -EIO;
          break;
        }

      peer_length = sizeof(peer);
      received = recvfrom(fd, line, sizeof(line) - 1, MSG_DONTWAIT,
                          (struct sockaddr *)&peer, &peer_length);
      if (received < 0)
        {
          if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)
            {
              continue;
            }

          ret = -errno;
          break;
        }

      line[received] = '\0';
      response = cJSON_Parse(line);
      if (response == NULL)
        {
          continue;
        }

      type = cJSON_GetObjectItemCaseSensitive(response, "type");
      protocol = cJSON_GetObjectItemCaseSensitive(response, "protocol");
      device_id_item = cJSON_GetObjectItemCaseSensitive(response, "device_id");
      if (type == NULL || protocol == NULL || device_id_item == NULL ||
          !cJSON_IsString(type) || !cJSON_IsNumber(protocol) ||
          !cJSON_IsString(device_id_item) ||
          strcmp(type->valuestring, "buffer.phone") != 0 ||
          protocol->valueint != 1 ||
          !buffer_wire_valid_device_id(device_id_item->valuestring) ||
          (expected_device_id != NULL && expected_device_id[0] != '\0' &&
           strcmp(expected_device_id, device_id_item->valuestring) != 0))
        {
          cJSON_Delete(response);
          continue;
        }

      if (inet_ntop(AF_INET, &peer.sin_addr, host, host_size) == NULL)
        {
          ret = -EHOSTUNREACH;
        }
      else
        {
          snprintf(device_id, device_id_size, "%s",
                   device_id_item->valuestring);
          ret = 0;
        }
      cJSON_Delete(response);
      break;
    }

  close(fd);
  return ret;
}

static int buffer_wire_refresh_phone_host(void)
{
  char host[96];
  char token[128];
  char phone_device_id[BUFFER_ID_SIZE];
  char discovered_host[96];
  char discovered_device_id[BUFFER_ID_SIZE];
  int ret;

  ret = buffer_store_load_pairing(host, sizeof(host), token, sizeof(token),
                                  phone_device_id, sizeof(phone_device_id));
  if (ret < 0 || token[0] == '\0')
    {
      return -EACCES;
    }

  ret = buffer_wire_discover_phone(phone_device_id, discovered_host,
                                   sizeof(discovered_host),
                                   discovered_device_id,
                                   sizeof(discovered_device_id));
  if (ret < 0)
    {
      return ret;
    }

  return buffer_store_save_pairing(discovered_host, token,
                                   discovered_device_id);
}

/* BLE enrollment has no LAN address yet. Resolve its pinned phone identity
 * before the first TCP attempt instead of connecting to INADDR_ANY. */
static int buffer_wire_load_phone_route(char *host, size_t host_size,
                                        char *token, size_t token_size,
                                        char *phone_id, size_t phone_id_size)
{
  char discovered_host[96];
  char discovered_id[BUFFER_ID_SIZE];
  int ret = buffer_store_load_pairing(host, host_size, token, token_size,
                                     phone_id, phone_id_size);
  if (ret < 0 || token[0] == '\0')
    {
      return -EACCES;
    }

  if (strcmp(host, "0.0.0.0") != 0)
    {
      return 0;
    }

  /* Never turn an incomplete enrollment into discovery of any nearby phone. */
  if (!buffer_wire_valid_device_id(phone_id))
    {
      return -EACCES;
    }

  ret = buffer_wire_discover_phone(phone_id, discovered_host,
                                   sizeof(discovered_host), discovered_id,
                                   sizeof(discovered_id));
  if (ret < 0)
    {
      return ret;
    }

  ret = buffer_store_save_pairing(discovered_host, token, phone_id);
  if (ret < 0)
    {
      return ret;
    }

  snprintf(host, host_size, "%s", discovered_host);
  return 0;
}

static int buffer_send_all(int fd, const void *data, size_t length)
{
  const unsigned char *cursor = data;

  while (length > 0)
    {
      ssize_t sent = send(fd, cursor, length, 0);
      if (sent < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          return -errno;
        }

      if (sent == 0)
        {
          return -EPIPE;
        }

      cursor += sent;
      length -= (size_t)sent;
    }

  return 0;
}

static int buffer_read_line(int fd, char *line, size_t line_size)
{
  size_t used = 0;

  if (line == NULL || line_size < 2)
    {
      return -EINVAL;
    }

  while (used + 1 < line_size)
    {
      unsigned char block[256];
      ssize_t received = recv(fd, block, sizeof(block), 0);
      size_t i;

      if (received < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          return -errno;
        }

      if (received == 0)
        {
          return -ECONNRESET;
        }

      for (i = 0; i < (size_t)received; i++)
        {
          if (block[i] == '\n')
            {
              line[used] = '\0';
              if (used > 0 && line[used - 1] == '\r')
                {
                  line[used - 1] = '\0';
                }

              return 0;
            }

          if (used + 1 >= line_size)
            {
              return -EFBIG;
            }

          line[used++] = (char)block[i];
        }
    }

  return -EFBIG;
}

static int buffer_exchange(int fd, cJSON *request, char *response,
                           size_t response_size)
{
  char *encoded;
  int ret;

  encoded = cJSON_PrintUnformatted(request);
  if (encoded == NULL)
    {
      return -ENOMEM;
    }

  ret = buffer_send_all(fd, encoded, strlen(encoded));
  free(encoded);
  if (ret < 0)
    {
      return ret;
    }

  ret = buffer_send_all(fd, "\n", 1);
  if (ret < 0)
    {
      return ret;
    }

  return buffer_read_line(fd, response, response_size);
}

static int buffer_connect_address(int fd, const struct sockaddr *address,
                                   socklen_t address_length)
{
  struct timespec started;
  int flags;
  int ret;

  flags = fcntl(fd, F_GETFL, 0);
  if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0)
    {
      return -errno;
    }

  if (clock_gettime(CLOCK_MONOTONIC, &started) < 0)
    {
      return -errno;
    }

  ret = connect(fd, address, address_length);
  if (ret < 0 && errno != EINPROGRESS && errno != EALREADY &&
      errno != EINTR)
    {
      return -errno;
    }

  while (ret < 0)
    {
      struct timespec now;
      struct pollfd ready = {.fd = fd, .events = POLLOUT};
      int64_t elapsed_ms;
      int socket_error;
      socklen_t error_length = sizeof(socket_error);
      int wait_ret;

      if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
        {
          return -errno;
        }

      elapsed_ms = (int64_t)(now.tv_sec - started.tv_sec) * 1000 +
                   (now.tv_nsec - started.tv_nsec) / 1000000;
      if (elapsed_ms >= BUFFER_WIRE_CONNECT_TIMEOUT_MS)
        {
          return -ETIMEDOUT;
        }

      wait_ret = poll(&ready, 1,
                      BUFFER_WIRE_CONNECT_TIMEOUT_MS - (int)elapsed_ms);
      if (wait_ret < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          return -errno;
        }
      if (wait_ret == 0)
        {
          return -ETIMEDOUT;
        }
      if ((ready.revents & POLLNVAL) != 0)
        {
          return -EBADF;
        }
      if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error,
                     &error_length) < 0)
        {
          return -errno;
        }
      if (socket_error != 0)
        {
          return -socket_error;
        }
      if ((ready.revents & POLLOUT) == 0 ||
          (ready.revents & POLLHUP) != 0)
        {
          return -ECONNRESET;
        }

      ret = 0;
    }

  /* The line protocol below uses blocking I/O with socket timeouts. */

  if (fcntl(fd, F_SETFL, flags) < 0)
    {
      return -errno;
    }

  return 0;
}

static int buffer_connect(const char *host, int port)
{
  struct addrinfo hints;
  struct addrinfo *addresses = NULL;
  struct addrinfo *address;
  char port_text[12];
  int fd = -1;
  int ret;
  int last_error = -ECONNREFUSED;

  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  snprintf(port_text, sizeof(port_text), "%d", port);

  ret = getaddrinfo(host, port_text, &hints, &addresses);
  if (ret != 0)
    {
      return -EHOSTUNREACH;
    }

  for (address = addresses; address != NULL; address = address->ai_next)
    {
      fd = socket(address->ai_family, address->ai_socktype,
                  address->ai_protocol);
      if (fd < 0)
        {
          last_error = -errno;
          continue;
        }

      last_error = buffer_connect_address(fd, address->ai_addr,
                                          address->ai_addrlen);
      if (last_error == 0)
        {
          struct timeval timeout = {30, 0};

          if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout,
                         sizeof(timeout)) == 0 &&
              setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout,
                         sizeof(timeout)) == 0)
            {
              break;
            }

          last_error = -errno;
        }

      close(fd);
      fd = -1;
    }

  freeaddrinfo(addresses);
  return fd < 0 ? last_error : fd;
}

static char *buffer_base64(const unsigned char *data, size_t length)
{
  static const char alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  size_t output_length = ((length + 2) / 3) * 4;
  char *encoded;
  size_t input = 0;
  size_t output = 0;

  encoded = malloc(output_length + 1);
  if (encoded == NULL)
    {
      return NULL;
    }

  while (input < length)
    {
      uint32_t value = (uint32_t)data[input++] << 16;
      bool second = input < length;
      bool third;

      if (second)
        {
          value |= (uint32_t)data[input++] << 8;
        }

      third = input < length;
      if (third)
        {
          value |= data[input++];
        }

      encoded[output++] = alphabet[(value >> 18) & 0x3f];
      encoded[output++] = alphabet[(value >> 12) & 0x3f];
      encoded[output++] = second ? alphabet[(value >> 6) & 0x3f] : '=';
      encoded[output++] = third ? alphabet[value & 0x3f] : '=';
    }

  encoded[output] = '\0';
  return encoded;
}

static void buffer_copy_json_string(char *destination, size_t size,
                                    cJSON *object, const char *name)
{
  cJSON *value = cJSON_GetObjectItemCaseSensitive(object, name);

  if (value != NULL && cJSON_IsString(value) && value->valuestring != NULL)
    {
      snprintf(destination, size, "%s", value->valuestring);
    }
  else
    {
      destination[0] = '\0';
    }
}

static void buffer_copy_json_actions(char *destination, size_t size,
                                     cJSON *object)
{
  cJSON *array;
  cJSON *item;
  size_t used = 0;

  if (destination == NULL || size == 0)
    {
      return;
    }

  destination[0] = '\0';
  array = cJSON_GetObjectItemCaseSensitive(object, "actions");
  if (array == NULL || !cJSON_IsArray(array))
    {
      return;
    }

  cJSON_ArrayForEach(item, array)
    {
      size_t length;
      bool needs_separator;

      if (!cJSON_IsString(item) || item->valuestring == NULL)
        {
          continue;
        }

      length = strlen(item->valuestring);
      needs_separator = used != 0;
      if (length == 0 || used + (needs_separator ? 1 : 0) + length + 1 > size)
        {
          break;
        }

      if (needs_separator)
        {
          destination[used++] = ',';
        }
      memcpy(destination + used, item->valuestring, length);
      used += length;
      destination[used] = '\0';
    }
}

static int buffer_parse_research(cJSON *root, struct buffer_research_s *research)
{
  cJSON *value = cJSON_GetObjectItemCaseSensitive(root, "research_session");
  cJSON *present, *active, *id, *title, *remaining, *reason, *mode;
  memset(research, 0, sizeof(*research));
  if (value == NULL) return 0;
  present = cJSON_GetObjectItemCaseSensitive(value, "present");
  if (!cJSON_IsObject(value) || !cJSON_IsBool(present)) return -EINVAL;
  if (!cJSON_IsTrue(present)) return 1;
  active = cJSON_GetObjectItemCaseSensitive(value, "active");
  id = cJSON_GetObjectItemCaseSensitive(value, "id");
  title = cJSON_GetObjectItemCaseSensitive(value, "title");
  remaining = cJSON_GetObjectItemCaseSensitive(value, "remaining_ms");
  reason = cJSON_GetObjectItemCaseSensitive(value, "reason");
  mode = cJSON_GetObjectItemCaseSensitive(root, "mode");
  if (!cJSON_IsBool(active) || !cJSON_IsString(id) ||
      !buffer_wire_valid_device_id(id->valuestring) || !cJSON_IsString(title) ||
      strlen(title->valuestring) >= sizeof(research->title) || !cJSON_IsNumber(remaining) ||
      !(remaining->valuedouble >= 0 && remaining->valuedouble <= 7200000) ||
      remaining->valuedouble != remaining->valueint || !cJSON_IsString(reason)) return -EINVAL;
  if (cJSON_IsTrue(active))
    {
      if (reason->valuestring[0] != 0 || !cJSON_IsString(mode) ||
          strcmp(mode->valuestring, "rabbit_hole") != 0) return -EINVAL;
    }
  else if (remaining->valueint != 0 ||
           (strcmp(reason->valuestring, "time_up") != 0 &&
            strcmp(reason->valuestring, "finished") != 0 &&
            strcmp(reason->valuestring, "mode_changed") != 0)) return -EINVAL;
  research->present = true;
  research->active = cJSON_IsTrue(active);
  research->remaining_ms = remaining->valueint;
  snprintf(research->id, sizeof(research->id), "%s", id->valuestring);
  snprintf(research->title, sizeof(research->title), "%s", title->valuestring);
  snprintf(research->reason, sizeof(research->reason), "%s", reason->valuestring);
  return 1;
}

static int buffer_parse_focus_config(cJSON *root, struct buffer_focus_config_s *config)
{
  cJSON *value = cJSON_GetObjectItemCaseSensitive(root, "focus_reminder");
  cJSON *type;
  cJSON *active;
  cJSON *clear;
  cJSON *interval;
  cJSON *remaining;
  cJSON *notice;
  cJSON *revision;

  if (value == NULL) return 0; /* Older phones retain the local timer. */
  type = cJSON_GetObjectItemCaseSensitive(value, "type");
  active = cJSON_GetObjectItemCaseSensitive(value, "active");
  clear = cJSON_GetObjectItemCaseSensitive(value, "clear_notice");
  interval = cJSON_GetObjectItemCaseSensitive(value, "interval_ms");
  remaining = cJSON_GetObjectItemCaseSensitive(value, "remaining_ms");
  notice = cJSON_GetObjectItemCaseSensitive(value, "notice_remaining_ms");
  revision = cJSON_GetObjectItemCaseSensitive(value, "revision");
  if (!cJSON_IsObject(value) || !cJSON_IsString(type) ||
      strcmp(type->valuestring, "FocusReminderConfigUpdated") != 0 ||
      !cJSON_IsBool(active) || !cJSON_IsBool(clear) ||
      !cJSON_IsNumber(interval) || !cJSON_IsNumber(remaining) ||
      !(interval->valuedouble >= BUFFER_FOCUS_INTERVAL_MIN_MS &&
        interval->valuedouble <= BUFFER_FOCUS_INTERVAL_MAX_MS) ||
      !(remaining->valuedouble >= 0 && remaining->valuedouble <= BUFFER_FOCUS_INTERVAL_MAX_MS) ||
      interval->valuedouble != interval->valueint || remaining->valuedouble != remaining->valueint ||
      (notice != NULL && (!cJSON_IsNumber(notice) ||
       !(notice->valuedouble >= 0 && notice->valuedouble <= 15000) ||
       notice->valuedouble != notice->valueint)) ||
      !cJSON_IsString(revision) || !buffer_wire_valid_device_id(revision->valuestring))
    {
      return -EINVAL;
    }
  memset(config, 0, sizeof(*config));
  config->active = cJSON_IsTrue(active);
  config->clear_notice = cJSON_IsTrue(clear);
  config->interval_ms = (uint32_t)interval->valueint;
  config->remaining_ms = (uint32_t)remaining->valueint;
  config->notice_remaining_ms = notice != NULL ? (uint32_t)notice->valueint : 0;
  snprintf(config->revision, sizeof(config->revision), "%s", revision->valuestring);
  return 1;
}

static int buffer_apply_cards(const char *response)
{
  struct buffer_card_s cards[BUFFER_CARD_LIMIT];
  cJSON *root;
  cJSON *type;
  cJSON *array;
  cJSON *response_mode;
  cJSON *item;
  int count = 0;
  int focus_present;
  int ret;
  struct buffer_focus_config_s focus;
  struct buffer_research_s research;

  root = cJSON_Parse(response);
  if (root == NULL)
    {
      return -EINVAL;
    }

  type = cJSON_GetObjectItemCaseSensitive(root, "type");
  if (type == NULL || !cJSON_IsString(type) || type->valuestring == NULL ||
      strcmp(type->valuestring, "display_cards.updated") != 0)
    {
      cJSON_Delete(root);
      return -EINVAL;
    }

  ret = buffer_parse_research(root, &research);
  if (ret < 0) { cJSON_Delete(root); return ret; }

  focus_present = buffer_parse_focus_config(root, &focus);
  if (focus_present < 0)
    {
      cJSON_Delete(root);
      return focus_present;
    }

  array = cJSON_GetObjectItemCaseSensitive(root, "cards");
  if (array == NULL || !cJSON_IsArray(array))
    {
      cJSON_Delete(root);
      return -EINVAL;
    }

  response_mode = cJSON_GetObjectItemCaseSensitive(root, "mode");
  if (response_mode != NULL && cJSON_IsString(response_mode) &&
      response_mode->valuestring != NULL)
    {
      buffer_sync_remote_mode(response_mode->valuestring);
    }

  if (array != NULL && cJSON_IsArray(array))
    {
      cJSON_ArrayForEach(item, array)
        {
          if (count >= BUFFER_CARD_LIMIT || !cJSON_IsObject(item))
            {
              break;
            }

          memset(&cards[count], 0, sizeof(cards[count]));
          buffer_copy_json_string(cards[count].card_id,
                                  sizeof(cards[count].card_id), item, "card_id");
          buffer_copy_json_string(cards[count].capture_id,
                                  sizeof(cards[count].capture_id), item, "capture_id");
          buffer_copy_json_string(cards[count].kind, sizeof(cards[count].kind),
                                  item, "kind");
          buffer_copy_json_string(cards[count].title, sizeof(cards[count].title),
                                  item, "title");
          buffer_copy_json_string(cards[count].summary,
                                  sizeof(cards[count].summary), item, "summary");
          buffer_copy_json_string(cards[count].answer,
                                  sizeof(cards[count].answer), item, "answer");
          buffer_copy_json_string(cards[count].state, sizeof(cards[count].state),
                                  item, "state");
          buffer_copy_json_actions(cards[count].actions,
                                   sizeof(cards[count].actions), item);
          cJSON *created = cJSON_GetObjectItemCaseSensitive(item, "created_at");
          cards[count].created_at = created != NULL && cJSON_IsNumber(created)
                                    ? (int64_t)created->valuedouble : 0;
          if (cards[count].card_id[0] != '\0')
            {
              count++;
            }
        }
    }

  cJSON_Delete(root);
  ret = buffer_update_cards(cards, count);
  if (ret == 0 && focus_present > 0) ret = buffer_sync_focus_config(&focus);
  if (ret == 0) ret = buffer_sync_research(&research);
  return ret;
}

static int buffer_response_is(const char *response, const char *type)
{
  cJSON *root = cJSON_Parse(response);
  cJSON *value;
  int matches = 0;

  if (root == NULL)
    {
      return 0;
    }

  value = cJSON_GetObjectItemCaseSensitive(root, "type");
  if (value != NULL && cJSON_IsString(value) && value->valuestring != NULL)
    {
      matches = strcmp(value->valuestring, type) == 0;
    }

  cJSON_Delete(root);
  return matches;
}

static bool buffer_response_hello_ack_matches(const char *response,
                                              const char *expected_device_id)
{
  cJSON *root;
  cJSON *type;
  cJSON *protocol;
  cJSON *ability;
  cJSON *device_id;
  bool matches = false;

  if (response == NULL || expected_device_id == NULL ||
      !buffer_wire_valid_device_id(expected_device_id))
    {
      return false;
    }

  root = cJSON_Parse(response);
  if (root == NULL)
    {
      return false;
    }

  type = cJSON_GetObjectItemCaseSensitive(root, "type");
  protocol = cJSON_GetObjectItemCaseSensitive(root, "protocol");
  ability = cJSON_GetObjectItemCaseSensitive(root, "ability");
  device_id = cJSON_GetObjectItemCaseSensitive(root, "device_id");
  if (type != NULL && protocol != NULL && ability != NULL &&
      device_id != NULL && cJSON_IsString(type) && cJSON_IsNumber(protocol) &&
      cJSON_IsString(ability) && cJSON_IsString(device_id) &&
      type->valuestring != NULL && ability->valuestring != NULL &&
      device_id->valuestring != NULL &&
      strcmp(type->valuestring, "hello_ack") == 0 &&
      protocol->valueint == 1 &&
      strcmp(ability->valuestring, "app.buffer") == 0 &&
      buffer_wire_valid_device_id(device_id->valuestring) &&
      strcmp(device_id->valuestring, expected_device_id) == 0)
    {
      matches = true;
    }

  cJSON_Delete(root);
  return matches;
}

static int buffer_wire_hmac_proof(const char *token, const char *nonce,
                                  const char *device_id, char *proof,
                                  size_t proof_size)
{
  static const char alphabet[] = "0123456789abcdef";
  const mbedtls_md_info_t *info;
  unsigned char digest[32];
  char message[BUFFER_WIRE_DISCOVERY_LINE];
  size_t index;
  size_t message_length;
  int ret;

  if (token == NULL || nonce == NULL || device_id == NULL || proof == NULL ||
      proof_size < BUFFER_WIRE_AUTH_PROOF_SIZE)
    {
      return -EINVAL;
    }

  ret = snprintf(message, sizeof(message), "%s:%s", nonce, device_id);
  if (ret < 0 || (size_t)ret >= sizeof(message))
    {
      return -ENAMETOOLONG;
    }
  message_length = (size_t)ret;

  info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == NULL)
    {
      return -ENOSYS;
    }

  ret = mbedtls_md_hmac(info, (const unsigned char *)token, strlen(token),
                        (const unsigned char *)message, message_length,
                        digest);
  if (ret != 0)
    {
      return -EIO;
    }

  for (index = 0; index < sizeof(digest); index++)
    {
      proof[index * 2] = alphabet[digest[index] >> 4];
      proof[index * 2 + 1] = alphabet[digest[index] & 0x0f];
    }
  proof[sizeof(digest) * 2] = '\0';
  return 0;
}

static bool buffer_wire_hex64(const char *value)
{
  if (value == NULL || strlen(value) != 64) return false;
  for (size_t i = 0; i < 64; i++)
    if (!((value[i] >= '0' && value[i] <= '9') ||
          (value[i] >= 'a' && value[i] <= 'f'))) return false;
  return true;
}

static bool buffer_wire_verify_phone(cJSON *challenge, const char *token,
                                     const char *client_nonce, const char *vela_id,
                                     const char *phone_id)
{
  cJSON *nonce = cJSON_GetObjectItemCaseSensitive(challenge, "nonce");
  cJSON *id = cJSON_GetObjectItemCaseSensitive(challenge, "device_id");
  cJSON *proof = cJSON_GetObjectItemCaseSensitive(challenge, "phone_proof");
  char context[320];
  char expected[BUFFER_WIRE_AUTH_PROOF_SIZE];
  unsigned char difference = 0;
  int length;
  if (!cJSON_IsString(nonce) || !cJSON_IsString(id) || !cJSON_IsString(proof) ||
      !buffer_wire_hex64(client_nonce) || !buffer_wire_hex64(nonce->valuestring) ||
      !buffer_wire_hex64(proof->valuestring) || !buffer_wire_valid_device_id(vela_id) ||
      !buffer_wire_valid_device_id(phone_id) || strcmp(id->valuestring, phone_id) != 0)
    return false;
  length = snprintf(context, sizeof(context), "buffer-phone-v1:%s:%s:%s",
                    client_nonce, nonce->valuestring, vela_id);
  if (length < 0 || (size_t)length >= sizeof(context) ||
      buffer_wire_hmac_proof(token, context, phone_id, expected, sizeof(expected)) < 0)
    return false;
  for (size_t i = 0; i < 64; i++) difference |= expected[i] ^ proof->valuestring[i];
  return difference == 0;
}

static int buffer_wire_enrollment_proof(const char *token, const char *nonce,
                                        const char *device_id, const char *phone_id,
                                        const char *request_id, char *proof, size_t size)
{
  char context[320];
  if (!token || !nonce || !device_id || !phone_id || !request_id || strlen(request_id) != 32)
    return -EINVAL;
  for (size_t i = 0; i < 32; i++)
    if (!((request_id[i] >= '0' && request_id[i] <= '9') ||
          (request_id[i] >= 'a' && request_id[i] <= 'f'))) return -EINVAL;
  if (!buffer_wire_hex64(nonce) || !buffer_wire_valid_device_id(device_id) ||
      !buffer_wire_valid_device_id(phone_id)) return -EINVAL;
  int n = snprintf(context, sizeof(context), "buffer-enrollment-v1:%s:%s:%s", nonce, device_id, phone_id);
  if (n < 0 || (size_t)n >= sizeof(context)) return -ENAMETOOLONG;
  return buffer_wire_hmac_proof(token, context, request_id, proof, size);
}

static int buffer_response_ack_matches(const char *response,
                                       const char *capture_id)
{
  cJSON *root = cJSON_Parse(response);
  cJSON *type;
  cJSON *id;
  int matches = 0;

  if (root == NULL)
    {
      return 0;
    }

  type = cJSON_GetObjectItemCaseSensitive(root, "type");
  id = cJSON_GetObjectItemCaseSensitive(root, "capture_id");
  if (type != NULL && id != NULL && cJSON_IsString(type) &&
      cJSON_IsString(id) && type->valuestring != NULL && id->valuestring != NULL)
    {
      matches = strcmp(type->valuestring, "ack") == 0 &&
                strcmp(id->valuestring, capture_id) == 0;
    }

  cJSON_Delete(root);
  return matches;
}

static int buffer_response_action_ack_matches(const char *response,
                                               const char *action_id,
                                               const char *card_id,
                                               const char *action)
{
  cJSON *root = cJSON_Parse(response);
  cJSON *type;
  cJSON *response_action_id;
  cJSON *response_card_id;
  cJSON *response_action;
  int matches = 0;

  if (root == NULL)
    {
      return 0;
    }

  type = cJSON_GetObjectItemCaseSensitive(root, "type");
  response_action_id = cJSON_GetObjectItemCaseSensitive(root, "action_id");
  response_card_id = cJSON_GetObjectItemCaseSensitive(root, "card_id");
  response_action = cJSON_GetObjectItemCaseSensitive(root, "action");
  if (type != NULL && cJSON_IsString(type) && type->valuestring != NULL &&
      strcmp(type->valuestring, "ack") == 0)
    {
      if (response_action_id != NULL && cJSON_IsString(response_action_id) &&
          response_action_id->valuestring != NULL)
        {
          matches = action_id != NULL && action_id[0] != '\0' &&
                    strcmp(response_action_id->valuestring, action_id) == 0;
        }
      else
        {
          /* Older phone builds did not echo action_id.  Match both fields so
           * a response for another operation cannot clear this queue item. */
          matches = response_card_id != NULL && response_action != NULL &&
                    cJSON_IsString(response_card_id) &&
                    cJSON_IsString(response_action) &&
                    response_card_id->valuestring != NULL &&
                    response_action->valuestring != NULL &&
                    strcmp(response_card_id->valuestring, card_id) == 0 &&
                    strcmp(response_action->valuestring, action) == 0;
        }
    }

  cJSON_Delete(root);
  return matches;
}

static int buffer_response_mode_ack_matches(const char *response,
                                            const char *mode_id,
                                            const char *mode)
{
  cJSON *root = cJSON_Parse(response);
  cJSON *type;
  cJSON *response_mode_id;
  cJSON *response_mode;
  int matches = 0;

  if (root == NULL)
    {
      return 0;
    }

  type = cJSON_GetObjectItemCaseSensitive(root, "type");
  response_mode_id = cJSON_GetObjectItemCaseSensitive(root, "mode_id");
  response_mode = cJSON_GetObjectItemCaseSensitive(root, "mode");
  if (type != NULL && cJSON_IsString(type) && type->valuestring != NULL &&
      strcmp(type->valuestring, "ack") == 0)
    {
      if (response_mode_id != NULL && cJSON_IsString(response_mode_id) &&
          response_mode_id->valuestring != NULL)
        {
          matches = mode_id != NULL && mode_id[0] != '\0' &&
                    strcmp(response_mode_id->valuestring, mode_id) == 0;
        }
      else
        {
          matches = response_mode != NULL && cJSON_IsString(response_mode) &&
                    response_mode->valuestring != NULL &&
                    strcmp(response_mode->valuestring, mode) == 0;
        }
    }

  cJSON_Delete(root);
  return matches;
}

static int buffer_send_mode(int fd, const char *mode, const char *mode_id,
                            char *response,
                            size_t response_size)
{
  cJSON *request = cJSON_CreateObject();
  int ret;

  if (request == NULL)
    {
      return -ENOMEM;
    }

  cJSON_AddStringToObject(request, "type", "mode.changed");
  cJSON_AddStringToObject(request, "mode", mode);
  cJSON_AddStringToObject(request, "mode_id", mode_id);
  ret = buffer_exchange(fd, request, response, response_size);
  cJSON_Delete(request);
  return ret;
}

static int buffer_send_action(int fd, const char *card_id,
                              const char *action_id, const char *action,
                              char *response, size_t response_size)
{
  cJSON *request = cJSON_CreateObject();
  int ret;

  if (request == NULL)
    {
      return -ENOMEM;
    }

  cJSON_AddStringToObject(request, "type", "card.action");
  cJSON_AddStringToObject(request, "card_id", card_id);
  cJSON_AddStringToObject(request, "action_id", action_id);
  cJSON_AddStringToObject(request, "action", action);
  ret = buffer_exchange(fd, request, response, response_size);
  cJSON_Delete(request);
  return ret;
}

static int buffer_send_device_status(int fd, const char *device_id,
                                     const char *mode, int pending_captures,
                                     bool cards_stale, bool recording,
                                     char *response, size_t response_size)
{
  cJSON *request = cJSON_CreateObject();
  int ret;

  if (request == NULL)
    {
      return -ENOMEM;
    }

  cJSON_AddStringToObject(request, "type", "device.status");
  cJSON_AddStringToObject(request, "device_id", device_id);
  cJSON_AddStringToObject(request, "mode", mode);
  cJSON_AddNumberToObject(request, "pending_captures", pending_captures);
  cJSON_AddBoolToObject(request, "cards_stale", cards_stale);
  cJSON_AddBoolToObject(request, "recording", recording);
  ret = buffer_exchange(fd, request, response, response_size);
  cJSON_Delete(request);
  return ret;
}

static int buffer_send_upload_retry(int fd, const char *device_id,
                                    const char *capture_id, const char *detail,
                                    char *response, size_t response_size)
{
  cJSON *request = cJSON_CreateObject();
  int ret;

  if (request == NULL)
    {
      return -ENOMEM;
    }

  cJSON_AddStringToObject(request, "type", "upload.retry");
  cJSON_AddStringToObject(request, "device_id", device_id);
  cJSON_AddStringToObject(request, "capture_id", capture_id);
  cJSON_AddStringToObject(request, "detail", detail);
  ret = buffer_exchange(fd, request, response, response_size);
  cJSON_Delete(request);
  if (ret == 0 && !buffer_response_ack_matches(response, capture_id))
    {
      return -EIO;
    }
  return ret;
}

int buffer_wire_sync_once(void)
{
  char client_nonce[65];
  unsigned char random_nonce[32];
  char host[96];
  char token[128];
  char phone_device_id[BUFFER_ID_SIZE];
  char device_id[BUFFER_ID_SIZE];
  char *response;
  char mode[BUFFER_MODE_SIZE];
  char mode_id[BUFFER_ACTION_ID_SIZE];
  char display_mode[BUFFER_MODE_SIZE];
  char action_card_id[BUFFER_ID_SIZE];
  char action_id[BUFFER_ACTION_ID_SIZE];
  char action[BUFFER_ACTION_SIZE];
  int fd;
  int displayed_cards;
  int pending_captures;
  int64_t created_at;
  uint32_t duration_ms;
  int ret;
  int sync_error = 0;
  bool upload_failed = false;

  buffer_wire_load_standalone_state();
  buffer_wire_refresh_pending_count();
  buffer_pairing_snapshot(device_id, sizeof(device_id), NULL, 0);
  if (device_id[0] == '\0')
    {
      ret = buffer_store_load_device_id(device_id, sizeof(device_id));
      if (ret < 0)
        {
          buffer_set_status("设备身份不可用：%d", ret);
          return ret;
        }
    }

  ret = buffer_wire_load_phone_route(host, sizeof(host), token, sizeof(token),
                                     phone_device_id, sizeof(phone_device_id));
  if (ret < 0)
    {
      buffer_mark_cards_stale();
      if (ret == -EACCES)
        {
          buffer_set_status("等待添加：在手机输入 S1 屏幕验证码");
        }
      else
        {
          buffer_set_status("等待手机连接，请确认手机与 S1 在同一局域网");
        }
      return ret;
    }

  fd = buffer_connect(host, CONFIG_BUFFER_VELA_PHONE_PORT);
  if (fd < 0)
    {
      buffer_mark_cards_stale();
      buffer_set_status("手机离线，队列保留（%s）", host);
      return fd;
    }

  response = malloc(BUFFER_WIRE_MAX_LINE);
  if (response == NULL)
    {
      close(fd);
      return -ENOMEM;
    }

  cJSON *hello = cJSON_CreateObject();
  if (hello == NULL)
    {
      close(fd);
      free(response);
      return -ENOMEM;
    }

  arc4random_buf(random_nonce, sizeof(random_nonce));
  for (size_t i = 0; i < sizeof(random_nonce); i++)
    snprintf(client_nonce + i * 2, 3, "%02x", random_nonce[i]);
  cJSON_AddStringToObject(hello, "client_nonce", client_nonce);
  cJSON_AddStringToObject(hello, "type", "hello");
  cJSON_AddNumberToObject(hello, "protocol", 1);
  cJSON_AddStringToObject(hello, "device_id", device_id);
  cJSON_AddStringToObject(hello, "auth", "challenge");
  ret = buffer_exchange(fd, hello, response, BUFFER_WIRE_MAX_LINE);
  cJSON_Delete(hello);
  if (ret < 0 || !buffer_response_is(response, "hello_challenge"))
    {
      close(fd);
      free(response);
      buffer_mark_cards_stale();
      buffer_set_status("手机配对被拒绝");
      return ret < 0 ? ret : -EACCES;
    }

  {
    cJSON *challenge = cJSON_Parse(response);
    cJSON *nonce_value = challenge == NULL
                           ? NULL
                           : cJSON_GetObjectItemCaseSensitive(challenge,
                                                              "nonce");
    char nonce[BUFFER_WIRE_DISCOVERY_LINE];
    char proof[BUFFER_WIRE_AUTH_PROOF_SIZE];

    if (!buffer_wire_verify_phone(challenge, token, client_nonce, device_id, phone_device_id) ||
        nonce_value == NULL || !cJSON_IsString(nonce_value) ||
        nonce_value->valuestring == NULL ||
        strlen(nonce_value->valuestring) >= sizeof(nonce))
      {
        cJSON_Delete(challenge);
        close(fd);
        free(response);
        buffer_mark_cards_stale();
        buffer_set_status("手机挑战无效");
        return -EACCES;
      }

    snprintf(nonce, sizeof(nonce), "%s", nonce_value->valuestring);
    cJSON_Delete(challenge);
    ret = buffer_wire_hmac_proof(token, nonce, device_id, proof,
                                 sizeof(proof));
    if (ret < 0)
      {
        close(fd);
        free(response);
        buffer_mark_cards_stale();
        buffer_set_status("同步认证组件不可用：%d", ret);
        return ret;
      }

    hello = cJSON_CreateObject();
    if (hello == NULL)
      {
        close(fd);
        free(response);
        return -ENOMEM;
      }
    cJSON_AddStringToObject(hello, "type", "hello_proof");
    cJSON_AddNumberToObject(hello, "protocol", 1);
    cJSON_AddStringToObject(hello, "device_id", device_id);
    cJSON_AddStringToObject(hello, "proof", proof);
    /* Read a single persisted binding snapshot. Never attach another owner's receipt. */
    char receipt_host[96], receipt_token[128], receipt_phone[BUFFER_ID_SIZE], receipt_id[33];
    int receipt_ret = buffer_store_load_enrollment(receipt_host, sizeof(receipt_host),
        receipt_token, sizeof(receipt_token), receipt_phone, sizeof(receipt_phone), receipt_id, sizeof(receipt_id));
    if (receipt_ret == 0 && receipt_id[0] && !strcmp(receipt_token, token) && !strcmp(receipt_phone, phone_device_id))
      {
        char receipt_proof[BUFFER_WIRE_AUTH_PROOF_SIZE];
        receipt_ret = buffer_wire_enrollment_proof(token, nonce, device_id, phone_device_id,
                                                  receipt_id, receipt_proof, sizeof(receipt_proof));
        if (receipt_ret == 0)
          {
            cJSON_AddStringToObject(hello, "enrollment_id", receipt_id);
            cJSON_AddStringToObject(hello, "enrollment_proof", receipt_proof);
          }
      }
    ret = buffer_exchange(fd, hello, response, BUFFER_WIRE_MAX_LINE);
    cJSON_Delete(hello);
  }

  if (ret < 0 || !buffer_response_hello_ack_matches(response,
                                                     phone_device_id))
    {
      close(fd);
      free(response);
      buffer_mark_cards_stale();
      buffer_set_status("手机身份校验失败");
      return ret < 0 ? ret : -EACCES;
    }

  pthread_mutex_lock(&g_buffer.lock);
  snprintf(mode, sizeof(mode), "%s", g_buffer.mode);
  snprintf(mode_id, sizeof(mode_id), "%s", g_buffer.mode_id);
  snprintf(display_mode, sizeof(display_mode), "%s", g_buffer.mode);
  pending_captures = g_buffer.pending_count;
  bool cards_stale = g_buffer.cards_stale;
  bool recording = g_buffer.recording;
  if (g_buffer.action_requested)
    {
      snprintf(action_card_id, sizeof(action_card_id), "%s",
               g_buffer.action_card_id);
      snprintf(action_id, sizeof(action_id), "%s", g_buffer.action_id);
      snprintf(action, sizeof(action), "%s", g_buffer.action);
    }
  else
    {
      action_card_id[0] = '\0';
      action_id[0] = '\0';
      action[0] = '\0';
    }
  pthread_mutex_unlock(&g_buffer.lock);

  ret = buffer_send_device_status(fd, device_id, mode, pending_captures,
                                  cards_stale, recording, response,
                                  BUFFER_WIRE_MAX_LINE);
  if (ret < 0)
    {
      sync_error = ret;
      goto sync_done;
    }
  if (!buffer_response_is(response, "ack"))
    {
      buffer_set_status("设备状态上报失败，稍后重试");
      sync_error = -EIO;
      goto sync_done;
    }

  pthread_mutex_lock(&g_buffer.lock);
  bool mode_dirty = g_buffer.mode_dirty;
  pthread_mutex_unlock(&g_buffer.lock);
  if (mode_dirty)
    {
      ret = buffer_send_mode(fd, mode, mode_id, response,
                             BUFFER_WIRE_MAX_LINE);
      if (ret < 0)
        {
          sync_error = ret;
          goto sync_done;
        }
      if (ret == 0 && buffer_response_mode_ack_matches(response, mode_id,
                                                       mode))
        {
          int persist_ret = 0;

          pthread_mutex_lock(&g_buffer.lock);
          /* A newer local mode may have been selected while the request was
           * on the wire.  Only consume the mode that produced this ACK. */
          if (g_buffer.mode_dirty != false &&
              strcmp(g_buffer.mode_id, mode_id) == 0)
            {
              /* Do not drop the durable pending file until the normal mode
               * file is also committed.  A transient write failure must
               * survive an ACK and a reboot. */
              persist_ret = buffer_store_save_mode(mode);
              if (persist_ret == 0)
                {
                  g_buffer.mode_dirty = false;
                  /* The local mode setter also writes this file while holding
                   * the same lock.  Clear it before releasing the lock so a
                   * newer mode cannot be deleted by this older ACK. */
                  buffer_store_clear_pending_mode();
                }
            }
          pthread_mutex_unlock(&g_buffer.lock);
          if (persist_ret < 0)
            {
              buffer_set_status("模式已同步，但本地保存失败：%d，将继续重试",
                                persist_ret);
            }
        }
      else
        {
          sync_error = -EIO;
          buffer_set_status("模式同步回执无效，稍后重试");
          goto sync_done;
        }
    }

  if (action_card_id[0] != '\0')
    {
      ret = buffer_send_action(fd, action_card_id, action_id, action,
                               response, BUFFER_WIRE_MAX_LINE);
      if (ret < 0)
        {
          sync_error = ret;
          goto sync_done;
        }
      if (ret == 0 && buffer_response_action_ack_matches(response, action_id,
                                                         action_card_id,
                                                         action))
        {
          pthread_mutex_lock(&g_buffer.lock);
          /* Preserve a newer button press made while the old action was
           * being delivered. */
          if (g_buffer.action_requested != false &&
              strcmp(g_buffer.action_id, action_id) == 0)
            {
              g_buffer.action_requested = false;
              /* buffer_request_action() persists a replacement while holding
               * this lock.  Remove the acknowledged file before unlocking so
               * that replacement cannot be mistaken for the old ACK. */
              buffer_store_clear_pending_action();
            }
          pthread_mutex_unlock(&g_buffer.lock);
        }
      else
        {
          sync_error = -EIO;
          buffer_set_status("卡片操作回执无效，稍后重试");
          goto sync_done;
        }
    }

  char (*ids)[BUFFER_ID_SIZE] = calloc(CONFIG_BUFFER_VELA_QUEUE_LIMIT,
                                       sizeof(*ids));
  if (ids == NULL)
    {
      sync_error = -ENOMEM;
      buffer_set_status("同步内存不足，录音队列稍后重试");
      goto sync_done;
    }

  {
    int count = buffer_store_list_pending(ids, CONFIG_BUFFER_VELA_QUEUE_LIMIT);
    int i;

    if (count < 0)
      {
        sync_error = count;
        buffer_set_status("读取录音队列失败：%d", count);
        free(ids);
        goto sync_done;
      }

    for (i = 0; i < count; i++)
      {
          unsigned char *audio = NULL;
          char *audio_b64 = NULL;
          size_t audio_length = 0;
          cJSON *capture;
          int retry_ret;

          buffer_set_status("正在上传录音 %d/%d", i + 1, count);
          (void)buffer_store_set_capture_state(ids[i], "uploading_metadata");

          ret = buffer_store_read_audio(ids[i], &audio, &audio_length);
          if (ret < 0)
            {
              (void)buffer_store_set_capture_state(ids[i], "failed_retryable");
              upload_failed = true;
              retry_ret = buffer_send_upload_retry(
                  fd, device_id, ids[i], "audio_read_failed", response,
                  BUFFER_WIRE_MAX_LINE);
              if (retry_ret < 0)
                {
                  sync_error = retry_ret;
                  break;
                }
              buffer_set_status("录音 %s 读取失败，将稍后重试", ids[i]);
              continue;
            }

          audio_b64 = buffer_base64(audio, audio_length);
          free(audio);
          if (audio_b64 == NULL)
            {
              (void)buffer_store_set_capture_state(ids[i], "failed_retryable");
              upload_failed = true;
              retry_ret = buffer_send_upload_retry(
                  fd, device_id, ids[i], "audio_encode_failed", response,
                  BUFFER_WIRE_MAX_LINE);
              if (retry_ret < 0)
                {
                  sync_error = retry_ret;
                  break;
                }
              buffer_set_status("录音 %s 编码失败，将稍后重试", ids[i]);
              continue;
            }

          (void)buffer_store_set_capture_state(ids[i], "uploading_blob");
          ret = buffer_store_read_metadata(ids[i], &created_at, &duration_ms,
                                           mode, sizeof(mode));
          if (ret < 0)
            {
              (void)buffer_store_set_capture_state(ids[i],
                                                   "failed_retryable");
              upload_failed = true;
              retry_ret = buffer_send_upload_retry(
                  fd, device_id, ids[i], "metadata_read_failed", response,
                  BUFFER_WIRE_MAX_LINE);
              free(audio_b64);
              if (retry_ret < 0)
                {
                  sync_error = retry_ret;
                  break;
                }
              buffer_set_status("录音 %s 元数据读取失败，将稍后重试",
                                ids[i]);
              continue;
            }
          capture = cJSON_CreateObject();
          if (capture != NULL)
            {
              cJSON_AddStringToObject(capture, "type", "capture.created");
              cJSON_AddStringToObject(capture, "capture_id", ids[i]);
              cJSON_AddStringToObject(capture, "device_id", device_id);
              cJSON_AddStringToObject(capture, "input", "button");
              cJSON_AddNumberToObject(capture, "created_at", (double)created_at);
              cJSON_AddStringToObject(capture, "mode", mode);
              cJSON_AddStringToObject(capture, "audio_format",
                                      "audio/wav;codec=pcm_s16le;rate=16000;channels=1");
              cJSON_AddNumberToObject(capture, "duration_ms", duration_ms);
              cJSON_AddStringToObject(capture, "audio_b64", audio_b64);
              (void)buffer_store_set_capture_state(ids[i], "waiting_ack");
              ret = buffer_exchange(fd, capture, response,
                                    BUFFER_WIRE_MAX_LINE);
              if (ret < 0)
                {
                  (void)buffer_store_set_capture_state(ids[i],
                                                       "failed_retryable");
                  sync_error = ret;
                  buffer_set_status("录音 %s 上传中断，将稍后重试", ids[i]);
                  cJSON_Delete(capture);
                  free(audio_b64);
                  break;
                }
              if (buffer_response_ack_matches(response, ids[i]))
                {
                  (void)buffer_store_set_capture_state(ids[i], "uploaded");
                  ret = buffer_store_ack(ids[i]);
                  buffer_wire_refresh_pending_count();
                  if (ret < 0)
                    {
                      upload_failed = true;
                      buffer_set_status("录音 %s 已上传，但本地清理失败：%d",
                                        ids[i], ret);
                    }
                  else
                    {
                      buffer_set_status("录音 %s 已上传并清理本地缓存",
                                        ids[i]);
                    }
                }
              else
                {
                  (void)buffer_store_set_capture_state(ids[i],
                                                       "failed_retryable");
                  upload_failed = true;
                  retry_ret = buffer_send_upload_retry(
                      fd, device_id, ids[i], "capture_ack_missing", response,
                      BUFFER_WIRE_MAX_LINE);
                  if (retry_ret < 0)
                    {
                      sync_error = retry_ret;
                      cJSON_Delete(capture);
                      free(audio_b64);
                      break;
                    }
                  buffer_set_status("录音 %s 上传失败，将稍后重试", ids[i]);
                }
              cJSON_Delete(capture);
            }
          else
            {
              (void)buffer_store_set_capture_state(ids[i], "failed_retryable");
              upload_failed = true;
              retry_ret = buffer_send_upload_retry(
                  fd, device_id, ids[i], "capture_request_create_failed",
                  response, BUFFER_WIRE_MAX_LINE);
              if (retry_ret < 0)
                {
                  sync_error = retry_ret;
                }
              buffer_set_status("录音 %s 请求创建失败，将稍后重试", ids[i]);
            }

          free(audio_b64);
          if (sync_error < 0)
            {
              break;
            }
        }

    free(ids);
  }

  if (sync_error < 0)
    {
      goto sync_done;
    }

  cJSON *cards_request = cJSON_CreateObject();
  if (cards_request == NULL)
    {
      sync_error = -ENOMEM;
      buffer_set_status("卡片同步内存不足，稍后重试");
      goto sync_done;
    }

  {
    cJSON_AddStringToObject(cards_request, "type", "cards.get");
    cJSON_AddStringToObject(cards_request, "mode", display_mode);
    ret = buffer_exchange(fd, cards_request, response,
                          BUFFER_WIRE_MAX_LINE);
    if (ret < 0)
      {
        sync_error = ret;
      }
    else if (buffer_response_is(response, "display_cards.updated"))
      {
        ret = buffer_apply_cards(response);
        if (ret == 0)
        {
          buffer_snapshot(NULL, 0, NULL, 0, NULL, &displayed_cards, NULL);
          if (upload_failed)
            {
              buffer_set_status("卡片已同步：%d 张，仍有录音待重试（%d 条）",
                                displayed_cards,
                                buffer_store_pending_count());
            }
          else
            {
              buffer_set_status("已同步：%d 张卡片，待上传 %d 条",
                                displayed_cards,
                                buffer_store_pending_count());
            }
        }
      else
        {
          sync_error = ret;
          buffer_mark_cards_stale();
          buffer_set_status("手机返回的卡片数据无效，稍后重试");
        }
      }
    else
      {
        sync_error = -EIO;
        buffer_mark_cards_stale();
        buffer_set_status("卡片同步回执无效，稍后重试");
      }
    cJSON_Delete(cards_request);
    }

sync_done:
  close(fd);
  free(response);
  return sync_error;
}

void *buffer_wire_thread_main(void *arg)
{
  unsigned int network_failures = 0;

  (void)arg;

  while (true)
    {
      int i;

      pthread_mutex_lock(&g_buffer.lock);
      bool stopping = g_buffer.stopping;
      g_buffer.sync_requested = false;
      pthread_mutex_unlock(&g_buffer.lock);
      if (stopping)
        {
          break;
        }

      int sync_ret = buffer_wire_sync_once();
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.phone_synced = sync_ret == 0;
      pthread_mutex_unlock(&g_buffer.lock);
      if (buffer_wire_should_reconnect(sync_ret))
        {
          network_failures++;
          if (network_failures >= BUFFER_WIRE_RECONNECT_AFTER_FAILURES)
            {
              buffer_set_status("网络连接中断，正在恢复 Wi-Fi");
              if (buffer_wire_refresh_phone_host() == 0)
                {
                  network_failures = 0;
                  buffer_set_status("已发现手机新地址，正在同步");
                  buffer_request_sync();
                }
              else if (buffer_wifi_reconnect() == 0)
                {
                  network_failures = 0;
                  buffer_request_sync();
                }
            }
        }
      else if (sync_ret == 0)
        {
          network_failures = 0;
        }

      for (i = 0; i < 50; i++)
        {
          usleep(100000);
          pthread_mutex_lock(&g_buffer.lock);
          stopping = g_buffer.stopping;
          bool requested = g_buffer.sync_requested;
          pthread_mutex_unlock(&g_buffer.lock);
          if (stopping || requested)
            {
              break;
            }
        }
    }

  return NULL;
}
