# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise local metadata repair with real generators and isolated deployment stubs."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from tools.contracts import generate, version

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which('powershell.exe')


@unittest.skipUnless(os.name == 'nt' and POWERSHELL, 'Windows PowerShell is required')
class DebugManagementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        sources = set(version.expected()) | {
            ROOT/'VERSION', ROOT/'packages/contracts/VERSION', ROOT/'CHANGELOG.md',
            ROOT/'LICENSE', ROOT/'NOTICE.md', ROOT/'debug/manage.ps1',
            ROOT/'tools/contracts/version.py', ROOT/'tools/contracts/generate.py',
            ROOT/'tools/ci/preflight.py',
        }
        # v1 schemas reference v2 definitions, so copy every supported version.
        for ver in generate.VERSIONS:
            sources.update((ROOT/'packages/contracts/schemas'/ver).glob('*.json'))
        for source in sources:
            target = self.root/source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        self.apis = [self.root/f'packages/contracts/openapi/{name}-v1.yaml'
                     for name in ('gateway', 'control')]
        self.api_originals = [path.read_bytes().replace(b'\r\n', b'\n') for path in self.apis]
        for path, original in zip(self.apis, self.api_originals):
            path.write_bytes(original.replace(b'\n', b'\r\n'))
        # The metadata commands need only the standard library; dependency installs are stubbed.
        subprocess.run([sys.executable, '-m', 'venv', '--without-pip',
                        str(self.root/'.dev/debug/venv')], check=True, timeout=30)
        (self.root/'debug/python.ps1').write_text('''
$script:originalInvokeChecked = ${function:Invoke-Checked}
function Find-DebugPython { return $env:TEST_DEBUG_PYTHON }
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    Add-Content -LiteralPath $env:TEST_DEBUG_CALLS -Value (ConvertTo-Json -InputObject @($Executable, $Arguments) -Depth 4 -Compress)
    if ($Arguments[0] -in @('tools/contracts/version.py', 'tools/contracts/generate.py', 'tools/ci/preflight.py')) {
        & $script:originalInvokeChecked $Executable $Arguments
    } else {
        $global:LASTEXITCODE = 0
    }
}
''', encoding='utf-8')
        (self.root/'debug/client-assets.ps1').write_text('param([string]$Python)\n', encoding='utf-8')
        self.harness = self.root/'harness.ps1'
        self.harness.write_text('''
function docker {
    Add-Content -LiteralPath $env:TEST_DEBUG_CALLS -Value (ConvertTo-Json -InputObject @('docker', @($args)) -Depth 4 -Compress)
    $global:LASTEXITCODE = 0
}
function git {}
function node {}
function npm {}
function Start-Process { param([string]$FilePath) }
$options = @{}
if ($env:TEST_DEBUG_VALIDATE -eq '1') { $options.ValidateOnly = $true }
& $env:TEST_DEBUG_SCRIPT $env:TEST_DEBUG_OPERATION @options
exit $LASTEXITCODE
''', encoding='utf-8')

    def manage(self, operation='restart', validate=False):
        log = self.root/'calls.jsonl'
        env = dict(os.environ, TEST_DEBUG_SCRIPT=str(self.root/'debug/manage.ps1'),
                   TEST_DEBUG_PYTHON=sys.executable, TEST_DEBUG_CALLS=str(log),
                   TEST_DEBUG_OPERATION=operation, TEST_DEBUG_VALIDATE='1' if validate else '0')
        result = subprocess.run([POWERSHELL, '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                 '-File', str(self.harness)], cwd=self.root/'debug',
                                env=env, capture_output=True, text=True, timeout=30)
        calls = [json.loads(line) for line in log.read_text(encoding='utf-8-sig').splitlines()]
        return result, calls

    def test_start_and_restart_repair_crlf_and_stale_bindings_before_install_or_deploy(self):
        binding = self.root/'packages/contracts/typescript/src/index.ts'
        binding.parent.mkdir(parents=True, exist_ok=True)
        for operation in ('start', 'restart'):
            with self.subTest(operation=operation):
                for path, original in zip(self.apis, self.api_originals):
                    path.write_bytes(original.replace(b'\n', b'\r\n'))
                binding.write_text('// stale binding\n', encoding='utf-8')
                (self.root/'calls.jsonl').unlink(missing_ok=True)
                result, calls = self.manage(operation)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn('Early release metadata and generated-binding gates passed', result.stdout)
                self.assertEqual([path.read_bytes() for path in self.apis], self.api_originals)
                self.assertIn(b'export interface ContractSet', binding.read_bytes())
                arguments = [call[1] for call in calls]
                repair = arguments.index(['tools/contracts/version.py'])
                self.assertEqual(arguments[repair:repair + 3], [
                    ['tools/contracts/version.py'], ['tools/contracts/generate.py'],
                    ['tools/ci/preflight.py']])
                self.assertLess(repair + 2, arguments.index(['ci', '--ignore-scripts']))
                self.assertTrue(any(call[0] == 'docker' and 'up' in call[1] for call in calls))

    def test_generation_failure_stops_before_dependency_install_or_container_replacement(self):
        schema = self.root/'packages/contracts/schemas/v1/invalid.schema.json'
        schema.write_text('{"$defs":{"Invalid":{"type":"object","allOf":[]}}}\n', encoding='utf-8')
        result, calls = self.manage()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Unsupported structural schema keywords', result.stderr)
        self.assertIn('Deployment was not continued', result.stdout)
        self.assertEqual(calls[-1][1], ['tools/contracts/generate.py'])
        self.assertFalse(any(call[0] in ('npm', 'npx') or 'up' in call[1] for call in calls))

    def test_stop_and_validate_leave_metadata_untouched(self):
        originals = [path.read_bytes() for path in self.apis]
        for operation, validate in (('stop', False), ('restart', True)):
            with self.subTest(operation=operation, validate=validate):
                (self.root/'calls.jsonl').unlink(missing_ok=True)
                result, calls = self.manage(operation, validate)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual([path.read_bytes() for path in self.apis], originals)
                self.assertTrue(all(call[0] == 'docker' for call in calls))


if __name__ == '__main__':
    unittest.main()
