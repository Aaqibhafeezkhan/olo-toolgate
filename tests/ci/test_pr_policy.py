# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import tempfile
import unittest
import yaml
from tools.ci.pr_policy import check_paths
from tools.release import notes


class PullRequestPolicyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.folder = self.root/'.changes/v0.1.0-beta.1'
        self.folder.mkdir(parents=True)
        (self.root/'.changes/config.json').write_text(json.dumps({'version': 'v0.1.0-beta.1'}))
        self.document = dict(schemaVersion=1, contributor=dict(name='Human contributor'),
                             changes=[dict(type='fixed', summary='Support OpenAI API responses.')])

    def write(self, document=None, name='1-human.yaml'):
        (self.folder/name).write_text(yaml.safe_dump(document or self.document), encoding='utf-8')

    def test_human_credit_and_ai_product_description_are_allowed(self):
        self.write()
        notes.load(self.root)
        check_paths(['CHANGELOG.md', 'apps/gateway/src/validation.rs'])

    def test_ai_filename_and_primary_attribution_are_rejected(self):
        self.write(name='3-OpenAI-Codex.yaml')
        with self.assertRaisesRegex(ValueError, 'AI identities'):
            notes.load(self.root)
        (self.folder/'3-OpenAI-Codex.yaml').unlink()
        for field, value in [('name', 'OpenAI Codex'), ('github', 'chatgpt')]:
            document = copy.deepcopy(self.document)
            document['contributor'][field] = value
            self.write(document)
            with self.assertRaisesRegex(ValueError, 'AI identities'):
                notes.load(self.root)

    def test_nested_ai_owner_reviewer_and_ticket_author_are_rejected(self):
        for change in [
                {'owners': [{'name': 'OpenAI Codex'}]},
                {'pullRequests': [{'number': 206, 'reviewers': [{'name': 'ChatGPT'}]}]},
                {'tickets': [{'system': 'github', 'id': '205', 'url': 'https://github.com/olo-labs/olo-toolgate/issues/205',
                              'summaryAuthor': {'name': 'OpenAI Codex'}}]}]:
            document = copy.deepcopy(self.document)
            document['changes'][0].update(change)
            self.write(document)
            with self.assertRaisesRegex(ValueError, 'AI identities'):
                notes.load(self.root)

    def test_generated_notes_cannot_change_in_any_package(self):
        for path in ['RELEASE-NOTES.md', 'packages/contracts/php/RELEASE-NOTES.md']:
            with self.assertRaisesRegex(ValueError, 'only during release'):
                check_paths([path])

    def test_release_output_is_current_without_touching_source_snapshot(self):
        self.write()
        (self.root/'packages/contracts').mkdir(parents=True)
        (self.root/'VERSION').write_text('1.2.3\n')
        (self.root/'packages/contracts/VERSION').write_text('1.2.3\n')
        (self.root/'CHANGELOG.md').write_text('# Changelog\n\n- Current fix.\n')
        snapshot = self.root/'RELEASE-NOTES.md'
        snapshot.write_text('Old release snapshot\n')
        output = self.root/'build/release'
        notes.generate(self.root, output)
        rendered = (output/'RELEASE-NOTES.md').read_text()
        self.assertIn('1.2.3 release notes', rendered)
        self.assertIn('Current fix.', rendered)
        self.assertEqual('Old release snapshot\n', snapshot.read_text())

    def test_pr_workflow_always_runs_read_only_and_checks_exact_commits(self):
        workflow = yaml.load((notes.ROOT/'.github/workflows/pr-policy.yml').read_text(), Loader=yaml.BaseLoader)
        self.assertIn('pull_request', workflow['on'])
        self.assertNotIn('pull_request_target', workflow['on'])
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        steps = workflow['jobs']['contribution-policy']['steps']
        checkout = steps[0]['with']
        self.assertEqual('0', checkout['fetch-depth'])
        self.assertEqual('false', checkout['persist-credentials'])
        gate = next(step for step in steps if 'pr_policy.py --base' in step.get('run', ''))
        self.assertNotIn('if', gate)
        self.assertNotIn('continue-on-error', gate)
