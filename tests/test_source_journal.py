"""Run the production native journal on private host storage, with its real indexes.

The shared native fixture uses host POSIX persistence, real crypto and cJSON.
This verifies journal semantics, not the S1 LittleFS driver or radio transport.
"""
import os
from pathlib import Path
import subprocess
import sys

subprocess.run([sys.executable,str(Path(__file__).with_name("test_agent_https.py"))],
               env=dict(os.environ,BUFFER_JOURNAL_CONTRACT="1"),check=True)
