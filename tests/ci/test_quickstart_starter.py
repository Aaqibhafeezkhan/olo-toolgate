# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The real starter smoke build selects native commands and owns its temporary source."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from tools.quickstart import check


class QuickstartStarterTests(unittest.TestCase):
    def test_windows_gradle_wrapper_and_project_directory(self):
        with patch.object(check, 'WINDOWS', True), patch.object(check.subprocess, 'run') as run:
            check.run(['./gradlew', 'task'], cwd=check.ROOT/'build/project')
        self.assertEqual(run.call_args.args[0], [str(check.ROOT/'build/project/gradlew.bat'), 'task'])
        self.assertEqual(run.call_args.kwargs['cwd'], check.ROOT/'build/project')

    def test_unix_keeps_its_gradle_wrapper(self):
        with patch.object(check, 'WINDOWS', False), patch.object(check.subprocess, 'run') as run:
            check.run(['./gradlew', 'task'])
        self.assertEqual(run.call_args.args[0], ['./gradlew', 'task'])

    def test_native_cli_respects_relative_target_directory_and_executable_suffix(self):
        for windows in (False, True):
            with self.subTest(windows=windows), patch.object(check, 'WINDOWS', windows), \
                    patch.object(check.shutil, 'which', return_value='cargo'), patch.object(check, 'run') as run, \
                    patch.dict(os.environ, {'CARGO_TARGET_DIR': 'target/starter-proof'}):
                binary=check.toolgate_cli()
            self.assertEqual(binary, check.ROOT/'target/starter-proof/debug'/('toolgate.exe' if windows else 'toolgate'))
            self.assertIn('--locked', run.call_args.args[0])

    def test_windows_without_cargo_cross_builds_a_windows_cli(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(check, 'ROOT', Path(temporary)), \
                patch.object(check, 'WINDOWS', True), patch.object(check.shutil, 'which', return_value=None), \
                patch.object(check, 'run') as run:
            binary=check.toolgate_cli()
            command=run.call_args.args[0]
            self.assertIn('--target', command)
            self.assertIn('x86_64-pc-windows-gnu', command)
            self.assertIn('--locked', command)
            self.assertEqual(binary, Path(temporary)/'target/client-release/x86_64-pc-windows-gnu/release/toolgate.exe')

    def test_starter_build_preserves_old_project_and_cleans_only_owned_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);old=root/'build/quickstart-starter';old.mkdir(parents=True)
            marker=old/'keep.txt';marker.write_text('user source')
            projects=[]
            def build(arguments, **kwargs):
                if arguments[1]=='init':
                    projects.append(Path(arguments[arguments.index('--dir')+1]))
                elif arguments[1]=='pack':
                    package=Path(kwargs['cwd'])/'build/toolgate/orders-0.1.0.tgpkg'
                    package.parent.mkdir(parents=True);package.write_bytes(b'package')
            with patch.object(check, 'ROOT', root), patch.object(check, 'toolgate_cli', return_value=root/'toolgate.exe'), \
                    patch.object(check, 'run', side_effect=build):
                self.assertEqual(check.starter_package(), b'package')
            self.assertEqual(marker.read_text(), 'user source')
            self.assertEqual(len(projects), 1)
            self.assertFalse(projects[0].exists())

    def test_failed_pack_cleans_owned_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);projects=[]
            def build(arguments, **kwargs):
                if arguments[1]=='init':projects.append(Path(arguments[arguments.index('--dir')+1]))
                elif arguments[1]=='pack':raise subprocess.CalledProcessError(1, arguments)
            with patch.object(check, 'ROOT', root), patch.object(check, 'toolgate_cli', return_value=root/'toolgate.exe'), \
                    patch.object(check, 'run', side_effect=build):
                with self.assertRaises(subprocess.CalledProcessError):check.starter_package()
            self.assertFalse(projects[0].exists())
