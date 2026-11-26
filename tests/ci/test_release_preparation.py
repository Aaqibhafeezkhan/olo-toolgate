# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Branch selection, patch isolation, protected updates and interrupted preparations."""
import base64
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import yaml
from tools.release import line, prepare

SHA = 'a'*40
PREPARED = 'b'*40
ROOT = Path(__file__).resolve().parents[2]
API = prepare.api


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.heads = {'main': SHA, 'release/1.x': SHA}
        self.tags = {}
        self.config = dict(version='1.0.0', tag='dev')
        self.writes = []
        self.protected = False
        self.pulls = []
        self.moved = False
        self.fail_after_ref = False
        for mock in (
                patch.dict(os.environ, GITHUB_RUN_ID='100', GITHUB_REPOSITORY='olo-labs/olo-toolgate'),
                patch.object(prepare, 'api', side_effect=self.api),
                patch.object(prepare, 'ref', side_effect=lambda name: dict(object=dict(sha=self.heads[name[6:]]))
                             if name[6:] in self.heads else None),
                patch.object(prepare, 'tag', side_effect=self.tags.get),
                patch.object(prepare, 'create_tag', side_effect=lambda name, sha, info: self.tags.update({name: (sha, info)})),
                patch.object(prepare, 'pages', side_effect=lambda path: [[dict(ref='refs/heads/'+name) for name in self.heads]])):
            mock.start()
            self.addCleanup(mock.stop)

    def api(self, path, data=None, method='POST'):
        from urllib.parse import unquote
        if data is not None:
            self.writes.append((path, data, method))
            if path == 'git/trees':
                return dict(sha='c'*40)
            if path == 'git/commits':
                if self.moved:
                    self.heads['release/1.x'] = 'd'*40
                    self.heads['main'] = 'd'*40
                return dict(sha=PREPARED)
            if path == 'git/refs':
                name = data['ref'].removeprefix('refs/heads/')
                if name in self.heads:
                    raise ValueError('Ref exists')
                self.heads[name] = data['sha']
            elif path.startswith('git/refs/heads/'):
                self.assertEqual(method, 'PATCH')
                self.assertFalse(data['force'])
                if self.protected:
                    raise subprocess.CalledProcessError(1, 'gh', stderr='GH006: protected branch update failed')
                self.heads[unquote(path.removeprefix('git/refs/heads/'))] = data['sha']
            elif path == 'pulls':
                self.pulls.append(dict(state='open', html_url='https://github.com/olo-labs/olo-toolgate/pull/999'))
                return self.pulls[-1]
            else:
                raise AssertionError(path)
            if self.fail_after_ref:
                self.fail_after_ref = False
                raise subprocess.CalledProcessError(1, 'gh', stderr='connection lost after ref update')
            return {}
        if path.startswith('commits/'):
            return dict(sha=self.heads[unquote(path[8:])])
        if path.startswith('contents/'):
            return dict(content=base64.b64encode(json.dumps(self.config).encode()).decode())
        if path.startswith('git/commits/'):
            return dict(tree=dict(sha='e'*40))
        if path.startswith('compare/'):
            return dict(status='diverged')
        if path.startswith('pulls?'):
            return self.pulls
        raise AssertionError(path)

    def test_main_creates_next_major_with_only_version_configuration_changed(self):
        result = line.initialize('main', 'latest')
        self.assertEqual((result['branch'], result['version']), ('release/2.x', '2.0.0'))
        self.assertEqual(self.heads['main'], SHA)
        tree = next(data for path, data, _ in self.writes if path == 'git/trees')
        self.assertEqual(len(tree['tree']), 1)
        self.assertEqual(tree['tree'][0]['path'], 'version.json')
        self.assertEqual(json.loads(tree['tree'][0]['content']), dict(version='2.0.0', tag='dev'))
        self.assertEqual(set(self.tags), {'release-preparations/100'})

    def test_numbering_uses_highest_matching_line_and_ignores_historical_names(self):
        config = dict(version='1.0.0')
        branches = ['release/1.x', 'release/2.x', 'release/1.1.x', 'release/1.3.x',
                    'release/9.0.0-beta.1', 'release/dev-11-10-2026']
        self.assertEqual(line.next_branch('main', config, branches), ('release/3.x', '3.0.0'))
        self.assertEqual(line.next_branch('release/1.x', config, branches), ('release/1.4.x', '1.4.0'))
        self.assertEqual(line.next_branch('main', dict(version='5.2.3'), branches), ('release/5.x', '5.0.0'))

    def test_release_source_creates_first_then_next_child(self):
        for existing, expected in ((None, '1.1'), ('release/1.1.x', '1.2')):
            with self.subTest(existing=existing):
                self.tags.clear()
                self.heads = {'main': SHA, 'release/1.x': SHA}
                if existing:
                    self.heads[existing] = SHA
                result = line.initialize('release/1.x', 'latest')
                self.assertEqual((result['branch'], result['version']), ('release/'+expected+'.x', expected+'.0'))
                self.assertEqual(self.heads['release/1.x'], SHA)

    def test_patch_updates_only_selected_branch_and_retry_does_not_increment_again(self):
        result = line.initialize('release/1.x', 'latest', 'increment-patch')
        self.assertEqual((result['branch'], result['version']), ('release/1.x', '1.0.1'))
        self.assertEqual(self.heads, {'main': SHA, 'release/1.x': PREPARED})
        writes = len(self.writes)
        self.assertEqual(line.initialize('release/1.x', 'latest', 'increment-patch'), result)
        self.assertEqual(len(self.writes), writes)

    def test_child_branch_patch_and_new_minor(self):
        self.config['version'] = '1.1.9'
        self.heads['release/1.1.x'] = SHA
        result = line.initialize('release/1.1.x', 'latest', 'increment-patch')
        self.assertEqual(result['version'], '1.1.10')
        self.assertEqual(self.heads['release/1.x'], SHA)
        self.tags.clear()
        result = line.initialize('release/1.1.x', 'latest')
        self.assertEqual((result['branch'], result['version']), ('release/1.2.x', '1.2.0'))

    def test_retry_after_uncertain_ref_response_recovers_same_result(self):
        for source, action in (('main', 'new-branch'), ('release/1.x', 'increment-patch')):
            with self.subTest(action=action):
                self.tags.clear()
                self.heads = {'main': SHA, 'release/1.x': SHA}
                self.fail_after_ref = True
                with self.assertRaises(subprocess.CalledProcessError):
                    line.initialize(source, 'latest', action)
                writes = len(self.writes)
                result = line.initialize(source, 'latest', action)
                self.assertEqual(result['commit'], PREPARED)
                self.assertEqual(len(self.writes), writes)

    def test_retry_rejects_changed_inputs(self):
        line.initialize('main', 'latest')
        with self.assertRaisesRegex(ValueError, 'original inputs'):
            line.initialize('release/1.x', 'latest')

    def test_unsupported_preparation_schema_is_rejected_without_conversion(self):
        line.initialize('main', 'latest')
        sha, info = self.tags['release-preparations/100']
        self.tags['release-preparations/100'] = (sha, dict(info, schemaVersion=2))
        writes = len(self.writes)
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            line.initialize('main', 'latest')
        self.assertEqual(len(self.writes), writes)

    def test_retry_does_not_overwrite_a_conflicting_target_branch(self):
        line.initialize('main', 'latest')
        self.heads['release/2.x'] = 'f'*40
        writes = len(self.writes)
        with self.assertRaisesRegex(ValueError, 'refusing to overwrite'):
            line.initialize('main', 'latest')
        self.assertEqual(len(self.writes), writes)

    def test_patch_requires_head_and_new_branch_requires_ancestor(self):
        with patch.object(prepare, 'api', side_effect=lambda path, *args, **kwargs:
                          dict(status='ahead') if path.startswith('compare/') else self.api(path, *args, **kwargs)):
            with self.assertRaisesRegex(ValueError, 'current release branch head'):
                line.initialize('release/1.x', 'd'*40, 'increment-patch')
        with self.assertRaisesRegex(ValueError, 'source branch history'):
            line.initialize('main', 'd'*40)
        self.assertEqual(self.writes, [])

    def test_unrelated_api_failure_does_not_open_a_pr_or_retry_the_write(self):
        def api(path, data=None, **kwargs):
            if path.startswith('git/refs/heads/'):
                raise subprocess.CalledProcessError(1, 'gh', stderr='Resource not accessible (HTTP 403)')
            return self.api(path, data, **kwargs)
        with patch.object(prepare, 'api', side_effect=api), self.assertRaises(subprocess.CalledProcessError):
            line.initialize('release/1.x', 'latest', 'increment-patch')
        self.assertEqual(self.heads, {'main': SHA, 'release/1.x': SHA})
        self.assertEqual(self.pulls, [])

    def test_api_uses_patch_method_and_serializes_false_as_a_boolean(self):
        # Check the actual HTTP adapter, beyond the in-memory GitHub fixture.
        with patch.object(prepare, 'run', return_value='{}') as run:
            API('git/refs/heads/release/1.x', dict(sha=PREPARED, force=False), method='PATCH')
            self.assertIn('PATCH', run.call_args.args)
            self.assertIs(json.loads(run.call_args.kwargs['input'])['force'], False)

    def test_moved_sources_are_rejected_without_updating_any_ref(self):
        self.moved = True
        for source, action in (('main', 'new-branch'), ('release/1.x', 'increment-patch')):
            with self.subTest(action=action):
                self.tags.clear()
                self.heads = {'main': SHA, 'release/1.x': SHA}
                self.writes.clear()
                with self.assertRaisesRegex(ValueError, 'branch moved'):
                    line.initialize(source, 'latest', action)
                self.assertFalse(any(path.startswith('git/refs') for path, _, _ in self.writes))

    def test_protected_patch_opens_one_pr_and_keeps_release_branch_unchanged(self):
        self.protected = True
        result = line.initialize('release/1.x', 'latest', 'increment-patch')
        self.assertIn('pullRequest', result)
        self.assertEqual(self.heads['release/1.x'], SHA)
        self.assertEqual(self.heads['release-preparation/100'], PREPARED)
        self.assertEqual(line.initialize('release/1.x', 'latest', 'increment-patch'), result)
        self.assertEqual(len(self.pulls), 1)
        self.pulls[0]['state'] = 'closed'
        with self.assertRaisesRegex(ValueError, 'PR is closed'):
            line.initialize('release/1.x', 'latest', 'increment-patch')

    def test_retry_after_uncertain_pr_creation_discovers_the_existing_pr(self):
        self.protected = True
        def api(path, data=None, **kwargs):
            result = self.api(path, data, **kwargs)
            if path == 'pulls':
                raise subprocess.CalledProcessError(1, 'gh', stderr='connection lost after PR creation')
            return result
        with patch.object(prepare, 'api', side_effect=api), self.assertRaises(subprocess.CalledProcessError):
            line.initialize('release/1.x', 'latest', 'increment-patch')
        result = line.initialize('release/1.x', 'latest', 'increment-patch')
        self.assertEqual(len(self.pulls), 1)
        self.assertEqual(result['pullRequest'], self.pulls[0]['html_url'])

    def test_invalid_source_action_and_version_fail_without_writes(self):
        for source, action in (('main', 'increment-patch'), ('release/garbage', 'new-branch'),
                               ('feature/foo', 'new-branch'), ('main', 'minor')):
            with self.subTest(source=source, action=action), self.assertRaises(ValueError):
                line.initialize(source, 'latest', action)
        self.config['version'] = '2.0.0'
        with self.assertRaisesRegex(ValueError, 'differs'):
            line.initialize('release/1.x', 'latest')
        self.config['version'] = '1.0.65535'
        with self.assertRaisesRegex(ValueError, '65535'):
            line.initialize('release/1.x', 'latest', 'increment-patch')
        self.assertEqual(self.writes, [])

    def test_workflow_exposes_selector_and_serializes_preparations(self):
        workflow = yaml.safe_load((ROOT/'.github/workflows/create-release-branch.yml').read_text())
        action = workflow['on']['workflow_dispatch']['inputs']['action']
        self.assertEqual(action['options'], ['new-branch', 'increment-patch'])
        self.assertEqual(action['default'], 'new-branch')
        self.assertFalse(workflow['concurrency']['cancel-in-progress'])
        job = workflow['jobs']['prepare']
        self.assertEqual(job['if'], "github.ref == 'refs/heads/main'")
        self.assertEqual(job['permissions']['pull-requests'], 'write')
        self.assertEqual(job['steps'][-1]['env']['RELEASE_ACTION'], '${{ inputs.action }}')
