# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Acceptance must see the configured agent/tool and stable duplicate admission."""
from copy import deepcopy
import unittest
from unittest.mock import Mock

from toolgate_runtime.trigger_bridge import DispatchReceipt
import workflow_e2e as fixture


class WorkflowAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.result = {'runId': 'run-1', 'workflowId': fixture.WORKFLOW, 'state': 'SUCCEEDED',
                       'execution': {'inferences': [
                           {'subjectKind': 'WORKFLOW', 'subjectId': fixture.WORKFLOW},
                           {'subjectKind': 'AGENT', 'subjectId': fixture.AGENT}],
                           'tools': [{'subjectKind': 'AGENT', 'subjectId': fixture.AGENT,
                                      'toolId': 'examples.hotfolder/read_text', 'state': 'SUCCEEDED'}]}}

    def test_accepts_configured_agent_and_confirmed_tool(self):
        fixture.verify_execution(self.result, 'run-1')

    def test_rejects_terminal_only_foreign_agent_and_uncertain_tools(self):
        variants = []
        for key, value in [('execution', {}), ('runId', 'other-run'), ('state', 'FAILED'), ('execution', None)]:
            variants.append({**self.result, key: value})
        for key in ('inferences', 'tools'):
            result = deepcopy(self.result)
            result['execution'][key] = []
            variants.append(result)
        for key, value in [('state', 'OUTCOME_UNKNOWN'), ('subjectId', 'other-agent'), ('toolId', 'other/tool')]:
            result = deepcopy(self.result)
            result['execution']['tools'][0][key] = value
            variants.append(result)
        for result in variants:
            with self.subTest(result=result), self.assertRaises(ValueError):
                fixture.verify_execution(result, 'run-1')

    def test_duplicate_must_retain_all_admission_coordinates(self):
        receipt = DispatchReceipt('subscription', '0000000000000001', 'admission-1', 'ADMITTED', 'run-1')
        transport = Mock()
        transport.admit.return_value = DispatchReceipt('subscription', receipt.cursor, 'admission-1', 'DUPLICATE', 'run-1')
        fixture.verify_duplicate(transport, receipt)
        for change in ({'admission_id': 'other'}, {'run_id': 'other'}, {'disposition': 'ADMITTED'}, {'cursor': '0000000000000002'}):
            transport.admit.return_value = DispatchReceipt(**{**receipt.__dict__, 'disposition': 'DUPLICATE', **change})
            with self.subTest(change=change), self.assertRaises(ValueError):
                fixture.verify_duplicate(transport, receipt)
