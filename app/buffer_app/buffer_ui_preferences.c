/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>
#include "buffer_ui_preferences.h"
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
#define PREF_FILE CONFIG_BUFFER_VELA_DATA_DIR "/ui.preferences"
#define PREF_TEMP CONFIG_BUFFER_VELA_DATA_DIR "/ui.preferences.tmp"
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake=PTHREAD_COND_INITIALIZER;
static pthread_t worker;
static struct buffer_ui_preferences state;
static unsigned touched;
static bool running,stopping,retry;
static bool scale_valid(unsigned scale){return scale>=85&&scale<=200;}
static uint32_t checksum(const unsigned char bytes[8])
{
 uint32_t crc=~0u;
 for(unsigned i=0;i<8;i++){crc^=bytes[i];for(unsigned bit=0;bit<8;bit++)crc=(crc>>1)^(0xedb88320u&-(crc&1));}
 return ~crc;
}
static int prepare(void)
{
 int result=buffer_private_storage_prepare();if(result)return result;
 if(mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700)&&errno!=EEXIST)return -errno;
 struct stat info;
 if(lstat(CONFIG_BUFFER_VELA_DATA_DIR,&info))return -errno;
 if(!S_ISDIR(info.st_mode))return -EACCES;
 if((info.st_mode&0777)!=0700&&chmod(CONFIG_BUFFER_VELA_DATA_DIR,0700))return -errno;
 return 0;
}
static int read_settings(struct buffer_ui_preferences *out)
{
 int result=prepare();if(result)return result;
 struct stat path_info;if(lstat(PREF_FILE,&path_info))return -errno;
 if(!S_ISREG(path_info.st_mode)||(path_info.st_mode&0777)!=0600)return -EACCES;
 int fd=open(PREF_FILE,O_RDONLY|O_NOFOLLOW);if(fd<0)return -errno;
 struct stat info;unsigned char bytes[13];size_t used=0;
 if(fstat(fd,&info))result=-errno;
 else if(!S_ISREG(info.st_mode)||(info.st_mode&0777)!=0600)result=-EACCES;
 while(!result&&used<sizeof(bytes)){
  ssize_t count=read(fd,bytes+used,sizeof(bytes)-used);
  if(count<0){if(errno==EINTR)continue;result=-errno;break;}
  if(!count)break;
  used+=(size_t)count;
 }
 if(close(fd)&&!result)result=-errno;
 if(result)return result;
 if(used!=12)return -EBADMSG;
 if(memcmp(bytes,"BUI1",4))return -ENOTSUP;
 unsigned scale=bytes[4]|((unsigned)bytes[5]<<8);uint32_t stored=0;
 for(unsigned i=0;i<4;i++)stored|=(uint32_t)bytes[8+i]<<(i*8);
 if(!scale_valid(scale)||(bytes[6]&~3u)||bytes[7]||stored!=checksum(bytes))return -EBADMSG;
 out->font_scale=scale;out->private_previews=(bytes[6]&1)!=0;out->reduced_motion=(bytes[6]&2)!=0;return 0;
}
static int write_settings(const struct buffer_ui_preferences *value)
{
 int result=prepare();if(result)return result;
 unsigned char bytes[12]={0};memcpy(bytes,"BUI1",4);bytes[4]=value->font_scale&255;bytes[5]=(value->font_scale>>8)&255;
 bytes[6]=(value->private_previews?1:0)|(value->reduced_motion?2:0);uint32_t crc=checksum(bytes);
 for(unsigned i=0;i<4;i++)bytes[8+i]=(crc>>(8*i))&255;
 /* The temp name belongs only to this writer; a crash cannot make us
  * truncate the last committed settings or follow a stale temp symlink. */
 if(unlink(PREF_TEMP)&&errno!=ENOENT)return -errno;
 int fd=open(PREF_TEMP,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)return -errno;
 if(fchmod(fd,0600))result=-errno;
 size_t done=0;
 while(!result&&done<sizeof(bytes)){
  ssize_t count=write(fd,bytes+done,sizeof(bytes)-done);
  if(count<0){if(errno==EINTR)continue;result=-errno;break;}
  if(!count){result=-EIO;break;}done+=(size_t)count;
 }
 if(!result&&fsync(fd))result=-errno;
 if(close(fd)&&!result)result=-errno;
 if(!result&&rename(PREF_TEMP,PREF_FILE))result=-errno;
 if(result){unlink(PREF_TEMP);return result;}
 int directory=open(CONFIG_BUFFER_VELA_DATA_DIR,O_RDONLY|O_DIRECTORY);
 if(directory<0)return -errno;
 if(fsync(directory)&&errno!=EINVAL&&errno!=ENOSYS)result=-errno;
 if(close(directory)&&!result)result=-errno;
 return result;
}
static void *run(void *unused)
{
 (void)unused;bool load=true,allowed=false;uint64_t failed=0;
 pthread_mutex_lock(&lock);
 for(;;){
  if(load){
   struct buffer_ui_preferences disk={0};pthread_mutex_unlock(&lock);int result=read_settings(&disk);pthread_mutex_lock(&lock);
   allowed=result==0||result==-ENOENT;state.loaded=true;state.error=allowed?0:result;
   if(!result){
    bool changed=false;
    if(!(touched&BUFFER_UI_PREF_FONT)&&state.font_scale!=disk.font_scale){state.font_scale=disk.font_scale;changed=true;}
    if(!(touched&BUFFER_UI_PREF_PRIVATE)&&state.private_previews!=disk.private_previews){state.private_previews=disk.private_previews;changed=true;}
    if(!(touched&BUFFER_UI_PREF_REDUCED)&&state.reduced_motion!=disk.reduced_motion){state.reduced_motion=disk.reduced_motion;changed=true;}
    if(changed)state.revision++;
   }
   if(allowed&&!state.pending)state.persisted_revision=state.revision;
   load=false;
  }
  while(!stopping&&!retry&&(!allowed||!state.pending||failed==state.revision))pthread_cond_wait(&wake,&lock);
  if(retry){retry=false;load=true;failed=0;continue;}
  if(stopping&&(!allowed||!state.pending||failed==state.revision))break;
  struct buffer_ui_preferences saving=state;state.pending=false;state.saving=true;
  pthread_mutex_unlock(&lock);int result=write_settings(&saving);pthread_mutex_lock(&lock);
  state.saving=false;state.error=result;
  if(result){failed=saving.revision;state.pending=true;}
  else{state.persisted_revision=saving.revision;failed=0;}
  if(state.revision!=saving.revision)state.pending=true;
 }
 pthread_mutex_unlock(&lock);return NULL;
}
int buffer_ui_preferences_start(unsigned default_font_scale)
{
 if(!scale_valid(default_font_scale))return -EINVAL;
 pthread_mutex_lock(&lock);if(running){pthread_mutex_unlock(&lock);return 0;}
 state=(struct buffer_ui_preferences){.font_scale=default_font_scale,.revision=1};touched=0;stopping=retry=false;
 pthread_attr_t attr;int result=pthread_attr_init(&attr);
 if(!result){result=pthread_attr_setstacksize(&attr,32768);if(!result)result=pthread_create(&worker,&attr,run,NULL);pthread_attr_destroy(&attr);}
 running=!result;if(result){state.loaded=true;state.error=-result;}
 pthread_mutex_unlock(&lock);return result?-result:0;
}
int buffer_ui_preferences_set(enum buffer_ui_preference field,unsigned value)
{
 if((field!=BUFFER_UI_PREF_FONT&&field!=BUFFER_UI_PREF_PRIVATE&&field!=BUFFER_UI_PREF_REDUCED)||
    (field==BUFFER_UI_PREF_FONT?!scale_valid(value):value>1))return -EINVAL;
 pthread_mutex_lock(&lock);
 if(!running||stopping){pthread_mutex_unlock(&lock);return -ESHUTDOWN;}
 if(state.revision==UINT64_MAX){pthread_mutex_unlock(&lock);return -EOVERFLOW;}
 bool same=field==BUFFER_UI_PREF_FONT?state.font_scale==value:field==BUFFER_UI_PREF_PRIVATE?state.private_previews==(value!=0):state.reduced_motion==(value!=0);
 if(state.loaded&&same&&!state.error){pthread_mutex_unlock(&lock);return 0;}
 if(field==BUFFER_UI_PREF_FONT)state.font_scale=value;
 else if(field==BUFFER_UI_PREF_PRIVATE)state.private_previews=value!=0;
 else state.reduced_motion=value!=0;
 touched|=field;state.revision++;state.pending=true;if(state.error)retry=true;
 pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);return 0;
}
int buffer_ui_preferences_snapshot(struct buffer_ui_preferences *out)
{
 if(!out)return -EINVAL;
 if(pthread_mutex_trylock(&lock))return -EAGAIN;
 *out=state;pthread_mutex_unlock(&lock);return 0;
}
void buffer_ui_preferences_retry(void)
{pthread_mutex_lock(&lock);if(running&&!stopping){retry=true;pthread_cond_signal(&wake);}pthread_mutex_unlock(&lock);}
void buffer_ui_preferences_shutdown(void)
{
 pthread_mutex_lock(&lock);bool join=running;stopping=true;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);
 if(join)pthread_join(worker,NULL);
 pthread_mutex_lock(&lock);running=false;pthread_mutex_unlock(&lock);
}
