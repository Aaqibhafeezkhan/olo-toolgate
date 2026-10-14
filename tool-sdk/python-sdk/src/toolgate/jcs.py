# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Canonical form of spec/descriptor section 3: every string NFC, then RFC 8785 JSON (keys by UTF-16 code units,
ECMAScript numbers and strings), as UTF-8 bytes. Values are dict, list, tuple, str, bool, int, float or None."""

from __future__ import annotations

import hashlib
import unicodedata
from decimal import Decimal
from typing import Any, Iterable, List

__all__ = ["canonical", "canonical_text", "sha256", "digest", "nfc", "sorted_utf16", "utf16_key", "number",
           "utf16_length", "SAFE_INTEGER", "protocol_canonical", "protocol_digest"]

SAFE_INTEGER = 9007199254740991

_ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def utf16_key(s: str) -> bytes:
    """A sort key that orders strings by UTF-16 code units, as RFC 8785 and Java's String.compareTo do."""
    return s.encode("utf-16-be", "surrogatepass")


def utf16_length(s: str) -> int:
    """The length of a string in UTF-16 code units, the unit every declaration limit counts in."""
    return len(s.encode("utf-16-le", "surrogatepass")) // 2


def sorted_utf16(values: Iterable[str]) -> List[str]:
    """NFC each string, then sort by UTF-16 code units."""
    return sorted((nfc(v) for v in values), key=utf16_key)


def _string(s: str) -> str:
    out = ['"']
    for c in s:
        e = _ESCAPES.get(c)
        if e is not None:
            out.append(e)
        elif ord(c) < 0x20:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    out.append('"')
    return "".join(out)


def number(v: float) -> str:
    """ECMAScript Number::toString with the shortest round-tripping digits."""
    if v != v or v in (float("inf"), float("-inf")):
        raise ValueError("not a finite number")
    if v == 0:
        return "0"
    sign = "-" if v < 0 else ""
    # repr gives the shortest digits that round-trip, which is what ECMAScript requires.
    d = Decimal(repr(abs(v))).normalize()
    t = d.as_tuple()
    digits = "".join(str(x) for x in t.digits)
    k = len(digits)
    n = k + t.exponent
    if k <= n <= 21:
        body = digits + "0" * (n - k)
    elif 0 < n <= 21:
        body = digits[:n] + "." + digits[n:]
    elif -6 < n <= 0:
        body = "0." + "0" * (-n) + digits
    else:
        e = n - 1
        body = digits[0] + ("." + digits[1:] if k > 1 else "") + "e" + ("+" if e >= 0 else "-") + str(abs(e))
    return sign + body


def _write(out: List[str], value: Any, descriptor: bool = True) -> None:
    if value is None:
        out.append("null")
    elif value is True:
        out.append("true")
    elif value is False:
        out.append("false")
    elif isinstance(value, int):
        if not descriptor:
            out.append(str(int(value)))
        elif abs(value) > SAFE_INTEGER:
            raise ValueError("integer beyond 2^53-1: %d" % value)
        out.append(str(int(value)))
    elif isinstance(value, float):
        out.append(number(value))
    elif not descriptor and isinstance(value, Decimal):
        out.append(number(float(value)))
    elif isinstance(value, str):
        out.append(_string(nfc(value) if descriptor else value))
    elif isinstance(value, dict):
        entries = {}
        for k, v in value.items():
            if not isinstance(k, str):
                raise ValueError("object keys must be strings: %r" % (k,))
            key = nfc(k) if descriptor else k
            if key in entries:
                raise ValueError("keys are equal after NFC: " + key)
            entries[key] = v
        out.append("{")
        first = True
        for key in sorted(entries, key=utf16_key):
            if not first:
                out.append(",")
            first = False
            out.append(_string(key))
            out.append(":")
            _write(out, entries[key], descriptor)
        out.append("}")
    elif isinstance(value, (list, tuple)):
        out.append("[")
        for i, v in enumerate(value):
            if i:
                out.append(",")
            _write(out, v, descriptor)
        out.append("]")
    else:
        raise ValueError("not a JSON value: %r" % (value,))


def canonical_text(value: Any) -> str:
    out: List[str] = []
    _write(out, value)
    return "".join(out)


def canonical(value: Any) -> bytes:
    """The canonical bytes of a JSON value."""
    return canonical_text(value).encode("utf-8")


def sha256(data: bytes) -> str:
    """``sha256:<hex>`` of some bytes."""
    return "sha256:" + hashlib.sha256(data).hexdigest()


def digest(value: Any) -> str:
    """``sha256:<hex>`` of a value's canonical bytes."""
    return sha256(canonical(value))


def protocol_canonical(value: Any) -> str:
    """RFC 8785 for ``argsHash`` and business-key fingerprints: as the descriptor form but without NFC, and
    integers keep every digit, so two different large integers never share a digest."""
    out: List[str] = []
    _write(out, value, False)
    return "".join(out)


def protocol_digest(value: Any) -> str:
    """``sha256:<hex>`` of :func:`protocol_canonical`."""
    return sha256(protocol_canonical(value).encode("utf-8", "replace"))
