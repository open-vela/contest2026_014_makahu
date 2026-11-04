/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_HOME_KEY_H
#define BUFFER_HOME_KEY_H
#include <stdbool.h>
#include <stdint.h>
enum buffer_home_action_e { BUFFER_HOME_NONE,BUFFER_HOME_SHORT,BUFFER_HOME_START,BUFFER_HOME_STOP };
struct buffer_home_key_s {
  bool initialized,raw,down,suppress,long_pressed;
  uint64_t changed,pressed;
};
static inline enum buffer_home_action_e buffer_home_key_step(struct buffer_home_key_s *s,bool down,uint64_t now)
{
  if(!s->initialized){s->initialized=true;s->raw=s->down=down;s->suppress=down;s->changed=now;return BUFFER_HOME_NONE;}
  if(down!=s->raw){s->raw=down;s->changed=now;}
  if(now<s->changed||now-s->changed<30)return BUFFER_HOME_NONE;
  if(s->suppress){if(!down){s->suppress=false;s->down=false;}return BUFFER_HOME_NONE;}
  if(s->down!=down){
    s->down=down;
    if(down){s->pressed=now;s->long_pressed=false;}
    else return s->long_pressed?BUFFER_HOME_STOP:BUFFER_HOME_SHORT;
  }
  if(down&&!s->long_pressed&&now>=s->pressed&&now-s->pressed>=600){
    s->long_pressed=true;return BUFFER_HOME_START;
  }
  return BUFFER_HOME_NONE;
}
#endif
