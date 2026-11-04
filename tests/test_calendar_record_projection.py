"""Calendar instants must survive the actual CRDT -> Vela display record conversion."""
import ctypes
import json
import subprocess
from host_support import PROJECT_ROOT, SDK_ROOT, CC, extract_c


def test_calendar_projection_preserves_signed_instants_and_point_events(tmp_path):
    include = tmp_path / 'include'
    (include / 'nuttx').mkdir(parents=True)
    (include / 'nuttx/config.h').write_text('')
    (include / 'netutils').mkdir()
    cjson = SDK_ROOT / 'apps/netutils/cjson/cJSON'
    (include / 'netutils/cJSON.h').symlink_to(cjson / 'cJSON.h')
    app = PROJECT_ROOT / 'app/buffer_app'
    replica = (app / 'buffer_replica.c').read_text()
    source = '#include "buffer_agent.h"\n#include "buffer_crdt.h"\n#include <string.h>\n#include <math.h>\n#include <errno.h>\n'
    source += extract_c((app / 'buffer_agent_store.c').read_text(), 'bool buffer_agent_valid_id(')
    for signature in ['static const char *text(', 'static bool integer(', 'static bool digest_valid(',
                      'static bool add_copy(', 'static bool task_action_view(', 'cJSON *buffer_replica_crdt_record(']:
        source += '\n' + extract_c(replica, signature)
    view = (app / 'buffer_source_view.c').read_text()
    for signature in ['static bool string_field(', 'static bool calendar_instant(', 'static bool counter(',
                      'static bool reference_id(', 'int buffer_source_view_validate(']:
        source += '\n' + extract_c(view, signature)
    glue = tmp_path / 'projection.c'; glue.write_text(source)
    library = tmp_path / 'projection.so'
    subprocess.run([*CC, '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function',
                    '-I' + str(include), '-I' + str(app), str(app / 'buffer_crdt.c'),
                    str(cjson / 'cJSON.c'), str(glue), '-lm', '-o', str(library)], check=True)
    lib = ctypes.CDLL(str(library)); ptr = ctypes.c_void_p
    lib.cJSON_Parse.argtypes = [ctypes.c_char_p]; lib.cJSON_Parse.restype = ptr
    lib.cJSON_Delete.argtypes = [ptr]
    lib.cJSON_PrintUnformatted.argtypes = [ptr]; lib.cJSON_PrintUnformatted.restype = ptr
    lib.free.argtypes = [ptr]
    lib.buffer_crdt_update.argtypes = [ptr, ctypes.c_char_p, ctypes.c_char_p, ptr, ctypes.c_char_p, ctypes.c_bool]
    lib.buffer_crdt_update.restype = ptr
    lib.buffer_source_view_validate.argtypes = [ptr]
    lib.buffer_replica_crdt_record.argtypes = [ptr, ptr]; lib.buffer_replica_crdt_record.restype = ptr
    for start, end in [(-178934400000, -178876800000), (0, 0), (1793721600000, 1793721600000),
                       (1793721600000, 1793725200000)]:
        schedule = dict(start=start, end=end, all_day=False, zone='Asia/Shanghai')
        changes = lib.cJSON_Parse(json.dumps(dict(title='Calendar event', location='', schedule=schedule)).encode())
        doc = lib.buffer_crdt_update(None, b'calendar-series', b'calendar', changes, b'a' * 64, False)
        assert doc
        assert lib.buffer_source_view_validate(doc) == 0
        record = lib.buffer_replica_crdt_record(doc, None)
        try:
            assert record, f'Projection rejected valid calendar instant {start}'
            raw = lib.cJSON_PrintUnformatted(record)
            try:
                value = json.loads(ctypes.string_at(raw))
                assert value['card']['schedule'] == schedule
                assert value['card']['created_at'] == start
                assert value['card']['kind'] == 'calendar_event'
                assert value['crdt']['kind'] == 'calendar'
            finally:
                lib.free(raw)
        finally:
            lib.cJSON_Delete(record); lib.cJSON_Delete(doc); lib.cJSON_Delete(changes)
