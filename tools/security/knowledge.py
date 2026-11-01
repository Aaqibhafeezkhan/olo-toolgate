# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Qualify the actual knowledge targets against this ToolGate SDK; retain source-bound component evidence."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SUFFIXES = {".java", ".sql", ".gradle", ".kts", ".py", ".json"}


def source_digest(root):
    files = []
    for base, directories, names in os.walk(root):
        directories[:] = [name for name in directories if name not in {"build", ".gradle", ".git", "node_modules"}]
        if any((Path(base) / name).is_symlink() for name in directories):
            raise ValueError("Qualification source directory links are unsupported")
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
    contracts = ROOT / f".dev/maven/io/github/olo-labs/toolgate-contracts/{version}/toolgate-contracts-{version}.jar"
    if report.get("toolgateContractsSha256") != hashlib.sha256(contracts.read_bytes()).hexdigest():
        raise ValueError("Knowledge qualification used a different contracts artifact")
    expected = {"toolgate-service-authorization": report["toolgateSdkSha256"],
                "toolgate-contracts": report["toolgateContractsSha256"]}
    resolved = report.get("resolvedToolgateArtifacts", {})
    if set(resolved) != set(modules) or any(value != expected for value in resolved.values()):
        raise ValueError("Knowledge application classpaths used different ToolGate artifacts")
    return report



RUNTIME_PATHS = ["apps", "backend-libs", "packages", "tool-sdk", "config", "Cargo.toml", "Cargo.lock",
                 "rust-toolchain.toml", "gradle", "gradlew", "gradlew.bat", "gradle.properties",
                 "build.gradle.kts", "settings.gradle.kts", "VERSION", "LICENSE", "NOTICE.md",
                 "tools/ui", "tools/contracts", "tools/gateway", "tools/control", "tools/quickstart"]

def stack_image(image):
    value = json.loads(subprocess.check_output(["docker", "image", "inspect", image], text=True))[0]
    labels = value["Config"].get("Labels") or {}
    revision = labels.get("org.opencontainers.image.revision", "")
    if labels.get("io.olo.qualification.owner") != "olo-knowledge" or not re.fullmatch("[a-f0-9]{40}", revision):
        raise ValueError("A source-labelled owned qualification image is required")
    subprocess.run(["git", "cat-file", "-e", revision + "^{commit}"], cwd=ROOT, check=True, capture_output=True)
    changed = subprocess.check_output(["git", "diff", "--name-only", revision, "--", *RUNTIME_PATHS], cwd=ROOT, text=True)
    untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *RUNTIME_PATHS], cwd=ROOT, text=True)
    if changed.strip() or untracked.strip():
        raise ValueError("Runtime source differs from the selected image revision; rebuild from a clean source checkpoint")
    return value

def installed_artifacts(knowledge):
    result = {}
    for name in ("memory", "rag"):
        folder = knowledge / f"olo-{name}/build/install/olo-{name}/lib"
        artifacts = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(folder.glob("*.jar"))}
        if not artifacts:
            raise ValueError("Both installed service distributions are required")
        result[name] = artifacts
    return result

def verify_stack(knowledge, image):
    report = json.loads((knowledge / "build/qualification/real-stack.json").read_text())
    fencing = report.get("fencedEffects", {})
    if not report.get("passed") or not fencing.get("passed"):
        raise ValueError("Actual Control/target fencing qualification has not passed")
    if report.get("knowledgeSourceSha256") != source_digest(knowledge):
        raise ValueError("Full-stack knowledge source differs from executed evidence")
    if report.get("installedArtifacts") != installed_artifacts(knowledge):
        raise ValueError("Installed target artifacts differ from full-stack evidence")
    selected = stack_image(image)
    if report.get("imageId") != selected["Id"] or report.get("toolgateRevision") != selected["Config"]["Labels"]["org.opencontainers.image.revision"]:
        raise ValueError("Full-stack runtime image differs from executed evidence")
    summaries = fencing.get("summary", {})
    if set(summaries) != {"memory", "rag"} or any(value.get("completed", 0) < 1 or value.get("pendingDelivery") != 0 for value in summaries.values()):
        raise ValueError("Both target services must have settled Control-confirmed outcomes")
    targets = fencing.get("targets", {})
    records = fencing.get("control", [])
    grants = {row["auth_nonce"]: row for row in records}
    if set(targets) != {"memory", "rag"} or len(grants) != len(records):
        raise ValueError("Complete target and unique Control records are required")
    for name, rows in targets.items():
        done = [row for row in rows if row.get("state") == "DONE"]
        if len(done) != summaries[name]["completed"]:
            raise ValueError("Fencing summary disagrees with actual target records")
        for row in done:
            grant = grants.get(row["auth_nonce"])
            if (grant is None or grant["effect_id"] != row["effect_id"]
                    or grant["generation"] != row["authorization_generation"]
                    or grant["consumer"] != "olo_" + name or grant["state"] != "COMPLETED"
                    or row.get("outcome") != "COMPLETED" or row.get("delivered") != 1
                    or not re.fullmatch("sha256:[a-f0-9]{64}", row.get("result_digest", ""))):
                raise ValueError("Committed target records do not prove settled Control outcomes")
    images = report.get("serviceImageIds", {})
    if set(images) != {"memory", "rag"} or any(not re.fullmatch("sha256:[a-f0-9]{64}", value) for value in images.values()):
        raise ValueError("Actual service image identities are required")
    if not re.fullmatch("sha256:[a-f0-9]{64}", report.get("embeddingDigest", "")):
        raise ValueError("Actual embedding artifact identity is required")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--knowledge-source", required=True, type=Path, help="The olo-mono/olo-knowledge component checkout")
    parser.add_argument("--gradle", help="Gradle 8.12 executable for the external component")
    parser.add_argument("--verify-only", action="store_true", help="Check previously executed source/SDK-bound component evidence")
    parser.add_argument("--full-stack", action="store_true", help="Also execute/verify the owned actual Control/Gateway target fixture")
    parser.add_argument("--stack-image", default="olo-toolgate-quickstart:olo-m8")
    parser.add_argument("--package-gradle", help="Gradle 9.7.1 for package staging; defaults to the ToolGate wrapper")
    args = parser.parse_args()
    knowledge = args.knowledge_source.resolve()
    version = (ROOT / "VERSION").read_text().strip()
    output = ROOT / "build/security/knowledge/conformance.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    runner_before = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    result = {"passed": False, "runnerSourceSha256": runner_before, "scope": "Actual memory/RAG component qualification with scripted Control; excludes full-stack and production SLO acceptance",
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
        stack_report = None
        if args.full_stack:
            stack_image(args.stack_image)
            if not args.verify_only:
                wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
                subprocess.run([args.gradle, "--no-parallel", "--max-workers=2", ":olo-memory:installDist", ":olo-rag:installDist"],
                               cwd=knowledge, env=environment, check=True)
                subprocess.run([sys.executable, "tools/build_packages.py", "--gradle", args.package_gradle or str(wrapper),
                                "--image", args.stack_image], cwd=knowledge, env=environment, check=True)
                subprocess.run([sys.executable, "tools/qualification_stack.py", "--image", args.stack_image],
                               cwd=knowledge, env=environment, check=True)
            stack_report = verify_stack(knowledge, args.stack_image)
        if source_digest(ROOT / "backend-libs/service-authorization") != before:
            raise ValueError("Target verifier source changed during qualification")
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != runner_before:
            raise ValueError("Qualification runner source changed during execution")
        result.update(passed=True, verifierSourceSha256=before, knowledge=report)
        if stack_report is not None:
            result.update(fullStack=stack_report, scope="Owned actual Control/Gateway/Tool Host and memory/RAG development fixture; excludes CNI and production SLO acceptance")
        print("Knowledge target qualification verified; all component tests executed")
    except BaseException as failure:
        result["failureType"] = type(failure).__name__
        raise
    finally:
        output.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
