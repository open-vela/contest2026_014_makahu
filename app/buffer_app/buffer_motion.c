/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_motion.h"
#include <math.h>
void buffer_spring_target(struct buffer_spring *s, float target)
{
  s->target = target;
  s->active = s->value != target || s->velocity != 0;
}
bool buffer_spring_step(struct buffer_spring *s, enum buffer_motion_role role,
                        unsigned elapsed_ms, bool reduce)
{
  /* Expressive scheme, unit mass, damping ratio and stiffness. These are
   * native spring parameters, NOT the web fallback Bezier durations. */
  static const float stiffness[] = {800, 380, 200, 3800, 1600, 800};
  static const float damping[] = {.6f, .8f, .8f, 1, 1, 1};
  if (!s->active) return false;
  if (reduce || elapsed_ms > 120) goto settle; /* resume, don't replay backlog */
  if ((unsigned)role > BUFFER_EL) role = BUFFER_ED;
  while (elapsed_ms)
    {
      unsigned slice = elapsed_ms > 4 ? 4 : elapsed_ms;
      float dt = slice * .001f;
      float previous = s->value;
      float k = stiffness[role], c = 2 * damping[role] * sqrtf(k);
      s->velocity += (k * (s->target - s->value) - c * s->velocity) * dt;
      s->value += s->velocity * dt;
      if (role >= BUFFER_EF && ((previous <= s->target && s->value > s->target) ||
                               (previous >= s->target && s->value < s->target)))
        goto settle;
      elapsed_ms -= slice;
    }
  if (fabsf(s->target - s->value) < .08f && fabsf(s->velocity) < .3f)
    {
settle:
      s->value = s->target; s->velocity = 0; s->active = false;
    }
  return s->active;
}
