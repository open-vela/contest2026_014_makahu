"""Production LCD worker: UI requests, night ownership and driver failures."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC
import subprocess


def test_manual_brightness_worker(tmp_path):
    (tmp_path / 'nuttx/lcd').mkdir(parents=True)
    (tmp_path / 'nuttx/config.h').write_text('')
    (tmp_path / 'nuttx/lcd/lcd_dev.h').write_text('#define LCDDEVIO_GETPOWER 1001\n#define LCDDEVIO_SETPOWER 1002\n')
    (tmp_path / 'test.c').write_text(r'''
#include "buffer_agent.h"
#include "buffer_backlight.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <nuttx/lcd/lcd_dev.h>
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
static int hardware=255,sets,failed;
int __real_open(const char *,int,...);
int __wrap_open(const char *path,int flags,...){
 mode_t mode=0;if(flags&O_CREAT){va_list args;va_start(args,flags);mode=va_arg(args,int);va_end(args);}
 return __real_open(!strcmp(path,CONFIG_BUFFER_VELA_LCD_DEVICE)?"/dev/null":path,flags,mode);
}
int __wrap_ioctl(int fd,unsigned long command,...){
 (void)fd;va_list args;va_start(args,command);unsigned long argument=va_arg(args,unsigned long);va_end(args);
 if(failed){errno=ENOTSUP;return -1;}
 if(command==LCDDEVIO_GETPOWER){*(int *)argument=hardware;return 0;}
 assert(command==LCDDEVIO_SETPOWER);hardware=(int)argument;sets++;return 0;
}
int fixture_clock(clockid_t id,struct timespec *stamp){(void)id;static long seconds;stamp->tv_sec=++seconds;stamp->tv_nsec=0;return 0;}
cJSON *buffer_agent_parse_json(const char *raw){return cJSON_Parse(raw);}
int main(void){
 assert(!mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700));strcpy(g_buffer.mode,"normal");
 assert(buffer_backlight_request(50)==-EAGAIN);buffer_backlight_tick();
 int percent;bool pending;assert(!buffer_backlight_level(&percent,&pending)&&percent==100&&!pending);
 assert(buffer_backlight_request(0)==-ERANGE&&buffer_backlight_request(101)==-ERANGE);
 assert(!buffer_backlight_request(25)&&!buffer_backlight_request(40));assert(sets==0);
 assert(!buffer_backlight_level(&percent,&pending)&&pending);buffer_backlight_tick();assert(hardware==102&&sets==1);
 strcpy(g_buffer.mode,"bedtime");buffer_backlight_tick();assert(hardware==20);
 assert(!buffer_backlight_request(80));buffer_backlight_tick();assert(hardware==204);
 buffer_backlight_tick();assert(hardware==204);strcpy(g_buffer.mode,"normal");buffer_backlight_tick();assert(hardware==204);
 strcpy(g_buffer.mode,"bedtime");buffer_backlight_tick();assert(hardware==40);
 strcpy(g_buffer.mode,"normal");buffer_backlight_tick();assert(hardware==204);
 failed=1;buffer_backlight_tick();assert(buffer_backlight_level(&percent,&pending)==-ENOTSUP&&buffer_backlight_request(60)==-ENOTSUP);
 failed=0;buffer_backlight_tick();assert(!buffer_backlight_request(1));buffer_backlight_tick();assert(hardware==3);
 buffer_backlight_shutdown();puts("PASS: asynchronous/coalesced brightness, actual readback, night manual override, restoration and unsupported driver");
}
''')
    app = PROJECT_ROOT / 'app/buffer_app'
    binary = tmp_path / 'backlight'
    subprocess.run([*CC, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-misleading-indentation',
                    '-fsanitize=address,undefined', '-pthread', '-D__NuttX__',
                    '-DCONFIG_BUFFER_VELA_NIGHT_DIM', '-DCONFIG_LCD_MAXPOWER=255',
                    '-DCONFIG_BUFFER_VELA_NIGHT_PERCENT=20', '-Dclock_gettime=fixture_clock',
                    '-DCONFIG_BUFFER_VELA_LCD_DEVICE="/dev/test-lcd"',
                    f'-DCONFIG_BUFFER_VELA_DATA_DIR="{tmp_path}/private"',
                    '-I'+str(tmp_path), '-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),
                    '-I'+str(app), '-I'+str(SDK_ROOT/'apps/include'),
                    '-I'+str(SDK_ROOT/'apps/netutils/cjson/cJSON'),
                    str(tmp_path/'test.c'), str(app/'buffer_backlight.c'),
                    str(SDK_ROOT/'apps/netutils/cjson/cJSON/cJSON.c'),
                    '-Wl,--wrap=open', '-Wl,--wrap=ioctl', '-lm', '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)
