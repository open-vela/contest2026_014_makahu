/* SPDX-License-Identifier: Apache-2.0 */
#include <nuttx/config.h>
#include "buffer_app.h"
#include "buffer_ble_service.h"
#include "buffer_enrollment.h"
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#ifdef CONFIG_BUFFER_VELA_BLE
#include <bluetooth.h>
#include <bt_adapter.h>
#include <bt_device.h>
#include <bt_gatts.h>

/* All attributes use authenticated Secure Connections. UUIDs are LE bytes. */
#define ENROLL_UUID(n) BT_UUID_DECLARE_128(0x01,0xb1,0x52,0xbd,0x62,0x3f,0x6b,0x9c,0x0a,0x4e,0x32,0x7d,n,0x10,0x7e,0x48)
#define SECURE (GATT_PERM_AUTHEN_REQUIRED | GATT_PERM_MITM_REQUIRED)
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_changed = PTHREAD_COND_INITIALIZER;
static bt_instance_t *g_instance;
static gatts_handle_t g_service;
static void *g_cookie;
static bt_io_capability_t g_old_io;
static bool g_io_changed;
static bool g_table_reported;
static bool g_table_ready;
static bool g_connected;
static bool g_lan_busy;
static bool g_pending;
static bool g_working;
static bool g_have_last;
static bool g_committed;
static bt_address_t g_peer;
static int64_t g_deadline;
static char g_code[7];
static char g_device_id[BUFFER_ID_SIZE];
static char g_state[32] = "idle";
static int g_error;
static char g_result[320];
static size_t g_result_length;
static size_t g_result_offset;
static struct buffer_enrollment_frame_s g_frame;
static struct buffer_enrollment_request_s g_request;
static struct buffer_enrollment_request_s g_last;

static int64_t now_seconds(void)
{
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec;
}

static void erase(void *data, size_t length)
{
  volatile unsigned char *p = data;
  while (length--) *p++ = 0;
}

/* Caller holds g_lock. Link security is also enforced by the attribute table. */
static bool selected(bt_address_t *peer)
{
  return g_connected && bt_addr_compare(peer, &g_peer) == 0 && now_seconds() < g_deadline;
}

static void connected(void *service, bt_address_t *peer)
{
  pthread_mutex_lock(&g_lock);
  bool reject = g_connected || g_working || g_committed || g_lan_busy;
  if (!reject)
    {
      g_connected = true;
      g_peer = *peer;
      g_deadline = now_seconds() + 180;
      g_code[0] = 0;
      g_result_length = g_result_offset = 0;
      buffer_enrollment_reset(&g_frame);
    }
  pthread_mutex_unlock(&g_lock);
  if (reject) (void)bt_gatts_disconnect(service, peer);
}

static void disconnected(void *service, bt_address_t *peer)
{
  (void)service;
  pthread_mutex_lock(&g_lock);
  if (g_connected && bt_addr_compare(peer, &g_peer) == 0)
    {
      g_connected = false;
      g_code[0] = 0;
      if (g_pending)
        {
          strcpy(g_state, "idle"); g_error = 0; g_have_last = false;
          erase(&g_last, sizeof(g_last));
        }
      g_pending = false;
      buffer_enrollment_reset(&g_frame);
      if (!g_working) erase(&g_request, sizeof(g_request));
    }
  pthread_mutex_unlock(&g_lock);
}

static void pair_request(void *cookie, bt_address_t *peer)
{
  (void)cookie;
  pthread_mutex_lock(&g_lock);
  bool allow = selected(peer) && !g_committed;
  pthread_mutex_unlock(&g_lock);
  (void)bt_device_pair_request_reply(g_instance, peer, allow);
}

static void pair_display(void *cookie, bt_address_t *peer,
                         bt_transport_t transport, bt_pair_type_t type,
                         uint32_t passkey)
{
  (void)cookie;
  pthread_mutex_lock(&g_lock);
  bool ours = selected(peer) && transport == BT_TRANSPORT_BLE;
  bool allow = ours && type == PAIR_TYPE_PASSKEY_NOTIFICATION && passkey <= 999999;
  if (allow) snprintf(g_code, sizeof(g_code), "%06lu", (unsigned long)(passkey % 1000000));
  if (ours && !allow) g_code[0] = 0;
  pthread_mutex_unlock(&g_lock);
  if (ours && !allow)
    (void)bt_device_cancel_bond(g_instance, peer);
}

static void table_added(void *service, gatt_status_t status, uint16_t handle)
{
  (void)service; (void)handle;
  pthread_mutex_lock(&g_lock);
  g_table_reported = true;
  g_table_ready = status == GATT_STATUS_SUCCESS;
  pthread_cond_broadcast(&g_changed);
  pthread_mutex_unlock(&g_lock);
}

/* Results use explicit pages because this stack does not preserve READ_BLOB
 * offsets for deferred reads. A two-byte write selects the result offset;
 * offset zero snapshots the current result. Each read fits default ATT MTU. */
static void snapshot_result(void)
{
  int n = snprintf(g_result, sizeof(g_result),
             "{\"protocol\":1,\"request_id\":\"%s\",\"device_id\":\"%s\",\"state\":\"%s\",\"error\":%d}",
             g_pending || g_working ? g_request.request_id : g_last.request_id,
             g_device_id, g_state, g_error);
  g_result_length = n > 0 && (size_t)n < sizeof(g_result) ? (size_t)n : 0;
  g_result_offset = 0;
}

static uint16_t read_result(void *service, bt_address_t *peer,
                             uint16_t attr, uint32_t request)
{
  (void)attr;
  uint8_t response[20] = {0};
  size_t count = 4;
  bool encrypted = bt_device_is_encrypted(g_instance, peer, BT_TRANSPORT_BLE);
  pthread_mutex_lock(&g_lock);
  if (selected(peer) && encrypted)
    {
      if (g_result_length == 0) snapshot_result();
      size_t n = g_result_length - g_result_offset;
      if (n > 16) n = 16;
      response[0] = g_result_offset >> 8; response[1] = g_result_offset;
      response[2] = g_result_length >> 8; response[3] = g_result_length;
      memcpy(response + 4, g_result + g_result_offset, n);
      count += n;
    }
  pthread_mutex_unlock(&g_lock);
  (void)bt_gatts_response(service, peer, request, response, count);
  return 0;
}

static uint16_t write_config(void *service, bt_address_t *peer, uint16_t attr,
                             const uint8_t *value, uint16_t length, uint16_t offset)
{
  (void)service; (void)attr;
  if (value == NULL) return 0;
  if (!bt_device_is_encrypted(g_instance, peer, BT_TRANSPORT_BLE)) return 0;
  pthread_mutex_lock(&g_lock);
  if (!selected(peer) || offset != 0)
    { pthread_mutex_unlock(&g_lock); return 0; }
  if (length == 2)
    {
      size_t cursor = ((size_t)value[0] << 8) | value[1];
      if (cursor == 0) snapshot_result();
      if (cursor < g_result_length) g_result_offset = cursor;
      pthread_mutex_unlock(&g_lock);
      return length;
    }
  if (g_pending || g_working)
    { pthread_mutex_unlock(&g_lock); return 0; }
  int ret = buffer_enrollment_feed(&g_frame, value, length);
  if (ret == 1)
    {
      ret = buffer_enrollment_decode(g_frame.data, g_frame.received, &g_request);
      if (ret == 0 && g_have_last && strcmp(g_last.request_id, g_request.request_id) == 0)
        {
          if (memcmp(&g_last, &g_request, sizeof(g_last)) != 0)
            { strcpy(g_state, "request_conflict"); g_error = -EINVAL; }
          erase(&g_request, sizeof(g_request));
          buffer_enrollment_reset(&g_frame);
        }
      else if (ret == 0 && !g_committed)
        { g_pending = true; strcpy(g_state, "queued"); g_error = 0; }
      else if (ret == 0) ret = -EACCES;
    }
  if (ret < 0)
    {
      strcpy(g_state, "invalid_request"); g_error = ret;
      buffer_enrollment_reset(&g_frame);
      erase(&g_request, sizeof(g_request));
    }
  pthread_mutex_unlock(&g_lock);
  return length;
}

static gatt_attr_db_t g_attrs[] = {
  GATT_H_PRIMARY_SERVICE(ENROLL_UUID(0), 1),
  GATT_H_CHARACTERISTIC_USER_RSP(ENROLL_UUID(1), GATT_PROP_WRITE, GATT_PERM_WRITE | SECURE, NULL, write_config, 2),
  GATT_H_CHARACTERISTIC_USER_RSP(ENROLL_UUID(2), GATT_PROP_READ, GATT_PERM_READ | SECURE, read_result, NULL, 3),
};
static gatt_srv_db_t g_database = { .attr_db = g_attrs, .attr_num = sizeof(g_attrs) / sizeof(g_attrs[0]) };
static gatts_callbacks_t g_callbacks = {
  .size = sizeof(gatts_callbacks_t), .on_connected = connected,
  .on_disconnected = disconnected, .on_attr_table_added = table_added,
};
static adapter_callbacks_t g_adapter_callbacks = { .on_pair_request = pair_request, .on_pair_display = pair_display };

int buffer_ble_service_start(void *instance)
{
  g_instance = instance;
  buffer_pairing_snapshot(g_device_id, sizeof(g_device_id), NULL, 0);
  g_old_io = bt_adapter_get_io_capability(g_instance);
  if (g_old_io == BT_IO_CAPABILITY_UNKNOW) goto fail;
  if (bt_adapter_set_io_capability(g_instance, BT_IO_CAPABILITY_DISPLAYONLY) != BT_STATUS_SUCCESS) goto fail;
  g_io_changed = true;
  if (bt_device_set_security_level(g_instance, 4, BT_TRANSPORT_BLE) != BT_STATUS_SUCCESS) goto fail;
  if (bt_device_set_bondable_le(g_instance, true) != BT_STATUS_SUCCESS) goto fail;
  g_cookie = bt_adapter_register_callback(g_instance, &g_adapter_callbacks);
  if (g_cookie == NULL) goto fail;
  if (bt_gatts_register_service(g_instance, &g_service, &g_callbacks) != BT_STATUS_SUCCESS) goto fail;
  pthread_mutex_lock(&g_lock);
  g_table_reported = g_table_ready = false;
  g_committed = false;
  pthread_mutex_unlock(&g_lock);
  if (bt_gatts_add_attr_table(g_service, &g_database) != BT_STATUS_SUCCESS) goto fail;
  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline); deadline.tv_sec += 5;
  pthread_mutex_lock(&g_lock);
  while (!g_table_reported)
    if (pthread_cond_timedwait(&g_changed, &g_lock, &deadline) != 0) break;
  bool ready = g_table_ready;
  pthread_mutex_unlock(&g_lock);
  if (!ready) goto fail;
  return 0;
fail:
  buffer_ble_service_stop();
  return -EIO;
}

void buffer_ble_service_stop(void)
{
  if (g_service != NULL) { (void)bt_gatts_unregister_service(g_service); g_service = NULL; }
  if (g_cookie != NULL) { (void)bt_adapter_unregister_callback(g_instance, g_cookie); g_cookie = NULL; }
  if (g_io_changed) { (void)bt_adapter_set_io_capability(g_instance, g_old_io); g_io_changed = false; }
  pthread_mutex_lock(&g_lock);
  g_connected = g_pending = g_working = g_have_last = g_committed = false;
  g_result_length = g_result_offset = 0;
  g_code[0] = 0; g_error = 0; strcpy(g_state, "idle");
  buffer_enrollment_reset(&g_frame);
  erase(&g_request, sizeof(g_request)); erase(&g_last, sizeof(g_last));
  pthread_mutex_unlock(&g_lock);
}

bool buffer_ble_service_claim_lan(void)
{
  pthread_mutex_lock(&g_lock);
  bool allow = !g_lan_busy && !g_connected && !g_pending && !g_working;
  if (allow) g_lan_busy = true;
  pthread_mutex_unlock(&g_lock);
  return allow;
}

void buffer_ble_service_release_lan(void)
{
  pthread_mutex_lock(&g_lock);
  g_lan_busy = false;
  pthread_mutex_unlock(&g_lock);
}

bool buffer_ble_service_busy(void)
{
  pthread_mutex_lock(&g_lock);
  bool busy = g_connected || g_pending || g_working;
  pthread_mutex_unlock(&g_lock);
  return busy;
}

void buffer_ble_service_code(char *code, size_t size)
{
  pthread_mutex_lock(&g_lock);
  snprintf(code, size, "%s", g_code);
  pthread_mutex_unlock(&g_lock);
}

/* Called only by the pairing thread. Wi-Fi/flash calls never run in a GATT callback. */
void buffer_ble_service_process(void)
{
  struct buffer_enrollment_request_s request;
  pthread_mutex_lock(&g_lock);
  if (g_connected && now_seconds() >= g_deadline && !g_working)
    {
      bt_address_t peer = g_peer;
      g_connected = false; g_code[0] = 0;
      if (g_pending)
        {
          strcpy(g_state, "idle"); g_error = 0; g_have_last = false;
          erase(&g_last, sizeof(g_last));
        }
      g_pending = false;
      erase(&g_request, sizeof(g_request));
      buffer_enrollment_reset(&g_frame);
      pthread_mutex_unlock(&g_lock);
      (void)bt_gatts_disconnect(g_service, &peer);
      return;
    }
  if (!g_pending) { pthread_mutex_unlock(&g_lock); return; }
  request = g_request; g_pending = false; g_working = true;
  strcpy(g_state, "connecting");
  pthread_mutex_unlock(&g_lock);
  int ret = request.use_existing_wifi ? buffer_wifi_reconnect() :
      buffer_wifi_configure(request.ssid, request.password);
  pthread_mutex_lock(&g_lock);
  /* A disconnected/cancelled enrollment may change Wi-Fi, but never binds an owner. */
  if (ret == 0 && !selected(&g_peer)) ret = -ECANCELED;
  if (ret == 0) ret = buffer_store_save_enrollment("0.0.0.0", request.token, request.phone_id, request.request_id);
  g_committed = ret == 0;
  g_last = request; g_have_last = true; g_working = false;
  g_error = ret; strcpy(g_state, ret == 0 ? "network_ready" : "failed");
  buffer_enrollment_reset(&g_frame);
  erase(&g_request, sizeof(g_request));
  pthread_mutex_unlock(&g_lock);
  erase(&request, sizeof(request));
  if (ret == 0) buffer_request_sync();
}
#else
int buffer_ble_service_start(void *instance) { (void)instance; return 0; }
void buffer_ble_service_stop(void) {}
bool buffer_ble_service_busy(void) { return false; }
bool buffer_ble_service_claim_lan(void) { return true; }
void buffer_ble_service_release_lan(void) {}
void buffer_ble_service_process(void) {}
void buffer_ble_service_code(char *code, size_t size) { if (size) code[0] = 0; }
#endif
