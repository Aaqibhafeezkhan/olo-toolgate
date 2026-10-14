# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The error hierarchy of spec/authoring/errors.md: identical codes and retry rules in every SDK."""

from __future__ import annotations

import datetime as _dt
from enum import Enum
from typing import Any, Mapping, Optional

__all__ = [
    "ToolError",
    "ToolInputError",
    "ToolBusinessError",
    "ToolRetryableError",
    "UpstreamError",
    "ToolConfigurationError",
    "PlatformError",
    "PlatformCode",
]


class ToolError(Exception):
    """The base of every error a tool raises on purpose. Raise a subclass, never this class."""

    def __init__(self, code: str, message: str) -> None:
        if type(self) is ToolError:
            raise TypeError("ToolError is abstract; raise one of its subclasses")
        super().__init__(message)
        self._code = code
        self._message = message

    @property
    def code(self) -> str:
        return self._code

    @property
    def message(self) -> str:
        return self._message


class ToolInputError(ToolError):
    """The arguments are wrong in a way the input schema cannot express. Code INVALID_ARGUMENT."""

    def __init__(self, message: str) -> None:
        super().__init__("INVALID_ARGUMENT", message)


class ToolBusinessError(ToolError):
    """A declared business outcome, such as ORDER_NOT_FOUND. The code must be in the tool's errors."""

    def __init__(self, code: str, message: str, data: Optional[Mapping[str, Any]] = None) -> None:
        super().__init__(code, message)
        self._data = dict(data or {})

    @property
    def data(self) -> dict:
        return dict(self._data)


class ToolRetryableError(ToolError):
    """A declared transient failure. It is retryable only for READ_ONLY and IDEMPOTENT tools."""

    def __init__(self, code: str, message: str, retry_after: Optional[_dt.timedelta] = None) -> None:
        super().__init__(code, message)
        self._retry_after = retry_after

    @property
    def retry_after(self) -> Optional[_dt.timedelta]:
        return self._retry_after


class UpstreamError(ToolError):
    """A route call that failed. The SDK raises it; tools may let it escape or catch it."""

    def __init__(self, code: str, route: str, status: Optional[int] = None, upstream_request_id: Optional[str] = None,
                 effect_uncertain: bool = False, body: Optional[str] = None) -> None:
        super().__init__(code, "Upstream call on route " + route + " failed: " + code)
        self._route = route
        self._status = status
        self._upstream_request_id = upstream_request_id
        self._effect_uncertain = effect_uncertain
        self._body = body

    @property
    def route(self) -> str:
        return self._route

    @property
    def status(self) -> Optional[int]:
        return self._status

    @property
    def upstream_request_id(self) -> Optional[str]:
        return self._upstream_request_id

    @property
    def effect_uncertain(self) -> bool:
        """True when the request was fully sent and no complete response arrived."""
        return self._effect_uncertain

    @property
    def body(self) -> Optional[str]:
        return self._body


class ToolConfigurationError(ToolError):
    """A configuration value the tool needs is missing or wrong. Code CONFIGURATION."""

    def __init__(self, message: str) -> None:
        super().__init__("CONFIGURATION", message)


class PlatformCode(str, Enum):
    """The platform codes of errors.md section 2."""

    CANCELLED = "CANCELLED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    ROUTE_REFUSED = "ROUTE_REFUSED"
    AUDIT_UNAVAILABLE = "AUDIT_UNAVAILABLE"
    CLAIMS_UNAVAILABLE = "CLAIMS_UNAVAILABLE"
    CLIENT_TOOLS_UNAVAILABLE = "CLIENT_TOOLS_UNAVAILABLE"
    UNSUPPORTED_TIER = "UNSUPPORTED_TIER"
    CONCURRENCY_LIMIT = "CONCURRENCY_LIMIT"


class PlatformError(ToolError):
    """An error the SDK raises on the platform's behalf. Tools do not raise it."""

    def __init__(self, code: PlatformCode, message: str) -> None:
        code = PlatformCode(code)
        super().__init__(code.value, message)
        self._platform_code = code

    @property
    def platform_code(self) -> PlatformCode:
        return self._platform_code
