# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Follow YAML changes and never turn missing local coverage green."""
import copy
import json
import io
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch
from tools.ci import local
from tools.contracts import generate


class LocalCiTests(unittest.TestCase):
    def setUp(self):
        self.workflows = local.load_workflows()

    def test_every_repository_job_is_accounted_for(self):
        rows = local.inventory(self.workflows)
        expected = {f'{name}.{job}' for name, document in self.workflows.items() for job in document['jobs']}
        self.assertEqual({row['id'] for row in rows}, expected)

    def test_python_matrix_remains_explicitly_outside_local_replay(self):
        rows = local.inventory(self.workflows)
        matrix = next(row for row in rows if row['id'] == 'foundation.python-sdk')
        self.assertEqual(matrix['kind'], 'python-matrix')
        self.assertIn('3.10', matrix['reason'])
        self.assertNotIn(matrix['id'], local.replayable_jobs(rows))

    def test_replayable_selection_follows_inventory_without_a_second_job_list(self):
        rows = local.inventory(self.workflows)
        rows.append(dict(id='future.validation', kind='replay'))
        selected = local.replayable_jobs(rows)
        self.assertIn('future.validation', selected)
        self.assertEqual(set(selected), {row['id'] for row in rows if row['kind'] == 'replay'})
        code, report, _ = self.run_mocked(local.replayable_jobs(local.inventory(self.workflows)))
        self.assertEqual(code, 0)
        self.assertTrue(report['passed'])
        self.assertFalse(report['allGitHubTriggersVerified'])

    def test_empty_replayable_scope_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, 'No replayable jobs'):
            local.replayable_jobs([dict(id='release.publish', kind='publication')])

    def test_job_selection_flags_cannot_be_used_for_an_audit_or_plan(self):
        for command in ('plan', 'audit'):
            with self.subTest(command=command), self.assertRaises(SystemExit) as error:
                local.main([command, '--replayable'])
            self.assertEqual(error.exception.code, 2)

    def test_replayable_and_explicit_job_selection_are_mutually_exclusive(self):
        with self.assertRaises(SystemExit) as error:
            local.main(['run', '--replayable', '--job', 'preflight.metadata'])
        self.assertEqual(error.exception.code, 2)

    def test_yaml_changes_flow_into_replay_without_mutating_source(self):
        source = self.workflows['foundation']['jobs']['scans']
        source['needs'] = 'preflight-fixture'
        source['steps'][1]['with']['java-version'] = '25'
        source['steps'].append({'name': 'New gate', 'run': 'python tools/new_gate.py'})
        body = local.replay_document('foundation.scans', self.workflows)['jobs']['replay']
        expected=copy.deepcopy(source['steps'])
        for step in expected:
            if step.get('name') == 'Derive build versions from version.json':
                del step['env']
        del expected[0]['with']['ref']
        expected.insert(1, copy.deepcopy(local.WORKSPACE_SETUP))
        self.assertEqual(body['steps'][1:], expected)
        self.assertEqual(source['steps'][0]['with']['ref'],'${{ inputs.source_sha || github.sha }}')
        self.assertNotIn('needs', body)
        self.assertIn('needs', source)
        body['steps'].clear()
        self.assertTrue(source['steps'])

    def test_version_initialization_replay_removes_only_the_reviewed_token_context(self):
        source = self.workflows['foundation']['jobs']['python']
        original = next(step for step in source['steps'] if step.get('name') == 'Derive build versions from version.json')
        replay = local.replay_document('foundation.python', self.workflows)['jobs']['replay']
        self.assertNotIn('github.token', json.dumps(replay))
        self.assertEqual(original['env'], {'GH_TOKEN': '${{ github.token }}'})
        original['run'] += 'gh release create unsafe\n'
        with self.assertRaisesRegex(ValueError, 'publication or credentials'):
            local.replay_document('foundation.python', self.workflows)

    def test_jobs_consuming_same_run_artifacts_are_staged_not_replayed(self):
        rows = {row['id']: row for row in local.inventory(self.workflows)}
        for key in ('foundation.browser', 'foundation.java', 'foundation.core-mcp', 'foundation.check'):
            with self.subTest(job=key):
                self.assertEqual(rows[key]['kind'], 'staged')
                self.assertIn('same run', rows[key]['reason'])
                self.assertNotIn(key, local.replayable_jobs(list(rows.values())))
        for key in ('foundation.python', 'foundation.ui', 'foundation.rust', 'foundation.examples', 'foundation.scans'):
            with self.subTest(job=key):
                self.assertEqual(rows[key]['kind'], 'replay')

    def test_unknown_job_requires_coverage_policy(self):
        self.workflows['foundation']['jobs']['new-job'] = {'runs-on': 'ubuntu-24.04', 'steps': []}
        with self.assertRaisesRegex(ValueError, 'no local coverage policy'):
            local.inventory(self.workflows)

    @unittest.skipIf(os.name == 'nt', 'Linux checkout modes need a POSIX filesystem')
    def test_checkout_bootstrap_restores_line_endings_and_executable_modes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(['git', 'init', '--quiet', '--template='], cwd=root, check=True)
            (root / '.gitattributes').write_text('* text eol=lf\n')
            wrapper = root / 'gradlew'
            canonical = b'#!/bin/sh\nexit 0\n'
            wrapper.write_bytes(canonical)
            subprocess.run(['git', 'add', '.'], cwd=root, check=True)
            subprocess.run(['git', 'update-index', '--chmod=+x', 'gradlew'], cwd=root, check=True)
            wrapper.write_bytes(canonical.replace(b'\n', b'\r\n'))
            wrapper.chmod(0o644)
            subprocess.run(['sh', '-eu', '-c', local.WORKSPACE_SETUP['run']], cwd=root, check=True)
            self.assertEqual(wrapper.read_bytes(), canonical)
            self.assertTrue(wrapper.stat().st_mode & 0o111)
            subprocess.run([str(wrapper)], cwd=root, check=True)

    def test_preflight_replay_includes_delegated_browser_gate_and_rejects_other_conditions(self):
        source = self.workflows['preflight']['jobs']['metadata']
        body = local.replay_document('preflight.metadata', self.workflows)['jobs']['replay']
        browser = [s for s in body['steps'] if 'browser-downloads.mjs' in s.get('run','')]
        self.assertEqual(len(browser), 1)
        self.assertNotIn('if', browser[0])
        self.assertTrue(any('if' in s for s in source['steps']))
        source['steps'][0]['if'] = 'github.event_name == "push"'
        with self.assertRaisesRegex(ValueError, 'conditional steps'):
            local.inventory(self.workflows)

    def test_unknown_dependency_is_rejected(self):
        self.workflows['foundation']['jobs']['check']['needs'] = 'missing'
        with self.assertRaisesRegex(ValueError, 'unknown dependency'):
            local.inventory(self.workflows)

    def test_scan_report_capture_preserves_always_without_allowing_conditional_gates(self):
        body=local.replay_document('foundation.scans',self.workflows)['jobs']['replay']
        capture=next(s for s in body['steps'] if s.get('with',{}).get('name')=='source-scan-findings')
        self.assertEqual(capture['if'],'always()')
        for step in ({'run':'python tools/check.py --scans','if':'always()'},
                     {**capture,'if':'failure()'}):
            workflows=copy.deepcopy(self.workflows)
            workflows['foundation']['jobs']['scans']['steps'].append(step)
            with self.subTest(step=step),self.assertRaisesRegex(ValueError,'conditional steps'):
                local.inventory(workflows)

    def test_dependency_cycle_is_rejected(self):
        self.workflows['ci']['jobs']['admission']['needs'] = 'foundation'
        with self.assertRaisesRegex(ValueError, 'cycle'):
            local.inventory(self.workflows)

    def test_missing_reusable_workflow_is_rejected(self):
        self.workflows['ci']['jobs']['preflight']['uses'] = './.github/workflows/missing.yml'
        with self.assertRaisesRegex(ValueError, 'unsupported reusable workflow'):
            local.inventory(self.workflows)

    def test_target_must_accept_workflow_call(self):
        del self.workflows['preflight']['on']['workflow_call']
        with self.assertRaisesRegex(ValueError, 'does not accept workflow_call'):
            local.inventory(self.workflows)

    def test_native_matrix_is_preserved_in_inventory(self):
        rows = {row['id']: row for row in local.inventory(self.workflows)}
        self.assertEqual(len(rows['client.native']['matrix']['include']), 6)
        self.assertEqual(rows['client.native']['kind'], 'native')

    def test_real_triggers_including_release_and_development_are_loaded(self):
        for name in ('foundation', 'client', 'control', 'gateway', 'quickstart'):
            with self.subTest(workflow=name):
                self.assertEqual(set(self.workflows[name]['on']), {'workflow_call'})
                self.assertNotIn('pull_request', self.workflows[name]['on'])
        self.assertNotIn('release-foundation', self.workflows)
        self.assertEqual(self.workflows['ci']['on']['push'], {'branches': ['**']})
        self.assertEqual(set(self.workflows['development-release']['on']), {'workflow_call'})
        self.assertEqual(set(self.workflows['manual-development-release']['on']), {'workflow_dispatch'})

    def test_loader_accepts_yaml_extension_and_all_on_forms(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / '.github/workflows'
            folder.mkdir(parents=True)
            for index, event in enumerate(['push', '[push, pull_request]', '\n  workflow_call:']):
                (folder / f'fixture{index}.yaml').write_text(
                    f'on: {event}\njobs:\n  test:\n    runs-on: ubuntu-24.04\n    steps: []\n', encoding='utf-8')
            workflows = local.load_workflows(root)
            self.assertEqual(set(workflows['fixture0']['on']), {'push'})
            self.assertEqual(set(workflows['fixture1']['on']), {'push', 'pull_request'})
            self.assertEqual(set(workflows['fixture2']['on']), {'workflow_call'})

    def test_empty_workflow_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp, self.assertRaisesRegex(ValueError, 'No workflows'):
            local.load_workflows(Path(temp))

    def test_duplicate_workflow_stems_cannot_hide_jobs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / '.github/workflows'
            folder.mkdir(parents=True)
            for suffix in ('.yml', '.yaml'):
                (folder / ('same' + suffix)).write_text('on: push\njobs:\n  test: {}\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Duplicate workflow'):
                local.load_workflows(root)

    def test_audit_failure_replaces_previous_success_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / 'build/local-ci'
            output.mkdir(parents=True)
            (output / 'audit-report.json').write_text('{"passed": true}', encoding='utf-8')
            with patch.object(local, 'fingerprint', return_value='source'), \
                 patch.object(local, 'execute', side_effect=RuntimeError('metadata failed')):
                with self.assertRaisesRegex(RuntimeError, 'metadata failed'):
                    local.audit(root)
            report = json.loads((output / 'audit-report.json').read_text(encoding='utf-8'))
            self.assertFalse(report['passed'])
            self.assertEqual(report['error'], 'metadata failed')

    def test_new_context_conditions_and_runner_require_review(self):
        for field, value in [('if', 'false'), ('services', {}), ('environment', 'release'),
                             ('runs-on', 'windows-2025'), ('env', {'VALUE': '${{ needs.preflight.outputs.value }}'})]:
            workflows = copy.deepcopy(self.workflows)
            workflows['foundation']['jobs']['scans'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                local.inventory(workflows)
        self.workflows['foundation']['jobs']['scans']['steps'][0]['if'] = 'false'
        with self.assertRaisesRegex(ValueError, 'conditional steps'):
            local.inventory(self.workflows)

    def test_publication_and_secret_steps_are_rejected(self):
        for step in [{'uses': 'actions/attest-build-provenance@v2'}, {'run': 'docker push ghcr.io/example/image'},
                     {'run': 'npm publish'}, {'run': 'git push origin main'},
                     {'run': 'echo test', 'env': {'TOKEN': '${{ secrets.GH_TOKEN }}'}}]:
            workflows = copy.deepcopy(self.workflows)
            workflows['foundation']['jobs']['scans']['steps'].append(step)
            with self.subTest(step=step), self.assertRaises(ValueError):
                local.inventory(workflows)

    def test_act_uses_generated_workflow_and_empty_credential_files(self):
        with tempfile.TemporaryDirectory() as temp:
            workspace = Path(temp)
            command = local.act_command('act', workspace / 'job.yml', workspace)
            self.assertEqual(command[command.index('--workflows') + 1], str(workspace / 'job.yml'))
            self.assertNotIn('--bind', command)
            self.assertIn('--rm', command)
            self.assertIn('--volume /var/lib/docker', command[command.index('--container-options') + 1])
            self.assertIn('--cgroupns=private', command[command.index('--container-options') + 1])
            self.assertEqual(command[command.index('--container-daemon-socket') + 1], '-')
            self.assertEqual(command[command.index('--network') + 1], 'toolgate-local-ci')
            for option in ('--secret-file', '--var-file', '--input-file', '--env-file'):
                self.assertEqual(Path(command[command.index(option) + 1]).read_text(), '')

    def test_implicit_act_config_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / '.actrc').write_text('--platform ubuntu-24.04=-self-hosted', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'implicit act configuration'):
                local.check_act_config(root)

    def test_release_credentials_are_not_inherited(self):
        with patch.dict(local.os.environ, {'GH_TOKEN': 'private', 'NODE_AUTH_TOKEN': 'private', 'PATH': 'tools'}):
            environment = local.act_environment()
            self.assertNotIn('GH_TOKEN', environment)
            self.assertNotIn('NODE_AUTH_TOKEN', environment)
            self.assertEqual(environment['PATH'], 'tools')

    def test_schema_descriptions_are_utf8_even_with_windows_default_encoding(self):
        original_open = io.open

        def windows_open(file, mode='r', buffering=-1, encoding=None, errors=None,
                         newline=None, closefd=True, opener=None):
            if 'b' not in mode and encoding is None:
                encoding = 'cp1252'
            return original_open(file, mode, buffering, encoding, errors, newline, closefd, opener)

        with tempfile.TemporaryDirectory() as temp:
            contracts = Path(temp)
            schemas = contracts / 'schemas/v2'
            schemas.mkdir(parents=True)
            description = 'composition.md \u00a72; \u03bb'
            (schemas / 'fixture.json').write_text(json.dumps({'$defs': {
                'Fixture': {'type': 'object', 'description': description}}}, ensure_ascii=False), encoding='utf-8')
            with patch.object(generate, 'CONTRACTS', contracts), patch('io.open', windows_open):
                self.assertEqual(generate.definitions('v2')['Fixture']['description'], description)

    def run_mocked(self, selected=None, failure=False, fingerprints=None):
        rows = local.inventory(self.workflows)
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            with patch.object(local, 'fingerprint', side_effect=fingerprints, return_value='source'), \
                 patch.object(local, 'check_act_config'), patch.object(local.shutil, 'which', return_value='act'), \
                 patch.object(local, 'create_snapshot', return_value=output), \
                 patch.object(local, 'build_runner'), \
                 patch.object(local, 'execute', side_effect=RuntimeError('broken command') if failure else None) as execute:
                try:
                    result = local.run_jobs(self.workflows, rows, selected, output)
                except RuntimeError:
                    result = 1
                report = json.loads((output / 'report.json').read_text(encoding='utf-8'))
                return result, report, execute.call_args_list

    def test_all_run_never_claims_complete_success(self):
        code, report, calls = self.run_mocked()
        self.assertEqual(code, 2)
        self.assertFalse(report['passed'])
        self.assertFalse(report['allGitHubTriggersVerified'])
        passed = {row['id'] for row in report['jobs'] if row['status'] == 'passed'}
        self.assertEqual(passed, {key for key, kind in local.POLICY.items() if kind == 'replay'})
        self.assertEqual(len(calls), len(passed) + 3)

    def test_selected_pass_is_explicitly_scoped(self):
        code, report, calls = self.run_mocked(['preflight.metadata'])
        self.assertEqual(code, 0)
        self.assertTrue(report['passed'])
        self.assertEqual(report['scope'], 'selected job bodies')
        self.assertFalse(report['allGitHubTriggersVerified'])
        self.assertEqual(len(calls), 4)
        self.assertTrue(any(row['status'] == 'not-selected' for row in report['jobs']))

    def test_selecting_publication_never_executes_it(self):
        code, report, calls = self.run_mocked(['release-images.publish'])
        self.assertEqual(code, 2)
        self.assertEqual(calls, [])
        self.assertFalse(report['passed'])

    def test_command_failure_is_recorded(self):
        code, report, _ = self.run_mocked(['preflight.metadata'], failure=True)
        self.assertEqual(code, 1)
        self.assertFalse(report['passed'])
        self.assertEqual(report['error'], 'broken command')

    def test_source_change_invalidates_pass(self):
        code, report, _ = self.run_mocked(['preflight.metadata'], fingerprints=['before', 'after'])
        self.assertEqual(code, 1)
        self.assertFalse(report['passed'])
        self.assertIn('Source changed', report['error'])

    def test_snapshot_has_working_tree_source_and_portable_git_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            source = folder / 'original'
            source.mkdir()
            subprocess.run(['git', 'init', '--template=', str(source)], check=True, capture_output=True)
            (source / '.gitignore').write_text('ignored.secret\n', encoding='utf-8')
            (source / 'tracked.txt').write_text('original', encoding='utf-8')
            subprocess.run(['git', '-C', str(source), 'add', '.'], check=True)
            (source / 'tracked.txt').write_text('changed', encoding='utf-8')
            (source / 'new.txt').write_text('untracked', encoding='utf-8')
            (source / 'ignored.secret').write_text('private fixture', encoding='utf-8')
            snapshot = local.create_snapshot(folder / 'snapshot', source)
            self.assertEqual((snapshot / 'tracked.txt').read_text(), 'changed')
            self.assertEqual((snapshot / 'new.txt').read_text(), 'untracked')
            self.assertFalse((snapshot / 'ignored.secret').exists())
            self.assertTrue((snapshot / '.git').is_dir())
            subprocess.run(['git', '-C', str(snapshot), 'checkout-index', '--all'], check=True)
            self.assertEqual((source / 'tracked.txt').read_text(), 'changed')


if __name__ == '__main__':
    unittest.main()
