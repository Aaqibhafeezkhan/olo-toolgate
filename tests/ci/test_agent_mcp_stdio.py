# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('agent_mcp_stdio',ROOT/'debug/agent-mcp-stdio.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class Agent:
    def __init__(self):self.calls=[]
    def rpc(self,method,params):
        self.calls.append((method,params))
        print('SEND '+method)
        if method=='tools/list':return {'tools':[{'name':'examples.hotfolder/write_text','inputSchema':{'type':'object'}}]}
        if params['arguments'].get('path')!='rahul-nigam.txt':raise ValueError('Private upstream details')
        return {'content':[{'type':'text','text':'ok'}],'structuredContent':{'written':True},'resultType':'complete'}


class BridgeTests(unittest.TestCase):
    def initialized(self,agent):
        bridge=module.Bridge(agent)
        response=bridge.handle({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'test','version':'1'}}})
        self.assertEqual(response['result']['protocolVersion'],'2025-11-25')
        self.assertEqual(response['result']['capabilities'],{'tools':{}})
        self.assertIsNone(bridge.handle({'jsonrpc':'2.0','method':'notifications/initialized'}))
        return bridge
    def test_requires_initialization_before_gateway_calls(self):
        agent=Agent();bridge=module.Bridge(agent)
        self.assertEqual(bridge.handle({'jsonrpc':'2.0','id':1,'method':'tools/list'})['error']['code'],-32002)
        self.assertEqual(agent.calls,[])
    def test_lists_only_current_gateway_tools_and_calls_gateway(self):
        agent=Agent();bridge=self.initialized(agent)
        with contextlib.redirect_stderr(io.StringIO()):
            result=bridge.handle({'jsonrpc':'2.0','id':2,'method':'tools/list'})
            called=bridge.handle({'jsonrpc':'2.0','id':'call-1','method':'tools/call','params':{'name':'examples.hotfolder/write_text','arguments':{'path':'rahul-nigam.txt','text':'My Name is Rahul Nigam'},'_meta':{'progressToken':'test'}}})
        self.assertEqual([t['name'] for t in result['result']['tools']],['examples.hotfolder/write_text'])
        self.assertEqual(called['id'],'call-1')
        self.assertTrue(called['result']['structuredContent']['written'])
        self.assertNotIn('resultType',called['result'])
        self.assertNotIn('_meta',agent.calls[1][1])
    def test_denial_is_returned_without_retry_or_private_detail(self):
        agent=Agent();bridge=self.initialized(agent)
        with contextlib.redirect_stderr(io.StringIO()):
            denied=bridge.handle({'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'examples.hotfolder/write_text','arguments':{'path':'other.txt'}}})
        self.assertEqual(denied['error']['code'],-32000)
        self.assertNotIn('Private',json.dumps(denied))
        self.assertEqual(len(agent.calls),1)
    def test_cannot_override_target_or_principal(self):
        agent=Agent();bridge=self.initialized(agent)
        denied=bridge.handle({'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'examples.hotfolder/write_text','arguments':{},'deviceId':'other-device'}})
        self.assertEqual(denied['error']['code'],-32602)
        self.assertEqual(agent.calls,[])
    def test_stdio_stdout_contains_only_correlated_json_responses(self):
        source=io.StringIO('\n'.join(json.dumps(x) for x in [
            {'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{}}},
            {'jsonrpc':'2.0','method':'notifications/initialized'},
            {'jsonrpc':'2.0','id':2,'method':'tools/list'}])+'\n')
        destination=io.StringIO();diagnostics=io.StringIO()
        with contextlib.redirect_stderr(diagnostics):module.serve(Agent(),source,destination)
        responses=[json.loads(line) for line in destination.getvalue().splitlines()]
        self.assertEqual([r['id'] for r in responses],[1,2])
        self.assertIn('SEND tools/list',diagnostics.getvalue())
    def test_malformed_and_oversized_input_do_not_reach_gateway(self):
        agent=Agent();destination=io.StringIO()
        module.serve(agent,io.StringIO('invalid\n'+'x'*(module.MAX_MESSAGE+1)+'\n'),destination)
        self.assertEqual([json.loads(x)['error']['code'] for x in destination.getvalue().splitlines()],[-32700,-32600])
        self.assertEqual(agent.calls,[])


if __name__=='__main__':unittest.main()
