#include "buffer_voice.h"
/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela PCM capture
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_agent.h"
#include "buffer_live_asr.h"
#include "buffer_source_events.h"

#include <alsa/asoundlib.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <syslog.h>
#include <unistd.h>

#define BUFFER_QUEUE_DIR CONFIG_BUFFER_VELA_DATA_DIR "/queue"
#define BUFFER_SAMPLE_RATE 16000
#define BUFFER_CHANNELS 1
#define BUFFER_BITS_PER_SAMPLE 16
#define BUFFER_WAV_HEADER_SIZE 44

static int g_audio_phase;
enum buffer_audio_phase_e buffer_audio_phase(void)
{
  return __atomic_load_n(&g_audio_phase, __ATOMIC_ACQUIRE);
}


static void buffer_put_u16(unsigned char *data, uint16_t value)
{
  data[0] = (unsigned char)(value & 0xff);
  data[1] = (unsigned char)((value >> 8) & 0xff);
}

static void buffer_put_u32(unsigned char *data, uint32_t value)
{
  data[0] = (unsigned char)(value & 0xff);
  data[1] = (unsigned char)((value >> 8) & 0xff);
  data[2] = (unsigned char)((value >> 16) & 0xff);
  data[3] = (unsigned char)((value >> 24) & 0xff);
}

static int buffer_write_wav_header(FILE *file, uint32_t data_size)
{
  unsigned char header[BUFFER_WAV_HEADER_SIZE];
  uint32_t byte_rate = BUFFER_SAMPLE_RATE * BUFFER_CHANNELS *
                       BUFFER_BITS_PER_SAMPLE / 8;
  uint16_t block_align = BUFFER_CHANNELS * BUFFER_BITS_PER_SAMPLE / 8;

  memset(header, 0, sizeof(header));
  memcpy(header, "RIFF", 4);
  buffer_put_u32(header + 4, 36 + data_size);
  memcpy(header + 8, "WAVE", 4);
  memcpy(header + 12, "fmt ", 4);
  buffer_put_u32(header + 16, 16);
  buffer_put_u16(header + 20, 1);
  buffer_put_u16(header + 22, BUFFER_CHANNELS);
  buffer_put_u32(header + 24, BUFFER_SAMPLE_RATE);
  buffer_put_u32(header + 28, byte_rate);
  buffer_put_u16(header + 32, block_align);
  buffer_put_u16(header + 34, BUFFER_BITS_PER_SAMPLE);
  memcpy(header + 36, "data", 4);
  buffer_put_u32(header + 40, data_size);
  return fwrite(header, 1, sizeof(header), file) == sizeof(header) ? 0 : -EIO;
}

static int64_t buffer_realtime_ms(void)
{
  struct timespec now;

  if (clock_gettime(CLOCK_REALTIME, &now) < 0)
    {
      return (int64_t)time(NULL) * 1000;
    }

  return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int64_t buffer_audio_monotonic_ms(void)
{
  struct timespec now;
  return clock_gettime(CLOCK_MONOTONIC,&now)?0:(int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}

int buffer_audio_store_pcm(const struct buffer_memory_capture_s *job)
{
  const char *capture_id=job->id,*mode=job->mode;
  const char *target_name=job->target==BUFFER_CAPTURE_TARGET_ASSISTANT?"assistant":
      job->target==BUFFER_CAPTURE_TARGET_AUTO?"auto":"buffer";
  const unsigned char *capture=job->pcm;
  uint32_t data_size=job->bytes,duration_ms=job->duration_ms;
  int64_t created_at=job->created_at;
  char temporary_path[256];
  char final_path[256];
  char temporary_metadata_path[256];
  char metadata_path[256];
  FILE *file = NULL;
  FILE *metadata = NULL;
  bool audio_committed=false;int ret;
  if(!capture||!data_size||data_size>CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES-44||
     !buffer_agent_valid_id(capture_id))return -EINVAL;
  ret = snprintf(temporary_path, sizeof(temporary_path), "%s/%s.wav.part",
                 BUFFER_QUEUE_DIR, capture_id);
  if (ret < 0 || (size_t)ret >= sizeof(temporary_path))
    {
      return -ENAMETOOLONG;
    }

  ret = snprintf(final_path, sizeof(final_path), "%s/%s.wav",
                 BUFFER_QUEUE_DIR, capture_id);
  if (ret < 0 || (size_t)ret >= sizeof(final_path))
    {
      return -ENAMETOOLONG;
    }

  ret = snprintf(metadata_path, sizeof(metadata_path), "%s/%s.meta",
                 BUFFER_QUEUE_DIR, capture_id);
  if (ret < 0 || (size_t)ret >= sizeof(metadata_path))
    {
      return -ENAMETOOLONG;
    }

  ret = snprintf(temporary_metadata_path, sizeof(temporary_metadata_path),
                 "%s/%s.meta.part", BUFFER_QUEUE_DIR, capture_id);
  if (ret < 0 || (size_t)ret >= sizeof(temporary_metadata_path))
    {
      return -ENAMETOOLONG;
    }

  if(buffer_store_queue_limit_reached()){ret=-ENOSPC;goto error;}
  file=fopen(temporary_path,"w");
  if(!file){ret=-errno;goto error;}
  ret=buffer_write_wav_header(file,data_size);
  if(ret<0||fwrite(capture,1,data_size,file)!=data_size||fflush(file)
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      ||fchmod(fileno(file),0600)
#endif
      ||fsync(fileno(file)))
    {ret=ret<0?ret:errno?-errno:-EIO;goto error;}
  if (fclose(file) != 0)
    {
      ret = errno > 0 ? -errno : -EIO;
      file = NULL;
      goto error;
    }
  file = NULL;

  if (rename(temporary_path, final_path) < 0)
    {
      ret = -errno;
      goto error;
    }
  audio_committed = true;

  metadata = fopen(temporary_metadata_path, "w");
  if (metadata == NULL)
    {
      ret = -errno;
      goto error;
    }


  if (fprintf(metadata, "capture_id=%s\n", capture_id) < 0 ||
      fprintf(metadata, "created_at=%lld\n",
              (long long)created_at) < 0 ||
      fprintf(metadata, "duration_ms=%u\n", (unsigned int)duration_ms) < 0 ||
      fprintf(metadata, "mode=%s\n", mode) < 0 ||
      fprintf(metadata, "target=%s\n", target_name) < 0 ||
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      fprintf(metadata, "source_events=1\n") < 0 ||
#endif
      fprintf(metadata, "state=queued\n") < 0 ||
      fprintf(metadata,
              "audio_format=audio/wav;codec=pcm_s16le;rate=16000;channels=1\n") < 0)
    {
      ret = errno > 0 ? -errno : -EIO;
      fclose(metadata);
      metadata = NULL;
      goto error;
    }

  if (fflush(metadata) != 0
#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
      || fchmod(fileno(metadata),0600) != 0
#endif
      || fsync(fileno(metadata)) != 0
     )
    {
      ret = errno > 0 ? -errno : -EIO;
      fclose(metadata);
      metadata = NULL;
      goto error;
    }
  if (fclose(metadata) != 0)
    {
      ret = errno > 0 ? -errno : -EIO;
      metadata = NULL;
      goto error;
    }
  metadata = NULL;

  if (rename(temporary_metadata_path, metadata_path) < 0)
    {
      ret = -errno;
      goto error;
    }

  /* Both queue files are now owned by recovery. A source-journal failure
   * must not delete the recorded audio or cancel its follow-up context. */
  ret=buffer_agent_sync_parent(metadata_path);
  /* The durable WAV+metadata are the recovery-owned precursor. read_audio
   * and ACK already ensure/archive the immutable source before consumption or
   * deletion, so their journal I/O must run on the background processing path. */
  if(ret<0) buffer_set_status("语音已保留，等待存储恢复：%d",ret);
  else buffer_set_status("已保存语音，等待同步（%u ms）", (unsigned int)duration_ms);
  return 0;
error:
  if(file)fclose(file);
  if(metadata)fclose(metadata);
  unlink(temporary_path);unlink(temporary_metadata_path);
  if(audio_committed)unlink(final_path);
  return ret;
}

static int buffer_audio_record_impl(const char *capture_id, const char *mode,
                        enum buffer_capture_target_e target,
                        volatile bool *stop, uint32_t *duration_ms, int *decision)
{
  snd_pcm_t *pcm = NULL;
  snd_pcm_uframes_t buffer_frames = 0;
  snd_pcm_uframes_t period_frames = 320;
  int16_t *samples = NULL;
  unsigned char *capture = NULL;
  uint64_t frame_limit=(uint64_t)CONFIG_BUFFER_VELA_MAX_RECORD_MS*BUFFER_SAMPLE_RATE/1000;
  uint64_t byte_limit=CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES>BUFFER_WAV_HEADER_SIZE?
      CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES-BUFFER_WAV_HEADER_SIZE:0;
  if(frame_limit>byte_limit/sizeof(int16_t))frame_limit=byte_limit/sizeof(int16_t);
  int64_t began=buffer_audio_monotonic_ms(),first_pcm=0,stopped=0,closed=0;
  uint32_t data_size = 0;
  uint64_t frames_total = 0;
  int ret;
  const char *stage = "初始化录音文件";

  if (capture_id == NULL || mode == NULL || stop == NULL ||
      duration_ms == NULL)
    {
      return -EINVAL;
    }

  switch (target)
    {
      case BUFFER_CAPTURE_TARGET_AUTO:
        break;
      case BUFFER_CAPTURE_TARGET_BUFFER:
        break;
      case BUFFER_CAPTURE_TARGET_ASSISTANT:
        break;
      default:
        return -EINVAL;
    }

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
  if(!buffer_agent_valid_id(capture_id))return -EINVAL;
#endif
  if(!frame_limit||frame_limit>SIZE_MAX/sizeof(int16_t))return -EFBIG;
  /* One bounded capture buffer keeps NAND and journal work off the microphone
   * start/read path. Audio is not reported saved until the durable queue commit. */
  capture=malloc((size_t)frame_limit*sizeof(int16_t));
  if(!capture)return -ENOMEM;

  stage = "打开麦克风";
  ret = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_CAPTURE, 0);
  if (ret < 0)
    {
      buffer_set_status("麦克风打开失败：%s", snd_strerror(ret));
      ret = -ENODEV;
      goto error;
    }

  stage = "配置麦克风";
  ret = snd_pcm_set_params(pcm, SND_PCM_FORMAT_S16_LE,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           BUFFER_CHANNELS, BUFFER_SAMPLE_RATE, 0, 100000);
  if (ret < 0)
    {
      buffer_set_status("麦克风参数失败：%s", snd_strerror(ret));
      ret = -EINVAL;
      goto error;
    }

  if (snd_pcm_get_params(pcm, &buffer_frames, &period_frames) < 0 ||
      period_frames == 0)
    {
      period_frames = 320;
    }

  /* The capture loop uses the period size only. */
  (void)buffer_frames;

  /* Keep the capture buffer bounded even if the driver reports a large period. */
  if (period_frames > 2048)
    {
      period_frames = 2048;
    }

  samples = malloc((size_t)period_frames * sizeof(int16_t));
  if (samples == NULL)
    {
      ret = -ENOMEM;
      goto error;
    }

  stage = "读取麦克风";
  buffer_live_asr_begin();
  while (!*stop && frames_total < frame_limit)
    {
      snd_pcm_sframes_t frames;
      size_t bytes;

      snd_pcm_uframes_t wanted=period_frames;
      if((uint64_t)wanted>frame_limit-frames_total)wanted=(snd_pcm_uframes_t)(frame_limit-frames_total);
      frames = snd_pcm_readi(pcm, samples, wanted);
      if (frames < 0)
        {
          frames = snd_pcm_recover(pcm, (int)frames, 0);
          if (frames < 0)
            {
              buffer_set_status("录音读取失败：%s", snd_strerror((int)frames));
              ret = -EIO;
              goto error;
            }

          continue;
        }

      if (frames == 0)
        {
          continue;
        }

      if((snd_pcm_uframes_t)frames>wanted){ret=-EIO;goto error;}
      if (frames_total == 0){
        first_pcm=buffer_audio_monotonic_ms();
        __atomic_store_n(&g_audio_phase, BUFFER_AUDIO_CAPTURING, __ATOMIC_RELEASE);
        syslog(LOG_INFO,"Buffer audio timing: first_pcm_ms=%lld\n",(long long)(first_pcm-began));
      }
      bytes = (size_t)frames * sizeof(int16_t);
      if ((uint64_t)data_size + bytes >
          CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES - BUFFER_WAV_HEADER_SIZE)
        {
          break;
        }

      memcpy(capture+data_size,samples,bytes);

      buffer_live_asr_feed(samples, bytes);
      data_size += (uint32_t)bytes;
      frames_total += (uint64_t)frames;
    }

  stopped=buffer_audio_monotonic_ms();
  __atomic_store_n(&g_audio_phase, BUFFER_AUDIO_SAVING, __ATOMIC_RELEASE);
  stage = "关闭麦克风";
  buffer_live_asr_end();
  snd_pcm_drop(pcm);
  snd_pcm_close(pcm);
  pcm = NULL;
  closed=buffer_audio_monotonic_ms();
  free(samples);
  samples = NULL;

  stage = "保存录音文件";
  /* The cancellation/commit decision is linearized before either queue file
   * becomes visible. No caller deletes an already published capture. */
  if (decision != NULL)
    {
      int expected = 0;
      if (!__atomic_compare_exchange_n(decision, &expected, 2, false,
                                       __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE))
        { ret = -ECANCELED; goto error; }
    }
  if (frames_total == 0) { ret = -ENODATA; goto error; }

  *duration_ms=(uint32_t)(frames_total*1000/BUFFER_SAMPLE_RATE);
  struct buffer_memory_capture_s job={.pcm=capture,.bytes=data_size,
      .duration_ms=*duration_ms,.created_at=buffer_realtime_ms(),.target=target};
  snprintf(job.id,sizeof(job.id),"%s",capture_id);
  snprintf(job.mode,sizeof(job.mode),"%s",mode);
  buffer_voice_update(job.id,BUFFER_VOICE_QUEUED,NULL,0);
  ret=buffer_agent_submit_audio(&job);
  if(ret==0){
    capture=NULL; /* Agent now owns the PCM; nothing was written to NAND. */
    buffer_set_status("正在识别并处理…");
  }else{
    ret=buffer_audio_store_pcm(&job);
    if(ret<0)goto error;
    free(capture);capture=NULL;
  }
  syslog(LOG_INFO,"Buffer audio timing: first_pcm_ms=%lld close_ms=%lld save_ms=%lld total_ms=%lld\n",
         (long long)(first_pcm-began),(long long)(closed-stopped),
         (long long)(buffer_audio_monotonic_ms()-closed),(long long)(buffer_audio_monotonic_ms()-began));
  syslog(LOG_INFO,"Buffer audio: captured frames=%llu bytes=%lu duration_ms=%lu\n",
         (unsigned long long)frames_total,(unsigned long)data_size,(unsigned long)*duration_ms);
  return 0;

error:
  __atomic_store_n(&g_audio_phase, BUFFER_AUDIO_SAVING, __ATOMIC_RELEASE);
  if (ret != -ECANCELED) buffer_set_status("%s失败：%d",stage,ret);
  syslog(LOG_ERR,"Buffer audio: stage=%s error=%d frames=%llu bytes=%lu\n",
         stage,ret,(unsigned long long)frames_total,(unsigned long)data_size);
  buffer_live_asr_end();
  if (pcm != NULL)
    {
      snd_pcm_drop(pcm);
      snd_pcm_close(pcm);
    }

  free(samples);
  free(capture);
  return ret;
}

int buffer_audio_record_cancelable(const char *capture_id, const char *mode,
                        enum buffer_capture_target_e target,
                        volatile bool *stop, uint32_t *duration_ms, int *decision)
{
  __atomic_store_n(&g_audio_phase, BUFFER_AUDIO_PREPARING, __ATOMIC_RELEASE);
  int ret = buffer_audio_record_impl(capture_id, mode, target, stop, duration_ms, decision);
  __atomic_store_n(&g_audio_phase, BUFFER_AUDIO_IDLE, __ATOMIC_RELEASE);
  return ret;
}

int buffer_audio_record(const char *capture_id, const char *mode,
                        enum buffer_capture_target_e target,
                        volatile bool *stop, uint32_t *duration_ms)
{
  return buffer_audio_record_cancelable(capture_id, mode, target, stop,
                                        duration_ms, NULL);
}
