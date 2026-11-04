/****************************************************************************
 * Buffer BLE discovery for combined enrollment.
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/
#include <nuttx/config.h>
#include "buffer_app.h"
#include "buffer_ble_service.h"
#ifdef CONFIG_BUFFER_VELA_FABRIC
#include "buffer_fabric.h"
#endif
#include <errno.h>

#ifdef CONFIG_BUFFER_VELA_BLE
#include <bluetooth.h>
#include <bt_adapter.h>
#include <bt_le_advertiser.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <signal.h>

/* Bluetooth AD encodes a 128-bit UUID least-significant octet first.
 * 487e1000-7d32-4e0a-9c6b-3f62bd52b101, shared with VelaBleDiscovery.
 * Advertising exposes no identity proof, pairing code or credentials.
 */
#ifdef CONFIG_BUFFER_VELA_FABRIC
static uint8_t g_advertisement[] = {
  2,0x01,0x06,17,0x07,0x43,0x49,0x52,0x42,0x41,0x46,0x8f,0x9e,
  0x30,0x4a,0x4e,0x6b,1,0,0x6d,0x7f
};
#else
static uint8_t g_advertisement[] = {
  2, 0x01, 0x06,
  17, 0x07, 0x01, 0xb1, 0x52, 0xbd, 0x62, 0x3f, 0x6b, 0x9c,
  0x0a, 0x4e, 0x32, 0x7d, 0x00, 0x10, 0x7e, 0x48
};
#endif
static uint8_t g_scan_response[] = {
  17, 0x09, 'B', 'u', 'f', 'f', 'e', 'r', ' ', 'G', 'e', 'm', 'i', 'n', 'i', ' ', 'S', '1'
};

/* Owned only by the pairing thread; callbacks never access these handles. */
static bt_instance_t *g_ble;
static pthread_mutex_t g_ble_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_advertiser_id = -1;
static pthread_cond_t g_ble_changed = PTHREAD_COND_INITIALIZER;
static bool g_start_reported;

static void buffer_ble_started(bt_advertiser_t *advertiser, uint8_t id,
                               uint8_t status)
{
  (void)advertiser;
  pthread_mutex_lock(&g_ble_lock);
  g_advertiser_id = status == BT_ADV_STATUS_SUCCESS ? id : -1;
  g_start_reported = true;
  pthread_cond_broadcast(&g_ble_changed);
  pthread_mutex_unlock(&g_ble_lock);
  if (status != BT_ADV_STATUS_SUCCESS)
    {
      buffer_set_status("蓝牙发现启动失败：%u", status);
    }
}

static void buffer_ble_stopped(bt_advertiser_t *advertiser, uint8_t id)
{
  (void)advertiser;
  pthread_mutex_lock(&g_ble_lock);
  if (g_advertiser_id == id) g_advertiser_id = -1;
  pthread_cond_broadcast(&g_ble_changed);
  pthread_mutex_unlock(&g_ble_lock);
}

static advertiser_callback_t g_callbacks = {
  .size = sizeof(advertiser_callback_t),
  .on_advertising_start = buffer_ble_started,
  .on_advertising_stopped = buffer_ble_stopped,
};

int buffer_ble_start(void)
{
  bool reuse_service = g_ble != NULL;
  /* The SDK's local socket IPC sends without MSG_NOSIGNAL. A daemon restart
   * must become an EPIPE/retry result, not terminate the Buffer task group. */
  if (signal(SIGPIPE, SIG_IGN) == SIG_ERR) return -errno;
  ble_adv_params_t params = {0};
#ifndef CONFIG_BUFFER_VELA_FABRIC
  char host[80];
  char token[128];
  char phone_id[BUFFER_ID_SIZE];
#endif

  if (g_ble != NULL)
    {
      pthread_mutex_lock(&g_ble_lock);
      bool active = g_advertiser_id >= 0;
      pthread_mutex_unlock(&g_ble_lock);
      if (active) return 0;
#ifdef CONFIG_BUFFER_VELA_FABRIC
      if (!buffer_fabric_accepting_connections()) return 0;
#else
      if (buffer_ble_service_busy()) return 0;
#endif
      /* A normal disconnect only consumes the advertiser. Retain the GATT
       * table, callbacks and instance while opening the next advertiser. */
    }

  /* Fabric advertises reconnect availability even when already bound. Its
   * identity/session policy rejects untrusted peers without reopening claim. */
#ifndef CONFIG_BUFFER_VELA_FABRIC
  /* Do not announce a bound device as available for legacy enrollment. */
  int pairing = buffer_store_load_pairing(host, sizeof(host), token, sizeof(token),
                                          phone_id, sizeof(phone_id));
  if (pairing == 0) return 0;
  if (pairing != -ENOENT) return pairing;
#endif

  if (g_ble == NULL) g_ble = bluetooth_create_instance();
  if (g_ble == NULL)
    {
      return -ENODEV;
    }

  bt_adapter_state_t state = bt_adapter_get_state(g_ble);
  if (state == BT_ADAPTER_STATE_OFF)
    {
      bt_status_t result = bt_adapter_enable_le(g_ble);
      if (result != BT_STATUS_SUCCESS)
        {
          printf("Buffer BLE: enable failed status=%d\n", result);
          bluetooth_delete_instance(g_ble); g_ble = NULL;
          return -EIO;
        }
    }
  /* Adapter power-on is asynchronous. This runs on the pairing worker,
   * never in an LVGL callback. BLE-only ON is sufficient for GATT. */
  for (unsigned attempt = 0; attempt < 50; attempt++)
    {
      state = bt_adapter_get_state(g_ble);
      if (state == BT_ADAPTER_STATE_ON || state == BT_ADAPTER_STATE_BLE_ON) break;
      usleep(100000);
    }
  if (state != BT_ADAPTER_STATE_ON && state != BT_ADAPTER_STATE_BLE_ON)
    {
      printf("Buffer BLE: adapter not ready state=%d\n", state);
      bluetooth_delete_instance(g_ble);
      g_ble = NULL;
      return -EAGAIN;
    }

  if (buffer_ble_service_start(g_ble) < 0)
    {
      bluetooth_delete_instance(g_ble);
      g_ble = NULL;
      return -EIO;
    }
  params.adv_type = BT_LE_LEGACY_ADV_IND;
  params.own_addr_type = BT_LE_ADDR_TYPE_PUBLIC;
  params.interval = 320;
  params.channel_map = BT_LE_ADV_CHANNEL_DEFAULT;
  params.filter_policy = BT_LE_ADV_FILTER_WHITE_LIST_FOR_NONE;
  pthread_mutex_lock(&g_ble_lock);
  g_start_reported = false;
  g_advertiser_id = -1;
  pthread_mutex_unlock(&g_ble_lock);
  bt_advertiser_t *advertiser = bt_le_start_advertising(g_ble, &params,
                                        g_advertisement,
                                        sizeof(g_advertisement),
                                        g_scan_response,
                                        sizeof(g_scan_response), &g_callbacks);
  if (advertiser == NULL)
    {
      if (reuse_service) return -EIO;
      buffer_ble_service_stop();
      bluetooth_delete_instance(g_ble);
      g_ble = NULL;
      return -EIO;
    }

  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += 5;
  pthread_mutex_lock(&g_ble_lock);
  while (!g_start_reported)
    {
      if (pthread_cond_timedwait(&g_ble_changed, &g_ble_lock, &deadline) != 0) break;
    }
  bool started = g_start_reported && g_advertiser_id >= 0;
  pthread_mutex_unlock(&g_ble_lock);
  if (!started)
    {
      if (reuse_service) return -EIO;
      buffer_ble_stop();
      return -EIO;
    }
  return 0;
}

void buffer_ble_stop(void)
{
  if (g_ble != NULL)
    {
      pthread_mutex_lock(&g_ble_lock);
      if (g_advertiser_id >= 0)
        {
          bt_le_stop_advertising_id(g_ble, (uint8_t)g_advertiser_id);
          struct timespec deadline;
          clock_gettime(CLOCK_REALTIME, &deadline);
          deadline.tv_sec += 5;
          while (g_advertiser_id >= 0)
            {
              if (pthread_cond_timedwait(&g_ble_changed, &g_ble_lock, &deadline) != 0) break;
            }
        }
      pthread_mutex_unlock(&g_ble_lock);
      buffer_ble_service_stop();
      bluetooth_delete_instance(g_ble);
      g_ble = NULL;
    }
}
#else
int buffer_ble_start(void) { return 0; }
void buffer_ble_stop(void) {}
#endif
