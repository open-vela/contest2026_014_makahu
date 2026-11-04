"""Behavioral contract against compiled production source-event C APIs."""
import ctypes as C
import errno
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import wave

AUTH=C.CFUNCTYPE(C.c_bool,C.c_void_p)
PEER=b'b'*64
OTHER=b'c'*64
class Import(C.Structure):
    _fields_=[('state',C.c_int),('next',C.c_uint64)]
class Progress(C.Structure):
    _fields_=[('covered',C.c_uint64),('original',C.c_uint64)]
class VisibleImport(C.Structure):
    _fields_=[('state',C.c_int),('progress',Progress)]
class Projection(C.Structure):
    _fields_=[('peer',C.c_char*65),('source',C.c_char*97),('event_id',C.c_char*161),('fingerprint',C.c_char*65),
              ('sequence',C.c_uint64),('raw',C.c_void_p),('length',C.c_size_t),('applied',C.c_bool),('state',C.c_char*32)]


def bind(lib):
    signatures={
        'buffer_source_local_head':[C.c_char_p,C.c_size_t,C.POINTER(C.c_uint64)],
        'buffer_source_local_read':[C.c_uint64,C.POINTER(C.c_void_p),C.POINTER(C.c_size_t)],
        'buffer_source_projection_save':[C.c_char_p,C.c_char_p,C.c_char_p,C.c_char_p],
        'buffer_source_projection_read_bytes':[C.c_char_p,C.c_size_t,C.POINTER(C.c_void_p),C.POINTER(C.c_size_t)],
        'buffer_source_remote_receive':[C.c_char_p,C.c_char_p,C.c_size_t,AUTH,C.c_void_p,C.POINTER(Import)],
        'buffer_source_remote_read':[C.c_char_p,C.c_uint64,C.POINTER(C.c_void_p),C.POINTER(C.c_size_t)],
        'buffer_source_remote_progress':[C.c_char_p,C.c_char_p,AUTH,C.c_void_p,C.POINTER(C.c_uint64)],
        'buffer_source_remote_visibility_reserve':[C.c_char_p,C.c_char_p,C.c_uint64,C.c_char_p,AUTH,C.c_void_p,C.POINTER(VisibleImport)],
        'buffer_source_remote_visibility_receive':[C.c_char_p,C.c_char_p,C.c_size_t,C.c_char_p,AUTH,C.c_void_p,C.POINTER(VisibleImport)],
        'buffer_source_remote_visibility_progress':[C.c_char_p,C.c_char_p,AUTH,C.c_void_p,C.POINTER(Progress)],
        'buffer_source_remote_causal_check':[C.POINTER(Projection),C.c_char_p],
        'buffer_source_capture_ensure':[C.c_char_p],
        'buffer_source_blob_open':[C.c_uint64,C.c_char_p,C.c_char_p,C.c_uint64,C.POINTER(C.c_int)],
    }
    for name,args in signatures.items():
        fn=getattr(lib,name);fn.argtypes=args;fn.restype=C.c_int
    lib.fixture_free.argtypes=[C.c_void_p]


def head(lib):
    source=C.create_string_buffer(97);sequence=C.c_uint64()
    assert lib.buffer_source_local_head(source,97,C.byref(sequence))==0
    return source.value,sequence.value


def read(lib,name,*args):
    ptr=C.c_void_p();length=C.c_size_t()
    ret=getattr(lib,name)(*args,C.byref(ptr),C.byref(length))
    if ret:
        assert not ptr.value
        return ret,None
    try:return ret,C.string_at(ptr,length.value)
    finally:lib.fixture_free(ptr)


def envelope(source,sequence,event_id,clock,parents=(),payload=None):
    return json.dumps(dict(wire_version=1,source_id=source,source_sequence=sequence,provenance='native',event=dict(
        event_id=event_id,app_namespace='app.buffer',actor_id='user',schema_version=1,parent_event_ids=list(parents),
        logical_clock=clock,type='ModeChangeRequested',capture_id=None,created_at=1000,payload=payload or {'mode':'focus'})),
        ensure_ascii=False,separators=(',',':')).encode()


def causal(lib,raw):
    j=json.loads(raw);buf=C.create_string_buffer(raw);e=j['event']
    p=Projection(peer=PEER,source=j['source_id'].encode(),event_id=e['event_id'].encode(),
        fingerprint=hashlib.sha256(raw).hexdigest().encode(),sequence=j['source_sequence'],raw=C.cast(buf,C.c_void_p),length=len(raw))
    state=C.create_string_buffer(24)
    assert lib.buffer_source_remote_causal_check(C.byref(p),state)==0
    return state.value


def restarted(library,source,sequence,expected):
    lib=C.CDLL(library);bind(lib);assert lib.buffer_agent_store_init()==0
    assert head(lib)==(source.encode(),int(sequence))
    ret,raw=read(lib,'buffer_source_remote_read',b'remote-a',1)
    assert ret==0 and hashlib.sha256(raw).hexdigest()==expected


def run_contract(lib,data,library):
    bind(lib);allow=AUTH(lambda _:True);deny=AUTH(lambda _:False)
    assert lib.buffer_agent_store_init()==0
    assert head(lib)[1]==0,'journal-disabled stubs must not satisfy this fixture'
    mode=str(data/'mode.conf').encode()
    assert lib.buffer_source_projection_save(mode,b'normal\n',b'ModeStateChanged',None)==0
    local,count=head(lib);assert count==1
    ret,original=read(lib,'buffer_source_local_read',1);assert ret==0
    assert json.loads(original)['event']['payload']['projection_text']=='normal\n'
    assert lib.buffer_source_projection_save(mode,b'normal\n',b'ModeStateChanged',None)==0
    assert head(lib)[1]==1,'unchanged cache is not a second fact'
    (data/'mode.conf').write_text('forged\n')
    assert read(lib,'buffer_source_projection_read_bytes',mode,65536)==(0,b'normal\n')
    assert head(lib)[1]==1,'cache repair must not mint original facts'
    print('PASS native immutable originals, idempotent local writes and cache repair',flush=True)

    # A local read must not recover/scan unrelated remote streams while holding
    # journal_lock. New facts still need a verified remote Lamport clock.
    remote_state=data/'event-journal/remote-state.json'
    saved_remote_state=remote_state.read_bytes()
    try:
        remote_state.write_text('{}')
        assert head(lib)==(local,count)
        assert read(lib,'buffer_source_local_read',1)==(0,original)
        assert read(lib,'buffer_source_projection_read_bytes',mode,65536)==(0,b'normal\n')
        assert lib.buffer_source_projection_save(mode,b'focus\n',b'ModeStateChanged',None)==-errno.EIO
        assert head(lib)==(local,count)
        assert (data/'mode.conf').read_bytes()==b'normal\n'
    finally:
        remote_state.write_bytes(saved_remote_state)
    print('PASS local reads independent of remote recovery; new writes retain clock validation',flush=True)

    child=envelope('remote-a',1,'child',10,['parent'])
    result=Import()
    assert lib.buffer_source_remote_receive(PEER,child,len(child),deny,None,C.byref(result))==-errno.EACCES
    assert lib.buffer_source_remote_receive(PEER,child,len(child),allow,None,C.byref(result))==0 and result.state==0 and result.next==2
    assert lib.buffer_source_remote_receive(PEER,child,len(child),allow,None,C.byref(result))==0 and result.state==1
    assert read(lib,'buffer_source_remote_read',b'remote-a',1)==(0,child)
    changed=envelope('remote-a',1,'child',10,['parent'],{'mode':'bedtime'})
    assert lib.buffer_source_remote_receive(PEER,changed,len(changed),allow,None,C.byref(result))==0 and result.state==3
    gap=envelope('remote-a',3,'gap',12)
    assert lib.buffer_source_remote_receive(PEER,gap,len(gap),allow,None,C.byref(result))==0 and result.state==2
    assert lib.buffer_source_remote_receive(OTHER,child,len(child),allow,None,C.byref(result))==-errno.EACCES
    assert causal(lib,child)==b'missing_parent'
    parent=envelope('remote-b',1,'parent',5)
    assert lib.buffer_source_remote_receive(PEER,parent,len(parent),allow,None,C.byref(result))==0
    assert causal(lib,parent)==b'linked' and causal(lib,child)==b'linked'
    assert head(lib)[1]==1,'receiving history must not request or execute a local mode change'
    assert (data/'mode.conf').read_bytes()==b'normal\n'
    print('PASS authenticated owner pin, duplicate/conflict/gap and late causal parent; history has no local side effects',flush=True)

    calls=[0]
    def revoke(_):
        calls[0]+=1
        return calls[0]<3
    revoked=AUTH(revoke)
    raw=envelope('revoked-source',1,'revoked-event',20)
    assert lib.buffer_source_remote_receive(PEER,raw,len(raw),revoked,None,C.byref(result))==-errno.EACCES
    assert read(lib,'buffer_source_remote_read',b'revoked-source',1)[0]!=0
    print('PASS authorization revoked during receive leaves no accepted original',flush=True)

    source=b'visible-source';token1=b'1'*32;token2=b'2'*32;v=VisibleImport()
    assert lib.buffer_source_remote_visibility_reserve(PEER,source,1,token1,allow,None,C.byref(v))==0
    assert v.progress.covered==2 and v.progress.original==1
    assert read(lib,'buffer_source_remote_read',source,1)[0]==-errno.EAGAIN
    assert lib.buffer_source_remote_visibility_reserve(PEER,source,2,token2,allow,None,C.byref(v))==0
    second=envelope('visible-source',2,'visible-child',32,['visible-parent'])
    first=envelope('visible-source',1,'visible-parent',31)
    assert lib.buffer_source_remote_visibility_receive(PEER,second,len(second),token2,allow,None,C.byref(v))==0
    assert v.progress.covered==3 and v.progress.original==1
    assert causal(lib,second)==b'missing_parent'
    assert lib.buffer_source_remote_visibility_receive(PEER,first,len(first),b'3'*32,allow,None,C.byref(v))==0 and v.state==6
    assert read(lib,'buffer_source_remote_read',source,1)[0]==-errno.EAGAIN
    assert lib.buffer_source_remote_visibility_receive(PEER,first,len(first),token1,allow,None,C.byref(v))==0
    assert v.progress.original==3
    assert causal(lib,first)==b'linked' and causal(lib,second)==b'linked'
    print('PASS visibility reservations are not original receipts; sparse original fill and token fences',flush=True)

    audio=data/'queue'/'meal.wav'
    with wave.open(str(audio),'wb') as out:
        out.setnchannels(1);out.setsampwidth(2);out.setframerate(16000);out.writeframes(bytes(3200))
    audio.chmod(0o600)
    meta=data/'queue'/'meal.meta';meta.write_text('source_events=1\ncreated_at=1000\nduration_ms=100\nmode=normal\n');meta.chmod(0o600)
    assert lib.buffer_source_capture_ensure(b'meal')==0
    sequence=head(lib)[1];assert sequence==2
    ret,audio_event=read(lib,'buffer_source_local_read',sequence);assert ret==0
    blob=json.loads(audio_event)['event']['payload']['blob'];digest=hashlib.sha256(audio_event).hexdigest().encode()
    fd=C.c_int(-1)
    assert lib.buffer_source_blob_open(sequence,digest,blob['sha256'].encode(),blob['bytes'],C.byref(fd))==0
    try:
        os.lseek(fd.value,0,os.SEEK_SET);assert os.read(fd.value,blob['bytes'])==audio.read_bytes()
    finally:os.close(fd.value)
    assert lib.buffer_source_blob_open(sequence,b'0'*64,blob['sha256'].encode(),blob['bytes'],C.byref(fd))<0 and fd.value==-1
    assert lib.buffer_source_capture_ensure(b'meal')==0 and head(lib)[1]==sequence
    print('PASS audio archive bound to exact source event, digest, size and descriptor',flush=True)

    # Fail real atomic publication and durability calls, then recover from the
    # same persisted journal rather than replacing its production implementation.
    for operation in ('rename','fsync'):
        for phase in (1,2,3):
            before=head(lib)[1]
            failure=getattr(lib,'fixture_journal_fail_'+operation)
            failure.argtypes=[C.c_uint];failure(phase)
            target=('focus' if before%2==0 else 'bedtime').encode()+b'\n'
            assert lib.buffer_source_projection_save(mode,target,b'ModeStateChanged',None)<0,(operation,phase)
            failure(0)
            assert lib.buffer_source_projection_save(mode,target,b'ModeStateChanged',None)==0
            assert head(lib)[1]==before+1
            assert read(lib,'buffer_source_projection_read_bytes',mode,65536)==(0,target)
    sequence=head(lib)[1]
    print('PASS injected ENOSPC rename and failed fsync retain retryable facts without duplicate commits',flush=True)

    for path in (data/'event-journal').rglob('*'):
        assert path.stat().st_mode&0o777==(0o700 if path.is_dir() else 0o600),(path,oct(path.stat().st_mode))
    subprocess.run([sys.executable,'-c',
        'import sys;sys.path.insert(0,sys.argv[1]);from source_journal_contract import restarted;restarted(*sys.argv[2:])',
        str(Path(__file__).parent),str(library),local.decode(),str(sequence),hashlib.sha256(child).hexdigest()],check=True)
    assert read(lib,'buffer_source_local_read',1)==(0,original)
    archive=data/'event-journal'/'local'/'00000000000000000001.json'
    archive.write_bytes(original.replace(b'normal',b'forged'))
    assert read(lib,'buffer_source_local_read',1)[0]<0,'corrupt committed bytes must never be reserialized as a valid event'
    archive.write_bytes(original)
    assert read(lib,'buffer_source_local_read',1)==(0,original)
    print('PASS fresh-process recovery, actual private file modes and immutable-byte corruption rejection',flush=True)
    # Cold operation lookup must converge without more API/model requests.
    # Exceed the per-step work budget, then drive the real background pump.
    for i in range(70):
        value=("focus\n" if i%2==0 else "bedtime\n").encode()
        assert lib.buffer_source_projection_save(mode,value,b'ModeStateChanged',None)==0
    subprocess.run([sys.executable,'-c',
        'import sys;sys.path.insert(0,sys.argv[1]);from source_journal_contract import cold_operation_lookup;cold_operation_lookup(sys.argv[2])',
        str(Path(__file__).parent),str(library)],check=True)
    print('PASS cold operation lookup converges on background maintenance without a model retry',flush=True)
    subprocess.run([sys.executable,'-c',
        'import sys;sys.path.insert(0,sys.argv[1]);from source_journal_contract import prewarm_operation_lookup;prewarm_operation_lookup(sys.argv[2])',
        str(Path(__file__).parent),str(library)],check=True)
    print('PASS idle prewarm proves absence before the first foreground operation',flush=True)
    # Real domain + immutable source journal: appended restart enum must be
    # recognized as a stateful timer operation on duplicate receipt lookup.
    import time
    for _ in range(1000):
        result=lib.fixture_journal_restart_history_check()
        if result!=-errno.EAGAIN:break
        lib.buffer_source_events_maintenance();time.sleep(0.002)
    assert result==0, result
    print('PASS timer restart idempotency through actual source journal',flush=True)
    for _ in range(1000):
        result=lib.fixture_recipe_restore_seed()
        if result!=-errno.EAGAIN:break
        lib.buffer_source_events_maintenance();time.sleep(0.002)
    assert result==0,result
    lib.fixture_recipe_restore_surface.restype=C.c_char_p
    surface=lib.fixture_recipe_restore_surface().decode()
    before_restore=head(lib)
    before_source=(data/"event-journal/source.json").read_bytes()
    subprocess.run([sys.executable,'-c',
        'import sys;sys.path.insert(0,sys.argv[1]);from source_journal_contract import restore_recipe_fresh_process;restore_recipe_fresh_process(*sys.argv[2:])',
        str(Path(__file__).parent),str(library),surface],check=True)
    assert head(lib)==before_restore,'restoration appended an execution event'
    assert (data/'event-journal/source.json').read_bytes()==before_source,'restoration modified the durable source head'
    print('PASS fresh-process recipe source/timer restoration without action re-execution or foreground replacement',flush=True)

    subprocess.run([sys.executable,'-c',
        'import sys;sys.path.insert(0,sys.argv[1]);from source_journal_contract import independent_legacy_recovery;independent_legacy_recovery(*sys.argv[2:])',
        str(Path(__file__).parent),str(library),str(data)],check=True)
    print('PASS unrelated invalid legacy calendar cannot block a healthy mode read/write',flush=True)


    if os.environ.get('BUFFER_JOURNAL_INTEROP_DIR'):
        run_interop(lib,data,os.environ['BUFFER_JOURNAL_INTEROP_DIR'])


def independent_legacy_recovery(library,data):
    data=Path(data);bad=data/'calendars/unrelated-legacy.json'
    bad.write_text('{');bad.chmod(0o600)
    try:
        lib=C.CDLL(library);bind(lib);assert lib.buffer_agent_store_init()==0
        mode=str(data/'mode.conf').encode()
        before=head(lib)
        for _ in range(200):
            ready=lib.buffer_source_events_ready()
            if ready!=-errno.EAGAIN:break
            lib.buffer_source_events_maintenance()
        assert ready==-errno.EINVAL,ready
        ret,current=read(lib,'buffer_source_projection_read_bytes',mode,65536);assert ret==0
        assert lib.buffer_source_projection_save(mode,current,b'ModeStateChanged',None)==0
        assert head(lib)==before
        assert read(lib,'buffer_source_projection_read_bytes',str(bad).encode(),65536)[0]==-errno.EINVAL
    finally:
        bad.unlink()


def restore_recipe_fresh_process(library,surface):
    import time
    lib=C.CDLL(library);bind(lib)
    assert lib.buffer_agent_store_init()==0
    lib.fixture_recipe_restore_verify.argtypes=[C.c_char_p]
    for _ in range(1000):
        result=lib.fixture_recipe_restore_verify(surface.encode())
        if result!=-errno.EAGAIN:break
        lib.buffer_source_events_maintenance();time.sleep(0.002)
    assert result==0,result


def prewarm_operation_lookup(library):
    lib=C.CDLL(library);bind(lib)
    assert lib.buffer_agent_store_init()==0
    for _ in range(200):
        if lib.buffer_source_events_maintenance()==1:break
    lib.buffer_source_assistant_operation.argtypes=[C.c_char_p,C.POINTER(C.c_void_p),C.POINTER(C.c_void_p)]
    operation=C.c_void_p();request=C.c_void_p()
    for name in (b'first-new-command',b'second-new-command'):
        assert lib.buffer_source_assistant_operation(name,C.byref(operation),C.byref(request))==-errno.ENOENT
        assert not operation.value and not request.value


def cold_operation_lookup(library):
    lib=C.CDLL(library);bind(lib)
    assert lib.buffer_agent_store_init()==0
    lib.buffer_source_assistant_operation.argtypes=[C.c_char_p,C.POINTER(C.c_void_p),C.POINTER(C.c_void_p)]
    operation=C.c_void_p();request=C.c_void_p()
    lookup=lambda:lib.buffer_source_assistant_operation(b'never-executed',C.byref(operation),C.byref(request))
    result=lookup()
    assert result in (-errno.EAGAIN,-errno.ENOENT), result
    if result==-errno.EAGAIN:
        for _ in range(200):
            if lib.buffer_source_events_maintenance()==1:break
        else:raise AssertionError('background index work never converged')
    assert lookup()==-errno.ENOENT
    assert not operation.value and not request.value

def run_interop(lib,data,root):
    """Exchange actual runtime originals, without replacing either journal."""
    root=Path(root).resolve();root.mkdir(parents=True,exist_ok=True)
    manifest=root/'android'/'manifest.json'
    assert manifest.is_file(),'run the Android interop export first'
    allow=AUTH(lambda _:True);result=Import();before=head(lib)
    mode=(data/'mode.conf').read_bytes()
    types=set();source=None
    entries=json.loads(manifest.read_text())['events']
    assert entries
    for index,entry in enumerate(entries,1):
        raw=(manifest.parent/entry['file']).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==entry['sha256']
        value=json.loads(raw);source=value['source_id'].encode()
        assert value['source_sequence']==index
        types.add(value['event']['type'])
        assert lib.buffer_source_remote_receive(PEER,raw,len(raw),allow,None,C.byref(result))==0 and result.state==0
        assert lib.buffer_source_remote_receive(PEER,raw,len(raw),allow,None,C.byref(result))==0 and result.state==1
        assert read(lib,'buffer_source_remote_read',source,index)==(0,raw)
    assert {'CaptureCreated','CaptureTranscribed','HealthQuantRecorded'}<=types
    assert head(lib)==before and (data/'mode.conf').read_bytes()==mode
    progress=C.c_uint64()
    assert lib.buffer_source_remote_progress(PEER,source,allow,None,C.byref(progress))==0 and progress.value==len(entries)+1
    out=root/'native';out.mkdir(exist_ok=True)
    native=[]
    for sequence in range(1,before[1]+1):
        ret,raw=read(lib,'buffer_source_local_read',sequence);assert ret==0
        name=f'{sequence}.json';(out/name).write_bytes(raw)
        native.append(dict(file=name,sha256=hashlib.sha256(raw).hexdigest()))
    audio=(data/'queue'/'meal.wav').read_bytes();(out/'meal.wav').write_bytes(audio)
    (out/'manifest.json').write_text(json.dumps(dict(events=native,audio_file='meal.wav')))
    (root/'native-received.json').write_text(json.dumps(dict(android_events=len(entries),source=source.decode(),
        original_next=progress.value,native_head_before=before[1],native_head_after=head(lib)[1])))
    print('PASS actual Android -> native originals, exact duplicate recovery and no local execution; native originals/audio exported',flush=True)
