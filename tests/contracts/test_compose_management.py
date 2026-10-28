# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise deployment image selection without touching the real Docker daemon."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ComposeManagementTests(unittest.TestCase):
    def deploy(self, folder, image, exists=True):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            for name in ('manage.ps1', 'manage.sh', 'compose.yaml', '.env.example'):
                shutil.copyfile(ROOT/'deploy/compose'/folder/name, work/name)
            if os.name != 'nt':
                # A Linux verification container can mount a Windows checkout.
                # Exercise the shell commands with the repository's specified LF format.
                script = work/'manage.sh'
                script.write_text(script.read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
            log = work/'calls.log'
            if os.name == 'nt':
                docker = work/'docker.cmd'
                docker.write_text('@echo off\n'
                    'echo %*>>"%TEST_DOCKER_LOG%"\n'
                    'echo %* | findstr /c:"config --images" >nul && (echo %TEST_IMAGE% & exit /b 0)\n'
                    'echo %* | findstr /c:"image inspect" >nul && exit /b %TEST_IMAGE_MISSING%\n'
                    'exit /b 0\n', encoding='utf-8')
                command = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                           '-File', str(work/'manage.ps1'), 'deploy']
            else:
                docker = work/'docker'
                docker.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TEST_DOCKER_LOG"\n'
                    'case "$*" in *"config --images"*) echo "$TEST_IMAGE";; '
                    '*"image inspect"*) exit "$TEST_IMAGE_MISSING";; esac\n', encoding='utf-8')
                docker.chmod(0o755)
                command = ['sh', str(work/'manage.sh'), 'deploy']
            env = dict(os.environ, PATH=str(work)+os.pathsep+os.environ['PATH'],
                       TEST_DOCKER_LOG=str(log), TEST_IMAGE=image,
                       TEST_IMAGE_MISSING='0' if exists else '1')
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=30)
            self.assertTrue(log.exists(),result.stdout+result.stderr)
            return result, log.read_text()

    def test_existing_local_images_deploy_without_registry_pull(self):
        for folder in ('QuickStart', 'QuickStart-WO-Password'):
            with self.subTest(folder=folder):
                result, calls = self.deploy(folder, 'olo-toolgate-quickstart:cache-options')
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                self.assertNotIn(' pull', calls)
                self.assertIn('up -d --wait', calls)

    def test_published_images_still_pull_on_deploy(self):
        for folder in ('QuickStart', 'QuickStart-WO-Password'):
            with self.subTest(folder=folder):
                result, calls = self.deploy(folder, 'ololab/olo-toolgate-quickstart:dev')
                self.assertEqual(0, result.returncode, result.stdout+result.stderr)
                self.assertTrue(any(line.startswith('pull ') and 'ololab/' in line for line in calls.splitlines()), calls)
                self.assertNotIn('image inspect', calls)

    def test_missing_local_image_stops_with_actionable_configuration(self):
        for folder in ('QuickStart', 'QuickStart-WO-Password'):
            with self.subTest(folder=folder):
                result, calls = self.deploy(folder, 'olo-toolgate-quickstart:cache-options', exists=False)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('QUICKSTART_IMAGE=ololab/olo-toolgate-quickstart:dev', result.stdout+result.stderr)
                self.assertNotIn(' pull', calls)
                self.assertNotIn('up -d', calls)
