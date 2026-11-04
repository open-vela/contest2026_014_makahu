/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>
#include "buffer_agent.h"
#include "buffer_fabric.h"
#include "buffer_replica.h"
#include "buffer_coordinator.h"
#include "buffer_focus.h"
#include "buffer_crdt_sync.h"
#include "buffer_calendar.h"
#include "buffer_agent_input.h"
#include "buffer_entropy.h"
#include "buffer_source_wire.h"
#include "buffer_source_envelope.h"
#ifdef CONFIG_BUFFER_VELA_FABRIC
#include <fabric_ble_host.h>
#include <fabric_pairing_host.h>
#include <fabric_identity_posix.h>
#include <mbedtls/sha256.h>
#include <bluetooth.h>
#include <bt_device.h>
#include <bt_gatts.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <syslog.h>

#define IDENTITY_DIR CONFIG_BUFFER_VELA_DATA_DIR "/fabric"
#define BINDING_FILE CONFIG_BUFFER_VELA_DATA_DIR "/fabric-binding.json"
#define FABRIC_UUID(n) BT_UUID_DECLARE_128(0x43,0x49,0x52,0x42,0x41,0x46,0x8f,0x9e,0x30,0x4a,0x4e,0x6b,n,0,0x6d,0x7f)
static const unsigned char channel[16] = "buffer-channel01";
static const unsigned char phone_port[16] = "buffer-phone-v1!";
static const unsigned char vela_port[16] = "buffer-vela--v1!";
static struct fabric_identity_posix identity = { IDENTITY_DIR };
struct buffer_fabric_sync_state_s
{
  uint64_t sequence;
  unsigned generation;
  bool pending;
  bool complete;
  bool conflict;
  char request_id[33];
  char *message;
  int64_t calendar_start,calendar_end;
};
struct buffer_fabric_mode_state_s
{
  unsigned generation;
  uint64_t retry_at_ms,owner_term;
  char mode[BUFFER_MODE_SIZE];
  char mode_id[BUFFER_ACTION_ID_SIZE];
  char *message;
};
struct buffer_fabric_runtime_state_s
{
  unsigned generation;
  uint64_t revision,owner_term;
  char fingerprint[65];
};
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake = PTHREAD_COND_INITIALIZER;
static pthread_cond_t io_wake = PTHREAD_COND_INITIALIZER;
static pthread_mutex_t status_lock = PTHREAD_MUTEX_INITIALIZER;
static struct buffer_fabric_status_s status_view = {.binding = -EAGAIN, .code = -1};
static uint8_t view_local_id[32], view_peer_id[32];
static bool view_has_binding;
static struct buffer_fabric_status_s status = {.binding = -EAGAIN, .code = -1};
static uint8_t seed[32],local_id[32],bound_public[32],bound_id[32];
static char member_ids[BUFFER_FABRIC_MAX_MEMBERS][65];
static struct buffer_fabric_member_s member_view[BUFFER_FABRIC_MAX_MEMBERS];
static unsigned member_count;
static uint64_t member_revision, add_member_until;
static char persisted_member[65];
static bool add_member_requested;
static bool cancel_member_requested;
static char remove_member_requested[65];
static bool initialized,stopping,started;
static bool retry_requested;
static bool table_reported, table_ready;
static gatts_handle_t service;
static pthread_t executor, app_worker, delivery_worker;
#define FABRIC_LINKS 4
struct packet {size_t length;uint8_t data[512];};
struct fabric_link_s {
  uint8_t peer_public[32],peer_id[32],selector_public[32],admitted_public[32];
  bool occupied,linked,subscribed,notifying,session_trusted,working;
  bool selector_complete,selector_processed,pairing_candidate,view_accepted;
  unsigned generation,host_generation,admitted_generation;
  uint64_t view_revision,pairing_finished;
  size_t selector_received,packet_bytes;
  int confirmation,member_confirmation,code;
  bool active,connected,member_confirmation_pending;
  char pending_member[65];
  bt_address_t address;
  fabric_ble_host *host;
  fabric_pairing_host *pairing;
  struct packet packets[64];unsigned head,count;
  struct packet tx_packet;bool tx_queued,disconnect_requested;
  unsigned char application[12289];size_t application_length;
  unsigned application_generation,reply_generation;
  char *application_reply,*outbox_message;bool outbox_crdt,delivery_preparing;
  struct buffer_fabric_sync_state_s sync_state;
  struct buffer_fabric_mode_state_s mode_state;
  struct buffer_fabric_runtime_state_s runtime_state;
};
static struct fabric_link_s links[FABRIC_LINKS];
static unsigned connection_epoch,application_cursor;
static bool app_busy;
static uint64_t pairing_window;
static unsigned pairing_attempts;
static char delivery_owner[65];static uint64_t delivery_term,delivery_revision;
static bool delivery_reset_pending;
struct source_authority_s {unsigned generation;bool allowed;char peer[65];};
static struct source_authority_s source_authorities[FABRIC_LINKS];
static struct fabric_link_s *link_for_generation(unsigned expected)
{for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].generation==expected&&expected)return &links[i];return NULL;}
static struct fabric_link_s *link_for_address(const bt_address_t *peer)
{for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].occupied&&!bt_addr_compare(peer,&links[i].address))return &links[i];return NULL;}
static struct fabric_link_s *display_link(void)
{
  struct fabric_link_s *selected=&links[0];
  for(unsigned i=0;i<FABRIC_LINKS;i++){
    if(links[i].code>=0&&links[i].pairing)return &links[i];
    if(links[i].member_confirmation_pending)return &links[i];
    if(links[i].active)selected=&links[i];
  }
  return selected;
}
static bool peer_admitted(struct fabric_link_s *link);
static bool current_phone_owner(const char *peer);
static uint64_t milliseconds(void);
static void hex(const uint8_t *bytes,char *text);

/* Executor lock is held. The small authority snapshot has its own terminal
 * lock, so a journal callback never needs to acquire the transport lock. */
static void publish_source_authority(void)
{
  pthread_mutex_lock(&status_lock);
  for(unsigned i=0;i<FABRIC_LINKS;i++){
    source_authorities[i].generation=links[i].generation;
    hex(links[i].peer_id,source_authorities[i].peer);
    source_authorities[i].allowed=links[i].active&&links[i].linked&&!stopping&&peer_admitted(&links[i]);
  }
  pthread_mutex_unlock(&status_lock);
}

/* Called only while holding the executor lock. The view has no authority;
 * all actual operations still check the live binding and connection. */
static void unlock_status(void)
{
  publish_source_authority();
  struct fabric_link_s *link=display_link();
  status.active=false;status.connected=false;
  for(unsigned i=0;i<FABRIC_LINKS;i++){status.active|=links[i].active;status.connected|=links[i].connected;}
  status.code=link->code;status.member_confirmation=link->member_confirmation_pending;
  memcpy(status.pending_member,link->pending_member,sizeof(status.pending_member));
  bool sync_seen=false,sync_complete=true;
  status.sync_pending=status.sync_conflict=false;
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].active&&links[i].linked&&
      links[i].sync_state.generation==links[i].generation&&
      (links[i].sync_state.pending||links[i].sync_state.complete||links[i].sync_state.conflict)){
    sync_seen=true;status.sync_pending|=links[i].sync_state.pending;status.sync_conflict|=links[i].sync_state.conflict;
    sync_complete=sync_complete&&links[i].sync_state.complete;
  }
  status.sync_complete=sync_seen&&sync_complete&&!status.sync_pending&&!status.sync_conflict;
  status.member_count = member_count;
  status.member_revision = member_revision;
  status.member_generation = link->generation;
  status.adding_member = add_member_until && milliseconds() < add_member_until;
  pthread_mutex_lock(&status_lock);
  status_view = status;
  for (unsigned i=0;i<member_count;i++) {
    memcpy(member_view[i].device_id,member_ids[i],65);
    member_view[i].selected=false;
    for(unsigned j=0;j<FABRIC_LINKS;j++)if(links[j].active&&links[j].linked){
      char selected[65];hex(links[j].peer_id,selected);if(!strcmp(selected,member_ids[i]))member_view[i].selected=true;
    }
  }
  view_has_binding = initialized && !stopping && status.binding == 0;
  if (view_has_binding)
    {
      memcpy(view_local_id, local_id, sizeof(view_local_id));
      memcpy(view_peer_id,bound_id,sizeof(view_peer_id));
    }
  else
    {
      memset(view_local_id, 0, sizeof(view_local_id));
      memset(view_peer_id, 0, sizeof(view_peer_id));
    }
  pthread_mutex_unlock(&status_lock);
  pthread_mutex_unlock(&lock);
}

static uint64_t milliseconds(void)
{ struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;
  return (uint64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000; }
static int random_bytes(void *context, uint8_t *out, size_t length)
{
  (void)context;
  return buffer_entropy_read(out, length);
}
static void hex(const uint8_t *bytes, char *text)
{ for (unsigned i = 0; i < 32; i++) snprintf(text + i * 2, 3, "%02x", bytes[i]); }
static int unhex(const char *text, uint8_t *bytes)
{
  if (!text || strlen(text) != 64) return -EINVAL;
  for (unsigned i = 0; i < 32; i++) { char a = text[i*2], b = text[i*2+1];
    if (!((a >= '0' && a <= '9') || (a >= 'a' && a <= 'f')) ||
        !((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f'))) return -EINVAL;
    bytes[i] = (a <= '9' ? a-'0' : a-'a'+10)*16 + (b <= '9' ? b-'0' : b-'a'+10); }
  return 0;
}

static bool member_contains(const char *id)
{
  for (unsigned i=0;i<member_count;i++) if (!strcmp(member_ids[i],id)) return true;
  return false;
}

/* The Fabric trust backend owns keys. This app ledger owns only approved
 * public DeviceIds and a monotonic roster revision; no credentials are copied. */
static int save_members(const char *selected, const char ids[][65], unsigned size, uint64_t revision)
{
  if (size>BUFFER_FABRIC_MAX_MEMBERS || !revision || revision>9007199254740991ULL) return -EINVAL;
  cJSON *value=cJSON_CreateObject(), *members=cJSON_CreateArray();
  if (!value || !members) { cJSON_Delete(value); cJSON_Delete(members); return -ENOMEM; }
  if (!cJSON_AddNumberToObject(value,"version",2) || !cJSON_AddNumberToObject(value,"revision",(double)revision) ||
      !cJSON_AddStringToObject(value,"peer_device_id",selected)) { cJSON_Delete(value); cJSON_Delete(members); return -ENOMEM; }
  cJSON_AddItemToObject(value,"member_device_ids",members);
  for (unsigned i=0;i<size;i++) {
    cJSON *id=cJSON_CreateString(ids[i]);
    if (!id) { cJSON_Delete(value); return -ENOMEM; }
    cJSON_AddItemToArray(members,id);
  }
  int result=buffer_agent_atomic_json(BINDING_FILE,value); cJSON_Delete(value);
  if (!result) snprintf(persisted_member,sizeof(persisted_member),"%s",selected);
  return result;
}

static int add_member_binding(struct fabric_link_s *link,const uint8_t id[32],const uint8_t public_key[32])
{
  char encoded[65]; hex(id,encoded);
  bool exists=member_contains(encoded);
  if (!memcmp(id,local_id,32)) return -EINVAL;
  if (!exists && member_count==BUFFER_FABRIC_MAX_MEMBERS) return -ENOSPC;
  if (member_revision>=9007199254740991ULL) return -EOVERFLOW;
  char next[BUFFER_FABRIC_MAX_MEMBERS][65]; memcpy(next,member_ids,sizeof(next));
  unsigned size=member_count;
  if (!exists) memcpy(next[size++],encoded,65);
  uint64_t revision=member_revision+1;
  char anchor[65];snprintf(anchor,sizeof(anchor),"%s",member_contains(persisted_member)?persisted_member:encoded);
  int result=save_members(anchor,next,size,revision);
  if(!result){
    memcpy(member_ids,next,sizeof(next));member_count=size;member_revision=revision;
    if(!memcmp(bound_id,(uint8_t[32]){0},32)){memcpy(bound_id,id,32);memcpy(bound_public,public_key,32);}
    /* A new roster requires every prior controller to acknowledge it anew. */
    for(unsigned i=0;i<FABRIC_LINKS;i++)if(&links[i]!=link){
      links[i].linked=false;links[i].view_accepted=false;links[i].session_trusted=false;
      char old[65];hex(links[i].peer_id,old);buffer_coordinator_peer_connected(old,false);
    }
    publish_source_authority();pthread_cond_signal(&wake);
  }
  return result;
}

size_t buffer_fabric_members(struct buffer_fabric_member_s *out,size_t capacity)
{
  if (!out || !capacity) return 0;
  pthread_mutex_lock(&status_lock);
  size_t size=status_view.member_count<capacity?status_view.member_count:capacity;
  memcpy(out,member_view,size*sizeof(*out)); pthread_mutex_unlock(&status_lock); return size;
}

int buffer_fabric_add_member(void)
{
  pthread_mutex_lock(&lock);
  int result=!initialized?-EAGAIN:member_count>=BUFFER_FABRIC_MAX_MEMBERS?-ENOSPC:0;
  if (!result) { add_member_requested=true; pthread_cond_signal(&io_wake); }
  unlock_status(); return result;
}

void buffer_fabric_cancel_add_member(void)
{
  pthread_mutex_lock(&lock);cancel_member_requested=true;pthread_cond_signal(&io_wake);unlock_status();
}

int buffer_fabric_remove_member(const char *id)
{
  uint8_t decoded[32]; if (unhex(id,decoded)) return -EINVAL;
  pthread_mutex_lock(&lock);
  int result=!member_contains(id)?-ENOENT:remove_member_requested[0]?-EBUSY:0;
  if (!result) { memcpy(remove_member_requested,id,65); pthread_cond_signal(&io_wake); }
  unlock_status(); return result;
}

void buffer_fabric_confirm_member(const char *id,int code,unsigned expected,bool accepted)
{
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  if (link && id && !strcmp(id,link->pending_member)) {
    if (link->member_confirmation_pending && code==-1) link->member_confirmation=accepted?1:-1;
    else if (link->pairing && code>=0 && code==link->code) link->confirmation=accepted?1:-1;
  }
  pthread_cond_signal(&io_wake); unlock_status();
}
static bool sync_id_valid(const char *value)
{
  if (!value || strlen(value) != 32) return false;
  for (size_t i = 0; i < 32; i++)
    if (!((value[i] >= '0' && value[i] <= '9') ||
          (value[i] >= 'a' && value[i] <= 'f'))) return false;
  return true;
}
static char *sync_request_json(const char *request_id)
{
  cJSON *request;
  char *message;
  if (!sync_id_valid(request_id)) return NULL;
  request = cJSON_CreateObject();
  if (!request) return NULL;
  if (!cJSON_AddStringToObject(request, "type", "buffer.sync.request") ||
      !cJSON_AddNumberToObject(request, "protocol", 1) ||
      !cJSON_AddStringToObject(request, "request_id", request_id))
    {
      cJSON_Delete(request);
      return NULL;
    }
  int64_t start=0,end=0;
  if (buffer_calendar_range(&start,&end)) {
      cJSON *range=cJSON_AddObjectToObject(request,"calendar_range");
      cJSON_AddNumberToObject(range,"start",(double)start);cJSON_AddNumberToObject(range,"end",(double)end);
      cJSON_AddNumberToObject(range,"series",1);
  }
  message = cJSON_PrintUnformatted(request);
  cJSON_Delete(request);
  return message;
}
static int sync_request_begin(struct buffer_fabric_sync_state_s *sync,
                              unsigned current_generation)
{
  if (!sync || !current_generation) return -EINVAL;
  if (sync->pending)
    {
      if (sync->generation != current_generation) return -ESTALE;
      int64_t start=0,end=0;(void)buffer_calendar_range(&start,&end);
      if(start==sync->calendar_start&&end==sync->calendar_end){
        if (!sync->message) sync->message = sync_request_json(sync->request_id);
        return sync->message ? 0 : -ENOMEM;
      }
      free(sync->message);sync->message=NULL;
    }
  if (sync->sequence == UINT64_MAX) return -EOVERFLOW;
  uint64_t sequence = sync->sequence + 1;
  char request_id[33];
  snprintf(request_id, sizeof(request_id), "%032llx",
           (unsigned long long)sequence);
  char *message = sync_request_json(request_id);
  if (!message) return -ENOMEM;
  sync->sequence = sequence;
  sync->generation = current_generation;
  sync->pending = true;
  sync->complete = false;
  sync->conflict = false;
  memcpy(sync->request_id, request_id, sizeof(request_id));
  sync->message = message;
  sync->calendar_start=sync->calendar_end=0;
  (void)buffer_calendar_range(&sync->calendar_start,&sync->calendar_end);
  return 0;
}
static int sync_reply_apply(struct buffer_fabric_sync_state_s *sync,
                            unsigned current_generation, bool active,
                            const cJSON *reply)
{
  if (!sync || !cJSON_IsObject(reply)) return -EINVAL;
  const cJSON *protocol = cJSON_GetObjectItemCaseSensitive(reply, "protocol");
  const cJSON *type_value = cJSON_GetObjectItemCaseSensitive(reply, "type");
  const cJSON *id_value = cJSON_GetObjectItemCaseSensitive(reply, "request_id");
  const cJSON *state_value = cJSON_GetObjectItemCaseSensitive(reply, "state");
  const char *type = cJSON_IsString(type_value) ? type_value->valuestring : "";
  const char *request_id = cJSON_IsString(id_value) ? id_value->valuestring : "";
  const char *result = cJSON_IsString(state_value) ? state_value->valuestring : "";
  if (strcmp(type, "buffer.sync.reply") || !cJSON_IsNumber(protocol) ||
      protocol->valuedouble != 1 || !sync_id_valid(request_id) ||
      (strcmp(result, "complete") && strcmp(result, "conflict"))) return -EINVAL;
  if (!active || !current_generation || sync->generation != current_generation ||
      strcmp(request_id, sync->request_id)) return -ESTALE;
  bool complete = !strcmp(result, "complete");
  bool conflict = !strcmp(result, "conflict");
  if (!sync->pending)
    return sync->complete == complete && sync->conflict == conflict ? 0 : -ESTALE;
  sync->pending = false;
  sync->complete = complete;
  sync->conflict = conflict;
  free(sync->message);
  sync->message = NULL;
  return 1;
}
static void sync_reset_session(struct buffer_fabric_sync_state_s *sync)
{
  if (!sync) return;
  free(sync->message);
  sync->generation = 0;
  sync->pending = false;
  sync->complete = false;
  sync->conflict = false;
  sync->request_id[0] = '\0';
  sync->message = NULL;
}
static bool runtime_mode_valid(const char *mode)
{
  return mode && (!strcmp(mode, "normal") || !strcmp(mode, "focus") ||
                  !strcmp(mode, "bedtime") || !strcmp(mode, "rabbit_hole"));
}
static bool mode_id_valid(const char *mode_id)
{
  if (!mode_id) return false;
  size_t length = strlen(mode_id);
  if (!length || length >= BUFFER_ACTION_ID_SIZE) return false;
  for (size_t i = 0; i < length; i++)
    {
      unsigned char ch = (unsigned char)mode_id[i];
      if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
            (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-'))
        return false;
    }
  return true;
}
static char *mode_change_json(const char *mode,const char *mode_id,uint64_t owner_term)
{
  cJSON *request;
  char *message;
  if (!runtime_mode_valid(mode) || !mode_id_valid(mode_id)) return NULL;
  request = cJSON_CreateObject();
  if (!request) return NULL;
  if (!cJSON_AddStringToObject(request, "type", "buffer.mode.change") ||
      !cJSON_AddNumberToObject(request, "protocol", 1) ||
      !cJSON_AddStringToObject(request, "mode", mode) ||
      !cJSON_AddStringToObject(request, "mode_id", mode_id)||!cJSON_AddNumberToObject(request,"coordinator_term",(double)owner_term))
    {
      cJSON_Delete(request);
      return NULL;
    }
  cJSON *focus=buffer_focus_session_snapshot();
  if(!focus&&errno){cJSON_Delete(request);return NULL;}
  if (focus && !buffer_focus_validate(focus,mode)) {
      if(!cJSON_AddItemToObject(request,"focus_session",focus)){cJSON_Delete(focus);cJSON_Delete(request);return NULL;}
  }
  else cJSON_Delete(focus);
  message = cJSON_PrintUnformatted(request);
  cJSON_Delete(request);
  return message;
}
static int mode_reply_validate(const cJSON *reply, char mode[BUFFER_MODE_SIZE],
                               char mode_id[BUFFER_ACTION_ID_SIZE],uint64_t *owner_term)
{
  const cJSON *type;
  const cJSON *protocol;
  const cJSON *mode_value;
  const cJSON *id_value;
  const cJSON *state_value;
  if (!cJSON_IsObject(reply) || cJSON_GetArraySize(reply) != 6) return -EINVAL;
  type = cJSON_GetObjectItemCaseSensitive(reply, "type");
  protocol = cJSON_GetObjectItemCaseSensitive(reply, "protocol");
  mode_value = cJSON_GetObjectItemCaseSensitive(reply, "mode");
  id_value = cJSON_GetObjectItemCaseSensitive(reply, "mode_id");
  state_value = cJSON_GetObjectItemCaseSensitive(reply, "state");
  if (!cJSON_IsString(type) || strcmp(type->valuestring, "buffer.mode.reply") ||
      !cJSON_IsNumber(protocol) || protocol->valuedouble != 1 ||
      !cJSON_IsString(mode_value) || !runtime_mode_valid(mode_value->valuestring) ||
      !cJSON_IsString(id_value) || !mode_id_valid(id_value->valuestring) ||
      !cJSON_IsString(state_value) || strcmp(state_value->valuestring, "applied"))
    return -EINVAL;
  const cJSON *term=cJSON_GetObjectItemCaseSensitive(reply,"coordinator_term");
  if(!cJSON_IsNumber(term)||!isfinite(term->valuedouble)||term->valuedouble<1||term->valuedouble>9007199254740991.0||floor(term->valuedouble)!=term->valuedouble)return -EINVAL;
  *owner_term=(uint64_t)term->valuedouble;
  snprintf(mode, BUFFER_MODE_SIZE, "%s", mode_value->valuestring);
  snprintf(mode_id, BUFFER_ACTION_ID_SIZE, "%s", id_value->valuestring);
  return 0;
}
static void mode_reset_session(struct buffer_fabric_mode_state_s *mode)
{
  if (!mode) return;
  free(mode->message);
  memset(mode, 0, sizeof(*mode));
}
static bool mode_refresh(struct buffer_fabric_mode_state_s *state,
                         unsigned current_generation,uint64_t owner_term,uint64_t now)
{
  if(!owner_term){mode_reset_session(state);return false;}
  char mode[BUFFER_MODE_SIZE] = {0};
  char mode_id[BUFFER_ACTION_ID_SIZE] = {0};
  bool dirty;
  pthread_mutex_lock(&g_buffer.lock);
  dirty = g_buffer.mode_dirty;
  if (dirty)
    {
      snprintf(mode, sizeof(mode), "%s", g_buffer.mode);
      snprintf(mode_id, sizeof(mode_id), "%s", g_buffer.mode_id);
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (!dirty)
    {
      mode_reset_session(state);
      return false;
    }
  if (!runtime_mode_valid(mode) || !mode_id_valid(mode_id)) return false;
  if (state->generation != current_generation ||state->owner_term!=owner_term||
      strcmp(state->mode, mode) || strcmp(state->mode_id, mode_id))
    {
      char *message = mode_change_json(mode,mode_id,owner_term);
      if (!message) return false;
      free(state->message);
      state->message = message;
      state->generation = current_generation;state->owner_term=owner_term;
      state->retry_at_ms = 0;
      snprintf(state->mode, sizeof(state->mode), "%s", mode);
      snprintf(state->mode_id, sizeof(state->mode_id), "%s", mode_id);
    }
  return state->message && now >= state->retry_at_ms;
}
static int runtime_revision_check(struct buffer_fabric_runtime_state_s *state,
                                  unsigned current_generation, bool active,
                                  uint64_t revision,uint64_t owner_term,const char *fingerprint)
{
  if (!state || !current_generation || !revision) return -EINVAL;
  if (!active) return -ESTALE;
  if(state->generation!=current_generation||state->owner_term!=owner_term){
    state->generation=current_generation;state->owner_term=owner_term;state->revision=0;state->fingerprint[0]=0;
  }
  if(revision==state->revision&&strcmp(state->fingerprint,fingerprint))return -EEXIST;
  if (revision < state->revision) return -ESTALE;
  return revision == state->revision && state->revision ? 1 : 0;
}
static int runtime_revision_commit(struct buffer_fabric_runtime_state_s *state,
                                   unsigned current_generation, bool active,
                                   uint64_t revision,uint64_t owner_term,const char *fingerprint)
{
  if (!state || !active || !current_generation || !revision ||
      state->generation != current_generation ||state->owner_term!=owner_term|| revision <= state->revision)
    return -ESTALE;
  state->revision = revision;snprintf(state->fingerprint,sizeof(state->fingerprint),"%s",fingerprint);
  return 0;
}
static void runtime_reset_session(struct buffer_fabric_runtime_state_s *state)
{
  if (state) memset(state, 0, sizeof(*state));
}
static char *runtime_reply_json(uint64_t revision,const char *state,uint64_t owner_term)
{
  cJSON *reply;
  char *message;
  if (!revision || revision > 9007199254740991ULL ||
      (strcmp(state, "applied") && strcmp(state, "deferred"))) return NULL;
  reply = cJSON_CreateObject();
  if (!reply) return NULL;
  if (!cJSON_AddStringToObject(reply, "type", "buffer.runtime.reply") ||
      !cJSON_AddNumberToObject(reply, "protocol", 1) ||
      !cJSON_AddNumberToObject(reply, "revision", (double)revision)||!cJSON_AddNumberToObject(reply,"coordinator_term",(double)owner_term) ||
      !cJSON_AddStringToObject(reply, "state", state))
    {
      cJSON_Delete(reply);
      return NULL;
    }
  message = cJSON_PrintUnformatted(reply);
  cJSON_Delete(reply);
  return message;
}
/* Trust writes are owned by this executor. Admission reads the canonical
 * backend once per host, while every operation retains live generation and
 * policy checks. Writers invalidate before changing durable trust. */
static void invalidate_admission(struct fabric_link_s *link)
{ link->session_trusted=false; memset(link->admitted_public,0,sizeof(link->admitted_public)); publish_source_authority(); }
static bool peer_admitted(struct fabric_link_s *link)
{ return !status.binding && link->session_trusted && link->admitted_generation==link->generation &&
    !memcmp(link->admitted_public,link->peer_public,sizeof(link->admitted_public)); }
static bool admit_peer(struct fabric_link_s *link)
{
  invalidate_admission(link);
  if (!link->linked || stopping || status.binding ||
      fabric_identity_posix_trusted(&identity,link->peer_public)!=1) return false;
  char selected[65];hex(link->peer_id,selected);
  if (!member_contains(selected)) return false;
  memcpy(link->admitted_public,link->peer_public,sizeof(link->admitted_public));
  link->admitted_generation=link->generation; link->session_trusted=true; return true;
}
int buffer_fabric_request_sync(void)
{
  pthread_mutex_lock(&lock);int ret=-ENOTCONN;bool requested=false;
  if(!initialized)ret=-EAGAIN;
  else if(status.binding)ret=status.binding;
  else for(unsigned i=0;i<FABRIC_LINKS;i++){
    struct fabric_link_s *link=&links[i];
    if(!link->active||!link->linked||!peer_admitted(link))continue;
    int result=sync_request_begin(&link->sync_state,link->generation);
    if(!result)requested=true;else if(!requested)ret=result;
  }
  if(requested){ret=0;pthread_cond_signal(&io_wake);}
  unlock_status();return ret;
}
int buffer_fabric_init(void)
{
  pthread_mutex_lock(&lock);
  if (initialized) { int result = status.binding; unlock_status(); return result == -ENOENT ? 0 : result; }
  int ret = buffer_agent_store_init();
  if (!ret) ret = fabric_identity_posix_load(&identity, random_bytes, NULL, seed) ? -EACCES : 0;
  uint8_t public[32];
  if (!ret && fabric_identity_public(seed, public, local_id)) ret = -EIO;
  if (!ret)
    {
      cJSON *binding = buffer_agent_read_json(BINDING_FILE, 8192);
      if (!binding)
        {
          ret = errno == ENOENT ? -ENOENT : -EIO;
          /* An incomplete binding after a trust commit must never reopen claim. */
          struct stat st;
          if (ret == -ENOENT && !stat(IDENTITY_DIR "/trusted-devices.txt", &st) && st.st_size) ret = -EIO;
        }
      else
        {
          cJSON *id = cJSON_GetObjectItemCaseSensitive(binding, "peer_device_id");
          cJSON *members=cJSON_GetObjectItemCaseSensitive(binding,"member_device_ids");
          if (!cJSON_IsObject(binding) || !cJSON_IsString(id)) ret=-EINVAL;
          else if (!members) {
            if (unhex(id->valuestring,bound_id) || fabric_identity_posix_peer(&identity,bound_id,bound_public)) ret=-EACCES;
            else { member_count=1;memcpy(member_ids[0],id->valuestring,65);member_revision=0; }
          } else {
            cJSON *version=cJSON_GetObjectItemCaseSensitive(binding,"version"), *revision=cJSON_GetObjectItemCaseSensitive(binding,"revision");
            if (cJSON_GetArraySize(binding)!=4 || !cJSON_IsNumber(version) || version->valuedouble!=2 || !cJSON_IsArray(members) ||
                cJSON_GetArraySize(members)>BUFFER_FABRIC_MAX_MEMBERS || !cJSON_IsNumber(revision) ||
                revision->valuedouble<1 || revision->valuedouble>9007199254740991.0 || floor(revision->valuedouble)!=revision->valuedouble) ret=-EINVAL;
            else {
              member_count=0;member_revision=(uint64_t)revision->valuedouble;
              const cJSON *entry;
              cJSON_ArrayForEach(entry,members) {
                uint8_t key[32], public_key[32];
                if (!cJSON_IsString(entry) || unhex(entry->valuestring,key) || !memcmp(key,local_id,32) || member_contains(entry->valuestring) ||
                    fabric_identity_posix_peer(&identity,key,public_key)) { ret=-EACCES;break; }
                memcpy(member_ids[member_count++],entry->valuestring,65);
              }
              if (!ret) {
                if (!member_count && !id->valuestring[0]) ret=-ENOENT;
                else if (!member_contains(id->valuestring) || unhex(id->valuestring,bound_id) ||
                    fabric_identity_posix_peer(&identity,bound_id,bound_public)) ret=-EACCES;
              }
            }
          }
          cJSON_Delete(binding);
          if (!ret) { hex(bound_id,persisted_member); }
        }
    }
  if (ret && ret!=-ENOENT) { member_count=0;member_revision=0; }
  status.binding = ret; status.error = ret == -ENOENT ? 0 : ret;
  if (!ret)
    {
      char local[65], peer[65]; hex(local_id,local); hex(bound_id,peer);
      int coordinator=buffer_coordinator_bind(local,peer);
      if (coordinator) status.error=coordinator;
    }
  for(unsigned i=0;i<FABRIC_LINKS;i++){links[i].code=-1;links[i].packet_bytes=20;}
  initialized = ret == 0 || ret == -ENOENT;
  unlock_status(); return ret == -ENOENT ? 0 : ret;
}
void buffer_fabric_report_error(int error)
{ pthread_mutex_lock(&lock); status.error=error; unlock_status(); }
void buffer_fabric_request_retry(void)
{ pthread_mutex_lock(&lock); retry_requested=true; unlock_status(); }
bool buffer_fabric_take_retry(void)
{ pthread_mutex_lock(&lock); bool value=retry_requested; retry_requested=false; unlock_status(); return value; }
void buffer_fabric_snapshot(struct buffer_fabric_status_s *out)
{
  /* UI reads a published view, never the executor's disk/radio lock. */
  pthread_mutex_lock(&status_lock);
  *out = status_view;
  pthread_mutex_unlock(&status_lock);
}
int buffer_fabric_identity_pair(char local[65], char peer[65])
{
  if (!local || !peer) return -EINVAL;
  local[0] = peer[0] = '\0';
  pthread_mutex_lock(&status_lock);
  bool bound = view_has_binding;
  if (bound)
    {
      hex(view_local_id, local);
      hex(view_peer_id, peer);
    }
  pthread_mutex_unlock(&status_lock);
  return bound ? 0 : -ENOENT;
}

int buffer_fabric_identity_for_peer(const char *peer,char local[65])
{
  if (!peer || !local)
    return -EINVAL;
  local[0] = 0;
  bool allowed = false;
  pthread_mutex_lock(&status_lock);
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(source_authorities[i].allowed&&!strcmp(source_authorities[i].peer,peer))allowed=true;
  if(allowed)hex(view_local_id,local);
  pthread_mutex_unlock(&status_lock);return allowed?0:-EACCES;
}

int buffer_fabric_qr_locator(char *locator, size_t locator_size)
{
  uint8_t public_key[32];
  uint8_t derived_id[32];
  char encoded[65];
  int ret;

  if (locator == NULL || locator_size == 0) return -EINVAL;
  locator[0] = '\0';
  ret = buffer_fabric_init();
  if (ret < 0) return ret;

  pthread_mutex_lock(&lock);
  if (status.binding != -ENOENT)
    ret = -EACCES;
  else if (fabric_identity_public(seed, public_key, derived_id) != 0 ||
           memcmp(derived_id, local_id, sizeof(derived_id)) != 0)
    ret = -EIO;
  else
    {
      hex(public_key, encoded);
      int written = snprintf(locator, locator_size,
                            "vela-fabric://pair/v1/%s", encoded);
      ret = written < 0 || (size_t)written >= locator_size ? -ENOSPC : 0;
    }
  unlock_status();

  memset(public_key, 0, sizeof(public_key));
  memset(derived_id, 0, sizeof(derived_id));
  memset(encoded, 0, sizeof(encoded));
  if (ret < 0) locator[0] = '\0';
  return ret;
}
void buffer_fabric_confirm(bool accepted)
{ pthread_mutex_lock(&lock); struct fabric_link_s *link=display_link(); if (link->pairing && link->code >= 0) link->confirmation = accepted ? 1 : -1;
  pthread_cond_signal(&io_wake); unlock_status(); }
void buffer_fabric_confirm_epoch(int code,unsigned expected,bool accepted)
{
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  if (link && link->pairing && code>=0 && link->code==code) link->confirmation=accepted?1:-1;
  pthread_cond_signal(&io_wake);unlock_status();
}
static int32_t persist_peer(void *context, const uint8_t public[32], const uint8_t id[32])
{
  struct fabric_link_s *link=context;
  invalidate_admission(link);
  if (!fabric_identity_posix_persist_peer(&identity, public, id)) return 0;
  int ret = add_member_binding(link,id,public);
  if (ret) { status.binding = ret; status.error = ret; return 0; }
  memcpy(link->peer_public, public, 32); memcpy(link->peer_id, id, 32);
  status.binding = 0; link->code = -1;
  link->member_confirmation_pending=false; link->pending_member[0]=0;
  char local[65], peer[65]; hex(local_id,local); hex(link->peer_id,peer);
  if (buffer_coordinator_bind_view(local,peer,member_ids,member_count,member_revision)) { status.error=-EIO; return 0; }
  return 1;
}
static void participant(const uint8_t id[32], uint8_t out[16])
{
  uint8_t input[42], digest[32]; memcpy(input, id, 32); memcpy(input + 32, "app.buffer", 10);
  if (mbedtls_sha256(input, sizeof(input), digest, 0)) memset(digest, 0, 32);
  memcpy(out, digest, 16);
}
static int32_t policy(void *context, const uint8_t plan[140], uint8_t action)
{
  struct fabric_link_s *link=context;
  if (!link->linked || stopping || status.binding || action > 2 ||
      !peer_admitted(link)) return 0;
  uint8_t remote[16], local[16]; participant(link->peer_id, remote); participant(local_id, local);
  static const uint8_t epoch[8] = {0,0,0,0,0,0,0,1};
  unsigned maximum = ((unsigned)plan[136]<<24)|((unsigned)plan[137]<<16)|((unsigned)plan[138]<<8)|plan[139];
  return maximum >= 1024 && maximum <= 12288 && !memcmp(plan+16,epoch,8) &&
    !memcmp(plan+24,channel,16) && !memcmp(plan+40,remote,16) && !memcmp(plan+56,local,16) &&
    !memcmp(plan+72,phone_port,16) && !memcmp(plan+88,vela_port,16);
}
static int send_packet(void *context, const uint8_t *bytes, size_t length)
{
  struct fabric_link_s *link=context;
  if(!link->linked||!link->subscribed||link->notifying||link->tx_queued||length>link->packet_bytes||length>sizeof(link->tx_packet.data))return FABRIC_BLE_BUSY;
  memcpy(link->tx_packet.data,bytes,length);link->tx_packet.length=length;
  link->tx_queued=true;link->notifying=true;
  return 0;
}
/* Bluetooth socket RPC can wait for the same callback worker that needs
 * lock. Stage one opaque frame under lock, submit it with lock released.
 * A synchronous completion owns notifying; do not restore it after return. */
static void radio_flush_one(unsigned slot)
{
  uint8_t packet[512];size_t length=0;bt_address_t address;void *handle;
  unsigned generation;bool disconnect;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=&links[slot];
  if(stopping||!service){unlock_status();return;}
  disconnect=link->disconnect_requested;
  if(!disconnect&&(!link->tx_queued||!link->linked||!link->subscribed)){unlock_status();return;}
  generation=link->generation;address=link->address;handle=service;
  if(disconnect)link->disconnect_requested=false;
  else{length=link->tx_packet.length;memcpy(packet,link->tx_packet.data,length);link->notifying=true;}
  unlock_status();
  int result=disconnect?bt_gatts_disconnect(handle,&address):bt_gatts_notify(handle,&address,3,packet,length);
  pthread_mutex_lock(&lock);link=&links[slot];
  if(link->generation==generation){
    if(disconnect){if(result!=BT_STATUS_SUCCESS&&link->occupied)link->disconnect_requested=true;}
    else if(result==BT_STATUS_SUCCESS||!link->linked){
      memset(link->tx_packet.data,0,link->tx_packet.length);link->tx_packet.length=0;link->tx_queued=false;
    }else link->notifying=false; /* Exact packet remains queued for retry. */
  }
  unlock_status();
}
static void coordinator_disconnect(struct fabric_link_s *link)
{
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(&links[i]!=link&&links[i].active&&links[i].linked&&
      links[i].view_accepted&&peer_admitted(&links[i])&&!memcmp(links[i].peer_id,link->peer_id,32))return;
  char peer[65];hex(link->peer_id,peer);buffer_coordinator_peer_connected(peer,false);
}
static void clear_hosts(struct fabric_link_s *link)
{
  invalidate_admission(link);
  sync_reset_session(&link->sync_state);
  mode_reset_session(&link->mode_state);
  runtime_reset_session(&link->runtime_state);
  coordinator_disconnect(link);
  link->view_accepted=false;
  /* CRDT cleanup runs on the application worker, outside the transport lock. */
  fabric_ble_host_destroy(link->host); link->host = NULL;
  fabric_pairing_host_destroy(link->pairing); link->pairing = NULL;
  link->active = false; link->code = -1; link->confirmation = 0;
  link->tx_queued=false;link->tx_packet.length=0;link->notifying=false;
  link->member_confirmation_pending=false;link->pending_member[0]=0;link->member_confirmation=0;
  link->pairing_finished = 0;
  if(link->outbox_message&&link->outbox_crdt)memset(link->outbox_message,0,strlen(link->outbox_message));
  free(link->outbox_message); link->outbox_message=NULL;link->outbox_crdt=false;
  link->application_length=0;
  free(link->application_reply);link->application_reply=NULL;
  pthread_cond_signal(&wake);
}

/* Runs on the transport executor with lock held. A selector is only a public
 * routing hint: the normal Fabric handshake still proves private-key ownership
 * and the exact app/port policy before a single application byte is admitted. */
static void select_member(struct fabric_link_s *link)
{
  if (!link->selector_complete || link->selector_processed) return;
  uint8_t id[32];
  if (mbedtls_sha256(link->selector_public,32,id,0)) { status.error=-EIO;link->linked=false;return; }
  char encoded[65];hex(id,encoded);
  bool allowed=member_contains(encoded);
  bool trusted=fabric_identity_posix_trusted(&identity,link->selector_public)==1;
  bool adding=add_member_until && milliseconds()<add_member_until;
  if (allowed && trusted) {
    memcpy(link->peer_public,link->selector_public,32);memcpy(link->peer_id,id,32);
    link->pairing_candidate=false;link->selector_processed=true;
  } else if ((!member_count || adding) && member_count<BUFFER_FABRIC_MAX_MEMBERS) {
    memcpy(link->pending_member,encoded,65);
    if (trusted) {
      link->member_confirmation_pending=true;
      if (!link->member_confirmation) return;
      bool accepted=link->member_confirmation>0;link->member_confirmation=0;link->member_confirmation_pending=false;
      if (!accepted) { status.error=-EACCES;link->linked=false;return; }
      int result=add_member_binding(link,id,link->selector_public);
      if (result) { status.error=result;link->linked=false;return; }
      memcpy(link->peer_public,link->selector_public,32);memcpy(link->peer_id,id,32);
      status.binding=0;link->pairing_candidate=false;link->selector_processed=true;
    } else { link->pairing_candidate=true;link->selector_processed=true; }
  } else { status.error=-EACCES;link->linked=false; }
}

static void member_actions(void)
{
  if(cancel_member_requested){
    cancel_member_requested=false;add_member_requested=false;add_member_until=0;
    for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].pairing||links[i].member_confirmation_pending){links[i].linked=false;invalidate_admission(&links[i]);}
  }
  if(add_member_requested){add_member_requested=false;add_member_until=milliseconds()+120000;}
  if(remove_member_requested[0]){
    char next[BUFFER_FABRIC_MAX_MEMBERS][65];unsigned size=0;
    for(unsigned i=0;i<member_count;i++)if(strcmp(member_ids[i],remove_member_requested))memcpy(next[size++],member_ids[i],65);
    if(size!=member_count){
      for(unsigned i=0;i<FABRIC_LINKS;i++){
        links[i].linked=false;invalidate_admission(&links[i]);
        char id[65];hex(links[i].peer_id,id);buffer_coordinator_peer_connected(id,false);
      }
      char selected[65];snprintf(selected,sizeof(selected),"%s",size?next[0]:"");
      uint64_t revision=member_revision+1;
      int result=revision>9007199254740991ULL?-EOVERFLOW:save_members(selected,next,size,revision);
      if(!result){
        memset(member_ids,0,sizeof(member_ids));memcpy(member_ids,next,size*65);member_count=size;member_revision=revision;
        memset(bound_id,0,32);memset(bound_public,0,32);
        if(!size)status.binding=-ENOENT;
        else if(unhex(selected,bound_id)||fabric_identity_posix_peer(&identity,bound_id,bound_public))status.binding=-EACCES;
        else status.binding=0;
        status.error=status.binding==-ENOENT?0:status.binding;
      }else{status.binding=result;status.error=result;}
    }
    remove_member_requested[0]=0;pthread_cond_signal(&wake);
  }
}
static int app_authorize(void *context, enum buffer_oobe_operation_e operation)
{
  (void)operation; unsigned expected = *(unsigned *)context;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  bool allowed = link && link->active && link->linked && !stopping && link->generation == expected &&
                 peer_admitted(link);
  unlock_status(); return allowed ? 0 : -EACCES;
}
static int source_authorize(void *context,enum buffer_oobe_operation_e operation)
{
  (void)operation;unsigned expected=*(unsigned *)context;
  pthread_mutex_lock(&status_lock);
  bool allowed=false;
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(source_authorities[i].generation==expected&&source_authorities[i].allowed)allowed=true;
  pthread_mutex_unlock(&status_lock);return allowed?0:-EACCES;
}
static int handle_sync_reply(unsigned expected, const cJSON *reply)
{
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  if(!link){unlock_status();return -EACCES;}
  bool active=link->active && link->linked && link->generation==expected && peer_admitted(link);
  int result=sync_reply_apply(&link->sync_state,expected,active,reply);
  bool complete=link->sync_state.complete;
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].active&&links[i].linked&&
      links[i].sync_state.generation==links[i].generation&&
      (links[i].sync_state.pending||links[i].sync_state.conflict))complete=false;
  bool conflict=link->sync_state.conflict;
  unlock_status();
  if (result>=0 && complete)
    {
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.cards_stale=false;
      pthread_mutex_unlock(&g_buffer.lock);
    }
  if (result==1)
    buffer_set_status(complete?"卡片副本同步完成":conflict?"发现设备保留了较新副本，请核对":"已收到同步回执，继续等待其他设备");
  return result;
}
struct mode_ack_s {const char *mode,*id;unsigned generation;};
static int apply_mode_ack(void *context)
{
  struct mode_ack_s *value=context;
  if(source_authorize(&value->generation,BUFFER_OOBE_GET_STATE))return -EACCES;
  return buffer_sync_remote_mode_ack(value->mode,value->id);
}
static int handle_mode_reply(unsigned expected,const cJSON *reply)
{
  char mode[BUFFER_MODE_SIZE],id[BUFFER_ACTION_ID_SIZE],peer[65];uint64_t term=0;
  int ret=mode_reply_validate(reply,mode,id,&term);if(ret)return ret;
  pthread_mutex_lock(&lock);struct fabric_link_s *link=link_for_generation(expected);
  bool active=link&&link->active&&link->linked&&!stopping&&peer_admitted(link)&&link->view_accepted&&
    link->view_revision==member_revision&&link->mode_state.generation==expected&&link->mode_state.owner_term==term&&
    !strcmp(link->mode_state.mode,mode)&&!strcmp(link->mode_state.mode_id,id);
  if (active)
    hex(link->peer_id, peer);
  unlock_status();
  if (!active)
    return -EACCES;
  struct mode_ack_s value={mode,id,expected};
  ret=buffer_coordinator_phone_owner_commit(peer,term,apply_mode_ack,&value);
  if(ret<0&&ret!=-EAGAIN&&ret!=-EACCES&&ret!=-ESTALE)buffer_set_status("模式回执未能持久保存，将继续重试：%d",ret);
  return ret;
}
struct runtime_apply_s {
  const char *mode;
  const struct buffer_focus_config_s *focus;
  const struct buffer_research_s *research;
  const cJSON *session;
  unsigned generation;
};
static int apply_runtime(void *context)
{
  struct runtime_apply_s *value=context;
  int ret=source_authorize(&value->generation,BUFFER_OOBE_GET_STATE);
  if(!ret)ret=buffer_sync_remote_mode(value->mode);
  if(!ret)ret=source_authorize(&value->generation,BUFFER_OOBE_GET_STATE);
  if(!ret)ret=buffer_sync_focus_config(value->focus);
  if(!ret)ret=source_authorize(&value->generation,BUFFER_OOBE_GET_STATE);
  if(!ret)ret=buffer_sync_research(value->research);
  if(!ret)ret=source_authorize(&value->generation,BUFFER_OOBE_GET_STATE);
  if(!ret)ret=buffer_focus_import(value->session);
  if(!ret)ret=source_authorize(&value->generation,BUFFER_OOBE_GET_STATE);
  return ret;
}
static int handle_runtime_state(unsigned expected, const char *message,
                                char **reply)
{
  uint64_t revision;
  char mode[BUFFER_MODE_SIZE];
  struct buffer_focus_config_s focus;
  struct buffer_research_s research;
  int ret;
  int revision_result;
  const char *receipt = "deferred";

  if (!reply) return -EINVAL;
  *reply = NULL;
  cJSON *payload=NULL;ret=buffer_source_json_object(message,strlen(message),12288,&payload);if(ret)return ret;
  const cJSON *term_value=cJSON_GetObjectItemCaseSensitive(payload,"coordinator_term");
  if(!cJSON_IsNumber(term_value)||!isfinite(term_value->valuedouble)||term_value->valuedouble<1||
     term_value->valuedouble>9007199254740991.0||floor(term_value->valuedouble)!=term_value->valuedouble){cJSON_Delete(payload);return -EACCES;}
  uint64_t owner_term=(uint64_t)term_value->valuedouble;
  unsigned char digest[32];char fingerprint[65];
  if(mbedtls_sha256((const unsigned char *)message,strlen(message),digest,0)){cJSON_Delete(payload);return -EIO;}
  for(unsigned i=0;i<32;i++)snprintf(fingerprint+2*i,3,"%02x",digest[i]);
  ret=buffer_wire_parse_runtime_state(message,&revision,mode,sizeof(mode),&focus,&research);
  if(ret){cJSON_Delete(payload);return ret;}
  cJSON *focus_session=cJSON_DetachItemFromObjectCaseSensitive(payload,"focus_session");
  cJSON_Delete(payload);
  ret=buffer_focus_validate(focus_session,mode);
  if (ret<0) {cJSON_Delete(focus_session);return ret;}

  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  if(!link){unlock_status();cJSON_Delete(focus_session);return -EACCES;}
  bool active = link->active && link->linked && !stopping && link->generation == expected &&
                peer_admitted(link);
  char peer[65];hex(link->peer_id,peer);
  revision_result=0;
  unlock_status();
  if (!active) {cJSON_Delete(focus_session);return -EACCES;}

  if(!buffer_coordinator_phone_owner_current(peer,owner_term)){
    cJSON_Delete(focus_session);*reply=runtime_reply_json(revision,"deferred",owner_term);return *reply?0:-ENOMEM;
  }
  pthread_mutex_lock(&lock);
  active=link->active&&link->linked&&!stopping&&link->generation==expected&&peer_admitted(link);
  revision_result=runtime_revision_check(&link->runtime_state,expected,active,revision,owner_term,fingerprint);
  unlock_status();
  if (revision_result == 1)
    {
      /* The same applied revision is an idempotent transport retry. */
      receipt = "applied";
    }
  else if (revision_result == 0)
    {
      struct runtime_apply_s applied={mode,&focus,&research,focus_session,expected};
      ret=buffer_coordinator_phone_owner_commit(peer,owner_term,apply_runtime,&applied);
      if (ret == 0)
        {
          pthread_mutex_lock(&lock);
          active = link->active && link->linked && !stopping && link->generation == expected &&
                   peer_admitted(link);
          ret = runtime_revision_commit(&link->runtime_state, expected, active,
                                        revision,owner_term,fingerprint);
          unlock_status();
          if (ret == 0) receipt = "applied";
        }
      if (ret < 0 && ret != -EAGAIN)
        {
          buffer_set_status("手机运行状态未完整应用，将请求新快照：%d", ret);
        }
    }
  /* A lower revision and a locally pending mode are deferred without
   * changing the last applied revision. The phone retries with a new snapshot. */
  else if (revision_result != -ESTALE)
    {
      cJSON_Delete(focus_session);return revision_result;
    }
  cJSON_Delete(focus_session);

  if (app_authorize(&expected, BUFFER_OOBE_GET_STATE) < 0) return -EACCES;
  *reply = runtime_reply_json(revision,receipt,owner_term);
  return *reply ? 0 : -ENOMEM;
}

static int compare_member_ids(const void *left,const void *right) { return strcmp(left,right); }
static int handle_member_view(unsigned expected,const cJSON *request,char **reply)
{
  const cJSON *type=cJSON_GetObjectItemCaseSensitive(request,"type"), *protocol=cJSON_GetObjectItemCaseSensitive(request,"protocol"),
    *nonce=cJSON_GetObjectItemCaseSensitive(request,"nonce");
  if (!cJSON_IsString(type) || !cJSON_IsNumber(protocol) || protocol->valuedouble!=1 ||
      !cJSON_IsString(nonce) || !sync_id_valid(nonce->valuestring)) return -EINVAL;
  bool accept=!strcmp(type->valuestring,"buffer.vela.members.accept");
  if (cJSON_GetArraySize(request)!=(accept?6:3)) return -EINVAL;
  if(accept){const cJSON *owner_protocol=cJSON_GetObjectItemCaseSensitive(request,"owner_protocol");
    if(!cJSON_IsNumber(owner_protocol)||owner_protocol->valuedouble!=1)return -ENOTSUP;}
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_generation(expected);
  if(!link){unlock_status();return -EACCES;}
  if (expected!=link->generation || !link->active || !link->linked || !peer_admitted(link)) { unlock_status();return -EACCES; }
  char local[65],peer[65],digest[65];hex(local_id,local);hex(link->peer_id,peer);
  int result=buffer_coordinator_view_digest(local,member_ids,member_count,member_revision,digest);
  if (accept && !result) {
    const cJSON *revision=cJSON_GetObjectItemCaseSensitive(request,"revision"), *hash=cJSON_GetObjectItemCaseSensitive(request,"membership_hash");
    if (!cJSON_IsNumber(revision) || revision->valuedouble!=(double)member_revision || !cJSON_IsString(hash) || strcmp(hash->valuestring,digest)) result=-ESTALE;
    else {
      result=buffer_coordinator_bind_view(local,peer,member_ids,member_count,member_revision);
      if(!result){link->view_accepted=true;link->view_revision=member_revision;buffer_coordinator_peer_connected(peer,true);}
    }
  }
  cJSON *value=NULL;
  if (!result) {
    value=cJSON_CreateObject();cJSON *members=cJSON_CreateArray();
    if (!value || !members) { cJSON_Delete(value);cJSON_Delete(members);value=NULL;result=-ENOMEM; }
    else {
      char ids[BUFFER_COORDINATOR_MAX_MEMBERS][65];memcpy(ids[0],local,65);
      for(unsigned i=0;i<member_count;i++)memcpy(ids[i+1],member_ids[i],65);
      qsort(ids,member_count+1,65,compare_member_ids);
      cJSON_AddItemToObject(value,"members",members);
      if (!cJSON_AddStringToObject(value,"type",accept?"buffer.vela.members.accepted":"buffer.vela.members") ||
          !cJSON_AddNumberToObject(value,"protocol",1)||!cJSON_AddNumberToObject(value,"owner_protocol",1) || !cJSON_AddStringToObject(value,"nonce",nonce->valuestring) ||
          !cJSON_AddStringToObject(value,"device_id",local) || !cJSON_AddNumberToObject(value,"revision",(double)member_revision) ||
          !cJSON_AddStringToObject(value,"membership_hash",digest)) result=-ENOMEM;
      for(unsigned i=0;!result && i<=member_count;i++) {
        cJSON *id=cJSON_CreateString(ids[i]);if (!id) result=-ENOMEM;else cJSON_AddItemToArray(members,id);
      }
      if (!result) {
        *reply=cJSON_PrintUnformatted(value);
        if(!*reply) result=-ENOMEM;
        else if (!link->host || strlen(*reply)>fabric_ble_host_max_payload(link->host)) { free(*reply);*reply=NULL;result=-EMSGSIZE; }
      }
      cJSON_Delete(value);
    }
  }
  if (result) status.error=result;
  unlock_status();return result;
}
/* Application-worker state only. Closing an old generation cannot revoke
 * its replacement, and cache maintenance never runs under the radio lock. */
static bool source_session_maintain(char previous_peer[65],unsigned *previous_generation,
                                    const char *peer,unsigned current,bool active)
{
  if(previous_peer[0]&&(!active||*previous_generation!=current||strcmp(previous_peer,peer)))
    {
      buffer_source_wire_close_session(previous_peer,*previous_generation);
      buffer_replica_close_session(previous_peer,*previous_generation);
      buffer_crdt_sync_close_session(previous_peer,*previous_generation);
    }
  if(active && peer && strlen(peer)==64){memcpy(previous_peer,peer,65);*previous_generation=current;}
  else{previous_peer[0]=0;*previous_generation=0;}
  return buffer_source_wire_session_idle(peer,current,active);
}
static void *application_main(void *unused)
{
  (void)unused;
  char source_peers[FABRIC_LINKS][65]={{0}};unsigned source_generations[FABRIC_LINKS]={0};
  for(;;){
    pthread_mutex_lock(&lock);struct fabric_link_s *link=NULL;
    while(!stopping){
      for(unsigned n=0;n<FABRIC_LINKS;n++){
        unsigned i=(application_cursor+n)%FABRIC_LINKS;
        if(links[i].application_length&&!links[i].application_reply){link=&links[i];application_cursor=(i+1)%FABRIC_LINKS;break;}
      }
      if(link)break;
      struct {char peer[65];unsigned generation;bool active;} observed[FABRIC_LINKS];
      for(unsigned i=0;i<FABRIC_LINKS;i++){
        hex(links[i].peer_id,observed[i].peer);observed[i].generation=links[i].generation;
        observed[i].active=links[i].active&&links[i].linked&&peer_admitted(&links[i]);
      }
      unlock_status();bool cached=false;
      if(!pthread_mutex_trylock(&g_buffer_capture_lock)){
        cached=buffer_replica_recover_results_step()!=0;pthread_mutex_unlock(&g_buffer_capture_lock);
      }
      for(unsigned i=0;i<FABRIC_LINKS;i++)cached=source_session_maintain(source_peers[i],&source_generations[i],observed[i].peer,observed[i].generation,observed[i].active)||cached;
      pthread_mutex_lock(&lock);
      bool changed=stopping;
      for(unsigned i=0;i<FABRIC_LINKS;i++)if((links[i].application_length&&!links[i].application_reply)||links[i].generation!=observed[i].generation||
        (links[i].active&&links[i].linked&&peer_admitted(&links[i]))!=observed[i].active)changed=true;
      if(changed)continue;
      struct timespec until;
      if(cached&&!clock_gettime(CLOCK_MONOTONIC,&until)){until.tv_sec++;(void)pthread_cond_clockwait(&wake,&lock,CLOCK_MONOTONIC,&until);}
      else pthread_cond_wait(&wake,&lock);
    }
    if(stopping){unlock_status();buffer_source_wire_reset();buffer_replica_reset();break;}
    size_t slot=(size_t)(link-links),length=link->application_length;
    unsigned current=link->application_generation;char current_peer[65];hex(link->peer_id,current_peer);
    unsigned char *data=malloc(length+1);
    if(data){memcpy(data,link->application,length);data[length]=0;}
    link->application_length=0;link->working=true;app_busy=true;
    bool source_active=current==link->generation&&link->active&&link->linked&&peer_admitted(link);
    unlock_status();
    (void)source_session_maintain(source_peers[slot],&source_generations[slot],current_peer,current,source_active);
      char *reply = NULL;
      if (data && !app_authorize(&current, BUFFER_OOBE_GET_STATE))
        {
          cJSON *value = cJSON_Parse((char *)data);
          cJSON *type = value ? cJSON_GetObjectItemCaseSensitive(value, "type") : NULL;
          if (cJSON_IsString(type) && !strncmp(type->valuestring,"vela.capture.input.",19))
            syslog(LOG_INFO,"Buffer Fabric: input receipt kind=%d bytes=%u\n",
                !strcmp(type->valuestring,"vela.capture.input.reply")?1:
                !strcmp(type->valuestring,"vela.capture.input.restart")?2:3,(unsigned)length);
          if (cJSON_IsString(type) && !strncmp(type->valuestring,"buffer.events.",14))
            {
              cJSON *request=NULL;char peer[65]={0};
              pthread_mutex_lock(&lock);
              size_t budget=link->host&&current==link->generation?fabric_ble_host_max_payload(link->host):0;
              if(budget)hex(link->peer_id,peer);
              unlock_status();
              int parsed=budget?buffer_source_json_object((char *)data,length,budget,&request):-EACCES;
              if(!parsed){
                struct buffer_oobe_authorization_s auth={source_authorize,&current};
                int result=buffer_source_wire_handle(&auth,peer,current,budget,request,&reply);
                if(result<0&&result!=-EACCES)syslog(LOG_WARNING,"Buffer source reply deferred: %d\n",result);
              }
              cJSON_Delete(request);
            }
          else if (cJSON_IsString(type) && (!strcmp(type->valuestring,"buffer.vela.members.get") || !strcmp(type->valuestring,"buffer.vela.members.accept")))
            {
              cJSON *request=NULL;
              pthread_mutex_lock(&lock);
              size_t budget=link->host&&current==link->generation?fabric_ble_host_max_payload(link->host):0;
              unlock_status();
              if (budget && !buffer_source_json_object((char *)data,length,budget,&request))
                (void)handle_member_view(current,request,&reply);
              cJSON_Delete(request);
            }
          else if (cJSON_IsString(type) && (!strcmp(type->valuestring,"vela.capture.processed.reply") ||
              !strcmp(type->valuestring,"vela.capture.processed.part.reply")))
            { if (!app_authorize(&current, BUFFER_OOBE_GET_STATE)&&current_phone_owner(current_peer)) (void)buffer_agent_outbox_ack(data,length); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"coordinator_heartbeat"))
            {
              if (!app_authorize(&current,BUFFER_OOBE_GET_STATE)) (void)buffer_coordinator_receive(current_peer,value);
            }
          else if (cJSON_IsString(type) && (!strcmp(type->valuestring,"vela.capture.input.reply") ||
              !strcmp(type->valuestring,"vela.capture.input.part.reply") || !strcmp(type->valuestring,"vela.capture.input.restart")))
            { if (!app_authorize(&current,BUFFER_OOBE_GET_STATE)&&current_phone_owner(current_peer)) (void)buffer_agent_input_ack_for_peer(current_peer,buffer_coordinator_fence(),value); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.card.action.reply"))
            { if (!app_authorize(&current,BUFFER_OOBE_GET_STATE)) (void)buffer_replica_action_ack_for_peer(current_peer,value); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.card.state.reply"))
            {
              if (!app_authorize(&current,BUFFER_OOBE_GET_STATE)&&current_phone_owner(current_peer))
                {
                  int result = buffer_agent_card_state_ack(data, length);
                  if (app_authorize(&current,BUFFER_OOBE_GET_STATE) < 0) result = -ESTALE;
                  if (result < 0 && result != -EEXIST && result != -ESTALE)
                    buffer_set_status("卡片状态回执未能持久保存，将继续重试：%d", result);
                }
            }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.calendar.status"))
            {
              cJSON *state=cJSON_GetObjectItemCaseSensitive(value,"state");
              cJSON *id=cJSON_GetObjectItemCaseSensitive(value,"request_id");
              bool matching=false;int64_t start=0,end=0;(void)buffer_calendar_range(&start,&end);
              pthread_mutex_lock(&lock);struct fabric_link_s *link=link_for_generation(current);
              matching=link&&cJSON_IsString(id)&&!strcmp(id->valuestring,link->sync_state.request_id)&&
                start==link->sync_state.calendar_start&&end==link->sync_state.calendar_end;
              unlock_status();
              if (matching&&!app_authorize(&current,BUFFER_OOBE_GET_STATE) && cJSON_IsString(state))
                buffer_calendar_sync_status(!strcmp(state->valuestring,"ready")?0:!strcmp(state->valuestring,"permission_required")?-EACCES:!strcmp(state->valuestring,"transferring")?-EAGAIN:-EIO);
            }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.sync.reply"))
            { (void)handle_sync_reply(current,value); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.mode.reply"))
            { (void)handle_mode_reply(current,value); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.runtime.state"))
            { (void)handle_runtime_state(current,(char *)data,&reply); }
          else if (cJSON_IsString(type) && !strcmp(type->valuestring,"buffer.crdt.reply"))
            { if (!app_authorize(&current,BUFFER_OOBE_GET_STATE)) (void)buffer_crdt_sync_ack(current_peer,current,value); }
          else if (cJSON_IsString(type) && (!strcmp(type->valuestring,"buffer.card.replica.part") || !strcmp(type->valuestring,"buffer.crdt.part")))
            {
              struct buffer_oobe_authorization_s auth = {source_authorize,&current};
              (void)buffer_replica_receive_session(&auth,current_peer,current,data,length,&reply);
            }
          else if (cJSON_IsString(type) &&
                   !strcmp(type->valuestring,"vela.capture.assistant.request"))
            {
              struct buffer_oobe_authorization_s auth = {source_authorize,&current};
              (void)buffer_agent_input_handle_assistant_request(&auth,current_peer,data,length,&reply);
            }
          else
            {
              struct buffer_oobe_authorization_s auth = {source_authorize,&current};
              (void)buffer_oobe_handle_message(&auth,data,length,&reply);
            }
          cJSON_Delete(value);
        }
      if (data) { memset(data,0,length); free(data); }
      pthread_mutex_lock(&lock);app_busy=false;link->working=false;
      if (reply && current == link->generation && link->active && link->linked &&
          !stopping && peer_admitted(link) && !link->application_reply)
        { link->application_reply = reply; link->reply_generation = current; }
      else free(reply);
      pthread_cond_signal(&io_wake);
      unlock_status();
    }
  return NULL;
}
static void refresh_delivery_owner(void)
{
  char owner[65];uint64_t term;bool remote_live;
  if(buffer_coordinator_transport_view(owner,sizeof(owner),&term,&remote_live))return;
  if(term==delivery_term&&!strcmp(owner,delivery_owner))return;
  snprintf(delivery_owner,sizeof(delivery_owner),"%s",owner);delivery_term=term;
  delivery_revision++;delivery_reset_pending=true;pthread_cond_signal(&wake);
  for(unsigned i=0;i<FABRIC_LINKS;i++)if(links[i].outbox_message&&!links[i].outbox_crdt){
    memset(links[i].outbox_message,0,strlen(links[i].outbox_message));free(links[i].outbox_message);links[i].outbox_message=NULL;
  }
}
static bool current_phone_owner(const char *peer)
{return buffer_coordinator_phone_owner_current(peer,buffer_coordinator_fence());}
static void poll_link(struct fabric_link_s *link)
{
      refresh_delivery_owner();
      if(link->host_generation!=link->generation||(!link->linked&&(link->host||link->pairing||link->active||link->application_reply||link->application_length))){
        clear_hosts(link);link->host_generation=link->generation;
      }
      if (link->linked && link->subscribed && !link->host && !link->pairing && (link->selector_complete || link->count))
        {
          select_member(link);
          bool ready=!link->selector_complete || link->selector_processed;
          if (!ready)return;
          if (status.binding == -ENOENT || link->pairing_candidate)
            {
              uint64_t clock = milliseconds();
              if (!pairing_window || clock - pairing_window >= 600000) { pairing_window=clock; pairing_attempts=0; }
              if (++pairing_attempts > 5) { status.error=-EAGAIN; link->linked=false; }
            }
          uint8_t entropy[32]; int ret = link->linked ? random_bytes(NULL,entropy,32) : -EAGAIN;
          if (!ret && !status.binding && !link->pairing_candidate && !admit_peer(link)) ret=-EACCES;
          if (!ret && !status.binding && !link->pairing_candidate)
            { struct fabric_ble_host_config config = {link,send_packet,policy,link->packet_bytes};
              link->host = fabric_ble_host_create(&config,seed,link->peer_public,entropy); }
          else if (!ret && (status.binding == -ENOENT || link->pairing_candidate))
            { struct fabric_pairing_host_config config = {link,send_packet,persist_peer,link->packet_bytes};
              link->pairing = fabric_pairing_host_create(&config,seed,entropy,link->selector_complete?link->selector_public:NULL,milliseconds()); }
          memset(entropy,0,sizeof(entropy));
          if (!link->host && !link->pairing) { invalidate_admission(link); status.error = -EACCES; link->linked = false; }
        }
      uint64_t now = milliseconds();
      while (link->count && link->linked)
        {
          struct packet *packet = &link->packets[link->head];
          int ret = link->host ? fabric_ble_host_feed(link->host,packet->data,packet->length,now) :
                    link->pairing ? fabric_pairing_host_feed(link->pairing,packet->data,packet->length,now) : -7;
          link->head = (link->head+1)%64; link->count--;
          if (ret < 0 && ret != FABRIC_BLE_BUSY && ret != FABRIC_BLE_NOT_READY && ret != FABRIC_BLE_DUPLICATE)
            { status.error = ret; link->linked = false; break; }
        }
      if (link->pairing)
        {
          int result = fabric_pairing_host_poll(link->pairing,now);
          if (result < 0 && result != FABRIC_BLE_BUSY && result != FABRIC_BLE_NOT_READY)
            { link->linked=false; status.error=result; }
          link->code = fabric_pairing_host_code(link->pairing);
          if (link->confirmation) { (void)fabric_pairing_host_confirm(link->pairing,link->code,link->confirmation>0,now); link->confirmation=0; }
          if (fabric_pairing_host_phase(link->pairing)==4 && fabric_pairing_host_flushed(link->pairing) && !link->notifying && !link->tx_queued)
            {
              link->code=-1;
              /* Let the initiator consume the final notification and reopen
               * its authenticated Session. A queued notify is not delivery. */
              if (!link->pairing_finished) link->pairing_finished=now;
              if (now-link->pairing_finished>=5000)
                { link->linked=false;link->disconnect_requested=true; }
            }
        }
      if (link->host && link->linked)
        {
          int result = fabric_ble_host_poll(link->host,now);
          if (result < 0 && result != FABRIC_BLE_BUSY && result != FABRIC_BLE_NOT_READY)
            { syslog(LOG_WARNING,"Buffer Fabric: poll result=%d phase=%d\n",result,fabric_ble_host_phase(link->host));
              link->linked=false; status.error=result; }
          bool was_active=link->active;
          link->active = fabric_ble_host_phase(link->host)==6 && fabric_ble_host_authorized(link->host,2)>0;
          if (link->active && !was_active) {
            for(unsigned i=0;i<FABRIC_LINKS;i++)if(&links[i]!=link&&links[i].active&&!memcmp(links[i].peer_id,link->peer_id,32)){
              links[i].linked=false;links[i].active=false;links[i].session_trusted=false;
              links[i].view_accepted=false;
            }
            char local[65],selected[65];hex(local_id,local);hex(link->peer_id,selected);
            int bind_result=buffer_coordinator_bind_view(local,selected,member_ids,member_count,member_revision);
            if(!bind_result)buffer_coordinator_peer_connected(selected,false);
            if (bind_result) { status.error=bind_result;link->active=false;link->linked=false; }
          }
          publish_source_authority();
          char mode_peer[65],owner[65];hex(link->peer_id,mode_peer);uint64_t mode_term=0;bool remote_live=false;
          bool owner_known=!buffer_coordinator_transport_view(owner,sizeof(owner),&mode_term,&remote_live);
          bool mode_owner=owner_known&&remote_live&&!strcmp(owner,mode_peer)&&link->view_accepted&&link->view_revision==member_revision;
          bool mode_due=link->active&&mode_owner&&link->mode_state.generation==link->generation&&
              link->mode_state.owner_term==mode_term&&link->mode_state.message&&now>=link->mode_state.retry_at_ms;
          /* Drain input even with a queued outbox record. Otherwise its
           * pending delivery receipt can be blocked by an undrained request,
           * while that request waits for the outbox record to finish. */
          if (link->active && !link->application_length)
            {
              int size = fabric_ble_host_take_message(link->host,link->application,sizeof(link->application)-1);
              if (size > 0) { link->application_length=size; link->application_generation=link->generation; pthread_cond_signal(&wake); }
            }
          if (link->application_reply)
            {
              if (link->reply_generation != link->generation)
                { free(link->application_reply); link->application_reply=NULL; pthread_cond_signal(&wake); }
              else if (!fabric_ble_host_send(link->host,(uint8_t *)link->application_reply,strlen(link->application_reply),now))
                { free(link->application_reply); link->application_reply=NULL; pthread_cond_signal(&wake); }
            }
          else if (mode_due)
            {
              if (!fabric_ble_host_send(link->host, (uint8_t *)link->mode_state.message,
                                        strlen(link->mode_state.message), now))
                link->mode_state.retry_at_ms = now + 5000;
            }
          else if (link->sync_state.message)
            {
              if (link->sync_state.generation != link->generation)
                sync_reset_session(&link->sync_state);
              else if (!fabric_ble_host_send(link->host,(uint8_t *)link->sync_state.message,
                                              strlen(link->sync_state.message),now))
                { free(link->sync_state.message); link->sync_state.message=NULL; }
            }
          else if (link->outbox_message)
            {
              char recipient[65];hex(link->peer_id,recipient);
              int checked=link->outbox_crdt ? (link->active && peer_admitted(link) ?
                buffer_crdt_sync_pending_check(recipient,link->generation,now):0) : !delivery_reset_pending;
              if (checked!=-EAGAIN) {
                bool permitted=checked>0;
                if (!permitted || !fabric_ble_host_send(link->host,(uint8_t *)link->outbox_message,strlen(link->outbox_message),now)) {
                  if(link->outbox_crdt)memset(link->outbox_message,0,strlen(link->outbox_message));
                  free(link->outbox_message);link->outbox_message=NULL;link->outbox_crdt=false;
                }
              }
            }
        }
      if (link->connected && !link->linked)link->disconnect_requested=true;
      if (!link->linked) link->active=false;
      link->connected=link->linked;
}
/* Disk selection must never run under the transport mutex: a directory or
 * journal scan can outlast the BLE acknowledgement deadline. Only publish a
 * prepared frame while its authenticated session and ownership fence match. */
static void prepare_link_mode(unsigned slot,unsigned generation,uint64_t owner_term)
{
  struct buffer_fabric_mode_state_s prepared={0};
  pthread_mutex_lock(&lock);struct fabric_link_s *link=&links[slot];
  if(stopping||link->generation!=generation||!link->active||!link->linked){unlock_status();return;}
  prepared=link->mode_state;
  prepared.message=link->mode_state.message?strdup(link->mode_state.message):NULL;
  bool failed=link->mode_state.message&&!prepared.message;
  unlock_status();if(failed)return;
  (void)mode_refresh(&prepared,generation,owner_term,milliseconds());
  pthread_mutex_lock(&lock);link=&links[slot];
  if(!stopping&&link->generation==generation&&link->active&&link->linked&&peer_admitted(link)){
    struct buffer_fabric_mode_state_s *old=&link->mode_state;
    if(old->generation==prepared.generation&&old->owner_term==prepared.owner_term&&
       !strcmp(old->mode,prepared.mode)&&!strcmp(old->mode_id,prepared.mode_id))
      prepared.retry_at_ms=old->retry_at_ms;
    mode_reset_session(old);*old=prepared;prepared.message=NULL;
    pthread_cond_signal(&io_wake);
  }
  unlock_status();mode_reset_session(&prepared);
}
static void *delivery_main(void *unused)
{
  (void)unused;unsigned cursor=0;
  for(;;){
    pthread_mutex_lock(&lock);
    if(stopping){unlock_status();break;}
    /* Reset is delivery maintenance. Its snapshot mutex can be held through
     * NAND scans; the application worker must keep consuming incoming frames
     * and acknowledgements while this worker waits for that mutex. */
    if(delivery_reset_pending){
      uint64_t reset_revision=delivery_revision;unlock_status();
      buffer_agent_outbox_reset();buffer_agent_input_reset();
      pthread_mutex_lock(&lock);
      if(reset_revision==delivery_revision)delivery_reset_pending=false;
      pthread_cond_signal(&io_wake);unlock_status();continue;
    }
    struct fabric_link_s *link=NULL;
    if(!app_busy)for(unsigned n=0;n<FABRIC_LINKS;n++){
      unsigned at=(cursor+n)%FABRIC_LINKS;
      if(links[at].active&&links[at].linked&&peer_admitted(&links[at])&&
         !links[at].application_length&&!links[at].application_reply&&!links[at].outbox_message){
        link=&links[at];cursor=(at+1)%FABRIC_LINKS;break;
      }
    }
    if(!link){unlock_status();usleep(100000);continue;}
    unsigned generation=link->generation;uint64_t revision=delivery_revision;
    char recipient[65];hex(link->peer_id,recipient);
    size_t budget=fabric_ble_host_max_payload(link->host);
    uint64_t term=0;char owner_id[65];bool remote_live=false;
    if(buffer_coordinator_transport_view(owner_id,sizeof(owner_id),&term,&remote_live)){
      unlock_status();usleep(100000);continue;
    }
    bool view=link->view_accepted&&link->view_revision==member_revision;
    bool owner=view&&remote_live&&!strcmp(owner_id,recipient);
    bool deliver=owner&&!delivery_reset_pending;
    link->delivery_preparing=true;unlock_status();
    prepare_link_mode((unsigned)(link-links),generation,owner?term:0);
    uint64_t now=milliseconds();char *message=NULL;bool crdt=false;
    int selected=view?buffer_coordinator_peer_heartbeat(recipient,now,&message):0;
    if(!selected){selected=buffer_crdt_sync_next(recipient,generation,now,budget,&message);crdt=selected>0;}
    if(!selected)selected=buffer_replica_action_next_for_peer(recipient,now,&message);
    if(!selected&&deliver){
      /* Previous storage selectors can outlast an entire rescan interval.
       * The next lane's backoff must start in its own current clock domain. */
      now=milliseconds();
      selected=buffer_agent_input_next_for_peer(recipient,term,now,&message);
    }
    if(!selected&&deliver)selected=buffer_agent_outbox_next(budget,&message);
    if(!selected&&deliver)selected=buffer_agent_card_state_next(milliseconds(),budget,&message);
    pthread_mutex_lock(&lock);link->delivery_preparing=false;
    if(selected>0&&message&&!stopping&&link->generation==generation&&
       link->active&&link->linked&&peer_admitted(link)&&delivery_revision==revision&&
       !link->outbox_message){link->outbox_message=message;link->outbox_crdt=crdt;message=NULL;pthread_cond_signal(&io_wake);}
    unlock_status();
    if(message){memset(message,0,strlen(message));free(message);}
    usleep(100000);
  }
  return NULL;
}
static void *executor_main(void *unused)
{
  (void)unused;
  for(;;){
    pthread_mutex_lock(&lock);
    if(stopping){for(unsigned i=0;i<FABRIC_LINKS;i++)clear_hosts(&links[i]);unlock_status();break;}
    member_actions();refresh_delivery_owner();
    for(unsigned i=0;i<FABRIC_LINKS;i++)poll_link(&links[i]);
    unlock_status();
    for(unsigned i=0;i<FABRIC_LINKS;i++)radio_flush_one(i);
    pthread_mutex_lock(&lock);
    struct timespec deadline;
    if(clock_gettime(CLOCK_MONOTONIC,&deadline)){unlock_status();usleep(50000);continue;}
    deadline.tv_nsec+=50000000;
    if(deadline.tv_nsec>=1000000000){deadline.tv_sec++;deadline.tv_nsec-=1000000000;}
    publish_source_authority();
    if(!stopping)(void)pthread_cond_clockwait(&io_wake,&lock,CLOCK_MONOTONIC,&deadline);
    unlock_status();
  }
  return NULL;
}
static void connected(void *handle,bt_address_t *peer)
{
  pthread_mutex_lock(&lock);struct fabric_link_s *link=NULL;
  bool reject=stopping||connection_epoch==UINT_MAX||(status.binding!=0&&status.binding!=-ENOENT)||link_for_address(peer);
  if(!reject)for(unsigned i=0;i<FABRIC_LINKS;i++)if(!links[i].occupied&&!links[i].host&&!links[i].pairing&&!links[i].working){link=&links[i];break;}
  if(!link)reject=true;
  if(!reject){
    memset(link,0,sizeof(*link));link->address=*peer;link->occupied=link->linked=link->connected=true;
    link->generation=++connection_epoch;link->packet_bytes=20;link->code=-1;
    memcpy(link->peer_id,bound_id,32);memcpy(link->peer_public,bound_public,32);
  }
  pthread_cond_signal(&io_wake);pthread_cond_signal(&wake);unlock_status();
  if(reject)(void)bt_gatts_disconnect(handle,peer);
}
static void disconnected(void *handle, bt_address_t *peer)
{
  (void)handle; pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return;}
  if (!bt_addr_compare(peer,&link->address)) { link->linked=false; link->subscribed=false; link->notifying=false; link->occupied=false;
    link->tx_queued=false;link->tx_packet.length=0;link->disconnect_requested=false;
    invalidate_admission(link);
    coordinator_disconnect(link);
    link->head=link->count=0; link->connected=link->active=false; link->code=-1;
    sync_reset_session(&link->sync_state); mode_reset_session(&link->mode_state);
    runtime_reset_session(&link->runtime_state); }
  pthread_cond_signal(&wake);pthread_cond_signal(&io_wake); unlock_status();
}
static uint16_t receive_packet(void *handle, bt_address_t *peer, uint16_t attribute,
                                const uint8_t *data, uint16_t length, uint16_t offset)
{
  (void)handle; (void)attribute;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return 0;}
  bool valid=link->linked && !bt_addr_compare(peer,&link->address) && !offset && length>=6 && length<=link->packet_bytes;
  if (valid && link->count<64) { unsigned tail=(link->head+link->count)%64; link->packets[tail].length=length;
    memcpy(link->packets[tail].data,data,length); link->count++; }
  else if (valid) { status.error=-ENOBUFS; link->linked=false; }
  pthread_cond_signal(&io_wake); unlock_status(); return valid ? length : 0;
}
static uint16_t ccc_changed(void *handle, bt_address_t *peer, uint16_t attribute,
                            const uint8_t *value, uint16_t length, uint16_t offset)
{
  (void)handle; (void)attribute;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return 0;}
  if (link->linked && !bt_addr_compare(peer,&link->address) && !offset && length==2)
    link->subscribed=value[0]==1 && value[1]==0;
  pthread_cond_signal(&io_wake); unlock_status(); return length;
}

static uint16_t select_peer_packet(void *handle,bt_address_t *peer,uint16_t attribute,
                                  const uint8_t *data,uint16_t length,uint16_t offset)
{
  (void)handle;(void)attribute;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return 0;}
  bool valid=link->linked && !bt_addr_compare(peer,&link->address) && !offset && !link->host && !link->pairing && !link->active &&
    data && length>=2 && length<=link->packet_bytes && data[0]<32 && data[0]+length-1<=32;
  if (valid) {
    size_t start=data[0],size=length-1;
    if (start<link->selector_received) valid=start+size<=link->selector_received && !memcmp(link->selector_public+start,data+1,size);
    else if (start!=link->selector_received || link->selector_complete) valid=false;
    else { memcpy(link->selector_public+start,data+1,size);link->selector_received+=size;link->selector_complete=link->selector_received==32; }
  }
  pthread_cond_signal(&io_wake);unlock_status();return valid?length:0;
}
static void mtu_changed(void *handle, bt_address_t *peer, uint32_t att_payload)
{
  (void)handle;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return;}
  /* openvela's GATTS callback already reports ATT_MTU - 3. Android's raw
   * BluetoothGatt callback reports ATT_MTU; subtracting here a second time
   * rejects the peer's full-size fragments while short controls still work. */
  if (link->linked && !bt_addr_compare(peer,&link->address) && !link->host && !link->pairing && att_payload>=20)
    link->packet_bytes=att_payload>512 ? 512 : att_payload;
  pthread_cond_signal(&io_wake); unlock_status();
}
static void notified(void *handle, bt_address_t *peer, gatt_status_t result, uint16_t attribute)
{
  (void)handle; (void)attribute;
  pthread_mutex_lock(&lock);
  struct fabric_link_s *link=link_for_address(peer);
  if(!link){unlock_status();return;}
  if (!bt_addr_compare(peer,&link->address)) { link->notifying=false; if (result!=GATT_STATUS_SUCCESS) { link->linked=false; status.error=-EIO; } }
  pthread_cond_signal(&io_wake); unlock_status();
}
static void table_added(void *handle, gatt_status_t result, uint16_t attribute)
{
  (void)handle; (void)attribute;
  printf("Buffer Fabric: GATT table status=%d\n", result);
  pthread_mutex_lock(&lock); table_reported=true; table_ready=result==GATT_STATUS_SUCCESS;
  pthread_cond_broadcast(&wake); unlock_status();
}
static uint8_t identity_locator[64];
static gatt_attr_db_t attributes[] = {
  GATT_H_PRIMARY_SERVICE(FABRIC_UUID(1),1),
  GATT_H_CHARACTERISTIC_USER_RSP(FABRIC_UUID(2),GATT_PROP_WRITE,GATT_PERM_WRITE,NULL,receive_packet,2),
  GATT_H_CHARACTERISTIC_USER_RSP(FABRIC_UUID(3),GATT_PROP_NOTIFY,GATT_PERM_READ,NULL,NULL,3),
  GATT_H_CCCD(GATT_PERM_READ|GATT_PERM_WRITE,ccc_changed,4),
  GATT_H_CHARACTERISTIC_AUTO_RSP(FABRIC_UUID(4),GATT_PROP_READ,GATT_PERM_READ,identity_locator,sizeof(identity_locator),5),
  GATT_H_CHARACTERISTIC_USER_RSP(FABRIC_UUID(5),GATT_PROP_WRITE,GATT_PERM_WRITE,NULL,select_peer_packet,6),
};
static gatt_srv_db_t database={.attr_db=attributes,.attr_num=sizeof(attributes)/sizeof(attributes[0])};
static gatts_callbacks_t callbacks={.size=sizeof(callbacks),.on_connected=connected,.on_disconnected=disconnected,
  .on_attr_table_added=table_added,.on_mtu_changed=mtu_changed,.on_notify_complete=notified};

int buffer_fabric_service_start(void *instance)
{
  int ret=buffer_fabric_init(); if (ret) return ret;
  pthread_mutex_lock(&lock);
  if (started) { unlock_status(); return 0; }
  stopping=false; table_reported=table_ready=false;
  fabric_identity_public(seed,identity_locator,identity_locator+32);
  unlock_status();
  int registration=bt_gatts_register_service(instance,&service,&callbacks);
  if (registration!=BT_STATUS_SUCCESS)
    { printf("Buffer Fabric: GATT register failed=%d\n",registration); return -EIO; }
  int added=bt_gatts_add_attr_table(service,&database);
  if (added!=BT_STATUS_SUCCESS)
    { printf("Buffer Fabric: GATT add table failed=%d\n",added); goto fail; }
  struct timespec deadline; clock_gettime(CLOCK_REALTIME,&deadline); deadline.tv_sec+=5;
  pthread_mutex_lock(&lock);
  while (!table_reported) if (pthread_cond_timedwait(&wake,&lock,&deadline)) break;
  bool ready=table_ready; unlock_status();
  if (!ready) { printf("Buffer Fabric: GATT table not ready\n"); goto fail; }
  pthread_attr_t attr; pthread_attr_init(&attr); pthread_attr_setstacksize(&attr,96*1024);
  ret=pthread_create(&app_worker,&attr,application_main,NULL);
  if (ret) { pthread_attr_destroy(&attr); goto fail; }
  ret=pthread_create(&delivery_worker,&attr,delivery_main,NULL);
  if(ret){pthread_attr_destroy(&attr);pthread_mutex_lock(&lock);stopping=true;pthread_cond_broadcast(&wake);unlock_status();pthread_join(app_worker,NULL);goto fail;}
  ret=pthread_create(&executor,&attr,executor_main,NULL); pthread_attr_destroy(&attr);
  if (ret) { pthread_mutex_lock(&lock); stopping=true; pthread_cond_broadcast(&wake); unlock_status();
    pthread_join(app_worker,NULL);pthread_join(delivery_worker,NULL); goto fail; }
  pthread_mutex_lock(&lock); started=true; unlock_status(); return 0;
fail:
  if (service) bt_gatts_unregister_service(service);
  service=NULL; return -EIO;
}
void buffer_fabric_service_stop(void)
{
  pthread_mutex_lock(&lock);bool join=started;stopping=true;
  for(unsigned i=0;i<FABRIC_LINKS;i++){links[i].linked=false;links[i].active=false;invalidate_admission(&links[i]);}
  pthread_cond_broadcast(&io_wake);pthread_cond_broadcast(&wake);unlock_status();
  if(join){pthread_join(executor,NULL);pthread_join(app_worker,NULL);pthread_join(delivery_worker,NULL);}
  if(service)bt_gatts_unregister_service(service);
  pthread_mutex_lock(&lock);service=NULL;started=false;
  for(unsigned i=0;i<FABRIC_LINKS;i++){
    free(links[i].application_reply);links[i].application_reply=NULL;links[i].application_length=0;
    links[i].occupied=links[i].connected=links[i].working=false;
  }
  app_busy=false;memset(seed,0,sizeof(seed));initialized=false;
  unlock_status();buffer_agent_outbox_reset();buffer_agent_input_reset();buffer_crdt_sync_reset();
}
bool buffer_fabric_busy(void)
{
  pthread_mutex_lock(&lock);bool result=false;
  for(unsigned i=0;i<FABRIC_LINKS;i++)result|=links[i].occupied;
  unlock_status();return result;
}
bool buffer_fabric_accepting_connections(void)
{
  pthread_mutex_lock(&lock);bool available=false;
  if(initialized&&!stopping&&connection_epoch<UINT_MAX&&(!status.binding||status.binding==-ENOENT))
    for(unsigned i=0;i<FABRIC_LINKS;i++)if(!links[i].occupied&&!links[i].host&&!links[i].pairing&&!links[i].working)available=true;
  unlock_status();return available;
}
#endif
