"""Compile production BLE discovery against a controlled framework boundary."""
from pathlib import Path
import re
import subprocess
import tempfile
import uuid

root = Path(__file__).resolve().parents[1]
source = (root / 'app/buffer_app/buffer_ble.c').read_text()
android = root / 'foundation.fabric/apps/android/buffer-app/src/main/kotlin/com/mocharealm/foundation/fabric/buffer/data/device/VelaBleDiscovery.kt'
# The submission includes both applications; a missing peer source is a failure.
assert android.is_file(), android
if android.exists():
    service = re.search(r'UUID.fromString\("([^"\n]+)"\)', android.read_text()).group(1)
    packet = source.split('g_advertisement[] = {', 1)[1].split('};', 1)[0]
    values = [int(x.strip(), 0) for x in packet.split(',') if x.strip()]
    assert uuid.UUID(bytes=bytes(reversed(values[5:]))) == uuid.UUID(service)
source = re.sub(r'^#include .*$', '', source, flags=re.M)
harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <pthread.h>
#include <stdio.h>
#include <time.h>
#define CONFIG_BUFFER_VELA_BLE 1
#define BUFFER_ID_SIZE 80
#define BT_ADV_STATUS_SUCCESS 0
#define BT_ADAPTER_STATE_ON 1
#define BT_LE_LEGACY_ADV_IND 5
#define BT_LE_ADDR_TYPE_PUBLIC 0
#define BT_LE_ADV_CHANNEL_DEFAULT 0
#define BT_LE_ADV_FILTER_WHITE_LIST_FOR_NONE 0
typedef int bt_instance_t;
typedef int bt_advertiser_t;
typedef struct { int adv_type, own_addr_type, interval, channel_map, filter_policy; } ble_adv_params_t;
typedef struct {
  size_t size;
  void (*on_advertising_start)(bt_advertiser_t *, uint8_t, uint8_t);
  void (*on_advertising_stopped)(bt_advertiser_t *, uint8_t);
} advertiser_callback_t;
static int pairing = -ENOENT, adapter = 1, instance, adv, starts, stops, deletes;
static int start_status;
static bool allocate_fail, adv_fail;
static advertiser_callback_t *callbacks;
static pthread_t stopper;
static bool stop_thread;
static int buffer_ble_service_start(void *p) { (void)p; return 0; }
static void buffer_ble_service_stop(void) {}
static bool buffer_ble_service_busy(void) { return false; }
static int buffer_store_load_pairing(char *h, size_t a, char *t, size_t b, char *p, size_t c)
{ (void)h; (void)a; (void)t; (void)b; (void)p; (void)c; return pairing; }
static void buffer_set_status(const char *format, ...) { (void)format; }
static bt_instance_t *bluetooth_create_instance(void) { return allocate_fail ? NULL : &instance; }
static void bluetooth_delete_instance(bt_instance_t *p) {
  assert(p == &instance); deletes++;
  if (stop_thread) { pthread_join(stopper, NULL); stop_thread = false; }
}
static int bt_adapter_get_state(bt_instance_t *p) { (void)p; return adapter; }
static bt_advertiser_t *bt_le_start_advertising(bt_instance_t *p, ble_adv_params_t *params,
 uint8_t *data, size_t n, uint8_t *rsp, size_t m, advertiser_callback_t *cb) {
  (void)p; starts++; callbacks = cb;
  assert(params->adv_type == BT_LE_LEGACY_ADV_IND);
  assert(n == 21 && m <= 31 && (size_t)rsp[0] + 1 == m);
  assert(data[0] == 2 && data[3] == 17 && data[4] == 7);
  const uint8_t uuid[] = {1,0xb1,0x52,0xbd,0x62,0x3f,0x6b,0x9c,0x0a,0x4e,0x32,0x7d,0,0x10,0x7e,0x48};
  for (int i=0; i<16; i++) assert(data[5+i] == uuid[i]);
  if (adv_fail) return NULL;
  cb->on_advertising_start(&adv, 3, start_status);
  return &adv;
}
static void *stopped(void *p) { (void)p; callbacks->on_advertising_stopped(&adv, 3); return NULL; }
static void bt_le_stop_advertising_id(bt_instance_t *p, uint8_t id) {
  (void)p; assert(id == 3); stops++; stop_thread = true;
  assert(pthread_create(&stopper, NULL, stopped, NULL) == 0);
}
void buffer_ble_stop(void);
''' + source + r'''
int main(void) {
  pairing=0; assert(buffer_ble_start()==0 && starts==0);
  pairing=-EACCES; assert(buffer_ble_start()==-EACCES && starts==0);
  pairing=-ENOENT; allocate_fail=true; assert(buffer_ble_start()==-ENODEV);
  allocate_fail=false; adapter=0; assert(buffer_ble_start()==-EAGAIN && deletes==1);
  adapter=1; adv_fail=true; assert(buffer_ble_start()==-EIO && deletes==2);
  adv_fail=false; start_status=1; assert(buffer_ble_start()==-EIO && deletes==3);
  start_status=0;
  for (int i=0; i<3; i++) {
    assert(buffer_ble_start()==0); int count=starts;
    assert(buffer_ble_start()==0 && starts==count);
    buffer_ble_stop(); assert(stops==i+1);
    buffer_ble_stop(); assert(stops==i+1);
  }
  assert(buffer_ble_start()==0);
  callbacks->on_advertising_stopped(&adv,3);
  buffer_ble_stop(); assert(stops==3); /* no stale advertiser handle */
  puts("BLE discovery payload and lifecycle passed");
}
'''
with tempfile.TemporaryDirectory() as d:
    c = Path(d) / 'test.c'
    c.write_text(harness)
    subprocess.run(['cc', '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-Wall', '-Wextra', '-Werror', '-pthread', str(c), '-o', d+'/test'], check=True)
    subprocess.run([d+'/test'], check=True, timeout=20)
