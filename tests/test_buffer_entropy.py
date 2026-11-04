"""Exercise the production entropy helper's NuttX and host source selection."""
from host_support import PROJECT_ROOT, CC
from pathlib import Path
import subprocess
import tempfile


ROOT = PROJECT_ROOT
APP = ROOT / "app/buffer_app"


MOCK_SOURCE = r'''
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

static const char *opened_path;
static int opens, reads, closes;
static size_t offset;
static int test_open(const char *path, int flags)
{ assert(flags == O_RDONLY); opened_path = path; opens++; return 17; }
static ssize_t test_read(int fd, void *buffer, size_t length)
{
  assert(fd == 17);
  if (reads++ == 0) { errno = EINTR; return -1; }
  size_t count = length > 7 ? 7 : length;
  for (size_t i = 0; i < count; i++) ((unsigned char *)buffer)[i] = (unsigned char)(offset + i + 1);
  offset += count;
  return (ssize_t)count;
}
static int test_close(int fd) { assert(fd == 17); closes++; return 0; }
#define open test_open
#define read test_read
#define close test_close
#include "buffer_entropy.h"
#undef open
#undef read
#undef close

int main(void)
{
  unsigned char bytes[32];
  memset(bytes, 0xa5, sizeof(bytes));
#ifdef __NuttX__
#  ifdef CONFIG_ARCH_HAVE_RNG
  assert(buffer_entropy_read(bytes, sizeof(bytes)) == 0);
  assert(opens == 1 && closes == 1 && reads == 6);
  assert(strcmp(opened_path, "/dev/random") == 0);
  for (size_t i = 0; i < sizeof(bytes); i++) assert(bytes[i] == (unsigned char)(i + 1));
#  else
#    ifndef CONFIG_DEV_URANDOM_CONGRUENTIAL
#      error weak NuttX test must model the configured software fallback
#    endif
  assert(buffer_entropy_read(bytes, sizeof(bytes)) == -ENOTSUP);
  assert(opens == 0 && reads == 0 && closes == 0);
  for (size_t i = 0; i < sizeof(bytes); i++) assert(bytes[i] == 0);
#  endif
#else
  assert(buffer_entropy_read(bytes, sizeof(bytes)) == 0);
  assert(opens == 1 && closes == 1 && reads == 6);
  assert(strcmp(opened_path, "/dev/urandom") == 0);
  for (size_t i = 0; i < sizeof(bytes); i++) assert(bytes[i] == (unsigned char)(i + 1));
#endif
  return 0;
}
'''


HOST_SOURCE = r'''
#include <assert.h>
#include <stddef.h>
#include <string.h>
#include "buffer_entropy.h"
int main(void)
{
  unsigned char bytes[64] = {0};
  assert(buffer_entropy_read(bytes, sizeof(bytes)) == 0);
  unsigned char nonzero = 0;
  for (size_t i = 0; i < sizeof(bytes); i++) nonzero |= bytes[i];
  assert(nonzero != 0);
  return 0;
}
'''


def compile_and_run(work: Path, name: str, source: str, *, nuttX: bool = False,
                    config: str = "") -> None:
    include = work / f"include-{name}"
    (include / "nuttx").mkdir(parents=True)
    (include / "nuttx/config.h").write_text(config)
    cfile = work / f"{name}.c"
    binary = work / name
    cfile.write_text(source)
    command = [*CC, "-std=c11", "-D_DEFAULT_SOURCE", "-Wall", "-Wextra", "-Werror",
               "-I" + str(include), "-I" + str(APP)]
    if nuttX:
        command.append("-D__NuttX__")
    command.extend([str(cfile), "-o", str(binary)])
    subprocess.run(command, check=True)
    subprocess.run([str(binary)], check=True, timeout=5)


def test_entropy_source_selection_and_fail_closed_behavior():
    with tempfile.TemporaryDirectory(prefix="buffer-entropy-") as temporary:
        work = Path(temporary)
        compile_and_run(work, "host_mock", MOCK_SOURCE)
        compile_and_run(work, "nuttx_rng", MOCK_SOURCE, nuttX=True,
                        config="#define CONFIG_ARCH_HAVE_RNG 1\n"
                               "#define CONFIG_DEV_URANDOM_CONGRUENTIAL 1\n")
        compile_and_run(work, "nuttx_weak", MOCK_SOURCE, nuttX=True,
                        config="#define CONFIG_DEV_URANDOM_CONGRUENTIAL 1\n")
        compile_and_run(work, "host_real", HOST_SOURCE)
