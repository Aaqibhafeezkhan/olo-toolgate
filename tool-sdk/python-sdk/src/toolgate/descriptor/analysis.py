# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The build-time analyser of http.md section 3, over the Python AST.

It proves every URL-style convenience call (``context.http.get(destination, template)`` and its siblings): both
arguments are string literals, the call sits in a tool handler, and method, destination and template match exactly
one declared route that the handler's tool lists. Anything it cannot prove is a build error at the call site, as is
reflective access to the network API. It also checks the ``get_*`` calls with a literal name (the name must be
declared for the tool and the getter must read its type), and that a literal error code a handler raises is one
its tool declares. Python has no static types, so a receiver counts as the HTTP client when it is an ``.http``
attribute, a name bound to one, or a parameter annotated ``HttpClient``.
"""

from __future__ import annotations

import ast
import inspect
import re
import tokenize
import types
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

from .problems import Problem, Site

CONVENIENCE = ("get", "post", "put", "patch", "delete", "head")
NETWORK_TYPES = ("HttpClient", "RouteCall", "TcpConnector")
_VARIABLE = re.compile(r"\{([A-Za-z][A-Za-z0-9_]*)(:[^{}]*(\{[^{}]*\}[^{}]*)*)?\}")
_REFLECTION = ("getattr", "setattr", "delattr", "vars", "hasattr")

#: The value types each getter reads, exactly as the runtime's declared values accept them.
GETTERS = {
    "get_str": {"STRING", "ENUM", "URI", "DATE", "DATE_TIME", "DURATION"},
    "get_int": {"INT"},
    "get_long": {"INT", "LONG"},
    "get_number": {"INT", "LONG", "NUMBER"},
    "get_bool": {"BOOLEAN"},
    "get_date": {"DATE"},
    "get_datetime": {"DATE_TIME"},
    "get_duration": {"DURATION"},
    "get_object": {"OBJECT"},
    "get_list": {"ARRAY"},
    "get_bytes": {"BINARY"},
}
_RAISES = ("ToolBusinessError", "ToolRetryableError")


def without_patterns(template: str) -> str:
    return _VARIABLE.sub(lambda m: "{" + m.group(1) + "}", template)


class Route:
    def __init__(self, id: str, method: str, destination: str, template: str) -> None:
        self.id = id
        self.method = method
        self.destination = destination
        self.template = template


def _source(module: types.ModuleType) -> Optional[Tuple[str, str]]:
    path = getattr(module, "__file__", None)
    if path and path.endswith(".py"):
        try:
            with tokenize.open(path) as f:
                return path, f.read()
        except OSError:
            pass
    try:
        return path or module.__name__, inspect.getsource(module)
    except (OSError, TypeError):
        return None


def analyse(handlers: Dict[str, Any], routes: Dict[str, Any], facts: Dict[str, Any],
            modules: Optional[Iterable[types.ModuleType]] = None) -> List[Problem]:
    """Analyses the handlers' modules; returns the problems found."""
    declared = [Route(r.id, r.method.value, r.destination, without_patterns(r.path)) for r in routes.values()]
    by_key: Dict[Tuple[str, str], Any] = {}
    seen: Dict[str, types.ModuleType] = {}
    for name, h in handlers.items():
        by_key[(h.module.__name__, h.qualname)] = facts[name]
        seen.setdefault(h.module.__name__, h.module)
    for m in modules or ():
        seen.setdefault(m.__name__, m)
    problems: List[Problem] = []
    for module_name, module in seen.items():
        found = _source(module)
        if found is None:
            continue
        path, text = found
        try:
            tree = ast.parse(text, filename=path)
        except SyntaxError as e:  # pragma: no cover - the module imported, so it parses
            problems.append(Problem(Site(path, e.lineno or 0), "cannot parse the module: " + str(e)))
            continue
        _Scanner(path, module_name, declared, by_key, problems).visit(tree)
    return problems


def _annotation_names(node: Optional[ast.AST]) -> Set[str]:
    out: Set[str] = set()
    if node is None:
        return out
    for n in ast.walk(node):
        if isinstance(n, ast.Name):
            out.add(n.id)
        elif isinstance(n, ast.Attribute):
            out.add(n.attr)
        elif isinstance(n, ast.Constant) and isinstance(n.value, str):
            out.update(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", n.value))
    return out


class _Scope:
    """Per-function state: the names bound to the HTTP client or TCP connector."""

    def __init__(self, parent: Optional["_Scope"] = None) -> None:
        self.http: Set[str] = set(parent.http) if parent else set()
        self.tcp: Set[str] = set(parent.tcp) if parent else set()


class _Scanner(ast.NodeVisitor):
    def __init__(self, path: str, module: str, routes: List[Route], handlers: Dict[Tuple[str, str], Any],
                 problems: List[Problem]) -> None:
        self.path = path
        self.module = module
        self.routes = routes
        self.handlers = handlers
        self.problems = problems
        self.qualname: List[str] = []
        self.facts: Any = None
        self.scope = _Scope()
        self.annotations: Set[int] = set()

    def error(self, node: ast.AST, message: str) -> None:
        self.problems.append(Problem(Site(self.path, getattr(node, "lineno", 0)), message))

    # -- structure ------------------------------------------------------------------------------------------

    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        # A nested class body is not the handler's own code.
        outer, outer_scope = self.facts, self.scope
        self.facts = None
        self.qualname.append(node.name)
        for d in node.decorator_list:
            self.visit(d)
        for b in node.bases:
            self.visit(b)
        for s in node.body:
            self.visit(s)
        self.qualname.pop()
        self.facts, self.scope = outer, outer_scope

    def _function(self, node: Any) -> None:
        for d in node.decorator_list:
            self.visit(d)
        self.qualname.append(node.name)
        qualname = ".".join(self.qualname)
        outer, outer_scope = self.facts, self.scope
        handler = self.handlers.get((self.module, qualname))
        if handler is not None:
            self.facts = handler
        self.scope = _Scope(outer_scope)
        args = node.args
        for a in args.posonlyargs + args.args + args.kwonlyargs + ([args.vararg] if args.vararg else []) \
                + ([args.kwarg] if args.kwarg else []):
            names = _annotation_names(a.annotation)
            if "HttpClient" in names:
                self.scope.http.add(a.arg)
            if "TcpConnector" in names:
                self.scope.tcp.add(a.arg)
            self._skip(a.annotation)
        self._skip(node.returns)
        for d in args.defaults + [d for d in args.kw_defaults if d is not None]:
            self.visit(d)
        self.qualname.append("<locals>")
        for s in node.body:
            self.visit(s)
        self.qualname.pop()
        self.qualname.pop()
        self.facts, self.scope = outer, outer_scope

    visit_FunctionDef = _function
    visit_AsyncFunctionDef = _function

    def _skip(self, annotation: Optional[ast.AST]) -> None:
        if annotation is not None:
            for n in ast.walk(annotation):
                self.annotations.add(id(n))

    def visit_AnnAssign(self, node: ast.AnnAssign) -> None:
        self._skip(node.annotation)
        if node.value is not None:
            self._bind(node.target, node.value)
            self.visit(node.value)
        names = _annotation_names(node.annotation)
        if isinstance(node.target, ast.Name):
            if "HttpClient" in names:
                self.scope.http.add(node.target.id)
            if "TcpConnector" in names:
                self.scope.tcp.add(node.target.id)

    def visit_Assign(self, node: ast.Assign) -> None:
        for t in node.targets:
            self._bind(t, node.value)
        self.visit(node.value)
        for t in node.targets:
            self.visit(t)

    def _bind(self, target: ast.AST, value: ast.AST) -> None:
        if isinstance(target, ast.Name):
            if self._is_http(value):
                self.scope.http.add(target.id)
            else:
                self.scope.http.discard(target.id)
            if self._is_tcp(value):
                self.scope.tcp.add(target.id)
            else:
                self.scope.tcp.discard(target.id)

    def _is_http(self, node: ast.AST) -> bool:
        return (isinstance(node, ast.Attribute) and node.attr == "http") or \
            (isinstance(node, ast.Name) and node.id in self.scope.http)

    def _is_tcp(self, node: ast.AST) -> bool:
        return (isinstance(node, ast.Attribute) and node.attr == "tcp") or \
            (isinstance(node, ast.Name) and node.id in self.scope.tcp)

    # -- checks ---------------------------------------------------------------------------------------------

    def visit_Name(self, node: ast.Name) -> None:
        if node.id in NETWORK_TYPES and id(node) not in self.annotations and isinstance(node.ctx, ast.Load):
            self.error(node, "reflective access to " + node.id + " cannot be analysed; call the network API directly")

    def visit_Attribute(self, node: ast.Attribute) -> None:
        if node.attr in NETWORK_TYPES and id(node) not in self.annotations and isinstance(node.ctx, ast.Load):
            self.error(node, "reflective access to " + node.attr + " cannot be analysed; call the network API"
                             " directly")
            return
        if node.attr in ("__dict__", "__getattribute__") and (self._is_http(node.value) or self._is_tcp(node.value)):
            self.error(node, "reflective access to " + ("HttpClient" if self._is_http(node.value) else "TcpConnector")
                       + " cannot be analysed; call the network API directly")
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        func = node.func
        if isinstance(func, ast.Name) and func.id in _REFLECTION and node.args:
            target = node.args[0]
            if self._is_http(target) or self._is_tcp(target):
                self.error(node, "reflective access to " + ("HttpClient" if self._is_http(target) else "TcpConnector")
                           + " cannot be analysed; call the network API directly")
            elif self.facts is not None and isinstance(target, ast.Name) and target.id == self.facts.context:
                self.error(node, "reflective access to ToolContext cannot be analysed; call the network API directly")
        if isinstance(func, ast.Attribute):
            if func.attr in CONVENIENCE and self._is_http(func.value):
                self.convenience(node, func.attr.upper())
            elif func.attr in GETTERS and node.args and self.facts is not None:
                self.value(node, func)
        if self.facts is not None:
            called = func.id if isinstance(func, ast.Name) else func.attr if isinstance(func, ast.Attribute) else None
            if called in _RAISES and node.args:
                code = _literal(node.args[0])
                if code is not None and code not in self.facts.errors:
                    self.error(node, "tool " + self.facts.tool + " raises undeclared error code " + code
                               + "; declare it in errors")
        self.generic_visit(node)

    def convenience(self, call: ast.Call, method: str) -> None:
        args = list(call.args)
        names = {"destination_id": 0, "path_template": 1}
        for k in call.keywords:
            if k.arg in names:
                while len(args) <= names[k.arg]:
                    args.append(None)  # type: ignore[arg-type]
                args[names[k.arg]] = k.value
        if len(args) != 2 or len(call.args) + len(call.keywords) != 2:
            return
        destination = _literal(args[0])
        template = _literal(args[1])
        if destination is None or template is None:
            self.error(call, "a convenience call needs string literals for the destination and the path template,"
                             " so the route can be proven at build time; use http.route(id) instead")
            return
        if self.facts is None:
            self.error(call, "a convenience call outside a tool handler cannot be proven; make it in the handler or"
                             " use http.route(id)")
            return
        wanted = without_patterns(template)
        matches = [r for r in self.routes if r.method == method and r.destination == destination
                   and r.template == wanted]
        if not matches:
            candidates = ", ".join(r.id + " (" + r.method + " " + r.template + ")" for r in self.routes
                                   if r.destination == destination)
            self.error(call, method + " " + destination + " " + template + " matches no declared route"
                       + ("; destination " + destination + " has no routes" if not candidates
                          else "; candidates: " + candidates))
        elif len(matches) > 1:
            self.error(call, method + " " + destination + " " + template + " matches more than one route: "
                       + " and ".join(r.id for r in matches))
        elif matches[0].id not in self.facts.routes:
            self.error(call, method + " " + destination + " " + template + " is route " + matches[0].id
                       + ", which tool " + self.facts.tool + " does not list in routes")

    def value(self, call: ast.Call, func: ast.Attribute) -> None:
        name = _literal(call.args[0])
        if name is None:
            return
        receiver = func.value
        context = self.facts.context
        if isinstance(receiver, ast.Name) and context is not None and receiver.id == context:
            kind, declared = "input", self.facts.inputs
        elif isinstance(receiver, ast.Attribute) and isinstance(receiver.value, ast.Name) and context is not None \
                and receiver.value.id == context and receiver.attr in ("globals", "secrets", "jwt"):
            kind, declared = {"globals": ("global", self.facts.globals), "secrets": ("secret", self.facts.secrets),
                              "jwt": ("claim", self.facts.claims)}[receiver.attr]
        else:
            return
        value_type = declared.get(name)
        if value_type is None:
            self.error(call, "tool " + self.facts.tool + " declares no " + kind + " " + name
                       + ("" if not declared else "; declared: " + ", ".join(sorted(declared))))
        elif value_type not in GETTERS[func.attr]:
            self.error(call, kind + " " + name + " of tool " + self.facts.tool + " is " + value_type + "; "
                       + func.attr + " does not read it")


def _literal(node: Optional[ast.AST]) -> Optional[str]:
    return node.value if isinstance(node, ast.Constant) and isinstance(node.value, str) else None
