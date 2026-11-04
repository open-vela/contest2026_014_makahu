"""Collect legacy host scripts without running compilers during collection.

Each script runs in its own process. Native pytest tests retain normal pytest
collection, fixtures and reporting; standalone script commands remain usable.
"""
import ast
from pathlib import Path
import subprocess
import sys

import pytest


def group_for(path: Path) -> str:
    name = path.stem.removeprefix("test_")
    if name.startswith("sdk_"):
        return "sdk"
    if name.startswith("fabric_") or name == "coordinator_runtime":
        return "fabric"
    if name.startswith(("oobe_", "enrollment_", "pairing_", "phone_", "ble_")):
        return "onboarding"
    if name.startswith(("focus_", "research_")):
        return "rhythm"
    if name.startswith(("assistant_", "a2ui_")):
        return "assistant"
    if name.startswith(("agent_", "capture_", "buffer_entropy")):
        return "agent"
    return "core"


class ScriptCase(pytest.Item):
    def runtest(self):
        subprocess.run([sys.executable, str(self.path)], check=True, cwd=self.config.rootpath)

    def reportinfo(self):
        return self.path, 0, self.name


class ScriptModule(pytest.File):
    def collect(self):
        yield ScriptCase.from_parent(self, name=self.path.stem.removeprefix("test_"))


def pytest_pycollect_makemodule(module_path, parent):
    tree = ast.parse(module_path.read_text(encoding="utf-8"), filename=str(module_path))
    native = any(
        isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_")
        or isinstance(node, ast.ClassDef) and node.name.startswith("Test")
        for node in tree.body
    )
    if not native:
        return ScriptModule.from_parent(parent, path=module_path)
    return None


def pytest_collection_modifyitems(items):
    for item in items:
        item.add_marker(getattr(pytest.mark, group_for(item.path)))
        if item.path.name == "test_agent_https.py":
            item.add_marker(pytest.mark.integration)
