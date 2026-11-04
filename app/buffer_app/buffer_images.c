/* SPDX-License-Identifier: Apache-2.0
 * Provider: https://api.ruseo.cn/api-doc/baidu_image
 * words/page/limit/type=1; envelope code=0, data.code=200, data.res URLs. */
#include "buffer_images.h"
#include "buffer_agent.h"
#include "buffer_https.h"
#include <mbedtls/sha256.h>
#include <errno.h>
#include <dirent.h>
#include <sys/stat.h>
#include <syslog.h>
#include <unistd.h>
#include <stdio.h>
#include <jpeglib.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#define IMAGE_RESULTS 4
#define IMAGE_SLOTS 5
#define IMAGE_PIXELS (BUFFER_IMAGE_WIDTH*BUFFER_IMAGE_HEIGHT)
#define IMAGE_BYTES (IMAGE_PIXELS*2u)
#define IMAGE_JPEG_LIMIT (256u*1024u)
struct image_slot {char id[BUFFER_IMAGE_ID_BYTES];_Alignas(64) uint16_t pixels[IMAGE_PIXELS];unsigned refs,generation;uint64_t age;};
static struct image_slot slots[IMAGE_SLOTS];
static pthread_mutex_t image_lock=PTHREAD_MUTEX_INITIALIZER;
static uint64_t image_age;
static const char *image_text(const cJSON *value,const char *key)
{const cJSON *item=cJSON_GetObjectItemCaseSensitive(value,key);return cJSON_IsString(item)?item->valuestring:"";}
static bool image_id_valid(const char *id)
{if(!id||strlen(id)!=64)return false;for(unsigned i=0;i<64;i++)if(!((id[i]>='0'&&id[i]<='9')||(id[i]>='a'&&id[i]<='f')))return false;return true;}
static bool preview_url_valid(const char *url)
{
 if(!url||strlen(url)>1024||strncmp(url,"https://img",11)||url[11]<'0'||url[11]>'9'||strncmp(url+12,".baidu.com/",11)||strpbrk(url,"#@\\ \t\r\n"))return false;
 for(const unsigned char *p=(const unsigned char *)url;*p;p++)if(*p<0x21||*p>=0x7f)return false;
 return true;
}
static int image_directory(char *path,size_t size)
{int n=snprintf(path,size,"%s/images",CONFIG_BUFFER_VELA_DATA_DIR);return n<0||(size_t)n>=size?-ENAMETOOLONG:buffer_agent_private_directory(path);}
/* Disposable photo files have a separate fixed disk budget. Removing an
 * old JPEG never touches pixels pinned by an existing LVGL image lease. */
static int trim_photos(const char *directory,const char *keep)
{
 for(;;){
  DIR *dir=opendir(directory);if(!dir)return -errno;
  unsigned count=0;char oldest[272]="";time_t oldest_time=0;struct dirent *entry;
  while((entry=readdir(dir))){
   if(strlen(entry->d_name)!=68||strcmp(entry->d_name+64,".jpg"))continue;
   char id[65];memcpy(id,entry->d_name,64);id[64]=0;if(!image_id_valid(id))continue;
   char path[272];int n=snprintf(path,sizeof(path),"%s/%s",directory,entry->d_name);if(n<0||(size_t)n>=sizeof(path))continue;
   struct stat info;if(lstat(path,&info)||!S_ISREG(info.st_mode))continue;count++;
   if(strcmp(path,keep)&&(!oldest[0]||info.st_mtime<oldest_time)){strcpy(oldest,path);oldest_time=info.st_mtime;}
  }
  closedir(dir);if(count<=12)return 0;
  if(!oldest[0])return -ENOSPC;
  if(unlink(oldest))return -errno;
 }
}
static int image_result_path(char *path,size_t size,const char *capture)
{if(!buffer_agent_valid_id(capture))return -EINVAL;int n=snprintf(path,size,"%s/records/%s.images.json",CONFIG_BUFFER_VELA_DATA_DIR,capture);return n<0||(size_t)n>=size?-ENAMETOOLONG:0;}
static void image_hash(const char *url,char id[BUFFER_IMAGE_ID_BYTES])
{unsigned char digest[32];if(mbedtls_sha256((const unsigned char *)url,strlen(url),digest,0)){id[0]=0;return;}for(unsigned i=0;i<32;i++)snprintf(id+2*i,3,"%02x",digest[i]);}
static cJSON *image_failure(const char *code)
{cJSON *result=cJSON_CreateObject();if(result){cJSON_AddBoolToObject(result,"success",false);cJSON_AddStringToObject(result,"error",code);}return result;}
cJSON *buffer_images_search(const char *capture,const char *query,unsigned page)
{
 char path[256],encoded[768],url[1024];size_t used=0;
 if(!query||!*query||strnlen(query,193)>192||page<1||page>10||image_result_path(path,sizeof(path),capture))return image_failure("invalid_image_query");
 for(const unsigned char *p=(const unsigned char *)query;*p;p++){
  if(*p<0x20||*p==0x7f)return image_failure("invalid_image_query");
  if((*p>='a'&&*p<='z')||(*p>='A'&&*p<='Z')||(*p>='0'&&*p<='9')||strchr("-._~",*p))encoded[used++]=(char)*p;
  else{snprintf(encoded+used,sizeof(encoded)-used,"%%%02X",*p);used+=3;}
 }
 encoded[used]=0;
 snprintf(url,sizeof(url),"https://api.ruseo.cn/api/baidu_image?words=%s&page=%u&limit=4&type=1",encoded,page);
 char *bytes=NULL;size_t length=0;int status=0;
 int ret=buffer_https_get_public(url,8000,&bytes,&length,&status);
 if(ret||status!=200||length>65536||memchr(bytes,0,length)){free(bytes);return image_failure("image_search_unavailable");}
 cJSON *response=cJSON_ParseWithOpts(bytes,NULL,true);free(bytes);
 const cJSON *data=cJSON_GetObjectItemCaseSensitive(response,"data");
 const cJSON *code=cJSON_GetObjectItemCaseSensitive(response,"code");
 if(cJSON_IsObject(data)){
  if(!cJSON_IsNumber(code)||code->valuedouble!=0){cJSON_Delete(response);return image_failure("image_search_unavailable");}
 }else data=response;
 code=cJSON_GetObjectItemCaseSensitive(data,"code");const cJSON *results=cJSON_GetObjectItemCaseSensitive(data,"res");
 if(!cJSON_IsNumber(code)||code->valuedouble!=200||!cJSON_IsArray(results)){cJSON_Delete(response);return image_failure("invalid_image_search_response");}
 cJSON *output=cJSON_CreateObject(),*images=cJSON_CreateArray();
 if(!output||!images){cJSON_Delete(output);cJSON_Delete(images);cJSON_Delete(response);return NULL;}
 cJSON_AddItemToObject(output,"images",images);cJSON_AddStringToObject(output,"provider","澄曜/百度图片");cJSON_AddStringToObject(output,"query",query);
 for(int i=0;i<cJSON_GetArraySize(results)&&cJSON_GetArraySize(images)<IMAGE_RESULTS;i++){
  const cJSON *entry=cJSON_GetArrayItem(results,i);if(!cJSON_IsString(entry)||!preview_url_valid(entry->valuestring))continue;
  char id[BUFFER_IMAGE_ID_BYTES];image_hash(entry->valuestring,id);if(!id[0])continue;
  cJSON *item=cJSON_CreateObject();if(!item)continue;
  cJSON_AddStringToObject(item,"image_id",id);cJSON_AddStringToObject(item,"preview_url",entry->valuestring);cJSON_AddItemToArray(images,item);
 }
 cJSON_Delete(response);
 /* Keep eight immutable result sets per capture, including repeated queries
  * whose provider ordering changed. Cached tool receipts retain valid IDs. */
 cJSON *registry=buffer_agent_read_json(path,65536);
 if(!cJSON_IsArray(registry)){cJSON_Delete(registry);registry=cJSON_CreateArray();}
 if(!registry){cJSON_Delete(output);return NULL;}
 while(cJSON_GetArraySize(registry)>=8)cJSON_DeleteItemFromArray(registry,0);
 cJSON *saved=cJSON_Duplicate(output,true);if(saved)cJSON_AddItemToArray(registry,saved);
 ret=saved?buffer_agent_atomic_json(path,registry):-ENOMEM;cJSON_Delete(registry);
 if(ret){cJSON_Delete(output);return image_failure("image_results_not_saved");}
 cJSON_AddBoolToObject(output,"success",true);return output;
}
struct jpeg_failure {struct jpeg_error_mgr manager;jmp_buf jump;};
static void jpeg_failed(j_common_ptr jpeg)
{struct jpeg_failure *failure=(struct jpeg_failure *)jpeg->err;longjmp(failure->jump,1);}
static int decode_photo(const unsigned char *jpeg,size_t length,uint16_t *pixels)
{
 struct jpeg_decompress_struct decoder={0};struct jpeg_failure failure;
 decoder.err=jpeg_std_error(&failure.manager);failure.manager.error_exit=jpeg_failed;
 if(setjmp(failure.jump)){jpeg_destroy_decompress(&decoder);return -EILSEQ;}
 jpeg_create_decompress(&decoder);jpeg_mem_src(&decoder,jpeg,length);
 if(jpeg_read_header(&decoder,TRUE)!=JPEG_HEADER_OK||!decoder.image_width||!decoder.image_height||decoder.image_width>2048||decoder.image_height>2048){jpeg_destroy_decompress(&decoder);return -EFBIG;}
 decoder.scale_num=1;decoder.scale_denom=1;
 while(decoder.scale_denom<8&&decoder.image_width/(decoder.scale_denom*2)>=BUFFER_IMAGE_WIDTH&&decoder.image_height/(decoder.scale_denom*2)>=BUFFER_IMAGE_HEIGHT)decoder.scale_denom*=2;
 decoder.out_color_space=JCS_RGB;jpeg_start_decompress(&decoder);
 unsigned w=decoder.output_width,h=decoder.output_height;
 if(decoder.output_components!=3||w>2048||h>2048){jpeg_destroy_decompress(&decoder);return -EILSEQ;}
 JSAMPARRAY row=(*decoder.mem->alloc_sarray)((j_common_ptr)&decoder,JPOOL_IMAGE,w*3,1);
 /* Center crop to the recipe photo aspect ratio, then nearest sample while
  * decoding scanlines. No full-size image buffer or UI-thread decoder. */
 unsigned crop_w=w,crop_h=h;
 if((uint64_t)w*BUFFER_IMAGE_HEIGHT>(uint64_t)h*BUFFER_IMAGE_WIDTH)crop_w=h*BUFFER_IMAGE_WIDTH/BUFFER_IMAGE_HEIGHT;
 else crop_h=w*BUFFER_IMAGE_HEIGHT/BUFFER_IMAGE_WIDTH;
 if(!crop_w||!crop_h){jpeg_destroy_decompress(&decoder);return -EILSEQ;}
 unsigned left=(w-crop_w)/2,top=(h-crop_h)/2,next=0;
 while(decoder.output_scanline<h){unsigned y=decoder.output_scanline;if(jpeg_read_scanlines(&decoder,row,1)!=1){jpeg_destroy_decompress(&decoder);return -EILSEQ;}
  while(next<BUFFER_IMAGE_HEIGHT&&top+next*crop_h/BUFFER_IMAGE_HEIGHT==y){
   for(unsigned x=0;x<BUFFER_IMAGE_WIDTH;x++){
    unsigned at=(left+x*crop_w/BUFFER_IMAGE_WIDTH)*3;const unsigned char *rgb=row[0]+at;
    pixels[next*BUFFER_IMAGE_WIDTH+x]=(uint16_t)(((rgb[0]>>3)<<11)|((rgb[1]>>2)<<5)|(rgb[2]>>3));
   }next++;
  }
 }
 jpeg_finish_decompress(&decoder);jpeg_destroy_decompress(&decoder);return next==BUFFER_IMAGE_HEIGHT?0:-EILSEQ;
}
static int find_image(const char *capture,const char *id,char url[1025])
{
 if(!image_id_valid(id))return -EINVAL;
 char registry_path[256];int ret=image_result_path(registry_path,sizeof(registry_path),capture);if(ret)return ret;
 cJSON *registry=buffer_agent_read_json(registry_path,65536);url[0]=0;
 if(cJSON_IsArray(registry))for(int i=0;i<cJSON_GetArraySize(registry);i++){
  const cJSON *images=cJSON_GetObjectItemCaseSensitive(cJSON_GetArrayItem(registry,i),"images");
  for(int j=0;j<cJSON_GetArraySize(images);j++){const cJSON *item=cJSON_GetArrayItem(images,j);if(!strcmp(image_text(item,"image_id"),id)){const char *candidate=image_text(item,"preview_url");if(preview_url_valid(candidate)){char digest[65];image_hash(candidate,digest);if(!strcmp(digest,id))snprintf(url,1025,"%s",candidate);}}}
 }
 cJSON_Delete(registry);return url[0]?0:-ENOENT;
}
int buffer_images_select(const char *capture,const char *id)
{char url[1025];return find_image(capture,id,url);}
int buffer_images_prepare(const char *capture,const char *id)
{
 char url[1025];int ret=find_image(capture,id,url);if(ret)return ret;
 pthread_mutex_lock(&image_lock);
 for(unsigned i=0;i<IMAGE_SLOTS;i++)if(!strcmp(slots[i].id,id)){slots[i].age=++image_age;pthread_mutex_unlock(&image_lock);return 0;}
 pthread_mutex_unlock(&image_lock);
 char directory[192],path[272];ret=image_directory(directory,sizeof(directory));if(ret)return ret;
 snprintf(path,sizeof(path),"%s/%s.jpg",directory,id);
 char *bytes=NULL;size_t length=0;ret=buffer_agent_read_bytes(path,IMAGE_JPEG_LIMIT,&bytes,&length);
 uint16_t *pixels=malloc(IMAGE_BYTES);if(!pixels){free(bytes);return -ENOMEM;}
 bool downloaded=false;
 if(ret||decode_photo((unsigned char *)bytes,length,pixels)){
  free(bytes);bytes=NULL;int status=0;ret=buffer_https_get_public(url,8000,&bytes,&length,&status);
  if(!ret&&status!=200)ret=-EIO;
  if(!ret)ret=decode_photo((unsigned char *)bytes,length,pixels);
  downloaded=!ret;
 }else ret=0;
 if(!ret&&downloaded){ret=buffer_agent_atomic_bytes(path,bytes,length,IMAGE_JPEG_LIMIT);if(!ret){ret=trim_photos(directory,path);if(ret)unlink(path);}}
 free(bytes);
 if(!ret){
  pthread_mutex_lock(&image_lock);unsigned selected=IMAGE_SLOTS;
  for(unsigned i=0;i<IMAGE_SLOTS;i++)if(!slots[i].refs&&(selected==IMAGE_SLOTS||slots[i].age<slots[selected].age))selected=i;
  if(selected==IMAGE_SLOTS)ret=-EBUSY;
  else{struct image_slot *slot=&slots[selected];memcpy(slot->pixels,pixels,IMAGE_BYTES);strcpy(slot->id,id);slot->generation=(slot->generation+1)&0x0fffffffu;if(!slot->generation)slot->generation=1;slot->age=++image_age;}
  pthread_mutex_unlock(&image_lock);
 }
 free(pixels);return ret;
}
int buffer_images_acquire(const char *id,struct buffer_image_view *view)
{
 if(!view||!image_id_valid(id))return -EINVAL;
 if(pthread_mutex_trylock(&image_lock))return -EAGAIN;
 int result=-ENOENT;
 for(unsigned i=0;i<IMAGE_SLOTS;i++)if(!strcmp(slots[i].id,id)){
  slots[i].refs++;slots[i].age=++image_age;*view=(struct buffer_image_view){slots[i].pixels,BUFFER_IMAGE_WIDTH,BUFFER_IMAGE_HEIGHT,(slots[i].generation<<4)|i};result=0;break;
 }
 pthread_mutex_unlock(&image_lock);return result;
}
void buffer_images_release(unsigned lease)
{
 unsigned index=lease&15u;if(index>=IMAGE_SLOTS)return;
 pthread_mutex_lock(&image_lock);if(slots[index].generation==(lease>>4)&&slots[index].refs)slots[index].refs--;pthread_mutex_unlock(&image_lock);
}

/* Downloads have their own worker so slow images cannot hold up timer,
 * capture or other UI command persistence on the shared command queue. */
struct image_job {char capture[BUFFER_ID_SIZE],id[BUFFER_IMAGE_ID_BYTES];unsigned state;uint64_t order;};
static struct image_job jobs[IMAGE_SLOTS];
static pthread_mutex_t jobs_lock=PTHREAD_MUTEX_INITIALIZER,stop_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t jobs_wake=PTHREAD_COND_INITIALIZER;
static pthread_t image_thread;
static bool image_started,image_stopping;
static uint64_t job_order;
static void *image_worker(void *unused)
{
 (void)unused;pthread_mutex_lock(&jobs_lock);
 for(;;){
  unsigned selected=IMAGE_SLOTS;
  for(unsigned i=0;i<IMAGE_SLOTS;i++)if(jobs[i].state==1&&(selected==IMAGE_SLOTS||jobs[i].order<jobs[selected].order))selected=i;
  if(image_stopping)break;
  if(selected==IMAGE_SLOTS){pthread_cond_wait(&jobs_wake,&jobs_lock);continue;}
  jobs[selected].state=2;struct image_job job=jobs[selected];pthread_mutex_unlock(&jobs_lock);
  int result=buffer_images_prepare(job.capture,job.id);
  if(result)syslog(LOG_INFO,"Buffer image: preparation result=%d\n",result);
  pthread_mutex_lock(&jobs_lock);memset(&jobs[selected],0,sizeof(jobs[selected]));
 }
 memset(jobs,0,sizeof(jobs));pthread_mutex_unlock(&jobs_lock);return NULL;
}
int buffer_images_request(const char *capture,const char *id)
{
 char url[1025];int result=find_image(capture,id,url);if(result)return result;
 pthread_mutex_lock(&jobs_lock);unsigned selected=IMAGE_SLOTS;
 if(image_stopping){pthread_mutex_unlock(&jobs_lock);return -ESHUTDOWN;}
 for(unsigned i=0;i<IMAGE_SLOTS;i++){
  if(jobs[i].state&&!strcmp(jobs[i].id,id)){pthread_mutex_unlock(&jobs_lock);return 0;}
  if(!jobs[i].state&&selected==IMAGE_SLOTS)selected=i;
 }
 if(selected==IMAGE_SLOTS){pthread_mutex_unlock(&jobs_lock);return -EBUSY;}
 if(!image_started){
  result=buffer_thread_create(&image_thread,image_worker,NULL);
  if(result){pthread_mutex_unlock(&jobs_lock);return -result;}image_started=true;
 }
 snprintf(jobs[selected].capture,sizeof(jobs[selected].capture),"%s",capture);strcpy(jobs[selected].id,id);
 jobs[selected].order=++job_order;jobs[selected].state=1;
 pthread_cond_signal(&jobs_wake);pthread_mutex_unlock(&jobs_lock);return 0;
}
void buffer_images_shutdown(void)
{
 pthread_mutex_lock(&stop_lock);pthread_mutex_lock(&jobs_lock);
 bool started=image_started;image_stopping=true;pthread_cond_signal(&jobs_wake);pthread_mutex_unlock(&jobs_lock);
 if(started)pthread_join(image_thread,NULL);
 pthread_mutex_lock(&jobs_lock);image_started=false;image_stopping=false;pthread_mutex_unlock(&jobs_lock);pthread_mutex_unlock(&stop_lock);
}
