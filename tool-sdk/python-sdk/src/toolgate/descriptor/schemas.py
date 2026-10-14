# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Schema generation rules of spec/descriptor section 5, generation 1, over Python type hints.

A port of the Java processor's Schemas class. The Python types map as spec/authoring/README.md section 7 says:
str, int, float and Decimal, bool, datetime.date, datetime.datetime, datetime.timedelta, Enum, bytes, list and
other sequences, dict[str, T], and dataclasses, TypedDicts and Pydantic models as closed objects.
"""

from __future__ import annotations

import collections.abc
import dataclasses
import datetime as _dt
import decimal
import enum
import re
import sys
import types
import typing
from typing import Any, Dict, List, Optional, Tuple

from ..declarations import Param
from ..enums import ValueType
from ..jcs import sorted_utf16
from .problems import Problem, Site

INT_MIN = -2147483648
INT_MAX = 2147483647
SAFE = 9007199254740991

_INTEGER = re.compile(r"^[+-]?[0-9]+$")
_DECIMAL = re.compile(r"^[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?$")
_SEQUENCES = {list, tuple, set, frozenset, collections.abc.Sequence, collections.abc.MutableSequence,
              collections.abc.Collection, collections.abc.Iterable, collections.abc.Set, collections.abc.MutableSet}
_MAPPINGS = {dict, collections.abc.Mapping, collections.abc.MutableMapping}
_NOT_REQUIRED = tuple(x for x in (getattr(typing, "NotRequired", None), getattr(typing, "Required", None),
                                  getattr(typing, "ReadOnly", None)) if x is not None)


class Spec:
    """The attributes Param, Variable and Global share, as the schema rules read them."""

    def __init__(self, source: Any, name: Optional[str] = None) -> None:
        self.name = name if name is not None else source.name
        self.description = source.description
        self.type = source.type
        self.required = source.required
        self.default_value = source.default_value
        self.min = source.min
        self.max = source.max
        self.min_length = source.min_length
        self.max_length = source.max_length
        self.pattern = source.pattern
        self.enum_values = list(source.enum_values)
        self.format = source.format
        self.sensitive = source.sensitive


class Scalar:
    """The type a grouped input stands for: the scalar Python type of its declared value type."""

    def __init__(self, value_type: ValueType) -> None:
        self.value_type = value_type


def scalar_type(value_type: ValueType) -> Any:
    return {
        ValueType.INT: int,
        ValueType.LONG: Scalar(ValueType.LONG),
        ValueType.NUMBER: float,
        ValueType.BOOLEAN: bool,
        ValueType.BINARY: bytes,
    }.get(value_type, str)


def type_name(tp: Any) -> str:
    if isinstance(tp, Scalar):
        return "int"
    if isinstance(tp, type):
        return tp.__qualname__
    return repr(tp).replace("typing.", "")


def _is_union(tp: Any) -> bool:
    origin = typing.get_origin(tp)
    return origin is typing.Union or (sys.version_info >= (3, 10) and origin is types.UnionType)


def unwrap(tp: Any, site: Site) -> Tuple[Any, List[Any], bool]:
    """Strips Annotated, Optional and NotRequired: the bare type, its metadata and whether None is allowed."""
    metadata: List[Any] = []
    optional = False
    while True:
        origin = typing.get_origin(tp)
        if origin is typing.Annotated:
            metadata.extend(tp.__metadata__)
            tp = typing.get_args(tp)[0]
        elif _NOT_REQUIRED and origin in _NOT_REQUIRED:
            tp = typing.get_args(tp)[0]
        elif _is_union(tp):
            args = typing.get_args(tp)
            rest = [a for a in args if a is not type(None)]
            if len(rest) != len(args):
                optional = True
            if len(rest) != 1:
                raise Problem(site, "union type " + type_name(tp) + " is not supported; use one type, optionally with None")
            tp = rest[0]
        else:
            return tp, metadata, optional


def param_of(metadata: List[Any], site: Site) -> Optional[Param]:
    params = [m for m in metadata if isinstance(m, Param)]
    if len(params) > 1:
        raise Problem(site, site.name + " has more than one Param")
    return params[0] if params else None


def is_typeddict(tp: Any) -> bool:
    return isinstance(tp, type) and issubclass(tp, dict) and hasattr(tp, "__required_keys__")


def is_pydantic(tp: Any) -> bool:
    return isinstance(tp, type) and isinstance(getattr(tp, "model_fields", None), dict) \
        and hasattr(tp, "model_validate")


def is_object_type(tp: Any) -> bool:
    return isinstance(tp, type) and (dataclasses.is_dataclass(tp) or is_typeddict(tp) or is_pydantic(tp))


def hints(owner: Any, site: Site) -> Dict[str, Any]:
    try:
        return typing.get_type_hints(owner, include_extras=True)
    except Exception as e:  # NameError for an unresolvable forward reference, and the like
        raise Problem(site, "cannot resolve the type hints of " + getattr(owner, "__qualname__", repr(owner))
                      + ": " + str(e))


def value_type_of(schema: Dict[str, Any]) -> str:
    """The value type of an input schema, as the runtime's declared values name it."""
    t = schema.get("type")
    if t == "integer":
        hi = schema.get("maximum", sys.maxsize)
        lo = schema.get("minimum", -sys.maxsize)
        return "INT" if hi <= INT_MAX and lo >= INT_MIN else "LONG"
    if t == "number":
        return "NUMBER"
    if t == "boolean":
        return "BOOLEAN"
    if t == "array":
        return "ARRAY"
    if t != "string":
        return "OBJECT"
    if "enum" in schema:
        return "ENUM"
    if schema.get("contentEncoding") == "base64":
        return "BINARY"
    return {"date": "DATE", "date-time": "DATE_TIME", "duration": "DURATION", "uri": "URI"}.get(schema.get("format"),
                                                                                              "STRING")


class Schemas:
    """One schema build: recursive object types collect in $defs."""

    def __init__(self) -> None:
        self.stack: List[str] = []
        self.recursive: set = set()
        self.defs: Dict[str, Any] = {}

    def inputs(self, properties: Dict[str, Any], required: List[str]) -> Dict[str, Any]:
        schema = closed(properties, required)
        if self.defs:
            schema["$defs"] = dict(self.defs)
        return schema

    def output(self, tp: Any, site: Site) -> Dict[str, Any]:
        schema = self.schema(tp, None, site)
        if schema.get("type") != "object" and "$ref" not in schema:
            raise Problem(site, "the return type must be a dataclass, a TypedDict, a Pydantic model, a dict[str, T]"
                                " or ToolResult")
        if self.defs:
            schema["$defs"] = dict(self.defs)
        return schema

    def schema(self, tp: Any, spec: Optional[Spec], site: Site) -> Dict[str, Any]:
        """The schema of one value; spec is None for unannotated properties and array items."""
        tp, metadata, _ = unwrap(tp, site)
        if spec is None:
            param = param_of(metadata, site)
            if param is not None:
                spec = Spec(param, param.name or site.name)
        inferred = self.infer(tp, site)
        vt = resolve(inferred, spec, site)
        schema: Dict[str, Any] = {}
        if vt is ValueType.STRING:
            schema["type"] = "string"
        elif vt in (ValueType.INT, ValueType.LONG):
            schema["type"] = "integer"
        elif vt is ValueType.NUMBER:
            schema["type"] = "number"
        elif vt is ValueType.BOOLEAN:
            schema["type"] = "boolean"
        elif vt is ValueType.DATE:
            _format(schema, "date")
        elif vt is ValueType.DATE_TIME:
            _format(schema, "date-time")
        elif vt is ValueType.DURATION:
            _format(schema, "duration")
        elif vt is ValueType.URI:
            _format(schema, "uri")
        elif vt is ValueType.BINARY:
            schema["type"] = "string"
            schema["contentEncoding"] = "base64"
        elif vt is ValueType.ENUM:
            schema["type"] = "string"
            schema["enum"] = [m.name for m in tp] if inferred is ValueType.ENUM else list(spec.enum_values)
        elif vt is ValueType.ARRAY:
            schema["type"] = "array"
            schema["items"] = self.schema(self.element_type(tp, site), None, site.child(site.name))
        elif vt is ValueType.OBJECT:
            if _is_map(tp):
                args = typing.get_args(tp)
                if len(args) != 2:
                    raise Problem(site, "raw type " + type_name(tp) + "; add type arguments, dict[str, T]")
                if args[0] is not str:
                    raise Problem(site, "map keys must be str")
                value = args[1]
                schema["type"] = "object"
                # dict[str, Any] holds any JSON value: the empty schema.
                schema["additionalProperties"] = {} if value in (typing.Any, object) \
                    else self.schema(value, None, site.child(site.name))
            else:
                return self.with_spec(self.object(tp, site), spec, vt, site)
        else:  # pragma: no cover - every value type is handled above
            raise Problem(site, "unsupported type " + type_name(tp))
        return self.with_spec(schema, spec, vt, site)

    def with_spec(self, schema: Dict[str, Any], spec: Optional[Spec], vt: ValueType, site: Site) -> Dict[str, Any]:
        if spec is None:
            if vt is ValueType.INT:
                _bounds(schema, INT_MIN, INT_MAX)
            if vt is ValueType.LONG:
                _bounds(schema, -SAFE, SAFE)
            return schema
        if spec.description:
            schema["description"] = spec.description
        constraints(schema, vt, spec, site, True)
        return schema

    def infer(self, tp: Any, site: Site) -> ValueType:
        """The value type a Python type maps to (authoring README section 7)."""
        if isinstance(tp, Scalar):
            return tp.value_type
        if tp is bool:
            return ValueType.BOOLEAN
        if tp is int:
            return ValueType.INT
        if tp in (float, decimal.Decimal):
            return ValueType.NUMBER
        if tp is str:
            return ValueType.STRING
        if tp in (bytes, bytearray):
            return ValueType.BINARY
        if tp is _dt.datetime:
            return ValueType.DATE_TIME
        if tp is _dt.date:
            return ValueType.DATE
        if tp is _dt.timedelta:
            return ValueType.DURATION
        if isinstance(tp, type) and issubclass(tp, enum.Enum):
            return ValueType.ENUM
        if _is_map(tp):
            return ValueType.OBJECT
        if _is_sequence(tp):
            return ValueType.ARRAY
        if is_object_type(tp):
            return ValueType.OBJECT
        raise Problem(site, "unsupported type " + type_name(tp)
                      + "; use a scalar, a list, a dict[str, T], a dataclass, a TypedDict or a Pydantic model")

    def element_type(self, tp: Any, site: Site) -> Any:
        args = typing.get_args(tp)
        origin = typing.get_origin(tp)
        if origin is tuple:
            if len(args) == 2 and args[1] is Ellipsis:
                return args[0]
            raise Problem(site, "a tuple is an array of one type: tuple[T, ...]")
        if len(args) != 1:
            raise Problem(site, "raw type " + type_name(tp) + "; add type arguments, list[T]")
        return args[0]

    def object(self, tp: Any, site: Site) -> Dict[str, Any]:
        name = tp.__name__
        if name in self.stack:
            self.recursive.add(name)
            return _ref(name)
        self.stack.append(name)
        properties: Dict[str, Any] = {}
        required: List[str] = []
        try:
            for field, hint, optional_key in self.fields(tp, site):
                fsite = site.child(field)
                bare, metadata, optional = unwrap(hint, fsite)
                p = param_of(metadata, fsite)
                spec = None if p is None else Spec(p, p.name or field)
                prop = p.name if p is not None and p.name else field
                fsite = site.child(prop)
                if prop in properties:
                    raise Problem(fsite, "duplicate property " + prop)
                properties[prop] = self.schema(bare, spec, fsite)
                if not (optional or optional_key or (p is not None and (not p.required or p.default_value))):
                    required.append(prop)
            if not properties:
                raise Problem(site, name + " has no properties; use a dataclass, a TypedDict, a Pydantic model or a dict")
        finally:
            self.stack.pop()
        schema = closed(properties, required)
        if name in self.recursive:
            self.defs[name] = schema
            return _ref(name)
        return schema

    def fields(self, tp: Any, site: Site) -> List[Tuple[str, Any, bool]]:
        """(name, hint, whether the key may be absent) for each property, in declaration order."""
        found = hints(tp, site)
        if dataclasses.is_dataclass(tp):
            return [(f.name, found.get(f.name, f.type), False) for f in dataclasses.fields(tp)]
        if is_typeddict(tp):
            optional_keys = getattr(tp, "__optional_keys__", frozenset())
            return [(n, h, n in optional_keys) for n, h in found.items()]
        out = []
        for n, info in tp.model_fields.items():
            hint = found.get(n, getattr(info, "annotation", typing.Any))
            extra = [m for m in getattr(info, "metadata", []) if isinstance(m, Param)]
            if extra and param_of(unwrap(hint, site)[1], site) is None:
                hint = typing.Annotated[hint, extra[0]]
            alias = getattr(info, "alias", None)
            out.append((alias or n, hint, False))
        return out


def _is_map(tp: Any) -> bool:
    return tp in _MAPPINGS or typing.get_origin(tp) in _MAPPINGS


def _is_sequence(tp: Any) -> bool:
    return tp in _SEQUENCES or typing.get_origin(tp) in _SEQUENCES


def _format(schema: Dict[str, Any], fmt: str) -> None:
    schema["type"] = "string"
    schema["format"] = fmt


def _bounds(schema: Dict[str, Any], lo: int, hi: int) -> None:
    schema["minimum"] = lo
    schema["maximum"] = hi


def _ref(name: str) -> Dict[str, Any]:
    return {"$ref": "#/$defs/" + name}


def closed(properties: Dict[str, Any], required: List[str]) -> Dict[str, Any]:
    return {"type": "object", "properties": properties, "required": sorted_utf16(required),
            "additionalProperties": False}


def resolve(inferred: ValueType, spec: Optional[Spec], site: Site) -> ValueType:
    if spec is None:
        return inferred
    declared = spec.type
    if declared is ValueType.INFER:
        return ValueType.ENUM if inferred is ValueType.STRING and spec.enum_values else inferred
    if declared is inferred:
        return declared
    if inferred is ValueType.INT and declared is ValueType.LONG:
        return declared
    if inferred is ValueType.STRING and declared in (ValueType.DATE, ValueType.DATE_TIME, ValueType.DURATION,
                                                     ValueType.URI):
        return declared
    if inferred is ValueType.STRING and declared is ValueType.ENUM:
        if not spec.enum_values:
            raise Problem(site, "a str typed as ENUM needs enum_values")
        return declared
    raise Problem(site, "type " + declared.value + " does not match the Python type, which maps to " + inferred.value)


def constraints(schema: Dict[str, Any], vt: ValueType, spec: Spec, site: Site, materialise: bool) -> None:
    """Adds the declared constraint keywords; with materialise, INT and LONG get their range bounds unless
    narrower ones are declared."""
    name = spec.name or site.name
    numeric = vt in (ValueType.INT, ValueType.LONG, ValueType.NUMBER)
    text = vt in (ValueType.STRING, ValueType.URI)
    if not numeric and (spec.min or spec.max):
        raise Problem(site, name + ": min and max apply to INT, LONG and NUMBER only")
    if not text and (spec.min_length >= 0 or spec.max_length >= 0 or spec.pattern):
        raise Problem(site, name + ": min_length, max_length and pattern apply to STRING and URI only")
    if spec.format and vt is not ValueType.STRING:
        raise Problem(site, name + ": format applies to STRING only; " + vt.value + " has its own format")
    if spec.enum_values and vt is not ValueType.ENUM:
        raise Problem(site, name + ": enum_values applies to a str typed as ENUM")
    if vt in (ValueType.INT, ValueType.LONG):
        lo = INT_MIN if vt is ValueType.INT else -SAFE
        hi = INT_MAX if vt is ValueType.INT else SAFE
        mn = integer(spec.min, lo, hi, name, site) if spec.min else None
        mx = integer(spec.max, lo, hi, name, site) if spec.max else None
        if mn is not None and mx is not None and mn > mx:
            raise Problem(site, name + ": min is greater than max")
        if mn is not None or materialise:
            schema["minimum"] = mn if mn is not None else lo
        if mx is not None or materialise:
            schema["maximum"] = mx if mx is not None else hi
    elif vt is ValueType.NUMBER:
        mn = number(spec.min, name, site) if spec.min else None
        mx = number(spec.max, name, site) if spec.max else None
        if mn is not None and mx is not None and mn > mx:
            raise Problem(site, name + ": min is greater than max")
        if mn is not None:
            schema["minimum"] = mn
        if mx is not None:
            schema["maximum"] = mx
    if spec.min_length < -1 or spec.max_length < -1:
        raise Problem(site, name + ": lengths must be at least 0")
    if spec.min_length >= 0:
        schema["minLength"] = spec.min_length
    if spec.max_length >= 0:
        schema["maxLength"] = spec.max_length
    if spec.min_length >= 0 and spec.max_length >= 0 and spec.min_length > spec.max_length:
        raise Problem(site, name + ": min_length is greater than max_length")
    if spec.pattern:
        try:
            re.compile(spec.pattern)
        except re.error as e:
            raise Problem(site, name + ": pattern does not compile: " + str(e))
        schema["pattern"] = spec.pattern
    if spec.format:
        schema["format"] = spec.format
    if spec.default_value:
        schema["default"] = typed_default(vt, spec, schema, name, site)
    if spec.sensitive:
        schema["x-toolgate-sensitive"] = True


def typed_default(vt: ValueType, spec: Spec, schema: Dict[str, Any], name: str, site: Site) -> Any:
    raw = spec.default_value
    if vt in (ValueType.INT, ValueType.LONG):
        lo = schema.get("minimum", INT_MIN if vt is ValueType.INT else -SAFE)
        hi = schema.get("maximum", INT_MAX if vt is ValueType.INT else SAFE)
        return integer(raw, lo, hi, name + " default", site)
    if vt is ValueType.NUMBER:
        return number(raw, name + " default", site)
    if vt is ValueType.BOOLEAN:
        if raw not in ("true", "false"):
            raise Problem(site, name + ": default must be true or false")
        return raw == "true"
    if vt is ValueType.ENUM:
        values = schema.get("enum")
        if values is None or raw not in values:
            raise Problem(site, name + ": default " + raw + " is not an enum value")
        return raw
    if vt in (ValueType.STRING, ValueType.DATE, ValueType.DATE_TIME, ValueType.DURATION, ValueType.URI):
        return raw
    raise Problem(site, name + ": " + vt.value + " values cannot have a default")


def integer(raw: str, lo: int, hi: int, name: str, site: Site) -> int:
    text = raw.strip()
    if not _INTEGER.match(text):
        raise Problem(site, name + ": " + raw + " is not an integer")
    value = int(text)
    if value < lo or value > hi:
        raise Problem(site, "%s: %s is outside %d..%d" % (name, raw, lo, hi))
    return value


def number(raw: str, name: str, site: Site) -> float:
    text = raw.strip()
    if not _DECIMAL.match(text):
        raise Problem(site, name + ": " + raw + " is not a number")
    value = float(text)
    if value != value or value in (float("inf"), float("-inf")):
        raise Problem(site, name + ": " + raw + " is not finite")
    return value
