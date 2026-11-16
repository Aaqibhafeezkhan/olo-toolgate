# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Drop one real file into a running configured file demo and independently verify delivery."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configuration',required=True)
    args=parser.parse_args()
    spec=importlib.util.spec_from_file_location('stack_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
    manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
    settings,state,compose=manager.environment(args.configuration)
    folders=settings['fileAgent']
    if folders is None:raise ValueError('This driver requires the explicit file-delivery adapter; use the source package producer for a non-file workflow')
    content=(manager.HERE/'configurations'/settings['id']/'example.txt').read_bytes()
    source=manager.HERE/'data'/settings['id']/folders['inbox']/('acceptance-'+secrets.token_hex(5)+'.txt')
    with open(source,'xb',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(content)
    print('Dropped real host file: '+str(source),flush=True)
    from folder_agent import ControlResults
    control=ControlResults('https://localhost:'+os.environ['TOOLGATE_TLS_PORT'],state/'workflow/reader')
    binding=control.binding
    deadline=time.monotonic()+360
    while time.monotonic()<deadline:
        outputs=list((source.parent.parent/folders['output']).glob(source.stem+'-*'))
        if outputs:break
        time.sleep(1)
    else:raise AssertionError('No confirmed output; inspect worker, listener and folder-agent metadata logs')
    output=outputs[0];prefix=output.name[len(source.stem)+1:].split('.')[0]
    for path in (state/'workflow/results').glob('run-*.json'):
        worker=json.loads(path.read_text())
        if worker.get('state')!='SUCCEEDED':continue
        try:saved=control.get(worker['runId'])
        except ValueError as failure:
            if str(failure)=='FOREIGN_WORKFLOW':continue
            raise
        if (saved.get('profileDigest')==binding['profileDigest'] and saved.get('workflowId')==settings['workflowId']
                and saved.get('message','').startswith('inbox/'+prefix)):
            break
    else:raise AssertionError('Output has no independently confirmed admitted run')
    assert saved['state']=='SUCCEEDED' and saved['outcome']['result']==worker['result']
    if folders['archive']:
        while source.exists() and time.monotonic()<deadline:time.sleep(.1)
        assert not source.exists()
        assert (source.parent.parent/folders['processed']/source.name).read_bytes()==content
    else:assert source.read_bytes()==content
    if folders['projection']=='summary':assert output.read_text().strip()==worker['result']['summary'].strip()
    else:assert json.loads(output.read_text())==worker['result']
    logs=subprocess.check_output(compose+['logs','--no-log-prefix','--tail','200','workflow-worker'],text=True)
    observed=next(json.loads(line) for line in logs.splitlines() if line.startswith('{') and json.loads(line).get('runId')==worker['runId'])
    execution=observed['execution']
    agents={item['subjectId'] for item in execution['inferences']+execution['tools'] if item['subjectKind']=='AGENT'}
    workflow=settings['document']['workflows']['workflows'][0]
    assert agents=={stage['agentId'] for stage in workflow['agentStages']}
    assert {item['subjectId'] for item in execution['inferences']}=={stage['agentId'] for stage in workflow['agentStages'] if stage['resultMode']=='MODEL'}
    assert not any(item['subjectKind']=='WORKFLOW' for item in execution['inferences'])
    for agent in settings['document']['workflows']['agentDefinitions']:
        for source_config in agent['contextSources']:
            assert any(item['subjectId']==agent['agentId'] and item['toolId']==source_config['toolId'] and item['state']=='SUCCEEDED' for item in execution['tools'])
    record=dict(configuration=settings['id'],runId=worker['runId'],state='SUCCEEDED',agents=sorted(agents),
        input=str(source),output=str(output),archived=folders['archive'],profileDigest=binding['profileDigest'])
    destination=state/'verification';destination.mkdir(mode=0o700,exist_ok=True)
    with open(destination/(worker['runId']+'.json'),'x',opener=lambda p,f:os.open(p,f,0o600)) as stream:json.dump(record,stream,indent=2)
    print(json.dumps(record,indent=2))


if __name__=='__main__':main()
