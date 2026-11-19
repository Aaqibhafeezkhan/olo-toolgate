# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Explicit local acceptance: real device triggers, QLoRA/Ollama, files and unchanged containers."""
import argparse
import hashlib
import json
import secrets
from pathlib import Path
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from bot.bundle import inventory,verify,MAX_BUNDLE
from tools.admin.import_workflow import call,deploy
from tools.admin.provision_devices import allocate
from tools.admin.qualify_native_files import qualify
from tools.workflows.hotswap_demo import (COMPOSE,PRIVATE,approve,command,containers,
    device_python,login,observations,snapshot_identity,status,until)

CASES=('hotswap-demo-a','hotswap-demo-b','issue-triage','log-triage','native-hotfolder','native-log','text-summary')
TEXT='ISSUE-123 HIGH: The database connection pool was exhausted. Restarting the pool restored service.\n'
LOG='ERROR ISSUE-123 database connection pool exhausted\nFATAL ISSUE-123 requests failed; restarting the pool restored service.\n'


def settled_containers():
    """An explicit credential refresh may still be settling before acceptance starts."""
    previous=None;stable_since=time.monotonic()
    def ready():
        nonlocal previous,stable_since
        current=containers()
        if current!=previous:previous=current;stable_since=time.monotonic()
        if time.monotonic()-stable_since<10:return None
        return current
    return until(ready)


def prepare_groups(client,device,references):
    for collection,route in (('deviceGroups','device-groups'),('toolGroups','tool-groups')):
        existing={row['id'] for row in call(client,'/api/control/v1/'+route+'?limit=100')['items']}
        for group in references[collection]:
            if group['id'] not in existing:
                item={**group,'revision':1}
                if collection=='deviceGroups':item['deviceIds']=[]
                else:item['toolIds']=[]
                call(client,'/api/control/v1/'+route,item)
    allocate(client,device,[row['id'] for row in references['deviceGroups']])


def prepare_folders(paths,log_path):
    # This explicit acceptance action is deliberately separate from bot import.
    device_python("import json,os,sys; from pathlib import Path; "
        "root=Path('/var/lib/olo-toolgate/files'); data=json.load(sys.stdin); "
        "paths=[root/x for x in data['paths']]; "
        "assert all(p.resolve().is_relative_to(root) for p in paths); "
        "[(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in paths]; "
        "p=root/data['log'] if data['log'] else None; (p.touch(exist_ok=True),p.chmod(0o600)) if p else None",
        json.dumps(dict(paths=paths,log=log_path)))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundles-dir',type=Path,default=ROOT/'build/local-model-bots')
    parser.add_argument('--version',default='1.0.0-demo.local',help='Exact bundle inventory version')
    parser.add_argument('--configuration',choices=CASES,action='append')
    parser.add_argument('--verify-existing',action='store_true',help='Verify confirmed matching completed demo artifacts without submitting another event')
    parser.add_argument('--approve-local-device',action='store_true',required=True)
    parser.add_argument('--prepare-demo-folders',action='store_true',required=True,
        help='Explicitly create this case\'s folders/input, separate from profile import')
    parser.add_argument('--acknowledge-unfenced-files',action='store_true',required=True,
        help='Operator accepts the qualified demo native filesystem owner\'s current limitations')
    parser.add_argument('--evidence',type=Path,default=ROOT/'build/local-model-bots-evidence.json')
    args=parser.parse_args();client=login();device=approve(client)
    before=settled_containers();identity=snapshot_identity(client)
    password=hashlib.sha256((PRIVATE/'password.txt').read_bytes()).hexdigest()
    key=command(COMPOSE+['exec','-T','quickstart','cat','/custody/ollama/token'],text=True).stdout.strip()
    index=json.loads((args.bundles_dir/('olo-profiles-'+args.version+'.json')).read_text(encoding='utf-8'))
    evidence=dict(sourceRevision=command(['git','rev-parse','HEAD'],text=True).stdout.strip(),cases=[],containers=before)
    for name in args.configuration or CASES:
        entry=next(row for row in index['profiles'] if row['configuration']==name)
        raw=(args.bundles_dir/entry['filename']).read_bytes();verify(raw)
        files=inventory(raw,MAX_BUNDLE,256*1024*1024)
        profile=json.loads(files['workflow.json']);references=json.loads(files['referenced-data.json'])
        workflow=profile['workflows']['workflows'][0];nodes=workflow['graph']['config']['nodes']
        connection=json.loads((ROOT/'bot/configurations'/name/'trigger-connections.json').read_text(encoding='utf-8'))[0]
        trigger=connection['assignmentSpec']['config'];is_log='file' in trigger
        source=trigger['file'] if is_log else trigger['folder']+'/source-'+secrets.token_hex(4)+'.txt'
        output=next(node for node in nodes if node['id']=='write')['input']['path']
        archive=next((node['input']['destination'] for node in nodes if node['id']=='archive'),None)
        destinations=[output]+([archive] if archive else [])
        paths=sorted({str(Path(path).parent).replace('\\','/') for path in [source]+destinations})
        text=LOG if is_log else TEXT
        existing=device_python("from pathlib import Path; print((Path('/var/lib/olo-toolgate/files')/"+repr(output)+").exists())").strip()=='True'
        if existing:
            if not args.verify_existing:raise AssertionError('Existing demo output preserved; inspect it or explicitly verify the confirmed run without resubmitting')
            run=None
            for observed in reversed(observations()):
                if observed['workflowId']!=workflow['id']:continue
                prior=call(client,'/api/control/v1/workflow-runs/'+observed['runId'])
                matching_input=prior['message']==LOG if is_log else prior['message'].startswith(trigger['folder']+'/') and prior['message'].endswith('.txt')
                if prior['workflowVersionId']==workflow['versionId'] and matching_input:
                    run=observed;published=dict(profileDigest=prior['profileDigest']);break
            if run is None:raise AssertionError('Existing artifact has no matching confirmed demo run; no event was submitted')
            print('Verifying existing '+name+' without resubmission',flush=True)
        else:
            device_python("import json,sys; from pathlib import Path; root=Path('/var/lib/olo-toolgate/files'); "
                "assert not any((root/p).exists() for p in json.load(sys.stdin)), 'Existing demo input/archive preserved; inspect the in-progress or unknown outcome before rerunning'",
                json.dumps(([archive] if archive else [])+([] if is_log else [source])))
            prepare_groups(client,device,references);prepare_folders(paths,source if is_log else None)
            call(client,'/api/control/v1/vault',dict(name='ollama-local-key',value=key,
                toolGroupId='default-tools',deviceGroupId='default-devices'))
            tools={node['toolRef'] for node in nodes if node['kind']=='tool'}
            def qualify_owner(_profile):
                qualify(client,device,profile['packages'][0]['deviceGroupIds'][0],tools)
            print('Preparing '+name,flush=True)
            published=deploy(client,raw,after_provision=qualify_owner)
            seen={row['runId'] for row in observations()}
            device_python("import json,sys,os; from pathlib import Path; os.umask(0o077); data=json.load(sys.stdin); "
                "p=Path('/var/lib/olo-toolgate/files')/data['path']; "
                "f=p.open('a' if data['append'] else 'x',encoding='utf-8'); f.write(data['text']); f.close()",
                json.dumps(dict(path=source,text=text,append=is_log)))
            def completed():
                rows=[row for row in observations() if row['runId'] not in seen and row['workflowId']==workflow['id']]
                return rows[-1] if rows else None
            run=until(completed,timeout=360)
        assert run['state']=='SUCCEEDED',name+': '+str(run.get('code'))
        saved=call(client,'/api/control/v1/workflow-runs/'+run['runId'])
        assert saved['state']=='SUCCEEDED' and saved['profileDigest']==published['profileDigest']
        result=saved['outcome']['result'];assert isinstance(result['summary'],str) and result['summary'].strip()
        if name in ('issue-triage','native-hotfolder'):
            assert result['issueId']=='ISSUE-123','Triage did not preserve the source issue identity'
            assert result['priority'] in ('HIGH','MEDIUM','LOW') and result['nextAction'].strip()
        raw_output=device_python("from pathlib import Path; print((Path('/var/lib/olo-toolgate/files')/"+repr(output)+").read_text(encoding='utf-8'),end='')")
        assert raw_output==result['summary'] if name.startswith('hotswap-demo-') else json.loads(raw_output)==result
        if archive:
            device_python("from pathlib import Path; root=Path('/var/lib/olo-toolgate/files'); "
                "assert not (root/"+repr(source)+").exists(); assert (root/"+repr(archive)+").read_text(encoding='utf-8')=="+repr(text))
        progress=call(client,'/api/control/v1/workflow-runs/'+run['runId']+'/progress')
        assert any(event['kind']=='MODEL_COMPLETED' for event in progress['items'])
        assert containers()==before,'A core/device process changed during bot configuration'
        row=dict(configuration=name,runId=run['runId'],profileDigest=published['profileDigest'],bundleSha256=entry['sha256'],
            state=run['state'],verifiedExisting=existing,input=trigger['file'] if is_log else saved['message'],output=output,archive=archive,execution=run['execution'],progressEvents=len(progress['items']))
        evidence['cases'].append(row);args.evidence.parent.mkdir(parents=True,exist_ok=True)
        args.evidence.write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        print(name+': SUCCEEDED '+run['runId'],flush=True)
    assert snapshot_identity(client)==identity
    assert hashlib.sha256((PRIVATE/'password.txt').read_bytes()).hexdigest()==password
    assert status()['deviceId']==device
    print('Accepted '+str(len(evidence['cases']))+' real local-model cases; core/device identity and Admin preserved.',flush=True)


if __name__=='__main__':main()
