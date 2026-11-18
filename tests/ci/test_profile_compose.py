# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Mounted configuration and trusted folder delivery; no simulated model acceptance claims."""
from copy import deepcopy
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import Mock

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/compose/WorkflowStack/scripts'))
import configuration
from folder_agent import FolderAgent,write_once

RUN='run-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'


class ProfileComposeTests(unittest.TestCase):
    def test_deployment_restart_preserves_application_managed_profile(self):
        spec=importlib.util.spec_from_file_location('profile_restart_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
        manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
        settings=configuration.load(ROOT/'deploy/compose/WorkflowStack/configurations/text-summary')
        operator=Mock();config=operator.Configuration.return_value
        source=settings['deployment']['source']
        live='sha256:'+'b'*64
        def api(path,*args):
            if path.endswith('/subscriptions/'+settings['subscription']):return dict(metadata=dict(uid='subscription-uid'),spec=dict(filter=source['eventFilter'],sources=dict(assignments=[settings['assignment']])))
            if path.endswith('/assignments/'+settings['assignment']):return dict(metadata=dict(uid='assignment-uid'))
            if '/workflow-profiles/published?' in path:return [dict(profileId=settings['document']['profileId'],digest=live,generation=9)]
            raise AssertionError(path)
        config.api.side_effect=api
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temporary,patch.object(manager,'wait_trigger'),patch.object(manager,'qualify_reader'):
            manager.profile(operator,settings,Path(temporary))
        config.upsert.assert_not_called()
        writes=operator.shared.private_file.call_args_list
        self.assertTrue(any(json.loads(call.args[1]).get('profileDigest')==live for call in writes))
        self.assertTrue(all(len(call.args)==1 for call in config.api.call_args_list))

    def test_trigger_identity_configuration_and_filter_are_profile_data_without_folder_adapter(self):
        profile=deepcopy(self.settings['document'])
        deployment=profile['workflows']['workflows'][0]['deployment']
        deployment.pop('fileAgent')
        source=deployment['source']
        source.update(packageId='acme.metric-watch',version='2.4.1',triggerId='acme.metric-watch/threshold',
            config={'metric':'queue-depth','above':50},eventFilter="event.type == 'acme.threshold.v1'")
        directory=self.root/'arbitrary';directory.mkdir()
        path=directory/'workflow-profile.json';path.write_text(json.dumps(profile))
        loaded=configuration.load(directory)
        self.assertIsNone(loaded['fileAgent'])
        self.assertEqual(source,loaded['deployment']['source'])
        source['triggerId']='other.package/threshold'
        path.write_text(json.dumps(profile))
        with self.assertRaisesRegex(ValueError,'pinned package'):
            configuration.load(directory)

    def test_profile_scope_preserves_inactive_workflows_without_activating_their_resources(self):
        profile=deepcopy(self.settings['document'])
        workflow=deepcopy(profile['workflows']['workflows'][0])
        workflow['id']='inactive'
        workflow['deployment']['enabled']=False
        profile['workflows']['workflows'].append(workflow)
        unused=deepcopy(profile['workflows']['agentDefinitions'][0]);unused['agentId']='unused-agent'
        profile['workflows']['agentDefinitions'].append(unused)
        directory=self.root/'merged'
        directory.mkdir()
        source=directory/'workflow-profile.json'
        source.write_text(json.dumps(profile))
        scoped=configuration.load(directory)
        self.assertEqual(['text-summary'],[item['id'] for item in scoped['document']['workflows']['workflows']])
        self.assertNotIn('unused-agent',{item['agentId'] for item in scoped['document']['workflows']['agentDefinitions']})
        self.assertEqual(profile,json.loads(source.read_text()))
        workflow['deployment']['enabled']=True
        source.write_text(json.dumps(profile))
        with self.assertRaisesRegex(ValueError,'exactly one'):
            configuration.load(directory)

    def test_model_and_engine_parameters_come_only_from_profile(self):
        spec=importlib.util.spec_from_file_location('profile_engine_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
        manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
        bundle=self.root/'bundle'
        directory=bundle/'configurations/text-summary'
        directory.mkdir(parents=True)
        source=directory/'workflow-profile.json'
        profile=deepcopy(self.settings['document'])
        source.write_text(json.dumps(profile))
        (bundle/'.env').write_text('CONFIGURATION=text-summary\n')
        manager.HERE=bundle
        from unittest.mock import patch
        with patch.dict(os.environ,{'TOOLGATE_MODEL_THREADS':'999','TOOLGATE_WORKFLOW_MODEL_FILE':'outside.gguf'}):
            manager.environment()
            self.assertEqual('4',os.environ['TOOLGATE_MODEL_THREADS'])
            self.assertEqual((bundle/'models/model.gguf').resolve().as_posix(),os.environ['TOOLGATE_WORKFLOW_MODEL_FILE'])
            profile['workflows']['workflows'][0]['deployment']['model']['threads']=2
            profile['workflows']['workflows'][0]['deployment']['model']['modelFile']='another.gguf'
            profile['workflows']['modelDeployments'][0]['modelId']='another-model'
            source.write_text(json.dumps(profile))
            manager.environment()
            self.assertEqual('2',os.environ['TOOLGATE_MODEL_THREADS'])
            self.assertEqual('another-model',os.environ['TOOLGATE_MODEL_ALIAS'])
            self.assertTrue(os.environ['TOOLGATE_WORKFLOW_MODEL_FILE'].endswith('/models/another.gguf'))
        (bundle/'.env').write_text('CONFIGURATION=text-summary\nTOOLGATE_MODEL_THREADS=9\n')
        with self.assertRaisesRegex(ValueError,'only CONFIGURATION'):
            manager.environment()

    def test_deployment_waits_for_current_available_trigger(self):
        spec=importlib.util.spec_from_file_location('profile_readiness_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
        manager=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(manager)
        config=Mock()
        assignment={'metadata':{'generation':2},'status':{'observedGeneration':1,
            'conditions':[{'type':'Available','status':'True'}]}}
        config.api.return_value=assignment
        with self.assertRaisesRegex(ValueError,'not available'):
            manager.wait_trigger(config,self.settings,timeout=0)
        assignment['status']['observedGeneration']=2
        assignment['status']['conditions'][0]['status']='False'
        with self.assertRaisesRegex(ValueError,'not available'):
            manager.wait_trigger(config,self.settings,timeout=0)
        assignment['status']['conditions'][0]['status']='True'
        manager.wait_trigger(config,self.settings,timeout=0)

    def test_redeploy_requires_intact_sealed_package_custody_without_rebuilding(self):
        spec=importlib.util.spec_from_file_location('profile_package_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
        manager=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(manager)
        operator=Mock()
        assets=self.root/'assets/packages';assets.mkdir(parents=True)
        artifact=assets/'custom.olotool';artifact.write_bytes(b'reviewed package')
        metadata=dict(id='acme.sensor-watch',version='2.3.0',artifact=artifact.name,sdkVersion='1',
            packageDigest='sha256:'+'a'*64,artifactSha256=hashlib.sha256(artifact.read_bytes()).hexdigest())
        (assets/'manifest.json').write_text(json.dumps(dict(formatVersion=2,packages=[metadata])))
        settings={'id':'sensor','fileAgent':None,'deployment':{'source':{'packageId':metadata['id'],'version':metadata['version']}}}
        manager.prepare_packages(operator,settings,self.root,['compose'],assets.parent)
        (self.root/'deployment.json').write_text('{}')
        manager.run=Mock()
        manager.prepare_packages(operator,settings,self.root,['compose'],assets.parent)
        operator.packages.prepare.assert_not_called()
        staged=self.root/'package/bootstrap/trigger.olotool';staged.write_bytes(b'changed package')
        with self.assertRaisesRegex(ValueError,'sealed lock'):
            manager.prepare_packages(operator,settings,self.root,['compose'],assets.parent)
        self.assertEqual(b'changed package',staged.read_bytes())
        operator.packages.prepare.assert_not_called()


    def test_redeploy_preserves_reviewed_release_and_refuses_changed_device(self):
        spec=importlib.util.spec_from_file_location('profile_compose_manager',ROOT/'deploy/compose/WorkflowStack/manage.py')
        manager=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(manager)
        operator=Mock()
        operator.DEVICE_GROUP='folder-devices'
        operator.linux_status.return_value={'deviceId':'device-a'}
        destination=self.root/'workflow/qualified-readers.env'
        destination.parent.mkdir()
        original='TOOLGATE_CONTROL_QUALIFIED_DEVICE_FILE_READERS=device-a@folder-devices@sha256:'+'a'*64+'\n'
        destination.write_text(original)
        manager.qualify_reader(operator,Mock(),self.root)
        operator.packages.publish.assert_not_called()
        operator.shared.private_file.assert_not_called()
        self.assertEqual(original,destination.read_text())
        operator.linux_status.return_value={'deviceId':'device-b'}
        with self.assertRaisesRegex(ValueError,'does not match'):
            manager.qualify_reader(operator,Mock(),self.root)
        self.assertEqual(original,destination.read_text())
        operator.packages.publish.assert_not_called()

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.settings=configuration.load(ROOT/'deploy/compose/WorkflowStack/configurations/text-summary')
        self.control=Mock()
        self.agent=FolderAgent(self.settings,self.root/'exchange',self.root/'device',self.root/'results',self.root/'journal',self.control)
        self.addCleanup(self.agent.db.close)

    def stage(self):
        source=self.root/'exchange/inbox/meeting.txt'
        source.write_text('Staging passed. Production awaits load testing.\n')
        os.utime(source,(1,1))
        self.agent.ingest()
        self.row=self.agent.db.execute('SELECT * FROM jobs').fetchone()
        self.result={'summary':'Staging passed; load testing precedes production.'}
        self.worker={'runId':RUN,'state':'SUCCEEDED','result':self.result}
        self.control.get.return_value={'runId':RUN,'state':'SUCCEEDED','message':self.row['source'],'outcome':{'result':self.result}}
        return source

    def test_two_mounted_profiles_have_distinct_agent_roles_and_outputs(self):
        issue=configuration.load(ROOT/'deploy/compose/WorkflowStack/configurations/issue-triage')
        self.assertFalse(issue['fileAgent']['archive'])
        self.assertTrue(self.settings['fileAgent']['archive'])
        profile=json.loads((ROOT/'deploy/compose/WorkflowStack/configurations/text-summary/workflow-profile.json').read_text())
        self.assertEqual({'langchain-linux-agent','text-summarizer'},{agent['agentId'] for agent in profile['workflows']['agentDefinitions']})
        self.assertEqual([],profile['workflows']['agentDefinitions'][1]['contextSources'])
        workflow=profile['workflows']['workflows'][0]
        self.assertNotIn('agentStages',workflow)
        self.assertEqual('olo.json-graph',workflow['graph']['packageId'])
        graph=workflow['graph']['config']
        self.assertEqual([
            {'id':'stage-1','kind':'agent','agentId':'langchain-linux-agent',
             'resultMode':'CONTEXT','input':{'$path':'/message'}},
            {'id':'stage-2','kind':'agent','agentId':'text-summarizer',
             'resultMode':'MODEL','input':{'$path':'/values/stage-1'}}],graph['nodes'])
        self.assertEqual([
            {'from':['START'],'to':'stage-1'},
            {'from':['stage-1'],'to':'stage-2'},
            {'from':['stage-2'],'to':'END'}],graph['edges'])
        self.assertEqual({'$path':'/values/stage-2'},graph['output'])
        self.assertEqual(workflow['resultSchema'],graph['outputSchema'])
        source=json.loads((ROOT/'bot/configurations/text-summary/bot.json').read_text())
        self.assertEqual(workflow['graph'],source['workflows']['workflows'][0]['graph'])
        self.assertEqual('uid',configuration.resolve('${SUBSCRIPTION_UID}',{'${SUBSCRIPTION_UID}':'uid'}))
        with self.assertRaises(ValueError):
            configuration.resolve('${TOKEN}',{})

    def test_confirmed_projection_archives_exact_input_and_replay_has_no_effect(self):
        source=self.stage()
        content=source.read_bytes()
        self.agent.project(self.worker)
        self.assertFalse(source.exists())
        self.assertEqual(content,(self.root/'exchange/processed/meeting.txt').read_bytes())
        summaries=list((self.root/'exchange/summaries').glob('*.summary.txt'))
        self.assertEqual(1,len(summaries))
        self.assertIn('load testing',summaries[0].read_text())
        self.assertFalse((self.root/'device'/self.row['source']).exists())
        self.agent.project(self.worker)
        self.assertEqual(1,self.control.get.call_count)

    def test_delivery_source_comes_from_control_even_when_model_claims_another_path(self):
        source=self.stage()
        self.result['sourcePath']='inbox/other-file.txt'
        self.agent.project(self.worker)
        self.assertFalse(source.exists())
        self.assertTrue((self.root/'exchange/processed/meeting.txt').is_file())
        self.assertFalse((self.root/'exchange/processed/other-file.txt').exists())

    def test_worker_success_cannot_replace_control_authority_or_admitted_source(self):
        source=self.stage()
        original=deepcopy(self.control.get.return_value)
        for change in ({'state':'FAILED'},{'message':'inbox/other.txt'},{'outcome':None}):
            self.control.get.return_value={**original,**change}
            self.agent.project(self.worker)
            self.assertTrue(source.exists())
            self.assertEqual([],list((self.root/'exchange/summaries').iterdir()))

    def test_changed_input_and_output_collision_never_archive_or_overwrite(self):
        source=self.stage()
        old=source.read_bytes()
        source.write_text('Changed by the operator')
        with self.assertRaisesRegex(ValueError,'INPUT_CHANGED'):
            self.agent.project(self.worker)
        self.assertFalse((self.root/'exchange/processed/meeting.txt').exists())
        source.write_bytes(old)
        output=self.root/'exchange/summaries'/('meeting-'+self.row['id'][:12]+'.summary.txt')
        output.write_text('Existing output')
        with self.assertRaisesRegex(ValueError,'OUTPUT_COLLISION'):
            self.agent.project(self.worker)
        self.assertEqual('Existing output',output.read_text())
        self.assertTrue(source.exists())

    def test_recovery_after_archival_uses_original_projection_without_resending_input(self):
        source=self.stage()
        write_once(self.root/'exchange/processed/meeting.txt',source.read_bytes())
        source.unlink()
        with self.agent.db:
            self.agent.db.execute("UPDATE jobs SET state='PROJECTING',run=?",(RUN,))
        self.agent.project(self.worker)
        self.assertEqual('COMPLETE',self.agent.db.execute('SELECT state FROM jobs').fetchone()[0])
        self.assertFalse(source.exists())

    def test_waiting_input_is_not_republished_if_device_snapshot_disappears(self):
        self.stage()
        (self.root/'device'/self.row['source']).unlink()
        self.agent.ingest()
        self.assertFalse((self.root/'device'/self.row['source']).exists())

    def test_unknown_existing_journal_is_refused_without_conversion(self):
        root=self.root/'obsolete'
        root.mkdir()
        db=sqlite3.connect(root/'delivery.sqlite')
        db.execute('CREATE TABLE obsolete(value TEXT)')
        db.commit()
        db.close()
        with self.assertRaisesRegex(ValueError,'UNSUPPORTED_JOURNAL'):
            FolderAgent(self.settings,self.root/'exchange',self.root/'device',self.root/'results',root,self.control)
        db=sqlite3.connect(root/'delivery.sqlite')
        self.assertEqual([('obsolete',)],db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall())
        db.close()


if __name__=='__main__':
    unittest.main()
