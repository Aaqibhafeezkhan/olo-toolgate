# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Java-compatible invocation pipeline shared by standalone, test and governed modes."""
from __future__ import annotations

import asyncio
import copy
import datetime as dt
import importlib
import importlib.resources
import inspect
import os
import queue
import sqlite3
import threading
import time
import typing
import uuid
from pathlib import Path

from .._json import dumpb, dumps, loads, now
from .._version import SDK_VERSION
from ..context import ToolResult
from ..descriptor import Build
from ..errors import (PlatformCode, PlatformError, ToolBusinessError, ToolConfigurationError, ToolError,
                      ToolInputError, ToolRetryableError, UpstreamError)
from ..jcs import canonical, protocol_digest, sha256, utf16_length
from ..protocol.core import TASKS, RequestMeta, ToolCatalog
from ..protocol.errors import INVALID_PARAMS, ProtocolError
from ..protocol.tasks import MemoryTaskStore, Tasks
from .config import Config, Mode, StartupRefused
from .context import InputNeeded, StandaloneContext
from .converter import from_json, to_json
from .diagnostics import Diagnostics
from .files import MemoryDeviceFiles, ReadOnlyFiles
from .redactor import Redactor, cap
from .schema import SchemaValidator
from .state import Reservations, SqliteTaskStore
from .values import DeclaredValues, Entry, type_of


def load_build():
    """Read the immutable generated index; never import/build arbitrary project modules at runtime."""
    base = importlib.resources.files("_toolgate_index")
    result = Build()
    result.index_bytes = base.joinpath("package.json").read_bytes()
    result.index = loads(result.index_bytes)
    for entry in result.index["tools"]:
        name = entry["name"]
        data = base.joinpath("tools", name + ".json").read_bytes()
        if sha256(data) != entry["toolDescriptorDigest"]:
            raise StartupRefused("CONFIGURATION", "The descriptor digest does not match the index")
        result.bytes[name] = data
        result.descriptors[name] = loads(data)
    return result


def failure(code, message, retryable=False, data=None, redactor=None):
    redact = redactor or Redactor()
    message = cap(redact.text(message or code), 4096)
    try:
        safe = redact.apply(to_json(data or {}))
    except (TypeError, ValueError):
        safe = {}
    if len(dumpb(safe)) > 16384:
        safe = {"truncated": True}
    return {"content": [{"type": "text", "text": message}], "isError": True,
            "structuredContent": {"error": {"code": code, "message": message,
                                             "retryable": retryable, "data": safe}}}


def typed(value, value_type):
    if value is None:
        return None
    if isinstance(value, str) and value_type in {"INT", "LONG", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY"}:
        try:
            value = loads(value)
        except ValueError:
            raise ToolConfigurationError("A configured " + value_type + " is not valid JSON")
    schemas = {"INT": {"type": "integer", "minimum": -2**31, "maximum": 2**31-1},
               "LONG": {"type": "integer", "minimum": -9007199254740991, "maximum": 9007199254740991},
               "NUMBER": {"type": "number"}, "BOOLEAN": {"type": "boolean"}, "OBJECT": {"type": "object"},
               "ARRAY": {"type": "array"}, "DATE": {"type": "string", "format": "date"},
               "DATE_TIME": {"type": "string", "format": "date-time"},
               "DURATION": {"type": "string", "format": "duration"}, "URI": {"type": "string", "format": "uri"},
               "BINARY": {"type": "string", "contentEncoding": "base64"}}
    if SchemaValidator(schemas.get(value_type, {"type": "string"})).validate(value):
        raise ToolConfigurationError("A configured value does not match " + value_type)
    return value


class StandaloneRuntime(ToolCatalog):
    def __init__(self, build=None, config=None, *, env=None, working_directory=None, diagnostics=None,
                 exchanges=None, files=None):
        self.build = build or load_build()
        self.config = config or Config.load(env if env is not None else os.environ, working_directory or Path.cwd())
        self.diagnostics = diagnostics or Diagnostics()
        self.exchanges = exchanges
        self.files = files or MemoryDeviceFiles()
        self.redactor = Redactor()
        self._contexts = set()
        self._contexts_lock = threading.Lock()
        self._closed = False
        self._reservations = None
        self._tasks = None
        self._core = None
        self.verifier = None
        location = self.config.env.get("TOOLGATE_EXECUTION_TARGET", "SERVER")
        if location not in {"SERVER", "CLIENT"}:
            raise StartupRefused("CONFIGURATION", "Execution target must be SERVER or CLIENT")
        self.location = location
        self.entries = {e["name"]: e for e in self.build.index["tools"]}
        self.by_name = {d["mcpName"]: n for n, d in self.build.descriptors.items()
                        if self.config.mode is Mode.TEST or d["kind"] == "SERVER" or location == "CLIENT"}
        self.semaphores = {n: threading.BoundedSemaphore(d["execution"].get("maxConcurrency", 64))
                           for n, d in self.build.descriptors.items()}
        self.globals = {}
        self.secrets = {}
        try:
            self._start(working_directory or Path.cwd())
        except Exception:
            self.close()
            raise

    def _start(self, directory):
        if self.config.mode is Mode.TEST and self.config.env:
            raise StartupRefused("CONFIGURATION", "Test mode is available only through ToolHarness")
        if not self.by_name:
            raise StartupRefused("CLIENT_TOOLS_UNAVAILABLE", "The package has no tools eligible for this execution target")
        from .identity import verifier_from_env
        self.verifier = verifier_from_env(self.config.env, self.config.mode)
        if self.config.mode is Mode.PRODUCTION and self.verifier is None:
            for n in self.by_name.values():
                d = self.build.descriptors[n]
                if d["variables"]["claims"] or "USER_SECRET" in d["permissions"] or "TOKEN_EXCHANGE" in d["permissions"] \
                        or d["businessKey"]["namespace"] == "DELEGATED_USER":
                    raise StartupRefused("CLAIMS_UNAVAILABLE", "This tool needs a verified identity")
        for n in self.by_name.values():
            d = self.build.descriptors[n]
            gs, ss = {}, {}
            for spec in d["variables"]["globals"]:
                value = self.config.global_value(spec["name"])
                if value is None:
                    value = spec.get("default")
                try:
                    value = typed(value, spec["type"])
                    if value is None and spec.get("required"):
                        raise ToolConfigurationError("Missing global " + spec["name"])
                    # Constraints use the same schema vocabulary as descriptor properties.
                    schema = {k: v for k, v in spec.items() if k in {
                        "minimum", "maximum", "minLength", "maxLength", "pattern", "enum", "minItems", "maxItems"}}
                    if value is not None and SchemaValidator(schema).validate(value):
                        raise ToolConfigurationError("Global " + spec["name"] + " violates its constraints")
                except ToolConfigurationError as exc:
                    raise StartupRefused("CONFIGURATION", str(exc))
                gs[spec["name"]] = Entry(spec["type"], value)
                if spec.get("sensitive"):
                    self.redactor.add(value)
            for spec in d["variables"]["secrets"]:
                # Governed auth secrets are never redeemed by the tool: the broker owns authentication.
                broker_only = self.config.mode is Mode.GOVERNED and "CREDENTIALS_IN_SANDBOX" not in d["permissions"]
                value = None if broker_only else typed(self.config.secret(spec["name"]), spec["type"])
                self.redactor.add(value)
                ss[spec["name"]] = Entry(spec["type"], value)
            self.globals[n], self.secrets[n] = gs, ss
        path = Path(self.config.env.get("TOOLGATE_STATE_DB", str(Path(directory) / ".toolgate" / "state.db")))
        if not path.is_absolute():
            path = Path(directory) / path
        store_kind = self.config.env.get("TOOLGATE_TASK_STORE", "memory")
        if store_kind not in {"memory", "sqlite"}:
            raise StartupRefused("CONFIGURATION", "TOOLGATE_TASK_STORE must be memory or sqlite")
        try:
            store = SqliteTaskStore(path) if store_kind == "sqlite" else MemoryTaskStore()
            self._tasks = Tasks(store)
            if self.config.mode not in {Mode.TEST, Mode.GOVERNED} and any(
                    d["businessKey"].get("required") or d["businessKey"].get("argument")
                    for d in self.build.descriptors.values()):
                from .formats import parse_duration
                retention = parse_duration(self.config.env.get("TOOLGATE_BUSINESS_KEY_RETENTION", "P30D")).total_seconds()
                if retention <= 0:
                    raise ValueError("retention must be positive")
                self._reservations = Reservations(path, retention)
        except (OSError, sqlite3.Error, ValueError) as exc:
            raise StartupRefused("CONFIGURATION", "The state store cannot open: " + str(exc))
        for warning in self.config.warnings:
            self.diagnostics.println("toolgate: " + warning)

    def server_info(self):
        return {"name": self.build.index["name"], "version": SDK_VERSION}

    def discover_meta(self):
        return {"io.ololabs.toolgate/descriptorDigests": {
            d["toolId"]: self.build.digest(n) for n, d in self.build.descriptors.items()},
            "io.ololabs.toolgate/descriptorSetDigest": self.build.descriptor_set_digest()}

    def tasks(self):
        return self._tasks

    @property
    def core(self):
        if self._core is None:
            from ..protocol.core import ProtocolCore
            self._core = ProtocolCore(self)
        return self._core

    def tools(self, meta):
        out = []
        for mcp, n in self.by_name.items():
            d = self.build.descriptors[n]
            if d["execution"]["async"] and not meta.declares(TASKS):
                continue
            effect = d["effect"]
            tool = {"name": mcp, "title": d["title"], "description": d["description"] + (
                "\n\nUse when: " + d["useWhen"] if d.get("useWhen") else ""),
                    "inputSchema": copy.deepcopy(d["inputSchema"]),
                    "annotations": {"readOnlyHint": effect["safety"] == "READ_ONLY",
                                    "destructiveHint": effect["destructive"],
                                    "idempotentHint": effect["safety"] in {"READ_ONLY", "IDEMPOTENT"},
                                    "openWorldHint": effect["openWorld"]}}
            if "outputSchema" in d:
                tool["outputSchema"] = copy.deepcopy(d["outputSchema"])
            if d["execution"]["async"]:
                tool["execution"] = {"taskSupport": "required"}
            out.append(tool)
        return out

    def call(self, name, arguments, meta):
        result = self._call_impl(name, arguments, meta)
        if self.config.mode.fixtures:
            result.setdefault("_meta", {})["io.ololabs.toolgate/identity"] = "fixture"
        return result

    def _call_impl(self, name, arguments, meta):
        n = self.by_name.get(name)
        if n is None:
            raise ProtocolError(INVALID_PARAMS, "Unknown tool: " + name)
        d = self.build.descriptors[n]
        if d["execution"]["async"] and not meta.declares(TASKS):
            raise ProtocolError.missing_extension(TASKS)
        args = copy.deepcopy(arguments)
        if len(dumpb(args)) > 1048576 or len(args) > 256:
            return failure("INVALID_ARGUMENT", "Arguments exceed the input limit")
        for key, schema in d["inputSchema"]["properties"].items():
            if key not in args and "default" in schema:
                args[key] = copy.deepcopy(schema["default"])
        invalid = SchemaValidator(d["inputSchema"]).validate(args)
        if invalid:
            return failure("INVALID_ARGUMENT", "Arguments do not match inputSchema: " + invalid)
        redact = self.redactor.copy()
        for key, schema in d["inputSchema"]["properties"].items():
            if schema.get("x-toolgate-sensitive") or schema.get("writeOnly"):
                redact.add(args.get(key))
        duration = d["execution"]["maxDurationMs"] / 1000
        deadline = now() + dt.timedelta(seconds=duration)
        if hasattr(self.config, "deadline"):
            deadline = min(deadline, self.config.deadline)
        claims = self.config.claims() if self.config.mode.configured_claims else (
            meta.identity.claims if meta.identity else None)
        def claim_value(path):
            value = claims or {}
            if not path.startswith("/"):
                return value.get(path)
            for component in path[1:].split("/"):
                component = component.replace("~1", "/").replace("~0", "~")
                if isinstance(value, dict):
                    value = value.get(component)
                elif isinstance(value, list) and component.isdigit() and int(component) < len(value):
                    value = value[int(component)]
                else:
                    return None
            return value
        cs = {c["name"]: Entry(c["type"], typed(claim_value(c["claim"]), c["type"]))
              for c in d["variables"]["claims"]}
        missing = lambda name: ToolConfigurationError("Missing declared value " + name)
        jwt = DeclaredValues("claim", cs, lambda name: PlatformError(PlatformCode.CLAIMS_UNAVAILABLE,
                                                                     "No verified claim " + name))
        jwt.identity = claims
        invocation = getattr(self.config, "invocation_id", str(uuid.uuid4()))
        ctx = StandaloneContext(DeclaredValues("input", {k: Entry(type_of(v), args.get(k))
                                                        for k, v in d["inputSchema"]["properties"].items()}, missing),
                                DeclaredValues("global", self.globals[n], missing),
                                DeclaredValues("secret", self.secrets[n], missing), jwt,
                                invocation, d["toolId"], deadline, redact, self.diagnostics)
        ctx.attach_input_responses(meta.input_responses)
        ctx.attach_progress(meta.progress_token, meta.notifications)
        from ..enums import InvocationMode
        ctx._mode = InvocationMode.GOVERNED if self.config.mode is Mode.GOVERNED else InvocationMode.STANDALONE
        if self.config.mode is Mode.GOVERNED:
            ctx.attach_principal(getattr(self.config, "principal", None))
            ctx.attach_execution(getattr(self.config, "execution", None), getattr(self.config, "traceparent", None))
        ctx._frames = getattr(self.config, "frames", None)
        permissions = d["permissions"]
        ctx._files = (self.files if "DEVICE_FILES_WRITE" in permissions else ReadOnlyFiles(self.files)) \
            if "DEVICE_FILES_READ" in permissions else None
        from .network import HttpClient, TcpConnector
        ctx.attach_http(HttpClient(d, self.config, ctx, self.exchanges))
        ctx.attach_tcp(TcpConnector(d, self.config, ctx))
        binding = None
        key_spec = d["businessKey"]
        key = args.get(key_spec.get("argument"))
        if not isinstance(key, str):
            key = None
        if key is not None and meta.idempotency_key is not None and key != meta.idempotency_key:
            return failure("INVALID_ARGUMENT", "The business key argument and Idempotency-Key must agree")
        key = key if key is not None else meta.idempotency_key
        if self.config.mode not in {Mode.GOVERNED, Mode.TEST}:
            if key is not None and (not isinstance(key, str) or not 1 <= utf16_length(key) <= 255):
                return failure("INVALID_ARGUMENT", "A business key has 1 to 255 characters")
            if key_spec.get("required") and (not isinstance(key, str) or not key):
                return failure("INVALID_ARGUMENT", "Supply the business key argument or Idempotency-Key")
            if key is not None and (key_spec.get("required") or key_spec.get("argument")):
                owner = meta.identity.owner if meta.identity else "local"
                if claims and self.config.mode.fixtures:
                    owner = "identity:" + str(claims.get("iss", "")) + "|" + str(claims.get("sub", ""))
                namespace = key_spec["namespace"]
                if namespace == "DELEGATED_USER" and not (claims or {}).get("sub"):
                    return failure("BUSINESS_KEY_NAMESPACE_MISMATCH", "A verified user is required")
                scope = self.build.index["id"] + "/" + namespace + ("/" + owner if namespace != "TENANT" else "")
                fingerprint = protocol_digest({"arguments": args, "package": self.build.index["id"],
                                               "effect": d["effect"]["safety"], "scope": "standalone",
                                               "user": owner if namespace != "DELEGATED_USER" else None})
                try:
                    state, replay = self._reservations.reserve(scope, key, owner, fingerprint)
                except (sqlite3.Error, AttributeError):
                    return failure("BUSINESS_KEY_STORE_UNAVAILABLE", "The business key store is unavailable", True)
                if state == "REPLAY":
                    return replay
                if state != "NEW":
                    return failure(state, state, state == "IN_PROGRESS")
                binding = (scope, key)
                ctx._business_owner = owner
                ctx._business_salt = self._reservations.salt
        ctx.attach_business_key(key)
        if not self.semaphores[n].acquire(timeout=min(1, max(0, (deadline-now()).total_seconds()))):
            if binding:
                self._reservations.release(*binding)
            return failure("CONCURRENCY_LIMIT", "The concurrency limit was reached", self._safe(d))
        if self._closed:
            self.semaphores[n].release()
            return failure("CANCELLED", "The runtime is closed")
        with self._contexts_lock:
            self._contexts.add(ctx)
        try:
            if d["execution"]["async"]:
                task = self._tasks.create(meta.owner, d["execution"]["cancelIfAbandoned"], None, ctx.cancel)
                ctx.attach_task(self._tasks, task.task_id)
                result = Tasks.create_result(task)
                if binding:
                    self._reservations.finish(*binding, result)
                def work():
                    outcome = self._execute(n, args, ctx)
                    if self._closed:
                        return
                    err = outcome.get("structuredContent", {}).get("error", {})
                    if err.get("code") == "OUTCOME_UNKNOWN":
                        self._tasks.fail(task.task_id, -32603, err["message"], err)
                    else:
                        self._tasks.complete(task.task_id, outcome)
                threading.Thread(target=work, name="toolgate-task", daemon=True).start()
                return result
            result = self._execute(n, args, ctx)
            if binding:
                if result.get("resultType") == "input_required":
                    self._reservations.release(*binding)
                else:
                    self._reservations.finish(*binding, result)
            return result
        except sqlite3.Error:
            self._release_context(n, ctx)
            return failure("TASK_STORE_UNAVAILABLE", "The task store is unavailable", True)
        except Exception:
            self._release_context(n, ctx)
            raise

    def _release_context(self, n, context):
        with self._contexts_lock:
            if not getattr(context, "_released_slot", False):
                context._released_slot = True
                self.semaphores[n].release()
            self._contexts.discard(context)

    @staticmethod
    def _safe(d):
        return d["effect"]["safety"] in {"READ_ONLY", "IDEMPOTENT"}

    def _execute(self, n, args, ctx):
        d = self.build.descriptors[n]
        box = queue.Queue(maxsize=1)
        def invoke():
            try:
                entry = self.entries[n]
                obj = importlib.import_module(entry["handlerModule"])
                parts = entry["handlerQualname"].split(".")
                for part in parts[:-1]:
                    obj = getattr(obj, part)
                if isinstance(obj, type):
                    obj = obj()
                fn = getattr(obj, parts[-1])
                hints = typing.get_type_hints(fn, include_extras=True)
                params = list(inspect.signature(fn).parameters.values())
                kwargs = {}
                positional = []
                for param, external in zip(params, entry["parameters"]):
                    argument = ctx if external == "$context" else from_json(args.get(external), hints[param.name])
                    if param.kind is inspect.Parameter.POSITIONAL_ONLY:
                        positional.append(argument)
                    else:
                        kwargs[param.name] = argument
                value = fn(*positional, **kwargs)
                if inspect.isawaitable(value):
                    async def run():
                        loop = asyncio.get_running_loop()
                        task = asyncio.ensure_future(value)
                        ctx.on_cancel(lambda: loop.call_soon_threadsafe(task.cancel))
                        return await task
                    value = asyncio.run(run())
                box.put((True, value))
            except BaseException as exc:
                box.put((False, exc))
            finally:
                self._release_context(n, ctx)
        threading.Thread(target=invoke, name="toolgate-invocation", daemon=True).start()
        try:
            while True:
                if ctx.is_cancelled():
                    return failure("CANCELLED", "The invocation was cancelled", redactor=ctx._redactor)
                left = (ctx.deadline-now()).total_seconds()
                if left <= 0:
                    ctx.cancel()
                    return failure("DEADLINE_EXCEEDED", "The invocation deadline passed", self._safe(d), redactor=ctx._redactor)
                try:
                    ok, value = box.get(timeout=min(left, .05))
                    break
                except queue.Empty:
                    continue
            if ctx.pending_input():
                return ctx.pending_input()
            if not ok:
                return self._thrown(n, value, ctx)
            if isinstance(value, ToolResult):
                result = {"content": to_json(value.content)}
                if value.structured_content is not None:
                    result["structuredContent"] = to_json(value.structured_content)
            else:
                structured = to_json(value)
                if "outputSchema" in d and SchemaValidator(d["outputSchema"]).validate(structured):
                    return failure("INTERNAL_TOOL_ERROR", "The tool failed.")
                result = {"content": [{"type": "text", "text": dumps(structured)}], "structuredContent": structured}
            if len(dumpb(result)) > 4194304:
                return failure("INTERNAL_TOOL_ERROR", "The tool failed.")
            result["isError"] = False
            return result
        except Exception as exc:
            return self._thrown(n, exc, ctx)

    def _thrown(self, n, exc, ctx):
        d = self.build.descriptors[n]
        code, message, retryable, data = "INTERNAL_TOOL_ERROR", "The tool failed.", False, {}
        if isinstance(exc, ToolError):
            code, message = exc.code, exc.message
            if isinstance(exc, (ToolBusinessError, ToolRetryableError)):
                declared = {e["code"]: e for e in d["errors"]}
                if code not in declared:
                    data, code = {"originalCode": code}, "UNDECLARED_ERROR"
                elif isinstance(exc, ToolBusinessError):
                    data = exc.data
                    schema = self.entries[n].get("errorDataSchemas", {}).get(code)
                    if schema and SchemaValidator(loads(schema) if isinstance(schema, str) else schema).validate(data):
                        code, message, data = "INTERNAL_TOOL_ERROR", "The tool failed.", {}
                else:
                    retryable = self._safe(d)
                    if exc.retry_after:
                        data["retryAfterMs"] = int(exc.retry_after.total_seconds()*1000)
            elif isinstance(exc, UpstreamError):
                data = {"route": exc.route}
                if exc.status is not None:
                    data["status"] = exc.status
                if exc.upstream_request_id:
                    data["upstreamRequestId"] = exc.upstream_request_id
                if not self._safe(d) and exc.effect_uncertain:
                    data["upstreamCode"], code = code, "OUTCOME_UNKNOWN"
                else:
                    retryable = self._safe(d)
            elif isinstance(exc, PlatformError):
                retryable = self._safe(d) and code in {"DEADLINE_EXCEEDED", "CONCURRENCY_LIMIT"}
        else:
            self.diagnostics.println("toolgate: " + ctx._redactor.text(repr(exc)))
        return failure(code, message, retryable, data, ctx._redactor)

    def close(self):
        self._closed = True
        with self._contexts_lock:
            for context in self._contexts:
                context.cancel()
        if self._tasks:
            self._tasks.close()
            self._tasks.store.close()
        if self._reservations:
            self._reservations.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
