/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>
#include "buffer_device_settings.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef CONFIG_BOARDCTL_RESET
#include <sys/boardctl.h>
#endif
#define RESET_MARKER CONFIG_BUFFER_VELA_DATA_DIR ".reset"
static int restart(void)
{
#ifdef CONFIG_BOARDCTL_RESET
 return boardctl(BOARDIOC_RESET,0)<0?-errno:0;
#else
 return -ENOTSUP;
#endif
}
static int remove_path(const char *path,dev_t device,unsigned depth)
{
 struct stat st;if(lstat(path,&st))return errno==ENOENT?0:-errno;
 if(st.st_dev!=device||depth>32)return -EXDEV;
 if(S_ISLNK(st.st_mode)||S_ISREG(st.st_mode))return unlink(path)?-errno:0;
 if(!S_ISDIR(st.st_mode))return -EPERM;
 DIR *dir=opendir(path);if(!dir)return -errno;
 int result=0;struct dirent *entry;
 for(;;){errno=0;entry=readdir(dir);if(!entry){if(errno)result=-errno;break;}
  if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
  char child[512];int n=snprintf(child,sizeof(child),"%s/%s",path,entry->d_name);
  if(n<0||(size_t)n>=sizeof(child)){result=-ENAMETOOLONG;break;}
  result=remove_path(child,device,depth+1);if(result)break;
 }
 closedir(dir);if(result)return result;return rmdir(path)?-errno:0;
}
int buffer_device_reset_apply_boot(void)
{
 int fd=open(RESET_MARKER,O_RDONLY|O_NOFOLLOW);if(fd<0)return errno==ENOENT?0:-errno;
 char data[3]={0};ssize_t n=read(fd,data,sizeof(data));struct stat marker;int checked=fstat(fd,&marker);close(fd);
 if(checked||!S_ISREG(marker.st_mode)||(marker.st_mode&0777)!=0600||n!=2||data[1]!='\n'||(data[0]!='2'&&data[0]!='3'))return -EINVAL;
 int result=0;
 if(data[0]=='3')result=remove_path(CONFIG_BUFFER_VELA_DATA_DIR,marker.st_dev,0);
 else{
  static const char *names[]={"fabric","fabric-binding.json","phone.conf","phone.conf.tmp","pairing.code","pairing.code.tmp","oobe.conf","oobe.conf.tmp","oobe.completed","oobe.completed.tmp"};
  for(unsigned i=0;i<sizeof(names)/sizeof(names[0])&&!result;i++){
   char path[512];snprintf(path,sizeof(path),"%s/%s",CONFIG_BUFFER_VELA_DATA_DIR,names[i]);result=remove_path(path,marker.st_dev,0);
  }
 }
#ifdef CONFIG_WIRELESS_WAPI_CONFIG_PATH
 if(!result)result=remove_path(CONFIG_WIRELESS_WAPI_CONFIG_PATH,marker.st_dev,0);
 if(!result)result=remove_path(CONFIG_WIRELESS_WAPI_CONFIG_PATH ".buffer-part",marker.st_dev,0);
#endif
 if(result)return result;
 sync();if(unlink(RESET_MARKER))return -errno;sync();return 0;
}
int buffer_device_reset_request(enum buffer_device_reset kind)
{
 if(kind<BUFFER_RESET_RESTART||kind>BUFFER_RESET_FACTORY)return -EINVAL;
#ifndef CONFIG_BOARDCTL_RESET
 return -ENOTSUP;
#endif
 if(kind!=BUFFER_RESET_RESTART){
  int fd=open(RESET_MARKER,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW,0600);if(fd<0)return -errno;
  char data[2]={(char)('0'+kind),'\n'};int result=write(fd,data,2)==2?0:-EIO;
  if(!result&&fsync(fd))result=-errno;
  if(close(fd)&&!result)result=-errno;
  if(result){unlink(RESET_MARKER);return result;}
 }
 sync();int result=restart();
 /* A failed reboot must not leave a surprise reset for a later boot. */
 if(kind!=BUFFER_RESET_RESTART){unlink(RESET_MARKER);sync();}
 return result?result:-EIO;
}
