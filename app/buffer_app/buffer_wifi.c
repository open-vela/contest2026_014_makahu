/****************************************************************************
 * Contest 2026 team 014 - Buffer Vela Wi-Fi provisioning
 *
 * SPDX-License-Identifier: Apache-2.0
 ****************************************************************************/

#include <nuttx/config.h>

#include "buffer_app.h"
#include "buffer_enrollment.h"
#include "buffer_agent.h"

#include <errno.h>
#include <arpa/inet.h>
#include <limits.h>
#include <fcntl.h>
#include <net/if.h>
#include <netutils/netinit.h>
#include <netutils/netlib.h>
#ifdef CONFIG_NETUTILS_NTPCLIENT
#include <netutils/ntpclient.h>
#endif
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <syslog.h>
#include <time.h>
#include <unistd.h>
#include <wireless/wapi.h>

#define BUFFER_WIFI_DHCP_ATTEMPTS 3
/* Association plus the SDK static boot address is not a DHCP lease. */
static bool g_wifi_lease_ready;
static int g_wifi_ntp_start=-EAGAIN;
static pthread_mutex_t g_wifi_connect_lock=PTHREAD_MUTEX_INITIALIZER;
#define BUFFER_WIFI_ASSOCIATE_WAIT_SEC 30

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
      ret = mkdir(path, 0700);
      struct stat st;
      if ((ret < 0 && errno != EEXIST) || lstat(path, &st) < 0 ||
          !S_ISDIR(st.st_mode) || (st.st_mode & 0777) != 0700)
        {
          path[index] = '/';
          return -EACCES;
        }
      path[index] = '/';
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

/* An initialized IP interface can still have no powered/associated radio.
 * In particular S1 starts with the SDK's static 10.0.0.2 address. */
static bool buffer_wifi_is_associated(void)
{
#ifdef CONFIG_ARCH_CHIP_GOLDFISH_ARM64
  if (!strcmp(CONFIG_BUFFER_VELA_WIFI_IFNAME, "eth0")) return true;
#endif
  struct ether_addr ap;
  memset(&ap, 0, sizeof(ap));
  int sock = wapi_make_socket();
  if (sock < 0) return false;
  int ret = wapi_get_ap(sock, CONFIG_BUFFER_VELA_WIFI_IFNAME, &ap);
  close(sock);
  if (ret < 0 || (ap.ether_addr_octet[0] & 1)) return false;
  unsigned char any = 0;
  for (unsigned i = 0; i < sizeof(ap.ether_addr_octet); i++)
    any |= ap.ether_addr_octet[i];
  return any != 0;
}

static int buffer_wifi_connect_saved_impl(const char *network_label)
{
  int ret;
  int attempt;

  __atomic_store_n(&g_wifi_lease_ready, false, __ATOMIC_RELEASE);
  /* IFF_UP does not power this radio. Reset a stale cached STA mode before
   * associating; realtek_ifup resets its mode only after a genuine ifdown. */
  ret = netlib_ifdown(CONFIG_BUFFER_VELA_WIFI_IFNAME);
  if (ret < 0) return errno ? -errno : -EIO;
  ret = buffer_wifi_ifup(CONFIG_BUFFER_VELA_WIFI_IFNAME);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 接口启动失败：%d", ret);
      return ret;
    }

  struct wpa_wconfig_s saved={0};
  void *profile=wapi_load_config(CONFIG_BUFFER_VELA_WIFI_IFNAME,
                                CONFIG_WIRELESS_WAPI_CONFIG_PATH,&saved);
  if(!profile){buffer_set_status("未能读取已保存的 Wi-Fi 配置，请重新配网");return -ENOENT;}
  /* Do not fall back to the SDK's compiled sample SSID/password. */
  ret = wpa_driver_wext_associate(&saved);
  wapi_unload_config(profile);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 关联失败：%d", ret);
      return ret;
    }

  syslog(LOG_WARNING,"Buffer Wi-Fi: association requested; awaiting AP\n");
  for (attempt = 0; attempt < BUFFER_WIFI_ASSOCIATE_WAIT_SEC; attempt++)
    {
      if (buffer_wifi_is_associated()) break;
      sleep(1);
    }
  if (!buffer_wifi_is_associated())
    {
      buffer_set_status("Wi-Fi 尚未关联，请检查网络名称与密码");
      return -ENETUNREACH;
    }
  for (attempt = 0; attempt < BUFFER_WIFI_DHCP_ATTEMPTS; attempt++)
    {
      syslog(LOG_WARNING,"Buffer Wi-Fi: AP associated; requesting DHCP\n");
      ret = netlib_obtain_ipv4addr(CONFIG_BUFFER_VELA_WIFI_IFNAME);
      if (ret == 0)
        {
          __atomic_store_n(&g_wifi_lease_ready, true, __ATOMIC_RELEASE);
#ifdef CONFIG_NETUTILS_NTPCLIENT
          /* Start synchronization only after DHCP supplied routing and DNS.
           * ntpc_start is idempotent when the daemon is already running. */
          int ntp=ntpc_start();
          __atomic_store_n(&g_wifi_ntp_start,ntp,__ATOMIC_RELEASE);
          syslog(LOG_WARNING,"Buffer Wi-Fi: DHCP complete, NTP start=%d\n",ntp);
#endif
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

static int buffer_wifi_connect_saved(const char *label)
{
 pthread_mutex_lock(&g_wifi_connect_lock);
 int result=buffer_wifi_connect_saved_impl(label);
 pthread_mutex_unlock(&g_wifi_connect_lock);
 return result;
}
void buffer_wifi_status(void)
{
 uint8_t flags=0;struct in_addr address={0};char ip[INET_ADDRSTRLEN];
 int link=netlib_getifstatus(CONFIG_BUFFER_VELA_WIFI_IFNAME,&flags);
 int addr=netlib_get_ipv4addr(CONFIG_BUFFER_VELA_WIFI_IFNAME,&address);
 bool lease=__atomic_load_n(&g_wifi_lease_ready,__ATOMIC_ACQUIRE);
 printf("wifi if_status=%d up=%d addr_status=%d ip=%s lease=%d associated=%d\n",
  link,!!(flags&IFF_UP),addr,inet_ntop(AF_INET,&address,ip,sizeof(ip))?ip:"unknown",
  lease,lease&&buffer_wifi_is_associated());
 struct stat st;int profile=lstat(CONFIG_WIRELESS_WAPI_CONFIG_PATH,&st);
 printf("wifi profile_present=%d clock_valid=%d utc_epoch=%lld\n",profile==0,
        time(NULL)>=1704067200,(long long)time(NULL));
#ifdef CONFIG_NETUTILS_NTPCLIENT
 struct ntpc_status_s ntp_status;int ntp_result=ntpc_status(&ntp_status);
 printf("wifi ntp_start=%d ntp_status=%d samples=%u\n",
  __atomic_load_n(&g_wifi_ntp_start,__ATOMIC_ACQUIRE),ntp_result,ntp_result<0?0:(unsigned)ntp_status.nsamples);
#endif
}

int buffer_wifi_configure(const char *ssid, const char *passphrase)
{
  struct wpa_wconfig_s config;
  int ret;

#ifdef CONFIG_ARCH_CHIP_GOLDFISH_ARM64
  /* The simulator's virtio Ethernet is real networking, not a Wi-Fi radio.
   * Never acknowledge an SSID/password as applied to Ethernet. */
  if (!strcmp(CONFIG_BUFFER_VELA_WIFI_IFNAME, "eth0")) return -ENOTSUP;
#endif

  ret = buffer_wifi_credentials_valid(ssid, passphrase);
  if (ret < 0)
    {
      buffer_set_status("Wi-Fi 名称或密码格式无效（64 位密钥必须为十六进制）");
      return ret;
    }

  ret = buffer_agent_store_init();
  if (!ret) ret = buffer_wifi_prepare_config_path();
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

  /* SDK wapi_save_config opens with0644 and truncates. Precreate a private
   * temporary inode, verify actual mode BEFORE passing it any credentials,
   * then atomically replace the saved profile only after durable success. */
  const char *temporary = CONFIG_WIRELESS_WAPI_CONFIG_PATH ".buffer-part";
  struct stat st;
  if (!lstat(temporary, &st))
    {
      if (!S_ISREG(st.st_mode) || (st.st_mode & 0777) != 0600) return -EACCES;
      if (unlink(temporary)) return -errno;
    }
  else if (errno != ENOENT) return -errno;
  int fd = open(temporary, O_CREAT | O_EXCL | O_RDWR, 0600);
  if (fd < 0) return -errno;
  ret = fchmod(fd, 0600) || fstat(fd, &st) ||
        !S_ISREG(st.st_mode) || (st.st_mode & 0777) != 0600 ? -EACCES : 0;
  close(fd);
  if (!ret) ret = wapi_save_config(CONFIG_BUFFER_VELA_WIFI_IFNAME, temporary, &config);
  if (!ret)
    {
      fd = open(temporary, O_RDONLY);
      if (fd < 0) ret = -errno;
      else { if (fsync(fd)) ret = -errno; close(fd); }
    }
  if (!ret && rename(temporary, CONFIG_WIRELESS_WAPI_CONFIG_PATH)) ret = -errno;
  if (ret < 0) unlink(temporary);
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
#ifdef CONFIG_ARCH_CHIP_GOLDFISH_ARM64
  if (!strcmp(CONFIG_BUFFER_VELA_WIFI_IFNAME, "eth0"))
    {
      /* Match the board's configured Ethernet addressing. This initializes
       * actual virtio networking, without claiming Wi-Fi association. */
      struct in_addr address;
      address.s_addr = htonl(CONFIG_NETINIT_IPADDR);
      if (netlib_set_ipv4addr("eth0", &address) < 0) return -errno;
      address.s_addr = htonl(CONFIG_NETINIT_NETMASK);
      if (netlib_set_ipv4netmask("eth0", &address) < 0) return -errno;
      address.s_addr = htonl(CONFIG_NETINIT_DRIPADDR);
      if (netlib_set_dripv4addr("eth0", &address) < 0) return -errno;
#ifdef CONFIG_NETINIT_DNS
      address.s_addr = htonl(CONFIG_NETINIT_DNSIPADDR);
      if (netlib_set_ipv4dnsaddr(&address) < 0) return -errno;
#endif
      return buffer_wifi_ifup("eth0");
    }
#endif
  return buffer_wifi_connect_saved("已保存网络");
}

bool buffer_wifi_is_ready(void)
{
  struct in_addr address;
  uint8_t flags = 0;

  if (netlib_getifstatus(CONFIG_BUFFER_VELA_WIFI_IFNAME, &flags) < 0 ||
      !(flags & IFF_UP)) return false;

  memset(&address, 0, sizeof(address));
  if (netlib_get_ipv4addr(CONFIG_BUFFER_VELA_WIFI_IFNAME, &address) < 0)
    {
      return false;
    }

  if(address.s_addr==htonl(INADDR_ANY)||address.s_addr==htonl(INADDR_LOOPBACK))return false;
#ifdef CONFIG_ARCH_CHIP_GOLDFISH_ARM64
  if(!strcmp(CONFIG_BUFFER_VELA_WIFI_IFNAME,"eth0"))return true;
#endif
  return __atomic_load_n(&g_wifi_lease_ready, __ATOMIC_ACQUIRE) && buffer_wifi_is_associated();
}
