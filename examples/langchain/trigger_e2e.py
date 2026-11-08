# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""End-to-end hot-folder trigger check (issue #274, modules 5 and 10-12), after `manage.py setup` on the Compose stack
started with compose.triggers.yaml.

1. Waits until the profile's assignment `hotfolder-inbox` has a Running instance on the Linux device.
2. Drops a uniquely named file into the device's `inbox/`.
3. Runs the LangGraph agent over `toolgate.triggers.v1` until that file's event arrives, which proves the path
   device POLL trigger -> check-in ingest -> router -> coarse labels -> subscription socket -> agent.
4. Checks the agent's outcome. `processed` must leave the file in `processed/`. While native HotFolder finality is
   outstanding (#230, see README), the Gateway reports read_text/move OUTCOME_UNKNOWN and the agent reports
   `read-unconfirmed` or `move-unconfirmed` without retrying; that is accepted and named, never shown as success.
"""
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import manage  # noqa: E402
import trigger_agent as ta  # noqa: E402

FOLDER = '/var/lib/olo-toolgate/hotfolder'
COMPOSE = manage.COMPOSE[:-2] + ['-f', str(HERE / 'compose.yaml'), '-f', str(HERE / 'compose.triggers.yaml')]


def device(command):
    return subprocess.run(COMPOSE + ['exec', '-T', 'linux-device', 'sh', '-c', command], check=True, capture_output=True, text=True, cwd=HERE).stdout


PROFILE = 'hotfolder-trigger'


def provisioning(config):
    """The profile's state and each package's step, state and bounded reason, as Control recorded them."""
    try:
        status = config.api('/api/control/v1/packages/provisioning/' + PROFILE)
    except ValueError as refused:
        return str(refused)
    packages = '; '.join(f"{p.get('packageId')} step={p.get('step')} state={p.get('state')} reason={p.get('reason', '-')}"
                         for p in status.get('packages', []))
    return f"profile {PROFILE} generation={status.get('generation')} state={status.get('state')}: {packages or 'no packages'}"


def running(config, timeout=300):
    deadline = time.monotonic() + timeout
    last = 'no instance reported yet'
    while time.monotonic() < deadline:
        try:
            items = config.api('/api/control/v1/triggers/instances?assignment=' + ta.ASSIGNMENT)['items']
            if any(item.get('phase') == 'Running' for item in items):
                return items
            last = 'instance phases: ' + (', '.join(str(item.get('phase')) for item in items) or 'none')
        except ValueError as refused:
            last = str(refused)
        time.sleep(5)
    raise ValueError('The profile assignment hotfolder-inbox has no Running instance (last: ' + last + '; '
                     + provisioning(config) + '); inspect the device report and Control logs')


def main():
    manage.load_environment()
    config = manage.Configuration(manage.wait_ready(), (manage.STATE / 'console-passwords.json').exists())
    running(config)
    print('Instance Running on the device', flush=True)
    directory = manage.STATE / 'agents'
    descriptor = json.loads((directory / 'linux.json').read_text(encoding='utf-8'))
    mimic = ta._load('agent_mimic', manage.ROOT / 'debug/agent-mimic.py')
    gateway = mimic.Agent(descriptor['gateway'], directory / 'linux.token', directory / 'gateway-ca.crt')
    from websockets.sync.client import connect
    ta.ensure_subscription(config.api, int(time.time() * 1000))
    context = ssl.create_default_context(cafile=str(directory / 'gateway-ca.crt'))
    url = descriptor['gateway'].replace('https://', 'wss://').rstrip('/') + '/api/control/v1/triggers/stream'

    class Socket:
        def __init__(self, subprotocols):
            self.connection = connect(url, ssl=context, subprotocols=subprotocols, max_size=65536)
        def send(self, text):
            self.connection.send(text)
        def recv(self, timeout):
            return self.connection.recv(timeout=timeout)
        def close(self):
            self.connection.close()

    name = 'e2e-' + secrets.token_hex(4) + '.txt'
    text = 'hot folder end to end ' + name
    device(f"printf '%s' '{text}' > {FOLDER}/inbox/{name}")
    print('Dropped inbox/' + name, flush=True)
    shown, outcomes = [], []
    graph = ta.build_graph(ta.gateway_call(gateway, (mimic.UnconfirmedOperation,)), lambda path, content: shown.append((path, content)))
    source = ta.SocketEvents(Socket, lambda: config.api('/api/control/v1/triggers/stream/tickets', {})['ticket'], wait=5)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline and not outcomes:
        for event in source.poll():
            if event.get('subject') != 'inbox/' + name:
                continue
            assert event.get('tgclassification') == 'INTERNAL' and not event.get('tgredacted'), 'an SDK package event is INTERNAL and readable'
            outcomes.append(graph.invoke({'event': event}).get('outcome'))
    source.close()
    if not outcomes:
        raise ValueError('The dropped file produced no event over the socket within 240 s')
    print('Event delivered over toolgate.triggers.v1; agent outcome: ' + outcomes[0], flush=True)
    listing = device(f'ls {FOLDER}/inbox {FOLDER}/processed')
    if outcomes[0] == 'processed':
        if shown != [('inbox/' + name, text)] or name not in device(f'ls {FOLDER}/processed'):
            raise ValueError('The agent reported processed, but the device does not have the file in processed/')
        print('PASS: displayed and moved to processed/', flush=True)
    elif outcomes[0] in {'read-unconfirmed', 'move-unconfirmed'}:
        print('PASS (trigger path): native HotFolder finality is outstanding (#230), so the operation is unconfirmed and was not retried', flush=True)
    else:
        raise ValueError('Unexpected agent outcome ' + str(outcomes[0]) + '\n' + listing)
    # At least once, deduplicated: the same file is never handled twice.
    if outcomes.count(outcomes[0]) != len(outcomes):
        raise ValueError('The event was handled more than once')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, AssertionError) as failure:
        print('FAILED: ' + str(failure), file=sys.stderr)
        raise SystemExit(1)
