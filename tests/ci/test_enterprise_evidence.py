# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Failed, skipped or absent acceptance evidence must never become a passing case."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('enterprise_acceptance', ROOT / 'tools/enterprise/acceptance.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


class EnterpriseEvidenceTests(unittest.TestCase):
    def test_report_needs_no_tracked_status_document_and_missing_evidence_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch.object(acceptance, 'ROOT', root), \
                 patch('sys.argv', ['acceptance.py', '--require-complete']), \
                 self.assertRaisesRegex(SystemExit, 'Acceptance evidence remains incomplete'):
                acceptance.main()
            report = json.loads((root/'build/enterprise/acceptance.json').read_text())
            self.assertEqual(report['mapped'], 96)
            self.assertEqual(report['verified'], 0)
            self.assertFalse((root/'docs').exists())

    def test_skips_failures_and_errors_are_not_passing_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            reports = root / 'apps/control-plane/build/test-results/test'
            reports.mkdir(parents=True)
            (reports / 'TEST-fixture.xml').write_text(
                '<testsuite><testcase name="pass()" classname="Fixture"/>'
                '<testcase name="skip()" classname="Fixture"><skipped/></testcase>'
                '<testcase name="fail()" classname="Fixture"><failure/></testcase>'
                '<testcase name="error()" classname="Fixture"><error/></testcase></testsuite>')
            with patch.object(acceptance, 'ROOT', root):
                results = acceptance.evidence()
            self.assertTrue(results['pass']['passed'])
            for name in ('skip', 'fail', 'error'):
                self.assertFalse(results[name]['passed'])
            self.assertNotIn('absent', results)

    def test_duplicate_test_name_cannot_select_a_convenient_passing_suite(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            reports = root / 'apps/control-plane/build/test-results/test'
            reports.mkdir(parents=True)
            for suite in ('First', 'Second'):
                (reports / ('TEST-' + suite + '.xml')).write_text(
                    '<testsuite><testcase name="same()" classname="' + suite + '"/></testsuite>')
            with patch.object(acceptance, 'ROOT', root), self.assertRaises(ValueError):
                acceptance.evidence()

    def test_absent_or_different_runtime_gate_does_not_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with patch.object(acceptance, 'ROOT', root):
                self.assertFalse(acceptance.runtime_gate('runtime.json', 'native delegated effect'))
                (root / 'runtime.json').write_text(json.dumps({'gates': ['unit simulation']}))
                self.assertFalse(acceptance.runtime_gate('runtime.json', 'native delegated effect'))


if __name__ == '__main__':
    unittest.main()
