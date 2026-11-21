# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Retain verified profile bundle bytes and version details as public release assets."""
import argparse
import json
from pathlib import Path
import re
import shutil
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from bot.bundle import digest,verify


def expected_profiles():
    return {(folder.name,workflow['id'])
            for folder in (ROOT/'bot/configurations').iterdir() if folder.is_dir()
            for workflow in json.loads((folder/'bot.json').read_text(encoding='utf-8'))['workflows']['workflows']}


def collect(source,output,version=None,commit=None):
    indexes=list(source.glob('olo-profiles-*.json'))
    if len(indexes)>1 and version:indexes=[path for path in indexes if json.loads(path.read_text(encoding='utf-8')).get('version')==version]
    if len(indexes)!=1:raise ValueError('Exactly one tested profile inventory is required')
    inventory=json.loads(indexes[0].read_text(encoding='utf-8'))
    if inventory['format']!='olo-profiles' or not inventory['profiles']:raise ValueError('Invalid profile inventory')
    if version and inventory['version']!=version or commit and inventory['sourceCommit']!=commit:raise ValueError('Profile source/version differs from publication')
    identities=[(item.get('configuration'),item.get('workflowId')) for item in inventory['profiles']]
    if len(set(identities))!=len(identities) or set(identities)!=expected_profiles():
        raise ValueError('Release must include every bot configuration/workflow exactly once')
    files=[indexes[0]];seen=set()
    for item in inventory['profiles']:
        name=item['filename']
        if not re.fullmatch(r'[A-Za-z0-9_.+-]+\.olo',name) or name in seen:raise ValueError('Unsafe or duplicate profile filename')
        path=source/name
        if path.is_symlink():raise ValueError('Linked profile artifact')
        data=path.read_bytes();manifest=verify(data)
        if (manifest['workflowId']!=item['workflowId'] or digest(data)!=item['sha256'] or len(data)!=item['bytes'] or manifest['packages']!=item['packages']
                or manifest['build']['version']!=inventory['version'] or manifest['build']['sourceCommit']!=inventory['sourceCommit']):raise ValueError('Profile evidence mismatch')
        seen.add(name);files.append(path)
    output.mkdir(parents=True,exist_ok=True)
    for path in files:
        target=output/path.name
        if target.exists() and target.read_bytes()!=path.read_bytes():raise ValueError('Conflicting profile release asset')
        shutil.copyfile(path,target)
    return [path.name for path in files]


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--version');parser.add_argument('--commit');args=parser.parse_args()
    print('Collected profiles: '+', '.join(collect(args.source,args.output,args.version,args.commit)))
