# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""PR metadata remains read-only; current notes are generated during release."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from tools.contracts import version


class ReleaseMetadataRegression(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        outputs = version.expected()
        sources = set(outputs) | {version.ROOT/'VERSION', version.ROOT/'packages/contracts/VERSION',
                                  version.ROOT/'CHANGELOG.md', version.ROOT/'RELEASE-NOTES.md'}
        for source in sources:
            target = self.root/source.relative_to(version.ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(outputs[source].encode('utf-8') if source in outputs else source.read_bytes())

    def run_version(self, check=True):
        with patch.object(version, 'ROOT', self.root), patch('sys.argv',
                ['version.py', '--check'] if check else ['version.py']), contextlib.redirect_stdout(io.StringIO()):
            version.main()

    def test_changelog_edit_does_not_require_or_rewrite_release_snapshot(self):
        notes = self.root/'RELEASE-NOTES.md'
        original = notes.read_bytes()
        changelog = self.root/'CHANGELOG.md'
        changelog.write_bytes(changelog.read_bytes()+b'\n- New behavior for next release.\n')
        self.run_version()
        self.run_version(check=False)
        self.assertEqual(original, notes.read_bytes())
        self.assertIn('New behavior for next release.', version.release_notes(self.root))

    def test_release_render_uses_current_versions_and_is_deterministic(self):
        (self.root/'VERSION').write_text('0.10.1-dev\n', encoding='utf-8')
        notes = self.root/'RELEASE-NOTES.md'
        original = notes.read_bytes()
        self.run_version(check=False)
        self.run_version()
        rendered = version.release_notes(self.root)
        self.assertIn('# OLO ToolGate 0.10.1-dev release notes', rendered)
        self.assertEqual(rendered, version.release_notes(self.root))
        self.assertEqual(original, notes.read_bytes())
