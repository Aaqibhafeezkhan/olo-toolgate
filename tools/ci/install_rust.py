# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicitly install the repository's pinned Rust toolchain and components."""
from pathlib import Path
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[2]


def main():
    with (ROOT/'rust-toolchain.toml').open('rb') as source:
        toolchain = tomllib.load(source)['toolchain']
    command = ['rustup', 'toolchain', 'install', toolchain['channel'],
               '--profile', toolchain['profile'], '--no-self-update']
    for component in toolchain.get('components', []):
        command.extend(['--component', component])
    for target in toolchain.get('targets', []):
        command.extend(['--target', target])
    subprocess.run(command, cwd=ROOT, check=True)
    subprocess.run(['rustup', 'show', 'active-toolchain'], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
