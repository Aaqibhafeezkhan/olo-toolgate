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
TAGS = {'beta': 'Beta', 'rc': 'RC', 'stable': 'Stable'}


def configuration(sha):
    document = json.loads(base64.b64decode(prepare.api('contents/version.json?ref='+sha)['content']))
    if set(document) != {'version', 'tag'}:
        raise ValueError('Expected only version and tag in version.json')
    if document['tag'] not in ('dev', 'beta', 'rc', 'stable'):
        raise ValueError('Invalid channel tag in version.json')
    identity.versions(document['version'], 1, 'Beta')
    return document


def initialize(source, commit, channel):
    if source != 'main' and not source.startswith('release/'):
        raise ValueError('Initialize a release line from main or release/*')
    prepare.run('git', 'check-ref-format', 'refs/heads/'+source)
    if channel not in TAGS.values() or (commit != 'latest' and not identity.SHA.fullmatch(commit)):
        raise ValueError('Invalid source commit or release channel')
    head = prepare.api('commits/'+quote(source, safe=''))['sha']
    sha = head if commit == 'latest' else commit
    if sha != head and prepare.api('compare/'+sha+'...'+head)['status'] not in ('ahead', 'identical'):
        raise ValueError('Selected commit must belong to the source branch history')
    config = configuration(sha)
    branch = 'release/'+config['version'].split('.')[0]+'.x'
    if prepare.ref('heads/'+branch):
        raise ValueError('Release line already exists; sync compatible main changes through a PR')
    config['tag'] = identity.CHANNELS[channel] or 'stable'
    tree = prepare.api('git/trees', dict(base_tree=prepare.api('git/commits/'+sha)['tree']['sha'], tree=[
        dict(path='version.json', mode='100644', type='blob', content=json.dumps(config, indent=2)+'\n')]))
    created = prepare.api('git/commits', dict(message='Initialize '+branch+' ('+channel+')', tree=tree['sha'], parents=[sha]))['sha']
    prepare.api('git/refs', dict(ref='refs/heads/'+branch, sha=created))
    return dict(branch=branch, commit=created, sourceCommit=sha, **config)


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
    if config['version'].split('.')[0] != match[1] or config['tag'] not in TAGS:
        raise ValueError('Release line major/channel differs from version.json; start a new line for breaking changes')
    if reuse:
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
    info = prepare.allocate(branch, sha, config['version'], TAGS[config['tag']], release_branch=branch)
    prepare.create_tag(info['preparedTag'], info['sourceCommit'], info)
    return info['sourceCommit'], info


if __name__ == '__main__':
    print(json.dumps(initialize(os.environ.get('SOURCE_BRANCH', 'main'), os.environ.get('SOURCE_COMMIT', 'latest'),
                                os.environ.get('CHANNEL', 'Beta')), indent=2))
