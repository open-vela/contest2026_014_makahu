"""Execute S1 capture filling with an upper callback that requeues a buffer."""
from pathlib import Path
import subprocess
import pytest
from host_support import SDK_ROOT, CC, extract_c


def test_record_callback_can_reenter_enqueue_without_pending_lock(tmp_path: Path):
    source = (SDK_ROOT / 'vendor/allwinnertech/chips/r528/components/audio/sunxi_alsa.c').read_text()
    harness = r'''
#include <assert.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#define FAR
static __attribute__((unused)) int nxsem_trywait(int *s){if(*s>0){(*s)--;return 0;}return -1;}
typedef unsigned irqstate_t;
static irqstate_t spin_lock_irqsave(pthread_mutex_t *m){assert(pthread_mutex_trylock(m)==0);return 0;}
static void spin_unlock_irqrestore(pthread_mutex_t *m,irqstate_t flags){(void)flags;pthread_mutex_unlock(m);}
#define CONFIG_AW_AUDIO_DMIC_INFLIGHT 4
#define AUDIO_CALLBACK_DEQUEUE 1
#define AUDIO_CALLBACK_IOERR 2
#define OK 0
#define LOG_INFO 1
#define LOG_ERR 2
#define syslog(...) ((void)0)
#define audinfo(...) ((void)0)
struct ap_buffer_s {unsigned char *samp;int nbytes,curbyte,flags;};
struct queue {struct ap_buffer_s *items[4];unsigned count;};
struct sunxi_dev_s {bool running;pthread_mutex_t pendlock;void *handle;int pendsem;struct queue pendq;struct {void *priv;void (*upper)(void *,int,struct ap_buffer_s *,int);} dev;};
static unsigned callbacks;
static __attribute__((unused)) void *dq_peek(struct queue *q){return q->count?q->items[0]:NULL;}
static void *dq_remfirst(struct queue *q){if(!q->count)return NULL;void *p=q->items[0];for(unsigned i=1;i<q->count;i++)q->items[i-1]=q->items[i];q->count--;return p;}
static __attribute__((unused)) int nxmutex_lock(pthread_mutex_t *m){return pthread_mutex_lock(m);}
static __attribute__((unused)) int nxmutex_unlock(pthread_mutex_t *m){return pthread_mutex_unlock(m);}
static unsigned snd_vela_pcm_frames_to_bytes(void *p,int n){(void)p;return n*2;}
static long snd_vela_pcm_bytes_to_frames(void *p,int n){(void)p;return n/2;}
static long pcm_read(void *p,char *s,long n,unsigned b){(void)p;(void)s;assert(b==2);return n;}
static void upper(void *context,int reason,struct ap_buffer_s *apb,int status){
 struct sunxi_dev_s *priv=context;(void)apb;assert(reason==AUDIO_CALLBACK_DEQUEUE&&status==0);
 assert(pthread_mutex_trylock(&priv->pendlock)==0); /* Same lock used by enqueue. */
 pthread_mutex_unlock(&priv->pendlock);callbacks++;
}
'''
    harness += extract_c(source, 'static int record_read_failed(')
    harness += extract_c(source, 'static int record_fillonebuffer(')
    harness += extract_c(source, 'static int record_fillbuffer(')
    harness += r'''
int main(void){
 unsigned char samples[8];struct ap_buffer_s a={samples,8,0,0},b=a;
 struct sunxi_dev_s priv={.running=true,.pendlock=PTHREAD_MUTEX_INITIALIZER,.handle=&a,.pendsem=2,.pendq={{&a,&b},2}};
 priv.dev.priv=&priv;priv.dev.upper=upper;
 assert(record_fillbuffer(&priv)==0&&callbacks==2&&priv.pendq.count==0&&priv.pendsem==0);
 assert(record_fillbuffer(&priv)==0&&callbacks==2);
 priv.pendq.items[0]=&a;priv.pendq.count=1;
 assert(record_fillonebuffer(&priv)==0&&callbacks==3);
 pthread_mutex_destroy(&priv.pendlock);return 0;
}
'''
    file = tmp_path / 'capture.c'; file.write_text(harness)
    binary = tmp_path / 'capture'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread', '-fsanitize=address,undefined',
                    str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)


def test_full_wakeup_queue_does_not_reject_accepted_audio(tmp_path: Path):
    source = (SDK_ROOT / 'vendor/allwinnertech/chips/r528/components/audio/sunxi_alsa.c').read_text()
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <pthread.h>
#include <stddef.h>
#define FAR
static __attribute__((unused)) int nxsem_trywait(int *s){if(*s>0){(*s)--;return 0;}return -1;}
typedef unsigned irqstate_t;
static irqstate_t spin_lock_irqsave(pthread_mutex_t *m){assert(pthread_mutex_trylock(m)==0);return 0;}
static void spin_unlock_irqrestore(pthread_mutex_t *m,irqstate_t flags){(void)flags;pthread_mutex_unlock(m);}
#define OK 0
#define AW_AUDIO_CODEC 0
#define AW_AUDIO_DMIC 1
#define CONFIG_AW_AUDIO_CODEC_MSG_PRIO 1
#define CONFIG_AW_AUDIO_DMIC_MSG_PRIO 1
#define AUDIO_MSG_ENQUEUE 1
#define syslog(...) ((void)0)
#define audinfo(...) ((void)0)
struct audio_lowerhalf_s {int dummy;};
struct ap_buffer_s {int dq_entry;};
struct audio_msg_s {int msg_id;union {int data;} u;};
struct sunxi_dev_s {struct audio_lowerhalf_s dev;int index;pthread_mutex_t pendlock;int pendq,pendsem;bool record;struct {void *f_inode;} mq;};
static unsigned mq_sends;
static int nxsem_post(int *s){(*s)++;return 0;}
static __attribute__((unused)) int nxmutex_lock(pthread_mutex_t *p){(void)p;assert(!"enqueue must not take a blocking mutex");return 0;}
static __attribute__((unused)) int nxmutex_unlock(pthread_mutex_t *p){return pthread_mutex_unlock(p);}
static void dq_addlast(int *entry,int *queue){(void)entry;(*queue)++;}
static int file_mq_send(void *q,const char *msg,size_t length,unsigned prio)
{(void)q;(void)msg;(void)length;(void)prio;mq_sends++;return -EAGAIN;}
'''
    harness += extract_c(source, 'static int sunxi_audio_enqueuebuffer(')
    harness += r'''
int main(void){
 struct sunxi_dev_s priv={.index=AW_AUDIO_CODEC,.pendlock=PTHREAD_MUTEX_INITIALIZER};
 priv.mq.f_inode=&priv;struct ap_buffer_s apb={0};
 assert(sunxi_audio_enqueuebuffer(&priv.dev,&apb)==0&&priv.pendq==1);
 priv.record=true;assert(sunxi_audio_enqueuebuffer(&priv.dev,&apb)==0);
 assert(priv.pendq==2&&priv.pendsem==1&&mq_sends==1);
 pthread_mutex_destroy(&priv.pendlock);return 0;
}
'''
    file = tmp_path / 'enqueue.c'; file.write_text(harness)
    binary = tmp_path / 'enqueue'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread', str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)


@pytest.mark.parametrize("stop_during_read", [True, False])
def test_stopped_pcm_read_returns_the_removed_buffer(tmp_path: Path, stop_during_read: bool):
    source = (SDK_ROOT / 'vendor/allwinnertech/chips/r528/components/audio/sunxi_alsa.c').read_text()
    harness = r'''
#include <assert.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define FAR
static __attribute__((unused)) int nxsem_trywait(int *s){if(*s>0){(*s)--;return 0;}return -1;}
typedef unsigned irqstate_t;
static irqstate_t spin_lock_irqsave(pthread_mutex_t *m){assert(pthread_mutex_trylock(m)==0);return 0;}
static void spin_unlock_irqrestore(pthread_mutex_t *m,irqstate_t flags){(void)flags;pthread_mutex_unlock(m);}
#define AUDIO_CALLBACK_DEQUEUE 1
#define AUDIO_CALLBACK_IOERR 2
#define OK 0
#define LOG_INFO 1
#define LOG_ERR 2
#define syslog(...) ((void)0)
#define audinfo(...) ((void)0)
struct ap_buffer_s {unsigned char *samp;int nbytes,curbyte,flags;};
struct queue {struct ap_buffer_s *items[4];unsigned count;};
struct sunxi_dev_s {bool running;pthread_mutex_t pendlock;void *handle;int pendsem;struct queue pendq;struct {void *priv;void (*upper)(void *,int,struct ap_buffer_s *,int);} dev;};
static unsigned returned, errors;
static void *dq_remfirst(struct queue *q){if(!q->count)return NULL;void *p=q->items[0];for(unsigned i=1;i<q->count;i++)q->items[i-1]=q->items[i];q->count--;return p;}
static __attribute__((unused)) int nxmutex_lock(pthread_mutex_t *m){return pthread_mutex_lock(m);}
static __attribute__((unused)) int nxmutex_unlock(pthread_mutex_t *m){return pthread_mutex_unlock(m);}
static unsigned snd_vela_pcm_frames_to_bytes(void *p,int n){(void)p;return n*2;}
static long snd_vela_pcm_bytes_to_frames(void *p,int n){(void)p;return n/2;}
static long pcm_read(void *p,char *s,long n,unsigned b){(void)s;(void)n;(void)b;if(PCM_STOP_TEST)((struct sunxi_dev_s *)p)->running=false;return -77;}
static void upper(void *context,int reason,struct ap_buffer_s *apb,int status){
 struct sunxi_dev_s *priv=context;assert(status==77&&apb&&apb->nbytes==0);
 if(reason==AUDIO_CALLBACK_IOERR){assert(!PCM_STOP_TEST);errors++;return;}
 assert(reason==AUDIO_CALLBACK_DEQUEUE);
 assert(pthread_mutex_trylock(&priv->pendlock)==0);pthread_mutex_unlock(&priv->pendlock);returned++;
}
'''
    if 'static int record_read_failed(' in source:
        harness += extract_c(source, 'static int record_read_failed(')
    harness += extract_c(source, 'static int record_fillonebuffer(')
    harness += extract_c(source, 'static int record_fillbuffer(')
    harness += r'''
int main(void){
 unsigned char samples[8];struct ap_buffer_s a={samples,8,0,0},b=a;
 struct sunxi_dev_s priv={.running=true,.pendlock=PTHREAD_MUTEX_INITIALIZER,.pendq={{&a,&b},2}};
 priv.handle=&priv;priv.dev.priv=&priv;priv.dev.upper=upper;
 assert(record_fillbuffer(&priv)==-77);
 assert(returned==1&&priv.pendq.count==1); /* Every accepted APB has an owner. */
 priv.running=true;
 assert(record_fillonebuffer(&priv)==-77);
 assert(returned==2&&priv.pendq.count==0&&!priv.running);
 assert(errors==(PCM_STOP_TEST?0:2));
 pthread_mutex_destroy(&priv.pendlock);return 0;
}
'''
    file = tmp_path / 'stop.c'; file.write_text('#define PCM_STOP_TEST '+str(int(stop_during_read))+'\n'+harness)
    binary = tmp_path / 'stop'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread', '-fsanitize=address,undefined',
                    str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)


def test_shutdown_discards_links_to_prepared_buffers_already_freed_by_upper(tmp_path: Path):
    source = (SDK_ROOT / 'vendor/allwinnertech/chips/r528/components/audio/sunxi_alsa.c').read_text()
    harness = r'''
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#define FAR
static __attribute__((unused)) int nxsem_trywait(int *s){if(*s>0){(*s)--;return 0;}return -1;}
#define OK 0
#define DEBUGASSERT assert
struct audio_lowerhalf_s {int dummy;};
struct queue {void *head,*tail;};
struct sunxi_dev_s {struct audio_lowerhalf_s dev;pthread_mutex_t pendlock;struct queue pendq;int pendsem;};
typedef unsigned irqstate_t;
static irqstate_t spin_lock_irqsave(pthread_mutex_t *p){assert(!pthread_mutex_trylock(p));return 0;}
static void spin_unlock_irqrestore(pthread_mutex_t *p,irqstate_t flags){(void)flags;pthread_mutex_unlock(p);}
static void dq_init(struct queue *q){q->head=q->tail=0;}
static int nxsem_reset(int *s,int value){*s=value;return 0;}
static void sunxi_audio_reset(struct sunxi_dev_s *p){(void)p;}
'''
    harness += extract_c(source, 'static int sunxi_audio_shutdown(')
    harness += r'''
int main(void){
 struct sunxi_dev_s p={.pendlock=PTHREAD_MUTEX_INITIALIZER,.pendq={(void *)1,(void *)2},.pendsem=4};
 assert(sunxi_audio_shutdown(&p.dev)==0);
 assert(p.pendq.head==0&&p.pendq.tail==0&&p.pendsem==0);
 pthread_mutex_destroy(&p.pendlock);return 0;
}
'''
    file = tmp_path / 'shutdown.c'; file.write_text(harness)
    binary = tmp_path / 'shutdown'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread', '-fsanitize=address,undefined',
                    str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)
