# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Workflow import refuses implicit identity authority and shared-definition replacement."""
from copy import deepcopy
import json
import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
from tools.admin import import_workflow as importer
from tools.admin.control import Refused,HttpRefused
from tools.admin import provision_devices as provision


class WorkflowImportTests(unittest.TestCase):
    def test_operator_setup_runs_after_references_and_before_provision_and_denial_stops(self):
        files={'workflow.json':b'{"workflows":{},"profileId":"demo"}', 'referenced-data.json':b'{}'}
        for refused in (False,True):
            events=[]
            def call(_client,path,body=None,**kwargs):
                if path.endswith('/config/export'):return {**self.snapshot(),'revision':1}
                if path=='/api/control/v1/workflow-profiles':
                    self.assertEqual(['bindings','operator-setup'],events)
                    raise Refused('stage reached')
                return {}
            def setup(profile):
                events.append('operator-setup')
                self.assertEqual('demo',profile['profileId'])
                if refused:raise Refused('operator denied')
            with self.subTest(refused=refused),patch.object(importer,'verify',return_value={'packages':[]}),\
                    patch.object(importer,'inventory',return_value=files),patch.object(importer,'call',side_effect=call),\
                    patch.object(importer,'service_bindings',side_effect=lambda *_:events.append('bindings')):
                with self.assertRaisesRegex(Refused,'operator denied' if refused else 'stage reached'):
                    importer.deploy(object(),b'bundle',after_references=setup)

    def test_preflight_precedes_upload_and_confirmed_registration_refreshes_import_revision(self):
        metadata=dict(packageId='example.files',version='1.0.0',packageDigest='sha256:'+'a'*64,path='packages/files.olotool')
        manifest={'packages':[metadata]};files={'workflow.json':b'{}','referenced-data.json':json.dumps({'policies':[{'id':'new-policy'}]}).encode(),metadata['path']:b'fixture'}
        first=self.snapshot();first['revision']=7
        current=deepcopy(first);current['revision']=8
        events=[];exports=iter([first,current])
        def call(_client,path,body=None,**kwargs):
            events.append((path,body,kwargs))
            if path.endswith('/config/export'):return next(exports)
            if path.endswith('/packages/uploads'):return {**metadata,'state':'VERIFIED'}
            if path.endswith('/config/import'):
                self.assertEqual(7 if body['dryRun'] else 8,kwargs['revision'])
                return {'changes':[{}]}
            self.fail('Unexpected call '+path)
        with patch.object(importer,'verify',return_value=manifest),patch.object(importer,'inventory',return_value=files),\
                patch.object(importer,'call',side_effect=call),patch.object(importer,'service_bindings',side_effect=Refused('stop after confirmed configuration')):
            with self.assertRaisesRegex(Refused,'stop after confirmed'):importer.deploy(object(),b'fixture')
        self.assertEqual(['/api/control/v1/config/export','/api/control/v1/config/import',
            '/api/control/v1/packages/uploads','/api/control/v1/config/export','/api/control/v1/config/import'],[event[0] for event in events])

    def test_changed_installation_ca_preserves_operator_files_before_any_login(self):
        from tools.workflows import hotswap_demo
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);(folder/'gateway-ca.crt').write_text('previous-ca')
            (folder/'password.txt').write_text('saved-private-password')
            with patch.object(hotswap_demo,'PRIVATE',folder),patch.object(hotswap_demo,'command',return_value=SimpleNamespace(stdout='different-ca')),patch.object(hotswap_demo.urllib.request,'build_opener') as opener:
                with self.assertRaises(Refused):hotswap_demo.login()
                opener.assert_not_called()
            self.assertEqual('previous-ca',(folder/'gateway-ca.crt').read_text())
            self.assertEqual('saved-private-password',(folder/'password.txt').read_text())

    def snapshot(self):
        return dict(requiredIdentities=[{'id':'admin'}],users=[],identityBindings=[],deviceEvidence=[],devices=[],workloadBindings=[],
                    deviceGroups=[dict(id='files',name='Local group',enabled=True,revision=4,deviceIds=['device-1'])],
                    tools=[],bindings=[],policies=[])

    def test_profile_cannot_allocate_device_membership_or_import_credentials(self):
        for collection in ('users','identityBindings','deviceEvidence','devices','workloadBindings'):
            with self.subTest(collection=collection),self.assertRaises(Refused):
                importer.merge_references(self.snapshot(),{collection:[{'id':'untrusted'}]})
        with self.assertRaises(Refused):
            importer.merge_references(self.snapshot(),dict(deviceGroups=[dict(id='files',deviceIds=['device-2'])]))

    def test_destination_membership_and_local_group_name_are_preserved(self):
        snapshot=self.snapshot();original=deepcopy(snapshot)
        result=importer.merge_references(snapshot,dict(deviceGroups=[dict(id='files',name='Portable group',revision=1,enabled=True,deviceIds=[])]))
        self.assertEqual(original,snapshot)
        self.assertEqual(original['deviceGroups'],result['deviceGroups'])

    def test_missing_owner_and_conflicting_shared_definition_stop_import(self):
        with self.assertRaises(Refused):importer.merge_references(self.snapshot(),dict(requiredIdentities=[{'id':'someone'}]))
        snapshot=self.snapshot();snapshot['policies']=[dict(id='p',enabled=True,revision=4,decision='DENY')]
        with self.assertRaises(Refused):importer.merge_references(snapshot,dict(policies=[dict(id='p',enabled=True,revision=1,decision='ALLOW')]))

    def test_matching_tool_keeps_destination_installed_digest(self):
        snapshot=self.snapshot();snapshot['tools']=[dict(id='t',version='1.0.0',definition={'id':'t'},packageDigest='installed',revision=8)]
        result=importer.merge_references(snapshot,dict(tools=[dict(id='t',version='1.0.0',definition={'id':'t'},packageDigest='source',revision=1)]))
        self.assertEqual(snapshot['tools'],result['tools'])

    def test_pending_is_never_reported_as_applied_and_no_retry_occurs(self):
        client=Mock();client.request.return_value=(202,{'id':'pending'})
        with self.assertRaises(Refused):importer.call(client,'/api/control/v1/config/import',{'snapshot':{}})
        self.assertEqual(1,client.request.call_count)

    def test_transport_patch_is_merge_patch_and_repeated_exact_input_has_same_key(self):
        client=Mock();client.request.return_value=(200,{'revision':2})
        for _ in range(2):importer.call(client,'/api/control/v1/settings',{'deviceApproval':{}},'PATCH',revision=1)
        self.assertEqual(client.request.call_args_list[0],client.request.call_args_list[1])
        self.assertEqual('application/merge-patch+json',client.request.call_args.args[3])

    def test_wait_can_observe_an_enrollment_before_endpoint_exists_but_never_retries_denial(self):
        with patch.object(provision,'call',side_effect=[HttpRefused(404),{'state':'ACTIVE'}]),patch.object(provision.time,'sleep'):
            self.assertEqual('ACTIVE',provision.wait_active(object(),'device-1',5)['state'])
        with patch.object(provision,'call',side_effect=HttpRefused(403)) as call:
            with self.assertRaises(HttpRefused):provision.wait_active(object(),'device-1',5)
            self.assertEqual(1,call.call_count)


if __name__=='__main__':unittest.main()
