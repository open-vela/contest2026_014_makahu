"""Check production canonical admission and live policy gates with ASan/UBSan."""
from host_support import PROJECT_ROOT, extract_c, CC, fabric_link_declarations
from pathlib import Path
import subprocess
import tempfile
source=(PROJECT_ROOT/"app/buffer_app/buffer_fabric.c").read_text()
def function(signature):
    return extract_c(source, signature)

harness=r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
static bool stopping,initialized,view_has_binding;
static uint8_t view_local_id[32],view_peer_id[32];

static uint8_t local_id[32],bound_id[32];
static const unsigned char channel[16]="buffer-channel01",phone_port[16]="buffer-phone-v1!",vela_port[16]="buffer-vela--v1!";
static int identity,trust_result=1,trust_calls;
static int entropy_result,entropy_calls;
static size_t entropy_length;
struct buffer_fabric_status_s
{
  int binding;
  int error;
  bool connected;
  bool active;
  int code;
  bool sync_pending;
  bool sync_complete;
  bool sync_conflict;
  bool adding_member;
  bool member_confirmation;
  char pending_member[65];
  unsigned member_count;
  uint64_t member_revision;
  unsigned member_generation;
};

static struct buffer_fabric_status_s status,status_view;
static pthread_mutex_t status_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
enum buffer_oobe_operation_e { TEST_OPERATION };
static int fabric_identity_posix_trusted(void *backend,const uint8_t key[32])
{(void)backend;(void)key;trust_calls++;return trust_result;}
static int buffer_entropy_read(unsigned char *out,size_t length)
{entropy_calls++;entropy_length=length;if(entropy_result==0)memset(out,0x5a,length);return entropy_result;}
static void participant(const uint8_t id[32],uint8_t out[16]){memcpy(out,id,16);}
'''
harness += fabric_link_declarations()
harness += r"""
static uint64_t member_revision, add_member_until;
static unsigned member_count;
static char member_ids[64][65];
struct buffer_fabric_member_s { char device_id[65]; bool selected; };
static struct buffer_fabric_member_s member_view[64];
struct source_authority_s {unsigned generation;bool allowed;char peer[65];};
static struct source_authority_s source_authorities[FABRIC_LINKS];
static uint64_t milliseconds(void) { return 0; }
static bool member_contains(const char *peer) { (void)peer;return true; }
static bool peer_admitted(struct fabric_link_s *link);
"""
for name in ("static struct fabric_link_s *link_for_generation(","static struct fabric_link_s *display_link(","static void hex(","static void publish_source_authority(","static int random_bytes(","static void unlock_status(","int buffer_fabric_identity_pair(","void buffer_fabric_snapshot(","static void invalidate_admission(","static bool peer_admitted(","static bool admit_peer(","static int32_t policy(","static int app_authorize("):
    harness+=function(name)+"\n"
harness+=r'''
int main(void){
 uint8_t entropy[32];
 assert(random_bytes(NULL,entropy,sizeof(entropy))==0);
 assert(entropy_calls==1 && entropy_length==sizeof(entropy) && entropy[0]==0x5a);
 entropy_result=-ENOTSUP;
 assert(random_bytes(NULL,entropy,sizeof(entropy))==-ENOTSUP);
 assert(entropy_calls==2 && entropy_length==sizeof(entropy));
 entropy_result=0;
 struct buffer_fabric_status_s view;
 pthread_mutex_lock(&lock);status.binding=-EAGAIN;unlock_status();
 pthread_mutex_lock(&lock);
 buffer_fabric_snapshot(&view);assert(view.binding==-EAGAIN);
 pthread_mutex_unlock(&lock);
 uint8_t plan[140]={0};unsigned expected=7;
 links[0].generation=7;links[0].linked=true;status.binding=0;links[0].active=true;
 memset(local_id,0x11,32);memset(links[0].peer_id,0x22,32);memset(links[0].peer_public,0x33,32);
 memcpy(bound_id,links[0].peer_id,32);
 char local[65],peer[65];
 assert(buffer_fabric_identity_pair(local,peer)==-ENOENT && !local[0] && !peer[0]);
 initialized=true;
 pthread_mutex_lock(&lock);unlock_status();
 pthread_mutex_lock(&lock);
 assert(buffer_fabric_identity_pair(local,peer)==0 && strlen(local)==64 && strlen(peer)==64);
 assert(local[0]=='1' && peer[0]=='2');
 pthread_mutex_unlock(&lock);
 stopping=true;pthread_mutex_lock(&lock);unlock_status();
 assert(buffer_fabric_identity_pair(local,peer)==-ENOENT && !local[0] && !peer[0]);
 stopping=false;
 plan[23]=1;memcpy(plan+24,channel,16);participant(links[0].peer_id,plan+40);participant(local_id,plan+56);
 memcpy(plan+72,phone_port,16);memcpy(plan+88,vela_port,16);plan[138]=4;
 assert(!peer_admitted(&links[0]));assert(!policy(&links[0],plan,0));assert(admit_peer(&links[0])&&trust_calls==1);
 for(int i=0;i<1000;i++){assert(policy(&links[0],plan,0)==1);assert(app_authorize(&expected,TEST_OPERATION)==0);}
 assert(trust_calls==1);
 unsigned positions[]={16,24,40,56,72,88};
 for(unsigned i=0;i<6;i++){plan[positions[i]]^=1;assert(!policy(&links[0],plan,0));plan[positions[i]]^=1;}
 assert(!policy(&links[0],plan,3));
 links[0].generation++;assert(!policy(&links[0],plan,0));assert(app_authorize(&expected,TEST_OPERATION)==-EACCES);
 assert(admit_peer(&links[0])&&trust_calls==2);expected=links[0].generation;
 links[0].peer_public[0]^=1;assert(!policy(&links[0],plan,0));links[0].peer_public[0]^=1;
 links[0].linked=false;assert(!policy(&links[0],plan,0));assert(app_authorize(&expected,TEST_OPERATION)==-EACCES);links[0].linked=true;
 stopping=true;assert(!policy(&links[0],plan,0));assert(app_authorize(&expected,TEST_OPERATION)==-EACCES);stopping=false;
 status.binding=-EACCES;assert(!policy(&links[0],plan,0));status.binding=0;
 links[0].active=false;assert(app_authorize(&expected,TEST_OPERATION)==-EACCES);links[0].active=true;
 invalidate_admission(&links[0]);assert(!policy(&links[0],plan,0));trust_result=0;
 assert(!admit_peer(&links[0]));assert(!peer_admitted(&links[0]));assert(!policy(&links[0],plan,0));
 assert(app_authorize(&expected,TEST_OPERATION)==-EACCES);
 puts("PASS: canonical admission, live peer/links[0].generation/tuple gates nonblocking UI snapshot and no repeated trust I/O");return 0;
}
'''
with tempfile.TemporaryDirectory(prefix="buffer-admission-") as directory:
    work=Path(directory);path=work/"admission.c";path.write_text(harness);executable=work/"admission"
    subprocess.run([*CC,"-std=c11","-Wall","-Wextra","-Werror","-pthread","-fsanitize=address,undefined","-fno-omit-frame-pointer",str(path),"-o",str(executable)],check=True)
    subprocess.run([str(executable)],check=True,timeout=10)
