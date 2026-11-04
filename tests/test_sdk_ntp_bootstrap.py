"""Run the SDK NTP daemon with valid NTP replies but unavailable ICMP."""
from pathlib import Path
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_ntp_can_set_time_without_dns_server_ping(tmp_path: Path):
    source = (SDK_ROOT / 'apps/netutils/ntpclient/ntpclient.c').read_text()
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <semaphore.h>
#define FAR
#define DEBUGASSERT assert
#define CONFIG_NETUTILS_NTPCLIENT_NUM_SAMPLES 3
#define CONFIG_NETUTILS_NTPCLIENT_RETRIES 0
#define MAX_RETRY_INTERVAL 60
#define NTP_RUNNING 1
#define NTP_STOP_REQUESTED 2
#define NTP_STOPPED 3
#define ninfo(...) ((void)0)
#define nerr(...) ((void)0)
struct ntp_sample_s {int64_t offset,delay;int address;};
struct ntp_servers_s {char *hostlist_str;const char *ntp_servers;};
static struct {int state;sem_t lock,sync;} g_ntpc_daemon;
static struct ntp_sample_s g_last_samples[3];
static int g_last_nsamples,sample_requests,clock_updates,ping_requests;
static int __attribute__((unused)) netlib_check_ipconnectivity(const char *s,int timeout,int retry)
{(void)s;(void)timeout;(void)retry;ping_requests++;return 0;}
static void sched_lock(void){}
static void sched_unlock(void){}
static int ntpc_get_ntp_sample(struct ntp_servers_s *s,struct ntp_sample_s *sample,int count)
{(void)s;sample[count].offset=123;sample[count].delay=1;sample_requests++;return 0;}
static void ntpc_settime(int64_t offset,struct timespec *a,struct timespec *b)
{(void)a;(void)b;assert(offset==123);clock_updates++;}
'''
    harness += extract_c(source, 'static int sample_cmp(')
    harness += extract_c(source, 'static int ntpc_daemon(')
    harness += r'''
int main(void){
 sem_init(&g_ntpc_daemon.lock,0,1);sem_init(&g_ntpc_daemon.sync,0,0);
 char *args[]={"ntpc","0.pool.ntp.org",NULL};
 assert(ntpc_daemon(2,args)==EXIT_SUCCESS);
 assert(sample_requests==3&&clock_updates==1&&g_last_nsamples==3&&ping_requests==0);
 sem_destroy(&g_ntpc_daemon.lock);sem_destroy(&g_ntpc_daemon.sync);return 0;
}
'''
    file = tmp_path / 'ntp.c'; file.write_text(harness)
    binary = tmp_path / 'ntp'
    subprocess.run([*CC, '-Wall', '-Wextra', '-Werror', '-pthread', '-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer', str(file), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)
