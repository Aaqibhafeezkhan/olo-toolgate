# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Operator lifecycle helpers: numbered selection and narrowly scoped destructive cleanup."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

NAME=re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}')
PREFIX='toolgate-workflow-'
PROJECT='com.docker.compose.project'
DIRECTORY='com.docker.compose.project.working_dir'
ROOT_LABEL='io.ololabs.toolgate.stack-root'


def device_hostname(root,selection):
    scope=os.path.normcase(str(root.resolve()))+'\0'+selection
    return 'workflow-'+hashlib.sha256(scope.encode()).hexdigest()[:24]


def choose(root,default,read=input,write=print):
    choices=sorted(path.name for path in (root/'configurations').iterdir()
                   if path.is_dir() and not path.is_symlink() and NAME.fullmatch(path.name)
                   and (path/'workflow-profile.json').is_file())
    if not choices:
        raise ValueError('No workflow configurations found')
    write('Available workflow configurations:')
    for number,name in enumerate(choices,1):
        write(f'  {number}. {name}'+(' (default)' if name==default else ''))
    selected=choices.index(default)+1 if default in choices else None
    while True:
        try:
            answer=read('Select configuration'+(f' [{selected}]' if selected else '')+': ').strip()
        except EOFError:
            raise ValueError('Configuration input is required; use --configuration for unattended startup') from None
        if not answer and selected:
            return choices[selected-1]
        if answer.isascii() and answer.isdigit() and 1<=int(answer)<=len(choices):
            return choices[int(answer)-1]
        write(f'Enter a serial number from 1 to {len(choices)}.')


def docker(*arguments):
    return subprocess.run(['docker',*arguments],check=True,capture_output=True,text=True).stdout.strip()


def objects(kind):
    listing=docker(kind,'ls',*(['--all'] if kind=='container' else []),
                   '--filter','label='+PROJECT,'--format','{{.ID}}' if kind!='volume' else '{{.Name}}')
    result=[]
    for identity in listing.splitlines():
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,255}',identity):
            raise ValueError('Invalid Docker resource identifier')
        template='{{json .Config.Labels}}' if kind=='container' else '{{json .Labels}}'
        labels=json.loads(docker(kind,'inspect',identity,'--format',template)) or {}
        result.append((identity,labels))
    return result


STORAGE_SCRIPT=r'''
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($env:WORKFLOW_CLEANUP_ROOT).TrimEnd('\')
$paths = @((ConvertFrom-Json $env:WORKFLOW_CLEANUP_PATHS))
foreach ($target in $paths) {
    $full = [IO.Path]::GetFullPath($target)
    if (-not $full.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Cleanup target leaves bundle' }
    $relative = $full.Substring($root.Length+1)
    if ($relative -notmatch '^(state|data)(\\[A-Za-z0-9][A-Za-z0-9_-]{0,63})?$') { throw 'Unsupported cleanup target' }
    $cursor = $full
    while ($cursor.Length -ge $root.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Cleanup refuses linked paths' }
        }
        if ($cursor -eq $root) { break }
        $cursor = Split-Path -Parent $cursor
    }
    if (Test-Path -LiteralPath $full) {
        $pending = New-Object 'System.Collections.Generic.Stack[string]'
        $pending.Push($full)
        while ($pending.Count) {
            foreach ($entry in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
                if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Cleanup refuses linked storage entries' }
                if ($entry.PSIsContainer) { $pending.Push($entry.FullName) }
            }
        }
    }
}
if ($env:WORKFLOW_CLEANUP_REMOVE -eq 'true') {
    foreach ($target in $paths) {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop }
    }
}
'''


def storage(root,selection,remove=False):
    root=root.resolve()
    if selection is not None and not NAME.fullmatch(selection):
        raise ValueError('Invalid cleanup configuration name')
    targets=[root/kind/selection if selection else root/kind for kind in ('state','data')]
    for target in targets:
        if not target.resolve().is_relative_to(root) or target.is_symlink():
            raise ValueError('Cleanup refuses paths outside the bundle or linked storage')
    if os.name=='nt':
        shell=shutil.which('pwsh') or shutil.which('powershell')
        if not shell:raise ValueError('PowerShell is required for Windows storage cleanup')
        subprocess.run([shell,'-NoProfile','-NonInteractive','-Command',STORAGE_SCRIPT],check=True,
            env=dict(os.environ,WORKFLOW_CLEANUP_ROOT=str(root),WORKFLOW_CLEANUP_PATHS=json.dumps(list(map(str,targets))),
                     WORKFLOW_CLEANUP_REMOVE=str(remove).lower()))
    else:
        for target in targets:
            for parent in (target,*target.parents):
                if parent==root:break
                if parent.is_symlink():raise ValueError('Cleanup refuses linked storage')
            if target.exists() and any(path.is_symlink() for path in target.rglob('*')):
                raise ValueError('Cleanup refuses linked storage entries')
        if remove:
            for target in targets:
                if target.exists():shutil.rmtree(target)


def destroy(root,selection=None):
    root=root.resolve()
    storage(root,selection)  # Verify every host target before removing any resource.
    containers,volumes,networks=objects('container'),objects('volume'),objects('network')
    local={path.name for kind in ('configurations','state') if (root/kind).is_dir()
           for path in (root/kind).iterdir() if path.is_dir() and NAME.fullmatch(path.name) and path.name!='prior-demo'}
    candidates={PREFIX+selection} if selection else {PREFIX+name for name in local}
    if not selection:
        for _,labels in (*containers,*volumes,*networks):
            project=labels.get(PROJECT,'')
            owner=labels.get(ROOT_LABEL) or labels.get(DIRECTORY)
            if (project.startswith(PREFIX) and NAME.fullmatch(project[len(PREFIX):]) and owner
                    and os.path.normcase(str(Path(owner).resolve()))==os.path.normcase(str(root))):
                candidates.add(project)
    owned=[]
    for identity,labels in containers:
        project=labels.get(PROJECT,'')
        if project not in candidates:continue
        directory=labels.get(DIRECTORY)
        if not directory or os.path.normcase(str(Path(directory).resolve()))!=os.path.normcase(str(root)):
            raise ValueError('Refusing a same-named Docker project owned by another directory')
        owned.append(identity)
    verified_projects={labels[PROJECT] for identity,labels in containers if identity in owned}
    for _,labels in (*volumes,*networks):
        if labels.get(PROJECT) not in candidates:continue
        owner=labels.get(ROOT_LABEL)
        if owner:
            if os.path.normcase(str(Path(owner).resolve()))!=os.path.normcase(str(root)):
                raise ValueError('Refusing Docker storage owned by another directory')
        elif labels.get(PROJECT) not in verified_projects:
            raise ValueError('Docker storage ownership cannot be verified; no resources were removed')
    # Read only public device hostname metadata. Native engine owner scopes are
    # endpoint+protected runtime directory, never a broad toolgate-* name match.
    selected_hosts=[]
    foreign_hosts=[]
    expected_hosts=[]
    for identity,labels in containers:
        if labels.get('com.docker.compose.service')!='linux-device':continue
        hostname=docker('container','inspect',identity,'--format','{{.Config.Hostname}}')
        (selected_hosts if identity in owned else foreign_hosts).append(hostname)
        if identity in owned:expected_hosts.append(device_hostname(root,labels[PROJECT][len(PREFIX):]))
    if set(selected_hosts)&set(foreign_hosts):
        raise ValueError('Native engine ownership overlaps another deployment; refusing ambiguous cleanup')
    if selected_hosts!=expected_hosts:
        raise ValueError('Native engine ownership cannot be verified for this bundle; no resources were removed')
    if owned:docker('container','stop','--time','30',*owned)
    for hostname in set(selected_hosts):
        owner=hashlib.sha256(('unix:///run/olo-toolgate-'+hostname+'-docker.sock:/var/lib/olo-toolgate/runtimes').encode()).hexdigest()
        children=docker('container','ls','--all','--filter','label=io.ololabs.toolgate.owner='+owner,'--format','{{.ID}}')
        if children:
            identities=children.splitlines()
            if any(not re.fullmatch(r'[a-f0-9]{12,64}',identity) for identity in identities):
                raise ValueError('Invalid native container identifier')
            docker('container','rm','--force','--volumes',*identities)
    if owned:docker('container','rm','--force','--volumes',*owned)
    for kind,resources in (('volume',volumes),('network',networks)):
        for identity,labels in resources:
            if labels.get(PROJECT) in candidates:docker(kind,'rm',identity)
    storage(root,selection,remove=True)
    print('Removed WorkflowStack '+(selection or 'deployments')+', Docker volumes, generated state and data.')
