"""Production bounded task query and explicit-ID action helper on private files."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c
from pathlib import Path
import json
import subprocess
import tempfile


def test_all_local_tasks_and_explicit_actions():
    app = PROJECT_ROOT / 'app/buffer_app'
    store = (app / 'buffer_agent_store.c').read_text()
    main = (app / 'buffer_app_main.c').read_text()
    with tempfile.TemporaryDirectory(prefix='vela-todo-') as temporary:
        work = Path(temporary)
        (work / 'nuttx').mkdir()
        (work / 'nuttx/config.h').write_text('')
        (work / 'netutils').mkdir()
        (work / 'netutils/cJSON.h').symlink_to(SDK_ROOT / 'apps/netutils/cjson/cJSON/cJSON.h')
        for name in ('records', 'replicas'):
            (work / name).mkdir()
        def write(folder, name, data):
            p = work / folder / (name + '.json')
            p.write_text(json.dumps(data)); p.chmod(0o600)
        for n in range(137):
            card_id = f'task_{n:04}'
            write('replicas', card_id, {'deleted': False, 'card': {'card_id': card_id,
                'kind': 'task_card', 'state': 'inbox', 'title': f'Todo {n}', 'actions': ['done']}})
        for n, state in enumerate(('done', 'archived', 'deleted', 'pending_transcription')):
            write('replicas', f'hidden_{n}', {'deleted': False, 'card': {
                'card_id': f'hidden_{n}', 'kind': 'task_card', 'state': state}})
        write('replicas', 'tombstone', {'deleted': True, 'card_id': 'tombstone'})
        write('replicas', 'reopen_peer', {'deleted': False, 'card': {'card_id': 'reopen_peer',
            'kind': 'task_card', 'state': 'done', 'actions': ['reopen']}})
        write('replicas', 'other', {'deleted': False, 'card': {'card_id': 'other', 'kind': 'spark_card', 'state': 'active'}})
        # Physical directory entries can outlive a published source view.
        write('replicas', 'retired', {})
        write('replicas', 'deferred', {})
        write('records', 'local', {'capture_id': 'local', 'state': 'ready', 'segments': [
            {'card_id': 'task_0001', 'kind': 'task_card', 'state': 'active', 'title': 'duplicate'},
            {'card_id': 'tombstone', 'kind': 'task_card', 'state': 'active', 'title': 'deleted by phone'},
            {'card_id': 'vla_local', 'kind': 'task_card', 'state': 'active', 'title': 'Local todo'},
            {'card_id': 'vla_pending', 'kind': 'task_card', 'state': 'done', 'previous_state': 'active',
             'action_revision': 2, 'action_synced_revision': 1, 'title': 'Pending done'},
            {'card_id': 'vla_done', 'kind': 'task_card', 'state': 'done'},
            {'card_id': 'vla_deleted', 'kind': 'task_card', 'state': 'deleted', 'previous_state': 'active', 'action_revision': 3, 'action_synced_revision': 1},
            {'card_id': 'vla_archived', 'kind': 'task_card', 'state': 'archived', 'previous_state': 'active', 'action_revision': 3, 'action_synced_revision': 1},
        ]})
        write('records', 'raw', {'state': 'pending_classification', 'segments': [
            {'card_id': 'not_ready', 'kind': 'task_card', 'state': 'active'}]})
        harness = r'''
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "buffer_todo.h"
#include "buffer_agent.h"
#include "buffer_source_events.h"
struct buffer_runtime_s g_buffer={.lock=PTHREAD_MUTEX_INITIALIZER};
''' + '\n'.join(extract_c(store, sig) for sig in (
            'static void wipe(', 'bool buffer_agent_valid_id(', 'int buffer_agent_read_bytes(', 'cJSON *buffer_agent_parse_json(', 'cJSON *buffer_agent_read_json_plain(', 'cJSON *buffer_agent_read_json(')) + r'''
bool buffer_replica_contains(const char *id)
{ char path[512];snprintf(path,sizeof(path),"%s/replicas/%s.json",CONFIG_BUFFER_VELA_DATA_DIR,id);return access(path,F_OK)==0; }
bool buffer_replica_available(const char *id) { return buffer_replica_contains(id); }
bool buffer_source_is_record_path(const char *path) { (void)path; return false; }
static int deferred_error=ENOENT;
bool buffer_source_is_projection_path(const char *path) { return strstr(path,"/retired.json")||strstr(path,"/deferred.json"); }
cJSON *buffer_source_record_read(const char *path,size_t limit) { return buffer_agent_read_json_plain(path,limit); }
cJSON *buffer_source_projection_read(const char *path,size_t limit) { (void)limit;errno=strstr(path,"/retired.json")?ENOENT:deferred_error;return NULL; }
bool buffer_replica_crdt_contains(const char *id) { (void)id; return false; }
int buffer_replica_crdt_action(const char *id,const char *action) { (void)id;(void)action;return -ENOTSUP; }
int buffer_replica_publish_local_action(const char *id,const char *action) { (void)id;(void)action;return 0; }
static int saves,local_actions,save_error;
static char saved_card[BUFFER_ID_SIZE],saved_action[8];
void buffer_set_status(const char *fmt,...) { (void)fmt; }
void buffer_request_sync(void) { }
int buffer_agent_apply_action(const char *id,const char *action)
{ assert((!strcmp(id,"vla_local") && !strcmp(action,"done"))||(!strcmp(id,"vla_done")&&!strcmp(action,"reopen")));local_actions++;return 0; }
int buffer_store_save_pending_action(const char *card,const char *id,const char *action)
{ (void)id;assert(!strcmp(action,"done")||!strcmp(action,"reopen"));if(save_error)return save_error;saves++;snprintf(saved_card,sizeof(saved_card),"%s",card);snprintf(saved_action,sizeof(saved_action),"%s",action);return 0; }
static int buffer_generate_action_id(char *id,size_t size)
{ snprintf(id,size,"unique-action");return 0; }
''' + extract_c(main, 'bool buffer_card_supports_action(') + '\n' + extract_c(main, 'int buffer_request_action_id(') + '\n' + extract_c(main, 'void buffer_request_card_action(') + r'''
int main(void)
{
  struct buffer_todo_item page[BUFFER_TODO_PAGE];char cursor[BUFFER_ID_SIZE]="";bool more=true;int total=0;
  deferred_error=EAGAIN;assert(buffer_todo_page("",1,page,BUFFER_TODO_PAGE,&more)==-EAGAIN);
  deferred_error=EIO;assert(buffer_todo_page("",1,page,BUFFER_TODO_PAGE,&more)==-EIO);
  deferred_error=ENOENT;more=true;
  while(more) {
    int n=buffer_todo_page(cursor,1,page,BUFFER_TODO_PAGE,&more);assert(n>0 && n<=8);
    for(int i=0;i<n;i++) { assert(!cursor[0] || strcmp(page[i].card.card_id,cursor)>0);snprintf(cursor,sizeof(cursor),"%s",page[i].card.card_id);total++; }
  }
  assert(total==142); /* 137 phone + 3 completed + local + pending; no duplicate/raw/tombstone. */
  struct buffer_todo_item completed;assert(!buffer_todo_get("hidden_0",&completed)&&!strcmp(completed.card.state,"done")&&!completed.card.actions[0]);
  assert(!buffer_todo_get("vla_done",&completed)&&!strcmp(completed.card.state,"done")&&!strcmp(completed.card.actions,"reopen"));
  int n=buffer_todo_page("task_0016",-1,page,8,&more);assert(n==8 && more);
  assert(!strcmp(page[0].card.card_id,"task_0008") && !strcmp(page[7].card.card_id,"task_0015"));
  struct buffer_todo_item item;
  assert(buffer_todo_get("vla_pending",&item)==0 && item.pending);
  assert(buffer_todo_get("tombstone",&item)==-ENOENT);
  assert(buffer_todo_get("task_0001",&item)==0 && !item.local && strcmp(item.card.title,"duplicate"));
  g_buffer.card_index=4; /* Explicit list action must not depend on home projection. */
  assert(buffer_request_action_id("task_0136","done")==0);
  assert(saves==1 && !strcmp(saved_card,"task_0136") && g_buffer.card_index==4);
  assert(buffer_request_action_id("task_0001","done")==-EBUSY && saves==1);
  g_buffer.action_requested=false;save_error=-EIO;
  assert(buffer_request_action_id("task_0001","done")==-EIO && !g_buffer.action_requested);
  save_error=0;
  assert(buffer_request_action_id("reopen_peer","reopen")==0&&!strcmp(saved_action,"reopen")&&!strcmp(g_buffer.action,"reopen"));
  assert(!strcmp(saved_card,"reopen_peer"));g_buffer.action_requested=false;
  assert(buffer_request_action_id("vla_local","done")==0 && local_actions==1);
  assert(buffer_request_action_id("vla_done","reopen")==0 && local_actions==2);
  assert(buffer_request_action_id("vla_local","reopen")==-EACCES);
  assert(buffer_request_action_id("hidden_0","reopen")==-EACCES); /* Old peer has not advertised support. */
  assert(buffer_request_action_id("vla_deleted","reopen")==-ENOENT);
  assert(buffer_request_action_id("vla_archived","reopen")==-ENOENT);
  assert(buffer_request_action_id("vla_pending","done")==-EBUSY);
  assert(buffer_request_action_id("tombstone","done")==-ENOENT);
  /* Card detail pins its ID even when the recommended deck reorders. */
  g_buffer.card_count=2;g_buffer.card_index=1;
  strcpy(g_buffer.cards[0].card_id,"task_0001");
  strcpy(g_buffer.cards[1].card_id,"task_0136");
  g_buffer.action_requested=false;
  int before=saves;
  buffer_request_card_action("task_0001","done");
  assert(saves==before+1 && !strcmp(saved_card,"task_0001") && g_buffer.card_index==1);
  g_buffer.action_requested=false;
  buffer_request_card_action("removed_card","done");
  assert(saves==before+1 && !g_buffer.action_requested);
  puts("PASS: 139 tasks across bounded pages, previous page, authoritative dedup/tombstones, raw exclusion, pending state, explicit-ID and durable slot failure/busy");
}
'''
        (work / 'test.c').write_text(harness)
        cjson = SDK_ROOT / 'apps/netutils/cjson/cJSON'
        subprocess.run([*CC, '-std=c11', '-D_DEFAULT_SOURCE', '-Wall', '-Wextra', '-Werror', '-pthread',
            '-DCONFIG_BUFFER_VELA_DATA_DIR="' + str(work) + '"', '-I'+str(work), '-I'+str(app), '-I'+str(cjson),
            str(work/'test.c'), str(app/'buffer_todo.c'), str(cjson/'cJSON.c'), '-lm', '-o', str(work/'test')], check=True)
        subprocess.run([str(work/'test')], check=True, timeout=20)
