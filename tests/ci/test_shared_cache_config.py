# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""External cache deployment contracts, without a live cluster or credentials."""
from pathlib import Path
import copy
import json
import unittest
import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'TOOLGATE_CONTROL_CACHE_'


class SharedCacheConfigurationTests(unittest.TestCase):
    def test_quickstart_forwards_all_cache_settings_with_local_default(self):
        properties = (ROOT/'apps/control-plane/src/main/resources/application.properties').read_text()
        for folder in ('QuickStart', 'QuickStart-WO-Password'):
            path = ROOT/'deploy/compose'/folder
            environment = yaml.safe_load((path/'compose.yaml').read_text())['services']['quickstart']['environment']
            self.assertEqual(environment[PREFIX+'MODE'], '${TOOLGATE_CONTROL_CACHE_MODE:-local}')
            for name in ('HOST', 'PORT', 'TLS', 'USERNAME', 'NAMESPACE', 'PASSWORD_PATH',
                         'ENCRYPTION_KEY_PATH', 'CA_PATH', 'TIMEOUT_MS', 'TTL_SECONDS'):
                self.assertTrue(environment[PREFIX+name].startswith('${'+PREFIX+name+':-'))
                self.assertIn(PREFIX+name, properties)
                self.assertIn(PREFIX+name+'=', (path/'.env.example').read_text())

    def test_production_compose_loads_optional_file_and_reuses_protected_mount(self):
        folder = ROOT/'deploy/compose/GatewayControl'
        control = yaml.safe_load((folder/'compose.yaml').read_text())['services']['control']
        self.assertIn({'path':'./env/.env.cache', 'format':'raw', 'required':False}, control['env_file'])
        self.assertIn('./custody/control:/custody:ro', control['volumes'])
        sample = (folder/'env/.env.cache.example').read_text()
        self.assertIn(PREFIX+'MODE=redis', sample)
        self.assertIn(PREFIX+'TLS=true', sample)
        self.assertIn(PREFIX+'PASSWORD_PATH=/custody/cache-password', sample)

    def test_helm_schema_accepts_optional_cache_and_rejects_unsafe_settings(self):
        chart = ROOT/'deploy/helm/olo-toolgate'
        defaults = yaml.safe_load((chart/'values.yaml').read_text())
        schema = json.loads((chart/'values.schema.json').read_text())
        validator = jsonschema.Draft7Validator(schema)
        validator.validate(defaults)
        self.assertEqual(defaults['control']['cache']['mode'], 'local')
        enabled = copy.deepcopy(defaults)
        enabled['control']['cache'].update(mode='redis', host='cache.example.com', namespace='production', credentialsSecret='cache-custody')
        validator.validate(enabled)
        for name, value in (('mode', 'unknown'), ('host', ''), ('host', 'redis://user:secret@host'),
                            ('namespace', ''), ('namespace', 'invalid space'), ('credentialsSecret', ''),
                            ('tls', False), ('password', 'inline-secret'), ('port', 0),
                            ('timeoutMs', 49), ('timeoutMs', 2001), ('ttlSeconds', 0), ('ttlSeconds', 3601)):
            with self.subTest(name=name, value=value):
                invalid = copy.deepcopy(enabled)
                invalid['control']['cache'][name] = value
                with self.assertRaises(jsonschema.ValidationError):
                    validator.validate(invalid)


if __name__ == '__main__':
    unittest.main()
