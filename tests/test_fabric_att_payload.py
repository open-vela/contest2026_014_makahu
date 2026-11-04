"""Compile SDK-to-app ATT payload negotiation and full-fragment admission."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC, fabric_link_declarations
from pathlib import Path
import os
import subprocess
import tempfile

project = PROJECT_ROOT
sdk = SDK_ROOT
app = (project / "app/buffer_app/buffer_fabric.c").read_text()
stack = (sdk / "frameworks/connectivity/bluetooth/service/stacks/zephyr/sal_gatt_server_interface.c").read_text()

def function(source, name):
    start = source.rfind("static ", 0, source.index(name))
    return extract_c(source, source[start:source.index("{", start)].strip())

harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#define MIN(a,b) ((a)<(b)?(a):(b))

typedef int bt_address_t;
struct bt_conn { int unused; };
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t io_wake=PTHREAD_COND_INITIALIZER;
static bt_address_t address=42;
static struct { int error; } status;
static void unlock_status(void) { pthread_mutex_unlock(&lock); }
static int bt_addr_compare(const bt_address_t *a, const bt_address_t *b) { return *a-*b; }
static void bt_sal_get_remote_address(struct bt_conn *c, bt_address_t *a) { (void)c; *a=42; }
'''
harness += fabric_link_declarations() + extract_c(app,"static struct fabric_link_s *link_for_address(") + "\n"
harness += function(app, "mtu_changed(") + "\n"
harness += r'''
static void if_gatts_on_mtu_changed(bt_address_t *a, uint32_t payload) {
    mtu_changed(NULL,a,payload);
}
'''
harness += function(stack, "zblue_gatts_mtu_updated_callback(") + "\n"
harness += function(app, "receive_packet(") + "\n"
harness += r'''
int main(void) {
    links[0].occupied=true;links[0].linked=true;links[0].address=address;links[0].packet_bytes=20;
    uint8_t packet[512]={0};
    zblue_gatts_mtu_updated_callback(NULL,23,23);
    assert(links[0].packet_bytes==20);
    assert(receive_packet(NULL,&address,2,packet,20,0)==20);
    zblue_gatts_mtu_updated_callback(NULL,517,253);
    assert(links[0].packet_bytes==250);
    assert(receive_packet(NULL,&address,2,packet,250,0)==250);
    assert(receive_packet(NULL,&address,2,packet,251,0)==0);
    assert(receive_packet(NULL,&address,2,packet,250,1)==0);
    links[0].host=&status;
    zblue_gatts_mtu_updated_callback(NULL,23,23);
    assert(links[0].packet_bytes==250); /* existing Session budget is immutable */
    links[0].host=NULL;
    zblue_gatts_mtu_updated_callback(NULL,517,517);
    assert(links[0].packet_bytes==512);
    assert(receive_packet(NULL,&address,2,packet,512,0)==512);
    assert(links[0].count==3 && status.error==0);
    return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
    c=Path(d)/"att.c";c.write_text(harness)
    exe=str(Path(d)/"att")
    subprocess.run([*CC,"-std=c11","-Wall","-Wextra","-Werror","-pthread",str(c),"-o",exe],check=True)
    subprocess.run([exe],check=True,timeout=10)
print("Actual SDK ATT MTU 253 -> payload 250 -> full app fragment accepted")
