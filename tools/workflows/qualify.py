# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Run the v1 scenario contract against an explicitly supplied live-install driver.

No built-in simulated driver or production success fallback is provided. Driver
code is trusted operator code, separate from the generic runtime and profiles.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "docs/architecture/workflow-v1-acceptance.json"
RUNTIME = ROOT / "apps/langgraph-runtime"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_inventory(root: Path) -> dict[str, str]:
    paths = sorted((root / "src").rglob("*.py"))
    paths.append(root / "requirements.lock")
    paths.append(root / "pyproject.toml")
    if len(paths) < 3:
        raise ValueError("runtime source or dependency lock missing")
    return {p.relative_to(root).as_posix(): digest(p) for p in paths}


def load_driver(path: Path):
    spec = importlib.util.spec_from_file_location("workflow_live_qualification", path)
    if spec is None or spec.loader is None:
        raise ValueError("driver is not a Python module")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    if not callable(getattr(module, "run_scenario", None)):
        raise ValueError("driver must export run_scenario(scenario, evidence_directory)")
    return module


def validate_observation(scenario: dict, value: object, directory: Path) -> dict:
    """Validate evidence completeness, not the truth of an operator's assertions."""
    if not isinstance(value, dict) or set(value) != {"checks", "artifacts"}:
        raise ValueError("observation requires exactly checks and artifacts")
    checks = value["checks"]
    if not isinstance(checks, dict) or set(checks) != set(scenario["then"]):
        raise ValueError("observation must cover every scenario assertion exactly")
    if any(type(result) is not bool for result in checks.values()):
        raise ValueError("assertion results must be booleans")
    artifacts = value["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError("redacted evidence artifacts required")
    records = []
    seen = set()
    for name in artifacts:
        if not isinstance(name, str) or not name or name in seen:
            raise ValueError("invalid or duplicate artifact")
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise ValueError("artifact must remain within the scenario directory")
        path = directory / relative
        if not path.resolve().is_relative_to(directory.resolve()) or not path.is_file():
            raise ValueError("artifact missing or outside evidence directory")
        if any(parent.is_symlink() for parent in (path, *path.parents)
               if parent != directory.parent):
            raise ValueError("symlink artifacts are not accepted")
        seen.add(name)
        records.append({"path": name, "sha256": digest(path)})
    return {"checks": checks, "artifacts": records}


def worker(driver_path: Path, request: Path, response: Path, evidence: Path) -> None:
    driver = load_driver(driver_path)
    scenario = json.loads(request.read_text(encoding="utf-8"))
    observation = driver.run_scenario(scenario, evidence)
    result = validate_observation(scenario, observation, evidence)
    response.write_text(json.dumps(result), encoding="utf-8")


def qualify(output: Path, driver: Path | None, timeout: float) -> tuple[dict, int]:
    # Refuse existing output: do not mix evidence from different runs or revisions.
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    inventory = source_inventory(RUNTIME)
    contract = json.loads(SPEC.read_text(encoding="utf-8"))
    scenarios = contract["scenarios"]
    identifiers = [scenario["id"] for scenario in scenarios]
    if not scenarios or len(set(identifiers)) != len(identifiers):
        raise ValueError("scenario contract must contain unique scenarios")
    for scenario in scenarios:
        if (not isinstance(scenario["id"], str) or not scenario["id"]
                or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in scenario["id"])
                or not scenario["then"] or len(set(scenario["then"])) != len(scenario["then"])):
            raise ValueError("invalid scenario contract")
    report = {
        "reportVersion": 1,
        "qualification": "BLOCKED",
        "scope": "operator-driver evidence; independent acceptance review required",
        "specSha256": digest(SPEC),
        "runtimeFiles": inventory,
        "driverSha256": digest(driver) if driver else None,
        "scenarios": [],
    }
    for scenario in scenarios:
        item = {"id": scenario["id"], "phase": scenario["phase"], "status": "BLOCKED"}
        report["scenarios"].append(item)
        if driver is None:
            item["reason"] = "LIVE_INSTALL_DRIVER_MISSING"
            continue
        directory = output / scenario["id"]
        directory.mkdir(mode=0o700)
        # Private protocol files are not retained as qualification artifacts.
        with tempfile.TemporaryDirectory(prefix="workflow-qualification-") as temporary:
            request, response = Path(temporary) / "request.json", Path(temporary) / "response.json"
            request.write_text(json.dumps(scenario), encoding="utf-8")
            try:
                completed = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--worker",
                     str(driver), str(request), str(response), str(directory)],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, timeout=timeout, check=False,
                )
                if completed.returncode != 0:
                    item.update(status="ERROR", reason="DRIVER_ERROR")
                    continue
                # Revalidate artifacts at the controller boundary as well.
                result = json.loads(response.read_text(encoding="utf-8"))
                observation = {"checks": result["checks"],
                               "artifacts": [a["path"] for a in result["artifacts"]]}
                verified = validate_observation(scenario, observation, directory)
                if verified != result:
                    raise ValueError("evidence changed after driver completion")
                item.update(verified)
                item["status"] = "RECORDED" if all(result["checks"].values()) else "FAILED"
            except subprocess.TimeoutExpired:
                item.update(status="ERROR", reason="DRIVER_TIMEOUT")
            except (OSError, ValueError, KeyError, TypeError):
                item.update(status="ERROR", reason="INVALID_EVIDENCE")
    try:
        unchanged = (source_inventory(RUNTIME) == inventory
                     and digest(SPEC) == report["specSha256"]
                     and (not driver or digest(driver) == report["driverSha256"]))
    except (OSError, ValueError):
        unchanged = False
    if not unchanged:
        report["qualification"] = "SOURCE_CHANGED"
    elif all(item["status"] == "RECORDED" for item in report["scenarios"]):
        report["qualification"] = "EVIDENCE_RECORDED_REVIEW_REQUIRED"
    elif driver:
        report["qualification"] = "INCOMPLETE"
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report, 0 if report["qualification"] == "EVIDENCE_RECORDED_REVIEW_REQUIRED" else 2


def main() -> int:
    if len(sys.argv) == 6 and sys.argv[1] == "--worker":
        worker(*(Path(value) for value in sys.argv[2:]))
        return 0
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new private evidence directory")
    parser.add_argument("--driver", type=Path, help="trusted live-install Python driver (no default)")
    parser.add_argument("--scenario-timeout", type=float, default=300)
    args = parser.parse_args()
    if not 0 < args.scenario_timeout <= 3600:
        parser.error("scenario timeout must be positive and at most 3600 seconds")
    try:
        report, code = qualify(args.output.resolve(), args.driver.resolve() if args.driver else None,
                               args.scenario_timeout)
    except (OSError, ValueError):
        print("qualification setup failed; use a new output directory and readable inputs", file=sys.stderr)
        return 2
    print(report["qualification"])
    return code


if __name__ == "__main__":
    raise SystemExit(main())
