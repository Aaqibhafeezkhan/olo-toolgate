# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""CI must install pinned Rust independently of the runner's preinstalled toolchain."""
import importlib.util
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
            with patch.object(installer, 'ROOT', root), patch.object(installer.subprocess, 'run') as run:
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


if __name__ == '__main__':
    unittest.main()
