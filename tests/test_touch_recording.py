"""Exercise production touch request/worker code without LVGL or an audio device."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
s = (root / 'app/buffer_app/buffer_app_main.c').read_text()
a = s.index('bool buffer_touch_record_active(')
b = s.index('static void *buffer_timer_thread_main', a)
harness = r'''
#include "buffer_app.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
struct buffer_runtime_s g_buffer = {.lock=PTHREAD_MUTEX_INITIALIZER};
static int starts, stops;
static int buffer_is_running(void) { return !g_buffer.stopping; }
static int buffer_start_recording(void) {
  pthread_mutex_lock(&g_buffer.lock);
  starts++; g_buffer.recording=true; g_buffer.record_stop=false;
  pthread_mutex_unlock(&g_buffer.lock); return 0;
}
static void buffer_stop_recording(void) {
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.recording) stops++;
  g_buffer.recording=false;
  pthread_mutex_unlock(&g_buffer.lock);
}
''' + s[a:b] + r'''
static void wait_idle(void) {
  for(int i=0;i<200;i++) {
    if (!buffer_touch_record_active()) return;
    usleep(1000);
  }
  assert(!"worker did not finish");
}
int main(void) {
  assert(buffer_touch_record_start()==-EBUSY);
  g_buffer.initialized=true;
  assert(buffer_touch_record_start()==0);
  assert(buffer_touch_record_start()==-EBUSY);
  buffer_touch_record_stop(); /* Release before worker wakes. */
  pthread_t thread;
  pthread_create(&thread,NULL,buffer_input_thread_main,NULL);
  wait_idle(); assert(starts==0);
  usleep(30000);
  for(int n=1;n<=3;n++) {
    assert(buffer_touch_record_start()==0);
    usleep(40000);
    assert(buffer_touch_record_active());
    buffer_touch_record_stop();
    wait_idle(); usleep(30000);
    assert(starts==n && stops==n);
  }
  pthread_mutex_lock(&g_buffer.lock); g_buffer.stopping=true;
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_join(thread,NULL);
  assert(buffer_touch_record_start()==-EBUSY);
  puts("PASS: quick tap, duplicate press, repeated press/release, shutdown guard");
}
'''
with tempfile.TemporaryDirectory() as d:
 p=Path(d); (p/'nuttx').mkdir(); (p/'nuttx/config.h').write_text('')
 (p/'test.c').write_text(harness)
 subprocess.run(['cc','-pthread','-I'+d,'-I'+str(root/'app/buffer_app'),str(p/'test.c'),'-o',str(p/'test')],check=True)
 subprocess.run([str(p/'test')],check=True,timeout=5)
