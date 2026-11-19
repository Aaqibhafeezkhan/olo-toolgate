# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Administrative device provisioning uses the existing policy and membership APIs."""
import unittest
from unittest.mock import patch
from tools.admin import provision_devices as provision
from tools.admin.control import Refused


class ProvisionDevicesTests(unittest.TestCase):
    def test_auto_approval_uses_device_settings_revision_without_replacing_other_settings(self):
        with patch.object(provision,'call',side_effect=[{'enabled':True},{'revision':8,'deviceApproval':{}},{'revision':9}]) as call:
            self.assertEqual({'revision':9},provision.approval_policy(object(),'admin',30))
        self.assertEqual('/api/control/v1/settings?section=device',call.call_args_list[1].args[1])
        self.assertEqual(('/api/control/v1/settings',{'deviceApproval':{'autoApprove':True,'durationMs':2592000000,'ownerUserId':'admin'}},'PATCH'),call.call_args.args[1:])
        self.assertEqual({'revision':8},call.call_args.kwargs)

    def test_disabled_owner_or_bad_duration_never_changes_policy(self):
        with patch.object(provision,'call',return_value={'enabled':False}) as call:
            with self.assertRaises(Refused):provision.approval_policy(object())
            self.assertEqual(1,call.call_count)
        for value in (0,3651,True,1.5):
            with patch.object(provision,'call') as call:
                with self.assertRaises(Refused):provision.approval_policy(object(),days=value)
                call.assert_not_called()

    def test_unchanged_policy_does_not_repeat_write(self):
        current={'revision':4,'deviceApproval':{'autoApprove':True,'durationMs':86400000,'ownerUserId':'admin'}}
        with patch.object(provision,'call',side_effect=[{'enabled':True},current]) as call:
            self.assertEqual(current,provision.approval_policy(object(),days=1))
            self.assertEqual(2,call.call_count)

    def test_allocate_enables_and_unions_existing_groups_with_fresh_membership_revision(self):
        rows=[{'state':'ACTIVE','connectionApproved':True},{'enabled':True},{'enabled':False,'revision':6},{},
              {'entityType':'DEVICE','entityId':'device-1','revision':9,'groupIds':['existing']},{}]
        with patch.object(provision,'call',side_effect=rows) as call:
            result=provision.allocate(object(),'device-1',['new'])
        self.assertEqual({'deviceId':'device-1','groupIds':['existing','new']},result)
        self.assertEqual({'expectedRevision':6,'enabled':True},call.call_args_list[3].args[2])
        self.assertEqual({'revision':9},call.call_args.kwargs)
        self.assertEqual('PUT',call.call_args.args[3])
        self.assertEqual(['existing','new'],call.call_args.args[2]['groupIds'])

    def test_inactive_expired_and_disabled_group_refuse_before_mutation(self):
        for rows in ([{'state':'PENDING'}],[{'state':'ACTIVE','connectionApproved':False}],
                     [{'state':'ACTIVE','connectionExpiresAtUnixMs':0}],
                     [{'state':'ACTIVE'},{'enabled':False}]):
            with self.subTest(rows=rows),patch.object(provision,'call',side_effect=rows) as call:
                with self.assertRaises(Refused):provision.allocate(object(),'device-1',['group-1'])
                self.assertTrue(all(len(c.args)==2 for c in call.call_args_list))

    def test_existing_allocation_is_read_only(self):
        with patch.object(provision,'call',side_effect=[{'state':'ACTIVE'},{'enabled':True},{'enabled':True},
                    {'revision':7,'groupIds':['old','new']}]) as call:
            provision.allocate(object(),'device-1',['new'])
            self.assertTrue(all(len(c.args)==2 for c in call.call_args_list))

    def test_pending_approval_selects_one_named_device_and_binds_its_reviewed_key(self):
        enrollment=dict(state='PENDING',userCode='ABC123',keyFingerprint='key-1')
        rows={'items':[dict(deviceId='device-2',enrollment={}),dict(deviceId='device-1',enrollment=enrollment)]}
        review=dict(deviceId='device-1',**enrollment)
        with patch.object(provision,'call',side_effect=[rows,review,{}]) as call:
            provision.approve_pending(object(),'device-1',30)
        self.assertEqual('/api/control/v1/endpoint/enrollments/decision',call.call_args.args[1])
        self.assertEqual('key-1',call.call_args.args[2]['keyFingerprint'])
        self.assertEqual('APPROVE',call.call_args.args[2]['choice'])

    def test_changed_enrollment_key_refuses_without_approving(self):
        enrollment=dict(state='PENDING',userCode='ABC123',keyFingerprint='key-1')
        with patch.object(provision,'call',side_effect=[{'items':[dict(deviceId='device-1',enrollment=enrollment)]},dict(deviceId='device-1',**{**enrollment,'keyFingerprint':'key-2'})]) as call:
            with self.assertRaises(Refused):provision.approve_pending(object(),'device-1',30)
            self.assertEqual(2,call.call_count)

    def test_observation_wait_does_not_retry_mutations(self):
        with patch.object(provision,'call',side_effect=[{'state':'PENDING'},{'state':'ACTIVE'}]) as call,patch.object(provision.time,'sleep'):
            self.assertEqual('ACTIVE',provision.wait_active(object(),'device-1',5)['state'])
            self.assertTrue(all(len(c.args)==2 for c in call.call_args_list))
        with patch.object(provision,'call',return_value={'state':'PENDING'}) as call:
            with self.assertRaises(Refused):provision.wait_active(object(),'device-1',0)
            self.assertEqual(1,call.call_count)

    def test_invalid_ids_or_no_group_never_make_requests(self):
        for device,groups in [('device/else',['group']),('device',['bad?x=1']),('device',[])]:
            with patch.object(provision,'call') as call:
                with self.assertRaises(Refused):provision.allocate(object(),device,groups)
                call.assert_not_called()


if __name__=='__main__':unittest.main()
