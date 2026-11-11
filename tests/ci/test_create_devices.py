# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Verify device deployment boundaries without creating or approving Docker devices."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which('powershell.exe') or shutil.which('pwsh')


@unittest.skipUnless(POWERSHELL, 'PowerShell is required for the Windows launcher')
class CreateDevicesTests(unittest.TestCase):
    def deploy(self, count=1, engine='linux', existing=False, owner='linux', validate=False, prompt=False):
        fixture = {
            'engine': engine, 'owner': owner,
            'existing': ['toolgate-device-linux-001'] if existing else [],
            'server': {'Name': '/toolgate-debug-quickstart-1',
                       'State': {'Health': {'Status': 'healthy'}},
                       'Config': {'Env': ['TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL=https://localhost:18450']},
                       'NetworkSettings': {'Networks': {'toolgate-debug_default': {}}}},
            'status': {'deviceId': 'device-' + 'a' * 32, 'state': 'PENDING',
                       'userCode': 'A' * 16, 'keyFingerprint': 'a' * 64},
        }
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / 'fixture.json').write_text(json.dumps(fixture))
            harness = work / 'harness.ps1'
            harness.write_text('''$fixture = Get-Content -LiteralPath $env:TEST_DEVICE_FIXTURE -Raw | ConvertFrom-Json
function docker {
    Add-Content -LiteralPath $env:TEST_DEVICE_CALLS -Value (ConvertTo-Json -InputObject @($args) -Compress)
    $items = @($args)
    if ($items[0] -eq '--context') { $items = $items[2..($items.Length - 1)] }
    $global:LASTEXITCODE = 0
    switch ($items[0]) {
        'info' { $fixture.engine }
        'ps' { if ($items -contains '--filter') { 'server-id' } else { $fixture.existing } }
        'inspect' {
            if ($items -contains '--format') {
                'true'
            } elseif ($items[1] -eq 'server-id') {
                ConvertTo-Json -InputObject @($fixture.server) -Depth 8 -Compress
            } else {
                ConvertTo-Json -InputObject @(@{ Config = @{ Labels = @{ 'io.ololabs.toolgate.debug-device' = $fixture.owner } } }) -Depth 8 -Compress
            }
        }
        'run' { 'new-container-id' }
        'start' { 'toolgate-device-linux-001' }
        'exec' { ConvertTo-Json -InputObject $fixture.status -Compress }
    }
}
$options = @{ DockerContext = 'fixture-linux' }
if ($env:TEST_DEVICE_PROMPT -ne '1') { $options.Count = [int]$env:TEST_DEVICE_COUNT }
if ($env:TEST_DEVICE_VALIDATE -eq '1') { $options.ValidateOnly = $true }
& $env:TEST_DEVICE_SCRIPT @options
exit $LASTEXITCODE
''', encoding='utf-8')
            env = dict(os.environ, TEST_DEVICE_FIXTURE=str(work / 'fixture.json'),
                       TEST_DEVICE_CALLS=str(work / 'calls.jsonl'), TEST_DEVICE_COUNT=str(count),
                       TEST_DEVICE_PROMPT='1' if prompt else '0',
                       TEST_DEVICE_VALIDATE='1' if validate else '0',
                       TEST_DEVICE_SCRIPT=str(ROOT / 'debug/standard/create-devices.ps1'))
            result = subprocess.run([POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(harness)], input='\n', env=env,
                                    capture_output=True, text=True, timeout=30)
            log = work / 'calls.jsonl'
            calls = [json.loads(line) for line in log.read_text(encoding='utf-8-sig').splitlines()] if log.exists() else []
            return result, calls

    def test_default_prompt_creates_one_unapproved_device(self):
        result, calls = self.deploy(prompt=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        runs = [call for call in calls if call[2] == 'run']
        self.assertEqual(len(runs), 1)
        self.assertIn('toolgate-device-linux-001', runs[0])
        self.assertIn('--cap-drop', runs[0])
        self.assertNotIn('-p', runs[0])
        self.assertIn('compare the code/fingerprint', result.stdout)
        self.assertTrue(all(call[:2] == ['--context', 'fixture-linux'] for call in calls))

    def test_existing_devices_are_started_without_replacement(self):
        result, calls = self.deploy(existing=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        operations = [call[2] for call in calls]
        self.assertIn('start', operations)
        self.assertNotIn('run', operations)
        self.assertNotIn('rm', operations)

    def test_foreign_container_stops_before_build_or_deployment(self):
        result, calls = self.deploy(existing=True, owner='other')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('not managed', result.stdout)
        self.assertFalse(any(call[2] in ('build', 'run', 'start', 'rm') for call in calls))

    def test_windows_engine_stops_before_deployment(self):
        result, calls = self.deploy(engine='windows')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Linux Docker engine', result.stdout)
        self.assertEqual([call[2] for call in calls], ['info'])

    def test_validation_does_not_mutate_docker(self):
        result, calls = self.deploy(validate=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(any(call[2] in ('build', 'run', 'start', 'rm') for call in calls))

    def test_zero_and_invalid_counts_do_not_contact_docker(self):
        for count, expected in ((0, 0), (-2, 1), (33, 1)):
            with self.subTest(count=count):
                result, calls = self.deploy(count=count)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                self.assertEqual(calls, [])


class DeviceRelayTests(unittest.TestCase):
    def test_relay_preserves_binary_data_and_half_closed_connections(self):
        spec = importlib.util.spec_from_file_location('device_entrypoint', ROOT / 'debug/standard/device-entrypoint.py')
        entrypoint = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(entrypoint)

        class Reply(socketserver.BaseRequestHandler):
            def handle(self):
                received = bytearray()
                while data := self.request.recv(4096):
                    received.extend(data)
                self.request.sendall(bytes(received)[::-1])

        with socketserver.TCPServer(('127.0.0.1', 0), Reply) as upstream:
            with entrypoint.Relay(0, '127.0.0.1', upstream.server_address[1]) as relay:
                threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (upstream, relay)]
                for thread in threads:
                    thread.start()
                try:
                    payload = bytes(range(256)) * 300
                    with socket.create_connection(relay.server_address, timeout=5) as client:
                        client.sendall(payload)
                        client.shutdown(socket.SHUT_WR)
                        received = bytearray()
                        while data := client.recv(4096):
                            received.extend(data)
                    self.assertEqual(bytes(received), payload[::-1])
                finally:
                    relay.shutdown()
                    upstream.shutdown()


if __name__ == '__main__':
    unittest.main()
