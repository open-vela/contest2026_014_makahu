/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_index.h"
#include <mbedtls/sha256.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define ROOT CONFIG_BUFFER_VELA_DATA_DIR "/event-journal/remote-index"
#define META ROOT "/index.json"
#define WAL ROOT "/pending.json"
#define NODE_BYTES 4096
#define WAL_BYTES (1024 * 1024)
#define MAX_COUNT 9007199254740991ULL
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j, const char *key, uint64_t *out) {
  const cJSON *v = cJSON_GetObjectItemCaseSensitive(j, key);
  if (!cJSON_IsNumber(v) || !isfinite(v->valuedouble) || v->valuedouble < 0 ||
      v->valuedouble > MAX_COUNT || floor(v->valuedouble) != v->valuedouble)
    return false;
  *out = (uint64_t)v->valuedouble;
  return true;
}
static bool identity(const char *s, size_t max, bool colon) {
  size_t n = strlen(s);
  if (!n || n > max)
    return false;
  for (size_t i = 0; i < n; i++)
    if (!((s[i] >= 'A' && s[i] <= 'Z') || (s[i] >= 'a' && s[i] <= 'z') ||
          (s[i] >= '0' && s[i] <= '9') || strchr("_.-", s[i]) ||
          (colon && s[i] == ':')))
      return false;
  return true;
}
static bool prefix(const char *s)
{size_t n=strlen(s);if(n>64)return false;for(size_t i=0;i<n;i++)if(!strchr("0123456789abcdef",s[i]))return false;return true;}
static bool hash(const char *s){return strlen(s)==64&&prefix(s);}
static int digest(const char *raw,size_t length,char out[65])
{unsigned char bytes[32];if(mbedtls_sha256((const unsigned char *)raw,length,bytes,0))return -EIO;
  for (unsigned i = 0; i < 32; i++)
    snprintf(out + 2 * i, 3, "%02x", bytes[i]);
  return 0;
}
static void release(char *raw)
{if(raw){volatile char *p=raw;size_t n=strlen(raw);while(n--)*p++=0;free(raw);}}
static int read_json(const char *path,size_t limit,cJSON **out)
{errno=0;*out=buffer_agent_read_json_plain(path,limit);return *out?0:errno?-errno:-EIO;}
static int write_json(const char *path,const cJSON *j,size_t limit)
{char *raw=cJSON_PrintUnformatted(j);if(!raw)return -ENOMEM;int ret=buffer_agent_atomic_bytes(path,raw,strlen(raw),limit);release(raw);return ret;}
static int node_path(char path[320],const char *key)
{if(!prefix(key))return -EIO;int n=snprintf(path,320,ROOT "/n-%s.json",key);return n<0||n>=320?-ENAMETOOLONG:0;}
static bool ref_valid(const cJSON *ref)
{return cJSON_IsObject(ref)&&cJSON_GetArraySize(ref)==2&&cJSON_IsString(cJSON_GetObjectItemCaseSensitive(ref,"prefix"))&&
  prefix(text(ref,"prefix"))&&hash(text(ref,"sha256"));}
static bool entry_valid(const cJSON *entry)
{uint64_t seq=0;return cJSON_IsObject(entry)&&cJSON_GetArraySize(entry)==4&&identity(text(entry,"event_id"),160,true)&&
  identity(text(entry,"source_id"),96,false)&&number(entry,"source_sequence",&seq)&&seq>0&&seq<MAX_COUNT&&hash(text(entry,"fingerprint"));}
static bool meta_valid(const cJSON *meta)
{uint64_t version=0,count=0;const cJSON *root=cJSON_GetObjectItemCaseSensitive(meta,"root");return cJSON_IsObject(meta)&&
  cJSON_GetArraySize(meta)==4&&number(meta,"schema_version",&version)&&version==1&&number(meta,"count",&count)&&
  cJSON_IsBool(cJSON_GetObjectItemCaseSensitive(meta,"ready"))&&(count?ref_valid(root):cJSON_IsNull(root));}
static unsigned nibble(char c){return (unsigned)(c<='9'?c-'0':c-'a'+10);}
static bool node_valid(const cJSON *node)
{
  if(!cJSON_IsObject(node)||cJSON_GetArraySize(node)!=2||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(node,"prefix")))return false;
  const char *key=text(node,"prefix");if(!prefix(key))return false;size_t depth=strlen(key);
  if(depth==64){const cJSON *entry=cJSON_GetObjectItemCaseSensitive(node,"entry");char actual[65];
    return entry_valid(entry)&&!digest(text(entry,"event_id"),strlen(text(entry,"event_id")),actual)&&!strcmp(actual,key);}
  const cJSON *children=cJSON_GetObjectItemCaseSensitive(node,"children");if(!cJSON_IsArray(children)||cJSON_GetArraySize(children)!=16)return false;
  unsigned count=0;for(unsigned i=0;i<16;i++){
    const cJSON *ref=cJSON_GetArrayItem(children,i);if(cJSON_IsNull(ref))continue;
    if (!ref_valid(ref))
      return false;
    const char *child = text(ref, "prefix");
    size_t n = strlen(child);
    if (n <= depth || strncmp(child, key, depth) || nibble(child[depth]) != i)
      return false;
    count++;
  }
  return count>=2;
}
static int load_node(const cJSON *ref,cJSON **node)
{
  *node=NULL;if(!ref_valid(ref))return -EIO;char path[320],actual[65],*raw=NULL;size_t bytes=0;
  int ret=node_path(path,text(ref,"prefix"));if(!ret)ret=buffer_agent_read_bytes(path,NODE_BYTES,&raw,&bytes);
  if (!ret)
    ret = digest(raw, bytes, actual);
  if (!ret && strcmp(actual, text(ref, "sha256")))
    ret = -EIO;
  if(!ret){*node=buffer_agent_parse_json(raw);if(!node_valid(*node)||strcmp(text(*node,"prefix"),text(ref,"prefix")))ret=-EIO;}
  release(raw);if(ret){cJSON_Delete(*node);*node=NULL;}return ret==-ENOENT?-EIO:ret;
}
static cJSON *reference(const char *key,const char *sha)
{cJSON *j=cJSON_CreateObject();if(j&&cJSON_AddStringToObject(j,"prefix",key)&&cJSON_AddStringToObject(j,"sha256",sha))return j;cJSON_Delete(j);return NULL;}
static int change(cJSON *changes,const cJSON *node,cJSON **ref)
{
  *ref=NULL;if(!node_valid(node)||cJSON_GetArraySize(changes)>=65)return -EIO;
  char sha[65],*raw=cJSON_PrintUnformatted(node);if(!raw)return -ENOMEM;
  int ret=strlen(raw)>NODE_BYTES?-EFBIG:digest(raw,strlen(raw),sha);
  cJSON *item=NULL;if(!ret){item=cJSON_CreateObject();*ref=reference(text(node,"prefix"),sha);
    if(!item||!*ref||!cJSON_AddStringToObject(item,"prefix",text(node,"prefix"))||!cJSON_AddStringToObject(item,"raw",raw)||
       !cJSON_AddStringToObject(item,"sha256",sha)||!cJSON_AddItemToArray(changes,item))ret=-ENOMEM;else item=NULL;}
  release(raw);cJSON_Delete(item);if(ret){cJSON_Delete(*ref);*ref=NULL;}return ret;
}
static int leaf(cJSON *changes,const char *key,const cJSON *entry,cJSON **ref)
{
  cJSON *node=cJSON_CreateObject(),*copy=cJSON_Duplicate(entry,true);int ret=-ENOMEM;
  if(node&&copy&&cJSON_AddStringToObject(node,"prefix",key)&&cJSON_AddItemToObject(node,"entry",copy)){copy=NULL;ret=change(changes,node,ref);}
  cJSON_Delete(copy);cJSON_Delete(node);return ret;
}
static int insert(const cJSON *old,const char *key,const cJSON *entry,cJSON *changes,unsigned level,cJSON **out,bool *added)
{
  *out=NULL;if(level>64)return -EIO;
  cJSON *node=NULL;int ret=load_node(old,&node);if(ret)return ret;
  const char *previous=text(node,"prefix");size_t n=strlen(previous),common=0;
  while(common<n&&previous[common]==key[common])common++;
  if(common<n){
    char shared[65];memcpy(shared,key,common);shared[common]=0;
    cJSON *branch=cJSON_CreateObject(),*children=cJSON_CreateArray(),*new_leaf=NULL,*old_copy=cJSON_Duplicate(old,true);
    if(!branch||!children||!old_copy){ret=-ENOMEM;goto split_done;}
    for(unsigned i=0;i<16;i++)if(!cJSON_AddItemToArray(children,cJSON_CreateNull())){ret=-ENOMEM;goto split_done;}
    ret=leaf(changes,key,entry,&new_leaf);if(ret)goto split_done;
    if(!cJSON_ReplaceItemInArray(children,nibble(key[common]),new_leaf)){ret=-ENOMEM;goto split_done;}new_leaf=NULL;
    if(!cJSON_ReplaceItemInArray(children,nibble(previous[common]),old_copy)){ret=-ENOMEM;goto split_done;}old_copy=NULL;
    if(!cJSON_AddStringToObject(branch,"prefix",shared)||!cJSON_AddItemToObject(branch,"children",children)){ret=-ENOMEM;goto split_done;}children=NULL;
    ret=change(changes,branch,out);if(!ret)*added=true;
split_done:
    cJSON_Delete(branch);cJSON_Delete(children);cJSON_Delete(new_leaf);cJSON_Delete(old_copy);
  }else if(n==64){
    if(!cJSON_Compare(cJSON_GetObjectItemCaseSensitive(node,"entry"),entry,true))ret=-EEXIST;
    else{*out=cJSON_Duplicate(old,true);ret=*out?0:-ENOMEM;}
  }else{
    cJSON *children=cJSON_GetObjectItemCaseSensitive(node,"children");unsigned slot=nibble(key[n]);
    cJSON *child=cJSON_GetArrayItem(children,slot),*next=NULL;
    if(cJSON_IsNull(child)){ret=leaf(changes,key,entry,&next);if(!ret)*added=true;}
    else ret=insert(child,key,entry,changes,level+1,&next,added);
    if(!ret&&*added){if(!cJSON_ReplaceItemInArray(children,slot,next))ret=-ENOMEM;else{next=NULL;ret=change(changes,node,out);}}
    else if(!ret){*out=cJSON_Duplicate(old,true);if(!*out)ret=-ENOMEM;}
    cJSON_Delete(next);
  }
  cJSON_Delete(node);return ret;
}
static int recover(void)
{
  cJSON *wal=NULL,*current=NULL;int ret=read_json(WAL,WAL_BYTES,&wal);if(ret==-ENOENT)return 0;if(ret)return ret;
  uint64_t version=0,before_count=0,after_count=0;
  const cJSON *before=cJSON_GetObjectItemCaseSensitive(wal,"before"),*after=cJSON_GetObjectItemCaseSensitive(wal,"after"),
    *nodes=cJSON_GetObjectItemCaseSensitive(wal,"nodes");
  if(!cJSON_IsObject(wal)||cJSON_GetArraySize(wal)!=4||!number(wal,"schema_version",&version)||version!=1||
     !meta_valid(before)||!meta_valid(after)||!cJSON_IsArray(nodes)||cJSON_GetArraySize(nodes)>65||
     !number(before,"count",&before_count)||!number(after,"count",&after_count)||after_count<before_count||after_count>before_count+1){ret=-EIO;goto done;}
  int count=cJSON_GetArraySize(nodes);
  if((count==0&&(after_count!=before_count||!cJSON_Compare(cJSON_GetObjectItemCaseSensitive(before,"root"),cJSON_GetObjectItemCaseSensitive(after,"root"),true)||
        !cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(after,"ready"))))||
     (count&&(after_count!=before_count+1||!cJSON_Compare(cJSON_GetObjectItemCaseSensitive(before,"ready"),cJSON_GetObjectItemCaseSensitive(after,"ready"),true)))){ret=-EIO;goto done;}
  ret=read_json(META,NODE_BYTES,&current);if(ret){if(ret==-ENOENT)ret=-EIO;goto done;}
  if(!cJSON_Compare(current,before,true)&&!cJSON_Compare(current,after,true)){ret=-ESTALE;goto done;}
  const cJSON *item;cJSON_ArrayForEach(item,nodes){
    if(!cJSON_IsObject(item)||cJSON_GetArraySize(item)!=3||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(item,"prefix"))||
       !prefix(text(item,"prefix"))||!hash(text(item,"sha256"))||!cJSON_IsString(cJSON_GetObjectItemCaseSensitive(item,"raw"))){ret=-EIO;goto done;}
    const char *raw=text(item,"raw");char actual[65];ret=strlen(raw)>NODE_BYTES?-EFBIG:digest(raw,strlen(raw),actual);if(ret)goto done;
    cJSON *node=buffer_agent_parse_json(raw);bool valid=node_valid(node)&&!strcmp(text(node,"prefix"),text(item,"prefix"))&&!strcmp(actual,text(item,"sha256"));
    cJSON_Delete(node);if(!valid){ret=-EIO;goto done;}
    for(const cJSON *other=item->next;other;other=other->next)if(!strcmp(text(item,"prefix"),text(other,"prefix"))){ret=-EIO;goto done;}
  }
  if(count){const cJSON *last=cJSON_GetArrayItem(nodes,count-1),*root=cJSON_GetObjectItemCaseSensitive(after,"root");
    if(strcmp(text(last,"prefix"),text(root,"prefix"))||strcmp(text(last,"sha256"),text(root,"sha256"))){ret=-EIO;goto done;}}
  cJSON_ArrayForEach(item,nodes){char path[320];ret=node_path(path,text(item,"prefix"));
    if (!ret)
      ret = buffer_agent_atomic_bytes(path, text(item, "raw"),
                                      strlen(text(item, "raw")), NODE_BYTES);
    if (ret)
      goto done;
  }
  ret=write_json(META,after,NODE_BYTES);if(ret)goto done;
  if(unlink(WAL)&&errno!=ENOENT){ret=-errno;goto done;}ret=buffer_agent_sync_parent(WAL);
done:
  cJSON_Delete(wal);cJSON_Delete(current);return ret;
}
static int open_meta(cJSON **meta)
{
  *meta=NULL;int ret=buffer_agent_private_directory(ROOT);if(ret)return ret;
  ret=recover();if(ret)return ret;
  ret=read_json(META,NODE_BYTES,meta);
  if(ret==-ENOENT){
    DIR *dir=opendir(ROOT);if(!dir)return -errno;
    for(;;){errno=0;struct dirent *entry=readdir(dir);if(!entry){ret=errno?-errno:0;break;}
      if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,"..")||!strcmp(entry->d_name,"index.json.tmp"))continue;
      ret=-EIO;break;
    }
    if (closedir(dir) && !ret)
      ret = -errno;
    if (ret)
      return ret;
    *meta=cJSON_CreateObject();bool built=*meta&&cJSON_AddNumberToObject(*meta,"schema_version",1)&&cJSON_AddNumberToObject(*meta,"count",0)&&
      cJSON_AddBoolToObject(*meta,"ready",false)&&cJSON_AddNullToObject(*meta,"root");ret=built?write_json(META,*meta,NODE_BYTES):-ENOMEM;
  }
  if(!ret&&!meta_valid(*meta))ret=-EIO;
  if(!ret&&!cJSON_IsNull(cJSON_GetObjectItemCaseSensitive(*meta,"root"))){cJSON *node=NULL;ret=load_node(cJSON_GetObjectItemCaseSensitive(*meta,"root"),&node);cJSON_Delete(node);}
  if(ret){cJSON_Delete(*meta);*meta=NULL;}return ret;
}
static int commit(const cJSON *before,cJSON *after,cJSON *nodes)
{
  cJSON *wal=cJSON_CreateObject(),*copy=cJSON_Duplicate(before,true);int ret=-ENOMEM;
  if(wal&&copy&&cJSON_AddNumberToObject(wal,"schema_version",1)&&cJSON_AddItemToObject(wal,"before",copy)){
    copy=NULL;if(cJSON_AddItemToObject(wal,"after",after)){after=NULL;
      if(cJSON_AddItemToObject(wal,"nodes",nodes)){nodes=NULL;ret=write_json(WAL,wal,WAL_BYTES);if(!ret)ret=recover();}}
  }
  cJSON_Delete(wal);cJSON_Delete(copy);cJSON_Delete(after);cJSON_Delete(nodes);return ret;
}
int buffer_source_index_open(bool *ready,uint64_t *count)
{
  if (!ready || !count)
    return -EINVAL;
  *ready = false;
  *count = 0;
  cJSON *meta = NULL;
  int ret = open_meta(&meta);
  if(!ret){*ready=cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(meta,"ready"));if(!number(meta,"count",count))ret=-EIO;}
  cJSON_Delete(meta);return ret;
}
int buffer_source_index_get(const char *event_id,cJSON **entry)
{
  if (!entry)
    return -EINVAL;
  *entry = NULL;
  if (!event_id || !identity(event_id, 160, true))
    return -EINVAL;
  char key[65];int ret=digest(event_id,strlen(event_id),key);if(ret)return ret;
  cJSON *meta=NULL,*node=NULL,*ref=NULL;ret=open_meta(&meta);if(ret)return ret;
  if(!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(meta,"ready"))){ret=-EAGAIN;goto done;}
  ref=cJSON_Duplicate(cJSON_GetObjectItemCaseSensitive(meta,"root"),true);if(!ref){ret=-ENOMEM;goto done;}
  for(unsigned level=0;level<=64;level++){
    if (cJSON_IsNull(ref))
      break;
    ret = load_node(ref, &node);
    if (ret)
      break;
    const char *p=text(node,"prefix");size_t n=strlen(p);if(strncmp(p,key,n))break;
    if(n==64){const cJSON *found=cJSON_GetObjectItemCaseSensitive(node,"entry");
      if(strcmp(text(found,"event_id"),event_id)){ret=-EEXIST;break;}*entry=cJSON_Duplicate(found,true);if(!*entry)ret=-ENOMEM;break;}
    const cJSON *child=cJSON_GetArrayItem(cJSON_GetObjectItemCaseSensitive(node,"children"),nibble(key[n]));
    cJSON *next=cJSON_Duplicate(child,true);if(!next){ret=-ENOMEM;break;}cJSON_Delete(ref);ref=next;cJSON_Delete(node);node=NULL;
    if(level==64)ret=-EIO;
  }
done:
  cJSON_Delete(meta);cJSON_Delete(node);cJSON_Delete(ref);return ret;
}
int buffer_source_index_put(const cJSON *entry)
{
  if (!entry_valid(entry))
    return -EINVAL;
  char key[65];
  int ret =
      digest(text(entry, "event_id"), strlen(text(entry, "event_id")), key);
  if (ret)
    return ret;
  cJSON *meta=NULL,*after=NULL,*nodes=NULL,*root=NULL;ret=open_meta(&meta);if(ret)return ret;
  uint64_t count=0;if(!number(meta,"count",&count)){ret=-EIO;goto done;}
  nodes=cJSON_CreateArray();after=cJSON_Duplicate(meta,true);if(!nodes||!after){ret=-ENOMEM;goto done;}
  const cJSON *previous=cJSON_GetObjectItemCaseSensitive(meta,"root");bool added=false;
  if(cJSON_IsNull(previous)){ret=leaf(nodes,key,entry,&root);added=!ret;}
  else ret=insert(previous,key,entry,nodes,0,&root,&added);
  if(ret||!added)goto done;
  if(count>=MAX_COUNT){ret=-EOVERFLOW;goto done;}
  if(!cJSON_ReplaceItemInObjectCaseSensitive(after,"count",cJSON_CreateNumber((double)(count+1)))){ret=-ENOMEM;goto done;}
  if(!cJSON_ReplaceItemInObjectCaseSensitive(after,"root",root)){ret=-ENOMEM;goto done;}root=NULL;
  ret=commit(meta,after,nodes);after=NULL;nodes=NULL;
done:
  cJSON_Delete(meta);cJSON_Delete(after);cJSON_Delete(nodes);cJSON_Delete(root);return ret;
}
int buffer_source_index_finish(uint64_t expected_count)
{
  cJSON *meta=NULL,*after=NULL,*nodes=NULL;int ret=open_meta(&meta);if(ret)return ret;uint64_t count=0;
  if(!number(meta,"count",&count)||count!=expected_count){ret=-EIO;goto done;}
  if(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(meta,"ready")))goto done;
  after=cJSON_Duplicate(meta,true);nodes=cJSON_CreateArray();
  if(!after||!nodes||!cJSON_ReplaceItemInObjectCaseSensitive(after,"ready",cJSON_CreateBool(true))){ret=-ENOMEM;goto done;}
  ret=commit(meta,after,nodes);after=NULL;nodes=NULL;
done:
  cJSON_Delete(meta);cJSON_Delete(after);cJSON_Delete(nodes);return ret;
}
#else
int buffer_source_index_open(bool *ready,uint64_t *count){if(ready)*ready=false;if(count)*count=0;return -ENOTSUP;}
int buffer_source_index_get(const char *event_id,cJSON **entry){(void)event_id;if(entry)*entry=NULL;return -ENOTSUP;}
int buffer_source_index_put(const cJSON *entry){(void)entry;return -ENOTSUP;}
int buffer_source_index_finish(uint64_t expected_count){(void)expected_count;return -ENOTSUP;}
#endif
