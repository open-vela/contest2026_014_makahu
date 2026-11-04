/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_remote.h"
#include "buffer_source_events.h"
#include "buffer_source_envelope.h"
#include "buffer_source_index.h"
#include "buffer_source_visibility.h"
#include "buffer_source_view.h"
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define ROOT CONFIG_BUFFER_VELA_DATA_DIR "/event-journal"
#define REMOTES ROOT "/remote"
#define IDS ROOT "/remote-ids"
#define OWNERS ROOT "/remote-owners"
#define STATE ROOT "/remote-state.json"
#define INTENT ROOT "/remote-pending.json"
#define STAGING ROOT "/remote-envelope.pending"
#define PROJECTION_CURSOR ROOT "/remote-projection-cursor.json"
#define MAX_COUNTER 9007199254740991ULL
static pthread_mutex_t remote_lock=PTHREAD_MUTEX_INITIALIZER;
static char visibility_local[97];
static int find_id(const char *event_id,cJSON **found);
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j,const char *key,uint64_t *out)
{
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);
  if(!cJSON_IsNumber(v)||!isfinite(v->valuedouble)||v->valuedouble<0||v->valuedouble>MAX_COUNTER||floor(v->valuedouble)!=v->valuedouble)return false;
  *out=(uint64_t)v->valuedouble;return true;
}
static bool hex(const char *s)
{if(strlen(s)!=64)return false;for(unsigned i=0;i<64;i++)if(!strchr("0123456789abcdef",s[i]))return false;return true;}
static bool id(const char *s,unsigned max,bool colon)
{
  size_t n=strlen(s);if(!n||n>max)return false;
  for (size_t i = 0; i < n; i++)
    if (!((s[i] >= 'A' && s[i] <= 'Z') || (s[i] >= 'a' && s[i] <= 'z') ||
          (s[i] >= '0' && s[i] <= '9') || strchr("_.-", s[i]) ||
          (colon && s[i] == ':')))
      return false;
  return true;
}
static void release(char *raw,size_t length)
{if(raw){volatile char *p=raw;while(length--)*p++=0;free(raw);}}
static int digest(const char *raw,size_t length,char out[65])
{unsigned char bytes[32];if(mbedtls_sha256((const unsigned char *)raw,length,bytes,0))return -EIO;
  for (unsigned i = 0; i < 32; i++)
    snprintf(out + i * 2, 3, "%02x", bytes[i]);
  return 0;
}
static int json_read(const char *path,cJSON **out)
{errno=0;*out=buffer_agent_read_json_plain(path,4096);return *out?0:errno?-errno:-EIO;}
static int json_write(const char *path,const cJSON *j)
{char *raw=cJSON_PrintUnformatted(j);if(!raw)return -ENOMEM;size_t n=strlen(raw);int ret=buffer_agent_atomic_bytes(path,raw,n,4096);release(raw,n);return ret;}
static int source_dir(char path[320],const char *source)
{char hash[65];int ret=digest(source,strlen(source),hash);if(ret)return ret;
  int n=snprintf(path,320,REMOTES "/%s",hash);return n<0||n>=320?-ENAMETOOLONG:0;}
static int source_path(char path[320],const char *source,uint64_t sequence,const char *suffix)
{char directory[320];int ret=source_dir(directory,source);if(ret)return ret;
  int n=sequence?snprintf(path,320,"%s/%020llu.%s",directory,(unsigned long long)sequence,suffix):snprintf(path,320,"%s/head.json",directory);
  return n<0||n>=320?-ENAMETOOLONG:0;}
static int owner_path(char path[320],const char *source)
{char hash[65];int ret=digest(source,strlen(source),hash);if(ret)return ret;
  int n=snprintf(path,320,OWNERS "/%s.json",hash);return n<0||n>=320?-ENAMETOOLONG:0;}
static int owner_read(const char *source,cJSON **out,uint64_t *sequence)
{
  char path[320];int ret=owner_path(path,source);if(ret)return ret;ret=json_read(path,out);if(ret)return ret;
  if(!cJSON_IsObject(*out)||cJSON_GetArraySize(*out)!=3||strcmp(text(*out,"source_id"),source)||
     !hex(text(*out,"owner_peer"))||!number(*out,"accepted_sequence",sequence)||*sequence>=MAX_COUNTER){
    cJSON_Delete(*out);*out=NULL;return -EIO;
  }
  return 0;
}
static int owner_write(const char *source,const char *peer,uint64_t sequence)
{
  char path[320];int ret=owner_path(path,source);if(ret)return ret;
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddStringToObject(j,"owner_peer",peer)&&
    cJSON_AddNumberToObject(j,"accepted_sequence",(double)sequence);
  ret=built?json_write(path,j):-ENOMEM;cJSON_Delete(j);return ret;
}
static int empty_directory(const char *path,bool head_temporary)
{
  DIR *dir=opendir(path);if(!dir)return -errno;int ret=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
    if(head_temporary&&!strcmp(entry->d_name,"head.json.tmp")){
      char temp[320];int n=snprintf(temp,sizeof(temp),"%s/head.json.tmp",path);
      if(n<0||(size_t)n>=sizeof(temp)){ret=-ENAMETOOLONG;break;}
      if(unlink(temp)){ret=-errno;break;}continue;
    }
    ret=-EIO;break;
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  return ret;
}
static int discard_staging(void)
{
  const char *paths[]={STAGING,STAGING ".tmp",INTENT ".tmp"};
  for(unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++)if(unlink(paths[i])&&errno!=ENOENT)return -errno;
  return buffer_agent_sync_parent(STAGING);
}
static int read_record(const char *source,uint64_t sequence,char **raw,size_t *length,cJSON **envelope)
{
  char path[320],hash[65],*saved=NULL;size_t bytes=0;*raw=NULL;*length=0;*envelope=NULL;
  int ret=source_path(path,source,sequence,"json");if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,raw,length);
  if(!ret)ret=buffer_source_envelope_decode(*raw,*length,envelope);
  uint64_t seq=0;
  if(!ret&&(strcmp(text(*envelope,"source_id"),source)||!number(*envelope,"source_sequence",&seq)||seq!=sequence))ret=-EIO;
  if(!ret)ret=digest(*raw,*length,hash);
  if(!ret)ret=source_path(path,source,sequence,"sha256");
  if(!ret)ret=buffer_agent_read_bytes(path,64,&saved,&bytes);
  if(!ret&&(bytes!=64||memcmp(saved,hash,64)))ret=-EIO;
  release(saved,bytes);
  if(ret){release(*raw,*length);*raw=NULL;*length=0;cJSON_Delete(*envelope);*envelope=NULL;}
  return ret==-ENOENT?-EIO:ret;
}
static int pin_empty(const char *source,const char *peer,cJSON **head);
static int load_head(const char *source,cJSON **out)
{
  char path[320];cJSON *owner=NULL;uint64_t accepted=0;
  int owner_ret=owner_read(source,&owner,&accepted);if(owner_ret&&owner_ret!=-ENOENT)return owner_ret;
  int ret=source_path(path,source,0,NULL);if(ret){cJSON_Delete(owner);return ret;}
  ret=json_read(path,out);
  if(ret==-ENOENT){
    if(owner){
      /* Only a source reservation with zero accepted events may recreate an
       * empty head. A committed stream must never restart at sequence one. */
      ret=accepted?-EIO:pin_empty(source,text(owner,"owner_peer"),out);
    }else{
      ret=source_dir(path,source);if(!ret){int empty=empty_directory(path,true);ret=empty==0||empty==-ENOENT?-ENOENT:empty;}
    }
    cJSON_Delete(owner);return ret;
  }
  if(ret){cJSON_Delete(owner);return ret;}
  uint64_t seq=0,clock=0;
  if(!cJSON_IsObject(*out)||cJSON_GetArraySize(*out)!=6||strcmp(text(*out,"source_id"),source)||
     !hex(text(*out,"owner_peer"))||!number(*out,"last_sequence",&seq)||seq>=MAX_COUNTER||!number(*out,"last_clock",&clock)||
     !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(*out,"last_event_id"))||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(*out,"last_fingerprint"))||
     (seq?(!id(text(*out,"last_event_id"),160,true)||!hex(text(*out,"last_fingerprint"))):
       (clock||text(*out,"last_event_id")[0]||text(*out,"last_fingerprint")[0]))||
     !owner||strcmp(text(owner,"owner_peer"),text(*out,"owner_peer"))||accepted<seq||accepted>seq+1)ret=-EIO;
  if(!ret&&accepted>seq){
    cJSON *pending=NULL;ret=json_read(INTENT,&pending);uint64_t pending_seq=0;
    if(!ret&&(strcmp(text(pending,"source_id"),source)||strcmp(text(pending,"peer"),text(owner,"owner_peer"))||
       !number(pending,"source_sequence",&pending_seq)||pending_seq!=accepted))ret=-EIO;
    cJSON_Delete(pending);if(ret==-ENOENT)ret=-EIO;
  }
  cJSON_Delete(owner);
  if(!ret&&seq){
    char *raw=NULL,hash[65];size_t bytes=0;cJSON *j=NULL;uint64_t saved_clock=0;
    ret=read_record(source,seq,&raw,&bytes,&j);
    if (!ret)
      ret = digest(raw, bytes, hash);
    const cJSON *event = cJSON_GetObjectItemCaseSensitive(j, "event");
    if(!ret&&(strcmp(hash,text(*out,"last_fingerprint"))||strcmp(text(event,"event_id"),text(*out,"last_event_id"))||
       !number(event,"logical_clock",&saved_clock)||saved_clock!=clock))ret=-EIO;
    release(raw,bytes);cJSON_Delete(j);
  }
  if(ret){cJSON_Delete(*out);*out=NULL;}return ret;
}
static int pin_empty(const char *source,const char *peer,cJSON **head)
{
  char path[320];int ret=source_dir(path,source);if(ret)return ret;
  ret=buffer_agent_private_directory(path);if(ret)return ret;
  ret=empty_directory(path,true);if(ret)return ret;
  cJSON *owner=NULL;uint64_t accepted=0;ret=owner_read(source,&owner,&accepted);
  if(!ret&&(strcmp(text(owner,"owner_peer"),peer)||accepted))ret=-EACCES;
  cJSON_Delete(owner);if(ret&&ret!=-ENOENT)return ret;
  ret=owner_write(source,peer,0);if(ret)return ret;
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddStringToObject(j,"owner_peer",peer)&&
    cJSON_AddNumberToObject(j,"last_sequence",0)&&cJSON_AddNumberToObject(j,"last_clock",0)&&
    cJSON_AddStringToObject(j,"last_event_id","")&&cJSON_AddStringToObject(j,"last_fingerprint","");
  if(!built)ret=-ENOMEM;else{ret=source_path(path,source,0,NULL);if(!ret)ret=json_write(path,j);}
  if(ret)cJSON_Delete(j);else *head=j;return ret;
}
static int visibility_base(const char *source,const char *peer,bool create,uint64_t *sequence)
{
  *sequence=0;cJSON *head=NULL;int ret=load_head(source,&head);
  if(ret==-ENOENT&&create)ret=pin_empty(source,peer,&head);
  if(!ret&&((peer&&strcmp(text(head,"owner_peer"),peer))||!number(head,"last_sequence",sequence)))ret=-EACCES;
  cJSON_Delete(head);return ret;
}
static int visibility_read_base(const char *source,uint64_t sequence,char **raw,size_t *bytes)
{
  *raw=NULL;*bytes=0;cJSON *head=NULL,*j=NULL;uint64_t last=0;int ret=load_head(source,&head);
  if(!ret&&!number(head,"last_sequence",&last))ret=-EIO;
  if(!ret)ret=sequence>last?-ENOENT:read_record(source,sequence,raw,bytes,&j);
  cJSON_Delete(head);cJSON_Delete(j);return ret;
}
static bool same_index(const cJSON *j,const char *source,uint64_t sequence,const char *event_id,const char *hash)
{uint64_t seq=0;return cJSON_IsObject(j)&&cJSON_GetArraySize(j)==4&&number(j,"source_sequence",&seq)&&seq==sequence&&
  !strcmp(text(j,"source_id"),source)&&!strcmp(text(j,"event_id"),event_id)&&!strcmp(text(j,"fingerprint"),hash);}
static int write_index(const char *source,uint64_t sequence,const char *event_id,const char *hash)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
    cJSON_AddStringToObject(j,"event_id",event_id)&&cJSON_AddStringToObject(j,"fingerprint",hash);
  int ret=built?buffer_source_index_put(j):-ENOMEM;cJSON_Delete(j);return ret;
}
static int observed(uint64_t *clock)
{cJSON *j=NULL;int ret=json_read(STATE,&j);uint64_t version=0;
  if(!ret&&(!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=2||!number(j,"schema_version",&version)||version!=1||!number(j,"observed_clock",clock)))ret=-EIO;
  cJSON_Delete(j);return ret;}
static int observe(uint64_t clock)
{
  uint64_t old=0;int ret=observed(&old);if(ret)return ret;if(clock<=old)return buffer_agent_sync_parent(STATE);
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"schema_version",1)&&cJSON_AddNumberToObject(j,"observed_clock",(double)clock);
  ret=built?json_write(STATE,j):-ENOMEM;cJSON_Delete(j);return ret;
}
static struct buffer_source_visibility_store_s visibility_store(const char *local)
{
  struct buffer_source_visibility_store_s store={.local_source=local,.base=visibility_base,.read_base=visibility_read_base,
    .find=find_id,.put=write_index,.observe=observe};return store;
}
static int immutable_file(const char *path,const char *raw,size_t length,size_t limit)
{
  char *old=NULL;size_t bytes=0;int ret=buffer_agent_read_bytes(path,limit,&old,&bytes);
  if(!ret){ret=bytes==length&&!memcmp(old,raw,length)?buffer_agent_sync_parent(path):-EEXIST;release(old,bytes);return ret;}
  return ret==-ENOENT?buffer_agent_atomic_bytes(path,raw,length,limit):ret;
}
static int recover(const char *local_source)
{
  cJSON *intent=NULL,*j=NULL,*head=NULL;char *raw=NULL;size_t bytes=0;
  int ret=json_read(INTENT,&intent);if(ret==-ENOENT)return discard_staging();if(ret)return ret;
  uint64_t seq=0,clock=0,last=0,previous=0;
  const char *source=text(intent,"source_id"),*event_id=text(intent,"event_id"),*hash=text(intent,"fingerprint");
  if(!cJSON_IsObject(intent)||cJSON_GetArraySize(intent)!=6||!id(source,96,false)||!strcmp(source,local_source)||!hex(text(intent,"peer"))||
     !id(event_id,160,true)||!hex(hash)||!number(intent,"source_sequence",&seq)||!seq||seq>=MAX_COUNTER||!number(intent,"logical_clock",&clock)){ret=-EIO;goto done;}
  ret=load_head(source,&head);if(ret)goto done;
  if(strcmp(text(head,"owner_peer"),text(intent,"peer"))||!number(head,"last_sequence",&last)||!number(head,"last_clock",&previous)||seq<last||seq>last+1){ret=-EIO;goto done;}
  ret=buffer_agent_read_bytes(STAGING,BUFFER_SOURCE_EVENT_BYTES,&raw,&bytes);
  if(ret==-ENOENT){char path[320];ret=source_path(path,source,seq,"json");if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&bytes);}
  if (ret)
    goto done;
  ret = buffer_source_envelope_decode(raw, bytes, &j);
  if (ret)
    goto done;
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event");uint64_t saved_seq=0,saved_clock=0;char actual[65];
  ret=digest(raw,bytes,actual);if(ret)goto done;
  if(strcmp(text(j,"source_id"),source)||!number(j,"source_sequence",&saved_seq)||saved_seq!=seq||strcmp(text(event,"event_id"),event_id)||
     !number(event,"logical_clock",&saved_clock)||saved_clock!=clock||strcmp(actual,hash)||
     (seq==last&&(strcmp(hash,text(head,"last_fingerprint"))||clock!=previous))||
     (seq>last&&!strcmp(text(j,"provenance"),"native")&&clock<=previous&&last)){ret=-EIO;goto done;}
  char path[320];ret=source_path(path,source,seq,"json");if(!ret)ret=immutable_file(path,raw,bytes,BUFFER_SOURCE_EVENT_BYTES);
  if (!ret)
    ret = source_path(path, source, seq, "sha256");
  if (!ret)
    ret = immutable_file(path, hash, 64, 64);
  if (!ret)
    ret = write_index(source, seq, event_id, hash);
  if (ret)
    goto done;
  /* Queue data never contains executable commands or local target paths.
   * Once the head is committed, retries must not reset a later view state. */
  if(seq>last){
    cJSON *queue=cJSON_CreateObject();bool built=queue&&cJSON_AddStringToObject(queue,"event_id",event_id)&&cJSON_AddStringToObject(queue,"state","pending");
    ret=built?source_path(path,source,seq,"view.json"):-ENOMEM;if(!ret)ret=json_write(path,queue);cJSON_Delete(queue);if(ret)goto done;
  }
  ret=observe(clock);if(ret)goto done;
  ret=owner_write(source,text(head,"owner_peer"),seq);if(ret)goto done;
  if(seq>last){
    bool built=cJSON_ReplaceItemInObjectCaseSensitive(head,"last_sequence",cJSON_CreateNumber((double)seq))&&
      cJSON_ReplaceItemInObjectCaseSensitive(head,"last_clock",cJSON_CreateNumber((double)clock))&&
      cJSON_ReplaceItemInObjectCaseSensitive(head,"last_event_id",cJSON_CreateString(event_id))&&
      cJSON_ReplaceItemInObjectCaseSensitive(head,"last_fingerprint",cJSON_CreateString(hash));
    ret=built?source_path(path,source,0,NULL):-ENOMEM;if(!ret)ret=json_write(path,head);if(ret)goto done;
  }
  if(unlink(INTENT)&&errno!=ENOENT){ret=-errno;goto done;}
  ret=buffer_agent_sync_parent(INTENT);if(!ret)ret=discard_staging();
done:
  cJSON_Delete(intent);cJSON_Delete(j);cJSON_Delete(head);release(raw,bytes);return ret;
}
static int owner_coverage(uint64_t *total)
{
  *total=0;
  /* Neither deleting a stream directory nor losing its owner index may make
   * committed source identities available for a fresh claim. Empty directories
   * from an interrupted initial reservation are the sole exception. */
  DIR *dir=opendir(REMOTES);if(!dir)return -errno;int ret=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
    if(!hex(entry->d_name)){ret=-EIO;break;}
    char path[320];int n=snprintf(path,sizeof(path),OWNERS "/%s.json",entry->d_name);
    if(n<0||(size_t)n>=sizeof(path)){ret=-ENAMETOOLONG;break;}
    cJSON *owner=NULL;ret=json_read(path,&owner);
    if(ret==-ENOENT){
      n=snprintf(path,sizeof(path),REMOTES "/%s",entry->d_name);
      ret=n<0||(size_t)n>=sizeof(path)?-ENAMETOOLONG:empty_directory(path,true);
    }else if(!ret){
      char expected[65];uint64_t accepted=0;const char *source=text(owner,"source_id");
      if(!id(source,96,false)||cJSON_GetArraySize(owner)!=3||!hex(text(owner,"owner_peer"))||
         !number(owner,"accepted_sequence",&accepted)||accepted>=MAX_COUNTER)ret=-EIO;
      if(!ret)ret=digest(source,strlen(source),expected);
      if(!ret&&strcmp(expected,entry->d_name))ret=-EIO;
    }
    cJSON_Delete(owner);if(ret)break;
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  if (ret)
    return ret;
  uint64_t clock=0;ret=observed(&clock);if(ret)return ret;
  dir=opendir(OWNERS);if(!dir)return -errno;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
    size_t length=strlen(entry->d_name);if(length>4&&!strcmp(entry->d_name+length-4,".tmp"))continue;
    if(length!=69||strcmp(entry->d_name+64,".json")){ret=-EIO;break;}
    char hash[65],path[320];memcpy(hash,entry->d_name,64);hash[64]=0;if(!hex(hash)){ret=-EIO;break;}
    int n=snprintf(path,sizeof(path),OWNERS "/%s",entry->d_name);if(n<0||(size_t)n>=sizeof(path)){ret=-ENAMETOOLONG;break;}
    cJSON *owner=NULL,*head=NULL;ret=json_read(path,&owner);uint64_t accepted=0;
    const char *source=text(owner,"source_id");char expected[65];
    if(!ret&&(!id(source,96,false)||cJSON_GetArraySize(owner)!=3||!hex(text(owner,"owner_peer"))||
       !number(owner,"accepted_sequence",&accepted)||accepted>=MAX_COUNTER))ret=-EIO;
    if (!ret)
      ret = digest(source, strlen(source), expected);
    if (!ret && strcmp(expected, hash))
      ret = -EIO;
    if(!ret&&accepted){
      ret=source_path(path,source,0,NULL);if(!ret)ret=json_read(path,&head);uint64_t seq=0,last_clock=0;
      if(!ret&&(strcmp(text(head,"source_id"),source)||strcmp(text(head,"owner_peer"),text(owner,"owner_peer"))||
         !number(head,"last_sequence",&seq)||seq!=accepted||!number(head,"last_clock",&last_clock)||last_clock>clock))ret=-EIO;
      if(ret==-ENOENT)ret=-EIO;
    }
    if(!ret){if(accepted>MAX_COUNTER-*total)ret=-EOVERFLOW;else *total+=accepted;}
    cJSON_Delete(owner);cJSON_Delete(head);if(ret)break;
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  return ret;
}
#define BUILD ROOT "/remote-index/build.json"
static int write_build(const char *source_hash,uint64_t sequence,uint64_t expected)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"schema_version",1)&&
    cJSON_AddStringToObject(j,"source_hash",source_hash)&&cJSON_AddNumberToObject(j,"sequence",(double)sequence)&&
    cJSON_AddNumberToObject(j,"expected_count",(double)expected);
  int ret=built?json_write(BUILD,j):-ENOMEM;cJSON_Delete(j);return ret;
}
static int next_source(const char *after,char best[65])
{
  best[0]=0;DIR *dir=opendir(OWNERS);if(!dir)return -errno;int ret=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
    size_t bytes=strlen(entry->d_name);if(bytes>4&&!strcmp(entry->d_name+bytes-4,".tmp"))continue;
    if(bytes!=69||strcmp(entry->d_name+64,".json")){ret=-EIO;break;}
    char hash[65];memcpy(hash,entry->d_name,64);hash[64]=0;if(!hex(hash)){ret=-EIO;break;}
    if(strcmp(hash,after)>0&&(!best[0]||strcmp(hash,best)<0))snprintf(best,65,"%s",hash);
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  return ret;
}
static int build_source(const char *source_hash,char source[97],uint64_t *last)
{
  char path[320],actual[65];int n=snprintf(path,sizeof(path),OWNERS "/%s.json",source_hash);
  if (n < 0 || (size_t)n >= sizeof(path))
    return -ENAMETOOLONG;
  cJSON *j = NULL;
  int ret = json_read(path, &j);
  const char *saved=text(j,"source_id");if(!ret&&!id(saved,96,false))ret=-EIO;
  if(!ret){snprintf(source,97,"%s",saved);ret=digest(source,strlen(source),actual);if(!ret&&strcmp(actual,source_hash))ret=-EIO;}
  cJSON_Delete(j);j=NULL;if(!ret)ret=load_head(source,&j);
  if (!ret && !number(j, "last_sequence", last))
    ret = -EIO;
  cJSON_Delete(j);
  return ret == -ENOENT ? -EIO : ret;
}
static int ensure_index(uint64_t expected_count,const struct buffer_source_visibility_store_s *store)
{
  bool ready=false;uint64_t count=0;int ret=buffer_source_index_open(&ready,&count);if(ret)return ret;
  if(ready){
    if(count!=expected_count)return -EIO;
    ret=buffer_source_visibility_build_clear();if(ret)return ret;
    if (unlink(BUILD) && errno != ENOENT)
      return -errno;
    return buffer_agent_sync_parent(BUILD);
  }
  /* Upgrade is a persisted, bounded maintenance step. Old input is retained;
   * no new remote receive may commit until the complete index is ready. */
  cJSON *progress=NULL;ret=json_read(BUILD,&progress);char current[65]="";uint64_t sequence=0;
  if(!ret){uint64_t version=0,target=0;const char *saved=text(progress,"source_hash");
    if(!cJSON_IsObject(progress)||cJSON_GetArraySize(progress)!=4||!number(progress,"schema_version",&version)||version!=1||
       !number(progress,"expected_count",&target)||target!=expected_count||!number(progress,"sequence",&sequence)||
       !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(progress,"source_hash"))||(*saved?!hex(saved):sequence!=0))ret=-EIO;
    if(!ret)snprintf(current,sizeof(current),"%s",saved);
  }else if(ret==-ENOENT)ret=write_build("",0,expected_count);
  cJSON_Delete(progress);if(ret)return ret;
  char source[97];uint64_t last=0;bool have=false;size_t bytes_read=0;
  for(unsigned work=0;work<16&&bytes_read<2*1024*1024;work++){
    if(!have&&current[0]){ret=build_source(current,source,&last);if(ret)return ret;if(sequence>last)return -EIO;have=true;}
    if(!have||sequence==last){
      char next[65];ret=next_source(current,next);if(ret)return ret;
      if(!next[0]){
        // Do not combine two full migration batches in one worker call.
        if(work||bytes_read)return -EAGAIN;
        bool visible_done=false;ret=buffer_source_visibility_build(store,expected_count,&visible_done);if(ret)return ret;
        if(!visible_done)return -EAGAIN;
        ret=buffer_source_index_finish(expected_count);if(ret)return ret;
        ret=buffer_source_visibility_build_clear();if(ret)return ret;
        if (unlink(BUILD) && errno != ENOENT)
          return -errno;
        return buffer_agent_sync_parent(BUILD);
      }
      snprintf(current,sizeof(current),"%s",next);sequence=0;
      ret=write_build(current,0,expected_count);if(ret)return ret;
      ret=build_source(current,source,&last);if(ret)return ret;have=true;
      if(!last)continue;
    }
    char *raw=NULL,sha[65];size_t bytes=0;cJSON *j=NULL;ret=read_record(source,sequence+1,&raw,&bytes,&j);
    if(!ret)ret=digest(raw,bytes,sha);
    if(!ret)ret=write_index(source,sequence+1,text(cJSON_GetObjectItemCaseSensitive(j,"event"),"event_id"),sha);
    release(raw,bytes);cJSON_Delete(j);if(ret)return ret;
    sequence++;bytes_read+=bytes;
    ret=write_build(current,sequence,expected_count);if(ret)return ret;
  }
  return -EAGAIN;
}
static int ensure(const char *local_source,bool require_index)
{
  if(!local_source||!id(local_source,96,false))return -EINVAL;
  if(strcmp(visibility_local,local_source))snprintf(visibility_local,sizeof(visibility_local),"%s",local_source);
  struct buffer_source_visibility_store_s store=visibility_store(local_source);
  const char *paths[]={ROOT,REMOTES,IDS,OWNERS,ROOT "/remote-visibility",ROOT "/remote-visibility-owners"};
  for(unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++){int ret=buffer_agent_private_directory(paths[i]);if(ret)return ret;}
  uint64_t clock=0;int ret=observed(&clock);
  if(ret==-ENOENT){
    ret=empty_directory(REMOTES,false);if(!ret)ret=empty_directory(IDS,false);
    if(!ret)ret=empty_directory(OWNERS,false);
    if(!ret){int empty=empty_directory(ROOT "/remote-index",false);if(empty!=-ENOENT)ret=empty;}
    if(!ret){if(!access(INTENT,F_OK)||errno!=ENOENT)ret=-EIO;}
    if(!ret){if(!access(STAGING,F_OK)||errno!=ENOENT)ret=-EIO;}
    if(!ret)ret=empty_directory(ROOT "/remote-visibility",false);
    if(!ret)ret=empty_directory(ROOT "/remote-visibility-owners",false);
    const char *visibility_metadata[]={PROJECTION_CURSOR,ROOT "/remote-visibility-pending.json",ROOT "/remote-visibility-envelope.pending",ROOT "/remote-visibility-index-build.json",ROOT "/remote-view-state.json"};
    for(unsigned i=0;!ret&&i<sizeof(visibility_metadata)/sizeof(visibility_metadata[0]);i++)if(!access(visibility_metadata[i],F_OK)||errno!=ENOENT)ret=-EIO;
    const char *views[]={ROOT "/remote-views",ROOT "/remote-causal",ROOT "/remote-parent-work"};
    for(unsigned i=0;!ret&&i<sizeof(views)/sizeof(views[0]);i++){int empty=empty_directory(views[i],false);if(empty!=-ENOENT)ret=empty;}
    if(ret)return ret;
    cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"schema_version",1)&&cJSON_AddNumberToObject(j,"observed_clock",0);
    ret=built?json_write(STATE,j):-ENOMEM;cJSON_Delete(j);
  }
  if(!ret)ret=recover(local_source);
  if(!ret)ret=buffer_source_visibility_recover(&store);
  uint64_t total=0;if(!ret)ret=owner_coverage(&total);
  uint64_t visible=0;if(!ret)ret=observed(&clock);if(!ret)ret=buffer_source_visibility_total(&store,clock,&visible);
  if(!ret){if(visible>MAX_COUNTER-total)ret=-EOVERFLOW;else total+=visible;}
  return ret?ret:require_index?ensure_index(total,&store):0;
}
/* A hash-verified trie proves absence without scanning immutable history.
 * A hit still resolves to the exact committed source envelope. */
static int find_id(const char *event_id,cJSON **found)
{
  *found=NULL;int ret=buffer_source_index_get(event_id,found);if(ret||!*found)return ret;
  uint64_t seq=0,last=0;
  if(!number(*found,"source_sequence",&seq)){ret=-EIO;goto done;}
  cJSON *head=NULL,*j=NULL;char *raw=NULL,hash[65];size_t bytes=0;
  bool visible=false;ret=buffer_source_visibility_active(text(*found,"source_id"),&visible);
  if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(visibility_local);
    ret=buffer_source_visibility_read(&store,NULL,text(*found,"source_id"),seq,&raw,&bytes);
    if (ret == -EAGAIN)
      ret = -EIO;
    if (!ret)
      ret = buffer_source_envelope_decode(raw, bytes, &j);
  }else if(!ret){
    ret=load_head(text(*found,"source_id"),&head);
    if(!ret&&(!number(head,"last_sequence",&last)||seq>last))ret=-EIO;
    if(!ret)ret=read_record(text(*found,"source_id"),seq,&raw,&bytes,&j);
  }
  if(!ret)ret=digest(raw,bytes,hash);
  if(!ret&&!same_index(*found,text(j,"source_id"),seq,text(cJSON_GetObjectItemCaseSensitive(j,"event"),"event_id"),hash))ret=-EIO;
  cJSON_Delete(head);cJSON_Delete(j);release(raw,bytes);
done:
  if(ret){cJSON_Delete(*found);*found=NULL;}return ret;
}
int buffer_source_remote_clock(const char *local_source,uint64_t *clock)
{
  if (!clock)
    return -EINVAL;
  *clock = 0;
  pthread_mutex_lock(&remote_lock);
  int ret=ensure(local_source,false);if(!ret)ret=observed(clock);pthread_mutex_unlock(&remote_lock);return ret;
}
int buffer_source_remote_progress(const char *peer,const char *source,bool (*authorize)(void *),void *context,uint64_t *next_sequence)
{
  if(!peer||!hex(peer)||!source||!id(source,96,false)||!authorize||!next_sequence)return -EINVAL;
  *next_sequence=0;if(!authorize(context))return -EACCES;
  char local[97];uint64_t sequence=0;int ret=buffer_source_local_head(local,sizeof(local),&sequence);if(ret)return ret;
  if(!strcmp(local,source))return -EACCES;
  pthread_mutex_lock(&remote_lock);cJSON *head=NULL;ret=ensure(local,true);
  if(!ret&&!authorize(context))ret=-EACCES;
  bool visible=false;if(!ret)ret=buffer_source_visibility_active(source,&visible);
  if(!ret&&visible){
    struct buffer_source_visibility_store_s store=visibility_store(local);struct buffer_source_visibility_progress_s progress;
    ret=buffer_source_visibility_progress(&store,peer,source,&progress);if(!ret)*next_sequence=progress.original_next;
    goto progress_done;
  }
  if(!ret){ret=load_head(source,&head);if(ret==-ENOENT){
      char path[320];ret=source_dir(path,source);
      if(!ret){int empty=empty_directory(path,true);ret=empty==-ENOENT?0:empty;}
      if(!ret)*next_sequence=1;
    }else if(!ret){
      if(strcmp(text(head,"owner_peer"),peer)||!number(head,"last_sequence",&sequence))ret=-EACCES;
      else *next_sequence=sequence+1;
    }
  }
progress_done:
  if(!ret&&!authorize(context))ret=-EACCES;
  if (ret)
    *next_sequence = 0;
  cJSON_Delete(head);
  pthread_mutex_unlock(&remote_lock);
  return ret;
}
int buffer_source_remote_read(const char *source,uint64_t sequence,char **raw,size_t *length)
{
  if (!raw || !length)
    return -EINVAL;
  *raw = NULL;
  *length = 0;
  if(!source||!id(source,96,false)||!sequence||sequence>=MAX_COUNTER)return -EINVAL;
  char local[97];uint64_t local_head=0;int ret=buffer_source_local_head(local,sizeof(local),&local_head);if(ret)return ret;
  if(!strcmp(local,source))return -EINVAL;
  pthread_mutex_lock(&remote_lock);cJSON *head=NULL,*envelope=NULL;uint64_t last=0;ret=ensure(local,false);
  bool visible=false;if(!ret)ret=buffer_source_visibility_active(source,&visible);
  if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(local);ret=buffer_source_visibility_read(&store,NULL,source,sequence,raw,length);}
  else if(!ret){
    ret=load_head(source,&head);
    if(!ret&&!number(head,"last_sequence",&last))ret=-EIO;
    if(!ret)ret=sequence>last?-ENOENT:read_record(source,sequence,raw,length,&envelope);
  }
  cJSON_Delete(head);cJSON_Delete(envelope);pthread_mutex_unlock(&remote_lock);return ret;
}
int buffer_source_remote_owned_read(const char *peer,const char *source,uint64_t sequence,
                                    bool (*authorize)(void *),void *context,char **raw,size_t *length)
{
  if (!raw || !length)
    return -EINVAL;
  *raw = NULL;
  *length = 0;
  if(!peer||!hex(peer)||!source||!id(source,96,false)||!sequence||sequence>=MAX_COUNTER||!authorize)return -EINVAL;
  if(!authorize(context))return -EACCES;
  char local[97];uint64_t local_head=0;int ret=buffer_source_local_head(local,sizeof(local),&local_head);if(ret)return ret;
  if(!strcmp(local,source))return -EACCES;
  pthread_mutex_lock(&remote_lock);cJSON *head=NULL,*envelope=NULL;uint64_t last=0;ret=ensure(local,false);
  if(!ret&&!authorize(context))ret=-EACCES;
  bool visible=false;if(!ret)ret=buffer_source_visibility_active(source,&visible);
  if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(local);ret=buffer_source_visibility_read(&store,peer,source,sequence,raw,length);}
  else if(!ret){
    ret=load_head(source,&head);
    if(!ret&&strcmp(text(head,"owner_peer"),peer))ret=-EACCES;
    if(!ret&&!number(head,"last_sequence",&last))ret=-EIO;
    if(!ret)ret=sequence>last?-ENOENT:read_record(source,sequence,raw,length,&envelope);
  }
  if(!ret&&!authorize(context))ret=-EACCES;
  if(ret){release(*raw,*length);*raw=NULL;*length=0;}
  cJSON_Delete(head);cJSON_Delete(envelope);pthread_mutex_unlock(&remote_lock);return ret;
}
int buffer_source_remote_receive(const char *peer,const char *raw,size_t length,bool (*authorize)(void *),void *context,struct buffer_source_import_s *result)
{
  if(!peer||!hex(peer)||!authorize||!result)return -EINVAL;
  memset(result,0,sizeof(*result));if(!authorize(context))return -EACCES;
  cJSON *j=NULL,*head=NULL,*old=NULL;int ret=buffer_source_envelope_decode(raw,length,&j);if(ret)return ret;
  char local[97],hash[65];uint64_t local_head=0,seq=0,clock=0,last=0,previous=0;
  ret=buffer_source_local_head(local,sizeof(local),&local_head);if(ret)goto outside;
  const char *source=text(j,"source_id");const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event");const char *event_id=text(event,"event_id");
  char reserved[104];snprintf(reserved,sizeof(reserved),"evt_%s_",local);
  if(!strcmp(local,source)||!strncmp(event_id,reserved,strlen(reserved))){ret=-EACCES;goto outside;}
  if(!number(j,"source_sequence",&seq)||!number(event,"logical_clock",&clock)){ret=-EINVAL;goto outside;}
  ret=digest(raw,length,hash);if(ret)goto outside;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,true);if(ret)goto done;
  if(!authorize(context)){ret=-EACCES;goto done;}
  bool visible=false;ret=buffer_source_visibility_active(source,&visible);if(ret)goto done;
  if(visible){ret=-ENOTSUP;goto done;}
  ret=load_head(source,&head);if(ret!=-ENOENT&&ret)goto done;
  if(!ret&&(strcmp(text(head,"owner_peer"),peer)||!number(head,"last_sequence",&last)||!number(head,"last_clock",&previous))){ret=-EACCES;goto done;}ret=0;
  result->next_sequence=last+1;
  ret=find_id(event_id,&old);if(ret)goto done;
  if(old){
    result->state=seq<=last&&same_index(old,source,seq,event_id,hash)?BUFFER_SOURCE_DUPLICATE:BUFFER_SOURCE_CONFLICT;
    /* Fingerprint equality never replaces exact original-byte equality. */
    if(result->state==BUFFER_SOURCE_DUPLICATE){char *saved=NULL;size_t bytes=0;cJSON *stored=NULL;ret=read_record(source,seq,&saved,&bytes,&stored);
      if (!ret && (bytes != length || memcmp(saved, raw, length)))
        result->state = BUFFER_SOURCE_CONFLICT;
      release(saved, bytes);
      cJSON_Delete(stored);
    }
    goto done;
  }
  if(seq<=last){result->state=BUFFER_SOURCE_CONFLICT;goto done;}
  if(seq!=last+1){result->state=BUFFER_SOURCE_GAP;goto done;}
  if(last&&!strcmp(text(j,"provenance"),"native")&&clock<=previous){result->state=BUFFER_SOURCE_CONFLICT;goto done;}
  if(!head){ret=pin_empty(source,peer,&head);if(ret)goto done;}
  if(!authorize(context)){ret=-EACCES;goto done;}
  ret=buffer_agent_atomic_bytes(STAGING,raw,length,BUFFER_SOURCE_EVENT_BYTES);if(ret)goto done;
  if(!authorize(context)){ret=-EACCES;goto done;}
  cJSON *intent=cJSON_CreateObject();bool built=intent&&cJSON_AddStringToObject(intent,"source_id",source)&&cJSON_AddStringToObject(intent,"peer",peer)&&
    cJSON_AddNumberToObject(intent,"source_sequence",(double)seq)&&cJSON_AddNumberToObject(intent,"logical_clock",(double)clock)&&
    cJSON_AddStringToObject(intent,"event_id",event_id)&&cJSON_AddStringToObject(intent,"fingerprint",hash);
  ret=built?json_write(INTENT,intent):-ENOMEM;cJSON_Delete(intent);if(ret)goto done;
  /* The durable intent records acceptance under the preceding live grant.
   * Recovery may finish storage after disconnect, never external actions. */
  ret=recover(local);if(!ret){result->state=BUFFER_SOURCE_STORED;result->next_sequence=seq+1;}
done:
  if(!ret&&!authorize(context))ret=-EACCES;
  pthread_mutex_unlock(&remote_lock);
outside:
  cJSON_Delete(j);cJSON_Delete(head);cJSON_Delete(old);return ret;
}
static int visibility_begin(const char *peer,const char *source,bool (*authorize)(void *),void *context,char local[97])
{
  if(!peer||!hex(peer)||!authorize||(source&&!id(source,96,false)))return -EINVAL;
  if (!authorize(context))
    return -EACCES;
  uint64_t sequence = 0;
  int ret = buffer_source_local_head(local, 97, &sequence);
  if (ret)
    return ret;
  if(source&&!strcmp(local,source))return -EACCES;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,true);if(!ret&&!authorize(context))ret=-EACCES;
  if (ret)
    pthread_mutex_unlock(&remote_lock);
  return ret;
}
int buffer_source_remote_visibility_progress(const char *peer,const char *source,bool (*authorize)(void *),void *context,
                                              struct buffer_source_visibility_progress_s *out)
{
  if (!source || !out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  char local[97];
  int ret = visibility_begin(peer, source, authorize, context, local);
  if (ret)
    return ret;
  struct buffer_source_visibility_store_s store=visibility_store(local);ret=buffer_source_visibility_progress(&store,peer,source,out);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    memset(out, 0, sizeof(*out));
  pthread_mutex_unlock(&remote_lock);
  return ret;
}
int buffer_source_remote_visibility_reserve(const char *peer,const char *source,uint64_t sequence,const char *token,
                                             bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{
  if (!source || !out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  char local[97];
  int ret = visibility_begin(peer, source, authorize, context, local);
  if (ret)
    return ret;
  struct buffer_source_visibility_store_s store=visibility_store(local);ret=buffer_source_visibility_reserve(&store,peer,source,sequence,token,authorize,context,out);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    memset(out, 0, sizeof(*out));
  pthread_mutex_unlock(&remote_lock);
  return ret;
}
int buffer_source_remote_visibility_receive(const char *peer,const char *raw,size_t length,const char *token,
                                             bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{
  if (!out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  char local[97];
  int ret = visibility_begin(peer, NULL, authorize, context, local);
  if (ret)
    return ret;
  struct buffer_source_visibility_store_s store=visibility_store(local);ret=buffer_source_visibility_receive(&store,peer,raw,length,token,authorize,context,out);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    memset(out, 0, sizeof(*out));
  pthread_mutex_unlock(&remote_lock);
  return ret;
}
static int projection_cursor_save(const char *hash,uint64_t sequence)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"version",BUFFER_SOURCE_PROJECTION_VERSION)&&
    cJSON_AddStringToObject(j,"source_hash",hash)&&cJSON_AddNumberToObject(j,"sequence",(double)sequence);
  int ret=built?json_write(PROJECTION_CURSOR,j):-ENOMEM;cJSON_Delete(j);return ret;
}
static int projection_cursor_load(char hash[65],uint64_t *sequence)
{
  hash[0]=0;*sequence=0;cJSON *j=NULL;int ret=json_read(PROJECTION_CURSOR,&j);if(ret==-ENOENT)return 0;
  uint64_t version=0;if(!ret&&(!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=3||!number(j,"version",&version)||version<1||version>BUFFER_SOURCE_PROJECTION_VERSION||
    !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(j,"source_hash"))||!number(j,"sequence",sequence)||
    (*text(j,"source_hash")?!hex(text(j,"source_hash")):*sequence!=0)))ret=-EIO;
  if(!ret&&version<BUFFER_SOURCE_PROJECTION_VERSION){hash[0]=0;*sequence=0;ret=projection_cursor_save(hash,*sequence);
  } else if (!ret)
    snprintf(hash, 65, "%s", text(j, "source_hash"));
  cJSON_Delete(j);
  return ret;
}
static int history_file_select(const char *directory,uint64_t anchor,int direction,uint64_t low,uint64_t high,uint64_t expected,
                               uint64_t *best,uint64_t *minimum,uint64_t *maximum)
{
  DIR *dir=opendir(directory);if(!dir)return -errno;int ret=0;uint64_t count=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    size_t n=strlen(entry->d_name);if(!((n==25&&!strcmp(entry->d_name+20,".json"))||(n==30&&!strcmp(entry->d_name+20,".view.json"))))continue;
    uint64_t seq=0;bool valid=true;for(unsigned i=0;i<20;i++){unsigned digit=(unsigned)(entry->d_name[i]-'0');
      if(digit>9||seq>(MAX_COUNTER-digit)/10){valid=false;break;}seq=seq*10+digit;}
    if(!valid){ret=-EIO;break;}
    if(seq<=low||seq>high){ret=-EIO;break;}
    if(n==25)count++;
    if(minimum&&(!*minimum||seq<*minimum))*minimum=seq;
    if(maximum&&seq>*maximum)*maximum=seq;
    if((!anchor||(direction>0?seq>anchor:seq<anchor))&&(!*best||(direction>0?seq<*best:seq>*best)))*best=seq;
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  if (!ret && count != expected)
    ret = -EIO;
  return ret;
}
static int projection_file_next(const char *directory,uint64_t after,uint64_t low,uint64_t high,uint64_t expected,uint64_t *best)
{return history_file_select(directory,after,1,low,high,expected,best,NULL,NULL);}
static int projection_paths(const char *local,const char *source,const char *peer,uint64_t sequence,char path[320],
                            char **raw,size_t *bytes,cJSON **envelope)
{
  bool visible=false;int ret=buffer_source_visibility_active(source,&visible);uint64_t base=0,covered=0;
  char directory[320];struct buffer_source_visibility_store_s store=visibility_store(local);
  *raw=NULL;*bytes=0;*envelope=NULL;
  if(!ret&&visible)ret=buffer_source_visibility_projection_range(&store,peer,source,&base,&covered,NULL,directory);
  if(!ret&&!visible){cJSON *head=NULL;ret=load_head(source,&head);if(!ret&&strcmp(text(head,"owner_peer"),peer))ret=-EACCES;
    if (!ret && !number(head, "last_sequence", &base))
      ret = -EIO;
    covered = base;
    cJSON_Delete(head);
  }
  if(!ret&&sequence>covered)ret=-ENOENT;
  if(!ret&&sequence<=base){ret=source_path(path,source,sequence,"view.json");if(!ret)ret=read_record(source,sequence,raw,bytes,envelope);}
  else if(!ret){int n=snprintf(path,320,"%s/%020llu.view.json",directory,(unsigned long long)sequence);ret=n<0||n>=320?-ENAMETOOLONG:0;
    if(!ret)ret=buffer_source_visibility_read(&store,peer,source,sequence,raw,bytes);
    if(!ret)ret=buffer_source_envelope_decode(*raw,*bytes,envelope);}
  return ret;
}
int buffer_source_remote_history_source(const char *after,char next[65])
{
  if (!after || !next || (*after && !hex(after)))
    return -EINVAL;
  next[0] = 0;
  char local[97];uint64_t sequence=0;int ret=buffer_source_local_head(local,sizeof(local),&sequence);if(ret)return ret;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,false);if(!ret)ret=next_source(after,next);
  pthread_mutex_unlock(&remote_lock);if(ret)next[0]=0;return ret;
}
int buffer_source_remote_history_read(const char *hash,uint64_t anchor,int direction,
                                      struct buffer_source_projection_input_s *out,bool *earlier,bool *later)
{
  if(!hash||!hex(hash)||!out||!earlier||!later||anchor>=MAX_COUNTER||(direction!=1&&direction!=-1))return -EINVAL;
  memset(out,0,sizeof(*out));*earlier=*later=false;
  char local[97],source[97],peer[65],parent[320],overlay[320];uint64_t head=0,base=0,covered=0,records=0,accepted=0,best=0,first=0,last=0;
  int ret=buffer_source_local_head(local,sizeof(local),&head);if(ret)return ret;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,false);if(!ret)ret=build_source(hash,source,&base);
  cJSON *owner=NULL,*envelope=NULL;if(!ret)ret=owner_read(source,&owner,&accepted);
  if (!ret)
    snprintf(peer, sizeof(peer), "%s", text(owner, "owner_peer"));
  cJSON_Delete(owner);
  bool visible=false;if(!ret)ret=buffer_source_visibility_active(source,&visible);covered=base;
  if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(local);
    ret=buffer_source_visibility_projection_range(&store,peer,source,&base,&covered,&records,overlay);}
  if(!ret&&anchor>covered)ret=-ESTALE;
  if(!ret)ret=source_dir(parent,source);
  if(!ret)ret=history_file_select(parent,anchor,direction,0,base,base,&best,&first,&last);
  if(!ret&&visible)ret=history_file_select(overlay,anchor,direction,base,covered,records,&best,&first,&last);
  if(!ret&&best){char queue_path[320];ret=projection_paths(local,source,peer,best,queue_path,&out->raw,&out->length,&envelope);
    if(!ret)ret=digest(out->raw,out->length,out->fingerprint);
    if(!ret){snprintf(out->peer,sizeof(out->peer),"%s",peer);snprintf(out->source,sizeof(out->source),"%s",source);
      snprintf(out->event_id,sizeof(out->event_id),"%s",text(cJSON_GetObjectItemCaseSensitive(envelope,"event"),"event_id"));
      out->sequence=best;*earlier=first<best;*later=last>best;}}
  cJSON_Delete(envelope);pthread_mutex_unlock(&remote_lock);
  if(ret){release(out->raw,out->length);memset(out,0,sizeof(*out));*earlier=*later=false;return ret;}return best?1:0;
}
/* Original lookup for offline causal inspection only. It never forwards a
 * remote owner's bytes. Local journal access always precedes remote locking. */
static int causal_original(const char *event_id,char **raw,size_t *bytes,bool *local_record)
{
  *raw=NULL;*bytes=0;*local_record=false;
  char local[97],prefix[112];uint64_t head=0;
  int ret=buffer_source_local_head(local,sizeof(local),&head);if(ret)return ret;
  int n=snprintf(prefix,sizeof(prefix),"evt_%s_",local);
  if(n<0||(size_t)n>=sizeof(prefix))return -EIO;
  if(!strncmp(event_id,prefix,(size_t)n)){
    uint64_t sequence=0;const char *digits=event_id+n;
    if(!*digits||*digits=='0')return -ENOENT;
    for(const char *p=digits;*p;p++){
      unsigned digit=(unsigned)(*p-'0');if(digit>9||sequence>(MAX_COUNTER-1-digit)/10)return -ENOENT;
      sequence=sequence*10+digit;
    }
    if(!sequence||sequence>head)return -ENOENT;
    ret=buffer_source_local_read(sequence,raw,bytes);if(!ret)*local_record=true;return ret;
  }
  pthread_mutex_lock(&remote_lock);cJSON *found=NULL,*envelope=NULL;
  ret=ensure(local,true);if(!ret)ret=find_id(event_id,&found);
  if(!ret&&!found)ret=-ENOENT;
  uint64_t sequence=0;bool visible=false;
  if(!ret&&!number(found,"source_sequence",&sequence))ret=-EIO;
  if(!ret)ret=buffer_source_visibility_active(text(found,"source_id"),&visible);
  if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(local);
    ret=buffer_source_visibility_read(&store,NULL,text(found,"source_id"),sequence,raw,bytes);}
  else if(!ret)ret=read_record(text(found,"source_id"),sequence,raw,bytes,&envelope);
  if(!ret){char hash[65];ret=digest(*raw,*bytes,hash);if(!ret&&strcmp(hash,text(found,"fingerprint")))ret=-EIO;}
  cJSON_Delete(found);cJSON_Delete(envelope);pthread_mutex_unlock(&remote_lock);
  if(ret){release(*raw,*bytes);*raw=NULL;*bytes=0;}return ret;
}
#define CAUSAL ROOT "/remote-causal"
#define PARENT_WORK ROOT "/remote-parent-work"
static int causal_path(const char *event_id,char path[320])
{
  char hash[65];int ret=digest(event_id,strlen(event_id),hash);if(ret)return ret;
  int n=snprintf(path,320,CAUSAL "/%s.json",hash);return n<0||n>=320?-ENAMETOOLONG:0;
}
static bool causal_state(const char *state)
{
  const char *known[]={"linked","legacy","unsupported_schema","invalid_clock","missing_parent","waiting_parent","blocked_parent"};
  for (unsigned i = 0; i < sizeof(known) / sizeof(known[0]); i++)
    if (!strcmp(state, known[i]))
      return true;
  return false;
}
static int causal_cached(const char *event_id,const char *hash,uint64_t clock,char state[24])
{
  state[0]=0;char path[320];int ret=causal_path(event_id,path);if(ret)return ret;
  cJSON *j=NULL;ret=json_read(path,&j);if(ret==-ENOENT||ret==-EIO||ret==-EINVAL)return 0;if(ret)return ret;
  uint64_t version=0,saved_clock=0;
  /* Damaged derived metadata is not source corruption. Leave it unproven
   * until this original reaches the background scan again. */
  if(cJSON_IsObject(j)&&cJSON_GetArraySize(j)==8&&number(j,"version",&version)&&version==1&&
    !strcmp(text(j,"event_id"),event_id)&&!strcmp(text(j,"fingerprint"),hash)&&
    number(j,"logical_clock",&saved_clock)&&saved_clock==clock&&causal_state(text(j,"state")))
    snprintf(state,24,"%s",text(j,"state"));
  cJSON_Delete(j);return 0;
}
int buffer_source_remote_causal_check(const struct buffer_source_projection_input_s *input,char state[24])
{
  if(!input||!state||!input->raw||!input->length||!hex(input->peer)||!id(input->source,96,false)||!id(input->event_id,160,true)||!hex(input->fingerprint))return -EINVAL;
  state[0]=0;cJSON *j=NULL;int ret=buffer_source_envelope_decode(input->raw,input->length,&j);if(ret)return ret;
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event");uint64_t clock=0,schema=0,sequence=0;
  char hash[65];ret=digest(input->raw,input->length,hash);
  if(!ret&&(strcmp(hash,input->fingerprint)||strcmp(text(event,"event_id"),input->event_id)||
    strcmp(text(j,"source_id"),input->source)||!number(j,"source_sequence",&sequence)||sequence!=input->sequence||
    !number(event,"logical_clock",&clock)||!number(event,"schema_version",&schema)))ret=-EIO;
  bool missing=false,waiting=false,blocked=false,invalid=false;
  if(!ret&&schema!=1)snprintf(state,24,"unsupported_schema");
  else if(!ret&&!strcmp(text(j,"provenance"),"legacy_local"))snprintf(state,24,"legacy");
  else if(!ret){
    const cJSON *parent,*parents=cJSON_GetObjectItemCaseSensitive(event,"parent_event_ids");
    cJSON_ArrayForEach(parent,parents){
      char *raw=NULL;size_t bytes=0;bool local=false;cJSON *original=NULL;
      ret=causal_original(parent->valuestring,&raw,&bytes,&local);
      if(ret==-ENOENT){missing=true;ret=0;continue;}if(ret)break;
      ret=buffer_source_envelope_decode(raw,bytes,&original);
      const cJSON *p=cJSON_GetObjectItemCaseSensitive(original,"event");uint64_t parent_clock=0,parent_schema=0,parent_sequence=0;
      char parent_hash[65],cached[24]={0};
      if(!ret)ret=digest(raw,bytes,parent_hash);
      if(!ret&&(strcmp(text(p,"event_id"),parent->valuestring)||!number(p,"logical_clock",&parent_clock)||
        !number(p,"schema_version",&parent_schema)||!number(original,"source_sequence",&parent_sequence)))ret=-EIO;
      if(!ret){
        if(parent_clock>=clock||(!strcmp(text(original,"source_id"),input->source)&&parent_sequence>=input->sequence))invalid=true;
        if(parent_schema!=1||strcmp(text(original,"provenance"),"native"))blocked=true;
        else if(!local){
          ret=causal_cached(parent->valuestring,parent_hash,parent_clock,cached);
          if(!ret){if(!cached[0]||!strcmp(cached,"missing_parent")||!strcmp(cached,"waiting_parent"))waiting=true;
            else if(strcmp(cached,"linked"))blocked=true;}
        }
      }
      cJSON_Delete(original);release(raw,bytes);if(ret)break;
    }
    if(!ret)snprintf(state,24,"%s",invalid?"invalid_clock":missing?"missing_parent":blocked?"blocked_parent":waiting?"waiting_parent":"linked");
  }
  if(!ret){
    ret=buffer_agent_private_directory(CAUSAL);char path[320];if(!ret)ret=causal_path(input->event_id,path);
    cJSON *saved=NULL;int old=ret?ret:json_read(path,&saved);
    bool same=!old&&cJSON_GetArraySize(saved)==8&&!strcmp(text(saved,"event_id"),input->event_id)&&
      !strcmp(text(saved,"fingerprint"),hash)&&!strcmp(text(saved,"state"),state)&&
      !strcmp(text(saved,"owner_peer"),input->peer)&&!strcmp(text(saved,"source_id"),input->source)&&
      cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(saved,"source_sequence"))&&cJSON_GetObjectItemCaseSensitive(saved,"source_sequence")->valuedouble==(double)input->sequence&&
      cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(saved,"version"))&&cJSON_GetObjectItemCaseSensitive(saved,"version")->valuedouble==1&&
      cJSON_IsNumber(cJSON_GetObjectItemCaseSensitive(saved,"logical_clock"))&&cJSON_GetObjectItemCaseSensitive(saved,"logical_clock")->valuedouble==(double)clock;
    cJSON_Delete(saved);
    if(!ret&&old&&old!=-ENOENT&&old!=-EIO&&old!=-EINVAL)ret=old;
    if(!ret&&!same)ret=buffer_source_view_causal_changed(!strcmp(state,"invalid_clock")||!strcmp(state,"blocked_parent"));
    if(!ret&&!same){cJSON *next=cJSON_CreateObject();bool built=next&&cJSON_AddNumberToObject(next,"version",1)&&
        cJSON_AddStringToObject(next,"event_id",input->event_id)&&cJSON_AddStringToObject(next,"fingerprint",hash)&&
        cJSON_AddNumberToObject(next,"logical_clock",(double)clock)&&cJSON_AddStringToObject(next,"state",state)&&
        cJSON_AddStringToObject(next,"owner_peer",input->peer)&&cJSON_AddStringToObject(next,"source_id",input->source)&&
        cJSON_AddNumberToObject(next,"source_sequence",(double)input->sequence);
      ret=built?json_write(path,next):-ENOMEM;cJSON_Delete(next);}
  }
  if(!ret){
    char event_hash[65],path[320];ret=digest(input->event_id,strlen(input->event_id),event_hash);
    int n=ret?0:snprintf(path,sizeof(path),PARENT_WORK "/%s.json",event_hash);
    if(!ret&&(n<0||(size_t)n>=sizeof(path)))ret=-ENAMETOOLONG;
    if(!ret&&!strcmp(state,"missing_parent")){
      ret=buffer_agent_private_directory(PARENT_WORK);
      char original[320];cJSON *current=NULL,*old=NULL;
      if(!ret)ret=causal_path(input->event_id,original);
      if(!ret)ret=json_read(original,&current);
      int existing=ret?ret:json_read(path,&old);
      if(!ret&&existing&&existing!=-ENOENT&&existing!=-EIO&&existing!=-EINVAL)ret=existing;
      if(!ret&&(existing||!cJSON_Compare(current,old,true)))ret=json_write(path,current);
      cJSON_Delete(current);cJSON_Delete(old);
    }else if(!ret){
      if(!unlink(path))ret=buffer_agent_sync_parent(path);
      else if(errno!=ENOENT)ret=-errno;
    }
  }
  cJSON_Delete(j);if(ret)state[0]=0;return ret;
}
/* A cursor covers one metadata record per call and at most three missing
 * references. The actual child must belong to this authenticated peer; neither
 * a derived file nor an alleged parent is authority to read another owner. */
int buffer_source_remote_parent_requests(const char *peer,const char *after,unsigned offset,
  bool (*authorize)(void *),void *context,char next[65],unsigned *next_offset,cJSON **ids)
{
  if(!peer||!hex(peer)||!after||(*after&&!hex(after))||offset>32||(!*after&&offset)||
    !authorize||!next||!next_offset||!ids)return -EINVAL;
  next[0]=0;*next_offset=0;*ids=NULL;if(!authorize(context))return -EACCES;
  char selected[65]={0};
  if(offset)snprintf(selected,sizeof(selected),"%s",after);
  else {
    DIR *dir=opendir(PARENT_WORK);if(!dir&&errno!=ENOENT)return -errno;
    int ret=0;
    if(dir){for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
      if(strlen(entry->d_name)!=69||strcmp(entry->d_name+64,".json"))continue;
      char hash[65];memcpy(hash,entry->d_name,64);hash[64]=0;if(!hex(hash)){ret=-EIO;break;}
      if(strcmp(hash,after)>0&&(!*selected||strcmp(hash,selected)<0))memcpy(selected,hash,65);
    }if(closedir(dir)&&!ret)ret=-errno;}if(ret)return ret;
  }
  cJSON *result=cJSON_CreateArray();if(!result)return -ENOMEM;
  char *raw=NULL;size_t bytes=0;cJSON *saved=NULL,*child=NULL;int ret=0;
  if(*selected){
    char path[320];int n=snprintf(path,sizeof(path),PARENT_WORK "/%s.json",selected);
    ret=n<0||(size_t)n>=sizeof(path)?-ENAMETOOLONG:json_read(path,&saved);
    if(ret==-ENOENT||ret==-EIO||ret==-EINVAL)ret=0;
    if(!ret&&saved&&!strcmp(text(saved,"owner_peer"),peer)&&!strcmp(text(saved,"state"),"missing_parent")){
      uint64_t sequence=0;char expected[65];
      if(!id(text(saved,"event_id"),160,true)||!id(text(saved,"source_id"),96,false)||
        !number(saved,"source_sequence",&sequence)||!sequence||sequence>=MAX_COUNTER||
        digest(text(saved,"event_id"),strlen(text(saved,"event_id")),expected)||strcmp(expected,selected))ret=-EIO;
      if(!ret)ret=buffer_source_remote_owned_read(peer,text(saved,"source_id"),sequence,authorize,context,&raw,&bytes);
      if(!ret)ret=buffer_source_envelope_decode(raw,bytes,&child);
      const cJSON *event=cJSON_GetObjectItemCaseSensitive(child,"event"),*parents=cJSON_GetObjectItemCaseSensitive(event,"parent_event_ids");
      char hash[65];if(!ret)ret=digest(raw,bytes,hash);
      if(!ret&&(strcmp(hash,text(saved,"fingerprint"))||strcmp(text(event,"event_id"),text(saved,"event_id"))))ret=-EIO;
      unsigned at=offset,count=(unsigned)cJSON_GetArraySize(parents);
      for(;!ret&&at<count;at++){
        const cJSON *parent=cJSON_GetArrayItem(parents,(int)at);char *original=NULL;size_t length=0;bool local=false;
        ret=causal_original(parent->valuestring,&original,&length,&local);release(original,length);
        if(ret==-ENOENT){ret=0;cJSON *id=cJSON_CreateString(parent->valuestring);if(!id){ret=-ENOMEM;break;}cJSON_AddItemToArray(result,id);}
        if(cJSON_GetArraySize(result)==3){at++;break;}
      }
      if(!ret&&at<count)*next_offset=at;
    }
    if(!ret)memcpy(next,selected,65);
  }
  if(!ret&&!authorize(context))ret=-EACCES;
  release(raw,bytes);cJSON_Delete(saved);cJSON_Delete(child);
  if(ret){cJSON_Delete(result);next[0]=0;*next_offset=0;return ret;}*ids=result;return 0;
}
int buffer_source_remote_projection_restart(void)
{
  char local[97];uint64_t head=0;int ret=buffer_source_local_head(local,sizeof(local),&head);if(ret)return ret;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,false);if(!ret)ret=projection_cursor_save("",0);
  pthread_mutex_unlock(&remote_lock);return ret;
}
int buffer_source_remote_projection_next(struct buffer_source_projection_input_s *out)
{
  if (!out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  char local[97], current[65];
  uint64_t local_head = 0, sequence = 0;
  int ret=buffer_source_local_head(local,sizeof(local),&local_head);if(ret)return ret;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,false);if(!ret)ret=projection_cursor_load(current,&sequence);
  for(unsigned work=0;!ret&&work<16;work++){
    if(!current[0]){ret=next_source("",current);if(ret)break;if(!current[0])break;sequence=0;}
    char source[97],peer[65],directory[320],overlay[320];uint64_t base=0,covered=0,accepted=0,best=0,records=0;
    ret=build_source(current,source,&base);cJSON *owner=NULL;
    if (!ret)
      ret = owner_read(source, &owner, &accepted);
    if (!ret)
      snprintf(peer, sizeof(peer), "%s", text(owner, "owner_peer"));
    cJSON_Delete(owner);
    bool visible=false;if(!ret)ret=buffer_source_visibility_active(source,&visible);covered=base;
    if(!ret&&visible){struct buffer_source_visibility_store_s store=visibility_store(local);
      ret=buffer_source_visibility_projection_range(&store,peer,source,&base,&covered,&records,overlay);}
    if(!ret&&sequence>covered)ret=-EIO;
    if (!ret)
      ret = source_dir(directory, source);
    if (!ret)
      ret = projection_file_next(directory, sequence, 0, base, base, &best);
    if (!ret && visible)
      ret = projection_file_next(overlay, sequence, base, covered, records,
                                 &best);
    if (ret)
      break;
    if(!best){char next[65];ret=next_source(current,next);if(ret)break;snprintf(current,sizeof(current),"%s",next);sequence=0;
      ret=projection_cursor_save(current,sequence);if(!ret&&!current[0])break;continue;}
    char path[320];if(best<=base)ret=source_path(path,source,best,"view.json");
    else {int n=snprintf(path,sizeof(path),"%s/%020llu.view.json",overlay,(unsigned long long)best);ret=n<0||(size_t)n>=sizeof(path)?-ENAMETOOLONG:0;}
    cJSON *queue=NULL;if(!ret){ret=json_read(path,&queue);if(ret==-ENOENT)ret=0;}
    if(!ret&&queue&&(!cJSON_IsObject(queue)||cJSON_GetArraySize(queue)!=2||!id(text(queue,"event_id"),160,true)||!id(text(queue,"state"),32,false)))ret=-EIO;
    cJSON *envelope=NULL;if(!ret)ret=projection_paths(local,source,peer,best,path,&out->raw,&out->length,&envelope);
    const char *event=text(cJSON_GetObjectItemCaseSensitive(envelope,"event"),"event_id");
    if(!ret&&queue&&strcmp(text(queue,"event_id"),event))ret=-EIO;
    if(!ret&&!queue){queue=cJSON_CreateObject();bool built=queue&&cJSON_AddStringToObject(queue,"event_id",event)&&cJSON_AddStringToObject(queue,"state","pending");ret=built?json_write(path,queue):-ENOMEM;}
    if(!ret)ret=digest(out->raw,out->length,out->fingerprint);
    if(!ret){out->applied=!strcmp(text(queue,"state"),"v3_partial")||!strcmp(text(queue,"state"),BUFFER_SOURCE_VIEW_APPLIED)||!strcmp(text(queue,"state"),"v3_applied")||!strcmp(text(queue,"state"),"v2_applied")||!strcmp(text(queue,"state"),"v1_applied");snprintf(out->projection_state,sizeof(out->projection_state),"%s",text(queue,"state"));snprintf(out->peer,sizeof(out->peer),"%s",peer);snprintf(out->source,sizeof(out->source),"%s",source);
      snprintf(out->event_id,sizeof(out->event_id),"%s",event);out->sequence=best;}
    cJSON_Delete(queue);cJSON_Delete(envelope);if(!ret){ret=1;break;}
  }
  if(!ret&&current[0])ret=-EAGAIN;
  pthread_mutex_unlock(&remote_lock);if(ret!=1){release(out->raw,out->length);memset(out,0,sizeof(*out));}return ret;
}
int buffer_source_remote_projection_complete(const struct buffer_source_projection_input_s *in,const char *state)
{
  if(!in||!state||!hex(in->peer)||!id(in->source,96,false)||!in->sequence||!in->raw||
    (strcmp(state,BUFFER_SOURCE_VIEW_APPLIED)&&strcmp(state,BUFFER_SOURCE_VIEW_UNSUPPORTED)&&strcmp(state,BUFFER_SOURCE_VIEW_INVALID)&&strcmp(state,BUFFER_SOURCE_VIEW_CONFLICT)&&strcmp(state,"v3_partial")&&strcmp(state,"v3_waiting_parent")&&strcmp(state,"v3_blocked_parent")))return -EINVAL;
  char local[97],path[320],hash[65];uint64_t last=0;int ret=buffer_source_local_head(local,sizeof(local),&last);if(ret)return ret;
  pthread_mutex_lock(&remote_lock);ret=ensure(local,false);cJSON *envelope=NULL,*queue=NULL;char *raw=NULL;size_t bytes=0;
  if(!ret)ret=projection_paths(local,in->source,in->peer,in->sequence,path,&raw,&bytes,&envelope);
  if(!ret)ret=digest(raw,bytes,hash);
  if(!ret&&(strcmp(hash,in->fingerprint)||bytes!=in->length||memcmp(raw,in->raw,bytes)||
    strcmp(text(cJSON_GetObjectItemCaseSensitive(envelope,"event"),"event_id"),in->event_id)))ret=-EIO;
  if(!ret)ret=json_read(path,&queue);
  if(!ret&&(!cJSON_IsObject(queue)||cJSON_GetArraySize(queue)!=2||strcmp(text(queue,"event_id"),in->event_id)))ret=-EIO;
  if(!ret&&strcmp(text(queue,"state"),state)){cJSON *next=cJSON_CreateObject();bool built=next&&cJSON_AddStringToObject(next,"event_id",in->event_id)&&cJSON_AddStringToObject(next,"state",state);
    ret=built?json_write(path,next):-ENOMEM;cJSON_Delete(next);}
  if (!ret)
    ret = digest(in->source, strlen(in->source), hash);
  if (!ret)
    ret = projection_cursor_save(hash, in->sequence);
  release(raw,bytes);cJSON_Delete(queue);cJSON_Delete(envelope);pthread_mutex_unlock(&remote_lock);return ret;
}
int buffer_source_remote_visibility_next(const char *peer,const char *source,uint64_t *cursor,bool (*authorize)(void *),void *context,
                                          uint64_t *sequence,char token[33])
{
  if (!source || !cursor || !sequence || !token)
    return -EINVAL;
  *sequence = 0;
  token[0] = 0;
  uint64_t saved = *cursor;
  char local[97];
  int ret=visibility_begin(peer,source,authorize,context,local);if(ret)return ret;struct buffer_source_visibility_store_s store=visibility_store(local);
  ret=buffer_source_visibility_next(&store,peer,source,cursor,sequence,token);if(!authorize(context))ret=-EACCES;
  if(ret){*sequence=0;token[0]=0;if(ret!=-EAGAIN)*cursor=saved;}pthread_mutex_unlock(&remote_lock);return ret;
}
#else
int buffer_source_remote_projection_restart(void){return -ENOTSUP;}
int buffer_source_remote_parent_requests(const char *peer,const char *after,unsigned offset,bool (*authorize)(void *),void *context,char next[65],unsigned *next_offset,cJSON **ids)
{(void)peer;(void)after;(void)offset;(void)authorize;(void)context;if(next)next[0]=0;if(next_offset)*next_offset=0;if(ids)*ids=NULL;return -ENOTSUP;}
int buffer_source_remote_causal_check(const struct buffer_source_projection_input_s *input,char state[24])
{(void)input;if(state)state[0]=0;return -ENOTSUP;}
int buffer_source_remote_history_source(const char *after,char next[65])
{(void)after;if(next)next[0]=0;return -ENOTSUP;}
int buffer_source_remote_history_read(const char *hash,uint64_t anchor,int direction,struct buffer_source_projection_input_s *out,bool *earlier,bool *later)
{(void)hash;(void)anchor;(void)direction;if(out)memset(out,0,sizeof(*out));if(earlier)*earlier=false;if(later)*later=false;return -ENOTSUP;}
int buffer_source_remote_projection_next(struct buffer_source_projection_input_s *out)
{if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_remote_projection_complete(const struct buffer_source_projection_input_s *in,const char *state)
{(void)in;(void)state;return -ENOTSUP;}
int buffer_source_remote_visibility_progress(const char *peer,const char *source,bool (*authorize)(void *),void *context,struct buffer_source_visibility_progress_s *out)
{(void)peer;(void)source;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_remote_visibility_reserve(const char *peer,const char *source,uint64_t sequence,const char *token,bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{(void)peer;(void)source;(void)sequence;(void)token;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_remote_visibility_receive(const char *peer,const char *raw,size_t length,const char *token,bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{(void)peer;(void)raw;(void)length;(void)token;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_remote_visibility_next(const char *peer,const char *source,uint64_t *cursor,bool (*authorize)(void *),void *context,uint64_t *sequence,char token[33])
{(void)peer;(void)source;(void)cursor;(void)authorize;(void)context;if(sequence)*sequence=0;if(token)token[0]=0;return -ENOTSUP;}
int buffer_source_remote_owned_read(const char *peer,const char *source,uint64_t sequence,
                                    bool (*authorize)(void *),void *context,char **raw,size_t *length)
{(void)peer;(void)source;(void)sequence;(void)authorize;(void)context;if(raw)*raw=NULL;if(length)*length=0;return -ENOTSUP;}
int buffer_source_remote_clock(const char *local_source,uint64_t *clock)
{(void)local_source;if(!clock)return -EINVAL;*clock=0;return 0;}
int buffer_source_remote_progress(const char *peer,const char *source,bool (*authorize)(void *),void *context,uint64_t *next_sequence)
{(void)peer;(void)source;(void)authorize;(void)context;if(next_sequence)*next_sequence=0;return -ENOTSUP;}
int buffer_source_remote_read(const char *source,uint64_t sequence,char **raw,size_t *length)
{(void)source;(void)sequence;if(raw)*raw=NULL;if(length)*length=0;return -ENOTSUP;}
int buffer_source_remote_receive(const char *peer,const char *raw,size_t length,bool (*authorize)(void *),void *context,struct buffer_source_import_s *result)
{(void)peer;(void)raw;(void)length;(void)authorize;(void)context;if(result)memset(result,0,sizeof(*result));return -ENOTSUP;}
#endif
