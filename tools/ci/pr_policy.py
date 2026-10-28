# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Enforce human attribution and release-only generated notes on pull requests."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release.notes import load


def check_paths(paths):
    for path in paths:
        if Path(path).name == 'RELEASE-NOTES.md':
            raise ValueError('Generated release notes change only during release, not in a PR: '+path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base')
    parser.add_argument('--head')
    parser.add_argument('--staged', action='store_true')
    args = parser.parse_args()
    if args.staged:
        if args.base or args.head:
            parser.error('--staged cannot accompany --base/--head')
        # Compare the staged PR as a whole, so removing an earlier accidental
        # generated-note edit is allowed rather than blocking its correction.
        base = subprocess.run(['git', 'merge-base', 'HEAD', 'refs/remotes/origin/main'],
                              cwd=ROOT, capture_output=True, text=True)
        diff = ['--cached', base.stdout.strip() if base.returncode == 0 else 'HEAD']
    else:
        if not all(value and re.fullmatch(r'[0-9a-f]{40}', value) for value in (args.base, args.head)):
            parser.error('--base and --head must be full commit SHAs')
        diff = [args.base+'...'+args.head]
    paths = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', *diff], cwd=ROOT)
    check_paths([path for path in paths.decode('utf-8').split('\0') if path])
    load(ROOT)
    for folder in (ROOT/'.changes').iterdir():
        if folder.is_dir() and folder.name.startswith('v'):
            load(ROOT, folder.name)
    print('PR policy passed: human attribution and release-only generated notes')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        raise SystemExit(str(error))
