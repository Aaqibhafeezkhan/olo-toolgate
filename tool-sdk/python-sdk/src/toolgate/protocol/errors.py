# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""JSON-RPC errors of the MCP 2026-07-28 core."""

from __future__ import annotations

from typing import Any, Optional

__all__ = ["ProtocolError", "PARSE_ERROR", "INVALID_REQUEST", "METHOD_NOT_FOUND", "INVALID_PARAMS", "INTERNAL_ERROR",
           "HEADER_MISMATCH", "MISSING_REQUIRED_CLIENT_CAPABILITY", "UNSUPPORTED_PROTOCOL_VERSION"]

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
HEADER_MISMATCH = -32020
MISSING_REQUIRED_CLIENT_CAPABILITY = -32021
UNSUPPORTED_PROTOCOL_VERSION = -32022


class ProtocolError(Exception):
    """A JSON-RPC error with its code and optional data."""

    PARSE_ERROR = PARSE_ERROR
    INVALID_REQUEST = INVALID_REQUEST
    METHOD_NOT_FOUND = METHOD_NOT_FOUND
    INVALID_PARAMS = INVALID_PARAMS
    INTERNAL_ERROR = INTERNAL_ERROR
    HEADER_MISMATCH = HEADER_MISMATCH
    MISSING_REQUIRED_CLIENT_CAPABILITY = MISSING_REQUIRED_CLIENT_CAPABILITY
    UNSUPPORTED_PROTOCOL_VERSION = UNSUPPORTED_PROTOCOL_VERSION

    def __init__(self, code: int, message: str, data: Optional[Any] = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data

    @classmethod
    def missing_extension(cls, extension: str) -> "ProtocolError":
        """-32021 naming one required extension, with the ``requiredCapabilities`` data the schema requires."""
        return cls(MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability",
                   {"requiredCapabilities": {"extensions": {extension: {}}}})

    @classmethod
    def missing_capability(cls, capability: str) -> "ProtocolError":
        """-32021 for a client capability such as ``elicitation``."""
        return cls(MISSING_REQUIRED_CLIENT_CAPABILITY, "Missing required client capability",
                   {"requiredCapabilities": {capability: {}}})
