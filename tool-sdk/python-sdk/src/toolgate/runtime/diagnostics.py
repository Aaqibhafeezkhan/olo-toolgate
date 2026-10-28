# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Local diagnostics: one line per entry on a text stream (standard error by default)."""

from __future__ import annotations

import io
import sys
import threading
from typing import Optional, TextIO

__all__ = ["Diagnostics"]


class Diagnostics:
    def __init__(self, stream: Optional[TextIO] = None) -> None:
        self._stream = stream
        self._lock = threading.Lock()

    @classmethod
    def captured(cls) -> "Diagnostics":
        """Diagnostics kept in memory, for tests."""
        return cls(io.StringIO())

    def println(self, line: str) -> None:
        stream = self._stream if self._stream is not None else sys.stderr
        with self._lock:
            try:
                stream.write(line + "\n")
                stream.flush()
            except (OSError, ValueError):
                pass

    def text(self) -> str:
        """What was written, when the stream is in memory."""
        stream = self._stream
        return stream.getvalue() if isinstance(stream, io.StringIO) else ""
