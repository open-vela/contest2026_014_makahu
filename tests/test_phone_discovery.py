"""Exercise production phone discovery over UDP with a loopback target."""
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

root = Path(__file__).resolve().parents[1]
source = (root / 'app/buffer_app/buffer_wire.c').read_text()
cjson = root.parent / 'apps/netutils/cjson/cJSON'
def extract(start, end):
    return source[source.index(start):source.index(end, source.index(start))]

harness = r'''
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include "cJSON.h"
#define BUFFER_ID_SIZE 80
#define BUFFER_WIRE_DISCOVERY_LINE 1024
#define BUFFER_WIRE_DISCOVERY_TIMEOUT_MS 250
#define CONFIG_BUFFER_VELA_DISCOVERY_PORT discovery_port
static int discovery_port;
/* Only redirect the broadcast destination; parsing, poll and recv are real. */
static ssize_t loopback_sendto(int fd, const void *data, size_t size, int flags,
                               const struct sockaddr *target, socklen_t length)
{
  struct sockaddr_in local = *(const struct sockaddr_in *)target;
  local.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  return sendto(fd, data, size, flags, (struct sockaddr *)&local, length);
}
#define sendto loopback_sendto
''' + extract('static bool buffer_wire_valid_device_id(', 'static void buffer_wire_load_standalone_state(') + extract('static int buffer_wire_discover_phone(', 'static int buffer_wire_refresh_phone_host(') + r'''
int main(int argc, char **argv)
{
  assert(argc == 3);
  discovery_port = atoi(argv[1]);
  char host[96] = "unchanged", id[80] = "unchanged";
  struct timespec start, end;
  assert(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
  int result = buffer_wire_discover_phone("phone-test", host, sizeof(host), id, sizeof(id));
  assert(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
  double elapsed = end.tv_sec - start.tv_sec + (end.tv_nsec - start.tv_nsec) / 1e9;
  if (strcmp(argv[2], "success") == 0)
    {
      assert(result == 0);
      assert(strcmp(id, "phone-test") == 0);
      assert(strcmp(host, "127.0.0.1") == 0);
    }
  else
    {
      assert(result == -ETIMEDOUT);
      assert(elapsed >= 0.20 && elapsed < 0.8);
      assert(strcmp(host, "unchanged") == 0 && strcmp(id, "unchanged") == 0);
    }
  printf("PASS: %s (%.3fs)\n", argv[2], elapsed);
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-phone-discovery-') as temporary:
    work = Path(temporary)
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-I', str(cjson),
                    str(work / 'test.c'), str(cjson / 'cJSON.c'), '-lm',
                    '-o', str(work / 'test')], check=True)
    for scenario in ['silence', 'flood', 'success']:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
            server.bind(('127.0.0.1', 0))
            server.settimeout(2)
            errors = []
            def respond():
                try:
                    _, peer = server.recvfrom(4096)
                    if scenario == 'silence':
                        return
                    wrong = b'{"type":"buffer.phone","protocol":1,"device_id":"other-phone"}'
                    if scenario == 'success':
                        server.sendto(wrong, peer)
                        server.sendto(b'{"type":"buffer.phone","protocol":1,"device_id":"phone-test"}', peer)
                        return
                    until = time.monotonic() + 1.0
                    while time.monotonic() < until:
                        server.sendto(wrong, peer)
                        server.sendto(b'invalid json', peer)
                        time.sleep(0.005)
                except Exception as error:
                    errors.append(error)
            thread = threading.Thread(target=respond)
            thread.start()
            try:
                subprocess.run([str(work / 'test'), str(server.getsockname()[1]), scenario],
                               check=True, timeout=3)
            finally:
                thread.join()
            if errors:
                raise errors[0]
