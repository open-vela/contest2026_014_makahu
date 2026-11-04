/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent_input.h"
#include "buffer_scan_clock.h"
#include "buffer_coordinator.h"
#include "buffer_fabric.h"
#include "buffer_replica.h"
#include <mbedtls/sha256.h>
#include <mbedtls/base64.h>
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <syslog.h>

static pthread_mutex_t input_lock=PTHREAD_MUTEX_INITIALIZER;
static unsigned char *snapshot;
static size_t total,cursor,sent_end;
static char id[BUFFER_ID_SIZE],mode[BUFFER_MODE_SIZE],hash[65];
static char capture_target[8]="buffer";
static bool audio;
static int64_t created_at;
static uint32_t duration_ms;
static uint64_t next_send,next_scan;
static char input_owner[65];static uint64_t input_term;
static const char *text(const cJSON *j,const char *key)
{ const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key); return cJSON_IsString(v)?v->valuestring:""; }
static const char *str(const cJSON *j,const char *key) { return text(j,key); }
static void set_capture_target(const char *target)
{ strcpy(capture_target,!strcmp(target,"auto")?"auto":"buffer"); }
static void reset(void)
{ if (snapshot) { memset(snapshot,0,total); free(snapshot); }
  snapshot=NULL; total=cursor=sent_end=0; id[0]=hash[0]=0; strcpy(capture_target,"buffer"); next_send=next_scan=0; }
static void forget_snapshot(const char *capture)
{ pthread_mutex_lock(&input_lock);
  if (snapshot&&!strcmp(id,capture)) reset();
  pthread_mutex_unlock(&input_lock); }
static void receipt_path(char *path,size_t size,const char *capture)
{ snprintf(path,size,"%s/records/%s.input.receipt",CONFIG_BUFFER_VELA_DATA_DIR,capture); }
bool buffer_agent_input_assistant(const char *capture)
{
  if (!buffer_agent_valid_id(capture)) return false;
  char record_path[320];
  if (!buffer_agent_record_path(record_path,sizeof(record_path),capture))
    {
      cJSON *record=buffer_agent_read_json(record_path,BUFFER_AGENT_JSON_BYTES);
      bool delegated=!strcmp(text(record,"target"),"assistant");
      cJSON_Delete(record);
      if (delegated) return true;
    }
  char path[320],line[128];
  snprintf(path,sizeof(path),"%s/queue/%s.meta",CONFIG_BUFFER_VELA_DATA_DIR,capture);
  FILE *file=fopen(path,"r"); if (!file) return false;
  bool assistant=false;
  while (fgets(line,sizeof(line),file)) if (!strncmp(line,"target=assistant",16)) { assistant=true; break; }
  fclose(file); return assistant;
}
/* A configured device resolves its microphone intent locally. Shared Buffer
 * ownership begins only after that decision; unconfigured devices retain the
 * existing phone-ASR fallback. Snapshot reads never touch config storage. */
static bool local_auto_routing(void)
{
  struct buffer_agent_status_s status;
  buffer_agent_snapshot(&status);
  return status.configured;
}
static int select_input(void)
{
  DIR *dir=opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");
  if (!dir) return -errno;
  struct dirent *entry; int ret=0;
  while ((entry=readdir(dir)))
    {
      size_t n=strlen(entry->d_name); if (n<6||n-5>=sizeof(id)||strcmp(entry->d_name+n-5,".json")) continue;
      memcpy(id,entry->d_name,n-5); id[n-5]=0;
      char path[320]; if (buffer_agent_record_path(path,sizeof(path),id)) continue;
      cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
      if (strcmp(text(record,"state"),"pending_classification")||
          (!strcmp(text(record,"target"),"assistant")||
           (!strcmp(text(record,"target"),"auto")&&local_auto_routing()))||
          buffer_agent_input_assistant(id))
        { cJSON_Delete(record); continue; }
      const char *target=text(record,"target");
      if (target[0]&&strcmp(target,"auto")&&strcmp(target,"buffer"))
        { cJSON_Delete(record); continue; }
      set_capture_target(target);
      const char *transcript=text(record,"transcript"); total=strlen(transcript);
      if (!total||total>BUFFER_AGENT_TEXT_BYTES) { cJSON_Delete(record); continue; }
      snapshot=(unsigned char *)strdup(transcript); audio=false;
      snprintf(mode,sizeof(mode),"%s",text(record,"mode"));
      const cJSON *timestamp=cJSON_GetObjectItemCaseSensitive(record,"created_at");
      created_at=cJSON_IsNumber(timestamp)?(int64_t)timestamp->valuedouble:0; duration_ms=0;
      cJSON_Delete(record);
      int accepted=buffer_replica_input_result_pending(id);
      if(accepted){reset();if(accepted<0){ret=accepted;break;}continue;}
      if (!snapshot) { ret=-ENOMEM; break; }
      unsigned char digest[32];
      if (mbedtls_sha256(snapshot,total,digest,0)) { reset(); ret=-EIO; break; }
      for (int i=0;i<32;i++) snprintf(hash+2*i,3,"%02x",digest[i]);
      receipt_path(path,sizeof(path),id);
      cJSON *receipt=buffer_agent_read_json(path,1024);
      const cJSON *owner_term=cJSON_GetObjectItemCaseSensitive(receipt,"owner_term");
      bool delivered=!strcmp(text(receipt,"record_sha256"),hash)&&!strcmp(text(receipt,"owner"),input_owner)&&
        cJSON_IsNumber(owner_term)&&owner_term->valuedouble==(double)input_term; cJSON_Delete(receipt);
      if (delivered) { reset(); continue; }
      ret=1; break;
    }
  closedir(dir); if (ret) return ret;
  char (*ids)[BUFFER_ID_SIZE]=calloc(CONFIG_BUFFER_VELA_QUEUE_LIMIT,sizeof(*ids)); if (!ids) return -ENOMEM;
  int count=buffer_store_list_pending(ids,CONFIG_BUFFER_VELA_QUEUE_LIMIT);
  for (int i=0;i<count;i++)
    {
      if (!buffer_agent_valid_id(ids[i])||buffer_agent_input_assistant(ids[i])) continue;
      char target[BUFFER_CAPTURE_TARGET_NAME_SIZE];
      if (buffer_store_capture_target(ids[i],target,sizeof(target))<0||
          (strcmp(target,"buffer")&&strcmp(target,"auto"))||
          (!strcmp(target,"auto")&&local_auto_routing())) continue;
      char path[320]; buffer_agent_record_path(path,sizeof(path),ids[i]);
      cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
      bool exists=record!=NULL; cJSON_Delete(record); if (exists) continue;
      int accepted=buffer_replica_input_result_pending(ids[i]);
      if(accepted){if(accepted<0){ret=accepted;break;}continue;}
      snprintf(id,sizeof(id),"%s",ids[i]);
      set_capture_target(target);
      ret=buffer_store_read_audio(id,&snapshot,&total); if (ret) { reset(); continue; }
      audio=true; created_at=(int64_t)time(NULL)*1000; duration_ms=0; strcpy(mode,"normal");
      (void)buffer_store_read_metadata(id,&created_at,&duration_ms,mode,sizeof(mode));
      unsigned char digest[32];
      if (mbedtls_sha256(snapshot,total,digest,0)) { reset(); ret=-EIO; continue; }
      for (int j=0;j<32;j++) snprintf(hash+2*j,3,"%02x",digest[j]);
      receipt_path(path,sizeof(path),id); cJSON *receipt=buffer_agent_read_json(path,1024);
      const cJSON *owner_term=cJSON_GetObjectItemCaseSensitive(receipt,"owner_term");
      bool delivered=!strcmp(text(receipt,"record_sha256"),hash)&&!strcmp(text(receipt,"owner"),input_owner)&&
        cJSON_IsNumber(owner_term)&&owner_term->valuedouble==(double)input_term; cJSON_Delete(receipt);
      if (delivered) { reset(); continue; }
      ret=1; break;
    }
  free(ids); return snapshot?1:ret<0?ret:0;
}
int buffer_agent_input_next_for_peer(const char *peer,uint64_t term,uint64_t now,char **message)
{
  *message=NULL;
  bool local=buffer_coordinator_local();
  pthread_mutex_lock(&input_lock);
  int ret=0;
  if(!peer||strlen(peer)!=64||!term){ret=-EINVAL;goto done;}
  if(strcmp(input_owner,peer)||input_term!=term){reset();snprintf(input_owner,sizeof(input_owner),"%s",peer);input_term=term;}
  if (local) { reset(); goto done; }
  if(snapshot&&!strcmp(capture_target,"auto")&&local_auto_routing())reset();
  if (now<next_send) goto done;
  if (!snapshot)
    {
      if (now<next_scan) goto done;
      uint64_t began=buffer_scan_clock_ms();
      ret=select_input();
      /* An empty durable queue must not rescan the whole LittleFS tree on
       * every radio poll. Existing snapshots and ACK progress bypass this. */
      if (ret<=0) { next_scan=buffer_scan_retry_after(now,began,1000); goto done; }
      next_scan=0;
    }
  size_t bytes=total-cursor; if (bytes>3072) bytes=3072;
  unsigned char encoded[4097]; size_t length;
  if (mbedtls_base64_encode(encoded,sizeof(encoded),&length,snapshot+cursor,bytes)) { ret=-EIO; goto done; }
  encoded[length]=0;
  cJSON *part=cJSON_CreateObject();
  cJSON_AddStringToObject(part,"type","vela.capture.input.part"); cJSON_AddNumberToObject(part,"protocol",1);
  cJSON_AddStringToObject(part,"capture_id",id); cJSON_AddStringToObject(part,"mode",mode);
  cJSON_AddStringToObject(part,"target",capture_target);
  cJSON_AddStringToObject(part,"content_type",audio?"audio/wav":"text/plain");
  cJSON_AddNumberToObject(part,"created_at",(double)created_at); cJSON_AddNumberToObject(part,"duration_ms",duration_ms);
  cJSON_AddStringToObject(part,"record_sha256",hash); cJSON_AddNumberToObject(part,"offset",cursor);
  cJSON_AddNumberToObject(part,"total",total); cJSON_AddStringToObject(part,"record_base64",(char *)encoded);
  /* Fabric already retries delivery. Leave room for a full BLE record and
   * its application receipt before adding another copy to the same queue. */
  *message=cJSON_PrintUnformatted(part); cJSON_Delete(part); sent_end=cursor+bytes; next_send=now+30000;
  ret=*message?1:-ENOMEM;
done:
  pthread_mutex_unlock(&input_lock); return ret;
}
int buffer_agent_input_ack_for_peer(const char *peer,uint64_t term,const cJSON *reply)
{
  pthread_mutex_lock(&input_lock); int ret=-ESTALE;
  if (!peer||strcmp(peer,input_owner)||term!=input_term||!snapshot||strcmp(text(reply,"capture_id"),id)||strcmp(text(reply,"record_sha256"),hash)) goto done;
  if (!strcmp(text(reply,"type"),"vela.capture.input.restart"))
    {
      const cJSON *from=cJSON_GetObjectItemCaseSensitive(reply,"from_offset");
      if (!cJSON_IsNumber(from)||from->valuedouble!=(double)cursor) goto done;
      cursor=0; next_send=0; ret=0;
    }
  else if (!strcmp(text(reply,"type"),"vela.capture.input.part.reply"))
    {
      const cJSON *offset=cJSON_GetObjectItemCaseSensitive(reply,"next_offset");
      /* After a local restart/election the phone can still hold a verified
       * prefix of this exact capture/hash. Its cumulative ACK may therefore
       * be ahead of the first retransmitted part. Accept bounded chunk edges
       * so progress resumes; the final import/processed receipts stay separate. */
      if (!cJSON_IsNumber(offset)||!isfinite(offset->valuedouble)||
          offset->valuedouble<(double)sent_end||offset->valuedouble>=(double)total||
          floor(offset->valuedouble)!=offset->valuedouble||
          (size_t)offset->valuedouble%3072) goto done;
      cursor=(size_t)offset->valuedouble; next_send=0; ret=0;
    }
  else if (!strcmp(text(reply,"type"),"vela.capture.input.reply"))
    {
      const char *state=text(reply,"state");
      if (strcmp(state,"imported")&&strcmp(state,"duplicate")&&strcmp(state,"conflict")) goto done;
      char path[320]; receipt_path(path,sizeof(path),id);
      cJSON *receipt=cJSON_CreateObject();
      bool built=receipt&&cJSON_AddStringToObject(receipt,"record_sha256",hash)&&cJSON_AddStringToObject(receipt,"state",state)&&
        cJSON_AddStringToObject(receipt,"owner",input_owner)&&cJSON_AddNumberToObject(receipt,"owner_term",(double)input_term);
      ret=built?buffer_agent_atomic_json(path,receipt):-ENOMEM;cJSON_Delete(receipt);
      if (!ret) reset();
      /* The original stays until a processed replica commits, or the Vela
       * resumes computation after loss of its phone coordinator. */
    }
done:
  if (!strcmp(text(reply,"type"),"vela.capture.input.reply"))
    syslog(LOG_INFO,"Buffer Input: import receipt result=%d\n",ret);
  pthread_mutex_unlock(&input_lock); return ret;
}
void buffer_agent_input_reset(void)
{pthread_mutex_lock(&input_lock);reset();input_owner[0]=0;input_term=0;pthread_mutex_unlock(&input_lock);}
int buffer_agent_input_digest(const char *capture,char hash_out[65])
{
  if (!buffer_agent_valid_id(capture) || !hash_out)
    return -EINVAL;
  hash_out[0] = 0;
  char path[320];int ret=buffer_agent_record_path(path,sizeof(path),capture);if(ret)return ret;
  errno=0;cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!record&&errno!=ENOENT)return errno?-errno:-EIO;
  if(record){
    if(strcmp(text(record,"state"),"pending_classification")||!strcmp(text(record,"target"),"assistant")||
       (!strcmp(text(record,"target"),"auto")&&local_auto_routing())){cJSON_Delete(record);return 0;}
    const char *value=text(record,"transcript");size_t bytes=strlen(value);unsigned char digest[32];
    if(!bytes){cJSON_Delete(record);return -EIO;}
    ret=mbedtls_sha256((const unsigned char *)value,bytes,digest,0)?-EIO:0;
    if(!ret)for(unsigned i=0;i<32;i++)snprintf(hash_out+2*i,3,"%02x",digest[i]);
    memset(digest,0,sizeof(digest));cJSON_Delete(record);return ret?ret:1;
  }
  char target[BUFFER_CAPTURE_TARGET_NAME_SIZE];ret=buffer_store_capture_target(capture,target,sizeof(target));
  if (ret == -ENOENT)
    return 0;
  if (ret)
    return ret;
  if (!strcmp(target, "assistant")||(!strcmp(target,"auto")&&local_auto_routing()))
    return 0;
  unsigned char *audio_bytes=NULL,digest[32];size_t bytes=0;ret=buffer_store_read_audio(capture,&audio_bytes,&bytes);
  if(ret)return ret;
  ret=mbedtls_sha256(audio_bytes,bytes,digest,0)?-EIO:0;
  if(!ret)for(unsigned i=0;i<32;i++)snprintf(hash_out+2*i,3,"%02x",digest[i]);
  volatile unsigned char *wipe=audio_bytes;size_t left=bytes;while(left--)*wipe++=0;free(audio_bytes);memset(digest,0,sizeof(digest));return ret?ret:1;
}
int buffer_agent_input_delegate_proof(const char *capture,const char *peer,uint64_t term,char hash_out[65])
{
  int ret=buffer_agent_input_digest(capture,hash_out);if(ret<=0)return ret;
  char path[320];receipt_path(path,sizeof(path),capture);errno=0;
  cJSON *receipt=buffer_agent_read_json(path,1024);if(!receipt)return errno==ENOENT?-EAGAIN:errno?-errno:-EIO;
  const cJSON *value=cJSON_GetObjectItemCaseSensitive(receipt,"owner_term");
  const char *state=text(receipt,"state");
  bool valid=!strcmp(text(receipt,"owner"),peer)&&cJSON_IsNumber(value)&&value->valuedouble==(double)term&&
    (!strcmp(state,"imported")||!strcmp(state,"duplicate"))&&!strcmp(text(receipt,"record_sha256"),hash_out);
  cJSON_Delete(receipt);return valid?1:-EAGAIN;
}

static int resolve_input_checked(const char *capture)
{
  char path[320];int ret=buffer_agent_record_path(path,sizeof(path),capture);if(ret)return ret;
  errno=0;cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!record&&errno!=ENOENT)return errno?-errno:-EIO;
  /* Assistant ownership cannot be retired by a Buffer card replica. */
  if(record&&!strcmp(text(record,"target"),"assistant")){cJSON_Delete(record);return 0;}
  if(record&&!strcmp(text(record,"state"),"pending_classification")){
    cJSON *state=cJSON_CreateString("phone_complete");
    if(!state){cJSON_Delete(record);return -ENOMEM;}
    if(!cJSON_ReplaceItemInObjectCaseSensitive(record,"state",state)){cJSON_Delete(state);cJSON_Delete(record);return -ENOMEM;}
    ret=buffer_agent_atomic_json(path,record);
  }
  cJSON_Delete(record);return ret?ret:buffer_store_ack(capture);
}
int buffer_agent_input_finish_accepted(const char *capture)
{return buffer_agent_valid_id(capture)?resolve_input_checked(capture):-EINVAL;}
void buffer_agent_input_resolved(const char *capture)
{if(buffer_agent_valid_id(capture))(void)resolve_input_checked(capture);}
#define ASSISTANT_HANDOFF_MAX_BYTES (12 * 1024)
#define ASSISTANT_HANDOFF_TEXT_MAX_BYTES 8192
#define ASSISTANT_HANDOFF_TEXT_MAX_CHARS 4000

static bool lower_hex(const char *value,size_t length)
{
  if (!value || strlen(value)!=length) return false;
  for (size_t i=0;i<length;i++)
    if (!((value[i]>='0'&&value[i]<='9')||(value[i]>='a'&&value[i]<='f'))) return false;
  return true;
}

static int handoff_sha256_hex(const char *value,char output[65])
{
  unsigned char digest[32];
  if (!value||mbedtls_sha256((const unsigned char *)value,strlen(value),digest,0))
    return -EIO;
  for (int i=0;i<32;i++) snprintf(output+2*i,3,"%02x",digest[i]);
  memset(digest,0,sizeof(digest));
  return 0;
}

static bool handoff_utf8(const char *value,size_t *bytes_out)
{
  if (!value) return false;
  const unsigned char *p=(const unsigned char *)value;
  size_t bytes=strlen(value),characters=0;
  if (!bytes || bytes>ASSISTANT_HANDOFF_MAX_BYTES) return false;
  while (*p)
    {
      uint32_t code=*p++; unsigned tail=0;
      if (code<0x80)
        { if (code<0x20&&code!='\n'&&code!='\t') return false; }
      else if (code>=0xc2&&code<=0xdf) { tail=1; code&=31; }
      else if (code>=0xe0&&code<=0xef) { tail=2; code&=15; }
      else if (code>=0xf0&&code<=0xf4) { tail=3; code&=7; }
      else return false;
      for (unsigned i=0;i<tail;i++)
        { if ((*p&0xc0)!=0x80) return false; code=(code<<6)|(*p++&63); }
      if ((tail==1&&code<0x80)||(tail==2&&code<0x800)||(tail==3&&code<0x10000)||
          code>0x10ffff||(code>=0xd800&&code<=0xdfff)) return false;
      if (++characters>ASSISTANT_HANDOFF_TEXT_MAX_CHARS) return false;
    }
  if (bytes>ASSISTANT_HANDOFF_TEXT_MAX_BYTES) return false;
  if (bytes_out) *bytes_out=bytes;
  return characters>0;
}

static bool handoff_unique_fields(const cJSON *request)
{
  unsigned count=0;
  for (const cJSON *field=request?request->child:NULL;field;field=field->next)
    {
      if (!field->string) return false;
      for (const cJSON *prior=request->child;prior!=field;prior=prior->next)
        if (prior->string&&!strcmp(prior->string,field->string)) return false;
      count++;
    }
  return count==7;
}

static bool handoff_request_shape(const cJSON *request,uint64_t *term)
{
  const cJSON *protocol=cJSON_GetObjectItemCaseSensitive(request,"protocol");
  const cJSON *term_json=cJSON_GetObjectItemCaseSensitive(request,"coordinator_term");
  const char *type=text(request,"type");
  if (!cJSON_IsObject(request)||!handoff_unique_fields(request)||
      strcmp(type,"vela.capture.assistant.request")||!cJSON_IsNumber(protocol)||
      protocol->valuedouble!=1||!lower_hex(text(request,"request_id"),32)||
      !buffer_agent_valid_id(text(request,"capture_id"))||
      !lower_hex(text(request,"record_sha256"),64)||!cJSON_IsNumber(term_json)||
      !isfinite(term_json->valuedouble)||term_json->valuedouble<0||
      term_json->valuedouble>9007199254740991.0||floor(term_json->valuedouble)!=term_json->valuedouble)
    return false;
  *term=(uint64_t)term_json->valuedouble;
  for (const cJSON *field=request->child;field;field=field->next)
    if (strcmp(field->string,"type")&&strcmp(field->string,"protocol")&&
        strcmp(field->string,"request_id")&&strcmp(field->string,"capture_id")&&
        strcmp(field->string,"record_sha256")&&strcmp(field->string,"transcript")&&
        strcmp(field->string,"coordinator_term")) return false;
  return cJSON_IsString(cJSON_GetObjectItemCaseSensitive(request,"transcript"));
}

static int handoff_reply(const cJSON *request,uint64_t term,const char *state,
                         const char *error,char **reply)
{
  cJSON *response=cJSON_CreateObject();
  if (!response) return -ENOMEM;
  cJSON_AddStringToObject(response,"type","vela.capture.assistant.reply");
  cJSON_AddNumberToObject(response,"protocol",1);
  cJSON_AddStringToObject(response,"request_id",text(request,"request_id"));
  cJSON_AddStringToObject(response,"capture_id",text(request,"capture_id"));
  cJSON_AddStringToObject(response,"record_sha256",text(request,"record_sha256"));
  cJSON_AddNumberToObject(response,"coordinator_term",(double)term);
  cJSON_AddStringToObject(response,"state",state);
  if (error&&error[0]) cJSON_AddStringToObject(response,"error_code",error);
  *reply=cJSON_PrintUnformatted(response); cJSON_Delete(response);
  return *reply?0:-ENOMEM;
}

struct handoff_commit_s
{
  const char *path;
  const cJSON *record;
};

static int handoff_commit_record(void *context)
{
  const struct handoff_commit_s *args=context;
  return buffer_agent_atomic_json(args->path,args->record);
}

static bool handoff_set_string(cJSON *object,const char *name,const char *value)
{
  if (cJSON_GetObjectItemCaseSensitive(object,name))
    return cJSON_ReplaceItemInObjectCaseSensitive(object,name,cJSON_CreateString(value));
  return cJSON_AddStringToObject(object,name,value)!=NULL;
}

static int handoff_count_records(void)
{
  DIR *dir=opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");
  if (!dir) return -errno;
  unsigned count=0; struct dirent *entry;
  while ((entry=readdir(dir)))
    {
      size_t n=strlen(entry->d_name); char candidate[BUFFER_ID_SIZE];
      if (n>5&&n-5<sizeof(candidate)&&!strcmp(entry->d_name+n-5,".json"))
        { memcpy(candidate,entry->d_name,n-5); candidate[n-5]=0;
          if (buffer_agent_valid_id(candidate)) count++; }
    }
  closedir(dir);
  return count>=BUFFER_AGENT_RECORDS_LIMIT?-ENOSPC:0;
}

int buffer_agent_input_handle_assistant_request(
    const struct buffer_oobe_authorization_s *auth,
    const char *authenticated_phone,const unsigned char *data,
    size_t length,char **reply)
{
  if (reply) *reply=NULL;
  if (!auth||!auth->authorize||!authenticated_phone||!data||!reply||
      !length||length>ASSISTANT_HANDOFF_MAX_BYTES||memchr(data,0,length)) return -EINVAL;
  char *wire=malloc(length+1);
  if (!wire) return -ENOMEM;
  memcpy(wire,data,length); wire[length]=0;
  for (size_t i=0;i+5<length;i++)
    if (wire[i]=='\\'&&wire[i+1]=='u'&&wire[i+2]=='0'&&wire[i+3]=='0'&&
        wire[i+4]=='0'&&wire[i+5]=='0')
      { memset(wire,0,length); free(wire); return -EINVAL; }
  cJSON *request=cJSON_ParseWithOpts(wire,NULL,true); memset(wire,0,length); free(wire);
  uint64_t term=0;
  if (!handoff_request_shape(request,&term)) { cJSON_Delete(request); return -EINVAL; }
  const char *capture=text(request,"capture_id"),*expected_hash=text(request,"record_sha256");
  const char *transcript=text(request,"transcript");
  const char *state="failed",*error="invalid_request";
  int ret=0;
  bool wake_worker=false;
  if (!handoff_utf8(transcript,NULL)) goto respond;
  if (auth->authorize(auth->context,BUFFER_OOBE_GET_STATE)<0)
    { error="unauthorized"; goto respond; }
  if (!buffer_oobe_setup_complete()) { error="setup_incomplete"; goto respond; }
  char local_id[65];
  if (buffer_fabric_identity_for_peer(authenticated_phone,local_id)<0)
    { error="unauthorized"; goto respond; }

  pthread_mutex_lock(&g_buffer_capture_lock);
  char record_path[320];
  ret=buffer_agent_record_path(record_path,sizeof(record_path),capture);
  if (ret<0) { state="conflict"; error="invalid_capture"; ret=0; goto unlock; }
  cJSON *record=buffer_agent_read_json(record_path,BUFFER_AGENT_JSON_BYTES);
  int read_error=errno;
  if (!record&&read_error!=ENOENT) { error="storage_error"; goto unlock; }

  /* A persisted remote handoff marker remains the durable idempotency key.
   * It remains sufficient after assistant completion has removed the WAV. */
  if (record&&!strcmp(text(record,"target"),"assistant")&&
      !strcmp(text(record,"handoff_source_sha256"),expected_hash))
    {
      if (strcmp(text(record,"transcript"),transcript))
        { state="conflict"; error="capture_payload_mismatch"; }
      else if (strcmp(text(record,"state"),"pending_classification")&&
              strcmp(text(record,"state"),"assistant_complete"))
        { state="conflict"; error="capture_state_conflict"; }
      else if (!buffer_coordinator_phone_owner_current(authenticated_phone,term))
        { error="not_coordinator"; }
      else
        { state="duplicate"; error=NULL;
          wake_worker=!strcmp(text(record,"state"),"pending_classification"); }
      cJSON_Delete(record); goto unlock;
    }

  /* A local auto decision owns the same capture even if the phone later wins
   * coordinator election. Match its original input or text digest and report
   * duplicate without replacing the device's transcript or rerunning tools. */
  if (record&&!strcmp(text(record,"target"),"assistant"))
    {
      const char *stored_transcript=text(record,"transcript");
      const char *intent_source=text(record,"intent_source_sha256");
      const char *intent_text=text(record,"intent_text_sha256");
      const char *source=text(record,"source_sha256");
      char stored_text_hash[65]={0};
      bool text_hashed=stored_transcript[0]&&
          handoff_sha256_hex(stored_transcript,stored_text_hash)==0;
      bool same_input=(lower_hex(intent_source,64)&&
                       !strcmp(intent_source,expected_hash))||
          (lower_hex(source,64)&&!strcmp(source,expected_hash));
      bool same_text=(lower_hex(intent_text,64)&&
                      !strcmp(intent_text,expected_hash))||
          (!intent_text[0]&&text_hashed&&!strcmp(stored_text_hash,expected_hash));
      bool state_ok=!strcmp(text(record,"state"),"pending_classification")||
                    !strcmp(text(record,"state"),"assistant_complete");
      if (state_ok&&(same_input||same_text))
        {
          if (!buffer_coordinator_phone_owner_current(authenticated_phone,term))
            { error="not_coordinator"; }
          else
            { state="duplicate"; error=NULL;
              wake_worker=!strcmp(text(record,"state"),"pending_classification"); }
        }
      else
        { state="conflict"; error=state_ok?"capture_source_mismatch":"capture_state_conflict"; }
      memset(stored_text_hash,0,sizeof(stored_text_hash));
      cJSON_Delete(record); goto unlock;
    }

  if (record&&strcmp(text(record,"state"),"pending_classification"))
    { state="conflict"; error="capture_state_conflict"; cJSON_Delete(record); goto unlock; }
  if (record&&strcmp(text(record,"target"),"auto"))
    { state="conflict"; error="capture_target_conflict"; cJSON_Delete(record); goto unlock; }
  if (record&&(cJSON_GetObjectItemCaseSensitive(record,"segments")||
               cJSON_GetObjectItemCaseSensitive(record,"assistant")))
    { state="conflict"; error="capture_already_processed"; cJSON_Delete(record); goto unlock; }
  bool source_is_transcript=record!=NULL;
  bool source_matches_record=false;
  unsigned char digest[32]; char actual_hash[65];
  if (source_is_transcript)
    {
      const char *stored_transcript=str(record,"transcript");
      const char *stored_source=text(record,"source_sha256");
      if (!stored_transcript[0])
        { state="conflict"; error="capture_payload_mismatch"; cJSON_Delete(record); goto unlock; }
      source_matches_record=lower_hex(stored_source,64)&&
                            !strcmp(stored_source,expected_hash);
      if (strcmp(stored_transcript,transcript)&&!source_matches_record)
        { state="conflict"; error="capture_payload_mismatch"; cJSON_Delete(record); goto unlock; }
      if (source_matches_record)
        memcpy(actual_hash,stored_source,sizeof(actual_hash));
      else if (mbedtls_sha256((const unsigned char *)stored_transcript,
                              strlen(stored_transcript),digest,0))
        { cJSON_Delete(record); error="hash_failed"; goto unlock; }
    }
  else
    {
      char queued_target[BUFFER_CAPTURE_TARGET_NAME_SIZE];
      ret=buffer_store_capture_target(capture,queued_target,sizeof(queued_target));
      if (ret<0||strcmp(queued_target,"auto"))
        { state="conflict"; error="capture_target_conflict"; ret=0; goto unlock; }
      unsigned char *audio_bytes=NULL; size_t audio_length=0;
      ret=buffer_store_read_audio(capture,&audio_bytes,&audio_length);
      if (ret<0)
        { state=ret==-ENOENT?"conflict":"failed"; error=ret==-ENOENT?"source_missing":"storage_error"; ret=0; goto unlock; }
      if (mbedtls_sha256(audio_bytes,audio_length,digest,0))
        { memset(audio_bytes,0,audio_length); free(audio_bytes); error="hash_failed"; goto unlock; }
      volatile unsigned char *wipe=audio_bytes;
      for (size_t i=0;i<audio_length;i++) wipe[i]=0;
      free(audio_bytes);
    }
  if (!source_matches_record)
    {
      for (int i=0;i<32;i++) snprintf(actual_hash+2*i,3,"%02x",digest[i]);
      memset(digest,0,sizeof(digest));
    }
  if (strcmp(actual_hash,expected_hash))
    { state="conflict"; error="source_hash_mismatch"; cJSON_Delete(record); goto unlock; }
  if (!record)
    {
      ret=handoff_count_records();
      if (ret<0) { error=ret==-ENOSPC?"record_limit":"storage_error"; ret=0; goto unlock; }
      int64_t capture_created_at=(int64_t)time(NULL)*1000;
      uint32_t capture_duration=0;
      char capture_mode[BUFFER_MODE_SIZE]="normal";
      ret=buffer_store_read_metadata(capture,&capture_created_at,
                                    &capture_duration,capture_mode,
                                    sizeof(capture_mode));
      if (ret<0) { error="storage_error"; ret=0; goto unlock; }
      record=cJSON_CreateObject();
      if (!record) { ret=-ENOMEM; error="storage_error"; goto unlock; }
      cJSON_AddStringToObject(record,"capture_id",capture);
      cJSON_AddStringToObject(record,"mode",capture_mode);
      cJSON_AddNumberToObject(record,"created_at",(double)capture_created_at);
      cJSON_AddStringToObject(record,"state","pending_classification");
    }
  if (!handoff_set_string(record,"target","assistant")||
      !handoff_set_string(record,"transcript",transcript)||
      !handoff_set_string(record,"handoff_source_sha256",expected_hash)||
      (!source_is_transcript&&!handoff_set_string(record,"source_sha256",expected_hash)))
    { cJSON_Delete(record); ret=-ENOMEM; error="storage_error"; goto unlock; }
  if (strcmp(str(record,"capture_id"),capture)||strcmp(str(record,"transcript"),transcript)||
      strcmp(str(record,"state"),"pending_classification")||
      strcmp(str(record,"target"),"assistant")||
      strcmp(str(record,"handoff_source_sha256"),expected_hash))
    { cJSON_Delete(record); ret=-ENOMEM; error="storage_error"; goto unlock; }
  /* Recheck authorization and identity just before the serialized durable
   * write; the coordinator helper holds its election lock through that write. */
  if (auth->authorize(auth->context,BUFFER_OOBE_GET_STATE)<0||
      !buffer_oobe_setup_complete()||
      buffer_fabric_identity_for_peer(authenticated_phone,local_id)<0)
    { cJSON_Delete(record); error="unauthorized"; goto unlock; }
  struct handoff_commit_s commit_args={record_path,record};
  ret=buffer_coordinator_phone_owner_commit(authenticated_phone,term,
      handoff_commit_record,&commit_args);
  cJSON_Delete(record);
  if (ret==-EAGAIN) { error="not_coordinator"; ret=0; }
  else if (ret<0) { error="storage_error"; ret=0; }
  else { state="accepted"; error=NULL; }
unlock:
  pthread_mutex_unlock(&g_buffer_capture_lock);
  if (!strcmp(state,"accepted")) wake_worker=true;
  if (wake_worker||!strcmp(state,"duplicate")) forget_snapshot(capture);
  if (wake_worker) buffer_agent_wake();
respond:
  if (!ret) ret=handoff_reply(request,term,state,error,reply);
  cJSON_Delete(request);
  return ret;
}
