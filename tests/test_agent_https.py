"""Actual SDK mbedTLS + production OOBE/store/worker against a local HTTPS fixture.

No external API, credential, microphone or private recording is used. The HTTP
fixture supplies deterministic model responses; production has no injected model
or insecure TLS path. Device radio and a paid cloud model are separate acceptance.
"""
from host_support import PROJECT_ROOT, SDK_ROOT, CC
import base64
import ctypes
import errno
import hashlib
import http.server
import json
import math
import os
from pathlib import Path
import ssl
import struct
import subprocess
import tempfile
import threading
import time
import wave

root = PROJECT_ROOT
sdk = SDK_ROOT
mbedtls = sdk / "apps/crypto/mbedtls/mbedtls"
build = Path(os.environ.get("BUFFER_AGENT_MBEDTLS_BUILD", "/tmp/buffer-agent-mbedtls"))
subprocess.run([
    "cmake", "-S", str(mbedtls), "-B", str(build), "-DENABLE_TESTING=OFF",
    "-DENABLE_PROGRAMS=OFF", "-DMBEDTLS_FATAL_WARNINGS=OFF", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
], check=True, stdout=subprocess.DEVNULL)
subprocess.run(["cmake", "--build", str(build), "--parallel", "4"],
               check=True, stdout=subprocess.DEVNULL)


def openssl(*args):
    subprocess.run(["openssl", *map(str, args)], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


from agent_fixture_service import Service, state


with tempfile.TemporaryDirectory(prefix="buffer-agent-https-") as temporary:
    work = Path(temporary)
    data = work / "data"
    data.mkdir(mode=0o700)
    (data / "queue").mkdir(mode=0o700)
    include = work / "include"
    (include / "nuttx").mkdir(parents=True)
    (include / "nuttx/config.h").write_text("#define CONFIG_BUFFER_VELA_SOURCE_JOURNAL 1\n" if os.environ.get("BUFFER_JOURNAL_CONTRACT") == "1" else "")
    (include / "netutils").mkdir()
    cjson = sdk / "apps/netutils/cjson/cJSON"
    (include / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
    ca = work / "ca.pem"
    ca_key = work / "ca.key"
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", ca_key,
            "-out", ca, "-days", "2", "-subj", "/CN=Buffer isolated test CA")

    def certificate(name, san, trusted=True):
        key, csr, cert, ext = [work / (name + suffix) for suffix in (".key", ".csr", ".pem", ".ext")]
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", key,
                "-out", csr, "-subj", "/CN=" + san)
        ext.write_text("subjectAltName=DNS:" + san + "\nextendedKeyUsage=serverAuth\n")
        if trusted:
            openssl("x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key,
                    "-CAcreateserial", "-out", cert, "-days", "1", "-extfile", ext)
        else:
            openssl("x509", "-req", "-in", csr, "-signkey", key,
                    "-out", cert, "-days", "1", "-extfile", ext)
        return cert, key

    servers = []

    def server(cert):
        service = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Service)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(*map(str, cert))
        service.socket = context.wrap_socket(service.socket, server_side=True)
        thread = threading.Thread(target=service.serve_forever, daemon=True)
        thread.start()
        servers.append(service)
        return f"https://localhost:{service.server_port}/v1"

    good = server(certificate("good", "localhost"))
    wrong_host = server(certificate("wrong", "wrong.invalid"))
    untrusted = server(certificate("untrusted", "localhost", trusted=False))
    main = (root / "app/buffer_app/buffer_app_main.c").read_text()
    start = main.index("int buffer_update_cards(")
    commit = main[start:main.index("\nint buffer_sync_remote_mode", start)]
    glue = work / "glue.c"
    glue.write_text(r'''
#include "buffer_agent.h"
#include "buffer_fabric.h"
#include "buffer_replica.h"
#include "buffer_crdt_sync.h"
#include "buffer_assistant.h"
#include "buffer_agent_assistant.h"
#include "buffer_a2ui_internal.h"
#include "buffer_coordinator.h"
#include "buffer_agent_input.h"
#include "buffer_agent.h"
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
struct buffer_runtime_s g_buffer = {.lock = PTHREAD_MUTEX_INITIALIZER};
static unsigned assistant_busy_attempts;
void fixture_assistant_busy(unsigned attempts){assistant_busy_attempts=attempts;}
int __real_buffer_assistant_execute(const struct buffer_assistant_request_s *,struct buffer_assistant_result_s *);
int __wrap_buffer_assistant_execute(const struct buffer_assistant_request_s *request,struct buffer_assistant_result_s *result)
{if(assistant_busy_attempts){assistant_busy_attempts--;return -EAGAIN;}return __real_buffer_assistant_execute(request,result);}
static pthread_mutex_t g_cards_commit_lock=PTHREAD_MUTEX_INITIALIZER;
static unsigned fixture_audio_saves;
static int64_t fixture_pcm_created_at=60000;
void fixture_pcm_time(int64_t at){fixture_pcm_created_at=at;}
static int fixture_audio_save_error;
void fixture_set_audio_save_error(int error){fixture_audio_save_error=error;}
int buffer_audio_store_pcm(const struct buffer_memory_capture_s *job)
{ (void)job;fixture_audio_saves++;return fixture_audio_save_error; }
unsigned fixture_audio_save_count(void){return fixture_audio_saves;}
int fixture_submit_pcm(const char *id,int silent)
{
  struct buffer_memory_capture_s job={.bytes=8000,.duration_ms=250,.created_at=fixture_pcm_created_at,
      .target=BUFFER_CAPTURE_TARGET_AUTO};
  snprintf(job.id,sizeof(job.id),"%s",id);strcpy(job.mode,"normal");
  job.pcm=malloc(job.bytes);if(!job.pcm)return -ENOMEM;
  for(unsigned i=0;i<job.bytes;i+=2){job.pcm[i]=silent?0:32;job.pcm[i+1]=silent?0:1;}
  int ret=buffer_agent_submit_audio(&job);if(ret)free(job.pcm);return ret;
}


void buffer_set_status(const char *format, ...) { (void)format; }
int buffer_wifi_configure(const char *ssid, const char *password)
{ (void)ssid; (void)password; return -ENOTSUP; }
static bool fixture_network_ready = true;
static int fixture_reconnect_result;
static int fixture_reconnect_calls;
int buffer_wifi_reconnect(void)
{ fixture_reconnect_calls++; if (!fixture_reconnect_result) fixture_network_ready = true;
  return fixture_reconnect_result; }
/* This harness has an actual ready host loopback network. Wi-Fi provisioning
 * remains unsupported here; HTTPS still uses real sockets and mbedTLS. */
int buffer_fabric_identity_for_peer(const char *peer,char local[65]) { if(strcmp(peer,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")) return -EACCES; memset(local,'a',64);local[64]=0;return 0; }
int buffer_fabric_request_sync(void) { return 0; }
bool buffer_wifi_is_ready(void) { return fixture_network_ready; }
void fixture_network(int ready, int result)
{ fixture_network_ready = ready != 0; fixture_reconnect_result = result; }
int fixture_reconnections(void) { return fixture_reconnect_calls; }
static int authorize(void *ctx, enum buffer_oobe_operation_e operation)
{ (void)operation; return ctx ? 0 : -EACCES; }
int fixture_dispatch(const char *data, size_t length, int allowed, char **reply)
{
  struct buffer_oobe_authorization_s auth = {authorize, allowed ? (void *)1 : NULL};
  return buffer_oobe_handle_message(&auth, (const unsigned char *)data, length, reply);
}
void fixture_free(void *p) { free(p); }
static uint64_t coordinator_ms;
int fixture_coordinator_clock(clockid_t ignored,struct timespec *out) {
  (void)ignored;
  if (!coordinator_ms) { struct timespec now;clock_gettime(CLOCK_MONOTONIC,&now);coordinator_ms=(uint64_t)now.tv_sec*1000+now.tv_nsec/1000000; }
  out->tv_sec=coordinator_ms/1000;out->tv_nsec=(coordinator_ms%1000)*1000000;return 0;
}
void fixture_coordinator_advance(uint64_t elapsed) { coordinator_ms+=elapsed; }

int fixture_crdt_ack(const char *raw) { cJSON *j=cJSON_Parse(raw);int ret=buffer_crdt_sync_ack("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",1,j);cJSON_Delete(j);return ret; }
void buffer_request_mode(const char *mode) { pthread_mutex_lock(&g_buffer.lock);
  snprintf(g_buffer.mode,sizeof(g_buffer.mode),"%s",mode);g_buffer.mode_dirty=true;pthread_mutex_unlock(&g_buffer.lock); }
void fixture_phone(int online) { g_buffer.phone_synced = online != 0; }
int buffer_fabric_identity_pair(char local[65], char peer[65])
{ memset(local, 'a', 64); local[64]=0; memset(peer, 'b', 64); peer[64]=0; return 0; }
void buffer_fabric_snapshot(struct buffer_fabric_status_s *status)
{ memset(status, 0, sizeof(*status)); }
int fixture_cli_text(int automatic, const char *capture, const char *text)
{
  char *legacy[] = {"text", (char *)capture, (char *)text};
  char *auto_text[] = {"auto-text", (char *)capture, (char *)text};
  return buffer_agent_command(3, automatic ? auto_text : legacy);
}
int fixture_handoff(const char *data, size_t length, int allowed, char **reply)
{
  struct buffer_oobe_authorization_s auth={authorize,allowed ? (void *)1 : NULL};
  return buffer_agent_input_handle_assistant_request(&auth,
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      (const unsigned char *)data,length,reply);
}
int fixture_agent_flags(void)
{ struct buffer_agent_status_s s; buffer_agent_snapshot(&s); return (s.ready ? 1 : 0) | (s.skipped ? 2 : 0); }
int fixture_coord_local(void) { return buffer_coordinator_local(); }
int fixture_setup_complete(void) { return buffer_oobe_setup_complete(); }
int fixture_capture_lock_free(void)
{ int ret=pthread_mutex_trylock(&g_buffer_capture_lock); if (!ret) pthread_mutex_unlock(&g_buffer_capture_lock); return ret==0; }
void fixture_capture_lock(void) { pthread_mutex_lock(&g_buffer_capture_lock); }
void fixture_capture_unlock(void) { pthread_mutex_unlock(&g_buffer_capture_lock); }
int fixture_replica(const char *data,size_t length,int allowed,char **reply)
{
  struct buffer_oobe_authorization_s auth={authorize,allowed ? (void *)1 : NULL};
  return buffer_replica_receive(&auth,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      (const unsigned char *)data,length,reply);
}
int fixture_journal_restart_history_check(void)
{
 static char timer_id[BUFFER_ASSISTANT_ID_SIZE];static unsigned stage;
 struct buffer_assistant_request_s *request=calloc(1,sizeof(*request));if(!request)return -ENOMEM;
 struct buffer_assistant_result_s result;int ret=0;
 if(stage==0){
  request->operation=BUFFER_ASSISTANT_TIMER_CREATE;strcpy(request->operation_id,"journal-restart-create");strcpy(request->label,"Journal restart check");request->duration_ms=30000;
  ret=buffer_assistant_execute(request,&result);if(ret){free(request);return ret;}strcpy(timer_id,result.object_id);stage=1;
 }
 request->operation=BUFFER_ASSISTANT_TIMER_RESTART;strcpy(request->operation_id,"journal-restart-repeat");strcpy(request->object_id,timer_id);
 ret=buffer_assistant_execute(request,&result);
 if(!ret){
  if(stage==1){if(result.duplicate){free(request);return -EPROTO;}stage=2;}
  ret=buffer_assistant_execute(request,&result);
  if(!ret&&(!result.duplicate||result.state!=BUFFER_ASSISTANT_RUNNING||strcmp(result.object_id,timer_id)))ret=-EPROTO;
 }
 free(request);return ret;
}
static char recipe_restore_surface[BUFFER_A2UI_SURFACE_ID_SIZE];
const char *fixture_recipe_restore_surface(void){return recipe_restore_surface;}
int fixture_recipe_restore_seed(void)
{
 struct buffer_assistant_request_s *request=calloc(1,sizeof(*request));if(!request)return -ENOMEM;
 request->operation=BUFFER_ASSISTANT_RECIPE_SHOW;strcpy(request->operation_id,"recipe-recovery-source");strcpy(request->recipe_title,"Recovery recipe");strcpy(request->ingredients,"Water");
 request->step_count=2;strcpy(request->steps[0].title,"Heat");strcpy(request->steps[0].detail,"Warm slowly");request->steps[0].timer_ms=600000;strcpy(request->steps[1].title,"Serve");
 struct buffer_assistant_result_s result;int ret=buffer_assistant_execute(request,&result);
 if(!ret){
  struct buffer_a2ui_surface_s *surface=malloc(sizeof(*surface));if(!surface){free(request);return -ENOMEM;}
  ret=buffer_a2ui_model_get_active(surface);
  if(!ret){
   strcpy(recipe_restore_surface,surface->id);memset(request,0,sizeof(*request));request->operation=BUFFER_ASSISTANT_TIMER_CREATE;
   const struct buffer_a2ui_component_s *step=buffer_a2ui_find_component(surface,"step1");if(!step){free(surface);free(request);return -EINVAL;}
   strcpy(request->operation_id,step->payload.recipe_step.request_id);strcpy(request->label,step->payload.recipe_step.title);request->duration_ms=step->payload.recipe_step.timer_ms;
   ret=buffer_assistant_execute(request,&result);
  }
  free(surface);
 }
 free(request);return ret;
}
int fixture_recipe_restore_verify(const char *surface_id)
{
 int ret=buffer_a2ui_present_reply("foreground-before-recovery","Keep current foreground");if(ret)return ret;
 struct buffer_a2ui_surface_s *before=malloc(sizeof(*before)),*surface=malloc(sizeof(*surface));
 if(!before||!surface){free(before);free(surface);return -ENOMEM;}
 ret=buffer_a2ui_model_get_active(before);char image[65];
 if(!ret)ret=buffer_assistant_restore_recipe("recipe-recovery-source",surface_id,image);
 if(!ret){ret=buffer_a2ui_model_get_active(surface);if(!ret&&strcmp(surface->id,before->id))ret=-EPROTO;}
 if(!ret)ret=buffer_a2ui_model_get_surface(surface_id,surface);
 if(!ret){
  const struct buffer_a2ui_component_s *step=buffer_a2ui_find_component(surface,"step1");
  if(!step||!step->payload.recipe_step.timer_id[0]||strcmp(surface->recipe_operation_id,"recipe-recovery-source"))ret=-EPROTO;
  else{struct buffer_assistant_item_s timer;ret=buffer_assistant_get(BUFFER_ASSISTANT_EVENT_TIMER,step->payload.recipe_step.timer_id,&timer);if(!ret&&timer.remaining_ms>600000)ret=-EPROTO;}
 }
 if(!ret){uint64_t sequence=surface->sequence;ret=buffer_assistant_restore_recipe("recipe-recovery-source",surface_id,image);if(!ret)ret=buffer_a2ui_model_get_surface(surface_id,surface);if(!ret&&surface->sequence!=sequence)ret=-EPROTO;}
 free(before);free(surface);return ret;
}
int fixture_ui_action_register(void){return buffer_a2ui_set_action_handler(buffer_agent_assistant_ui_action,NULL);}
int fixture_ui_action_status(char *id,int *pending,int *result)
{
 struct buffer_a2ui_action_status_s status;int ret=buffer_a2ui_model_action_status("ui-action-surface","choose",&status);
 if(!ret){strcpy(id,status.id);*pending=status.pending?(status.accepted?2:1):0;*result=status.result;}return ret;
}
int fixture_timer_count(void)
{
  struct buffer_assistant_item_s items[BUFFER_ASSISTANT_MAX_TIMERS]; size_t count=0;
  return buffer_assistant_snapshot(BUFFER_ASSISTANT_EVENT_TIMER,items,BUFFER_ASSISTANT_MAX_TIMERS,&count) ? -1 : (int)count;
}
int fixture_active_has_type(int type)
{
  struct buffer_a2ui_surface_s surface;
  if (buffer_a2ui_model_get_active(&surface) < 0) return 0;
  for (size_t i=0; i<surface.component_count; i++)
    if (surface.components[i].type == (enum buffer_a2ui_component_type_e)type) return 1;
  return 0;
}
int fixture_heartbeat(const char *wire)
{
  cJSON *heartbeat=cJSON_Parse(wire);
  int ret=buffer_coordinator_receive("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",heartbeat);
  cJSON_Delete(heartbeat); return ret;
}
int fixture_input_next(uint64_t now,char **message) { (void)buffer_coordinator_local(); char owner[65];uint64_t term;buffer_coordinator_status(owner,sizeof(owner),&term);if(strcmp(owner,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")){buffer_agent_input_reset();*message=NULL;return 0;}return buffer_agent_input_next_for_peer(owner,term,now,message); }
int fixture_input_ack(const char *wire)
{ cJSON *reply=cJSON_Parse(wire); char owner[65]; uint64_t term; buffer_coordinator_status(owner,sizeof(owner),&term); int ret=buffer_agent_input_ack_for_peer(owner,term,reply); cJSON_Delete(reply); return ret; }
''' + '\nint buffer_ui_work_submit(void (*run)(void *),void *context){run(context);free(context);return 0;}\n' + commit)
    with glue.open("a") as stream:
        stream.write(r'''
static unsigned image_downloads;
unsigned fixture_image_downloads(void){return __atomic_load_n(&image_downloads,__ATOMIC_ACQUIRE);}
int __wrap_buffer_https_get_public(const char *url,unsigned timeout,char **bytes,size_t *length,int *status)
{
 (void)timeout;*status=200;
 if(strstr(url,"api.ruseo.cn/api/baidu_image?")){
  *bytes=strdup("{\"code\":0,\"data\":{\"code\":200,\"res\":[\"https://img1.baidu.com/test/recipe.jpg\"]}}");*length=strlen(*bytes);return 0;
 }
 if(strcmp(url,"https://img1.baidu.com/test/recipe.jpg"))return -EACCES;
 __atomic_add_fetch(&image_downloads,1,__ATOMIC_RELEASE);FILE *file=fopen(IMAGE_FIXTURE_PATH,"rb");if(!file)return -errno;fseek(file,0,SEEK_END);long size=ftell(file);rewind(file);if(size<=0||size>262144){fclose(file);return -EFBIG;}*bytes=malloc(size);if(!*bytes){fclose(file);return -ENOMEM;}*length=fread(*bytes,1,size,file);fclose(file);return *length==(size_t)size?0:-EIO;
}
const char *fixture_active_image(void)
{static struct buffer_a2ui_surface_s surface;if(buffer_a2ui_model_get_active(&surface))return "";return surface.image_id;}
''')
    library = work / "agent.so"
    # The production calendar now links its pinned recurrence engine. Keep
    # third-party compilation separate from the application's -Werror policy.
    ical = root / "app/buffer_app/third_party/libical"
    subprocess.run([*CC, "-shared", "-fPIC", "-DHAVE_CONFIG_H", "-I" + str(ical),
                    *map(str, sorted(ical.glob("*.c"))), "-lm", "-pthread", "-o", str(work / "libbuffer_ical.so")], check=True)
    coordinator_source = work / "coordinator.c"
    coordinator_source.write_text('#define clock_gettime fixture_coordinator_clock\n#include "buffer_coordinator.c"\n')
    sources = ["buffer_calendar", "buffer_crdt_sync", "buffer_crdt", "buffer_focus", "buffer_followup", "buffer_https", "buffer_agent_store", "buffer_private_storage", "buffer_source_events", "buffer_source_view", "buffer_source_projector", "buffer_source_envelope", "buffer_source_remote", "buffer_source_remote_blob", "buffer_source_index", "buffer_source_visibility", "buffer_source_wire", "buffer_agent_model", "buffer_agent_assistant", "buffer_images", "buffer_assistant", "buffer_a2ui_model", "buffer_agent_sync", "buffer_replica", "buffer_agent_input", "buffer_agent", "buffer_agent_cli", "buffer_oobe", "buffer_store"]
    subprocess.run([
        *CC, "-shared", "-fPIC", "-std=c11", "-D_GNU_SOURCE", "-D_DEFAULT_SOURCE", "-D_POSIX_C_SOURCE=200809L",
        "-DCONFIG_BUFFER_VELA_FABRIC",
        "-Wall", "-Wextra", "-Werror", "-pthread", "-I" + str(include),
        '-DIMAGE_FIXTURE_PATH="' + str(root / "tests/fixtures/ui/image-sample.jpg") + '"', "-Wl,--wrap=buffer_https_get_public,--wrap=buffer_assistant_execute",
        "-I" + str(root / "app/buffer_app"), "-I" + str(mbedtls / "include"),
        '-DCONFIG_BUFFER_VELA_DATA_DIR="' + str(data) + '"',
        '-DCONFIG_BUFFER_VELA_AGENT_CA_FILE="' + str(ca) + '"',
        "-DCONFIG_BUFFER_VELA_AGENT_HTTP_TIMEOUT_MS=3000",
        *[str(root / "app/buffer_app" / (s + ".c")) for s in sources],
        str(cjson / "cJSON.c"), str(glue), str(coordinator_source),
        *([str(root / "tests/fixtures/journal_io_faults.c"), "-Wl,--wrap=rename,--wrap=fsync"]
          if os.environ.get("BUFFER_JOURNAL_CONTRACT") == "1" else []),
        str(build / "library/libmbedtls.a"), str(build / "library/libmbedx509.a"),
        str(build / "library/libmbedcrypto.a"), "-ljpeg", "-L" + str(work), "-lbuffer_ical",
        "-Wl,-rpath,$ORIGIN", "-o", str(library),
    ], check=True)
    lib = ctypes.CDLL(str(library))
    if os.environ.get("BUFFER_JOURNAL_CONTRACT") == "1":
        from source_journal_contract import run_contract
        try:
            run_contract(lib, data, library)
        finally:
            for service in servers:
                service.shutdown()
                service.server_close()
        raise SystemExit(0)
    lib.buffer_store_save_oobe_completed.argtypes = [ctypes.c_bool]
    lib.buffer_store_save_oobe_completed.restype = ctypes.c_int
    lib.buffer_store_oobe_completed_cached.restype = ctypes.c_bool
    lib.fixture_dispatch.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
    lib.fixture_replica.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
    lib.fixture_free.argtypes = [ctypes.c_void_p]
    lib.buffer_https_post.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
                                     ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_int)]
    lib.buffer_agent_submit_text.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int64]
    lib.buffer_agent_submit_auto_text.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int64]
    lib.buffer_agent_submit_assistant_text.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int64]
    lib.buffer_agent_apply_action.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
    lib.buffer_coordinator_bind.argtypes = [ctypes.c_char_p,ctypes.c_char_p]
    lib.buffer_coordinator_heartbeat.argtypes = [ctypes.c_uint64,ctypes.POINTER(ctypes.c_void_p)]
    lib.fixture_coordinator_advance.argtypes = [ctypes.c_uint64]
    lib.buffer_coordinator_fence.restype = ctypes.c_uint64
    lib.fixture_input_next.argtypes = [ctypes.c_uint64,ctypes.POINTER(ctypes.c_void_p)]
    lib.buffer_agent_input_resolved.argtypes = [ctypes.c_char_p]
    lib.fixture_heartbeat.argtypes = [ctypes.c_char_p]
    lib.fixture_input_ack.argtypes = [ctypes.c_char_p]
    lib.fixture_cli_text.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p]
    lib.fixture_handoff.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)]
    lib.fixture_active_has_type.argtypes = [ctypes.c_int]
    lib.fixture_agent_flags.restype = ctypes.c_int
    lib.fixture_coord_local.restype = ctypes.c_int
    lib.fixture_setup_complete.restype = ctypes.c_int
    lib.fixture_capture_lock_free.restype = ctypes.c_int


    lib.buffer_agent_outbox_next.argtypes = [ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
    lib.buffer_agent_outbox_ack.argtypes = [ctypes.c_char_p, ctypes.c_size_t]

    def outbox():
        pointer = ctypes.c_void_p()
        result = lib.buffer_agent_outbox_next(12288, ctypes.byref(pointer))
        raw = ctypes.string_at(pointer) if pointer.value else None
        lib.fixture_free(pointer)
        assert raw is None or len(raw) <= 12288
        return result, json.loads(raw) if raw else None

    def ack(value):
        raw = json.dumps(value).encode()
        return lib.buffer_agent_outbox_ack(raw, len(raw))

    def post(url):
        result, status = ctypes.c_void_p(), ctypes.c_int()
        code = lib.buffer_https_post(url.encode(), b"fixture-secret", b"{}", 2,
                                    ctypes.byref(result), ctypes.byref(status))
        text = ctypes.string_at(result).decode() if result.value else None
        lib.fixture_free(result)
        return code, status.value, text

    try:
        assert post(good) == (0, 200, '{"probe":true}')
        before = len(state["requests"])
        assert post(wrong_host)[0] == -errno.EACCES
        assert post(untrusted)[0] == -errno.EACCES
        assert len(state["requests"]) == before, "credentials were sent before server verification"
        state["probe"] = "chunked"
        assert post(good)[0] == 0
        for framing in ("keepalive", "chunked_keepalive", "fragmented_keepalive"):
            state["probe"] = framing
            assert post(good) == (0, 200, '{"probe":true}'), framing
        state["probe"] = "truncated"
        assert post(good)[0] == -errno.EPROTO
        state["probe"] = "normal"

        profile = {
            "type": "vela.oobe.request", "protocol": 1,
            "request_id": "a" * 32, "revision": 1, "operation": "agent.configure",
            "payload": {"base_url": good, "completion_model": "mimo-v2.5",
                        "transcription_model": "mimo-v2.5-asr", "api_key": "fixture-secret",
                        "time_zone": "Asia/Shanghai", "language": "zh-CN"},
        }

        def dispatch(request, allowed=1):
            raw = json.dumps(request).encode()
            reply = ctypes.c_void_p()
            code = lib.fixture_dispatch(raw, len(raw), allowed, ctypes.byref(reply))
            result = json.loads(ctypes.string_at(reply)) if reply.value else None
            lib.fixture_free(reply)
            return code, result

        assert dispatch(profile, allowed=0)[0] == -errno.EACCES
        assert not (data / "agent.json").exists()
        assert lib.buffer_agent_submit_text(b"first", b"Buffer first project", b"normal", 1000) == 0
        assert lib.buffer_agent_process_once() == 0
        assert not (data / "cards.json").exists(), "pending/unconfigured text entered the shelf"
        code, reply = dispatch(profile)
        assert code == 0 and reply["state"] == "agent_pending", reply
        assert (data / "agent.json").stat().st_mode & 0o777 == 0o600
        requests_before_reconnect = len(state["requests"])
        lib.fixture_network(0, -errno.ENETUNREACH)
        assert lib.buffer_agent_process_once() == -errno.ENETUNREACH
        assert lib.fixture_reconnections() == 1
        assert len(state["requests"]) == requests_before_reconnect
        lib.fixture_network(0, 0)
        state["auth_fail"] = True
        assert lib.buffer_agent_process_once() == -errno.EPERM
        assert lib.fixture_reconnections() == 2
        denied_calls = len(state["requests"])
        state["auth_fail"] = False
        assert lib.buffer_agent_process_once() == -errno.EPERM
        assert len(state["requests"]) == denied_calls, "authentication failure retried without user/config action"
        lib.buffer_agent_retry()
        assert lib.buffer_agent_process_once() == 0, "real HTTPS LLM/ASR validation failed"
        saved = json.loads((data / "agent.json").read_text())
        assert saved["validated"] is True
        ready_reply = dispatch(profile)[1]
        assert ready_reply["state"] == "ready", "idempotent replay reset validation"
        assert ready_reply["capabilities"]["setup_complete"] is False
        assert not lib.buffer_store_oobe_completed_cached()
        requests_before_oobe_finish = len(state["requests"])
        assert lib.buffer_agent_process_once() == 0
        pending_first = json.loads((data / "records/first.json").read_text())
        assert pending_first["state"] == "pending_classification", \
            "validated Agent processed a user capture before explicit setup completion"
        assert len(state["requests"]) == requests_before_oobe_finish
        assert not (data / "cards.json").exists()
        assert lib.buffer_store_save_oobe_completed(True) == 0
        assert lib.buffer_agent_process_once() == 0
        first = json.loads((data / "records/first.json").read_text())
        first_id = first["segments"][0]["card_id"]
        assert first["state"] == "ready" and first["segments"][0]["related_card_ids"] == []
        assert first["tool_receipts"] == [{"tool": "search_records", "success": True, "count": 0}]
        assert len(json.loads((data / "cards.json").read_text())) == 1
        conflict = {**profile, "payload": {**profile["payload"], "api_key": "other"}}
        assert dispatch(conflict)[1]["error_code"] == "revision_conflict"
        assert json.loads((data / "agent.json").read_text())["request"]["payload"]["api_key"] == "fixture-secret"

        wav = data / "queue/voice.wav"
        with wave.open(str(wav), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b"".join(struct.pack("<h", int(1200 * math.sin(i / 8))) for i in range(4000)))
        (data / "queue/voice.meta").write_text("created_at=2000\nduration_ms=250\nmode=normal\nstate=queued\n")
        wav.chmod(0o600)
        state["bad_link"] = True
        assert lib.buffer_agent_process_once() == -errno.EPROTO
        assert wav.exists(), "failed classification discarded pending audio"
        pending = json.loads((data / "records/voice.json").read_text())
        assert pending["state"] == "pending_classification" and pending["transcript"]
        assert len(json.loads((data / "cards.json").read_text())) == 1
        asr_calls = sum(r.get("model") == "mimo-v2.5-asr" for r in state["requests"])
        state["bad_link"] = False
        assert lib.buffer_agent_process_once() == 0
        assert not wav.exists()
        voice = json.loads((data / "records/voice.json").read_text())
        assert voice["segments"][0]["related_card_ids"] == [first_id]
        assert voice["tool_receipts"] == [
            {"tool": "search_records", "success": True, "count": 1},
            {"tool": "read_record", "success": True, "count": 1},
        ]
        assert sum(r.get("model") == "mimo-v2.5-asr" for r in state["requests"]) == asr_calls

        lib.fixture_phone(1)
        assert lib.buffer_agent_submit_text(b"phone", b"Buffer while phone is online", b"normal", 3000) == 0
        before = len(state["requests"])
        assert lib.buffer_agent_process_once() == 0 and len(state["requests"]) == before
        lib.fixture_phone(0)
        assert lib.buffer_agent_process_once() == 0
        assert json.loads((data / "records/phone.json").read_text())["state"] == "ready"
        system_prompts = {hashlib.sha256(r["messages"][0]["content"].encode()).hexdigest()
                          for r in state["requests"] if r.get("tools")}
        assert len(system_prompts) == 1, "text and voice used different organization rules"
        phone = json.loads((data / "records/phone.json").read_text())
        voice_id = voice["segments"][0]["card_id"].encode()
        phone_id = phone["segments"][0]["card_id"].encode()
        assert lib.buffer_agent_apply_action(first_id.encode(), b"archive") == 0
        assert lib.buffer_agent_apply_action(voice_id, b"done") == 0
        assert json.loads((data / "records/voice.json").read_text())["segments"][0]["state"] == "done"
        assert lib.buffer_agent_apply_action(voice_id, b"delete") == 0
        assert lib.buffer_agent_apply_action(voice_id, b"done") == -errno.ESTALE
        assert lib.buffer_agent_apply_action(phone_id, b"later") == 0
        assert json.loads((data / "cards.json").read_text()) == []
        (data / "cards.json").unlink()
        subprocess.run(["python3", "-c",
            "import ctypes; l=ctypes.CDLL(" + repr(str(library)) + "); "
            "assert l.buffer_agent_store_init()==0; assert l.buffer_agent_process_once()==0"],
            check=True)
        assert not (data / "cards.json").exists() or json.loads((data / "cards.json").read_text()) == []
        assert json.loads((data / "records/first.json").read_text())["segments"][0]["state"] == "archived"
        # Pending raw WAV was already ACKed, yet structured facts remain until
        # an application import receipt. Exercise bounded fragments and restart.
        first_path = data / "records/first.processed"
        padded = json.loads(first_path.read_text())
        padded["fixture_padding"] = "字节" * 2000
        first_path.write_text(json.dumps(padded, ensure_ascii=False))
        first_path.chmod(0o600)
        code, initial = outbox()
        assert code == 1 and initial["offset"] == 0
        assert not list((data / "records").glob("*.receipt"))
        assert outbox()[0] == 0, "unacknowledged snapshot bypassed retry backoff"
        lib.buffer_agent_outbox_reset()
        assert outbox()[1] == initial, "new session failed to replay unacknowledged facts"
        lib.buffer_agent_outbox_reset()
        delivered = set()
        while True:
            code, part = outbox()
            if code == 0:
                break
            assert code == 1
            record_hash = part["record_sha256"]
            assembled = bytearray()
            fragments = 0
            while True:
                assert part["offset"] == len(assembled)
                assembled.extend(base64.b64decode(part["record_base64"], validate=True))
                fragments += 1
                if len(assembled) == part["total"]:
                    break
                partial = {"type": "vela.capture.processed.part.reply", "protocol": 1,
                           "request_id": part["request_id"], "record_sha256": record_hash,
                           "next_offset": len(assembled)}
                assert ack({**partial, "record_sha256": "0" * 64}) == -errno.ESTALE
                assert ack(partial) == 0
                code, part = outbox()
                assert code == 1
            assert hashlib.sha256(assembled).hexdigest() == record_hash
            record = json.loads(assembled)
            if record["capture_id"] == "first":
                assert fragments > 1, "padded baseline did not exercise fragmentation"
            assert (data / ("records/" + record["capture_id"] + ".processed")).read_bytes() == assembled
            receipt = {"type": "vela.capture.processed.reply", "protocol": 1,
                       "request_id": part["request_id"], "record_sha256": record_hash,
                       "capture_id": record["capture_id"], "state": "imported"}
            assert ack(receipt) == 0
            delivered.add(record["capture_id"])
            assert (data / ("records/" + record["capture_id"] + ".json")).exists()
        assert delivered == {"first", "voice", "phone"}
        lib.buffer_agent_outbox_reset()
        assert outbox()[0] == 0
        # Native actions update mutable state through the card-state protocol;
        # the imported processed-capture baseline remains byte-for-byte stable.
        baseline = first_path.read_bytes()
        assert lib.buffer_agent_apply_action(first_id.encode(), b"done") == 0
        assert first_path.read_bytes() == baseline
        lib.buffer_agent_outbox_reset()
        assert outbox()[0] == 0
        lib.buffer_agent_card_state_next.argtypes = [ctypes.c_uint64, ctypes.c_size_t,
                                                    ctypes.POINTER(ctypes.c_void_p)]
        # A slow model request holds this lock. The transport must keep polling
        # heartbeats and BLE while the next card-state scan is deferred.
        busy_result = []
        busy_done = threading.Event()
        def card_state_during_model_request():
            pointer = ctypes.c_void_p()
            code = lib.buffer_agent_card_state_next(1000, 12288, ctypes.byref(pointer))
            busy_result.append((code, pointer.value))
            busy_done.set()
        lib.fixture_capture_lock()
        busy_thread = threading.Thread(target=card_state_during_model_request, daemon=True)
        try:
            busy_thread.start()
            assert busy_done.wait(1), "card-state polling blocked behind a model request"
            assert busy_result == [(0, None)]
        finally:
            lib.fixture_capture_unlock()
            busy_thread.join(2)
        changes = {}
        for _ in range(3):
            pointer = ctypes.c_void_p()
            code = lib.buffer_agent_card_state_next(1000, 12288, ctypes.byref(pointer))
            assert code == 1 and pointer.value
            change = json.loads(ctypes.string_at(pointer))
            lib.fixture_free(pointer)
            changes[change["card_id"]] = change
            lib.buffer_agent_card_state_ack.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
            reply = json.dumps({"type": "buffer.card.state.reply", "protocol": 1,
                                **{key: change[key] for key in
                                   ("capture_id", "card_id", "revision", "action_id")},
                                "state": "applied"}).encode()
            assert lib.buffer_agent_card_state_ack(reply, len(reply)) == 0
        assert set(changes) == {first_id, voice_id.decode(), phone_id.decode()}
        pointer = ctypes.c_void_p()
        assert lib.buffer_agent_card_state_next(1000, 12288, ctypes.byref(pointer)) == 0
        assert not pointer.value
        assert changes[first_id]["state"] == "done"
        assert changes[voice_id.decode()]["state"] == "deleted"
        assert changes[phone_id.decode()]["state"] == "later"
        # Replica storage retains cards beyond the five-card projection, and
        # explicit tombstones override old local/generated display entries.
        def replica(record, allowed=1, split=100, channel="buffer.card.replica.part"):
            raw = json.dumps(record, ensure_ascii=False).encode()
            digest = hashlib.sha256(raw).hexdigest()
            answer = None
            for offset in range(0, len(raw), split):
                message = json.dumps({"type": channel, "protocol": 1,
                    "record_sha256": digest, "offset": offset, "total": len(raw),
                    "record_base64": base64.b64encode(raw[offset:offset+split]).decode()}).encode()
                pointer = ctypes.c_void_p()
                code = lib.fixture_replica(message, len(message), allowed, ctypes.byref(pointer))
                answer = json.loads(ctypes.string_at(pointer)) if pointer.value else None
                lib.fixture_free(pointer)
                if code:
                    return code, answer
            return 0, answer

        def phone_card(index, revision=1, deleted=False, summary="Phone replica"):
            return {"card_id": f"phone_{index}", "revision": revision, "deleted": deleted,
                "card": {"card_id": f"phone_{index}", "capture_id": "phone_capture", "kind": "spark_card",
                    "title": f"Replica {index}", "summary": summary, "state": "active",
                    "created_at": 10000+index, "actions": ["archive"]}}
        assert replica(phone_card(0), allowed=0)[0] == -errno.EACCES
        assert not (data / "replicas/phone_0.json").exists()
        for index in range(8):
            assert replica(phone_card(index))[1]["state"] == "stored"
        assert len(list((data / "replicas").glob("*.json"))) == 8
        # Production receive commits the source then defers home projection to
        # its worker. This synchronous fixture must advance that worker step.
        assert lib.buffer_agent_refresh_cards() == 0
        assert len(json.loads((data / "cards.json").read_text())) == 5
        assert replica(phone_card(0))[1]["state"] == "duplicate"
        assert replica(phone_card(0, summary="Different same revision"))[0] == -errno.EEXIST
        assert replica(phone_card(0, revision=2, deleted=True))[1]["state"] == "stored"
        assert replica(phone_card(0, revision=1))[1]["state"] == "stale"
        assert replica(phone_card(0, revision=3))[1]["state"] == "stale"
        assert json.loads((data / "replicas/phone_0.json").read_text())["deleted"] is True
        assert replica(phone_card(1, revision=2, summary="Edited summary"))[1]["state"] == "stored"
        assert json.loads((data / "replicas/phone_1.json").read_text())["card"]["summary"] == "Edited summary"

        # Real bidirectional CRDT fragment transport and C local authoring.
        values = dict(phone_card(99)["card"], kind="task_card", actions=["done", "delete"],
                      schedule={"start": 1792458000000, "end": 1792461600000, "all_day": False, "zone": "Asia/Shanghai"})
        doc = {"version": 1, "id": "phone_99", "kind": "card", "deleted": False,
               "fields": {key: {"counter": 1, "actor": "c"*64, "value": value} for key, value in values.items()}}
        envelope = {"card_id": "phone_99", "crdt": doc}
        assert replica(envelope, allowed=0, channel="buffer.crdt.part")[0] == -errno.EACCES
        assert replica(envelope, channel="buffer.crdt.part")[1]["type"] == "buffer.crdt.reply"
        lib.buffer_replica_crdt_action.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        assert lib.buffer_replica_crdt_action(b"phone_99", b"archive") == -errno.EACCES
        assert lib.buffer_replica_crdt_action(b"phone_99", b"done") == 0
        doc["fields"]["title"] = {"counter": 2, "actor": "c"*64, "value": "Concurrent phone edit"}
        assert replica(envelope, channel="buffer.crdt.part")[0] == 0
        saved = json.loads((data / "replicas/phone_99.json").read_text())
        assert saved["card"]["state"] == "done" and saved["card"]["title"] == "Concurrent phone edit"
        assert saved["card"]["schedule"] == values["schedule"]
        assert "reopen" in saved["card"]["actions"] and "done" not in saved["card"]["actions"]
        assert lib.buffer_replica_crdt_action(b"phone_99", b"reopen") == 0
        reopened = json.loads((data / "replicas/phone_99.json").read_text())
        assert reopened["card"]["state"] == "active" and "done" in reopened["card"]["actions"]
        assert reopened["card"]["schedule"] == values["schedule"] and reopened["card"]["title"] == "Concurrent phone edit"
        assert lib.buffer_replica_crdt_action(b"phone_99", b"reopen") == -errno.ESTALE
        assert lib.buffer_replica_crdt_action(b"phone_99", b"done") == 0
        saved = json.loads((data / "replicas/phone_99.json").read_text())
        lib.buffer_crdt_sync_next.argtypes = [ctypes.c_char_p, ctypes.c_uint, ctypes.c_uint64, ctypes.c_size_t, ctypes.POINTER(ctypes.c_void_p)]
        lib.buffer_crdt_sync_ack.argtypes = [ctypes.c_char_p, ctypes.c_uint, ctypes.c_void_p]
        lib.fixture_crdt_ack.argtypes = [ctypes.c_char_p]
        assembled = bytearray()
        for tick in range(100):
            ptr = ctypes.c_void_p()
            assert lib.buffer_crdt_sync_next(b"b" * 64, 1, 100000 + tick, 12288, ctypes.byref(ptr)) == 1
            part = json.loads(ctypes.string_at(ptr)); lib.fixture_free(ptr)
            assert part["type"] == "buffer.crdt.part" and part["offset"] == len(assembled)
            assembled.extend(base64.b64decode(part["record_base64"]))
            complete = len(assembled) == part["total"]
            assert lib.fixture_crdt_ack(json.dumps({"type": "buffer.crdt.reply", "protocol": 1, "record_sha256": part["record_sha256"],
                "next_offset": len(assembled), "state": "merged" if complete else "partial"}).encode()) == 0
            if complete: break
        else: raise AssertionError("outbound CRDT document did not finish")
        assert hashlib.sha256(assembled).hexdigest() == part["record_sha256"]
        assert json.loads(assembled)["crdt"] == saved["crdt"]
        doc["fields"]["state"] = {"counter": 100, "actor": "c"*64, "value": "archived"}
        assert replica(envelope, channel="buffer.crdt.part")[0] == 0
        assert lib.buffer_replica_crdt_action(b"phone_99", b"reopen") == -errno.ESTALE
        assert lib.buffer_replica_crdt_action(b"phone_99", b"delete") == 0
        assert replica(envelope, channel="buffer.crdt.part")[0] == 0
        assert json.loads((data / "replicas/phone_99.json").read_text())["deleted"] is True
        assert lib.buffer_replica_crdt_action(b"phone_99", b"reopen") == -errno.ENOENT

        # Auto intent uses the same production HTTPS/model/tool path, while a
        # successful assistant decision is durably promoted before execution.
        auto_answer_start = len(state["requests"])
        cards_before_auto_answer = (data / "cards.json").read_bytes()
        state["assistant_case"] = ("show_answer", {"text": "A timer measures elapsed time."})
        assert lib.buffer_agent_submit_auto_text(b"auto_answer",
            b"How does a timer work?", b"normal", 49000) == 0
        assert lib.buffer_agent_process_once() == 0
        auto_answer = json.loads((data / "records/auto_answer.json").read_text())
        assert auto_answer["state"] == "assistant_complete" and auto_answer["target"] == "assistant"
        assert "segments" not in auto_answer and auto_answer["tool_receipts"][-1]["tool"] == "show_answer"
        assert (data / "cards.json").read_bytes() == cards_before_auto_answer
        auto_requests = state["requests"][auto_answer_start:]
        assert any(request.get("tool_choice") == "auto" and
                   json.loads(next(m["content"] for m in request["messages"] if m["role"] == "user"))[
                       "target"] == "auto" for request in auto_requests if request.get("tools"))
        state.pop("assistant_case")

        timer_before_auto_note = lib.fixture_timer_count()
        assert lib.buffer_agent_submit_auto_text(b"auto_note",
            b"Tomorrow buy milk", b"normal", 49500) == 0
        assert lib.buffer_agent_process_once() == 0
        auto_note = json.loads((data / "records/auto_note.json").read_text())
        assert auto_note["state"] == "ready" and auto_note["target"] == "buffer"
        assert "segments" in auto_note and lib.fixture_timer_count() == timer_before_auto_note

        # The device may win auto intent locally, complete an assistant tool,
        # and ACK the WAV before the phone retries as coordinator. Persist the
        # original-source and chosen-text hashes before the side effect.
        local_wav=data/"queue/local_auto_audio.wav"
        with wave.open(str(local_wav),"wb") as audio:
            audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
            audio.writeframes(b"\x20\x01"*1800)
        local_audio_bytes=local_wav.read_bytes(); local_wav.chmod(0o600)
        local_audio_hash=hashlib.sha256(local_audio_bytes).hexdigest()
        (data/"queue/local_auto_audio.meta").write_text(
            "created_at=49600\nduration_ms=112\nmode=normal\nstate=queued\ntarget=auto\n")
        state["assistant_case"]=("show_answer",{"text":"The recording asks a question."})
        assert lib.buffer_agent_process_once()==0
        local_auto_record=json.loads((data/"records/local_auto_audio.json").read_text())
        assert local_auto_record["state"]=="assistant_complete"
        assert local_auto_record["target"]=="assistant"
        assert local_auto_record["source_sha256"]==local_audio_hash
        assert local_auto_record["intent_source_sha256"]==local_audio_hash
        assert local_auto_record["intent_text_sha256"]==hashlib.sha256(
            local_auto_record["transcript"].encode()).hexdigest()
        assert not local_wav.exists(), "assistant completion did not ACK source audio"
        state.pop("assistant_case")

        # A real generated Button dispatches through the production local Agent
        # adapter. JSON path values retain numeric/bool/string types; accepted
        # clicks stay busy until the actual assistant record is committed.
        assert lib.fixture_ui_action_register()==0
        lib.buffer_a2ui_apply_message.argtypes=[ctypes.c_char_p,ctypes.c_size_t,ctypes.c_uint64,ctypes.c_bool]
        lib.buffer_a2ui_model_emit_action.argtypes=[ctypes.c_char_p]*4
        def apply_ui(message):
            raw=json.dumps({"version":"v0.9.1",**message},separators=(",",":")).encode()
            assert lib.buffer_a2ui_apply_message(raw,len(raw),1,True)==0
        apply_ui({"createSurface":{"surfaceId":"ui-action-surface","catalogId":"https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json"}})
        context={"duration":{"path":"/duration"},"enabled":{"path":"/enabled"},"topic":{"path":"/topic"},"static_id":"literal-item"}
        apply_ui({"updateComponents":{"surfaceId":"ui-action-surface","components":[
            {"id":"root","component":"Column","children":["choose"]},
            {"id":"choose","component":"Button","child":"label","action":{"event":{"name":"explain_choice","context":context}}},
            {"id":"label","component":"Text","text":"Explain my selection"}]}})
        apply_ui({"updateDataModel":{"surfaceId":"ui-action-surface","path":"/","value":{"duration":60,"enabled":True,"topic":"Study"}}})
        args=(b"ui-action-surface",b"choose",b"explain_choice",json.dumps(context,separators=(",",":")).encode())
        assert lib.buffer_a2ui_model_emit_action(*args)==0
        assert lib.buffer_a2ui_model_emit_action(*args)==-errno.EALREADY
        action_id=ctypes.create_string_buffer(64);pending=ctypes.c_int();action_result=ctypes.c_int()
        for _ in range(1000):
            ret=lib.fixture_ui_action_status(action_id,ctypes.byref(pending),ctypes.byref(action_result))
            if ret==0 and pending.value==2 and (data/"records"/(action_id.value.decode()+".json")).exists():break
            time.sleep(0.002)
        assert ret==0 and pending.value and not action_result.value
        record_path=data/"records"/(action_id.value.decode()+".json")
        queued=json.loads(record_path.read_text());event=json.loads(queued["transcript"])
        assert queued["target"]=="assistant" and queued["state"]=="pending_classification"
        assert event["event"]["action"]["context"]=={"duration":60,"enabled":True,"topic":"Study","static_id":"literal-item"}
        assert event["source"]["button_label"]=="Explain my selection"
        stable_cards=(data/"cards.json").read_bytes()
        original_action_id=action_id.value
        lib.fixture_network(0,-errno.ENETUNREACH)
        assert lib.buffer_agent_process_once()==-errno.ENETUNREACH
        assert lib.fixture_ui_action_status(action_id,ctypes.byref(pending),ctypes.byref(action_result))==0
        assert not pending.value and action_result.value==-errno.ENETUNREACH
        assert lib.buffer_a2ui_model_emit_action(*args)==0
        for _ in range(1000):
            ret=lib.fixture_ui_action_status(action_id,ctypes.byref(pending),ctypes.byref(action_result))
            if ret==0 and pending.value==2:break
            time.sleep(0.001)
        assert pending.value==2 and action_id.value==original_action_id
        assert json.loads(record_path.read_text())==queued, "retry rewrote the accepted input or minted another request"
        lib.fixture_network(1,0)
        state["assistant_case"]=("show_answer",{"text":"The selected topic is Study."})
        assert lib.buffer_agent_process_once()==0
        state.pop("assistant_case")
        assert json.loads(record_path.read_text())["state"]=="assistant_complete"
        assert (data/"cards.json").read_bytes()==stable_cards
        for _ in range(1000):
            ret=lib.fixture_ui_action_status(action_id,ctypes.byref(pending),ctypes.byref(action_result))
            if ret==0 and not pending.value:break
            time.sleep(0.001)
        assert ret==0 and not pending.value and not action_result.value
        print("PASS generated Button -> resolved official event -> durable local Agent -> actual assistant completion",flush=True)

        before_cards = (data / "cards.json").read_bytes()
        cases = [
            ("assistant_timer", "device_timer", {"action": "create", "label": "Test timer", "duration_ms": 60000}),
            ("assistant_translate", "show_translation", {"original": "Hello", "translated": "你好", "source_language": "en", "target_language": "zh"}),
            ("assistant_recipe", "show_recipe", {"title": "Test recipe", "ingredients": "Water",
                "steps": [{"title": "Wait", "detail": "Start the timer only when tapped", "timer_ms": 60000}]}),
            ("assistant_answer", "show_answer", {"text": "A timer counts elapsed time."}),
            ("assistant_question", "ask_user", {"text": "What time should the alarm ring?"}),
        ]
        for capture, name, arguments in cases:
            state["assistant_case"] = name, arguments
            state["nested_assistant_response"] = name == "show_translation"
            requests_before = len(state["requests"])
            assert lib.buffer_agent_submit_assistant_text(capture.encode(),
                b"Explicit synthetic device request", 50000) == 0
            process_result = lib.buffer_agent_process_once()
            record = json.loads((data / f"records/{capture}.json").read_text())
            assert process_result == 0 and record["state"] == "assistant_complete" and "segments" not in record, {
                "record": record, "process_result": process_result,
                "requests_added": len(state["requests"]) - requests_before,
                "agent_flags": lib.fixture_agent_flags(),
                "setup_complete": lib.fixture_setup_complete(),
                "coordinator_local": lib.fixture_coord_local(),
                "capture_lock_free": lib.fixture_capture_lock_free(),
            }
            assert record["assistant"]["assistant_response"] == "Local tool returned its actual result."
            assert record["tool_receipts"][-1]["tool"] == name
            assert record["tool_receipts"][-1]["success"] is True
            assert (data / "cards.json").read_bytes() == before_cards, "assistant polluted Buffer collection"
        assert lib.fixture_timer_count() == 1, "recipe generation auto-started a second timer"
        state.pop("assistant_case")
        state.pop("nested_assistant_response")

        # Search -> real result handle -> recipe -> JPEG cache, with no
        # auto-started timer and no image URL invented by the model.
        state["assistant_case"] = ("image_search", {"query": "海带红烧肉"})
        state["assistant_image_pipeline"] = True
        lib.fixture_active_image.restype = ctypes.c_char_p
        assert lib.buffer_agent_submit_assistant_text(b"assistant_recipe_image", b"Explicit recipe with searched photo", 50001) == 0
        assert lib.buffer_agent_process_once() == 0
        image_record = json.loads((data / "records/assistant_recipe_image.json").read_text())
        assert image_record["state"] == "assistant_complete"
        assert [item["tool"] for item in image_record["tool_receipts"]] == ["image_search", "show_recipe"]
        assert all(item["success"] for item in image_record["tool_receipts"])
        image_id = lib.fixture_active_image().decode()
        for _ in range(1000):
            if (data / f"images/{image_id}.jpg").is_file(): break
            time.sleep(0.001)
        assert len(image_id) == 64 and (data / f"images/{image_id}.jpg").is_file(), {"image_id":image_id,"downloads":lib.fixture_image_downloads(),"files":[p.name for p in (data/"images").glob("*")]}
        assert lib.fixture_image_downloads() == 1 and lib.fixture_timer_count() == 1
        state.pop("assistant_case");state.pop("assistant_image_pipeline")

        # A durable tool receipt survives a malformed model continuation. The
        # retry must replay the saved receipt without executing the timer twice.
        state["assistant_case"] = "device_timer", {"action":"create","label":"Local index retry","duration_ms":60000}
        lib.fixture_assistant_busy(1)
        before_requests=len(state["requests"]);before_timers=lib.fixture_timer_count()
        assert lib.buffer_agent_submit_assistant_text(b"assistant_index_retry",b"Create a timer",int(time.time()*1000))==0
        assert lib.buffer_agent_process_once()==-errno.EAGAIN
        retry_checkpoint=data/"records/assistant_index_retry.json.tools.json"
        pending_tool=json.loads(retry_checkpoint.read_text())["tools"]
        assert len(pending_tool)==1 and pending_tool[0]["output"]["retryable"] is True
        assert len(state["requests"])==before_requests+1
        assert lib.fixture_timer_count()==before_timers
        assert lib.buffer_agent_process_once()==0
        assert lib.fixture_timer_count()==before_timers+1
        assert len(state["requests"])==before_requests+2,"local index wait regenerated intent instead of resuming its tool"
        assert not retry_checkpoint.exists()
        state.pop("assistant_case")

        # A microphone tool waiting on storage resumes before unrelated record
        # enumeration. Neither intent generation nor ASR runs a second time.
        state["assistant_case"]="device_timer",{"action":"create","label":"Microphone index retry","duration_ms":60000}
        lib.fixture_pcm_time.argtypes=[ctypes.c_int64]
        lib.fixture_pcm_time(int(time.time()*1000));lib.fixture_assistant_busy(1)
        before_requests=len(state["requests"]);before_timers=lib.fixture_timer_count()
        assert lib.fixture_submit_pcm(b"microphone_index_retry",0)==0
        assert lib.buffer_agent_process_once()==-errno.EAGAIN
        assert len(state["requests"])==before_requests+2 # ASR + intent
        unrelated=data/"records/zz_unrelated_bad.json"
        try:
            unrelated.write_text('{');unrelated.chmod(0o600)
            assert lib.buffer_agent_process_once()==0
        finally:
            unrelated.unlink();lib.fixture_pcm_time(60000)
        assert lib.fixture_timer_count()==before_timers+1
        assert len(state["requests"])==before_requests+3 # final confirmation only
        state.pop("assistant_case")

        retry_args = {"action": "create", "label": "Retry checkpoint", "duration_ms": 60000}
        timer_before = lib.fixture_timer_count()
        state["assistant_case"] = "device_timer", retry_args
        state["assistant_malformed_followup_once"] = True
        assert lib.buffer_agent_submit_assistant_text(b"assistant_retry_tool",
            b"Create a test timer, then confirm the result", 51000) == 0
        assert lib.buffer_agent_process_once() == -errno.EPROTO
        checkpoint = data / "records/assistant_retry_tool.json.tools.json"
        saved_checkpoint = json.loads(checkpoint.read_text())
        assert len(saved_checkpoint["tools"]) == 1
        assert saved_checkpoint["tools"][0]["output"]["success"] is True
        assert lib.fixture_timer_count() == timer_before + 1
        assert lib.buffer_agent_process_once() == 0
        retry_record = json.loads((data / "records/assistant_retry_tool.json").read_text())
        assert retry_record["state"] == "assistant_complete"
        assert retry_record["tool_receipts"][-1]["tool"] == "device_timer"
        assert retry_record["tool_receipts"][-1]["success"] is True
        assert lib.fixture_timer_count() == timer_before + 1
        assert not checkpoint.exists()
        state.pop("assistant_case")

        # A real read timeout after the side effect follows the same resume
        # path as malformed JSON and must not execute the timer again.
        timeout_args = {"action": "create", "label": "Timeout checkpoint", "duration_ms": 60000}
        timer_before = lib.fixture_timer_count()
        state["assistant_case"] = "device_timer", timeout_args
        state["assistant_timeout_followup_once"] = True
        assert lib.buffer_agent_submit_assistant_text(b"assistant_timeout_tool",
            b"Create a timer and confirm after timeout", 51500) == 0
        assert lib.buffer_agent_process_once() == -errno.ETIMEDOUT
        timeout_checkpoint = data / "records/assistant_timeout_tool.json.tools.json"
        assert json.loads(timeout_checkpoint.read_text())["tools"][0]["output"]["success"] is True
        assert lib.fixture_timer_count() == timer_before + 1
        assert lib.buffer_agent_process_once() == 0
        timeout_record = json.loads((data / "records/assistant_timeout_tool.json").read_text())
        assert timeout_record["state"] == "assistant_complete"
        assert timeout_record["tool_receipts"][-1]["success"] is True
        assert lib.fixture_timer_count() == timer_before + 1
        assert not timeout_checkpoint.exists()
        state.pop("assistant_case")

        # Real legacy checkpoint: a temporary local EAGAIN must retry the
        # exact slot, then continue with one model request instead of replaying
        # an unsuccessful result into EPROTO forever.
        import time
        def failed_timer_checkpoint(capture,created):
            arguments={"action":"create","label":"Resumed temporary failure","duration_ms":30000}
            assert lib.buffer_agent_submit_assistant_text(capture.encode(),b"Start a timer",created)==0
            saved={"version":1,"capture_id":capture,"tools":[{"slot":0,"call":{
                "id":"interrupted0","type":"function","function":{"name":"device_timer","arguments":json.dumps(arguments)}},
                "output":{"success":False,"error":"clock_or_service_not_ready"},
                "receipt":{"tool":"device_timer","success":False,"object_id":""}}]}
            checkpoint=data/f"records/{capture}.json.tools.json"
            checkpoint.write_text(json.dumps(saved));checkpoint.chmod(0o600)
            return arguments,checkpoint
        arguments,temporary_checkpoint=failed_timer_checkpoint("assistant_transient",int(time.time()*1000))
        duplicate_pending=json.loads(temporary_checkpoint.read_text())
        competing=json.loads(json.dumps(duplicate_pending["tools"][0]));competing["slot"]=1
        competing["call"]["id"]="interrupted1"
        duplicate_pending["tools"].append(competing)
        temporary_checkpoint.write_text(json.dumps(duplicate_pending))
        state["assistant_case"]="device_timer",arguments
        requests_before=len(state["requests"]);timer_before=lib.fixture_timer_count()
        assert lib.buffer_agent_process_once()==0
        resumed=json.loads((data/"records/assistant_transient.json").read_text())
        assert resumed["state"]=="assistant_complete" and [r["success"] for r in resumed["tool_receipts"]]==[True,False]
        assert lib.fixture_timer_count()==timer_before+1 and len(state["requests"])==requests_before+1
        assert not temporary_checkpoint.exists()
        assert lib.buffer_agent_process_once()==0 and lib.fixture_timer_count()==timer_before+1
        for expired_capture,expired_at in [("assistant_expired",int(time.time()*1000)-3600000),("assistant_unknown_clock",50000)]:
            _,expired_checkpoint=failed_timer_checkpoint(expired_capture,expired_at)
            requests_before=len(state["requests"]);timer_before=lib.fixture_timer_count()
            assert lib.buffer_agent_process_once()==0
            expired=json.loads((data/f"records/{expired_capture}.json").read_text())
            assert expired["state"]=="assistant_complete" and expired["assistant"]["needs_clarification"] is True
            assert expired["tool_receipts"][0]["success"] is False
            assert lib.fixture_timer_count()==timer_before and len(state["requests"])==requests_before
            assert not expired_checkpoint.exists()
        state.pop("assistant_case")

        # A single assistant response may present only one competing surface.
        batch_args = {"action": "create", "label": "One visible surface", "duration_ms": 60000}
        translation_args = {"original": "hello", "translated": "你好",
                            "source_language": "en", "target_language": "zh"}
        state["assistant_case"] = "device_timer", batch_args
        state["assistant_tool_batch"] = [
            ("device_timer", batch_args), ("show_translation", translation_args),
        ]
        request_start = len(state["requests"])
        timer_before = lib.fixture_timer_count()
        assert lib.buffer_agent_submit_assistant_text(b"assistant_one_surface",
            b"Create one timer and translate hello", 52000) == 0
        assert lib.buffer_agent_process_once() == 0
        batch_record = json.loads((data / "records/assistant_one_surface.json").read_text())
        assert batch_record["state"] == "assistant_complete"
        assert [item["success"] for item in batch_record["tool_receipts"]] == [True, False]
        returned_errors = []
        for request in state["requests"][request_start:]:
            for item in request.get("messages", []):
                if item.get("role") == "tool":
                    try:
                        returned_errors.append(json.loads(item["content"]).get("error"))
                    except (KeyError, TypeError, json.JSONDecodeError):
                        pass
        assert "surface_already_presented" in returned_errors
        assert lib.fixture_timer_count() == timer_before + 1
        assert lib.fixture_active_has_type(7) == 1  # BUFFER_A2UI_TIMER_CARD
        assert lib.fixture_active_has_type(9) == 0  # BUFFER_A2UI_TRANSLATION_RESULT
        state.pop("assistant_case")
        state.pop("assistant_tool_batch")

        # The model can violate a Buffer target's offered tools; dispatch checks
        # the target again before invoking any assistant-side effect.
        timer_before = lib.fixture_timer_count()
        state["force_unoffered_tool"] = ("device_timer", retry_args)
        request_start = len(state["requests"])
        assert lib.buffer_agent_submit_text(b"buffer_forbidden_tool",
            b"Buffer task with an invalid device tool", b"normal", 53000) == 0
        assert lib.buffer_agent_process_once() == 0
        assert lib.fixture_timer_count() == timer_before
        assert json.loads((data / "records/buffer_forbidden_tool.json").read_text())["state"] == "ready"
        assert lib.fixture_timer_count() == timer_before
        denied = [json.loads(item["content"]).get("error")
                  for request in state["requests"][request_start:]
                  for item in request.get("messages", []) if item.get("role") == "tool"]
        assert "tool_not_allowed_for_target" in denied

        expected_timers = lib.fixture_timer_count()
        before = len(state["requests"])
        assert lib.buffer_agent_process_once() == 0
        assert lib.fixture_timer_count() == expected_timers and len(state["requests"]) == before
        # Actual native Buffer ownership and raw-input sender. A phone import
        # receipt must retain source audio until processed replicas commit.
        import time
        clock = int(time.monotonic()*1000)
        assert lib.buffer_coordinator_bind(b"a"*64,b"b"*64) == 0
        assert lib.buffer_coordinator_local() == 1
        lib.buffer_coordinator_connected(1)
        pointer=ctypes.c_void_p()
        assert lib.buffer_coordinator_heartbeat(clock,ctypes.byref(pointer)) == 1
        heartbeat=json.loads(ctypes.string_at(pointer)); lib.fixture_free(pointer)
        heartbeat.update(device_id="b"*64,device_class="PHONE",incarnation_id="phone-test",heartbeat_sequence=1,
                         coordinator_term=heartbeat["coordinator_term"]+1,coordinator_device_id="b"*64)
        assert lib.fixture_heartbeat(json.dumps(heartbeat).encode()) == 0
        assert lib.buffer_coordinator_local() == 1
        lib.fixture_coordinator_advance(15000)
        heartbeat["heartbeat_sequence"] += 1
        assert lib.fixture_heartbeat(json.dumps(heartbeat).encode()) == 0
        assert lib.buffer_coordinator_local() == 0
        pointer=ctypes.c_void_p()
        assert lib.buffer_coordinator_heartbeat(clock,ctypes.byref(pointer)) == 1
        lib.fixture_free(pointer)
        assert lib.fixture_cli_text(0,b"delegated_text",b"Buffer remote task") == 0

        def input_part():
            pointer=ctypes.c_void_p()
            code=lib.fixture_input_next(clock,ctypes.byref(pointer))
            part=json.loads(ctypes.string_at(pointer)) if pointer.value else None
            lib.fixture_free(pointer)
            return code,part
        assert input_part()[0] == 1
        clock+=5000
        assert input_part()[0] == 0, "application retry flooded a draining BLE record"
        clock+=25000
        code,part=input_part()
        assert code == 1 and part["capture_id"] == "delegated_text"
        assert part["target"] == "buffer", "legacy CLI text must remain Buffer-only"
        assert base64.b64decode(part["record_base64"]) == b"Buffer remote task"
        assert part["record_sha256"] == hashlib.sha256(b"Buffer remote task").hexdigest()
        def import_ack(part):
            reply={"type":"vela.capture.input.reply","protocol":1,"capture_id":part["capture_id"],
                   "record_sha256":part["record_sha256"],"state":"imported"}
            assert lib.fixture_input_ack(json.dumps(reply).encode()) == 0
        import_ack(part)
        assert json.loads((data/"records/delegated_text.json").read_text())["state"] == "pending_classification"

        saves_before_ram=lib.fixture_audio_save_count()
        assert lib.fixture_submit_pcm(b"delegated_auto_text",0)==0
        assert not (data/"queue/delegated_auto_text.wav").exists()
        assert lib.fixture_audio_save_count()==saves_before_ram
        assert input_part()[0]==0, "configured S1 exported unresolved local intent to phone"
        timer_before_local=lib.fixture_timer_count()
        state["assistant_case"]=("device_timer",{"action":"create","label":"Local timer with phone owner",
                                                   "duration_ms":45000})
        assert lib.buffer_coordinator_local()==0
        assert lib.buffer_agent_process_once()==0
        local_routed=json.loads((data/"records/delegated_auto_text.json").read_text())
        assert local_routed["state"]=="assistant_complete" and local_routed["target"]=="assistant"
        assert lib.fixture_timer_count()==timer_before_local+1
        assert lib.fixture_audio_save_count()==saves_before_ram, "timer archived raw speech"
        requests_after_ram=len(state["requests"])
        assert lib.fixture_submit_pcm(b"delegated_auto_text",0)==0
        assert lib.buffer_agent_process_once()==0
        assert lib.fixture_timer_count()==timer_before_local+1
        assert len(state["requests"])==requests_after_ram, "completed RAM command retranscribed or replayed"
        assert lib.fixture_audio_save_count()==saves_before_ram
        state.pop("assistant_case")
        auto_part={"record_sha256":local_routed["intent_source_sha256"]}

        def handoff_request(capture_id,digest,transcript,term=None):
            transcript_bytes=transcript.encode()
            request_id=hashlib.sha256(capture_id.encode()+b"\0"+digest.encode()+b"\0"+
                                      transcript_bytes).digest()[:16].hex()
            return {"type":"vela.capture.assistant.request","protocol":1,
                "request_id":request_id,"capture_id":capture_id,"record_sha256":digest,
                "transcript":transcript,"coordinator_term":int(
                    lib.buffer_coordinator_fence() if term is None else term)}
        def handoff(request,allowed=1):
            raw=json.dumps(request,ensure_ascii=False).encode()
            pointer=ctypes.c_void_p()
            result=lib.fixture_handoff(raw,len(raw),allowed,ctypes.byref(pointer))
            reply=json.loads(ctypes.string_at(pointer)) if pointer.value else None
            lib.fixture_free(pointer)
            return result,reply

        local_phone_retry=handoff_request("local_auto_audio",local_audio_hash,
                                          "Phone ASR wording differs from Vela")
        local_retry_request_count=len(state["requests"])
        local_retry_code,local_retry=handoff(local_phone_retry)
        assert local_retry_code==0 and local_retry["state"]=="duplicate"
        assert local_retry["coordinator_term"]==local_phone_retry["coordinator_term"]
        assert len(state["requests"])==local_retry_request_count
        local_after_retry=json.loads((data/"records/local_auto_audio.json").read_text())
        assert local_after_retry["transcript"]==local_auto_record["transcript"]

        auto_request=handoff_request("delegated_auto_text",auto_part["record_sha256"],
                                     local_routed["transcript"])
        denial_code,denial=handoff(auto_request,allowed=0)
        assert denial_code==0 and denial["state"]=="failed" and denial["error_code"]=="unauthorized"
        requests_after_local=len(state["requests"])
        accepted_code,accepted=handoff(auto_request)
        assert accepted_code==0 and accepted["state"]=="duplicate"
        assert accepted["coordinator_term"]==auto_request["coordinator_term"]
        accepted_record=json.loads((data/"records/delegated_auto_text.json").read_text())
        assert accepted_record["intent_source_sha256"]==auto_part["record_sha256"]
        assert accepted_record["transcript"]==local_routed["transcript"]
        assert lib.fixture_timer_count()==timer_before_local+1
        assert len(state["requests"])==requests_after_local
        assert input_part()[0]==0, "assistant-owned capture was re-exported to Buffer"
        # Backoff starts after the empty storage scan finishes. Advance past
        # that window rather than landing exactly on scan-start + 1000 ms.
        clock+=2000

        source=data/"queue/delegated_audio.wav"
        with wave.open(str(source),"wb") as audio:
            audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
            audio.writeframes(b"\x10\x01"*4000)
        original=source.read_bytes(); source.chmod(0o600)
        (data/"queue/delegated_audio.meta").write_text("created_at=60000\nduration_ms=250\nmode=normal\nstate=queued\n")
        assembled=bytearray()
        resumed_once=False
        while True:
            code,part=input_part(); assert code == 1 and part["capture_id"] == "delegated_audio"
            assert part["target"]=="buffer", "missing legacy target must stay Buffer-only"
            assert part["offset"] == len(assembled)
            assembled.extend(base64.b64decode(part["record_base64"]))
            if len(assembled) == part["total"]: break
            reply={"type":"vela.capture.input.part.reply","protocol":1,"capture_id":part["capture_id"],
                   "record_sha256":part["record_sha256"],"next_offset":len(assembled)}
            if len(assembled)==6144 and not resumed_once:
                # Sender loses ownership while the receiver retains its prefix.
                # Replaying offset zero must accept the cumulative 6144 ACK.
                lib.buffer_coordinator_connected(0)
                lib.fixture_coordinator_advance(12001)
                assert input_part()[0] == 0
                recovery_term=lib.buffer_coordinator_fence()+1
                lib.buffer_coordinator_connected(1)
                heartbeat.update(heartbeat_sequence=heartbeat["heartbeat_sequence"]+1,coordinator_term=recovery_term,
                                 coordinator_device_id="b"*64)
                assert lib.fixture_heartbeat(json.dumps(heartbeat).encode()) == 0
                lib.fixture_coordinator_advance(15000)
                heartbeat["heartbeat_sequence"] += 1
                assert lib.fixture_heartbeat(json.dumps(heartbeat).encode()) == 0
                pointer=ctypes.c_void_p()
                assert lib.buffer_coordinator_heartbeat(clock,ctypes.byref(pointer)) == 1
                lib.fixture_free(pointer)
                code,replayed=input_part()
                if code == 1 and replayed["capture_id"] == "delegated_text":
                    # A new authority term must confirm unfinished inputs again.
                    import_ack(replayed)
                    code,replayed=input_part()
                assert code==1 and replayed["offset"]==0
                assert replayed["record_sha256"]==part["record_sha256"]
                invalid=dict(reply,next_offset=6145)
                assert lib.fixture_input_ack(json.dumps(invalid).encode()) == -errno.ESTALE
                resumed_once=True
            assert lib.fixture_input_ack(json.dumps(reply).encode()) == 0
        assert assembled == original
        import_ack(part)
        assert source.exists(), "phone import receipt removed source before processing"
        resolved=phone_card(9)
        resolved["card"]["capture_id"]="delegated_audio"; resolved["processed"]=True
        assert replica(resolved)[0] == 0
        assert source.exists(), "an unrelated card ID must not settle retained source"
        result_id = "vla_" + hashlib.sha256(b"delegated_audio").hexdigest()[:32] + "_0"
        resolved["card_id"] = resolved["card"]["card_id"] = result_id
        assert replica(resolved)[0] == 0
        assert not source.exists(), "processed replica did not settle retained source"
        lib.buffer_coordinator_connected(0)
        lib.fixture_coordinator_advance(12001)
        assert lib.buffer_coordinator_local() == 1
        assert lib.buffer_agent_process_once() == 0
        assert json.loads((data/"records/delegated_text.json").read_text())["state"] == "ready"
        # Collections persist raw audio only after local recognition/intent.
        saves_before_note=lib.fixture_audio_save_count()
        assert lib.fixture_submit_pcm(b"memory_buffer_note",0)==0
        assert lib.fixture_audio_save_count()==saves_before_note
        assert lib.buffer_agent_process_once()==0
        assert json.loads((data/"records/memory_buffer_note.json").read_text())["state"]=="ready"
        assert lib.fixture_audio_save_count()==saves_before_note+1
        # If recognition and its recovery save both fail, retain the bounded
        # RAM input; backpressure rejects a second job without losing the first.
        lib.fixture_set_audio_save_error(-errno.ENOSPC)
        assert lib.fixture_submit_pcm(b"memory_silent",1)==0
        assert lib.buffer_agent_process_once()==-errno.ENODATA
        assert lib.fixture_submit_pcm(b"memory_other",0)==-errno.EBUSY
        lib.fixture_set_audio_save_error(0)
        assert lib.buffer_agent_process_once()==-errno.ENODATA
        # Actual focus tool/store/HTTPS path: continuation failure cannot
        # duplicate the session; final records carry immutable history.
        state["assistant_case"] = "focus_session", {"action": "start", "title": "Write homework"}
        state["assistant_malformed_followup_once"] = True
        assert lib.buffer_agent_submit_assistant_text(b"focus_start", b"Start homework now", 70000) == 0
        assert lib.buffer_agent_process_once() == -errno.EPROTO
        first_focus = json.loads((data / "focus.json").read_text())["session"]
        assert first_focus["status"] == "active"
        assert lib.buffer_agent_process_once() == 0
        start_record = json.loads((data / "records/focus_start.json").read_text())
        assert start_record["focus_session"] == first_focus
        assert len(json.loads((data / "focus.json").read_text())["operations"]) == 1
        state["assistant_case"] = "focus_session", {"action": "finish", "session_id": first_focus["id"],
            "progress": "partial", "experience": "tired", "remaining": "Finish the last two questions"}
        assert lib.buffer_agent_submit_assistant_text(b"focus_finish", b"Stop, two questions remain", 71000) == 0
        assert lib.buffer_agent_process_once() == 0
        end_record = json.loads((data / "records/focus_finish.json").read_text())
        assert end_record["focus_session"]["status"] == "reviewed"
        assert end_record["focus_session"]["remaining"] == "Finish the last two questions"
        assert end_record["focus_session"]["experience"] == "tired"
        assert start_record["focus_session"]["status"] == "active", "history was mutated by finish"
        state.pop("assistant_case")
        assert lib.buffer_store_save_oobe_completed(False) == 0
        skip = {"type": "vela.oobe.request", "protocol": 1,
                "request_id": "c" * 32, "revision": 999,
                "operation": "agent.skip", "payload": {}}
        skip_code, skip_reply = dispatch(skip)
        assert skip_code == 0 and skip_reply["state"] == "skipped"
        assert skip_reply["capabilities"]["setup_complete"] is False
        assert lib.fixture_agent_flags() == 2, "agent.skip incorrectly advertises ready"
        print("PASS: real SDK mbedTLS CA/hostname checks, chunked/truncated HTTP, authorized OOBE persistence,"
              " LLM+ASR validation, shared text/voice tools, grounded links, retry without retranscription,"
              " phone-priority policy, process-restart archive/delete/later recovery and durable fragmented outbox receipts")
    finally:
        if "lib" in locals():
            lib.buffer_agent_stop()
        for service in servers:
            service.shutdown()
            service.server_close()
