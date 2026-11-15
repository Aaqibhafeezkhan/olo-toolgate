# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Populate root-owned, read-only bootstrap material from immutable image assets."""
import hashlib
import json
import os
from pathlib import Path
import secrets


def atomic(path,data):
    if path.is_symlink() or path.exists() and (not path.is_file() or path.stat().st_uid!=0):
        raise ValueError('Unsupported bootstrap custody')
    temporary=path.with_name('.bootstrap-'+secrets.token_hex(12))
    try:
        with open(temporary,'xb',opener=lambda p,f:os.open(p,f,0o600)) as stream:
            stream.write(data);stream.flush();os.fsync(stream.fileno())
        temporary.chmod(0o644)
        os.replace(temporary,path)
    finally:temporary.unlink(missing_ok=True)


def initialize(assets,profile,output):
    declaration=json.loads(profile.read_bytes())
    if set(declaration)!={'profileVersion','profileId','packages'} or declaration['profileVersion']!=2:
        raise ValueError('Unsupported generated bootstrap profile')
    manifest=json.loads((assets/'manifest.json').read_bytes())
    if manifest.get('formatVersion')!=1:
        raise ValueError('Unsupported packaged workflow assets')
    package=next(item for item in manifest['packages'] if item['id']=='examples.inbox-watch')
    if package['artifact']!='inbox-watch.tgpkg':raise ValueError('Unexpected packaged trigger artifact')
    data=(assets/package['artifact']).read_bytes()
    if hashlib.sha256(data).hexdigest()!=package['artifactSha256']:
        raise ValueError('Packaged trigger digest mismatch')
    if output.is_symlink():raise ValueError('Linked bootstrap storage')
    output.mkdir(mode=0o755,exist_ok=True)
    marker=output/'format.json'
    if any(output.iterdir()) and not marker.is_file():
        raise ValueError('Unsupported bootstrap storage without format marker')
    if marker.is_symlink() or marker.exists() and json.loads(marker.read_bytes())!={'formatVersion':1}:
        raise ValueError('Unsupported bootstrap storage format')
    if output.stat().st_uid!=0 or output.stat().st_mode&0o022:
        raise ValueError('Bootstrap storage must be root-owned and non-writable to other users')
    atomic(marker,b'{"formatVersion":1}\n')
    for directory in (output,output/'core',output/'profile'):
        if directory.is_symlink():raise ValueError('Linked bootstrap storage')
        directory.mkdir(mode=0o755,exist_ok=True)
        if directory.stat().st_uid!=0 or directory.stat().st_mode&0o022:
            raise ValueError('Bootstrap storage must be root-owned and non-writable to other users')
    lock=dict(schemaVersion=1,sdkVersion=package['sdkVersion'],packages=[dict(
        project='examples/java-tools/inbox-watch',sdk='java-gradle',**{key:package[key] for key in
            ('id','version','artifact','artifactSha256','packageDigest')})])
    atomic(output/'core'/package['artifact'],data)
    atomic(output/'core/core-packages.lock.json',(json.dumps(lock,indent=2)+'\n').encode())
    atomic(output/'profile/trigger.json',(json.dumps(declaration,indent=2)+'\n').encode())
    atomic(marker,b'{"formatVersion":1}\n')


if __name__=='__main__':
    initialize(Path('/opt/toolgate/workflow-stack/packages'),Path('/input/profile.json'),Path('/bootstrap'))
