"""Actual checkpoint recovery must not treat image search as presentation."""
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_readonly_image_success_does_not_block_recipe_retry(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_agent_model.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include "cJSON.h"
#define BUFFER_AGENT_JSON_BYTES 65536
struct buffer_agent_config_s {unsigned unused;};
static unsigned executions,writes;
static const char *str(const cJSON *json,const char *key){const cJSON *value=cJSON_GetObjectItemCaseSensitive(json,key);return cJSON_IsString(value)?value->valuestring:"";}
static int buffer_agent_record_path(char *path,size_t size,const char *id){(void)id;snprintf(path,size,"/fixture/record");return 0;}
static cJSON *buffer_agent_read_json(const char *path,size_t limit){(void)path;(void)limit;return cJSON_Parse("{\"target\":\"assistant\",\"created_at\":0}");}
static int config_current(const struct buffer_agent_config_s *c,uint64_t fence,bool required){(void)c;(void)fence;(void)required;return 0;}
static cJSON *buffer_agent_assistant_tool(const char *name,const cJSON *args,const char *id,unsigned slot){(void)args;(void)id;assert(slot==1&&!strcmp(name,"show_recipe"));executions++;return cJSON_Parse("{\"success\":true,\"object_id\":\"recipe\"}");}
static int assistant_checkpoint_store(const char *id,const cJSON *checkpoint){(void)id;(void)checkpoint;writes++;return 0;}
'''
    harness+=extract_c(source,'static cJSON *assistant_tool_receipt(')+'\n'
    harness+=extract_c(source,'static int assistant_checkpoint_resume(')+'\n'
    harness+=r'''
int main(void){
 struct buffer_agent_config_s config={0};bool expired=true;
 cJSON *checkpoint=cJSON_Parse("{\"tools\":[{\"slot\":0,\"call\":{\"id\":\"search\",\"function\":{\"name\":\"image_search\",\"arguments\":\"{}\"}},\"output\":{\"success\":true},\"receipt\":{}},{\"slot\":1,\"call\":{\"id\":\"recipe\",\"function\":{\"name\":\"show_recipe\",\"arguments\":\"{}\"}},\"output\":{\"success\":false,\"retryable\":true},\"receipt\":{}}]}");assert(checkpoint);
 assert(!assistant_checkpoint_resume(&config,"capture",checkpoint,&expired));assert(!expired&&executions==1&&writes==1);
 assert(!assistant_checkpoint_resume(&config,"capture",checkpoint,&expired));assert(executions==1&&writes==1);
 cJSON_Delete(checkpoint);return 0;
}
'''
    test=tmp_path/'checkpoint.c';test.write_text(harness);binary=tmp_path/'checkpoint'
    subprocess.run([*CC,'-std=c11','-D_DEFAULT_SOURCE','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-I'+str(SDK_ROOT/'apps/netutils/cjson/cJSON'),str(test),str(SDK_ROOT/'apps/netutils/cjson/cJSON/cJSON.c'),'-lm','-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True,timeout=10)
