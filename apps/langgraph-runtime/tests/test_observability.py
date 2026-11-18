# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Opt-in structural tracing cannot disclose content or retry execution effects."""
from dataclasses import replace
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import threading
import unittest

from toolgate_runtime.observability import Observability
from toolgate_runtime.runner import Runner
from test_runner import lease,Gate


class ObservabilityTests(unittest.TestCase):
    def test_real_runner_exports_only_structural_spans_with_common_trace_and_bounded_parentage(self):
        batches=[];run=replace(lease(),message='PRIVATE INPUT',supervisor_prompt='PRIVATE SYSTEM PROMPT')
        exporter=Observability('http://localhost:4318/v1/traces',send=batches.append)
        with exporter.run(run) as observation:
            outcome=Runner(Gate(run),progress=observation.event).execute(run);observation.finish(outcome.state)
        exporter.close();self.assertEqual('SUCCEEDED',outcome.state);self.assertEqual(1,len(batches))
        raw=batches[0].decode()
        for secret in ('PRIVATE INPUT','PRIVATE SYSTEM PROMPT','secret-lease-token','Findings','untrusted tool content','proposed'):
            self.assertNotIn(secret,raw)
        spans=json.loads(raw)['resourceSpans'][0]['scopeSpans'][0]['spans'];traces={s['traceId'] for s in spans};self.assertEqual(1,len(traces))
        kinds={dict((a['key'],a['value']['stringValue']) for a in span['attributes'])['openinference.span.kind'] for span in spans}
        self.assertTrue({'CHAIN','LLM','TOOL'}.issubset(kinds))
        root=next(s for s in spans if 'parentSpanId' not in s)
        ids={s['spanId'] for s in spans}
        for span in spans:
            self.assertLessEqual(int(span['startTimeUnixNano']),int(span['endTimeUnixNano']))
            if span is not root:self.assertIn(span['parentSpanId'],ids)
        self.assertEqual(1,root['status']['code'])

    def test_unavailable_exporter_drops_telemetry_without_retrying_or_changing_the_run(self):
        calls=[]
        def failed(raw):calls.append(raw);raise OSError('PRIVATE EXCEPTION')
        exporter=Observability('http://localhost:4318/v1/traces',send=failed);run=lease();gate=Gate(run)
        with exporter.run(run) as observation:
            outcome=Runner(gate,progress=observation.event).execute(run);observation.finish(outcome.state)
        exporter.close();self.assertEqual('SUCCEEDED',outcome.state);self.assertEqual(1,len(calls));self.assertEqual(1,exporter.dropped)
        self.assertEqual(1,len(gate.finishes))

    def test_disabled_and_bounded_spans_and_unconfirmed_completion(self):
        disabled=Observability();self.assertIsNone(disabled.thread)
        batches=[];exporter=Observability('http://localhost:4318/v1/traces',send=batches.append)
        with exporter.run(lease()) as observation:
            observation.event('MODEL_STARTED',subjectId='support')
            for _ in range(1000):observation.event('NODE_COMPLETED',nodeId='work')
        exporter.close();spans=json.loads(batches[0])['resourceSpans'][0]['scopeSpans'][0]['spans'];self.assertLessEqual(len(spans),512)
        self.assertEqual(2,spans[-1]['status']['code'])

    def test_http_json_export_and_redirect_refusal(self):
        received=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                received.append(self.rfile.read(int(self.headers['Content-Length'])))
                self.send_response(302);self.send_header('Location','/other/v1/traces');self.end_headers()
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            exporter=Observability(f'http://127.0.0.1:{server.server_port}/v1/traces')
            with exporter.run(lease()) as observation:observation.finish('SUCCEEDED')
            exporter.close();self.assertEqual(1,len(received));self.assertEqual(1,exporter.dropped)
        finally:server.shutdown();server.server_close();thread.join()
        for value in ('https://user:secret@collector/v1/traces','https://collector/v1/traces?token=private','https://collector/base','file:///v1/traces'):
            with self.assertRaises(ValueError):Observability(value)


if __name__=='__main__':unittest.main()
