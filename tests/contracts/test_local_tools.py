# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The local tool IPC contract stays closed and separate from client control; public artifacts stay anonymous."""
import json
import sys
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, ValidationError
from test_foundation import validators, schemas_at
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/client'))
from package import verify_binary

class LocalToolContracts(unittest.TestCase):
    def test_protocol_one_does_not_accept_tools_and_two_is_closed(self):
        models=validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures=json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        with self.assertRaises(ValidationError):models['ClientIpcRequest'].validate(fixtures['LocalToolIpcRequest'])
        with self.assertRaises(ValidationError):models['LocalToolIpcRequest'].validate(fixtures['ClientIpcRequest'])
        with self.assertRaises(ValidationError):models['LocalToolIpcRequest'].validate({**fixtures['LocalToolIpcRequest'],'token':'cannot-export'})
    def test_executable_names_and_short_fake_magic_are_insufficient(self):
        for target in ['x86_64-unknown-linux-gnu','x86_64-pc-windows-gnu','x86_64-apple-darwin']:
            with self.assertRaises(ValueError):verify_binary(b'fake-binary',target)
            with self.assertRaises(ValueError):verify_binary(b'MZ'+bytes(64),target)
    def test_public_api_is_anonymous_but_directory_api_keeps_security(self):
        import yaml
        document=yaml.safe_load((ROOT/'packages/contracts/openapi/control-v1.yaml').read_text())
        self.assertEqual([],document['paths']['/api/public/v1/clients']['get']['security'])
        self.assertEqual([],document['paths']['/api/public/v1/clients/{filename}']['get']['security'])
        self.assertNotEqual([],document['security'])

if __name__=='__main__':unittest.main()
