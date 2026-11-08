# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The Quickstart console proxy must reach every Control route that accepts a management session only over direct TLS.

A Control resource whose actor() calls tls() answers 401 "Direct TLS required" on the plain HTTP port. The supervisor
forwards such paths to Control's TLS port; a resource missing from that list is unreachable from the console and the
examples (the trigger admin API was, issue #274).
"""
import importlib.util
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('quickstart_direct_tls', ROOT/'apps/quickstart/supervisor.py')
quickstart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quickstart)

RESOURCES = ROOT/'apps/control-plane/src/main/java/io/ololabs/toolgate/control/adapter'
TLS_ACTOR = re.compile(r'Actor\s+actor\(\)\s*\{\s*tls\(\);')
CLASS_PATH = re.compile(r'^@Path\("([^"]*)"\)', re.M)
METHOD_PATH = re.compile(r'@(?:GET|POST|PUT|DELETE|PATCH)\s+@Path\("([^"]*)"\)')


def tls_routes():
    for source in sorted(RESOURCES.glob('*Resource.java')):
        text = source.read_text(encoding='utf-8')
        if not TLS_ACTOR.search(text):
            continue
        base = '/' + CLASS_PATH.search(text).group(1).strip('/')
        for method in METHOD_PATH.findall(text):
            yield source.stem, base + '/' + method.strip('/')


class DirectTlsRouteTests(unittest.TestCase):
    def test_every_tls_only_management_route_is_forwarded_over_tls(self):
        routes = list(tls_routes())
        self.assertTrue(any(name == 'TriggerResource' for name, _ in routes), 'the scan found the trigger admin resource')
        missing = [f'{name} {path}' for name, path in routes if not quickstart.direct_tls(path)]
        self.assertEqual(missing, [])

    def test_trigger_admin_routes_use_tls_and_other_management_routes_do_not(self):
        for path in ('/api/control/v1/triggers/instances', '/api/control/v1/triggers/stream/tickets',
                     '/api/control/v1/fleet/releases', '/api/control/v1/endpoint/devices'):
            self.assertTrue(quickstart.direct_tls(path), path)
        for path in ('/api/control/v1/users', '/api/control/v1/mcp/servers', '/api/control/v1/triggersx'):
            self.assertFalse(quickstart.direct_tls(path), path)


if __name__ == '__main__':
    unittest.main()
