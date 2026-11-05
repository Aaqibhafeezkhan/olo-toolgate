# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicitly install the repository's pinned Rust toolchain and components."""
import os
from pathlib import Path
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]


def owned_ci_home():
    """Allow repair only inside the exact disposable directory used by CI."""
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        return False
    temporary = os.environ.get('RUNNER_TEMP')
    home = os.environ.get('RUSTUP_HOME')
    if not temporary or not home:
        return False
    expected = Path(temporary).resolve() / 'toolgate-rustup'
    return Path(home).is_absolute() and Path(home).resolve() == expected


def install(command):
    try:
        result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as error:
        sys.stdout.write(error.stdout or '')
        sys.stderr.write(error.stderr or '')
        raise
    sys.stdout.write(result.stdout or '')
    sys.stderr.write(result.stderr or '')


def main():
    with (ROOT/'rust-toolchain.toml').open('rb') as source:
        toolchain = tomllib.load(source)['toolchain']
    command = ['rustup', 'toolchain', 'install', toolchain['channel'],
               '--profile', toolchain['profile'], '--no-self-update']
    for component in toolchain.get('components', []):
        command.extend(['--component', component])
    for target in toolchain.get('targets', []):
        command.extend(['--target', target])
    try:
        install(command)
    except subprocess.CalledProcessError as error:
        detail = (error.stdout or '') + (error.stderr or '')
        if not (owned_ci_home()
                and 'recovering from a partially installed toolchain' in detail
                and 'failed to install component:' in detail
                and 'detected conflict:' in detail):
            raise
        # One reinstall of the pinned toolchain in disposable CI state only.
        # Never delete developer toolchains, retry unrelated errors or fall back.
        subprocess.run(['rustup', 'toolchain', 'uninstall', toolchain['channel']],
                       cwd=ROOT, check=True)
        install(command)
    subprocess.run(['rustup', 'show', 'active-toolchain'], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
