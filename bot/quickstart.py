# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Operator-selected bot installation on the managed community Quickstart stack."""
import argparse
from copy import deepcopy
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from bot.bundle import MAX_BUNDLE, inventory, verify
from tools.admin.control import Control, Refused, read_file
from tools.admin.import_workflow import call, deploy
from tools.admin.provision_devices import allocate, wait_active
from tools.admin.qualify_native_files import qualify


def relative_path(value):
    if (not isinstance(value, str) or not value or '\\' in value or ':' in value
            or value.startswith('/') or any(part in ('', '.', '..') for part in value.split('/'))):
        raise Refused('Use a relative bot data path without traversal')
    return value


def catalog(path=None):
    # JSON flow syntax is valid YAML 1.2 and keeps the launcher dependency-free.
    value = json.loads((path or ROOT/'bot/AutoConfigure.yaml').read_text(encoding='utf-8'))
    if value.get('formatVersion') != 1 or not isinstance(value.get('bots'), list):
        raise Refused('Unsupported AutoConfigure catalog')
    seen = set()
    for row in value['bots']:
        if (set(row) != {'id', 'name', 'workflowId', 'requiresOllama', 'directories', 'logFiles'}
                or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', row['id']) or row['id'] in seen
                or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', row['workflowId'])
                or type(row['requiresOllama']) is not bool or not isinstance(row['name'], str)
                or any(c in row['name'] for c in '\r\n\0')):
            raise Refused('Invalid or duplicate bot catalog entry')
        seen.add(row['id'])
        for path_value in row['directories'] + row['logFiles']: relative_path(path_value)
    return value['bots']


def selection(answer, rows):
    answer = answer.strip()
    if answer in ('', '0'): return []
    parts = answer.split(',')
    if any(not re.fullmatch(r'[1-9][0-9]*', part.strip()) for part in parts):
        raise Refused('Choose 0 None, or comma-separated bot numbers such as 2,3')
    numbers = list(dict.fromkeys(int(part.strip()) for part in parts))
    if any(number > len(rows) for number in numbers): raise Refused('Unknown bot number')
    result = [rows[number-1] for number in numbers]
    if len({row['workflowId'] for row in result}) != len(result):
        raise Refused('The hotswap variants replace the same bot; select only one variant')
    return result


def choose(answer=None):
    rows = catalog()
    if answer is not None: return selection(answer, rows)
    if not sys.stdin.isatty(): return []
    print('\nBots to install and configure:\n  (0) None [default]')
    for number, row in enumerate(rows, 1): print(f'  ({number}) {row["name"]}')
    print('File bots allocate this managed local device, create their demo folders, and')
    print('qualify its native filesystem tools for one day. Existing files are preserved.')
    print('A current tenant Super Admin can run all installed demos.')
    while True:
        try: return selection(input('Select bots (for example 2,3) [0]: '), rows)
        except Refused as error: print(str(error))


def private_json(stack, path, value):
    stack.secure_directory(path.parent)
    temporary = path.with_name(path.name+'.'+uuid.uuid4().hex+'.next')
    try:
        stack.private(temporary, json.dumps(value, indent=2)+'\n')
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()


def prompt_credentials(stack, *, interactive=True):
    """Blank preserves existing custody; new model keys remain in memory until vault import."""
    updates = {}
    if not interactive: return updates, False
    print('\nCredentials: Enter keeps an existing value; empty optional providers stay disabled.')
    print('The local LangGraph service token is provisioned automatically by Control.')
    value = getpass.getpass('OpenRouter API key (optional standby model; hidden): ').strip()
    if value: updates['openrouter-key'] = value
    path = stack.HERE/'credentials/observability.json'
    settings = json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else {}
    original = deepcopy(settings)
    providers = (
        ('langsmith', 'LangSmith', [('apiKey', 'API key', True), ('baseUrl', 'base URL', False),
                                  ('project', 'project', False), ('workspaceId', 'workspace ID (optional)', False)]),
        ('langfuse', 'Langfuse', [('publicKey', 'public key', True), ('secretKey', 'secret key', True),
                                ('baseUrl', 'base URL', False)]),
        ('phoenix', 'Phoenix', [('apiKey', 'API key', True), ('baseUrl', 'OTLP base URL', False),
                              ('project', 'project', False)]),
    )
    for provider, title, fields in providers:
        current = dict(settings.get(provider, {}))
        for field, label, secret in fields:
            if not secret and not any(current.get(key) for key, _, hidden in fields if hidden): continue
            if secret:
                hint = 'saved' if current.get(field) else 'optional'
                value = getpass.getpass(f'{title} {label} ({hint}; hidden): ').strip()
            else:
                value = input(f'{title} {label} [{current.get(field, "provider default")}]: ').strip()
            if value: current[field] = value
        if current: settings[provider] = current
    # Validate complete cloud settings before replacing any saved credential file.
    for provider in ('langfuse', 'phoenix'):
        current = settings.get(provider, {})
        fields = ('publicKey', 'secretKey') if provider == 'langfuse' else ('apiKey', 'baseUrl')
        if any(current.get(field) for field in fields) and not all(current.get(field) for field in fields):
            raise Refused('Complete the '+provider+' credentials; existing settings were preserved')
    if settings != original:
        spec = importlib.util.spec_from_file_location('olo_monitoring_setup', ROOT/'debug/profiled/observability/manage.py')
        monitoring = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(monitoring)
        monitoring.configuration(settings, allow_local=True)
        private_json(stack, path, settings)
    enabled = any(settings.get(provider, {}).get('apiKey') for provider in ('langsmith', 'phoenix'))
    enabled = enabled or bool(settings.get('langfuse', {}).get('secretKey'))
    return updates, enabled


def command(stack, *args, input=None):
    # Captured output may contain private device or provider material. Never echo failures.
    result = subprocess.run(['docker', 'compose', '-p', stack.PROJECT, '-f', str(stack.STATE/'compose.json'),
                             *args], cwd=ROOT, input=input, text=True, capture_output=True)
    if result.returncode: raise Refused('Managed stack command failed; inspect service status; no automatic retry')
    return result.stdout


def observe(predicate, timeout=180):
    deadline = time.monotonic()+timeout
    while True:
        result = predicate()
        if result: return result
        if time.monotonic() >= deadline: raise Refused('Timed out waiting for bot dependencies; setup is incomplete')
        time.sleep(1)


def local_device(stack, client):
    def status():
        return json.loads(command(stack, 'exec', '-T', 'device', 'python3', '-c',
            "from pathlib import Path;p=Path('/run/olo-toolgate/device-status.json');print(p.read_text() if p.exists() else '{}')"))
    current = observe(lambda: (value if (value := status()).get('deviceId') else None))
    device = current['deviceId']
    if current['state'] == 'PENDING':
        code = current['userCode']
        if not re.fullmatch(r'[A-Za-z0-9]+', code): raise Refused('Invalid local enrollment code')
        review = call(client, '/api/control/v1/endpoint/enrollments/review?code='+code)
        if (review['deviceId'], review['keyFingerprint'], review['state']) != (device, current['keyFingerprint'], 'PENDING'):
            raise Refused('Local enrollment identity changed; no approval was sent')
        call(client, '/api/control/v1/endpoint/enrollments/decision', dict(userCode=code,
            keyFingerprint=current['keyFingerprint'], choice='APPROVE',
            connectionExpiresAtUnixMs=int(time.time()*1000)+86400000))
    wait_active(client, device, 180)
    return device


def prepare_files(stack, row):
    paths = [relative_path(value) for value in row['directories']]
    logs = [relative_path(value) for value in row['logFiles']]
    if not paths and not logs: return
    command(stack, 'exec', '-T', 'device', 'python3', '-c',
        "import json,os,sys;from pathlib import Path;os.umask(0o077);"
        "root=Path('/var/lib/olo-toolgate/files').resolve();data=json.load(sys.stdin);"
        "paths=[root/p for p in data['directories']];logs=[root/p for p in data['logs']];"
        "assert all(p.resolve().is_relative_to(root) for p in paths+logs);"
        "[(p.mkdir(parents=True,exist_ok=True),p.chmod(0o700)) for p in paths];"
        "[(p.open('x').close(),p.chmod(0o600)) for p in logs if not p.exists()]",
        input=json.dumps(dict(directories=paths, logs=logs)))


def load_bundle(folder, row, *, version=None, commit=None):
    indexes = list(folder.glob('olo-profiles-*.json'))
    if len(indexes) != 1: raise Refused('Exactly one matching release bot inventory is required')
    index = json.loads(indexes[0].read_text(encoding='utf-8'))
    if index.get('format') != 'olo-profiles' or (version and index['version'] != version) or (commit and index['sourceCommit'] != commit):
        raise Refused('Bot inventory does not match the running release')
    rows = [item for item in index['profiles'] if item['configuration'] == row['id']]
    if len(rows) != 1 or rows[0]['workflowId'] != row['workflowId']: raise Refused('Bot is absent or ambiguous in the release')
    item = rows[0]
    if not re.fullmatch(r'[A-Za-z0-9_.+-]+\.olo', item['filename']): raise Refused('Unsafe bot filename')
    path = folder/item['filename']
    if path.is_symlink(): raise Refused('Linked bot artifact refused')
    raw = read_file(path, MAX_BUNDLE)
    if 'sha256:'+hashlib.sha256(raw).hexdigest() != item['sha256'] or len(raw) != item['bytes']:
        raise Refused('Bot release checksum mismatch')
    manifest = verify(raw)
    if (manifest['workflowId'] != row['workflowId'] or manifest['build']['version'] != index['version']
            or manifest['build']['sourceCommit'] != index['sourceCommit'] or manifest['packages'] != item['packages']):
        raise Refused('Bot bundle identity differs from its inventory')
    return raw


def fetch(url, maximum):
    if not url.startswith(('https://api.github.com/repos/olo-labs/olo-toolgate/',
                           'https://github.com/olo-labs/olo-toolgate/releases/download/')):
        raise Refused('Unexpected bot release location')
    request = urllib.request.Request(url, headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'olo-quickstart'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response: data = response.read(maximum+1)
    except OSError: raise Refused('Cannot download matching bot release assets; existing installation preserved') from None
    if len(data) > maximum: raise Refused('Bot release asset exceeds size limit')
    return data


def release_bundles(stack, rows):
    ids = command(stack, 'ps', '-q', 'quickstart', 'langgraph', 'device').split()
    if len(ids) != 3: raise Refused('All three managed services must be running before bot setup')
    result = subprocess.run(['docker', 'inspect', *ids], text=True, capture_output=True, check=True)
    services = json.loads(result.stdout)
    identities = {(item['Config'].get('Labels', {}).get('org.opencontainers.image.version'),
                   item['Config'].get('Labels', {}).get('org.opencontainers.image.revision')) for item in services}
    if len(identities) != 1: raise Refused('Application image versions differ; choose one matching release')
    version, commit = identities.pop()
    if not version or not re.fullmatch(r'[0-9A-Za-z.+-]{1,128}', version) or not commit or not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise Refused('Running images do not identify their release version and source')
    folder = stack.STATE/'bots'/version
    folder.mkdir(parents=True, exist_ok=True)
    indexes = list(folder.glob('olo-profiles-*.json'))
    if not indexes:
        release = None
        for page in range(1, 11):
            releases = json.loads(fetch(f'https://api.github.com/repos/olo-labs/olo-toolgate/releases?per_page=100&page={page}', 8*1024*1024))
            release = next((item for item in releases if any(asset['name'] == 'olo-profiles-'+version+'.json' for asset in item['assets'])), None)
            if release or len(releases) < 100: break
        if release is None: raise Refused('No bot inventory matches the running images; choose a complete published release')
        asset = next(item for item in release['assets'] if item['name'] == 'olo-profiles-'+version+'.json')
        raw = fetch(asset['browser_download_url'], 4*1024*1024)
        index = json.loads(raw)
        if index['sourceCommit'] != commit or index['version'] != version: raise Refused('Release source mismatch')
        # Retain the exact release URLs, without any credentials, for the selected downloads.
        (folder/'assets.json').write_text(json.dumps({item['name']: item['browser_download_url'] for item in release['assets']}), encoding='utf-8')
        (folder/asset['name']).write_bytes(raw)
    index = json.loads(next(folder.glob('olo-profiles-*.json')).read_text(encoding='utf-8'))
    assets = json.loads((folder/'assets.json').read_text(encoding='utf-8'))
    for row in rows:
        matches = [item for item in index['profiles'] if item['configuration'] == row['id']]
        if len(matches) != 1: raise Refused('Selected bot is missing from the running release')
        name = matches[0]['filename']
        if not re.fullmatch(r'[A-Za-z0-9_.+-]+\.olo', name): raise Refused('Unsafe bot release filename')
        if not (folder/name).exists(): (folder/name).write_bytes(fetch(assets[name], MAX_BUNDLE))
        load_bundle(folder, row, version=version, commit=commit)
    return folder


def configure_bot(stack, client, row, raw, credential_updates):
    manifest = verify(raw)
    files = inventory(raw, MAX_BUNDLE, 256*1024*1024)
    profile = json.loads(files['workflow.json'])
    references = json.loads(files['referenced-data.json'])
    if manifest['workflowId'] != row['workflowId']: raise Refused('Selected bot identity mismatch')
    directories = set(); logs = set()
    for connection in manifest['connections']:
        config = connection['assignmentSpec']['config']
        if 'folder' in config: directories.add(config['folder'])
        if 'file' in config: logs.add(config['file']); directories.add(str(PurePosixPath(config['file']).parent))
    for workflow in profile['workflows']['workflows']:
        for node in workflow['graph']['config'].get('nodes', []):
            if node['kind'] != 'tool': continue
            for field in ('path', 'destination'):
                value = node.get('input', {}).get(field)
                if isinstance(value, str): directories.add(str(PurePosixPath(value).parent))
    if directories != set(row['directories']) or logs != set(row['logFiles']):
        raise Refused('Bot folder prerequisites differ from this launcher; select matching release source')
    device = None
    scopes = {(item['secretName'], item['toolGroupId'], item['deviceGroupId'])
              for item in profile['workflows']['modelDeployments']}
    def setup(_profile):
        nonlocal device
        existing = call(client, '/api/control/v1/vault')['items']
        for name, tool_group, device_group in sorted(scopes):
            value = credential_updates.get(name)
            saved = any(item['name'] == name and item['toolGroupId'] == tool_group
                        and item['deviceGroupId'] == device_group for item in existing)
            if name == 'ollama-local-key' and not saved:
                value = command(stack, 'exec', '-T', 'quickstart', 'cat', '/custody/ollama/token').strip()
            if value:
                call(client, '/api/control/v1/vault', dict(name=name, value=value,
                    toolGroupId=tool_group, deviceGroupId=device_group))
        groups = [group['id'] for group in references['deviceGroups']]
        if row['directories'] or row['logFiles']:
            device = local_device(stack, client)
            allocate(client, device, groups)
            prepare_files(stack, row)
    def qualification(_profile):
        if device is None: return
        tools = {node['toolRef'] for workflow in profile['workflows']['workflows']
                 for node in workflow['graph']['config'].get('nodes', []) if node['kind'] == 'tool'}
        for group in sorted({group for package in profile['packages'] if package['packageId'] == 'examples.hotfolder'
                             for group in package['deviceGroupIds']}):
            qualify(client, device, group, tools)
    result = deploy(client, raw, after_references=setup, after_provision=qualification)
    if result['state'] != 'PUBLISHED': raise Refused('Bot publication is not confirmed; setup is incomplete')
    # Publication alone is insufficient for trigger-driven bots: wait for live assignment readiness.
    for connection in manifest['connections']:
        def ready():
            from tools.admin.import_workflow import assignments
            matches = [item for item in assignments(client) if item['metadata']['name'] == connection['assignment']]
            return len(matches) == 1 and assignment_ready(matches[0])
        observe(ready)
    print(row['id']+': configured and published; ready to run.', flush=True)
    return result


def assignment_ready(assignment):
    status = assignment['status']
    counts = status['instances']
    return (status['observedGeneration'] == assignment['metadata']['generation']
            and counts['desired'] > 0 and counts['running'] == counts['desired']
            and all(counts[field] == 0 for field in ('pending', 'starting', 'failed', 'unsupported', 'stale'))
            and any(condition['type'] == 'Available' and condition['status'] == 'True'
                    for condition in status['conditions']))


def setup_authority(client):
    session = call(client, '/api/control/v1/admin-session')
    if session['role'] != 'SUPER_ADMIN': raise Refused('Bot setup requires a current tenant Super Admin')
    user = session.get('bootstrapAdminUserId')
    if not user or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', user):
        raise Refused('The managed installation has no protected setup identity')
    effective = call(client, '/api/control/v1/users/'+user+'/effective-access')
    purposes = {grant['purpose'] for group in effective['memberships'] if group['enabled']
                for grant in group['grants'] if grant['enabled'] and grant['id'].startswith('super-admin-')
                and grant['scope']['allActions']}
    if not {'HUMAN', 'SECRET'}.issubset(purposes):
        raise Refused('Application images do not expose current tenant Super Admin runtime entitlements; publish matching current images before demo setup')


def configure_selected(stack, rows, *, bundles_dir=None, client=None, credential_updates=None):
    if not rows: return []
    folder = bundles_dir or release_bundles(stack, rows)
    # Check every selected bundle before making any bot configuration changes.
    bundles = [load_bundle(folder, row) for row in rows]
    results = []
    for row, raw in zip(rows, bundles):
        print('Configuring '+row['id']+'...', flush=True)
        operator = client or stack.operator_client()
        setup_authority(operator)
        try: results.append(configure_bot(stack, operator, row, raw, credential_updates or {}))
        except Refused as error: raise Refused(row['id']+': '+str(error)) from None
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bot', required=True)
    parser.add_argument('--bundles-dir', type=Path)
    parser.add_argument('--origin')
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--ca-file', type=Path)
    parser.add_argument('--non-interactive', action='store_true')
    args = parser.parse_args()
    rows = [row for row in catalog() if row['id'] == args.bot]
    if not rows: parser.error('Unknown bot')
    if bool(args.origin) != bool(args.token_file): parser.error('--origin and --token-file are required together')
    # Standalone configurators use the same published installation and saved credentials.
    sys.path.insert(0, str(ROOT/'deploy/docker/quickstart/dev'))
    import manage
    manage.configure(manage.settings())
    # A standalone bot entrypoint also brings up its managed prerequisites. Explicit
    # --bots 0 prevents recursion and leaves credential prompting to this process.
    startup = [sys.executable, str(ROOT/'deploy/docker/quickstart/dev/manage.py'), 'start',
               '--bots', '0', '--without-monitoring']
    startup.append('--with-ollama' if rows[0]['requiresOllama'] else '--without-ollama')
    subprocess.run(startup, check=True)
    import stack
    updates, monitoring = prompt_credentials(stack, interactive=not args.non_interactive and sys.stdin.isatty())
    if monitoring:
        subprocess.run([sys.executable, str(ROOT/'debug/profiled/observability/manage.py'), 'start'], check=True)
    client = Control(args.origin, args.token_file, args.ca_file) if args.origin else None
    configure_selected(stack, rows, bundles_dir=args.bundles_dir, client=client, credential_updates=updates)


if __name__ == '__main__':
    try: main()
    except Refused as error:
        print('Bot setup incomplete: '+str(error), file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError):
        print('Bot setup incomplete. Inspect Control readiness and service status before retrying; no effects were automatically replayed.', file=sys.stderr)
        raise SystemExit(1)
