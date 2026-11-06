# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Coverage reporting must expose incomplete measurements and preserve source scope."""
import contextlib
import runpy
import sys
import types
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('coverage_inventory', ROOT/'tools/ci/coverage_inventory.py')
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class CoverageBaselineTests(unittest.TestCase):
    def test_production_sources_are_not_excluded(self):
        for name in ('apps/admin-ui/src/api.ts', 'packages/contracts/typescript/src/index.ts', 'tools/check.py'):
            self.assertIsNone(inventory.exclusion(Path(name)))
        self.assertIsNotNone(inventory.exclusion(Path('apps/admin-ui/tests/api.test.ts')))
        self.assertIsNotNone(inventory.exclusion(Path('apps/admin-ui/src/vite-env.d.ts')))

    def test_java_coverage_executes_enterprise_suites_and_preserves_test_failure(self):
        @contextlib.contextmanager
        def database():
            yield dict(CONTROL_TEST_URL='jdbc:postgresql://fixture/control',CONTROL_TEST_PASSWORD='test-only-password')
        fake=types.ModuleType('control.check');fake.database=database
        for failed in (0,1):
            results=[types.SimpleNamespace(returncode=failed),types.SimpleNamespace(returncode=0)]
            with self.subTest(failed=failed),patch.dict(sys.modules,{'control.check':fake}),patch.object(sys,'path',list(sys.path)),patch('subprocess.run',side_effect=results) as run:
                with self.assertRaises(SystemExit) as exit:
                    runpy.run_path(str(ROOT/'tools/ci/java_coverage.py'),run_name='__main__')
                self.assertEqual(failed,exit.exception.code)
                commands=[call.args[0] for call in run.call_args_list]
                self.assertIn(':control-plane:enterpriseTest',commands[0])
                self.assertEqual('jacocoRootReport',commands[1][-1])
                self.assertEqual('jdbc:postgresql://fixture/control',run.call_args_list[0].kwargs['env']['CONTROL_TEST_URL'])

    def test_missing_reports_are_explicit(self):
        (ROOT/'.dev').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/'.dev') as temp:
            root = Path(temp)
            (root/'settings.gradle.kts').write_text('project(":example").projectDir = file("example")')
            with patch.object(inventory, 'collect', return_value=[]):
                result = inventory.append_summary(root)
            self.assertIn('MISSING — see collector logs', result)
            self.assertIn('MISSING: not compiled', result)
            self.assertIn('not included in a repository-wide percentage', result)
            self.assertTrue((root/'build/coverage/source-inventory.json').exists())

