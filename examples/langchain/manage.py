# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Explicit, reviewed provisioning of this isolated Compose example."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import sys
import time
import urllib.request

import packages

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
STATE = Path(os.environ.get('TOOLGATE_LANGCHAIN_STATE', str(HERE / '.state'))).resolve()
spec = importlib.util.spec_from_file_location('debug_configuration', ROOT / 'debug/configure-debug-agent.py')
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
TOOLS = packages.TOOLS
DEVICE_GROUP = 'ReadAndWriteDeviceGroup'
TOOL_GROUP = 'langchain-package-tools'
COMPOSE = ['docker', 'compose', '-p', os.environ.get('TOOLGATE_LANGCHAIN_PROJECT', 'toolgate-langchain'), '-f', str(HERE / 'compose.yaml')]


def load_environment():
    path = HERE / '.env'
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def docker(service, arguments, input=None):
    result = subprocess.run(COMPOSE + ['exec', '-T', service] + arguments,
                            input=input, capture_output=True, text=True, timeout=60, cwd=HERE)
    if result.returncode:
        raise ValueError(f'{service} command failed; inspect docker compose logs (private output withheld)')
    return result.stdout.strip()


def wait_ready():
    origin = 'http://127.0.0.1:' + os.environ.get('TOOLGATE_HTTP_PORT', '18091')
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            if json.load(urllib.request.urlopen(origin + '/health/ready', timeout=3))['ready']:
                return origin
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise ValueError('Quickstart is not ready; inspect docker compose logs quickstart')


class Configuration(shared.Configuration):
    def __init__(self, origin, bootstrap):
        self.bootstrap = bootstrap
        super().__init__(origin=origin, credential_directory=STATE)
        if bootstrap:
            for number in (1, 2):
                key = f'reviewer-{number}'
                if key not in self.tokens:
                    self.login(key, os.environ.get(f'TOOLGATE_REVIEWER{number}_USERNAME', key),
                               os.environ.get(f'TOOLGATE_REVIEWER{number}_PASSWORD', ''))

    def login(self, key, username, password, replacement=None):
        if self.bootstrap:
            if username not in {'admin', 'reviewer-1', 'reviewer-2'}:
                raise ValueError('--bootstrap-local supports only this example\'s three installation identities')
            filename = 'bootstrap-password' + ('' if username == 'admin' else '-' + username)
            initial = docker('quickstart', ['/opt/quickstart-python/bin/python', '-c',
                'from pathlib import Path; p=Path("/data/' + filename + '"); print(p.read_text().strip() if p.exists() else "")'])
            path = STATE / 'console-passwords.json'
            saved = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
            if initial:
                password = secrets.token_urlsafe(32)
                saved[username] = password
                shared.private_file(path, json.dumps(saved))
                replacement = password
                password = initial
            elif username in saved:
                password = saved[username]
            elif not password:
                raise ValueError('Installation password already changed; provide the existing credential in .env')
        super().login(key, username, password, replacement)


def linux_status():
    return json.loads(docker('linux-device', ['cat', '/run/olo-toolgate/device-status.json']))


def wait_device(config, device, seen_after=0, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        endpoint = config.api('/api/control/v1/endpoint/devices/' + device)
        directory = config.api('/api/control/v1/devices/' + device)
        if device_available(dict(endpointDevice=endpoint, directoryDevice=directory), int(time.time()*1000)) \
                and endpoint.get('lastSeenUnixMs', 0) >= seen_after:
            return
        if time.monotonic() >= deadline:
            raise ValueError('Approve and enable the selected device in this example\'s console first')
        time.sleep(2)


def gateway_url():
    return 'https://localhost:' + os.environ.get('TOOLGATE_TLS_PORT', '18451')


def sync_deployment_metadata():
    """Collect only public Docker facts; runtime device keys and peer addresses stay authoritative."""
    result=subprocess.run(COMPOSE+['ps','-q','quickstart','linux-device','registry'],capture_output=True,text=True,check=True,timeout=30,cwd=HERE)
    ids=result.stdout.split()
    if len(ids)!=3 or any(not re.fullmatch('[a-f0-9]{64}',value) for value in ids):
        raise ValueError('Quickstart, Linux device and registry must be running to inspect their deployment')
    result=subprocess.run(['docker','inspect',*ids],capture_output=True,text=True,check=True,timeout=30,cwd=HERE)
    containers={item['Config']['Labels']['com.docker.compose.service']:item for item in json.loads(result.stdout)}
    quickstart,endpoint=containers['quickstart'],containers['linux-device']
    quickstart['NetworkSettings']['Networks']=containers['registry']['NetworkSettings']['Networks']
    networks=set(quickstart['NetworkSettings']['Networks'])&set(endpoint['NetworkSettings']['Networks'])
    if not networks:raise ValueError('Example containers have no shared deployment network')
    network='toolgate-langchain_default' if 'toolgate-langchain_default' in networks else sorted(networks)[0]
    def details(container,kind):
        address=container['NetworkSettings']['Networks'][network]
        return dict(executionKind=kind,containerId=container['Id'],containerName=container['Name'].lstrip('/'),
                    systemName=container['Config']['Hostname'],networkId=address['NetworkID'],networkName=network,
                    ipAddress=address['IPAddress'] or address['GlobalIPv6Address'])
    local=json.loads(docker('quickstart',['/opt/quickstart-python/bin/python','-c',
        "import json;from pathlib import Path;p=Path('/data/run');r={};"
        "r.update({k:json.loads((p/n).read_text())[f] for k,n,f in "
        "[('TOOL_HOST_DIRECT','tool-host.json','hostId'),"
        "('TOOL_HOST_PULL','tool-host-remote.json','hostId')] if (p/n).exists()});"
        "print(json.dumps(r))"]))
    records={device:details(quickstart,kind) for kind,device in local.items() if device}
    records[linux_status()['deviceId']]=details(endpoint,'ENDPOINT_CLIENT')
    document=dict(gatewaySystemName=quickstart['Config']['Hostname'],devices=records)
    docker('quickstart',['/opt/quickstart-python/bin/python','-c',
        "import sys,json;sys.path.insert(0,'/opt/quickstart');import supervisor as s;"
        "s.atomic(s.DATA/'run/deployment-metadata.json',json.dumps(json.load(sys.stdin)))"],json.dumps(document))
    print('Recorded deployment: '+network+' ('+details(quickstart,'TOOL_HOST_DIRECT')['networkId']+')')
    return document


def verify_deployment(target):
    """Reject stale local setup without adopting new trust or changing credentials."""
    remedy = 'Run deploy.bat' + (' -Win' if target == 'windows' else
                               ' -Target linux' if target == 'linux' else '') + ' again.'
    directory = STATE / 'agents'
    if not all((directory / name).is_file() for name in (target + '.json', target + '.token', 'gateway-ca.crt')):
        raise ValueError('The selected deployment is incomplete. ' + remedy)
    descriptor = json.loads((directory / (target + '.json')).read_text(encoding='utf-8'))
    if descriptor.get('gateway') != gateway_url():
        raise ValueError('The selected deployment belongs to another Gateway URL. ' + remedy)
    expires = descriptor.get('expiresAtUnixMs', 0)
    if not isinstance(expires, int) or expires <= int(time.time() * 1000):
        raise ValueError('The selected agent credential has expired. ' + remedy)
    try:
        saved = ssl.PEM_cert_to_DER_cert((directory / 'gateway-ca.crt').read_text(encoding='ascii').strip())
        current = ssl.PEM_cert_to_DER_cert(docker('quickstart', ['cat', '/data/keys/device-ca.crt']).strip())
    except (OSError, ValueError):
        raise ValueError('Cannot verify the saved Gateway trust. ' + remedy) from None
    if saved != current:
        raise ValueError('Gateway trust changed after this deployment was configured. ' + remedy)
    if descriptor.get('platform') == 'linux' and descriptor.get('deviceId') != linux_status().get('deviceId'):
        raise ValueError('The selected Linux device changed after deployment. ' + remedy)
    return descriptor


def validate_registration(record):
    if not re.fullmatch(r'device-[a-f0-9]{32}', record['deviceId']) or record['platform'] not in {'linux', 'windows'}:
        raise ValueError('Device registration requires an enrolled ID and linux/windows platform')
    return {key: record[key] for key in ('deviceId', 'platform', 'gateway')}


def device_available(row, now):
    endpoint = row.get('endpointDevice') or {}
    expires = endpoint.get('connectionExpiresAtUnixMs')
    seen = endpoint.get('lastSeenUnixMs', 0)
    return not row.get('systemExecutor') and (row.get('directoryDevice') or {}).get('enabled') is True \
        and endpoint.get('state') == 'ACTIVE' and endpoint.get('connectionApproved') is True \
        and (expires is None or expires > now) and 0 <= now - seen < 120000


def select_device(config, registrations, target, device_id=None):
    group = config.api('/api/control/v1/device-groups/' + DEVICE_GROUP)
    if not group['enabled']:
        raise ValueError(DEVICE_GROUP + ' must be enabled')
    members = set(group['deviceIds'])
    endpoints = {row['deviceId']: row for row in config.api('/api/control/v1/endpoint/devices')['items']}
    now = int(time.time() * 1000)
    candidates = [record for record in registrations if record.get('gateway') == gateway_url() and record['deviceId'] in members
                  and (target == 'any' or record['platform'] == target)
                  and (not device_id or record['deviceId'] == device_id)
                  and device_available(endpoints.get(record['deviceId'], {}), now)]
    if not candidates:
        raise ValueError(f'No available prepared {target} device in {DEVICE_GROUP}. '
                         'Enroll/approve and enable the device on this Gateway; for Windows run '
                         'prepare-windows.ps1 and select its enrolled ID with --device-id.')
    # Pick once per run. A write is never retried against a different device.
    return secrets.choice(candidates)


def register_devices(config, args):
    status = linux_status()
    device = status['deviceId']
    if args.approve_linux_device and status['state'] == 'PENDING':
        review = config.api('/api/control/v1/endpoint/enrollments/review?code=' + status['userCode'])
        if (review['deviceId'], review['keyFingerprint']) != (device, status['keyFingerprint']):
            raise ValueError('Enrollment fingerprint does not match the Compose device')
        config.api('/api/control/v1/endpoint/enrollments/decision',
            dict(userCode=status['userCode'], keyFingerprint=status['keyFingerprint'], choice='APPROVE',
                 unlimitedConnection=False, connectionExpiresAtUnixMs=int(time.time()*1000)+86400000))
        wait_device(config, device)
    records = [dict(deviceId=device, platform='linux', gateway=gateway_url())]
    # Other devices are explicit operator selections; their package executor and
    # public release trust must be configured through their administrator first.
    if args.device_id and args.device_id != device:
        if args.target != 'windows':
            raise ValueError('An external device requires --target windows and an enrolled --device-id')
        records.append(validate_registration(dict(deviceId=args.device_id, platform='windows', gateway=gateway_url())))
    for record in records:
        groups = config.api('/api/control/v1/devices/'+record['deviceId']+'/groups')['groupIds']
        config.memberships('devices', record['deviceId'], sorted(set(groups) | {DEVICE_GROUP}))
    return records


def configure_package_device():
    # These are public verification keys, not signing keys or enrollment custody.
    trust = dict(releaseKeys=json.loads(docker('quickstart', ['cat', '/data/keys/release-keys.json'])),
                 organizationKeys=json.loads(docker('quickstart', ['cat', '/data/keys/organization-keys.json'])))
    shared.private_file(STATE/'deployment-trust.json', json.dumps(trust))
    docker('linux-device', ['python', '-c',
        "import sys,json;from pathlib import Path;p=Path('/etc/olo-toolgate/client.json');"
        "c=json.loads(p.read_text());c['deployment']=json.load(sys.stdin);p.write_text(json.dumps(c))"], json.dumps(trust))
    subprocess.run(COMPOSE+['restart', 'linux-device'], check=True, capture_output=True, cwd=HERE)


def load_mimic():
    spec = importlib.util.spec_from_file_location('agent_mimic', ROOT / 'debug/agent-mimic.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def wait_package_executor(release, timeout=240):
    """A fresh heartbeat can precede package recovery after a service restart."""
    expected = {runtime['id'] for runtime in release['document']['runtimes']}
    deadline = time.monotonic()+timeout
    while True:
        try:
            status = json.loads(docker('linux-device', ['/usr/local/lib/olo-toolgate/olo-toolgate-client', 'runtimes', 'status']))
            health = status.get('health') or {}
            ready = {runtime['runtimeId'] for runtime in health.get('runtimes', []) if runtime['state'] == 'READY'}
            if health.get('ready') is True and expected and expected <= ready:
                return
        except ValueError:
            pass  # Native IPC returns busy while package pull/self-tests hold its service lock.
        if time.monotonic() >= deadline:
            raise ValueError('The signed package executor did not recover after restart; inspect device activation logs')
        time.sleep(2)


def verify_discovery(selection, directory, target, timeout=30):
    """Run the agent's own discovery once, so deploy fails with the cause instead of execute."""
    gateway = load_mimic().Agent(gateway_url(), directory / (target + '.token'), directory / 'gateway-ca.crt')
    deadline = time.monotonic() + timeout
    while True:
        missing = sorted(TOOLS - {tool['name'] for tool in gateway.rpc('tools/list')['tools']})
        if not missing:
            return
        if time.monotonic() >= deadline:
            break
        time.sleep(3)
    device, platform = selection['deviceId'], selection['platform']
    raise ValueError(f'The {platform} device {device} does not offer {", ".join(missing)}. '
                     'Check the signed package rollout, current grants and device report; rerun deploy.bat.')


def configure_execution(config, name, agent, device, profiles):
    """Keep the shared selection group, then bind this run to its selected device and tools."""
    base = dict(enabled=True, revision=1)
    devices, agents, tools = name+'-devices', name+'-agents', TOOL_GROUP
    config.upsert('device-groups', dict(**base, id=devices, name='LangChain selected device', deviceIds=[device]))
    config.upsert('agent-groups', dict(**base, id=agents, name='LangChain selected agent', agentIds=[agent], roleIds=[]))
    # SDK exports use invoke/CUSTOM, while standard presets use read/write/FILE.
    # Keep package authorization explicit and restricted to these exact exports.
    config.memberships('agents', agent, ['ReadAndWriteAgentGroup', agents])
    scope = config.api('/api/control/v1/grants/standard-ReadAndWrite-capability')['scope']
    scope = {**scope, 'toolGroups': dict(ids=[tools], all=False), 'deviceGroups': dict(ids=[devices], all=False),
             'actions': sorted({action['name'] for profile in profiles for action in profile['tool']['definition']['actions']}),
             'allActions': False,
             'resources': [dict(kind='CUSTOM', locator='tool://'+tool, match='EXACT') for tool in sorted(TOOLS)]}
    for purpose in ('CAPABILITY', 'SERVICE'):
        config.upsert('grants', dict(**base, id=name+'-'+purpose.lower(), name='LangChain '+purpose.lower(),
                                    sourceType='AGENT_GROUP', sourceId=agents, purpose=purpose, scope=scope))
    config.upsert('policies', dict(**base, id=name+'-access', name='LangChain selected-device access', decision='ALLOW',
        scope=scope, teams=dict(ids=[], all=False), agentGroups=dict(ids=[agents], all=False),
        approverTeams=dict(ids=[], all=False)))
    binding = name+'-binding'
    config.upsert('bindings', dict(**base, id=binding, name='LangChain selected-device execution',
        toolGroupId=tools, deviceGroupId=devices, actions=scope['actions'],
        allowedPackageDigests=sorted({profile['tool']['packageDigest'] for profile in profiles}),
        requireOnline=True, ownerDependency=False))
    return binding, devices


def provision(args):
    origin = wait_ready()
    config = Configuration(origin, args.bootstrap_local)
    if config.api('/api/control/v1/admin-session')['role'] != 'SUPER_ADMIN':
        raise ValueError('Initial example provisioning requires a Super Admin API login')
    config.presets()
    registrations = register_devices(config, args)
    selection = select_device(config, registrations, args.target, args.device_id)
    device = selection['deviceId']
    configure_package_device()
    wait_device(config, device)
    release = packages.publish(config, STATE/'package/hotfolder.tgpkg')
    selected = [{'tool': tool} for tool in config.all('tools') if tool['id'] in TOOLS]
    if {profile['tool']['id'] for profile in selected} != TOOLS:
        raise ValueError('The client release did not register the required HotFolder tools')
    if not any(group['id'] == TOOL_GROUP for group in config.all('tool-groups')):
        config.upsert('tool-groups', dict(id=TOOL_GROUP, name='LangChain HotFolder package tools', enabled=True,
                                        revision=1, toolIds=[]))
    for profile in selected:
        tool = profile['tool']
        if not tool['enabled']:
            config.upsert('tools', {**tool, 'enabled': True})
        config.memberships('tools', tool['id'], [TOOL_GROUP])
    print(f'Selected {selection["platform"]}: {device} from {DEVICE_GROUP}', flush=True)
    wait_device(config, device)
    packages.assign(config, release, device)
    name = 'langchain-' + args.target
    agent = name + '-agent'
    workload = name + '-workload'
    config.upsert('agents', dict(id=agent, name='LangChain ' + args.target + ' example', enabled=True,
                                revision=1, ownerUserId=config.username))
    binding_id, execution_group = configure_execution(config, name, agent, device, selected)
    expires = int(time.time()*1000) + 86400000
    token = secrets.token_urlsafe(48)
    digest = hashlib.sha256(token.encode()).hexdigest()
    old = next((row for row in config.all('workload-bindings') if row['id'] == workload), None)
    epoch = old['credentialEpoch'] + 1 if old else 1
    config.upsert('workload-bindings', dict(id=workload, name='LangChain example credential', enabled=True,
        revision=1, agentId=agent, mode='SERVICE', issuer='gateway', subject=name, audience='gateway',
        credentialSha256=digest, credentialEpoch=epoch, expiresAtUnixMs=expires))
    # The context names no device; this binding's group contains only the selected member.
    # Quickstart allocates a UUID tenant at first start; the container fills it in below.
    credential = dict(tokenSha256=digest, expiresAtUnixMs=expires, context=dict(requestId=name,
        tenantId=None, mode='SERVICE', credentialKind='STATIC_TOKEN', agentId=agent, workloadBindingId=workload,
        chain=[], credentialEpoch=epoch, expiresAtUnixMs=expires, credentialSha256=digest, bindingId=binding_id))
    # Install opaque bearer facts only. No private signing key or server-side verifier is read.
    docker('quickstart', ['/opt/quickstart-python/bin/python', '-c',
        "import sys,json,time,os;os.umask(0o077);sys.path.insert(0,'/opt/quickstart');import supervisor as s;"
        "p=s.DATA/'client-runtime-credentials.json';v=s.strict(p.read_bytes()) if p.exists() else [];c=json.load(sys.stdin);"
        "c['context']['tenantId']=s.strict((s.DATA/'state/tenant.json').read_bytes())['tenantId'];"
        "v=[x for x in v if x['expiresAtUnixMs']>int(time.time()*1000) and x['context'].get('workloadBindingId')!=c['context']['workloadBindingId']];"
        "assert len(v)<32;s.atomic(p,json.dumps(v+[c]))"], json.dumps(credential))
    directory = STATE / 'agents'
    shared.private_file(directory / (args.target + '.token'), token)
    descriptor = dict(deviceId=device, platform=selection['platform'], deviceGroupId=execution_group,
                      selectionGroupId=DEVICE_GROUP,
                      agentId=agent, bindingId=binding_id, expiresAtUnixMs=expires, gateway=gateway_url())
    shared.private_file(directory / (args.target + '.json'), json.dumps(descriptor))
    shared.private_file(directory / 'gateway-ca.crt', docker('quickstart', ['cat', '/data/keys/device-ca.crt']))
    result = subprocess.run(COMPOSE + ['restart', 'quickstart'], capture_output=True, text=True, cwd=HERE)
    if result.returncode:
        raise ValueError('Could not restart this example\'s Quickstart to load its credential')
    wait_ready()
    # Shutdown grace still permits old-process check-ins. Only a heartbeat after
    # the replacement Gateway is ready proves the device has reconnected.
    reconnect_after = int(time.time() * 1000)
    if selection['platform'] == 'linux':
        # Clear retry backoff from the gateway restart; identity/files stay in volumes.
        result = subprocess.run(COMPOSE + ['restart', 'linux-device'], capture_output=True, text=True, cwd=HERE)
        if result.returncode:
            raise ValueError('Could not reconnect this example\'s Linux device')
    print('Waiting for a fresh device check-in after the Gateway restart (up to 6 minutes for client retry backoff).', flush=True)
    wait_device(config, device, seen_after=reconnect_after, timeout=360)
    if selection['platform'] == 'linux':
        wait_package_executor(release)
    verify_discovery(selection, directory, args.target)
    sync_deployment_metadata()
    print(f'Configured {selection["platform"]}: {device} in {DEVICE_GROUP}. Configuration applied through the Control API. Credentials expire in 24 hours.')
    print('Private runner credentials: ' + str(directory))


def main():
    load_environment()
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    setup = sub.add_parser('setup')
    setup.add_argument('--target', choices=['any', 'linux', 'windows'], default='any',
                       help='Select an available prepared member of ReadAndWriteDeviceGroup, optionally filtering OS')
    setup.add_argument('--bootstrap-local', action='store_true', help='Use/change the isolated stack\'s one-time installation passwords; retain independent reviewer identities')
    setup.add_argument('--approve-linux-device', action='store_true', help='Approve only the fingerprint-checked Compose Linux device for 24 hours')
    setup.add_argument('--device-id')
    sub.add_parser('prepare', help='Build the HotFolder package and prepare the local OCI registry')
    sub.add_parser('status')
    verify = sub.add_parser('verify-deployment', help='Check saved trust and target before running the agent')
    verify.add_argument('--target', choices=['any', 'linux', 'windows'], default='any')
    sub.add_parser('sync-deployment', help='Refresh public container and network details from Docker')
    args = parser.parse_args()
    STATE.mkdir(mode=0o700, exist_ok=True)
    if args.command == 'prepare':
        packages.prepare(COMPOSE, STATE, os.environ)
    elif args.command == 'setup':
        provision(args)
    elif args.command == 'verify-deployment':
        verify_deployment(args.target)
        print('Saved deployment matches the current Gateway trust and selected device.')
    elif args.command == 'sync-deployment':
        sync_deployment_metadata()
    else:
        print(json.dumps(linux_status(), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as failure:
        print('FAILED: ' + (str(failure) if isinstance(failure, ValueError) else
              'Example setup failed; inspect Compose health and configuration'), file=sys.stderr)
        raise SystemExit(1)
