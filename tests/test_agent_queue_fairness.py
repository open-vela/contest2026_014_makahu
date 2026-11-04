"""Compile a small host harness around the production native queue scheduler."""
from host_support import PROJECT_ROOT, SDK_ROOT, CC
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = PROJECT_ROOT
SDK = SDK_ROOT


def test_failed_capture_does_not_starve_later_inputs_and_manual_retry_is_immediate():
    cjson = SDK / "apps/netutils/cjson/cJSON"
    mbedtls = SDK / "apps/crypto/mbedtls/mbedtls"
    with tempfile.TemporaryDirectory(prefix="buffer-agent-fairness-") as temporary:
        work = Path(temporary)
        data = work / "data"
        data.mkdir(mode=0o700)
        include = work / "include"
        (include / "nuttx").mkdir(parents=True)
        (include / "nuttx/config.h").write_text("")
        (include / "netutils").mkdir()
        (include / "netutils/cJSON.h").symlink_to(cjson / "cJSON.h")
        executable = work / "agent-queue-fairness"
        subprocess.run([
            *CC, "-std=c11", "-D_DEFAULT_SOURCE",
            "-D_POSIX_C_SOURCE=200809L", "-ffunction-sections", "-fdata-sections",
            "-pthread", "-I" + str(include), "-I" + str(ROOT / "app/buffer_app"),
            "-I" + str(mbedtls / "include"),
            "-DCONFIG_BUFFER_VELA_DATA_DIR=\"" + str(data) + "\"",
            str(ROOT / "tests/agent_queue_fairness.c"), str(cjson / "cJSON.c"),
            "-Wl,--gc-sections", "-lm", "-o", str(executable),
        ], check=True)
        subprocess.run([str(executable)], check=True)
