# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Deterministic Chrome MV3 release package, shared identity with the Windows native host."""
import argparse
import hashlib
import json
import os
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'apps/browser-extension'

def package(output, build=0):
    if type(build) is not int or not 0 <= build <= 65535:
        raise ValueError('Chrome version components must fit 0..65535')
    version = (ROOT / 'VERSION').read_text(encoding='utf-8').strip()
    release = json.loads((ROOT/'release.json').read_text()) if (ROOT/'release.json').is_file() else None
    if release:
        if release['version'] != version:
            raise ValueError('Extension release identity differs from product version')
        build = release['build']
    core = version.split('-')[0].split('.')
    if len(core) != 3 or any(not x.isdigit() or int(x)>65535 for x in core) or not 0<=build<=65535:
        raise ValueError('Chrome version components must fit 0..65535')
    identity = json.loads((SOURCE/'identity.json').read_text(encoding='utf-8'))
    chrome_version = '.'.join(core+[str(build)])
    manifest = json.loads((SOURCE/'manifest.json').read_text(encoding='utf-8'))
    manifest.update(version=chrome_version,version_name=version,key=identity['key'])
    name=f'olo-toolgate-chrome-{version}-{build}.zip'
    output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output/name,'w',zipfile.ZIP_DEFLATED) as archive:
        files={p.name:p.read_bytes() for p in SOURCE.iterdir() if p.suffix in ('.js','.html','.css')}
        files.update({'manifest.json':(json.dumps(manifest,sort_keys=True,indent=2)+'\n').encode(),
                      'LICENSE':(ROOT/'LICENSE').read_bytes(),'NOTICE.md':(ROOT/'NOTICE.md').read_bytes(),
                      'RELEASE-NOTES.md':(ROOT/'RELEASE-NOTES.md').read_bytes(),
                      'README.md':(ROOT/'docs/client/connect.md').read_bytes()})
        sbom={'bomFormat':'CycloneDX','specVersion':'1.5','version':1,'metadata':{'component':{'type':'application','name':'ToolGate Connect','version':version,'licenses':[{'expression':'Apache-2.0'}]}},'components':[]}
        files['sbom.cdx.json']=(json.dumps(sbom,sort_keys=True,indent=2)+'\n').encode()
        for filename,data in sorted(files.items()):
            info=zipfile.ZipInfo(filename,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o644<<16;archive.writestr(info,data)
    path=output/name;digest=hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_name(name+'.sha256').write_text(digest+'  '+name+'\n',encoding='utf-8')
    listing=os.environ.get('TOOLGATE_CHROME_STORE_URL','')
    if listing and listing != 'https://chromewebstore.google.com/detail/'+identity['id']:
        raise ValueError('Store URL must refer to this extension identity')
    metadata={'protocol':1,'version':version,'chromeVersion':chrome_version,'extensionId':identity['id'],
              'storeUrl':listing,'filename':name,'sha256':digest,'bytes':path.stat().st_size}
    (output/'extension.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    return metadata

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'deploy/client-assets/release')
    build=0 if (ROOT/'release.json').is_file() else int(subprocess.check_output(['git','rev-list','--count','HEAD'],cwd=ROOT)) if os.environ.get('GITHUB_ACTIONS')=='true' else 0
    parser.add_argument('--build',type=int,default=build)
    args=parser.parse_args();package(args.output,args.build)
