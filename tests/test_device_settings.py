"""Actual boot-time reset implementation on private temporary directories."""
from pathlib import Path
import subprocess
from host_support import PROJECT_ROOT, CC


def test_reset_scope_recovery_and_failed_restart(tmp_path):
    (tmp_path / 'nuttx').mkdir()
    (tmp_path / 'sys').mkdir()
    (tmp_path / 'nuttx/config.h').write_text(
        '#define CONFIG_BOARDCTL_RESET 1\n'
        f'#define CONFIG_BUFFER_VELA_DATA_DIR "{tmp_path}/buffer"\n'
        f'#define CONFIG_WIRELESS_WAPI_CONFIG_PATH "{tmp_path}/wifi"\n')
    (tmp_path / 'sys/boardctl.h').write_text('#define BOARDIOC_RESET 1\nint boardctl(unsigned int, unsigned long);\n')
    source = r'''
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <nuttx/config.h>
#include "buffer_device_settings.h"
static unsigned reboot_calls;
int boardctl(unsigned command,unsigned long argument){assert(command==1&&!argument);reboot_calls++;errno=EIO;return -1;}
void fixture_sync(void){}
static void file(const char *path,const char *value){int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC,0600);assert(fd>=0);assert(write(fd,value,strlen(value))==(ssize_t)strlen(value));assert(!close(fd));}
int main(void){
 assert(!buffer_device_reset_apply_boot());
 assert(!mkdir(CONFIG_BUFFER_VELA_DATA_DIR,0700));
 assert(!mkdir(CONFIG_BUFFER_VELA_DATA_DIR "/records",0700));
 file(CONFIG_BUFFER_VELA_DATA_DIR "/records/keep","private record");
 assert(!mkdir(CONFIG_BUFFER_VELA_DATA_DIR "/fabric",0700));
 file(CONFIG_BUFFER_VELA_DATA_DIR "/fabric/key","pairing");file(CONFIG_BUFFER_VELA_DATA_DIR "/fabric-binding.json","binding");
 file(CONFIG_BUFFER_VELA_DATA_DIR "/oobe.completed","ready");file(CONFIG_WIRELESS_WAPI_CONFIG_PATH,"network");
 file(CONFIG_BUFFER_VELA_DATA_DIR ".reset","2\n");assert(!buffer_device_reset_apply_boot());
 assert(!access(CONFIG_BUFFER_VELA_DATA_DIR "/records/keep",F_OK));
 assert(access(CONFIG_BUFFER_VELA_DATA_DIR "/fabric",F_OK));assert(access(CONFIG_WIRELESS_WAPI_CONFIG_PATH,F_OK));
 assert(access(CONFIG_BUFFER_VELA_DATA_DIR "/oobe.completed",F_OK));assert(access(CONFIG_BUFFER_VELA_DATA_DIR ".reset",F_OK));
 for(unsigned kind=1;kind<=3;kind++){assert(buffer_device_reset_request(kind)==-EIO);assert(access(CONFIG_BUFFER_VELA_DATA_DIR ".reset",F_OK));}
 assert(reboot_calls==3&&!access(CONFIG_BUFFER_VELA_DATA_DIR "/records/keep",F_OK));
 assert(buffer_device_reset_request(0)==-EINVAL&&buffer_device_reset_request(4)==-EINVAL);
 file(CONFIG_BUFFER_VELA_DATA_DIR ".reset","3\nx");assert(buffer_device_reset_apply_boot()==-EINVAL);assert(!access(CONFIG_BUFFER_VELA_DATA_DIR "/records/keep",F_OK));
 file(CONFIG_BUFFER_VELA_DATA_DIR ".reset","3\n");assert(!chmod(CONFIG_BUFFER_VELA_DATA_DIR ".reset",0644));
 assert(buffer_device_reset_apply_boot()==-EINVAL);assert(!chmod(CONFIG_BUFFER_VELA_DATA_DIR ".reset",0600));
 file(CONFIG_BUFFER_VELA_DATA_DIR ".outside","untouched");
 assert(!symlink(CONFIG_BUFFER_VELA_DATA_DIR ".outside",CONFIG_BUFFER_VELA_DATA_DIR "/records/link"));
 assert(!mkfifo(CONFIG_BUFFER_VELA_DATA_DIR "/unexpected",0600));
 assert(buffer_device_reset_apply_boot()==-EPERM); /* Failed cleanup keeps its durable intent. */
 assert(!access(CONFIG_BUFFER_VELA_DATA_DIR ".reset",F_OK));
 assert(!unlink(CONFIG_BUFFER_VELA_DATA_DIR "/unexpected"));
 assert(!buffer_device_reset_apply_boot());assert(access(CONFIG_BUFFER_VELA_DATA_DIR,F_OK));assert(!access(CONFIG_BUFFER_VELA_DATA_DIR ".outside",F_OK));
 assert(!buffer_device_reset_apply_boot()); /* Interrupted cleanup is idempotent. */
 file(CONFIG_BUFFER_VELA_DATA_DIR ".reset","3\n");assert(!buffer_device_reset_apply_boot());
 assert(!access(CONFIG_BUFFER_VELA_DATA_DIR ".outside",F_OK));
 puts("PASS: three reset choices, retained records, factory scope, no symlink traversal, malformed markers and failed-reboot cleanup");
}
'''
    (tmp_path / 'test.c').write_text(source)
    binary = tmp_path / 'test'
    subprocess.run([*CC, '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror',
                    '-fsanitize=address,undefined', '-Dsync=fixture_sync', '-I'+str(tmp_path),
                    '-I'+str(PROJECT_ROOT / 'app/buffer_app'), str(tmp_path / 'test.c'),
                    str(PROJECT_ROOT / 'app/buffer_app/buffer_device_settings.c'), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True, timeout=10)
