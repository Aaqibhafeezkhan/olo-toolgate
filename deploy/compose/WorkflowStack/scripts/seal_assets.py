# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Seal profile-independent native packages into the release image at build time."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile


def seal(source,output):
    packages=[]
    for name in ('hotfolder','inbox-watch'):
        artifact=source/name/'build/toolgate'/(name+'-0.1.0.tgpkg')
        with zipfile.ZipFile(artifact) as archive:
            manifest=json.loads(archive.read('toolgate-package.json'))
            checksum=archive.read('CHECKSUMS.sha256')
        if manifest['id']!='examples.'+name or manifest['version']!='0.1.0':
            raise ValueError('Unexpected workflow package identity')
        packages.append(dict(id=manifest['id'],version=manifest['version'],artifact=name+'.tgpkg',
            artifactSha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
            packageDigest='sha256:'+hashlib.sha256(checksum).hexdigest(),sdkVersion=manifest['sdk']['version']))
    output.mkdir(parents=True,exist_ok=True)
    for package in packages:
        name=package['artifact'].removesuffix('.tgpkg')
        shutil.copyfile(source/name/'build/toolgate'/(name+'-0.1.0.tgpkg'),output/package['artifact'])
    (output/'manifest.json').write_text(json.dumps(dict(formatVersion=1,packages=packages),indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':seal(Path(sys.argv[1]),Path(sys.argv[2]))
