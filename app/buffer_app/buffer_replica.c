/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_replica.h"
#include "buffer_agent_input.h"
#include "buffer_crdt_sync.h"
#include "buffer_fabric.h"
#include "buffer_coordinator.h"
#include "buffer_deck.h"
#include "buffer_source_events.h"
#include "buffer_source_envelope.h"
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define REPLICA_DIR CONFIG_BUFFER_VELA_DATA_DIR "/replicas"
#define REPLICA_LIMIT 1024
#define REPLICA_LINKS 4
struct replica_transfer_s {
  unsigned char *assembly;size_t assembled,expected;unsigned generation;
  char assembling_hash[65],assembling_peer[65];
};
static struct replica_transfer_s replica_transfers[REPLICA_LINKS];
static struct replica_transfer_s *replica_transfer=&replica_transfers[0];
static uint64_t action_next;
static DIR *result_directory;
static pthread_mutex_t result_directory_lock=PTHREAD_MUTEX_INITIALIZER;

static const char *text(const cJSON *j, const char *key)
{ const cJSON *v = cJSON_GetObjectItemCaseSensitive(j,key); return cJSON_IsString(v) ? v->valuestring : ""; }
static bool integer(const cJSON *j, double maximum)
{ return cJSON_IsNumber(j) && isfinite(j->valuedouble) && j->valuedouble >= 0 &&
    j->valuedouble <= maximum && floor(j->valuedouble) == j->valuedouble; }
static bool digest_valid(const char *hash)
{
  if (!hash || strlen(hash) != 64) return false;
  for (int i=0;i<64;i++)
    {
      if (!((hash[i]>='0'&&hash[i]<='9')||(hash[i]>='a'&&hash[i]<='f'))) return false;
    }
  return true;
}
static int path_for(char *path, size_t size, const char *id)
{ if (!buffer_agent_valid_id(id)) return -EINVAL;
  int n=snprintf(path,size,"%s/%s.json",REPLICA_DIR,id); return n<0||(size_t)n>=size ? -EINVAL : 0; }

static bool add_copy(cJSON *object,const char *key,const cJSON *value)
{
  cJSON *copy=cJSON_Duplicate(value,true);if(!copy)return false;
  if(!cJSON_AddItemToObject(object,key,copy)){cJSON_Delete(copy);return false;}return true;
}
/* Action availability follows the projected task state, including after a
 * remote state-only merge. The authoritative action register is unchanged. */
static bool task_action_view(cJSON *values)
{
  cJSON *actions=cJSON_GetObjectItemCaseSensitive(values,"actions");
  if(strcmp(text(values,"kind"),"task_card")||!cJSON_IsArray(actions))return true;
  bool done=!strcmp(text(values,"state"),"done");
  bool closed=!strcmp(text(values,"state"),"archived")||!strcmp(text(values,"state"),"deleted");
  cJSON *result=cJSON_CreateArray(),*action;if(!result)return false;
  cJSON_ArrayForEach(action,actions){
    const char *name=cJSON_IsString(action)?action->valuestring:"";
    if(!strcmp(name,"reopen")&&closed)continue;
    if(done&&!strcmp(name,"done"))name="reopen";
    else if(!done&&!strcmp(name,"reopen"))name="done";
    if(!cJSON_AddItemToArray(result,cJSON_CreateString(name))){cJSON_Delete(result);return false;}
  }
  if(!cJSON_ReplaceItemInObjectCaseSensitive(values,"actions",result)){cJSON_Delete(result);return false;}
  return true;
}
cJSON *buffer_replica_crdt_record(const cJSON *document,const cJSON *previous)
{
  cJSON *values=buffer_crdt_values(document);
  if (!values) return NULL;
  cJSON *record=cJSON_CreateObject();
  bool deleted=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(document,"deleted"));
  if(!record||!cJSON_AddStringToObject(record,"card_id",text(document,"id"))||
    !cJSON_AddNumberToObject(record,"revision",(double)buffer_crdt_clock(document))||
    !cJSON_AddBoolToObject(record,"deleted",deleted)||!add_copy(record,"crdt",document))goto fail;
  if (!deleted)
    {
      cJSON_DeleteItemFromObjectCaseSensitive(values,"card_id");
      if(!cJSON_AddStringToObject(values,"card_id",text(document,"id")))goto fail;
      if (!strcmp(text(document,"kind"),"calendar"))
        {
          cJSON_DeleteItemFromObjectCaseSensitive(values,"kind");cJSON_DeleteItemFromObjectCaseSensitive(values,"state");
          cJSON_DeleteItemFromObjectCaseSensitive(values,"summary");cJSON_DeleteItemFromObjectCaseSensitive(values,"created_at");
          if(!cJSON_AddStringToObject(values,"kind","calendar_event")||!cJSON_AddStringToObject(values,"state","active")||
            !cJSON_AddStringToObject(values,"summary",text(values,"location")))goto fail;
          const cJSON *schedule=cJSON_GetObjectItemCaseSensitive(values,"schedule");
          const cJSON *start=cJSON_GetObjectItemCaseSensitive(schedule,"start");
          if (!cJSON_IsNumber(start)||!isfinite(start->valuedouble)||fabs(start->valuedouble)>9007199254740991.0||
              floor(start->valuedouble)!=start->valuedouble) {cJSON_Delete(values);cJSON_Delete(record);return NULL;}
          if(!cJSON_AddNumberToObject(values,"created_at",start->valuedouble))goto fail;
        }
      if(!task_action_view(values))goto fail;
      cJSON *view=cJSON_GetObjectItemCaseSensitive(values,"presentation");
      if (cJSON_IsObject(view)&&!add_copy(record,"presentation",view))goto fail;
      view=cJSON_GetObjectItemCaseSensitive(values,"conversation");
      if (cJSON_IsArray(view)&&!add_copy(record,"conversation",view))goto fail;
      if (!cJSON_AddItemToObject(record, "card", values))
        goto fail;
      values = NULL;
    }
  if(!cJSON_AddBoolToObject(record,"processed",cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(previous,"processed"))))goto fail;
  if(digest_valid(text(previous,"origin"))&&!cJSON_AddStringToObject(record,"origin",text(previous,"origin")))goto fail;
  cJSON_Delete(values);return record;
fail:
  cJSON_Delete(values);cJSON_Delete(record);return NULL;
}
bool buffer_replica_crdt_contains(const char *id)
{
  char path[320];if (path_for(path,sizeof(path),id)) return false;
  cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  bool present=cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(record,"crdt"));
  cJSON_Delete(record);return present;
}
static bool crdt_action_supported(const char *action)
{ return !strcmp(action,"done") || !strcmp(action,"reopen") || !strcmp(action,"archive") || !strcmp(action,"delete") || !strcmp(action,"research"); }
static int local_actor(char actor[65])
{
  char peer[65];if (!buffer_fabric_identity_pair(actor,peer)) return 0;
  char id[BUFFER_ID_SIZE];pthread_mutex_lock(&g_buffer.lock);snprintf(id,sizeof(id),"%s",g_buffer.device_id);pthread_mutex_unlock(&g_buffer.lock);
  if (!id[0]) return -EAGAIN;
  unsigned char digest[32];if (mbedtls_sha256((const unsigned char *)id,strlen(id),digest,0)) return -EIO;
  for (unsigned i=0;i<32;i++) snprintf(actor+i*2,3,"%02x",digest[i]);
  return 0;
}
int buffer_replica_crdt_action(const char *id,const char *action)
{
  if (!action || !crdt_action_supported(action)) return -ENOTSUP;
  char path[320],actor[65];if (path_for(path,sizeof(path),id)) return -EINVAL;
  int ret=local_actor(actor);if (ret) return ret;
  pthread_mutex_lock(&g_buffer_capture_lock);
  cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  cJSON *old=cJSON_GetObjectItemCaseSensitive(record,"crdt");
  if (!old) {cJSON_Delete(record);pthread_mutex_unlock(&g_buffer_capture_lock);return -ENOTSUP;}
  if (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(old,"deleted"))) ret=-ENOENT;
  cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card");
  if(!ret&&!strcmp(action,"reopen")&&(strcmp(text(card,"kind"),"task_card")||strcmp(text(card,"state"),"done")))ret=-ESTALE;
  cJSON *actions=cJSON_GetObjectItemCaseSensitive(card,"actions");
  if (!ret && cJSON_IsArray(actions) && cJSON_GetArraySize(actions)>0)
    {
      bool allowed=false;cJSON *entry;
      cJSON_ArrayForEach(entry,actions) if(cJSON_IsString(entry)&&!strcmp(entry->valuestring,action)) allowed=true;
      if(!allowed) ret=-EACCES;
    }
  cJSON *changes=cJSON_CreateObject();
  const char *state=!strcmp(action,"reopen")?"active":!strcmp(action,"archive")?"archived":!strcmp(action,"delete")?"deleted":!strcmp(action,"research")?"rabbit_hole_candidate":"done";
  if(!ret&&(!changes||!cJSON_AddStringToObject(changes,"state",state)))ret=-ENOMEM;
  // A grow-only tombstone otherwise retains old sensitive registers. Clear
  // their values in this actual source mutation, before journaling the result.
  if(!ret&&!strcmp(action,"delete")&&(!cJSON_AddNullToObject(changes,"capture_context")||
     !cJSON_AddStringToObject(changes,"context_summary","")||!cJSON_AddBoolToObject(changes,"bedtime_readable",false)))ret=-ENOMEM;
  cJSON *merged=ret?NULL:buffer_crdt_update(old,id,"card",changes,actor,!strcmp(action,"delete"));
  cJSON *next=merged?buffer_replica_crdt_record(merged,record):NULL;
  if (!ret && !next) ret=-EINVAL;
  if (!ret) ret=buffer_source_projection_json(path,next,"CardActionPerformed",action);
  if(!ret&&(!strcmp(state,"archived")||!strcmp(state,"deleted")||!strcmp(state,"done")))
    buffer_deck_retire_home(id);
  cJSON_Delete(next);cJSON_Delete(merged);cJSON_Delete(changes);cJSON_Delete(record);
  pthread_mutex_unlock(&g_buffer_capture_lock);
  if (!ret) {
    buffer_agent_projection_changed();ret=buffer_agent_refresh_cards();
    if(ret<0&&(!strcmp(state,"archived")||!strcmp(state,"deleted")||!strcmp(state,"done"))){buffer_agent_wake();ret=0;}
  }
  return ret;
}
int buffer_replica_publish_local_action(const char *id,const char *action)
{
  if (!crdt_action_supported(action)) return 0;
  if (buffer_replica_crdt_contains(id)) return buffer_replica_crdt_action(id,action);
  char actor[65],path[320];int ret=local_actor(actor);if (ret) return ret;
  if (path_for(path,sizeof(path),id)) return -EINVAL;
  DIR *dir=opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");if (!dir) return -errno;
  struct dirent *entry; cJSON *values=NULL;
  for (;;)
    {
      errno=0;entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
      size_t n=strlen(entry->d_name);if (n<6 || n-5>=BUFFER_ID_SIZE || strcmp(entry->d_name+n-5,".json")) continue;
      char capture[BUFFER_ID_SIZE],record_path[320];memcpy(capture,entry->d_name,n-5);capture[n-5]=0;
      if (buffer_agent_record_path(record_path,sizeof(record_path),capture)) continue;
      errno=0;cJSON *record=buffer_agent_read_json(record_path,BUFFER_AGENT_JSON_BYTES),*segment;
      if(!record){ret=errno?-errno:-EIO;break;}
      cJSON_ArrayForEach(segment,cJSON_GetObjectItemCaseSensitive(record,"segments"))
        if (!strcmp(text(segment,"card_id"),id))
          {
            values=cJSON_Duplicate(segment,true);
            if(!values){ret=-ENOMEM;break;}
            cJSON_DeleteItemFromObjectCaseSensitive(values,"state");
            cJSON_DeleteItemFromObjectCaseSensitive(values,"capture_id");
            cJSON_DeleteItemFromObjectCaseSensitive(values,"created_at");
            const cJSON *created=cJSON_GetObjectItemCaseSensitive(record,"created_at");
            if(!cJSON_AddStringToObject(values,"state",text(segment,"previous_state")[0]?text(segment,"previous_state"):"active")||
               !cJSON_AddStringToObject(values,"capture_id",capture)||
               !cJSON_AddNumberToObject(values,"created_at",cJSON_IsNumber(created)?created->valuedouble:0))ret=-ENOMEM;
            /* Preserve source fields; the phone supplies its complete action
             * list after import instead of competing with an invented subset. */
            break;
          }
      cJSON_Delete(record);
      if(values||ret)break;
    }
  closedir(dir);if(ret){cJSON_Delete(values);return ret;}if (!values) return -ENOENT;
  cJSON *doc=buffer_crdt_update(NULL,id,"card",values,actor,false);
  cJSON *record=doc?buffer_replica_crdt_record(doc,NULL):NULL;
  char own[65],recipient[65];
  if(record&&!buffer_fabric_identity_pair(own,recipient)&&!cJSON_AddStringToObject(record,"origin",recipient))
    {cJSON_Delete(record);record=NULL;}
  cJSON_Delete(doc);cJSON_Delete(values);
  if (!record) return -EINVAL;
  if (mkdir(REPLICA_DIR,0700) && errno!=EEXIST) {cJSON_Delete(record);return -errno;}
  pthread_mutex_lock(&g_buffer_capture_lock);
  if (!buffer_replica_contains(id)) ret=buffer_agent_atomic_json(path,record);
  pthread_mutex_unlock(&g_buffer_capture_lock);cJSON_Delete(record);
  return ret ? ret:buffer_replica_crdt_action(id,action);
}

static void reset_transfer(void)
{ if (replica_transfer->assembly) { memset(replica_transfer->assembly,0,replica_transfer->expected); free(replica_transfer->assembly); }
  replica_transfer->assembly=NULL;replica_transfer->generation=0;replica_transfer->assembled=replica_transfer->expected=0; replica_transfer->assembling_hash[0]=replica_transfer->assembling_peer[0]=0; }

void buffer_replica_reset(void)
{struct replica_transfer_s *saved=replica_transfer;for(unsigned i=0;i<REPLICA_LINKS;i++){replica_transfer=&replica_transfers[i];reset_transfer();}replica_transfer=saved;pthread_mutex_lock(&result_directory_lock);if(result_directory){closedir(result_directory);result_directory=NULL;}pthread_mutex_unlock(&result_directory_lock);}
void buffer_replica_close_session(const char *peer,unsigned generation)
{if(!peer)return;for(unsigned i=0;i<REPLICA_LINKS;i++)if(replica_transfers[i].generation==generation&&!strcmp(replica_transfers[i].assembling_peer,peer)){struct replica_transfer_s *saved=replica_transfer;replica_transfer=&replica_transfers[i];reset_transfer();replica_transfer=saved;}}
static bool select_transfer(const char *peer,unsigned generation)
{
  struct replica_transfer_s *empty=NULL;
  for(unsigned i=0;i<REPLICA_LINKS;i++){
    if(replica_transfers[i].generation==generation&&!strcmp(replica_transfers[i].assembling_peer,peer)){replica_transfer=&replica_transfers[i];return true;}
    if(!empty&&!replica_transfers[i].assembly)empty=&replica_transfers[i];
  }
  if (!empty)
    return false;
  replica_transfer = empty;
  reset_transfer();
  return true;
}

static int commit_view(const struct buffer_oobe_authorization_s *auth,const char *peer,
                       const char *hash,const cJSON *record,const char **state,const char *accepted_capture)
{
  const char *id=text(record,"card_id"); char path[320];
  const cJSON *incoming=cJSON_GetObjectItemCaseSensitive(record,"crdt");
  if (incoming) {
      if (path_for(path,sizeof(path),id) || buffer_crdt_validate(incoming) || strcmp(text(incoming,"id"),id)) return -EINVAL;
      int ret=buffer_agent_store_init();if (ret) return ret;
      const char *directory=REPLICA_DIR;
      if (!strcmp(text(incoming,"kind"),"calendar")) {
          directory=CONFIG_BUFFER_VELA_DATA_DIR "/calendars";
          if(snprintf(path,sizeof(path),"%s/%s.json",directory,id)>=(int)sizeof(path)) return -EINVAL;
      }
      if (mkdir(directory,0700) && errno!=EEXIST) return -errno;
      if (chmod(directory,0700)) return -errno;
      /* Caller holds g_buffer_capture_lock across merge and atomic commit. */
      errno=0;cJSON *old=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
      if (!old && errno!=ENOENT) return errno?-errno:-EIO;
      const cJSON *old_fields=cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(old,"crdt"),"fields");
      const char *old_capture=text(cJSON_GetObjectItemCaseSensitive(old,"card"),"capture_id");
      if(!old_capture[0])old_capture=text(cJSON_GetObjectItemCaseSensitive(old_fields,"capture_id"),"value");
      bool accepted_scope=accepted_capture&&(!strcmp(old_capture,accepted_capture)||
        (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(old,"deleted"))&&!strcmp(text(old,"card_id"),id)));
      if(old&&digest_valid(text(old,"origin"))&&strcmp(text(old,"origin"),peer)&&!accepted_scope)
        {cJSON_Delete(old);return -EACCES;}
      cJSON *merged=buffer_crdt_merge(cJSON_GetObjectItemCaseSensitive(old,"crdt"),incoming);
      cJSON *projected=merged?buffer_replica_crdt_record(merged,record):NULL;
      if (!projected) {cJSON_Delete(merged);cJSON_Delete(old);return -EPROTO;}
      // Transport payloads cannot assign their own forwarding provenance.
      // Do not inherit an ownership marker from the incoming wire object.
      cJSON_DeleteItemFromObjectCaseSensitive(projected,"origin");
      cJSON_DeleteItemFromObjectCaseSensitive(projected,"local_original");
      // Unknown historical owners stay unknown unless this peer supplied the
      // entire merged document, including all old fields and stamps. A partial
      // update must not grant access to someone else's retained registers.
      const char *owner=!old||!strcmp(text(old,"origin"),peer)||cJSON_Compare(merged,incoming,true)?peer:"";
      bool marked=cJSON_AddStringToObject(projected,"origin",owner)!=NULL;
      if(!marked){cJSON_Delete(projected);cJSON_Delete(merged);cJSON_Delete(old);return -ENOMEM;}
      *state=old && !strcmp(text(old,"origin"),owner) && cJSON_Compare(cJSON_GetObjectItemCaseSensitive(old,"crdt"),merged,true)?"duplicate":"stored";
      if (auth->authorize(auth->context,BUFFER_OOBE_GET_STATE)) ret=-EACCES;
      if (!ret && strcmp(*state,"duplicate")) ret=buffer_agent_atomic_json(path,projected);
      const char *card_state=text(cJSON_GetObjectItemCaseSensitive(projected,"card"),"state");
      if(!ret&&(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(projected,"deleted"))||
         !strcmp(card_state,"archived")||!strcmp(card_state,"deleted")||!strcmp(card_state,"done")))
        buffer_deck_retire_home(id);
      cJSON_Delete(projected);cJSON_Delete(merged);cJSON_Delete(old);return ret;
  }
  const cJSON *revision=cJSON_GetObjectItemCaseSensitive(record,"revision");
  const cJSON *deleted=cJSON_GetObjectItemCaseSensitive(record,"deleted");
  const cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card");
  if (!cJSON_IsObject(record) || path_for(path,sizeof(path),id) ||
      !integer(revision,9007199254740991.0) || revision->valuedouble<1 || !cJSON_IsBool(deleted)) return -EINVAL;
  if (!cJSON_IsTrue(deleted) && (!cJSON_IsObject(card) || strcmp(text(card,"card_id"),id) ||
      strlen(text(card,"title"))>1024 || strlen(text(card,"summary"))>16384 ||
      strlen(text(card,"kind"))<1 || strlen(text(card,"kind"))>=32 ||
      strlen(text(card,"state"))<1 || strlen(text(card,"state"))>=32 ||
      !integer(cJSON_GetObjectItemCaseSensitive(card,"created_at"),9007199254740991.0))) return -EINVAL;
  int ret=buffer_agent_store_init(); if (ret<0) return ret;
  if (mkdir(REPLICA_DIR,0700) && errno!=EEXIST) return -errno;
  if (chmod(REPLICA_DIR,0700)) return -errno;
  cJSON *previous=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if (!previous && errno!=ENOENT) return -errno;
  *state="stored";
  if (previous)
    {
      if (cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(previous,"crdt"))) {cJSON_Delete(previous);*state="stale";return 0;}
      const cJSON *old=cJSON_GetObjectItemCaseSensitive(previous,"revision");
      bool tombstone=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(previous,"deleted"));
      int order=integer(old,9007199254740991.0) ?
        (revision->valuedouble>old->valuedouble ? 1 : revision->valuedouble<old->valuedouble ? -1 : strcmp(peer,text(previous,"origin"))) : 1;
      if ((tombstone && !cJSON_IsTrue(deleted)) || order<0) *state="stale";
      else if (!order)
        {
          if (strcmp(hash,text(previous,"record_sha256"))) { cJSON_Delete(previous); return -EEXIST; }
          *state="duplicate";
        }
      cJSON_Delete(previous);
      if (strcmp(*state,"stored")) {
        return 0;
      }
    }
  else
    {
      DIR *dir=opendir(REPLICA_DIR); if (!dir) return -errno;
      struct dirent *entry; unsigned count=0;
      while ((entry=readdir(dir))) if (strstr(entry->d_name,".json")) count++;
      closedir(dir); if (count>=REPLICA_LIMIT) return -ENOSPC;
    }
  if (auth->authorize(auth->context,BUFFER_OOBE_GET_STATE)) return -EACCES;
  cJSON *copy=cJSON_Duplicate(record,true); if (!copy) return -ENOMEM;
  cJSON_DeleteItemFromObjectCaseSensitive(copy,"origin");
  cJSON_DeleteItemFromObjectCaseSensitive(copy,"local_original");
  cJSON_DeleteItemFromObjectCaseSensitive(copy,"record_sha256");
  cJSON_AddStringToObject(copy,"origin",peer); cJSON_AddStringToObject(copy,"record_sha256",hash);
  ret=buffer_agent_atomic_json(path,copy); cJSON_Delete(copy);
  if(!ret&&(cJSON_IsTrue(deleted)||!strcmp(text(card,"state"),"archived")||
     !strcmp(text(card,"state"),"deleted")||!strcmp(text(card,"state"),"done")))
    buffer_deck_retire_home(id);
  return ret;
}

/* A local acceptance is a fact, not a restored lease. Its original source
 * entry seals exact result bytes before any derived view or queue ACK changes. */
#define INPUT_RESULTS CONFIG_BUFFER_VELA_DATA_DIR "/input-results"
static int result_path(const char *capture,char path[320])
{
  if(!buffer_agent_valid_id(capture))return -EINVAL;
  int ret=buffer_agent_private_directory(INPUT_RESULTS);if(ret)return ret;
  int n=snprintf(path,320,INPUT_RESULTS "/%s.json",capture);return n<0||n>=320?-ENAMETOOLONG:0;
}
static int result_digest(const char *raw,char hash[65])
{
  unsigned char value[32];if(mbedtls_sha256((const unsigned char *)raw,strlen(raw),value,0))return -EIO;
  for (unsigned i = 0; i < 32; i++)
    snprintf(hash + 2 * i, 3, "%02x", value[i]);
  memset(value, 0, sizeof(value));
  return 0;
}
static bool result_card_id(const char *id,const char *capture)
{
  char hash[65],prefix[48];if(result_digest(capture,hash))return false;
  snprintf(prefix,sizeof(prefix),"vla_%.32s_",hash);size_t n=strlen(prefix);
  if(strncmp(id,prefix,n)||!id[n]||(id[n]=='0'&&id[n+1]))return false;
  unsigned index=0;for(const char *p=id+n;*p;p++){
    if (*p < '0' || *p > '9' || index > 102)
      return false;
    index = index * 10 + (unsigned)(*p - '0');
  }
  return index<1024;
}
static int validate_result(const cJSON *record,const char *capture)
{
  const char *id=text(record,"card_id");
  if(!cJSON_IsObject(record)||!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"processed"))||
     !cJSON_IsFalse(cJSON_GetObjectItemCaseSensitive(record,"deleted"))||!result_card_id(id,capture))return -EINVAL;
  const cJSON *doc=cJSON_GetObjectItemCaseSensitive(record,"crdt");
  if(doc){
    if(buffer_crdt_validate(doc)||strcmp(text(doc,"kind"),"card")||strcmp(text(doc,"id"),id)||
       cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(doc,"deleted")))return -EINVAL;
    cJSON *view=buffer_replica_crdt_record(doc,record);if(!view)return -ENOMEM;
    bool valid=!strcmp(text(cJSON_GetObjectItemCaseSensitive(view,"card"),"capture_id"),capture);
    cJSON_Delete(view);return valid?0:-EINVAL;
  }
  const cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card"),*revision=cJSON_GetObjectItemCaseSensitive(record,"revision");
  return cJSON_IsObject(card)&&!strcmp(text(card,"capture_id"),capture)&&!strcmp(text(card,"card_id"),id)&&
    integer(revision,9007199254740991.0)&&revision->valuedouble>=1&&strlen(text(card,"title"))<=1024&&
    strlen(text(card,"summary"))<=16384&&text(card,"kind")[0]&&strlen(text(card,"kind"))<32&&
    text(card,"state")[0]&&strlen(text(card,"state"))<32&&integer(cJSON_GetObjectItemCaseSensitive(card,"created_at"),9007199254740991.0)?0:-EINVAL;
}
static int load_result(const char *capture,cJSON **receipt,cJSON **record)
{
  *receipt=NULL;*record=NULL;char path[320],hash[65];int ret=result_path(capture,path);if(ret)return ret;
  errno=0;*receipt=buffer_agent_read_json(path,BUFFER_SOURCE_INPUT_RESULT_BYTES);
  if(!*receipt)return errno==ENOENT?0:errno?-errno:-EIO;
  const cJSON *version=cJSON_GetObjectItemCaseSensitive(*receipt,"schema_version"),*term=cJSON_GetObjectItemCaseSensitive(*receipt,"owner_term");
  const char *raw=text(*receipt,"record_json");
  if(!cJSON_IsObject(*receipt)||cJSON_GetArraySize(*receipt)!=7||!integer(version,1)||version->valuedouble!=1||
     strcmp(text(*receipt,"capture_id"),capture)||!digest_valid(text(*receipt,"input_sha256"))||
     !digest_valid(text(*receipt,"owner_device_id"))||!integer(term,9007199254740991.0)||term->valuedouble<1||
     !digest_valid(text(*receipt,"record_sha256"))||!raw[0]||strlen(raw)>BUFFER_AGENT_JSON_BYTES){ret=-EIO;goto failed;}
  ret=result_digest(raw,hash);if(ret)goto failed;
  if(strcmp(hash,text(*receipt,"record_sha256"))){ret=-EIO;goto failed;}
  ret=buffer_source_json_object(raw,strlen(raw),BUFFER_AGENT_JSON_BYTES,record);if(ret)goto failed;
  ret=validate_result(*record,capture);if(ret)goto failed;return 1;
failed:
  cJSON_Delete(*receipt);cJSON_Delete(*record);*receipt=*record=NULL;return ret;
}
int buffer_replica_input_result_pending(const char *capture)
{
  if(!buffer_agent_valid_id(capture))return 0;
  cJSON *receipt=NULL,*record=NULL;int ret=load_result(capture,&receipt,&record);
  if(ret>0){char hash[65];ret=buffer_agent_input_digest(capture,hash);
    if(ret>0)ret=!strcmp(hash,text(receipt,"input_sha256"))?1:0;}
  cJSON_Delete(receipt);cJSON_Delete(record);return ret;
}
static int accepted_authorize(void *unused,enum buffer_oobe_operation_e operation)
{(void)unused;(void)operation;return 0;}
static int apply_result(const char *capture,const cJSON *receipt,const cJSON *record)
{
  char hash[65];int pending=buffer_agent_input_digest(capture,hash);
  if(pending<0)return pending;
  if(!pending){
    char path[320];int ret=buffer_agent_record_path(path,sizeof(path),capture);if(ret)return ret;
    errno=0;cJSON *native=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
    if(!native&&errno!=ENOENT)return errno?-errno:-EIO;
    bool complete=native&&!strcmp(text(native,"state"),"phone_complete")&&strcmp(text(native,"target"),"assistant");
    cJSON_Delete(native);if(!complete)return 0;
    char target[BUFFER_CAPTURE_TARGET_NAME_SIZE];ret=buffer_store_capture_target(capture,target,sizeof(target));
    if (ret == -ENOENT)
      return 0;
    if (ret)
      return ret;
    ret=buffer_agent_input_finish_accepted(capture);return ret?ret:1;
  }
  if(strcmp(hash,text(receipt,"input_sha256")))return 0;
  struct buffer_oobe_authorization_s accepted={accepted_authorize,NULL};const char *state="stored";
  int ret=commit_view(&accepted,text(receipt,"owner_device_id"),text(receipt,"record_sha256"),record,&state,capture);
  if(ret)return ret;
  /* A committed user tombstone remains absorbing. Recovering a result never
   * restarts a model, calendar action, reminder or assistant operation. */
  ret=buffer_agent_input_finish_accepted(capture);return ret?ret:1;
}
int buffer_replica_recover_input_result(const char *capture)
{
  cJSON *receipt=NULL,*record=NULL;int ret=load_result(capture,&receipt,&record);
  if(ret>0)ret=apply_result(capture,receipt,record);
  if(ret>0){buffer_agent_projection_changed();int refreshed=buffer_agent_refresh_cards();if(refreshed<0)ret=refreshed;}
  cJSON_Delete(receipt);cJSON_Delete(record);return ret;
}
struct accept_result_s {
  const struct buffer_oobe_authorization_s *auth;
  const char *capture,*input_hash,*path;
  const cJSON *receipt;
};
static int seal_result(void *context)
{
  struct accept_result_s *value=context;char current[65];
  if(value->auth->authorize(value->auth->context,BUFFER_OOBE_GET_STATE))return -EACCES;
  int ret=buffer_agent_input_digest(value->capture,current);
  if (ret < 0)
    return ret;
  if (!ret || strcmp(current, value->input_hash))
    return -ESTALE;
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  return buffer_source_projection_json(value->path,value->receipt,"DeviceInputResultAccepted",NULL);
#else
  char *raw=cJSON_PrintUnformatted(value->receipt);if(!raw)return -ENOMEM;
  size_t bytes=strlen(raw);ret=buffer_agent_atomic_bytes(value->path,raw,bytes,BUFFER_SOURCE_INPUT_RESULT_BYTES);
  memset(raw,0,bytes);free(raw);return ret;
#endif
}
static int commit(const struct buffer_oobe_authorization_s *auth,const char *peer,const char *hash,
                  const cJSON *record,const char *raw,const char **state)
{
  const char *capture=text(cJSON_GetObjectItemCaseSensitive(record,"card"),"capture_id");
  if(!buffer_agent_valid_id(capture)||!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"processed"))||
     cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted"))||!result_card_id(text(record,"card_id"),capture))
    return commit_view(auth,peer,hash,record,state,NULL);
  char input_hash[65];int pending=buffer_agent_input_digest(capture,input_hash);if(pending<0)return pending;
  if(!pending)return commit_view(auth,peer,hash,record,state,NULL);
  int ret=validate_result(record,capture);if(ret)return ret;
  cJSON *receipt=NULL,*accepted=NULL;ret=load_result(capture,&receipt,&accepted);if(ret<0)return ret;
  if(ret&&strcmp(input_hash,text(receipt,"input_sha256"))){cJSON_Delete(receipt);cJSON_Delete(accepted);receipt=accepted=NULL;ret=0;}
  if(!ret){
    uint64_t term=buffer_coordinator_fence();ret=buffer_agent_input_delegate_proof(capture,peer,term,input_hash);
    if(ret<=0){if(!ret)ret=-ESTALE;goto done;}
    receipt=cJSON_CreateObject();
    bool built=receipt&&cJSON_AddNumberToObject(receipt,"schema_version",1)&&cJSON_AddStringToObject(receipt,"capture_id",capture)&&
      cJSON_AddStringToObject(receipt,"input_sha256",input_hash)&&cJSON_AddStringToObject(receipt,"owner_device_id",peer)&&
      cJSON_AddNumberToObject(receipt,"owner_term",(double)term)&&cJSON_AddStringToObject(receipt,"record_sha256",hash)&&
      cJSON_AddStringToObject(receipt,"record_json",raw);
    if(!built){ret=-ENOMEM;goto done;}
    char path[320];ret=result_path(capture,path);if(ret)goto done;
    struct accept_result_s args={auth,capture,input_hash,path,receipt};
    ret=buffer_coordinator_phone_owner_commit(peer,term,seal_result,&args);if(ret)goto done;
    accepted=cJSON_Duplicate(record,true);if(!accepted){ret=-ENOMEM;goto done;}
  }
  ret=apply_result(capture,receipt,accepted);if(ret<0)goto done;
  if(!strcmp(text(accepted,"card_id"),text(record,"card_id"))){
    *state=!strcmp(hash,text(receipt,"record_sha256"))?"duplicate":"stale";ret=0;
  }else ret=commit_view(auth,peer,hash,record,state,NULL);
done:
  cJSON_Delete(receipt);cJSON_Delete(accepted);return ret;
}
/* Capture lock is held by the application or native background worker. One
 * directory entry per pass; original lookup restores a missing cache by ID. */
int buffer_replica_recover_results_step(void)
{
  char capture[BUFFER_ID_SIZE]={0};int ret=1;pthread_mutex_lock(&result_directory_lock);
  if(!result_directory){
    result_directory=opendir(INPUT_RESULTS);
    if(!result_directory){ret=errno==ENOENT?0:-errno;goto done;}
  }
  errno=0;struct dirent *entry=readdir(result_directory);
  if(!entry){ret=errno?-errno:0;closedir(result_directory);result_directory=NULL;goto done;}
  size_t n=strlen(entry->d_name);
  if(n>5&&n-5<sizeof(capture)&&!strcmp(entry->d_name+n-5,".json")){
    memcpy(capture,entry->d_name,n-5);capture[n-5]=0;
  }
done:
  pthread_mutex_unlock(&result_directory_lock);
  if(ret>0&&buffer_agent_valid_id(capture)){int recovered=buffer_replica_recover_input_result(capture);if(recovered<0)return recovered;}
  return ret;
}

int buffer_replica_receive_session(const struct buffer_oobe_authorization_s *auth,
                           const char *peer,unsigned generation,const unsigned char *data,
                           size_t length, char **reply)
{
  if (!reply || !auth || !auth->authorize || !digest_valid(peer) || !data || !length || length>12288) return -EINVAL;
  *reply=NULL;
  if (auth->authorize(auth->context,BUFFER_OOBE_GET_STATE)) return -EACCES;
  if(!select_transfer(peer,generation))return -EAGAIN;
  cJSON *part=NULL;int parsed=buffer_source_json_object((const char *)data,length,12288,&part);
  if(parsed)return parsed;
  const char *hash=text(part,"record_sha256"), *encoded=text(part,"record_base64");
  const cJSON *offset=cJSON_GetObjectItemCaseSensitive(part,"offset"), *total=cJSON_GetObjectItemCaseSensitive(part,"total");
  int ret=-EINVAL;
  if ((strcmp(text(part,"type"),"buffer.card.replica.part") && strcmp(text(part,"type"),"buffer.crdt.part")) ||
      !integer(cJSON_GetObjectItemCaseSensitive(part,"protocol"),1) ||
      cJSON_GetObjectItemCaseSensitive(part,"protocol")->valuedouble!=1 || !digest_valid(hash) ||
      !integer(offset,BUFFER_AGENT_JSON_BYTES) || !integer(total,BUFFER_AGENT_JSON_BYTES-256) ||
      total->valuedouble<1 || strlen(encoded)>8192) goto done;
  size_t start=(size_t)offset->valuedouble, size=(size_t)total->valuedouble, bytes=0;
  unsigned char *decoded=malloc(6144); if (!decoded) { ret=-ENOMEM; goto done; }
  ret=mbedtls_base64_decode(decoded,6144,&bytes,(const unsigned char *)encoded,strlen(encoded));
  if (ret || !bytes || start>size || bytes>size-start) { free(decoded); ret=-EINVAL; goto done; }
  if (!replica_transfer->assembly || strcmp(replica_transfer->assembling_hash,hash) || strcmp(replica_transfer->assembling_peer,peer))
    {
      if (start) { free(decoded); ret=-ESTALE; goto done; }
      reset_transfer(); replica_transfer->assembly=calloc(1,size+1);
      if (!replica_transfer->assembly) { free(decoded); ret=-ENOMEM; goto done; }
      replica_transfer->generation=generation;replica_transfer->expected=size; strcpy(replica_transfer->assembling_hash,hash); strcpy(replica_transfer->assembling_peer,peer);
    }
  ret=-ESTALE;
  if (size!=replica_transfer->expected || start>replica_transfer->assembled || (start<replica_transfer->assembled &&
      (bytes>replica_transfer->assembled-start || memcmp(replica_transfer->assembly+start,decoded,bytes)))) { free(decoded); goto done; }
  if (start==replica_transfer->assembled) { memcpy(replica_transfer->assembly+start,decoded,bytes); replica_transfer->assembled+=bytes; }
  free(decoded);
  const char *state="partial";
  if (replica_transfer->assembled==replica_transfer->expected)
    {
      unsigned char digest[32]; char hex[65];
      if (mbedtls_sha256(replica_transfer->assembly,replica_transfer->expected,digest,0)) { ret=-EIO; goto done; }
      for (int i=0;i<32;i++) snprintf(hex+2*i,3,"%02x",digest[i]);
      if (strcmp(hex,hash) || memchr(replica_transfer->assembly,0,replica_transfer->expected)) { ret=-EBADMSG; goto done; }
      cJSON *record=NULL;ret=buffer_source_json_object((char *)replica_transfer->assembly,replica_transfer->expected,BUFFER_AGENT_JSON_BYTES,&record);
      if(ret)goto done;
      /* Keep the assembled bytes during contention. A busy response is not
       * a durable receipt: it requests a short retry of this same final chunk
       * instead of making the sender wait for its lost-receipt timeout. */
      int locked=pthread_mutex_trylock(&g_buffer_capture_lock);
      if(locked){cJSON_Delete(record);if(locked==EBUSY){if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(part,"retry_busy"))){state="busy";goto respond;}ret=-EAGAIN;goto done;}ret=-locked;goto done;}
      ret=commit(auth,peer,hash,record,(char *)replica_transfer->assembly,&state); cJSON_Delete(record);
      if (!ret) buffer_agent_projection_changed();
      pthread_mutex_unlock(&g_buffer_capture_lock);
      if (ret==-EAGAIN&&cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(part,"retry_busy"))){state="busy";goto respond;}
      if (ret) goto done;
    }
respond: ;
  cJSON *response=cJSON_CreateObject();
  cJSON_AddStringToObject(response,"type",!strcmp(text(part,"type"),"buffer.crdt.part")?"buffer.crdt.reply":"buffer.card.replica.reply");
  cJSON_AddNumberToObject(response,"protocol",1); cJSON_AddStringToObject(response,"record_sha256",hash);
  cJSON_AddNumberToObject(response,"next_offset",replica_transfer->assembled); cJSON_AddStringToObject(response,"state",state);
  *reply=cJSON_PrintUnformatted(response); cJSON_Delete(response); ret=*reply ? 0 : -ENOMEM;
done:
  cJSON_Delete(part); return ret;
}

int buffer_replica_receive(const struct buffer_oobe_authorization_s *auth,const char *peer,
                           const unsigned char *data,size_t length,char **reply)
{return buffer_replica_receive_session(auth,peer,0,data,length,reply);}

bool buffer_replica_contains(const char *id)
{ char path[320]; struct stat st; return !path_for(path,sizeof(path),id) && !stat(path,&st); }
/* Physical cache ownership still prevents a retired remote card from being
 * resurrected from the screen shelf. Native capture fallback, however, must
 * use the current logical view rather than an uncommitted discovery filename. */
bool buffer_replica_available(const char *id)
{
  char path[320];if(path_for(path,sizeof(path),id))return false;
  errno=0;cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(record){cJSON_Delete(record);return true;}
  if(errno!=ENOENT)return true;
  bool deleted=false;int ret=buffer_source_projection_local_deleted(path,&deleted);
  return ret<0||deleted;
}
static void copy_text(char *out,size_t size,const char *in)
{ size_t n=strlen(in); if (n>=size) { n=size-1; while (n && ((unsigned char)in[n]&0xc0)==0x80) n--; }
  memcpy(out,in,n); out[n]=0; }
static cJSON *read_entry(const char *name)
{ size_t n=strlen(name); char id[BUFFER_ID_SIZE], path[320];
  if (n<=5 || n-5>=sizeof(id) || strcmp(name+n-5,".json")) return NULL;
  memcpy(id,name,n-5); id[n-5]=0; if (path_for(path,sizeof(path),id)) return NULL;
  return buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES); }
static const char *presentation_preview(const cJSON *record)
{
  const cJSON *blocks=cJSON_GetObjectItemCaseSensitive(
      cJSON_GetObjectItemCaseSensitive(record,"presentation"),"blocks");
  const cJSON *block;
  cJSON_ArrayForEach(block,blocks)
    {
      if (!strcmp(text(block,"type"),"paragraph") && text(block,"text")[0])
        return text(block,"text");
      if (!strcmp(text(block,"type"),"list"))
        {
          const cJSON *item=cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(block,"items"),0);
          if (cJSON_IsString(item) && item->valuestring[0]) return item->valuestring;
        }
    }
  return "";
}
int buffer_replica_cards(struct buffer_card_s *cards,int maximum)
{
  if (!cards || maximum <= 0) return 0;
  DIR *dir=opendir(REPLICA_DIR); if (!dir) return errno==ENOENT ? 0 : -errno;
  struct buffer_deck_s *deck=calloc(1,sizeof(*deck));
  if (!deck) {closedir(dir);return -ENOMEM;}
  pthread_mutex_lock(&g_buffer.lock);
  char mode[BUFFER_MODE_SIZE];snprintf(mode,sizeof(mode),"%s",g_buffer.mode);
  pthread_mutex_unlock(&g_buffer.lock);
  buffer_deck_init(deck,mode,(int64_t)time(NULL)*1000);
  struct dirent *entry;
  while ((entry=readdir(dir)))
    {
      cJSON *record=read_entry(entry->d_name), *card=cJSON_GetObjectItemCaseSensitive(record,"card");
      const char *state=text(card,"state");
      if (!card || !strcmp(text(card,"kind"),"calendar_event") || cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted")) ||
          !strcmp(state,"archived") || !strcmp(state,"done") || !strcmp(state,"deleted") || !strcmp(state,"pending_transcription"))
        { cJSON_Delete(record); continue; }
      struct buffer_card_s value={0};
      copy_text(value.card_id,sizeof(value.card_id),text(card,"card_id"));
      copy_text(value.capture_id,sizeof(value.capture_id),text(card,"capture_id"));
      copy_text(value.title,sizeof(value.title),text(card,"title"));
      copy_text(value.summary,sizeof(value.summary),text(card,"summary"));
      const char *result = text(card,"research_notes");
      if (!result[0]) result = text(card,"answer");
      if (!result[0]) result = presentation_preview(record);
      if (deck->bedtime) result=!strcmp(text(card,"kind"),"question_card") ? text(card,"answer") : "";
      copy_text(value.answer,sizeof(value.answer),result);
      const cJSON *revision=cJSON_GetObjectItemCaseSensitive(record,"revision");
      value.content_revision=cJSON_IsNumber(revision) ? (uint64_t)revision->valuedouble : 0;
      copy_text(value.kind,sizeof(value.kind),text(card,"kind")); copy_text(value.state,sizeof(value.state),state);
      value.created_at=buffer_deck_number(card,"created_at");
      buffer_deck_metadata(&value,card);
      if(value.bedtime_readable && !deck->bedtime)
        {
          // Normal previews may contain long research notes/presentation text.
          // Do not bless those previews for a cache-only bedtime fallback.
          char gentle[BUFFER_TEXT_SIZE]={0};
          if(!strcmp(value.kind,"question_card"))copy_text(gentle,sizeof(gentle),text(card,"answer"));
          if(strcmp(gentle,value.answer))value.bedtime_readable=false;
        }
      cJSON *action, *actions=cJSON_GetObjectItemCaseSensitive(card,"actions");
      cJSON_ArrayForEach(action,actions)
        if (cJSON_IsString(action) && (!deck->bedtime || !strcmp(action->valuestring,"later") ||
            !strcmp(action->valuestring,"archive") || !strcmp(action->valuestring,"done")) && strlen(action->valuestring)<32 &&
            strlen(value.actions)+strlen(action->valuestring)+2<sizeof(value.actions))
          { if (value.actions[0]) strcat(value.actions,","); strcat(value.actions,action->valuestring); }
      buffer_deck_offer(deck,&value);
      cJSON_Delete(record);
    }
  closedir(dir);
  int count=buffer_deck_finish(deck,cards,maximum);
  free(deck);return count;
}
/* Two passes over bounded local JSON: size first, then write complete UTF-8.
 * No 192-byte projection field participates in detail rendering. */
static void detail_piece(char *out,size_t *length,const char *value)
{
  size_t bytes=strlen(value);
  if (out) memcpy(out+*length,value,bytes);
  *length+=bytes;
}
static void detail_section(char *out,size_t *length,const char *title,const char *body)
{
  if (!body[0]) return;
  if (*length) detail_piece(out,length,"\n\n");
  if (title[0]) { detail_piece(out,length,title);detail_piece(out,length,"\n"); }
  detail_piece(out,length,body);
}
static size_t detail_build(const cJSON *record,char *out,bool include_context)
{
  const cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card");
  size_t length=0;
  detail_section(out,&length,"深挖",text(card,"research_notes"));
  detail_section(out,&length,"回答",text(card,"answer"));
  if (include_context) detail_section(out,&length,"捕获上下文",text(card,"context_summary"));
  const cJSON *blocks=cJSON_GetObjectItemCaseSensitive(
      cJSON_GetObjectItemCaseSensitive(record,"presentation"),"blocks");
  const cJSON *block;
  cJSON_ArrayForEach(block,blocks)
    {
      const char *type=text(block,"type");
      if (!strcmp(type,"paragraph"))
        detail_section(out,&length,text(block,"title"),text(block,"text"));
      else if (!strcmp(type,"list"))
        {
          const cJSON *items=cJSON_GetObjectItemCaseSensitive(block,"items");
          if (!cJSON_IsArray(items) || !cJSON_GetArraySize(items)) continue;
          if (length) detail_piece(out,&length,"\n\n");
          if (text(block,"title")[0]) detail_piece(out,&length,text(block,"title"));
          const cJSON *item;
          cJSON_ArrayForEach(item,items)
            if (cJSON_IsString(item) && item->valuestring[0])
              { detail_piece(out,&length,"\n• ");detail_piece(out,&length,item->valuestring); }
        }
    }
  detail_section(out,&length,length ? "原记录" : "",text(card,"summary"));
  if (out) out[length]=0;
  return length;
}
static char *detail_for(const char *id,bool include_context)
{
  char path[320];
  if (path_for(path,sizeof(path),id)) {errno=EINVAL;return NULL;}
  cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!record)return NULL;
  cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card");
  if (!card || cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted")))
    { errno=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted"))?ENOENT:EINVAL;cJSON_Delete(record);return NULL; }
  size_t length=detail_build(record,NULL,include_context);
  char *detail=length<=BUFFER_AGENT_JSON_BYTES ? malloc(length+1) : NULL;
  if(!detail)errno=length>BUFFER_AGENT_JSON_BYTES?EFBIG:ENOMEM;
  if (detail) detail_build(record,detail,include_context);
  cJSON_Delete(record);
  return detail;
}

char *buffer_replica_detail(const char *id) {return detail_for(id,true);}
char *buffer_replica_model_detail(const char *id) {return detail_for(id,false);}

cJSON *buffer_replica_conversation(const char *id)
{
  char path[320];if (path_for(path,sizeof(path),id)) {errno=EINVAL;return NULL;}
  cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!record)return NULL;
  const cJSON *value=cJSON_GetObjectItemCaseSensitive(record,"conversation");cJSON *turns=NULL;
  if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted"))||!value)errno=ENOENT;
  else if(!cJSON_IsArray(value))errno=EINVAL;
  else {turns=cJSON_Duplicate(value,true);errno=turns?0:ENOMEM;}
  cJSON_Delete(record);return turns;
}

cJSON *buffer_replica_search(const char *query,const char *read_id,const char *current_capture)
{
  cJSON *items=cJSON_CreateArray(); DIR *dir=opendir(REPLICA_DIR);
  if (!items || !dir) { if (dir) closedir(dir); return items; }
  struct dirent *entry; unsigned scanned=0;
  while ((entry=readdir(dir)) && scanned++<REPLICA_LIMIT+2 && cJSON_GetArraySize(items)<12)
    {
      cJSON *record=read_entry(entry->d_name), *card=cJSON_GetObjectItemCaseSensitive(record,"card");
      if (card && !cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted")) &&
          strcmp(text(card,"capture_id"),current_capture) &&
          (read_id ? !strcmp(read_id,text(card,"card_id")) :
           (strstr(text(card,"title"),query)||strstr(text(card,"summary"),query))))
        {
          /* Device sharing does not grant cloud/model access to collected
           * context, including via search_records/read_record tool results. */
          cJSON *item=cJSON_Duplicate(card,true);
          cJSON_DeleteItemFromObjectCaseSensitive(item,"capture_context");
          cJSON_DeleteItemFromObjectCaseSensitive(item,"context_summary");
          cJSON_AddItemToArray(items,item);
        }
      cJSON_Delete(record);
    }
  closedir(dir); return items;
}

static int action_next_for_peer(const char *peer,uint64_t now,char **message)
{
  *message=NULL;
  if (now<action_next) return 0;
  cJSON *event=NULL;
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.action_requested)
    {
      event=cJSON_CreateObject();
      cJSON_AddStringToObject(event,"type","buffer.card.action");
      cJSON_AddNumberToObject(event,"protocol",1);
      cJSON_AddStringToObject(event,"card_id",g_buffer.action_card_id);
      cJSON_AddStringToObject(event,"action_id",g_buffer.action_id);
      cJSON_AddStringToObject(event,"action",g_buffer.action);
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (!event) return 0;
  if(peer){
    char path[320];const char *id=text(event,"card_id");cJSON *record=NULL;
    if(!path_for(path,sizeof(path),id))record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
    bool matches=record&&!strcmp(text(record,"origin"),peer);cJSON_Delete(record);
    if(!matches){cJSON_Delete(event);return 0;}
  }
  *message=cJSON_PrintUnformatted(event); cJSON_Delete(event);
  action_next=now+5000; return *message ? 1 : -ENOMEM;
}
int buffer_replica_action_next(uint64_t now,char **message)
{return action_next_for_peer(NULL,now,message);}
int buffer_replica_action_next_for_peer(const char *peer,uint64_t now,char **message)
{return digest_valid(peer)?action_next_for_peer(peer,now,message):-EINVAL;}
int buffer_replica_action_ack_for_peer(const char *peer,const cJSON *reply)
{
  char path[320];cJSON *record=NULL;
  if(!digest_valid(peer)||path_for(path,sizeof(path),text(reply,"card_id")))return -EINVAL;
  record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  bool matches=record&&!strcmp(text(record,"origin"),peer);cJSON_Delete(record);
  return matches?buffer_replica_action_ack(reply):-EACCES;
}
int buffer_replica_action_ack(const cJSON *reply)
{
  const char *state=text(reply,"state");
  if (strcmp(text(reply,"type"),"buffer.card.action.reply") ||
      (strcmp(state,"applied") && strcmp(state,"absent") && strcmp(state,"rejected"))) return -EINVAL;
  pthread_mutex_lock(&g_buffer.lock);
  int ret=-ESTALE;
  if (g_buffer.action_requested && !strcmp(text(reply,"action_id"),g_buffer.action_id) &&
      !strcmp(text(reply,"card_id"),g_buffer.action_card_id) && !strcmp(text(reply,"action"),g_buffer.action))
    {
      ret=buffer_store_clear_pending_action();
      if(!ret)g_buffer.action_requested=false;
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (!ret && strcmp(state,"applied")) buffer_set_status("手机未执行该操作：%s",state);
  return ret;
}
