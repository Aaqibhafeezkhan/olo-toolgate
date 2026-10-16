#!/usr/bin/env python3
# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Build configured SDK projects into sealed artifacts. Never upload or provision tenants."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "core-mcp"


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate field: {key}")
        result[key] = value
    return result


def inventory(path):
    document = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "packages"} or type(document["schemaVersion"]) is not int or document["schemaVersion"] != 1:
        raise ValueError("Invalid core package inventory")
    if not isinstance(document["packages"], list) or not document["packages"]:
        raise ValueError("Declare at least one core package")
    seen = set()
    projects = set()
    for package in document["packages"]:
        if not isinstance(package, dict) or set(package) != {"project", "sdk", "id", "version"} or any(not isinstance(v, str) or not v for v in package.values()):
            raise ValueError("Invalid package entry")
        if package["sdk"] not in {"java-gradle", "json"}:
            raise ValueError("SDK build adapter is not implemented: " + package["sdk"])
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9._-]{0,127}", package["id"]) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?", package["version"]):
            raise ValueError("Invalid package identity")
        relative = Path(package["project"])
        project = (CORE / relative).resolve()
        if relative.is_absolute() or ".." in relative.parts or not project.is_relative_to(CORE) or project == CORE or not project.is_dir():
            raise ValueError("Package project must be a directory within core-mcp")
        if package["id"] in seen or project in projects:
            raise ValueError("Duplicate package or project")
        seen.add(package["id"])
        projects.add(project)
        yield package, project


def run(command, env):
    subprocess.run([str(arg) for arg in command], env=env, check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, default=CORE / "core-packages.build.json")
    parser.add_argument("--cli", type=Path, required=True, help="Verified ToolGate CLI binary")
    parser.add_argument("--output", type=Path, default=ROOT / "build/core-mcp")
    args = parser.parse_args()
    packages = list(inventory(args.inventory))  # Validate every entry before any build.
    cli = args.cli.resolve(strict=True)
    env = dict(os.environ, TOOLGATE_CLI=str(cli))
    gradle = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    prerequisites = []
    if any(p["sdk"] == "java-gradle" for p, _ in packages):
        prerequisites.append("publishJavaSdkLocally")
    if any(p["sdk"] == "json" for p, _ in packages):
        prerequisites.append(":sdk-json:installDist")
    run([gradle, "--console=plain", *prerequisites], env)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    lock = {"schemaVersion": 1, "sdkVersion": (ROOT / "VERSION").read_text().strip(), "packages": []}
    for package, project in packages:
        if package["sdk"] == "java-gradle":
            run([gradle, "-p", project, "--console=plain", "-PtoolgateSdkRepository=" + (ROOT / "build/sdk-repo").as_uri(), "clean", "check", "toolgatePackage"], env)
        else:
            sdk = ROOT / "tool-sdk/json-sdk/build/install/toolgate-json-sdk/bin" / ("toolgate-json-sdk.bat" if os.name == "nt" else "toolgate-json-sdk")
            run([sdk, "pack", project], env)
        artifacts = sorted((project / "build/toolgate").glob("*.tgpkg"))
        if len(artifacts) != 1:
            raise ValueError("Build must produce exactly one package: " + str(project))
        artifact = artifacts[0]
        run([cli, "validate", artifact], env)
        with zipfile.ZipFile(artifact) as archive:
            manifest = json.loads(archive.read("toolgate-package.json"), object_pairs_hook=strict_object)
            if manifest.get("id") != package["id"] or manifest.get("version") != package["version"]:
                raise ValueError("Built package identity does not match inventory")
            if manifest.get("sdk", {}).get("version") != lock["sdkVersion"]:
                raise ValueError("Package SDK version does not match the pinned build")
            checksums = archive.read("CHECKSUMS.sha256")
        filename = f"{package['id']}-{package['version']}.tgpkg"
        destination = output / filename
        if destination.parent != output:
            raise ValueError("Unsafe package identity")
        shutil.copyfile(artifact, destination)
        lock["packages"].append(dict(package, artifact=filename,
            artifactSha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
            packageDigest="sha256:" + hashlib.sha256(checksums).hexdigest()))
    temporary = output / "core-packages.lock.json.tmp"
    temporary.write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(output / "core-packages.lock.json")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
