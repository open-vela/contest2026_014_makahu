/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_wire.h"
#include "buffer_source_events.h"
#include "buffer_source_envelope.h"
#include "buffer_source_remote.h"
#include "buffer_source_remote_blob.h"
#include "buffer_source_privacy.h"
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <unistd.h>

/* One application worker owns every context; transport callbacks never touch
 * these slots. Retained envelopes share one bounded memory budget. */
#define WIRE_SESSIONS 4
struct wire_session_s {
  char peer[65];
  unsigned generation;
  char *cached;
  bool cached_allowed;
  size_t cached_bytes;
  unsigned cached_generation;
  uint64_t cached_sequence;
  char cached_peer[65],cached_source[97],cached_hash[65];
  bool cached_visible_offered;
  char cached_visible_token[33];
  char *incoming;
  size_t incoming_bytes,incoming_used;
  unsigned incoming_generation;
  uint64_t incoming_sequence;
  char incoming_peer[65],incoming_source[97],incoming_hash[65];
  bool incoming_visible;
  char incoming_token[33];
  uint64_t cached_touch,incoming_touch;
  int blob_fd;
  unsigned blob_generation;
  uint64_t blob_sequence,blob_bytes,blob_touch;
  char blob_peer[65],blob_source[97],blob_record_hash[65],blob_hash[65];
};
static struct wire_session_s sessions[WIRE_SESSIONS]={
  {.blob_fd=-1},{.blob_fd=-1},{.blob_fd=-1},{.blob_fd=-1}
};
static struct wire_session_s *wire=&sessions[0];
static uint64_t now_ms(void)
{struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now))return 0;return (uint64_t)now.tv_sec*1000+now.tv_nsec/1000000;}
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j, const char *key, uint64_t min, uint64_t max,
                   uint64_t *out) {
  const cJSON *v = cJSON_GetObjectItemCaseSensitive(j, key);
  if (!cJSON_IsNumber(v) || !isfinite(v->valuedouble) || v->valuedouble < min ||
      v->valuedouble > max || floor(v->valuedouble) != v->valuedouble)
    return false;
  *out = (uint64_t)v->valuedouble;
  return true;
}
static bool hex(const char *value,size_t n)
{if(strlen(value)!=n)return false;for(size_t i=0;i<n;i++)if(!strchr("0123456789abcdef",value[i]))return false;return true;}
static bool authorized(const struct buffer_oobe_authorization_s *auth)
{return auth&&auth->authorize&&!auth->authorize(auth->context,BUFFER_OOBE_GET_STATE);}
static void reset_outgoing(void)
{if(wire->cached){volatile char *p=wire->cached;size_t n=wire->cached_bytes;while(n--)*p++=0;free(wire->cached);}
  wire->cached=NULL;wire->cached_allowed=false;wire->cached_visible_offered=false;wire->cached_visible_token[0]=0;
  wire->cached_bytes=0;wire->cached_sequence=0;wire->cached_generation=0;wire->cached_touch=0;wire->cached_peer[0]=wire->cached_source[0]=wire->cached_hash[0]=0;}
static void reset_incoming(void)
{if(wire->incoming){volatile char *p=wire->incoming;size_t n=wire->incoming_bytes+1;while(n--)*p++=0;free(wire->incoming);}
  wire->incoming=NULL;wire->incoming_bytes=wire->incoming_used=0;wire->incoming_sequence=0;wire->incoming_generation=0;wire->incoming_touch=0;
  wire->incoming_visible=false;wire->incoming_token[0]=0;wire->incoming_peer[0]=wire->incoming_source[0]=wire->incoming_hash[0]=0;}
static void reset_blob(void)
{if(wire->blob_fd>=0)close(wire->blob_fd);wire->blob_fd=-1;wire->blob_generation=0;wire->blob_sequence=wire->blob_bytes=wire->blob_touch=0;
  wire->blob_peer[0]=wire->blob_source[0]=wire->blob_record_hash[0]=wire->blob_hash[0]=0;}
static void reset_session(void)
{reset_outgoing();reset_incoming();reset_blob();wire->peer[0]=0;wire->generation=0;}
void buffer_source_wire_reset(void)
{
  struct wire_session_s *saved=wire;
  for(unsigned i=0;i<WIRE_SESSIONS;i++){wire=&sessions[i];reset_session();}
  wire=saved;buffer_source_remote_blob_reset_all();
}
void buffer_source_wire_close_session(const char *peer,unsigned generation)
{
  if (!peer)
    return;
  struct wire_session_s *saved = wire;
  for(unsigned i=0;i<WIRE_SESSIONS;i++)if(sessions[i].generation==generation&&!strcmp(sessions[i].peer,peer)){
    wire=&sessions[i];reset_session();
  }
  wire=saved;buffer_source_remote_blob_close_session(peer,generation);
}
static bool maintain_sessions(void)
{
  struct wire_session_s *saved=wire;uint64_t now=now_ms();bool retained=false;
  for(unsigned i=0;i<WIRE_SESSIONS;i++){
    wire=&sessions[i];
    if(wire->cached&&(!now||!wire->cached_touch||now<wire->cached_touch||now-wire->cached_touch>=30000))reset_outgoing();
    if(wire->incoming&&(!now||!wire->incoming_touch||now<wire->incoming_touch||now-wire->incoming_touch>=30000))reset_incoming();
    if(wire->blob_fd>=0&&(!now||!wire->blob_touch||now<wire->blob_touch||now-wire->blob_touch>=30000))reset_blob();
    retained=retained||wire->cached||wire->incoming||wire->blob_fd>=0;
  }
  wire=saved;return retained;
}
bool buffer_source_wire_session_idle(const char *peer,unsigned generation,bool active)
{
  if(!active)buffer_source_wire_close_session(peer,generation);
  bool retained=maintain_sessions();
  return buffer_source_remote_blob_session_idle(peer,generation,active)||retained;
}
/* Compatibility for the current single-link transport. Multi-link callers
 * must use the peer-scoped lifecycle above. */
bool buffer_source_wire_idle(unsigned generation,bool active)
{
  if(!active){buffer_source_wire_reset();return false;}
  struct wire_session_s *saved=wire;
  for(unsigned i=0;i<WIRE_SESSIONS;i++)if(sessions[i].peer[0]&&sessions[i].generation!=generation){wire=&sessions[i];reset_session();}
  wire=saved;bool retained=maintain_sessions();
  return buffer_source_remote_blob_idle(generation,true)||retained;
}
static bool select_session(const char *peer,unsigned generation)
{
  struct wire_session_s *empty=NULL;
  for(unsigned i=0;i<WIRE_SESSIONS;i++){
    struct wire_session_s *slot=&sessions[i];
    if(slot->generation==generation&&!strcmp(slot->peer,peer)){wire=slot;return true;}
    if(!empty&&!slot->cached&&!slot->incoming&&slot->blob_fd<0)empty=slot;
  }
  if (!empty)
    return false;
  wire = empty;
  reset_session();
  snprintf(wire->peer,sizeof(wire->peer),"%s",peer);wire->generation=generation;return true;
}
static bool envelope_room(size_t bytes,size_t replace)
{
  size_t used=0;
  for(unsigned i=0;i<WIRE_SESSIONS;i++){
    if(sessions[i].cached)used+=sessions[i].cached_bytes+1;
    if(sessions[i].incoming)used+=sessions[i].incoming_bytes+1;
  }
  const size_t limit=2*((size_t)BUFFER_SOURCE_EVENT_BYTES+1);
  return used>=replace&&used-replace<=limit&&bytes<=limit-(used-replace);
}
/* A copied card does not grant forwarding permission. Legacy ranges stop at
 * sensitive originals; visibility-v1 returns an opaque durable reservation. */
static bool may_share(const char *raw,size_t bytes,const char *peer)
{
  cJSON *j=NULL;if(buffer_source_envelope_decode(raw,bytes,&j))return false;
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  uint64_t schema=0;bool allowed=false;
  if(!number(event,"schema_version",1,1,&schema)||!strncmp(text(event,"type"),"CaptureContext",14)||buffer_source_private_context(payload))goto done;
  const char *kind=text(payload,"projection_kind");
  const cJSON *projection=cJSON_GetObjectItemCaseSensitive(payload,"projection_text"),*record=cJSON_GetObjectItemCaseSensitive(payload,"record_json");
  if(kind[0]){
    /* Device acceptance is local execution bookkeeping. Its exact result is
     * retained for recovery; history sharing never distributes this authority. */
    if(!strcmp(kind,"input_result"))goto done;
    const char *kinds[]={"replica","calendar","focus","assistant","focus_runtime","followup","conversation","research","mode","pending_action","pending_mode","focus_config"};
    bool known=false;for(unsigned i=0;i<sizeof(kinds)/sizeof(kinds[0]);i++)if(!strcmp(kind,kinds[i]))known=true;
    if(!known)goto done;
    if(cJSON_IsNull(projection)){allowed=true;goto done;}
    if(!cJSON_IsString(projection))goto done;
    bool structured=strcmp(kind,"mode")&&strcmp(kind,"pending_action")&&strcmp(kind,"pending_mode")&&strcmp(kind,"focus_config");
    if(!structured){allowed=true;goto done;}
    cJSON *value=buffer_agent_parse_json(projection->valuestring);
    allowed=cJSON_IsObject(value)&&!buffer_source_private_context(value)&&
      ((strcmp(kind,"replica")&&strcmp(kind,"calendar"))||buffer_source_replica_may_share(value,peer));cJSON_Delete(value);
  }else if(cJSON_IsString(record)){
    cJSON *value=buffer_agent_parse_json(record->valuestring);allowed=cJSON_IsObject(value)&&!buffer_source_private_context(value);cJSON_Delete(value);
  }else allowed=cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(payload,"blob"))&&
    cJSON_IsString(cJSON_GetObjectItemCaseSensitive(payload,"capture_metadata"));
done:
  cJSON_Delete(j);return allowed;
}
static cJSON *base(const char *type,const char *exchange)
{
  cJSON *j = cJSON_CreateObject();
  if (j && cJSON_AddStringToObject(j, "type", type) &&
      cJSON_AddNumberToObject(j, "protocol", 1) &&
      cJSON_AddStringToObject(j, "exchange_id", exchange))
    return j;
  cJSON_Delete(j);
  return NULL;
}
static int encode(cJSON *j,size_t budget,char **reply)
{
  if (!j)
    return -ENOMEM;
  *reply = cJSON_PrintUnformatted(j);
  cJSON_Delete(j);
  if (!*reply)
    return -ENOMEM;
  if(strlen(*reply)>budget){free(*reply);*reply=NULL;return -EMSGSIZE;}return 0;
}
static int error_reply(const char *exchange,const char *state,size_t budget,char **reply)
{
  cJSON *j=base("buffer.events.error",exchange);if(!j)return -ENOMEM;
  if(!cJSON_AddStringToObject(j,"state",state)||!cJSON_AddNumberToObject(j,"retry_after_ms",2000)){cJSON_Delete(j);return -ENOMEM;}
  return encode(j,budget,reply);
}
static bool source_id(const char *id) {
  size_t n = strlen(id);
  if (!n || n > 96)
    return false;
  for (size_t i = 0; i < n; i++)
    if (!((id[i] >= 'A' && id[i] <= 'Z') || (id[i] >= 'a' && id[i] <= 'z') ||
          (id[i] >= '0' && id[i] <= '9') || strchr("_.-", id[i])))
      return false;
  return true;
}
static bool remote_authorized(void *context)
{return authorized((const struct buffer_oobe_authorization_s *)context);}
static const char *storage_error(int result)
{return result==-ENOTSUP?"unsupported":result==-EAGAIN?"busy":result==-EACCES?"source_owner":result==-EEXIST?"conflict":"storage_error";}
/* Local private-file permission failures are storage faults, not remote
 * source-owner denials. Live authorization is checked at handler boundaries. */
static const char *local_storage_error(int result)
{return result==-ENOTSUP?"unsupported":result==-EAGAIN?"busy":result==-EEXIST?"conflict":"storage_error";}
static int receive_offer(const struct buffer_oobe_authorization_s *auth,const char *peer,const cJSON *request,size_t budget,char **reply)
{
  const char *source=text(request,"source_id"),*exchange=text(request,"exchange_id");uint64_t last=0,next=0;
  if(!source_id(source)||!number(request,"last_sequence",0,9007199254740990ULL,&last))return -EINVAL;
  int ret=buffer_source_remote_progress(peer,source,remote_authorized,(void *)auth,&next);
  if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
  if(!next||next>last+1)return error_reply(exchange,"history_rollback",budget,reply);
  reset_incoming();cJSON *j=base("buffer.events.ready",exchange);
  bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"next_sequence",(double)next);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int receive_receipt(const cJSON *request,const char *state,uint64_t next,size_t position,size_t total,size_t budget,char **reply)
{
  cJSON *j=base("buffer.events.receipt",text(request,"exchange_id"));uint64_t sequence=0;
  if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)){cJSON_Delete(j);return -EINVAL;}
  bool built=j&&cJSON_AddStringToObject(j,"source_id",text(request,"source_id"))&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"record_sha256",text(request,"record_sha256"))&&
    cJSON_AddNumberToObject(j,"next_offset",(double)position)&&cJSON_AddNumberToObject(j,"total",(double)total)&&
    cJSON_AddStringToObject(j,"state",state)&&cJSON_AddNumberToObject(j,"next_sequence",(double)next);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int receive_proof(const struct buffer_oobe_authorization_s *auth,const char *peer,const cJSON *request,size_t budget,char **reply)
{
  const char *source=text(request,"source_id"),*hash=text(request,"record_sha256"),*exchange=text(request,"exchange_id");
  uint64_t sequence=0,total=0,next=0;
  if(!source_id(source)||!hex(hash,64)||!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total))return -EINVAL;
  int ret=buffer_source_remote_progress(peer,source,remote_authorized,(void *)auth,&next);
  if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
  if(sequence>=next)return receive_receipt(request,"gap",next,0,(size_t)total,budget,reply);
  char *raw=NULL,actual[65];size_t bytes=0;ret=buffer_source_remote_read(source,sequence,&raw,&bytes);
  if(!ret){unsigned char sha[32];ret=mbedtls_sha256((unsigned char *)raw,bytes,sha,0);
    if(!ret){for(unsigned i=0;i<32;i++)snprintf(actual+2*i,3,"%02x",sha[i]);}else ret=-EIO;}
  if(raw){volatile char *p=raw;size_t n=bytes;while(n--)*p++=0;free(raw);}
  if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
  if(bytes!=total||strcmp(actual,hash))return error_reply(exchange,"conflict",budget,reply);
  return receive_receipt(request,"duplicate",next,bytes,bytes,budget,reply);
}
static int visible_received(const cJSON *request,const char *state,const struct buffer_source_visibility_progress_s *progress,
                            size_t position,size_t total,size_t budget,char **reply)
{
  uint64_t sequence=0;if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence))return -EINVAL;
  cJSON *j=base("buffer.events.visible.received",text(request,"exchange_id"));
  bool built=j&&cJSON_AddNumberToObject(j,"visibility_version",1)&&cJSON_AddStringToObject(j,"source_id",text(request,"source_id"))&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"slot_token",text(request,"slot_token"))&&
    cJSON_AddStringToObject(j,"record_sha256",text(request,"record_sha256"))&&cJSON_AddNumberToObject(j,"total",(double)total)&&
    cJSON_AddNumberToObject(j,"next_offset",(double)position)&&cJSON_AddStringToObject(j,"state",state)&&
    cJSON_AddNumberToObject(j,"covered_next",(double)progress->covered_next)&&cJSON_AddNumberToObject(j,"original_next",(double)progress->original_next);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int receive_part(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,const cJSON *request,bool visible,size_t budget,char **reply)
{
  const char *source=text(request,"source_id"),*hash=text(request,"record_sha256"),*exchange=text(request,"exchange_id");
  const cJSON *encoded=cJSON_GetObjectItemCaseSensitive(request,"record_base64");uint64_t sequence=0,offset=0,total=0;
  if(!source_id(source)||!hex(hash,64)||!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"offset",0,BUFFER_SOURCE_EVENT_BYTES,&offset)||!number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total)||
     !cJSON_IsString(encoded)||!encoded->valuestring[0]||strlen(encoded->valuestring)>4096)return -EINVAL;
  const char *token=text(request,"slot_token");struct buffer_source_visibility_progress_s progress={0};
  if(visible){
    if(!hex(token,32))return -EINVAL;
    int check=buffer_source_remote_visibility_progress(peer,source,remote_authorized,(void *)auth,&progress);
    if(check)return error_reply(exchange,storage_error(check),budget,reply);
    if(sequence>=progress.covered_next)return visible_received(request,"gap",&progress,0,(size_t)total,budget,reply);
  }
  unsigned char *data=malloc(3072),*canonical=malloc(4097);if(!data||!canonical){free(data);free(canonical);return -ENOMEM;}
  size_t bytes=0,canonical_bytes=0;int ret=mbedtls_base64_decode(data,3072,&bytes,(unsigned char *)encoded->valuestring,strlen(encoded->valuestring));
  if(!ret&&bytes&&bytes<=3072)ret=mbedtls_base64_encode(canonical,4097,&canonical_bytes,data,bytes);else ret=-EINVAL;
  if(!ret&&(canonical_bytes!=strlen(encoded->valuestring)||memcmp(canonical,encoded->valuestring,canonical_bytes)||offset>total||bytes>total-offset))ret=-EINVAL;
  if(ret)goto done;
  bool matches=wire->incoming&&wire->incoming_generation==generation&&!strcmp(wire->incoming_peer,peer)&&!strcmp(wire->incoming_source,source)&&
    wire->incoming_sequence==sequence&&!strcmp(wire->incoming_hash,hash)&&wire->incoming_bytes==total&&wire->incoming_visible==visible&&(!visible||!strcmp(wire->incoming_token,token));
  if(!matches){
    if(offset){ret=error_reply(exchange,"restart",budget,reply);goto done;}
    if(!visible){uint64_t next=0;ret=buffer_source_remote_progress(peer,source,remote_authorized,(void *)auth,&next);
      if(ret){ret=error_reply(exchange,storage_error(ret),budget,reply);goto done;}
      if(sequence>next){ret=receive_receipt(request,"gap",next,0,(size_t)total,budget,reply);goto done;}}
    if(!envelope_room((size_t)total+1,wire->incoming?wire->incoming_bytes+1:0)){ret=error_reply(exchange,"busy",budget,reply);goto done;}
    reset_incoming();wire->incoming=calloc(1,(size_t)total+1);if(!wire->incoming){ret=-ENOMEM;goto done;}
    wire->incoming_bytes=(size_t)total;wire->incoming_generation=generation;wire->incoming_sequence=sequence;
    wire->incoming_visible=visible;if(visible)snprintf(wire->incoming_token,sizeof(wire->incoming_token),"%s",token);
    snprintf(wire->incoming_peer,sizeof(wire->incoming_peer),"%s",peer);snprintf(wire->incoming_source,sizeof(wire->incoming_source),"%s",source);snprintf(wire->incoming_hash,sizeof(wire->incoming_hash),"%s",hash);
  }
  if(offset>wire->incoming_used||(offset<wire->incoming_used&&(offset+bytes>wire->incoming_used||memcmp(wire->incoming+offset,data,bytes)))){
    reset_incoming();ret=error_reply(exchange,"conflict",budget,reply);goto done;
  }
  wire->incoming_touch=now_ms();
  if(offset==wire->incoming_used){memcpy(wire->incoming+wire->incoming_used,data,bytes);wire->incoming_used+=bytes;}
  if(wire->incoming_used<wire->incoming_bytes){ret=visible?visible_received(request,"partial",&progress,wire->incoming_used,wire->incoming_bytes,budget,reply):
      receive_receipt(request,"partial",sequence,wire->incoming_used,wire->incoming_bytes,budget,reply);goto done;}
  unsigned char sha[32];char actual[65];
  if(mbedtls_sha256((unsigned char *)wire->incoming,wire->incoming_bytes,sha,0)){ret=-EIO;goto done;}
  for(unsigned i=0;i<32;i++)snprintf(actual+i*2,3,"%02x",sha[i]);
  if(strcmp(actual,hash)){reset_incoming();ret=error_reply(exchange,"conflict",budget,reply);goto done;}
  cJSON *envelope=NULL;ret=buffer_source_envelope_decode(wire->incoming,wire->incoming_bytes,&envelope);uint64_t saved_sequence=0;
  if(ret==-ENOMEM){ret=error_reply(exchange,"storage_error",budget,reply);goto done;}
  bool valid=!ret&&!strcmp(text(envelope,"source_id"),source)&&number(envelope,"source_sequence",1,9007199254740990ULL,&saved_sequence)&&saved_sequence==sequence;
  cJSON_Delete(envelope);
  if(!valid){reset_incoming();ret=error_reply(exchange,"invalid",budget,reply);goto done;}
  if(visible){
    struct buffer_source_visibility_import_s imported;
    ret=buffer_source_remote_visibility_receive(peer,wire->incoming,wire->incoming_bytes,token,remote_authorized,(void *)auth,&imported);
    if(ret){ret=error_reply(exchange,storage_error(ret),budget,reply);goto done;}
    const char *state=imported.state==BUFFER_VISIBILITY_STORED?"stored":imported.state==BUFFER_VISIBILITY_DUPLICATE?"duplicate":
      imported.state==BUFFER_VISIBILITY_GAP?"gap":"conflict";
    ret=visible_received(request,state,&imported.progress,wire->incoming_used,wire->incoming_bytes,budget,reply);
    if (imported.state == BUFFER_VISIBILITY_GAP ||
        imported.state == BUFFER_VISIBILITY_CONFLICT)
      reset_incoming();
    goto done;
  }
  struct buffer_source_import_s imported;
  ret=buffer_source_remote_receive(peer,wire->incoming,wire->incoming_bytes,remote_authorized,(void *)auth,&imported);
  if(ret){ret=error_reply(exchange,storage_error(ret),budget,reply);goto done;}
  const char *state=imported.state==BUFFER_SOURCE_STORED?"stored":imported.state==BUFFER_SOURCE_DUPLICATE?"duplicate":
    imported.state==BUFFER_SOURCE_GAP?"gap":"conflict";
  ret=receive_receipt(request,state,imported.next_sequence,wire->incoming_used,wire->incoming_bytes,budget,reply);
  if(imported.state==BUFFER_SOURCE_GAP||imported.state==BUFFER_SOURCE_CONFLICT)reset_incoming();
done:
  memset(data,0,3072);memset(canonical,0,4097);free(data);free(canonical);return ret;
}
static int accept_delivery(const struct buffer_oobe_authorization_s *auth,const char *peer,const cJSON *request,size_t budget,char **reply)
{
  const char *source=text(request,"source_id"),*hash=text(request,"record_sha256"),*exchange=text(request,"exchange_id");
  uint64_t sequence=0,total=0,next=0;
  if(!source_id(source)||!hex(hash,64)||!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total))return -EINVAL;
  int ret=buffer_source_delivery_accept(peer,source,sequence,hash,(size_t)total,remote_authorized,(void *)auth,&next);
  if(ret)return error_reply(exchange,ret==-ERANGE?"receipt_gap":storage_error(ret),budget,reply);
  cJSON *j=base("buffer.events.accepted.reply",exchange);
  bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
    cJSON_AddStringToObject(j,"record_sha256",hash)&&cJSON_AddNumberToObject(j,"total",(double)total)&&
    cJSON_AddNumberToObject(j,"next_unacknowledged",(double)next);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int request_parents(const struct buffer_oobe_authorization_s *auth,const char *peer,const cJSON *request,size_t budget,char **reply)
{
  const char *after=text(request,"after_hash"),*exchange=text(request,"exchange_id");uint64_t offset=0;
  if((*after&&!hex(after,64))||!number(request,"parent_offset",0,32,&offset)||(!*after&&offset))return -EINVAL;
  char next[65];unsigned next_offset=0;cJSON *ids=NULL;
  int ret=buffer_source_remote_parent_requests(peer,after,(unsigned)offset,remote_authorized,(void *)auth,next,&next_offset,&ids);
  if(ret)return error_reply(exchange,ret==-ENOTSUP?"unsupported":storage_error(ret),budget,reply);
  cJSON *j=base("buffer.events.parents",exchange);
  bool built=j&&cJSON_AddStringToObject(j,"after_hash",next)&&cJSON_AddNumberToObject(j,"parent_offset",next_offset);
  if(!built){cJSON_Delete(j);cJSON_Delete(ids);return -ENOMEM;}
  if(!cJSON_AddItemToObject(j,"event_ids",ids)){cJSON_Delete(ids);cJSON_Delete(j);return -ENOMEM;}
  return encode(j,budget,reply);
}
static int locate_parent(const char *peer,const cJSON *request,size_t budget,char **reply)
{
  const char *id=text(request,"event_id"),*exchange=text(request,"exchange_id");size_t id_bytes=strlen(id);
  if(!id_bytes||id_bytes>160)return -EINVAL;
  for(size_t i=0;i<id_bytes;i++)if(!((id[i]>='a'&&id[i]<='z')||(id[i]>='A'&&id[i]<='Z')||
      (id[i]>='0'&&id[i]<='9')||strchr("_.:-",id[i])))return -EINVAL;
  char source[97],prefix[112];uint64_t head=0,sequence=0;
  int ret=buffer_source_local_head(source,sizeof(source),&head);
  if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
  int n=snprintf(prefix,sizeof(prefix),"evt_%s_",source);
  if(n<0||(size_t)n>=sizeof(prefix))return -EIO;
  /* All committed local identities, including legacy snapshots, use this
   * canonical identity. Never scan or forward another owner's remote store. */
  if(strncmp(id,prefix,(size_t)n))return error_reply(exchange,"not_found",budget,reply);
  const char *digits=id+n;if(!*digits||*digits=='0')return error_reply(exchange,"not_found",budget,reply);
  for(const char *p=digits;*p;p++){
    if(*p<'0'||*p>'9'||sequence>(9007199254740990ULL-(unsigned)(*p-'0'))/10)
      return error_reply(exchange,"not_found",budget,reply);
    sequence=sequence*10+(unsigned)(*p-'0');
  }
  if(!sequence||sequence>head)return error_reply(exchange,"not_found",budget,reply);
  char *raw=NULL,hash[65];size_t bytes=0;cJSON *envelope=NULL;
  ret=buffer_source_local_read(sequence,&raw,&bytes);
  if(!ret)ret=buffer_source_envelope_decode(raw,bytes,&envelope);
  if(!ret&&strcmp(text(cJSON_GetObjectItemCaseSensitive(envelope,"event"),"event_id"),id))ret=-EIO;
  cJSON_Delete(envelope);
  bool share=!ret&&may_share(raw,bytes,peer);
  if(!ret&&share){unsigned char digest[32];if(mbedtls_sha256((unsigned char *)raw,bytes,digest,0))ret=-EIO;
    else for(unsigned i=0;i<32;i++)snprintf(hash+2*i,3,"%02x",digest[i]);}
  if(raw){volatile char *p=raw;size_t remaining=bytes;while(remaining--)*p++=0;free(raw);}
  if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
  if(!share)return error_reply(exchange,"withheld",budget,reply);
  cJSON *j=base("buffer.events.parent",exchange);
  bool built=j&&cJSON_AddStringToObject(j,"event_id",id)&&cJSON_AddStringToObject(j,"source_id",source)&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"record_sha256",hash)&&
    cJSON_AddNumberToObject(j,"last_sequence",(double)head);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int blob_reply(const cJSON *request,const char *type,const char *encoded,uint64_t offset,size_t budget,char **reply)
{
  uint64_t sequence=0,total=0;if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"total",44,CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES,&total))return -EINVAL;
  cJSON *j=base(type,text(request,"exchange_id"));
  bool built=j&&cJSON_AddStringToObject(j,"source_id",text(request,"source_id"))&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
    cJSON_AddStringToObject(j,"record_sha256",text(request,"record_sha256"))&&
    cJSON_AddStringToObject(j,"blob_sha256",text(request,"blob_sha256"))&&cJSON_AddNumberToObject(j,"total",(double)total);
  if(built&&encoded)built=cJSON_AddNumberToObject(j,"offset",(double)offset)&&cJSON_AddStringToObject(j,"blob_base64",encoded);
  if(built&&!encoded)built=cJSON_AddStringToObject(j,"state","stored");
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int share_blob_record(const char *peer,uint64_t sequence,bool *allowed)
{
  char *raw=NULL;size_t bytes=0;*allowed=false;int ret=buffer_source_local_read(sequence,&raw,&bytes);
  if(!ret)*allowed=may_share(raw,bytes,peer);
  if(raw){volatile char *p=raw;size_t left=bytes;while(left--)*p++=0;free(raw);}return ret;
}
static int serve_blob(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,
                      const cJSON *request,bool accepted,size_t budget,char **reply)
{
  const char *exchange=text(request,"exchange_id"),*source=text(request,"source_id");
  const char *record=text(request,"record_sha256"),*hash=text(request,"blob_sha256");uint64_t sequence=0,total=0,offset=0,head=0;
  if(!source_id(source)||!hex(record,64)||!hex(hash,64)||!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"total",44,CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES,&total)||
     (!accepted&&!number(request,"offset",0,total-1,&offset)))return -EINVAL;
  char own_source[97];int ret=buffer_source_local_head(own_source,sizeof(own_source),&head);
  if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
  if(strcmp(source,own_source)||sequence>head)return error_reply(exchange,"conflict",budget,reply);
  bool matches=wire->blob_fd>=0&&wire->blob_generation==generation&&wire->blob_sequence==sequence&&wire->blob_bytes==total&&
    !strcmp(wire->blob_peer,peer)&&!strcmp(wire->blob_source,source)&&!strcmp(wire->blob_record_hash,record)&&!strcmp(wire->blob_hash,hash);
  if(accepted||!matches){
    bool allowed=false;ret=share_blob_record(peer,sequence,&allowed);
    if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
    if(!allowed)return error_reply(exchange,"withheld",budget,reply);
  }
  if(accepted){
    ret=buffer_source_blob_accept(peer,source,sequence,record,hash,total,remote_authorized,(void *)auth);
    if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
    return blob_reply(request,"buffer.events.blob.receipt",NULL,0,budget,reply);
  }
  if(!matches){
    reset_blob();ret=buffer_source_blob_open(sequence,record,hash,total,&wire->blob_fd);
    if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
    wire->blob_generation=generation;wire->blob_sequence=sequence;wire->blob_bytes=total;
    snprintf(wire->blob_peer,sizeof(wire->blob_peer),"%s",peer);snprintf(wire->blob_source,sizeof(wire->blob_source),"%s",source);
    snprintf(wire->blob_record_hash,sizeof(wire->blob_record_hash),"%s",record);snprintf(wire->blob_hash,sizeof(wire->blob_hash),"%s",hash);
  }
  wire->blob_touch=now_ms();struct stat st;
  if(!wire->blob_touch){reset_blob();return error_reply(exchange,"storage_error",budget,reply);}
  if(fstat(wire->blob_fd,&st)||!S_ISREG(st.st_mode)||st.st_size!=(off_t)total||(st.st_mode&0077)){
    reset_blob();return error_reply(exchange,"storage_error",budget,reply);
  }
  if(budget<=640)return error_reply(exchange,"unsupported",budget,reply);
  size_t chunk=((budget-640)/4)*3;if(chunk>3072)chunk=3072;if(chunk>total-offset)chunk=(size_t)(total-offset);
  if(!chunk)return error_reply(exchange,"unsupported",budget,reply);
  unsigned char *data=malloc(chunk);size_t encoded_capacity=4*((chunk+2)/3)+1;char *encoded=malloc(encoded_capacity);
  if(!data||!encoded){free(data);free(encoded);return -ENOMEM;}
  if(lseek(wire->blob_fd,(off_t)offset,SEEK_SET)<0)ret=-errno;
  size_t used=0;while(!ret&&used<chunk){ssize_t n=read(wire->blob_fd,data+used,chunk-used);
    if (n < 0 && errno == EINTR)
      continue;
    if (n <= 0) {
      ret = n < 0 ? -errno : -EIO;
      break;
    }
    used += (size_t)n;
  }
  size_t encoded_bytes=0;
  if(!ret)ret=mbedtls_base64_encode((unsigned char *)encoded,encoded_capacity,&encoded_bytes,data,used);
  if(!ret){encoded[encoded_bytes]=0;ret=blob_reply(request,"buffer.events.blob.part",encoded,offset,budget,reply);}
  volatile unsigned char *clear=data;size_t left=chunk;while(left--)*clear++=0;
  clear=(unsigned char *)encoded;left=encoded_capacity;while(left--)*clear++=0;free(data);free(encoded);
  if(ret){reset_blob();if(ret!=-ENOMEM&&ret!=-EMSGSIZE)return error_reply(exchange,"storage_error",budget,reply);}
  return ret;
}
static cJSON *visible_base(const char *type,const char *exchange)
{cJSON *j=base(type,exchange);if(j&&cJSON_AddNumberToObject(j,"visibility_version",1))return j;cJSON_Delete(j);return NULL;}
static int visible_cached(const char *peer,unsigned generation,const char *source,uint64_t sequence)
{
  if(wire->cached&&(wire->cached_sequence!=sequence||wire->cached_generation!=generation||strcmp(wire->cached_peer,peer)||strcmp(wire->cached_source,source)))reset_outgoing();
  if(!wire->cached){
    if(!envelope_room((size_t)BUFFER_SOURCE_EVENT_BYTES+1,0))return -EAGAIN;
    int ret=buffer_source_local_read(sequence,&wire->cached,&wire->cached_bytes);if(ret)return ret;
    wire->cached_generation=generation;wire->cached_sequence=sequence;snprintf(wire->cached_peer,sizeof(wire->cached_peer),"%s",peer);snprintf(wire->cached_source,sizeof(wire->cached_source),"%s",source);
    unsigned char sha[32];if(mbedtls_sha256((unsigned char *)wire->cached,wire->cached_bytes,sha,0)){reset_outgoing();return -EIO;}
    for (unsigned i = 0; i < 32; i++)
      snprintf(wire->cached_hash + 2 * i, 3, "%02x", sha[i]);
    wire->cached_allowed = may_share(wire->cached, wire->cached_bytes, peer);
  }
  wire->cached_touch=now_ms();return 0;
}
static int visible_slot(const char *exchange,const char *source,uint64_t sequence,const char *token,size_t budget,char **reply)
{
  cJSON *j=visible_base("buffer.events.visible.slot",exchange);bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&cJSON_AddStringToObject(j,"slot_token",token);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int visible_ack(const cJSON *request,bool original,const struct buffer_source_visible_delivery_s *progress,size_t budget,char **reply)
{
  uint64_t sequence=0,total=0;if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
    (original&&!number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total)))return -EINVAL;
  cJSON *j=visible_base("buffer.events.visible.receipt",text(request,"exchange_id"));
  bool built=j&&cJSON_AddStringToObject(j,"source_id",text(request,"source_id"))&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
    cJSON_AddStringToObject(j,"slot_token",text(request,"slot_token"))&&cJSON_AddStringToObject(j,"receipt_kind",original?"original":"slot")&&
    cJSON_AddNumberToObject(j,"covered_next",(double)progress->covered_next)&&cJSON_AddNumberToObject(j,"original_next",(double)progress->original_next)&&
    cJSON_AddBoolToObject(j,"checking",progress->checking);
  if(built&&original)built=cJSON_AddStringToObject(j,"record_sha256",text(request,"record_sha256"))&&cJSON_AddNumberToObject(j,"total",(double)total);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static int visible_push(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,size_t budget,const cJSON *request,char **reply)
{
  const char *type=text(request,"type"),*source=text(request,"source_id"),*exchange=text(request,"exchange_id"),*token=text(request,"slot_token");
  bool offer=!strcmp(type,"buffer.events.visible.offer"),reserve=!strcmp(type,"buffer.events.visible.reserve"),
    put=!strcmp(type,"buffer.events.visible.put"),proof=!strcmp(type,"buffer.events.visible.proof");
  uint64_t version=0,last=0,sequence=0,total=0;
  if((!offer&&!reserve&&!put&&!proof)||!source_id(source)||!number(request,"visibility_version",1,1,&version)||
     cJSON_GetArraySize(request)!=(offer?6:reserve?7:put?11:9))return -EINVAL;
  if(budget<1024)return error_reply(exchange,"unsupported",budget,reply);
  if(offer){
    if(!number(request,"last_sequence",0,9007199254740990ULL,&last))return -EINVAL;
    struct buffer_source_visibility_progress_s progress;int ret=buffer_source_remote_visibility_progress(peer,source,remote_authorized,(void *)auth,&progress);
    if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
    if(progress.covered_next>last+1)return error_reply(exchange,"history_rollback",budget,reply);
    reset_incoming();cJSON *j=visible_base("buffer.events.visible.ready",exchange);
    bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"covered_next",(double)progress.covered_next)&&
      cJSON_AddNumberToObject(j,"original_next",(double)progress.original_next);
    if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
  }
  if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||!hex(token,32))return -EINVAL;
  if(put)return receive_part(auth,peer,generation,request,true,budget,reply);
  if(proof&&(!hex(text(request,"record_sha256"),64)||!number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total)))return -EINVAL;
  if(proof){
    struct buffer_source_visibility_progress_s progress;
    int ret=buffer_source_remote_visibility_progress(peer,source,remote_authorized,(void *)auth,&progress);
    if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
    // A hash proof cannot create a reservation or accept unseen original
    // bytes. The sender first obtains a separate durable slot response.
    if(sequence>=progress.covered_next)return visible_received(request,"gap",&progress,0,(size_t)total,budget,reply);
  }
  struct buffer_source_visibility_import_s imported;
  // Repeating a reservation validates an existing token and never erases an
  // original. original_present is still not an original-byte receipt.
  int ret=buffer_source_remote_visibility_reserve(peer,source,sequence,token,remote_authorized,(void *)auth,&imported);
  if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
  const char *state=imported.state==BUFFER_VISIBILITY_HELD?"held":imported.state==BUFFER_VISIBILITY_HELD_DUPLICATE?"held_duplicate":
    imported.state==BUFFER_VISIBILITY_ORIGINAL_PRESENT?"original_present":imported.state==BUFFER_VISIBILITY_GAP?"gap":"conflict";
  if(reserve){
    cJSON *j=visible_base("buffer.events.visible.reserved",exchange);
    bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
      cJSON_AddStringToObject(j,"slot_token",token)&&cJSON_AddStringToObject(j,"state",state)&&
      cJSON_AddNumberToObject(j,"covered_next",(double)imported.progress.covered_next)&&cJSON_AddNumberToObject(j,"original_next",(double)imported.progress.original_next);
    if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
  }
  if(imported.state!=BUFFER_VISIBILITY_ORIGINAL_PRESENT)return visible_received(request,
    imported.state==BUFFER_VISIBILITY_CONFLICT?"conflict":"gap",&imported.progress,0,(size_t)total,budget,reply);
  char *raw=NULL,actual[65];size_t bytes=0;
  ret=buffer_source_remote_owned_read(peer,source,sequence,remote_authorized,(void *)auth,&raw,&bytes);
  if(!ret){unsigned char sha[32];if(mbedtls_sha256((unsigned char *)raw,bytes,sha,0))ret=-EIO;
    else for(unsigned i=0;i<32;i++)snprintf(actual+2*i,3,"%02x",sha[i]);}
  if(raw){volatile char *p=raw;size_t n=bytes;while(n--)*p++=0;free(raw);}
  if(ret)return error_reply(exchange,storage_error(ret),budget,reply);
  if(bytes!=total||strcmp(actual,text(request,"record_sha256")))return error_reply(exchange,"conflict",budget,reply);
  return visible_received(request,"duplicate",&imported.progress,bytes,bytes,budget,reply);
}
static int visible_handle(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,size_t budget,const cJSON *request,char **reply)
{
  const char *type=text(request,"type"),*exchange=text(request,"exchange_id");uint64_t visibility=0;
  if(!strcmp(type,"buffer.events.visible.offer")||!strcmp(type,"buffer.events.visible.reserve")||
     !strcmp(type,"buffer.events.visible.put")||!strcmp(type,"buffer.events.visible.proof"))
    return visible_push(auth,peer,generation,budget,request,reply);
  bool manifest=!strcmp(type,"buffer.events.visible.manifest.get"),read=!strcmp(type,"buffer.events.visible.read"),
    slot_ack=!strcmp(type,"buffer.events.visible.slot.accepted"),original_ack=!strcmp(type,"buffer.events.visible.original.accepted");
  if((!manifest&&!read&&!slot_ack&&!original_ack)||!number(request,"visibility_version",1,1,&visibility)||
     cJSON_GetArraySize(request)!=(manifest?4:slot_ack?7:9))return -EINVAL;
  if(budget<1024)return error_reply(exchange,"unsupported",budget,reply);
  char source[97];uint64_t head=0;int ret=buffer_source_local_head(source,sizeof(source),&head);
  if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
  if(manifest){
    struct buffer_source_visible_delivery_s progress;ret=buffer_source_visible_progress(peer,remote_authorized,(void *)auth,&progress);
    if(!ret)ret=buffer_source_local_head(source,sizeof(source),&head);
    if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
    if(!progress.covered_next||progress.covered_next>head+1||!progress.original_next||progress.original_next>progress.covered_next)return -EIO;
    reset_outgoing();cJSON *j=visible_base("buffer.events.visible.manifest",exchange);
    bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"last_sequence",(double)head)&&
      cJSON_AddNumberToObject(j,"covered_next",(double)progress.covered_next)&&cJSON_AddNumberToObject(j,"original_next",(double)progress.original_next)&&
      cJSON_AddBoolToObject(j,"checking",progress.checking);
    if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
  }
  uint64_t sequence=0,offset=0,total=0;const char *token=text(request,"slot_token"),*hash=text(request,"record_sha256");
  if(strcmp(text(request,"source_id"),source))return error_reply(exchange,"conflict",budget,reply);
  if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(request,"slot_token"))||
     (*token?!hex(token,32):slot_ack))return -EINVAL;
  if(sequence>head)return error_reply(exchange,"not_found",budget,reply);
  if(slot_ack){
    struct buffer_source_visible_delivery_s progress;ret=buffer_source_visible_accept_slot(peer,source,sequence,token,remote_authorized,(void *)auth,&progress);
    if(ret)return error_reply(exchange,ret==-ERANGE?"receipt_gap":local_storage_error(ret),budget,reply);
    return visible_ack(request,false,&progress,budget,reply);
  }
  if(!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(request,"record_sha256"))||(*hash&&!hex(hash,64)))return -EINVAL;
  if(read){if(!number(request,"offset",0,BUFFER_SOURCE_EVENT_BYTES,&offset)||(offset&&!*hash))return -EINVAL;}
  else if(!hex(hash,64)||!number(request,"total",1,BUFFER_SOURCE_EVENT_BYTES,&total))return -EINVAL;
  ret=visible_cached(peer,generation,source,sequence);if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
  // Policy is evaluated from immutable original bytes and this pinned peer.
  // Channel authority remains live for each request and final reply.
  if(!wire->cached_allowed){
    if(original_ack){reset_outgoing();return error_reply(exchange,"withheld",budget,reply);}
    char reserved[33];ret=buffer_source_visible_prepare(peer,source,sequence,remote_authorized,(void *)auth,reserved);
    reset_outgoing();if(ret)return error_reply(exchange,local_storage_error(ret),budget,reply);
    return visible_slot(exchange,source,sequence,reserved,budget,reply);
  }
  if((*hash&&strcmp(hash,wire->cached_hash))||(read?offset>=wire->cached_bytes:total!=wire->cached_bytes))return error_reply(exchange,"conflict",budget,reply);
  if(!wire->cached_visible_offered){
    ret=buffer_source_visible_offered(peer,source,sequence,wire->cached_hash,wire->cached_bytes,remote_authorized,(void *)auth,wire->cached_visible_token);
    if (ret)
      return error_reply(exchange, local_storage_error(ret), budget, reply);
    wire->cached_visible_offered = true;
  }
  if(*token&&strcmp(token,wire->cached_visible_token))return error_reply(exchange,"conflict",budget,reply);
  if(original_ack){
    struct buffer_source_visible_delivery_s progress;ret=buffer_source_visible_accept_original(peer,source,sequence,token,hash,(size_t)total,remote_authorized,(void *)auth,&progress);
    if(ret)return error_reply(exchange,ret==-ERANGE?"receipt_gap":local_storage_error(ret),budget,reply);
    return visible_ack(request,true,&progress,budget,reply);
  }
  // Establish a previously reserved token on a fresh receiver before sending
  // its original, even when the original is currently permitted.
  if(wire->cached_visible_token[0]&&!*token)return visible_slot(exchange,source,sequence,wire->cached_visible_token,budget,reply);
  size_t chunk=((budget-640)/4)*3;if(chunk>3072)chunk=3072;if(chunk>wire->cached_bytes-offset)chunk=wire->cached_bytes-offset;
  if (!chunk)
    return -EMSGSIZE;
  size_t capacity = 4 * ((chunk + 2) / 3) + 1, length = 0;
  char *encoded = malloc(capacity);
  if (!encoded)
    return -ENOMEM;
  ret=mbedtls_base64_encode((unsigned char *)encoded,capacity,&length,(unsigned char *)wire->cached+offset,chunk);
  if(!ret){encoded[length]=0;cJSON *j=visible_base("buffer.events.visible.part",exchange);
    bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
      cJSON_AddStringToObject(j,"slot_token",wire->cached_visible_token)&&cJSON_AddStringToObject(j,"record_sha256",wire->cached_hash)&&
      cJSON_AddNumberToObject(j,"offset",(double)offset)&&cJSON_AddNumberToObject(j,"total",(double)wire->cached_bytes)&&cJSON_AddStringToObject(j,"record_base64",encoded);
    if(built)ret=encode(j,budget,reply);else{cJSON_Delete(j);ret=-ENOMEM;}}
  memset(encoded,0,capacity);free(encoded);return ret;
}
int buffer_source_wire_handle(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,size_t budget,const cJSON *request,char **reply)
{
  if (!reply)
    return -EINVAL;
  *reply = NULL;
  if(!authorized(auth)){buffer_source_wire_close_session(peer,generation);return -EACCES;}
  if(!peer||!hex(peer,64)||budget<512||budget>12288||!cJSON_IsObject(request))return -EINVAL;
  const char *exchange=text(request,"exchange_id"),*type=text(request,"type");uint64_t version=0;
  if(!hex(exchange,32)||!number(request,"protocol",1,1,&version))return -EINVAL;
  (void)buffer_source_wire_session_idle(peer,generation,true);
  if(!select_session(peer,generation))return error_reply(exchange,"busy",budget,reply);
  int ret;
  if(!strncmp(type,"buffer.events.visible.",22)){ret=visible_handle(auth,peer,generation,budget,request,reply);goto done;}
  bool manifest=!strcmp(type,"buffer.events.manifest.get"),read=!strcmp(type,"buffer.events.read");
  bool offer=!strcmp(type,"buffer.events.offer"),put=!strcmp(type,"buffer.events.put");
  bool proof=!strcmp(type,"buffer.events.proof"),accepted=!strcmp(type,"buffer.events.accepted");
  bool parent=!strcmp(type,"buffer.events.parent.get"),parents=!strcmp(type,"buffer.events.parents.get");
  bool blob_get=!strcmp(type,"buffer.events.blob.get"),blob_accepted=!strcmp(type,"buffer.events.blob.accepted");
  bool blob_offer=!strcmp(type,"buffer.events.blob.offer"),blob_put=!strcmp(type,"buffer.events.blob.put");
  int fields=cJSON_GetArraySize(request);uint64_t receipt_version=0;
  bool receipts=manifest&&fields==4&&number(request,"receipt_version",1,1,&receipt_version);
  if((!manifest&&!read&&!offer&&!put&&!proof&&!accepted&&!parent&&!parents&&!blob_get&&!blob_accepted&&!blob_offer&&!blob_put)||
     (manifest?fields!=3&&!receipts:fields!=((blob_accepted||blob_offer)?8:blob_put?10:blob_get?9:parents?5:parent?4:(read||proof||accepted)?7:offer?5:9)))return -EINVAL;
  if(blob_offer||blob_put){
    ret=buffer_source_remote_blob_handle(auth,peer,generation,budget,request,reply);
    if(ret==-ENOTSUP)ret=error_reply(exchange,"unsupported",budget,reply);
    goto done;
  }
  if(blob_get||blob_accepted){ret=serve_blob(auth,peer,generation,request,blob_accepted,budget,reply);goto done;}
  if(parents){ret=request_parents(auth,peer,request,budget,reply);goto done;}
  if(parent){ret=locate_parent(peer,request,budget,reply);goto done;}
  if(accepted){ret=accept_delivery(auth,peer,request,budget,reply);goto done;}
  if(offer||put||proof){ret=offer?receive_offer(auth,peer,request,budget,reply):proof?receive_proof(auth,peer,request,budget,reply):
      receive_part(auth,peer,generation,request,false,budget,reply);goto done;}
  char source[97];uint64_t head=0;ret=buffer_source_local_head(source,sizeof(source),&head);
  if(ret){ret=error_reply(exchange,ret==-ENOTSUP?"unsupported":ret==-EAGAIN?"busy":"storage_error",budget,reply);goto done;}
  if(manifest){
    reset_outgoing();
    uint64_t next=0;
    if(receipts){ret=buffer_source_delivery_progress(peer,remote_authorized,(void *)auth,&next);
      if(ret){ret=error_reply(exchange,storage_error(ret),budget,reply);goto done;}}
    cJSON *j=base("buffer.events.manifest",exchange);bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&
      cJSON_AddNumberToObject(j,"last_sequence",(double)head);
    if(built&&receipts)built=cJSON_AddNumberToObject(j,"receipt_version",1)&&cJSON_AddNumberToObject(j,"next_unacknowledged",(double)next);
    if(!built){cJSON_Delete(j);return -ENOMEM;}ret=encode(j,budget,reply);goto done;
  }
  uint64_t sequence=0,offset=0;
  if(strcmp(text(request,"source_id"),source)){ret=error_reply(exchange,"conflict",budget,reply);goto done;}
  if(!number(request,"source_sequence",1,9007199254740990ULL,&sequence)||
     !number(request,"offset",0,BUFFER_SOURCE_EVENT_BYTES,&offset)||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(request,"record_sha256"))||
     (text(request,"record_sha256")[0]&&!hex(text(request,"record_sha256"),64)))return -EINVAL;
  if(sequence>head){ret=error_reply(exchange,"not_found",budget,reply);goto done;}
  if(wire->cached&&(wire->cached_sequence!=sequence||strcmp(wire->cached_source,source)))reset_outgoing();
  if(!wire->cached){
    if(!envelope_room((size_t)BUFFER_SOURCE_EVENT_BYTES+1,0)){ret=error_reply(exchange,"busy",budget,reply);goto done;}
    ret=buffer_source_local_read(sequence,&wire->cached,&wire->cached_bytes);if(ret){ret=error_reply(exchange,ret==-EAGAIN?"busy":"storage_error",budget,reply);goto done;}
    wire->cached_generation=generation;wire->cached_sequence=sequence;snprintf(wire->cached_peer,sizeof(wire->cached_peer),"%s",peer);snprintf(wire->cached_source,sizeof(wire->cached_source),"%s",source);
    unsigned char sha[32];if(mbedtls_sha256((unsigned char *)wire->cached,wire->cached_bytes,sha,0)){reset_outgoing();return -EIO;}
    for(unsigned i=0;i<32;i++)snprintf(wire->cached_hash+2*i,3,"%02x",sha[i]);
    wire->cached_allowed=may_share(wire->cached,wire->cached_bytes,peer);
  }
  /* This content policy uses only immutable bytes and the pinned peer. Its
   * cached decision stays bound to generation/peer; live channel authority is
   * checked separately for every fragment, including just before returning. */
  if(!wire->cached_allowed){reset_outgoing();ret=error_reply(exchange,"withheld",budget,reply);goto done;}
  wire->cached_touch=now_ms();
  if(offset>=wire->cached_bytes||(offset&&!text(request,"record_sha256")[0])||
     (text(request,"record_sha256")[0]&&strcmp(text(request,"record_sha256"),wire->cached_hash))){ret=error_reply(exchange,"conflict",budget,reply);goto done;}
  size_t chunk=((budget-512)/4)*3;if(chunk>3072)chunk=3072;if(chunk>wire->cached_bytes-offset)chunk=wire->cached_bytes-offset;
  if(!chunk)return -EMSGSIZE;
  char *encoded=malloc(4*((chunk+2)/3)+1);if(!encoded)return -ENOMEM;size_t length=0;
  ret=mbedtls_base64_encode((unsigned char *)encoded,4*((chunk+2)/3)+1,&length,(unsigned char *)wire->cached+offset,chunk);
  if(!ret){encoded[length]=0;cJSON *j=base("buffer.events.part",exchange);
    bool built=j&&cJSON_AddStringToObject(j,"source_id",source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)sequence)&&
      cJSON_AddStringToObject(j,"record_sha256",wire->cached_hash)&&cJSON_AddNumberToObject(j,"offset",(double)offset)&&
      cJSON_AddNumberToObject(j,"total",(double)wire->cached_bytes)&&cJSON_AddStringToObject(j,"record_base64",encoded);
    if(built)ret=encode(j,budget,reply);else{cJSON_Delete(j);ret=-ENOMEM;}}
  memset(encoded,0,4*((chunk+2)/3)+1);free(encoded);
done:
  if(!authorized(auth)){if(*reply){memset(*reply,0,strlen(*reply));free(*reply);*reply=NULL;}buffer_source_wire_close_session(peer,generation);return -EACCES;}
  return ret;
}
