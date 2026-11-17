# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Resolve a profile-selected package without executing package code on the operator."""
import hashlib
import json
from pathlib import Path
import re
import zipfile


def describe(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size>64*1024*1024:
        raise ValueError('Package assets must be confined regular files, at most 64 MiB')
    with zipfile.ZipFile(path) as archive:
        if archive.getinfo('toolgate-package.json').file_size>65536 or archive.getinfo('CHECKSUMS.sha256').file_size>1024*1024:
            raise ValueError('Unbounded package metadata')
        manifest=json.loads(archive.read('toolgate-package.json'))
        checksums=archive.read('CHECKSUMS.sha256')
    identity,version=manifest['id'],manifest['version']
    if not isinstance(identity,str) or not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,127}',identity):
        raise ValueError('Invalid package identity')
    if not isinstance(version,str) or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?',version):
        raise ValueError('Invalid package version')
    return dict(id=identity,version=version,artifact=path.name,
        artifactSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        packageDigest='sha256:'+hashlib.sha256(checksums).hexdigest(),sdkVersion=manifest['sdk']['version'])


def select(source,configuration,assets):
    directory=configuration/'packages'
    if directory.is_symlink():raise ValueError('Linked configuration package directory')
    local=[]
    if directory.exists():
        for path in sorted(directory.glob('*.olotool')):
            metadata=describe(path)
            if (metadata['id'],metadata['version'])==(source['packageId'],source['version']):local.append((metadata,path))
    if local:
        if len(local)!=1 or source.get('packageDigest')!=local[0][0]['packageDigest']:
            raise ValueError('Local trigger package requires one exact profile-pinned package digest')
        return local[0]
    manifest=json.loads((assets/'packages/manifest.json').read_text(encoding='utf-8'))
    matches=[item for item in manifest['packages'] if (item['id'],item['version'])==(source['packageId'],source['version'])]
    if len(matches)!=1:raise ValueError('Selected trigger package is missing; place its pinned .olotool in the configuration packages folder')
    metadata=matches[0]
    if source.get('packageDigest',metadata['packageDigest'])!=metadata['packageDigest']:
        raise ValueError('Selected trigger package digest does not match the profile')
    return metadata,assets/'packages'/metadata['artifact']
