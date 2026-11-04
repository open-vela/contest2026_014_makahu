"""Production historical validation: restart states and pre-image recipe retry."""
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_historical_timer_and_recipe_compatibility(tmp_path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_assistant.c').read_text()
    harness=r'''
#include "buffer_assistant.h"
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "cJSON.h"
#define ASSISTANT_MAX_TIMER_MS (24u*60u*60u*1000u)
static cJSON *saved_args,*saved_operation;
static int lookup_error;
static int buffer_source_assistant_operation(const char *id,cJSON **operation,cJSON **request)
{(void)id;*operation=*request=NULL;if(lookup_error)return lookup_error;*operation=cJSON_Duplicate(saved_operation,true);*request=cJSON_Duplicate(saved_args,true);return 0;}
'''
    for signature in ('static bool assistant_json_u64(', 'static bool assistant_read_u64(', 'static bool assistant_read_number(', 'static bool assistant_read_text(', 'static uint64_t assistant_hash_bytes(', 'static uint64_t assistant_hash_string(', 'static uint64_t assistant_fingerprint(', 'static cJSON *assistant_encode_request(', 'static int assistant_historical_operation('):
        harness+=extract_c(source,signature)+'\n'
    harness+=r'''
static uint64_t store(struct buffer_assistant_request_s *request,unsigned state)
{
 cJSON_Delete(saved_args);cJSON_Delete(saved_operation);saved_args=assistant_encode_request(request);assert(saved_args);
 saved_operation=cJSON_CreateObject();assert(saved_operation);uint64_t fingerprint=assistant_fingerprint(request);
 assert(assistant_json_u64(saved_operation,"fingerprint",fingerprint));
 cJSON_AddNumberToObject(saved_operation,"operation",request->operation);cJSON_AddNumberToObject(saved_operation,"state",state);
 cJSON_AddNumberToObject(saved_operation,"remaining_ms",30000);cJSON_AddStringToObject(saved_operation,"object_id",request->object_id);return fingerprint;
}
int main(void){
 struct buffer_assistant_request_s request={.operation=BUFFER_ASSISTANT_TIMER_RESTART};
 strcpy(request.operation_id,"restart-recorded");strcpy(request.object_id,"timer-existing");
 struct buffer_assistant_result_s result={0};uint64_t fingerprint=store(&request,BUFFER_ASSISTANT_RUNNING);
 assert(assistant_historical_operation(&request,fingerprint,&result)==1&&result.duplicate&&result.state==BUFFER_ASSISTANT_RUNNING&&!strcmp(result.object_id,request.object_id));
 cJSON_ReplaceItemInObjectCaseSensitive(saved_operation,"state",cJSON_CreateNumber(0));assert(assistant_historical_operation(&request,fingerprint,&result)==-EBADMSG);
 store(&request,BUFFER_ASSISTANT_PAUSED);cJSON_ReplaceItemInObjectCaseSensitive(saved_operation,"object_id",cJSON_CreateString(""));assert(assistant_historical_operation(&request,fingerprint,&result)==-EBADMSG);
 lookup_error=-EAGAIN;assert(assistant_historical_operation(&request,fingerprint,&result)==-EAGAIN);lookup_error=0;
 memset(&request,0,sizeof(request));request.operation=BUFFER_ASSISTANT_RECIPE_SHOW;strcpy(request.operation_id,"old-recipe");strcpy(request.recipe_title,"旧版菜谱");strcpy(request.ingredients,"材料");request.step_count=1;strcpy(request.steps[0].title,"步骤");
 fingerprint=store(&request,0);assert(!cJSON_HasObjectItem(saved_args,"image_id"));
 /* Reproduce the deployed recipe fingerprint, before image_id existed. */
 uint64_t old=1469598103934665603ULL;old=assistant_hash_bytes(old,&request.operation,sizeof(request.operation));old=assistant_hash_string(old,request.recipe_title);old=assistant_hash_string(old,request.ingredients);old=assistant_hash_bytes(old,&request.step_count,sizeof(request.step_count));old=assistant_hash_string(old,request.steps[0].title);old=assistant_hash_string(old,request.steps[0].detail);old=assistant_hash_bytes(old,&request.steps[0].timer_ms,sizeof(request.steps[0].timer_ms));
 assert(old==fingerprint);assert(assistant_historical_operation(&request,old,&result)==1);
 strcpy(request.image_id,"abc");assert(assistant_fingerprint(&request)!=old);assert(assistant_historical_operation(&request,assistant_fingerprint(&request),&result)==-EALREADY);
 cJSON_Delete(saved_args);cJSON_Delete(saved_operation);return 0;
}
'''
    test=tmp_path/'history.c';test.write_text(harness)
    binary=tmp_path/'history'
    subprocess.run([*CC,'-std=c11','-D_DEFAULT_SOURCE','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(SDK_ROOT/'apps/netutils/cjson/cJSON'),str(test),str(SDK_ROOT/'apps/netutils/cjson/cJSON/cJSON.c'),'-lm','-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True,timeout=10)
