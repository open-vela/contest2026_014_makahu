#!/usr/bin/env python3
"""Build the Buffer Goldfish image without changing the SDK's active .config."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def source_view(source: Path, target: Path, excluded: set[str]) -> None:
    if target.is_symlink():
        if target.resolve() != source.resolve():
            raise SystemExit(f'Unexpected scratch source link: {target}')
        target.unlink()
    target.mkdir(parents=True, exist_ok=True)
    for child in source.iterdir():
        link = target / child.name
        if child.name not in excluded and not link.exists() and not link.is_symlink():
            link.symlink_to(child, target_is_directory=child.is_dir())


def prepare(sdk: Path, root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    # Only expose tracked top-level NuttX source entries, not active Make outputs.
    tracked = subprocess.check_output(
        ['git', '-C', str(sdk / 'nuttx'), 'ls-files', '-z']
    ).decode().split('\0')
    kernel = root / 'nuttx'
    kernel.mkdir(exist_ok=True)
    for name in sorted({Path(p).parts[0] for p in tracked if p}):
        if name in {'include', 'Make.defs', '.config', '.config.old', '.version'}:
            continue
        destination = kernel / name
        if not destination.exists() and not destination.is_symlink():
            destination.symlink_to(sdk / 'nuttx' / name)
    headers = kernel / 'include'
    if headers.is_symlink():
        raise SystemExit('Scratch include must be a private directory, not a symlink')
    if not headers.exists():
        shutil.copytree(sdk / 'nuttx/include', headers, symlinks=True)
        # Make builds can leave these generated headers in the SDK source tree.
        # CMake generates replacements under build/include when required.
        for name in ('math.h', 'float.h'):
            stale = headers / name
            if stale.exists() or stale.is_symlink():
                stale.unlink()
    make_defs = kernel / 'Make.defs'
    if make_defs.is_symlink():
        raise SystemExit('Scratch Make.defs must not point into the SDK')
    content = '# CMake external libraries use their generated per-target Make.defs.\n'
    if not make_defs.exists() or make_defs.read_text() != content:
        make_defs.write_text(content)
    for name in ('vendor', 'external', 'prebuilts', 'tools'):
        link = root / name
        if (sdk / name).exists() and not link.exists() and not link.is_symlink():
            link.symlink_to(sdk / name, target_is_directory=True)
    apps = sdk / 'apps'
    source_view(apps, root / 'apps', {'interpreters', 'external', 'builtin', '.git'})
    source_view(apps / 'interpreters', root / 'apps/interpreters', {'quickjs'})
    quickjs = root / 'apps/interpreters/quickjs'
    source_view(apps / 'interpreters/quickjs', quickjs, {'CMakeLists.txt'})
    original = (apps / 'interpreters/quickjs/CMakeLists.txt').read_text()
    old = 'BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/\n'
    install = 'INSTALL_COMMAND "")'
    if old not in original or install not in original:
        raise SystemExit('SDK QuickJS CMake changed; review the host-tool isolation patch')
    patched = original.replace(old, 'BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/host-tool\n')
    patched = patched.replace(
        install,
        'INSTALL_COMMAND ${CMAKE_COMMAND} -E copy '
        '${CMAKE_CURRENT_BINARY_DIR}/host-tool/qjsc ${CMAKE_CURRENT_BINARY_DIR}/qjsc)',
    )
    (quickjs / 'CMakeLists.txt').write_text(patched)

    builtin = root / 'apps/builtin'
    source_view(apps / 'builtin', builtin,
                {'builtin_list.c', 'builtin_list.h', 'builtin_proto.h', 'registry'})
    # Avoid quoted includes selecting stale SDK Make-generated builtin headers.
    (builtin / 'builtin_list.c').write_text((apps / 'builtin/builtin_list.c').read_text())
    source_view(apps / 'external', root / 'apps/external', {'zblue'})
    zblue = root / 'apps/external/zblue'
    source_view(apps / 'external/zblue', zblue, {'CMakeLists.txt'})
    source = (apps / 'external/zblue/CMakeLists.txt').read_text()
    # SDK's classic SDP code still calls a deprecated Zephyr helper. Keep the
    # diagnostic visible, but do not make that third-party warning fatal.
    source += ('\nif(TARGET zblue)\n'
               '  target_compile_options(zblue PRIVATE -Wno-error=deprecated-declarations)\n'
               'endif()\n')
    (zblue / 'CMakeLists.txt').write_text(source)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--root', type=Path, required=True, help='Private scratch directory')
    parser.add_argument('-j', '--jobs', type=int, default=8)
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--board-config', type=Path,
                        help='Alternate simulator defconfig directory, e.g. a local TLS fixture config')
    parser.add_argument('--refresh-config', action='store_true',
                        help='Back up and regenerate ONLY this scratch build Kconfig from the selected defconfig')
    parser.add_argument('--reset-data', action='store_true',
                        help='Regenerate the scratch simulator data disk instead of preserving it')
    args = parser.parse_args()
    sdk, root = args.sdk.resolve(), args.root.resolve()
    if root == sdk or root == sdk / 'nuttx' or root == sdk / 'apps':
        parser.error('--root must be a private scratch directory')
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    prepare(sdk, root)
    if args.prepare_only:
        print(f'Prepared isolated source view: {root}')
        return
    env = os.environ.copy()
    # SDK AIDL matches its Binder headers; Android SDK AIDL may be incompatible.
    tool_dirs = [root / 'host-venv/bin', root / 'host-tools/usr/sbin',
                 root / 'host-tools/usr/bin', sdk / 'prebuilts/tools/linux/x86_64',
                 sdk / 'prebuilts/gcc/linux-x86_64/aarch64-none-elf/bin']
    env['PATH'] = os.pathsep.join(map(str, tool_dirs)) + os.pathsep + env.get('PATH', '')
    missing = [name for name in ('cmake', 'make', 'mkfs.fat', 'mcopy', 'genromfs')
               if shutil.which(name, path=env['PATH']) is None]
    if missing:
        raise SystemExit('Missing host build tools: ' + ', '.join(missing) +
                         '. Install dosfstools/mtools and the SDK host tool dependencies, '
                         'or put private binaries under ROOT/host-tools/usr/{bin,sbin}.')
    interpreter = shutil.which('python3', path=env['PATH'])
    if not interpreter or subprocess.run(
        [interpreter, '-c', 'import elftools, cxxfilt, kconfiglib'], env=env,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode:
        raise SystemExit('Install pyelftools, cxxfilt and kconfiglib in ROOT/host-venv '
                         'or in the active host Python environment.')
    project = Path(__file__).resolve().parents[1]
    board_config = (args.board_config or project / 'board/vela_sim/configs/goldfish-arm64').resolve()
    if not (board_config / 'defconfig').is_file():
        parser.error('--board-config must contain defconfig')
    scratch_config = root / 'build/.config'
    if args.refresh_config and scratch_config.exists():
        fd, name = tempfile.mkstemp(prefix='saved-simulator-config-', suffix='.config', dir=root)
        os.close(fd)
        shutil.copy2(scratch_config, name)
        scratch_config.unlink()
        print(f'Previous scratch Kconfig saved at {name}', flush=True)
    configure = ['cmake', '-S', str(root / 'nuttx'), '-B', str(root / 'build'),
                 f'-DBOARD_CONFIG={board_config}',
                 f'-DNUTTX_APPS_DIR={root / "apps"}', '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON',
                 # LVGL's existing slash-drive compatibility warning is intentional.
                 '-DCMAKE_C_FLAGS=-Wno-error=cpp']
    data_disk = root / 'build/vela_data.bin'
    backup = None
    if data_disk.exists() and not args.reset_data:
        fd, name = tempfile.mkstemp(prefix='saved-simulator-data-', suffix='.bin', dir=root)
        os.close(fd)
        backup = Path(name)
        try:
            shutil.copy2(data_disk, backup)
        except BaseException:
            backup.unlink(missing_ok=True)
            raise
    try:
        subprocess.run(configure, cwd=sdk, env=env, check=True)
        subprocess.run(['cmake', '--build', str(root / 'build'), '--parallel', str(args.jobs)],
                       cwd=sdk, env=env, check=True)
    finally:
        # Upstream gen_images recreates this disk on each build. Keep pairing,
        # fonts and configuration across incremental firmware builds by default.
        if backup is not None:
            os.replace(backup, data_disk)



if __name__ == '__main__':
    main()
