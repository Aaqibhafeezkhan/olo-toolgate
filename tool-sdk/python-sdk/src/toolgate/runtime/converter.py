# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Converts between JSON values and the Python types of authoring README section 7, mirroring the descriptor build's
mapping: properties take their names from Param, dataclass fields that are None are left out, enums travel by name,
bytes as base64, dates and durations in their ISO-8601 forms."""

from __future__ import annotations

import base64
import collections.abc
import dataclasses
import datetime as _dt
import enum
import math
import typing
from decimal import Decimal
from typing import Any, Dict, List, Tuple

from ..declarations import Param
from ..descriptor import schemas as S
from .formats import (FormatError, decode_base64, format_date, format_datetime, format_duration, parse_date,
                      parse_datetime, parse_duration)

__all__ = ["ConversionError", "to_json", "from_json"]


class ConversionError(ValueError):
    """A value that does not fit the Python type."""


def _node_type(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float, Decimal)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    return "object"


def _type_name(tp: Any) -> str:
    if isinstance(tp, type):
        return tp.__name__
    origin = typing.get_origin(tp)
    if isinstance(origin, type):
        return origin.__name__
    return str(tp).replace("typing.", "")


def _property_name(field: str, hint: Any) -> str:
    try:
        _, metadata, _ = S.unwrap(hint, S.Site(None, 0, field))
    except Exception:
        return field
    for m in metadata:
        if isinstance(m, Param) and m.name:
            return m.name
    return field


def _fields(tp: type) -> List[Tuple[str, str, Any]]:
    """(attribute, property name, hint) of a dataclass or TypedDict."""
    try:
        found = typing.get_type_hints(tp, include_extras=True)
    except Exception:
        found = {}
    if dataclasses.is_dataclass(tp):
        return [(f.name, _property_name(f.name, found.get(f.name, f.type)), found.get(f.name, f.type))
                for f in dataclasses.fields(tp)]
    return [(n, _property_name(n, h), h) for n, h in found.items()]


# ---- Python to JSON ----

def _key(k: Any) -> str:
    if isinstance(k, enum.Enum):
        return k.name
    if isinstance(k, bool):
        return "true" if k else "false"
    return str(k)


def to_json(value: Any) -> Any:
    if value is None or isinstance(value, (bool, str)):
        return value
    if isinstance(value, int):
        return int(value)
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            raise ConversionError("a number is not finite")
        return value
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ConversionError("a number is not finite")
        return int(value) if value == value.to_integral_value() and value.as_tuple().exponent >= 0 else float(value)
    if isinstance(value, enum.Enum):
        return value.name
    if isinstance(value, _dt.datetime):
        return format_datetime(value)
    if isinstance(value, _dt.date):
        return format_date(value)
    if isinstance(value, _dt.timedelta):
        return format_duration(value)
    if isinstance(value, (bytes, bytearray, memoryview)):
        return base64.b64encode(bytes(value)).decode("ascii")
    if isinstance(value, collections.abc.Mapping):
        return {_key(k): to_json(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_json(v) for v in value]
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        out: Dict[str, Any] = {}
        for attribute, name, _ in _fields(type(value)):
            v = getattr(value, attribute)
            if v is not None:
                out[name] = to_json(v)
        return out
    if S.is_pydantic(type(value)):
        try:
            dumped = value.model_dump(by_alias=True, exclude_none=True)
        except Exception as e:
            raise ConversionError("cannot read " + type(value).__name__ + ": " + str(e))
        return to_json(dumped)
    if hasattr(value, "__dict__") and not callable(value):
        return {k: to_json(v) for k, v in vars(value).items() if not k.startswith("_") and v is not None}
    raise ConversionError("cannot convert " + type(value).__name__ + " to JSON")


# ---- JSON to Python ----

def _fail(value: Any, tp: Any) -> Any:
    raise ConversionError("cannot read " + _node_type(value) + " as " + _type_name(tp))


def _text(value: Any) -> str:
    if not isinstance(value, str):
        raise ConversionError("expected a string")
    return value


def from_json(value: Any, tp: Any) -> Any:
    """Converts a JSON value, or None for absent, to the Python type."""
    origin = typing.get_origin(tp)
    if origin is typing.Annotated:
        return from_json(value, typing.get_args(tp)[0])
    if S._NOT_REQUIRED and origin in S._NOT_REQUIRED:
        return from_json(value, typing.get_args(tp)[0])
    if S._is_union(tp):
        args = [a for a in typing.get_args(tp) if a is not type(None)]
        if value is None:
            return None
        if len(args) == 1:
            return from_json(value, args[0])
        return _plain(value)
    if value is None:
        return None
    if tp in (typing.Any, object) or tp is None:
        return _plain(value)
    try:
        if tp is str:
            return _text(value)
        if tp is bool:
            return value if isinstance(value, bool) else _fail(value, tp)
        if tp is int:
            if isinstance(value, bool):
                return _fail(value, tp)
            if isinstance(value, int):
                return value
            if isinstance(value, float) and value.is_integer():
                return int(value)
            return _fail(value, tp)
        if tp is float:
            return float(value) if isinstance(value, (int, float, Decimal)) and not isinstance(value, bool) \
                else _fail(value, tp)
        if tp is Decimal:
            if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
                return _fail(value, tp)
            return Decimal(repr(value)) if isinstance(value, float) else Decimal(value)
        if tp is _dt.datetime:
            return parse_datetime(_text(value))
        if tp is _dt.date:
            return parse_date(_text(value))
        if tp is _dt.timedelta:
            return parse_duration(_text(value))
        if tp in (bytes, bytearray):
            return tp(decode_base64(_text(value)))
        if isinstance(tp, type) and issubclass(tp, enum.Enum):
            name = _text(value)
            try:
                return tp[name]
            except KeyError:
                raise ConversionError(name + " is not a " + tp.__name__)
        if tp in S._MAPPINGS or origin in S._MAPPINGS:
            if not isinstance(value, dict):
                return _fail(value, tp)
            args = typing.get_args(tp)
            item = args[1] if len(args) == 2 else object
            return {k: from_json(v, item) for k, v in value.items()}
        if tp in S._SEQUENCES or origin in S._SEQUENCES:
            if not isinstance(value, list):
                return _fail(value, tp)
            args = typing.get_args(tp)
            item = args[0] if args else object
            items = [from_json(v, item) for v in value]
            kind = origin or tp
            if kind is tuple:
                return tuple(items)
            if kind in (set, collections.abc.Set, collections.abc.MutableSet):
                return set(items)
            if kind is frozenset:
                return frozenset(items)
            return items
        if dataclasses.is_dataclass(tp) and isinstance(tp, type):
            if not isinstance(value, dict):
                return _fail(value, tp)
            kwargs = {a: from_json(value.get(n), h) for a, n, h in _fields(tp)}
            try:
                return tp(**kwargs)
            except Exception:
                raise ConversionError("the " + tp.__name__ + " constructor refused the value")
        if S.is_typeddict(tp):
            if not isinstance(value, dict):
                return _fail(value, tp)
            out = {}
            for a, n, h in _fields(tp):
                if n in value:
                    out[a] = from_json(value[n], h)
            return out
        if S.is_pydantic(tp):
            if not isinstance(value, dict):
                return _fail(value, tp)
            try:
                return tp.model_validate(value)
            except Exception:
                raise ConversionError("the " + tp.__name__ + " model refused the value")
        return _fail(value, tp)
    except ConversionError:
        raise
    except (FormatError, ValueError, TypeError):
        raise ConversionError("cannot read " + _node_type(value) + " as " + _type_name(tp))


def _plain(value: Any) -> Any:
    return value
