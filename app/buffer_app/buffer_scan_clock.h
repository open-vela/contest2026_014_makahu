/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SCAN_CLOCK_H
#define BUFFER_SCAN_CLOCK_H
#include <stdint.h>
#include <time.h>

static inline uint64_t buffer_scan_clock_ms(void)
{
  struct timespec stamp;
  if (clock_gettime(CLOCK_MONOTONIC, &stamp)) return 0;
  return (uint64_t)stamp.tv_sec * 1000 + (uint64_t)stamp.tv_nsec / 1000000;
}

/* Callers may use a supplied/test clock. Add only the measured scan duration
 * to that clock so the full retry interval starts after storage releases its
 * locks. A slow scan must not consume its own backoff and immediately repeat. */
static inline uint64_t buffer_scan_retry_after(uint64_t now, uint64_t began,
                                              uint64_t interval)
{
  uint64_t finished = buffer_scan_clock_ms();
  uint64_t elapsed = began && finished >= began ? finished - began : 0;
  if (elapsed > UINT64_MAX - now) return UINT64_MAX;
  now += elapsed;
  return interval > UINT64_MAX - now ? UINT64_MAX : now + interval;
}
#endif
