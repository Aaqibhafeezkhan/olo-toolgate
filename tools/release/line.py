# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Long-lived major release lines with immutable, per-build release identities."""
import base64
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import identity, prepare

LINE = re.compile(r'release/(0|[1-9][0-9]*)\.x')
# Release job choices; `release` is the stable version without a channel or build suffix.
CHANNELS = {'dev': 'Dev', 'alpha': 'Alpha', 'beta': 'Beta', 'rc': 'RC', 'release': 'Stable'}


def requested_channel():
    # Reusable build workflows share the dispatch event, so they see the same input.
    value = os.environ.get('CHANNEL', '').strip()
    if not value and os.environ.get('GITHUB_EVENT_PATH'):
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        value = str((event.get('inputs') or {}).get('channel') or '').strip()
    value = value or 'dev'
    if value not in CHANNELS:
        raise ValueError('Release channel must be dev, alpha, beta, rc or release')
    return CHANNELS[value]


def configuration(sha):
    document = json.loads(base64.b64decode(prepare.api('contents/version.json?ref='+sha)['content']))
    if set(document) != {'version', 'tag'}:
        raise ValueError('Expected only version and tag in version.json')
    if document['tag'] not in ('dev', 'alpha', 'beta', 'rc', 'stable'):
        raise ValueError('Invalid channel tag in version.json')
    identity.versions(document['version'], 1, 'Beta')
    return document


def initialize(source, commit):
    if source != 'main' and not source.startswith('release/'):
        raise ValueError('Initialize a release line from main or release/*')
    prepare.run('git', 'check-ref-format', 'refs/heads/'+source)
    if commit != 'latest' and not identity.SHA.fullmatch(commit):
        raise ValueError('Invalid source commit')
    head = prepare.api('commits/'+quote(source, safe=''))['sha']
    sha = head if commit == 'latest' else commit
    if sha != head and prepare.api('compare/'+sha+'...'+head)['status'] not in ('ahead', 'identical'):
        raise ValueError('Selected commit must belong to the source branch history')
    config = configuration(sha)
    if config['tag'] != 'dev':
        raise ValueError('version.json must keep tag dev; each release build selects its channel')
    branch = 'release/'+config['version'].split('.')[0]+'.x'
    if prepare.ref('heads/'+branch):
        raise ValueError('Release line already exists; sync compatible main changes through a PR')
    # The line starts at the exact source commit; version.json stays on dev.
    prepare.api('git/refs', dict(ref='refs/heads/'+branch, sha=sha))
    return dict(branch=branch, commit=sha, sourceCommit=sha, **config)


def resolve(branch):
    match = LINE.fullmatch(branch)
    sha = os.environ.get('GITHUB_SHA', '')
    reuse = os.environ.get('BUILD_RUN_ID', '').strip()
    if not match or not identity.SHA.fullmatch(sha):
        raise ValueError('Release builds require release/<major>.x and an exact dispatch commit')
    if os.environ.get('GITHUB_REF') != 'refs/heads/'+branch:
        raise ValueError('Release workflow must run within its release line')
    if reuse:
        if not re.fullmatch(r'[1-9][0-9]*', reuse):
            raise ValueError('Build run ID must be numeric')
        producer = prepare.api('actions/runs/'+reuse)
        if (producer.get('head_branch') != branch or producer.get('path') != '.github/workflows/build-release.yml'
                or producer.get('event') != 'workflow_dispatch'
                or producer.get('repository', {}).get('full_name') != os.environ['GITHUB_REPOSITORY']):
            raise ValueError('Reuse must identify an original release build from this line')
        sha = producer['head_sha']
        if not identity.SHA.fullmatch(sha):
            raise ValueError('Original release build has an invalid source commit')
    config = configuration(sha)
    if config['version'].split('.')[0] != match[1]:
        raise ValueError('Release line major differs from version.json; start a new line for breaking changes')
    if reuse:
        # A reused bundle keeps its original channel; the channel input does not apply.
        prefix = 'git/matching-refs/tags/release-builds/'+config['version']+'/?per_page=100'
        for page in prepare.pages(prefix):
            for item in page:
                info = json.loads(prepare.api('git/tags/'+item['object']['sha'])['message'])
                if info.get('prepareRun') == reuse:
                    identity.validate(info)
                    if (info['branch'] != branch or info['sourceCommit'] != sha
                            or prepare.tag(info['preparedTag']) != (sha, info)):
                        raise ValueError('Original build differs from its immutable preparation record')
                    return sha, info
        raise ValueError('Original release build reservation was not found')
    info = prepare.allocate(branch, sha, config['version'], requested_channel(), release_branch=branch)
    prepare.create_tag(info['preparedTag'], info['sourceCommit'], info)
    return info['sourceCommit'], info


if __name__ == '__main__':
    print(json.dumps(initialize(os.environ.get('SOURCE_BRANCH', 'main'), os.environ.get('SOURCE_COMMIT', 'latest')), indent=2))
