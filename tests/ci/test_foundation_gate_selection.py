# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Shared gate selection must execute current Quickstart tests locally and in CI."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('foundation_check',ROOT/'tools/check.py')
check=importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class FoundationGateSelectionTests(unittest.TestCase):
    def selected(self, *arguments):
        with patch.object(sys,'argv',['check.py',*arguments]), patch.object(check,'helm_checks'), patch.object(
                check,'run',return_value=subprocess.CompletedProcess([],0)) as run, contextlib.redirect_stdout(io.StringIO()):
            check.main()
        return [call for call in run.call_args_list if 'tests/quickstart' in call.args[0]]

    def test_python_group_runs_current_quickstart_tests_once_with_import_path(self):
        with patch.dict(os.environ,{'PYTHONPATH':'existing-import-path'}):
            calls=self.selected('--ci-common','--ci-part','python')
            self.assertEqual(1,len(calls))
            self.assertEqual(['-m','unittest','discover','-s','tests/quickstart','-v'],calls[0].args[0][1:])
            self.assertEqual(str(ROOT/'apps/quickstart')+os.pathsep+'existing-import-path',
                             calls[0].kwargs['env']['PYTHONPATH'])
            self.assertEqual('existing-import-path',os.environ['PYTHONPATH'])

    def test_rust_group_does_not_select_python_runtime_tests(self):
        self.assertEqual([],self.selected('--ci-common','--ci-part','rust'))

    def test_contract_only_mode_does_not_select_runtime_tests(self):
        self.assertEqual([],self.selected('--ci-common','--ci-part','python','--contracts-only'))
