# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Public interoperability vectors are discovery metadata, never JWT access credentials."""
import base64
import json
from pathlib import Path
import unittest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ROOT=Path(__file__).resolve().parents[2]


def decode(value): return base64.urlsafe_b64decode(value+'='*(-len(value)%4))


class TenantDiscoveryContracts(unittest.TestCase):
    def test_the_narrow_public_jws_fixture_exception_contains_no_credentials_or_private_parameters(self):
        vectors=json.loads((ROOT/'tests/fixtures/tenant-discovery/v2.json').read_text())
        schema=json.loads((ROOT/'packages/contracts/schemas/v2/discovery.schema.json').read_text())['$defs']
        self.assertEqual(set(vectors['cases']), {'initial','announced','rotated','reordered','retired','unannounced',
            'unversioned','revokedSigner','otherTenant','wrongPurpose','wrongAlgorithm','remoteKeyHeader',
            'unknownKey','wrongVersion','aliasTenant','sharedCaKey','splitOrigins'})
        for name,envelope in vectors['cases'].items():
            with self.subTest(case=name):
                self.assertEqual({'jws'},set(envelope))
                h,p,s=envelope['jws'].split('.')
                header,payload=json.loads(decode(h)),json.loads(decode(p))
                self.assertEqual(set(header), {'alg','typ','kid','jku'} if name=='remoteKeyHeader' else {'alg','typ','kid'})
                self.assertEqual(set(schema['TenantDiscovery']['properties']),set(payload))
                for key in payload['discoveryKeys']['keys']:
                    self.assertEqual({'kty','use','alg','kid','n','e'},set(key))
                for authority in payload['deviceAuthorities']:
                    self.assertEqual({'keyId','state','certificatePem'},set(authority))
                    self.assertNotIn('PRIVATE KEY',authority['certificatePem'])
                if name in {'initial','announced','rotated','reordered','retired','otherTenant','splitOrigins'}:
                    key=next(key for key in payload['discoveryKeys']['keys'] if key['kid']==header['kid'])
                    public=rsa.RSAPublicNumbers(int.from_bytes(decode(key['e']),'big'),int.from_bytes(decode(key['n']),'big')).public_key()
                    public.verify(decode(s),(h+'.'+p).encode(),padding.PKCS1v15(),hashes.SHA256())


if __name__=='__main__': unittest.main()
