# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Build/check immutable text graph directories; installation is privileged operator work."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def build(source, destination, *, check=False):
    source, destination = Path(source), Path(destination)
    manifest = json.loads((source / "package.json").read_text(encoding="utf-8"))
    if set(manifest) != {"format", "packageId", "version", "runtimeApi", "dependencies", "capabilities", "factories"}:
        raise ValueError("Unsupported graph package declaration")
    files = {}
    # Path ordering follows host case rules; package bytes must not.
    for path in sorted(source.rglob("*"), key=lambda path: path.relative_to(source).as_posix()):
        if path.is_symlink():
            raise ValueError("Graph package links are unsupported")
        if not path.is_file() or path.name == "package.json":
            continue
        relative = path.relative_to(source).as_posix()
        if path.suffix not in (".py", ".json", ".md") or not re.fullmatch(r"[A-Za-z0-9_./-]{1,160}", relative):
            raise ValueError("Only declared text graph sources are supported")
        files[relative] = path.read_text(encoding="utf-8").encode("utf-8")
    if not 2 <= len(files) <= 64 or "README.md" not in files or sum(map(len, files.values())) > 1048576:
        raise ValueError("Graph package requires bounded sources and README.md")
    manifest["files"] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()
    digest = hashlib.sha256(raw).hexdigest()
    target = destination / digest
    outputs = {**files, "manifest.json": (json.dumps(manifest, indent=2) + "\n").encode()}
    if target.exists():
        actual = {path.relative_to(target).as_posix() for path in target.rglob("*") if path.is_file()}
        if actual != set(outputs) or any((target / name).read_bytes() != data for name, data in outputs.items()):
            raise ValueError("Installed graph content differs; immutable directories are never overwritten")
    elif check:
        raise ValueError("Generated graph package is missing")
    else:
        target.mkdir(parents=True)
        for name, data in outputs.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    return {"packageId": manifest["packageId"], "version": manifest["version"], "digest": "sha256:" + digest,
            "entryPoint": next(iter(manifest["factories"])), "config": {}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(json.dumps(build(args.source, args.destination, check=args.check), indent=2))


if __name__ == "__main__":
    main()
