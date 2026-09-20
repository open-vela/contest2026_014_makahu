"""Compile production phone proof validation with OpenSSL-backed HMAC adapter."""
from pathlib import Path
import subprocess, tempfile, hmac, hashlib
root = Path(__file__).resolve().parents[1]
wire = (root/'app/buffer_app/buffer_wire.c').read_text()
cjson = root.parent/'apps/netutils/cjson/cJSON'
def section(start,end):
    a=wire.index(start)
    return wire[a:wire.index(end,a)]
client='a'*64
nonce='b'*64
proof=hmac.new(b'test-token',f'buffer-phone-v1:{client}:{nonce}:vela-test:phone-test'.encode(),hashlib.sha256).hexdigest()
receipt_proof=hmac.new(b'test-token',f'buffer-enrollment-v1:{nonce}:vela-test:phone-test:{"c"*32}'.encode(),hashlib.sha256).hexdigest()
harness=r'''
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <assert.h>
#include <openssl/hmac.h>
#include "cJSON.h"
#define BUFFER_ID_SIZE 80
#define BUFFER_WIRE_AUTH_PROOF_SIZE 65
#define BUFFER_WIRE_DISCOVERY_LINE 1024
#define MBEDTLS_MD_SHA256 1
typedef EVP_MD mbedtls_md_info_t;
static const EVP_MD *mbedtls_md_info_from_type(int type) { (void)type; return EVP_sha256(); }
static int mbedtls_md_hmac(const EVP_MD *md, const unsigned char *key, size_t n,
                         const unsigned char *data, size_t size, unsigned char *out)
{ return HMAC(md,key,(int)n,data,size,out,NULL) != NULL ? 0 : -1; }
'''+section('static bool buffer_wire_valid_device_id(', 'static void buffer_wire_load_standalone_state(')+section('static int buffer_wire_hmac_proof(', 'static int buffer_response_ack_matches(')+f'''
int main(void) {{
  const char *client = "{client}";
  cJSON *challenge = cJSON_CreateObject();
  cJSON_AddStringToObject(challenge,"nonce","{nonce}");
  cJSON_AddStringToObject(challenge,"device_id","phone-test");
  assert(!buffer_wire_verify_phone(challenge,"test-token",client,"vela-test","phone-test"));
  cJSON_AddStringToObject(challenge,"phone_proof","{proof}");
  assert(buffer_wire_verify_phone(challenge,"test-token",client,"vela-test","phone-test"));
  assert(!buffer_wire_verify_phone(challenge,"wrong",client,"vela-test","phone-test"));
  assert(!buffer_wire_verify_phone(challenge,"test-token","{'c'*64}","vela-test","phone-test"));
  assert(!buffer_wire_verify_phone(challenge,"test-token",client,"other","phone-test"));
  assert(!buffer_wire_verify_phone(challenge,"test-token",client,"vela-test","other"));
  char receipt[65];
  assert(buffer_wire_enrollment_proof("test-token","{nonce}","vela-test","phone-test","{'c'*32}",receipt,sizeof(receipt)) == 0);
  assert(strcmp(receipt,"{receipt_proof}") == 0);
  assert(buffer_wire_enrollment_proof("test-token","{nonce}","vela-test","phone-test","bad",receipt,sizeof(receipt)) < 0);
  char reflected[65];
  assert(buffer_wire_hmac_proof("test-token","{nonce}","vela-test",reflected,sizeof(reflected)) == 0);
  cJSON_ReplaceItemInObjectCaseSensitive(challenge,"phone_proof",cJSON_CreateString(reflected));
  assert(!buffer_wire_verify_phone(challenge,"test-token",client,"vela-test","phone-test"));
  cJSON_Delete(challenge);
  puts("PASS: phone proof vector, missing proof, wrong token, nonce/identity binding, role reflection rejection");
}}
'''
with tempfile.TemporaryDirectory(prefix='buffer-phone-auth-') as tmp:
    work=Path(tmp)
    (work/'test.c').write_text(harness)
    subprocess.run(['cc','-Wall','-Wextra','-Werror','-I',str(cjson),str(work/'test.c'),str(cjson/'cJSON.c'),'-lcrypto','-lm','-o',str(work/'test')],check=True)
    subprocess.run([str(work/'test')],check=True)
