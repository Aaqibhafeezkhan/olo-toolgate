# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Validate workflow contracts in preflight before costly platform/build jobs."""
from pathlib import Path
import copy
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


class EarlyGateTests(unittest.TestCase):
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
        # Compatibility callers still require their own metadata gate. Shared
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
        commands = '\n'.join(s.get('run','') for s in foundation['jobs']['check']['steps'])
        self.assertIn('python tools/check.py --ci-common', commands)
        self.assertIn('python tools/check.py --scans --ci-common', commands)
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            workflow = yaml.load(path.read_text(), Loader=yaml.BaseLoader)
            for job in workflow['jobs'].values():
                for step in job.get('steps', []):
                    if 'uses' in step:
                        self.assertRegex(step['uses'], r'@[a-f0-9]{40}$')
