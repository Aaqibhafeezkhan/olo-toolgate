#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The Marketplace import, end to end through a real Quickstart ToolGate (run by ci/e2e.sh).

Starts the Quickstart image on the Marketplace stack's network with the TOOLGATE_CONTROL_MARKETPLACE_* settings, then
uses only Control's and the Gateway's public HTTP APIs: the installation owner imports examples.notify and
examples.health from the Marketplace, deploys them to the bundled Tool Host, and reviewed changes enable them, allow
their packages on the binding and grant them. Both are then listed and called through /mcp. Standard library only.
"""
import argparse
import hashlib
import json
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

PACKAGES = {'examples.notify': 'examples.notify/send', 'examples.health': 'examples.health/system'}
TOOL_HOST = 'tool-host-local'


def run(*args, capture=True):
    return subprocess.run(list(args), check=True, capture_output=capture, text=True).stdout.strip()


def request(url, token=None, body=None, method='GET', headers=None, timeout=90):
    fields = {'Content-Type': 'application/json', **(headers or {})}
    if token: fields['Authorization'] = 'Bearer ' + token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=fields)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response: return response.status, response.read()
    except urllib.error.HTTPError as error: return error.code, error.read()


def until(check, what, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        value = check()
        if value: return value
        if time.monotonic() > deadline: raise AssertionError('Timed out waiting for ' + what)
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('--image', '--network', '--ca', '--base-url', '--marketplace-id', '--keys'): parser.add_argument(name, required=True)
    args = parser.parse_args()
    name = 'toolgate-marketplace-e2e-' + secrets.token_hex(4)
    container = run('docker', 'run', '-d', '--name', name, '--network', args.network, '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--memory=1536m', '--cpus=2', '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m,uid=65532,gid=65532',
        '-e', 'TOOLGATE_ALLOW_LOOPBACK_MCP_HTTP=true',
        '-e', 'TOOLGATE_CONTROL_MARKETPLACE_ENABLED=true', '-e', 'TOOLGATE_CONTROL_MARKETPLACE_ID=' + args.marketplace_id,
        '-e', 'TOOLGATE_CONTROL_MARKETPLACE_BASE_URL=' + args.base_url, '-e', 'TOOLGATE_CONTROL_MARKETPLACE_KEYS=' + args.keys,
        '-e', 'TOOLGATE_CONTROL_MARKETPLACE_CA_PATH=/marketplace/ca.pem', '-v', args.ca + ':/marketplace/ca.pem:ro',
        '-p', '127.0.0.1::8080', '-v', name + ':/data', args.image)
    try:
        flow(container, args)
    except BaseException:
        logs = subprocess.run(['docker', 'logs', '--tail', '300', container], capture_output=True, text=True)
        print(logs.stdout + logs.stderr, file=sys.stderr)
        raise
    finally:
        subprocess.run(['docker', 'rm', '-f', container], capture_output=True)
        subprocess.run(['docker', 'volume', 'rm', name], capture_output=True)


def flow(container, args):
    port = json.loads(run('docker', 'inspect', container))[0]['NetworkSettings']['Ports']['8080/tcp'][0]['HostPort']
    origin = 'http://127.0.0.1:' + port
    def ready():
        try: return request(origin + '/health/ready', timeout=5)[0] == 200
        except OSError: return False
    until(ready, 'Quickstart readiness', 180)
    def api(path, token, body=None, method='GET', revision=None, key=None):
        headers = {}
        if body is not None or method != 'GET': headers['Idempotency-Key'] = key or secrets.token_hex(16)
        if revision is not None: headers['If-Match'] = '"%d"' % revision
        status, raw = request(origin + path, token, body, method, headers)
        return status, (json.loads(raw) if raw else None)
    def ok(result, expected=200):
        assert result[0] == expected, result
        return result[1]
    tokens = {}
    for user in ('admin', 'reviewer-1', 'reviewer-2'):
        initial = run('docker', 'exec', container, 'cat', '/data/bootstrap-password' + ('' if user == 'admin' else '-' + user))
        tokens[user] = ok(api('/api/quickstart/v1/login', None, {'username': user, 'password': initial, 'newPassword': secrets.token_urlsafe(32)}, 'POST'))['accessToken']
    admin, owner = tokens['admin'], tokens['reviewer-2']  # reviewer-2 is the installation owner (Super Admin).
    def transition(change, action, user):
        return ok(api('/api/control/v1/configuration-changes/' + change['id'] + '/transition', tokens[user], {'action': action, 'expectedRevision': change['revision']}, 'POST'))
    def reviewed(path, document, method='POST', revision=None):
        # A change applies at once for a Super Admin; otherwise it is a draft that two other people approve.
        status, value = api(path, admin, document, method, revision)
        if status in (200, 201): return value
        assert status == 202, (status, value)
        change = transition(value, 'SUBMIT', 'admin')
        for reviewer in ('reviewer-1', 'reviewer-2')[:change['requiredReviews']]: change = transition(change, 'APPROVE', reviewer)
        return transition(change, 'APPLY', 'admin')

    # Settings, catalog and Control's compatibility verdict.
    status = ok(api('/api/control/v1/marketplace', admin))
    assert status['enabled'] and status['marketplaceId'] == args.marketplace_id and len(status['keys']) == 1, status
    catalog = ok(api('/api/control/v1/marketplace/catalog?limit=50', admin))
    listed = {item['packageId']: item for item in catalog['items']}
    for package in PACKAGES:
        assert listed[package]['control']['compatible'] is True, listed.get(package)
    print('PASS catalog through Control with compatibility verdicts', flush=True)
    icon = next((item['iconMediaId'] for item in catalog['items'] if item.get('iconMediaId')), None)
    if icon:
        status, body = request(origin + '/api/control/v1/marketplace/media/' + icon)
        assert status == 200 and body[:4] in (b'\x89PNG', b'\xff\xd8\xff\xe0', b'\xff\xd8\xff\xe1', b'\xff\xd8\xff\xdb'), status
        print('PASS media through the Control proxy', flush=True)

    # Import (Super Admin only; MarketplaceTest covers the refusal): verified, audited, idempotent.
    versions = {}
    for package in PACKAGES:
        install = ok(api('/api/control/v1/marketplace/packages/' + package, admin))['installVersion']
        imported = ok(api('/api/control/v1/marketplace/imports', owner, {'packageId': package, 'version': install}, 'POST'), 201)
        assert imported['packageId'] == package and imported['state'] == 'VERIFIED', imported
        assert ok(api('/api/control/v1/marketplace/imports', owner, {'packageId': package, 'version': install}, 'POST'))['packageDigest'] == imported['packageDigest']
        versions[package] = imported
    audit = ok(api('/api/control/v1/audit?limit=100', admin))['items']
    targets = [entry['target'] for entry in audit if entry['operation'] == 'PACKAGE_MARKETPLACE_IMPORT']
    for package, version in versions.items():
        assert any(t.startswith('marketplace:%s/%s@%s publisher:examples key:' % (args.marketplace_id, package, version['version'])) for t in targets), targets
    print('PASS import with the §8.3 checks, audit entry and idempotent repeat', flush=True)

    # The bundled Tool Host enrolls; the imported packages deploy to it like any upload.
    def pending():
        return [e for e in ok(api('/api/control/v1/endpoint/enrollments', admin))['items'] if e['deviceId'] == TOOL_HOST]
    enrollment = until(pending, 'the Tool Host enrollment')[0]
    review = ok(api('/api/control/v1/endpoint/enrollments/review?code=' + enrollment['userCode'], admin))
    ok(api('/api/control/v1/endpoint/enrollments/decision', admin, {'userCode': review['userCode'], 'keyFingerprint': review['keyFingerprint'], 'choice': 'APPROVE'}, 'POST'))
    globals_ = {'examples.notify': {'WebhookHost': 'hooks.invalid'}, 'examples.health': {}}
    for package, version in versions.items():
        def deploy():
            status, value = api('/api/control/v1/packages/deployments', owner, {'packageDigest': version['packageDigest'], 'globals': globals_[package], 'hostIds': [TOOL_HOST]}, 'POST')
            if status == 200: return value
            assert status == 400, (status, value)  # The host can be named once it has registered.
        deployed = until(deploy, package + ' deployment')
        assert deployed['toolIds'] == [PACKAGES[package]], deployed
    for tool_id in PACKAGES.values():
        tool = ok(api('/api/control/v1/tools/' + tool_id, admin))
        assert tool['enabled'] is False
        reviewed('/api/control/v1/tools/' + tool_id, {**tool, 'enabled': True}, 'PUT', tool['revision'])
    binding = ok(api('/api/control/v1/bindings/binding-default', admin))
    digests = {v['packageDigest'].removeprefix('sha256:') for v in versions.values()}
    reviewed('/api/control/v1/bindings/binding-default', {**binding, 'actions': sorted({*binding['actions'], 'invoke'}),
        'allowedPackageDigests': sorted({*binding['allowedPackageDigests'], *digests})}, 'PUT', binding['revision'])
    agent = ok(api('/api/control/v1/agents/agent-local', admin))
    reviewed('/api/control/v1/agents/agent-local', {**agent, 'enabled': True}, 'PUT', agent['revision'])
    service_token = run('docker', 'exec', container, 'cat', '/data/run/runtime-token')
    reviewed('/api/control/v1/workload-bindings', dict(id='workload-local', name='Marketplace e2e workload', enabled=True, revision=1,
        agentId='agent-local', mode='SERVICE', issuer='gateway', subject='local-workload', audience='gateway',
        credentialSha256=hashlib.sha256(service_token.encode()).hexdigest(), credentialEpoch=1, expiresAtUnixMs=int(time.time() * 1000) + 3600000))
    conditions = dict(notBeforeUnixMs=0, expiresAtUnixMs=0, networkCidrs=[], devicePosture=[], regions=[], hoursUtc=[], requireOnline=True, highRisk=False)
    def scope(resources):
        return dict(toolGroups=dict(ids=['default-tools'], all=False), deviceGroups=dict(ids=['default-devices'], all=False),
            actions=['invoke'], allActions=False, resources=[dict(kind='CUSTOM', locator=r, match='EXACT') for r in resources], conditions=conditions)
    tools = ['tool://' + t for t in PACKAGES.values()]
    for purpose in ('CAPABILITY', 'SERVICE'):
        reviewed('/api/control/v1/grants', dict(id='marketplace-' + purpose.lower(), name='Marketplace e2e ' + purpose, enabled=True, revision=1,
            sourceType='AGENT_GROUP', sourceId='default-agents', purpose=purpose, scope=scope(tools + ['secret://WebhookApiKey'])))
    reviewed('/api/control/v1/grants', dict(id='marketplace-secret', name='Marketplace e2e webhook key', enabled=True, revision=1,
        sourceType='AGENT_GROUP', sourceId='default-agents', purpose='SECRET', scope=scope(['secret://WebhookApiKey'])))
    print('PASS deploy, reviewed enable, binding and grants', flush=True)

    def mcp(method, params=None):
        body = {'jsonrpc': '2.0', 'id': secrets.token_hex(16), 'method': method, 'params': {**(params or {}),
            '_meta': {'io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': {'tools': {}}}}}
        headers = {'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2026-07-28', 'MCP-Method': method,
                   'X-Request-ID': body['id'], 'Idempotency-Key': body['id']}
        if method == 'tools/call': headers['MCP-Name'] = params['name']
        status, raw = request(origin + '/mcp', service_token, body, 'POST', headers)
        return status, json.loads(raw)
    until(lambda: set(PACKAGES.values()) <= {t['name'] for t in ok(mcp('tools/list'))['result']['tools']}, 'the imported tools on /mcp', 120)
    health = ok(mcp('tools/call', {'name': 'examples.health/system', 'arguments': {}}))
    assert health['result']['isError'] is False and health['result']['structuredContent']['status'] in ('OK', 'WARN', 'CRITICAL'), health
    # A channel outside AllowedChannels: the imported code answers its own business error before any network call.
    notify = ok(mcp('tools/call', {'name': 'examples.notify/send', 'arguments': {'channel': 'ceo-inbox', 'title': 'hi', 'text': 'hi', 'reference': 'e2e-1'}}))
    assert notify['error']['data']['error']['code'] == 'CHANNEL_NOT_ALLOWED', notify
    print('PASS examples.health and examples.notify called through /mcp from the imported packages', flush=True)


if __name__ == '__main__':
    main()
