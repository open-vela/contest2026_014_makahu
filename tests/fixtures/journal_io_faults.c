/* Host test linker wrappers. No production journal code is replaced. */
#include <errno.h>
#include <stddef.h>
#include <sys/types.h>
static unsigned rename_after, fsync_after;
void fixture_journal_fail_rename(unsigned nth) { rename_after=nth; }
void fixture_journal_fail_fsync(unsigned nth) { fsync_after=nth; }
int __real_rename(const char *, const char *);
int __real_fsync(int);
int __wrap_rename(const char *from,const char *to)
{
  if(rename_after && --rename_after==0){errno=ENOSPC;return -1;}
  return __real_rename(from,to);
}
int __wrap_fsync(int fd)
{
  if(fsync_after && --fsync_after==0){errno=EIO;return -1;}
  return __real_fsync(fd);
}
