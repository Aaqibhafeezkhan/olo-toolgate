# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Canonical bundle boundaries, frozen compatibility and external key deployment gates."""
import base64
import copy
import json
import unittest
from pathlib import Path
import yaml
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from jsonschema import Draft7Validator, ValidationError
from test_foundation import schemas_at, validators

ROOT=Path(__file__).resolve().parents[2]


class PolicyContractsTests(unittest.TestCase):
    def test_public_vectors_verify_and_canonical_version_is_compatible(self):
        corpus=json.loads((ROOT/'tests/fixtures/policy/signed-v1.json').read_text())
        models=validators(schemas_at(ROOT/'tests/fixtures/contracts/pre-enterprise'))
        keyring={item['keyId']:item for item in corpus['keyring']['keys']}
        def decode(value):return base64.urlsafe_b64decode(value+'==')
        for name in ('allow','strict','many','block','rollback','rotated','precedence'):
            signed=corpus['bundles'][name];models['SignedPolicyBundle'].validate(signed)
            parts=signed['jws'].split('.');header=json.loads(decode(parts[0]));models['BundleHeader'].validate(header)
            key=keyring[header['kid']];public=rsa.RSAPublicNumbers(65537,int.from_bytes(decode(key['modulus']),'big')).public_key()
            public.verify(decode(parts[2]),(parts[0]+'.'+parts[1]).encode(),padding.PKCS1v15(),hashes.SHA256())
            payload=json.loads(decode(parts[1]));models['BundlePayload'].validate(payload)
            models['CompiledPolicy'].validate(json.loads(decode(payload['policy'])))
        # The original closed v1 contracts still accept the frozen foundation corpus.
        frozen=json.loads((ROOT/'tests/fixtures/contracts/v1/compatibility.json').read_text())
        archival=validators(schemas_at(ROOT/'tests/fixtures/contracts/v1/schemas'))
        for model,fixture in frozen['fixtures'].items():archival[model].validate(fixture)
        with self.assertRaises(ValidationError):validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))['EnterpriseSnapshotPayload'].validate(json.loads(decode(corpus['bundles']['allow']['jws'].split('.')[1])))
    def test_trust_domains_and_algorithms_are_not_interchangeable(self):
        models=validators(schemas_at(ROOT/'tests/fixtures/contracts/pre-enterprise'))
        fixtures=json.loads((ROOT/'tests/fixtures/contracts/pre-enterprise/valid.json').read_text())
        for name in ('MarketplaceRelease','DeploymentAssignment','PolicyDecision','RequestContext'):
            with self.assertRaises(ValidationError):models['SignedPolicyBundle'].validate(fixtures[name])
        for field,value in [('alg','none'),('typ','JWT'),('kid',''),('jku','https://evil.invalid/keys')]:
            with self.assertRaises(ValidationError):models['BundleHeader'].validate({**fixtures['BundleHeader'],field:value})
    def test_external_keys_required_and_raw_secrets_forbidden_in_values(self):
        path=ROOT/'deploy/helm/olo-toolgate';values=yaml.safe_load((path/'values.yaml').read_text());schema=json.loads((path/'values.schema.json').read_text())
        for component,secret in [('control','signingSecret')]:
            bad=copy.deepcopy(values);bad[component]['bundle']['enabled']=True
            with self.assertRaises(ValidationError):Draft7Validator(schema).validate(bad)
        bad=copy.deepcopy(values);bad['control']['bundle']['privateKey']='forbidden'
        with self.assertRaises(ValidationError):Draft7Validator(schema).validate(bad)
        bad=copy.deepcopy(values);bad['gateway']['bundle']={'enabled':True,'maxGraceMs':300001}
        with self.assertRaises(ValidationError):Draft7Validator(schema).validate(bad)
    def test_openapi_publish_and_rollback_reuse_shared_contracts(self):
        api=yaml.safe_load((ROOT/'packages/contracts/openapi/control-v1.yaml').read_text())
        for path in ('publish','rollback'):
            operation=api['paths']['/api/control/v1/bundles/'+path]['post']
            self.assertTrue(operation['requestBody']['content']['application/json']['schema']['$ref'].endswith('/BundlePublishRequest'))
            self.assertTrue(operation['responses']['201']['content']['application/json']['schema']['$ref'].endswith('/SignedPolicyBundle'))
            self.assertEqual('Idempotency-Key',operation['parameters'][0]['name'])

    def test_bundle_documentation_and_release_gate(self):
        import re
        for relative in ('docs/control-plane/policy-bundles.md','docs/adr/005-signed-policy-bundles.md'):
            file=ROOT/relative
            for target in re.findall(r'\]\(([^)]+)\)',file.read_text()):
                if not target.startswith(('http:','https:','#')):self.assertTrue((file.parent/target.split('#')[0]).exists(),target)
        for name in ('control','gateway'):
            workflow=yaml.safe_load((ROOT/f'.github/workflows/{name}.yml').read_text())
            self.assertEqual('./.github/workflows/policy.yml',workflow['jobs']['policy-compatibility']['uses'])
            self.assertIn('policy-compatibility',workflow['jobs']['container-and-replicas']['needs'])
        pipeline=yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text())
        self.assertNotIn('release',pipeline['jobs'])
        # Canonical policy/custody checks remain in the shared foundation owner;
        # production delegated effects and restore proof remain in images.
        foundation=(ROOT/'tools/check.py').read_text()
        images=(ROOT/'.github/workflows/images.yml').read_text()
        self.assertIn('tools/enterprise/check.py',foundation)
        for gate in ('tools/gateway/container.py','tools/enterprise/restore_check.py'):
            self.assertIn(gate,images)


if __name__=='__main__':unittest.main()
