"""Prepared capture buffers must be released when recording ends before readi."""
from pathlib import Path
import subprocess
from host_support import SDK_ROOT, CC, extract_c


def test_capture_close_before_start_frees_buffers_without_waiting(tmp_path: Path):
    source = (SDK_ROOT / 'apps/audioutils/alsa-lib/pcm_hw.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdlib.h>
#define FAR
#define SND_PCM_STATE_PAUSED 3
#define SND_PCM_STATE_RUNNING 2
#define AUDIO_STATE_PREPARED 1
#define AUDIO_STATE_DRAINING 4
#define AUDIO_STATE_OPEN 0
#define AUDIOIOC_STOP 1
#define AUDIOIOC_FREEBUFFER 2
struct audio_status_s {int state;unsigned long tail;};
struct audio_buf_desc_s {union {void *buffer;} u;};
typedef struct {int fd;struct audio_status_s *status;} snd_pcm_hw_t;
typedef struct {void *private_data;unsigned periods;unsigned long appl;} snd_pcm_t;
static unsigned freed,waits,stops;
static int test_ioctl(int fd,int cmd,void *arg){(void)fd;(void)arg;
 if(cmd==AUDIOIOC_FREEBUFFER)freed++;else if(cmd==AUDIOIOC_STOP)stops++;return 0;}
#define ioctl(fd,cmd,arg) test_ioctl(fd,cmd,(void *)(arg))
static int snd_pcm_wait(snd_pcm_t *pcm,int timeout){(void)pcm;(void)timeout;waits++;assert(!"unstarted capture must not wait for a worker");return 0;}
static void snd_pcm_hw_deinit(snd_pcm_t *pcm){(void)pcm;}
'''
    harness += extract_c(source, 'static int snd_pcm_hw_close(')
    harness += r'''
int main(void){
 for(unsigned attempt=0;attempt<3;attempt++){
  struct audio_status_s status={AUDIO_STATE_PREPARED,0};
  snd_pcm_hw_t *hw=calloc(1,sizeof(*hw));hw->status=&status;
  snd_pcm_t pcm={hw,4,4};
  assert(snd_pcm_hw_close(&pcm)==0);
  assert(freed==4*(attempt+1)&&waits==0&&stops==0);
 }
 return 0;
}
'''
    c = tmp_path / 'pcm.c'; c.write_text(harness)
    binary = tmp_path / 'pcm'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
                    str(c), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)
