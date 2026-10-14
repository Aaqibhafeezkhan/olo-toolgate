# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The ToolGate Tool SDK for Python: the authoring API of spec/authoring/python.md."""

from ._version import SDK_VERSION
from .accessors import declared
from .context import (CancellationToken, HttpClient, InputRequest, Invocation, Jwt, RawResponse, RouteCall,
                      TcpConnector, ToolContext, ToolResult, Values)
from .declarations import (ErrorDecl, Global, JwtClaim, Param, Secret, Variable, client_tool, destination, example,
                           http_auth, http_route, idempotency, reconcile, server_tool, tcp_destination, tool_package,
                           tool_variables)
from .enums import (AuthType, BusinessKey, EffectSafety, HttpMethod, IdempotencyScope, InvocationMode, KeyNamespace,
                    OpenWorld, RuntimeTier, SecretScope, Streaming, ValueType)
from .errors import (PlatformCode, PlatformError, ToolBusinessError, ToolConfigurationError, ToolError,
                     ToolInputError, ToolRetryableError, UpstreamError)

__version__ = SDK_VERSION

__all__ = [
    "SDK_VERSION", "declared",
    "CancellationToken", "HttpClient", "InputRequest", "Invocation", "Jwt", "RawResponse", "RouteCall",
    "TcpConnector", "ToolContext", "ToolResult", "Values",
    "ErrorDecl", "Global", "JwtClaim", "Param", "Secret", "Variable", "client_tool", "destination", "example",
    "http_auth", "http_route", "idempotency", "reconcile", "server_tool", "tcp_destination", "tool_package",
    "tool_variables",
    "AuthType", "BusinessKey", "EffectSafety", "HttpMethod", "IdempotencyScope", "InvocationMode", "KeyNamespace",
    "OpenWorld", "RuntimeTier", "SecretScope", "Streaming", "ValueType",
    "PlatformCode", "PlatformError", "ToolBusinessError", "ToolConfigurationError", "ToolError", "ToolInputError",
    "ToolRetryableError", "UpstreamError",
]
