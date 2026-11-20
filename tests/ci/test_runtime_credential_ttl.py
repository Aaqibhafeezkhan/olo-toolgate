# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Service-only lifetime changes leave human session issuance short-lived."""
import ast
import base64
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch,Mock

ROOT=Path(__file__).resolve().parents[2]


class RuntimeCredentialTests(unittest.TestCase):
    def setUp(self):
        source=ast.parse((ROOT/'apps/quickstart/supervisor.py').read_text(encoding='utf-8'))
        functions=[node for node in source.body if isinstance(node,ast.FunctionDef) and node.name in ('jwt','runtime_credential','runtime_credential_ttl','runtime_credential_settings')]
        self.ns=dict(os=os,TENANT='tenant-fixture',ISSUER='issuer',time=SimpleNamespace(time=lambda:10000),
            base64=base64,json=json,strict=json.loads,key=lambda _:Mock(sign=lambda *args:b'fixture-signature'),
            padding=SimpleNamespace(PKCS1v15=lambda:None),hashes=SimpleNamespace(SHA256=lambda:None),local_users=lambda:set())
        exec(compile(ast.Module(body=functions,type_ignores=[]),'<runtime issuance>','exec'),self.ns)

    def claims(self,token):
        payload=token.split('.')[1];return json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))

    def test_default_seven_days_only_for_runtime_and_expiry_matches_signed_claim(self):
        with patch.dict(os.environ,{},clear=True):result=self.ns['runtime_credential']()
        claims=self.claims(result['accessToken'])
        self.assertEqual(604800,claims['exp']-claims['iat'])
        self.assertEqual(claims['exp']*1000,result['expiresAtUnixMs'])
        self.assertEqual(['toolgate-workflow-runtime'],claims['groups'])
        self.assertNotIn('user_id',claims)
        with patch.dict(os.environ,{},clear=True):settings=self.ns['runtime_credential_settings']()
        self.assertEqual('604800',settings['MP_JWT_VERIFY_TOKEN_AGE'])
        self.assertEqual('604800',settings['SMALLRYE_JWT_TIME_TO_LIVE'])
        human=self.claims(self.ns['jwt'](['toolgate-admin']))
        self.assertEqual(900,human['exp']-human['iat'])
        with self.assertRaises(ValueError):self.ns['jwt'](['toolgate-admin'],lifetime_seconds=604800)

    def test_explicit_lifetime_and_invalid_settings(self):
        for ttl in ('1','30','3600','2592000'):
            with patch.dict(os.environ,TOOLGATE_RUNTIME_CREDENTIAL_TTL_SECONDS=ttl):
                claims=self.claims(self.ns['runtime_credential']()['accessToken'])
                self.assertEqual(int(ttl),claims['exp']-claims['iat'])
                limits=self.ns['runtime_credential_settings']()
                self.assertEqual(str(max(900,int(ttl))),limits['SMALLRYE_JWT_TIME_TO_LIVE'])
                self.assertEqual(ttl,limits['TOOLGATE_RUNTIME_CREDENTIAL_TTL_SECONDS'])
        for ttl in ('0','-1','1.5','2592001','forever','１２'):
            with patch.dict(os.environ,TOOLGATE_RUNTIME_CREDENTIAL_TTL_SECONDS=ttl):
                with self.assertRaises(ValueError):self.ns['runtime_credential']()
