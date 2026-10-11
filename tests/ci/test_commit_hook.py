# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise the installed commit-message gate through actual Git commits."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('node'), 'Node is required for Git hook integration tests')
class CommitHookTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = dict(os.environ, TOOLGATE_CI_PYTHON=sys.executable)
        self.env.pop('TOOLGATE_CI_ACT', None)
        # These fixtures own their Git metadata, even when tests run from a hook.
        variables = subprocess.check_output(['git', 'rev-parse', '--local-env-vars'],
                                            cwd=ROOT, text=True).splitlines()
        for name in variables:
            self.env.pop(name, None)
        self.git('init', '--quiet', '--template=', '--initial-branch=fixture')
        self.git('config', 'user.name', 'Hook fixture')
        self.git('config', 'user.email', 'hook@example.invalid')
        self.git('config', 'core.hooksPath', '.githooks')
        (self.root / '.githooks').mkdir()
        (self.root / 'tools/ci').mkdir(parents=True)
        for name in ('pre-commit', 'commit-msg'):
            target = self.root / '.githooks' / name
            shutil.copy2(ROOT / '.githooks' / name, target)
            target.chmod(0o755)
        shutil.copy2(ROOT / 'tools/ci/commit-msg.mjs', self.root / 'tools/ci/commit-msg.mjs')
        # Stub expensive checks; the real hook and Git lifecycle are exercised.
        (self.root / 'tools/ci/precommit.mjs').write_text(
            "import { mkdirSync, writeFileSync } from 'node:fs';\n"
            "mkdirSync('build', {recursive:true}); writeFileSync('build/small', 'ran');\n",
            encoding='utf-8')
        self.runner = self.root / 'tools/ci/local.py'
        self.runner.write_text(
            "import json, os, pathlib, sys\n"
            "pathlib.Path('build').mkdir(exist_ok=True)\n"
            "pathlib.Path('build/full.json').write_text(json.dumps({\n"
            "  'args': sys.argv[1:], 'gitIndex': os.environ.get('GIT_INDEX_FILE'),\n"
            "  'gitDir': os.environ.get('GIT_DIR')}))\n",
            encoding='utf-8')
        (self.root / '.gitignore').write_text('build/\n', encoding='utf-8')
        (self.root / 'source.txt').write_text('staged source\n', encoding='utf-8')
        self.git('add', '.')

    def git(self, *args, check=True):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env,
                              capture_output=True, text=True, check=check)

    def commit(self, subject, *args):
        return self.git('commit', *args, '-m', subject, check=False)

    def full_result(self):
        return json.loads((self.root / 'build/full.json').read_text(encoding='utf-8'))

    def assert_blocked(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotEqual(self.git('rev-parse', '--verify', 'HEAD', check=False).returncode, 0)

    def test_ordinary_commit_keeps_small_checks_without_python_or_full_ci(self):
        self.env['TOOLGATE_CI_PYTHON'] = 'missing-python-fixture'
        result = self.commit('docs: ordinary change')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'build/small').is_file())
        self.assertFalse((self.root / 'build/full.json').exists())

    def test_rc_in_body_or_lowercase_subject_does_not_trigger_full_ci(self):
        for message in ('docs: change\n\nRC: body text', 'rc: lowercase'):
            result = self.commit(message, '--allow-empty')
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / 'build/full.json').exists())

    def test_rc_subject_replays_inventory_and_clears_git_environment(self):
        act_path = str(self.root / 'path with spaces/act')
        self.env['TOOLGATE_CI_ACT'] = act_path
        result = self.commit('RC: ready for CI')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'build/small').is_file())
        full = self.full_result()
        self.assertEqual(full['args'], ['run', '--replayable', '--act', act_path])
        self.assertIsNone(full['gitIndex'])
        self.assertIsNone(full['gitDir'])

    def test_failed_replay_blocks_rc_commit(self):
        with self.runner.open('a', encoding='utf-8') as stream:
            stream.write('sys.exit(2)\n')
        self.git('add', '.')
        result = self.commit('RC: failing checks')
        self.assert_blocked(result)
        self.assertIn('blocked by local CI', result.stderr)

    def test_missing_python_blocks_rc_commit(self):
        self.env['TOOLGATE_CI_PYTHON'] = 'missing-python-fixture'
        result = self.commit('RC: missing tooling')
        self.assert_blocked(result)
        self.assertIn('blocked by local CI', result.stderr)

    def test_unstaged_source_blocks_rc_commit_before_replay(self):
        (self.root / 'source.txt').write_text('unstaged change\n', encoding='utf-8')
        result = self.commit('RC: wrong source')
        self.assert_blocked(result)
        self.assertIn('source to match the commit index', result.stderr)
        self.assertFalse((self.root / 'build/full.json').exists())

    def test_untracked_source_blocks_rc_commit_before_replay(self):
        (self.root / 'untracked.txt').write_text('unstaged new source\n', encoding='utf-8')
        result = self.commit('RC: extra source')
        self.assert_blocked(result)
        self.assertIn('Stage or ignore untracked source', result.stderr)
        self.assertFalse((self.root / 'build/full.json').exists())

    def test_source_change_during_replay_blocks_rc_commit(self):
        with self.runner.open('a', encoding='utf-8') as stream:
            stream.write("pathlib.Path('source.txt').write_text('changed during replay')\n")
        self.git('add', '.')
        self.assert_blocked(self.commit('RC: changing source'))

    def test_index_change_during_replay_blocks_rc_commit(self):
        with self.runner.open('a', encoding='utf-8') as stream:
            stream.write("import subprocess\n"
                         "pathlib.Path('new.txt').write_text('new staged source')\n"
                         "subprocess.run(['git', 'add', 'new.txt'], check=True)\n")
        self.git('add', '.')
        result = self.commit('RC: changing index')
        self.assert_blocked(result)
        self.assertIn('commit index changed', result.stderr)

    def test_amending_to_an_rc_subject_also_runs_full_ci(self):
        self.assertEqual(self.commit('docs: initial').returncode, 0)
        result = self.commit('RC: amended subject', '--amend')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.full_result()['args'], ['run', '--replayable'])


if __name__ == '__main__':
    unittest.main()
