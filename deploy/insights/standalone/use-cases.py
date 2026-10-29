# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Local evaluation bootstrap and real Gateway -> Control -> Tool Host calls.

Runs inside this stack's QuickStart, never reads credentials from another stack.
Only the bundled Hello World tool receives runtime grants. No secrets are printed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import time
import urllib.error
import urllib.request

ORIGIN = 'http://127.0.0.1:8080'
TOOL = 'ololabs.hello-world/server_greet'
PREFIX = '/api/control/v1'


def request(path, token=None, body=None, method='GET', revision=None, headers=None):
    values = dict(headers or {})
    if token:
        values['Authorization'] = 'Bearer ' + token
    if body is not None:
        values['Content-Type'] = 'application/json'
        values['Idempotency-Key'] = secrets.token_hex(16)
    if revision is not None:
        values['If-Match'] = '"' + str(revision) + '"'
    req = urllib.request.Request(ORIGIN + path, method=method, headers=values,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(req, timeout=40)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None


def ok(result):
    status, value = result
    if status not in (200, 201, 204):
        raise RuntimeError('Local API failed: HTTP ' + str(status))
    return value


def bootstrap():
    credentials = json.loads(Path('/data/credentials.json').read_text())
    passwords = {u['username']: u['password'] for u in credentials['users']}
    tokens = {}
    for user in ('admin', 'reviewer-1'):
        tokens[user] = ok(request('/api/quickstart/v1/login', body={
            'username': user, 'password': passwords[user]}, method='POST'))['accessToken']
    admin = tokens['admin']

    def api(path, body=None, method='GET', token=admin, revision=None):
        return request(PREFIX + path, token, body, method, revision)

    def save(collection, document):
        status, current = api('/' + collection + '/' + document['id'])
        method = 'PUT' if status == 200 else 'POST'
        revision = current['revision'] if status == 200 else None
        document = dict(document, revision=revision or 1)
        path = '/' + collection + ('/' + document['id'] if method == 'PUT' else '')
        status, change = api(path, document, method, tokens['reviewer-1'], revision)
        if status in (200, 201, 204):
            return
        if status != 202:
            raise RuntimeError('Could not configure local ' + collection + ': HTTP ' + str(status))
        for action, actor in [('SUBMIT', 'reviewer-1'), ('APPROVE', 'admin'), ('APPLY', 'reviewer-1')]:
            if action == 'APPROVE' and change['state'] == 'APPROVED':
                continue
            change = ok(api('/configuration-changes/' + change['id'] + '/transition',
                {'action': action, 'expectedRevision': change['revision']}, 'POST', tokens[actor]))

    # This is an isolated evaluation stack: approve only its bundled Tool Hosts and its one device.
    deadline = time.monotonic() + 180
    approved_device = False
    while time.monotonic() < deadline:
        for pending in ok(api('/endpoint/enrollments'))['items']:
            review = ok(api('/endpoint/enrollments/review?code=' + pending['userCode']))
            if review.get('state') not in (None, 'PENDING'):
                continue
            ok(api('/endpoint/enrollments/decision', {'userCode': review['userCode'],
                'keyFingerprint': review['keyFingerprint'], 'choice': 'APPROVE'}, 'POST'))
        devices = ok(api('/endpoint/devices'))['items']
        approved_device = any(d.get('systemName') == 'monitoring-device' and
            d.get('endpointDevice', {}).get('state') == 'ACTIVE' for d in devices)
        status, profile = api('/packages/provisioning/fresh')
        if status == 200 and profile['state'] == 'READY' and approved_device:
            break
        time.sleep(2)
    else:
        raise RuntimeError('The bundled device or Hello World profile did not become ready within 180s')

    agent = ok(api('/agents/agent-local'))
    if not agent['enabled']:
        save('agents', dict(agent, enabled=True))
    status, workload = api('/workload-bindings/monitoring-workload')
    if status != 200:
        save('workload-bindings', dict(id='monitoring-workload', name='Local monitoring workload',
            enabled=True, agentId='agent-local', mode='SERVICE', issuer='gateway', subject='monitoring',
            audience='gateway', credentialSha256=hashlib.sha256(secrets.token_bytes(32)).hexdigest(),
            credentialEpoch=1, expiresAtUnixMs=int(time.time() * 1000) + 86400000))
    elif workload.get('expiresAtUnixMs', 0) < int(time.time() * 1000) + 3600000:
        save('workload-bindings', dict(workload, credentialEpoch=workload['credentialEpoch'] + 1,
            expiresAtUnixMs=int(time.time() * 1000) + 86400000))
    scope = dict(toolGroups=dict(ids=['default-tools'], all=False),
        deviceGroups=dict(ids=['default-devices'], all=False), actions=['invoke'], allActions=False,
        resources=[dict(kind='CUSTOM', locator='tool://' + TOOL, match='EXACT')],
        conditions=dict(notBeforeUnixMs=0, expiresAtUnixMs=0, networkCidrs=[], devicePosture=[],
            regions=[], hoursUtc=[], requireOnline=True, highRisk=False))
    for purpose in ('CAPABILITY', 'SERVICE'):
        save('grants', dict(id='monitoring-' + purpose.lower(), name='Monitoring greeting ' + purpose,
            enabled=True, sourceType='AGENT_GROUP', sourceId='default-agents', purpose=purpose, scope=scope))
    token = ok(request('/api/quickstart/v1/workloads/monitoring-workload/reauthorize',
                       admin, {}, 'POST'))['token']
    token_path = Path('/data/state/monitoring-token')
    token_path.write_text(token)
    token_path.chmod(0o600)
    print('Standalone device ACTIVE; Hello World ready; scoped monitoring grants applied.', flush=True)


def execute(case, index):
    call_id = 'monitoring-' + case + '-' + secrets.token_hex(8)
    token = Path('/data/state/monitoring-token').read_text().strip() if case == 'success' else 'monitoring-invalid-token'
    body = dict(jsonrpc='2.0', id=call_id, method='tools/call', params=dict(name=TOOL,
        arguments={'text': 'Monitoring run ' + str(index)},
        _meta={'io.modelcontextprotocol/protocolVersion': '2026-07-28',
               'io.modelcontextprotocol/clientCapabilities': {'tools': {}}}))
    status, value = request('/mcp', token, body, 'POST', headers={
        'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2026-07-28',
        'MCP-Method': 'tools/call', 'MCP-Name': TOOL, 'X-Request-ID': call_id})
    passed = (status == 200 and 'result' in (value or {}) and not value['result'].get('isError')) if case == 'success' else status == 401
    print(json.dumps(dict(case=case, requestId=call_id, httpStatus=status, passed=passed)), flush=True)
    if not passed:
        raise RuntimeError('Use case failed: ' + case + ', HTTP ' + str(status))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--case', choices=['all', 'success', 'denied'], default='all')
    parser.add_argument('--repeat', type=int, default=10)
    parser.add_argument('--interval', type=float, default=2)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 1000 or not 0 <= args.interval <= 60:
        parser.error('repeat must be 1..1000 and interval 0..60 seconds')
    if args.bootstrap:
        bootstrap()
    for index in range(args.repeat):
        for case in (['success', 'denied'] if args.case == 'all' else [args.case]):
            execute(case, index + 1)
        if index + 1 < args.repeat:
            time.sleep(args.interval)
