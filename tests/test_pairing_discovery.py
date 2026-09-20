"""Compile production discovery functions and exercise real loopback UDP."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "app/buffer_app/buffer_pairing.c").read_text()
cjson = root.parent / "apps/netutils/cjson/cJSON"


def extract(start, end):
    return source[source.index(start):source.index(end, source.index(start))]


with tempfile.TemporaryDirectory(prefix="buffer-discovery-test-") as temporary:
    work = Path(temporary)
    harness = r'''
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "cJSON.h"
#define BUFFER_PAIRING_MAX_LINE 4096
#define BUFFER_ID_SIZE 80
#define CONFIG_BUFFER_VELA_PAIRING_PORT 18201
#define CONFIG_BUFFER_VELA_PHONE_PORT 18202
static void buffer_pairing_snapshot(char *id, size_t size, char *code, size_t n)
{
  (void)code; (void)n;
  snprintf(id, size, "vela-test-device");
}
''' + extract("static bool buffer_pairing_request_is(", "static cJSON *buffer_pairing_error(") + extract(
        "static int buffer_pairing_send_datagram(", "static int buffer_pairing_generate_token("
    ) + r'''
static void check(int server, int client, struct sockaddr_in *address,
                  const char *request, bool expect_reply)
{
  assert(sendto(client, request, strlen(request), 0,
                (struct sockaddr *)address, sizeof(*address)) == (ssize_t)strlen(request));
  struct pollfd incoming = {.fd = server, .events = POLLIN};
  assert(poll(&incoming, 1, 1000) == 1);
  assert(buffer_pairing_handle_discovery(server) == 0);
  struct pollfd reply = {.fd = client, .events = POLLIN};
  assert(poll(&reply, 1, 50) == (expect_reply ? 1 : 0));
  if (!expect_reply) return;
  char data[4096];
  ssize_t count = recv(client, data, sizeof(data) - 1, 0);
  assert(count > 0);
  data[count] = 0;
  cJSON *response = cJSON_Parse(data);
  assert(buffer_pairing_request_is(response, "buffer.vela"));
  assert(strcmp(cJSON_GetObjectItemCaseSensitive(response, "device_id")->valuestring,
                "vela-test-device") == 0);
  assert(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(response, "code_required")));
  assert(cJSON_GetObjectItemCaseSensitive(response, "pairing_port")->valueint ==
         CONFIG_BUFFER_VELA_PAIRING_PORT);
  cJSON_Delete(response);
}
int main(void)
{
  int server = socket(AF_INET, SOCK_DGRAM, 0);
  int client = socket(AF_INET, SOCK_DGRAM, 0);
  assert(server >= 0 && client >= 0);
  struct sockaddr_in address = {.sin_family = AF_INET,
                               .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
  assert(bind(server, (struct sockaddr *)&address, sizeof(address)) == 0);
  socklen_t length = sizeof(address);
  assert(getsockname(server, (struct sockaddr *)&address, &length) == 0);
  check(server, client, &address, "{\"type\":\"buffer.phone.discover\",\"protocol\":1}", false);
  check(server, client, &address, "{\"type\":\"buffer.discover\",\"protocol\":1}", true);
  check(server, client, &address, "{\"type\":\"buffer.discover\",\"protocol\":2}", false);
  check(server, client, &address, "{\"type\":\"unknown\",\"protocol\":1}", false);
  check(server, client, &address, "not json", false);
  close(client);
  close(server);
  puts("PASS: Vela discovery, phone discovery silence, unsupported/invalid requests");
}
'''
    (work / "test.c").write_text(harness)
    subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-I", str(cjson),
                    str(work / "test.c"), str(cjson / "cJSON.c"), "-lm",
                    "-o", str(work / "test")], check=True)
    subprocess.run([str(work / "test")], check=True, timeout=10)
