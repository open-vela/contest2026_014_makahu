#!/usr/bin/env python3
"""Stage the pinned, unmodified MiSans faces in an isolated S1 build only."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True, help='Isolated writable SDK view')
    parser.add_argument('--check', action='store_true', help='Verify existing staged files without writing')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    sdk = args.sdk.resolve()
    if sdk == project.parent or sdk == project or not (sdk / 'nuttx/.config').is_file():
        parser.error('Use an isolated SDK build; the canonical SDK source is read-only')
    fonts = project / 'app/buffer_app/assets/fonts'
    manifest = json.loads((fonts / 'sources.json').read_text())
    assets = {face['file']: face for face in manifest['faces']}
    target = sdk / 'vendor/allwinnertech/lichee/board/common/data/res/fonts'
    if not target.resolve().is_relative_to(sdk):
        parser.error('Resource directory escapes the isolated SDK')
    prepared = []
    for destination, source in manifest['resource_mapping'].items():
        if Path(destination).name != destination or Path(source).name != source:
            parser.error('Font mapping must contain plain basenames')
        path = fonts / source
        expected = assets[source]
        if path.is_symlink() or digest(path) != expected['sha256']:
            raise SystemExit('Font source hash mismatch: ' + source)
        output = target / destination
        if output.is_symlink():
            raise SystemExit('Refusing a symlink font destination: ' + str(output))
        prepared.append((path, output, expected))
    for source, output, expected in prepared:
        matches = output.is_file() and digest(output) == expected['sha256']
        if args.check and not matches:
            raise SystemExit('Staged font mismatch: ' + str(output))
        if not args.check and not matches:
            target.mkdir(parents=True, exist_ok=True)
            temporary = output.with_name(output.name + '.buffer-tmp')
            with temporary.open('xb') as staged, source.open('rb') as original:
                shutil.copyfileobj(original, staged)
                staged.flush()
                os.fsync(staged.fileno())
            os.replace(temporary, output)
        if digest(output) != expected['sha256']:
            raise SystemExit('Staged font hash mismatch: ' + str(output))
    print(json.dumps({'resource_directory': str(target), 'font_files': len(prepared),
                      'verified': True, 'read_only_check': args.check}, indent=2))


if __name__ == '__main__':
    main()
