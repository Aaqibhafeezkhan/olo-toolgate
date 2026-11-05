# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('agent_mimic',ROOT/'debug/agent-mimic.py')
mimic=importlib.util.module_from_spec(spec);spec.loader.exec_module(mimic)

class ScriptTests(unittest.TestCase):
    def test_repeats_only_existing_write_then_reads_it_back(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append((method,params))
                if params['name']=='examples.hotfolder/write_text':return {'structuredContent':{'written':True}}
                return {'structuredContent':{'exists':True,'text':mimic.TEXT}}
        with contextlib.redirect_stdout(io.StringIO()),patch.object(mimic.time,'monotonic',side_effect=[0,1,2,8]),patch.object(mimic.time,'sleep') as sleep:
            mimic.mimic(Agent(),'rahul-nigam.txt',8)
        self.assertEqual([params['name'] for _,params in calls],['examples.hotfolder/write_text','examples.hotfolder/write_text','examples.hotfolder/read_text'])
        self.assertEqual(calls[0],calls[1])
        sleep.assert_called_once_with(0.25)
    def test_repeated_write_failure_stops_without_reading(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append(params['name'])
                return {'structuredContent':{'written':len(calls)==1}}
        with contextlib.redirect_stdout(io.StringIO()),patch.object(mimic.time,'monotonic',side_effect=[0,1,2]),patch.object(mimic.time,'sleep'),self.assertRaises(ValueError):
            mimic.mimic(Agent(),'rahul-nigam.txt',8)
        self.assertEqual(calls,['examples.hotfolder/write_text','examples.hotfolder/write_text'])
    def test_invalid_duration_sends_nothing(self):
        class Agent:
            def rpc(self,*args):raise AssertionError('Invalid duration must not call the gateway')
        for duration in (-1,61,True,1.5,'8'):
            with self.subTest(duration=duration),self.assertRaisesRegex(ValueError,'Duration seconds'):
                mimic.mimic(Agent(),'rahul-nigam.txt',duration)
    def test_call_that_outlasts_interval_is_not_repeated(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append(params['name'])
                return {'structuredContent':{'written':True}} if len(calls)==1 else {'structuredContent':{'exists':True,'text':mimic.TEXT}}
        with contextlib.redirect_stdout(io.StringIO()),patch.object(mimic.time,'monotonic',side_effect=[0,20]),patch.object(mimic.time,'sleep') as sleep:
            mimic.mimic(Agent(),'rahul-nigam.txt',8)
        self.assertEqual(calls,['examples.hotfolder/write_text','examples.hotfolder/read_text'])
        sleep.assert_not_called()
    def test_only_calls_two_tools_and_prints_their_responses(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append((method,params))
                if params['name']=='examples.hotfolder/write_text':return {'structuredContent':{'written':True}}
                return {'structuredContent':{'exists':True,'text':mimic.TEXT}}
        output=io.StringIO()
        with contextlib.redirect_stdout(output):content=mimic.mimic(Agent(),'rahul-nigam.txt')
        self.assertEqual([call[0] for call in calls],['tools/call','tools/call'])
        self.assertEqual(calls[0][1],{'name':'examples.hotfolder/write_text','arguments':{'path':'rahul-nigam.txt','text':'My Name is Rahul Nigam'}})
        self.assertEqual(calls[1][1],{'name':'examples.hotfolder/read_text','arguments':{'path':'rahul-nigam.txt'}})
        self.assertEqual(content['text'],mimic.TEXT)
        self.assertIn('Client file-write response:',output.getvalue())
        self.assertIn('"written": true',output.getvalue())
        self.assertIn('Client file-read response:',output.getvalue())
    def test_rejected_call_stops_without_setup_or_second_call(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append(method)
                raise ValueError('MCP HTTP 401: UNAUTHORIZED')
        with contextlib.redirect_stdout(io.StringIO()),self.assertRaises(ValueError):mimic.mimic(Agent(),'rahul-nigam.txt')
        self.assertEqual(calls,['tools/call'])
    def test_failed_write_does_not_issue_second_call(self):
        calls=[]
        class Agent:
            def rpc(self,method,params=None):
                calls.append(method)
                return {'structuredContent':{'written':False}}
        with contextlib.redirect_stdout(io.StringIO()),self.assertRaises(ValueError):mimic.mimic(Agent(),'rahul-nigam.txt')
        self.assertEqual(calls,['tools/call'])
    def test_uses_configured_trust_instead_of_an_exported_debug_ca(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            stale=root/'.dev/debug/toolgate-quickstart-ca.crt'
            stale.parent.mkdir(parents=True);stale.write_text('stale')
            config=root/'client.json'
            ca=root/'configured-ca.crt'
            config.write_text(json.dumps({'serverUrl':'https://localhost:18450','caCertificatePath':str(ca)}))
            with patch.object(mimic,'ROOT',root),patch.object(mimic,'installed_client_config',return_value=config):
                self.assertEqual(mimic.resolve_ca('https://localhost:18450'),ca)
                config.unlink()
                self.assertIsNone(mimic.resolve_ca('https://localhost:18450'))

class OutcomeMetadataTests(unittest.TestCase):
    def rpc(self, result, wrong_id=False):
        agent=mimic.Agent.__new__(mimic.Agent)
        agent.token='fixture-token';agent.url='https://gateway.example/mcp'
        agent.run_id='fixture';agent.sequence=0;agent.opener=Mock()
        def response(request,timeout):
            request_id=json.loads(request.data)['id']
            data=json.dumps({'jsonrpc':'2.0','id':'wrong' if wrong_id else request_id,
                             'result':result}).encode()
            reply=Mock();reply.read.return_value=data;reply.headers={}
            return contextlib.nullcontext(reply)
        agent.opener.open.side_effect=response
        with contextlib.redirect_stdout(io.StringIO()):
            return agent.rpc('tools/call',{'name':'examples.hotfolder/list','arguments':{}})

    def outcome(self):
        return {'operationId':'inv-fixture','state':'OUTCOME_UNKNOWN','grade':'REPORTED',
                'retryable':False,'unsettledAttemptCount':1}

    def test_unknown_is_not_success_even_when_tool_error_flag_is_missing(self):
        for flag in (True,False):
            result={'isError':flag,'_meta':{'io.ololabs.toolgate/operationOutcome':self.outcome()},
                    'structuredContent':{'private':'must-not-escape'}}
            with self.subTest(flag=flag),self.assertRaises(mimic.UnconfirmedOperation) as caught:
                self.rpc(result)
            self.assertEqual(caught.exception.tool_error,flag)
            self.assertNotIn('must-not-escape',str(caught.exception))
            self.assertNotIn('structuredContent',caught.exception.__dict__)
            self.assertIn('Do not retry',str(caught.exception))

    def test_malformed_ids_retry_authority_and_worker_content_are_not_trusted_metadata(self):
        for changes in ({'operationId':'bad\\nlog'},{'operationId':''},{'retryable':True}):
            outcome=self.outcome();outcome.update(changes)
            with self.subTest(changes=changes),self.assertRaises(ValueError) as caught:
                self.rpc({'isError':True,'_meta':{'io.ololabs.toolgate/operationOutcome':outcome}})
            self.assertNotIsInstance(caught.exception,mimic.UnconfirmedOperation)
        with self.assertRaises(ValueError) as caught:
            self.rpc({'isError':True,'structuredContent':{'operationOutcome':self.outcome()}})
        self.assertNotIsInstance(caught.exception,mimic.UnconfirmedOperation)

    def test_missing_metadata_or_unknown_retry_authority_cannot_be_a_success(self):
        outcome=self.outcome();outcome['retryable']=True
        for result in ({'isError':False,'structuredContent':{'written':True}},
                       {'isError':False,'_meta':{'io.ololabs.toolgate/operationOutcome':outcome}}):
            with self.subTest(result=result),self.assertRaises(ValueError):
                self.rpc(result)

    def test_duration_mode_stops_after_first_unknown_without_readback_or_retry(self):
        agent=Mock()
        agent.rpc.side_effect=mimic.UnconfirmedOperation(self.outcome(),True)
        with contextlib.redirect_stdout(io.StringIO()),self.assertRaises(mimic.UnconfirmedOperation):
            mimic.mimic(agent,'fixture.txt',60)
        agent.rpc.assert_called_once_with('tools/call',{
            'name':'examples.hotfolder/write_text',
            'arguments':{'path':'fixture.txt','text':mimic.TEXT}})

    def test_response_identity_is_checked_before_outcome_metadata(self):
        with self.assertRaisesRegex(ValueError,'identity mismatch'):
            self.rpc({'isError':True,'_meta':{'io.ololabs.toolgate/operationOutcome':self.outcome()}},True)

if __name__=='__main__':unittest.main()
