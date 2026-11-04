/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_notifications.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_typography.h"
#include <errno.h>
#include <string.h>
struct slot {struct buffer_notification notice;uint64_t expires;unsigned batch;bool used,dismissed;};
static struct slot slots[BUFFER_NOTIFICATION_LIMIT];
static unsigned generation;
static struct {char owner[32];unsigned batch;} sources[BUFFER_NOTIFICATION_LIMIT];
static unsigned owner_batch(const char *owner)
{for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(!strcmp(sources[i].owner,owner))return sources[i].batch;return 0;}
static struct slot *lookup(const char *key)
{if(key)for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(slots[i].used&&!strcmp(slots[i].notice.key,key))return &slots[i];return NULL;}
static unsigned utf8_span(const unsigned char *s)
{
 unsigned char lead=s[0];if(lead<0x80)return lead?1:0;
 unsigned n=lead>=0xc2&&lead<=0xdf?2:lead>=0xe0&&lead<=0xef?3:lead>=0xf0&&lead<=0xf4?4:0;
 if(!n)return 0;
 for(unsigned i=1;i<n;i++)if(!s[i]||(s[i]&0xc0)!=0x80)return 0;
 if((lead==0xe0&&s[1]<0xa0)||(lead==0xed&&s[1]>=0xa0)||
    (lead==0xf0&&s[1]<0x90)||(lead==0xf4&&s[1]>=0x90))return 0;
 return n;
}
void buffer_notification_text(char *target,size_t capacity,const char *source)
{
 if(!capacity)return;
 if(!source)source="";
 size_t used=0;
 while(*source){
  unsigned n=utf8_span((const unsigned char *)source);
  if(!n){if(used+1>=capacity)break;target[used++]='?';source++;continue;}
  if(used+n>=capacity)break;
  memcpy(target+used,source,n);used+=n;source+=n;
 }
 target[used]=0;
}
static bool terminated(const char *text,size_t size)
{
 if(!memchr(text,0,size))return false;
 while(*text){unsigned n=utf8_span((const unsigned char *)text);if(!n)return false;text+=n;}
 return true;
}
int buffer_notification_build(struct buffer_notification *notice,const char *key,
                              const char *owner,const char *title,const char *text)
{
 if(!notice||!key||!*key||!owner||!*owner||strlen(key)>=sizeof(notice->key)||strlen(owner)>=sizeof(notice->owner))return -EINVAL;
 memset(notice,0,sizeof(*notice));strcpy(notice->key,key);strcpy(notice->owner,owner);
 buffer_notification_text(notice->title,sizeof(notice->title),title);
 buffer_notification_text(notice->text,sizeof(notice->text),text);
 notice->importance=BUFFER_NOTICE_NORMAL;return 0;
}
int buffer_notification_publish(const struct buffer_notification *notice,uint64_t now)
{
 if(!notice||!terminated(notice->key,sizeof(notice->key))||!notice->key[0]||
    !terminated(notice->owner,sizeof(notice->owner))||!notice->owner[0]||
    !terminated(notice->channel,sizeof(notice->channel))||!terminated(notice->status,sizeof(notice->status))||!terminated(notice->title,sizeof(notice->title))||
    !terminated(notice->text,sizeof(notice->text))||!terminated(notice->artwork,sizeof(notice->artwork))||notice->action_count>BUFFER_NOTIFICATION_ACTIONS||
    (unsigned)notice->style>BUFFER_NOTICE_PROGRESS||(unsigned)notice->phase>BUFFER_NOTICE_ERROR||
    (unsigned)notice->importance>BUFFER_NOTICE_HIGH||(unsigned)notice->privacy>BUFFER_NOTICE_SENSITIVE||
    (notice->live&&(notice->phase==BUFFER_NOTICE_COMPLETE||notice->phase==BUFFER_NOTICE_ERROR))||
    (notice->determinate&&(!notice->progress_max||notice->progress>notice->progress_max)))return -EINVAL;
 for(unsigned i=0;i<=notice->action_count;i++){
  const struct buffer_notification_action *a=i?&notice->actions[i-1]:&notice->open_action;
  if(!terminated(a->id,sizeof(a->id))||!terminated(a->label,sizeof(a->label))||
     !terminated(a->route,sizeof(a->route))||!terminated(a->object,sizeof(a->object)))return -EINVAL;
  if(!i&&!a->id[0])continue;
  if(!a->id[0]||!a->route[0]||(!i&&strcmp(a->id,"open")))return -EINVAL;
 }
 struct slot *slot=lookup(notice->key);
 if(slot&&strcmp(slot->notice.owner,notice->owner))return -EACCES;
 if(!slot)for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(!slots[i].used){slot=&slots[i];break;}
 if(!slot)return -ENOSPC;
 bool changed=!slot->used||slot->notice.phase!=notice->phase;
 if(changed){slot->dismissed=false;slot->expires=notice->expires_after_ms?now+notice->expires_after_ms:0;}
 slot->notice=*notice;slot->batch=owner_batch(notice->owner);slot->used=true;return 0;
}
void buffer_notification_remove(const char *key)
{struct slot *slot=lookup(key);if(slot)memset(slot,0,sizeof(*slot));}
void buffer_notification_dismiss(const char *key)
{struct slot *slot=lookup(key);if(slot)slot->dismissed=true;}
unsigned buffer_notification_begin(const char *owner)
{
 if(!owner||!*owner||strlen(owner)>=sizeof(sources[0].owner))return 0;
 unsigned index=BUFFER_NOTIFICATION_LIMIT;
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(!strcmp(sources[i].owner,owner)){index=i;break;}
 if(index==BUFFER_NOTIFICATION_LIMIT)for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(!sources[i].owner[0]){index=i;break;}
 if(index==BUFFER_NOTIFICATION_LIMIT)return 0;
 if(++generation==0)generation++;
 strcpy(sources[index].owner,owner);sources[index].batch=generation;return generation;
}
void buffer_notification_end(const char *owner,unsigned batch)
{if(!owner||!batch||owner_batch(owner)!=batch)return;for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)if(slots[i].used&&!strcmp(slots[i].notice.owner,owner)&&slots[i].batch!=batch)memset(&slots[i],0,sizeof(slots[i]));}
bool buffer_notification_get(const char *key,struct buffer_notification *out)
{struct slot *slot=lookup(key);if(!slot||slot->dismissed||!out)return false;*out=slot->notice;return true;}
size_t buffer_notification_snapshot(struct buffer_notification *out,size_t capacity,
                                    bool desktop,bool allow_sensitive,uint64_t now)
{
 if(!out||!capacity)return 0;
 size_t count=0;
 for(unsigned importance=BUFFER_NOTICE_HIGH+1;importance>0;importance--)
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
  struct slot *slot=&slots[i];if(!slot->used||slot->dismissed||slot->notice.importance!=importance-1)continue;
  if(slot->expires&&now>=slot->expires){slot->dismissed=true;continue;}
  if(desktop&&!slot->notice.desktop)continue;
  if(count>=capacity)return count;
  out[count]=slot->notice;
  if(!allow_sensitive&&out[count].privacy==BUFFER_NOTICE_SENSITIVE){
    buffer_notification_text(out[count].title,sizeof(out[count].title),"私密通知");
    buffer_notification_text(out[count].text,sizeof(out[count].text),"打开应用查看内容");
    out[count].status[0]=out[count].artwork[0]=0;out[count].determinate=false;out[count].style=BUFFER_NOTICE_BASIC;
    unsigned kept=0;
    for(unsigned j=0;j<out[count].action_count;j++)if(!strcmp(out[count].actions[j].id,"open")){
      out[count].actions[0]=out[count].actions[j];strcpy(out[count].actions[0].label,"打开");kept=1;break;
    }
    out[count].action_count=kept;
  }
  count++;
 }
 return count;
}
void buffer_notification_reset(void){memset(slots,0,sizeof(slots));generation=0;memset(sources,0,sizeof(sources));}
static void view_swipe(lv_event_t *event)
{
 struct buffer_notification_view *view=lv_event_get_user_data(event);lv_indev_t *input=lv_indev_active();
 if(view->desktop||!input||lv_indev_get_type(input)!=LV_INDEV_TYPE_POINTER)return;
 lv_event_code_t code=lv_event_get_code(event);lv_point_t point;lv_indev_get_point(input,&point);
 if(code==LV_EVENT_PRESSED){view->swipe_input=input;view->swipe_start=point;strcpy(view->swipe_key,view->key);view->swipe_cancelled=false;return;}
 if(input!=view->swipe_input)return;
 if(code==LV_EVENT_PRESS_LOST||code==LV_EVENT_RELEASED){view->swipe_input=NULL;return;}
 if(code!=LV_EVENT_PRESSING||view->swipe_cancelled)return;
 int dx=point.x-view->swipe_start.x,dy=point.y-view->swipe_start.y;
 if(strcmp(view->swipe_key,view->key)||LV_ABS(dy)>buffer_ui_dp(12)){view->swipe_cancelled=true;return;}
 if(LV_ABS(dx)<buffer_ui_dp(32)||LV_ABS(dx)<2*LV_ABS(dy))return;
 view->swipe_input=NULL;lv_indev_wait_release(input);lv_indev_reset(input,NULL);
 buffer_notification_dismiss(view->key);buffer_ui_add_flag(view->root,LV_OBJ_FLAG_HIDDEN);
}
static void view_action(lv_event_t *event)
{
 struct buffer_notification_view *view=lv_event_get_user_data(event);
 if(lv_event_get_target(event)!=lv_event_get_current_target(event))return;
 lv_obj_t *target=lv_event_get_current_target(event);unsigned selected=0;
 if(target!=view->root){for(;selected<BUFFER_NOTIFICATION_ACTIONS;selected++)if(view->buttons[selected]==target)break;}
 struct buffer_notification notice;
 if(!buffer_notification_get(view->key,&notice))return;
 bool primary=target==view->root&&view->bound_open.id[0];
 if(!primary&&(selected>=BUFFER_NOTIFICATION_ACTIONS||selected>=notice.action_count))return;
 const struct buffer_notification_action *old=primary?&view->bound_open:&view->bound[selected],*current=primary?&notice.open_action:&notice.actions[selected];
 /* A stale row cannot execute a replacement task's action. */
 if(strcmp(old->id,current->id)||strcmp(old->route,current->route)||strcmp(old->object,current->object))return;
 if(view->dispatch)view->dispatch(current,view->context);
}
void buffer_notification_view_create(struct buffer_notification_view *view,lv_obj_t *parent,
                                      buffer_notification_dispatch dispatch,void *context)
{
 memset(view,0,sizeof(*view));view->dispatch=dispatch;view->context=context;
 view->root=lv_obj_create(parent);lv_obj_remove_style_all(view->root);
 lv_obj_set_size(view->root,LV_PCT(100),LV_SIZE_CONTENT);
 lv_obj_set_flex_flow(view->root,LV_FLEX_FLOW_COLUMN);
 lv_obj_set_style_pad_all(view->root,buffer_ui_dp(16),0);lv_obj_set_style_pad_row(view->root,buffer_ui_dp(8),0);
 lv_obj_set_style_radius(view->root,BUFFER_CARD_RADIUS,0);
 lv_obj_set_style_bg_color(view->root,lv_color_hex(BUFFER_PANEL),0);lv_obj_set_style_bg_opa(view->root,LV_OPA_COVER,0);
 lv_obj_remove_flag(view->root,LV_OBJ_FLAG_SCROLLABLE);lv_obj_add_event_cb(view->root,view_action,LV_EVENT_CLICKED,view);
 lv_obj_add_flag(view->root,LV_OBJ_FLAG_PRESS_LOCK);
 lv_obj_add_event_cb(view->root,view_swipe,LV_EVENT_ALL,view);
 view->status=lv_label_create(view->root);buffer_ui_text_role(view->status,BUFFER_TEXT_LABEL_LARGE);
 lv_obj_set_style_text_color(view->status,lv_color_hex(BUFFER_ACCENT),0);
 view->title=lv_label_create(view->root);buffer_ui_text_role(view->title,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
 lv_obj_set_width(view->title,LV_PCT(100));lv_label_set_long_mode(view->title,LV_LABEL_LONG_WRAP);
 view->text=lv_label_create(view->root);buffer_ui_text_role(view->text,BUFFER_TEXT_BODY_LARGE);
 lv_obj_set_width(view->text,LV_PCT(100));lv_label_set_long_mode(view->text,LV_LABEL_LONG_WRAP);
 view->progress=lv_bar_create(view->root);lv_obj_set_size(view->progress,LV_PCT(100),buffer_ui_dp(4));
 lv_obj_set_style_bg_color(view->progress,lv_color_hex(BUFFER_ACCENT),LV_PART_INDICATOR);
 view->actions=lv_obj_create(view->root);lv_obj_remove_style_all(view->actions);
 lv_obj_set_size(view->actions,LV_PCT(100),LV_SIZE_CONTENT);lv_obj_set_flex_flow(view->actions,LV_FLEX_FLOW_ROW_WRAP);
 lv_obj_set_style_pad_column(view->actions,buffer_ui_dp(8),0);lv_obj_set_style_pad_row(view->actions,buffer_ui_dp(4),0);
 lv_obj_remove_flag(view->actions,LV_OBJ_FLAG_SCROLLABLE);
 for(unsigned i=0;i<BUFFER_NOTIFICATION_ACTIONS;i++){
  lv_obj_t *button=lv_button_create(view->actions);view->buttons[i]=button;buffer_ui_button_style(button,false);
  lv_obj_add_flag(button,LV_OBJ_FLAG_EVENT_BUBBLE);
  lv_obj_set_size(button,LV_SIZE_CONTENT,LV_SIZE_CONTENT);lv_obj_set_style_pad_all(button,buffer_ui_dp(12),0);
  lv_obj_t *label=lv_label_create(button);buffer_ui_button_label(label);lv_obj_center(label);
  lv_obj_add_event_cb(button,view_action,LV_EVENT_CLICKED,view);
 }
 buffer_ui_add_flag(view->root,LV_OBJ_FLAG_HIDDEN);
}
void buffer_notification_view_bind(struct buffer_notification_view *view,const struct buffer_notification *notice)
{
 if(!notice){buffer_ui_add_flag(view->root,LV_OBJ_FLAG_HIDDEN);return;}
 buffer_notification_text(view->key,sizeof(view->key),notice->key);
 view->bound_open=notice->open_action;
 memcpy(view->bound,notice->actions,sizeof(view->bound));
 buffer_ui_label_set_text(view->title,notice->title);buffer_ui_label_set_text(view->text,notice->text);
 int32_t text_limit=view->desktop||notice->style==BUFFER_NOTICE_BIG_TEXT?LV_COORD_MAX:buffer_ui_sp(48);
 if(lv_obj_get_style_max_height(view->text,0)!=text_limit)lv_obj_set_style_max_height(view->text,text_limit,0);
 lv_label_set_long_mode(view->text,notice->style==BUFFER_NOTICE_BIG_TEXT?LV_LABEL_LONG_WRAP:LV_LABEL_LONG_DOT);
 buffer_ui_label_set_text(view->status,notice->status[0]?notice->status:notice->phase==BUFFER_NOTICE_RUNNING?"进行中":notice->phase==BUFFER_NOTICE_PAUSED?"已暂停":notice->phase==BUFFER_NOTICE_COMPLETE?"已完成":"未完成");
 if(notice->style==BUFFER_NOTICE_PROGRESS&&notice->determinate){
  buffer_ui_remove_flag(view->progress,LV_OBJ_FLAG_HIDDEN);
  lv_bar_set_range(view->progress,0,1000);
  lv_bar_set_value(view->progress,(int32_t)((uint64_t)notice->progress*1000/notice->progress_max),LV_ANIM_OFF);
 }else buffer_ui_add_flag(view->progress,LV_OBJ_FLAG_HIDDEN);
 if(!view->desktop||notice->action_count||notice->open_action.id[0])buffer_ui_add_flag(view->root,LV_OBJ_FLAG_CLICKABLE);else buffer_ui_remove_flag(view->root,LV_OBJ_FLAG_CLICKABLE);
 for(unsigned i=0;i<BUFFER_NOTIFICATION_ACTIONS;i++){
  if(i<notice->action_count){buffer_ui_remove_flag(view->buttons[i],LV_OBJ_FLAG_HIDDEN);buffer_ui_label_set_text(lv_obj_get_child(view->buttons[i],0),notice->actions[i].label);}
  else buffer_ui_add_flag(view->buttons[i],LV_OBJ_FLAG_HIDDEN);
 }
 if(notice->action_count)buffer_ui_remove_flag(view->actions,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(view->actions,LV_OBJ_FLAG_HIDDEN);
 buffer_ui_remove_flag(view->root,LV_OBJ_FLAG_HIDDEN);
}
