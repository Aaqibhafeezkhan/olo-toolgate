# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Major/minor release lines with immutable preparation and build identities."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import identity, prepare

LINE = identity.LINE
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


def next_branch(source, config, branches):
    """Select a fresh major from main, or a fresh minor within the source major."""
    major, minor, _ = map(int, config['version'].split('.'))
    lines = [LINE.fullmatch(branch) for branch in branches]
    lines = [match for match in lines if match]
    if source == 'main':
        existing = [int(match[1]) for match in lines]
        major = max(major, max(existing, default=major-1)+1)
        return f'release/{major}.x', f'{major}.0.0'
    minors = [int(match[2]) for match in lines if int(match[1]) == major and match[2] is not None]
    minor = max([minor, *minors])+1
    return f'release/{major}.{minor}.x', f'{major}.{minor}.0'


def apply_preparation(info):
    """Apply only the reserved effect; interrupted runs never select another version."""
    branch, sha = info['branch'], info['commit']
    current = prepare.ref('heads/'+branch)
    if current and current['object']['sha'] == sha:
        return info
    if current:
        comparison = prepare.api('compare/'+sha+'...'+current['object']['sha'])
        if comparison['status'] in ('ahead', 'identical'):
            return info  # Already applied, then advanced through normal development.
    if info['action'] == 'new-branch':
        if current:
            raise ValueError('Target branch was created by another operation; refusing to overwrite it')
        if prepare.api('commits/'+quote(info['sourceBranch'], safe=''))['sha'] != info['sourceHead']:
            raise ValueError('Source branch moved; dispatch a fresh preparation')
        prepare.api('git/refs', dict(ref='refs/heads/'+branch, sha=sha))
        return info
    if not current or current['object']['sha'] != info['sourceCommit']:
        raise ValueError('Patch source branch moved; dispatch a fresh preparation')
    try:
        # A sibling commit from a concurrently advanced branch fails the fast-forward check.
        prepare.api('git/refs/heads/'+quote(branch, safe='/'), dict(sha=sha, force=False), method='PATCH')
        return info
    except subprocess.CalledProcessError as error:
        if not re.search(r'protected branch|GH006|GH013|repository rule', error.stderr or '', re.I):
            raise
    # Respect protection: propose the exact same commit through a reviewable PR.
    proposal = 'release-preparation/'+info['prepareRun']
    existing = prepare.ref('heads/'+proposal)
    if existing is None:
        prepare.api('git/refs', dict(ref='refs/heads/'+proposal, sha=sha))
    elif existing['object']['sha'] != sha:
        raise ValueError('Patch proposal branch differs from the reserved preparation')
    owner = os.environ['GITHUB_REPOSITORY'].split('/')[0]
    pulls = prepare.api('pulls?state=all&head='+quote(owner+':'+proposal, safe='')+'&base='+quote(branch, safe=''))
    if pulls:
        if len(pulls) != 1 or pulls[0]['state'] != 'open':
            raise ValueError('Preparation PR is closed without the reserved commit on the release branch')
        pull = pulls[0]
    else:
        pull = prepare.api('pulls', dict(head=proposal, base=branch, title='Release: prepare '+info['version'],
            body='Increment the patch version on `'+branch+'` to `'+info['version']+'`. '
                 'Merge this PR before building the new version.'))
    return dict(info, pullRequest=pull['html_url'])


def initialize(source, commit, action='new-branch'):
    match = LINE.fullmatch(source)
    if source != 'main' and not match:
        raise ValueError('Initialize from main or release/<major>[.<minor>].x')
    if action not in ('new-branch', 'increment-patch') or (action == 'increment-patch' and source == 'main'):
        raise ValueError('Select new-branch, or increment-patch on a release branch')
    prepare.run('git', 'check-ref-format', 'refs/heads/'+source)
    if commit != 'latest' and not identity.SHA.fullmatch(commit):
        raise ValueError('Invalid source commit')
    run_id = os.environ['GITHUB_RUN_ID']
    if not re.fullmatch(r'[1-9][0-9]*', run_id):
        raise ValueError('Preparation requires a numeric Actions run ID')
    record = 'release-preparations/'+run_id
    previous = prepare.tag(record)
    if previous:
        sha, info = previous
        if info.get('schemaVersion') != 1:
            raise ValueError('Unsupported release preparation record schema')
        if (info['sourceBranch'], info['requestedCommit'], info['action'], info['prepareRun']) != (source, commit, action, run_id):
            raise ValueError('A preparation retry must use the original inputs')
        if sha != info['commit']:
            raise ValueError('Preparation record commit mismatch')
        return apply_preparation(info)
    head = prepare.api('commits/'+quote(source, safe=''))['sha']
    sha = head if commit == 'latest' else commit
    if sha != head and prepare.api('compare/'+sha+'...'+head)['status'] not in ('ahead', 'identical'):
        raise ValueError('Selected commit must belong to the source branch history')
    if action == 'increment-patch' and sha != head:
        raise ValueError('Patch preparation must select the current release branch head')
    config = configuration(sha)
    if config['tag'] != 'dev':
        raise ValueError('version.json must keep tag dev; each release build selects its channel')
    major, minor, patch = map(int, config['version'].split('.'))
    if match and (int(match[1]) != major or (match[2] is not None and int(match[2]) != minor)):
        raise ValueError('Source release line differs from version.json')
    if action == 'new-branch':
        branches = [item['ref'].removeprefix('refs/heads/')
                    for page in prepare.pages('git/matching-refs/heads/release/?per_page=100') for item in page]
        branch, version = next_branch(source, config, branches)
    else:
        branch, version = source, f'{major}.{minor}.{patch+1}'
    identity.versions(version, 1, 'Dev')  # Reject platform version overflow before any writes.
    source_version = config['version']
    config = dict(version=version, tag='dev')
    parent = prepare.api('git/commits/'+sha)
    entries = [dict(path='version.json', mode='100644', type='blob', content=json.dumps(config, indent=2)+'\n')]
    if action == 'new-branch':
        from tools.release.contributors import entries as contributor_entries
        entries.extend(contributor_entries(source, source_version, version, parent['tree']['sha']))
    tree = prepare.api('git/trees', dict(base_tree=parent['tree']['sha'], tree=entries))
    prepared = prepare.api('git/commits', dict(message='Prepare release '+version, tree=tree['sha'], parents=[sha]))['sha']
    info = dict(schemaVersion=1, branch=branch, commit=prepared, sourceCommit=sha, sourceHead=head, sourceBranch=source,
                requestedCommit=commit, action=action, prepareRun=run_id, **config)
    # Store the exact pending effect before changing any branch ref.
    prepare.create_tag(record, prepared, info)
    return apply_preparation(info)


def main():
    info = initialize(os.environ.get('SOURCE_BRANCH', 'main'), os.environ.get('SOURCE_COMMIT', 'latest'),
                      os.environ.get('RELEASE_ACTION', 'new-branch'))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write('## Release preparation\n\n')
            for key in ('action', 'sourceBranch', 'sourceCommit', 'branch', 'version', 'commit', 'pullRequest'):
                if key in info:
                    output.write(f'- {key}: `{info[key]}`\n')
    print(json.dumps(info, indent=2))


def resolve(branch):
    match = LINE.fullmatch(branch)
    sha = os.environ.get('GITHUB_SHA', '')
    reuse = os.environ.get('BUILD_RUN_ID', '').strip()
    if not match or not identity.SHA.fullmatch(sha):
        raise ValueError('Release builds require release/<major>[.<minor>].x and an exact dispatch commit')
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
    if match[2] is not None and config['version'].split('.')[1] != match[2]:
        raise ValueError('Release child line minor differs from version.json')
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
    main()
