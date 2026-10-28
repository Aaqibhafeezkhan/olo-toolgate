# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Descriptor vector tool-01. Mirrors the Java processor fixture ChecksumTools."""

from dataclasses import dataclass
from typing import Annotated

from toolgate import EffectSafety, Param, ToolContext, client_tool


@dataclass
class Checksum:
    sha256: Annotated[str, Param(description="", pattern="^[0-9a-f]{64}$")]


class ChecksumTools:
    @client_tool(name="files.checksum", title="Checksum a file",
                 description="SHA-256 of a file in the protected HotFolder.", effect=EffectSafety.READ_ONLY)
    def checksum(self, path: Annotated[str, Param(description="Path relative to the HotFolder", min_length=1,
                                                  max_length=1024)],
                 *, context: ToolContext) -> Checksum:
        return Checksum("")
