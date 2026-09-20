"""Host fault-injection test of the production deck commit function."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "app/buffer_app/buffer_app_main.c").read_text()
start = source.index("int buffer_update_cards(")
end = source.index("\nvoid buffer_sync_remote_mode", start)
with tempfile.TemporaryDirectory(prefix="buffer-cache-test-") as temporary:
    work = Path(temporary)
    (work / "nuttx").mkdir()
    (work / "nuttx/config.h").write_text("")
    harness = r'''#include "buffer_app.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static int store_result;
static int stores;
int buffer_store_save_cards(const struct buffer_card_s *cards, int count)
{
  (void)cards; (void)count; stores++; return store_result;
}
int buffer_store_pending_count(void) { return 2; }
void buffer_set_status(const char *format, ...) { (void)format; }
''' + source[start:end] + r'''
int main(void)
{
  struct buffer_card_s cards[3] = {0};
  strcpy(cards[0].card_id, "a");
  strcpy(cards[1].card_id, "b");
  strcpy(cards[2].card_id, "c");
  assert(buffer_update_cards(cards, 3) == 0);
  g_buffer.card_index = 1;
  assert(buffer_update_cards(cards, 3) == 0);
  assert(g_buffer.card_index == 1);
  struct buffer_card_s reordered[2] = {cards[1], cards[0]};
  assert(buffer_update_cards(reordered, 2) == 0);
  assert(g_buffer.card_index == 0);
  g_buffer.card_index = 1;
  store_result = -ENOSPC;
  assert(buffer_update_cards(cards, 3) == -ENOSPC);
  assert(g_buffer.cards_stale);
  assert(g_buffer.card_count == 2 && g_buffer.card_index == 1);
  assert(strcmp(g_buffer.cards[0].card_id, "b") == 0);
  int before = stores;
  assert(buffer_update_cards(NULL, 1) == -EINVAL);
  assert(buffer_update_cards(cards, -1) == -EINVAL);
  assert(buffer_update_cards(cards, BUFFER_CARD_LIMIT + 1) == -EINVAL);
  assert(stores == before);
  store_result = 0;
  assert(buffer_update_cards(cards + 2, 1) == 0);
  assert(!g_buffer.cards_stale && g_buffer.card_index == 0);
  assert(g_buffer.pending_count == 2);
  assert(buffer_update_cards(NULL, 0) == 0);
  assert(g_buffer.card_count == 0 && g_buffer.card_index == 0);
  puts("PASS: cache failure, retry, selection preservation, removal, empty deck, invalid input");
}
'''
    (work / "test.c").write_text(harness)
    subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-pthread",
                    "-I", str(work), "-I", str(root / "app/buffer_app"),
                    str(work / "test.c"), "-o", str(work / "test")], check=True)
    subprocess.run([str(work / "test")], check=True)
