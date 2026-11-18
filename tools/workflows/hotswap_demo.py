# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Real local A/B demo: administrative APIs, signed packages and unchanged core/device processes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.admin.control import Control,Refused
from tools.admin.import_workflow import call,deploy
from tools.admin.provision_devices import approval_policy,allocate
from tools.admin.qualify_native_files import qualify

HERE=ROOT/'debug/profiled'
COMPOSE=['docker','compose','-p','toolgate-profiled','-f',str(HERE/'state/compose.json')]
PRIVATE=HERE/'credentials/admin'


def command(args,**kwargs):
    return subprocess.run(args,cwd=ROOT,check=True,capture_output=True,**kwargs)


def device_python(source,data=''):
    return command(COMPOSE+['exec','-T','device','python3','-c',source],input=data,text=True).stdout


def containers():
    ids=command(COMPOSE+['ps','-q'],text=True).stdout.split()
    values=json.loads(command(['docker','inspect',*ids],text=True).stdout)
    return {value['Config']['Labels']['com.docker.compose.service']:dict(id=value['Id'],
        startedAt=value['State']['StartedAt'],restarts=value['RestartCount'],pid=value['State']['Pid']) for value in values}


def status():
    result=subprocess.run(COMPOSE+['exec','-T','device','python3','-c',
        "from pathlib import Path;p=Path('/run/olo-toolgate/device-status.json');print(p.read_text() if p.exists() else '{}')"],cwd=ROOT,capture_output=True,text=True)
    return json.loads(result.stdout) if result.returncode==0 else {}


def until(predicate,timeout=180):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=predicate()
        if value:return value
        time.sleep(1)
    raise AssertionError('Timed out waiting for real service evidence')


def login():
    ca=command(COMPOSE+['exec','-T','quickstart','cat','/data/keys/device-ca.crt'],text=True).stdout
    ca_path=PRIVATE/'gateway-ca.crt'
    if ca_path.exists() and ca_path.read_text(encoding='ascii').strip()!=ca.strip():
        raise Refused('Running installation CA differs from saved operator trust; credentials and storage preserved. Select the intended installation explicitly.')
    password=(PRIVATE/'password.txt').read_text(encoding='utf-8-sig').rstrip('\r\n')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def authenticate(body):
        request=urllib.request.Request('http://127.0.0.1:18090/api/quickstart/v1/login',
            json.dumps(body).encode(),{'Content-Type':'application/json'},method='POST')
        with opener.open(request,timeout=20) as response:return json.load(response)['accessToken']
    token=authenticate(dict(username='admin',password=password))
    path=PRIVATE/'operator.token'
    with open(path,'w',encoding='ascii',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(token)
    if not ca_path.exists():ca_path.write_text(ca,encoding='ascii')
    return Control('https://127.0.0.1:18450',path,PRIVATE/'gateway-ca.crt')


def provider(document):
    image=document['services']['quickstart']['image']
    custody=document['volumes']['custody']['name']
    script=str(ROOT/'tools/workflows/demo_provider.py')+':/provider.py:ro'
    command(['docker','run','--rm','--network','none','--user','0:0','--mount',
        'type=volume,source='+custody+',target=/custody','-v',script,
        '--entrypoint','/opt/quickstart-python/bin/python',image,'/provider.py','initialize'])
    name='toolgate-profiled-demo-provider'
    existing=subprocess.run(['docker','inspect',name],capture_output=True,text=True)
    if existing.returncode==0:
        value=json.loads(existing.stdout)[0]
        if value['Config']['Labels'].get('io.ololabs.toolgate.demo-root')!=str(ROOT):raise ValueError('Foreign provider container')
        if not value['State']['Running']:raise ValueError('Existing demo provider is not running; repair explicitly')
    else:
        command(['docker','run','-d','--name',name,'--network','toolgate-profiled_default','--network-alias','demo-provider',
            '--health-cmd',"/opt/quickstart-python/bin/python -c \"import ssl,urllib.request; urllib.request.urlopen('https://demo-provider:9443/health',context=ssl.create_default_context(cafile='/custody/provider/ca.crt'),timeout=3).read()\"",
            '--health-interval','5s','--health-timeout','4s','--health-retries','3',
            '--user','65532:65532','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true',
            '--label','io.ololabs.toolgate.demo-root='+str(ROOT),'--mount',
            'type=volume,source='+custody+',target=/custody/provider,volume-subpath=provider','-v',script,
            '--entrypoint','/opt/quickstart-python/bin/python',image,'/provider.py','serve'])
    return command(['docker','exec',name,'cat','/custody/provider/token'],text=True).stdout.strip()


def approve(client):
    current=until(lambda:(value if (value:=status()).get('deviceId') else None))
    device=current['deviceId']
    if current['state']=='PENDING':
        review=call(client,'/api/control/v1/endpoint/enrollments/review?code='+current['userCode'])
        assert (review['deviceId'],review['keyFingerprint'])==(device,current['keyFingerprint'])
        call(client,'/api/control/v1/endpoint/enrollments/decision',dict(userCode=current['userCode'],
            keyFingerprint=current['keyFingerprint'],choice='APPROVE',connectionExpiresAtUnixMs=int(time.time()*1000)+86400000))
    until(lambda:status().get('state')=='ACTIVE')
    record=call(client,'/api/control/v1/devices/'+device)
    if not record['enabled']:
        call(client,'/api/control/v1/endpoint/devices/'+device+'/enabled',dict(expectedRevision=record['revision'],enabled=True))
    return device


def snapshot_identity(client):
    payload={key:call(client,'/api/control/v1/'+key+'?limit=100')['items'] for key in ('users','identity-bindings')}
    for binding in payload['identity-bindings']:
        for field in ('revision','attemptCount','lastAttemptUnixMs'):binding.pop(field,None)
    return hashlib.sha256(json.dumps(payload,sort_keys=True).encode()).hexdigest()


def drop(revision,text):
    device_python("import sys,os; from pathlib import Path; p=Path('/var/lib/olo-toolgate/files/inbox-"+revision+"/source.txt'); "
        "f=open(p,'x',encoding='utf-8',opener=lambda p,f:os.open(p,f,0o600)); f.write(sys.stdin.read()); f.close()",text)


def result(revision):
    raw=device_python("from pathlib import Path; p=Path('/var/lib/olo-toolgate/files/summaries/summary-"+revision+".txt'); "
        "print(p.read_text() if p.exists() else '')")
    return raw.strip()


def observations():
    logs=command(COMPOSE+['logs','--no-log-prefix','langgraph'],text=True).stdout
    return [json.loads(line) for line in logs.splitlines() if line.startswith('{') and 'runId' in json.loads(line)]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--approve-local-device',action='store_true',required=True,
                        help='Explicitly approve the real local device and assign the demo device groups')
    parser.add_argument('--resume-preparation',action='store_true',help='Explicitly resume this demo after a failed preparation; does not claim a new blank-start observation')
    parser.add_argument('--prepare-only',action='store_true',help='Activate A on a blank installation, then leave input creation and profile switching to the operator')
    args=parser.parse_args()
    document=json.loads((HERE/'state/compose.json').read_text(encoding='utf-8'))
    client=login();before_directory=call(client,'/api/control/v1/config/export')
    approval_policy(client,'admin',30)
    assert [row['id'] for row in call(client,'/api/control/v1/users?limit=100')['items']]==['admin']
    published=call(client,'/api/control/v1/workflow-profiles/published')
    if args.resume_preparation:assert all(row['profileId']=='hotswap-demo' for row in published)
    else:assert published==[], 'Demo requires a fresh blank installation'
    packages=call(client,'/api/control/v1/packages')['items']
    if args.resume_preparation:
        assert all(row['packageId'] in ('examples.hotfolder','examples.hotfolder-watch') for row in packages)
    else:assert not packages, 'Blank installation seeded packages'
    identity_before=snapshot_identity(client)
    password_digest=hashlib.sha256((PRIVATE/'password.txt').read_bytes()).hexdigest()
    token=provider(document)
    device=approve(client)
    device_python("from pathlib import Path; root=Path('/var/lib/olo-toolgate/files'); "
        "assert all((root/name).is_dir() for name in ('inbox-a','inbox-b','processed','summaries')), 'Create the demo folders explicitly using debug/profiled/README.md'")
    # The import cannot enable/approve/join devices. The operator creates the
    # empty destination groups and explicitly allocates this verified device.
    for group in ('ReadAndWriteDeviceGroup','default-devices','langchain-linux-devices'):
        page=call(client,'/api/control/v1/device-groups?limit=100')['items']
        if not any(row['id']==group for row in page):
            call(client,'/api/control/v1/device-groups',dict(id=group,name=group,revision=1,enabled=True,deviceIds=[]))
    allocate(client,device,['ReadAndWriteDeviceGroup','default-devices','langchain-linux-devices'])
    # The model secret is destination custody and is never copied into a bundle.
    call(client,'/api/control/v1/vault',dict(name='demo-provider-key',value=token,
        toolGroupId='default-tools',deviceGroupId='default-devices'))
    start=containers()
    previous_runs={row['runId'] for row in observations()}
    previous_operations={row['id'] for row in call(client,'/api/control/v1/access/invocations?limit=100')['items']}
    a=ROOT/'bot/bin/hotswap-demo-a-hotswap-demo-1.0.0-demo.a.olo'
    b=ROOT/'bot/bin/hotswap-demo-b-hotswap-demo-1.0.0-demo.b.olo'
    def qualify_owner(profile):
        tools={node['toolRef'] for workflow in profile['workflows']['workflows'] for node in workflow['graph']['config']['nodes'] if node['kind']=='tool'}
        qualify(client,device,'ReadAndWriteDeviceGroup',tools)
    first=deploy(client,a.read_bytes(),after_provision=qualify_owner)
    if args.prepare_only:
        print('Workflow A is ready. Place a UTF-8 .txt file under /var/lib/olo-toolgate/files/inbox-a; see debug/profiled/README.md for switching and output commands.',flush=True)
        return
    print('Workflow A activated; dropping a real watched file.',flush=True)
    text_a='slow demo: the database pool exhausted its connections. Restarting the pool restored service. '+secrets.token_hex(5)
    drop('a',text_a)
    until(lambda:json.loads(command(['docker','exec','toolgate-profiled-demo-provider','/opt/quickstart-python/bin/python','-c',
        "from pathlib import Path; p=Path('/custody/provider/inflight.json'); print(p.read_text() if p.exists() else '{}')"],text=True).stdout).get('inputSha256')==hashlib.sha256(text_a.encode()).hexdigest())
    second=deploy(client,b.read_bytes(),after_provision=qualify_owner)
    assert not [row for row in observations() if row['runId'] not in previous_runs], 'A completed before B activation; overlap is not proved'
    print('Workflow B activated while A was in flight; dropping its real watched file.',flush=True)
    drop('b','A disk alert was resolved by removing temporary files.')
    def drained():
        rows=[row for row in observations() if row['runId'] not in previous_runs]
        assert len(rows)<=2 and all(row['state']=='SUCCEEDED' for row in rows), 'A workflow failed; inspect terminal worker and Control evidence'
        return rows if len(rows)==2 else None
    until(drained,timeout=180)
    output_a=until(lambda:result('a'),timeout=180);output_b=until(lambda:result('b'),timeout=180)
    assert output_a=='demo-a: '+text_a
    assert output_b=='demo-b: A disk alert was resolved by removing temporary files.'
    for revision,text in (('a',text_a),('b','A disk alert was resolved by removing temporary files.')):
        assert device_python("from pathlib import Path; r=Path('/var/lib/olo-toolgate/files'); "
            "print((r/'processed/source-"+revision+".txt').exists() and not (r/'inbox-"+revision+"/source.txt').exists())").strip()=='True'
        archived=device_python("from pathlib import Path; print(Path('/var/lib/olo-toolgate/files/processed/source-"+revision+".txt').read_text(),end='')")
        assert archived==text, 'Archived input content changed'
    # Both pinned runs have drained. Retire this demo's replaced source through
    # the same administrator API used by the console; never restart the device.
    old=call(client,'/api/control/v1/triggers/assignments/demo-a')
    call(client,'/api/control/v1/triggers/assignments/demo-a:action',dict(action='Stop',scope=dict(devices=[device]),reason='Replaced demo revision drained'),revision=old['metadata']['resourceVersion'])
    def retired():
        rows=call(client,'/api/control/v1/triggers/instances?assignment=demo-a')['items']
        return rows and all(row['desiredState']=='Stopped' and row['phase']=='Stopped' for row in rows)
    until(retired)
    end=containers();assert start==end, 'A core or device container/process changed'
    assert status()['deviceId']==device
    assert identity_before==snapshot_identity(client)
    assert password_digest==hashlib.sha256((PRIVATE/'password.txt').read_bytes()).hexdigest()
    runs=[row for row in observations() if row['runId'] not in previous_runs]
    assert len(runs)==2 and all(row['state']=='SUCCEEDED' for row in runs)
    for run in runs:
        saved=call(client,'/api/control/v1/workflow-runs/'+run['runId'])
        assert saved['state']=='SUCCEEDED' and saved['profileDigest'] in (first['profileDigest'],second['profileDigest'])
        assert {tool['toolId'] for tool in run['execution']['tools']}=={'examples.hotfolder/read_text','examples.hotfolder/write_text','examples.hotfolder/move'}
        assert all(tool['state']=='SUCCEEDED' for tool in run['execution']['tools'])
    operations=[row for row in call(client,'/api/control/v1/access/invocations?limit=100')['items'] if row['id'] not in previous_operations]
    assert len(operations)==6, 'Expected six independent Control tool outcomes'
    outcomes=[]
    for operation in operations:
        # The catalog exposes stored executor state; only the exact detail route
        # projects Control's current independently derived business outcome.
        operation=call(client,'/api/control/v1/access/invocations/'+operation['id'])
        outcome=operation['operationOutcome']
        assert outcome['state']=='SUCCEEDED' and outcome['finalized'] and not outcome['retryable']
        assert outcome['unresolvedEffectCount']==0 and outcome['unsettledAttemptCount']==0
        assert operation['evaluation']['context']['deviceId']==device
        if outcome['effectCount']:assert outcome['grade']=='TARGET_CONFIRMED'
        outcomes.append(dict(toolId=operation['evaluation']['toolId'],**outcome))
    assert sum(row['effectCount'] for row in outcomes)==4
    assert sum(row['unfencedEffectCount'] for row in outcomes)==4
    assert sum(row['fencedEffectCount'] for row in outcomes)==0
    record=dict(state='PASSED',profileA=first,profileB=second,deviceId=device,containers=start,
                runs=[dict(runId=row['runId'],state=row['state'],execution=row['execution']) for row in runs],
                outputs=dict(a=output_a,b=output_b),adminCredentialsUnchanged=True,adminIdentityUnchanged=True,
                controlOutcomes=outcomes,blankInitialization=not args.resume_preparation,
                model='external deterministic mock',retiredAssignment='demo-a',sourceRevision=command(['git','rev-parse','HEAD'],text=True).stdout.strip(),
                images={name:service['image'] for name,service in document['services'].items()},
                bundles={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (a,b)})
    evidence=ROOT/'build/hotswap-demo/evidence.json';evidence.parent.mkdir(parents=True,exist_ok=True)
    evidence.write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
    print('PASS: two real trigger workflows, configured agent/model, read/write/move, stable core/device identities. Evidence: '+str(evidence),flush=True)


if __name__=='__main__':main()
