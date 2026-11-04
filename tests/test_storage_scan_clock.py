"""Slow storage must not consume the rescan backoff or move protocol clocks."""
import subprocess
from host_support import PROJECT_ROOT, CC


def test_scan_backoff_begins_after_storage_and_handles_clock_edges(tmp_path):
    source=tmp_path/'clock.c'
    source.write_text(r'''
#include <assert.h>
#include <stdint.h>
#include <time.h>
static int failure;
static uint64_t stamp;
static int scan_test_clock(clockid_t id,struct timespec *out){
 assert(id==CLOCK_MONOTONIC);if(failure)return -1;
 out->tv_sec=stamp/1000;out->tv_nsec=(stamp%1000)*1000000;return 0;
}
#define clock_gettime scan_test_clock
#include "buffer_scan_clock.h"
#undef clock_gettime
int main(void){
 stamp=500000;uint64_t began=buffer_scan_clock_ms();
 stamp+=30000;assert(buffer_scan_retry_after(1000,began,2000)==33000);
 assert(buffer_scan_retry_after(1000,stamp,1000)==2000);
 stamp=499000;assert(buffer_scan_retry_after(1000,began,1000)==2000);
 failure=1;assert(buffer_scan_retry_after(1000,began,1000)==2000);
 failure=0;stamp=530000;
 assert(buffer_scan_retry_after(UINT64_MAX-1,began,1000)==UINT64_MAX);
 assert(buffer_scan_retry_after(UINT64_MAX-500,stamp,1000)==UINT64_MAX);
 return 0;
}
''')
    exe=tmp_path/'clock'
    subprocess.run([*CC,'-D_POSIX_C_SOURCE=200809L','-std=c11','-Wall','-Wextra','-Werror',
                    '-I'+str(PROJECT_ROOT/'app/buffer_app'),str(source),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)
