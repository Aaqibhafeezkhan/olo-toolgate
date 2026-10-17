# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Reject policy injection and ambiguous Gateway destinations before device startup."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('secured_network', ROOT/'apps/device/secured_network.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SecuredDeviceTests(unittest.TestCase):
    def setUp(self):
        self.env = dict(TOOLGATE_GATEWAY_URLS='https://localhost:18450',
                        TOOLGATE_SECURED_GATEWAY_IPV4='172.20.0.2', TOOLGATE_GATEWAY_CA_FILE=str(ROOT/'gateway.crt'))

    def parse(self, **overrides):
        with patch.object(Path, 'is_file', return_value=True), patch.object(module.ssl, 'create_default_context'):
            return module.settings({**self.env, **overrides})

    def test_ambiguous_or_untrusted_gateway_origins_are_rejected(self):
        for value in ('', 'https://one,https://two', 'http://gateway', 'https://u:p@gateway',
                      'https://gateway/path', 'https://gateway?x=1', 'https://gateway#x',
                      'https://[::1]:443', 'https://gateway:0', 'https://gateway:65536',
                      'https://gateway;accept'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse(TOOLGATE_GATEWAY_URLS=value)

    def test_firewall_destination_cannot_be_a_network_or_rule_fragment(self):
        for value in ('0.0.0.0', '127.0.0.1', '169.254.169.254', '224.0.0.1',
                      '255.255.255.255', '::1', '172.20.0.0/16', '172.20.0.2; accept'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse(TOOLGATE_SECURED_GATEWAY_IPV4=value)

    def test_port_and_console_cannot_widen_the_allowlist(self):
        for value in ('0', '65536', '8443; accept', '443,8080'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.parse(TOOLGATE_SECURED_GATEWAY_PORT=value)
        with self.assertRaises(ValueError):
            self.parse(TOOLGATE_GATEWAY_CONSOLE_URLS='http://localhost:8080')

    def test_missing_or_invalid_certificate_fails_before_firewall_and_service(self):
        with patch.object(Path, 'is_file', return_value=False), self.assertRaises(ValueError):
            module.settings(self.env)
        with patch.object(Path, 'is_file', return_value=True), \
                patch.object(module.ssl, 'create_default_context', side_effect=ValueError('bad CA')), \
                self.assertRaisesRegex(ValueError, 'bad CA'):
            module.settings(self.env)

    def test_single_gateway_and_distinct_advertised_tls_port_are_preserved(self):
        config = self.parse(TOOLGATE_GATEWAY_URLS='https://gateway.example:18451/')
        self.assertEqual((config['origin'], config['port'], config['upstream_port'], config['address']),
                         ('https://gateway.example:18451', 18451, 8443, '172.20.0.2'))


execution_spec = importlib.util.spec_from_file_location('device_execution', ROOT/'apps/device/device_execution.py')
execution = importlib.util.module_from_spec(execution_spec)
execution_spec.loader.exec_module(execution)


class DeviceExecutionTests(unittest.TestCase):
    """The mounted file supplies only execution and release trust; identity and Gateway settings are never taken from it."""

    def write(self, directory, value):
        path = Path(directory)/'execution.json'
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding='utf-8')
        return {execution.VARIABLE: str(path)}

    def test_without_the_variable_the_configuration_is_kept(self):
        saved = {'serverUrl': 'https://localhost:18451', 'execution': {'stateDirectory': '/var/lib/olo-toolgate/runtimes'}}
        self.assertIsNone(execution.load({}))
        self.assertEqual((saved, False), execution.merge(saved, None))

    def test_mounted_sections_replace_the_devices_own_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as directory:
            mounted = {'execution': {'stateDirectory': '/var/lib/olo-toolgate/runtimes', 'files': {'root': '/var/lib/olo-toolgate/hotfolder'}},
                       'deployment': {'releaseKeys': [], 'organizationKeys': []}}
            loaded = execution.load(self.write(directory, mounted))
        saved = {'serverUrl': 'https://localhost:18451', 'stateDirectory': '/var/lib/olo-toolgate', 'gateway': {'deviceId': 'kept'}}
        merged, changed = execution.merge(saved, loaded)
        self.assertTrue(changed)
        self.assertEqual('https://localhost:18451', merged['serverUrl'])
        self.assertEqual({'deviceId': 'kept'}, merged['gateway'])
        self.assertEqual(mounted['execution'], merged['execution'])
        self.assertEqual((merged, False), execution.merge(merged, loaded), 'a repeated start changes nothing')
        # Dropping the deployment section from the file removes release trust from the device.
        without = execution.merge(merged, {'execution': mounted['execution']})[0]
        self.assertNotIn('deployment', without)

    def test_identity_or_unknown_sections_and_wrong_state_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            for value in ({'execution': {'stateDirectory': '/var/lib/olo-toolgate/runtimes'}, 'serverUrl': 'https://evil.example'},
                          {'deployment': {}},
                          {'execution': {'stateDirectory': '/tmp/runtimes'}},
                          '[]'):
                with self.assertRaises(ValueError, msg=str(value)):
                    execution.load(self.write(directory, value))
        with self.assertRaises(ValueError):
            execution.load({execution.VARIABLE: 'relative/execution.json'})
