/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_view.h"
#include "buffer_source_remote.h"
#include "buffer_source_envelope.h"
#include "buffer_source_events.h"
#include "buffer_source_privacy.h"
#include "buffer_crdt.h"
#include "buffer_replica.h"
#include "buffer_calendar.h"
#include "buffer_voice.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake=PTHREAD_COND_INITIALIZER;
static pthread_t thread;
static bool started,stopping;
static bool local_turn=true;
static bool view_initialized;
static uint64_t local_cursor;
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
/* cJSON cannot retain embedded NUL in payload strings. Never materialize its
 * truncated interpretation. Escaped backslashes are skipped as a pair. */
static bool embedded_nul(const char *raw,size_t bytes)
{
  for(size_t i=0;i+1<bytes;i++)if(raw[i]=='\\'){
      if (i + 5 < bytes && raw[i + 1] == 'u' && !memcmp(raw + i + 2, "0000", 4))
        return true;
      i++;
  }
  return false;
}
static int project(struct buffer_source_projection_input_s *input,const char **state)
{
  *state=BUFFER_SOURCE_VIEW_UNSUPPORTED;cJSON *envelope=NULL;int ret=buffer_source_envelope_decode(input->raw,input->length,&envelope);if(ret)return ret;
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  const char *type=text(event,"type");bool card=!strcmp(type,"CardDocumentObserved"),calendar=!strcmp(type,"CalendarDocumentObserved"),merge=!strcmp(type,"DocumentMerged");
  bool deletion=!strcmp(type,"CardDeleted");cJSON *tombstone=NULL;
  if(!card&&!calendar&&!merge&&!deletion)goto done;
  const cJSON *schema=cJSON_GetObjectItemCaseSensitive(event,"schema_version");
  if(!cJSON_IsNumber(schema)||schema->valuedouble!=1)goto done;
  if(merge&&!cJSON_GetObjectItemCaseSensitive(payload,"document"))goto done;
  *state=BUFFER_SOURCE_VIEW_INVALID;
  const cJSON *document=cJSON_GetObjectItemCaseSensitive(payload,"document"),*clock=cJSON_GetObjectItemCaseSensitive(payload,"clock");
  if(deletion){
    if(embedded_nul(input->raw,input->length)||!cJSON_IsObject(payload)||cJSON_GetArraySize(payload)!=1||!buffer_agent_valid_id(text(payload,"card_id")))goto done;
    /* Deletion is itself the monotone CRDT fact. It needs no invented field
     * stamp and may arrive before any document for this identity. */
    tombstone=cJSON_CreateObject();cJSON *fields=cJSON_CreateObject();
    if(!tombstone||!fields){cJSON_Delete(fields);ret=-ENOMEM;goto done;}
    if(!cJSON_AddItemToObject(tombstone,"fields",fields)){cJSON_Delete(fields);ret=-ENOMEM;goto done;}
    if(!cJSON_AddNumberToObject(tombstone,"version",1)||!cJSON_AddStringToObject(tombstone,"id",text(payload,"card_id"))||
      !cJSON_AddStringToObject(tombstone,"kind","card")||!cJSON_AddBoolToObject(tombstone,"deleted",true)){ret=-ENOMEM;goto done;}
    document=tombstone;
  }else {
    if(embedded_nul(input->raw,input->length)||!cJSON_IsObject(payload)||cJSON_GetArraySize(payload)!=4||
      strcmp(text(payload,"id"),text(document,"id"))||!cJSON_IsNumber(clock)||!isfinite(clock->valuedouble)||
      clock->valuedouble!=(double)buffer_crdt_clock(document)||
      (card&&strcmp(text(document,"kind"),"card"))||(calendar&&strcmp(text(document,"kind"),"calendar")))goto done;
    const char *audience=text(payload,"audience");
    if(strcmp(audience,"local")&&strcmp(audience,"unknown")&&!buffer_source_peer_valid(audience))goto done;
  }
  ret=buffer_source_view_validate(document);if(ret==-EINVAL){ret=0;goto done;}if(ret)goto done;
  /* Fetch released the remote lock. Local view reads retain the existing
   * capture -> local journal -> remote journal order. Never wait behind a
   * model request with this worker's shutdown blocked on the capture lock. */
  int locked=pthread_mutex_trylock(&g_buffer_capture_lock);
  if(locked){ret=locked==EBUSY?-EAGAIN:-locked;goto done;}
  ret=buffer_source_view_publish(input->peer,document,input->applied);
  if(ret==1)buffer_agent_projection_changed();
  pthread_mutex_unlock(&g_buffer_capture_lock);
  if(ret==-EEXIST){*state=BUFFER_SOURCE_VIEW_CONFLICT;buffer_set_status("部分历史记录存在冲突，已保留原文");ret=0;}
  else if(ret==-EFBIG){*state=BUFFER_SOURCE_VIEW_UNSUPPORTED;buffer_set_status("部分历史内容超过显示范围，已保留原文");ret=0;}
  else if(ret==-EINVAL){*state=BUFFER_SOURCE_VIEW_INVALID;ret=0;}
  else if(ret>=0){*state=BUFFER_SOURCE_VIEW_APPLIED;if(ret==1)buffer_calendar_refresh();ret=0;}
done:
  cJSON_Delete(tombstone);cJSON_Delete(envelope);return ret;
}
static int step(void)
{
  if(!buffer_oobe_setup_complete())return 0;
  if(buffer_voice_foreground_busy())return -EAGAIN;
  static int cleanup_failure;
  int maintenance=buffer_source_view_cleanup();
  if(maintenance!=cleanup_failure){if(maintenance)buffer_set_status("旧历史视图暂未清理，保留原文并继续恢复");cleanup_failure=maintenance;}
  int ret=0;
  bool needed=false,building=false;ret=buffer_source_view_rebuild_needed(&needed,&building);if(ret)return ret;
  if(!view_initialized||(needed&&!building)){
    if(needed||building){ret=buffer_source_remote_projection_restart();if(!ret)ret=buffer_source_view_rebuild_begin();if(ret)return ret;}
    view_initialized=true;
  }
  int locked=pthread_mutex_trylock(&g_buffer_capture_lock);
  if(locked)return locked==EBUSY?-EAGAIN:-locked;
  int local=0;
  if(local_turn)local=buffer_source_local_repair_step(&local_cursor);
  if(local==2){buffer_agent_projection_changed();local=1;}
  local_turn=!local_turn;
  pthread_mutex_unlock(&g_buffer_capture_lock);
  if(local)return local;
  struct buffer_source_projection_input_s input;ret=buffer_source_remote_projection_next(&input);
  if(!ret){
    ret=buffer_source_view_rebuild_needed(&needed,&building);if(ret)return ret;
    if(!building)return 0;
    ret=buffer_source_view_rebuild_finish();if(ret<0)return ret;
    if(ret==1){buffer_agent_projection_changed();buffer_calendar_invalidate();return 1;}
    ret=buffer_source_remote_projection_restart();if(!ret)ret=buffer_source_view_rebuild_begin();return ret?ret:1;
  }
  if(ret!=1)return ret;
  char causal[24];const char *state=NULL;
  ret=buffer_source_remote_causal_check(&input,causal);
  if(!ret)ret=buffer_source_view_rebuild_needed(&needed,&building);
  bool restarted=false;
  if(!ret&&needed&&!building){ret=buffer_source_remote_projection_restart();if(!ret)ret=buffer_source_view_rebuild_begin();restarted=!ret;}
  if(!ret&&!restarted){
    if(!strcmp(causal,"invalid_clock")||!strcmp(causal,"blocked_parent"))state="v3_blocked_parent";
    else if(!strcmp(causal,"unsupported_schema"))state=BUFFER_SOURCE_VIEW_UNSUPPORTED;
    else if(!strcmp(input.projection_state,BUFFER_SOURCE_VIEW_UNSUPPORTED)||!strcmp(input.projection_state,BUFFER_SOURCE_VIEW_INVALID)||
      (!building&&!strcmp(input.projection_state,BUFFER_SOURCE_VIEW_CONFLICT)))state=input.projection_state;
    else ret=project(&input,&state);
    /* Self-contained CRDT observations may form a restricted view while
     * ancestors are withheld. This never verifies the missing chain or runs
     * a historical action, and later imports trigger another assessment. */
    if(!ret&&state&&!strcmp(state,BUFFER_SOURCE_VIEW_APPLIED)&&
      (!strcmp(causal,"missing_parent")||!strcmp(causal,"waiting_parent")))state="v3_partial";
  }
  if(!ret&&!restarted)ret=buffer_source_remote_projection_complete(&input,state);
  if(input.raw){volatile char *p=input.raw;size_t n=input.length;while(n--)*p++=0;free(input.raw);}
  return ret?ret:1;
}
static void *run(void *unused)
{
  (void)unused;int failure=0;
  for(;;){pthread_mutex_lock(&lock);bool quit=stopping;pthread_mutex_unlock(&lock);if(quit)break;
    int ret=step();unsigned delay=ret==1||ret==-EAGAIN?100:ret<0?30000:5000;
    if(ret<0&&ret!=-EAGAIN){if(ret!=failure)buffer_set_status("部分历史记录暂未恢复，稍后重试");failure=ret;}else failure=0;
    struct timespec until;if(clock_gettime(CLOCK_MONOTONIC,&until)){usleep(100000);continue;}
    until.tv_sec+=delay/1000;until.tv_nsec+=(long)(delay%1000)*1000000L;
    if(until.tv_nsec>=1000000000L){until.tv_sec++;until.tv_nsec-=1000000000L;}
    pthread_mutex_lock(&lock);while(!stopping)if(pthread_cond_clockwait(&wake,&lock,CLOCK_MONOTONIC,&until))break;
    pthread_mutex_unlock(&lock);
  }
  return NULL;
}
int buffer_source_projector_start(void)
{
  pthread_mutex_lock(&lock);if(started){pthread_mutex_unlock(&lock);return 0;}stopping=false;view_initialized=false;
  pthread_attr_t attr;int ret=pthread_attr_init(&attr);
  if(!ret){ret=pthread_attr_setstacksize(&attr,32768);if(!ret)ret=pthread_create(&thread,&attr,run,NULL);pthread_attr_destroy(&attr);}
  if (!ret)
    started = true;
  pthread_mutex_unlock(&lock);
  return ret ? -ret : 0;
}
void buffer_source_projector_stop(void)
{
  pthread_mutex_lock(&lock);bool join=started;stopping=true;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);
  if (join)
    pthread_join(thread, NULL);
  pthread_mutex_lock(&lock);
  started = false;
  pthread_mutex_unlock(&lock);
}
#else
int buffer_source_projector_start(void){return 0;}
void buffer_source_projector_stop(void){}
#endif
