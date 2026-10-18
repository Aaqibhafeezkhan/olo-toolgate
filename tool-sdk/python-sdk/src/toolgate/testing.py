# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""ToolHarness: the real protocol/runtime with fixtures and recorded exchanges, as in Java."""
from __future__ import annotations

import copy
import json
import time
from dataclasses import dataclass
from pathlib import Path

from .descriptor import Build, build as build_modules
from .protocol.core import Connection, TASKS
from .runtime import Config, StandaloneRuntime


@dataclass
class Result:
    raw: dict

    @property
    def output(self):
        return self.raw.get("structuredContent")

    @property
    def error_code(self):
        return self.raw.get("structuredContent", {}).get("error", {}).get("code")

    @property
    def is_error(self):
        return self.raw.get("isError", False)


class RecordedExchanges:
    def __init__(self, exchanges=()):
        self.exchanges = copy.deepcopy(list(exchanges))
        self.calls = []
        for exchange in self.exchanges:
            if set(exchange) != {"request", "response"}:
                raise ValueError("A recorded exchange needs request and response only")
            request, response = exchange["request"], exchange["response"]
            if not isinstance(request, dict) or not isinstance(response, dict) or not request.get("route"):
                raise ValueError("Invalid recorded exchange")
            if set(request) - {"route", "method", "variables", "query", "headers", "body", "bodyText", "bodyBase64"} \
                    or set(response) - {"status", "headers", "body", "bodyText", "bodyBase64", "failure"}:
                raise ValueError("Unknown recorded-exchange field")
            if len(set(response) & {"body", "bodyText", "bodyBase64"}) > 1:
                raise ValueError("A recorded response has exactly one body representation")

    def __call__(self, request):
        call = copy.deepcopy(request)
        for exchange in self.exchanges:
            expected = exchange["request"]
            if all(({k.lower(): v for k, v in request["headers"].items()}.get(h.lower()) == v
                    for h, v in expected.get("headers", {}).items())) and all(
                    request.get(k) == v for k, v in expected.items() if k != "headers"):
                call["answered"] = True
                self.calls.append(call)
                return copy.deepcopy(exchange["response"])
        call["answered"] = False
        self.calls.append(call)
        return {"refused": "No recorded exchange answered the route call"}


class ToolHarness:
    def __init__(self, modules, *, fixtures=None, exchanges=(), files=None, generation=1):
        self.build = modules if isinstance(modules, Build) else build_modules(modules, generation)
        self.fixtures = copy.deepcopy(fixtures or {})
        self.exchanges = RecordedExchanges(exchanges)
        self.runtime = StandaloneRuntime(self.build, Config.test(self.fixtures), exchanges=self.exchanges, files=files)
        self.connection = Connection("test")
        self.notifications = []

    def request(self, method, **params):
        params["_meta"] = {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
                           "io.modelcontextprotocol/clientCapabilities": {
                               "elicitation": {}, "extensions": {TASKS: {}}}}
        response = self.runtime.core.handle({"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
                                            self.connection, notify=self.notifications.append)
        if response is None or "error" in response:
            raise ValueError(response)
        return response["result"]

    def call(self, tool, arguments=None, **params):
        name = next((d["mcpName"] for n, d in self.build.descriptors.items()
                     if tool in (n, d["mcpName"], d["toolId"])), tool)
        result = self.request("tools/call", name=name, arguments=arguments or {}, **params)
        if result.get("resultType") == "task":
            task_id = result["taskId"]
            while True:
                task = self.request("tasks/get", taskId=task_id)
                if task["status"] == "completed":
                    result = task["result"]
                    break
                if task["status"] in {"failed", "cancelled", "input_required"}:
                    raise ValueError("Task did not complete: " + str(task))
                time.sleep(.01)
        result.setdefault("_meta", {})["io.ololabs.toolgate/identity"] = "fixture"
        return Result(result)

    def route_calls(self):
        return copy.deepcopy(self.exchanges.calls)

    def run_examples(self, root=None):
        results = []
        for entry in self.build.index["tools"]:
            for example in entry.get("examples", []):
                mocks = []
                if example.get("exchanges"):
                    if root is None:
                        raise ValueError("Examples with exchanges need a project root")
                    path = (Path(root) / example["exchanges"]).resolve()
                    if not path.is_relative_to(Path(root).resolve()):
                        raise ValueError("Example exchanges must be inside the project")
                    mocks = json.loads(path.read_text(encoding="utf-8"))
                with ToolHarness(self.build, fixtures=self.fixtures, exchanges=mocks) as harness:
                    result = harness.call(entry["name"], json.loads(example["arguments"]))
                    expected_error = example.get("expectedError")
                    if any(not c["answered"] for c in harness.route_calls()):
                        raise AssertionError(entry["name"] + " has an unanswered route call")
                    if expected_error:
                        if result.error_code != expected_error:
                            raise AssertionError(entry["name"] + " expected error " + expected_error)
                    elif result.is_error:
                        raise AssertionError(entry["name"] + " failed: " + str(result.raw))
                    elif example.get("expectedOutput") and result.output != json.loads(example["expectedOutput"]):
                        raise AssertionError(entry["name"] + " output differs from its example")
                    results.append(result)
        return results

    def close(self):
        self.runtime.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
