"""Exercise the OOBE deferral persistence functions from the device store."""
from host_support import PROJECT_ROOT, extract_c, CC
from pathlib import Path
import subprocess
import tempfile

root = PROJECT_ROOT
source = (root / "app/buffer_app/buffer_store.c").read_text()


def function(name):
    return extract_c(source, name)

atomic = function("static int buffer_write_atomic(")
load = function("int buffer_store_load_oobe_deferred(")
save = function("int buffer_store_save_oobe_deferred(")

harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#define BUFFER_OOBE_STATE_FILE "oobe.conf"
#define BUFFER_OOBE_STATE_TMP "oobe.conf.tmp"
static int fail_rename;
static int test_rename(const char *from, const char *to)
{
  if (fail_rename) { errno = EIO; return -1; }
  return rename(from, to);
}
#define rename test_rename
#include <stdlib.h>
#define BUFFER_AGENT_JSON_BYTES (64 * 1024)
static bool buffer_source_is_projection_path(const char *path) { (void)path; return false; }
static int buffer_source_projection_save(const char *path,const char *raw,const char *type,const char *action)
{ (void)path;(void)raw;(void)type;(void)action;return -ENOTSUP; }

''' + atomic + "\n" + load + "\n" + save + r'''
static bool read_state(void) {
  bool deferred = false;
  assert(buffer_store_load_oobe_deferred(&deferred) == 0);
  return deferred;
}
int main(void) {
  bool deferred = true;
  FILE *file;
  char text[32] = {0};
  assert(buffer_store_load_oobe_deferred(&deferred) == -ENOENT);
  assert(!deferred);
  assert(buffer_store_save_oobe_deferred(true) == 0);
  assert(read_state());
  fail_rename = 1;
  assert(buffer_store_save_oobe_deferred(false) == -EIO);
  fail_rename = 0;
  assert(read_state());
  assert(buffer_store_save_oobe_deferred(false) == 0);
  assert(!read_state());
  file = fopen(BUFFER_OOBE_STATE_FILE, "w"); assert(file);
  assert(fputs("deferred=yes\n", file) >= 0); assert(fclose(file) == 0);
  deferred = true;
  assert(buffer_store_load_oobe_deferred(&deferred) == -EINVAL);
  assert(!deferred);
  file = fopen(BUFFER_OOBE_STATE_FILE, "r"); assert(file);
  assert(fread(text, 1, sizeof(text) - 1, file) == 13);
  assert(fclose(file) == 0);
  assert(strcmp(text, "deferred=yes\n") == 0);
  assert(buffer_store_load_oobe_deferred(NULL) == -EINVAL);
  puts("PASS: OOBE later state persists, rejects corruption and preserves prior state on atomic-write failure");
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-oobe-store-") as temporary:
    work = Path(temporary)
    test_source = work / "test.c"
    test_source.write_text(harness)
    subprocess.run(
        [*CC, "-Wall", "-Wextra", "-Werror", str(test_source), "-o", str(work / "test")],
        check=True,
    )
    subprocess.run([str(work / "test")], cwd=work, check=True, timeout=5)
