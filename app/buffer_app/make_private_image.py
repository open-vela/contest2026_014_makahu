#!/usr/bin/env python3
"""Create a NEW simulator LittleFS disk; never open/format an existing disk.

Geometry matches board/vela_sim: sectors 512, logical blocks 4096,
read/program/cache 2048. Attach as a separate virtio disk, not userdata.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

SOURCE = r'''
#include "lfs.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned char *image;
static size_t image_size;
static int rd(const struct lfs_config *c,lfs_block_t b,lfs_off_t o,void *p,lfs_size_t n) {
 size_t x=(size_t)b*c->block_size+o;if(x+n>image_size)return LFS_ERR_IO;memcpy(p,image+x,n);return 0;}
static int wr(const struct lfs_config *c,lfs_block_t b,lfs_off_t o,const void *p,lfs_size_t n) {
 size_t x=(size_t)b*c->block_size+o;if(x+n>image_size)return LFS_ERR_IO;memcpy(image+x,p,n);return 0;}
static int er(const struct lfs_config *c,lfs_block_t b) {
 size_t x=(size_t)b*c->block_size;if(x+c->block_size>image_size)return LFS_ERR_IO;
 memset(image+x,255,c->block_size);return 0;}
static int sy(const struct lfs_config *c){(void)c;return 0;}
int main(int argc,char **argv) {
 if(argc!=2)return 1;image_size=strtoul(argv[1],NULL,10);image=malloc(image_size);if(!image)return 2;
 memset(image,255,image_size);
 struct lfs_config c={.read=rd,.prog=wr,.erase=er,.sync=sy,.read_size=2048,
 .prog_size=2048,.block_size=4096,.block_count=image_size/4096,.block_cycles=200,
 .cache_size=2048,.lookahead_size=2048,.name_max=96};
 lfs_t lfs;int r=lfs_format(&lfs,&c);if(r)return 3;
 if(lfs_mount(&lfs,&c))return 4;
 /* Root attributes use NuttX littlefs_attr_s ABI, owner root, mode 0700. */
 struct {uint32_t version,mode,uid,gid;uint64_t atime,mtime,ctime;} a={.mode=0700};
 if(lfs_setattr(&lfs,"/",0,&a,sizeof(a)))return 5;
 if(lfs_unmount(&lfs))return 6;
 if(fwrite(image,1,image_size,stdout)!=image_size)return 7;free(image);return 0;
}
'''

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--sdk', type=Path, default=Path(__file__).resolve().parents[3])
    p.add_argument('--size-mib', type=int, default=32)
    a = p.parse_args()
    if not 4 <= a.size_mib <= 256:
        p.error('--size-mib must be 4..256')
    lfs = a.sdk / 'nuttx/fs/littlefs/littlefs'
    # Create exclusively BEFORE generating; existing FAT/private disks are untouched.
    fd = os.open(a.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with tempfile.TemporaryDirectory(prefix='buffer-littlefs-') as directory:
            source = Path(directory) / 'mkimage.c'
            binary = Path(directory) / 'mkimage'
            source.write_text(SOURCE)
            subprocess.run(['cc', '-O2', '-DLFS_NO_DEBUG', '-DLFS_NO_WARN', '-DLFS_NO_ERROR',
                            '-I', str(lfs), str(source), str(lfs/'lfs.c'),
                            str(lfs/'lfs_util.c'), '-o', str(binary)], check=True)
            subprocess.run([str(binary), str(a.size_mib * 1024 * 1024)], stdout=fd, check=True)
            os.fsync(fd)
    except BaseException:
        os.close(fd)
        a.output.unlink()
        raise
    else:
        os.close(fd)
    print(f'Created private LittleFS disk: {a.output} ({a.size_mib} MiB); existing disks untouched')

if __name__ == '__main__':
    main()
