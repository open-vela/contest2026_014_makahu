/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_backlight.h"
#include "buffer_agent.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static pthread_mutex_t status_lock=PTHREAD_MUTEX_INITIALIZER;
static char status_text[160];
static int observed_percent,level_error=-EAGAIN,requested_percent;
static bool applying;
int buffer_backlight_level(int *percent,bool *pending)
{
 pthread_mutex_lock(&status_lock);int result=level_error;if(percent)*percent=observed_percent;if(pending)*pending=requested_percent!=0||applying;pthread_mutex_unlock(&status_lock);return result;
}
int buffer_backlight_request(int percent)
{
 if(percent<1||percent>100)return -ERANGE;
 pthread_mutex_lock(&status_lock);int result=level_error;if(!result)requested_percent=percent;pthread_mutex_unlock(&status_lock);return result;
}
static void publish_level(int percent,int result)
{pthread_mutex_lock(&status_lock);observed_percent=percent;level_error=result;pthread_mutex_unlock(&status_lock);}

static void publish(const char *text)
{pthread_mutex_lock(&status_lock);snprintf(status_text,sizeof(status_text),"%s",text);pthread_mutex_unlock(&status_lock);}
void buffer_backlight_snapshot(char *text,size_t capacity)
{if(!text||!capacity)return;pthread_mutex_lock(&status_lock);snprintf(text,capacity,"%s",status_text);pthread_mutex_unlock(&status_lock);}

#if defined(__NuttX__) && defined(CONFIG_BUFFER_VELA_NIGHT_DIM) && defined(CONFIG_LCD_MAXPOWER) && CONFIG_LCD_MAXPOWER > 1
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <nuttx/lcd/lcd_dev.h>
#define JOURNAL CONFIG_BUFFER_VELA_DATA_DIR "/backlight-restore.json"
static int device=-1,original,target;
static bool initialized,dimmed,overridden,restored_original;
static int64_t next_tick;

/* These three non-secret display values are not Agent credentials. Write a
 * new final file exclusively and fsync it before touching hardware, so this
 * path also works on the S1 FAT backend without weakening private storage. */
static int save_journal(const cJSON *saved)
{
  char *raw=cJSON_PrintUnformatted(saved);if(!raw)return -ENOMEM;
  size_t length=strlen(raw),offset=0;
  if(!length||length>1024){free(raw);return -EFBIG;}
  int flags=O_WRONLY|O_CREAT|O_EXCL;
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  int fd=open(JOURNAL,flags,0600);
  if(fd<0){int ret=-errno;free(raw);return ret;}
  int ret=0;
  while(offset<length){ssize_t n=write(fd,raw+offset,length-offset);if(n<0&&errno==EINTR)continue;
    if(n<=0){ret=n<0?-errno:-EIO;break;}offset+=(size_t)n;}
  if(!ret&&fsync(fd))ret=-errno;
  if(close(fd)&&!ret)ret=-errno;
  free(raw);if(ret)unlink(JOURNAL);return ret;
}
static cJSON *load_journal(void)
{
  int flags=O_RDONLY;
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  int fd=open(JOURNAL,flags);if(fd<0)return NULL;
  struct stat st;
  if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<1||st.st_size>1024){close(fd);errno=EINVAL;return NULL;}
  char raw[1025];size_t offset=0,length=(size_t)st.st_size;int ret=0;
  while(offset<length){ssize_t n=read(fd,raw+offset,length-offset);if(n<0&&errno==EINTR)continue;
    if(n<=0){ret=n<0?-errno:-EIO;break;}offset+=(size_t)n;}
  if(close(fd)&&!ret)ret=-errno;
  if(ret||memchr(raw,0,length)){errno=ret?-ret:EINVAL;return NULL;}
  raw[length]=0;return buffer_agent_parse_json(raw);
}

static int power(void)
{
  int value=-1;
  if(ioctl(device,LCDDEVIO_GETPOWER,(unsigned long)&value)<0)return -errno;
  return value>=0&&value<=CONFIG_LCD_MAXPOWER?value:-ERANGE;
}
static int set_power(int value)
{
  if(value<1||value>CONFIG_LCD_MAXPOWER)return -ERANGE; // Never turn the display off.
  if(ioctl(device,LCDDEVIO_SETPOWER,(unsigned long)value)<0)return -errno;
  int observed=power();return observed<0?observed:observed==value?0:-ENOTSUP;
}
static int clear_journal(void)
{return unlink(JOURNAL)==0||errno==ENOENT?0:-errno;}
static bool level(const cJSON *value,int *out)
{
  if(!cJSON_IsNumber(value)||value->valuedouble<1||value->valuedouble>CONFIG_LCD_MAXPOWER||
     value->valuedouble!=value->valueint)return false;
  *out=value->valueint;return true;
}
static int open_device(void)
{
  if(device>=0)return 0;
  device=open(CONFIG_BUFFER_VELA_LCD_DEVICE,O_RDWR);
  return device<0?-errno:0;
}
static int restore(void)
{
  int value=power();if(value<0)return value;
  // An external/manual change owns the display. Do not overwrite it on exit.
  bool external=value!=target&&value!=original;
  int ret=value==target?set_power(original):0;
  if(ret)return ret;
  ret=clear_journal();if(!ret){dimmed=false;restored_original=!external;if(external)overridden=true;}
  return ret;
}
static int recover(bool night)
{
  errno=0;cJSON *saved=load_journal();
  if(!saved)return errno==ENOENT?0:-EIO;
  const cJSON *path=cJSON_GetObjectItemCaseSensitive(saved,"device");
  bool valid=cJSON_GetArraySize(saved)==3&&cJSON_IsString(path)&&
      !strcmp(path->valuestring,CONFIG_BUFFER_VELA_LCD_DEVICE)&&
      level(cJSON_GetObjectItemCaseSensitive(saved,"original"),&original)&&
      level(cJSON_GetObjectItemCaseSensitive(saved,"target"),&target)&&target<original;
  cJSON_Delete(saved);if(!valid)return -EINVAL;
  dimmed=true;int ret=open_device();if(ret)return ret;
  if(night&&power()==target){publish("继续使用已保存的睡前亮度，退出后恢复");return 0;}
  ret=restore();
  if(!ret&&overridden)publish("亮度已由外部修改，保留当前值");
  return ret;
}

void buffer_backlight_tick(void)
{
  struct timespec stamp;
  if(clock_gettime(CLOCK_MONOTONIC,&stamp)){publish("亮度调度时钟不可用，保持原状");return;}
  int64_t now=(int64_t)stamp.tv_sec*1000+stamp.tv_nsec/1000000;
  pthread_mutex_lock(&status_lock);bool manual_pending=requested_percent!=0;pthread_mutex_unlock(&status_lock);
  if(now<next_tick&&!manual_pending)return;
  next_tick=now+1000;
  pthread_mutex_lock(&g_buffer.lock);bool night=!strcmp(g_buffer.mode,"bedtime");pthread_mutex_unlock(&g_buffer.lock);
  if(!initialized)
    {
      int ret=recover(night);
      if(ret){publish("背光恢复信息不可用，将重试；未调整亮度");return;}
      initialized=true;
    }
  /* Probe in every mode, so the manual control reports actual capability.
   * All open/ioctl/journal work stays on this service thread. */
  int probe=open_device();int current=probe?probe:power();
  publish_level(current<0?0:(current*100+CONFIG_LCD_MAXPOWER/2)/CONFIG_LCD_MAXPOWER,current<0?current:0);
  pthread_mutex_lock(&status_lock);int manual=requested_percent;requested_percent=0;applying=manual!=0;pthread_mutex_unlock(&status_lock);
  if(manual){
    int desired=(manual*CONFIG_LCD_MAXPOWER+50)/100;if(desired<1)desired=1;
    int result=current<0?current:set_power(desired);
    if(!result){
      bool had_journal=dimmed;dimmed=false;overridden=night;
      int cleanup=had_journal?clear_journal():0;
      publish_level((desired*100+CONFIG_LCD_MAXPOWER/2)/CONFIG_LCD_MAXPOWER,0);
      publish(cleanup?"亮度已调整，恢复记录待清理":"已应用手动亮度");
    }else{publish("亮度调整失败，保留当前亮度");publish_level(current<0?0:(current*100/CONFIG_LCD_MAXPOWER),result);}
    pthread_mutex_lock(&status_lock);applying=false;pthread_mutex_unlock(&status_lock);
    return;
  }
  if(!night)
    {
      overridden=false;
      if(dimmed)
        {
          if(restore()){publish("亮度恢复失败，将重试");return;}
          publish(restored_original?"已恢复进入睡前模式前的亮度":"亮度已由外部修改，保留当前值");
        }
      return;
    }
  if(overridden)return;
  if(open_device()){publish("可调背光设备不可用；未调整亮度");return;}
  int value=power();
  if(value<0){publish(dimmed?"背光状态读取失败，保留恢复记录":"驱动未提供可读亮度级别；未调整亮度");return;}
  if(dimmed)
    {
      if(value!=target)
        {
          if(clear_journal()){publish("亮度已由外部修改，恢复记录清理失败");return;}
          dimmed=false;overridden=true;publish("保留你手动调整的亮度");
        }
      return;
    }
  if(value==0){publish("屏幕当前已关闭，保持原状");return;}
  original=value;target=original*CONFIG_BUFFER_VELA_NIGHT_PERCENT/100;if(target<1)target=1;
  if(target>=original){overridden=true;publish("当前亮度已低，保持原状");return;}
  cJSON *saved=cJSON_CreateObject();if(!saved){publish("亮度恢复信息保存失败；未调整亮度");return;}
  bool built=cJSON_AddStringToObject(saved,"device",CONFIG_BUFFER_VELA_LCD_DEVICE)&&
      cJSON_AddNumberToObject(saved,"original",original)&&cJSON_AddNumberToObject(saved,"target",target);
  int ret=built?save_journal(saved):-ENOMEM;cJSON_Delete(saved);
  if(ret){initialized=false;publish("亮度恢复信息保存失败；未调整亮度");return;}
  // Recheck after the durable write in case a user changed brightness meanwhile.
  if(power()!=original)
    {
      if(clear_journal()){publish("亮度已变化，恢复记录清理失败");return;}
      overridden=true;publish("保留你手动调整的亮度");return;
    }
  ret=set_power(target);
  if(!ret){dimmed=true;publish("睡前亮度已降低，退出后恢复");return;}
  // A driver may change hardware despite returning an error. Preserve the
  // journal and restore ownership until a read establishes the actual state.
  int observed=power();
  if(observed==original)
    {
      if(clear_journal()){dimmed=true;publish("调光未完成，恢复记录待清理");return;}
      overridden=true;publish("驱动不支持所需调光级别，保留原亮度");return;
    }
  dimmed=true;publish("背光设置未确认，退出时将核对恢复");
}
void buffer_backlight_shutdown(void)
{
  if(dimmed&&device>=0&&restore())publish("退出时亮度恢复失败，下次启动继续恢复");
  if(device>=0)close(device);
  device=-1;initialized=false;overridden=false;next_tick=0;
}
#else
void buffer_backlight_tick(void)
{
  pthread_mutex_lock(&g_buffer.lock);bool night=!strcmp(g_buffer.mode,"bedtime");pthread_mutex_unlock(&g_buffer.lock);
  publish_level(0,-ENOTSUP);
  publish(night?"此设备未提供可调背光，未改变屏幕电源":"");
}
void buffer_backlight_shutdown(void) {publish("");}
#endif
