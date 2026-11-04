"""Real image service: provider parsing, grounded IDs, JPEG decode and leases."""
from pathlib import Path
import os
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC


def test_provider_recipe_image_cache(tmp_path):
    build = Path(os.environ.get('BUFFER_AGENT_MBEDTLS_BUILD', '/tmp/buffer-agent-mbedtls'))
    crypto = build / 'library/libmbedcrypto.a'
    if not crypto.exists():
        subprocess.run(['cmake', '-S', str(SDK_ROOT/'apps/crypto/mbedtls/mbedtls'), '-B', str(build), '-DENABLE_TESTING=OFF', '-DENABLE_PROGRAMS=OFF', '-DMBEDTLS_FATAL_WARNINGS=OFF'], check=True, capture_output=True)
        subprocess.run(['cmake', '--build', str(build), '--target', 'mbedcrypto', '--parallel', '4'], check=True, capture_output=True)
    (tmp_path/'nuttx').mkdir();(tmp_path/'nuttx/config.h').write_text('')
    (tmp_path/'netutils').mkdir()
    (tmp_path/'netutils/cJSON.h').symlink_to(SDK_ROOT/'apps/netutils/cjson/cJSON/cJSON.h')
    (tmp_path/'records').mkdir()
    sample = PROJECT_ROOT/'tests/fixtures/ui/image-sample.jpg'
    harness = r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include "buffer_images.c"
static unsigned searches,downloads,selector,mode;
static sem_t download_entered,download_release;static bool block_download;
bool buffer_agent_valid_id(const char *id){if(!id||!*id||strlen(id)>=64)return false;for(const char *p=id;*p;p++)if(!((*p>='a'&&*p<='z')||(*p>='0'&&*p<='9')||*p=='-'))return false;return true;}
int buffer_agent_private_directory(const char *path){return mkdir(path,0700)&&errno!=EEXIST?-errno:0;}
int buffer_agent_atomic_bytes(const char *path,const char *data,size_t length,size_t limit){assert(length<=limit);FILE *file=fopen(path,"wb");if(!file)return -errno;assert(fwrite(data,1,length,file)==length);assert(!fclose(file));return 0;}
int buffer_agent_read_bytes(const char *path,size_t limit,char **data,size_t *length){*data=NULL;*length=0;FILE *file=fopen(path,"rb");if(!file)return -errno;fseek(file,0,SEEK_END);long size=ftell(file);rewind(file);if(size<0||(size_t)size>limit){fclose(file);return -EFBIG;}*data=calloc(1,(size_t)size+1);assert(*data);assert(fread(*data,1,size,file)==(size_t)size);fclose(file);*length=size;return 0;}
int buffer_agent_atomic_json(const char *path,const cJSON *json){char *bytes=cJSON_PrintUnformatted(json);assert(bytes);int result=buffer_agent_atomic_bytes(path,bytes,strlen(bytes),65536);free(bytes);return result;}
cJSON *buffer_agent_read_json(const char *path,size_t limit){char *bytes;size_t length;if(buffer_agent_read_bytes(path,limit,&bytes,&length))return NULL;cJSON *json=cJSON_Parse(bytes);free(bytes);return json;}
int buffer_https_get_public(const char *url,unsigned timeout,char **bytes,size_t *length,int *status){
 assert(timeout<=8000);*status=200;
 if(strstr(url,"api.ruseo.cn")){
  searches++;assert(strstr(url,"words=%E6%B5%B7%E5%B8%A6")&&strstr(url,"limit=4&type=1"));
  char body[1024];
  if(mode==1)strcpy(body,"{\"code\":500,\"data\":{\"code\":200,\"res\":[]}}");
  else if(mode==2)strcpy(body,"{\"code\":0,\"data\":{\"code\":200,\"res\":[\"http://img1.baidu.com/x\",\"https://img1.baidu.com.evil/x\",\"https://127.0.0.1/x\"]}}");
  else snprintf(body,sizeof(body),"{\"code\":0,\"data\":{\"code\":200,\"res\":[\"https://img1.baidu.com/test/%u.jpg\"]}}",selector);
  *bytes=strdup(body);*length=strlen(body);return 0;
 }
 if(block_download){sem_post(&download_entered);sem_wait(&download_release);}
 downloads++;assert(!strncmp(url,"https://img1.baidu.com/",23));
 return buffer_agent_read_bytes(SAMPLE_JPEG,262144,bytes,length);
}
static void search_id(char id[65]){
 cJSON *result=buffer_images_search("capture","海带",1);assert(result&&cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(result,"success")));
 const cJSON *images=cJSON_GetObjectItemCaseSensitive(result,"images");assert(cJSON_GetArraySize(images)==1);strcpy(id,image_text(cJSON_GetArrayItem(images,0),"image_id"));assert(image_id_valid(id));cJSON_Delete(result);
}
int main(void){
 char ids[IMAGE_SLOTS+1][65];struct buffer_image_view leases[IMAGE_SLOTS];
 search_id(ids[0]);assert(searches==1&&!downloads);
 char forged[65];memset(forged,'a',64);forged[64]=0;assert(buffer_images_select("capture",forged)==-ENOENT);assert(buffer_images_select("other",ids[0])==-ENOENT);
 sem_init(&download_entered,0,0);sem_init(&download_release,0,0);block_download=true;
 assert(!buffer_images_request("capture",ids[0]));sem_wait(&download_entered);assert(!downloads);
 assert(!buffer_images_request("capture",ids[0]));sem_post(&download_release);buffer_images_shutdown();assert(downloads==1);block_download=false;sem_destroy(&download_entered);sem_destroy(&download_release);
 assert(!buffer_images_acquire(ids[0],&leases[0]));assert(leases[0].width==176&&leases[0].height==160);assert((uintptr_t)leases[0].pixels%64==0);
 assert(leases[0].pixels[0]!=leases[0].pixels[176*159]);
 pthread_mutex_lock(&image_lock);struct buffer_image_view temporary;assert(buffer_images_acquire(ids[0],&temporary)==-EAGAIN);pthread_mutex_unlock(&image_lock);
 assert(!buffer_images_prepare("capture",ids[0])&&downloads==1);
 for(selector=1;selector<IMAGE_SLOTS;selector++){search_id(ids[selector]);assert(!buffer_images_prepare("capture",ids[selector]));assert(!buffer_images_acquire(ids[selector],&leases[selector]));}
 selector=IMAGE_SLOTS;search_id(ids[IMAGE_SLOTS]);assert(buffer_images_prepare("capture",ids[IMAGE_SLOTS])==-EBUSY);assert(downloads==IMAGE_SLOTS+1);
 uint16_t pinned=leases[0].pixels[0];assert(leases[0].pixels[0]==pinned);
 buffer_images_release(leases[0].lease);assert(!buffer_images_prepare("capture",ids[IMAGE_SLOTS])&&downloads==IMAGE_SLOTS+1);
 assert(!buffer_images_acquire(ids[IMAGE_SLOTS],&temporary));buffer_images_release(leases[0].lease);assert(slots[temporary.lease&15].refs==1);buffer_images_release(temporary.lease);
 for(unsigned i=1;i<IMAGE_SLOTS;i++)buffer_images_release(leases[i].lease);
 memset(slots,0,sizeof(slots));assert(!buffer_images_prepare("capture",ids[IMAGE_SLOTS])&&downloads==IMAGE_SLOTS+1);
 for(selector=IMAGE_SLOTS+1;selector<18;selector++){search_id(ids[0]);assert(!buffer_images_prepare("capture",ids[0]));}
 char photo_directory[192];assert(!image_directory(photo_directory,sizeof(photo_directory)));DIR *dir=opendir(photo_directory);assert(dir);unsigned cached=0;struct dirent *entry;while((entry=readdir(dir)))if(strstr(entry->d_name,".jpg"))cached++;closedir(dir);assert(cached==12);
 mode=1;cJSON *result=buffer_images_search("capture","海带",1);assert(!cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(result,"success")));cJSON_Delete(result);
 mode=2;result=buffer_images_search("capture","海带",1);assert(cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(result,"success"))&&!cJSON_GetArraySize(cJSON_GetObjectItemCaseSensitive(result,"images")));cJSON_Delete(result);
 uint16_t pixels[IMAGE_PIXELS];assert(decode_photo((unsigned char *)"invalid",7,pixels)<0);
 return 0;
}
'''
    source = tmp_path/'images.c';source.write_text(harness)
    binary = tmp_path/'image_test'
    subprocess.run([*CC,'-std=c11','-D_DEFAULT_SOURCE','-Wall','-Wextra','-Werror','-pthread','-fsanitize=address,undefined',f'-DCONFIG_BUFFER_VELA_DATA_DIR="{tmp_path}"',f'-DSAMPLE_JPEG="{sample}"','-I'+str(tmp_path),'-I'+str(PROJECT_ROOT/'app/buffer_app'),'-I'+str(SDK_ROOT/'apps/crypto/mbedtls/mbedtls/include'),str(source),str(SDK_ROOT/'apps/netutils/cjson/cJSON/cJSON.c'),str(crypto),'-ljpeg','-lm','-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True,timeout=15)
