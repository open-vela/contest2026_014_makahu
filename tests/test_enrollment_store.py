"""Production binding/receipt persistence with real atomic rename and injected failure."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
s = (root / 'app/buffer_app/buffer_store.c').read_text()
a = s.index('static int buffer_write_atomic(')
b = s.index('\n}', a) + 2
atomic = s[a:b]
store = s[s.index('/* Keep the enrollment receipt'):]
harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#define BUFFER_PAIRING_FILE "phone.conf"
#define BUFFER_PAIRING_TMP "phone.conf.tmp"
static int fail_rename;
static int test_rename(const char *from, const char *to)
{
  if (fail_rename) { errno = EIO; return -1; }
  return rename(from, to);
}
#define rename test_rename
''' + atomic + '\n' + store + r'''
static const char *receipt = "0123456789abcdef0123456789abcdef";
static char host[96], token[128], phone[80], request[33];
static int load(void) {
  return buffer_store_load_enrollment(host,sizeof(host),token,sizeof(token),phone,sizeof(phone),request,sizeof(request));
}
static void write_file(const char *text) {
  FILE *f = fopen(BUFFER_PAIRING_FILE,"w"); assert(f); assert(fputs(text,f)>=0); assert(!fclose(f));
}
int main(void) {
  assert(load() == -ENOENT);
  write_file("host=192.168.1.2\ntoken=old\nphone_device_id=phone\n");
  assert(load()==0 && !request[0]);
  assert(buffer_store_save_enrollment("0.0.0.0","secret","phone",receipt)==0);
  assert(load()==0 && !strcmp(request,receipt) && !strcmp(token,"secret"));
  assert(buffer_store_save_pairing("192.168.1.3","secret","phone")==0);
  assert(load()==0 && !strcmp(request,receipt) && !strcmp(host,"192.168.1.3"));
  fail_rename=1;
  assert(buffer_store_save_enrollment("0.0.0.0","new-secret","new-phone","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")<0);
  assert(load()==0 && !strcmp(request,receipt) && !strcmp(token,"secret") && !strcmp(phone,"phone"));
  fail_rename=0;
  assert(buffer_store_save_pairing("192.168.1.4","changed","phone")==0);
  assert(load()==0 && !request[0]);
  assert(buffer_store_save_enrollment("host\nenrollment_id=bad","secret","phone",receipt)==-EINVAL);
  assert(buffer_store_save_enrollment("host","secret","phone","bad")==-EINVAL);
  assert(load()==0 && !strcmp(token,"changed"));
  write_file("host=h\ntoken=t\nphone_device_id=p\nenrollment_id=bad\n");
  assert(load()==-EINVAL && !token[0] && !request[0]);
  write_file("host=h\ntoken=t\nphone_device_id=p\ntoken=other\n");
  assert(load()==-EINVAL && !token[0]);
  write_file("host=h\ntoken=t\n");
  assert(load()==-EINVAL);
  assert(buffer_store_save_enrollment("host","secret","phone",receipt)==0);
  assert(buffer_store_load_pairing(host,sizeof(host),token,sizeof(token),phone,sizeof(phone))==0);
  assert(!strcmp(token,"secret"));
  assert(buffer_store_load_enrollment(host,2,token,sizeof(token),phone,sizeof(phone),request,sizeof(request))==-EINVAL);
  puts("PASS: legacy format, atomic receipt, address refresh, rename failure, owner change, injection and corruption rejection");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-enrollment-store-') as temporary:
    work = Path(temporary)
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc','-Wall','-Wextra','-Werror','-pthread',str(work/'test.c'),'-o',str(work/'test')],check=True)
    subprocess.run([str(work/'test')],cwd=work,check=True,timeout=5)
