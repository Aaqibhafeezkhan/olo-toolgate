# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shared helpers for the SDK's own tests: fixture paths and building throwaway tool packages from source text."""

from __future__ import annotations

import importlib
import itertools
import sys
import tempfile
import textwrap
from pathlib import Path
from typing import Dict, List, Optional

HERE = Path(__file__).resolve().parent
SDK = HERE.parent
REPO = SDK.parent.parent
FIXTURES = HERE / "fixtures"
VECTORS = REPO / "tool-sdk" / "spec" / "descriptor" / "vectors"
CONFORMANCE = REPO / "tool-sdk" / "conformance"

if str(SDK / "src") not in sys.path:
    sys.path.insert(0, str(SDK / "src"))

from toolgate.descriptor import Build, BuildFailed, build  # noqa: E402

_counter = itertools.count()
_TEMP = Path(tempfile.mkdtemp(prefix="toolgate-tests-"))
if str(_TEMP) not in sys.path:
    sys.path.insert(0, str(_TEMP))


def fixture_modules(name: str) -> List:
    """Imports a fixture package under tests/fixtures/<name> with its submodules."""
    root = FIXTURES / name
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    out = []
    for package in sorted(p for p in root.iterdir() if (p / "__init__.py").is_file()):
        out.append(importlib.import_module(package.name))
        for module in sorted(package.glob("*.py")):
            if module.stem != "__init__":
                out.append(importlib.import_module(package.name + "." + module.stem))
    return out


class Result:
    def __init__(self, build: Optional[Build], failure: Optional[Exception], modules: List) -> None:
        self.build = build
        self.failure = failure
        self.modules = modules

    @property
    def success(self) -> bool:
        return self.build is not None

    @property
    def errors(self) -> str:
        return "" if self.failure is None else str(self.failure)

    def descriptor(self, tool: str) -> str:
        assert self.build is not None, self.errors
        return self.build.bytes[tool].decode("utf-8")


def compile_package(files: Dict[str, str], generation: Optional[object] = 1) -> Result:
    """Writes the files (module path to source) into a fresh package and builds it.

    Keys are module names relative to the package, such as ``__init__`` or ``tools``.
    """
    package = "tgtest%d" % next(_counter)
    directory = _TEMP / package
    directory.mkdir()
    for name, source in files.items():
        (directory / (name + ".py")).write_text(textwrap.dedent(source), encoding="utf-8")
    if "__init__" not in files:
        (directory / "__init__.py").write_text("", encoding="utf-8")
    importlib.invalidate_caches()
    modules = []
    try:
        modules.append(importlib.import_module(package))
        for name in sorted(files):
            if name != "__init__":
                modules.append(importlib.import_module(package + "." + name))
    except Exception as e:  # an import-time error, such as an unknown keyword argument
        return Result(None, e, modules)
    try:
        return Result(build(modules, generation), None, modules)
    except BuildFailed as e:
        return Result(None, e, modules)


TEMPLATE = '''\
from dataclasses import dataclass
from typing import Annotated, Any, Dict, List, Optional
from toolgate import *

tool_package(id="acme.demo", name="Demo", publisher="Acme")
destination(id="api", host_setting="ApiHost")
http_route(id="items.get", destination="api", method=HttpMethod.GET, path="/items/{id:[0-9]+}")
%s


@dataclass
class Out:
    value: str


class Tools:
%s
'''


def tools(annotations: str, body: str, generation: Optional[object] = 1) -> Result:
    """A one-module package for declaration tests: module-level statements, then the body of class Tools."""
    body = textwrap.indent(textwrap.dedent(body), "    ")
    return compile_package({"__init__": TEMPLATE % (textwrap.dedent(annotations), body)}, generation)
