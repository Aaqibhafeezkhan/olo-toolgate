# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Gateway selection must retry availability without moving a protected enrollment."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('device_entrypoint', ROOT/'debug/device-entrypoint.py')
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)
spec = importlib.util.spec_from_file_location('container_device', ROOT/'apps/device/entrypoint.py')
module = importlib.util.module_from_spec(spec)
with patch.dict(sys.modules, {'device_entrypoint': device}):
    spec.loader.exec_module(module)


class DeviceImageTests(unittest.TestCase):
    def setUp(self):
        device.STOP.clear()
        self.addCleanup(device.STOP.clear)

    def test_busy_health_during_package_activation_marks_unhealthy_without_killing_service(self):
        service=Mock();service.poll.return_value=None
        health=json.dumps({'state':'ACTIVE'})
        with tempfile.TemporaryDirectory() as folder:
            status=Path(folder)/'device-status.json';status.write_text('stale health')
            waits=[]
            def wait(seconds):
                waits.append(seconds)
                if len(waits)==1:
                    self.assertFalse(status.exists())
                    service.terminate.assert_not_called()
                else:
                    device.STOP.set()
            with patch.object(device,'STATUS',status),patch.object(device.subprocess,'Popen',return_value=service), \
                    patch.object(device,'cli',side_effect=[health,RuntimeError('busy'),health]) as cli, \
                    patch.object(device,'publish') as publish,patch.object(device.STOP,'wait',side_effect=wait):
                device.supervise()
            self.assertEqual(cli.call_count,3)
            publish.assert_called_once_with({'state':'ACTIVE'},None)
            service.terminate.assert_called_once()

    def test_gateway_origins_normalize_and_deduplicate(self):
        self.assertEqual(module.gateway_urls(' https://ONE.example:443/, https://two.example:8443,https://one.example '),
                         ['https://one.example', 'https://two.example:8443'])
        self.assertEqual(module.gateway_urls('https://[::1]:8443'), ['https://[::1]:8443'])
        self.assertEqual(module.gateway_urls('container_name_1:8443, container_name_2:8443'),
                         ['https://container_name_1:8443', 'https://container_name_2:8443'])

    def test_docker_defaults_discover_only_the_corresponding_local_console(self):
        urls = module.gateway_urls('https://host.docker.internal:18450,https://host.docker.internal:18451')
        self.assertEqual(module.console_urls(urls), dict(zip(urls, [
            'http://host.docker.internal:18090', 'http://host.docker.internal:18091'])))
        aliases = module.gateway_urls('container_name_1:8443,container_name_2:8443')
        self.assertEqual(module.console_urls(aliases)[aliases[1]], 'http://container_name_2:8080')
        remote = module.gateway_urls('https://gateway.example.com:8443')
        self.assertEqual(module.console_urls(remote), {remote[0]: ''})
        self.assertEqual(module.console_urls(aliases, ca='provided-ca.crt'), dict.fromkeys(aliases, ''))

    def test_console_overrides_cannot_bootstrap_from_an_unrelated_host(self):
        urls = module.gateway_urls('container_name_1:8443,container_name_2:8443')
        for consoles in ('http://other:8080,http://container_name_2:8080',
                         'http://container_name_1:8080',
                         'http://container_name_1:8080/private,http://container_name_2:8080'):
            with self.subTest(consoles=consoles), self.assertRaises(ValueError):
                module.console_urls(urls, consoles)

    def test_docker_alias_selection_verifies_the_advertised_tls_gateway(self):
        urls = module.gateway_urls('missing:8443,container_name_2:8443')
        consoles = module.console_urls(urls)
        with patch.object(module, 'ready_local', side_effect=[False, True]) as ready, \
                patch.object(module, 'ready') as direct, patch.object(device, 'publish'):
            self.assertEqual(module.select_gateway(urls, Mock(), consoles=consoles), urls[1])
        self.assertEqual(ready.call_args_list[1].args, (urls[1], 'http://container_name_2:8080'))
        direct.assert_not_called()

    def test_invalid_destinations_fail_before_installation(self):
        for url in ('', 'http://gateway.example', 'https://user:password@gateway.example',
                    'https://gateway.example/mcp', 'https://gateway.example?token=x',
                    'https://gateway.example#fragment', 'https://gateway.example:bad',
                    'https://metadata.google.internal', 'https://0.0.0.0', 'https://gateway.example,'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                module.gateway_urls(url)

    def test_offline_urls_are_retried_until_a_later_gateway_is_available(self):
        urls = ['https://one.example', 'https://two.example']
        opener = Mock()
        with patch.object(module, 'ready', side_effect=[False, False, False, True]) as ready, \
                patch.object(device, 'publish') as publish, patch.object(device.STOP, 'wait') as wait:
            self.assertEqual(module.select_gateway(urls, opener), urls[1])
        self.assertEqual([call.args[1] for call in ready.call_args_list], urls+urls)
        self.assertEqual(publish.call_count, 2)
        wait.assert_called_once_with(5)

    def test_existing_enrollment_keeps_its_gateway_during_an_outage(self):
        with patch.object(module, 'ready') as ready:
            self.assertEqual(module.select_gateway(['https://new.example', 'https://saved.example'],
                                                  Mock(), 'https://saved.example'), 'https://saved.example')
            ready.assert_not_called()
            with self.assertRaisesRegex(ValueError, 'separate device volumes'):
                module.select_gateway(['https://new.example'], Mock(), 'https://saved.example')

    def test_shutdown_interrupts_gateway_wait(self):
        with patch.object(module, 'ready', return_value=False), patch.object(device, 'publish'), \
                patch.object(device.STOP, 'wait', side_effect=lambda _: device.STOP.set()):
            self.assertIsNone(module.select_gateway(['https://gateway.example'], Mock()))

    def test_readiness_accepts_only_the_v2_signed_discovery_envelope(self):
        def opener(document):
            response = Mock(status=200)
            response.read.return_value = json.dumps(document).encode()
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            return Mock(open=Mock(return_value=response))
        self.assertTrue(module.ready(opener({'jws': 'header.payload.signature'}), 'https://gateway.example'))
        for document in ({'signature': 'v1'}, {'jws': 'not-compact'}, {'jws': 1}, ['jws']):
            with self.subTest(document=document):
                self.assertFalse(module.ready(opener(document), 'https://gateway.example'))

    def test_redirects_are_not_followed(self):
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))

    def test_transient_enrollment_failure_retries_but_denial_stops_requests(self):
        health = lambda state: json.dumps(dict(state=state))
        service = Mock()
        service.poll.return_value = None
        prompt = dict(deviceId='device-example', expiresAtUnixMs=20000)
        iterations = 0
        def wait(_):
            nonlocal iterations
            iterations += 1
            if iterations == 3:
                device.STOP.set()
        with patch.object(device.subprocess, 'Popen', return_value=service), \
                patch.object(device, 'cli', side_effect=[health('UNENROLLED'), health('UNENROLLED'),
                    health('UNENROLLED'), health('PENDING'), health('UNENROLLED')]), \
                patch.object(device, 'enrollment', side_effect=[RuntimeError('Gateway unavailable'), prompt]) as enroll, \
                patch.object(device, 'publish'), patch.object(device.time, 'time', return_value=1), \
                patch.object(device.STOP, 'wait', side_effect=wait), patch.object(device, 'STATUS', Mock()):
            device.supervise()
        self.assertEqual(enroll.call_count, 2)
        service.terminate.assert_called_once()
        service.wait.assert_called_once_with(timeout=20)
