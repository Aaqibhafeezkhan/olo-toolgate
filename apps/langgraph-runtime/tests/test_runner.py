# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real LangGraph execution with a simulated ToolGate boundary, not server authorization qualification."""
from collections import deque
from dataclasses import replace
import os
import socket
import unittest
from unittest.mock import patch

from langsmith.run_helpers import get_tracing_context
from toolgate_runtime.runner import (Agent, Candidate, CompletionAck, CompletionUnconfirmed, ContextSource, Denied, Limits,
    OwnershipLost, RunLease, Runner, StepGrant, Tool, ToolOutcome)


def lease(prefix="support", mode="APPLY", *, limits=None):
    return RunLease("run-1", "lease-1", prefix, "version-1", "sha256:" + "a" * 64,
        "secret-lease-token", "Please investigate.", f"{prefix} supervisor prompt", f"{prefix}-supervisor-model",
        limits or Limits(30, 4, 60),
        (Agent(f"{prefix}-investigator", f"{prefix}-investigator-model", f"{prefix} investigator prompt",
               (ContextSource(f"{prefix}/context", {"source": prefix}),)),
         Agent(f"{prefix}-fixer", f"{prefix}-fixer-model", f"{prefix} fixer prompt")),
        remediation_mode=mode)


class Gate:
    def __init__(self, run):
        p = run.workflow_id
        self.decisions = {
            p: deque([{"kind": "delegate", "agentId": f"{p}-investigator", "input": "Investigate"},
                      {"kind": "delegate", "agentId": f"{p}-fixer", "input": "Prepare fix"},
                      {"kind": "finish", "result": {"answer": p}}]),
            f"{p}-investigator": deque([{"kind": "tool", "toolId": f"{p}/read", "arguments": {"id": 7}},
                                       {"kind": "finish", "result": "Findings"}]),
            f"{p}-fixer": deque([{"kind": "tool", "toolId": f"{p}/write", "arguments": {"fix": "proposed"}},
                                {"kind": "finish", "result": "Fix ready"}])}
        self.catalogs = {f"{p}-investigator": (Tool(f"{p}/context", True), Tool(f"{p}/read", True)),
                         f"{p}-fixer": (Tool(f"{p}/write", False),)}
        self.agent_catalog = tuple(Candidate(a.agent_id, f"{a.agent_id} capabilities") for a in run.agents)
        self.candidate_requests = []
        self.authorizations, self.inferences, self.calls, self.finishes = [], [], [], []
        self.tool_outcome = ToolOutcome("SUCCEEDED", {"observation": "untrusted tool content"})
        self.refuse_tool = self.lose_ownership = self.fail_finish = self.mismatch = False
        self.revoke_after_discovery = False
        self.tracing = []

    def authorize_step(self, run, kind, subject, model, *, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        if self.lose_ownership:
            raise OwnershipLost("sensitive remote body secret-lease-token")
        self.authorizations.append((kind, subject, model))
        grant = StepGrant(run.run_id, run.lease_id, kind, subject, model, "secret-step-token")
        return replace(grant, run_id="foreign") if self.mismatch else grant

    def assert_request(self, run, timeout):
        assert run.lease_token == "secret-lease-token"
        assert 0 < timeout <= 60
        self.tracing.append(get_tracing_context()["enabled"])

    def discover_agents(self, run, grant, *, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        return self.agent_catalog

    def discover_tools(self, run, grant, *, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        if self.revoke_after_discovery:
            self.refuse_tool = True
        return self.catalogs.get(grant.subject_id, ())

    def infer(self, run, grant, *, system_prompt, messages, tools, candidates, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        self.candidate_requests.append((grant.subject_id, candidates))
        self.inferences.append((grant.subject_id, grant.model_group_id, system_prompt, messages, tuple(t.tool_id for t in tools)))
        return self.decisions[grant.subject_id].popleft()

    def call_tool(self, run, grant, tool_id, arguments, *, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        if self.refuse_tool:
            raise Denied("sensitive remote body secret-step-token")
        self.calls.append((grant.subject_id, tool_id, arguments))
        return self.tool_outcome

    def finish(self, run, outcome, *, timeout_seconds):
        self.assert_request(run, timeout_seconds)
        if self.fail_finish:
            raise RuntimeError("sensitive remote body secret-lease-token")
        self.finishes.append(outcome)
        return CompletionAck(run.run_id, run.lease_id, outcome.state)


class RunnerTests(unittest.TestCase):
    def execute(self, run=None, customize=None, **kwargs):
        run = run or lease()
        gate = Gate(run)
        if customize:
            customize(gate)
        result = Runner(gate, **kwargs).execute(run)
        return result, gate

    def test_two_profiles_use_unchanged_graph_with_dedicated_models_prompts_context_and_tools(self):
        for prefix in ("support", "database"):
            with self.subTest(profile=prefix):
                result, gate = self.execute(lease(prefix))
                self.assertEqual(result.state, "SUCCEEDED")
                self.assertEqual(result.result, {"answer": prefix})
                self.assertEqual(gate.finishes, [result])
                self.assertEqual(len(gate.calls), 3)
                self.assertEqual(gate.calls[0][1], f"{prefix}/context")
                self.assertEqual({x[1] for x in gate.inferences},
                    {f"{prefix}-supervisor-model", f"{prefix}-investigator-model", f"{prefix}-fixer-model"})
                for subject, model, prompt, messages, tools in gate.inferences:
                    role = f"{prefix}-supervisor" if subject == prefix else subject
                    self.assertEqual(model, f"{role}-model")
                    self.assertEqual(prompt, f"{role.replace('-', ' ')} prompt")
                    self.assertNotIn("secret-", str(messages))
                    if subject == prefix:
                        self.assertEqual(tools, ())
                    elif subject.endswith("investigator"):
                        self.assertNotIn(f"{prefix}/write", tools)
                    else:
                        self.assertNotIn(f"{prefix}/read", tools)
                investigator = next(i for i in gate.inferences if i[0].endswith("investigator"))
                self.assertEqual(investigator[3][1]["toolId"], f"{prefix}/context")
                self.assertGreater(len(gate.authorizations), len(gate.inferences))

    def test_current_agent_discovery_narrows_the_pinned_snapshot(self):
        run = lease(); gate = Gate(run)
        gate.agent_catalog = (Candidate("support-investigator", "investigation only"),)
        result = Runner(gate).execute(run)
        self.assertEqual(result.code, "AGENT_NOT_AVAILABLE")
        self.assertFalse(any(c[0] == "support-fixer" for c in gate.calls))
        for subject, candidates in gate.candidate_requests:
            if subject == "support":
                self.assertEqual([c.agent_id for c in candidates], ["support-investigator"])
            else:
                self.assertEqual(candidates, ())

    def test_foreign_or_duplicate_agent_catalog_is_not_sent_to_model(self):
        for catalog in ((Candidate("foreign", "secret"),),
                        (Candidate("support-investigator"), Candidate("support-investigator"))):
            result, gate = self.execute(customize=lambda g: setattr(g, "agent_catalog", catalog))
            self.assertEqual(result.code, "INVALID_AGENT_CATALOG")
            self.assertFalse(gate.inferences)
            self.assertFalse(gate.calls)

    def test_unknown_agent_and_non_entry_agent_never_receive_authority(self):
        for target, agents in (("foreign", lease().agents), ("support-fixer", (lease().agents[0], replace(lease().agents[1], entry_eligible=False)))):
            run = replace(lease(), agents=agents)
            def change(g):
                g.decisions["support"] = deque([{"kind": "delegate", "agentId": target, "input": "payload"}])
            result, gate = self.execute(run, change)
            self.assertEqual(result.code, "AGENT_NOT_AVAILABLE")
            self.assertTrue(all(a[0] == "WORKFLOW" for a in gate.authorizations))
            self.assertFalse(gate.calls)

    def test_delegation_filters_and_handoff_bound_are_not_widened(self):
        run = replace(lease(), agents=(lease().agents[0], replace(lease().agents[1], delegation_allowed=False)))
        result, gate = self.execute(run)
        self.assertEqual(result.code, "AGENT_NOT_AVAILABLE")
        self.assertFalse(any(c[0] == "support-fixer" for c in gate.calls))
        result, gate = self.execute(replace(lease(), limits=Limits(30, 1, 60)))
        self.assertEqual(result.code, "HANDOFF_LIMIT")

    def test_unlisted_tool_and_model_override_are_rejected_before_effect(self):
        for decision in ({"kind": "tool", "toolId": "other/write", "arguments": {}},
                         {"kind": "finish", "result": "ok", "model": "unapproved"}):
            def change(g):
                g.decisions["support-investigator"] = deque([decision])
            result, gate = self.execute(customize=change)
            self.assertIn(result.code, ("TOOL_NOT_AVAILABLE", "INVALID_MODEL_DECISION"))
            self.assertEqual([c[1] for c in gate.calls], ["support/context"])

    def test_prepare_and_context_retrieval_cannot_mutate(self):
        result, gate = self.execute(lease(mode="PREPARE"))
        self.assertEqual(result.code, "MUTATION_NOT_ALLOWED")
        self.assertFalse(any(c[1].endswith("/write") for c in gate.calls))
        result, gate = self.execute(customize=lambda g: g.catalogs.update({"support-investigator": (Tool("support/context", False),)}))
        self.assertEqual(result.code, "MUTATION_NOT_ALLOWED")
        self.assertFalse(gate.calls)

    def test_approval_denial_unknown_and_failed_tool_outcomes_stop_without_retry(self):
        for tool_state, expected_state, code in (("AWAITING_APPROVAL", "AWAITING_APPROVAL", "APPROVAL_REQUIRED"),
                ("DENIED", "FAILED", "DENIED"), ("OUTCOME_UNKNOWN", "FAILED", "TOOL_OUTCOME_UNKNOWN"), ("FAILED", "FAILED", "TOOL_FAILED")):
            with self.subTest(outcome=tool_state):
                result, gate = self.execute(customize=lambda g: setattr(g, "tool_outcome", ToolOutcome(tool_state)))
                self.assertEqual((result.state, result.code), (expected_state, code))
                self.assertEqual(len(gate.calls), 1)
                self.assertEqual(len(gate.inferences), 1)

    def test_revocation_between_discovery_and_call_is_not_overridden(self):
        result, gate = self.execute(customize=lambda g: setattr(g, "revoke_after_discovery", True))
        self.assertEqual(result.code, "DENIED")
        self.assertFalse(gate.calls)
        self.assertNotIn("secret", str(result))

    def test_mismatched_step_scope_and_lost_ownership_stop_without_more_calls(self):
        for field, value in (("run_id", "foreign"), ("lease_id", "foreign"),
                             ("subject_kind", "AGENT"), ("subject_id", "foreign"),
                             ("model_group_id", "foreign"), ("token", ""), ("token", 1)):
            run = lease(); gate = Gate(run); original = gate.authorize_step
            def mismatch(*args, **kwargs):
                return replace(original(*args, **kwargs), **{field: value})
            gate.authorize_step = mismatch
            result = Runner(gate).execute(run)
            self.assertEqual(result.code, "GRANT_SCOPE_MISMATCH")
            self.assertFalse(gate.inferences)
        run = lease(); gate = Gate(run); gate.lose_ownership = True
        with self.assertRaisesRegex(OwnershipLost, "^Run ownership was lost$"):
            Runner(gate).execute(run)
        self.assertFalse(gate.finishes)
        self.assertFalse(gate.calls)

    def test_completion_failure_is_not_reported_as_success_or_retried(self):
        run = lease(); gate = Gate(run); gate.fail_finish = True
        with self.assertRaisesRegex(CompletionUnconfirmed, "^ToolGate did not confirm run completion$"):
            Runner(gate).execute(run)
        self.assertEqual(len(gate.calls), 3)
        self.assertFalse(gate.finishes)

    def test_absent_or_foreign_completion_ack_never_reports_success(self):
        for ack in (None, False, CompletionAck("foreign", "lease-1", "SUCCEEDED"),
                    CompletionAck("run-1", "lease-1", "FAILED")):
            run = lease(); gate = Gate(run)
            gate.finish = lambda *args, **kwargs: ack
            with self.assertRaises(CompletionUnconfirmed):
                Runner(gate).execute(run)
            self.assertEqual(len(gate.calls), 3)

    def test_step_and_time_limits_stop_the_graph(self):
        result, gate = self.execute(replace(lease(), limits=Limits(1, 1, 60)))
        self.assertEqual(result.code, "STEP_LIMIT")
        self.assertEqual(len(gate.inferences), 1)
        class Clock:
            value = 0
            def __call__(self): return self.value
        clock = Clock(); run = lease(); gate = Gate(run)
        original = gate.infer
        def delayed(*args, **kwargs):
            decision = original(*args, **kwargs)
            clock.value = 61
            return decision
        gate.infer = delayed
        result = Runner(gate, clock=clock).execute(run)
        self.assertEqual(result.code, "TIME_LIMIT")
        self.assertFalse(gate.calls)

    def test_malformed_limits_and_large_or_non_json_results_fail_closed(self):
        for limits in (Limits(0, 0, 60), Limits(True, 0, 60), Limits(1, 2, 60), Limits(10, 1, 0)):
            result, gate = self.execute(replace(lease(), limits=limits))
            self.assertEqual(result.code, "INVALID_LIMITS")
            self.assertFalse(gate.authorizations)
        for value, code in ((float("nan"), "INVALID_JSON"), ("x" * 100000, "VALUE_TOO_LARGE")):
            def change(g): g.decisions["support"] = deque([{"kind": "finish", "result": value}])
            result, gate = self.execute(customize=change)
            self.assertEqual(result.code, code)

    def test_credentials_are_not_graph_state_or_external_tracing_content(self):
        run = lease(); gate = Gate(run)
        with patch.dict(os.environ, {"LANGSMITH_TRACING": "true", "LANGCHAIN_TRACING_V2": "true"}), \
             patch.object(socket, "create_connection", side_effect=AssertionError("No direct egress")):
            result = Runner(gate).execute(run)
        self.assertEqual(result.state, "SUCCEEDED")
        self.assertTrue(all(t is False for t in gate.tracing), gate.tracing)
        self.assertNotIn("secret-lease-token", repr(run))
        self.assertNotIn("secret-step-token", repr(StepGrant("run", "lease", "AGENT", "a", "m", "secret-step-token")))


    def test_ambiguous_mutation_transport_failure_is_unknown_and_never_retried(self):
        run = lease(); gate = Gate(run)
        original = gate.call_tool
        def uncertain(*args, **kwargs):
            value = original(*args, **kwargs)
            if args[2].endswith("/write"):
                raise TimeoutError("sensitive remote body secret-step-token")
            return value
        gate.call_tool = uncertain
        result = Runner(gate).execute(run)
        self.assertEqual((result.state, result.code), ("FAILED", "TOOL_OUTCOME_UNKNOWN"))
        self.assertEqual(sum(c[1].endswith("/write") for c in gate.calls), 1)
        self.assertNotIn("secret", repr(result))

    def test_model_denial_and_transport_errors_do_not_leak_error_content(self):
        for error, code in ((Denied("secret-step-token"), "DENIED"), (TimeoutError("secret-step-token"), "RUNTIME_ERROR")):
            run = lease(); gate = Gate(run)
            def refuse(*args, **kwargs): raise error
            gate.infer = refuse
            result = Runner(gate).execute(run)
            self.assertEqual(result.code, code)
            self.assertFalse(gate.calls)
            self.assertNotIn("secret", repr(result))

    def test_history_limit_and_repeated_handoffs_cannot_run_forever(self):
        run = lease(); gate = Gate(run)
        gate.decisions["support-investigator"] = deque([{"kind": "finish", "result": "x" * 60000}])
        gate.decisions["support-fixer"] = deque([{"kind": "finish", "result": "y" * 60000}])
        result = Runner(gate).execute(run)
        self.assertEqual(result.code, "VALUE_TOO_LARGE")
        self.assertEqual(len(gate.calls), 1)

    def test_repeated_model_tool_decisions_exhaust_the_step_budget(self):
        run = replace(lease(), limits=Limits(5, 2, 60)); gate = Gate(run)
        gate.decisions["support-investigator"] = deque([
            {"kind": "tool", "toolId": "support/read", "arguments": {}} for _ in range(20)])
        result = Runner(gate).execute(run)
        self.assertEqual(result.code, "STEP_LIMIT")
        self.assertEqual(len(gate.calls), 2)
        self.assertEqual(gate.finishes, [result])

    def test_tool_arguments_are_copied_before_untrusted_adapter_access(self):
        run = lease(); gate = Gate(run)
        original = gate.call_tool
        def mutating_adapter(*args, **kwargs):
            args[3]["changed"] = True
            return original(*args, **kwargs)
        gate.call_tool = mutating_adapter
        result = Runner(gate).execute(run)
        self.assertEqual(result.state, "SUCCEEDED")
        self.assertEqual(run.agents[0].context_sources[0].arguments, {"source": "support"})

if __name__ == "__main__":
    unittest.main()
