# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Deterministic executable staging; the existing Rust CLI remains the single .tgpkg packager."""
from __future__ import annotations

import contextlib
import email
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

from ._version import SDK_VERSION, CONTRACTS_VERSION
from .descriptor import build_project

BOOTSTRAP = '''# Generated ToolGate Python executable. Native wheel contents are loaded from a private temporary directory.
import atexit, os, shutil, sys, tempfile, zipfile
root = tempfile.mkdtemp(prefix="toolgate-python-")
atexit.register(shutil.rmtree, root, ignore_errors=True)
with zipfile.ZipFile(sys.argv[0]) as archive:
    for entry in archive.infolist():
        parts = entry.filename.split("/")
        if entry.filename.startswith("/") or any(p in ("..", ".") or ":" in p or "\\\\" in p for p in parts):
            raise RuntimeError("Invalid executable entry")
        archive.extract(entry, root)
sys.path.insert(0, root)
from toolgate.__main__ import main
raise SystemExit(main(["run"]))
'''


def _add(entries, name, data):
    path = PurePosixPath(name)
    if path.is_absolute() or any(p in {"..", "."} or ":" in p or "\\" in p for p in path.parts):
        raise ValueError("Invalid executable entry " + name)
    if name in entries and entries[name] != data:
        raise ValueError("Conflicting executable entry " + name)
    entries[name] = data


def stage(root=None, output=None):
    with contextlib.redirect_stdout(sys.stderr):
        project, modules, result = build_project(root)
    if re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", project.version) is None:
        raise ValueError("The package version must be SemVer")
    out = Path(output or project.build_dir / "staging").resolve()
    out.mkdir(parents=True, exist_ok=True)
    (out / "artifacts").mkdir(exist_ok=True)
    entries = {"__main__.py": BOOTSTRAP.encode("utf-8")}
    sdk = Path(__file__).resolve().parent
    for path in sorted(sdk.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix not in {".pyc", ".pyo"}:
            _add(entries, "toolgate/" + path.relative_to(sdk).as_posix(), path.read_bytes())
    for name in project.packages + project.modules:
        source = project.source.joinpath(*name.split("."))
        for parent in source.parents:
            if parent == project.source:
                break
            init = parent / "__init__.py"
            if init.is_file():
                _add(entries, init.relative_to(project.source).as_posix(), init.read_bytes())
        sources = source.rglob("*") if source.is_dir() else [source.with_suffix(".py")]
        for path in sorted(sources):
            if path.is_file() and "__pycache__" not in path.parts and path.suffix not in {".pyc", ".pyo"}:
                if not path.resolve().is_relative_to(project.source):
                    raise ValueError("A tool source file escapes the source directory")
                _add(entries, path.relative_to(project.source).as_posix(), path.read_bytes())
    dependencies = project.settings.get("requirements_lock")
    if project.settings.get("requirements_lock") is None and project.settings.get("vendor_dependencies", False):
        raise ValueError("Vendoring needs requirements_lock with pinned wheel hashes")
    from .descriptor.project import read_toml
    metadata = read_toml(project.root / "pyproject.toml").get("project", {})
    runtime_deps = [d for d in metadata.get("dependencies", []) if not re.match(r"toolgate-sdk(?:\s*[=<>!~;]|\s*$)", d, re.I)]
    if runtime_deps and not dependencies:
        raise ValueError("Tool dependencies need [tool.toolgate].requirements_lock with pinned hashes")
    if dependencies:
        lock = (project.root / dependencies).resolve()
        if not lock.is_relative_to(project.root):
            raise ValueError("requirements_lock must be inside the project")
        with tempfile.TemporaryDirectory(prefix="toolgate-wheels-") as temp:
            command = [sys.executable, "-m", "pip", "download", "--disable-pip-version-check", "--require-hashes",
                       "--only-binary=:all:", "--dest", temp, "-r", str(lock)]
            if project.settings.get("wheelhouse"):
                command.extend(["--no-index", "--find-links", str(project.root / project.settings["wheelhouse"])])
            subprocess.run(command, check=True, stdout=sys.stderr)
            try:
                from packaging.requirements import Requirement
            except ImportError:
                raise ValueError("Validating locked dependencies requires toolgate-sdk[build]")
            resolved = {}
            for wheel in sorted(Path(temp).glob("*.whl")):
                with zipfile.ZipFile(wheel) as archive:
                    meta_name = next(n for n in archive.namelist() if n.endswith(".dist-info/METADATA"))
                    meta = email.message_from_bytes(archive.read(meta_name))
                    resolved[re.sub(r"[-_.]+", "-", meta["Name"]).lower()] = meta["Version"]
            for dependency in runtime_deps:
                required = Requirement(dependency)
                if required.marker and not required.marker.evaluate():
                    continue
                name = re.sub(r"[-_.]+", "-", required.name).lower()
                if name not in resolved or resolved[name] not in required.specifier:
                    raise ValueError("The lockfile does not satisfy project dependency " + dependency)
            for wheel in sorted(Path(temp).glob("*.whl")):
                with zipfile.ZipFile(wheel) as archive:
                    for entry in archive.infolist():
                        if entry.is_dir():
                            continue
                        name = entry.filename
                        if ".data/" in name:
                            _, target = name.split(".data/", 1)
                            scheme, _, name = target.partition("/")
                            if scheme not in {"purelib", "platlib"}:
                                raise ValueError("Unsupported wheel data scheme " + scheme)
                        _add(entries, name, archive.read(entry))
    _add(entries, "_toolgate_index/__init__.py", b"")
    _add(entries, "_toolgate_index/package.json", result.index_bytes)
    for name, data in result.bytes.items():
        _add(entries, "_toolgate_index/tools/" + name + ".json", data)
    artifact = out / "artifacts" / "tool.pyz"
    with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    manifest = {"schemaVersion": 2, "kind": "SDK", "id": result.index["id"], "version": project.version,
                "publisher": result.index["publisher"], "sdk": {"language": "PYTHON", "version": SDK_VERSION,
                    "protocol": {"min": 2, "max": 2}, "descriptorGeneration": 1},
                "compatibility": {"contractsVersion": CONTRACTS_VERSION},
                "runtimes": [{"runtimeId": "python", "language": "python", "version": "3.10"}],
                "tools": [{"descriptor": d, "runtimeId": "python", "entryPoint": "toolgate.runtime",
                           "artifactIds": ["tool"]} for d in result.descriptors.values()],
                "artifacts": [{"artifactId": "tool", "path": "artifacts/tool.pyz", "mediaType": "application/zip",
                               "runtimeId": "python"}], "credentialReferences": [], "connections": [],
                "composition": [], "limits": {"maxTools": 64, "maxDescriptorBytes": 1048576}}
    # Match the source contract version at SDK build time, never a moving repository at execution time.
    (out / "toolgate-package.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return out


def pack(root=None, output=None):
    from .descriptor.project import load_project
    project = load_project(root)
    staging = stage(project.root)
    destination = Path(output or project.build_dir / (project.name + "-" + project.version + ".tgpkg"))
    cli = os.environ.get("TOOLGATE_CLI", "toolgate")
    subprocess.run([cli, "pack", str(staging), "--output", str(destination)], check=True, stdout=sys.stderr)
    subprocess.run([cli, "validate", str(destination)], check=True, stdout=sys.stderr)
    return destination
