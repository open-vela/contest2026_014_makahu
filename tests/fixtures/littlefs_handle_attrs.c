/* SPDX-License-Identifier: Apache-2.0 */
#include "lfs.h"

#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define BLOCK_SIZE 512
#define BLOCK_COUNT 128
#define CACHE_SIZE 64
#define LOOKAHEAD_SIZE 16
#define RECORD_COUNT 48
#define ATTR_TYPE 0

static uint8_t disk[BLOCK_SIZE * BLOCK_COUNT];
static uint8_t read_cache[CACHE_SIZE];
static uint8_t prog_cache[CACHE_SIZE];
static uint8_t lookahead[LOOKAHEAD_SIZE];
static uint8_t file_cache[CACHE_SIZE];
static pthread_mutex_t device_lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned lock_calls;
static unsigned unlock_calls;

struct record_attr
{
  uint32_t sequence;
  uint8_t marker[12];
};

static int mem_read(const struct lfs_config *cfg, lfs_block_t block,
                    lfs_off_t offset, void *buffer, lfs_size_t size)
{
  (void)cfg;
  assert(block < BLOCK_COUNT && offset + size <= BLOCK_SIZE);
  memcpy(buffer, &disk[block * BLOCK_SIZE + offset], size);
  return 0;
}

static int mem_prog(const struct lfs_config *cfg, lfs_block_t block,
                    lfs_off_t offset, const void *buffer, lfs_size_t size)
{
  (void)cfg;
  assert(block < BLOCK_COUNT && offset + size <= BLOCK_SIZE);
  memcpy(&disk[block * BLOCK_SIZE + offset], buffer, size);
  return 0;
}

static int mem_erase(const struct lfs_config *cfg, lfs_block_t block)
{
  (void)cfg;
  assert(block < BLOCK_COUNT);
  memset(&disk[block * BLOCK_SIZE], 0xff, BLOCK_SIZE);
  return 0;
}

static int mem_sync(const struct lfs_config *cfg)
{
  (void)cfg;
  return 0;
}

static int mem_lock(const struct lfs_config *cfg)
{
  (void)cfg;
  if (pthread_mutex_lock(&device_lock) != 0)
    {
      return LFS_ERR_IO;
    }

  lock_calls++;
  return 0;
}

static int mem_unlock(const struct lfs_config *cfg)
{
  (void)cfg;
  unlock_calls++;
  return pthread_mutex_unlock(&device_lock) == 0 ? 0 : LFS_ERR_IO;
}

static void record_path(char *path, size_t size, unsigned sequence)
{
  int written = snprintf(path, size,
                         "buffer/records/capture-%03u-0123456789abcdef.input.receipt.tmp",
                         sequence);
  assert(written > 0 && (size_t)written < size);
}

static void renamed_path(char *path, size_t size)
{
  int written = snprintf(path, size,
                         "buffer/records/capture-renamed-0123456789abcdef.input.receipt.tmp");
  assert(written > 0 && (size_t)written < size);
}

static void make_attr(struct record_attr *attr, unsigned sequence)
{
  attr->sequence = sequence;
  memset(attr->marker, (int)(sequence ^ 0xa5), sizeof(attr->marker));
}

static void check_attr(lfs_t *fs, lfs_file_t *file,
                       const struct record_attr *expected)
{
  struct record_attr actual;
  unsigned locks_before = lock_calls;
  unsigned unlocks_before = unlock_calls;
  lfs_ssize_t size = lfs_file_getattr(fs, file, ATTR_TYPE, &actual,
                                      sizeof(actual));
  assert(size == (lfs_ssize_t)sizeof(actual));
  assert(lock_calls == locks_before + 1);
  assert(unlock_calls == unlocks_before + 1);
  assert(memcmp(&actual, expected, sizeof(actual)) == 0);
}

int main(void)
{
  struct lfs_config cfg =
    {
      .read = mem_read,
      .prog = mem_prog,
      .erase = mem_erase,
      .sync = mem_sync,
      .lock = mem_lock,
      .unlock = mem_unlock,
      .read_size = 16,
      .prog_size = 16,
      .block_size = BLOCK_SIZE,
      .block_count = BLOCK_COUNT,
      .cache_size = CACHE_SIZE,
      .lookahead_size = LOOKAHEAD_SIZE,
      .block_cycles = 32,
      .name_max = 96,
      .attr_max = 32,
      .read_buffer = read_cache,
      .prog_buffer = prog_cache,
      .lookahead_buffer = lookahead
    };
  struct lfs_file_config file_cfg = {.buffer = file_cache};
  lfs_t fs;

  assert(lfs_format(&fs, &cfg) == 0);
  assert(lfs_mount(&fs, &cfg) == 0);
  assert(lfs_mkdir(&fs, "buffer") == 0);
  assert(lfs_mkdir(&fs, "buffer/records") == 0);

  for (unsigned i = 0; i < RECORD_COUNT; i++)
    {
      char path[128];
      struct record_attr attr;
      lfs_file_t file;
      record_path(path, sizeof(path), i);
      assert(lfs_file_opencfg(&fs, &file, path,
                              LFS_O_CREAT | LFS_O_RDWR, &file_cfg) == 0);

      struct record_attr missing;
      memset(&missing, 0xff, sizeof(missing));
      unsigned locks_before = lock_calls;
      unsigned unlocks_before = unlock_calls;
      assert(lfs_file_getattr(&fs, &file, ATTR_TYPE, &missing,
                              sizeof(missing)) == LFS_ERR_NOATTR);
      assert(lock_calls == locks_before + 1);
      assert(unlock_calls == unlocks_before + 1);
      const uint8_t *bytes = (const uint8_t *)&missing;
      for (size_t j = 0; j < sizeof(missing); j++)
        {
          assert(bytes[j] == 0);
        }

      make_attr(&attr, i);
      locks_before = lock_calls;
      unlocks_before = unlock_calls;
      assert(lfs_file_setattr(&fs, &file, ATTR_TYPE, &attr,
                              sizeof(attr)) == 0);
      assert(lock_calls == locks_before + 1);
      assert(unlock_calls == unlocks_before + 1);
      check_attr(&fs, &file, &attr);
      assert(lfs_file_close(&fs, &file) == 0);
    }

  char first_path[128];
  char tail_path[128];
  lfs_file_t first;
  lfs_file_t tail;
  record_path(first_path, sizeof(first_path), 0);
  record_path(tail_path, sizeof(tail_path), RECORD_COUNT - 1);
  assert(lfs_file_opencfg(&fs, &first, first_path, LFS_O_RDONLY,
                          &file_cfg) == 0);
  assert(lfs_file_opencfg(&fs, &tail, tail_path, LFS_O_RDONLY,
                          &file_cfg) == 0);
  assert(memcmp(first.m.pair, tail.m.pair, sizeof(first.m.pair)) != 0);
  assert(first.m.split);

  char actual_path[160];
  assert(lfs_file_path(&fs, &tail, actual_path, sizeof(actual_path)) == 0);
  assert(strcmp(actual_path, tail_path) == 0);
  assert(lfs_file_close(&fs, &first) == 0);
  assert(lfs_file_close(&fs, &tail) == 0);

  unsigned rename_index = RECORD_COUNT - 1;
  record_path(tail_path, sizeof(tail_path), rename_index);
  char moved_path[128];
  renamed_path(moved_path, sizeof(moved_path));
  struct record_attr attr;
  lfs_file_t rename_source;
  assert(lfs_file_opencfg(&fs, &rename_source, tail_path, LFS_O_RDWR,
                          &file_cfg) == 0);
  make_attr(&attr, rename_index);
  check_attr(&fs, &rename_source, &attr);
  assert(lfs_file_close(&fs, &rename_source) == 0);
  assert(lfs_rename(&fs, tail_path, moved_path) == 0);
  lfs_file_t renamed;
  assert(lfs_file_opencfg(&fs, &renamed, moved_path, LFS_O_RDWR,
                          &file_cfg) == 0);
  assert(lfs_file_path(&fs, &renamed, actual_path, sizeof(actual_path)) == 0);
  assert(strcmp(actual_path, moved_path) == 0);
  make_attr(&attr, rename_index);
  check_attr(&fs, &renamed, &attr);

  make_attr(&attr, 0x1234);
  unsigned locks_before = lock_calls;
  unsigned unlocks_before = unlock_calls;
  assert(lfs_file_setattr(&fs, &renamed, ATTR_TYPE, &attr,
                          sizeof(attr)) == 0);
  assert(lock_calls == locks_before + 1);
  assert(unlock_calls == unlocks_before + 1);
  check_attr(&fs, &renamed, &attr);

  uint8_t too_large[33] = {0};
  locks_before = lock_calls;
  unlocks_before = unlock_calls;
  assert(lfs_file_setattr(&fs, &renamed, ATTR_TYPE, too_large,
                          sizeof(too_large)) == LFS_ERR_NOSPC);
  assert(lock_calls == locks_before + 1);
  assert(unlock_calls == unlocks_before + 1);
  assert(lfs_file_close(&fs, &renamed) == 0);

  unsigned remove_index = RECORD_COUNT / 2;
  char removed_path[128];
  record_path(removed_path, sizeof(removed_path), remove_index);
  lfs_file_t removed;
  assert(lfs_file_opencfg(&fs, &removed, removed_path, LFS_O_RDONLY,
                          &file_cfg) == 0);
  assert(lfs_remove(&fs, removed_path) == 0);
  assert(lfs_file_close(&fs, &removed) == 0);
  struct lfs_info info;
  assert(lfs_stat(&fs, removed_path, &info) == LFS_ERR_NOENT);
  assert(lfs_unmount(&fs) == 0);

  assert(lfs_mount(&fs, &cfg) == 0);
  for (unsigned i = 0; i < RECORD_COUNT; i++)
    {
      char path[128];
      struct record_attr expected;
      lfs_file_t file;
      if (i == remove_index)
        {
          continue;
        }

      if (i == rename_index)
        {
          memcpy(path, moved_path, strlen(moved_path) + 1);
        }
      else
        {
          record_path(path, sizeof(path), i);
        }

      assert(lfs_file_opencfg(&fs, &file, path, LFS_O_RDONLY,
                              &file_cfg) == 0);
      assert(lfs_file_path(&fs, &file, actual_path, sizeof(actual_path)) == 0);
      assert(strcmp(actual_path, path) == 0);
      if (i == rename_index)
        {
          make_attr(&expected, 0x1234);
        }
      else
        {
          make_attr(&expected, i);
        }

      check_attr(&fs, &file, &expected);
      assert(lfs_file_close(&fs, &file) == 0);
    }

  assert(lfs_stat(&fs, removed_path, &info) == LFS_ERR_NOENT);
  assert(lfs_unmount(&fs) == 0);
  assert(pthread_mutex_destroy(&device_lock) == 0);
  puts("PASS LittleFS handle attributes across split, rename, unlink, remount");
  return 0;
}
