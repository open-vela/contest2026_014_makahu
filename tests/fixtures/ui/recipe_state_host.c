/* Actual asynchronous recipe progress worker and private atomic file. */
#include <assert.h>
#include <errno.h>
#include <semaphore.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>
#include "buffer_ui_recipe_state.h"
static bool block_load,fail_write;
static sem_t entered,released;
int buffer_private_storage_prepare(void)
{if(block_load){sem_post(&entered);sem_wait(&released);}return 0;}
ssize_t __real_write(int fd,const void *bytes,size_t length);
ssize_t __wrap_write(int fd,const void *bytes,size_t length)
{if(fail_write){errno=ENOSPC;return -1;}return __real_write(fd,bytes,length);}
static struct buffer_ui_recipe_progress value={.surface="recipe-one",.fingerprint="0123456789abcdef",.operation_id="operation-source",.capture_id="capture-source",.count=3,.current=1,.done=1,.started=true};
static struct buffer_ui_recipe_store_status wait_ready(void)
{
 struct buffer_ui_recipe_store_status status={0};
 for(unsigned n=0;n<3000;n++){if(!buffer_ui_recipe_state_status(&status)&&status.loaded&&(!status.pending||status.error))return status;usleep(1000);}
 assert(!"worker did not settle");return status;
}
int main(int argc,char **argv)
{
 assert(argc==2);sem_init(&entered,0,0);sem_init(&released,0,0);
 if(!strcmp(argv[1],"race"))block_load=true;
 assert(!buffer_ui_recipe_state_start());
 if(block_load){sem_wait(&entered);value.current=2;value.done=3;assert(!buffer_ui_recipe_state_set(&value));assert(buffer_ui_recipe_state_get(value.surface,value.fingerprint,&value)==-EAGAIN);block_load=false;sem_post(&released);}
 struct buffer_ui_recipe_store_status status=wait_ready();
 if(!strcmp(argv[1],"save")||!strcmp(argv[1],"failure")){
  assert(!status.error);if(!strcmp(argv[1],"failure")){fail_write=true;value.current=2;value.done=7;}
  assert(!buffer_ui_recipe_state_set(&value));status=wait_ready();
  if(fail_write){assert(status.error==-ENOSPC&&status.pending);fail_write=false;buffer_ui_recipe_state_retry();
   for(unsigned n=0;n<3000;n++){usleep(1000);if(!buffer_ui_recipe_state_status(&status)&&!status.pending&&!status.error)break;}}
  assert(!status.error&&!status.pending);
 }else if(!strcmp(argv[1],"load")||!strcmp(argv[1],"race")||!strcmp(argv[1],"recovered")){
  assert(!status.error);struct buffer_ui_recipe_progress saved;assert(!buffer_ui_recipe_state_get(value.surface,value.fingerprint,&saved));
  unsigned expected=!strcmp(argv[1],"load")?1:2;assert(saved.current==expected&&saved.started&&saved.count==3);
  assert(buffer_ui_recipe_state_get(value.surface,"aaaaaaaaaaaaaaaa",&saved)==-ENOENT);
 }else if(!strcmp(argv[1],"metadata")){
  assert(!status.error);struct buffer_ui_recipe_progress saved;assert(!buffer_ui_recipe_state_slot(0,&saved));assert(!strcmp(saved.operation_id,"operation-source")&&!strcmp(saved.capture_id,"capture-source"));
 }else if(!strcmp(argv[1],"invalid")){assert(status.error&& !status.pending);}
 else if(!strcmp(argv[1],"limits")){
  assert(!status.error);for(unsigned i=0;i<3;i++){snprintf(value.surface,sizeof(value.surface),"active-%u",i);assert(!buffer_ui_recipe_state_set(&value));}
  strcpy(value.surface,"overflow");assert(buffer_ui_recipe_state_set(&value)==-ENOSPC);
  value.count=0;assert(buffer_ui_recipe_state_set(&value)==-EINVAL);
 }
 buffer_ui_recipe_state_shutdown();sem_destroy(&entered);sem_destroy(&released);return 0;
}
