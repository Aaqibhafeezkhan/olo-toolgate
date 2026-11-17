# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Real LangGraph compilation/execution; deterministic capability adapters only."""
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
import unittest

import toolgate_runtime
from toolgate_runtime.graph_packages import GraphPackages, GraphRefused
from toolgate_runtime.json_graph import build_graph, cache_status, clear_cache, validate
from toolgate_runtime.runner import Runner, Agent, ContextSource, Limits, Tool, ToolOutcome
from test_runner import Gate, lease


def installed_selection():
    candidates=[]
    for path in Path(toolgate_runtime.__file__).with_name('graphs').glob('*/manifest.json'):
        manifest=json.loads(path.read_text(encoding='utf-8'))
        if manifest['packageId']=='olo.json-graph' and manifest['version']=='1.0.0':
            candidates.append(dict(packageId=manifest['packageId'],version=manifest['version'],
                digest='sha256:'+path.parent.name,entryPoint='workflow',config={}))
    if len(candidates)!=1:
        raise AssertionError('Exactly one installed JSON graph factory is required')
    return candidates[0]


def spec(nodes=None, edges=None, output=None):
    return dict(schemaVersion=1,
        nodes=nodes or [dict(id='echo',kind='value',input={'$path':'/message'})],
        edges=edges or [{'from':['START'],'to':'echo'},{'from':['echo'],'to':'END'}],
        output=output or {'$path':'/values/echo'},outputSchema={})


class JsonGraphTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.selection = installed_selection()

    def setUp(self):
        clear_cache()

    def execute(self, config, run=None, gate=None):
        selected = {**self.selection, 'config':config}
        run = replace(run or lease(),graph=selected)
        gate = gate or Gate(run)
        return Runner(gate).execute(run), gate

    def test_json_selected_graph_executes_through_the_existing_governed_runner(self):
        outcome, gate = self.execute(spec())
        self.assertEqual(('SUCCEEDED','Please investigate.'),(outcome.state,outcome.result))
        self.assertEqual([],gate.inferences)

    def test_admitted_compiler_identity_is_required_before_any_graph_effect(self):
        outcome, gate = self.execute(spec(), run=replace(lease(),compiler_digest='sha256:'+'0'*64))
        self.assertEqual(('FAILED','GRAPH_COMPILER_MISMATCH'),(outcome.state,outcome.code))
        self.assertEqual([],gate.inferences)

    def test_supplied_summary_profile_reads_with_one_agent_then_summarizes_with_another(self):
        root=Path(__file__).resolve().parents[3]
        config=json.loads((root/'profiles/configurations/text-summary/workflow-profile.json').read_text(encoding='utf-8'))['workflows']
        workflow=config['workflows'][0]
        agents=tuple(Agent(item['agentId'],item['modelGroupId'],item['systemPrompt'],
            tuple(ContextSource(source['toolId'],source['arguments'],source.get('argumentBindings',{})) for source in item['contextSources']))
            for item in config['agentDefinitions'])
        run=replace(lease(),workflow_id=workflow['id'],workflow_version_id=workflow['versionId'],
            message='inbox/configured.txt',supervisor_prompt=workflow['systemPrompt'],
            supervisor_model_group_id=workflow['modelGroupId'],agents=agents,graph=workflow['graph'],
            limits=Limits(workflow['limits']['maxSteps'],workflow['limits']['maxHandoffs'],60))
        gate=Gate(run)
        gate.catalogs={agents[0].agent_id:(Tool('examples.hotfolder/read_text',True),),agents[1].agent_id:()}
        gate.tool_outcome=ToolOutcome('SUCCEEDED',{'text':'A configured source document.'})
        gate.decisions[agents[1].agent_id]=deque([{'kind':'finish','result':{'summary':'Configured summary.'}}])
        result=Runner(gate).execute(run)
        self.assertEqual(('SUCCEEDED',{'summary':'Configured summary.'}),(result.state,result.result))
        self.assertEqual([(agents[0].agent_id,'examples.hotfolder/read_text',{'path':'inbox/configured.txt'})],gate.calls)
        self.assertEqual([agents[1].agent_id],[item[0] for item in gate.inferences])
        self.assertEqual('A configured source document.',gate.inferences[0][3][0]['content']['context'][0]['content']['text'])

    def test_agent_selection_comes_from_json_and_retains_authorization(self):
        config=spec([dict(id='chosen',kind='agent',agentId='support-fixer',input={'$path':'/message'})],
            [{'from':['START'],'to':'chosen'},{'from':['chosen'],'to':'END'}],{'$path':'/values/chosen'})
        run=replace(lease(),graph={**self.selection,'config':config})
        gate=Gate(run);gate.decisions['support-fixer']=deque([dict(kind='finish',result={'summary':'configured'})])
        outcome,_=self.execute(config,run,gate)
        self.assertEqual('SUCCEEDED',outcome.state)
        self.assertEqual({'summary':'configured'},outcome.result)
        unknown=deepcopy(config);unknown['nodes'][0]['agentId']='unassigned-agent'
        refused,_=self.execute(unknown)
        self.assertNotEqual('SUCCEEDED',refused.state)

    def test_parallel_branches_and_explicit_join(self):
        config=spec([dict(id='left',kind='value',input={'$literal':'L'}),dict(id='right',kind='value',input={'$literal':'R'}),
            dict(id='join',kind='value',input={'left':{'$path':'/values/left'},'right':{'$path':'/values/right'}})],
            [{'from':['START'],'to':'left'},{'from':['START'],'to':'right'},
             {'from':['left','right'],'to':'join'},{'from':['join'],'to':'END'}],{'$path':'/values/join'})
        result,_=self.execute(config)
        self.assertEqual({'left':'L','right':'R'},result.result)

    def test_boolean_branch_and_data_only_expression(self):
        config=spec([dict(id='choose',kind='branch',input={'$eq':[{'$path':'/message'},'yes']},routes={'true':'yes','false':'no'}),
            dict(id='yes',kind='value',input={'$literal':True}),dict(id='no',kind='value',input={'$literal':False})],
            [{'from':['START'],'to':'choose'},{'from':['yes'],'to':'END'},{'from':['no'],'to':'END'}],{'$path':'/values'})
        result,_=self.execute(config)
        self.assertEqual({'choose':False,'no':False},result.result)

    def test_cached_topology_has_no_cross_run_state_or_context(self):
        def execute(index):
            result,_=self.execute(spec(),replace(lease(),message='input-'+str(index)))
            return result.result
        with ThreadPoolExecutor(max_workers=4) as pool:
            self.assertEqual(['input-'+str(i) for i in range(12)],list(pool.map(execute,range(12))))
        self.assertEqual(1,cache_status()['entries'])
        with GraphPackages().load({**self.selection,'config':spec()}) as (first,_):
            with GraphPackages().load({**self.selection,'config':spec()}) as (second,_):
                self.assertIs(first,second)
        changed=spec(output={'$literal':'new'})
        self.assertEqual('new',self.execute(changed)[0].result)
        self.assertEqual(2,cache_status()['entries'])

    def test_reserved_state_names_can_be_used_as_node_ids(self):
        config=spec([dict(id='result',kind='value',input={'$literal':42})],
            [{'from':['START'],'to':'result'},{'from':['result'],'to':'END'}],{'$path':'/values/result'})
        self.assertEqual(42,self.execute(config)[0].result)

    def test_unsupported_code_and_invalid_references_fail_before_execution(self):
        cases=[]
        for kind in ('python','interrupt','subgraph','dynamic-fan-out'):
            value=spec();value['nodes'][0]['kind']=kind;cases.append(value)
        value=spec();value['nodes'][0]['input']={'$eval':'__import__("os")'};cases.append(value)
        value=spec();value['edges'][0]['to']='missing';cases.append(value)
        value=spec();value['nodes'].append(deepcopy(value['nodes'][0]));cases.append(value)
        value=spec();value['outputSchema']={'$ref':'https://example.invalid/schema'};cases.append(value)
        value=spec();value['schemaVersion']=True;cases.append(value)
        for value in cases:
            with self.subTest(value=value):
                with self.assertRaises(GraphRefused):build_graph(value)
        self.assertEqual(0,cache_status()['entries'])

    def test_output_schema_and_missing_input_fail_without_leaking_values(self):
        value=spec();value['outputSchema']={'type':'object'}
        result,_=self.execute(value)
        self.assertEqual('JSON_GRAPH_OUTPUT_SCHEMA',result.code)
        value=spec();value['output']={'$path':'/absent'}
        self.assertEqual('JSON_GRAPH_INPUT_UNAVAILABLE',self.execute(value)[0].code)

    def test_loop_obeys_the_runner_recursion_budget(self):
        config=spec([dict(id='forever',kind='value',input={'$literal':'tick'}),
            dict(id='choose',kind='branch',input={'$literal':True},routes={'true':'forever','false':'END'})],
            [{'from':['START'],'to':'forever'},{'from':['forever'],'to':'choose'}],{'$path':'/values/forever'})
        result,_=self.execute(config)
        self.assertEqual('FAILED',result.state)

    def test_conditional_model_loop_finishes_with_its_last_configured_result(self):
        config=spec([dict(id='inspect',kind='model',agentId='support-fixer',input={'$literal':[{'role':'user','content':'Inspect progress'}]}),
            dict(id='done',kind='branch',input={'$path':'/values/inspect/result/done'},routes={'true':'END','false':'inspect'})],
            [{'from':['START'],'to':'inspect'},{'from':['inspect'],'to':'done'}],{'$path':'/values/inspect/result'})
        run=replace(lease(),graph={**self.selection,'config':config})
        gate=Gate(run);gate.decisions['support-fixer']=deque([
            {'kind':'finish','result':{'done':False}},{'kind':'finish','result':{'done':True,'answer':'complete'}}])
        result=Runner(gate).execute(run)
        self.assertEqual(('SUCCEEDED',{'done':True,'answer':'complete'}),(result.state,result.result))
        self.assertEqual(2,len(gate.inferences))
