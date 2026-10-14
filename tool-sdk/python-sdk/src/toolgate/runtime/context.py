# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The ToolContext of one standalone (or governed, or test) invocation."""

from __future__ import annotations

import datetime as _dt
import logging
import math
import threading
from typing import Any, Callable, Dict, List, Optional

from ..context import InputRequest
from ..enums import InvocationMode
from ..errors import PlatformCode, PlatformError, ToolConfigurationError, ToolInputError
from .converter import ConversionError, from_json, to_json
from .diagnostics import Diagnostics
from .redactor import Redactor, cap
from .schema import SchemaValidator
from .values import DeclaredValues

__all__ = ["StandaloneContext", "InputNeeded", "LOG_ENTRY_BYTES", "LOG_INVOCATION_BYTES"]

LOG_ENTRY_BYTES = 4 * 1024
LOG_INVOCATION_BYTES = 256 * 1024


class InputNeeded(Exception):
    """Ends a sync round that needs an answer the client has not sent yet."""

    def __init__(self) -> None:
        super().__init__("input required")


_LEVELS = {logging.DEBUG: "debug", logging.INFO: "info", logging.WARNING: "warn", logging.ERROR: "error",
           logging.CRITICAL: "error"}


class _LogHandler(logging.Handler):
    def __init__(self, context: "StandaloneContext") -> None:
        super().__init__(logging.DEBUG)
        self._context = context

    def emit(self, record: logging.LogRecord) -> None:
        try:
            message = record.getMessage()
        except Exception:
            message = str(record.msg)
        if record.exc_info and record.exc_info[1] is not None:
            message += ": " + repr(record.exc_info[1])
        level = _LEVELS.get(record.levelno, "info" if record.levelno < logging.WARNING else "error")
        self._context._write_log(level, message)


class _Invocation:
    def __init__(self, context: "StandaloneContext") -> None:
        self._c = context

    @property
    def invocation_id(self) -> str:
        return self._c.invocation_id

    @property
    def tool_id(self) -> str:
        return self._c.tool_id

    @property
    def agent_id(self) -> Optional[str]:
        return self._c._agent_id

    @property
    def user_id(self) -> Optional[str]:
        return self._c._user_id

    @property
    def catalog_revision(self) -> Optional[int]:
        return self._c._catalog_revision

    @property
    def deadline(self) -> _dt.datetime:
        return self._c.deadline

    @property
    def business_key(self) -> Optional[str]:
        return self._c._business_key

    @property
    def attempt(self) -> int:
        return self._c._attempt

    @property
    def mode(self) -> InvocationMode:
        return self._c._mode

    def __repr__(self) -> str:
        return "Invocation(%s, %s)" % (self.tool_id, self.invocation_id)


class _Cancellation:
    def __init__(self, context: "StandaloneContext") -> None:
        self._c = context

    def is_cancellation_requested(self) -> bool:
        return self._c.is_cancelled()

    def throw_if_cancellation_requested(self) -> None:
        if self._c.is_cancelled():
            raise PlatformError(PlatformCode.CANCELLED, "The invocation was cancelled")

    def on_cancel(self, callback: Callable[[], None]) -> None:
        self._c.on_cancel(callback)


class StandaloneContext:
    def __init__(self, inputs: DeclaredValues, globals: DeclaredValues, secrets: DeclaredValues,
                 claims: DeclaredValues, invocation_id: str, tool_id: str, deadline: _dt.datetime,
                 redactor: Redactor, diagnostics: Diagnostics) -> None:
        self._inputs = inputs
        self.globals = globals
        self.secrets = secrets
        self.jwt = claims
        self.invocation_id = invocation_id
        self.tool_id = tool_id
        self.deadline = deadline
        self._redactor = redactor
        self._diagnostics = diagnostics
        self._cancelled = threading.Event()
        self._on_cancel: List[Callable[[], None]] = []
        self._lock = threading.RLock()
        self._logged = 0
        self._input_requests = 0
        self._progressed = -1.0
        self._tasks: Any = None
        self._task_id: Optional[str] = None
        self._progress_token: Any = None
        self._notifications: Optional[Callable[[Dict[str, Any]], None]] = None
        self._responses: Optional[Dict[str, Any]] = None
        self._pending_input: Optional[Dict[str, Any]] = None
        self._http: Any = None
        self._tcp: Any = None
        self._business_key: Optional[str] = None
        self._mode = InvocationMode.STANDALONE
        self._attempt = 1
        self._agent_id: Optional[str] = None
        self._user_id: Optional[str] = None
        self._catalog_revision: Optional[int] = None
        self.invocation = _Invocation(self)
        self.cancellation = _Cancellation(self)
        self.log = logging.Logger("toolgate.tool." + tool_id, logging.DEBUG)
        self.log.propagate = False
        self.log.addHandler(_LogHandler(self))

    # ---- inputs ----

    def get_str(self, name: str) -> str:
        return self._inputs.get_str(name)

    def get_int(self, name: str) -> int:
        return self._inputs.get_int(name)

    def get_long(self, name: str) -> int:
        return self._inputs.get_long(name)

    def get_number(self, name: str) -> float:
        return self._inputs.get_number(name)

    def get_bool(self, name: str) -> bool:
        return self._inputs.get_bool(name)

    def get_date(self, name: str) -> Any:
        return self._inputs.get_date(name)

    def get_datetime(self, name: str) -> Any:
        return self._inputs.get_datetime(name)

    def get_duration(self, name: str) -> Any:
        return self._inputs.get_duration(name)

    def get_object(self, name: str, type: Any = object) -> Any:
        return self._inputs.get_object(name, type)

    def get_list(self, name: str, type: Any = object) -> Any:
        return self._inputs.get_list(name, type)

    def get_bytes(self, name: str) -> bytes:
        return self._inputs.get_bytes(name)

    def _toolgate_declared(self) -> Dict[str, Dict[str, str]]:
        return {"globals": self.globals.declared_types(), "secrets": self.secrets.declared_types(),
                "claims": self.jwt.declared_types()}

    # ---- network ----

    @property
    def http(self) -> Any:
        if self._http is None:
            raise ToolConfigurationError("The routes HTTP client is not available here")
        return self._http

    @property
    def tcp(self) -> Any:
        if self._tcp is None:
            raise ToolConfigurationError("The TCP connector is not available here")
        return self._tcp

    def attach_http(self, http: Any) -> None:
        self._http = http

    def attach_tcp(self, tcp: Any) -> None:
        self._tcp = tcp

    def attach_business_key(self, key: str) -> None:
        self._business_key = key

    def attach_task(self, tasks: Any, task_id: str) -> None:
        """Makes this invocation a task's: progress and input requests go to it."""
        self._tasks = tasks
        self._task_id = task_id

    def attach_input_responses(self, responses: Dict[str, Any]) -> None:
        """Makes a sync call answer ``request_input()`` by MRTR from this round's input responses (adapter A3)."""
        self._responses = responses

    def attach_progress(self, token: Any, sink: Optional[Callable[[Dict[str, Any]], None]]) -> None:
        self._progress_token = token
        self._notifications = sink

    def attach_invocation(self, mode: InvocationMode, attempt: int, agent_id: Optional[str], user_id: Optional[str],
                          catalog_revision: Optional[int]) -> None:
        """The invocation facts of governed mode, from the Tool Host."""
        self._mode = mode
        self._attempt = attempt
        self._agent_id = agent_id
        self._user_id = user_id
        self._catalog_revision = catalog_revision

    def pending_input(self) -> Optional[Dict[str, Any]]:
        """The InputRequiredResult this round ends with, or None. It is recorded before InputNeeded is raised, so a
        handler that catches the exception still ends the round asking for input."""
        return self._pending_input

    # ---- progress ----

    def progress(self, fraction: float, message: Optional[str] = None) -> None:
        """Bounded and monotonic: a fraction outside 0..1, or lower than the last one, is ignored."""
        with self._lock:
            try:
                f = float(fraction)
            except (TypeError, ValueError):
                return
            if math.isnan(f) or f < 0 or f > 1 or f < self._progressed:
                return
            self._progressed = f
            safe = None if message is None or not str(message).strip() \
                else cap(self._redactor.text(str(message)), 256)
            percent = math.floor(f * 100 + 0.5)
            if self._tasks is not None:
                self._tasks.progress(self._task_id, str(percent) + "%" + ("" if safe is None else " " + safe))
            elif self._progress_token is not None and self._notifications is not None:
                params: Dict[str, Any] = {"progressToken": self._progress_token, "progress": percent, "total": 100}
                if safe is not None:
                    params["message"] = safe
                try:
                    self._notifications({"jsonrpc": "2.0", "method": "notifications/progress", "params": params})
                except Exception:  # the client went away; the tool carries on to its own end
                    pass

    # ---- cancellation ----

    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()

    def on_cancel(self, callback: Callable[[], None]) -> None:
        with self._lock:
            self._on_cancel.append(callback)
            already = self._cancelled.is_set()
        if already:
            _quietly(callback)

    def cancel(self) -> None:
        with self._lock:
            if self._cancelled.is_set():
                return
            self._cancelled.set()
            callbacks = list(self._on_cancel)
        for c in callbacks:
            _quietly(c)

    # ---- input ----

    def request_input(self, request: InputRequest) -> Any:
        """Asks for one form elicitation.

        In a task it moves the task to input_required and waits for the answer from tasks/update. In a sync call it
        is MRTR: the handler runs again from the start on each round, and the n-th call answers from the round's
        input response ``input-n``, or ends the round with an InputRequiredResult asking for it.
        """
        with self._lock:
            self._input_requests += 1
            key = "input-" + str(self._input_requests)
        schema = to_json(request.requested_schema)
        elicitation = {"method": "elicitation/create",
                       "params": {"mode": "form", "message": self._redactor.text(request.message),
                                  "requestedSchema": schema}}
        tasks = self._tasks
        if tasks is None:
            round = self._responses
            answer = None if round is None else round.get(key)
            if answer is None:
                self._pending_input = {"resultType": "input_required", "inputRequests": {key: elicitation}}
                raise InputNeeded()
        else:
            answers = tasks.await_input(self._task_id, {key: elicitation}, self.deadline)
            if answers is None:
                if self.is_cancelled() or tasks.cancel_requested(self._task_id):
                    raise PlatformError(PlatformCode.CANCELLED, "The invocation was cancelled")
                raise PlatformError(PlatformCode.DEADLINE_EXCEEDED, "No input arrived before the deadline")
            answer = answers.get(key)
        answer = answer if isinstance(answer, dict) else {}
        action = answer.get("action")
        if action != "accept":
            raise PlatformError(PlatformCode.CANCELLED, "The input request was "
                                + ("cancelled" if action == "cancel" else "declined"))
        content = answer.get("content")
        invalid = SchemaValidator(schema).validate(content)
        if invalid is not None:
            raise ToolInputError("The input does not match the requested schema: " + invalid[1:])
        try:
            return from_json(content, request.answer_type)
        except ConversionError as e:
            raise ToolInputError("The input does not match the requested type: " + str(e))

    # ---- log ----

    def _write_log(self, level: str, message: str) -> None:
        entry = cap(self._redactor.text(str(message)), LOG_ENTRY_BYTES)
        size = len(entry.encode("utf-8", "replace"))
        with self._lock:
            self._logged += size
            over = self._logged > LOG_INVOCATION_BYTES
        if over:
            return
        self._diagnostics.println("[" + level + "] " + self.tool_id + " " + self.invocation_id + ": " + entry)


def _quietly(callback: Callable[[], None]) -> None:
    try:
        callback()
    except Exception:  # a callback failure does not stop cancellation
        pass
