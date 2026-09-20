"""Production focus timer advances while the production sync loop is blocked."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
main = (root/'app/buffer_app/buffer_app_main.c').read_text()
wire = (root/'app/buffer_app/buffer_wire.c').read_text()
def section(text, start, end):
    a = text.index(start)
    return text[a:text.index(end, a)]
harness = r'''
#define CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS 1200000
#include "buffer_app.h"
#include <assert.h>
#include <errno.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#define BUFFER_WIRE_RECONNECT_AFTER_FAILURES 3
struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static atomic_llong clock_ms = 100;
static atomic_int reminders;
static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static bool network_entered, network_released;
static int64_t buffer_monotonic_ms(void) { return atomic_load(&clock_ms); }
void buffer_set_status(const char *format, ...) { (void)format; atomic_fetch_add(&reminders, 1); }
int buffer_wire_sync_once(void)
{
  pthread_mutex_lock(&gate);
  network_entered = true;
  pthread_cond_broadcast(&changed);
  while (!network_released) pthread_cond_wait(&changed, &gate);
  pthread_mutex_unlock(&gate);
  return 0;
}
static bool buffer_wire_should_reconnect(int ret) { return ret < 0; }
static int buffer_wire_refresh_phone_host(void) { return -ENETUNREACH; }
int buffer_wifi_reconnect(void) { return -ENETUNREACH; }
void buffer_request_sync(void) {}
''' + section(main, 'static uint32_t buffer_focus_interval(', 'bool buffer_focus_snapshot(') + section(main, 'static int buffer_is_running(', 'static int buffer_start_recording(') + section(main, 'static void *buffer_timer_thread_main(', 'static void buffer_service_stop(void);') + wire[wire.index('void *buffer_wire_thread_main('):] + section(main, 'static void buffer_service_stop(void)\n{', 'static void buffer_record_for_ms(') + r'''
static void expect_state(int64_t started, bool due, int notices)
{
  for (int i = 0; i < 100; i++)
    {
      pthread_mutex_lock(&g_buffer.lock);
      bool matches = g_buffer.focus_started_at == started &&
                     g_buffer.focus_reminder_due == due;
      pthread_mutex_unlock(&g_buffer.lock);
      if (matches && atomic_load(&reminders) == notices) return;
      usleep(10000);
    }
  assert(!"timer failed to advance independently of network sync");
}
int main(void)
{
  for (int round = 0; round < 2; round++)
    {
      g_buffer.initialized = true;
      g_buffer.stopping = false;
      g_buffer.focus_started_at = 0;
      g_buffer.next_care_at = 0;
      strcpy(g_buffer.mode, "focus");
      atomic_store(&clock_ms, 100);
      atomic_store(&reminders, 0);
      network_entered = network_released = false;
      assert(pthread_create(&g_buffer.sync_thread, NULL, buffer_wire_thread_main, NULL) == 0);
      g_buffer.sync_thread_started = true;
      pthread_mutex_lock(&gate);
      while (!network_entered) pthread_cond_wait(&changed, &gate);
      pthread_mutex_unlock(&gate);
      assert(pthread_create(&g_buffer.timer_thread, NULL, buffer_timer_thread_main, NULL) == 0);
      g_buffer.timer_thread_started = true;
      expect_state(100, false, 0);
      atomic_store(&clock_ms, 1200100);
      expect_state(100, true, 1);
      atomic_store(&clock_ms, 1215100);
      expect_state(100, false, 1);
      pthread_mutex_lock(&g_buffer.lock);
      strcpy(g_buffer.mode, "normal");
      pthread_mutex_unlock(&g_buffer.lock);
      expect_state(0, false, 1);
      pthread_mutex_lock(&gate);
      assert(!network_released);
      network_released = true;
      pthread_cond_broadcast(&changed);
      pthread_mutex_unlock(&gate);
      buffer_service_stop();
      assert(!g_buffer.initialized && !g_buffer.timer_thread_started && !g_buffer.sync_thread_started);
    }
  puts("PASS: reminder and expiry while sync blocked, mode exit, stop/join/restart");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-focus-timer-') as temporary:
    work=Path(temporary)
    (work/'nuttx').mkdir()
    (work/'nuttx/config.h').write_text('')
    (work/'test.c').write_text(harness)
    subprocess.run(['cc','-Wall','-Wextra','-Werror','-pthread','-I',str(work),'-I',str(root/'app/buffer_app'),str(work/'test.c'),'-o',str(work/'test')],check=True)
    subprocess.run([str(work/'test')],check=True,timeout=10)
