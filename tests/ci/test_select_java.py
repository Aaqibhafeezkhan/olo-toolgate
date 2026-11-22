# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.ci import local, select_java


class SelectJavaTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.home = self.root / 'jdk with spaces'
        (self.home / 'bin').mkdir(parents=True)
        suffix = '.exe' if os.name == 'nt' else ''
        for tool in ('java', 'javac'):
            (self.home / 'bin' / (tool + suffix)).touch()
        self.env_file = self.root / 'env'
        self.path_file = self.root / 'path'
        self.environment = {
            'JAVA_HOME_21_X64': str(self.home), 'JAVA_HOME': str(self.root / 'wrong-default'),
            'GITHUB_ENV': str(self.env_file), 'GITHUB_PATH': str(self.path_file),
        }
        self.results = [subprocess.CompletedProcess([], 0, '    java.specification.version = 21\n'),
                        subprocess.CompletedProcess([], 0, 'javac 21.0.9\n')]

    def select(self):
        with patch.dict(os.environ, self.environment, clear=True), \
                patch.object(select_java.subprocess, 'run', side_effect=self.results) as run:
            select_java.main()
        return run

    def test_selects_versioned_jdk_and_exports_after_validation(self):
        run = self.select()
        self.assertEqual(self.env_file.read_text(), f'JAVA_HOME={self.home.resolve()}\n')
        self.assertEqual(self.path_file.read_text(), f'{self.home.resolve() / "bin"}\n')
        self.assertEqual(run.call_count, 2)
        for call in run.call_args_list:
            self.assertTrue(Path(call.args[0][0]).is_relative_to(self.home))
            self.assertTrue(call.kwargs['check'])
            self.assertEqual(call.kwargs['timeout'], 30)

    def test_local_replay_can_supply_java_home(self):
        self.environment.pop('JAVA_HOME_21_X64')
        self.environment['JAVA_HOME'] = str(self.home)
        self.select()
        self.assertTrue(self.env_file.exists())

    def test_missing_jdk_does_not_export(self):
        for location in ('', str(self.root / 'missing'), str(self.home) + '\nINJECT=value'):
            with self.subTest(location=location):
                self.environment['JAVA_HOME_21_X64'] = location
                self.environment.pop('JAVA_HOME', None)
                with self.assertRaises(SystemExit):
                    self.select()
                self.assertFalse(self.env_file.exists())
                self.assertFalse(self.path_file.exists())

    def test_wrong_runtime_or_compiler_does_not_export(self):
        for runtime, compiler in (('java.specification.version = 17\n', 'javac 21\n'),
                                  ('java.specification.version = 21\n', 'javac 25\n')):
            with self.subTest(runtime=runtime, compiler=compiler):
                self.results = [subprocess.CompletedProcess([], 0, runtime),
                                subprocess.CompletedProcess([], 0, compiler)]
                with self.assertRaisesRegex(SystemExit, 'must report Java 21'):
                    self.select()
                self.assertFalse(self.env_file.exists())

    def test_broken_or_stalled_java_does_not_export(self):
        for error in (subprocess.CalledProcessError(1, 'java'),
                      subprocess.TimeoutExpired('java', 30), OSError('not executable')):
            with self.subTest(error=error):
                self.results = error
                with self.assertRaises(type(error)):
                    self.select()
                self.assertFalse(self.env_file.exists())

    def test_workflows_do_not_download_java_setup_action(self):
        workflows = local.load_workflows()
        selections = 0
        for workflow in workflows.values():
            for job in workflow['jobs'].values():
                steps = job.get('steps', [])
                for index, step in enumerate(steps):
                    self.assertFalse(step.get('uses', '').startswith('actions/setup-java@'))
                    if step.get('run') != 'python3 tools/ci/select_java.py':
                        continue
                    selections += 1
                    self.assertTrue(any(previous.get('uses', '').startswith('actions/checkout@')
                                        for previous in steps[:index]))
                    if job['runs-on'] != 'ubuntu-24.04':
                        self.assertEqual(step['if'], "inputs.run_tests && matrix.target == 'x86_64-unknown-linux-gnu'")
        self.assertGreater(selections, 0)
        trigger = workflows['foundation']['jobs']['trigger-package-tests']
        self.assertTrue(any(step.get('run') == 'python3 tools/ci/select_java.py'
                            for step in trigger['steps']))
