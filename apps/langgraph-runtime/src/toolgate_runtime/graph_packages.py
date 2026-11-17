# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Digest-pinned, operator-installed graph directories. This is not a Python sandbox."""
from contextlib import contextmanager
from importlib import metadata
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import types
import uuid

from jsonschema import Draft202012Validator
from langgraph.graph import StateGraph

from .graph_api import RunContext, json_value

API_VERSION = 1
CAPABILITIES = frozenset({"json-state", "agents", "models", "tools", "async", "branches", "parallel-compute", "subgraphs"})
DEFAULT_DIRECTORY = Path(__file__).with_name("graphs")


class GraphRefused(ValueError):
    """Constant diagnostics only: no package code, inputs, paths or provider bodies."""


def _object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise GraphRefused("GRAPH_DUPLICATE_MEMBER")
        value[key] = item
    return value


def canonical(value):
    return json.dumps(json_value(value), sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def manifest_digest(manifest):
    return "sha256:" + hashlib.sha256(canonical(manifest)).hexdigest()


def schema_validator(schema):
    json_value(schema, max_bytes=65536)
    if not isinstance(schema, dict):
        raise GraphRefused("GRAPH_INVALID_SCHEMA")

    def local_refs(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ("$ref", "$dynamicRef") and (not isinstance(item, str) or not item.startswith("#")):
                    raise GraphRefused("GRAPH_REMOTE_SCHEMA_REFUSED")
                local_refs(item)
        elif isinstance(value, list):
            for item in value:
                local_refs(item)

    local_refs(schema)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def validate_selection(selection):
    if not isinstance(selection, dict) or set(selection) != {"packageId", "version", "digest", "entryPoint", "config"}:
        raise GraphRefused("GRAPH_SELECTION_REQUIRED")
    for key, pattern in (("packageId", r"[a-z][a-z0-9.-]{0,127}"), ("version", r"[0-9]+\.[0-9]+\.[0-9]+"),
                         ("digest", r"sha256:[a-f0-9]{64}"), ("entryPoint", r"[a-z][a-z0-9_-]{0,47}")):
        if not isinstance(selection[key], str) or not re.fullmatch(pattern, selection[key]):
            raise GraphRefused("GRAPH_INVALID_SELECTION")
    if not isinstance(selection["config"], dict) or len(selection["config"]) > 64:
        raise GraphRefused("GRAPH_INVALID_CONFIG")
    json_value(selection["config"], max_bytes=16384)


class GraphPackages:
    def __init__(self, directory=None):
        self.directory = Path(directory) if directory is not None else None

    def _verified(self, selection, *, validate_config=True):
        validate_selection(selection)
        roots = [DEFAULT_DIRECTORY]
        if self.directory is not None:
            roots.append(self.directory)
        matches = []
        for root in roots:
            if not root.is_dir():
                continue
            # Only a digest directory is selected; never scan/import arbitrary Python paths.
            package = root / selection["digest"][7:]
            if not package.exists():
                continue
            if root.is_symlink() or package.is_symlink() or not package.is_dir():
                raise GraphRefused("GRAPH_PACKAGE_LINK_REFUSED")
            matches.append(package)
        if len(matches) != 1:
            raise GraphRefused("GRAPH_PACKAGE_UNAVAILABLE")
        package = matches[0]
        manifest_path = package / "manifest.json"
        if manifest_path.is_symlink() or not manifest_path.is_file() or manifest_path.stat().st_size > 65536:
            raise GraphRefused("GRAPH_INVALID_MANIFEST")
        manifest = json.loads(manifest_path.read_bytes(), object_pairs_hook=_object)
        if (set(manifest) != {"format", "packageId", "version", "runtimeApi", "dependencies", "capabilities", "factories", "files"}
                or manifest["format"] != 1 or manifest["runtimeApi"] != API_VERSION
                or manifest["packageId"] != selection["packageId"] or manifest["version"] != selection["version"]
                or manifest_digest(manifest) != selection["digest"]):
            raise GraphRefused("GRAPH_MANIFEST_MISMATCH")
        caps = manifest["capabilities"]
        if not isinstance(caps, list) or any(not isinstance(c, str) for c in caps) or not set(caps) <= CAPABILITIES:
            raise GraphRefused("GRAPH_UNSUPPORTED_CAPABILITY")
        deps = manifest["dependencies"]
        if not isinstance(deps, dict) or len(deps) > 32:
            raise GraphRefused("GRAPH_INVALID_DEPENDENCIES")
        for name, version in deps.items():
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name) or not isinstance(version, str):
                raise GraphRefused("GRAPH_INVALID_DEPENDENCIES")
            try:
                if metadata.version(name) != version:
                    raise GraphRefused("GRAPH_DEPENDENCY_MISMATCH")
            except metadata.PackageNotFoundError:
                raise GraphRefused("GRAPH_DEPENDENCY_MISSING") from None
        files = manifest["files"]
        if not isinstance(files, dict) or not 2 <= len(files) <= 64 or "README.md" not in files:
            raise GraphRefused("GRAPH_INVALID_FILES")
        sources = {}
        total = 0
        for name, digest in files.items():
            path = PurePosixPath(name)
            if (not re.fullmatch(r"[A-Za-z0-9_./-]{1,160}", name) or str(path) != name
                    or path.is_absolute() or any(part in (".", "..") for part in path.parts)
                    or path.suffix not in (".py", ".json", ".md") or name == "manifest.json"
                    or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest)):
                raise GraphRefused("GRAPH_INVALID_FILE")
            target = package.joinpath(*path.parts)
            if any(parent.is_symlink() for parent in [target, *target.parents] if parent != package.parent):
                raise GraphRefused("GRAPH_PACKAGE_LINK_REFUSED")
            if not target.is_file() or target.stat().st_size > 262144:
                raise GraphRefused("GRAPH_FILE_UNAVAILABLE")
            content = target.read_bytes().replace(b"\r\n", b"\n")
            total += len(content)
            if total > 1048576 or hashlib.sha256(content).hexdigest() != digest:
                raise GraphRefused("GRAPH_FILE_MISMATCH")
            sources[name] = content
        actual = {p.relative_to(package).as_posix() for p in package.rglob("*") if p.is_file()}
        if actual != set(files) | {"manifest.json"}:
            raise GraphRefused("GRAPH_UNDECLARED_FILE")
        factories = manifest["factories"]
        if not isinstance(factories, dict) or not 1 <= len(factories) <= 16:
            raise GraphRefused("GRAPH_INVALID_FACTORY")
        factory = factories.get(selection["entryPoint"])
        if (not isinstance(factory, dict) or set(factory) != {"file", "function", "configSchema", "outputSchema"}
                or factory["file"] not in sources or not factory["file"].endswith(".py")
                or not isinstance(factory["function"], str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", factory["function"])):
            raise GraphRefused("GRAPH_INVALID_FACTORY")
        validator = schema_validator(factory["configSchema"])
        if validate_config and not validator.is_valid(selection["config"]):
            raise GraphRefused("GRAPH_CONFIG_SCHEMA")
        return sources, factory, schema_validator(factory["outputSchema"])

    def installed_digests(self):
        """Bounded verified installation inventory; never import a factory to advertise it."""
        from itertools import islice
        found=set()
        roots=[DEFAULT_DIRECTORY]+([self.directory] if self.directory is not None else [])
        for root in roots:
            if not root.is_dir() or root.is_symlink():
                continue
            entries=list(islice(root.iterdir(),257))
            if len(entries)>256:
                raise GraphRefused('GRAPH_INVENTORY_LIMIT')
            for path in entries:
                if not re.fullmatch(r'[a-f0-9]{64}',path.name) or not path.is_dir() or path.is_symlink():
                    continue
                manifest_path=path/'manifest.json'
                try:
                    if manifest_path.is_symlink() or manifest_path.stat().st_size>65536:
                        continue
                    manifest=json.loads(manifest_path.read_bytes(),object_pairs_hook=_object)
                    factory=next(iter(manifest['factories']))
                    selection=dict(packageId=manifest['packageId'],version=manifest['version'],
                        digest='sha256:'+path.name,entryPoint=factory,config={})
                    self._verified(selection,validate_config=False)
                    found.add(selection['digest'])
                except (OSError,ValueError,KeyError,TypeError,StopIteration,GraphRefused):
                    continue
                if len(found)>128:
                    raise GraphRefused('GRAPH_INVENTORY_LIMIT')
        return tuple(sorted(found))

    @contextmanager
    def load(self, selection):
        """Verify all bytes before execution; execute the captured bytes, not reopened paths.

        Fresh module objects are owned by this invocation. Relative package imports
        are deliberately not supported by API v1; authors may declare multiple
        factories in one file or qualified dependencies installed in the image.
        """
        sources, factory, output = self._verified(selection)
        name = "_toolgate_graph_" + uuid.uuid4().hex
        module = types.ModuleType(name)
        sys.modules[name] = module
        try:
            exec(compile(sources[factory["file"]], "<installed-graph>", "exec"), module.__dict__)
            build = getattr(module, factory["function"], None)
            if not callable(build):
                raise GraphRefused("GRAPH_INVALID_FACTORY")
            builder = build(json_value(selection["config"]))
            if not isinstance(builder, StateGraph) or builder.compiled or builder.context_schema is not RunContext:
                raise GraphRefused("GRAPH_UNBOUND_BUILDER_REQUIRED")
            yield builder.compile(), output
        finally:
            sys.modules.pop(name, None)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Check a profile's installed graph before deployment")
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--workflow", required=True)
    parser.add_argument("--graph-directory", type=Path)
    args = parser.parse_args()
    try:
        document = json.loads(args.profile.read_bytes(), object_pairs_hook=_object)
        workflows = document["workflows"]["workflows"]
        selected = [workflow for workflow in workflows if workflow["id"] == args.workflow]
        if len(selected) != 1:
            raise GraphRefused("GRAPH_WORKFLOW_UNAVAILABLE")
        selection = selected[0]["graph"]
        GraphPackages(args.graph_directory)._verified(selection)
        print(json.dumps({"state": "GRAPH_PACKAGE_READY", "packageId": selection["packageId"], "digest": selection["digest"]}))
        return 0
    except Exception:
        print(json.dumps({"state": "GRAPH_PACKAGE_REFUSED"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
