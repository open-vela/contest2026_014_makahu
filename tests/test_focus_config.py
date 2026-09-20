"""Exercise production focus payload parsing and revision-based timer updates."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
main = (root / 'app/buffer_app/buffer_app_main.c').read_text()
wire = (root / 'app/buffer_app/buffer_wire.c').read_text()
cjson = root.parent / 'apps/netutils/cjson/cJSON'


def section(source, start, end):
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


harness = r'''
#include "buffer_app.h"
#include "cJSON.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static int64_t now = 1000;
static int storage_error, writes;
static int64_t buffer_monotonic_ms(void) { return now; }
int buffer_store_save_focus_interval(uint32_t interval)
{
  assert(interval >= BUFFER_FOCUS_INTERVAL_MIN_MS);
  writes++;
  return storage_error;
}
''' + section(main, 'int buffer_sync_focus_config(', '/* Called with the runtime lock held. */') + section(
    wire, 'static bool buffer_wire_valid_device_id(', 'static void buffer_wire_load_standalone_state('
) + section(wire, 'static int buffer_parse_focus_config(', 'static int buffer_apply_cards(') + r'''
int main(void)
{
  struct buffer_focus_config_s config;
  cJSON *root = cJSON_Parse("{\"focus_reminder\":{\"type\":\"FocusReminderConfigUpdated\",\"active\":true,\"interval_ms\":300000,\"remaining_ms\":60000,\"revision\":\"focus-abc\",\"clear_notice\":true}}");
  assert(root && buffer_parse_focus_config(root, &config) == 1);
  assert(config.notice_remaining_ms == 0);
  strcpy(g_buffer.mode, "focus");
  g_buffer.focus_reminder_due = true;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.next_care_at == 61000 && writes == 1);
  assert(!g_buffer.focus_reminder_due);
  now = 9000;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.next_care_at == 61000 && writes == 1);
  strcpy(config.revision, "focus-next");
  config.clear_notice = false;
  g_buffer.focus_reminder_due = true;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.next_care_at == 69000 && g_buffer.focus_reminder_due);
  strcpy(config.revision, "focus-defer");
  config.clear_notice = true;
  config.interval_ms = 600000;
  storage_error = -ENOSPC;
  assert(buffer_sync_focus_config(&config) == -ENOSPC);
  assert(g_buffer.focus_interval_ms == 300000);
  assert(strcmp(g_buffer.focus_revision, "focus-next") == 0);
  assert(g_buffer.next_care_at == 69000 && g_buffer.focus_reminder_due);
  storage_error = 0;
  config.remaining_ms = 0;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.next_care_at == now && !g_buffer.focus_reminder_due);
  strcpy(config.revision, "focus-conflict");
  config.remaining_ms = 100;
  g_buffer.mode_dirty = true;
  assert(buffer_sync_focus_config(&config) == 0 && g_buffer.next_care_at == now);
  g_buffer.mode_dirty = false;
  strcpy(g_buffer.mode, "normal");
  assert(buffer_sync_focus_config(&config) == 0 && g_buffer.next_care_at == now);
  config.interval_ms = 1;
  assert(buffer_sync_focus_config(&config) == -EINVAL);
  assert(buffer_sync_focus_config(NULL) == -EINVAL);
  cJSON *value = cJSON_GetObjectItemCaseSensitive(root, "focus_reminder");
  cJSON *interval = cJSON_GetObjectItemCaseSensitive(value, "interval_ms");
  cJSON_SetNumberValue(interval, 300000.5);
  assert(buffer_parse_focus_config(root, &config) == -EINVAL);
  cJSON_SetNumberValue(interval, 300000);
  cJSON *notice = cJSON_AddNumberToObject(value, "notice_remaining_ms", 15000);
  assert(buffer_parse_focus_config(root, &config) == 1 && config.notice_remaining_ms == 15000);
  cJSON_SetNumberValue(notice, 15000.5);
  assert(buffer_parse_focus_config(root, &config) == -EINVAL);
  cJSON_SetNumberValue(notice, -1);
  assert(buffer_parse_focus_config(root, &config) == -EINVAL);
  cJSON_SetNumberValue(notice, 15001);
  assert(buffer_parse_focus_config(root, &config) == -EINVAL);
  cJSON_SetNumberValue(notice, 0);
  cJSON_ReplaceItemInObjectCaseSensitive(value, "revision", cJSON_CreateString("bad revision"));
  assert(buffer_parse_focus_config(root, &config) == -EINVAL);
  cJSON_DeleteItemFromObjectCaseSensitive(root, "focus_reminder");
  assert(buffer_parse_focus_config(root, &config) == 0);
  strcpy(g_buffer.mode, "focus");
  config = (struct buffer_focus_config_s){.active = true, .interval_ms = 600000,
    .remaining_ms = 600000, .notice_remaining_ms = 15000};
  strcpy(config.revision, "focus-phone-first");
  g_buffer.focus_reminder_due = false;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.focus_reminder_due && g_buffer.focus_reminder_until == now + 15000);
  now += 1000;
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.focus_reminder_until == now + 14000);
  strcpy(config.revision, "focus-local-first");
  assert(buffer_sync_focus_config(&config) == 0);
  assert(g_buffer.focus_reminder_until == now + 14000);
  strcpy(config.revision, "focus-snooze");
  config.clear_notice = true;
  assert(buffer_sync_focus_config(&config) == 0 && !g_buffer.focus_reminder_due);
  strcpy(config.revision, "focus-expired");
  config.clear_notice = false;
  config.notice_remaining_ms = 0;
  assert(buffer_sync_focus_config(&config) == 0 && !g_buffer.focus_reminder_due);
  config.notice_remaining_ms = 15001;
  assert(buffer_sync_focus_config(&config) == -EINVAL);
  cJSON_Delete(root);
  puts("PASS: focus payload validation, revision idempotency, snooze, persistence failure/retry, mode conflicts, phone-first/local-first notice, expiry and snooze");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-focus-config-') as temporary:
    work = Path(temporary)
    (work / 'nuttx').mkdir()
    (work / 'nuttx/config.h').write_text('')
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-pthread', '-I', str(work),
                    '-I', str(root / 'app/buffer_app'), '-I', str(cjson),
                    str(work / 'test.c'), str(cjson / 'cJSON.c'), '-lm', '-o', str(work / 'test')], check=True)
    subprocess.run([str(work / 'test')], check=True, timeout=10)
