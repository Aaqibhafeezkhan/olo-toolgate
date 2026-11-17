# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Current .olo profile format shared by repository builds and release verification."""
import hashlib
import io
import json
from pathlib import PurePosixPath
import re
import zipfile
from copy import deepcopy

MAX_PACKAGE=64*1024*1024
MAX_BUNDLE=128*1024*1024


def digest(data):return 'sha256:'+hashlib.sha256(data).hexdigest()


def encoded(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()


def inventory(data,maximum,expanded):
    if not 22<=len(data)<=maximum:raise ValueError('Archive size limit')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files={};size=0
        for entry in archive.infolist():
            name=entry.filename;parts=PurePosixPath(name).parts
            size+=entry.file_size
            if (not re.fullmatch(r'[A-Za-z0-9_./-]+',name) or name.startswith('/') or '..' in parts
                    or '.' in name.split('/') or '' in name.split('/') or name in files or len(files)>=1024
                    or (entry.external_attr>>16)&0o170000==0o120000 or size>expanded):raise ValueError('Unsafe archive inventory')
            files[name]=archive.read(entry)
        return files


def package(data):
    files=inventory(data,MAX_PACKAGE,128*1024*1024)
    checksums=files['CHECKSUMS.sha256'];listed=set()
    for line in checksums.decode('ascii').splitlines():
        match=re.fullmatch(r'([a-f0-9]{64})  ([A-Za-z0-9_./-]+)',line)
        if not match or match[2] in listed or digest(files[match[2]])!='sha256:'+match[1]:raise ValueError('Package checksum mismatch')
        listed.add(match[2])
    if set(files)-listed-{'CHECKSUMS.sha256'}-{name for name in files if name.startswith('signatures/')}:raise ValueError('Unlisted package content')
    manifest=json.loads(files['toolgate-package.json'])
    if manifest['schemaVersion']!=2:raise ValueError('Unsupported package format')
    identity=digest(checksums)
    return dict(packageId=manifest['id'],version=manifest['version'],packageDigest=identity,sha256=digest(data),bytes=len(data),path='packages/'+identity[7:]+'.olotool'),manifest


def pack(profile,references,connections,archives,build):
    profile=deepcopy(profile)
    payload={'referenced-data.json':encoded(references)}
    entries=[]
    for data in archives:
        item,_=package(data)
        if item['path'] in payload:raise ValueError('Duplicate package')
        payload[item['path']]=data;entries.append(item)
    if len(entries)>32:raise ValueError('Too many package archives')
    for pin in profile['packages']:
        matches=[item for item in entries if item['packageId']==pin['packageId'] and item['version']==pin['version']
                 and (not pin.get('packageDigest') or pin['packageDigest']==item['packageDigest'])]
        if len(matches)!=1:raise ValueError('Profile package needs its exact embedded archive: '+pin['packageId'])
        pin.update(source='BUNDLE',packageDigest=matches[0]['packageDigest'])
    payload['workflow.json']=encoded(profile)
    if len(payload['workflow.json'])>262144 or len(payload['referenced-data.json'])>2097152:raise ValueError('Configuration exceeds Control limits')
    manifest=dict(format='olo-workflow',version=2,workflowId=profile['workflows']['workflows'][0]['id'],
                  sha256=digest(payload['workflow.json']),referencesSha256=digest(payload['referenced-data.json']),
                  connections=connections,packages=sorted(entries,key=lambda row:row['packageDigest']),build=build)
    payload['manifest.json']=encoded(manifest)
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for name,data in sorted(payload.items()):
            info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            archive.writestr(info,data)
    data=output.getvalue()
    if len(data)>MAX_BUNDLE:raise ValueError('Profile bundle exceeds 128 MiB')
    verify(data)
    return data,manifest


def verify(data):
    files=inventory(data,MAX_BUNDLE,256*1024*1024)
    manifest=json.loads(files['manifest.json'])
    if manifest['format']!='olo-workflow' or manifest['version']!=2:raise ValueError('Unsupported profile format')
    if digest(files['workflow.json'])!=manifest['sha256'] or digest(files['referenced-data.json'])!=manifest['referencesSha256']:raise ValueError('Profile checksum mismatch')
    allowed={'manifest.json','workflow.json','referenced-data.json'}
    for item in manifest['packages']:
        if not re.fullmatch(r'packages/[a-f0-9]{64}\.olotool',item['path']) or item['path'] in allowed:raise ValueError('Invalid package path')
        if package(files[item['path']])[0]!=item:raise ValueError('Package inventory mismatch')
        allowed.add(item['path'])
    if set(files)!=allowed:raise ValueError('Unexpected bundle content')
    profile=json.loads(files['workflow.json'])
    for pin in profile['packages']:
        if pin.get('source')!='BUNDLE' or not any(item['packageId']==pin['packageId'] and item['version']==pin['version']
                and item['packageDigest']==pin.get('packageDigest') for item in manifest['packages']):raise ValueError('Profile package must use its exact embedded archive')
    if len(profile['workflows']['workflows'])!=1 or profile['workflows']['workflows'][0]['id']!=manifest['workflowId']:raise ValueError('Bundle must contain one workflow')
    return manifest
