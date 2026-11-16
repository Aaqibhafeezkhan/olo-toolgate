# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Verify the managed-log package through a real deployed workflow and independent Control result."""
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    spec = importlib.util.spec_from_file_location('log_stack_manager', ROOT/'deploy/compose/WorkflowStack/manage.py')
    manager = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(manager)
    settings, state, compose = manager.environment('log-triage')
    source = settings['deployment']['source']
    assert source['triggerId'] == 'examples.log-watch/error-spike'
    assert source['config'] == dict(file='logs/app.log', pattern='ERROR|FATAL', threshold=2, windowSeconds=60, cooldownSeconds=60)
    assert settings['fileAgent'] is None
    image = settings['deployment']['platform']['quickstartImage']
    image_id = subprocess.check_output(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], text=True).strip()
    previous = {p.stem for p in (state/'workflow/results').glob('run-*.json')}
    reference = 'INFRAFAIL-LOG-' + secrets.token_hex(4)
    create = """import os
from pathlib import Path
os.umask(0o077)
p=Path('/var/lib/olo-toolgate/hotfolder/logs/app.log')
p.parent.mkdir(mode=0o700,exist_ok=True)
with p.open('x',encoding='utf-8') as f:f.write('INFO healthy\\n')
"""
    # The test owns only a newly created fixture; it refuses to overwrite an existing source log.
    subprocess.run(compose+['exec', '-T', 'linux-device', 'python', '-c', create], check=True)
    time.sleep(12)
    assert {p.stem for p in (state/'workflow/results').glob('run-*.json')} == previous, 'Noise triggered a workflow'
    append = """import sys
from pathlib import Path
with Path('/var/lib/olo-toolgate/hotfolder/logs/app.log').open('a',encoding='utf-8') as f:f.write(sys.stdin.read())
"""
    subprocess.run(compose+['exec', '-T', 'linux-device', 'python', '-c', append],
                   input=f'ERROR {reference}: database pool exhausted\nERROR {reference}: checkout timed out\n', text=True, check=True)
    print('Noise produced no workflow; appended two synthetic matching records', flush=True)
    deadline = time.monotonic()+300
    while time.monotonic() < deadline:
        paths = [p for p in (state/'workflow/results').glob('run-*.json') if p.stem not in previous]
        if paths:
            worker = json.loads(paths[0].read_text(encoding='utf-8'))
            break
        time.sleep(1)
    else:
        raise AssertionError('No completed log workflow; inspect source health and listener/worker metadata')
    assert worker['state'] == 'SUCCEEDED', worker['state']
    from folder_agent import ControlResults
    control = ControlResults('https://localhost:62092', state/'workflow/reader')
    saved = control.get(worker['runId'])
    assert saved['state'] == 'SUCCEEDED'
    assert saved['outcome']['result'] == worker['result']
    assert reference in saved['message']
    assert saved['workflowId'] == 'log-triage'
    assert worker['result']['summary'].strip()
    logs = subprocess.check_output(compose+['logs', '--no-log-prefix', '--tail', '150', 'workflow-worker'], text=True)
    observation = next(json.loads(line) for line in logs.splitlines()
                       if line.startswith('{') and json.loads(line).get('runId') == worker['runId'])
    execution = observation['execution']
    assert execution['tools'] == []
    assert {entry['subjectId'] for entry in execution['inferences']} == {'log-diagnoser'}
    assert image_id == subprocess.check_output(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'], text=True).strip()
    record = dict(configuration='log-triage', runId=worker['runId'], state='SUCCEEDED',
                  packageId=source['packageId'], packageDigest=source['packageDigest'], agentId='log-diagnoser',
                  messagePointer='/sample', noiseTriggered=False, commonImageId=image_id,
                  result=worker['result'])
    destination = state/'verification'
    destination.mkdir(mode=0o700, exist_ok=True)
    (destination/(worker['runId']+'.json')).write_text(json.dumps(record, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
