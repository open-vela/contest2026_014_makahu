/* Production preferences and real private files across process restarts.
 * Linker write wrapping injects blocking/failure only at the I/O boundary. */
#include "buffer_ui_preferences.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <semaphore.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static sem_t entered,released;
static int hold_read,hold_write,fail_write;
int buffer_private_storage_prepare(void)
{if(__atomic_exchange_n(&hold_read,0,__ATOMIC_ACQ_REL)){sem_post(&entered);sem_wait(&released);}return 0;}
ssize_t __real_write(int fd,const void *data,size_t bytes);
ssize_t __wrap_write(int fd,const void *data,size_t bytes)
{
 if(__atomic_exchange_n(&hold_write,0,__ATOMIC_ACQ_REL)){sem_post(&entered);sem_wait(&released);}
 if(__atomic_load_n(&fail_write,__ATOMIC_ACQUIRE)){errno=ENOSPC;return -1;}
 return __real_write(fd,data,bytes);
}
static struct buffer_ui_preferences wait_state(bool error)
{
 struct buffer_ui_preferences value;
 for(unsigned i=0;i<3000;i++){
  if(!buffer_ui_preferences_snapshot(&value)&&value.loaded&&!value.saving&&
     (error?value.error!=0:!value.error&&!value.pending&&value.persisted_revision==value.revision))return value;
  usleep(1000);
 }
 assert(!"preferences worker did not finish");return value;
}
static void read_bytes(unsigned char bytes[12])
{int fd=open(CONFIG_BUFFER_VELA_DATA_DIR "/ui.preferences",O_RDONLY);assert(fd>=0);assert(read(fd,bytes,12)==12);assert(!close(fd));}
int main(int argc,char **argv)
{
 assert(argc>=2);sem_init(&entered,0,0);sem_init(&released,0,0);
 if(!strcmp(argv[1],"race"))hold_read=1;
 assert(!buffer_ui_preferences_start(100));
 struct buffer_ui_preferences value;
 if(!strcmp(argv[1],"race")){
  sem_wait(&entered);assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,200));assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_PRIVATE,0));
  assert(!buffer_ui_preferences_snapshot(&value)&&!value.loaded&&value.font_scale==200&&!value.private_previews);
  sem_post(&released);value=wait_state(false);assert(value.font_scale==200&&!value.private_previews&&value.reduced_motion);
 }else if(!strcmp(argv[1],"error")){
  value=wait_state(true);assert(value.error<0);assert(!value.private_previews);assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,125));
 }else{
  value=wait_state(false);
  if(!strcmp(argv[1],"save")){
   assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,150));assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_PRIVATE,1));assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_REDUCED,1));
   value=wait_state(false);assert(value.font_scale==150&&value.private_previews&&value.reduced_motion);
  }else if(!strcmp(argv[1],"load")){
   assert(argc==5&&value.font_scale==(unsigned)atoi(argv[2])&&value.private_previews==(atoi(argv[3])!=0)&&value.reduced_motion==(atoi(argv[4])!=0));
  }else if(!strcmp(argv[1],"write-race")){
   __atomic_store_n(&hold_write,1,__ATOMIC_RELEASE);assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,125));sem_wait(&entered);
   assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,175));assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_PRIVATE,1));assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_REDUCED,0));
   assert(!buffer_ui_preferences_snapshot(&value)&&value.saving&&value.pending&&value.font_scale==175);
   sem_post(&released);value=wait_state(false);assert(value.font_scale==175&&value.private_previews&&!value.reduced_motion);
  }else if(!strcmp(argv[1],"fail")){
   unsigned char before[12],after[12];read_bytes(before);__atomic_store_n(&fail_write,1,__ATOMIC_RELEASE);
   assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_FONT,200));value=wait_state(true);
   assert(value.pending&&value.font_scale==200&&value.persisted_revision<value.revision);read_bytes(after);assert(!memcmp(before,after,12));
   __atomic_store_n(&fail_write,0,__ATOMIC_RELEASE);buffer_ui_preferences_retry();value=wait_state(false);assert(value.font_scale==200);
  }else assert(!"unknown fixture mode");
 }
 buffer_ui_preferences_shutdown();sem_destroy(&entered);sem_destroy(&released);
 printf("PASS: preferences %s\n",argv[1]);return 0;
}
