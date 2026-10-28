# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Build-time errors with the place in the source they refer to."""

from __future__ import annotations

from typing import List, Optional


class Site:
    """A place in the tool source: a file, a line and the name of the thing there."""

    def __init__(self, file: str, line: int, name: str = "") -> None:
        self.file = file
        self.line = line
        self.name = name

    @classmethod
    def of(cls, declaration: object, name: str = "") -> "Site":
        return cls(getattr(declaration, "file", "<unknown>"), getattr(declaration, "line", 0), name)

    def child(self, name: str) -> "Site":
        return Site(self.file, self.line, name)

    def __str__(self) -> str:
        return "%s:%d" % (self.file, self.line)


class Problem(Exception):
    """One declaration error. The build collects them and fails with all of them."""

    def __init__(self, site: Optional[Site], message: str) -> None:
        super().__init__(message)
        self.site = site
        self.message = message

    def __str__(self) -> str:
        where = "" if self.site is None else str(self.site) + ": "
        return "toolgate: " + where + self.message


class BuildFailed(Exception):
    """The descriptor build found declaration errors."""

    def __init__(self, problems: List[Problem]) -> None:
        super().__init__("\n".join(str(p) for p in problems))
        self.problems = list(problems)

    @property
    def errors(self) -> str:
        return str(self)
