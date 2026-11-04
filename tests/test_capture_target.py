"""Exercise automatic capture routing and persisted WAV metadata without hardware."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC
from pathlib import Path
import subprocess
import tempfile

root = PROJECT_ROOT
app = root / "app/buffer_app"
main_source = (app / "buffer_app_main.c").read_text()
store_source = (app / "buffer_store.c").read_text()
ui_source = (app / "buffer_ui.c").read_text()

assert "g_capture_target_group" not in ui_source
assert "buffer_capture_target_event" not in ui_source


def function(source, signature):
    return extract_c(source, signature)

with tempfile.TemporaryDirectory(prefix="buffer-capture-target-") as temporary:
    work = Path(temporary)
    include = work / "include"
    (include / "nuttx").mkdir(parents=True)
    (include / "nuttx/config.h").write_text("")
    (include / "netutils").mkdir()
    (include / "netutils/cJSON.h").symlink_to(SDK_ROOT / "apps/netutils/cjson/cJSON/cJSON.h")
    (include / "alsa").mkdir()
    (include / "alsa/asoundlib.h").write_text(r"""
#ifndef TEST_ASOUNDLIB_H
#define TEST_ASOUNDLIB_H
typedef struct snd_pcm snd_pcm_t;
typedef unsigned long snd_pcm_uframes_t;
typedef long snd_pcm_sframes_t;
#define SND_PCM_STREAM_CAPTURE 0
#define SND_PCM_FORMAT_S16_LE 0
#define SND_PCM_ACCESS_RW_INTERLEAVED 0
int snd_pcm_open(snd_pcm_t **, const char *, int, int);
int snd_pcm_set_params(snd_pcm_t *, int, int, unsigned int, unsigned int,
                       int, unsigned int);
int snd_pcm_get_params(snd_pcm_t *, snd_pcm_uframes_t *, snd_pcm_uframes_t *);
snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *, void *, snd_pcm_uframes_t);
int snd_pcm_recover(snd_pcm_t *, int, int);
int snd_pcm_drop(snd_pcm_t *);
int snd_pcm_close(snd_pcm_t *);
const char *snd_strerror(int);
#endif
""")
    data = work / "data"
    queue = data / "queue"
    queue.mkdir(parents=True)

    store_helpers = "\n".join([
        function(store_source, "static int buffer_path("),
        function(store_source, "static bool buffer_store_valid_device_id("),
        function(store_source, "int buffer_store_capture_target("),
        function(store_source, "int buffer_store_capture_is_assistant("),
    ])
    audio_harness = r'''
#include "buffer_app.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
#include "buffer_agent.h"
#include <alsa/asoundlib.h>
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#define BUFFER_QUEUE_DIR CONFIG_BUFFER_VELA_DATA_DIR "/queue"

''' + store_helpers + r'''
struct snd_pcm { int unused; };
static struct snd_pcm g_pcm;
static volatile bool *g_stop;
static const char *active_id;
static bool queue_full,run_to_limit,accept_memory;
static struct buffer_memory_capture_s memory_job;
static unsigned reads;

int snd_pcm_open(snd_pcm_t **pcm, const char *name, int stream, int mode)
{ (void)name; (void)stream; (void)mode; *pcm = &g_pcm; return 0; }
int snd_pcm_set_params(snd_pcm_t *pcm, int format, int access,
                       unsigned int channels, unsigned int rate,
                       int resample, unsigned int latency)
{ (void)pcm; (void)format; (void)access; (void)channels; (void)rate;
  (void)resample; (void)latency; return 0; }
int snd_pcm_get_params(snd_pcm_t *pcm, snd_pcm_uframes_t *buffer,
                       snd_pcm_uframes_t *period)
{ (void)pcm; *buffer = 640; *period = 320; return 0; }
snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *pcm, void *samples,
                                snd_pcm_uframes_t frames)
{ (void)pcm;
  char path[512];snprintf(path,sizeof(path),"%s/%s.wav.part",BUFFER_QUEUE_DIR,active_id);
  assert(access(path,F_OK)!=0); /* No NAND/file creation while sampling. */
  assert(++reads<=400);
  memset(samples, 0, frames * sizeof(short)); *g_stop = !run_to_limit;
  return (snd_pcm_sframes_t)frames; }
int snd_pcm_recover(snd_pcm_t *pcm, int error, int silent)
{ (void)pcm; (void)error; (void)silent; return 0; }
int snd_pcm_drop(snd_pcm_t *pcm) { (void)pcm; return 0; }
int snd_pcm_close(snd_pcm_t *pcm) { (void)pcm; return 0; }
const char *snd_strerror(int error) { (void)error; return "test"; }
void buffer_set_status(const char *format, ...) { (void)format; }
void buffer_agent_wake(void) {}
int buffer_agent_sync_parent(const char *path) { (void)path; return 0; }
int buffer_store_queue_limit_reached(void) { return queue_full; }
int buffer_agent_submit_audio(const struct buffer_memory_capture_s *job) { if(accept_memory){memory_job=*job;return 0;}return -EAGAIN; }
bool buffer_agent_valid_id(const char *id) { return id&&id[0]&&strchr(id,'/')==NULL; }
int buffer_source_events_ready(void) { return 0; }
int buffer_source_capture_ensure(const char *id) { (void)id; return 0; }
void buffer_live_asr_begin(void) {}
void buffer_live_asr_end(void) {}
void buffer_live_asr_feed(const void *pcm, size_t length) { (void)pcm; (void)length; }

static void cancel_before_commit(void)
{
  volatile bool stop = false;
  uint32_t duration = 0;
  int decision = 1;
  g_stop = &stop;active_id="cancelled";reads=0;
  assert(buffer_audio_record_cancelable("cancelled", "normal", BUFFER_CAPTURE_TARGET_AUTO,
                                         &stop, &duration, &decision) == -ECANCELED);
  char path[512];
  snprintf(path,sizeof(path),"%s/cancelled.meta",BUFFER_QUEUE_DIR); assert(access(path,F_OK) != 0);
  snprintf(path,sizeof(path),"%s/cancelled.wav",BUFFER_QUEUE_DIR); assert(access(path,F_OK) != 0);
  snprintf(path,sizeof(path),"%s/cancelled.wav.part",BUFFER_QUEUE_DIR); assert(access(path,F_OK) != 0);
}

static void record(const char *id, enum buffer_capture_target_e target)
{
  volatile bool stop = false;
  uint32_t duration = 0;
  char stored[BUFFER_CAPTURE_TARGET_NAME_SIZE];
  g_stop = &stop;active_id=id;reads=0;
  assert(buffer_audio_record(id, "normal", target, &stop, &duration) == 0);
  assert(duration == (run_to_limit?8000:20));
  char wav_path[512];snprintf(wav_path,sizeof(wav_path),"%s/%s.wav",BUFFER_QUEUE_DIR,id);
  FILE *wav=fopen(wav_path,"rb");assert(wav);
  unsigned char header[44];assert(fread(header,1,44,wav)==44);
  unsigned bytes=header[40]|header[41]<<8|header[42]<<16|header[43]<<24;
  assert(bytes==duration*32);
  assert(fseek(wav,0,SEEK_END)==0&&ftell(wav)==(long)bytes+44);fclose(wav);
  assert(buffer_store_capture_target(id, stored, sizeof(stored)) == 0);
  if (target == BUFFER_CAPTURE_TARGET_AUTO)
    assert(strcmp(stored, "auto") == 0);
  else if (target == BUFFER_CAPTURE_TARGET_ASSISTANT)
    assert(strcmp(stored, "assistant") == 0);
  else
    assert(strcmp(stored, "buffer") == 0);
  assert(buffer_store_capture_is_assistant(id) ==
         (target == BUFFER_CAPTURE_TARGET_ASSISTANT ? 1 : 0));
}

int main(void)
{
  FILE *file;
  cancel_before_commit();
  record("cap-auto", BUFFER_CAPTURE_TARGET_AUTO);
  record("cap-assistant", BUFFER_CAPTURE_TARGET_ASSISTANT);
  record("cap-buffer", BUFFER_CAPTURE_TARGET_BUFFER);
  run_to_limit=true;record("cap-limit", BUFFER_CAPTURE_TARGET_AUTO);run_to_limit=false;
  accept_memory=true;active_id="cap-memory";reads=0;
  volatile bool memory_stop=false;uint32_t memory_duration=0;g_stop=&memory_stop;
  assert(buffer_audio_record(active_id,"normal",BUFFER_CAPTURE_TARGET_AUTO,&memory_stop,&memory_duration)==0);
  assert(memory_job.pcm&&memory_job.bytes==640&&memory_job.duration_ms==20);
  char memory_path[512];snprintf(memory_path,sizeof(memory_path),"%s/cap-memory.wav",BUFFER_QUEUE_DIR);
  assert(access(memory_path,F_OK)!=0);free(memory_job.pcm);memory_job.pcm=NULL;accept_memory=false;
  queue_full=true;active_id="cap-full";reads=0;
  volatile bool full_stop=false;uint32_t full_duration=0;g_stop=&full_stop;
  assert(buffer_audio_record(active_id,"normal",BUFFER_CAPTURE_TARGET_AUTO,&full_stop,&full_duration)==-ENOSPC);
  char full_path[512];snprintf(full_path,sizeof(full_path),"%s/cap-full.wav",BUFFER_QUEUE_DIR);
  assert(access(full_path,F_OK)!=0);queue_full=false;
  assert(buffer_store_capture_is_assistant("../escape") == -EINVAL);
  assert(buffer_store_capture_is_assistant("cap-missing") == -ENOENT);
  file = fopen("''' + str(queue) + r'''/cap-legacy.meta", "w");
  assert(file != NULL);
  assert(fputs("state=queued\n", file) >= 0);
  assert(fclose(file) == 0);
  assert(buffer_store_capture_is_assistant("cap-legacy") == 0);
  char legacy_target[BUFFER_CAPTURE_TARGET_NAME_SIZE];
  assert(buffer_store_capture_target("cap-legacy", legacy_target,
                                     sizeof(legacy_target)) == 0);
  assert(strcmp(legacy_target, "buffer") == 0);
  file = fopen("''' + str(queue) + r'''/cap-bad.meta", "w");
  assert(file != NULL);
  assert(fputs("target=unknown\n", file) >= 0);
  assert(fclose(file) == 0);
  assert(buffer_store_capture_is_assistant("cap-bad") == -EINVAL);
  assert(buffer_store_capture_target("cap-bad", legacy_target,
                                     sizeof(legacy_target)) == -EINVAL);
  puts("PASS: new WAVs persist auto; store API preserves legacy target semantics");
  return 0;
}
'''
    audio_test = work / "audio_test.c"
    audio_test.write_text(audio_harness)
    audio_binary = work / "audio_test"
    subprocess.run([
        *CC, "-std=c11", "-D_DEFAULT_SOURCE", "-D_POSIX_C_SOURCE=200809L",
        "-Wall", "-Wextra", "-Werror", "-pthread", "-I" + str(include),
        "-I" + str(app),
        '-DCONFIG_BUFFER_VELA_DATA_DIR="' + str(data) + '"',
        str(app / "buffer_audio.c"), str(audio_test), "-o", str(audio_binary),
    ], check=True)
    subprocess.run([str(audio_binary)], cwd=work, check=True, timeout=5)

    routing_harness = r'''
#include "buffer_app.h"
#include "buffer_voice.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static enum buffer_capture_target_e observed_target;
int buffer_store_queue_limit_reached(void) { return 0; }
int buffer_store_pending_count(void) { return 0; }
bool buffer_oobe_setup_complete(void) { return true; }
void buffer_set_status(const char *format, ...) { (void)format; }
void buffer_agent_wake(void) {}
int buffer_followup_prepare(const char *capture, const char *card) { (void)capture; (void)card; return 0; }
int buffer_followup_cancel(const char *capture) { (void)capture; return 0; }
int buffer_audio_record_cancelable(const char *id, const char *mode,
                        enum buffer_capture_target_e target,
                        volatile bool *stop, uint32_t *duration, int *decision)
{ (void)decision; (void)id; (void)mode; (void)stop; *duration = 20;
  observed_target = target; return 0; }

''' + "\n".join([
        function(main_source, "static void *buffer_record_thread_main(void *arg)"),
        function(main_source, "static int64_t buffer_monotonic_ms(void)"),
        function(main_source, "static int buffer_start_recording(void)"),
    ]) + r'''
static void start_and_check(void)
{
  observed_target = BUFFER_CAPTURE_TARGET_ASSISTANT;
  assert(buffer_start_recording() == 0);
  assert(pthread_join(g_buffer.record_thread, NULL) == 0);
  assert(observed_target == BUFFER_CAPTURE_TARGET_AUTO);
  pthread_mutex_lock(&g_buffer.lock);
  g_buffer.record_thread_started = false;
  pthread_mutex_unlock(&g_buffer.lock);
}

int main(void)
{
  g_buffer.initialized = true;
  strcpy(g_buffer.mode, "normal");
  start_and_check();
  start_and_check();
  puts("PASS: all new user captures enter the automatic target route");
  return 0;
}
'''
    routing_test = work / "routing_test.c"
    routing_test.write_text(routing_harness)
    routing_binary = work / "routing_test"
    subprocess.run([
        *CC, "-std=c11", "-D_DEFAULT_SOURCE", "-D_POSIX_C_SOURCE=200809L",
        "-Wall", "-Wextra", "-Werror", "-pthread", "-I" + str(include),
        "-I" + str(app), str(routing_test), "-o", str(routing_binary),
    ], check=True)
    subprocess.run([str(routing_binary)], cwd=work, check=True, timeout=5)
