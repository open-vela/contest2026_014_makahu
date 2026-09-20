"""Run production GATT service callbacks with a controlled Bluetooth/Wi-Fi boundary."""
from pathlib import Path
import re, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
app=root/'app/buffer_app'; cjson=root.parent/'apps/netutils/cjson/cJSON'
source=re.sub(r'^#include .*$', '', (app/'buffer_ble_service.c').read_text(),flags=re.M)
harness=r'''
#include <assert.h>
#include "cJSON.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <time.h>
#include <errno.h>
#include "buffer_enrollment.h"
#define CONFIG_BUFFER_VELA_BLE 1
#define BUFFER_ID_SIZE 80
#define BT_TRANSPORT_BLE 0
#define PAIR_TYPE_PASSKEY_NOTIFICATION 3
#define BT_IO_CAPABILITY_UNKNOW 255
#define BT_IO_CAPABILITY_DISPLAYONLY 0
#define BT_STATUS_SUCCESS 0
#define GATT_STATUS_SUCCESS 0
#define GATT_PERM_AUTHEN_REQUIRED 8
#define GATT_PERM_MITM_REQUIRED 16
#define GATT_PERM_READ 1
#define GATT_PERM_WRITE 2
#define GATT_PROP_READ 2
#define GATT_PROP_WRITE 8
typedef struct { unsigned char val[6]; } bt_address_t;
typedef void bt_instance_t;
typedef void *gatts_handle_t;
typedef int bt_io_capability_t;
typedef int bt_transport_t;
typedef int bt_pair_type_t;
typedef int gatt_status_t;
typedef uint16_t (*read_cb)(void*,bt_address_t*,uint16_t,uint32_t);
typedef uint16_t (*write_cb)(void*,bt_address_t*,uint16_t,const uint8_t*,uint16_t,uint16_t);
typedef struct { int permission; read_cb read; write_cb write; } gatt_attr_db_t;
typedef struct { gatt_attr_db_t *attr_db; size_t attr_num; } gatt_srv_db_t;
#define BT_UUID_DECLARE_128(...) 0
#define GATT_H_PRIMARY_SERVICE(uuid,id) {1,NULL,NULL}
#define GATT_H_CHARACTERISTIC_USER_RSP(uuid,prop,perm,read,write,id) {perm,read,write}
typedef struct {
 size_t size;
 void (*on_connected)(void*,bt_address_t*);
 void (*on_disconnected)(void*,bt_address_t*);
 void (*on_attr_table_added)(void*,int,uint16_t);
} gatts_callbacks_t;
typedef struct {
 void (*on_pair_request)(void*,bt_address_t*);
 void (*on_pair_display)(void*,bt_address_t*,int,int,uint32_t);
} adapter_callbacks_t;
static bool encrypted, accepted, disconnect_on_wifi;
static int table_status;
static bt_address_t disconnect_peer={{1}};
static int wifi_result, wifi_calls, saves, syncs, disconnects, cancels;
static int io_cap=4;
static uint8_t response[320]; static size_t response_len;
static gatts_callbacks_t *callbacks;
static int bt_addr_compare(const bt_address_t*a,const bt_address_t*b){return memcmp(a,b,sizeof(*a));}
static bool bt_device_is_encrypted(void*i,bt_address_t*p,int t){(void)i;(void)p;(void)t;return encrypted;}
static int bt_device_pair_request_reply(void*i,bt_address_t*p,bool allow){(void)i;(void)p;accepted=allow;return 0;}
static int bt_device_cancel_bond(void*i,bt_address_t*p){(void)i;(void)p;cancels++;return 0;}
static int bt_gatts_disconnect(void*s,bt_address_t*p){(void)s;disconnects++;callbacks->on_disconnected(s,p);return 0;}
static int bt_gatts_response(void*s,bt_address_t*p,uint32_t r,uint8_t*v,uint16_t n){(void)s;(void)p;(void)r;assert(n<=20);memcpy(response,v,n);response_len=n;return 0;}
static int bt_adapter_get_io_capability(void*i){(void)i;return io_cap;}
static int bt_adapter_set_io_capability(void*i,int cap){(void)i;io_cap=cap;return 0;}
static int bt_device_set_bondable_le(void*i,bool b){(void)i;assert(b);return 0;}
static int bt_device_set_security_level(void*i,int level,int t){(void)i;(void)t;assert(level==4);return 0;}
static void*bt_adapter_register_callback(void*i,const adapter_callbacks_t*c){(void)c;return i;}
static int bt_adapter_unregister_callback(void*i,void*c){(void)i;(void)c;return 0;}
static int bt_gatts_register_service(void*i,void**s,gatts_callbacks_t*c){*s=i;callbacks=c;return 0;}
static int bt_gatts_unregister_service(void*s){(void)s;return 0;}
static int bt_gatts_add_attr_table(void*s,gatt_srv_db_t*d){
 assert(d->attr_num==3);
 assert((d->attr_db[1].permission & 24)==24);
 assert((d->attr_db[2].permission & 24)==24);
 callbacks->on_attr_table_added(s,table_status,1);return 0;
}
static void buffer_pairing_snapshot(char*d,size_t n,char*c,size_t m){(void)c;(void)m;snprintf(d,n,"vela-test");}
static int buffer_wifi_configure(const char*s,const char*p){assert(!strcmp(s,"wifi"));assert(!strcmp(p,"password"));wifi_calls++;if(disconnect_on_wifi)callbacks->on_disconnected((void*)1,&disconnect_peer);return wifi_result;}
static int reconnect_calls;
static int buffer_wifi_reconnect(void){wifi_calls++;reconnect_calls++;return wifi_result;}
static int buffer_store_save_enrollment(const char*h,const char*t,const char*p,const char*r){assert(strlen(r)==32);assert(!strcmp(h,"0.0.0.0"));assert(strlen(t)==64);assert(!strcmp(p,"phone"));saves++;return 0;}
static void buffer_request_sync(void){syncs++;}
void buffer_ble_service_stop(void);
''' + source + r'''
static char payload[768]="{\"type\":\"buffer.configure\",\"protocol\":1,\"request_id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"phone_device_id\":\"phone\",\"token\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"ssid\":\"wifi\",\"password\":\"password\"}";
static bt_address_t peer={{1}}, other={{2}};
static void submit(void){
 size_t n=strlen(payload);
 for(size_t off=0;off<n;off+=16){
  uint8_t frame[20];size_t k=n-off;if(k>16)k=16;
  frame[0]=off>>8;frame[1]=off;frame[2]=n>>8;frame[3]=n;
  memcpy(frame+4,payload+off,k);write_config(g_service,&peer,2,frame,k+4,0);
 }
}
static void result(char*out,size_t size){
 uint8_t cursor[2]={0};size_t off=0,total;
 do {
  cursor[0]=off>>8;cursor[1]=off;write_config(g_service,&peer,2,cursor,2,0);
  read_result(g_service,&peer,3,1);
  assert((((size_t)response[0]<<8)|response[1])==off);
  total=((size_t)response[2]<<8)|response[3];assert(total<size);
  assert(response_len>4);memcpy(out+off,response+4,response_len-4);off+=response_len-4;
 }while(off<total);
 out[off]=0;
}
int main(void){
 table_status=1;assert(buffer_ble_service_start((void*)1)<0 && io_cap==4);
 table_status=0;assert(buffer_ble_service_start((void*)1)==0);assert(io_cap==0);
 assert(buffer_ble_service_claim_lan());
 connected(g_service,&peer);assert(!g_connected && disconnects==1);
 buffer_ble_service_release_lan();disconnects=0;
 connected(g_service,&peer);assert(buffer_ble_service_busy());
 assert(!buffer_ble_service_claim_lan());
 connected(g_service,&other);assert(disconnects==1 && g_connected);
 pair_request(NULL,&other);assert(!accepted);
 pair_request(NULL,&peer);assert(accepted);
 pair_display(NULL,&peer,0,3,42);char code[7];buffer_ble_service_code(code,sizeof(code));assert(!strcmp(code,"000042"));
 pair_display(NULL,&peer,0,0,42);assert(cancels==1);
 submit();buffer_ble_service_process();assert(wifi_calls==0 && saves==0);
 encrypted=true;
 read_result(g_service,&other,3,1);assert(response_len==4);
 submit();assert(g_pending);assert(wifi_calls==0);
 wifi_result=-EIO;buffer_ble_service_process();assert(wifi_calls==1 && saves==0);
 char text[320];result(text,sizeof(text));assert(strstr(text,"failed") && strstr(text,"aaaaaaaa"));
 submit();buffer_ble_service_process();assert(wifi_calls==1); /* replay result */
 /* Explicit new session with a fresh request would retry; reset isolates data. */
 buffer_ble_service_stop();assert(io_cap==4 && !buffer_ble_service_busy() && g_frame.received==0);
 assert(buffer_ble_service_start((void*)1)==0);connected(g_service,&peer);
 wifi_result=0;submit();buffer_ble_service_process();assert(saves==1 && syncs==1);
 result(text,sizeof(text));assert(strstr(text,"network_ready") && strstr(text,"vela-test"));
 submit();buffer_ble_service_process();assert(saves==1 && wifi_calls==2);
 g_deadline=0;submit();buffer_ble_service_process();assert(!g_connected && saves==1);
 buffer_ble_service_stop();assert(io_cap==4);
 assert(buffer_ble_service_start((void*)1)==0);connected(g_service,&peer);
 submit();disconnected(g_service,&peer);buffer_ble_service_process();assert(wifi_calls==2 && saves==1 && !strcmp(g_state,"idle") && g_request.token[0]==0);
 buffer_ble_service_stop();assert(buffer_ble_service_start((void*)1)==0);connected(g_service,&peer);
 disconnect_on_wifi=true;submit();buffer_ble_service_process();assert(saves==1 && g_error==-ECANCELED);
 buffer_ble_service_stop();
 /* Reuse stored Wi-Fi without transmitting or overwriting credentials. */
 cJSON *bind=cJSON_Parse(payload);assert(bind);
 cJSON_ReplaceItemInObjectCaseSensitive(bind,"type",cJSON_CreateString("buffer.bind"));
 cJSON_DeleteItemFromObjectCaseSensitive(bind,"ssid");cJSON_DeleteItemFromObjectCaseSensitive(bind,"password");
 char *encoded=cJSON_PrintUnformatted(bind);assert(encoded);strcpy(payload,encoded);free(encoded);cJSON_Delete(bind);
 int before=saves;wifi_result=-ENOENT;
 assert(buffer_ble_service_start((void*)1)==0);connected(g_service,&peer);
 submit();buffer_ble_service_process();assert(reconnect_calls==1 && saves==before);
 result(text,sizeof(text));assert(strstr(text,"failed"));buffer_ble_service_stop();
 wifi_result=0;assert(buffer_ble_service_start((void*)1)==0);connected(g_service,&peer);
 submit();buffer_ble_service_process();assert(reconnect_calls==2 && saves==before+1);
 result(text,sizeof(text));assert(strstr(text,"network_ready"));buffer_ble_service_stop();
 puts("PASS: GATT secure permissions, peer isolation, passkey display, queued Wi-Fi, failure/replay, paged results, expiry and cleanup");
}
'''
with tempfile.TemporaryDirectory() as tmp:
    t=Path(tmp);(t/'netutils').mkdir();(t/'netutils/cJSON.h').symlink_to(cjson/'cJSON.h')
    (t/'test.c').write_text(harness)
    subprocess.run(['cc','-std=c11','-D_POSIX_C_SOURCE=200809L','-Wall','-Wextra','-Werror','-pthread','-I'+str(app),'-I'+tmp,'-I'+str(cjson),str(t/'test.c'),str(app/'buffer_enrollment.c'),str(cjson/'cJSON.c'),'-lm','-o',str(t/'test')],check=True)
    subprocess.run([str(t/'test')],check=True,timeout=20)
