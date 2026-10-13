# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicit manual development builds/publication; no CI, tests, scans or smoke gates."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT/'build/manual-development'
ASSETS = OUTPUT/'assets'
CLIENTS = ROOT/'deploy/client-assets/release'
sys.path.insert(0, str(ROOT/'tools/client'))
sys.path.insert(0, str(ROOT))
from publication import public_asset
from tools.release.bundle import checksum_assets

HELM_IMAGE = 'alpine/helm:3.17.3'
IMAGES = {name: 'olo-toolgate-'+name for name in ('control', 'gateway', 'quickstart', 'device')}
IMAGES['secured_device'] = 'olo-toolgate-linux-secured-device'


def run(*args, capture=False, input=None):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=capture, input=input)


def metadata():
    sha = os.environ['RELEASE_SHA']
    identity = os.environ['GITHUB_RUN_ID']+'-'+os.environ.get('GITHUB_RUN_ATTEMPT', '1')
    base = (ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip().split('-')[0]
    version = base+'-dev.'+identity.replace('-', '.')+'.g'+sha[:12]
    return dict(commit=sha, releaseTag='dev-'+sha+'-'+identity,
                dockerTag='dev-'+sha[:12]+'-'+identity, libraryVersion=version,
                binaryVersion=(ROOT/'VERSION').read_text(encoding='utf-8').strip(),
                verification='not-run', tests='not-run', ciRuns={}, trigger='workflow_dispatch')


def build():
    ASSETS.mkdir(parents=True, exist_ok=True)
    info = metadata()
    version = info['binaryVersion']
    common = ['--build-arg', 'VERSION='+version, '--build-arg', 'REVISION='+info['commit'],
              '--build-arg', 'BUILD_NUMBER='+os.environ['GITHUB_RUN_NUMBER']+' ('+info['commit'][:7]+')']
    clients = ['--build-arg', 'CLIENT_ASSETS_DIR=deploy/client-assets/release',
               '--build-arg', 'CLIENT_DOWNLOADS_DIRECTORY=/opt/toolgate/client-downloads']
    # Images keep the source product version and its matching initial configuration bundle.
    run('docker', 'build', '-f', 'apps/control-plane/Dockerfile', *common, *clients,
        '-t', 'olo-toolgate-control:manual-development', '.')
    run('docker', 'build', '-f', 'apps/gateway/Dockerfile', *common,
        '-t', 'olo-toolgate-gateway:manual-development', '.')
    run('docker', 'build', '-f', 'apps/control-plane/Dockerfile', *common, *clients,
        '--build-arg', 'QUARKUS_PROFILE=quickstart', '-t', 'olo-toolgate-control:manual-quickstart', '.')
    run('docker', 'build', '-f', 'apps/quickstart/Dockerfile', *common, *clients,
        '--build-arg', 'CONTROL_IMAGE=olo-toolgate-control:manual-quickstart',
        '-t', 'olo-toolgate-quickstart:manual-development', '.')
    run('docker', 'build', '-f', 'apps/device/Dockerfile', *common,
        '--build-arg', 'QUICKSTART_IMAGE=olo-toolgate-quickstart:manual-development',
        '-t', 'olo-toolgate-device:manual-development', '.')
    run('docker', 'build', '-f', 'apps/device/secured.Dockerfile',
        '--build-arg', 'DEVICE_IMAGE=olo-toolgate-device:manual-development',
        '-t', 'olo-toolgate-linux-secured-device:manual-development', '.')
    for path in sorted(CLIENTS.iterdir()):
        if path.is_file() and public_asset(path):
            shutil.copyfile(path, ASSETS/path.name)
    # Rust uses the checked-in crate version; each GitHub release has its own distribution.
    run('cargo', 'package', '-p', 'olo-toolgate-contracts', '--no-verify', '--allow-dirty', '--locked')
    for path in (ROOT/'target/package').glob('olo-toolgate-contracts-*.crate'):
        shutil.copyfile(path, ASSETS/path.name)
    with zipfile.ZipFile(ASSETS/('olo-toolgate-contracts-php-'+info['libraryVersion']+'.zip'),
                         'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((ROOT/'packages/contracts/php').rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(ROOT/'packages/contracts/php').as_posix())
    # Registry coordinates are independent of the source/wire and product versions.
    package = ROOT/'packages/contracts/typescript/package.json'
    document = json.loads(package.read_text(encoding='utf-8'))
    document['version'] = info['libraryVersion']
    package.write_text(json.dumps(document, indent=2)+'\n', encoding='utf-8')
    run('npm', 'run', 'contracts:build')
    run('npm', 'pack', './packages/contracts/typescript', '--ignore-scripts', '--pack-destination', str(ASSETS))
    run('./gradlew', '--no-daemon', '-PcontractsPublicationVersion='+info['libraryVersion'],
        ':contracts-java:assemble', ':contracts-java:generatePomFileForMavenJavaPublication', '-x', 'test')
    for path in (ROOT/'packages/contracts/java/build/libs').glob('*.jar'):
        shutil.copyfile(path, ASSETS/path.name)
    pom = ROOT/'packages/contracts/java/build/publications/mavenJava/pom-default.xml'
    shutil.copyfile(pom, ASSETS/('toolgate-contracts-'+info['libraryVersion']+'.pom'))
    run(sys.executable, 'tools/release/bundle.py', '--output', str(ASSETS))
    chart = OUTPUT/'chart'
    shutil.copytree(ROOT/'deploy/helm/olo-toolgate', chart)
    values = chart/'values.yaml'
    namespace = os.environ.get('DOCKERHUB_NAMESPACE') or os.environ['DOCKERHUB_USERNAME']
    values.write_text(re.sub(r'(?m)^  imageRegistry:.*$', '  imageRegistry: '+json.dumps(namespace),
                            values.read_text(encoding='utf-8'), count=1), encoding='utf-8')
    run('docker', 'run', '--rm', '-v', str(ROOT)+':/work', '-w', '/work', HELM_IMAGE,
        'package', 'build/manual-development/chart', '--version', info['libraryVersion'],
        '--app-version', info['dockerTag'], '--destination', 'build/manual-development/assets')
    (ASSETS/'development-release.json').write_text(json.dumps(info, indent=2)+'\n', encoding='utf-8')


def publish():
    info = json.loads((ASSETS/'development-release.json').read_text(encoding='utf-8'))
    repo = os.environ['GITHUB_REPOSITORY']
    namespace = os.environ.get('DOCKERHUB_NAMESPACE') or os.environ['DOCKERHUB_USERNAME']
    token = os.environ['DOCKERHUB_TOKEN']
    current = json.loads(run('gh', 'api', 'repos/'+repo+'/git/ref/heads/main', capture=True).stdout)['object']['sha'] == info['commit']
    run('docker', 'login', '--username', os.environ['DOCKERHUB_USERNAME'], '--password-stdin', input=token)
    images = {}
    try:
        for component, name in IMAGES.items():
            target = namespace+'/'+name+':'+info['dockerTag']
            run('docker', 'tag', name+':manual-development', target)
            run('docker', 'push', target)
            images[component] = run('docker', 'inspect', target, '--format', '{{index .RepoDigests 0}}', capture=True).stdout.strip()
            for alias in ['dev-'+info['commit'][:12]]+(['dev'] if current else []):
                run('docker', 'tag', target, namespace+'/'+name+':'+alias)
                run('docker', 'push', namespace+'/'+name+':'+alias)
    finally:
        run('docker', 'logout')
    owner = repo.split('/')[0].lower()
    run('docker', 'run', '--rm', '--env', 'GH_TOKEN', '--env', 'GITHUB_ACTOR',
        '--env', 'HELM_OWNER='+owner, '--env', 'HELM_VERSION='+info['libraryVersion'],
        '-v', str(ROOT)+':/work', '-w', '/work', '--entrypoint', 'sh', HELM_IMAGE, '-c',
        'printf %s "$GH_TOKEN" | helm registry login ghcr.io --username "$GITHUB_ACTOR" --password-stdin && '
        'helm push "build/manual-development/assets/olo-toolgate-${HELM_VERSION}.tgz" "oci://ghcr.io/${HELM_OWNER}/charts"')
    info.update(images=images, maven='io.ololabs.toolgate:toolgate-contracts:'+info['libraryVersion'],
                npm='@olo-labs/toolgate-contracts@'+info['libraryVersion'],
                helm='oci://ghcr.io/'+owner+'/charts/olo-toolgate:'+info['libraryVersion'])
    (ASSETS/'development-release.json').write_text(json.dumps(info, indent=2)+'\n', encoding='utf-8')
    compatibility = ASSETS/'compatibility.json'
    distribution = json.loads(compatibility.read_text(encoding='utf-8'))
    distribution.update(maven=info['maven'], npm=info['npm'], helmOci=info['helm'],
                        gatewayImage=images['gateway'], controlImage=images['control'],
                        quickstartImage=images['quickstart'], deviceImage=images['device'],
                        securedDeviceImage=images['secured_device'])
    compatibility.write_text(json.dumps(distribution, indent=2)+'\n', encoding='utf-8')
    checksum_assets(ASSETS)
    notes = OUTPUT/'notes.md'
    notes.write_text('Manual development build for commit `'+info['commit']+'`.\n\n'
        'No CI verification, tests, scans, smoke tests, or signing were run.\n\n'
        'Library version: `'+info['libraryVersion']+'`. Container digests and checksums are attached.\n\n'
        +(ROOT/'RELEASE-NOTES.md').read_text(encoding='utf-8'), encoding='utf-8')
    run('gh', 'release', 'create', info['releaseTag'], *[str(p) for p in sorted(ASSETS.iterdir()) if p.is_file()],
        '--repo', repo, '--target', info['commit'], '--prerelease', '--latest=false',
        '--title', 'ToolGate manual development '+info['commit'][:12], '--notes-file', str(notes))


if __name__ == '__main__':
    {'build': build, 'publish': publish}[sys.argv[1]]()
