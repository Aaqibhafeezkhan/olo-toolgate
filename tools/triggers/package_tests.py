# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Discover package-owned mock tests and run one isolated CI matrix entry.

This is build/test tooling, never source placement, watch supervision or workflow routing.
Provider credentials are neither required nor supplied by the workflow.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FIELDS = {'schemaVersion', 'providerMode', 'packageId', 'buildRoot', 'project', 'startConfig'}
NAME = re.compile(r'[a-z][a-z0-9._-]{0,95}')


def load(root, manifest):
    root = root.resolve()
    manifest = Path(manifest)
    if manifest.is_symlink() or not manifest.resolve().is_relative_to(root):
        raise ValueError('Package test manifest must stay inside the source checkout')
    package = manifest.parent
    readme = package/'README.md'
    if readme.is_symlink() or not readme.is_file() or len(readme.read_text(encoding='utf-8').strip()) < 80:
        raise ValueError('Every trigger package needs its own explanatory README.md')
    data = json.loads(manifest.read_text(encoding='utf-8'))
    if set(data) != FIELDS or data['schemaVersion'] != 1 or data['providerMode'] != 'mock':
        raise ValueError('Unsupported package test manifest or provider mode')
    if not isinstance(data['packageId'], str) or not NAME.fullmatch(data['packageId']):
        raise ValueError('Invalid package test identity')
    if not isinstance(data['project'], str) or data['project'] and not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', data['project']):
        raise ValueError('Invalid Gradle project')
    if not isinstance(data['buildRoot'], str) or Path(data['buildRoot']).is_absolute() or '\\' in data['buildRoot']:
        raise ValueError('Build root must be a relative portable path')
    build = root/data['buildRoot']
    if any(part in ('', '.', '..') for part in data['buildRoot'].split('/')) or build.is_symlink() or not build.resolve().is_relative_to(root):
        raise ValueError('Build root escapes the source checkout')
    expected = build/data['project'] if data['project'] else build
    if expected.resolve() != package.resolve() or not (package/'build.gradle.kts').is_file():
        raise ValueError('Test manifest must select its own Gradle package')
    if not isinstance(data['startConfig'], dict) or len(json.dumps(data['startConfig']).encode()) > 8192:
        raise ValueError('Mock start config must be a bounded object')
    return dict(data, directory=package.relative_to(root).as_posix())


def discover(root=ROOT):
    root = Path(root).resolve()
    inventory = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    manifests = sorted({name for name in inventory if name.endswith('/trigger-tests.json')})
    if not manifests:
        raise ValueError('No registered trigger package tests')
    entries = [load(root, root/name) for name in manifests]
    if len(entries) > 256 or len({entry['packageId'] for entry in entries}) != len(entries):
        raise ValueError('Duplicate or excessive trigger package test entries')
    # A new Java trigger cannot silently omit registration and miss the matrix.
    registered = {entry['directory'] for entry in entries}
    for name in inventory:
        if '/src/main/' not in name or not name.endswith('.java'):
            continue
        source = root/name
        if not source.is_file():
            continue
        if not re.search(r'@(?:io\.ololabs\.toolgate\.sdk\.annotations\.)?(?:ClientTrigger|ServerTrigger)\s*\(', source.read_text(encoding='utf-8')):
            continue
        package = source.parent
        while package != root and not (package/'build.gradle.kts').is_file():
            package = package.parent
        # SDK implementation code/starter templates aren't installable example packages.
        if package != root and (package/'settings.gradle.kts').is_file() and '/tool-sdk/' in package.as_posix():
            continue
        if package != root and package.relative_to(root).as_posix() not in registered:
            raise ValueError('Trigger package is absent from mock-test matrix: '+package.relative_to(root).as_posix())
    return entries


def gradle(command, env, output, *, sleep=time.sleep):
    """Retry Maven Central throttling during dependency resolution, never failed tests/compilation."""
    output.mkdir(parents=True,exist_ok=True)
    for attempt in range(3):
        log=output/('gradle-'+str(attempt+1)+'.log')
        with log.open('wb') as stream:
            result=subprocess.run(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
        with log.open('rb') as stream:
            for chunk in iter(lambda:stream.read(65536),b''):
                sys.stdout.write(chunk.decode('utf-8',errors='replace'))
            stream.seek(max(0,stream.tell()-65536))
            tail=stream.read().decode('utf-8',errors='replace')
        if tail and not tail.endswith('\n'):print()
        if result.returncode==0:return
        throttled=('Could not resolve all files for configuration' in tail
            and 'Received status code 429 from server' in tail
            and not re.search(r'(?m)^> Task .*:[^\s]*[Tt]est[^\s]* FAILED',tail)
            and 'There were failing tests' not in tail
            and not re.search(r'(?m)(?:Compilation failed|\berror:)',tail))
        if attempt==2 or not throttled:
            raise subprocess.CalledProcessError(result.returncode,command)
        delay=(5,15)[attempt]
        print('Maven dependency request was throttled; retrying package build in '+str(delay)+' seconds.',flush=True)
        sleep(delay)


def run(entry, sdk_repo, cli, sdk_version):
    package = ROOT/entry['directory']
    prefix = ':'+entry['project']+':' if entry['project'] else ''
    env = dict(os.environ, TOOLGATE_CLI=str(cli), TOOLGATE_TEST_PROVIDER_MODE='mock')
    wrapper = ROOT/('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '--no-daemon', '--console=plain', '-p', str(ROOT/entry['buildRoot']),
               '-PtoolgateSdkRepo='+str(sdk_repo), '-PtoolgateSdkVersion='+sdk_version,
               prefix+'check', prefix+'toolgatePackage']
    gradle(command,env,ROOT/'build/trigger-package-tests'/entry['packageId'])
    artifacts = list((package/'build/toolgate').glob('*.olotool'))
    if len(artifacts) != 1:
        raise ValueError('Expected one current package artifact')
    artifact = artifacts[0]
    subprocess.run([str(cli), 'validate', str(artifact)], check=True)
    with zipfile.ZipFile(artifact) as archive:
        manifest = json.loads(archive.read('toolgate-package.json'))
        if manifest['id'] != entry['packageId'] or not manifest.get('triggers'):
            raise ValueError('Built artifact does not export the registered trigger package')
        trigger = manifest['triggers'][0]
        descriptor = trigger['descriptor']
        jar = package/'build/toolgate/mock-smoke.jar'
        jar.write_bytes(archive.read('artifacts/tool.jar'))
    target = 'CLIENT' if descriptor['kind'] == 'CLIENT_TRIGGER' else 'SERVER'
    start = dict(kind='TRIGGER_START', triggerId=descriptor['triggerId'], instanceId='mock-package-test',
                 configRevision=1, leaseEpoch=1, config=entry['startConfig'])
    wire = json.dumps(start)+'\n'+json.dumps(dict(kind='STOP', reason='MockPackageTest', graceMs=100))+'\n'
    # The standalone smoke process gets no ambient provider credentials.
    process_env = {key: os.environ[key] for key in ('SYSTEMROOT', 'SystemRoot', 'WINDIR') if key in os.environ}
    process_env.update(TOOLGATE_PROTOCOL='2', TOOLGATE_EXECUTION_MODE='TRIGGER', TOOLGATE_EXECUTION_TARGET=target)
    result = subprocess.run([shutil.which('java'), '-jar', str(jar)], input=wire, capture_output=True,
                            text=True, timeout=30, env=process_env, check=True)
    frames = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if not any(frame.get('kind') == 'READY' for frame in frames) or not any(frame.get('kind') == 'EXIT' and frame.get('code') == 0 for frame in frames):
        raise ValueError('Trigger package did not start/stop in isolated mock smoke')
    output = ROOT/'build/trigger-package-tests'/entry['packageId']
    output.mkdir(parents=True, exist_ok=True)
    (output/'result.json').write_text(json.dumps(dict(packageId=entry['packageId'], providerMode='mock',
        package=str(artifact.relative_to(ROOT)), checks='PASSED', archiveValidated=True, triggerStartup='PASSED'), indent=2)+'\n', encoding='utf-8')
    print('Mock package verification passed: '+entry['packageId'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', action='store_true')
    parser.add_argument('--github-output', action='store_true')
    parser.add_argument('--package', default=os.environ.get('TOOLGATE_TEST_PACKAGE'))
    parser.add_argument('--sdk-repo', type=Path, default=os.environ.get('TOOLGATE_TEST_SDK_REPO'))
    parser.add_argument('--cli', type=Path, default=os.environ.get('TOOLGATE_CLI'))
    parser.add_argument('--sdk-version-file', type=Path, default=ROOT/'VERSION')
    args = parser.parse_args()
    if args.matrix:
        entries = discover()
        matrix = json.dumps({'include': [dict(package=entry['directory'], key=entry['packageId']) for entry in entries]}, separators=(',', ':'))
        if args.github_output:
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write('matrix='+matrix+'\n')
        print(matrix)
        return
    if not args.package or args.sdk_repo is None or args.cli is None:
        parser.error('Select a registered package and supply the local SDK repository and CLI')
    entry = load(ROOT, ROOT/args.package/'trigger-tests.json')
    run(entry, args.sdk_repo.resolve(), args.cli.resolve(), args.sdk_version_file.read_text().strip())


if __name__ == '__main__':
    main()
