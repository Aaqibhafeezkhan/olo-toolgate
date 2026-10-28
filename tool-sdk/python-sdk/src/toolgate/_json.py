# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""JSON helpers the protocol layer and runtime share.

Values are plain Python JSON (dict, list, str, int, float, bool, None). ``bool`` is never a number here, although
Python makes it an ``int``.
"""

from __future__ import annotations

import datetime as _dt
import json
from decimal import Decimal
from typing import Any, Optional

__all__ = ["loads", "dumps", "dumpb", "text", "is_int", "is_number", "is_object", "is_array", "is_string",
           "decimal", "instant_text", "parse_instant", "now", "MISSING"]


class _Missing:
    __slots__ = ()

    def __repr__(self) -> str:
        return "MISSING"


#: A path that leads nowhere, as Jackson's MissingNode.
MISSING = _Missing()


def _refuse_constant(name: str) -> Any:
    raise ValueError("JSON has no " + name)


def loads(data: "str | bytes") -> Any:
    """Parses JSON; NaN and Infinity are refused, as strict JSON parsers do."""
    if isinstance(data, (bytes, bytearray)):
        data = bytes(data).decode("utf-8")
    return json.loads(data, parse_constant=_refuse_constant)


def dumps(value: Any) -> str:
    """Compact JSON with UTF-8 characters kept as they are, in insertion order."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def dumpb(value: Any) -> bytes:
    return dumps(value).encode("utf-8")


def is_int(v: Any) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def is_number(v: Any) -> bool:
    return isinstance(v, (int, float, Decimal)) and not isinstance(v, bool)


def is_object(v: Any) -> bool:
    return isinstance(v, dict)


def is_array(v: Any) -> bool:
    return isinstance(v, list)


def is_string(v: Any) -> bool:
    return isinstance(v, str)


def decimal(v: Any) -> Decimal:
    """A JSON number as a Decimal; floats through their shortest repr."""
    if isinstance(v, Decimal):
        return v
    if isinstance(v, float):
        return Decimal(repr(v))
    return Decimal(int(v))


def text(v: Any, default: str = "") -> str:
    """Jackson's ``asText``: a string's value, the JSON text of a number or boolean, else ``default``."""
    if v is None or v is MISSING:
        return default
    if isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return dumps(v)
    if isinstance(v, Decimal):
        return str(v)
    return default


def now() -> _dt.datetime:
    return _dt.datetime.now(_dt.timezone.utc)


def instant_text(t: _dt.datetime) -> str:
    """ISO-8601 in UTC as Java's ``Instant.toString``: fractions in groups of three digits, none when zero."""
    t = t.astimezone(_dt.timezone.utc)
    base = t.strftime("%Y-%m-%dT%H:%M:%S")
    us = t.microsecond
    if us == 0:
        return base + "Z"
    if us % 1000 == 0:
        return base + ".%03dZ" % (us // 1000)
    return base + ".%06dZ" % us


def parse_instant(s: str) -> _dt.datetime:
    value = s[:-1] + "+00:00" if s.endswith("Z") else s
    head, _, rest = value.partition(".")
    if rest:
        digits = ""
        i = 0
        while i < len(rest) and rest[i].isdigit():
            digits += rest[i]
            i += 1
        value = head + "." + (digits + "000000")[:6] + rest[i:]
    out = _dt.datetime.fromisoformat(value)
    return out.astimezone(_dt.timezone.utc)


def path(v: Any, *keys: Any) -> Any:
    """Jackson's ``path`` chain: MISSING wherever a key or index is absent."""
    for k in keys:
        if isinstance(k, int) and isinstance(v, list):
            v = v[k] if 0 <= k < len(v) else MISSING
        elif isinstance(v, dict):
            v = v.get(k, MISSING)
        else:
            return MISSING
    return v


def get(v: Any, key: str) -> Optional[Any]:
    """The member, or None when absent (a JSON null comes back as None too)."""
    return v.get(key) if isinstance(v, dict) else None


def as_bool(v: Any, default: bool = False) -> bool:
    """Jackson's ``asBoolean``."""
    if isinstance(v, bool):
        return v
    if is_int(v):
        return v != 0
    if isinstance(v, str):
        return v.strip() == "true" if v.strip() in ("true", "false") else default
    return default


def as_long(v: Any, default: int = 0) -> int:
    """Jackson's ``asLong``."""
    if isinstance(v, bool):
        return 1 if v else 0
    if is_int(v):
        return v
    if isinstance(v, float):
        return int(v)
    if isinstance(v, str):
        try:
            return int(v.strip())
        except ValueError:
            try:
                return int(float(v.strip()))
            except ValueError:
                return default
    return default
