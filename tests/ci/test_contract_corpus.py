# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Catch missing language round-trip coverage before expensive builds."""
import subprocess
import unittest
from unittest.mock import patch

from tools.ci import preflight
from tools.contracts import fixtures


class ContractCorpusTests(unittest.TestCase):
    def test_language_inventories_and_corpus_match_all_canonical_models(self):
        for path, expected in fixtures.outputs().items():
            with self.subTest(path=str(path.relative_to(fixtures.ROOT))):
                self.assertEqual(expected, path.read_text(encoding='utf-8'))

    def test_fixture_drift_blocks_preflight_and_explains_the_repair(self):
        results = [subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0),
                   subprocess.CompletedProcess([], 1)]
        with patch.dict('os.environ', {'GITHUB_REF': 'refs/heads/fixture-check'}), \
             patch.object(preflight.subprocess, 'run', side_effect=results) as run:
            self.assertEqual(1, preflight.main())
        self.assertEqual('fixtures.py', run.call_args.args[0][1].split('/')[-1].split('\\')[-1])
        self.assertEqual('--check', run.call_args.args[0][-1])
