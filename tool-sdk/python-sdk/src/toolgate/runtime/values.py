# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Typed reads over declared values. Reading an undeclared name, or with a getter that does not fit the declared
type, is a ToolConfigurationError; a declared value with nothing behind it raises ``on_missing``."""

from __future__ import annotations

from typing import Any, Callable, Dict, List, NamedTuple, Optional, Type, TypeVar

from ..errors import PlatformCode, PlatformError, ToolConfigurationError
from .._json import text
from .converter import from_json

__all__ = ["Entry", "DeclaredValues", "type_of"]

T = TypeVar("T")

_TEXT = {"STRING", "ENUM", "URI", "DATE", "DATE_TIME", "DURATION"}


class Entry(NamedTuple):
    """A declared value: its value type name and the value, or None."""

    type: str
    value: Any


class DeclaredValues:
    def __init__(self, kind: str, entries: Dict[str, Entry], on_missing: Callable[[str], Exception]) -> None:
        self._kind = kind
        self._entries = entries
        self._on_missing = on_missing
        #: The caller's whole identity, for the Jwt members; only the claims accessor uses it.
        self.identity: Optional[Dict[str, Any]] = None

    def declared_types(self) -> Dict[str, str]:
        return {n: e.type for n, e in self._entries.items()}

    def _read(self, name: str, types: set, getter: str) -> Any:
        e = self._entries.get(name)
        if e is None:
            raise ToolConfigurationError(self._kind + " " + str(name) + " is not declared for this tool")
        if e.type not in types:
            raise ToolConfigurationError(self._kind + " " + name + " is " + e.type + "; " + getter
                                         + " does not read it")
        if e.value is None:
            raise self._on_missing(name)
        return e.value

    @staticmethod
    def _convert(value: Any, tp: Any) -> Any:
        return from_json(value, tp)

    def get_str(self, name: str) -> str:
        return text(self._read(name, _TEXT, "get_str"))

    def get_int(self, name: str) -> int:
        return self._convert(self._read(name, {"INT"}, "get_int"), int)

    def get_long(self, name: str) -> int:
        return self._convert(self._read(name, {"INT", "LONG"}, "get_long"), int)

    def get_number(self, name: str) -> float:
        return self._convert(self._read(name, {"INT", "LONG", "NUMBER"}, "get_number"), float)

    def get_bool(self, name: str) -> bool:
        return self._convert(self._read(name, {"BOOLEAN"}, "get_bool"), bool)

    def get_date(self, name: str) -> Any:
        import datetime
        return self._convert(self._read(name, {"DATE"}, "get_date"), datetime.date)

    def get_datetime(self, name: str) -> Any:
        import datetime
        return self._convert(self._read(name, {"DATE_TIME"}, "get_datetime"), datetime.datetime)

    def get_duration(self, name: str) -> Any:
        import datetime
        return self._convert(self._read(name, {"DURATION"}, "get_duration"), datetime.timedelta)

    def get_object(self, name: str, type: Type[T] = object) -> T:  # type: ignore[assignment]
        return self._convert(self._read(name, {"OBJECT"}, "get_object"), type)

    def get_list(self, name: str, type: Type[T] = object) -> List[T]:  # type: ignore[assignment]
        return self._convert(self._read(name, {"ARRAY"}, "get_list"), List[type])  # type: ignore[valid-type]

    def get_bytes(self, name: str) -> bytes:
        return self._convert(self._read(name, {"BINARY"}, "get_bytes"), bytes)

    # Jwt members
    @property
    def user_name(self) -> str:
        return self._standard("preferred_username")

    @property
    def subject(self) -> str:
        return self._standard("sub")

    @property
    def issuer(self) -> str:
        return self._standard("iss")

    def _standard(self, claim: str) -> str:
        if self.identity is None:
            raise PlatformError(PlatformCode.CLAIMS_UNAVAILABLE, "No verified identity is available on this transport")
        v = self.identity.get(claim)
        if not isinstance(v, str):
            raise PlatformError(PlatformCode.CLAIMS_UNAVAILABLE, "The identity has no " + claim + " claim")
        return v


def type_of(schema: Dict[str, Any]) -> str:
    """The value type name of a descriptor property schema (authoring README section 7)."""
    t = schema.get("type", "")
    if t == "integer":
        hi = schema.get("maximum")
        lo = schema.get("minimum")
        hi = hi if isinstance(hi, (int, float)) and not isinstance(hi, bool) else 2 ** 63 - 1
        lo = lo if isinstance(lo, (int, float)) and not isinstance(lo, bool) else -2 ** 63
        return "INT" if hi <= 2147483647 and lo >= -2147483648 else "LONG"
    if t == "number":
        return "NUMBER"
    if t == "boolean":
        return "BOOLEAN"
    if t == "object":
        return "OBJECT"
    if t == "array":
        return "ARRAY"
    if t == "string":
        if "enum" in schema:
            return "ENUM"
        if schema.get("contentEncoding") == "base64":
            return "BINARY"
        return {"date": "DATE", "date-time": "DATE_TIME", "duration": "DURATION", "uri": "URI"}.get(
            schema.get("format", ""), "STRING")
    return "OBJECT"
