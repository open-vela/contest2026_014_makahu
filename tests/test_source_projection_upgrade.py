"""An older derived cursor restarts once without changing retained event facts."""
import re
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_old_projection_cursor_restarts_once_and_future_versions_fail_closed(tmp_path):
    app=PROJECT_ROOT/'app/buffer_app'
    src=(app/'buffer_source_remote.c').read_text()
    header=(app/'buffer_source_remote.h').read_text()
    version=re.search(r'^#define BUFFER_SOURCE_PROJECTION_VERSION \d+',header,re.M).group()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "cJSON.h"
#define MAX_COUNTER 9007199254740991ULL
#define PROJECTION_CURSOR "derived-cursor"
static cJSON *stored;
static unsigned writes;
static int json_read(const char *path,cJSON **out){assert(!strcmp(path,PROJECTION_CURSOR));*out=cJSON_Duplicate(stored,true);return *out?0:-ENOENT;}
static int json_write(const char *path,const cJSON *value){assert(!strcmp(path,PROJECTION_CURSOR));cJSON_Delete(stored);stored=cJSON_Duplicate(value,true);writes++;return stored?0:-ENOMEM;}
'''+version+'\n'
    for sig in ['static const char *text(', 'static bool number(', 'static bool hex(',
                'static int projection_cursor_save(', 'static int projection_cursor_load(']:
        harness+=extract_c(src,sig)+'\n'
    harness+=r'''
int main(void){
 assert(BUFFER_SOURCE_PROJECTION_VERSION>=4);
 stored=cJSON_Parse("{\"version\":3,\"source_hash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"sequence\":209}");
 char hash[65];uint64_t sequence=999;
 assert(!projection_cursor_load(hash,&sequence));assert(!hash[0]&&!sequence&&writes==1);
 assert(!projection_cursor_load(hash,&sequence));assert(!hash[0]&&!sequence&&writes==1);
 memset(hash,'b',64);hash[64]=0;assert(!projection_cursor_save(hash,275));
 assert(!projection_cursor_load(hash,&sequence));assert(sequence==275&&hash[0]=='b'&&writes==2);
 cJSON_ReplaceItemInObjectCaseSensitive(stored,"version",cJSON_CreateNumber(BUFFER_SOURCE_PROJECTION_VERSION+1));
 assert(projection_cursor_load(hash,&sequence)==-EIO&&writes==2);
 cJSON_Delete(stored);return 0;
}
'''
    c=tmp_path/'upgrade.c';c.write_text(harness);exe=tmp_path/'upgrade'
    cjson=SDK_ROOT/'apps/netutils/cjson/cJSON'
    subprocess.run([*CC,'-Wall','-Wextra','-Werror','-I'+str(cjson),str(c),str(cjson/'cJSON.c'),'-lm','-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
