"""Verify actual Android <-> native journal originals and native WAV delivery.

Run with JAVA_HOME (JDK 21) and ANDROID_HOME set. No emulator/radio is used.
The native side is compiled from production C with the journal enabled; Android
uses the production repository and SQLite under Robolectric.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=project / ".validation" / "interop")
    output = parser.parse_args().output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    gradle = ["bash", str(project / "foundation.fabric/apps/android/gradlew"),
              "-p", str(project / "foundation.fabric/apps/android"),
              "-I", str(project / "tests/interop/android.init.gradle"),
              "--max-workers=4", f"-PbufferSourceInterop={output}",
              ":buffer-app:testDebugUnitTest", "--tests", "*SourceJournalInteropTest"]

    def run(name, command, env=None):
        log = output / f"{name}.log"
        with log.open("w") as stream:
            result = subprocess.run(command, cwd=project, env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            print(log.read_text()[-10000:], file=sys.stderr)
            raise SystemExit(result.returncode)
        print(f"PASS {name}: {log}", flush=True)

    run("android-export", gradle)
    run("native-receive-export", [sys.executable, str(project / "tests/test_source_journal.py")],
        dict(os.environ, BUFFER_JOURNAL_INTEROP_DIR=str(output)))
    run("android-native-receive", gradle + ["-PbufferSourceInteropRequireNative=true"])
    native = json.loads((output / "native-received.json").read_text())
    android = json.loads((output / "android-received.json").read_text())
    assert native["android_events"] > 0 and android["native_events"] > 0
    assert native["native_head_before"] == native["native_head_after"]
    assert android["native_facts_before"] == android["native_facts_after"]
    assert len(android["blob_hash"]) == 64
    (output / "result.json").write_text(json.dumps(dict(native=native, android=android), indent=2))
    print("PASS cross-runtime exact originals, duplicate delivery, independent audio receipt and no historical execution", flush=True)


if __name__ == "__main__":
    main()
