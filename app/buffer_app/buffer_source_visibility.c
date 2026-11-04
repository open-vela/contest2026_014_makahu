/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_visibility.h"
#include "buffer_source_events.h"
#include "buffer_source_envelope.h"
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define ROOT CONFIG_BUFFER_VELA_DATA_DIR "/event-journal"
#define DATA ROOT "/remote-visibility"
#define OWNERS ROOT "/remote-visibility-owners"
#define WAL ROOT "/remote-visibility-pending.json"
#define STAGING ROOT "/remote-visibility-envelope.pending"
#define BUILD ROOT "/remote-visibility-index-build.json"
#define MAX 9007199254740991ULL
#define WAL_BYTES 16384
struct head_s {
  char source[97],peer[65];
  uint64_t base,covered,original,count,first,last,clock;
  bool exists;
};
struct slot_s {uint64_t sequence,previous,next;char token[33],event[161],hash[65];bool filled;};
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j,const char *key,uint64_t *out)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);if(!cJSON_IsNumber(v)||!isfinite(v->valuedouble)||v->valuedouble<0||v->valuedouble>MAX||floor(v->valuedouble)!=v->valuedouble)return false;
  *out=(uint64_t)v->valuedouble;return true;}
static bool hex(const char *s,size_t length)
{if(!s||strlen(s)!=length)return false;for(size_t i=0;i<length;i++)if(!strchr("0123456789abcdef",s[i]))return false;return true;}
static bool identity(const char *s,size_t maximum,bool colon)
{if(!s||!s[0]||strlen(s)>maximum)return false;for(const char *p=s;*p;p++)if(!((*p>='A'&&*p<='Z')||(*p>='a'&&*p<='z')||(*p>='0'&&*p<='9')||strchr("_.-",*p)||(colon&&*p==':')))return false;return true;}
static void release(char *raw,size_t bytes)
{if(raw){volatile char *p=raw;while(bytes--)*p++=0;free(raw);}}
static int digest(const void *raw,size_t bytes,char hash[65])
{unsigned char sha[32];if(mbedtls_sha256(raw,bytes,sha,0))return -EIO;for(unsigned i=0;i<32;i++)snprintf(hash+2*i,3,"%02x",sha[i]);return 0;}
static int read_json(const char *path,size_t limit,cJSON **out)
{errno=0;*out=buffer_agent_read_json_plain(path,limit);return *out?0:errno?-errno:-EIO;}
static int write_json(const char *path,const cJSON *j,size_t limit)
{char *raw=cJSON_PrintUnformatted(j);if(!raw)return -ENOMEM;size_t bytes=strlen(raw);int ret=buffer_agent_atomic_bytes(path,raw,bytes,limit);release(raw,bytes);return ret;}
static int directory(char path[320],const char *source)
{char hash[65];int ret=digest(source,strlen(source),hash);if(ret)return ret;int n=snprintf(path,320,DATA "/%s",hash);return n<0||n>=320?-ENAMETOOLONG:0;}
static int meta_path(char path[320],const char *source)
{char hash[65];int ret=digest(source,strlen(source),hash);if(ret)return ret;int n=snprintf(path,320,OWNERS "/%s.json",hash);return n<0||n>=320?-ENAMETOOLONG:0;}
static int position(char path[320],const char *source,uint64_t sequence,const char *suffix)
{char parent[320];int ret=directory(parent,source);if(ret)return ret;int n=snprintf(path,320,"%s/%020llu.%s",parent,(unsigned long long)sequence,suffix);return n<0||n>=320?-ENAMETOOLONG:0;}
static int empty(const char *path)
{struct stat st;if(lstat(path,&st))return -errno;if(!S_ISDIR(st.st_mode)||(st.st_mode&0077))return -EACCES;
  DIR *dir=opendir(path);if(!dir)return -errno;int ret=0;for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
  if(strcmp(entry->d_name,".")&&strcmp(entry->d_name,"..")){ret=-EIO;break;}}if(closedir(dir)&&!ret)ret=-errno;return ret;}
static int absent(const char *path)
{struct stat st;if(!lstat(path,&st))return -EIO;return errno==ENOENT?0:-errno;}
static int roots(void)
{int ret=buffer_agent_private_directory(DATA);if(!ret)ret=buffer_agent_private_directory(OWNERS);if(!ret)ret=buffer_agent_sync_parent(DATA);return ret;}
static int validate_source(const struct buffer_source_visibility_store_s *store,const char *source,const char *peer)
{return !identity(source,96,false)||!strcmp(source,store->local_source)||(peer&&!hex(peer,64))?-EINVAL:0;}
static bool valid_head(const struct head_s *h)
{
  if(!identity(h->source,96,false)||!hex(h->peer,64)||h->base>h->original||h->original>h->covered||h->covered>=MAX||
     h->count>h->covered-h->base||h->count<h->original-h->base||h->clock>MAX||(!h->count&&h->clock))return false;
  uint64_t held=h->covered-h->base-h->count;
  if(!held)return !h->first&&!h->last&&h->original==h->covered;
  return h->first==h->original+1&&h->first>h->base&&h->first<=h->last&&h->last<=h->covered&&
    (held==1?h->first==h->last:h->first<h->last);
}
static int parse_head(const cJSON *j,struct head_s *h)
{
  memset(h,0,sizeof(*h));uint64_t version=0;
  if(!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=10||!number(j,"visibility_version",&version)||version!=1||
     !identity(text(j,"source_id"),96,false)||!hex(text(j,"owner_peer"),64)||!number(j,"base_sequence",&h->base)||
     !number(j,"covered_sequence",&h->covered)||!number(j,"original_sequence",&h->original)||!number(j,"record_count",&h->count)||
     !number(j,"first_held",&h->first)||!number(j,"last_held",&h->last)||!number(j,"observed_clock",&h->clock))return -EIO;
  snprintf(h->source,sizeof(h->source),"%s",text(j,"source_id"));snprintf(h->peer,sizeof(h->peer),"%s",text(j,"owner_peer"));h->exists=true;
  return valid_head(h)?0:-EIO;
}
static cJSON *head_json(const struct head_s *h)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"source_id",h->source)&&
    cJSON_AddStringToObject(j,"owner_peer",h->peer)&&cJSON_AddNumberToObject(j,"base_sequence",(double)h->base)&&
    cJSON_AddNumberToObject(j,"covered_sequence",(double)h->covered)&&cJSON_AddNumberToObject(j,"original_sequence",(double)h->original)&&
    cJSON_AddNumberToObject(j,"record_count",(double)h->count)&&cJSON_AddNumberToObject(j,"first_held",(double)h->first)&&
    cJSON_AddNumberToObject(j,"last_held",(double)h->last)&&cJSON_AddNumberToObject(j,"observed_clock",(double)h->clock);
  if(!built){cJSON_Delete(j);return NULL;}return j;
}
static bool same_head(const struct head_s *a,const struct head_s *b)
{return !strcmp(a->source,b->source)&&!strcmp(a->peer,b->peer)&&a->base==b->base&&a->covered==b->covered&&a->original==b->original&&
  a->count==b->count&&a->first==b->first&&a->last==b->last&&a->clock==b->clock;}
static int save_head(const struct head_s *h)
{char path[320];int ret=meta_path(path,h->source);if(ret)return ret;cJSON *j=head_json(h);ret=j?write_json(path,j,4096):-ENOMEM;cJSON_Delete(j);return ret;}
static int slot_read(const struct head_s *h,uint64_t sequence,struct slot_s *slot)
{
  char path[320];int ret=position(path,h->source,sequence,"slot.json");if(ret)return ret;cJSON *j=NULL;ret=read_json(path,4096,&j);if(ret)return ret;
  memset(slot,0,sizeof(*slot));uint64_t version=0;const char *state=text(j,"state");
  if(!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=10||!number(j,"visibility_version",&version)||version!=1||
     strcmp(text(j,"source_id"),h->source)||strcmp(text(j,"owner_peer"),h->peer)||!number(j,"source_sequence",&slot->sequence)||slot->sequence!=sequence||
     sequence<=h->base||sequence>=MAX||!hex(text(j,"slot_token"),32)||!number(j,"previous",&slot->previous)||!number(j,"next",&slot->next)||
     (slot->previous&&(slot->previous<=h->base||slot->previous>=sequence))||(slot->next&&(slot->next<=sequence||slot->next>=MAX))||
     !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(j,"event_id"))||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(j,"fingerprint"))||
     (strcmp(state,"held")&&strcmp(state,"filled")))ret=-EIO;
  if(!ret){slot->filled=!strcmp(state,"filled");
    if(slot->filled?(!identity(text(j,"event_id"),160,true)||!hex(text(j,"fingerprint"),64)):(text(j,"event_id")[0]||text(j,"fingerprint")[0]))ret=-EIO;}
  if(!ret){snprintf(slot->token,sizeof(slot->token),"%s",text(j,"slot_token"));snprintf(slot->event,sizeof(slot->event),"%s",text(j,"event_id"));snprintf(slot->hash,sizeof(slot->hash),"%s",text(j,"fingerprint"));}
  cJSON_Delete(j);return ret;
}
static int slot_write(const struct head_s *h,const struct slot_s *slot)
{
  char path[320];int ret=position(path,h->source,slot->sequence,"slot.json");if(ret)return ret;cJSON *j=cJSON_CreateObject();
  bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"source_id",h->source)&&cJSON_AddStringToObject(j,"owner_peer",h->peer)&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)slot->sequence)&&cJSON_AddStringToObject(j,"slot_token",slot->token)&&cJSON_AddStringToObject(j,"state",slot->filled?"filled":"held")&&
    cJSON_AddNumberToObject(j,"previous",(double)slot->previous)&&cJSON_AddNumberToObject(j,"next",(double)slot->next)&&
    cJSON_AddStringToObject(j,"event_id",slot->event)&&cJSON_AddStringToObject(j,"fingerprint",slot->hash);
  ret=built?write_json(path,j,4096):-ENOMEM;cJSON_Delete(j);return ret;
}
static int chain_node(const struct head_s *h,const struct slot_s *slot)
{
  if(slot->filled||slot->sequence<h->first||slot->sequence>h->last||slot->previous>h->covered||slot->next>h->covered||
     ((slot->previous==0)!=(slot->sequence==h->first))||((slot->next==0)!=(slot->sequence==h->last)))return -EIO;
  struct slot_s neighbor;int ret=0;
  if(slot->previous){ret=slot_read(h,slot->previous,&neighbor);if(!ret&&(neighbor.filled||neighbor.next!=slot->sequence))ret=-EIO;}
  if(!ret&&slot->next){ret=slot_read(h,slot->next,&neighbor);if(!ret&&(neighbor.filled||neighbor.previous!=slot->sequence))ret=-EIO;}
  return ret;
}
static int raw_read(const struct head_s *h,uint64_t sequence,char **raw,size_t *bytes)
{
  char path[320],hash[65],*saved=NULL;size_t length=0;*raw=NULL;*bytes=0;
  int ret=position(path,h->source,sequence,"json");if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,raw,bytes);
  if (!ret)
    ret = digest(*raw, *bytes, hash);
  if (!ret)
    ret = position(path, h->source, sequence, "sha256");
  if (!ret)
    ret = buffer_agent_read_bytes(path, 64, &saved, &length);
  if (!ret && (length != 64 || memcmp(hash, saved, 64)))
    ret = -EIO;
  cJSON *j = NULL;
  uint64_t seq = 0;
  if(!ret)ret=buffer_source_envelope_decode(*raw,*bytes,&j);
  uint64_t clock=0;
  if(!ret&&(strcmp(text(j,"source_id"),h->source)||!number(j,"source_sequence",&seq)||seq!=sequence||
      !number(cJSON_GetObjectItemCaseSensitive(j,"event"),"logical_clock",&clock)||clock>h->clock))ret=-EIO;
  release(saved,length);cJSON_Delete(j);if(ret){release(*raw,*bytes);*raw=NULL;*bytes=0;}return ret==-ENOENT?-EIO:ret;
}
static int no_raw(const struct head_s *h,uint64_t sequence)
{char path[320];int ret=position(path,h->source,sequence,"json");if(!ret)ret=absent(path);if(!ret)ret=position(path,h->source,sequence,"sha256");
  if (!ret)
    ret = absent(path);
  if (!ret)
    ret = position(path, h->source, sequence, "view.json");
  return ret ? ret : absent(path);
}
static int read_at(const struct buffer_source_visibility_store_s *store,const struct head_s *h,uint64_t sequence,char **raw,size_t *bytes)
{
  *raw=NULL;*bytes=0;if(!sequence||sequence>h->covered)return -ENOENT;
  if(sequence<=h->base)return store->read_base(h->source,sequence,raw,bytes);
  struct slot_s slot;int ret=slot_read(h,sequence,&slot);
  if(!ret&&(slot.previous>h->covered||slot.next>h->covered))return -EIO;
  if(!ret&&!slot.filled){ret=no_raw(h,sequence);return ret?ret:-EAGAIN;}
  if (ret && ret != -ENOENT)
    return ret;
  bool marker = !ret;
  ret = raw_read(h, sequence, raw, bytes);
  if(!ret&&marker){char hash[65];cJSON *j=NULL;ret=digest(*raw,*bytes,hash);if(!ret)ret=buffer_source_envelope_decode(*raw,*bytes,&j);
    if (!ret &&
        (strcmp(hash, slot.hash) ||
         strcmp(text(cJSON_GetObjectItemCaseSensitive(j, "event"), "event_id"),
                slot.event)))
      ret = -EIO;
    cJSON_Delete(j);
  }
  if(ret){release(*raw,*bytes);*raw=NULL;*bytes=0;}return ret;
}
static int load_head(const struct buffer_source_visibility_store_s *store,const char *source,const char *peer,bool validate,struct head_s *h)
{
  memset(h,0,sizeof(*h));int ret=validate_source(store,source,peer);if(ret)return ret;char path[320];ret=meta_path(path,source);if(ret)return ret;
  cJSON *j=NULL;ret=read_json(path,4096,&j);
  if(ret==-ENOENT){
    ret=directory(path,source);if(!ret){int check=empty(path);ret=check==-ENOENT?0:check;}if(ret)return ret;
    if (!peer)
      return -ENOENT;
    uint64_t base = 0;
    ret = store->base(source, peer, false, &base);
    if (ret && ret != -ENOENT)
      return ret;
    snprintf(h->source,sizeof(h->source),"%s",source);snprintf(h->peer,sizeof(h->peer),"%s",peer);h->base=h->covered=h->original=base;return 0;
  }
  if (!ret)
    ret = parse_head(j, h);
  cJSON_Delete(j);
  if (ret)
    return ret;
  if (strcmp(h->source, source))
    return -EIO;
  if (peer && strcmp(h->peer, peer))
    return -EACCES;
  uint64_t base=0;ret=store->base(source,h->peer,false,&base);if(ret)return ret==-ENOENT?-EIO:ret;if(base!=h->base)return -EIO;
  ret=directory(path,source);if(ret)return ret;struct stat st;if(lstat(path,&st))return errno==ENOENT?-EIO:-errno;
  if(!S_ISDIR(st.st_mode)||(st.st_mode&0077))return -EACCES;
  if(!validate)return 0;
  struct slot_s first,last;
  if(h->first){ret=slot_read(h,h->first,&first);if(!ret)ret=slot_read(h,h->last,&last);
    if(!ret&&(first.filled||first.previous||last.filled||last.next||first.next>h->covered||last.previous>h->covered))ret=-EIO;
    if (!ret)
      ret = chain_node(h, &first);
    if (!ret)
      ret = chain_node(h, &last);
    if (!ret)
      ret = no_raw(h, h->first);
    if (!ret && h->last != h->first)
      ret = no_raw(h, h->last);
  }
  char *raw=NULL;size_t bytes=0;
  if (!ret && h->original > h->base)
    ret = read_at(store, h, h->original, &raw, &bytes);
  release(raw, bytes);
  raw = NULL;
  bytes = 0;
  if(!ret&&h->covered>h->base){ret=read_at(store,h,h->covered,&raw,&bytes);if(ret==-EAGAIN)ret=0;}
  release(raw,bytes);return ret;
}
static int activate(const struct buffer_source_visibility_store_s *store,struct head_s *h)
{
  if (h->exists)
    return 0;
  uint64_t base = 0;
  int ret = store->base(h->source, h->peer, true, &base);
  if (ret)
    return ret;
  if (base != h->base)
    return -EIO;
  char path[320];ret=directory(path,h->source);if(!ret)ret=buffer_agent_private_directory(path);if(!ret)ret=buffer_agent_sync_parent(path);
  if (!ret)
    ret = save_head(h);
  if (!ret)
    h->exists = true;
  return ret;
}
static void progress(const struct head_s *h,struct buffer_source_visibility_progress_s *out)
{out->covered_next=h->covered+1;out->original_next=h->original+1;}
static int immutable(const char *path,const char *raw,size_t bytes,size_t limit)
{char *old=NULL;size_t length=0;int ret=buffer_agent_read_bytes(path,limit,&old,&length);
  if(!ret){ret=length==bytes&&!memcmp(old,raw,bytes)?buffer_agent_sync_parent(path):-EEXIST;release(old,length);return ret;}
  return ret==-ENOENT?buffer_agent_atomic_bytes(path,raw,bytes,limit):ret;}
static int clock_bounds(const struct buffer_source_visibility_store_s *store,const struct head_s *h,const cJSON *incoming,uint64_t sequence,uint64_t clock)
{
  for(unsigned side=0;side<2;side++){
    if((!side&&sequence==1)||(side&&sequence>=h->covered))continue;
    uint64_t seq=side?sequence+1:sequence-1;char *raw=NULL;size_t bytes=0;int ret=read_at(store,h,seq,&raw,&bytes);
    if (ret == -EAGAIN)
      continue;
    if (ret)
      return ret;
    cJSON *j = NULL;
    ret = buffer_source_envelope_decode(raw, bytes, &j);
    uint64_t adjacent = 0;
    if(!ret&&!number(cJSON_GetObjectItemCaseSensitive(j,"event"),"logical_clock",&adjacent))ret=-EIO;
    if(!ret&&(!strcmp(text(side?j:incoming,"provenance"),"native"))&&(side?adjacent<=clock:clock<=adjacent))ret=-EEXIST;
    cJSON_Delete(j);release(raw,bytes);if(ret)return ret;
  }
  return 0;
}
static int cleanup(void)
{const char *paths[]={STAGING,STAGING ".tmp",WAL ".tmp"};for(unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++)if(unlink(paths[i])&&errno!=ENOENT)return -errno;return buffer_agent_sync_parent(STAGING);}

/* WAL snapshots compare typed exact counters, not approximate JSON doubles.
 * Filled slots retain their old links as tombstones for bounded iteration and
 * idempotent recovery; they are never counted in the active held chain. */
int buffer_source_visibility_recover(const struct buffer_source_visibility_store_s *store)
{
  int ret=roots();if(ret)return ret;cJSON *wal=NULL,*envelope=NULL;char *raw=NULL;size_t bytes=0;
  ret=read_json(WAL,WAL_BYTES,&wal);if(ret==-ENOENT)return cleanup();if(ret)return ret;
  struct head_s before,after,current;uint64_t version=0,sequence=0,previous=0,next=0,clock=0;
  const char *kind=text(wal,"kind"),*token=text(wal,"slot_token"),*source=text(wal,"source_id"),*peer=text(wal,"owner_peer");
  const cJSON *proof=cJSON_GetObjectItemCaseSensitive(wal,"record");bool reservation=!strcmp(kind,"reserve"),append=!strcmp(kind,"append"),fill=!strcmp(kind,"fill");
  if(!cJSON_IsObject(wal)||cJSON_GetArraySize(wal)!=11||!number(wal,"visibility_version",&version)||version!=1||
     validate_source(store,source,peer)||(!reservation&&!append&&!fill)||!number(wal,"source_sequence",&sequence)||!sequence||sequence>=MAX||
     !number(wal,"previous",&previous)||!number(wal,"next",&next)||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(wal,"slot_token"))||
     (append?token[0]:!hex(token,32))){ret=-EIO;goto done;}
  ret=parse_head(cJSON_GetObjectItemCaseSensitive(wal,"before"),&before);if(!ret)ret=parse_head(cJSON_GetObjectItemCaseSensitive(wal,"after"),&after);
  if (ret)
    goto done;
  if (strcmp(source, before.source) || strcmp(peer, before.peer) ||
      strcmp(source, after.source) || strcmp(peer, after.peer) ||
      before.base != after.base) {
    ret = -EIO;
    goto done;
  }
  struct head_s expected=before;
  if(reservation){
    if(!cJSON_IsNull(proof)||sequence!=before.covered+1||previous!=before.last||next){ret=-EIO;goto done;}
    expected.covered=sequence;if(!expected.first)expected.first=sequence;expected.last=sequence;
  }else{
    if(!cJSON_IsObject(proof)||cJSON_GetArraySize(proof)!=3||!identity(text(proof,"event_id"),160,true)||!hex(text(proof,"fingerprint"),64)||!number(proof,"logical_clock",&clock)||before.count>=MAX-1){ret=-EIO;goto done;}
    expected.count++;if(clock>expected.clock)expected.clock=clock;
    if(append){if(sequence!=before.covered+1||previous||next){ret=-EIO;goto done;}expected.covered=sequence;}
    else{
      if(sequence<=before.base||sequence>before.covered||(previous&&(previous<=before.base||previous>=sequence))||
         (next&&(next<=sequence||next>before.covered))||((previous==0)!=(before.first==sequence))||((next==0)!=(before.last==sequence))){ret=-EIO;goto done;}
      if (before.first == sequence)
        expected.first = next;
      if (before.last == sequence)
        expected.last = previous;
    }
  }
  expected.original=expected.first?expected.first-1:expected.covered;
  if(!valid_head(&expected)||!same_head(&expected,&after)){ret=-EIO;goto done;}
  ret=load_head(store,source,peer,false,&current);if(ret)goto done;
  if(!current.exists||(!same_head(&current,&before)&&!same_head(&current,&after))){ret=-EIO;goto done;}
  struct slot_s target={.sequence=sequence,.previous=previous,.next=next};
  snprintf(target.token,sizeof(target.token),"%s",token);struct slot_s saved;int marker=slot_read(&before,sequence,&saved);
  if(reservation){
    if(marker!=-ENOENT&&(marker||saved.filled||saved.previous!=previous||saved.next||strcmp(saved.token,token))){ret=-EIO;goto done;}
    ret=no_raw(&before,sequence);if(ret)goto done;
  }else if(fill){
    if(marker||saved.previous!=previous||saved.next!=next||strcmp(saved.token,token)||
       (saved.filled&&(strcmp(saved.event,text(proof,"event_id"))||strcmp(saved.hash,text(proof,"fingerprint"))))){ret=-EIO;goto done;}
    target.filled=true;snprintf(target.event,sizeof(target.event),"%s",text(proof,"event_id"));snprintf(target.hash,sizeof(target.hash),"%s",text(proof,"fingerprint"));
  }else if(marker!=-ENOENT){ret=-EIO;goto done;}
  if(!reservation){
    ret=buffer_agent_read_bytes(STAGING,BUFFER_SOURCE_EVENT_BYTES,&raw,&bytes);
    if(ret==-ENOENT){char path[320];ret=position(path,source,sequence,"json");if(!ret)ret=buffer_agent_read_bytes(path,BUFFER_SOURCE_EVENT_BYTES,&raw,&bytes);}
    if (ret)
      goto done;
    ret = buffer_source_envelope_decode(raw, bytes, &envelope);
    uint64_t seq = 0, saved_clock = 0;
    char hash[65], reserved[112];
    if (!ret)
      ret = digest(raw, bytes, hash);
    const cJSON *event = cJSON_GetObjectItemCaseSensitive(envelope, "event");
    snprintf(reserved,sizeof(reserved),"evt_%s_",store->local_source);
    if(!ret&&(strcmp(text(envelope,"source_id"),source)||!number(envelope,"source_sequence",&seq)||seq!=sequence||
       !number(event,"logical_clock",&saved_clock)||saved_clock!=clock||strcmp(text(event,"event_id"),text(proof,"event_id"))||
       strcmp(hash,text(proof,"fingerprint"))||!strncmp(text(event,"event_id"),reserved,strlen(reserved))))ret=-EIO;
    if (!ret)
      ret = clock_bounds(store, &before, envelope, sequence, clock);
    if (ret)
      goto done;
    char path[320];ret=position(path,source,sequence,"json");if(!ret)ret=immutable(path,raw,bytes,BUFFER_SOURCE_EVENT_BYTES);
    if (!ret)
      ret = position(path, source, sequence, "sha256");
    if (!ret)
      ret = immutable(path, hash, 64, 64);
    if (!ret)
      ret = store->put(source, sequence, text(event, "event_id"), hash);
    if (ret)
      goto done;
    ret=position(path,source,sequence,"view.json");cJSON *queue=NULL;
    if(!ret){int read=read_json(path,4096,&queue);if(read==-ENOENT){queue=cJSON_CreateObject();bool built=queue&&cJSON_AddStringToObject(queue,"event_id",text(event,"event_id"))&&cJSON_AddStringToObject(queue,"state","pending");ret=built?write_json(path,queue,4096):-ENOMEM;}
      else if(read)ret=read;else if(cJSON_GetArraySize(queue)!=2||strcmp(text(queue,"event_id"),text(event,"event_id"))||!identity(text(queue,"state"),32,false))ret=-EIO;}
    cJSON_Delete(queue);if(!ret)ret=store->observe(clock);if(ret)goto done;
  }
  if(previous){
    struct slot_s neighbor;ret=slot_read(&before,previous,&neighbor);if(ret)goto done;
    uint64_t end=reservation?sequence:next;
    if(neighbor.filled||(neighbor.next!=(reservation?0:sequence)&&neighbor.next!=end)){ret=-EIO;goto done;}
    neighbor.next=end;ret=slot_write(&before,&neighbor);if(ret)goto done;
  }
  if(fill&&next){
    struct slot_s neighbor;ret=slot_read(&before,next,&neighbor);if(ret)goto done;
    if(neighbor.filled||(neighbor.previous!=sequence&&neighbor.previous!=previous)){ret=-EIO;goto done;}
    neighbor.previous=previous;ret=slot_write(&before,&neighbor);if(ret)goto done;
  }
  if(reservation||fill){ret=slot_write(&before,&target);if(ret)goto done;}
  ret=save_head(&after);if(ret)goto done;
  if(unlink(WAL)&&errno!=ENOENT){ret=-errno;goto done;}ret=buffer_agent_sync_parent(WAL);if(!ret)ret=cleanup();
done:
  cJSON_Delete(wal);cJSON_Delete(envelope);release(raw,bytes);return ret;
}

static int commit(const struct buffer_source_visibility_store_s *store,struct head_s *before,const struct head_s *after,const char *kind,
                  uint64_t sequence,const char *token,uint64_t previous,uint64_t next,const char *raw,size_t bytes,const cJSON *event,uint64_t clock,
                  bool (*authorize)(void *),void *context)
{
  if(!authorize(context))return -EACCES;
  if(!valid_head(before)||!valid_head(after)||strcmp(before->source,after->source)||strcmp(before->peer,after->peer)||before->base!=after->base)return -EIO;
  int ret=activate(store,before);if(ret)return ret;
  if(raw){ret=buffer_agent_atomic_bytes(STAGING,raw,bytes,BUFFER_SOURCE_EVENT_BYTES);if(ret)return ret;}
  cJSON *j=cJSON_CreateObject(),*old=head_json(before),*fresh=head_json(after),*record=raw?cJSON_CreateObject():cJSON_CreateNull();char hash[65];
  if(raw){ret=digest(raw,bytes,hash);if(ret)goto done;}
  bool built=j&&old&&fresh&&record&&(!raw||(cJSON_AddStringToObject(record,"event_id",text(event,"event_id"))&&
    cJSON_AddStringToObject(record,"fingerprint",hash)&&cJSON_AddNumberToObject(record,"logical_clock",(double)clock)))&&
    cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"source_id",before->source)&&cJSON_AddStringToObject(j,"owner_peer",before->peer)&&
    cJSON_AddStringToObject(j,"kind",kind)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"slot_token",token)&&
    cJSON_AddNumberToObject(j,"previous",(double)previous)&&cJSON_AddNumberToObject(j,"next",(double)next);
  if(!built){ret=-ENOMEM;goto done;}
  if(!cJSON_AddItemToObject(j,"before",old)){ret=-ENOMEM;goto done;}old=NULL;
  if(!cJSON_AddItemToObject(j,"after",fresh)){ret=-ENOMEM;goto done;}fresh=NULL;
  if(!cJSON_AddItemToObject(j,"record",record)){ret=-ENOMEM;goto done;}record=NULL;
  if(!authorize(context)){ret=-EACCES;goto done;}
  ret=write_json(WAL,j,WAL_BYTES);if(!ret)ret=buffer_source_visibility_recover(store);
done:
  cJSON_Delete(j);cJSON_Delete(old);cJSON_Delete(fresh);cJSON_Delete(record);return ret;
}
int buffer_source_visibility_active(const char *source,bool *active)
{if(!active||!identity(source,96,false))return -EINVAL;*active=false;char path[320];int ret=meta_path(path,source);if(ret)return ret;struct stat st;
  if (lstat(path, &st))
    return errno == ENOENT ? 0 : -errno;
  if (!S_ISREG(st.st_mode) || (st.st_mode & 0077))
    return -EACCES;
  *active = true;
  return 0;
}
int buffer_source_visibility_progress(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,struct buffer_source_visibility_progress_s *out)
{struct head_s h;int ret=load_head(store,source,peer,true,&h);if(!ret&&h.exists)ret=save_head(&h);if(!ret)progress(&h,out);return ret;}
int buffer_source_visibility_reserve(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,uint64_t sequence,const char *token,
                                     bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{
  if (!sequence || sequence >= MAX || !hex(token, 32))
    return -EINVAL;
  struct head_s h;
  int ret = load_head(store, source, peer, true, &h);
  if (ret)
    return ret;
  progress(&h, &out->progress);
  struct slot_s slot;int marker=h.exists&&sequence>h.base?slot_read(&h,sequence,&slot):-ENOENT;
  if(marker&&marker!=-ENOENT)return marker;
  if(!marker&&sequence>h.covered)return -EIO;
  if(!marker){
    if(strcmp(slot.token,token)){out->state=BUFFER_VISIBILITY_CONFLICT;return 0;}
    if(!slot.filled){ret=chain_node(&h,&slot);if(ret)return ret;}
    char *raw=NULL;size_t bytes=0;ret=read_at(store,&h,sequence,&raw,&bytes);release(raw,bytes);
    if(slot.filled){if(ret)return ret;out->state=BUFFER_VISIBILITY_ORIGINAL_PRESENT;}
    else{if(ret!=-EAGAIN)return ret?ret:-EIO;out->state=BUFFER_VISIBILITY_HELD_DUPLICATE;}
    return save_head(&h);
  }
  if(sequence<=h.covered){char *raw=NULL;size_t bytes=0;ret=read_at(store,&h,sequence,&raw,&bytes);release(raw,bytes);
    if (ret)
      return ret;
    out->state = BUFFER_VISIBILITY_ORIGINAL_PRESENT;
    return h.exists ? save_head(&h) : 0;
  }
  if(sequence!=h.covered+1){out->state=BUFFER_VISIBILITY_GAP;return 0;}
  ret=no_raw(&h,sequence);if(ret)return ret;
  struct head_s after=h;after.covered=sequence;if(!after.first)after.first=sequence;after.last=sequence;after.original=after.first-1;after.exists=true;
  ret=commit(store,&h,&after,"reserve",sequence,token,h.last,0,NULL,0,NULL,0,authorize,context);
  if(!ret){out->state=BUFFER_VISIBILITY_HELD;progress(&after,&out->progress);}return ret;
}
int buffer_source_visibility_receive(const struct buffer_source_visibility_store_s *store,const char *peer,const char *raw,size_t length,const char *token,
                                     bool (*authorize)(void *),void *context,struct buffer_source_visibility_import_s *out)
{
  if (!token || (*token && !hex(token, 32)))
    return -EINVAL;
  cJSON *j = NULL, *found = NULL;
  int ret = buffer_source_envelope_decode(raw, length, &j);
  if (ret)
    return ret;
  const char *source=text(j,"source_id");const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event");uint64_t sequence=0,clock=0;
  char reserved[112],hash[65];snprintf(reserved,sizeof(reserved),"evt_%s_",store->local_source);
  if(validate_source(store,source,peer)||!strncmp(text(event,"event_id"),reserved,strlen(reserved))||!number(j,"source_sequence",&sequence)||!number(event,"logical_clock",&clock)){ret=-EACCES;goto done;}
  struct head_s h;ret=load_head(store,source,peer,true,&h);if(ret)goto done;progress(&h,&out->progress);ret=digest(raw,length,hash);if(ret)goto done;
  struct slot_s slot;int marker=h.exists&&sequence>h.base?slot_read(&h,sequence,&slot):-ENOENT;if(marker&&marker!=-ENOENT){ret=marker;goto done;}
  if(!marker&&sequence>h.covered){ret=-EIO;goto done;}
  if(!marker&&!slot.filled){ret=chain_node(&h,&slot);if(!ret)ret=no_raw(&h,sequence);if(ret)goto done;}
  ret=store->find(text(event,"event_id"),&found);if(ret)goto done;
  if(found){uint64_t saved_sequence=0;
    bool same=number(found,"source_sequence",&saved_sequence)&&saved_sequence==sequence&&!strcmp(text(found,"source_id"),source)&&!strcmp(text(found,"fingerprint"),hash)&&
      sequence<=h.covered&&(!*token||(!marker&&!strcmp(slot.token,token)));
    if(same){char *saved=NULL;size_t bytes=0;ret=read_at(store,&h,sequence,&saved,&bytes);if(!ret)same=bytes==length&&!memcmp(saved,raw,length);release(saved,bytes);}
    if (ret)
      goto done;
    out->state =
        same ? BUFFER_VISIBILITY_DUPLICATE : BUFFER_VISIBILITY_CONFLICT;
    if (same && h.exists)
      ret = save_head(&h);
    goto done;
  }
  if(sequence<=h.covered&&(marker||slot.filled)){
    char *saved=NULL;size_t bytes=0;cJSON *existing=NULL;ret=read_at(store,&h,sequence,&saved,&bytes);
    if(!ret)ret=buffer_source_envelope_decode(saved,bytes,&existing);
    // A different ID reusing an occupied position is a protocol conflict.
    // The same ID absent from the common index is damaged metadata instead.
    if(!ret){if(!strcmp(text(cJSON_GetObjectItemCaseSensitive(existing,"event"),"event_id"),text(event,"event_id")))ret=-EIO;
      else out->state=BUFFER_VISIBILITY_CONFLICT;}
    cJSON_Delete(existing);release(saved,bytes);goto done;
  }
  if(!marker&&strcmp(slot.token,token)){out->state=BUFFER_VISIBILITY_CONFLICT;goto done;}
  if(sequence>h.covered+1){out->state=BUFFER_VISIBILITY_GAP;goto done;}
  if(marker&&*token){out->state=BUFFER_VISIBILITY_CONFLICT;goto done;}
  if(marker){ret=no_raw(&h,sequence);if(ret)goto done;}
  ret=clock_bounds(store,&h,j,sequence,clock);if(ret==-EEXIST){out->state=BUFFER_VISIBILITY_CONFLICT;ret=0;goto done;}if(ret)goto done;
  struct head_s after=h;after.exists=true;after.count++;if(sequence>after.covered)after.covered=sequence;if(clock>after.clock)after.clock=clock;
  if(!marker){if(h.first==sequence)after.first=slot.next;if(h.last==sequence)after.last=slot.previous;}
  after.original=after.first?after.first-1:after.covered;
  ret=commit(store,&h,&after,marker?"append":"fill",sequence,token,marker?0:slot.previous,marker?0:slot.next,raw,length,event,clock,authorize,context);
  if(!ret){out->state=BUFFER_VISIBILITY_STORED;progress(&after,&out->progress);}
done:
  cJSON_Delete(j);cJSON_Delete(found);return ret;
}
int buffer_source_visibility_read(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,uint64_t sequence,char **raw,size_t *bytes)
{*raw=NULL;*bytes=0;struct head_s h;int ret=load_head(store,source,peer,true,&h);return ret?ret:read_at(store,&h,sequence,raw,bytes);}
int buffer_source_visibility_projection_range(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                               uint64_t *base,uint64_t *covered,uint64_t *records,char parent[320])
{
  struct head_s h;int ret=load_head(store,source,peer,true,&h);if(ret)return ret;
  *base=h.base;*covered=h.covered;if(records)*records=h.count;return directory(parent,source);
}

/* cursor=0 starts a pass. Returned cursor is the next link, not seq+1.
 * Filled tombstones are skipped in bounded batches; EAGAIN retains cursor. */
int buffer_source_visibility_next(const struct buffer_source_visibility_store_s *store,const char *peer,const char *source,
                                  uint64_t *cursor,uint64_t *sequence,char token[33])
{
  *sequence=0;token[0]=0;struct head_s h;int ret=load_head(store,source,peer,true,&h);if(ret)return ret;uint64_t current=*cursor?*cursor:h.first;
  for(unsigned work=0;current&&work<16;work++){
    if (current <= h.base || current > h.covered)
      return -EINVAL;
    struct slot_s slot;
    ret = slot_read(&h, current, &slot);
    if (ret)
      return ret;
    *cursor=slot.next;
    if(!slot.filled){ret=chain_node(&h,&slot);if(!ret)ret=no_raw(&h,current);if(ret)return ret;*sequence=current;snprintf(token,33,"%s",slot.token);return 0;}
    current=slot.next;
  }
  *cursor=current;return current?-EAGAIN:0;
}

static int next_source(const char *after,char best[65])
{
  best[0]=0;DIR *dir=opendir(OWNERS);if(!dir)return -errno;int ret=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, ".."))
      continue;
    size_t n = strlen(entry->d_name);
    if (n > 4 && !strcmp(entry->d_name + n - 4, ".tmp"))
      continue;
    if (n != 69 || strcmp(entry->d_name + 64, ".json")) {
      ret = -EIO;
      break;
    }
    char hash[65];memcpy(hash,entry->d_name,64);hash[64]=0;if(!hex(hash,64)){ret=-EIO;break;}
    if(strcmp(hash,after)>0&&(!best[0]||strcmp(hash,best)<0))snprintf(best,65,"%s",hash);
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  return ret;
}
static int from_hash(const struct buffer_source_visibility_store_s *store,const char *hash,struct head_s *h)
{
  char path[320],actual[65];int n=snprintf(path,sizeof(path),OWNERS "/%s.json",hash);if(n<0||(size_t)n>=sizeof(path))return -ENAMETOOLONG;
  cJSON *j=NULL;int ret=read_json(path,4096,&j);if(!ret)ret=parse_head(j,h);cJSON_Delete(j);if(ret)return ret;
  ret=digest(h->source,strlen(h->source),actual);if(!ret&&strcmp(actual,hash))ret=-EIO;
  if (ret)
    return ret;
  char source[97], peer[65];
  snprintf(source, sizeof(source), "%s", h->source);
  snprintf(peer, sizeof(peer), "%s", h->peer);
  return load_head(store,source,peer,true,h);
}
int buffer_source_visibility_total(const struct buffer_source_visibility_store_s *store,uint64_t observed,uint64_t *count)
{
  *count=0;DIR *dir=opendir(DATA);if(!dir)return -errno;int ret=0;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, ".."))
      continue;
    if (!hex(entry->d_name, 64)) {
      ret = -EIO;
      break;
    }
    char path[320];int n=snprintf(path,sizeof(path),OWNERS "/%s.json",entry->d_name);if(n<0||(size_t)n>=sizeof(path)){ret=-ENAMETOOLONG;break;}
    int check=absent(path);if(!check){n=snprintf(path,sizeof(path),DATA "/%s",entry->d_name);ret=n<0||(size_t)n>=sizeof(path)?-ENAMETOOLONG:empty(path);
    } else if (check != -EIO)
      ret = check;
    if (ret)
      break;
  }
  if (closedir(dir) && !ret)
    ret = -errno;
  if (ret)
    return ret;
  dir=opendir(OWNERS);if(!dir)return -errno;
  for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, ".."))
      continue;
    size_t bytes = strlen(entry->d_name);
    if (bytes > 4 && !strcmp(entry->d_name + bytes - 4, ".tmp"))
      continue;
    if (bytes != 69 || strcmp(entry->d_name + 64, ".json")) {
      ret = -EIO;
      break;
    }
    char hash[65];memcpy(hash,entry->d_name,64);hash[64]=0;if(!hex(hash,64)){ret=-EIO;break;}
    struct head_s h;ret=from_hash(store,hash,&h);if(ret)break;
    if(h.clock>observed||h.count>MAX-*count){ret=-EIO;break;}*count+=h.count;}
  if(closedir(dir)&&!ret)ret=-errno;
  return ret;
}
int buffer_source_visibility_build_clear(void)
{const char *paths[]={BUILD,BUILD ".tmp"};for(unsigned i=0;i<sizeof(paths)/sizeof(paths[0]);i++)if(unlink(paths[i])&&errno!=ENOENT)return -errno;return buffer_agent_sync_parent(BUILD);}
static int save_build(const char *hash,uint64_t sequence,uint64_t expected)
{cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"source_hash",hash)&&
  cJSON_AddNumberToObject(j,"sequence",(double)sequence)&&cJSON_AddNumberToObject(j,"expected_count",(double)expected);
  int ret=built?write_json(BUILD,j,4096):-ENOMEM;cJSON_Delete(j);return ret;}
int buffer_source_visibility_build(const struct buffer_source_visibility_store_s *store,uint64_t expected,bool *done)
{
  *done=false;cJSON *j=NULL;int ret=read_json(BUILD,4096,&j);char current[65]="";uint64_t sequence=0,version=0,target=0;
  if(!ret){if(cJSON_GetArraySize(j)!=4||!number(j,"visibility_version",&version)||version!=1||!number(j,"expected_count",&target)||target!=expected||
      !number(j,"sequence",&sequence)||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(j,"source_hash"))||
      (text(j,"source_hash")[0]?!hex(text(j,"source_hash"),64):sequence!=0))ret=-EIO;
    if(!ret)snprintf(current,sizeof(current),"%s",text(j,"source_hash"));
  } else if (ret == -ENOENT)
    ret = save_build("", 0, expected);
  cJSON_Delete(j);
  if (ret)
    return ret;
  struct head_s h;bool have=false;size_t bytes_read=0;
  for(unsigned work=0;work<16&&bytes_read<2*1024*1024;work++){
    if(!have&&current[0]){ret=from_hash(store,current,&h);if(ret)return ret;if(sequence<h.base||sequence>h.covered)return -EIO;have=true;}
    if(!have||sequence==h.covered){char next[65];ret=next_source(current,next);if(ret)return ret;if(!next[0]){*done=true;return 0;}
      snprintf(current,sizeof(current),"%s",next);ret=from_hash(store,current,&h);if(ret)return ret;sequence=h.base;have=true;
      ret=save_build(current,sequence,expected);if(ret)return ret;if(sequence==h.covered)continue;}
    char *raw=NULL,hash[65];size_t bytes=0;ret=read_at(store,&h,sequence+1,&raw,&bytes);
    if(ret==-EAGAIN)ret=0;else if(!ret){cJSON *envelope=NULL;ret=buffer_source_envelope_decode(raw,bytes,&envelope);
      if (!ret)
        ret = digest(raw, bytes, hash);
      if (!ret)
        ret =
            store->put(h.source, sequence + 1,
                       text(cJSON_GetObjectItemCaseSensitive(envelope, "event"),
                            "event_id"),
                       hash);
      cJSON_Delete(envelope);
    }
    release(raw,bytes);if(ret)return ret;bytes_read+=bytes;sequence++;ret=save_build(current,sequence,expected);if(ret)return ret;
  }
  return 0;
}
#endif
