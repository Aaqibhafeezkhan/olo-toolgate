# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Published-origin discovery must preserve every established Tool Host trust binding."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('quickstart_origins', ROOT/'apps/quickstart/supervisor.py')
quickstart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quickstart)


class ToolHostOriginTests(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.root = Path(work.name)
        self.tenant = '73c244ff-25a9-4e2d-bce1-407bc14569d4'
        self.bound_tenant = patch.object(quickstart, 'TENANT', self.tenant)
        self.bound_tenant.start()
        self.addCleanup(self.bound_tenant.stop)

    def journal(self, **changes):
        state = dict(formatVersion=3, tenantId=self.tenant, serverUrl='https://localhost:8443',
                     manifest=None, discoveryTrust=None, identity=None, challenge=None, sequence=0)
        state.update(changes)
        path = self.root/'device.json'
        path.write_text(json.dumps(state))
        return path

    def configure(self):
        with patch.dict(os.environ, {'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL':'https://localhost:18453'}):
            return quickstart.tool_host_control_url(self.root)

    def test_unbound_journal_adopts_published_port_and_retains_key(self):
        path = self.journal()
        key = self.root/'device-key.pem'
        key.write_bytes(b'preserved private key')
        self.assertEqual(self.configure(), 'https://localhost:18453')
        self.assertEqual(json.loads(path.read_text())['serverUrl'], 'https://localhost:18453')
        self.assertEqual(key.read_bytes(), b'preserved private key')
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        before = path.read_bytes()
        self.configure()
        self.assertEqual(path.read_bytes(), before)

    def test_bound_or_pending_journal_is_never_rewritten(self):
        for field in ('manifest', 'discoveryTrust', 'identity', 'challenge'):
            with self.subTest(field=field):
                path = self.journal(**{field:{'preserve':'authority'}})
                before = path.read_bytes()
                self.configure()
                self.assertEqual(path.read_bytes(), before)

    def test_other_tenant_version_origin_and_sequence_are_preserved(self):
        for changes in ({'tenantId':'other'}, {'formatVersion':2}, {'sequence':1},
                        {'serverUrl':'https://localhost:18452'}):
            with self.subTest(changes=changes):
                path = self.journal(**changes)
                before = path.read_bytes()
                self.configure()
                self.assertEqual(path.read_bytes(), before)

    def test_fresh_host_needs_no_journal_or_key(self):
        self.assertEqual(self.configure(), 'https://localhost:18453')
        self.assertEqual(list(self.root.iterdir()), [])

    def test_default_origin_preserves_existing_journal(self):
        path = self.journal()
        before = path.read_bytes()
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(quickstart.tool_host_control_url(self.root), 'https://localhost:8443')
        self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()