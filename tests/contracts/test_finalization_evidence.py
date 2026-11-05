# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""A green aggregate, stale XML, or skipped PostgreSQL test cannot accept a vector."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/security"))
spec = importlib.util.spec_from_file_location("finalization_gate", ROOT / "tools/security/finalization.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class FinalizationEvidenceTest(unittest.TestCase):
    def test_catalog_keeps_explicit_component_scope(self):
        cases = gate.catalog()
        self.assertEqual(11, len(cases))
        self.assertTrue(all(len(case["caseSha256"]) == 64 for case in cases.values()))
        self.assertTrue(all(case["test"].startswith(gate.PREFIX) for case in cases.values()))

    def test_only_exact_unique_executed_cases_count(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "TEST-example.xml"
            case = '<testcase classname="example.Test" name="required()">{}</testcase>'
            def check(body):
                path.write_text("<testsuite>" + body + "</testsuite>")
                return gate.require_executed([path], {"example.Test.required"})
            self.assertEqual({"example.Test.required": path}, check(case.format("")))
            for content in ("", case.format("<skipped/>"), case.format("<failure/>"),
                            case.format("<error/>"), case.format("") * 2,
                            case.replace("required", "other").format("")):
                with self.subTest(content=content), self.assertRaises(ValueError):
                    check(content)


if __name__ == "__main__":
    unittest.main()
