"""Shared paths and source extraction for native production-code harnesses."""
import os
from pathlib import Path
import re
import shlex

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SDK_ROOT = Path(os.environ.get("OPENVELA_SDK", PROJECT_ROOT.parent)).expanduser().resolve()

CC = shlex.split(os.environ.get("CC", "cc"))


def extract_c(source: str, signature: str, *, last: bool = False,
              semicolon: bool = False) -> str:
    """Extract a function/struct, ignoring braces in comments and literals."""
    start = source.rindex(signature) if last else source.index(signature)
    opening = source.index("{", start)
    # A forward declaration is not a definition. Skip its semicolon rather
    # than accidentally extracting the next unrelated function's body.
    if "(" in signature:
        while ";" in re.sub(r"/\*.*?\*/|//[^\n]*", "", source[start:opening], flags=re.DOTALL):
            start = source.index(signature, start + len(signature))
            opening = source.index("{", start)
    tokens = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', re.DOTALL)
    depth = 0
    for match in tokens.finditer(source, opening):
        if match.group() == "{":
            depth += 1
        elif match.group() == "}":
            depth -= 1
            if depth == 0:
                end = match.end()
                if semicolon and source[end:end + 1] == ";":
                    end += 1
                return source[start:end]
    raise ValueError(f"Unterminated C body: {signature}")


def focus_legacy_helpers() -> str:
    """Actual focus snapshot helpers and disabled journal persistence bodies."""
    source = (PROJECT_ROOT / "app/buffer_app/buffer_app_main.c").read_text()
    bodies = [extract_c(source, "struct focus_reminder_state_s", semicolon=True),
              "static bool focus_runtime_checkpoint=true;"]
    for signature in ("static void focus_reminder_snapshot(",
                      "static void focus_reminder_apply(",
                      "static int focus_runtime_restore_locked(",
                      "static int focus_runtime_save_locked("):
        bodies.append(extract_c(source, signature, last=True))
    return "\n".join(bodies) + "\n"


def fabric_link_declarations() -> str:
    """Production per-connection state with opaque SDK handles at the host boundary."""
    source = (PROJECT_ROOT / "app/buffer_app/buffer_fabric.c").read_text()
    bodies = ["""
#ifndef BUFFER_MODE_SIZE
#define BUFFER_MODE_SIZE 24
#endif
#ifndef BUFFER_ACTION_ID_SIZE
#define BUFFER_ACTION_ID_SIZE 80
#endif
#define FABRIC_LINKS 4
typedef int bt_address_t;
typedef void fabric_ble_host;
typedef void fabric_pairing_host;
"""]
    for signature in ("struct buffer_fabric_sync_state_s", "struct buffer_fabric_mode_state_s",
                      "struct buffer_fabric_runtime_state_s", "struct packet {", "struct fabric_link_s {"):
        bodies.append(extract_c(source, signature, semicolon=True))
    bodies.append("static struct fabric_link_s links[FABRIC_LINKS];")
    return "\n".join(bodies) + "\n"
