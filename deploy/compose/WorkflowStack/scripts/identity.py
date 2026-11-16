# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Operator-only local Quickstart fixture identity lifecycle; not a production identity provider."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request


def replace(path, text):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.next-' + secrets.token_hex(8))
    try:
        with open(temporary, 'x', encoding='utf-8', opener=lambda p,f:os.open(p,f,0o600)) as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def refresh():
    root = Path('/state')
    username=(root/'credentials/admin/username.txt').read_text(encoding='utf-8').strip()
    password=(root/'credentials/admin/password.txt').read_text(encoding='utf-8').strip()
    request = urllib.request.Request('http://127.0.0.1:8080/api/quickstart/v1/login',
        json.dumps(dict(username=username, password=password)).encode(), {'Content-Type':'application/json'}, method='POST')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=20) as response:
        operator = json.load(response)['accessToken']
    command = ['docker','exec',os.environ['QUICKSTART_CONTAINER'],'/opt/quickstart-python/bin/python','-c',
        "import sys;sys.path.insert(0,'/opt/quickstart');import supervisor as s;"
        "print(s.jwt(['toolgate-workflow-runtime'],'configured-workflow-worker',directory_bound=False))"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise ValueError('Local workload credential issuance unavailable')
    ca = (root/'agents/gateway-ca.crt').read_text()
    for folder in ('worker','reader'):
        replace(root/'workflow'/folder/'gateway-ca.crt', ca)
    replace(root/'workflow/worker/worker.token', result.stdout.strip())
    replace(root/'workflow/worker/listener.token', operator)
    replace(root/'workflow/reader/result.token', operator)
    custody=root/'workflow/worker'
    os.chown(custody,65532,65532)
    custody.chmod(0o700)
    for path in custody.iterdir():
        if path.is_symlink() or not path.is_file():
            raise ValueError('Unexpected workload custody entry')
        os.chown(path,65532,65532)
        path.chmod(0o600)
    results=root/'workflow/results'
    results.mkdir(mode=0o700,parents=True,exist_ok=True)
    os.chown(results,65532,65532)
    results.chmod(0o700)
    replace(root/'workflow/identity-ready', str(int(time.time())))


if __name__ == '__main__':
    while True:
        try:
            refresh()
            print('{"event":"FIXTURE_IDENTITIES_REFRESHED"}', flush=True)
            time.sleep(300)
        except Exception:
            # Neither exception bodies nor captured subprocess output belong in logs.
            print('{"event":"FIXTURE_IDENTITIES_UNAVAILABLE"}', flush=True)
            time.sleep(10)
