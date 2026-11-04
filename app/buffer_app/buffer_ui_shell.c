/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_ui_shell.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_decor.h"
#include "buffer_ui_icons.h"
#include <string.h>
#include <stdio.h>
#include <errno.h>

#define SURFACES 12
struct surface {
  char id[BUFFER_UI_SURFACE_TEXT];
  struct buffer_ui_surface_info info;
  enum buffer_ui_surface_kind kind;
  enum buffer_ui_capture_mode capture_mode;
  lv_obj_t *content, *focus;
  lv_obj_t *local_focus[4];unsigned local_depth;
  buffer_ui_surface_closed closed;
  void *context;
  buffer_ui_surface_back back;
  void *back_context;
  buffer_ui_surface_prepare prepare_open;
  buffer_ui_surface_activated activated;
  lv_group_t *group;
};
static struct surface surfaces[SURFACES];
static lv_obj_t *shell_root, *desktop_root, *scrim, *capture_bar, *capture_preview;
static lv_obj_t *status_bar,*capture_backdrop,*capture_return_focus;
static lv_obj_t *capture_dock,*capture_scroll;
static bool capture_expanded;
static int32_t capture_preview_rest_height;
static struct surface *gesture_surface;
static lv_indev_t *gesture_input;
static lv_point_t gesture_start;
static char status_route[BUFFER_UI_SURFACE_TEXT];
static lv_indev_t *status_input;
static lv_point_t status_start;
static struct surface *status_drag_surface;
static bool status_owned;
static bool status_drag_preprocess(lv_indev_t *input,lv_indev_data_t *data,lv_point_t point);
static void overlay_settle(struct surface *surface,bool opened);

static struct surface *app, *overlay;
static bool arranging;
static void edge_register(lv_indev_t *input);
static void edge_shutdown(void);
#include "buffer_ui_shell_focus.inc"
#include "buffer_ui_shell_back_gesture.inc"

static struct surface *find(const char *id)
{
  if (!id) return NULL;
  for (unsigned i=0;i<SURFACES;i++)
    if (surfaces[i].content && !strcmp(surfaces[i].id,id)) return &surfaces[i];
  return NULL;
}
const char *buffer_ui_shell_foreground(void)
{return overlay?overlay->id:app?app->id:"";}
static void surface_gesture(lv_event_t *event)
{
 struct surface *surface=lv_event_get_user_data(event);lv_indev_t *input=lv_indev_active();
 if(!input||lv_indev_get_type(input)!=LV_INDEV_TYPE_POINTER||lv_event_get_target(event)!=surface->content)return;
 lv_event_code_t code=lv_event_get_code(event);
 if(code==LV_EVENT_PRESSED){gesture_surface=surface;gesture_input=input;lv_indev_get_point(input,&gesture_start);return;}
 if(gesture_surface!=surface||gesture_input!=input)return;
 if(code==LV_EVENT_RELEASED||code==LV_EVENT_PRESS_LOST){gesture_surface=NULL;gesture_input=NULL;return;}
 if(code!=LV_EVENT_PRESSING)return;
 lv_point_t point;lv_indev_get_point(input,&point);int dx=point.x-gesture_start.x,dy=point.y-gesture_start.y;
 bool dismiss=surface->kind==BUFFER_UI_SYSTEM_OVERLAY&&dy<=-buffer_ui_dp(32)&&-dy>LV_ABS(dx);
 if(dismiss){gesture_surface=NULL;gesture_input=NULL;lv_indev_wait_release(input);(void)buffer_ui_shell_back();}
}
static void surface_deleted(lv_event_t *event)
{
  struct surface *surface=lv_event_get_user_data(event);
  if(gesture_surface==surface){gesture_surface=NULL;gesture_input=NULL;}
  if (app==surface) {
    app=NULL;if(desktop_root)buffer_ui_remove_flag(desktop_root,LV_OBJ_FLAG_HIDDEN);
  }
  if (overlay==surface) {
    overlay=NULL;if(scrim)buffer_ui_add_flag(scrim,LV_OBJ_FLAG_HIDDEN);
  }
  if(surface->group)for(lv_indev_t *i=lv_indev_get_next(NULL);i;i=lv_indev_get_next(i))if(lv_indev_get_group(i)==surface->group){if(lv_indev_get_state(i)==LV_INDEV_STATE_PRESSED)lv_indev_wait_release(i);lv_indev_reset(i,NULL);lv_indev_set_group(i,focus_base);}
  if(surface->group)lv_group_delete(surface->group);
  memset(surface,0,sizeof(*surface));
  focus_schedule();
}
static void root_changed(lv_event_t *event)
{
  if (lv_event_get_code(event)==LV_EVENT_SIZE_CHANGED){edge_invalidate();buffer_ui_shell_layout();}
  else if(lv_event_get_code(event)==LV_EVENT_DELETE){
    edge_shutdown();
    focus_shutdown();
    shell_root=desktop_root=scrim=capture_bar=capture_preview=status_bar=capture_backdrop=capture_return_focus=NULL;status_input=NULL;status_route[0]=0;
    app=overlay=NULL;
  }
}
static void scrim_pressed(lv_event_t *event)
{ (void)event; if (overlay) buffer_ui_shell_hide(overlay->id); }
static void capture_deleted(lv_event_t *event)
{
 lv_obj_t *object=lv_event_get_current_target(event);
 if(capture_bar==object){capture_bar=NULL;capture_expanded=false;capture_return_focus=NULL;}
 if(capture_preview==object)capture_preview=NULL;
 focus_schedule();
}
static void overlay_translate(void *object,int32_t value)
{
 lv_obj_t *content=object;lv_obj_set_style_translate_y(content,value,0);
 if(overlay&&overlay->content==content&&scrim){
  int height=lv_obj_get_height(content);int shown=height+value;
  lv_obj_set_style_bg_opa(scrim,height>0?LV_CLAMP(0,LV_OPA_40*shown/height,LV_OPA_40):0,0);
 }
}
static void overlay_settled(lv_anim_t *animation)
{
 lv_obj_t *content=animation->var;
 if(overlay&&overlay->content==content&&animation->end_value<0)buffer_ui_shell_hide(overlay->id);
}
static void overlay_settle(struct surface *surface,bool opened)
{
 if(!surface||surface!=overlay)return;
 lv_obj_t *content=surface->content;buffer_ui_motion_cancel(content);lv_anim_delete(content,overlay_translate);
 int from=lv_obj_get_style_translate_y(content,0),to=opened?0:-lv_obj_get_height(content);
 if(buffer_ui_motion_reduced()){
  overlay_translate(content,to);if(!opened)buffer_ui_shell_hide(surface->id);return;
 }
 lv_anim_t animation;lv_anim_init(&animation);lv_anim_set_var(&animation,content);
 lv_anim_set_values(&animation,from,to);lv_anim_set_duration(&animation,200);
 lv_anim_set_exec_cb(&animation,overlay_translate);lv_anim_set_path_cb(&animation,lv_anim_path_ease_out);
 lv_anim_set_completed_cb(&animation,overlay_settled);lv_anim_start(&animation);
}
static bool status_drag_preprocess(lv_indev_t *input,lv_indev_data_t *data,lv_point_t point)
{
 if(input!=status_input)return false;
 int dx=LV_ABS(point.x-status_start.x),dy=point.y-status_start.y;
 if(data->state==LV_INDEV_STATE_RELEASED){
  struct surface *surface=status_drag_surface;bool owned=status_owned;
  status_input=NULL;status_drag_surface=NULL;status_owned=false;
  if(owned){lv_indev_reset(input,NULL);overlay_settle(surface,dy>=buffer_ui_dp(32)&&dy>dx);return true;}
  return false;
 }
 if(!status_owned&&(dy<-buffer_ui_dp(8)||(dx>buffer_ui_dp(16)&&dx>=dy))){status_input=NULL;return false;}
 if(!status_owned&&dy>=buffer_ui_dp(8)&&dy>dx){
  status_owned=true;
  if(buffer_ui_shell_open(status_route,NULL)){status_input=NULL;status_owned=false;return false;}
  status_drag_surface=overlay;
  buffer_ui_motion_cancel(overlay->content);lv_anim_delete(overlay->content,overlay_translate);
  lv_obj_set_style_opa(overlay->content,LV_OPA_COVER,0);
 }
 if(!status_owned)return false;
 if(!overlay||status_drag_surface!=overlay){status_input=NULL;status_drag_surface=NULL;status_owned=false;lv_indev_reset(input,NULL);return true;}
 int height=lv_obj_get_height(overlay->content);
 overlay_translate(overlay->content,LV_CLAMP(-height,dy-height,0));
 input->pointer.last_raw_point=data->point;return true;
}
static void status_event(lv_event_t *event)
{
 lv_event_code_t code=lv_event_get_code(event);lv_indev_t *input=lv_indev_active();
 if(code==LV_EVENT_DELETE){status_bar=NULL;status_input=NULL;status_drag_surface=NULL;status_owned=false;status_route[0]=0;focus_schedule();return;}
 if(code==LV_EVENT_SIZE_CHANGED){buffer_ui_shell_layout();return;}
 if(code==LV_EVENT_CLICKED){
  if(input&&(lv_indev_get_type(input)==LV_INDEV_TYPE_KEYPAD||lv_indev_get_type(input)==LV_INDEV_TYPE_ENCODER))
   (void)buffer_ui_shell_open(status_route,NULL);
  return;
 }
 if(capture_expanded||!input||lv_indev_get_type(input)!=LV_INDEV_TYPE_POINTER)return;
 if(code==LV_EVENT_PRESSED&&!overlay){edge_register(input);status_input=input;status_owned=false;status_drag_surface=NULL;lv_indev_get_point(input,&status_start);}
 if((code==LV_EVENT_RELEASED||code==LV_EVENT_PRESS_LOST)&&!status_owned&&status_input==input)status_input=NULL;
}
bool buffer_ui_shell_statusbar(lv_obj_t *bar,const char *route)
{
 if(!shell_root||!bar||!route||!*route||strlen(route)>=sizeof(status_route)||(status_bar&&status_bar!=bar))return false;
 if(status_bar)lv_obj_remove_event_cb(status_bar,status_event);
 status_bar=bar;status_input=NULL;snprintf(status_route,sizeof(status_route),"%s",route);
 lv_obj_set_parent(bar,shell_root);lv_obj_set_align(bar,LV_ALIGN_TOP_LEFT);
 lv_obj_add_flag(bar,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_PRESS_LOCK);
 lv_obj_remove_flag(bar,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_SCROLL_CHAIN|LV_OBJ_FLAG_GESTURE_BUBBLE|LV_OBJ_FLAG_CLICK_FOCUSABLE);
 lv_obj_set_style_outline_color(bar,lv_color_hex(BUFFER_ACCENT),LV_STATE_FOCUS_KEY);
 lv_obj_set_style_outline_width(bar,buffer_ui_dp(2),LV_STATE_FOCUS_KEY);
 lv_obj_set_style_outline_pad(bar,-buffer_ui_dp(2),LV_STATE_FOCUS_KEY);
 static const lv_event_code_t codes[]={LV_EVENT_DELETE,LV_EVENT_SIZE_CHANGED,LV_EVENT_PRESSED,LV_EVENT_PRESSING,LV_EVENT_RELEASED,LV_EVENT_PRESS_LOST,LV_EVENT_CLICKED};
 for(unsigned i=0;i<sizeof(codes)/sizeof(codes[0]);i++)lv_obj_add_event_cb(bar,status_event,codes[i],NULL);
 lv_obj_move_foreground(bar);focus_schedule();buffer_ui_shell_layout();return true;
}
void buffer_ui_shell_init(lv_obj_t *root, lv_obj_t *desktop)
{
  buffer_ui_shell_shutdown();
  shell_root=root;desktop_root=desktop;
  if (!root) return;
  focus_init();
  scrim=lv_obj_create(root);lv_obj_remove_style_all(scrim);
  lv_obj_add_flag(scrim,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_HIDDEN);
  lv_obj_remove_flag(scrim,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_size(scrim,LV_PCT(100),LV_PCT(100));
  lv_obj_set_style_bg_color(scrim,lv_color_black(),0);
  lv_obj_set_style_bg_opa(scrim,LV_OPA_40,0);
  lv_obj_add_event_cb(scrim,scrim_pressed,LV_EVENT_CLICKED,NULL);
  capture_backdrop=lv_obj_create(root);lv_obj_remove_style_all(capture_backdrop);
  lv_obj_add_flag(capture_backdrop,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_HIDDEN|LV_OBJ_FLAG_CLICKABLE);
  lv_obj_remove_flag(capture_backdrop,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICK_FOCUSABLE|LV_OBJ_FLAG_SCROLL_CHAIN|LV_OBJ_FLAG_GESTURE_BUBBLE);
  lv_obj_set_style_bg_color(capture_backdrop,lv_color_hex(BUFFER_BG),0);lv_obj_set_style_bg_opa(capture_backdrop,LV_OPA_COVER,0);
  lv_obj_add_event_cb(root,root_changed,LV_EVENT_SIZE_CHANGED,NULL);
  lv_obj_add_event_cb(root,root_changed,LV_EVENT_DELETE,NULL);
  lv_obj_add_event_cb(root,focus_tree_changed,LV_EVENT_CHILD_CREATED,NULL);
  lv_obj_add_event_cb(root,focus_tree_changed,LV_EVENT_CHILD_DELETED,NULL);
}
void buffer_ui_shell_shutdown(void)
{
  edge_shutdown();
  buffer_ui_shell_capture_dock(NULL);capture_expanded=false;
  if (shell_root) lv_obj_remove_event_cb(shell_root,root_changed);
  if(capture_bar)lv_obj_remove_event_cb(capture_bar,capture_deleted);
  if(capture_preview)lv_obj_remove_event_cb(capture_preview,capture_deleted);
  if(status_bar)lv_obj_remove_event_cb(status_bar,status_event);
  focus_shutdown();
  for (unsigned i=0;i<SURFACES;i++)
    if(surfaces[i].content){lv_obj_remove_event_cb(surfaces[i].content,surface_deleted);lv_obj_remove_event_cb(surfaces[i].content,surface_gesture);}
  if (scrim) lv_obj_delete(scrim);
  if(capture_backdrop)lv_obj_delete(capture_backdrop);
  memset(surfaces,0,sizeof(surfaces));app=overlay=NULL;
  shell_root=desktop_root=scrim=capture_bar=capture_preview=status_bar=capture_backdrop=capture_return_focus=NULL;status_input=gesture_input=NULL;gesture_surface=NULL;status_route[0]=0;arranging=false;
}
bool buffer_ui_shell_mount(const char *id, enum buffer_ui_surface_kind kind,
                           lv_obj_t *content, buffer_ui_surface_closed closed, void *context)
{
  const struct buffer_ui_surface_declaration declaration={.id=id,.title=id,.entry_route=id,.kind=kind,.closed=closed,.context=context};
  return buffer_ui_shell_declare(&declaration,content);
}
bool buffer_ui_shell_declare(const struct buffer_ui_surface_declaration *d,lv_obj_t *content)
{
  /* Standalone component hosts can run without a system Shell. Production
   * initializes it before attaching any application. */
  if (!shell_root || !d || !d->id || !*d->id || !d->title || !*d->title || !d->entry_route || !*d->entry_route || !content ||find(d->id)||
      (d->kind!=BUFFER_UI_APP&&d->kind!=BUFFER_UI_SYSTEM_OVERLAY)||(unsigned)d->capture_mode>BUFFER_UI_CAPTURE_HIDDEN||d->action_count>BUFFER_UI_SURFACE_ACTIONS||
      strlen(d->id)>=BUFFER_UI_SURFACE_TEXT||strlen(d->title)>=BUFFER_UI_SURFACE_TEXT||strlen(d->entry_route)>=BUFFER_UI_SURFACE_TEXT||
      (d->action_count&&!d->actions))return false;
  for(unsigned i=0;i<d->action_count;i++){
    if(!d->actions[i]||!*d->actions[i]||strlen(d->actions[i])>=32)return false;
    for(unsigned j=0;j<i;j++)if(!strcmp(d->actions[i],d->actions[j]))return false;
  }
  for(unsigned i=0;i<SURFACES;i++)if(surfaces[i].content&&(surfaces[i].content==content||!strcmp(surfaces[i].info.entry_route,d->entry_route)))return false;
  struct surface *surface=NULL;
  for (unsigned i=0;i<SURFACES;i++)if(!surfaces[i].content){surface=&surfaces[i];break;}
  if (!surface) return false;
  lv_group_t *group=lv_group_create();if(!group)return false;
  lv_group_set_focus_cb(group,focus_group_changed);
  memset(surface,0,sizeof(*surface));snprintf(surface->id,sizeof(surface->id),"%s",d->id);
  surface->kind=d->kind;surface->content=content;surface->closed=d->closed;surface->context=d->context;surface->back=d->back;surface->back_context=d->context;surface->group=group;
  surface->prepare_open=d->prepare_open;surface->activated=d->activated;
  surface->capture_mode=d->capture_mode;
  struct buffer_ui_surface_info *info=&surface->info;
  snprintf(info->id,sizeof(info->id),"%s",d->id);snprintf(info->title,sizeof(info->title),"%s",d->title);snprintf(info->entry_route,sizeof(info->entry_route),"%s",d->entry_route);
  info->kind=d->kind;info->min_width_dp=d->min_width_dp;info->min_height_dp=d->min_height_dp;info->preferred_width_dp=d->preferred_width_dp;info->action_count=d->action_count;
  for(unsigned i=0;i<d->action_count;i++)snprintf(info->actions[i],sizeof(info->actions[i]),"%s",d->actions[i]);
  lv_obj_set_parent(content,shell_root);
  lv_obj_set_align(content,LV_ALIGN_TOP_LEFT);
  lv_obj_add_flag(content,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_HIDDEN|LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_PRESS_LOCK);
  lv_obj_add_event_cb(content,surface_gesture,LV_EVENT_ALL,surface);
  lv_obj_remove_flag(content,LV_OBJ_FLAG_SCROLL_CHAIN|LV_OBJ_FLAG_GESTURE_BUBBLE);
  buffer_ui_type(content,0);
  lv_obj_set_style_bg_color(content,lv_color_hex(BUFFER_BG),0);
  lv_obj_set_style_bg_opa(content,LV_OPA_COVER,0);
  lv_obj_add_event_cb(content,surface_deleted,LV_EVENT_DELETE,surface);
  buffer_ui_shell_layout();
  focus_schedule();
  return true;
}
bool buffer_ui_shell_info(const char *id,struct buffer_ui_surface_info *info)
{struct surface *surface=find(id);if(!surface||!info)return false;*info=surface->info;return true;}
void buffer_ui_shell_on_back(const char *id,buffer_ui_surface_back handler,void *context)
{struct surface *surface=find(id);if(surface){surface->back=handler;surface->back_context=context;}}
void buffer_ui_shell_capture(lv_obj_t *capture,lv_obj_t *preview)
{
 if(!shell_root)return;
 if(capture_bar)lv_obj_remove_event_cb(capture_bar,capture_deleted);
 if(capture_preview)lv_obj_remove_event_cb(capture_preview,capture_deleted);
 capture_bar=capture;capture_preview=preview;
 if(capture){lv_obj_set_parent(capture,shell_root);lv_obj_set_align(capture,LV_ALIGN_TOP_LEFT);lv_obj_add_flag(capture,LV_OBJ_FLAG_FLOATING);lv_obj_add_event_cb(capture,capture_deleted,LV_EVENT_DELETE,NULL);}
 if(preview){lv_obj_set_parent(preview,shell_root);lv_obj_set_align(preview,LV_ALIGN_TOP_LEFT);lv_obj_add_flag(preview,LV_OBJ_FLAG_FLOATING|LV_OBJ_FLAG_CLICKABLE);lv_obj_add_event_cb(preview,capture_deleted,LV_EVENT_DELETE,NULL);}
 buffer_ui_shell_layout();
}
static void dock_changed(lv_event_t *event)
{
 if(lv_event_get_code(event)==LV_EVENT_DELETE){
  lv_obj_t *object=lv_event_get_current_target(event);
  if(capture_dock&&capture_dock!=object)lv_obj_remove_event_cb(capture_dock,dock_changed);
  if(capture_scroll&&capture_scroll!=object)lv_obj_remove_event_cb(capture_scroll,dock_changed);
  if(capture_bar&&lv_obj_get_parent(capture_bar)==capture_dock)lv_obj_set_parent(capture_bar,shell_root);
  capture_dock=capture_scroll=NULL;
 }else buffer_ui_shell_layout();
}
void buffer_ui_shell_capture_dock(lv_obj_t *dock)
{
 if(capture_dock)lv_obj_remove_event_cb(capture_dock,dock_changed);
 if(capture_scroll)lv_obj_remove_event_cb(capture_scroll,dock_changed);
 capture_dock=dock;capture_scroll=NULL;
 if(dock){
  lv_obj_add_event_cb(dock,dock_changed,LV_EVENT_DELETE,NULL);lv_obj_add_event_cb(dock,dock_changed,LV_EVENT_SIZE_CHANGED,NULL);
  for(lv_obj_t *p=lv_obj_get_parent(dock);p&&p!=shell_root;p=lv_obj_get_parent(p))if(lv_obj_has_flag(p,LV_OBJ_FLAG_SCROLLABLE)){capture_scroll=p;break;}
  if(capture_scroll){lv_obj_add_event_cb(capture_scroll,dock_changed,LV_EVENT_SCROLL,NULL);lv_obj_add_event_cb(capture_scroll,dock_changed,LV_EVENT_DELETE,NULL);}
 }
 buffer_ui_shell_layout();
}
void buffer_ui_shell_capture_expanded(bool expanded)
{
 if(capture_expanded==expanded)return;
 if(expanded){
  if(capture_preview)capture_preview_rest_height=lv_obj_get_style_height(capture_preview,0);
  lv_group_t *group=focus_active_group();capture_return_focus=group?lv_group_get_focused(group):NULL;
  if(focus_descendant(capture_return_focus,capture_bar)||focus_descendant(capture_return_focus,capture_preview))capture_return_focus=NULL;
 }
 if(!expanded&&capture_preview)lv_obj_set_height(capture_preview,capture_preview_rest_height);
 capture_expanded=expanded;buffer_ui_shell_layout();focus_dirty=true;buffer_ui_shell_focus_refresh();
 if(!expanded){
  lv_obj_t *previous=capture_return_focus;capture_return_focus=NULL;
  if(previous&&lv_obj_is_valid(previous)&&focus_allowed(previous)&&lv_obj_get_group(previous)==focus_active_group())lv_group_focus_obj(previous);
 }
 focus_schedule();
}
void buffer_ui_shell_capture_mode(const char *id,enum buffer_ui_capture_mode mode)
{struct surface *surface=find(id);if(surface&&(unsigned)mode<=BUFFER_UI_CAPTURE_HIDDEN){surface->capture_mode=mode;buffer_ui_shell_layout();}}
void buffer_ui_shell_layout(void)
{
  if (!shell_root || arranging) return;
  arranging=true;
  int32_t w=lv_obj_get_content_width(shell_root),h=lv_obj_get_content_height(shell_root);
  if(w<1)w=1;
  if(h<1)h=1;
  int32_t status_height=status_bar?lv_obj_get_style_height(status_bar,0):0;
  if(status_height<0)status_height=0;
  if(status_height>h)status_height=h;
  if(status_bar){lv_obj_set_pos(status_bar,0,0);lv_obj_set_width(status_bar,w);}
  int32_t capture_height=capture_bar?lv_obj_get_style_height(capture_bar,0):0;
  if(capture_height<0)capture_height=0;
  if(capture_height>h-status_height)capture_height=h-status_height;
  enum buffer_ui_capture_mode mode=app?app->capture_mode:BUFFER_UI_CAPTURE_BOTTOM;
  bool docked=!app&&capture_dock&&!capture_expanded;
  if(desktop_root){
    if(app||capture_expanded)buffer_ui_add_flag(desktop_root,LV_OBJ_FLAG_HIDDEN);
    else buffer_ui_remove_flag(desktop_root,LV_OBJ_FLAG_HIDDEN);
  }
  bool floating=mode==BUFFER_UI_CAPTURE_FLOATING||capture_expanded;
  int32_t gap=buffer_ui_dp(10),reserved=docked||mode==BUFFER_UI_CAPTURE_HIDDEN||floating?0:capture_height;
  if(capture_bar){
    if(mode==BUFFER_UI_CAPTURE_HIDDEN&&!capture_expanded)buffer_ui_add_flag(capture_bar,LV_OBJ_FLAG_HIDDEN);else buffer_ui_remove_flag(capture_bar,LV_OBJ_FLAG_HIDDEN);
    lv_obj_t *parent=docked?capture_dock:shell_root;if(lv_obj_get_parent(capture_bar)!=parent)lv_obj_set_parent(capture_bar,parent);
    if(docked){lv_obj_set_pos(capture_bar,0,0);lv_obj_set_width(capture_bar,lv_obj_get_content_width(capture_dock));}
    else if(floating){int32_t available=w-2*gap;if(available<1)available=1;
      int32_t width=capture_expanded?available:buffer_ui_dp(132);if(width>available)width=available;
      lv_obj_set_pos(capture_bar,capture_expanded?gap:w-width-gap,h-capture_height-gap);lv_obj_set_width(capture_bar,width);}
    else{lv_obj_set_pos(capture_bar,0,h-capture_height);lv_obj_set_width(capture_bar,w);}
  }
  if(capture_backdrop){
    if(capture_expanded){
      lv_obj_set_pos(capture_backdrop,0,status_height);lv_obj_set_size(capture_backdrop,w,h-status_height);
      buffer_ui_surface_decor(capture_backdrop,BUFFER_DECOR_LISTENING,gap);
      buffer_ui_remove_flag(capture_backdrop,LV_OBJ_FLAG_HIDDEN);
      int top=(int)lv_obj_get_child_count(shell_root)-1;bool ordered=true;
      if(capture_bar)ordered=lv_obj_get_index(capture_bar)==top--;
      if(capture_preview)ordered=(lv_obj_get_index(capture_preview)==top--)&&ordered;
      ordered=(lv_obj_get_index(capture_backdrop)==top)&&ordered;
      if(!ordered){
        lv_obj_move_foreground(capture_backdrop);
        if(capture_preview)lv_obj_move_foreground(capture_preview);
        if(capture_bar)lv_obj_move_foreground(capture_bar);
      }
    }else buffer_ui_add_flag(capture_backdrop,LV_OBJ_FLAG_HIDDEN);
  }
  if(desktop_root&&lv_obj_get_style_pad_bottom(desktop_root,0)!=reserved)
    lv_obj_set_style_pad_bottom(desktop_root,reserved,0);
  if(desktop_root&&lv_obj_get_style_pad_top(desktop_root,0)!=status_height)
    lv_obj_set_style_pad_top(desktop_root,status_height,0);
  if(capture_preview){
    int32_t preview_height=lv_obj_get_style_height(capture_preview,0);
    if(capture_expanded){preview_height=h-status_height-capture_height-3*gap;if(preview_height<1)preview_height=1;
      lv_obj_set_pos(capture_preview,gap,status_height+gap);lv_obj_set_size(capture_preview,w>2*gap?w-2*gap:1,preview_height);}
    else{lv_obj_set_pos(capture_preview,0,h-capture_height-preview_height-buffer_ui_dp(4));lv_obj_set_width(capture_preview,w);}
  }
  for (unsigned i=0;i<SURFACES;i++)if(surfaces[i].content){
    lv_obj_t *content=surfaces[i].content;
    int32_t width=w;
    int32_t preferred=buffer_ui_dp(surfaces[i].info.preferred_width_dp?surfaces[i].info.preferred_width_dp:416);
    if(surfaces[i].kind==BUFFER_UI_SYSTEM_OVERLAY && width>preferred)width=preferred;
    lv_obj_set_pos(content,w-width,surfaces[i].kind==BUFFER_UI_APP?status_height:0);
    int32_t inset=surfaces[i].capture_mode==BUFFER_UI_CAPTURE_BOTTOM?capture_height:0;
    lv_obj_set_size(content,width,surfaces[i].kind==BUFFER_UI_APP?h-inset-status_height:h);
    surfaces[i].info.width_px=width;surfaces[i].info.height_px=surfaces[i].kind==BUFFER_UI_APP?h-inset-status_height:h;
    surfaces[i].info.constrained=width<buffer_ui_dp(surfaces[i].info.min_width_dp)||surfaces[i].info.height_px<buffer_ui_dp(surfaces[i].info.min_height_dp);
  }
  arranging=false;
}
void buffer_ui_shell_hide(const char *id)
{
  struct surface *surface=find(id);if(!surface)return;
  edge_invalidate();
  bool visible=!lv_obj_has_flag(surface->content,LV_OBJ_FLAG_HIDDEN);
  buffer_ui_motion_cancel(surface->content);
  lv_anim_delete(surface->content,overlay_translate);
  if(status_drag_surface==surface){status_drag_surface=NULL;status_input=NULL;status_owned=false;}
  lv_obj_set_style_translate_x(surface->content,0,0);
  lv_obj_set_style_translate_y(surface->content,0,0);
  lv_obj_set_style_opa(surface->content,LV_OPA_COVER,0);
  buffer_ui_add_flag(surface->content,LV_OBJ_FLAG_HIDDEN);
  if(app==surface)app=NULL;
  if(overlay==surface){overlay=NULL;buffer_ui_add_flag(scrim,LV_OBJ_FLAG_HIDDEN);}
  if (!app && desktop_root) buffer_ui_remove_flag(desktop_root,LV_OBJ_FLAG_HIDDEN);
  if (visible && surface->closed) surface->closed(surface->context);
  buffer_ui_shell_layout();
  focus_dirty=true;buffer_ui_shell_focus_refresh();
  /* Focus can disappear while a task updates. Never retain a dead pointer. */
  if (surface->focus && lv_obj_is_valid(surface->focus) && focus_allowed(surface->focus) && lv_obj_get_group(surface->focus))
    lv_group_focus_obj(surface->focus);
  surface->focus=NULL;
}
static void show_surface(struct surface *surface)
{
  edge_invalidate();
  bool was_open=surface==app||surface==overlay;
  lv_group_t *previous_group=focus_active_group();lv_obj_t *previous_focus=previous_group?lv_group_get_focused(previous_group):NULL;
  if (surface->kind==BUFFER_UI_APP){
    if(overlay)buffer_ui_shell_hide(overlay->id);
    if(app && app!=surface)buffer_ui_shell_hide(app->id);
    app=surface;
    if(desktop_root)buffer_ui_add_flag(desktop_root,LV_OBJ_FLAG_HIDDEN);
  }else{
    if(overlay && overlay!=surface)buffer_ui_shell_hide(overlay->id);
    overlay=surface;lv_obj_set_style_bg_opa(scrim,LV_OPA_40,0);buffer_ui_remove_flag(scrim,LV_OBJ_FLAG_HIDDEN);lv_obj_move_foreground(scrim);
  }
  if(!was_open)surface->focus=previous_focus;
  buffer_ui_remove_flag(surface->content,LV_OBJ_FLAG_HIDDEN);
  lv_obj_move_foreground(surface->content);
  if(surface->kind==BUFFER_UI_APP){
    if(status_bar)lv_obj_move_foreground(status_bar);
    if(capture_preview)lv_obj_move_foreground(capture_preview);
    if(capture_bar)lv_obj_move_foreground(capture_bar);
  }
  buffer_ui_shell_layout();
  if(surface->activated)surface->activated(was_open,surface->context);
  if(!surface->content||surface!=(surface->kind==BUFFER_UI_APP?app:overlay))return;
  if(!was_open){
    if(surface->kind==BUFFER_UI_SYSTEM_OVERLAY&&!strcmp(surface->info.entry_route,status_route))buffer_ui_motion_enter_vertical(surface->content,1);
    else buffer_ui_motion_enter(surface->content,1,surface->kind==BUFFER_UI_APP);
  }
  focus_dirty=true;buffer_ui_shell_focus_refresh();
}
int buffer_ui_shell_open(const char *route,const char *object)
{
 if(!route||!*route||strlen(route)>=BUFFER_UI_SURFACE_TEXT||(object&&strlen(object)>=96))return -EINVAL;
 struct surface *surface=NULL;
 for(unsigned i=0;i<SURFACES;i++)if(surfaces[i].content&&!strcmp(surfaces[i].info.entry_route,route)){surface=&surfaces[i];break;}
 if(!surface)return -ENOENT;
 int result=surface->prepare_open?surface->prepare_open(object,surface->context):object&&*object?-EINVAL:0;
 if(result)return result;
 if(!surface->content)return -ENOENT;
 show_surface(surface);return 0;
}
void buffer_ui_shell_show(const char *id)
{struct surface *surface=find(id);if(surface)(void)buffer_ui_shell_open(surface->info.entry_route,NULL);}
bool buffer_ui_shell_local_begin(const char *id,lv_obj_t *return_focus)
{
 struct surface *surface=find(id);
 if(!surface||surface!=(overlay?overlay:app)||surface->local_depth>=4)return false;
 buffer_ui_shell_focus_refresh();
 if(!return_focus)return_focus=lv_group_get_focused(surface->group);
 if(return_focus&&(!focus_allowed(return_focus)||lv_obj_get_group(return_focus)!=surface->group))return false;
 surface->local_focus[surface->local_depth++]=return_focus;return true;
}
void buffer_ui_shell_local_end(const char *id,lv_obj_t *override_focus)
{
 struct surface *surface=find(id);if(!surface||!surface->local_depth)return;
 lv_obj_t *target=surface->local_focus[--surface->local_depth];surface->local_focus[surface->local_depth]=NULL;
 if(surface!=(overlay?overlay:app))return;
 if(override_focus)target=override_focus;
 buffer_ui_shell_focus_refresh();
 if(target&&focus_allowed(target)&&lv_obj_get_group(target)==surface->group)lv_group_focus_obj(target);
}
bool buffer_ui_shell_back(void)
{
  if(capture_expanded&&capture_bar){lv_obj_send_event(capture_bar,LV_EVENT_PRESS_LOST,lv_indev_active());return true;}
  struct surface *surface=overlay?overlay:app;
  if(!surface)return false;
  if(surface->back&&surface->back(surface->back_context)){buffer_ui_shell_focus_refresh();return true;}
  buffer_ui_shell_hide(surface->id);return true;
}
void buffer_ui_shell_home(void)
{
  if(overlay)buffer_ui_shell_hide(overlay->id);
  if(app)buffer_ui_shell_hide(app->id);
}
bool buffer_ui_shell_is_open(const char *id)
{struct surface *surface=find(id);return surface&&(surface==app||surface==overlay);}
bool buffer_ui_shell_has_app(void){return app!=NULL;}
