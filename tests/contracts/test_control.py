# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Control canonical API, deployment defaults, examples and CI wiring smoke gates."""
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, ValidationError

ROOT=Path(__file__).resolve().parents[2]


class ControlAssetsTests(unittest.TestCase):
    def test_canonical_api_is_versioned_and_reuses_shared_schemas(self):
        api=yaml.safe_load((ROOT/'packages/contracts/openapi/control-v1.yaml').read_text())
        self.assertEqual((ROOT/'VERSION').read_text().strip(),api['info']['version'])
        self.assertEqual([{'adminAccessToken':[]}],api['security'])
        for kind,model in [('users','ControlUser'),('teams','ControlTeam'),('agents','ControlAgent'),('tools','ControlTool'),('policies','ControlPolicy'),('devices','ControlDevice')]:
            path='/api/control/v1/'+kind
            self.assertIn('post',api['paths'][path]);self.assertIn('put',api['paths'][path+'/{id}'])
            ref=api['paths'][path]['post']['requestBody']['content']['application/json']['schema']['$ref']
            self.assertEqual('../schemas/v1/control.schema.json#/$defs/'+model,ref)
        self.assertNotIn('schemas',api['components'])
        self.assertIn('/api/control/v1/openapi',api['paths'])
    def test_release_assets_include_control_contracts_and_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run([sys.executable,str(ROOT/'tools/release/bundle.py'),'--output',folder],check=True,capture_output=True)
            output=Path(folder);version=(ROOT/'VERSION').read_text().strip()
            metadata=json.loads((output/'compatibility.json').read_text())
            self.assertEqual(version,metadata['runtimeComponents']['control'])
            self.assertEqual(13,metadata['controlDatabase']['flywaySchema'])
            self.assertEqual(6,metadata['controlDatabase']['sqliteSchema'])
            self.assertEqual([3],metadata['policyBundle']['supportedFormatVersions'])
            self.assertEqual('ghcr.io/olo-labs/olo-toolgate-control:'+version,metadata['controlImage'])
            contracts=(ROOT/'packages/contracts/VERSION').read_text().strip()
            self.assertEqual(contracts,metadata['contractsVersion'])
            with tarfile.open(output/('olo-toolgate-contracts-'+contracts+'.tar.gz')) as archive:
                self.assertIn('schemas/v1/control.schema.json',archive.getnames())
                self.assertIn('openapi/control-v1.yaml',archive.getnames())
            self.assertIn('compatibility.json',(output/'SHA256SUMS').read_text())
    def test_production_chart_requires_external_secrets_and_database_tls(self):
        folder=ROOT/'deploy/helm/olo-toolgate';values=yaml.safe_load((folder/'values.yaml').read_text());schema=json.loads((folder/'values.schema.json').read_text())
        self.assertFalse(values['control']['enabled']);values['control']['enabled']=True
        with self.assertRaises(ValidationError):Draft7Validator(schema).validate(values)
        values['control']['publicKeySecret']='identity';values['control']['database'].update(credentialsSecret='db',caSecret='db-ca');values['control']['effect']['signingSecret']='effect-custody'
        Draft7Validator(schema).validate(values)
        values['control']['database']['sslMode']='disable'
        with self.assertRaises(ValidationError):Draft7Validator(schema).validate(values)
    def test_container_workflow_tests_scans_and_publishes_exact_image(self):
        workflow=yaml.safe_load((ROOT/'.github/workflows/control.yml').read_text());text=(ROOT/'.github/workflows/control.yml').read_text()
        for command in ('tools/control/check.py','tools/control/helm.py','tools/control/container.py','tools/enterprise/helm.py','--severity HIGH,CRITICAL','--format cyclonedx','docker save','docker load'):
            self.assertIn(command,text)
        self.assertEqual('control-release',workflow['jobs']['publish']['environment'])
        dockerfile=(ROOT/'apps/control-plane/Dockerfile').read_text()
        self.assertIn('USER 65532:65532',dockerfile);self.assertEqual(2,dockerfile.count('FROM eclipse-temurin:'))
        self.assertNotIn('PRIVATE KEY',dockerfile)
    def test_control_docs_links_and_example_are_valid(self):
        from test_foundation import validators,schemas_at
        example=json.loads((ROOT/'docs/examples/control-import.json').read_text())
        validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))['ControlImportRequest'].validate(example)
        for relative in ('apps/control-plane/README.md','docs/control-plane/README.md','docs/control-plane/configuration.md','docs/control-plane/upgrades.md','docs/api/control-plane-api.md','docs/adr/003-control-plane-transactions.md'):
            path=ROOT/relative
            for link in re.findall(r'\]\(([^)]+)\)',path.read_text()):
                if '://' not in link and not link.startswith('#'):
                    self.assertTrue((path.parent/link.split('#')[0]).exists(),(relative,link))


if __name__=='__main__':unittest.main()
