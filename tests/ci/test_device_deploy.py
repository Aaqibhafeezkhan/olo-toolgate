# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Deployment arguments/defaults must reach Compose intact without invoking Docker."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which('powershell.exe')
BASH = shutil.which('bash') if os.name != 'nt' else 'C:/Program Files/Git/bin/bash.exe'


@unittest.skipUnless(POWERSHELL, 'Windows deployment wrapper')
class DeviceDeployTests(unittest.TestCase):
    def deploy(self, arguments):
        script = """
$ErrorActionPreference = 'Stop'
$global:Calls = [System.Collections.Generic.List[object]]::new()
function global:docker { $global:Calls.Add(@($args)); $global:LASTEXITCODE = 0 }
& $env:DEVICE_DEPLOY_SCRIPT @Parameters
@{calls=@($global:Calls);gateways=$env:TOOLGATE_GATEWAY_URLS;image=$env:TOOLGATE_DEVICE_IMAGE;
  network=$env:TOOLGATE_DEVICE_NETWORK} | ConvertTo-Json -Depth 5 -Compress
"""
        parameters = '$Parameters = '+arguments+'\n'
        result = subprocess.run([POWERSHELL, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
            '-Command', parameters+script],
            env={**os.environ, 'DEVICE_DEPLOY_SCRIPT': str(ROOT/'deploy/device/deploy.ps1')},
            check=True, text=True, capture_output=True)
        return json.loads(result.stdout.splitlines()[-1])

    def test_noninteractive_defaults_pull_and_recreate_the_latest_published_device(self):
        result = self.deploy('@{NonInteractive=$true}')
        self.assertEqual(result['gateways'],
            'https://host.docker.internal:18450,https://host.docker.internal:18451')
        self.assertEqual(result['image'], 'ololab/olo-toolgate-device:dev')
        self.assertEqual(result['calls'][1][-2:], ['pull', 'device'])
        self.assertIn('--force-recreate', result['calls'][2])
        self.assertIn('--wait', result['calls'][2])

    def test_container_aliases_and_an_existing_network_are_passed_as_explicit_arguments(self):
        result = self.deploy("@{NonInteractive=$true;Gateways='container_name_1:8443, container_name_2:8443';"
                             "Network='gateway_network';Project='second-device';Image='ololab/olo-toolgate-device:dev-abc'}")
        self.assertEqual(result['gateways'], 'container_name_1:8443, container_name_2:8443')
        self.assertEqual(result['network'], 'gateway_network')
        self.assertIn('second-device', result['calls'][0])
        self.assertTrue(any(value.endswith('compose.network.yaml') for value in result['calls'][0]))


class ShellDeployTests(unittest.TestCase):
    @unittest.skipUnless(BASH and Path(BASH).is_file(), 'Bash unavailable')
    def test_shell_wrapper_syntax(self):
        subprocess.run([BASH, '-n', str(ROOT/'deploy/device/deploy.sh')], check=True, capture_output=True)
