"""Exercise SDK GATT table ownership and compaction with ASan/UBSan."""
from host_support import PROJECT_ROOT, SDK_ROOT, extract_c, CC
from pathlib import Path
import os
import subprocess
import tempfile

project = PROJECT_ROOT
sdk = SDK_ROOT
source = (sdk / "frameworks/connectivity/bluetooth/service/stacks/zephyr/sal_gatt_server_interface.c").read_text()

def function(name):
    start = source.rfind("static ", 0, source.index(name))
    return extract_c(source, source[start:source.index("{", start)].strip())

harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define CONFIG_GATT_SERVER_MAX_ATTRIBUTES 16
#define BT_UUID_TYPE_16 0
#define BT_LOGE(...) ((void)0)
#define BT_LOGD(...) ((void)0)
#define CONTAINER_OF(p,t,m) ((t *)((char *)(p)-offsetof(t,m)))
struct bt_uuid { uint8_t type; };
union uuid {
    struct bt_uuid uuid;
    struct { struct bt_uuid uuid; uint16_t value; } u16;
    struct { struct bt_uuid uuid; uint8_t value[16]; } u128;
};
struct bt_gatt_attr { const struct bt_uuid *uuid; void *user_data; };
static struct bt_gatt_attr server_db[CONFIG_GATT_SERVER_MAX_ATTRIBUTES];
static bool server_data_owned[CONFIG_GATT_SERVER_MAX_ATTRIBUTES];
static size_t attr_count, svc_attr_count;
'''
harness += function("remove_from_server_db(") + "\n" + function("gatt_db_add(")
harness += r'''
int main(void) {
    union uuid uuid = {.u16 = {.uuid = {.type=BT_UUID_TYPE_16}, .value=42}};
    int caller_owned = 1234, copy_owned = 5678;
    for (int cycle=0; cycle<30; cycle++) {
        struct bt_gatt_attr pattern = {.uuid=&uuid.uuid, .user_data=&caller_owned};
        assert(gatt_db_add(&pattern, 0) == server_db);
        pattern.user_data=&copy_owned;
        assert(gatt_db_add(&pattern, sizeof(copy_owned)) == server_db+1);
        pattern.user_data=&caller_owned;
        assert(gatt_db_add(&pattern, 0) == server_db+2);
        pattern.user_data=&copy_owned;
        assert(gatt_db_add(&pattern, sizeof(copy_owned)) == server_db+3);
        remove_from_server_db(server_db, 2);
        assert(attr_count == 2 && !server_data_owned[0] && server_data_owned[1]);
        assert(server_db[0].user_data == &caller_owned);
        assert(*(int *)server_db[1].user_data == copy_owned);
        remove_from_server_db(server_db, 2);
        assert(attr_count == 0 && caller_owned == 1234);
        for (int i=0;i<4;i++) assert(!server_data_owned[i] && !server_db[i].uuid);
    }
    return 0;
}
'''
with tempfile.TemporaryDirectory() as d:
    c = Path(d)/"gatt.c"; c.write_text(harness)
    exe = str(Path(d)/"gatt")
    subprocess.run([*CC, "-g", "-fsanitize=address,undefined", "-fno-omit-frame-pointer", str(c), "-o", exe], check=True)
    subprocess.run([exe], check=True, timeout=10)
print("SDK GATT borrowed/owned values and table compaction: 30 ASan cycles passed")
