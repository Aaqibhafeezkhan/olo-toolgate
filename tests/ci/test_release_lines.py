# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""A major release line moves; each build identity and artifact producer stay pinned."""
import base64
import json
import os
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest
from tools.release import line, prepare, identity

SHA = 'a'*40
BRANCH = 'release/1.x'
REPO = 'olo-labs/olo-toolgate'


class ReleaseLineTests(unittest.TestCase):
    def setUp(self):
        # Ignore the runner's real dispatch event, whose channel input would leak into these tests.
        self.env = patch.dict(os.environ, GITHUB_EVENT_PATH='', GITHUB_SHA=SHA, GITHUB_REF='refs/heads/'+BRANCH,
                              GITHUB_RUN_ID='100', GITHUB_REPOSITORY=REPO, BUILD_RUN_ID='', CHANNEL='beta')
        self.env.start()
        self.addCleanup(self.env.stop)
        self.records = {}
        self.tags = {}
        self.config = dict(version='1.0.0', tag='dev')

    def api(self, path, data=None):
        if path.startswith('contents/version.json'):
            return dict(content=base64.b64encode(json.dumps(self.config).encode()).decode())
        if path.startswith('commits/'):
            return dict(sha=SHA)
        if path.startswith('git/tags/'):
            return dict(message=json.dumps(self.records[path.rsplit('/',1)[1]]))
        if path.startswith('actions/runs/'):
            return dict(head_branch=BRANCH, head_sha=SHA, path='.github/workflows/build-release.yml',
                        event='workflow_dispatch', repository=dict(full_name=REPO))
        raise AssertionError(path)

    def pages(self, path):
        if path.startswith('git/matching-refs/tags/release-builds/'):
            return [[dict(ref='refs/tags/release-builds/'+self.config['version']+'/'+number, object=dict(sha=number)) for number in self.records]]
        return [[]]

    def create(self, name, sha, record):
        if name.startswith('release-builds/'):
            self.records[name.rsplit('/',1)[1]] = record
        if name in self.tags:
            self.assertEqual(self.tags[name], (sha, record))
        self.tags[name] = (sha, record)

    def test_new_builds_increment_retries_reuse_and_moved_line_can_publish_original_bundle(self):
        with patch.object(prepare, 'api', side_effect=self.api), patch.object(prepare, 'pages', side_effect=self.pages), \
                patch.object(prepare, 'create_tag', side_effect=self.create), patch.object(prepare, 'tag', side_effect=self.tags.get):
            sha, first = line.resolve(BRANCH)
            self.assertEqual(first['version'], '1.0.0-beta.1')
            self.assertEqual(first['branch'], BRANCH)
            self.assertEqual(line.resolve(BRANCH), (sha, first))
            self.assertEqual(len(self.records), 1)
            with patch.dict(os.environ, GITHUB_RUN_ID='101'):
                _, second = line.resolve(BRANCH)
            self.assertEqual(second['version'], '1.0.0-beta.2')
            with patch.dict(os.environ, GITHUB_RUN_ID='102', BUILD_RUN_ID='100', GITHUB_SHA='b'*40):
                self.assertEqual(line.resolve(BRANCH), (SHA, first))
            self.assertEqual(len(self.records), 2)

    def test_release_job_input_selects_channel_and_release_has_no_suffix(self):
        with patch.object(prepare, 'api', side_effect=self.api), patch.object(prepare, 'pages', side_effect=self.pages), \
                patch.object(prepare, 'create_tag', side_effect=self.create), patch.object(prepare, 'tag', side_effect=self.tags.get), \
                patch.object(prepare, 'ref', return_value=None):
            versions = []
            for run, channel in enumerate(('dev', 'alpha', 'beta', 'rc', 'release', ''), 200):
                with patch.dict(os.environ, GITHUB_RUN_ID=str(run), CHANNEL=channel):
                    versions.append(line.resolve(BRANCH)[1]['version'])
        # One shared build sequence; the release version omits channel and build number.
        self.assertEqual(versions, ['1.0.0-dev.1', '1.0.0-alpha.2', '1.0.0-beta.3', '1.0.0-rc.4', '1.0.0', '1.0.0-dev.6'])

    def test_reusable_workflows_read_the_channel_from_the_dispatch_event(self):
        with tempfile.TemporaryDirectory() as temporary:
            event = Path(temporary)/'event.json'
            event.write_text(json.dumps(dict(inputs=dict(channel='rc'))))
            with patch.dict(os.environ, CHANNEL='', GITHUB_EVENT_PATH=str(event)):
                self.assertEqual(line.requested_channel(), 'RC')
            with patch.dict(os.environ, CHANNEL='stable'), self.assertRaisesRegex(ValueError, 'dev, alpha, beta, rc or release'):
                line.requested_channel()

    def test_major_mismatch_fails_before_reserving(self):
        with patch.object(prepare, 'api', side_effect=self.api), patch.object(prepare, 'allocate') as allocate:
            self.config = dict(version='2.0.0', tag='dev')
            with self.assertRaisesRegex(ValueError, 'major differs'):
                line.resolve(BRANCH)
            allocate.assert_not_called()

    def test_cross_line_artifact_reuse_is_rejected(self):
        with patch.dict(os.environ, BUILD_RUN_ID='99'), patch.object(prepare, 'api', return_value=dict(head_branch='release/2.x')):
            with self.assertRaisesRegex(ValueError, 'original release build'):
                line.resolve(BRANCH)

    def test_minor_updates_stay_in_the_line_and_major_updates_need_a_new_line(self):
        info = dict(schemaVersion=1, **identity.versions('1.1.0', 2, 'RC'), sourceBranch=BRANCH, sourceCommit=SHA)
        info['branch'] = BRANCH
        identity.validate(info)
        info['baseVersion'] = '2.0.0'
        with self.assertRaisesRegex(ValueError, 'major version'):
            identity.validate(info)

    def test_child_line_builds_are_resolved_and_reused_within_the_same_branch(self):
        child = 'release/1.1.x'
        self.config['version'] = '1.1.0'
        with patch.dict(os.environ, GITHUB_REF='refs/heads/'+child), \
                patch.object(prepare, 'api', side_effect=self.api), patch.object(prepare, 'pages', side_effect=self.pages), \
                patch.object(prepare, 'create_tag', side_effect=self.create), patch.object(prepare, 'tag', side_effect=self.tags.get):
            sha, info = prepare.resolve(child)
            self.assertEqual(info['version'], '1.1.0-beta.1')
            self.assertEqual(info['branch'], child)
            self.assertEqual(prepare.resolve(child), (sha, info))
            with patch.dict(os.environ, BUILD_RUN_ID='100', GITHUB_SHA='b'*40), \
                    patch.object(prepare, 'api', side_effect=lambda path, *args:
                        dict(head_branch=child, head_sha=SHA, path='.github/workflows/build-release.yml',
                             event='workflow_dispatch', repository=dict(full_name=REPO))
                        if path.startswith('actions/runs/') else self.api(path, *args)):
                self.assertEqual(prepare.resolve(child), (sha, info))

    def test_child_minor_mismatch_fails_before_reservation(self):
        with patch.dict(os.environ, GITHUB_REF='refs/heads/release/1.1.x'), \
                patch.object(prepare, 'api', side_effect=self.api), patch.object(prepare, 'allocate') as allocate:
            with self.assertRaisesRegex(ValueError, 'minor differs'):
                line.resolve('release/1.1.x')
            allocate.assert_not_called()
        info = dict(schemaVersion=1, **identity.versions('1.2.0', 1, 'Beta'),
                    sourceBranch='release/1.1.x', sourceCommit=SHA)
        info['branch'] = 'release/1.1.x'
        with self.assertRaisesRegex(ValueError, 'minor version'):
            identity.validate(info)
