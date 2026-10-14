# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Adapter A2: the Tasks extension ``io.modelcontextprotocol/tasks`` over a task store (protocol-layer.md section 3).

The runtime creates tasks and reports their progress, input requests and outcome; the protocol core routes
``tasks/get``, ``tasks/update`` and ``tasks/cancel`` here.
"""

from __future__ import annotations

import abc
import copy
import datetime as _dt
import enum
import secrets
import threading
from typing import Any, Callable, Dict, List, Optional

from .._json import instant_text, now
from .errors import INVALID_PARAMS, ProtocolError

__all__ = ["TaskStatus", "TaskRecord", "TaskStore", "MemoryTaskStore", "Tasks"]


class TaskStatus(enum.Enum):
    WORKING = "working"
    INPUT_REQUIRED = "input_required"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"

    @property
    def wire(self) -> str:
        return self.value

    @property
    def terminal(self) -> bool:
        return self in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)


class TaskRecord:
    """One task. ``owner`` is the identity (or connection) that created it; every other owner gets not-found."""

    def __init__(self) -> None:
        self.task_id: str = ""
        self.owner: str = ""
        self.status: TaskStatus = TaskStatus.WORKING
        self.status_message: Optional[str] = None
        self.created_at: _dt.datetime = now()
        self.last_updated_at: _dt.datetime = self.created_at
        self.ttl_ms: int = 0
        self.poll_interval_ms: int = 0
        self.expires_at: _dt.datetime = self.created_at
        self.last_polled_at: _dt.datetime = self.created_at
        self.cancel_if_abandoned = False
        self.cancel_requested = False
        #: Pending input requests by key, each ``{method, params}``.
        self.input_requests: Dict[str, Any] = {}
        #: Answers received for pending keys and not yet taken by the tool.
        self.input_responses: Dict[str, Any] = {}
        #: The tool result, when completed.
        self.result: Optional[Dict[str, Any]] = None
        #: The JSON-RPC error ``{code, message, data?}``, when failed.
        self.error: Optional[Dict[str, Any]] = None

    def copy(self) -> "TaskRecord":
        return copy.deepcopy(self)


class TaskStore(abc.ABC):
    @abc.abstractmethod
    def create(self, task: TaskRecord) -> None:
        """Stores a new task; it exists before its CreateTaskResult is sent."""

    @abc.abstractmethod
    def get(self, task_id: str, owner: str) -> Optional[TaskRecord]:
        """The task, if it exists and belongs to ``owner``."""

    @abc.abstractmethod
    def transition(self, task_id: str, change: Callable[[TaskRecord], None]) -> Optional[TaskRecord]:
        """Applies ``change`` atomically; returns the task after it, or None if the task is gone."""

    @abc.abstractmethod
    def submit_input(self, task_id: str, owner: str, responses: Dict[str, Any]) -> Optional[TaskRecord]:
        """Records answers for pending input keys and removes those keys from ``input_requests``."""

    @abc.abstractmethod
    def request_cancel(self, task_id: str, owner: str) -> Optional[TaskRecord]:
        """Marks a non-terminal task cancel-requested."""

    @abc.abstractmethod
    def purge_expired(self, at: _dt.datetime) -> List[TaskRecord]:
        """Removes the tasks whose retention ended by ``at`` and returns them."""

    @abc.abstractmethod
    def active(self) -> List[TaskRecord]:
        """Every task that has not ended, for the abandonment sweep."""

    def close(self) -> None:
        pass


def apply_input(t: TaskRecord, responses: Dict[str, Any]) -> None:
    for key, value in responses.items():
        if key in t.input_requests:
            del t.input_requests[key]
            t.input_responses[key] = copy.deepcopy(value)


class MemoryTaskStore(TaskStore):
    """An in-process task store. Tasks do not survive the process, which is the standalone contract for memory."""

    def __init__(self) -> None:
        self._tasks: Dict[str, TaskRecord] = {}
        self._lock = threading.RLock()

    def create(self, task: TaskRecord) -> None:
        with self._lock:
            if task.task_id in self._tasks:
                raise RuntimeError("duplicate task id")
            self._tasks[task.task_id] = task.copy()

    def _owned(self, task_id: str, owner: str) -> Optional[TaskRecord]:
        t = self._tasks.get(task_id)
        return t if t is not None and t.owner == owner else None

    def get(self, task_id: str, owner: str) -> Optional[TaskRecord]:
        with self._lock:
            t = self._owned(task_id, owner)
            return None if t is None else t.copy()

    def transition(self, task_id: str, change: Callable[[TaskRecord], None]) -> Optional[TaskRecord]:
        with self._lock:
            t = self._tasks.get(task_id)
            if t is None:
                return None
            change(t)
            return t.copy()

    def submit_input(self, task_id: str, owner: str, responses: Dict[str, Any]) -> Optional[TaskRecord]:
        with self._lock:
            t = self._owned(task_id, owner)
            if t is None:
                return None
            apply_input(t, responses)
            return t.copy()

    def request_cancel(self, task_id: str, owner: str) -> Optional[TaskRecord]:
        with self._lock:
            t = self._owned(task_id, owner)
            if t is None:
                return None
            if not t.status.terminal:
                t.cancel_requested = True
            return t.copy()

    def purge_expired(self, at: _dt.datetime) -> List[TaskRecord]:
        with self._lock:
            purged = [t for t in self._tasks.values() if not t.expires_at > at]
            for t in purged:
                del self._tasks[t.task_id]
            return [t.copy() for t in purged]

    def active(self) -> List[TaskRecord]:
        with self._lock:
            return [t.copy() for t in self._tasks.values() if not t.status.terminal]


def _ms(n: int) -> _dt.timedelta:
    return _dt.timedelta(milliseconds=n)


class Tasks:
    DEFAULT_TTL_MS = 3_600_000
    DEFAULT_POLL_INTERVAL_MS = 1_000
    #: Polls extend retention, never past this long after creation.
    MAX_RETENTION_MS = 86_400_000
    #: A ``cancelIfAbandoned`` task with no poll for this many poll intervals is cancelled (execution.md section 6).
    ABANDONED_AFTER_POLLS = 3

    def __init__(self, store: TaskStore, ttl_ms: int = DEFAULT_TTL_MS,
                 poll_interval_ms: int = DEFAULT_POLL_INTERVAL_MS) -> None:
        if ttl_ms <= 0 or poll_interval_ms <= 0:
            raise ValueError("ttlMs and pollIntervalMs must be positive")
        self.store = store
        self.ttl_ms = ttl_ms
        self.poll_interval_ms = poll_interval_ms
        self._cancel_handlers: Dict[str, Callable[[], None]] = {}
        self._handlers_lock = threading.Lock()
        self._listeners: List[Callable[[TaskRecord], None]] = []
        self._changed = threading.Condition()
        self._closed = threading.Event()
        self._sweep_s = max(10, poll_interval_ms // 2) / 1000.0
        self._sweeper = threading.Thread(target=self._sweep_loop, name="toolgate-tasks", daemon=True)
        self._sweeper.start()

    def on_change(self, listener: Callable[[TaskRecord], None]) -> None:
        """Called with the task after each change of status or status message, for ``notifications/tasks``."""
        self._listeners.append(listener)

    # ---- runtime side ----

    def create(self, owner: str, cancel_if_abandoned: bool, status_message: Optional[str],
               on_cancel: Callable[[], None]) -> TaskRecord:
        at = now()
        t = TaskRecord()
        t.task_id = secrets.token_hex(16)
        t.owner = owner
        t.status_message = status_message
        t.created_at = at
        t.last_updated_at = at
        t.ttl_ms = self.ttl_ms
        t.poll_interval_ms = self.poll_interval_ms
        t.expires_at = at + _ms(self.ttl_ms)
        t.last_polled_at = at
        t.cancel_if_abandoned = cancel_if_abandoned
        with self._handlers_lock:
            self._cancel_handlers[t.task_id] = on_cancel
        self.store.create(t)
        return t.copy()

    @staticmethod
    def create_result(t: TaskRecord) -> Dict[str, Any]:
        """The ``CreateTaskResult`` for a new task: flat, ``resultType: "task"``."""
        r = Tasks.view(t)
        r["resultType"] = "task"
        return r

    def progress(self, task_id: str, message: str) -> None:
        def change(t: TaskRecord) -> None:
            if t.status is TaskStatus.WORKING:
                t.status_message = message
        self._change(task_id, change)

    def complete(self, task_id: str, result: Dict[str, Any]) -> None:
        """Ends the task with the tool result; a task whose cancellation was requested ends cancelled instead."""
        def change(t: TaskRecord) -> None:
            if t.cancel_requested:
                t.status = TaskStatus.CANCELLED
            else:
                t.status = TaskStatus.COMPLETED
                t.result = result
        self._end(task_id, change)

    def fail(self, task_id: str, code: int, message: str, data: Any = None) -> None:
        def change(t: TaskRecord) -> None:
            t.status = TaskStatus.FAILED
            t.error = {"code": code, "message": message}
            if data is not None:
                t.error["data"] = data
        self._end(task_id, change)

    def cancelled(self, task_id: str) -> None:
        def change(t: TaskRecord) -> None:
            t.status = TaskStatus.CANCELLED
        self._end(task_id, change)

    def cancel_requested(self, task_id: str) -> bool:
        """Whether cancellation of the task was requested, or the task is gone."""
        t = self.store.transition(task_id, lambda x: None)
        return True if t is None else t.cancel_requested

    def await_input(self, task_id: str, requests: Dict[str, Any], deadline: _dt.datetime) -> Optional[Dict[str, Any]]:
        """Moves the task to ``input_required`` and blocks until every key is answered through ``tasks/update``.

        Returns the answers by key, or None when the deadline passes, cancellation is requested or the task is gone;
        the task is working again either way.
        """
        def ask(t: TaskRecord) -> None:
            t.input_requests.update(copy.deepcopy(requests))
            t.status = TaskStatus.INPUT_REQUIRED
        self._change(task_id, ask)
        try:
            with self._changed:
                while True:
                    current = self.store.transition(task_id, lambda x: None)
                    if current is None or current.cancel_requested:
                        return None
                    if all(k in current.input_responses for k in requests):
                        answers: Dict[str, Any] = {}

                        def take(t: TaskRecord) -> None:
                            for k in requests:
                                answers[k] = t.input_responses.pop(k, None)
                        self._change(task_id, take)
                        return answers
                    left = (deadline - now()).total_seconds()
                    if left <= 0:
                        return None
                    self._changed.wait(min(left, 0.2))
        finally:
            def settle(t: TaskRecord) -> None:
                for k in requests:
                    t.input_requests.pop(k, None)
                    t.input_responses.pop(k, None)
                if t.status is TaskStatus.INPUT_REQUIRED and not t.input_requests:
                    t.status = TaskStatus.WORKING
            self._change(task_id, settle)

    def find(self, owner: str, task_id: str) -> Optional[TaskRecord]:
        return self.store.get(task_id, owner)

    # ---- protocol side ----

    def get(self, params: Dict[str, Any], meta: Any) -> Dict[str, Any]:
        task_id = _task_id(params)
        t = self.store.get(task_id, meta.owner)
        if t is None:
            raise _not_found()
        at = now()

        def poll(x: TaskRecord) -> None:
            x.last_polled_at = at
            extended = at + _ms(self.poll_interval_ms * self.ABANDONED_AFTER_POLLS)
            cap = x.created_at + _ms(self.MAX_RETENTION_MS)
            if extended > x.expires_at:
                x.expires_at = cap if extended > cap else extended
        self.store.transition(task_id, poll)
        r = self.view(t)
        if t.status is TaskStatus.INPUT_REQUIRED:
            r["inputRequests"] = t.input_requests
        elif t.status is TaskStatus.COMPLETED:
            r["result"] = t.result
        elif t.status is TaskStatus.FAILED:
            r["error"] = t.error
        return r

    def update(self, params: Dict[str, Any], meta: Any) -> Dict[str, Any]:
        task_id = _task_id(params)
        responses = params.get("inputResponses")
        if not isinstance(responses, dict):
            raise ProtocolError(INVALID_PARAMS, "inputResponses must be an object")
        if self.store.submit_input(task_id, meta.owner, responses) is None:
            raise _not_found()
        self._signal()
        return {}

    def cancel(self, params: Dict[str, Any], meta: Any) -> Dict[str, Any]:
        task_id = _task_id(params)
        t = self.store.request_cancel(task_id, meta.owner)
        if t is None:
            raise _not_found()
        if t.cancel_requested:
            self._run_cancel(task_id)
        return {}

    def sweep(self) -> None:
        """Purges expired tasks and cancels abandoned ones. Runs on a timer; public for tests."""
        at = now()
        for t in self.store.purge_expired(at):
            if not t.status.terminal:
                self._run_cancel(t.task_id)
            with self._handlers_lock:
                self._cancel_handlers.pop(t.task_id, None)
        for t in self.store.active():
            if t.cancel_if_abandoned and not t.cancel_requested \
                    and at > t.last_polled_at + _ms(t.poll_interval_ms * self.ABANDONED_AFTER_POLLS):
                def mark(x: TaskRecord) -> None:
                    x.cancel_requested = True
                self._change(t.task_id, mark)
                self._run_cancel(t.task_id)

    def close(self) -> None:
        self._closed.set()

    def _sweep_loop(self) -> None:
        while not self._closed.wait(self._sweep_s):
            try:
                self.sweep()
            except Exception:  # a store failure never stops the sweeper
                pass

    @staticmethod
    def view(t: TaskRecord) -> Dict[str, Any]:
        r: Dict[str, Any] = {"taskId": t.task_id, "status": t.status.wire}
        if t.status_message is not None:
            r["statusMessage"] = t.status_message
        r["createdAt"] = instant_text(t.created_at)
        r["lastUpdatedAt"] = instant_text(t.last_updated_at)
        r["ttlMs"] = t.ttl_ms
        r["pollIntervalMs"] = t.poll_interval_ms
        return r

    def _change(self, task_id: str, change: Callable[[TaskRecord], None]) -> None:
        before: List[Any] = []

        def wrapped(t: TaskRecord) -> None:
            before.append((t.status, t.status_message))
            change(t)
            t.last_updated_at = now()
        after = self.store.transition(task_id, wrapped)
        self._signal()
        if after is None or before[0] == (after.status, after.status_message):
            return
        for listener in list(self._listeners):
            try:
                listener(after)
            except Exception:  # a listener failure never affects the task
                pass

    def _end(self, task_id: str, change: Callable[[TaskRecord], None]) -> None:
        def ending(t: TaskRecord) -> None:
            if t.status.terminal:
                return
            change(t)
            t.input_requests.clear()
            t.input_responses.clear()
        self._change(task_id, ending)
        with self._handlers_lock:
            self._cancel_handlers.pop(task_id, None)

    def _run_cancel(self, task_id: str) -> None:
        with self._handlers_lock:
            handler = self._cancel_handlers.pop(task_id, None)
        if handler is not None:
            handler()
        self._signal()

    def _signal(self) -> None:
        with self._changed:
            self._changed.notify_all()


def _task_id(params: Dict[str, Any]) -> str:
    v = params.get("taskId")
    if not isinstance(v, str) or not v or len(v) > 128:
        raise ProtocolError(INVALID_PARAMS, "taskId is required")
    return v


def _not_found() -> ProtocolError:
    return ProtocolError(INVALID_PARAMS, "Task not found")

