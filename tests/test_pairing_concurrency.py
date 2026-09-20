"""Production pairing service must answer UDP while its TCP handler is busy."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root / 'app/buffer_app/buffer_pairing.c').read_text()
cjson = root.parent / 'apps/netutils/cjson/cJSON'
def extract(start, end):
    return source[source.index(start):source.index(end, source.index(start))]
harness = r'''
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "cJSON.h"
#define BUFFER_PAIRING_MAX_LINE 4096
#define BUFFER_ID_SIZE 80
#define BUFFER_PAIRING_CODE_SIZE 16
#define CONFIG_BUFFER_VELA_PAIRING_PORT 48887
#define CONFIG_BUFFER_VELA_PHONE_PORT 48888
static atomic_bool running = true;
static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static bool handler_entered, release_handler;
static unsigned short tcp_port, udp_port;
static bool buffer_pairing_running(void) { return atomic_load(&running); }
static bool buffer_ble_service_claim_lan(void) { return true; }
static void buffer_ble_service_release_lan(void) {}
static void buffer_ble_service_process(void) {}
static int buffer_ble_start(void) { return 0; }
static void buffer_ble_stop(void) {}
static void buffer_set_status(const char *format, ...) { (void)format; }
static void buffer_pairing_snapshot(char *id, size_t n, char *code, size_t m)
{
  if (id) snprintf(id, n, "vela-test");
  if (code) snprintf(code, m, "123456");
}
static int open_local(int type, unsigned short *port)
{
  int fd = socket(AF_INET, type, 0);
  assert(fd >= 0);
  struct sockaddr_in address = {.sin_family = AF_INET,
                               .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
  assert(bind(fd, (struct sockaddr *)&address, sizeof(address)) == 0);
  if (type == SOCK_STREAM) assert(listen(fd, 1) == 0);
  socklen_t n = sizeof(address);
  assert(getsockname(fd, (struct sockaddr *)&address, &n) == 0);
  pthread_mutex_lock(&gate);
  *port = address.sin_port;
  pthread_cond_broadcast(&changed);
  pthread_mutex_unlock(&gate);
  return fd;
}
static int buffer_pairing_open_udp(void) { return open_local(SOCK_DGRAM, &udp_port); }
static int buffer_pairing_open_tcp(void) { return open_local(SOCK_STREAM, &tcp_port); }
/* Deterministically hold the TCP handler busy, like an incomplete request. */
static void buffer_pairing_handle_client(int fd, const struct sockaddr_in *peer)
{
  (void)fd; (void)peer;
  pthread_mutex_lock(&gate);
  handler_entered = true;
  pthread_cond_broadcast(&changed);
  while (!release_handler) pthread_cond_wait(&changed, &gate);
  pthread_mutex_unlock(&gate);
}
''' + extract('static bool buffer_pairing_request_is(', 'static cJSON *buffer_pairing_error(') + extract('static int buffer_pairing_send_datagram(', 'static int buffer_pairing_generate_token(') + source[source.index('struct buffer_discovery_worker_s'):]+r'''
int main(void)
{
  /* Repeat startup/shutdown to check that the discovery worker is joined. */
  for (int round = 0; round < 3; round++)
    {
      atomic_store(&running, true);
      tcp_port = udp_port = 0;
      handler_entered = release_handler = false;
      pthread_t service;
      assert(pthread_create(&service, NULL, buffer_pairing_thread_main, NULL) == 0);
      pthread_mutex_lock(&gate);
      while (!tcp_port) pthread_cond_wait(&changed, &gate);
      struct sockaddr_in target = {.sin_family = AF_INET, .sin_port = tcp_port,
                                  .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
      pthread_mutex_unlock(&gate);
      int tcp = socket(AF_INET, SOCK_STREAM, 0);
      assert(tcp >= 0);
      assert(connect(tcp, (struct sockaddr *)&target, sizeof(target)) == 0);
      pthread_mutex_lock(&gate);
      while (!handler_entered) pthread_cond_wait(&changed, &gate);
      target.sin_port = udp_port;
      pthread_mutex_unlock(&gate);
      int udp = socket(AF_INET, SOCK_DGRAM, 0);
      assert(udp >= 0);
      const char *request = "{\"type\":\"buffer.discover\",\"protocol\":1}";
      assert(sendto(udp, request, strlen(request), 0,
                    (struct sockaddr *)&target, sizeof(target)) > 0);
      struct pollfd ready = {.fd = udp, .events = POLLIN};
      assert(poll(&ready, 1, 1000) == 1);
      char reply[1024];
      ssize_t n = recv(udp, reply, sizeof(reply)-1, 0);
      assert(n > 0);
      reply[n] = 0;
      cJSON *response = cJSON_Parse(reply);
      assert(buffer_pairing_request_is(response, "buffer.vela"));
      cJSON_Delete(response);
      pthread_mutex_lock(&gate);
      release_handler = true;
      atomic_store(&running, false);
      pthread_cond_broadcast(&changed);
      pthread_mutex_unlock(&gate);
      assert(pthread_join(service, NULL) == 0);
      close(udp); close(tcp);
    }
  puts("PASS: discovery during blocked TCP pairing; repeated startup/shutdown");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-pairing-concurrency-') as temporary:
    work = Path(temporary)
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-pthread', '-I', str(cjson),
                    str(work/'test.c'), str(cjson/'cJSON.c'), '-lm', '-o', str(work/'test')], check=True)
    subprocess.run([str(work/'test')], check=True, timeout=12)
