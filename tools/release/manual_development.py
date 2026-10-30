# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicit manual development builds/publication; no CI, tests, scans or smoke gates, only a startup gate."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT/'build/manual-development'
ASSETS = OUTPUT/'assets'
CLIENTS = ROOT/'deploy/client-assets/release'
sys.path.insert(0, str(ROOT/'tools/client'))
sys.path.insert(0, str(ROOT))
from publication import public_asset
from tools.release.bundle import checksum_assets
from tools.release import development_source, github_release, images as image_publication

HELM_IMAGE = 'alpine/helm:3.17.3'
IMAGES = {name: 'olo-toolgate-'+name for name in ('control', 'gateway', 'quickstart', 'device')}
IMAGES['secured_device'] = 'olo-toolgate-linux-secured-device'


def run(*args, capture=False, input=None):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=capture, input=input)


def metadata():
    info = json.loads((ROOT/'development-source.json').read_text(encoding='utf-8'))
    for name in ('VERSION', 'packages/contracts/VERSION'):
        if (ROOT/name).read_text().strip() != info['version']:
            raise ValueError('Development version was not stamped before building')
    if info['commit'] != os.environ['RELEASE_SHA']:
        raise ValueError('Development source differs from selected commit')
    return info


def startup_gate(image, timeout=180):
    """Refuses an image whose Control never becomes ready, such as a dependency mismatch that only fails at boot."""
    container = run('docker', 'run', '-d', '--tmpfs', '/data:rw,uid=65532,gid=65532', '-p', '127.0.0.1::8080',
                    image, capture=True).stdout.strip()
    try:
        port = run('docker', 'port', container, '8080/tcp', capture=True).stdout.split(':')[-1].strip()
        deadline = time.monotonic()+timeout
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/health/ready', timeout=3) as response:
                    if response.status == 200:
                        return
            except OSError:
                pass
            if run('docker', 'inspect', '-f', '{{.State.Running}}', container, capture=True).stdout.strip() != 'true':
                break
            time.sleep(1)
        print(run('docker', 'logs', '--tail', '50', container, capture=True).stdout, file=sys.stderr)
        raise RuntimeError(image+' did not become ready; refusing to publish it')
    finally:
        subprocess.run(['docker', 'rm', '-f', container], cwd=ROOT, capture_output=True)


def build():
    ASSETS.mkdir(parents=True, exist_ok=True)
    info = metadata()
    version = info['binaryVersion']
    common = ['--build-arg', 'VERSION='+version, '--build-arg', 'REVISION='+info['commit'],
              '--build-arg', 'BUILD_NUMBER='+os.environ['GITHUB_RUN_NUMBER']+' ('+info['commit'][:7]+')']
    clients = ['--build-arg', 'CLIENT_ASSETS_DIR=deploy/client-assets/release',
               '--build-arg', 'CLIENT_DOWNLOADS_DIRECTORY=/opt/toolgate/client-downloads']
    # Images and all packages use the same stamped development identity.
    run('docker', 'build', '-f', 'apps/control-plane/Dockerfile', *common, *clients,
        '-t', 'olo-toolgate-control:manual-development', '.')
    run('docker', 'build', '-f', 'apps/gateway/Dockerfile', *common,
        '-t', 'olo-toolgate-gateway:manual-development', '.')
    run('docker', 'build', '-f', 'apps/control-plane/Dockerfile', *common, *clients,
        '--build-arg', 'QUARKUS_PROFILE=quickstart', '-t', 'olo-toolgate-control:manual-quickstart', '.')
    run('docker', 'build', '-f', 'apps/quickstart/Dockerfile', *common, *clients,
        '--build-arg', 'CONTROL_IMAGE=olo-toolgate-control:manual-quickstart',
        '-t', 'olo-toolgate-quickstart:manual-development', '.')
    startup_gate('olo-toolgate-quickstart:manual-development')
    run('docker', 'build', '-f', 'apps/device/Dockerfile', *common,
        '--build-arg', 'QUICKSTART_IMAGE=olo-toolgate-quickstart:manual-development',
        '-t', 'olo-toolgate-device:manual-development', '.')
    run('docker', 'build', '-f', 'apps/device/secured.Dockerfile',
        '--build-arg', 'DEVICE_IMAGE=olo-toolgate-device:manual-development',
        '-t', 'olo-toolgate-linux-secured-device:manual-development', '.')
    for path in sorted(CLIENTS.iterdir()):
        if path.is_file() and public_asset(path):
            shutil.copyfile(path, ASSETS/path.name)
    run('cargo', 'package', '-p', 'olo-toolgate-contracts', '--no-verify', '--allow-dirty', '--locked')
    for path in (ROOT/'target/package').glob('olo-toolgate-contracts-*.crate'):
        shutil.copyfile(path, ASSETS/path.name)
    with zipfile.ZipFile(ASSETS/('olo-toolgate-contracts-php-'+info['libraryVersion']+'.zip'),
                         'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((ROOT/'packages/contracts/php').rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(ROOT/'packages/contracts/php').as_posix())
    run('npm', 'run', 'contracts:build')
    run('npm', 'pack', './packages/contracts/typescript', '--ignore-scripts', '--pack-destination', str(ASSETS))
    run('./gradlew', '--no-daemon', '-PcontractsPublicationVersion='+info['libraryVersion'],
        ':contracts-java:assemble', ':contracts-java:generatePomFileForMavenJavaPublication', '-x', 'test')
    for path in (ROOT/'packages/contracts/java/build/libs').glob('*.jar'):
        shutil.copyfile(path, ASSETS/path.name)
    pom = ROOT/'packages/contracts/java/build/publications/mavenJava/pom-default.xml'
    shutil.copyfile(pom, ASSETS/('toolgate-contracts-'+info['libraryVersion']+'.pom'))
    run(sys.executable, 'tools/release/bundle.py', '--output', str(ASSETS))
    for document in ('RELEASE-NOTES.md', 'RELEASE-NOTES.generated.md', 'contributors.json'):
        shutil.copyfile(ROOT/'release-notes'/document, ASSETS/document)
    chart = OUTPUT/'chart'
    shutil.copytree(ROOT/'deploy/helm/olo-toolgate', chart)
    values = chart/'values.yaml'
    namespace = os.environ.get('DOCKERHUB_NAMESPACE') or os.environ['DOCKERHUB_USERNAME']
    values.write_text(re.sub(r'(?m)^  imageRegistry:.*$', '  imageRegistry: '+json.dumps(namespace),
                            values.read_text(encoding='utf-8'), count=1), encoding='utf-8')
    run('docker', 'run', '--rm', '-v', str(ROOT)+':/work', '-w', '/work', HELM_IMAGE,
        'package', 'build/manual-development/chart', '--version', info['libraryVersion'],
        '--app-version', version, '--destination', 'build/manual-development/assets')
    (ASSETS/'development-release.json').write_text(json.dumps(info, indent=2)+'\n', encoding='utf-8')
    checksum_assets(ASSETS)


def publish():
    info = json.loads((ASSETS/'development-release.json').read_text(encoding='utf-8'))
    development_source.verify(info)
    repo = os.environ['GITHUB_REPOSITORY']
    namespace = os.environ.get('DOCKERHUB_NAMESPACE') or os.environ['DOCKERHUB_USERNAME']
    images = image_publication.publish(run, repo, info['commit'], namespace,
        {component:(name+':manual-development',name) for component,name in IMAGES.items()},
        info['dockerTag'], source_branch=info['sourceBranch'],
        branch_alias='dev-'+info['branchKey'])
    owner = repo.split('/')[0].lower()
    run('docker', 'run', '--rm', '--env', 'GH_TOKEN', '--env', 'GITHUB_ACTOR',
        '--env', 'HELM_OWNER='+owner, '--env', 'HELM_VERSION='+info['libraryVersion'],
        '-v', str(ROOT)+':/work', '-w', '/work', '--entrypoint', 'sh', HELM_IMAGE, '-c',
        'printf %s "$GH_TOKEN" | helm registry login ghcr.io --username "$GITHUB_ACTOR" --password-stdin && '
        'helm push "build/manual-development/assets/olo-toolgate-${HELM_VERSION}.tgz" "oci://ghcr.io/${HELM_OWNER}/charts"')
    info.update(images=images, maven='io.github.olo-labs:toolgate-contracts:'+info['libraryVersion'],
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
        'Source branch: `'+info['sourceBranch']+'`.\n\n'
        'No CI verification, tests, scans or smoke tests were run; the Quickstart image only had to start and become ready. '
        'Maven registry publication is disabled.\n\n'
        'Library version: `'+info['libraryVersion']+'`. Container digests and checksums are attached.\n\n'
        +(ASSETS/'RELEASE-NOTES.md').read_text(encoding='utf-8'), encoding='utf-8')
    github_release.publish(run, repo, info['releaseTag'], info['commit'], ASSETS,
                           'ToolGate manual development '+info['commit'][:12], notes, replace=True)


def npm_version_id(owner, version):
    """The GitHub Packages id of this contracts version, or None when it was never published."""
    try:
        pages = json.loads(run('gh', 'api', f'orgs/{owner}/packages/npm/toolgate-contracts/versions?per_page=100',
                               '--paginate', '--slurp', capture=True).stdout)
    except subprocess.CalledProcessError as error:
        if '404' in (error.stdout or '') + (error.stderr or ''):
            return None
        raise
    return next((item['id'] for page in pages for item in page if item['name'] == version), None)


def publish_typescript():
    info = json.loads((ASSETS/'development-release.json').read_text(encoding='utf-8'))
    development_source.verify(info)
    package = ASSETS/('olo-labs-toolgate-contracts-'+info['version']+'.tgz')
    with tarfile.open(package, 'r:gz') as archive:
        manifest = archive.extractfile('package/package.json')
        document = json.load(manifest)
    if (document['name'], document['version']) != ('@olo-labs/toolgate-contracts', info['version']):
        raise ValueError('TypeScript archive differs from selected package identity')
    # Each run has an immutable version; branch aliases only follow the current head.
    tag = 'dev-run-'+info['runId']+'-'+info['runAttempt']
    # npm never overwrites a version: a rerun deletes the version its earlier attempt published, then publishes again.
    owner = os.environ['GITHUB_REPOSITORY'].split('/')[0]
    existing = npm_version_id(owner, info['version'])
    if existing is not None:
        run('gh', 'api', '-X', 'DELETE', f'orgs/{owner}/packages/npm/toolgate-contracts/versions/{existing}')
    run('npm', 'publish', str(package), '--registry', 'https://npm.pkg.github.com',
        '--tag', tag, '--ignore-scripts')
    if image_publication.current_head(run, os.environ['GITHUB_REPOSITORY'], info['sourceBranch'], info['commit']):
        for alias in ['dev-'+info['branchKey']]+(['dev'] if info['sourceBranch'] == 'main' else []):
            run('npm', 'dist-tag', 'add', '@olo-labs/toolgate-contracts@'+info['version'], alias,
                '--registry', 'https://npm.pkg.github.com')


if __name__ == '__main__':
    {'build': build, 'publish': publish, 'publish-typescript': publish_typescript}[sys.argv[1]]()
