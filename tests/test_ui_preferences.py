"""Real settings persistence, failed commits and startup/write races."""
from pathlib import Path
import os
import subprocess
from host_support import PROJECT_ROOT, CC


def test_ui_preferences_persistence(tmp_path):
    data = tmp_path / "private"
    exe = tmp_path / "preferences"
    subprocess.run([
        *CC, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-pthread",
        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
        f'-DCONFIG_BUFFER_VELA_DATA_DIR="{data}"',
        "-I" + str(PROJECT_ROOT / "tests/fixtures/ui"),
        "-I" + str(PROJECT_ROOT / "app/buffer_app"),
        str(PROJECT_ROOT / "tests/fixtures/ui/preferences_host.c"),
        str(PROJECT_ROOT / "app/buffer_app/buffer_ui_preferences.c"),
        "-Wl,--wrap=write", "-o", str(exe),
    ], check=True)

    def run(*args):
        subprocess.run([str(exe), *args], check=True, timeout=10)

    run("load", "100", "0", "0")
    run("save")
    settings = data / "ui.preferences"
    assert settings.stat().st_mode & 0o777 == 0o600
    assert data.stat().st_mode & 0o777 == 0o700
    run("load", "150", "1", "1")  # independent process
    run("race")  # edits during a blocked startup read win per field
    run("load", "200", "0", "1")
    run("write-race")  # new edits survive an older blocked commit
    run("load", "175", "1", "0")
    run("fail")  # failed write preserves committed bytes, retry saves latest
    run("load", "200", "1", "0")

    temporary = data / "ui.preferences.tmp"
    temporary.write_bytes(b"interrupted")
    run("load", "200", "1", "0")
    target = tmp_path / "untouched"
    target.write_text("preserve this")
    temporary.unlink()
    temporary.symlink_to(target)
    run("save")
    assert target.read_text() == "preserve this"
    valid = settings.read_bytes()
    for broken in (valid[:-1], valid[:6] + bytes([valid[6] ^ 1]) + valid[7:], b"BUI2" + valid[4:]):
        settings.write_bytes(broken)
        run("error")
        assert settings.read_bytes() == broken  # never overwrite corrupt/future data
    settings.write_bytes(valid)
    os.chmod(settings, 0o644)
    run("error")
    assert settings.read_bytes() == valid
