"""Exercise production H5 framing and retransmission under ASan/UBSan."""
from pathlib import Path
import subprocess
from host_support import CC, SDK_ROOT, extract_c


def test_h5_retransmission(tmp_path: Path):
    source = (SDK_ROOT / 'nuttx/drivers/wireless/bluetooth/bt_slip.c').read_text()
    macros = source[source.index('#define HCI_3WIRE_ACK_PKT'):source.index('/****************************************************************************\n * Private Types')]
    structs = '\n'.join(extract_c(source, name, semicolon=True) for name in (
        'struct unack_pool_s\n', 'struct unack_frame_s\n', 'struct sliphci_s\n'))
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#define FAR
#define CODE
#define CONFIG_BLUETOOTH_SLIP_TXWIN 4
#define CONFIG_BLUETOOTH_SLIP_TXBUFSIZE 128
#define MSEC2TICK(x) (x)
#define HPWORK 0
#define DEBUGASSERT assert
#define wlerr(...) ((void)0)
#define wlinfo(...) ((void)0)
typedef int mutex_t;
typedef int sem_t;
enum bt_buf_type_e {BT_CMD=20,BT_ACL_OUT,BT_ISO_OUT};
struct work_s {int pending;};
struct bt_driver_s {
 int (*send)(struct bt_driver_s *,enum bt_buf_type_e,void *,size_t);
};
static int nxmutex_lock(mutex_t *m) { (void)m; return 0; }
static void nxmutex_unlock(mutex_t *m) { (void)m; }
static int nxsem_wait_uninterruptible(sem_t *s) { (void)s; assert(0); return 0; }
static int work_available(struct work_s *w) { return !w->pending; }
static int work_queue(int q,struct work_s *w,void (*cb)(void *),void *a,int t) {
 (void)q;(void)cb;(void)a;(void)t;w->pending=1;return 0;
}
static uint16_t crc16ccittpart(const void *b,size_t n,uint16_t c) {
 (void)b;(void)n;return c;
}
static uint8_t sent[128];static size_t sent_len;static int short_write;
static int lower_send(struct bt_driver_s *d,enum bt_buf_type_e t,void *p,size_t n) {
 (void)d;(void)t; assert(n<=sizeof(sent));memcpy(sent,p,n);sent_len=n;
 return short_write ? n-1 : n;
}
'''
    harness += macros + structs
    for name in ('static void bt_slip_unack_init(', 'static FAR void *bt_slip_unack_ctor(',
                 'static size_t bt_slip_unack_size(', 'static uint8_t *bt_slip_slip_byte(',
                 'static uint8_t bt_slip_bit8_reverse(', 'static uint16_t bt_slip_bit16_reverse(',
                 'static bool bt_slip_reliable_packet(', 'static int bt_slip_send_packet(',
                 'static void bt_slip_retx_work(', 'static void bt_slip_send_ack(',
                 'static int bt_slip_send('):
        harness += extract_c(source, name)
    harness += r'''
static unsigned seq(void) {
 assert(sent_len>5 && sent[0]==0xc0);return sent[1]&7;
}
int main(void) {
 struct bt_driver_s lower={.send=lower_send};
 struct sliphci_s p={.drv=&lower,.txwin=4,.linkstate=BT_SLIP_ACTIVE};
 uint8_t data[5]={0x20,0xfc,2,1,0xdb};
 assert(bt_slip_send(&p.dev,BT_CMD,data,sizeof(data))==sizeof(data));
 assert(seq()==0 && p.txseq==1 && p.unackpool.size==1);
 p.retxworker.pending=0;bt_slip_retx_work(&p);
 assert(seq()==0 && p.txseq==1 && p.unackpool.size==1 && p.retxworker.pending);
 assert(bt_slip_send(&p.dev,BT_CMD,data,sizeof(data))==sizeof(data));
 assert(seq()==1 && p.txseq==2 && p.unackpool.size==2);
 p.retxworker.pending=0;bt_slip_retx_work(&p);
 assert(seq()==1 && p.txseq==2 && p.unackpool.size==2);
 short_write=1;
 assert(bt_slip_send(&p.dev,BT_CMD,data,sizeof(data))==-EIO);
 assert(p.txseq==2 && p.unackpool.size==2);
 short_write=0;
 assert(bt_slip_send_packet(&p,HCI_COMMAND_PKT,data,4096,2)==-EMSGSIZE);
 p.linkstate=BT_SLIP_UNINITIALIZED;
 assert(bt_slip_send(&p.dev,BT_CMD,data,sizeof(data))==-ENOTCONN);
 return 0;
}
'''
    cfile = tmp_path / 'h5.c'
    cfile.write_text(harness)
    executable = tmp_path / 'h5'
    subprocess.run([*CC, '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    str(cfile), '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
