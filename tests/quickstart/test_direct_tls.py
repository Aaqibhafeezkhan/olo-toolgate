# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Every Control administration route that demands direct TLS is forwarded by the supervisor over :8443.

Control refuses these routes on its plain management listener ("Direct TLS required"), so a route the supervisor
sends to :8082 answers 401 through Quickstart. The hot-folder trigger admin routes once did exactly that.
"""
import importlib.util
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / 'apps/control-plane/src/main/java/io/ololabs/toolgate/control/adapter'
sys.path.insert(0, str(ROOT / 'apps/quickstart'))
spec = importlib.util.spec_from_file_location('quickstart_direct_tls', ROOT / 'apps/quickstart/supervisor.py')
quickstart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quickstart)


def join(*parts):
    return '/' + '/'.join(segment for part in parts for segment in part.split('/') if segment)


def tls_admin_routes():
    """(resource, full path) of each method whose management actor requires direct TLS."""
    routes = []
    for source in sorted(ADAPTER.glob('*.java')):
        text = source.read_text(encoding='utf-8')
        if not re.search(r'actor\(\)\s*\{[^}]*(tls\(\)|isSSL\(\))', text):
            continue
        base = re.search(r'^@Path\("([^"]+)"\)', text, re.M)
        if base is None:
            continue
        methods = list(re.finditer(r'@Path\("([^"]+)"\)', text[base.end():]))
        body = text[base.end():]
        for index, match in enumerate(methods):
            end = methods[index + 1].start() if index + 1 < len(methods) else len(body)
            if 'actor()' in body[match.end():end]:
                routes.append((source.stem, join(base.group(1), match.group(1))))
    return routes


class DirectTlsForwardingTests(unittest.TestCase):
    def test_every_tls_only_control_admin_route_is_forwarded_over_tls(self):
        routes = tls_admin_routes()
        resources = {resource for resource, _ in routes}
        self.assertLessEqual({'BuilderResource', 'EndpointResource', 'FleetResource', 'TriggerResource'}, resources)
        self.assertIn(('TriggerResource', '/api/control/v1/triggers/instances'), routes)
        self.assertEqual([], [route for route in routes if not quickstart.direct_tls(route[1])])

    def test_plain_management_routes_stay_on_the_management_listener(self):
        for path in ('/api/control/v1/users', '/api/control/v1/vault', '/api/control/v1/endpoints/kills',
                     '/api/control/v1/triggersx', '/api/control/v1/triggers'):
            self.assertFalse(quickstart.direct_tls(path), path)


if __name__ == '__main__':
    unittest.main()
