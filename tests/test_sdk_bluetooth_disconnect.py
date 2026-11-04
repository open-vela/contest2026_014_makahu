"""Exercise SDK IPC disconnect followed by instance cleanup under ASan.

Run against the openvela SDK containing the project (or set OPENVELA_SDK).
The source under test is extracted unchanged; only OS/libuv boundaries are faked.
"""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC
from pathlib import Path
import os
import subprocess
import tempfile

project = PROJECT_ROOT
sdk = SDK_ROOT
source = (sdk / "frameworks/connectivity/bluetooth/service/ipc/socket/src/bt_socket_client.c").read_text()

def function(name):
    start = source.index("static ", source.index(name) - 30)
    return extract_c(source, source[start:source.index("{", start)].strip())

harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#define BT_STATUS_SUCCESS 0
#define BT_STATUS_NOMEM -12
#define BT_STATUS_FAIL -1
#define BT_MESSAGE_START 1
#define BT_MESSAGE_END 10
#define BT_CALLBACK_START 11
#define BT_CALLBACK_END 20
#define UV_DISCONNECT 1
#define UV_READABLE 2
#define BT_LOGE(...) ((void)0)
typedef int uv_os_fd_t;
typedef int bt_status_t;
typedef struct { void *data; } uv_poll_t;
typedef uv_poll_t uv_handle_t;
typedef struct { int code; } bt_message_packet_t;
typedef struct instance {
    bt_message_packet_t *packet, *cpacket;
    int offset, message_processed, peer_fd;
    void *external_loop;
    uv_poll_t *poll;
    void (*disconnected)(struct instance *, void *, int);
} bt_instance_t;
typedef struct { bt_instance_t *ins; bt_message_packet_t packet; } bt_client_msg_t;
static int removed, mode, notified;
static void thread_loop_remove_poll(uv_poll_t *p) {
    assert(removed++ == 0); free(p);
}
static int uv_fileno(uv_handle_t *h, uv_os_fd_t *fd) {
    (void)h; *fd = -1; return mode == 1 ? -1 : 0;
}
static int fake_recv(int fd, void *buf, size_t n, int flags) {
    (void)fd; (void)buf; (void)n; (void)flags;
    errno = ECONNRESET; return mode == 3 ? -1 : 0;
}
#define recv fake_recv
static void uv_sem_post(int *p) { (void)p; }
static int callback_send_to_external(bt_instance_t *p, bt_client_msg_t *m) {
    (void)p; free(m); return 0;
}
static int callback_send_to_queue_work(bt_instance_t *p, bt_client_msg_t *m) {
    (void)p; free(m); return 0;
}
static void disconnected(bt_instance_t *p, void *unused, int status) {
    (void)unused; (void)status; assert(p->poll == NULL); notified++;
}
'''
harness += "\n".join(function(name) for name in (
    "bt_socket_client_receive(", "bt_socket_client_handle_event(", "bt_socket_sync_close("))
harness += r'''
int main(void) {
    for (mode = 0; mode < 4; mode++) {
        bt_message_packet_t packet = {0};
        bt_instance_t ins = {.packet=&packet, .peer_fd=-1, .disconnected=disconnected};
        uv_poll_t *poll = malloc(sizeof(*poll));
        poll->data = &ins; ins.poll = poll; removed = 0;
        bt_socket_client_handle_event(poll, 0, mode == 2 ? UV_DISCONNECT : UV_READABLE);
        assert(ins.poll == NULL && removed == 1);
        bt_socket_sync_close(&ins);
        assert(ins.poll == NULL && removed == 1);
    }
    assert(notified == 1);
    return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
    c = Path(d) / "disconnect.c"
    c.write_text(harness)
    exe = str(Path(d) / "disconnect")
    subprocess.run([*CC, "-g", "-fsanitize=address,undefined", "-fno-omit-frame-pointer", str(c), "-o", exe], check=True)
    subprocess.run([exe], check=True, timeout=10)
print("SDK EOF, invalid handle, disconnect and read error followed by cleanup: ASan passed")
