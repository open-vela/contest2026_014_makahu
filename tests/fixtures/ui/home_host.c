/* Entire production home builder/refresh, with controlled domain snapshots. */
#include <assert.h>
#include <stdio.h>
#include <semaphore.h>
#include <sys/stat.h>
#include "buffer_ui.c"
#include "edge_touch_fixture.h"
static struct buffer_ui_runtime_s fixture;
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
#include "home_domains.inc"
static sem_t io_entered, io_release;
static unsigned io_done;
static lv_indev_t *touch;
static lv_point_t pointer;
static lv_indev_state_t pointer_state=LV_INDEV_STATE_RELEASED;
static void read_pointer(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;if(pointer_state==LV_INDEV_STATE_PRESSED)data->point=pointer;data->state=pointer_state;}
static void blocked_storage(void *context)
{(void)context;sem_post(&io_entered);sem_wait(&io_release);__atomic_store_n(&io_done,1,__ATOMIC_RELEASE);}
#include "visual_capture.h"
static void pump(unsigned n)
{while(n--){lv_tick_inc(16);lv_timer_handler();}}
static void pull_center(void)
{
 lv_obj_update_layout(g_root);lv_area_t bar;lv_obj_get_coords(g_header,&bar);
 int x=(bar.x1+bar.x2)/2,y=bar.y1+buffer_ui_dp(3);
 pointer=(lv_point_t){x,y};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
 int previous=-lv_obj_get_height(g_mode_menu);
 for(int i=1;i<=5;i++){
  pointer.y=y+buffer_ui_dp(8*i);lv_indev_read(touch);
  if(i>=2){int position=lv_obj_get_style_translate_y(g_mode_menu,0);assert(position>previous&&position<0);previous=position;}
 }
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);pump(20);
 assert(buffer_ui_shell_is_open("control-center"));
}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{
 lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);
 if(!(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2))
  fprintf(stderr,"child %d,%d:%d,%d parent %d,%d:%d,%d\n",a.x1,a.y1,a.x2,a.y2,b.x1,b.y1,b.x2,b.y2);
 assert(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2);
}
static void close_center(void)
{
 lv_obj_update_layout(g_root);assert(lv_obj_get_child(g_mode_menu,0)==g_menu_columns);
 lv_area_t bounds;lv_obj_get_coords(g_mode_menu,&bounds);
 pointer=(lv_point_t){bounds.x2-buffer_ui_dp(3),bounds.y2-buffer_ui_dp(3)};
 pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
 for(unsigned i=0;i<5;i++){pointer.y-=buffer_ui_dp(8);lv_indev_read(touch);}
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);pump(20);
 assert(!buffer_ui_shell_is_open("control-center"));
}
int main(void)
{
 mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700);unlink(CONFIG_BUFFER_VELA_DATA_DIR "/ui.preferences");unlink(CONFIG_BUFFER_VELA_DATA_DIR "/ui.preferences.tmp");
 lv_init();lv_display_t *d=lv_display_create(320,240);
 const char *dpi=getenv("BUFFER_UI_DPI");if(dpi)lv_display_set_dpi(d,atoi(dpi));
 static uint32_t pixels[1280*16];lv_display_set_buffers(d,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(d,flush);
 strcpy(fixture.mode,"normal");strcpy(fixture.status,"ready");fixture.card_count=2;
 for(unsigned i=0;i<2;i++){
  snprintf(fixture.cards[i].card_id,BUFFER_ID_SIZE,"card-%u",i);
  strcpy(fixture.cards[i].title,"明天的安排与需要跟进的事情");
  strcpy(fixture.cards[i].summary,"今天下午整理项目进度，明天上午九点开会，记得提前准备材料。");
 }
 strcpy(fixture.cards[0].kind,"spark_card");strcpy(fixture.cards[0].title,"建立图书角");
 strcpy(fixture.cards[0].summary,"在每个教室建立一个图书角，鼓励同学们带来自己喜欢的书籍，在课间阅读、交换和分享。定期开展读书交流，让大家介绍喜欢的故事，记录阅读收获。图书角可以由同学们轮流整理，共同维护。");
 strcpy(fixture.cards[1].kind,"question_card");strcpy(fixture.cards[1].title,"为什么海水是咸的？");
 g_initial_page_pending=false;assert(!buffer_ui_init(false));buffer_ui_motion_policy(true,false);
 touch=lv_indev_create();lv_indev_set_type(touch,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(touch,read_pointer);
 assert(buffer_ui_shell_open("notifications",NULL)==-ENOENT);
 assert(!buffer_ui_shell_open("settings",NULL));pump(5);assert(buffer_ui_shell_is_open("settings"));
 assert(lv_obj_get_parent(lv_obj_get_parent(g_private_option))==g_settings_preferences);
 assert(lv_obj_get_parent(lv_obj_get_parent(lv_obj_get_parent(g_font_slider)))==g_settings_preferences);
 lv_textarea_set_text(g_settings_ssid,"test-network");lv_textarea_set_text(g_settings_password,"private-password");
 lv_obj_send_event(g_settings_password,LV_EVENT_CLICKED,NULL);assert(!lv_obj_has_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN));
 assert(buffer_ui_shell_back()&&buffer_ui_shell_is_open("settings")&&lv_obj_has_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN));
 lv_obj_send_event(g_settings_connect,LV_EVENT_CLICKED,NULL);
 for(unsigned n=0;n<1000;n++){pthread_mutex_lock(&g_settings_lock);bool busy=g_settings_busy;pthread_mutex_unlock(&g_settings_lock);if(!busy)break;usleep(1000);}
 settings_refresh();assert(fixture_settings_wifi==1&&!strcmp(lv_textarea_get_text(g_settings_password),"")&&!strcmp(lv_label_get_text(g_settings_feedback),"网络已连接"));
 for(unsigned kind=1;kind<=3;kind++){
  lv_obj_t *reset=g_settings_reset_buttons[kind-1];
  lv_obj_send_event(reset,LV_EVENT_CLICKED,NULL);assert(g_settings_reset_kind==kind);
  assert(buffer_ui_shell_back()&&buffer_ui_shell_is_open("settings")&&!g_settings_reset_kind&&!fixture_settings_resets);
 }
 assert(buffer_ui_shell_back()&&!buffer_ui_shell_is_open("settings"));
 buffer_ui_label_set_text(g_connection_label,"离线");buffer_header_layout();lv_obj_update_layout(g_header);
 assert(lv_obj_has_flag(g_connection_label,LV_OBJ_FLAG_HIDDEN)&&!lv_obj_has_flag(g_status_alert,LV_OBJ_FLAG_HIDDEN));
 assert(lv_image_get_src(g_status_alert)==buffer_ui_icon_source(&buffer_devices_off_icon,buffer_ui_dp(20)));
 assert(!buffer_ui_preferences_set(BUFFER_UI_PREF_REDUCED,true));
 unsigned initial_scale=buffer_ui_font_scale();
 lv_slider_set_value(g_font_slider,1,LV_ANIM_OFF);lv_obj_send_event(g_font_slider,LV_EVENT_VALUE_CHANGED,NULL);
 assert(buffer_ui_font_scale()==initial_scale);lv_obj_send_event(g_font_slider,LV_EVENT_RELEASED,NULL);
 assert(buffer_ui_font_scale()==125&&lv_slider_get_value(g_font_slider)==1);
 for(unsigned i=0;i<4;i++)if(g_font_sizes[i]==initial_scale){lv_slider_set_value(g_font_slider,i,LV_ANIM_OFF);lv_obj_send_event(g_font_slider,LV_EVENT_RELEASED,NULL);}
 assert(buffer_ui_font_scale()==initial_scale);
 /* An actual pointer press remains owned by the same capture widget while
  * Shell reparents it from the scrolling desktop dock into the modal. */
 buffer_ui_refresh(NULL);pump(10);assert(buffer_ui_desktop_reveal(g_desktop,"home"));pump(5);
 lv_area_t dock_bounds;lv_obj_get_coords(g_record_button,&dock_bounds);
 pointer=(lv_point_t){(dock_bounds.x1+dock_bounds.x2)/2,(dock_bounds.y1+dock_bounds.y2)/2};
 pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);pump(2);
 assert(g_touch_recording&&lv_obj_get_parent(g_record_button)==g_root);
 lv_indev_read(touch);assert(!fixture_record_cancels);
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);pump(5);
 assert(!g_touch_recording&&fixture_record_stops==1&&!fixture_record_cancels&&lv_obj_get_parent(g_record_button)==g_home_capture);
 lv_obj_get_coords(g_record_button,&dock_bounds);pointer=(lv_point_t){(dock_bounds.x1+dock_bounds.x2)/2,(dock_bounds.y1+dock_bounds.y2)/2};
 pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);pump(2);assert(g_touch_recording);
 pointer.y-=buffer_ui_dp(48);lv_indev_read(touch);pump(2);assert(!g_touch_recording&&fixture_record_cancels==1);
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);pump(5);assert(fixture_record_stops==1);
 /* Desktop cards are independent, stable-ID objects, not a paged preview. */
 lv_obj_update_layout(g_root);buffer_ui_refresh(NULL);
 assert(!strcmp(g_deck_tiles[0].id,"card-0")&&!strcmp(g_deck_tiles[1].id,"card-1"));
 lv_obj_t *stable=g_deck_tiles[0].root;
 struct buffer_card_s swap=fixture.cards[0];fixture.cards[0]=fixture.cards[1];fixture.cards[1]=swap;
 buffer_ui_refresh(NULL);assert(!strcmp(g_deck_tiles[0].id,"card-0")&&g_deck_tiles[0].root==stable);
 lv_obj_send_event(stable,LV_EVENT_CLICKED,NULL);pump(5);
 assert(buffer_ui_shell_is_open("record-detail")&&!strcmp(g_detail_id,"card-0"));
 assert(buffer_ui_shell_open("record-detail","missing-card")==-ENOENT);
 assert(buffer_ui_shell_is_open("record-detail")&&!strcmp(g_detail_id,"card-0"));
 assert(lv_obj_has_flag(g_pages,LV_OBJ_FLAG_HIDDEN));
 /* Shared capture stays usable above an app and preserves follow-up identity. */
 assert(lv_obj_get_parent(g_record_button)==g_root);
 lv_obj_send_event(g_record_button,LV_EVENT_PRESSED,NULL);assert(!strcmp(fixture_record_target,"card-0"));
 lv_obj_send_event(g_record_button,LV_EVENT_RELEASED,NULL);
 assert(buffer_ui_shell_back());assert(!lv_obj_has_flag(g_pages,LV_OBJ_FLAG_HIDDEN));
 assert(!buffer_ui_shell_open("control-center",NULL));assert(__atomic_load_n(&g_menu_open,__ATOMIC_ACQUIRE));
 assert(buffer_ui_shell_open("record-detail","missing-card")==-ENOENT&&buffer_ui_shell_is_open("control-center"));
 assert(!buffer_ui_shell_open("record-detail","card-1"));assert(!strcmp(g_detail_id,"card-1"));
 assert(!buffer_ui_shell_is_open("control-center")&&!__atomic_load_n(&g_menu_open,__ATOMIC_ACQUIRE));
 assert(buffer_ui_shell_back());
 fixture_timer_present=true;buffer_ui_refresh(NULL);pump(5);
 assert(!lv_obj_has_flag(g_activity_views[0].root,LV_OBJ_FLAG_HIDDEN));
 assert(!strcmp(lv_label_get_text(g_activity_views[0].text),"00:45"));
 fixture_timer_recipe=true;buffer_ui_refresh(NULL);pump(5);assert(lv_obj_has_flag(g_activity_views[0].root,LV_OBJ_FLAG_HIDDEN));
 fixture_timer_recipe=false;__atomic_store_n(&fixture_timer_state,BUFFER_ASSISTANT_FIRED,__ATOMIC_RELEASE);
 buffer_ui_refresh(NULL);pump(5);assert(lv_obj_has_flag(g_activity_views[0].root,LV_OBJ_FLAG_HIDDEN));
 __atomic_store_n(&fixture_timer_state,BUFFER_ASSISTANT_RUNNING,__ATOMIC_RELEASE);buffer_ui_refresh(NULL);pump(5);
 buffer_ui_shell_show("control-center");pump(5);assert(buffer_ui_shell_is_open("control-center"));
 assert(!strcmp(lv_label_get_text(g_notification_views[0].text),lv_label_get_text(g_activity_views[0].text)));
 lv_obj_send_event(g_notification_views[0].root,LV_EVENT_CLICKED,NULL);
 assert(fixture_timer_opens==1&&!strcmp(fixture_opened_timer,"timer-stable"));
 buffer_ui_shell_home();assert(fixture_timer_present);fixture_timer_present=false;buffer_ui_refresh(NULL);
 /* Exercise the real menu back handler during its fade/slide animation. */
 buffer_ui_motion_policy(false,false);
 for(unsigned n=0;n<12;n++){
  pull_center();pump(2);
  assert(!lv_obj_has_flag(g_mode_menu,LV_OBJ_FLAG_HIDDEN));
  close_center();pump(2);
  assert(lv_obj_has_flag(g_mode_menu,LV_OBJ_FLAG_HIDDEN));
  pump(40);
 }
 buffer_ui_motion_policy(true,false);
 sem_init(&io_entered,0,0);sem_init(&io_release,0,0);
 assert(!buffer_ui_work_submit(blocked_storage,malloc(1)));sem_wait(&io_entered);
 lv_obj_send_event(g_mode_options[1],LV_EVENT_CLICKED,NULL);buffer_ui_refresh(NULL);
 assert(g_mode_pending==2&&lv_obj_has_state(g_mode_options[1],LV_STATE_DISABLED));
 assert(!strcmp(buffer_ui_label_source_text(lv_obj_get_child(g_mode_options[1],0)),"切换中"));
 /* The production worker is blocked, but layout/refresh and input keep ticking. */
 pump(80);buffer_ui_refresh(NULL);if(lv_obj_is_visible(g_record_button))inside(g_record_button,g_root);
 sem_post(&io_release);
 for(unsigned wait=0;wait<1000&&!__atomic_load_n(&io_done,__ATOMIC_ACQUIRE);wait++)usleep(1000);
 assert(io_done);sem_destroy(&io_entered);sem_destroy(&io_release);
 for(unsigned wait=0;wait<1000&&g_mode_pending;wait++){pump(1);buffer_ui_refresh(NULL);usleep(1000);}
 assert(!g_mode_pending&&!g_mode_failed&&lv_obj_has_state(g_mode_options[1],LV_STATE_CHECKED));
 buffer_focus_request_mode("normal");buffer_ui_refresh(NULL);
 fixture.recording=true;g_touch_recording=true;g_record_timer_started=false;
 fixture_audio_phase=BUFFER_AUDIO_PREPARING;buffer_ui_refresh(NULL);
 assert(!strcmp(lv_label_get_text(g_record_label),"准备录音…")&&!g_record_timer_started);
 fixture_audio_phase=BUFFER_AUDIO_CAPTURING;buffer_ui_refresh(NULL);
 assert(g_record_timer_started&&strstr(lv_label_get_text(g_record_label),"我在听"));
 fixture_audio_phase=BUFFER_AUDIO_SAVING;buffer_ui_refresh(NULL);
 assert(!strcmp(buffer_ui_label_source_text(g_record_label),"正在保存录音…"));
 g_touch_recording=false;buffer_ui_refresh(NULL);
 assert(!lv_obj_has_flag(g_record_label,LV_OBJ_FLAG_HIDDEN));
 assert(lv_obj_has_state(g_record_button,LV_STATE_DISABLED));
 assert(!strcmp(buffer_ui_label_source_text(g_record_label),"正在保存录音…"));
 fixture.recording=false;fixture_audio_phase=BUFFER_AUDIO_IDLE;buffer_ui_refresh(NULL);
 assert(!lv_obj_has_state(g_record_button,LV_STATE_DISABLED));
 const int sizes[][2]={{320,240},{240,320},{240,240},{320,480},{480,320},{480,800},{800,480},{1280,720},{200,240}};
 for(unsigned n=0;n<sizeof(sizes)/sizeof(sizes[0]);n++){
  lv_display_set_resolution(d,sizes[n][0],sizes[n][1]);pump(80);buffer_ui_refresh(NULL);pump(10);lv_obj_update_layout(g_root);
  buffer_pages_show(0);pump(80);assert(buffer_ui_desktop_reveal(g_desktop,"home"));pump(5);screenshot("buffer",sizes[n][0],sizes[n][1]);
  assert(lv_obj_get_child_count(g_header)==4);
  for(uint32_t i=0;i<lv_obj_get_child_count(g_header);i++)assert(!lv_obj_check_type(lv_obj_get_child(g_header,i),&lv_button_class));
  if(!lv_obj_has_flag(g_clock_label,LV_OBJ_FLAG_HIDDEN))inside(g_clock_label,g_header);
  if(!lv_obj_has_flag(g_connection_label,LV_OBJ_FLAG_HIDDEN))inside(g_connection_label,g_header);
  assert(!lv_obj_has_flag(g_desktop_date,LV_OBJ_FLAG_HIDDEN));
  if(lv_obj_get_y(g_desktop_date)+lv_obj_get_height(g_desktop_date)>lv_obj_get_height(g_clock_tile))fprintf(stderr,"clock geometry: tile=%dx%d time=%dx%d date=%dx%d fonts=%d/%d scale=%u\n",(int)lv_obj_get_width(g_clock_tile),(int)lv_obj_get_height(g_clock_tile),(int)lv_obj_get_width(g_desktop_time),(int)lv_obj_get_height(g_desktop_time),(int)lv_obj_get_width(g_desktop_date),(int)lv_obj_get_height(g_desktop_date),lv_font_get_line_height(lv_obj_get_style_text_font(g_desktop_time,0)),lv_font_get_line_height(lv_obj_get_style_text_font(g_desktop_date,0)),buffer_ui_font_scale());
  inside(g_desktop_date,g_clock_tile);
  assert(strstr(lv_label_get_text(g_desktop_date),"周")||strstr(lv_label_get_text(g_desktop_date),"校时"));
  assert(!strcmp(buffer_ui_launcher_id(lv_obj_get_parent(g_page_switch)),"app.assistant"));
  bool horizontal=sizes[n][0]>sizes[n][1];
  lv_area_t viewport_bounds,page_bounds,home_bounds;
  lv_obj_get_coords(g_body,&viewport_bounds);lv_obj_get_coords(g_buffer_page,&page_bounds);lv_obj_get_coords(g_home_anchor,&home_bounds);
  assert(viewport_bounds.x1==page_bounds.x1&&viewport_bounds.x2==page_bounds.x2);
  assert(viewport_bounds.y1==page_bounds.y1&&viewport_bounds.y2==page_bounds.y2);
  assert(home_bounds.x1==viewport_bounds.x1+buffer_ui_dp(10));
  assert(home_bounds.y1==viewport_bounds.y1+buffer_ui_dp(10));
  assert(lv_obj_get_scroll_dir(g_body)==(horizontal?LV_DIR_HOR:LV_DIR_VER));
  assert(lv_obj_get_scroll_snap_x(g_body)==(horizontal?LV_SCROLL_SNAP_START:LV_SCROLL_SNAP_NONE));
  assert(lv_obj_get_scroll_snap_y(g_body)==(horizontal?LV_SCROLL_SNAP_NONE:LV_SCROLL_SNAP_START));
  assert(lv_obj_get_height(g_deck_tiles[0].root)>0);
  lv_obj_send_event(g_deck_tiles[0].root,LV_EVENT_CLICKED,NULL);pump(5);
  assert(buffer_ui_shell_is_open("record-detail"));inside(g_card_more_panel,g_root);
  screenshot("sparks",sizes[n][0],sizes[n][1]);
  assert(lv_obj_get_y(g_card_more_panel)>=lv_obj_get_height(g_header));
  pull_center();assert(buffer_ui_shell_is_open("record-detail"));edge_touch_back(g_root,true);
  assert(!buffer_ui_shell_is_open("control-center")&&buffer_ui_shell_is_open("record-detail"));
  assert(lv_obj_get_height(g_detail_reader)>0);edge_touch_back(g_root,false);assert(!buffer_ui_shell_is_open("record-detail"));pump(5);
  assert(!buffer_ui_shell_open("settings",NULL));pump(5);inside(g_settings,g_root);inside(g_settings_body,g_settings);
  assert(lv_obj_get_height(g_settings_body)>0);lv_obj_scroll_to_y(g_settings_body,0,LV_ANIM_OFF);pump(3);screenshot("settings",sizes[n][0],sizes[n][1]);
  lv_obj_scroll_to_view_recursive(g_font_slider,LV_ANIM_OFF);pump(3);screenshot("settings-type",sizes[n][0],sizes[n][1]);
  edge_touch_back(g_root,true);assert(!buffer_ui_shell_is_open("settings"));

  fixture_timer_present=true;buffer_ui_refresh(NULL);buffer_ui_shell_show("control-center");pump(20);
  lv_obj_scroll_to_view_recursive(g_menu_right,LV_ANIM_OFF);pump(5);inside(g_menu_right,g_mode_menu);inside(g_notification_list,g_menu_right);
  assert(lv_obj_get_height(g_notification_list)>0);
  screenshot("notifications",sizes[n][0],sizes[n][1]);
  edge_touch_back(g_root,true);assert(!buffer_ui_shell_is_open("control-center"));fixture_timer_present=false;buffer_ui_refresh(NULL);pump(5);
  fixture_timer_present=true;
  struct buffer_notification health_notice;assert(!buffer_notification_build(&health_notice,"fixture-health","fixture","健康","步数趋势减少\n你的步数趋势相较于七天前减少了1356步。"));
  health_notice.desktop=false;assert(!buffer_notification_publish(&health_notice,buffer_ui_now_ms()));buffer_ui_refresh(NULL);
  pull_center();pump(80);screenshot("menu",sizes[n][0],sizes[n][1]);
  buffer_notification_remove("fixture-health");fixture_timer_present=false;
  assert(lv_obj_get_style_pad_row(g_menu_modes,0)==buffer_ui_dp(3));
  for(unsigned mode=1;mode<4;mode++){
   lv_area_t previous_mode,current_mode;lv_obj_get_coords(g_mode_options[mode-1],&previous_mode);lv_obj_get_coords(g_mode_options[mode],&current_mode);
   assert(current_mode.y1-previous_mode.y2-1==buffer_ui_dp(3));
  }
  for(unsigned mode=0;mode<4;mode++){
   lv_obj_send_event(g_mode_options[mode],LV_EVENT_PRESSED,NULL);pump(40);
   lv_obj_send_event(g_mode_options[mode],LV_EVENT_RELEASED,NULL);pump(40);
   assert(lv_obj_get_style_radius(g_mode_options[mode],0)==buffer_ui_dp(3));
  }
  close_center();pump(80);
  inside(g_header,g_root);
  inside(g_body,g_buffer_page);if(lv_obj_is_visible(g_record_button))inside(g_record_button,g_root);
  assert(lv_obj_get_height(g_record_button)>=buffer_ui_dp(48));
  inside(g_mic,g_record_button);
  fixture.recording=true;g_touch_recording=true;fixture_audio_phase=BUFFER_AUDIO_CAPTURING;
  fixture_transcript="帮我定一个十分钟计时器。";buffer_ui_shell_capture_expanded(true);
  buffer_ui_refresh(NULL);pump(5);inside(g_live_panel,g_root);inside(g_record_label,g_record_button);
  screenshot("assistant-listening",sizes[n][0],sizes[n][1]);
  fixture_transcript="";buffer_ui_shell_capture_expanded(false);
  fixture.recording=false;g_touch_recording=false;fixture_audio_phase=BUFFER_AUDIO_IDLE;buffer_ui_refresh(NULL);
  lv_obj_remove_flag(g_card_more_panel,LV_OBJ_FLAG_HIDDEN);buffer_ui_refresh(NULL);pump(10);
  inside(g_card_more_panel,g_root);
  assert(lv_obj_get_height(g_detail_reader)>0);
  lv_area_t nav_before,nav_after;lv_obj_get_coords(lv_obj_get_child(g_card_more_panel,0),&nav_before);
  lv_obj_scroll_to_y(g_detail_reader,100,LV_ANIM_OFF);pump(2);
  lv_obj_get_coords(lv_obj_get_child(g_card_more_panel,0),&nav_after);assert(nav_before.y1==nav_after.y1);
  lv_obj_add_flag(g_card_more_panel,LV_OBJ_FLAG_HIDDEN);
  strcpy(fixture.mode,"focus");
  memset(fixture.focus_title,'F',160);fixture.focus_title[160]=0;
  buffer_ui_refresh(NULL);pump(5);
  if(!lv_obj_has_flag(g_focus_caption,LV_OBJ_FLAG_HIDDEN))inside(g_focus_caption,g_focus_panel);
  inside(g_focus_time,g_focus_panel);
  lv_area_t caption,timer;lv_obj_get_coords(g_focus_caption,&caption);lv_obj_get_coords(g_focus_time,&timer);if(!lv_obj_has_flag(g_focus_caption,LV_OBJ_FLAG_HIDDEN))assert(caption.y2<timer.y1);
  strcpy(fixture.mode,"normal");buffer_ui_refresh(NULL);
  /* Unchanged polling must not redraw the home every half second. */
  pump(80);unsigned before=flushes;buffer_ui_refresh(NULL);pump(2);assert(flushes==before);
  printf("home %dx%d: header/record/detail bounds and idle redraw verified\n",sizes[n][0],sizes[n][1]);
 }
 /* Drive the real controls, stop/drain the storage worker, then recreate the
  * entire UI. Restored settings must reach both layout and control states. */
 fixture_timer_present=fixture_recipe_present=true;buffer_ui_shell_home();buffer_ui_refresh(NULL);pump(10);
 unsigned timer_slot=BUFFER_NOTIFICATION_LIMIT,recipe_slot=BUFFER_NOTIFICATION_LIMIT;
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++){
  if(!strcmp(g_activity_views[i].key,"timer:timer-stable"))timer_slot=i;
  if(!strcmp(g_activity_views[i].key,"surface:recipe-stable"))recipe_slot=i;
 }
 assert(timer_slot<BUFFER_NOTIFICATION_LIMIT&&recipe_slot<BUFFER_NOTIFICATION_LIMIT);
 struct buffer_notification_view *timer_view=&g_activity_views[timer_slot];
 if(!g_notification_private_previews){
  assert(lv_obj_has_flag(g_activity_image_frames[recipe_slot],LV_OBJ_FLAG_HIDDEN));
  lv_obj_send_event(g_private_option,LV_EVENT_CLICKED,NULL);buffer_ui_refresh(NULL);pump(5);
 }
 assert(lv_image_get_src(g_activity_images[recipe_slot])==buffer_ui_recipe_artwork("fixture-recipe-photo"));
 fixture_recipe_result_visible=false;buffer_ui_refresh(NULL);pump(5);
 for(unsigned i=0;i<BUFFER_NOTIFICATION_LIMIT;i++)assert(lv_obj_has_flag(g_activity_views[i].root,LV_OBJ_FLAG_HIDDEN)||strcmp(g_activity_views[i].key,"surface:recipe-stable"));
 fixture_recipe_result_visible=true;buffer_ui_refresh(NULL);pump(5);
 assert(!buffer_ui_recipe_artwork("另一份菜谱"));
 lv_obj_send_event(timer_view->buttons[0],LV_EVENT_CLICKED,NULL);
 for(unsigned n=0;n<1000&&__atomic_load_n(&g_timer_command_pending,__ATOMIC_ACQUIRE);n++)usleep(1000);
 assert(__atomic_load_n(&fixture_timer_operation,__ATOMIC_ACQUIRE)==BUFFER_ASSISTANT_TIMER_PAUSE&&!strcmp(fixture_timer_target,"timer-stable"));
 buffer_ui_refresh(NULL);pump(5);assert(!strcmp(timer_view->bound[0].id,"resume"));
 lv_obj_send_event(timer_view->buttons[0],LV_EVENT_CLICKED,NULL);
 for(unsigned n=0;n<1000&&__atomic_load_n(&g_timer_command_pending,__ATOMIC_ACQUIRE);n++)usleep(1000);
 assert(__atomic_load_n(&fixture_timer_operation,__ATOMIC_ACQUIRE)==BUFFER_ASSISTANT_TIMER_RESUME);
 buffer_ui_refresh(NULL);lv_obj_send_event(timer_view->buttons[1],LV_EVENT_CLICKED,NULL);
 for(unsigned n=0;n<1000&&__atomic_load_n(&g_timer_command_pending,__ATOMIC_ACQUIRE);n++)usleep(1000);
 assert(__atomic_load_n(&fixture_timer_operation,__ATOMIC_ACQUIRE)==BUFFER_ASSISTANT_TIMER_RESTART&&!strcmp(fixture_timer_target,"timer-stable"));
 for(unsigned n=0;n<10;n++)buffer_ui_refresh(NULL);
 assert(lv_obj_get_child_count(timer_view->buttons[0])==2&&lv_obj_get_child_count(timer_view->buttons[1])==2);
 lv_obj_send_event(g_activity_views[recipe_slot].root,LV_EVENT_CLICKED,NULL);assert(!strcmp(fixture_opened_recipe,"recipe-stable"));
 const int card_sizes[][2]={{800,240},{320,240},{240,320},{200,240}};
 for(unsigned n=0;n<4;n++){
  lv_display_set_resolution(d,card_sizes[n][0],card_sizes[n][1]);pump(30);buffer_ui_refresh(NULL);pump(5);
  inside(timer_view->buttons[0],timer_view->root);inside(timer_view->buttons[1],timer_view->root);
  assert(buffer_ui_desktop_reveal(g_desktop,"home"));pump(5);screenshot("desktop-cards",card_sizes[n][0],card_sizes[n][1]);
 }
 fixture_timer_present=fixture_recipe_present=false;buffer_ui_refresh(NULL);
 for(unsigned i=0;i<4;i++)if(g_font_sizes[i]==150){lv_slider_set_value(g_font_slider,i,LV_ANIM_OFF);lv_obj_send_event(g_font_slider,LV_EVENT_RELEASED,NULL);}
 if(!g_notification_private_previews)lv_obj_send_event(g_private_option,LV_EVENT_CLICKED,NULL);
 buffer_ui_shutdown();char original_default[16];snprintf(original_default,sizeof(original_default),"%u",initial_scale);setenv("BUFFER_UI_FONT_SCALE",original_default,1);assert(!buffer_ui_init(false));
 bool restored=false;
 for(unsigned i=0;i<3000;i++){
  buffer_preferences_refresh();if(buffer_ui_font_scale()==150&&g_notification_private_previews&&lv_obj_has_state(g_private_option,LV_STATE_CHECKED)&&lv_obj_has_state(g_motion_option,LV_STATE_CHECKED)){restored=true;break;}usleep(1000);
 }
 assert(restored);puts("home: font, privacy and motion settings restored after full UI restart");
 buffer_ui_shutdown();lv_indev_delete(touch);lv_display_delete(d);lv_deinit();return 0;
}
