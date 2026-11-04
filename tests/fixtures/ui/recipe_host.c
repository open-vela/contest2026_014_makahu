/* Actual A2UI model + assistant persistence/timers + LVGL recipe workflow.
 * Only work-queue scheduling and unrelated Buffer services are fixture adapters. */
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include "buffer_a2ui_lvgl.c"
#include "visual_capture.h"
int buffer_private_storage_prepare(void){return 0;}
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
void recipe_model_lock(void);
void recipe_model_unlock(void);
void recipe_model_restart(void);
static struct buffer_assistant_request_s fixture_recover_request;
static char fixture_recover_timers[BUFFER_ASSISTANT_MAX_RECIPE_STEPS][BUFFER_ASSISTANT_ID_SIZE];
static unsigned fixture_recovery_calls,fixture_recovery_attempts;
int __real_buffer_assistant_restore_recipe(const char *,const char *,char image[65]);
int __wrap_buffer_assistant_restore_recipe(const char *operation,const char *surface,char image[65])
{
 if(strcmp(operation,fixture_recover_request.operation_id))return __real_buffer_assistant_restore_recipe(operation,surface,image);
 if(++fixture_recovery_attempts==1)return -EAGAIN;
 fixture_recovery_calls++;image[0]=0;return buffer_a2ui_restore_recipe(&fixture_recover_request,fixture_recover_timers,surface);
}
void buffer_set_status(const char *format,...){(void)format;}
bool buffer_todo_ui_open(void){return false;}
static bool defer_work;
static void (*queued_work)(void *);static void *queued_context;
int buffer_ui_work_submit(void (*run)(void *),void *context)
{if(defer_work){if(queued_work)return -EBUSY;queued_work=run;queued_context=context;return 0;}run(context);free(context);return 0;}
static void pump(unsigned ms)
{for(unsigned i=0;i<ms;i+=16){lv_tick_inc(16);lv_timer_handler();}}
static lv_indev_state_t pointer_state=LV_INDEV_STATE_RELEASED;
static lv_point_t pointer;
static void read_pointer(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;if(pointer_state==LV_INDEV_STATE_PRESSED)data->point=pointer;data->state=pointer_state;}
static void side_back(lv_obj_t *window,bool left)
{
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,read_pointer);
 buffer_ui_shell_focus_refresh();int x=left?2:lv_obj_get_width(window)-3;
 pointer=(lv_point_t){x,lv_obj_get_height(window)/2};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 for(int i=1;i<=6;i++){pointer.x=x+(left?1:-1)*buffer_ui_dp(i*8);lv_indev_read(input);pump(16);}
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);pump(200);buffer_a2ui_ui_refresh();lv_indev_delete(input);
}
static lv_obj_t *plain(lv_obj_t *parent)
{lv_obj_t *obj=lv_obj_create(parent);lv_obj_remove_style_all(obj);lv_obj_set_size(obj,LV_PCT(100),LV_PCT(100));lv_obj_remove_flag(obj,LV_OBJ_FLAG_SCROLLABLE);return obj;}
static lv_obj_t *recipe_start(lv_obj_t *root)
{
 for(uint32_t i=0;i<lv_obj_get_event_count(root);i++){
  lv_event_dsc_t *event=lv_obj_get_event_dsc(root,i);
  if(lv_event_dsc_get_cb(event)==ui_action_event&&((uintptr_t)lv_event_dsc_get_user_data(event)&7u)==UI_ACTION_RECIPE_START)return root;
 }
 for(uint32_t i=0;i<lv_obj_get_child_count(root);i++){lv_obj_t *found=recipe_start(lv_obj_get_child(root,i));if(found)return found;}
 return NULL;
}
static lv_obj_t *find_text(lv_obj_t *root,const char *text)
{
 if(lv_obj_check_type(root,&lv_label_class)&&!strcmp(lv_label_get_text(root),text))return root;
 for(uint32_t i=0;i<lv_obj_get_child_count(root);i++){lv_obj_t *found=find_text(lv_obj_get_child(root,i),text);if(found)return found;}
 return NULL;
}
static lv_obj_t *find_callback(lv_obj_t *root,lv_event_cb_t callback)
{
 for(uint32_t i=0;i<lv_obj_get_event_count(root);i++)if(lv_event_dsc_get_cb(lv_obj_get_event_dsc(root,i))==callback)return root;
 for(uint32_t i=0;i<lv_obj_get_child_count(root);i++){lv_obj_t *found=find_callback(lv_obj_get_child(root,i),callback);if(found)return found;}return NULL;
}
static size_t timers(void)
{struct buffer_assistant_item_s items[BUFFER_ASSISTANT_MAX_TIMERS];size_t count=0;assert(!buffer_assistant_snapshot(BUFFER_ASSISTANT_EVENT_TIMER,items,BUFFER_ASSISTANT_MAX_TIMERS,&count));return count;}
static void *background_reply(void *unused)
{(void)unused;assert(!buffer_a2ui_present_reply("unrelated-background","另一个任务的结果"));return NULL;}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);if(!(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2))fprintf(stderr,"recipe bounds: child=%d,%d:%d,%d parent=%d,%d:%d,%d done=%d\n",a.x1,a.y1,a.x2,a.y2,b.x1,b.y1,b.x2,b.y2,child==g_recipe.done);assert(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2);}
int main(void)
{
 mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700);
 unlink(CONFIG_BUFFER_VELA_DATA_DIR "/ui.recipes");
 unlink(CONFIG_BUFFER_VELA_DATA_DIR "/assistant.state");unlink(CONFIG_BUFFER_VELA_DATA_DIR "/assistant.state.tmp");unlink(CONFIG_BUFFER_VELA_DATA_DIR "/assistant.json");
 lv_init();lv_display_t *display=lv_display_create(320,240);
 static uint32_t pixels[1280*16];lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 (void)buffer_ui_typography_init();buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
 lv_obj_t *window=plain(lv_screen_active());buffer_ui_text_role(window,BUFFER_TEXT_BODY_LARGE);
 lv_obj_t *desktop=plain(window);buffer_ui_shell_init(window,desktop);
 lv_obj_t *status=plain(window);int status_height=buffer_ui_scaled_sp(28)+buffer_ui_dp(4);
 if(status_height<buffer_ui_dp(36))status_height=buffer_ui_dp(36);
 lv_obj_set_height(status,status_height);assert(buffer_ui_shell_statusbar(status,"control-center"));
 lv_obj_t *capture=lv_button_create(window);lv_obj_set_height(capture,buffer_ui_dp(48));
 lv_obj_set_style_bg_opa(capture,LV_OPA_TRANSP,0);lv_obj_set_style_shadow_width(capture,0,0);
 lv_obj_t *mic=lv_obj_create(capture);lv_obj_remove_style_all(mic);lv_obj_set_size(mic,buffer_ui_dp(40),buffer_ui_dp(40));
 lv_obj_set_style_bg_color(mic,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);lv_obj_set_style_bg_opa(mic,LV_OPA_COVER,0);lv_obj_set_style_radius(mic,buffer_ui_dp(16),0);lv_obj_center(mic);
 lv_obj_t *icon=buffer_ui_icon_create(mic,&buffer_mic_icon);lv_obj_center(icon);
 assert(!buffer_a2ui_ui_attach(desktop,capture));buffer_a2ui_ui_dock_page(desktop);buffer_ui_shell_capture(capture,NULL);
 struct buffer_assistant_recipe_step_s steps[3]={0};
 strcpy(steps[0].title,"小火炖煮");strcpy(steps[1].title,"调味");strcpy(steps[2].title,"装盘");
 for(unsigned i=0;i<80;i++)memcpy(steps[0].detail+3*i,"炖",3);
 steps[0].timer_ms=1000;
 strcpy(steps[1].detail,"加入适量盐，尝味后调整。此步骤不需要计时。");strcpy(steps[2].detail,"装盘后检查材料与火源。");
 char ingredients[256]={0};for(unsigned i=0;i<72;i++)memcpy(ingredients+3*i,"菜",3);
 assert(!buffer_assistant_init());assert(!buffer_a2ui_present_recipe("recipe-ui-chain","番茄炖豆腐",ingredients,steps,3));
 assert(!timers());buffer_a2ui_ui_open_current();pump(100);
 assert(g_recipe.root&&g_recipe.session->count==3);
 assert(!strcmp(g_recipe_materials,ingredients));assert(strlen(lv_label_get_text(g_recipe.detail))==240);
 lv_obj_send_event(g_recipe.next,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->current==1&&!timers());
 lv_obj_send_event(g_recipe.overview,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->overview&&!timers());
 lv_obj_send_event(g_recipe.step_buttons[0],LV_EVENT_CLICKED,NULL);assert(g_recipe.session->current==0&&!g_recipe.session->overview);
 lv_obj_send_event(g_recipe.done,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->started&&!g_recipe.session->done&&!timers());
 lv_obj_t *start=recipe_start(g_recipe.timer);
 defer_work=true;lv_obj_send_event(start,LV_EVENT_CLICKED,NULL);lv_obj_send_event(start,LV_EVENT_CLICKED,NULL);
 assert(queued_work&&!timers());buffer_a2ui_ui_refresh();assert(recipe_start(g_recipe.timer));
 defer_work=false;queued_work(queued_context);free(queued_context);queued_work=NULL;queued_context=NULL;
 assert(!buffer_a2ui_present_reply("timer-in-flight-reply","另一个后台回答"));
 assert(timers()==1);buffer_a2ui_ui_refresh();pump(100);
 assert(g_recipe.timer_id[0]&&!recipe_start(g_recipe.timer)&&g_pending_id[0]);
 char timer[BUFFER_ASSISTANT_ID_SIZE];strcpy(timer,g_recipe.timer_id);assert(timer[0]);
 struct buffer_assistant_item_s item;
 for(unsigned i=0;i<200;i++){assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,timer,&item));if(item.state==BUFFER_ASSISTANT_FIRED)break;usleep(10000);}
 assert(item.state==BUFFER_ASSISTANT_FIRED&&item.recipe_step);buffer_a2ui_ui_refresh();assert(!g_recipe.session->done&&g_recipe.session->current==0);
 /* A shorter materials block shifts component indices. The same step retains
  * its domain timer and its buttons must target the new component index. */
 unsigned old_index=g_recipe.timer_index;
 assert(!buffer_a2ui_present_recipe("recipe-ui-chain","番茄炖豆腐","菜",steps,3));
 buffer_a2ui_ui_refresh();pump(32);assert(g_recipe.timer_index!=old_index);
 assert(!strcmp(g_recipe.timer_id,timer));
 struct buffer_notification notices[BUFFER_NOTIFICATION_LIMIT];buffer_a2ui_ui_publish_activities(1000);
 size_t count=buffer_notification_snapshot(notices,BUFFER_NOTIFICATION_LIMIT,true,true,1000);assert(count==1&&notices[0].live&&notices[0].progress==0);
 lv_obj_send_event(g_recipe.done,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->done==1&&g_recipe.session->current==1);
 buffer_ui_shell_home();buffer_a2ui_ui_open_current();pump(100);assert(g_recipe.session->current==1&&g_recipe.session->done==1);
 /* Teardown clears the entire in-memory recipe session cache. Rebuilding
  * against the same domain model must recover progress from its private file. */
 char recovered_surface[BUFFER_A2UI_SURFACE_ID_SIZE];strcpy(recovered_surface,g_rendered_surface_id);
 fixture_recover_request.operation=BUFFER_ASSISTANT_RECIPE_SHOW;strcpy(fixture_recover_request.operation_id,"recipe-ui-chain");strcpy(fixture_recover_request.recipe_title,"番茄炖豆腐");strcpy(fixture_recover_request.ingredients,"菜");fixture_recover_request.step_count=3;memcpy(fixture_recover_request.steps,steps,3*sizeof(*steps));strcpy(fixture_recover_timers[0],timer);
 buffer_a2ui_ui_detach();assert(!g_recipe.root&&!g_recipes[0].used);recipe_model_restart();buffer_notification_reset();
 assert(!buffer_a2ui_ui_attach(desktop,capture));buffer_a2ui_ui_dock_page(desktop);
 /* Private progress is loaded by a real worker after attach. Wait for that
  * publication without advancing the UI clock or its retry deadline. */
 for(unsigned wait=0;wait<1000&&!fixture_recovery_attempts;wait++){buffer_a2ui_ui_refresh();usleep(1000);}
 assert(fixture_recovery_attempts==1&&!fixture_recovery_calls);
 for(unsigned poll=0;poll<10;poll++)buffer_a2ui_ui_refresh();
 assert(fixture_recovery_attempts==1);lv_tick_inc(1000);
 for(unsigned wait=0;wait<1000&&!fixture_recovery_calls;wait++){usleep(1000);buffer_a2ui_ui_refresh();}
 assert(fixture_recovery_calls==1&&!buffer_ui_shell_has_app());
 buffer_a2ui_ui_publish_activities(1000);count=buffer_notification_snapshot(notices,BUFFER_NOTIFICATION_LIMIT,true,true,1000);assert(count==1&&notices[0].live&&notices[0].progress==1&&notices[0].progress_max==3);assert(buffer_a2ui_ui_recipe_running(recovered_surface));
 struct buffer_a2ui_surface_s *restored=malloc(sizeof(*restored));assert(restored);assert(buffer_a2ui_model_get_active(restored)==-ENOENT);assert(!buffer_a2ui_model_get_surface(recovered_surface,restored));free(restored);
 struct buffer_ui_recipe_progress recovered_progress;struct buffer_assistant_surface_card_s recovered_cards[3];assert(buffer_a2ui_surface_cards(recovered_cards,3)==1);
 assert(!buffer_ui_recipe_state_get(recovered_surface,recovered_cards[0].recipe_fingerprint,&recovered_progress));
 struct buffer_ui_recipe_progress obsolete_progress=recovered_progress;strcpy(obsolete_progress.fingerprint,"ffffffffffffffff");assert(!buffer_ui_recipe_state_set(&obsolete_progress));
 for(unsigned wait=0;wait<1000&&buffer_a2ui_ui_recipe_running(recovered_surface);wait++){usleep(1000);buffer_a2ui_ui_publish_activities(1001);}
 assert(!buffer_notification_snapshot(notices,BUFFER_NOTIFICATION_LIMIT,true,true,1001)&&!buffer_a2ui_ui_recipe_running(recovered_surface));
 assert(!buffer_ui_recipe_state_set(&recovered_progress));
 for(unsigned wait=0;wait<1000&&!buffer_a2ui_ui_recipe_running(recovered_surface);wait++){usleep(1000);buffer_a2ui_ui_publish_activities(1002);}
 assert(buffer_a2ui_ui_recipe_running(recovered_surface));
 assert(!buffer_a2ui_ui_open_surface(recovered_surface));
 for(unsigned wait=0;wait<1000;wait++){usleep(1000);buffer_a2ui_ui_refresh();if(g_recipe.root&&!g_recipe.session->restore_pending)break;}
 assert(g_recipe.root&&g_recipe.session->started&&g_recipe.session->current==1&&g_recipe.session->done==1);
 assert(timers()==1&&!strcmp(g_recipe.session->fingerprint,g_recipes[0].fingerprint));
 recipe_model_lock();unsigned previous=lv_obj_get_child_count(g_overlay_content);buffer_a2ui_ui_refresh();
 assert(buffer_a2ui_ui_open_surface(g_rendered_surface_id)==-EAGAIN);
 assert(buffer_ui_shell_is_open("assistant"));
 struct buffer_assistant_surface_card_s cards[3];assert(buffer_a2ui_surface_cards(cards,3)==-EAGAIN);recipe_model_unlock();
 assert(lv_obj_get_child_count(g_overlay_content)==previous);
 assert(buffer_a2ui_ui_open_surface("missing-surface")==-ENOENT);
 assert(g_recipe.session->current==1&&g_recipe.session->done==1);
 buffer_ui_shell_home();assert(!buffer_a2ui_ui_open());
 assert(!buffer_ui_shell_open("assistant",g_rendered_surface_id));
 assert(buffer_a2ui_ui_open()&&g_recipe.session->current==1&&g_recipe.session->done==1);
 /* An explicit target stays pinned until its first render even when input
  * delays that render and another producer replaces the model's active ID. */
 char pinned[BUFFER_A2UI_SURFACE_ID_SIZE];strcpy(pinned,g_rendered_surface_id);
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,read_pointer);
 pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);assert(ui_input_busy());
 assert(!buffer_a2ui_ui_open_surface(pinned)&&g_open_snapshot_ready);
 assert(!buffer_a2ui_present_reply("navigation-concurrent-reply","另一个请求的结果"));
 buffer_a2ui_ui_refresh();assert(g_open_snapshot_ready&&!strcmp(g_incoming.id,pinned));
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);lv_indev_delete(input);
 buffer_a2ui_ui_refresh();assert(!g_open_snapshot_ready&&!strcmp(g_rendered_surface_id,pinned));
 buffer_a2ui_ui_refresh();assert(!strcmp(g_rendered_surface_id,pinned)&&g_pending_id[0]);
 assert(!buffer_a2ui_ui_open_surface(pinned));
 lv_obj_set_style_translate_x(g_render_parent,17,0);
 lv_obj_send_event(lv_obj_get_child(lv_obj_get_child(g_overlay,0),0),LV_EVENT_CLICKED,NULL);
 assert(!buffer_ui_shell_is_open("assistant")&&g_page_browsing&&!g_open_snapshot_ready);
 assert(lv_obj_get_style_translate_x(g_render_parent,0)==0);
 assert(!buffer_a2ui_ui_open_surface(pinned));
 /* Navigation and layout changes preserve progress; timer expiry never checks
  * a step, and completing steps never cancels the independent timer. */
 const int sizes[][2]={{320,240},{240,320},{480,320},{320,480},{800,480},{480,800},{1280,720},{200,240}};
 for(unsigned i=0;i<sizeof(sizes)/sizeof(sizes[0]);i++){
  lv_display_set_resolution(display,sizes[i][0],sizes[i][1]);pump(120);buffer_a2ui_ui_refresh();pump(32);
  inside(g_recipe.root,g_overlay_content);inside(g_recipe.footer,g_recipe.root);assert(lv_obj_get_height(g_recipe.reader)>0);
  inside(g_recipe.previous,g_recipe.footer);inside(g_recipe.next,g_recipe.footer);
  if(g_recipe.wide){lv_obj_scroll_to_view_recursive(g_recipe.done,LV_ANIM_OFF);inside(g_recipe.done,g_recipe.reader);}else inside(g_recipe.done,g_recipe.footer);
  if(!lv_obj_has_flag(g_recipe.overview,LV_OBJ_FLAG_HIDDEN))inside(g_recipe.overview,g_recipe.footer);
  lv_obj_scroll_to_y(g_recipe.reader,0,LV_ANIM_OFF);pump(32);screenshot("recipe",sizes[i][0],sizes[i][1]);
  lv_area_t before,after;lv_obj_get_coords(g_recipe.footer,&before);
  lv_obj_scroll_to_y(g_recipe.reader,300,LV_ANIM_OFF);pump(16);lv_obj_get_coords(g_recipe.footer,&after);assert(before.y1==after.y1);
  screenshot("recipe-scrolled",sizes[i][0],sizes[i][1]);
  if(sizes[i][0]==1280)assert(g_recipe.wide);
  assert(g_recipe.session->current==1&&g_recipe.session->done==1&&timers()==1);
 }
 lv_obj_send_event(g_recipe.done,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->current==2);
 lv_obj_send_event(g_recipe.done,LV_EVENT_CLICKED,NULL);assert(g_recipe.session->done==7);
 assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,timer,&item)&&item.state==BUFFER_ASSISTANT_FIRED);
 buffer_a2ui_ui_publish_activities(2000);count=buffer_notification_snapshot(notices,24,true,true,2000);assert(!count);
 count=buffer_notification_snapshot(notices,24,false,true,2000);assert(count==1&&!notices[0].live&&notices[0].phase==BUFFER_NOTICE_COMPLETE);
 assert(!buffer_a2ui_ui_recipe_result_visible(g_recipe.session->surface,g_recipe.session->fingerprint));
 /* Server-generated recipes receive stable request keys locally. Repeated
  * updates keep the bound timer; an old rendered action cannot target a new
  * semantic revision, including one that arrives before the next UI refresh. */
 const char *create="{\"version\":\"v0.9.1\",\"createSurface\":{\"surfaceId\":\"remote-recipe\",\"catalogId\":\"https://vela.local/a2ui/catalogs/assistant/1\"}}";
 const char *wire="{\"version\":\"v0.9.1\",\"updateComponents\":{\"surfaceId\":\"remote-recipe\",\"components\":[{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"step\"]},{\"id\":\"step\",\"component\":\"VelaRecipeStep\",\"title\":\"蒸熟\",\"detail\":\"等待蒸熟后手动确认\",\"timerMs\":1000}]}}";
 assert(!buffer_a2ui_apply_message(create,strlen(create),10,true));assert(!buffer_a2ui_apply_message(wire,strlen(wire),10,false));
 buffer_a2ui_ui_open_current();pump(32);assert(g_recipe.root&&g_recipe.session->count==1);
 start=recipe_start(g_recipe.timer);assert(start);
 assert(!buffer_a2ui_apply_message(wire,strlen(wire),11,false));
 lv_obj_send_event(start,LV_EVENT_CLICKED,NULL);assert(timers()==1&&g_action_error==-ESTALE);
 buffer_a2ui_ui_open_current();pump(32);start=recipe_start(g_recipe.timer);
 lv_obj_send_event(start,LV_EVENT_CLICKED,NULL);lv_obj_send_event(start,LV_EVENT_CLICKED,NULL);assert(timers()==1);
 buffer_a2ui_ui_refresh();assert(g_recipe.timer_id[0]);char remote_timer[BUFFER_ASSISTANT_ID_SIZE];strcpy(remote_timer,g_recipe.timer_id);
 assert(strcmp(remote_timer,timer));
 assert(!buffer_a2ui_apply_message(wire,strlen(wire),11,false));buffer_a2ui_ui_refresh();assert(!strcmp(remote_timer,g_recipe.timer_id)&&timers()==1);
 lv_obj_t *toggle=NULL;
 for(size_t i=0;i<g_dynamic_label_count;i++)if(g_dynamic_labels[i].label&&!strcmp(g_dynamic_labels[i].object_id,remote_timer))toggle=g_dynamic_labels[i].toggle;
 assert(toggle);lv_obj_send_event(toggle,LV_EVENT_CLICKED,NULL);buffer_a2ui_ui_refresh();
 assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,remote_timer,&item)&&item.state==BUFFER_ASSISTANT_PAUSED);
 assert(g_recipe.root&&!strcmp(g_rendered_surface_id,"remote-recipe"));
 lv_obj_send_event(toggle,LV_EVENT_CLICKED,NULL);buffer_a2ui_ui_refresh();
 assert(!buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,remote_timer,&item)&&item.state==BUFFER_ASSISTANT_RUNNING);
 /* User's design scenes exercise real request-to-result navigation, the
  * recipe preview/full/local-Back route, artwork binding and timer controls. */
 lv_display_set_resolution(display,320,240);pump(200);buffer_ui_shell_home();
 buffer_a2ui_ui_capture_prompt("帮我打开海带红烧肉菜谱。","design-recipe-capture");
 assert(!buffer_a2ui_present_reply("unrelated-background","另一个任务的结果"));buffer_a2ui_ui_refresh();
 assert(buffer_ui_shell_is_open("assistant")&&g_capture_pending&&!g_prompt_surface[0]);
 assert(!lv_obj_has_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN)&&lv_obj_has_flag(g_overlay_content,LV_OBJ_FLAG_HIDDEN));
 assert(!buffer_a2ui_capture_context_begin("design-recipe-capture"));
 pthread_t background;assert(!pthread_create(&background,NULL,background_reply,NULL));assert(!pthread_join(background,NULL));
 struct buffer_a2ui_surface_s background_surface;assert(!buffer_a2ui_model_get_active(&background_surface));assert(!background_surface.capture_id[0]);
 memset(steps,0,sizeof(steps));strcpy(steps[0].title,"炖煮");strcpy(steps[0].detail,"小火炖煮一个小时。");steps[0].timer_ms=3600000;
 strcpy(steps[1].title,"收汁");strcpy(steps[1].detail,"大火收汁后装盘。");
 assert(!buffer_a2ui_present_recipe_image("recipe-ui-chain","海带红烧肉","海带、五花肉、生抽、老抽、料酒。",steps,2,"fixture-recipe-photo"));
 buffer_a2ui_capture_context_end();
 buffer_a2ui_ui_refresh();pump(200);assert(buffer_ui_shell_is_open("assistant")&&!g_recipe.root);
 lv_obj_t *preview=find_callback(g_overlay_content,ui_recipe_preview_open);assert(preview);screenshot("assistant-recipe-preview",320,240);
 lv_obj_t *preview_identity=preview;unsigned response_children=lv_obj_get_child_count(g_session_response);
 assert(!buffer_a2ui_present_recipe_image("recipe-ui-chain","海带红烧肉","海带、五花肉、生抽、老抽、料酒。",steps,2,"fixture-recipe-photo"));buffer_a2ui_ui_refresh();pump(100);
 assert(g_recipe_preview_panel==preview_identity&&lv_obj_get_child_count(g_session_response)==response_children);
 lv_area_t reply_area,preview_area;lv_obj_get_coords(lv_obj_get_child(g_session_response,0),&reply_area);lv_obj_get_coords(preview_identity,&preview_area);assert(preview_area.y1-reply_area.y2-1==buffer_ui_dp(3));

 lv_obj_send_event(preview,LV_EVENT_CLICKED,NULL);pump(200);assert(g_recipe.root);
 assert(lv_image_get_src(g_recipe.image)==buffer_ui_recipe_artwork("fixture-recipe-photo"));screenshot("assistant-recipe-full",320,240);
 side_back(window,true);pump(100);assert(buffer_ui_shell_is_open("assistant")&&!g_recipe.root);
 side_back(window,false);assert(!buffer_ui_shell_is_open("assistant"));
 char recipe_return_id[BUFFER_A2UI_SURFACE_ID_SIZE];strcpy(recipe_return_id,g_rendered_surface_id);
 size_t running_timers=timers();
 /* Desktop/live-activity entry has no assistant-preview parent, even when
  * this recipe used to be associated with a capture conversation. */
 assert(!buffer_a2ui_ui_open_recipe_surface(recipe_return_id));pump(100);assert(g_recipe.root);
 side_back(window,false);assert(!buffer_ui_shell_is_open("assistant")&&timers()==running_timers);
 for(unsigned n=0;n<20;n++)buffer_a2ui_ui_refresh();
 assert(!buffer_ui_shell_is_open("assistant"));
 buffer_a2ui_ui_capture_prompt("帮我定一个十分钟计时器。","design-timer-capture");
 struct buffer_assistant_request_s request={.operation=BUFFER_ASSISTANT_TIMER_CREATE,.duration_ms=600000};
 strcpy(request.operation_id,"design-ten-minute");strcpy(request.label,"十分钟计时器");struct buffer_assistant_result_s result;
 assert(!buffer_a2ui_capture_context_begin("design-timer-capture"));
 assert(!buffer_assistant_execute(&request,&result));assert(!buffer_a2ui_present_timer(result.object_id));buffer_a2ui_capture_context_end();
 buffer_a2ui_ui_refresh();pump(200);assert(buffer_ui_shell_is_open("assistant"));screenshot("assistant-timer-session",320,240);
 assert(lv_obj_get_scroll_y(g_overlay_content)==0);
 bool visible_time=false;for(size_t i=0;i<g_dynamic_label_count;i++)if(g_dynamic_labels[i].label&&g_dynamic_labels[i].inverse){
  assert(lv_color_eq(lv_obj_get_style_text_color(g_dynamic_labels[i].label,0),lv_color_hex(BUFFER_ON_PRIMARY)));
  assert(strchr(lv_label_get_text(g_dynamic_labels[i].label),':'));visible_time=true;
 }assert(visible_time);
 lv_obj_t *timer_preview=find_callback(g_overlay_content,ui_timer_preview_open);assert(timer_preview);
 lv_obj_send_event(timer_preview,LV_EVENT_CLICKED,NULL);pump(100);assert(g_timer_expanded);
 assert(buffer_ui_shell_back()&&!g_timer_expanded&&buffer_ui_shell_is_open("assistant"));
 ui_timer_preview_open(NULL);buffer_a2ui_ui_refresh();assert(g_timer_expanded);
 assert(!buffer_a2ui_ui_open_recipe_surface(recipe_return_id));pump(100);assert(g_recipe.root&&!g_timer_expanded);
 side_back(window,true);assert(!buffer_ui_shell_is_open("assistant"));
 buffer_ui_shell_home();buffer_a2ui_ui_capture_prompt("稍后返回的请求","navigation-away");
 lv_obj_t *other=plain(window);assert(buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="design-other",.title="其他页面",.kind=BUFFER_UI_APP,.entry_route="design-other"},other));
 assert(!buffer_ui_shell_open("design-other",NULL));buffer_a2ui_ui_refresh();
 assert(!buffer_a2ui_capture_context_begin("navigation-away"));assert(!buffer_a2ui_present_reply("unrelated-background","完成"));buffer_a2ui_capture_context_end();
 buffer_a2ui_ui_refresh();pump(100);assert(buffer_ui_shell_is_open("design-other")&&!g_capture_pending);
 buffer_ui_shell_home();
 pthread_mutex_lock(&g_buffer.lock);strcpy(g_buffer.record_id,"plain-capture");buffer_voice_begin_locked();pthread_mutex_unlock(&g_buffer.lock);
 buffer_a2ui_ui_capture_prompt("直接回答测试","plain-capture");
 buffer_voice_update("old-capture",BUFFER_VOICE_ERROR,NULL,-EIO);
 buffer_voice_update("plain-capture",BUFFER_VOICE_ASR,NULL,0);buffer_a2ui_ui_refresh();
 assert(!strcmp(lv_label_get_text(g_empty_hint),"正在识别语音…"));
 assert(lv_color_eq(lv_obj_get_style_text_color(g_empty_hint,0),lv_color_hex(BUFFER_ON_PRIMARY)));
 buffer_voice_update("plain-capture",BUFFER_VOICE_EXECUTING,"完整识别的请求",0);buffer_a2ui_ui_refresh();
 assert(!strcmp(lv_label_get_text(g_empty_hint),"正在执行本次指令…")&&!strcmp(lv_label_get_text(g_capture_request),"完整识别的请求"));
 buffer_voice_update("old-capture",BUFFER_VOICE_READY,"旧请求",0);buffer_a2ui_ui_refresh();
 assert(!strcmp(lv_label_get_text(g_capture_request),"完整识别的请求"));pump(40);screenshot("assistant-voice-waiting",320,240);
 assert(buffer_ui_shell_is_open("assistant")&&!lv_obj_has_flag(g_empty_host,LV_OBJ_FLAG_HIDDEN));
 assert(!buffer_a2ui_capture_context_begin("plain-capture"));assert(!buffer_a2ui_present_reply("plain-reply","这是一个普通回答。"));buffer_a2ui_capture_context_end();
 buffer_a2ui_ui_refresh();pump(40);
 assert(!buffer_a2ui_ui_result_unread(g_surface_snapshot.id,g_surface_snapshot.sequence));
 assert(buffer_a2ui_ui_result_unread(g_surface_snapshot.id,g_surface_snapshot.sequence+1));
 assert(g_session_response&&lv_color_eq(lv_obj_get_style_bg_color(g_session_response,0),lv_color_hex(BUFFER_ACCENT)));
 lv_obj_t *plain_text=find_text(g_session_response,"这是一个普通回答。");assert(plain_text&&lv_color_eq(lv_obj_get_style_text_color(plain_text,0),lv_color_hex(BUFFER_ON_PRIMARY)));pump(40);screenshot("assistant-plain-reply",320,240);
 buffer_a2ui_ui_detach();recipe_model_restart();
 assert(!buffer_a2ui_present_reply("busy-one","one"));assert(!buffer_a2ui_present_reply("busy-two","two"));assert(!buffer_a2ui_present_reply("busy-three","three"));
 struct buffer_a2ui_surface_s *busy=malloc(sizeof(*busy));assert(busy);assert(!buffer_a2ui_model_get_active(busy));char busy_active[64];strcpy(busy_active,busy->id);
 assert(buffer_a2ui_restore_recipe(&fixture_recover_request,fixture_recover_timers,recovered_surface)==-ENOSPC);assert(!buffer_a2ui_model_get_active(busy)&&!strcmp(busy->id,busy_active));free(busy);
 buffer_ui_shell_shutdown();lv_obj_delete(window);buffer_ui_motion_shutdown();buffer_ui_typography_shutdown();
 lv_display_delete(display);lv_deinit();
 puts("PASS: actual model/timer persistence, full UTF-8 ingredients/details, duplicate starts, explicit completion, Home/reopen, notifications and responsive recipe panes");
}
