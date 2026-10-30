# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""One release identity, with lossless package and platform version mappings."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
CORE = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
SHA = re.compile(r'[0-9a-f]{40}')
CHANNELS = {'Dev': 'dev', 'Alpha': 'alpha', 'Beta': 'beta', 'RC': 'rc', 'Stable': None}
DEV_LABEL = re.compile(r'dev(?:-[a-z0-9]+(?:-[a-z0-9]+)*)?')


def branch_label(branch):
    """Development tag: dev on main and release/*, dev-branch-<branch> on every other branch."""
    if not branch or branch == 'main' or branch.startswith('release/'):
        return 'dev'
    slug = re.sub(r'[^a-z0-9]+', '-', branch.lower()).strip('-')[:40].strip('-')
    return 'dev-branch-'+slug if slug else 'dev-branch'


def versions(base, build, channel, label=None):
    match = CORE.fullmatch(base)
    if not match or any(int(n) > 65535 for n in match.groups()):
        raise ValueError('Base version must be MAJOR.MINOR.PATCH with components <= 65535')
    if type(build) is not int or not 1 <= build <= 65535 or channel not in CHANNELS:
        raise ValueError('Build must be 1..65535; channel must be Dev, Alpha, Beta, RC or Stable')
    if label is not None and (channel != 'Dev' or not DEV_LABEL.fullmatch(label)):
        raise ValueError('Only development builds take a dev or dev-branch-<branch> label')
    # Stable package coordinates deliberately omit the build: one immutable stable version.
    package = base if channel == 'Stable' else f'{base}-{label or CHANNELS[channel]}.{build}'
    display = package
    return dict(baseVersion=base, build=build, channel=channel, version=package,
                displayVersion=display, numericVersion=f'{base}.{build}',
                macosBuild=f'{build // 10000 + 1}.{build % 10000 // 100}.{build % 100}',
                branch='release/'+display, tag='v'+display,
                preparedTag='prepared/'+display)


def validate(info):
    expected = versions(info['baseVersion'], info['build'], info['channel'])
    line = re.fullmatch(r'release/(0|[1-9][0-9]*)\.x', info.get('branch', ''))
    if line:
        if line[1] != info['baseVersion'].split('.')[0]:
            raise ValueError('Release line must match the major version')
        expected['branch'] = info['branch']
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError('Release identity/version mapping mismatch')
    if info.get('schemaVersion') != 1 or not SHA.fullmatch(info.get('sourceCommit', '')):
        raise ValueError('Invalid release manifest or source commit')
    branch = info.get('sourceBranch', '')
    if branch != 'main' and not branch.startswith('release/'):
        raise ValueError('Release source must be main or release/*')
    return info


def load(root=ROOT):
    return validate(json.loads((root/'release.json').read_text(encoding='utf-8')))


def display(root=ROOT):
    return load(root)['displayVersion'] if (root/'release.json').is_file() else (root/'VERSION').read_text().strip()
