"""Recipe progress survives processes, handles startup edits and failed writes."""
from pathlib import Path
import subprocess
import zlib
import struct
from host_support import PROJECT_ROOT, CC


def test_recipe_progress_file_and_worker(tmp_path):
    data=tmp_path/'private'
    binary=tmp_path/'progress'
    subprocess.run([*CC,'-std=c11','-D_DEFAULT_SOURCE','-Wall','-Wextra','-Werror','-pthread','-fsanitize=address,undefined',f'-DCONFIG_BUFFER_VELA_DATA_DIR="{data}"','-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(PROJECT_ROOT/'tests/fixtures/ui'),str(PROJECT_ROOT/'tests/fixtures/ui/recipe_state_host.c'),str(PROJECT_ROOT/'app/buffer_app/buffer_ui_recipe_state.c'),'-Wl,--wrap=write','-o',str(binary)],check=True)
    def run(mode):subprocess.run([str(binary),mode],check=True,timeout=10)
    run('save');run('load');run('metadata')
    path=data/'ui.recipes';assert path.stat().st_mode&0o777==0o600
    assert data.stat().st_mode&0o777==0o700
    run('race');run('recovered');run('failure');run('recovered')
    committed=path.read_bytes();assert len(committed)==651
    legacy=b'BRP1'+b'\0'*4+b''.join(committed[8+i*213:8+i*213+85] for i in range(3))
    legacy+=struct.pack('<I',zlib.crc32(legacy));path.write_bytes(legacy);run('recovered');path.write_bytes(committed)
    for damaged in (committed[:-1], committed+b'x', b'BAD!'+committed[4:], committed[:50]+bytes([committed[50]^1])+committed[51:]):
        path.write_bytes(damaged);run('invalid');assert path.read_bytes()==damaged
    path.unlink();path.symlink_to(tmp_path/'outside');run('invalid');assert not (tmp_path/'outside').exists()
    path.unlink();run('limits')
