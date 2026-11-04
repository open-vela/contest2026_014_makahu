/* Runs actual production renderer and lifecycle code against actual SDK LVGL.
 * Controlled model/domain inputs isolate view reconciliation from I/O. */
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include "buffer_a2ui_lvgl.c"
#include "buffer_oobe_ui.h"
#include "buffer_todo_ui.c"
int buffer_private_storage_prepare(void){return 0;}
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
static bool reverse_tasks;
static bool design_tasks;
static bool design_completed=true;
static char submitted_todo_action[8];
static unsigned todo_queries;
int buffer_todo_page(const char *anchor,int order,struct buffer_todo_item *out,unsigned capacity,bool *more)
{
  __atomic_add_fetch(&todo_queries,1,__ATOMIC_RELAXED);
  (void)anchor;(void)order;assert(capacity==8);*more=false;
  if(design_tasks){
    const char *titles[]={"写完作业","遛狗","洗衣服","学习英语"};
    for(unsigned i=0;i<4;i++){bool done=i==2&&__atomic_load_n(&design_completed,__ATOMIC_RELAXED);memset(&out[i],0,sizeof(out[i]));snprintf(out[i].card.card_id,sizeof(out[i].card.card_id),"design-task-%u",i);strcpy(out[i].card.title,titles[i]);strcpy(out[i].card.state,done?"done":"active");strcpy(out[i].card.actions,done?"reopen":"done");}return 4;
  }
  for (unsigned i=0;i<2;i++)
    { memset(&out[i],0,sizeof(out[i]));unsigned id=reverse_tasks?1-i:i;
      snprintf(out[i].card.card_id,sizeof(out[i].card.card_id),"task-%u",id);
      snprintf(out[i].card.title,sizeof(out[i].card.title),"Long task %u with enough words to wrap on compact screens",id);
      strcpy(out[i].card.actions,"done"); }
  return 2;
}
static int todo_submit_result=-EBUSY;
int buffer_request_action_id(const char *id,const char *action)
{
 assert(!strcmp(action,"done")||!strcmp(action,"reopen"));snprintf(submitted_todo_action,sizeof(submitted_todo_action),"%s",action);
 int result=__atomic_load_n(&todo_submit_result,__ATOMIC_RELAXED);
 if(!result&&!strcmp(id,"design-task-2"))__atomic_store_n(&design_completed,!strcmp(action,"done"),__ATOMIC_RELAXED);
 return result;
}
static void todo_ready(void)
{
 for(unsigned n=0;n<500;n++){
  pthread_mutex_lock(&lock);bool ready=dirty&&!working&&!requested;pthread_mutex_unlock(&lock);
  if(ready){buffer_todo_ui_refresh();return;}usleep(1000);
 }
 assert(!"Todo worker did not publish its result");
}
static struct buffer_a2ui_surface_s fixture;
static bool active_surface=true;
static int active_error;
static int action_fixture_state;
static unsigned action_fixture_submissions;
static enum buffer_assistant_state_e domain_state=BUFFER_ASSISTANT_RUNNING;
#include "visual_capture.h"
#include "edge_touch_fixture.h"
void buffer_set_status(const char *format,...) { (void)format; }
int buffer_a2ui_model_get_surface(const char *id,struct buffer_a2ui_surface_s *s)
{if(strcmp(id,fixture.id))return -ENOENT;*s=fixture;return 0;}
int buffer_a2ui_model_surface_revision(const char *id,uint64_t *rev)
{if(strcmp(id,fixture.id))return -ENOENT;*rev=fixture.task_revision;return 0;}
int buffer_a2ui_model_get_active(struct buffer_a2ui_surface_s *s)
{ if(active_error)return active_error;if (!active_surface) return -ENOENT; *s=fixture; return 0; }
void buffer_a2ui_close_active(void) { active_surface=false; }
void buffer_a2ui_model_close(const char *id) { (void)id;active_surface=false; }
int buffer_a2ui_model_action_status(const char *surface,const char *component,struct buffer_a2ui_action_status_s *status)
{(void)surface;(void)component;if(!action_fixture_state)return -ENOENT;
 *status=(struct buffer_a2ui_action_status_s){.pending=action_fixture_state<3,.accepted=action_fixture_state==2,.result=action_fixture_state==3?-ETIMEDOUT:0};strcpy(status->id,"ui-fixture-action");return 0;}
int buffer_a2ui_model_emit_action(const char *s,const char *c,const char *a,const char *j)
{ (void)s;(void)c;(void)a;(void)j;action_fixture_submissions++;return 0; }
int buffer_a2ui_model_bind_timer(const char *s,const char *c,const char *t,uint64_t revision,const char *request)
{ (void)s;(void)c;(void)t;(void)revision;(void)request;return 0; }
int buffer_a2ui_model_activate(const char *s) { (void)s; return 0; }
int buffer_a2ui_model_try_activate(const char *id,struct buffer_a2ui_surface_s *s)
{if(active_error)return active_error;return buffer_a2ui_model_get_surface(id,s);}
int buffer_a2ui_model_set_refresh_hook(buffer_a2ui_refresh_hook_fn hook) { (void)hook;return 0; }
const char *buffer_a2ui_component_text(const struct buffer_a2ui_surface_s *s,const struct buffer_a2ui_component_s *c)
{ (void)s;return c->text; }
int buffer_a2ui_surface_cards(struct buffer_assistant_surface_card_s *cards,size_t capacity){(void)cards;(void)capacity;return 0;}
int buffer_assistant_init(void) { return 0; }
int buffer_assistant_restore_recipe(const char *operation,const char *surface,char image[65]){(void)operation;(void)surface;image[0]=0;return -ENOTSUP;}
void buffer_assistant_set_event_handler(buffer_assistant_event_fn fn,void *ctx) { (void)fn;(void)ctx; }
int buffer_a2ui_present_timer(const char *s) { (void)s;return 0; }
int buffer_a2ui_present_alarm(const char *s) { (void)s;return 0; }
int buffer_assistant_get(enum buffer_assistant_event_e e,const char *s,struct buffer_assistant_item_s *item)
{ (void)e;(void)s;memset(item,0,sizeof(*item));item->state=domain_state;item->remaining_ms=5000;return 0; }
int buffer_assistant_execute(const struct buffer_assistant_request_s *r,struct buffer_assistant_result_s *result)
{ (void)result; domain_state=r->operation==BUFFER_ASSISTANT_TIMER_PAUSE?BUFFER_ASSISTANT_PAUSED:BUFFER_ASSISTANT_RUNNING;return 0; }
static void advance(unsigned ms)
{ for (unsigned i=0;i<ms;i+=16) { lv_tick_inc(16);lv_timer_handler(); } }
static void springs(void)
{
  for (int role=BUFFER_SF;role<=BUFFER_EL;role++)
    {
      struct buffer_spring s={0,0,100,true};
      for (int n=0;n<500;n++) { buffer_spring_step(&s,role,4,false);if (role>=BUFFER_EF) assert(s.value>=0 && s.value<=100); }
      assert(!s.active && s.value==100);
      buffer_spring_target(&s,-20);buffer_spring_step(&s,role,16,false);
      float value=s.value,velocity=s.velocity;buffer_spring_target(&s,60);
      assert(value==s.value && velocity==s.velocity);
      buffer_spring_step(&s,role,16,true); assert(!s.active && s.value==60);
      buffer_spring_target(&s,2);buffer_spring_step(&s,role,500,false);assert(s.value==2);
    }
}
int buffer_assistant_ui_get(enum buffer_assistant_event_e type,const char *id,struct buffer_assistant_item_s *item)
{return buffer_assistant_get(type,id,item);}
int buffer_ui_work_submit(void (*run)(void *),void *context)
{run(context);free(context);return 0;}
static unsigned icon_draws;
static void verify_icon_draw(lv_event_t *event)
{
 lv_draw_image_dsc_t *d=lv_draw_task_get_image_dsc(lv_event_get_draw_task(event));if(!d)return;
 lv_obj_t *image=lv_event_get_target(event);
 assert(d->recolor_opa==LV_OPA_COVER);
 assert(lv_color_eq(d->recolor,lv_obj_get_style_text_color(lv_obj_get_parent(image),0)));
 icon_draws++;
}
int main(void)
{
  springs();lv_init();lv_display_t *display=lv_display_create(320,480);
  static uint8_t pixels[320*32*4];lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(display,flush);
  /* Real density conversion, icon bounds, inherited state colors/lifetime. */
  const unsigned densities[]={130,160,240,320},targets[]={39,48,72,96};
  for(unsigned n=0;n<4;n++){
    lv_display_set_dpi(display,densities[n]);
    assert(buffer_ui_dp(48)==(int)targets[n]&&buffer_ui_dp(-48)==-(int)targets[n]);
    assert(buffer_ui_dp(0)==0&&buffer_ui_dp(1)>=1);
    lv_obj_t *owner=lv_button_create(lv_screen_active());buffer_ui_button_style(owner,false);
    lv_obj_set_size(owner,buffer_ui_dp(48),buffer_ui_dp(48));lv_obj_set_style_pad_all(owner,0,0);
    lv_obj_t *icon=buffer_ui_icon_create(owner,&buffer_more_icon);lv_obj_center(icon);
    lv_obj_add_event_cb(icon,verify_icon_draw,LV_EVENT_DRAW_TASK_ADDED,NULL);
    const lv_state_t states[]={LV_STATE_DEFAULT,LV_STATE_PRESSED,LV_STATE_CHECKED,LV_STATE_CHECKED|LV_STATE_PRESSED,LV_STATE_DISABLED};
    for(unsigned i=0;i<5;i++){
      lv_obj_remove_state(owner,LV_STATE_ANY);lv_obj_add_state(owner,states[i]);
      unsigned before=icon_draws;lv_obj_invalidate(icon);lv_tick_inc(64);lv_refr_now(display);
      assert(icon_draws>before);
    }
    lv_obj_update_layout(owner);assert(lv_obj_get_width(icon)==buffer_ui_dp(BUFFER_GLYPH_DP));
    const lv_image_dsc_t *native=lv_image_get_src(icon);
    assert(native->header.w==buffer_ui_dp(BUFFER_GLYPH_DP));
    assert(lv_image_get_scale_x(icon)==LV_SCALE_NONE);
    assert(lv_image_get_inner_align(icon)==LV_IMAGE_ALIGN_CENTER);
    lv_obj_set_size(icon,buffer_ui_dp(32),buffer_ui_dp(32));lv_obj_update_layout(owner);
    native=lv_image_get_src(icon);assert(native->header.w==buffer_ui_dp(32));
    assert(lv_image_get_scale_x(icon)==LV_SCALE_NONE);

    lv_obj_delete(icon);lv_obj_add_state(owner,LV_STATE_PRESSED);lv_obj_delete(owner);
  }
  lv_display_set_dpi(display,BUFFER_UI_TEST_DPI);
  buffer_ui_motion_init();buffer_ui_motion_policy(false,false);
#ifdef BUFFER_UI_REAL_FONTS
 assert(!buffer_ui_typography_init());
#endif
  /* The actual shared component used by Todo, Calendar and History. */
  lv_obj_t *launcher_row=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(launcher_row);
  lv_obj_set_size(launcher_row,LV_PCT(100),buffer_ui_dp(112));lv_obj_set_flex_flow(launcher_row,LV_FLEX_FLOW_ROW);
  const char *names[]={"待办","日历","历史"};int baseline=0;
  for(unsigned i=0;i<3;i++){
    lv_obj_t *b=lv_button_create(launcher_row);
    char key[24];snprintf(key,sizeof(key),"app-%u",i);
    lv_obj_t *column=buffer_ui_launcher_attach(launcher_row,b,key,names[i]);
    assert(!strcmp(buffer_ui_launcher_id(column),key));
    memset(key,0,sizeof(key));assert(*buffer_ui_launcher_id(column));
    buffer_ui_icon_create(b,&buffer_checklist_icon);
    lv_obj_update_layout(launcher_row);lv_area_t label;lv_obj_get_coords(lv_obj_get_child(column,1),&label);
    if(i)assert(label.y1==baseline);else baseline=label.y1;
  }
  lv_obj_delete(launcher_row);
  lv_obj_t *root=lv_obj_create(lv_screen_active());lv_obj_set_size(root,320,480);
  lv_obj_t *body=lv_obj_create(root);lv_obj_set_size(body,296,300);
  lv_obj_t *mic=lv_button_create(root);lv_obj_set_size(mic,296,56);
  assert(buffer_a2ui_ui_attach(body,mic)==0);
  fixture.used=fixture.has_tree=fixture.full_screen=true;fixture.sequence=1;fixture.task_revision=1;
  strcpy(fixture.id,"generic-action");fixture.component_count=2;
  strcpy(fixture.components[0].id,"root");fixture.components[0].type=BUFFER_A2UI_COLUMN;fixture.components[0].child_count=1;fixture.components[0].children[0]=1;
  strcpy(fixture.components[1].id,"choose");fixture.components[1].type=BUFFER_A2UI_BUTTON;strcpy(fixture.components[1].text,"继续处理");
  buffer_a2ui_ui_open_current();advance(1000);lv_obj_t *generic=g_nodes[1];assert(generic);lv_area_t generic_before,generic_after;lv_obj_get_coords(generic,&generic_before);
  action_fixture_state=1;buffer_a2ui_ui_refresh();advance(100);assert(lv_obj_has_state(generic,LV_STATE_DISABLED));assert(!strcmp(buffer_ui_label_source_text(lv_obj_get_child(generic,0)),"正在提交…"));
  lv_obj_send_event(generic,LV_EVENT_CLICKED,NULL);assert(!action_fixture_submissions);
  action_fixture_state=2;buffer_a2ui_ui_refresh();advance(100);assert(!strcmp(buffer_ui_label_source_text(lv_obj_get_child(generic,0)),"处理中…"));lv_obj_get_coords(generic,&generic_after);assert(!memcmp(&generic_before,&generic_after,sizeof(generic_before)));
  action_fixture_state=3;buffer_a2ui_ui_refresh();advance(100);assert(!lv_obj_has_state(generic,LV_STATE_DISABLED));assert(!strcmp(buffer_ui_label_source_text(lv_obj_get_child(generic,0)),"重试 · 继续处理"));
  action_fixture_state=4;buffer_a2ui_ui_refresh();assert(!lv_obj_has_state(generic,LV_STATE_DISABLED));assert(!strcmp(buffer_ui_label_source_text(lv_obj_get_child(generic,0)),"继续处理"));
  action_fixture_state=0;memset(&fixture,0,sizeof(fixture));
  fixture.used=fixture.has_tree=fixture.full_screen=true;fixture.sequence=1;fixture.task_revision=1;
  strcpy(fixture.id,"reply-one");fixture.component_count=3;
  strcpy(fixture.components[0].id,"root");fixture.components[0].type=BUFFER_A2UI_COLUMN;
  fixture.components[0].child_count=2;fixture.components[0].children[0]=1;fixture.components[0].children[1]=2;
  strcpy(fixture.components[1].id,"paragraph");fixture.components[1].type=BUFFER_A2UI_TEXT;
  strcpy(fixture.components[1].text,"A long result\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10\nline 11\nline 12\nline 13\nline 14\nline 15\nline 16\nline 17\nline 18\nline 19");
  strcpy(fixture.components[2].id,"timer");fixture.components[2].type=BUFFER_A2UI_TIMER_CARD;
  strcpy(fixture.components[2].payload.timer.id,"timer-one");
  buffer_a2ui_ui_open_current();advance(1000);lv_obj_update_layout(root);
  lv_obj_t *paragraph=g_nodes[1],*timer=g_nodes[2];assert(paragraph && timer);
  lv_obj_scroll_to_y(g_overlay_content,35,LV_ANIM_OFF);int scroll=lv_obj_get_scroll_y(g_overlay_content);
  fixture.sequence++;strcat(fixture.components[1].text," updated");
  buffer_a2ui_ui_refresh();assert(g_nodes[1]==paragraph && g_nodes[2]==timer);
  assert(lv_obj_get_scroll_y(g_overlay_content)==scroll);
  assert(strstr(lv_label_get_text(paragraph),"updated"));
  domain_state=BUFFER_ASSISTANT_PAUSED;buffer_a2ui_ui_refresh();
  assert(!strcmp(lv_label_get_text(lv_obj_get_child(g_dynamic_labels[0].toggle,0)),"继续"));
  domain_state=BUFFER_ASSISTANT_FIRED;buffer_a2ui_ui_refresh();
  assert(lv_obj_has_state(g_dynamic_labels[0].toggle,LV_STATE_DISABLED));
  assert(!strcmp(lv_label_get_text(lv_obj_get_child(g_dynamic_labels[0].cancel,0)),"关闭提醒"));
  fixture.full_screen=false;fixture.sequence++;buffer_a2ui_ui_refresh();
  ui_failure("timer failure",-EIO);
  /* Assistant results, including formerly inline surfaces, stay on their
   * own page. An error must not insert a new row into the home layout. */
  assert(g_rendered_full_screen && !g_inline_error);
  assert(g_feedback && !lv_obj_has_flag(g_feedback,LV_OBJ_FLAG_HIDDEN));
  assert(strstr(lv_label_get_text(g_feedback),"timer failure"));
  fixture.full_screen=true;fixture.sequence++;buffer_a2ui_ui_refresh();
  assert(g_inline_error==NULL);
  strcpy(fixture.id,"new-task");fixture.task_revision++;
  buffer_a2ui_ui_refresh();assert(!strcmp(g_rendered_surface_id,"reply-one"));
  g_accept_incoming=true;buffer_a2ui_ui_refresh();assert(!strcmp(g_rendered_surface_id,"new-task"));
  buffer_ui_motion_enter(g_nodes[0],1,true);lv_obj_delete(g_nodes[0]);advance(500);
  buffer_a2ui_ui_detach();
  const int ratios[][2]={{320,240},{240,320},{240,240},{480,320},{320,480},{800,480},{480,800}};
  for(unsigned r=0;r<sizeof(ratios)/sizeof(ratios[0]);r++) {
    lv_display_set_resolution(display,ratios[r][0],ratios[r][1]);
    lv_obj_t *page=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(page);
    lv_obj_set_size(page,LV_PCT(100),LV_PCT(100));lv_obj_set_flex_flow(page,LV_FLEX_FLOW_COLUMN);
    lv_obj_t *reading=lv_obj_create(page);lv_obj_remove_style_all(reading);lv_obj_set_size(reading,LV_PCT(100),0);lv_obj_set_flex_grow(reading,1);
    lv_obj_t *capture=lv_button_create(page);lv_obj_set_size(capture,LV_PCT(100),40);
    assert(!buffer_a2ui_ui_attach(reading,capture));buffer_a2ui_ui_refresh();advance(100);lv_obj_update_layout(page);
    lv_area_t overlay,button;lv_obj_get_coords(g_overlay,&overlay);lv_obj_get_coords(capture,&button);
    assert(overlay.x1>=0 && overlay.x2<ratios[r][0] && overlay.y1>=0 && overlay.y2<button.y1);
    screenshot("a2ui",ratios[r][0],ratios[r][1]);
    assert(lv_obj_get_height(g_overlay_content)>0);
    if(ratios[r][1]<=320)assert(lv_obj_get_scroll_bottom(g_overlay_content)>0);
    buffer_a2ui_ui_dock_page(page);advance(100);lv_obj_update_layout(page);
    lv_obj_t *bar=lv_obj_get_child(g_overlay,0),*back_button=lv_obj_get_child(bar,0);
    lv_area_t bar_area,back_area;lv_obj_get_coords(bar,&bar_area);lv_obj_get_coords(back_button,&back_area);
    assert(back_area.y1>=bar_area.y1&&back_area.y2<=bar_area.y2);
    buffer_a2ui_ui_open_current();advance(100);
    unsigned children=lv_obj_get_child_count(g_overlay_content);
    active_error=-EAGAIN;buffer_a2ui_ui_refresh();active_error=0;
    assert(lv_obj_get_child_count(g_overlay_content)==children);
    active_surface=false;buffer_a2ui_ui_open_current();advance(100);
    assert(!lv_obj_has_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN));
    assert(!lv_obj_has_flag(g_overlay,LV_OBJ_FLAG_HIDDEN)&&!lv_obj_get_child_count(g_overlay_content));
    active_surface=true;buffer_a2ui_ui_open_current();advance(100);
    assert(lv_obj_has_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN));
    screenshot("a2ui-docked",ratios[r][0],ratios[r][1]);
    buffer_a2ui_ui_detach();lv_obj_delete(page);
  }
  lv_display_set_resolution(display,320,480);
  struct buffer_oobe_ui_s *oobe=buffer_oobe_ui_create(lv_screen_active(),LV_FONT_DEFAULT,LV_FONT_DEFAULT,LV_FONT_DEFAULT,NULL,NULL);
  assert(oobe);struct buffer_oobe_ui_state_s state={0};state.page=BUFFER_OOBE_UI_READY;state.english_fallback=true;
  buffer_oobe_ui_render(oobe,&state);advance(1000);buffer_oobe_ui_render(oobe,&state);
  buffer_oobe_ui_destroy(oobe);advance(500);
  buffer_ui_motion_policy(true,false);buffer_ui_motion_enter(mic,1,true);
  assert(lv_obj_get_style_translate_x(mic,0)==0 && lv_obj_get_style_opa(mic,0)==255);
  buffer_ui_button_style(mic,true);lv_obj_add_state(mic,LV_STATE_PRESSED);
  assert(lv_color_to_u32(lv_obj_get_style_text_color(mic,0))==lv_color_to_u32(lv_color_white()));
  /* Exercise actual bounded row pool + worker on a compact logical screen. */
  lv_display_set_resolution(display,240,320);lv_obj_set_size(root,240,320);lv_obj_set_size(body,216,220);
  assert(buffer_todo_ui_attach(body)==0);
  advance(4000);buffer_todo_ui_refresh();usleep(10000);assert(__atomic_load_n(&todo_queries,__ATOMIC_RELAXED)==0);
  lv_obj_update_layout(root);
  assert(lv_obj_get_height(entry)>=buffer_ui_dp(48));
  assert(!buffer_todo_ui_open());
  buffer_todo_ui_set_visible(false);
  assert(lv_obj_has_flag(host,LV_OBJ_FLAG_HIDDEN));
  buffer_todo_ui_set_visible(true);
  assert(!lv_obj_has_flag(host,LV_OBJ_FLAG_HIDDEN));
  lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);
  assert(buffer_todo_ui_open() && !lv_obj_has_flag(panel,LV_OBJ_FLAG_HIDDEN));
  for (unsigned n=0;n<100;n++) { usleep(1000);pthread_mutex_lock(&lock);bool ready=dirty;pthread_mutex_unlock(&lock);if(ready)break; }
  buffer_todo_ui_refresh();lv_obj_update_layout(root);
  lv_obj_send_event(rows[mapping[0]].check,LV_EVENT_CLICKED,NULL);todo_ready();
  assert(!lv_obj_has_flag(status,LV_OBJ_FLAG_HIDDEN)&&strstr(lv_label_get_text(status),"提交失败"));
  for(unsigned n=0;n<3;n++){
    request();todo_ready();assert(!lv_obj_has_flag(status,LV_OBJ_FLAG_HIDDEN)&&strstr(lv_label_get_text(status),"提交失败"));
  }
  __atomic_store_n(&todo_submit_result,0,__ATOMIC_RELAXED);
  lv_obj_send_event(rows[mapping[0]].check,LV_EVENT_CLICKED,NULL);todo_ready();assert(lv_obj_has_flag(status,LV_OBJ_FLAG_HIDDEN));
  /* Docked S1 subpage keeps Back and pagination outside the scrolling list. */
  lv_obj_t *launchers=lv_obj_create(root);
  lv_obj_set_style_pad_all(root,0,0);
  buffer_ui_shell_init(root,body);
  buffer_todo_ui_dock_launcher(root,launchers);
  buffer_ui_shell_show("todo");
  assert(buffer_todo_ui_open()&&!lv_obj_has_flag(panel,LV_OBJ_FLAG_HIDDEN));
  edge_touch_back(root,true);assert(!buffer_todo_ui_open());buffer_todo_ui_refresh();assert(!buffer_todo_ui_open());
  assert(!buffer_ui_shell_open("todo",NULL));assert(buffer_todo_ui_open());
  lv_display_set_resolution(display,320,240);lv_obj_set_size(root,320,240);
  advance(100);lv_obj_update_layout(root);
  assert(!lv_obj_has_flag(host,LV_OBJ_FLAG_SCROLLABLE));
  assert(lv_obj_has_flag(list,LV_OBJ_FLAG_SCROLLABLE));
  assert(lv_obj_get_height(list)>0);
  lv_area_t before_back,after_back;lv_obj_get_coords(back,&before_back);
  lv_obj_scroll_to_y(list,80,LV_ANIM_OFF);advance(32);lv_obj_get_coords(back,&after_back);
  assert(before_back.y1==after_back.y1&&before_back.y2==after_back.y2);
  assert(after_back.y1>=0&&after_back.y2<240);
  screenshot("todo",320,240);
  assert(lv_obj_get_child(list,0)==rows[mapping[0]].root);
  unsigned original_slot=mapping[0];lv_obj_t *original_task=rows[original_slot].root;
  lv_obj_remove_flag(rows[original_slot].detail,LV_OBJ_FLAG_HIDDEN);
  reverse_tasks=true;request();
  for (unsigned n=0;n<100;n++) { usleep(1000);pthread_mutex_lock(&lock);bool ready=dirty;pthread_mutex_unlock(&lock);if(ready)break; }
  buffer_todo_ui_refresh();lv_obj_update_layout(root);
  assert(rows[mapping[1]].root==original_task);
  assert(!lv_obj_has_flag(rows[mapping[1]].detail,LV_OBJ_FLAG_HIDDEN));
  assert(lv_obj_get_width(rows[mapping[1]].title)<=lv_obj_get_content_width(rows[mapping[1]].root));
  pthread_mutex_lock(&lock);count=0;strcpy(cursor,"task-last");pthread_mutex_unlock(&lock);
  lv_obj_send_event(previous_button,LV_EVENT_CLICKED,NULL);
  pthread_mutex_lock(&lock);assert(cursor[0]==0);pthread_mutex_unlock(&lock);
  lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);
  assert(!buffer_todo_ui_open() && lv_obj_has_flag(panel,LV_OBJ_FLAG_HIDDEN));
  design_tasks=true;opened=true;request();
  for(unsigned n=0;n<100;n++){usleep(1000);pthread_mutex_lock(&lock);bool ready=dirty;pthread_mutex_unlock(&lock);if(ready)break;}
  buffer_todo_ui_refresh();assert(count==4);
  assert(!lv_obj_has_state(rows[mapping[2]].check,LV_STATE_DISABLED));
  lv_obj_send_event(rows[mapping[2]].check,LV_EVENT_CLICKED,NULL);todo_ready();
  assert(!strcmp(submitted_todo_action,"reopen")&&!strcmp(items[2].card.state,"active")&&!strcmp(rows[mapping[2]].action,"done"));
  lv_obj_send_event(rows[mapping[2]].check,LV_EVENT_CLICKED,NULL);todo_ready();
  assert(!strcmp(submitted_todo_action,"done")&&!strcmp(items[2].card.state,"done")&&!strcmp(rows[mapping[2]].action,"reopen"));
  lv_obj_add_flag(launchers,LV_OBJ_FLAG_HIDDEN);lv_obj_remove_state(mic,LV_STATE_ANY);
  lv_obj_set_style_border_width(root,0,0);lv_obj_set_style_radius(root,0,0);lv_obj_set_style_bg_color(root,lv_color_hex(BUFFER_BG),0);
  lv_obj_t *design_status=lv_obj_create(root);lv_obj_remove_style_all(design_status);lv_obj_set_height(design_status,buffer_ui_scaled_sp(28)+buffer_ui_dp(4));
  lv_obj_t *design_clock=lv_label_create(design_status);buffer_ui_text_role(design_clock,BUFFER_TEXT_TITLE_LARGE_EMPHASIZED);buffer_ui_label_set_text(design_clock,"09:46");lv_obj_set_pos(design_clock,buffer_ui_dp(10),0);
  assert(buffer_ui_shell_statusbar(design_status,"control-center"));
  lv_obj_clean(mic);buffer_ui_button_style(mic,false);lv_obj_set_style_pad_all(mic,0,0);lv_obj_set_height(mic,buffer_ui_dp(48));lv_obj_set_flex_flow(mic,LV_FLEX_FLOW_ROW);lv_obj_set_flex_align(mic,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);lv_obj_set_style_pad_column(mic,buffer_ui_dp(8),0);
  buffer_ui_icon_create(mic,&buffer_mic_icon);lv_obj_t *add=lv_label_create(mic);buffer_ui_text_role(add,BUFFER_TEXT_TITLE_MEDIUM_EMPHASIZED);buffer_ui_label_set_text(add,"添加");buffer_ui_shell_capture(mic,NULL);
  const int todo_sizes[][2]={{320,240},{240,320},{480,320},{800,480}};
  for(unsigned n=0;n<4;n++){
    lv_display_set_resolution(display,todo_sizes[n][0],todo_sizes[n][1]);lv_obj_set_size(root,todo_sizes[n][0],todo_sizes[n][1]);buffer_ui_shell_show("todo");page_layout(NULL);lv_obj_scroll_to_y(list,0,LV_ANIM_OFF);advance(200);
    assert(!lv_obj_has_state(rows[mapping[2]].check,LV_STATE_DISABLED));
    screenshot("todo-design",todo_sizes[n][0],todo_sizes[n][1]);
  }
  buffer_todo_ui_detach();
  buffer_ui_shell_shutdown();buffer_ui_motion_shutdown();lv_obj_delete(root);
#ifdef BUFFER_UI_REAL_FONTS
  buffer_ui_typography_shutdown();
#endif
  lv_display_delete(display);lv_deinit();
  puts("PASS: six springs, redirect, reduce/resume, real LVGL lifecycle, stable IDs/scroll, timer states, task interruption, OOBE destroy");
}
