# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The confined in-memory device files double used outside a managed device."""
from __future__ import annotations

import re
import threading
from dataclasses import dataclass

from ..errors import PlatformCode, PlatformError


@dataclass(frozen=True)
class FileInfo:
    path: str
    bytes: int


def refused(message="The device files operation was refused"):
    return PlatformError(PlatformCode.FILES_REFUSED, message)


def portable(path):
    if not isinstance(path, str) or len(path) > 256 or not path:
        raise refused()
    for part in path.split("/"):
        if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", part) or part.endswith("."):
            raise refused()
        if part.split(".")[0] in {"con", "prn", "aux", "nul", *("com%d" % n for n in range(1, 10)),
                                  *("lpt%d" % n for n in range(1, 10))}:
            raise refused()
    return path


class MemoryDeviceFiles:
    def __init__(self):
        self.entries = {}
        self.lock = threading.RLock()

    def list(self):
        with self.lock:
            return sorted(self.entries)

    def _entry(self, path):
        path = portable(path)
        if path not in self.entries:
            raise refused("The path does not exist")
        return self.entries[path]

    def read_text(self, path):
        with self.lock:
            value = self._entry(path)
            if value is None:
                raise refused("The path is a directory")
            return value

    def info(self, path):
        return FileInfo(portable(path), len(self.read_text(path).encode("utf-8")))

    def _write(self, path, text):
        portable(path)
        if not isinstance(text, str) or len(text.encode("utf-8")) > 65536:
            raise refused("File size exceeds 64 KiB")
        parent = path.rpartition("/")[0]
        if parent and (parent not in self.entries or self.entries[parent] is not None):
            raise refused("The parent directory does not exist")
        if path in self.entries and self.entries[path] is None:
            raise refused("The path is a directory")
        if path not in self.entries and len(self.entries) >= 1024:
            raise refused("The files area is full")
        self.entries[path] = text

    def write_text(self, path, text):
        with self.lock:
            self._write(path, text)

    def append_text(self, path, text):
        with self.lock:
            self._write(path, (self.read_text(path) if path in self.entries else "") + text)

    def mkdir(self, path):
        with self.lock:
            if path in self.entries:
                raise refused("The path exists")
            self._write(path, "")
            self.entries[path] = None

    def rename(self, path, destination):
        with self.lock:
            value = self.read_text(path)
            if portable(destination) in self.entries:
                raise refused("The destination exists")
            self._write(destination, value)
            del self.entries[path]


class ReadOnlyFiles:
    def __init__(self, files):
        self.files = files

    def list(self):
        return self.files.list()

    def read_text(self, path):
        return self.files.read_text(path)

    def info(self, path):
        return self.files.info(path)

    def __getattr__(self, name):
        if name in {"write_text", "append_text", "mkdir", "rename"}:
            def deny(*args):
                raise refused("This tool declared read-only device file access")
            return deny
        raise AttributeError(name)
