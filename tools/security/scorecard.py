# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Generate a fail-closed C1–C8 scorecard; authored labels are never evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
CLAIMS = {
    "C1": ["A1", "A2"], "C2": ["A1", "A8"],
    "C3a": ["A1", "A3"], "C3b": ["A1", "A2", "A4"],
    "C3c": ["A2"], "C4": ["A1", "A3"], "C5": ["A2"],
    "C6": ["A1", "A2", "A3", "A4", "A5", "A6"],
    "C7": ["A1", "A2", "A3"], "C8": ["A8"],
}
# Conservative coverage links, not assertions that a case proves a whole claim.
AREAS = {
    "security": {
        "admission": ["C3a", "C4", "C8"], "broker": ["C1", "C3b"],
        "business-keys": ["C8"], "catalog-scope": ["C3a"],
        "credentials": ["C1", "C2"], "delegation": ["C2", "C3a"],
        "effect-class": ["C3b", "C8"], "egress": ["C1", "C5"],
        "identity": ["C2"], "kill-merge": ["C7"],
        "request-state": ["C3a", "C4"], "sandbox": ["C1", "C3c", "C5"],
        "secrets": ["C1", "C3b"], "service-profile-delta": ["C3b", "C4"],
    },
    "integration": {
        "audit-spool": ["C6"], "client-delivery": ["C4", "C8"],
        "deployment-binding": ["C4"], "duplicate-execution": ["C8"],
        "kill-delivery": ["C3c", "C7"], "lifecycle-rollout": ["C3a", "C4"],
        "openapi-import": ["C3b", "C4"], "persistence-failures": ["C6", "C8"],
        "transitions": ["C3a", "C6", "C8"],
    },
}
REQUIREMENTS = {
    "C1": "No ambient credentials or direct brokered-target access in each qualified tier.",
    "C2": "Sender-constrained verified agent-run identity, not bearer identity alone.",
    "C3a": "Revocation-before-admission ordering against actual Control.",
    "C3b": "Exact effect consent/request, owner ordering, target consume and unfenced expiry.",
    "C3c": "Measured healthy-host lease bound including unmediated effects and partition.",
    "C4": "Exact signed arguments/package/host/attempt; substitution and replay refusal.",
    "C5": "Full sandbox, broker identity/network and interface attack matrix for each tier.",
    "C6": "Graded outcomes, durable intent, tenant chains, external anchors and offline verification.",
    "C7": "Measured revocation SLO for every supported connectivity class.",
    "C8": "All attempts/effects, owner generations, ambiguous retries, consent slots and target recovery.",
}


def source_digest(root=ROOT):
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=root
    )
    digest = hashlib.sha256()
    for name in sorted(set(names.split(b"\0")) - {b""}):
        path = root / os.fsdecode(name)
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Linked source cannot establish scorecard evidence")
        if path.is_file():
            digest.update(name + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def inventory(root=ROOT):
    result = []
    seen = set()
    base = root / "tool-sdk/conformance"
    for suite, areas in AREAS.items():
        paths = sorted((base / suite).glob("*.json"))
        actual = {path.stem for path in paths}
        if actual != set(areas):
            raise ValueError(f"Conformance area mapping drift: {suite}")
        for path in paths:
            document = json.loads(path.read_text())
            if document["suite"] != suite or document["area"] != path.stem:
                raise ValueError(f"Conformance location mismatch: {path.name}")
            for case in document["cases"]:
                identifier = case["id"]
                if identifier in seen:
                    raise ValueError(f"Duplicate case: {identifier}")
                seen.add(identifier)
                result.append({
                    "id": identifier, "title": case["title"],
                    "claims": areas[path.stem],
                    "source": path.relative_to(root).as_posix(),
                    "declaredStatus": case["status"],
                    "status": "MISSING",
                    "reason": "No complete case-specific executed-evidence adapter is registered.",
                })
    if not result:
        raise ValueError("Empty conformance inventory")
    return result


def scorecard(cases, revision, source, observations=()):
    # Observations intentionally never promote requirements or authored cases.
    # An adapter must prove every assertion/target before a case can be credited.
    claims = []
    for identifier, attackers in CLAIMS.items():
        required = [case["id"] for case in cases if identifier in case["claims"]]
        if not required:
            raise ValueError(f"Claim without required coverage: {identifier}")
        claims.append({
            "id": identifier, "attackers": attackers,
            "requirement": REQUIREMENTS[identifier],
            "status": "INCOMPLETE", "requiredCases": required,
            "reason": "Complete claim-specific execution and deployment qualification is outstanding.",
        })
    return {
        "formatVersion": 1, "revision": revision, "sourceSha256": source,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "passed": False, "claims": claims, "cases": cases,
        "observations": list(observations),
        "counts": {"cases": len(cases), "passed": 0,
                   "missing": len(cases), "claimsAccepted": 0},
        "scope": "Coverage inventory and explicitly scoped observations; not full security acceptance",
    }



def markdown(report):
    lines = [
        "# Zero Trust Execution scorecard", "",
        "**NOT ACCEPTED** — missing case execution remains a release gap.", "",
        f"Tested revision: {report['revision']}",
        f"Source SHA-256: {report['sourceSha256']}", "",
        "| Claim check | Attacker classes | Result | Required cases |",
        "|---|---|---|---:|",
    ]
    for row in report["claims"]:
        lines.append(f"| {row['id']} | {', '.join(row['attackers'])} | {row['status']} | {len(row['requiredCases'])} |")
    lines += ["", "## Scoped executed observations", ""]
    for row in report["observations"]:
        lines.append(f"- {row['id']}: **{row['status']}**. {row['scope']}")
    if not report["observations"]:
        lines.append("None executed; inventory-only run.")
    lines += ["", "## Required cases", "",
              "| Case | Coverage links | Result |", "|---|---|---|"]
    for row in report["cases"]:
        lines.append(f"| {row['id']} | {', '.join(row['claims'])} | {row['status']} |")
    lines += ["", "Authored passing labels are metadata. Scoped observations do not satisfy",
              "complete case assertions, target matrices or claim requirements.", ""]
    return "\n".join(lines)


def observation_passed(returncode, text):
    name = "a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls"
    return (returncode == 0
            and re.search(r"^test " + name + r" \.\.\. ok$", text, re.MULTILINE) is not None
            and "1 passed; 0 failed; 0 ignored;" in text)


def execute_observation(output, root=ROOT):
    command = ["cargo", "test", "-p", "olo-toolgate-tool-host", "--locked",
               "--test", "service_dispatch",
               "a_refused_send_gate_contacts_no_target_and_an_allowed_gate_sends_once_over_mtls",
               "--", "--exact", "--nocapture"]
    log = output / "owner-dispatch.log"
    with log.open("w") as stream:
        try:
            result = subprocess.run(command, cwd=root, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=600, check=False)
            returncode = result.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            stream.write(type(error).__name__ + ": observation did not complete\n")
            returncode = None
    data = log.read_bytes()
    text = data.decode("utf-8", errors="replace")
    passed = observation_passed(returncode, text)
    return {
        "id": "olo-owner-dispatch", "claims": ["C3b", "C8"],
        "status": "PASSED" if passed else "FAILED", "command": command,
        "returnCode": returncode, "log": log.name,
        "logSha256": hashlib.sha256(data).hexdigest(),
        "scope": "Actual Rust mTLS send ordering against scripted Control/target; no full-case/claim credit",
    }



def verify_diagnostics(output, root=ROOT):
    """Permit diagnostic publication, never security release acceptance."""
    report = json.loads((output / "scorecard.json").read_text())
    expected = inventory(root)
    if (report.get("formatVersion") != 1 or report.get("passed") is not False
            or report.get("sourceSha256") != source_digest(root)):
        raise ValueError("Incomplete, changed-source or unexpected scorecard")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if report.get("revision") != revision:
        raise ValueError("Scorecard revision differs from the checkout")
    cases = report.get("cases", [])
    if cases != expected:
        raise ValueError("Required case inventory differs from the checkout")
    claims = report.get("claims", [])
    if len(claims) != len(CLAIMS) or {row["id"] for row in claims} != set(CLAIMS):
        raise ValueError("Complete unique claim checks are required")
    for row in claims:
        if (row.get("status") != "INCOMPLETE"
                or row.get("attackers") != CLAIMS[row["id"]]
                or row.get("requiredCases") != [case["id"] for case in expected if row["id"] in case["claims"]]):
            raise ValueError("Claim requirements or result differ from diagnostic policy")
    observations = report.get("observations", [])
    if len(observations) != 1:
        raise ValueError("The supported observation must execute")
    observation = observations[0]
    if (observation.get("id") != "olo-owner-dispatch"
            or observation.get("status") != "PASSED" or observation.get("returnCode") != 0
            or observation.get("log") != "owner-dispatch.log"):
        raise ValueError("The supported observation failed or was skipped")
    log = output / "owner-dispatch.log"
    if (observation.get("logSha256") != hashlib.sha256(log.read_bytes()).hexdigest()
            or not observation_passed(observation["returnCode"], log.read_text())):
        raise ValueError("Observation log changed after execution")
    if report.get("counts") != {"cases": len(expected), "passed": 0,
                               "missing": len(expected), "claimsAccepted": 0}:
        raise ValueError("Scorecard counts do not match required coverage")
    if (output / "scorecard.md").read_text() != markdown(report):
        raise ValueError("Published summary differs from scorecard data")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true",
                        help="Execute the available scoped observation; missing full cases still fail.")
    parser.add_argument("--output", type=Path, default=ROOT / "build/zero-trust-scorecard")
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    # Invalidate old acceptance before reading input or launching a producer.
    report_path = args.output / "scorecard.json"
    report_path.write_text(json.dumps({"passed": False, "status": "RUNNING"}) + "\n")
    (args.output / "scorecard.md").write_text("# Zero Trust Execution scorecard\n\nNOT ACCEPTED: qualification is running or failed.\n")
    try:
        before = source_digest()
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        cases = inventory()
        observations = [execute_observation(args.output)] if args.execute else []
        report = scorecard(cases, revision, before, observations)
        if source_digest() != before:
            raise ValueError("Source changed during qualification")
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        (args.output / "scorecard.md").write_text(markdown(report))
        print(f"Zero Trust scorecard: 0/{len(CLAIMS)} claim checks accepted; {len(cases)} missing case adapters.")
        print(f"Report: {report_path}")
        return 1
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report_path.write_text(json.dumps({"passed": False, "status": "ERROR",
                                          "error": str(error)}, indent=2) + "\n")
        print(f"Scorecard failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
