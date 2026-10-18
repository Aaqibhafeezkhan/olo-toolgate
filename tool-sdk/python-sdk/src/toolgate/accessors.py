# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Typed access to declared values: ``declared(context).globals.timeout_seconds``.

The Python counterpart of the Java processor's generated ``<HandlerClass>Vars`` classes. Each declared name is an
attribute in snake case, read with the getter for its declared type; the build refuses two names that would have
the same attribute. OBJECT and ARRAY values come back as their JSON form (dicts and lists).
"""

from __future__ import annotations

import keyword
import re
from typing import Any, Dict, Mapping

__all__ = ["declared", "attribute_name", "collision", "READERS"]

#: The context getter that reads each value type.
READERS = {
    "INT": "get_int", "LONG": "get_long", "NUMBER": "get_number", "BOOLEAN": "get_bool", "DATE": "get_date",
    "DATE_TIME": "get_datetime", "DURATION": "get_duration", "BINARY": "get_bytes", "OBJECT": "get_object",
    "ARRAY": "get_list",
}

_RESERVED = {"globals", "secrets", "jwt"}


def attribute_name(name: str) -> str:
    """The attribute for a declared name: snake case, never a Python keyword."""
    parts = []
    for part in re.split(r"_+", name):
        if not part:
            continue
        if part == part.upper():
            parts.append(part.lower())
            continue
        # Split camel case: maxItemsPerPage -> max_items_per_page, HTTPHost -> http_host.
        words = re.findall(r"[A-Z]+(?=[A-Z][a-z0-9]|$)|[A-Z]?[a-z0-9]+|[A-Z]+", part)
        parts.append("_".join(w.lower() for w in words))
    out = "_".join(p for p in parts if p)
    if keyword.iskeyword(out) or out in _RESERVED or out.startswith("__"):
        out += "_value"
    return out


def collision(values: Mapping[str, str]) -> "str | None":
    """The first pair of names with the same attribute, as ``a and b``, or None."""
    seen: Dict[str, str] = {}
    for name in values:
        prior = seen.setdefault(attribute_name(name), name)
        if prior != name:
            return prior + " and " + name
    return None


class _View:
    def __init__(self, values: Any, types: Mapping[str, str], what: str) -> None:
        object.__setattr__(self, "_values", values)
        object.__setattr__(self, "_names", {attribute_name(n): (n, t) for n, t in types.items()})
        object.__setattr__(self, "_what", what)

    def __getattr__(self, attribute: str) -> Any:
        entry = self._names.get(attribute)
        if entry is None:
            declared = ", ".join(sorted(self._names)) or "none"
            raise AttributeError("no declared %s has the attribute %s; declared: %s" % (self._what, attribute, declared))
        name, value_type = entry
        reader = READERS.get(value_type, "get_str")
        if reader in ("get_object", "get_list"):
            return getattr(self._values, reader)(name, object)
        return getattr(self._values, reader)(name)

    def __setattr__(self, attribute: str, value: Any) -> None:
        raise AttributeError("declared values are read-only")

    def __dir__(self):
        return sorted(self._names)


class Declared:
    """The declared globals, secrets and claims of the current tool."""

    def __init__(self, context: Any) -> None:
        facts = context._toolgate_declared()
        self.globals = _View(context.globals, facts.get("globals", {}), "global")
        self.secrets = _View(context.secrets, facts.get("secrets", {}), "secret")
        self.jwt = _View(context.jwt, facts.get("claims", {}), "claim")


def declared(context: Any) -> Declared:
    """Typed views over the values the handler's tool declares."""
    return Declared(context)
