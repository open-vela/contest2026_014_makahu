"""Production UI paths remain usable while persistence/radio work is blocked."""
from pathlib import Path
import subprocess
from host_support import PROJECT_ROOT, CC, extract_c


def test_ui_runtime_contention_and_obsolete_detail(tmp_path: Path):
    app = PROJECT_ROOT / 'app/buffer_app'
    main = (app / 'buffer_app_main.c').read_text()
    ui = (app / 'buffer_ui.c').read_text()
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <semaphore.h>
#include "buffer_app.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
static pthread_mutex_t g_wifi_poll_lock=PTHREAD_MUTEX_INITIALIZER;
static bool g_wifi_poll_stop;
static char g_detail_job_id[BUFFER_ID_SIZE];
static char g_detail_fallback[BUFFER_TEXT_SIZE*2+8];
static uint64_t g_detail_job_revision, g_detail_job_conversation, g_detail_job_epoch;
static bool g_detail_requested, g_detail_working, g_detail_ready;
static char *g_detail_result;
static sem_t entered, release_io;
static bool block_io;
static char *buffer_replica_detail(const char *id)
{
 if(block_io){sem_post(&entered);sem_wait(&release_io);}
 return strdup(id);
}
static char *buffer_followup_history(const char *id){(void)id;return strdup("complete history");}
'''
    harness += extract_c(main, 'bool buffer_ui_runtime_snapshot(')
    harness += extract_c(ui, 'static void buffer_ui_detail_load(')
    harness += r'''
static void *hold_runtime(void *unused)
{
 (void)unused;pthread_mutex_lock(&g_buffer.lock);sem_post(&entered);
 sem_wait(&release_io);pthread_mutex_unlock(&g_buffer.lock);return NULL;
}
static void *load_detail(void *unused){(void)unused;buffer_ui_detail_load();return NULL;}
int main(void)
{
 sem_init(&entered,0,0);sem_init(&release_io,0,0);
 struct buffer_ui_runtime_s *view=calloc(1,sizeof(*view));assert(view);
 strcpy(view->status,"last frame");pthread_t worker;
 assert(!pthread_create(&worker,NULL,hold_runtime,NULL));sem_wait(&entered);
 /* Does not wait for the storage owner and leaves the last frame intact. */
 for(unsigned i=0;i<1000;i++)assert(!buffer_ui_runtime_snapshot(view));
 assert(!strcmp(view->status,"last frame"));sem_post(&release_io);pthread_join(worker,NULL);
 strcpy(g_buffer.mode,"focus");strcpy(g_buffer.status,"new frame");
 g_buffer.card_count=1;strcpy(g_buffer.cards[0].title,"saved card");
 g_buffer.focus_started_at=123;g_buffer.recording=true;
 assert(buffer_ui_runtime_snapshot(view));assert(!strcmp(view->status,"new frame"));
 assert(!strcmp(view->cards[0].title,"saved card")&&view->recording&&view->focus_started_at==123);
 /* A delayed card read must never overwrite a subsequently selected card. */
 strcpy(g_detail_job_id,"old-card");g_detail_job_epoch=1;g_detail_requested=true;block_io=true;
 assert(!pthread_create(&worker,NULL,load_detail,NULL));sem_wait(&entered);
 assert(!pthread_mutex_trylock(&g_wifi_poll_lock));
 strcpy(g_detail_job_id,"new-card");g_detail_job_epoch++;g_detail_requested=true;
 g_detail_job_revision=2;g_detail_job_conversation=3;
 pthread_mutex_unlock(&g_wifi_poll_lock);sem_post(&release_io);pthread_join(worker,NULL);
 assert(!g_detail_ready&&!g_detail_result&&g_detail_requested);
 block_io=false;buffer_ui_detail_load();assert(g_detail_ready&&g_detail_result);
 assert(!strcmp(g_detail_result,"new-card\n\ncomplete history"));
 free(g_detail_result);free(view);sem_destroy(&entered);sem_destroy(&release_io);return 0;
}
'''
    source = tmp_path / 'ui_runtime.c'
    source.write_text(harness)
    binary = tmp_path / 'ui_runtime'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-g', '-pthread',
                    '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    '-I' + str(app), '-I' + str(PROJECT_ROOT / 'tests/fixtures/ui'),
                    str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)


def test_agent_snapshot_does_not_wait_for_config_io(tmp_path: Path):
    """A stalled NAND config load must leave the published UI view readable."""
    from host_support import SDK_ROOT
    store = (PROJECT_ROOT / 'app/buffer_app/buffer_agent_store.c').read_text()
    locks = '\n'.join(line for line in store.splitlines()
                      if line.startswith('static pthread_mutex_t '))
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <semaphore.h>
#include <unistd.h>
#include "buffer_agent.h"
#include <string.h>
static sem_t entered, release_io;
static unsigned snapshot_done;
static struct buffer_agent_status_s observed;
'''
    harness += locks + '\nstatic struct buffer_agent_status_s cached_status;\n'
    for signature in ('static void status_update(', 'void buffer_agent_snapshot(',
                      'void buffer_agent_set_validating(', 'void buffer_agent_report_error('):
        harness += extract_c(store, signature) + '\n'
    harness += r'''
static int config_load(struct buffer_agent_config_s *config)
{
 sem_post(&entered);sem_wait(&release_io);
 config->request.operation=BUFFER_OOBE_CONFIGURE_AGENT;
 config->request.revision=8;config->validated=true;config->last_error=0;return 0;
}
'''
    harness += extract_c(store, 'int buffer_agent_config_load(')
    harness += r'''
static void *slow_io(void *unused)
{(void)unused;struct buffer_agent_config_s config={0};assert(!buffer_agent_config_load(&config));return NULL;}
static void *ui_snapshot(void *unused)
{(void)unused;buffer_agent_snapshot(&observed);__atomic_store_n(&snapshot_done,1,__ATOMIC_RELEASE);return NULL;}
int main(void)
{
 sem_init(&entered,0,0);sem_init(&release_io,0,0);
 struct buffer_agent_config_s initial={0};initial.request.operation=BUFFER_OOBE_CONFIGURE_AGENT;
 initial.request.revision=7;initial.validated=true;status_update(&initial,0);buffer_agent_set_validating(true);
 pthread_t io,ui;assert(!pthread_create(&io,NULL,slow_io,NULL));sem_wait(&entered);
 assert(!pthread_create(&ui,NULL,ui_snapshot,NULL));
 for(unsigned i=0;i<100&&!__atomic_load_n(&snapshot_done,__ATOMIC_ACQUIRE);i++)usleep(1000);
 assert(snapshot_done);assert(observed.revision==7&&observed.ready&&observed.validating);
 pthread_join(ui,NULL);sem_post(&release_io);pthread_join(io,NULL);
 buffer_agent_snapshot(&observed);assert(observed.revision==8&&observed.ready&&observed.validating);
 buffer_agent_report_error(-EIO);buffer_agent_set_validating(false);buffer_agent_snapshot(&observed);
 assert(observed.error==-EIO&&!observed.validating);
 struct buffer_agent_config_s loaded={0};sem_post(&release_io);
 assert(buffer_agent_config_load(&loaded)==0);
 buffer_agent_snapshot(&observed);
 assert(observed.error==-EIO); /* Reading the same config cannot erase a runtime failure. */
 buffer_agent_report_error(0);buffer_agent_snapshot(&observed);assert(observed.error==0);
 sem_destroy(&entered);sem_destroy(&release_io);return 0;
}
'''
    source = tmp_path / 'agent_ui.c'; source.write_text(harness)
    binary = tmp_path / 'agent_ui'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread',
                    '-fsanitize=address,undefined', '-I' + str(PROJECT_ROOT / 'app/buffer_app'),
                    '-I' + str(PROJECT_ROOT / 'tests/fixtures/ui'),
                    '-I' + str(SDK_ROOT / 'apps/include'), str(source), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)


def test_committed_archive_retires_home_without_full_deck_io(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_deck.h').read_text()
    harness=r'''
#include <assert.h>
#include <string.h>
#include "buffer_app.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
'''+extract_c(source,'static inline void buffer_deck_retire_home(')+r'''
int main(void){
 g_buffer.card_count=3;g_buffer.card_index=2;
 strcpy(g_buffer.cards[0].card_id,"first");strcpy(g_buffer.cards[1].card_id,"archived");strcpy(g_buffer.cards[2].card_id,"selected");
 buffer_deck_retire_home("archived");
 assert(g_buffer.card_count==2&&g_buffer.card_index==1);
 assert(!strcmp(g_buffer.cards[1].card_id,"selected")&&!g_buffer.cards[2].card_id[0]);
 buffer_deck_retire_home("missing");assert(g_buffer.card_count==2);
 buffer_deck_retire_home("selected");buffer_deck_retire_home("first");
 assert(!g_buffer.card_count&&!g_buffer.card_index);return 0;
}
'''
    c=tmp_path/'retire.c';c.write_text(harness);exe=tmp_path/'retire'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-pthread',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),
                    '-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
