# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Select an immutable development source and stamp a credential-free build overlay."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import identity, prepare

SELECTION = ROOT/'build/manual-development/selection/development-source.json'


def branch_key(branch):
    slug = re.sub('[^a-z0-9-]', '-', branch.lower()).strip('-')[:40] or 'branch'
    return slug+'-'+hashlib.sha256(branch.encode()).hexdigest()[:12]


def select(branch, commit):
    prepare.run('git', 'check-ref-format', 'refs/heads/'+branch)
    if commit != 'latest' and not identity.SHA.fullmatch(commit):
        raise ValueError('Source commit must be latest or a full lowercase commit SHA')
    head = prepare.api('git/ref/heads/'+quote(branch, safe=''))['object']['sha']
    sha = head if commit == 'latest' else commit
    if sha != head and prepare.api('compare/'+sha+'...'+head)['status'] not in ('ahead', 'identical'):
        raise ValueError('Selected commit must belong to the source branch history')
    configured = prepare.api('contents/version.json?ref='+sha, missing=True)
    if configured:
        base = json.loads(base64.b64decode(configured['content']))['version']
    else:
        source_version = base64.b64decode(prepare.api('contents/VERSION?ref='+sha)['content']).decode().strip()
        base = source_version.split('-')[0]
    numeric = identity.versions(base, int(os.environ['GITHUB_RUN_NUMBER']), 'Beta')
    run_id, attempt = os.environ['GITHUB_RUN_ID'], os.environ.get('GITHUB_RUN_ATTEMPT', '1')
    if not all(re.fullmatch('[1-9][0-9]*', value) for value in (run_id, attempt)):
        raise ValueError('Invalid workflow run identity')
    version = f"{base}-dev.{numeric['build']}"
    key = branch_key(branch)
    return dict(schemaVersion=1, sourceBranch=branch, sourceCommit=sha, commit=sha,
                requestedCommit=commit, baseVersion=base, build=numeric['build'],
                channel='Development', version=version, displayVersion=version,
                numericVersion=numeric['numericVersion'], macosBuild=numeric['macosBuild'],
                binaryVersion=version, libraryVersion=version, dockerTag=version,
                releaseTag='v'+version, branchKey=key,
                runId=run_id, runAttempt=attempt, verification='not-run', tests='not-run',
                ciRuns={}, trigger='workflow_dispatch')


def load():
    return json.loads(SELECTION.read_text(encoding='utf-8'))


def verify(info):
    """Compare build claims against the separate, immutable controller selection artifact."""
    expected = load()
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError('Built development metadata differs from the selected source/version')
    # Re-running failed jobs reuses the selection of an earlier attempt of the same run: it publishes that identity
    # again, replacing what the earlier attempt published.
    if expected['runId'] != os.environ['GITHUB_RUN_ID'] or int(os.environ.get('GITHUB_RUN_ATTEMPT', '1')) < int(expected['runAttempt']):
        raise ValueError('Development artifacts belong to another workflow run')
    return expected


def overlay(root, destination, info):
    if prepare.run('git', 'rev-parse', 'HEAD', cwd=root) != info['sourceCommit']:
        raise ValueError('Checkout differs from the selected source commit')
    prepare.stamp_versions(root, info)
    # Old branches also need the controller's development build adapter.
    for name in ('tools/release/manual_development.py', 'tools/release/development_source.py',
                 'tools/release/prepare.py', 'tools/release/identity.py'):
        (root/name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/name, root/name)
    names = prepare.run('git', 'diff', '--name-only', '-z', cwd=root).split('\0')
    names += prepare.run('git', 'ls-files', '--others', '--exclude-standard', '-z', cwd=root).split('\0')
    names += ['release.json']
    names += ['tools/release/'+name+'.py' for name in ('manual_development', 'development_source', 'prepare', 'identity')]
    for name in set(names)-{''}:
        relative = Path(name)
        path = root/relative
        if (relative.is_absolute() or '..' in relative.parts or '.git' in relative.parts
                or path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve())):
            raise ValueError('Development preparation must only update regular source files')
        target = destination/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    # Build metadata is carried separately from release.json for the build adapter.
    prepare.write_json(destination/'development-source.json', info)
    # Render using the selected branch's collection before any build/publish job.
    # Carry the exact bytes in the overlay, including for historical branches.
    from tools.release.notes import generate
    generate(root, destination/'release-notes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('select', 'prepare'))
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    if args.command == 'select':
        info = select(os.environ['SOURCE_BRANCH'], os.environ['SOURCE_COMMIT'])
        SELECTION.parent.mkdir(parents=True, exist_ok=True)
        prepare.write_json(SELECTION, info)
        prepare.emit(info)
    else:
        overlay(args.source.resolve(), ROOT/'build/manual-development/overlay', load())


if __name__ == '__main__':
    main()
