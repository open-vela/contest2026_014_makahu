"""Production firmware I/O must reject truncated, stale and timed-out replies."""
from pathlib import Path
import subprocess
from host_support import CC, SDK_ROOT, extract_c


def test_realtek_command_io(tmp_path: Path):
    source = (SDK_ROOT / 'vendor/allwinnertech/chips/r528/components/bluetooth/rtk_hci.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>
#include <string.h>
#include <errno.h>
#define FAR
#define OK 0
#define HCI_EVENT_PKT 4
struct file {int unused;};
struct bt_hci_evt_hdr {uint8_t evt,len;};
static uint64_t ticks;
static int mode;
static size_t offset;
static uint8_t reply[15]={4,0x0e,4,1,3,0x0c,0};
static uint64_t get_timestamp_us(void) { return ticks; }
static int fake_sleep(unsigned n) { ticks+=n; return 0; }
#define usleep fake_sleep
static ssize_t file_read(struct file *f,void *p,size_t n) {
 (void)f;(void)n;
 if(mode==1 || offset>=7) return -EAGAIN;
 if(mode==2) return 0;
 if(mode==3) return -EIO;
 *(uint8_t *)p=reply[offset++];return 1;
}
static ssize_t file_write(struct file *f,const void *p,size_t n) {
 (void)f;(void)p;(void)n;
 if(mode==4)return 0;
 if(mode==5)return -EAGAIN;
 return 1;
}
'''
    for name in ('static int hci_recv(', 'static int hci_send(', 'static int hci_send_recv('):
        harness += extract_c(source, name)
    harness += r'''
static int command(void) {
 uint8_t cmd[15]={1,3,0x0c,0};struct file f={0};offset=0;ticks=0;
 return hci_send_recv(&f,cmd,4,sizeof(cmd));
}
int main(void) {
 assert(command()==0);
 mode=1;assert(command()==-ETIMEDOUT && ticks>=5000000 && ticks<5010000);
 mode=2;assert(command()==-EPIPE);
 mode=3;assert(command()==-EIO);
 mode=4;assert(command()==-EPIPE);
 mode=5;assert(command()==-ETIMEDOUT);
 mode=0;reply[2]=200;assert(command()==-EPROTO);
 reply[2]=4;reply[4]=4;assert(command()==-EPROTO);
 reply[4]=3;reply[6]=1;assert(command()==-EPROTO);
 return 0;
}
'''
    cfile = tmp_path / 'download.c'
    cfile.write_text(harness)
    executable = tmp_path / 'download'
    subprocess.run([*CC, '-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer',
                    str(cfile), '-o', str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
