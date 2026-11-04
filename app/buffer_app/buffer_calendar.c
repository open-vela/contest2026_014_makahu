/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_calendar.h"
#include "buffer_agent.h"
#include "buffer_fabric.h"
#include <dirent.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t wake=PTHREAD_COND_INITIALIZER;
static pthread_t worker;
static bool started,quit,requested,needs_sync,calendar_visible=true;
static uint64_t generation,revision;
static int sync_error=-EAGAIN;
static int requested_year,requested_month,requested_day,order=1;
static bool expanded;
static int64_t anchor_start,range_start,range_end;
static time_t sync_completed_at;
static char anchor_id[BUFFER_ID_SIZE];
static struct buffer_calendar_view view,building;
static const char *text(const cJSON *o,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(o,key);return cJSON_IsString(v)?v->valuestring:"";}
static int64_t number(const cJSON *o,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(o,key);return cJSON_IsNumber(v)&&isfinite(v->valuedouble)&&v->valuedouble>=-9007199254740991.0&&v->valuedouble<=9007199254740991.0&&floor(v->valuedouble)==v->valuedouble?(int64_t)v->valuedouble:0;}
int buffer_calendar_month_days(int year,int month)
{static const int days[]={31,28,31,30,31,30,31,31,30,31,30,31};if(year<1970||year>2199||month<1||month>12)return 0;
 return days[month-1]+(month==2 && year%4==0 && (year%100!=0 || year%400==0));}
int buffer_calendar_shift(int *year,int *month,int delta)
{if(!year||!month||!buffer_calendar_month_days(*year,*month)||delta < -12||delta>12)return -EINVAL;
 int value=*year*12+*month-1+delta,y=value/12,m=value%12+1;if(!buffer_calendar_month_days(y,m))return -ERANGE;*year=y;*month=m;return 0;}
void buffer_calendar_grid_dates(int y,int m,struct buffer_calendar_day days[BUFFER_CALENDAR_DAYS])
{
 if(!days)return;
 if(!buffer_calendar_month_days(y,m)){memset(days,0,sizeof(*days)*BUFFER_CALENDAR_DAYS);return;}
 static const int offsets[]={0,3,2,5,0,3,5,1,4,6,2,4};
 int adjusted=y-(m<3);
 int first=(adjusted+adjusted/4-adjusted/100+adjusted/400+offsets[m-1]+1)%7;
 int d=1-(first+6)%7;
 for(unsigned i=0;i<BUFFER_CALENDAR_DAYS;i++,d++){
  int cy=y,cm=m,cd=d;
  if(cd<1){if(--cm<1){cm=12;cy--;}cd+=buffer_calendar_month_days(cy<1970?1970:cy,cm);}
  else if(cd>buffer_calendar_month_days(y,m)){cd-=buffer_calendar_month_days(y,m);if(++cm>12){cm=1;cy++;}}
  memset(&days[i],0,sizeof(days[i]));days[i].year=cy;days[i].month=cm;days[i].day=cd;
 }
}
static int64_t midnight(int y,int m,int d)
{struct tm t={0};t.tm_year=y-1900;t.tm_mon=m-1;t.tm_mday=d;t.tm_isdst=-1;time_t value=mktime(&t);return value<0?0:(int64_t)value*1000;}
static void date_at(int64_t at,int *y,int *m,int *d)
{time_t value=(time_t)(at/1000);struct tm t;localtime_r(&value,&t);*y=t.tm_year+1900;*m=t.tm_mon+1;*d=t.tm_mday;}
static uint32_t color(const char *id)
{uint32_t hash=0x811c9dc5;while(*id)hash=(hash^(unsigned char)*id++)*16777619;hash^=hash>>16;
 return ((64+(hash&127))<<16)|((64+((hash>>8)&127))<<8)|(64+((hash>>16)&127));}
static void copy(char *out,size_t capacity,const char *in)
{size_t n=strlen(in);if(n>=capacity){n=capacity-1;while(n&&((unsigned char)in[n]&0xc0)==0x80)n--;}memcpy(out,in,n);out[n]=0;}
static int compare(const struct buffer_calendar_event *a,const struct buffer_calendar_event *b)
{return a->start>b->start?1:a->start<b->start?-1:strcmp(a->id,b->id);}
static void add_event(struct buffer_calendar_view *out,const struct buffer_calendar_event *event,
                      const int64_t bounds[43],int64_t day_start,int64_t day_end,
                      int64_t cursor,const char *id,int direction)
{
  for(unsigned i=0;i<42;i++)if(event->start<bounds[i+1]&&(event->end>bounds[i]||(event->end==event->start&&event->start>=bounds[i]))){
      struct buffer_calendar_day *day=&out->days[i];day->count++;
      uint64_t duration=(uint64_t)((event->end<bounds[i+1]?event->end:bounds[i+1])-(event->start>bounds[i]?event->start:bounds[i]));
      unsigned at=day->segments;
      if(at<BUFFER_CALENDAR_SEGMENTS){day->bar[at].color=event->color;day->bar[at].duration=duration;day->segments++;}
      else {day->bar[BUFFER_CALENDAR_SEGMENTS-1].color=0x87918A;day->bar[BUFFER_CALENDAR_SEGMENTS-1].duration+=duration;}
  }
  if(event->start>=day_end||event->end<day_start||(event->end==day_start&&event->end!=event->start))return;
  out->total++;
  struct buffer_calendar_event anchor={0};anchor.start=cursor;copy(anchor.id,sizeof(anchor.id),id);
  int relation=id[0]?compare(event,&anchor):direction;
  if(id[0]&&(direction>0?relation<=0:relation>=0)) {if(direction>0)out->previous=true;else out->next=true;return;}
  unsigned at=0;while(at<out->count&&direction*compare(&out->events[at],event)<0)at++;
  if(out->count==BUFFER_CALENDAR_PAGE){if(direction>0)out->next=true;else out->previous=true;}
  if(at>=BUFFER_CALENDAR_PAGE)return;
  unsigned n=out->count<BUFFER_CALENDAR_PAGE?out->count:BUFFER_CALENDAR_PAGE-1;
  while(n>at){out->events[n]=out->events[n-1];n--;}out->events[at]=*event;
  if(out->count<BUFFER_CALENDAR_PAGE)out->count++;
}
#include "buffer_calendar_series.inc"
static int scan(const char *directory,struct buffer_calendar_view *out,const int64_t bounds[43],int64_t ds,int64_t de,
                 int64_t cursor,const char *id,int direction,uint64_t expected)
{
  DIR *dir=opendir(directory);if(!dir)return errno==ENOENT?0:-errno;
  struct dirent *entry;int ret=0;
  while((entry=readdir(dir))){
      pthread_mutex_lock(&lock);bool current=calendar_visible&&expected==generation&&!quit;pthread_mutex_unlock(&lock);
      if(!current){ret=-ECANCELED;break;}
      size_t n=strlen(entry->d_name);if(n<6||n-5>=BUFFER_ID_SIZE||strcmp(entry->d_name+n-5,".json"))continue;
      char path[384];int len=snprintf(path,sizeof(path),"%s/%s",directory,entry->d_name);if(len<0||(size_t)len>=sizeof(path))continue;
      errno=0;cJSON *record=buffer_agent_read_json(path,BUFFER_AGENT_JSON_BYTES);
      if(!record){if(errno!=ENOENT)ret=errno?-errno:-EIO;continue;}
      const cJSON *card=cJSON_GetObjectItemCaseSensitive(record,"card"),*schedule=cJSON_GetObjectItemCaseSensitive(card,"schedule");
      const char *kind=text(card,"kind"),*state=text(card,"state");
      if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted"))||!strcmp(state,"deleted")||
          (strcmp(kind,"task_card")&&strcmp(kind,"calendar_event"))||!cJSON_IsObject(schedule)){cJSON_Delete(record);continue;}
      // System calendar entries linked to a dated task share its projection.
      const char *linked=text(card,"linked_card_id");
      if(linked[0]&&!strcmp(kind,"calendar_event")){
          char target[320];snprintf(target,sizeof(target),"%s/replicas/%s.json",CONFIG_BUFFER_VELA_DATA_DIR,linked);
          cJSON *task=buffer_agent_valid_id(linked)?buffer_agent_read_json(target,BUFFER_AGENT_JSON_BYTES):NULL;
          bool duplicate=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(task,"deleted")) || cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(cJSON_GetObjectItemCaseSensitive(task,"card"),"schedule"));
          cJSON_Delete(task);if(duplicate){cJSON_Delete(record);continue;}
      }
      struct buffer_calendar_event event={0};event.start=number(schedule,"start");event.end=number(schedule,"end");
      copy(event.id,sizeof(event.id),text(record,"card_id"));copy(event.title,sizeof(event.title),text(card,"title"));
      copy(event.location,sizeof(event.location),text(card,"location"));event.color=color(event.id);
      copy(event.calendar,sizeof(event.calendar),text(card,"calendar_name"));
      event.all_day=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(schedule,"all_day"));event.done=!strcmp(state,"done");
      if(event.end>=event.start&&buffer_agent_valid_id(event.id)&&event.title[0]){
       const cJSON *series=cJSON_GetObjectItemCaseSensitive(card,"recurrence");
       if(cJSON_IsObject(series)){int expanded=expand_series(series,&event,out,bounds,ds,de,cursor,id,direction);if(expanded)ret=expanded;}
       else add_event(out,&event,bounds,ds,de,cursor,id,direction);
      }
      cJSON_Delete(record);
  }closedir(dir);return ret;
}
static void *run(void *unused)
{
 (void)unused;pthread_mutex_lock(&lock);
 while(!quit){while((!requested||!calendar_visible)&&!quit){
   if(calendar_visible&&needs_sync&&requested_year){struct timespec until;clock_gettime(CLOCK_REALTIME,&until);until.tv_sec+=3;
    if(pthread_cond_timedwait(&wake,&lock,&until)==ETIMEDOUT)requested=true;
   }else pthread_cond_wait(&wake,&lock);
  }if(quit)break;
  int y=requested_year,m=requested_month,d=requested_day,direction=order;bool exp=expanded;
  uint64_t expected=generation;int64_t cursor=anchor_start;char id[BUFFER_ID_SIZE];strcpy(id,anchor_id);requested=false;
  bool sync=needs_sync;view.working=true;view.revision=++revision;pthread_mutex_unlock(&lock);
  memset(&building,0,sizeof(building));building.year=y;building.month=m;building.day=d;building.expanded=exp;
  struct tm first={0};first.tm_year=y-1900;first.tm_mon=m-1;first.tm_mday=1;first.tm_isdst=-1;(void)mktime(&first);
  int offset=(first.tm_wday+6)%7;int64_t bounds[43];
  for(int i=0;i<43;i++){bounds[i]=midnight(y,m,1-offset+i);if(i<42)date_at(bounds[i],&building.days[i].year,&building.days[i].month,&building.days[i].day);}
  int64_t ds=midnight(y,m,d),de=midnight(y,m,d+1);
  pthread_mutex_lock(&lock);
  bool current=calendar_visible&&expected==generation;
  if(!current)continue; /* The loop/condition wait always owns lock. */
  range_start=bounds[0];range_end=bounds[42];
  pthread_mutex_unlock(&lock);
  if(sync)(void)buffer_fabric_request_sync();
  int result=scan(CONFIG_BUFFER_VELA_DATA_DIR "/replicas",&building,bounds,ds,de,cursor,id,direction,expected);
  int other=result==-ECANCELED?0:scan(CONFIG_BUFFER_VELA_DATA_DIR "/calendars",&building,bounds,ds,de,cursor,id,direction,expected);
  if(!result)result=other;
  if(direction<0)for(unsigned i=0;i<building.count/2;i++){struct buffer_calendar_event tmp=building.events[i];building.events[i]=building.events[building.count-i-1];building.events[building.count-i-1]=tmp;}
  building.error=result;pthread_mutex_lock(&lock);
  if(expected==generation){building.revision=++revision;view=building;range_start=bounds[0];range_end=bounds[42];}
 }
 pthread_mutex_unlock(&lock);return NULL;
}
int buffer_calendar_start(void)
{
 pthread_mutex_lock(&lock);if(started){pthread_mutex_unlock(&lock);return 0;}quit=false;requested=false;calendar_visible=true;
 requested_year=requested_month=requested_day=0;range_start=range_end=0;anchor_start=0;anchor_id[0]=0;order=1;
 needs_sync=true;sync_error=-EAGAIN;sync_completed_at=0;generation++;memset(&view,0,sizeof(view));
 int ret=buffer_thread_create(&worker,run,NULL);
 if(!ret)started=true;
 pthread_mutex_unlock(&lock);
 return ret?-ret:0;
}
/* UI observation owns projection scans, not durable synchronization. Hidden
 * pages retain a dirty request and resume once, instead of repeatedly reading
 * every record while the same storage is needed by incoming synchronization. */
void buffer_calendar_set_visible(bool visible)
{
 pthread_mutex_lock(&lock);
 if(calendar_visible!=visible){calendar_visible=visible;generation++;view.working=false;
  if(visible&&requested_year)requested=true;
  pthread_cond_signal(&wake);
 }
 pthread_mutex_unlock(&lock);
}
void buffer_calendar_stop(void)
{pthread_mutex_lock(&lock);bool join=started;quit=true;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);if(join)pthread_join(worker,NULL);
 pthread_mutex_lock(&lock);started=false;range_start=range_end=0;pthread_mutex_unlock(&lock);}
void buffer_calendar_select(int year,int month,int day,bool exp)
{
 int days=buffer_calendar_month_days(year,month);if(!days||day<1||day>days)return;
 pthread_mutex_lock(&lock);bool changed=year!=requested_year||month!=requested_month;
 requested_year=year;requested_month=month;requested_day=day;expanded=exp;anchor_id[0]=0;anchor_start=0;order=1;
 generation++;requested=true;if(changed){needs_sync=true;sync_error=-EAGAIN;}pthread_cond_signal(&wake);
 if(changed)range_start=range_end=0;
 pthread_mutex_unlock(&lock);
}
void buffer_calendar_page(int direction)
{
 pthread_mutex_lock(&lock);if(view.count){const struct buffer_calendar_event *event=&view.events[direction>0?view.count-1:0];anchor_start=event->start;strcpy(anchor_id,event->id);order=direction>0?1:-1;
 generation++;requested=true;pthread_cond_signal(&wake);}pthread_mutex_unlock(&lock);
}
void buffer_calendar_snapshot(struct buffer_calendar_view *out)
{pthread_mutex_lock(&lock);*out=view;out->working=view.working||requested;out->sync_error=sync_error;pthread_mutex_unlock(&lock);}
bool buffer_calendar_range(int64_t *start,int64_t *end)
{pthread_mutex_lock(&lock);bool valid=range_end>range_start;*start=range_start;*end=range_end;pthread_mutex_unlock(&lock);return valid;}

void buffer_calendar_invalidate(void)
{
  pthread_mutex_lock(&lock);generation++;requested=true;view.working=true;view.count=view.total=0;
  view.previous=view.next=false;memset(view.events,0,sizeof(view.events));
  for(unsigned i=0;i<BUFFER_CALENDAR_DAYS;i++){view.days[i].count=view.days[i].segments=0;memset(view.days[i].bar,0,sizeof(view.days[i].bar));}
  view.revision=++revision;pthread_cond_signal(&wake);pthread_mutex_unlock(&lock);
}
void buffer_calendar_refresh(void)
{pthread_mutex_lock(&lock);if(!sync_completed_at||time(NULL)-sync_completed_at>=60)needs_sync=true;
 if(!view.working&&!requested){generation++;requested=true;pthread_cond_signal(&wake);}pthread_mutex_unlock(&lock);}

void buffer_calendar_sync_status(int error)
{pthread_mutex_lock(&lock);sync_error=error;if(!error){needs_sync=false;sync_completed_at=time(NULL);}view.revision=++revision;pthread_mutex_unlock(&lock);}
