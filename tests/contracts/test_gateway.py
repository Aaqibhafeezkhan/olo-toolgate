# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Gateway contract, deployment/CI and documentation smoke checks."""
import json
import re
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, ValidationError

ROOT=Path(__file__).resolve().parents[2]


class GatewayAssetsTests(unittest.TestCase):
    def test_openapi_reuses_canonical_models_and_product_version(self):
        api=yaml.safe_load((ROOT/'packages/contracts/openapi/gateway-v1.yaml').read_text())
        self.assertEqual('3.1.0',api['openapi'])
        self.assertEqual((ROOT/'VERSION').read_text().strip(),api['info']['version'])
        ref=api['paths']['/access/invocations']['post']['requestBody']['content']['application/json']['schema']['$ref']
        self.assertTrue(ref.endswith('runtime.schema.json#/$defs/AuthorizationRequest'))
        self.assertEqual('bearer',api['components']['securitySchemes']['runtimeBearer']['scheme'])
        self.assertNotIn('AuthorizationRequest',api['components'].get('schemas',{}))

    def test_enabled_chart_values_require_credentials_and_reject_weakened_security(self):
        chart=ROOT/'deploy/helm/olo-toolgate'; schema=json.loads((chart/'values.schema.json').read_text()); values=yaml.safe_load((chart/'values.yaml').read_text())
        values['gateway']['enabled']=True
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)
        values['gateway']['credentialsSecret']='gateway-runtime'
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)
        values['gateway']['control']['tokenSecret']='gateway-authority'
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)
        values['gateway']['control']['tenantId']='10000000-0000-4000-8000-000000000001'
        values['gateway']['config']['tenantHosts']=['gateway.example.com']
        values['gateway']['networkPolicy'].update(controlTo=[{'podSelector':{'matchLabels':{'app':'control'}}}],dnsTo=[{'namespaceSelector':{'matchLabels':{'kubernetes.io/metadata.name':'kube-system'}}}])
        Draft7Validator(schema).validate(values)
        values['gateway']['securityContext']['allowPrivilegeEscalation']=True
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)

    def test_production_image_and_cluster_workflow_have_required_gates(self):
        docker=(ROOT/'apps/gateway/Dockerfile').read_text()
        self.assertEqual(2,len(re.findall(r'^FROM .*@sha256:[a-f0-9]{64}',docker,re.M)))
        for required in ['USER 65532:65532','STOPSIGNAL SIGTERM','--locked','org.opencontainers.image.version']: self.assertIn(required,docker)
        workflow=yaml.load((ROOT/'.github/workflows/gateway.yml').read_text(),Loader=yaml.BaseLoader)
        self.assertEqual('read',workflow['permissions']['contents'])
        commands='\n'.join(s.get('run','') for s in workflow['jobs']['container-and-replicas']['steps'])
        for required in ['cargo test --workspace --locked','tools/gateway/container.py','tools/enterprise/helm.py','--severity HIGH,CRITICAL','--format cyclonedx','sha256sum']: self.assertIn(required,commands)
        self.assertNotIn('publish',workflow['jobs'])
        publisher=yaml.load((ROOT/'.github/workflows/release-images.yml').read_text(),Loader=yaml.BaseLoader)
        self.assertEqual('development-release',publisher['jobs']['publish']['environment'])
        attest=[step for step in publisher['jobs']['publish']['steps'] if step.get('name')=='Attest Gateway image']
        self.assertEqual('true',attest[0]['with']['push-to-registry'])
        self.assertNotIn('secrets.MAVEN',json.dumps(workflow))

    def test_gateway_local_documentation_links(self):
        paths=['apps/gateway/README.md','docs/gateway/README.md','docs/gateway/configuration.md','docs/gateway/deployment.md','docs/gateway/upgrades.md','docs/gateway/performance.md','docs/adr/002-gateway-static-foundation.md']
        for name in paths:
            path=ROOT/name; text=path.read_text(encoding='utf-8')
            for target in re.findall(r'\]\(([^)]+)\)',text):
                if '://' in target or target.startswith('#'): continue
                self.assertTrue((path.parent/target.split('#')[0]).is_file(),f'{path}: {target}')


if __name__=='__main__': unittest.main()
