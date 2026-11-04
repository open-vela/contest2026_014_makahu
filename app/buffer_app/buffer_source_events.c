/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_events.h"
#include "buffer_source_envelope.h"
#include "buffer_source_remote.h"
#include "buffer_source_view.h"
#include "buffer_entropy.h"
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define ROOT CONFIG_BUFFER_VELA_DATA_DIR "/event-journal"
#define META ROOT "/source.json"
#define INTENT ROOT "/pending.json"
#define RECORDS CONFIG_BUFFER_VELA_DATA_DIR "/records/"
#define QUEUE CONFIG_BUFFER_VELA_DATA_DIR "/queue/"
#define MAX_COUNTER 9007199254740991ULL
static pthread_mutex_t journal_lock=PTHREAD_MUTEX_INITIALIZER;
static cJSON *head;
static bool initialized, recovery_required=true, projections_scanned, migration_pending;
static bool bootstrap_complete;
static bool full_readiness;
static int migrate_projections_locked(void);
static int local_read_locked(uint64_t sequence,char **raw,size_t *length);
static int rebuild_capture_index_locked(const char *id);
static int rebuild_projection_index_locked(const char *kind,const char *id);
static int request_rebuild_locked(const char *kind,const char *id,bool capture,uint64_t *sequence);
static void advance_rebuild_locked(void);
static bool rebuild_pending_locked(void);
static int assistant_operation_at_locked(uint64_t seq,const char *id,cJSON **operation,cJSON **request);
static int focus_operation_at_locked(uint64_t seq,const char *id,cJSON **request,cJSON **result);
/* A RAM-only negative hint. Coverage proves every committed entity identity
 * was inserted; collisions only cause a normal verified source scan. Neither
 * this filter nor its cursor is a fact, an index replacement or a receipt. */
#define CAPTURE_FILTER_BYTES 16384
static unsigned char capture_filter[CAPTURE_FILTER_BYTES];
static uint64_t capture_filter_covered;
static uint64_t focus_filter_covered,assistant_filter_covered;
static bool capture_filter_contains(const char *kind,const char *id,bool insert)
{
  uint32_t a=2166136261u,b=5381u;bool present=true;
  for(const unsigned char *p=(const unsigned char *)kind;*p;p++){a=(a^*p)*16777619u;b=b*33u+*p;}
  a*=16777619u;b*=33u; // namespace separator
  for(const unsigned char *p=(const unsigned char *)id;*p;p++){a=(a^*p)*16777619u;b=b*33u+*p;}
  b|=1u;
  for(unsigned i=0;i<4;i++){
    uint32_t bit=(a+i*b)%(CAPTURE_FILTER_BYTES*8u);unsigned char mask=(unsigned char)(1u<<(bit%8));
    if(!(capture_filter[bit/8]&mask))present=false;
    if(insert)capture_filter[bit/8]|=mask;
  }
  return present;
}
static void capture_filter_add_event(const cJSON *event)
{
  const cJSON *capture=cJSON_GetObjectItemCaseSensitive(event,"capture_id");
  if(cJSON_IsString(capture)&&capture->valuestring[0])(void)capture_filter_contains("capture",capture->valuestring,true);
  const cJSON *payload=cJSON_GetObjectItemCaseSensitive(event,"payload"),
    *kind=cJSON_GetObjectItemCaseSensitive(payload,"projection_kind"),*id=cJSON_GetObjectItemCaseSensitive(payload,"entity_id");
  if(cJSON_IsString(kind)&&kind->valuestring[0]&&cJSON_IsString(id)&&id->valuestring[0])
    (void)capture_filter_contains(kind->valuestring,id->valuestring,true);
}
static void capture_filter_observed(uint64_t sequence,const cJSON *event)
{
  capture_filter_add_event(event);
  if(sequence==capture_filter_covered+1)capture_filter_covered=sequence;
  const cJSON *kind=cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(event,"payload"),"projection_kind");
  // Old focus snapshots need their nested operation identities checked first.
  if(sequence==focus_filter_covered+1&&(!cJSON_IsString(kind)||strcmp(kind->valuestring,"focus")))focus_filter_covered=sequence;
  if(sequence==assistant_filter_covered+1&&(!cJSON_IsString(kind)||strcmp(kind->valuestring,"assistant")))assistant_filter_covered=sequence;
}

static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j,const char *key,uint64_t *out)
{
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);
  if(!cJSON_IsNumber(v)||!isfinite(v->valuedouble)||v->valuedouble<0||v->valuedouble>MAX_COUNTER||floor(v->valuedouble)!=v->valuedouble)return false;
  *out=(uint64_t)v->valuedouble;return true;
}
static void release(char *raw)
{if(raw){volatile char *p=raw;size_t n=strlen(raw);while(n--)*p++=0;free(raw);}}
static bool utf8(const char *raw)
{
  const unsigned char *p=(const unsigned char *)raw;size_t n=strlen(raw),i=0;
  while(i<n){uint32_t c=p[i++];unsigned tail=0;uint32_t minimum=0;
    if(c<128)continue;
    if(c>=0xc2&&c<=0xdf){tail=1;c&=31;minimum=128;}
    else if(c>=0xe0&&c<=0xef){tail=2;c&=15;minimum=2048;}
    else if(c>=0xf0&&c<=0xf4){tail=3;c&=7;minimum=65536;}else return false;
    if (n - i < tail)
      return false;
    while (tail--) {
      unsigned next = p[i++];
      if ((next & 0xc0) != 0x80)
        return false;
      c = (c << 6) | (next & 63);
    }
    if(c<minimum||c>0x10ffff||(c>=0xd800&&c<=0xdfff))return false;
  }return true;
}
static bool hex_digest(const char *value)
{if(strlen(value)!=64)return false;for(size_t i=0;i<64;i++)if(!strchr("0123456789abcdef",value[i]))return false;return true;}
static bool event_type(const char *value)
{
  size_t n=strlen(value);if(!n||n>96||!((value[0]>='A'&&value[0]<='Z')||(value[0]>='a'&&value[0]<='z')))return false;
  for (size_t i = 1; i < n; i++)
    if (!((value[i] >= 'A' && value[i] <= 'Z') ||
          (value[i] >= 'a' && value[i] <= 'z') ||
          (value[i] >= '0' && value[i] <= '9') || strchr("_.-", value[i])))
      return false;
  return true;
}
static int digest(const char *raw,size_t length,char hex[65])
{
  unsigned char bytes[32];if(mbedtls_sha256((const unsigned char *)raw,length,bytes,0))return -EIO;
  for (size_t i = 0; i < 32; i++)
    snprintf(hex + 2 * i, 3, "%02x", bytes[i]);
  return 0;
}
static bool add_copy(cJSON *object,const char *key,const cJSON *value)
{cJSON *copy=cJSON_Duplicate(value,true);if(!copy)return false;if(cJSON_AddItemToObject(object,key,copy))return true;cJSON_Delete(copy);return false;}
static int json_write(const char *path,const cJSON *j,size_t limit)
{
  char *raw=cJSON_PrintUnformatted(j);if(!raw)return -ENOMEM;
  int ret=buffer_agent_atomic_bytes(path,raw,strlen(raw),limit);release(raw);return ret;
}
static int confirm_receipt(const char *path,const cJSON *j)
{
#ifdef __NuttX__
  /* A rename may be visible in RAM after cfg.sync failed. LittleFS does not
   * offer directory-fd fsync: recommit identical transport metadata to retry
   * the real backend barrier before reporting a durable duplicate/cursor. */
  return json_write(path,j,4096);
#else
  (void)j;return buffer_agent_sync_parent(path);
#endif
}
static int path_event(char *path,size_t capacity,uint64_t seq)
{int n=snprintf(path,capacity,ROOT "/local/%020llu.json",(unsigned long long)seq);return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;}
static int path_digest(char *path,size_t capacity,uint64_t seq)
{int n=snprintf(path,capacity,ROOT "/local/%020llu.sha256",(unsigned long long)seq);return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;}
static int verify_digest(uint64_t seq,const char *fingerprint,bool create)
{
  char path[320],*saved=NULL;size_t bytes=0;
  int ret=path_digest(path,sizeof(path),seq);if(ret)return ret;
  ret=buffer_agent_read_bytes(path,64,&saved,&bytes);
  if(!ret){ret=bytes==64&&!memcmp(saved,fingerprint,64)?buffer_agent_sync_parent(path):-EIO;release(saved);return ret;}
  if(ret==-ENOENT&&create)return buffer_agent_atomic_bytes(path,fingerprint,64,64);
  return ret;
}
static int path_capture(char *path,size_t capacity,const char *id)
{
  if(!buffer_agent_valid_id(id))return -EINVAL;
  int n=snprintf(path,capacity,ROOT "/captures/%s.json",id);return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;
}
static bool record_id(const char *path,char *id)
{
  if(!path||strncmp(path,RECORDS,strlen(RECORDS)))return false;
  const char *name=path+strlen(RECORDS);size_t n=strlen(name);
  if(n<=5||n-5>=BUFFER_ID_SIZE||strcmp(name+n-5,".json"))return false;
  memcpy(id,name,n-5);id[n-5]=0;return buffer_agent_valid_id(id);
}
bool buffer_source_is_record_path(const char *path)
{char id[BUFFER_ID_SIZE];return record_id(path,id);}
/* Paths are derived from a closed set of projection types. Neither a pending
 * file nor an incoming history payload can choose an arbitrary destination. */
static size_t projection_limit(const char *kind)
{return kind&&!strcmp(kind,"input_result")?BUFFER_SOURCE_INPUT_RESULT_BYTES:BUFFER_AGENT_JSON_BYTES;}
static int projection_target(char *path,size_t size,const char *kind,const char *id)
{
  if(!buffer_agent_valid_id(id))return -EINVAL;
  int n;
  if(!strcmp(kind,"replica"))n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/replicas/%s.json",id);
  else if(!strcmp(kind,"input_result"))n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/input-results/%s.json",id);
  else if(!strcmp(kind,"calendar"))n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/calendars/%s.json",id);
  else if(!strcmp(kind,"conversation"))n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/conversations/%s.json",id);
  else if(!strcmp(kind,"followup"))n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/records/%s.context",id);
  else {
    if(strcmp(id,"state"))return -EINVAL;
    const char *name=!strcmp(kind,"focus")?"focus.json":!strcmp(kind,"mode")?"mode.conf":
      !strcmp(kind,"pending_action")?"pending.action":!strcmp(kind,"pending_mode")?"pending.mode":
      !strcmp(kind,"assistant")?"assistant.json":!strcmp(kind,"focus_config")?"focus.conf":
      !strcmp(kind,"focus_runtime")?"focus_runtime.json":!strcmp(kind,"research")?"research.json":NULL;
    if(!name)return -EINVAL;
    n=snprintf(path,size,CONFIG_BUFFER_VELA_DATA_DIR "/%s",name);
  }
  return n<0||(size_t)n>=size?-ENAMETOOLONG:0;
}
static bool projection_id(const char *path,const char **kind,char id[BUFFER_ID_SIZE])
{
  if(!path)return false;
  const char *prefixes[]={CONFIG_BUFFER_VELA_DATA_DIR "/replicas/",CONFIG_BUFFER_VELA_DATA_DIR "/conversations/",RECORDS,CONFIG_BUFFER_VELA_DATA_DIR "/calendars/",CONFIG_BUFFER_VELA_DATA_DIR "/input-results/"};
  const char *dynamic_kinds[]={"replica","conversation","followup","calendar","input_result"},*suffixes[]={".json",".json",".context",".json",".json"};
  for(size_t i=0;i<sizeof(prefixes)/sizeof(prefixes[0]);i++)if(!strncmp(path,prefixes[i],strlen(prefixes[i]))){
    const char *name=path+strlen(prefixes[i]);size_t n=strlen(name),suffix=strlen(suffixes[i]);
    if(n<=suffix||n-suffix>=BUFFER_ID_SIZE||strcmp(name+n-suffix,suffixes[i]))continue;
    memcpy(id,name,n-suffix);id[n-suffix]=0;*kind=dynamic_kinds[i];return buffer_agent_valid_id(id);
  }
  const char *kinds[]={"focus","mode","pending_action","pending_mode","assistant","focus_config","focus_runtime","research"};char target[320];
  for(size_t i=0;i<sizeof(kinds)/sizeof(kinds[0]);i++){
    if(!projection_target(target,sizeof(target),kinds[i],"state")&&!strcmp(path,target)){
      *kind=kinds[i];snprintf(id,BUFFER_ID_SIZE,"state");return true;
    }
  }
  return false;
}
bool buffer_source_is_projection_path(const char *path)
{const char *kind;char id[BUFFER_ID_SIZE];return projection_id(path,&kind,id);}
static bool document_projection(const char *kind)
{return !strcmp(kind,"replica")||!strcmp(kind,"calendar");}
static int path_projection_index(char *path,size_t size,const char *kind,const char *id)
{
  char target[320];int ret=projection_target(target,sizeof(target),kind,id);if(ret)return ret;
  int n=snprintf(path,size,ROOT "/entities/%s-%s.json",kind,id);
  return n<0||(size_t)n>=size?-ENAMETOOLONG:0;
}
static int read_json(const char *path,size_t limit,cJSON **out)
{errno=0;*out=buffer_agent_read_json_plain(path,limit);return *out?0:-(errno?errno:EIO);}
/* Only derived files use this size classification. Symlinks, public files,
 * permission failures and storage I/O retain their errors; source originals,
 * source heads and pending intents never enter this repair path. */
static int read_derived_bytes(const char *path,size_t limit,char **raw,size_t *length)
{
  int ret=buffer_agent_read_bytes(path,limit,raw,length);
  if(ret==-EACCES){
    struct stat st;
    if(!lstat(path,&st)&&S_ISREG(st.st_mode)&&!(st.st_mode&0077)&&
        (st.st_size<=0||(uint64_t)st.st_size>limit))return -EFBIG;
  }
  return ret;
}
static int read_derived_index(const char *path,cJSON **out)
{
  *out=NULL;char *raw=NULL;size_t bytes=0;
  int ret=read_derived_bytes(path,4096,&raw,&bytes);
  if(ret==-EFBIG)ret=-EINVAL;
  if(!ret){
    if(!utf8(raw))ret=-EINVAL;
    else{errno=0;*out=buffer_agent_parse_json(raw);if(!*out)ret=-(errno?errno:ENOMEM);}
  }
  release(raw);return ret;
}
static bool assistant_operation_id(const char *id)
{
  size_t n=strlen(id);if(!n||n>=64)return false;
  for(size_t i=0;i<n;i++)if(!((id[i]>='a'&&id[i]<='z')||(id[i]>='A'&&id[i]<='Z')||
      (id[i]>='0'&&id[i]<='9')||strchr("-_.:",id[i])))return false;
  return true;
}
static int path_assistant_operation(char *path,size_t capacity,const char *id)
{
  if(!id||!assistant_operation_id(id))return -EINVAL;
  char hash[65];int ret=digest(id,strlen(id),hash);if(ret)return ret;
  int n=snprintf(path,capacity,ROOT "/assistant-operations/%s.json",hash);
  return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;
}
static int index_assistant_operation(const char *id,uint64_t seq,bool request)
{
  char path[320];int ret=path_assistant_operation(path,sizeof(path),id);if(ret)return ret;
  cJSON *saved=NULL;ret=read_json(path,4096,&saved);uint64_t previous=0;
  if(!ret){
    bool valid=cJSON_IsObject(saved)&&cJSON_GetArraySize(saved)==2&&!strcmp(text(saved,"operation_id"),id)&&
      number(saved,"source_sequence",&previous)&&previous>0;
    cJSON_Delete(saved);if(!valid)return -EINVAL;if(previous>seq)return -ESTALE;
    if(!request||previous>=seq)return 0;
  } else if(ret!=-ENOENT)return ret;
  cJSON *index=cJSON_CreateObject();
  bool built=index&&cJSON_AddStringToObject(index,"operation_id",id)&&cJSON_AddNumberToObject(index,"source_sequence",(double)seq);
  ret=built?json_write(path,index,4096):-ENOMEM;cJSON_Delete(index);return ret;
}
static int index_assistant_projection(const char *raw,uint64_t seq,bool legacy)
{
  cJSON *state=buffer_agent_parse_json(raw);if(!cJSON_IsObject(state)){cJSON_Delete(state);return -EINVAL;}
  const cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"),*item;
  const cJSON *request=cJSON_GetObjectItemCaseSensitive(state,"request");
  if(!cJSON_IsArray(operations)||cJSON_GetArraySize(operations)>32||(!cJSON_IsNull(request)&&!cJSON_IsObject(request)))
    {cJSON_Delete(state);return -EINVAL;}
  int ret=0;
  cJSON_ArrayForEach(item,operations)if(buffer_agent_valid_id(text(item,"operation_id")))
    (void)capture_filter_contains("assistant_operation",text(item,"operation_id"),true);
  if(cJSON_IsObject(request)&&buffer_agent_valid_id(text(request,"operation_id")))
    (void)capture_filter_contains("assistant_operation",text(request,"operation_id"),true);
  if(legacy)cJSON_ArrayForEach(item,operations){
      ret=index_assistant_operation(text(item,"operation_id"),seq,false);if(ret)break;
    }
  /* Rollback/display-failure snapshots may remove the operation from the
   * runtime ring. Index the request too, so a stale success cannot hide that
   * later outcome and conflicting reuse of its ID remains detectable. */
  if(!ret&&cJSON_IsObject(request))ret=index_assistant_operation(text(request,"operation_id"),seq,true);
  if(!ret&&seq==assistant_filter_covered+1)assistant_filter_covered=seq;
  cJSON_Delete(state);return ret;
}
static int path_focus_operation(char *path,size_t capacity,const char *id)
{
  if(!buffer_agent_valid_id(id))return -EINVAL;
  char hash[65];int ret=digest(id,strlen(id),hash);if(ret)return ret;
  /* Share the private operation-index directory with an explicit namespace. */
  int n=snprintf(path,capacity,ROOT "/assistant-operations/focus-%s.json",hash);
  return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;
}
static int index_focus_operation(const char *id,uint64_t seq)
{
  char path[320];int ret=path_focus_operation(path,sizeof(path),id);if(ret)return ret;
  cJSON *index=cJSON_CreateObject();
  bool built=index&&cJSON_AddStringToObject(index,"operation_id",id)&&cJSON_AddNumberToObject(index,"source_sequence",(double)seq);
  ret=built?json_write(path,index,4096):-ENOMEM;cJSON_Delete(index);return ret;
}
static bool focus_operations_valid(const cJSON *state)
{
  const cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"),*item;
  if(!cJSON_IsObject(state)||!cJSON_IsArray(operations))return false;
  cJSON_ArrayForEach(item,operations)if(!cJSON_IsString(item)||!buffer_agent_valid_id(item->valuestring))return false;
  const cJSON *last=cJSON_GetObjectItemCaseSensitive(state,"last_operation");
  return !last||(cJSON_IsString(last)&&buffer_agent_valid_id(last->valuestring));
}
static int index_focus_projection(const char *raw,uint64_t seq)
{
  cJSON *state=buffer_agent_parse_json(raw);if(!focus_operations_valid(state)){cJSON_Delete(state);return -EINVAL;}
  const cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"),*item;
  if(!cJSON_IsArray(operations)){cJSON_Delete(state);return -EINVAL;}
  cJSON_ArrayForEach(item,operations){
    if(!cJSON_IsString(item)||!buffer_agent_valid_id(item->valuestring)){cJSON_Delete(state);return -EINVAL;}
    (void)capture_filter_contains("focus_operation",item->valuestring,true);
  }
  const char *id=text(state,"last_operation");
  if(id[0])(void)capture_filter_contains("focus_operation",id,true);
  const cJSON *request=cJSON_GetObjectItemCaseSensitive(state,"last_request");
  if(cJSON_IsObject(request)&&buffer_agent_valid_id(text(request,"operation_id")))
    (void)capture_filter_contains("focus_operation",text(request,"operation_id"),true);
  int ret=id[0]?index_focus_operation(id,seq):0;
  if(!ret&&seq==focus_filter_covered+1)focus_filter_covered=seq;
  cJSON_Delete(state);return ret;
}
static bool valid_head(const cJSON *j)
{
  uint64_t seq,clock;
  return cJSON_IsObject(j)&&cJSON_GetArraySize(j)==6&&buffer_agent_valid_id(text(j,"source_id"))&&
    number(j,"last_sequence",&seq)&&seq<MAX_COUNTER&&number(j,"last_clock",&clock)&&
    cJSON_IsBool(cJSON_GetObjectItemCaseSensitive(j,"legacy_migrated"))&&
    (seq?buffer_agent_valid_id(text(j,"last_event_id"))&&hex_digest(text(j,"last_fingerprint"))&&clock>0:
      !text(j,"last_event_id")[0]&&!text(j,"last_fingerprint")[0]&&clock==0);
}
/* Verify a derived pointer against its immutable witness. Failure to read
 * an original/hash stays an I/O error; a pointer to another entity is a
 * repairable metadata error. No cache bytes participate in this proof. */
#define INDEX_WITNESSES 16
static struct { uint64_t sequence;char binding[65]; } index_witnesses[INDEX_WITNESSES];
static unsigned next_index_witness;
static int index_witness(uint64_t sequence,const char *kind,const char *id,bool record,const cJSON *audio)
{
  // Cache only an exact pointer/metadata proof for immutable originals. Actual
  // record/projection reads still verify their source bytes independently.
  char binding[BUFFER_ID_SIZE+96],audio_hash[65]="",key[65];
  if(audio){char *encoded=cJSON_PrintUnformatted(audio);if(!encoded)return -ENOMEM;
    int hashed=digest(encoded,strlen(encoded),audio_hash);release(encoded);if(hashed)return hashed;}
  int n=snprintf(binding,sizeof(binding),"%s:%s:%u:%s",kind?kind:"",id,record?1u:0u,audio_hash);
  if(n<0||(size_t)n>=sizeof(binding))return -EOVERFLOW;
  int hashed=digest(binding,(size_t)n,key);if(hashed)return hashed;
  for(unsigned i=0;i<INDEX_WITNESSES;i++)
    if(index_witnesses[i].sequence==sequence&&!strcmp(index_witnesses[i].binding,key))return 0;
  char *raw=NULL;size_t bytes=0;cJSON *envelope=NULL;
  int ret=local_read_locked(sequence,&raw,&bytes);
  if (!ret)
    ret = buffer_source_envelope_decode(raw, bytes, &envelope);
  release(raw);
  if(!ret){
    const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
    if(kind){
      if(strcmp(text(payload,"projection_kind"),kind)||strcmp(text(payload,"entity_id"),id))ret=-EINVAL;
    }else{
      if(strcmp(text(event,"capture_id"),id)||record!=cJSON_IsString(cJSON_GetObjectItemCaseSensitive(payload,"record_json")))ret=-EINVAL;
      const cJSON *blob=cJSON_GetObjectItemCaseSensitive(payload,"blob");
      if(blob&&audio&&!cJSON_Compare(blob,audio,true))ret=-EINVAL;
    }
  }
  cJSON_Delete(envelope);
  if(!ret){unsigned slot=next_index_witness++%INDEX_WITNESSES;
    index_witnesses[slot].sequence=sequence;strcpy(index_witnesses[slot].binding,key);}
  return ret;
}
static int load_capture_file(const char *id,cJSON **out)
{
  char path[320];int ret=path_capture(path,sizeof(path),id);if(ret)return ret;
  ret=read_derived_index(path,out);
  if(ret)return ret;
  uint64_t seq,record,committed=0;
  if(!cJSON_IsObject(*out)||cJSON_GetArraySize(*out)!=5||strcmp(text(*out,"capture_id"),id)||!number(*out,"last_sequence",&seq)||!seq||
     !number(*out,"record_sequence",&record)||record>seq||!buffer_agent_valid_id(text(*out,"last_event_id"))||
     !number(head,"last_sequence",&committed)||seq>committed)
    {cJSON_Delete(*out);*out=NULL;return -EINVAL;}
  const cJSON *audio=cJSON_GetObjectItemCaseSensitive(*out,"audio");
  if(!cJSON_IsNull(audio)&&!cJSON_IsObject(audio)){cJSON_Delete(*out);*out=NULL;return -EINVAL;}
  char expected[BUFFER_ID_SIZE];
  int n=snprintf(expected,sizeof(expected),"evt_%s_%llu",text(head,"source_id"),(unsigned long long)seq);
  if(n<0||(size_t)n>=sizeof(expected)||strcmp(expected,text(*out,"last_event_id")))
    {cJSON_Delete(*out);*out=NULL;return -EINVAL;}
  int verified=index_witness(seq,NULL,id,record==seq,audio);
  if(!verified&&record&&record!=seq)verified=index_witness(record,NULL,id,true,NULL);
  if(verified){cJSON_Delete(*out);*out=NULL;return verified;}
  return 0;
}
/* Derived indices may be reconstructed only from verified committed events.
 * A damaged existing index with no source witness is not a new/legacy entity. */
static int load_capture(const char *id,cJSON **out)
{
  *out=NULL;int ret=load_capture_file(id,out);
  if(ret!=-ENOENT&&ret!=-EINVAL)return ret;
  int initial=ret;uint64_t committed=0;
  if(ret==-ENOENT&&number(head,"last_sequence",&committed)&&capture_filter_covered==committed&&
      !capture_filter_contains("capture",id,false))return ret;
  cJSON_Delete(*out);*out=NULL;
  ret=rebuild_capture_index_locked(id);
  if(ret==-ENOENT&&initial!=-ENOENT)return initial;
  return ret?ret:load_capture_file(id,out);
}
static int delete_intent(void)
{
  if(unlink(INTENT)&&errno!=ENOENT)return -errno;
  int ret=buffer_agent_sync_parent(INTENT);if(!ret)recovery_required=false;return ret;
}

/* A pending intent is the write-ahead authority. Never reapply an older
 * projection after its source head has already committed. */
static int recover_locked(void)
{
  if(!recovery_required)return 0;
  cJSON *pending=NULL;int ret=read_json(INTENT,BUFFER_SOURCE_INTENT_BYTES,&pending);
  if(ret==-ENOENT)return delete_intent();
  if(ret)return ret;
  cJSON *disk=NULL;ret=read_json(META,4096,&disk);
  if(ret||!valid_head(disk)){cJSON_Delete(disk);cJSON_Delete(pending);return ret?ret:-EINVAL;}
  cJSON_Delete(head);head=disk;
  const char *raw=text(pending,"envelope");
  cJSON *envelope=buffer_agent_parse_json(raw);
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event");
  bool generic=cJSON_HasObjectItem(pending,"projection_kind");
  const char *kind=text(pending,"projection_kind");
  const cJSON *index=cJSON_GetObjectItemCaseSensitive(pending,generic?"projection_index":"capture_index");
  const cJSON *projection=cJSON_GetObjectItemCaseSensitive(pending,generic?"projection_text":"record_json");
  const char *id=generic?text(index,"entity_id"):text(event,"capture_id");
  char target[320];
  if(generic&&(projection_target(target,sizeof(target),kind,id)||
      !cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(event,"capture_id")))){ret=-EINVAL;goto done;}
  uint64_t seq=0,clock=0,previous=0,previous_clock=0,index_seq=0,record_seq=0,wire=0,schema=0,created=0;
  if(!cJSON_IsObject(pending)||cJSON_GetArraySize(pending)!=(generic?4:3)||!cJSON_IsObject(envelope)||
     cJSON_GetArraySize(envelope)!=5||strcmp(text(envelope,"source_id"),text(head,"source_id"))||
     !number(envelope,"wire_version",&wire)||wire!=1||!utf8(raw)||
     (strcmp(text(envelope,"provenance"),"native")&&strcmp(text(envelope,"provenance"),"legacy_local"))||
     !cJSON_IsObject(event)||cJSON_GetArraySize(event)!=10||strcmp(text(event,"app_namespace"),"app.buffer")||
     strcmp(text(event,"actor_id"),text(head,"source_id"))||!number(event,"schema_version",&schema)||schema!=1||
     !event_type(text(event,"type"))||
     !number(event,"created_at",&created)||!cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(event,"payload"))||
     !number(envelope,"source_sequence",&seq)||!seq||seq>=MAX_COUNTER||
     !number(event,"logical_clock",&clock)||!clock||!buffer_agent_valid_id(id)||
     !buffer_agent_valid_id(text(event,"event_id"))||strcmp(text(index,generic?"entity_id":"capture_id"),id)||
     strcmp(text(index,"last_event_id"),text(event,"event_id"))||
     !number(index,"last_sequence",&index_seq)||index_seq!=seq||
     !number(index,"record_sequence",&record_seq)||record_seq>seq||
     !number(head,"last_sequence",&previous)||!number(head,"last_clock",&previous_clock)||
     (!cJSON_IsNull(projection)&&!cJSON_IsString(projection))||strlen(raw)>BUFFER_SOURCE_EVENT_BYTES)
    {ret=-EINVAL;goto done;}
  const cJSON *parents=cJSON_GetObjectItemCaseSensitive(event,"parent_event_ids"),*parent;
  if(!cJSON_IsArray(parents)||cJSON_GetArraySize(parents)>2){ret=-EINVAL;goto done;}
  cJSON_ArrayForEach(parent,parents)if(!cJSON_IsString(parent)||!buffer_agent_valid_id(parent->valuestring)||
      !strcmp(parent->valuestring,text(event,"event_id"))){ret=-EINVAL;goto done;}
  char expected[BUFFER_ID_SIZE];
  int written=snprintf(expected,sizeof(expected),"evt_%s_%llu",text(head,"source_id"),(unsigned long long)seq);
  if(written<0||(size_t)written>=sizeof(expected)||strcmp(expected,text(event,"event_id"))){ret=-EINVAL;goto done;}
  if(cJSON_IsString(projection)&&
     (record_seq!=seq||strlen(projection->valuestring)>(generic?projection_limit(kind):BUFFER_AGENT_JSON_BYTES)||
      strcmp(projection->valuestring,text(cJSON_GetObjectItemCaseSensitive(event,"payload"),generic?"projection_text":"record_json"))))
    {ret=-EINVAL;goto done;}
  if(generic){
    const cJSON *payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
    if(!cJSON_IsObject(index)||cJSON_GetArraySize(index)!=5||record_seq!=seq||
       !cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(index,"audio"))||
       strcmp(text(payload,"projection_kind"),kind)||strcmp(text(payload,"entity_id"),id)||
       (cJSON_IsString(projection)?!utf8(projection->valuestring):
         !cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(payload,"projection_text"))))
      {ret=-EINVAL;goto done;}
  }
  if(!generic&&cJSON_IsNull(projection)&&record_seq>=seq){ret=-EINVAL;goto done;}
  if(cJSON_IsString(projection)&&(!generic||document_projection(kind)||!strcmp(kind,"focus")||!strcmp(kind,"assistant")||
     !strcmp(kind,"focus_runtime")||!strcmp(kind,"followup")||!strcmp(kind,"conversation")||!strcmp(kind,"research")||!strcmp(kind,"input_result"))){
    cJSON *check=buffer_agent_parse_json(projection->valuestring);
    bool valid=cJSON_IsObject(check)&&(generic||!strcmp(text(check,"capture_id"),id));
    cJSON_Delete(check);if(!valid){ret=-EINVAL;goto done;}
  }
  if(cJSON_GetArraySize(parents)==2&&!strcmp(cJSON_GetArrayItem(parents,0)->valuestring,cJSON_GetArrayItem(parents,1)->valuestring))
    {ret=-EINVAL;goto done;}
  if(seq>previous&&((previous&&(!cJSON_GetArraySize(parents)||strcmp(cJSON_GetArrayItem(parents,0)->valuestring,text(head,"last_event_id"))))||
      (!previous&&cJSON_GetArraySize(parents)))){ret=-EINVAL;goto done;}
  if(previous>seq||seq>previous+1||(seq==previous&&
     (clock!=previous_clock||strcmp(text(event,"event_id"),text(head,"last_event_id"))))||
     (seq>previous&&clock<=previous_clock)){ret=-ESTALE;goto done;}
  char path[320];ret=path_event(path,sizeof(path),seq);if(ret)goto done;
  char *stored=NULL;size_t bytes=0;
  ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&stored,&bytes);
  if(!ret){ret=bytes==strlen(raw)&&!memcmp(stored,raw,bytes)?buffer_agent_sync_parent(path):-EEXIST;release(stored);}
  else if(ret==-ENOENT&&seq>previous)ret=buffer_agent_atomic_bytes(path,raw,strlen(raw),BUFFER_SOURCE_EVENT_BYTES);
  if(ret)goto done;
  char fingerprint[65];ret=digest(raw,strlen(raw),fingerprint);if(ret)goto done;
  if(seq==previous&&strcmp(fingerprint,text(head,"last_fingerprint"))){ret=-EIO;goto done;}
  ret=verify_digest(seq,fingerprint,true);if(ret)goto done;
  if(seq==previous){ret=buffer_agent_sync_parent(META);if(!ret)ret=delete_intent();goto done;}
  if(cJSON_IsString(projection)){
    ret=generic?projection_target(path,sizeof(path),kind,id):buffer_agent_record_path(path,sizeof(path),id);if(ret)goto done;
    ret=buffer_agent_atomic_bytes(path,projection->valuestring,strlen(projection->valuestring),generic?projection_limit(kind):BUFFER_AGENT_JSON_BYTES);
    if(ret)goto done;
  } else if(generic){
    ret=projection_target(path,sizeof(path),kind,id);if(ret)goto done;
    if(unlink(path)&&errno!=ENOENT){ret=-errno;goto done;}
    ret=buffer_agent_sync_parent(path);if(ret)goto done;
  }
  ret=generic?path_projection_index(path,sizeof(path),kind,id):path_capture(path,sizeof(path),id);if(ret)goto done;
  ret=json_write(path,index,4096);if(ret)goto done;
  if(generic&&!strcmp(kind,"assistant")&&cJSON_IsString(projection)){
    ret=index_assistant_projection(projection->valuestring,seq,!strcmp(text(envelope,"provenance"),"legacy_local"));if(ret)goto done;
  }
  if(generic&&!strcmp(kind,"focus")&&cJSON_IsString(projection)){
    ret=index_focus_projection(projection->valuestring,seq);if(ret)goto done;
  }
  cJSON *next=cJSON_Duplicate(head,true);
  if(!next){ret=-ENOMEM;goto done;}
  bool built=cJSON_ReplaceItemInObjectCaseSensitive(next,"last_sequence",cJSON_CreateNumber((double)seq))&&
    cJSON_ReplaceItemInObjectCaseSensitive(next,"last_clock",cJSON_CreateNumber((double)clock))&&
    cJSON_ReplaceItemInObjectCaseSensitive(next,"last_event_id",cJSON_CreateString(text(event,"event_id")))&&
    cJSON_ReplaceItemInObjectCaseSensitive(next,"last_fingerprint",cJSON_CreateString(fingerprint));
  ret=built?json_write(META,next,4096):-ENOMEM;
  if(!ret){
    cJSON_Delete(head);head=next;next=NULL;
    capture_filter_observed(seq,event);
    ret=delete_intent();
  }
  cJSON_Delete(next);
done:
  cJSON_Delete(envelope);cJSON_Delete(pending);return ret;
}

static int load_projection_index_file(const char *kind,const char *id,cJSON **out)
{
  char path[320];int ret=path_projection_index(path,sizeof(path),kind,id);if(ret)return ret;
  ret=read_derived_index(path,out);if(ret)return ret;
  uint64_t seq=0,record=0,last=0;char expected[BUFFER_ID_SIZE];
  bool valid=cJSON_IsObject(*out)&&cJSON_GetArraySize(*out)==5&&!strcmp(text(*out,"entity_id"),id)&&
    number(*out,"last_sequence",&seq)&&seq&&number(*out,"record_sequence",&record)&&record==seq&&
    number(head,"last_sequence",&last)&&seq<=last&&cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(*out,"audio"));
  int n=snprintf(expected,sizeof(expected),"evt_%s_%llu",text(head,"source_id"),(unsigned long long)seq);
  if(!valid||n<0||(size_t)n>=sizeof(expected)||strcmp(expected,text(*out,"last_event_id")))
    {cJSON_Delete(*out);*out=NULL;return -EINVAL;}
  int verified=index_witness(seq,kind,id,false,NULL);
  if(verified){cJSON_Delete(*out);*out=NULL;return verified;}
  return 0;
}
static int load_projection_index(const char *kind,const char *id,cJSON **out)
{
  *out=NULL;int ret=load_projection_index_file(kind,id,out);
  if(ret!=-EINVAL)return ret;
  cJSON_Delete(*out);*out=NULL;
  ret=rebuild_projection_index_locked(kind,id);
  if(ret==-ENOENT)return -EINVAL;
  return ret?ret:load_projection_index_file(kind,id,out);
}
static int commit_target_locked(const char *kind,const char *id,const char *type,const cJSON *payload,
                               const char *record_raw,const cJSON *audio,bool legacy,int64_t at)
{
  if(at<0||(uint64_t)at>MAX_COUNTER)return -EINVAL;
  uint64_t previous,clock;
  if(!number(head,"last_sequence",&previous)||previous>=MAX_COUNTER-1||
     !number(head,"last_clock",&clock)||clock>=MAX_COUNTER)return -EOVERFLOW;
  uint64_t observed=0;int clock_ret=buffer_source_remote_clock(text(head,"source_id"),&observed);if(clock_ret)return clock_ret;
  if (observed > clock)
    clock = observed;
  if (clock >= MAX_COUNTER)
    return -EOVERFLOW;
  uint64_t seq=previous+1;clock++;
  cJSON *old=NULL;int ret=kind?load_projection_index(kind,id,&old):load_capture(id,&old);if(ret&&ret!=-ENOENT)return ret;
  char event_id[BUFFER_ID_SIZE];
  int n=snprintf(event_id,sizeof(event_id),"evt_%s_%llu",text(head,"source_id"),(unsigned long long)seq);
  if(n<0||(size_t)n>=sizeof(event_id)){cJSON_Delete(old);return -ENAMETOOLONG;}
  cJSON *envelope=cJSON_CreateObject(),*event=cJSON_CreateObject(),*parents=cJSON_CreateArray();
  cJSON *index=cJSON_CreateObject(),*pending=cJSON_CreateObject();
  char *raw=NULL;
  if(!envelope||!event||!parents||!index||!pending){ret=-ENOMEM;goto done;}
  if(previous&&!cJSON_AddItemToArray(parents,cJSON_CreateString(text(head,"last_event_id")))){ret=-ENOMEM;goto done;}
  if(old&&strcmp(text(old,"last_event_id"),text(head,"last_event_id"))&&
     !cJSON_AddItemToArray(parents,cJSON_CreateString(text(old,"last_event_id")))){ret=-ENOMEM;goto done;}
  bool built=cJSON_AddStringToObject(event,"event_id",event_id)&&
    cJSON_AddStringToObject(event,"app_namespace","app.buffer")&&
    cJSON_AddStringToObject(event,"actor_id",text(head,"source_id"))&&
    cJSON_AddNumberToObject(event,"schema_version",1)&&
    cJSON_AddNumberToObject(event,"logical_clock",(double)clock)&&
    cJSON_AddStringToObject(event,"type",type)&&
    (kind?cJSON_AddNullToObject(event,"capture_id"):cJSON_AddStringToObject(event,"capture_id",id))&&
    cJSON_AddNumberToObject(event,"created_at",(double)at)&&
    add_copy(event,"payload",payload);
  if(!built||!cJSON_AddItemToObject(event,"parent_event_ids",parents)){ret=-ENOMEM;goto done;}parents=NULL;
  built=cJSON_AddNumberToObject(envelope,"wire_version",1)&&
    cJSON_AddStringToObject(envelope,"source_id",text(head,"source_id"))&&
    cJSON_AddNumberToObject(envelope,"source_sequence",(double)seq)&&
    cJSON_AddStringToObject(envelope,"provenance",legacy?"legacy_local":"native");
  if(!built||!cJSON_AddItemToObject(envelope,"event",event)){ret=-ENOMEM;goto done;}event=NULL;
  raw=cJSON_PrintUnformatted(envelope);
  if(!raw){ret=-ENOMEM;goto done;}if(strlen(raw)>BUFFER_SOURCE_EVENT_BYTES){ret=-EFBIG;goto done;}
  uint64_t old_record=0;if(old&&!number(old,"record_sequence",&old_record)){ret=-EINVAL;goto done;}
  const cJSON *old_audio=cJSON_GetObjectItemCaseSensitive(old,"audio");
  built=cJSON_AddStringToObject(index,kind?"entity_id":"capture_id",id)&&cJSON_AddStringToObject(index,"last_event_id",event_id)&&
    cJSON_AddNumberToObject(index,"last_sequence",(double)seq)&&
    cJSON_AddNumberToObject(index,"record_sequence",(double)((kind||record_raw)?seq:old_record));
  cJSON *audio_copy=audio?cJSON_Duplicate(audio,true):old_audio?cJSON_Duplicate(old_audio,true):cJSON_CreateNull();
  if(!built||!audio_copy||!cJSON_AddItemToObject(index,"audio",audio_copy)){cJSON_Delete(audio_copy);ret=-ENOMEM;goto done;}
  if(!cJSON_AddStringToObject(pending,"envelope",raw)||
     !(record_raw?cJSON_AddStringToObject(pending,kind?"projection_text":"record_json",record_raw):cJSON_AddNullToObject(pending,kind?"projection_text":"record_json"))||
     (kind&&!cJSON_AddStringToObject(pending,"projection_kind",kind))||
     !cJSON_AddItemToObject(pending,kind?"projection_index":"capture_index",index)){ret=-ENOMEM;goto done;}index=NULL;
  recovery_required=true;
  ret=json_write(INTENT,pending,BUFFER_SOURCE_INTENT_BYTES);
  if(!ret)ret=recover_locked();
done:
  release(raw);cJSON_Delete(old);cJSON_Delete(envelope);cJSON_Delete(event);cJSON_Delete(parents);
  cJSON_Delete(index);cJSON_Delete(pending);return ret;
}
static int commit_locked(const char *id,const char *type,const cJSON *payload,
                         const char *record_raw,const cJSON *audio,bool legacy,int64_t at)
{return commit_target_locked(NULL,id,type,payload,record_raw,audio,legacy,at);}

static int migrate_records_locked(void)
{
  static DIR *directory;
  static char pending[BUFFER_ID_SIZE];
  if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(head,"legacy_migrated")))return 0;
  if(!directory){directory=opendir(RECORDS);if(!directory)return -errno;}
  if(!pending[0]){
    errno=0;struct dirent *entry=readdir(directory);
    if(!entry){
      int error=errno;if(closedir(directory)&&!error)error=errno;directory=NULL;
      if(error)return -error;
      cJSON *next=cJSON_Duplicate(head,true);if(!next)return -ENOMEM;
      int ret=cJSON_ReplaceItemInObjectCaseSensitive(next,"legacy_migrated",cJSON_CreateTrue())?json_write(META,next,4096):-ENOMEM;
      if(!ret){cJSON_Delete(head);head=next;next=NULL;}cJSON_Delete(next);return ret;
    }
    char path[320];int n=snprintf(path,sizeof(path),RECORDS "%s",entry->d_name);
    if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
    if(!record_id(path,pending)){pending[0]=0;return -EAGAIN;}
  }
  cJSON *index=NULL;int ret=load_capture(pending,&index);cJSON_Delete(index);
  if(!ret){pending[0]=0;return -EAGAIN;}
  if(ret!=-ENOENT)return ret;
  char path[320];int n=snprintf(path,sizeof(path),RECORDS "%s.json",pending);
  if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
  char *raw=NULL;size_t length=0;ret=buffer_agent_read_bytes(path,BUFFER_AGENT_JSON_BYTES,&raw,&length);
  if(ret)return ret;
  cJSON *record=buffer_agent_parse_json(raw),*payload=cJSON_CreateObject();uint64_t created=0;
  if(!record||!utf8(raw)||strcmp(text(record,"capture_id"),pending)||!number(record,"created_at",&created))ret=-EINVAL;
  else if(!payload||!cJSON_AddStringToObject(payload,"record_json",raw))ret=-ENOMEM;
  else ret=commit_locked(pending,"LegacyCaptureSnapshot",payload,raw,NULL,true,(int64_t)created);
  cJSON_Delete(record);cJSON_Delete(payload);release(raw);
  if(!ret){pending[0]=0;return -EAGAIN;}
  return ret;
}

static int ensure_locked(void)
{
  migration_pending=false;
  if(!initialized){
    const char *paths[]={CONFIG_BUFFER_VELA_DATA_DIR,CONFIG_BUFFER_VELA_DATA_DIR "/records",
      CONFIG_BUFFER_VELA_DATA_DIR "/replicas",CONFIG_BUFFER_VELA_DATA_DIR "/calendars",CONFIG_BUFFER_VELA_DATA_DIR "/conversations",CONFIG_BUFFER_VELA_DATA_DIR "/input-results",ROOT,ROOT "/local",ROOT "/captures",ROOT "/blobs",ROOT "/entities",ROOT "/assistant-operations",ROOT "/remote",ROOT "/remote-ids",ROOT "/remote-owners",ROOT "/remote-index",ROOT "/outgoing",ROOT "/blob-deliveries",ROOT "/remote-blobs",ROOT "/remote-visibility",ROOT "/remote-visibility-owners",ROOT "/visible-outgoing",ROOT "/remote-views",ROOT "/remote-causal",ROOT "/remote-parent-work"};
    for(size_t i=0;i<sizeof(paths)/sizeof(paths[0]);i++){int ret=buffer_agent_private_directory(paths[i]);if(ret)return ret;}
    cJSON *saved=NULL;int ret=read_json(META,4096,&saved);
    if(ret==-ENOENT){
      const char *stores[]={ROOT "/local",ROOT "/captures",ROOT "/blobs",ROOT "/entities",ROOT "/assistant-operations",ROOT "/remote",ROOT "/remote-ids",ROOT "/remote-owners",ROOT "/remote-index",ROOT "/outgoing",ROOT "/blob-deliveries",ROOT "/remote-blobs",ROOT "/remote-visibility",ROOT "/remote-visibility-owners",ROOT "/visible-outgoing",ROOT "/remote-views",ROOT "/remote-causal",ROOT "/remote-parent-work"};
      for(size_t i=0;i<sizeof(stores)/sizeof(stores[0]);i++){
        DIR *dir=opendir(stores[i]);if(!dir)return -errno;
        bool empty=true;struct dirent *entry;
        while((entry=readdir(dir)))if(strcmp(entry->d_name,".")&&strcmp(entry->d_name,"..")){empty=false;break;}
        closedir(dir);if(!empty)return -EIO;
      }
      if (access(INTENT, F_OK) == 0)
        return -EIO;
      if (errno != ENOENT)
        return -errno;
      const char *remote_metadata[]={ROOT "/remote-projection-cursor.json",ROOT "/remote-state.json",ROOT "/remote-pending.json",ROOT "/remote-envelope.pending",
        ROOT "/remote-visibility-pending.json",ROOT "/remote-visibility-envelope.pending",ROOT "/remote-visibility-index-build.json",ROOT "/remote-view-state.json"};
      for(unsigned i=0;i<sizeof(remote_metadata)/sizeof(remote_metadata[0]);i++){
        if (access(remote_metadata[i], F_OK) == 0)
          return -EIO;
        if (errno != ENOENT)
          return -errno;
      }
      const char *derived[]={ROOT "/remote-views/card",ROOT "/remote-views/calendar"};
      for(unsigned i=0;i<2;i++){DIR *dir=opendir(derived[i]);if(!dir){if(errno==ENOENT)continue;return -errno;}
        struct dirent *entry;bool empty=true;while((entry=readdir(dir)))if(strcmp(entry->d_name,".")&&strcmp(entry->d_name,"..")){empty=false;break;}
        closedir(dir);if(!empty)return -EIO;}
      unsigned char nonce[16];ret=buffer_entropy_read(nonce,sizeof(nonce));if(ret)return ret;
      char source[37]="src-";for(size_t i=0;i<sizeof(nonce);i++)snprintf(source+4+2*i,3,"%02x",nonce[i]);
      saved=cJSON_CreateObject();
      bool built=saved&&cJSON_AddStringToObject(saved,"source_id",source)&&cJSON_AddNumberToObject(saved,"last_sequence",0)&&
        cJSON_AddNumberToObject(saved,"last_clock",0)&&cJSON_AddStringToObject(saved,"last_event_id","")&&
        cJSON_AddStringToObject(saved,"last_fingerprint","")&&
        cJSON_AddBoolToObject(saved,"legacy_migrated",false);
      ret=built?json_write(META,saved,4096):-ENOMEM;
    }
    if(ret||!valid_head(saved)){cJSON_Delete(saved);return ret?ret:-EINVAL;}
    uint64_t seq=0;if(!number(saved,"last_sequence",&seq)){cJSON_Delete(saved);return -EINVAL;}
    if(seq){
      char path[320],fingerprint[65],*raw=NULL;size_t length=0;
      ret=path_event(path,sizeof(path),seq);
      if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&length);
      if(!ret)ret=digest(raw,length,fingerprint);
      if(!ret&&strcmp(fingerprint,text(saved,"last_fingerprint")))ret=-EIO;
      if(!ret)ret=verify_digest(seq,fingerprint,false);
      release(raw);if(ret){cJSON_Delete(saved);return ret;}
    }
    cJSON_Delete(head);head=saved;initialized=true;recovery_required=true;
  }
  int ret=recover_locked();if(ret)return ret;
  /* Local reads do not depend on remote stream recovery or ownership scans.
   * A new fact obtains the current remote clock in commit_target_locked;
   * remote APIs validate their own journals before accepting any bytes. */
  uint64_t last=0;if(!number(head,"last_sequence",&last))return -EINVAL;
  char path[320];
  ret=path_event(path,sizeof(path),last+1);if(ret)return ret;
  if (access(path, F_OK) == 0)
    return -EIO;
  if (errno != ENOENT)
    return -errno;
  ret=path_digest(path,sizeof(path),last+1);if(ret)return ret;
  if (access(path, F_OK) == 0)
    return -EIO;
  if (errno != ENOENT)
    return -errno;
  /* Unrelated index repairs belong to the maintenance worker. Running them
   * here makes every read pay for another entity's history under this lock. */
  /* After bootstrap, an ordinary operation migrates/verifies its own entity.
   * Global legacy discovery is a startup maintenance task, not a dependency
   * of an otherwise healthy mode change, card action or transcript commit. */
  if(__atomic_load_n(&bootstrap_complete,__ATOMIC_ACQUIRE)&&!full_readiness)return 0;
  ret=migrate_records_locked();
  if(!ret&&!projections_scanned){ret=migrate_projections_locked();if(!ret)projections_scanned=true;}
  migration_pending=ret==-EAGAIN;
  return ret;
}

int buffer_source_events_init(void)
{
  /* Bootstrap is not a journal read or a health check. Every actual source
   * operation still runs ensure_locked(), including recovery and validation.
   * Re-entering it here on every agent tick makes unrelated history scans
   * block microphone admission before a model request can even start. */
  if(__atomic_load_n(&bootstrap_complete,__ATOMIC_ACQUIRE))return 0;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  // Base storage checks passed; derived migration advances in the worker.
  // Normal read/write APIs continue returning EAGAIN until it is usable.
  if(ret==-EAGAIN&&migration_pending)ret=0;
  if(!ret)__atomic_store_n(&bootstrap_complete,true,__ATOMIC_RELEASE);
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_events_ready(void)
{pthread_mutex_lock(&journal_lock);full_readiness=true;int ret=ensure_locked();full_readiness=false;pthread_mutex_unlock(&journal_lock);return ret;}
/* The materialized capture file is a cache of record_sequence, which can
 * precede the latest audio-descriptor event. Never treat a missing cache as a
 * new capture, or replace a committed snapshot with unverified cache bytes. */
static int read_capture_record_locked(const char *path,const char *id,size_t limit,char **raw,size_t *length,bool *changed)
{
  *raw=NULL;*length=0;if(changed)*changed=false;if(!limit||limit>BUFFER_AGENT_JSON_BYTES)return -EINVAL;
  cJSON *index=NULL,*envelope=NULL,*record=NULL;char *original=NULL,*current=NULL;
  size_t original_bytes=0,current_bytes=0;uint64_t sequence=0;
  int ret=load_capture(id,&index);
  if(ret==-ENOENT)return buffer_agent_read_bytes(path,limit,raw,length);
  if(ret)goto done;
  if(!number(index,"record_sequence",&sequence)){ret=-EIO;goto done;}
  if(!sequence){ret=-ENOENT;goto done;}
  ret=local_read_locked(sequence,&original,&original_bytes);
  if(!ret)ret=buffer_source_envelope_decode(original,original_bytes,&envelope);
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  const cJSON *snapshot=cJSON_GetObjectItemCaseSensitive(payload,"record_json");
  if(!ret&&(!cJSON_IsString(snapshot)||strcmp(text(event,"capture_id"),id))){ret=-EIO;goto done;}
  if(ret)goto done;
  const char *expected=snapshot->valuestring;size_t bytes=strlen(expected);
  if(!bytes||bytes>limit||!utf8(expected)){ret=bytes>limit?-EFBIG:-EIO;goto done;}
  record=buffer_agent_parse_json(expected);
  if(!cJSON_IsObject(record)||strcmp(text(record,"capture_id"),id)){ret=-EIO;goto done;}
  ret=read_derived_bytes(path,BUFFER_AGENT_JSON_BYTES,&current,&current_bytes);
  if(ret&&ret!=-ENOENT&&ret!=-EFBIG)goto done;
  if(ret==-ENOENT||ret==-EFBIG||current_bytes!=bytes||memcmp(current,expected,bytes)){
    ret=buffer_agent_atomic_bytes(path,expected,bytes,BUFFER_AGENT_JSON_BYTES);if(ret)goto done;
    if(changed)*changed=true;
  }
  *raw=malloc(bytes+1);if(!*raw){ret=-ENOMEM;goto done;}
  memcpy(*raw,expected,bytes+1);*length=bytes;ret=0;
done:
  cJSON_Delete(index);cJSON_Delete(envelope);cJSON_Delete(record);release(original);release(current);return ret;
}
cJSON *buffer_source_record_read(const char *path,size_t limit)
{
  char id[BUFFER_ID_SIZE];if(!record_id(path,id)){errno=EINVAL;return NULL;}
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  char *raw=NULL;size_t length=0;
  if(!ret)ret=read_capture_record_locked(path,id,limit,&raw,&length,NULL);
  cJSON *record=ret?NULL:buffer_agent_parse_json(raw);
  int error=ret?-ret:errno;
  release(raw);
  pthread_mutex_unlock(&journal_lock);if(!record)errno=error;return record;
}

static int now_ms(int64_t *out)
{
  struct timespec stamp;if(clock_gettime(CLOCK_REALTIME,&stamp)||stamp.tv_sec<0)return -EIO;
  if((uint64_t)stamp.tv_sec>MAX_COUNTER/1000)return -EOVERFLOW;
  *out=(int64_t)stamp.tv_sec*1000+stamp.tv_nsec/1000000;return (uint64_t)*out<=MAX_COUNTER?0:-EOVERFLOW;
}
static bool projection_valid(const char *kind,const char *raw)
{
  if(!raw||!raw[0]||strlen(raw)>projection_limit(kind)||!utf8(raw))return false;
  if(!document_projection(kind)&&strcmp(kind,"focus")&&strcmp(kind,"assistant")&&strcmp(kind,"focus_runtime")&&
     strcmp(kind,"followup")&&strcmp(kind,"conversation")&&strcmp(kind,"research")&&strcmp(kind,"input_result"))return true;
  cJSON *value=buffer_agent_parse_json(raw);bool valid=cJSON_IsObject(value)&&
    (strcmp(kind,"focus")||focus_operations_valid(value));cJSON_Delete(value);return valid;
}
static int commit_projection_locked(const char *kind,const char *id,const char *type,
                                    const char *raw,const char *action,bool legacy)
{
  cJSON *payload=cJSON_CreateObject();int64_t at;int ret=now_ms(&at);
  bool built=payload&&cJSON_AddStringToObject(payload,"projection_kind",kind)&&
    cJSON_AddStringToObject(payload,"entity_id",id)&&
    (raw?cJSON_AddStringToObject(payload,"projection_text",raw):cJSON_AddNullToObject(payload,"projection_text"))&&
    (!action||cJSON_AddStringToObject(payload,"action",action));
  if(!ret)ret=built?commit_target_locked(kind,id,type,payload,raw,NULL,legacy,at):-ENOMEM;
  cJSON_Delete(payload);return ret;
}
static int rebuild_projection_index_locked(const char *kind,const char *id);
static int migrate_projection_locked(const char *kind,const char *id)
{
  cJSON *index=NULL;int ret=load_projection_index(kind,id,&index);cJSON_Delete(index);
  if (!ret)
    return 0;
  if (ret != -ENOENT)
    return ret;
  ret=rebuild_projection_index_locked(kind,id);
  if(ret!=-ENOENT)return ret;
  /* Acceptance is created only under the live device-owner fence. A plain
   * cache without an original journal entry cannot manufacture that fact. */
  if(!strcmp(kind,"input_result"))return 0;
  char path[320],*raw=NULL;size_t length=0;
  ret=projection_target(path,sizeof(path),kind,id);if(ret)return ret;
  if(document_projection(kind)){
    int view=buffer_source_view_exists(path);
    if (view == 1)
      return 0;
    if (view != -ENOENT)
      return view;
  }
  ret=buffer_agent_read_bytes(path,BUFFER_AGENT_JSON_BYTES,&raw,&length);
  if(ret==-ENOENT)return 0;
  if(!ret&&document_projection(kind)){
    cJSON *record=buffer_agent_parse_json(raw);bool derived=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"source_projection"));
    cJSON_Delete(record);if(derived){release(raw);return 0;}
  }
  if(!ret)ret=projection_valid(kind,raw)?commit_projection_locked(kind,id,"LegacyProjectionSnapshot",raw,NULL,true):-EINVAL;
  release(raw);return ret;
}
static int migrate_projections_locked(void)
{
  static unsigned fixed,dynamic;
  static DIR *directory;
  static char pending_kind[24],pending_id[BUFFER_ID_SIZE];
  const char *kinds[]={"focus","mode","pending_action","pending_mode","assistant","focus_config","focus_runtime","research"};
  if(fixed<sizeof(kinds)/sizeof(kinds[0])){
    int ret=migrate_projection_locked(kinds[fixed],"state");if(ret)return ret;
    fixed++;return -EAGAIN;
  }
  const char *directories[]={CONFIG_BUFFER_VELA_DATA_DIR "/replicas",CONFIG_BUFFER_VELA_DATA_DIR "/conversations",CONFIG_BUFFER_VELA_DATA_DIR "/records",CONFIG_BUFFER_VELA_DATA_DIR "/calendars"};
  if(dynamic>=sizeof(directories)/sizeof(directories[0]))return 0;
  if(!directory){directory=opendir(directories[dynamic]);if(!directory)return -errno;}
  if(!pending_kind[0]){
    errno=0;struct dirent *entry=readdir(directory);
    if(!entry){int error=errno;if(closedir(directory)&&!error)error=errno;directory=NULL;if(error)return -error;dynamic++;return -EAGAIN;}
    char path[320];const char *kind=NULL;int n=snprintf(path,sizeof(path),"%s/%s",directories[dynamic],entry->d_name);
    if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
    if(!projection_id(path,&kind,pending_id))return -EAGAIN;
    snprintf(pending_kind,sizeof(pending_kind),"%s",kind);
  }
  int ret=migrate_projection_locked(pending_kind,pending_id);
  if(!ret){pending_kind[0]=pending_id[0]=0;return -EAGAIN;}
  return ret;
}
/* Partial scans are scheduling hints only. Publish an index only after its
 * originals have been checked. Completed slots may be reused; active work is
 * never evicted by another entity. A restart safely repeats unpublished work. */
#define INDEX_REBUILDS 4
#define INDEX_REBUILD_WORK 64
struct index_rebuild_s {
  bool used,done,capture,operation,incomplete;
  uint64_t known_sequence;
  char kind[24],id[BUFFER_ID_SIZE],source[97],event_id[BUFFER_ID_SIZE];
  uint64_t ceiling,cursor,tail_next,tail_floor,tail_head,newest,record,audio_sequence;
  cJSON *audio;
  int result;
};
static struct index_rebuild_s index_rebuilds[INDEX_REBUILDS];
static unsigned next_rebuild;
static void clear_rebuild(struct index_rebuild_s *work)
{cJSON_Delete(work->audio);memset(work,0,sizeof(*work));}
static bool rebuild_pending_locked(void)
{
  for(unsigned i=0;i<INDEX_REBUILDS;i++)if(index_rebuilds[i].used&&!index_rebuilds[i].done)return true;
  return false;
}
static bool rebuild_ready(const struct index_rebuild_s *work)
{return !work->cursor||(work->capture?(work->record&&work->audio_sequence):work->record!=0);}
static uint64_t rebuild_now(void)
{
  struct timespec stamp;if(clock_gettime(CLOCK_MONOTONIC,&stamp))return 0;
  return (uint64_t)stamp.tv_sec*1000+(uint64_t)stamp.tv_nsec/1000000;
}
static int rebuild_observe(struct index_rebuild_s *work,uint64_t sequence,const cJSON *event)
{
  capture_filter_add_event(event);
  const cJSON *payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  if(work->operation){
    bool coverage=!strcmp(work->id,"@coverage");
    bool focus=!strcmp(work->kind,"focus_operation")||(coverage&&!strcmp(text(payload,"projection_kind"),"focus"));
    const char *expected=focus?"focus":"assistant";
    if(strcmp(text(payload,"projection_kind"),expected))return 0;
    cJSON *first=NULL,*second=NULL;
    int found=focus?focus_operation_at_locked(sequence,work->id,&first,&second):
      assistant_operation_at_locked(sequence,work->id,&first,&second);
    cJSON_Delete(first);cJSON_Delete(second);
    if(coverage)return found<0&&!(focus&&found==-ESTALE)?found:0;
    if(found==2){if(sequence>work->known_sequence)work->known_sequence=sequence;return 0;}
    if(found<0&&!(focus&&found==-ESTALE))return found;
    if(!found)return 0;
    if(sequence>work->record){work->record=sequence;work->incomplete=found==-ESTALE;}
    if(sequence>work->newest)work->newest=sequence;
    return 0;
  }
  if(work->capture){
    if(strcmp(text(event,"capture_id"),work->id))return 0;
    const cJSON *record=cJSON_GetObjectItemCaseSensitive(payload,"record_json"),*audio=cJSON_GetObjectItemCaseSensitive(payload,"blob");
    if(record&&sequence>work->record){
      cJSON *value=cJSON_IsString(record)?buffer_agent_parse_json(record->valuestring):NULL;
      bool valid=cJSON_IsObject(value)&&!strcmp(text(value,"capture_id"),work->id);cJSON_Delete(value);
      if (!valid)
        return -EIO;
      work->record = sequence;
    }
    if(audio&&sequence>work->audio_sequence){
      uint64_t bytes=0,created=0,duration=0;const char *mode=text(audio,"mode");
      if(!cJSON_IsObject(audio)||cJSON_GetArraySize(audio)!=6||!hex_digest(text(audio,"sha256"))||
         !number(audio,"bytes",&bytes)||bytes<44||bytes>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES||
         strcmp(text(audio,"content_type"),"audio/wav")||!number(audio,"created_at",&created)||
         !number(audio,"duration_ms",&duration)||duration>UINT32_MAX||
         (strcmp(mode,"normal")&&strcmp(mode,"focus")&&strcmp(mode,"bedtime")&&strcmp(mode,"rabbit_hole")))return -EIO;
      cJSON *copy=cJSON_Duplicate(audio,true);if(!copy)return -ENOMEM;
      cJSON_Delete(work->audio);work->audio=copy;work->audio_sequence=sequence;
    }
  }else{
    if(strcmp(text(payload,"projection_kind"),work->kind)||strcmp(text(payload,"entity_id"),work->id))return 0;
    const cJSON *projection=cJSON_GetObjectItemCaseSensitive(payload,"projection_text");
    if(!cJSON_IsNull(projection)&&(!cJSON_IsString(projection)||!projection_valid(work->kind,projection->valuestring)))return -EINVAL;
    if(sequence>work->record)work->record=sequence;
  }
  if(sequence>work->newest){work->newest=sequence;snprintf(work->event_id,sizeof(work->event_id),"%s",text(event,"event_id"));}
  return 0;
}
static int publish_rebuild(struct index_rebuild_s *work)
{
  if(!work->cursor){
    capture_filter_covered=work->ceiling;
    if(work->operation){
      if(!strcmp(work->kind,"focus_operation"))focus_filter_covered=work->ceiling;
      else assistant_filter_covered=work->ceiling;
      if(!strcmp(work->id,"@coverage"))focus_filter_covered=assistant_filter_covered=work->ceiling;
    }
  }
  if(work->operation){
    uint64_t sequence=work->record?work->record:work->known_sequence;
    if(!sequence)return -ENOENT;
    char path[320];cJSON *index=cJSON_CreateObject();
    bool built=index&&cJSON_AddStringToObject(index,"operation_id",work->id)&&
      cJSON_AddNumberToObject(index,"source_sequence",(double)sequence);
    int ret=!strcmp(work->kind,"focus_operation")?path_focus_operation(path,sizeof(path),work->id):path_assistant_operation(path,sizeof(path),work->id);
    // Returning the verified source pointer below does not depend on free
    // space for this cache. An index failure never makes an old ID new.
    if (!ret && built)
      (void)json_write(path, index, 4096);
    cJSON_Delete(index);
    return work->incomplete||!work->record?-ESTALE:0;
  }
  if(!work->newest)return -ENOENT;
  cJSON *index=cJSON_CreateObject();if(!index)return -ENOMEM;
  bool built=cJSON_AddStringToObject(index,work->capture?"capture_id":"entity_id",work->id)&&
    cJSON_AddStringToObject(index,"last_event_id",work->event_id)&&
    cJSON_AddNumberToObject(index,"last_sequence",(double)work->newest)&&
    cJSON_AddNumberToObject(index,"record_sequence",(double)work->record);
  if(built&&work->audio){
    cJSON *copy=cJSON_Duplicate(work->audio,true);
    if(!copy)built=false;else if(!cJSON_AddItemToObject(index,"audio",copy)){cJSON_Delete(copy);built=false;}
  }else if(built)built=cJSON_AddNullToObject(index,"audio");
  char path[320];int ret=work->capture?path_capture(path,sizeof(path),work->id):path_projection_index(path,sizeof(path),work->kind,work->id);
  if(!ret)ret=built?json_write(path,index,4096):-ENOMEM;
  cJSON_Delete(index);return ret;
}
static int rebuild_step_locked(struct index_rebuild_s *work)
{
  uint64_t last=0;if(!number(head,"last_sequence",&last)||last<work->ceiling||strcmp(text(head,"source_id"),work->source))return -EIO;
  if(work->done&&last==work->ceiling)return work->result?work->result:publish_rebuild(work);
  work->done=false;size_t bytes=0;uint64_t started=rebuild_now();
  for(unsigned count=0;count<INDEX_REBUILD_WORK&&bytes<2*BUFFER_SOURCE_EVENT_BYTES;count++){
    if(!work->tail_next&&last>work->ceiling){work->tail_next=last;work->tail_floor=work->ceiling;work->tail_head=last;}
    bool tail=work->tail_next!=0;
    if(!tail&&rebuild_ready(work))break;
    uint64_t sequence=tail?work->tail_next:work->cursor;
    char *raw=NULL;size_t length=0;cJSON *envelope=NULL;
    int ret=local_read_locked(sequence,&raw,&length);
    if (!ret)
      ret = buffer_source_envelope_decode(raw, length, &envelope);
    release(raw);
    if(!ret)ret=rebuild_observe(work,sequence,cJSON_GetObjectItemCaseSensitive(envelope,"event"));
    cJSON_Delete(envelope);if(ret)return ret;
    bytes+=work->operation?2*length:length;
    if(tail){work->tail_next--;if(work->tail_next==work->tail_floor){work->ceiling=work->tail_head;work->tail_next=0;}}
    else work->cursor--;
    uint64_t now=rebuild_now();if(started&&now>=started&&now-started>=20)break;
  }
  if(work->tail_next||last!=work->ceiling||!rebuild_ready(work))return -EAGAIN;
  int ret=publish_rebuild(work);
  if(!ret||ret==-ENOENT||(work->operation&&ret==-ESTALE)){work->done=true;work->result=ret;}
  return ret;
}
static void advance_rebuild_locked(void)
{
  for(unsigned count=0;count<INDEX_REBUILDS;count++){
    unsigned slot=next_rebuild++%INDEX_REBUILDS;struct index_rebuild_s *work=&index_rebuilds[slot];
    if(work->used&&!work->done){
      // An unrelated private-index failure must not reject a healthy fact.
      // Its own caller still receives the actual storage/validation error.
      (void)rebuild_step_locked(work);break;
    }
  }
}
int buffer_source_events_maintenance(void)
{
  if(pthread_mutex_trylock(&journal_lock))return 0;
  bool pending=initialized&&!recovery_required&&rebuild_pending_locked();
  if(pending)advance_rebuild_locked();
  int completed=pending&&!rebuild_pending_locked();
  /* Prove operation absence once per boot, while idle, rather than making
   * the first timer scan all unrelated history. '@' cannot name an operation.
   * Only verified originals populate the filter; an unfinished scan grants
   * no permission to execute a new operation. */
  uint64_t last=0;
  if(initialized&&!recovery_required&&!rebuild_pending_locked()&&
     number(head,"last_sequence",&last)&&assistant_filter_covered!=last){
    int ret=request_rebuild_locked("assistant_operation","@coverage",false,NULL);
    if(ret==-ENOENT&&assistant_filter_covered==last)completed=1;
  }
  if(initialized&&!recovery_required&&!rebuild_pending_locked()&&
     (!projections_scanned||!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(head,"legacy_migrated")))){
    full_readiness=true;int ret=ensure_locked();full_readiness=false;
    if(!ret)completed=1;
  }
  pthread_mutex_unlock(&journal_lock);
  return completed;
}
static int request_rebuild_locked(const char *kind,const char *id,bool capture,uint64_t *sequence)
{
  uint64_t last=0;if(!number(head,"last_sequence",&last))return -EINVAL;
  if(!kind||!id||strlen(kind)>=sizeof(index_rebuilds[0].kind)||strlen(id)>=BUFFER_ID_SIZE)return -EINVAL;
  struct index_rebuild_s *work=NULL,*available=NULL;
  for(unsigned i=0;i<INDEX_REBUILDS;i++){
    struct index_rebuild_s *candidate=&index_rebuilds[i];
    if(candidate->used&&candidate->capture==capture&&!strcmp(candidate->kind,kind)&&!strcmp(candidate->id,id)){work=candidate;break;}
    if(!candidate->used||candidate->done)available=candidate;
  }
  uint64_t covered=!strcmp(kind,"focus_operation")?focus_filter_covered:
    !strcmp(kind,"assistant_operation")?assistant_filter_covered:capture_filter_covered;
  if(strcmp(id,"@coverage")&&covered!=last){
    int ret=request_rebuild_locked("assistant_operation","@coverage",false,NULL);
    if(ret!=-ENOENT)return ret;
    covered=!strcmp(kind,"focus_operation")?focus_filter_covered:
      !strcmp(kind,"assistant_operation")?assistant_filter_covered:capture_filter_covered;
  }
  if(covered==last&&!capture_filter_contains(kind,id,false)){
    if (work)
      clear_rebuild(work);
    return -ENOENT;
  }
  if(!work){
    if (!available)
      return -EAGAIN;
    work = available;
    clear_rebuild(work);
    work->used=true;work->capture=capture;work->cursor=work->ceiling=last;
    work->operation=!strcmp(kind,"focus_operation")||!strcmp(kind,"assistant_operation");
    snprintf(work->kind,sizeof(work->kind),"%s",kind);snprintf(work->id,sizeof(work->id),"%s",id);
    snprintf(work->source,sizeof(work->source),"%s",text(head,"source_id"));
  }
  int ret=rebuild_step_locked(work);
  if(sequence&&work->done)*sequence=work->record?work->record:work->known_sequence;
  if(work->done)clear_rebuild(work);
  return ret;
}
static int rebuild_projection_index_locked(const char *kind,const char *id)
{return request_rebuild_locked(kind,id,false,NULL);}
/* Entity files are rebuildable views of their committed local source event.
 * Never interpret a missing view as an empty assistant/session after a crash,
 * or accept an out-of-journal edit as the next fact's previous state. */
static int read_projection_locked(const char *path,size_t limit,char **raw,size_t *length,bool with_remote,bool *changed)
{
  *raw=NULL;*length=0;if(changed)*changed=false;const char *kind;char id[BUFFER_ID_SIZE],event_path[320];
  if(!projection_id(path,&kind,id)||!limit||limit>projection_limit(kind))return -EINVAL;
  cJSON *index=NULL,*envelope=NULL;char *event_raw=NULL,*current=NULL;size_t event_bytes=0,current_bytes=0;
  int ret=load_projection_index(kind,id,&index);
  if(ret==-ENOENT){
    ret=migrate_projection_locked(kind,id);
    if(!ret)ret=load_projection_index(kind,id,&index);
  }
  if(ret==-ENOENT&&with_remote&&document_projection(kind)){
    ret=buffer_source_view_overlay(path,NULL,limit,raw,length,changed);goto done;
  }
  if(ret)goto done;
  uint64_t seq=0;if(!number(index,"last_sequence",&seq)){ret=-EINVAL;goto done;}
  ret=path_event(event_path,sizeof(event_path),seq);if(ret)goto done;
  ret=buffer_agent_read_bytes(event_path,BUFFER_SOURCE_EVENT_BYTES,&event_raw,&event_bytes);
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  char hash[65];ret=digest(event_raw,event_bytes,hash);if(!ret)ret=verify_digest(seq,hash,false);
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  envelope=buffer_agent_parse_json(event_raw);
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  const cJSON *projection=cJSON_GetObjectItemCaseSensitive(payload,"projection_text");uint64_t source_seq=0;
  if(!cJSON_IsObject(envelope)||!number(envelope,"source_sequence",&source_seq)||source_seq!=seq||
     strcmp(text(envelope,"source_id"),text(head,"source_id"))||strcmp(text(event,"event_id"),text(index,"last_event_id"))||
     strcmp(text(payload,"projection_kind"),kind)||strcmp(text(payload,"entity_id"),id)||
     (!cJSON_IsNull(projection)&&!cJSON_IsString(projection))){ret=-EINVAL;goto done;}
  if(cJSON_IsNull(projection)){
    if(with_remote){
      if(!unlink(path)){if(changed)*changed=true;}
      else if(errno!=ENOENT){ret=-errno;goto done;}
      ret=buffer_agent_sync_parent(path);if(ret)goto done;
    }
    ret=-ENOENT;goto done;
  }
  const char *expected=projection->valuestring;size_t expected_bytes=strlen(expected);
  if(!projection_valid(kind,expected)||expected_bytes>limit){ret=-EFBIG;goto done;}
  if(with_remote&&document_projection(kind)){
    ret=buffer_source_view_overlay(path,expected,limit,raw,length,changed);
    if(ret!=-ENOENT)goto done;
  }
  if(with_remote){
    ret=read_derived_bytes(path,projection_limit(kind),&current,&current_bytes);
    if(ret&&ret!=-ENOENT&&ret!=-EFBIG)goto done;
    if(ret==-ENOENT||ret==-EFBIG||current_bytes!=expected_bytes||memcmp(current,expected,expected_bytes)){
      ret=buffer_agent_atomic_bytes(path,expected,expected_bytes,projection_limit(kind));if(ret)goto done;
      if(changed)*changed=true;
    }
  }
  *raw=malloc(expected_bytes+1);if(!*raw){ret=-ENOMEM;goto done;}
  memcpy(*raw,expected,expected_bytes+1);*length=expected_bytes;ret=0;
done:
  release(event_raw);release(current);cJSON_Delete(envelope);cJSON_Delete(index);return ret;
}
static int projection_save(const char *path,const char *raw,const char *type,const char *action,bool legacy)
{
  const char *kind;char id[BUFFER_ID_SIZE];
  if(!projection_id(path,&kind,id)||!projection_valid(kind,raw)||
     (type&&!event_type(type))||(action&&(!buffer_agent_valid_id(action)||strcmp(kind,"replica"))))return -EINVAL;
  if(!type)type=!strcmp(kind,"replica")?"ReplicaDocumentObserved":!strcmp(kind,"calendar")?"CalendarReplicaObserved":!strcmp(kind,"focus")?"FocusStateObserved":
    !strcmp(kind,"mode")?"ModeStateChanged":!strcmp(kind,"pending_mode")?"ModeChangeRequested":
    !strcmp(kind,"assistant")?"AssistantStateChanged":!strcmp(kind,"focus_config")?"FocusReminderConfigChanged":
    !strcmp(kind,"focus_runtime")?"FocusReminderStateChanged":!strcmp(kind,"followup")?"FollowupContextChanged":
    !strcmp(kind,"input_result")?"DeviceInputResultAccepted":!strcmp(kind,"conversation")?"ConversationViewUpdated":!strcmp(kind,"research")?"ResearchSessionObserved":"CardActionRequested";
  pthread_mutex_lock(&journal_lock);
  int ret=ensure_locked();char *old=NULL;size_t size=0;cJSON *index=NULL;
  if(ret)goto done;
  ret=read_projection_locked(path,projection_limit(kind),&old,&size,true,NULL);if(ret&&ret!=-ENOENT)goto done;
  ret=load_projection_index(kind,id,&index);bool indexed=!ret;if(ret&&ret!=-ENOENT)goto done;
  int remote_view=-ENOENT;
  if(!indexed&&document_projection(kind)){remote_view=buffer_source_view_exists(path);if(remote_view!=1&&remote_view!=-ENOENT){ret=remote_view;goto done;}}
  if(old&&!indexed&&remote_view!=1){
    if(!projection_valid(kind,old)){ret=-EINVAL;goto done;}
    ret=commit_projection_locked(kind,id,"LegacyProjectionSnapshot",old,NULL,true);if(ret)goto done;
  }
  if(old&&!strcmp(old,raw)){ret=0;goto done;}
  ret=commit_projection_locked(kind,id,type,raw,action,legacy);
done:
  release(old);cJSON_Delete(index);pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_projection_save(const char *path,const char *raw,const char *type,const char *action)
{return projection_save(path,raw,type,action,false);}
int buffer_source_projection_json(const char *path,const cJSON *value,const char *type,const char *action)
{
  if(!cJSON_IsObject(value))return -EINVAL;
  char *raw=cJSON_PrintUnformatted(value);if(!raw)return -ENOMEM;
  int ret=buffer_source_projection_save(path,raw,type,action);release(raw);return ret;
}
int buffer_source_projection_legacy(const char *path,const cJSON *value)
{
  if(!cJSON_IsObject(value))return -EINVAL;
  char *raw=cJSON_PrintUnformatted(value);if(!raw)return -ENOMEM;
  int ret=projection_save(path,raw,"LegacyProjectionSnapshot",NULL,true);release(raw);return ret;
}
cJSON *buffer_source_projection_read(const char *path,size_t limit)
{
  if(!buffer_source_is_projection_path(path)){errno=EINVAL;return NULL;}
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  char *raw=NULL;size_t length=0;
  if(ret==-ENOENT)ret=-EIO;
  if(!ret)ret=read_projection_locked(path,limit,&raw,&length,true,NULL);
  cJSON *value=ret?NULL:buffer_agent_parse_json(raw);int error=ret?-ret:errno;
  release(raw);
  pthread_mutex_unlock(&journal_lock);if(!value)errno=error;return value;
}
cJSON *buffer_source_projection_local_read(const char *path,size_t limit)
{
  if(!buffer_source_is_projection_path(path)){errno=EINVAL;return NULL;}
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();char *raw=NULL;size_t length=0;
  if(!ret)ret=read_projection_locked(path,limit,&raw,&length,false,NULL);
  cJSON *value=ret?NULL:buffer_agent_parse_json(raw);int error=ret?-ret:errno;
  release(raw);pthread_mutex_unlock(&journal_lock);if(!value)errno=error;return value;
}
int buffer_source_projection_local_deleted(const char *path,bool *deleted)
{
  if (!deleted)
    return -EINVAL;
  *deleted = false;
  const char *kind;
  char id[BUFFER_ID_SIZE];
  if(!projection_id(path,&kind,id))return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();char *raw=NULL;size_t length=0;cJSON *index=NULL;
  if(!ret)ret=read_projection_locked(path,projection_limit(kind),&raw,&length,false,NULL);
  if(ret==-ENOENT){ret=load_projection_index(kind,id,&index);if(!ret)*deleted=true;else if(ret==-ENOENT)ret=0;}
  release(raw);cJSON_Delete(index);pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_projection_read_bytes(const char *path,size_t limit,char **raw,size_t *length)
{
  if (!raw || !length)
    return -EINVAL;
  *raw = NULL;
  *length = 0;
  if(!buffer_source_is_projection_path(path))return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  if(ret==-ENOENT)ret=-EIO;
  if(!ret)ret=read_projection_locked(path,limit,raw,length,true,NULL);
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_projection_remove(const char *path,const char *type)
{
  const char *kind;char id[BUFFER_ID_SIZE];
  if(!projection_id(path,&kind,id)||!type||!event_type(type))return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();char *old=NULL;size_t length=0;cJSON *index=NULL;
  if(ret)goto done;
  ret=read_projection_locked(path,projection_limit(kind),&old,&length,true,NULL);
  if(ret==-ENOENT){ret=0;goto done;}if(ret)goto done;
  ret=load_projection_index(kind,id,&index);
  if(ret==-ENOENT){
    if(!projection_valid(kind,old)){ret=-EINVAL;goto done;}
    ret=commit_projection_locked(kind,id,"LegacyProjectionSnapshot",old,NULL,true);
  }
  if(!ret)ret=commit_projection_locked(kind,id,type,NULL,NULL,false);
done:
  release(old);cJSON_Delete(index);pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_projection_ids(const char *kind,const char *after,unsigned limit,cJSON **ids)
{
  if (!ids)
    return -EINVAL;
  *ids = NULL;
  char target[320];
  if(!kind||!after||!limit||limit>32||(after[0]&&!buffer_agent_valid_id(after))||
     projection_target(target,sizeof(target),kind,"state"))return -EINVAL;
  char (*names)[BUFFER_ID_SIZE]=calloc(limit,BUFFER_ID_SIZE);if(!names)return -ENOMEM;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();unsigned count=0;uint64_t last=0;
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  if(!number(head,"last_sequence",&last)){ret=-EINVAL;goto done;}
  for(uint64_t seq=1;seq<=last;seq++){
    char path[320],hash[65],*raw=NULL;size_t length=0;
    ret=path_event(path,sizeof(path),seq);
    if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&length);
    if (!ret)
      ret = digest(raw, length, hash);
    if (!ret)
      ret = verify_digest(seq, hash, false);
    if(ret){release(raw);if(ret==-ENOENT)ret=-EIO;goto done;}
    cJSON *envelope=buffer_agent_parse_json(raw);release(raw);uint64_t source_seq=0;
    const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
    if(!cJSON_IsObject(envelope)||strcmp(text(envelope,"source_id"),text(head,"source_id"))||
       !number(envelope,"source_sequence",&source_seq)||source_seq!=seq||!cJSON_IsObject(event)||!cJSON_IsObject(payload)){
      cJSON_Delete(envelope);ret=-EINVAL;goto done;
    }
    if(!strcmp(text(payload,"projection_kind"),kind)){
      const char *id=text(payload,"entity_id");
      if(!buffer_agent_valid_id(id)||projection_target(target,sizeof(target),kind,id)){
        cJSON_Delete(envelope);ret=-EINVAL;goto done;
      }
      if(strcmp(id,after)>0){
        unsigned at=0;while(at<count&&strcmp(names[at],id)<0)at++;
        if(at<count&&!strcmp(names[at],id)){cJSON_Delete(envelope);continue;}
        if(at<limit){
          if(count<limit)count++;
          for(unsigned j=count-1;j>at;j--)memcpy(names[j],names[j-1],BUFFER_ID_SIZE);
          snprintf(names[at],BUFFER_ID_SIZE,"%s",id);
        }
      }
    }
    cJSON_Delete(envelope);
  }
  *ids=cJSON_CreateArray();if(!*ids){ret=-ENOMEM;goto done;}
  for(unsigned i=0;i<count;i++){
    cJSON *name=cJSON_CreateString(names[i]);
    if(!name||!cJSON_AddItemToArray(*ids,name)){cJSON_Delete(name);ret=-ENOMEM;goto done;}
  }
done:
  if(ret){cJSON_Delete(*ids);*ids=NULL;}
  pthread_mutex_unlock(&journal_lock);memset(names,0,limit*BUFFER_ID_SIZE);free(names);return ret;
}
static int assistant_operation_at_locked(uint64_t seq,const char *id,cJSON **operation,cJSON **request)
{
  char path[320],hash[65],*raw=NULL;size_t length=0;cJSON *envelope=NULL,*state=NULL;
  int ret=path_event(path,sizeof(path),seq);if(ret)return ret;
  ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&length);
  if (!ret)
    ret = digest(raw, length, hash);
  if (!ret)
    ret = verify_digest(seq, hash, false);
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  envelope=buffer_agent_parse_json(raw);uint64_t saved_seq=0;
  if(!cJSON_IsObject(envelope)||strcmp(text(envelope,"source_id"),text(head,"source_id"))||
     !number(envelope,"source_sequence",&saved_seq)||saved_seq!=seq){ret=-EINVAL;goto done;}
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  if(strcmp(text(payload,"projection_kind"),"assistant")){ret=0;goto done;}
  const cJSON *projection=cJSON_GetObjectItemCaseSensitive(payload,"projection_text");
  if(cJSON_IsNull(projection)){ret=0;goto done;}
  if(!cJSON_IsString(projection)){ret=-EINVAL;goto done;}
  state=buffer_agent_parse_json(projection->valuestring);
  const cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"),*item;
  const cJSON *args=cJSON_GetObjectItemCaseSensitive(state,"request");
  if(!cJSON_IsObject(state)||!cJSON_IsArray(operations)||cJSON_GetArraySize(operations)>32||
     (!cJSON_IsNull(args)&&!cJSON_IsObject(args))){ret=-EINVAL;goto done;}
  cJSON_ArrayForEach(item,operations)if(buffer_agent_valid_id(text(item,"operation_id")))
    (void)capture_filter_contains("assistant_operation",text(item,"operation_id"),true);
  if(cJSON_IsObject(args)&&buffer_agent_valid_id(text(args,"operation_id")))
    (void)capture_filter_contains("assistant_operation",text(args,"operation_id"),true);
  bool known=false;
  cJSON_ArrayForEach(item,operations)if(!strcmp(text(item,"operation_id"),id)){
    *operation=cJSON_Duplicate(item,true);if(!*operation){ret=-ENOMEM;goto done;}break;
  }
  if(cJSON_IsObject(args)&&!strcmp(text(args,"operation_id"),id)){
    *request=cJSON_Duplicate(args,true);if(!*request){ret=-ENOMEM;goto done;}
  }
  if(*operation&&!*request&&strcmp(text(envelope,"provenance"),"legacy_local")){
    /* A later native snapshot may still contain this operation in its small
     * cache. Find its actual request, not a copied cache entry that lacks the
     * input needed to detect same-ID/different-content retries. */
    cJSON_Delete(*operation);*operation=NULL;known=true;
  }
  if(*request&&!*operation&&strcmp(text(event,"type"),"AssistantDisplayFailed")){
    ret=-EINVAL;goto done;
  }
  ret=*operation||*request?1:known?2:0;
done:
  release(raw);cJSON_Delete(envelope);cJSON_Delete(state);return ret;
}
int buffer_source_assistant_operation(const char *id,cJSON **operation,cJSON **request)
{
  if (!operation || !request)
    return -EINVAL;
  *operation = NULL;
  *request = NULL;
  char path[320];int ret=path_assistant_operation(path,sizeof(path),id);if(ret)return ret;
  pthread_mutex_lock(&journal_lock);ret=ensure_locked();cJSON *index=NULL;
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  uint64_t last=0,seq=0;if(!number(head,"last_sequence",&last)){ret=-EINVAL;goto done;}
  ret=read_derived_index(path,&index);
  if(!ret){
    bool valid=cJSON_IsObject(index)&&cJSON_GetArraySize(index)==2&&!strcmp(text(index,"operation_id"),id)&&
      number(index,"source_sequence",&seq)&&seq&&seq<=last;
    if(valid){
      ret=assistant_operation_at_locked(seq,id,operation,request);
      if(ret==1){ret=0;goto done;}if(ret==2){ret=-ESTALE;goto done;}if(ret<0)goto done;
    }
    ret=-EINVAL;
  }
  if(ret!=-ENOENT&&ret!=-EINVAL)goto done;
  if(assistant_filter_covered!=last){
    ret=request_rebuild_locked("assistant_operation","@coverage",false,NULL);
    if(ret!=-ENOENT)goto done;
  }
  ret=request_rebuild_locked("assistant_operation",id,false,&seq);
  if(!ret){ret=assistant_operation_at_locked(seq,id,operation,request);ret=ret==1?0:ret==2?-ESTALE:ret?ret:-EINVAL;}
done:
  cJSON_Delete(index);
  if(ret){cJSON_Delete(*operation);cJSON_Delete(*request);*operation=NULL;*request=NULL;}
  pthread_mutex_unlock(&journal_lock);return ret;
}
static int focus_operation_at_locked(uint64_t seq,const char *id,cJSON **request,cJSON **result)
{
  char path[320],hash[65],*raw=NULL;size_t length=0;cJSON *envelope=NULL,*state=NULL;
  int ret=path_event(path,sizeof(path),seq);if(ret)return ret;
  ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&length);
  if (!ret)
    ret = digest(raw, length, hash);
  if (!ret)
    ret = verify_digest(seq, hash, false);
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  ret=buffer_source_envelope_decode(raw,length,&envelope);if(ret)goto done;
  uint64_t saved=0;
  if(strcmp(text(envelope,"source_id"),text(head,"source_id"))||!number(envelope,"source_sequence",&saved)||saved!=seq){ret=-EINVAL;goto done;}
  const cJSON *payload=cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(envelope,"event"),"payload");
  if(strcmp(text(payload,"projection_kind"),"focus")){ret=0;goto done;}
  const cJSON *projection=cJSON_GetObjectItemCaseSensitive(payload,"projection_text");
  if(cJSON_IsNull(projection)){ret=0;goto done;}
  if(!cJSON_IsString(projection)){ret=-EINVAL;goto done;}
  state=buffer_agent_parse_json(projection->valuestring);if(!focus_operations_valid(state)){ret=-EINVAL;goto done;}
  const cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"),*cached;
  if(!cJSON_IsArray(operations)){ret=-EINVAL;goto done;}
  cJSON_ArrayForEach(cached,operations){
    if(!cJSON_IsString(cached)||!buffer_agent_valid_id(cached->valuestring)){ret=-EINVAL;goto done;}
    (void)capture_filter_contains("focus_operation",cached->valuestring,true);
  }
  if(text(state,"last_operation")[0])(void)capture_filter_contains("focus_operation",text(state,"last_operation"),true);
  const cJSON *hint=cJSON_GetObjectItemCaseSensitive(state,"last_request");
  if(cJSON_IsObject(hint)&&buffer_agent_valid_id(text(hint,"operation_id")))
    (void)capture_filter_contains("focus_operation",text(hint,"operation_id"),true);
  if(strcmp(text(state,"last_operation"),id)){
    ret=0;const cJSON *item;cJSON_ArrayForEach(item,operations)
      if(!strcmp(item->valuestring,id)){ret=!strcmp(text(envelope,"provenance"),"legacy_local")?-ESTALE:2;break;}
    if(cJSON_IsObject(hint)&&!strcmp(text(hint,"operation_id"),id))ret=!strcmp(text(envelope,"provenance"),"legacy_local")?-ESTALE:2;
    goto done;
  }
  const cJSON *args=cJSON_GetObjectItemCaseSensitive(state,"last_request"),*value=cJSON_GetObjectItemCaseSensitive(state,"last_result");
  if(!cJSON_IsObject(args)||!cJSON_IsObject(value)){ret=-ESTALE;goto done;}
  *request=cJSON_Duplicate(args,true);*result=cJSON_Duplicate(value,true);
  ret=*request&&*result?1:-ENOMEM;
done:
  release(raw);cJSON_Delete(envelope);cJSON_Delete(state);return ret;
}
int buffer_source_focus_operation(const char *id,cJSON **request,cJSON **result)
{
  if (!request || !result)
    return -EINVAL;
  *request = NULL;
  *result = NULL;
  char path[320];int ret=path_focus_operation(path,sizeof(path),id);if(ret)return ret;
  pthread_mutex_lock(&journal_lock);ret=ensure_locked();cJSON *index=NULL;
  if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  uint64_t last=0,seq=0;if(!number(head,"last_sequence",&last)){ret=-EINVAL;goto done;}
  ret=read_derived_index(path,&index);
  if(!ret){
    bool valid=cJSON_IsObject(index)&&cJSON_GetArraySize(index)==2&&!strcmp(text(index,"operation_id"),id)&&
      number(index,"source_sequence",&seq)&&seq&&seq<=last;
    if(valid){
      ret=focus_operation_at_locked(seq,id,request,result);
      if(ret==1){ret=0;goto done;}if(ret==2){ret=-ESTALE;goto done;}if(ret<0)goto done;
    }
    ret=-EINVAL;
  }
  if(ret!=-ENOENT&&ret!=-EINVAL)goto done;
  ret=request_rebuild_locked("focus_operation",id,false,&seq);
  if(!ret){ret=focus_operation_at_locked(seq,id,request,result);ret=ret==1?0:ret==2?-ESTALE:ret?ret:-EINVAL;}
done:
  cJSON_Delete(index);
  if(ret){cJSON_Delete(*request);cJSON_Delete(*result);*request=NULL;*result=NULL;}
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_record_save(const char *path,const cJSON *record)
{
  char id[BUFFER_ID_SIZE];if(!record_id(path,id)||strcmp(text(record,"capture_id"),id))return -EINVAL;
  char *raw=cJSON_PrintUnformatted(record);if(!raw)return -ENOMEM;
  if(strlen(raw)>BUFFER_AGENT_JSON_BYTES||!utf8(raw)){release(raw);return -EFBIG;}
  cJSON *verified=buffer_agent_parse_json(raw);
  bool valid=cJSON_IsObject(verified)&&!strcmp(text(verified,"capture_id"),id);
  cJSON_Delete(verified);if(!valid){release(raw);return -EINVAL;}
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();char *old_raw=NULL;size_t length=0;
  cJSON *old=NULL,*index=NULL,*payload=NULL;
  if(ret)goto done;
  ret=read_capture_record_locked(path,id,BUFFER_AGENT_JSON_BYTES,&old_raw,&length,NULL);
  if(ret&&ret!=-ENOENT)goto done;
  old=old_raw?buffer_agent_parse_json(old_raw):NULL;
  if(old_raw&&!old){ret=-EINVAL;goto done;}
  ret=load_capture(id,&index);bool exists=!ret;if(ret&&ret!=-ENOENT)goto done;
  if(old_raw&&!exists){
    uint64_t created=0;payload=cJSON_CreateObject();
    if(!old||!utf8(old_raw)||strcmp(text(old,"capture_id"),id)||!number(old,"created_at",&created)){ret=-EINVAL;goto done;}
    if(!payload||!cJSON_AddStringToObject(payload,"record_json",old_raw)){ret=-ENOMEM;goto done;}
    ret=commit_locked(id,"LegacyCaptureSnapshot",payload,old_raw,NULL,true,(int64_t)created);
    if (ret)
      goto done;
    cJSON_Delete(payload);
    payload = NULL;
    ret=load_capture(id,&index);if(ret)goto done;exists=true;
  }
  if(old_raw&&!strcmp(old_raw,raw)){ret=0;goto done;}
  payload=cJSON_CreateObject();if(!payload||!cJSON_AddStringToObject(payload,"record_json",raw)){ret=-ENOMEM;goto done;}
  const char *type="CaptureRecordUpdated";
  if(!exists)type="CaptureCreated";
  else if(strcmp(text(old,"transcript"),text(record,"transcript")))type="CaptureTranscribed";
  else if(strcmp(text(old,"state"),"ready")&&!strcmp(text(record,"state"),"ready"))type="ClassificationProposed";
  else if(strcmp(text(old,"state"),"assistant_complete")&&!strcmp(text(record,"state"),"assistant_complete"))type="AssistantResultRecorded";
  else if((cJSON_HasObjectItem(old,"segments")||cJSON_HasObjectItem(record,"segments"))&&
      !cJSON_Compare(cJSON_GetObjectItemCaseSensitive(old,"segments"),cJSON_GetObjectItemCaseSensitive(record,"segments"),true))type="CardRecordChanged";
  int64_t at;uint64_t created=0;
  ret=!exists?(number(record,"created_at",&created)?0:-EINVAL):now_ms(&at);
  if(!exists)at=(int64_t)created;
  if(!ret)ret=commit_locked(id,type,payload,raw,NULL,false,at);
done:
  cJSON_Delete(old);cJSON_Delete(index);cJSON_Delete(payload);release(old_raw);release(raw);
  pthread_mutex_unlock(&journal_lock);return ret;
}

static int descriptor_digest(int fd,bool require_private,uint64_t *size,char hash[65])
{
  struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<44||
      st.st_size>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES||(require_private&&(st.st_mode&0077)))return -EACCES;
  if(lseek(fd,0,SEEK_SET)<0)return -errno;
  mbedtls_sha256_context ctx;mbedtls_sha256_init(&ctx);
  int ret=mbedtls_sha256_starts(&ctx,0)?-EIO:0;unsigned char buffer[1024],digest[32];uint64_t total=0;
  while(!ret){ssize_t n=read(fd,buffer,sizeof(buffer));if(n<0&&errno==EINTR)continue;if(n<0){ret=-errno;break;}if(!n)break;
    total+=(uint64_t)n;if(total>(uint64_t)st.st_size||mbedtls_sha256_update(&ctx,buffer,(size_t)n)){ret=-EIO;break;}}
  if(!ret&&(total!=(uint64_t)st.st_size||mbedtls_sha256_finish(&ctx,digest)))ret=-EIO;
  mbedtls_sha256_free(&ctx);volatile unsigned char *clear=buffer;size_t remaining=sizeof(buffer);while(remaining--)*clear++=0;
  if(!ret){for(size_t i=0;i<32;i++)snprintf(hash+2*i,3,"%02x",digest[i]);*size=total;}return ret;
}
static int file_digest(const char *path,bool require_private,uint64_t *size,char hash[65])
{
  int flags=O_RDONLY;
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  int fd=open(path,flags);if(fd<0)return -errno;
  int ret=descriptor_digest(fd,require_private,size,hash);if(close(fd)&&!ret)ret=-errno;return ret;
}
static int archive_audio(const char *from,const char *hash,uint64_t size)
{
  char path[320],temporary[320],actual[65];uint64_t bytes=0;
  int n=snprintf(path,sizeof(path),ROOT "/blobs/%s.wav",hash);if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
  int ret=file_digest(path,true,&bytes,actual);
  if(!ret)return bytes==size&&!strcmp(hash,actual)?buffer_agent_sync_parent(path):-EEXIST;
  if(ret!=-ENOENT)return ret;
  n=snprintf(temporary,sizeof(temporary),"%s.tmp",path);if(n<0||(size_t)n>=sizeof(temporary))return -ENAMETOOLONG;
  int in_flags=O_RDONLY,out_flags=O_WRONLY|O_CREAT|O_TRUNC;
#ifdef O_NOFOLLOW
  in_flags|=O_NOFOLLOW;out_flags|=O_NOFOLLOW;
#endif
  int in=open(from,in_flags);if(in<0)return -errno;
  struct stat st;if(fstat(in,&st)||!S_ISREG(st.st_mode)||(uint64_t)st.st_size!=size){close(in);return -EIO;}
  int out=open(temporary,out_flags,0600);if(out<0){ret=-errno;close(in);return ret;}
  ret=fchmod(out,0600)?-errno:0;unsigned char buffer[1024];uint64_t total=0;
  while(!ret){ssize_t got=read(in,buffer,sizeof(buffer));if(got<0&&errno==EINTR)continue;if(got<0){ret=-errno;break;}if(!got)break;
    total+=(uint64_t)got;if(total>size){ret=-EFBIG;break;}
    size_t offset=0;while(offset<(size_t)got){ssize_t wrote=write(out,buffer+offset,(size_t)got-offset);if(wrote<0&&errno==EINTR)continue;
      if(wrote<=0){ret=wrote<0?-errno:-EIO;break;}offset+=(size_t)wrote;}}
  memset(buffer,0,sizeof(buffer));if(!ret&&(total!=size||fsync(out)))ret=-EIO;
  if (close(in) && !ret)
    ret = -errno;
  if (close(out) && !ret)
    ret = -errno;
  if(!ret){ret=file_digest(temporary,true,&bytes,actual);if(!ret&&(bytes!=size||strcmp(hash,actual)))ret=-EIO;}
  if(!ret&&rename(temporary,path))ret=-errno;
  if (!ret)
    ret = buffer_agent_sync_parent(path);
  if (ret)
    unlink(temporary);
  return ret;
}

int buffer_source_capture_ensure(const char *id)
{
  if(!buffer_agent_valid_id(id))return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();cJSON *index=NULL,*audio=NULL,*payload=NULL;char *metadata_raw=NULL;
  if(ret)goto done;
  ret=load_capture(id,&index);if(ret&&ret!=-ENOENT)goto done;
  char path[320],hash[65];uint64_t bytes=0;
  int n=snprintf(path,sizeof(path),QUEUE "%s.wav",id);if(n<0||(size_t)n>=sizeof(path)){ret=-ENAMETOOLONG;goto done;}
  ret=file_digest(path,false,&bytes,hash);if(ret)goto done;
  ret=archive_audio(path,hash,bytes);if(ret)goto done;
  const cJSON *known=cJSON_GetObjectItemCaseSensitive(index,"audio");uint64_t old_bytes=0;
  if(index&&(!cJSON_IsObject(known)||!number(known,"bytes",&old_bytes)||old_bytes!=bytes||strcmp(text(known,"sha256"),hash))){
    // A lost/corrupt audio member in a derived index is not a second capture.
    // Re-read the original witness before adding or rejecting an audio fact.
    cJSON_Delete(index);index=NULL;ret=rebuild_capture_index_locked(id);
    if (!ret)
      ret = load_capture_file(id, &index);
    if (ret)
      goto done;
    known=cJSON_GetObjectItemCaseSensitive(index,"audio");
  }
  if(cJSON_IsObject(known)){ret=number(known,"bytes",&old_bytes)&&old_bytes==bytes&&!strcmp(text(known,"sha256"),hash)?0:-EEXIST;goto done;}
  int64_t created=0;uint32_t duration=0;char mode[BUFFER_MODE_SIZE];
  ret=buffer_store_read_metadata(id,&created,&duration,mode,sizeof(mode));if(ret)goto done;
  bool native=false;n=snprintf(path,sizeof(path),QUEUE "%s.meta",id);if(n<0||(size_t)n>=sizeof(path)){ret=-ENAMETOOLONG;goto done;}
  int flags=O_RDONLY;
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  int fd=open(path,flags);if(fd<0){ret=-errno;goto done;}
  struct stat st;
  if(fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<1||st.st_size>4095){close(fd);ret=-EFBIG;goto done;}
  size_t size=(size_t)st.st_size,used=0;
  metadata_raw=calloc(1,size+1);if(!metadata_raw){close(fd);ret=-ENOMEM;goto done;}
  while(used<size){ssize_t got=read(fd,metadata_raw+used,size-used);if(got<0&&errno==EINTR)continue;
    if(got<=0){ret=got<0?-errno:-EIO;break;}used+=(size_t)got;}
  if (close(fd) && !ret)
    ret = -errno;
  if (ret)
    goto done;
  if(memchr(metadata_raw,0,size)){ret=-EINVAL;goto done;}
  native=!strncmp(metadata_raw,"source_events=1\n",16)||strstr(metadata_raw,"\nsource_events=1\n")!=NULL;
  if(!utf8(metadata_raw)){ret=-EINVAL;goto done;}
  audio=cJSON_CreateObject();payload=cJSON_CreateObject();
  bool built=audio&&payload&&cJSON_AddStringToObject(audio,"sha256",hash)&&cJSON_AddNumberToObject(audio,"bytes",(double)bytes)&&
    cJSON_AddStringToObject(audio,"content_type","audio/wav")&&cJSON_AddNumberToObject(audio,"created_at",(double)created)&&
    cJSON_AddNumberToObject(audio,"duration_ms",duration)&&cJSON_AddStringToObject(audio,"mode",mode)&&
    add_copy(payload,"blob",audio)&&cJSON_AddStringToObject(payload,"capture_metadata",metadata_raw);
  ret=built?commit_locked(id,native?"CaptureCreated":"LegacyAudioSnapshot",payload,NULL,audio,!native,created):-ENOMEM;
done:
  cJSON_Delete(index);cJSON_Delete(audio);cJSON_Delete(payload);release(metadata_raw);pthread_mutex_unlock(&journal_lock);return ret;
}

int buffer_source_local_head(char *source,size_t capacity,uint64_t *sequence)
{
  if(!source||!capacity||!sequence)return -EINVAL;
  source[0]=0;*sequence=0;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  if(!ret){const char *id=text(head,"source_id");if(strlen(id)>=capacity)ret=-ENAMETOOLONG;
    else{snprintf(source,capacity,"%s",id);if(!number(head,"last_sequence",sequence))ret=-EINVAL;}}
  pthread_mutex_unlock(&journal_lock);return ret;
}
/* Caller holds journal_lock and has run ensure_locked. */
static int local_read_locked(uint64_t sequence,char **raw,size_t *length)
{
  if (!raw || !length)
    return -EINVAL;
  *raw = NULL;
  *length = 0;
  if(!sequence||sequence>=MAX_COUNTER)return -EINVAL;
  int ret=0;uint64_t last=0;
  if(!ret&&!number(head,"last_sequence",&last))ret=-EINVAL;
  if(!ret&&sequence>last)ret=-ENOENT;
  if(!ret){char path[320],fingerprint[65];ret=path_event(path,sizeof(path),sequence);
    if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,raw,length);
    if(!ret)ret=digest(*raw,*length,fingerprint);
    if(!ret)ret=verify_digest(sequence,fingerprint,false);
    if(!ret){cJSON *envelope=NULL;ret=buffer_source_envelope_decode(*raw,*length,&envelope);
      uint64_t saved_seq=0;
      if(!ret&&(strcmp(text(envelope,"source_id"),text(head,"source_id"))||!number(envelope,"source_sequence",&saved_seq)||saved_seq!=sequence))ret=-EIO;
      cJSON_Delete(envelope);}
    if(ret==-ENOENT)ret=-EIO; /* A committed source/hash is never end-of-stream. */
    if(ret){release(*raw);*raw=NULL;*length=0;}}
  return ret;
}
/* Rebuild derived capture metadata only from verified committed originals.
 * A descriptor and a record snapshot are independent observations: preserve
 * the newest of each rather than guessing either from queue/cache files. */
static int rebuild_capture_index_locked(const char *id)
{return request_rebuild_locked("capture",id,true,NULL);}
int buffer_source_local_read(uint64_t sequence,char **raw,size_t *length)
{
  if (!raw || !length)
    return -EINVAL;
  *raw = NULL;
  *length = 0;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  if(!ret)ret=local_read_locked(sequence,raw,length);
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_local_repair_step(uint64_t *cursor)
{
  if(!cursor||*cursor>=MAX_COUNTER)return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();uint64_t last=0;
  if(!ret&&(!number(head,"last_sequence",&last)||*cursor>last))ret=-EIO;
  for(unsigned work=0;!ret&&*cursor<last&&work<16;work++){
    uint64_t sequence=*cursor+1;char *raw=NULL;size_t bytes=0;cJSON *envelope=NULL,*index=NULL;
    ret=local_read_locked(sequence,&raw,&bytes);
    if (!ret)
      ret = buffer_source_envelope_decode(raw, bytes, &envelope);
    release(raw);
    const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
    const char *id=text(event,"capture_id");bool checked=false,changed=false;
    if(!ret&&id[0]){
      uint64_t latest=0,record_sequence=0;ret=load_capture(id,&index);
      if(!ret&&(!number(index,"last_sequence",&latest)||!number(index,"record_sequence",&record_sequence)))ret=-EIO;
      const cJSON *observed_audio=cJSON_GetObjectItemCaseSensitive(payload,"blob");
      bool stale=!ret&&(latest<sequence||
        (cJSON_HasObjectItem(payload,"record_json")&&record_sequence<sequence)||
        (observed_audio&&!cJSON_Compare(observed_audio,cJSON_GetObjectItemCaseSensitive(index,"audio"),true)));
      if(stale){
        cJSON_Delete(index);index=NULL;ret=rebuild_capture_index_locked(id);
        if(!ret)ret=load_capture(id,&index);
      }
      if(!ret&&cJSON_HasObjectItem(payload,"record_json")){
        if(!number(index,"record_sequence",&latest))ret=-EIO;
        if(!ret&&latest==sequence){
          char path[320];ret=buffer_agent_record_path(path,sizeof(path),id);
          raw=NULL;bytes=0;if(!ret)ret=read_capture_record_locked(path,id,BUFFER_AGENT_JSON_BYTES,&raw,&bytes,&changed);
          release(raw);checked=!ret;
        }
      }
    }else if(!ret&&cJSON_HasObjectItem(payload,"projection_kind")){
      const char *kind=text(payload,"projection_kind"),*entity=text(payload,"entity_id");
      char path[320];uint64_t latest=0;
      ret=projection_target(path,sizeof(path),kind,entity);
      if(!ret)ret=load_projection_index(kind,entity,&index);
      if(ret==-ENOENT||(!ret&&number(index,"last_sequence",&latest)&&latest<sequence)){
        cJSON_Delete(index);index=NULL;ret=rebuild_projection_index_locked(kind,entity);
        if(!ret)ret=load_projection_index(kind,entity,&index);
      }
      if(!ret&&!number(index,"last_sequence",&latest))ret=-EIO;
      if(!ret&&latest==sequence){
        raw=NULL;bytes=0;ret=read_projection_locked(path,projection_limit(kind),&raw,&bytes,true,&changed);
        // A source tombstone removes a stale cache; it is not missing history.
        if(ret==-ENOENT&&cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(payload,"projection_text")))ret=0;
        release(raw);checked=!ret;
      }
    }
    if(!ret)capture_filter_observed(sequence,event);
    cJSON_Delete(index);cJSON_Delete(envelope);
    if (ret)
      break;
    *cursor = sequence;
    if (checked) {
      ret = changed ? 2 : 1;
      break;
    }
  }
  if(!ret&&(*cursor<last||rebuild_pending_locked()))ret=-EAGAIN;
  pthread_mutex_unlock(&journal_lock);return ret;
}
/* Caller holds journal_lock. Verify the record/blob binding and hash the
 * very same descriptor that will be used for streaming, avoiding reopen races. */
static int blob_open_locked(uint64_t sequence,const char *record_hash,const char *blob_hash,uint64_t bytes,int *descriptor)
{
  char *raw=NULL,actual[65];size_t length=0;cJSON *j=NULL;*descriptor=-1;
  int ret=local_read_locked(sequence,&raw,&length);
  if(!ret)ret=digest(raw,length,actual);
  if(!ret&&strcmp(actual,record_hash))ret=-EEXIST;
  if(!ret)ret=buffer_source_envelope_decode(raw,length,&j);
  if(!ret){
    const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
    const cJSON *blob=cJSON_GetObjectItemCaseSensitive(payload,"blob");uint64_t size=0;
    if((strcmp(text(event,"type"),"CaptureCreated")&&strcmp(text(event,"type"),"LegacyAudioSnapshot"))||
       !cJSON_IsObject(blob)||strcmp(text(blob,"content_type"),"audio/wav")||strcmp(text(blob,"sha256"),blob_hash)||
       !number(blob,"bytes",&size)||size!=bytes)ret=-EEXIST;
  }
  release(raw);cJSON_Delete(j);if(ret)return ret;
  char path[320];int n=snprintf(path,sizeof(path),ROOT "/blobs/%s.wav",blob_hash);
  if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
  int flags=O_RDONLY;
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  int fd=open(path,flags);if(fd<0)return errno==ENOENT?-EIO:-errno;
  uint64_t size=0;ret=descriptor_digest(fd,true,&size,actual);
  if(!ret&&(size!=bytes||strcmp(actual,blob_hash)))ret=-EIO;
  if(ret){close(fd);return ret;}*descriptor=fd;return 0;
}
int buffer_source_blob_open(uint64_t sequence,const char *record_hash,const char *blob_hash,uint64_t bytes,int *descriptor)
{
  if (!descriptor)
    return -EINVAL;
  *descriptor = -1;
  if(!record_hash||!blob_hash||!hex_digest(record_hash)||!hex_digest(blob_hash)||!sequence||sequence>=MAX_COUNTER||
     bytes<44||bytes>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES)return -EINVAL;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  if(!ret)ret=blob_open_locked(sequence,record_hash,blob_hash,bytes,descriptor);
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_blob_accept(const char *peer,const char *source,uint64_t sequence,const char *record_hash,
                              const char *blob_hash,uint64_t bytes,bool (*authorize)(void *),void *context)
{
  if(!peer||!source||!record_hash||!blob_hash||!authorize||!hex_digest(peer)||!hex_digest(record_hash)||!hex_digest(blob_hash)||
     !sequence||sequence>=MAX_COUNTER||bytes<44||bytes>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES)return -EINVAL;
  if(!authorize(context))return -EACCES;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked(),fd=-1;cJSON *receipt=NULL,*saved=NULL;char *old=NULL;size_t old_bytes=0;
  if(!ret&&strcmp(source,text(head,"source_id")))ret=-EEXIST;
  if(!ret)ret=blob_open_locked(sequence,record_hash,blob_hash,bytes,&fd);
  if(fd>=0&&close(fd)&&!ret)ret=-errno;
  char path[320];int n=snprintf(path,sizeof(path),ROOT "/blob-deliveries/%s-%llu.json",peer,(unsigned long long)sequence);
  if(!ret&&(n<0||(size_t)n>=sizeof(path)))ret=-ENAMETOOLONG;
  if(!ret){
    receipt=cJSON_CreateObject();bool built=receipt&&cJSON_AddNumberToObject(receipt,"schema_version",1)&&
      cJSON_AddStringToObject(receipt,"peer",peer)&&cJSON_AddStringToObject(receipt,"source_id",source)&&
      cJSON_AddNumberToObject(receipt,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(receipt,"record_sha256",record_hash)&&
      cJSON_AddStringToObject(receipt,"blob_sha256",blob_hash)&&cJSON_AddNumberToObject(receipt,"total",(double)bytes);
    if(!built)ret=-ENOMEM;
  }
  if(!ret){
    ret=buffer_agent_read_bytes(path,4096,&old,&old_bytes);
    if(!ret){ret=buffer_source_json_object(old,old_bytes,4096,&saved);
      if(!ret&&!cJSON_Compare(saved,receipt,true))ret=-EEXIST;
      if(!ret)ret=confirm_receipt(path,receipt);
    }else if(ret==-ENOENT){
      ret=authorize(context)?json_write(path,receipt,4096):-EACCES;
    }
  }
  if(!authorize(context))ret=-EACCES;
  release(old);cJSON_Delete(saved);cJSON_Delete(receipt);pthread_mutex_unlock(&journal_lock);return ret;
}
static int delivery_path(const char *peer,char *path,size_t capacity)
{
  if(!peer||!hex_digest(peer))return -EINVAL;
  int n=snprintf(path,capacity,ROOT "/outgoing/%s.json",peer);
  return n<0||(size_t)n>=capacity?-ENAMETOOLONG:0;
}
/* Missing transport metadata safely requests historical receipts again.
 * Corrupt metadata or missing committed originals cannot advance the cursor. */
static int delivery_load_locked(const char *peer,const char *path,uint64_t *next)
{
  char *saved=NULL;size_t bytes=0;*next=1;
  int ret=buffer_agent_read_bytes(path,4096,&saved,&bytes);if(ret==-ENOENT)return 0;if(ret)return ret;
  cJSON *j=NULL;ret=buffer_source_json_object(saved,bytes,4096,&j);release(saved);
  uint64_t schema=0,sequence=0,total=0,last=0;
  if(!ret&&(cJSON_GetArraySize(j)!=6||!number(j,"schema_version",&schema)||schema!=1||
     strcmp(text(j,"peer"),peer)||strcmp(text(j,"source_id"),text(head,"source_id"))||
     !number(j,"last_sequence",&sequence)||!sequence||!number(head,"last_sequence",&last)||sequence>last||
     !number(j,"last_size",&total)||!total||total>BUFFER_SOURCE_EVENT_BYTES||!hex_digest(text(j,"last_fingerprint"))))ret=-EIO;
  char *raw=NULL,hash[65];size_t length=0;
  if(!ret)ret=local_read_locked(sequence,&raw,&length);
  if(!ret)ret=digest(raw,length,hash);
  if(!ret&&(length!=total||strcmp(hash,text(j,"last_fingerprint"))))ret=-EIO;
  /* A previous atomic rename may have succeeded before parent fsync failed.
   * Finish that durability boundary before advertising this cursor again. */
  if(!ret)ret=confirm_receipt(path,j);
  if(!ret)*next=sequence+1;
  release(raw);cJSON_Delete(j);return ret;
}
int buffer_source_delivery_progress(const char *peer,bool (*authorize)(void *),void *context,uint64_t *next)
{
  if (!next || !authorize)
    return -EINVAL;
  *next = 0;
  if(!authorize(context))return -EACCES;
  char path[320];int ret=delivery_path(peer,path,sizeof(path));if(ret)return ret;
  pthread_mutex_lock(&journal_lock);ret=ensure_locked();
  if(!ret)ret=delivery_load_locked(peer,path,next);
  if(!authorize(context)){ret=-EACCES;*next=0;}
  pthread_mutex_unlock(&journal_lock);return ret;
}
int buffer_source_delivery_accept(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,
                                 size_t length,bool (*authorize)(void *),void *context,uint64_t *next)
{
  if(!next||!authorize||!source||!fingerprint||!hex_digest(fingerprint)||!sequence||sequence>=MAX_COUNTER||
     !length||length>BUFFER_SOURCE_EVENT_BYTES)return -EINVAL;
  *next=0;if(!authorize(context))return -EACCES;
  char path[320];int ret=delivery_path(peer,path,sizeof(path));if(ret)return ret;
  pthread_mutex_lock(&journal_lock);ret=ensure_locked();
  if(!ret&&strcmp(source,text(head,"source_id")))ret=-EEXIST;
  if(!ret)ret=delivery_load_locked(peer,path,next);
  if(!ret&&sequence>*next)ret=-ERANGE;
  char *raw=NULL,hash[65];size_t bytes=0;cJSON *j=NULL;
  if(!ret)ret=local_read_locked(sequence,&raw,&bytes);
  if(!ret)ret=digest(raw,bytes,hash);
  if(!ret&&(bytes!=length||strcmp(hash,fingerprint)))ret=-EEXIST;
  if(!ret&&!authorize(context))ret=-EACCES;
  if(!ret&&sequence==*next){
    j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"schema_version",1)&&
      cJSON_AddStringToObject(j,"peer",peer)&&cJSON_AddStringToObject(j,"source_id",source)&&
      cJSON_AddNumberToObject(j,"last_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"last_fingerprint",hash)&&
      cJSON_AddNumberToObject(j,"last_size",(double)bytes);
    ret=built?json_write(path,j,4096):-ENOMEM;
    if(!ret)*next=sequence+1;
  }
  if(!authorize(context))ret=-EACCES;
  release(raw);cJSON_Delete(j);pthread_mutex_unlock(&journal_lock);return ret;
}
/* visibility-v1 keeps each original receipt independently. A prepared random
 * slot is neither coverage nor a full receipt. All helpers hold journal_lock. */
struct visible_node_s {
  uint64_t sequence;size_t bytes;char event[161],hash[65],token[33];
  bool offered,slot_confirmed,original_confirmed;
};
struct visible_head_s {uint64_t covered,original;};
struct visible_budget_s {unsigned work;size_t bytes;};
static bool visible_token(const char *value,bool empty)
{if(!value)return false;size_t n=strlen(value);if(!n)return empty;if(n!=32)return false;
  for (size_t i = 0; i < n; i++)
    if (!strchr("0123456789abcdef", value[i]))
      return false;
  return true;
}
static int visible_path(const char *peer,uint64_t sequence,char path[320])
{
  if(!peer||!hex_digest(peer))return -EINVAL;
  int n=sequence?snprintf(path,320,ROOT "/visible-outgoing/%s/%020llu.json",peer,(unsigned long long)sequence):
    snprintf(path,320,ROOT "/visible-outgoing/%s/head.json",peer);
  return n<0||n>=320?-ENAMETOOLONG:0;
}
static int visible_json(const char *path,cJSON **out)
{
  *out=NULL;char *raw=NULL;size_t bytes=0;int ret=buffer_agent_read_bytes(path,4096,&raw,&bytes);
  if (!ret)
    ret = buffer_source_json_object(raw, bytes, 4096, out);
  release(raw);
  return ret;
}
static int visible_binding(uint64_t sequence,struct visible_node_s *node)
{
  memset(node,0,sizeof(*node));node->sequence=sequence;char *raw=NULL;size_t bytes=0;cJSON *j=NULL;
  int ret=local_read_locked(sequence,&raw,&bytes);if(!ret)ret=digest(raw,bytes,node->hash);
  if(!ret)ret=buffer_source_envelope_decode(raw,bytes,&j);
  if(!ret){snprintf(node->event,sizeof(node->event),"%s",text(cJSON_GetObjectItemCaseSensitive(j,"event"),"event_id"));node->bytes=bytes;}
  cJSON_Delete(j);release(raw);return ret;
}
static int visible_node_load(const char *peer,uint64_t sequence,struct visible_node_s *node)
{
  char path[320];int ret=visible_path(peer,sequence,path);if(ret)return ret;cJSON *j=NULL;ret=visible_json(path,&j);if(ret)return ret;
  uint64_t version=0,seq=0,bytes=0;const cJSON *offered=cJSON_GetObjectItemCaseSensitive(j,"original_offered"),
    *slot=cJSON_GetObjectItemCaseSensitive(j,"slot_confirmed"),*original=cJSON_GetObjectItemCaseSensitive(j,"original_confirmed");
  if(cJSON_GetArraySize(j)!=11||!number(j,"visibility_version",&version)||version!=1||strcmp(text(j,"peer"),peer)||
     strcmp(text(j,"source_id"),text(head,"source_id"))||!number(j,"source_sequence",&seq)||seq!=sequence||
     !number(j,"total",&bytes)||!bytes||bytes>BUFFER_SOURCE_EVENT_BYTES||!hex_digest(text(j,"record_sha256"))||
     !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(j,"slot_token"))||!visible_token(text(j,"slot_token"),true)||
     !cJSON_IsBool(offered)||!cJSON_IsBool(slot)||!cJSON_IsBool(original)||
     (cJSON_IsTrue(original)&&!cJSON_IsTrue(offered))||(cJSON_IsTrue(slot)&&!text(j,"slot_token")[0]))ret=-EIO;
  if(!ret)ret=visible_binding(sequence,node);
  if(!ret&&(node->bytes!=bytes||strcmp(node->hash,text(j,"record_sha256"))||strcmp(node->event,text(j,"event_id"))))ret=-EIO;
  if(!ret){snprintf(node->token,sizeof(node->token),"%s",text(j,"slot_token"));node->offered=cJSON_IsTrue(offered);
    node->slot_confirmed=cJSON_IsTrue(slot);node->original_confirmed=cJSON_IsTrue(original);}
  cJSON_Delete(j);return ret;
}
static int visible_node_save(const char *peer,const struct visible_node_s *node)
{
  char path[320];int ret=visible_path(peer,node->sequence,path);if(ret)return ret;cJSON *j=cJSON_CreateObject();
  bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"peer",peer)&&cJSON_AddStringToObject(j,"source_id",text(head,"source_id"))&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)node->sequence)&&cJSON_AddStringToObject(j,"event_id",node->event)&&cJSON_AddStringToObject(j,"record_sha256",node->hash)&&
    cJSON_AddNumberToObject(j,"total",(double)node->bytes)&&cJSON_AddStringToObject(j,"slot_token",node->token)&&cJSON_AddBoolToObject(j,"original_offered",node->offered)&&
    cJSON_AddBoolToObject(j,"slot_confirmed",node->slot_confirmed)&&cJSON_AddBoolToObject(j,"original_confirmed",node->original_confirmed);
  ret=built?json_write(path,j,4096):-ENOMEM;cJSON_Delete(j);return ret;
}
static int visible_head_save(const char *peer,const struct visible_head_s *value)
{
  char path[320];int ret=visible_path(peer,0,path);if(ret)return ret;cJSON *j=cJSON_CreateObject();
  bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"peer",peer)&&cJSON_AddStringToObject(j,"source_id",text(head,"source_id"))&&
    cJSON_AddNumberToObject(j,"covered_sequence",(double)value->covered)&&cJSON_AddNumberToObject(j,"original_sequence",(double)value->original);
  ret=built?json_write(path,j,4096):-ENOMEM;cJSON_Delete(j);return ret;
}
static int visible_head_load(const char *peer,struct visible_head_s *value,bool (*authorize)(void *),void *context)
{
  memset(value,0,sizeof(*value));char path[320],directory[320];int ret=visible_path(peer,0,path);if(ret)return ret;
  int n=snprintf(directory,sizeof(directory),ROOT "/visible-outgoing/%s",peer);if(n<0||(size_t)n>=sizeof(directory))return -ENAMETOOLONG;
  ret=buffer_agent_sync_parent(ROOT "/visible-outgoing");
  if (!ret)
    ret = buffer_agent_private_directory(directory);
  if (!ret)
    ret = buffer_agent_sync_parent(directory);
  if (ret)
    return ret;
  cJSON *j=NULL;ret=visible_json(path,&j);
  if(ret==-ENOENT){
    DIR *dir=opendir(directory);if(!dir)return -errno;ret=0;
    for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
      if(strcmp(entry->d_name,".")&&strcmp(entry->d_name,"..")){ret=-EIO;break;}}
    if(closedir(dir)&&!ret)ret=-errno;
    return ret?ret:authorize(context)?visible_head_save(peer,value):-EACCES;
  }
  uint64_t version=0,last=0;
  if(!ret&&(cJSON_GetArraySize(j)!=5||!number(j,"visibility_version",&version)||version!=1||strcmp(text(j,"peer"),peer)||
     strcmp(text(j,"source_id"),text(head,"source_id"))||!number(j,"covered_sequence",&value->covered)||
     !number(j,"original_sequence",&value->original)||!number(head,"last_sequence",&last)||value->original>value->covered||value->covered>last))ret=-EIO;
  cJSON_Delete(j);struct visible_node_s boundary;
  if(!ret&&value->covered){ret=visible_node_load(peer,value->covered,&boundary);if(!ret&&!boundary.slot_confirmed&&!boundary.original_confirmed)ret=-EIO;}
  if(!ret&&value->original){ret=visible_node_load(peer,value->original,&boundary);if(!ret&&!boundary.original_confirmed)ret=-EIO;}
  return ret==-ENOENT?-EIO:ret;
}
/* Receipt nodes are authoritative; the head is a conservative checkpoint.
 * Replay of a failed head write only advances through real persisted flags.
 * Never infer an original receipt from a slot or an offered fragment. */
static int visible_reconcile(const char *peer,struct visible_head_s *value,bool (*authorize)(void *),void *context,
                             struct buffer_source_visible_delivery_s *out,struct visible_budget_s *budget)
{
  bool stopped=false;uint64_t last=0;if(!number(head,"last_sequence",&last))return -EIO;
  while(value->covered<last&&budget->work<16&&budget->bytes<2*1024*1024){
    struct visible_node_s node;int ret=visible_node_load(peer,value->covered+1,&node);if(ret==-ENOENT){stopped=true;break;}if(ret)return ret;
    budget->work++;budget->bytes+=node.bytes;
    if(!node.slot_confirmed&&!node.original_confirmed){stopped=true;break;}
    if (!authorize(context))
      return -EACCES;
    ret = visible_node_save(peer, &node);
    if (ret)
      return ret;
    value->covered++;
  }
  bool checking=!stopped&&value->covered<last&&(budget->work>=16||budget->bytes>=2*1024*1024);
  stopped=false;
  while(value->original<value->covered&&budget->work<16&&budget->bytes<2*1024*1024){
    struct visible_node_s node;int ret=visible_node_load(peer,value->original+1,&node);if(ret)return ret==-ENOENT?-EIO:ret;
    budget->work++;budget->bytes+=node.bytes;
    if(!node.slot_confirmed&&!node.original_confirmed)return -EIO;
    if(!node.original_confirmed){stopped=true;break;}
    if (!authorize(context))
      return -EACCES;
    ret = visible_node_save(peer, &node);
    if (ret)
      return ret;
    value->original++;
  }
  if(!stopped&&value->original<value->covered&&(budget->work>=16||budget->bytes>=2*1024*1024))checking=true;
  if (!authorize(context))
    return -EACCES;
  int ret = visible_head_save(peer, value);
  if (ret)
    return ret;
  out->covered_next=value->covered+1;out->original_next=value->original+1;out->checking=checking;return 0;
}
static int visible_open(const char *peer,const char *source,bool (*authorize)(void *),void *context)
{
  if (!peer || !hex_digest(peer) || !authorize)
    return -EINVAL;
  if (!authorize(context))
    return -EACCES;
  pthread_mutex_lock(&journal_lock);int ret=ensure_locked();
  if(!ret&&source&&strcmp(source,text(head,"source_id")))ret=-EEXIST;
  if (!ret && !authorize(context))
    ret = -EACCES;
  if (ret)
    pthread_mutex_unlock(&journal_lock);
  return ret;
}
int buffer_source_visible_progress(const char *peer,bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{
  if (!out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  int ret = visible_open(peer, NULL, authorize, context);
  if (ret)
    return ret;
  struct visible_head_s value;
  struct visible_budget_s budget={0};
  ret=visible_head_load(peer,&value,authorize,context);if(!ret)ret=visible_reconcile(peer,&value,authorize,context,out,&budget);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    memset(out, 0, sizeof(*out));
  pthread_mutex_unlock(&journal_lock);
  return ret;
}
static int visible_prepare(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,size_t length,bool offered,
                            bool (*authorize)(void *),void *context,char token[33])
{
  if (!token)
    return -EINVAL;
  token[0] = 0;
  if(!source||!sequence||sequence>=MAX_COUNTER||(offered&&(!fingerprint||!hex_digest(fingerprint)||!length||length>BUFFER_SOURCE_EVENT_BYTES)))return -EINVAL;
  int ret=visible_open(peer,source,authorize,context);if(ret)return ret;struct visible_head_s value;struct visible_node_s node;
  ret=visible_head_load(peer,&value,authorize,context);
  if(!ret){ret=visible_node_load(peer,sequence,&node);if(ret==-ENOENT){if(sequence<=value.covered)ret=-EIO;else ret=visible_binding(sequence,&node);}}
  if(!ret&&offered&&(node.bytes!=length||strcmp(node.hash,fingerprint)))ret=-EEXIST;
  if(!ret&&!offered&&!node.token[0]){
    unsigned char random[16];ret=buffer_entropy_read(random,sizeof(random));
    if(!ret)for(unsigned i=0;i<16;i++)snprintf(node.token+2*i,3,"%02x",random[i]);
    volatile unsigned char *clear=random;for(unsigned i=0;i<sizeof(random);i++)clear[i]=0;
  }
  if(!ret){if(offered)node.offered=true;ret=authorize(context)?visible_node_save(peer,&node):-EACCES;}
  if(!ret)snprintf(token,33,"%s",node.token);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    token[0] = 0;
  pthread_mutex_unlock(&journal_lock);
  return ret;
}
int buffer_source_visible_prepare(const char *peer,const char *source,uint64_t sequence,bool (*authorize)(void *),void *context,char token[33])
{return visible_prepare(peer,source,sequence,NULL,0,false,authorize,context,token);}
int buffer_source_visible_offered(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,size_t length,
                                  bool (*authorize)(void *),void *context,char token[33])
{return visible_prepare(peer,source,sequence,fingerprint,length,true,authorize,context,token);}
static int visible_accept(const char *peer,const char *source,uint64_t sequence,const char *token,const char *fingerprint,size_t length,bool original,
                           bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{
  if (!out)
    return -EINVAL;
  memset(out, 0, sizeof(*out));
  if(!source||!sequence||sequence>=MAX_COUNTER||!visible_token(token,original)||
     (original&&(!fingerprint||!hex_digest(fingerprint)||!length||length>BUFFER_SOURCE_EVENT_BYTES)))return -EINVAL;
  int ret=visible_open(peer,source,authorize,context);if(ret)return ret;struct visible_head_s value;struct visible_budget_s budget={0};
  ret=visible_head_load(peer,&value,authorize,context);
  if(!ret)ret=visible_reconcile(peer,&value,authorize,context,out,&budget);
  if(!ret&&sequence>value.covered+1)ret=-ERANGE;
  struct visible_node_s node;if(!ret)ret=visible_node_load(peer,sequence,&node);
  if(!ret&&(*token?strcmp(node.token,token):!original))ret=-EEXIST;
  if(!ret&&original&&(!node.offered||node.bytes!=length||strcmp(node.hash,fingerprint)))ret=-EEXIST;
  if(!ret){if(original)node.original_confirmed=true;else node.slot_confirmed=true;
    ret=authorize(context)?visible_node_save(peer,&node):-EACCES;}
  // This exact node is already validated and durable. Do not delay forward
  // coverage behind a large historical-original prefix reconciliation.
  if(!ret&&sequence==value.covered+1)value.covered=sequence;
  if(!ret)ret=visible_reconcile(peer,&value,authorize,context,out,&budget);
  if (!authorize(context))
    ret = -EACCES;
  if (ret)
    memset(out, 0, sizeof(*out));
  pthread_mutex_unlock(&journal_lock);
  return ret;
}
int buffer_source_visible_accept_slot(const char *peer,const char *source,uint64_t sequence,const char *token,
                                      bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{return visible_accept(peer,source,sequence,token,NULL,0,false,authorize,context,out);}
int buffer_source_visible_accept_original(const char *peer,const char *source,uint64_t sequence,const char *token,const char *fingerprint,size_t length,
                                          bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{return visible_accept(peer,source,sequence,token,fingerprint,length,true,authorize,context,out);}
#else
int buffer_source_projection_local_deleted(const char *path,bool *deleted)
{(void)path;if(!deleted)return -EINVAL;*deleted=false;return 0;}
cJSON *buffer_source_projection_local_read(const char *path,size_t limit)
{return buffer_agent_read_json_plain(path,limit);}
int buffer_source_events_init(void){return 0;}
int buffer_source_events_maintenance(void){return 0;}
int buffer_source_events_ready(void){return 0;}
cJSON *buffer_source_record_read(const char *path,size_t limit){return buffer_agent_read_json_plain(path,limit);}
bool buffer_source_is_record_path(const char *path){(void)path;return false;}
int buffer_source_record_save(const char *path,const cJSON *record){(void)path;(void)record;return -ENOTSUP;}
bool buffer_source_is_projection_path(const char *path){(void)path;return false;}
int buffer_source_projection_save(const char *path,const char *raw,const char *type,const char *action)
{(void)path;(void)raw;(void)type;(void)action;return -ENOTSUP;}
int buffer_source_projection_json(const char *path,const cJSON *value,const char *type,const char *action)
{(void)type;(void)action;return buffer_agent_atomic_json(path,value);}
int buffer_source_projection_legacy(const char *path,const cJSON *value)
{return buffer_agent_atomic_json(path,value);}
cJSON *buffer_source_projection_read(const char *path,size_t limit)
{return buffer_agent_read_json_plain(path,limit);}
int buffer_source_projection_read_bytes(const char *path,size_t limit,char **raw,size_t *length)
{return buffer_agent_read_bytes(path,limit,raw,length);}
int buffer_source_projection_remove(const char *path,const char *type)
{(void)type;return unlink(path)&&errno!=ENOENT?-errno:0;}
int buffer_source_assistant_operation(const char *id,cJSON **operation,cJSON **request)
{(void)id;if(operation)*operation=NULL;if(request)*request=NULL;return -ENOTSUP;}
int buffer_source_focus_operation(const char *id,cJSON **request,cJSON **result)
{(void)id;if(request)*request=NULL;if(result)*result=NULL;return -ENOTSUP;}
int buffer_source_projection_ids(const char *kind,const char *after,unsigned limit,cJSON **ids)
{(void)kind;(void)after;(void)limit;if(ids)*ids=NULL;return -ENOTSUP;}
int buffer_source_capture_ensure(const char *id){(void)id;return 0;}
int buffer_source_local_head(char *source,size_t capacity,uint64_t *sequence)
{if(source&&capacity)source[0]=0;if(sequence)*sequence=0;return -ENOTSUP;}
int buffer_source_local_read(uint64_t sequence,char **raw,size_t *length)
{(void)sequence;if(raw)*raw=NULL;if(length)*length=0;return -ENOTSUP;}
int buffer_source_local_repair_step(uint64_t *cursor)
{(void)cursor;return 0;}
int buffer_source_blob_open(uint64_t sequence,const char *record_hash,const char *blob_hash,uint64_t bytes,int *descriptor)
{(void)sequence;(void)record_hash;(void)blob_hash;(void)bytes;if(descriptor)*descriptor=-1;return -ENOTSUP;}
int buffer_source_blob_accept(const char *peer,const char *source,uint64_t sequence,const char *record_hash,
                              const char *blob_hash,uint64_t bytes,bool (*authorize)(void *),void *context)
{(void)peer;(void)source;(void)sequence;(void)record_hash;(void)blob_hash;(void)bytes;(void)authorize;(void)context;return -ENOTSUP;}
int buffer_source_delivery_progress(const char *peer,bool (*authorize)(void *),void *context,uint64_t *next)
{(void)peer;(void)authorize;(void)context;if(next)*next=0;return -ENOTSUP;}
int buffer_source_delivery_accept(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,
                                 size_t length,bool (*authorize)(void *),void *context,uint64_t *next)
{(void)peer;(void)source;(void)sequence;(void)fingerprint;(void)length;(void)authorize;(void)context;if(next)*next=0;return -ENOTSUP;}
int buffer_source_visible_progress(const char *peer,bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{(void)peer;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_visible_prepare(const char *peer,const char *source,uint64_t sequence,bool (*authorize)(void *),void *context,char token[33])
{(void)peer;(void)source;(void)sequence;(void)authorize;(void)context;if(token)token[0]=0;return -ENOTSUP;}
int buffer_source_visible_offered(const char *peer,const char *source,uint64_t sequence,const char *fingerprint,size_t length,bool (*authorize)(void *),void *context,char token[33])
{(void)peer;(void)source;(void)sequence;(void)fingerprint;(void)length;(void)authorize;(void)context;if(token)token[0]=0;return -ENOTSUP;}
int buffer_source_visible_accept_slot(const char *peer,const char *source,uint64_t sequence,const char *token,bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{(void)peer;(void)source;(void)sequence;(void)token;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
int buffer_source_visible_accept_original(const char *peer,const char *source,uint64_t sequence,const char *token,const char *fingerprint,size_t length,
                                          bool (*authorize)(void *),void *context,struct buffer_source_visible_delivery_s *out)
{(void)peer;(void)source;(void)sequence;(void)token;(void)fingerprint;(void)length;(void)authorize;(void)context;if(out)memset(out,0,sizeof(*out));return -ENOTSUP;}
#endif
