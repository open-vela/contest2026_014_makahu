/* SPDX-License-Identifier: Apache-2.0
 * App-local settings routes. Shell owns placement, Back and Home. */
#include "buffer_device_settings.h"
static lv_obj_t *g_settings,*g_settings_body,*g_settings_preferences,*g_settings_feedback,*g_settings_confirm;
static lv_obj_t *g_settings_ssid,*g_settings_password,*g_settings_keyboard,*g_settings_reset_copy,*g_settings_connect;
static lv_obj_t *g_settings_reset_buttons[3],*g_settings_commit;
static unsigned g_settings_reset_kind,g_settings_epoch;
static pthread_mutex_t g_settings_lock=PTHREAD_MUTEX_INITIALIZER;
static bool g_settings_busy,g_settings_result_ready;
static int g_settings_result;
static unsigned g_settings_result_epoch;
struct settings_job {char ssid[33],password[65];unsigned kind,epoch;};
static void settings_work(void *context)
{
 struct settings_job *job=context;
 int result=job->kind?buffer_device_reset_request(job->kind):buffer_wifi_configure(job->ssid,job->password);
 memset(job->ssid,0,sizeof(job->ssid));memset(job->password,0,sizeof(job->password));
 pthread_mutex_lock(&g_settings_lock);g_settings_busy=false;g_settings_result=result;g_settings_result_epoch=job->epoch;g_settings_result_ready=true;pthread_mutex_unlock(&g_settings_lock);
}
static bool settings_back(void *unused)
{
 (void)unused;
 if(g_settings_keyboard&&!lv_obj_has_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN)){
  lv_keyboard_set_textarea(g_settings_keyboard,NULL);buffer_ui_add_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN);return true;
 }
 if(!g_settings_reset_kind)return false;
 g_settings_reset_kind=0;buffer_ui_add_flag(g_settings_confirm,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(g_settings_body,LV_OBJ_FLAG_HIDDEN);buffer_ui_shell_local_end("settings",NULL);return true;
}
static void settings_closed(void *unused)
{while(settings_back(unused)){}if(g_settings_password)lv_textarea_set_text(g_settings_password,"");}
static void settings_open(lv_event_t *event)
{(void)event;(void)buffer_ui_shell_open("settings",NULL);}
static void settings_back_event(lv_event_t *event)
{(void)event;(void)buffer_ui_shell_back();}
static void settings_keyboard_event(lv_event_t *event)
{if(lv_event_get_code(event)==LV_EVENT_READY||lv_event_get_code(event)==LV_EVENT_CANCEL)(void)settings_back(NULL);}
static void settings_edit(lv_event_t *event)
{
 lv_obj_t *field=lv_event_get_current_target(event);lv_keyboard_set_textarea(g_settings_keyboard,field);
 buffer_ui_remove_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN);lv_obj_scroll_to_view_recursive(field,LV_ANIM_OFF);
}
static void settings_submit(lv_event_t *event)
{
 bool reset=(intptr_t)lv_event_get_user_data(event)!=0;
 pthread_mutex_lock(&g_settings_lock);
 if(g_settings_busy){pthread_mutex_unlock(&g_settings_lock);return;}
 struct settings_job *job=calloc(1,sizeof(*job));
 if(!job){pthread_mutex_unlock(&g_settings_lock);return;}
 job->kind=reset?g_settings_reset_kind:0;job->epoch=g_settings_epoch;
 if(!reset){
  const char *ssid=lv_textarea_get_text(g_settings_ssid),*password=lv_textarea_get_text(g_settings_password);
  if(!*ssid||strlen(ssid)>32||strlen(password)>64){free(job);pthread_mutex_unlock(&g_settings_lock);buffer_ui_label_set_text(g_settings_feedback,"网络名称不能为空，且最多 32 字节；密码最多 64 字节。");return;}
  strcpy(job->ssid,ssid);strcpy(job->password,password);
 }else if(!job->kind){free(job);pthread_mutex_unlock(&g_settings_lock);return;}
 g_settings_busy=true;pthread_mutex_unlock(&g_settings_lock);
 int result=buffer_ui_work_submit(settings_work,job);
 if(result){memset(job,0,sizeof(*job));free(job);pthread_mutex_lock(&g_settings_lock);g_settings_busy=false;pthread_mutex_unlock(&g_settings_lock);}
 if(reset)(void)settings_back(NULL);else if(!lv_obj_has_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN))(void)settings_back(NULL);
 lv_textarea_set_text(g_settings_password,"");
 buffer_ui_label_set_text(g_settings_feedback,result?"操作未提交，请重试":reset?"正在重启设备…":"正在连接网络…");
}
static void settings_reset(lv_event_t *event)
{
 unsigned kind=(unsigned)(uintptr_t)lv_event_get_user_data(event);
 if(kind<1||kind>3)return;
 if(!buffer_ui_shell_local_begin("settings",lv_event_get_current_target(event)))return;
 g_settings_reset_kind=kind;
 const char *copy=kind==1?"重启设备？\n所有配置和记录都会保留。":kind==2?"重置网络与配对？\n将忘记 Wi-Fi 和已批准设备，重新进入配置流程。记录和服务配置保留。":"恢复出厂设置？\n将删除本机所有记录、任务、服务密钥、配对和网络配置。手机上的数据不会被删除。此操作无法撤销。";
 buffer_ui_label_set_text(g_settings_reset_copy,copy);buffer_ui_add_flag(g_settings_body,LV_OBJ_FLAG_HIDDEN);buffer_ui_remove_flag(g_settings_confirm,LV_OBJ_FLAG_HIDDEN);
}
static void settings_refresh(void)
{
 if(!g_settings)return;
 pthread_mutex_lock(&g_settings_lock);bool busy=g_settings_busy,ready=g_settings_result_ready&&g_settings_result_epoch==g_settings_epoch;int result=g_settings_result;g_settings_result_ready=false;pthread_mutex_unlock(&g_settings_lock);
 if(busy)lv_obj_add_state(g_settings_connect,LV_STATE_DISABLED);else lv_obj_remove_state(g_settings_connect,LV_STATE_DISABLED);
 for(unsigned i=0;i<3;i++){if(busy)lv_obj_add_state(g_settings_reset_buttons[i],LV_STATE_DISABLED);else lv_obj_remove_state(g_settings_reset_buttons[i],LV_STATE_DISABLED);}
 if(busy)lv_obj_add_state(g_settings_commit,LV_STATE_DISABLED);else lv_obj_remove_state(g_settings_commit,LV_STATE_DISABLED);
 if(ready)buffer_ui_label_set_text(g_settings_feedback,result?"操作失败，请检查网络或稍后重试。":"网络已连接");
 buffer_visible(g_settings_feedback,*lv_label_get_text(g_settings_feedback)!=0);
 int brightness=0;bool pending=false;int available=buffer_backlight_level(&brightness,&pending);
 if(available)lv_obj_add_state(g_brightness_slider,LV_STATE_DISABLED);else lv_obj_remove_state(g_brightness_slider,LV_STATE_DISABLED);
 if(!lv_slider_is_dragged(g_brightness_slider)&&!pending){
  char caption[48];if(available)snprintf(caption,sizeof(caption),"此设备暂不支持调节亮度");
  else{lv_slider_set_value(g_brightness_slider,brightness,LV_ANIM_OFF);snprintf(caption,sizeof(caption),"屏幕亮度 · %d%%",brightness);}
  buffer_ui_label_set_text(g_brightness_value,caption);
 }

}
static lv_obj_t *settings_text(lv_obj_t *parent,const char *text,enum buffer_ui_type_role role)
{
 lv_obj_t *label=lv_label_create(parent);buffer_ui_text_role(label,role);buffer_ui_label_set_text(label,text);
 lv_obj_set_width(label,LV_PCT(100));lv_label_set_long_mode(label,LV_LABEL_LONG_WRAP);return label;
}
static lv_obj_t *settings_section(lv_obj_t *parent,const char *title)
{
 lv_obj_t *group=buffer_card_panel_create(parent);lv_obj_set_style_pad_all(group,buffer_ui_dp(16),0);
 lv_obj_set_style_pad_row(group,buffer_ui_dp(12),0);lv_obj_set_style_radius(group,buffer_ui_dp(20),0);
 buffer_ui_style_bg_color(group,lv_color_white(),0);
 if(title&&*title){lv_obj_t *label=settings_text(group,title,BUFFER_TEXT_TITLE_MEDIUM);buffer_ui_style_text_color(label,lv_color_hex(BUFFER_ACCENT),0);}
 return group;
}
static lv_obj_t *settings_create(void)
{
 g_settings_epoch++;g_settings_reset_kind=0;
 g_settings=buffer_card_panel_create(g_root);lv_obj_set_style_pad_all(g_settings,0,0);
 (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="settings",.title="设置",.entry_route="settings",.kind=BUFFER_UI_APP,.capture_mode=BUFFER_UI_CAPTURE_HIDDEN,.min_width_dp=160,.min_height_dp=128,.actions=(const char *const[]){"open","back","set-font-scale","set-privacy","connect-wifi","restart","reset-connections","factory-reset"},.action_count=8,.back=settings_back,.closed=settings_closed},g_settings);
 lv_obj_t *header=buffer_card_panel_create(g_settings);lv_obj_set_style_pad_all(header,0,0);buffer_ui_style_bg_opa(header,LV_OPA_TRANSP,0);lv_obj_set_style_radius(header,0,0);lv_obj_set_flex_flow(header,LV_FLEX_FLOW_ROW);lv_obj_set_flex_align(header,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
 lv_obj_t *back=buffer_button(header,"返回",settings_back_event,NULL);buffer_ui_icon_button(back,&buffer_back_icon);lv_obj_set_size(back,buffer_ui_dp(48),buffer_ui_dp(48));
 lv_obj_t *title=lv_label_create(header);buffer_ui_text_role(title,BUFFER_TEXT_TITLE_LARGE);buffer_ui_label_set_text(title,"设置");
 g_settings_body=buffer_card_panel_create(g_settings);lv_obj_set_height(g_settings_body,0);lv_obj_set_style_min_height(g_settings_body,0,0);lv_obj_set_flex_grow(g_settings_body,1);buffer_ui_add_flag(g_settings_body,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(g_settings_body,LV_DIR_VER);
 lv_obj_set_style_radius(g_settings_body,0,0);buffer_ui_style_bg_color(g_settings_body,lv_color_hex(BUFFER_BG),0);lv_obj_set_style_pad_all(g_settings_body,buffer_ui_dp(16),0);lv_obj_set_style_pad_row(g_settings_body,buffer_ui_dp(12),0);
 g_settings_feedback=lv_label_create(g_settings_body);buffer_ui_text_role(g_settings_feedback,BUFFER_TEXT_BODY_MEDIUM);lv_obj_set_width(g_settings_feedback,LV_PCT(100));lv_label_set_long_mode(g_settings_feedback,LV_LABEL_LONG_WRAP);buffer_ui_label_set_text(g_settings_feedback,"");buffer_ui_add_flag(g_settings_feedback,LV_OBJ_FLAG_HIDDEN);
 g_settings_preferences=settings_section(g_settings_body,"");lv_obj_set_style_pad_all(g_settings_preferences,0,0);buffer_ui_style_bg_opa(g_settings_preferences,LV_OPA_TRANSP,0);
 lv_obj_t *network=settings_section(g_settings_body,"网络与连接");
 settings_text(network,"连接 Wi-Fi",BUFFER_TEXT_TITLE_MEDIUM);
 settings_text(network,"输入网络名称和密码。开放网络无需密码。",BUFFER_TEXT_BODY_MEDIUM);
 g_settings_ssid=lv_textarea_create(network);lv_obj_set_width(g_settings_ssid,LV_PCT(100));lv_textarea_set_one_line(g_settings_ssid,true);lv_textarea_set_max_length(g_settings_ssid,32);lv_textarea_set_placeholder_text(g_settings_ssid,"网络名称");buffer_ui_text_role(g_settings_ssid,BUFFER_TEXT_BODY_LARGE);
 g_settings_password=lv_textarea_create(network);lv_obj_set_width(g_settings_password,LV_PCT(100));lv_textarea_set_one_line(g_settings_password,true);lv_textarea_set_password_mode(g_settings_password,true);lv_textarea_set_max_length(g_settings_password,64);lv_textarea_set_placeholder_text(g_settings_password,"密码");buffer_ui_text_role(g_settings_password,BUFFER_TEXT_BODY_LARGE);
 lv_obj_add_event_cb(g_settings_ssid,settings_edit,LV_EVENT_CLICKED,NULL);lv_obj_add_event_cb(g_settings_password,settings_edit,LV_EVENT_CLICKED,NULL);
 g_settings_connect=buffer_button(network,"连接网络",settings_submit,NULL);lv_obj_set_width(g_settings_connect,LV_PCT(100));
 lv_obj_t *device=settings_section(g_settings_body,"设备管理");
 static const char *const names[]={"重启设备","重置网络与配对","恢复出厂设置"};
 for(unsigned i=0;i<3;i++){lv_obj_t *button=buffer_button(device,names[i],settings_reset,(void *)(uintptr_t)(i+1));g_settings_reset_buttons[i]=button;lv_obj_set_width(button,LV_PCT(100));
  if(i==2){buffer_ui_style_bg_color(button,lv_color_hex(BUFFER_ERROR),0);buffer_ui_style_text_color(button,lv_color_white(),0);}}
 g_settings_confirm=buffer_card_panel_create(g_settings);lv_obj_set_height(g_settings_confirm,0);lv_obj_set_style_min_height(g_settings_confirm,0,0);lv_obj_set_flex_grow(g_settings_confirm,1);buffer_ui_add_flag(g_settings_confirm,LV_OBJ_FLAG_HIDDEN|LV_OBJ_FLAG_SCROLLABLE);
 g_settings_reset_copy=lv_label_create(g_settings_confirm);buffer_ui_text_role(g_settings_reset_copy,BUFFER_TEXT_BODY_LARGE);lv_obj_set_width(g_settings_reset_copy,LV_PCT(100));lv_label_set_long_mode(g_settings_reset_copy,LV_LABEL_LONG_WRAP);
 lv_obj_t *cancel=buffer_button(g_settings_confirm,"取消",settings_back_event,NULL);lv_obj_set_width(cancel,LV_PCT(100));
 lv_obj_t *confirm=buffer_button(g_settings_confirm,"确认并重启",settings_submit,(void *)1);g_settings_commit=confirm;lv_obj_set_width(confirm,LV_PCT(100));
 g_settings_keyboard=lv_keyboard_create(g_settings);lv_obj_set_size(g_settings_keyboard,LV_PCT(100),LV_PCT(42));buffer_ui_text_role(g_settings_keyboard,BUFFER_TEXT_LABEL_LARGE);lv_obj_add_event_cb(g_settings_keyboard,settings_keyboard_event,LV_EVENT_ALL,NULL);buffer_ui_add_flag(g_settings_keyboard,LV_OBJ_FLAG_HIDDEN);
 return g_settings_preferences;
}
