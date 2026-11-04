/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_focus.h"
#include "buffer_a2ui.h"
#include "buffer_source_events.h"
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define FOCUS_PATH CONFIG_BUFFER_VELA_DATA_DIR "/focus.json"
static pthread_mutex_t focus_lock = PTHREAD_MUTEX_INITIALIZER;
static const char *string(const cJSON *value, const char *key)
{
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(value, key);
  return cJSON_IsString(item) ? item->valuestring : "";
}
static double timestamp(const cJSON *value, const char *key)
{
  const cJSON *item = cJSON_GetObjectItemCaseSensitive(value, key);
  return cJSON_IsNumber(item) ? item->valuedouble : -1;
}
static bool one_of(const char *value, const char *a, const char *b, const char *c)
{ return !strcmp(value,a) || !strcmp(value,b) || !strcmp(value,c); }
static int set_value(cJSON *object,const char *key,cJSON *value)
{
  if(!value)return -ENOMEM;
  bool ok=cJSON_HasObjectItem(object,key)?cJSON_ReplaceItemInObjectCaseSensitive(object,key,value):cJSON_AddItemToObject(object,key,value);
  if(!ok)cJSON_Delete(value);
  return ok?0:-ENOMEM;
}

int buffer_focus_validate(const cJSON *s, const char *mode)
{
  if (!s || cJSON_IsNull(s)) return 0;
  if (!cJSON_IsObject(s) || cJSON_GetArraySize(s) != 12 ||
      !buffer_agent_valid_id(string(s,"id")) || !string(s,"title")[0] ||
      strlen(string(s,"title")) > 160 || strlen(string(s,"remaining")) > 1000 ||
      (string(s,"card_id")[0] && !buffer_agent_valid_id(string(s,"card_id"))) ||
      strlen(string(s,"calendar_event_id")) > 80 ||
      !one_of(string(s,"status"),"active","review_pending","reviewed")) return -EINVAL;
  const char *fields[] = {"started_at","ended_at","updated_at"};
  for (unsigned i=0;i<3;i++)
    {
      double n=timestamp(s,fields[i]);
      if (!isfinite(n) || n<0 || n>9007199254740991.0 || floor(n)!=n) return -EINVAL;
    }
  bool active = !strcmp(string(s,"status"),"active");
  if (timestamp(s,"started_at") <= 0 || timestamp(s,"updated_at") < timestamp(s,"started_at") ||
      (active ? timestamp(s,"ended_at") != 0 : timestamp(s,"ended_at") < timestamp(s,"started_at")) ||
      (!active && timestamp(s,"updated_at") < timestamp(s,"ended_at")) ||
      (mode && active != !strcmp(mode,"focus"))) return -EINVAL;
  if (string(s,"progress")[0] && !one_of(string(s,"progress"),"done","partial","not_started")) return -EINVAL;
  if (string(s,"experience")[0] && !one_of(string(s,"experience"),"good","tired","hard")) return -EINVAL;
  if (!strcmp(string(s,"status"),"reviewed") && !string(s,"progress")[0]) return -EINVAL;
  const char *texts[]={"id","title","card_id","calendar_event_id","status","progress","experience","remaining"};
  for (unsigned i=0;i<8;i++)
    if (!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(s,texts[i]))) return -EINVAL;
  const cJSON *duration=cJSON_GetObjectItemCaseSensitive(s,"planned_minutes");
  if (!cJSON_IsNumber(duration) || duration->valuedouble<0 || duration->valuedouble>1440 ||
      floor(duration->valuedouble)!=duration->valuedouble) return -EINVAL;
  return 0;
}

static void publish(const cJSON *session)
{
  pthread_mutex_lock(&g_buffer.lock);
  snprintf(g_buffer.focus_title,sizeof(g_buffer.focus_title),"%s",string(session,"title"));
  pthread_mutex_unlock(&g_buffer.lock);
}
static cJSON *load(void)
{
  errno=0;
  cJSON *state=buffer_agent_read_json(FOCUS_PATH,BUFFER_AGENT_JSON_BYTES);
  if (!state && errno==ENOENT)
    {
      state=cJSON_CreateObject();
      if(!state||set_value(state,"operations",cJSON_CreateArray())){cJSON_Delete(state);errno=ENOMEM;return NULL;}
    }
  if(state&&(!cJSON_IsObject(state)||!cJSON_IsArray(cJSON_GetObjectItemCaseSensitive(state,"operations"))||
      buffer_focus_validate(cJSON_GetObjectItemCaseSensitive(state,"session"),NULL))){cJSON_Delete(state);errno=EINVAL;return NULL;}
  if(state){
    const cJSON *item;cJSON_ArrayForEach(item,cJSON_GetObjectItemCaseSensitive(state,"operations"))
      if(!cJSON_IsString(item)||!buffer_agent_valid_id(item->valuestring)){cJSON_Delete(state);errno=EINVAL;return NULL;}
    if(string(state,"pending_operation")[0]&&!cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(state,"session")))
      {cJSON_Delete(state);errno=EINVAL;return NULL;}
  }
  return state;
}
cJSON *buffer_focus_session_snapshot(void)
{
  pthread_mutex_lock(&focus_lock);
  cJSON *state=load();
  const cJSON *stored=cJSON_GetObjectItemCaseSensitive(state,"session");
  cJSON *session=cJSON_IsObject(stored)?cJSON_Duplicate(stored,true):NULL;
  int error=!state?(errno?errno:EIO):cJSON_IsObject(stored)&&!session?ENOMEM:0;
  cJSON_Delete(state);
  pthread_mutex_unlock(&focus_lock);
  errno=error;
  return session;
}
int buffer_focus_import(const cJSON *session)
{
  int ret=buffer_focus_validate(session,NULL);
  if (ret || !session || cJSON_IsNull(session)) return ret;
  pthread_mutex_lock(&focus_lock);
  cJSON *state=load();
  if (!state) ret=-(errno?errno:EIO);
  if (!ret)
    {
      cJSON *old=cJSON_GetObjectItemCaseSensitive(state,"session");
      bool prompt=old && !strcmp(string(old,"id"),string(session,"id")) &&
          !strcmp(string(old,"status"),"active") && !strcmp(string(session,"status"),"review_pending");
      /* Equal timestamps must identify the same value. Do not regress a
       * locally newer review when an old transport snapshot is retried. */
      if (old && timestamp(old,"updated_at") > timestamp(session,"updated_at")) ret=-EAGAIN;
      else if (old && timestamp(old,"updated_at")==timestamp(session,"updated_at") &&
               !cJSON_Compare(old,session,true)) ret=-EAGAIN;
      else
        {
          bool changed=!old||!cJSON_Compare(old,session,true);
          ret=set_value(state,"session",cJSON_Duplicate(session,true));
          if(!ret&&changed)cJSON_DeleteItemFromObjectCaseSensitive(state,"pending_operation");
          if(!ret)ret=buffer_agent_atomic_json(FOCUS_PATH,state);
          if (!ret) {
              publish(session);
              if (prompt) (void)buffer_a2ui_present_reply(string(session,"id"),
                  "这段专注已结束。事情推进得怎么样？现在感觉如何？可以按住录音告诉我，也可以稍后回顾。");
          }
        }
    }
  cJSON_Delete(state); pthread_mutex_unlock(&focus_lock); return ret;
}

cJSON *buffer_focus_execute(const cJSON *args, const char *operation)
{
  cJSON *reply=cJSON_CreateObject();
  if (!reply) return NULL;
  cJSON *saved_request=NULL,*saved_result=NULL;
  int ret=-EINVAL;
  bool duplicate=false;
  if (!cJSON_IsObject(args) || !buffer_agent_valid_id(operation)) goto done;
  const char *action=string(args,"action");
  if (!one_of(action,"start","finish","review")) goto done;
  const cJSON *field;
  cJSON_ArrayForEach(field,args)
    if (!cJSON_IsString(field) || !field->string || (!one_of(field->string,"action","title","session_id") &&
        !one_of(field->string,"progress","experience","remaining"))) goto done;
  if (strlen(string(args,"title"))>160 || strlen(string(args,"remaining"))>1000 ||
      (string(args,"progress")[0] && !one_of(string(args,"progress"),"done","partial","not_started")) ||
      (string(args,"experience")[0] && !one_of(string(args,"experience"),"good","tired","hard"))) goto done;
  ret=buffer_agent_store_init(); if (ret) goto done;
  pthread_mutex_lock(&focus_lock);
  cJSON *state=load(), *session=NULL;
  if (!state) { ret=-(errno?errno:EIO); goto unlock; }
  cJSON *operations=cJSON_GetObjectItemCaseSensitive(state,"operations"), *item;
  if (!cJSON_IsArray(operations)) { ret=-EPROTO; goto unlock; }
  int found=buffer_source_focus_operation(operation,&saved_request,&saved_result);
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  const bool journal=true;
#else
  const bool journal=false;
#endif
  if(!found){
    if(!cJSON_Compare(saved_request,args,true)){ret=-EEXIST;goto unlock;}
    if(buffer_focus_validate(saved_result,NULL)){ret=-EPROTO;goto unlock;}
    duplicate=true;
  } else if(found!=-ENOENT&&(journal||found!=-ENOTSUP)){ret=found;goto unlock;}
  cJSON_ArrayForEach(item,operations)
    if (cJSON_IsString(item) && !strcmp(item->valuestring,operation)) {
      if(!duplicate){
        const cJSON *request=cJSON_GetObjectItemCaseSensitive(state,"last_request");
        if(journal||strcmp(string(state,"last_operation"),operation)||!cJSON_IsObject(request)){ret=-ESTALE;goto unlock;}
        if(!cJSON_Compare(request,args,true)){ret=-EEXIST;goto unlock;}
        saved_result=cJSON_Duplicate(cJSON_GetObjectItemCaseSensitive(state,"last_result"),true);
        if(!saved_result){ret=-ENOMEM;goto unlock;}
        if(buffer_focus_validate(saved_result,NULL)){ret=-EPROTO;goto unlock;}
        duplicate=true;
      }
    }
  if(duplicate&&strcmp(string(state,"last_operation"),operation)){ret=0;goto respond;}
  session=cJSON_GetObjectItemCaseSensitive(state,"session");
  if (!duplicate)
    {
      if (!journal&&cJSON_GetArraySize(operations)>=BUFFER_AGENT_RECORDS_LIMIT) {ret=-ENOSPC;goto unlock;}
      double now=(double)time(NULL)*1000;
      if (now<1700000000000.0) {ret=-EAGAIN;goto unlock;}
      if (session && now<=timestamp(session,"updated_at")) now=timestamp(session,"updated_at")+1;
      if (!strcmp(action,"start"))
        {
          if (!string(args,"title")[0]) {ret=-EINVAL;goto unlock;}
          if (session && !strcmp(string(session,"status"),"active")) {ret=-EBUSY;goto unlock;}
          session=cJSON_CreateObject();
          if(!session){ret=-ENOMEM;goto unlock;}
          bool built=cJSON_AddStringToObject(session,"id",operation)&&cJSON_AddStringToObject(session,"title",string(args,"title"));
          const char *empty[]={"card_id","calendar_event_id","progress","experience","remaining"};
          for (unsigned i=0;built&&i<5;i++)built=cJSON_AddStringToObject(session,empty[i],"")!=NULL;
          built=built&&cJSON_AddStringToObject(session,"status","active")&&cJSON_AddNumberToObject(session,"started_at",now)&&
            cJSON_AddNumberToObject(session,"ended_at",0)&&cJSON_AddNumberToObject(session,"updated_at",now)&&cJSON_AddNumberToObject(session,"planned_minutes",0);
          if(!built){cJSON_Delete(session);session=NULL;ret=-ENOMEM;goto unlock;}
          ret=set_value(state,"session",session);if(ret){session=NULL;goto unlock;}
        }
      else
        {
          if (!session || !string(args,"session_id")[0] ||
              strcmp(string(args,"session_id"),string(session,"id"))) {ret=-ESTALE;goto unlock;}
          if (!strcmp(action,"finish"))
            {
              if (strcmp(string(session,"status"),"active")) {ret=-ESTALE;goto unlock;}
              ret=set_value(session,"ended_at",cJSON_CreateNumber(now));if(ret)goto unlock;
              ret=set_value(session,"status",cJSON_CreateString("review_pending"));if(ret)goto unlock;
            }
          else if (!strcmp(string(session,"status"),"active") || !string(args,"progress")[0])
            {ret=-EINVAL;goto unlock;}
          const char *feedback[]={"progress","experience","remaining"};
          for (unsigned i=0;i<3;i++) if (cJSON_GetObjectItemCaseSensitive(args,feedback[i]))
            {ret=set_value(session,feedback[i],cJSON_CreateString(string(args,feedback[i])));if(ret)goto unlock;}
          if (string(session,"progress")[0])
            {ret=set_value(session,"status",cJSON_CreateString("reviewed"));if(ret)goto unlock;}
          ret=set_value(session,"updated_at",cJSON_CreateNumber(now));if(ret)goto unlock;
        }
      ret=buffer_focus_validate(session,NULL); if (ret) goto unlock;
      cJSON *entry=cJSON_CreateString(operation);
      if(!entry||!cJSON_AddItemToArray(operations,entry)){cJSON_Delete(entry);ret=-ENOMEM;goto unlock;}
      if(journal)while(cJSON_GetArraySize(operations)>32)cJSON_DeleteItemFromArray(operations,0);
      ret=set_value(state,"last_request",cJSON_Duplicate(args,true));if(ret)goto unlock;
      ret=set_value(state,"last_result",cJSON_Duplicate(session,true));if(ret)goto unlock;
      ret=set_value(state,"last_operation",cJSON_CreateString(operation));if(ret)goto unlock;
      ret=set_value(state,"pending_operation",cJSON_CreateString(operation));if(ret)goto unlock;
      ret=buffer_source_projection_json(FOCUS_PATH,state,"FocusSessionChanged",NULL); if (ret) goto unlock;
    }
  /* Reconcile only the latest durable intent. A replay of an older capture
   * must not reset the mode or elapsed time of a newer session. */
  ret=0;
  if (!strcmp(string(state,"pending_operation"),operation))
    {
      const char *mode=!strcmp(string(session,"status"),"active")?"focus":"normal";
      pthread_mutex_lock(&g_buffer.lock);
      bool already=!strcmp(g_buffer.mode,mode) && g_buffer.mode_dirty;
      pthread_mutex_unlock(&g_buffer.lock);
      if (!already) buffer_request_mode(mode);
      pthread_mutex_lock(&g_buffer.lock);
      bool applied=!strcmp(g_buffer.mode,mode) && g_buffer.mode_dirty;
      pthread_mutex_unlock(&g_buffer.lock);
      if (!applied) ret=-EIO;
      if (!ret) {
          cJSON_DeleteItemFromObjectCaseSensitive(state,"pending_operation");
          ret=buffer_source_projection_json(FOCUS_PATH,state,"FocusModeRequestRecorded",NULL);
      }
    }
  if (!ret)
    {
      publish(session);
    }
respond:
  if(!ret){
    ret=set_value(reply,"session",cJSON_Duplicate(duplicate?saved_result:session,true));
    if(!ret&&!cJSON_AddBoolToObject(reply,"duplicate",duplicate))ret=-ENOMEM;
  }
unlock:
  cJSON_Delete(state);pthread_mutex_unlock(&focus_lock);
done:
  cJSON_Delete(saved_request);cJSON_Delete(saved_result);
  if(!cJSON_AddBoolToObject(reply,"success",ret==0)||(ret&&!cJSON_AddStringToObject(reply,"error",buffer_agent_error_code(ret))))
    {cJSON_Delete(reply);return NULL;}
  return reply;
}

/* The mode menu uses the same domain transitions as voice, so a manual
 * session can subsequently be finished or reviewed by the assistant. */
void buffer_focus_request_mode(const char *mode)
{
  cJSON *session=buffer_focus_session_snapshot();
  if(!session&&errno){buffer_set_status("专注状态暂时无法读取，请重试");return;}
  bool active=session && !strcmp(string(session,"status"),"active");
  bool start=!strcmp(mode,"focus") && !active;
  bool finish=strcmp(mode,"focus") && active;
  if (!start && !finish) {cJSON_Delete(session);buffer_request_mode(mode);return;}
  char operation[BUFFER_ACTION_ID_SIZE];
  uint32_t random[2];
  arc4random_buf(random,sizeof(random));
  snprintf(operation,sizeof(operation),"focus-menu-%08lx-%08lx",
      (unsigned long)random[0],(unsigned long)random[1]);
  cJSON *args=cJSON_CreateObject();
  bool built=args&&cJSON_AddStringToObject(args,"action",start ? "start":"finish")&&
    (start?cJSON_AddStringToObject(args,"title","专注"):cJSON_AddStringToObject(args,"session_id",string(session,"id")));
  if(!built){cJSON_Delete(args);cJSON_Delete(session);buffer_set_status("专注状态未保存，请重试");return;}
  cJSON *result=buffer_focus_execute(args,operation);
  bool success=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(result,"success"));
  cJSON_Delete(session);cJSON_Delete(args);cJSON_Delete(result);
  if (!success) {buffer_set_status("专注状态未保存，请重试");return;}
  if (strcmp(mode,"focus") && strcmp(mode,"normal")) buffer_request_mode(mode);
}
