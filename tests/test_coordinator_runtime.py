"""Exercise actual Vela election/fencing code with a local monotonic clock."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC
from pathlib import Path
import subprocess
import tempfile

root = PROJECT_ROOT
cjson = SDK_ROOT / "apps/netutils/cjson/cJSON"
mbedtls = SDK_ROOT / "apps/crypto/mbedtls/mbedtls/include"
harness = r'''
#include "buffer_coordinator.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
static uint64_t elapsed=100000;
static bool fail_store;
static cJSON *saved;
static int test_clock(clockid_t id,struct timespec *value)
{ (void)id; value->tv_sec=elapsed/1000; value->tv_nsec=(elapsed%1000)*1000000; return 0; }
void buffer_agent_snapshot(struct buffer_agent_status_s *state)
{ memset(state,0,sizeof(*state)); state->ready=true; }
bool buffer_wifi_is_ready(void) { return true; }
int buffer_agent_atomic_json(const char *path,const cJSON *json)
{ (void)path; if (fail_store) return -EIO; cJSON_Delete(saved); saved=cJSON_Duplicate(json,true); return 0; }
cJSON *buffer_agent_read_json(const char *path,size_t limit)
{ (void)path; (void)limit; if (!saved) errno=ENOENT; return cJSON_Duplicate(saved,true); }
#define clock_gettime test_clock
#include "buffer_coordinator.c"
#undef clock_gettime
int main(void)
{
  const char *local="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const char *phone="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  assert(buffer_coordinator_bind(local,phone)==0);
  assert(buffer_coordinator_local());
  uint64_t old=buffer_coordinator_fence();
  buffer_coordinator_connected(true);
  char *wire=NULL; assert(buffer_coordinator_heartbeat(elapsed,&wire)==1);
  cJSON *heartbeat=cJSON_Parse(wire); free(wire);
  cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"device_id",cJSON_CreateString(phone));
  cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"incarnation_id",cJSON_CreateString("phone-boot"));
  cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"device_class",cJSON_CreateString("PHONE"));
  cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"coordinator_device_id",cJSON_CreateString(phone));
  cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"coordinator_term",cJSON_CreateNumber((double)old+100));
  assert(buffer_coordinator_receive("unknown",heartbeat)<0);
  assert(buffer_coordinator_receive(phone,heartbeat)==0);
  assert(buffer_coordinator_local() && buffer_coordinator_fence_current(old));
  /* A controller cannot assign authority or bypass continuous eligibility. */
  for (unsigned i=1;i<=5;i++) {
    elapsed+=3000;
    cJSON_ReplaceItemInObjectCaseSensitive(heartbeat,"heartbeat_sequence",cJSON_CreateNumber(i+1));
    assert(buffer_coordinator_receive(phone,heartbeat)==0);
    assert(buffer_coordinator_local()==(i<5));
  }
  assert(!buffer_coordinator_fence_current(old));
  char owner[65];uint64_t granted;
  buffer_coordinator_status(owner,sizeof(owner),&granted);
  assert(!strcmp(owner,phone) && granted<old+100);
  /* Grant expiry creates a new term even when the same peer remains owner. */
  wire=NULL;assert(buffer_coordinator_heartbeat(elapsed,&wire)==1);free(wire);
  assert(buffer_coordinator_phone_owner_current(phone,granted));
  char observed[65];uint64_t observed_term=0;bool live=false;
  pthread_mutex_lock(&co_lock);
  assert(buffer_coordinator_transport_view(observed,sizeof(observed),&observed_term,&live)==-EAGAIN);
  pthread_mutex_unlock(&co_lock);
  assert(!buffer_coordinator_transport_view(observed,sizeof(observed),&observed_term,&live));
  assert(live && observed_term==granted && !strcmp(observed,phone));
  elapsed+=12001;
  assert(!buffer_coordinator_phone_owner_current(phone,granted));
  assert(buffer_coordinator_receive(phone,heartbeat)==-ESTALE);
  elapsed+=18000;
  fail_store=true;
  assert(!buffer_coordinator_local()); /* Failed takeover persistence fails closed. */
  fail_store=false;
  assert(buffer_coordinator_local());
  assert(buffer_coordinator_fence()>granted);
  assert(!buffer_coordinator_fence_current(old));
  cJSON_Delete(heartbeat); cJSON_Delete(saved);
  puts("PASS: authenticated phone priority, stale heartbeat cannot renew liveness, Vela takeover, recovery window, old-job fencing and fail-closed persistent terms");
  return 0;
}
'''
with tempfile.TemporaryDirectory(prefix="buffer-coordinator-") as temporary:
    work = Path(temporary)
    (work / "nuttx").mkdir()
    (work / "nuttx/config.h").write_text("")
    (work / "netutils").mkdir()
    (work / "netutils/CJSON.h").symlink_to(cjson / "cJSON.h")
    (work / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
    source = work / "test.c"
    source.write_text(harness)
    binary = work / "test"
    subprocess.run([*CC, "-std=c11", "-D_DEFAULT_SOURCE", "-Wall", "-Wextra", "-Werror",
                    "-fsanitize=address,undefined", "-g", "-pthread", "-I"+str(work),
                    "-I"+str(root/"app/buffer_app"), "-I"+str(mbedtls), str(source), str(cjson/"cJSON.c"),
                    "/tmp/buffer-agent-mbedtls/library/libmbedcrypto.a", "-lm", "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
