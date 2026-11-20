# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
import urllib.request
from urllib.error import HTTPError
from unittest.mock import Mock,patch
from toolgate_runtime.availability import Availability,Backoff,read_status
from toolgate_runtime.worker import run_worker,ExecutionEvidence
from toolgate_runtime.run_transport import RunTransportError
from toolgate_runtime.service import Health


class RecoveryTests(unittest.TestCase):
    def test_live_stays_healthy_while_execution_readiness_is_unavailable(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'status';status=Availability(path);status.set('AUTH_REQUIRED')
            server=ThreadingHTTPServer(('127.0.0.1',0),Health)
            server.status_file=path;server.ready=threading.Event()
            thread=threading.Thread(target=server.serve_forever);thread.start()
            origin='http://127.0.0.1:'+str(server.server_port)
            try:
                with urllib.request.urlopen(origin+'/health/live') as response:
                    self.assertEqual({'workerState':'AUTH_REQUIRED'},json.load(response))
                with self.assertRaises(HTTPError) as failure:urllib.request.urlopen(origin+'/health/ready')
                self.assertEqual(503,failure.exception.code);failure.exception.close()
                status.set('READY');server.ready.set()
                with urllib.request.urlopen(origin+'/health/ready') as response:self.assertEqual(200,response.status)
            finally:server.shutdown();server.server_close();thread.join(3)

    def test_stale_ready_cannot_claim_readiness_and_diagnostics_have_no_secret(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'status.json';status=Availability(path)
            status.set('READY');self.assertEqual('READY',read_status(path))
            data=json.loads(path.read_text());data['updatedAt']-=16;path.write_text(json.dumps(data))
            self.assertEqual('CONNECTING',read_status(path))
            status.set('AUTH_REQUIRED');self.assertEqual('AUTH_REQUIRED',read_status(path))
            self.assertEqual({'state','updatedAt'},set(json.loads(path.read_text())))

    def test_expired_auth_and_outage_recover_without_worker_exit_or_claim_replay(self):
        for failure,state in [(RunTransportError('never log token',auth_required=True),'AUTH_REQUIRED'),
                              (RunTransportError('private network information'),'BACKOFF')]:
            with self.subTest(state=state),tempfile.TemporaryDirectory() as d:
                token=Path(d)/'worker.token';token.write_text('expired')
                stopped=threading.Event();states=[];claims=[]
                class Control:
                    def heartbeat(self):
                        if token.read_text()=='expired':raise failure
                    def claim(self):claims.append('fresh-poll');stopped.set();return None
                status=Mock()
                def record(value):
                    states.append(value)
                    if value==state:token.write_text('replacement')
                status.set.side_effect=record
                with patch('toolgate_runtime.worker.Backoff.next',return_value=.001):
                    result=run_worker(Control(),Mock(),Mock(),stopped,status)
                self.assertEqual(0,result)
                self.assertEqual(['fresh-poll'],claims)
                self.assertIn(state,states);self.assertIn('READY',states)
                self.assertEqual('STOPPED',states[-1])

    def test_lost_presence_refuses_tools_and_models_before_adapter_send(self):
        adapter=Mock()
        def unavailable():raise RunTransportError('Worker presence is unavailable')
        evidence=ExecutionEvidence(adapter,unavailable)
        with self.assertRaises(RunTransportError):evidence.call_tool(None,None,'files/write',{})
        with self.assertRaises(RunTransportError):evidence.infer(None,None)
        adapter.call_tool.assert_not_called();adapter.infer.assert_not_called()

    def test_gateway_outage_blocks_admission_even_when_control_is_available(self):
        control=Mock();gateway=Mock();stop=threading.Event();status=Mock();states=[]
        gateway.probe.side_effect=RunTransportError('Gateway is unavailable')
        def record(state):
            states.append(state)
            if state=='BACKOFF':
                control.claim.assert_not_called();control.heartbeat.assert_not_called()
                gateway.probe.side_effect=None
        status.set.side_effect=record
        def claimed():stop.set();return None
        control.claim.side_effect=claimed
        with patch('toolgate_runtime.worker.Backoff.next',return_value=.001):
            self.assertEqual(0,run_worker(control,gateway,Mock(),stop,status))
        self.assertIn('BACKOFF',states);self.assertIn('READY',states)
        control.claim.assert_called_once()

    def test_backoff_is_bounded_jittered_and_resettable(self):
        backoff=Backoff()
        values=[backoff.next() for _ in range(30)]
        self.assertTrue(.5<=values[0]<=1)
        self.assertTrue(all(15<=v<=30 for v in values[6:]))
        backoff.reset();self.assertLessEqual(backoff.next(),1)


if __name__=='__main__':unittest.main()
