"""Production research parser and monotonic display, without board hardware."""
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
static int64_t buffer_monotonic_ms(void) { return now; }
''' + section(main, 'int buffer_sync_research(', 'int buffer_sync_focus_config(') + section(
    wire, 'static bool buffer_wire_valid_device_id(', 'static void buffer_wire_load_standalone_state('
) + section(wire, 'static int buffer_parse_research(', 'static int buffer_parse_focus_config(') + r'''
int main(void) {
  struct buffer_research_s config;
  char text[BUFFER_TEXT_SIZE + 80];
  cJSON *root = cJSON_Parse("{\"mode\":\"rabbit_hole\",\"research_session\":{\"present\":true,\"id\":\"research-1\",\"title\":\"为什么天空是蓝色\",\"active\":true,\"reason\":\"\",\"remaining_ms\":120000}}");
  assert(buffer_parse_research(root, &config) == 1);
  strcpy(g_buffer.mode, "rabbit_hole");
  assert(buffer_sync_research(&config) == 0);
  assert(buffer_research_snapshot(text, sizeof(text)) && strstr(text, "2 分钟"));
  now += 60000;
  assert(buffer_sync_research(&config) == 0); /* Replay does not extend. */
  assert(g_buffer.research_deadline == 121000);
  assert(buffer_research_snapshot(text, sizeof(text)) && strstr(text, "1 分钟"));
  now += 61000;
  assert(buffer_research_snapshot(text, sizeof(text)) && strstr(text, "时间到"));
  g_buffer.mode_dirty = true;
  strcpy(config.id, "research-2");
  assert(buffer_sync_research(&config) == 0 && strcmp(g_buffer.research.id, "research-1") == 0);
  assert(!buffer_research_snapshot(text, sizeof(text)));
  g_buffer.mode_dirty = false;
  assert(buffer_sync_research(&config) == 0 && g_buffer.research_deadline == now + 120000);
  config.active = false; config.remaining_ms = 0; strcpy(config.reason, "finished");
  assert(buffer_sync_research(&config) == 0);
  strcpy(g_buffer.mode, "normal");
  assert(buffer_research_snapshot(text, sizeof(text)) && strstr(text, "已结束"));
  config.active = true; config.remaining_ms = 120000; config.reason[0] = 0;
  assert(buffer_sync_research(&config) == 0 && !g_buffer.research.active);
  strcpy(g_buffer.mode, "bedtime");
  assert(!buffer_research_snapshot(text, sizeof(text)));
  cJSON *session = cJSON_GetObjectItem(root, "research_session");
  cJSON_ReplaceItemInObject(session, "remaining_ms", cJSON_CreateNumber(7200001));
  assert(buffer_parse_research(root, &config) == -EINVAL);
  cJSON_ReplaceItemInObject(session, "remaining_ms", cJSON_CreateNumber(1.5));
  assert(buffer_parse_research(root, &config) == -EINVAL);
  cJSON_ReplaceItemInObject(session, "remaining_ms", cJSON_CreateNumber(100));
  cJSON_ReplaceItemInObject(root, "mode", cJSON_CreateString("normal"));
  assert(buffer_parse_research(root, &config) == -EINVAL);
  cJSON_DeleteItemFromObject(root, "research_session");
  assert(buffer_parse_research(root, &config) == 0 && !config.present);
  assert(buffer_sync_research(&config) == 0 && !g_buffer.research.present);
  cJSON_Delete(root);
  puts("PASS: research payload bounds, mode validation, countdown, replay, local mode conflict, terminal state and legacy clear");
}
'''
with tempfile.TemporaryDirectory(prefix='buffer-research-') as temporary:
    work = Path(temporary)
    (work / 'nuttx').mkdir()
    (work / 'nuttx/config.h').write_text('')
    (work / 'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', '-pthread', '-I', str(work),
                    '-I', str(root / 'app/buffer_app'), '-I', str(cjson),
                    str(work / 'test.c'), str(cjson / 'cJSON.c'), '-lm', '-o', str(work / 'test')], check=True)
    subprocess.run([str(work / 'test')], check=True, timeout=10)
