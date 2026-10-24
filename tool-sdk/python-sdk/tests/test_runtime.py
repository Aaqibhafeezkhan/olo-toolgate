# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Behavioral counterparts of Java Runtime/Tasks/MRTR/Routes/Governed/Identity tests."""
import copy
import datetime as dt
import http.client
import io
import json
import tempfile
import threading
import time
import unittest
from enum import Enum, IntEnum
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from support import REPO, compile_package
from toolgate.protocol.core import Connection, TASKS
from toolgate.protocol.identity import Rejected
from toolgate.runtime import Config, Mode, StandaloneRuntime, StartupRefused
from toolgate.runtime.converter import to_json
from toolgate.runtime.files import MemoryDeviceFiles
from toolgate.runtime.governed import run
from toolgate.runtime.identity import JwtVerifier
from toolgate.runtime.state import Reservations, SqliteTaskStore
from toolgate.protocol.tasks import TaskRecord, TaskStatus
from toolgate.testing import ToolHarness

SOURCE = '''
from dataclasses import dataclass
from typing import Annotated
from toolgate import *
from toolgate import device_file_access
import asyncio
import time
tool_package(id="acme.runtime", name="Runtime", publisher="Acme")
destination(id="api", host_setting="ApiHost")
http_route(id="items.get", destination="api", method="GET", path="/items/{id:[0-9]+}", query=["expand"], headers=["X-Test"])

@dataclass
class Out:
    value: str

@server_tool(name="hello", title="Hello", description="Hello")
@Global(name="Prefix", type=ValueType.STRING, default_value="Hello")
@example(arguments={"name":"World"}, expected_output={"value":"Hello World"})
def hello(name: Annotated[str, Param(description="Name")] = "World", *, context: ToolContext) -> Out:
    context.log.info("hello")
    context.progress(.5, "half")
    return Out(context.globals.get_str("Prefix") + " " + name)

@server_tool(name="coroutine", title="Coroutine", description="Coroutine")
async def coroutine(*, context: ToolContext) -> Out:
    await asyncio.sleep(.001)
    return Out("async")

@server_tool(name="background", title="Task", description="Task", async_=True)
def background(*, context: ToolContext) -> Out:
    context.progress(.5, "working")
    return Out("task")

@server_tool(name="ask", title="Ask", description="Ask")
def ask(*, context: ToolContext) -> Out:
    return Out(context.request_input(InputRequest("Name?", {"type":"string"}, str)))

@server_tool(name="broken", title="Broken", description="Broken")
def broken() -> Out:
    raise RuntimeError("internal detail")

@server_tool(name="wrong-output", title="Wrong", description="Wrong")
def wrong_output() -> Out:
    return {"wrong":1}

@server_tool(name="slow", title="Slow", description="Slow", max_duration_ms=20, max_concurrency=1)
def slow() -> Out:
    time.sleep(.2)
    return Out("late")

@server_tool(name="retry", title="Retry", description="Retry", effect=EffectSafety.READ_ONLY, errors=[ErrorDecl("BUSY", "Busy", retryable=True)])
def retry() -> Out:
    raise ToolRetryableError("BUSY", "Try later")

@server_tool(name="lookup", title="Lookup", description="Lookup", routes=["items.get"])
def lookup(id: Annotated[str, Param(description="Id")], *, context: ToolContext) -> Out:
    return context.http.route("items.get").path_var("id", id).query("expand", None).send(Out)

@client_tool(name="files", title="Files", description="Files")
@device_file_access(write=True)
def files(*, context: ToolContext) -> Out:
    context.files.append_text("notes.txt", "hello")
    return Out(context.files.read_text("notes.txt"))
'''


PRINCIPAL_SOURCE = '''
from dataclasses import dataclass
from typing import List
from toolgate import *
tool_package(id="acme.principal", name="Principal", publisher="Acme")

@dataclass
class Who:
    user: str
    tenant: str
    agent: str
    operation: str
    chain: List[str]
    resources: List[str]
    claims: List[str]

@server_tool(name="whoami", title="Who", description="Reports the verified principal of a user-scoped call")
def whoami(*, context: ToolContext) -> Who:
    inv = context.invocation
    user = inv.require_user()
    return Who(user, inv.tenant_id, inv.agent_id or "", inv.operation or "", inv.delegation_chain,
               [r.kind + ":" + r.locator for r in inv.resources], sorted(context.jwt.identity or {}))
'''


def compiled(source=SOURCE):
    result = compile_package({"__init__": source})
    if not result.success:
        raise AssertionError(result.errors)
    return result.build


def message(method, **params):
    params["_meta"] = {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
                       "io.modelcontextprotocol/clientCapabilities": {"elicitation": {}, "extensions": {TASKS: {}}}}
    return {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}


class RuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = compiled()

    def test_defaults_globals_coroutines_and_tasks(self):
        with ToolHarness(self.build) as h:
            self.assertEqual({"value": "Hello World"}, h.call("hello").output)
            self.assertEqual({"value": "async"}, h.call("coroutine").output)
            self.assertEqual({"value": "task"}, h.call("background").output)

    def test_invalid_input_never_runs(self):
        with ToolHarness(self.build) as h:
            self.assertEqual("INVALID_ARGUMENT", h.call("hello", {"unexpected": 1}).error_code)

    def test_errors_output_validation_and_deadline(self):
        with ToolHarness(self.build) as h:
            self.assertEqual("INTERNAL_TOOL_ERROR", h.call("broken").error_code)
            self.assertNotIn("internal detail", json.dumps(h.call("broken").raw))
            self.assertEqual("INTERNAL_TOOL_ERROR", h.call("wrong-output").error_code)
            self.assertEqual("DEADLINE_EXCEEDED", h.call("slow").error_code)
            self.assertTrue(h.call("retry").raw["structuredContent"]["error"]["retryable"])

    def test_examples(self):
        with ToolHarness(self.build) as h:
            self.assertEqual(1, len(h.run_examples()))

    def test_recorded_routes_never_resolve_credentials(self):
        exchanges = [{"request": {"route": "items.get", "variables": {"id": "7"}},
                      "response": {"body": {"value": "recorded"}}}]
        with ToolHarness(self.build, exchanges=exchanges) as h:
            self.assertEqual({"value": "recorded"}, h.call("lookup", {"id": "7"}).output)
            self.assertTrue(h.route_calls()[0]["answered"])
            self.assertEqual("ROUTE_REFUSED", h.call("lookup", {"id": "../7"}).error_code)

    def test_missing_route_mock_is_recorded(self):
        with ToolHarness(self.build) as h:
            self.assertEqual("ROUTE_REFUSED", h.call("lookup", {"id": "7"}).error_code)
            self.assertFalse(h.route_calls()[0]["answered"])

    def test_real_development_route_and_redirect_are_not_retried(self):
        calls = []
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                calls.append(self.path)
                if self.path.endswith("/8"):
                    self.send_response(302)
                    self.send_header("Location", "/items/7")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"value":"live"}')
        server = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        config = Config(Mode.DEVELOPMENT, {}, {"destinations": {"ApiHost": "http://127.0.0.1:%d" % server.server_port}})
        try:
            with StandaloneRuntime(self.build, config) as runtime:
                response = runtime.core.handle(message("tools/call", name="runtime.lookup", arguments={"id":"7"}), Connection("stdio"))
                self.assertEqual({"value":"live"}, response["result"]["structuredContent"])
                response = runtime.core.handle(message("tools/call", name="runtime.lookup", arguments={"id":"8"}), Connection("stdio"))
                self.assertEqual("UPSTREAM_4XX", response["result"]["structuredContent"]["error"]["code"])
                self.assertEqual(["/items/7", "/items/8"], calls)
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=3)

    def test_device_files_and_target_eligibility(self):
        with ToolHarness(self.build) as h:
            self.assertEqual({"value": "hello"}, h.call("files").output)
        with StandaloneRuntime(self.build, Config.test({})) as runtime:
            self.assertIn("runtime.files", runtime.by_name)
        with StandaloneRuntime(self.build, Config.load({}, Path.cwd())) as runtime:
            self.assertNotIn("runtime.files", runtime.by_name)

    def test_bound_route_method_is_rejected_at_build(self):
        r = compile_package({"__init__": SOURCE.replace('return context.http.route("items.get").path_var("id", id).query("expand", None).send(Out)',
            'call = context.http.get\n    return call("api", "/items/" + id).send(Out)')})
        self.assertFalse(r.success)
        self.assertIn("bound convenience method", r.errors)

    def test_enum_names_are_serialized(self):
        class Number(IntEnum):
            READY = 1
        class Text(str, Enum):
            READY = "ready"
        self.assertEqual("READY", to_json(Number.READY))
        self.assertEqual("READY", to_json(Text.READY))

    def test_positional_only_handlers_bind_declared_inputs(self):
        build = compiled(SOURCE.replace(
            '= "World", *, context: ToolContext)', '= "World", /, *, context: ToolContext)'))
        with ToolHarness(build) as h:
            self.assertEqual({"value": "Hello Ada"}, h.call("hello", {"name": "Ada"}).output)

    def test_task_capability_and_owner(self):
        with ToolHarness(self.build) as h:
            request = message("tools/list")
            request["params"]["_meta"]["io.modelcontextprotocol/clientCapabilities"] = {}
            listed = h.runtime.core.handle(request, h.connection)["result"]["tools"]
            self.assertNotIn("runtime.background", [t["name"] for t in listed])
            result = h.request("tools/call", name="runtime.background", arguments={})
            response = h.runtime.core.handle(message("tasks/get", taskId=result["taskId"]), Connection("test"))
            self.assertEqual(-32602, response["error"]["code"])

    def test_business_keys_replay_without_executing_and_refuse_conflicts(self):
        build = compiled(SOURCE + '''
charge_calls = 0
@server_tool(name="charge", title="Charge", description="Charge", effect=EffectSafety.NON_IDEMPOTENT,
             destructive=True, business_key_argument="key", reconciliation=Reconciliation.MANUAL)
def charge(key: Annotated[str, Param(description="Key")], amount: Annotated[int, Param(description="Amount")] = 1) -> Out:
    global charge_calls
    charge_calls += 1
    return Out(str(charge_calls))
''')
        with tempfile.TemporaryDirectory() as temp:
            config = Config(Mode.DEVELOPMENT, {"TOOLGATE_STATE_DB": str(Path(temp)/"state.db")}, {})
            for _ in range(2):  # Replays also survive a runtime restart.
                with StandaloneRuntime(build, config) as runtime:
                    def call(**args):
                        return runtime.core.handle(message("tools/call", name="runtime.charge", arguments=args), Connection("stdio"))["result"]
                    self.assertEqual({"value":"1"}, call(key="order-1")["structuredContent"])
                    self.assertEqual({"value":"1"}, call(key="order-1")["structuredContent"])
                    self.assertEqual("IDEMPOTENCY_CONFLICT", call(key="order-1", amount=2)["structuredContent"]["error"]["code"])
                    self.assertEqual("INVALID_ARGUMENT", call(key="x"*256)["structuredContent"]["error"]["code"])
            self.assertEqual(1, build.handlers["charge"].module.charge_calls)

    def test_mrtr_answer_and_replay_refusal(self):
        with ToolHarness(self.build) as h:
            first = h.request("tools/call", name="runtime.ask", arguments={})
            self.assertEqual("input_required", first["resultType"])
            params = {"name": "runtime.ask", "arguments": {}, "requestState": first["requestState"],
                      "inputResponses": {"input-1": {"action": "accept", "content": "Alice"}}}
            self.assertEqual({"value": "Alice"}, h.request("tools/call", **params)["structuredContent"])
            with self.assertRaises(ValueError):
                h.request("tools/call", **params)

    def test_test_mode_cannot_be_enabled_from_environment(self):
        with self.assertRaises(StartupRefused):
            Config.load({"TOOLGATE_MODE": "test"}, Path.cwd())

    def test_http_transport_headers_and_loopback_guard(self):
        from toolgate.protocol.http import StreamableHttpServer
        with ToolHarness(self.build) as h, StreamableHttpServer(h.runtime.core, ("127.0.0.1", 0)) as server:
            def post(headers):
                connection = http.client.HTTPConnection(*server.address, timeout=3)
                connection.request("POST", "/mcp", json.dumps(message("tools/list")), headers)
                response = connection.getresponse()
                status, body = response.status, response.read()
                connection.close()
                return status, json.loads(body) if body else None
            self.assertEqual(400, post({})[0])
            headers = {"MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/list"}
            self.assertEqual(200, post(headers)[0])
            self.assertEqual(403, post({**headers, "Origin": "https://evil.example"})[0])

    def test_governed_id_deadline_and_response_frames(self):
        inp = {"protocolVersion": 2, "invocationId": "invoke-1", "deadlineUnixMs": int(time.time()*1000)+5000,
               "toolId": "acme.runtime/hello", "arguments": {"name": "World"}}
        output = io.BytesIO()
        self.assertEqual(0, run(io.BytesIO(json.dumps(inp).encode()+b"\n"), output, {}, self.build))
        frames = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(["LOG", "PROGRESS", "RESULT"], [f["kind"] for f in frames])
        self.assertEqual({"value": "Hello World"}, frames[-1]["structuredContent"])
        self.assertEqual(1, sum(f["kind"] == "RESULT" for f in frames))
        output = io.BytesIO()
        inp["deadlineUnixMs"] = 1
        self.assertEqual(0, run(io.BytesIO(json.dumps(inp).encode()), output, {}, self.build))
        self.assertEqual("DEADLINE_EXCEEDED", json.loads(output.getvalue())["error"]["code"])

    def test_http_progress_request_uses_sse_even_without_progress_emissions(self):
        from toolgate.protocol.http import StreamableHttpServer
        with ToolHarness(self.build) as h, StreamableHttpServer(h.runtime.core, ("127.0.0.1", 0)) as server:
            request = message("tools/call", name="runtime.coroutine", arguments={})
            request["params"]["_meta"]["progressToken"] = "progress-1"
            connection = http.client.HTTPConnection(*server.address, timeout=3)
            try:
                connection.request("POST", "/mcp", json.dumps(request), {
                    "MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/call",
                    "Mcp-Name": "runtime.coroutine", "Accept": "text/event-stream"})
                response = connection.getresponse()
                self.assertEqual(200, response.status)
                self.assertEqual("text/event-stream", response.getheader("Content-Type"))
                result = json.loads(response.read().split(b"data: ")[1])
                self.assertEqual({"value": "async"}, result["result"]["structuredContent"])
            finally:
                connection.close()

    def test_governed_principal_comes_from_the_host_and_never_from_claims(self):
        build = compiled(PRINCIPAL_SOURCE)
        principal = {"tenantId": "10000000-0000-4000-8000-000000000001", "mode": "DELEGATED", "userId": "user-42",
                     "agentId": "agent-planner", "chain": ["agent-router"], "operation": "read",
                     "resources": [{"kind": "CUSTOM", "locator": "olo://memory/user-42"}]}
        inp = {"protocolVersion": 2, "invocationId": "who-1", "deadlineUnixMs": int(time.time()*1000)+5000,
               "toolId": "acme.principal/whoami", "arguments": {}, "claims": {"sub": "u-1", "toolgate": principal}}
        output = io.BytesIO()
        self.assertEqual(0, run(io.BytesIO(json.dumps(inp).encode()+b"\n"), output, {}, build))
        result = json.loads(output.getvalue().splitlines()[-1])["structuredContent"]
        self.assertEqual({"user": "user-42", "tenant": principal["tenantId"], "agent": "agent-planner", "operation": "read",
                          "chain": ["agent-router"], "resources": ["CUSTOM:olo://memory/user-42"], "claims": ["sub"]}, result)

    def test_user_scoped_tools_refuse_without_a_verified_user(self):
        build = compiled(PRINCIPAL_SOURCE)
        for claims in ({}, {"toolgate": {"tenantId": "10000000-0000-4000-8000-000000000001", "mode": "SERVICE"}}):
            inp = {"protocolVersion": 2, "invocationId": "who-2", "deadlineUnixMs": int(time.time()*1000)+5000,
                   "toolId": "acme.principal/whoami", "arguments": {}, "claims": claims}
            output = io.BytesIO()
            run(io.BytesIO(json.dumps(inp).encode()+b"\n"), output, {}, build)
            self.assertEqual("CLAIMS_UNAVAILABLE", json.loads(output.getvalue().splitlines()[-1])["error"]["code"])
        with ToolHarness(build) as h:
            self.assertEqual("CLAIMS_UNAVAILABLE", h.call("whoami").error_code)

    def test_governed_routes_use_stdio_broker(self):
        inp = {"protocolVersion": 2, "invocationId": "invoke-2", "deadlineUnixMs": int(time.time()*1000)+5000,
               "toolId": "acme.runtime/lookup", "arguments": {"id": "7"}}
        answer = {"kind": "BROKER_RESPONSE", "requestId": "broker-1", "result": {"body": {"value": "brokered"}}}
        output = io.BytesIO()
        self.assertEqual(0, run(io.BytesIO((json.dumps(inp)+"\n"+json.dumps(answer)+"\n").encode()), output, {}, self.build))
        frames = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual("ROUTE_CALL", frames[0]["operation"])
        self.assertEqual({"value": "brokered"}, frames[-1]["structuredContent"])

    def test_governed_client_files_use_executor_broker(self):
        inp = {"protocolVersion": 2, "invocationId": "device-1", "deadlineUnixMs": int(time.time()*1000)+5000,
               "toolId": "acme.runtime/files", "arguments": {}}
        answers = [{"kind": "BROKER_RESPONSE", "requestId": "broker-1", "result": {}},
                   {"kind": "BROKER_RESPONSE", "requestId": "broker-2", "result": {"text": "hello"}}]
        output = io.BytesIO()
        data = "\n".join(json.dumps(value) for value in [inp, *answers])+"\n"
        self.assertEqual(0, run(io.BytesIO(data.encode()), output, {"TOOLGATE_EXECUTION_TARGET":"CLIENT"}, self.build))
        frames = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual(["FILES_APPEND", "FILES_READ"], [f["operation"] for f in frames if f["kind"] == "BROKER_REQUEST"])
        self.assertEqual({"value":"hello"}, frames[-1]["structuredContent"])

    def test_jwt_shared_java_vectors(self):
        fixtures = REPO / "tool-sdk/conformance/fixtures/identity"
        tokens = json.loads((fixtures / "tokens.json").read_text())
        keys = json.loads((fixtures / "jwks.json").read_text())
        try:
            verifier = JwtVerifier(tokens["issuer"], tokens["audience"], keys=keys)
        except StartupRefused as exc:
            self.skipTest(str(exc))
        with patch("toolgate.runtime.identity.time.time", return_value=tokens["now"]):
            for entry in tokens["tokens"]:
                if entry["expect"] not in {"accept", "reject"}:
                    continue
                with self.subTest(entry["name"]):
                    if entry["expect"] == "accept":
                        self.assertEqual("user-1", verifier.verify(entry["token"]).subject)
                    else:
                        with self.assertRaises(Rejected):
                            verifier.verify(entry["token"])


class StateTest(unittest.TestCase):
    def test_task_restart_marks_inflight_outcome_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"state.db"
            store = SqliteTaskStore(path)
            t = TaskRecord()
            t.task_id, t.owner = "one", "owner"
            store.create(t)
            store.close()
            store = SqliteTaskStore(path)
            try:
                recovered = store.get("one", "owner")
                self.assertEqual(TaskStatus.FAILED, recovered.status)
                self.assertEqual("OUTCOME_UNKNOWN", recovered.error["data"]["code"])
                self.assertIsNone(store.get("one", "other"))
            finally:
                store.close()

    def test_reservations_are_durable_and_reject_conflicts(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"state.db"
            reservations = Reservations(path)
            self.assertEqual("NEW", reservations.reserve("tenant", "key", "owner", "hash")[0])
            self.assertEqual("IN_PROGRESS", reservations.reserve("tenant", "key", "owner", "hash")[0])
            self.assertEqual("IDEMPOTENCY_CONFLICT", reservations.reserve("tenant", "key", "owner", "different")[0])
            self.assertEqual("IDEMPOTENCY_KEY_IN_USE", reservations.reserve("tenant", "key", "other", "hash")[0])
            reservations.finish("tenant", "key", {"ok": True})
            reservations.close()
            reservations = Reservations(path)
            try:
                self.assertEqual(("REPLAY", {"ok": True}), reservations.reserve("tenant", "key", "owner", "hash"))
            finally:
                reservations.close()

    def test_files_are_confined_and_size_bounded(self):
        area = MemoryDeviceFiles()
        area.append_text("notes.txt", "hello")
        self.assertEqual(5, area.info("notes.txt").bytes)
        for path in ("../notes.txt", "con.txt", "/absolute", "hidden/.file"):
            with self.assertRaises(Exception):
                area.write_text(path, "bad")
        with self.assertRaises(Exception):
            area.append_text("notes.txt", "x"*65536)
        self.assertEqual("hello", area.read_text("notes.txt"))


if __name__ == "__main__":
    unittest.main()
