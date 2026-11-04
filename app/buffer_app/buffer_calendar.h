/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_CALENDAR_H
#define BUFFER_CALENDAR_H
#include "buffer_app.h"
#define BUFFER_CALENDAR_DAYS 42
#define BUFFER_CALENDAR_SEGMENTS 12
#define BUFFER_CALENDAR_PAGE 8
struct buffer_calendar_segment { uint32_t color; uint64_t duration; };
struct buffer_calendar_day { int year,month,day; unsigned count,segments; struct buffer_calendar_segment bar[BUFFER_CALENDAR_SEGMENTS]; };
struct buffer_calendar_event { char id[BUFFER_ID_SIZE],title[161],location[161],calendar[161]; int64_t start,end; bool all_day,done; uint32_t color; };
struct buffer_calendar_view {
  uint64_t revision;int year,month,day;bool expanded,working,previous,next;int error,sync_error;
  unsigned count,total;struct buffer_calendar_day days[BUFFER_CALENDAR_DAYS];
  struct buffer_calendar_event events[BUFFER_CALENDAR_PAGE];
};
int buffer_calendar_start(void);
void buffer_calendar_stop(void);
void buffer_calendar_set_visible(bool visible);
void buffer_calendar_select(int year,int month,int day,bool expanded);
void buffer_calendar_page(int direction);
void buffer_calendar_refresh(void);
/* Fence an in-flight read and drop its stale display cache. */
void buffer_calendar_invalidate(void);
void buffer_calendar_sync_status(int error);
void buffer_calendar_snapshot(struct buffer_calendar_view *out);
bool buffer_calendar_range(int64_t *start,int64_t *end);
int buffer_calendar_month_days(int year,int month);
void buffer_calendar_grid_dates(int year,int month,struct buffer_calendar_day days[BUFFER_CALENDAR_DAYS]);
unsigned buffer_calendar_ui_state(void);
void buffer_calendar_ui_request(bool open);
void buffer_calendar_ui_request_month(int direction);
int buffer_calendar_shift(int *year,int *month,int delta);
int buffer_calendar_ui_attach(void *page,void *launchers);
void buffer_calendar_ui_refresh(void);
bool buffer_calendar_ui_open(void);
void buffer_calendar_ui_detach(void);
#endif
