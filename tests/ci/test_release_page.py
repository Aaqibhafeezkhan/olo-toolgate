# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import unittest
from tools.release.page import render


class ReleasePageTests(unittest.TestCase):
    def test_dynamic_identity_channels_registries_and_contributors(self):
        fragments = [dict(contributor=dict(name='Zoë', github='human'), changes=[
            dict(type='fixed', summary='Repair startup.', pr=123)])]
        for version, status in [('2.1.3-beta.7', 'Beta Release'), ('2.1.3-rc.8', 'Release Candidate'), ('2.1.3', 'Stable Release')]:
            info = dict(version=version, commit='a'*40)
            receipt = dict(images=dict(control='ghcr.io/example/control@sha256:abc'),
                           dockerhub=dict(control='docker.io/example/control@sha256:abc'))
            page = render(info, fragments, receipt, repository='example/project')
            self.assertEqual(page, render(info, fragments, receipt, repository='example/project'))
            for expected in (version, status, 'Zoë', 'Repair startup', 'example/project/blob/v'+version,
                             receipt['images']['control'], receipt['dockerhub']['control']):
                self.assertIn(expected, page)
            self.assertNotIn('0.10.0-dev', page)
            self.assertNotIn('1.0.0-beta.3', page)

    def test_invalid_metadata_is_rejected(self):
        for info in [dict(version='../bad', commit='a'*40), dict(version='1.0.0', commit='bad')]:
            with self.assertRaises(ValueError):
                render(info, [])

    def test_bundle_generation_uses_page_without_changing_source_snapshot(self):
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch
        from tools.release import notes
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'release.json').write_text(json.dumps(dict(version='2.0.0-beta.1', sourceCommit='a'*40)), encoding='utf-8')
            snapshot = root/'RELEASE-NOTES.md'
            snapshot.write_text('Historical snapshot', encoding='utf-8')
            with patch.object(notes, 'load', return_value=('v2.0.0-beta.1', [])):
                notes.generate(root, root/'output')
            page = (root/'output/RELEASE-NOTES.md').read_text(encoding='utf-8')
            self.assertIn('Beta Release', page)
            self.assertNotIn('Historical snapshot', page)
            self.assertEqual(snapshot.read_text(encoding='utf-8'), 'Historical snapshot')


if __name__ == '__main__':
    unittest.main()
