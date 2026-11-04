/* Actual production calendar worker, storage projection and LVGL view.
 * Fixture files and clock are synthetic; no emulator, radio, or user calendar. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include <semaphore.h>
static time_t fixture_now=-1;
static time_t ui_time(time_t *out){time_t value=fixture_now<0?time(NULL):fixture_now;if(out)*out=value;return value;}
#define time ui_time
#include "buffer_calendar_ui.c"
#undef time
static sem_t read_entered, read_release;
static unsigned pause_read,calendar_reads;
#include "buffer_agent.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
void buffer_fabric_snapshot(struct buffer_fabric_status_s *status) { memset(status,0,sizeof(*status));status->connected=true; }
static unsigned synchronizations;
int buffer_fabric_request_sync(void) {synchronizations++;return 0;}
cJSON *buffer_agent_read_json(const char *path,size_t limit)
{
 __atomic_add_fetch(&calendar_reads,1,__ATOMIC_RELAXED);
 if(__atomic_exchange_n(&pause_read,0,__ATOMIC_ACQ_REL)){sem_post(&read_entered);sem_wait(&read_release);}
 FILE *f=fopen(path,"rb");if(!f)return NULL;
 assert(!fseek(f,0,SEEK_END));long length=ftell(f);rewind(f);
 if(length<0||(size_t)length>limit){fclose(f);errno=EFBIG;return NULL;}
 char *raw=calloc(1,(size_t)length+1);assert(raw);assert(fread(raw,1,length,f)==(size_t)length);fclose(f);
 cJSON *value=cJSON_Parse(raw);free(raw);return value;
}
bool buffer_agent_valid_id(const char *id)
{if(!id||!id[0]||strlen(id)>=BUFFER_ID_SIZE)return false;for(const char *p=id;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='-'||*p=='_'))return false;return true;}
#include "visual_capture.h"
#include "edge_touch_fixture.h"
static void pump(unsigned milliseconds)
{for(unsigned n=0;n<milliseconds;n+=16){lv_tick_inc(16);lv_timer_handler();usleep(1000);}}
static void ready(void)
{for(unsigned i=0;i<500;i++){struct buffer_calendar_view v;buffer_calendar_snapshot(&v);if(!v.working&&v.year==year&&v.month==month&&v.day==day){buffer_calendar_ui_refresh();return;}usleep(2000);}assert(!"calendar worker timed out");}
static int64_t at(int y,int m,int d,int hour)
{struct tm t={0};t.tm_year=y-1900;t.tm_mon=m-1;t.tm_mday=d;t.tm_hour=hour;t.tm_isdst=-1;return(int64_t)mktime(&t)*1000;}
static struct buffer_calendar_view series_day(int y,int m,int d)
{
 buffer_calendar_select(y,m,d,false);
 for(unsigned i=0;i<2000;i++){
  struct buffer_calendar_view view;buffer_calendar_snapshot(&view);
  if(!view.working&&view.year==y&&view.month==m&&view.day==d){assert(!view.error);return view;}
  usleep(1000);
 }assert(!"series expansion timed out");return(struct buffer_calendar_view){0};
}
static void series_fixture(void)
{
 char directory[320],path[380];snprintf(directory,sizeof(directory),"%s/calendars",CONFIG_BUFFER_VELA_DATA_DIR);mkdir(directory,0700);
 snprintf(path,sizeof(path),"%s/series.json",directory);
 cJSON *doc=cJSON_Parse("{\"card_id\":\"series\",\"deleted\":false,\"card\":{\"kind\":\"calendar_event\",\"title\":\"重复课程\",\"schedule\":{},\"recurrence\":{\"version\":1,\"local_start\":\"20261101T090000\",\"rrule\":\"FREQ=DAILY;COUNT=4\",\"exrule\":\"\",\"rdates\":[],\"exdates\":[],\"offsets\":[[0,28800]],\"exceptions\":[],\"days\":0}}}");assert(doc);
 cJSON *card=cJSON_GetObjectItemCaseSensitive(doc,"card"),*schedule=cJSON_GetObjectItemCaseSensitive(card,"schedule"),*series=cJSON_GetObjectItemCaseSensitive(card,"recurrence");
 cJSON_AddNumberToObject(schedule,"start",(double)at(2026,11,1,9));cJSON_AddNumberToObject(schedule,"end",(double)at(2026,11,1,10));cJSON_AddBoolToObject(schedule,"all_day",false);
 cJSON_AddItemToArray(cJSON_GetObjectItemCaseSensitive(series,"exdates"),cJSON_CreateNumber((double)at(2026,11,2,9)));
 cJSON *exception=cJSON_CreateObject();cJSON_AddNumberToObject(exception,"original",(double)at(2026,11,3,9));cJSON_AddBoolToObject(exception,"cancelled",false);cJSON_AddStringToObject(exception,"title","改到周五");cJSON_AddStringToObject(exception,"location","二楼");
 cJSON *moved=cJSON_AddObjectToObject(exception,"schedule");cJSON_AddNumberToObject(moved,"start",(double)at(2026,11,6,14));cJSON_AddNumberToObject(moved,"end",(double)at(2026,11,6,15));
 cJSON_AddItemToArray(cJSON_GetObjectItemCaseSensitive(series,"exceptions"),exception);
 cJSON_AddItemToArray(cJSON_GetObjectItemCaseSensitive(series,"rdates"),cJSON_CreateNumber((double)at(2026,11,10,9)));
 char *raw=cJSON_PrintUnformatted(doc);FILE *file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);
 assert(!buffer_calendar_start());
 assert(series_day(2026,11,1).total==1);assert(series_day(2026,11,2).total==0);assert(series_day(2026,11,3).total==0);
 struct buffer_calendar_view view=series_day(2026,11,6);assert(view.count==1&&!strcmp(view.events[0].title,"改到周五"));
 assert(series_day(2026,11,4).total==1);assert(series_day(2026,11,10).total==1);assert(series_day(2026,11,11).total==0);
 buffer_calendar_stop();
 cJSON_ReplaceItemInObjectCaseSensitive(series,"exceptions",cJSON_CreateArray());
 cJSON_ReplaceItemInObjectCaseSensitive(series,"exdates",cJSON_CreateArray());cJSON_ReplaceItemInObjectCaseSensitive(series,"rdates",cJSON_CreateArray());
 cJSON_ReplaceItemInObjectCaseSensitive(series,"local_start",cJSON_CreateString("20261102T090000"));
 cJSON_ReplaceItemInObjectCaseSensitive(series,"rrule",cJSON_CreateString("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=4"));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(2026,11,2,9)));cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(2026,11,2,10)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 assert(series_day(2026,11,2).total==1);assert(series_day(2026,11,4).total==1);assert(series_day(2026,11,9).total==0);assert(series_day(2026,11,16).total==1);assert(series_day(2026,11,18).total==1);assert(series_day(2026,11,30).total==0);
 buffer_calendar_stop();
 cJSON_ReplaceItemInObjectCaseSensitive(series,"local_start",cJSON_CreateString("20260130T090000"));
 cJSON_ReplaceItemInObjectCaseSensitive(series,"rrule",cJSON_CreateString("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;UNTIL=20260430T010000Z"));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(2026,1,30,9)));cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(2026,1,30,10)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 assert(series_day(2026,2,27).total==1);assert(series_day(2026,2,28).total==0);assert(series_day(2026,3,31).total==1);assert(series_day(2026,4,30).total==1);assert(series_day(2026,5,29).total==0);
 buffer_calendar_stop();
 cJSON_ReplaceItemInObjectCaseSensitive(series,"local_start",cJSON_CreateString("20260307T090000"));
 cJSON_ReplaceItemInObjectCaseSensitive(series,"rrule",cJSON_CreateString("FREQ=DAILY;COUNT=3"));
 cJSON_ReplaceItemInObjectCaseSensitive(series,"offsets",cJSON_Parse("[[0,-18000],[1772953200,-14400]]"));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(2026,3,7,22)));cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(2026,3,7,23)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 struct buffer_calendar_view before=series_day(2026,3,7),after=series_day(2026,3,8);assert(before.count==1&&after.count==1);
 assert(after.events[0].start-before.events[0].start==23*3600000LL);assert(after.events[0].start==at(2026,3,8,21));
 buffer_calendar_stop();
 cJSON_ReplaceItemInObjectCaseSensitive(series,"local_start",cJSON_CreateString("20260308T000000"));cJSON_ReplaceItemInObjectCaseSensitive(series,"days",cJSON_CreateNumber(1));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"all_day",cJSON_CreateBool(true));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(2026,3,8,13)));cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(2026,3,9,12)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 before=series_day(2026,3,8);assert(before.count==1&&before.events[0].end-before.events[0].start==23*3600000LL);
 buffer_calendar_stop();
 cJSON_DeleteItemFromObject(card,"recurrence");
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(2026,11,4,0)));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(2026,11,4,0)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 assert(series_day(2026,11,3).total==0);view=series_day(2026,11,4);assert(view.count==1&&view.events[0].start==view.events[0].end);
 assert(series_day(2026,11,5).total==0);
 buffer_calendar_stop();
 cJSON_AddItemToObject(card,"recurrence",cJSON_Parse("{\"version\":1,\"local_start\":\"19640501T090000\",\"rrule\":\"FREQ=YEARLY\",\"exrule\":\"\",\"rdates\":[],\"exdates\":[],\"offsets\":[[0,28800]],\"exceptions\":[],\"days\":0}"));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"all_day",cJSON_CreateBool(false));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"start",cJSON_CreateNumber((double)at(1964,5,1,9)));
 cJSON_ReplaceItemInObjectCaseSensitive(schedule,"end",cJSON_CreateNumber((double)at(1964,5,1,10)));
 raw=cJSON_PrintUnformatted(doc);file=fopen(path,"wb");assert(file);fputs(raw,file);fclose(file);free(raw);assert(!buffer_calendar_start());
 view=series_day(2026,5,1);assert(view.count==1&&view.events[0].start==at(2026,5,1,9));
 buffer_calendar_stop();unlink(path);cJSON_Delete(doc);
}
static void time_label_fixture(void)
{
 char label[144];struct buffer_calendar_event event={.start=at(2026,11,4,23),.end=at(2026,11,5,1)};
 event_time_label(&event,label,sizeof(label));assert(!strcmp(label,"11月4日 23:00 – 11月5日 01:00"));
 event.start=at(2026,12,31,23);event.end=at(2027,1,1,1);event_time_label(&event,label,sizeof(label));assert(strstr(label,"2026年12月31日")&&strstr(label,"2027年1月1日"));
 event.start=at(2026,11,4,0);event.end=at(2026,11,7,0);event.all_day=true;
 event_time_label(&event,label,sizeof(label));assert(!strcmp(label,"11月4日–11月6日 · 全天"));
 event.end=at(2026,11,5,0);event_time_label(&event,label,sizeof(label));assert(!strcmp(label,"全天"));
 event.end=event.start;event_time_label(&event,label,sizeof(label));assert(!strcmp(label,"全天"));
 event.all_day=false;event_time_label(&event,label,sizeof(label));assert(!strcmp(label,"00:00"));
}
static void fixture(void)
{
 mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700);char directory[320];snprintf(directory,sizeof(directory),"%s/replicas",CONFIG_BUFFER_VELA_DATA_DIR);mkdir(directory,0700);
 for(unsigned i=0;i<35;i++){
  char path[380];snprintf(path,sizeof(path),"%s/task-%02u.json",directory,i);FILE *f=fopen(path,"wb");assert(f);
  int64_t begin=at(2026,10,20,8)+(int64_t)i*60000,end=i==0?at(2026,10,21,1):begin+(i%4+1)*1800000;
  fprintf(f,"{\"card_id\":\"task-%02u\",\"deleted\":false,\"card\":{\"kind\":\"task_card\",\"state\":\"active\",\"title\":\"Scheduled task %02u with long text to exercise wrapping\",\"schedule\":{\"start\":%lld,\"end\":%lld,\"all_day\":false,\"zone\":\"Asia/Shanghai\"}}}",i,i,(long long)begin,(long long)end);fclose(f);
 }
}
static lv_point_t position;
static lv_indev_state_t pressed;
static void pointer_read(lv_indev_t *input,lv_indev_data_t *data)
{(void)input;if(pressed==LV_INDEV_STATE_PRESSED)data->point=position;data->state=pressed;}
static void inside(lv_obj_t *child,lv_obj_t *parent)
{lv_area_t a,b;lv_obj_get_coords(child,&a);lv_obj_get_coords(parent,&b);assert(a.x1>=b.x1&&a.x2<=b.x2&&a.y1>=b.y1&&a.y2<=b.y2);}
static void navigation_bounds(void)
{
 for(unsigned i=0;i<4;i++)inside(lv_obj_get_child(header,i),header);
 inside(month_label,lv_obj_get_parent(month_label));
 for(unsigned i=0;i<7;i++)inside(lv_obj_get_child(week,i),week);
}
int main(void)
{
 setenv("TZ","Asia/Shanghai",1);tzset();time_label_fixture();fixture();series_fixture();
 assert(buffer_calendar_month_days(2000,2)==29);assert(buffer_calendar_month_days(2100,2)==28);
 int y=2026,m=12;assert(!buffer_calendar_shift(&y,&m,1)&&y==2027&&m==1);
 lv_init();lv_display_t *display=lv_display_create(320,480);assert(display);
 static uint32_t pixels[320*24];lv_display_set_buffers(display,pixels,NULL,sizeof(pixels),LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(display,flush);
 buffer_ui_motion_init();
#ifdef BUFFER_UI_REAL_FONTS
 assert(!buffer_ui_typography_init());
#endif
 lv_indev_t *input=lv_indev_create();lv_indev_set_type(input,LV_INDEV_TYPE_POINTER);lv_indev_set_read_cb(input,pointer_read);
 /* Reproduce an RTC-less boot, then clock correction while flash is stalled. */
 sem_init(&read_entered,0,0);sem_init(&read_release,0,0);
 fixture_now=0;
 lv_obj_t *cold_page=lv_obj_create(lv_screen_active());lv_obj_set_size(cold_page,320,240);
 lv_obj_t *cold_desktop=lv_obj_create(cold_page);lv_obj_t *cold_launchers=lv_obj_create(cold_desktop);
 buffer_ui_shell_init(cold_page,cold_desktop);
 assert(!buffer_calendar_ui_attach(cold_desktop,cold_launchers));
 assert(!buffer_ui_shell_open("calendar",NULL));buffer_calendar_ui_refresh();assert(opened&&!date_known);
 assert(strstr(lv_label_get_text(empty),"校准"));
 assert(lv_obj_has_flag(month_view,LV_OBJ_FLAG_HIDDEN));
 __atomic_store_n(&pause_read,1,__ATOMIC_RELEASE);fixture_now=1793448000;
 buffer_calendar_ui_refresh();sem_wait(&read_entered);
 assert(date_known&&year==2026&&month==10&&day==31);
 assert(!lv_obj_has_state(cells[0].root,LV_STATE_DISABLED));
 assert(strcmp(lv_label_get_text(cells[0].label),"Text"));
 change_month(1);assert(year==2026&&month==11);
 buffer_calendar_ui_request(false);buffer_calendar_ui_refresh();assert(!opened);
 assert(!buffer_ui_shell_open("calendar",NULL));assert(opened&&year==2026&&month==11);
 buffer_ui_shell_home();assert(!opened);
 sem_post(&read_release);buffer_calendar_ui_detach();buffer_ui_shell_shutdown();lv_obj_delete(cold_page);
 sem_destroy(&read_entered);sem_destroy(&read_release);fixture_now=-1;
 const int sizes[][2]={{320,480},{240,320},{320,240},{480,320},{800,480},{480,800},{200,240}};
 for(unsigned layout=0;layout<sizeof(sizes)/sizeof(sizes[0]);layout++){
  lv_display_set_resolution(display,sizes[layout][0],sizes[layout][1]);
  lv_obj_t *page=lv_obj_create(lv_screen_active());lv_obj_remove_style_all(page);lv_obj_set_size(page,LV_PCT(100),LV_PCT(100));
  lv_obj_t *launchers=lv_obj_create(page);lv_obj_set_size(launchers,180,100);
  buffer_ui_shell_init(page,launchers);
  assert(!buffer_calendar_ui_attach(page,launchers));assert(!opened);
  lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);assert(opened&&expanded);
  select_date(2026,10,20,true);ready();pump(1200);buffer_calendar_ui_refresh();
  navigation_bounds();
  screenshot("calendar-month",sizes[layout][0],sizes[layout][1]);
  assert(snapshot.total==35&&snapshot.count==8&&snapshot.next);
  unsigned index=42;for(unsigned i=0;i<42;i++)if(cells[i].year==2026&&cells[i].month==10&&cells[i].day==20)index=i;
  assert(index<42);assert(snapshot.days[index].count==35);assert(snapshot.days[index].segments==12);
  assert(lv_obj_get_style_clip_corner(cells[index].bar,0));
  lv_obj_update_layout(host);lv_area_t g,h;lv_obj_get_coords(grid,&g);lv_obj_get_coords(host,&h);
  assert(g.y1>=h.y1&&(g.y2<=h.y2||lv_obj_get_scroll_bottom(month_view)>0));
  assert(lv_obj_get_height(cells[0].root)>=buffer_ui_dp(48));
  if(sizes[layout][1]>=240){
   lv_area_t bottom,viewport;lv_obj_get_coords(cells[41].root,&bottom);lv_obj_get_coords(month_view,&viewport);
   assert(bottom.y2<=viewport.y2||lv_obj_get_scroll_bottom(month_view)>0); /* All weeks remain reachable at 48dp. */
  }
  lv_obj_send_event(entry,LV_EVENT_PRESSED,NULL);pump(100);assert(lv_obj_get_style_radius(entry,0)==buffer_ui_dp(32));
  lv_obj_send_event(entry,LV_EVENT_RELEASED,NULL);pump(100);assert(lv_obj_get_style_radius(entry,0)==buffer_ui_dp(32));
  int full=lv_obj_get_height(month_view);
  lv_obj_send_event(cells[index].root,LV_EVENT_CLICKED,NULL);ready();
  assert(!expanded&&!lv_obj_has_flag(agenda,LV_OBJ_FLAG_HIDDEN));
  assert(strstr(lv_label_get_text(rows[0].ui_time),"10月21日"));
  lv_obj_update_layout(agenda);assert(lv_obj_get_width(rows[0].ui_time)<=lv_obj_get_content_width(rows[0].root));
  assert(lv_obj_get_height(month_view)==full);pump(64);
  assert(lv_obj_get_height(month_view)<full);pump(1200);assert(lv_obj_get_height(month_view)==0);
  screenshot("calendar-day",sizes[layout][0],sizes[layout][1]);
  inside(pager,host);inside(previous,pager);inside(next,pager);
  lv_area_t fixed_before,fixed_after;lv_obj_get_coords(pager,&fixed_before);
  assert(lv_obj_get_style_bg_opa(rows[0].root,0)==LV_OPA_TRANSP);
  assert(snapshot.count==8&&snapshot.next);char first[80];strcpy(first,snapshot.events[0].id);
  lv_obj_update_layout(agenda);if(sizes[layout][1]<=480)assert(lv_obj_get_scroll_bottom(agenda)>0);
  lv_obj_scroll_to_y(agenda,60,LV_ANIM_OFF);int before=lv_obj_get_scroll_y(agenda);
  pump(32);lv_obj_get_coords(pager,&fixed_after);assert(fixed_before.y1==fixed_after.y1&&fixed_before.y2==fixed_after.y2);
  buffer_calendar_refresh();ready();assert(lv_obj_get_scroll_y(agenda)==before);
  lv_obj_send_event(next,LV_EVENT_CLICKED,NULL);ready();assert(strcmp(first,snapshot.events[0].id)&&snapshot.previous);
  char page_anchor[80];strcpy(page_anchor,snapshot.events[0].id);
  toggle(NULL);assert(!opened);toggle(NULL);ready();assert(!strcmp(page_anchor,snapshot.events[0].id));
  lv_obj_send_event(previous,LV_EVENT_CLICKED,NULL);ready();assert(!strcmp(first,snapshot.events[0].id));
  lv_obj_send_event(lv_obj_get_parent(month_label),LV_EVENT_CLICKED,NULL);ready();assert(expanded);pump(1200);assert(lv_obj_get_height(month_view)==month_height);
  lv_obj_send_event(lv_obj_get_child(header,3),LV_EVENT_CLICKED,NULL);ready();assert(month==11);assert(lv_obj_get_style_translate_y(month_view,0)>0);pump(1200);assert(lv_obj_get_style_translate_y(month_view,0)==0);
  lv_obj_send_event(lv_obj_get_child(header,2),LV_EVENT_CLICKED,NULL);ready();assert(month==10);pump(1200);
  if(lv_obj_get_scroll_bottom(month_view)>0) {
    lv_obj_scroll_to_y(month_view,10000,LV_ANIM_OFF);pump(10);
    lv_area_t cell,viewport;lv_obj_get_coords(cells[41].root,&cell);lv_obj_get_coords(month_view,&viewport);
    assert(lv_obj_get_scroll_y(month_view)>0&&cell.y2<=viewport.y2);
    lv_obj_scroll_to_y(month_view,0,LV_ANIM_OFF);
  } else {
  // Actual LVGL pointer gesture, rather than invoking the month helper.
  lv_obj_update_layout(grid);lv_obj_get_coords(grid,&g);position.x=(g.x1+g.x2)/2;position.y=g.y2-10;pressed=LV_INDEV_STATE_PRESSED;pump(48);
  for(unsigned step=1;step<=4;step++){position.y=g.y2-10-(int)(step*25);pump(32);}
  pressed=LV_INDEV_STATE_RELEASED;pump(48);ready();assert(month==11&&expanded);
  }
  buffer_ui_motion_policy(true,false);
  select_date(2026,11,20,false);
  for(unsigned i=0;i<BUFFER_CALENDAR_PAGE;i++)assert(lv_obj_has_flag(rows[i].root,LV_OBJ_FLAG_HIDDEN));
  ready();assert(lv_obj_get_height(month_view)==0);
  select_date(2026,11,20,true);ready();assert(lv_obj_get_height(month_view)==month_height);
  assert(lv_obj_get_style_translate_x(month_view,0)==0);
  buffer_ui_motion_policy(false,false);
  lv_obj_send_event(lv_obj_get_child(header,0),LV_EVENT_CLICKED,NULL);assert(!opened);
  lv_obj_send_event(entry,LV_EVENT_CLICKED,NULL);assert(opened);
  assert(year==2026&&month==11&&day==20&&expanded);ready();
#ifdef BUFFER_UI_REAL_FONTS
  if(!layout){
   unsigned original=buffer_ui_font_scale();assert(!buffer_ui_typography_scale(original==200?125:200));
   buffer_calendar_ui_refresh();pump(1200);navigation_bounds();
   assert(year==2026&&month==11&&day==20&&expanded);
   assert(!buffer_ui_typography_scale(original));buffer_calendar_ui_refresh();pump(1200);navigation_bounds();
  }
#endif
  edge_touch_back(page,layout%2);assert(!opened);buffer_calendar_ui_refresh();assert(!opened);
  usleep(100000);unsigned reads=__atomic_load_n(&calendar_reads,__ATOMIC_RELAXED);
  for(unsigned i=0;i<3;i++){buffer_calendar_refresh();usleep(100000);}
  assert(__atomic_load_n(&calendar_reads,__ATOMIC_RELAXED)==reads);
  buffer_calendar_ui_detach();buffer_ui_shell_shutdown();lv_obj_delete(page);pump(32);
 }
 assert(synchronizations>=4);buffer_ui_motion_shutdown();lv_indev_delete(input);lv_display_delete(display);
#ifdef BUFFER_UI_REAL_FONTS
 buffer_ui_typography_shutdown();
#endif
 lv_deinit();
 puts("PASS: native calendar projection, leap years, 35-item paging, 320x480/240x320 month/day view, scroll retention and real vertical swipe");
 return 0;
}
