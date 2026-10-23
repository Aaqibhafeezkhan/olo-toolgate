# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('configuration',ROOT/'debug/configure-debug-agent.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class InitialConfigurationTests(unittest.TestCase):
    def test_release_bundle_has_complete_group_presets_and_no_individual_acl(self):
        bundle=module.load_configuration()
        for name,suffix in [('teams','Team'),('agentGroups','AgentGroup'),('toolGroups','ToolGroup'),('deviceGroups','DeviceGroup')]:
            records=bundle['records'][name]
            self.assertTrue({tier+suffix for tier in ('ReadOnly','ReadAndWrite','Admin')} <= {x['id'] for x in records})
            for record in records:
                members=next(record[key] for key in ('userIds','agentIds','toolIds','deviceIds') if key in record)
                self.assertEqual([],members)
        self.assertTrue(all(not b['allowedPackageDigests'] for b in bundle['records']['bindings']))
        self.assertFalse({'users','agents','devices','tools'}&bundle['records'].keys())

    def test_admin_credentials_use_login_api_without_docker_or_signing_keys(self):
        with patch.object(module.Configuration,'login') as login,patch.object(module.urllib.request,'build_opener'),patch.object(module,'docker_script') as docker,patch.dict(module.os.environ,{'TOOLGATE_ADMIN_USERNAME':'operator','TOOLGATE_ADMIN_PASSWORD':'example'},clear=True):
            module.Configuration()
            login.assert_called_once_with('admin','operator','example');docker.assert_not_called()

    def test_membership_allocation_uses_reviewed_api_and_expected_revision(self):
        config=object.__new__(module.Configuration)
        current=dict(entityType='AGENT',entityId='agent',groupIds=['default-agents'],revision=9)
        with patch.object(config,'api',side_effect=[current,(202,dict(id='draft'))]) as api,patch.object(config,'apply_change') as review:
            config.memberships('agents','agent',['ReadAndWriteAgentGroup'])
            self.assertEqual(api.call_args_list[1].args,('/api/control/v1/agents/agent/groups',{**current,'groupIds':['ReadAndWriteAgentGroup']},'PUT'))
            self.assertEqual(api.call_args_list[1].kwargs['revision'],9);review.assert_called_once_with(dict(id='draft'))

    def test_direct_super_admin_mutations_do_not_submit_an_entity_as_a_change(self):
        config=object.__new__(module.Configuration)
        for status,document in [(200,dict(id='examples.hotfolder/write_text')), (201,dict(id='agent')), (204,None)]:
            with self.subTest(status=status), patch.object(config,'api',return_value=(status,document)),patch.object(config,'apply_change') as review:
                config.mutate('/api/control/v1/agents',dict(id='agent'))
                review.assert_not_called()

    def test_scoped_administrator_still_completes_independent_review(self):
        config=object.__new__(module.Configuration)
        config.tokens={'admin':'requester','reviewer-1':'independent-reviewer'}
        draft=dict(id='config-example',revision=1,state='DRAFT',requiredReviews=1)
        with patch.object(config,'api',side_effect=[(202,draft),
                {**draft,'revision':2,'state':'PENDING'}, {**draft,'revision':3,'state':'APPROVED'},
                {**draft,'revision':4,'state':'APPLIED'}]) as api:
            config.mutate('/api/control/v1/agents',dict(id='agent'))
        self.assertEqual([call.args[1]['action'] for call in api.call_args_list[1:]],['SUBMIT','APPROVE','APPLY'])
        self.assertEqual(api.call_args_list[2].kwargs['user'],'reviewer-1')

    def test_empty_direct_delete_response_is_accepted(self):
        config=object.__new__(module.Configuration)
        config.tokens={'admin':'session'}
        response=unittest.mock.MagicMock(status=204)
        response.__enter__.return_value=response
        response.read.return_value=b''
        config.opener=unittest.mock.Mock()
        config.opener.open.return_value=response
        config.origin='http://127.0.0.1:18090'
        with patch.object(config,'apply_change') as review:
            config.mutate('/api/control/v1/agents/agent',method='DELETE',revision=1)
            review.assert_not_called()

    def test_configured_membership_does_not_create_another_change(self):
        config=object.__new__(module.Configuration)
        with patch.object(config,'api',return_value=dict(groupIds=['ReadAndWriteAgentGroup'])) as api,patch.object(config,'apply_change') as review:
            config.memberships('agents','agent',['ReadAndWriteAgentGroup']);self.assertEqual(api.call_count,1);review.assert_not_called()


class PackageToolTests(unittest.TestCase):
    def test_package_grants_pin_exports_digests_and_selected_device_without_changing_standard_bindings(self):
        from unittest.mock import Mock
        for group in ('ReadAndWriteAgentGroup', 'ReadOnlyAgentGroup'):
            config=Mock()
            installed={tool:{'packageDigest':'a'*64} for tool in module.MIMIC_TOOLS}
            module.configure_package_access(config,'device-selected',installed,group)
            records={(call.args[0],call.args[1]['id']):call.args[1] for call in config.upsert.call_args_list}
            binding=records['bindings','debug-mimic-binding']
            self.assertEqual(binding['actions'],['invoke'])
            self.assertEqual(binding['allowedPackageDigests'],['a'*64])
            self.assertEqual(records['device-groups','debug-mimic-devices']['deviceIds'],['device-selected'])
            self.assertFalse(any(key[1].startswith('standard-') for key in records))
            scope=records['grants','debug-mimic-service']['scope']
            self.assertEqual(scope['resources'],[dict(kind='CUSTOM',locator='tool://'+tool,match='EXACT') for tool in sorted(module.MIMIC_TOOLS)])
            membership=next(call.args[2] for call in config.memberships.call_args_list if call.args[0]=='agents')
            self.assertEqual('debug-mimic-agents' in membership,group!='ReadOnlyAgentGroup')

    def test_the_debug_agent_is_never_configured_with_tools_a_package_did_not_install(self):
        config=object.__new__(module.Configuration)
        with patch.object(module,'private_file'),patch.object(module,'Configuration',return_value=config),             patch.object(config,'api',side_effect=[{'role':'SUPER_ADMIN'},{'state':'ACTIVE','lastSeenUnixMs':10**15}]),             patch.object(config,'presets'),patch.object(config,'all',return_value=[{'id':'examples.hotfolder/write_text','packageDigest':'a'*64}]),             patch.object(config,'upsert') as upsert,patch.object(module.time,'time',return_value=10**12):
            with self.assertRaisesRegex(ValueError,'Install the examples.hotfolder package'):
                module.configure('device-1')
            upsert.assert_not_called()
