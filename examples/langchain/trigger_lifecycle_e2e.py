# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""M12 scenario 10 lifecycle checks on an isolated Quickstart with four enrolled devices."""
import argparse
import base64
import json
import re
import secrets
import time
from urllib.parse import urlencode

import manage

BASE = '/api/control/v1/triggers'


def wait_phases(config, assignment, expected, timeout=300):
    deadline = time.monotonic() + timeout
    phases = {}
    while time.monotonic() < deadline:
        page = config.api(BASE + '/instances?' + urlencode({'assignment': assignment}))
        phases = {row['executorId']: row['phase'] for row in page['items']}
        if all((phases.get(device) in (None, 'Removed') if phase == 'Removed'
                else phases.get(device) == phase) for device, phase in expected.items()):
            return
        time.sleep(2)
    raise ValueError(f'Expected device phases {expected}, observed {phases}')


def action(config, assignment, operation, scope, target):
    path = f'{BASE}/assignments/{assignment}'
    revision = config.api(path)['metadata']['resourceVersion']
    config.api(path + ':action', {
        'action': operation,
        'scope': {scope: [target]},
        'reason': 'M12 scenario 10 lifecycle acceptance',
    }, etag=revision)


def checkpoint_probe(config, assignment, device, service, timeout, previous=None):
    """Read only the synthetic marker's local checkpoint on the disposable fixture."""
    rows = config.api(BASE + '/instances?' + urlencode({'assignment': assignment, 'executorId': device}))['items']
    instance = next(row['instanceId'] for row in rows if row['executorId'] == device)
    if not re.fullmatch(r'ti-[a-f0-9]{32}', instance):
        raise ValueError('Unexpected trigger instance identifier')
    subject = 'inbox/m12-' + secrets.token_hex(8) + '.txt'
    manage.docker(service, ['python', '-c',
        "import os,sys; from pathlib import Path; os.umask(0o077);"
        "p=Path('/var/lib/olo-toolgate/hotfolder')/sys.argv[1];"
        "with_file=p.open('x');with_file.write('synthetic checkpoint marker');with_file.close()", subject])
    deadline = time.monotonic() + timeout
    path = '/var/lib/olo-toolgate/triggers/' + instance + '/checkpoint-local.json'
    while time.monotonic() < deadline:
        try:
            checkpoint = json.loads(manage.docker(service, ['cat', path]))
            if checkpoint['instanceId'] != instance:
                raise ValueError('Checkpoint belongs to a different trigger instance')
            value = checkpoint['value']
            state = json.loads(base64.b64decode(value['state'], validate=True))
            reported = state.get('reported', {})
            if subject in reported:
                sequence = value['afterOutboxSeq']
                epoch = value['leaseEpoch']
                if previous is not None:
                    old_subject, old_sequence, old_epoch = previous
                    if old_subject not in reported:
                        raise RuntimeError('The first marker disappeared from the post-resume checkpoint')
                    if epoch != old_epoch or sequence != old_sequence + 1:
                        raise RuntimeError('Resume changed the lease epoch or emitted more than the new marker')
                return subject, sequence, epoch
        except (ValueError, KeyError):
            pass
        time.sleep(2)
    raise ValueError('The synthetic marker did not appear in the device checkpoint')


def run(config, devices, package_version, timeout, checkpoint_service=None):
    d1, d2, d3, d4 = devices
    if len(set(devices)) != 4:
        raise ValueError('Provide four distinct device IDs')
    existing = {row['id'] for row in config.all('devices')}
    if not set(devices).issubset(existing):
        raise ValueError('All four devices must be enrolled before the acceptance run')
    assignment = 'm12-lifecycle-' + secrets.token_hex(5)
    g1, g2 = assignment + '-g1', assignment + '-g2'

    def group(name, members):
        config.upsert('device-groups', {
            'id': name, 'name': name, 'enabled': True, 'revision': 1, 'deviceIds': members,
        })

    def expect(expected):
        wait_phases(config, assignment, expected, timeout)

    group(g1, [d1, d2])
    group(g2, [d2, d3])
    config.api(BASE + '/assignments', {
        'name': assignment,
        'spec': {
            'trigger': 'examples.inbox-watch/new-file',
            'packageVersion': package_version,
            'target': {'executor': 'CLIENT', 'deviceGroups': [g1, g2]},
            'config': {'folder': 'inbox', 'extension': '.txt'},
            'desiredState': 'Running',
        },
    })
    print(f'Created isolated fixture assignment {assignment}', flush=True)
    expect({d1: 'Running', d2: 'Running', d3: 'Running'})
    marker = checkpoint_probe(config, assignment, d2, checkpoint_service, timeout) if checkpoint_service else None
    action(config, assignment, 'Pause', 'deviceGroups', g1)
    expect({d1: 'Paused', d2: 'Paused', d3: 'Running'})
    action(config, assignment, 'Stop', 'devices', d3)
    action(config, assignment, 'Run', 'deviceGroups', g2)
    expect({d1: 'Paused', d2: 'Paused', d3: 'Stopped'})
    group(g1, [d1, d2, d4])
    expect({d4: 'Paused'})
    action(config, assignment, 'Remove', 'devices', d1)
    expect({d1: 'Removed'})
    action(config, assignment, 'Clear', 'deviceGroups', g1)
    expect({d1: 'Removed', d2: 'Running', d3: 'Stopped', d4: 'Running'})
    if marker:
        checkpoint_probe(config, assignment, d2, checkpoint_service, timeout, marker)
        print('Verified a new checkpoint after resume retained the first synthetic marker', flush=True)
    print('PASS: live executor phases for M12 scenario 10', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', nargs=4, required=True, metavar=('D1', 'D2', 'D3', 'D4'))
    parser.add_argument('--package-version', required=True)
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--checkpoint-service', choices=('linux-device','lifecycle-device-b','lifecycle-device-c','lifecycle-device-d'),
                        help='Compose service for D2 when checking a synthetic local checkpoint')
    parser.add_argument('--isolated-fixture', action='store_true', required=True,
                        help='Confirm this Quickstart and its devices are disposable test fixtures')
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    manage.load_environment()
    config = manage.Configuration(manage.wait_ready(), (manage.STATE / 'console-passwords.json').exists())
    if args.checkpoint_service:
        manage.COMPOSE.extend(['-f', str(manage.HERE / 'compose.trigger-lifecycle.yaml')])
    run(config, args.device, args.package_version, args.timeout, args.checkpoint_service)


if __name__ == '__main__':
    main()
