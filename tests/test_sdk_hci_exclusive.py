"""Verify exclusive firmware ownership using the production HCI open/close."""
from pathlib import Path
import subprocess

from host_support import CC, SDK_ROOT, extract_c


def test_hci_exclusive_owner(tmp_path: Path):
    source = (SDK_ROOT / "nuttx/drivers/serial/uart_bth4.c").read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#define FAR
#define OK 0
struct bt_driver_s {
  int (*open)(struct bt_driver_s *);
  void (*close)(struct bt_driver_s *);
};
struct uart_bth4_s {
  struct bt_driver_s *drv;
  int openlock;
  uint8_t refcnt;
  bool exclusive;
  bool handoff;
  unsigned sendlen;
};
static int g_uart_bth4_ops;
struct inode { void *i_private; union {void *i_ops;} u; };
struct file { struct inode *f_inode; int f_oflags; };
static int opened, closed, open_error;
static int nxmutex_lock(int *lock) { assert(!*lock); *lock=1; return 0; }
static void nxmutex_unlock(int *lock) { assert(*lock); *lock=0; }
static void uart_bth4_pollnotify(struct uart_bth4_s *d, int e) { (void)d; (void)e; }
static int lower_open(struct bt_driver_s *d) { (void)d; opened++; return open_error; }
static void lower_close(struct bt_driver_s *d) { (void)d; closed++; }
'''
    harness += extract_c(source, "static int uart_bth4_open(")
    harness += extract_c(source, "static int uart_bth4_close(")
    harness += extract_c(source, "int uart_bth4_handoff(")
    harness += r'''
int main(void) {
  struct bt_driver_s drv={lower_open,lower_close};
  struct uart_bth4_s dev={.drv=&drv};
  struct inode inode={&dev,{&g_uart_bth4_ops}};
  struct file firmware={&inode,O_RDWR|O_EXCL}, host={&inode,O_RDWR};
  assert(uart_bth4_open(&firmware)==0);
  assert(dev.exclusive && dev.refcnt==1);
  assert(uart_bth4_open(&host)==-EBUSY);
  assert(uart_bth4_open(&firmware)==-EBUSY);
  assert(dev.refcnt==1 && opened==1);
  assert(uart_bth4_close(&firmware)==0);
  assert(!dev.exclusive && !dev.refcnt && closed==1);
  assert(uart_bth4_open(&host)==0);
  assert(uart_bth4_open(&firmware)==-EBUSY);
  assert(uart_bth4_open(&host)==0 && dev.refcnt==2);
  assert(uart_bth4_close(&host)==0 && dev.refcnt==1 && closed==1);
  assert(uart_bth4_close(&host)==0 && !dev.refcnt && closed==2);
  open_error=-EIO;
  assert(uart_bth4_open(&firmware)==-EIO);
  assert(!dev.refcnt && !dev.exclusive && !dev.openlock);
  open_error=0;
  assert(uart_bth4_open(&host)==0);
  assert(uart_bth4_close(&host)==0);
  assert(uart_bth4_open(&firmware)==0);
  assert(uart_bth4_handoff(&firmware)==0);
  int previous_opened=opened, previous_closed=closed;
  assert(uart_bth4_close(&firmware)==0 && closed==previous_closed);
  assert(uart_bth4_open(&host)==0 && opened==previous_opened);
  assert(!dev.handoff && !dev.exclusive);
  assert(uart_bth4_handoff(&host)==-EPERM);
  assert(uart_bth4_close(&host)==0 && closed==previous_closed+1);
  return 0;
}
'''
    cfile = tmp_path / "exclusive.c"
    cfile.write_text(harness)
    executable = tmp_path / "exclusive"
    subprocess.run([*CC, "-g", "-fsanitize=address,undefined",
                    "-fno-omit-frame-pointer", str(cfile), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
