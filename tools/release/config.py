# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Materialize package metadata from the branch's single version configuration."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import identity, prepare

TAGS = {'dev': 'Dev', 'alpha': 'Alpha', 'beta': 'Beta', 'rc': 'RC', 'stable': 'Stable'}


def load(root=ROOT):
    document = json.loads((root/'version.json').read_text(encoding='utf-8'))
    if set(document) != {'version', 'tag'} or document['tag'] not in TAGS:
        raise ValueError('version.json must contain only version and tag (dev, alpha, beta, rc, stable)')
    identity.versions(document['version'], 1, TAGS[document['tag']])
    return document


def coordinates(config, build):
    return identity.versions(config['version'], build, TAGS[config['tag']])


def materialize(root=ROOT, build=None, selection=None):
    if not (root/'version.json').is_file():
        return  # Historical sources retain their existing metadata.
    config = load(root)
    existing = root/'release.json'
    if existing.is_file() and json.loads(existing.read_text()).get('channel') == 'Development':
        return  # An explicitly selected development overlay already has its immutable identity.
    ref = os.environ.get('GITHUB_REF_NAME', '')
    major_line = re.fullmatch(r'release/(0|[1-9][0-9]*)\.x', ref)
    if major_line and major_line[1] != config['version'].split('.')[0]:
        raise ValueError('Release line major/channel differs from version.json')
    match = re.fullmatch(r'(?:release/|v)'+re.escape(config['version'])+r'-(dev|alpha|beta|rc)\.([1-9][0-9]*)', ref)
    if match and match[1] != config['tag']:
        raise ValueError('Branch or tag channel differs from version.json')
    number = build if build is not None else (int(match[2]) if match else int(os.environ.get('GITHUB_RUN_NUMBER', '1')))
    info = dict(schemaVersion=1, **coordinates(config, number),
                sourceBranch='main', sourceCommit=prepare.run('git', 'rev-parse', 'HEAD', cwd=root))
    if major_line:
        info.update(branch=ref, sourceBranch=ref)
    if selection:
        selected = json.loads(selection.read_text())
        # Release-line builds take their channel from the release job input, not version.json.
        if selected['baseVersion'] != config['version']:
            raise ValueError('Selected release differs from version.json')
        info = {key: value for key, value in selected.items() if key not in ('commit', 'buildRun')}
    elif os.environ.get('GITHUB_WORKFLOW') == 'Build and release binaries':
        prepared_sha, info = prepare.resolve(ref)
        if prepared_sha != prepare.run('git', 'rev-parse', 'HEAD', cwd=root):
            raise ValueError('Build checkout differs from the prepared release commit')
    prepare.stamp_versions(root, info)
    print('Build version: '+info['version'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--build', type=int)
    parser.add_argument('--selection', type=Path)
    args = parser.parse_args()
    materialize(args.root.resolve(), args.build, args.selection)


if __name__ == '__main__':
    main()
