# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import json
from email.message import Message
from io import BytesIO
from urllib.error import HTTPError
import unittest

from toolgate_runtime.run_transport import ControlRuns, GatewayRuntime, RunTransportError
from toolgate_runtime.runner import Candidate, Denied, RunOutcome, StepGrant


class Response(BytesIO):
    def __init__(self, status, value):
        super().__init__(json.dumps(value).encode())
        self.status = status
        self.headers = Message()
        self.headers["Content-Type"] = "application/json"


class RunTransportTests(unittest.TestCase):
    def test_model_only_agents_get_no_tools_and_calls_still_refuse(self):
        self.responses.append(Response(200, self.snapshot))
        lease = self.control.claim()
        gateway = GatewayRuntime('https://gateway.example')
        grant = StepGrant('run-1', 'lease-1', 'AGENT', 'summary-agent', 'models', 'y' * 43)
        def refused(request, timeout):
            raise HTTPError(request.full_url, 403, 'Forbidden', {}, BytesIO(b'private response'))
        gateway._opener = type('Opener', (), {'open': staticmethod(refused)})()
        self.assertEqual((), gateway.discover_tools(lease, grant, timeout_seconds=4))
        with self.assertRaises(Denied):
            gateway.call_tool(lease, grant, 'files/write', {}, timeout_seconds=4)
        with self.assertRaises(Denied):
            gateway.infer(lease, grant, system_prompt='Summary', messages=[], tools=(), candidates=(), timeout_seconds=4)

    def setUp(self):
        self.control = ControlRuns("https://control.example", lambda: "workload.credential")
        self.control._opener = self
        self.requests = []
        self.snapshot = {
            "runId": "run-1", "leaseId": "lease-1", "workflowId": "workflow-1", "workflowVersionId": "v1",
            "profileDigest": "sha256:" + "1" * 64, "leaseToken": "x" * 43, "message": "test",
            "compilerDigest": "sha256:" + "2" * 64,
            "graph": {'packageId': 'toolgate.supervisor', 'version': '1.0.0', 'digest': 'sha256:d6bbacb512d4425dc6b0ae09c17cd3fbd776cb0a8ebdf4d770362e7d464a13cd', 'entryPoint': 'supervisor', 'config': {}}, "supervisorPrompt": "Delegate", "supervisorModelGroupId": "models", "contextSources": [], "agentStages": [],
            "limits": {"maxSteps": 4, "maxHandoffs": 1, "maxDurationSeconds": 20},
            "agents": [{"agentId": "agent-1", "modelGroupId": "models", "systemPrompt": "Handle",
                        "contextSources": [], "entryEligible": True, "delegationAllowed": True}],
            "remediationMode": "PREPARE", "expiresAtUnixMs": 1,
        }
        self.responses = []

    def open(self, request, timeout):
        self.requests.append((request.full_url, request.get_header("Authorization"), json.loads(request.data), timeout))
        return self.responses.pop(0)

    def test_claim_and_finish_use_the_workload_credential_and_lease(self):
        self.responses.extend([Response(200, self.snapshot), Response(200, {"runId": "run-1", "leaseId": "lease-1", "state": "SUCCEEDED"})])
        lease = self.control.claim()
        self.assertEqual("agent-1", lease.agents[0].agent_id)
        self.assertEqual(self.snapshot["graph"], lease.graph)
        ack = self.control.finish(lease, RunOutcome("SUCCEEDED"), timeout_seconds=5)
        self.assertEqual("SUCCEEDED", ack.state)
        self.assertEqual([
            ("https://control.example/internal/v1/runtime/claim", "Bearer workload.credential", {}, 10),
            ("https://control.example/internal/v1/runtime/runs/run-1/finish", "Bearer workload.credential",
             {"leaseId": "lease-1", "leaseToken": "x" * 43, "state": "SUCCEEDED", "result": None, "code": None}, 5),
        ], self.requests)

    def test_empty_queue_and_unexpected_snapshot(self):
        self.responses.extend([Response(204, None), Response(200, dict(self.snapshot, modelOrigin="https://model.example"))])
        self.assertIsNone(self.control.claim())
        with self.assertRaisesRegex(RunTransportError, "Invalid Control run snapshot"):
            self.control.claim()

    def test_graph_less_or_malformed_snapshots_are_not_current_leases(self):
        for graph in (None, {}, {**self.snapshot["graph"], "entryPoint": "../../source.py"}):
            self.responses.append(Response(200, {**self.snapshot, "graph": graph}))
            with self.assertRaisesRegex(RunTransportError, "Invalid Control run snapshot"):
                self.control.claim()
        obsolete = dict(self.snapshot)
        del obsolete["graph"]
        self.responses.append(Response(200, obsolete))
        with self.assertRaisesRegex(RunTransportError, "Invalid Control run snapshot"):
            self.control.claim()

    def test_pinned_stages_are_parsed_and_obsolete_or_extended_snapshots_refused(self):
        self.snapshot['agentStages'] = [{'agentId':'agent-1','resultMode':'MODEL'}]
        self.responses.append(Response(200,self.snapshot))
        lease = self.control.claim()
        self.assertEqual(('agent-1','MODEL'),(lease.agent_stages[0].agent_id,lease.agent_stages[0].result_mode))
        obsolete = dict(self.snapshot)
        obsolete.pop('agentStages')
        self.responses.append(Response(200,obsolete))
        with self.assertRaisesRegex(RunTransportError,'Invalid Control run snapshot'):
            self.control.claim()
        extended = dict(self.snapshot,agentStages=[{'agentId':'agent-1','resultMode':'MODEL','code':'untrusted'}])
        self.responses.append(Response(200,extended))
        with self.assertRaisesRegex(RunTransportError,'Invalid Control run snapshot'):
            self.control.claim()

    def test_step_authorization_is_bound_to_the_claimed_run(self):
        self.responses.extend([Response(200, self.snapshot), Response(200, {"runId": "run-1", "leaseId": "lease-1",
            "subjectKind": "AGENT", "subjectId": "agent-1", "modelGroupId": "models", "token": "y" * 43})])
        lease = self.control.claim()
        grant = self.control.authorize_step(lease, "AGENT", "agent-1", "models", timeout_seconds=4)
        self.assertEqual("y" * 43, grant.token)
        self.assertEqual(("https://control.example/internal/v1/runtime/runs/run-1/steps:authorize", "Bearer workload.credential",
                          {"leaseId": "lease-1", "leaseToken": "x" * 43, "subjectKind": "AGENT", "subjectId": "agent-1",
                           "modelGroupId": "models"}, 4), self.requests[-1])

    def test_inference_sends_step_identity_and_messages_to_the_gateway(self):
        self.responses.extend([Response(200, self.snapshot), Response(200, {"kind": "delegate", "agentId": "agent-1", "input": "test"})])
        lease = self.control.claim()
        gateway = GatewayRuntime("https://gateway.example")
        gateway._opener = self
        grant = StepGrant("run-1", "lease-1", "WORKFLOW", "workflow-1", "models", "y" * 43)
        decision = gateway.infer(lease, grant, system_prompt="Delegate", messages=[{"role": "user", "content": "test"}],
                                 tools=(), candidates=(Candidate("agent-1"),), timeout_seconds=4)
        self.assertEqual("agent-1", decision["agentId"])
        url, auth, payload, timeout = self.requests[-1]
        self.assertEqual("https://gateway.example/v1/chat/completions", url)
        self.assertEqual("Bearer " + "y" * 43, auth)
        self.assertEqual(4, timeout)
        self.assertEqual({"messages"}, payload.keys())
        self.assertIn("agent-1", payload["messages"][0]["content"])

    def test_tool_requests_use_gateway_mcp_and_keep_unknown_outcomes(self):
        self.responses.append(Response(200, self.snapshot))
        lease = self.control.claim()
        gateway = GatewayRuntime("https://gateway.example")
        grant = StepGrant("run-1", "lease-1", "AGENT", "agent-1", "models", "y" * 43)

        def open_mcp(request, timeout):
            self.assertEqual("https://gateway.example/mcp", request.full_url)
            self.assertEqual("Bearer " + "y" * 43, request.get_header("Authorization"))
            self.assertEqual("run-1", request.get_header("X-toolgate-run-id"))
            body = json.loads(request.data)
            reply = {"jsonrpc": "2.0", "id": body["id"]}
            if body["method"] == "tools/list":
                reply["result"] = {"tools": [{"name": "files/read", "description": "Read", "inputSchema": {},
                                             "annotations": {"readOnlyHint": True}}]}
            else:
                self.assertEqual("files/read", body["params"]["name"])
                reply["error"] = {"data": {"state": "OUTCOME_UNKNOWN"}}
            return Response(200, reply)

        gateway._opener = type("Opener", (), {"open": staticmethod(open_mcp)})()
        self.assertTrue(gateway.discover_tools(lease, grant, timeout_seconds=4)[0].read_only)
        self.assertEqual("OUTCOME_UNKNOWN", gateway.call_tool(lease, grant, "files/read", {}, timeout_seconds=4).state)
