# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Restart credentials are verified against custody, never silently reset."""
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'debug/profiled'/file)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
helper=load('credential_state','credential-state.py')
stack=load('credential_stack','stack.py')


class CredentialTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name);(self.root/'keys').mkdir()
        salt=bytes(range(16));digest=hashlib.pbkdf2_hmac('sha256',b'current-password',salt,600000).hex()
        (self.root/'identity.json').write_text(json.dumps(dict(salt=salt.hex(),hash=digest)))
        (self.root/'keys/device-ca.crt').write_text('public-ca')

    def test_saved_password_survives_restart_without_initial_password(self):
        result=helper.resolve(self.root,'current-password')
        self.assertEqual(('READY','saved','current-password',False),(result['state'],result['source'],result['password'],result['initialAvailable']))

    def test_stale_local_password_uses_only_current_verified_initial_password(self):
        (self.root/'bootstrap-password').write_text('current-password\n')
        self.assertEqual('initial',helper.resolve(self.root,'stale')['source'])
        (self.root/'bootstrap-password').write_text('obsolete-initial-password')
        self.assertEqual({'state':'PASSWORD_REQUIRED'},helper.resolve(self.root,'stale'))

    def test_changed_password_is_not_reset_or_falsely_recovered_from_verifier(self):
        before=(self.root/'identity.json').read_bytes()
        self.assertEqual({'state':'PASSWORD_REQUIRED'},helper.resolve(self.root,None))
        self.assertEqual(before,(self.root/'identity.json').read_bytes())
        self.assertFalse((self.root/'bootstrap-password').exists())

    def prepare_launcher(self):
        (self.root/'credential-state.py').write_text('read-only helper')
        folder=self.root/'credentials/admin';folder.mkdir(parents=True)
        return folder

    def test_sync_refreshes_stale_initial_files_and_preserves_history_without_logging_password(self):
        folder=self.prepare_launcher();(folder/'password.txt').write_text('old-password\n');(folder/'gateway-ca.crt').write_text('old-ca')
        value=dict(state='READY',source='initial',password='current-password',initialAvailable=True,ca='public-ca')
        with patch.object(stack,'HERE',self.root),patch.object(stack.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(value))) as run,patch('builtins.print') as output,patch.object(stack,'private',side_effect=lambda p,d:(p.parent.mkdir(parents=True,exist_ok=True),p.write_text(d))):
            stack.sync_credentials(['docker','compose'])
        self.assertEqual('current-password\n',(folder/'password.txt').read_text())
        self.assertEqual('public-ca',(folder/'gateway-ca.crt').read_text())
        self.assertEqual('old-password\n',next((folder/'history').glob('*/password.txt')).read_text())
        self.assertNotIn('current-password',str(output.call_args_list))
        self.assertNotIn('old-password',str(run.call_args.args))
        self.assertEqual({'password':'old-password'},json.loads(run.call_args.kwargs['input']))

    def test_password_input_accepts_powershell_utf8_bom_without_stripping_password_spaces(self):
        folder=self.prepare_launcher();(folder/'password.txt').write_text(' current-password \n',encoding='utf-8-sig')
        value=dict(state='READY',source='saved',password=' current-password ',initialAvailable=False,ca='public-ca')
        with patch.object(stack,'HERE',self.root),patch.object(stack.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(value))) as run,patch('builtins.print'),patch.object(stack,'private',side_effect=lambda p,d:(p.parent.mkdir(parents=True,exist_ok=True),p.write_text(d,encoding='utf-8'))):
            stack.sync_credentials(['docker','compose'])
        self.assertEqual({'password':' current-password '},json.loads(run.call_args.kwargs['input']))

    def test_sync_refuses_changed_trust_after_rotation_and_preserves_files(self):
        folder=self.prepare_launcher();(folder/'password.txt').write_text('current-password\n');(folder/'gateway-ca.crt').write_text('old-ca')
        value=dict(state='READY',source='saved',password='current-password',initialAvailable=False,ca='new-ca')
        with patch.object(stack,'HERE',self.root),patch.object(stack.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(value))):
            with self.assertRaises(ValueError):stack.sync_credentials(['docker','compose'])
        self.assertEqual('old-ca',(folder/'gateway-ca.crt').read_text())
        self.assertFalse((folder/'history').exists())


if __name__=='__main__':unittest.main()
