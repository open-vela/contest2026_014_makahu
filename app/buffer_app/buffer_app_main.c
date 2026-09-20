/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela application
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"

#include <lvgl/lvgl.h>

#include <errno.h>
#include <fcntl.h>
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

static void *buffer_record_thread_main(void *arg)
{
  uint32_t duration = 0;
  char capture_id[BUFFER_ID_SIZE];
  char mode[BUFFER_MODE_SIZE];
  int ret;

  (void)arg;
  pthread_mutex_lock(&g_buffer.lock);
  snprintf(capture_id, sizeof(capture_id), "%s", g_buffer.record_id);
  snprintf(mode, sizeof(mode), "%s", g_buffer.record_mode);
  pthread_mutex_unlock(&g_buffer.lock);
  ret = buffer_audio_record(capture_id, mode, &g_buffer.record_stop, &duration);
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.recording = false;
  g_buffer.pending_count = buffer_store_pending_count();
  pthread_mutex_unlock(&g_buffer.lock);
  if (ret < 0)
    {
      buffer_set_status("录音保存失败：%d", ret);
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

int buffer_sync_research(const struct buffer_research_s *research)
{
  int64_t deadline;
  bool same;
  if (research == NULL || research->remaining_ms > 7200000u ||
      memchr(research->id, 0, sizeof(research->id)) == NULL ||
      memchr(research->title, 0, sizeof(research->title)) == NULL ||
      memchr(research->reason, 0, sizeof(research->reason)) == NULL) return -EINVAL;
  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.mode_dirty) { pthread_mutex_unlock(&g_buffer.lock); return 0; }
  same = g_buffer.research.present && research->present &&
         strcmp(g_buffer.research.id, research->id) == 0;
  if (same && !g_buffer.research.active && research->active)
    { pthread_mutex_unlock(&g_buffer.lock); return 0; }
  deadline = buffer_monotonic_ms() + research->remaining_ms;
  if (same && research->active && g_buffer.research_deadline < deadline)
    deadline = g_buffer.research_deadline; /* Repeated snapshots cannot restart a timer. */
  g_buffer.research = *research;
  g_buffer.research_deadline = deadline;
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
      return 0;
    }
  if (g_buffer.focus_interval_ms != config->interval_ms)
    {
      ret = buffer_store_save_focus_interval(config->interval_ms);
      if (ret < 0)
        {
          pthread_mutex_unlock(&g_buffer.lock);
          return ret;
        }
      g_buffer.focus_interval_ms = config->interval_ms;
    }
  if (config->active && strcmp(g_buffer.focus_revision, config->revision) != 0)
    {
      if (g_buffer.focus_started_at == 0) g_buffer.focus_started_at = now > 0 ? now : 1;
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
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

/* Called with the runtime lock held. */
static uint32_t buffer_focus_interval(void)
{
  return g_buffer.focus_interval_ms != 0 ? g_buffer.focus_interval_ms
                                        : CONFIG_BUFFER_VELA_FOCUS_REMINDER_MS;
}

static void buffer_focus_tick(void)
{
  int64_t now = buffer_monotonic_ms();
  bool reminder_due = false;

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.focus_reminder_due && g_buffer.focus_reminder_until != 0 &&
      now >= g_buffer.focus_reminder_until)
    {
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
    }
  if (strcmp(g_buffer.mode, "focus") == 0)
    {
      if (g_buffer.focus_started_at == 0)
        {
          g_buffer.focus_started_at = now;
          g_buffer.next_care_at = now +
                                  (int64_t)buffer_focus_interval();
        }
      else if (g_buffer.next_care_at != 0 && now >= g_buffer.next_care_at)
        {
          g_buffer.focus_reminder_due = true;
          g_buffer.focus_reminder_until = now + 15000;
          g_buffer.next_care_at = now +
                                  (int64_t)buffer_focus_interval();
          reminder_due = true;
        }
    }
  else
    {
      g_buffer.focus_started_at = 0;
      g_buffer.next_care_at = 0;
      g_buffer.focus_reminder_due = false;
      g_buffer.focus_reminder_until = 0;
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
               (long long)((now - started_at) / 60000));
    }
  else if (next_care_at > now)
    {
      snprintf(text, text_size, "专注 %lld 分钟 · 下次照顾 %lld 分钟后",
               (long long)((now - started_at) / 60000),
               (long long)((next_care_at - now + 59999) / 60000));
    }
  else
    {
      snprintf(text, text_size, "专注 %lld 分钟 · 下次照顾提醒即将到达",
               (long long)((now - started_at) / 60000));
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

void buffer_request_action(const char *action)
{
  char pending_card_id[BUFFER_ID_SIZE];
  char pending_action_id[BUFFER_ACTION_ID_SIZE];
  char pending_action[BUFFER_ACTION_SIZE];
  int ret;

  if (action == NULL || action[0] == '\0')
    {
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

  pthread_mutex_lock(&g_buffer.lock);
  ret = buffer_store_save_pending_mode(mode, mode_id);
  if (ret < 0)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      buffer_set_status("模式切换持久化失败：%d", ret);
      return;
    }
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", mode);
  snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "%s", mode_id);
  g_buffer.mode_dirty = true;
  g_buffer.focus_revision[0] = '\0';
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
  pthread_mutex_unlock(&g_buffer.lock);
  if (buffer_store_save_mode(mode) < 0)
    {
      buffer_set_status("模式已切换，待同步状态已保存");
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
  pthread_mutex_lock(&g_buffer.lock);
  ret = buffer_store_save_cards(cards, count);
  if (ret < 0)
    {
      g_buffer.cards_stale = true;
      pthread_mutex_unlock(&g_buffer.lock);
      buffer_set_status("卡片缓存保存失败：%d", ret);
      return ret;
    }

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
  g_buffer.pending_count = buffer_store_pending_count();
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

void buffer_sync_remote_mode(const char *mode)
{
  int64_t now;
  bool changed = false;
  int persist_ret = 0;

  if (mode == NULL ||
      (strcmp(mode, "normal") != 0 && strcmp(mode, "focus") != 0 &&
       strcmp(mode, "bedtime") != 0 && strcmp(mode, "rabbit_hole") != 0))
    {
      return;
    }

  now = buffer_monotonic_ms();
  pthread_mutex_lock(&g_buffer.lock);
  /* A locally selected mode has priority until its mode_id is ACKed.  The
   * next cards response then carries the phone's authoritative mode. */
  if (!g_buffer.mode_dirty && strcmp(g_buffer.mode, mode) != 0)
    {
      /* Persist while holding the same lock used by buffer_request_mode().
       * Otherwise an older cards response could overwrite a newer local mode
       * file after the local setter had released the lock.  Keep the old
       * in-memory mode on failure so the next response retries the write. */
      persist_ret = buffer_store_save_mode(mode);
      if (persist_ret == 0)
        {
          snprintf(g_buffer.mode, sizeof(g_buffer.mode), "%s", mode);
          g_buffer.mode_id[0] = '\0';
          g_buffer.focus_revision[0] = '\0';
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
          changed = true;
        }
    }
  pthread_mutex_unlock(&g_buffer.lock);

  if (persist_ret < 0)
    {
      buffer_set_status("手机模式保存失败：%d，将继续重试", persist_ret);
    }
  else if (changed)
    {
      buffer_set_status("手机已切换为 %s 模式", mode);
    }
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

  if (buffer_store_queue_limit_reached())
    {
      buffer_set_status("离线队列已满，请先连接手机");
      return -ENOSPC;
    }

  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized || g_buffer.stopping || g_buffer.recording)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return -EBUSY;
    }

  snprintf(g_buffer.record_id, sizeof(g_buffer.record_id), "vela-%lld-%08x",
           (long long)buffer_monotonic_ms(), (unsigned int)arc4random());
  snprintf(g_buffer.record_mode, sizeof(g_buffer.record_mode), "%s",
           g_buffer.mode);
  g_buffer.record_stop = false;
  g_buffer.recording = true;
  g_buffer.record_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);

  ret = pthread_create(&g_buffer.record_thread, NULL,
                       buffer_record_thread_main, NULL);
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

  pthread_mutex_lock(&g_buffer.lock);
  join_needed = g_buffer.recording || g_buffer.record_thread_started;
  g_buffer.record_stop = true;
  pthread_mutex_unlock(&g_buffer.lock);

  if (join_needed)
    {
      buffer_set_status("正在保存录音");
      pthread_join(g_buffer.record_thread, NULL);
    }

  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.recording = false;
  g_buffer.record_thread_started = false;
  g_buffer.pending_count = buffer_store_pending_count();
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
  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized || g_buffer.stopping || g_buffer.recording ||
      g_buffer.touch_requested)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return -EBUSY;
    }
  g_buffer.touch_requested = true;
  g_buffer.touch_released = false;
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

void buffer_touch_record_stop(void)
{
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.touch_released = true;
  if (g_buffer.recording) g_buffer.record_stop = true;
  pthread_mutex_unlock(&g_buffer.lock);
}

/* LVGL only posts intent; file I/O and pthread joins stay on this worker. */
static void *buffer_input_thread_main(void *arg)
{
  (void)arg;
  while (buffer_is_running())
    {
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
  return NULL;
}

static void *buffer_timer_thread_main(void *arg)
{
  (void)arg;
  while (buffer_is_running())
    {
      buffer_focus_tick();
      usleep(100000);
    }

  return NULL;
}

static void buffer_service_stop(void);

static int buffer_service_start(void)
{
  int ret;

  pthread_mutex_lock(&g_buffer.lock);
  if (g_buffer.initialized)
    {
      pthread_mutex_unlock(&g_buffer.lock);
      return 0;
    }
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
    pthread_mutex_unlock(&g_buffer.lock);
  }
  buffer_focus_tick();

  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.pending_count = buffer_store_pending_count();
  pthread_mutex_unlock(&g_buffer.lock);

  ret = pthread_create(&g_buffer.input_thread, NULL,
                       buffer_input_thread_main, NULL);
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

  ret = pthread_create(&g_buffer.pairing_thread, NULL,
                       buffer_pairing_thread_main, NULL);
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

  ret = pthread_create(&g_buffer.timer_thread, NULL,
                       buffer_timer_thread_main, NULL);
  if (ret != 0)
    {
      buffer_set_status("计时线程启动失败：%d", ret);
      buffer_service_stop();
      return -ret;
    }
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.timer_thread_started = true;
  pthread_mutex_unlock(&g_buffer.lock);
  return 0;
}

static void buffer_service_stop(void)
{
  bool input_started;
  bool sync_started;
  bool record_started;
  bool pairing_started;
  bool timer_started;

  pthread_mutex_lock(&g_buffer.lock);
  if (!g_buffer.initialized)
    {
      pthread_mutex_unlock(&g_buffer.lock);
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
}

static void buffer_record_for_ms(unsigned int duration_ms)
{
  int ret;

  ret = buffer_start_recording();
  if (ret < 0)
    {
      return;
    }

  while (duration_ms > 0 && buffer_is_running())
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

int main(int argc, char *argv[])
{
  bool standalone_lvgl = false;
  lv_nuttx_result_t result;
  int ret;

  if (argc > 1 && strcmp(argv[1], "wifi") == 0)
    {
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
      buffer_service_stop();
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
      ret = buffer_store_init();
      if (ret == 0)
        {
          ret = buffer_wire_sync_once();
        }
      return ret;
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

  ret = buffer_init_standalone_lvgl(&result);
  if (ret < 0)
    {
      syslog(LOG_ERR, "Buffer: LVGL initialization failed: %d\n", ret);
      printf("Buffer: LVGL initialization failed: %d\n", ret);
      return ret;
    }
  standalone_lvgl = ret > 0;

  ret = buffer_service_start();
  if (ret < 0)
    {
      printf("Buffer: service startup failed: %d\n", ret);
      if (standalone_lvgl)
        {
          lv_nuttx_deinit(&result);
          lv_deinit();
        }
      return ret;
    }

  ret = buffer_ui_init(!standalone_lvgl);
  if (ret < 0)
    {
      printf("Buffer: UI initialization failed: %d\n", ret);
      buffer_service_stop();
      if (standalone_lvgl)
        {
          lv_nuttx_deinit(&result);
          lv_deinit();
        }
      return ret;
    }

  if (!standalone_lvgl)
    {
      printf("Buffer started on the existing LVGL loop\n");
      return 0;
    }

  while (buffer_is_running())
    {
      uint32_t idle = lv_timer_handler();
      usleep((idle > 0 ? idle : 5) * 1000);
    }

  buffer_ui_shutdown();
  buffer_service_stop();
  lv_nuttx_deinit(&result);
  lv_deinit();
  return 0;
}
