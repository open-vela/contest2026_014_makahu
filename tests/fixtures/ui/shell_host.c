/* Real LVGL: global routing/layers, focus, desktop preservation and teardown. */
#include <assert.h>
#include <stdio.h>
#include <errno.h>
#include "buffer_ui_shell.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_icons.h"
#include "buffer_ui_controls.h"
#include "visual_capture.h"
static unsigned closed[3];
static bool record_active;static unsigned record_cancels,tree_changes;
static void tree_changed(lv_event_t *event){(void)event;tree_changes++;}
static void record_cancel(lv_event_t *event)
{(void)event;if(record_active){record_active=false;record_cancels++;buffer_ui_shell_capture_expanded(false);}}
static unsigned activations,reopens,preparations;
static int prepare(const char *object,void *context)
{(void)context;preparations++;return object&&strcmp(object,"valid")?-ENOENT:0;}
static void activated(bool was_visible,void *context)
{(void)context;activations++;if(was_visible)reopens++;}
static bool local_open;
static uint32_t key=LV_KEY_ENTER;static lv_indev_state_t key_state=LV_INDEV_STATE_RELEASED;
static unsigned clicks[3];
static unsigned prior_key_calls;
static bool prior_key_hook(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;(void)data;prior_key_calls++;return false;}
static lv_point_t pointer;
static lv_indev_state_t pointer_state=LV_INDEV_STATE_RELEASED;
static unsigned prior_pointer_calls;
static bool pointer_has_sample=true;
static bool prior_pointer_hook(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;(void)data;prior_pointer_calls++;return false;}
static void read_pointer(lv_indev_t *input,lv_indev_data_t *data)
{
 if(pointer_state==LV_INDEV_STATE_PRESSED&&pointer_has_sample){
  lv_display_t *display=lv_indev_get_display(input);lv_display_rotation_t rotation=lv_display_get_rotation(display);
  int w=lv_display_get_original_horizontal_resolution(display),h=lv_display_get_original_vertical_resolution(display);
  lv_point_t raw=pointer;
  if(rotation==LV_DISPLAY_ROTATION_90||rotation==LV_DISPLAY_ROTATION_270){raw.x=pointer.y;raw.y=h-pointer.x-1;}
  if(rotation==LV_DISPLAY_ROTATION_180||rotation==LV_DISPLAY_ROTATION_270){raw.x=w-raw.x-1;raw.y=h-raw.y-1;}
  data->point=raw;
 }
 /* Match NuttX: release carries state only, using LVGL's cached raw point. */
 data->state=pointer_state;
}
static void drag(lv_indev_t *input,int x,int y,int dx,int dy)
{
 pointer=(lv_point_t){x,y};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 for(int i=1;i<=4;i++){pointer=(lv_point_t){x+dx*i/4,y+dy*i/4};lv_indev_read(input);}
 pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);
}
static void read_key(lv_indev_t *input,lv_indev_data_t *data){(void)input;data->key=key;data->state=key_state;}
static void clicked(lv_event_t *event){clicks[(uintptr_t)lv_event_get_user_data(event)]++;}
static unsigned sparse_event_checks;
static void remove_current_and_refresh(lv_event_t *event)
{
 lv_obj_t *object=lv_event_get_current_target(event);
 lv_obj_remove_event_cb(object,remove_current_and_refresh);
 bool hole=false;
 for(uint32_t i=0;i<lv_obj_get_event_count(object);i++)if(!lv_obj_get_event_dsc(object,i))hole=true;
 assert(hole); /* Native LVGL leaves a tombstone until this dispatch returns. */
 lv_group_remove_obj(object);
 buffer_ui_shell_capture_expanded(true);buffer_ui_shell_capture_expanded(false);
 assert(lv_obj_get_group(object));sparse_event_checks++;
}
static void press_key(lv_indev_t *input,uint32_t value)
{key=value;key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);key_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);}
static bool local_back(void *unused){(void)unused;if(!local_open)return false;local_open=false;return true;}
static void dismissed(void *context){closed[(uintptr_t)context]++;}
static void pump(void){for(unsigned i=0;i<20;i++){lv_tick_inc(16);lv_timer_handler();lv_obj_update_layout(lv_screen_active());}}
static void count_click(lv_event_t *event){(*(unsigned *)lv_event_get_user_data(event))++;}
static void count_switch(bool checked,void *context){(void)checked;(*(unsigned *)context)++;}
static void space_input(lv_indev_t *input,lv_obj_t *parent)
{
 unsigned clicked_count=0,switched=0;
 lv_obj_t *button=lv_button_create(parent);lv_obj_set_size(button,80,48);
 lv_obj_add_event_cb(button,count_click,LV_EVENT_CLICKED,&clicked_count);pump();lv_group_focus_obj(button);
 unsigned previous=prior_key_calls;
 key=' ';key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 assert(lv_obj_has_state(button,LV_STATE_PRESSED)&&!clicked_count);
 for(unsigned i=0;i<8;i++){lv_tick_inc(200);lv_indev_read(input);assert(!clicked_count);}
 key_state=LV_INDEV_STATE_RELEASED;key=0;lv_indev_read(input);
 assert(clicked_count==1&&!lv_obj_has_state(button,LV_STATE_PRESSED)&&prior_key_calls>previous);
 lv_obj_add_state(button,LV_STATE_DISABLED);press_key(input,' ');assert(clicked_count==1);lv_obj_remove_state(button,LV_STATE_DISABLED);
 lv_obj_t *tile=lv_obj_create(parent);lv_obj_set_size(tile,80,48);
 lv_obj_add_event_cb(tile,count_click,LV_EVENT_CLICKED,&clicked_count);pump();lv_group_focus_obj(tile);
 press_key(input,' ');assert(clicked_count==2);
 lv_obj_t *row=buffer_ui_switch_create(parent,"Space activates once",false,count_switch,&switched);
 pump();lv_group_focus_obj(row);key=' ';key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
 for(unsigned i=0;i<8;i++){lv_tick_inc(200);lv_indev_read(input);assert(!switched);}
 key_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(switched==1&&lv_obj_has_state(row,LV_STATE_CHECKED));
#if LV_USE_TEXTAREA
 lv_obj_t *text=lv_textarea_create(parent);lv_obj_set_size(text,160,48);lv_textarea_set_text(text,"");
 pump();lv_group_focus_obj(text);press_key(input,' ');assert(!strcmp(lv_textarea_get_text(text)," "));lv_obj_delete(text);
#endif
 /* Device deletion removes the normalizer registration, even mid-contact. */
 lv_indev_t *temporary=lv_indev_create();lv_indev_set_type(temporary,LV_INDEV_TYPE_KEYPAD);lv_indev_set_read_cb(temporary,read_key);
 lv_indev_set_read_preprocess_cb(temporary,prior_key_hook);buffer_ui_shell_focus_refresh();
 assert(lv_indev_get_read_preprocess_cb(temporary)!=prior_key_hook);lv_indev_delete(temporary);
 lv_obj_delete(row);lv_obj_delete(tile);lv_obj_delete(button);pump();
}
static lv_obj_t *content(lv_obj_t *parent)
{lv_obj_t *obj=lv_obj_create(parent);lv_obj_remove_style_all(obj);return obj;}
int main(void)
{
  lv_init();buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
  lv_display_t *display=lv_display_create(800,480);
  static uint32_t pixels[1280*16];lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
  lv_obj_t *root=content(lv_screen_active());lv_obj_set_size(root,LV_PCT(100),LV_PCT(100));
  lv_obj_t *desktop=content(root);lv_obj_set_size(desktop,LV_PCT(100),LV_PCT(100));
  lv_obj_t *tiles=content(desktop);lv_obj_set_size(tiles,1600,200);
  lv_obj_set_scroll_dir(desktop,LV_DIR_HOR);lv_obj_scroll_to_x(desktop,100,LV_ANIM_OFF);
  lv_group_t *group=lv_group_create();lv_group_set_default(group);
  lv_obj_t *entry=lv_button_create(desktop);lv_obj_set_size(entry,48,48);
  lv_group_add_obj(group,entry);lv_group_focus_obj(entry);
  lv_obj_add_event_cb(entry,clicked,LV_EVENT_CLICKED,(void *)0);
  lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_KEYPAD);lv_indev_set_read_cb(input,read_key);lv_indev_set_group(input,group);
  lv_indev_set_read_preprocess_cb(input,prior_key_hook);
  buffer_ui_shell_init(root,desktop);
  lv_obj_t *first=content(desktop),*second=content(desktop),*control=content(desktop);
  char title[]="First application";char action[]="open";const char *actions[]={action,"back"};
  struct buffer_ui_surface_declaration declaration={.id="first",.title=title,.entry_route="first",.kind=BUFFER_UI_APP,.min_width_dp=300,.min_height_dp=128,.actions=actions,.action_count=2,.closed=dismissed,.prepare_open=prepare,.activated=activated};
  assert(buffer_ui_shell_declare(&declaration,first));memset(title,'x',sizeof(title)-1);action[0]='x';
  struct buffer_ui_surface_info info;assert(buffer_ui_shell_info("first",&info)&&!strcmp(info.title,"First application")&&!strcmp(info.actions[0],"open"));
  struct buffer_ui_surface_declaration invalid_mode={.id="invalid-mode",.title="Invalid",.entry_route="invalid-mode",.kind=BUFFER_UI_APP,.capture_mode=(enum buffer_ui_capture_mode)-1};
  assert(!buffer_ui_shell_declare(&invalid_mode,second));invalid_mode.capture_mode=(enum buffer_ui_capture_mode)3;assert(!buffer_ui_shell_declare(&invalid_mode,second));
  assert(buffer_ui_shell_mount("second",BUFFER_UI_APP,second,dismissed,(void *)1));
  assert(buffer_ui_shell_mount("control",BUFFER_UI_SYSTEM_OVERLAY,control,dismissed,(void *)2));
  lv_obj_t *first_control=lv_button_create(first);lv_group_add_obj(group,first_control);
  lv_obj_add_event_cb(first_control,clicked,LV_EVENT_CLICKED,(void *)1);
  lv_obj_t *overlay_control=lv_button_create(control);lv_obj_add_event_cb(overlay_control,clicked,LV_EVENT_CLICKED,(void *)2);
  assert(!buffer_ui_shell_mount("first",BUFFER_UI_APP,second,NULL,NULL));
  assert(!buffer_ui_shell_mount("alias",BUFFER_UI_APP,first,NULL,NULL));
  assert(!buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="duplicate-route",.entry_route="first",.title="duplicate",.kind=BUFFER_UI_APP},second));
  assert(lv_obj_get_parent(first)==root);
  pump();buffer_ui_shell_show("first");pump();
  assert(buffer_ui_shell_is_open("first")&&buffer_ui_shell_has_app());
  assert(activations==1&&preparations==1&&!reopens);
  assert(buffer_ui_shell_open("missing",NULL)==-ENOENT);
  assert(buffer_ui_shell_open("control","unsupported-object")==-EINVAL);
  assert(buffer_ui_shell_open("first","missing-object")==-ENOENT);
  assert(activations==1&&buffer_ui_shell_is_open("first")&&!buffer_ui_shell_is_open("control"));
  lv_obj_set_style_translate_x(first,7,0);
  assert(!buffer_ui_shell_open("first","valid"));assert(activations==2&&reopens==1);
  assert(lv_obj_get_style_translate_x(first,0)==7);lv_obj_set_style_translate_x(first,0,0);
  assert(lv_obj_has_flag(desktop,LV_OBJ_FLAG_HIDDEN));
  assert(lv_obj_get_width(first)==800&&lv_obj_get_height(first)==480);
  assert(lv_indev_get_group(input)==lv_obj_get_group(first_control)&&lv_indev_get_group(input)!=group);
  press_key(input,LV_KEY_ENTER);assert(clicks[1]==1&&!clicks[0]&&!clicks[2]);
  space_input(input,first);lv_group_focus_obj(first_control);
  buffer_ui_shell_on_back("first",local_back,NULL);local_open=true;
  assert(buffer_ui_shell_back()&&!local_open&&buffer_ui_shell_is_open("first"));
  lv_group_focus_obj(first_control);buffer_ui_shell_show("first");
  buffer_ui_shell_show("control");pump();
  assert(buffer_ui_shell_open("first","missing-object")==-ENOENT);
  assert(buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first"));
  assert(buffer_ui_shell_is_open("first")&&buffer_ui_shell_is_open("control"));
  assert(lv_indev_get_group(input)==lv_obj_get_group(overlay_control));
  for(unsigned i=0;i<8;i++){lv_group_focus_next(lv_indev_get_group(input));assert(lv_group_get_focused(lv_indev_get_group(input))==overlay_control);}
  press_key(input,LV_KEY_ENTER);assert(clicks[2]==1&&clicks[1]==1);
  assert(lv_obj_get_width(control)==buffer_ui_dp(416));
  assert(lv_obj_get_x(control)==800-buffer_ui_dp(416));
  assert(buffer_ui_shell_back());assert(closed[2]==1);
  assert(buffer_ui_shell_is_open("first"));
  lv_obj_t *route=content(first),*local_control=lv_button_create(route);lv_obj_add_event_cb(local_control,clicked,LV_EVENT_CLICKED,(void *)1);pump();
  lv_group_focus_obj(local_control);lv_obj_add_flag(route,LV_OBJ_FLAG_HIDDEN);press_key(input,LV_KEY_ENTER);assert(clicks[1]==1);
  buffer_ui_shell_focus_refresh();press_key(input,LV_KEY_ENTER);assert(clicks[1]==2);
  /* A held key cannot finish on a different global layer. */
  key=LV_KEY_ENTER;key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);buffer_ui_shell_show("control");key_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(clicks[2]==1);
  assert(!lv_obj_has_state(first_control,LV_STATE_PRESSED));
  press_key(input,LV_KEY_ESC);assert(!buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first"));
  key=' ';key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);buffer_ui_shell_show("control");
  key_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(clicks[2]==1&&!lv_obj_has_state(first_control,LV_STATE_PRESSED));
  press_key(input,LV_KEY_ESC);assert(!buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first"));
  buffer_ui_shell_show("second");assert(closed[0]==1);
  assert(!buffer_ui_shell_is_open("first")&&buffer_ui_shell_is_open("second"));
  buffer_ui_shell_home();assert(closed[1]==1);
  assert(!buffer_ui_shell_has_app()&&!lv_obj_has_flag(desktop,LV_OBJ_FLAG_HIDDEN));
  assert(!buffer_ui_shell_back());assert(lv_group_get_focused(group)==entry);
  lv_obj_scroll_to_x(desktop,100,LV_ANIM_OFF);int reading=lv_obj_get_scroll_x(desktop);
  buffer_ui_shell_focus_refresh();assert(lv_obj_get_scroll_x(desktop)==reading);
  unsigned before_reveal=clicks[0];press_key(input,LV_KEY_ENTER);pump();assert(clicks[0]==before_reveal&&lv_obj_is_visible(entry));
  press_key(input,LV_KEY_ENTER);assert(clicks[0]==before_reveal+1);
  lv_obj_t *capture=lv_button_create(root);lv_obj_set_height(capture,buffer_ui_dp(48));lv_obj_add_event_cb(capture,record_cancel,LV_EVENT_PRESS_LOST,NULL);
  lv_obj_t *preview=content(root);lv_obj_set_height(preview,buffer_ui_dp(80));lv_obj_set_align(preview,LV_ALIGN_BOTTOM_MID);
  buffer_ui_shell_capture(capture,preview);
  buffer_ui_shell_show("first");buffer_ui_shell_show("control");
  pump();lv_group_focus_obj(overlay_control);unsigned capture_overlay_clicks=clicks[2],overlay_closes=closed[2];
  lv_obj_add_event_cb(root,tree_changed,LV_EVENT_CHILD_CHANGED,NULL);
  record_active=true;buffer_ui_shell_capture_expanded(true);pump();
  unsigned settled_tree=tree_changes;for(unsigned i=0;i<10;i++){buffer_ui_shell_layout();pump();}assert(tree_changes==settled_tree);
  assert(buffer_ui_shell_is_open("first")&&buffer_ui_shell_is_open("control"));
  assert(lv_group_get_focused(lv_indev_get_group(input))==capture);
  press_key(input,LV_KEY_ENTER);assert(clicks[2]==capture_overlay_clicks);
  press_key(input,LV_KEY_ESC);pump();assert(!record_active&&record_cancels==1&&closed[2]==overlay_closes);
  assert(buffer_ui_shell_is_open("control")&&lv_group_get_focused(lv_indev_get_group(input))==overlay_control);
  lv_obj_t *temporary=lv_button_create(control);pump();lv_group_focus_obj(temporary);
  record_active=true;buffer_ui_shell_capture_expanded(true);pump();lv_obj_delete(temporary);
  assert(buffer_ui_shell_back());pump();assert(!record_active&&record_cancels==2&&buffer_ui_shell_is_open("control"));
  lv_display_set_resolution(display,240,320);pump();
  assert(lv_obj_get_width(first)==240&&lv_obj_get_height(first)==320-buffer_ui_dp(48));
  assert(lv_obj_get_x(control)==0&&lv_obj_get_width(control)==240);
  assert(buffer_ui_shell_info("first",&info)&&info.constrained&&info.width_px==240);
  buffer_ui_shell_capture_mode("first",(enum buffer_ui_capture_mode)-1);buffer_ui_shell_capture_mode("first",(enum buffer_ui_capture_mode)3);pump();
  assert(lv_obj_get_height(first)==320-buffer_ui_dp(48));
  assert(lv_obj_get_y(capture)==320-buffer_ui_dp(48));
  assert(lv_obj_get_y(preview)==320-buffer_ui_dp(48)-buffer_ui_dp(80)-buffer_ui_dp(4));
  lv_obj_delete(preview);lv_obj_delete(capture);pump();
  assert(lv_obj_get_height(first)==320&&lv_obj_get_style_pad_bottom(desktop,0)==0);
  buffer_ui_shell_home();
  lv_obj_t *status=content(root);lv_obj_set_height(status,buffer_ui_dp(24));
  assert(buffer_ui_shell_statusbar(status,"control"));pump();
  assert(lv_obj_get_style_pad_top(desktop,0)==buffer_ui_dp(24));
  buffer_ui_shell_show("first");pump();
  assert(lv_obj_get_y(first)==buffer_ui_dp(24)&&lv_obj_get_height(first)==320-buffer_ui_dp(24));
  lv_indev_t *touch=lv_indev_create();lv_indev_set_type(touch,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(touch,read_pointer);
  lv_indev_set_read_preprocess_cb(touch,prior_pointer_hook);
  drag(touch,100,4,0,buffer_ui_dp(12));assert(!buffer_ui_shell_is_open("control"));
  drag(touch,100,4,buffer_ui_dp(60),buffer_ui_dp(40));assert(!buffer_ui_shell_is_open("control"));
  drag(touch,100,buffer_ui_dp(80),0,buffer_ui_dp(48));assert(!buffer_ui_shell_is_open("control"));
  unsigned overlay_clicks=clicks[2];drag(touch,100,4,0,buffer_ui_dp(48));pump();
  assert(buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first")&&clicks[2]==overlay_clicks);
  drag(touch,120,315,0,-buffer_ui_dp(48));assert(!buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first"));
  lv_group_focus_obj(status);press_key(input,LV_KEY_ENTER);
  assert(buffer_ui_shell_is_open("control"));assert(buffer_ui_shell_back());
  lv_obj_set_pos(first_control,0,70);lv_obj_set_size(first_control,240,140);pump();
  unsigned child_clicks=clicks[1];local_open=true;
  drag(touch,4,120,buffer_ui_dp(60),0);pump();
  assert(!local_open&&buffer_ui_shell_is_open("first")&&clicks[1]==child_clicks);
  /* Vertical scrolling and short edge taps keep the current route. */
  drag(touch,4,120,0,0);pump();assert(buffer_ui_shell_is_open("first")&&clicks[1]==child_clicks+1);
  drag(touch,4,120,0,buffer_ui_dp(40));pump();assert(buffer_ui_shell_is_open("first"));
  buffer_ui_shell_show("control");lv_obj_set_pos(overlay_control,0,70);lv_obj_set_size(overlay_control,240,140);pump();
  overlay_clicks=clicks[2];drag(touch,235,120,-buffer_ui_dp(60),0);pump();
  assert(!buffer_ui_shell_is_open("control")&&buffer_ui_shell_is_open("first")&&clicks[2]==overlay_clicks);
  child_clicks=clicks[1];drag(touch,235,120,-buffer_ui_dp(60),0);pump();
  assert(!buffer_ui_shell_has_app()&&clicks[1]==child_clicks);
  buffer_ui_shell_show("first");pump();
  lv_obj_t *sparse=content(first);lv_obj_add_flag(sparse,LV_OBJ_FLAG_CLICKABLE);
  lv_label_create(sparse);lv_obj_add_event_cb(sparse,clicked,LV_EVENT_CLICKED,(void *)1);
  for(unsigned i=0;i<20;i++){
   lv_obj_add_event_cb(sparse,remove_current_and_refresh,LV_EVENT_RELEASED,NULL);
   lv_obj_send_event(sparse,LV_EVENT_RELEASED,NULL);
  }
  assert(sparse_event_checks==20);lv_obj_delete(sparse);
  /* Actual pointer samples drive ENTRY/ACTIVE/rewind/CANCEL/COMMIT. The
   * feedback stays above every app, never takes focus/input, and settles. */
  const int feedback_sizes[][2]={{320,240},{240,320},{800,480}};
  buffer_ui_motion_policy(false,false);
  for(unsigned n=0;n<3;n++){
   int w=feedback_sizes[n][0],h=feedback_sizes[n][1],y=h/2;
   lv_display_set_resolution(display,w,h);buffer_ui_shell_show("first");pump();
   pointer=(lv_point_t){4,y};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
   pointer.x=4+buffer_ui_dp(16);lv_indev_read(touch);pump();
   lv_obj_t *hint=lv_obj_get_child(root,-1);
   assert(lv_obj_is_visible(hint)&&!lv_obj_has_flag(hint,LV_OBJ_FLAG_CLICKABLE));
   assert(lv_obj_get_child_count(hint)==1&&lv_obj_get_width(hint)>0);
   assert(lv_image_get_src(lv_obj_get_child(hint,0))==buffer_ui_icon_source(&buffer_previous_icon,buffer_ui_dp(24)));
   lv_area_t bounds;lv_obj_get_coords(hint,&bounds);assert(bounds.x1>=0&&bounds.y1>=0&&bounds.x2<w&&bounds.y2<h);
   screenshot("back-entry",w,h);
   pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);
   assert(buffer_ui_shell_is_open("first"));pump();pump();pump();assert(!lv_obj_is_visible(hint));
   pointer=(lv_point_t){4,y};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
   pointer.x=4+buffer_ui_dp(60);lv_indev_read(touch);pointer_has_sample=false;pump();pump();pointer_has_sample=true;
   assert(lv_obj_is_visible(hint)&&lv_obj_get_width(hint)>=buffer_ui_dp(45));screenshot("back-active",w,h);
   pointer.x=4+buffer_ui_dp(10);lv_indev_read(touch);pump();
   pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);pump();pump();pump();
   assert(buffer_ui_shell_is_open("first")&&!lv_obj_is_visible(hint));
   pointer=(lv_point_t){w-5,y};pointer_state=LV_INDEV_STATE_PRESSED;lv_indev_read(touch);
   pointer.x-=buffer_ui_dp(60);lv_indev_read(touch);pump();pump();
   assert(lv_obj_is_visible(hint));
   assert(lv_image_get_src(lv_obj_get_child(hint,0))==buffer_ui_icon_source(&buffer_previous_icon,buffer_ui_dp(24)));
   lv_obj_get_coords(hint,&bounds);assert(bounds.x1>=0&&bounds.y1>=0&&bounds.x2<w&&bounds.y2<h);
   screenshot("back-right",w,h);
   pointer_state=LV_INDEV_STATE_RELEASED;lv_indev_read(touch);
   assert(!buffer_ui_shell_has_app());pump();pump();pump();assert(!lv_obj_is_visible(hint));
  }
  buffer_ui_motion_policy(true,false);lv_display_set_resolution(display,240,320);
  const lv_display_rotation_t rotations[]={LV_DISPLAY_ROTATION_0,LV_DISPLAY_ROTATION_90,LV_DISPLAY_ROTATION_180,LV_DISPLAY_ROTATION_270};
  for(unsigned n=0;n<4;n++){
   lv_display_set_rotation(display,rotations[n]);pump();
   for(unsigned side=0;side<2;side++){
    buffer_ui_shell_show("first");pump();int w=lv_obj_get_width(root),h=lv_obj_get_height(root);
    drag(touch,side?w-3:2,h/2,buffer_ui_dp(side?-60:60),0);pump();assert(!buffer_ui_shell_has_app());
   }
  }
  lv_display_set_rotation(display,LV_DISPLAY_ROTATION_0);pump();
  buffer_ui_shell_show("first");pump();
  assert(prior_pointer_calls>0);lv_obj_delete(status);pump();
  assert(lv_obj_get_style_pad_top(desktop,0)==0&&lv_obj_get_y(first)==0&&lv_obj_get_height(first)==320);
  buffer_ui_shell_home();
  lv_obj_t *a=lv_button_create(second),*b=lv_button_create(second),*c=lv_button_create(second);
  buffer_ui_shell_show("second");pump();lv_group_t *ordered_group=lv_obj_get_group(a);
  lv_group_focus_obj(a);lv_group_set_editing(ordered_group,true);
  lv_obj_move_to_index(c,0);pump();
  assert(lv_group_get_focused(ordered_group)==a&&lv_group_get_editing(ordered_group));
  lv_group_set_editing(ordered_group,false);lv_group_focus_obj(c);
  lv_group_focus_next(ordered_group);assert(lv_group_get_focused(ordered_group)==a);
  lv_group_focus_next(ordered_group);assert(lv_group_get_focused(ordered_group)==b);
  assert(buffer_ui_shell_local_begin("second",b));lv_group_focus_obj(a);
  buffer_ui_shell_local_end("second",NULL);assert(lv_group_get_focused(ordered_group)==b);
  assert(buffer_ui_shell_local_begin("second",c));lv_obj_delete(c);
  buffer_ui_shell_local_end("second",NULL);assert(lv_group_get_focused(ordered_group)!=c);
  buffer_ui_shell_home();
  for(unsigned i=0;i<50;i++){
    buffer_ui_shell_show("first");buffer_ui_shell_show("control");
    assert(buffer_ui_shell_back());assert(buffer_ui_shell_back());
  }
  /* Destroying a mounted app invalidates the routing slot, not a dangling
   * root pointer or stale callback. Reuse the identity on a fresh object. */
  buffer_ui_shell_show("first");lv_obj_delete(first);
  assert(!buffer_ui_shell_is_open("first"));
  first=content(desktop);assert(buffer_ui_shell_mount("first",BUFFER_UI_APP,first,NULL,NULL));
  buffer_ui_shell_show("first");buffer_ui_shell_home();
  assert(lv_indev_get_read_preprocess_cb(touch)!=prior_pointer_hook);
  unsigned outside_clicks=0;lv_obj_t *outside=lv_button_create(lv_screen_active());lv_group_add_obj(group,outside);
  lv_obj_add_event_cb(outside,count_click,LV_EVENT_CLICKED,&outside_clicks);
  lv_obj_scroll_to_view_recursive(entry,LV_ANIM_OFF);lv_group_focus_obj(entry);pump();
  key=' ';key_state=LV_INDEV_STATE_PRESSED;lv_indev_read(input);
  assert(lv_obj_has_state(entry,LV_STATE_PRESSED));
  buffer_ui_shell_shutdown();assert(lv_indev_get_read_preprocess_cb(touch)==prior_pointer_hook);
  assert(lv_indev_get_read_preprocess_cb(input)==prior_key_hook);
  lv_group_focus_obj(outside);key_state=LV_INDEV_STATE_RELEASED;lv_indev_read(input);assert(!outside_clicks);lv_obj_delete(outside);
  lv_indev_delete(touch);lv_obj_delete(root);
  assert(lv_indev_get_group(input)==group);lv_indev_delete(input);
  lv_group_delete(group);buffer_ui_motion_shutdown();lv_display_delete(display);lv_deinit();
  puts("PASS: app/overlay routing, back/Home, focus, resize and repeated teardown");
}
