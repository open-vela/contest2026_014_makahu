/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_view.h"
#include "buffer_source_events.h"
#include "buffer_source_privacy.h"
#include "buffer_crdt_sync.h"
#include "buffer_entropy.h"
#include "buffer_replica.h"
#include "buffer_calendar.h"
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#include <mbedtls/sha256.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define VIEWS CONFIG_BUFFER_VELA_DATA_DIR "/event-journal/remote-views"
#define VIEW_BYTES (BUFFER_AGENT_JSON_BYTES+1024)
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static void release(char *raw)
{if(raw){volatile char *p=raw;size_t n=strlen(raw);while(n--)*p++=0;free(raw);}}
static int fingerprint(const char *raw,char hash[65])
{unsigned char digest[32];if(mbedtls_sha256((const unsigned char *)raw,strlen(raw),digest,0))return -EIO;
  for (unsigned i = 0; i < 32; i++)
    snprintf(hash + i * 2, 3, "%02x", digest[i]);
  return 0;
}
#define VIEW_STATE CONFIG_BUFFER_VELA_DATA_DIR "/event-journal/remote-view-state.json"
struct view_state {char active[33],building[33];bool dirty,changed;};
/* Terminal file-state lock. No journal, capture, transport or model API may be
 * called while holding it. Journal readers may take this lock in that order. */
static pthread_mutex_t view_lock=PTHREAD_MUTEX_INITIALIZER;
static bool view_fenced;
static bool generation_valid(const char *id)
{if(strlen(id)!=32)return false;for(unsigned i=0;i<32;i++)if(!strchr("0123456789abcdef",id[i]))return false;return true;}
static int state_read(struct view_state *out)
{
  memset(out,0,sizeof(*out));out->dirty=true;
  errno=0;cJSON *j=buffer_agent_read_json_plain(VIEW_STATE,2048);
  if(!j)return errno==ENOENT||errno==EINVAL||errno==EIO?0:errno?-errno:0;
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,"version"),*dirty=cJSON_GetObjectItemCaseSensitive(j,"dirty"),*changed=cJSON_GetObjectItemCaseSensitive(j,"changed");
  const cJSON *active=cJSON_GetObjectItemCaseSensitive(j,"active"),*building=cJSON_GetObjectItemCaseSensitive(j,"building");
  int ret=!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=5||!cJSON_IsNumber(v)||v->valuedouble!=1||
    !cJSON_IsString(active)||!cJSON_IsString(building)||!cJSON_IsBool(dirty)||!cJSON_IsBool(changed)||
    (*active->valuestring&&!generation_valid(active->valuestring))||(*building->valuestring&&!generation_valid(building->valuestring))||
    (*active->valuestring&&!strcmp(active->valuestring,building->valuestring))?-EIO:0;
  if(!ret){snprintf(out->active,33,"%s",active->valuestring);snprintf(out->building,33,"%s",building->valuestring);
    out->dirty=cJSON_IsTrue(dirty);out->changed=cJSON_IsTrue(changed);}
  cJSON_Delete(j);if(ret){memset(out,0,sizeof(*out));out->dirty=true;}return 0;
}
static int state_write(const struct view_state *state)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"version",1)&&
    cJSON_AddStringToObject(j,"active",state->active)&&cJSON_AddStringToObject(j,"building",state->building)&&
    cJSON_AddBoolToObject(j,"dirty",state->dirty)&&cJSON_AddBoolToObject(j,"changed",state->changed);
  int ret=built?buffer_agent_atomic_json(VIEW_STATE,j):-ENOMEM;cJSON_Delete(j);return ret;
}
int buffer_source_view_rebuild_needed(bool *needed,bool *building)
{
  if (!needed || !building)
    return -EINVAL;
  pthread_mutex_lock(&view_lock);
  struct view_state state;
  int ret = state_read(&state);
  if(!ret&&state.active[0]){
    char path[320];int n=snprintf(path,sizeof(path),VIEWS "/%s",state.active);struct stat st;
    if(n<0||(size_t)n>=sizeof(path))ret=-ENAMETOOLONG;
    else if(stat(path,&st)){if(errno==ENOENT)state.dirty=true;else ret=-errno;}
    else if(!S_ISDIR(st.st_mode))state.dirty=true;
  }
  *building=!ret&&state.building[0];*needed=!ret&&(view_fenced||state.dirty||!state.active[0]);pthread_mutex_unlock(&view_lock);return ret;
}
int buffer_source_view_rebuild_begin(void)
{
  pthread_mutex_lock(&view_lock);bool was_fenced=view_fenced;struct view_state state;int ret=state_read(&state);unsigned char entropy[16];
  if(!ret)ret=buffer_entropy_read(entropy,sizeof(entropy));
  if(!ret){for(unsigned i=0;i<16;i++)snprintf(state.building+2*i,3,"%02x",entropy[i]);
    if(!strcmp(state.active,state.building))ret=-EIO;}
  memset(entropy,0,sizeof(entropy));
  char path[320];if(!ret){int n=snprintf(path,sizeof(path),VIEWS "/%s",state.building);
    ret=n<0||(size_t)n>=sizeof(path)?-ENAMETOOLONG:buffer_agent_private_directory(VIEWS);
    if(!ret){struct stat st;if(!stat(path,&st))ret=-EEXIST;else if(errno!=ENOENT)ret=-errno;}
    if(!ret)ret=buffer_agent_private_directory(path);
    if(!ret)ret=buffer_agent_sync_parent(path);}
  if(!ret){view_fenced=true;state.dirty=true;state.changed=false;ret=state_write(&state);}
  bool notify=!was_fenced&&view_fenced;pthread_mutex_unlock(&view_lock);
  if(notify){buffer_agent_projection_changed();buffer_calendar_invalidate();}return ret;
}
int buffer_source_view_rebuild_request(void)
{
  pthread_mutex_lock(&view_lock);bool notify=!view_fenced;view_fenced=true;
  struct view_state state;int ret=state_read(&state);
  if(!ret){state.dirty=true;state.building[0]=0;state.changed=false;ret=state_write(&state);}
  pthread_mutex_unlock(&view_lock);
  if(notify){buffer_agent_projection_changed();buffer_calendar_invalidate();}return ret;
}
int buffer_source_view_causal_changed(bool invalid)
{
  pthread_mutex_lock(&view_lock);bool was_fenced=view_fenced;struct view_state state;int ret=state_read(&state);
  if(invalid)view_fenced=true;
  if(!ret){bool dirty=state.dirty||invalid;bool changed=state.changed||state.building[0];
    if(dirty!=state.dirty||changed!=state.changed){state.dirty=dirty;state.changed=changed;ret=state_write(&state);}}
  bool notify=!was_fenced&&view_fenced;pthread_mutex_unlock(&view_lock);
  if(notify){buffer_agent_projection_changed();buffer_calendar_invalidate();}return ret;
}
/* 1=published, 0=causal metadata changed during this pass; start a fresh pass.
 * The control rename is the sole commit point, never a per-document marker. */
int buffer_source_view_rebuild_finish(void)
{
  pthread_mutex_lock(&view_lock);struct view_state state;int ret=state_read(&state);
  if(!ret&&!state.building[0])ret=-EINVAL;
  if(!ret&&!state.changed){snprintf(state.active,33,"%s",state.building);state.building[0]=0;state.dirty=false;
    ret=state_write(&state);if(!ret){view_fenced=false;ret=1;}}
  pthread_mutex_unlock(&view_lock);return ret;
}
/* Only derived generations are removed. At most four leaf files per call;
 * active/building are protected by the same lock used by all overlay reads. */
int buffer_source_view_cleanup(void)
{
  pthread_mutex_lock(&view_lock);struct view_state state;int ret=state_read(&state);DIR *root=ret?NULL:opendir(VIEWS);
  if (!root && !ret && errno != ENOENT)
    ret = -errno;
  unsigned removed = 0;
  while(root&&!ret&&removed<4){errno=0;struct dirent *entry=readdir(root);if(!entry){if(errno)ret=-errno;break;}
    const char *id=entry->d_name;bool legacy=!strcmp(id,"card")||!strcmp(id,"calendar");
    if (!legacy && !generation_valid(id))
      continue;
    if (!strcmp(id, state.active) || !strcmp(id, state.building))
      continue;
    char dirpath[320];int n=snprintf(dirpath,sizeof(dirpath),VIEWS "/%s",id);if(n<0||(size_t)n>=sizeof(dirpath)){ret=-ENAMETOOLONG;break;}
    unsigned kinds=legacy?1:2;
    for(unsigned kind=0;!ret&&kind<kinds&&removed<4;kind++){
      char folder[320];n=legacy?snprintf(folder,sizeof(folder),"%s",dirpath):snprintf(folder,sizeof(folder),"%s/%s",dirpath,kind?"calendar":"card");
      if(n<0||(size_t)n>=sizeof(folder)){ret=-ENAMETOOLONG;break;}
      DIR *dir=opendir(folder);if(!dir){if(errno!=ENOENT)ret=-errno;continue;}
      while(!ret&&removed<4){errno=0;struct dirent *file=readdir(dir);if(!file){if(errno)ret=-errno;break;}
        if(!strcmp(file->d_name,".")||!strcmp(file->d_name,".."))continue;
        size_t size=strlen(file->d_name);if(!((size>=6&&!strcmp(file->d_name+size-5,".json"))||(size>=10&&!strcmp(file->d_name+size-9,".json.tmp"))))continue;
        char target[320];n=snprintf(target,sizeof(target),"%s/%s",folder,file->d_name);
        if(n<0||(size_t)n>=sizeof(target)){ret=-ENAMETOOLONG;break;}
        if(unlink(target)&&errno!=ENOENT){ret=-errno;break;}removed++;ret=buffer_agent_sync_parent(target);
      }
      if(closedir(dir)&&!ret)ret=-errno;
      if(!ret&&rmdir(folder)&&errno!=ENOENT&&errno!=ENOTEMPTY)ret=-errno;
    }
    if(!ret&&!legacy&&rmdir(dirpath)&&errno!=ENOENT&&errno!=ENOTEMPTY)ret=-errno;
    if(!ret)ret=buffer_agent_sync_parent(dirpath);
  }
  if (root && closedir(root) && !ret)
    ret = -errno;
  pthread_mutex_unlock(&view_lock);
  return ret;
}
static int view_path(const char *path,const char *generation,char out[320],const char **kind)
{
  const char *dirs[]={CONFIG_BUFFER_VELA_DATA_DIR "/replicas/",CONFIG_BUFFER_VELA_DATA_DIR "/calendars/"};
  for(unsigned i=0;i<2;i++)if(!strncmp(path,dirs[i],strlen(dirs[i]))){
    const char *name=path+strlen(dirs[i]);size_t n=strlen(name);char id[BUFFER_ID_SIZE];
    if(n<6||n-5>=sizeof(id)||strcmp(name+n-5,".json"))return -EINVAL;
    memcpy(id,name,n-5);id[n-5]=0;if(!buffer_agent_valid_id(id))return -EINVAL;
    *kind=i?"calendar":"card";
    int len=snprintf(out,320,VIEWS "/%s/%s/%s.json",generation,*kind,id);return len<0||len>=320?-ENAMETOOLONG:0;
  }
  return -EINVAL;
}
static int load_view_at(const char *path,const char *generation,cJSON **record)
{
  *record=NULL;if(!generation_valid(generation))return -ENOENT;char file[320];const char *kind;int ret=view_path(path,generation,file,&kind);if(ret)return ret;
  errno=0;cJSON *saved=buffer_agent_read_json_plain(file,VIEW_BYTES);if(!saved)return errno?-errno:-EIO;
  const cJSON *body=cJSON_GetObjectItemCaseSensitive(saved,"record");char *raw=cJSON_PrintUnformatted(body),hash[65];
  ret=raw?fingerprint(raw,hash):-ENOMEM;
  if(!ret&&(!cJSON_IsObject(saved)||cJSON_GetArraySize(saved)!=2||!cJSON_IsObject(body)||strcmp(hash,text(saved,"sha256"))))ret=-EIO;
  const cJSON *doc=cJSON_GetObjectItemCaseSensitive(body,"crdt");
  const cJSON *origin=cJSON_GetObjectItemCaseSensitive(body,"origin");
  if(!ret&&(buffer_crdt_validate(doc)||strcmp(text(doc,"id"),text(body,"card_id"))||strcmp(text(doc,"kind"),kind)||
    !cJSON_IsString(origin)||(*origin->valuestring&&!buffer_source_peer_valid(origin->valuestring))||
    !cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(body,"source_projection"))))ret=-EIO;
  char expected[320];if(!ret){int n=snprintf(expected,sizeof(expected),VIEWS "/%s/%s/%s.json",generation,kind,text(doc,"id"));
    if(n<0||(size_t)n>=sizeof(expected)||strcmp(expected,file))ret=-EIO;}
  if(!ret){*record=cJSON_Duplicate(body,true);if(!*record)ret=-ENOMEM;}
  release(raw);cJSON_Delete(saved);return ret;
}
static int load_view(const char *path,cJSON **record)
{
  *record=NULL;pthread_mutex_lock(&view_lock);struct view_state state;int ret=state_read(&state);
  if(!ret)ret=view_fenced||state.dirty||!state.active[0]?-ENOENT:load_view_at(path,state.active,record);
  pthread_mutex_unlock(&view_lock);
  if(ret==-EIO||ret==-EINVAL||ret==-EFBIG){int repair=buffer_source_view_rebuild_request();return repair?repair:-ENOENT;}
  return ret;
}
static int load_publish_view(const char *path,const char *generation,cJSON **record)
{
  int ret=load_view_at(path,generation,record);
  if(ret==-EIO||ret==-EINVAL||ret==-EFBIG){int repair=buffer_source_view_rebuild_request();return repair?repair:-EAGAIN;}
  return ret;
}
int buffer_source_view_exists(const char *path)
{cJSON *record=NULL;int ret=load_view(path,&record);cJSON_Delete(record);return ret?ret:1;}
static int serialize(const cJSON *record,char **raw,size_t *length)
{
  *raw=cJSON_PrintUnformatted(record);if(!*raw)return -ENOMEM;*length=strlen(*raw);
  if(*length>BUFFER_AGENT_JSON_BYTES){release(*raw);*raw=NULL;*length=0;return -EFBIG;}return 0;
}
static bool string_field(const cJSON *values,const char *key,size_t maximum,bool optional)
{
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(values,key);
  return optional&&(!v||cJSON_IsNull(v))?true:cJSON_IsString(v)&&strlen(v->valuestring)<=maximum;
}
/* Calendar instants may precede the Unix epoch; counters remain unsigned. */
static bool calendar_instant(const cJSON *value)
{return cJSON_IsNumber(value)&&isfinite(value->valuedouble)&&fabs(value->valuedouble)<=9007199254740991.0&&floor(value->valuedouble)==value->valuedouble;}
static bool counter(const cJSON *value)
{return cJSON_IsNumber(value)&&isfinite(value->valuedouble)&&value->valuedouble>=0&&value->valuedouble<=9007199254740991.0&&floor(value->valuedouble)==value->valuedouble;}
static bool reference_id(const char *value)
{
  if(!value||!value[0]||strlen(value)>160)return false;
  for(const char *p=value;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||strchr("_.:-",*p)))return false;
  return true;
}
int buffer_source_view_validate(const cJSON *document)
{
  if(buffer_crdt_validate(document))return errno==ENOMEM?-ENOMEM:-EINVAL;
  if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(document,"deleted")))return 0;
  cJSON *values=buffer_crdt_values(document);if(!values)return -ENOMEM;int ret=0;
  bool calendar=!strcmp(text(document,"kind"),"calendar");
  if(!string_field(values,"title",1024,false)||!string_field(values,"location",24000,true))ret=-EINVAL;
  if(!calendar){
    const char *kinds[]={"task_card","spark_card","question_card","care_card","weekly_card","rabbit_hole_card",
      "project_card","article_card","code_card","unclassified_card"};bool known=false;
    for(unsigned i=0;i<sizeof(kinds)/sizeof(kinds[0]);i++)if(!strcmp(text(values,"kind"),kinds[i]))known=true;
    if(!known||strcmp(text(values,"card_id"),text(document,"id"))||!text(values,"state")[0]||
      !string_field(values,"state",40,false)||!string_field(values,"summary",16384,false)||
      !counter(cJSON_GetObjectItemCaseSensitive(values,"created_at")))ret=-EINVAL;
    const char *optional[]={"answer","research_notes","text","context_summary"};
    for(unsigned i=0;i<4;i++)if(!string_field(values,optional[i],24000,true))ret=-EINVAL;
    const cJSON *capture=cJSON_GetObjectItemCaseSensitive(values,"capture_id");
    if(capture&&!cJSON_IsNull(capture)&&(!cJSON_IsString(capture)||!reference_id(capture->valuestring)))ret=-EINVAL;
  }else if(!text(values,"title")[0])ret=-EINVAL;
  const cJSON *schedule=cJSON_GetObjectItemCaseSensitive(values,"schedule");
  if(calendar|| (schedule&&!cJSON_IsNull(schedule))){
    const cJSON *start=cJSON_GetObjectItemCaseSensitive(schedule,"start"),*end=cJSON_GetObjectItemCaseSensitive(schedule,"end");
    if(!cJSON_IsObject(schedule)||cJSON_GetArraySize(schedule)!=4||!calendar_instant(start)||!calendar_instant(end)||
      end->valuedouble<start->valuedouble||!cJSON_IsBool(cJSON_GetObjectItemCaseSensitive(schedule,"all_day"))||
      !string_field(schedule,"zone",80,false)||!text(schedule,"zone")[0])ret=-EINVAL;
  }
  const cJSON *actions=cJSON_GetObjectItemCaseSensitive(values,"actions"),*entry;
  if(actions){if(!cJSON_IsArray(actions)||cJSON_GetArraySize(actions)>64)ret=-EINVAL;
    cJSON_ArrayForEach(entry,actions)if(!cJSON_IsString(entry)||!buffer_agent_valid_id(entry->valuestring)||strlen(entry->valuestring)>64)ret=-EINVAL;}
  const cJSON *conversation=cJSON_GetObjectItemCaseSensitive(values,"conversation");
  if(conversation){if(!cJSON_IsArray(conversation)||cJSON_GetArraySize(conversation)>8)ret=-EINVAL;
    cJSON_ArrayForEach(entry,conversation)if(!cJSON_IsObject(entry)||!reference_id(text(entry,"capture_id"))||
      !string_field(entry,"question",8192,false)||!string_field(entry,"answer",8192,false)||
      !counter(cJSON_GetObjectItemCaseSensitive(entry,"created_at")))ret=-EINVAL;}
  cJSON_Delete(values);return ret;
}
static cJSON *merge_record(const cJSON *old,const cJSON *incoming,const char *peer,int *result)
{
  *result=0;const cJSON *previous=cJSON_GetObjectItemCaseSensitive(old,"crdt");
  if(old&&!previous){
    if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(incoming,"deleted"))&&buffer_source_peer_valid(peer)&&!strcmp(text(old,"origin"),peer))old=NULL;
    else {*result=-EEXIST;return NULL;}
  }
  if(old&&buffer_crdt_validate(previous)){*result=errno==ENOMEM?-ENOMEM:-EIO;return NULL;}
  if(old&&buffer_source_peer_valid(text(old,"origin"))&&strcmp(text(old,"origin"),peer)){*result=-EEXIST;return NULL;}
  cJSON *merged=buffer_crdt_merge(previous,incoming);
  if(!merged){*result=errno==EEXIST||errno==EINVAL?-EEXIST:-ENOMEM;return NULL;}
  *result=buffer_source_view_validate(merged);if(*result){if(*result==-EINVAL)*result=-EEXIST;cJSON_Delete(merged);return NULL;}
  cJSON *record=buffer_replica_crdt_record(merged,old);
  const char *owner=!old||!strcmp(text(old,"origin"),peer)||cJSON_Compare(merged,incoming,true)?peer:"";
  if(record){cJSON_DeleteItemFromObjectCaseSensitive(record,"origin");
    if(!cJSON_AddStringToObject(record,"origin",owner)||!cJSON_AddBoolToObject(record,"source_projection",true)||
      !cJSON_Compare(cJSON_GetObjectItemCaseSensitive(record,"crdt"),merged,true)){
      cJSON_Delete(record);record=NULL;}}
  cJSON_Delete(merged);if(!record)*result=-ENOMEM;return record;
}
int buffer_source_view_overlay(const char *path,const char *local,size_t limit,char **raw,size_t *length,bool *changed)
{
  *raw=NULL;*length=0;if(changed)*changed=false;cJSON *remote=NULL,*own=NULL,*merged=NULL;int ret=load_view(path,&remote);if(ret)return ret;
  if(local){own=buffer_agent_parse_json(local);if(!own){ret=-EIO;goto done;}
    const cJSON *doc=cJSON_GetObjectItemCaseSensitive(own,"crdt");
    if(!doc){
      const cJSON *remote_doc=cJSON_GetObjectItemCaseSensitive(remote,"crdt");
      if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(remote_doc,"deleted"))&&buffer_source_peer_valid(text(remote,"origin"))&&
        !strcmp(text(own,"origin"),text(remote,"origin"))){merged=cJSON_Duplicate(remote,true);if(!merged)ret=-ENOMEM;goto encoded;}
      ret=-EEXIST;goto done;
    }
    if(buffer_crdt_validate(doc)){ret=errno==ENOMEM?-ENOMEM:-EIO;goto done;}
    merged=merge_record(remote,doc,text(remote,"origin"),&ret);if(ret)goto done;
  }else {merged=cJSON_Duplicate(remote,true);if(!merged){ret=-ENOMEM;goto done;}}
encoded:
  if (!ret)
    ret = serialize(merged, raw, length);
  if (!ret && *length > limit)
    ret = -EFBIG;
  if(!ret){char *current=NULL;size_t bytes=0;ret=buffer_agent_read_bytes(path,BUFFER_AGENT_JSON_BYTES,&current,&bytes);
    if(ret==-EACCES){struct stat st;
      if(!lstat(path,&st)&&S_ISREG(st.st_mode)&&!(st.st_mode&0077)&&
          (st.st_size<=0||(uint64_t)st.st_size>BUFFER_AGENT_JSON_BYTES))ret=-ENOENT;}
    if(ret==-ENOENT||(!ret&&(bytes!=*length||memcmp(current,*raw,bytes)))){
      ret=buffer_agent_atomic_bytes(path,*raw,*length,BUFFER_AGENT_JSON_BYTES);
      if(!ret&&changed)*changed=true;
    }
    release(current);}
done:
  cJSON_Delete(remote);cJSON_Delete(own);cJSON_Delete(merged);
  if(ret){release(*raw);*raw=NULL;*length=0;}return ret;
}
int buffer_source_view_publish(const char *peer,const cJSON *document,bool already_applied)
{
  if (!buffer_source_peer_valid(peer))
    return -EINVAL;
  int valid = buffer_source_view_validate(document);
  if (valid)
    return valid;
  struct view_state state;pthread_mutex_lock(&view_lock);int control=state_read(&state);state.dirty=state.dirty||view_fenced;pthread_mutex_unlock(&view_lock);
  if (control)
    return control;
  const char *generation = state.building[0] ? state.building : state.active;
  if(!generation_valid(generation)||(!state.building[0]&&state.dirty))return -EAGAIN;
  const char *kind=text(document,"kind"),*id=text(document,"id");char path[320],file[320];
  int n=snprintf(path,sizeof(path),CONFIG_BUFFER_VELA_DATA_DIR "/%s/%s.json",!strcmp(kind,"card")?"replicas":"calendars",id);
  if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
  char other[320];n=snprintf(other,sizeof(other),CONFIG_BUFFER_VELA_DATA_DIR "/%s/%s.json",!strcmp(kind,"card")?"calendars":"replicas",id);
  if(n<0||(size_t)n>=sizeof(other))return -ENAMETOOLONG;
  bool other_deleted=false;int other_fact=buffer_source_projection_local_deleted(other,&other_deleted);
  if (other_fact)
    return other_fact;
  if (other_deleted)
    return -EEXIST;
  errno=0;cJSON *collision=buffer_source_projection_local_read(other,BUFFER_AGENT_JSON_BYTES);
  if(collision){cJSON_Delete(collision);return -EEXIST;}if(errno!=ENOENT)return errno?-errno:-EIO;
  cJSON *other_record=NULL;int other_view=load_publish_view(other,generation,&other_record);cJSON_Delete(other_record);if(!other_view)other_view=1;if(other_view==1)return -EEXIST;if(other_view!=-ENOENT)return other_view;
  /* A local remove is an absorbing historical tombstone, not an absent view. */
  {bool deleted=false;int ret=buffer_source_projection_local_deleted(path,&deleted);if(ret)return ret;if(deleted)return 0;}
  errno=0;cJSON *old=buffer_source_projection_local_read(path,BUFFER_AGENT_JSON_BYTES);
  int ret=old?0:errno==ENOENT?0:errno?-errno:-EIO;
  cJSON *overlay=NULL;
  if(!ret){int found=load_publish_view(path,generation,&overlay);if(found&&found!=-ENOENT)ret=found;
    if(!ret&&overlay){
      if(old){
        const cJSON *local_doc=cJSON_GetObjectItemCaseSensitive(old,"crdt");
        if(!local_doc&&cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(document,"deleted"))&&
          !strcmp(text(old,"origin"),peer)&&!strcmp(text(overlay,"origin"),peer)){cJSON_Delete(old);old=overlay;overlay=NULL;}
        else {cJSON *combined=merge_record(overlay,local_doc,text(overlay,"origin"),&ret);
          if(!ret){cJSON_Delete(old);old=combined;}}
      }
      else {old=overlay;overlay=NULL;}
    }}
  cJSON_Delete(overlay);
  cJSON *record=ret?NULL:merge_record(old,document,peer,&ret);char *raw=NULL,*saved=NULL;size_t bytes=0;bool changed=false;
  if(!ret)ret=serialize(record,&raw,&bytes);
  const char *ignored;if(!ret)ret=view_path(path,generation,file,&ignored);
  if(!ret)ret=buffer_agent_private_directory(VIEWS);
  char parent[320];if(!ret){n=snprintf(parent,sizeof(parent),VIEWS "/%s/%s",generation,kind);ret=n<0||(size_t)n>=sizeof(parent)?-ENAMETOOLONG:buffer_agent_private_directory(parent);if(!ret)ret=buffer_agent_sync_parent(parent);}
  cJSON *wrapper=NULL;char hash[65];
  if(!ret)ret=fingerprint(raw,hash);
  if(!ret){wrapper=cJSON_CreateObject();cJSON *copy=cJSON_Duplicate(record,true);
    if(!wrapper||!copy){cJSON_Delete(copy);ret=-ENOMEM;}
    else if(!cJSON_AddItemToObject(wrapper,"record",copy)){cJSON_Delete(copy);ret=-ENOMEM;}
    else if(!cJSON_AddStringToObject(wrapper,"sha256",hash))ret=-ENOMEM;}
  if(!ret){saved=cJSON_PrintUnformatted(wrapper);if(!saved)ret=-ENOMEM;}
  if(!ret){cJSON *previous=NULL;int found=load_publish_view(path,generation,&previous);
    bool same=!found&&cJSON_Compare(previous,record,true);cJSON_Delete(previous);
    if(found&&found!=-ENOENT)ret=found;
    if(!ret&&(!same||!already_applied)){ret=buffer_agent_atomic_bytes(file,saved,strlen(saved),VIEW_BYTES);if(!ret&&!same)changed=true;}}
  if(!ret){n=snprintf(parent,sizeof(parent),CONFIG_BUFFER_VELA_DATA_DIR "/%s",!strcmp(kind,"card")?"replicas":"calendars");
    ret=n<0||(size_t)n>=sizeof(parent)?-ENAMETOOLONG:buffer_agent_private_directory(parent);if(!ret)ret=buffer_agent_sync_parent(parent);}
  if(!ret){char *current=NULL;size_t length=0;ret=buffer_agent_read_bytes(path,BUFFER_AGENT_JSON_BYTES,&current,&length);
    bool different=ret==-ENOENT||(!ret&&(length!=bytes||memcmp(current,raw,bytes)));
    bool absent=ret==-ENOENT;
    if((state.building[0]&&absent)||(!state.building[0]&&(different||(!ret&&!already_applied)))){
      ret=buffer_agent_atomic_bytes(path,raw,bytes,BUFFER_AGENT_JSON_BYTES);if(!ret&&different)changed=true;
    }else if(state.building[0]&&!ret)ret=0;
    release(current);}
  cJSON_Delete(old);cJSON_Delete(record);cJSON_Delete(wrapper);release(raw);release(saved);return ret?ret:!state.building[0]&&changed?1:0;
}
