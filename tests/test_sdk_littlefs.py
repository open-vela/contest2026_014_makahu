"""Run both SDK LittleFS regressions with the same compiler and RAM flash setup."""
import os
from pathlib import Path
import subprocess

import pytest

from host_support import PROJECT_ROOT, SDK_ROOT, CC


@pytest.mark.parametrize("fixture,flags", [
    pytest.param("littlefs_split_path", [], id="split-directory-path"),
    pytest.param("littlefs_handle_attrs", ["-DLFS_THREADSAFE", "-DLFS_NO_MALLOC", "-pthread"],
                 id="handle-attributes"),
])
def test_littlefs_regression(tmp_path: Path, fixture: str, flags: list[str]):
    littlefs = SDK_ROOT / "nuttx/fs/littlefs/littlefs"
    executable = tmp_path / fixture
    subprocess.run([
        *CC, "-std=c11", "-g",
        "-fsanitize=address,undefined", "-fno-omit-frame-pointer", *flags,
        "-I" + str(littlefs), str(PROJECT_ROOT / "tests/fixtures" / f"{fixture}.c"),
        str(littlefs / "lfs.c"), str(littlefs / "lfs_util.c"), "-o", str(executable),
    ], check=True)
    subprocess.run([str(executable)], check=True, timeout=30)
