"""Exercise the production first-enrollment route with controlled discovery/storage."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/buffer_app/buffer_wire.c').read_text()
def extract(start, end):
    return source[source.index(start):source.index(end, source.index(start))]

harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#define BUFFER_ID_SIZE 80
static const char *stored_host, *stored_token, *stored_id;
static int load_error, discover_error, save_error, discoveries, saves;
static int buffer_store_load_pairing(char *host, size_t hs, char *token,
                                    size_t ts, char *id, size_t ids)
{
  if (load_error) return load_error;
  snprintf(host, hs, "%s", stored_host);
  snprintf(token, ts, "%s", stored_token);
  snprintf(id, ids, "%s", stored_id);
  return 0;
}
static int buffer_wire_discover_phone(const char *expected, char *host,
                                     size_t hs, char *id, size_t ids)
{
  discoveries++;
  assert(strcmp(expected, "phone-test") == 0);
  if (discover_error) return discover_error;
  snprintf(host, hs, "192.168.1.23");
  snprintf(id, ids, "%s", expected);
  return 0;
}
static int buffer_store_save_pairing(const char *host, const char *token,
                                    const char *id)
{
  saves++;
  assert(strcmp(host, "192.168.1.23") == 0);
  assert(strcmp(token, "secret") == 0);
  assert(strcmp(id, "phone-test") == 0);
  return save_error;
}
''' + extract('static bool buffer_wire_valid_device_id(', 'static void buffer_wire_load_standalone_state(') + extract('static int buffer_wire_load_phone_route(', 'static int buffer_send_all(') + r'''
int main(void)
{
  char host[96], token[128], id[80];
  stored_host="0.0.0.0"; stored_token="secret"; stored_id="phone-test";
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == 0);
  assert(discoveries == 1 && saves == 1);
  assert(strcmp(host,"192.168.1.23") == 0);
  assert(strcmp(token,"secret") == 0 && strcmp(id,"phone-test") == 0);

  stored_host="192.168.1.24";
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == 0);
  assert(discoveries == 1 && saves == 1);
  assert(strcmp(host,stored_host) == 0);

  stored_host="0.0.0.0"; discover_error=-ETIMEDOUT;
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == -ETIMEDOUT);
  assert(discoveries == 2 && saves == 1);
  assert(strcmp(host,"0.0.0.0") == 0);

  discover_error=0; save_error=-EIO;
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == -EIO);
  assert(discoveries == 3 && saves == 2);
  assert(strcmp(host,"0.0.0.0") == 0);

  save_error=0; stored_id="";
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == -EACCES);
  stored_id="phone-test"; stored_token="";
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == -EACCES);
  stored_token="secret"; load_error=-ENOENT;
  assert(buffer_wire_load_phone_route(host,sizeof(host),token,sizeof(token),id,sizeof(id)) == -EACCES);
  assert(discoveries == 3 && saves == 2);
  puts("PASS: first discovery, cached route, timeout, save failure, identity and token guards");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-enrollment-route-') as temporary:
    work = Path(temporary)
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', str(work / 'test.c'),
                    '-o', str(work / 'test')], check=True)
    subprocess.run([str(work / 'test')], check=True, timeout=5)
