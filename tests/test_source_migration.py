"""Legacy discovery yields between entries and never gates unrelated runtime state."""
import subprocess
from host_support import PROJECT_ROOT, CC, extract_c


def test_projection_migration_is_bounded_and_retries_the_same_entry(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_source_events.c').read_text()
    for name in ('replicas','conversations','records','calendars'):
        (tmp_path/name).mkdir()
    (tmp_path/'replicas/a.json').write_text('{}')
    (tmp_path/'calendars/b.json').write_text('{}')
    harness=r'''
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#define CONFIG_BUFFER_VELA_DATA_DIR "DATA_PATH"
#define BUFFER_ID_SIZE 161
static unsigned calls,a,b;
static int migrate_projection_locked(const char *kind,const char *id){
 (void)kind;calls++;
 if(!strcmp(id,"a")&&++a==1)return -EAGAIN;
 if(!strcmp(id,"b")&&++b==1)return -EIO;
 return 0;
}
static int projection_id(const char *path,const char **kind,char *id){
 const char *name=strrchr(path,'/');name=name?name+1:path;size_t n=strlen(name);
 if(n<6||strcmp(name+n-5,".json"))return 0;
 memcpy(id,name,n-5);id[n-5]=0;*kind="calendar";return 1;
}
'''.replace('DATA_PATH',str(tmp_path))+extract_c(source,'static int migrate_projections_locked(')+r'''
int main(void){
 int ret=-EAGAIN;
 for(unsigned step=0;step<100&&ret;step++){
  unsigned before=calls;ret=migrate_projections_locked();
  assert(calls-before<=1);assert(!ret||ret==-EAGAIN||ret==-EIO);
 }
 assert(!ret&&a==2&&b==2);return 0;
}
'''
    c=tmp_path/'migration.c';c.write_text(harness);exe=tmp_path/'migration'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)


def test_critical_runtime_restores_without_waiting_for_global_migration(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_app_main.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <syslog.h>
#include "buffer_app.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER,.mode="normal"};
static pthread_mutex_t g_mode_commit_lock=PTHREAD_MUTEX_INITIALIZER;
static bool source_startup_pending=true,research_reconcile;
static unsigned base_checks,global_checks,wakes;
static int pending_error;
int buffer_source_events_init(void){base_checks++;return 0;}
int buffer_source_events_ready(void){global_checks++;return -EAGAIN;}
int buffer_store_load_mode(char *out,size_t n){snprintf(out,n,"bedtime");return 0;}
int buffer_store_load_pending_mode(char *mode,size_t m,char *id,size_t n){(void)mode;(void)m;(void)id;(void)n;return pending_error?pending_error:-ENOENT;}
int buffer_store_load_pending_action(char *a,size_t an,char *b,size_t bn,char *c,size_t cn){(void)a;(void)an;(void)b;(void)bn;(void)c;(void)cn;return -ENOENT;}
int buffer_store_load_focus_interval(uint32_t *out){*out=300000;return 0;}
static void focus_runtime_reload_locked(void){}
void buffer_agent_projection_changed(void){}
void buffer_agent_wake(void){wakes++;}
'''+extract_c(source,'static void source_startup_tick(')+r'''
int main(void){
 pending_error=-EAGAIN;source_startup_tick();assert(source_startup_pending&&!strcmp(g_buffer.mode,"normal")&&!wakes);
 pending_error=0;source_startup_tick();assert(!source_startup_pending&&!strcmp(g_buffer.mode,"bedtime")&&wakes==1);
 assert(base_checks==2&&!global_checks);source_startup_tick();assert(base_checks==2);return 0;
}
'''
    c=tmp_path/'startup.c';c.write_text(harness);exe=tmp_path/'startup'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),
                    str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
