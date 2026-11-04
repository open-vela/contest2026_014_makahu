/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_calendar.h"
#include "buffer_todo.h"
#include "buffer_fabric.h"
#include "buffer_ui_motion.h"
#include "buffer_ui_theme.h"
#include "buffer_ui_layout.h"
#include "buffer_ui_shell.h"
#include "buffer_ui_icons.h"
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <syslog.h>
#include <string.h>
#include <time.h>
struct cell {lv_obj_t *root,*label,*bar,*segments[BUFFER_CALENDAR_SEGMENTS];int year,month,day;};
struct agenda_row {lv_obj_t *root,*time,*title,*location,*dot;};
static struct cell cells[42];
static struct agenda_row rows[BUFFER_CALENDAR_PAGE];
static lv_obj_t *host,*entry,*launcher,*header,*month_label,*month_view,*grid,*week,*agenda,*empty,*pager,*previous,*next;
static int month_height,transition,fold_height;
static bool fold_pending;
static bool opened,expanded=true;
static int year,month,day;
static uint64_t rendered;
static uint32_t refreshed;
static int connection=-1;
static bool layout_running,layout_dirty;
static unsigned layout_scale;
static int32_t layout_dpi;
static int requested_ui;
static bool date_known,follow_today=true;
static unsigned presentation_state;
unsigned buffer_calendar_ui_state(void){return __atomic_load_n(&presentation_state,__ATOMIC_ACQUIRE);}
static void publish_date(void){unsigned date=date_known?(unsigned)(year*10000+month*100+day):0;__atomic_store_n(&presentation_state,(date<<3)|(opened?1u:0u)|(expanded?2u:0u)|(date_known?4u:0u),__ATOMIC_RELEASE);}
static struct buffer_calendar_day local_days[BUFFER_CALENDAR_DAYS];
static struct buffer_calendar_view snapshot;
static const int32_t cols[]={LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_TEMPLATE_LAST};
static const int32_t week_rows[]={LV_GRID_FR(1),LV_GRID_TEMPLATE_LAST};
static const int32_t grid_rows[]={LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_FR(1),LV_GRID_TEMPLATE_LAST};
static void visible(lv_obj_t *obj,bool show)
{if(show)buffer_ui_remove_flag(obj,LV_OBJ_FLAG_HIDDEN);else buffer_ui_add_flag(obj,LV_OBJ_FLAG_HIDDEN);}
static lv_obj_t *plain(lv_obj_t *parent)
{lv_obj_t *obj=lv_obj_create(parent);lv_obj_remove_style_all(obj);buffer_ui_remove_flag(obj,LV_OBJ_FLAG_SCROLLABLE|LV_OBJ_FLAG_CLICKABLE);buffer_ui_add_flag(obj,LV_OBJ_FLAG_GESTURE_BUBBLE);return obj;}
static int title_height(const char *text,int width,lv_point_t *size)
{
 lv_text_get_size(size,text,lv_obj_get_style_text_font(month_label,0),
   lv_obj_get_style_text_letter_space(month_label,0),lv_obj_get_style_text_line_space(month_label,0),width,LV_TEXT_FLAG_NONE);
 return size->y;
}
static void layout_mode(bool animate)
{
 if(layout_running)return;
 layout_running=true;
 struct buffer_ui_viewport v=buffer_ui_viewport(host);
 layout_scale=buffer_ui_font_scale();layout_dpi=lv_display_get_dpi(lv_obj_get_display(host));
 int width=lv_obj_get_content_width(host);
 bool header_wrap=width<4*v.control;
 int title_width=width-(header_wrap?1:3)*v.control;if(title_width<v.control)title_width=v.control;
 char title[64];lv_point_t measured;
 buffer_ui_text_role(month_label,width<buffer_ui_dp(260)?BUFFER_TEXT_LABEL_LARGE:BUFFER_TEXT_TITLE_MEDIUM);
 if(!date_known)snprintf(title,sizeof(title),"等待校时");
 else if(expanded)snprintf(title,sizeof(title),"%d年%d月",year,month);
 else snprintf(title,sizeof(title),"%d月%d日",month,day);
 (void)title_height(title,LV_COORD_MAX,&measured);
 if(measured.x>title_width){
  if(!date_known)snprintf(title,sizeof(title),"等待\n校时");
  else if(expanded){
   snprintf(title,sizeof(title),"%d.%02d",year,month);(void)title_height(title,LV_COORD_MAX,&measured);
   if(measured.x>title_width)snprintf(title,sizeof(title),"%d\n%d月",year,month);
  }else snprintf(title,sizeof(title),"%d月\n%d日",month,day);
 }
 buffer_ui_label_set_text(month_label,title);
 lv_obj_t *title_button=lv_obj_get_parent(month_label);
 lv_label_set_long_mode(month_label,LV_LABEL_LONG_WRAP);lv_obj_set_width(month_label,title_width);
 int navigation=title_height(title,title_width,&measured);if(navigation<v.control)navigation=v.control;
 /* Constrain the title to its allocated slot after generic label measurement;
  * a control's natural minimum must not push Back/month arrows off-screen. */
 lv_obj_set_flex_grow(title_button,0);lv_obj_set_size(title_button,title_width,navigation);
 lv_obj_set_style_min_width(title_button,v.control,0);lv_obj_set_style_min_height(title_button,navigation,0);
 lv_obj_set_height(header,navigation+(header_wrap?v.control:0));
 int week_height=buffer_ui_scaled_sp(buffer_ui_type_metrics(BUFFER_TEXT_LABEL_LARGE)->line_sp);
 if(week_height<buffer_ui_dp(24))week_height=buffer_ui_dp(24);
 lv_obj_set_height(week,week_height);
 lv_obj_set_style_pad_row(host,v.gap,0);
 lv_obj_set_style_pad_row(month_view,v.gap,0);
 int notice=lv_obj_has_flag(empty,LV_OBJ_FLAG_HIDDEN)?0:lv_obj_get_height(empty)+v.gap;
 int height=lv_obj_get_content_height(host)-navigation-(header_wrap?v.control:0)-v.gap-notice;
 if(height<buffer_ui_dp(80))height=buffer_ui_dp(80);
 month_height=height;
 int grid_height=height-week_height-v.gap;
 /* Date cells retain 48dp targets. Short panels scroll the six-week grid
  * below a fixed navigation bar; adjacent targets never overlap. */
 int cell_height=buffer_ui_scaled_sp(buffer_ui_type_metrics(BUFFER_TEXT_LABEL_LARGE)->line_sp)+buffer_ui_dp(10);
 if(cell_height<v.control)cell_height=v.control;
 int minimum=6*cell_height+5*buffer_ui_dp(2);
 if(grid_height<minimum)grid_height=minimum;
 lv_obj_set_height(grid,grid_height);
 buffer_ui_add_flag(month_view,LV_OBJ_FLAG_SCROLLABLE);
 lv_obj_set_scroll_dir(month_view,LV_DIR_ALL);
 buffer_ui_remove_flag(month_view,LV_OBJ_FLAG_SCROLL_CHAIN_HOR);
 int minimum_width=7*buffer_ui_dp(48)+6*buffer_ui_dp(2);
 if(width<minimum_width)width=minimum_width;
 lv_obj_set_width(week,width);lv_obj_set_width(grid,width);
 /* Loading/status text may remeasure while folding. Preserve the visible
  * starting height instead of clamping it before the spring can start. */
 lv_obj_set_style_max_height(month_view,animate&&fold_height>height?fold_height:height,0);
 visible(agenda,!expanded);
 visible(pager,!expanded&&(snapshot.previous||snapshot.next));
 if(animate)buffer_ui_motion_height(month_view,fold_height,expanded?height:0);
 else {buffer_ui_motion_cancel(month_view);lv_obj_set_height(month_view,expanded?height:0);}
 layout_running=false;
}
/* Date-only ranges use an exclusive end; point events retain their one date. */
static void event_time_label(const struct buffer_calendar_event *event,char *out,size_t size)
{
 time_t begin=(time_t)(event->start/1000),finish=(time_t)(event->end/1000);
 struct tm a,b;localtime_r(&begin,&a);localtime_r(&finish,&b);
 const char *done=event->done?" · 已完成":"";
 if(event->all_day){
  if(event->end>event->start){finish=(time_t)((event->end-1)/1000);localtime_r(&finish,&b);}
  if(a.tm_year==b.tm_year&&a.tm_yday==b.tm_yday)snprintf(out,size,"全天%s",done);
  else if(a.tm_year==b.tm_year)snprintf(out,size,"%d月%d日–%d月%d日 · 全天%s",a.tm_mon+1,a.tm_mday,b.tm_mon+1,b.tm_mday,done);
  else snprintf(out,size,"%d年%d月%d日–%d年%d月%d日 · 全天%s",a.tm_year+1900,a.tm_mon+1,a.tm_mday,b.tm_year+1900,b.tm_mon+1,b.tm_mday,done);
 }else if(event->start==event->end)snprintf(out,size,"%02d:%02d%s",a.tm_hour,a.tm_min,done);
 else if(a.tm_year==b.tm_year&&a.tm_yday==b.tm_yday)snprintf(out,size,"%02d:%02d – %02d:%02d%s",a.tm_hour,a.tm_min,b.tm_hour,b.tm_min,done);
 else if(a.tm_year==b.tm_year)snprintf(out,size,"%d月%d日 %02d:%02d – %d月%d日 %02d:%02d%s",a.tm_mon+1,a.tm_mday,a.tm_hour,a.tm_min,b.tm_mon+1,b.tm_mday,b.tm_hour,b.tm_min,done);
 else snprintf(out,size,"%d年%d月%d日 %02d:%02d – %d年%d月%d日 %02d:%02d%s",a.tm_year+1900,a.tm_mon+1,a.tm_mday,a.tm_hour,a.tm_min,b.tm_year+1900,b.tm_mon+1,b.tm_mday,b.tm_hour,b.tm_min,done);
}
static void resized(lv_event_t *event)
{(void)event;layout_dirty=true;}
static bool today_date(int *y,int *m,int *d)
{
 time_t now=time(NULL);struct tm t;
 if(now<1704067200||!localtime_r(&now,&t)||!buffer_calendar_month_days(t.tm_year+1900,t.tm_mon+1))return false;
 *y=t.tm_year+1900;*m=t.tm_mon+1;*d=t.tm_mday;return true;
}
static void local_grid(void)
{
 if(!date_known)return;
 buffer_calendar_grid_dates(year,month,local_days);
 int ty=0,tm=0,td=0;(void)today_date(&ty,&tm,&td);
 for(unsigned i=0;i<42;i++){
  struct cell *cell=&cells[i];struct buffer_calendar_day *date=&local_days[i];
  cell->year=date->year;cell->month=date->month;cell->day=date->day;
  char value[12];snprintf(value,sizeof(value),"%d",date->day);buffer_ui_label_set_text(cell->label,value);
  lv_obj_remove_state(cell->root,LV_STATE_DISABLED);
  bool selected=cell->year==year&&cell->month==month&&cell->day==day;
  buffer_ui_style_bg_color(cell->root,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);
  buffer_ui_style_bg_opa(cell->root,selected?LV_OPA_COVER:LV_OPA_TRANSP,0);
  lv_obj_set_style_border_width(cell->root,cell->year==ty&&cell->month==tm&&cell->day==td?buffer_ui_dp(1):0,0);
  lv_obj_set_style_border_color(cell->root,lv_color_hex(BUFFER_ACCENT),0);
  buffer_ui_style_text_color(cell->label,lv_color_hex(cell->month==month?BUFFER_ON_SURFACE:BUFFER_MUTED),0);
  visible(cell->bar,false);
 }
}
void buffer_calendar_ui_request(bool open)
{__atomic_store_n(&requested_ui,open?1:2,__ATOMIC_RELEASE);}
void buffer_calendar_ui_request_month(int direction)
{__atomic_store_n(&requested_ui,direction>0?3:4,__ATOMIC_RELEASE);}
static void select_date(int y,int m,int d,bool expand)
{
 fold_height=lv_obj_get_height(month_view);
 follow_today=false;fold_pending=expanded!=expand;year=y;month=m;day=d;expanded=expand;rendered=0;
 date_known=true;publish_date();visible(month_view,true);local_grid();
 for(unsigned i=0;i<BUFFER_CALENDAR_PAGE;i++)visible(rows[i].root,false);
 visible(pager,false);
 buffer_ui_label_set_text(empty,"正在读取日程…");
 visible(empty,true);layout_dirty=false;
 layout_mode(fold_pending);buffer_calendar_select(y,m,d,expand);refreshed=lv_tick_get();
 lv_obj_scroll_to_y(agenda,0,LV_ANIM_OFF);
}
static void shell_closed(void *context)
{
 (void)context;opened=false;buffer_calendar_set_visible(false);buffer_ui_motion_cancel(host);
 buffer_ui_motion_cancel(month_view);publish_date();
}
static bool initialized;
static void shell_activated(bool was_visible,void *context)
{
 (void)context;opened=true;buffer_calendar_set_visible(true);
  if(!initialized){
  initialized=true;expanded=true;transition=0;fold_pending=false;
  int y,m,d;
  if(today_date(&y,&m,&d)){select_date(y,m,d,true);follow_today=true;}
  else {
   date_known=false;layout_mode(false);visible(month_view,false);visible(pager,false);
   for(unsigned i=0;i<BUFFER_CALENDAR_PAGE;i++)visible(rows[i].root,false);
   buffer_ui_label_set_text(empty,"正在校准时间，联网后自动显示日期");
  }
  } else if(!was_visible&&date_known)buffer_calendar_refresh();
 publish_date();
}
static void toggle(lv_event_t *event)
{
 (void)event;struct buffer_ui_surface_info surface;
 if(buffer_ui_shell_info("calendar",&surface)){
  if(buffer_ui_shell_is_open("calendar"))buffer_ui_shell_hide("calendar");else(void)buffer_ui_shell_open(surface.entry_route,NULL);
  return;
 }
 opened=!opened;visible(host,opened);
 if(opened){shell_activated(false,NULL);buffer_ui_motion_enter(host,1,false);}else shell_closed(NULL);
}
static void expand(lv_event_t *event)
{(void)event;if(!expanded)select_date(year,month,day,true);}
static void choose(lv_event_t *event)
{struct cell *cell=lv_event_get_user_data(event);if(!date_known)return;select_date(cell->year,cell->month,cell->day,false);}
static void change_month(int direction)
{
 if(!date_known)return;
 transition=direction;
 if(expanded){int y=year,m=month;if(!buffer_calendar_shift(&y,&m,direction)){int d=day,limit=buffer_calendar_month_days(y,m);if(d>limit)d=limit;select_date(y,m,d,true);}}
 else {struct tm t={0};t.tm_year=year-1900;t.tm_mon=month-1;t.tm_mday=day+direction;t.tm_isdst=-1;(void)mktime(&t);
  if(buffer_calendar_month_days(t.tm_year+1900,t.tm_mon+1))select_date(t.tm_year+1900,t.tm_mon+1,t.tm_mday,false);}
}
static void navigate(lv_event_t *event)
{change_month((int)(intptr_t)lv_event_get_user_data(event));}
static void gesture(lv_event_t *event)
{
 (void)event;if(!expanded)return;
 if(lv_obj_get_scroll_y(month_view)>0||lv_obj_get_scroll_bottom(month_view)>0)return;
 lv_indev_t *input=lv_indev_active();if(!input)return;
 lv_dir_t direction=lv_indev_get_gesture_dir(input);if(direction==LV_DIR_TOP){lv_indev_wait_release(input);change_month(1);}else if(direction==LV_DIR_BOTTOM){lv_indev_wait_release(input);change_month(-1);}
}
static void paginate(lv_event_t *event)
{transition=(int)(intptr_t)lv_event_get_user_data(event);buffer_calendar_page(transition);lv_obj_scroll_to_y(agenda,0,LV_ANIM_OFF);}
static lv_obj_t *button(lv_obj_t *parent,const char *label,lv_event_cb_t cb,void *data,int width)
{
 lv_obj_t *b=lv_button_create(parent);buffer_ui_button_style(b,false);lv_obj_set_size(b,buffer_ui_dp(width),buffer_ui_dp(48));
 lv_obj_set_style_pad_hor(b,buffer_ui_dp(12),0);lv_obj_set_style_pad_ver(b,buffer_ui_dp(10),0);
 lv_obj_t *text=lv_label_create(b);buffer_ui_button_label(text);buffer_ui_label_set_text(text,label);lv_obj_center(text);
 lv_obj_add_event_cb(b,cb,LV_EVENT_CLICKED,data);return b;
}
int buffer_calendar_ui_attach(void *page,void *launchers)
{
 follow_today=true;year=month=day=0;date_known=today_date(&year,&month,&day);requested_ui=0;layout_dirty=false;
 initialized=false;opened=false;expanded=true;rendered=0;connection=-1;transition=0;fold_pending=false;
 int result=buffer_calendar_start();if(result)return result;buffer_calendar_set_visible(false);
 entry=lv_button_create(launchers);
 launcher=buffer_ui_launcher_attach(launchers,entry,"app.calendar","日历");
 lv_obj_add_event_cb(entry,toggle,LV_EVENT_CLICKED,NULL);
 buffer_ui_icon_button(entry,&buffer_calendar_icon);
 host=plain(page);lv_obj_set_size(host,LV_PCT(100),LV_PCT(100));
 (void)buffer_ui_shell_declare(&(struct buffer_ui_surface_declaration){.id="calendar",.title="日历",.entry_route="calendar",.kind=BUFFER_UI_APP,.min_width_dp=160,.min_height_dp=128,
  .actions=(const char *const[]){"open","select-date","previous-month","next-month","previous","next","back"},.action_count=7,.closed=shell_closed,.activated=shell_activated},host);
 buffer_ui_style_bg_color(host,lv_color_hex(BUFFER_BG),0);buffer_ui_style_bg_opa(host,LV_OPA_COVER,0);
 lv_obj_set_style_pad_all(host,0,0);lv_obj_set_style_pad_row(host,BUFFER_SECTION_GAP,0);lv_obj_set_flex_flow(host,LV_FLEX_FLOW_COLUMN);
 header=plain(host);lv_obj_set_size(header,LV_PCT(100),buffer_ui_dp(48));lv_obj_set_flex_flow(header,LV_FLEX_FLOW_ROW_WRAP);
 lv_obj_set_flex_align(header,LV_FLEX_ALIGN_START,LV_FLEX_ALIGN_CENTER,LV_FLEX_ALIGN_CENTER);
 buffer_ui_icon_button(button(header,"返回",toggle,NULL,48),&buffer_back_icon);
 lv_obj_t *title=button(header,"",expand,NULL,0);lv_obj_set_flex_grow(title,1);month_label=lv_obj_get_child(title,0);buffer_ui_text_role(month_label,BUFFER_TEXT_TITLE_LARGE);
 buffer_ui_style_bg_opa(title,LV_OPA_TRANSP,0);lv_obj_set_style_pad_all(title,0,0);
 buffer_ui_icon_button(button(header,"",navigate,(void *)(intptr_t)-1,48),&buffer_up_icon);
 buffer_ui_icon_button(button(header,"",navigate,(void *)(intptr_t)1,48),&buffer_down_icon);
 month_view=plain(host);lv_obj_set_width(month_view,LV_PCT(100));lv_obj_set_height(month_view,0);
 lv_obj_set_flex_flow(month_view,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(month_view,BUFFER_SECTION_GAP,0);
 lv_obj_set_style_min_height(month_view,0,0);
 week=plain(month_view);lv_obj_set_size(week,LV_PCT(100),buffer_ui_dp(24));lv_obj_set_layout(week,LV_LAYOUT_GRID);lv_obj_set_grid_dsc_array(week,cols,week_rows);
 static const char *names[]={"一","二","三","四","五","六","日"};
 for(unsigned i=0;i<7;i++){lv_obj_t *w=lv_label_create(week);buffer_ui_text_role(w,BUFFER_TEXT_LABEL_LARGE);buffer_ui_label_set_text(w,names[i]);buffer_ui_style_text_color(w,lv_color_hex(BUFFER_MUTED),0);
  lv_obj_set_grid_cell(w,LV_GRID_ALIGN_CENTER,i,1,LV_GRID_ALIGN_CENTER,0,1);}
 grid=plain(month_view);buffer_ui_remove_flag(grid,LV_OBJ_FLAG_GESTURE_BUBBLE);lv_obj_set_width(grid,LV_PCT(100));lv_obj_set_height(grid,0);
 lv_obj_set_layout(grid,LV_LAYOUT_GRID);lv_obj_set_grid_dsc_array(grid,cols,grid_rows);
 lv_obj_set_style_pad_row(grid,buffer_ui_dp(2),0);lv_obj_set_style_pad_column(grid,buffer_ui_dp(2),0);lv_obj_add_event_cb(grid,gesture,LV_EVENT_GESTURE,NULL);
 for(unsigned i=0;i<42;i++){struct cell *cell=&cells[i];cell->root=plain(grid);buffer_ui_add_flag(cell->root,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_GESTURE_BUBBLE);
  lv_obj_set_grid_cell(cell->root,LV_GRID_ALIGN_STRETCH,i%7,1,LV_GRID_ALIGN_STRETCH,i/7,1);
  lv_obj_set_style_radius(cell->root,BUFFER_CARD_RADIUS,0);lv_obj_add_event_cb(cell->root,choose,LV_EVENT_CLICKED,cell);
  cell->label=lv_label_create(cell->root);buffer_ui_text_role(cell->label,BUFFER_TEXT_LABEL_LARGE);lv_obj_align(cell->label,LV_ALIGN_CENTER,0,-buffer_ui_dp(3));
  cell->bar=plain(cell->root);lv_obj_set_size(cell->bar,LV_PCT(92),buffer_ui_dp(4));lv_obj_align(cell->bar,LV_ALIGN_BOTTOM_MID,0,-buffer_ui_dp(2));
  visible(cell->bar,false);
  lv_obj_set_style_radius(cell->bar,LV_RADIUS_CIRCLE,0);lv_obj_set_style_clip_corner(cell->bar,true,0);
  for(unsigned j=0;j<BUFFER_CALENDAR_SEGMENTS;j++){cell->segments[j]=plain(cell->bar);buffer_ui_style_bg_opa(cell->segments[j],LV_OPA_COVER,0);}
 }
 agenda=plain(host);lv_obj_set_width(agenda,LV_PCT(100));lv_obj_set_height(agenda,0);lv_obj_set_flex_grow(agenda,1);
 buffer_ui_add_flag(agenda,LV_OBJ_FLAG_SCROLLABLE);lv_obj_set_scroll_dir(agenda,LV_DIR_VER);lv_obj_set_flex_flow(agenda,LV_FLEX_FLOW_COLUMN);lv_obj_set_style_pad_row(agenda,BUFFER_SECTION_GAP,0);
 lv_obj_set_style_min_height(agenda,0,0);lv_obj_set_scrollbar_mode(agenda,LV_SCROLLBAR_MODE_AUTO);
 for(unsigned i=0;i<BUFFER_CALENDAR_PAGE;i++){struct agenda_row *row=&rows[i];row->root=plain(agenda);lv_obj_set_size(row->root,LV_PCT(100),LV_SIZE_CONTENT);
  lv_obj_set_style_pad_ver(row->root,BUFFER_SECTION_GAP,0);lv_obj_set_style_pad_left(row->root,buffer_ui_dp(12),0);lv_obj_set_style_pad_row(row->root,BUFFER_SECTION_GAP,0);
  lv_obj_set_style_border_width(row->root,buffer_ui_dp(1),0);lv_obj_set_style_border_side(row->root,LV_BORDER_SIDE_BOTTOM,0);
  lv_obj_set_style_border_color(row->root,lv_color_hex(BUFFER_PANEL),0);
  lv_obj_set_flex_flow(row->root,LV_FLEX_FLOW_COLUMN);
  row->dot=plain(row->root);buffer_ui_add_flag(row->dot,LV_OBJ_FLAG_FLOATING);lv_obj_set_pos(row->dot,-buffer_ui_dp(10),buffer_ui_dp(2));lv_obj_set_size(row->dot,buffer_ui_dp(4),buffer_ui_dp(24));lv_obj_set_style_radius(row->dot,buffer_ui_dp(2),0);buffer_ui_style_bg_opa(row->dot,LV_OPA_COVER,0);
  row->time=lv_label_create(row->root);buffer_ui_text_role(row->time,BUFFER_TEXT_LABEL_LARGE);
  lv_obj_set_width(row->time,LV_PCT(100));lv_label_set_long_mode(row->time,LV_LABEL_LONG_WRAP);
  row->title=lv_label_create(row->root);buffer_ui_text_role(row->title,BUFFER_TEXT_BODY_LARGE);lv_obj_set_width(row->title,LV_PCT(100));lv_label_set_long_mode(row->title,LV_LABEL_LONG_WRAP);
  row->location=lv_label_create(row->root);buffer_ui_text_role(row->location,BUFFER_TEXT_LABEL_LARGE);lv_obj_set_width(row->location,LV_PCT(100));lv_label_set_long_mode(row->location,LV_LABEL_LONG_WRAP);visible(row->root,false);
 }
 empty=lv_label_create(host);lv_obj_set_size(empty,LV_PCT(100),LV_SIZE_CONTENT);lv_label_set_long_mode(empty,LV_LABEL_LONG_WRAP);buffer_ui_text_role(empty,BUFFER_TEXT_LABEL_LARGE);buffer_ui_style_text_color(empty,lv_color_hex(BUFFER_MUTED),0);
 pager=plain(host);lv_obj_set_size(pager,LV_PCT(100),LV_SIZE_CONTENT);lv_obj_set_style_min_height(pager,buffer_ui_dp(48),0);lv_obj_set_flex_flow(pager,LV_FLEX_FLOW_ROW);visible(pager,false);
 previous=button(pager,"上一页",paginate,(void *)(intptr_t)-1,0);next=button(pager,"下一页",paginate,(void *)(intptr_t)1,0);
 lv_obj_set_style_pad_column(pager,BUFFER_SECTION_GAP,0);lv_obj_set_flex_grow(previous,1);lv_obj_set_flex_grow(next,1);
 lv_obj_add_event_cb(host,resized,LV_EVENT_SIZE_CHANGED,NULL);
 lv_obj_update_layout(host);layout_mode(false);visible(host,false);publish_date();return 0;
}
bool buffer_calendar_ui_open(void){return opened;}
void buffer_calendar_ui_refresh(void)
{
 if(!host)return;
 int request=__atomic_exchange_n(&requested_ui,0,__ATOMIC_ACQ_REL);
 if(request&&request<3&&opened!=(request==1))toggle(NULL);
 if(opened&&request>=3)change_month(request==3?1:-1);
 if(!opened)return;
 if(layout_scale!=buffer_ui_font_scale()||layout_dpi!=lv_display_get_dpi(lv_obj_get_display(host)))layout_dirty=true;
 if(layout_dirty){layout_dirty=false;layout_mode(false);}
 int y,m,d;
 if(today_date(&y,&m,&d)){
  if(!date_known||(follow_today&&(year!=y||month!=m||day!=d))){select_date(y,m,d,expanded);follow_today=true;}
 } else if(!date_known)return;
 for(lv_indev_t *input=lv_indev_get_next(NULL);input;input=lv_indev_get_next(input))if(lv_indev_get_state(input)==LV_INDEV_STATE_PRESSED)return;
 if(lv_obj_is_scrolling(agenda))return;
 struct buffer_fabric_status_s fabric;buffer_fabric_snapshot(&fabric);
 if(connection!=(int)fabric.connected){connection=fabric.connected;rendered=0;}
 buffer_calendar_snapshot(&snapshot);
 if(!snapshot.working&&snapshot.revision!=rendered&&snapshot.year==year&&snapshot.month==month&&snapshot.day==day){
  rendered=snapshot.revision;lv_obj_update_layout(grid);
  time_t now=time(NULL);struct tm today;localtime_r(&now,&today);
  for(unsigned i=0;i<42;i++){struct cell *cell=&cells[i];const struct buffer_calendar_day *date=&snapshot.days[i];
   lv_obj_remove_state(cell->root,LV_STATE_DISABLED);
   cell->year=date->year;cell->month=date->month;cell->day=date->day;char value[12];snprintf(value,sizeof(value),"%d",date->day);buffer_ui_label_set_text(cell->label,value);
   bool selected=date->year==year&&date->month==month&&date->day==day;
   bool current=date->year==today.tm_year+1900&&date->month==today.tm_mon+1&&date->day==today.tm_mday;
   buffer_ui_style_bg_color(cell->root,lv_color_hex(BUFFER_PRIMARY_CONTAINER),0);buffer_ui_style_bg_opa(cell->root,selected?LV_OPA_COVER:LV_OPA_TRANSP,0);
   lv_obj_set_style_border_width(cell->root,current?buffer_ui_dp(1):0,0);lv_obj_set_style_border_color(cell->root,lv_color_hex(BUFFER_ACCENT),0);
   buffer_ui_style_text_color(cell->label,lv_color_hex(date->month==month?BUFFER_ON_SURFACE:BUFFER_MUTED),0);
   visible(cell->bar,date->segments>0);uint64_t total=0;for(unsigned j=0;j<date->segments;j++)total+=date->bar[j].duration;
   int width=lv_obj_get_width(cell->bar),position=0;uint64_t sum=0;
   for(unsigned j=0;j<BUFFER_CALENDAR_SEGMENTS;j++){visible(cell->segments[j],j<date->segments);if(j>=date->segments)continue;
    sum+=date->bar[j].duration;int edge=total?(int)(sum*(uint64_t)width/total):width;
    lv_obj_set_pos(cell->segments[j],position,0);lv_obj_set_size(cell->segments[j],edge-position,buffer_ui_dp(4));position=edge;
    buffer_ui_style_bg_color(cell->segments[j],lv_color_hex(date->bar[j].color),0);
   }
  }
  for(unsigned i=0;i<BUFFER_CALENDAR_PAGE;i++){struct agenda_row *row=&rows[i];visible(row->root,i<snapshot.count);if(i>=snapshot.count)continue;
   const struct buffer_calendar_event *e=&snapshot.events[i];char time_label[144];
   event_time_label(e,time_label,sizeof(time_label));
   char source[330];snprintf(source,sizeof(source),"%s%s%s",e->calendar,e->calendar[0]&&e->location[0]?" · ":"",e->location);
   buffer_ui_label_set_text(row->time,time_label);buffer_ui_label_set_text(row->title,e->title);buffer_ui_label_set_text(row->location,source);visible(row->location,source[0]);
   buffer_ui_style_bg_color(row->dot,lv_color_hex(e->color),0);
  }
  if(transition){buffer_ui_motion_enter_vertical(expanded?month_view:agenda,transition);transition=0;}
  if(fold_pending){buffer_ui_motion_enter(expanded?month_view:agenda,1,false);fold_pending=false;}
  visible(pager,!expanded&&(snapshot.previous||snapshot.next));
  if(snapshot.previous)lv_obj_remove_state(previous,LV_STATE_DISABLED);else lv_obj_add_state(previous,LV_STATE_DISABLED);
  if(snapshot.next)lv_obj_remove_state(next,LV_STATE_DISABLED);else lv_obj_add_state(next,LV_STATE_DISABLED);
  const char *notice=!fabric.connected?"手机未连接 · 显示已同步日程":snapshot.sync_error==-EACCES?"请在手机授权读取系统日历 · 待办仍可同步":snapshot.sync_error==-EIO?"手机系统日历读取失败 · 显示已同步日程":snapshot.sync_error?"正在同步日历 · 显示本地缓存":NULL;
  buffer_ui_label_set_text(empty,notice?notice:snapshot.error?"日历读取失败，请稍后重试":snapshot.working?"正在读取日历…":!expanded&&!snapshot.count?"当天暂无日程":"");
  bool caption=lv_label_get_text(empty)[0]!=0;
  bool was_caption=!lv_obj_has_flag(empty,LV_OBJ_FLAG_HIDDEN);
  visible(empty,caption);
  if(caption!=was_caption)layout_dirty=true;
 }
 if(lv_tick_elaps(refreshed)>3000){refreshed=lv_tick_get();buffer_calendar_refresh();}
}
void buffer_calendar_ui_detach(void)
{
 if(!host)return;
 buffer_calendar_stop();lv_obj_delete(host);lv_obj_delete(launcher);host=entry=launcher=NULL;opened=false;
}
