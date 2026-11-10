# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Qualification harness tests; generated drivers are simulations, never v1 evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("qualify", Path(__file__).with_name("qualify.py"))
qualify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qualify)


class QualificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def driver(self, body):
        path = self.root / "driver.py"
        path.write_text("def run_scenario(scenario, evidence_directory):\n" + body, encoding="utf-8")
        return path

    def run_driver(self, body, timeout=10):
        return qualify.qualify(self.root / "evidence", self.driver(body), timeout)

    def test_missing_driver_blocks_every_scenario_and_pins_sources(self):
        report, code = qualify.qualify(self.root / "evidence", None, 10)
        self.assertEqual(code, 2)
        self.assertEqual(report["qualification"], "BLOCKED")
        self.assertEqual(len(report["scenarios"]), 16)
        self.assertTrue(all(s["status"] == "BLOCKED" for s in report["scenarios"]))
        self.assertIn("requirements.lock", report["runtimeFiles"])
        self.assertEqual(json.loads((self.root / "evidence/report.json").read_text()), report)

    def test_refuses_reused_evidence_directory(self):
        qualify.qualify(self.root / "evidence", None, 10)
        with self.assertRaises(FileExistsError):
            qualify.qualify(self.root / "evidence", None, 10)

    def test_complete_simulated_evidence_requires_independent_review(self):
        report, code = self.run_driver(
            "    (evidence_directory / 'redacted.json').write_text('{}')\n"
            "    return {'checks': {key: True for key in scenario['then']}, 'artifacts': ['redacted.json']}\n")
        self.assertEqual(code, 0)
        self.assertEqual(report["qualification"], "EVIDENCE_RECORDED_REVIEW_REQUIRED")
        self.assertTrue(all(s["status"] == "RECORDED" for s in report["scenarios"]))
        self.assertTrue(all(len(s["artifacts"][0]["sha256"]) == 64 for s in report["scenarios"]))

    def test_failed_assertions_do_not_qualify(self):
        report, code = self.run_driver(
            "    (evidence_directory / 'outcome').write_text('redacted denial')\n"
            "    return {'checks': {key: False for key in scenario['then']}, 'artifacts': ['outcome']}\n")
        self.assertEqual(code, 2)
        self.assertTrue(all(s["status"] == "FAILED" for s in report["scenarios"]))

    def test_exception_is_sanitized_and_does_not_become_success(self):
        report, code = self.run_driver("    raise RuntimeError('SECRET_MUST_NOT_APPEAR')\n")
        self.assertEqual(code, 2)
        self.assertTrue(all(s["reason"] == "DRIVER_ERROR" for s in report["scenarios"]))
        self.assertNotIn("SECRET_MUST_NOT_APPEAR", json.dumps(report))

    def test_deadline_stops_hung_driver(self):
        contract = {"scenarios": [{"id": "timeout", "phase": "V1.4", "then": ["visible"]}]}
        spec = self.root / "spec.json"
        spec.write_text(json.dumps(contract))
        with patch.object(qualify, "SPEC", spec):
            report, code = self.run_driver("    import time\n    time.sleep(60)\n", timeout=0.2)
        self.assertEqual(code, 2)
        self.assertEqual(report["scenarios"][0]["reason"], "DRIVER_TIMEOUT")

    def test_missing_or_nonboolean_assertions_rejected(self):
        scenario = {"then": ["no-effect"]}
        for checks in ({}, {"no-effect": 1}, {"no-effect": True, "extra": True}):
            with self.subTest(checks=checks), self.assertRaises(ValueError):
                qualify.validate_observation(scenario, {"checks": checks, "artifacts": []}, self.root)

    def test_missing_duplicate_and_escaping_artifacts_rejected(self):
        (self.root / "ok").write_text("redacted")
        for artifacts in ([], ["absent"], ["ok", "ok"], ["../outside"], [str(self.root / "ok")], ["a\\b"]):
            with self.subTest(artifacts=artifacts), self.assertRaises(ValueError):
                qualify.validate_observation({"then": ["a"]},
                    {"checks": {"a": True}, "artifacts": artifacts}, self.root)

    def test_symlink_evidence_rejected(self):
        outside = self.root / "outside"
        outside.write_text("sensitive")
        evidence = self.root / "evidence"
        evidence.mkdir()
        try:
            (evidence / "link").symlink_to(outside)
        except OSError:
            self.skipTest("host cannot create symlinks")
        with self.assertRaises(ValueError):
            qualify.validate_observation({"then": ["a"]},
                {"checks": {"a": True}, "artifacts": ["link"]}, evidence)

    def test_changed_source_invalidates_report(self):
        with patch.object(qualify, "source_inventory", side_effect=[{"source": "old"}, {"source": "new"}]):
            report, code = qualify.qualify(self.root / "evidence", None, 10)
        self.assertEqual(code, 2)
        self.assertEqual(report["qualification"], "SOURCE_CHANGED")

    def test_deleted_source_preserves_invalidated_report(self):
        with patch.object(qualify, "source_inventory", side_effect=[{"source": "old"}, FileNotFoundError()]):
            report, code = qualify.qualify(self.root / "evidence", None, 10)
        self.assertEqual(code, 2)
        self.assertEqual(report["qualification"], "SOURCE_CHANGED")
        self.assertTrue((self.root / "evidence/report.json").is_file())

    def test_nonfinite_deadline_rejected_by_cli(self):
        import subprocess
        import sys
        for timeout in ("nan", "inf", "0", "-1"):
            result = subprocess.run([sys.executable, str(Path(qualify.__file__)),
                "--output", str(self.root / "evidence"), "--scenario-timeout", timeout],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertFalse((self.root / "evidence").exists())

    def test_invalid_manifest_cannot_escape_or_vacuously_succeed(self):
        for index, scenarios in enumerate(([], [{"id": "../escape", "then": ["a"]}],
                [{"id": "same", "then": ["a"]}, {"id": "same", "then": ["a"]}])):
            spec = self.root / "spec.json"
            spec.write_text(json.dumps({"scenarios": scenarios}))
            with patch.object(qualify, "SPEC", spec), self.assertRaises(ValueError):
                qualify.qualify(self.root / str(index), None, 10)

    def test_true_assertions_without_artifacts_cannot_qualify(self):
        report, code = self.run_driver(
            "    return {'checks': {key: True for key in scenario['then']}, 'artifacts': []}\n")
        self.assertEqual(code, 2)
        self.assertTrue(all(s["status"] == "ERROR" for s in report["scenarios"]))


if __name__ == "__main__":
    unittest.main()
