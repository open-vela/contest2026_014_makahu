#include "buffer_voice.h"
/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_agent.h"
#include "buffer_agent_assistant.h"
#include "buffer_images.h"
#include "buffer_coordinator.h"
#include "buffer_agent_input.h"
#include "buffer_replica.h"
#include "buffer_fabric.h"
#include <mbedtls/sha256.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <syslog.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

pthread_mutex_t g_buffer_capture_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t worker_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t request_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t worker_wake = PTHREAD_COND_INITIALIZER;
static pthread_t worker_thread;
static bool worker_started, worker_stopping, retry_requested;
static bool manual_validation_requested;
static char interaction_priority[8][BUFFER_ID_SIZE];
static unsigned interaction_priority_count;

#define BUFFER_AGENT_CANDIDATE_LIMIT \
  (BUFFER_AGENT_RECORDS_LIMIT + CONFIG_BUFFER_VELA_QUEUE_LIMIT)
#define BUFFER_AGENT_AUTO_ATTEMPT_LIMIT 4
#define BUFFER_AGENT_RETRY_BASE_MS 5000u
#define BUFFER_AGENT_RETRY_MAX_MS 300000u

struct capture_retry_s
{
  char id[BUFFER_ID_SIZE];
  unsigned failures;
  uint64_t retry_at_ms;
  bool used;
};

static struct capture_retry_s retry_state[BUFFER_AGENT_CANDIDATE_LIMIT];
static char last_automatic_id[BUFFER_ID_SIZE];
static char last_failed_id[BUFFER_ID_SIZE];

static struct buffer_memory_capture_s memory_capture;
/* A checkpointed foreground tool waiting for local history. Resume it before
 * deck refresh/full capture enumeration; its durable record owns recovery. */
static char foreground_retry_id[BUFFER_ID_SIZE];
int buffer_agent_submit_audio(const struct buffer_memory_capture_s *job)
{
  if(!job||!job->pcm||!job->bytes||job->bytes>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES-44||
     !buffer_agent_valid_id(job->id))return -EINVAL;
  struct buffer_agent_status_s status;buffer_agent_snapshot(&status);
  if(!status.ready)return -EAGAIN;
  pthread_mutex_lock(&worker_lock);
  int ret=worker_stopping?-ESHUTDOWN:memory_capture.pcm?-EBUSY:0;
  if(!ret){memory_capture=*job;retry_requested=true;pthread_cond_signal(&worker_wake);}
  pthread_mutex_unlock(&worker_lock);return ret;
}
static void release_memory_capture(void)
{
  pthread_mutex_lock(&worker_lock);
  if(memory_capture.pcm){
    volatile unsigned char *p=memory_capture.pcm;
    for(size_t i=0;i<memory_capture.bytes;i++)p[i]=0;
    free(memory_capture.pcm);memset(&memory_capture,0,sizeof(memory_capture));
  }
  pthread_mutex_unlock(&worker_lock);
}

void buffer_agent_wake(void)
{
  pthread_mutex_lock(&worker_lock); retry_requested = true;
  pthread_cond_signal(&worker_wake); pthread_mutex_unlock(&worker_lock);
}

void buffer_agent_retry(void)
{
  pthread_mutex_lock(&worker_lock); manual_validation_requested = true;
  retry_requested = true; pthread_cond_signal(&worker_wake); pthread_mutex_unlock(&worker_lock);
}

void buffer_agent_prioritize(const char *id)
{
 if(!buffer_agent_valid_id(id))return;
 pthread_mutex_lock(&worker_lock);
 bool found=false;for(unsigned i=0;i<interaction_priority_count;i++)if(!strcmp(interaction_priority[i],id))found=true;
 if(!found){
  if(interaction_priority_count==8){memmove(interaction_priority[0],interaction_priority[1],7*sizeof(interaction_priority[0]));interaction_priority_count--;}
  strcpy(interaction_priority[interaction_priority_count++],id);
 }
 retry_requested=true;pthread_cond_signal(&worker_wake);pthread_mutex_unlock(&worker_lock);
}

static uint64_t agent_monotonic_ms(void)
{
  struct timespec now;
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
    return (uint64_t)time(NULL) * 1000;
  return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
}

static int candidate_compare(const void *left, const void *right)
{
  return strcmp((const char *)left, (const char *)right);
}

static bool candidate_contains(char ids[][BUFFER_ID_SIZE], size_t count,
                               const char *id)
{
  for (size_t i = 0; i < count; i++)
    if (!strcmp(ids[i], id)) return true;
  return false;
}

static int candidate_add(char ids[][BUFFER_ID_SIZE], size_t capacity,
                         size_t *count, const char *id)
{
  if (candidate_contains(ids, *count, id)) return 0;
  if (*count >= capacity) return -ENOSPC;
  snprintf(ids[*count], BUFFER_ID_SIZE, "%s", id);
  (*count)++;
  return 0;
}

/* Gather pending text records and raw captures into a bounded result, then
 * sort for fair stable-ID rotation. Completed history must not truncate the
 * directory scan before a later pending record is considered. */
static int pending_candidates(char ids[][BUFFER_ID_SIZE], size_t capacity,
                              size_t *count, bool allow_buffer,
                              bool allow_auto)
{
  DIR *dir = opendir(CONFIG_BUFFER_VELA_DATA_DIR "/records");
  if (!dir) return -errno;
  struct dirent *entry;
  int ret = 0;
  *count = 0;
  for (;;)
    {
      errno=0;entry=readdir(dir);if(!entry){if(errno)ret=-errno;break;}
      size_t length = strlen(entry->d_name);
      if (length < 6 || length - 5 >= BUFFER_ID_SIZE ||
          strcmp(entry->d_name + length - 5, ".json")) continue;
      char id[BUFFER_ID_SIZE];
      memcpy(id, entry->d_name, length - 5);
      id[length - 5] = 0;
      if (!buffer_agent_valid_id(id)) continue;
      char path[320]; buffer_agent_record_path(path, sizeof(path), id);
      errno=0;
      cJSON *record = buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
      if(!record){ret=errno?-errno:-EIO;break;}
      cJSON *state = cJSON_GetObjectItemCaseSensitive(record, "state");
      /* Completed assistant text records have no queue item left to ACK and
       * must not monopolize the worker. A completed audio record whose ACK
       * failed is still discovered through the durable queued-WAV scan below. */
      bool pending = cJSON_IsString(state) &&
          !strcmp(state->valuestring, "pending_classification");
      cJSON *target = cJSON_GetObjectItemCaseSensitive(record, "target");
      const char *target_name=cJSON_IsString(target)?target->valuestring:"buffer";
      bool assistant = !strcmp(target_name,"assistant")||buffer_agent_input_assistant(id);
      bool automatic = !strcmp(target_name,"auto");
      if (!assistant&&((automatic&&!allow_auto)||(!automatic&&!allow_buffer))) pending=false;
      cJSON_Delete(record);
      if (pending)
        {
          ret = candidate_add(ids, capacity, count, id);
          if (ret < 0) break;
        }
    }
  closedir(dir);
  if (ret < 0) return ret;

  char (*queued)[BUFFER_ID_SIZE] =
    calloc(CONFIG_BUFFER_VELA_QUEUE_LIMIT, sizeof(*queued));
  if (!queued) return *count ? 0 : -ENOMEM;
  int queued_count = buffer_store_list_pending(queued,
                                                CONFIG_BUFFER_VELA_QUEUE_LIMIT);
  if (queued_count < 0)
    {
      free(queued);
      return *count ? 0 : queued_count;
    }
  for (int i = 0; i < queued_count; i++)
    {
      if (!buffer_agent_valid_id(queued[i])) continue;
      if (!buffer_agent_input_assistant(queued[i]))
        {
          char target[BUFFER_CAPTURE_TARGET_NAME_SIZE];
          if (buffer_store_capture_target(queued[i],target,sizeof(target))<0) continue;
          if (!strcmp(target,"auto")?!allow_auto:!allow_buffer) continue;
        }
      ret = candidate_add(ids, capacity, count, queued[i]);
      if (ret < 0) break;
    }
  free(queued);
  if (ret < 0) return ret;
  qsort(ids, *count, sizeof(*ids), candidate_compare);
  return 0;
}

static struct capture_retry_s *retry_find(const char *id)
{
  for (size_t i = 0; i < BUFFER_AGENT_CANDIDATE_LIMIT; i++)
    if (retry_state[i].used && !strcmp(retry_state[i].id, id))
      return &retry_state[i];
  return NULL;
}

static void retry_prune(char ids[][BUFFER_ID_SIZE], size_t count)
{
  for (size_t i = 0; i < BUFFER_AGENT_CANDIDATE_LIMIT; i++)
    if (retry_state[i].used && !candidate_contains(ids, count, retry_state[i].id))
      memset(&retry_state[i], 0, sizeof(retry_state[i]));
}

static bool retry_deferred(const char *id, uint64_t now_ms)
{
  struct capture_retry_s *state = retry_find(id);
  return state && now_ms < state->retry_at_ms;
}

static void retry_failed(const char *id, uint64_t now_ms)
{
  struct capture_retry_s *state = retry_find(id);
  if (!state)
    for (size_t i = 0; i < BUFFER_AGENT_CANDIDATE_LIMIT; i++)
      if (!retry_state[i].used)
        {
          state = &retry_state[i];
          memset(state, 0, sizeof(*state));
          state->used = true;
          snprintf(state->id, sizeof(state->id), "%s", id);
          break;
        }
  if (!state)
    {
      state = &retry_state[0];
      for (size_t i = 1; i < BUFFER_AGENT_CANDIDATE_LIMIT; i++)
        if (retry_state[i].retry_at_ms < state->retry_at_ms)
          state = &retry_state[i];
      memset(state, 0, sizeof(*state));
      state->used = true;
      snprintf(state->id, sizeof(state->id), "%s", id);
    }
  if (state->failures < UINT_MAX) state->failures++;
  uint64_t delay_ms = BUFFER_AGENT_RETRY_BASE_MS;
  for (unsigned i = 1; i < state->failures && delay_ms < BUFFER_AGENT_RETRY_MAX_MS; i++)
    delay_ms *= 2;
  if (delay_ms > BUFFER_AGENT_RETRY_MAX_MS) delay_ms = BUFFER_AGENT_RETRY_MAX_MS;
  state->retry_at_ms = now_ms + delay_ms;
  snprintf(last_failed_id, sizeof(last_failed_id), "%s", id);
}

static void retry_succeeded(const char *id)
{
  struct capture_retry_s *state = retry_find(id);
  if (state) memset(state, 0, sizeof(*state));
  if (!strcmp(last_failed_id, id)) last_failed_id[0] = 0;
}

static unsigned retry_wait_ms(char ids[][BUFFER_ID_SIZE], size_t count,
                              uint64_t now_ms)
{
  uint64_t earliest = UINT64_MAX;
  for (size_t i = 0; i < count; i++)
    {
      struct capture_retry_s *state = retry_find(ids[i]);
      if (state && state->retry_at_ms < earliest) earliest = state->retry_at_ms;
    }
  if (earliest == UINT64_MAX) return 5000;
  if (earliest <= now_ms) return 1000;
  uint64_t wait = earliest - now_ms;
  return wait > UINT_MAX ? UINT_MAX : (unsigned)wait;
}

static int choose_candidate(char ids[][BUFFER_ID_SIZE], size_t count,
                            bool automatic, bool force_retry, uint64_t now_ms)
{
  if (!count) return -1;
  pthread_mutex_lock(&worker_lock);
  for(unsigned p=0;p<interaction_priority_count;p++)for(size_t i=0;i<count;i++)if(!strcmp(interaction_priority[p],ids[i])){
   memmove(interaction_priority[p],interaction_priority[p+1],(interaction_priority_count-p-1)*sizeof(interaction_priority[0]));interaction_priority_count--;
   pthread_mutex_unlock(&worker_lock);return (int)i;
  }
  pthread_mutex_unlock(&worker_lock);
  if (!automatic || force_retry)
    {
      for (size_t i = 0; last_failed_id[0] && i < count; i++)
        if (!strcmp(ids[i], last_failed_id)) return (int)i;
      return 0;
    }

  size_t start = 0;
  while (start < count && strcmp(ids[start], last_automatic_id) <= 0) start++;
  for (size_t offset = 0; offset < count; offset++)
    {
      size_t index = (start + offset) % count;
      if (!retry_deferred(ids[index], now_ms)) return (int)index;
    }
  return -1;
}

static int agent_process_once(bool automatic, bool force_retry,
                              bool *capture_attempted, unsigned *wait_ms)
{
  if (capture_attempted) *capture_attempted = false;
  bool capture_failed = false;
  bool memory_retained = false;
  const char *phase="storage";
  if (wait_ms) *wait_ms = 5000;
  int ret = buffer_agent_store_init();
  if (ret < 0) { if (wait_ms) *wait_ms = 60000; buffer_agent_report_error(ret); buffer_a2ui_actions_unavailable(ret); return ret; }
  struct buffer_agent_config_s *config = calloc(1, sizeof(*config));
  if (!config) { if (wait_ms) *wait_ms = 60000; return -ENOMEM; }
  phase="configuration";
  ret = buffer_agent_config_load(config);
  if (ret == -ENOENT) ret = 0;
  else if (!ret && config->request.operation == BUFFER_OOBE_CONFIGURE_AGENT)
    {
      /* The device owns reconnect, including after a phone-free reboot.
       * Never make UI or the BLE executor wait for association/DHCP. */
      phase="network";
      if (!buffer_wifi_is_ready())
        {
          ret = buffer_wifi_reconnect();
          if (ret < 0) goto report;
          if (!buffer_wifi_is_ready()) { ret = -ENETUNREACH; goto report; }
        }
      if (!config->validated)
        {
          if (!force_retry && (config->last_error == -EPERM || config->last_error == -EACCES))
            { ret = config->last_error; goto report; }
          buffer_agent_set_validating(true);
          buffer_set_status("设备正在验证 LLM 与 ASR 服务…");
          phase="validation";
          ret = buffer_agent_validate(config);
          int saved = buffer_agent_config_result(config, ret == 0, ret);
          buffer_agent_set_validating(false);
          if (saved < 0) ret = saved;
          if (!ret) buffer_set_status("设备 Agent 已验证，手机离线时可独立整理");
        }
      else if (!buffer_oobe_setup_complete())
        {
          /* Keep service verification available during onboarding, but hold
           * queued user work until the explicit READY-page confirmation. */
          ret = 0;
          if (wait_ms) *wait_ms = 60000;
        }
      else
        {
          /* The elected owner, not mere phone connectivity, owns automatic
           * classification. A phone coordinator routes assistant intent here. */
          bool allow_buffer = buffer_coordinator_local();
          pthread_mutex_lock(&g_buffer.lock);
          bool phone_online=g_buffer.phone_synced;
          pthread_mutex_unlock(&g_buffer.lock);
          allow_buffer=allow_buffer&&!phone_online;
          /* Microphone intent belongs to this device, regardless of the
           * elected owner for shared Buffer classification. */
          bool allow_auto=true;
          if (pthread_mutex_trylock(&g_buffer_capture_lock) == 0)
            {
              pthread_mutex_lock(&worker_lock);
              struct buffer_memory_capture_s job=memory_capture;
              char foreground[BUFFER_ID_SIZE];
              snprintf(foreground,sizeof(foreground),"%s",foreground_retry_id);
              pthread_mutex_unlock(&worker_lock);
              if(job.pcm){
                bool consumed=false;
                if(capture_attempted)*capture_attempted=true;
                phase="memory-capture";
                ret=buffer_agent_process_audio(config,&job,&consumed);
                if(consumed&&ret==-EAGAIN){
                  pthread_mutex_lock(&worker_lock);
                  snprintf(foreground_retry_id,sizeof(foreground_retry_id),"%s",job.id);
                  pthread_mutex_unlock(&worker_lock);
                }
                if(ret<0)buffer_voice_update(job.id,BUFFER_VOICE_RETRY,NULL,ret);
                memory_retained=!consumed;capture_failed=ret<0;
                if(!ret&&consumed)buffer_agent_report_error(0);
                if(consumed)release_memory_capture();
                pthread_mutex_unlock(&g_buffer_capture_lock);
                if(wait_ms)*wait_ms=consumed?1:5000;
                goto report;
              }
              if(foreground[0]){
                if(capture_attempted)*capture_attempted=true;
                phase="foreground-tool";
                ret=buffer_agent_process_capture(config,foreground);
                capture_failed=ret<0;
                if(ret!=-EAGAIN){
                  pthread_mutex_lock(&worker_lock);
                  if(!strcmp(foreground_retry_id,foreground))foreground_retry_id[0]=0;
                  pthread_mutex_unlock(&worker_lock);
                }
                pthread_mutex_unlock(&g_buffer_capture_lock);
                if(wait_ms)*wait_ms=ret==-EAGAIN?250:1000;
                goto report;
              }
              phase="card-projection";
              ret = buffer_agent_refresh_cards();
              /* A busy/stale deck is retried by the background projection
               * pump. It is not an assistant readiness failure and must not
               * prevent processing an independent capture. */
              if (ret == -EAGAIN) ret = 0;
              char (*ids)[BUFFER_ID_SIZE] =
                calloc(BUFFER_AGENT_CANDIDATE_LIMIT, sizeof(*ids));
              size_t count = 0;
              if (!ret && !ids) ret = -ENOMEM;
              if (!ret) ret = pending_candidates(ids, BUFFER_AGENT_CANDIDATE_LIMIT,
                                                 &count, allow_buffer,allow_auto);
              if (!ret)
                {
                  retry_prune(ids, count);
                  uint64_t now_ms = agent_monotonic_ms();
                  unsigned attempt_limit = automatic && !force_retry ?
                    BUFFER_AGENT_AUTO_ATTEMPT_LIMIT : 1;
                  unsigned attempts = 0;
                  int last_error = 0;
                  while (attempts < attempt_limit)
                    {
                      int selected = choose_candidate(ids, count, automatic,
                                                      force_retry, now_ms);
                      if (selected < 0)
                        {
                          if (!attempts && wait_ms)
                            *wait_ms = retry_wait_ms(ids, count, now_ms);
                          break;
                        }
                      const char *id = ids[selected];
                      if (automatic)
                        snprintf(last_automatic_id, sizeof(last_automatic_id), "%s", id);
                      attempts++;
                      if (capture_attempted) *capture_attempted = true;
                      phase="queued-capture";
                      ret = buffer_agent_process_capture(config, id);
                      if (ret < 0)
                        {
                          capture_failed = true;
                          last_error = ret;
                          retry_failed(id, now_ms);
                          buffer_agent_report_error(ret);
                          if (automatic && !force_retry && attempts < attempt_limit)
                            continue;
                          break;
                        }
                      buffer_agent_report_error(0);
                      retry_succeeded(id);
                      last_error = 0;
                      break;
                    }
                  if (last_error) ret = last_error;
                  else if (!attempts) ret = 0;
                  if (automatic && attempts && wait_ms) *wait_ms = 5000;
                }
              free(ids);
              pthread_mutex_unlock(&g_buffer_capture_lock);
              int pending=buffer_store_pending_count();
              pthread_mutex_lock(&g_buffer.lock);
              g_buffer.pending_count = pending;
              pthread_mutex_unlock(&g_buffer.lock);
            }
        }
    }
report:
  if (ret < 0)
    {
      if(!strcmp(phase,"network")||!strcmp(phase,"configuration")||!strcmp(phase,"validation"))buffer_a2ui_actions_unavailable(ret);
      if (config->validated && (ret == -EPERM || ret == -EACCES))
        (void)buffer_agent_config_result(config, false, ret);
      /* Capture failures were reported at the point of each attempted ID. */
      if (!capture_failed) buffer_agent_report_error(ret);
      syslog(LOG_WARNING,"Buffer Agent: phase=%s result=%d\n",phase,ret);
      if(memory_retained)buffer_set_status("语音仍在内存中，等待重试，请勿断电");
      else if(ret==-EAGAIN&&time(NULL)<1704067200)buffer_set_status("正在校准时间，随后连接识别服务");
      else if(ret==-EAGAIN)buffer_set_status("处理条件暂未就绪，正在自动重试");
      else if(ret==-ENETUNREACH)buffer_set_status("网络尚未就绪，正在重新连接");
      else if(ret==-ETIMEDOUT)buffer_set_status("识别服务响应超时，将自动重试");
      else if(capture_failed)buffer_set_status("有内容尚未处理完成（%d），将自动重试",ret);
      else buffer_set_status("处理暂未完成（%d），将自动重试",ret);
      if (wait_ms && !capture_failed) *wait_ms = 60000;
    }
  buffer_agent_config_wipe(config);
  free(config);
  return ret;
}

int buffer_agent_process_once(void)
{
  pthread_mutex_lock(&worker_lock);
  bool explicit_retry = manual_validation_requested;
  manual_validation_requested = false;
  pthread_mutex_unlock(&worker_lock);
  bool setup_complete = false;
  (void)buffer_store_load_oobe_completed(&setup_complete);
  /* This public pump is also the manual retry path: it intentionally bypasses
   * per-capture backoff so the HTTPS fixture and user retry act immediately. */
  return agent_process_once(false, explicit_retry, NULL, NULL);
}

static void *worker_main(void *unused)
{
  (void)unused;
  uint64_t next_process_ms = 0;
  bool previous_pair_ready = false;
  char previous_local_id[65] = {0};
  char previous_peer_id[65] = {0};
  while (true)
    {
      pthread_mutex_lock(&worker_lock);
      bool stopping = worker_stopping;
      bool explicit_retry = manual_validation_requested;
      bool wake_requested = retry_requested;
      bool foreground_waiting = foreground_retry_id[0]!=0;
      manual_validation_requested = false;
      retry_requested = false;
      pthread_mutex_unlock(&worker_lock);
      if (stopping) break;

      /* Refresh on every short background poll. A matching identity hits the
       * RAM cache; a newly published pair causes exactly one disk reload. */
      bool setup_complete = false;
      (void)buffer_store_load_oobe_completed(&setup_complete);
      /* Deck dates and local resurfacing do not depend on Wi-Fi or a model
       * request succeeding. The projection itself avoids I/O until dirty/due. */
      if (!foreground_waiting && !buffer_voice_foreground_busy() &&
          pthread_mutex_trylock(&g_buffer_capture_lock) == 0)
        {
          /* Finish accepted facts even when onboarding, Bluetooth or model
           * validation is unavailable. This never invokes a model task. */
          int recovered=buffer_replica_recover_results_step();
          int projection_result = recovered<0?recovered:setup_complete?buffer_agent_refresh_cards():0;
          pthread_mutex_unlock(&g_buffer_capture_lock);
          if (projection_result < 0 && projection_result != -EAGAIN)
            buffer_set_status("卡片更新失败：%d，保留已保存内容", projection_result);
        }
      char local_id[65] = {0};
      char peer_id[65] = {0};
      bool pair_ready = buffer_fabric_identity_pair(local_id, peer_id) == 0;
      bool pair_changed = pair_ready &&
          (!previous_pair_ready || strcmp(local_id, previous_local_id) ||
           strcmp(peer_id, previous_peer_id));
      if (pair_ready)
        {
          memcpy(previous_local_id, local_id, sizeof(previous_local_id));
          memcpy(previous_peer_id, peer_id, sizeof(previous_peer_id));
        }
      else
        {
          memset(previous_local_id, 0, sizeof(previous_local_id));
          memset(previous_peer_id, 0, sizeof(previous_peer_id));
        }
      memset(local_id, 0, sizeof(local_id));
      memset(peer_id, 0, sizeof(peer_id));

      uint64_t now_ms = agent_monotonic_ms();
      previous_pair_ready = pair_ready;
      bool process_due = now_ms >= next_process_ms || explicit_retry ||
                         wake_requested || pair_changed;
      if (process_due)
        {
          unsigned process_delay_ms = 60000;
          (void)agent_process_once(true, explicit_retry, NULL,
                                   &process_delay_ms);
          now_ms = agent_monotonic_ms();
          next_process_ms = now_ms + process_delay_ms;
        }

      /* Poll identity once per second even when capture work backs off. This
       * recovers early startup and re-pair changes without holding up config
       * validation or repeatedly scanning captures. */
      uint64_t until_process_ms = next_process_ms > now_ms ?
                                  next_process_ms - now_ms : 0;
      unsigned delay_ms = until_process_ms < 1000 ?
                          (unsigned)until_process_ms : 1000;
      if (delay_ms == 0) delay_ms = 1;
      struct timespec until;
      clock_gettime(CLOCK_REALTIME, &until);
      until.tv_sec += delay_ms / 1000;
      until.tv_nsec += (long)(delay_ms % 1000) * 1000000L;
      if (until.tv_nsec >= 1000000000L)
        { until.tv_sec++; until.tv_nsec -= 1000000000L; }
      pthread_mutex_lock(&worker_lock);
      while (!worker_stopping && !retry_requested)
        if (pthread_cond_timedwait(&worker_wake, &worker_lock, &until) == ETIMEDOUT) break;
      pthread_mutex_unlock(&worker_lock);
    }
  return NULL;
}

int buffer_agent_start(void)
{
  pthread_mutex_lock(&worker_lock);
  if (worker_started) { pthread_mutex_unlock(&worker_lock); return 0; }
  int action_ready=buffer_a2ui_set_action_handler(buffer_agent_assistant_ui_action,NULL);
  if(action_ready){pthread_mutex_unlock(&worker_lock);return action_ready;}
  worker_stopping = false; retry_requested = true;
  pthread_attr_t attr;
  int ret = pthread_attr_init(&attr);
  if (!ret)
    {
      ret = pthread_attr_setstacksize(&attr, 96 * 1024);
      if (!ret) ret = pthread_create(&worker_thread, &attr, worker_main, NULL);
      pthread_attr_destroy(&attr);
    }
  worker_started = ret == 0;
  pthread_mutex_unlock(&worker_lock);
  if(ret)(void)buffer_a2ui_set_action_handler(NULL,NULL);
  return ret ? -ret : 0;
}

void buffer_agent_stop(void)
{
  pthread_mutex_lock(&worker_lock);
  bool started = worker_started;
  worker_stopping = true; pthread_cond_signal(&worker_wake);
  pthread_mutex_unlock(&worker_lock);
  if (started) pthread_join(worker_thread, NULL);
  pthread_mutex_lock(&worker_lock);
  struct buffer_memory_capture_s pending=memory_capture;
  pthread_mutex_unlock(&worker_lock);
  if(pending.pcm){
    if(!buffer_audio_store_pcm(&pending))release_memory_capture();
    else buffer_set_status("语音仍在内存中，保存失败，请勿断电");
  }
  pthread_mutex_lock(&worker_lock); worker_started = false; pthread_mutex_unlock(&worker_lock);
  (void)buffer_a2ui_set_action_handler(NULL,NULL);
  buffer_images_shutdown();
}

static int configure_network(const struct buffer_oobe_request_s *r)
{
  char path[] = CONFIG_BUFFER_VELA_DATA_DIR "/oobe-network.json";
  unsigned char bytes[98], hash[32]; char fingerprint[65];
  size_t ssid = strlen(r->ssid), password = strlen(r->wifi_password);
  memcpy(bytes, r->ssid, ssid); bytes[ssid] = 0;
  memcpy(bytes + ssid + 1, r->wifi_password, password);
  if (mbedtls_sha256(bytes, ssid + 1 + password, hash, 0)) return -EIO;
  volatile unsigned char *secret = bytes; for (size_t i = 0; i < sizeof(bytes); i++) secret[i] = 0;
  for (int i = 0; i < 32; i++) snprintf(fingerprint + i * 2, 3, "%02x", hash[i]);
  cJSON *receipt = buffer_agent_read_json(path, 2048);
  if (receipt)
    {
      cJSON *rev = cJSON_GetObjectItemCaseSensitive(receipt, "revision");
      cJSON *id = cJSON_GetObjectItemCaseSensitive(receipt, "request_id");
      cJSON *digest = cJSON_GetObjectItemCaseSensitive(receipt, "fingerprint");
      if (!cJSON_IsNumber(rev) || !cJSON_IsString(id) || !cJSON_IsString(digest))
        { cJSON_Delete(receipt); return -EINVAL; }
      if (r->revision < (uint64_t)rev->valuedouble) { cJSON_Delete(receipt); return -ESTALE; }
      if (r->revision == (uint64_t)rev->valuedouble &&
          (strcmp(r->request_id, id->valuestring) || strcmp(fingerprint, digest->valuestring)))
        { cJSON_Delete(receipt); return -EEXIST; }
      if (r->revision == (uint64_t)rev->valuedouble && buffer_wifi_is_ready())
        { cJSON_Delete(receipt); return 0; }
      cJSON_Delete(receipt);
    }
  else if (errno != ENOENT) return -errno;
  receipt = cJSON_CreateObject();
  cJSON_AddNumberToObject(receipt, "revision", (double)r->revision);
  cJSON_AddStringToObject(receipt, "request_id", r->request_id);
  cJSON_AddStringToObject(receipt, "fingerprint", fingerprint);
  cJSON_AddStringToObject(receipt, "state", "network_pending");
  int ret = buffer_agent_atomic_json(path, receipt);
  if (!ret) ret = buffer_wifi_configure(r->ssid, r->wifi_password);
  cJSON_ReplaceItemInObjectCaseSensitive(receipt, "state",
      cJSON_CreateString(!ret && buffer_wifi_is_ready() ? "network_ready" : "network_pending"));
  cJSON_AddNumberToObject(receipt, "last_error", ret);
  int saved = buffer_agent_atomic_json(path, receipt); cJSON_Delete(receipt);
  return saved < 0 ? saved : ret;
}

int buffer_oobe_handle_message(const struct buffer_oobe_authorization_s *auth,
                               const unsigned char *data, size_t length,
                               char **reply)
{
  if (!reply) return -EINVAL;
  *reply = NULL;
  if (!auth || !auth->authorize) return -EACCES;
  struct buffer_oobe_request_s *request = calloc(1, sizeof(*request));
  if (!request) return -ENOMEM;
  int ret = buffer_oobe_decode(data, length, request);
  if (ret < 0) goto done;
  ret = auth->authorize(auth->context, request->operation);
  if (ret) { ret = -EACCES; goto done; }
  pthread_mutex_lock(&request_lock);
  ret = buffer_agent_store_init();
  if (!ret && auth->authorize(auth->context, request->operation)) ret = -EACCES;
  if (!ret)
    {
      if (request->operation == BUFFER_OOBE_CONFIGURE_NETWORK) ret = configure_network(request);
      else if (request->operation == BUFFER_OOBE_CONFIGURE_AGENT || request->operation == BUFFER_OOBE_SKIP_AGENT)
        { ret = buffer_agent_config_apply(request); if (!ret) buffer_agent_retry(); }
    }
  if (ret < 0) buffer_agent_report_error(ret);
  struct buffer_agent_status_s state;
  buffer_agent_snapshot(&state);
  bool wifi_ready = buffer_wifi_is_ready();
  const char *phase = ret < 0 ? "failed" : state.skipped ? "skipped" :
    !wifi_ready ? "network_pending" : state.ready ? "ready" :
    state.validating || (state.configured && time(NULL) < 1704067200) ? "validating" :
    state.error ? "failed" : state.configured ? "agent_pending" : "network_ready";
  cJSON *json = cJSON_CreateObject(), *capabilities = cJSON_CreateObject();
  cJSON_AddStringToObject(json, "type", "vela.oobe.reply");
  cJSON_AddNumberToObject(json, "protocol", 1);
  cJSON_AddStringToObject(json, "request_id", request->request_id);
  cJSON_AddNumberToObject(json, "revision", (double)request->revision);
  cJSON_AddStringToObject(json, "state", phase);
  if (ret < 0 || state.error) cJSON_AddStringToObject(json, "error_code", buffer_agent_error_code(ret < 0 ? ret : state.error));
  cJSON_AddBoolToObject(capabilities, "verified_https", true);
  cJSON_AddBoolToObject(capabilities, "local_record_tools", true);
  cJSON_AddBoolToObject(capabilities, "autonomous_agent", true);
  cJSON_AddBoolToObject(capabilities, "setup_complete",
                        buffer_oobe_setup_complete());
  cJSON_AddItemToObject(json, "capabilities", capabilities);
  *reply = cJSON_PrintUnformatted(json); cJSON_Delete(json);
  pthread_mutex_unlock(&request_lock);
  ret = *reply ? 0 : -ENOMEM;
done:
  buffer_oobe_request_reset(request); free(request); return ret;
}
