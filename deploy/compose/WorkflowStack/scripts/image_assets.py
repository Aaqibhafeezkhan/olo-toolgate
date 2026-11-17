# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Read immutable, profile-independent deployment assets from the selected image."""
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess


def command(arguments):
    result=subprocess.run(['docker',*arguments],capture_output=True,text=True)
    if result.returncode:raise ValueError('Selected Quickstart image lacks packaged workflow assets; build/pull the current image')
    return result.stdout.strip()


def validate(root):
    manifest=json.loads((root/'packages/manifest.json').read_text(encoding='utf-8'))
    packages=manifest.get('packages')
    if (manifest.get('formatVersion')!=2 or not isinstance(packages,list) or len(packages)>64
            or len({(p['id'],p['version']) for p in packages})!=len(packages)):
        raise ValueError('Unsupported packaged workflow asset manifest')
    for package in manifest['packages']:
        name=package['artifact']
        if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\.olotool',name):
            raise ValueError('Unsupported packaged artifact path')
        path=root/'packages'/name
        if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=package['artifactSha256']:
            raise ValueError('Packaged workflow artifact digest mismatch')
    for relative in ('examples/langchain/manage.py','examples/langchain/packages.py','debug/profiled/configure-debug-agent.py',
                     'debug/profiled/agent-mimic.py','tools/access/presets.py','packages/contracts/management-permissions.json','VERSION'):
        if not (root/'operator'/relative).is_file():raise ValueError('Incomplete image-provided workflow operator')


def extract(image,state):
    identity=command(['image','inspect',image,'--format','{{.Id}}'])
    if not re.fullmatch(r'sha256:[a-f0-9]{64}',identity):raise ValueError('Invalid selected image identity')
    destination=state/'image-assets'/identity.removeprefix('sha256:')
    if destination.exists():
        validate(destination)
        return destination
    temporary=state/('.image-assets-'+secrets.token_hex(8))
    temporary.mkdir(mode=0o700)
    container=command(['create','--network','none','--entrypoint','/bin/true',identity])
    try:
        for name in ('packages','operator'):
            command(['cp',container+':/opt/toolgate/workflow-stack/'+name,str(temporary/name)])
        validate(temporary)
    finally:command(['rm','--volumes',container])
    destination.parent.mkdir(mode=0o700,exist_ok=True)
    temporary.rename(destination)
    return destination
