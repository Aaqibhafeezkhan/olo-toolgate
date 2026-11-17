# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Workload-authenticated transport for the Control run ledger."""
from __future__ import annotations

import json
import re
import uuid
from urllib.error import HTTPError
from urllib.request import Request

from .runner import Agent, AgentStage, Candidate, CompletionAck, ContextSource, Denied, ID, Limits, OwnershipLost, RunLease, StepGrant, Tool, ToolOutcome
from .trigger_bridge import _constant, _object
from .trigger_transport import ControlTriggers
from .graph_packages import validate_selection


class RunTransportError(Exception):
    """A bounded error that carries no response body or credential."""


def _record(value, fields):
    if not isinstance(value, dict) or set(value) != set(fields.split()):
        raise ValueError("Invalid runtime response")
    return value


def _contexts(values):
    if not isinstance(values, list) or len(values) > 16:
        raise ValueError("Invalid context sources")
    contexts = []
    for value in values:
        if (not isinstance(value, dict) or not {"toolId", "arguments"} <= set(value)
                or set(value) - {"toolId", "arguments", "argumentBindings"}):
            raise ValueError("Invalid context source")
        contexts.append(ContextSource(value["toolId"], value["arguments"], value.get("argumentBindings", {})))
    return tuple(contexts)


def _agent(value):
    agent = _record(value, "agentId modelGroupId systemPrompt contextSources entryEligible delegationAllowed")
    return Agent(agent["agentId"], agent["modelGroupId"], agent["systemPrompt"], _contexts(agent["contextSources"]),
                 agent["entryEligible"], agent["delegationAllowed"])


def _lease(value):
    data = _record(value, "runId leaseId workflowId workflowVersionId profileDigest leaseToken message supervisorPrompt "
                         "supervisorModelGroupId limits agents contextSources remediationMode expiresAtUnixMs agentStages graph compilerDigest")
    validate_selection(data["graph"])
    if (not isinstance(data["leaseToken"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", data["leaseToken"])
            or type(data["expiresAtUnixMs"]) is not int or data["expiresAtUnixMs"] <= 0
            or not isinstance(data["agents"], list) or not 1 <= len(data["agents"]) <= 32):
        raise ValueError("Invalid run lease")
    limits = _record(data["limits"], "maxSteps maxHandoffs maxDurationSeconds")
    if not isinstance(data['agentStages'], list) or len(data['agentStages']) > 32:
        raise ValueError('Invalid agent stages')
    stages = tuple(AgentStage(**{'agent_id': _record(stage, 'agentId resultMode')['agentId'],
                                'result_mode': stage['resultMode']}) for stage in data['agentStages'])
    return RunLease(data["runId"], data["leaseId"], data["workflowId"], data["workflowVersionId"], data["profileDigest"],
                    data["leaseToken"], data["message"], data["supervisorPrompt"], data["supervisorModelGroupId"],
                    Limits(limits["maxSteps"], limits["maxHandoffs"], limits["maxDurationSeconds"]),
                    tuple(map(_agent, data["agents"])), data["graph"], data["compilerDigest"], _contexts(data["contextSources"]), data["remediationMode"], stages)


class ControlRuns(ControlTriggers):
    """Reuse the Control TLS policy with a separate workload credential."""

    def _runtime_request(self, path, payload, *, timeout_seconds, allow_empty=False):
        try:
            token = self._token()
            if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,8192}", token):
                raise ValueError("Invalid workload credential")
            timeout = min(self.timeout, timeout_seconds)
            if not 0 < timeout <= 30:
                raise ValueError("Invalid runtime timeout")
            request = Request(self.origin + "/internal/v1/runtime/" + path,
                              json.dumps(payload, allow_nan=False).encode(),
                              {"Authorization": "Bearer " + token, "Content-Type": "application/json",
                               "Accept": "application/json"}, method="POST")
            with self._opener.open(request, timeout=timeout) as response:
                if allow_empty and response.status == 204:
                    return None
                if response.status != 200 or response.headers.get_content_type() != "application/json":
                    raise ValueError("Invalid runtime response")
                raw = response.read(262145)
                if len(raw) > 262144:
                    raise ValueError("Runtime response exceeds limit")
                return json.loads(raw, object_pairs_hook=_object, parse_constant=_constant)
        except HTTPError as failure:
            with failure:
                if failure.code in (401, 403, 409) and not allow_empty:
                    if path.endswith("/finish"):
                        raise OwnershipLost("Run lease is unavailable") from None
                    raise Denied("Control refused the workflow step") from None
            raise RunTransportError("Control rejected the runtime request") from None
        except Exception:
            raise RunTransportError("Control runtime request failed") from None

    def claim(self):
        value = self._runtime_request("claim", {}, timeout_seconds=self.timeout, allow_empty=True)
        if value is None:
            return None
        try:
            return _lease(value)
        except (ValueError, TypeError):
            raise RunTransportError("Invalid Control run snapshot") from None

    @staticmethod
    def _run_path(lease, operation):
        if not isinstance(lease.run_id, str) or not ID.fullmatch(lease.run_id) or any(c in lease.run_id for c in ":/"):
            raise RunTransportError("Invalid run identity")
        return f"runs/{lease.run_id}/{operation}"

    def authorize_step(self, lease, subject_kind, subject_id, model_group_id, *, timeout_seconds):
        value = self._runtime_request(self._run_path(lease, "steps:authorize"),
                                      {"leaseId": lease.lease_id, "leaseToken": lease.lease_token, "subjectKind": subject_kind,
                                       "subjectId": subject_id, "modelGroupId": model_group_id}, timeout_seconds=timeout_seconds)
        try:
            grant = _record(value, "runId leaseId subjectKind subjectId modelGroupId token")
            if not isinstance(grant["token"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", grant["token"]):
                raise ValueError("Invalid step token")
            return StepGrant(grant["runId"], grant["leaseId"], grant["subjectKind"], grant["subjectId"],
                             grant["modelGroupId"], grant["token"])
        except ValueError:
            raise RunTransportError("Invalid Control step grant") from None

    def finish(self, lease, outcome, *, timeout_seconds):
        value = self._runtime_request(self._run_path(lease, "finish"),
                                      {"leaseId": lease.lease_id, "leaseToken": lease.lease_token, "state": outcome.state,
                                       "result": outcome.result, "code": outcome.code},
                                      timeout_seconds=timeout_seconds)
        try:
            ack = _record(value, "runId leaseId state")
            return CompletionAck(ack["runId"], ack["leaseId"], ack["state"])
        except ValueError:
            raise RunTransportError("Invalid Control completion acknowledgement") from None


class GatewayRuntime(ControlTriggers):
    """Inference and tools through Gateway using the active step credential."""

    def __init__(self, origin, *, ca_file=None, timeout_seconds=30):
        super().__init__(origin, lambda: "", ca_file=ca_file, timeout_seconds=timeout_seconds)

    def infer(self, lease, grant, *, system_prompt, messages, tools, candidates, timeout_seconds):
        decisions = ['{"kind":"finish","result":{...}}']
        if grant.subject_kind == "WORKFLOW" and candidates:
            decisions.append('{"kind":"delegate","agentId":"<available agent>","input":"..."}')
            if any(message.get('role') == 'tool' and message.get('agentId') for message in messages):
                decisions.append('{"kind":"delegate","agentId":"<available agent>","inputFromAgent":"<completed agent>"}')
        elif tools:
            decisions.append('{"kind":"tool","toolId":"<available tool>","arguments":{...}}')
        if grant.subject_kind == 'AGENT' and any(agent.agent_id == grant.subject_id and agent.context_sources for agent in lease.agents):
            decisions.append('{"kind":"finish_context"}')
        guidance = {"instruction": "Return exactly one JSON object following a listed decision shape.",
                    "decisions": decisions, "agents": [{"id": item.agent_id, "description": item.description} for item in candidates],
                    "completedAgents": [message['agentId'] for message in messages if message.get('role') == 'tool' and message.get('agentId')],
                    "tools": [{"id": item.tool_id, "description": item.description, "inputSchema": item.input_schema} for item in tools]}
        conversation = [{"role": "system", "content": system_prompt + "\n" + json.dumps(guidance, allow_nan=False)}]
        for message in messages:
            # Encode tool results as data. The proxy accepts text messages and structured decisions.
            role = "assistant" if message["role"] == "assistant" else "user"
            conversation.append({"role": role, "content": json.dumps(message, allow_nan=False)})
        try:
            timeout = min(self.timeout, timeout_seconds)
            if timeout <= 0:
                raise ValueError("Invalid inference deadline")
            body = json.dumps({"messages": conversation}, allow_nan=False).encode()
            if len(body) > 98304:
                raise ValueError("Inference request exceeds limit")
            request = Request(self.origin + "/v1/chat/completions", body,
                              {"Authorization": "Bearer " + grant.token, "X-ToolGate-Run-Id": lease.run_id,
                               "Content-Type": "application/json", "Accept": "application/json"}, method="POST")
            with self._opener.open(request, timeout=timeout) as response:
                if response.status != 200 or response.headers.get_content_type() != "application/json":
                    raise ValueError("Invalid inference response")
                raw = response.read(98305)
                if len(raw) > 98304:
                    raise ValueError("Inference response exceeds limit")
                value = json.loads(raw, object_pairs_hook=_object, parse_constant=_constant)
                if not isinstance(value, dict):
                    raise ValueError("Invalid model decision")
                return value
        except HTTPError as failure:
            with failure:
                if failure.code in (401, 403):
                    raise Denied("Gateway refused inference") from None
            raise RunTransportError("Gateway inference failed") from None
        except Exception:
            raise RunTransportError("Gateway inference failed") from None

    def _mcp(self, lease, grant, method, timeout_seconds, *, tool_id=None, arguments=None):
        version = "2026-07-28"
        rpc_id = str(uuid.uuid4())
        params = {"_meta": {"io.modelcontextprotocol/protocolVersion": version,
                            "io.modelcontextprotocol/clientCapabilities": {"tools": {}},
                            "io.ololabs.toolgate/agentRunId": lease.run_id}}
        headers = {"Authorization": "Bearer " + grant.token, "X-ToolGate-Run-Id": lease.run_id,
                   "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": version, "MCP-Method": method}
        if tool_id is not None:
            params.update(name=tool_id, arguments=arguments)
            headers["MCP-Name"] = tool_id
        body = json.dumps({"jsonrpc": "2.0", "id": rpc_id, "method": method, "params": params}, allow_nan=False).encode()
        try:
            timeout = min(self.timeout, timeout_seconds)
            if len(body) > 98304 or timeout <= 0:
                raise ValueError("Invalid MCP request")
            request = Request(self.origin + "/mcp", body, headers, method="POST")
            with self._opener.open(request, timeout=timeout) as response:
                raw = response.read(98305)
                if response.status != 200 or len(raw) > 98304 or response.headers.get_content_type() != "application/json":
                    raise ValueError("Invalid MCP response")
                value = json.loads(raw, object_pairs_hook=_object, parse_constant=_constant)
                if value.get("jsonrpc") != "2.0" or value.get("id") != rpc_id:
                    raise ValueError("Invalid MCP reply identity")
                if isinstance(value.get("error"), dict) and value["error"].get("data", {}).get("state") == "OUTCOME_UNKNOWN":
                    return {"_meta": {"io.ololabs.toolgate/operationOutcome": {"state": "OUTCOME_UNKNOWN"}}}
                if not isinstance(value.get("result"), dict) or "error" in value:
                    raise ValueError("Invalid MCP result")
                return value["result"]
        except HTTPError as failure:
            with failure:
                if failure.code == 403 and method == "tools/list":
                    # Current model authority does not require an MCP execution binding.
                    # An empty catalog grants no tools; direct calls still fail closed.
                    return {"tools": []}
                if failure.code in (401, 403):
                    raise Denied("Gateway refused the tool request") from None
            raise RunTransportError("Gateway tool request failed") from None
        except Exception:
            raise RunTransportError("Gateway tool request failed") from None

    def discover_tools(self, lease, grant, *, timeout_seconds):
        result = self._mcp(lease, grant, "tools/list", timeout_seconds)
        tools = result.get("tools")
        if not isinstance(tools, list) or len(tools) > 64:
            raise RunTransportError("Invalid Gateway tool catalog")
        return tuple(Tool(item["name"], item.get("annotations", {}).get("readOnlyHint") is True,
                          item["description"], item["inputSchema"]) for item in tools)

    def call_tool(self, lease, grant, tool_id, arguments, *, timeout_seconds):
        result = self._mcp(lease, grant, "tools/call", timeout_seconds, tool_id=tool_id, arguments=arguments)
        outcome = result.get("_meta", {}).get("io.ololabs.toolgate/operationOutcome", {})
        structured = result.get("structuredContent")
        if isinstance(structured, dict) and structured.get("state") == "PENDING_APPROVAL" and result.get("isError") is True:
            return ToolOutcome("AWAITING_APPROVAL")
        if outcome.get("state") == "OUTCOME_UNKNOWN":
            return ToolOutcome("OUTCOME_UNKNOWN")
        if outcome.get("state") == "SUCCEEDED" and outcome.get("finalized") is True and not result.get("isError"):
            return ToolOutcome("SUCCEEDED", structured if structured is not None else result.get("content"))
        return ToolOutcome("FAILED")


class WorkflowGateway:
    """Bind the graph ports to the Control ledger and the Gateway runtime."""

    def __init__(self, control, gateway):
        self.authorize_step = control.authorize_step
        self.finish = control.finish
        self.infer = gateway.infer
        self.discover_tools = gateway.discover_tools
        self.call_tool = gateway.call_tool

    def discover_agents(self, lease, grant, *, timeout_seconds):
        if grant.subject_kind != "WORKFLOW":
            raise Denied("Agent discovery requires the workflow step")
        return tuple(Candidate(agent.agent_id) for agent in lease.agents)
