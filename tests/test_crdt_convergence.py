"""Production native CRDT convergence and malformed register rejection."""
import ctypes
import itertools
import json
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_native_crdt_converges(tmp_path):
    include = tmp_path / 'include'
    (include / 'nuttx').mkdir(parents=True)
    (include / 'nuttx/config.h').write_text('')
    (include / 'netutils').mkdir()
    cjson = SDK_ROOT / 'apps/netutils/cjson/cJSON'
    (include / 'netutils/cJSON.h').symlink_to(cjson / 'cJSON.h')
    glue = tmp_path / 'glue.c'
    glue.write_text('#include "buffer_agent.h"\n#include <string.h>\n' + extract_c(
        (PROJECT_ROOT / 'app/buffer_app/buffer_agent_store.c').read_text(), 'bool buffer_agent_valid_id('))
    library = tmp_path / 'crdt.so'
    subprocess.run([*CC, '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror', '-I' + str(include),
                    '-I' + str(PROJECT_ROOT / 'app/buffer_app'), str(PROJECT_ROOT / 'app/buffer_app/buffer_crdt.c'),
                    str(cjson / 'cJSON.c'), str(glue), '-lm', '-o', str(library)], check=True)
    lib = ctypes.CDLL(str(library)); pointer = ctypes.c_void_p
    lib.cJSON_Parse.argtypes = [ctypes.c_char_p]; lib.cJSON_Parse.restype = pointer
    lib.cJSON_Delete.argtypes = [pointer]
    lib.cJSON_PrintUnformatted.argtypes = [pointer]; lib.cJSON_PrintUnformatted.restype = pointer
    lib.free.argtypes = [pointer]
    lib.buffer_crdt_update.argtypes = [pointer, ctypes.c_char_p, ctypes.c_char_p, pointer, ctypes.c_char_p, ctypes.c_bool]
    lib.buffer_crdt_update.restype = pointer
    lib.buffer_crdt_merge.argtypes = [pointer, pointer]; lib.buffer_crdt_merge.restype = pointer
    lib.buffer_crdt_validate.argtypes = [pointer]
    def parse(value): return lib.cJSON_Parse(json.dumps(value).encode())
    def result(value):
        assert value
        encoded = lib.cJSON_PrintUnformatted(value)
        try: return json.loads(ctypes.string_at(encoded))
        finally: lib.free(encoded); lib.cJSON_Delete(value)
    def update(previous, changes, actor, deleted=False):
        a = parse(previous) if previous else None; b = parse(changes)
        try: return result(lib.buffer_crdt_update(a, b'task-one', b'card', b, actor.encode(), deleted))
        finally: lib.cJSON_Delete(a); lib.cJSON_Delete(b)
    def merge(left, right):
        a, b = parse(left), parse(right)
        try: return result(lib.buffer_crdt_merge(a, b))
        finally: lib.cJSON_Delete(a); lib.cJSON_Delete(b)
    base = update(None, {'title': 'old', 'state': 'active'}, 'a' * 64)
    edits = [update(base, {'title': 'edited'}, 'a' * 64), update(base, {'state': 'done'}, 'b' * 64),
             update(base, {'schedule': {'start': 1792458000000, 'end': 1792461600000, 'all_day': False, 'zone': 'Asia/Shanghai'}}, 'c' * 64)]
    expected = merge(merge(edits[0], edits[1]), edits[2])
    for permutation in itertools.permutations(edits):
        actual = base
        for change in [*permutation, *reversed(permutation), base]: actual = merge(actual, change)
        assert actual == expected
    assert expected['fields']['title']['value'] == 'edited'
    assert expected['fields']['state']['value'] == 'done'
    removed = update(base, {}, 'b' * 64, True)
    assert merge(expected, removed)['deleted']
    assert merge(removed, expected) == merge(expected, removed)
    assert update(removed, {'title': 'again'}, 'a' * 64)['deleted']
    corrupt = json.loads(json.dumps(base)); corrupt['fields']['title']['value'] = 'bad'
    a, b = parse(base), parse(corrupt)
    assert not lib.buffer_crdt_merge(a, b)
    lib.cJSON_Delete(a); lib.cJSON_Delete(b)
    for clock in [0, -1, 1.5, 9007199254740992]:
        malformed = json.loads(json.dumps(base)); malformed['fields']['title']['counter'] = clock
        a = parse(malformed); assert lib.buffer_crdt_validate(a) != 0; lib.cJSON_Delete(a)
