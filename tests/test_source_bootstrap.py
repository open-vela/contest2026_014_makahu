"""Agent admission must not repeat journal recovery while history owns its lock."""
import subprocess
from host_support import PROJECT_ROOT, CC, extract_c


def test_bootstrap_is_idempotent_but_source_health_checks_remain_live(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_source_events.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <pthread.h>
static pthread_mutex_t journal_lock=PTHREAD_MUTEX_INITIALIZER;
static bool bootstrap_complete,migration_pending,full_readiness;
static unsigned checks;
static int status=-EIO;
static int ensure_locked(void){checks++;return status;}
'''
    harness+=extract_c(source,'int buffer_source_events_init(')+'\n'
    harness+=extract_c(source,'int buffer_source_events_ready(')+r'''
int main(void){
 assert(buffer_source_events_init()==-EIO&&!bootstrap_complete&&checks==1);
 status=-EAGAIN;migration_pending=true;
 assert(!buffer_source_events_init()&&bootstrap_complete&&checks==2);
 /* A concurrent history worker can hold the journal through a NAND scan.
  * An already bootstrapped agent must not wait for that worker here. */
 pthread_mutex_lock(&journal_lock);
 assert(!buffer_source_events_init()&&checks==2);
 pthread_mutex_unlock(&journal_lock);
 /* Actual source operations still validate/recover and fail closed. */
 status=-EIO;assert(buffer_source_events_ready()==-EIO&&checks==3);
 status=0;assert(!buffer_source_events_ready()&&checks==4);
 return 0;
}
'''
    c=tmp_path/'bootstrap.c';c.write_text(harness);exe=tmp_path/'bootstrap'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)


def test_history_yields_to_foreground_without_waiting_on_runtime(tmp_path):
    harness=r'''
#include <assert.h>
#include "buffer_voice.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
int main(void){
 assert(!buffer_voice_foreground_busy());
 g_buffer.touch_requested=true;assert(buffer_voice_foreground_busy());g_buffer.touch_requested=false;
 g_buffer.recording=true;assert(buffer_voice_foreground_busy());g_buffer.recording=false;
 strcpy(g_buffer.voice.id,"current-voice");
 for(int phase=BUFFER_VOICE_RECORDING;phase<=BUFFER_VOICE_EXECUTING;phase++){
  g_buffer.voice.phase=phase;assert(buffer_voice_foreground_busy());
 }
 for(int phase=BUFFER_VOICE_READY;phase<=BUFFER_VOICE_CANCELLED;phase++){
  g_buffer.voice.phase=phase;assert(!buffer_voice_foreground_busy());
 }
 pthread_mutex_lock(&g_buffer.lock);
 assert(buffer_voice_foreground_busy()); /* must return without waiting */
 pthread_mutex_unlock(&g_buffer.lock);
 assert(!buffer_voice_foreground_busy());return 0;
}
'''
    c=tmp_path/'foreground.c';c.write_text(harness);exe=tmp_path/'foreground'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),
                    '-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
