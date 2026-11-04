/* SPDX-License-Identifier: Apache-2.0 */
#include "lfs.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static unsigned char disk[512*4096];
static int rd(const struct lfs_config*c,lfs_block_t b,lfs_off_t o,void*p,lfs_size_t n){(void)c;memcpy(p,disk+b*4096+o,n);return 0;}
static int wr(const struct lfs_config*c,lfs_block_t b,lfs_off_t o,const void*p,lfs_size_t n){(void)c;memcpy(disk+b*4096+o,p,n);return 0;}
static int er(const struct lfs_config*c,lfs_block_t b){(void)c;memset(disk+b*4096,255,4096);return 0;}
static int sy(const struct lfs_config*c){(void)c;return 0;}
int main(void){
struct lfs_config c={.read=rd,.prog=wr,.erase=er,.sync=sy,.read_size=16,.prog_size=16,.block_size=4096,.block_count=512,.cache_size=256,.lookahead_size=64,.block_cycles=200,.name_max=96};
lfs_t fs; assert(!lfs_format(&fs,&c));assert(!lfs_mount(&fs,&c));assert(!lfs_mkdir(&fs,"buffer"));assert(!lfs_mkdir(&fs,"buffer/records"));
for(int i=0;i<100;i++){char name[128],path[256],attr[56]={0};lfs_file_t f;
snprintf(name,sizeof(name),"buffer/records/vela-%08d-61423847.input.receipt.tmp",i);
assert(!lfs_file_open(&fs,&f,name,LFS_O_CREAT|LFS_O_RDWR));assert(!lfs_setattr(&fs,name,0,attr,sizeof(attr)));assert(!lfs_file_sync(&fs,&f));
int ret=lfs_file_path(&fs,&f,path,sizeof(path));if(ret){printf("FAIL file=%d path_result=%d\n",i,ret);return 1;}assert(!strcmp(path,name));assert(lfs_file_write(&fs,&f,"ok",2)==2);assert(!lfs_file_close(&fs,&f));}
assert(!lfs_unmount(&fs));assert(!lfs_mount(&fs,&c));
for(int i=0;i<100;i++){char name[128],path[256];lfs_file_t f;
snprintf(name,sizeof(name),"buffer/records/vela-%08d-61423847.input.receipt.tmp",i);
assert(!lfs_file_open(&fs,&f,name,LFS_O_RDONLY));assert(!lfs_file_path(&fs,&f,path,sizeof(path)));assert(!strcmp(path,name));assert(!lfs_file_close(&fs,&f));}
assert(!lfs_unmount(&fs));puts("PASS split-directory file paths");return 0;}
