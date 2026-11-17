# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Seal built packages into a generic release-image catalog."""
import json
from pathlib import Path
import shutil
import sys
from package_assets import describe


def seal(source,output):
    found=[]
    for artifact in sorted(source.glob('*/build/toolgate/*.olo')):
        metadata=describe(artifact)
        metadata['artifact']=artifact.parents[2].name+'.olo'
        found.append((metadata,artifact))
    if len(found)>64 or len({(p['id'],p['version']) for p,_ in found})!=len(found):
        raise ValueError('Package catalog must contain unique, bounded package identities')
    if len({p['artifact'] for p,_ in found})!=len(found):raise ValueError('Duplicate catalog artifact names')
    output.mkdir(parents=True,exist_ok=True)
    packages=[]
    for package,artifact in sorted(found,key=lambda row:(row[0]['id'],row[0]['version'])):
        shutil.copyfile(artifact,output/package['artifact'])
        packages.append(package)
    (output/'manifest.json').write_text(json.dumps(dict(formatVersion=2,packages=packages),indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':seal(Path(sys.argv[1]),Path(sys.argv[2]))
