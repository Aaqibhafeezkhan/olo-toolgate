# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Real proxy HTTP: first SSE record is forwarded before upstream completion; stale sessions stop delivery."""
import http.client
import json
import threading
import unittest
import urllib.request
from unittest.mock import patch
import test_security as security

quickstart = security.quickstart


class ApplicationStreamTests(unittest.TestCase):
    def setUp(self):
        security.SecurityTests.setUp(self)
        quickstart.atomic(quickstart.DATA/'identity.json', json.dumps(quickstart.password_record('Stream-Password-2026-Private', True)))
        self.token = self.identity.authenticate('Stream-Password-2026-Private')

    def tearDown(self): security.SecurityTests.tearDown(self)

    def stream(self, revoke):
        ready = threading.Event(); ready.set(); release = threading.Event(); forwarded = []
        real_connection = http.client.HTTPConnection

        class Response:
            status = 200
            calls = 0
            def getheader(self, name, default=None): return 'text/event-stream' if name.lower() == 'content-type' else default
            def read1(self, _):
                self.calls += 1
                if self.calls == 1: return b'id: 8\nevent: PROGRESS\ndata: {"kind":"QUEUED"}\n\n'
                if self.calls == 2:
                    if not release.wait(4): raise TimeoutError()
                    return b'id: 9\nevent: RESULT\ndata: {"kind":"SUCCEEDED"}\n\n'
                return b''
            def read(self, _): raise AssertionError('SSE must not be buffered until completion')

        class Upstream:
            def request(self, method, target, body, headers): forwarded.append({k.lower():v for k,v in headers.items()})
            def getresponse(self): return Response()
            def close(self): pass

        def connect(host, port=None, **kwargs):
            return Upstream() if host == '127.0.0.1' and port == 8082 else real_connection(host, port, **kwargs)

        server = quickstart.BoundedServer(('127.0.0.1', 0), quickstart.Handler, ready)
        thread = threading.Thread(target=server.serve_forever); thread.start()
        client = real_connection('127.0.0.1', server.server_port, timeout=6)
        try:
            with patch.object(quickstart.http.client, 'HTTPConnection', side_effect=connect):
                client.request('GET', '/api/control/v1/application-events/WORKFLOW/run-test/stream',
                    headers={'Authorization':'Bearer '+self.token, 'Accept':'text/event-stream', 'Last-Event-ID':'7'})
                with client.getresponse() as response:
                    first = b''
                    while not first.endswith(b'\n\n'): first += response.readline()
                    self.assertIn(b'id: 8', first); self.assertFalse(release.is_set())
                    self.assertEqual('7', forwarded[0]['last-event-id']); self.assertEqual('text/event-stream', forwarded[0]['accept'])
                    if revoke:
                        quickstart.atomic(quickstart.DATA/'identity.json', json.dumps(quickstart.password_record('Changed-Password-2026-Private', True, 2)))
                    release.set(); remaining = response.read()
                    if revoke: self.assertNotIn(b'id: 9', remaining)
                    else: self.assertIn(b'id: 9', remaining)
        finally:
            client.close()
            release.set(); server.shutdown(); thread.join(); server.server_close()

    def test_stream_is_unbuffered_and_resumes_with_authorization(self): self.stream(False)
    def test_password_generation_change_stops_next_stream_record(self): self.stream(True)
