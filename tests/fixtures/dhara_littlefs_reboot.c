/* SPDX-License-Identifier: Apache-2.0 */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "dhara/map.h"
#include "sim.h"
#include "lfs.h"

static struct dhara_map map;
static unsigned char page[512];

static int read_page(const struct lfs_config *c, lfs_block_t block,
                     lfs_off_t off, void *data, lfs_size_t size)
{
  (void)c;
  assert(off == 0 && size == sizeof(page));
  return dhara_map_read(&map, block, data, NULL);
}

static int write_page(const struct lfs_config *c, lfs_block_t block,
                      lfs_off_t off, const void *data, lfs_size_t size)
{
  (void)c;
  assert(off == 0 && size == sizeof(page));
  return dhara_map_write(&map, block, data, NULL);
}

static int erase_page(const struct lfs_config *c, lfs_block_t block)
{
  (void)c;
  (void)block;
  return 0; /* Matches the NuttX block-device LittleFS erase callback. */
}

static int sync_pages(const struct lfs_config *c)
{
  (void)c;
  return dhara_map_sync(&map, NULL);
}

int main(void)
{
  struct lfs_config cfg = {
    .read = read_page, .prog = write_page, .erase = erase_page,
    .sync = sync_pages, .read_size = 512, .prog_size = 512,
    .block_size = 512, .cache_size = 512, .lookahead_size = 16,
    .block_cycles = 20,
  };
  lfs_t fs;
  lfs_file_t file;
  unsigned char expected[512], actual[512];
  sim_reset();
  dhara_map_init(&map, &sim_nand, page, 4);
  const dhara_sector_t nominal = dhara_map_capacity(&map);
  cfg.block_count = nominal;
  assert(lfs_format(&fs, &cfg) == 0);
  assert(lfs_mount(&fs, &cfg) == 0);
  assert(lfs_file_open(&fs, &file, "retained", LFS_O_CREAT | LFS_O_RDWR) == 0);
  for (unsigned int i = 0; i < 1000; i++) {
    memset(expected, i & 255, sizeof(expected));
    assert(lfs_file_rewind(&fs, &file) == 0);
    assert(lfs_file_write(&fs, &file, expected, sizeof(expected)) == sizeof(expected));
    assert(lfs_file_sync(&fs, &file) == 0);
  }
  assert(lfs_file_close(&fs, &file) == 0);
  assert(lfs_unmount(&fs) == 0);

  dhara_map_init(&map, &sim_nand, page, 4);
  const dhara_sector_t stable = dhara_map_capacity(&map);
  assert(dhara_map_resume(&map, NULL) == 0);
  const dhara_sector_t learned = dhara_map_capacity(&map);
  assert(learned > nominal);
  cfg.block_count = learned;
  assert(lfs_mount(&fs, &cfg) == LFS_ERR_INVAL);
  cfg.block_count = stable;
  assert(lfs_mount(&fs, &cfg) == 0);
  assert(lfs_file_open(&fs, &file, "retained", LFS_O_RDONLY) == 0);
  assert(lfs_file_read(&fs, &file, actual, sizeof(actual)) == sizeof(actual));
  assert(memcmp(expected, actual, sizeof(actual)) == 0);
  assert(lfs_file_close(&fs, &file) == 0);
  assert(lfs_unmount(&fs) == 0);
  printf("reboot: learned capacity %u, fixed geometry %u; file preserved\n",
         (unsigned)learned, (unsigned)stable);
  return 0;
}
