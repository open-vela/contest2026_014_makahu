/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_coordinator.h"
#include "buffer_entropy.h"
#include <mbedtls/sha256.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>

#define STATE_FILE CONFIG_BUFFER_VELA_DATA_DIR "/coordinator.json"
#define PEER_TIMEOUT_MS 30000
#define AUTHORITY_LEASE_MS 12000
static pthread_mutex_t co_lock=PTHREAD_MUTEX_INITIALIZER;
static char self_id[65], peer_id[65], session_id[80], membership[65], incarnation[33], owner_id[65];
static char view_members[BUFFER_COORDINATOR_MAX_MEMBERS][65];
static unsigned view_count;
struct controller_s {
  char id[65],incarnation[65];
  uint64_t sequence,revision,seen,connected_at,next_heartbeat;
  bool connected,ready;
  unsigned rank;
};
static struct controller_s controllers[BUFFER_COORDINATOR_MAX_MEMBERS-1];
static unsigned controller_count;
static uint64_t term,revision=1,sequence,preferred_since,owner_grant_until,handoff_until;
static bool ready_previous,bound,storage_failed;
static char preferred_id[65];
static void clear_preference(void)
{ preferred_since=0;preferred_id[0]=0; }
static struct controller_s *controller(const char *id)
{if(!id)return NULL;for(unsigned i=0;i<controller_count;i++)if(!strcmp(controllers[i].id,id))return &controllers[i];return NULL;}
static bool fresh(const struct controller_s *peer,uint64_t now)
{return peer&&peer->connected&&peer->ready&&now&&peer->seen&&now>=peer->seen&&now-peer->seen<PEER_TIMEOUT_MS;}

static uint64_t mono(void)
{ struct timespec ts;if(clock_gettime(CLOCK_MONOTONIC,&ts))return 0;return (uint64_t)ts.tv_sec*1000+ts.tv_nsec/1000000; }
static const char *text(const cJSON *j,const char *name)
{ const cJSON *value=cJSON_GetObjectItemCaseSensitive(j,name); return cJSON_IsString(value)?value->valuestring:""; }
static bool number(const cJSON *value,uint64_t *out)
{ if (!cJSON_IsNumber(value)||!isfinite(value->valuedouble)||value->valuedouble<0||
      value->valuedouble>9007199254740991.0||floor(value->valuedouble)!=value->valuedouble) return false;
  *out=(uint64_t)value->valuedouble; return true; }
static int persist_locked(uint64_t next,const char *owner)
{
  if(next>9007199254740991ULL){if(!storage_failed)revision++;storage_failed=true;return -EOVERFLOW;}
  cJSON *record=cJSON_CreateObject();if(!record){if(!storage_failed)revision++;storage_failed=true;return -ENOMEM;}
  if(!cJSON_AddStringToObject(record,"membership",membership)||!cJSON_AddNumberToObject(record,"term",(double)next)||
     !cJSON_AddStringToObject(record,"coordinator",owner)){cJSON_Delete(record);if(!storage_failed)revision++;storage_failed=true;return -ENOMEM;}
  int ret=buffer_agent_atomic_json(STATE_FILE,record); cJSON_Delete(record);
  if(storage_failed!=(ret!=0))revision++;
  storage_failed=ret!=0;
  if (!ret) { term=next;strcpy(owner_id,owner);owner_grant_until=0;revision++; }
  return ret;
}
static bool local_ready(void)
{ struct buffer_agent_status_s state; buffer_agent_snapshot(&state);
  return state.ready && buffer_wifi_is_ready(); }
static void choose_locked(uint64_t now,bool ready)
{
  struct controller_s *preferred=NULL;bool waiting=false;
  for(unsigned i=0;i<controller_count;i++){
    struct controller_s *peer=&controllers[i];
    if(fresh(peer,now)&&(!preferred||peer->rank<preferred->rank||
        (peer->rank==preferred->rank&&strcmp(peer->id,preferred->id)<0)))preferred=peer;
    if(peer->connected&&now>=peer->connected_at&&now-peer->connected_at<9000)waiting=true;
  }
  /* Compare the same priority/DeviceId tuple on every link. A recovered
   * candidate must remain eligible before displacing a healthy owner. */
  unsigned best=preferred?preferred->rank:3;
  const char *choice=preferred?preferred->id:"";
  if(ready&&(best>1||(best==1&&strcmp(self_id,choice)<0)))choice=self_id;
  if(!owner_id[0]&&now<handoff_until){clear_preference();return;}
  if(!strcmp(choice,owner_id)&&!storage_failed){
    /* Renewing an expired grant is a new fence even for the same peer. */
    if(owner_id[0]&&strcmp(owner_id,self_id)&&owner_grant_until&&now>=owner_grant_until)
      (void)persist_locked(term+1,choice);
    clear_preference();return;
  }
  struct controller_s *current=controller(owner_id);
  bool current_ready=!strcmp(owner_id,self_id)?ready:fresh(current,now);
  if(owner_id[0]&&choice[0]&&current_ready&&!storage_failed){
    if(strcmp(preferred_id,choice)){strcpy(preferred_id,choice);preferred_since=now;}
    if(now<preferred_since||now-preferred_since<15000){
      if(current&&owner_grant_until&&now>=owner_grant_until)
        (void)persist_locked(term+1,current->id);
      return;
    }
  }else clear_preference();
  if(owner_id[0]&&strcmp(owner_id,self_id)&&now<owner_grant_until){
    handoff_until=owner_grant_until;
    (void)persist_locked(term+1,"");clear_preference();return;
  }
  if(waiting&&!preferred&&!owner_id[0])return;
  if(!persist_locked(term+1,choice))
    syslog(LOG_INFO,"Buffer Coordinator: owner=%s term=%llu\n",choice,(unsigned long long)term);
  clear_preference();
}
static bool device_id_valid(const char *id)
{
  if (!id || strlen(id)!=64) return false;
  for (unsigned i=0;i<64;i++) if (!((id[i]>='0'&&id[i]<='9')||(id[i]>='a'&&id[i]<='f'))) return false;
  return true;
}
static int compare_ids(const void *left,const void *right) { return strcmp(left,right); }
static int view_digest(const char *local,const char members[][65],unsigned count,uint64_t roster_revision,bool legacy,char out[65])
{
  if (!device_id_valid(local) || !members || !count || count>=BUFFER_COORDINATOR_MAX_MEMBERS || roster_revision>9007199254740991ULL) return -EINVAL;
  char ids[BUFFER_COORDINATOR_MAX_MEMBERS][65];memcpy(ids[0],local,65);
  for (unsigned i=0;i<count;i++) {
    if (!device_id_valid(members[i]) || !strcmp(local,members[i])) return -EINVAL;
    memcpy(ids[i+1],members[i],65);
  }
  qsort(ids,count+1,65,compare_ids);
  unsigned char input[BUFFER_COORDINATOR_MAX_MEMBERS*65+40];size_t size=0;
  for (unsigned i=0;i<=count;i++) {
    if (i && !strcmp(ids[i-1],ids[i])) return -EINVAL;
    if (i) input[size++]=0;
    memcpy(input+size,ids[i],64);size+=64;
  }
  if (!legacy) { input[size++]=0;int n=snprintf((char *)input+size,sizeof(input)-size,"roster_revision:%llu",(unsigned long long)roster_revision);if(n<0||(size_t)n>=sizeof(input)-size)return-EOVERFLOW;size+=(size_t)n; }
  unsigned char hash[32];if (mbedtls_sha256(input,size,hash,0)) return -EIO;
  for (unsigned i=0;i<32;i++) snprintf(out+i*2,3,"%02x",hash[i]);
  return 0;
}
int buffer_coordinator_view_digest(const char *local,const char members[][65],unsigned count,uint64_t roster_revision,char digest[65])
{ return view_digest(local,members,count,roster_revision,false,digest); }

static int bind_view(const char *local,const char *peer,const char members[][65],unsigned count,uint64_t roster_revision,bool legacy)
{
  char digest[65];int result=view_digest(local,members,count,roster_revision,legacy,digest);
  if (result || !device_id_valid(peer)) return result?result:-EINVAL;
  bool contains=false;for(unsigned i=0;i<count;i++)if(!strcmp(peer,members[i]))contains=true;
  if (!contains) return -EACCES;
  pthread_mutex_lock(&co_lock);
  if (bound && !strcmp(local,self_id) && !strcmp(digest,membership)) { pthread_mutex_unlock(&co_lock); return 0; }
  bound=false;storage_failed=true;memset(controllers,0,sizeof(controllers));controller_count=0;owner_id[0]=0;
  unsigned char nonce[16];
  int entropy_ret=buffer_entropy_read(nonce,sizeof(nonce));
  if (entropy_ret) { pthread_mutex_unlock(&co_lock); return entropy_ret; }
  for (int i=0;i<16;i++) snprintf(incarnation+2*i,3,"%02x",nonce[i]);
  memset(nonce,0,sizeof(nonce));
  cJSON *saved=buffer_agent_read_json(STATE_FILE,1024);
  if (!saved && errno!=ENOENT) { pthread_mutex_unlock(&co_lock); return -EIO; }
  uint64_t previous=0;
  if (saved && (!cJSON_IsObject(saved)||!number(cJSON_GetObjectItemCaseSensitive(saved,"term"),&previous)))
    { cJSON_Delete(saved); pthread_mutex_unlock(&co_lock); return -EINVAL; }
  cJSON_Delete(saved);
  strcpy(self_id,local);strcpy(peer_id,peer);strcpy(membership,digest);
  snprintf(session_id,sizeof(session_id),"buffer-v1-%s",membership);
  memcpy(view_members[0],local,65);for(unsigned i=0;i<count;i++)memcpy(view_members[i+1],members[i],65);view_count=count+1;
  controller_count=count;
  for(unsigned i=0;i<count;i++)memcpy(controllers[i].id,members[i],65);
  term=previous;storage_failed=false;owner_id[0]=0;clear_preference();
  sequence=0;revision=1;ready_previous=false;owner_grant_until=0;
  /* A prior process may have issued a phone lease immediately before exit. */
  handoff_until=previous?mono()+AUTHORITY_LEASE_MS:0;
  bound=true; pthread_mutex_unlock(&co_lock); return 0;
}
int buffer_coordinator_bind(const char *local,const char *peer)
{
  if (!device_id_valid(peer)) return -EINVAL;
  char members[1][65];memcpy(members[0],peer,65);return bind_view(local,peer,members,1,0,true);
}
int buffer_coordinator_bind_view(const char *local,const char *peer,const char members[][65],unsigned count,uint64_t roster_revision)
{ return bind_view(local,peer,members,count,roster_revision,false); }
static bool view_contains(const char *id)
{ for(unsigned i=0;i<view_count;i++)if(!strcmp(id,view_members[i]))return true;return false; }
void buffer_coordinator_peer_connected(const char *id,bool active)
{
  pthread_mutex_lock(&co_lock);struct controller_s *peer=controller(id);
  if(peer&&peer->connected!=active){
    if(!strcmp(preferred_id,id))clear_preference();
    peer->connected=active;peer->connected_at=mono();peer->ready=false;peer->rank=3;
    peer->sequence=peer->revision=peer->seen=peer->next_heartbeat=0;peer->incarnation[0]=0;revision++;
    if(!active&&!strcmp(owner_id,id)){
      handoff_until=owner_grant_until;(void)persist_locked(term+1,"");
    }
  }
  pthread_mutex_unlock(&co_lock);
}
void buffer_coordinator_connected(bool active)
{
  char id[65];pthread_mutex_lock(&co_lock);memcpy(id,peer_id,65);pthread_mutex_unlock(&co_lock);
  buffer_coordinator_peer_connected(id,active);
}
int buffer_coordinator_receive(const char *authenticated,const cJSON *heartbeat)
{
  uint64_t seq, rev, remote_term; int ret=-EINVAL;
  bool ready=local_ready(); uint64_t now=mono();
  pthread_mutex_lock(&co_lock);
  struct controller_s *peer=controller(authenticated);
  if (!bound||!peer||!peer->connected||
      strcmp(text(heartbeat,"type"),"coordinator_heartbeat")||
      strcmp(text(heartbeat,"device_id"),authenticated)||strcmp(text(heartbeat,"session_id"),session_id)||
      strcmp(text(heartbeat,"membership_version"),membership)||
      !number(cJSON_GetObjectItemCaseSensitive(heartbeat,"heartbeat_sequence"),&seq)||!seq||
      !number(cJSON_GetObjectItemCaseSensitive(heartbeat,"state_revision"),&rev)||
      !number(cJSON_GetObjectItemCaseSensitive(heartbeat,"coordinator_term"),&remote_term)) goto done;
  uint64_t protocol;
  const char *boot=text(heartbeat,"incarnation_id"), *leader=text(heartbeat,"coordinator_device_id");
  if (!number(cJSON_GetObjectItemCaseSensitive(heartbeat,"protocol_version"),&protocol)||protocol!=1||
      !boot[0]||strlen(boot)>=sizeof(peer->incarnation)||
      (leader[0]&&!view_contains(leader))) goto done;
  if (!strcmp(boot,peer->incarnation) && (seq<=peer->sequence||rev<peer->revision)) { ret=-ESTALE; goto done; }
  const cJSON *r=cJSON_GetObjectItemCaseSensitive(heartbeat,"readiness");
  const cJSON *caps=cJSON_GetObjectItemCaseSensitive(r,"capabilities"), *cap;
  bool transcribe=false,classify=false;
  cJSON_ArrayForEach(cap,caps)
    { if (cJSON_IsString(cap)) { if (!strcmp(cap->valuestring,"TRANSCRIBE")) transcribe=true;
        if (!strcmp(cap->valuestring,"CLASSIFY")) classify=true; } }
  const char *device_class=text(heartbeat,"device_class");
  unsigned rank=(!strcmp(device_class,"PHONE")||!strcmp(device_class,"TABLET"))?0:
    !strcmp(device_class,"VELA")?1:!strcmp(device_class,"OTHER")?2:3;
  if(peer->incarnation[0]&&!strcmp(boot,peer->incarnation)&&rev==peer->revision&&rank!=peer->rank){ret=-ESTALE;goto done;}
  bool eligible=rank<3&&cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r,"configuration_valid"))&&
    cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r,"service_reachable"))&&
    cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r,"background_execution_available"))&&
    cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r,"storage_available"))&&
    cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r,"can_coordinate"))&&transcribe&&classify;
  if(peer->incarnation[0]&&!strcmp(boot,peer->incarnation)&&rev==peer->revision&&eligible!=peer->ready){ret=-ESTALE;goto done;}
  if(peer->incarnation[0]&&strcmp(peer->incarnation,boot)&&!strcmp(owner_id,authenticated)){
    handoff_until=owner_grant_until;(void)persist_locked(term+1,"");
  }
  if(!strcmp(preferred_id,authenticated)&&
      (!fresh(peer,now)||strcmp(peer->incarnation,boot)||!eligible||peer->rank!=rank))clear_preference();
  /* A controller advertises eligibility, never assigns the device's owner/term.
   * All links share this one durable device authority. */
  strcpy(peer->incarnation,boot);peer->sequence=seq;peer->revision=rev;peer->seen=now;peer->ready=eligible;peer->rank=rank;
  choose_locked(now,ready); ret=0;
done:
  pthread_mutex_unlock(&co_lock); return ret;
}
int buffer_coordinator_peer_heartbeat(const char *id,uint64_t now,char **message)
{
  if (!message)
    return -EINVAL;
  *message = NULL;
  bool ready = local_ready();
  pthread_mutex_lock(&co_lock);struct controller_s *peer=controller(id);
  /* A request can arrive after the executor sampled its poll clock. Start
   * the device's lease after that request, while holding the owner fence. */
  now=mono();if(!now){pthread_mutex_unlock(&co_lock);return -EIO;}
  if(!bound||!peer||!peer->connected){pthread_mutex_unlock(&co_lock);return 0;}
  if(ready!=ready_previous){ready_previous=ready;revision++;}
  choose_locked(now,ready);
  if(now<peer->next_heartbeat){pthread_mutex_unlock(&co_lock);return 0;}
  if(sequence>=9007199254740991ULL){pthread_mutex_unlock(&co_lock);return -EOVERFLOW;}
  cJSON *j=cJSON_CreateObject(),*r=cJSON_CreateObject(),*caps=cJSON_CreateArray();
  bool built=j&&r&&caps&&cJSON_AddStringToObject(j,"type","coordinator_heartbeat")&&
    cJSON_AddNumberToObject(j,"protocol_version",1)&&cJSON_AddNumberToObject(j,"device_authority_version",1)&&
    cJSON_AddStringToObject(j,"session_id",session_id)&&cJSON_AddStringToObject(j,"membership_version",membership)&&
    cJSON_AddStringToObject(j,"device_id",self_id)&&cJSON_AddStringToObject(j,"device_class","VELA")&&cJSON_AddStringToObject(j,"incarnation_id",incarnation)&&
    cJSON_AddNumberToObject(j,"state_revision",(double)revision)&&cJSON_AddNumberToObject(j,"heartbeat_sequence",(double)(sequence+1))&&
    cJSON_AddNumberToObject(j,"coordinator_term",(double)term)&&
    cJSON_AddNumberToObject(j,"authority_request_sequence",(double)peer->sequence)&&
    cJSON_AddStringToObject(j,"authority_request_incarnation",peer->incarnation)&&
    cJSON_AddNumberToObject(j,"authority_ttl_ms",!storage_failed&&fresh(peer,now)&&!strcmp(owner_id,id)?AUTHORITY_LEASE_MS:0)&&
    (owner_id[0]?cJSON_AddStringToObject(j,"coordinator_device_id",owner_id):cJSON_AddNullToObject(j,"coordinator_device_id"))&&
    cJSON_AddBoolToObject(r,"configuration_valid",ready)&&cJSON_AddBoolToObject(r,"service_reachable",ready)&&
    cJSON_AddBoolToObject(r,"background_execution_available",true)&&cJSON_AddBoolToObject(r,"storage_available",!storage_failed)&&
    cJSON_AddBoolToObject(r,"can_coordinate",ready&&!storage_failed);
  const char *names[]={"TRANSCRIBE","CLASSIFY","ANSWER","GENERATE"};
  for(unsigned i=0;built&&i<4;i++){cJSON *cap=cJSON_CreateString(names[i]);if(!cap)built=false;else if(!cJSON_AddItemToArray(caps,cap)){cJSON_Delete(cap);built=false;}}
  if(built&&cJSON_AddItemToObject(r,"capabilities",caps))caps=NULL;else built=false;
  if(built&&cJSON_AddItemToObject(j,"readiness",r))r=NULL;else built=false;
  if(built)*message=cJSON_PrintUnformatted(j);
  cJSON_Delete(caps);cJSON_Delete(r);cJSON_Delete(j);
  if(*message){
    sequence++;peer->next_heartbeat=now+3000;
    if(!storage_failed&&fresh(peer,now)&&!strcmp(owner_id,id))owner_grant_until=now+AUTHORITY_LEASE_MS;
  }
  pthread_mutex_unlock(&co_lock);return *message?1:-ENOMEM;
}
int buffer_coordinator_heartbeat(uint64_t now,char **message)
{
  char id[65];pthread_mutex_lock(&co_lock);memcpy(id,peer_id,65);pthread_mutex_unlock(&co_lock);
  return buffer_coordinator_peer_heartbeat(id,now,message);
}
bool buffer_coordinator_local(void)
{
  bool ready=local_ready(); pthread_mutex_lock(&co_lock);
  if (bound) choose_locked(mono(),ready);
  bool result=(!bound&&!storage_failed)||(!strcmp(owner_id,self_id)&&ready&&!storage_failed);
  pthread_mutex_unlock(&co_lock); return result;
}
uint64_t buffer_coordinator_fence(void)
{ pthread_mutex_lock(&co_lock); uint64_t value=term; pthread_mutex_unlock(&co_lock); return value; }
static bool local_current_locked(uint64_t token,bool ready)
{
  if(bound)choose_locked(mono(),ready);
  return token==term&&!storage_failed&&(!bound||(!strcmp(owner_id,self_id)&&ready));
}
bool buffer_coordinator_fence_current(uint64_t token)
{
  bool ready=local_ready();pthread_mutex_lock(&co_lock);
  bool valid=local_current_locked(token,ready);
  pthread_mutex_unlock(&co_lock);return valid;
}
int buffer_coordinator_local_commit(uint64_t token,int (*commit)(void *context),void *context)
{
  if(!commit)return -EINVAL;
  bool ready=local_ready();pthread_mutex_lock(&co_lock);
  int ret=local_current_locked(token,ready)?commit(context):-ESTALE;
  pthread_mutex_unlock(&co_lock);return ret;
}
bool buffer_coordinator_phone_owner_current(const char *authenticated_phone,
                                            uint64_t expected_term)
{
  if (!authenticated_phone || strlen(authenticated_phone) != 64) return false;
  bool ready=local_ready();
  uint64_t now=mono();
  pthread_mutex_lock(&co_lock);now=mono();
  choose_locked(now,ready);
  struct controller_s *peer=controller(authenticated_phone);
  bool valid=bound&&fresh(peer,now)&&now<owner_grant_until&&!storage_failed&&!strcmp(owner_id,authenticated_phone)&&expected_term==term;
  pthread_mutex_unlock(&co_lock);
  return valid;
}
int buffer_coordinator_phone_owner_commit(const char *authenticated_phone,
                                          uint64_t expected_term,
                                          int (*commit)(void *context),
                                          void *context)
{
  if (!authenticated_phone || strlen(authenticated_phone) != 64 || !commit)
    return -EINVAL;
  bool ready = local_ready();
  uint64_t now = mono();
  pthread_mutex_lock(&co_lock);now=mono();
  choose_locked(now, ready);
  struct controller_s *peer=controller(authenticated_phone);
  bool valid=bound&&fresh(peer,now)&&now<owner_grant_until&&!storage_failed&&!strcmp(owner_id,authenticated_phone)&&expected_term==term;
  int ret = valid ? commit(context) : -EAGAIN;
  pthread_mutex_unlock(&co_lock);
  return ret;
}
void buffer_coordinator_status(char *owner,size_t size,uint64_t *epoch)
{ pthread_mutex_lock(&co_lock); snprintf(owner,size,"%s",owner_id); *epoch=term; pthread_mutex_unlock(&co_lock); }
int buffer_coordinator_transport_view(char *owner,size_t size,uint64_t *epoch,bool *remote_live)
{
  if(!owner||!size||!epoch||!remote_live)return -EINVAL;
  int locked=pthread_mutex_trylock(&co_lock);
  if(locked)return locked==EBUSY?-EAGAIN:-locked;
  uint64_t now=mono();
  snprintf(owner,size,"%s",owner_id);*epoch=term;
  *remote_live=bound&&!storage_failed&&now&&now<owner_grant_until&&fresh(controller(owner_id),now);
  pthread_mutex_unlock(&co_lock);return 0;
}
