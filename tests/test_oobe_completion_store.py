"""Exercise the identity-bound OOBE completion cache and durable store code."""
from host_support import PROJECT_ROOT, extract_c, CC
from pathlib import Path
import subprocess
import tempfile

root = PROJECT_ROOT
source = (root / "app/buffer_app/buffer_store.c").read_text()


def function(name):
    return extract_c(source, name)

atomic = function("static int buffer_write_atomic(")
valid_id = function("static bool buffer_store_valid_fabric_id(")
current_pair = function("static int buffer_store_current_fabric_pair(")
read_line = function("static int buffer_store_oobe_read_line(")
load = function("int buffer_store_load_oobe_completed(")
save = function("int buffer_store_save_oobe_completed(")
cached = function("bool buffer_store_oobe_completed_cached(")
invalidate = function("void buffer_store_oobe_completion_invalidate(")

harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#define BUFFER_OOBE_COMPLETION_FILE "oobe.completed"
#define BUFFER_OOBE_COMPLETION_TMP "oobe.completed.tmp"
static pthread_mutex_t g_oobe_completion_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_oobe_completion_io_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_oobe_completion_cache_valid;
static bool g_oobe_completion_cache_value;
static int g_oobe_completion_cache_result = -EAGAIN;
static char g_oobe_completion_cache_local[65];
static char g_oobe_completion_cache_peer[65];
static int pair_result;
static int fail_rename;
static int read_open_count;
static char local_id[65];
static char peer_id[65];
void buffer_store_oobe_completion_invalidate(void);
static int test_rename(const char *from, const char *to)
{
  if (fail_rename) { errno = EIO; return -1; }
  return rename(from, to);
}
static FILE *test_fopen(const char *path, const char *mode)
{
  if (!strcmp(path, BUFFER_OOBE_COMPLETION_FILE) && !strcmp(mode, "r"))
    read_open_count++;
  return fopen(path, mode);
}
#define rename test_rename
#include <stdlib.h>
#define BUFFER_AGENT_JSON_BYTES (64 * 1024)
static bool buffer_source_is_projection_path(const char *path) { (void)path; return false; }
static int buffer_source_projection_save(const char *path,const char *raw,const char *type,const char *action)
{ (void)path;(void)raw;(void)type;(void)action;return -ENOTSUP; }

#define fopen test_fopen
int buffer_fabric_identity_pair(char local[65], char peer[65])
{
  local[0] = peer[0] = '\0';
  if (pair_result) return pair_result;
  memcpy(local, local_id, 65); memcpy(peer, peer_id, 65);
  return 0;
}
''' + atomic + "\n" + valid_id + "\n" + current_pair + "\n" + read_line + "\n" + load + "\n" + save + "\n" + cached + "\n" + invalidate + r'''
static void set_pair(char local_char, char peer_char)
{
  memset(local_id, local_char, 64); local_id[64] = '\0';
  memset(peer_id, peer_char, 64); peer_id[64] = '\0';
  pair_result = 0;
}
static bool read_completed(void)
{
  bool complete = true;
  assert(buffer_store_load_oobe_completed(&complete) == 0);
  return complete;
}
int main(void)
{
  bool complete = true;
  set_pair('a', 'b');
  assert(buffer_store_load_oobe_completed(&complete) == -ENOENT);
  assert(!complete && !buffer_store_oobe_completed_cached());
  int reads = read_open_count;
  assert(buffer_store_load_oobe_completed(&complete) == -ENOENT);
  assert(read_open_count == reads); /* errors stay cached until explicit retry */

  assert(buffer_store_save_oobe_completed(true) == 0);
  assert(buffer_store_oobe_completed_cached());
  reads = read_open_count;
  assert(buffer_store_oobe_completed_cached());
  assert(read_open_count == reads); /* cached UI path performs no file open */

  buffer_store_oobe_completion_invalidate(); /* reboot/background preload */
  assert(read_completed());
  assert(buffer_store_oobe_completed_cached());

  set_pair('c', 'd');
  assert(!buffer_store_oobe_completed_cached()); /* binding mismatch is immediate */
  assert(buffer_store_load_oobe_completed(&complete) == 0 && !complete);

  pair_result = -ENOENT;
  assert(!buffer_store_oobe_completed_cached());
  assert(buffer_store_load_oobe_completed(&complete) == -ENOENT && !complete);
  set_pair('a', 'b');
  assert(read_completed());

  FILE *file = fopen(BUFFER_OOBE_COMPLETION_FILE, "w"); assert(file);
  assert(fputs("version=bad\n", file) >= 0); assert(fclose(file) == 0);
  buffer_store_oobe_completion_invalidate();
  assert(buffer_store_load_oobe_completed(&complete) == -EINVAL && !complete);
  reads = read_open_count;
  assert(buffer_store_load_oobe_completed(&complete) == -EINVAL);
  assert(read_open_count == reads);

  file = fopen(BUFFER_OOBE_COMPLETION_FILE, "w"); assert(file);
  assert(fprintf(file,
      "version=1\nlocal_fabric_id=%s\npeer_fabric_id=%s\ncompleted=1\n",
      local_id, peer_id) > 0);
  assert(fclose(file) == 0);
  buffer_store_oobe_completion_invalidate(); /* explicit retry refreshes */
  assert(read_completed());

  fail_rename = 1;
  assert(buffer_store_save_oobe_completed(false) == -EIO);
  assert(!buffer_store_oobe_completed_cached());
  fail_rename = 0;
  buffer_store_oobe_completion_invalidate();
  assert(read_completed()); /* failed write preserved prior durable completion */
  assert(buffer_store_load_oobe_completed(NULL) == -EINVAL);
  puts("PASS: identity-bound completion persistence, cache-only queries, retry refresh, and write failure");
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-oobe-completion-") as temporary:
    work = Path(temporary)
    test_source = work / "test.c"
    test_source.write_text(harness)
    subprocess.run(
        [*CC, "-std=c11", "-Wall", "-Wextra", "-Werror", str(test_source),
         "-pthread", "-o", str(work / "test")],
        check=True,
    )
    subprocess.run([str(work / "test")], cwd=work, check=True, timeout=5)
