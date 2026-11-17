# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Profile-selected native LangGraph execution through existing governed adapters."""
from __future__ import annotations

from copy import deepcopy
import asyncio
from dataclasses import dataclass, field
import json
import re
import time
import threading
from typing import Callable, Literal, Protocol, TypedDict

from langsmith import tracing_context
from .graph_api import JsonTooLarge, RunContext, json_value
from .graph_packages import GraphPackages, GraphRefused, validate_selection

MAX_VALUE_BYTES = 96 * 1024
ID = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$")


class Denied(Exception):
    """ToolGate refused current authorization. Remote error bodies must not be logged."""


class OwnershipLost(Exception):
    """The runner no longer owns this run; no further requests may be issued."""


class CompletionUnconfirmed(Exception):
    """Execution stopped, but ToolGate did not confirm the ledger outcome. Never rerun effects automatically."""


class _Stop(Exception):
    def __init__(self, code: str, state: str = "FAILED"):
        self.code, self.state = code, state


@dataclass(frozen=True)
class Limits:
    max_steps: int
    max_handoffs: int
    max_duration_seconds: int


@dataclass(frozen=True)
class ContextSource:
    tool_id: str
    arguments: dict = field(repr=False)
    argument_bindings: dict = field(default_factory=dict, repr=False)


@dataclass(frozen=True)
class Agent:
    agent_id: str
    model_group_id: str
    system_prompt: str = field(repr=False)
    context_sources: tuple[ContextSource, ...] = ()
    entry_eligible: bool = True
    delegation_allowed: bool = True


@dataclass(frozen=True)
class AgentStage:
    agent_id: str
    result_mode: Literal["CONTEXT", "MODEL"]


@dataclass(frozen=True)
class RunLease:
    run_id: str
    lease_id: str
    workflow_id: str
    workflow_version_id: str
    profile_digest: str
    lease_token: str = field(repr=False)
    message: str = field(repr=False)
    supervisor_prompt: str = field(repr=False)
    supervisor_model_group_id: str
    limits: Limits
    agents: tuple[Agent, ...]
    graph: dict = field(repr=False)
    context_sources: tuple[ContextSource, ...] = ()
    remediation_mode: Literal["PREPARE", "APPLY"] = "PREPARE"
    agent_stages: tuple[AgentStage, ...] = ()


@dataclass(frozen=True)
class StepGrant:
    run_id: str
    lease_id: str
    subject_kind: Literal["WORKFLOW", "AGENT"]
    subject_id: str
    model_group_id: str
    token: str = field(repr=False)


@dataclass(frozen=True)
class Candidate:
    agent_id: str
    description: str = ""


@dataclass(frozen=True)
class Tool:
    tool_id: str
    read_only: bool
    description: str = ""
    input_schema: dict = field(default_factory=dict, repr=False)


@dataclass(frozen=True)
class ToolOutcome:
    state: Literal["SUCCEEDED", "AWAITING_APPROVAL", "DENIED", "OUTCOME_UNKNOWN", "FAILED"]
    value: object = field(default=None, repr=False)


@dataclass(frozen=True)
class RunOutcome:
    state: Literal["SUCCEEDED", "FAILED", "AWAITING_APPROVAL"]
    result: object = field(default=None, repr=False)
    code: str | None = None


@dataclass(frozen=True)
class CompletionAck:
    run_id: str
    lease_id: str
    state: Literal["SUCCEEDED", "FAILED", "AWAITING_APPROVAL"]


class ToolGate(Protocol):
    """Future authenticated adapter boundary, not a server implementation or an authorization cache.

    Each method must enforce current tenant/run/ownership/subject authority server-side,
    honor timeout_seconds and avoid redirects, retries, direct providers and raw error logging.
    Grant metadata is checked locally only to prevent accidental widening; opaque tokens are
    verified by ToolGate. discover_agents returns current workflow/user-scoped summaries;
    discover_tools returns only the current step's effective tools. The adapter/server must
    enforce pinned input/output schemas and existing effect identities; the runner is not policy.
    """
    def authorize_step(self, lease: RunLease, subject_kind: str, subject_id: str,
                       model_group_id: str, *, timeout_seconds: float) -> StepGrant: ...
    def discover_agents(self, lease: RunLease, grant: StepGrant, *, timeout_seconds: float) -> tuple[Candidate, ...]: ...
    def discover_tools(self, lease: RunLease, grant: StepGrant, *, timeout_seconds: float) -> tuple[Tool, ...]: ...
    def infer(self, lease: RunLease, grant: StepGrant, *, system_prompt: str, messages: list[dict],
              tools: tuple[Tool, ...], candidates: tuple[Candidate, ...], timeout_seconds: float) -> dict: ...
    def call_tool(self, lease: RunLease, grant: StepGrant, tool_id: str, arguments: dict,
                  *, timeout_seconds: float) -> ToolOutcome: ...
    def finish(self, lease: RunLease, outcome: RunOutcome, *, timeout_seconds: float) -> CompletionAck: ...


class _State(TypedDict):
    phase: str
    active_agent: str | None
    supervisor_messages: list[dict]
    agent_messages: list[dict]
    supervisor_context_loaded: bool
    agent_context_loaded: bool
    outcome: RunOutcome | None


def _json(value: object) -> object:
    try:
        return json_value(value, max_bytes=MAX_VALUE_BYTES)
    except JsonTooLarge:
        raise _Stop("VALUE_TOO_LARGE") from None
    except (TypeError, ValueError, UnicodeError, RecursionError):
        raise _Stop("INVALID_JSON") from None


def _validate(lease: RunLease) -> None:
    validate_selection(lease.graph)
    for value in (lease.run_id, lease.lease_id, lease.workflow_id, lease.workflow_version_id,
                  lease.supervisor_model_group_id):
        if not isinstance(value, str) or not ID.fullmatch(value):
            raise _Stop("INVALID_LEASE")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", lease.profile_digest):
        raise _Stop("INVALID_LEASE")
    if not isinstance(lease.lease_token, str) or not lease.lease_token:
        raise _Stop("INVALID_LEASE")
    if lease.remediation_mode not in ("PREPARE", "APPLY"):
        raise _Stop("INVALID_LEASE")
    limits = lease.limits
    if (type(limits.max_steps) is not int or type(limits.max_handoffs) is not int
            or type(limits.max_duration_seconds) is not int
            or not 1 <= limits.max_steps <= 100
            or not 0 <= limits.max_handoffs <= limits.max_steps
            or not 1 <= limits.max_duration_seconds <= 3600):
        raise _Stop("INVALID_LIMITS")
    if not isinstance(lease.message, str) or not lease.message or len(lease.message) > 32768:
        raise _Stop("INVALID_MESSAGE")
    _json(lease.message)
    if not 1 <= len(lease.agents) <= 32 or len({a.agent_id for a in lease.agents}) != len(lease.agents):
        raise _Stop("INVALID_AGENTS")
    if not any(a.entry_eligible is True for a in lease.agents):
        raise _Stop("INVALID_AGENTS")
    stage_ids = set()
    agents = {agent.agent_id: agent for agent in lease.agents}
    if len(lease.agent_stages) > lease.limits.max_handoffs:
        raise _Stop("INVALID_STAGES")
    for stage in lease.agent_stages:
        if not isinstance(stage, AgentStage) or not isinstance(stage.agent_id, str):
            raise _Stop("INVALID_STAGES")
        agent = agents.get(stage.agent_id)
        if (agent is None or stage.agent_id in stage_ids or stage.result_mode not in ("CONTEXT", "MODEL")
                or not (agent.entry_eligible if not stage_ids else agent.delegation_allowed)
                or stage.result_mode == "CONTEXT" and not agent.context_sources):
            raise _Stop("INVALID_STAGES")
        stage_ids.add(stage.agent_id)
    prompts = [lease.supervisor_prompt]
    sources = [lease.context_sources]
    for agent in lease.agents:
        if not ID.fullmatch(agent.agent_id) or not ID.fullmatch(agent.model_group_id):
            raise _Stop("INVALID_AGENTS")
        if type(agent.entry_eligible) is not bool or type(agent.delegation_allowed) is not bool:
            raise _Stop("INVALID_AGENTS")
        prompts.append(agent.system_prompt)
        sources.append(agent.context_sources)
    if any(not isinstance(p, str) or not p.strip() or len(p) > 32768 for p in prompts):
        raise _Stop("INVALID_PROMPT")
    for contexts in sources:
        if len(contexts) > 16:
            raise _Stop("INVALID_CONTEXT")
        for source in contexts:
            if not isinstance(source.tool_id, str) or not source.tool_id or not isinstance(source.arguments, dict):
                raise _Stop("INVALID_CONTEXT")
            if (not isinstance(source.argument_bindings, dict) or len(source.argument_bindings) > 32
                    or any(not isinstance(key, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", key)
                           or value != "/message" or key in source.arguments for key, value in source.argument_bindings.items())):
                raise _Stop("INVALID_CONTEXT")
            _json(source.arguments)


class _Execution:
    def __init__(self, gate: ToolGate, lease: RunLease, clock: Callable[[], float]):
        self.gate, self.lease, self.clock = gate, lease, clock
        self.deadline = clock() + lease.limits.max_duration_seconds
        self.steps = self.handoffs = 0
        self.agents = {a.agent_id: a for a in lease.agents}
        self.current_candidates: set[str] = set()
        self.context_results: dict[str, list[dict]] = {}
        self.completed: dict[str, object] = {}

    def remaining(self) -> float:
        remaining = self.deadline - self.clock()
        if remaining <= 0:
            raise _Stop("TIME_LIMIT")
        return remaining

    def consume(self) -> None:
        self.remaining()
        if self.steps >= self.lease.limits.max_steps:
            raise _Stop("STEP_LIMIT")
        self.steps += 1

    def grant(self, agent: Agent | None) -> StepGrant:
        kind = "AGENT" if agent else "WORKFLOW"
        subject = agent.agent_id if agent else self.lease.workflow_id
        model = agent.model_group_id if agent else self.lease.supervisor_model_group_id
        grant = self.gate.authorize_step(self.lease, kind, subject, model, timeout_seconds=self.remaining())
        self.remaining()
        if (not isinstance(grant, StepGrant) or not isinstance(grant.token, str) or not grant.token
                or (grant.run_id, grant.lease_id, grant.subject_kind, grant.subject_id, grant.model_group_id)
                != (self.lease.run_id, self.lease.lease_id, kind, subject, model)):
            raise _Stop("GRANT_SCOPE_MISMATCH")
        return grant

    def tools(self, grant: StepGrant) -> tuple[Tool, ...]:
        tools = self.gate.discover_tools(self.lease, grant, timeout_seconds=self.remaining())
        self.remaining()
        if (not isinstance(tools, tuple) or len(tools) > 64
                or any(not isinstance(t, Tool) or not isinstance(t.tool_id, str) or not t.tool_id
                       or not isinstance(t.description, str) or not isinstance(t.input_schema, dict) or type(t.read_only) is not bool for t in tools)
                or len({t.tool_id for t in tools}) != len(tools)):
            raise _Stop("INVALID_TOOL_CATALOG")
        _json([{"id": t.tool_id, "description": t.description, "inputSchema": t.input_schema} for t in tools])
        return tools

    def candidates(self, grant: StepGrant) -> tuple[Candidate, ...]:
        candidates = self.gate.discover_agents(self.lease, grant, timeout_seconds=self.remaining())
        self.remaining()
        if (not isinstance(candidates, tuple) or len(candidates) > 32
                or any(not isinstance(c, Candidate) or not isinstance(c.agent_id, str) or c.agent_id not in self.agents
                       or not isinstance(c.description, str) for c in candidates)
                or len({c.agent_id for c in candidates}) != len(candidates)):
            raise _Stop("INVALID_AGENT_CATALOG")
        allowed = {a.agent_id for a in self.lease.agents
                   if (a.entry_eligible if self.handoffs == 0 else a.delegation_allowed)}
        candidates = tuple(c for c in candidates if c.agent_id in allowed)
        _json([{"agentId": c.agent_id, "description": c.description} for c in candidates])
        self.current_candidates = {c.agent_id for c in candidates}
        return candidates

    def call(self, agent: Agent | None, tool_id: str, arguments: dict, *, context: bool = False) -> object:
        self.consume()
        grant = self.grant(agent)
        tools = {t.tool_id: t for t in self.tools(grant)}
        tool = tools.get(tool_id)
        if tool is None:
            raise _Stop("TOOL_NOT_AVAILABLE")
        if (context or self.lease.remediation_mode == "PREPARE") and not tool.read_only:
            raise _Stop("MUTATION_NOT_ALLOWED")
        arguments = _json(arguments)
        timeout = self.remaining()
        try:
            outcome = self.gate.call_tool(self.lease, grant, tool_id, arguments, timeout_seconds=timeout)
        except (Denied, OwnershipLost):
            raise
        except Exception:
            # A transport failure can follow an applied mutation. Never turn it into a retryable call.
            raise _Stop("TOOL_OUTCOME_UNKNOWN") from None
        # No retry, including after an ambiguous transport failure or a late successful call.
        self.remaining()
        if not isinstance(outcome, ToolOutcome):
            raise _Stop("INVALID_TOOL_OUTCOME")
        if outcome.state == "AWAITING_APPROVAL":
            raise _Stop("APPROVAL_REQUIRED", "AWAITING_APPROVAL")
        if outcome.state != "SUCCEEDED":
            codes = {"DENIED": "DENIED", "OUTCOME_UNKNOWN": "TOOL_OUTCOME_UNKNOWN", "FAILED": "TOOL_FAILED"}
            raise _Stop(codes.get(outcome.state, "INVALID_TOOL_OUTCOME"))
        return _json(outcome.value)

    def context(self, agent: Agent | None, messages: list[dict]) -> list[dict]:
        messages = deepcopy(messages)
        results = []
        for source in agent.context_sources if agent else self.lease.context_sources:
            arguments = deepcopy(source.arguments)
            for name in source.argument_bindings:
                arguments[name] = self.lease.message
            value = self.call(agent, source.tool_id, arguments, context=True)
            messages.append({"role": "tool", "toolId": source.tool_id, "content": value})
            results.append({"toolId": source.tool_id, "content": value})
        _json(messages)
        if agent is not None:
            self.context_results[agent.agent_id] = _json(results)
        return messages

    def infer(self, agent: Agent | None, messages: list[dict]) -> dict:
        self.consume()
        grant = self.grant(agent)
        # Supervisor may delegate or finish; its declared context is retrieved separately.
        tools = self.tools(grant) if agent else ()
        if self.lease.remediation_mode == "PREPARE":
            tools = tuple(t for t in tools if t.read_only)
        candidates = () if agent else self.candidates(grant)
        result = self.gate.infer(self.lease, grant,
            system_prompt=agent.system_prompt if agent else self.lease.supervisor_prompt,
            messages=_json(messages), tools=tools, candidates=candidates, timeout_seconds=self.remaining())
        self.remaining()
        result = _json(result)
        if not isinstance(result, dict):
            raise _Stop("INVALID_MODEL_DECISION")
        return result

    def advance(self, state: _State) -> dict:
        if state["phase"] == "supervisor":
            messages = state["supervisor_messages"]
            if not state["supervisor_context_loaded"]:
                messages = self.context(None, messages)
            if self.lease.agent_stages:
                self.consume()
                grant = self.grant(None)
                if self.handoffs == len(self.lease.agent_stages):
                    return {"outcome": RunOutcome("SUCCEEDED", _json(self.completed[self.lease.agent_stages[-1].agent_id]))}
                self.candidates(grant)
                stage = self.lease.agent_stages[self.handoffs]
                decision = {"kind": "delegate", "agentId": stage.agent_id}
                if self.handoffs:
                    decision["inputFromAgent"] = self.lease.agent_stages[self.handoffs - 1].agent_id
                else:
                    decision["input"] = self.lease.message
            else:
                decision = self.infer(None, messages)
            if set(decision) == {"kind", "result"} and decision["kind"] == "finish":
                return {"outcome": RunOutcome("SUCCEEDED", decision["result"])}
            if (set(decision) not in ({"kind", "agentId", "input"}, {"kind", "agentId", "inputFromAgent"})
                    or decision["kind"] != "delegate"):
                raise _Stop("INVALID_MODEL_DECISION")
            agent_id = decision["agentId"]
            agent = self.agents.get(agent_id) if isinstance(agent_id, str) else None
            if agent is None or agent_id not in self.current_candidates:
                raise _Stop("AGENT_NOT_AVAILABLE")
            if self.handoffs >= self.lease.limits.max_handoffs:
                raise _Stop("HANDOFF_LIMIT")
            if "inputFromAgent" in decision:
                source = decision["inputFromAgent"]
                if not isinstance(source, str) or source not in self.completed:
                    raise _Stop("CONTEXT_NOT_AVAILABLE")
                handoff = _json(self.completed[source])
            else:
                handoff = decision["input"]
                if not isinstance(handoff, str):
                    raise _Stop("INVALID_MODEL_DECISION")
            self.handoffs += 1
            return {"phase": "agent", "active_agent": agent_id,
                    "supervisor_messages": messages, "supervisor_context_loaded": True,
                    "agent_messages": [{"role": "user", "content": handoff}], "agent_context_loaded": False}
        agent = self.agents[state["active_agent"]]
        messages = state["agent_messages"]
        if not state["agent_context_loaded"]:
            messages = self.context(agent, messages)
        context_only = (self.lease.agent_stages and self.lease.agent_stages[self.handoffs - 1].result_mode == "CONTEXT")
        decision = {"kind": "finish_context"} if context_only else self.infer(agent, messages)
        finishing = True
        if decision == {"kind": "finish_context"}:
            if not agent.context_sources or agent.agent_id not in self.context_results:
                raise _Stop("CONTEXT_NOT_AVAILABLE")
            result = _json({"message": self.lease.message, "context": self.context_results[agent.agent_id]})
        elif set(decision) == {"kind", "result"} and decision["kind"] == "finish":
            result = _json(decision["result"])
        else:
            finishing = False
        if finishing:
            self.completed[agent.agent_id] = _json(result)
            supervisor = state["supervisor_messages"] + [{"role": "tool", "agentId": agent.agent_id, "content": result}]
            _json(supervisor)
            return {"phase": "supervisor", "active_agent": None, "supervisor_messages": supervisor}
        if (set(decision) != {"kind", "toolId", "arguments"} or decision["kind"] != "tool"
                or not isinstance(decision["toolId"], str) or not isinstance(decision["arguments"], dict)):
            raise _Stop("INVALID_MODEL_DECISION")
        value = self.call(agent, decision["toolId"], decision["arguments"])
        messages = messages + [{"role": "assistant", "content": decision},
                               {"role": "tool", "toolId": decision["toolId"], "content": value}]
        _json(messages)
        return {"agent_messages": messages, "agent_context_loaded": True}


class _Capabilities:
    """Per-run serialized adapters for Control's current single-active-step ledger."""
    def __init__(self, execution):
        self.execution = execution
        self.lock = threading.RLock()
        self.fatal = None

    def guarded(self, operation, *args):
        with self.lock:
            if self.fatal is not None:
                raise self.fatal
            try:
                return operation(*args)
            except (_Stop, Denied, OwnershipLost) as failure:
                self.fatal = failure
                raise

    def admit(self, agent_id):
        execution = self.execution
        execution.consume()
        execution.candidates(execution.grant(None))
        agent = execution.agents.get(agent_id) if isinstance(agent_id, str) else None
        if agent is None or agent_id not in execution.current_candidates:
            raise _Stop("AGENT_NOT_AVAILABLE")
        if execution.handoffs >= execution.lease.limits.max_handoffs:
            raise _Stop("HANDOFF_LIMIT")
        execution.handoffs += 1
        return agent

    def agent(self, agent_id, value, mode):
        if mode not in ("MODEL", "CONTEXT"):
            raise _Stop("INVALID_RESULT_MODE")
        agent = self.admit(agent_id)
        execution = self.execution
        messages = execution.context(agent, [{"role": "user", "content": _json(value)}])
        if mode == "CONTEXT":
            if not agent.context_sources:
                raise _Stop("CONTEXT_NOT_AVAILABLE")
            return _json({"message": execution.lease.message, "context": execution.context_results[agent.agent_id]})
        while True:
            decision = execution.infer(agent, messages)
            if set(decision) == {"kind", "result"} and decision["kind"] == "finish":
                return _json(decision["result"])
            if (set(decision) != {"kind", "toolId", "arguments"} or decision["kind"] != "tool"
                    or not isinstance(decision["toolId"], str) or not isinstance(decision["arguments"], dict)):
                raise _Stop("INVALID_MODEL_DECISION")
            value = execution.call(agent, decision["toolId"], decision["arguments"])
            messages = _json(messages + [{"role": "assistant", "content": decision},
                {"role": "tool", "toolId": decision["toolId"], "content": value}])


class _Agents:
    def __init__(self, capabilities):
        self._capabilities = capabilities

    async def invoke(self, agent_id, value, *, result_mode="MODEL"):
        return await asyncio.to_thread(self._capabilities.guarded, self._capabilities.agent, agent_id, value, result_mode)

    def _advance(self, state):
        # Only the installed built-in graph uses this current supervisor implementation.
        return self._capabilities.guarded(self._capabilities.execution.advance, state)


class _Models:
    def __init__(self, capabilities):
        self._capabilities = capabilities

    async def invoke(self, agent_id, messages):
        def operation():
            if not isinstance(messages, list):
                raise _Stop("INVALID_MESSAGES")
            return self._capabilities.execution.infer(self._capabilities.admit(agent_id), _json(messages))
        return await asyncio.to_thread(self._capabilities.guarded, operation)


class _Tools:
    def __init__(self, capabilities):
        self._capabilities = capabilities

    async def call(self, agent_id, tool_ref, arguments):
        def operation():
            if not isinstance(tool_ref, str) or not isinstance(arguments, dict):
                raise _Stop("INVALID_TOOL_CALL")
            return self._capabilities.execution.call(self._capabilities.admit(agent_id), tool_ref, _json(arguments))
        return await asyncio.to_thread(self._capabilities.guarded, operation)


class Runner:
    def __init__(self, toolgate: ToolGate, *, clock: Callable[[], float] = time.monotonic, graph_directory=None):
        self.toolgate, self.clock = toolgate, clock
        self.graph_packages = GraphPackages(graph_directory)

    async def _graph(self, lease, execution):
        capabilities = _Capabilities(execution)
        context = RunContext(_Models(capabilities), _Tools(capabilities), _Agents(capabilities))
        final = None
        with self.graph_packages.load(lease.graph) as (graph, output_schema):
            async for kind, update in graph.astream({"message": lease.message},
                    {"recursion_limit": lease.limits.max_steps + 4, "callbacks": []},
                    context=context, stream_mode=["updates", "values"]):
                execution.remaining()
                if capabilities.fatal is not None:
                    raise capabilities.fatal
                if isinstance(update, dict) and "__interrupt__" in update:
                    raise _Stop("GRAPH_INTERRUPTS_UNSUPPORTED")
                update = _json(update)
                if kind == "values":
                    final = update
            if capabilities.fatal is not None:
                raise capabilities.fatal
            if not isinstance(final, dict) or "result" not in final:
                raise _Stop("GRAPH_RESULT_REQUIRED")
            if not output_schema.is_valid(final["result"]):
                raise _Stop("GRAPH_OUTPUT_SCHEMA")
            # Recheck current workflow ownership/authority even for pure-computation graphs.
            execution.grant(None)
            return RunOutcome("SUCCEEDED", _json(final["result"]))

    async def _bounded_graph(self, lease, execution):
        deadline = asyncio.timeout(execution.remaining())
        try:
            async with deadline:
                return await self._graph(lease, execution)
        except TimeoutError:
            if deadline.expired():
                raise _Stop("TIME_LIMIT") from None
            raise

    def execute(self, lease: RunLease) -> RunOutcome:
        # Capture one pinned input snapshot; never let adapters mutate the caller's configuration.
        lease = deepcopy(lease)
        try:
            _validate(lease)
            execution = _Execution(self.toolgate, lease, self.clock)
            with tracing_context(enabled=False):
                outcome = asyncio.run(self._bounded_graph(lease, execution))
        except OwnershipLost:
            raise OwnershipLost("Run ownership was lost") from None
        except _Stop as stopped:
            outcome = RunOutcome(stopped.state, code=stopped.code)
        except Denied:
            outcome = RunOutcome("FAILED", code="DENIED")
        except GraphRefused as failure:
            outcome = RunOutcome("FAILED", code=str(failure))
        except Exception:
            # Transport exceptions may contain tokens, prompts or provider bodies. Do not copy them into results.
            outcome = RunOutcome("FAILED", code="RUNTIME_ERROR")
        try:
            # Closing the ledger is allowed after the execution budget ends; ToolGate still verifies ownership.
            with tracing_context(enabled=False):
                ack = self.toolgate.finish(lease, outcome, timeout_seconds=5.0)
            if (not isinstance(ack, CompletionAck)
                    or (ack.run_id, ack.lease_id, ack.state) != (lease.run_id, lease.lease_id, outcome.state)):
                raise CompletionUnconfirmed("Completion acknowledgement did not match this run")
        except Exception:
            raise CompletionUnconfirmed("ToolGate did not confirm run completion") from None
        return outcome
