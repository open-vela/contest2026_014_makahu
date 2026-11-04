"""Compatibility entry point: regenerate the entire pinned Rounded icon set."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).with_name("generate_symbols.py")))
