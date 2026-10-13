# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Publish development or tagged artifacts after the exact run's CI gates pass."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT/'build/development'
sys.path.insert(0,str(ROOT))
sys.path.insert(0,str(ROOT/'tools/client'))
from publication import public_asset
WORKFLOWS = {'control':'control.yml', 'gateway':'gateway.yml', 'quickstart':'quickstart.yml',
             'foundation':'foundation.yml', 'client':'client.yml'}
IMAGES = {'control':('olo-toolgate-control:module05','olo-toolgate-control'),
          'gateway':('olo-toolgate-gateway:module05','olo-toolgate-gateway'),
          'quickstart':('olo-toolgate-quickstart:module11','olo-toolgate-quickstart'),
          'device':('olo-toolgate-device:module11','olo-toolgate-device'),
          'secured_device':('olo-toolgate-linux-secured-device:module11','olo-toolgate-linux-secured-device')}


def run(*arguments, capture=False, input=None):
    return subprocess.run(arguments, cwd=ROOT, check=True, text=True,
                          capture_output=capture, input=input)


def api(path):
    return json.loads(run('gh','api',path,capture=True).stdout)


def validate_maven_repository():
    target=urlsplit(os.environ.get('MAVEN_REPOSITORY_URL',''))
    if target.scheme!='https' or not target.hostname or target.username or target.password or target.query or target.fragment:
        raise ValueError('Maven publication requires an HTTPS repository URL without embedded credentials')
    if target.hostname.lower() in ('central.sonatype.com','oss.sonatype.org','s01.oss.sonatype.org'):
        raise ValueError('Use a standard Maven repository such as GitHub Packages. Sonatype Central requires a separate Portal/GPG integration.')


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            checksum.update(block)
    return checksum.hexdigest()


def verify_checksums(folder):
    verified=set()
    for line in (folder/'SHA256SUMS').read_text().splitlines():
        expected, name = line.split(maxsplit=1)
        if not re.fullmatch(r'[A-Za-z0-9_.-]+',name) or digest(folder/name)!=expected:
            raise ValueError('Artifact checksum mismatch or unsafe filename')
        verified.add(name)
    if 'image.tar' not in verified:
        raise ValueError('Image archive must have a verified checksum')


def coordinates():
    sha=os.environ['RELEASE_SHA']; repo=os.environ['GITHUB_REPOSITORY']
    if not re.fullmatch(r'[0-9a-f]{40}',sha):raise ValueError('Invalid release SHA')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo):raise ValueError('Invalid release repository')
    if os.environ.get('GITHUB_REF','').startswith('refs/tags/'):
        from tools.release.tagged import version_tag
        version_tag()
        return repo,sha,repo.split('/')[0].lower()
    namespace=os.environ.get('DOCKERHUB_NAMESPACE') or os.environ.get('DOCKERHUB_USERNAME','')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*',namespace):raise ValueError('Set Docker Hub namespace/username')
    if not os.environ.get('DOCKERHUB_TOKEN'):raise ValueError('Set DOCKERHUB_TOKEN in development-release secrets')
    if not os.environ.get('DOCKERHUB_USERNAME'):raise ValueError('Set DOCKERHUB_USERNAME')
    return repo,sha,namespace


def unified_run(repo, sha, run_id):
    if not re.fullmatch(r'[0-9]+', str(run_id)):raise ValueError('Invalid unified CI run ID')
    workflow=api(f'repos/{repo}/actions/runs/{run_id}')
    ref=os.environ.get('GITHUB_REF','refs/heads/main')
    branch='main'
    if ref.startswith('refs/tags/'):
        from tools.release.tagged import version_tag,verify_tag_commit,ensure_unpublished
        branch=version_tag()
        verify_tag_commit(repo,sha)
        ensure_unpublished(repo)
    elif ref!='refs/heads/main':raise ValueError('Only main or an exact version tag can publish')
    if (workflow.get('head_sha')!=sha or workflow.get('head_branch')!=branch
            or workflow.get('path')!='.github/workflows/ci.yml'
            or workflow.get('event') not in ('push','workflow_dispatch')):
        raise ValueError('The unified CI run must match this branch/tag and checkout')
    jobs=api(f'repos/{repo}/actions/runs/{run_id}/jobs?filter=latest&per_page=100')['jobs']
    required={'admission / admit','preflight / metadata','foundation / check','clients / public-bundle','images / verify'}
    found={job['name'] for job in jobs if job.get('status')=='completed' and job.get('conclusion')=='success'}
    if not required<=found:raise ValueError('Every unified CI gate must complete successfully before release')
    targets={'x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu','x86_64-pc-windows-msvc','aarch64-pc-windows-msvc','x86_64-apple-darwin','aarch64-apple-darwin'}
    native=[job for job in jobs if job['name'].startswith('clients / native (')]
    if len(native)!=6 or any(job.get('status')!='completed' or job.get('conclusion')!='success' for job in native):
        raise ValueError('All six native platform gates must succeed before release')
    if any(not any(target in job['name'] for job in native) for target in targets):
        raise ValueError('A required native platform is missing')
    return dict.fromkeys(WORKFLOWS,int(run_id))


def prepare_development_java_assets():
    """Copy the checked Java distribution without changing versions or invoking Gradle."""
    from tools.release.tagged import verified_files
    folder=OUTPUT/'foundation/build/release'
    version=(ROOT/'packages/contracts/VERSION').read_text(encoding='utf-8').strip()
    required={f'toolgate-contracts-{version}{classifier}.jar' for classifier in ('','-sources','-javadoc')}
    required.update(f'toolgate-contracts-{version}.{extension}' for extension in ('pom','module'))
    if not required<=verified_files(folder):
        raise ValueError('Development release is missing checked foundation Java artifacts')
    for name in sorted(required):
        shutil.copyfile(folder/name,OUTPUT/'assets'/name)
    return version


def prepare(run_id=None):
    repo,sha,_=coordinates()
    if run('git','rev-parse','HEAD',capture=True).stdout.strip()!=sha:raise ValueError('Checkout SHA mismatch')
    tagged=os.environ.get('GITHUB_REF','').startswith('refs/tags/')
    if tagged and run_id is None:raise ValueError('Tagged publication requires a single verified CI run')
    selected=unified_run(repo,sha,run_id) if run_id is not None else {}
    deadline=time.monotonic()+1800
    while run_id is None:
        selected={}
        for name,workflow in WORKFLOWS.items():
            response=api(f'repos/{repo}/actions/workflows/{workflow}/runs?branch=main&event=push&per_page=100')
            matches=[r for r in response['workflow_runs'] if r['head_sha']==sha]
            if not matches:continue
            latest=max(matches,key=lambda r:r['id'])
            if latest['status']=='completed':
                if latest['conclusion']!='success':raise ValueError(f'{name} CI gate failed for release SHA')
                selected[name]=latest['id']
        if len(selected)==len(WORKFLOWS):break
        if time.monotonic()>deadline:raise TimeoutError('All release CI gates must complete successfully')
        print('Waiting for same-commit CI gates',flush=True);time.sleep(15)
    OUTPUT.mkdir(parents=True,exist_ok=True)
    assets=OUTPUT/'assets';assets.mkdir()
    for name,artifact in [('control','control-evidence'),('gateway','gateway-evidence'),
                          ('quickstart','quickstart-evidence'),('foundation','foundation-evidence'),
                          ('client','public-client-bundle')]:
        run('gh','run','download',str(selected[name]),'--repo',repo,'--name',artifact,'--dir',str(OUTPUT/name))
    run('gh','run','download',str(selected['quickstart']),'--repo',repo,'--name','device-evidence','--dir',str(OUTPUT/'device'))
    run('gh','run','download',str(selected['quickstart']),'--repo',repo,'--name','secured-device-evidence','--dir',str(OUTPUT/'secured_device'))
    run(sys.executable,'tools/client/manifest.py','--source',str(OUTPUT/'client'),'--output',str(assets))
    if not (assets/'installers.json').exists():raise ValueError('Native installers required for release')
    # ZIP archives remain CI build inputs, never public Windows installation assets.
    for path in assets.iterdir():
        if not public_asset(path):path.unlink()
    manifest=json.loads((assets/'manifest.json').read_text())
    if len(manifest['artifacts'])!=6:raise ValueError('All six native OS/architecture packages are required')
    for component in IMAGES:
        folder=OUTPUT/component;verify_checksums(folder)
        for path in folder.glob('*sbom.cdx.json'):shutil.copyfile(path,assets/path.name)
    contracts=list((OUTPUT/'foundation').rglob('olo-toolgate-contracts-*.tar.gz'))
    if len(contracts)!=1:raise ValueError('Exactly one validated raw contract bundle is required')
    for path in contracts:
        shutil.copyfile(path,assets/path.name)
    if tagged:
        from tools.release.tagged import prepare_assets
        prepare_assets(selected)
        return
    java_version=prepare_development_java_assets()
    base=java_version.split('-')[0]
    version=f"{base}-dev.{os.environ['GITHUB_RUN_ID']}.g{sha[:12]}"
    (OUTPUT/'MAVEN_VERSION').write_text(version+'\n')
    (assets/'development-release.json').write_text(json.dumps({'commit':sha,'ciRuns':selected,
        'mavenVersion':version,'javaAssetVersion':java_version,'binaryVersion':manifest['version'],'releaseTag':'dev-'+sha+'-'+os.environ['GITHUB_RUN_ID'],
        'dockerTag':'dev-'+sha[:12]},indent=2)+'\n')


def publish():
    if os.environ.get('GITHUB_REF','').startswith('refs/tags/'):
        from tools.release.tagged import publish
        publish()
        return
    repo,sha,namespace=coordinates();assets=OUTPUT/'assets'
    metadata=json.loads((assets/'development-release.json').read_text())
    if metadata['commit']!=sha:raise ValueError('Prepared SHA mismatch')
    run('docker','login','--username',os.environ['DOCKERHUB_USERNAME'],'--password-stdin',input=os.environ['DOCKERHUB_TOKEN'])
    current=api(f'repos/{repo}/git/ref/heads/main')['object']['sha']==sha
    images={}
    try:
        for component,(local,name) in IMAGES.items():
            folder=OUTPUT/component;verify_checksums(folder)
            run('docker','load','-i',str(folder/'image.tar'))
            target=f'{namespace}/{name}:dev-{sha[:12]}'
            run('docker','tag',local,target);run('docker','push',target)
            images[component]=run('docker','inspect',target,'--format','{{index .RepoDigests 0}}',capture=True).stdout.strip()
            if current:
                run('docker','tag',target,f'{namespace}/{name}:dev');run('docker','push',f'{namespace}/{name}:dev')
    finally:run('docker','logout')
    metadata['images']=images
    (assets/'development-release.json').write_text(json.dumps(metadata,indent=2)+'\n')
    for document in ('LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md'):
        shutil.copyfile(ROOT/document, assets/document)
    checksums=''.join(f'{digest(path)}  {path.name}\n' for path in sorted(assets.iterdir()) if path.is_file() and path.name!='SHA256SUMS')
    (assets/'SHA256SUMS').write_text(checksums)
    notes=OUTPUT/'notes.md'
    notes.write_text(f'Development artifacts for commit `{sha}`.\n\nMaven: `io.ololabs.toolgate:toolgate-contracts:{metadata["mavenVersion"]}`.\n\nSix native client packages retain the tested source version `{metadata["binaryVersion"]}`; their commit identity is recorded in development-release.json.\n\nContainer digests and checksums are included.\n')
    notes.write_text(notes.read_text()+'\n'+(assets/'RELEASE-NOTES.md').read_text(), encoding='utf-8', newline='\n')
    tag=metadata['releaseTag']
    # One immutable prerelease per publishing run; no clobber of an existing release.
    run('gh','release','create',tag,*[str(p) for p in sorted(assets.iterdir()) if p.is_file()],
        '--repo',repo,'--target',sha,'--prerelease','--latest=false','--generate-notes','--title','ToolGate development '+sha[:12],
        '--notes-file',str(notes))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('operation',choices=('prepare','publish','publish-maven','finalize','validate-maven'));parser.add_argument('--run-id',type=int);args=parser.parse_args()
    if args.operation=='prepare':prepare(args.run_id)
    else:
        if args.run_id is not None:parser.error('--run-id applies only to prepare')
        if args.operation=='publish':publish()
        elif args.operation=='validate-maven':validate_maven_repository()
        else:
            from tools.release import tagged
            if args.operation=='publish-maven':tagged.publish_maven()
            else:tagged.finalize()
