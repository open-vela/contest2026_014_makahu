/* SPDX-License-Identifier: Apache-2.0 */
#include "buffer_source_envelope.h"
#include "buffer_source_events.h"
#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

/* cJSON accepts some non-JSON numbers and does not reject duplicate keys.
 * Check lexical JSON, Unicode and depth first, then check the decoded tree.
 * No input-sized objects or recursive frames are allocated on the stack. */
struct lexer { const unsigned char *at, *end; };
static void space(struct lexer *s)
{while(s->at<s->end&&strchr(" \r\n\t",*s->at))s->at++;}
static bool take(struct lexer *s,unsigned char c)
{space(s);if(s->at==s->end||*s->at!=c)return false;s->at++;return true;}
static int digit(unsigned char c)
{if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return -1;}
static bool hex4(struct lexer *s,unsigned *out)
{
  if (s->end - s->at < 4)
    return false;
  unsigned v = 0;
  for(unsigned i=0;i<4;i++){int n=digit(*s->at++);if(n<0)return false;v=(v<<4)|(unsigned)n;}
  *out=v;return true;
}
/* key receives only a short ASCII prefix, used to recognize event.payload
 * even when its key is Unicode-escaped. All other keys are checked later. */
static bool string(struct lexer *s,bool allow_nul,char key[8])
{
  if (!take(s, '"'))
    return false;
  size_t n = 0;
  while(s->at<s->end){
    unsigned c=*s->at++;
    if(c=='"'){if(key){if(n<8)key[n]=0;else key[0]=0;}return true;}
    if(c<0x20)return false;
    if(c=='\\'){
      if (s->at == s->end)
        return false;
      c = *s->at++;
      if(c=='u'){
        if(!hex4(s,&c))return false;
        if(c>=0xd800&&c<=0xdbff){
          if(s->end-s->at<6||s->at[0]!='\\'||s->at[1]!='u')return false;
          s->at+=2;unsigned low;if(!hex4(s,&low)||low<0xdc00||low>0xdfff)return false;
          c=0x10000+((c-0xd800)<<10)+(low-0xdc00);
        }else if(c>=0xdc00&&c<=0xdfff)return false;
        if(!c&&!allow_nul)return false;
      }else if(c=='b')c='\b';else if(c=='f')c='\f';else if(c=='n')c='\n';
      else if(c=='r')c='\r';else if(c=='t')c='\t';else if(c!='"'&&c!='\\'&&c!='/')return false;
    }else if(c>=128){
      unsigned tails,min;
      if(c>=0xc2&&c<=0xdf){tails=1;min=128;c&=31;}
      else if(c>=0xe0&&c<=0xef){tails=2;min=2048;c&=15;}
      else if(c>=0xf0&&c<=0xf4){tails=3;min=65536;c&=7;}else return false;
      if((size_t)(s->end-s->at)<tails)return false;
      while(tails--){unsigned d=*s->at++;if((d&0xc0)!=0x80)return false;c=(c<<6)|(d&63);}
      if(c<min||c>0x10ffff||(c>=0xd800&&c<=0xdfff))return false;
    }
    if (key && n < 7)
      key[n] = (c < 128 && c) ? (char)c : '?';
    n++;
  }
  return false;
}
static bool value(struct lexer *s,unsigned depth,bool payload)
{
  if (depth > 32)
    return false;
  space(s);
  if (s->at == s->end)
    return false;
  if(*s->at=='"')return string(s,payload,NULL);
  if(*s->at=='{'){
    s->at++;if(take(s,'}'))return true;
    do{char key[8]={0};if(!string(s,false,key)||!take(s,':')||
      !value(s,depth+1,payload||(depth==1&&!strcmp(key,"payload"))))return false;
    }while(take(s,','));return take(s,'}');
  }
  if(*s->at=='['){
    s->at++;if(take(s,']'))return true;
    do{if(!value(s,depth+1,payload))return false;}while(take(s,','));return take(s,']');
  }
  const unsigned char *start=s->at;
  if(*s->at=='t'||*s->at=='f'||*s->at=='n'){
    const char *word=*s->at=='t'?"true":*s->at=='f'?"false":"null";size_t n=strlen(word);
    if ((size_t)(s->end - s->at) < n || memcmp(s->at, word, n))
      return false;
    s->at += n;
    return true;
  }
  if (*s->at == '-')
    s->at++;
  if (s->at == s->end)
    return false;
  if(*s->at=='0')s->at++;
  else{if(*s->at<'1'||*s->at>'9')return false;do{s->at++;}while(s->at<s->end&&*s->at>='0'&&*s->at<='9');}
  if(s->at<s->end&&*s->at=='.'){
    s->at++;const unsigned char *fraction=s->at;
    while (s->at < s->end && *s->at >= '0' && *s->at <= '9')
      s->at++;
    if (s->at == fraction)
      return false;
  }
  if(s->at<s->end&&(*s->at=='e'||*s->at=='E')){
    s->at++;if(s->at<s->end&&(*s->at=='+'||*s->at=='-'))s->at++;
    const unsigned char *exponent=s->at;
    while (s->at < s->end && *s->at >= '0' && *s->at <= '9')
      s->at++;
    if (s->at == exponent)
      return false;
  }
  return s->at>start;
}
static int order(const void *a,const void *b)
{return strcmp(*(const char *const *)a,*(const char *const *)b);}
static int tree(const cJSON *j)
{
  if(cJSON_IsNumber(j)&&!isfinite(j->valuedouble))return -EINVAL;
  if(cJSON_IsObject(j)){
    size_t count=(size_t)cJSON_GetArraySize(j);const char **keys=count?malloc(count*sizeof(*keys)):NULL;
    if (count && !keys)
      return -ENOMEM;
    size_t at = 0;
    const cJSON *child;
    cJSON_ArrayForEach(child,j){if(!child->string){free(keys);return -EINVAL;}keys[at++]=child->string;}
    if(count>1){qsort(keys,count,sizeof(*keys),order);for(size_t i=1;i<count;i++)if(!strcmp(keys[i-1],keys[i])){free(keys);return -EINVAL;}}
    free(keys);
  }
  const cJSON *child;cJSON_ArrayForEach(child,j){int ret=tree(child);if(ret)return ret;}return 0;
}
static const char *text(const cJSON *j,const char *key)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsString(v)?v->valuestring:"";}
static bool identity(const char *s,size_t max,bool colon)
{
  size_t n=strlen(s);if(!n||n>max)return false;
  for (size_t i = 0; i < n; i++)
    if (!((s[i] >= 'A' && s[i] <= 'Z') || (s[i] >= 'a' && s[i] <= 'z') ||
          (s[i] >= '0' && s[i] <= '9') || strchr("_.-", s[i]) ||
          (colon && s[i] == ':')))
      return false;
  return true;
}
static bool integer(const cJSON *j,const char *key,double min,double max)
{const cJSON *v=cJSON_GetObjectItemCaseSensitive(j,key);return cJSON_IsNumber(v)&&isfinite(v->valuedouble)&&
  v->valuedouble>=min&&v->valuedouble<=max&&floor(v->valuedouble)==v->valuedouble;}
int buffer_source_json_object(const char *raw,size_t length,size_t maximum,cJSON **out)
{
  if (!out)
    return -EINVAL;
  *out = NULL;
  if(!maximum||maximum>BUFFER_SOURCE_EVENT_BYTES||!raw||!length||length>maximum||memchr(raw,0,length))return -EINVAL;
  struct lexer lexer={(const unsigned char *)raw,(const unsigned char *)raw+length};
  if (!value(&lexer, 0, false))
    return -EINVAL;
  space(&lexer);
  if (lexer.at != lexer.end)
    return -EINVAL;
  /* API does not require a caller-supplied trailing NUL. */
  char *copy=malloc(length+1);if(!copy)return -ENOMEM;memcpy(copy,raw,length);copy[length]=0;
  cJSON *j=cJSON_ParseWithOpts(copy,NULL,true);memset(copy,0,length);free(copy);if(!j)return -EINVAL;
  int ret=tree(j);if(!ret&&!cJSON_IsObject(j))ret=-EINVAL;
  if(ret)cJSON_Delete(j);else *out=j;return ret;
}
int buffer_source_envelope_decode(const char *raw,size_t length,cJSON **out)
{
  if (!out)
    return -EINVAL;
  *out = NULL;
  cJSON *j = NULL;
  int ret=buffer_source_json_object(raw,length,BUFFER_SOURCE_EVENT_BYTES,&j);if(ret)return ret;
  bool native=!strcmp(text(j,"provenance"),"native");
  const cJSON *event=cJSON_GetObjectItemCaseSensitive(j,"event"),*parents=cJSON_GetObjectItemCaseSensitive(event,"parent_event_ids");
  const char *type=text(event,"type");
  if(!cJSON_IsObject(j)||cJSON_GetArraySize(j)!=5||!integer(j,"wire_version",1,1)||
     !identity(text(j,"source_id"),96,false)||!integer(j,"source_sequence",1,9007199254740990.0)||
     (!native&&strcmp(text(j,"provenance"),"legacy_local"))||!cJSON_IsObject(event)||cJSON_GetArraySize(event)!=10||
     !identity(text(event,"event_id"),160,true)||strcmp(text(event,"app_namespace"),"app.buffer")||
     !identity(text(event,"actor_id"),96,false)||!integer(event,"schema_version",1,65535)||
     !integer(event,"logical_clock",native?1:0,9007199254740991.0)||!integer(event,"created_at",0,9007199254740991.0)||
     !identity(type,96,false)||!((type[0]>='A'&&type[0]<='Z')||(type[0]>='a'&&type[0]<='z'))||
     !cJSON_IsArray(parents)||cJSON_GetArraySize(parents)>32||!cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(event,"payload"))) {ret=-EINVAL;goto done;}
  const cJSON *capture=cJSON_GetObjectItemCaseSensitive(event,"capture_id");
  if(!cJSON_IsNull(capture)&&(!cJSON_IsString(capture)||!identity(capture->valuestring,160,true))){ret=-EINVAL;goto done;}
  const cJSON *parent;cJSON_ArrayForEach(parent,parents){
    if(!cJSON_IsString(parent)||!identity(parent->valuestring,160,true)||!strcmp(parent->valuestring,text(event,"event_id"))){ret=-EINVAL;goto done;}
    for(const cJSON *other=parent->next;other;other=other->next)
      if(cJSON_IsString(other)&&!strcmp(parent->valuestring,other->valuestring)){ret=-EINVAL;goto done;}
  }
  *out=j;j=NULL;
done:
  cJSON_Delete(j);return ret;
}
