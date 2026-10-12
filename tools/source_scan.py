# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Scan all release source and untracked contributor source in an isolated Linux snapshot.

Git's source inventory excludes ignored dependency caches/build outputs. Actual
runtime artifacts have separate image/SBOM scans. Every selected working file is
copied, including modifications. Frozen compatibility dependency findings are
reported separately; active dependency and all license findings remain blocking.
"""
import hashlib
import json
import subprocess
import sys
from contextlib import contextmanager
import tarfile
import tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
# Exact immutable evidence, not a directory exclusion. Changed/new manifests
# remain blocking, and no runtime or license finding is exempted.
FROZEN_MANIFESTS={
    'tool-sdk/spec/sdk-compat/2026-10-10/pins/java/pom.xml': '7cf421828127e135a438ab40dc5ed1bd28a76090ec454a497d6d18f5f11ad8db',
    'tool-sdk/spec/sdk-compat/2026-10-10/pins/python/requirements.txt': 'd5c1605b4cef2ec930eb74c7eb402ee25713a31e160d4ffe41dbf636b087d8e2',
    'tool-sdk/spec/sdk-compat/2026-10-10/pins/dotnet/packages.lock.json': '4be1899fecb965ecd9f7559187d8c47f1985f5014c05450a1c768e937e1a03e0',
    'tool-sdk/spec/sdk-compat/2026-10-10/pins/typescript/package-lock.json': '8eb5fa030de95c035f8ebb931de2903d6c428cc20ecd835c7261bd406fb1335d',
}


@contextmanager
def snapshot():
    names=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode('utf-8').split('\0')
    (ROOT/'.dev').mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='source-scan-',dir=ROOT/'.dev') as temporary:
        archive=Path(temporary)/'source.tar'
        count=0
        with tarfile.open(archive,'w') as output:
            for name in sorted(set(names)-{''}):
                path=ROOT/name
                if not path.exists():continue  # Working-tree deletions are absent from the release.
                if not path.resolve().is_relative_to(ROOT.resolve()) or not path.is_file():
                    raise ValueError('Source snapshot path must be a workspace file')
                # Preserve source bytes but never produce symlink/traversal entries.
                with path.open('rb') as content:
                    entry=tarfile.TarInfo(Path(name).as_posix());entry.size=path.stat().st_size
                    entry.mode=0o644;entry.mtime=0;output.addfile(entry,content)
                count+=1
        print(f'Scanning {count} working source files; Linux snapshot avoids slow Windows mount traversal',flush=True)
        yield archive


def secret_scan():
    # Include tracked modifications and untracked source. Ignore private operator
    # custody only through Git's inventory, so accidentally tracked keys still fail.
    with snapshot() as archive:
        command='mkdir /source && tar -xf /source.tar -C /source && exec gitleaks detect --source=/source --no-git --redact --exit-code=1'
        subprocess.run(['docker','run','--rm','-v',f'{archive.as_posix()}:/source.tar:ro',
                        '--entrypoint','sh','zricethezav/gitleaks:v8.24.2','-c',command],cwd=ROOT,check=True)


def historical_targets(archive):
    """Classify only the approved bytes from the snapshot actually scanned."""
    targets=set()
    with tarfile.open(archive) as source:
        for target,expected in FROZEN_MANIFESTS.items():
            try:entry=source.getmember(target)
            except KeyError:continue
            if entry.isfile() and hashlib.sha256(source.extractfile(entry).read()).hexdigest()==expected:
                targets.add(target)
    return targets


def split_findings(report, historical):
    if report.get('SchemaVersion')!=2 or not isinstance(report.get('Results'),list):
        raise ValueError('Missing or unsupported Trivy source report')
    blocking=[];archived=[]
    for result in report['Results']:
        if not isinstance(result,dict) or not isinstance(result.get('Target'),str):
            raise ValueError('Malformed Trivy source result')
        target=result['Target']
        groups={}
        for key in ('Vulnerabilities','Licenses'):
            findings=result.get(key)
            if findings is None:findings=[]
            if not isinstance(findings,list) or any(not isinstance(f,dict) or
                    f.get('Severity') not in ('UNKNOWN','LOW','MEDIUM','HIGH','CRITICAL') for f in findings):
                raise ValueError('Malformed Trivy source findings')
            groups[key]=[f for f in findings if f['Severity'] in ('HIGH','CRITICAL')]
        vulnerabilities=groups['Vulnerabilities']
        if target in historical and vulnerabilities:
            archived.append(dict(Target=target,Type=result.get('Type'),Vulnerabilities=vulnerabilities))
            vulnerabilities=[]
        if vulnerabilities or groups['Licenses']:
            blocking.append(dict(Target=target,Type=result.get('Type'),
                                 Vulnerabilities=vulnerabilities,Licenses=groups['Licenses']))
    return blocking,archived


def scan():
    with snapshot() as archive:
        command='mkdir /source && tar -xf /source.tar -C /source && exec trivy fs --no-progress --timeout 15m --include-dev-deps --scanners vuln,license --license-full --format json --exit-code 0 --severity HIGH,CRITICAL /source'
        result=subprocess.run(['docker','run','--rm','-v',f'{archive.as_posix()}:/source.tar:ro','--entrypoint','sh','aquasec/trivy:0.61.1','-c',command],
                              cwd=ROOT,capture_output=True,text=True,encoding='utf-8')
        print(result.stderr,file=sys.stderr,end='',flush=True)
        result.check_returncode()
        report=json.loads(result.stdout)
        blocking,archived=split_findings(report,historical_targets(archive))
    output=ROOT/'build/source-scan';output.mkdir(parents=True,exist_ok=True)
    for name,document in (('full',report),('blocking',dict(SchemaVersion=2,Results=blocking)),
                          ('historical',dict(SchemaVersion=2,Results=archived))):
        (output/(name+'.json')).write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
    for category,results in (('HISTORICAL (reported, nonblocking)',archived),('BLOCKING',blocking)):
        for result in results:
            for key in ('Vulnerabilities','Licenses'):
                for finding in result.get(key,[]):
                    identity=finding.get('VulnerabilityID') or finding.get('Name') or 'license'
                    print(f'{category}: {result["Target"]}: {identity} ({finding["Severity"]})',flush=True)
    print(f'Source scan: {len(blocking)} blocking targets, {len(archived)} historical targets; reports: {output}',flush=True)
    if blocking:raise RuntimeError('HIGH/CRITICAL active dependency or license findings block publication')


if __name__=='__main__':scan()
