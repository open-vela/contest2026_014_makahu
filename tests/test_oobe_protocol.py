"""Exercise the Vela C decoder used at the authenticated app-message boundary."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC
import ctypes as C
import errno
import json
from pathlib import Path
import subprocess
import tempfile

root = PROJECT_ROOT
cjson = SDK_ROOT / "apps/netutils/cjson/cJSON"


class Request(C.Structure):
    _fields_ = [
        ("operation", C.c_int),
        ("revision", C.c_uint64),
        ("request_id", C.c_char * 33),
        ("ssid", C.c_char * 33),
        ("wifi_password", C.c_char * 65),
        ("base_url", C.c_char * 257),
        ("completion_model", C.c_char * 201),
        ("transcription_model", C.c_char * 201),
        ("api_key", C.c_char * 8193),
        ("time_zone", C.c_char * 81),
        ("language", C.c_char * 41),
    ]


class Presentation(C.Structure):
    _fields_ = [
        ("page", C.c_int),
        ("pairing_code_visible", C.c_bool),
        ("wifi_ready", C.c_bool),
        ("retry_visible", C.c_bool),
    ]


with tempfile.TemporaryDirectory() as tmp:
    temp = Path(tmp)
    (temp / "netutils").mkdir()
    (temp / "nuttx").mkdir()
    (temp / "nuttx/config.h").write_text("/* host OOBE test config */\n")
    (temp / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
    library_path = temp / "oobe.so"
    stubs = temp / "oobe_stubs.c"
    stubs.write_text(r'''
#include "buffer_agent.h"
#include "buffer_fabric.h"
#include <stdio.h>
#include <string.h>
static int pair_result;
static int wifi_ready = 1;
static int agent_ready = 1;
static int agent_skipped;
static int save_result;
static int saved;
static int save_calls;
static int wake_calls;
static int cache_calls;
static int retry_calls;
void test_setup(int pair, int wifi, int ready, int skipped, int save)
{ pair_result=pair; wifi_ready=wifi; agent_ready=ready; agent_skipped=skipped; save_result=save; saved=0; }
int test_save_calls(void) { return save_calls; }
int test_wake_calls(void) { return wake_calls; }
int test_cache_calls(void) { return cache_calls; }
int test_retry_calls(void) { return retry_calls; }
int buffer_fabric_identity_pair(char local[65], char peer[65])
{
  memset(local, 0, 65); memset(peer, 0, 65);
  if (pair_result) return pair_result;
  memset(local, 'a', 64); local[64]=0;
  memset(peer, 'b', 64); peer[64]=0;
  return 0;
}
bool buffer_wifi_is_ready(void) { return wifi_ready; }
void buffer_agent_snapshot(struct buffer_agent_status_s *status)
{ memset(status, 0, sizeof(*status)); status->ready=agent_ready; status->skipped=agent_skipped; }
int buffer_store_save_oobe_completed(bool value)
{ save_calls++; if (!save_result) saved=value; return save_result; }
bool buffer_store_oobe_completed_cached(void)
{ cache_calls++; return saved != 0; }
void buffer_store_oobe_completion_invalidate(void) { saved=0; }
void buffer_agent_retry(void) { retry_calls++; }
void buffer_agent_wake(void) { wake_calls++; }
''')
    subprocess.run([
        *CC, "-std=c11", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared",
        "-DCONFIG_BUFFER_VELA_FABRIC",
        "-I" + str(temp), "-I" + str(cjson),
        "-I" + str(root / "app/buffer_app"),
        str(root / "app/buffer_app/buffer_oobe.c"), str(cjson / "cJSON.c"),
        str(stubs),
        "-o", str(library_path),
    ], check=True)
    lib = C.CDLL(str(library_path))
    lib.buffer_oobe_decode.argtypes = [C.c_char_p, C.c_size_t, C.POINTER(Request)]
    lib.buffer_oobe_decode.restype = C.c_int
    lib.buffer_oobe_request_reset.argtypes = [C.POINTER(Request)]
    lib.buffer_oobe_present.argtypes = [
        C.c_int, C.c_bool, C.c_bool, C.c_bool, C.c_bool, C.c_bool,
        C.c_bool, C.POINTER(Presentation),
    ]
    lib.buffer_oobe_present.restype = None
    lib.buffer_oobe_finish.restype = C.c_int
    lib.buffer_oobe_setup_complete.restype = C.c_bool
    lib.test_setup.argtypes = [C.c_int, C.c_int, C.c_int, C.c_int, C.c_int]
    lib.test_save_calls.restype = C.c_int
    lib.test_wake_calls.restype = C.c_int
    lib.test_cache_calls.restype = C.c_int
    lib.test_retry_calls.restype = C.c_int

    def present(binding_status, paired, wifi_ready, agent_ready, skipped=False,
                deferred=False, setup_complete=False):
        state = Presentation()
        lib.buffer_oobe_present(binding_status, paired, wifi_ready, agent_ready,
                                skipped, deferred, setup_complete, C.byref(state))
        return state

    welcome = present(-errno.ENOENT, False, False, False, False)
    assert welcome.page == 1 and welcome.pairing_code_visible
    assert not welcome.retry_visible

    bound_offline = present(0, True, False, False, False)
    assert bound_offline.page == 2 and not bound_offline.pairing_code_visible
    assert bound_offline.retry_visible and not bound_offline.wifi_ready

    bound_online = present(0, True, True, False, False)
    assert bound_online.page == 2 and bound_online.wifi_ready
    assert not bound_online.pairing_code_visible

    ready_page = present(0, True, True, True)
    assert ready_page.page == 4
    assert present(0, True, True, True, skipped=True).page == 2

    unreadable = present(-errno.EIO, False, False, False, False)
    assert unreadable.page == 3 and not unreadable.pairing_code_visible
    unreadable_after_later = present(-errno.EIO, False, False, False,
                                     deferred=True)
    assert unreadable_after_later.page == 3
    assert not unreadable_after_later.pairing_code_visible

    complete = present(0, True, False, False, setup_complete=True)
    assert complete.page == 0 and not complete.pairing_code_visible

    later = present(-errno.ENOENT, False, False, False, deferred=True)
    assert later.page == 1 and later.pairing_code_visible
    configured_but_unconfirmed = present(0, True, True, True,
                                         deferred=True, setup_complete=False)
    assert configured_but_unconfirmed.page == 4

    # Finish uses the public Fabric identity API and does not require legacy
    # phone.conf. The store stub models a durable atomic commit.
    lib.test_setup(0, 1, 1, 0, 0)
    saves_before = lib.test_save_calls()
    wakes_before = lib.test_wake_calls()
    assert lib.buffer_oobe_finish() == 0
    assert lib.test_save_calls() == saves_before + 1
    assert lib.test_wake_calls() == wakes_before + 1
    assert lib.buffer_oobe_setup_complete()
    cache_before = lib.test_cache_calls()
    assert lib.buffer_oobe_setup_complete()
    assert lib.test_cache_calls() == cache_before + 1
    retries_before = lib.test_retry_calls()
    lib.buffer_oobe_retry()
    assert lib.test_retry_calls() == retries_before + 1
    assert not lib.buffer_oobe_setup_complete()

    lib.test_setup(-errno.ENOENT, 1, 1, 0, 0)
    saves_before = lib.test_save_calls()
    assert lib.buffer_oobe_finish() == -errno.ENOENT
    assert lib.test_save_calls() == saves_before
    lib.test_setup(0, 0, 1, 0, 0)
    assert lib.buffer_oobe_finish() == -errno.ENETUNREACH
    lib.test_setup(0, 1, 0, 0, 0)
    assert lib.buffer_oobe_finish() == -errno.EAGAIN
    lib.test_setup(0, 1, 1, 1, 0)
    assert lib.buffer_oobe_finish() == -errno.EAGAIN
    lib.test_setup(0, 1, 1, 0, -errno.EIO)
    assert lib.buffer_oobe_finish() == -errno.EIO
    assert not lib.buffer_oobe_setup_complete()

    request_id = "0123456789abcdef0123456789abcdef"

    def message(operation, payload, revision=12):
        return json.dumps({
            "type": "vela.oobe.request", "protocol": 1,
            "request_id": request_id, "revision": revision,
            "operation": operation, "payload": payload,
        }, separators=(",", ":")).encode()

    def decode(raw):
        result = Request()
        code = lib.buffer_oobe_decode(raw, len(raw), C.byref(result))
        return code, result

    code, status = decode(message("oobe.status", {}))
    assert code == 0 and status.operation == 0 and status.revision == 12

    code, wifi = decode(message("network.configure", {
        "ssid": "Home WiFi", "password": "test-password-123",
    }))
    assert code == 0 and wifi.operation == 1 and wifi.ssid == b"Home WiFi"
    assert wifi.wifi_password == b"test-password-123"

    agent_payload = {
        "base_url": "https://api.example/v1",
        "completion_model": "mimo-v2.5",
        "transcription_model": "mimo-v2.5-asr",
        "api_key": "private-test-secret",
        "time_zone": "Asia/Shanghai", "language": "zh-CN",
    }
    encoded = message("agent.configure", agent_payload)
    code, agent = decode(encoded)
    assert code == 0 and agent.operation == 2 and agent.api_key == b"private-test-secret"
    lib.buffer_oobe_request_reset(C.byref(agent))
    assert not any(bytes(agent))

    code, skipped = decode(message("agent.skip", {}))
    assert code == 0 and skipped.operation == 3

    for raw in (
        message("network.configure", {"ssid": "x", "password": "12345678", "unexpected": 1}),
        message("agent.configure", {**agent_payload, "transcription_model": "local-whisper"}),
        message("agent.configure", {**agent_payload, "base_url": "http://api.example/v1"}),
        message("agent.configure", {**agent_payload, "base_url": "https://user:pass@example/v1"}),
        message("agent.configure", {**agent_payload, "base_url": "https://api.example/v1?key=x"}),
        message("agent.configure", {**agent_payload, "api_key": "line\nbreak"}),
        message("agent.configure", {**agent_payload, "api_key": "x" * 8193}),
        message("oobe.status", {"api_key": "unexpected"}),
        message("unrecognized.operation", {}),
        message("oobe.status", {}, revision=1.5),
        message("oobe.status", {}, revision=0),
        b'{"type":"vela.oobe.request","type":"vela.oobe.request",'
        b'"protocol":1,"request_id":"' + request_id.encode() +
        b'","revision":1,"operation":"oobe.status","payload":{}}',
        message("oobe.status", {}).replace(b"\"payload\":{}", b"\"payload\":{\"x\":\"\\u0000\"}"),
        message("oobe.status", {}) + b"{}",
        b"x" * (12 * 1024 + 1),
    ):
        code, _ = decode(raw)
        assert code < 0, raw[:160]

print("PASS: Vela OOBE protocol validation, durable-binding screen transitions and fail-closed pairing-code visibility")
