"""Exercise the production Fabric runtime parser, revision fence and mode helpers."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC, fabric_link_declarations
from pathlib import Path
import os
import subprocess
import tempfile


ROOT = PROJECT_ROOT
SDK = SDK_ROOT
FABRIC = (ROOT / "app/buffer_app/buffer_fabric.c").read_text()
WIRE = (ROOT / "app/buffer_app/buffer_wire.c").read_text()
MAIN = (ROOT / "app/buffer_app/buffer_app_main.c").read_text()


def function(source, signature):
    return extract_c(source, signature, last=True)


def declaration(source, signature):
    return extract_c(source, signature, semicolon=True)


harness = r'''
#include <assert.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include "buffer_app.h"
#include "cJSON.h"
#include "buffer_source_envelope.h"
#include <mbedtls/sha256.h>



''' + fabric_link_declarations() + r'''
static pthread_mutex_t g_mode_commit_lock=PTHREAD_MUTEX_INITIALIZER;
struct buffer_runtime_s g_buffer = {
  .lock = PTHREAD_MUTEX_INITIALIZER,
  .mode = "normal",
  .focus_interval_ms = 300000,
};


static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static bool stopping;
static int mode_save_result, pending_clear_result, focus_save_result;
static unsigned mode_save_calls, pending_clear_calls, focus_save_calls;
static int64_t fake_now = 1000;
cJSON *buffer_focus_session_snapshot(void) { errno=0;return NULL; }
int buffer_focus_validate(const cJSON *value,const char *mode) { (void)value;(void)mode;return 0; }
int buffer_focus_import(const cJSON *value) { (void)value;return 0; }

static int64_t buffer_monotonic_ms(void) { return fake_now; }
int buffer_store_save_mode(const char *mode)
{ (void)mode; mode_save_calls++; return mode_save_result; }
int buffer_store_clear_pending_mode(void)
{ pending_clear_calls++; return pending_clear_result; }
int buffer_store_save_focus_interval(uint32_t interval)
{ (void)interval; focus_save_calls++; return focus_save_result; }
void buffer_set_status(const char *format, ...)
{ (void)format; }
static void unlock_status(void) { pthread_mutex_unlock(&lock); }
static bool peer_admitted(struct fabric_link_s *link)
{ return link->session_trusted && link->admitted_generation == link->generation &&
    !memcmp(link->admitted_public, link->peer_public, 32); }
static int source_authorize(void *context,enum buffer_oobe_operation_e op) {
  (void)op;unsigned expected=*(unsigned *)context;
  return links[0].active&&links[0].linked&&!stopping&&links[0].generation==expected&&peer_admitted(&links[0])?0:-EACCES;
}
static bool buffer_coordinator_phone_owner_current(const char *peer,uint64_t term) { (void)peer;return term==1; }
static int buffer_coordinator_phone_owner_commit(const char *peer,uint64_t term,int (*commit)(void *),void *context) {
  return buffer_coordinator_phone_owner_current(peer,term)?commit(context):-EACCES;
}
static uint32_t buffer_focus_interval(void)
{ return g_buffer.focus_interval_ms ? g_buffer.focus_interval_ms : 300000; }
'''

harness += function(FABRIC,"static void hex(") + "\n"
harness += function(FABRIC,"static struct fabric_link_s *link_for_generation(") + "\n"
harness += declaration(FABRIC,"struct runtime_apply_s") + "\n"
harness += function(FABRIC,"static int apply_runtime(") + "\n"

# Keep the existing legacy-profile harness linked to the real helper bodies
# after runtime persistence was factored out of the public entry points.
harness += MAIN[MAIN.index("static bool research_known"):MAIN.index("static void research_tick(")]
harness += declaration(MAIN, "struct focus_reminder_state_s") + "\n"
harness += "static bool focus_runtime_checkpoint = true;\n"
for signature in (
    "static void focus_reminder_snapshot(",
    "static void focus_reminder_apply(",
    "static int focus_runtime_restore_locked(",
    "static int focus_runtime_save_locked(",
):
    harness += function(MAIN, signature) + "\n"

for source, signatures in (
    (WIRE, (
        "static bool buffer_wire_valid_device_id(",
        "static int buffer_parse_research(",
        "static int buffer_parse_focus_config(",
        "int buffer_wire_parse_runtime_state(",
    )),
    (MAIN, (
        "int buffer_sync_research(",
        "int buffer_sync_focus_config(",
        "int buffer_sync_remote_mode(",
        "int buffer_sync_remote_mode_ack(",
    )),
    (FABRIC, (
        "static bool runtime_mode_valid(",
        "static bool mode_id_valid(",
        "static char *mode_change_json(",
        "static int mode_reply_validate(",
        "static void mode_reset_session(",
        "static bool mode_refresh(",
        "static int runtime_revision_check(",
        "static int runtime_revision_commit(",
        "static void runtime_reset_session(",
        "static char *runtime_reply_json(",
        "static int app_authorize(",
        "static int handle_runtime_state(",
    )),
):
    for signature in signatures:
        harness += function(source, signature) + "\n"

harness += r'''
static cJSON *make_state(uint64_t revision, const char *mode,
                         bool focus_active, bool research_active,
                         uint32_t focus_interval)
{
  cJSON *root = cJSON_CreateObject();
  cJSON *focus = cJSON_CreateObject();
  cJSON *research = cJSON_CreateObject();
  assert(root && focus && research);
  cJSON_AddStringToObject(root, "type", "buffer.runtime.state");
  cJSON_AddNumberToObject(root, "protocol", 1);
  cJSON_AddNumberToObject(root, "revision", (double)revision);
  cJSON_AddNumberToObject(root, "coordinator_term", 1);
  cJSON_AddStringToObject(root, "mode", mode);
  cJSON_AddStringToObject(focus, "type", "FocusReminderConfigUpdated");
  cJSON_AddBoolToObject(focus, "active", focus_active);
  cJSON_AddBoolToObject(focus, "clear_notice", false);
  cJSON_AddNumberToObject(focus, "interval_ms", focus_interval);
  cJSON_AddNumberToObject(focus, "remaining_ms", focus_active ? focus_interval : 0);
  cJSON_AddNumberToObject(focus, "notice_remaining_ms", 0);
  cJSON_AddStringToObject(focus, "revision", focus_active ? "focus-r1" : "inactive");
  cJSON_AddItemToObject(root, "focus_reminder", focus);
  cJSON_AddBoolToObject(research, "present", true);
  cJSON_AddBoolToObject(research, "active", research_active);
  char research_id[32];
  snprintf(research_id, sizeof(research_id), "research-%llu",
           (unsigned long long)revision);
  cJSON_AddStringToObject(research, "id", research_id);
  cJSON_AddStringToObject(research, "title", "test research");
  cJSON_AddNumberToObject(research, "remaining_ms", research_active ? 60000 : 0);
  cJSON_AddStringToObject(research, "reason", research_active ? "" : "finished");
  cJSON_AddItemToObject(root, "research_session", research);
  return root;
}

static char *encode(cJSON *value)
{ char *text = cJSON_PrintUnformatted(value); assert(text); return text; }

int main(void)
{
  uint64_t owner_term=0;
  links[0].generation=links[0].admitted_generation=7;links[0].active=links[0].linked=links[0].session_trusted=true;
  memset(links[0].admitted_public,1,32);memset(links[0].peer_public,1,32);
  char mode[BUFFER_MODE_SIZE] = "untouched";
  char mode_id[BUFFER_ACTION_ID_SIZE] = "";
  char *message = mode_change_json("focus", "local-mode-7",1);
  assert(message);
  cJSON *json = cJSON_Parse(message);
  assert(json && cJSON_GetArraySize(json) == 5);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "type")->valuestring,
                 "buffer.mode.change"));
  assert(cJSON_GetObjectItemCaseSensitive(json, "protocol")->valuedouble == 1);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "mode")->valuestring, "focus"));
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "mode_id")->valuestring,
                 "local-mode-7"));
  free(message); cJSON_Delete(json);
  assert(!mode_change_json("unknown", "local-mode-7",1));

  cJSON *mode_reply = cJSON_CreateObject();
  cJSON_AddStringToObject(mode_reply, "type", "buffer.mode.reply");
  cJSON_AddNumberToObject(mode_reply, "protocol", 1);
  cJSON_AddStringToObject(mode_reply, "mode", "focus");
  cJSON_AddStringToObject(mode_reply, "mode_id", "local-mode-7");
  cJSON_AddStringToObject(mode_reply, "state", "applied");
  cJSON_AddNumberToObject(mode_reply,"coordinator_term",1);
  assert(mode_reply_validate(mode_reply, mode, mode_id,&owner_term) == 0);
  assert(!strcmp(mode, "focus") && !strcmp(mode_id, "local-mode-7"));
  cJSON_AddStringToObject(mode_reply, "extra", "rejected");
  assert(mode_reply_validate(mode_reply, mode, mode_id,&owner_term) == -EINVAL);
  cJSON_Delete(mode_reply);

  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "focus");
  snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "local-mode-7");
  g_buffer.mode_dirty = true;
  mode_save_result = 0; pending_clear_result = -EIO;
  assert(buffer_sync_remote_mode_ack("focus", "local-mode-7") == -EIO);
  assert(g_buffer.mode_dirty && mode_save_calls == 1 && pending_clear_calls == 1);
  pending_clear_result = 0;
  assert(buffer_sync_remote_mode_ack("focus", "local-mode-7") == 0);
  assert(!g_buffer.mode_dirty && mode_save_calls == 2 && pending_clear_calls == 2);
  assert(buffer_sync_remote_mode_ack("normal", "local-mode-7") == -ESTALE);
  assert(buffer_sync_remote_mode_ack("focus", "local-mode-7") == 0);

  struct buffer_focus_config_s focus;
  struct buffer_research_s research;
  uint64_t revision = 0;
  json = make_state(1, "focus", true, false, 300000);
  message = encode(json);
  assert(buffer_wire_parse_runtime_state(message, &revision, mode, sizeof(mode),
                                         &focus, &research) == 0);
  assert(revision == 1 && !strcmp(mode, "focus") && focus.active && research.present);
  free(message); cJSON_Delete(json);
  json = make_state(2, "rabbit_hole", false, true, 300000);
  message = encode(json);
  assert(buffer_wire_parse_runtime_state(message, &revision, mode, sizeof(mode),
                                         &focus, &research) == 0);
  assert(revision == 2 && !strcmp(mode, "rabbit_hole") && !focus.active && research.active);
  free(message); cJSON_Delete(json);
  json = make_state(0, "focus", true, false, 300000);
  message = encode(json);
  strcpy(mode, "unchanged"); revision = 99;
  assert(buffer_wire_parse_runtime_state(message, &revision, mode, sizeof(mode),
                                         &focus, &research) == -EINVAL);
  assert(!strcmp(mode, "unchanged") && revision == 99);
  free(message); cJSON_Delete(json);

  /* The production runtime handler applies all three fields, commits only a
   * strictly newer revision, and makes duplicate/old revisions idempotent. */
  json = make_state(1, "focus", true, false, 300000);
  message = encode(json); cJSON_Delete(json);
  char *reply = NULL;
  mode_save_calls = focus_save_calls = 0;
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "normal");
  snprintf(g_buffer.research.id, sizeof(g_buffer.research.id), "existing");
  g_buffer.research.present = true;
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  assert(links[0].runtime_state.revision == 1 && mode_save_calls == 1);
  json = cJSON_Parse(reply);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "state")->valuestring, "applied"));
  cJSON_Delete(json); free(reply); reply = NULL; free(message);
  json = make_state(1, "focus", true, false, 300000);
  message = encode(json); cJSON_Delete(json);
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  assert(mode_save_calls == 1);
  free(reply); reply = NULL; free(message);
  json = make_state(2, "rabbit_hole", false, true, 300000);
  message = encode(json); cJSON_Delete(json);
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  assert(links[0].runtime_state.revision == 2 && !strcmp(g_buffer.mode, "rabbit_hole") &&
         g_buffer.research.active && !strcmp(g_buffer.research.id, "research-2"));
  free(reply); reply = NULL; free(message);
  json = make_state(1, "focus", true, false, 300000);
  message = encode(json); cJSON_Delete(json);
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  json = cJSON_Parse(reply);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "state")->valuestring, "deferred"));
  assert(links[0].runtime_state.revision == 2 && mode_save_calls == 2);
  cJSON_Delete(json); free(reply); reply = NULL; free(message);

  /* A local unacknowledged choice fences an old phone snapshot before any
   * focus or research mutation. */
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "bedtime");
  g_buffer.mode_dirty = true;
  json = make_state(3, "focus", true, false, 600000);
  message = encode(json); cJSON_Delete(json);
  uint32_t prior_interval = g_buffer.focus_interval_ms;
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  assert(!strcmp(g_buffer.mode, "bedtime") && links[0].runtime_state.revision == 2);
  assert(g_buffer.focus_interval_ms == prior_interval &&
         !strcmp(g_buffer.research.id, "research-2"));
  json = cJSON_Parse(reply);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "state")->valuestring, "deferred"));
  cJSON_Delete(json); free(reply); reply = NULL; free(message);

  /* Storage failure is propagated as deferred and never advances revision. */
  g_buffer.mode_dirty = false; g_buffer.mode[0] = '\0';
  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "normal");
  focus_save_result = -EIO;
  json = make_state(3, "bedtime", false, false, 600000);
  message = encode(json); cJSON_Delete(json);
  assert(handle_runtime_state(links[0].generation, message, &reply) == 0 && reply);
  assert(links[0].runtime_state.revision == 2 && !strcmp(g_buffer.mode, "bedtime"));
  json = cJSON_Parse(reply);
  assert(!strcmp(cJSON_GetObjectItemCaseSensitive(json, "state")->valuestring, "deferred"));
  cJSON_Delete(json); free(reply); free(message);

  snprintf(g_buffer.mode, sizeof(g_buffer.mode), "focus");
  snprintf(g_buffer.mode_id, sizeof(g_buffer.mode_id), "local-mode-8");
  g_buffer.mode_dirty = true;
  assert(!mode_refresh(&links[0].mode_state, links[0].generation, 0, 999));
  assert(!links[0].mode_state.message && g_buffer.mode_dirty);
  assert(mode_refresh(&links[0].mode_state, links[0].generation, 1, 1000));
  char retained[256];
  snprintf(retained, sizeof(retained), "%s", links[0].mode_state.message);
  links[0].mode_state.retry_at_ms = 6000;
  assert(!mode_refresh(&links[0].mode_state, links[0].generation, 1, 5999));
  assert(mode_refresh(&links[0].mode_state, links[0].generation, 1, 6000));
  assert(!strcmp(retained, links[0].mode_state.message));
  assert(!mode_refresh(&links[0].mode_state, links[0].generation, 0, 6001));
  assert(!links[0].mode_state.message && g_buffer.mode_dirty);
  assert(mode_refresh(&links[0].mode_state, links[0].generation, 2, 6002));
  assert(links[0].mode_state.owner_term == 2);
  g_buffer.mode_dirty = false;
  assert(!mode_refresh(&links[0].mode_state, links[0].generation, 1, 6001) && !links[0].mode_state.message);

  char *receipt = runtime_reply_json(2, "applied",1);
  assert(receipt);
  json = cJSON_Parse(receipt);
  assert(cJSON_GetArraySize(json) == 5 &&
         cJSON_GetObjectItemCaseSensitive(json, "revision")->valuedouble == 2);
  cJSON_Delete(json); free(receipt);
  runtime_reset_session(&links[0].runtime_state);
  assert(links[0].runtime_state.revision == 0 && links[0].runtime_state.generation == 0);
  puts("PASS: Fabric runtime complete parsing, authenticated revision fencing, durable mode ACK, retry and deferred receipts");
  return 0;
}
'''

with tempfile.TemporaryDirectory(prefix="buffer-fabric-runtime-") as temporary:
    work = Path(temporary)
    include = work / "include/nuttx"
    include.mkdir(parents=True)
    (include / "config.h").write_text("")
    (work / "include/netutils").mkdir()
    (work / "include/netutils/cJSON.h").symlink_to(SDK / "apps/netutils/cjson/cJSON/cJSON.h")
    source = work / "runtime.c"
    executable = work / "runtime"
    source.write_text(harness)
    cjson = SDK / "apps/netutils/cjson/cJSON"
    subprocess.run([
        *CC, "-std=c11", "-Wall", "-Wextra", "-Werror",
        "-pthread", "-I" + str(work / "include"),
        "-I" + str(ROOT / "app/buffer_app"), "-I" + str(cjson),
        "-I" + str(SDK / "apps/crypto/mbedtls/mbedtls/include"), str(source), str(ROOT / "app/buffer_app/buffer_source_envelope.c"), str(cjson / "cJSON.c"), "/tmp/buffer-agent-mbedtls/library/libmbedcrypto.a", "-lm", "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True, timeout=10)
