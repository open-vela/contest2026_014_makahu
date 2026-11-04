/* Unified state -> desktop and notification views; no real business tasks. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include "buffer_ui_notifications.h"
#include "buffer_ui_layout.h"
static unsigned calls;
static char opened[80];
static lv_point_t pointer;
static lv_indev_state_t pointer_state=LV_INDEV_STATE_RELEASED;
static void read_pointer(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;if(pointer_state==LV_INDEV_STATE_PRESSED)data->point=pointer;data->state=pointer_state;}
static void dispatch(const struct buffer_notification_action *action,void *context)
{assert(context==&calls);calls++;snprintf(opened,sizeof(opened),"%s",action->object);}
static struct buffer_notification make(const char *key,const char *owner)
{
 struct buffer_notification n;assert(!buffer_notification_build(&n,key,owner,"中文计时器","00:30"));
 strcpy(n.channel,"timers");n.desktop=n.live=true;n.phase=BUFFER_NOTICE_RUNNING;n.action_count=1;
 strcpy(n.actions[0].id,"open");strcpy(n.actions[0].label,"打开");strcpy(n.actions[0].route,"timer");strcpy(n.actions[0].object,key);return n;
}
int main(void)
{
 lv_init();lv_display_t *display=lv_display_create(320,240);buffer_notification_reset();
 struct buffer_notification n=make("timer:one","timers"),snapshot[BUFFER_NOTIFICATION_LIMIT];
 unsigned timers=buffer_notification_begin("timers"),alarms=buffer_notification_begin("alarms");
 assert(!buffer_notification_publish(&n,100));struct buffer_notification alarm=make("alarm:one","alarms");
 alarm.importance=BUFFER_NOTICE_HIGH;assert(!buffer_notification_publish(&alarm,100));
 buffer_notification_end("timers",timers);buffer_notification_end("alarms",alarms);
 assert(buffer_notification_snapshot(snapshot,BUFFER_NOTIFICATION_LIMIT,true,true,100)==2);
 assert(!strcmp(snapshot[0].key,"alarm:one"));
 struct buffer_notification stolen=n;strcpy(stolen.owner,"other");assert(buffer_notification_publish(&stolen,101)==-EACCES);
 struct buffer_notification_view desktop,center;
 buffer_notification_view_create(&desktop,lv_screen_active(),dispatch,&calls);
 buffer_notification_view_create(&center,lv_screen_active(),dispatch,&calls);
 buffer_notification_view_bind(&desktop,&n);buffer_notification_view_bind(&center,&n);
 lv_obj_send_event(desktop.root,LV_EVENT_CLICKED,NULL);assert(calls==1&&!strcmp(opened,n.key));
 /* Updating the model underneath a pressed view must not retarget its action. */
 strcpy(n.actions[0].object,"replacement");assert(!buffer_notification_publish(&n,110));
 lv_obj_send_event(center.buttons[0],LV_EVENT_CLICKED,NULL);assert(calls==1);
 buffer_notification_view_bind(&center,&n);lv_obj_send_event(center.buttons[0],LV_EVENT_CLICKED,NULL);
 assert(calls==2&&!strcmp(opened,"replacement"));
 buffer_notification_dismiss(n.key);assert(!buffer_notification_get(n.key,&snapshot[0]));
 assert(!buffer_notification_publish(&n,120));assert(buffer_notification_snapshot(snapshot,24,true,true,120)==1);
 n.live=false;n.phase=BUFFER_NOTICE_COMPLETE;n.expires_after_ms=100;
 assert(!buffer_notification_publish(&n,200));assert(buffer_notification_get(n.key,&snapshot[0]));
 assert(buffer_notification_snapshot(snapshot,24,true,true,301)==1);
 assert(!buffer_notification_publish(&n,400));assert(!buffer_notification_get(n.key,&snapshot[0]));
 /* Expired terminal states stay dismissed despite recurring source snapshots. */
 assert(buffer_notification_snapshot(snapshot,24,true,true,400)==1);
 n.phase=BUFFER_NOTICE_PAUSED;n.live=true;n.expires_after_ms=0;assert(!buffer_notification_publish(&n,500));
 n.privacy=BUFFER_NOTICE_SENSITIVE;strcpy(n.status,"private status");strcpy(n.title,"private title");
 assert(!buffer_notification_publish(&n,501));
 size_t count=buffer_notification_snapshot(snapshot,24,false,false,501);assert(count==2);
 for(size_t i=0;i<count;i++)if(!strcmp(snapshot[i].key,n.key)){
  assert(!strstr(snapshot[i].title,"private")&&!snapshot[i].status[0]);
  assert(snapshot[i].action_count==1&&!strcmp(snapshot[i].actions[0].id,"open"));
 }
 struct buffer_notification invalid=n;invalid.phase=BUFFER_NOTICE_COMPLETE;
 assert(buffer_notification_publish(&invalid,502)==-EINVAL);
 invalid=n;invalid.style=BUFFER_NOTICE_PROGRESS;invalid.determinate=true;invalid.progress_max=10;invalid.progress=11;
 assert(buffer_notification_publish(&invalid,502)==-EINVAL);
 invalid=n;memset(invalid.text,'x',sizeof(invalid.text));assert(buffer_notification_publish(&invalid,502)==-EINVAL);
 char utf8[7];buffer_notification_text(utf8,sizeof(utf8),"中文测试");assert(!strcmp(utf8,"中文"));
 buffer_notification_text(utf8,sizeof(utf8),"\xc0\x80");assert(!strcmp(utf8,"??"));
 timers=buffer_notification_begin("timers");buffer_notification_end("timers",timers);
 assert(!buffer_notification_get(n.key,&snapshot[0]));assert(buffer_notification_get(alarm.key,&snapshot[0]));
 /* Whole-card navigation is independent from contextual controls, and both
  * are fenced against replacement while an old view remains pressed. */
 n=make("timer:controls","timers");n.open_action=n.actions[0];strcpy(n.actions[0].id,"pause");strcpy(n.actions[0].label,"暂停");
 assert(!buffer_notification_publish(&n,700));buffer_notification_view_bind(&desktop,&n);
 unsigned before=calls;lv_obj_send_event(desktop.root,LV_EVENT_CLICKED,NULL);assert(calls==before+1);
 strcpy(n.open_action.object,"replacement");assert(!buffer_notification_publish(&n,701));
 lv_obj_send_event(desktop.root,LV_EVENT_CLICKED,NULL);assert(calls==before+1);
 buffer_notification_view_bind(&desktop,&n);lv_obj_send_event(desktop.root,LV_EVENT_CLICKED,NULL);assert(calls==before+2&&!strcmp(opened,"replacement"));
 n.privacy=BUFFER_NOTICE_SENSITIVE;strcpy(n.artwork,"private recipe");assert(!buffer_notification_publish(&n,702));
 count=buffer_notification_snapshot(snapshot,24,true,false,702);
 for(size_t i=0;i<count;i++)if(!strcmp(snapshot[i].key,n.key)){assert(!snapshot[i].artwork[0]&&!snapshot[i].action_count);assert(!strcmp(snapshot[i].open_action.object,"replacement"));}
 /* Real pointer dispatch: horizontal dismissal never opens/cancels work,
  * vertical movement and rebinding cannot delete an unrelated notice. */
 buffer_notification_view_bind(&center,&n);lv_obj_add_flag(desktop.root,LV_OBJ_FLAG_HIDDEN);
 lv_obj_set_pos(center.root,20,20);lv_obj_set_size(center.root,280,180);lv_obj_update_layout(center.root);
 lv_indev_t *touch=lv_indev_create();lv_indev_set_type(touch,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(touch,read_pointer);
 unsigned before_swipe=calls;
 pointer=(lv_point_t){160,35};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
 pointer.x=210;lv_indev_read(touch);pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);
 assert(!buffer_notification_get(n.key,&snapshot[0])&&lv_obj_has_flag(center.root,LV_OBJ_FLAG_HIDDEN)&&calls==before_swipe);
 assert(!buffer_notification_publish(&n,703));assert(!buffer_notification_get(n.key,&snapshot[0]));
 n.phase=BUFFER_NOTICE_PAUSED;assert(!buffer_notification_publish(&n,704));buffer_notification_view_bind(&center,&n);lv_obj_update_layout(center.root);
 pointer=(lv_point_t){160,35};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
 pointer.y=55;lv_indev_read(touch);pointer.x=210;lv_indev_read(touch);pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);
 assert(buffer_notification_get(n.key,&snapshot[0]));
 pointer=(lv_point_t){160,35};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
 struct buffer_notification replacement=make("replacement","timers");assert(!buffer_notification_publish(&replacement,705));buffer_notification_view_bind(&center,&replacement);
 pointer.x=210;lv_indev_read(touch);pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);assert(buffer_notification_get(replacement.key,&snapshot[0]));
 lv_indev_delete(touch);
 buffer_notification_reset();for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
  char key[80];snprintf(key,sizeof(key),"timer:%u",i);n=make(key,"timers");assert(!buffer_notification_publish(&n,1000));
 }
 n=make("overflow","timers");assert(buffer_notification_publish(&n,1000)==-ENOSPC);
 lv_obj_delete(desktop.root);lv_obj_delete(center.root);buffer_notification_reset();
 lv_display_delete(display);lv_deinit();
 puts("PASS: unified notification state, source batches, privacy, expiry/dismissal, stable actions, UTF-8 and capacity");
}
