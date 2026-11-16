# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""TLS-only transport for trigger subscriptions and workflow dispatch admission."""
from __future__ import annotations

import json
import logging
import re
import ssl
import time
from typing import Callable
import urllib.error
import urllib.parse
import urllib.request

from websockets.sync.client import reconnect

from .trigger_bridge import (CURSOR, DispatchReceipt, ListenerFailure, MAX_FRAME_BYTES, PROTOCOL,
    Reason, Subscription, TriggerBridge, decode_frame, validate_receipt)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


class _TicketConnection(reconnect):
    def process_redirect(self, error):
        # Never forward the one-use ticket subprotocol to a redirected endpoint.
        return error


def _quiet_logger():
    logger = logging.Logger("toolgate.runtime.trigger.transport")
    logger.disabled = True
    logger.propagate = False
    return logger


class ControlTriggers:
    """Subscription, ticket and ADR 063 dispatch admission APIs.

    404/501/503, redirects, auth failures and malformed responses fail closed.
    No API availability, persistence or workflow execution is simulated here.
    Credentials come from infrastructure bootstrap, never an event or profile.
    """
    def __init__(self, origin: str, token: Callable[[], str], *, ca_file: str | None = None,
                 timeout_seconds: float = 10):
        parsed = urllib.parse.urlsplit(origin)
        try:
            parsed.port
        except ValueError:
            raise ValueError("Control requires a valid HTTPS origin") from None
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment or any(ord(c) < 33 for c in origin)):
            raise ValueError("Control requires a valid HTTPS origin")
        if not 0 < timeout_seconds <= 30:
            raise ValueError("Transport timeout must be positive and at most 30 seconds")
        self.origin, self._token, self.timeout = origin.rstrip("/"), token, timeout_seconds
        self._tls = ssl.create_default_context(cafile=ca_file)
        # Quickstart's explicitly supplied CA may predate OpenSSL strict key-identifier checks.
        # Chain, hostname and expiry verification remain active.
        if ca_file is not None:
            self._tls.verify_flags &= ~getattr(ssl, "VERIFY_X509_STRICT", 0)
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect(),
                                                   urllib.request.HTTPSHandler(context=self._tls))

    def _request(self, method, path, body=None, *, timeout_seconds=None):
        try:
            token = self._token()
            if (not isinstance(token, str) or not 1 <= len(token) <= 8192
                    or any(ord(c) < 33 or ord(c) > 126 for c in token)):
                raise ValueError()
            raw = None if body is None else json.dumps(body, allow_nan=False, separators=(",", ":")).encode()
            request = urllib.request.Request(self.origin + path, raw,
                {"Authorization": "Bearer " + token, "Content-Type": "application/json", "Accept": "application/json"}, method=method)
            timeout = self.timeout if timeout_seconds is None else min(self.timeout, timeout_seconds)
            if not 0 < timeout <= 30:
                raise ValueError()
            with self._opener.open(request, timeout=timeout) as response:
                if not 200 <= response.status < 300 or response.headers.get_content_type() != "application/json":
                    raise ValueError()
                value = response.read(MAX_FRAME_BYTES + 1)
                if len(value) > MAX_FRAME_BYTES:
                    raise ValueError()
                return decode_frame(value.decode("utf-8"))
        except Exception:
            raise ListenerFailure(Reason.CONNECTION_LOST) from None

    def verify_subscription(self, subscription: Subscription):
        value = self._request("GET", "/api/control/v1/triggers/subscriptions/" + subscription.name)
        if (value.get("apiVersion") != "toolgate.olo/v1" or value.get("kind") != "TriggerSubscription" or not isinstance(value.get("metadata"), dict)
                or value["metadata"].get("name") != subscription.name
                or value["metadata"].get("uid") != subscription.uid
                or not isinstance(value.get("status"), dict) or value["status"].get("phase") != "Active"):
            raise ListenerFailure(Reason.SUBSCRIPTION_CHANGED)

    def ticket(self):
        value = self._request("POST", "/api/control/v1/triggers/stream/tickets", {})
        ticket = value.get("ticket")
        if not isinstance(ticket, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", ticket):
            raise ListenerFailure(Reason.INVALID_FRAME)
        return ticket

    def admit(self, subscription_uid: str, cursor: str, *, timeout_seconds: float):
        subscription = Subscription("validation", subscription_uid)
        if not isinstance(cursor, str) or not CURSOR.fullmatch(cursor):
            raise ValueError("Invalid delivered trigger cursor")
        value = self._request("POST", "/api/control/v1/workflow-dispatches:admit",
                              {"subscriptionUid": subscription_uid, "cursor": cursor}, timeout_seconds=timeout_seconds)
        if set(value) - {"subscriptionUid", "cursor", "admissionId", "disposition", "runId"}:
            raise ListenerFailure(Reason.INVALID_RECEIPT)
        if "runId" in value and not isinstance(value["runId"], str):
            raise ListenerFailure(Reason.INVALID_RECEIPT)
        receipt = DispatchReceipt(value.get("subscriptionUid"), value.get("cursor"), value.get("admissionId"),
                                  value.get("disposition"), value.get("runId"))
        validate_receipt(receipt, subscription, cursor)
        return receipt

    def connect(self, ticket: str):
        return _TicketConnection("wss://" + self.origin.removeprefix("https://") + "/api/control/v1/triggers/stream",
            ssl=self._tls, subprotocols=[PROTOCOL, "ticket." + ticket], proxy=None, compression=None,
            open_timeout=self.timeout, close_timeout=5, max_size=MAX_FRAME_BYTES, max_queue=1,
            logger=_quiet_logger())


class TriggerListener:
    """One connection attempt. Every reconnect revalidates UID and obtains a fresh ticket.

    Durable admission enqueues work in Control. This listener does not execute event
    payloads, select workflows, mint identities or invent leases for the Runner.
    """
    def __init__(self, control: ControlTriggers, subscription: Subscription):
        self.control, self.subscription = control, subscription

    def listen_once(self, *, max_events: int | None = None, idle_timeout: float | None = None) -> tuple[int, DispatchReceipt | None]:
        if max_events is not None and (type(max_events) is not int or max_events < 1):
            raise ValueError("max_events must be a positive integer")
        if idle_timeout is not None and not 0 < idle_timeout <= 3600:
            raise ValueError("idle_timeout must be positive and at most 3600 seconds")
        self.control.verify_subscription(self.subscription)
        ticket = self.control.ticket()
        bridge = TriggerBridge(self.subscription, self.control, timeout_seconds=self.control.timeout)
        processed, last = 0, None
        deadline = None if idle_timeout is None else time.monotonic() + idle_timeout
        try:
            with self.control.connect(ticket) as connection:
                if connection.subprotocol != PROTOCOL:
                    raise ListenerFailure(Reason.CONNECTION_LOST)
                # Omit resumeAfter: only the durable server-side cursor is authoritative after ACK loss.
                connection.send(json.dumps({"type": "subscribe", "subscription": self.subscription.name}))
                while max_events is None or processed < max_events:
                    remaining = self.control.timeout if deadline is None else min(self.control.timeout, deadline - time.monotonic())
                    if remaining <= 0:
                        raise ListenerFailure(Reason.CONNECTION_LOST)
                    try:
                        raw = connection.recv(timeout=remaining)
                    except TimeoutError:
                        continue  # Idle connections use protocol pings; no synthetic ACK or cursor movement.
                    receipt = bridge.handle(raw, connection.send)
                    if receipt is not None:
                        processed += 1
                        last = receipt
                        if idle_timeout is not None:
                            deadline = time.monotonic() + idle_timeout
        except ListenerFailure:
            raise
        except Exception:
            raise ListenerFailure(Reason.CONNECTION_LOST) from None
        return processed, last
