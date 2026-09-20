"""Exercise production connect helper: loopback and injected socket failures."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'app/buffer_app/buffer_wire.c').read_text()
start = source.index('static int buffer_connect_address(')
end = source.index('static char *buffer_base64(', start)
functions = source[start:end]
headers = r'''
#define _POSIX_C_SOURCE 200809L
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#define BUFFER_WIRE_CONNECT_TIMEOUT_MS 5000
'''
real = headers + functions + r'''
int main(void)
{
  int server = socket(AF_INET, SOCK_STREAM, 0);
  assert(server >= 0);
  struct sockaddr_in address = {.sin_family = AF_INET,
                               .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
  assert(bind(server, (struct sockaddr *)&address, sizeof(address)) == 0);
  assert(listen(server, 1) == 0);
  socklen_t size = sizeof(address);
  assert(getsockname(server, (struct sockaddr *)&address, &size) == 0);
  int fd = buffer_connect("127.0.0.1", ntohs(address.sin_port));
  assert(fd >= 0);
  assert((fcntl(fd, F_GETFL) & O_NONBLOCK) == 0);
  struct timeval timeout;
  size = sizeof(timeout);
  assert(getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, &size) == 0);
  assert(timeout.tv_sec == 30);
  int peer = accept(server, NULL, NULL);
  assert(peer >= 0);
  assert(send(fd, "x", 1, 0) == 1);
  char value;
  assert(recv(peer, &value, 1, 0) == 1 && value == 'x');
  close(peer); close(fd);
  /* Bound, non-listening local TCP socket reliably refuses connections. */
  close(server);
  server = socket(AF_INET, SOCK_STREAM, 0);
  address.sin_port = 0;
  assert(bind(server, (struct sockaddr *)&address, sizeof(address)) == 0);
  size = sizeof(address);
  assert(getsockname(server, (struct sockaddr *)&address, &size) == 0);
  assert(buffer_connect("127.0.0.1", ntohs(address.sin_port)) == -ECONNREFUSED);
  close(server);
  puts("PASS: real TCP success, restored flags, I/O timeout, refusal");
}
'''
helper = functions[:functions.index('static int buffer_connect(const')]
faults = headers + r'''
static int scenario, ticks, polls, restored;
static int fake_fcntl(int fd, int command, int flags)
{
  (void)fd;
  if (command == F_GETFL) return 0;
  if (flags == 0) restored++;
  return 0;
}
static int fake_clock_gettime(clockid_t id, struct timespec *now)
{
  (void)id;
  now->tv_sec = ticks++;
  now->tv_nsec = 0;
  return 0;
}
static int fake_connect(int fd, const struct sockaddr *address, socklen_t size)
{
  (void)fd; (void)address; (void)size;
  if (scenario == 4) return 0;
  errno = EINPROGRESS;
  return -1;
}
static int fake_poll(struct pollfd *ready, nfds_t count, int timeout)
{
  (void)count;
  polls++;
  assert(timeout == 5000 - polls * 1000);
  if (scenario == 0) { errno = EINTR; return -1; }
  if (scenario == 1) return 0;
  ready->revents = POLLOUT;
  return 1;
}
static int fake_getsockopt(int fd, int level, int option, void *value, socklen_t *size)
{
  (void)fd; (void)level; (void)option; (void)size;
  if (scenario == 5) { errno = EIO; return -1; }
  *(int *)value = scenario == 2 ? ECONNREFUSED : 0;
  return 0;
}
#define fcntl fake_fcntl
#define clock_gettime fake_clock_gettime
#define connect fake_connect
#define poll fake_poll
#define getsockopt fake_getsockopt
''' + helper + r'''
int main(void)
{
  int expected[] = {-ETIMEDOUT, -ETIMEDOUT, -ECONNREFUSED, 0, 0, -EIO};
  for (scenario = 0; scenario < 6; scenario++)
    {
      ticks = polls = restored = 0;
      assert(buffer_connect_address(42, NULL, 0) == expected[scenario]);
      assert(restored == (expected[scenario] == 0 ? 1 : 0));
      if (scenario == 0) assert(polls == 4 && ticks == 6);
    }
  puts("PASS: fixed deadline across EINTR, poll timeout, async refusal/success, immediate success, SO_ERROR failure");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-connect-') as temporary:
    work = Path(temporary)
    for name, code in [('real', real), ('faults', faults)]:
        (work / f'{name}.c').write_text(code)
        subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', str(work / f'{name}.c'),
                        '-o', str(work / name)], check=True)
        subprocess.run([str(work / name)], check=True, timeout=10)
