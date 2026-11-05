# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""CI must install pinned Rust independently of the runner's preinstalled toolchain."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('install_rust', ROOT/'tools/ci/install_rust.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class RustInstallationTests(unittest.TestCase):
    def test_native_clients_install_pinned_rust_before_using_it(self):
        job = yaml.safe_load((ROOT/'.github/workflows/client.yml').read_text())['jobs']['native']
        self.assertNotIn('RUSTUP_HOME', job['env'])
        self.assertNotIn('CARGO_HOME', job['env'])
        steps = job['steps']
        install = next(step for step in steps if 'python tools/ci/install_rust.py' in step.get('run', ''))
        self.assertEqual(install['shell'], 'bash')
        self.assertEqual(install['env']['NATIVE_TARGET'], '${{ matrix.target }}')
        self.assertEqual('${{ runner.temp }}/toolgate-rustup', install['env']['RUSTUP_HOME'])
        self.assertEqual('${{ runner.temp }}/toolgate-cargo', install['env']['CARGO_HOME'])
        self.assertIn('rustup set default-host "$NATIVE_TARGET"', install['run'])
        self.assertIn('RUSTUP_HOME=%s', install['run'])
        self.assertIn('CARGO_HOME=%s', install['run'])
        self.assertIn('$GITHUB_PATH', install['run'])
        verify = next(step for step in steps if step.get('name') == 'Verify native target and source')
        self.assertLess(steps.index(install), steps.index(verify))
        self.assertNotIn('if', install)
        self.assertNotIn('continue-on-error', install)

    def test_foundation_installs_rust_in_job_local_storage(self):
        jobs = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text())['jobs']
        checked = 0
        for name, job in jobs.items():
            steps = [step for step in job.get('steps', [])
                     if 'tools/ci/install_rust.py' in step.get('run', '')]
            if not steps:
                continue
            with self.subTest(job=name):
                self.assertEqual('${{ runner.temp }}/toolgate-rustup', steps[0]['env']['RUSTUP_HOME'])
                self.assertEqual('${{ runner.temp }}/toolgate-cargo', steps[0]['env']['CARGO_HOME'])
                self.assertIn('CARGO_HOME=%s', steps[0]['run'])
                self.assertIn('$GITHUB_PATH', steps[0]['run'])
                self.assertIn('"$RUSTUP_HOME" >> "$GITHUB_ENV"', steps[0]['run'])
                self.assertNotIn('continue-on-error', steps[0])
                self.assertNotIn('rustup show', steps[0]['run'])
                checked += 1
        self.assertGreater(checked, 0)
        self.assertTrue(any('tools/ci/install_rust.py' in step.get('run', '')
                            for step in jobs['scans']['steps']))

    def test_pins_components_and_targets_follow_the_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'rust-toolchain.toml').write_text(
                '[toolchain]\nchannel="1.90.0"\nprofile="minimal"\n'
                'components=["rustfmt","clippy"]\ntargets=["aarch64-unknown-linux-gnu"]\n')
            with patch.object(installer, 'ROOT', root), patch.object(installer.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '', '')) as run:
                installer.main()
            command = run.call_args_list[0].args[0]
            self.assertEqual(['rustup', 'toolchain', 'install', '1.90.0'], command[:4])
            self.assertIn('--no-self-update', command)
            self.assertEqual('aarch64-unknown-linux-gnu', command[command.index('--target')+1])
            self.assertEqual(['rustfmt', 'clippy'],
                             [command[i+1] for i, value in enumerate(command) if value == '--component'])
            self.assertTrue(all(call.kwargs['check'] for call in run.call_args_list))

    def test_installation_errors_stop_setup(self):
        with patch.object(installer.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'rustup')) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                installer.main()
        self.assertEqual(1, run.call_count)

    def test_partial_component_conflict_reinstalls_once_in_owned_ci_home(self):
        with tempfile.TemporaryDirectory() as folder:
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': folder,
                   'RUSTUP_HOME': str(Path(folder)/'toolgate-rustup')}
            failure = subprocess.CalledProcessError(1, 'rustup', stderr=
                "recovering from a partially installed toolchain\n"
                "failed to install component: 'clippy-preview', detected conflict: 'bin/cargo-clippy'")
            ok = subprocess.CompletedProcess([], 0, '', '')
            with patch.dict(os.environ, env), patch.object(installer.subprocess, 'run',
                    side_effect=[failure, ok, ok, ok]) as run:
                installer.main()
            calls = run.call_args_list
            self.assertEqual(4, len(calls))
            self.assertEqual(['rustup', 'toolchain', 'uninstall', '1.94.1'], calls[1].args[0])
            self.assertEqual(calls[0].args[0], calls[2].args[0])
            self.assertEqual(['rustup', 'show', 'active-toolchain'], calls[3].args[0])

    def test_repair_never_touches_developer_external_or_symlinked_homes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            allowed = root/'toolgate-rustup'
            cases = [
                {'GITHUB_ACTIONS': 'false', 'RUNNER_TEMP': folder, 'RUSTUP_HOME': str(allowed)},
                {'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': folder, 'RUSTUP_HOME': str(root/'other')},
                {'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': folder, 'RUSTUP_HOME': ''},
            ]
            if os.name != 'nt':
                external = root/'external'
                external.mkdir()
                allowed.symlink_to(external, target_is_directory=True)
                cases.append({'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': folder,
                              'RUSTUP_HOME': str(allowed)})
            for env in cases:
                with self.subTest(env=env), patch.dict(os.environ, env):
                    self.assertFalse(installer.owned_ci_home())

    def test_unrelated_errors_and_repeated_conflicts_stop_without_another_retry(self):
        with tempfile.TemporaryDirectory() as folder:
            env = {'GITHUB_ACTIONS': 'true', 'RUNNER_TEMP': folder,
                   'RUSTUP_HOME': str(Path(folder)/'toolgate-rustup')}
            ok = subprocess.CompletedProcess([], 0, '', '')
            for detail, expected in [
                ('network unavailable', 1),
                ("failed to install component: clippy, detected conflict: cargo-clippy", 1),
                ("recovering from a partially installed toolchain\n"
                 "failed to install component: clippy, detected conflict: cargo-clippy", 3),
            ]:
                failure = subprocess.CalledProcessError(1, 'rustup', stderr=detail)
                with self.subTest(detail=detail), patch.dict(os.environ, env), patch.object(
                        installer.subprocess, 'run', side_effect=[failure, ok, failure]) as run:
                    with self.assertRaises(subprocess.CalledProcessError):
                        installer.main()
                    self.assertEqual(expected, run.call_count)


if __name__ == '__main__':
    unittest.main()
