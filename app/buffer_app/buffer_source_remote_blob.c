/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_remote_blob.h"
#include "buffer_source_remote.h"
#include "buffer_source_envelope.h"
#include <mbedtls/base64.h>
#include <mbedtls/sha256.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifdef CONFIG_BUFFER_VELA_SOURCE_JOURNAL
#define ROOT CONFIG_BUFFER_VELA_DATA_DIR "/event-journal/remote-blobs"
#define MAX_BYTES (2u*1024u*1024u)
#define MAX_COUNTER 9007199254740991ULL
struct binding_s {
  char peer[65],source[97],record_hash[65],blob_hash[65],event_id[161],content_type[32];
  uint64_t sequence,total;
};
#define BLOB_SESSIONS 4
struct blob_session_s {
  char peer[65];
  unsigned generation;
  struct binding_s receiving;
  int receiving_fd;
  uint64_t receiving_used,receiving_touch;
  unsigned receiving_generation;
};
static struct blob_session_s transfers[BLOB_SESSIONS]={
  {.receiving_fd=-1},{.receiving_fd=-1},{.receiving_fd=-1},{.receiving_fd=-1}
};
static struct blob_session_s *transfer=&transfers[0];
static const char *partial_path(void)
{
  static char path[sizeof(ROOT)+32];
  snprintf(path,sizeof(path),ROOT "/incoming-%u.partial",(unsigned)(transfer-transfers));return path;
}
static void wipe(void *data,size_t length)
{volatile unsigned char *p=data;while(length--)*p++=0;}
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool number(const cJSON *j,const char *key,uint64_t min,uint64_t max,uint64_t *out)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);
  if(!cJSON_IsNumber(v)||!isfinite(v->valuedouble)||v->valuedouble<min||v->valuedouble>max||floor(v->valuedouble)!=v->valuedouble)return false;
  *out=(uint64_t)v->valuedouble;return true;}
static bool hex(const char *s)
{if(strlen(s)!=64)return false;for(unsigned i=0;i<64;i++)if(!strchr("0123456789abcdef",s[i]))return false;return true;}
static bool source_id(const char *s) {
  size_t n = strlen(s);
  if (!n || n > 96)
    return false;
  for (size_t i = 0; i < n; i++)
    if (!((s[i] >= 'A' && s[i] <= 'Z') || (s[i] >= 'a' && s[i] <= 'z') ||
          (s[i] >= '0' && s[i] <= '9') || strchr("_.-", s[i])))
      return false;
  return true;
}
static uint64_t now_ms(void)
{struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now))return 0;return (uint64_t)now.tv_sec*1000+now.tv_nsec/1000000;}
static bool authorized(void *context)
{const struct buffer_oobe_authorization_s *auth=context;return auth&&auth->authorize&&!auth->authorize(auth->context,BUFFER_OOBE_GET_STATE);}
void buffer_source_remote_blob_reset(void)
{
  if(transfer->receiving_fd>=0){close(transfer->receiving_fd);unlink(partial_path());}
  transfer->receiving_fd=-1;transfer->receiving_used=transfer->receiving_touch=0;transfer->receiving_generation=0;wipe(&transfer->receiving,sizeof(transfer->receiving));
}
void buffer_source_remote_blob_reset_all(void)
{
  struct blob_session_s *saved=transfer;
  for(unsigned i=0;i<BLOB_SESSIONS;i++){transfer=&transfers[i];buffer_source_remote_blob_reset();transfer->peer[0]=0;transfer->generation=0;}
  transfer=saved;
}
void buffer_source_remote_blob_close_session(const char *peer,unsigned generation)
{
  if (!peer)
    return;
  struct blob_session_s *saved = transfer;
  for(unsigned i=0;i<BLOB_SESSIONS;i++)if(transfers[i].generation==generation&&!strcmp(transfers[i].peer,peer)){
    transfer=&transfers[i];buffer_source_remote_blob_reset();transfer->peer[0]=0;transfer->generation=0;
  }
  transfer=saved;
}
static bool maintain_sessions(void)
{
  struct blob_session_s *saved=transfer;uint64_t now=now_ms();bool retained=false;
  for(unsigned i=0;i<BLOB_SESSIONS;i++){
    transfer=&transfers[i];
    if(transfer->receiving_fd>=0&&(!now||!transfer->receiving_touch||now<transfer->receiving_touch||now-transfer->receiving_touch>=30000))buffer_source_remote_blob_reset();
    retained=retained||transfer->receiving_fd>=0;
  }
  transfer=saved;return retained;
}
bool buffer_source_remote_blob_session_idle(const char *peer,unsigned generation,bool active)
{if(!active)buffer_source_remote_blob_close_session(peer,generation);return maintain_sessions();}
bool buffer_source_remote_blob_idle(unsigned generation,bool active)
{
  if(!active){buffer_source_remote_blob_reset_all();return false;}
  struct blob_session_s *saved=transfer;
  for(unsigned i=0;i<BLOB_SESSIONS;i++)if(transfers[i].generation!=generation){transfer=&transfers[i];buffer_source_remote_blob_reset();transfer->peer[0]=0;transfer->generation=0;}
  transfer=saved;return maintain_sessions();
}
static bool select_session(const char *peer,unsigned generation)
{
  struct blob_session_s *empty=NULL;
  for(unsigned i=0;i<BLOB_SESSIONS;i++){
    struct blob_session_s *slot=&transfers[i];
    if(slot->generation==generation&&!strcmp(slot->peer,peer)){transfer=slot;return true;}
    if(!empty&&slot->receiving_fd<0)empty=slot;
  }
  if (!empty)
    return false;
  transfer = empty;
  buffer_source_remote_blob_reset();
  snprintf(transfer->peer,sizeof(transfer->peer),"%s",peer);transfer->generation=generation;return true;
}
static int sha(const void *data,size_t bytes,char hash[65])
{unsigned char digest[32];if(mbedtls_sha256(data,bytes,digest,0))return -EIO;
  for (unsigned i = 0; i < 32; i++)
    snprintf(hash + 2 * i, 3, "%02x", digest[i]);
  wipe(digest, sizeof(digest));
  return 0;
}
static int binding(const struct buffer_oobe_authorization_s *auth,const char *peer,const cJSON *request,struct binding_s *out)
{
  memset(out,0,sizeof(*out));const char *source=text(request,"source_id"),*record=text(request,"record_sha256"),*blob=text(request,"blob_sha256");
  if(!source_id(source)||!hex(peer)||!hex(record)||!hex(blob)||
     !number(request,"source_sequence",1,MAX_COUNTER-1,&out->sequence)||!number(request,"total",1,MAX_BYTES,&out->total))return -EINVAL;
  char *raw=NULL,hash[65];size_t bytes=0;cJSON *envelope=NULL;
  int ret=buffer_source_remote_owned_read(peer,source,out->sequence,authorized,(void *)auth,&raw,&bytes);
  if(!ret)ret=sha(raw,bytes,hash);
  if(!ret&&strcmp(hash,record))ret=-EEXIST;
  if(!ret)ret=buffer_source_envelope_decode(raw,bytes,&envelope);
  if(raw){wipe(raw,bytes);free(raw);}if(ret){cJSON_Delete(envelope);return ret;}
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(envelope,"event"),*payload=cJSON_GetObjectItemCaseSensitive(event,"payload");
  const cJSON *description=cJSON_GetObjectItemCaseSensitive(payload,"blob");
  const char *type=text(event,"type"),*content=text(description,"content_type"),*mode=text(description,"mode");uint64_t value=0;
  bool native_capture=!strcmp(type,"CaptureCreated")||!strcmp(type,"CaptureBlobDescribed");
  bool valid=number(event,"schema_version",1,1,&value)&&(native_capture||!strcmp(type,"LegacyAudioSnapshot"))&&
    (!native_capture||cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(payload,"local_original")))&&
    cJSON_IsObject(description)&&cJSON_GetArraySize(description)==6&&!strcmp(text(description,"sha256"),blob)&&
    number(description,"bytes",1,MAX_BYTES,&value)&&value==out->total&&
    (!strcmp(content,"audio/wav")||!strcmp(content,"audio/mp4")||!strcmp(content,"application/octet-stream"))&&
    (!strcmp(mode,"normal")||!strcmp(mode,"focus")||!strcmp(mode,"bedtime")||!strcmp(mode,"rabbit_hole"))&&
    number(description,"created_at",0,MAX_COUNTER,&value)&&number(description,"duration_ms",0,4294967295ULL,&value);
  if(valid){
    snprintf(out->peer,sizeof(out->peer),"%s",peer);snprintf(out->source,sizeof(out->source),"%s",source);
    snprintf(out->record_hash,sizeof(out->record_hash),"%s",record);snprintf(out->blob_hash,sizeof(out->blob_hash),"%s",blob);
    snprintf(out->event_id,sizeof(out->event_id),"%s",text(event,"event_id"));snprintf(out->content_type,sizeof(out->content_type),"%s",content);
  }
  cJSON_Delete(envelope);return valid?0:-EEXIST;
}
static int paths(const struct binding_s *ref,char file[320],char receipt[320])
{
  char hash[65],directory[320];int ret=sha(ref->source,strlen(ref->source),hash);if(ret)return ret;
  int n=snprintf(directory,sizeof(directory),ROOT "/%s",hash);if(n<0||(size_t)n>=sizeof(directory))return -ENAMETOOLONG;
  ret=buffer_agent_private_directory(ROOT);if(!ret)ret=buffer_agent_sync_parent(ROOT);
  if (!ret)
    ret = buffer_agent_private_directory(directory);
  if (!ret)
    ret = buffer_agent_sync_parent(directory);
  if (ret)
    return ret;
  n=snprintf(file,320,"%s/%020llu.blob",directory,(unsigned long long)ref->sequence);if(n<0||n>=320)return -ENAMETOOLONG;
  n=snprintf(receipt,320,"%s/%020llu.json",directory,(unsigned long long)ref->sequence);return n<0||n>=320?-ENAMETOOLONG:0;
}
static cJSON *metadata(const struct binding_s *ref)
{
  cJSON *j=cJSON_CreateObject();bool built=j&&cJSON_AddNumberToObject(j,"schema_version",1)&&cJSON_AddStringToObject(j,"owner_peer",ref->peer)&&
    cJSON_AddStringToObject(j,"source_id",ref->source)&&cJSON_AddNumberToObject(j,"source_sequence",(double)ref->sequence)&&
    cJSON_AddStringToObject(j,"event_id",ref->event_id)&&cJSON_AddStringToObject(j,"record_sha256",ref->record_hash)&&
    cJSON_AddStringToObject(j,"blob_sha256",ref->blob_hash)&&cJSON_AddNumberToObject(j,"total",(double)ref->total)&&
    cJSON_AddStringToObject(j,"content_type",ref->content_type);
  if(!built){cJSON_Delete(j);return NULL;}return j;
}
/* Metadata never vouches for a missing or corrupt file. A failed rename sync
 * is retried by writing the identical receipt through the backend barrier. */
static int receipt(const struct binding_s *ref,const char *path,bool commit)
{
  cJSON *expected=metadata(ref);if(!expected)return -ENOMEM;errno=0;
  cJSON *old=buffer_agent_read_json_plain(path,4096);int ret=0;
  if(old){
    uint64_t version=0,sequence=0,total=0;
    /* cJSON_Compare uses approximate doubles. Counter identities must remain
     * exact even near the wire's maximum safe integer. */
    if(!cJSON_IsObject(old)||cJSON_GetArraySize(old)!=9||!number(old,"schema_version",1,1,&version)||
       !number(old,"source_sequence",1,MAX_COUNTER-1,&sequence)||sequence!=ref->sequence||
       !number(old,"total",1,MAX_BYTES,&total)||total!=ref->total||
       strcmp(text(old,"owner_peer"),ref->peer)||strcmp(text(old,"source_id"),ref->source)||
       strcmp(text(old,"event_id"),ref->event_id)||strcmp(text(old,"record_sha256"),ref->record_hash)||
       strcmp(text(old,"blob_sha256"),ref->blob_hash)||strcmp(text(old,"content_type"),ref->content_type))ret=-EEXIST;
  }
  else if(errno!=ENOENT)ret=errno?-errno:-EIO;
  if(!ret&&commit){char *raw=cJSON_PrintUnformatted(expected);if(!raw)ret=-ENOMEM;
    else{size_t bytes=strlen(raw);ret=buffer_agent_atomic_bytes(path,raw,bytes,4096);wipe(raw,bytes);free(raw);}}
  cJSON_Delete(old);cJSON_Delete(expected);return ret;
}
static int private_open(const char *path,int flags)
{
#ifdef O_NOFOLLOW
  flags|=O_NOFOLLOW;
#endif
  /* lstat also rejects links on targets without O_NOFOLLOW. The directory is
   * private and only this application worker writes these transfer paths. */
  struct stat st;if(!lstat(path,&st)){if(!S_ISREG(st.st_mode)||(st.st_mode&0077))return -EACCES;}
  else if(errno!=ENOENT)return -errno;
  int fd=open(path,flags,0600);if(fd<0)return -errno;
  if(fstat(fd,&st)||!S_ISREG(st.st_mode)||(st.st_mode&0077)){close(fd);return -EACCES;}return fd;
}
static int verify_fd(int fd,const struct binding_s *ref)
{
  struct stat st;if(fstat(fd,&st)||!S_ISREG(st.st_mode)||(st.st_mode&0077)||st.st_size<1||(uint64_t)st.st_size!=ref->total)return -EEXIST;
  if(lseek(fd,0,SEEK_SET)<0)return -errno;
  mbedtls_sha256_context ctx;mbedtls_sha256_init(&ctx);int ret=mbedtls_sha256_starts(&ctx,0)?-EIO:0;
  unsigned char data[1024],digest[32];uint64_t total=0;char hash[65];
  while(!ret){ssize_t got=read(fd,data,sizeof(data));if(got<0&&errno==EINTR)continue;if(got<0){ret=-errno;break;}if(!got)break;
    total+=(uint64_t)got;if(total>ref->total||mbedtls_sha256_update(&ctx,data,(size_t)got))ret=-EIO;}
  if(!ret&&(total!=ref->total||mbedtls_sha256_finish(&ctx,digest)))ret=-EIO;
  if(!ret){for(unsigned i=0;i<32;i++)snprintf(hash+2*i,3,"%02x",digest[i]);if(strcmp(hash,ref->blob_hash))ret=-EEXIST;}
  if(!ret&&fsync(fd))ret=-errno;
  mbedtls_sha256_free(&ctx);wipe(data,sizeof(data));wipe(digest,sizeof(digest));return ret;
}
static cJSON *base(const char *type,const cJSON *request)
{
  cJSON *j = cJSON_CreateObject();
  if (j && cJSON_AddStringToObject(j, "type", type) &&
      cJSON_AddNumberToObject(j, "protocol", 1) &&
      cJSON_AddStringToObject(j, "exchange_id", text(request, "exchange_id")))
    return j;
  cJSON_Delete(j);
  return NULL;
}
static int encode(cJSON *j,size_t budget,char **reply)
{if(!j)return -ENOMEM;*reply=cJSON_PrintUnformatted(j);cJSON_Delete(j);if(!*reply)return -ENOMEM;
  if(strlen(*reply)>budget){free(*reply);*reply=NULL;return -EMSGSIZE;}return 0;}
static int error(const cJSON *request,int result,size_t budget,char **reply)
{
  const char *state=result==-ENOENT?"event_missing":result==-EEXIST?"conflict":result==-EAGAIN?"busy":"storage_error";
  cJSON *j=base("buffer.events.error",request);if(!j)return -ENOMEM;
  if(!cJSON_AddStringToObject(j,"state",state)||!cJSON_AddNumberToObject(j,"retry_after_ms",2000)){cJSON_Delete(j);return -ENOMEM;}
  return encode(j,budget,reply);
}
static int respond(const cJSON *request,const struct binding_s *ref,const char *state,uint64_t offset,size_t budget,char **reply)
{
  cJSON *j=base("buffer.events.blob.received",request);bool built=j&&cJSON_AddStringToObject(j,"source_id",ref->source)&&
    cJSON_AddNumberToObject(j,"source_sequence",(double)ref->sequence)&&cJSON_AddStringToObject(j,"record_sha256",ref->record_hash)&&
    cJSON_AddStringToObject(j,"blob_sha256",ref->blob_hash)&&cJSON_AddNumberToObject(j,"total",(double)ref->total)&&
    cJSON_AddNumberToObject(j,"next_offset",(double)offset)&&cJSON_AddStringToObject(j,"state",state);
  if(!built){cJSON_Delete(j);return -ENOMEM;}return encode(j,budget,reply);
}
static bool same(const struct binding_s *a,const struct binding_s *b)
{return a->sequence==b->sequence&&a->total==b->total&&!strcmp(a->peer,b->peer)&&!strcmp(a->source,b->source)&&
  !strcmp(a->record_hash,b->record_hash)&&!strcmp(a->blob_hash,b->blob_hash)&&!strcmp(a->event_id,b->event_id)&&!strcmp(a->content_type,b->content_type);}
static int discard_partial(const char *path)
{
  struct stat st;
  if(lstat(path,&st))return errno==ENOENT?0:-errno;
  if(!S_ISREG(st.st_mode)||(st.st_mode&0077))return -EACCES;
  return unlink(path)?-errno:0;
}
/* Account for crash leftovers before reserving any new staging bytes. The
 * four fixed paths bound file count; active slots keep their own prefixes. */
static int clean_abandoned_partials(void)
{
  int ret=discard_partial(ROOT "/incoming.partial");if(ret)return ret;
  for(unsigned i=0;i<BLOB_SESSIONS;i++)if(transfers[i].receiving_fd<0){
    char path[sizeof(ROOT)+32];snprintf(path,sizeof(path),ROOT "/incoming-%u.partial",i);
    ret=discard_partial(path);if(ret)return ret;
  }
  return 0;
}
static int start(const struct binding_s *ref,unsigned generation)
{
  uint64_t reserved=ref->total;
  for(unsigned i=0;i<BLOB_SESSIONS;i++)if(&transfers[i]!=transfer&&transfers[i].receiving_fd>=0)reserved+=transfers[i].receiving.total;
  if(reserved>MAX_BYTES)return -EAGAIN;
  buffer_source_remote_blob_reset();
  int ret=clean_abandoned_partials();if(ret)return ret;
  int fd=private_open(partial_path(),O_RDWR|O_CREAT|O_EXCL);if(fd<0)return fd;
  transfer->receiving_fd=fd;transfer->receiving=*ref;transfer->receiving_generation=generation;transfer->receiving_touch=now_ms();return 0;
}
static int append(uint64_t offset,const unsigned char *data,size_t bytes)
{
  if(offset>transfer->receiving_used||offset+bytes>transfer->receiving.total||(offset<transfer->receiving_used&&offset+bytes>transfer->receiving_used))return -EEXIST;
  if(lseek(transfer->receiving_fd,(off_t)offset,SEEK_SET)<0)return -errno;
  if(offset<transfer->receiving_used){
    unsigned char old[3072];size_t used=0;int ret=0;
    while(used<bytes){ssize_t got=read(transfer->receiving_fd,old+used,bytes-used);if(got<0&&errno==EINTR)continue;if(got<=0){ret=got<0?-errno:-EIO;break;}used+=(size_t)got;}
    if (!ret && memcmp(old, data, bytes))
      ret = -EEXIST;
    wipe(old, sizeof(old));
    return ret;
  }
  size_t used=0;while(used<bytes){ssize_t put=write(transfer->receiving_fd,data+used,bytes-used);if(put<0&&errno==EINTR)continue;
    if (put <= 0)
      return put < 0 ? -errno : -EIO;
    used += (size_t)put;
  }
  transfer->receiving_used+=bytes;return 0;
}
int buffer_source_remote_blob_handle(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,size_t budget,
                                     const cJSON *request,char **reply)
{
  if (!reply)
    return -EINVAL;
  *reply = NULL;
  if(!peer||!hex(peer)||!cJSON_IsObject(request)||budget<512||budget>12288)return -EINVAL;
  if(!authorized((void *)auth)){buffer_source_remote_blob_close_session(peer,generation);return -EACCES;}
  (void)maintain_sessions();
  if(!select_session(peer,generation))return error(request,-EAGAIN,budget,reply);
  bool offer=!strcmp(text(request,"type"),"buffer.events.blob.offer");
  struct binding_s ref;int ret=binding(auth,peer,request,&ref);
  if(ret)return authorized((void *)auth)?error(request,ret,budget,reply):-EACCES;
  char file[320],proof[320];ret=paths(&ref,file,proof);if(!ret)ret=receipt(&ref,proof,false);
  if(ret)goto failed;
  int fd=private_open(file,O_RDWR);
  if(fd>=0){
    ret=verify_fd(fd,&ref);if(close(fd)&&!ret)ret=-errno;
    if(!ret&&authorized((void *)auth))ret=receipt(&ref,proof,true);else if(!ret)ret=-EACCES;
    if(ret)goto failed;
    if(transfer->receiving_fd>=0&&same(&transfer->receiving,&ref))buffer_source_remote_blob_reset();
    ret=respond(request,&ref,"stored",ref.total,budget,reply);goto done;
  }
  if(fd!=-ENOENT){ret=fd;goto failed;}
  if(offer){
    /* An offer is a restart boundary, including a lost completed transfer.
     * Existing durable files above are always rehashed before confirmation. */
    ret=start(&ref,generation);if(ret==-EAGAIN){ret=error(request,ret,budget,reply);goto done;}if(ret)goto failed;
    ret=respond(request,&ref,"ready",0,budget,reply);goto done;
  }
  if(transfer->receiving_fd<0||transfer->receiving_generation!=generation||!same(&transfer->receiving,&ref)){
    ret=respond(request,&ref,"restart",0,budget,reply);goto done;
  }
  uint64_t offset=0;const char *encoded=text(request,"blob_base64");size_t encoded_bytes=strlen(encoded);
  if(!number(request,"offset",0,ref.total-1,&offset)||!encoded_bytes||encoded_bytes>4096){ret=-EINVAL;goto done;}
  unsigned char *data=malloc(3072),*canonical=malloc(4097);size_t bytes=0,canonical_bytes=0;
  if(!data||!canonical){free(data);free(canonical);ret=-ENOMEM;goto failed;}
  ret=mbedtls_base64_decode(data,3072,&bytes,(const unsigned char *)encoded,encoded_bytes);
  if(!ret&&bytes&&bytes<=3072)ret=mbedtls_base64_encode(canonical,4097,&canonical_bytes,data,bytes);else ret=-EINVAL;
  if(!ret&&(canonical_bytes!=encoded_bytes||memcmp(canonical,encoded,encoded_bytes)||bytes>ref.total-offset))ret=-EINVAL;
  if(!ret)ret=append(offset,data,bytes);
  wipe(data,3072);wipe(canonical,4097);free(data);free(canonical);
  if (ret)
    goto failed;
  transfer->receiving_touch = now_ms();
  if(transfer->receiving_used<ref.total){ret=respond(request,&ref,"partial",transfer->receiving_used,budget,reply);goto done;}
  ret=verify_fd(transfer->receiving_fd,&ref);if(ret)goto failed;
  if(!authorized((void *)auth)){ret=-EACCES;goto failed;}
  /* Rename only a complete verified original. The receipt follows the file
   * barrier; replay repairs missing receipts without changing the source log. */
  if(rename(partial_path(),file)){ret=-errno;goto failed;}
  ret=buffer_agent_sync_parent(file);
  if (close(transfer->receiving_fd) && !ret)
    ret = -errno;
  transfer->receiving_fd = -1;
  if(!ret&&authorized((void *)auth))ret=receipt(&ref,proof,true);else if(!ret)ret=-EACCES;
  buffer_source_remote_blob_reset();if(ret)goto failed;
  ret=respond(request,&ref,"stored",ref.total,budget,reply);goto done;
failed:
  buffer_source_remote_blob_reset();
  if(authorized((void *)auth))ret=error(request,ret,budget,reply);
done:
  if(!authorized((void *)auth)){if(*reply){wipe(*reply,strlen(*reply));free(*reply);*reply=NULL;}buffer_source_remote_blob_reset();return -EACCES;}
  return ret;
}
#else
void buffer_source_remote_blob_reset_all(void){}
void buffer_source_remote_blob_close_session(const char *peer,unsigned generation){(void)peer;(void)generation;}
bool buffer_source_remote_blob_session_idle(const char *peer,unsigned generation,bool active){(void)peer;(void)generation;(void)active;return false;}
void buffer_source_remote_blob_reset(void){}
bool buffer_source_remote_blob_idle(unsigned generation,bool active){(void)generation;(void)active;return false;}
int buffer_source_remote_blob_handle(const struct buffer_oobe_authorization_s *auth,const char *peer,unsigned generation,size_t budget,
                                     const cJSON *request,char **reply)
{(void)auth;(void)peer;(void)generation;(void)budget;(void)request;if(reply)*reply=NULL;return -ENOTSUP;}
#endif
