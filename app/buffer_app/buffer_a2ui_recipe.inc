/* SPDX-License-Identifier: Apache-2.0
 * Private recipe presentation adapter. Included by the A2UI LVGL renderer so
 * timer operations use its existing fenced worker queue and domain IDs. */
#include "buffer_ui_recipe_state.h"
#define RECIPE_SESSIONS BUFFER_A2UI_SURFACE_COUNT
struct recipe_session_s {
 bool used,started,overview,obsolete,restore_pending;
 char fingerprint[17],operation_id[64],capture_id[64];
 char surface[BUFFER_A2UI_SURFACE_ID_SIZE],title[BUFFER_ASSISTANT_LABEL_SIZE],image_id[65];
 uint64_t revision;
 char steps[BUFFER_ASSISTANT_MAX_RECIPE_STEPS][BUFFER_A2UI_COMPONENT_ID_SIZE];
 char current_title[BUFFER_ASSISTANT_LABEL_SIZE];
 unsigned count,current,done;
};
struct recipe_view_s {
 lv_obj_t *root,*body,*meta,*reader,*reader_content,*position,*title,*detail,*timer,*footer;
 lv_obj_t *previous,*next,*overview,*done,*materials,*materials_link,*meta_name,*step_buttons[BUFFER_ASSISTANT_MAX_RECIPE_STEPS];
 lv_obj_t *summary,*estimate,*image,*image_frame;
 struct recipe_session_s *session;
 uint8_t indices[BUFFER_ASSISTANT_MAX_RECIPE_STEPS];
 char timer_step[BUFFER_A2UI_COMPONENT_ID_SIZE],timer_id[BUFFER_ASSISTANT_ID_SIZE];
 uint32_t timer_ms;unsigned timer_index;bool wide;
};
static struct recipe_session_s g_recipes[RECIPE_SESSIONS];
static char g_recovered_recipe_live[BUFFER_RECIPE_SAVED][64];
static struct recipe_view_s g_recipe;
static char g_recipe_materials[BUFFER_A2UI_STRING_BUDGET];
static uint8_t g_recipe_scan[BUFFER_ASSISTANT_MAX_RECIPE_STEPS];
static unsigned g_recipe_scan_count;
static bool g_recipe_has_title;
static char g_recipe_title[BUFFER_ASSISTANT_LABEL_SIZE];
static bool ui_recipe_collect(unsigned index,unsigned depth)
{
 if(index>=g_surface_snapshot.component_count||depth>BUFFER_A2UI_DEPTH_MAX)return false;
 const struct buffer_a2ui_component_s *c=&g_surface_snapshot.components[index];
 if(c->type==BUFFER_A2UI_RECIPE_STEP){
  if(g_recipe_scan_count>=BUFFER_ASSISTANT_MAX_RECIPE_STEPS)return false;
  g_recipe_scan[g_recipe_scan_count++]=index;return true;
 }
 if(c->type==BUFFER_A2UI_TEXT){
  const char *text=buffer_a2ui_component_text(&g_surface_snapshot,c);
  if(!g_recipe_has_title&&(c->variant==1||c->variant==2)){
   buffer_notification_text(g_recipe_title,sizeof(g_recipe_title),text);g_recipe_has_title=true;
  }else{
   size_t used=strlen(g_recipe_materials),length=strlen(text);
   if(used+length+2>=sizeof(g_recipe_materials))return false;
   memcpy(g_recipe_materials+used,text,length+1);
   /* Local material chunks are contiguous; generic text nodes are paragraphs. */
   if(strncmp(c->id,"ingredients",11)){used+=length;g_recipe_materials[used++]='\n';g_recipe_materials[used]=0;}
  }
  return true;
 }
 if(c->type==BUFFER_A2UI_DIVIDER)return true;
 if(c->type!=BUFFER_A2UI_COLUMN&&c->type!=BUFFER_A2UI_ROW&&c->type!=BUFFER_A2UI_CARD)return false;
 for(unsigned i=0;i<c->child_count;i++)if(!ui_recipe_collect(c->children[i],depth+1))return false;
 return true;
}
static unsigned ui_recipe_completed(const struct recipe_session_s *s)
{unsigned n=0;for(unsigned i=0;i<s->count;i++)if(s->done&(1u<<i))n++;return n;}
static void ui_recipe_fingerprint(char out[17])
{
 uint64_t hash=1469598103934665603ull;
 const char *fields[1]={g_recipe_title};
 for(unsigned i=0;i<1;i++){for(const unsigned char *p=(const unsigned char *)fields[i];*p;p++){hash^=*p;hash*=1099511628211ull;}hash^=0xff;hash*=1099511628211ull;}
 for(unsigned i=0;i<g_recipe_scan_count;i++){
  const struct buffer_a2ui_component_s *c=&g_surface_snapshot.components[g_recipe_scan[i]];
  const char *parts[]={c->id,c->payload.recipe_step.title,c->payload.recipe_step.detail,c->payload.recipe_step.request_id};
  for(unsigned j=0;j<4;j++){for(const unsigned char *p=(const unsigned char *)parts[j];*p;p++){hash^=*p;hash*=1099511628211ull;}hash^=0xff;hash*=1099511628211ull;}
  for(unsigned b=0;b<4;b++){hash^=(c->payload.recipe_step.timer_ms>>(b*8))&255;hash*=1099511628211ull;}
 }
 snprintf(out,17,"%016llx",(unsigned long long)hash);
}
static bool ui_recipe_restore(struct recipe_session_s *session)
{
 if(!session->restore_pending)return false;
 struct buffer_ui_recipe_progress saved;
 int result=buffer_ui_recipe_state_get(session->surface,session->fingerprint,&saved);
 if(result==-EAGAIN)return false;
 session->restore_pending=false;
 if(result||saved.count!=session->count)return false;
 session->current=saved.current;session->done=saved.done;session->started=saved.started;session->overview=saved.overview;
 if(!session->operation_id[0])strcpy(session->operation_id,saved.operation_id);
 if(!session->capture_id[0])strcpy(session->capture_id,saved.capture_id);
 return true;
}
static void ui_recipe_save(struct recipe_session_s *session)
{
 session->restore_pending=false;
 struct buffer_ui_recipe_progress value={.count=session->count,.current=session->current,.done=session->done,.started=session->started,.overview=session->overview};
 snprintf(value.surface,sizeof(value.surface),"%s",session->surface);strcpy(value.fingerprint,session->fingerprint);strcpy(value.operation_id,session->operation_id);strcpy(value.capture_id,session->capture_id);
 int result=buffer_ui_recipe_state_set(&value);
 if(result)ui_failure("菜谱进度未保存，可稍后重试",result);
}
static struct recipe_session_s *ui_recipe_session(void)
{
 char fingerprint[17];ui_recipe_fingerprint(fingerprint);
 struct recipe_session_s *session=NULL,*available=NULL;
 for(unsigned i=0;i<RECIPE_SESSIONS;i++){
  struct recipe_session_s *s=&g_recipes[i];
  if(s->used&&!strcmp(s->surface,g_surface_snapshot.id)&&s->revision==g_surface_snapshot.task_revision)session=s;
  if(!available&&!s->used)available=s;
 }
 if(!session){
  if(!available)for(unsigned i=0;i<RECIPE_SESSIONS;i++){struct recipe_session_s *s=&g_recipes[i];if(!s->started||ui_recipe_completed(s)==s->count||s->obsolete){available=s;break;}}
  if(!available)return NULL;
  session=available;memset(session,0,sizeof(*session));session->used=true;
  strcpy(session->surface,g_surface_snapshot.id);session->revision=g_surface_snapshot.task_revision;
 }
 if(strcmp(session->fingerprint,fingerprint)){
  session->count=session->current=session->done=0;session->started=session->overview=false;session->restore_pending=true;strcpy(session->fingerprint,fingerprint);
 }
 char old_steps[BUFFER_ASSISTANT_MAX_RECIPE_STEPS][BUFFER_A2UI_COMPONENT_ID_SIZE];memcpy(old_steps,session->steps,sizeof(old_steps));
 unsigned old_done=session->done,old_count=session->count,old_current=session->current;
 session->count=g_recipe_scan_count;session->done=0;
 if(session->current>=session->count)session->current=session->count-1;
 for(unsigned i=0;i<session->count;i++){
  const char *id=g_surface_snapshot.components[g_recipe_scan[i]].id;strcpy(session->steps[i],id);
  for(unsigned j=0;j<old_count;j++)if(!strcmp(old_steps[j],id)){
   if(old_done&(1u<<j))session->done|=1u<<i;
   if(j==old_current)session->current=i;
  }
 }
 snprintf(session->operation_id,sizeof(session->operation_id),"%s",g_surface_snapshot.recipe_operation_id);
 if(g_surface_snapshot.capture_id[0])snprintf(session->capture_id,sizeof(session->capture_id),"%s",g_surface_snapshot.capture_id);
 snprintf(session->image_id,sizeof(session->image_id),"%s",g_surface_snapshot.image_id);
 buffer_notification_text(session->title,sizeof(session->title),g_recipe_has_title?g_recipe_title:"菜谱");(void)ui_recipe_restore(session);return session;
}
static void ui_recipe_update(bool navigate);
static void ui_recipe_action(lv_event_t *event)
{
 struct recipe_session_s *s=g_recipe.session;if(!s||strcmp(s->surface,g_surface_snapshot.id)||s->revision!=g_surface_snapshot.task_revision)return;
 unsigned action=(unsigned)(uintptr_t)lv_event_get_user_data(event);bool navigate=false;
 if(action>=10){unsigned selected=action-10;if(selected>=s->count)return;s->current=selected;s->overview=false;navigate=true;}
 else if(action==0){if(s->current)s->current--;s->overview=false;navigate=true;}
 else if(action==1){if(s->current+1<s->count)s->current++;s->overview=false;navigate=true;}
 else if(action==2)s->overview=!s->overview;
 else if(action==3){
  if(s->overview&&!g_recipe.wide){s->overview=false;navigate=true;}
  else if(!s->started)s->started=true;
  else{
   s->done^=1u<<s->current;
   if((s->done&(1u<<s->current))&&s->current+1<s->count){s->current++;navigate=true;}
  }
 }
 ui_recipe_save(s);ui_recipe_update(navigate);
}
static void ui_recipe_started(lv_event_t *event)
{(void)event;if(g_recipe.session){g_recipe.session->started=true;ui_recipe_save(g_recipe.session);ui_recipe_update(false);}}
static lv_obj_t *ui_recipe_button(lv_obj_t *parent,const char *text,unsigned action)
{
 lv_obj_t *button=ui_button_create(parent,text,UI_ACTION_GENERIC,0);
 lv_obj_remove_event_cb(button,ui_action_event);lv_obj_add_event_cb(button,ui_recipe_action,LV_EVENT_CLICKED,(void *)(uintptr_t)action);
 lv_obj_set_size(button,LV_SIZE_CONTENT,LV_SIZE_CONTENT);lv_obj_set_style_pad_ver(button,buffer_ui_dp(4),0);
 return button;
}
static void ui_recipe_deleted(lv_event_t *event)
{(void)event;memset(&g_recipe,0,sizeof(g_recipe));}
static void ui_recipe_layout(lv_event_t *event)
{
 (void)event;if(!g_recipe.root||!g_recipe.session)return;
 int width=lv_obj_get_content_width(g_recipe.root),height=lv_obj_get_content_height(g_recipe.root);
 int inset=buffer_ui_dp(10),pane=buffer_ui_dp(144)*(int)buffer_ui_font_scale()/100;
 bool wide=width>=pane+buffer_ui_dp(200)*(int)buffer_ui_font_scale()/100+3*inset&&height>=buffer_ui_dp(128);
 g_recipe.wide=wide;bool overview=g_recipe.session->overview;
 if(wide||overview)buffer_ui_remove_flag(g_recipe.meta,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.meta,LV_OBJ_FLAG_HIDDEN);
 if(wide||!overview)buffer_ui_remove_flag(g_recipe.reader,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.reader,LV_OBJ_FLAG_HIDDEN);
 lv_obj_set_width(g_recipe.meta,wide?pane+inset:LV_PCT(100));lv_obj_set_width(g_recipe.reader,wide?0:LV_PCT(100));
 lv_obj_set_style_pad_left(g_recipe.meta,inset,0);lv_obj_set_style_pad_right(g_recipe.meta,wide?0:inset,0);
 lv_obj_set_style_pad_ver(g_recipe.meta,inset,0);
 lv_obj_set_flex_grow(g_recipe.reader,wide?1:0);
 int footer=buffer_ui_dp(48),right=wide?width-pane-3*inset:width-2*inset;
 lv_obj_set_size(g_recipe.footer,right,footer);lv_obj_set_pos(g_recipe.footer,wide?pane+2*inset:inset,height-footer-inset);
 lv_obj_set_height(g_recipe.reader,height-footer-BUFFER_SECTION_GAP);lv_obj_set_height(g_recipe.meta,wide?height:height-footer-BUFFER_SECTION_GAP);
 int minimum=height-footer-BUFFER_SECTION_GAP-2*inset;if(minimum<0)minimum=0;
 lv_obj_set_style_min_height(g_recipe.reader_content,minimum,0);
 lv_obj_t *done_parent=wide?g_recipe.reader_content:g_recipe.footer;
 if(lv_obj_get_parent(g_recipe.done)!=done_parent){lv_obj_set_parent(g_recipe.done,done_parent);if(!wide)lv_obj_move_to_index(g_recipe.done,2);}
 lv_obj_set_width(g_recipe.done,wide?LV_PCT(100):0);lv_obj_set_flex_grow(g_recipe.done,wide?0:1);
 lv_obj_set_width(g_recipe.previous,wide?0:buffer_ui_dp(48));lv_obj_set_flex_grow(g_recipe.previous,wide?1:0);
 lv_obj_set_width(g_recipe.next,wide?0:buffer_ui_dp(48));lv_obj_set_flex_grow(g_recipe.next,wide?1:0);
 lv_obj_set_style_pad_column(g_recipe.footer,buffer_ui_dp(wide?3:4),0);
 lv_obj_set_style_radius(g_recipe.footer,wide?BUFFER_CARD_RADIUS:0,0);lv_obj_set_style_clip_corner(g_recipe.footer,wide,0);
 lv_obj_set_style_radius(g_recipe.previous,wide?buffer_ui_dp(3):BUFFER_BUTTON_RADIUS,0);lv_obj_set_style_radius(g_recipe.next,wide?buffer_ui_dp(3):BUFFER_BUTTON_RADIUS,0);
 if(overview||!buffer_ui_recipe_image_ready(g_recipe.image))buffer_ui_remove_flag(g_recipe.materials,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.materials,LV_OBJ_FLAG_HIDDEN);
 if(!overview&&buffer_ui_recipe_image_ready(g_recipe.image))buffer_ui_remove_flag(g_recipe.image_frame,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.image_frame,LV_OBJ_FLAG_HIDDEN);
 for(unsigned i=0;i<g_recipe.session->count;i++){if(overview)buffer_ui_remove_flag(g_recipe.step_buttons[i],LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.step_buttons[i],LV_OBJ_FLAG_HIDDEN);}
 if(wide&&!g_recipe.session->started)buffer_ui_add_flag(g_recipe.position,LV_OBJ_FLAG_HIDDEN);else buffer_ui_remove_flag(g_recipe.position,LV_OBJ_FLAG_HIDDEN);
 const char *done=overview&&!wide?"查看步骤":!g_recipe.session->started?"开始烹饪":
   (g_recipe.session->done&(1u<<g_recipe.session->current))?"标为未完成":"完成本步";
 lv_obj_t *label=lv_obj_get_child(g_recipe.done,0);lv_point_t text;
 int gap=buffer_ui_dp(4),target=buffer_ui_dp(48);
 int padding=lv_obj_get_style_pad_left(g_recipe.done,0)+lv_obj_get_style_pad_right(g_recipe.done,0);
 lv_text_get_size(&text,done,lv_obj_get_style_text_font(label,0),lv_obj_get_style_text_letter_space(label,0),0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
 int available=wide?right-buffer_ui_dp(20):right-3*(target+gap);
 if(text.x+padding>available)done=overview?"步骤":!g_recipe.session->started?"开始":
   (g_recipe.session->done&(1u<<g_recipe.session->current))?"未完成":"完成";
 lv_text_get_size(&text,done,lv_obj_get_style_text_font(label,0),lv_obj_get_style_text_letter_space(label,0),0,LV_COORD_MAX,LV_TEXT_FLAG_NONE);
 /* On the narrowest layouts keep step navigation and completion visible;
  * the secondary overview remains reachable in the scrolling reader. */
 bool overview_in_reader=!wide&&text.x+padding>available;
 if(wide||overview_in_reader)buffer_ui_add_flag(g_recipe.overview,LV_OBJ_FLAG_HIDDEN);else buffer_ui_remove_flag(g_recipe.overview,LV_OBJ_FLAG_HIDDEN);
 if(overview_in_reader)buffer_ui_remove_flag(g_recipe.materials_link,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.materials_link,LV_OBJ_FLAG_HIDDEN);
 buffer_ui_label_set_text(label,done);
}
static void ui_recipe_update(bool navigate)
{
 struct recipe_session_s *s=g_recipe.session;if(!s)return;
 unsigned index=g_recipe.indices[s->current];const struct buffer_a2ui_component_s *step=&g_surface_snapshot.components[index];
 buffer_ui_label_set_text(g_overlay_heading,s->title);buffer_ui_label_set_text(g_recipe.meta_name,s->title);
 char progress[128];snprintf(progress,sizeof(progress),"第 %u/%u 步 · 已勾选 %u 步",s->current+1,s->count,ui_recipe_completed(s));
 buffer_ui_label_set_text(g_recipe.position,progress);
 buffer_ui_label_set_text(g_recipe.title,step->payload.recipe_step.title);
 buffer_ui_label_set_text(g_recipe.detail,step->payload.recipe_step.detail);
 buffer_notification_text(s->current_title,sizeof(s->current_title),step->payload.recipe_step.title);
 uint64_t duration=0;for(unsigned i=0;i<s->count;i++)duration+=g_surface_snapshot.components[g_recipe.indices[i]].payload.recipe_step.timer_ms;
 char estimate[80];if(duration&&duration%3600000==0)snprintf(estimate,sizeof(estimate),"预计：%llu小时",(unsigned long long)(duration/3600000));
 else snprintf(estimate,sizeof(estimate),"预计：%llu分钟",(unsigned long long)((duration+59999)/60000));
 buffer_ui_label_set_text(g_recipe.estimate,estimate);if(duration)buffer_ui_remove_flag(g_recipe.estimate,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(g_recipe.estimate,LV_OBJ_FLAG_HIDDEN);
 for(unsigned i=0;i<BUFFER_ASSISTANT_MAX_RECIPE_STEPS;i++){
  if(i>=s->count){buffer_ui_add_flag(g_recipe.step_buttons[i],LV_OBJ_FLAG_HIDDEN);continue;}
  const struct buffer_a2ui_component_s *item=&g_surface_snapshot.components[g_recipe.indices[i]];
  char label[BUFFER_ASSISTANT_LABEL_SIZE+32];snprintf(label,sizeof(label),"%u. %s%s",i+1,item->payload.recipe_step.title,(s->done&(1u<<i))?" · 已勾选":"");
  buffer_ui_label_set_text(lv_obj_get_child(g_recipe.step_buttons[i],0),label);
  buffer_ui_remove_flag(g_recipe.step_buttons[i],LV_OBJ_FLAG_HIDDEN);
  if(i==s->current)lv_obj_add_state(g_recipe.step_buttons[i],LV_STATE_CHECKED);else lv_obj_remove_state(g_recipe.step_buttons[i],LV_STATE_CHECKED);
 }
 bool timer_changed=strcmp(g_recipe.timer_step,step->id)||strcmp(g_recipe.timer_id,step->payload.recipe_step.timer_id)||g_recipe.timer_ms!=step->payload.recipe_step.timer_ms||g_recipe.timer_index!=index;
 if(timer_changed){
  lv_obj_clean(g_recipe.timer);strcpy(g_recipe.timer_step,step->id);strcpy(g_recipe.timer_id,step->payload.recipe_step.timer_id);g_recipe.timer_ms=step->payload.recipe_step.timer_ms;g_recipe.timer_index=index;
  if(step->payload.recipe_step.timer_id[0])ui_timer_controls(&g_surface_snapshot,index,g_recipe.timer,step->payload.recipe_step.timer_id,"计时器",true);
  else if(step->payload.recipe_step.timer_ms){
   buffer_ui_style_bg_color(g_recipe.timer,lv_color_white(),0);buffer_ui_style_bg_opa(g_recipe.timer,LV_OPA_COVER,0);lv_obj_set_style_pad_all(g_recipe.timer,buffer_ui_dp(10),0);lv_obj_set_style_radius(g_recipe.timer,BUFFER_INNER_RADIUS,0);
   lv_obj_t *caption=ui_text_create(g_recipe.timer,"计时器",false,false);buffer_ui_text_role(caption,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);
   lv_obj_t *line=lv_obj_create(g_recipe.timer);lv_obj_remove_style_all(line);lv_obj_set_size(line,LV_PCT(100),LV_SIZE_CONTENT);lv_obj_set_flex_flow(line,LV_FLEX_FLOW_ROW);lv_obj_set_flex_align(line,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);buffer_ui_remove_flag(line,LV_OBJ_FLAG_SCROLLABLE);
   char remaining[32];unsigned seconds=(step->payload.recipe_step.timer_ms+999)/1000;snprintf(remaining,sizeof(remaining),"%u:%02u",seconds/60,seconds%60);
   lv_obj_t *time=ui_text_create(line,remaining,false,false);buffer_ui_text_role(time,BUFFER_TEXT_DISPLAY_MEDIUM);lv_obj_set_width(time,0);lv_obj_set_flex_grow(time,1);
   lv_obj_t *start=ui_button_create(line,"开始本步计时",UI_ACTION_RECIPE_START,index);buffer_ui_icon_button(start,&buffer_play_arrow_icon);lv_obj_set_size(start,buffer_ui_dp(48),buffer_ui_dp(48));
   lv_obj_add_event_cb(start,ui_recipe_started,LV_EVENT_CLICKED,NULL);
  }else ui_text_create(g_recipe.timer,"本步无需计时",false,false);
 }
 if(s->current)lv_obj_remove_state(g_recipe.previous,LV_STATE_DISABLED);else lv_obj_add_state(g_recipe.previous,LV_STATE_DISABLED);
 if(s->current+1<s->count)lv_obj_remove_state(g_recipe.next,LV_STATE_DISABLED);else lv_obj_add_state(g_recipe.next,LV_STATE_DISABLED);
 ui_recipe_layout(NULL);watch_tree(g_recipe.root);
 if(navigate){lv_obj_scroll_to_y(g_recipe.reader,0,LV_ANIM_OFF);}
}
static lv_obj_t *ui_recipe_plain(lv_obj_t *parent)
{
 lv_obj_t *obj=lv_obj_create(parent);lv_obj_remove_style_all(obj);
 lv_obj_set_size(obj,LV_PCT(100),LV_SIZE_CONTENT);lv_obj_set_flex_flow(obj,LV_FLEX_FLOW_COLUMN);
 lv_obj_set_style_pad_row(obj,BUFFER_SECTION_GAP,0);buffer_ui_remove_flag(obj,LV_OBJ_FLAG_SCROLLABLE);return obj;
}
static bool ui_recipe_render(unsigned root)
{
 g_recipe_scan_count=0;g_recipe_has_title=false;g_recipe_materials[0]=g_recipe_title[0]=0;
 if(!ui_recipe_collect(root,0)||!g_recipe_scan_count)return false;
 struct recipe_session_s *session=ui_recipe_session();
 if(!session){ui_failure("进行中的菜谱较多，先以完整步骤列表显示",-ENOSPC);return false;}
 if(!g_recipe.root){
  lv_obj_clean(g_render_parent);g_dynamic_label_count=0;
  g_recipe.root=ui_recipe_plain(g_render_parent);lv_obj_set_size(g_recipe.root,LV_PCT(100),LV_PCT(100));
  lv_obj_set_layout(g_recipe.root,LV_LAYOUT_NONE);
  lv_obj_set_style_min_height(g_recipe.root,0,0);lv_obj_add_event_cb(g_recipe.root,ui_recipe_deleted,LV_EVENT_DELETE,NULL);
  g_recipe.body=ui_recipe_plain(g_recipe.root);lv_obj_set_size(g_recipe.body,LV_PCT(100),LV_PCT(100));lv_obj_set_style_min_height(g_recipe.body,0,0);
  lv_obj_set_flex_flow(g_recipe.body,LV_FLEX_FLOW_ROW);lv_obj_set_style_pad_column(g_recipe.body,0,0);
  g_recipe.meta=ui_recipe_plain(g_recipe.body);lv_obj_set_height(g_recipe.meta,LV_PCT(100));buffer_ui_add_flag(g_recipe.meta,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_recipe.meta,LV_DIR_VER);
  g_recipe.summary=ui_recipe_plain(g_recipe.meta);buffer_ui_style_bg_color(g_recipe.summary,lv_color_hex(BUFFER_ACCENT),0);buffer_ui_style_bg_opa(g_recipe.summary,LV_OPA_COVER,0);lv_obj_set_style_radius(g_recipe.summary,buffer_ui_dp(20),0);lv_obj_set_style_pad_all(g_recipe.summary,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_recipe.summary,0,0);
  lv_obj_add_flag(g_recipe.summary,LV_OBJ_FLAG_CLICKABLE);lv_obj_add_event_cb(g_recipe.summary,ui_recipe_action,LV_EVENT_CLICKED,(void *)2);
  lv_obj_t *kind=ui_text_create(g_recipe.summary,"菜谱",false,false);buffer_ui_text_role(kind,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);buffer_ui_style_text_color(kind,lv_color_white(),0);
  g_recipe.meta_name=ui_text_create(g_recipe.summary,session->title,false,false);buffer_ui_text_role(g_recipe.meta_name,BUFFER_TEXT_HEADLINE_SMALL);buffer_ui_style_text_color(g_recipe.meta_name,lv_color_white(),0);
  g_recipe.estimate=ui_text_create(g_recipe.summary,"",false,false);buffer_ui_text_role(g_recipe.estimate,BUFFER_TEXT_BODY_MEDIUM);buffer_ui_style_text_color(g_recipe.estimate,lv_color_white(),0);
  g_recipe.image=buffer_ui_recipe_image_create(g_recipe.meta,buffer_ui_dp(20),&g_recipe.image_frame);
  lv_obj_set_size(g_recipe.image_frame,LV_PCT(100),0);lv_obj_set_flex_grow(g_recipe.image_frame,1);lv_obj_set_style_min_height(g_recipe.image_frame,buffer_ui_dp(80),0);
  lv_obj_set_style_border_width(g_recipe.image_frame,buffer_ui_dp(5),0);lv_obj_set_style_border_color(g_recipe.image_frame,lv_color_hex(BUFFER_ACCENT),0);lv_obj_set_style_border_post(g_recipe.image_frame,true,0);
  g_recipe.materials=ui_text_create(g_recipe.meta,"",false,false);
  for(unsigned i=0;i<BUFFER_ASSISTANT_MAX_RECIPE_STEPS;i++){
   g_recipe.step_buttons[i]=ui_recipe_button(g_recipe.meta,"",10+i);
   lv_obj_set_width(g_recipe.step_buttons[i],LV_PCT(100));
  }
  g_recipe.reader=ui_recipe_plain(g_recipe.body);lv_obj_set_height(g_recipe.reader,LV_PCT(100));buffer_ui_add_flag(g_recipe.reader,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_recipe.reader,LV_DIR_VER);
  lv_obj_set_style_pad_all(g_recipe.reader,buffer_ui_dp(10),0);
  g_recipe.reader_content=ui_recipe_plain(g_recipe.reader);
  buffer_ui_style_bg_color(g_recipe.reader_content,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(g_recipe.reader_content,LV_OPA_COVER,0);lv_obj_set_style_radius(g_recipe.reader_content,buffer_ui_dp(20),0);lv_obj_set_style_pad_all(g_recipe.reader_content,buffer_ui_dp(10),0);lv_obj_set_style_pad_row(g_recipe.reader_content,buffer_ui_dp(4),0);
  g_recipe.title=ui_text_create(g_recipe.reader_content,"",true,false);g_recipe.detail=ui_text_create(g_recipe.reader_content,"",false,false);
  buffer_ui_text_role(g_recipe.title,BUFFER_TEXT_HEADLINE_SMALL);buffer_ui_style_text_color(g_recipe.title,lv_color_hex(BUFFER_ON_SURFACE),0);buffer_ui_text_role(g_recipe.detail,BUFFER_TEXT_BODY_MEDIUM);
  g_recipe.timer=ui_recipe_plain(g_recipe.reader_content);
  g_recipe.position=ui_text_create(g_recipe.reader_content,"",false,false);buffer_ui_text_role(g_recipe.position,BUFFER_TEXT_LABEL_LARGE);
  g_recipe.materials_link=ui_recipe_button(g_recipe.reader_content,"材料与步骤",2);
  g_recipe.footer=ui_recipe_plain(g_recipe.root);lv_obj_set_flex_flow(g_recipe.footer,LV_FLEX_FLOW_ROW);
  buffer_ui_add_flag(g_recipe.footer,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_recipe.footer,LV_DIR_HOR);
  lv_obj_set_style_pad_column(g_recipe.footer,buffer_ui_dp(4),0);
  g_recipe.previous=ui_recipe_button(g_recipe.footer,"上一步",0);buffer_ui_icon_button(g_recipe.previous,&buffer_back_icon);
  lv_obj_set_size(g_recipe.previous,buffer_ui_dp(48),buffer_ui_dp(48));
  g_recipe.overview=ui_recipe_button(g_recipe.footer,"概览",2);buffer_ui_icon_button(g_recipe.overview,&buffer_checklist_icon);
  lv_obj_set_size(g_recipe.overview,buffer_ui_dp(48),buffer_ui_dp(48));
  g_recipe.done=ui_recipe_button(g_recipe.footer,"开始烹饪",3);buffer_ui_button_style(g_recipe.done,true);lv_obj_set_width(g_recipe.done,0);lv_obj_set_flex_grow(g_recipe.done,1);
  g_recipe.next=ui_recipe_button(g_recipe.footer,"下一步",1);lv_obj_t *forward=buffer_ui_icon_button(g_recipe.next,&buffer_back_icon);lv_image_set_rotation(forward,1800);
  lv_obj_set_size(g_recipe.next,buffer_ui_dp(48),buffer_ui_dp(48));
  lv_obj_add_event_cb(g_recipe.root,ui_recipe_layout,LV_EVENT_SIZE_CHANGED,NULL);
 }
 g_recipe.session=session;memcpy(g_recipe.indices,g_recipe_scan,sizeof(g_recipe.indices));
 if(buffer_ui_recipe_image_bind(g_recipe.image,session->image_id))lv_image_set_inner_align(g_recipe.image,LV_IMAGE_ALIGN_STRETCH);
 buffer_ui_label_set_text(g_recipe.materials,g_recipe_materials[0]?g_recipe_materials:"未提供材料清单，可查看步骤说明。");
 ui_recipe_update(false);buffer_ui_remove_flag(g_render_parent,LV_OBJ_FLAG_SCROLLABLE);return true;
}
bool buffer_a2ui_ui_recipe_running(const char *id)
{
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(g_recovered_recipe_live[i][0]&&!strcmp(g_recovered_recipe_live[i],id))return true;
 for(unsigned i=0;i<RECIPE_SESSIONS;i++)if(g_recipes[i].used&&g_recipes[i].started&&!g_recipes[i].obsolete&&!strcmp(g_recipes[i].surface,id)&&ui_recipe_completed(&g_recipes[i])<g_recipes[i].count)return true;
 return false;
}
bool buffer_a2ui_ui_recipe_result_visible(const char *id,const char *fingerprint)
{
 for(unsigned i=0;i<RECIPE_SESSIONS;i++){
  const struct recipe_session_s *s=&g_recipes[i];
  if(s->used&&!s->obsolete&&!strcmp(s->surface,id)&&!strcmp(s->fingerprint,fingerprint))return !s->started;
 }
 struct buffer_ui_recipe_progress saved;
 int result=buffer_ui_recipe_state_get(id,fingerprint,&saved);
 return result==-EAGAIN?false:result?true:!saved.started;
}
static void ui_recipe_publish_activity(const struct recipe_session_s *s,uint64_t now)
{
 uint64_t hash=1469598103934665603ull;for(const unsigned char *p=(const unsigned char *)s->surface;*p;p++){hash^=*p;hash*=1099511628211ull;}
 char key[80],text[160];snprintf(key,sizeof(key),"recipe:%016llx-%016llx",(unsigned long long)hash,(unsigned long long)s->revision);
 unsigned completed=ui_recipe_completed(s);
 if(s->obsolete)snprintf(text,sizeof(text),"菜谱内容已更新，已有计时不受影响");
 else snprintf(text,sizeof(text),"第 %u/%u 步 · 已勾选 %u 步",s->current+1,s->count,completed);
 struct buffer_notification notice;if(buffer_notification_build(&notice,key,"assistant.recipe",s->title,text))return;
 strcpy(notice.channel,"cooking");strcpy(notice.status,"烹饪步骤");notice.privacy=BUFFER_NOTICE_SENSITIVE;
 buffer_notification_text(notice.artwork,sizeof(notice.artwork),s->image_id);
 notice.phase=s->obsolete?BUFFER_NOTICE_ERROR:completed==s->count?BUFFER_NOTICE_COMPLETE:BUFFER_NOTICE_RUNNING;
 notice.live=notice.phase==BUFFER_NOTICE_RUNNING;notice.desktop=completed<s->count;
 notice.style=BUFFER_NOTICE_PROGRESS;notice.determinate=true;notice.progress=completed;notice.progress_max=s->count;
 if(!notice.live)notice.expires_after_ms=300000;
 notice.action_count=1;strcpy(notice.actions[0].id,"open");strcpy(notice.actions[0].label,"查看步骤");strcpy(notice.actions[0].route,"recipe");strcpy(notice.actions[0].object,s->surface);
 (void)buffer_notification_publish(&notice,now);
}
void buffer_a2ui_ui_publish_activities(uint64_t now)
{
 struct buffer_assistant_surface_card_s cards[BUFFER_ASSISTANT_SURFACE_CARDS];
 int count=buffer_a2ui_surface_cards(cards,BUFFER_ASSISTANT_SURFACE_CARDS);if(count<0)return;
 struct buffer_ui_recipe_progress saved[BUFFER_RECIPE_SAVED];bool found[BUFFER_RECIPE_SAVED]={false};
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++){
  int result=buffer_ui_recipe_state_slot(i,&saved[i]);if(result==-EAGAIN)return;found[i]=!result;
 }
 memset(g_recovered_recipe_live,0,sizeof(g_recovered_recipe_live));
 unsigned recovered_count=0;
 unsigned batch=buffer_notification_begin("assistant.recipe");
 for(unsigned i=0;i<RECIPE_SESSIONS;i++){
  struct recipe_session_s *session=&g_recipes[i];if(!session->used||!session->started)continue;
  uint64_t revision;int result=buffer_a2ui_model_surface_revision(session->surface,&revision);
  if(result==-ENOENT||(!result&&revision!=session->revision))session->obsolete=true;
  ui_recipe_publish_activity(session,now);
 }
 /* Progress cards can recover before the user opens the recipe. The source
  * operation and exact step fingerprint must match the rebuilt model. */
 for(unsigned i=0;i<BUFFER_RECIPE_SAVED;i++)if(found[i]&&saved[i].started&&saved[i].done!=((1u<<saved[i].count)-1u)){
  bool visible=false;for(unsigned j=0;j<RECIPE_SESSIONS;j++)if(g_recipes[j].used&&!strcmp(g_recipes[j].surface,saved[i].surface))visible=true;
  if(visible)continue;
  for(int j=0;j<count;j++)if(cards[j].recipe&&saved[i].operation_id[0]&&!strcmp(cards[j].id,saved[i].surface)&&!strcmp(cards[j].recipe_operation_id,saved[i].operation_id)&&!strcmp(cards[j].recipe_fingerprint,saved[i].fingerprint)){
   struct recipe_session_s recovered={.used=true,.started=true,.current=saved[i].current,.count=saved[i].count,.done=saved[i].done,.revision=cards[j].task_revision};
   strcpy(recovered.surface,cards[j].id);strcpy(recovered.title,cards[j].title);strcpy(recovered.image_id,cards[j].image_id);ui_recipe_publish_activity(&recovered,now);strcpy(g_recovered_recipe_live[recovered_count++],cards[j].id);break;
  }
 }
 buffer_notification_end("assistant.recipe",batch);
}
