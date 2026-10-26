# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""One v2 ToolInput, broker requests over stdio, and exactly one final RESULT (ADR 027)."""
from __future__ import annotations

import datetime as dt
import re
import threading
import time

from .._json import dumpb, loads
from ..protocol.core import TASKS, Connection
from ..protocol.tasks import TaskStatus
from .config import Config, StartupRefused
from .engine import StandaloneRuntime, failure

#: Reserved claim carrying the principal the Tool Host verified in the Control permit (OLO M4.2).
PRINCIPAL_CLAIM = "toolgate"
from .files import FileInfo, refused


def frame(result):
    out = {"kind": "RESULT", "resultType": "complete", "content": result.get("content", []),
           "isError": result.get("isError", False)}
    if isinstance(result.get("structuredContent"), dict):
        out["structuredContent"] = result["structuredContent"]
    if out["isError"]:
        out["error"] = result.get("structuredContent", {}).get("error", {
            "code": "INTERNAL_ERROR", "message": "The tool failed", "retryable": False})
    return out


class StdioBroker:
    def __init__(self, input, emit):
        self.input, self.emit = input, emit
        self.lock = threading.Lock()
        self.calls = 0

    def call(self, operation, request):
        with self.lock:
            self.calls += 1
            if self.calls > 1024:
                raise ValueError("Broker call limit exceeded")
            ident = "broker-" + str(self.calls)
            self.emit({"kind": "BROKER_REQUEST", "requestId": ident, "operation": operation, "request": request})
            line = self.input.readline(8*1024*1024+1)
            if not line or len(line) > 8*1024*1024:
                raise ValueError("The broker channel failed")
            response = loads(line)
            if response.get("kind") != "BROKER_RESPONSE" or response.get("requestId") != ident:
                raise ValueError("The broker answered out of order")
            if "error" in response:
                raise ValueError(response["error"].get("message", "The executor refused the operation"))
            return response.get("result", {})

    def exchange(self, request):
        try:
            return self.call("ROUTE_CALL", request)
        except (ValueError, OSError) as exc:
            return {"refused": str(exc)}

    def redeem(self, handle):
        try:
            return self.call("SECRET_REDEEM", {"handle": handle}).get("value")
        except (ValueError, OSError):
            return None

    def _files(self, operation, **request):
        try:
            return self.call(operation, request)
        except (ValueError, OSError) as exc:
            raise refused(str(exc))

    def list(self):
        return self._files("FILES_LIST").get("paths", [])

    def read_text(self, path):
        return self._files("FILES_READ", path=path)["text"]

    def info(self, path):
        result = self._files("FILES_INFO", path=path)
        return FileInfo(result["path"], result["bytes"])

    def write_text(self, path, text):
        self._files("FILES_WRITE", path=path, text=text)

    def append_text(self, path, text):
        self._files("FILES_APPEND", path=path, text=text)

    def mkdir(self, path):
        self._files("FILES_MKDIR", path=path)

    def rename(self, path, destination):
        self._files("FILES_RENAME", path=path, destination=destination)


def run(input, output, env, build=None):
    lock = threading.Lock()
    terminal = False
    def emit(value):
        nonlocal terminal
        with lock:
            if terminal:
                return
            terminal = value.get("kind") == "RESULT"
            output.write(dumpb(value)+b"\n")
            output.flush()
    def fail(code, message, exit_code):
        emit(frame(failure(code, message)))
        return exit_code
    try:
        line = input.readline(2*1024*1024+1)
        if len(line) > 2*1024*1024:
            raise ValueError()
        value = loads(line)
        if not isinstance(value, dict):
            raise ValueError()
    except (ValueError, OSError):
        return fail("INVALID_INPUT", "The tool input is not one JSON line", 65)
    if value.get("protocolVersion") != 2:
        return fail("UNSUPPORTED_PROTOCOL", "This runtime speaks tool protocol v2", 65)
    deadline, ident = value.get("deadlineUnixMs"), value.get("invocationId")
    if not isinstance(ident, str) or re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}", ident) is None \
            or not isinstance(deadline, int) or isinstance(deadline, bool) or not 0 < deadline <= 9007199254740991:
        return fail("INVALID_INPUT", "The tool input must bind an invocation and deadline", 65)
    if deadline <= time.time()*1000:
        return fail("DEADLINE_EXCEEDED", "The invocation deadline passed before the tool started", 0)
    broker = StdioBroker(input, emit)
    handles = {h["name"]: h["handle"] for h in value.get("secretHandles", [])}
    claims = dict(value.get("claims", {})) if isinstance(value.get("claims"), dict) else {}
    # The Tool Host adds the verified principal under a reserved claim; it is lifted out so no tool reads it as a claim.
    principal = claims.pop(PRINCIPAL_CLAIM, None)
    config = Config.governed({"globals": value.get("globals", {}), "claims": claims},
                             lambda name: broker.redeem(handles[name]) if name in handles else None)
    config.env = {"TOOLGATE_EXECUTION_TARGET": env.get("TOOLGATE_EXECUTION_TARGET", "SERVER")}
    config.invocation_id = ident
    config.principal = principal if isinstance(principal, dict) else None
    # Correlation only (ADR 031): never read for any decision. Older executors send neither member.
    config.execution = value.get("executionContext") if isinstance(value.get("executionContext"), dict) else None
    config.traceparent = value.get("traceparent") if isinstance(value.get("traceparent"), str) else None
    config.deadline = dt.datetime.fromtimestamp(deadline/1000, dt.timezone.utc)
    config.frames = emit
    try:
        runtime = StandaloneRuntime(build, config, exchanges=broker.exchange,
                                    files=broker if config.env["TOOLGATE_EXECUTION_TARGET"] == "CLIENT" else None)
    except StartupRefused as exc:
        return fail(exc.code, str(exc), 78)
    with runtime:
        name = next((d["mcpName"] for d in runtime.build.descriptors.values()
                     if d["toolId"] == value.get("toolId") and d["mcpName"] in runtime.by_name), None)
        if name is None:
            return fail("TOOL_NOT_FOUND", "The artifact has no eligible tool " + str(value.get("toolId")), 65)
        meta = {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {"extensions": {TASKS: {}}}}
        connection = Connection("governed")
        response = runtime.core.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                        "params": {"name": name, "arguments": value.get("arguments", {}), "_meta": meta}}, connection)
        if "error" in response:
            return fail("INVALID_ARGUMENT", response["error"]["message"], 0)
        result = response["result"]
        if result.get("resultType") == "task":
            while True:
                task = runtime.tasks().find(connection.owner, result["taskId"])
                if task.status is TaskStatus.COMPLETED:
                    result = task.result
                    break
                if task.status is TaskStatus.FAILED:
                    err = task.error.get("data", {})
                    result = failure(err.get("code", "INTERNAL_ERROR"), task.error["message"])
                    break
                if task.status is TaskStatus.CANCELLED:
                    result = failure("CANCELLED", "The task was cancelled")
                    break
                if time.time()*1000 >= deadline:
                    result = failure("DEADLINE_EXCEEDED", "The invocation deadline passed")
                    break
                time.sleep(.01)
        emit(frame(result))
    return 0
