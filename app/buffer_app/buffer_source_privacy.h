/* SPDX-License-Identifier: Apache-2.0 */
#ifndef BUFFER_SOURCE_PRIVACY_H
#define BUFFER_SOURCE_PRIVACY_H
#include <netutils/cJSON.h>
#include <math.h>
#include <stdbool.h>
#include <string.h>

/* Native Vela has no grant to forward nonempty capture context. Use the same
 * rule for source originals and the compatibility CRDT transport. Empty CRDT
 * registers are safe only when their wrapper has the exact register shape. */
static inline bool buffer_source_peer_valid(const char *peer)
{
  if (!peer || strlen(peer)!=64) return false;
  for (unsigned i=0;i<64;i++) if (!strchr("0123456789abcdef",peer[i])) return false;
  return true;
}
static inline bool buffer_source_private_context(const cJSON *json)
{
  const cJSON *item;
  cJSON_ArrayForEach(item,json)
    {
      if (item->string && (!strcmp(item->string,"capture_context") ||
          !strcmp(item->string,"context_summary") || !strcmp(item->string,"context")) &&
          !cJSON_IsNull(item) && !(cJSON_IsString(item) && !item->valuestring[0]))
        {
          const cJSON *counter=cJSON_GetObjectItemCaseSensitive(item,"counter");
          const cJSON *actor=cJSON_GetObjectItemCaseSensitive(item,"actor");
          const cJSON *value=cJSON_GetObjectItemCaseSensitive(item,"value");
          bool empty=cJSON_IsObject(item) && cJSON_GetArraySize(item)==3 &&
            cJSON_IsNumber(counter) && isfinite(counter->valuedouble) &&
            counter->valuedouble>=1 && counter->valuedouble<=9007199254740991.0 &&
            floor(counter->valuedouble)==counter->valuedouble && cJSON_IsString(actor) &&
            buffer_source_peer_valid(actor->valuestring) && value &&
            (cJSON_IsNull(value) || (cJSON_IsString(value) && !value->valuestring[0]));
          if (!empty) return true;
        }
      if ((cJSON_IsObject(item) || cJSON_IsArray(item)) && buffer_source_private_context(item)) return true;
    }
  return false;
}
static inline bool buffer_source_replica_may_share(const cJSON *record,const char *peer)
{
  const cJSON *owner=cJSON_GetObjectItemCaseSensitive(record,"origin");
  return buffer_source_peer_valid(peer) && cJSON_IsObject(record) &&
    cJSON_IsString(owner) && !strcmp(owner->valuestring,peer) &&
    !buffer_source_private_context(record);
}
#endif
