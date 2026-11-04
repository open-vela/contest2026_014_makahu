#include "buffer_voice.h"
/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela application
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_live_asr.h"
#include "buffer_home_key.h"
#if defined(CONFIG_DRIVERS_LRADC) && defined(CONFIG_ARCH_BOARD_R528S3_GEMINI_S1)
#include <nuttx/input/buttons.h>
#include <poll.h>
#endif
#include "buffer_followup.h"
#include "buffer_todo.h"
#include "buffer_calendar.h"
#include "buffer_crdt_sync.h"
#include "buffer_agent.h"
#include "buffer_replica.h"
#include "buffer_fabric.h"
#include "buffer_backlight.h"
#include "buffer_source_events.h"
#include "buffer_source_view.h"
#include "buffer_entropy.h"
#include "buffer_private_storage.h"

#include <lvgl/lvgl.h>

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <sched.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>

struct buffer_runtime_s g_buffer =
{
  .lock = PTHREAD_MUTEX_INITIALIZER,
  .mode = "normal",
  .status = "Buffer 正在启动",
};

static pthread_mutex_t g_cards_commit_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_mode_commit_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_ui_owner_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_ui_owner_changed = PTHREAD_COND_INITIALIZER;
static bool g_ui_owner_active;
static bool g_ui_owner_closing;
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
static bool source_startup_pending;
static pthread_mutex_t g_focus_runtime_commit_lock = PTHREAD_MUTEX_INITIALIZER;
#endif

static void buffer_ui_owner_done(void)
{
  pthread_mutex_lock(&g_ui_owner_lock);
  g_ui_owner_active = false;
  g_ui_owner_closing = false;
  pthread_cond_broadcast(&g_ui_owner_changed);
  pthread_mutex_unlock(&g_ui_owner_lock);
}

static void *buffer_record_thread_main(void *arg)
{
  uint32_t duration = 0;
  char capture_id[BUFFER_ID_SIZE];
  char mode[BUFFER_MODE_SIZE];
  char context_card[BUFFER_ID_SIZE];
  enum buffer_capture_target_e target;
  int ret;

  (void)arg;
  pthread_mutex_lock(&g_buffer.lock);
  snprintf(capture_id, sizeof(capture_id), "%s", g_buffer.record_id);
  snprintf(mode, sizeof(mode), "%s", g_buffer.record_mode);
  snprintf(context_card, sizeof(context_card), "%s", g_buffer.record_context_card);
  target=context_card[0]?BUFFER_CAPTURE_TARGET_ASSISTANT:g_buffer.record_target;
  pthread_mutex_unlock(&g_buffer.lock);
  ret = context_card[0] ? buffer_followup_prepare(capture_id, context_card) : 0;
  bool audio_started = ret == 0;
  if (!ret) ret = buffer_audio_record_cancelable(capture_id, mode,
                            target,
                            &g_buffer.record_stop, &duration,
                            &g_buffer.record_decision);
  if(ret<0)buffer_voice_update(capture_id,ret==-ECANCELED?BUFFER_VOICE_CANCELLED:BUFFER_VOICE_ERROR,NULL,ret);
  int context_error=ret<0&&context_card[0]?buffer_followup_cancel(capture_id):0;
  int pending=buffer_store_pending_count();
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.recording = false;
  g_buffer.pending_count = pending;
  pthread_mutex_unlock(&g_buffer.lock);
  if(ret==0)buffer_agent_wake();
  if(context_error<0)buffer_set_status("录音已结束，追问上下文清理待恢复：%d",context_error);
  else if (ret == -ECANCELED) buffer_set_status("已取消录音，未加入队列");
  else if (ret < 0)
    {
      if (!audio_started) buffer_set_status("录音上下文准备失败：%d", ret);
    }
  return NULL;
}

static int64_t buffer_monotonic_ms(void)
{
  struct timespec now;

  if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
    {
      return (int64_t)time(NULL) * 1000;
    }

  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int buffer_generate_action_id(char *action_id, size_t action_id_size)
{
  uint32_t random_value;
  int ret;

  if (action_id == NULL || action_id_size == 0)
    {
      return -EINVAL;
    }

  arc4random_buf(&random_value, sizeof(random_value));
  ret = snprintf(action_id, action_id_size, "act-%08lx-%08lx",
                 (unsigned long)time(NULL), (unsigned long)random_value);
  return ret < 0 || (size_t)ret >= action_id_size ? -ENAMETOOLONG : 0;
}

bool buffer_card_supports_action(const struct buffer_card_s *card,
                                 const char *action)
{
  const char *cursor;
  size_t action_length;

  if (card == NULL || action == NULL || action[0] == '\0')
    {
      return false;
    }

  /* Cards written by older firmware have no action list.  Preserve their
   * original behavior until the next phone refreshes the deck. */
  if (card->actions[0] == '\0')
    {
      return true;
    }

  action_length = strlen(action);
  cursor = card->actions;
  while (*cursor != '\0')
    {
      const char *start;
      size_t length;

      while (*cursor == ',')
        {
          cursor++;
        }
      start = cursor;
      while (*cursor != '\0' && *cursor != ',')
        {
          cursor++;
        }
      length = (size_t)(cursor - start);
      if (length == action_length && strncmp(start, action, length) == 0)
        {
          return true;
        }
    }

  return false;
}

static bool research_known,research_display_expired;
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
/* Shared process identity and timing anchors, also used by focus reminders. */
static char focus_runtime_boot[33];
static int focus_runtime_boot_init(void);
static int64_t focus_wall_ms(void);
static bool focus_runtime_number(const cJSON *value,const char *key,int64_t max,int64_t *out);
#define RESEARCH_RUNTIME_FILE CONFIG_BUFFER_VELA_DATA_DIR "/research.json"
static bool research_reconcile=true;
#endif
static bool valid_research(const struct buffer_research_s *value)
{
  if(!value||value->remaining_ms>7200000u||!memchr(value->id,0,sizeof(value->id))||
     !memchr(value->title,0,sizeof(value->title))||!memchr(value->reason,0,sizeof(value->reason)))return false;
  if(!value->present)return !value->active&&!value->remaining_ms&&!value->id[0]&&!value->title[0]&&!value->reason[0];
  if(!value->id[0])return false;
  for(const unsigned char *p=(const unsigned char *)value->id;*p;p++)
    if(!((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||*p=='-'||*p=='_'||*p=='.'))return false;
  return value->active?!value->reason[0]:!value->remaining_ms&&
    (!strcmp(value->reason,"time_up")||!strcmp(value->reason,"finished")||!strcmp(value->reason,"mode_changed"));
}
static int research_save_locked(const struct buffer_research_s *research,int64_t deadline,bool expired,const char *type)
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  int ret=focus_runtime_boot_init();if(ret)return ret;
  int64_t now=buffer_monotonic_ms();if(now<0||now>9007199254740991LL)return -EIO;
  cJSON *value=cJSON_CreateObject();if(!value)return -ENOMEM;
  bool built=cJSON_AddNumberToObject(value,"schema_version",1)&&cJSON_AddBoolToObject(value,"present",research->present)&&
    cJSON_AddBoolToObject(value,"active",research->active)&&cJSON_AddStringToObject(value,"id",research->id)&&
    cJSON_AddStringToObject(value,"title",research->title)&&cJSON_AddStringToObject(value,"reason",research->reason)&&
    cJSON_AddNumberToObject(value,"remaining_ms",(double)(research->active&&deadline>now?deadline-now:0))&&
    cJSON_AddBoolToObject(value,"display_expired",expired)&&cJSON_AddNumberToObject(value,"recorded_at",(double)focus_wall_ms())&&
    cJSON_AddNumberToObject(value,"recorded_mono",(double)now)&&cJSON_AddStringToObject(value,"boot_id",focus_runtime_boot);
  ret=built?buffer_source_projection_json(RESEARCH_RUNTIME_FILE,value,type,NULL):-ENOMEM;
  if(ret&&built){
    cJSON *saved=buffer_agent_read_json(RESEARCH_RUNTIME_FILE,BUFFER_AGENT_JSON_BYTES);
    if (saved && cJSON_Compare(saved, value, true))
      ret = 0;
    cJSON_Delete(saved);
  }
  if(ret)research_reconcile=true;
  cJSON_Delete(value);return ret;
#else
  (void)research;(void)deadline;(void)expired;(void)type;return 0;
#endif
}
static int research_restore_locked(void)
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(!research_reconcile)return 0;
  int ret=focus_runtime_boot_init();if(ret)return ret;
  errno=0;cJSON *value=buffer_agent_read_json(RESEARCH_RUNTIME_FILE,BUFFER_AGENT_JSON_BYTES);
  if(!value){
    if(errno==ENOENT){
      memset(&g_buffer.research,0,sizeof(g_buffer.research));g_buffer.research_deadline=0;
      research_known=false;research_display_expired=false;research_reconcile=false;return 0;
    }
    return -(errno?errno:EIO);
  }
  struct buffer_research_s research={0};int64_t schema,remaining,wall,mono;
  const cJSON *present=cJSON_GetObjectItemCaseSensitive(value,"present"),*active=cJSON_GetObjectItemCaseSensitive(value,"active");
  const cJSON *id=cJSON_GetObjectItemCaseSensitive(value,"id"),*title=cJSON_GetObjectItemCaseSensitive(value,"title");
  const cJSON *reason=cJSON_GetObjectItemCaseSensitive(value,"reason"),*boot=cJSON_GetObjectItemCaseSensitive(value,"boot_id");
  const cJSON *expired=cJSON_GetObjectItemCaseSensitive(value,"display_expired");
  bool valid=cJSON_IsObject(value)&&cJSON_GetArraySize(value)==11&&
    focus_runtime_number(value,"schema_version",1,&schema)&&schema==1&&
    focus_runtime_number(value,"remaining_ms",7200000,&remaining)&&
    focus_runtime_number(value,"recorded_at",9007199254740991LL,&wall)&&
    focus_runtime_number(value,"recorded_mono",9007199254740991LL,&mono)&&cJSON_IsBool(present)&&cJSON_IsBool(active)&&
    cJSON_IsBool(expired)&&cJSON_IsString(id)&&strlen(id->valuestring)<sizeof(research.id)&&
    cJSON_IsString(title)&&strlen(title->valuestring)<sizeof(research.title)&&
    cJSON_IsString(reason)&&strlen(reason->valuestring)<sizeof(research.reason)&&cJSON_IsString(boot)&&strlen(boot->valuestring)==32;
  if(!valid){cJSON_Delete(value);return -EINVAL;}
  research.present=cJSON_IsTrue(present);research.active=cJSON_IsTrue(active);research.remaining_ms=(uint32_t)remaining;
  snprintf(research.id,sizeof(research.id),"%s",id->valuestring);snprintf(research.title,sizeof(research.title),"%s",title->valuestring);
  snprintf(research.reason,sizeof(research.reason),"%s",reason->valuestring);
  if(!valid_research(&research)||(!research.active&&cJSON_IsTrue(expired))){cJSON_Delete(value);return -EINVAL;}
  int64_t now=buffer_monotonic_ms(),current_wall=focus_wall_ms(),age=-1;
  if(!strcmp(boot->valuestring,focus_runtime_boot)&&now>=mono)age=now-mono;
  else if(wall>0&&current_wall>=wall)age=current_wall-wall;
  if(research.active&&(age<0||g_buffer.mode_dirty||strcmp(g_buffer.mode,"rabbit_hole"))){
    memset(&g_buffer.research,0,sizeof(g_buffer.research));g_buffer.research_deadline=0;
    research_known=false;research_display_expired=false;
  } else {
    g_buffer.research=research;g_buffer.research_deadline=research.active?now+(remaining>age?remaining-age:0):0;
    research_display_expired=cJSON_IsTrue(expired);research_known=true;
  }
  cJSON_Delete(value);research_reconcile=false;
#endif
  return 0;
}
static void research_tick(void)
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE))return;
#endif
  pthread_mutex_lock(&g_buffer.lock);int ret=research_restore_locked();
  if(!ret&&research_known&&g_buffer.research.present&&g_buffer.research.active&&!research_display_expired&&
     !g_buffer.mode_dirty&&!strcmp(g_buffer.mode,"rabbit_hole")&&buffer_monotonic_ms()>=g_buffer.research_deadline){
    ret=research_save_locked(&g_buffer.research,g_buffer.research_deadline,true,"ResearchDisplayDeadlineReached");
    if(!ret)research_display_expired=true;
  }
  if(ret&&!strcmp(g_buffer.mode,"rabbit_hole"))
    snprintf(g_buffer.status,sizeof(g_buffer.status),"研究状态等待存储恢复：%d",ret);
  pthread_mutex_unlock(&g_buffer.lock);
}

int buffer_sync_research(const struct buffer_research_s *research)
{
  int64_t deadline;
  bool same;
  if(!valid_research(research))return -EINVAL;
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.mode_dirty) { pthread_mutex_unlock(&g_buffer.lock); return -EAGAIN; }
  int ret=research_restore_locked();if(ret){pthread_mutex_unlock(&g_buffer.lock);return ret;}
  same = research_known && g_buffer.research.present && research->present &&
         strcmp(g_buffer.research.id, research->id) == 0;
  if (same && !g_buffer.research.active && research->active)
    { pthread_mutex_unlock(&g_buffer.lock); return -EAGAIN; }
  deadline = buffer_monotonic_ms() + research->remaining_ms;
  if (same && research->active && g_buffer.research_deadline < deadline)
    deadline = g_buffer.research_deadline; /* Repeated snapshots cannot restart a timer. */
  bool expired=research->active&&((same&&research_display_expired)||deadline<=buffer_monotonic_ms());
  bool changed=!research_known||research->present!=g_buffer.research.present||research->active!=g_buffer.research.active||
    strcmp(research->id,g_buffer.research.id)||strcmp(research->title,g_buffer.research.title)||strcmp(research->reason,g_buffer.research.reason)||
    (research->active&&deadline!=g_buffer.research_deadline)||expired!=research_display_expired;
  if(changed){
    ret=research_save_locked(research,deadline,expired,"ResearchSessionObserved");
    if(ret){pthread_mutex_unlock(&g_buffer.lock);return ret;}
  }
  g_buffer.research = *research;
  g_buffer.research_deadline = deadline;
  research_known=true;research_display_expired=expired;
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

bool buffer_research_snapshot(char *text, size_t text_size)
{
  struct buffer_research_s research;
  int64_t left;
  if (text == NULL || text_size == 0) return false;
  text[0] = 0;
  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.research.present || g_buffer.mode_dirty ||
      strcmp(g_buffer.mode, "bedtime") == 0 || strcmp(g_buffer.mode, "focus") == 0 ||
      (g_buffer.research.active && strcmp(g_buffer.mode, "rabbit_hole") != 0))
    { pthread_mutex_unlock(&g_buffer.lock); return false; }
  research = g_buffer.research;
  left = g_buffer.research_deadline - buffer_monotonic_ms();
  pthread_mutex_unlock(&g_buffer.lock);
  if (research.active && left > 0)
    snprintf(text, text_size, "研究：%s · 剩余 %lld 分钟", research.title,
             (long long)((left + 59999) / 60000));
  else
    snprintf(text, text_size, "研究：%s · %s", research.title,
             research.active || strcmp(research.reason, "time_up") == 0 ? "时间到" :
             strcmp(research.reason, "mode_changed") == 0 ? "已中断" : "已结束");
  return true;
}

struct focus_reminder_state_s
{
  uint32_t interval;
  char revision[BUFFER_ID_SIZE];
  int64_t started, elapsed_offset, next, until;
  bool due;
};
static bool focus_runtime_checkpoint=true;
/* Caller holds the runtime lock. Journal code never acquires that lock. */
static void focus_reminder_snapshot(struct focus_reminder_state_s *state)
{
  state->interval=g_buffer.focus_interval_ms;
  snprintf(state->revision,sizeof(state->revision),"%s",g_buffer.focus_revision);
  state->started=g_buffer.focus_started_at;state->elapsed_offset=g_buffer.focus_elapsed_offset_ms;
  state->next=g_buffer.next_care_at;state->until=g_buffer.focus_reminder_until;state->due=g_buffer.focus_reminder_due;
}
static void focus_reminder_apply(const struct focus_reminder_state_s *state)
{
  g_buffer.focus_interval_ms=state->interval;
  snprintf(g_buffer.focus_revision,sizeof(g_buffer.focus_revision),"%s",state->revision);
  g_buffer.focus_started_at=state->started;g_buffer.focus_elapsed_offset_ms=state->elapsed_offset;
  g_buffer.next_care_at=state->next;g_buffer.focus_reminder_until=state->until;g_buffer.focus_reminder_due=state->due;
}
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define FOCUS_RUNTIME_FILE CONFIG_BUFFER_VELA_DATA_DIR "/focus_runtime.json"
static bool focus_runtime_reconcile=true;
static int focus_runtime_boot_init(void)
{
  if(focus_runtime_boot[0])return 0;
  unsigned char bytes[16];int ret=buffer_entropy_read(bytes,sizeof(bytes));if(ret)return ret;
  for(size_t i=0;i<sizeof(bytes);i++)snprintf(focus_runtime_boot+2*i,3,"%02x",bytes[i]);
  return 0;
}
static int64_t focus_wall_ms(void)
{
  struct timespec stamp;
  if(clock_gettime(CLOCK_REALTIME,&stamp)||stamp.tv_sec<1700000000||stamp.tv_sec>9007199254740LL)return 0;
  return (int64_t)stamp.tv_sec*1000+stamp.tv_nsec/1000000;
}
static bool focus_runtime_number(const cJSON *value,const char *key,int64_t max,int64_t *out)
{
  const cJSON *item=cJSON_GetObjectItemCaseSensitive(value,key);
  if(!cJSON_IsNumber(item)||!isfinite(item->valuedouble)||item->valuedouble<0||
     item->valuedouble>(double)max||floor(item->valuedouble)!=item->valuedouble)return false;
  *out=(int64_t)item->valuedouble;return true;
}
static int focus_runtime_restore_locked(int64_t now)
{
  if(!focus_runtime_reconcile)return 0;
  int ret=focus_runtime_boot_init();if(ret)return ret;
  struct focus_reminder_state_s before={0},after={0};focus_reminder_snapshot(&before);
  char old_mode[BUFFER_MODE_SIZE],old_mode_id[BUFFER_ACTION_ID_SIZE];
  snprintf(old_mode,sizeof(old_mode),"%s",g_buffer.mode);snprintf(old_mode_id,sizeof(old_mode_id),"%s",g_buffer.mode_id);
  pthread_mutex_unlock(&g_buffer.lock);
  errno=0;cJSON *value=buffer_source_projection_read(FOCUS_RUNTIME_FILE,BUFFER_AGENT_JSON_BYTES);int read_error=errno;
  pthread_mutex_lock(&g_buffer.lock);focus_reminder_snapshot(&after);
  if(memcmp(&before,&after,sizeof(before))||strcmp(old_mode,g_buffer.mode)||strcmp(old_mode_id,g_buffer.mode_id)){
    cJSON_Delete(value);return -EAGAIN;
  }
  if(!value){if(read_error==ENOENT){focus_runtime_reconcile=false;return 0;}return -(read_error?read_error:EIO);}
  int64_t schema,interval,elapsed,remaining,notice,recorded,recorded_mono;
  const cJSON *mode=cJSON_GetObjectItemCaseSensitive(value,"mode"),*revision=cJSON_GetObjectItemCaseSensitive(value,"revision");
  const cJSON *mode_id=cJSON_GetObjectItemCaseSensitive(value,"mode_id");
  const cJSON *boot=cJSON_GetObjectItemCaseSensitive(value,"boot_id");
  bool valid=cJSON_IsObject(value)&&cJSON_GetArraySize(value)==11&&
    focus_runtime_number(value,"schema_version",1,&schema)&&schema==1&&
    focus_runtime_number(value,"interval_ms",BUFFER_FOCUS_INTERVAL_MAX_MS,&interval)&&interval>=BUFFER_FOCUS_INTERVAL_MIN_MS&&
    focus_runtime_number(value,"elapsed_ms",9007199254740991LL,&elapsed)&&
    focus_runtime_number(value,"remaining_ms",BUFFER_FOCUS_INTERVAL_MAX_MS,&remaining)&&
    focus_runtime_number(value,"notice_ms",15000,&notice)&&
    focus_runtime_number(value,"recorded_at",9007199254740991LL,&recorded)&&
    focus_runtime_number(value,"recorded_mono",9007199254740991LL,&recorded_mono)&&
    cJSON_IsString(mode)&&cJSON_IsString(revision)&&strlen(revision->valuestring)<BUFFER_ID_SIZE&&
    (!strcmp(mode->valuestring,"normal")||!strcmp(mode->valuestring,"focus")||
     !strcmp(mode->valuestring,"bedtime")||!strcmp(mode->valuestring,"rabbit_hole"))&&
    cJSON_IsString(mode_id)&&strlen(mode_id->valuestring)<BUFFER_ACTION_ID_SIZE&&
    cJSON_IsString(boot)&&strlen(boot->valuestring)==32;
  if(!valid){cJSON_Delete(value);return -EINVAL;}
  now=buffer_monotonic_ms();
  if(!strcmp(mode->valuestring,g_buffer.mode)&&!strcmp(g_buffer.mode,"focus")&&
     (!g_buffer.mode_dirty||!strcmp(mode_id->valuestring,g_buffer.mode_id))){
    int64_t wall=focus_wall_ms();
    int64_t age=-1;
    if(!strcmp(boot->valuestring,focus_runtime_boot)&&now>=recorded_mono)age=now-recorded_mono;
    else if(recorded>0&&wall>=recorded)age=wall-recorded;
    if(age>=0){
      g_buffer.focus_interval_ms=(uint32_t)interval;
      g_buffer.focus_started_at=now>0?now:1;
      g_buffer.focus_elapsed_offset_ms=age>9007199254740991LL-elapsed?9007199254740991LL:elapsed+age;
      g_buffer.next_care_at=now+(remaining>age?remaining-age:0);
      g_buffer.focus_reminder_due=notice>age;
      g_buffer.focus_reminder_until=notice>age?now+notice-age:0;
      snprintf(g_buffer.focus_revision,sizeof(g_buffer.focus_revision),"%s",revision->valuestring);
    }
    /* Missing/backward wall clock cannot restore a monotonic deadline. Leave
     * the new session's safe schedule in place; never replay the old notice. */
  }
  cJSON_Delete(value);focus_runtime_reconcile=false;return 0;
}
static int focus_runtime_write_locked(const char *type,int64_t now)
{
  int ready=focus_runtime_boot_init();if(ready)return ready;
  now=buffer_monotonic_ms();
  if(now<0||now>9007199254740991LL)return -EIO;
  cJSON *value=cJSON_CreateObject();if(!value)return -ENOMEM;
  int64_t elapsed=g_buffer.focus_started_at>0?g_buffer.focus_elapsed_offset_ms+
    (now>g_buffer.focus_started_at?now-g_buffer.focus_started_at:0):0;
  if(elapsed>9007199254740991LL)elapsed=9007199254740991LL;
  uint32_t interval=g_buffer.focus_interval_ms?g_buffer.focus_interval_ms:CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
  if(interval<BUFFER_FOCUS_INTERVAL_MIN_MS||interval>BUFFER_FOCUS_INTERVAL_MAX_MS||elapsed<0||
     (g_buffer.next_care_at>now&&g_buffer.next_care_at-now>BUFFER_FOCUS_INTERVAL_MAX_MS)||
     (g_buffer.focus_reminder_due&&g_buffer.focus_reminder_until>now&&g_buffer.focus_reminder_until-now>15000))
    {cJSON_Delete(value);return -EINVAL;}
  bool built=cJSON_AddNumberToObject(value,"schema_version",1)&&cJSON_AddStringToObject(value,"mode",g_buffer.mode)&&
    cJSON_AddStringToObject(value,"mode_id",g_buffer.mode_id)&&cJSON_AddStringToObject(value,"revision",g_buffer.focus_revision)&&
    cJSON_AddNumberToObject(value,"interval_ms",interval)&&cJSON_AddNumberToObject(value,"elapsed_ms",(double)elapsed)&&
    cJSON_AddNumberToObject(value,"remaining_ms",(double)(g_buffer.next_care_at>now?g_buffer.next_care_at-now:0))&&
    cJSON_AddNumberToObject(value,"notice_ms",(double)(g_buffer.focus_reminder_due&&g_buffer.focus_reminder_until>now?g_buffer.focus_reminder_until-now:0))&&
    cJSON_AddNumberToObject(value,"recorded_at",(double)focus_wall_ms())&&
    cJSON_AddStringToObject(value,"boot_id",focus_runtime_boot)&&cJSON_AddNumberToObject(value,"recorded_mono",(double)now);
  pthread_mutex_unlock(&g_buffer.lock);
  int ret=built?buffer_source_projection_json(FOCUS_RUNTIME_FILE,value,type,NULL):-ENOMEM;
  if(ret&&built){
    cJSON *saved=buffer_source_projection_read(FOCUS_RUNTIME_FILE,BUFFER_AGENT_JSON_BYTES);
    if (saved && cJSON_Compare(saved, value, true))
      ret = 0;
    cJSON_Delete(saved);
  }
  pthread_mutex_lock(&g_buffer.lock);
  if(ret)focus_runtime_reconcile=true;
  cJSON_Delete(value);return ret;
}
static int focus_runtime_save_locked(const char *type,int64_t now)
{
  /* Serialize snapshots/writes without holding the presentation mutex while
   * waiting for another writer or the NAND journal. Re-snapshot after taking
   * the writer lock so a delayed caller cannot persist an older state. */
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_lock(&g_focus_runtime_commit_lock);
  pthread_mutex_lock(&g_buffer.lock);
  int ret=focus_runtime_write_locked(type,now);
  pthread_mutex_unlock(&g_focus_runtime_commit_lock);return ret;
}
#else
static int focus_runtime_restore_locked(int64_t now){(void)now;return 0;}
static int focus_runtime_save_locked(const char *type,int64_t now){(void)type;(void)now;return 0;}
#endif
static void focus_runtime_reload_locked(void)
{
  focus_runtime_checkpoint=true;
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  focus_runtime_reconcile=true;
#endif
}

int buffer_sync_focus_config(const struct buffer_focus_config_s *config)
{
  int ret = 0;
  int64_t now;

  if (config == NULL || config->interval_ms < BUFFER_FOCUS_INTERVAL_MIN_MS ||
      config->interval_ms > BUFFER_FOCUS_INTERVAL_MAX_MS ||
      config->remaining_ms > BUFFER_FOCUS_INTERVAL_MAX_MS ||
      config->notice_remaining_ms > 15000 ||
      config->revision[0] == '\0' ||
      memchr(config->revision, '\0', sizeof(config->revision)) == NULL)
    {
      return -EINVAL;
    }
  now = buffer_monotonic_ms();
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.mode_dirty ||
      (strcmp(g_buffer.mode, "focus") == 0) != config->active)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return -EAGAIN;
    }
  ret=focus_runtime_restore_locked(now);
  if(ret){pthread_mutex_unlock(&g_buffer.lock);return ret;}
  now=buffer_monotonic_ms();
  struct focus_reminder_state_s previous;focus_reminder_snapshot(&previous);
  bool changed=false;
  if (g_buffer.focus_interval_ms != config->interval_ms)
    {
      ret = buffer_store_save_focus_interval(config->interval_ms);
      if (ret < 0)
        {
          pthread_mutex_unlock(&g_buffer.lock);
          return ret;
        }
      g_buffer.focus_interval_ms = config->interval_ms;
      changed=true;
    }
  if (config->active && strcmp(g_buffer.focus_revision, config->revision) != 0)
    {
      if (g_buffer.focus_started_at == 0) {
          g_buffer.focus_started_at = now > 0 ? now : 1;
          g_buffer.focus_elapsed_offset_ms=0;
      }
      changed=true;
      g_buffer.next_care_at = now + config->remaining_ms;
      snprintf(g_buffer.focus_revision, sizeof(g_buffer.focus_revision), "%s", config->revision);
      if (config->clear_notice)
        {
          g_buffer.focus_reminder_due = false;
          g_buffer.focus_reminder_until = 0;
        }
      else if (config->notice_remaining_ms > 0 && !g_buffer.focus_reminder_due)
        {
          /* The phone may advance the deadline before our timer fires. */
          g_buffer.focus_reminder_due = true;
          g_buffer.focus_reminder_until = now + config->notice_remaining_ms;
        }
    }
  if(changed||focus_runtime_checkpoint){
    struct focus_reminder_state_s writing={0},current={0};focus_reminder_snapshot(&writing);
    ret=focus_runtime_save_locked("FocusReminderConfigObserved",now);
    focus_reminder_snapshot(&current);
    if(ret&&!memcmp(&writing,&current,sizeof(writing)))focus_reminder_apply(&previous);
    focus_runtime_checkpoint=ret!=0;
  }
  pthread_mutex_unlock(&g_buffer.lock);
  return ret;
}

/* Called with the runtime lock held. */
static uint32_t buffer_focus_interval(void)
{
  return g_buffer.focus_interval_ms != 0 ? g_buffer.focus_interval_ms
                                        : CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
}

static void buffer_focus_tick(void)
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE))return;
#endif
  int64_t now = buffer_monotonic_ms();
  bool reminder_due = false;

  pthread_mutex_lock(&g_buffer.lock);
  int ret=focus_runtime_restore_locked(now);
  if(ret){
    snprintf(g_buffer.status,sizeof(g_buffer.status),"专注提醒等待存储恢复：%d",ret);
    pthread_mutex_unlock(&g_buffer.lock);return;
  }
  now=buffer_monotonic_ms();
  struct focus_reminder_state_s previous;focus_reminder_snapshot(&previous);
  bool changed=false;
  const char *event_type="FocusReminderScheduleChanged";
  if (g_buffer.focus_reminder_due && g_buffer.focus_reminder_until != 0 &&
      now >= g_buffer.focus_reminder_until)
    {
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
      changed=true;event_type="FocusReminderNoticeExpired";
    }
  if (strcmp(g_buffer.mode, "focus") == 0)
    {
      if (g_buffer.focus_started_at == 0)
        {
          g_buffer.focus_started_at = now > 0 ? now : 1;
          g_buffer.focus_elapsed_offset_ms=0;
          g_buffer.next_care_at = now +
                                  (int64_t)buffer_focus_interval();
          changed=true;
        }
      else if (g_buffer.next_care_at != 0 && now >= g_buffer.next_care_at)
        {
          g_buffer.focus_reminder_due = true;
          g_buffer.focus_reminder_until = now + 15000;
          g_buffer.next_care_at = now +
                                  (int64_t)buffer_focus_interval();
          reminder_due = true;
          changed=true;event_type="FocusReminderTriggered";
        }
    }
  else
    {
      if(g_buffer.focus_started_at||g_buffer.next_care_at||g_buffer.focus_reminder_due||g_buffer.focus_reminder_until||
         g_buffer.focus_elapsed_offset_ms)changed=true;
      g_buffer.focus_started_at = 0;
      g_buffer.focus_elapsed_offset_ms=0;
      g_buffer.next_care_at = 0;
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
    }
  if(changed||focus_runtime_checkpoint){
    struct focus_reminder_state_s writing={0},current={0};focus_reminder_snapshot(&writing);
    ret=focus_runtime_save_locked(event_type,now);
    focus_reminder_snapshot(&current);
    if(ret){
      if(!memcmp(&writing,&current,sizeof(writing)))focus_reminder_apply(&previous);
      reminder_due=false;
      snprintf(g_buffer.status,sizeof(g_buffer.status),"专注提醒保存失败：%d",ret);
    }
    focus_runtime_checkpoint=ret!=0;
  }
  pthread_mutex_unlock(&g_buffer.lock);

  if (reminder_due)
    {
      buffer_set_status("专注提醒：喝水、看远处、活动肩颈");
    }
}

bool buffer_focus_snapshot(char *text, size_t text_size)
{
  int64_t now;
  int64_t started_at;
  int64_t elapsed_offset;
  int64_t next_care_at;
  bool reminder_due;

  if (text == NULL || text_size == 0)
    {
      return false;
    }

  text[0] = '\0';
  pthread_mutex_lock(&g_buffer.lock);
  if (strcmp(g_buffer.mode, "focus") != 0)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return false;
    }
  started_at = g_buffer.focus_started_at;
  elapsed_offset = g_buffer.focus_elapsed_offset_ms;
  next_care_at = g_buffer.next_care_at;
  reminder_due = g_buffer.focus_reminder_due;
  pthread_mutex_unlock(&g_buffer.lock);

  now = buffer_monotonic_ms();
  if (started_at <= 0)
    {
      snprintf(text, text_size, "专注计时启动中");
      return true;
    }

  if (reminder_due)
    {
      snprintf(text, text_size, "专注 %lld 分钟 · 照顾提醒进行中",
               (long long)((elapsed_offset + now - started_at) / 60000));
    }
  else if (next_care_at > now)
    {
      snprintf(text, text_size, "专注 %lld 分钟 · 下次照顾 %lld 分钟后",
               (long long)((elapsed_offset + now - started_at) / 60000),
               (long long)((next_care_at - now + 59999) / 60000));
    }
  else
    {
      snprintf(text, text_size, "专注 %lld 分钟 · 下次照顾提醒即将到达",
               (long long)((elapsed_offset + now - started_at) / 60000));
    }

  return true;
}

void buffer_set_status(const char *format, ...)
{
  va_list ap;

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.focus_reminder_due && g_buffer.focus_reminder_until != 0 &&
      buffer_monotonic_ms() < g_buffer.focus_reminder_until &&
      strncmp(g_buffer.status, "专注提醒：", strlen("专注提醒：")) == 0)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return;
    }
  va_start(ap, format);
  vsnprintf(g_buffer.status, sizeof(g_buffer.status), format, ap);
  va_end(ap);
  pthread_mutex_unlock(&g_buffer.lock);
}

void buffer_request_sync(void)
{
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.sync_requested = true;
  pthread_mutex_unlock(&g_buffer.lock);
}

void buffer_request_card_action(const char *card_id, const char *action)
{
  char pending_card_id[BUFFER_ID_SIZE];
  char pending_action_id[BUFFER_ACTION_ID_SIZE];
  char pending_action[BUFFER_ACTION_SIZE];
  int ret;

  if (action == NULL || action[0] == '\0')
    {
      return;
    }

  if (card_id && buffer_replica_crdt_contains(card_id)) {
      int merged=buffer_replica_crdt_action(card_id,action);
      if (merged!=-ENOTSUP) {buffer_set_status(merged<0?"操作保存失败：%d":"已在本机保存：%d",merged);return;}
  }
  /* Local record state is the durable source of truth, including archive,
   * later and tombstones. It must be changed before projecting the shelf. */
  pthread_mutex_lock(&g_buffer.lock);
  int selected = g_buffer.card_index;
  if (selected < 0 || selected >= g_buffer.card_count) selected = 0;
  if (card_id)
    {
      for (selected = 0; selected < g_buffer.card_count; selected++)
        if (!strcmp(g_buffer.cards[selected].card_id, card_id)) break;
      if (selected == g_buffer.card_count)
        {
          pthread_mutex_unlock(&g_buffer.lock);
          buffer_set_status("卡片已更新，请重新打开");
          return;
        }
    }
  bool local_candidate = g_buffer.card_count > 0 &&
      !strncmp(g_buffer.cards[selected].card_id, "vla_", 4);
  if (local_candidate) snprintf(pending_card_id, sizeof(pending_card_id), "%s",
                      g_buffer.cards[selected].card_id);
  pthread_mutex_unlock(&g_buffer.lock);
  bool local=local_candidate&&!buffer_replica_available(pending_card_id);
  if (local)
    {
      ret = buffer_agent_apply_action(pending_card_id, action);
      if (!ret) ret=buffer_replica_publish_local_action(pending_card_id,action);
      buffer_set_status(ret < 0 ? "本地操作保存失败：%d" : "本地操作已保存：%d", ret);
      return;
    }

  pending_card_id[0] = '\0';
  pending_action_id[0] = '\0';
  pending_action[0] = '\0';
  ret = buffer_generate_action_id(pending_action_id, sizeof(pending_action_id));
  if (ret < 0)
    {
      buffer_set_status("卡片操作编号生成失败：%d", ret);
      return;
    }

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.card_count <= 0)
    {
      snprintf(g_buffer.status, sizeof(g_buffer.status), "当前没有可操作卡片");
      pthread_mutex_unlock(&g_buffer.lock);
      return;
    }
  if (g_buffer.action_requested)
    {
      /* There is one durable action slot.  Do not overwrite an operation
       * whose ACK has not arrived; the user can retry after the next deck
       * refresh, and the original action remains recoverable after reboot. */
      snprintf(g_buffer.status, sizeof(g_buffer.status),
               "上一操作等待同步，请稍后再试");
      pthread_mutex_unlock(&g_buffer.lock);
      return;
    }
  else
    {
      int card_index = g_buffer.card_index;
      if (card_index < 0 || card_index >= g_buffer.card_count)
        {
          card_index = 0;
        }
      if (card_id)
        {
          for (card_index = 0; card_index < g_buffer.card_count; card_index++)
            if (!strcmp(g_buffer.cards[card_index].card_id, card_id)) break;
          if (card_index == g_buffer.card_count)
            {
              pthread_mutex_unlock(&g_buffer.lock);
              buffer_set_status("卡片已更新，请重新打开");
              return;
            }
        }
      if (!buffer_card_supports_action(&g_buffer.cards[card_index], action))
        {
          snprintf(g_buffer.status, sizeof(g_buffer.status),
                   "当前卡片不支持：%s", action);
          pthread_mutex_unlock(&g_buffer.lock);
          return;
        }
      snprintf(g_buffer.action_card_id, sizeof(g_buffer.action_card_id), "%s",
               g_buffer.cards[card_index].card_id);
      snprintf(g_buffer.action_id, sizeof(g_buffer.action_id), "%s",
               pending_action_id);
      snprintf(g_buffer.action, sizeof(g_buffer.action), "%s", action);
      snprintf(pending_card_id, sizeof(pending_card_id), "%s",
               g_buffer.action_card_id);
      snprintf(pending_action, sizeof(pending_action), "%s", g_buffer.action);
    }

  /* Keep the runtime slot and its durable representation in lockstep.  The
   * sync thread cannot observe an action until its file is safely replaced. */
  ret = buffer_store_save_pending_action(pending_card_id,
                                          pending_action_id,
                                          pending_action);
  if (ret == 0)
    {
      g_buffer.action_requested = true;
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (ret < 0)
    {
      buffer_set_status("卡片操作持久化失败：%d", ret);
      return;
    }

  buffer_set_status("已记录操作：%s，等待同步", pending_action);
  buffer_request_sync();
}

void buffer_request_action(const char *action)
{
  buffer_request_card_action(NULL, action);
}

/* Explicit ID path for the task list; never changes the home selection. */
int buffer_request_action_id(const char *card_id, const char *action)
{
  struct buffer_todo_item item;
  if (!action || (strcmp(action, "done") && strcmp(action,"reopen"))) return -EINVAL;
  int ret = buffer_todo_get(card_id, &item);
  if (ret < 0) return ret;
  if (buffer_replica_crdt_contains(card_id)) return buffer_replica_crdt_action(card_id,action);
  if (item.pending) return -EBUSY;
  if (!buffer_card_supports_action(&item.card, action) || !item.card.actions[0]) return -EACCES;
  if (item.local)
    {
      ret = buffer_agent_apply_action(card_id, action);
      if (!ret) ret=buffer_replica_publish_local_action(card_id,action);
      buffer_set_status(ret < 0 ? "待办操作保存失败：%d" : "待办已在本机保存，等待状态同步：%d", ret);
      return ret;
    }
  char action_id[BUFFER_ACTION_ID_SIZE];
  ret = buffer_generate_action_id(action_id, sizeof(action_id));
  if (ret < 0) return ret;
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.action_requested) { pthread_mutex_unlock(&g_buffer.lock); return -EBUSY; }
  ret = buffer_store_save_pending_action(card_id, action_id, action);
  if (!ret)
    {
      snprintf(g_buffer.action_card_id,sizeof(g_buffer.action_card_id),"%s",card_id);
      snprintf(g_buffer.action_id,sizeof(g_buffer.action_id),"%s",action_id);
      snprintf(g_buffer.action,sizeof(g_buffer.action),"%s",action);
      g_buffer.action_requested = true;
    }
  pthread_mutex_unlock(&g_buffer.lock);
  if (!ret) { buffer_request_sync(); buffer_set_status("待办操作已排队，等待手机回执"); }
  return ret;
}

void buffer_request_mode(const char *mode)
{
  char mode_id[BUFFER_ACTION_ID_SIZE];
  int64_t now;
  int ret;

  if (mode == NULL ||
      (strcmp(mode, "normal") != 0 && strcmp(mode, "focus") != 0 &&
       strcmp(mode, "bedtime") != 0 && strcmp(mode, "rabbit_hole") != 0))
    {
      buffer_set_status("模式应为 normal/focus/bedtime/rabbit_hole");
      return;
    }

  now = buffer_monotonic_ms();
  ret = buffer_generate_action_id(mode_id, sizeof(mode_id));
  if (ret < 0)
    {
      buffer_set_status("模式操作编号生成失败：%d", ret);
      return;
    }

  pthread_mutex_lock(&g_mode_commit_lock);
  ret = buffer_store_save_pending_mode(mode, mode_id);
  if (ret < 0)
    {
      pthread_mutex_unlock(&g_mode_commit_lock);
      buffer_set_status("模式切换持久化失败：%d", ret);
      return;
    }
  pthread_mutex_lock(&g_buffer.lock);
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", mode);
  snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "%s", mode_id);
  g_buffer.mode_dirty = true;
  g_buffer.focus_revision[0] = '\0';
  g_buffer.focus_elapsed_offset_ms=0;
  if (strcmp(mode, "focus") == 0)
    {
      g_buffer.focus_started_at = now;
      g_buffer.next_care_at = now +
                              (int64_t)buffer_focus_interval();
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
    }
  else
    {
      g_buffer.focus_started_at = 0;
      g_buffer.next_care_at = 0;
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
  }
  /* Serialize the mode cache with newer mode requests. The pending request
   * above remains the authority if either subsequent checkpoint fails. */
  pthread_mutex_unlock(&g_buffer.lock);
  int mode_saved=buffer_store_save_mode(mode);
  pthread_mutex_lock(&g_buffer.lock);
  int focus_saved=focus_runtime_save_locked("FocusReminderModeChanged",now);
  focus_runtime_checkpoint=focus_saved!=0;
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_mode_commit_lock);
  if (mode_saved < 0)
    {
      buffer_set_status("模式已切换，待同步状态已保存");
    }
  else if(focus_saved<0)
    {
      buffer_set_status("模式已保存，专注提醒等待存储恢复：%d",focus_saved);
    }
  else
    {
      buffer_set_status("模式已切换为 %s", mode);
    }
  buffer_request_sync();
}

void buffer_request_card_step(int delta)
{
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.card_count <= 0)
    {
      snprintf(g_buffer.status, sizeof(g_buffer.status), "当前没有可浏览卡片");
    }
  else if (delta != 0)
    {
      g_buffer.card_index = (g_buffer.card_index + delta) %
                            g_buffer.card_count;
      if (g_buffer.card_index < 0)
        {
          g_buffer.card_index += g_buffer.card_count;
        }
      snprintf(g_buffer.status, sizeof(g_buffer.status), "当前卡片 %d/%d",
               g_buffer.card_index + 1, g_buffer.card_count);
    }
  pthread_mutex_unlock(&g_buffer.lock);
}

int buffer_update_cards(const struct buffer_card_s *cards, int count)
{
  int index;
  int ret;
  char selected_id[BUFFER_ID_SIZE] = {0};

  if (count < 0 || count > BUFFER_CARD_LIMIT || (count > 0 && cards == NULL))
    {
      return -EINVAL;
    }

  /* Commit the durable cache first.  If storage is full or unavailable, keep
   * the previous in-memory deck and mark it stale instead of presenting cards
   * that will disappear after a reboot. */
  pthread_mutex_lock(&g_cards_commit_lock);
  ret = buffer_store_save_cards(cards, count);
  if (ret < 0)
    {
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.cards_stale = true;
      pthread_mutex_unlock(&g_buffer.lock);
      pthread_mutex_unlock(&g_cards_commit_lock);
      buffer_set_status("卡片缓存保存失败：%d", ret);
      return ret;
    }

  int pending=buffer_store_pending_count();
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.card_index >= 0 && g_buffer.card_index < g_buffer.card_count)
    {
      snprintf(selected_id, sizeof(selected_id), "%s",
               g_buffer.cards[g_buffer.card_index].card_id);
    }
  g_buffer.card_index = 0;
  memset(g_buffer.cards, 0, sizeof(g_buffer.cards));
  for (index = 0; index < count; index++)
    {
      g_buffer.cards[index] = cards[index];
      if (selected_id[0] != '\0' && strcmp(selected_id, cards[index].card_id) == 0)
        {
          g_buffer.card_index = index;
        }
    }
  g_buffer.card_count = count;
  g_buffer.cards_stale = false;
  g_buffer.pending_count = pending;
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_cards_commit_lock);
  return 0;
}

int buffer_sync_remote_mode(const char *mode)
{
  int64_t now;
  bool changed = false;
  int persist_ret = 0;

  if (mode == NULL ||
      (strcmp(mode, "normal") != 0 && strcmp(mode, "focus") != 0 &&
       strcmp(mode, "bedtime") != 0 && strcmp(mode, "rabbit_hole") != 0))
    {
      return -EINVAL;
    }

  now = buffer_monotonic_ms();
  pthread_mutex_lock(&g_mode_commit_lock);
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.mode_dirty)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      pthread_mutex_unlock(&g_mode_commit_lock);
      return -EAGAIN;
    }
  /* A locally selected mode has priority until its mode_id is ACKed.  The
   * next cards response then carries the phone's authoritative mode. */
  if (!g_buffer.mode_dirty && strcmp(g_buffer.mode, mode) != 0)
    {
      /* Serialize storage with the same mode writer lock as local requests.
       * Otherwise an older cards response could overwrite a newer local mode
       * file after the local setter had released the lock.  Keep the old
       * in-memory mode on failure so the next response retries the write. */
      pthread_mutex_unlock(&g_buffer.lock);
      persist_ret = buffer_store_save_mode(mode);
      pthread_mutex_lock(&g_buffer.lock);
      if (persist_ret == 0)
        {
          snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", mode);
          g_buffer.mode_id[0] = '\0';
          g_buffer.focus_revision[0] = '\0';
          g_buffer.focus_elapsed_offset_ms=0;
          if (strcmp(mode, "focus") == 0)
            {
              g_buffer.focus_started_at = now;
              g_buffer.next_care_at = now +
                                      (int64_t)buffer_focus_interval();
              g_buffer.focus_reminder_due = false;
              g_buffer.focus_reminder_until = 0;
            }
          else
            {
              g_buffer.focus_started_at = 0;
              g_buffer.next_care_at = 0;
              g_buffer.focus_reminder_due = false;
              g_buffer.focus_reminder_until = 0;
            }
          focus_runtime_checkpoint=focus_runtime_save_locked("FocusReminderModeObserved",now)!=0;
          changed = true;
        }
    }
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_mode_commit_lock);

  if (persist_ret < 0)
    {
      buffer_set_status("手机模式保存失败：%d，将继续重试", persist_ret);
    }
  else if (changed)
    {
      buffer_set_status("手机已切换为 %s 模式", mode);
    }
  return persist_ret;
}

int buffer_sync_remote_mode_ack(const char *mode, const char *mode_id)
{
  size_t length;
  int ret;

  if (mode == NULL || mode_id == NULL ||
      (strcmp(mode, "normal") != 0 && strcmp(mode, "focus") != 0 &&
       strcmp(mode, "bedtime") != 0 && strcmp(mode, "rabbit_hole") != 0))
    {
      return -EINVAL;
    }

  length = strlen(mode_id);
  if (length == 0 || length >= BUFFER_ACTION_ID_SIZE)
    {
      return -EINVAL;
    }
  for (size_t i = 0; i < length; i++)
    {
      unsigned char character = (unsigned char)mode_id[i];
      if (!((character >= 'a' && character <= 'z') ||
            (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') ||
            character == '.' || character == '_' || character == '-'))
        {
          return -EINVAL;
        }
    }

  pthread_mutex_lock(&g_mode_commit_lock);
  pthread_mutex_lock(&g_buffer.lock);
  if (strcmp(g_buffer.mode, mode) != 0 ||
      strcmp(g_buffer.mode_id, mode_id) != 0)
    {
      ret = -ESTALE;
    }
  else if (!g_buffer.mode_dirty)
    {
      /* A retransmitted receipt for the just-committed ID is idempotent. */
      ret = 0;
    }
  else
    {
      pthread_mutex_unlock(&g_buffer.lock);
      ret = buffer_store_save_mode(mode);
      if (ret == 0)
        {
          ret = buffer_store_clear_pending_mode();
        }
      pthread_mutex_lock(&g_buffer.lock);
      if (ret == 0)
        {
          g_buffer.mode_dirty = false;
        }
    }
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_mode_commit_lock);

  if (ret < 0)
    {
      buffer_set_status("模式回执持久化失败：%d，将继续重试", ret);
    }
  return ret;
}

void buffer_mark_cards_stale(void)
{
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.cards_stale = true;
  pthread_mutex_unlock(&g_buffer.lock);
}

bool buffer_cards_are_stale(void)
{
  bool stale;

  pthread_mutex_lock(&g_buffer.lock);
  stale = g_buffer.cards_stale;
  pthread_mutex_unlock(&g_buffer.lock);
  return stale;
}

int buffer_current_card_index(void)
{
  int index;

  pthread_mutex_lock(&g_buffer.lock);
  index = g_buffer.card_index;
  pthread_mutex_unlock(&g_buffer.lock);
  return index;
}

bool buffer_ui_runtime_snapshot(struct buffer_ui_runtime_s *out)
{
  if (!out || pthread_mutex_trylock(&g_buffer.lock) != 0) return false;
  snprintf(out->status, sizeof(out->status), "%s", g_buffer.status);
  snprintf(out->mode, sizeof(out->mode), "%s", g_buffer.mode);
  snprintf(out->pairing_code, sizeof(out->pairing_code), "%s", g_buffer.pairing_code);
  snprintf(out->focus_title, sizeof(out->focus_title), "%s", g_buffer.focus_title);
  out->card_count = g_buffer.card_count;
  if (out->card_count < 0) out->card_count = 0;
  if (out->card_count > BUFFER_CARD_LIMIT) out->card_count = BUFFER_CARD_LIMIT;
  memcpy(out->cards, g_buffer.cards, out->card_count * sizeof(out->cards[0]));
  out->card_index = g_buffer.card_index;
  out->pending_count = g_buffer.pending_count;
  out->cards_stale = g_buffer.cards_stale;
  out->action_requested = g_buffer.action_requested;
  out->recording = g_buffer.recording || g_buffer.touch_requested;
  out->phone_synced = g_buffer.phone_synced;
  out->mode_dirty = g_buffer.mode_dirty;
  out->focus_reminder_due = g_buffer.focus_reminder_due;
  out->research = g_buffer.research;
  out->research_deadline = g_buffer.research_deadline;
  out->focus_started_at = g_buffer.focus_started_at;
  out->next_care_at = g_buffer.next_care_at;
  out->focus_interval_ms = g_buffer.focus_interval_ms;
  pthread_mutex_unlock(&g_buffer.lock);
  return true;
}

void buffer_snapshot(char *status, size_t status_size,
                     char *mode, size_t mode_size,
                     struct buffer_card_s *cards, int *card_count,
                     int *pending_count)
{
  int count;
  int index;

  pthread_mutex_lock(&g_buffer.lock);
  if (status != NULL && status_size > 0)
    {
      snprintf(status, status_size, "%s", g_buffer.status);
    }
  if (mode != NULL && mode_size > 0)
    {
      snprintf(mode, mode_size, "%s", g_buffer.mode);
    }
  count = g_buffer.card_count;
  if (count > BUFFER_CARD_LIMIT)
    {
      count = BUFFER_CARD_LIMIT;
    }
  if (cards != NULL)
    {
      for (index = 0; index < count; index++)
        {
          cards[index] = g_buffer.cards[index];
        }
    }
  if (card_count != NULL)
    {
      *card_count = count;
    }
  if (pending_count != NULL)
    {
      *pending_count = g_buffer.pending_count;
    }
  pthread_mutex_unlock(&g_buffer.lock);
}

static int buffer_is_running(void)
{
  int running;

  pthread_mutex_lock(&g_buffer.lock);
  running = g_buffer.initialized && !g_buffer.stopping;
  pthread_mutex_unlock(&g_buffer.lock);
  return running;
}

static int buffer_start_recording(void)
{
  int ret;

  if (!buffer_oobe_setup_complete())
    {
      buffer_set_status("请先完成设备设置再开始使用");
      return -EACCES;
    }

  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized || g_buffer.stopping || g_buffer.recording)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return -EBUSY;
    }
  /* The input path uses the published queue view. The audio commit rechecks
   * actual capacity on disk before publishing a new queue item. */
  if(g_buffer.pending_count>=CONFIG_BUFFER_VELA_QUEUE_LIMIT){
    pthread_mutex_unlock(&g_buffer.lock);buffer_set_status("离线队列已满，请先连接手机");return -ENOSPC;
  }

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE)){
    pthread_mutex_unlock(&g_buffer.lock);buffer_set_status("历史状态恢复中，请稍后录音");return -EAGAIN;
  }
#endif
  if(!g_buffer.touch_requested){
  snprintf(g_buffer.record_id, sizeof(g_buffer.record_id), "vela-%lld-%08x",
           (long long)buffer_monotonic_ms(), (unsigned int)arc4random());
    buffer_voice_begin_locked();
  }
  snprintf(g_buffer.record_mode, sizeof(g_buffer.record_mode), "%s",
           g_buffer.mode);
  if (!g_buffer.touch_requested)
    {
      g_buffer.record_context_card[0] = 0;
      g_buffer.record_target=BUFFER_CAPTURE_TARGET_AUTO;
      __atomic_store_n(&g_buffer.record_decision, 0, __ATOMIC_RELEASE);
    }
  g_buffer.record_stop = false;
  g_buffer.recording = true;
  g_buffer.record_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  pthread_attr_t record_attributes;
  ret=pthread_attr_init(&record_attributes);
  if(!ret){
    ret=pthread_attr_setstacksize(&record_attributes,32*1024);
    if(!ret)ret=pthread_create(&g_buffer.record_thread,&record_attributes,
                              buffer_record_thread_main,NULL);
    pthread_attr_destroy(&record_attributes);
  }
#else
  ret = buffer_thread_create(&g_buffer.record_thread, buffer_record_thread_main, NULL);
#endif
  if (ret != 0)
    {
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.recording = false;
      g_buffer.record_thread_started = false;
      pthread_mutex_unlock(&g_buffer.lock);
      buffer_set_status("录音线程启动失败：%d", ret);
      return -ret;
    }

  buffer_set_status("录音中，松开保存");
  return 0;
}

static void buffer_stop_recording(void)
{
  bool join_needed;
  bool was_recording;

  pthread_mutex_lock(&g_buffer.lock);
  was_recording = g_buffer.recording;
  join_needed = g_buffer.recording || g_buffer.record_thread_started;
  g_buffer.record_stop = true;
  pthread_mutex_unlock(&g_buffer.lock);

  if (!join_needed) return;
  if (join_needed)
    {
      if (was_recording) buffer_set_status("正在保存录音");
      pthread_join(g_buffer.record_thread, NULL);
    }

  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.recording = false;
  g_buffer.record_thread_started = false;
  pthread_mutex_unlock(&g_buffer.lock);
}

bool buffer_touch_record_active(void)
{
  bool active;
  pthread_mutex_lock(&g_buffer.lock);
  active = g_buffer.recording || g_buffer.touch_requested;
  pthread_mutex_unlock(&g_buffer.lock);
  return active;
}

int buffer_touch_record_start(void)
{
  return buffer_touch_record_start_for_card(NULL);
}
static int buffer_record_request(const char *card_id,enum buffer_capture_target_e target)
{
  if (card_id && !buffer_agent_valid_id(card_id)) return -EINVAL;
  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized || g_buffer.stopping || g_buffer.recording ||
      g_buffer.touch_requested)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return -EBUSY;
    }
  snprintf(g_buffer.record_context_card, sizeof(g_buffer.record_context_card), "%s", card_id ? card_id : "");
  __atomic_store_n(&g_buffer.record_decision, 0, __ATOMIC_RELEASE);
  g_buffer.record_target=target;
  snprintf(g_buffer.record_id,sizeof(g_buffer.record_id),"vela-%lld-%08x",(long long)buffer_monotonic_ms(),(unsigned int)arc4random());
  buffer_voice_begin_locked();
  g_buffer.touch_requested = true;
  g_buffer.touch_released = false;
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

int buffer_touch_record_start_for_card(const char *card_id)
{ return buffer_record_request(card_id,card_id?BUFFER_CAPTURE_TARGET_ASSISTANT:BUFFER_CAPTURE_TARGET_AUTO); }
int buffer_assistant_record_start(void)
{ return buffer_record_request(NULL,BUFFER_CAPTURE_TARGET_ASSISTANT); }

void buffer_touch_record_stop(void)
{
  buffer_live_asr_end();
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.touch_released = true;
  if(g_buffer.voice.phase==BUFFER_VOICE_RECORDING)g_buffer.voice.phase=BUFFER_VOICE_CLOSING;
  if (g_buffer.recording) g_buffer.record_stop = true;
  pthread_mutex_unlock(&g_buffer.lock);
}

int buffer_touch_record_cancel(void)
{
  int expected = 0;
  bool accepted = __atomic_compare_exchange_n(&g_buffer.record_decision,
      &expected, 1, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
  buffer_touch_record_stop();
  return accepted || expected == 1 ? 0 : -EALREADY;
}

/* S1 exposes the five LRADC keys through the NuttX buttons interface, not
 * Linux input_event. Read only on edges so the driver never floods UART. */
#if defined(CONFIG_DRIVERS_LRADC) && defined(CONFIG_ARCH_BOARD_R528S3_GEMINI_S1)
static void buffer_home_key_poll(int *fd,struct buffer_home_key_s *key,bool *owned)
{
  static uint64_t retry_at;
  uint64_t now=(uint64_t)buffer_monotonic_ms();
  bool sample=false;
  if(*fd<0){
    if(now<retry_at)return;
    retry_at=now+5000;*fd=open("/dev/input/event1",O_RDONLY|O_NONBLOCK);
    if(*fd<0)return;
    sample=true;
  }
  struct pollfd event={.fd=*fd,.events=POLLIN};
  int ready=poll(&event,1,0);
  if(ready<0||(event.revents&(POLLERR|POLLHUP|POLLNVAL))){
    close(*fd);*fd=-1;
    if(*owned){buffer_touch_record_stop();buffer_ui_home_command(3);*owned=false;}
    memset(key,0,sizeof(*key));return;
  }
  bool down=key->raw;
  if(sample||(ready>0&&(event.revents&POLLIN))){
    btn_buttonset_t keys=0;
    if(read(*fd,&keys,sizeof(keys))==(ssize_t)sizeof(keys)){
      down=(keys&16u)!=0; /* S1 HOME measured: ADC 40, LRADC key 5, mask 16. */
      syslog(LOG_INFO,"Buffer keys: mask=%lu\n",(unsigned long)keys);
    }
  }
  enum buffer_home_action_e action=buffer_home_key_step(key,down,now);
  if(action==BUFFER_HOME_START){
    if(buffer_oobe_setup_complete()&&buffer_assistant_record_start()==0){
      *owned=true;buffer_ui_home_command(2);
    }
  }else if(action==BUFFER_HOME_STOP){
    if(*owned){buffer_touch_record_stop();buffer_ui_home_command(3);*owned=false;}
  }else if(action==BUFFER_HOME_SHORT)buffer_ui_home_command(1);
}
#endif

/* LVGL only posts intent; file I/O and pthread joins stay on this worker. */
static void *buffer_input_thread_main(void *arg)
{
  (void)arg;
#if defined(CONFIG_DRIVERS_LRADC) && defined(CONFIG_ARCH_BOARD_R528S3_GEMINI_S1)
  int home_fd=-1;struct buffer_home_key_s home_key={0};bool home_owned=false;
#endif
  while (buffer_is_running())
    {
#if defined(CONFIG_DRIVERS_LRADC) && defined(CONFIG_ARCH_BOARD_R528S3_GEMINI_S1)
      buffer_home_key_poll(&home_fd,&home_key,&home_owned);
#endif
      bool requested;
      bool released;
      pthread_mutex_lock(&g_buffer.lock);
      requested = g_buffer.touch_requested;
      released = g_buffer.touch_released;
      if (!requested && released) g_buffer.touch_requested = true;
      pthread_mutex_unlock(&g_buffer.lock);
      if (requested)
        {
          int ret = 0;
          buffer_stop_recording();
          /* A tap released before the worker wakes is not an empty recording. */
          if (!released) ret = buffer_start_recording();
          pthread_mutex_lock(&g_buffer.lock);
          g_buffer.touch_requested = false;
          released = g_buffer.touch_released;
          if(ret<0){g_buffer.voice.phase=BUFFER_VOICE_ERROR;g_buffer.voice.error=ret;}
          else if(released&&!g_buffer.recording&&g_buffer.voice.phase==BUFFER_VOICE_CLOSING)g_buffer.voice.phase=BUFFER_VOICE_CANCELLED;
          pthread_mutex_unlock(&g_buffer.lock);
          if (ret == 0 && released) buffer_stop_recording();
        }
      else if (released)
        {
          buffer_stop_recording();
          pthread_mutex_lock(&g_buffer.lock);
          g_buffer.touch_released = false;
          g_buffer.touch_requested = false;
          pthread_mutex_unlock(&g_buffer.lock);
        }
      usleep(20000);
    }
#if defined(CONFIG_DRIVERS_LRADC) && defined(CONFIG_ARCH_BOARD_R528S3_GEMINI_S1)
  if(home_fd>=0)close(home_fd);
  if(home_owned)buffer_touch_record_stop();
#endif
  return NULL;
}

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
static void source_startup_tick(void)
{
  static int previous_error;
  if(!__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE))return;
  int ready=buffer_source_events_init();
  if(ready){
    if(ready!=previous_error)syslog(LOG_WARNING,"Buffer recovery: phase=journal result=%d\n",ready);
    previous_error=ready;return;
  }
  pthread_mutex_lock(&g_mode_commit_lock);
  char mode[BUFFER_MODE_SIZE],pending_mode[BUFFER_MODE_SIZE],mode_id[BUFFER_ACTION_ID_SIZE];
  char card_id[BUFFER_ID_SIZE],action_id[BUFFER_ACTION_ID_SIZE],action[BUFFER_ACTION_SIZE];
  uint32_t interval=CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
  int saved=buffer_store_load_mode(mode,sizeof(mode));
  int pending=buffer_store_load_pending_mode(pending_mode,sizeof(pending_mode),mode_id,sizeof(mode_id));
  int requested=buffer_store_load_pending_action(card_id,sizeof(card_id),action_id,sizeof(action_id),action,sizeof(action));
  int configured=buffer_store_load_focus_interval(&interval);
  if((saved&&saved!=-ENOENT)||(pending&&pending!=-ENOENT)||(requested&&requested!=-ENOENT)||(configured&&configured!=-ENOENT)){
    int error=saved&&saved!=-ENOENT?saved:pending&&pending!=-ENOENT?pending:
        requested&&requested!=-ENOENT?requested:configured;
    if(error!=previous_error)syslog(LOG_WARNING,"Buffer recovery: mode=%d pending_mode=%d pending_action=%d focus_interval=%d\n",saved,pending,requested,configured);
    previous_error=error;pthread_mutex_unlock(&g_mode_commit_lock);return;
  }
  pthread_mutex_lock(&g_buffer.lock);
  // Restore the same committed local state used at normal startup. Imported
  // read-only observations never populate these mode/request files.
  if(!saved)snprintf(g_buffer.mode,sizeof(g_buffer.mode),"%s",mode);
  if(!pending){
    snprintf(g_buffer.mode,sizeof(g_buffer.mode),"%s",pending_mode);
    snprintf(g_buffer.mode_id,sizeof(g_buffer.mode_id),"%s",mode_id);g_buffer.mode_dirty=true;
  }
  if(!requested){
    snprintf(g_buffer.action_card_id,sizeof(g_buffer.action_card_id),"%s",card_id);
    snprintf(g_buffer.action_id,sizeof(g_buffer.action_id),"%s",action_id);
    snprintf(g_buffer.action,sizeof(g_buffer.action),"%s",action);g_buffer.action_requested=true;
  }
  if(!configured)g_buffer.focus_interval_ms=interval;
  focus_runtime_reload_locked();research_reconcile=true;
  __atomic_store_n(&source_startup_pending,false,__ATOMIC_RELEASE);
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_mode_commit_lock);
  previous_error=0;syslog(LOG_INFO,"Buffer recovery: runtime restored\n");
  buffer_agent_projection_changed();buffer_agent_wake();
}
#endif

static void *buffer_timer_thread_main(void *arg)
{
  (void)arg;
  while (buffer_is_running())
    {
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      source_startup_tick();
      if(!buffer_voice_foreground_busy()&&buffer_source_events_maintenance()>0)buffer_agent_wake();
      if(!__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE)){
#endif
      buffer_focus_tick();
      research_tick();
      buffer_backlight_tick();
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      }
#endif
      usleep(100000);
    }

  buffer_backlight_shutdown();
  return NULL;
}

static void buffer_service_stop(void);
static pthread_mutex_t g_service_stop_lock = PTHREAD_MUTEX_INITIALIZER;

static int buffer_start_runtime_thread(pthread_t *thread,void *(*entry)(void *))
{
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  pthread_attr_t attributes;int ret=pthread_attr_init(&attributes);if(ret)return ret;
  ret=pthread_attr_setstacksize(&attributes,32*1024);
  if(!ret)ret=pthread_create(thread,&attributes,entry,NULL);
  pthread_attr_destroy(&attributes);return ret;
#else
  return buffer_thread_create(thread, entry, NULL);
#endif
}

static int buffer_service_start(void)
{
  int ret;

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.initialized)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return 0;
    }
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  __atomic_store_n(&source_startup_pending,true,__ATOMIC_RELEASE);
#endif
  g_buffer.stopping = false;
  g_buffer.initialized = true;
  g_buffer.recording = false;
  g_buffer.record_thread_started = false;
  g_buffer.pairing_thread_started = false;
  g_buffer.timer_thread_started = false;
  g_buffer.sync_requested = true;
  g_buffer.pending_count = 0;
  pthread_mutex_unlock(&g_buffer.lock);

  ret = buffer_store_init();
  if (ret < 0)
    {
      buffer_set_status("Buffer 存储初始化失败：%d", ret);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.initialized = false;
      pthread_mutex_unlock(&g_buffer.lock);
      return ret;
    }

  (void)buffer_fabric_init();

  {
    char device_id[BUFFER_ID_SIZE];
    char pairing_code[BUFFER_PAIRING_CODE_SIZE];
    char saved_mode[BUFFER_MODE_SIZE];
    char pending_mode[BUFFER_MODE_SIZE];
    char pending_mode_id[BUFFER_ACTION_ID_SIZE];
    char pending_card_id[BUFFER_ID_SIZE];
    char pending_action_id[BUFFER_ACTION_ID_SIZE];
    char pending_action[BUFFER_ACTION_SIZE];
    struct buffer_card_s cached_cards[BUFFER_CARD_LIMIT];
    int cached_count;

    ret = buffer_store_load_device_id(device_id, sizeof(device_id));
    if (ret < 0)
      {
        buffer_set_status("设备身份初始化失败：%d", ret);
        pthread_mutex_lock(&g_buffer.lock);
        g_buffer.initialized = false;
        pthread_mutex_unlock(&g_buffer.lock);
        return ret;
      }

    ret = buffer_store_load_pairing_code(pairing_code, sizeof(pairing_code));
    if (ret < 0)
      {
        buffer_set_status("配对码初始化失败：%d", ret);
        pthread_mutex_lock(&g_buffer.lock);
        g_buffer.initialized = false;
        pthread_mutex_unlock(&g_buffer.lock);
        return ret;
      }

    cached_count = buffer_store_load_cards(cached_cards, BUFFER_CARD_LIMIT);
    if (cached_count < 0)
      {
        cached_count = 0;
      }

    pthread_mutex_lock(&g_buffer.lock);
    snprintf(g_buffer.device_id, sizeof(g_buffer.device_id), "%s", device_id);
    snprintf(g_buffer.pairing_code, sizeof(g_buffer.pairing_code), "%s",
             pairing_code);
    if (buffer_store_load_mode(saved_mode, sizeof(saved_mode)) == 0)
      {
        snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", saved_mode);
      }
    if (buffer_store_load_pending_mode(pending_mode, sizeof(pending_mode),
                                       pending_mode_id,
                                       sizeof(pending_mode_id)) == 0)
      {
        snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", pending_mode);
        snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "%s",
                 pending_mode_id);
        g_buffer.mode_dirty = true;
      }
    if (buffer_store_load_pending_action(pending_card_id,
                                         sizeof(pending_card_id),
                                         pending_action_id,
                                         sizeof(pending_action_id),
                                         pending_action,
                                         sizeof(pending_action)) == 0)
      {
        snprintf(g_buffer.action_card_id, sizeof(g_buffer.action_card_id), "%s",
                 pending_card_id);
        snprintf(g_buffer.action_id, sizeof(g_buffer.action_id), "%s",
                 pending_action_id);
        snprintf(g_buffer.action, sizeof(g_buffer.action), "%s",
                 pending_action);
        g_buffer.action_requested = true;
      }
    memset(g_buffer.cards, 0, sizeof(g_buffer.cards));
    memcpy(g_buffer.cards, cached_cards,
           (size_t)cached_count * sizeof(cached_cards[0]));
    g_buffer.card_count = cached_count;
    g_buffer.card_index = 0;
    g_buffer.cards_stale = cached_count > 0;
    pthread_mutex_unlock(&g_buffer.lock);
  }

  {
    uint32_t interval = CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
    (void)buffer_store_load_focus_interval(&interval);
    pthread_mutex_lock(&g_buffer.lock);
    g_buffer.focus_interval_ms = interval;
    g_buffer.focus_revision[0] = '\0';
    focus_runtime_reload_locked();
    research_known=false;research_display_expired=false;
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
    research_reconcile=true;
#endif
    pthread_mutex_unlock(&g_buffer.lock);
  }
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(!__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE)){
#endif
  buffer_focus_tick();
  research_tick();
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  }
#endif

  int initial_pending=buffer_store_pending_count();
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pending_count = initial_pending;
  pthread_mutex_unlock(&g_buffer.lock);

  ret = buffer_start_runtime_thread(&g_buffer.input_thread,buffer_input_thread_main);
  if (ret != 0)
    {
      buffer_set_status("录音控制线程启动失败：%d", ret);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.initialized = false;
      pthread_mutex_unlock(&g_buffer.lock);
      return -ret;
    }
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.input_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);

  {
    pthread_attr_t attr;

    /* The wire sync frame alone uses about 8 KiB on ARM64.  Leave room
     * for filesystem, protocol parsing and TLS below that frame.
     */

    ret = pthread_attr_init(&attr);
    if (ret == 0)
      {
        ret = pthread_attr_setstacksize(&attr, 32768);
        if (ret == 0)
          {
            ret = pthread_create(&g_buffer.sync_thread, &attr,
                                 buffer_wire_thread_main, NULL);
          }
        pthread_attr_destroy(&attr);
      }
  }
  if (ret != 0)
    {
      buffer_set_status("同步线程启动失败：%d", ret);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.stopping = true;
      pthread_mutex_unlock(&g_buffer.lock);
      pthread_join(g_buffer.input_thread, NULL);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.initialized = false;
      g_buffer.input_thread_started = false;
      pthread_mutex_unlock(&g_buffer.lock);
      return -ret;
    }
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.sync_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);

  ret = buffer_thread_create(&g_buffer.pairing_thread, buffer_pairing_thread_main, NULL);
  if (ret != 0)
    {
      buffer_set_status("配对线程启动失败：%d", ret);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.stopping = true;
      pthread_mutex_unlock(&g_buffer.lock);
      pthread_join(g_buffer.input_thread, NULL);
      pthread_join(g_buffer.sync_thread, NULL);
      pthread_mutex_lock(&g_buffer.lock);
      g_buffer.initialized = false;
      g_buffer.input_thread_started = false;
      g_buffer.sync_thread_started = false;
      pthread_mutex_unlock(&g_buffer.lock);
      return -ret;
    }
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pairing_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);

  ret = buffer_start_runtime_thread(&g_buffer.timer_thread,buffer_timer_thread_main);
  if (ret != 0)
    {
      buffer_set_status("计时线程启动失败：%d", ret);
      buffer_service_stop();
      return -ret;
    }
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.timer_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);
  ret = buffer_agent_start();
  if (ret < 0)
    {
      buffer_set_status("设备 Agent 工作器启动失败：%d", ret);
      buffer_service_stop();
      return ret;
    }
  ret=buffer_source_projector_start();
  if(ret<0){buffer_set_status("设备历史恢复工作器启动失败：%d",ret);buffer_service_stop();return ret;}
  return 0;
}

static void buffer_service_stop(void)
{
  bool input_started;
  bool sync_started;
  bool record_started;
  bool pairing_started;
  bool timer_started;

  pthread_mutex_lock(&g_service_stop_lock);
  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      pthread_mutex_unlock(&g_service_stop_lock);
      return;
    }
  g_buffer.stopping = true;
  g_buffer.record_stop = true;
  input_started = g_buffer.input_thread_started;
  sync_started = g_buffer.sync_thread_started;
  record_started = g_buffer.record_thread_started;
  pairing_started = g_buffer.pairing_thread_started;
  timer_started = g_buffer.timer_thread_started;
  pthread_mutex_unlock(&g_buffer.lock);

  buffer_source_projector_stop();
  buffer_agent_stop();

  if (timer_started)
    {
      pthread_join(g_buffer.timer_thread, NULL);
    }
  if (input_started)
    {
      pthread_join(g_buffer.input_thread, NULL);
    }
  pthread_mutex_lock(&g_buffer.lock);
  record_started = g_buffer.record_thread_started;
  pthread_mutex_unlock(&g_buffer.lock);
  if (record_started)
    {
      pthread_join(g_buffer.record_thread, NULL);
    }
  if (sync_started)
    {
      pthread_join(g_buffer.sync_thread, NULL);
    }
  if (pairing_started)
    {
      pthread_join(g_buffer.pairing_thread, NULL);
    }

  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.initialized = false;
  g_buffer.phone_synced = false;
  g_buffer.touch_requested = false;
  g_buffer.touch_released = false;
  g_buffer.input_thread_started = false;
  g_buffer.sync_thread_started = false;
  g_buffer.record_thread_started = false;
  g_buffer.pairing_thread_started = false;
  g_buffer.timer_thread_started = false;
  g_buffer.recording = false;
  pthread_mutex_unlock(&g_buffer.lock);
  pthread_mutex_unlock(&g_service_stop_lock);
}

static void buffer_record_for_ms(unsigned int duration_ms)
{
  int ret;

  ret = buffer_start_recording();
  if (ret < 0)
    {
      return;
    }

  /* A diagnostic duration measures actual capture, not storage/codec setup. */
  int64_t ready_deadline = buffer_monotonic_ms() + 30000;
  while (buffer_is_running() && buffer_touch_record_active() &&
         buffer_audio_phase() != BUFFER_AUDIO_CAPTURING &&
         buffer_monotonic_ms() < ready_deadline)
    usleep(20000);

  while (duration_ms > 0 && buffer_is_running() && buffer_touch_record_active())
    {
      unsigned int slice = duration_ms > 100 ? 100 : duration_ms;
      usleep(slice * 1000);
      duration_ms -= slice;
    }
  buffer_stop_recording();
}

static int buffer_init_standalone_lvgl(lv_nuttx_result_t *result)
{
  lv_nuttx_dsc_t info;

  if (lv_is_initialized())
    {
      return 0;
    }

  lv_init();
  lv_nuttx_dsc_init(&info);
#ifdef CONFIG_LV_USE_NUTTX_LCD
  info.fb_path = "/dev/lcd0";
#endif
  lv_nuttx_init(&info, result);
  if (result->disp == NULL)
    {
      lv_deinit();
      return -ENODEV;
    }

  return 1;
}

#ifdef CONFIG_SCHED_BACKTRACE
static unsigned g_ui_monitor_running;
static void *buffer_ui_monitor(void *context)
{
 pid_t owner=(pid_t)(intptr_t)context;
 unsigned previous=buffer_ui_progress_sequence(),stalled=0;
 for(unsigned n=0;__atomic_load_n(&g_ui_monitor_running,__ATOMIC_ACQUIRE);n++){
  usleep(1000000);
  if(!__atomic_load_n(&g_ui_monitor_running,__ATOMIC_ACQUIRE))break;
  unsigned now=buffer_ui_progress_sequence();
  stalled=now==previous?stalled+1:0;previous=now;
  if(n<10||stalled==3)
   syslog(LOG_ERR,"Buffer UI monitor: pid=%d sequence=%u phase=%s stalled=%u\n",
          (int)owner,now,buffer_ui_progress_stage(),stalled);
  if(stalled==3)sched_dumpstack(owner);
 }
 return NULL;
}
#endif

#include "buffer_device_settings.h"
int main(int argc, char *argv[])
{
  bool standalone_lvgl = false;
  lv_nuttx_result_t result;
  int ret;

  if (argc > 1 && strcmp(argv[1], "agent") == 0)
    return buffer_agent_command(argc - 2, argv + 2);

  if (argc > 1 && strcmp(argv[1], "wifi") == 0)
    {
      if(argc==3&&!strcmp(argv[2],"status")){buffer_wifi_status();return 0;}
      if (argc < 4)
        {
          printf("用法：buffer_app wifi <SSID> <密码>\n");
          return -EINVAL;
        }

      ret = buffer_wifi_configure(argv[2], argv[3]);
      if (ret == 0)
        {
          printf("Wi-Fi 已连接，配对服务会在网络就绪后广播。\n");
        }
      else
        {
          printf("Wi-Fi 配置失败：%d\n", ret);
        }
      return ret;
    }

  if (argc > 1 && strcmp(argv[1], "pairing") == 0)
    {
      char device_id[BUFFER_ID_SIZE];
      char pairing_code[BUFFER_PAIRING_CODE_SIZE];

      ret = buffer_store_init();
      if (ret == 0)
        {
          ret = buffer_store_load_device_id(device_id, sizeof(device_id));
        }
      if (ret == 0)
        {
          ret = buffer_store_load_pairing_code(pairing_code,
                                               sizeof(pairing_code));
        }
      if (ret == 0)
        {
          printf("Buffer Vela %s\n  device_id=%s\n  pairing_code=%s\n"
                 "  discovery_udp=%d\n  pairing_tcp=%d\n  data_tcp=%d\n",
                 "pairing", device_id, pairing_code,
                 CONFIG_BUFFER_VELA_DISCOVERY_PORT,
                 CONFIG_BUFFER_VELA_PAIRING_PORT,
                 CONFIG_BUFFER_VELA_PHONE_PORT);
        }
      else
        {
          printf("Buffer pairing status failed: %d\n", ret);
        }
      return ret;
    }

  if (argc > 1 && strcmp(argv[1], "stop") == 0)
    {
      pthread_mutex_lock(&g_ui_owner_lock);
      g_ui_owner_closing = g_ui_owner_active;
      pthread_mutex_unlock(&g_ui_owner_lock);
      buffer_service_stop();
      pthread_mutex_lock(&g_ui_owner_lock);
      while (g_ui_owner_active) pthread_cond_wait(&g_ui_owner_changed, &g_ui_owner_lock);
      pthread_mutex_unlock(&g_ui_owner_lock);
      printf("Buffer stopped\n");
      return 0;
    }

  if (argc > 1 && strcmp(argv[1], "mode") == 0)
    {
      if (argc < 3)
        {
          printf("用法：buffer_app mode normal|focus|bedtime|rabbit_hole\n");
          return -EINVAL;
        }
      ret = buffer_service_start();
      if (ret == 0)
        {
          buffer_request_mode(argv[2]);
          buffer_request_sync();
        }
      return ret;
    }

  if (argc > 1 && strcmp(argv[1], "sync") == 0)
    {
#ifdef CONFIG_BUFFER_VELA_FABRIC
      ret = buffer_fabric_request_sync();
      if (ret == 0)
        printf("Fabric sync request queued for the authenticated phone session.\n");
      else
        printf("Fabric sync request unavailable: %d\n", ret);
#else
      ret = buffer_store_init();
      if (ret == 0)
        {
          ret = buffer_wire_sync_once();
        }
#endif
      return ret;
    }

  if (argc == 3 && !strcmp(argv[1], "menu"))
    {
      if (!strcmp(argv[2], "status"))
        { printf("UI refreshes=%u menu_open=%u stage=%s progress=%u\n",buffer_ui_heartbeat(),buffer_ui_menu_state(),buffer_ui_progress_stage(),buffer_ui_progress_sequence());return 0; }
      int command=!strcmp(argv[2],"open")?1:!strcmp(argv[2],"close")?2:0;
      if (!command) return -EINVAL;
      buffer_ui_menu_command(command);
      printf("Menu %s requested\n",argv[2]);return 0;
    }

  if (argc == 3 && !strcmp(argv[1], "voice") && !strcmp(argv[2], "status"))
    {
      char status[BUFFER_STATUS_SIZE];
      pthread_mutex_lock(&g_buffer.lock);
      snprintf(status, sizeof(status), "%s", g_buffer.status);
      bool recording = g_buffer.recording, pending = g_buffer.touch_requested;
      pthread_mutex_unlock(&g_buffer.lock);
      printf("voice recording=%d pending=%d phase=%d status=%s\n", recording, pending, buffer_audio_phase(), status);
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      printf("voice source_recovery_pending=%d\n",__atomic_load_n(&source_startup_pending,__ATOMIC_ACQUIRE));
#endif
      return 0;
    }

  if (argc > 1 && strcmp(argv[1], "record") == 0)
    {
      unsigned int duration_ms = 3000;
      if (argc > 2)
        {
          duration_ms = (unsigned int)strtoul(argv[2], NULL, 10);
        }
      ret = buffer_service_start();
      if (ret == 0)
        {
          buffer_record_for_ms(duration_ms);
        }
      return ret;
    }

  if(argc>2&&!strcmp(argv[1],"calendar")){
    if(!strcmp(argv[2],"status")){unsigned state=buffer_calendar_ui_state(),date=state>>3;
      printf("UI refreshes=%u calendar_open=%u date_known=%u expanded=%u date=%04u-%02u-%02u\n",
       buffer_ui_heartbeat(),state&1u,!!(state&4u),!!(state&2u),date/10000,date/100%100,date%100);return 0;}
    int command=!strcmp(argv[2],"open")?1:!strcmp(argv[2],"close")?2:!strcmp(argv[2],"next")?3:!strcmp(argv[2],"previous")?4:0;
    if(!command)return -EINVAL;
    buffer_ui_calendar_command(command);
    printf("Calendar %s requested\n",argv[2]);return 0;
  }

  pthread_mutex_lock(&g_ui_owner_lock);
  while (g_ui_owner_closing) pthread_cond_wait(&g_ui_owner_changed, &g_ui_owner_lock);
  pthread_mutex_unlock(&g_ui_owner_lock);
#ifdef CONFIG_BUFFER_VELA_S1_PRIVATE_STORAGE
  /* Factory storage must be provisioned before creating the display and
   * its input callbacks. A missing volume needs no LVGL startup/teardown. */
  ret = buffer_private_storage_prepare();
  if (ret < 0)
    {
      syslog(LOG_ERR, "Buffer: private storage not ready: %d\n", ret);
      printf("Buffer: private storage not ready: %d\n", ret);
      return ret;
    }
#endif
  ret=buffer_device_reset_apply_boot();
  if(ret<0){syslog(LOG_ERR,"Buffer: reset recovery failed: %d\n",ret);return ret;}
  ret = buffer_init_standalone_lvgl(&result);
  if (ret < 0)
    {
      syslog(LOG_ERR, "Buffer: LVGL initialization failed: %d\n", ret);
      printf("Buffer: LVGL initialization failed: %d\n", ret);
      return ret;
    }
  standalone_lvgl = ret > 0;
  if (standalone_lvgl)
    {
      pthread_mutex_lock(&g_ui_owner_lock);
      g_ui_owner_active = true;
      pthread_mutex_unlock(&g_ui_owner_lock);
    }

  if(standalone_lvgl)buffer_ui_startup_show();

  ret = buffer_service_start();
  if (ret < 0)
    {
      printf("Buffer: service startup failed: %d\n", ret);
      if (standalone_lvgl)
        {
          lv_nuttx_deinit(&result);
          lv_deinit();
          buffer_ui_owner_done();
        }
      return ret;
    }

  syslog(LOG_INFO, "Buffer: services ready; initializing UI\n");
  ret = buffer_ui_init(!standalone_lvgl);
  if (ret < 0)
    {
      printf("Buffer: UI initialization failed: %d\n", ret);
      buffer_service_stop();
      if (standalone_lvgl)
        {
          lv_nuttx_deinit(&result);
          lv_deinit();
          buffer_ui_owner_done();
        }
      return ret;
    }

  syslog(LOG_INFO, "Buffer: UI initialized\n");
  if (!standalone_lvgl)
    {
      printf("Buffer started on the existing LVGL loop\n");
      return 0;
    }

#ifdef CONFIG_SCHED_BACKTRACE
  pthread_t monitor;
  __atomic_store_n(&g_ui_monitor_running,1,__ATOMIC_RELEASE);
  if(!buffer_thread_create(&monitor,buffer_ui_monitor,(void *)(intptr_t)getpid()))pthread_detach(monitor);
#endif
  while (true)
    {
      /* Persistence may own the runtime mutex for a slow NAND transaction.
       * Rendering and input must keep running against the last snapshot. */
      if (pthread_mutex_trylock(&g_buffer.lock) == 0)
        {
          bool running = g_buffer.initialized && !g_buffer.stopping;
          pthread_mutex_unlock(&g_buffer.lock);
          if (!running) break;
        }
      buffer_ui_progress("lvgl-draw-input");
      uint32_t idle = lv_timer_handler();
      buffer_ui_progress("idle");
      /* LV_NO_TIMER_READY is UINT32_MAX, not a safe microsecond delay.
       * Bound sleep so newly queued work/input cannot strand the event loop. */
      if (idle > 20) idle = 20;
      usleep((idle > 0 ? idle : 5) * 1000);
    }

#ifdef CONFIG_SCHED_BACKTRACE
  __atomic_store_n(&g_ui_monitor_running,0,__ATOMIC_RELEASE);
#endif
  buffer_ui_shutdown();
  buffer_service_stop();
  lv_nuttx_deinit(&result);
  lv_deinit();
  buffer_ui_owner_done();
  return 0;
}
