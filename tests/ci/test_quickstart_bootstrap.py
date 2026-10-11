# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Keep installation reviews within Control's initial recovery boundary."""
import base64
import ast
import http.client
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
from unittest.mock import Mock

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('enterprise_bootstrap', ROOT/'apps/quickstart/enterprise_bootstrap.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)
check_spec = importlib.util.spec_from_file_location('quickstart_check', ROOT/'tools/quickstart/check.py')
quickstart_check = importlib.util.module_from_spec(check_spec)
check_spec.loader.exec_module(quickstart_check)


class QuickstartBootstrapTests(unittest.TestCase):
    def test_dependency_outage_clears_readiness_without_exiting_supervisor(self):
        source = ast.parse((ROOT/'apps/quickstart/supervisor.py').read_text(encoding='utf-8'))
        function = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'dependencies_ready')
        call = Mock(return_value=(200, {}, {}))
        namespace = {'call': call, 'http': http}
        exec(compile(ast.Module(body=[function], type_ignores=[]), '<supervisor readiness>', 'exec'), namespace)
        ready = namespace['dependencies_ready']
        self.assertTrue(ready())
        self.assertEqual(2, call.call_count)
        for failure in (TimeoutError(), ConnectionRefusedError(), http.client.RemoteDisconnected()):
            call.side_effect = failure
            self.assertFalse(ready())
        call.side_effect = None
        self.assertTrue(ready())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.data = Path(self.temp.name)
        (self.data/'state').mkdir()
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def atomic(self, path, value):
        path.write_bytes(value.encode('utf-8') if isinstance(value, str) else value)
        path.chmod(0o600)

    def prepare(self):
        return bootstrap.prepare(self.data, 'quickstart', 'https://quickstart.local',
                                 lambda name: self.key, self.atomic, [])

    def test_signed_installation_contains_no_workload_bindings_or_runtime_authority(self):
        _, path, _ = self.prepare()
        packet = json.loads(path.read_text(encoding='utf-8'))
        authorization = packet['authorization']
        snapshot = authorization['snapshot']
        for field in ('workloadBindings', 'grants', 'delegations', 'agentDelegations', 'devices'):
            self.assertEqual(snapshot[field], [], field)
        self.assertTrue(all(not agent['enabled'] for agent in snapshot['agents']))
        canonical = json.dumps(authorization, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        signature = packet['proofs'][0]['signature']
        self.key.public_key().verify(base64.urlsafe_b64decode(signature + '='*(-len(signature) % 4)),
                                     b'OLO ToolGate recovery v1\n' + canonical, padding.PKCS1v15(), hashes.SHA256())

    def test_restart_preserves_original_review_after_database_creation(self):
        first = self.prepare()
        originals = [path.read_bytes() for path in first[1:]]
        (self.data/'state/control.sqlite').touch()
        with patch.object(bootstrap.time, 'time', return_value=0):
            self.assertEqual(self.prepare(), first)
        self.assertEqual([path.read_bytes() for path in first[1:]], originals)

    def test_first_admin_has_default_email_and_super_admin_role(self):
        with patch.dict(os.environ, {}, clear=True):
            _, path, _ = self.prepare()
        snapshot = json.loads(path.read_text(encoding='utf-8'))['authorization']['snapshot']
        self.assertEqual('admin@admin.com', snapshot['users'][0]['email'])
        admin = snapshot['users'][0]['id']
        roles = {role['id']: role for role in snapshot['roles']}
        self.assertTrue(any(admin in team['userIds'] and any(roles[r]['portalRole'] == 'SUPER_ADMIN' for r in team['roleIds']) for team in snapshot['teams']))
        self.assertTrue(all('email' not in user for user in snapshot['users'][1:]))

    def test_email_override_is_applied_only_on_first_creation(self):
        with patch.dict(os.environ, {'TOOLGATE_ADMIN_EMAIL': 'owner@example.com'}):
            first = self.prepare()
        original = first[1].read_bytes()
        self.assertEqual('owner@example.com', json.loads(original)['authorization']['snapshot']['users'][0]['email'])
        (self.data/'state/control.sqlite').touch()
        with patch.dict(os.environ, {'TOOLGATE_ADMIN_EMAIL': 'invalid-on-restart'}):
            self.assertEqual(first, self.prepare())
        self.assertEqual(original, first[1].read_bytes())

    def test_invalid_email_is_rejected_before_signing_the_installation(self):
        for value in ('', 'admin', 'admin@localhost', 'admin @example.com', 'a'*250+'@example.com'):
            with self.subTest(value=value), patch.dict(os.environ, {'TOOLGATE_ADMIN_EMAIL': value}):
                with self.assertRaisesRegex(ValueError, 'TOOLGATE_ADMIN_EMAIL'):
                    self.prepare()
        self.assertFalse((self.data/'installation-review.json').exists())

    def test_existing_database_without_installation_review_is_not_reseeded(self):
        (self.data/'state/control.sqlite').touch()
        with self.assertRaisesRegex(ValueError, 'externally reviewed recovery'):
            self.prepare()
        self.assertFalse((self.data/'installation-review.json').exists())


class QuickstartRestartTests(unittest.TestCase):
    def test_restart_waits_on_the_newly_assigned_host_port(self):
        ports = {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '54600'}],
                 '8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '54601'}]}
        info = [{'NetworkSettings': {'Ports': ports}}]
        with patch.object(quickstart_check, 'run', side_effect=[
                subprocess.CompletedProcess([], 0, stdout='fixture-container'),
                subprocess.CompletedProcess([], 0, stdout=json.dumps(info))]), \
                patch.object(quickstart_check, 'ready') as ready:
            self.assertEqual(quickstart_check.restart_ready('fixture-container'), ports)
        ready.assert_called_once_with('fixture-container', '54600')


if __name__ == '__main__':
    unittest.main()
