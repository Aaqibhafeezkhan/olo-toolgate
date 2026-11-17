# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Worker observations cannot substitute a terminal status for actual adapter calls."""
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import threading
from unittest.mock import Mock

from toolgate_runtime.runner import Denied, RunOutcome, StepGrant, ToolOutcome
from toolgate_runtime.worker import ExecutionEvidence, WorkerPresence, log_result
from toolgate_runtime.run_transport import RunTransportError


class ExecutionEvidenceTests(unittest.TestCase):
    def test_presence_renews_while_execution_is_busy_without_claiming_another_run(self):
        control=Mock();renewed=threading.Event()
        control.heartbeat.side_effect=lambda:renewed.set() if control.heartbeat.call_count>1 else None
        presence=WorkerPresence(control,interval=.01)
        try:
            presence.start()
            self.assertTrue(renewed.wait(1),'busy execution must not stop capability renewal')
            presence.require_current()
            control.claim.assert_not_called()
        finally:presence.close()
        self.assertFalse(presence.thread.is_alive())

    def test_presence_failure_stops_new_claims_without_exposing_the_error(self):
        control=Mock();control.heartbeat.side_effect=[None,RunTransportError('private credential details')]
        presence=WorkerPresence(control,interval=.01)
        try:
            presence.start();self.assertTrue(presence.failed.wait(1))
            with self.assertRaisesRegex(RunTransportError,'^Worker presence is unavailable$'):presence.require_current()
            control.claim.assert_not_called()
        finally:presence.close()

    def setUp(self):
        self.adapter = Mock()
        self.evidence = ExecutionEvidence(self.adapter)
        self.grant = StepGrant('run-1', 'lease-1', 'AGENT', 'agent-1', 'models', 'private-token')

    def test_observations_exclude_sensitive_inputs_and_outputs(self):
        decision = {'kind': 'finish', 'result': 'private-model-result'}
        self.adapter.infer.return_value = decision
        outcome = ToolOutcome('SUCCEEDED', 'private-tool-result')
        self.adapter.call_tool.return_value = outcome
        self.assertIs(decision, self.evidence.infer(None, self.grant, system_prompt='private-prompt'))
        self.assertIs(outcome, self.evidence.call_tool(None, self.grant, 'files/list', {'private': 'argument'}))
        self.assertEqual({'inferences': [{'subjectKind': 'AGENT', 'subjectId': 'agent-1'}],
                          'tools': [{'subjectKind': 'AGENT', 'subjectId': 'agent-1',
                                     'toolId': 'files/list', 'state': 'SUCCEEDED'}]}, self.evidence.summary())
        self.assertNotIn('private', json.dumps(self.evidence.summary()))

    def test_failed_inference_does_not_count_as_a_model_response(self):
        self.adapter.infer.side_effect = OSError('private-error')
        with self.assertRaises(OSError):
            self.evidence.infer(None, self.grant)
        self.assertEqual([], self.evidence.summary()['inferences'])

    def test_unknown_and_denied_calls_remain_unsuccessful_without_retries(self):
        for failure, state in [(OSError('private-error'), 'OUTCOME_UNKNOWN'), (Denied('private-error'), 'DENIED')]:
            with self.subTest(state=state):
                self.adapter.call_tool.reset_mock()
                self.adapter.call_tool.side_effect = failure
                with self.assertRaises(type(failure)):
                    self.evidence.call_tool(None, self.grant, 'files/list', {})
                self.assertEqual(state, self.evidence.summary()['tools'][-1]['state'])
                self.adapter.call_tool.assert_called_once()
                self.assertNotIn('private', json.dumps(self.evidence.summary()))

    def test_evidence_is_scoped_to_each_run(self):
        self.adapter.infer.return_value = {'kind': 'finish', 'result': {}}
        self.evidence.infer(None, self.grant)
        self.assertEqual({'inferences': [], 'tools': []}, ExecutionEvidence(self.adapter).summary())

    def test_explicit_result_logging_is_private_and_never_overwrites_a_run(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder) / 'results'
            lease = SimpleNamespace(run_id='run-1', workflow_id='triage', graph={'packageId':'example.graph','version':'1.0.0','digest':'sha256:'+'a'*64,'entryPoint':'run'})
            log_result(directory, lease, RunOutcome('SUCCEEDED', {'issueId': 'issue-123'}))
            path = directory / 'run-1.json'
            self.assertEqual({'issueId': 'issue-123'}, json.loads(path.read_text())['result'])
            if os.name != 'nt':
                self.assertEqual(0o600, path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):
                log_result(directory, lease, RunOutcome('FAILED'))
            self.assertEqual('SUCCEEDED', json.loads(path.read_text())['state'])
            with self.assertRaises(ValueError):
                log_result(directory, SimpleNamespace(run_id='../outside', workflow_id='triage'), RunOutcome('FAILED'))
