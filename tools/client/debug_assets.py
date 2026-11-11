# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Assemble debug downloads without repackaging legacy Windows clients as Chrome installers."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

from extension import package as package_extension
from installer import build as build_installer, payload
from manifest import manifest
from package import ROOT, TARGETS, verify_binary

GNU_TARGET = 'x86_64-pc-windows-gnu'
NATIVE_WINDOWS = ('x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc')
BUILD_IMAGE = 'olo-toolgate-client-debug-tools:rust-1.94.1'


def archive_name(version, target):
    suffix = 'zip' if 'windows' in target else 'tar.gz'
    return f'olo-toolgate-client-{version}-{target}.{suffix}'


def compatible_windows(source, version):
    targets = []
    for target in NATIVE_WINDOWS:
        archive = source / archive_name(version, target)
        if not archive.exists():
            continue
        files = payload(archive)
        verify_binary(files['olo-toolgate-client.exe'], target)
        if 'olo-toolgate-browser-host.exe' not in files:
            print(f'Ignoring legacy {archive.name}: Chrome native host is missing', flush=True)
            continue
        verify_binary(files['olo-toolgate-browser-host.exe'], target)
        targets.append(target)
    return targets


def build_windows_gnu(source):
    # The debug stack already requires Linux Docker. Both executables come from
    # this working tree; never mix a current host with an older released client.
    relative_source = source.resolve().relative_to(ROOT.resolve()).as_posix()
    registry = ROOT / '.dev/cargo-registry'
    registry.mkdir(parents=True, exist_ok=True)
    subprocess.run(['docker', 'build', '-f', str(ROOT / 'debug/standard/windows-client.Dockerfile'),
                    '-t', BUILD_IMAGE, str(ROOT)], cwd=ROOT, check=True)
    container = ['docker', 'run', '--rm', '-v', f'{ROOT.as_posix()}:/work',
                 '-v', f'{registry.as_posix()}:/usr/local/cargo/registry',
                 '-w', '/work', '-e', 'CARGO_TARGET_DIR=/work/target/client-release', BUILD_IMAGE]
    subprocess.run([*container, 'cargo', 'build', '-p', 'olo-toolgate-client',
                    '--release', '--locked', '--target', GNU_TARGET, '--bins'], cwd=ROOT, check=True)
    subprocess.run([*container, 'python3', 'tools/client/package.py', '--target', GNU_TARGET,
                    '--binary', f'target/client-release/{GNU_TARGET}/release/olo-toolgate-client.exe',
                    '--output', relative_source], cwd=ROOT, check=True)


def copy_asset(source, destination, name):
    shutil.copyfile(source / name, destination / name)
    shutil.copyfile(source / (name + '.sha256'), destination / (name + '.sha256'))


def prepare(source, output):
    source = source.resolve()
    version = (ROOT / 'VERSION').read_text().strip()
    targets = compatible_windows(source, version)
    if NATIVE_WINDOWS[0] not in targets:
        print('Building the current Windows client and Chrome native host with Docker', flush=True)
        build_windows_gnu(source)
        targets.append(GNU_TARGET)
    # A fresh staging directory excludes cached legacy MSVC inputs. In particular,
    # manifest.py must not select an old MSVC ZIP over the current GNU client.
    staging_root = ROOT / '.dev/debug'
    staging_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='client-bundle-', dir=staging_root) as temporary:
        stage = Path(temporary)
        for target in sorted(TARGETS):
            if 'windows' in target:
                continue
            name = archive_name(version, target)
            if not (source / name).exists():
                continue
            copy_asset(source, stage, name)
            suffix = 'dmg' if 'apple' in target else 'run'
            copy_asset(source, stage, f'olo-toolgate-client-{version}-{target}.{suffix}')
        for target in targets:
            copy_asset(source, stage, archive_name(version, target))
            build_installer(target, stage)
        package_extension(stage, 0)
        document = manifest(stage, output)
    for name in ('LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md'):
        shutil.copyfile(ROOT / name, output / name)
    return document


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / '.dev/debug/client-assets')
    parser.add_argument('--output', type=Path, default=ROOT / 'deploy/client-assets/release')
    arguments = parser.parse_args()
    prepare(arguments.source, arguments.output)
