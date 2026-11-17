# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""A newly published workflow can deliver without trusting worker claims or changing credentials."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock,patch
import urllib.error

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'deploy/compose/WorkflowStack/scripts'))
from folder_agent import ControlResults


class LiveDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.old='sha256:'+'a'*64;self.new='sha256:'+'b'*64
        (self.root/'delivery.json').write_text(json.dumps(dict(profileId='profile-a',profileDigest=self.old,workflowIds=['workflow-a'])))
        (self.root/'result.token').write_text('fixture-token')
        context=Mock(verify_flags=0)
        with patch('folder_agent.ssl.create_default_context',return_value=context):
            self.results=ControlResults('https://localhost:8443',self.root)
        self.results.opener=Mock()
        self.run=dict(runId='run-'+'a'*8+'-'+'b'*4+'-'+'c'*4+'-'+'d'*4+'-'+'e'*12,
                      profileDigest=self.new,workflowId='workflow-a',workflowVersionId='version-b',state='SUCCEEDED')

    def responses(self,publication=None):
        return [io.BytesIO(json.dumps(self.run).encode()),io.BytesIO(json.dumps(publication or dict(workflows=[dict(id='workflow-a',versionId='version-b')])).encode())]

    def test_current_publication_allows_new_digest_and_keeps_tokens_unchanged(self):
        self.results.opener.open.side_effect=self.responses()
        self.assertEqual(self.run,self.results.get(self.run['runId']))
        self.assertEqual(self.new,self.results.binding['profileDigest'])
        request=self.results.opener.open.call_args_list[1].args[0]
        self.assertIn('/published/profile-a/'+self.new,request.full_url)
        self.assertEqual('fixture-token',(self.root/'result.token').read_text())
        self.assertEqual(self.old,json.loads((self.root/'delivery.json').read_text())['profileDigest'])

    def test_unbound_workflow_is_rejected_before_publication_lookup(self):
        self.run['workflowId']='another-workflow'
        self.results.opener.open.side_effect=self.responses()
        with self.assertRaisesRegex(ValueError,'FOREIGN_WORKFLOW'):self.results.get(self.run['runId'])
        self.assertEqual(1,self.results.opener.open.call_count)

    def test_version_mismatch_and_old_publication_cannot_authorize_delivery(self):
        self.results.opener.open.side_effect=self.responses(dict(workflows=[dict(id='workflow-a',versionId='old-version')]))
        with self.assertRaisesRegex(ValueError,'FOREIGN_WORKFLOW'):self.results.get(self.run['runId'])
        self.results.opener.open.side_effect=[io.BytesIO(json.dumps(self.run).encode()),urllib.error.HTTPError('https://localhost',404,'not current',{},None)]
        with self.assertRaisesRegex(ValueError,'FOREIGN_WORKFLOW'):self.results.get(self.run['runId'])


if __name__=='__main__':unittest.main()
