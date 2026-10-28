# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Allocate and prepare immutable releases using GitHub Git objects, never force pushes."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from tools.release import identity


def run(*args, cwd=ROOT, input=None):
    try:
        return subprocess.run(args, cwd=cwd, input=input, text=True, capture_output=True, check=True).stdout.strip()
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise


def api(path, data=None, missing=False):
    args = ['gh', 'api', 'repos/'+os.environ['GITHUB_REPOSITORY']+'/'+path]
    if data is not None:
        args += ['--method', 'POST', '--input', '-']
    try:
        return json.loads(run(*args, input=json.dumps(data) if data is not None else None))
    except subprocess.CalledProcessError as error:
        if missing and re.search(r'\(HTTP 404\)', error.stderr or ''):
            return None
        raise


def pages(path):
    return json.loads(run('gh', 'api', 'repos/'+os.environ['GITHUB_REPOSITORY']+'/'+path,
                          '--paginate', '--slurp'))


def ref(name):
    return api('git/ref/'+quote(name, safe='/'), missing=True)


def tag(name):
    value = ref('tags/'+name)
    if value is None:
        return None
    obj = value['object']
    if obj['type'] != 'tag':
        raise ValueError('Release bookkeeping tags must be annotated')
    result = api('git/tags/'+obj['sha'])
    if result['object']['type'] != 'commit' or not identity.SHA.fullmatch(result['object']['sha']):
        raise ValueError('Release bookkeeping tag must identify a commit')
    return result['object']['sha'], json.loads(result['message'])


def create_tag(name, sha, message):
    existing = tag(name)
    if existing is not None:
        if existing != (sha, message):
            raise ValueError('Immutable release tag identity conflict: '+name)
        return
    obj = api('git/tags', dict(tag=name, message=json.dumps(message, sort_keys=True), object=sha, type='commit'))
    api('git/refs', dict(ref='refs/tags/'+name, sha=obj['sha']))


def emit(info, **extra):
    values = {**info, **extra}
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
            for key, value in values.items():
                output.write(f'{key}={value}\n')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
            output.write('Release: `'+info['displayVersion']+'`\n\n')
            for key in ('branch', 'version', 'sourceBranch', 'sourceCommit', 'commit', 'buildRun'):
                if key in values:
                    output.write(f'- {key}: `{values[key]}`\n')
    print(json.dumps(values, indent=2))


def allocate(branch, commit, base, channel):
    identity.versions(base, 1, channel)  # Validate before contacting GitHub.
    if branch != 'main' and not branch.startswith('release/'):
        raise ValueError('Select main or release/* as the source')
    run('git', 'check-ref-format', 'refs/heads/'+branch)
    if commit != 'latest' and not identity.SHA.fullmatch(commit):
        raise ValueError('Source commit must be latest or a full lowercase commit SHA')
    run_id = os.environ['GITHUB_RUN_ID']
    prefix = 'release-builds/'+base+'/'
    reservations = [item for page in pages('git/matching-refs/tags/'+prefix+'?per_page=100') for item in page]
    highest = 0
    for item in reservations:
        number = item['ref'].removeprefix('refs/tags/'+prefix)
        if not number.isdigit():
            raise ValueError('Invalid release counter reservation')
        highest = max(highest, int(number))
        previous = api('git/tags/'+item['object']['sha'])
        record = json.loads(previous['message'])
        if record.get('prepareRun') == run_id:
            identity.validate(record)
            if (record['sourceBranch'], record['requestedCommit'], record['baseVersion'], record['channel']) != (branch, commit, base, channel):
                raise ValueError('A preparation retry must use the original inputs')
            return record
    head = api('commits/'+quote(branch, safe=''))['sha']
    sha = head if commit == 'latest' else commit
    if sha != head:
        comparison = api('compare/'+sha+'...'+head)
        if comparison['status'] not in ('ahead', 'identical'):
            raise ValueError('Selected commit is not an ancestor of the source branch')
    # Branches/tags/releases may predate the reservation mechanism.
    reserved = [item['ref'] for kind in ('heads/release/', 'tags/v', 'tags/prepared/')
                for page in pages('git/matching-refs/'+kind+'?per_page=100') for item in page]
    releases = [item['tag_name'] for page in pages('releases?per_page=100') for item in page]
    for value in reserved+releases:
        match = re.search(re.escape(base)+r'-(?:beta|rc)\.([0-9]+)$', value)
        if match:
            highest = max(highest, int(match[1]))
    info = dict(schemaVersion=1, **identity.versions(base, highest+1, channel),
                sourceBranch=branch, sourceCommit=sha, requestedCommit=commit, prepareRun=run_id)
    if channel == 'Stable' and any(ref(name) for name in ('tags/v'+base, 'tags/prepared/'+base, 'heads/release/'+base)):
        raise ValueError('Stable package version is already reserved; choose a new base version')
    create_tag(prefix+str(info['build']), sha, info)
    return info


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8', newline='\n')


def stamp(root, info):
    """Run source-compatible generators without registry credentials or network dependency updates."""
    identity.validate(info)
    if (root/'version.json').is_file():
        write_json(root/'version.json', dict(version=info['baseVersion'], tag=identity.CHANNELS[info['channel']] or 'stable'))
    else:
        stamp_versions(root, info)


def stamp_versions(root, info):
    """Apply an already validated product or development identity to every package."""
    # Older main/release commits may predate these standalone packaging adapters.
    # Carry the controller's adapters into the prepared commit so its exact tree
    # remains buildable, including the same release number on every platform.
    for name in ('tools/release/artifacts.py', 'tools/client/extension.py', 'tools/client/installer.py',
                 'tools/client/package.py', 'apps/endpoint-client/packaging/windows-setup.iss'):
        destination=root/name
        destination.parent.mkdir(parents=True,exist_ok=True)
        if (ROOT/name).resolve() != destination.resolve():
            shutil.copyfile(ROOT/name,destination)
    old = (root/'VERSION').read_text().strip()
    mapping = root/'config/initial/releases.json'
    configurations = json.loads(mapping.read_text())
    if old not in configurations['releases']:
        raise ValueError('Source product version has no initial configuration bundle')
    configurations['releases'][info['version']] = configurations['releases'][old]
    write_json(mapping, configurations)
    write_json(root/'release.json', info)
    native_main = root/'apps/endpoint-client/src/main.rs'
    if native_main.is_file():
        native_code = native_main.read_text()
        if 'fn build_label() -> String {' in native_code and 'option_env!("TOOLGATE_RELEASE_BUILD")' not in native_code:
            native_code = native_code.replace('fn build_label() -> String {',
                'fn build_label() -> String {\n'
                '    if let Some(build) = option_env!("TOOLGATE_RELEASE_BUILD").filter(|value| !value.is_empty()) {\n'
                '        return build.into();\n'
                '    }', 1)
            native_main.write_text(native_code, encoding='utf-8', newline='\n')
    gradle = root/'build.gradle.kts'
    script = gradle.read_text()
    if 'manifest.attributes["Implementation-Version"]' not in script:
        marker = '        tasks.withType<Test>().configureEach {'
        if marker not in script:
            raise ValueError('Source Gradle build does not support release manifest version stamping')
        if 'import org.gradle.jvm.tasks.Jar' not in script:
            script = 'import org.gradle.jvm.tasks.Jar\n'+script
        script = script.replace(marker,
            '        tasks.withType<Jar>().configureEach {\n'
            '            manifest.attributes["Implementation-Version"] = project.version.toString()\n'
            '        }\n\n'+marker, 1)
        gradle.write_text(script, encoding='utf-8', newline='\n')
    run(sys.executable, 'tools/contracts/version.py', '--set', info['version'], cwd=root)
    # Release preparation is the only stage that refreshes tracked note snapshots;
    # generate.py then embeds the same current notes in every SDK distribution.
    from tools.contracts.version import release_notes
    (root/'RELEASE-NOTES.md').write_text(release_notes(root), encoding='utf-8', newline='\n')
    run(sys.executable, 'tools/contracts/generate.py', cwd=root)
    lock = root/'package-lock.json'
    document = json.loads(lock.read_text())
    document['version'] = info['version']
    for name in ('', 'apps/admin-ui', 'packages/contracts/typescript'):
        package = document['packages'][name]
        package['version'] = info['version']
        if name == 'apps/admin-ui':
            package['dependencies']['@olo-labs/toolgate-contracts'] = info['version']
    write_json(lock, document)
    # Update only workspace versions in Cargo.lock; external pins/checksums remain untouched.
    cargo = root/'Cargo.lock'
    workspace = {'olo-toolgate-contracts', 'olo-toolgate-gateway', 'olo-toolgate-client',
                 'olo-toolgate-tenant-identity', 'olo-toolgate-tool-host', 'toolgate-cli'}
    blocks = cargo.read_text().split('[[package]]')
    for i, block in enumerate(blocks[1:], 1):
        name = re.search(r'(?m)^name = "([^"]+)"$', block)
        if name and name[1] in workspace and not re.search(r'(?m)^source = ', block):
            blocks[i] = re.sub(r'(?m)^version = "[^"]+"$', 'version = "'+info['version']+'"', block, count=1)
    cargo.write_text('[[package]]'.join(blocks), encoding='utf-8', newline='\n')
    # Numeric store/platform fields and human-visible labels are derived, never independently entered.
    extension = root/'apps/browser-extension/manifest.json'
    doc = json.loads(extension.read_text())
    doc.update(version=info['numericVersion'], version_name=info['displayVersion'])
    write_json(extension, doc)
    run(sys.executable, 'tools/contracts/version.py', '--check', cwd=root)
    run(sys.executable, 'tools/contracts/generate.py', '--check', cwd=root)


def commit_branch(root, info):
    if run('git', 'rev-parse', 'HEAD', cwd=root) != info['sourceCommit']:
        raise ValueError('Source checkout differs from reserved commit')
    run('git', 'add', '--all', cwd=root)
    expected_tree = run('git', 'write-tree', cwd=root)
    branch = ref('heads/'+info['branch'])
    if branch:
        sha = branch['object']['sha']
        metadata_path = 'version.json' if (root/'version.json').is_file() else 'release.json'
        existing = json.loads(base64.b64decode(api('contents/'+metadata_path+'?ref='+sha)['content']))
        expected = dict(version=info['baseVersion'], tag=identity.CHANNELS[info['channel']] or 'stable') if metadata_path == 'version.json' else info
        if existing != expected:
            raise ValueError('Existing release branch has different preparation metadata')
        commit = api('git/commits/'+sha)
        if commit['tree']['sha'] != expected_tree or [parent['sha'] for parent in commit['parents']] != [info['sourceCommit']]:
            raise ValueError('Existing release branch differs from the exact prepared source tree')
    else:
        names = run('git', 'diff', '--cached', '--name-only', cwd=root).splitlines()
        entries = []
        for name in names:
            path = root/name
            if path.is_symlink() or not path.is_file():
                raise ValueError('Release preparation must only update regular files')
            blob = api('git/blobs', dict(content=base64.b64encode(path.read_bytes()).decode(), encoding='base64'))
            mode = run('git', 'ls-files', '-s', '--', name, cwd=root).split()[0]
            entries.append(dict(path=name, mode=mode, type='blob', sha=blob['sha']))
        tree = api('git/trees', dict(base_tree=api('git/commits/'+info['sourceCommit'])['tree']['sha'], tree=entries))
        if tree['sha'] != expected_tree:
            raise ValueError('Remote prepared tree differs from the validated source tree')
        sha = api('git/commits', dict(message='Prepare release '+info['displayVersion'], tree=tree['sha'], parents=[info['sourceCommit']]))['sha']
        api('git/refs', dict(ref='refs/heads/'+info['branch'], sha=sha))
    create_tag(info['preparedTag'], sha, info)
    return sha


def resolve(branch):
    if not branch.startswith('release/'):
        raise ValueError('Select a prepared release/* branch')
    run('git', 'check-ref-format', 'refs/heads/'+branch)
    value = tag('prepared/'+branch.removeprefix('release/'))
    if value is None:
        raise ValueError('Branch has not been prepared by Job 1')
    sha, info = value
    identity.validate(info)
    current = ref('heads/'+branch)
    if current is None or info['branch'] != branch or current['object']['sha'] != sha:
        raise ValueError('Prepared branch moved; prepare a new release/build number')
    tree = api('git/trees/'+sha)
    configured = any(item['path'] == 'version.json' for item in tree['tree'])
    path = 'version.json' if configured else 'release.json'
    manifest = json.loads(base64.b64decode(api('contents/'+path+'?ref='+sha)['content']))
    expected = dict(version=info['baseVersion'], tag=identity.CHANNELS[info['channel']] or 'stable') if configured else info
    if manifest != expected:
        raise ValueError('Prepared commit manifest differs from immutable preparation record')
    return sha, info


def claim(sha, info, reuse):
    name = 'release-runs/'+info['displayVersion']
    existing = tag(name)
    if reuse:
        if existing != (sha, dict(commit=sha, buildRun=reuse)):
            raise ValueError('Reuse must identify the original build run for this prepared release')
        return reuse
    run_id = os.environ['GITHUB_RUN_ID']
    if existing is not None:
        if existing != (sha, dict(commit=sha, buildRun=run_id)):
            raise ValueError('Release already has a build run; select its ID to reuse its artifacts')
        return run_id
    create_tag(name, sha, dict(commit=sha, buildRun=run_id))
    return run_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('allocate', 'stamp', 'commit', 'resolve'))
    parser.add_argument('--root', type=Path)
    args = parser.parse_args()
    if args.operation == 'allocate':
        source_ref = os.environ['SOURCE_BRANCH'] if os.environ['SOURCE_COMMIT'] == 'latest' else os.environ['SOURCE_COMMIT']
        config = json.loads(base64.b64decode(api('contents/version.json?ref='+quote(source_ref, safe=''))['content']))
        info = allocate(os.environ['SOURCE_BRANCH'], os.environ['SOURCE_COMMIT'], config['version'], os.environ['CHANNEL'])
        write_json(ROOT/'build-release-reservation.json', info)
        emit(info)
    elif args.operation in ('stamp', 'commit'):
        root = args.root.resolve()
        info = json.loads((ROOT/'build-release-reservation.json').read_text())
        if args.operation == 'stamp':
            stamp(root, info)
        else:
            emit(info, commit=commit_branch(root, info))
    else:
        sha, info = resolve(os.environ['RELEASE_BRANCH'])
        if os.environ.get('GITHUB_REF') != 'refs/heads/'+info['branch'] or os.environ.get('GITHUB_SHA') != sha:
            raise ValueError('Release workflow must run on the exact prepared release branch commit')
        reuse = os.environ.get('BUILD_RUN_ID', '').strip()
        if reuse and not re.fullmatch(r'[1-9][0-9]*', reuse):
            raise ValueError('Build run ID must be empty or a numeric Actions run ID')
        build_run = claim(sha, info, reuse)
        write_json(ROOT/'build-release-selection.json', dict(commit=sha, buildRun=build_run, **info))
        emit(info, commit=sha, buildRun=build_run, reuse='true' if reuse else 'false')


if __name__ == '__main__':
    main()
