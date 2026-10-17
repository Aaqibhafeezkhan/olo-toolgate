# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicit local debug provisioning; the mimic command itself never changes access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = 'toolgate-debug-quickstart-1'
ORIGIN = 'http://127.0.0.1:18090'
sys.path.insert(0,str(ROOT/'tools/access'))
from presets import load as load_configuration


def private_file(path, value=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink() or path.parent.is_symlink(): raise ValueError('Credential custody requires regular private files')
    if not path.exists(): path.write_text('', encoding='utf-8')
    if os.name == 'nt':
        result = subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-Command',
            "$ErrorActionPreference='Stop';$user=[System.Security.Principal.WindowsIdentity]::GetCurrent().User;"
            "$acl=New-Object System.Security.AccessControl.FileSecurity;$acl.SetAccessRuleProtection($true,$false);"
            "foreach($sid in @($user,(New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18')))){"
            "$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow')))};"
            "[System.IO.File]::SetAccessControl($env:TOOLGATE_DEBUG_CREDENTIAL_FILE,$acl)"],
            env={**{k:v for k,v in os.environ.items() if k.casefold()!='psmodulepath'},
                 'TOOLGATE_DEBUG_CREDENTIAL_FILE':str(path)}, capture_output=True)
        if result.returncode: raise ValueError('Cannot protect local debug credentials')
    else: path.chmod(0o600)
    if value is not None: path.write_text(value, encoding='utf-8')


def docker_script(script, stdin=None):
    result = subprocess.run(['docker', 'exec', '-i', CONTAINER,
        '/opt/quickstart-python/bin/python', '-c', script], input=stdin,
        capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Local debug container operation failed (private output withheld)')
    return result.stdout.strip()


class Configuration:
    def __init__(self, local_fixture_reviewers=False, origin=ORIGIN, credential_directory=None):
        self.origin = origin.rstrip('/')
        self.credential_directory = credential_directory or ROOT/'.dev/debug'
        self.tokens = {}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.username = os.environ.get('TOOLGATE_ADMIN_USERNAME','admin')
        self.login('admin', self.username, os.environ.get('TOOLGATE_ADMIN_PASSWORD',''))
        for index in (1,2):
            user = os.environ.get(f'TOOLGATE_REVIEWER{index}_USERNAME',f'reviewer-{index}')
            password = os.environ.get(f'TOOLGATE_REVIEWER{index}_PASSWORD')
            if password: self.login('reviewer-'+str(index),user,password)
            elif local_fixture_reviewers: self.fixture_login(user,'reviewer-'+str(index))

    def login(self, key, username, password, replacement=None):
        body = dict(username=username,password=password)
        if replacement is not None: body['newPassword']=replacement
        request = urllib.request.Request(self.origin+'/api/quickstart/v1/login',json.dumps(body).encode(),
            {'Content-Type':'application/json'},method='POST')
        try:
            with self.opener.open(request,timeout=30) as response: self.tokens[key]=json.load(response)['accessToken']
        except urllib.error.HTTPError as error:
            raise ValueError(f'API login failed for {username}: HTTP {error.code}; supply valid credentials (and complete first-login password change)') from None

    def fixture_login(self, user, key):
        # Explicit local fixture mode uses password authentication, never signing keys or stored verifiers.
        if user not in ('reviewer-1','reviewer-2','test-readonly','test-readwrite','test-admin'):
            raise ValueError('Unknown local fixture identity')
        path = self.credential_directory/'local-test-credentials.json'
        private_file(path)
        saved = json.loads(path.read_text() or '{}')
        initial = docker_script("from pathlib import Path;p=Path('/data/bootstrap-password-"+user+"');print(p.read_text().strip() if p.exists() else '')")
        if initial:
            password = secrets.token_urlsafe(32)
            saved[user] = password
            private_file(path,json.dumps(saved))  # Secure custody before changing the server password.
            self.login(key,user,initial,password)
        elif user in saved: self.login(key,user,saved[user])
        else: raise ValueError('Local fixture password was already changed; supply its credential explicitly')

    def api(self, path, body=None, method=None, user='admin', revision=None, with_status=False):
        headers = {'Authorization': 'Bearer '+self.tokens[user],
                   'Content-Type': 'application/json', 'X-Request-ID': secrets.token_hex(16)}
        if body is not None or method=='DELETE': headers['Idempotency-Key'] = secrets.token_hex(16)
        if revision is not None: headers['If-Match'] = '"'+str(revision)+'"'
        request = urllib.request.Request(self.origin+path,
            json.dumps(body).encode() if body is not None else None, headers,
            method=method or ('POST' if body is not None else 'GET'))
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read(1048576)
                document = json.loads(data) if data else None
                return (response.status, document) if with_status else document
        except urllib.error.HTTPError as error:
            raise ValueError(f'Debug configuration API rejected {method or "GET"} {path}: HTTP {error.code}') from None

    def upsert(self, collection, document):
        existing = next((i for i in self.all(collection) if i['id']==document['id']), None)
        path = '/api/control/v1/'+collection
        if existing:
            document = {**document, 'revision': existing['revision']}
            if document == existing: return
            path += '/'+document['id']
        self.mutate(path, document, 'PUT' if existing else 'POST', revision=existing['revision'] if existing else None)
        print('Configured '+collection+': '+document['id'], flush=True)

    def mutate(self, path, body=None, method=None, revision=None):
        status, document = self.api(path, body, method, revision=revision, with_status=True)
        if status == 202:
            self.apply_change(document)
        elif status not in (200, 201, 204):
            raise ValueError('Unexpected configuration mutation response: HTTP '+str(status))

    def all(self, collection):
        result=[];cursor='';seen=set()
        while True:
            page=self.api('/api/control/v1/'+collection+'?limit=100'+('&cursor='+cursor if cursor else ''))
            result.extend(page['items']);cursor=page.get('nextCursor')
            if not cursor:return result
            if cursor in seen or len(result)>512:raise ValueError('Directory pagination limit exceeded')
            seen.add(cursor)

    def apply_change(self, change):
        def transition(action, user='admin'):
            return self.api('/api/control/v1/configuration-changes/'+change['id']+'/transition',
                {'action':action,'expectedRevision':change['revision']}, user=user)
        change = transition('SUBMIT')
        reviewers=('reviewer-1','reviewer-2')[:change['requiredReviews']]
        if all(user in self.tokens for user in reviewers):
            for user in reviewers: change = transition('APPROVE', user)
        else:
            print('Independent review required: '+self.origin+'/console/#config-reviews?id='+change['id'],flush=True)
            deadline=time.monotonic()+600
            while change['state']=='PENDING' and time.monotonic()<deadline:
                time.sleep(2);change=self.api('/api/control/v1/configuration-changes/'+change['id'])
            if change['state']!='APPROVED':raise ValueError('Configuration was not independently approved; no allocation was applied')
        change = transition('APPLY')
        if change['state'] != 'APPLIED': raise ValueError('Reviewed configuration did not apply')

    def memberships(self, collection, id, groups):
        current=self.api('/api/control/v1/'+collection+'/'+id+'/groups')
        if sorted(current['groupIds'])==sorted(groups):return
        self.mutate('/api/control/v1/'+collection+'/'+id+'/groups',
            {**current,'groupIds':groups},'PUT',revision=current['revision'])
        print('Configured group allocation: '+id+' -> '+', '.join(groups),flush=True)

    def presets(self):
        snapshot=self.api('/api/control/v1/config/export');records=load_configuration()['records'];changed=False
        for collection, values in records.items():
            existing={x['id'] for x in snapshot[collection]}
            additions=[v for v in values if v['id'] not in existing]
            snapshot[collection].extend(additions);changed|=bool(additions)
        if changed:self.mutate('/api/control/v1/config/import',
            dict(snapshot=snapshot,mode='MERGE',dryRun=False),revision=snapshot['revision'])

    def delete(self, collection, id):
        old=next((x for x in self.all(collection) if x['id']==id),None)
        if old:self.mutate('/api/control/v1/'+collection+'/'+id,method='DELETE',revision=old['revision'])

    def test_users(self):
        for user, tier in (('test-readonly','ReadOnly'),('test-readwrite','ReadAndWrite'),('test-admin','Admin')):
            self.upsert('users',dict(id=user,name='Quickstart '+user,enabled=True,revision=1))
            if not any(x['userId']==user for x in self.all('identity-bindings')):
                now=int(time.time()*1000)
                self.upsert('identity-bindings',dict(id='installed-'+user,name='Reviewed local test identity',enabled=True,
                    revision=1,userId=user,issuer='https://quickstart.local',subject=user,sessionEpoch=1,
                    firstSeenUnixMs=now,lastAttemptUnixMs=now,attemptCount=1,registrationReason='REVIEWED_DEBUG',sessionsValidAfterUnixMs=0))
            self.memberships('users',user,[tier+'Team'])
            self.fixture_login(user,user)


# The HotFolder package's tools the mimic calls. They exist only once the package is installed and released to the
# device (ADR 026/027); this helper never creates or changes a tool definition.
MIMIC_TOOLS = ('examples.hotfolder/write_text', 'examples.hotfolder/read_text')


def configure(device, local_fixture_reviewers=False, group='ReadAndWriteAgentGroup'):
    # Verify private token custody before publishing any rotated workload credential.
    path = ROOT/'.dev/debug/client-agent-token'
    private_file(path)
    config = Configuration(local_fixture_reviewers)
    if config.api('/api/control/v1/admin-session')['role']!='SUPER_ADMIN':
        raise ValueError('Use a Super Admin API login for initial debug allocation')
    config.presets()
    endpoint = config.api('/api/control/v1/endpoint/devices/'+device)
    if endpoint.get('state') != 'ACTIVE' or endpoint.get('connectionApproved') is False or int(time.time()*1000)-endpoint.get('lastSeenUnixMs',0)>120000:
        raise ValueError('The selected device must already be enrolled, approved and connected')
    installed = {tool['id']: tool for tool in config.all('tools') if tool['id'] in MIMIC_TOOLS}
    if set(installed) != set(MIMIC_TOOLS):
        raise ValueError('Install the examples.hotfolder package and release it to this device first; its tools are not installed')
    config.upsert('agents', dict(id='debug-mimic-agent', name='Debug mimic agent', enabled=True, revision=1, ownerUserId='admin'))
    config.memberships('agents','debug-mimic-agent',[group])
    config.memberships('devices',device,['ReadAndWriteDeviceGroup'])
    for tool_id in MIMIC_TOOLS:config.memberships('tools',tool_id,['ReadAndWriteToolGroup'])
    config.upsert('bindings', dict(id='standard-ReadAndWrite-ReadAndWrite', name='ReadAndWrite tools on ReadAndWrite devices', enabled=True, revision=1,
        toolGroupId='ReadAndWriteToolGroup', deviceGroupId='ReadAndWriteDeviceGroup', actions=['invoke'],
        allowedPackageDigests=sorted({tool['packageDigest'] for tool in installed.values()}), requireOnline=True, ownerDependency=False))
    for purpose in ('capability','service'):config.delete('grants','debug-mimic-'+purpose)
    config.delete('bindings','debug-mimic-binding')
    for collection, id in (('agent-groups','debug-mimic-agents'),('device-groups','debug-mimic-devices')):
        old=next((x for x in config.all(collection) if x['id']==id),None)
        if old and not old.get('agentIds',old.get('deviceIds',[])):config.delete(collection,id)
    if local_fixture_reviewers:config.test_users()
    token = secrets.token_urlsafe(48)
    digest = hashlib.sha256(token.encode()).hexdigest()
    expires = int(time.time()*1000)+86400000
    old = next((i for i in config.all('workload-bindings') if i['id']=='debug-mimic-workload'), None)
    epoch = old['credentialEpoch']+1 if old else 1
    config.upsert('workload-bindings', dict(id='debug-mimic-workload',name='Debug mimic workload',enabled=True,revision=1,
        agentId='debug-mimic-agent',mode='SERVICE',issuer='gateway',subject='debug-mimic',audience='gateway',
        credentialSha256=digest,credentialEpoch=epoch,expiresAtUnixMs=expires))
    private_file(path,token)
    credential = dict(tokenSha256=digest, expiresAtUnixMs=expires,
        context=dict(requestId='debug-mimic',tenantId='quickstart',mode='SERVICE',credentialKind='STATIC_TOKEN',agentId='debug-mimic-agent',
            workloadBindingId='debug-mimic-workload',chain=[],credentialEpoch=epoch,expiresAtUnixMs=expires,credentialSha256=digest,
            bindingId='standard-ReadAndWrite-ReadAndWrite'))
    docker_script("import sys,json,time,os;os.umask(0o077);sys.path.insert(0,'/opt/quickstart');import supervisor as s;"
        "p=s.DATA/'client-runtime-credentials.json';v=s.strict(p.read_bytes()) if p.exists() else [];c=json.load(sys.stdin);"
        "v=[x for x in v if x['expiresAtUnixMs']>int(time.time()*1000) and x['context'].get('workloadBindingId')!='debug-mimic-workload'];"
        "assert len(v)<32;s.atomic(p,json.dumps(v+[c]))", json.dumps(credential))
    subprocess.run(['docker','restart',CONTAINER],check=True,capture_output=True)
    deadline = time.monotonic()+120
    while time.monotonic()<deadline:
        try:
            if json.load(urllib.request.urlopen(ORIGIN+'/health/ready',timeout=2))['ready']: break
        except (OSError, ValueError): time.sleep(1)
    else: raise ValueError('Debug Gateway did not become ready')
    print('Configured 24-hour debug credential and group-only grants for '+device+'. No token was printed.')


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device-id')
    parser.add_argument('--agent-group',choices=['ReadOnlyAgentGroup','ReadAndWriteAgentGroup','AdminAgentGroup'],default='ReadAndWriteAgentGroup')
    parser.add_argument('--local-fixture-reviewers',action='store_true',help='Explicit local Quickstart test setup using independent fixture password logins')
    parser.add_argument('--allocate-agent',help='Only allocate an existing Agent using the reviewed membership API; do not rotate credentials or configure devices')
    args=parser.parse_args()
    try:
        if args.allocate_agent:
            config=Configuration(args.local_fixture_reviewers)
            config.memberships('agents',args.allocate_agent,[args.agent_group])
        else:
            if not args.device_id:raise ValueError('Provide --device-id for initial provisioning')
            configure(args.device_id,args.local_fixture_reviewers,args.agent_group)
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as error:
        print('FAILED: '+(str(error) if isinstance(error,ValueError) else 'Debug setup failed; private details withheld'))
        raise SystemExit(1)
