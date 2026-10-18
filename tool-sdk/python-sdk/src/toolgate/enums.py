# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The neutral enumerations of the authoring contract. Each member's value is its name."""

from __future__ import annotations

from enum import Enum

__all__ = [
    "AuthType", "BusinessKey", "EffectSafety", "HttpMethod", "IdempotencyScope", "KeyNamespace", "OpenWorld",
    "RuntimeTier", "SecretScope", "Streaming", "ValueType", "InvocationMode",
]


class _Named(str, Enum):
    def __str__(self) -> str:
        return self.value


class AuthType(_Named):
    API_KEY = "API_KEY"
    BASIC = "BASIC"
    OAUTH2_CLIENT_CREDENTIALS = "OAUTH2_CLIENT_CREDENTIALS"
    TOKEN_EXCHANGE = "TOKEN_EXCHANGE"
    CONNECTION = "CONNECTION"
    CUSTOM = "CUSTOM"


class BusinessKey(_Named):
    DEFAULT = "DEFAULT"
    REQUIRED = "REQUIRED"
    NOT_REQUIRED = "NOT_REQUIRED"


class EffectSafety(_Named):
    READ_ONLY = "READ_ONLY"
    IDEMPOTENT = "IDEMPOTENT"
    NON_IDEMPOTENT = "NON_IDEMPOTENT"
    UNKNOWN = "UNKNOWN"


class HttpMethod(_Named):
    GET = "GET"
    HEAD = "HEAD"
    POST = "POST"
    PUT = "PUT"
    PATCH = "PATCH"
    DELETE = "DELETE"


class IdempotencyScope(_Named):
    BUSINESS_KEY = "BUSINESS_KEY"
    PER_INVOCATION = "PER_INVOCATION"


class KeyNamespace(_Named):
    AGENT = "AGENT"
    DELEGATED_USER = "DELEGATED_USER"
    TENANT = "TENANT"


class OpenWorld(_Named):
    DEFAULT = "DEFAULT"
    TRUE = "TRUE"
    FALSE = "FALSE"


class RuntimeTier(_Named):
    OCI_SANDBOX = "OCI_SANDBOX"
    WASI = "WASI"
    HOST_NATIVE = "HOST_NATIVE"


class SecretScope(_Named):
    GROUP = "GROUP"
    USER = "USER"


class Streaming(_Named):
    NONE = "NONE"
    RESPONSE = "RESPONSE"
    BIDIRECTIONAL = "BIDIRECTIONAL"


class ValueType(_Named):
    INFER = "INFER"
    STRING = "STRING"
    INT = "INT"
    LONG = "LONG"
    NUMBER = "NUMBER"
    BOOLEAN = "BOOLEAN"
    DATE = "DATE"
    DATE_TIME = "DATE_TIME"
    DURATION = "DURATION"
    ENUM = "ENUM"
    URI = "URI"
    OBJECT = "OBJECT"
    ARRAY = "ARRAY"
    BINARY = "BINARY"


class InvocationMode(_Named):
    STANDALONE = "STANDALONE"
    GOVERNED = "GOVERNED"
