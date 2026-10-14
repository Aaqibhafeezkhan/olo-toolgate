# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Turns the recorded declarations into generation-1 semantic descriptors (tool-sdk/spec/descriptor).

A port of the Java SDK's DescriptorProcessor: the same checks with the same messages, and byte-identical
descriptors for the same declarations. It runs over imported modules; their declarations recorded themselves at
import time (toolgate.declarations).
"""

from __future__ import annotations

import inspect
import json
import re
import sys
import types
import typing
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

from .. import accessors
from ..context import ToolContext, ToolResult
from ..declarations import (ATTACHED_ATTRIBUTE, REGISTRY_ATTRIBUTE, Declaration, ErrorDecl, Global, JwtClaim, Param,
                            Secret, ToolDeclaration, ToolPackageDeclaration, client_tool, destination, example,
                            http_auth, http_route, idempotency, reconcile, server_tool, tcp_destination,
                            tool_variables)
from ..enums import AuthType, BusinessKey, EffectSafety, OpenWorld, SecretScope, ValueType
from ..jcs import canonical, sha256, sorted_utf16, utf16_key, utf16_length
from . import schemas as S
from .problems import BuildFailed, Problem, Site

GENERATION = 1

PACKAGE_ID = re.compile(r"^[a-z][a-z0-9-]{1,30}(\.[a-z][a-z0-9-]{1,30}){1,3}$")
LOCAL_NAME = re.compile(r"^[a-z][a-z0-9_-]{0,47}(\.[a-z][a-z0-9_-]{0,47}){0,2}$")
TOOL_ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$")
MCP_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,128}$")
VARIABLE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
DECLARATION_ID = re.compile(r"^[a-z][a-z0-9_.-]{0,63}$")
ERROR_CODE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
HEADER = re.compile(r"^[A-Za-z0-9-]{1,128}$")
RESERVED = ("toolgate.", "hotfolder.", "builtin.")
FORBIDDEN_HEADERS = {"authorization", "proxy-authorization", "cookie", "host"}


def _full(pattern: "re.Pattern[str]", value: str) -> bool:
    # Java's Matcher.matches() is a whole-string match; Python's $ would also accept a trailing newline.
    return isinstance(value, str) and pattern.fullmatch(value) is not None


class Facts:
    """What one tool handler may use. Value maps are name to value type, as the runtime names them."""

    def __init__(self, tool: str, routes: Set[str], inputs: Dict[str, str], globals: Dict[str, str],
                 secrets: Dict[str, str], claims: Dict[str, str], errors: Set[str], context: Optional[str]) -> None:
        self.tool = tool
        self.routes = routes
        self.inputs = inputs
        self.globals = globals
        self.secrets = secrets
        self.claims = claims
        self.errors = errors
        self.context = context


class Handler:
    """A resolved tool handler: its declaration, function, module and owning class."""

    def __init__(self, declaration: ToolDeclaration, module: types.ModuleType, qualname: str,
                 owner: Optional[type]) -> None:
        self.declaration = declaration
        self.function = declaration.handler
        self.module = module
        self.qualname = qualname
        self.owner = owner
        self.site = Site.of(declaration, declaration.name)

    @property
    def scope_name(self) -> str:
        return self.module.__name__ + ("." + self.qualname.rsplit(".", 1)[0] if self.owner is not None else "")


class Build:
    """The result of a descriptor build: canonical descriptor bytes per tool, and the package index."""

    def __init__(self) -> None:
        self.package: Optional[ToolPackageDeclaration] = None
        self.descriptors: Dict[str, Dict[str, Any]] = {}
        self.bytes: Dict[str, bytes] = {}
        self.handlers: Dict[str, Handler] = {}
        self.facts: Dict[str, Facts] = {}
        self.index: Dict[str, Any] = {}
        self.index_bytes = b""
        self.notes: List[str] = []

    def digest(self, tool: str) -> str:
        return sha256(self.bytes[tool])

    def descriptor_set_digest(self) -> str:
        entries = sorted(({"toolId": d["toolId"], "toolDescriptorDigest": self.digest(n)}
                          for n, d in self.descriptors.items()), key=lambda e: utf16_key(e["toolId"]))
        return sha256(canonical(entries))


def check_generation(generation: Any) -> None:
    if generation is None:
        raise BuildFailed([Problem(None, "the build must set descriptor_generation in [tool.toolgate] of"
                                         " pyproject.toml; it has no default")])
    if str(generation).strip() != str(GENERATION):
        raise BuildFailed([Problem(None, "descriptor_generation=" + str(generation)
                                         + " is not supported; this SDK emits generation " + str(GENERATION))])


def build(modules: Sequence[types.ModuleType], generation: Any, analyse: bool = True) -> Build:
    """Builds the descriptors of the package the modules declare; raises BuildFailed with every problem found."""
    check_generation(generation)
    return _Builder(list(modules), analyse).run()


class _Variables:
    """Scope-level or tool-level globals, secrets and claims, with case-insensitive name tracking."""

    def __init__(self) -> None:
        self.globals: List[Global] = []
        self.secrets: List[Secret] = []
        self.claims: List[JwtClaim] = []
        self.names: Dict[str, str] = {}

    def add(self, site: Site, gs: Iterable[Global], ss: Iterable[Secret], cs: Iterable[JwtClaim]) -> None:
        for g in gs:
            self._name(g.name, "global " + g.name, site)
            self.globals.append(g)
        for s in ss:
            self._name(s.name, "secret " + s.name, site)
            if s.type is ValueType.INFER:
                raise Problem(site, "secret " + s.name + " needs a type")
            self.secrets.append(s)
        for c in cs:
            self._name(c.name, "claim " + c.name, site)
            if not c.claim or utf16_length(c.claim) > 256:
                raise Problem(site, "claim " + c.name + " needs a claim of 1-256 characters")
            if c.type is ValueType.INFER:
                raise Problem(site, "claim " + c.name + " needs a type")
            self.claims.append(c)

    def merge(self, other: "_Variables") -> None:
        for k, v in other.names.items():
            self.names.setdefault(k, v)
        self.globals.extend(other.globals)
        self.secrets.extend(other.secrets)
        self.claims.extend(other.claims)

    def _name(self, name: str, what: str, site: Site) -> None:
        variable(name, what, site)
        key = name.lower()
        prior = self.names.get(key)
        self.names[key] = what
        if prior is not None:
            raise Problem(site, what + " and " + prior + " have the same name, ignoring case")

    def to_json(self) -> Dict[str, Any]:
        def by_name(items):
            return sorted(items, key=lambda x: utf16_key(_nfc(x.name)))

        gs = []
        for x in by_name(self.globals):
            v: Dict[str, Any] = {"name": x.name, "type": x.type.value, "required": x.required,
                                 "sensitive": x.sensitive}
            if x.description:
                v["description"] = x.description
            v.update(global_constraints(x, None))
            gs.append(v)
        ss = [{"name": x.name, "type": x.type.value, "scope": x.scope.value} for x in by_name(self.secrets)]
        cs = [{"name": x.name, "claim": x.claim, "type": x.type.value, "required": x.required}
              for x in by_name(self.claims)]
        return {"globals": gs, "secrets": ss, "claims": cs}


def _nfc(s: str) -> str:
    from ..jcs import nfc
    return nfc(s)


def global_constraints(g: Global, site: Optional[Site]) -> Dict[str, Any]:
    """The constraint members of a global; INT and LONG bounds are not materialised."""
    site = site or Site.of(g, g.name)
    if g.type is ValueType.INFER:
        raise Problem(site, "global " + g.name + " needs a type")
    if g.type is ValueType.ENUM and not g.enum_values:
        raise Problem(site, "global " + g.name + " of type ENUM needs enum_values")
    if utf16_length(g.description) > 1024:
        raise Problem(site, "global " + g.name + " description is longer than 1024 characters")
    out: Dict[str, Any] = {}
    if g.type is ValueType.ENUM:
        out["enum"] = list(g.enum_values)
    S.constraints(out, g.type, S.Spec(g), site, False)
    out.pop("x-toolgate-sensitive", None)
    return out


def variable(name: str, what: str, site: Optional[Site]) -> None:
    if not _full(VARIABLE, name):
        raise Problem(site, what + " " + str(name) + " does not match " + VARIABLE.pattern)


def declaration_id(value: str, what: str, site: Optional[Site]) -> None:
    if not _full(DECLARATION_ID, value):
        raise Problem(site, what + " " + str(value) + " does not match " + DECLARATION_ID.pattern)


def length(value: str, lo: int, hi: int, what: str, site: Optional[Site]) -> None:
    n = utf16_length(value)
    if n < lo or n > hi:
        raise Problem(site, "%s must be %d-%d characters" % (what, lo, hi))


def value_range(value: int, lo: int, hi: int, what: str, site: Optional[Site]) -> None:
    if value < lo or value > hi:
        raise Problem(site, "%s must be %d..%d" % (what, lo, hi))


def port(value: int, site: Optional[Site]) -> None:
    value_range(value, 1, 65535, "port", site)


def unique(values: Sequence[str], what: str, site: Optional[Site]) -> List[str]:
    seen: Set[str] = set()
    for v in values:
        if v in seen:
            raise Problem(site, what + " " + str(v) + " is listed twice")
        seen.add(v)
    return list(values)


def reserved(value: str, what: str, site: Optional[Site]) -> None:
    for prefix in RESERVED:
        if value.startswith(prefix) or value == prefix[:-1]:
            raise Problem(site, what + " " + value + " uses the reserved prefix " + prefix)


def _attached(target: Any) -> List[Declaration]:
    d = getattr(target, "__dict__", {})
    return list(d.get(ATTACHED_ATTRIBUTE, []))


def _python_default(value: Any) -> str:
    from ..declarations import _text
    return _text(value)


class _Builder:
    def __init__(self, modules: List[types.ModuleType], analyse: bool) -> None:
        self.modules = modules
        self.by_name = {m.__name__: m for m in modules}
        self.do_analyse = analyse
        self.problems: List[Problem] = []
        self.declarations: List[Declaration] = []
        seen: Set[int] = set()
        for m in modules:
            for d in getattr(m, REGISTRY_ATTRIBUTE, []):
                if id(d) not in seen:
                    seen.add(id(d))
                    self.declarations.append(d)
        self.destinations: Dict[str, destination] = {}
        self.auths: Dict[str, http_auth] = {}
        self.routes: Dict[str, http_route] = {}
        self.tcps: Dict[str, tcp_destination] = {}
        self.tools: Dict[str, Handler] = {}
        self.reconcilers: Dict[str, str] = {}
        self.parameters: Dict[str, List[str]] = {}
        self.examples: Dict[str, List[Dict[str, Any]]] = {}
        self.data_schemas: Dict[str, Dict[str, str]] = {}
        self.result = Build()

    # -- driver ---------------------------------------------------------------------------------------------

    def guard(self, step: Callable[[], Any]) -> None:
        try:
            step()
        except Problem as p:
            self.problems.append(p)

    def fail_if_problems(self) -> None:
        if self.problems:
            raise BuildFailed(self.problems)

    def run(self) -> Build:
        try:
            self.package_declaration()
        except Problem as p:
            raise BuildFailed([p])
        self.placement()
        self.network()
        for d in self.declarations:
            if isinstance(d, ToolDeclaration):
                self.guard(lambda d=d: self.register(d))
        for d in self.declarations:
            if isinstance(d, reconcile):
                self.guard(lambda d=d: self.reconciler(d))
        self.fail_if_problems()
        for name in sorted(self.tools, key=utf16_key):
            def one(name=name):
                self.result.descriptors[name] = self.descriptor(name, self.tools[name])
                self.result.handlers[name] = self.tools[name]
            self.guard(one)
        self.fail_if_problems()
        if self.do_analyse:
            from .analysis import analyse
            self.problems.extend(analyse(self.result.handlers, self.routes, self.result.facts, self.modules))
            self.fail_if_problems()
        self.accessors()
        self.fail_if_problems()
        self.write()
        return self.result

    # -- package and placement ------------------------------------------------------------------------------

    def package_declaration(self) -> None:
        declared = [d for d in self.declarations if isinstance(d, ToolPackageDeclaration)]
        if len(declared) != 1:
            at = declared[0] if declared else next((d for d in self.declarations if isinstance(d, ToolDeclaration)),
                                                   None)
            raise Problem(None if at is None else Site.of(at),
                          "exactly one tool_package is required per package; found %d" % len(declared))
        pkg = declared[0]
        site = Site.of(pkg)
        if not _full(PACKAGE_ID, pkg.id):
            raise Problem(site, "package id " + str(pkg.id) + " does not match " + PACKAGE_ID.pattern)
        reserved(pkg.id, "package id", site)
        if not pkg.name.strip():
            raise Problem(site, "package name is required")
        if not pkg.publisher.strip():
            raise Problem(site, "package publisher is required")
        self.package = pkg
        self.result.package = pkg

    def placement(self) -> None:
        """Each declaration stands where python.md allows it."""
        for d in self.declarations:
            site = Site.of(d)
            target = d.target
            if isinstance(d, ToolDeclaration) or isinstance(d, ToolPackageDeclaration):
                continue
            if d.embedded:
                continue
            if target is None:
                if not d.on_class or isinstance(d, tool_variables):
                    self.problems.append(Problem(site, d.kind + " decorates a tool handler"))
                continue
            if isinstance(target, type):
                if not d.on_class:
                    self.problems.append(Problem(site, d.kind + " decorates a tool handler, not a class"))
            elif not d.on_handler:
                self.problems.append(Problem(site, d.kind + " decorates a class or stands at module level"))
            elif not any(isinstance(x, ToolDeclaration) for x in _attached(target)):
                self.problems.append(Problem(site, d.kind + " is on a function that is not a server_tool or"
                                                        " client_tool"))

    # -- network --------------------------------------------------------------------------------------------

    def network(self) -> None:
        kinds = ((destination, self.destinations, "destination"), (http_auth, self.auths, "auth"),
                 (http_route, self.routes, "route"), (tcp_destination, self.tcps, "TCP destination"))
        for d in self.declarations:
            for cls, table, kind in kinds:
                if isinstance(d, cls):
                    self.guard(lambda d=d, table=table, kind=kind: self.put(table, d, kind))
        for r in sorted(self.routes.values(), key=lambda r: utf16_key(r.id)):
            self.guard(lambda r=r: self.route(r))
        for d in sorted(self.destinations.values(), key=lambda x: utf16_key(x.id)):
            def check_destination(d=d):
                variable(d.host_setting, "host_setting of destination " + d.id, Site.of(d))
                port(d.port, Site.of(d))
            self.guard(check_destination)
        for t in sorted(self.tcps.values(), key=lambda x: utf16_key(x.id)):
            def check_tcp(t=t):
                variable(t.host_setting, "host_setting of TCP destination " + t.id, Site.of(t))
                port(t.port, Site.of(t))
            self.guard(check_tcp)
        for a in sorted(self.auths.values(), key=lambda x: utf16_key(x.id)):
            def check_auth(a=a):
                site = Site.of(a)
                if a.header and not _full(HEADER, a.header):
                    raise Problem(site, "auth " + a.id + " header is not a header name")
                if a.secret:
                    variable(a.secret, "secret of auth " + a.id, site)
                if a.connection:
                    declaration_id(a.connection, "connection", site)
            self.guard(check_auth)

    def put(self, table: Dict[str, Any], d: Any, kind: str) -> None:
        site = Site.of(d)
        declaration_id(d.id, kind + " id", site)
        if d.id in table:
            raise Problem(site, kind + " id " + d.id + " is declared twice in the package")
        table[d.id] = d

    def route(self, r: http_route) -> None:
        site = Site.of(r)
        if r.destination not in self.destinations:
            raise Problem(site, "route " + r.id + " names undeclared destination " + r.destination)
        if r.auth and r.auth not in self.auths:
            raise Problem(site, "route " + r.id + " names undeclared auth " + r.auth)
        self.path_template(r, site)
        auth_header = self.auths[r.auth].header.lower() if r.auth else ""
        for h in r.headers:
            lower = h.lower()
            if not _full(HEADER, h):
                raise Problem(site, "route " + r.id + " header " + h + " is not a header name")
            if lower in FORBIDDEN_HEADERS or lower.startswith("x-forwarded-") or lower == auth_header:
                raise Problem(site, "route " + r.id + " may not allow header " + h)
        unique(r.query, "query name", site)
        unique(r.headers, "header", site)
        unique(r.request_content_types, "content type", site)
        value_range(r.max_request_bytes, 0, 1 << 30, "max_request_bytes", site)
        value_range(r.max_response_bytes, 0, 1 << 30, "max_response_bytes", site)
        value_range(r.timeout_ms, 1, 900_000, "timeout_ms", site)

    def path_template(self, r: http_route, site: Site) -> None:
        """RFC 6570 level 1 with {name:regex}: every variable needs a pattern, and .* and .+ are refused."""
        path = r.path
        if not path.startswith("/"):
            raise Problem(site, "route " + r.id + " path must start with /")
        names: Set[str] = set()
        i = 0
        while i < len(path):
            c = path[i]
            if c == "}":
                raise Problem(site, "route " + r.id + " path has an unmatched }")
            if c != "{":
                i += 1
                continue
            depth = 1
            j = i + 1
            while j < len(path) and depth > 0:
                if path[j] == "{":
                    depth += 1
                if path[j] == "}":
                    depth -= 1
                j += 1
            if depth != 0:
                raise Problem(site, "route " + r.id + " path has an unmatched {")
            body = path[i + 1:j - 1]
            colon = body.find(":")
            name = body if colon < 0 else body[:colon]
            variable(name, "path variable of route " + r.id, site)
            if name in names:
                raise Problem(site, "route " + r.id + " repeats path variable " + name)
            names.add(name)
            if colon < 0:
                raise Problem(site, "route " + r.id + " path variable " + name + " needs a pattern, {" + name
                              + ":regex}")
            regex = body[colon + 1:]
            if regex in (".*", ".+"):
                raise Problem(site, "route " + r.id + " path variable " + name + " may not use " + regex)
            try:
                re.compile(regex)
            except re.error:
                raise Problem(site, "route " + r.id + " path variable " + name + " pattern does not compile")
            i = j

    # -- tools ----------------------------------------------------------------------------------------------

    def register(self, d: ToolDeclaration) -> None:
        site = Site.of(d, d.name)
        if d.handler is None:
            raise Problem(site, d.kind + " is not applied to a handler")
        kinds = [x for x in _attached(d.handler) if isinstance(x, ToolDeclaration)]
        if len(kinds) > 1:
            if kinds[0] is d:
                raise Problem(site, "a handler is either server_tool or client_tool, once")
            return
        name = d.name
        if not _full(LOCAL_NAME, name):
            raise Problem(site, "tool name " + str(name) + " does not match " + LOCAL_NAME.pattern)
        reserved(name, "tool name", site)
        if name in self.tools:
            raise Problem(site, "tool " + name + " is declared twice")
        self.tools[name] = self.handler_shape(d, site)

    def handler_shape(self, d: ToolDeclaration, site: Site) -> Handler:
        fn = d.handler
        module = self.by_name.get(getattr(fn, "__module__", None)) or sys.modules.get(getattr(fn, "__module__", ""))
        qualname = getattr(fn, "__qualname__", "")
        shape = "a handler must be a module-level function or an instance method of a module-level class"
        if module is None or "<locals>" in qualname or not qualname:
            raise Problem(site, shape)
        parts = qualname.split(".")
        owner: Any = None
        if len(parts) == 1:
            if getattr(module, parts[0], None) is not fn:
                raise Problem(site, shape + "; " + qualname + " is not reachable as " + module.__name__ + "."
                              + qualname)
        else:
            owner = module
            for p in parts[:-1]:
                owner = getattr(owner, p, None)
                if not isinstance(owner, type):
                    raise Problem(site, shape)
            static = inspect.getattr_static(owner, parts[-1], None)
            if isinstance(static, (staticmethod, classmethod)) or static is not fn:
                raise Problem(site, shape)
            if inspect.isabstract(owner):
                raise Problem(site, "a handler must live in a concrete class; " + owner.__qualname__ + " is abstract")
            try:
                signature = inspect.signature(owner)
                signature.bind()
            except (TypeError, ValueError):
                raise Problem(site, "class " + owner.__qualname__ + " holds tools, so it must be constructible with"
                                    " no arguments")
        return Handler(d, module, qualname, owner)

    def reconciler(self, r: reconcile) -> None:
        site = Site.of(r)
        target = r.target
        tool = next((x for x in _attached(target) if isinstance(x, ToolDeclaration)), None) if target else None
        if tool is None:
            raise Problem(site, "a reconcile handler must also be a server_tool or client_tool")
        if r.tool not in self.tools:
            raise Problem(site, "reconcile names unknown tool " + r.tool)
        if r.tool == tool.name:
            raise Problem(site, "a tool cannot reconcile itself")
        if r.tool in self.reconcilers:
            raise Problem(site, "tool " + r.tool + " has two reconcile handlers")
        self.reconcilers[r.tool] = tool.name

    # -- descriptor -----------------------------------------------------------------------------------------

    def descriptor(self, name: str, h: Handler) -> Dict[str, Any]:
        t = h.declaration
        site = h.site
        is_client = isinstance(t, client_tool)
        pkg = self.package
        assert pkg is not None

        tool_id = pkg.id + "/" + name
        if utf16_length(tool_id) > 128 or not _full(TOOL_ID, tool_id):
            raise Problem(site, "toolId " + tool_id + " is longer than 128 characters or not a valid id")
        alias = pkg.id[pkg.id.rfind(".") + 1:]
        mcp_name = alias + "." + name
        if not _full(MCP_NAME, mcp_name):
            raise Problem(site, "mcpName " + mcp_name + " is longer than 128 characters")
        if (not t.title) == (not t.text):
            raise Problem(site, "tool " + name + " must set exactly one of title and text")
        title = t.title or t.text
        length(title, 1, 100, "title", site)
        length(t.description, 1, 2048, "description", site)
        if utf16_length(t.use_when) > 512:
            raise Problem(site, "use_when is longer than 512 characters")

        attached = _attached(h.function)
        grouped = [x for x in attached if isinstance(x, tool_variables)]
        if len(grouped) > 1:
            raise Problem(site, "a handler takes at most one tool_variables")
        group = grouped[0] if grouped else None
        schemas = S.Schemas()
        properties: Dict[str, Any] = {}
        required: List[str] = []
        parameters: List[str] = []
        context_name = self.inputs(h, group, schemas, properties, required, parameters)

        d: Dict[str, Any] = {
            "descriptorGeneration": GENERATION,
            "toolId": tool_id,
            "mcpName": mcp_name,
            "kind": "CLIENT" if is_client else "SERVER",
            "title": title,
            "description": t.description,
        }
        if t.use_when:
            d["useWhen"] = t.use_when
        d["inputSchema"] = schemas.inputs(properties, required)
        output = self.output(h)
        if output is not None:
            d["outputSchema"] = output
        d["errors"] = self.errors(t.errors, site)
        open_world = {OpenWorld.TRUE: True, OpenWorld.FALSE: False}.get(t.open_world, not is_client and bool(t.routes))
        d["effect"] = {"safety": t.effect.value, "destructive": t.destructive, "openWorld": open_world}
        d["businessKey"] = self.business_key(t, attached, set(properties), site)
        d["execution"] = self.execution(t, site)
        if name in self.reconcilers:
            d["reconcileTool"] = self.reconcilers[name]
        if is_client:
            if not t.tiers:
                raise Problem(site, "a client tool needs at least one tier")
            d["tiers"] = sorted_utf16(unique([x.value for x in t.tiers], "tier", site))

        route_ids = sorted(set(unique(list(t.routes), "route", site)), key=utf16_key)
        tcp_ids = sorted(set(unique(list(t.tcp), "TCP destination", site)), key=utf16_key)
        route_list = []
        destination_ids: Set[str] = set()
        auth_ids: Set[str] = set()
        for rid in route_ids:
            r = self.routes.get(rid)
            if r is None:
                raise Problem(site, "tool " + name + " lists undeclared route " + rid)
            route_list.append(self.route_json(r))
            destination_ids.add(r.destination)
            if r.auth:
                auth_ids.add(r.auth)
        d["routes"] = route_list
        d["destinations"] = [self.destination_json(self.destinations[i]) for i in sorted(destination_ids, key=utf16_key)]
        d["httpAuth"] = [self.auth_json(self.auths[i]) for i in sorted(auth_ids, key=utf16_key)]
        tcp_list = []
        for tid in tcp_ids:
            tcp = self.tcps.get(tid)
            if tcp is None:
                raise Problem(site, "tool " + name + " lists undeclared TCP destination " + tid)
            tcp_list.append({"id": tid, "hostSetting": tcp.host_setting, "port": tcp.port})
        d["tcp"] = tcp_list

        variables = self.variables(h, group, list(properties), is_client)
        for auth_id in sorted(auth_ids, key=utf16_key):
            a = self.auths[auth_id]
            if a.secret and not any(s.name == a.secret for s in variables.secrets):
                raise Problem(site, "auth " + auth_id + " uses secret " + a.secret + ", which tool " + name
                              + " does not declare")
        if is_client and self.auth_next_to(h):
            raise Problem(site, "client tool " + name + " cannot be declared next to http_auth")
        d["variables"] = variables.to_json()

        permissions: Set[str] = set()
        if route_ids:
            permissions.add("HTTP_ROUTE")
        if tcp_ids:
            permissions.update(("TCP", "CREDENTIALS_IN_SANDBOX"))
        for auth_id in auth_ids:
            kind = self.auths[auth_id].type
            if kind is AuthType.CUSTOM:
                permissions.add("CREDENTIALS_IN_SANDBOX")
            if kind is AuthType.TOKEN_EXCHANGE:
                permissions.add("TOKEN_EXCHANGE")
            if kind is AuthType.CONNECTION:
                permissions.add("CONNECTION")
        if any(s.scope is SecretScope.USER for s in variables.secrets):
            permissions.add("USER_SECRET")
        d["permissions"] = sorted_utf16(permissions)

        for toolset in t.toolsets:
            length(toolset, 1, 64, "toolset", site)
        d["toolsets"] = sorted_utf16(unique(list(t.toolsets), "toolset", site))

        examples = [x for x in attached if isinstance(x, example)]
        if len(examples) > 8:
            raise Problem(site, "tool %s declares %d examples; at most 8 are allowed" % (name, len(examples)))
        entries = []
        for e in examples:
            esite = Site.of(e)
            if not e.arguments.strip():
                raise Problem(esite, "an example needs arguments")
            try:
                parsed = json.loads(e.arguments)
            except ValueError:
                parsed = None
            if not isinstance(parsed, dict):
                raise Problem(esite, "example arguments must be a JSON object")
            if e.expected_output and e.expected_error:
                raise Problem(esite, "an example expects either an output or an error")
            # Examples are not part of the descriptor; the index carries them for check and the harness.
            entry: Dict[str, Any] = {"arguments": e.arguments}
            if e.expected_output:
                entry["expectedOutput"] = e.expected_output
            if e.expected_error:
                entry["expectedError"] = e.expected_error
            if e.exchanges:
                entry["exchanges"] = e.exchanges
            entries.append(entry)
        self.examples[name] = entries
        self.parameters[name] = parameters
        input_types = {k: S.value_type_of(v) for k, v in properties.items() if isinstance(v, dict)}
        self.result.facts[name] = Facts(
            name, set(route_ids), input_types,
            {g.name: g.type.value for g in variables.globals},
            {s.name: s.type.value for s in variables.secrets},
            {c.name: c.type.value for c in variables.claims},
            {e.code for e in t.errors}, context_name)
        self.data_schemas[name] = {e.code: e.data_schema for e in t.errors if e.data_schema}
        return d

    def auth_next_to(self, h: Handler) -> bool:
        """Whether an http_auth is declared on the handler's class or at the top of its module."""
        if h.owner is not None and any(isinstance(x, http_auth) for x in _attached(h.owner)):
            return True
        return any(isinstance(x, http_auth) and x.target is None and x.module == h.module.__name__
                   for x in self.declarations)

    def inputs(self, h: Handler, group: Optional[tool_variables], schemas: S.Schemas, properties: Dict[str, Any],
               required: List[str], parameters: List[str]) -> Optional[str]:
        fn = h.function
        site = h.site
        try:
            signature = inspect.signature(fn)
        except (TypeError, ValueError) as e:
            raise Problem(site, "cannot read the signature of the handler: " + str(e))
        found = S.hints(fn, site)
        params = list(signature.parameters.values())
        if h.owner is not None:
            if not params or params[0].kind not in (params[0].POSITIONAL_ONLY, params[0].POSITIONAL_OR_KEYWORD):
                raise Problem(site, "a method handler takes self first")
            params = params[1:]
        grouped_inputs = group is not None and bool(group.inputs)
        context_name = None
        for p in params:
            psite = site.child(p.name)
            hint = found.get(p.name, p.annotation if p.annotation is not p.empty else None)
            bare = S.unwrap(hint, psite)[0] if hint is not None else None
            if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
                raise Problem(psite, "a handler takes no *args or **kwargs")
            if p.kind is p.KEYWORD_ONLY:
                if p.name != "context":
                    raise Problem(psite, "keyword-only parameter " + p.name + ": only context is keyword-only")
                context_name = p.name
                continue
            if bare is ToolContext or p.name == "context":
                raise Problem(psite, "the ToolContext parameter must be keyword-only: *, context: ToolContext")
            if grouped_inputs:
                raise Problem(psite, "with tool_variables inputs the handler takes only the keyword-only context")
            if hint is None:
                raise Problem(psite, "parameter " + p.name + " needs a type: Annotated[T, Param(description=...)]")
            tp, metadata, optional = S.unwrap(hint, psite)
            param = S.param_of(metadata, psite)
            if param is None:
                raise Problem(psite, "parameter " + p.name + " needs Param: Annotated[T, Param(description=...)]")
            name = param.name or p.name
            spec = S.Spec(param, name)
            if p.default is not p.empty and p.default is not None:
                text = _python_default(p.default)
                if spec.default_value and spec.default_value != text:
                    raise Problem(psite, "input " + name + " has one default in Python and another in Param")
                spec.default_value = text
            if p.default is None:
                optional = True
            self.input(name, spec, tp, schemas, psite.child(name), properties, required, optional)
            parameters.append(name)
        if context_name is not None:
            parameters.append("$context")
        if grouped_inputs:
            for v in group.inputs:
                vsite = Site.of(group, v.name)
                if v.type in (ValueType.INFER, ValueType.OBJECT, ValueType.ARRAY):
                    raise Problem(vsite, "grouped input " + v.name + " needs a scalar type; use the typed form for"
                                         " objects and arrays")
                self.input(v.name, S.Spec(v), S.scalar_type(v.type), schemas, vsite, properties, required, False)
        return context_name

    def input(self, name: str, spec: S.Spec, tp: Any, schemas: S.Schemas, site: Site, properties: Dict[str, Any],
              required: List[str], optional: bool) -> None:
        variable(name, "input name", site)
        if not spec.description:
            raise Problem(site, "input " + name + " needs a description")
        if utf16_length(spec.description) > 1024:
            raise Problem(site, "input " + name + " description is longer than 1024 characters")
        if name in properties:
            raise Problem(site, "input " + name + " is declared twice")
        properties[name] = schemas.schema(tp, spec, site)
        if spec.required and not spec.default_value and not optional:
            required.append(name)

    def output(self, h: Handler) -> Optional[Dict[str, Any]]:
        site = h.site
        found = S.hints(h.function, site)
        tp = found.get("return")
        returns = "a handler returns a dataclass, a TypedDict, a Pydantic model, a dict or ToolResult"
        if tp is None or tp is type(None):
            raise Problem(site, returns + "; annotate its return type")
        origin = typing.get_origin(tp)
        import collections.abc as abc
        if origin in (abc.Awaitable, abc.Coroutine):
            args = typing.get_args(tp)
            if not args:
                raise Problem(site, "Awaitable needs its result type")
            tp = args[-1]
        if tp is ToolResult:
            return None
        return S.Schemas().output(tp, site.child("return"))

    def errors(self, errors: Sequence[ErrorDecl], site: Site) -> List[Dict[str, Any]]:
        by_code: Dict[str, Dict[str, Any]] = {}
        for e in errors:
            if not isinstance(e, ErrorDecl):
                raise Problem(site, "errors lists ErrorDecl values")
            if not _full(ERROR_CODE, e.code):
                raise Problem(site, "error code " + str(e.code) + " does not match " + ERROR_CODE.pattern)
            length(e.description, 1, 1024, "error description", site)
            if e.code in by_code:
                raise Problem(site, "error code " + e.code + " is declared twice")
            if e.data_schema:
                try:
                    if not isinstance(json.loads(e.data_schema), dict):
                        raise ValueError
                except ValueError:
                    raise Problem(site, "error " + e.code + " data_schema is not a JSON object")
            by_code[e.code] = {"code": e.code, "description": e.description, "retryable": e.retryable}
        return [by_code[c] for c in sorted(by_code, key=utf16_key)]

    def business_key(self, t: ToolDeclaration, attached: List[Declaration], inputs: Set[str],
                     site: Site) -> Dict[str, Any]:
        out: Dict[str, Any] = {
            "required": {BusinessKey.REQUIRED: True, BusinessKey.NOT_REQUIRED: False}.get(
                t.requires_business_key, t.effect is EffectSafety.NON_IDEMPOTENT and t.destructive),
            "namespace": t.business_key_namespace.value,
        }
        if t.business_key_argument:
            if t.business_key_argument not in inputs:
                raise Problem(site, "business_key_argument " + t.business_key_argument + " is not an input of the tool")
            out["argument"] = t.business_key_argument
        idems = [x for x in attached if isinstance(x, idempotency)]
        if len(idems) > 1:
            raise Problem(site, "a handler takes at most one idempotency")
        if idems:
            if not _full(HEADER, idems[0].header):
                raise Problem(site, "idempotency header is not a header name")
            out["idempotencyHeader"] = idems[0].header
            out["idempotencyScope"] = idems[0].scope.value
        return out

    def execution(self, t: ToolDeclaration, site: Site) -> Dict[str, Any]:
        ceiling = 900_000 if t.async_ else 60_000
        duration = (900_000 if t.async_ else 30_000) if t.max_duration_ms == -1 else t.max_duration_ms
        if duration < 1 or duration > ceiling:
            raise Problem(site, "max_duration_ms must be 1..%d%s" % (
                ceiling, " for async tools" if t.async_ else " for sync tools; make the tool async for longer work"))
        if t.cancel_if_abandoned and not t.async_:
            raise Problem(site, "cancel_if_abandoned applies to async tools only")
        out: Dict[str, Any] = {"async": t.async_, "maxDurationMs": duration, "stateless": t.stateless}
        if t.max_concurrency != -1:
            value_range(t.max_concurrency, 1, 1_048_576, "max_concurrency", site)
            out["maxConcurrency"] = t.max_concurrency
        out["cancelIfAbandoned"] = t.cancel_if_abandoned
        return out

    @staticmethod
    def route_json(r: http_route) -> Dict[str, Any]:
        out = {"id": r.id, "destination": r.destination, "method": r.method.value, "path": r.path,
               "query": sorted_utf16(r.query), "headers": sorted_utf16(r.headers),
               "requestContentTypes": sorted_utf16(r.request_content_types), "maxRequestBytes": r.max_request_bytes,
               "maxResponseBytes": r.max_response_bytes, "streaming": r.streaming.value, "timeoutMs": r.timeout_ms}
        if r.auth:
            out["auth"] = r.auth
        return out

    @staticmethod
    def destination_json(d: destination) -> Dict[str, Any]:
        out: Dict[str, Any] = {"id": d.id, "hostSetting": d.host_setting, "port": d.port, "spkiPins": list(d.spki_pins)}
        if d.ca_bundle:
            out["caBundle"] = d.ca_bundle
        return out

    @staticmethod
    def auth_json(a: http_auth) -> Dict[str, Any]:
        out: Dict[str, Any] = {"id": a.id, "type": a.type.value}
        if a.header:
            out["header"] = a.header
        if a.secret:
            out["secret"] = a.secret
        if a.connection:
            out["connection"] = a.connection
        out["scopes"] = list(a.scopes)
        if a.audience:
            out["audience"] = a.audience
        return out

    def scope_declarations(self, kind_filter: Callable[[Declaration], bool], scope: Any) -> List[Declaration]:
        """Declarations of one scope: unattached ones of a module (scope is a module), or a class's own."""
        if isinstance(scope, types.ModuleType):
            return [d for d in self.declarations if kind_filter(d) and d.target is None and not d.embedded
                    and d.module == scope.__name__]
        return [d for d in _attached(scope) if kind_filter(d) and not d.embedded]

    def variables(self, h: Handler, group: Optional[tool_variables], inputs: List[str],
                  is_client: bool) -> _Variables:
        """Globals, secrets and claims in scope: the package module, the handler's module and class, then the
        handler."""
        assert self.package is not None
        scopes: List[Any] = []
        for s in (self.by_name.get(self.package.module) or sys.modules.get(self.package.module), h.module, h.owner):
            if s is not None and all(s is not x for x in scopes):
                scopes.append(s)
        scope = _Variables()
        for s in scopes:
            ds = self.scope_declarations(lambda d: isinstance(d, (Global, Secret, JwtClaim)), s)
            site = Site.of(ds[0]) if ds else h.site
            scope.add(site, [d for d in ds if isinstance(d, Global)], [d for d in ds if isinstance(d, Secret)],
                      [d for d in ds if isinstance(d, JwtClaim)])
        tool = _Variables()
        attached = _attached(h.function)
        tool.add(h.site, [d for d in attached if isinstance(d, Global)], [d for d in attached if isinstance(d, Secret)],
                 [d for d in attached if isinstance(d, JwtClaim)])
        if group is not None:
            tool.add(h.site, group.globals, group.secrets, group.jwt)
        for n in tool.names:
            if n in scope.names:
                raise Problem(h.site, "tool-level " + tool.names[n] + " cannot override the declaration "
                              + scope.names[n] + " of its package, module or class")
        every = _Variables()
        every.merge(scope)
        every.merge(tool)
        seen = dict(every.names)
        for i in inputs:
            key = i.lower()
            prior = seen.get(key)
            seen[key] = "input " + i
            if prior is not None:
                raise Problem(h.site, "input " + i + " and " + prior + " have the same name, ignoring case")
        if is_client and every.secrets:
            raise Problem(h.site, "client tool cannot use secrets; found " + every.secrets[0].name)
        for g in every.globals:
            global_constraints(g, Site.of(g, g.name))
        return every

    # -- accessors and output -------------------------------------------------------------------------------

    def accessors(self) -> None:
        """declared(context) views: one per class or module that holds tools, over the union of its tools'
        declarations; two names with the same attribute are an error, as in the Java processor."""
        by_scope: Dict[str, List[Facts]] = {}
        for name, h in self.result.handlers.items():
            by_scope.setdefault(h.scope_name, []).append(self.result.facts[name])
        for scope_name, facts in by_scope.items():
            site = self.result.handlers[facts[0].tool].site

            def one(scope_name=scope_name, facts=facts, site=site):
                for kind, what in (("globals", "global"), ("secrets", "secret"), ("claims", "claim")):
                    union: Dict[str, str] = {}
                    for f in facts:
                        for k, v in getattr(f, kind).items():
                            prior = union.setdefault(k, v)
                            if prior != v:
                                raise Problem(site, what + " " + k + " is " + prior + " in one tool and " + v
                                              + " in another")
                    clash = accessors.collision(union)
                    if clash is not None:
                        raise Problem(site, what + " " + clash + " would have the same attribute in declared(context)"
                                      " for " + scope_name)
            self.guard(one)

    def write(self) -> None:
        pkg = self.package
        assert pkg is not None
        tools = []
        for name in sorted(self.result.descriptors, key=utf16_key):
            data = canonical(self.result.descriptors[name])
            self.result.bytes[name] = data
            h = self.result.handlers[name]
            entry: Dict[str, Any] = {
                "name": name,
                "toolDescriptorDigest": sha256(data),
                "handlerModule": h.module.__name__,
                "handlerQualname": h.qualname,
                "parameters": self.parameters[name],
            }
            if self.data_schemas[name]:
                entry["errorDataSchemas"] = self.data_schemas[name]
            if self.examples[name]:
                entry["examples"] = self.examples[name]
            tools.append(entry)
        index: Dict[str, Any] = {"descriptorGeneration": GENERATION, "id": pkg.id, "name": pkg.name,
                                 "publisher": pkg.publisher}
        if pkg.description:
            index["description"] = pkg.description
        index["tools"] = tools
        self.result.index = index
        self.result.index_bytes = canonical(index)
