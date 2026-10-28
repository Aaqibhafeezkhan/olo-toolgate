# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Replaces declared secret values and sensitive inputs or globals with [REDACTED] (errors.md section 4)."""

from __future__ import annotations

import threading
from typing import Any, Optional, Set

from .._json import text

__all__ = ["Redactor", "cap", "MARK"]

MARK = "[REDACTED]"


class Redactor:
    def __init__(self) -> None:
        self._values: Set[str] = set()
        self._lock = threading.Lock()

    def copy(self) -> "Redactor":
        """A copy that per-invocation values (sensitive inputs) can be added to."""
        out = Redactor()
        with self._lock:
            out._values = set(self._values)
        return out

    def add(self, value: Any) -> None:
        if value is None:
            return
        if isinstance(value, dict):
            for v in list(value.values()):
                self.add(v)
            return
        if isinstance(value, list):
            for v in list(value):
                self.add(v)
            return
        s = text(value)
        if s:
            with self._lock:
                self._values.add(s)

    def apply(self, value: Any) -> Any:
        """Redacts a string, or every string and scalar of a JSON value."""
        if value is None:
            return None
        if isinstance(value, str):
            return self.text(value)
        if isinstance(value, dict):
            return {self.text(k): self.apply(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.apply(v) for v in value]
        with self._lock:
            hit = text(value) in self._values
        return MARK if hit else value

    def text(self, s: Optional[str]) -> Optional[str]:
        if s is None:
            return None
        with self._lock:
            if not self._values:
                return s
            longest_first = sorted(self._values, key=len, reverse=True)
        for v in longest_first:
            s = s.replace(v, MARK)
        return s


def cap(s: str, limit: int) -> str:
    """Truncates to at most ``limit`` UTF-8 bytes without splitting a character."""
    data = s.encode("utf-8", "replace")
    if len(data) <= limit:
        return s
    end = limit
    while end > 0 and (data[end] & 0xC0) == 0x80:
        end -= 1
    return data[:end].decode("utf-8", "replace")
