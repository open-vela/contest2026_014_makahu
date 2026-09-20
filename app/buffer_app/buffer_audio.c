/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela PCM capture
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"

#include <alsa/asoundlib.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define BUFFER_QUEUE_DIR CONFIG_BUFFER_VELA_DATA_DIR "/queue"
#define BUFFER_SAMPLE_RATE 16000
#define BUFFER_CHANNELS 1
#define BUFFER_BITS_PER_SAMPLE 16
#define BUFFER_WAV_HEADER_SIZE 44

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

int buffer_audio_record(const char *capture_id, const char *mode,
                        volatile bool *stop, uint32_t *duration_ms)
{
  char temporary_path[256];
  char final_path[256];
  char temporary_metadata_path[256];
  char metadata_path[256];
  FILE *file = NULL;
  FILE *metadata = NULL;
  snd_pcm_t *pcm = NULL;
  snd_pcm_uframes_t buffer_frames = 0;
  snd_pcm_uframes_t period_frames = 320;
  int16_t *samples = NULL;
  uint32_t data_size = 0;
  uint64_t frames_total = 0;
  bool audio_committed = false;
  int ret;

  if (capture_id == NULL || mode == NULL || stop == NULL ||
      duration_ms == NULL)
    {
      return -EINVAL;
    }

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

  file = fopen(temporary_path, "w+");
  if (file == NULL)
    {
      return -errno;
    }

  ret = buffer_write_wav_header(file, 0);
  if (ret < 0 || fflush(file) != 0)
    {
      ret = ret < 0 ? ret : (errno > 0 ? -errno : -EIO);
      goto error;
    }

  ret = snd_pcm_open(&pcm, "default", SND_PCM_STREAM_CAPTURE, 0);
  if (ret < 0)
    {
      buffer_set_status("麦克风打开失败：%s", snd_strerror(ret));
      ret = -ENODEV;
      goto error;
    }

  ret = snd_pcm_set_params(pcm, SND_PCM_FORMAT_S16_LE,
                           SND_PCM_ACCESS_RW_INTERLEAVED,
                           BUFFER_CHANNELS, BUFFER_SAMPLE_RATE, 0, 500000);
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

  while (!*stop && frames_total <
         (uint64_t)CONFIG_BUFFER_VELA_MAX_RECORD_MS * BUFFER_SAMPLE_RATE / 1000)
    {
      snd_pcm_sframes_t frames;
      size_t bytes;

      frames = snd_pcm_readi(pcm, samples, period_frames);
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

      bytes = (size_t)frames * sizeof(int16_t);
      if ((uint64_t)data_size + bytes >
          CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES - BUFFER_WAV_HEADER_SIZE)
        {
          break;
        }

      if (fwrite(samples, 1, bytes, file) != bytes)
        {
          ret = -EIO;
          goto error;
        }

      data_size += (uint32_t)bytes;
      frames_total += (uint64_t)frames;
    }

  snd_pcm_drop(pcm);
  snd_pcm_close(pcm);
  pcm = NULL;
  free(samples);
  samples = NULL;

  if (fseek(file, 0, SEEK_SET) != 0)
    {
      ret = -EIO;
      goto error;
    }

  ret = buffer_write_wav_header(file, data_size);
  if (ret < 0 || fflush(file) != 0)
    {
      ret = ret < 0 ? ret : (errno > 0 ? -errno : -EIO);
      goto error;
    }
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

  *duration_ms = (uint32_t)(frames_total * 1000 / BUFFER_SAMPLE_RATE);
  if (fprintf(metadata, "capture_id=%s\n", capture_id) < 0 ||
      fprintf(metadata, "created_at=%lld\n",
              (long long)buffer_realtime_ms()) < 0 ||
      fprintf(metadata, "duration_ms=%u\n", (unsigned int)*duration_ms) < 0 ||
      fprintf(metadata, "mode=%s\n", mode) < 0 ||
      fprintf(metadata, "state=queued\n") < 0 ||
      fprintf(metadata,
              "audio_format=audio/wav;codec=pcm_s16le;rate=16000;channels=1\n") < 0)
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

  buffer_set_status("已保存语音，等待同步（%u ms）", (unsigned int)*duration_ms);
  return 0;

error:
  if (pcm != NULL)
    {
      snd_pcm_drop(pcm);
      snd_pcm_close(pcm);
    }

  free(samples);
  if (file != NULL)
    {
      fclose(file);
    }

  unlink(temporary_path);
  if (audio_committed)
    {
      /* The WAV is not a valid queue entry until its metadata has also been
       * committed.  Do not leave an unregistered blob after a storage error. */
      unlink(final_path);
    }
  if (metadata != NULL)
    {
      fclose(metadata);
    }
  unlink(temporary_metadata_path);
  return ret;
}
