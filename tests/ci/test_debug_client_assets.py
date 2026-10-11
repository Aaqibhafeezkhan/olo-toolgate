# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/client'))
import debug_assets

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which('powershell.exe') or shutil.which('pwsh')


@unittest.skipUnless(POWERSHELL, 'PowerShell is required for the debug release lookup')
class DebugReleaseLookupTest(unittest.TestCase):
    def lookup(self, token=None, rejected=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'debug').mkdir()
            shutil.copyfile(ROOT/'debug/client-assets.ps1', root/'debug/client-assets.ps1')
            (root/'VERSION').write_text('fixture\n')
            compiler = root/'.dev/debug/toolgate-inno-6.7.1/ISCC.exe'
            compiler.parent.mkdir(parents=True)
            compiler.touch()
            harness = root/'harness.ps1'
            harness.write_text('''
$ErrorActionPreference = 'Stop'
function Invoke-RestMethod {
    param([string]$Uri, [hashtable]$Headers)
    Add-Content -LiteralPath $env:TEST_ASSETS_CALLS -Value (ConvertTo-Json -InputObject @{kind='api'; uri=$Uri; headers=$Headers} -Depth 4 -Compress)
    if ($env:TEST_ASSETS_REJECTED -eq '1') { throw 'GitHub returned 401 Unauthorized' }
    return @(@{
        draft = $false
        tag_name = 'fixture-release'
        assets = @(
            @{name='installers.json'},
            @{name='olo-toolgate-client-fixture-x86_64-pc-windows-msvc.setup.exe'},
            @{name='olo-toolgate-client-fixture-x86_64-unknown-linux-gnu.run'; browser_download_url='https://github.com/olo-labs/olo-toolgate/releases/download/fixture/client.run'}
        )
    })
}
function curl.exe {
    Add-Content -LiteralPath $env:TEST_ASSETS_CALLS -Value (ConvertTo-Json -InputObject @{kind='download'; arguments=@($args)} -Compress)
    $global:LASTEXITCODE = 0
}
function fixture-python {
    Add-Content -LiteralPath $env:TEST_ASSETS_CALLS -Value (ConvertTo-Json -InputObject @{kind='build'; arguments=@($args)} -Compress)
    $global:LASTEXITCODE = 0
}
& "$PSScriptRoot/debug/client-assets.ps1" -Python fixture-python
''', encoding='utf-8')
            environment = dict(os.environ, TEST_ASSETS_CALLS=str(root/'calls.jsonl'),
                               TEST_ASSETS_REJECTED='1' if rejected else '0')
            environment.pop('GITHUB_TOKEN', None)
            if token is not None:
                environment['GITHUB_TOKEN'] = token
            result = subprocess.run([POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                     '-File', str(harness)], env=environment,
                                    capture_output=True, text=True, timeout=30)
            calls = [json.loads(line) for line in
                     (root/'calls.jsonl').read_text(encoding='utf-8-sig').splitlines()]
            return result, calls

    def test_token_authenticates_api_without_entering_download_commands_or_logs(self):
        result, calls = self.lookup(' fixture-token ')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([call['kind'] for call in calls], ['api', 'download', 'build'])
        self.assertEqual(calls[0]['uri'], 'https://api.github.com/repos/olo-labs/olo-toolgate/releases?per_page=30')
        self.assertEqual(calls[0]['headers']['Authorization'], 'Bearer fixture-token')
        self.assertNotIn('fixture-token', json.dumps(calls[1:]))
        self.assertNotIn('fixture-token', result.stdout + result.stderr)

    def test_missing_or_blank_token_uses_public_access(self):
        for token in (None, '', ' '):
            with self.subTest(token=token):
                result, calls = self.lookup(token)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn('Authorization', calls[0]['headers'])
                self.assertEqual([call['kind'] for call in calls], ['api', 'download', 'build'])

    def test_rejected_token_stops_without_an_unauthenticated_retry(self):
        result, calls = self.lookup('fixture-token', rejected=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual([call['kind'] for call in calls], ['api'])
        self.assertIn('401 Unauthorized', result.stderr)
        self.assertNotIn('fixture-token', result.stdout + result.stderr)


def binary(target):
    raw = bytearray(160)
    arm = target.startswith('aarch64')
    if 'windows' in target:
        raw[:2] = b'MZ'
        raw[60:64] = (64).to_bytes(4, 'little')
        raw[64:68] = b'PE\0\0'
        raw[68:70] = (0xaa64 if arm else 0x8664).to_bytes(2, 'little')
    elif 'apple' in target:
        raw[:4] = b'\xcf\xfa\xed\xfe'
        raw[4:8] = (0x100000c if arm else 0x1000007).to_bytes(4, 'little')
    else:
        raw[:6] = b'\x7fELF\x02\x01'
        raw[18:20] = (183 if arm else 62).to_bytes(2, 'little')
    return bytes(raw)


def checksum(path):
    path.with_name(path.name + '.sha256').write_text(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n')


def archive(source, version, target, host=True):
    executable = 'olo-toolgate-client.exe' if 'windows' in target else 'olo-toolgate-client'
    files = {executable: binary(target), 'sbom.cdx.json': b'{"bomFormat":"CycloneDX"}'}
    if 'windows' in target and host:
        files['olo-toolgate-browser-host.exe'] = binary(target)
    path = source / debug_assets.archive_name(version, target)
    if 'windows' in target:
        with zipfile.ZipFile(path, 'w') as writer:
            for name, data in files.items():
                writer.writestr(name, data)
    else:
        with tarfile.open(path, 'w:gz') as writer:
            for name, data in files.items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                writer.addfile(info, io.BytesIO(data))
        suffix = 'dmg' if 'apple' in target else 'run'
        installer = source / f'olo-toolgate-client-{version}-{target}.{suffix}'
        installer.write_bytes(b'native fixture installer')
        checksum(installer)
    checksum(path)
    return path


class DebugClientAssetsTest(unittest.TestCase):
    def test_legacy_inputs_do_not_override_a_current_gnu_bundle(self):
        version = (debug_assets.ROOT / 'VERSION').read_text().strip()
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'source'
            source.mkdir()
            output = Path(temporary) / 'output'
            legacy = archive(source, version, debug_assets.NATIVE_WINDOWS[0], host=False)
            original = legacy.read_bytes()
            archive(source, version, debug_assets.NATIVE_WINDOWS[1], host=False)
            archive(source, version, 'x86_64-unknown-linux-gnu')
            archive(source, version, 'x86_64-apple-darwin')

            def compile_windows(destination):
                archive(destination, version, debug_assets.GNU_TARGET)

            def compile_installer(target, stage):
                files = debug_assets.payload(stage / debug_assets.archive_name(version, target))
                self.assertIn('olo-toolgate-browser-host.exe', files)
                installer = stage / f'olo-toolgate-client-{version}-{target}.setup.exe'
                installer.write_bytes(b'combined fixture installer')
                checksum(installer)

            with patch.object(debug_assets, 'build_windows_gnu', side_effect=compile_windows) as build, \
                 patch.object(debug_assets, 'build_installer', side_effect=compile_installer) as install:
                document = debug_assets.prepare(source, output)
            build.assert_called_once_with(source)
            self.assertEqual(install.call_args.args[0], debug_assets.GNU_TARGET)
            self.assertEqual({item['target'] for item in document['artifacts']},
                             {debug_assets.GNU_TARGET, 'x86_64-unknown-linux-gnu', 'x86_64-apple-darwin'})
            installers = json.loads((output / 'installers.json').read_text())
            self.assertIn(debug_assets.GNU_TARGET, {item['target'] for item in installers['artifacts']})
            self.assertFalse(any(output.glob('*windows-msvc*')))
            self.assertEqual(legacy.read_bytes(), original)
            self.assertTrue((output / 'extension.json').is_file())

    def test_compatible_native_ci_inputs_remain_native(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            for target in debug_assets.NATIVE_WINDOWS:
                archive(source, 'fixture', target)
            self.assertEqual(debug_assets.compatible_windows(source, 'fixture'),
                             list(debug_assets.NATIVE_WINDOWS))

    def test_bad_checksum_does_not_trigger_a_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            path = archive(source, 'fixture', debug_assets.NATIVE_WINDOWS[0], host=False)
            path.write_bytes(path.read_bytes() + b'tampered')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                debug_assets.compatible_windows(source, 'fixture')

    def test_wrong_native_host_architecture_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary)
            path = archive(source, 'fixture', debug_assets.NATIVE_WINDOWS[0])
            with zipfile.ZipFile(path) as reader:
                files = {name: reader.read(name) for name in reader.namelist()}
            files['olo-toolgate-browser-host.exe'] = binary(debug_assets.NATIVE_WINDOWS[1])
            with zipfile.ZipFile(path, 'w') as writer:
                for name, data in files.items():
                    writer.writestr(name, data)
            checksum(path)
            with self.assertRaisesRegex(ValueError, 'architecture does not match'):
                debug_assets.compatible_windows(source, 'fixture')
