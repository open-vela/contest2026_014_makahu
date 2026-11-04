/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_private_storage.h"
#include "buffer_app.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>

#ifdef CONFIG_BUFFER_VELA_S1_PRIVATE_STORAGE
#include <nuttx/fs/fs.h>
#include <nuttx/mtd/mtd.h>
#include <sys/mount.h>
#include <sys/stat.h>

extern const struct mountpt_operations g_littlefs_operations;

#if !defined(CONFIG_MTD_DHARA) || !defined(CONFIG_FS_LITTLEFS) || !defined(CONFIG_FS_LITTLEFS_ATTR_UPDATE)
#error S1 private storage requires Dhara and LittleFS file attributes
#endif
#if CONFIG_FS_LITTLEFS_PROGRAM_SIZE_FACTOR != 1 || CONFIG_FS_LITTLEFS_READ_SIZE_FACTOR != 1 || CONFIG_FS_LITTLEFS_CACHE_SIZE_FACTOR != 1 || CONFIG_FS_LITTLEFS_BLOCK_SIZE_FACTOR != 1
#error S1 Dhara logical page geometry requires LittleFS size factors of one
#endif

#define PRIVATE_MTD "/dev/bufpriv"
#define PRIVATE_BLOCK "/dev/buffer-private"
#define PRIVATE_MOUNT "/private"
#define PRIVATE_BYTES (64u * 1024u * 1024u)
static pthread_mutex_t storage_lock=PTHREAD_MUTEX_INITIALIZER;
/* Dhara borrows its MTD pointer. Retain this inode reference for the lifetime
 * of the registered map; app stop does not unmount or reset the journal. */
static struct inode *private_inode;
static bool registered,mounted;

static int private_geometry(struct inode *inode,struct mtd_geometry_s *geometry)
{
  struct mtd_dev_s *mtd=inode->u.i_mtd;
  if(!mtd||!mtd->erase||!mtd->bread||!mtd->bwrite||!mtd->isbad||!mtd->markbad)return -ENOTSUP;
  memset(geometry,0,sizeof(*geometry));
  int ret=MTD_IOCTL(mtd,MTDIOC_GEOMETRY,(unsigned long)((uintptr_t)geometry));if(ret<0)return ret;
  if(geometry->blocksize<512||geometry->blocksize>65536||
     (geometry->blocksize&(geometry->blocksize-1))||geometry->erasesize<geometry->blocksize||
     geometry->erasesize%geometry->blocksize||!geometry->neraseblocks||
     (uint64_t)geometry->erasesize*geometry->neraseblocks!=PRIVATE_BYTES)return -EINVAL;
  struct partition_info_s info;
  memset(&info,0,sizeof(info));
  ret=MTD_IOCTL(mtd,BIOC_PARTINFO,(unsigned long)((uintptr_t)&info));if(ret<0)return ret;
  /* Offset is relative to nand_phy's physical region, in page sectors.
   * Pin the factory layout as well as capacity: a different 64 MiB alias
   * must never qualify for provisioning. */
  if(info.sectorsize!=geometry->blocksize||
     (uint64_t)info.startsector*info.sectorsize!=82432ULL*512||
     info.startsector%(geometry->erasesize/geometry->blocksize)||
     (uint64_t)info.numsectors*info.sectorsize!=PRIVATE_BYTES||strcmp(info.parent,"nand_phy"))return -EINVAL;
  return 0;
}
static int private_map_locked(bool allow_blank)
{
  if(registered)return 0;
  struct inode *inode=NULL;struct mtd_geometry_s geometry;
  int ret=find_mtddriver(PRIVATE_MTD,&inode);if(ret<0)return ret;
  ret=private_geometry(inode,&geometry);
  if(!ret)ret=dhara_initialize_by_path_ex(PRIVATE_BLOCK,inode->u.i_mtd,allow_blank);
  if(ret<0){close_mtddriver(inode);return ret;}
  private_inode=inode;registered=true;return 0;
}
static int private_mount_permissions(void)
{
  struct stat st;
  if (lstat(PRIVATE_MOUNT, &st)) return -errno;
  if (!S_ISDIR(st.st_mode)) return -ENODEV;
  if ((st.st_mode & 0777) != 0700 && chmod(PRIVATE_MOUNT, 0700))
    return -errno;
  if (lstat(PRIVATE_MOUNT, &st)) return -errno;
  return (st.st_mode & 0777) == 0700 ? 0 : -EACCES;
}
/* Check the current mount, without statfs() walking all LittleFS blocks. */
static int private_mount_backend(void)
{
  struct file directory;
  int ret=file_open(&directory,PRIVATE_MOUNT,O_RDONLY|O_DIRECTORY);
  if(ret)return ret;
  const struct fs_dirent_s *dir=directory.f_priv;
  bool expected=dir&&dir->fd_root&&
      dir->fd_root->u.i_mops==&g_littlefs_operations;
  int closed=file_close(&directory);
  if(!expected)return -ENODEV;
  return closed?closed:private_mount_permissions();
}
static int private_mount_locked(const char *options)
{
  if(strcmp(CONFIG_BUFFER_VELA_DATA_DIR,PRIVATE_MOUNT "/buffer"))return -EINVAL;
  if(mounted)return private_mount_backend();
  /* This mountpoint belongs to this backend. Do not shadow an existing mount
   * or accept another filesystem merely because it happens to be LittleFS. */
  struct stat st;
  if(!lstat(PRIVATE_MOUNT,&st))return -EBUSY;
  if(errno!=ENOENT)return -errno;
  if(mount(PRIVATE_BLOCK,PRIVATE_MOUNT,"littlefs",0,options))return -errno;
  mounted=true;
  return private_mount_backend();
}
int buffer_private_storage_prepare(void)
{
  pthread_mutex_lock(&storage_lock);
  int ret=private_map_locked(false);
  if(!ret)ret=private_mount_locked(NULL);
  pthread_mutex_unlock(&storage_lock);return ret;
}
static int private_erased(struct inode *inode,const struct mtd_geometry_s *geometry)
{
  struct mtd_dev_s *mtd=inode->u.i_mtd;
  uint8_t *page=malloc(geometry->blocksize);if(!page)return -ENOMEM;
  uint32_t pages=geometry->erasesize/geometry->blocksize;unsigned good=0;int ret=0;
  for(uint32_t block=0;block<geometry->neraseblocks;block++){
    ret=MTD_ISBAD(mtd,block);if(ret<0)break;if(ret){ret=0;continue;}good++;
    for(uint32_t offset=0;offset<pages;offset++){
      ssize_t got=MTD_BREAD(mtd,(off_t)block*pages+offset,1,page);
      if(got!=1&&got!=-EUCLEAN){ret=got<0?(int)got:-EIO;break;}
      for(uint32_t i=0;i<geometry->blocksize;i++)if(page[i]!=0xff){ret=-EEXIST;break;}
      if(ret)break;
    }
    if(ret)break;
  }
  memset(page,0,geometry->blocksize);free(page);
  return ret?ret:good?0:-ENOSPC;
}
int buffer_private_storage_provision(const char *confirmation)
{
  if(!confirmation||strcmp(confirmation,"--initialize-erased-bufpriv"))return -EPERM;
  pthread_mutex_lock(&g_buffer.lock);bool running=g_buffer.initialized;pthread_mutex_unlock(&g_buffer.lock);
  if(running)return -EBUSY;
  pthread_mutex_lock(&storage_lock);
  int ret;
  if(registered||mounted){ret=-EALREADY;goto done;}
  /* No erase is attempted while checking. This command cannot reset an old
   * or damaged volume: every readable page of every good block must be FF. */
  struct inode *inode=NULL;struct mtd_geometry_s geometry;
  ret=find_mtddriver(PRIVATE_MTD,&inode);if(ret<0)goto done;
  ret=private_geometry(inode,&geometry);
  if(!ret)ret=private_erased(inode,&geometry);
  close_mtddriver(inode);
  if(ret)goto done;
  ret=private_map_locked(true);
  if(!ret)ret=private_mount_locked("forceformat");
done:
  pthread_mutex_unlock(&storage_lock);return ret;
}
#else
int buffer_private_storage_prepare(void){return 0;}
int buffer_private_storage_provision(const char *confirmation)
{(void)confirmation;return -ENOTSUP;}
#endif
