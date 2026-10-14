# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The declarations of spec/authoring/python.md.

Each declaration records where it was written and registers itself with the module that wrote it. Applied as a
decorator it attaches to the class or handler below it; written as a plain statement at module level it applies to
the whole module. The descriptor build (toolgate.descriptor) imports the tool modules and reads what they recorded,
so nothing here is checked until the build runs; every rule is a build-time error there.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any, Callable, Iterable, Mapping, Optional, Sequence, Tuple, Union

from .enums import (AuthType, BusinessKey, EffectSafety, HttpMethod, IdempotencyScope, KeyNamespace, OpenWorld,
                    RuntimeTier, SecretScope, Streaming, ValueType)

__all__ = [
    "tool_package", "server_tool", "client_tool", "Param", "tool_variables", "Variable", "Global", "Secret",
    "JwtClaim", "destination", "http_auth", "http_route", "tcp_destination", "idempotency", "reconcile", "example",
    "ErrorDecl", "REGISTRY_ATTRIBUTE", "ATTACHED_ATTRIBUTE",
]

#: The module-level list every declaration registers in.
REGISTRY_ATTRIBUTE = "__toolgate_declarations__"
#: The list of declarations attached to a class or handler, top to bottom.
ATTACHED_ATTRIBUTE = "__toolgate__"

_THIS = os.path.abspath(__file__)


def _caller() -> Tuple[dict, str, int]:
    """The globals, file and line of the first frame outside this module."""
    frame = sys._getframe(1)
    while frame is not None and os.path.abspath(frame.f_code.co_filename) == _THIS:
        frame = frame.f_back
    if frame is None:  # pragma: no cover - only when called from nowhere
        return {}, "<unknown>", 0
    return frame.f_globals, frame.f_code.co_filename, frame.f_lineno


def _text(value: Any) -> str:
    """A declaration value written as the neutral string form, such as a min, max or default."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return repr(value)
    if hasattr(value, "name") and hasattr(type(value), "__members__"):
        return value.name
    return str(value)


def _strings(value: Union[str, Iterable[str], None]) -> Tuple[str, ...]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    return tuple(value)


def _attach(target: Any, declaration: "Declaration") -> None:
    attached = target.__dict__.get(ATTACHED_ATTRIBUTE) if hasattr(target, "__dict__") else None
    if attached is None:
        attached = []
        setattr(target, ATTACHED_ATTRIBUTE, attached)
    # Decorators apply bottom-up; inserting at the front keeps the list in source order.
    attached.insert(0, declaration)
    declaration.target = target


class Declaration:
    """A recorded declaration: where it was written and what it is attached to."""

    kind = "declaration"
    #: Whether the declaration may decorate a class, and a handler.
    on_class = True
    on_handler = True

    def __init__(self) -> None:
        module_globals, self.file, self.line = _caller()
        self.module = module_globals.get("__name__", "<unknown>")
        self.target: Any = None
        self.embedded = False
        module_globals.setdefault(REGISTRY_ATTRIBUTE, []).append(self)

    def __call__(self, target: Any) -> Any:
        if self.target is not None or self.embedded:
            raise TypeError(self.kind + " is already applied")
        _attach(target, self)
        return target

    @property
    def where(self) -> str:
        return "%s:%d" % (self.file, self.line)

    def __repr__(self) -> str:
        fields = ", ".join("%s=%r" % (k, v) for k, v in vars(self).items()
                           if k not in ("file", "line", "module", "target", "embedded"))
        return "%s(%s)" % (type(self).__name__, fields)


class ErrorDecl:
    """One declared error code of a tool."""

    def __init__(self, code: str, description: str, retryable: bool = False,
                 data_schema: Union[str, Mapping[str, Any], None] = None) -> None:
        self.code = code
        self.description = description
        self.retryable = bool(retryable)
        if data_schema is None or data_schema == "":
            self.data_schema = ""
        elif isinstance(data_schema, str):
            self.data_schema = data_schema
        else:
            self.data_schema = json.dumps(data_schema, separators=(",", ":"), ensure_ascii=False)

    def __repr__(self) -> str:
        return "ErrorDecl(%r, %r, retryable=%r)" % (self.code, self.description, self.retryable)


class _Spec:
    """The attributes Param, Variable and Global share."""

    def _spec(self, name: str, description: str, type: Any, required: bool, default_value: Any, min: Any, max: Any,
              min_length: int, max_length: int, pattern: str, enum_values: Iterable[str], format: str,
              sensitive: bool) -> None:
        self.name = name or ""
        self.description = description if description is not None else ""
        self.type = ValueType(type)
        self.required = bool(required)
        self.default_value = _text(default_value)
        self.min = _text(min)
        self.max = _text(max)
        self.min_length = int(min_length)
        self.max_length = int(max_length)
        self.pattern = pattern or ""
        self.enum_values = tuple(enum_values or ())
        self.format = format or ""
        self.sensitive = bool(sensitive)


class Param(_Spec):
    """Annotates a handler parameter or a property of an input or output type: ``Annotated[str, Param(...)]``."""

    def __init__(self, *, name: str = "", description: Optional[str] = None, type: ValueType = ValueType.INFER,
                 required: bool = True, default_value: Any = "", min: Any = "", max: Any = "", min_length: int = -1,
                 max_length: int = -1, pattern: str = "", enum_values: Iterable[str] = (), format: str = "",
                 sensitive: bool = False) -> None:
        self.described = description is not None
        self._spec(name, description, type, required, default_value, min, max, min_length, max_length, pattern,
                   enum_values, format, sensitive)

    def __repr__(self) -> str:
        return "Param(name=%r, description=%r)" % (self.name, self.description)


class Variable(_Spec):
    """A scalar input of the grouped form, inside ``tool_variables(inputs=[...])``."""

    def __init__(self, *, name: str, description: str, type: ValueType, required: bool = True,
                 default_value: Any = "", min: Any = "", max: Any = "", min_length: int = -1, max_length: int = -1,
                 pattern: str = "", enum_values: Iterable[str] = (), format: str = "", sensitive: bool = False) -> None:
        self._spec(name, description, type, required, default_value, min, max, min_length, max_length, pattern,
                   enum_values, format, sensitive)

    def __repr__(self) -> str:
        return "Variable(name=%r)" % (self.name,)


class Global(Declaration, _Spec):
    """A deployment setting of the tool, supplied by the administrator."""

    kind = "Global"

    def __init__(self, *, name: str, type: ValueType, default_value: Any = "", required: bool = True,
                 description: str = "", min: Any = "", max: Any = "", min_length: int = -1, max_length: int = -1,
                 pattern: str = "", enum_values: Iterable[str] = (), format: str = "", sensitive: bool = False) -> None:
        Declaration.__init__(self)
        self._spec(name, description, type, required, default_value, min, max, min_length, max_length, pattern,
                   enum_values, format, sensitive)


class Secret(Declaration):
    """A credential the tool reads by name; its value never appears in a descriptor, log or error."""

    kind = "Secret"

    def __init__(self, *, name: str, type: ValueType = ValueType.STRING, scope: SecretScope = SecretScope.GROUP,
                 description: str = "") -> None:
        super().__init__()
        self.name = name
        self.type = ValueType(type)
        self.scope = SecretScope(scope)
        self.description = description or ""


class JwtClaim(Declaration):
    """A claim of the caller's verified identity, read by name."""

    kind = "JwtClaim"

    def __init__(self, *, name: str, claim: str, type: ValueType = ValueType.STRING, required: bool = True) -> None:
        super().__init__()
        self.name = name
        self.claim = claim or ""
        self.type = ValueType(type)
        self.required = bool(required)


class tool_variables(Declaration):
    """The grouped form: inputs, globals, claims and secrets of one handler in a single decorator."""

    kind = "tool_variables"
    on_class = False

    def __init__(self, *, inputs: Sequence[Variable] = (), globals: Sequence[Global] = (),
                 jwt: Sequence[JwtClaim] = (), secrets: Sequence[Secret] = ()) -> None:
        super().__init__()
        self.inputs = tuple(inputs)
        self.globals = tuple(globals)
        self.jwt = tuple(jwt)
        self.secrets = tuple(secrets)
        for group in (self.globals, self.jwt, self.secrets):
            for d in group:
                d.embedded = True


class _Network(Declaration):
    on_handler = False


class destination(_Network):
    """An HTTPS destination; the host comes from a deployment setting."""

    kind = "destination"

    def __init__(self, *, id: str, host_setting: str, default_host: str = "", port: int = 443,
                 spki_pins: Iterable[str] = (), ca_bundle: str = "") -> None:
        super().__init__()
        self.id = id
        self.host_setting = host_setting
        self.default_host = default_host or ""
        self.port = int(port)
        self.spki_pins = _strings(spki_pins)
        self.ca_bundle = ca_bundle or ""


class http_auth(_Network):
    """How the platform authenticates route calls."""

    kind = "http_auth"

    def __init__(self, *, id: str, type: AuthType, header: str = "", secret: str = "", connection: str = "",
                 scopes: Iterable[str] = (), audience: str = "") -> None:
        super().__init__()
        self.id = id
        self.type = AuthType(type)
        self.header = header or ""
        self.secret = secret or ""
        self.connection = connection or ""
        self.scopes = _strings(scopes)
        self.audience = audience or ""


class http_route(_Network):
    """One HTTP route a tool may call: method, destination and an RFC 6570 level-1 path with patterns."""

    kind = "http_route"

    def __init__(self, *, id: str, destination: str, method: HttpMethod, path: str, query: Iterable[str] = (),
                 headers: Iterable[str] = (), request_content_types: Iterable[str] = ("application/json",),
                 max_request_bytes: int = 1_048_576, max_response_bytes: int = 4_194_304,
                 streaming: Streaming = Streaming.NONE, timeout_ms: int = 10_000, auth: str = "") -> None:
        super().__init__()
        self.id = id
        self.destination = destination
        self.method = HttpMethod(method)
        self.path = path
        self.query = _strings(query)
        self.headers = _strings(headers)
        self.request_content_types = _strings(request_content_types)
        self.max_request_bytes = int(max_request_bytes)
        self.max_response_bytes = int(max_response_bytes)
        self.streaming = Streaming(streaming)
        self.timeout_ms = int(timeout_ms)
        self.auth = auth or ""


class tcp_destination(_Network):
    """A TCP destination; the host comes from a deployment setting."""

    kind = "tcp_destination"

    def __init__(self, *, id: str, host_setting: str, port: int, default_host: str = "") -> None:
        super().__init__()
        self.id = id
        self.host_setting = host_setting
        self.default_host = default_host or ""
        self.port = int(port)


class idempotency(Declaration):
    """The header that carries the business key upstream."""

    kind = "idempotency"
    on_class = False

    def __init__(self, *, header: str, scope: IdempotencyScope = IdempotencyScope.BUSINESS_KEY) -> None:
        super().__init__()
        self.header = header
        self.scope = IdempotencyScope(scope)


class reconcile(Declaration):
    """Marks a tool as the reconciler of another tool's uncertain outcomes."""

    kind = "reconcile"
    on_class = False

    def __init__(self, *, tool: str) -> None:
        super().__init__()
        self.tool = tool


def _json_text(value: Any) -> str:
    if value is None or value == "":
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


class example(Declaration):
    """An example call, checked by ``python -m toolgate check`` and the harness.

    ``exchanges`` is a path, relative to the declaring module's directory, to the recorded exchanges the example's
    route calls get (spec/authoring/testing.md). An example without one must not call a route.
    """

    kind = "example"
    on_class = False

    def __init__(self, *, arguments: Union[str, Mapping[str, Any]], expected_output: Union[str, Mapping, None] = "",
                 expected_error: str = "", exchanges: str = "") -> None:
        super().__init__()
        self.arguments = _json_text(arguments)
        self.expected_output = _json_text(expected_output)
        self.expected_error = expected_error or ""
        self.exchanges = exchanges or ""


class ToolDeclaration(Declaration):
    """What server_tool and client_tool share."""

    on_class = False
    client = False

    def __init__(self, name: str, title: str, text: str, description: str, use_when: str, effect: Any,
                 destructive: bool, open_world: Any, async_: bool, max_duration_ms: Optional[int], stateless: bool,
                 max_concurrency: Optional[int], routes: Any, tcp: Any, requires_business_key: Any,
                 business_key_namespace: Any, business_key_argument: str, cancel_if_abandoned: bool,
                 errors: Sequence[ErrorDecl], toolsets: Any) -> None:
        super().__init__()
        self.name = name
        self.title = title or ""
        self.text = text or ""
        self.description = description if description is not None else ""
        self.use_when = use_when or ""
        self.effect = EffectSafety(effect)
        self.destructive = bool(destructive)
        if open_world is None:
            self.open_world = OpenWorld.DEFAULT
        elif isinstance(open_world, bool):
            self.open_world = OpenWorld.TRUE if open_world else OpenWorld.FALSE
        else:
            self.open_world = OpenWorld(open_world)
        self.async_ = bool(async_)
        self.max_duration_ms = -1 if max_duration_ms is None else int(max_duration_ms)
        self.stateless = bool(stateless)
        self.max_concurrency = -1 if max_concurrency is None else int(max_concurrency)
        self.routes = _strings(routes)
        self.tcp = _strings(tcp)
        if requires_business_key is None:
            self.requires_business_key = BusinessKey.DEFAULT
        elif isinstance(requires_business_key, bool):
            self.requires_business_key = BusinessKey.REQUIRED if requires_business_key else BusinessKey.NOT_REQUIRED
        else:
            self.requires_business_key = BusinessKey(requires_business_key)
        self.business_key_namespace = KeyNamespace(business_key_namespace)
        self.business_key_argument = business_key_argument or ""
        self.cancel_if_abandoned = bool(cancel_if_abandoned)
        self.errors = tuple(errors or ())
        self.toolsets = _strings(toolsets)
        self.handler: Optional[Callable[..., Any]] = None

    def __call__(self, handler: Any) -> Any:
        if self.handler is not None:
            raise TypeError(self.kind + " is already applied")
        if not callable(handler) or isinstance(handler, type):
            raise TypeError(self.kind + " decorates a function or method")
        self.handler = handler
        code = getattr(handler, "__code__", None)
        if code is not None:
            self.file, self.line = code.co_filename, code.co_firstlineno
        _attach(handler, self)
        return handler


class server_tool(ToolDeclaration):
    """A tool that runs on the server side and may call declared routes and TCP destinations."""

    kind = "server_tool"

    def __init__(self, *, name: str, description: Optional[str] = None, title: str = "", text: str = "",
                 use_when: str = "", effect: EffectSafety = EffectSafety.UNKNOWN, destructive: bool = False,
                 open_world: Union[bool, OpenWorld, None] = None, async_: bool = False,
                 max_duration_ms: Optional[int] = None, stateless: bool = False,
                 max_concurrency: Optional[int] = None, routes: Union[str, Iterable[str]] = (),
                 tcp: Union[str, Iterable[str]] = (), requires_business_key: Union[bool, BusinessKey, None] = None,
                 business_key_namespace: KeyNamespace = KeyNamespace.AGENT, business_key_argument: str = "",
                 cancel_if_abandoned: bool = False, errors: Sequence[ErrorDecl] = (),
                 toolsets: Union[str, Iterable[str]] = ()) -> None:
        super().__init__(name, title, text, description, use_when, effect, destructive, open_world, async_,
                         max_duration_ms, stateless, max_concurrency, routes, tcp, requires_business_key,
                         business_key_namespace, business_key_argument, cancel_if_abandoned, errors, toolsets)


class client_tool(ToolDeclaration):
    """A tool that runs on the client's device, in one of the declared tiers. It has no egress and no secrets."""

    kind = "client_tool"
    client = True

    def __init__(self, *, name: str, description: Optional[str] = None, title: str = "", text: str = "",
                 use_when: str = "", effect: EffectSafety = EffectSafety.UNKNOWN, destructive: bool = False,
                 open_world: Union[bool, OpenWorld, None] = None, async_: bool = False,
                 max_duration_ms: Optional[int] = None, stateless: bool = False,
                 max_concurrency: Optional[int] = None, requires_business_key: Union[bool, BusinessKey, None] = None,
                 business_key_namespace: KeyNamespace = KeyNamespace.AGENT, business_key_argument: str = "",
                 cancel_if_abandoned: bool = False, errors: Sequence[ErrorDecl] = (),
                 toolsets: Union[str, Iterable[str]] = (),
                 tiers: Union[RuntimeTier, Iterable[RuntimeTier]] = (RuntimeTier.OCI_SANDBOX,)) -> None:
        super().__init__(name, title, text, description, use_when, effect, destructive, open_world, async_,
                         max_duration_ms, stateless, max_concurrency, (), (), requires_business_key,
                         business_key_namespace, business_key_argument, cancel_if_abandoned, errors, toolsets)
        if isinstance(tiers, (str, RuntimeTier)):
            tiers = (tiers,)
        self.tiers = tuple(RuntimeTier(t) for t in tiers)


class ToolPackageDeclaration(Declaration):
    kind = "tool_package"

    def __init__(self, id: str, name: str, publisher: str, description: str) -> None:
        super().__init__()
        self.id = id
        self.name = name or ""
        self.publisher = publisher or ""
        self.description = description or ""

    def __call__(self, target: Any) -> Any:
        raise TypeError("tool_package is a module-level statement, not a decorator")


def tool_package(*, id: str, name: str, publisher: str, description: str = "") -> ToolPackageDeclaration:
    """Declares the tool package, once, in the package's ``__init__`` or ``toolgate_package.py``.

    Module-level Global, Secret and JwtClaim statements in that module apply to every tool of the package.
    """
    return ToolPackageDeclaration(id, name, publisher, description)
