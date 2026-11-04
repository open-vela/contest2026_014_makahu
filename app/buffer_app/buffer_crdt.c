/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_crdt.h"
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
static const char *str(const cJSON *o,const char *key)
{ const cJSON *v=cJSON_GetObjectItemCaseSensitive(o,key);return cJSON_IsString(v)?v->valuestring:""; }
static bool integer(const cJSON *v)
{ return cJSON_IsNumber(v) && isfinite(v->valuedouble) && v->valuedouble>=1 &&
    v->valuedouble<=9007199254740991.0 && floor(v->valuedouble)==v->valuedouble; }
static bool actor_valid(const char *actor)
{
  if (!actor || strlen(actor)!=64) return false;
  for (unsigned i=0;i<64;i++) if (!((actor[i]>='0' && actor[i]<='9') || (actor[i]>='a' && actor[i]<='f'))) return false;
  return true;
}
static bool value_valid(const cJSON *value,unsigned depth)
{
  if (!value || depth>8) return false;
  if (cJSON_IsNumber(value) && !isfinite(value->valuedouble)) return false;
  if (cJSON_IsString(value) && strlen(value->valuestring)>24000) return false;
  const cJSON *item;
  cJSON_ArrayForEach(item,value) if (!value_valid(item,depth+1)) return false;
  return true;
}
int buffer_crdt_validate(const cJSON *d)
{
  errno=EINVAL;
  const cJSON *v=cJSON_GetObjectItemCaseSensitive(d,"version");
  const cJSON *fields=cJSON_GetObjectItemCaseSensitive(d,"fields"),*field;
  if (!cJSON_IsObject(d) || cJSON_GetArraySize(d)!=5 || !integer(v) || v->valuedouble!=1 ||
      !buffer_agent_valid_id(str(d,"id")) ||
      (strcmp(str(d,"kind"),"card") && strcmp(str(d,"kind"),"calendar")) ||
      !cJSON_IsBool(cJSON_GetObjectItemCaseSensitive(d,"deleted")) ||
      !cJSON_IsObject(fields) || cJSON_GetArraySize(fields)>32) return -1;
  cJSON_ArrayForEach(field,fields)
    {
      if (!field->string || !field->string[0] || strlen(field->string)>48 ||
          !cJSON_IsObject(field) || cJSON_GetArraySize(field)!=3 ||
          !integer(cJSON_GetObjectItemCaseSensitive(field,"counter")) ||
          !actor_valid(str(field,"actor")) ||
          !value_valid(cJSON_GetObjectItemCaseSensitive(field,"value"),0)) return -1;
      /* Duplicate field names cannot describe a deterministic register map. */
      for (const cJSON *other=field->next;other;other=other->next)
        if (other->string && !strcmp(field->string,other->string)) return -1;
    }
  char *encoded=cJSON_PrintUnformatted(d);
  if(!encoded){errno=ENOMEM;return -1;}
  bool bounded=encoded && strlen(encoded)<=60*1024;free(encoded);
  return bounded?0:-1;
}
uint64_t buffer_crdt_clock(const cJSON *d)
{
  uint64_t clock=0; const cJSON *field;
  cJSON_ArrayForEach(field,cJSON_GetObjectItemCaseSensitive(d,"fields"))
    {
      const cJSON *n=cJSON_GetObjectItemCaseSensitive(field,"counter");
      if (integer(n) && (uint64_t)n->valuedouble>clock) clock=(uint64_t)n->valuedouble;
    }
  return clock;
}
cJSON *buffer_crdt_values(const cJSON *d)
{
  if (buffer_crdt_validate(d)) return NULL;
  cJSON *values=cJSON_CreateObject();const cJSON *field;if(!values){errno=ENOMEM;return NULL;}
  cJSON_ArrayForEach(field,cJSON_GetObjectItemCaseSensitive(d,"fields"))
    {
      cJSON *copy=cJSON_Duplicate(cJSON_GetObjectItemCaseSensitive(field,"value"),true);
      if(!copy||!cJSON_AddItemToObject(values,field->string,copy)){cJSON_Delete(copy);cJSON_Delete(values);errno=ENOMEM;return NULL;}
    }
  return values;
}
cJSON *buffer_crdt_merge(const cJSON *left,const cJSON *right)
{
  if (buffer_crdt_validate(right) || (left && (buffer_crdt_validate(left) ||
      strcmp(str(left,"id"),str(right,"id")) || strcmp(str(left,"kind"),str(right,"kind"))))) {if(errno!=ENOMEM)errno=EINVAL;return NULL;}
  if (!left) {cJSON *copy=cJSON_Duplicate(right,true);if(!copy)errno=ENOMEM;return copy;}
  cJSON *merged=cJSON_Duplicate(left,true), *fields=cJSON_GetObjectItemCaseSensitive(merged,"fields");
  if(!merged){errno=ENOMEM;return NULL;}
  const cJSON *incoming;
  cJSON_ArrayForEach(incoming,cJSON_GetObjectItemCaseSensitive(right,"fields"))
    {
      const cJSON *old=cJSON_GetObjectItemCaseSensitive(fields,incoming->string);
      double clock=cJSON_GetObjectItemCaseSensitive(incoming,"counter")->valuedouble;
      double previous=old ? cJSON_GetObjectItemCaseSensitive(old,"counter")->valuedouble:0;
      int order=clock>previous ? 1:clock<previous ? -1:strcmp(str(incoming,"actor"),str(old,"actor"));
      if (!order && !cJSON_Compare(cJSON_GetObjectItemCaseSensitive(old,"value"),cJSON_GetObjectItemCaseSensitive(incoming,"value"),true))
        {cJSON_Delete(merged);errno=EEXIST;return NULL;}
      if (order>0)
        {
          cJSON *copy=cJSON_Duplicate(incoming,true);
          if(!copy){cJSON_Delete(merged);errno=ENOMEM;return NULL;}
          cJSON_DeleteItemFromObjectCaseSensitive(fields,incoming->string);
          if(!cJSON_AddItemToObject(fields,incoming->string,copy)){cJSON_Delete(copy);cJSON_Delete(merged);errno=ENOMEM;return NULL;}
        }
    }
  if (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(right,"deleted"))){
    cJSON *tombstone=cJSON_CreateBool(true);
    if(!tombstone||!cJSON_ReplaceItemInObjectCaseSensitive(merged,"deleted",tombstone)){cJSON_Delete(tombstone);cJSON_Delete(merged);errno=ENOMEM;return NULL;}
  }
  if (buffer_crdt_validate(merged)) {cJSON_Delete(merged);return NULL;} return merged;
}
cJSON *buffer_crdt_update(const cJSON *previous,const char *id,const char *kind,
                           const cJSON *changes,const char *actor,bool deleted)
{
  if (!id || !kind || !buffer_agent_valid_id(id) || !actor_valid(actor) || !cJSON_IsObject(changes) ||
      cJSON_GetArraySize(changes)>32 || (strcmp(kind,"card") && strcmp(kind,"calendar"))) {errno=EINVAL;return NULL;}
  if(previous&&buffer_crdt_validate(previous))return NULL;
  if(previous&&(strcmp(str(previous,"id"),id)||strcmp(str(previous,"kind"),kind))) {errno=EINVAL;return NULL;}
  const cJSON *value;
  cJSON_ArrayForEach(value,changes){
    if(!value->string||!value->string[0]||strlen(value->string)>48||!value_valid(value,0)){errno=EINVAL;return NULL;}
    for(const cJSON *other=value->next;other;other=other->next)
      if(other->string&&!strcmp(value->string,other->string)){errno=EINVAL;return NULL;}
  }
  cJSON *doc=previous?cJSON_Duplicate(previous,true):cJSON_CreateObject();
  if(!doc){errno=ENOMEM;return NULL;}
  if (!previous)
    {
      if(!cJSON_AddNumberToObject(doc,"version",1)||!cJSON_AddStringToObject(doc,"id",id)||
         !cJSON_AddStringToObject(doc,"kind",kind)||!cJSON_AddBoolToObject(doc,"deleted",false))goto nomem;
      cJSON *created=cJSON_CreateObject();
      if(!created||!cJSON_AddItemToObject(doc,"fields",created)){cJSON_Delete(created);goto nomem;}
    }
  uint64_t current=buffer_crdt_clock(doc),clock=0;
  cJSON *fields=cJSON_GetObjectItemCaseSensitive(doc,"fields");
  cJSON_ArrayForEach(value,changes)
    {
      cJSON *old=cJSON_GetObjectItemCaseSensitive(fields,value->string);
      if (old && cJSON_Compare(cJSON_GetObjectItemCaseSensitive(old,"value"),value,true)) continue;
      // Unchanged retries and a tombstone alone need no new register clock.
      if(!clock){if(current>=9007199254740991ULL){cJSON_Delete(doc);errno=EOVERFLOW;return NULL;}clock=current+1;}
      cJSON *field=cJSON_CreateObject(),*copy=cJSON_Duplicate(value,true);
      if(!field||!copy||!cJSON_AddNumberToObject(field,"counter",(double)clock)||!cJSON_AddStringToObject(field,"actor",actor)){
        cJSON_Delete(field);cJSON_Delete(copy);goto nomem;
      }
      if(!cJSON_AddItemToObject(field,"value",copy)){cJSON_Delete(copy);cJSON_Delete(field);goto nomem;}
      cJSON_DeleteItemFromObjectCaseSensitive(fields,value->string);
      if(!cJSON_AddItemToObject(fields,value->string,field)){cJSON_Delete(field);goto nomem;}
    }
  if (deleted&&!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(doc,"deleted"))){
    cJSON *tombstone=cJSON_CreateBool(true);
    if(!tombstone||!cJSON_ReplaceItemInObjectCaseSensitive(doc,"deleted",tombstone)){cJSON_Delete(tombstone);goto nomem;}
  }
  if (buffer_crdt_validate(doc)) {cJSON_Delete(doc);return NULL;} return doc;
nomem:
  // Never return a partially updated register map after an allocation failure.
  cJSON_Delete(doc);errno=ENOMEM;return NULL;
}
