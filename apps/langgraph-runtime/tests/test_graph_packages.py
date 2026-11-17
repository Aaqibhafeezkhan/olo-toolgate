# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Native graph execution against deterministic governed adapters, not live server attestation."""
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from copy import deepcopy
from dataclasses import replace
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

from toolgate_runtime.runner import Runner, ToolOutcome
from toolgate_runtime.graph_api import json_value
from toolgate_runtime.graph_packages import GraphPackages, GraphRefused
from test_runner import Gate, lease

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("graph_packaging", ROOT / "tools/workflows/graph_package.py")
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)

PURE_GRAPH = '''from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.runtime import Runtime
from toolgate_runtime.graph_api import RunContext
import asyncio
counter = 0
class State(TypedDict, total=False):
    message: str
    result: object
def build_graph(config):
    async def node(state, runtime: Runtime[RunContext]):
        global counter
        counter += 1
        await asyncio.sleep(0)
        return {"result": {"message": state["message"], "prefix": config.get("prefix", ""), "count": counter}}
    graph = StateGraph(State, context_schema=RunContext)
    graph.add_node("node", node)
    graph.add_edge(START, "node")
    graph.add_edge("node", END)
    return graph
'''


class NativeGraphTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.installed = self.root / "source", self.root / "installed"
        self.source.mkdir()
        self.manifest = json.loads((ROOT / "examples/native-graphs/summary/package.json").read_text())
        self.manifest["factories"]["summary"]["configSchema"] = {"type": "object"}

    def package(self, code=PURE_GRAPH):
        (self.source / "graph.py").write_text(code, encoding="utf-8")
        (self.source / "README.md").write_text("Test graph with deterministic mock providers and no credentials.", encoding="utf-8")
        (self.source / "package.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        return packaging.build(self.source, self.installed)

    def execute(self, selection, run=None, gate=None):
        run = replace(run or lease(), graph=selection)
        gate = gate or Gate(run)
        return Runner(gate, graph_directory=self.installed).execute(run), gate

    def test_native_factory_receives_json_and_has_no_cross_run_module_state(self):
        selection = self.package()
        selection["config"] = {"prefix": "first"}
        outcome, gate = self.execute(selection)
        self.assertEqual("SUCCEEDED", outcome.state)
        self.assertEqual({"message": "Please investigate.", "prefix": "first", "count": 1}, outcome.result)
        self.assertEqual([], gate.inferences)
        second = deepcopy(selection)
        second["config"]["prefix"] = "second"
        outcome, _ = self.execute(second)
        self.assertEqual(("second", 1), (outcome.result["prefix"], outcome.result["count"]))
        self.assertFalse(any(name.startswith("_toolgate_graph_") for name in sys.modules))

    def test_concurrent_runs_receive_their_own_input_and_factory_config(self):
        selection = self.package()
        def invoke(index):
            selected = {**selection, "config": {"prefix": str(index)}}
            result, _ = self.execute(selected, replace(lease(), message=f"input-{index}"))
            return result.result
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(invoke, range(8)))
        self.assertEqual([{"message": f"input-{i}", "prefix": str(i), "count": 1} for i in range(8)], results)

    def test_installed_native_async_agent_uses_current_model_and_authority(self):
        selection = packaging.build(ROOT / "examples/native-graphs/summary", self.installed)
        selection["config"] = {"agentId": "support-fixer", "resultKey": "wrapped"}
        run = replace(lease(), graph=selection)
        gate = Gate(run)
        gate.decisions["support-fixer"] = deque([{"kind": "finish", "result": {"summary": "configured result"}}])
        outcome, _ = self.execute(selection, run, gate)
        self.assertEqual({"wrapped": {"summary": "configured result"}}, outcome.result)
        self.assertEqual(["support-fixer"], [call[0] for call in gate.inferences])
        gate = Gate(run)
        gate.agent_catalog = ()
        outcome, _ = self.execute(selection, run, gate)
        self.assertEqual("AGENT_NOT_AVAILABLE", outcome.code)
        self.assertEqual([], gate.inferences)

    def test_mismatched_content_and_unknown_package_fail_before_adapter_calls(self):
        selection = self.package()
        (self.installed / selection["digest"][7:] / "graph.py").write_text("raise RuntimeError('must never execute')", encoding="utf-8")
        outcome, gate = self.execute(selection)
        self.assertEqual("GRAPH_FILE_MISMATCH", outcome.code)
        self.assertEqual([], gate.authorizations)
        selection["digest"] = "sha256:" + "0" * 64
        outcome, gate = self.execute(selection)
        self.assertEqual("GRAPH_PACKAGE_UNAVAILABLE", outcome.code)
        self.assertEqual([], gate.calls)

    def test_unsupported_checkpoint_capability_is_refused_before_import(self):
        self.manifest["capabilities"].append("checkpoints")
        selection = self.package("raise RuntimeError('must never execute')")
        outcome, gate = self.execute(selection)
        self.assertEqual("GRAPH_UNSUPPORTED_CAPABILITY", outcome.code)
        self.assertEqual([], gate.authorizations)

    def test_configuration_and_output_schemas_are_enforced(self):
        self.manifest["factories"]["summary"]["configSchema"] = {"type": "object", "required": ["required"]}
        selection = self.package()
        outcome, _ = self.execute(selection)
        self.assertEqual("GRAPH_CONFIG_SCHEMA", outcome.code)
        self.manifest["factories"]["summary"]["configSchema"] = {"type": "object"}
        self.manifest["factories"]["summary"]["outputSchema"] = {"type": "string"}
        selection = self.package()
        outcome, _ = self.execute(selection)
        self.assertEqual("GRAPH_OUTPUT_SCHEMA", outcome.code)

    def test_native_branches_parallel_computation_and_join(self):
        code = '''from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from toolgate_runtime.graph_api import RunContext
import asyncio
class State(TypedDict, total=False):
    message: str
    left: str
    right: str
    result: object
def build_graph(config):
    async def left(state):
        await asyncio.sleep(0)
        return {"left": state["message"].upper()}
    async def right(state):
        await asyncio.sleep(0)
        return {"right": state["message"].lower()}
    graph = StateGraph(State, context_schema=RunContext)
    graph.add_node("route", lambda state: {})
    graph.add_node("fork", lambda state: {})
    graph.add_node("left", left)
    graph.add_node("right", right)
    graph.add_node("join", lambda state: {"result": [state["left"], state["right"]]})
    graph.add_node("single", lambda state: {"result": config["result"]})
    graph.add_edge(START, "route")
    graph.add_conditional_edges("route", lambda state: "fork" if config["parallel"] else "single", ["fork", "single"])
    graph.add_edge("fork", "left")
    graph.add_edge("fork", "right")
    graph.add_edge(["left", "right"], "join")
    graph.add_edge("join", END)
    graph.add_edge("single", END)
    return graph
'''
        selection = self.package(code)
        selection["config"] = {"parallel": True, "result": "configured branch"}
        outcome, _ = self.execute(selection)
        self.assertEqual(["PLEASE INVESTIGATE.", "please investigate."], outcome.result)
        selection["config"]["parallel"] = False
        outcome, _ = self.execute(selection)
        self.assertEqual("configured branch", outcome.result)

    def test_native_subgraph_inherits_run_context_without_a_checkpointer(self):
        code = PURE_GRAPH.replace('graph.add_node("node", node)', '''child = StateGraph(State, context_schema=RunContext)
    child.add_node("nested", node)
    child.add_edge(START, "nested")
    child.add_edge("nested", END)
    graph.add_node("node", child.compile())''')
        selection = self.package(code)
        outcome, _ = self.execute(selection)
        self.assertEqual("SUCCEEDED", outcome.state)
        self.assertEqual(1, outcome.result["count"])

    def test_non_json_results_and_unbound_factory_requirement(self):
        selection = self.package(PURE_GRAPH.replace('"count": counter', '"count": {1, 2}'))
        outcome, _ = self.execute(selection)
        self.assertEqual("INVALID_JSON", outcome.code)
        selection = self.package(PURE_GRAPH.replace("return graph\n", "return graph.compile()\n"))
        outcome, _ = self.execute(selection)
        self.assertEqual("GRAPH_UNBOUND_BUILDER_REQUIRED", outcome.code)

    def test_unknown_effect_latches_even_if_a_native_node_catches_the_exception(self):
        code = PURE_GRAPH.replace('return {"result": {"message": state["message"], "prefix": config.get("prefix", ""), "count": counter}}', '''for _ in range(2):
            try:
                await runtime.context.tools.call("support-fixer", "support/write", {"value": 1})
            except Exception:
                pass
        return {"result": "cannot hide an unknown effect"}''')
        selection = self.package(code)
        run = replace(lease(), graph=selection)
        gate = Gate(run)
        gate.tool_outcome = ToolOutcome("OUTCOME_UNKNOWN", None)
        outcome, _ = self.execute(selection, run, gate)
        self.assertEqual("TOOL_OUTCOME_UNKNOWN", outcome.code)
        self.assertEqual(1, len(gate.calls))

    def test_remote_schema_and_missing_graph_are_refused(self):
        self.manifest["factories"]["summary"]["configSchema"] = {"$ref": "https://example.invalid/schema"}
        selection = self.package()
        outcome, _ = self.execute(selection)
        self.assertEqual("GRAPH_REMOTE_SCHEMA_REFUSED", outcome.code)
        outcome, _ = self.execute({})
        self.assertEqual("GRAPH_SELECTION_REQUIRED", outcome.code)

    def test_json_does_not_silently_coerce_objects_or_keys(self):
        for value in [(1, 2), {1: "value"}, {"value": float("nan")}, object()]:
            with self.assertRaises(ValueError):
                json_value(value)
