# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Same-run release trust and shared check execution without launching full suites."""
from contextlib import contextmanager
import copy
import importlib
import os
import re
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import yaml
from tools import check
from tools.release import development

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
TARGETS = ('x86_64-unknown-linux-gnu', 'aarch64-unknown-linux-gnu',
           'x86_64-pc-windows-msvc', 'aarch64-pc-windows-msvc',
           'x86_64-apple-darwin', 'aarch64-apple-darwin')


def workflow(name):
    return yaml.safe_load((ROOT/'.github/workflows'/name).read_text())


def steps(name):
    """Every step of a workflow; its stages run as separate jobs."""
    return [step for job in workflow(name)['jobs'].values() for step in job.get('steps', [])]


class SameRunReleaseTests(unittest.TestCase):
    def setUp(self):
        self.environment=patch.dict(os.environ,{'GITHUB_REF':'refs/heads/main'})
        self.environment.start();self.addCleanup(self.environment.stop)
        self.sha = 'a' * 40
        self.run = dict(head_sha=self.sha, head_branch='main',
                        path='.github/workflows/ci.yml', event='push', status='in_progress')
        self.jobs = [dict(name=name, status='completed', conclusion='success') for name in
                     ('admission / admit', 'preflight / metadata', 'foundation / check',
                      'clients / public-bundle', 'images / verify')]
        self.jobs += [dict(name=f'clients / native ({target})', status='completed',
                          conclusion='success') for target in TARGETS]
        self.jobs.append(dict(name='release / publish', status='in_progress', conclusion=None))

    def select(self):
        def api(url):
            return {'jobs': self.jobs} if '/jobs?' in url else self.run
        with patch.object(development, 'api', side_effect=api):
            return development.unified_run('olo-labs/olo-toolgate', self.sha, 123)

    def test_publisher_consumes_one_run_without_waiting_for_itself(self):
        self.assertEqual(self.select(), dict.fromkeys(development.WORKFLOWS, 123))

    def test_every_gate_and_native_failure_skip_or_missing_result_blocks_release(self):
        original = copy.deepcopy(self.jobs)
        for index in range(len(original)-1):
            for result in ('failure', 'skipped', 'cancelled', None):
                with self.subTest(gate=original[index]['name'], result=result):
                    self.jobs = copy.deepcopy(original)
                    self.jobs[index]['conclusion'] = result
                    with self.assertRaises(ValueError): self.select()
            self.jobs = copy.deepcopy(original)
            del self.jobs[index]
            with self.assertRaises(ValueError): self.select()

    def test_different_commit_branch_workflow_or_event_is_rejected(self):
        for key, value in (('head_sha', 'b'*40), ('head_branch', 'feature'),
                           ('path', '.github/workflows/manual-development-release.yml'),
                           ('event', 'pull_request')):
            with self.subTest(field=key), patch.dict(self.run, {key: value}):
                with self.assertRaises(ValueError): self.select()

    def test_duplicate_platform_cannot_replace_missing_platform(self):
        self.jobs[5]['name'] = self.jobs[6]['name']
        with self.assertRaisesRegex(ValueError, 'platform is missing'): self.select()

    def test_invalid_run_id_does_not_contact_github(self):
        with patch.object(development, 'api') as api:
            with self.assertRaises(ValueError):
                development.unified_run('olo-labs/olo-toolgate', self.sha, '../another-run')
        api.assert_not_called()


class SharedCheckOwnershipTests(unittest.TestCase):
    def check_caller_permissions(self, pipeline):
        levels = {'none': 0, 'read': 1, 'write': 2}
        for name, caller in pipeline['jobs'].items():
            target = workflow(Path(caller['uses']).name)
            granted = caller.get('permissions', pipeline.get('permissions', {}))
            requested = [target.get('permissions', {})]
            requested += [job['permissions'] for job in target['jobs'].values() if 'permissions' in job]
            for permissions in requested:
                for scope, level in permissions.items():
                    self.assertGreaterEqual(levels[granted.get(scope, 'none')], levels[level],
                                            f'{name}: called workflow exceeds caller permission for {scope}')

    def test_reusable_workflow_permissions_fit_the_calling_job(self):
        self.check_caller_permissions(workflow('ci.yml'))
        self.check_caller_permissions(workflow('ci-manual.yml'))

    def test_missing_client_pr_read_permission_reproduces_startup_rejection(self):
        pipeline = workflow('ci.yml')
        pipeline['jobs']['clients']['permissions'] = {'contents':'read'}
        with self.assertRaisesRegex(AssertionError, 'clients.*pull-requests'):
            self.check_caller_permissions(pipeline)

    def test_main_calls_one_shared_stage_and_one_platform_matrix(self):
        jobs = workflow('ci.yml')['jobs']
        self.assertEqual(set(jobs), {'admission','preflight','foundation','clients','images','coverage'})
        for name in ('preflight','clients'):
            self.assertTrue(jobs[name]['with']['shared_checks'])
        self.assertEqual(set(jobs['images']['needs']), {'foundation','clients'})
        self.assertNotIn('release',jobs)
        native = workflow('client.yml')['jobs']['native']
        self.assertEqual({row['target'] for row in native['strategy']['matrix']['include']}, set(TARGETS))
        self.assertFalse(native['strategy']['fail-fast'])
        # Compatibility callers retain their full native checks by default.
        self.assertFalse(workflow('client.yml')['on']['workflow_call']['inputs']['shared_checks']['default'])

    def test_helpers_execute_shared_checks_once_and_reuse_ui(self):
        commands = []
        @contextmanager
        def database():
            yield dict(CONTROL_TEST_URL='fixture', CONTROL_TEST_PASSWORD='fixture')
        with patch.object(sys, 'argv', ['check.py','--ci-common']), \
                patch.object(check, 'run', side_effect=lambda args, **kw: commands.append(args)), \
                patch.object(check, 'python', side_effect=lambda *args: commands.append(['python',*args])), \
                patch.object(check, 'publication_proof') as publication, \
                patch.object(check, 'helm_checks'), patch('control.check.database', database):
            check.main()
        for command in (['cargo','test','--workspace','--locked'],
                        ['npm','run','contracts:build'], ['npm','run','contracts:check'],
                        ['npm','--workspace','@olo-labs/toolgate-admin-ui','test'],
                        ['npm','--workspace','@olo-labs/toolgate-admin-ui','run','check'],
                        ['node','tools/ci/browser-downloads.mjs','--skip-build']):
            self.assertEqual(commands.count(command), 1, command)
        self.assertFalse(any('tools/ci/preflight.py' in args or 'tests/ci' in args
                             or 'tools/quickstart/check.py' in args for args in commands))
        self.assertIn(['gradle','-PprebuiltAdminUi=true','projects','javaCheck','build'], commands)
        self.assertNotIn(['gradle',':control-plane:enterpriseTest'], commands)
        publication.assert_called_once_with(prebuilt_ui=True)

    def test_image_stage_uses_same_run_ui_and_preserves_all_runtime_contexts(self):
        images = steps('images.yml')
        scripts = '\n'.join(step.get('run','') for step in images)
        for gate in ('tools/control/container.py','tools/enterprise/restore_check.py',
                     'tools/client/downloads.py','tools/gateway/container.py',
                     'tools/gateway/benchmark.py','tools/device/check.py','tools/device/secured_check.py'):
            self.assertEqual(scripts.count(gate), 1, gate)
        self.assertEqual(scripts.count('--prebuilt-ui'), 2)
        self.assertIn('--postgresql --no-browser', scripts)
        self.assertIn('agent --target linux --smoke', scripts)
        for image in ('quickstart','control','gateway','device','linux-secured-device'):
            self.assertIn(f'--severity HIGH,CRITICAL olo-toolgate-{image}:', scripts)
        self.assertNotIn('tools/check.py', scripts)
        self.assertNotIn('cargo test', scripts)
        downloads = [s['with'] for s in images if s.get('with',{}).get('name')=='foundation-evidence']
        self.assertTrue(downloads)
        for download in downloads:
            self.assertEqual(download, dict(name='foundation-evidence', path='.'))

    def test_image_stage_retains_browser_diagnostics_after_failure(self):
        diagnostics = next(step for step in steps('images.yml') if step.get('with', {}).get('name') == 'browser-diagnostics')
        self.assertEqual(diagnostics['if'], '${{ always() && inputs.run_tests }}')
        self.assertEqual(diagnostics['with']['path'].splitlines(),
                         ['build/ui/browser-results.json', 'build/ui/browser-output/',
                          'build/quickstart/runtime-failure.log', 'build/quickstart/startup-failure.log'])
        self.assertEqual(diagnostics['with']['if-no-files-found'], 'ignore')

    def test_foundation_runs_every_gate_group_in_its_own_job(self):
        jobs = workflow('foundation.yml')['jobs']
        parts = {}
        for name, job in jobs.items():
            for step in job.get('steps', []):
                for part in re.findall(r'tools/check\.py --ci-common --ci-part (\w+)', step.get('run', '')):
                    parts[part] = name
        self.assertEqual(set(parts), set(check.CI_PARTS))
        self.assertEqual(len(set(parts.values())), len(parts))
        for name in ('browser', 'java', 'core-mcp'):
            self.assertEqual(jobs[name]['needs'], 'ui')

    def test_ci_parts_together_run_the_whole_common_gate_set(self):
        def commands(argv):
            recorded = []
            @contextmanager
            def database():
                yield dict(CONTROL_TEST_URL='fixture', CONTROL_TEST_PASSWORD='fixture')
            with patch.object(sys, 'argv', argv), \
                    patch.object(check, 'run', side_effect=lambda args, **kw: recorded.append(args)), \
                    patch.object(check, 'python', side_effect=lambda *args: recorded.append(['python',*args])), \
                    patch.object(check, 'publication_proof'), \
                    patch.object(check, 'helm_checks', side_effect=lambda: recorded.append(['helm'])), \
                    patch('control.check.database', database):
                check.main()
            return recorded
        whole = commands(['check.py','--ci-common'])
        split = [args for part in check.CI_PARTS
                 for args in commands(['check.py','--ci-common','--ci-part',part])]
        # Only the browser job repeats npm ci, because it receives node_modules from no other job.
        self.assertEqual(split.count(['npm','ci','--ignore-scripts']), 2)
        split.remove(['npm','ci','--ignore-scripts'])
        key = lambda args: [str(arg) for arg in args]
        self.assertEqual(sorted(map(key, split)), sorted(map(key, whole)))

    def test_release_gates_require_every_stage(self):
        foundation = workflow('foundation.yml')['jobs']
        self.assertEqual(set(foundation['check']['needs']), set(foundation)-{'check','python-sdk'})
        images = workflow('images.yml')['jobs']
        verify = images['verify']
        self.assertEqual(set(verify['needs']), set(images)-{'verify'})
        self.assertEqual(verify['if'], '${{ !cancelled() }}')
        script = verify['steps'][0]['run']
        for name in set(images)-{'verify'}:
            self.assertIn(f'"{name}"', script)
        self.assertEqual(images['build']['if'], '${{ !inputs.run_tests }}')
        for name in set(images)-{'verify','build'}:
            self.assertEqual(images[name].get('if', '${{ inputs.run_tests }}'), '${{ inputs.run_tests }}')

    def test_shared_helm_lint_has_one_owner(self):
        # Standalone helper scripts share the basename "check". Other fixtures
        # may have loaded Control's script under that name; isolate this import.
        with patch.dict(sys.modules, {'check': check}):
            modules = [importlib.import_module(name) for name in
                       ('gateway.check','control.helm','enterprise.helm','client.helm','deployment.helm')]
            with patch.object(modules[0], 'helm_checks') as gateway, patch.object(modules[1], 'checks') as control, \
                    patch.object(modules[2], 'checks'), patch.object(modules[3], 'checks'), patch.object(modules[4], 'checks'):
                check.helm_checks()
        gateway.assert_called_once_with()
        control.assert_called_once_with(lint=False)

    def test_active_trivy_scans_reuse_database_cache_without_skipping_updates(self):
        for name in ('foundation.yml','images.yml'):
            scripts='\n'.join(step.get('run','') for step in steps(name))
            scans=[line for line in scripts.splitlines() if 'aquasec/trivy:0.61.1' in line]
            self.assertTrue(scans,name)
            for scan in scans:
                with self.subTest(workflow=name,scan=scan):
                    self.assertIn('$PWD/.dev/trivy-cache:/root/.cache/trivy',scan)
                    self.assertIn('TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db:2,mirror.gcr.io/aquasec/trivy-db:2',scan)
                    self.assertNotIn('--skip-db-update',scan)


if __name__ == '__main__':
    unittest.main()
