/* SPDX-License-Identifier: Apache-2.0 */
#include "third_party/libical/ical.h"
#define SERIES_OCCURRENCES 16384
struct series_times {int64_t *values;unsigned count;};
static bool series_time_add(struct series_times *times,int64_t value)
{
 if(times->count==SERIES_OCCURRENCES)return false;
 times->values[times->count++]=value;return true;
}
static int series_compare(const void *a,const void *b)
{int64_t x=*(const int64_t *)a,y=*(const int64_t *)b;return x>y?1:x<y?-1:0;}
static icaltimezone *series_zone(const cJSON *series)
{
 const cJSON *offsets=cJSON_GetObjectItemCaseSensitive(series,"offsets");
 if(!cJSON_IsArray(offsets)||!cJSON_GetArraySize(offsets)||cJSON_GetArraySize(offsets)>1024)return NULL;
 icalcomponent *component=icalcomponent_new_vtimezone();
 if(!component)return NULL;
 icalcomponent_add_property(component,icalproperty_new_tzid("Buffer/Calendar"));
 int previous=0;int64_t previous_at=-1;const cJSON *entry;unsigned i=0;
 cJSON_ArrayForEach(entry,offsets){
  const cJSON *at=cJSON_GetArrayItem(entry,0),*offset=cJSON_GetArrayItem(entry,1);
  if(!cJSON_IsArray(entry)||cJSON_GetArraySize(entry)!=2||!cJSON_IsNumber(at)||!cJSON_IsNumber(offset)||
    !isfinite(at->valuedouble)||floor(at->valuedouble)!=at->valuedouble||at->valuedouble<0||at->valuedouble>7258118400.0||
    !isfinite(offset->valuedouble)||floor(offset->valuedouble)!=offset->valuedouble||fabs(offset->valuedouble)>86400||
    (!i&&at->valuedouble!=0)||at->valuedouble<=previous_at){icalcomponent_free(component);return NULL;}
  previous_at=(int64_t)at->valuedouble;
  int current=(int)offset->valuedouble;if(!i)previous=current;
  icalcomponent *standard=icalcomponent_new_xstandard();if(!standard){icalcomponent_free(component);return NULL;}
  struct icaltimetype wall=icaltime_from_timet_with_zone((time_t)(at->valuedouble+previous),0,NULL);
  icalcomponent_add_property(standard,icalproperty_new_dtstart(wall));
  icalcomponent_add_property(standard,icalproperty_new_tzoffsetfrom(previous));
  icalcomponent_add_property(standard,icalproperty_new_tzoffsetto(current));
  icalcomponent_add_component(component,standard);previous=current;i++;
 }
 icaltimezone *zone=icaltimezone_new();
 if(!zone||!icaltimezone_set_component(zone,component)){if(zone)icaltimezone_free(zone,1);icalcomponent_free(component);return NULL;}
 return zone;
}
static int series_single_rule(const char *encoded,struct icaltimetype start,icaltimezone *zone,
                       int64_t from,int64_t to,struct series_times *times)
{
 if(!*encoded)return 0;
 struct icalrecurrencetype rule=icalrecurrencetype_from_string(encoded);
 if(rule.freq==ICAL_NO_RECURRENCE)return -EINVAL;
 char *rscale=rule.rscale;
 icalrecur_iterator *iterator=icalrecur_iterator_new(rule,start);icalrecurrencetype_clear(&rule);
 if(!iterator){free(rscale);return -EINVAL;}
 /* set_start preserves COUNT semantics by refusing to skip counted rules. */
 (void)icalrecur_iterator_set_start(iterator,icaltime_from_timet_with_zone((time_t)(from/1000),0,zone));
 int result=0;unsigned work=0;
 for(;;){
  if(++work>1000000){result=-E2BIG;break;}
  struct icaltimetype date=icalrecur_iterator_next(iterator);if(icaltime_is_null_time(date))break;
  int64_t value=(int64_t)icaltime_as_timet_with_zone(date,zone)*1000;if(value>=to)break;
  if(value>=from&&!series_time_add(times,value)){result=-E2BIG;break;}
 }
 icalrecur_iterator_free(iterator);free(rscale);return result;
}
static int series_rule(const char *encoded,struct icaltimetype start,icaltimezone *zone,
                       int64_t from,int64_t to,struct series_times *times)
{
 if(!*encoded)return 0;
 if(strlen(encoded)>8192)return -E2BIG;
 char *rules=strdup(encoded);if(!rules)return -ENOMEM;
 char *save=NULL;int result=0;unsigned count=0;
 for(char *rule=strtok_r(rules,"\r\n",&save);rule;rule=strtok_r(NULL,"\r\n",&save)){
  if(++count>32){result=-E2BIG;break;}
  result=series_single_rule(rule,start,zone,from,to,times);if(result)break;
 }
 free(rules);return result;
}
static int series_dates(const cJSON *series,const char *key,struct series_times *times,int64_t from,int64_t to)
{
 const cJSON *dates=cJSON_GetObjectItemCaseSensitive(series,key),*date;
 if(!cJSON_IsArray(dates))return -EINVAL;
 cJSON_ArrayForEach(date,dates){
  if(!cJSON_IsNumber(date)||!isfinite(date->valuedouble)||floor(date->valuedouble)!=date->valuedouble||date->valuedouble < -9007199254740991.0||date->valuedouble>9007199254740991.0)return -EINVAL;
  if(date->valuedouble>=from&&date->valuedouble<to&&!series_time_add(times,(int64_t)date->valuedouble))return -E2BIG;
 }
 return 0;
}
static int expand_series(const cJSON *series,const struct buffer_calendar_event *base,
                         struct buffer_calendar_view *out,const int64_t bounds[43],int64_t ds,int64_t de,
                         int64_t cursor,const char *id,int direction)
{
 if(number(series,"version")!=1)return -EPROTONOSUPPORT;
 const cJSON *days=cJSON_GetObjectItemCaseSensitive(series,"days");
 if(!cJSON_IsNumber(days)||!isfinite(days->valuedouble)||floor(days->valuedouble)!=days->valuedouble||
    days->valuedouble<0||days->valuedouble>84006||(base->all_day&&days->valuedouble<1))return -EINVAL;
 icaltimezone *zone=series_zone(series);if(!zone)return -EINVAL;
 int result=0;struct series_times include={calloc(SERIES_OCCURRENCES,sizeof(int64_t)),0},exclude={calloc(SERIES_OCCURRENCES,sizeof(int64_t)),0};
 if(!include.values||!exclude.values){result=-ENOMEM;goto done;}
 struct icaltimetype start=icaltime_from_string(text(series,"local_start"));
 if(icaltime_is_null_time(start)||!icaltime_is_valid_time(start)){result=-EINVAL;goto done;}
 start.zone=zone;start.is_date=0;
 int64_t duration=base->end-base->start,lookback=base->all_day?(number(series,"days")+1)*86400000LL:duration;
 int64_t from=bounds[0]-lookback,to=bounds[42];
 (void)series_time_add(&include,base->start);
 result=series_rule(text(series,"rrule"),start,zone,from,to,&include);if(result)goto done;
 result=series_rule(text(series,"exrule"),start,zone,from,to,&exclude);if(result)goto done;
 result=series_dates(series,"rdates",&include,from,to);if(result)goto done;
 result=series_dates(series,"exdates",&exclude,from,to);if(result)goto done;
 const cJSON *exceptions=cJSON_GetObjectItemCaseSensitive(series,"exceptions"),*exception;
 if(!cJSON_IsArray(exceptions)){result=-EINVAL;goto done;}
 cJSON_ArrayForEach(exception,exceptions){if(!series_time_add(&exclude,number(exception,"original"))){result=-E2BIG;goto done;}}
 qsort(include.values,include.count,sizeof(int64_t),series_compare);qsort(exclude.values,exclude.count,sizeof(int64_t),series_compare);
 for(unsigned i=0;i<include.count;i++){
  int64_t at=include.values[i];if((i&&at==include.values[i-1])||at<from||at>=to||bsearch(&at,exclude.values,exclude.count,sizeof(int64_t),series_compare))continue;
  struct buffer_calendar_event event=*base;event.start=at;event.end=at+duration;
  if(base->all_day){struct icaltimetype end=icaltime_from_timet_with_zone((time_t)(at/1000),0,zone);icaltime_adjust(&end,(int)number(series,"days"),0,0,0);event.end=(int64_t)icaltime_as_timet_with_zone(end,zone)*1000;}
  snprintf(event.id,sizeof(event.id),"%.50s_%lld",base->id,(long long)at);
  add_event(out,&event,bounds,ds,de,cursor,id,direction);
 }
 cJSON_ArrayForEach(exception,exceptions){
  if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(exception,"cancelled")))continue;
  const cJSON *schedule=cJSON_GetObjectItemCaseSensitive(exception,"schedule");struct buffer_calendar_event event=*base;
  event.start=number(schedule,"start");event.end=number(schedule,"end");if(event.end<event.start){result=-EINVAL;goto done;}
  copy(event.title,sizeof(event.title),text(exception,"title"));copy(event.location,sizeof(event.location),text(exception,"location"));
  event.all_day=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(schedule,"all_day"));
  snprintf(event.id,sizeof(event.id),"%.50s_%lld",base->id,(long long)number(exception,"original"));
  add_event(out,&event,bounds,ds,de,cursor,id,direction);
 }
done:
 free(include.values);free(exclude.values);icaltimezone_free(zone,1);return result;
}
