"""Production request reader rejects slow, incomplete and oversized input."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'app/buffer_app/buffer_pairing.c').read_text()
start = source.index('static int buffer_pairing_read_line(')
end = source.index('static bool buffer_pairing_request_is(', start)
harness = r'''
#include <assert.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#define BUFFER_PAIRING_READ_TIMEOUT_MS 5000
''' + source[start:end] + r'''
static void *trickle(void *arg)
{
  int fd = *(int *)arg;
  struct timespec pause = {.tv_nsec = 100000000};
  for (int i = 0; i < 60; i++)
    {
      assert(send(fd, "x", 1, 0) == 1);
      nanosleep(&pause, NULL);
    }
  return NULL;
}
int main(void)
{
  int sockets[2];
  char line[4096];
  assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
  assert(send(sockets[0], "test\r\n", 6, 0) == 6);
  assert(buffer_pairing_read_line(sockets[1], line, sizeof(line)) == 0);
  assert(strcmp(line, "test") == 0);
  assert(send(sockets[0], "abcd", 4, 0) == 4);
  assert(buffer_pairing_read_line(sockets[1], line, 4) == -EFBIG);
  assert(buffer_pairing_read_line(sockets[1], NULL, 4) == -EINVAL);
  close(sockets[0]);
  assert(buffer_pairing_read_line(sockets[1], line, sizeof(line)) == -ECONNRESET);
  close(sockets[1]);
  assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
  pthread_t sender;
  assert(pthread_create(&sender, NULL, trickle, &sockets[0]) == 0);
  struct timespec start, end;
  clock_gettime(CLOCK_MONOTONIC, &start);
  assert(buffer_pairing_read_line(sockets[1], line, sizeof(line)) == -ETIMEDOUT);
  clock_gettime(CLOCK_MONOTONIC, &end);
  double elapsed = end.tv_sec - start.tv_sec + (end.tv_nsec - start.tv_nsec) / 1e9;
  assert(elapsed >= 4.9 && elapsed < 5.7);
  pthread_join(sender, NULL);
  close(sockets[0]); close(sockets[1]);
  printf("PASS: CRLF, oversize, invalid arguments, EOF, trickle timeout %.3fs\n", elapsed);
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-pairing-read-') as temporary:
    work = Path(temporary)
    (work/'test.c').write_text(harness)
    subprocess.run(['cc','-Wall','-Wextra','-Werror','-pthread',str(work/'test.c'),'-o',str(work/'test')],check=True)
    subprocess.run([str(work/'test')],check=True,timeout=10)
