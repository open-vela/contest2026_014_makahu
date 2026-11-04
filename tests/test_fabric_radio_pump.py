"""Bluetooth RPC and completion never run under the transport mutex."""
from host_support import PROJECT_ROOT, CC, extract_c
import subprocess


def test_radio_rpc_callbacks_and_generation(tmp_path):
    source = (PROJECT_ROOT/'app/buffer_app/buffer_fabric.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <pthread.h>
#define FABRIC_BLE_BUSY -9
#define BT_STATUS_SUCCESS 0
typedef struct {unsigned char data[6];} bt_address_t;
struct packet {size_t length;uint8_t data[512];};
struct fabric_link_s {bool linked,subscribed,notifying,tx_queued,disconnect_requested,occupied;size_t packet_bytes;unsigned generation;bt_address_t address;struct packet tx_packet;};
static struct fabric_link_s links[1];
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
static bool stopping;
static void *service=(void *)1;
static unsigned calls,disconnects;
static bool busy,replace;
static void unlock_status(void){pthread_mutex_unlock(&lock);}
static int bt_gatts_notify(void *handle,bt_address_t *address,int attr,uint8_t *bytes,size_t length){
 assert(handle==service&&address&&attr==3&&length==3&&bytes[0]==0x71);
 /* Model a synchronous callback that needs the same transport lock. */
 assert(!pthread_mutex_trylock(&lock));calls++;
 if(replace){links[0].generation++;links[0].tx_queued=true;links[0].tx_packet.length=2;links[0].tx_packet.data[0]=0x55;}
 else if(!busy)links[0].notifying=false;
 pthread_mutex_unlock(&lock);return busy?1:0;
}
static int bt_gatts_disconnect(void *handle,bt_address_t *address){
 assert(handle==service&&address);assert(!pthread_mutex_trylock(&lock));disconnects++;links[0].occupied=false;pthread_mutex_unlock(&lock);return 0;
}
'''
    harness += extract_c(source,'static int send_packet(')+'\n'
    harness += extract_c(source,'static void radio_flush_one(')+'\n'
    harness += r'''
int main(void){
 struct fabric_link_s *link=&links[0];link->linked=link->subscribed=link->occupied=true;link->packet_bytes=20;link->generation=1;
 uint8_t bytes[]={0x71,2,3};
 pthread_mutex_lock(&lock);assert(!send_packet(link,bytes,3));assert(!calls&&link->notifying&&link->tx_queued);
 assert(send_packet(link,bytes,3)==FABRIC_BLE_BUSY);pthread_mutex_unlock(&lock);
 bytes[0]=0;radio_flush_one(0);assert(calls==1&&!link->notifying&&!link->tx_queued);
 bytes[0]=0x71;pthread_mutex_lock(&lock);assert(!send_packet(link,bytes,3));pthread_mutex_unlock(&lock);
 busy=true;radio_flush_one(0);assert(link->tx_queued&&!link->notifying&&link->tx_packet.data[0]==0x71);
 busy=false;radio_flush_one(0);assert(!link->tx_queued&&!link->notifying);
 pthread_mutex_lock(&lock);assert(!send_packet(link,bytes,3));pthread_mutex_unlock(&lock);
 replace=true;radio_flush_one(0);assert(link->generation==2&&link->tx_queued&&link->tx_packet.length==2&&link->tx_packet.data[0]==0x55);
 replace=false;link->tx_queued=false;link->disconnect_requested=true;radio_flush_one(0);assert(disconnects==1&&!link->disconnect_requested);
 stopping=true;radio_flush_one(0);assert(calls==4);
}
'''
    c=tmp_path/'radio.c';c.write_text(harness);exe=tmp_path/'radio'
    subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-pthread',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=10)
