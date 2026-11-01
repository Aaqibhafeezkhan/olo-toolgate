# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Acceptance labels and scoped test observations must not become security claims."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("zero_trust_scorecard", ROOT / "tools/security/scorecard.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ScorecardTest(unittest.TestCase):
    def test_every_catalog_case_is_present_but_authored_passing_is_not_evidence(self):
        cases = gate.inventory()
        expected = []
        for suite in gate.AREAS:
            for path in (ROOT / "tool-sdk/conformance" / suite).glob("*.json"):
                expected.extend(case["id"] for case in json.loads(path.read_text())["cases"])
        self.assertEqual(sorted(case["id"] for case in cases), sorted(expected))
        self.assertTrue(any(case["declaredStatus"] == "passing" for case in cases))
        self.assertTrue(all(case["status"] == "MISSING" for case in cases))

    def test_scoped_passing_observation_cannot_promote_a_case_or_claim(self):
        report = gate.scorecard(gate.inventory(), "a" * 40, "b" * 64,
                                [{"id": "observed", "status": "PASSED", "claims": list(gate.CLAIMS)}])
        self.assertFalse(report["passed"])
        self.assertEqual(report["counts"]["passed"], 0)
        self.assertEqual(report["counts"]["claimsAccepted"], 0)
        self.assertEqual({row["status"] for row in report["claims"]}, {"INCOMPLETE"})
        self.assertEqual({row["id"] for row in report["claims"]}, set(gate.CLAIMS))
        self.assertTrue(all(row["requiredCases"] for row in report["claims"]))

    def test_empty_coverage_cannot_report_acceptance(self):
        with self.assertRaisesRegex(ValueError, "without required coverage"):
            gate.scorecard([], "a" * 40, "b" * 64)

    def observation(self, output, returncode=0):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            def execute(*args, **kwargs):
                kwargs["stdout"].write(output)
                return subprocess.CompletedProcess(args[0], returncode)
            with patch.object(gate.subprocess, "run", side_effect=execute):
                return gate.execute_observation(folder)

    def test_observation_requires_exact_executed_test_and_no_skip(self):
        name = "a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls"
        result = self.observation(f"test {name} ... ok\ntest result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;\n")
        self.assertEqual(result["status"], "PASSED")
        for output in (
            "test result: ok. 0 passed; 0 failed; 0 ignored;\n",
            f"test {name} ... ignored\n1 passed; 0 failed; 0 ignored;\n",
            f"test {name} ... ok\n1 passed; 0 failed; 1 ignored;\n",
            "test another_test ... ok\n1 passed; 0 failed; 0 ignored;\n",
        ):
            with self.subTest(output=output):
                self.assertEqual(self.observation(output)["status"], "FAILED")
        self.assertEqual(self.observation(f"test {name} ... ok\n1 passed; 0 failed; 0 ignored;\n", 1)["status"], "FAILED")

    def test_missing_tool_and_timeout_are_failed_observations(self):
        for error in (FileNotFoundError("cargo"), subprocess.TimeoutExpired(["cargo"], 600)):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as temporary:
                with patch.object(gate.subprocess, "run", side_effect=error):
                    result = gate.execute_observation(Path(temporary))
                self.assertEqual(result["status"], "FAILED")
                self.assertIsNone(result["returnCode"])

    def test_old_success_is_invalidated_before_failed_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "scorecard.json").write_text('{"passed":true}')
            (output / "scorecard.md").write_text("ACCEPTED: old evidence")
            with patch.object(gate, "source_digest", side_effect=ValueError("linked source")):
                self.assertEqual(gate.main(["--output", str(output)]), 2)
            self.assertFalse(json.loads((output / "scorecard.json").read_text())["passed"])
            self.assertIn("NOT ACCEPTED", (output / "scorecard.md").read_text())
            self.assertNotIn("old evidence", (output / "scorecard.md").read_text())

    def test_source_change_during_execution_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(gate, "source_digest", side_effect=["a" * 64, "b" * 64]):
                self.assertEqual(gate.main(["--output", temporary]), 2)
            report = json.loads((Path(temporary) / "scorecard.json").read_text())
            self.assertFalse(report["passed"])
            self.assertIn("Source changed", report["error"])

    def test_inventory_only_still_has_nonzero_exit_and_complete_missing_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            self.assertEqual(gate.main(["--output", temporary]), 1)
            report = json.loads((Path(temporary) / "scorecard.json").read_text())
            self.assertEqual(report["counts"]["cases"], len(gate.inventory()))
            self.assertEqual(report["counts"]["cases"], report["counts"]["missing"])
            self.assertEqual(report["observations"], [])


    def test_diagnostic_publication_checks_source_inventory_and_log(self):
        import copy
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            log = b"test a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls ... ok\n1 passed; 0 failed; 0 ignored;\n"
            (folder / "owner-dispatch.log").write_bytes(log)
            report = gate.scorecard(gate.inventory(), "a" * 40, "b" * 64, [{
                "id": "olo-owner-dispatch", "status": "PASSED", "returnCode": 0,
                "log": "owner-dispatch.log", "logSha256": hashlib.sha256(log).hexdigest(),
                "scope": "scripted Control; scoped observation only",
            }])
            def verify(value):
                (folder / "scorecard.json").write_text(json.dumps(value))
                (folder / "scorecard.md").write_text(gate.markdown(value))
                with patch.object(gate, "source_digest", return_value="b" * 64), patch.object(
                        gate.subprocess, "check_output", return_value="a" * 40):
                    return gate.verify_diagnostics(folder)
            self.assertFalse(verify(report)["passed"])
            mutations = [
                lambda row: row.update(passed=True),
                lambda row: row.update(sourceSha256="c" * 64),
                lambda row: row.update(revision="c" * 40),
                lambda row: row["cases"].pop(),
                lambda row: row["cases"][0].update(status="PASSED"),
                lambda row: row["claims"].append(row["claims"][0]),
                lambda row: row["claims"][0].update(status="PASSED"),
                lambda row: row["counts"].update(passed=1),
                lambda row: row.update(observations=[]),
                lambda row: row["observations"][0].update(status="FAILED"),
                lambda row: row["observations"][0].update(log="../another.log"),
                lambda row: row["observations"][0].update(logSha256="f" * 64),
            ]
            for mutate in mutations:
                value = copy.deepcopy(report)
                mutate(value)
                with self.subTest(mutation=mutations.index(mutate)), self.assertRaises(ValueError):
                    verify(value)
            verify(report)
            (folder / "scorecard.md").write_text("ACCEPTED")
            with patch.object(gate, "source_digest", return_value="b" * 64), patch.object(
                    gate.subprocess, "check_output", return_value="a" * 40), self.assertRaises(ValueError):
                gate.verify_diagnostics(folder)


if __name__ == "__main__":
    unittest.main()
