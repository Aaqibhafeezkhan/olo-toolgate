# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Serial durable-admission bridge for the existing toolgate.triggers.v1 stream."""
from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
import json
import re
import threading
from typing import Callable, Protocol

from langsmith import tracing_context

PROTOCOL = "toolgate.triggers.v1"
MAX_FRAME_BYTES = 65536
NAME = re.compile(r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
CURSOR = re.compile(r"^[0-9]{16}$")
IDENTIFIER = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$")


class Reason(StrEnum):
    INVALID_FRAME = "INVALID_FRAME"
    NOT_SUBSCRIBED = "NOT_SUBSCRIBED"
    WRONG_SUBSCRIPTION = "WRONG_SUBSCRIPTION"
    OUT_OF_ORDER = "OUT_OF_ORDER"
    INVALID_RECEIPT = "INVALID_RECEIPT"
    ADMISSION_UNAVAILABLE = "ADMISSION_UNAVAILABLE"
    CONNECTION_LOST = "CONNECTION_LOST"
    STREAM_GAP = "STREAM_GAP"
    STREAM_REVOKED = "STREAM_REVOKED"
    STREAM_ERROR = "STREAM_ERROR"
    SUBSCRIPTION_CHANGED = "SUBSCRIPTION_CHANGED"
    STOPPED = "STOPPED"


class ListenerFailure(Exception):
    def __init__(self, code: Reason):
        self.code = Reason(code)
        super().__init__(self.code.value)


@dataclass(frozen=True)
class Subscription:
    name: str
    uid: str

    def __post_init__(self):
        if (not isinstance(self.name, str) or not NAME.fullmatch(self.name)
                or not isinstance(self.uid, str) or not 1 <= len(self.uid) <= 160
                or any(ord(c) < 33 or ord(c) > 126 for c in self.uid)):
            raise ValueError("Invalid pinned trigger subscription")


@dataclass(frozen=True)
class DispatchReceipt:
    subscription_uid: str
    cursor: str
    admission_id: str
    disposition: str
    run_id: str | None = None


class Admissions(Protocol):
    """Control must authenticate ownership, read its stored event, resolve the published
    binding and current service authority, and durably dedupe (tenant, UID, cursor).
    No receipt may be returned before commit. This port is not a local ledger.
    """
    def admit(self, subscription_uid: str, cursor: str, *, timeout_seconds: float) -> DispatchReceipt: ...


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON member")
        result[key] = value
    return result


def _constant(_):
    raise ValueError("Non-finite JSON number")


def decode_frame(raw: str) -> dict:
    try:
        if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
            raise ValueError()
        value = json.loads(raw, object_pairs_hook=_object,
                           parse_constant=_constant)
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ListenerFailure(Reason.INVALID_FRAME) from None


def validate_receipt(receipt: DispatchReceipt, subscription: Subscription, cursor: str) -> None:
    if (not isinstance(receipt, DispatchReceipt)
            or receipt.subscription_uid != subscription.uid or receipt.cursor != cursor
            or not isinstance(receipt.admission_id, str) or not IDENTIFIER.fullmatch(receipt.admission_id)
            or not isinstance(receipt.disposition, str) or receipt.disposition not in {"ADMITTED", "DUPLICATE", "REJECTED", "FILTERED"}
            or (receipt.disposition == "ADMITTED" and receipt.run_id is None)
            or (receipt.disposition in {"REJECTED", "FILTERED"} and receipt.run_id is not None)
            or (receipt.run_id is not None and (not isinstance(receipt.run_id, str)
                                               or not IDENTIFIER.fullmatch(receipt.run_id)))):
        raise ListenerFailure(Reason.INVALID_RECEIPT)


class TriggerBridge:
    """One connection, one pinned subscription, no local cursor/dedupe/authority fallback.

    A failure permanently stops this connection's bridge, so a later cumulative ACK
    cannot skip an unresolved event. Reconnect with a new bridge and the server cursor.
    Event content never reaches the admission request, workflow selector or run identity.
    """
    def __init__(self, subscription: Subscription, admissions: Admissions, *, timeout_seconds: float = 10):
        if not 0 < timeout_seconds <= 30:
            raise ValueError("Admission timeout must be positive and at most 30 seconds")
        self.subscription, self.admissions, self.timeout = subscription, admissions, timeout_seconds
        self._subscribed = self._stopped = False
        self._last_cursor: str | None = None
        self._lock = threading.Lock()

    def handle(self, raw: str, send: Callable[[str], None]) -> DispatchReceipt | None:
        with self._lock:
            if self._stopped:
                raise ListenerFailure(Reason.STOPPED)
            try:
                return self._handle(decode_frame(raw), send)
            except BaseException:
                self._stopped = True
                raise

    def _send(self, send, value):
        try:
            send(json.dumps(value, separators=(",", ":")))
        except Exception:
            raise ListenerFailure(Reason.CONNECTION_LOST) from None

    def _handle(self, frame, send):
        kind = frame.get("type")
        if kind == "error":
            raise ListenerFailure(Reason.STREAM_ERROR)
        if frame.get("subscription") != self.subscription.name:
            raise ListenerFailure(Reason.WRONG_SUBSCRIPTION)
        if kind == "gap":
            raise ListenerFailure(Reason.STREAM_GAP)
        if kind == "revoked":
            raise ListenerFailure(Reason.STREAM_REVOKED)
        if kind == "subscribed":
            if set(frame) != {"type", "subscription"}:
                raise ListenerFailure(Reason.INVALID_FRAME)
            self._subscribed = True
            return None
        if kind != "event" or set(frame) != {"type", "subscription", "cursor", "event"}:
            raise ListenerFailure(Reason.INVALID_FRAME)
        if not self._subscribed:
            raise ListenerFailure(Reason.NOT_SUBSCRIBED)
        cursor = frame["cursor"]
        if not isinstance(cursor, str) or not CURSOR.fullmatch(cursor) or not isinstance(frame["event"], dict):
            raise ListenerFailure(Reason.INVALID_FRAME)
        if self._last_cursor is not None and cursor < self._last_cursor:
            raise ListenerFailure(Reason.OUT_OF_ORDER)
        try:
            with tracing_context(enabled=False):
                receipt = self.admissions.admit(self.subscription.uid, cursor, timeout_seconds=self.timeout)
        except Exception:
            # A denial or timeout without a durable receipt is never converted into a terminal ACK.
            raise ListenerFailure(Reason.ADMISSION_UNAVAILABLE) from None
        validate_receipt(receipt, self.subscription, cursor)
        self._send(send, {"type": "ack", "subscription": self.subscription.name, "cursor": cursor})
        self._last_cursor = cursor
        self._send(send, {"type": "credit", "n": 1})
        return receipt
