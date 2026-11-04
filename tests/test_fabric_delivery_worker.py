"""Storage selection cannot hold the BLE executor lock or publish stale work."""
from pathlib import Path
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c, fabric_link_declarations


def test_slow_storage_keeps_executor_available_and_fences_prepared_frames(tmp_path: Path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_fabric.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stddef.h>
'''+fabric_link_declarations()+r'''
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t io_wake=PTHREAD_COND_INITIALIZER;
static bool stopping,app_busy;
static uint64_t delivery_revision,member_revision;
static sem_t entered,release_io;
static void unlock_status(void){pthread_mutex_unlock(&lock);}
static bool peer_admitted(struct fabric_link_s *p){return p->session_trusted;}
static void hex(const uint8_t *p,char *out){(void)p;memset(out,'a',64);out[64]=0;}
static size_t fabric_ble_host_max_payload(void *p){(void)p;return 4096;}
static uint64_t buffer_coordinator_fence(void){return 1;}
static bool buffer_coordinator_phone_owner_current(const char *p,uint64_t t){(void)p;(void)t;return true;}
static int buffer_coordinator_transport_view(char *p,size_t n,uint64_t *t,bool *live){memset(p,'a',64);p[64]=0;*t=1;*live=true;(void)n;return 0;}
static void prepare_link_mode(unsigned slot,unsigned generation,uint64_t term){(void)slot;(void)generation;(void)term;assert(!pthread_mutex_trylock(&lock));pthread_mutex_unlock(&lock);}
static uint64_t fake_now=1000;
static bool slow_empty_scan;
static uint64_t milliseconds(void){return fake_now;}
static int buffer_coordinator_peer_heartbeat(const char *p,uint64_t t,char **s){(void)p;(void)t;(void)s;return 0;}
static int buffer_crdt_sync_next(const char *p,unsigned g,uint64_t t,size_t b,char **s){
 (void)p;(void)g;(void)t;(void)b;
 if(slow_empty_scan){fake_now+=30000;return 0;}
 sem_post(&entered);sem_wait(&release_io);*s=strdup("prepared");return 1;
}
static int buffer_replica_action_next_for_peer(const char *p,uint64_t t,char **s){(void)p;(void)t;(void)s;return 0;}
static int buffer_agent_input_next_for_peer(const char *p,uint64_t f,uint64_t t,char **s){
 (void)p;(void)f;(void)s;if(slow_empty_scan){assert(t==31000);fake_now+=10000;}return 0;
}
static int buffer_agent_outbox_next(size_t b,char **s){(void)b;(void)s;return 0;}
static int buffer_agent_card_state_next(uint64_t t,size_t b,char **s){
 (void)b;(void)s;if(slow_empty_scan){assert(t==41000);pthread_mutex_lock(&lock);stopping=true;pthread_mutex_unlock(&lock);}return 0;
}
static bool delivery_reset_pending,block_reset;
static unsigned resets,input_resets;
static void buffer_agent_outbox_reset(void){resets++;if(block_reset){sem_post(&entered);sem_wait(&release_io);}}
static void buffer_agent_input_reset(void){input_resets++;}
'''+extract_c(source,'static void *delivery_main(')+r'''
int main(void){
 sem_init(&entered,0,0);sem_init(&release_io,0,0);
 for(int scenario=0;scenario<3;scenario++){
  memset(links,0,sizeof(links));stopping=false;
  links[0].generation=1;links[0].active=links[0].linked=links[0].session_trusted=true;
  pthread_t worker;assert(!pthread_create(&worker,NULL,delivery_main,NULL));sem_wait(&entered);
  /* A real blocked storage worker must leave the mutex used by radio
   * callbacks, incoming frames and protocol polling immediately available. */
  assert(!pthread_mutex_trylock(&lock));
  if(scenario==0)links[0].generation++;
  if(scenario==1)delivery_revision++;
  if(scenario<2)stopping=true;
  pthread_mutex_unlock(&lock);sem_post(&release_io);
  if(scenario==2){
   bool ready=false;
   for(int i=0;i<200&&!ready;i++){pthread_mutex_lock(&lock);ready=links[0].outbox_message!=NULL;pthread_mutex_unlock(&lock);usleep(1000);}
   assert(ready);pthread_mutex_lock(&lock);stopping=true;pthread_mutex_unlock(&lock);
  }
  pthread_join(worker,NULL);
  if(scenario<2)assert(!links[0].outbox_message);
  else {assert(!strcmp(links[0].outbox_message,"prepared"));assert(links[0].outbox_crdt);free(links[0].outbox_message);}
 }
 /* Slow earlier lanes must not pass their expired clock sample onward. */
 memset(links,0,sizeof(links));stopping=false;slow_empty_scan=true;fake_now=1000;
 links[0].generation=1;links[0].active=links[0].linked=links[0].session_trusted=links[0].view_accepted=true;
 links[0].view_revision=member_revision;
 pthread_t clock_worker;assert(!pthread_create(&clock_worker,NULL,delivery_main,NULL));pthread_join(clock_worker,NULL);
 assert(fake_now==41000);slow_empty_scan=false;
 /* A delivery reset must not occupy the application worker or radio lock,
  * and completion for an old owner must not clear a replacement reset. */
 for(int changed=0;changed<2;changed++){
  memset(links,0,sizeof(links));stopping=false;delivery_reset_pending=true;block_reset=true;
  unsigned before=resets;pthread_t worker;assert(!pthread_create(&worker,NULL,delivery_main,NULL));sem_wait(&entered);
  assert(!pthread_mutex_trylock(&lock));assert(!app_busy);
  if(changed)delivery_revision++;
  stopping=true;pthread_mutex_unlock(&lock);sem_post(&release_io);pthread_join(worker,NULL);
  assert(resets==before+1&&input_resets==resets);assert(delivery_reset_pending==(bool)changed);
 }
 sem_destroy(&entered);sem_destroy(&release_io);return 0;
}
'''
    c=tmp_path/'delivery.c';c.write_text(harness);exe=tmp_path/'delivery'
    subprocess.run([*CC,'-std=c11','-D_DEFAULT_SOURCE','-pthread','-I'+str(PROJECT_ROOT/'app/buffer_app'),
                    '-I'+str(SDK_ROOT/'apps/netutils/cjson/cJSON'),str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=10)


def test_transport_policy_check_defers_while_storage_owns_transfer(tmp_path: Path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_crdt_sync.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static int checks;
static bool select_transfer(const char *peer,unsigned generation,bool create){(void)peer;assert(!create);return generation==7;}
static bool pending_allowed_locked(const char *peer,unsigned generation,uint64_t now){(void)peer;(void)generation;checks++;return now==1000;}
'''+extract_c(source,'int buffer_crdt_sync_pending_check(')+r'''
int main(void){
 pthread_mutex_lock(&lock);
 assert(buffer_crdt_sync_pending_check("peer",7,1000)==-EAGAIN);assert(checks==0);
 pthread_mutex_unlock(&lock);
 assert(buffer_crdt_sync_pending_check("peer",7,1000)==1);assert(checks==1);
 assert(buffer_crdt_sync_pending_check("peer",8,1000)==0);assert(checks==1);
 assert(buffer_crdt_sync_pending_check("peer",7,31000)==0);assert(checks==2);
 return 0;
}
'''
    c=tmp_path/'policy.c';c.write_text(harness);exe=tmp_path/'policy'
    subprocess.run([*CC,'-pthread',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
