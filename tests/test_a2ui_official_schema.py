"""Validate pinned official A2UI schemas and the upstream catalog example."""
from host_support import PROJECT_ROOT

import copy
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker, RefResolver


ROOT = PROJECT_ROOT
SPEC = ROOT / "data/a2ui/spec/v0.9.1"
FIXTURE = ROOT / "tests/fixtures/a2ui_v0_9_1_official_interactive_button.json"
V09_1_CATALOG = (
    "https://a2ui.org/specification/v0_9_1/catalogs/basic/catalog.json"
)


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def check_pinned_hashes():
    for line in (SPEC / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        digest, relative_path = line.split(maxsplit=1)
        path = (SPEC / relative_path.strip()).resolve()
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        assert actual == digest, f"SHA-256 mismatch: {path}"


def check_schema_fixtures():
    server = load_json(SPEC / "server_to_client.json")
    common = load_json(SPEC / "common_types.json")
    catalog = load_json(SPEC / "catalog.json")
    client = load_json(SPEC / "client_to_server.json")
    for schema in (server, common, catalog, client):
        Draft202012Validator.check_schema(schema)

    # The official envelope schema uses a relative catalog.json placeholder;
    # the official basic catalog, in turn, references common types at the v0.9
    # URI. Resolve both to the pinned v0.9.1 copies without editing source.
    store = {
        "https://a2ui.org/specification/v0_9_1/catalog.json": catalog,
        "https://a2ui.org/specification/v0_9/catalog.json": catalog,
        common["$id"]: common,
    }
    resolver = RefResolver.from_schema(server, store=store)
    server_validator = Draft202012Validator(
        server, resolver=resolver, format_checker=FormatChecker()
    )

    official = load_json(FIXTURE)
    assert official["name"] == "Interactive Button"
    assert len(official["messages"]) == 2
    for message in official["messages"]:
        server_validator.validate(message)

    # Also validate the exact v0.9.1 identifiers consumed by this firmware.
    native_messages = copy.deepcopy(official["messages"])
    for message in native_messages:
        message["version"] = "v0.9.1"
        if "createSurface" in message:
            message["createSurface"]["catalogId"] = V09_1_CATALOG
        server_validator.validate(message)

    action = {
        "version": "v0.9.1",
        "action": {
            "name": "button_clicked",
            "surfaceId": "gallery-interactive-button",
            "sourceComponentId": "action_button",
            "timestamp": "2026-10-17T00:00:00Z",
            "context": {},
        },
    }
    Draft202012Validator(
        client, format_checker=FormatChecker()
    ).validate(action)

    unsupported = copy.deepcopy(native_messages[1])
    unsupported["updateComponents"]["components"][0]["component"] = "UnknownWidget"
    assert list(server_validator.iter_errors(unsupported)), (
        "the official basic catalog must reject an unknown component"
    )


if __name__ == "__main__":
    check_pinned_hashes()
    check_schema_fixtures()
    print("PASS: pinned official A2UI v0.9.1 schemas and upstream sample")
