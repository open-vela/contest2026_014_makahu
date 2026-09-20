/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela discovery and pairing
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_ble_service.h"

#include <arpa/inet.h>
#include <errno.h>
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

#define BUFFER_PAIRING_MAX_LINE 4096
#define BUFFER_PAIRING_BACKLOG 2
#define BUFFER_PAIRING_MAX_FAILURES 5
#define BUFFER_PAIRING_COOLDOWN_SECONDS 30
#define BUFFER_PAIRING_READ_TIMEOUT_MS 5000

static bool buffer_pairing_running(void)
{
  bool running;

  pthread_mutex_lock(&g_buffer.lock);
  running = g_buffer.initialized && !g_buffer.stopping;
  pthread_mutex_unlock(&g_buffer.lock);
  return running;
}

static bool buffer_pairing_rate_limited(void)
{
  int64_t now = (int64_t)time(NULL);
  bool limited;

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.pairing_block_until != 0 &&
      now >= g_buffer.pairing_block_until)
    {
      g_buffer.pairing_failures = 0;
      g_buffer.pairing_block_until = 0;
    }
  limited = g_buffer.pairing_block_until != 0 &&
            now < g_buffer.pairing_block_until;
  pthread_mutex_unlock(&g_buffer.lock);
  return limited;
}

static void buffer_pairing_record_failure(void)
{
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pairing_failures++;
  if (g_buffer.pairing_failures >= BUFFER_PAIRING_MAX_FAILURES)
    {
      g_buffer.pairing_block_until =
        (int64_t)time(NULL) + BUFFER_PAIRING_COOLDOWN_SECONDS;
      g_buffer.pairing_failures = 0;
    }
  pthread_mutex_unlock(&g_buffer.lock);
}

static void buffer_pairing_clear_failures(void)
{
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pairing_failures = 0;
  g_buffer.pairing_block_until = 0;
  pthread_mutex_unlock(&g_buffer.lock);
}

void buffer_pairing_snapshot(char *device_id, size_t device_id_size,
                             char *pairing_code, size_t pairing_code_size)
{
  pthread_mutex_lock(&g_buffer.lock);
  if (device_id != NULL && device_id_size > 0)
    {
      snprintf(device_id, device_id_size, "%s", g_buffer.device_id);
    }
  if (pairing_code != NULL && pairing_code_size > 0)
    {
      snprintf(pairing_code, pairing_code_size, "%s", g_buffer.pairing_code);
    }
  pthread_mutex_unlock(&g_buffer.lock);
}

static int buffer_pairing_send_all(int fd, const void *data, size_t length)
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

static int buffer_pairing_send_json(int fd, cJSON *message)
{
  char *encoded;
  int ret;

  encoded = cJSON_PrintUnformatted(message);
  if (encoded == NULL)
    {
      return -ENOMEM;
    }

  ret = buffer_pairing_send_all(fd, encoded, strlen(encoded));
  free(encoded);
  if (ret < 0)
    {
      return ret;
    }

  return buffer_pairing_send_all(fd, "\n", 1);
}

static int buffer_pairing_read_line(int fd, char *line, size_t line_size)
{
  size_t used = 0;
  struct timespec started;

  if (line == NULL || line_size < 2)
    {
      return -EINVAL;
    }

  if (clock_gettime(CLOCK_MONOTONIC, &started) < 0)
    {
      return -errno;
    }

  while (used + 1 < line_size)
    {
      unsigned char block[128];
      ssize_t received;
      size_t index;
      struct timespec now;
      struct pollfd ready = {.fd = fd, .events = POLLIN};
      int64_t elapsed_ms;
      int ret;

      if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
        {
          return -errno;
        }
      elapsed_ms = (int64_t)(now.tv_sec - started.tv_sec) * 1000 +
                   (now.tv_nsec - started.tv_nsec) / 1000000;
      if (elapsed_ms >= BUFFER_PAIRING_READ_TIMEOUT_MS)
        {
          return -ETIMEDOUT;
        }

      ret = poll(&ready, 1, BUFFER_PAIRING_READ_TIMEOUT_MS - (int)elapsed_ms);
      if (ret < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }
          return -errno;
        }
      if (ret == 0)
        {
          return -ETIMEDOUT;
        }
      if ((ready.revents & POLLIN) == 0)
        {
          return -ECONNRESET;
        }

      received = recv(fd, block, sizeof(block), MSG_DONTWAIT);

      if (received < 0)
        {
          if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)
            {
              continue;
            }

          return -errno;
        }

      if (received == 0)
        {
          return -ECONNRESET;
        }

      for (index = 0; index < (size_t)received; index++)
        {
          if (block[index] == '\n')
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

          line[used++] = (char)block[index];
        }
    }

  return -EFBIG;
}

static bool buffer_pairing_request_is(cJSON *root, const char *type)
{
  cJSON *message_type;
  cJSON *protocol;

  if (root == NULL || !cJSON_IsObject(root))
    {
      return false;
    }

  message_type = cJSON_GetObjectItemCaseSensitive(root, "type");
  protocol = cJSON_GetObjectItemCaseSensitive(root, "protocol");
  return message_type != NULL && protocol != NULL &&
         cJSON_IsString(message_type) && cJSON_IsNumber(protocol) &&
         message_type->valuestring != NULL &&
         strcmp(message_type->valuestring, type) == 0 &&
         protocol->valueint == 1;
}

static cJSON *buffer_pairing_error(const char *code, const char *detail)
{
  cJSON *response = cJSON_CreateObject();

  if (response != NULL)
    {
      cJSON_AddStringToObject(response, "type", "error");
      cJSON_AddNumberToObject(response, "protocol", 1);
      cJSON_AddStringToObject(response, "code", code);
      if (detail != NULL)
        {
          cJSON_AddStringToObject(response, "detail", detail);
        }
    }

  return response;
}

static bool buffer_pairing_valid_device_id(const char *device_id)
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

static int buffer_pairing_send_datagram(int fd,
                                        const struct sockaddr_in *peer,
                                        socklen_t peer_length,
                                        cJSON *response)
{
  char *encoded;
  size_t length;
  int ret;

  encoded = cJSON_PrintUnformatted(response);
  if (encoded == NULL)
    {
      return -ENOMEM;
    }

  length = strlen(encoded);
  ret = (int)sendto(fd, encoded, length, 0,
                    (const struct sockaddr *)peer, peer_length);
  if (ret >= 0 && (size_t)ret != length)
    {
      ret = -EIO;
    }
  else if (ret >= 0)
    {
      ret = 0;
    }

  free(encoded);
  return ret;
}

static int buffer_pairing_handle_discovery(int fd)
{
  char *line;
  struct sockaddr_in peer;
  socklen_t peer_length = sizeof(peer);
  ssize_t received;
  cJSON *request;
  int ret;

  line = malloc(BUFFER_PAIRING_MAX_LINE);
  if (line == NULL)
    {
      return -ENOMEM;
    }

  received = recvfrom(fd, line, BUFFER_PAIRING_MAX_LINE - 1, 0,
                      (struct sockaddr *)&peer, &peer_length);
  if (received < 0)
    {
      ret = errno == EINTR ? 0 : -errno;
      free(line);
      return ret;
    }

  line[received] = '\0';
  request = cJSON_Parse(line);
  free(line);
  if (request == NULL)
    {
      return 0;
    }

  if (buffer_pairing_request_is(request, "buffer.discover"))
    {
      cJSON *response;
      char device_id[BUFFER_ID_SIZE];

      buffer_pairing_snapshot(device_id, sizeof(device_id), NULL, 0);
      response = cJSON_CreateObject();
      if (response == NULL)
        {
          cJSON_Delete(request);
          return -ENOMEM;
        }

      cJSON_AddStringToObject(response, "type", "buffer.vela");
      cJSON_AddNumberToObject(response, "protocol", 1);
      cJSON_AddStringToObject(response, "device_id", device_id);
      cJSON_AddStringToObject(response, "name", "Gemini S1");
      cJSON_AddNumberToObject(response, "pairing_port",
                              CONFIG_BUFFER_VELA_PAIRING_PORT);
      cJSON_AddNumberToObject(response, "data_port",
                              CONFIG_BUFFER_VELA_PHONE_PORT);
      cJSON_AddBoolToObject(response, "code_required", true);
      cJSON_AddStringToObject(response, "transport", "wifi");
      ret = buffer_pairing_send_datagram(fd, &peer, peer_length, response);
      cJSON_Delete(response);
    }
  else
    {
      ret = 0;
    }

  cJSON_Delete(request);
  return ret;
}

static int buffer_pairing_generate_token(char *token, size_t token_size)
{
  static const char alphabet[] = "0123456789abcdef";
  unsigned char random_bytes[32];
  size_t index;
  int ret;

  if (token == NULL || token_size == 0)
    {
      return -EINVAL;
    }

  arc4random_buf(random_bytes, sizeof(random_bytes));
  ret = snprintf(token, token_size, "vela-");
  if (ret < 0 || (size_t)ret >= token_size)
    {
      return -ENAMETOOLONG;
    }

  for (index = 0; index < sizeof(random_bytes); index++)
    {
      if ((size_t)ret + 2 >= token_size)
        {
          return -ENAMETOOLONG;
        }

      token[ret++] = alphabet[random_bytes[index] >> 4];
      token[ret++] = alphabet[random_bytes[index] & 0x0f];
    }

  token[ret] = '\0';
  return ret < 0 || (size_t)ret >= token_size ? -ENAMETOOLONG : 0;
}

static void buffer_pairing_handle_client(int client_fd,
                                         const struct sockaddr_in *peer)
{
  struct timeval timeout = {5, 0};
  char *line;
  char expected_code[BUFFER_PAIRING_CODE_SIZE];
  char device_id[BUFFER_ID_SIZE];
  char phone_device_id[BUFFER_ID_SIZE];
  char host[INET_ADDRSTRLEN];
  char token[128];
  cJSON *request;
  cJSON *response;
  cJSON *code;
  cJSON *phone_id;
  int ret;

  if (buffer_ble_service_busy())
    {
      /* Do not let legacy LAN pairing replace a BLE enrollment in progress. */
      return;
    }

  (void)setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &timeout,
                   sizeof(timeout));
  (void)setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout,
                   sizeof(timeout));

  line = malloc(BUFFER_PAIRING_MAX_LINE);
  if (line == NULL)
    {
      buffer_set_status("配对请求内存不足");
      return;
    }

  ret = buffer_pairing_read_line(client_fd, line, BUFFER_PAIRING_MAX_LINE);
  request = ret < 0 ? NULL : cJSON_Parse(line);
  free(line);
  buffer_pairing_snapshot(device_id, sizeof(device_id), expected_code,
                           sizeof(expected_code));

  if (buffer_pairing_rate_limited())
    {
      response = buffer_pairing_error("pairing_rate_limited",
                                      "too many invalid pairing codes; retry later");
      if (response != NULL)
        {
          (void)buffer_pairing_send_json(client_fd, response);
          cJSON_Delete(response);
        }
      cJSON_Delete(request);
      return;
    }

  if (request == NULL || !buffer_pairing_request_is(request, "buffer.pair"))
    {
      response = buffer_pairing_error("invalid_pairing_request", NULL);
      if (response != NULL)
        {
          (void)buffer_pairing_send_json(client_fd, response);
          cJSON_Delete(response);
        }
      cJSON_Delete(request);
      sleep(1);
      return;
    }

  code = cJSON_GetObjectItemCaseSensitive(request, "pairing_code");
  if (code == NULL || !cJSON_IsString(code) || code->valuestring == NULL ||
      strcmp(code->valuestring, expected_code) != 0)
    {
      response = buffer_pairing_error("invalid_pairing_code",
                                      "enter the code shown on Gemini S1");
      if (response != NULL)
        {
          (void)buffer_pairing_send_json(client_fd, response);
          cJSON_Delete(response);
        }
      cJSON_Delete(request);
      buffer_pairing_record_failure();
      buffer_set_status("配对码错误，仍等待手机配对");
      sleep(1);
      return;
    }

  phone_id = cJSON_GetObjectItemCaseSensitive(request, "phone_device_id");
  if (phone_id == NULL || !cJSON_IsString(phone_id) ||
      !buffer_pairing_valid_device_id(phone_id->valuestring))
    {
      response = buffer_pairing_error("invalid_phone_identity", NULL);
      if (response != NULL)
        {
          (void)buffer_pairing_send_json(client_fd, response);
          cJSON_Delete(response);
        }
      cJSON_Delete(request);
      sleep(1);
      return;
    }

  snprintf(phone_device_id, sizeof(phone_device_id), "%s",
           phone_id->valuestring);

  if (inet_ntop(AF_INET, &peer->sin_addr, host, sizeof(host)) == NULL)
    {
      snprintf(host, sizeof(host), "%s", "0.0.0.0");
    }

  ret = buffer_pairing_generate_token(token, sizeof(token));
  if (ret == 0)
    {
      ret = buffer_store_save_pairing(host, token, phone_device_id);
    }
  if (ret < 0)
    {
      response = buffer_pairing_error("pairing_store_failed", NULL);
      if (response != NULL)
        {
          (void)buffer_pairing_send_json(client_fd, response);
          cJSON_Delete(response);
        }
      cJSON_Delete(request);
      return;
    }

  buffer_ble_stop();
  buffer_pairing_clear_failures();
  response = cJSON_CreateObject();
  if (response != NULL)
    {
      cJSON_AddStringToObject(response, "type", "buffer.pair_ack");
      cJSON_AddNumberToObject(response, "protocol", 1);
      cJSON_AddStringToObject(response, "device_id", device_id);
      cJSON_AddStringToObject(response, "token", token);
      cJSON_AddNumberToObject(response, "data_port",
                              CONFIG_BUFFER_VELA_PHONE_PORT);
      (void)buffer_pairing_send_json(client_fd, response);
      cJSON_Delete(response);
    }

  cJSON_Delete(request);
  buffer_set_status("已配对手机 %s，等待同步", host);
  buffer_request_sync();
}

static int buffer_pairing_open_udp(void)
{
  struct sockaddr_in address;
  int fd;
  int reuse = 1;

  fd = socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0)
    {
      return -errno;
    }

  (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_ANY);
  address.sin_port = htons((uint16_t)CONFIG_BUFFER_VELA_DISCOVERY_PORT);
  if (bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0)
    {
      int ret = -errno;
      close(fd);
      return ret;
    }

  return fd;
}

static int buffer_pairing_open_tcp(void)
{
  struct sockaddr_in address;
  int fd;
  int reuse = 1;

  fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0)
    {
      return -errno;
    }

  (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
  memset(&address, 0, sizeof(address));
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = htonl(INADDR_ANY);
  address.sin_port = htons((uint16_t)CONFIG_BUFFER_VELA_PAIRING_PORT);
  if (bind(fd, (struct sockaddr *)&address, sizeof(address)) < 0)
    {
      int ret = -errno;
      close(fd);
      return ret;
    }

  if (listen(fd, BUFFER_PAIRING_BACKLOG) < 0)
    {
      int ret = -errno;
      close(fd);
      return ret;
    }

  return fd;
}

struct buffer_discovery_worker_s
{
  int fd;
  bool stopping;
  pthread_mutex_t lock;
};

static void *buffer_discovery_thread_main(void *arg)
{
  struct buffer_discovery_worker_s *worker = arg;
  struct pollfd ready = {.fd = worker->fd, .events = POLLIN};

  while (buffer_pairing_running())
    {
      bool stopping;
      int ret;

      pthread_mutex_lock(&worker->lock);
      stopping = worker->stopping;
      pthread_mutex_unlock(&worker->lock);
      if (stopping)
        {
          break;
        }

      ret = poll(&ready, 1, 1000);
      if (ret < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          break;
        }
      if (ret > 0 && (ready.revents & POLLIN) != 0)
        {
          (void)buffer_pairing_handle_discovery(worker->fd);
        }
      if (ret > 0 && (ready.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0)
        {
          break;
        }
    }

  return NULL;
}

void *buffer_pairing_thread_main(void *arg)
{
  struct pollfd ready;
  struct buffer_discovery_worker_s discovery;
  pthread_t discovery_thread;
  int ret;
  char pairing_code[BUFFER_PAIRING_CODE_SIZE];
  int udp_fd;
  int tcp_fd;
  unsigned int ble_retry = 0;

  (void)arg;
  udp_fd = buffer_pairing_open_udp();
  if (udp_fd < 0)
    {
      buffer_set_status("发现服务启动失败：%d", udp_fd);
      return NULL;
    }

  tcp_fd = buffer_pairing_open_tcp();
  if (tcp_fd < 0)
    {
      close(udp_fd);
      buffer_set_status("配对服务启动失败：%d", tcp_fd);
      return NULL;
    }

  discovery.fd = udp_fd;
  discovery.stopping = false;
  ret = pthread_mutex_init(&discovery.lock, NULL);
  if (ret != 0)
    {
      close(tcp_fd);
      close(udp_fd);
      buffer_set_status("发现服务锁初始化失败：%d", ret);
      return NULL;
    }

  ret = pthread_create(&discovery_thread, NULL,
                       buffer_discovery_thread_main, &discovery);
  if (ret != 0)
    {
      pthread_mutex_destroy(&discovery.lock);
      close(tcp_fd);
      close(udp_fd);
      buffer_set_status("发现服务线程启动失败：%d", ret);
      return NULL;
    }

  buffer_pairing_snapshot(NULL, 0, pairing_code, sizeof(pairing_code));
  buffer_set_status("等待配对：输入屏幕上的配对码 %s", pairing_code);

  ret = buffer_ble_start();
  if (ret < 0)
    {
      buffer_set_status("蓝牙发现不可用：%d", ret);
    }

  memset(&ready, 0, sizeof(ready));
  ready.fd = tcp_fd;
  ready.events = POLLIN;
  while (buffer_pairing_running())
    {
      buffer_ble_service_process();
      /* The Bluetooth service may become ready after Buffer at boot. */
      if (++ble_retry >= 5)
        {
          (void)buffer_ble_start();
          ble_retry = 0;
        }
      ret = poll(&ready, 1, 1000);
      if (ret < 0)
        {
          if (errno == EINTR)
            {
              continue;
            }

          break;
        }
      if (ret == 0)
        {
          continue;
        }

      if ((ready.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0)
        {
          break;
        }

      if ((ready.revents & POLLIN) != 0)
        {
          struct sockaddr_in peer;
          socklen_t peer_length = sizeof(peer);
          int client_fd = accept(tcp_fd, (struct sockaddr *)&peer,
                                 &peer_length);
          if (client_fd >= 0)
            {
              if (buffer_ble_service_claim_lan())
                {
                  buffer_pairing_handle_client(client_fd, &peer);
                  buffer_ble_service_release_lan();
                }
              close(client_fd);
            }
        }
    }

  buffer_ble_stop();
  pthread_mutex_lock(&discovery.lock);
  discovery.stopping = true;
  pthread_mutex_unlock(&discovery.lock);
  pthread_join(discovery_thread, NULL);
  pthread_mutex_destroy(&discovery.lock);
  close(tcp_fd);
  close(udp_fd);
  return NULL;
}
