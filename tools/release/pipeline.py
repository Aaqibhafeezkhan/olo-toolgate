# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Collect verified release bytes and resume immutable registry publication."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT/'tools/client'))
from tools.release import identity, prepare, github_release
from tools.release.bundle import checksum_assets
from tools.release.development import IMAGES, digest, verify_checksums
from tools.release.tagged import verified_files
from publication import public_asset

OUTPUT = ROOT/'build/product-release'


def run(*args, capture=False, input=None):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=capture, input=input)


def selection():
    info = json.loads((ROOT/'build-release-selection.json').read_text())
    identity.validate(info)
    if not identity.SHA.fullmatch(info['commit']) or not re.fullmatch(r'[1-9][0-9]*', info['buildRun']):
        raise ValueError('Invalid prepared commit/build run')
    return info


def verify_source(source, info):
    from tools.release.notes import load
    load(source)
    if prepare.run('git', 'rev-parse', 'HEAD', cwd=source) != info['commit']:
        raise ValueError('Checkout does not match immutable prepared commit')
    release = identity.load(source)
    if any(info.get(key) != value for key, value in release.items()):
        raise ValueError('Source release manifest differs from selection')
    version = info['version']
    for path in ('VERSION', 'packages/contracts/VERSION'):
        if (source/path).read_text().strip() != version:
            raise ValueError('Every product/library must share the release version: '+path)
    for path in ('package.json', 'apps/admin-ui/package.json', 'packages/contracts/typescript/package.json'):
        if json.loads((source/path).read_text())['version'] != version:
            raise ValueError('npm version drift: '+path)
    cargo = tomllib.loads((source/'Cargo.toml').read_text())
    contracts = tomllib.loads((source/'packages/contracts/rust/Cargo.toml').read_text())
    if cargo['workspace']['package']['version'] != version or contracts['package']['version'] != version:
        raise ValueError('Cargo workspace/contracts version drift')
    for script in ('tools/contracts/version.py', 'tools/contracts/generate.py'):
        run(sys.executable, str(source/script), '--check')


def verify_run(run_id, collected=False):
    info = selection()
    producer = prepare.api('actions/runs/'+run_id)
    if (producer.get('path') != '.github/workflows/build-release.yml' or producer.get('event') != 'workflow_dispatch'
            or producer.get('head_branch') != info['branch'] or producer.get('head_sha') != info['commit'] or producer.get('repository', {}).get('full_name') != os.environ['GITHUB_REPOSITORY']):
        raise ValueError('Artifacts must originate in the trusted build/release workflow on the prepared release branch')
    jobs = [job for page in prepare.pages('actions/runs/'+run_id+'/jobs?filter=latest&per_page=100') for job in page['jobs']]
    required = {'preflight / metadata', 'foundation / check', 'clients / public-bundle', 'images / verify'}
    if collected:
        required.add('Collect immutable release bundle')
    success = {job['name'] for job in jobs if job.get('status') == 'completed' and job.get('conclusion') == 'success'}
    if not required <= success:
        raise ValueError('Every original build/test/package gate must succeed before publishing')
    targets = {'x86_64-unknown-linux-gnu', 'aarch64-unknown-linux-gnu', 'x86_64-pc-windows-msvc',
               'aarch64-pc-windows-msvc', 'x86_64-apple-darwin', 'aarch64-apple-darwin'}
    native = [job for job in jobs if job['name'].startswith('clients / native (')]
    if len(native) != 6 or any(job['name'] not in success for job in native) or any(not any(t in j['name'] for j in native) for t in targets):
        raise ValueError('All six native platform gates must succeed')
    claim = prepare.tag('release-runs/'+info['displayVersion'])
    if claim != (info['commit'], dict(commit=info['commit'], buildRun=run_id)):
        raise ValueError('Producer run differs from the immutable release build reservation')
    return producer


def collect(source):
    info = selection()
    verify_source(source, info)
    producer = verify_run(info['buildRun'])
    evidence = ROOT/'build/release-evidence'
    for artifact, directory in [('foundation-evidence', 'foundation'), ('public-client-bundle', 'client')]+[
            (name+'-evidence', key) for key, name in [('control', 'control'), ('gateway', 'gateway'),
            ('quickstart', 'quickstart'), ('device', 'device'), ('secured_device', 'secured-device')]]:
        run('gh', 'run', 'download', info['buildRun'], '--repo', os.environ['GITHUB_REPOSITORY'],
            '--name', artifact, '--dir', str(evidence/directory))
    assets = OUTPUT/'assets'
    assets.mkdir(parents=True)
    foundation = evidence/'foundation/build/release'
    files = verified_files(foundation)
    version = info['version']
    required = {'release.json', 'sbom.cdx.json', 'compatibility.json', 'LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md',
                f'olo-toolgate-{version}.tgz', f'olo-toolgate-contracts-{version}.tar.gz',
                f'olo-toolgate-contracts-{version}.crate', f'olo-toolgate-contracts-php-{version}.zip',
                f'olo-labs-toolgate-contracts-{version}.tgz'}
    required.update(f'toolgate-contracts-{version}{suffix}' for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'))
    if not required <= files:
        raise ValueError('Foundation is missing required release packages: '+str(sorted(required-files)))
    for path in foundation.iterdir():
        if path.name != 'SHA256SUMS':
            shutil.copyfile(path, assets/path.name)
    for path in (evidence/'client').iterdir():
        if path.is_file() and public_asset(path):
            shutil.copyfile(path, assets/path.name)
    for name in ('manifest.json', 'installers.json'):
        doc = json.loads((assets/name).read_text())
        if doc['version'] != version or len(doc['artifacts']) != 6:
            raise ValueError('All six clients/installers must share the release version')
        for item in doc['artifacts']:
            # Windows ZIPs are internal build inputs; public installation uses the EXE.
            if not public_asset(Path(item['filename'])):
                continue
            path = assets/item['filename']
            if digest(path) != item['sha256'] or path.stat().st_size != item['bytes']:
                raise ValueError('Client/installer checksum mismatch')
    ui = evidence/'foundation/build/ui'
    for path in ui.glob('*'):
        if path.is_file() and path.name != 'SHA256SUMS':
            shutil.copyfile(path, assets/path.name)
    for component in IMAGES:
        folder = evidence/component
        verify_checksums(folder)
        target = OUTPUT/'images'/component
        target.mkdir(parents=True)
        shutil.copyfile(folder/'image.tar', target/'image.tar')
        for path in folder.glob('*sbom.cdx.json'):
            shutil.copyfile(path, assets/path.name)
    receipt = dict(schemaVersion=1, commit=info['commit'], sourceCommit=info['sourceCommit'],
                   version=version, buildRun=info['buildRun'], controllerCommit=producer['head_sha'],
                   verification='passed', tests='passed', nativePlatforms=6)
    prepare.write_json(assets/'build-manifest.json', receipt)
    verify_packages(info, assets)
    from tools.release.notes import generate
    generate(source, assets)
    checksum_assets(assets)
    checksums = {p.relative_to(OUTPUT).as_posix():digest(p) for p in sorted(OUTPUT.rglob('*')) if p.is_file()}
    prepare.write_json(OUTPUT/'bundle-checksums.json', checksums)
    validate_bundle()


def verify_packages(info, assets):
    """Inspect actual package contents, not just their filenames or build claims."""
    version = info['version']
    pom = ET.parse(assets/f'toolgate-contracts-{version}.pom').getroot()
    ns = {'m':'http://maven.apache.org/POM/4.0.0'}
    if [pom.findtext('m:'+key, namespaces=ns) for key in ('groupId', 'artifactId', 'version')] != ['io.github.olo-labs', 'toolgate-contracts', version]:
        raise ValueError('Prepared Maven POM coordinate differs from release')
    module = json.loads((assets/f'toolgate-contracts-{version}.module').read_text())['component']
    if [module.get(key) for key in ('group', 'module', 'version')] != ['io.github.olo-labs', 'toolgate-contracts', version]:
        raise ValueError('Prepared Maven module coordinate differs from release')
    for path in assets.glob('*.jar'):
        with zipfile.ZipFile(path) as archive:
            manifest = archive.read('META-INF/MANIFEST.MF').decode()
            match = re.search(r'(?m)^Implementation-Version: ([^\r\n]+)', manifest)
            if not match or match[1] != version:
                raise ValueError('JAR embedded version differs from release: '+path.name)
    with tarfile.open(assets/f'olo-labs-toolgate-contracts-{version}.tgz') as archive:
        if json.load(archive.extractfile('package/package.json'))['version'] != version:
            raise ValueError('npm tarball embedded version differs from release')
    with tarfile.open(assets/f'olo-toolgate-contracts-{version}.crate') as archive:
        manifest = archive.extractfile(f'olo-toolgate-contracts-{version}/Cargo.toml').read().decode()
        if tomllib.loads(manifest)['package']['version'] != version:
            raise ValueError('Rust crate embedded version differs from release')
    with tarfile.open(assets/f'olo-toolgate-contracts-{version}.tar.gz') as archive:
        if archive.extractfile('VERSION').read().decode().strip() != version:
            raise ValueError('Raw contract bundle version differs from release')
    with zipfile.ZipFile(assets/f'olo-toolgate-contracts-php-{version}.zip') as archive:
        if f"public const VERSION = '{version}';" not in archive.read('src/ContractSet.php').decode():
            raise ValueError('PHP embedded version differs from release')
    with tarfile.open(assets/f'olo-toolgate-{version}.tgz') as archive:
        chart = archive.extractfile('olo-toolgate/Chart.yaml').read().decode()
        for key in ('version', 'appVersion'):
            match = re.search(r'(?m)^'+key+r':\s*([^\r\n]+)', chart)
            if not match or match[1].strip().strip('"\'') != version:
                raise ValueError('Helm chart/app version differs from release')
    with tarfile.open(assets/f'olo-toolgate-admin-ui-{version}.tar') as archive:
        ui = json.load(archive.extractfile('release.json'))
        if ui['version'] != version or ui['contracts'] != version:
            raise ValueError('UI embedded version differs from release')


def validate_bundle():
    info = selection()
    checksums = json.loads((OUTPUT/'bundle-checksums.json').read_text())
    actual = {p.relative_to(OUTPUT).as_posix():p for p in OUTPUT.rglob('*') if p.is_file() and p.name != 'bundle-checksums.json'}
    if set(actual) != set(checksums) or any(p.is_symlink() or digest(p) != checksums[name] for name, p in actual.items()):
        raise ValueError('Release bundle is incomplete or its bytes have changed')
    for name in checksums:
        if name.startswith('/') or '..' in name.split('/') or '\\' in name:
            raise ValueError('Unsafe release bundle path')
    receipt = json.loads((OUTPUT/'assets/build-manifest.json').read_text())
    if any(receipt.get(key) != info[key] for key in ('commit', 'version', 'buildRun', 'sourceCommit')) or receipt.get('tests') != 'passed':
        raise ValueError('Release bundle does not match prepared commit/version/original run')
    manifest = identity.load(OUTPUT/'assets')
    if any(info.get(key) != value for key, value in manifest.items()):
        raise ValueError('Release identity differs from checked artifacts')
    verified_files(OUTPUT/'assets')
    for component in IMAGES:
        if f'images/{component}/image.tar' not in checksums:
            raise ValueError('Release bundle is missing a required image')
    return info


def remote_manifest(target, missing=False):
    try:
        doc = json.loads(run('docker', 'manifest', 'inspect', '--verbose', target, capture=True).stdout)
    except subprocess.CalledProcessError as error:
        if missing and any(message in (error.stderr or '').lower() for message in ('manifest unknown', 'no such manifest', 'name unknown')):
            return None
        raise
    if not isinstance(doc, dict) or not re.fullmatch(r'sha256:[0-9a-f]{64}', doc.get('Descriptor', {}).get('digest', '')):
        raise ValueError('Expected a single-platform image with a verified registry digest')
    return doc


def publish_images(info):
    owner = os.environ['GITHUB_REPOSITORY'].split('/')[0].lower()
    token = os.environ['GH_TOKEN']
    run('docker', 'login', 'ghcr.io', '--username', os.environ['GITHUB_ACTOR'], '--password-stdin', input=token)
    result = {}
    try:
        for component, (local, name) in IMAGES.items():
            run('docker', 'load', '-i', str(OUTPUT/'images'/component/'image.tar'))
            doc = json.loads(run('docker', 'image', 'inspect', local, capture=True).stdout)[0]
            labels = doc['Config'].get('Labels') or {}
            if labels.get('org.opencontainers.image.version') != info['version']:
                raise ValueError('Built container version differs from release: '+component)
            # The secured device inherits the device labels and source revision.
            if labels.get('org.opencontainers.image.revision') != info['commit']:
                raise ValueError('Built container source revision differs from release: '+component)
            image = f'ghcr.io/{owner}/{name}'
            target = image+':'+info['version']
            remote = remote_manifest(target, missing=True)
            if remote is None:
                run('docker', 'tag', local, target)
                run('docker', 'push', target)
                remote = remote_manifest(target)
            manifest = remote.get('SchemaV2Manifest') or remote.get('OCIManifest') or {}
            if manifest.get('config', {}).get('digest') != doc['Id']:
                raise ValueError('Existing registry version contains a different image: '+target)
            reference = image+'@'+remote['Descriptor']['digest']
            prepare.create_tag('published/'+info['version']+'/'+component, info['commit'], dict(image=reference, config=doc['Id']))
            result[component] = reference
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                    output.write(f'{component}_image={image}\n{component}_digest={remote["Descriptor"]["digest"]}\n')
    finally:
        run('docker', 'logout', 'ghcr.io')
    return result


def maven_request(url, data=None, authenticated=True):
    headers = {}
    if authenticated:
        token = base64.b64encode((os.environ['MAVEN_USERNAME']+':'+os.environ['MAVEN_PASSWORD']).encode()).decode()
        headers['Authorization'] = 'Basic '+token
    if data is not None:
        headers['Content-Type'] = 'application/octet-stream'
    # Never forward repository credentials through a redirect.
    from tools.release.central import NoRedirect
    request = urllib.request.Request(url, data=data, headers=headers, method='PUT' if data is not None else 'GET')
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=120) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404 and data is None:
            return None
        raise ValueError(f'Maven repository returned HTTP {error.code}') from None


def publish_maven(info):
    from tools.release import central, development
    development.validate_maven_repository()
    url = os.environ['MAVEN_REPOSITORY_URL'].rstrip('/')
    default = 'https://maven.pkg.github.com/'+os.environ['GITHUB_REPOSITORY']
    if url == default:
        os.environ['MAVEN_USERNAME'] = os.environ.get('MAVEN_USERNAME') or os.environ['GITHUB_ACTOR']
        os.environ['MAVEN_PASSWORD'] = os.environ.get('MAVEN_PASSWORD') or os.environ['GH_TOKEN']
    elif not os.environ.get('MAVEN_USERNAME') or not os.environ.get('MAVEN_PASSWORD'):
        raise ValueError('Custom Maven repositories require explicit MAVEN_USERNAME and MAVEN_PASSWORD')
    is_central = url == central.UPLOAD_URL
    base = ('https://repo.maven.apache.org/maven2' if is_central else url)+'/io/github/olo-labs/toolgate-contracts/'+info['version']+'/'
    files = [OUTPUT/'assets'/f'toolgate-contracts-{info["version"]}{suffix}' for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')]
    missing = []
    # Inspect every coordinate before doing any write, including interrupted releases.
    for path in files:
        data = maven_request(base+path.name, authenticated=not is_central)
        if data is None:
            missing.append(path)
        elif hashlib.sha256(data).hexdigest() != digest(path):
            raise ValueError('Existing Maven version contains different bytes: '+path.name)
    if missing and is_central:
        if len(missing) != len(files):
            raise ValueError('Central is partially visible; wait for the original deployment before retrying')
        record = prepare.tag('central-deployments/'+info['version'])
        if record and record[0] != info['commit']:
            raise ValueError('Central deployment belongs to another release commit')
        deployment = record[1]['deployment'] if record else None
        central.publish(OUTPUT/'assets', info['version'], deployment_id=deployment,
                        on_deployment=lambda value: prepare.create_tag('central-deployments/'+info['version'], info['commit'], dict(deployment=value)))
    elif missing:
        for path in missing:
            maven_request(base+path.name, path.read_bytes())
            if maven_request(base+path.name) != path.read_bytes():
                raise ValueError('Maven upload verification failed: '+path.name)
            for algorithm in ('sha1', 'sha256', 'sha512'):
                maven_request(base+path.name+'.'+algorithm, hashlib.new(algorithm, path.read_bytes()).hexdigest().encode())
    prepare.create_tag('published/'+info['version']+'/maven', info['commit'], dict(repository=url, files={p.name:digest(p) for p in files}))
    return url


def publish_npm(info):
    coordinate = '@olo-labs/toolgate-contracts@'+info['version']
    registry = 'https://npm.pkg.github.com'
    tarball = OUTPUT/'assets'/f'olo-labs-toolgate-contracts-{info["version"]}.tgz'
    expected = 'sha512-'+base64.b64encode(hashlib.sha512(tarball.read_bytes()).digest()).decode()
    try:
        current = json.loads(run('npm', 'view', coordinate, 'dist.integrity', '--registry', registry, '--json', capture=True).stdout)
    except subprocess.CalledProcessError as error:
        if 'E404' not in (error.stderr or ''):
            raise
        current = None
    if current is None:
        run('npm', 'publish', str(tarball), '--registry', registry, '--tag', 'build-'+str(info['build']), '--ignore-scripts')
        current = json.loads(run('npm', 'view', coordinate, 'dist.integrity', '--registry', registry, '--json', capture=True).stdout)
    if current != expected:
        raise ValueError('Existing npm version contains different bytes')
    prepare.create_tag('published/'+info['version']+'/npm', info['commit'], dict(coordinate=coordinate, integrity=expected))
    return coordinate


def publish_helm(info):
    owner = os.environ['GITHUB_REPOSITORY'].split('/')[0].lower()
    registry = f'oci://ghcr.io/{owner}/charts'
    archive = OUTPUT/'assets'/f'olo-toolgate-{info["version"]}.tgz'
    run('helm', 'registry', 'login', 'ghcr.io', '--username', os.environ['GITHUB_ACTOR'], '--password-stdin', input=os.environ['GH_TOKEN'])
    try:
        with tempfile.TemporaryDirectory(prefix='toolgate-chart-') as temporary:
            args = ['helm', 'pull', registry+'/olo-toolgate', '--version', info['version'], '--destination', temporary]
            try:
                run(*args, capture=True)
            except subprocess.CalledProcessError as error:
                if not any(message in (error.stderr or '').lower() for message in ('not found', 'manifest unknown')):
                    raise
                run('helm', 'push', str(archive), registry)
                run(*args, capture=True)
            if digest(Path(temporary)/archive.name) != digest(archive):
                raise ValueError('Existing Helm version contains different bytes')
    finally:
        run('helm', 'registry', 'logout', 'ghcr.io')
    prepare.create_tag('published/'+info['version']+'/helm', info['commit'], dict(registry=registry, sha256=digest(archive)))
    return registry+'/olo-toolgate:'+info['version']


def publish():
    info = validate_bundle()
    verify_packages(info, OUTPUT/'assets')
    from tools.release.development import validate_maven_repository
    validate_maven_repository()
    default = 'https://maven.pkg.github.com/'+os.environ['GITHUB_REPOSITORY']
    if os.environ['MAVEN_REPOSITORY_URL'].rstrip('/') != default and (not os.environ.get('MAVEN_USERNAME') or not os.environ.get('MAVEN_PASSWORD')):
        raise ValueError('Custom Maven repositories require explicit MAVEN_USERNAME and MAVEN_PASSWORD')
    producer = verify_run(info['buildRun'], collected=True)
    receipt = json.loads((OUTPUT/'assets/build-manifest.json').read_text())
    if receipt['controllerCommit'] != producer['head_sha']:
        raise ValueError('Bundle controller commit differs from original build workflow')
    if prepare.resolve(info['branch'])[0] != info['commit']:
        raise ValueError('Prepared branch no longer identifies the release commit')
    # Work on a separate copy: the retained build bundle is never mutated.
    release = ROOT/'build/publication/assets'
    shutil.copytree(OUTPUT/'assets', release)
    registries = dict(images=publish_images(info), maven=publish_maven(info), npm=publish_npm(info), helm=publish_helm(info))
    prepare.write_json(release/'registry-publication.json', dict(version=info['version'], commit=info['commit'], **registries))
    checksum_assets(release)


def version_order(version):
    match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)(?:-(beta|rc)\.(\d+))?', version)
    if not match:
        raise ValueError('Unexpected channel alias version; inspect it before replacing it')
    return (*map(int, match.groups()[:3]), {'beta':0, 'rc':1, None:2}[match[4]], int(match[5] or 0))


def finalize():
    info = validate_bundle()
    release = ROOT/'build/publication/assets'
    verified_files(release)
    registries = json.loads((release/'registry-publication.json').read_text())
    if registries['commit'] != info['commit'] or registries['version'] != info['version']:
        raise ValueError('Publication receipt identity mismatch')
    prepare.create_tag(info['tag'], info['commit'], dict(version=info['version'], buildRun=info['buildRun']))
    github_release.publish(run, os.environ['GITHUB_REPOSITORY'], info['tag'], info['commit'], release,
                           'OLO ToolGate '+info['version'], release/'RELEASE-NOTES.md', prerelease=info['channel'] != 'Stable')
    # Advance only the matching npm channel, and never move an alias backwards.
    alias = {'Beta':'beta', 'RC':'rc', 'Stable':'latest'}[info['channel']]
    tags = json.loads(run('npm', 'view', '@olo-labs/toolgate-contracts', 'dist-tags', '--registry', 'https://npm.pkg.github.com', '--json', capture=True).stdout)
    if alias not in tags or version_order(tags[alias]) < version_order(info['version']):
        run('npm', 'dist-tag', 'add', registries['npm'], alias, '--registry', 'https://npm.pkg.github.com')
    prepare.emit(info, commit=info['commit'], buildRun=info['buildRun'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('verify-source', 'collect', 'validate-bundle', 'publish', 'finalize'))
    parser.add_argument('--source', type=Path, default=ROOT/'source')
    args = parser.parse_args()
    if args.operation == 'verify-source':
        verify_source(args.source.resolve(), selection())
    elif args.operation == 'collect':
        collect(args.source.resolve())
    elif args.operation == 'validate-bundle':
        verify_run(selection()['buildRun'], collected=True)
        validate_bundle()
    elif args.operation == 'publish':
        publish()
    else:
        finalize()
