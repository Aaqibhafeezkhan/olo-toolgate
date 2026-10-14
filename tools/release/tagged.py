# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Immutable GHCR/Helm/GitHub publication using the common run's tested artifacts."""
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

from tools.contracts.version import SEMVER
from tools.release import development as shared


def version_tag():
    version=(shared.ROOT/'VERSION').read_text(encoding='utf-8').strip()
    tag='v'+version
    if not SEMVER.fullmatch(version) or os.environ.get('GITHUB_REF')!='refs/tags/'+tag:
        raise ValueError('Tagged publication requires the exact v<VERSION> ref')
    return tag


def verify_tag_commit(repo,sha):
    tag=version_tag()
    target=shared.api(f'repos/{repo}/git/ref/tags/{tag}')['object']
    seen=set()
    while target.get('type')=='tag':
        object_sha=target.get('sha','')
        if not re.fullmatch(r'[0-9a-f]{40}',object_sha) or object_sha in seen or len(seen)>=8:
            raise ValueError('Invalid or cyclic annotated tag')
        seen.add(object_sha)
        target=shared.api(f'repos/{repo}/git/tags/{object_sha}')['object']
    if target.get('type')!='commit' or target.get('sha')!=sha:
        raise ValueError('Release tag does not identify the verified CI commit')


def ensure_unpublished(repo):
    """An exact 404 allows a new release; authentication/network failures remain blocking."""
    try:
        shared.run('gh','api',f'repos/{repo}/releases/tags/{version_tag()}',capture=True)
    except subprocess.CalledProcessError as error:
        if re.search(r'\(HTTP 404\)',error.stderr or ''):return
        raise
    raise ValueError('The immutable GitHub release already exists')


def verified_files(folder):
    if any(p.is_symlink() or not p.is_file() for p in folder.iterdir()):
        raise ValueError('Release assets must be regular files')
    files={p.name for p in folder.iterdir() if p.name!='SHA256SUMS'}
    listed=set()
    for line in (folder/'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        checksum,name=line.split(maxsplit=1)
        if (not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or name in listed
                or not re.fullmatch(r'[0-9a-f]{64}',checksum)
                or shared.digest(folder/name)!=checksum):
            raise ValueError('Invalid release artifact checksum')
        listed.add(name)
    if listed!=files:raise ValueError('Every release asset must be checksummed')
    return files


def verified_foundation(folder):
    files=verified_files(folder)
    version=(shared.ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    product=version_tag()[1:]
    required={'sbom.cdx.json','compatibility.json','LICENSE','NOTICE.md','RELEASE-NOTES.md',
              'CHANGELOG.md','foundation-upgrades.md','gateway-upgrades.md','control-upgrades.md',f'olo-toolgate-{product}.tgz',
              f'olo-toolgate-contracts-{version}.tar.gz'}
    required.update(f'toolgate-contracts-{version}{classifier}.jar' for classifier in ('','-sources','-javadoc'))
    required.update(f'toolgate-contracts-{version}.{extension}' for extension in ('pom','module'))
    if not required<=files:raise ValueError('Tagged release is missing required foundation artifacts')
    return files


def checksum_assets():
    assets=shared.OUTPUT/'assets'
    (assets/'SHA256SUMS').write_text(''.join(f'{shared.digest(p)}  {p.name}\n' for p in sorted(assets.iterdir())
        if p.is_file() and p.name!='SHA256SUMS'),encoding='utf-8')


def verified_assets():
    assets=shared.OUTPUT/'assets'
    verified_files(assets)
    foundation=shared.OUTPUT/'foundation/build/release'
    for name in verified_foundation(foundation):
        if shared.digest(assets/name)!=shared.digest(foundation/name):
            raise ValueError('Release asset differs from checked foundation evidence')
    metadata=json.loads((assets/'tagged-release.json').read_text(encoding='utf-8'))
    if metadata['commit']!=os.environ['RELEASE_SHA'] or metadata['releaseTag']!=version_tag():
        raise ValueError('Prepared release identity mismatch')
    return metadata


def prepare_assets(selected):
    assets=shared.OUTPUT/'assets'
    folder=shared.OUTPUT/'foundation/build/release'
    for name in verified_foundation(folder):
        shutil.copyfile(folder/name,assets/name)
    version=(shared.ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    (shared.OUTPUT/'MAVEN_VERSION').write_text(version+'\n',encoding='utf-8')
    (assets/'tagged-release.json').write_text(json.dumps(dict(commit=os.environ['RELEASE_SHA'],
        ciRuns=selected,releaseTag=version_tag(),mavenVersion=version),indent=2)+'\n',encoding='utf-8')
    checksum_assets()


def publish_maven():
    """Publish the exact checked JAR/POM/module bytes without rebuilding or rerunning tests."""
    version=(shared.ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    if not SEMVER.fullmatch(version):raise ValueError('Invalid checked Maven version')
    verified_assets()
    assets=shared.OUTPUT/'assets'
    pom=ET.parse(assets/f'toolgate-contracts-{version}.pom').getroot()
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    if [pom.findtext('m:'+field,namespaces=ns) for field in ('groupId','artifactId','version')]!=['io.github.olo-labs','toolgate-contracts',version]:
        raise ValueError('Checked Maven POM identity mismatch')
    module=json.loads((assets/f'toolgate-contracts-{version}.module').read_text(encoding='utf-8'))
    identity=module.get('component',{})
    if [identity.get(k) for k in ('group','module','version')]!=['io.github.olo-labs','toolgate-contracts',version]:
        raise ValueError('Checked Gradle module identity mismatch')
    from tools.release import central
    if os.environ.get('MAVEN_REPOSITORY_URL') == central.UPLOAD_URL:
        central.publish(assets, version)
        return
    from tools.release import maven
    maven.publish(assets, version, project=shared.OUTPUT/'maven-publication', run=shared.run)



def publish():
    repo,sha,owner=shared.coordinates()
    tag=version_tag();version=tag[1:]
    verify_tag_commit(repo,sha)
    assets=shared.OUTPUT/'assets'
    metadata=verified_assets()
    ensure_unpublished(repo)
    for component in shared.IMAGES:
        shared.verify_checksums(shared.OUTPUT/component)
    token=os.environ.get('GH_TOKEN','')
    if not token:raise ValueError('GitHub publication token required')
    actor=os.environ.get('GITHUB_ACTOR',owner)
    shared.run('docker','login','ghcr.io','--username',actor,'--password-stdin',input=token)
    images={};outputs=[]
    try:
        for component,(local,name) in shared.IMAGES.items():
            image=f'ghcr.io/{owner}/{name}'
            target=image+':'+version
            shared.run('docker','load','-i',str(shared.OUTPUT/component/'image.tar'))
            shared.run('docker','tag',local,target);shared.run('docker','push',target)
            reference=shared.run('docker','inspect',target,'--format','{{index .RepoDigests 0}}',capture=True).stdout.strip()
            if not reference.startswith(image+'@sha256:') or not re.fullmatch(r'[0-9a-f]{64}',reference.split('@sha256:',1)[-1]):
                raise ValueError('Published image digest is invalid')
            images[component]=reference
            outputs.extend((component+'_image='+image,component+'_digest='+reference.split('@')[1]))
    finally:shared.run('docker','logout','ghcr.io')
    shared.run('helm','registry','login','ghcr.io','--username',actor,'--password-stdin',input=token)
    try:
        shared.run('helm','push',str(assets/f'olo-toolgate-{version}.tgz'),f'oci://ghcr.io/{owner}/charts')
    finally:shared.run('helm','registry','logout','ghcr.io')
    metadata['images']=images
    (assets/'tagged-release.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    checksum_assets()
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'],'a',encoding='utf-8') as stream:
            stream.write('\n'.join(outputs)+'\n')


def finalize():
    """Called only after the common publisher's required provenance steps succeed."""
    repo,sha,owner=shared.coordinates()
    tag=version_tag();version=tag[1:]
    verify_tag_commit(repo,sha)
    metadata=verified_assets()
    for component,(_,name) in shared.IMAGES.items():
        if not re.fullmatch(re.escape(f'ghcr.io/{owner}/{name}@sha256:')+r'[0-9a-f]{64}',metadata.get('images',{}).get(component,'')):
            raise ValueError('Tagged release is missing published image digests')
    ensure_unpublished(repo)
    assets=shared.OUTPUT/'assets'
    arguments=['gh','release','create',tag,*[str(p) for p in sorted(assets.iterdir()) if p.is_file()],
        '--repo',repo,'--verify-tag','--title','OLO ToolGate '+version,'--generate-notes',
        '--notes-file',str(shared.ROOT/'RELEASE-NOTES.md')]
    if '-' in version:arguments.append('--prerelease')
    shared.run(*arguments)
