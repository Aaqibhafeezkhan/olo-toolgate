# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Qualify the actual knowledge targets against this ToolGate SDK; retain source-bound component evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SUFFIXES = {".java", ".sql", ".gradle", ".kts", ".py", ".json"}


def source_digest(root):
    files = []
    for base, directories, names in os.walk(root):
        directories[:] = [name for name in directories if name not in {"build", ".gradle", ".git", "node_modules"}]
        files.extend(Path(base) / name for name in names if Path(name).suffix in SUFFIXES)
    digest = hashlib.sha256()
    for path in sorted(files, key=lambda value: value.relative_to(root).as_posix()):
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError("Source file resolves outside the selected component")
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes())
    return digest.hexdigest()


def verify(knowledge, version):
    path = knowledge / "build/qualification/service-components.json"
    report = json.loads(path.read_text(encoding="utf-8"))
    if not report.get("passed") or report.get("status") != "PASSED":
        raise ValueError("Knowledge qualification has not passed")
    if not all(report.get(name) for name in ("withTls", "withVectors", "withEvaluation")):
        raise ValueError("TLS, vectors and retrieval evaluation must all execute")
    modules = report["modules"]
    if set(modules) != {"service-core", "olo-memory", "olo-rag"} or any(
        result["tests"] <= 0 or any(result[key] for key in ("failures", "errors", "skipped"))
        for result in modules.values()
    ):
        raise ValueError("Incomplete knowledge component evidence")
    if report["sourceSha256"] != source_digest(knowledge):
        raise ValueError("Knowledge source differs from executed qualification")
    if report.get("toolgateVerifierSourceSha256") != source_digest(ROOT / "backend-libs/service-authorization"):
        raise ValueError("Target verifier source differs from executed qualification")
    artifact = ROOT / f"build/sdk-repo/io/github/olo-labs/toolgate-service-authorization/{version}/toolgate-service-authorization-{version}.jar"
    if report["toolgateSdkSha256"] != hashlib.sha256(artifact.read_bytes()).hexdigest():
        raise ValueError("Knowledge qualification used a different SDK artifact")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--knowledge-source", required=True, type=Path, help="The olo-mono/olo-knowledge component checkout")
    parser.add_argument("--gradle", help="Gradle 8.12 executable for the external component")
    parser.add_argument("--verify-only", action="store_true", help="Check previously executed source/SDK-bound component evidence")
    args = parser.parse_args()
    knowledge = args.knowledge_source.resolve()
    version = (ROOT / "VERSION").read_text().strip()
    output = ROOT / "build/security/knowledge/conformance.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {"passed": False, "scope": "Actual memory/RAG component qualification with scripted Control; excludes full-stack and production SLO acceptance",
              "toolgateRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
    output.write_text(json.dumps(result, indent=2) + "\n")
    try:
        if not (knowledge / "service-core/src/main/java/org/olo/knowledge/core/TransportBindings.java").is_file():
            raise ValueError("Select the upgraded knowledge component with trusted transport bindings")
        before = source_digest(ROOT / "backend-libs/service-authorization")
        if not args.verify_only:
            if not args.gradle:
                parser.error("--gradle is required when executing qualification")
            environment = dict(os.environ, TOOLGATE_SOURCE=str(ROOT), TOOLGATE_VERSION=version, GRADLE=args.gradle)
            environment.setdefault("GRADLE_OPTS", "-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false")
            wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
            subprocess.run([str(wrapper), "--no-parallel", "--max-workers=2", ":service-authorization:check",
                            "publishJavaSdkLocally", ":contracts-java:publishMavenJavaPublicationToContractProofRepository"],
                           cwd=ROOT, env=environment, check=True)
            subprocess.run([sys.executable, "tools/test_services.py", "--with-vectors", "--with-tls", "--evaluation"],
                           cwd=knowledge, env=environment, check=True)
        report = verify(knowledge, version)
        if source_digest(ROOT / "backend-libs/service-authorization") != before:
            raise ValueError("Target verifier source changed during qualification")
        result.update(passed=True, verifierSourceSha256=before, knowledge=report)
        print("Knowledge target qualification verified; all component tests executed")
    except BaseException as failure:
        result["failureType"] = type(failure).__name__
        raise
    finally:
        output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
