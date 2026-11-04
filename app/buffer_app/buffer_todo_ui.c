/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_todo.h"
#include "buffer_ui_motion.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_shell.h"
#include "buffer_ui_icons.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <syslog.h>
struct row { lv_obj_t *root, *title, *detail, *check; char id[BUFFER_ID_SIZE],action[8]; };
static struct row rows[BUFFER_TODO_PAGE];
static unsigned mapping[BUFFER_TODO_PAGE];
static lv_obj_t *host, *panel, *status, *next_button, *previous_button;
static lv_obj_t *entry, *pager, *back, *list;
static bool launcher_docked;
static pthread_t worker;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake = PTHREAD_COND_INITIALIZER;
static bool stop_worker, requested, working, dirty, opened, more_rows;
static int direction = 1, result, action_result;
static unsigned count;
static char cursor[BUFFER_ID_SIZE], action_id[BUFFER_ID_SIZE];
static char action_name[8];
static struct buffer_todo_item items[BUFFER_TODO_PAGE];
static uint32_t queried_at;
static lv_obj_t *column(lv_obj_t *parent);
static void page_layout(lv_event_t *event)
{
 (void)event;if(!launcher_docked||!host||!panel)return;
 int w=lv_obj_get_content_width(host),h=lv_obj_get_content_height(host);
 int preferred=buffer_ui_dp(232)*(int)buffer_ui_font_scale()/100;
 bool narrow=w<preferred+buffer_ui_dp(142);
 int height=narrow?h-buffer_ui_dp(58):h;if(height<1)height=1;
 lv_obj_set_size(panel,narrow?LV_PCT(100):preferred,height);
 lv_obj_set_style_min_height(panel,0,0);
}
static void request(void)
{
  pthread_mutex_lock(&lock); requested = true; pthread_cond_signal(&wake); pthread_mutex_unlock(&lock);
  queried_at = lv_tick_get();
}
static void *run(void *unused)
{
  (void)unused;
  /* Fixed page buffers only; never allocate one LVGL node per stored record. */
  static struct buffer_todo_item page[BUFFER_TODO_PAGE];
  pthread_mutex_lock(&lock);
  while (!stop_worker)
    {
      while (!requested && !stop_worker) pthread_cond_wait(&wake,&lock);
      if (stop_worker) break;
      char selected[BUFFER_ID_SIZE], anchor[BUFFER_ID_SIZE],operation[8];
      snprintf(selected,sizeof(selected),"%s",action_id);
      snprintf(operation,sizeof(operation),"%s",action_name);
      snprintf(anchor,sizeof(anchor),"%s",cursor);
      int order=direction; action_id[0]=0; requested=false; working=true;
      pthread_mutex_unlock(&lock);
      int acted=selected[0] ? buffer_request_action_id(selected,operation) : 0;
      bool more=false;
      int ret=buffer_todo_page(anchor,order,page,BUFFER_TODO_PAGE,&more);
      pthread_mutex_lock(&lock);
      if (ret>=0) { memcpy(items,page,ret*sizeof(*page)); count=ret; more_rows=more; }
      if(ret<0&&ret!=result)syslog(LOG_WARNING,"Buffer Todo: read result=%d\n",ret);
      result=ret;
      /* A successful background read is not an action receipt. Keep the
       * failed submission visible until a subsequent action succeeds. */
      if(selected[0])action_result=acted;
      dirty=true; working=false;
    }
  pthread_mutex_unlock(&lock); return NULL;
}
static void shell_closed(void *context)
{ (void)context; opened=false;buffer_ui_add_flag(panel,LV_OBJ_FLAG_HIDDEN); }
static void shell_activated(bool was_visible,void *context)
{(void)context;opened=true;buffer_ui_remove_flag(panel,LV_OBJ_FLAG_HIDDEN);if(!was_visible)request();}
static void toggle(lv_event_t *event)
{
  struct buffer_ui_surface_info surface;
  if(buffer_ui_shell_info("todo",&surface)){
    if(buffer_ui_shell_is_open("todo"))buffer_ui_shell_hide("todo");else(void)buffer_ui_shell_open(surface.entry_route,NULL);
    return;
  }
  (void)event; opened=!opened;
  if (opened)
    {
      buffer_ui_remove_flag(panel,LV_OBJ_FLAG_HIDDEN);
      if (!launcher_docked) buffer_ui_label_set_text(lv_obj_get_child(entry,0),"返回");
      buffer_ui_motion_enter(panel,1,false);
    }
  else
    {
      buffer_ui_add_flag(panel,LV_OBJ_FLAG_HIDDEN);
      if (!launcher_docked) buffer_ui_label_set_text(lv_obj_get_child(entry,0),"待办");
    }
  if (lv_obj_get_parent(entry) != host)
    {
      if (opened) buffer_ui_remove_flag(host,LV_OBJ_FLAG_HIDDEN);
      else buffer_ui_add_flag(host,LV_OBJ_FLAG_HIDDEN);
    }
  if (opened) { buffer_ui_shell_show("todo"); request(); }
  else buffer_ui_shell_hide("todo");
  lv_obj_t *body=lv_obj_get_parent(host);
  lv_obj_update_layout(body);
  lv_obj_scroll_to_y(body,0,LV_ANIM_OFF);
  lv_obj_send_event(body,LV_EVENT_VALUE_CHANGED,NULL);
}
bool buffer_todo_ui_open(void) { return opened; }
void buffer_todo_ui_set_visible(bool visible)
{
  if (!host) return;
  struct buffer_ui_surface_info surface;
  if(buffer_ui_shell_info("todo",&surface)){
    if(!visible)buffer_ui_shell_hide("todo");
    if(visible)buffer_ui_remove_flag(entry,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(entry,LV_OBJ_FLAG_HIDDEN);
    return;
  }
  bool docked = lv_obj_get_parent(entry) != host;
  if (visible && (!docked || opened)) buffer_ui_remove_flag(host, LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_add_flag(host, LV_OBJ_FLAG_HIDDEN);
  if (visible) buffer_ui_remove_flag(entry, LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_add_flag(entry, LV_OBJ_FLAG_HIDDEN);
}
void buffer_todo_ui_dock_entry(void *toolbar)
{
  if (!host || !toolbar) return;
  lv_obj_set_parent(entry, toolbar);
  lv_obj_move_to_index(entry, 0);
  lv_obj_set_width(entry, buffer_ui_dp(112));
  lv_obj_set_style_radius(entry, buffer_ui_dp(24), 0);
  lv_obj_set_style_pad_hor(entry, buffer_ui_dp(12), 0);
  buffer_ui_style_bg_color(entry, lv_color_hex(BUFFER_PANEL), 0);
  buffer_ui_style_bg_opa(entry, LV_OPA_COVER, 0);
  buffer_todo_ui_set_visible(true);
}
void buffer_todo_ui_dock_launcher(void *page, void *launchers)
{
  if (!host || !page || !launchers) return;
  launcher_docked = true;
  buffer_ui_launcher_attach(launchers,entry,"app.todo","待办");
  lv_obj_t *icon=buffer_ui_icon_create(entry,&buffer_checklist_icon);lv_obj_center(icon);
  /* The app declares its content; Shell owns placement and layering. */
  (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="todo",.title="待办",.entry_route="todo",.kind=BUFFER_UI_APP,.capture_mode=BUFFER_UI_CAPTURE_FLOATING,.min_width_dp=160,.min_height_dp=96,
    .actions=(const char *const[]){"open","complete","reopen","previous","next","back"},.action_count=6,.closed=shell_closed,.activated=shell_activated},host);
  buffer_ui_remove_flag(host,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_style_pad_all(host,buffer_ui_dp(10),0);lv_obj_set_style_pad_all(panel,0,0);
  lv_obj_set_style_pad_all(list,buffer_ui_dp(10),0);lv_obj_set_style_pad_all(pager,buffer_ui_dp(10),0);
  buffer_ui_style_bg_color(panel,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(panel,LV_OPA_COVER,0);lv_obj_set_style_radius(panel,buffer_ui_dp(20),0);
  lv_obj_set_style_pad_row(panel,0,0);
  lv_obj_set_height(panel,LV_PCT(100));
  lv_obj_set_height(list,0);lv_obj_set_flex_grow(list,1);
  buffer_ui_add_flag(list,LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_set_scroll_dir(list,LV_DIR_VER);
  lv_obj_set_scroll_dir(host, LV_DIR_VER);
  buffer_ui_add_flag(back,LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_event_cb(host,page_layout,LV_EVENT_SIZE_CHANGED,NULL);page_layout(NULL);
  buffer_todo_ui_set_visible(true);
}
static void complete(lv_event_t *event)
{
  struct row *row=lv_event_get_user_data(event);
  if(!row->action[0]||lv_obj_has_state(row->check,LV_STATE_DISABLED))return;
  pthread_mutex_lock(&lock);
  if (working || requested || action_id[0]) { pthread_mutex_unlock(&lock); return; }
  snprintf(action_id,sizeof(action_id),"%s",row->id);
  snprintf(action_name,sizeof(action_name),"%s",row->action);
  requested=true; pthread_cond_signal(&wake); pthread_mutex_unlock(&lock);
  buffer_ui_label_set_text(status,!strcmp(row->action,"reopen")?"正在恢复未完成…":"正在标记完成…");
  buffer_ui_remove_flag(status,LV_OBJ_FLAG_HIDDEN);
}
static void detail(lv_event_t *event)
{
  struct row *row=lv_event_get_user_data(event);
  if (lv_obj_has_flag(row->detail,LV_OBJ_FLAG_HIDDEN)) buffer_ui_remove_flag(row->detail,LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_add_flag(row->detail,LV_OBJ_FLAG_HIDDEN);
}
static void navigate(lv_event_t *event)
{
  int order=(int)(intptr_t)lv_event_get_user_data(event);
  pthread_mutex_lock(&lock);
  if (working || requested) { pthread_mutex_unlock(&lock); return; }
  if (!count) { cursor[0]=0; order=1; }
  else snprintf(cursor,sizeof(cursor),"%.*s",BUFFER_ID_SIZE-1,items[order>0?count-1:0].card.card_id);
  direction=order; pthread_mutex_unlock(&lock); request();
}
static lv_obj_t *button(lv_obj_t *parent,const char *text,lv_event_cb_t cb,void *data)
{
  lv_obj_t *obj=lv_button_create(parent);
  lv_obj_set_size(obj,LV_PCT(100),LV_SIZE_CONTENT); buffer_ui_button_style(obj,false);
  lv_obj_set_style_min_height(obj,buffer_ui_dp(48),0);
  lv_obj_set_style_pad_hor(obj,buffer_ui_dp(12),0); lv_obj_set_style_pad_ver(obj,buffer_ui_dp(10),0);
  lv_obj_t *label=lv_label_create(obj); lv_obj_set_width(label,LV_PCT(100));
  lv_label_set_long_mode(label,LV_LABEL_LONG_WRAP); lv_obj_set_style_text_align(label,LV_TEXT_ALIGN_CENTER,0);
  buffer_ui_button_label(label);
  buffer_ui_label_set_text(label,text); lv_obj_center(label);
  lv_obj_add_event_cb(obj,cb,LV_EVENT_CLICKED,data); return obj;
}
static lv_obj_t *column(lv_obj_t *parent)
{
  lv_obj_t *obj=lv_obj_create(parent); lv_obj_remove_style_all(obj);
  lv_obj_set_size(obj,LV_PCT(100),LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(obj,LV_FLEX_FLOW_COLUMN); lv_obj_set_style_pad_row(obj,buffer_ui_dp(8),0);
  buffer_ui_remove_flag(obj,LV_OBJ_FLAG_SCROLLABLE); return obj;
}
int buffer_todo_ui_attach(void *body)
{
  launcher_docked=false;
  stop_worker=false; requested=false; working=false; dirty=false; opened=false;
  count=0; cursor[0]=action_id[0]=action_name[0]=0; direction=1; action_result=0;
  host=column(body); lv_obj_move_to_index(host,0);
  entry=button(host,"待办",toggle,NULL);
  lv_obj_set_height(entry,buffer_ui_dp(48));
  lv_obj_set_style_pad_ver(entry,0,0);
  buffer_ui_style_bg_opa(entry,LV_OPA_TRANSP,0);
  lv_obj_set_style_shadow_width(entry,0,0);
  lv_obj_set_style_text_align(lv_obj_get_child(entry,0),LV_TEXT_ALIGN_LEFT,0);
  lv_label_set_long_mode(lv_obj_get_child(entry,0),LV_LABEL_LONG_DOT);
  panel=column(host); buffer_ui_add_flag(panel,LV_OBJ_FLAG_HIDDEN);
  back=button(panel,"返回",toggle,NULL);
  buffer_ui_icon_button(back,&buffer_back_icon);lv_obj_set_size(back,buffer_ui_dp(48),buffer_ui_dp(48));
  buffer_ui_add_flag(back,LV_OBJ_FLAG_HIDDEN);
  status=lv_label_create(panel); lv_obj_set_width(status,LV_PCT(100));
  lv_label_set_long_mode(status,LV_LABEL_LONG_WRAP); buffer_ui_text_role(status,BUFFER_TEXT_LABEL_LARGE);
  buffer_ui_label_set_text(status,"读取待办…");
  buffer_ui_add_flag(status,LV_OBJ_FLAG_HIDDEN);
  list=column(panel);
  for (unsigned i=0;i<BUFFER_TODO_PAGE;i++)
    {
      struct row *row=&rows[i]; row->id[0]=row->action[0]=0;
      row->root=column(list);
      lv_obj_set_style_pad_ver(row->root,0,0);lv_obj_set_style_pad_row(row->root,0,0);
      lv_obj_set_style_border_width(row->root,0,0);
      lv_obj_set_style_border_side(row->root,LV_BORDER_SIDE_BOTTOM,0);
      lv_obj_set_style_border_color(row->root,lv_color_hex(BUFFER_PANEL),0);
      lv_obj_t *line=column(row->root);
      lv_obj_set_flex_flow(line,LV_FLEX_FLOW_ROW);
      lv_obj_set_flex_align(line,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
      row->check=button(line,"",complete,row);
      buffer_ui_icon_button(row->check,&buffer_unchecked_icon);
      lv_obj_set_size(row->check,buffer_ui_dp(48),buffer_ui_dp(48));
      lv_obj_set_style_pad_all(row->check,0,0);
      buffer_ui_style_bg_opa(row->check,LV_OPA_TRANSP,0);
      lv_obj_set_style_shadow_width(row->check,0,0);
      row->title=lv_label_create(line);
      lv_obj_set_width(row->title,0);
      lv_obj_set_flex_grow(row->title,1);
      lv_label_set_long_mode(row->title,LV_LABEL_LONG_WRAP); buffer_ui_text_role(row->title,BUFFER_TEXT_HEADLINE_SMALL);
      lv_obj_set_style_min_height(row->title,buffer_ui_dp(48),0);
      lv_obj_set_style_pad_ver(row->title,buffer_ui_dp(6),0);
      buffer_ui_add_flag(row->title,LV_OBJ_FLAG_CLICKABLE);
      lv_obj_add_event_cb(row->title,detail,LV_EVENT_CLICKED,row);
      row->detail=lv_label_create(row->root); lv_obj_set_width(row->detail,LV_PCT(100));
      lv_label_set_long_mode(row->detail,LV_LABEL_LONG_WRAP); buffer_ui_text_role(row->detail,BUFFER_TEXT_BODY_LARGE);
      buffer_ui_add_flag(row->detail,LV_OBJ_FLAG_HIDDEN);
      buffer_ui_add_flag(row->root,LV_OBJ_FLAG_HIDDEN);
    }
  pager=column(panel);
  lv_obj_set_flex_flow(pager,LV_FLEX_FLOW_ROW);
  previous_button=button(pager,"上一页",navigate,(void *)(intptr_t)-1);
  next_button=button(pager,"下一页",navigate,(void *)(intptr_t)1);
  lv_obj_set_width(previous_button,0); lv_obj_set_flex_grow(previous_button,1);
  lv_obj_set_width(next_button,0); lv_obj_set_flex_grow(next_button,1);
  int ret=buffer_thread_create(&worker, run, NULL);
  if (ret) { lv_obj_delete(host); host=NULL; return -ret; }
  return 0;
}
void buffer_todo_ui_refresh(void)
{
  if (!host || (launcher_docked && !opened)) return;
  if (lv_obj_is_scrolling(lv_obj_get_parent(host))) return;
  for (lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input))
    if (lv_indev_get_state(input)==LV_INDEV_STATE_PRESSED) return;
  pthread_mutex_lock(&lock);
  if (dirty)
    {
      if (result<0)
        {
          buffer_ui_label_set_text(status,result==-EAGAIN||result==-EBUSY?
              "待办正在同步，稍后自动更新":"读取失败，将自动重试");
          buffer_ui_remove_flag(status,LV_OBJ_FLAG_HIDDEN);
        }
      else
        {
          char entry_text[64];
          if (opened) snprintf(entry_text,sizeof(entry_text),"返回");
          else if (cursor[0]) snprintf(entry_text,sizeof(entry_text),"待办");
          else snprintf(entry_text,sizeof(entry_text),"待办 %u%s",count,more_rows ? "+" : "");
          if (!launcher_docked) buffer_ui_label_set_text(lv_obj_get_child(entry,0),entry_text);
          buffer_ui_label_set_text(status,action_result<0 ? "提交失败，待办已保留，请重试" :
              count ? "" : "暂无待办");
          if (action_result<0 || !count) buffer_ui_remove_flag(status,LV_OBJ_FLAG_HIDDEN);
          else buffer_ui_add_flag(status,LV_OBJ_FLAG_HIDDEN);
          bool used[BUFFER_TODO_PAGE]={false};
          for (unsigned i=0;i<count;i++)
            {
              unsigned slot=BUFFER_TODO_PAGE;
              for (unsigned j=0;j<BUFFER_TODO_PAGE;j++)
                if (!used[j] && !strcmp(rows[j].id,items[i].card.card_id)) { slot=j; break; }
              if (slot==BUFFER_TODO_PAGE)
                for (unsigned j=0;j<BUFFER_TODO_PAGE;j++)
                  {
                    bool needed=false;
                    for (unsigned k=i+1;k<count;k++)
                      if (!strcmp(rows[j].id,items[k].card.card_id)) needed=true;
                    if (!used[j] && !needed) { slot=j; break; }
                  }
              if (slot==BUFFER_TODO_PAGE) continue;
              used[slot]=true; mapping[i]=slot;
              struct row *row=&rows[slot];
              bool same=!strcmp(row->id,items[i].card.card_id);
              if (!same) buffer_ui_add_flag(row->detail,LV_OBJ_FLAG_HIDDEN);
              snprintf(row->id,sizeof(row->id),"%.*s",BUFFER_ID_SIZE-1,items[i].card.card_id);
              snprintf(row->action,sizeof(row->action),"%.7s",items[i].card.actions);
              if (strcmp(lv_label_get_text(row->title),items[i].card.title)) buffer_ui_label_set_text(row->title,items[i].card.title);
              if (strcmp(lv_label_get_text(row->detail),items[i].card.summary)) buffer_ui_label_set_text(row->detail,items[i].card.summary);
              lv_obj_move_to_index(row->root,i);
              buffer_ui_remove_flag(row->root,LV_OBJ_FLAG_HIDDEN);
            }
          for (unsigned j=0;j<BUFFER_TODO_PAGE;j++)
            if (!used[j]) buffer_ui_add_flag(rows[j].root,LV_OBJ_FLAG_HIDDEN);
          buffer_ui_label_set_text(lv_obj_get_child(previous_button,0),count ? "上一页" : "回到开头");
          buffer_ui_label_set_text(lv_obj_get_child(next_button,0),count ? "下一页" : "回到开头");
          if (direction>0 ? more_rows : cursor[0]) lv_obj_remove_state(next_button,LV_STATE_DISABLED);
          else lv_obj_add_state(next_button,LV_STATE_DISABLED);
          if (direction<0 ? more_rows : cursor[0]) lv_obj_remove_state(previous_button,LV_STATE_DISABLED);
          else lv_obj_add_state(previous_button,LV_STATE_DISABLED);
        }
      dirty=false;
    }
  if (pthread_mutex_trylock(&g_buffer.lock) != 0)
    { pthread_mutex_unlock(&lock); return; }
  bool remote_pending=g_buffer.action_requested;
  for (unsigned i=0;i<count;i++)
    {
      bool waiting=items[i].pending || (remote_pending && !strcmp(g_buffer.action_card_id,items[i].card.card_id));
      bool disabled=waiting || remote_pending || working || requested || !items[i].card.actions[0];
      if (disabled) lv_obj_add_state(rows[mapping[i]].check,LV_STATE_DISABLED);
      else lv_obj_remove_state(rows[mapping[i]].check,LV_STATE_DISABLED);
      lv_obj_t *icon=lv_obj_get_child(rows[mapping[i]].check,1);
      const lv_image_dsc_t *source=waiting?&buffer_pending_icon:!strcmp(items[i].card.state,"done")?&buffer_check_box_icon:&buffer_unchecked_icon;
      buffer_ui_icon_set_src(icon,source);
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (!cursor[0] && !more_rows) buffer_ui_add_flag(pager,LV_OBJ_FLAG_HIDDEN);
  else buffer_ui_remove_flag(pager,LV_OBJ_FLAG_HIDDEN);
  bool refresh=opened && !working && !requested && lv_tick_elaps(queried_at)>3000;
  pthread_mutex_unlock(&lock);
  if (refresh) request();
}
void buffer_todo_ui_detach(void)
{
  if (!host) return;
  pthread_mutex_lock(&lock); stop_worker=true; pthread_cond_signal(&wake); pthread_mutex_unlock(&lock);
  pthread_join(worker,NULL);
  if (entry && lv_obj_get_parent(entry) != host) lv_obj_delete(entry);
  entry=NULL; launcher_docked=false; back=NULL;
  lv_obj_delete(host); host=NULL; opened=false;
}
