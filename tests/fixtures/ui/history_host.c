/* Actual history worker, parser and LVGL app; archive I/O is a controlled
 * read-only source adapter. No live user records or device are accessed. */
#include <assert.h>
#include <semaphore.h>
#include <unistd.h>
#include "buffer_history_ui.c"
#include "visual_capture.h"
#include "edge_touch_fixture.h"
static sem_t read_entered,read_release;
static int read_error=-EIO;static bool block_read;
bool buffer_oobe_setup_complete(void){return true;}
int buffer_source_local_head(char *source,size_t capacity,uint64_t *sequence)
{snprintf(source,capacity,"local-fixture");*sequence=2;return __atomic_load_n(&read_error,__ATOMIC_ACQUIRE);}
static char *original(const char *source,uint64_t sequence)
{
 char text[7201];for(unsigned i=0;i<2400;i++)memcpy(text+3*i,"记",3);text[7200]=0;
 cJSON *j=cJSON_CreateObject(),*e=cJSON_AddObjectToObject(j,"event");
 cJSON_AddNumberToObject(j,"wire_version",1);cJSON_AddStringToObject(j,"source_id",source);cJSON_AddNumberToObject(j,"source_sequence",sequence);cJSON_AddStringToObject(j,"provenance","native");
 cJSON_AddStringToObject(e,"event_id","fixture:event");cJSON_AddStringToObject(e,"app_namespace","app.buffer");cJSON_AddStringToObject(e,"actor_id","fixture-device");
 cJSON_AddNumberToObject(e,"schema_version",1);cJSON_AddNumberToObject(e,"logical_clock",sequence);cJSON_AddNumberToObject(e,"created_at",1793592000000.0);
 cJSON_AddStringToObject(e,"type","CaptureCreated");cJSON_AddNullToObject(e,"capture_id");cJSON_AddArrayToObject(e,"parent_event_ids");
 cJSON *p=cJSON_AddObjectToObject(e,"payload");cJSON_AddStringToObject(p,"title","完整中文历史记录");cJSON_AddStringToObject(p,"text",text);
 char *raw=cJSON_PrintUnformatted(j);cJSON_Delete(j);return raw;
}
int buffer_source_local_read(uint64_t sequence,char **raw,size_t *bytes)
{
 if(__atomic_load_n(&block_read,__ATOMIC_ACQUIRE)){sem_post(&read_entered);sem_wait(&read_release);}
 *raw=original("local-fixture",sequence);*bytes=strlen(*raw);return 0;
}
int buffer_source_remote_history_source(const char *after,char next[65])
{if(*after)next[0]=0;else{memset(next,'a',64);next[64]=0;}return 0;}
int buffer_source_remote_history_read(const char *hash,uint64_t anchor,int direction,struct buffer_source_projection_input_s *record,bool *older,bool *newer)
{(void)hash;(void)anchor;(void)direction;strcpy(record->source,"remote-fixture");memset(record->peer,'b',64);record->peer[64]=0;record->sequence=1;record->raw=original(record->source,1);record->length=strlen(record->raw);*older=*newer=false;return 1;}
int buffer_source_remote_causal_check(const struct buffer_source_projection_input_s *input,char state[24])
{(void)input;strcpy(state,"missing_parent");return 0;}
static void pump(unsigned n){while(n--){lv_tick_inc(16);lv_timer_handler();}}
static void settled(void)
{
 for(unsigned i=0;i<3000;i++){
  buffer_history_ui_refresh();pthread_mutex_lock(&lock);bool done=!working&&!requested&&!dirty;pthread_mutex_unlock(&lock);
  if(done){pump(5);return;}usleep(1000);
 }assert(!"history worker did not settle");
}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);assert(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2);}
int main(void)
{
 sem_init(&read_entered,0,0);sem_init(&read_release,0,0);lv_init();lv_display_t *display=lv_display_create(320,240);
 static uint32_t pixels[1280*16];lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 (void)buffer_ui_typography_init();buffer_ui_motion_init();buffer_ui_motion_policy(true,false);
 lv_obj_t *window=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(window);lv_obj_set_size(window,LV_PCT(100),LV_PCT(100));buffer_ui_text_role(window,BUFFER_TEXT_BODY_LARGE);
 lv_obj_t *desktop=lv_obj_create(window);lv_obj_set_size(desktop,LV_PCT(100),LV_PCT(100));buffer_ui_shell_init(window,desktop);
 lv_obj_t *launchers=lv_obj_create(desktop);assert(!buffer_history_ui_attach(desktop,launchers));
 lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);settled();assert(opened&&!rendered_view.record&&strstr(lv_label_get_text(notice),"读取失败")&&!strstr(lv_label_get_text(body),"暂无"));
 __atomic_store_n(&read_error,0,__ATOMIC_RELEASE);submit(REFRESH);settled();assert(rendered_view.sequence==2&&rendered_view.next);
 assert(strstr(lv_label_get_text(body),"记"));lv_obj_scroll_to_y(reader,200,LV_ANIM_OFF);pump(2);int scroll=lv_obj_get_scroll_y(reader);assert(scroll>0);
 buffer_ui_shell_home();lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);settled();assert(lv_obj_get_scroll_y(reader)==scroll);
 show_options(true);edge_touch_back(window,true);assert(!options_open&&opened);
 assert(lv_group_get_focused(lv_obj_get_group(options_button))==options_button);
 assert(lv_obj_get_scroll_y(reader)==scroll);
 __atomic_store_n(&read_error,-EIO,__ATOMIC_RELEASE);submit(REFRESH);settled();assert(strstr(lv_label_get_text(notice),"读取失败")&&rendered_view.sequence==2&&lv_obj_get_scroll_y(reader)==scroll);
 __atomic_store_n(&read_error,0,__ATOMIC_RELEASE);__atomic_store_n(&block_read,true,__ATOMIC_RELEASE);submit(REFRESH);sem_wait(&read_entered);
 buffer_ui_shell_home();assert(!opened);pump(10);sem_post(&read_release);__atomic_store_n(&block_read,false,__ATOMIC_RELEASE);
 lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);settled();assert(opened);
 submit(NEXT);settled();assert(rendered_view.offset>0&&rendered_view.previous);size_t offset=rendered_view.offset;
 submit(RAW);settled();assert(rendered_view.raw&&rendered_view.offset==0);submit(RAW);settled();assert(!rendered_view.raw);
 submit(NEXT);settled();assert(rendered_view.offset==offset);submit(PREVIOUS);settled();assert(!rendered_view.offset);
 submit(EARLIER);settled();assert(rendered_view.sequence==1&&rendered_view.later);
 submit(SOURCE);settled();assert(!strcmp(rendered_view.source,"remote-fixture")&&strstr(lv_label_get_text(notice),"前序原文"));
 submit(SOURCE);settled();assert(!strcmp(rendered_view.source,"local-fixture"));
 const int sizes[][2]={{320,240},{240,320},{240,240},{480,320},{320,480},{800,480},{480,800},{1280,720},{200,240}};
 for(unsigned i=0;i<sizeof(sizes)/sizeof(sizes[0]);i++){
  lv_display_set_resolution(display,sizes[i][0],sizes[i][1]);pump(10);history_layout(NULL);pump(5);
  inside(header,host);inside(footer,host);inside(reader,host);assert(lv_obj_get_height(reader)>0);
  screenshot("history",sizes[i][0],sizes[i][1]);show_options(true);pump(5);inside(options,host);assert(lv_obj_get_height(options)>0);screenshot("history-options",sizes[i][0],sizes[i][1]);
  edge_touch_back(window,false);assert(opened&&!options_open);pump(3);
  unsigned before=flushes;buffer_history_ui_refresh();pump(3);assert(flushes==before);
 }
 edge_touch_back(window,true);assert(!opened);buffer_history_ui_refresh();assert(!opened);
 buffer_history_ui_detach();buffer_ui_shell_shutdown();lv_obj_delete(window);buffer_ui_motion_shutdown();buffer_ui_typography_shutdown();lv_display_delete(display);lv_deinit();
 sem_destroy(&read_entered);sem_destroy(&read_release);
 puts("PASS: history archive worker, envelope/UTF-8 chunks, sources/errors, nonblocking Home, local Back, reading preservation and adaptive layout");
}
