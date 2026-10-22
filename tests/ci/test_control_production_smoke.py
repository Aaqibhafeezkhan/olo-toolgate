# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Release presets and installation owners cannot weaken production smoke checks."""
import copy
import unittest
from unittest.mock import Mock

from tools.control import container


def installation():
    snapshot=copy.deepcopy(container.initial_configuration()['records'])
    snapshot['roles'].append(dict(id='installation-management',portalRole='SUPER_ADMIN',revision=1,managementRules=[]))
    snapshot['bindings'].append(dict(id='binding-default'))
    snapshot['teams'].append(dict(id='installation-administrators',revision=1,userIds=['admin','reviewer-1','reviewer-2'],roleIds=['installation-management']))
    next(team for team in snapshot['teams'] if team['id']=='team-default')['userIds']=['admin','reviewer-1','reviewer-2']
    snapshot['tools']=[dict(id='builtin',enabled=False)]
    snapshot['agents']=[dict(id='agent-local',enabled=False)]
    for field in ('delegations','agentDelegations','workloadBindings','devices','deviceEvidence'):snapshot[field]=[]
    return snapshot


class ProductionSmokeDefaultsTests(unittest.TestCase):
    def test_current_release_presets_are_valid_without_active_runtime_membership(self):
        snapshot=installation()
        self.assertTrue(snapshot['grants'])
        self.assertGreater(len(snapshot['deviceGroups']),1)
        container.protected_defaults(snapshot)

    def test_missing_or_modified_preset_authority_is_rejected(self):
        for field in ('grants','policies','roles','bindings'):
            snapshot=installation();snapshot[field][0]['enabled']=False
            with self.subTest(field=field),self.assertRaises(AssertionError):container.protected_defaults(snapshot)
        snapshot=installation();snapshot['grants'].pop()
        with self.assertRaises(AssertionError):container.protected_defaults(snapshot)

    def test_additional_grants_or_groups_and_populated_preset_groups_are_rejected(self):
        for field in ('grants','deviceGroups'):
            snapshot=installation();snapshot[field].append({**snapshot[field][0],'id':'unreviewed'})
            with self.subTest(field=field),self.assertRaises(AssertionError):container.protected_defaults(snapshot)
        for field,default,member in (('teams','team-default','userIds'),('agentGroups','default-agents','agentIds'),
                                     ('toolGroups','default-tools','toolIds'),('deviceGroups','default-devices','deviceIds')):
            snapshot=installation()
            next(group for group in snapshot[field] if group['id'] not in (default,'installation-administrators'))[member]=['unexpected-member']
            with self.subTest(field=field),self.assertRaises(AssertionError):container.protected_defaults(snapshot)

    def test_active_tools_agents_and_runtime_identity_paths_are_rejected(self):
        for field in ('tools','agents','delegations','agentDelegations','workloadBindings','devices','deviceEvidence'):
            snapshot=installation()
            if field in ('tools','agents'):snapshot[field][0]['enabled']=True
            else:snapshot[field].append(dict(id='unreviewed'))
            with self.subTest(field=field),self.assertRaises(AssertionError):container.protected_defaults(snapshot)

    def test_review_fixture_preserves_bootstrap_owner_when_restricting_reviewers(self):
        api=Mock(side_effect=[(201,{}),(201,{}),(200,{})])
        container.scoped_reviewers(api,installation())
        calls=api.call_args_list
        role=calls[0].kwargs['body'];team=calls[1].kwargs['body']
        self.assertEqual(role['portalRole'],'ADMINISTRATOR')
        self.assertEqual(team['userIds'],['reviewer-1','reviewer-2'])
        self.assertEqual(team['roleIds'],[role['id']])
        self.assertEqual(calls[2].args[0],'/api/control/v1/teams/installation-administrators')
        self.assertEqual(calls[2].kwargs['body']['userIds'],['admin'])
        self.assertEqual(calls[2].kwargs['body']['roleIds'],['installation-management'])
        self.assertEqual(calls[2].kwargs['revision'],1)

    def test_failed_scoped_role_creation_leaves_owners_unchanged(self):
        api=Mock(return_value=(403,{}))
        with self.assertRaises(AssertionError):container.scoped_reviewers(api,installation())
        self.assertEqual(api.call_count,1)
