# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Real HTTPS credential replacement/outage recovery without re-entering a worker."""
import contextlib
import io
from pathlib import Path
import shutil
import tempfile
import threading
import unittest
from unittest.mock import Mock,patch
import test_trigger_transport as tls_fixture
from toolgate_runtime.run_transport import ControlRuns
from toolgate_runtime.worker import run_worker


@unittest.skipUnless(shutil.which('openssl'),'OpenSSL is required for ephemeral HTTPS certificates')
class RecoveryWireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):tls_fixture.WireTests.setUpClass.__func__(cls)
    @classmethod
    def tearDownClass(cls):tls_fixture.WireTests.tearDownClass.__func__(cls)
    tls=tls_fixture.WireTests.tls
    http=tls_fixture.WireTests.http

    def test_auth_refusal_replaced_file_and_transient_outage_recover_in_one_worker(self):
        for rejected in (401,403):
            with self.subTest(rejected=rejected),tempfile.TemporaryDirectory() as d:
                path=Path(d)/'credential';path.write_text('expired.fixture')
                stop=threading.Event();states=[];available_attempts=[]
                def reply(method,route,raw):
                    if server.requests[-1][2]=='Bearer expired.fixture':return rejected,{},{}
                    self.assertEqual('Bearer replaced.fixture',server.requests[-1][2])
                    if route.endswith('/workers:heartbeat'):
                        available_attempts.append(1)
                        return (503 if len(available_attempts)==1 else 204),{},{}
                    self.assertTrue(route.endswith('/claim'))
                    stop.set();return 204,{},{}
                status=Mock()
                def record(state):
                    states.append(state)
                    if state=='AUTH_REQUIRED':
                        new=path.with_suffix('.new');new.write_text('replaced.fixture');new.replace(path)
                status.set.side_effect=record
                with self.http(reply) as (server,origin),contextlib.redirect_stdout(io.StringIO()) as log:
                    control=ControlRuns(origin,lambda:path.read_text(),ca_file=str(self.cert),timeout_seconds=2)
                    with patch('toolgate_runtime.worker.Backoff.next',return_value=.001):
                        self.assertEqual(0,run_worker(control,Mock(),Mock(),stop,status))
                    self.assertEqual(1,sum(route.endswith('/claim') for _,route,_,_ in server.requests))
                    self.assertIn('AUTH_REQUIRED',states);self.assertIn('BACKOFF',states);self.assertIn('READY',states)
                    self.assertNotIn('fixture',log.getvalue())
