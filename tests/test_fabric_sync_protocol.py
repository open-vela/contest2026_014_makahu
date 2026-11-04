"""Exercise the production Fabric sync request/reply state helpers on host."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC, fabric_link_declarations
from pathlib import Path
import subprocess
import tempfile


ROOT = PROJECT_ROOT
SDK = SDK_ROOT
SOURCE = (ROOT / "app/buffer_app/buffer_fabric.c").read_text()


def extract(signature):
    return extract_c(SOURCE, signature, semicolon=True)


harness = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include "cJSON.h"
static int64_t calendar_start,calendar_end;
static bool buffer_calendar_range(int64_t *start,int64_t *end) { *start=calendar_start;*end=calendar_end;return calendar_end>calendar_start; }
'''
harness += fabric_link_declarations()
for signature in (
    "static bool sync_id_valid(",
    "static char *sync_request_json(",
    "static int sync_request_begin(",
    "static int sync_reply_apply(",
    "static void sync_reset_session(",
):
    harness += extract(signature) + "\n"

harness += r'''
struct buffer_fabric_status_s { int binding; bool active; };

static struct buffer_fabric_status_s status;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t io_wake = PTHREAD_COND_INITIALIZER;
static bool initialized, peer_ok;
static bool peer_admitted(struct fabric_link_s *link) { (void)link; return peer_ok; }
static void unlock_status(void) { pthread_mutex_unlock(&lock); }
'''
harness += extract("static struct fabric_link_s *link_for_generation(") + "\n"
harness += extract("int buffer_fabric_request_sync(") + "\n"

harness += r'''
struct buffer_runtime_s
{
  pthread_mutex_t lock;
  bool cards_stale;
  bool phone_synced;
};
static struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static void buffer_set_status(const char *format, ...)
{ (void)format; }
'''
harness += extract("static int handle_sync_reply(") + "\n"

harness += r'''
static cJSON *make_reply(const char *id, const char *state)
{
  cJSON *reply = cJSON_CreateObject();
  assert(reply);
  assert(cJSON_AddStringToObject(reply, "type", "buffer.sync.reply"));
  assert(cJSON_AddNumberToObject(reply, "protocol", 1));
  assert(cJSON_AddStringToObject(reply, "request_id", id));
  assert(cJSON_AddStringToObject(reply, "state", state));
  return reply;
}

int main(void)
{
  struct buffer_fabric_sync_state_s sync = {0};
  assert(sync_request_begin(&sync, 17) == 0);
  assert(sync.pending && !sync.complete && !sync.conflict);
  assert(sync_id_valid(sync.request_id));
  char first_id[33];
  char first_message[160];
  memcpy(first_id, sync.request_id, sizeof(first_id));
  snprintf(first_message, sizeof(first_message), "%s", sync.message);

  cJSON *request = cJSON_Parse(sync.message);
  assert(request);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "type")->valuestring,
                 "buffer.sync.request"));
  assert(cJSON_GetObjectItemCaseSensitive(request, "protocol")->valuedouble == 1);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(request, "request_id")->valuestring,
                 first_id));
  cJSON_Delete(request);

  /* A second button press keeps one current ID and does not enqueue a copy
   * while its message is already waiting for transport. After send, pressing
   * again requeues the same ID so Android can return its terminal receipt. */
  char *queued = sync.message;
  assert(sync_request_begin(&sync, 17) == 0);
  assert(sync.message == queued && !strcmp(sync.request_id, first_id));
  free(sync.message);
  sync.message = NULL;
  assert(sync_request_begin(&sync, 17) == 0);
  assert(!strcmp(sync.request_id, first_id));
  assert(!strcmp(sync.message, first_message));

  char other_id[33];
  memcpy(other_id, first_id, sizeof(other_id));
  other_id[31] = other_id[31] == '0' ? '1' : '0';
  cJSON *reply = make_reply(other_id, "complete");
  assert(sync_reply_apply(&sync, 17, true, reply) == -ESTALE);
  cJSON_Delete(reply);
  reply = make_reply(first_id, "complete");
  assert(sync_reply_apply(&sync, 18, true, reply) == -ESTALE);
  assert(sync_reply_apply(&sync, 17, false, reply) == -ESTALE);
  assert(sync.pending && !sync.complete && !sync.conflict);
  assert(sync_reply_apply(&sync, 17, true, reply) == 1);
  assert(!sync.pending && sync.complete && !sync.conflict && !sync.message);
  assert(sync_reply_apply(&sync, 17, true, reply) == 0);
  cJSON_Delete(reply);

  /* A new button press starts a new full scan; stale replica receipts become
   * a terminal conflict and cannot be overwritten by a contradictory reply. */
  assert(sync_request_begin(&sync, 17) == 0);
  char second_id[33];
  memcpy(second_id, sync.request_id, sizeof(second_id));
  assert(strcmp(second_id, first_id));
  assert(sync.pending && !sync.complete && !sync.conflict);
  reply = make_reply(second_id, "conflict");
  assert(sync_reply_apply(&sync, 17, true, reply) == 1);
  assert(!sync.pending && !sync.complete && sync.conflict);
  assert(sync_reply_apply(&sync, 17, true, reply) == 0);
  cJSON_Delete(reply);
  reply = make_reply(second_id, "complete");
  assert(sync_reply_apply(&sync, 17, true, reply) == -ESTALE);
  assert(sync.conflict && !sync.complete);
  cJSON_Delete(reply);

  sync_reset_session(&sync);
  assert(!sync.pending && !sync.complete && !sync.conflict);
  assert(!sync.request_id[0] && !sync.message && sync.sequence == 2);
  assert(sync_request_begin(&sync, 19) == 0);
  assert(strcmp(sync.request_id, second_id));
  sync_reset_session(&sync);
  sync.sequence = UINT64_MAX;
  assert(sync_request_begin(&sync, 19) == -EOVERFLOW);
  assert(!sync.pending && !sync.message);

  /* The public request entry point requires an initialized, bound and
   * currently admitted Fabric session before it creates or requeues work. */
  initialized = true; status.binding = 0; links[0].generation = 23;
  links[0].active = false; links[0].linked = true; peer_ok = true;
  assert(buffer_fabric_request_sync() == -ENOTCONN);
  assert(!links[0].sync_state.pending);
  links[0].active = true; links[0].linked = false;
  assert(buffer_fabric_request_sync() == -ENOTCONN);
  links[0].linked = true; peer_ok = false;
  assert(buffer_fabric_request_sync() == -ENOTCONN);
  peer_ok = true;
  assert(buffer_fabric_request_sync() == 0);
  char api_id[33];
  memcpy(api_id, links[0].sync_state.request_id, sizeof(api_id));
  assert(links[0].sync_state.pending && links[0].sync_state.message);
  assert(buffer_fabric_request_sync() == 0);
  assert(!strcmp(api_id, links[0].sync_state.request_id));
  sync_reset_session(&links[0].sync_state);
  status.binding = -ENOENT;
  assert(buffer_fabric_request_sync() == -ENOENT);

  /* Completion clears only the cached-card marker; conflict leaves it set and
   * neither outcome changes the legacy phone_synced flag. */
  status.binding = 0; links[0].active = true; links[0].linked = true; peer_ok = true;
  links[0].generation = 31; g_buffer.cards_stale = true; g_buffer.phone_synced = false;
  assert(buffer_fabric_request_sync() == 0);
  char complete_id[33];
  memcpy(complete_id, links[0].sync_state.request_id, sizeof(complete_id));
  reply = make_reply(complete_id, "complete");
  assert(handle_sync_reply(31, reply) == 1);
  assert(!g_buffer.cards_stale && !g_buffer.phone_synced);
  g_buffer.cards_stale = true;
  assert(handle_sync_reply(31, reply) == 0);
  assert(!g_buffer.cards_stale && !g_buffer.phone_synced);
  cJSON_Delete(reply);

  g_buffer.cards_stale = true; g_buffer.phone_synced = true;
  assert(buffer_fabric_request_sync() == 0);
  char conflict_id[33];
  memcpy(conflict_id, links[0].sync_state.request_id, sizeof(conflict_id));
  reply = make_reply(conflict_id, "conflict");
  assert(handle_sync_reply(31, reply) == 1);
  assert(g_buffer.cards_stale && g_buffer.phone_synced);
  cJSON_Delete(reply);
  /* A month selected while the old window is in flight creates a new ID.
   * Neither the old completion nor repeated presses can discard the new one. */
  sync_reset_session(&sync);sync.sequence=0;calendar_start=1000;calendar_end=2000;
  assert(!sync_request_begin(&sync,50));char old_window[33];strcpy(old_window,sync.request_id);
  calendar_start=3000;calendar_end=4000;assert(!sync_request_begin(&sync,50));
  assert(strcmp(old_window,sync.request_id)&&sync.calendar_start==3000&&sync.calendar_end==4000);
  reply=make_reply(old_window,"complete");assert(sync_reply_apply(&sync,50,true,reply)==-ESTALE);cJSON_Delete(reply);
  request=cJSON_Parse(sync.message);cJSON *range=cJSON_GetObjectItemCaseSensitive(request,"calendar_range");
  assert(cJSON_GetObjectItemCaseSensitive(range,"start")->valuedouble==3000);
  assert(cJSON_GetObjectItemCaseSensitive(range,"series")->valuedouble==1);cJSON_Delete(request);
  sync_reset_session(&sync);
  puts("PASS: Fabric sync request fencing, window replacement, complete/conflict receipts, and cache freshness without legacy phone flag changes");
  return 0;
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-fabric-sync-") as temporary:
    work = Path(temporary)
    source = work / "sync.c"
    executable = work / "sync"
    source.write_text(harness)
    cjson = SDK / "apps/netutils/cjson/cJSON"
    subprocess.run([
        *CC, "-std=c11", "-Wall", "-Wextra", "-Werror",
        "-I" + str(cjson), str(source), str(cjson / "cJSON.c"),
        "-lm", "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
