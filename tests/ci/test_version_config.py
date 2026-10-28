# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""The version config is shared; channels materialize only in build checkouts."""
import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import yaml
from tools.release import config, prepare, identity

ROOT = Path(__file__).resolve().parents[2]


class VersionConfigTests(unittest.TestCase):
    def test_dev_beta_rc_and_stable_have_matching_package_coordinates(self):
        for tag, expected in [('dev', '1.0.0-dev.1'), ('beta', '1.0.0-beta.1'),
                              ('rc', '1.0.0-rc.1'), ('stable', '1.0.0')]:
            info = config.coordinates(dict(version='1.0.0', tag=tag), 1)
            self.assertEqual(info['version'], expected)
            self.assertEqual(info['tag'], 'v'+expected)
            self.assertEqual(info['numericVersion'], '1.0.0.1')

    def test_preparing_a_release_changes_only_the_version_config(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'version.json').write_text(json.dumps(dict(version='1.0.0', tag='dev')))
            (root/'VERSION').write_text('1.0.0-dev.1\n')
            prepare.stamp(root, dict(schemaVersion=1, **identity.versions('1.0.0', 1, 'Beta'), sourceBranch='main', sourceCommit='a'*40))
            self.assertEqual(json.loads((root/'version.json').read_text()), dict(version='1.0.0', tag='beta'))
            self.assertEqual((root/'VERSION').read_text(), '1.0.0-dev.1\n')
            self.assertFalse((root/'release.json').exists())

    def test_materialization_uses_config_and_retries_keep_the_same_run_number(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'version.json').write_text(json.dumps(dict(version='1.0.0', tag='dev')))
            before = (root/'version.json').read_bytes()
            with patch.dict(os.environ, GITHUB_REF_NAME='main', GITHUB_RUN_NUMBER='7', GITHUB_WORKFLOW='CI'), \
                    patch.object(prepare, 'run', return_value='a'*40), \
                    patch.object(prepare, 'stamp_versions') as stamp:
                for attempt in ('1', '2'):
                    with patch.dict(os.environ, GITHUB_RUN_ATTEMPT=attempt):
                        config.materialize(root)
                    self.assertEqual(stamp.call_args.args[1]['version'], '1.0.0-dev.7')
            self.assertEqual(before, (root/'version.json').read_bytes())

    def test_exact_release_tag_supplies_the_build_number(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'version.json').write_text(json.dumps(dict(version='1.0.0', tag='beta')))
            with patch.dict(os.environ, GITHUB_REF_NAME='v1.0.0-beta.1', GITHUB_RUN_NUMBER='99', GITHUB_WORKFLOW='CI'), \
                    patch.object(prepare, 'run', return_value='a'*40), \
                    patch.object(prepare, 'stamp_versions') as stamp:
                config.materialize(root)
                self.assertEqual(stamp.call_args.args[1]['version'], '1.0.0-beta.1')
                (root/'version.json').write_text(json.dumps(dict(version='1.0.0', tag='dev')))
                with self.assertRaisesRegex(ValueError, 'channel differs'):
                    config.materialize(root)

    def test_major_release_line_accepts_minor_updates_and_rejects_breaking_sync(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config_file = root/'version.json'
            config_file.write_text(json.dumps(dict(version='1.1.0', tag='beta')))
            with patch.dict(os.environ, GITHUB_REF_NAME='release/1.x', GITHUB_RUN_NUMBER='2', GITHUB_WORKFLOW='CI'), \
                    patch.object(prepare, 'run', return_value='a'*40), \
                    patch.object(prepare, 'stamp_versions') as stamp:
                config.materialize(root)
                self.assertEqual(stamp.call_args.args[1]['branch'], 'release/1.x')
                self.assertEqual(stamp.call_args.args[1]['version'], '1.1.0-beta.2')
                config_file.write_text(json.dumps(dict(version='2.0.0', tag='beta')))
                with self.assertRaisesRegex(ValueError, 'major/channel'):
                    config.materialize(root)

    def test_invalid_and_extra_config_fields_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for document in [dict(version='01.0.0', tag='dev'), dict(version='1.0.0', tag='oops'),
                             dict(version='1.0.0', tag='dev', build=1)]:
                (root/'version.json').write_text(json.dumps(document))
                with self.assertRaises(ValueError):
                    config.load(root)

    def test_prepared_config_is_validated_against_immutable_release_identity(self):
        info = dict(schemaVersion=1, **identity.versions('1.0.0', 1, 'Beta'), sourceBranch='main', sourceCommit='a'*40)
        document = dict(version='1.0.0', tag='beta')
        def api(path):
            if path.startswith('git/trees/'):
                return dict(tree=[dict(path='version.json')])
            return dict(content=base64.b64encode(json.dumps(document).encode()).decode())
        with patch.object(prepare, 'tag', return_value=('b'*40, info)), \
                patch.object(prepare, 'ref', return_value=dict(object=dict(sha='b'*40))), \
                patch.object(prepare, 'api', side_effect=api):
            self.assertEqual(prepare.resolve('release/1.0.0-beta.1'), ('b'*40, info))
            document['tag'] = 'rc'
            with self.assertRaisesRegex(ValueError, 'manifest differs'):
                prepare.resolve('release/1.0.0-beta.1')

    def test_build_workflows_materialize_versions_before_compilation(self):
        for name in ('foundation', 'client', 'images', 'preflight', 'quality-summary'):
            document = yaml.safe_load((ROOT/f'.github/workflows/{name}.yml').read_text())
            for job in document['jobs'].values():
                steps = job.get('steps', [])
                if any('actions/setup-python@' in step.get('uses', '') for step in steps):
                    self.assertTrue(any('python tools/release/config.py' in step.get('run', '') for step in steps), name)
