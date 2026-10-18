# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""JSON Schema 2020-12 validation for the keywords descriptors use (descriptor section 5) plus the common structural
ones. Unknown keywords are ignored. ``validate`` returns the first violation as ``$.path: reason``, or None."""

from __future__ import annotations

import json
import re
from decimal import Decimal
from typing import Any, Dict, Optional

from .._json import as_bool, as_long, decimal, dumps, is_int, is_number, text
from . import formats

__all__ = ["SchemaValidator"]


def _strict_equal(a: Any, b: Any) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    if type(a) is not type(b):
        return False
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(_strict_equal(a[k], b[k]) for k in a)
    if isinstance(a, list):
        return len(a) == len(b) and all(_strict_equal(x, y) for x, y in zip(a, b))
    return a == b


def _equal(a: Any, b: Any) -> bool:
    if is_number(a) and is_number(b):
        return decimal(a) == decimal(b)
    return _strict_equal(a, b)


def _is_whole(v: Any) -> bool:
    d = decimal(v)
    return d == d.to_integral_value()


class SchemaValidator:
    def __init__(self, root: Any) -> None:
        self.root = root
        self._patterns: Dict[str, Any] = {}

    def validate(self, value: Any) -> Optional[str]:
        return self._validate(self.root, value, "$", 0)

    def _validate(self, schema: Any, value: Any, path: str, depth: int) -> Optional[str]:
        if depth > 64:
            return path + ": too deeply nested"
        if schema is None or isinstance(schema, bool):
            return path + ": no value is allowed" if schema is False else None
        if not isinstance(schema, dict):
            return None
        ref = schema.get("$ref")
        if ref is not None:
            r = text(ref)
            if not r.startswith("#/$defs/"):
                return path + ": unsupported $ref " + r
            defs = self.root.get("$defs") if isinstance(self.root, dict) else None
            target = defs.get(r[len("#/$defs/"):]) if isinstance(defs, dict) else None
            failure = self._validate(target, value, path, depth + 1)
            if failure is not None:
                return failure
        t = schema.get("type")
        if t is not None and not _type_matches(t, value):
            return path + ": expected " + (t if isinstance(t, str) else dumps(t))
        if "enum" in schema:
            options = schema["enum"] if isinstance(schema["enum"], list) else []
            if not any(_equal(o, value) for o in options):
                return path + ": not one of the allowed values"
        if "const" in schema and not _equal(schema["const"], value):
            return path + ": not the allowed value"
        if is_number(value):
            failure = _number(schema, decimal(value), path)
            if failure is not None:
                return failure
        if isinstance(value, str):
            failure = self._string(schema, value, path)
            if failure is not None:
                return failure
        if isinstance(value, list):
            if "minItems" in schema and len(value) < as_long(schema["minItems"]):
                return path + ": too few items"
            if "maxItems" in schema and len(value) > as_long(schema["maxItems"]):
                return path + ": too many items"
            if as_bool(schema.get("uniqueItems"), False):
                seen = set()
                for item in value:
                    key = json.dumps(item, sort_keys=True)
                    if key in seen:
                        return path + ": items are not unique"
                    seen.add(key)
            items = schema.get("items")
            if items is not None:
                for i, item in enumerate(value):
                    failure = self._validate(items, item, path + "[" + str(i) + "]", depth + 1)
                    if failure is not None:
                        return failure
        if isinstance(value, dict):
            required = schema.get("required")
            if isinstance(required, list):
                for name in required:
                    if text(name) not in value:
                        return path + ": " + text(name) + " is required"
            properties = schema.get("properties")
            if not isinstance(properties, dict):
                properties = {}
            has_additional = "additionalProperties" in schema
            additional = schema.get("additionalProperties")
            for key, member in value.items():
                child = path + "." + key
                if key in properties:
                    failure = self._validate(properties[key], member, child, depth + 1)
                elif has_additional:
                    failure = self._validate(additional, member, child, depth + 1) if additional is not None \
                        else None
                else:
                    failure = None
                if failure is not None:
                    return child + ": is not allowed" if failure == child + ": no value is allowed" else failure
        return None

    def _string(self, schema: Dict[str, Any], s: str, path: str) -> Optional[str]:
        length = len(s)  # code points
        if "minLength" in schema and length < as_long(schema["minLength"]):
            return path + ": shorter than " + dumps(schema["minLength"])
        if "maxLength" in schema and length > as_long(schema["maxLength"]):
            return path + ": longer than " + dumps(schema["maxLength"])
        pattern = schema.get("pattern")
        if pattern is not None:
            p = self._patterns.get(text(pattern))
            if p is None:
                try:
                    p = re.compile(text(pattern))
                except re.error:
                    return path + ": the schema pattern does not compile"
                self._patterns[text(pattern)] = p
            if not p.search(s):
                return path + ": does not match the pattern"
        fmt = schema.get("format")
        if fmt is not None and not _format_matches(text(fmt), s):
            return path + ": not a valid " + text(fmt)
        if schema.get("contentEncoding") == "base64":
            try:
                formats.decode_base64(s)
            except ValueError:
                return path + ": not valid base64"
        return None


def _type_matches(t: Any, value: Any) -> bool:
    if isinstance(t, list):
        return any(_type_matches(x, value) for x in t)
    name = text(t)
    if name == "object":
        return isinstance(value, dict)
    if name == "array":
        return isinstance(value, list)
    if name == "string":
        return isinstance(value, str)
    if name == "boolean":
        return isinstance(value, bool)
    if name == "null":
        return value is None
    if name == "number":
        return is_number(value)
    if name == "integer":
        return is_int(value) or (is_number(value) and _is_whole(value))
    return False


def _number(schema: Dict[str, Any], v: Decimal, path: str) -> Optional[str]:
    def bound(key: str) -> Optional[Decimal]:
        b = schema.get(key)
        return decimal(b) if is_number(b) else (Decimal(0) if key in schema else None)
    lo, hi = bound("minimum"), bound("maximum")
    xlo, xhi = bound("exclusiveMinimum"), bound("exclusiveMaximum")
    if lo is not None and v < lo:
        return path + ": below the minimum " + dumps(schema["minimum"])
    if hi is not None and v > hi:
        return path + ": above the maximum " + dumps(schema["maximum"])
    if xlo is not None and v <= xlo:
        return path + ": not above " + dumps(schema["exclusiveMinimum"])
    if xhi is not None and v >= xhi:
        return path + ": not below " + dumps(schema["exclusiveMaximum"])
    return None


def _format_matches(fmt: str, s: str) -> bool:
    try:
        if fmt == "date":
            formats.parse_date(s)
        elif fmt == "date-time":
            formats.parse_datetime(s)
        elif fmt == "duration":
            formats.parse_duration(s)
        elif fmt == "uri":
            return formats.is_absolute_uri(s)
        return True
    except ValueError:
        return False
