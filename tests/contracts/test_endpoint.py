# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Endpoint trust boundaries remain closed and release targets explicit."""
import copy
import json
import unittest
from pathlib import Path
import yaml
from jsonschema import Draft7Validator, ValidationError
from test_foundation import schemas_at, validators
ROOT = Path(__file__).resolve().parents[2]

class EndpointContractsTests(unittest.TestCase):
    def test_deployment_view_is_explicit_and_does_not_extend_default_closed_device_page(self):
        models=validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures=json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        original=dict(items=[fixtures['EndpointManagedDevice']])
        models['EndpointManagedDevicePage'].validate(original)
        models['EndpointManagedDeviceDeploymentPage'].validate(original)
        enriched={**original,'deployments':[fixtures['DeviceDeploymentRecord']]}
        models['EndpointManagedDeviceDeploymentPage'].validate(enriched)
        with self.assertRaises(ValidationError):models['EndpointManagedDevicePage'].validate(enriched)
        for field,value in [('containerId','short-id'),('networkId','b'*65),('executionKind','ADMINISTRATOR'),('ipAddress','not-an-ip')]:
            sample=copy.deepcopy(fixtures['DeviceDeploymentMetadata']);sample[field]=value
            with self.subTest(field=field),self.assertRaises(ValidationError):models['DeviceDeploymentMetadata'].validate(sample)
    def test_response_inspection_excludes_private_lease_and_metadata_is_bounded(self):
        models=validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures=json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        inspection=copy.deepcopy(fixtures['RemoteToolInspection']);models['RemoteToolInspection'].validate(inspection)
        inspection['leaseId']='private-lease'
        with self.assertRaises(ValidationError): models['RemoteToolInspection'].validate(inspection)
        for field,value in [('systemName','a'*256),('systemName','line\nbreak'),('ipAddress','example.test'),('ipAddress','1'*46)]:
            device=copy.deepcopy(fixtures['EndpointManagedDevice']);device[field]=value
            with self.assertRaises(ValidationError): models['EndpointManagedDevice'].validate(device)

    def test_management_snapshots_and_explicit_unlimited_approval_are_bounded(self):
        models = validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        page = {'items': [fixtures['EndpointManagedDevice']] * 544}
        models['EndpointManagedDevicePage'].validate(page)
        page['items'].append(fixtures['EndpointManagedDevice'])
        with self.assertRaises(ValidationError): models['EndpointManagedDevicePage'].validate(page)
        for name, field, invalid in [('EndpointApprovalRequest','expectedApprovalRevision',0),('EndpointEnabledRequest','expectedRevision',-1),('EndpointEnrollmentDecision','unlimitedConnection','true'),('EndpointManagedDevice','systemName','bad name')]:
            sample=copy.deepcopy(fixtures[name]); sample[field]=invalid
            with self.assertRaises(ValidationError): models[name].validate(sample)
        models['EndpointEnabledRequest'].validate({'expectedRevision':0,'enabled':False})

    def test_pending_enrollment_page_and_optional_connection_deadlines_are_bounded(self):
        models = validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        page = {'items': [fixtures['EndpointEnrollmentReview']] * 32}
        models['EndpointEnrollmentPage'].validate(page)
        page['items'].append(fixtures['EndpointEnrollmentReview'])
        with self.assertRaises(ValidationError): models['EndpointEnrollmentPage'].validate(page)
        for name in ('EndpointEnrollmentDecision', 'EndpointEnrollmentReview', 'EndpointDeviceRecord'):
            sample = copy.deepcopy(fixtures[name])
            models[name].validate(sample)  # Existing records and older API callers remain readable.
            sample['connectionExpiresAtUnixMs'] = 1900000000000
            models[name].validate(sample)
            for invalid in (-1, 9007199254740992, 1.5, 'forever'):
                sample['connectionExpiresAtUnixMs'] = invalid
                with self.assertRaises(ValidationError): models[name].validate(sample)

    def test_job_socket_rejects_unknown_operations_and_unstructured_frames(self):
        models = validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        sample = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())['ClientSocketRequest']
        for field, invalid in (('operation','EXECUTE'),('requestId','unbound'),('body','command string')):
            changed = copy.deepcopy(sample); changed[field] = invalid
            with self.assertRaises(ValidationError): models['ClientSocketRequest'].validate(changed)

    def test_check_in_interval_supports_milliseconds_and_legacy_seconds(self):
        models = validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        sample = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())['EndpointCheckInAck']
        models['EndpointCheckInAck'].validate(sample)
        sample['nextIntervalMs'] = 500
        models['EndpointCheckInAck'].validate(sample)
        for invalid in (499, 500.5, 3600001):
            sample['nextIntervalMs'] = invalid
            with self.assertRaises(ValidationError): models['EndpointCheckInAck'].validate(sample)

    def test_private_device_code_cannot_enter_public_prompt(self):
        models = validators(schemas_at(ROOT/'packages/contracts/schemas/v1'))
        fixtures = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())
        for name in ('EndpointEnrollmentPrompt','EndpointEnrollmentDecision','ClientIpcRequest','DeviceIdentity'):
            sample = copy.deepcopy(fixtures[name]); sample['deviceCode'] = 'a'*64
            with self.assertRaises(ValidationError): models[name].validate(sample)
        sample = copy.deepcopy(fixtures['ClientIpcRequest']); sample['operation'] = 'EXPORT_KEY'
        with self.assertRaises(ValidationError): models['ClientIpcRequest'].validate(sample)

    def test_endpoint_chart_requires_external_tls_and_ca(self):
        schema = json.loads((ROOT/'deploy/helm/olo-toolgate/values.schema.json').read_text())
        values = yaml.safe_load((ROOT/'deploy/helm/olo-toolgate/values.yaml').read_text())
        Draft7Validator(schema).validate(values)
        values['control']['endpoint']['enabled'] = True
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)
        settings = values['control']['endpoint']
        settings.update(serverId='server',organization='Organization',deviceCaSecret='device-ca',
                        tlsSecret='server-tls',trustStoreSecret='device-trust',controlUrl='https://control.example.test',gatewayUrl='https://gateway.example.test')
        Draft7Validator(schema).validate(values)
        settings['controlUrl'] = 'http://control.example.test'
        with self.assertRaises(ValidationError): Draft7Validator(schema).validate(values)

    def test_native_matrix_and_packaging_targets_cover_six_platforms(self):
        workflow = yaml.safe_load((ROOT/'.github/workflows/client.yml').read_text())
        matrix = workflow['jobs']['native']['strategy']['matrix']['include']
        self.assertEqual(6,len(matrix)); self.assertEqual(6,len({row['target'] for row in matrix}))
        self.assertTrue(all('arm' in row['runner'] or row['target'].startswith('x86_64') or row['runner']=='macos-15' for row in matrix))
        from importlib.util import spec_from_file_location, module_from_spec
        spec = spec_from_file_location('client_package',ROOT/'tools/client/package.py'); package = module_from_spec(spec); spec.loader.exec_module(package)
        self.assertEqual(package.TARGETS,{row['target'] for row in matrix})
