# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Resolve the operator-provisioned subscription identity, never an event-selected workflow."""
import json
import os
from pathlib import Path
from toolgate_runtime.trigger_listener import main

if __name__ == '__main__':
    subscription = json.loads(Path('/custody/listener.json').read_text())
    raise SystemExit(main(['--control-origin', os.environ['CONTROL_ORIGIN'],
        '--subscription', subscription['name'], '--subscription-uid', subscription['uid'],
        '--token-file', '/custody/listener.token', '--ca-file', '/custody/gateway-ca.crt']))
