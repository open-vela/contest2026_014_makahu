/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_app.h"
#include "buffer_history.h"
#include "buffer_source_events.h"
#include "buffer_source_remote.h"
#include "buffer_source_envelope.h"
#include "buffer_crdt.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_shell.h"
#include "buffer_ui_icons.h"
#include "buffer_ui_motion.h"
#include <lvgl/lvgl.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHUNK 3072
#define BODY_LIMIT (BUFFER_SOURCE_EVENT_BYTES+2048)
enum operation {OPEN,CLOSE,SOURCE,REFRESH,EARLIER,LATER,PREVIOUS,NEXT,RAW};
struct history_view {
  char caption[160],title[96],body[CHUNK+1],notice[128];
  char source[97];uint64_t sequence;size_t offset;
  bool earlier,later,previous,next,raw,record;
};
struct history_cache {
  char hash[65],source[97],peer[65],causal[24];
  uint64_t sequence;
  char *raw,*pretty;
  size_t bytes,offset;
  bool earlier,later,raw_mode;
};
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake=PTHREAD_COND_INITIALIZER;
static pthread_t thread;
static bool started,stopping,requested,dirty,opened,working;
static uint64_t generation;
static enum operation command;
static struct history_view view;
static struct history_view rendered_view;
static struct history_view incoming_view;
static bool has_view,options_open;
static lv_obj_t *host,*entry,*caption,*title,*body,*notice,*earlier,*later,*previous,*next,*raw_button,*source_button,*refresh_button;
static lv_obj_t *header,*heading,*reader,*footer,*options,*options_button;
static void history_layout(lv_event_t *event)
{
 (void)event;
 struct buffer_ui_viewport v=buffer_ui_viewport(host);
 lv_obj_set_style_pad_all(host,0,0);
 if(header)lv_obj_set_style_pad_hor(header,v.padding,0);
 if(footer)lv_obj_set_style_pad_hor(footer,v.padding,0);
 if(reader)lv_obj_set_style_pad_all(reader,v.padding,0);
 if(options)lv_obj_set_style_pad_all(options,v.padding,0);
 lv_obj_set_style_pad_row(host,v.gap,0);
}
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static void release(char **value)
{if(*value){volatile char *p=*value;size_t n=strlen(*value);while(n--)*p++=0;free(*value);*value=NULL;}}
static void clear_record(struct history_cache *cache)
{release(&cache->raw);release(&cache->pretty);cache->bytes=cache->offset=0;cache->sequence=0;cache->earlier=cache->later=cache->raw_mode=false;cache->source[0]=cache->peer[0]=cache->causal[0]=0;}
static const char *event_title(const char *type)
{
  static const struct {const char *type,*label;} names[]={
    {"CaptureCreated","保存输入"},{"CaptureRecorded","保存录音"},{"CaptureTranscribed","语音转写"},
    {"CaptureSegmented","拆分输入"},{"ClassificationProposed","分类建议"},{"ClassificationAccepted","确认分类"},
    {"ClassificationEdited","调整分类"},{"ClassificationRejected","保留原始输入"},{"CardContentEdited","编辑内容"},
    {"CardDocumentObserved","卡片更新"},{"CalendarDocumentObserved","日程更新"},{"CalendarReplicaObserved","收到日程更新"},{"DocumentMerged","合并更新"},
    {"CardDeleted","删除卡片"},{"CardActionPerformed","卡片操作"},{"CardResurfaced","记录回流"},
    {"TaskScheduleChanged","调整日程"},{"CaptureContextCaptured","捕获上下文"},{"QuestionAnswered","记录回答"},
    {"QuestionResearchNoteAdded","补充研究笔记"},{"CardFollowupAdded","补充追问"},{"FocusSessionUpdated","专注记录"},
    {"FocusSessionImported","专注记录"},{"FocusEnded","结束专注"},{"RabbitHoleStarted","开始研究"},
    {"RabbitHoleEnded","结束研究"},{"ResearchSessionObserved","研究记录"},{"WeeklySummaryGenerated","每周回顾"},
    {"CardDocumentUnavailable","原始记录已保存"}
  };
  for(unsigned i=0;i<sizeof(names)/sizeof(names[0]);i++)if(!strcmp(type,names[i].type))return names[i].label;
  return "记录变更";
}
static bool embedded_nul(const char *raw)
{
  for(size_t i=0;raw[i];i++)if(raw[i]=='\\'){
      if (raw[i + 1] == 'u' && !strncmp(raw + i + 2, "0000", 4))
        return true;
      if (raw[i + 1])
        i++;
  }
  return false;
}
static char *describe(const cJSON *event,const char *original)
{
  if(embedded_nul(original))return strdup("记录含特殊字符，可切换到原始数据查看。 ");
  const cJSON *payload=cJSON_GetObjectItemCaseSensitive(event,"payload"),*selected=payload;
  cJSON *parsed=NULL,*values=NULL;
  const cJSON *document=cJSON_GetObjectItemCaseSensitive(payload,"document");
  if(cJSON_IsObject(document)&&!buffer_crdt_validate(document)){
    if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(document,"deleted")))return strdup("这条卡片或日程已删除。原始变更仍保留在历史中。");
    values=buffer_crdt_values(document);if(!values)return NULL;selected=values;
  }else {
    const char *encoded=text(payload,"record_json");if(!*encoded)encoded=text(payload,"projection_text");
    if(*encoded){
      if(embedded_nul(encoded))return strdup("记录含特殊字符，可切换到原始数据查看。");
      parsed=buffer_agent_parse_json(encoded);if(cJSON_IsObject(parsed))selected=parsed;
    }
  }
  static const struct {const char *key,*label;} fields[]={
    {"title",""},{"text","原始输入"},{"transcript","转写"},{"summary","内容"},{"question","问题"},
    {"answer","回答"},{"research_notes","研究笔记"},{"note","笔记"},{"topic","主题"},
    {"remaining","剩余事项"},{"feedback","回顾"},{"reason","说明"},{"detail","详情"}
  };
  size_t bytes=1;for(unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++)if(*text(selected,fields[i].key))bytes+=strlen(text(selected,fields[i].key))+strlen(fields[i].label)+4;
  char *result=bytes<=BODY_LIMIT?malloc(bytes):NULL;
  if(result){size_t at=0;result[0]=0;
    for(unsigned i=0;i<sizeof(fields)/sizeof(fields[0]);i++){
      const char *value=text(selected,fields[i].key);if(!*value)continue;
      int n=snprintf(result+at,bytes-at,"%s%s%s%s",at?"\n\n":"",fields[i].label,*fields[i].label?"\n":"",value);
      if(n<0||(size_t)n>=bytes-at){release(&result);break;}at+=(size_t)n;
    }
    if(result&&!result[0]){release(&result);result=strdup("这条变更已保存，可切换到原始数据查看完整内容。");}
  }
  cJSON_Delete(values);cJSON_Delete(parsed);return result;
}
static int load(struct history_cache *cache,uint64_t anchor,int direction)
{
  struct buffer_source_projection_input_s record={0};bool older=false,newer=false;int ret=0;
  if(cache->hash[0])ret=buffer_source_remote_history_read(cache->hash,anchor,direction,&record,&older,&newer);
  else {
    uint64_t head=0;ret=buffer_source_local_head(record.source,sizeof(record.source),&head);
    uint64_t position=!anchor?(direction<0?head:head?1:0):direction<0?(anchor>1?anchor-1:0):(anchor<head?anchor+1:0);
    if(!ret&&anchor>head)ret=-ESTALE;
    if(!ret&&position){ret=buffer_source_local_read(position,&record.raw,&record.length);
      if(!ret){record.sequence=position;older=position>1;newer=position<head;ret=1;}}
  }
  if(ret==1){cJSON *envelope=NULL;ret=buffer_source_envelope_decode(record.raw,record.length,&envelope);
    if(!ret&&strcmp(text(envelope,"source_id"),record.source))ret=-ESTALE;
    char causal[24]={0};
    if(!ret&&cache->hash[0]&&buffer_source_remote_causal_check(&record,causal)<0)
      snprintf(causal,sizeof(causal),"unavailable");
    char *pretty=NULL;if(!ret){pretty=describe(cJSON_GetObjectItemCaseSensitive(envelope,"event"),record.raw);if(!pretty)ret=-ENOMEM;}
    if(!ret){clear_record(cache);cache->raw=record.raw;record.raw=NULL;cache->bytes=record.length;cache->pretty=pretty;pretty=NULL;
      cache->sequence=record.sequence;cache->earlier=older;cache->later=newer;snprintf(cache->causal,sizeof(cache->causal),"%s",causal);
      snprintf(cache->source,sizeof(cache->source),"%s",record.source);snprintf(cache->peer,sizeof(cache->peer),"%s",record.peer);ret=1;}
    release(&pretty);cJSON_Delete(envelope);
  }
  release(&record.raw);if(!ret&&!anchor)clear_record(cache);return ret;
}
static void render(struct history_cache *cache,struct history_view *out,int error)
{
  memset(out,0,sizeof(*out));
  if(cache->hash[0])snprintf(out->caption,sizeof(out->caption),"其他设备 %.8s",cache->peer);
  else snprintf(out->caption,sizeof(out->caption),"本机记录");
  if(error<0){snprintf(out->notice,sizeof(out->notice),"%s",error==-ENOTSUP?"当前设备未启用历史归档":error==-EAGAIN?"历史索引正在恢复，请稍后刷新":"历史记录读取失败，请稍后刷新");}
  if(!cache->raw){snprintf(out->body,sizeof(out->body),"%s",error<0?"原始记录尚未读取，可在历史操作中刷新或切换来源。":cache->hash[0]?"这个来源尚无可查看的原始记录。":"本机暂无记录，可切换来源查看已同步的记录。");return;}
  cJSON *envelope=NULL;if(buffer_source_envelope_decode(cache->raw,cache->bytes,&envelope)){
    snprintf(out->notice,sizeof(out->notice),"记录校验失败，请刷新重试");cJSON_Delete(envelope);return;}
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event");snprintf(out->title,sizeof(out->title),"%s",event_title(text(event,"type")));
  const cJSON *created=cJSON_GetObjectItemCaseSensitive(event,"created_at");
  if(cJSON_IsNumber(created)&&created->valuedouble>=0&&created->valuedouble<=9007199254740991.0){
    int64_t seconds=(int64_t)created->valuedouble/1000;time_t at=(time_t)seconds;struct tm date;char when[32];
    if((int64_t)at==seconds&&localtime_r(&at,&date)&&strftime(when,sizeof(when),"%Y-%m-%d %H:%M",&date)){
      size_t used=strlen(out->caption);snprintf(out->caption+used,sizeof(out->caption)-used," · %s",when);}}
  const char *content=cache->raw_mode?cache->raw:cache->pretty;size_t length=strlen(content);
  if(cache->offset>length)cache->offset=0;
  size_t end=cache->offset+CHUNK;if(end>length)end=length;
  while(end>cache->offset&&end<length&&((unsigned char)content[end]&0xc0)==0x80)end--;
  memcpy(out->body,content+cache->offset,end-cache->offset);out->body[end-cache->offset]=0;
  out->record=true;out->raw=cache->raw_mode;out->earlier=cache->earlier;out->later=cache->later;out->previous=cache->offset>0;out->next=end<length;
  snprintf(out->source,sizeof(out->source),"%s",cache->source);out->sequence=cache->sequence;out->offset=cache->offset;
  if(!out->notice[0]&&cache->causal[0]) {
    const char *state=cache->causal;
    const char *message=!strcmp(state,"missing_parent")?"前序原文尚未收齐；连接来源设备后继续补传。":
      !strcmp(state,"waiting_parent")?"前序记录仍在核对，完整原文已保留。":
      !strcmp(state,"invalid_clock")||!strcmp(state,"blocked_parent")?"因果信息不一致，已保留原文并停止合并。":
      !strcmp(state,"legacy")?"旧版本记录，不作为已验证的完整因果链。":
      !strcmp(state,"unsupported_schema")?"此记录版本暂不支持，完整原文已保留。":
      !strcmp(state,"unavailable")?"因果状态暂不可用，仍可阅读完整原文。":NULL;
    if(message)snprintf(out->notice,sizeof(out->notice),"%s",message);
  }
  if(!out->notice[0]&&(out->previous||out->next))snprintf(out->notice,sizeof(out->notice),"内容分段显示，可在操作中选择前段或后段。");
  cJSON_Delete(envelope);
}
static int process(struct history_cache *cache,enum operation op)
{
  if(op==CLOSE){clear_record(cache);cache->hash[0]=0;return 0;}
  if(!buffer_oobe_setup_complete()){clear_record(cache);cache->hash[0]=0;return -EACCES;}
  if(op==OPEN){clear_record(cache);cache->hash[0]=0;return load(cache,0,-1);}
  if(op==SOURCE){char selected[65];int ret=buffer_source_remote_history_source(cache->hash,selected);if(ret)return ret;
    clear_record(cache);snprintf(cache->hash,sizeof(cache->hash),"%s",selected);return load(cache,0,-1);}
  if(op==REFRESH)return load(cache,0,-1);
  if(op==EARLIER||op==LATER)return load(cache,cache->sequence,op==EARLIER?-1:1);
  if(op==RAW){cache->raw_mode=!cache->raw_mode;cache->offset=0;return 0;}
  if(!cache->raw)return 0;
  const char *content=cache->raw_mode?cache->raw:cache->pretty;size_t length=strlen(content);
  if(op==NEXT){size_t end=cache->offset+CHUNK;if(end<length){while(end>cache->offset&&((unsigned char)content[end]&0xc0)==0x80)end--;cache->offset=end;}}
  else if(op==PREVIOUS){size_t start=cache->offset>CHUNK?cache->offset-CHUNK:0;
    while (start < cache->offset &&
           ((unsigned char)content[start] & 0xc0) == 0x80)
      start++;
    cache->offset = start;
  }
  return 0;
}
static void *run(void *unused)
{
  (void)unused;struct history_cache cache={0};pthread_mutex_lock(&lock);
  while(!stopping){while(!requested&&!stopping)pthread_cond_wait(&wake,&lock);if(stopping)break;
    enum operation op=command;uint64_t epoch=generation;requested=false;working=true;pthread_mutex_unlock(&lock);
    int result=process(&cache,op);
    if(op!=CLOSE&&!buffer_oobe_setup_complete()){clear_record(&cache);cache.hash[0]=0;result=-EACCES;}
    struct history_view page;render(&cache,&page,result);
    pthread_mutex_lock(&lock);if(!stopping&&epoch==generation){view=page;dirty=true;}working=false;
  }
  pthread_mutex_unlock(&lock);clear_record(&cache);return NULL;
}
static void enabled(lv_obj_t *object,bool value)
{if(value)lv_obj_remove_state(object,LV_STATE_DISABLED);else lv_obj_add_state(object,LV_STATE_DISABLED);}
static void busy(void)
{
  enabled(earlier,false);enabled(later,false);enabled(previous,false);enabled(next,false);enabled(raw_button,false);enabled(source_button,false);enabled(refresh_button,false);
  buffer_ui_remove_flag(notice,LV_OBJ_FLAG_HIDDEN);
  buffer_ui_label_set_text(notice,started?"读取记录…":"历史记录暂时无法打开");
}
static void submit(enum operation op)
{
 pthread_mutex_lock(&lock);
 bool accepted=started&&!working&&!requested;
 if(accepted){command=op;generation++;requested=true;pthread_cond_signal(&wake);}
 pthread_mutex_unlock(&lock);if(accepted||!started)busy();
}
static void show_options(bool show)
{
 if(options_open==show)return;
 if(show)(void)buffer_ui_shell_local_begin("history",options_button);
 options_open=show;
 if(show){buffer_ui_add_flag(reader,LV_OBJ_FLAG_HIDDEN);buffer_ui_add_flag(footer,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(options,LV_OBJ_FLAG_HIDDEN);}
 else{buffer_ui_remove_flag(reader,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(footer,LV_OBJ_FLAG_HIDDEN);buffer_ui_add_flag(options,LV_OBJ_FLAG_HIDDEN);}
 buffer_ui_label_set_text(heading,show?"历史操作":"历史");
 if(!show)buffer_ui_shell_local_end("history",NULL);
}
static bool local_back(void *unused)
{(void)unused;if(!options_open)return false;show_options(false);return true;}
static void options_event(lv_event_t *event)
{(void)event;show_options(!options_open);}
static void action(lv_event_t *event)
{show_options(false);submit((enum operation)(intptr_t)lv_event_get_user_data(event));}
static void shell_closed(void *context)
{ (void)context;opened=false;show_options(false); }
static void shell_activated(bool was_visible,void *context)
{(void)was_visible;(void)context;opened=true;if(!has_view)submit(OPEN);}
static void toggle(lv_event_t *event)
{
  struct buffer_ui_surface_info surface;
  if(buffer_ui_shell_info("history",&surface)){
    if(buffer_ui_shell_is_open("history"))buffer_ui_shell_hide("history");else(void)buffer_ui_shell_open(surface.entry_route,NULL);
    return;
  }
  (void)event;opened=!opened;
  if(opened){
    buffer_ui_remove_flag(host,LV_OBJ_FLAG_HIDDEN);buffer_ui_motion_enter(host,1,false);
    /* Home/Back hide this reader without discarding its cache or scroll. */
    if(!has_view)submit(OPEN);
  } else buffer_ui_add_flag(host,LV_OBJ_FLAG_HIDDEN);
  if(opened)buffer_ui_shell_show("history");else buffer_ui_shell_hide("history");
}
static void back_event(lv_event_t *event)
{if(!local_back(NULL))toggle(event);}
static lv_obj_t *plain(lv_obj_t *parent)
{lv_obj_t *object=lv_obj_create(parent);lv_obj_remove_style_all(object);lv_obj_set_size(object,LV_PCT(100),LV_SIZE_CONTENT);buffer_ui_remove_flag(object,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE);return object;}
static lv_obj_t *button(lv_obj_t *parent,const char *label,lv_event_cb_t callback,void *data,int width)
{
  lv_obj_t *object=lv_button_create(parent);buffer_ui_button_style(object,false);lv_obj_set_size(object,buffer_ui_dp(width),buffer_ui_dp(48));
  lv_obj_t *text=lv_label_create(object);buffer_ui_button_label(text);buffer_ui_label_set_text(text,label);lv_obj_center(text);
  lv_obj_add_event_cb(object,callback,LV_EVENT_CLICKED,data);return object;
}
static lv_obj_t *row(lv_obj_t *parent)
{lv_obj_t *object=plain(parent);lv_obj_set_flex_flow(object,LV_FLEX_FLOW_ROW_WRAP);lv_obj_set_style_pad_row(object,buffer_ui_dp(4),0);lv_obj_set_style_pad_column(object,buffer_ui_dp(6),0);lv_obj_set_flex_align(object,LV_FLEX_ALIGN_SPACE_BETWEEN,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);return object;}
static lv_obj_t *label(lv_obj_t *parent,int type)
{lv_obj_t *object=lv_label_create(parent);buffer_ui_type(object,type);lv_obj_set_width(object,LV_PCT(100));lv_label_set_long_mode(object,LV_LABEL_LONG_WRAP);buffer_ui_label_set_text(object,"");return object;}
int buffer_history_ui_attach(void *page,void *launchers)
{
  opened=false;started=false;stopping=false;requested=working=dirty=false;has_view=options_open=false;generation=0;memset(&view,0,sizeof(view));memset(&rendered_view,0,sizeof(rendered_view));
  entry=button(launchers,"",toggle,NULL,64);
  buffer_ui_launcher_attach(launchers,entry,"app.history","历史");
  buffer_ui_icon_button(entry,&buffer_history_icon);
  host=plain(page);lv_obj_set_size(host,LV_PCT(100),LV_PCT(100));buffer_ui_add_flag(host,LV_OBJ_FLAG_HIDDEN);
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="history",.title="历史",.entry_route="history",.kind=BUFFER_UI_APP,.min_width_dp=160,.min_height_dp=128,
    .actions=(const char *const[]){"open","switch-source","refresh","raw","previous","next","back"},.action_count=7,.closed=shell_closed,.back=local_back,.activated=shell_activated},host);
  buffer_ui_add_flag(host,LV_OBJ_FLAG_CLICKABLE);lv_obj_set_scroll_dir(host,LV_DIR_VER);
  buffer_ui_style_bg_color(host,lv_color_hex(BUFFER_BG),0);buffer_ui_style_bg_opa(host,LV_OPA_COVER,0);lv_obj_set_style_pad_all(host,0,0);lv_obj_set_style_pad_row(host,buffer_ui_dp(4),0);lv_obj_set_flex_flow(host,LV_FLEX_FLOW_COLUMN);
  header=row(host);lv_obj_set_flex_flow(header,LV_FLEX_FLOW_ROW);
  buffer_ui_icon_button(button(header,"返回",back_event,NULL,48),&buffer_back_icon);
  heading=label(header,2);buffer_ui_label_set_text(heading,"历史");lv_obj_set_width(heading,0);lv_obj_set_flex_grow(heading,1);
  options_button=button(header,"历史操作",options_event,NULL,48);buffer_ui_icon_button(options_button,&buffer_more_icon);
  reader=plain(host);lv_obj_set_height(reader,0);lv_obj_set_style_min_height(reader,0,0);lv_obj_set_flex_grow(reader,1);
  buffer_ui_add_flag(reader,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(reader,LV_DIR_VER);
  lv_obj_set_flex_flow(reader,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(reader,buffer_ui_dp(8),0);
  title=label(reader,2);caption=label(reader,4);notice=label(reader,4);body=label(reader,0);
  footer=row(host);lv_obj_set_flex_flow(footer,LV_FLEX_FLOW_ROW);
  earlier=button(footer,"较早记录",action,(void *)(intptr_t)EARLIER,48);buffer_ui_icon_button(earlier,&buffer_previous_icon);
  later=button(footer,"较新记录",action,(void *)(intptr_t)LATER,48);buffer_ui_icon_button(later,&buffer_next_icon);
  lv_obj_set_width(earlier,0);lv_obj_set_flex_grow(earlier,1);lv_obj_set_width(later,0);lv_obj_set_flex_grow(later,1);
  options=plain(host);lv_obj_set_height(options,0);lv_obj_set_style_min_height(options,0,0);lv_obj_set_flex_grow(options,1);
  lv_obj_set_flex_flow(options,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(options,buffer_ui_dp(8),0);buffer_ui_add_flag(options,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_HIDDEN);lv_obj_set_scroll_dir(options,LV_DIR_VER);
  /* Keep the original content first in the reader. Full event/source/time
   * metadata remains available in the same fixed-entry operations route. */
  lv_obj_set_parent(title,options);buffer_ui_text_role(title,BUFFER_TEXT_TITLE_MEDIUM);
  lv_obj_set_parent(caption,options);buffer_ui_text_role(caption,BUFFER_TEXT_BODY_MEDIUM);
  source_button=button(options,"切换记录来源",action,(void *)(intptr_t)SOURCE,64);
  refresh_button=button(options,"刷新最新记录",action,(void *)(intptr_t)REFRESH,64);
  raw_button=button(options,"原始数据",action,(void *)(intptr_t)RAW,64);
  previous=button(options,"前一段内容",action,(void *)(intptr_t)PREVIOUS,64);
  next=button(options,"后一段内容",action,(void *)(intptr_t)NEXT,64);
  lv_obj_t *actions[]={source_button,refresh_button,raw_button,previous,next};for(unsigned i=0;i<5;i++)lv_obj_set_width(actions[i],LV_PCT(100));
  lv_obj_add_event_cb(host,history_layout,LV_EVENT_SIZE_CHANGED,NULL);
  lv_obj_update_layout(host);history_layout(NULL);
  pthread_attr_t attr;int ret=pthread_attr_init(&attr);if(!ret){ret=pthread_attr_setstacksize(&attr,32768);if(!ret)ret=pthread_create(&thread,&attr,run,NULL);pthread_attr_destroy(&attr);}
  started=!ret;busy();return ret?-ret:0;
}
void buffer_history_ui_refresh(void)
{
  if (!host || !opened)
    return;
  if(pthread_mutex_trylock(&lock))return;
  bool loading = working || requested;
  struct history_view *next_view=&incoming_view;
  bool update=dirty&&!loading;if(update){*next_view=view;dirty=false;}
  pthread_mutex_unlock(&lock);
  if(update){
    const struct history_view incoming=*next_view;
    bool same=has_view&&!strcmp(rendered_view.source,incoming.source)&&rendered_view.sequence==incoming.sequence&&rendered_view.offset==incoming.offset&&rendered_view.raw==incoming.raw;
    buffer_ui_label_set_text(title,incoming.title);buffer_ui_label_set_text(caption,incoming.caption);buffer_ui_label_set_text(body,incoming.body);buffer_ui_label_set_text(notice,incoming.notice);
    if(incoming.notice[0])buffer_ui_remove_flag(notice,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(notice,LV_OBJ_FLAG_HIDDEN);
    enabled(earlier,incoming.earlier);enabled(later,incoming.later);enabled(previous,incoming.previous);enabled(next,incoming.next);enabled(raw_button,incoming.record);
    enabled(source_button,started);enabled(refresh_button,started);buffer_ui_label_set_text(lv_obj_get_child(raw_button,0),incoming.raw?"返回内容":"原始数据");
    if(!same)lv_obj_scroll_to_y(reader,0,LV_ANIM_OFF);
    rendered_view=incoming;has_view=true;
  }
}
bool buffer_history_ui_open(void){return opened;}
void buffer_history_ui_detach(void)
{
  pthread_mutex_lock(&lock);stopping=true;generation++;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);if(started)pthread_join(thread,NULL);
  started=opened=false;host=entry=caption=title=body=notice=earlier=later=previous=next=raw_button=source_button=refresh_button=header=heading=reader=footer=options=NULL;
  memset(&view,0,sizeof(view));
  memset(&rendered_view,0,sizeof(rendered_view));memset(&incoming_view,0,sizeof(incoming_view));has_view=options_open=false;
}
