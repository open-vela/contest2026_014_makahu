"""The real commit path must preserve retryable index-read errors."""
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_crdt_commit_preserves_cold_index_retry_without_writes(tmp_path):
    src=(PROJECT_ROOT/'app/buffer_app/buffer_replica.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <dirent.h>
#include <sys/stat.h>
#include "cJSON.h"
#define BUFFER_AGENT_JSON_BYTES 65536
#define BUFFER_OOBE_GET_STATE 0
#define REPLICA_LIMIT 512
#define CONFIG_BUFFER_VELA_DATA_DIR "DATA_PATH"
#define REPLICA_DIR CONFIG_BUFFER_VELA_DATA_DIR "/replicas"
struct buffer_oobe_authorization_s {int (*authorize)(void *,int);void *context;};
static int read_error=EAGAIN,writes;
static void buffer_deck_retire_home(const char *id){(void)id;assert(0);}
static int path_for(char *p,size_t n,const char *id){return snprintf(p,n,REPLICA_DIR "/%s.json",id)<0?-EINVAL:0;}
static int buffer_crdt_validate(const cJSON *v){return cJSON_IsObject(v)?0:-EINVAL;}
static int buffer_agent_store_init(void){return 0;}
static cJSON *buffer_agent_read_json(const char *p,size_t n){(void)p;(void)n;errno=read_error;return NULL;}
static cJSON *buffer_crdt_merge(const cJSON *a,const cJSON *b){(void)a;(void)b;return NULL;}
static cJSON *buffer_replica_crdt_record(const cJSON *a,const cJSON *b){(void)a;(void)b;return NULL;}
static int buffer_agent_atomic_json(const char *p,const cJSON *v){(void)p;(void)v;writes++;return 0;}
static int authorize(void *p,int op){(void)p;(void)op;return 0;}
'''.replace('DATA_PATH',str(tmp_path))
    for sig in ['static const char *text(', 'static bool integer(', 'static bool digest_valid(', 'static int commit_view(']:
        harness+=extract_c(src,sig)+'\n'
    harness+=r'''
int main(void){
 struct buffer_oobe_authorization_s auth={authorize,NULL};const char *state=NULL;
 cJSON *record=cJSON_Parse("{\"card_id\":\"calendar-one\",\"crdt\":{\"id\":\"calendar-one\",\"kind\":\"calendar\"}}");
 int errors[]={EAGAIN,EIO,EACCES,EBUSY};
 for(unsigned i=0;i<sizeof(errors)/sizeof(errors[0]);i++){
  read_error=errors[i];assert(commit_view(&auth,"peer","hash",record,&state,NULL)==-read_error);assert(!writes);
 }
 cJSON_Delete(record);return 0;
}
'''
    c=tmp_path/'cold.c';c.write_text(harness);exe=tmp_path/'cold';cjson=SDK_ROOT/'apps/netutils/cjson/cJSON'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-I'+str(cjson),str(c),str(cjson/'cJSON.c'),'-lm','-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
