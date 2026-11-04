"""Presentation remains readable while the production mode writers wait on NAND."""
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_mode_commit_releases_runtime_during_each_storage_phase(tmp_path):
    main=(PROJECT_ROOT/'app/buffer_app/buffer_app_main.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <semaphore.h>
#include <stdio.h>
#include <string.h>
#include "buffer_app.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER,.mode="normal"};
static pthread_mutex_t g_mode_commit_lock=PTHREAD_MUTEX_INITIALIZER;
static sem_t entered,release_io;
static bool focus_runtime_checkpoint;
static int64_t buffer_monotonic_ms(void){return 10000;}
static int buffer_generate_action_id(char *out,size_t n){snprintf(out,n,"mode-test");return 0;}
void buffer_set_status(const char *fmt,...){(void)fmt;}
static uint32_t buffer_focus_interval(void){return 300000;}
int buffer_store_save_pending_mode(const char *mode,const char *id){(void)mode;(void)id;sem_post(&entered);sem_wait(&release_io);return 0;}
int buffer_store_save_mode(const char *mode){(void)mode;sem_post(&entered);sem_wait(&release_io);return 0;}
static int focus_runtime_save_locked(const char *type,int64_t now){(void)type;(void)now;return 0;}
void buffer_request_sync(void){}
'''+extract_c(main,'void buffer_request_mode(')+r'''
static void *request(void *unused){(void)unused;buffer_request_mode("bedtime");return NULL;}
int main(void){
 sem_init(&entered,0,0);sem_init(&release_io,0,0);pthread_t writer;
 assert(!pthread_create(&writer,NULL,request,NULL));sem_wait(&entered);
 assert(!pthread_mutex_trylock(&g_buffer.lock));assert(!strcmp(g_buffer.mode,"normal"));pthread_mutex_unlock(&g_buffer.lock);
 sem_post(&release_io);sem_wait(&entered);
 assert(!pthread_mutex_trylock(&g_buffer.lock));assert(!strcmp(g_buffer.mode,"bedtime")&&g_buffer.mode_dirty);pthread_mutex_unlock(&g_buffer.lock);
 sem_post(&release_io);pthread_join(writer,NULL);
 sem_destroy(&entered);sem_destroy(&release_io);return 0;
}
'''
    c=tmp_path/'mode.c';c.write_text(harness);exe=tmp_path/'mode'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),
                    str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)


def test_focus_checkpoint_serializes_writers_without_holding_runtime(tmp_path):
    main=(PROJECT_ROOT/'app/buffer_app/buffer_app_main.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <semaphore.h>
#include <string.h>
#include "buffer_app.h"
#include "cJSON.h"
#define FOCUS_RUNTIME_FILE "focus-runtime"
#define BUFFER_AGENT_JSON_BYTES 65536
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER,.mode="focus",.focus_interval_ms=300000};
static pthread_mutex_t g_focus_runtime_commit_lock=PTHREAD_MUTEX_INITIALIZER;
static sem_t entered,release_io;
static bool focus_runtime_reconcile;
static char focus_runtime_boot[33]="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
static int focus_runtime_boot_init(void){return 0;}
static int64_t buffer_monotonic_ms(void){return 10000;}
static int64_t focus_wall_ms(void){return 1700000000000LL;}
static unsigned snapshots;
static int buffer_source_projection_json(const char *path,const cJSON *value,const char *type,const char *action){
 (void)path;(void)type;(void)action;
 unsigned n=__atomic_add_fetch(&snapshots,1,__ATOMIC_ACQ_REL);
 assert(cJSON_GetObjectItemCaseSensitive(value,"interval_ms")->valuedouble==(n==1?300000:600000));
 sem_post(&entered);sem_wait(&release_io);return 0;
}
static cJSON *buffer_source_projection_read(const char *path,size_t limit){(void)path;(void)limit;assert(0);return NULL;}
'''
    for sig in ('static int focus_runtime_write_locked(','static int focus_runtime_save_locked('):
        harness+=extract_c(main,sig)+'\n'
    harness+=r'''
static void *save(void *unused){(void)unused;pthread_mutex_lock(&g_buffer.lock);assert(!focus_runtime_save_locked("Checkpoint",10000));pthread_mutex_unlock(&g_buffer.lock);return NULL;}
int main(void){
 sem_init(&entered,0,0);sem_init(&release_io,0,0);pthread_t first,second;
 assert(!pthread_create(&first,NULL,save,NULL));sem_wait(&entered);
 assert(!pthread_mutex_trylock(&g_buffer.lock));g_buffer.focus_interval_ms=600000;pthread_mutex_unlock(&g_buffer.lock);
 assert(!pthread_create(&second,NULL,save,NULL));sem_post(&release_io);sem_wait(&entered);
 assert(!pthread_mutex_trylock(&g_buffer.lock));pthread_mutex_unlock(&g_buffer.lock);
 sem_post(&release_io);pthread_join(first,NULL);pthread_join(second,NULL);
 assert(snapshots==2);sem_destroy(&entered);sem_destroy(&release_io);return 0;
}
'''
    c=tmp_path/'focus.c';c.write_text(harness);exe=tmp_path/'focus';cjson=SDK_ROOT/'apps/netutils/cjson/cJSON'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),
                    '-I'+str(cjson),str(c),str(cjson/'cJSON.c'),'-lm','-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
