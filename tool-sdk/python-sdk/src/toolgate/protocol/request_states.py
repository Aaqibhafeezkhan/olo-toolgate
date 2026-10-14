# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Adapter A3 (protocol-layer.md section 3): MRTR ``requestState`` in standalone mode.

A compact JWS, HS256, with a random per-process key of 256 bits, so a token from another process never verifies. The
payload binds the tool, the JCS digest of the arguments, the identity and the scope, lives at most 10 minutes, and its
nonce is accepted once. It also carries the input responses of earlier rounds, which the client sent and may read.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import secrets
import threading
import time
import uuid
from typing import Any, Callable, Dict, Optional

from .._json import as_long, loads, path, text
from ..jcs import protocol_canonical, protocol_digest
from .errors import INVALID_PARAMS, ProtocolError

__all__ = ["RequestStates", "b64url", "b64url_decode"]


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(s: str) -> bytes:
    """Base64url with optional padding; anything outside the alphabet is refused."""
    stripped = s.rstrip("=")
    if len(s) - len(stripped) > 2:
        raise ValueError("bad padding")
    padded = stripped + "=" * (-len(stripped) % 4)
    try:
        return base64.b64decode(padded.encode("ascii"), altchars=b"-_", validate=True)
    except (binascii.Error, UnicodeEncodeError) as e:
        raise ValueError(str(e))


class RequestStates:
    LIFETIME_MS = 10 * 60 * 1000
    SCOPE = "standalone"
    HEADER = b64url(b'{"alg":"HS256","typ":"JWT"}')

    def __init__(self, key: Optional[bytes] = None, clock: Optional[Callable[[], int]] = None) -> None:
        key = secrets.token_bytes(32) if key is None else bytes(key)
        if len(key) < 32:
            raise ValueError("the requestState key must be at least 256 bits")
        self._key = key
        self._clock = clock or (lambda: int(time.time() * 1000))
        self._spent: Dict[str, int] = {}
        self._lock = threading.Lock()

    def mint(self, tool_id: str, arguments: Any, meta: Any, responses: Dict[str, Any]) -> str:
        """The state for the next round of ``tool_id`` with ``arguments``, carrying ``responses``."""
        at = self._clock()
        payload = {
            "toolId": tool_id,
            "argsHash": protocol_digest(arguments),
            "identity": meta.owner,
            "scope": self.SCOPE,
            "exp": (at + self.LIFETIME_MS) // 1000,
            "nonce": str(uuid.uuid4()),
            "responses": responses,
        }
        body = b64url(protocol_canonical(payload).encode("utf-8"))
        signing_input = self.HEADER + "." + body
        return signing_input + "." + b64url(self._sign(signing_input))

    def verify(self, token: str, tool_id: str, arguments: Any, meta: Any) -> Dict[str, Any]:
        """Verifies a retry's state before anything runs and spends its nonce; returns the responses it carries.

        Every refusal is the same -32602, so a caller learns nothing about why or about another identity's call.
        """
        parts = token.split(".")
        if len(parts) != 3 or parts[0] != self.HEADER:
            raise _refused()
        try:
            signature = b64url_decode(parts[2])
            payload = loads(b64url_decode(parts[1]))
        except (ValueError, UnicodeDecodeError):
            raise _refused()
        if not hmac.compare_digest(signature, self._sign(parts[0] + "." + parts[1])) or not isinstance(payload, dict):
            raise _refused()
        at = self._clock()
        exp = as_long(path(payload, "exp"), 0)
        if exp * 1000 <= at \
                or tool_id != text(path(payload, "toolId")) \
                or protocol_digest(arguments) != text(path(payload, "argsHash")) \
                or meta.owner != text(path(payload, "identity")) \
                or self.SCOPE != text(path(payload, "scope")) \
                or not isinstance(payload.get("responses"), dict):
            raise _refused()
        with self._lock:
            for nonce in [n for n, e in self._spent.items() if e <= at]:
                del self._spent[nonce]
            nonce = text(path(payload, "nonce"))
            if nonce in self._spent:
                raise _refused()
            self._spent[nonce] = exp * 1000
        return payload["responses"]

    def _sign(self, signing_input: str) -> bytes:
        return hmac.new(self._key, signing_input.encode("ascii", "replace"), hashlib.sha256).digest()


def _refused() -> ProtocolError:
    return ProtocolError(INVALID_PARAMS, "Invalid requestState")

