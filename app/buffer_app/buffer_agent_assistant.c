/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent_assistant.h"
#include "buffer_followup.h"
#include "buffer_focus.h"
#include "buffer_assistant.h"
#include "buffer_a2ui_internal.h"
#include "buffer_images.h"
#include <mbedtls/sha256.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>

/* These tools expose only the local device domain. No arbitrary commands,
 * filesystem paths, external writes, or generated executable code are allowed. */
static const char definitions[] =
"["
"{\"type\":\"function\",\"function\":{\"name\":\"focus_session\",\"description\":\"开始、结束专注或补充结束反馈。我要开始写作业了/开始工作等当前开始意图应start，title保留用户目标；不用追问时长。finish/review必须用focus_session事实中的真实session_id，不能杜撰。结束可直接附带用户明确表达的进展(done/partial/not_started)、体验(good/tired/hard)、剩余工作；没有表达的不猜测。review用于已结束会话。只有用户明确说全部做完才done；不改日历或自动完成待办。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"start\",\"finish\",\"review\"]},\"title\":{\"type\":\"string\",\"maxLength\":50},\"session_id\":{\"type\":\"string\"},\"progress\":{\"type\":\"string\",\"enum\":[\"done\",\"partial\",\"not_started\"]},\"experience\":{\"type\":\"string\",\"enum\":[\"good\",\"tired\",\"hard\"]},\"remaining\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"action\"],\"additionalProperties\":false}}},"

"{\"type\":\"function\",\"function\":{\"name\":\"show_answer\",\"description\":\"显示常识问答或能力限制说明。不能用于创建、启动、暂停、取消设备任务或翻译、菜谱；这些必须调用各自专用工具，不能以文本冒充执行。缺少参数时改用ask_user。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"text\"],\"additionalProperties\":false}}},"
"{\"type\":\"function\",\"function\":{\"name\":\"ask_user\",\"description\":\"缺少必要参数时向用户提出具体澄清问题，不创建任何任务。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"text\"],\"additionalProperties\":false}}},"
"{\"type\":\"function\",\"function\":{\"name\":\"device_timer\",\"description\":\"在本Vela设备创建、暂停、继续或取消倒计时。仅在用户明确要求计时时调用；制作计时器原型属于Buffer记录。明确三分钟用180000毫秒。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"create\",\"pause\",\"resume\",\"cancel\"]},\"label\":{\"type\":\"string\",\"maxLength\":30},\"duration_ms\":{\"type\":\"integer\",\"minimum\":1000,\"maximum\":86400000},\"object_id\":{\"type\":\"string\",\"maxLength\":63}},\"required\":[\"action\"],\"additionalProperties\":false}}},"
"{\"type\":\"function\",\"function\":{\"name\":\"device_alarm\",\"description\":\"在本Vela设备创建或取消一次闹钟。创建需要明确的未来绝对Unix毫秒时间；缺日期、时区或钟点时先澄清。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"create\",\"cancel\"]},\"label\":{\"type\":\"string\",\"maxLength\":30},\"scheduled_at_unix_ms\":{\"type\":\"integer\",\"minimum\":1700000000000},\"object_id\":{\"type\":\"string\",\"maxLength\":63}},\"required\":[\"action\"],\"additionalProperties\":false}}},"
"{\"type\":\"function\",\"function\":{\"name\":\"show_translation\",\"description\":\"显示用户要求的短文翻译，保留原文、译文和语言。不保存为Buffer收藏。每段最多80个汉字。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"original\":{\"type\":\"string\",\"maxLength\":80},\"translated\":{\"type\":\"string\",\"maxLength\":80},\"source_language\":{\"type\":\"string\",\"maxLength\":16},\"target_language\":{\"type\":\"string\",\"maxLength\":16}},\"required\":[\"original\",\"translated\",\"source_language\",\"target_language\"],\"additionalProperties\":false}}},"
"{\"type\":\"function\",\"function\":{\"name\":\"show_recipe\",\"description\":\"展示菜谱全屏步骤，用户点击步骤计时才开始，不自动创建所有计时器。不保存为Buffer收藏。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"maxLength\":30},\"ingredients\":{\"type\":\"string\",\"maxLength\":80},\"steps\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\",\"maxLength\":30},\"detail\":{\"type\":\"string\",\"maxLength\":80},\"timer_ms\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":86400000}},\"required\":[\"title\",\"detail\",\"timer_ms\"],\"additionalProperties\":false}}},\"required\":[\"title\",\"ingredients\",\"steps\"],\"additionalProperties\":false}}}"
"]";

cJSON *buffer_agent_assistant_tools(void)
{
 cJSON *tools=cJSON_Parse(definitions),*search=cJSON_Parse("{\"type\":\"function\",\"function\":{\"name\":\"image_search\",\"description\":\"通过澄曜百度图片搜索查找对应菜品或主题的真实图片。仅查询必要主题词，不发送整段私人记录。将返回的image_id交给show_recipe；不要编造图片地址。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":64},\"page\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}},\"required\":[\"query\"],\"additionalProperties\":false}}}");
 if(!tools||!search){cJSON_Delete(tools);cJSON_Delete(search);return NULL;}
 for(int i=0;i<cJSON_GetArraySize(tools);i++){
  cJSON *fn=cJSON_GetObjectItemCaseSensitive(cJSON_GetArrayItem(tools,i),"function");
  cJSON *name=cJSON_GetObjectItemCaseSensitive(fn,"name");
  if(cJSON_IsString(name)&&!strcmp(name->valuestring,"show_recipe")){
   cJSON *properties=cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(fn,"parameters"),"properties");
   cJSON *image=cJSON_CreateObject();if(!image){cJSON_Delete(tools);cJSON_Delete(search);return NULL;}
   cJSON_AddStringToObject(image,"type","string");cJSON_AddNumberToObject(image,"maxLength",64);
   cJSON_AddStringToObject(image,"description","本次image_search真实返回的image_id；搜索失败时可省略配图。");cJSON_AddItemToObject(properties,"image_id",image);
  }
 }
 cJSON_AddItemToArray(tools,search);return tools;
}
bool buffer_agent_assistant_tool_known(const char *name)
{ return !strcmp(name,"image_search") || !strcmp(name,"device_timer") || !strcmp(name,"device_alarm") ||
    !strcmp(name,"show_translation") || !strcmp(name,"show_recipe") ||
    !strcmp(name,"show_answer") || !strcmp(name,"ask_user") || !strcmp(name,"focus_session"); }
static const char *text(const cJSON *value,const char *name)
{ const cJSON *item=cJSON_GetObjectItemCaseSensitive(value,name); return cJSON_IsString(item) ? item->valuestring : ""; }
static bool copy(char *out,size_t size,const cJSON *value,const char *name,bool required)
{ const char *s=text(value,name); size_t n=strlen(s); if ((required && !n)||n>=size) return false;
  memcpy(out,s,n+1); return true; }
static bool number(const cJSON *value,const char *name,double minimum,double maximum,double *out)
{ const cJSON *item=cJSON_GetObjectItemCaseSensitive(value,name);
  if (!cJSON_IsNumber(item)||!isfinite(item->valuedouble)||floor(item->valuedouble)!=item->valuedouble||
      item->valuedouble<minimum||item->valuedouble>maximum) return false;
  *out=item->valuedouble; return true; }

cJSON *buffer_agent_assistant_tool(const char *name,const cJSON *arguments,
                                   const char *capture_id,unsigned slot)
{
  struct buffer_assistant_request_s *request=calloc(1,sizeof(*request));
  cJSON *reply=cJSON_CreateObject();
  if (!reply || !request) { free(request); cJSON_Delete(reply); return NULL; }
  int ret=-EINVAL;
  if (!cJSON_IsObject(arguments) || !buffer_agent_valid_id(capture_id) || slot>=8) goto done;
  if(!strcmp(name,"image_search")){
    double page=1;const cJSON *provided=cJSON_GetObjectItemCaseSensitive(arguments,"page");
    if(cJSON_GetArraySize(arguments)!=(provided?2:1)||(provided&&!number(arguments,"page",1,10,&page)))goto done;
    cJSON *images=buffer_images_search(capture_id,text(arguments,"query"),(unsigned)page);
    free(request);cJSON_Delete(reply);return images;
  }
  ret=buffer_a2ui_capture_context_begin(capture_id);if(ret)goto done;ret=-EINVAL;
  unsigned char hash[32]; char identity[128];
  int n=snprintf(identity,sizeof(identity),"%s:%u",capture_id,slot);
  if (n<0 || (size_t)n>=sizeof(identity) || mbedtls_sha256((unsigned char *)identity,n,hash,0)) goto done;
  strcpy(request->operation_id,"agent-");
  for (int i=0;i<16;i++) snprintf(request->operation_id+6+i*2,3,"%02x",hash[i]);
  if (!strcmp(name,"focus_session"))
    {
      cJSON_Delete(reply);
      reply=buffer_focus_execute(arguments,request->operation_id);
      if (reply && cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(reply,"success")) &&
          !cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(reply,"duplicate"))) {
          const cJSON *session=cJSON_GetObjectItemCaseSensitive(reply,"session");
          const char *state=text(session,"status");
          char message[512];
          snprintf(message,sizeof(message),"%s\n%s",text(session,"title"),
              !strcmp(state,"active") ? "已开始专注。结束时可以告诉我进展和感受。" :
              !strcmp(state,"review_pending") ? "已结束专注。事情推进得怎么样？现在感觉如何？也可以稍后再说。" : "已保存这次专注反馈。");
          (void)buffer_a2ui_present_reply(request->operation_id,message);
      }
      buffer_a2ui_capture_context_end();memset(request,0,sizeof(*request));free(request);return reply;
    }
  if (!strcmp(name,"show_answer") || !strcmp(name,"ask_user"))
    {
      const char *answer=text(arguments,"text");
      if (!answer[0] || strlen(answer)>BUFFER_A2UI_REPLY_TEXT_MAX || cJSON_GetArraySize(arguments)!=1) goto done;
      ret=buffer_followup_answer(capture_id,answer);
      if (ret==1) ret=buffer_a2ui_present_reply(request->operation_id,answer);
      goto done;
    }
  const char *action=text(arguments,"action"); double value;
  if (!strcmp(name,"device_timer"))
    {
      if (!copy(request->label,sizeof(request->label),arguments,"label",false) ||
          !copy(request->object_id,sizeof(request->object_id),arguments,"object_id",false)) goto done;
      if (!strcmp(action,"create"))
        { request->operation=BUFFER_ASSISTANT_TIMER_CREATE;
          if (!number(arguments,"duration_ms",1000,86400000,&value)) goto done;
          request->duration_ms=(uint32_t)value;
          if (!request->label[0]) strcpy(request->label,"计时器"); }
      else if (!strcmp(action,"pause")) request->operation=BUFFER_ASSISTANT_TIMER_PAUSE;
      else if (!strcmp(action,"resume")) request->operation=BUFFER_ASSISTANT_TIMER_RESUME;
      else if (!strcmp(action,"cancel")) request->operation=BUFFER_ASSISTANT_TIMER_CANCEL;
      else goto done;
      if (request->operation!=BUFFER_ASSISTANT_TIMER_CREATE && !request->object_id[0]) goto done;
    }
  else if (!strcmp(name,"device_alarm"))
    {
      if (!copy(request->label,sizeof(request->label),arguments,"label",false) ||
          !copy(request->object_id,sizeof(request->object_id),arguments,"object_id",false)) goto done;
      if (!strcmp(action,"create"))
        { request->operation=BUFFER_ASSISTANT_ALARM_CREATE;
          if (!number(arguments,"scheduled_at_unix_ms",1700000000000.0,9007199254740991.0,&value)) goto done;
          request->scheduled_at_unix_ms=(int64_t)value;
          if (!request->label[0]) strcpy(request->label,"闹钟"); }
      else if (!strcmp(action,"cancel") && request->object_id[0]) request->operation=BUFFER_ASSISTANT_ALARM_CANCEL;
      else goto done;
    }
  else if (!strcmp(name,"show_translation"))
    {
      request->operation=BUFFER_ASSISTANT_TRANSLATION_SHOW;
      if (!copy(request->original,sizeof(request->original),arguments,"original",true) ||
          !copy(request->translated,sizeof(request->translated),arguments,"translated",true) ||
          !copy(request->source_language,sizeof(request->source_language),arguments,"source_language",true) ||
          !copy(request->target_language,sizeof(request->target_language),arguments,"target_language",true)) goto done;
    }
  else if (!strcmp(name,"show_recipe"))
    {
      request->operation=BUFFER_ASSISTANT_RECIPE_SHOW;
      if (!copy(request->recipe_title,sizeof(request->recipe_title),arguments,"title",true) ||
          !copy(request->ingredients,sizeof(request->ingredients),arguments,"ingredients",true)||
          !copy(request->image_id,sizeof(request->image_id),arguments,"image_id",false)) goto done;
      if(request->image_id[0]&&(ret=buffer_images_select(capture_id,request->image_id)))goto done;
      ret=-EINVAL;
      const cJSON *steps=cJSON_GetObjectItemCaseSensitive(arguments,"steps");
      int count=cJSON_GetArraySize(steps);
      if (!cJSON_IsArray(steps)||count<1||count>BUFFER_ASSISTANT_MAX_RECIPE_STEPS) goto done;
      request->step_count=count;
      for (int i=0;i<count;i++)
        {
          const cJSON *step=cJSON_GetArrayItem(steps,i);
          if (!copy(request->steps[i].title,sizeof(request->steps[i].title),step,"title",true) ||
              !copy(request->steps[i].detail,sizeof(request->steps[i].detail),step,"detail",true) ||
              !number(step,"timer_ms",0,86400000,&value)) goto done;
          request->steps[i].timer_ms=(uint32_t)value;
        }
    }
  else goto done;
  struct buffer_assistant_result_s result;
  /* Return a temporary index wait to the worker. The caller checkpoints this
   * exact tool call and resumes it without another intent request. */
  syslog(LOG_INFO,"Buffer Assistant: capture=%s slot=%u tool=%d begin\n",capture_id,slot,request->operation);
  ret=buffer_assistant_execute(request,&result);
  if(ret==-EAGAIN)buffer_set_status("正在恢复执行记录，随后处理指令…");
  if (!ret)
    {
      if(request->operation==BUFFER_ASSISTANT_RECIPE_SHOW&&request->image_id[0]){
        int image_result=buffer_images_request(capture_id,request->image_id);
        cJSON_AddStringToObject(reply,"image_status",image_result?"unavailable":"queued");
      }
      cJSON_AddStringToObject(reply,"object_id",result.object_id);
      cJSON_AddNumberToObject(reply,"remaining_ms",result.remaining_ms);
      cJSON_AddNumberToObject(reply,"state",result.state);
      cJSON_AddBoolToObject(reply,"duplicate",result.duplicate);
      if(request->operation==BUFFER_ASSISTANT_TIMER_CREATE)
        buffer_set_status("倒计时已创建，请在助手页查看");
      else buffer_set_status("指令已执行，请在助手页查看");
    }
done:
  buffer_a2ui_capture_context_end();
  syslog(LOG_INFO,"Buffer Assistant: capture=%s slot=%u tool=%d result=%d\n",capture_id,slot,request->operation,ret);
  cJSON_AddBoolToObject(reply,"success",ret==0);
  if (ret) cJSON_AddStringToObject(reply,"error",buffer_agent_error_code(ret));
  if(ret==-EAGAIN)cJSON_AddBoolToObject(reply,"retryable",true);
  memset(request,0,sizeof(*request)); free(request); return reply;
}

int buffer_agent_assistant_reply(const char *capture_id,const char *reply)
{
  unsigned char hash[32]; char operation[40]="reply-";
  if (!buffer_agent_valid_id(capture_id) || mbedtls_sha256((const unsigned char *)capture_id,strlen(capture_id),hash,0)) return -EINVAL;
  for (int i=0;i<16;i++) snprintf(operation+6+2*i,3,"%02x",hash[i]);
  int result=buffer_a2ui_capture_context_begin(capture_id);if(result)return result;
  result=buffer_a2ui_present_reply(operation,reply);buffer_a2ui_capture_context_end();return result;
}

/* Resolve only the bounded scalar string data model this catalog implements.
 * Static IDs remain literals; missing bindings fail instead of becoming an
 * invented command. Surface and event strings remain user data to the Agent. */
static cJSON *ui_action_context(const cJSON *value,const struct buffer_a2ui_surface_s *surface,unsigned depth)
{
 if(!value||depth>BUFFER_A2UI_DEPTH_MAX)return NULL;
 if(cJSON_IsObject(value)&&cJSON_HasObjectItem(value,"path")){
  const char *path=text(value,"path");if(cJSON_GetArraySize(value)!=1||!path[0])return NULL;
  for(unsigned i=0;i<surface->data_count;i++)if(!strcmp(surface->data[i].path,path)){
   if(!surface->data[i].json_value)return cJSON_CreateString(surface->data[i].value);
   cJSON *resolved=surface->data[i].value[0]?buffer_agent_parse_json(surface->data[i].value):cJSON_CreateNull();
   if(cJSON_IsNumber(resolved)&&!isfinite(resolved->valuedouble)){cJSON_Delete(resolved);return NULL;}return resolved;
  }
  return NULL;
 }
 if(!cJSON_IsObject(value)&&!cJSON_IsArray(value))return cJSON_Duplicate(value,true);
 cJSON *out=cJSON_IsArray(value)?cJSON_CreateArray():cJSON_CreateObject();if(!out)return NULL;
 for(const cJSON *child=value->child;child;child=child->next){
  cJSON *resolved=ui_action_context(child,surface,depth+1);
  if(!resolved||(cJSON_IsArray(out)?!cJSON_AddItemToArray(out,resolved):!child->string||!cJSON_AddItemToObject(out,child->string,resolved))){cJSON_Delete(resolved);cJSON_Delete(out);return NULL;}
 }
 return out;
}
int buffer_agent_assistant_ui_action(const struct buffer_a2ui_action_s *event,void *context)
{
 (void)context;if(!event||!buffer_agent_valid_id(event->id))return -EINVAL;
 struct buffer_agent_status_s service;buffer_agent_snapshot(&service);
 if(!service.configured||service.skipped)return -ENOTCONN;
 struct buffer_a2ui_surface_s *surface=malloc(sizeof(*surface));if(!surface)return -ENOMEM;
 int result=buffer_a2ui_model_get_surface(event->surface_id,surface);
 const struct buffer_a2ui_component_s *node=result?NULL:buffer_a2ui_find_component(surface,event->component_id);
 if(!result&&(!node||surface->task_revision!=event->revision||surface->sequence!=event->sequence||node->type!=BUFFER_A2UI_BUTTON||strcmp(node->action,event->name)||strcmp(node->context_json,event->context_json)))result=-ESTALE;
 if(result){free(surface);return result;}
 char path[320];result=buffer_agent_record_path(path,sizeof(path),event->id);
 if(result){free(surface);return result;}
 cJSON *existing=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
 if(existing){
  result=strcmp(text(existing,"target"),"assistant")?-EEXIST:0;
  bool complete=!strcmp(text(existing,"state"),"assistant_complete");cJSON_Delete(existing);free(surface);
  if(!result){if(complete)buffer_a2ui_action_complete(event->id,0);else {buffer_agent_prioritize(event->id);buffer_agent_retry();}}return result;
 }
 if(errno!=ENOENT){result=-errno;free(surface);return result;}
 cJSON *raw=buffer_agent_parse_json(event->context_json);
 cJSON *resolved=cJSON_IsObject(raw)?ui_action_context(raw,surface,0):NULL;cJSON_Delete(raw);
 if(!resolved){free(surface);return -EINVAL;}
 cJSON *input=cJSON_CreateObject(),*wire=cJSON_CreateObject(),*action=cJSON_CreateObject(),*source=cJSON_CreateObject();
 if(!input||!wire||!action||!source){cJSON_Delete(input);cJSON_Delete(wire);cJSON_Delete(action);cJSON_Delete(source);cJSON_Delete(resolved);free(surface);return -ENOMEM;}
 cJSON_AddStringToObject(wire,"version","v0.9.1");cJSON_AddItemToObject(wire,"action",action);
 cJSON_AddStringToObject(action,"name",event->name);cJSON_AddStringToObject(action,"surfaceId",event->surface_id);cJSON_AddStringToObject(action,"sourceComponentId",event->component_id);
 char stamp[32];time_t now=time(NULL);struct tm utc;
 if(!gmtime_r(&now,&utc)||!strftime(stamp,sizeof(stamp),"%Y-%m-%dT%H:%M:%SZ",&utc)){cJSON_Delete(input);cJSON_Delete(wire);cJSON_Delete(source);cJSON_Delete(resolved);free(surface);return -EIO;}
 cJSON_AddStringToObject(action,"timestamp",stamp);cJSON_AddItemToObject(action,"context",resolved);
 cJSON_AddStringToObject(input,"input_kind","a2ui_action");cJSON_AddItemToObject(input,"event",wire);cJSON_AddItemToObject(input,"source",source);
 cJSON_AddStringToObject(source,"button_label",buffer_a2ui_component_text(surface,node));cJSON_AddStringToObject(source,"capture_id",surface->capture_id);
 cJSON *visible=cJSON_CreateArray();if(!visible){cJSON_Delete(input);free(surface);return -ENOMEM;}
 cJSON_AddItemToObject(source,"visible_text",visible);
 unsigned bytes=0;for(unsigned i=0;i<surface->component_count;i++)if(surface->components[i].type==BUFFER_A2UI_TEXT){
  const char *line=buffer_a2ui_component_text(surface,&surface->components[i]);size_t length=strlen(line);
  if(length&&bytes+length<=1024){cJSON *v=cJSON_CreateString(line);if(!v){cJSON_Delete(input);free(surface);return -ENOMEM;}cJSON_AddItemToArray(visible,v);bytes+=(unsigned)length;}
 }
 free(surface);char *serialized=cJSON_PrintUnformatted(input);cJSON_Delete(input);if(!serialized)return -ENOMEM;
 result=buffer_agent_submit_assistant_text(event->id,serialized,(int64_t)now*1000);free(serialized);
 if(!result){buffer_agent_prioritize(event->id);buffer_set_status("正在处理你的选择…");}
 return result;
}
