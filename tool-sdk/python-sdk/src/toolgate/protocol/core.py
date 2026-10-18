# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Adapter A1: the MCP 2026-07-28 server core (tool-sdk/spec/protocol-layer.md section 3).

Stateless per request; the only connection state is the stdio era lock and the connection's ``subscriptions/listen``
streams. A port of the Java SDK's ProtocolCore, kept on the ToolGate core until the official Python MCP SDK ships the
2026-07-28 core.
"""

from __future__ import annotations

import abc
import threading
import uuid
from typing import Any, Callable, Dict, FrozenSet, Optional

from .._json import MISSING, dumps, is_int, text
from .errors import (HEADER_MISMATCH, INTERNAL_ERROR, INVALID_PARAMS, INVALID_REQUEST, METHOD_NOT_FOUND,
                     UNSUPPORTED_PROTOCOL_VERSION, ProtocolError)
from .identity import VerifiedIdentity
from .request_states import RequestStates

__all__ = ["VERSION", "TASKS", "RequestMeta", "ToolCatalog", "Connection", "ProtocolCore", "error", "http_status"]

VERSION = "2026-07-28"
TASKS = "io.modelcontextprotocol/tasks"
META_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId"
_TOP_LEVEL = {"jsonrpc", "id", "method", "params"}

#: The client capability each MRTR input request method needs (SEP-2322).
INPUT_CAPABILITY = {"elicitation/create": "elicitation", "sampling/createMessage": "sampling", "roots/list": "roots"}

Sink = Callable[[Dict[str, Any]], None]


class RequestMeta:
    """What one request carries besides its parameters."""

    TASKS = TASKS

    def __init__(self, protocol_version: str, client_capabilities: Dict[str, Any], extensions: FrozenSet[str],
                 transport: str, owner: str, identity: Optional[VerifiedIdentity], progress_token: Any,
                 notifications: Sink, input_responses: Dict[str, Any], idempotency_key: Optional[str]) -> None:
        self.protocol_version = protocol_version
        self.client_capabilities = client_capabilities
        self.extensions = extensions
        self.transport = transport
        self.owner = owner
        self.identity = identity
        self.progress_token = progress_token
        self.notifications = notifications
        self.input_responses = input_responses
        self.idempotency_key = idempotency_key

    def declares(self, extension: str) -> bool:
        return extension in self.extensions

    def declares_capability(self, capability: str) -> bool:
        """Whether ``clientCapabilities`` declares a capability such as ``elicitation``, ``sampling`` or ``roots``."""
        return isinstance(self.client_capabilities.get(capability), dict)

    def with_input_responses(self, responses: Dict[str, Any]) -> "RequestMeta":
        return RequestMeta(self.protocol_version, self.client_capabilities, self.extensions, self.transport, self.owner,
                           self.identity, self.progress_token, self.notifications, responses, self.idempotency_key)


class ToolCatalog(abc.ABC):
    @abc.abstractmethod
    def server_info(self) -> Dict[str, Any]:
        """Server name and version for ``io.modelcontextprotocol/serverInfo``."""

    @abc.abstractmethod
    def discover_meta(self) -> Dict[str, Any]:
        """Extra members of the ``server/discover`` result ``_meta``, such as descriptor digests."""

    @abc.abstractmethod
    def tools(self, meta: RequestMeta) -> list:
        """The ``tools`` array for this request's capabilities."""

    @abc.abstractmethod
    def call(self, name: str, arguments: Dict[str, Any], meta: RequestMeta) -> Dict[str, Any]:
        """Runs one ``tools/call``: the result without ``resultType``, or a ``CreateTaskResult``."""

    def tasks(self) -> Any:
        """The Tasks extension (adapter A2) this catalog serves, or None."""
        return None


class Connection:
    """One connection. A stdio connection is locked to the era of its first accepted message; HTTP is stateless."""

    def __init__(self, transport: str, owner: Optional[str] = None, identity: Optional[VerifiedIdentity] = None) -> None:
        self.transport = transport
        self.identity = identity
        if identity is not None:
            self.owner = identity.owner
        else:
            self.owner = owner if owner is not None else "connection:" + str(uuid.uuid4())
        self._era: Optional[str] = None
        self._lock = threading.Lock()

    def lock(self, version: str) -> None:
        if self.transport != "stdio":
            return
        with self._lock:
            if self._era is None:
                self._era = version
            elif self._era != version:
                raise ProtocolError(UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", unsupported(version))


class _Subscription:
    def __init__(self, connection: Connection, id: Any, sink: Sink, tools: bool, tasks: bool, owner: str) -> None:
        self.connection = connection
        self.id = id
        self.sink = sink
        self.tools = tools
        self.tasks = tasks
        self.owner = owner


def http_status(code: int) -> int:
    """The HTTP status for a JSON-RPC error code (Streamable HTTP, 2026-07-28)."""
    if code == METHOD_NOT_FOUND:
        return 404
    if code == INTERNAL_ERROR:
        return 500
    return 400


def error(id: Any, e: ProtocolError) -> Dict[str, Any]:
    """A JSON-RPC error response."""
    err: Dict[str, Any] = {"code": e.code, "message": e.message}
    if e.data is not None:
        err["data"] = e.data
    return {"jsonrpc": "2.0", "id": id, "error": err}


def unsupported(requested: Optional[str]) -> Dict[str, Any]:
    return {"supported": [VERSION], "requested": requested}


def _valid_id(id: Any) -> bool:
    return (isinstance(id, str) and len(id) <= 128) or is_int(id)


class ProtocolCore:
    VERSION = VERSION

    def __init__(self, catalog: ToolCatalog, request_states: Optional[RequestStates] = None) -> None:
        self.catalog = catalog
        self.request_states = request_states or RequestStates()
        self._subscriptions: Dict[str, _Subscription] = {}
        self._lock = threading.Lock()
        tasks = catalog.tasks()
        if tasks is not None:
            tasks.on_change(self._task_changed)

    def handle(self, message: Any, connection: Connection, headers: Optional[Callable[[str], Optional[str]]] = None,
               notify: Optional[Sink] = None) -> Optional[Dict[str, Any]]:
        """Handles one parsed message; returns the response, or None for notifications, client responses and an
        accepted ``subscriptions/listen``, whose frames go to ``notify``.

        ``headers`` looks up HTTP request headers case-insensitively, or is None over stdio.
        """
        sink: Sink = notify if notify is not None else (lambda frame: None)
        if not isinstance(message, dict):
            return error(None, ProtocolError(INVALID_REQUEST, "Invalid request"))
        if "method" not in message:
            return None  # a response from the client; the core sends no requests
        if "id" not in message:
            if message.get("method") == "notifications/cancelled":
                params = message.get("params")
                if isinstance(params, dict) and "requestId" in params:
                    self.unsubscribe(connection, params["requestId"])
            return None
        id = message["id"]
        try:
            _validate(message)
            method = message["method"]
            params = message["params"]
            if headers is not None and headers("MCP-Protocol-Version") is None:
                raise ProtocolError(HEADER_MISMATCH, "Missing MCP-Protocol-Version header")
            meta = params.get("_meta")
            if not isinstance(meta, dict):
                meta = {}
            version = meta.get(META_VERSION, MISSING)
            if version is MISSING:
                if method == "initialize":
                    requested = text(params.get("protocolVersion", MISSING), "unknown")
                    # Refused on a 2026-07-28 connection by the era lock, and on a fresh one until the 2025-11-25
                    # session path through the official SDK arrives with the legacy adapter.
                    raise ProtocolError(UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                                        unsupported(requested))
                raise ProtocolError(INVALID_PARAMS, "_meta " + META_VERSION + " is required")
            capabilities = meta.get(META_CAPABILITIES)
            if not isinstance(capabilities, dict):
                raise ProtocolError(INVALID_PARAMS, "_meta " + META_CAPABILITIES + " is required")
            requested = version if isinstance(version, str) else dumps(version)
            if headers is not None and requested != (headers("MCP-Protocol-Version") or "").strip():
                raise ProtocolError(HEADER_MISMATCH, "MCP-Protocol-Version does not match _meta")
            if requested != VERSION:
                raise ProtocolError(UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version",
                                    unsupported(requested))
            if headers is not None:
                _check_routing_headers(method, params, headers)
            connection.lock(VERSION)
            extensions = capabilities.get("extensions")
            request = RequestMeta(VERSION, capabilities,
                                  frozenset(extensions) if isinstance(extensions, dict) else frozenset(),
                                  connection.transport, connection.owner, connection.identity,
                                  meta.get("progressToken"), sink, {},
                                  None if headers is None else headers("Idempotency-Key"))
            if method == "server/discover":
                result = self._discover()
            elif method == "tools/list":
                result = self._list(request)
            elif method == "tools/call":
                result = self._call(params, request)
            elif method in ("tasks/get", "tasks/update", "tasks/cancel"):
                result = self._task(method, params, request)
            elif method == "subscriptions/listen":
                self._listen(id, params, request, connection, sink)
                return None
            else:
                raise ProtocolError(METHOD_NOT_FOUND, "Method not found")
            if "resultType" not in result:
                result["resultType"] = "complete"
            result_meta = result.get("_meta")
            if not isinstance(result_meta, dict):
                result_meta = result["_meta"] = {}
            result_meta[META_SERVER_INFO] = self.catalog.server_info()
            return {"jsonrpc": "2.0", "id": id, "result": result}
        except ProtocolError as e:
            return error(id if _valid_id(id) else None, e)
        except Exception:
            return error(id if _valid_id(id) else None, ProtocolError(INTERNAL_ERROR, "Internal error"))

    def tools_list_changed(self) -> None:
        """Sends ``notifications/tools/list_changed`` to every stream that asked for it."""
        for s in self._streams():
            if s.tools:
                self._send(s, "notifications/tools/list_changed", {})

    def unsubscribe(self, connection: Connection, id: Any) -> None:
        """Ends a listen stream (client abort, write failure or ``notifications/cancelled``)."""
        if id is not MISSING:
            with self._lock:
                self._subscriptions.pop(_key(connection, id), None)

    def subscribed(self, connection: Connection, id: Any) -> bool:
        with self._lock:
            return _key(connection, id) in self._subscriptions

    def _streams(self) -> list:
        with self._lock:
            return list(self._subscriptions.values())

    def _listen(self, id: Any, params: Dict[str, Any], request: RequestMeta, connection: Connection,
                sink: Sink) -> None:
        filter = params.get("notifications", MISSING)
        if filter is not MISSING and not isinstance(filter, dict):
            raise ProtocolError(INVALID_PARAMS, "notifications must be an object")
        tools = isinstance(filter, dict) and filter.get("toolsListChanged") is True
        tasks = self.catalog.tasks() is not None and request.declares(TASKS)
        s = _Subscription(connection, id, sink, tools, tasks, request.owner)
        key = _key(connection, id)
        with self._lock:
            if key in self._subscriptions:
                raise ProtocolError(INVALID_PARAMS, "A listen stream with this id is already open")
            self._subscriptions[key] = s
        honoured: Dict[str, Any] = {}
        if tools:
            honoured["toolsListChanged"] = True
        self._send(s, "notifications/subscriptions/acknowledged", {"notifications": honoured})

    def _task_changed(self, t: Any) -> None:
        for s in self._streams():
            if not s.tasks or s.owner != t.owner:
                continue
            params: Dict[str, Any] = {"taskId": t.task_id, "status": t.status.wire}
            if t.status_message is not None:
                params["statusMessage"] = t.status_message
            self._send(s, "notifications/tasks", params)

    def _send(self, s: _Subscription, method: str, params: Dict[str, Any]) -> None:
        params["_meta"] = {META_SUBSCRIPTION: s.id}
        frame = {"jsonrpc": "2.0", "method": method, "params": params}
        try:
            s.sink(frame)
        except Exception:
            key = _key(s.connection, s.id)
            with self._lock:
                if self._subscriptions.get(key) is s:
                    del self._subscriptions[key]

    def _discover(self) -> Dict[str, Any]:
        capabilities: Dict[str, Any] = {"tools": {"listChanged": True}}
        if self.catalog.tasks() is not None:
            capabilities["extensions"] = {TASKS: {}}
        return {"supportedVersions": [VERSION], "capabilities": capabilities, "ttlMs": 0, "cacheScope": "private",
                "_meta": dict(self.catalog.discover_meta())}

    def _list(self, request: RequestMeta) -> Dict[str, Any]:
        return {"tools": self.catalog.tools(request), "ttlMs": 0, "cacheScope": "private"}

    def _call(self, params: Dict[str, Any], request: RequestMeta) -> Dict[str, Any]:
        # Unrecognised members, such as the pre-SEP-2663 "task" hint, are ignored (SEP-2322).
        name = params.get("name", MISSING)
        arguments = params.get("arguments", MISSING)
        responses = params.get("inputResponses", MISSING)
        state = params.get("requestState", MISSING)
        if not isinstance(name, str) or (arguments is not MISSING and not isinstance(arguments, dict)) \
                or (responses is not MISSING and not _valid_responses(responses)) \
                or (state is not MISSING and not isinstance(state, str)):
            raise ProtocolError(INVALID_PARAMS, "Invalid parameters")
        args = {} if arguments is MISSING else arguments
        # A3: the state is verified before anything runs. Answers sent without one are only the client's own answers.
        answers: Dict[str, Any] = {}
        if state is not MISSING:
            answers.update(self.request_states.verify(state, name, args, request))
        if responses is not MISSING:
            answers.update(responses)
        round = request.with_input_responses(answers)
        result = self.catalog.call(name, args, round)
        if result.get("resultType") == "input_required":
            inputs = result.get("inputRequests")
            if not isinstance(inputs, dict) or not inputs:
                raise RuntimeError("an InputRequiredResult needs inputRequests")
            for value in inputs.values():
                method = text(value.get("method")) if isinstance(value, dict) else ""
                capability = INPUT_CAPABILITY.get(method)
                if capability is None:
                    raise RuntimeError("not an MRTR input request: " + method)
                if not round.declares_capability(capability):
                    raise ProtocolError.missing_capability(capability)
            result["requestState"] = self.request_states.mint(name, args, round, answers)
        return result

    def _task(self, method: str, params: Dict[str, Any], request: RequestMeta) -> Dict[str, Any]:
        tasks = self.catalog.tasks()
        if tasks is None:
            raise ProtocolError(METHOD_NOT_FOUND, "Method not found")
        # The capability gate comes before any lookup, so an undeclared request learns nothing about task ids.
        if not request.declares(TASKS):
            raise ProtocolError.missing_extension(TASKS)
        if method == "tasks/get":
            return tasks.get(params, request)
        if method == "tasks/update":
            return tasks.update(params, request)
        return tasks.cancel(params, request)


def _check_routing_headers(method: str, params: Dict[str, Any], headers: Callable[[str], Optional[str]]) -> None:
    header = headers("Mcp-Method")
    if header is None or header.strip() != method:
        raise ProtocolError(HEADER_MISMATCH, "Mcp-Method does not match the request")
    name_key = {"tools/call": "name", "prompts/get": "name", "resources/read": "uri", "tasks/get": "taskId",
                "tasks/update": "taskId", "tasks/cancel": "taskId"}.get(method)
    if name_key is None:
        return
    name = headers("Mcp-Name")
    if name is None or name.strip() != text(params.get(name_key, MISSING)):
        raise ProtocolError(HEADER_MISMATCH, "Mcp-Name does not match the request")


def _key(connection: Connection, id: Any) -> str:
    # By owner, not by object: each HTTP request has its own Connection, and a later POST may cancel the stream.
    return connection.transport + ":" + connection.owner + ":" + dumps(id)


def _validate(message: Dict[str, Any]) -> None:
    if any(k not in _TOP_LEVEL for k in message):
        raise ProtocolError(INVALID_REQUEST, "Invalid request")
    if message.get("jsonrpc") != "2.0" or not _valid_id(message.get("id")) \
            or not isinstance(message.get("method"), str) or not isinstance(message.get("params"), dict):
        raise ProtocolError(INVALID_REQUEST, "Invalid request")


def _valid_responses(responses: Any) -> bool:
    """``inputResponses`` is an object of response objects (SEP-2322); anything else is a protocol error."""
    return isinstance(responses, dict) and all(isinstance(r, dict) for r in responses.values())
