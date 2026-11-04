/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_ENTROPY_H
#define BUFFER_ENTROPY_H

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>

#ifdef __NuttX__
#  include <nuttx/config.h>
#endif

/* Security-sensitive randomness must not silently inherit NuttX's software
 * /dev/urandom fallback. On NuttX, use the hardware RNG's /dev/random only
 * when the architecture advertises a real RNG. Host fixtures use the OS CSPRNG
 * interface, which is /dev/urandom on Linux. */
static inline int buffer_entropy_read(unsigned char *out, size_t length)
{
  const char *device;
  size_t offset = 0;
  int fd;
  int error = 0;

  if (length == 0)
    {
      return 0;
    }

  if (out == NULL)
    {
      return -EINVAL;
    }

#ifdef __NuttX__
#  ifdef CONFIG_ARCH_HAVE_RNG
  device = "/dev/random";
#  else
  memset(out, 0, length);
  return -ENOTSUP;
#  endif
#else
  device = "/dev/urandom";
#endif

  fd = open(device, O_RDONLY);
  if (fd < 0)
    {
      error = -errno;
      goto failed;
    }

  while (offset < length)
    {
      ssize_t count = read(fd, out + offset, length - offset);
      if (count < 0 && errno == EINTR)
        {
          continue;
        }

      if (count < 0)
        {
          error = -errno;
          break;
        }

      if (count == 0)
        {
          error = -EIO;
          break;
        }

      offset += (size_t)count;
    }

  (void)close(fd);
  if (error == 0)
    {
      return 0;
    }

failed:
  memset(out, 0, length);
  return error;
}

#endif /* BUFFER_ENTROPY_H */
