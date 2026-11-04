"""Run the real readiness and reconnect code with a powered-down S1 radio."""
from pathlib import Path
import subprocess
from host_support import CC, PROJECT_ROOT, extract_c


def test_wifi_requires_association_and_starts_time_after_dhcp(tmp_path: Path):
    source = (PROJECT_ROOT / 'app/buffer_app/buffer_wifi.c').read_text()
    harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <syslog.h>
#include <net/if.h>
#define CONFIG_BUFFER_VELA_WIFI_IFNAME "wlan0"
#define CONFIG_WIRELESS_WAPI_CONFIG_PATH "/private/wapi.conf"
struct wpa_wconfig_s {int unused;};
static pthread_mutex_t g_wifi_connect_lock=PTHREAD_MUTEX_INITIALIZER;
static int netlib_ifdown(const char *name){(void)name;return 0;}
static void *wapi_load_config(const char *n,const char *p,struct wpa_wconfig_s *s){(void)n;(void)p;(void)s;return (void *)1;}
static void wapi_unload_config(void *p){assert(p==(void *)1);}
#define CONFIG_NETUTILS_NTPCLIENT 1
#define BUFFER_WIFI_ASSOCIATE_WAIT_SEC 30
#define BUFFER_WIFI_DHCP_ATTEMPTS 3
struct ether_addr {unsigned char ether_addr_octet[6];};
static int ap_error, sockets, closed_sockets, sleeps, dhcp, ntp, associate_error;
static int g_wifi_ntp_start=-EAGAIN;
static bool associated, interface_up=true, g_wifi_lease_ready;
static uint32_t ip;
static unsigned char first=2;
static int wapi_make_socket(void) {sockets++;return 9;}
static int close(int fd) {assert(fd==9);closed_sockets++;return 0;}
static int wapi_get_ap(int fd,const char *name,struct ether_addr *ap) {
 (void)name;assert(fd==9);
 if(ap_error)return -EIO;
 memset(ap,0,sizeof(*ap));if(associated)ap->ether_addr_octet[0]=first;return 0;
}
static int netlib_getifstatus(const char *n,uint8_t *f){(void)n;*f=interface_up?IFF_UP:0;return 0;}
static int netlib_get_ipv4addr(const char *n,struct in_addr *a){(void)n;a->s_addr=ip;return 0;}
static int buffer_wifi_ifup(const char *n){(void)n;return 0;}
static int wpa_driver_wext_associate(struct wpa_wconfig_s *s){(void)s;return associate_error;}
static void buffer_set_status(const char *f,...){(void)f;}
static int netlib_obtain_ipv4addr(const char *n){(void)n;assert(associated);dhcp++;return 0;}
static int ntpc_start(void){assert(dhcp>0);ntp++;return 1;}
static unsigned sleep(unsigned n){sleeps+=n;return 0;}
'''
    for name in ('static bool buffer_wifi_is_associated(', 'static int buffer_wifi_connect_saved_impl(', 'static int buffer_wifi_connect_saved(', 'bool buffer_wifi_is_ready('):
        harness += extract_c(source, name)
    harness += r'''
int main(void) {
 ip=htonl(0x0a000002); /* SDK default IP, radio is not even powered. */
 assert(!buffer_wifi_is_ready());
 assert(buffer_wifi_connect_saved("saved")==-ENETUNREACH);
 assert(sleeps==30 && dhcp==0 && ntp==0);
 associated=true;assert(!buffer_wifi_is_ready());
 assert(buffer_wifi_connect_saved("saved")==0 && dhcp==1 && ntp==1);
 assert(buffer_wifi_is_ready());
 ap_error=1;assert(!buffer_wifi_is_ready());ap_error=0;
 first=0xff;assert(!buffer_wifi_is_ready());first=2;
 ip=0;assert(!buffer_wifi_is_ready());ip=htonl(INADDR_LOOPBACK);assert(!buffer_wifi_is_ready());
 ip=htonl(0xc0a80164);interface_up=false;assert(!buffer_wifi_is_ready());interface_up=true;
 assert(buffer_wifi_connect_saved("saved")==0 && dhcp==2 && ntp==2);
 associate_error=-EINVAL;assert(buffer_wifi_connect_saved("missing")==-EINVAL);
 assert(sockets==closed_sockets);
 return 0;
}
'''
    cfile=tmp_path/'wifi.c';cfile.write_text(harness);exe=tmp_path/'wifi'
    subprocess.run([*CC,'-g','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(cfile),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=10)
