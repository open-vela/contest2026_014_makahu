/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>
#include "buffer_ui_recipe_state.h"
#include "buffer_private_storage.h"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef CONFIG_BUFFER_VELA_DATA_DIR
#define CONFIG_BUFFER_VELA_DATA_DIR "/data/buffer"
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif
#ifndef O_DIRECTORY
#define O_DIRECTORY 0
#endif
#define STATE_FILE CONFIG_BUFFER_VELA_DATA_DIR "/ui.recipes"
#define STATE_TEMP CONFIG_BUFFER_VELA_DATA_DIR "/ui.recipes.tmp"
#define ENTRY_BYTES 213u
#define LEGACY_ENTRY_BYTES 85u
#define LEGACY_WIRE_BYTES (8u+BUFFER_RECIPE_SAVED*LEGACY_ENTRY_BYTES+4u)
#define WIRE_BYTES (8u+BUFFER_RECIPE_SAVED*ENTRY_BYTES+4u)
struct state_entry {bool used;struct buffer_ui_recipe_progress value;};
static struct state_entry entries[BUFFER_RECIPE_SAVED];
static struct buffer_ui_recipe_store_status status;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake=PTHREAD_COND_INITIALIZER;
static pthread_t thread;
static bool running,stopping,retry;
static uint64_t revision,saved_revision;
static bool optional_id(const char id[64])
{
 if(!memchr(id,0,64))return false;
 for(const char *p=id;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='-'||*p=='_'))return false;
 return true;
}
static bool valid(const struct buffer_ui_recipe_progress *p)
{
 if(!p||!optional_id(p->operation_id)||!optional_id(p->capture_id)||!memchr(p->surface,0,sizeof(p->surface))||!p->surface[0]||!memchr(p->fingerprint,0,sizeof(p->fingerprint))||strlen(p->fingerprint)!=16||!p->count||p->count>8||p->current>=p->count||p->done>=(1u<<p->count))return false;
 for(unsigned i=0;i<16;i++)if(!((p->fingerprint[i]>='0'&&p->fingerprint[i]<='9')||(p->fingerprint[i]>='a'&&p->fingerprint[i]<='f')))return false;
 return true;
}
static uint32_t crc32(const unsigned char *bytes,size_t length)
{uint32_t crc=~0u;for(size_t i=0;i<length;i++){crc^=bytes[i];for(unsigned bit=0;bit<8;bit++)crc=(crc>>1)^(0xedb88320u&-(crc&1));}return ~crc;}
static int prepare(void)
{
 int result=buffer_private_storage_prepare();if(result)return result;
 if(mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700)&&errno!=EEXIST)return -errno;
 struct stat info;if(lstat(CONFIG_BUFFER_VELA_DATA_DIR,&info))return -errno;
 if(!S_ISDIR(info.st_mode))return -EACCES;
 if((info.st_mode&0777)!=0700&&chmod(CONFIG_BUFFER_VELA_DATA_DIR,0700))return -errno;
 return 0;
}
static int load(struct state_entry out[BUFFER_RECIPE_SAVED])
{
 int result=prepare();if(result)return result;
 struct stat info;if(lstat(STATE_FILE,&info))return -errno;
 if(!S_ISREG(info.st_mode)||(info.st_mode&0777)!=0600)return -EACCES;
 int fd=open(STATE_FILE,O_RDONLY|O_NOFOLLOW);if(fd<0)return -errno;
 unsigned char bytes[WIRE_BYTES+1];size_t used=0;
 if(fstat(fd,&info))result=-errno;else if(!S_ISREG(info.st_mode)||(info.st_mode&0777)!=0600)result=-EACCES;
 while(!result&&used<sizeof(bytes)){
  ssize_t n=read(fd,bytes+used,sizeof(bytes)-used);if(n<0){if(errno==EINTR)continue;result=-errno;break;}if(!n)break;used+=(size_t)n;
 }
 if(close(fd)&&!result)result=-errno;
 if(result)return result;
 bool legacy=used==LEGACY_WIRE_BYTES&&!memcmp(bytes,"BRP1\0\0\0\0",8);
 if(!legacy&&(used!=WIRE_BYTES||memcmp(bytes,"BRP2\0\0\0\0",8)))return -EBADMSG;
 size_t record_bytes=legacy?LEGACY_ENTRY_BYTES:ENTRY_BYTES;

 uint32_t expected=0;for(unsigned i=0;i<4;i++)expected|=(uint32_t)bytes[used-4+i]<<(8*i);
 if(expected!=crc32(bytes,used-4))return -EBADMSG;
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++){
  const unsigned char *record=bytes+8+i*record_bytes;
  if(!record[0]){for(unsigned k=0;k<record_bytes;k++)if(record[k])return -EBADMSG;continue;}
  struct buffer_ui_recipe_progress *p=&out[i].value;
  if(!memchr(record,0,64)||record[84]&~3u)return -EBADMSG;
  memcpy(p->surface,record,64);memcpy(p->fingerprint,record+64,16);p->fingerprint[16]=0;
  p->count=record[80];p->current=record[81];p->done=record[82];
  if(record[83])return -EBADMSG;
  p->started=(record[84]&1)!=0;p->overview=(record[84]&2)!=0;
  if(!legacy){memcpy(p->operation_id,record+85,64);memcpy(p->capture_id,record+149,64);}
  if(!valid(p))return -EBADMSG;
  for(unsigned j=0;j<i;j++)if(out[j].used&&!strcmp(out[j].value.surface,p->surface))return -EBADMSG;
  out[i].used=true;
 }
 return 0;
}
static int save(const struct state_entry snapshot[BUFFER_RECIPE_SAVED])
{
 int result=prepare();if(result)return result;
 unsigned char bytes[WIRE_BYTES]={0};memcpy(bytes,"BRP2",4);
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(snapshot[i].used){
  const struct buffer_ui_recipe_progress *p=&snapshot[i].value;unsigned char *record=bytes+8+i*ENTRY_BYTES;
  memcpy(record,p->surface,strlen(p->surface));memcpy(record+64,p->fingerprint,16);
  memcpy(record+85,p->operation_id,strlen(p->operation_id));memcpy(record+149,p->capture_id,strlen(p->capture_id));
  record[80]=p->count;record[81]=p->current;record[82]=p->done;record[84]=(p->started?1:0)|(p->overview?2:0);
 }
 uint32_t crc=crc32(bytes,WIRE_BYTES-4);for(unsigned i=0;i<4;i++)bytes[WIRE_BYTES-4+i]=(crc>>(i*8))&255;
 if(unlink(STATE_TEMP)&&errno!=ENOENT)return -errno;
 int fd=open(STATE_TEMP,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)return -errno;
 if(fchmod(fd,0600))result=-errno;
 size_t used=0;while(!result&&used<sizeof(bytes)){
  ssize_t n=write(fd,bytes+used,sizeof(bytes)-used);if(n<0){if(errno==EINTR)continue;result=-errno;break;}if(!n){result=-EIO;break;}used+=(size_t)n;
 }
 if(!result&&fsync(fd))result=-errno;
 if(close(fd)&&!result)result=-errno;
 if(!result&&rename(STATE_TEMP,STATE_FILE))result=-errno;
 if(result){unlink(STATE_TEMP);return result;}
 fd=open(CONFIG_BUFFER_VELA_DATA_DIR,O_RDONLY|O_DIRECTORY);if(fd<0)return -errno;
 if(fsync(fd)&&errno!=EINVAL&&errno!=ENOSYS)result=-errno;
 if(close(fd)&&!result)result=-errno;
 return result;
}
static bool same(const struct buffer_ui_recipe_progress *a,const struct buffer_ui_recipe_progress *b)
{return !strcmp(a->operation_id,b->operation_id)&&!strcmp(a->capture_id,b->capture_id)&&!strcmp(a->surface,b->surface)&&!strcmp(a->fingerprint,b->fingerprint)&&a->count==b->count&&a->current==b->current&&a->done==b->done&&a->started==b->started&&a->overview==b->overview;}
static void *run(void *unused)
{
 (void)unused;struct state_entry disk[BUFFER_RECIPE_SAVED]={0};int result=load(disk);
 pthread_mutex_lock(&lock);bool allowed=!result||result==-ENOENT;status.loaded=true;status.error=allowed?0:result;
 if(!result)for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(disk[i].used){
  bool found=false;for(unsigned j=0;j<BUFFER_RECIPE_SAVED;j++)if(entries[j].used&&!strcmp(entries[j].value.surface,disk[i].value.surface))found=true;
  if(!found)for(unsigned j=0;j<BUFFER_RECIPE_SAVED;j++)if(!entries[j].used){entries[j]=disk[i];break;}
 }
 uint64_t failed=0;
 for(;;){
  while(!stopping&&!retry&&(!allowed||revision==saved_revision||revision==failed))pthread_cond_wait(&wake,&lock);
  if(retry){retry=false;allowed=true;failed=0;}
  if(stopping&&(!allowed||revision==saved_revision||revision==failed))break;
  if(!allowed||revision==saved_revision||revision==failed)continue;
  memcpy(disk,entries,sizeof(disk));uint64_t saving=revision;pthread_mutex_unlock(&lock);result=save(disk);pthread_mutex_lock(&lock);
  status.error=result;if(result)failed=saving;else{saved_revision=saving;failed=0;}
  status.pending=saved_revision!=revision;
 }
 pthread_mutex_unlock(&lock);return NULL;
}
int buffer_ui_recipe_state_start(void)
{
 pthread_mutex_lock(&lock);if(running){pthread_mutex_unlock(&lock);return 0;}
 memset(entries,0,sizeof(entries));status=(struct buffer_ui_recipe_store_status){0};stopping=retry=false;revision=saved_revision=0;
 pthread_attr_t attr;int result=pthread_attr_init(&attr);if(!result){result=pthread_attr_setstacksize(&attr,32768);if(!result)result=pthread_create(&thread,&attr,run,NULL);pthread_attr_destroy(&attr);}
 running=!result;if(result){status.loaded=true;status.error=-result;}pthread_mutex_unlock(&lock);return result?-result:0;
}
int buffer_ui_recipe_state_get(const char *surface,const char *fingerprint,struct buffer_ui_recipe_progress *out)
{
 if(!surface||!fingerprint||!out)return -EINVAL;
 if(pthread_mutex_trylock(&lock))return -EAGAIN;
 int result=!running?-ESHUTDOWN:!status.loaded?-EAGAIN:status.error?status.error:-ENOENT;
 if(running&&status.loaded&&!status.error)for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(entries[i].used&&!strcmp(entries[i].value.surface,surface)&&!strcmp(entries[i].value.fingerprint,fingerprint)){*out=entries[i].value;result=0;break;}
 pthread_mutex_unlock(&lock);return result;
}
int buffer_ui_recipe_state_slot(unsigned index,struct buffer_ui_recipe_progress *out)
{
 if(index>=BUFFER_RECIPE_SAVED||!out)return -EINVAL;
 if(pthread_mutex_trylock(&lock))return -EAGAIN;
 int result=!running?-ESHUTDOWN:!status.loaded?-EAGAIN:status.error?status.error:!entries[index].used?-ENOENT:0;
 if(!result)*out=entries[index].value;
 pthread_mutex_unlock(&lock);return result;
}
int buffer_ui_recipe_state_set(const struct buffer_ui_recipe_progress *value)
{
 if(!valid(value))return -EINVAL;
 pthread_mutex_lock(&lock);if(!running||stopping){pthread_mutex_unlock(&lock);return -ESHUTDOWN;}
 unsigned selected=BUFFER_RECIPE_SAVED;
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(entries[i].used&&!strcmp(entries[i].value.surface,value->surface)){selected=i;break;}
 if(selected==BUFFER_RECIPE_SAVED)for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(!entries[i].used){selected=i;break;}
 if(selected==BUFFER_RECIPE_SAVED)for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(!entries[i].value.started||entries[i].value.done==((1u<<entries[i].value.count)-1u)){selected=i;break;}
 if(selected==BUFFER_RECIPE_SAVED){pthread_mutex_unlock(&lock);return -ENOSPC;}
 if(entries[selected].used&&same(&entries[selected].value,value)&&!status.error){pthread_mutex_unlock(&lock);return 0;}
 entries[selected].used=true;entries[selected].value=*value;revision++;status.pending=true;
 pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);return 0;
}
int buffer_ui_recipe_state_status(struct buffer_ui_recipe_store_status *out)
{if(!out)return -EINVAL;if(pthread_mutex_trylock(&lock))return -EAGAIN;*out=status;pthread_mutex_unlock(&lock);return 0;}
void buffer_ui_recipe_state_retry(void)
{pthread_mutex_lock(&lock);if(running&&!stopping){retry=true;pthread_cond_signal(&wake);}pthread_mutex_unlock(&lock);}
void buffer_ui_recipe_state_shutdown(void)
{
 pthread_mutex_lock(&lock);bool join=running;stopping=true;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);
 if(join)pthread_join(thread,NULL);
 pthread_mutex_lock(&lock);running=false;pthread_mutex_unlock(&lock);
}
