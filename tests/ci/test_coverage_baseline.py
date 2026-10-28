# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Coverage reporting must expose incomplete measurements and preserve source scope."""
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

