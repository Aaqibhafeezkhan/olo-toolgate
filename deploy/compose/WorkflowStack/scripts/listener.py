# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Follow Control's authorized subscription set; event payloads never choose workflows."""
import os
from toolgate_runtime.trigger_listener import main

if __name__ == '__main__':
    raise SystemExit(main(['--control-origin', os.environ['CONTROL_ORIGIN'],
        '--follow-subscriptions',
        '--token-file', '/custody/listener.token', '--ca-file', '/custody/gateway-ca.crt']))
