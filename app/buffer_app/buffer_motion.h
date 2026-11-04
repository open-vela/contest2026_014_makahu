/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_MOTION_H
#define BUFFER_MOTION_H
#include <stdbool.h>
/* Unit mass springs: spatial may overshoot, effects are critically damped. */
enum buffer_motion_role { BUFFER_SF, BUFFER_SD, BUFFER_SL,
                          BUFFER_EF, BUFFER_ED, BUFFER_EL };
struct buffer_spring { float value, velocity, target; bool active; };
void buffer_spring_target(struct buffer_spring *s, float target);
bool buffer_spring_step(struct buffer_spring *s, enum buffer_motion_role role,
                        unsigned elapsed_ms, bool reduce);
#endif
