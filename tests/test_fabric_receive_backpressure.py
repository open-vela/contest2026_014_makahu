"""A model-owned capture lock must defer replica commit, not stall BLE input."""
from pathlib import Path
import base64
import hashlib
import json
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c, fabric_link_declarations


def test_replica_defers_locked_commit_and_revalidates_retry(tmp_path: Path):
    source = (PROJECT_ROOT / 'app/buffer_app/buffer_replica.c').read_text()
    raw = b'{"card_id":"calendar-one","title":"meeting"}'
    message = json.dumps(dict(type='buffer.crdt.part', protocol=1,
        record_sha256=hashlib.sha256(raw).hexdigest(), offset=0, total=len(raw),
        record_base64=base64.b64encode(raw).decode(), retry_busy=True), separators=(',', ':'))
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include "cJSON.h"
#define BUFFER_AGENT_JSON_BYTES 65536
#define BUFFER_OOBE_GET_STATE 0
struct buffer_oobe_authorization_s {int (*authorize)(void *,int);void *context;};
static pthread_mutex_t g_buffer_capture_lock=PTHREAD_MUTEX_INITIALIZER;
static bool allowed=true;
static unsigned commits,projections;
'''
    harness += extract_c(source, 'struct replica_transfer_s', semicolon=True) + r'''
static struct replica_transfer_s state,*replica_transfer=&state;
static bool select_transfer(const char *peer,unsigned generation){(void)peer;return generation==7;}
static void reset_transfer(void){free(state.assembly);memset(&state,0,sizeof(state));}
static int authorize(void *context,int operation){(void)context;(void)operation;return allowed?0:-EACCES;}
static int buffer_source_json_object(const char *raw,size_t bytes,size_t limit,cJSON **value){
 if(bytes>limit)return -EFBIG;*value=cJSON_Parse(raw);return *value?0:-EINVAL;
}
static int commit(const struct buffer_oobe_authorization_s *auth,const char *peer,const char *hash,
                  const cJSON *record,const char *raw,const char **result){
 (void)peer;(void)hash;(void)record;(void)raw;assert(!auth->authorize(auth->context,0));
 assert(pthread_mutex_trylock(&g_buffer_capture_lock)==EBUSY);commits++;*result="imported";return 0;
}
static void buffer_agent_projection_changed(void){projections++;}
'''
    for signature in ('static const char *text(', 'static bool integer(',
                      'static bool digest_valid(', 'int buffer_replica_receive_session('):
        harness += extract_c(source, signature) + '\n'
    harness += '\nstatic const unsigned char input[]=' + json.dumps(message) + ';\n'
    harness += r'''
int main(void){
 struct buffer_oobe_authorization_s auth={authorize,NULL};
 const char *peer="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
 char *reply=NULL;
 pthread_mutex_lock(&g_buffer_capture_lock);
 /* Older peers did not negotiate busy receipts; preserve their old retry behavior. */
 cJSON *legacy=cJSON_Parse((char *)input);cJSON_DeleteItemFromObjectCaseSensitive(legacy,"retry_busy");char *legacy_raw=cJSON_PrintUnformatted(legacy);
 assert(buffer_replica_receive_session(&auth,peer,7,(unsigned char *)legacy_raw,strlen(legacy_raw),&reply)==-EAGAIN);
 assert(!reply&&!commits);cJSON_Delete(legacy);free(legacy_raw);
 assert(!buffer_replica_receive_session(&auth,peer,7,input,sizeof(input)-1,&reply));
 assert(reply&&!commits&&!projections&&state.assembled==state.expected&&state.assembly);
 cJSON *busy=cJSON_Parse(reply);assert(busy&&!strcmp(text(busy,"state"),"busy"));
 assert(cJSON_GetObjectItemCaseSensitive(busy,"next_offset")->valuedouble==state.expected);
 cJSON_Delete(busy);free(reply);reply=NULL;
 pthread_mutex_unlock(&g_buffer_capture_lock);
 /* Revocation while deferred cannot become an accepted durable receipt. */
 allowed=false;
 assert(buffer_replica_receive_session(&auth,peer,7,input,sizeof(input)-1,&reply)==-EACCES);
 assert(!reply&&!commits);
 allowed=true;
 assert(!buffer_replica_receive_session(&auth,peer,7,input,sizeof(input)-1,&reply));
 assert(reply&&commits==1&&projections==1);
 cJSON *value=cJSON_Parse(reply);assert(value);
 assert(!strcmp(text(value,"state"),"imported"));
 assert(cJSON_GetObjectItemCaseSensitive(value,"next_offset")->valuedouble==state.expected);
 cJSON_Delete(value);free(reply);reset_transfer();return 0;
}
'''
    c = tmp_path / 'receive.c'; c.write_text(harness)
    cjson = SDK_ROOT / 'apps/netutils/cjson/cJSON'
    mbedtls = SDK_ROOT / 'apps/crypto/mbedtls/mbedtls/include'
    executable = tmp_path / 'receive'
    subprocess.run([*CC, '-std=c11', '-D_DEFAULT_SOURCE', '-pthread',
        '-I' + str(cjson), '-I' + str(mbedtls), str(c), str(cjson / 'cJSON.c'),
        '/tmp/buffer-agent-mbedtls/library/libmbedcrypto.a', '-lm', '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=5)


def test_mode_storage_runs_without_transport_lock_and_cannot_replace_new_session(tmp_path: Path):
    source = (PROJECT_ROOT / 'app/buffer_app/buffer_fabric.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
''' + fabric_link_declarations() + r'''
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t io_wake=PTHREAD_COND_INITIALIZER;
static bool stopping,replace_session;
static bool peer_admitted(struct fabric_link_s *link){return link->session_trusted;}
static void unlock_status(void){pthread_mutex_unlock(&lock);}
static uint64_t milliseconds(void){return 1000;}
static bool mode_refresh(struct buffer_fabric_mode_state_s *mode,unsigned generation,uint64_t term,uint64_t now){
 (void)now;
 /* Focus snapshot storage may take arbitrarily long. It must never own
  * the mutex needed by receive callbacks and authenticated receipts. */
 assert(!pthread_mutex_trylock(&lock));
 assert(mode->message!=links[0].mode_state.message);
 if(replace_session)links[0].generation++;
 pthread_mutex_unlock(&lock);
 free(mode->message);mode->message=strdup("prepared");mode->generation=generation;mode->owner_term=term;
 return true;
}
'''
    harness += extract_c(source, 'static void mode_reset_session(') + '\n'
    harness += extract_c(source, 'static void prepare_link_mode(') + r'''
int main(void){
 links[0].active=links[0].linked=links[0].session_trusted=true;links[0].generation=1;
 links[0].mode_state.generation=1;links[0].mode_state.owner_term=3;
 links[0].mode_state.message=strdup("cached");links[0].mode_state.retry_at_ms=6000;
 prepare_link_mode(0,1,3);
 assert(!strcmp(links[0].mode_state.message,"prepared")&&links[0].mode_state.retry_at_ms==6000);
 free(links[0].mode_state.message);links[0].mode_state.message=strdup("new-session");
 replace_session=true;prepare_link_mode(0,1,3);
 assert(links[0].generation==2&&!strcmp(links[0].mode_state.message,"new-session"));
 mode_reset_session(&links[0].mode_state);return 0;
}
'''
    c = tmp_path / 'mode.c'; c.write_text(harness)
    executable = tmp_path / 'mode'
    subprocess.run([*CC, '-std=c11', '-D_DEFAULT_SOURCE', '-pthread', str(c), '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=5)
