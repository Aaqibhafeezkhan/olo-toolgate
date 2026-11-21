# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Collect exact first-party tool archives; promotion verifies rather than rebuilds."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from bot.bundle import MAX_BUNDLE,digest,inventory,package,verify
from bot.build import filename


def projects(root):
    examples={path.parent.relative_to(root).as_posix() for group in ('java-tools','mocked-tools')
              for path in (root/'examples'/group).glob('*/build.gradle.kts')}
    core=json.loads((root/'core-mcp/core-packages.build.json').read_text(encoding='utf-8'))['packages']
    return examples,core


def asset_name(metadata):
    if not isinstance(metadata.get('sha256'),str) or not re.fullmatch(r'sha256:[a-f0-9]{64}',metadata['sha256']):
        raise ValueError('Invalid tool archive hash')
    return filename(metadata['packageId'])+'-'+filename(metadata['version'])+'-'+metadata['sha256'][7:]+'.olotool'


def prepare(root,output,version,commit):
    examples,core=projects(root)
    archives={}
    def add(data,project=None):
        metadata,_=package(data)
        name=asset_name(metadata)
        if name not in archives:archives[name]=dict(metadata,filename=name,projects=[]),data
        if project:archives[name][0]['projects'].append(project)
    for project in sorted(examples):
        paths=list((root/project/'build/toolgate').glob('*.olotool'))
        if len(paths)!=1 or paths[0].is_symlink():raise ValueError('Exactly one built tool archive required: '+project)
        add(paths[0].read_bytes(),project)
    folder=root/'build/core-mcp'
    lock=json.loads((folder/'core-packages.lock.json').read_text(encoding='utf-8'))
    expected={(item['project'],item['id'],item['version']) for item in core}
    actual=[(item['project'],item['id'],item['version']) for item in lock['packages']]
    if len(actual)!=len(set(actual)) or set(actual)!=expected or lock['sdkVersion']!=version:
        raise ValueError('Incomplete or mismatched core tool inventory')
    for item in lock['packages']:
        path=folder/item['artifact']
        if path.parent!=folder or path.is_symlink():raise ValueError('Unsafe core tool archive')
        data=path.read_bytes();metadata,_=package(data)
        if (metadata['packageId'],metadata['version'],metadata['packageDigest'],metadata['sha256'][7:])!=(item['id'],item['version'],item['packageDigest'],item['artifactSha256']):
            raise ValueError('Core tool evidence mismatch')
        add(data,'core-mcp/'+item['project'])
    for path in sorted(output.glob('*.olo')):
        data=path.read_bytes();manifest=verify(data)
        files=inventory(data,MAX_BUNDLE,256*1024*1024)
        for item in manifest['packages']:add(files[item['path']])
    output.mkdir(parents=True,exist_ok=True)
    for name,(_,data) in archives.items():
        path=output/name
        if path.is_symlink() or path.exists() and path.read_bytes()!=data:raise ValueError('Conflicting tool release asset')
        path.write_bytes(data)
    index=dict(format='olo-tools',version=version,sourceCommit=commit,
               packages=[entry for _,(entry,_) in sorted(archives.items())])
    (output/('olo-tools-'+filename(version)+'.json')).write_text(json.dumps(index,indent=2)+'\n',encoding='utf-8')
    validate(output,version,commit,root)


def validate(folder,version,commit,root=ROOT):
    index=json.loads((folder/('olo-tools-'+filename(version)+'.json')).read_text(encoding='utf-8'))
    if index['format']!='olo-tools' or index['version']!=version or index['sourceCommit']!=commit:
        raise ValueError('Tool source/version differs from publication')
    examples,core=projects(root)
    expected=examples|{'core-mcp/'+item['project'] for item in core}
    selected=[project for row in index['packages'] for project in row['projects']]
    if len(selected)!=len(set(selected)) or set(selected)!=expected:
        raise ValueError('Release must include every first-party tool project exactly once')
    names=set();hashes=set()
    for row in index['packages']:
        name=asset_name(row)
        if row['filename']!=name or name in names:raise ValueError('Unsafe or duplicate tool asset name')
        path=folder/name
        if path.is_symlink():raise ValueError('Linked tool release asset')
        metadata,_=package(path.read_bytes())
        if any(row[key]!=value for key,value in metadata.items()):raise ValueError('Tool release evidence mismatch')
        names.add(name);hashes.add(metadata['sha256'])
    if names!={path.name for path in folder.glob('*.olotool')}:
        raise ValueError('Unlisted or missing tool release asset')
    for path in folder.glob('*.olo'):
        for item in verify(path.read_bytes())['packages']:
            if item['sha256'] not in hashes:raise ValueError('Embedded bot tool missing standalone release asset')
    return names


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--version',required=True);parser.add_argument('--commit',required=True)
    parser.add_argument('--verify-only',action='store_true')
    args=parser.parse_args()
    if args.verify_only:validate(args.output,args.version,args.commit)
    else:prepare(ROOT,args.output,args.version,args.commit)
