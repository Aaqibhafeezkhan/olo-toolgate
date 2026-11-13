# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""M12 scenario 11 on a disposable Quickstart with three ready client devices."""
import argparse
from pathlib import Path
import secrets
import time
from urllib.parse import urlencode

import manage
import packages
from trigger_lifecycle_e2e import BASE


def ready(row):
    return any(c['type'] == 'Ready' and c['status'] == 'True' for c in row['conditions'])


def wait(config, assignment, devices, timeout, predicate, budget=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        page = config.api(BASE + '/instances?' + urlencode({'assignment': assignment}))
        rows = {row['executorId']: row for row in page['items']}
        if set(rows) == set(devices):
            if budget is not None and sum(not ready(row) for row in rows.values()) > budget:
                raise ValueError('Observed more unready instances than the rollout budget')
            if predicate(rows):
                return rows
        time.sleep(2)
    raise ValueError('Timed out waiting for the expected rollout state')


def release_to(config, version, device):
    config.api('/api/control/v1/fleet/rollouts', {
        'id': 'm12-fleet-' + secrets.token_hex(8),
        'packageId': 'examples.inbox-watch', 'version': version,
        'deviceIds': [device], 'desiredPresence': True, 'percentage': 100,
    })


def run(config, devices, good_version, failing_package, timeout):
    if len(set(devices)) != 3:
        raise ValueError('Provide three distinct fixture device IDs')
    upload = packages.release_request(config, 'packages/uploads', failing_package.read_bytes(),
                                      'application/vnd.ololabs.toolgate.package+zip')
    release = packages.release_request(config, 'packages/client-releases', {'packageDigest': upload['packageDigest']})
    if release['packageId'] != 'examples.inbox-watch' or not release.get('signed') or release['version'] == good_version:
        raise ValueError('Provide a distinct, intentionally failing Inbox Watch package version')
    bad_version = release['version']
    assignment = 'm12-rollout-' + secrets.token_hex(5)
    path = BASE + '/assignments/' + assignment
    groups = [assignment + '-g1', assignment + '-g2']
    for group, members in zip(groups, (devices[:2], devices[1:])):
        config.upsert('device-groups', {'id': group, 'name': group, 'enabled': True, 'revision': 1, 'deviceIds': members})
    config.api(BASE + '/assignments', {
        'name': assignment,
        'spec': {
            'trigger': 'examples.inbox-watch/new-file', 'packageVersion': good_version,
            'target': {'executor': 'CLIENT', 'deviceGroups': groups},
            'config': {'folder': 'inbox', 'extension': '.txt'}, 'desiredState': 'Running',
            'strategy': {'rollingUpdate': {'maxUnavailable': '1'}},
        },
    })
    initial = wait(config, assignment, devices, timeout, lambda rows: all(map(ready, rows.values())))
    good_digest = next(iter(initial.values()))['packageDigest']
    if not all(row['packageDigest'] == good_digest for row in initial.values()):
        raise ValueError('Fixture devices began on different package versions')
    etag = config.api(path)['metadata']['resourceVersion']
    config.api(path, {'spec': {'packageVersion': bad_version}}, method='PATCH', etag=etag,
               content_type='application/merge-patch+json')
    changed = wait(config, assignment, devices, timeout,
                   lambda rows: sum(row['packageDigest'] != good_digest for row in rows.values()) == 1, budget=1)
    canary = next(device for device, row in changed.items() if row['packageDigest'] != good_digest)
    release_to(config, bad_version, canary)
    wait(config, assignment, devices, timeout,
         lambda rows: rows[canary]['phase'] == 'Failed' and all(
             ready(row) and row['packageDigest'] == good_digest for device, row in rows.items() if device != canary), budget=1)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = config.api(path)
        if any(c['reason'] == 'RolloutStalled' for c in status['status']['conditions']):
            break
        time.sleep(2)
    else:
        raise ValueError('Control did not report RolloutStalled after the replacement failed')
    config.api(path + ':rollback', method='POST', etag=status['metadata']['resourceVersion'])
    release_to(config, good_version, canary)
    restored = wait(config, assignment, devices, timeout, lambda rows: all(
        ready(row) and row['packageDigest'] == good_digest and row['configRevision'] == 3 for row in rows.values()), budget=1)
    if len(restored) != 3:
        raise ValueError('Rollback did not restore all fixture devices')
    print('PASS: a failed package replacement stalled the rollout and rollback restored all three devices', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', nargs=3, required=True)
    parser.add_argument('--good-version', required=True)
    parser.add_argument('--failing-package', required=True, type=Path)
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--isolated-fixture', action='store_true', required=True)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    manage.load_environment()
    config = manage.Configuration(manage.wait_ready(), (manage.STATE / 'console-passwords.json').exists())
    run(config, args.device, args.good_version, args.failing_package, args.timeout)


if __name__ == '__main__':
    main()
