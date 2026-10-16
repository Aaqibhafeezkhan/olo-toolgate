# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""SDK Builder API boundaries and the separate signed device-package distribution authority."""
import base64
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path
import yaml
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from builder.sign import sign_release


def decode(value):
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


class BuilderContracts(unittest.TestCase):
    def test_distribution_signer_preserves_exact_bytes_and_separate_authority(self):
        fixtures = json.loads((ROOT / 'tests/fixtures/contracts/v1/valid.json').read_text())
        raw = json.dumps(fixtures['FleetPackageDocument'], indent=2).encode()
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        release = sign_release(raw, key, 'offline-release')
        header, payload, signature = release['release']['jws'].split('.')
        self.assertEqual(decode(payload), raw)
        self.assertEqual(release['manifestDigest'], hashlib.sha256(raw).hexdigest())
        key.public_key().verify(decode(signature), (header + '.' + payload).encode(), padding.PKCS1v15(), hashes.SHA256())
        self.assertEqual(json.loads(decode(header))['typ'], 'toolgate-package-release+jws')
        invalid = copy.deepcopy(fixtures['FleetPackageDocument'])
        invalid['installScript'] = 'untrusted'
        with self.assertRaises(Exception):
            sign_release(json.dumps(invalid).encode(), key, 'offline-release')
        with self.assertRaises(ValueError):
            sign_release(b'x' * 32769, key, 'offline-release')
        with self.assertRaises(ValueError):
            sign_release(b'{"formatVersion":1,"formatVersion":1}', key, 'offline-release')

    def test_builder_has_one_source_and_package_download_and_no_device_test_route(self):
        api = yaml.safe_load((ROOT / 'packages/contracts/openapi/control-v1.yaml').read_text())
        paths = {path: methods for path, methods in api['paths'].items() if '/builder/' in path}
        self.assertEqual(set(paths), {'/api/control/v1/builder/status', '/api/control/v1/builder/drafts', '/api/control/v1/builder/builds',
            '/api/control/v1/builder/builds/{id}/package', '/api/control/v1/builder/builds/{id}/source'})
        count = 0
        for path, methods in paths.items():
            for operation in methods.values():
                count += 1
                self.assertEqual(operation['security'], [{'adminAccessToken': []}])
                if path.endswith(('/package', '/source')):
                    content = operation['responses']['200']['content']
                    self.assertEqual(next(iter(content.values()))['schema'], {'type': 'string', 'format': 'binary'})
                else:
                    self.assertIn('sdk-builder.schema.json', json.dumps(operation))
        self.assertEqual(count, 7)

    def test_unbuilt_source_is_not_a_runtime_registration(self):
        schema = json.loads((ROOT / 'packages/contracts/schemas/v1/execution.schema.json').read_text())
        self.assertNotIn('source', schema['$defs']['LocalToolRegistration']['properties'])
        names = {name for path in (ROOT / 'packages/contracts/schemas/v1').glob('*.json') for name in json.loads(path.read_text())['$defs']}
        self.assertFalse(names.intersection({'LocalToolSource', 'BuilderDefinition', 'BuilderTestTask'}))


if __name__ == '__main__':
    unittest.main()
