# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Validate workflow contracts in preflight before costly platform/build jobs."""
from pathlib import Path
import copy
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


class EarlyGateTests(unittest.TestCase):
    def test_langchain_restart_and_package_contracts_run_before_image_builds(self):
        foundation = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text())
        steps = foundation['jobs']['python']['steps']
        gate = next(step for step in steps if 'examples/langchain -p test_manage.py' in step.get('run', ''))
        self.assertIn('examples/langchain -p test_packages.py', gate['run'])
        self.assertNotIn('if', gate)
        self.assertNotIn('continue-on-error', gate)

    def test_quickstart_restore_and_security_fixtures_run_before_image_builds(self):
        foundation = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text())
        steps = foundation['jobs']['python']['steps']
        gates = [step for step in steps if 'tools/check.py --ci-common --ci-part python' in step.get('run', '')]
        self.assertEqual(1,len(gates))
        gate=gates[0]
        self.assertNotIn('--contracts-only',gate['run'])
        self.assertNotIn('if', gate)
        self.assertNotIn('continue-on-error', gate)
        self.assertNotIn('continue-on-error',foundation['jobs']['python'])
        # Execution and the scoped import path are covered by FoundationGateSelectionTests.

    def test_oci_uses_fresh_fuse_storage_before_pulling_or_qualifying(self):
        jobs = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text())['jobs']
        gate = next(step for step in jobs['rust']['steps']
                    if step.get('name') == 'Rootless OCI worker containment')
        script = gate['run']
        self.assertIn('test ! -e "$HOME/.config/containers/storage.conf"', script)
        self.assertIn('mount_program = "/usr/bin/fuse-overlayfs"', script)
        self.assertLess(script.index('mount_program ='), script.index('podman pull'))
        self.assertLess(script.index('podman pull'), script.index('tools/security/sandbox.py'))
        self.assertNotIn('system reset', script)
        self.assertNotIn('continue-on-error', gate)
        self.assertNotIn('if', gate)
        self.assertIn('tools/security/containment.py', str(jobs['rust']['steps']))
        self.assertIn('tools/security/deployment.py', str(jobs['rust']['steps']))

    def test_langchain_gate_verifies_current_unqualified_native_refusal(self):
        jobs=yaml.safe_load((ROOT/'.github/workflows/images.yml').read_text())['jobs']
        gate=next(step for step in jobs['langchain']['steps']
                  if '--verify-native-boundary' in step.get('run',''))
        self.assertNotIn('--smoke',gate['run'])
        self.assertIn('test_agent.py',gate['run'])
        self.assertIn('manage.py setup',gate['run'])
        self.assertNotIn('continue-on-error',gate)
        self.assertNotIn('continue-on-error',jobs['langchain'])

    def test_manual_ci_reuses_all_unified_stages_after_bypassing_admission(self):
        manual = yaml.load((ROOT/'.github/workflows/ci-manual.yml').read_text(), Loader=yaml.BaseLoader)
        self.assertEqual(manual['name'], 'ToolGate CI - Manual')
        self.assertEqual(set(manual['on']), {'workflow_dispatch'})
        automatic = yaml.load((ROOT/'.github/workflows/ci.yml').read_text(), Loader=yaml.BaseLoader)
        expected = copy.deepcopy(automatic['jobs'])
        del expected['admission']
        del expected['preflight']['needs']
        del expected['preflight']['if']
        self.assertEqual(manual['jobs'], expected)
        self.assertEqual(manual['permissions'], automatic['permissions'])
        self.assertNotEqual(manual['concurrency']['group'], automatic['concurrency']['group'])
        self.assert_preflight_dependencies(manual['jobs'], {'foundation','clients','images'})

    def assert_preflight_dependencies(self, jobs, names):
        self.assertEqual(jobs['preflight']['uses'], './.github/workflows/preflight.yml')
        def gated(name, seen=None):
            if name == 'preflight':
                return True
            seen = (seen or set()) | {name}
            needs = jobs[name].get('needs', [])
            if isinstance(needs, str):
                needs = [needs]
            return any(gated(parent, seen) for parent in needs if parent not in seen)
        for name in names:
            self.assertTrue(gated(name), f'{name}: expensive job can start before metadata validation')

    def test_expensive_jobs_require_preflight(self):
        pipeline = yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8'))
        jobs = pipeline['jobs']
        self.assertEqual(jobs['foundation']['uses'], './.github/workflows/foundation.yml')
        self.assertEqual(jobs['clients']['uses'], './.github/workflows/client.yml')
        self.assert_preflight_dependencies(jobs, set(jobs)-{'admission','preflight'})
        # Component callers still require their own metadata gate. Shared
        # foundation/client bodies are gated by the unified caller above.
        targets = {
            'gateway': ['policy-compatibility', 'container-and-replicas'],
            'control': ['clients', 'policy-compatibility', 'container-and-replicas'],
            'quickstart': ['clients', 'image'],
        }
        for workflow, names in targets.items():
            jobs = yaml.safe_load((ROOT/f'.github/workflows/{workflow}.yml').read_text(encoding='utf-8'))['jobs']
            with self.subTest(workflow=workflow):
                self.assert_preflight_dependencies(jobs, names)

    def test_any_unified_stage_bypassing_preflight_is_rejected(self):
        original = yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8'))['jobs']
        for name in set(original)-{'admission','preflight'}:
            with self.subTest(stage=name):
                jobs = copy.deepcopy(original)
                jobs[name]['needs'] = 'admission'
                with self.assertRaisesRegex(AssertionError, 'before metadata'):
                    self.assert_preflight_dependencies(jobs, [name])

    def test_ci_smoke(self):
        ci = yaml.load((ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8'), Loader=yaml.BaseLoader)
        self.assertEqual('read', ci['permissions']['contents'])
        self.assertEqual({'push', 'workflow_dispatch'}, set(ci['on']))
        self.assertEqual(['**'], ci['on']['push']['branches'])
        self.assertEqual('true', ci['jobs']['admission']['with']['branch_checks'])
        self.assertEqual('./.github/workflows/admission.yml', ci['jobs']['admission']['uses'])
        self.assertNotIn('tags', ci['on']['push'])
        self.assertNotIn('main_only', ci['jobs']['admission'].get('with',{}))
        self.assertEqual("needs.admission.outputs.allow == 'true'", ci['jobs']['preflight']['if'])
        self.assertEqual('./.github/workflows/foundation.yml', ci['jobs']['foundation']['uses'])
        self.assertEqual('preflight', ci['jobs']['foundation']['needs'])
        foundation = yaml.load((ROOT/'.github/workflows/foundation.yml').read_text(encoding='utf-8'), Loader=yaml.BaseLoader)
        self.assertEqual({'workflow_call'}, set(foundation['on']))
        commands = '\n'.join(s.get('run','') for job in foundation['jobs'].values() for s in job.get('steps',[]))
        self.assertIn('python tools/check.py --ci-common', commands)
        self.assertIn('python tools/check.py --scans --ci-common', commands)
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            workflow = yaml.load(path.read_text(), Loader=yaml.BaseLoader)
            for job in workflow['jobs'].values():
                for step in job.get('steps', []):
                    if 'uses' in step:
                        self.assertRegex(step['uses'], r'@[a-f0-9]{40}$')
