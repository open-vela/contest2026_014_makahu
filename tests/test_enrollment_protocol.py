"""Exercise real C JSON validation and bounded BLE fragment assembly."""
from pathlib import Path
import ctypes as C
import json
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
app = root / 'app/buffer_app'
cjson = root.parent / 'apps/netutils/cjson/cJSON'
class Request(C.Structure):
    _fields_ = [('use_existing_wifi', C.c_int)] + [(k, C.c_char*n) for k,n in [('request_id',33),('phone_id',80),('token',65),('ssid',33),('password',65)]]
class Frame(C.Structure):
    _fields_ = [('total',C.c_size_t),('received',C.c_size_t),('data',C.c_ubyte*769)]
with tempfile.TemporaryDirectory() as tmp:
    (Path(tmp)/'netutils').mkdir()
    (Path(tmp)/'netutils/cJSON.h').symlink_to(cjson/'cJSON.h')
    so = str(Path(tmp)/'protocol.so')
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror','-fPIC','-shared',
                    '-I'+tmp,'-I'+str(cjson),str(app/'buffer_enrollment.c'),str(cjson/'cJSON.c'),'-o',so],check=True)
    lib = C.CDLL(so)
    lib.buffer_enrollment_decode.argtypes=[C.c_char_p,C.c_size_t,C.POINTER(Request)]
    lib.buffer_enrollment_feed.argtypes=[C.POINTER(Frame),C.c_char_p,C.c_size_t]
    lib.buffer_enrollment_reset.argtypes=[C.POINTER(Frame)]
    lib.buffer_wifi_credentials_valid.argtypes=[C.c_char_p,C.c_char_p]
    obj=dict(type='buffer.configure',protocol=1,request_id='a'*32,phone_device_id='phone-01',token='b'*64,ssid='家庭网络',password='password123')
    def encode(o): return json.dumps(o,ensure_ascii=False,separators=(',',':')).encode()
    def decode(b):
        r=Request();result=lib.buffer_enrollment_decode(b,len(b),C.byref(r));return result,r
    payload=encode(obj)
    result,r=decode(payload)
    assert result==0 and r.ssid=='家庭网络'.encode() and r.phone_id==b'phone-01'
    existing={k:v for k,v in obj.items() if k not in ('ssid','password')}
    existing['type']='buffer.bind'
    result,bound=decode(encode(existing))
    assert result==0 and bound.use_existing_wifi==1 and not bound.ssid and not bound.password
    assert r.use_existing_wifi==0
    for key in ('ssid','password'):
        mixed=dict(existing);mixed[key]='password';assert decode(encode(mixed))[0]<0
    for key in existing:
        missing=dict(existing);del missing[key];assert decode(encode(missing))[0]<0
    for key in obj:
        bad=dict(obj);del bad[key];assert decode(encode(bad))[0]<0
        bad=dict(obj);bad[key]=None;assert decode(encode(bad))[0]<0
    for key,value in [('protocol',True),('protocol',1.1),('type','other'),('phone_device_id','x:y'),('phone_device_id','x'*80),('request_id','0'*31),('token','G'*64),('ssid','中'*11),('password','x'*64),('ssid','ab\x00cd'),('ssid','bad\nname')]:
        bad=dict(obj);bad[key]=value;assert decode(encode(bad))[0]<0,(key,value)
    assert decode(payload[:-1]+b',"protocol":1}')[0]<0
    assert decode(payload+b'{}')[0]<0
    assert decode(payload+b'\0junk')[0]<0
    assert decode(b'x'*769)[0]<0
    bad=dict(obj);bad['ssid']=r'literal\u0000';assert decode(encode(bad))[0]==0
    assert lib.buffer_wifi_credentials_valid(b'wifi',b'A'*64)==0
    assert lib.buffer_wifi_credentials_valid(b'wifi',b'g'*64)<0
    def fragment(offset,data,total=len(payload)):
        return offset.to_bytes(2,'big')+total.to_bytes(2,'big')+data
    def feed(f,b): return lib.buffer_enrollment_feed(C.byref(f),b,len(b))
    # MTU 23 gives a 20-byte attribute write: 4-byte header + 16-byte body.
    f=Frame()
    assert feed(f,fragment(16,payload[16:32]))<0 and f.received==0
    for i in range(0,len(payload),16):
        part=fragment(i,payload[i:i+16]);result=feed(f,part)
        assert result==(1 if i+16>=len(payload) else 0)
        assert feed(f,part)==0  # duplicate final fragment does not reexecute
    assert bytes(f.data[:f.received])==payload
    assert feed(f,fragment(0,b'Z'))<0
    assert feed(f,fragment(0,b'X',769))<0
    lib.buffer_enrollment_reset(C.byref(f))
    assert f.total==0 and f.received==0 and not any(f.data)
    assert feed(f,fragment(0,payload))==1
    print('PASS: strict enrollment JSON, credentials, NUL/duplicate rejection, MTU-23 fragments, retransmission and reset')
