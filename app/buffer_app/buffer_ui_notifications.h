/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_UI_NOTIFICATIONS_H
#define BUFFER_UI_NOTIFICATIONS_H
#include <lvgl/lvgl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#define BUFFER_NOTIFICATION_LIMIT 24
#define BUFFER_NOTIFICATION_ACTIONS 2
#define BUFFER_NOTIFICATION_KEY 80

enum buffer_notification_style {BUFFER_NOTICE_BASIC,BUFFER_NOTICE_BIG_TEXT,BUFFER_NOTICE_PROGRESS};
enum buffer_notification_phase {BUFFER_NOTICE_RUNNING,BUFFER_NOTICE_PAUSED,BUFFER_NOTICE_COMPLETE,BUFFER_NOTICE_ERROR};
enum buffer_notification_importance {BUFFER_NOTICE_LOW,BUFFER_NOTICE_NORMAL,BUFFER_NOTICE_HIGH};
enum buffer_notification_privacy {BUFFER_NOTICE_PUBLIC,BUFFER_NOTICE_SENSITIVE};
struct buffer_notification_action {char id[24],label[32],route[32],object[80];};
struct buffer_notification {
 char key[BUFFER_NOTIFICATION_KEY],owner[32],channel[24],status[48],title[96],text[256],artwork[96];
 enum buffer_notification_style style;
 enum buffer_notification_phase phase;
 enum buffer_notification_importance importance;
 enum buffer_notification_privacy privacy;
 bool live,desktop,determinate;
 uint32_t progress,progress_max,expires_after_ms;
 unsigned action_count;
 /* Whole-card navigation is independent of its two contextual controls. */
 struct buffer_notification_action open_action;
 struct buffer_notification_action actions[BUFFER_NOTIFICATION_ACTIONS];
};
/* All APIs below run on the LVGL owner thread. Payloads describe content and
 * actions, never global coordinates. Removing a display never cancels work. */
int buffer_notification_build(struct buffer_notification *notice,const char *key,
                              const char *owner,const char *title,const char *text);
void buffer_notification_text(char *target,size_t capacity,const char *source);
int buffer_notification_publish(const struct buffer_notification *notice,uint64_t now_ms);
void buffer_notification_remove(const char *key);
void buffer_notification_dismiss(const char *key);
unsigned buffer_notification_begin(const char *owner);
void buffer_notification_end(const char *owner,unsigned batch);
bool buffer_notification_get(const char *key,struct buffer_notification *out);
size_t buffer_notification_snapshot(struct buffer_notification *out,size_t capacity,
                                    bool desktop,bool allow_sensitive,uint64_t now_ms);
void buffer_notification_reset(void);

typedef void (*buffer_notification_dispatch)(const struct buffer_notification_action *action,void *context);
struct buffer_notification_view {
 bool desktop;
 lv_obj_t *root,*status,*title,*text,*progress,*actions;
 lv_obj_t *buttons[BUFFER_NOTIFICATION_ACTIONS];
 char key[BUFFER_NOTIFICATION_KEY];
 struct buffer_notification_action bound_open;
 struct buffer_notification_action bound[BUFFER_NOTIFICATION_ACTIONS];
 buffer_notification_dispatch dispatch;void *context;
 lv_indev_t *swipe_input;lv_point_t swipe_start;
 char swipe_key[BUFFER_NOTIFICATION_KEY];
 bool swipe_cancelled;
};
void buffer_notification_view_create(struct buffer_notification_view *view,lv_obj_t *parent,
                                      buffer_notification_dispatch dispatch,void *context);
void buffer_notification_view_bind(struct buffer_notification_view *view,const struct buffer_notification *notice);
#endif
