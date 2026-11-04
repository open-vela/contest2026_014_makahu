/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_followup.h"
#include "buffer_replica.h"
#include "buffer_source_events.h"
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define TURNS 8
static pthread_mutex_t conversation_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t revision_lock=PTHREAD_MUTEX_INITIALIZER;
static uint64_t revision;
static void revision_changed(void)
{ pthread_mutex_lock(&revision_lock);revision++;pthread_mutex_unlock(&revision_lock); }
static const char *txt(const cJSON *object,const char *key)
{ const cJSON *value=cJSON_GetObjectItemCaseSensitive(object,key);return cJSON_IsString(value)?value->valuestring:""; }
static int context_path(char *out,size_t size,const char *capture)
{
  if (!buffer_agent_valid_id(capture)) return -EINVAL;
  int n=snprintf(out,size,"%s/records/%s.context",CONFIG_BUFFER_VELA_DATA_DIR,capture);
  return n<0 || (size_t)n>=size ? -EINVAL:0;
}
static int history_path(char *out,size_t size,const char *card)
{
  if (!buffer_agent_valid_id(card)) return -EINVAL;
  int n=snprintf(out,size,"%s/conversations/%s.json",CONFIG_BUFFER_VELA_DATA_DIR,card);
  return n<0 || (size_t)n>=size ? -EINVAL:0;
}
static bool valid_turn(const cJSON *turn,const char *card)
{
  const cJSON *at=cJSON_GetObjectItemCaseSensitive(turn,"created_at");
  return cJSON_IsObject(turn)&&!strcmp(txt(turn,"card_id"),card)&&buffer_agent_valid_id(txt(turn,"capture_id"))&&
    txt(turn,"question")[0]&&strlen(txt(turn,"question"))<=4096&&txt(turn,"answer")[0]&&strlen(txt(turn,"answer"))<=4096&&
    cJSON_IsNumber(at)&&isfinite(at->valuedouble)&&at->valuedouble>=0&&at->valuedouble<=9007199254740991.0&&
    floor(at->valuedouble)==at->valuedouble;
}
static int offer_turn(cJSON *turns,const cJSON *turn,const char *card)
{
  if(!valid_turn(turn,card))return -EINVAL;
  double at=cJSON_GetObjectItemCaseSensitive(turn,"created_at")->valuedouble;int position=0;const cJSON *old;
  cJSON_ArrayForEach(old,turns){
    if(!strcmp(txt(old,"capture_id"),txt(turn,"capture_id")))
      return !strcmp(txt(old,"question"),txt(turn,"question"))&&!strcmp(txt(old,"answer"),txt(turn,"answer"))&&
        cJSON_GetObjectItemCaseSensitive(old,"created_at")->valuedouble==at?0:-EEXIST;
    double previous=cJSON_GetObjectItemCaseSensitive(old,"created_at")->valuedouble;
    if(previous<at||(previous==at&&strcmp(txt(old,"capture_id"),txt(turn,"capture_id"))<0))position++;
  }
  cJSON *copy=cJSON_Duplicate(turn,true);if(!copy)return -ENOMEM;
  if(!cJSON_InsertItemInArray(turns,position,copy)){cJSON_Delete(copy);return -ENOMEM;}
  if(cJSON_GetArraySize(turns)>TURNS)cJSON_DeleteItemFromArray(turns,0);
  return 0;
}
static int offer_context(cJSON *turns,const char *capture,const char *card)
{
  char path[320];if(context_path(path,sizeof(path),capture))return -EINVAL;
  errno=0;cJSON *context=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!context)return errno==ENOENT?0:-(errno?errno:EIO);
  const cJSON *turn=cJSON_GetObjectItemCaseSensitive(context,"result");
  int ret=buffer_agent_valid_id(txt(context,"card_id"))?0:-EINVAL;
  if(!ret&&!strcmp(txt(context,"card_id"),card)&&turn){
    if(strcmp(txt(turn,"capture_id"),capture))ret=-EINVAL;
    else ret=offer_turn(turns,turn,card);
  }
  cJSON_Delete(context);return ret;
}
static int collect_contexts(cJSON *turns,const char *card)
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  char after[BUFFER_ID_SIZE]="";
  for(;;){
    cJSON *ids=NULL;int ret=buffer_source_projection_ids("followup",after,32,&ids);if(ret)return ret;
    unsigned count=(unsigned)cJSON_GetArraySize(ids);const cJSON *id;
    cJSON_ArrayForEach(id,ids){
      ret=offer_context(turns,id->valuestring,card);if(ret){cJSON_Delete(ids);return ret;}
      snprintf(after,sizeof(after),"%s",id->valuestring);
    }
    cJSON_Delete(ids);if(count<32)return 0;
  }
#else
  DIR *dir=opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");if(!dir)return errno==ENOENT?0:-errno;
  struct dirent *entry;int ret=0;
  while((entry=readdir(dir))){
    size_t length=strlen(entry->d_name);char id[BUFFER_ID_SIZE];
    if(length<=8||length-8>=sizeof(id)||strcmp(entry->d_name+length-8,".context"))continue;
    memcpy(id,entry->d_name,length-8);id[length-8]=0;if(!buffer_agent_valid_id(id))continue;
    ret=offer_context(turns,id,card);if(ret)break;
  }
  closedir(dir);return ret;
#endif
}
/* The eight-turn window is a view. Each completed capture keeps its original
 * turn in its own context and, with journal enabled, in immutable history. */
static cJSON *merged_turns(const char *card,const cJSON *local)
{
  errno=0;cJSON *remote=buffer_replica_conversation(card),*result=cJSON_CreateArray();
  int ret=remote?0:errno==ENOENT?0:-(errno?errno:EIO);
  if(!result)ret=-ENOMEM;
  const cJSON *sources[]={local,remote};
  for(unsigned i=0;!ret&&i<2;i++){
    if(!sources[i])continue;
    if(!cJSON_IsArray(sources[i])){ret=-EINVAL;break;}
    const cJSON *turn;cJSON_ArrayForEach(turn,sources[i]){ret=offer_turn(result,turn,card);if(ret)break;}
  }
  if(!ret)ret=collect_contexts(result,card);
  cJSON_Delete(remote);
  if(ret){cJSON_Delete(result);errno=-ret;return NULL;}return result;
}
static int fit_snapshot(cJSON *value,const char *window)
{
  for(;;){
    char *raw=cJSON_PrintUnformatted(value);if(!raw)return -ENOMEM;
    size_t bytes=strlen(raw);free(raw);if(bytes<=BUFFER_AGENT_JSON_BYTES)return 0;
    cJSON *turns=cJSON_GetObjectItemCaseSensitive(value,window);
    if(!cJSON_IsArray(turns)||!cJSON_GetArraySize(turns))return -EFBIG;
    cJSON_DeleteItemFromArray(turns,0);
  }
}
static int save_context(const char *path,const cJSON *context,const char *type)
{
  int ret=buffer_source_projection_json(path,context,type,NULL);
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(ret){
    cJSON *saved=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
    if(saved&&cJSON_Compare(saved,context,true))ret=0;
    cJSON_Delete(saved);
  }
#endif
  return ret;
}
static int existing_context(const cJSON *context,const char *card)
{
  if(!cJSON_IsObject(context)||!buffer_agent_valid_id(txt(context,"card_id"))||
     !cJSON_IsString(cJSON_GetObjectItemCaseSensitive(context,"topic"))||
     !cJSON_IsArray(cJSON_GetObjectItemCaseSensitive(context,"history")))return -EINVAL;
  return strcmp(txt(context,"card_id"),card)?-EEXIST:0;
}
uint64_t buffer_followup_revision(void)
{ pthread_mutex_lock(&revision_lock);uint64_t value=revision;pthread_mutex_unlock(&revision_lock);return value; }
cJSON *buffer_followup_context(const char *capture)
{
  char path[320];if (context_path(path,sizeof(path),capture)) {errno=EINVAL;return NULL;}
  return buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
}
cJSON *buffer_followup_result(const char *capture)
{
  cJSON *context=buffer_followup_context(capture);
  cJSON *result=cJSON_Duplicate(cJSON_GetObjectItemCaseSensitive(context,"result"),true);
  cJSON_Delete(context);return result;
}
int buffer_followup_prepare(const char *capture,const char *card)
{
  char path[320],history[320];
  if (context_path(path,sizeof(path),capture) || history_path(history,sizeof(history),card)) return -EINVAL;
  int ret=buffer_agent_store_init();if (ret) return ret;
  pthread_mutex_lock(&conversation_lock);errno=0;
  cJSON *existing=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  ret=existing?existing_context(existing,card):errno==ENOENT?1:-(errno?errno:EIO);
  cJSON_Delete(existing);pthread_mutex_unlock(&conversation_lock);
  if(ret!=1)return ret;
  errno=0;
  char *topic=buffer_replica_model_detail(card);
  if(topic&&!topic[0]){free(topic);topic=NULL;errno=ENOENT;}
  if(!topic&&errno!=ENOENT)return -(errno?errno:EIO);
  if (!topic)
    {
      struct buffer_card_s value={0};bool found=false;
      pthread_mutex_lock(&g_buffer.lock);
      for (int i=0;i<g_buffer.card_count;i++)
        if (!strcmp(g_buffer.cards[i].card_id,card)) {value=g_buffer.cards[i];found=true;break;}
      pthread_mutex_unlock(&g_buffer.lock);
      if (!found) return -ENOENT;
      char record_path[320];cJSON *record=NULL;
      if (!buffer_agent_record_path(record_path,sizeof(record_path),value.capture_id))
        {
          errno=0;record=buffer_agent_read_json(record_path,BUFFER_AGENT_JSON_BYTES);
          if(!record&&errno!=ENOENT)return -(errno?errno:EIO);
        }
      const cJSON *segment;
      cJSON_ArrayForEach(segment,cJSON_GetObjectItemCaseSensitive(record,"segments"))
        if (!strcmp(txt(segment,"card_id"),card)) {topic=cJSON_PrintUnformatted(segment);break;}
      cJSON_Delete(record);
      if (!topic)
        {
          size_t length=strlen(value.title)+strlen(value.summary)+strlen(value.answer)+8;
          topic=malloc(length);
          if (topic) snprintf(topic,length,"%s\n%s\n%s",value.title,value.summary,value.answer);
        }
      if (!topic) return -ENOMEM;
    }
  /* Keep a complete, immutable topic snapshot; refuse oversized contexts
   * rather than silently creating a context-free question. */
  if (strlen(topic)>24000) { free(topic);return -EFBIG; }
  cJSON *context=cJSON_CreateObject();if (!context) {free(topic);return -ENOMEM;}
  bool built=cJSON_AddStringToObject(context,"card_id",card)&&cJSON_AddStringToObject(context,"topic",topic);free(topic);
  if(!built){cJSON_Delete(context);return -ENOMEM;}
  pthread_mutex_lock(&conversation_lock);
  errno=0;existing=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(existing||errno!=ENOENT){
    ret=existing?existing_context(existing,card):-(errno?errno:EIO);
    cJSON_Delete(existing);cJSON_Delete(context);pthread_mutex_unlock(&conversation_lock);return ret;
  }
  errno=0;
  cJSON *saved=buffer_agent_read_json(history,BUFFER_AGENT_JSON_BYTES);
  ret=saved?0:errno==ENOENT?0:-(errno?errno:EIO);
  if(saved&&!cJSON_IsArray(cJSON_GetObjectItemCaseSensitive(saved,"turns")))ret=-EINVAL;
  cJSON *turns=ret?NULL:merged_turns(card,cJSON_GetObjectItemCaseSensitive(saved,"turns"));
  if(!ret&&!turns)ret=-(errno?errno:ENOMEM);
  cJSON_Delete(saved);
  if(!ret&&!cJSON_AddItemToObject(context,"history",turns)){ret=-ENOMEM;cJSON_Delete(turns);}
  if(!ret)ret=fit_snapshot(context,"history");
  if(!ret)ret=save_context(path,context,"FollowupContextPrepared");
  cJSON_Delete(context);
  pthread_mutex_unlock(&conversation_lock);
  return ret;
}
int buffer_followup_cancel(const char *capture)
{
  char path[320];if(context_path(path,sizeof(path),capture))return -EINVAL;
  pthread_mutex_lock(&conversation_lock);
  errno=0;cJSON *context=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  int ret=!context?(errno==ENOENT?0:-(errno?errno:EIO)):
    cJSON_HasObjectItem(context,"result")?-EALREADY:0;
  if(!ret&&context)ret=buffer_source_is_projection_path(path)?buffer_source_projection_remove(path,"FollowupCaptureCancelled"):
    unlink(path)&&errno!=ENOENT?-errno:0;
  cJSON_Delete(context);
  pthread_mutex_unlock(&conversation_lock);return ret;
}
int buffer_followup_answer(const char *capture,const char *answer)
{
  char path[320],record_path[320],ctx_path[320];
  if(!answer||!answer[0]||strlen(answer)>4096||context_path(ctx_path,sizeof(ctx_path),capture)||
     buffer_agent_record_path(record_path,sizeof(record_path),capture))return -EINVAL;
  pthread_mutex_lock(&conversation_lock);
  errno=0;cJSON *context=buffer_agent_read_json(ctx_path,BUFFER_AGENT_JSON_BYTES);
  if(!context){int error=errno;pthread_mutex_unlock(&conversation_lock);return error==ENOENT?1:-(error?error:EIO);}
  const char *card=txt(context,"card_id");int ret=history_path(path,sizeof(path),card);
  cJSON *turn=NULL,*saved=NULL,*turns=NULL,*record=NULL;
  if(ret)goto done;
  errno=0;saved=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  if(!saved&&errno!=ENOENT){ret=-(errno?errno:EIO);goto done;}
  const cJSON *old_turns=cJSON_GetObjectItemCaseSensitive(saved,"turns");
  if(saved&&!cJSON_IsArray(old_turns)){ret=-EINVAL;goto done;}
  const cJSON *original=cJSON_GetObjectItemCaseSensitive(context,"result");
  if(original){
    if(!valid_turn(original,card)||strcmp(txt(original,"capture_id"),capture)){ret=-EINVAL;goto done;}
    turn=cJSON_Duplicate(original,true);if(!turn){ret=-ENOMEM;goto done;}
  } else {
    errno=0;record=buffer_agent_read_json(record_path,BUFFER_AGENT_JSON_BYTES);
    if(!record){ret=-(errno?errno:EIO);goto done;}
    const char *question=txt(record,"transcript");const cJSON *created=cJSON_GetObjectItemCaseSensitive(record,"created_at");
    if(!question[0]||strlen(question)>4096){ret=-EFBIG;goto done;}
    turn=cJSON_CreateObject();
    bool built=turn&&cJSON_AddStringToObject(turn,"card_id",card)&&cJSON_AddStringToObject(turn,"capture_id",capture)&&
      cJSON_AddStringToObject(turn,"question",question)&&cJSON_AddStringToObject(turn,"answer",answer)&&
      cJSON_IsNumber(created)&&cJSON_AddNumberToObject(turn,"created_at",created->valuedouble);
    if(!built){ret=!turn||cJSON_IsNumber(created)?-ENOMEM:-EINVAL;goto done;}
    if(!valid_turn(turn,card)){ret=-EINVAL;goto done;}
    /* Compatibility with the former cache-first commit order: a crash may
     * have saved the first answer there before attaching it to the context. */
    const cJSON *cached;
    cJSON_ArrayForEach(cached,old_turns)if(!strcmp(txt(cached,"capture_id"),capture)){
      if(!valid_turn(cached,card)||strcmp(txt(cached,"question"),question)||
         cJSON_GetObjectItemCaseSensitive(cached,"created_at")->valuedouble!=created->valuedouble){ret=-EEXIST;goto done;}
      cJSON *first=cJSON_Duplicate(cached,true);if(!first){ret=-ENOMEM;goto done;}
      cJSON_Delete(turn);turn=first;break;
    }
    cJSON *copy=cJSON_Duplicate(turn,true);
    if(!copy){ret=-ENOMEM;goto done;}
    if(!cJSON_AddItemToObject(context,"result",copy)){cJSON_Delete(copy);ret=-ENOMEM;goto done;}
    ret=fit_snapshot(context,"history");
    if(!ret)ret=save_context(ctx_path,context,"QuestionAnswered");
    if(ret)goto done;
    /* The per-capture result is now authoritative. Cache repair or a retry
     * must reuse this exact turn even after its eight-turn window evicts it. */
    revision_changed();
  }
  char directory[320];snprintf(directory,sizeof(directory),"%s/conversations",CONFIG_BUFFER_VELA_DATA_DIR);
  if (mkdir(directory,0700) && errno!=EEXIST) ret=-errno;
  if (!ret && chmod(directory,0700)) ret=-errno;
  if(ret)goto done;
  turns=cJSON_CreateArray();if(!turns){ret=-ENOMEM;goto done;}
  const cJSON *old;
  cJSON_ArrayForEach(old,old_turns){ret=offer_turn(turns,old,card);if(ret)goto done;}
  ret=offer_turn(turns,turn,card);if(ret)goto done;
  cJSON *next=cJSON_CreateObject();if(!next){ret=-ENOMEM;goto done;}
  if(!cJSON_AddItemToObject(next,"turns",turns)){cJSON_Delete(next);ret=-ENOMEM;goto done;}turns=NULL;
  ret=fit_snapshot(next,"turns");
  if(!ret&&(!saved||!cJSON_Compare(saved,next,true))){
    ret=buffer_source_projection_json(path,next,"ConversationViewUpdated",NULL);
    if(!ret)revision_changed();
  }
  cJSON_Delete(next);
done:
  cJSON_Delete(record);cJSON_Delete(turns);cJSON_Delete(saved);cJSON_Delete(turn);cJSON_Delete(context);
  pthread_mutex_unlock(&conversation_lock);
  return ret;
}
char *buffer_followup_history(const char *card)
{
  char path[320];if (history_path(path,sizeof(path),card)) {errno=EINVAL;return NULL;}
  pthread_mutex_lock(&conversation_lock);
  errno=0;
  cJSON *saved=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
  int error=saved?0:errno==ENOENT?0:errno?errno:EIO;
  if(saved&&!cJSON_IsArray(cJSON_GetObjectItemCaseSensitive(saved,"turns")))error=EINVAL;
  cJSON *turns=error?NULL:merged_turns(card,cJSON_GetObjectItemCaseSensitive(saved,"turns")),*turn;
  if(!turns&&!error)error=errno?errno:ENOMEM;
  pthread_mutex_unlock(&conversation_lock);
  if(error){cJSON_Delete(saved);errno=error;return NULL;}
  size_t length=0;
  cJSON_ArrayForEach(turn,turns) length+=strlen(txt(turn,"question"))+strlen(txt(turn,"answer"))+32;
  char *result=length ? calloc(1,length+1):NULL;
  if (result) cJSON_ArrayForEach(turn,turns)
    {
      if (result[0]) strcat(result,"\n\n");
      strcat(result,"你\n");strcat(result,txt(turn,"question"));
      strcat(result,"\n\n助手\n");strcat(result,txt(turn,"answer"));
    }
  if(!result)errno=length?ENOMEM:ENOENT;else errno=0;
  cJSON_Delete(turns);cJSON_Delete(saved);return result;
}
