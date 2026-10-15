# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Verify shared workflow calls retain tested automatic and build-only manual paths."""
from pathlib import Path
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[2]


def workflow(name):
    return yaml.safe_load((ROOT/'.github/workflows'/f'{name}.yml').read_text())


class SharedReleaseJobTests(unittest.TestCase):
    def test_both_paths_share_native_build_image_build_and_publisher_bodies(self):
        automatic = workflow('ci')['jobs']
        manual = workflow('manual-development-release')['jobs']
        verified = workflow('development-release')['jobs']
        self.assertEqual(automatic['clients']['uses'],manual['native']['uses'])
        self.assertEqual(automatic['images']['uses'],manual['build']['uses'])
        self.assertEqual(verified['publish']['uses'],manual['publish']['uses'])
        self.assertIs(verified['publish']['with']['manual'],False)
        self.assertIs(manual['publish']['with']['manual'],True)
        self.assertNotIn('steps',verified['publish'])
        self.assertNotIn('steps',manual['publish'])

    def test_automatic_tests_cannot_be_bypassed_by_default_inputs_or_dependencies(self):
        automatic = workflow('ci')['jobs']
        self.assertIs(automatic['clients']['with']['run_tests'],True)
        self.assertIs(automatic['images']['with']['run_tests'],True)
        self.assertEqual(set(automatic['release']['needs']),{'foundation','clients','images'})
        self.assertEqual(set(automatic['images']['needs']),{'foundation','clients'})
        for name in ('client','images'):
            self.assertIs(workflow(name)['on']['workflow_call']['inputs']['run_tests']['default'],True)

    def test_manual_disables_test_gates_and_uses_only_its_own_build_artifacts(self):
        manual = workflow('manual-development-release')['jobs']
        for job in ('native','build'):
            self.assertIs(manual[job]['with']['run_tests'],False)
        self.assertNotIn('foundation',manual)
        self.assertNotIn('admission',manual)
        for job in ('publish','publish-typescript'):
            self.assertEqual(manual[job]['needs'],'build')
        images = workflow('images')['jobs']['verify']['steps']
        foundation = next(s for s in images if s.get('with',{}).get('name') == 'foundation-evidence')
        self.assertEqual(foundation['if'],'${{ inputs.run_tests }}')
        manual_build = next(s for s in images if s.get('name') == 'Build containers and library packages')
        self.assertEqual(manual_build['if'],'${{ !inputs.run_tests }}')
        self.assertIn('manual_development.py build',manual_build['run'])
        for step in images:
            if step.get('with',{}).get('name','').startswith('manual-development-'):
                self.assertEqual(step['if'],'${{ !inputs.run_tests }}')

    def test_nested_reusable_workflows_never_request_more_permissions_than_callers(self):
        levels = {'none':0,'read':1,'write':2}
        def check(name):
            document = workflow(name)
            for job_name,job in document['jobs'].items():
                if not job.get('uses','').startswith('./.github/workflows/'):
                    continue
                child_name = Path(job['uses']).stem
                child = workflow(child_name)
                granted = job.get('permissions',document.get('permissions',{}))
                requested = [child.get('permissions',{})]+[j.get('permissions',{}) for j in child['jobs'].values()]
                for scopes in requested:
                    for scope,level in scopes.items():
                        self.assertGreaterEqual(levels[granted.get(scope,'none')],levels[level],
                            f'{name}/{job_name} exceeds {scope} permission')
                check(child_name)
        check('ci')
        check('manual-development-release')

    def test_all_reusable_call_inputs_exist_and_have_matching_types(self):
        for path in (ROOT/'.github/workflows').glob('*.yml'):
            document=yaml.safe_load(path.read_text())
            for job in document['jobs'].values():
                if not job.get('uses','').startswith('./.github/workflows/'):
                    continue
                called=workflow(Path(job['uses']).stem)
                schema=(called.get('on',called.get(True,{})).get('workflow_call') or {}).get('inputs',{})
                for key,value in job.get('with',{}).items():
                    self.assertIn(key,schema,f'{path.name}: unsupported input {key}')
                    if schema[key]['type']=='boolean':self.assertIsInstance(value,bool)
