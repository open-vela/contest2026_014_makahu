/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela Wi-Fi provisioning
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_enrollment.h"

#include <errno.h>
#include <limits.h>
#include <netutils/netinit.h>
#include <netutils/netlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <wireless/wapi.h>

#define BUFFER_WIFI_DHCP_ATTEMPTS 3
#define BUFFER_WIFI_ASSOCIATE_WAIT_SEC 5

static int buffer_wifi_prepare_config_path(void)
{
  char path[PATH_MAX];
  size_t length;
  size_t index;

  length = strlen(CONFIG_WIRELESS_WAPI_CONFIG_PATH);
  if (length == 0 || length >= sizeof(path))
    {
      return -ENAMETOOLONG;
    }

  snprintf(path, sizeof(path), "%s", CONFIG_WIRELESS_WAPI_CONFIG_PATH);
  for (index = 1; index < length; index++)
    {
      int ret;

      if (path[index] != '/')
        {
          continue;
        }

      path[index] = '\0';
      ret = mkdir(path, 0755);
      path[index] = '/';
      if (ret < 0 && errno != EEXIST)
        {
          return -errno;
        }
    }

  return 0;
}

static int buffer_wifi_ifup(const char *ifname)
{
  int ret;

  ret = netlib_ifup(ifname);
  if (ret < 0)
    {
      return errno > 0 ? -errno : -EIO;
    }

  return 0;
}

static int buffer_wifi_connect_saved(const char *network_label)
{
  int ret;
  int attempt;

  ret = buffer_wifi_ifup(CONFIG_BUFFER_VELA_WIFI_IFNAME);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 接口启动失败：%d", ret);
      return ret;
    }

  ret = netinit_associate(CONFIG_BUFFER_VELA_WIFI_IFNAME);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 关联失败：%d", ret);
      return ret;
    }

  buffer_set_status("Wi-Fi 正在连接 %s", network_label);
  sleep(BUFFER_WIFI_ASSOCIATE_WAIT_SEC);
  for (attempt = 0; attempt < BUFFER_WIFI_DHCP_ATTEMPTS; attempt++)
    {
      ret = netlib_obtain_ipv4addr(CONFIG_BUFFER_VELA_WIFI_IFNAME);
      if (ret == 0)
        {
          buffer_set_status("Wi-Fi 已连接：%s", network_label);
          return 0;
        }

      if (attempt + 1 < BUFFER_WIFI_DHCP_ATTEMPTS)
        {
          sleep(2);
        }
    }

  buffer_set_status("Wi-Fi 已关联但 DHCP 失败：%d", ret);
  return ret;
}

int buffer_wifi_configure(const char *ssid, const char *passphrase)
{
  struct wpa_wconfig_s config;
  int ret;

  ret = buffer_wifi_credentials_valid(ssid, passphrase);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 名称或密码格式无效（64 位密钥必须为十六进制）");
      return ret;
    }

  ret = buffer_wifi_prepare_config_path();
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 配置目录创建失败：%d", ret);
      return ret;
    }

  memset(&config, 0, sizeof(config));
  config.sta_mode = WAPI_MODE_MANAGED;
  config.auth_wpa = IW_AUTH_WPA_VERSION_WPA2;
  config.cipher_mode = IW_AUTH_CIPHER_CCMP;
  config.alg = WPA_ALG_CCMP;
  config.ifname = CONFIG_BUFFER_VELA_WIFI_IFNAME;
  config.ssid = ssid;
  config.ssidlen = (uint8_t)strlen(ssid);
  config.bssid = "";
  config.passphrase = passphrase;
  config.phraselen = (uint8_t)strlen(passphrase);

  ret = wapi_save_config(CONFIG_BUFFER_VELA_WIFI_IFNAME, NULL, &config);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 配置保存失败：%d", ret);
      return ret;
    }

  ret = buffer_wifi_connect_saved(ssid);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 连接失败：%d（配置已保存）", ret);
    }

  return ret;
}

int buffer_wifi_reconnect(void)
{
  return buffer_wifi_connect_saved("已保存网络");
}
