/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_todo.h"
#include "buffer_agent.h"
#include "buffer_replica.h"
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
static const char *txt(const cJSON *j, const char *key)
{
  const cJSON *v = cJSON_GetObjectItemCaseSensitive(j, key);
  return cJSON_IsString(v) ? v->valuestring : "";
}
static void copy(char *out, size_t size, const char *in)
{
  size_t n = strlen(in);
  if (n >= size) { n = size - 1; while (n && ((unsigned char)in[n] & 0xc0) == 0x80) n--; }
  memcpy(out, in, n); out[n] = 0;
}
static bool visible_state(const char *state)
{
  return strcmp(state, "archived") &&
         strcmp(state, "deleted") && strcmp(state, "pending_transcription");
}
static bool task(const cJSON *card, const cJSON *record, bool local,
                  struct buffer_todo_item *item)
{
  if (strcmp(txt(card, "kind"), "task_card") ||
      !buffer_agent_valid_id(txt(card, "card_id")) ||
      cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(card, "deleted"))) return false;
  /* Tombstones and archives stay hidden even with an unsent local revision.
   * Only pending completion remains as an explicitly unconfirmed working row. */
  if (!strcmp(txt(card,"state"),"deleted") || !strcmp(txt(card,"state"),"archived")) return false;
  const cJSON *revision = cJSON_GetObjectItemCaseSensitive(card, "action_revision");
  const cJSON *synced = cJSON_GetObjectItemCaseSensitive(card, "action_synced_revision");
  bool pending = local && cJSON_IsNumber(revision) &&
      revision->valuedouble > (cJSON_IsNumber(synced) ? synced->valuedouble : 0);
  const char *state = pending ? txt(card, "previous_state") : txt(card, "state");
  if (!visible_state(state)) return false;
  memset(item, 0, sizeof(*item));
  item->local = local; item->pending = pending;
  copy(item->card.card_id, sizeof(item->card.card_id), txt(card,"card_id"));
  copy(item->card.title, sizeof(item->card.title), txt(card,"title")[0] ? txt(card,"title") : txt(card,"summary"));
  if(!item->card.title[0])copy(item->card.title,sizeof(item->card.title),"未命名待办");
  copy(item->card.summary, sizeof(item->card.summary), txt(card,"summary"));
  copy(item->card.kind, sizeof(item->card.kind), "task_card");
  copy(item->card.state, sizeof(item->card.state), txt(card,"state"));
  copy(item->card.capture_id, sizeof(item->card.capture_id), local ? txt(record,"capture_id") : txt(card,"capture_id"));
  const char *wanted=!strcmp(state,"done")?"reopen":"done";
  const cJSON *actions=cJSON_GetObjectItemCaseSensitive(card,"actions");
  if (local || (cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(record,"crdt"))&&!cJSON_IsArray(actions)))
    copy(item->card.actions, sizeof(item->card.actions), wanted);
  else
    {
      cJSON *action;
      cJSON_ArrayForEach(action, actions)
        if (cJSON_IsString(action) && !strcmp(action->valuestring,wanted))
          copy(item->card.actions, sizeof(item->card.actions), wanted);
    }
  return true;
}
static void offer(struct buffer_todo_item *items, unsigned *count,
                   unsigned capacity, const struct buffer_todo_item *item,
                   const char *cursor, int direction, bool *more)
{
  int relation = strcmp(item->card.card_id, cursor);
  if (cursor[0] && (direction > 0 ? relation <= 0 : relation >= 0)) return;
  unsigned at = 0;
  while (at < *count && direction * strcmp(items[at].card.card_id, item->card.card_id) < 0) at++;
  if (at < *count && !strcmp(items[at].card.card_id, item->card.card_id)) return;
  if (*count == capacity) *more = true;
  if (at >= capacity) return;
  unsigned end = *count < capacity ? (*count)++ : capacity - 1;
  while (end > at) { items[end] = items[end-1]; end--; }
  items[at] = *item;
}
static int query(const char *cursor, int direction,
                     struct buffer_todo_item *items, unsigned capacity, bool *more,
                     const char *exact)
{
  if (!cursor || !items || !more || !capacity || capacity > BUFFER_TODO_PAGE ||
      (direction != 1 && direction != -1)) return -EINVAL;
  unsigned count = 0; *more = false;
  const char *const dirs[] = {CONFIG_BUFFER_VELA_DATA_DIR "/replicas", CONFIG_BUFFER_VELA_DATA_DIR "/records"};
  for (unsigned source = 0; source < 2; source++)
    {
      DIR *dir = opendir(dirs[source]);
      if (!dir) { if (errno == ENOENT) continue; return -errno; }
      struct dirent *entry;
      for (;;)
        {
          errno=0;entry=readdir(dir);
          if(!entry){int error=errno;closedir(dir);if(error)return -error;break;}
          size_t n = strlen(entry->d_name);
          if (n <= 5 || strcmp(entry->d_name+n-5,".json")) continue;
          char path[384];
          int len = snprintf(path,sizeof(path),"%s/%s",dirs[source],entry->d_name);
          if (len < 0 || (size_t)len >= sizeof(path)) continue;
          errno=0;cJSON *record = buffer_agent_read_json(path, BUFFER_AGENT_JSON_BYTES);
          if (!record) {
            /* Directory entries and the published projection are separate
             * snapshots. A concurrent sync may already have retired this
             * entry; it must not make every other task unreadable. */
            if(errno==ENOENT)continue;
            int ret = errno ? -errno : -EIO; closedir(dir); return ret;
          }
          if (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(record,"deleted")))
            { cJSON_Delete(record); continue; }
          if (!source)
            {
              struct buffer_todo_item value;
              if (task(cJSON_GetObjectItemCaseSensitive(record,"card"), record, false, &value) &&
                  (!exact || !strcmp(value.card.card_id, exact)))
                offer(items,&count,capacity,&value,cursor,direction,more);
            }
          else if (!strcmp(txt(record,"state"),"ready"))
            {
              cJSON *segment;
              cJSON_ArrayForEach(segment,cJSON_GetObjectItemCaseSensitive(record,"segments"))
                {
                  /* Any phone replica, including a tombstone, takes authority. */
                  if (buffer_replica_available(txt(segment,"card_id"))) continue;
                  struct buffer_todo_item value;
                  if (task(segment,record,true,&value) && (!exact || !strcmp(value.card.card_id,exact)))
                    offer(items,&count,capacity,&value,cursor,direction,more);
                }
            }
          cJSON_Delete(record);
        }
    }
  if (direction < 0)
    for (unsigned i = 0; i < count/2; i++)
      { struct buffer_todo_item tmp = items[i]; items[i] = items[count-1-i]; items[count-1-i] = tmp; }
  return (int)count;
}
int buffer_todo_page(const char *cursor, int direction,
                     struct buffer_todo_item *items, unsigned capacity, bool *more)
{ return query(cursor,direction,items,capacity,more,NULL); }
int buffer_todo_get(const char *id, struct buffer_todo_item *item)
{
  if (!id || !item || !buffer_agent_valid_id(id)) return -EINVAL;
  bool more; int ret = query("",1,item,1,&more,id);
  return ret < 0 ? ret : ret == 1 ? 0 : -ENOENT;
}
