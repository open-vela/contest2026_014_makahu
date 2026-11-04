/* Controlled domain inputs; home LVGL objects are production code. */
int buffer_private_storage_prepare(void){return 0;}
static unsigned fixture_settings_resets,fixture_settings_wifi;
int buffer_device_reset_request(enum buffer_device_reset kind){assert(kind>=1&&kind<=3);fixture_settings_resets++;return -EIO;}
int buffer_wifi_configure(const char *ssid,const char *password){assert(ssid&&password);fixture_settings_wifi++;return 0;}
static void fixture_launcher(lv_obj_t *parent,const char *id,const char *name,
                             const lv_image_dsc_t *symbol)
{
 lv_obj_t *entry=lv_button_create(parent);
 buffer_ui_launcher_attach(parent,entry,id,name);buffer_ui_icon_button(entry,symbol);
}
int buffer_a2ui_present_alarm(const char * alarm_id) { return 0; }
static unsigned fixture_timer_opens;
static char fixture_opened_timer[80];
int buffer_a2ui_present_timer(const char * timer_id) {fixture_timer_opens++;snprintf(fixture_opened_timer,sizeof(fixture_opened_timer),"%s",timer_id);return 0; }
static bool fixture_recipe_present;
int buffer_a2ui_surface_cards(struct buffer_assistant_surface_card_s *cards,size_t capacity) {
 if(!fixture_recipe_present||!capacity)return 0;
 memset(cards,0,sizeof(*cards));cards[0].recipe=true;strcpy(cards[0].image_id,"fixture-recipe-photo");strcpy(cards[0].id,"recipe-stable");strcpy(cards[0].title,"海带红烧肉");strcpy(cards[0].preview,"小火炖煮一个小时。");return 1;
}
int buffer_a2ui_ui_attach(void * home_body, void * record_button) { return 0; }
void buffer_a2ui_ui_detach(void) {  }
void buffer_a2ui_ui_dock_notice(void * slot) {  }
void buffer_a2ui_ui_dock_page(void * page) {  }
void buffer_a2ui_ui_open_current(void) {  }
void buffer_a2ui_ui_capture_prompt(const char *text,const char *capture){(void)text;(void)capture;}
bool buffer_a2ui_ui_open(void) { return false; }
int buffer_a2ui_ui_open_surface(const char * id) { return 0; }
static char fixture_opened_recipe[80];
int buffer_a2ui_ui_open_recipe_surface(const char *id){snprintf(fixture_opened_recipe,sizeof(fixture_opened_recipe),"%s",id);return 0;}
void buffer_a2ui_ui_refresh(void) {  }
void buffer_agent_snapshot(struct buffer_agent_status_s * status) { memset(status, 0, sizeof(*status)); }
void buffer_agent_wake(void) {  }
static bool fixture_timer_present;
static bool fixture_timer_recipe;
static unsigned fixture_timer_state=BUFFER_ASSISTANT_RUNNING,fixture_timer_operation;
static char fixture_timer_target[BUFFER_ASSISTANT_ID_SIZE];
int buffer_assistant_ui_snapshot(enum buffer_assistant_event_e type, struct buffer_assistant_item_s * items, size_t capacity, size_t * count) {
 *count=0;
 if(fixture_timer_present&&type==BUFFER_ASSISTANT_EVENT_TIMER&&capacity){
   memset(items,0,sizeof(*items));strcpy(items[0].object_id,"timer-stable");strcpy(items[0].label,"测试倒计时");
   items[0].state=__atomic_load_n(&fixture_timer_state,__ATOMIC_ACQUIRE);items[0].recipe_step=fixture_timer_recipe;items[0].remaining_ms=45000;*count=1;
 }
 return 0;
}
void buffer_backlight_snapshot(char * text, size_t capacity) { if (capacity) text[0]=0; }
void buffer_ble_service_code(char * code, size_t size) { if (size) code[0]=0; }
int buffer_calendar_ui_attach(void * page, void * launchers) {fixture_launcher(launchers,"app.calendar","日历",&buffer_calendar_icon);return 0; }
void buffer_calendar_ui_detach(void) {  }
static bool fixture_calendar_open;
bool buffer_calendar_ui_open(void) { return fixture_calendar_open; }
void buffer_calendar_ui_refresh(void) {  }
bool buffer_card_supports_action(const struct buffer_card_s * card, const char * action) { return 0; }
void buffer_focus_request_mode(const char * mode) {
 pthread_mutex_lock(&g_buffer.lock);snprintf(g_buffer.mode,sizeof(g_buffer.mode),"%s",mode);pthread_mutex_unlock(&g_buffer.lock);
 snprintf(fixture.mode,sizeof(fixture.mode),"%s",mode);
}
char * buffer_followup_history(const char * card) { return NULL; }
uint64_t buffer_followup_revision(void) { return 0; }
int buffer_history_ui_attach(void * page, void * launchers) {fixture_launcher(launchers,"app.history","历史",&buffer_history_icon);return 0; }
void buffer_history_ui_detach(void) {  }
bool buffer_history_ui_open(void) { return 0; }
void buffer_history_ui_refresh(void) {  }
void buffer_live_asr_shutdown(void) {  }
static const char *fixture_transcript="";
void buffer_live_asr_snapshot(char * text, size_t capacity) { snprintf(text,capacity,"%s",fixture_transcript); }
int buffer_oobe_finish(void) { return 0; }
void buffer_oobe_present(int binding_record_status, bool paired, bool wifi_ready, bool agent_ready, bool agent_skipped, bool legacy_deferred, bool setup_complete, struct buffer_oobe_presentation_s * presentation) { memset(presentation,0,sizeof(*presentation)); presentation->page=BUFFER_OOBE_PAGE_HOME; }
void buffer_oobe_retry(void) {  }
bool buffer_oobe_setup_complete(void) { return true; }
char * buffer_replica_detail(const char * id) { return NULL; }
void buffer_request_card_action(const char * card_id, const char * action) {  }
void buffer_request_card_step(int delta) {  }
void buffer_request_sync(void) {  }
void buffer_set_status(const char * format, ...) {  }
int buffer_store_load_pairing(char * host, size_t host_size, char * token, size_t token_size, char * phone_device_id, size_t phone_device_id_size) { return -ENOENT; }
int buffer_todo_ui_attach(void * body) { return 0; }
void buffer_todo_ui_detach(void) {  }
void buffer_todo_ui_dock_entry(void * toolbar) {  }
void buffer_todo_ui_dock_launcher(void * page, void * launchers) {fixture_launcher(launchers,"app.todo","待办",&buffer_checklist_icon);}
bool buffer_todo_ui_open(void) { return 0; }
void buffer_todo_ui_refresh(void) {  }
void buffer_todo_ui_set_visible(bool visible) {  }
static unsigned fixture_record_cancels,fixture_record_stops;
int buffer_touch_record_cancel(void) {fixture_record_cancels++;return 0; }
static char fixture_record_target[BUFFER_ID_SIZE];
int buffer_touch_record_start_for_card(const char * card_id) {snprintf(fixture_record_target,sizeof(fixture_record_target),"%s",card_id?card_id:"");return 0; }
void buffer_touch_record_stop(void) {fixture_record_stops++; }
bool buffer_ui_runtime_snapshot(struct buffer_ui_runtime_s * out) { *out = fixture; return true; }
bool buffer_wifi_is_ready(void) { return 0; }
int buffer_wifi_reconnect(void) { return 0; }

int buffer_assistant_init(void) { return 0; }
int buffer_assistant_execute(const struct buffer_assistant_request_s *request,struct buffer_assistant_result_s *result){
 (void)result;snprintf(fixture_timer_target,sizeof(fixture_timer_target),"%s",request->object_id);
 __atomic_store_n(&fixture_timer_state,request->operation==BUFFER_ASSISTANT_TIMER_PAUSE?BUFFER_ASSISTANT_PAUSED:BUFFER_ASSISTANT_RUNNING,__ATOMIC_RELEASE);
 __atomic_store_n(&fixture_timer_operation,request->operation,__ATOMIC_RELEASE);return 0;
}

void buffer_calendar_ui_request(bool open){(void)open;}
void buffer_calendar_ui_request_month(int direction){(void)direction;}

static enum buffer_audio_phase_e fixture_audio_phase;
enum buffer_audio_phase_e buffer_audio_phase(void){return fixture_audio_phase;}

void buffer_a2ui_ui_publish_activities(uint64_t now){(void)now;}
bool buffer_a2ui_ui_recipe_running(const char *id){(void)id;return false;}
static bool fixture_recipe_result_visible=true;
bool buffer_a2ui_ui_recipe_result_visible(const char *id,const char *fingerprint){(void)id;(void)fingerprint;return fixture_recipe_result_visible;}

int buffer_backlight_request(int percent){return percent>=1&&percent<=100?0:-ERANGE;}
int buffer_backlight_level(int *percent,bool *pending){*percent=75;*pending=false;return 0;}

bool buffer_a2ui_ui_result_unread(const char *id,uint64_t sequence){(void)id;(void)sequence;return true;}
