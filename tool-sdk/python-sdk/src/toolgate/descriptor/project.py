# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The ``[tool.toolgate]`` table of a tool project's pyproject.toml, and importing the modules it names.

    [tool.toolgate]
    descriptor_generation = 1          # required; there is no default
    packages = ["acme_orders"]         # imported with every submodule
    modules = []                       # single modules, optional
    source = "src"                     # where the packages live; defaults to src/ when it exists, else the root
"""

from __future__ import annotations

import importlib
import os
import pkgutil
import sys
import types
from pathlib import Path
from typing import Any, Dict, List, Optional

from .problems import BuildFailed, Problem

__all__ = ["Project", "load_project", "import_modules", "read_toml"]


def read_toml(path: Path) -> Dict[str, Any]:
    try:
        import tomllib  # type: ignore[import-not-found]
    except ModuleNotFoundError:  # Python 3.10
        try:
            import tomli as tomllib  # type: ignore[import-not-found,no-redef]
        except ModuleNotFoundError:
            raise BuildFailed([Problem(None, "reading pyproject.toml on Python 3.10 needs tomli; pip install tomli")])
    with open(path, "rb") as f:
        return tomllib.load(f)


class Project:
    """A tool project: its root, its [project] metadata and its [tool.toolgate] settings."""

    def __init__(self, root: Path, data: Dict[str, Any]) -> None:
        self.root = root
        project = data.get("project", {})
        tool = data.get("tool", {}).get("toolgate")
        if not isinstance(tool, dict):
            raise BuildFailed([Problem(None, "pyproject.toml has no [tool.toolgate] table")])
        self.settings = tool
        self.name: str = project.get("name", root.name)
        self.version: str = str(project.get("version", "0.0.0"))
        self.generation: Any = tool.get("descriptor_generation")
        self.packages: List[str] = list(tool.get("packages", []))
        self.modules: List[str] = list(tool.get("modules", []))
        if not self.packages and not self.modules:
            raise BuildFailed([Problem(None, "[tool.toolgate] must list the tool code in packages or modules")])
        source = tool.get("source")
        if source is not None:
            self.source = (root / source).resolve()
        elif (root / "src").is_dir():
            self.source = (root / "src").resolve()
        else:
            self.source = root.resolve()

    @property
    def build_dir(self) -> Path:
        return self.root / "build" / "toolgate"


def load_project(root: Optional[os.PathLike] = None) -> Project:
    base = Path(root or os.getcwd()).resolve()
    path = base / "pyproject.toml"
    if not path.is_file():
        raise BuildFailed([Problem(None, "no pyproject.toml in " + str(base))])
    return Project(base, read_toml(path))


def import_modules(project: Project) -> List[types.ModuleType]:
    """Imports the project's packages (with their submodules) and modules, in a stable order."""
    source = str(project.source)
    if source not in sys.path:
        sys.path.insert(0, source)
    importlib.invalidate_caches()
    out: List[types.ModuleType] = []
    seen = set()

    def add(module: types.ModuleType) -> None:
        if module.__name__ not in seen:
            seen.add(module.__name__)
            out.append(module)

    for name in project.packages:
        package = importlib.import_module(name)
        add(package)
        path = getattr(package, "__path__", None)
        if path is None:
            continue
        for info in sorted(pkgutil.walk_packages(path, name + "."), key=lambda i: i.name):
            leaf = info.name.rsplit(".", 1)[-1]
            if leaf == "__main__":
                continue
            add(importlib.import_module(info.name))
    for name in project.modules:
        add(importlib.import_module(name))
    return out
