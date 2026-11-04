"""Debounced HOME short/long press semantics, using the production state machine."""
from host_support import PROJECT_ROOT, CC
import subprocess
import tempfile
from pathlib import Path


def test_home_hold_release_and_boot_suppression():
    with tempfile.TemporaryDirectory(prefix="buffer-home-") as temporary:
        work=Path(temporary)
        source=work/"home.c"
        source.write_text(r'''
#include "buffer_home_key.h"
#include <assert.h>
int main(void){
 struct buffer_home_key_s s={0};
 assert(buffer_home_key_step(&s,false,0)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,10)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,40)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,639)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,640)==BUFFER_HOME_START);
 assert(buffer_home_key_step(&s,true,1640)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,1650)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,1680)==BUFFER_HOME_STOP);
 assert(buffer_home_key_step(&s,false,1700)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,1800)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,1810)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,1850)==BUFFER_HOME_NONE); /* bounce */
 assert(buffer_home_key_step(&s,true,1900)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,true,1930)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,2000)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&s,false,2030)==BUFFER_HOME_SHORT);
 struct buffer_home_key_s boot={0};
 assert(buffer_home_key_step(&boot,true,0)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,true,3000)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,false,3010)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,false,3040)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,true,3100)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,true,3130)==BUFFER_HOME_NONE);
 assert(buffer_home_key_step(&boot,true,3730)==BUFFER_HOME_START);
 return 0;
}
''')
        binary=work/"home"
        subprocess.run([*CC,"-std=c11","-Wall","-Wextra","-Werror","-I"+str(PROJECT_ROOT/"app/buffer_app"),str(source),"-o",str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
