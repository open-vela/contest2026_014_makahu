/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_crdt_sync.h"
#include "buffer_scan_clock.h"
#include "buffer_source_privacy.h"
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define DIR_PATH CONFIG_BUFFER_VELA_DATA_DIR "/replicas"
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
#define CRDT_LINKS 4
struct crdt_transfer_s {
  char peer[65];unsigned generation;
  char *pending,current[BUFFER_ID_SIZE],hash[65],pending_peer[65];
  unsigned pending_generation;
  size_t size,offset,end;
  uint64_t retry,scan_at,opened_at;
};
static struct crdt_transfer_s transfers[CRDT_LINKS];
static struct crdt_transfer_s *transfer=&transfers[0];
static const char *txt(const cJSON *j,const char *k)
{ const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,k);return cJSON_IsString(v)?v->valuestring:""; }
static int ack_path(char *path,size_t capacity,const char *id,const char *peer)
{ int n=snprintf(path,capacity,"%s/%s.%s.crdtack",DIR_PATH,id,peer);return n<0||(size_t)n>=capacity?-EINVAL:0; }
static void reset_locked(void)
{
  if (transfer->pending) {volatile char *p=transfer->pending;size_t remaining=transfer->size+1;while(remaining--)*p++=0;free(transfer->pending);}
  transfer->pending=NULL;transfer->size=transfer->offset=transfer->end=0;transfer->retry=transfer->scan_at=transfer->opened_at=0;
  transfer->current[0]=transfer->hash[0]=transfer->pending_peer[0]=0;transfer->pending_generation=0;
}
void buffer_crdt_sync_reset(void)
{
  pthread_mutex_lock(&lock);struct crdt_transfer_s *saved=transfer;
  for(unsigned i=0;i<CRDT_LINKS;i++){transfer=&transfers[i];reset_locked();transfer->peer[0]=0;transfer->generation=0;}
  transfer=saved;pthread_mutex_unlock(&lock);
}
void buffer_crdt_sync_close_session(const char *peer,unsigned generation)
{
  if (!peer)
    return;
  pthread_mutex_lock(&lock);
  struct crdt_transfer_s *saved = transfer;
  for(unsigned i=0;i<CRDT_LINKS;i++)if(transfers[i].generation==generation&&!strcmp(transfers[i].peer,peer)){
    transfer=&transfers[i];reset_locked();transfer->peer[0]=0;transfer->generation=0;
  }
  transfer=saved;pthread_mutex_unlock(&lock);
}
static bool select_transfer(const char *peer,unsigned generation,bool create)
{
  if(!buffer_source_peer_valid(peer)||!generation)return false;
  struct crdt_transfer_s *empty=NULL;
  for(unsigned i=0;i<CRDT_LINKS;i++){
    struct crdt_transfer_s *slot=&transfers[i];
    if(slot->generation==generation&&!strcmp(slot->peer,peer)){transfer=slot;return true;}
    if(!empty&&!slot->pending)empty=slot;
  }
  if (!create || !empty)
    return false;
  transfer = empty;
  reset_locked();
  snprintf(transfer->peer,sizeof(transfer->peer),"%s",peer);transfer->generation=generation;return true;
}
static bool pending_allowed_locked(const char *peer,unsigned generation,uint64_t now)
{
  if (!transfer->pending) return false;
  if (!buffer_source_peer_valid(peer) || strcmp(peer,transfer->pending_peer) || generation!=transfer->pending_generation ||
      !now || !transfer->opened_at || now<transfer->opened_at || now-transfer->opened_at>=30000)
    {reset_locked();return false;}
  cJSON *record=buffer_agent_parse_json(transfer->pending);
  bool allowed=buffer_source_replica_may_share(record,peer);cJSON_Delete(record);
  if (!allowed) reset_locked();
  return allowed;
}
int buffer_crdt_sync_pending_check(const char *peer,unsigned generation,uint64_t now)
{
  int locked=pthread_mutex_trylock(&lock);
  if(locked)return locked==EBUSY?-EAGAIN:-locked;
  bool allowed=select_transfer(peer,generation,false)&&pending_allowed_locked(peer,generation,now);
  pthread_mutex_unlock(&lock);return allowed?1:0;
}
bool buffer_crdt_sync_pending_allowed(const char *peer,unsigned generation,uint64_t now)
{
  pthread_mutex_lock(&lock);bool allowed=select_transfer(peer,generation,false)&&pending_allowed_locked(peer,generation,now);
  pthread_mutex_unlock(&lock);return allowed;
}
static int select_document(const char *peer,unsigned generation,uint64_t now)
{
  DIR *directory=opendir(DIR_PATH);if (!directory) return errno==ENOENT?0:-errno;
  struct dirent *entry;int ret=0;
  while ((entry=readdir(directory)))
    {
      size_t n=strlen(entry->d_name);if (n<6 || n-5>=sizeof(transfer->current) || strcmp(entry->d_name+n-5,".json")) continue;
      char id[BUFFER_ID_SIZE],path[320];memcpy(id,entry->d_name,n-5);id[n-5]=0;if (!buffer_agent_valid_id(id)) continue;
      snprintf(path,sizeof(path),"%s/%s",DIR_PATH,entry->d_name);
      cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
      cJSON *doc=cJSON_GetObjectItemCaseSensitive(record,"crdt");
      if (!doc || buffer_crdt_validate(doc) || strcmp(txt(doc,"kind"),"card") || strcmp(txt(doc,"id"),id) ||
          !buffer_source_replica_may_share(record,peer)) {cJSON_Delete(record);continue;}
      /* A local creation must reach the phone as an immutable processed
       * capture before later field changes. Never trade away its source. */
      const char *capture=txt(cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(doc,"fields"),"capture_id"),"value");
      char source_path[320];snprintf(source_path,sizeof(source_path),"%s/records/%s.json",CONFIG_BUFFER_VELA_DATA_DIR,capture);
      if (!strncmp(id,"vla_",4) && buffer_agent_valid_id(capture) && access(source_path,F_OK)==0) {
          snprintf(path,sizeof(path),"%s/records/%s.receipt",CONFIG_BUFFER_VELA_DATA_DIR,capture);
          cJSON *receipt=buffer_agent_read_json(path,1024);
          bool imported=!strcmp(txt(receipt,"state"),"imported") || !strcmp(txt(receipt,"state"),"duplicate");
          cJSON_Delete(receipt);if (!imported) {cJSON_Delete(record);continue;}
      }
      char *raw=cJSON_PrintUnformatted(record);cJSON_Delete(record);if (!raw) {ret=-ENOMEM;break;}
      size_t bytes=strlen(raw);if (bytes>BUFFER_AGENT_JSON_BYTES) {free(raw);ret=-EFBIG;break;}
      unsigned char digest[32];char fingerprint[65];
      if (mbedtls_sha256((unsigned char *)raw,bytes,digest,0)) {free(raw);ret=-EIO;break;}
      for (unsigned i=0;i<32;i++) snprintf(fingerprint+2*i,3,"%02x",digest[i]);
      ack_path(path,sizeof(path),id,peer);cJSON *receipt=buffer_agent_read_json(path,1024);
      bool delivered=!strcmp(txt(receipt,"peer"),peer) && !strcmp(txt(receipt,"card_id"),id) &&
        !strcmp(txt(receipt,"hash"),fingerprint);cJSON_Delete(receipt);
      if (delivered) {free(raw);continue;}
      transfer->pending=raw;transfer->size=bytes;transfer->offset=transfer->end=0;transfer->retry=0;transfer->opened_at=now;transfer->pending_generation=generation;
      strcpy(transfer->pending_peer,peer);snprintf(transfer->current,sizeof(transfer->current),"%s",id);strcpy(transfer->hash,fingerprint);ret=1;break;
    }
  closedir(directory);return ret;
}
int buffer_crdt_sync_next(const char *peer,unsigned generation,uint64_t now,size_t budget,char **message)
{
  if (!message) return -EINVAL;
  *message=NULL;
  pthread_mutex_lock(&lock);int ret=0;
  if(!select_transfer(peer,generation,true)){pthread_mutex_unlock(&lock);return -EAGAIN;}
  (void)pending_allowed_locked(peer,generation,now);
  if (!buffer_source_peer_valid(peer) || !now) {reset_locked();pthread_mutex_unlock(&lock);return -EACCES;}
  if (!transfer->pending && now>=transfer->scan_at) {
    uint64_t began=buffer_scan_clock_ms();
    ret=select_document(peer,generation,now);
    transfer->scan_at=buffer_scan_retry_after(now,began,2000);
  }
  if (ret<0 || !transfer->pending || now<transfer->retry) {pthread_mutex_unlock(&lock);return ret<0?ret:0;}
  size_t bytes=transfer->size-transfer->offset;if(bytes>3072)bytes=3072;
  char encoded[4097];
  while (bytes)
    {
      size_t length=0;ret=mbedtls_base64_encode((unsigned char *)encoded,sizeof(encoded)-1,&length,
        (unsigned char *)transfer->pending+transfer->offset,bytes);
      if(ret)break;
      encoded[length]=0;cJSON *part=cJSON_CreateObject();
      bool built=part && cJSON_AddStringToObject(part,"type","buffer.crdt.part") && cJSON_AddNumberToObject(part,"protocol",1) &&
        cJSON_AddStringToObject(part,"record_sha256",transfer->hash) && cJSON_AddNumberToObject(part,"offset",transfer->offset) &&
        cJSON_AddNumberToObject(part,"total",transfer->size) && cJSON_AddStringToObject(part,"record_base64",encoded);
      *message=built?cJSON_PrintUnformatted(part):NULL;cJSON_Delete(part);
      if (!*message) {ret=-ENOMEM;break;}
      if (strlen(*message)<=budget) {transfer->end=transfer->offset+bytes;transfer->opened_at=now;transfer->retry=now+5000;ret=1;break;}
      memset(*message,0,strlen(*message));free(*message);*message=NULL;bytes/=2;ret=-EFBIG;
    }
  memset(encoded,0,sizeof(encoded));
  pthread_mutex_unlock(&lock);return ret;
}
int buffer_crdt_sync_ack(const char *peer,unsigned generation,const cJSON *reply)
{
  const cJSON *protocol=cJSON_GetObjectItemCaseSensitive(reply,"protocol");
  if (!cJSON_IsObject(reply) || cJSON_GetArraySize(reply)!=5 || strcmp(txt(reply,"type"),"buffer.crdt.reply") ||
      !cJSON_IsNumber(protocol) || protocol->valuedouble!=1) return -EINVAL;
  pthread_mutex_lock(&lock);int ret=-ESTALE;
  if(!select_transfer(peer,generation,false)){pthread_mutex_unlock(&lock);return ret;}
  const cJSON *next=cJSON_GetObjectItemCaseSensitive(reply,"next_offset");
  if (transfer->pending && buffer_source_peer_valid(peer) && !strcmp(peer,transfer->pending_peer) && generation==transfer->pending_generation &&
      transfer->end>transfer->offset && !strcmp(transfer->hash,txt(reply,"record_sha256")) && cJSON_IsNumber(next) && next->valuedouble==(double)transfer->end) {
      const char *state=txt(reply,"state");
      if (transfer->end<transfer->size && !strcmp(state,"partial")) {transfer->offset=transfer->end;transfer->retry=0;ret=0;}
      else if (transfer->end==transfer->size && (!strcmp(state,"stored") || !strcmp(state,"duplicate") || !strcmp(state,"merged"))) {
          char path[320];ret=ack_path(path,sizeof(path),transfer->current,peer);cJSON *receipt=cJSON_CreateObject();
          bool built=receipt && cJSON_AddStringToObject(receipt,"hash",transfer->hash) && cJSON_AddStringToObject(receipt,"peer",peer) &&
            cJSON_AddStringToObject(receipt,"card_id",transfer->current);
          if (!ret)
            ret = built ? buffer_agent_atomic_json(path, receipt) : -ENOMEM;
          cJSON_Delete(receipt);
          if (!ret) reset_locked();
      }
  }
  pthread_mutex_unlock(&lock);return ret;
}
