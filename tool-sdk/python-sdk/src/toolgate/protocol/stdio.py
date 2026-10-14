# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Newline-delimited JSON-RPC over stdio. Requests run concurrently; each response is written as one line. The
connection is locked to the era of its first accepted message."""

from __future__ import annotations

import concurrent.futures
import threading
from typing import Any, BinaryIO, Dict, Optional

from .._json import dumpb, loads
from .core import Connection, ProtocolCore, error
from .errors import INVALID_REQUEST, PARSE_ERROR, ProtocolError

__all__ = ["StdioServer", "MAX_LINE"]

#: The longest line accepted: the 1 MiB input ceiling plus the envelope.
MAX_LINE = 2 * 1024 * 1024


class StdioServer:
    def __init__(self, core: ProtocolCore) -> None:
        self.core = core

    def serve(self, input: BinaryIO, output: BinaryIO, wait_s: float = 70.0) -> None:
        """Serves until the input ends, then waits for in-flight requests."""
        connection = Connection("stdio")
        lock = threading.Lock()

        def write(frame: Dict[str, Any]) -> None:
            data = dumpb(frame) + b"\n"
            with lock:
                output.write(data)
                output.flush()

        workers = concurrent.futures.ThreadPoolExecutor(max_workers=64, thread_name_prefix="toolgate-stdio")
        pending = []
        try:
            while True:
                line = self._read_line(input, write)
                if line is None:
                    break
                if not line.strip():
                    continue
                try:
                    message = loads(line)
                except ValueError:
                    write(error(None, ProtocolError(PARSE_ERROR, "Parse error")))
                    continue

                def run(message: Any = message) -> None:
                    response = self.core.handle(message, connection, None, write)
                    if response is not None:
                        write(response)
                pending.append(workers.submit(run))
                pending = [f for f in pending if not f.done()]
        finally:
            concurrent.futures.wait(pending, timeout=wait_s)
            workers.shutdown(wait=False)

    @staticmethod
    def _read_line(input: BinaryIO, write: Any) -> Optional[bytes]:
        line = input.readline(MAX_LINE + 1)
        if not line:
            return None
        if len(line) > MAX_LINE and not line.endswith(b"\n"):
            # Skip the rest of the line.
            while True:
                rest = input.readline(MAX_LINE)
                if not rest or rest.endswith(b"\n"):
                    break
            write(error(None, ProtocolError(INVALID_REQUEST, "Request too large")))
            return b""
        return line.rstrip(b"\n")
