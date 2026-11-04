/* SPDX-License-Identifier: Apache-2.0
 * Progressive prefix recognition. Drafts never enter the capture queue or
 * the agent. A single worker always takes the latest available PCM prefix. */
#include "buffer_live_asr.h"
#include "buffer_agent.h"
#include "buffer_https.h"
#include <mbedtls/base64.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <syslog.h>
#define PCM_SECOND 32000u
#define DRAFT_BYTES 4096u
#define SSE_LINE_BYTES 16384u
static pthread_mutex_t live_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t live_wake = PTHREAD_COND_INITIALIZER;
static pthread_t live_worker;
static bool live_started, live_active, live_quit;
static uint64_t live_generation;
static unsigned char *live_pcm;
static size_t live_size, live_dispatched;
static char live_text[DRAFT_BYTES];
struct stream_context
{
  uint64_t generation;
  char line[SSE_LINE_BYTES];
  char text[DRAFT_BYTES];
  size_t line_size, text_size;
  bool finished;
};
static bool stale(void *context)
{
  struct stream_context *stream = context;
  pthread_mutex_lock(&live_lock);
  bool cancelled = live_quit || !live_active || stream->generation != live_generation;
  pthread_mutex_unlock(&live_lock);
  return cancelled;
}
static int event_line(struct stream_context *stream)
{
  stream->line[stream->line_size] = 0;
  if (strncmp(stream->line,"data:",5)) return 0;
  const char *data=stream->line+5;
  while (*data==' ' || *data=='\t') data++;
  if (!strcmp(data,"[DONE]")) return stream->finished ? 0 : -EPROTO;
  cJSON *root=cJSON_ParseWithOpts(data,NULL,true);
  if (!root) return -EPROTO;
  cJSON *choices=cJSON_GetObjectItemCaseSensitive(root,"choices");
  cJSON *choice=cJSON_GetArrayItem(choices,0);
  cJSON *delta=cJSON_GetObjectItemCaseSensitive(choice,"delta");
  cJSON *content=cJSON_GetObjectItemCaseSensitive(delta,"content");
  cJSON *finish=cJSON_GetObjectItemCaseSensitive(choice,"finish_reason");
  int ret=0;
  if (cJSON_IsString(finish))
    { if (!strcmp(finish->valuestring,"stop")) stream->finished=true; else ret=-EPROTO; }
  if (!ret && cJSON_IsString(content))
    {
      size_t length=strlen(content->valuestring);
      if (length > sizeof(stream->text)-1-stream->text_size) ret=-EFBIG;
      else
        {
          memcpy(stream->text+stream->text_size,content->valuestring,length+1);
          stream->text_size+=length;
          pthread_mutex_lock(&live_lock);
          if (live_active && !live_quit && live_generation==stream->generation)
            memcpy(live_text,stream->text,stream->text_size+1);
          pthread_mutex_unlock(&live_lock);
        }
    }
  cJSON_Delete(root);
  return ret;
}
static int stream_data(const char *data,size_t length,void *context)
{
  struct stream_context *stream=context;
  if (stale(context)) return -ECANCELED;
  for (size_t i=0;i<length;i++)
    {
      if (data[i]=='\n')
        {
          if (stream->line_size && stream->line[stream->line_size-1]=='\r') stream->line_size--;
          int ret=event_line(stream);stream->line_size=0;
          if (ret<0) return ret;
        }
      else
        {
          if (stream->line_size>=sizeof(stream->line)-1) return -EFBIG;
          stream->line[stream->line_size++]=data[i];
        }
    }
  return 0;
}
static void put32(unsigned char *data,uint32_t value)
{ for (unsigned i=0;i<4;i++) data[i]=(unsigned char)(value>>(i*8)); }
static int request_prefix(const struct buffer_agent_config_s *config,
                          unsigned char *wav,size_t length,uint64_t generation)
{
  size_t capacity=4*((length+2)/3)+1,written=0;
  char *audio=malloc(capacity+23);
  struct stream_context *stream=calloc(1,sizeof(*stream));
  if (!audio || !stream) { free(audio);free(stream);return -ENOMEM; }
  stream->generation=generation;
  strcpy(audio,"data:audio/wav;base64,");
  int ret=mbedtls_base64_encode((unsigned char *)audio+22,capacity,&written,wav,length);
  if (ret) { free(audio);free(stream);return -EIO; }
  audio[22+written]=0;
  cJSON *body=cJSON_CreateObject();
  cJSON *messages=cJSON_CreateArray(),*message=cJSON_CreateObject();
  cJSON *content=cJSON_CreateArray(),*part=cJSON_CreateObject(),*input=cJSON_CreateObject();
  if (!body || !messages || !message || !content || !part || !input)
    {
      cJSON_Delete(body);cJSON_Delete(messages);cJSON_Delete(message);
      cJSON_Delete(content);cJSON_Delete(part);cJSON_Delete(input);
      memset(audio,0,capacity+23);free(audio);free(stream);return -ENOMEM;
    }
  cJSON_AddStringToObject(body,"model",config->request.transcription_model);
  cJSON_AddBoolToObject(body,"stream",true);
  cJSON_AddStringToObject(input,"data",audio);
  memset(audio,0,capacity+23);free(audio);
  cJSON_AddStringToObject(part,"type","input_audio");
  cJSON_AddItemToObject(part,"input_audio",input);cJSON_AddItemToArray(content,part);
  cJSON_AddStringToObject(message,"role","user");
  cJSON_AddItemToObject(message,"content",content);cJSON_AddItemToArray(messages,message);
  cJSON_AddItemToObject(body,"messages",messages);
  cJSON *options=cJSON_AddObjectToObject(body,"asr_options");
  cJSON_AddStringToObject(options,"language","auto");
  char *encoded=cJSON_PrintUnformatted(body);cJSON_Delete(body);
  int status=0;
  if (!encoded) ret=-ENOMEM;
  else
    {
      ret=buffer_https_post_stream(config->request.base_url,config->request.api_key,
          encoded,strlen(encoded),stream_data,stale,stream,&status);
      memset(encoded,0,strlen(encoded));free(encoded);
      if (!ret && (status<200 || status>=300 || !stream->finished || !stream->text_size)) ret=-EPROTO;
    }
  memset(stream,0,sizeof(*stream));free(stream);
  return ret;
}
static void *recognize(void *unused)
{
  (void)unused;
  pthread_mutex_lock(&live_lock);
  while (!live_quit)
    {
      while (!live_quit && (!live_active || live_size < live_dispatched+PCM_SECOND))
        pthread_cond_wait(&live_wake,&live_lock);
      if (live_quit) break;
      size_t bytes=live_size;
      uint64_t generation=live_generation;
      live_dispatched=bytes;
      unsigned char *wav=calloc(1,bytes+44);
      if (!wav) continue;
      memcpy(wav,"RIFF",4);put32(wav+4,(uint32_t)bytes+36);
      memcpy(wav+8,"WAVEfmt ",8);put32(wav+16,16);
      wav[20]=1;wav[22]=1;put32(wav+24,16000);put32(wav+28,32000);
      wav[32]=2;wav[34]=16;memcpy(wav+36,"data",4);put32(wav+40,(uint32_t)bytes);
      memcpy(wav+44,live_pcm,bytes);
      pthread_mutex_unlock(&live_lock);
      struct buffer_agent_config_s *config=calloc(1,sizeof(*config));
      if (config)
        {
          if (!buffer_agent_config_load(config) && config->validated)
            {
              int ret=request_prefix(config,wav,bytes+44,generation);
              if(ret && ret!=-ECANCELED)
                syslog(LOG_WARNING,"Buffer ASR: prefix_bytes=%lu error=%d\n",(unsigned long)bytes,ret);
            }
          buffer_agent_config_wipe(config);free(config);
        }
      memset(wav,0,bytes+44);free(wav);
      pthread_mutex_lock(&live_lock);
    }
  pthread_mutex_unlock(&live_lock);
  return NULL;
}
void buffer_live_asr_begin(void)
{
  pthread_mutex_lock(&live_lock);
  if (!live_pcm) live_pcm=malloc(CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES);
  live_generation++;live_active=live_pcm!=NULL;live_quit=false;
  live_size=live_dispatched=0;memset(live_text,0,sizeof(live_text));
  if (live_active && !live_started)
    {
      pthread_attr_t attributes;pthread_attr_init(&attributes);
      pthread_attr_setstacksize(&attributes,32768);
      int ret=pthread_create(&live_worker,&attributes,recognize,NULL);
      pthread_attr_destroy(&attributes);
      if (!ret) live_started=true;
      else live_active=false;
    }
  pthread_mutex_unlock(&live_lock);
}
void buffer_live_asr_feed(const void *pcm,size_t bytes)
{
  pthread_mutex_lock(&live_lock);
  if (live_active && bytes<=CONFIG_BUFFER_VELA_MAX_CAPTURE_BYTES-live_size)
    {
      memcpy(live_pcm+live_size,pcm,bytes);live_size+=bytes;
      if (live_size>=live_dispatched+PCM_SECOND) pthread_cond_signal(&live_wake);
    }
  pthread_mutex_unlock(&live_lock);
}
void buffer_live_asr_end(void)
{
  pthread_mutex_lock(&live_lock);
  live_active=false;live_generation++;
  if (live_pcm) memset(live_pcm,0,live_size);
  live_size=0;
  pthread_cond_signal(&live_wake);pthread_mutex_unlock(&live_lock);
}
void buffer_live_asr_snapshot(char *text,size_t capacity)
{
  if (!text || !capacity) return;
  pthread_mutex_lock(&live_lock);
  snprintf(text,capacity,"%s",live_text);
  pthread_mutex_unlock(&live_lock);
}
void buffer_live_asr_shutdown(void)
{
  pthread_mutex_lock(&live_lock);
  live_active=false;live_quit=true;live_generation++;
  bool join=live_started;pthread_cond_signal(&live_wake);
  pthread_mutex_unlock(&live_lock);
  if (join) pthread_join(live_worker,NULL);
  pthread_mutex_lock(&live_lock);
  free(live_pcm);live_pcm=NULL;live_started=false;
  memset(live_text,0,sizeof(live_text));live_size=live_dispatched=0;
  pthread_mutex_unlock(&live_lock);
}
