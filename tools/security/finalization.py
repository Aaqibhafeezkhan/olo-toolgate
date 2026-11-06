# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Execute source-bound S10 Control component vectors; never accept distributed suites."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/control"))
from check import database
from scorecard import source_digest

PREFIX = "io.ololabs.toolgate.control."
POSTGRES = [
    PREFIX + "AttemptManifestTest.membershipAndClosureHoldOnThePostgresRuntimeRole",
    PREFIX + "AttemptReviewTest.reviewProofGuardsAlsoHoldUnderPostgresRuntimeRole",
    PREFIX + "RecoveryAdmissionIntegrationTest.signedScopeAndLeaseRecoveryAlsoHoldUnderThePostgresRuntimeRole",
    PREFIX + "EnterpriseOperationTest.windowAndRetentionScenariosHoldUnderPostgresRuntimeRole",
]


def catalog(root=ROOT):
    mapping = json.loads((root / "tools/security/finalization-cases.json").read_text())["tests"]
    cases = {}
    for path in (root / "tool-sdk/conformance/integration").glob("*.json"):
        for case in json.loads(path.read_text())["cases"]:
            if case["id"] in mapping:
                if case["id"] in cases:
                    raise ValueError("Duplicate finalization case")
                if case["level"] != "component" or case["targets"] != ["control"]:
                    raise ValueError("Component runner cannot accept other targets")
                cases[case["id"]] = {
                    "test": mapping[case["id"]],
                    "caseSha256": hashlib.sha256(json.dumps(case, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
                }
    if not mapping or set(cases) != set(mapping) or len(set(mapping.values())) != len(mapping):
        raise ValueError("Missing or ambiguous finalization case mapping")
    return cases


def require_executed(paths, required):
    """Missing, duplicate, failed, errored and skipped cases are never evidence."""
    found = {}
    for path in paths:
        suite = ET.parse(path).getroot()
        for case in suite.iter("testcase"):
            name = case.attrib.get("classname", "") + "." + case.attrib.get("name", "").removesuffix("()")
            if name in required:
                if name in found or any(case.find(kind) is not None for kind in ("failure", "error", "skipped")):
                    raise ValueError("Non-passing or duplicate execution: " + name)
                found[name] = path
    if set(found) != set(required):
        raise ValueError("Missing executed tests: " + ", ".join(sorted(set(required) - set(found))))
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/finalization")
    args = parser.parse_args(argv)
    output = args.output.resolve()
    if output.is_relative_to(ROOT) and not output.is_relative_to(ROOT / "build"):
        parser.error("In-repository evidence must live under build/")
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "finalization.json"
    report = {"formatVersion": 1, "passed": False, "securityAccepted": False,
              "scope": "S10 Control component vectors only; no distributed suite or claim acceptance"}
    # Invalidate previous success before discovery or execution can fail.
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    try:
        source = source_digest()
        cases = catalog()
        boundary = PREFIX + "application.OperationFinalizationPolicyTest.clippedHistoryPreservesKnownActiveWorkWithoutProvingFinality"
        required = {row["test"] for row in cases.values()} | set(POSTGRES) | {boundary}
        classes = sorted({name.rsplit(".", 1)[0] for name in required})
        results = ROOT / "apps/control-plane/build/test-results/enterpriseTest"
        # Remove only selected generated reports; stale XML can never satisfy this run.
        for name in classes:
            (results / ("TEST-" + name + ".xml")).unlink(missing_ok=True)
        command = ["./gradlew", "--no-parallel", "--max-workers=2", ":control-plane:enterpriseTest", "--rerun"]
        for name in classes:
            command += ["--tests", name]
        log = output / "control.log"
        with database() as fixture, log.open("w") as stream:
            env = dict(os.environ, **{key: value for key, value in fixture.items() if key.startswith("CONTROL_")})
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT,
                                    timeout=1200, check=False)
        if result.returncode:
            raise ValueError("Control component execution failed; see control.log")
        paths = [results / ("TEST-" + name + ".xml") for name in classes]
        require_executed(paths, required)
        if source != source_digest():
            raise ValueError("Source changed during execution")
        evidence = {}
        for path in paths:
            data = path.read_bytes()
            (output / path.name).write_bytes(data)
            evidence[path.name] = hashlib.sha256(data).hexdigest()
        report.update(passed=True, revision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                      sourceSha256=source, cases=cases, postgresTests=POSTGRES, xmlSha256=evidence,
                      logSha256=hashlib.sha256(log.read_bytes()).hexdigest())
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        report["error"] = str(error)
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": report["passed"], "securityAccepted": False, "report": str(report_path)}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
