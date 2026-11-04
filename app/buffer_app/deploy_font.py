#!/usr/bin/env python3
"""Stage the SDK's existing MiSans font; never formats or mounts storage."""
import argparse
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('--sdk-root', required=True, type=Path)
parser.add_argument('--data-root', required=True, type=Path)
args = parser.parse_args()
source = args.sdk_root / 'vendor/openvela/boards/vela/resource/font/MiSans-Regular.ttf'
if not source.is_file():
    parser.error('SDK MiSans-Regular.ttf is missing; provide the actual licensed SDK resource')
target = args.data_root / 'fonts' / 'MiSans-Regular.ttf'
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, target)
target.chmod(0o644)
print(target)
# Keep the same licensed family for medium/emphasized roles when available.
for name in ('MiSans-Medium.ttf', 'MiSans-Demibold.ttf', 'MiSans-Bold.ttf'):
    face = source.parent / name
    if face.is_file():
        destination = target.parent / name
        shutil.copyfile(face, destination)
        destination.chmod(0o644)
        print(destination)
