# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Pure JSON graph compilation. Executable implementations are installed code only."""
from collections import OrderedDict
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import threading
from typing import Annotated, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime

from .graph_api import RunContext, json_value
from .graph_packages import GraphRefused, schema_validator

COMPILER_DIGEST = 'sha256:' + hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
FEATURES = ('value', 'agent', 'model', 'tool', 'branch', 'parallel', 'join', 'loops')
NODE = re.compile(r'[a-z][a-z0-9_-]{0,47}')
_cache = OrderedDict()
_lock = threading.RLock()
MAX_CACHE = 128


def canonical(value):
    return json.dumps(json_value(value), sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()


def specification_digest(value):
    return 'sha256:' + hashlib.sha256(canonical(value)).hexdigest()


def pointer(state, path):
    if not isinstance(path, str) or not path.startswith('/') or len(path) > 1024:
        raise GraphRefused('JSON_GRAPH_POINTER')
    value = state
    for segment in path[1:].split('/'):
        if re.search(r'~(?![01])', segment):
            raise GraphRefused('JSON_GRAPH_POINTER')
        key = segment.replace('~1', '/').replace('~0', '~')
        if isinstance(value, dict) and key in value:
            value = value[key]
        elif isinstance(value, list) and re.fullmatch(r'0|[1-9][0-9]*', key) and int(key) < len(value):
            value = value[int(key)]
        else:
            raise GraphRefused('JSON_GRAPH_INPUT_UNAVAILABLE')
    return json_value(value)


def expression(value, state):
    """Bounded data expressions, never eval, import paths, templates or Python."""
    if isinstance(value, dict):
        if len(value) == 1:
            op, args = next(iter(value.items()))
            if op == '$path':
                return pointer(state, args)
            if op == '$literal':
                return json_value(args)
            if op in ('$eq', '$lt', '$and', '$or', '$add'):
                if not isinstance(args, list) or len(args) != 2:
                    raise GraphRefused('JSON_GRAPH_EXPRESSION')
                a, b = [expression(item, state) for item in args]
                if op == '$eq':
                    return type(a) is type(b) and a == b
                if op in ('$and', '$or') and type(a) is type(b) is bool:
                    return a and b if op == '$and' else a or b
                if op in ('$lt', '$add') and type(a) in (int, float) and type(b) in (int, float):
                    return a < b if op == '$lt' else json_value(a + b)
                raise GraphRefused('JSON_GRAPH_EXPRESSION')
        if any(key.startswith('$') for key in value):
            raise GraphRefused('JSON_GRAPH_EXPRESSION')
        return {key: expression(item, state) for key, item in value.items()}
    if isinstance(value, list):
        return [expression(item, state) for item in value]
    return value


def _validate_expression(value):
    if isinstance(value, dict):
        special = [key for key in value if key.startswith('$')]
        if special:
            if len(value) != 1 or special[0] not in ('$path', '$literal', '$eq', '$lt', '$and', '$or', '$add'):
                raise GraphRefused('JSON_GRAPH_EXPRESSION')
            op = special[0]
            if op == '$path':
                if (not isinstance(value[op], str) or not value[op].startswith('/') or len(value[op])>1024
                        or re.search(r'~(?![01])',value[op])):
                    raise GraphRefused('JSON_GRAPH_POINTER')
                return
            if op == '$literal':
                return
            if not isinstance(value[op], list) or len(value[op]) != 2:
                raise GraphRefused('JSON_GRAPH_EXPRESSION')
        for item in value.values():
            _validate_expression(item)
    elif isinstance(value, list):
        for item in value:
            _validate_expression(item)


def validate(spec):
    spec = json_value(spec, max_bytes=16384)
    if (not isinstance(spec, dict) or set(spec) != {'schemaVersion', 'nodes', 'edges', 'output', 'outputSchema'}
            or type(spec['schemaVersion']) is not int or spec['schemaVersion'] != 1):
        raise GraphRefused('JSON_GRAPH_SCHEMA')
    nodes, edges = spec['nodes'], spec['edges']
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 64 or not isinstance(edges, list) or not 1 <= len(edges) <= 128:
        raise GraphRefused('JSON_GRAPH_LIMIT')
    names = set()
    for node in nodes:
        if not isinstance(node, dict) or set(node) - {'id', 'kind', 'input', 'agentId', 'toolRef', 'resultMode', 'routes'}:
            raise GraphRefused('JSON_GRAPH_NODE')
        if not {'id', 'kind', 'input'} <= set(node) or not isinstance(node['id'], str) or not NODE.fullmatch(node['id']) or node['id'] in names:
            raise GraphRefused('JSON_GRAPH_NODE')
        names.add(node['id'])
        if not isinstance(node['input'], dict):
            raise GraphRefused('JSON_GRAPH_EXPRESSION')
        kind = node['kind']
        required = {'id', 'kind', 'input'}
        if kind in ('agent', 'model', 'tool'):
            required.add('agentId')
            if not isinstance(node.get('agentId'), str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}', node['agentId']):
                raise GraphRefused('JSON_GRAPH_AGENT')
        if kind == 'tool':
            required.add('toolRef')
            if not isinstance(node.get('toolRef'), str) or not re.fullmatch(r'[a-z0-9][a-z0-9._-]{0,127}/[a-z0-9][a-z0-9._-]{0,127}', node['toolRef']):
                raise GraphRefused('JSON_GRAPH_TOOL')
        if kind == 'branch':
            required.add('routes')
            if not isinstance(node.get('routes'), dict) or set(node['routes']) != {'true', 'false'}:
                raise GraphRefused('JSON_GRAPH_BRANCH')
        if kind not in ('value', 'agent', 'model', 'tool', 'branch'):
            raise GraphRefused('JSON_GRAPH_FEATURE_UNSUPPORTED')
        allowed = required | ({'resultMode'} if kind == 'agent' else set())
        if not required <= set(node) or set(node) - allowed or node.get('resultMode', 'MODEL') not in ('MODEL', 'CONTEXT'):
            raise GraphRefused('JSON_GRAPH_NODE')
        _validate_expression(node['input'])
    targets = names | {'END'}
    forward = {name: set() for name in names | {'START'}}
    reverse = {name: set() for name in targets}
    seen_edges = set()
    for edge in edges:
        if not isinstance(edge, dict) or set(edge) != {'from', 'to'} or not isinstance(edge['from'], list) or not edge['from']:
            raise GraphRefused('JSON_GRAPH_EDGE')
        if not isinstance(edge['to'], str) or edge['to'] not in targets:
            raise GraphRefused('JSON_GRAPH_EDGE')
        sources = edge['from']
        if any(not isinstance(item, str) or item not in forward for item in sources) or len(set(sources)) != len(sources):
            raise GraphRefused('JSON_GRAPH_EDGE')
        key = (tuple(sorted(sources)), edge['to'])
        if key in seen_edges or 'START' in sources and len(sources) != 1:
            raise GraphRefused('JSON_GRAPH_EDGE')
        seen_edges.add(key)
        for source in sources:
            forward[source].add(edge['to'])
            reverse[edge['to']].add(source)
    for node in nodes:
        if node['kind'] == 'branch':
            if forward[node['id']]:
                raise GraphRefused('JSON_GRAPH_BRANCH')
            for target in node['routes'].values():
                if not isinstance(target, str) or target not in targets:
                    raise GraphRefused('JSON_GRAPH_BRANCH')
                forward[node['id']].add(target)
                reverse[target].add(node['id'])
    def reachable(start, links):
        visited, pending = set(), [start]
        while pending:
            item = pending.pop()
            if item not in visited:
                visited.add(item)
                pending.extend(links.get(item, ()))
        return visited
    if not names <= reachable('START', forward) or not names <= reachable('END', reverse):
        raise GraphRefused('JSON_GRAPH_UNREACHABLE')
    if not isinstance(spec['output'], dict):
        raise GraphRefused('JSON_GRAPH_EXPRESSION')
    _validate_expression(spec['output'])
    schema_validator(spec['outputSchema'])
    return spec


def _merge_values(left, right):
    return json_value({**(left or {}), **right})


class State(TypedDict, total=False):
    message: str
    values: Annotated[dict, _merge_values]
    result: object


def _operation(declaration):
    # Capture a private JSON copy only. No run state, credentials or live clients.
    node = deepcopy(declaration)
    async def execute(state: State, runtime: Runtime[RunContext]):
        value = expression(node['input'], json_value(state))
        kind = node['kind']
        if kind == 'agent':
            value = await runtime.context.agents.invoke(node['agentId'], value, result_mode=node.get('resultMode', 'MODEL'))
        elif kind == 'model':
            if not isinstance(value, list):
                raise GraphRefused('JSON_GRAPH_MESSAGES')
            value = await runtime.context.models.invoke(node['agentId'], value)
        elif kind == 'tool':
            if not isinstance(value, dict):
                raise GraphRefused('JSON_GRAPH_ARGUMENTS')
            value = await runtime.context.tools.call(node['agentId'], node['toolRef'], value)
        elif kind == 'branch' and type(value) is not bool:
            raise GraphRefused('JSON_GRAPH_CONDITION')
        return {'values': {node['id']: json_value(value)}}
    return execute


class JsonGraphBuilder(StateGraph):
    """Only this pure built-in builder opts into cross-run compiled topology caching."""
    def __init__(self, spec):
        super().__init__(State, context_schema=RunContext)
        self.spec_digest = specification_digest(spec)

    def compile(self, **options):
        if options:
            raise GraphRefused('JSON_GRAPH_COMPILE_OPTIONS')
        key = (COMPILER_DIGEST, self.spec_digest)
        with _lock:
            if key in _cache:
                _cache.move_to_end(key)
                return _cache[key]
            graph = super().compile()
            _cache[key] = graph
            while len(_cache) > MAX_CACHE:
                _cache.popitem(last=False)
            return graph


def build_graph(configuration):
    spec = validate(configuration)
    graph = JsonGraphBuilder(spec)
    for node in spec['nodes']:
        graph.add_node('_node_' + node['id'], _operation(node))
        if node['kind'] == 'branch':
            def route(state, identity=node['id']):
                return 'true' if state['values'][identity] else 'false'
            graph.add_conditional_edges('_node_' + node['id'], route,
                {key: '_finish' if target == 'END' else '_node_' + target for key, target in node['routes'].items()})
    output, validator = deepcopy(spec['output']), schema_validator(spec['outputSchema'])
    def finish(state):
        result = json_value(expression(output, state))
        if not validator.is_valid(result):
            raise GraphRefused('JSON_GRAPH_OUTPUT_SCHEMA')
        return {'result': result}
    graph.add_node('_finish', finish)
    for edge in spec['edges']:
        sources = [START if name == 'START' else '_node_' + name for name in edge['from']]
        graph.add_edge(sources[0] if len(sources) == 1 else sources,
            '_finish' if edge['to'] == 'END' else '_node_' + edge['to'])
    graph.add_edge('_finish', END)
    return graph


def clear_cache():
    with _lock:
        _cache.clear()


def cache_status():
    with _lock:
        return {'entries': len(_cache), 'capacity': MAX_CACHE, 'compilerDigest': COMPILER_DIGEST, 'features': list(FEATURES)}
