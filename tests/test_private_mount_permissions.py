"""Verify the mounted private volume is sealed before accepting Wi-Fi writes."""
from pathlib import Path
import subprocess
from host_support import CC, PROJECT_ROOT, extract_c


def test_private_mount_permissions(tmp_path: Path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_private_storage.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <sys/stat.h>
#define PRIVATE_MOUNT "/private"
static mode_t mode=S_IFDIR|0777;
static int fail, ignored, changes;
static int fake_lstat(const char *p,struct stat *s){(void)p;s->st_mode=mode;return 0;}
static int fake_chmod(const char *p,mode_t m){(void)p;changes++;if(fail){errno=EROFS;return -1;}if(!ignored)mode=S_IFDIR|m;return 0;}
#define lstat fake_lstat
#define chmod fake_chmod
'''+extract_c(source,'static int private_mount_permissions(')+r'''
int main(void){
 assert(private_mount_permissions()==0 && mode==(S_IFDIR|0700) && changes==1);
 assert(private_mount_permissions()==0 && changes==1);
 mode=S_IFDIR|0777;fail=1;assert(private_mount_permissions()==-EROFS);
 fail=0;ignored=1;assert(private_mount_permissions()==-EACCES);
 mode=S_IFREG|0700;assert(private_mount_permissions()==-ENODEV);
 return 0;
}
'''
    c=tmp_path/'private.c';c.write_text(harness);exe=tmp_path/'private'
    subprocess.run([*CC,'-g','-fsanitize=address,undefined','-fno-omit-frame-pointer',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=10)


def test_existing_agent_directories_do_not_rewrite_permissions(tmp_path: Path):
    source=(PROJECT_ROOT/'app/buffer_app/buffer_agent_store.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <sys/stat.h>
static mode_t permissions=S_IFDIR|0700;
static unsigned writes;
static int failure,ignored;
static int fake_mkdir(const char *p,mode_t m){(void)p;(void)m;errno=EEXIST;return -1;}
static int fake_stat(const char *p,struct stat *s){(void)p;s->st_mode=permissions;return 0;}
static int fake_chmod(const char *p,mode_t m){(void)p;writes++;if(failure){errno=EROFS;return -1;}if(!ignored)permissions=S_IFDIR|m;return 0;}
#define mkdir fake_mkdir
#define stat(p,s) fake_stat(p,s)
#define lstat fake_stat
#define chmod fake_chmod
'''+extract_c(source,'static int private_directory(')+r'''
int main(void){
 for(int i=0;i<100;i++)assert(private_directory("/private/buffer/records")==0);
 assert(writes==0);
 permissions=S_IFDIR|0777;assert(private_directory("path")==0&&writes==1);
 assert(private_directory("path")==0&&writes==1);
 permissions=S_IFDIR|0777;failure=1;assert(private_directory("path")==-EROFS);
 failure=0;ignored=1;assert(private_directory("path")==-ENODEV);
 permissions=S_IFREG|0700;assert(private_directory("path")==-EACCES);
 return 0;
}
'''
    c=tmp_path/'agent_private.c';c.write_text(harness);exe=tmp_path/'agent_private'
    subprocess.run([*CC,'-Wall','-Wextra','-Werror',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)


def test_backend_checks_do_not_scan_littlefs_and_reject_replaced_mount(tmp_path: Path):
    store=(PROJECT_ROOT/'app/buffer_app/buffer_agent_store.c').read_text()
    private=(PROJECT_ROOT/'app/buffer_app/buffer_private_storage.c').read_text()
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <string.h>
#define __NuttX__ 1
#define CONFIG_FS_LITTLEFS_ATTR_UPDATE 1
#define PRIVATE_MOUNT "/private"
#include <sys/stat.h>
struct mountpt_operations {int unused;};
static const struct mountpt_operations g_littlefs_operations,foreign;
struct inode {union {const struct mountpt_operations *i_mops;} u;};
struct fs_dirent_s {struct inode *fd_root;};
struct file {struct inode *f_inode;void *f_priv;};
static struct inode node,wrapper;
static struct fs_dirent_s directory;
static int opened,closed,missing,denied,permissions;
static int regular_file,intermediate_file;
static int backend_stat(const char *p,struct stat *st){
 (void)p;if(intermediate_file){errno=ENOTDIR;return -1;}
 st->st_mode=S_IFREG|0600;return 0;
}
#define stat(p,s) backend_stat(p,s)
static int file_open(struct file *f,const char *p,int flags){
 assert(flags==(O_RDONLY|O_DIRECTORY));opened++;
 if(denied)return -EACCES;
 if(regular_file&&!strcmp(p,"/private/buffer/head.json"))return -ENOTDIR;
 if(intermediate_file)return -ENOTDIR;
 if(missing&&strcmp(p,PRIVATE_MOUNT))return -ENOENT;
 directory.fd_root=&node;f->f_inode=&wrapper;f->f_priv=&directory;return 0;
}
static int file_close(struct file *f){assert(f->f_inode==&wrapper);closed++;return 0;}
static int buffer_private_storage_prepare(void){return 0;}
static int private_mount_permissions(void){permissions++;return 0;}
'''+extract_c(store,'static int persistent_backend(')+extract_c(private,'static int private_mount_backend(')+r'''
int main(void){
 node.u.i_mops=&g_littlefs_operations;
 for(int i=0;i<100;i++){assert(persistent_backend("/private/buffer")==0);assert(private_mount_backend()==0);}
 assert(opened==200&&closed==200&&permissions==100);
 missing=1;assert(persistent_backend("/private/new/records")==0);assert(opened==203&&closed==201);
 missing=0;regular_file=1;
 assert(persistent_backend("/private/buffer/head.json")==0);
 regular_file=0;intermediate_file=1;
 assert(persistent_backend("/private/file/child")==-ENOTDIR);
 intermediate_file=0;node.u.i_mops=&foreign;
 assert(persistent_backend("/private/buffer")==-ENODEV);
 assert(private_mount_backend()==-ENODEV);assert(permissions==100);
 node.u.i_mops=&g_littlefs_operations;denied=1;
 assert(persistent_backend("/private/buffer")==-EACCES);
 assert(private_mount_backend()==-EACCES);
 char longpath[321];memset(longpath,'a',320);longpath[320]=0;
 assert(persistent_backend(longpath)==-ENAMETOOLONG);
 return 0;
}
'''
    c=tmp_path/'backend.c';c.write_text(harness);exe=tmp_path/'backend'
    subprocess.run([*CC,'-Wall','-Wextra','-Werror',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True,timeout=5)


def test_backend_existing_file_with_real_directory_open(tmp_path: Path):
    """Use real O_DIRECTORY behavior for first creation and atomic overwrite."""
    source=(PROJECT_ROOT/'app/buffer_app/buffer_agent_store.c').read_text()
    root=tmp_path/'private';root.mkdir();(root/'records').mkdir()
    (root/'head.json').write_text('{}')
    (root/'linked.json').symlink_to(root/'head.json')
    harness=r'''
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define __NuttX__ 1
#define CONFIG_FS_LITTLEFS_ATTR_UPDATE 1
struct mountpt_operations {int unused;};
static const struct mountpt_operations g_littlefs_operations;
struct inode {union {const struct mountpt_operations *i_mops;} u;};
struct fs_dirent_s {struct inode *fd_root;};
struct file {void *f_priv;int fd;};
static struct inode node={.u.i_mops=&g_littlefs_operations};
static struct fs_dirent_s directory={.fd_root=&node};
static int file_open(struct file *f,const char *p,int flags){
 int fd=open(p,flags);if(fd<0)return -errno;f->fd=fd;f->f_priv=&directory;return 0;
}
static int file_close(struct file *f){return close(f->fd)?-errno:0;}
static int buffer_private_storage_prepare(void){return 0;}
'''+extract_c(source,'static int persistent_backend(')+r'''
int main(int argc,char **argv){
 assert(argc==2);char p[320];
 assert(persistent_backend(argv[1])==0);
 snprintf(p,sizeof(p),"%s/records",argv[1]);assert(persistent_backend(p)==0);
 snprintf(p,sizeof(p),"%s/head.json",argv[1]);assert(persistent_backend(p)==0);
 snprintf(p,sizeof(p),"%s/new.json",argv[1]);assert(persistent_backend(p)==0);
 snprintf(p,sizeof(p),"%s/new/subdir/new.json",argv[1]);assert(persistent_backend(p)==0);
 snprintf(p,sizeof(p),"%s/linked.json",argv[1]);assert(persistent_backend(p)==0);
 snprintf(p,sizeof(p),"%s/head.json/child",argv[1]);assert(persistent_backend(p)==-ENOTDIR);
 return 0;
}
'''
    harness='#include <stdio.h>\n'+harness
    c=tmp_path/'real_backend.c';c.write_text(harness);exe=tmp_path/'real_backend'
    subprocess.run([*CC,'-Wall','-Wextra','-Werror',str(c),'-o',str(exe)],check=True)
    subprocess.run([str(exe),str(root)],check=True,timeout=5)
