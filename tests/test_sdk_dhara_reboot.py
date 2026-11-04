"""Exercise LittleFS remount after Dhara learns a larger allocation capacity."""
from pathlib import Path
import subprocess

from host_support import CC, PROJECT_ROOT, SDK_ROOT


def test_dhara_littlefs_reboot(tmp_path: Path):
    dhara = SDK_ROOT / "nuttx/drivers/mtd/dhara"
    littlefs = SDK_ROOT / "nuttx/fs/littlefs/littlefs"
    executable = tmp_path / "dhara_littlefs_reboot"
    subprocess.run([
        *CC, "-std=gnu11", "-g", "-fsanitize=address,undefined",
        "-fno-omit-frame-pointer", "-I" + str(dhara),
        "-I" + str(dhara / "tests"), "-I" + str(littlefs),
        str(PROJECT_ROOT / "tests/fixtures/dhara_littlefs_reboot.c"),
        *[str(dhara / name) for name in (
            "dhara/map.c", "dhara/journal.c", "dhara/error.c",
            "tests/sim.c", "tests/util.c")],
        str(littlefs / "lfs.c"), str(littlefs / "lfs_util.c"),
        "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True, timeout=30)
