# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Version 1 author API for operator-installed native graphs. No wire credentials."""
from dataclasses import dataclass, field
import json
import math
from typing import Protocol


class JsonTooLarge(ValueError):
    """JSON byte limit exceeded, without retaining the value."""


def json_value(value, *, max_bytes=98304):
    """Copy bounded JSON without coercing tuples, objects, keys or non-finite numbers."""
    count = 0

    def visit(item, depth):
        nonlocal count
        count += 1
        if count > 8192 or depth > 32:
            raise ValueError("JSON value exceeds structural limits")
        if item is None or type(item) in (str, bool, int):
            return
        if type(item) is float and math.isfinite(item):
            return
        if type(item) is list:
            for child in item:
                visit(child, depth + 1)
            return
        if type(item) is dict and all(type(key) is str for key in item):
            for child in item.values():
                visit(child, depth + 1)
            return
        raise ValueError("Expected strict JSON values")

    visit(value, 0)
    raw = json.dumps(value, allow_nan=False, ensure_ascii=True, separators=(",", ":"))
    if len(raw.encode()) > max_bytes:
        raise JsonTooLarge("JSON value exceeds byte limit")
    return json.loads(raw)


class Models(Protocol):
    async def invoke(self, agent_id: str, messages: list[dict]) -> dict: ...


class Tools(Protocol):
    async def call(self, agent_id: str, tool_ref: str, arguments: dict) -> object: ...


class Agents(Protocol):
    async def invoke(self, agent_id: str, value: object, *, result_mode: str = "MODEL") -> object: ...


@dataclass(frozen=True, repr=False)
class RunContext:
    """Run-scoped capabilities; never put this object into graph state or static config.

    Python object privacy is not a security sandbox. Only operator-installed code
    is loaded. Implementations retain credentials outside this author-facing API.
    """
    models: Models = field(repr=False)
    tools: Tools = field(repr=False)
    agents: Agents = field(repr=False)
