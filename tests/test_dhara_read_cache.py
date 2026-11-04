"""Exercise actual Dhara cache/read/program/erase code with counted NAND I/O.

The working set is synthetic; this verifies cache behavior and coherence,
not the application's measured hit rate or the physical NAND timing.
"""
import json
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_dhara_read_cache_preserves_writes_erase_ecc_and_bounds_io(tmp_path):
    source=(SDK_ROOT/'nuttx/drivers/mtd/dhara.c').read_text()
    queue=(SDK_ROOT/'nuttx/include/nuttx/queue.h').read_text()
    (tmp_path/'queue.h').write_text(queue)
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define FAR
#define CONFIG_LIBC_INLINE_QUEUE 1
#define STATIC_INLINE static inline
#include "queue.h"
#define PAGE_SIZE 2048
#define DHARA_PAGE_NONE UINT32_MAX
#define DHARA_E_ECC 1
#define DHARA_E_BAD_BLOCK 2
#define kmm_malloc malloc
#define kmm_free free
typedef uint32_t dhara_page_t;
typedef unsigned dhara_block_t;
typedef int dhara_error_t;
struct dhara_nand {unsigned log2_page_size,log2_ppb;};
struct mtd_dev_s {unsigned unused;};
static unsigned reads;
static int fail_read;
static uint8_t media[32][PAGE_SIZE];
static int nand_read(struct mtd_dev_s *mtd,unsigned page,unsigned count,uint8_t *out){
 (void)mtd;assert(count==1&&page<32);reads++;if(fail_read==-EIO)return -EIO;
 memcpy(out,media[page],PAGE_SIZE);return fail_read==-EUCLEAN?-EUCLEAN:1;
}
static int nand_write(struct mtd_dev_s *mtd,unsigned page,unsigned count,const uint8_t *in){
 (void)mtd;assert(count==1&&page<32);memcpy(media[page],in,PAGE_SIZE);return 1;
}
static int nand_erase(struct mtd_dev_s *mtd,unsigned block,unsigned count){
 (void)mtd;assert(count==1&&block<8);memset(media[block*4],255,4*PAGE_SIZE);return 0;
}
#define MTD_BREAD nand_read
#define MTD_BWRITE nand_write
#define MTD_ERASE nand_erase
static void dhara_set_error(dhara_error_t *error,int code){if(error)*error=code;}
'''
    harness+=extract_c(source,'struct dhara_pagecache_s',semicolon=True)+'\n'
    harness+='''typedef struct dhara_pagecache_s dhara_pagecache_t;
struct dhara_dev_s {
 struct dhara_nand nand;
 struct mtd_dev_s *mtd;
 struct {unsigned blocksize;} geo;
 uint16_t blkper;
 dq_queue_t readcache;
 dhara_pagecache_t readpage[CONFIG_DHARA_READ_NCACHES];
};
typedef struct dhara_dev_s dhara_dev_t;
'''
    for sig in ['static int dhara_init_readcache(', 'static void dhara_deinit_readcache(',
                'static uint8_t *dhara_find_readcache(', 'static dhara_pagecache_t *dhara_grab_readcache(',
                'static void dhara_insert_readcache(', 'static void dhara_discard_readcache(',
                'static void dhara_update_readcache(', 'int dhara_nand_read(',
                'int dhara_nand_prog(', 'int dhara_nand_erase(']:
        harness+=extract_c(source,sig)+'\n'
    harness+=r'''
int main(void){
 struct mtd_dev_s mtd={0};dhara_dev_t dev={.nand={11,2},.mtd=&mtd,.geo={PAGE_SIZE},.blkper=4};
 for(unsigned page=0;page<32;page++)memset(media[page],page,PAGE_SIZE);
 assert(!dhara_init_readcache(&dev));uint8_t data[PAGE_SIZE];dhara_error_t error=0;
 for(unsigned round=0;round<100;round++)for(unsigned page=0;page<12;page++){
  assert(!dhara_nand_read(&dev.nand,page,17,31,data,&error));
  for(unsigned i=0;i<31;i++)assert(data[i]==page);
 }
 unsigned working_set_reads=reads;
 assert(working_set_reads==(CONFIG_DHARA_READ_NCACHES>=12?12:1200));
 memset(data,99,sizeof(data));assert(!dhara_nand_prog(&dev.nand,11,data,&error));
 unsigned before=reads;assert(!dhara_nand_read(&dev.nand,11,0,sizeof(data),data,&error));
 assert(reads==before);for(unsigned i=0;i<sizeof(data);i++)assert(data[i]==99);
 assert(!dhara_nand_erase(&dev.nand,2,&error));
 before=reads;assert(!dhara_nand_read(&dev.nand,11,0,sizeof(data),data,&error));assert(reads==before+1);
 for(unsigned i=0;i<sizeof(data);i++)assert(data[i]==255);
 fail_read=-EIO;assert(dhara_nand_read(&dev.nand,30,0,1,data,&error)==-EIO);
 fail_read=-EUCLEAN;before=reads;assert(!dhara_nand_read(&dev.nand,30,0,1,data,&error));assert(reads==before+1&&data[0]==30);
 fail_read=0;before=reads;assert(!dhara_nand_read(&dev.nand,30,0,1,data,&error));assert(reads==before);
 printf("{\"cache_pages\":%u,\"cache_bytes\":%u,\"working_set_reads\":%u,\"requests\":1200}\n",CONFIG_DHARA_READ_NCACHES,CONFIG_DHARA_READ_NCACHES*PAGE_SIZE,working_set_reads);
 dhara_deinit_readcache(&dev);return 0;
}
'''
    code=tmp_path/'cache.c';code.write_text(harness)
    results=[]
    for pages in [4,16]:
        exe=tmp_path/f'cache-{pages}'
        subprocess.run([*CC,'-std=c11','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
                        '-fno-omit-frame-pointer',f'-DCONFIG_DHARA_READ_NCACHES={pages}',
                        '-I'+str(tmp_path),str(code),'-o',str(exe)],check=True)
        results.append(json.loads(subprocess.check_output([str(exe)],text=True,timeout=5)))
    print(json.dumps(results))
