# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Every Docker build importing root Gradle settings needs all included projects."""
from pathlib import Path, PurePosixPath
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class JavaDockerWorkspaceTests(unittest.TestCase):
    def test_root_gradle_builds_copy_every_project(self):
        members = re.findall(r'project\("[^"]+"\)\.projectDir = file\("([^"]+)"\)',
                             (ROOT/'settings.gradle.kts').read_text())
        candidates = [*ROOT.glob('apps/*/Dockerfile'), *ROOT.glob('examples/**/*.Dockerfile'),
                      *ROOT.glob('debug/*.Dockerfile')]
        builders = [path for path in candidates if re.search(
            r'^COPY [^\n]*\bsettings\.gradle\.kts\b[^\n]*$', path.read_text(), re.MULTILINE)]
        self.assertTrue(builders, 'At least one root Gradle Docker build must be checked')
        for dockerfile in builders:
            with self.subTest(dockerfile=str(dockerfile.relative_to(ROOT))):
                self.assert_copies_every_project(dockerfile, members)

    def assert_copies_every_project(self, dockerfile, members):
        copied = set()
        for source, destination in re.findall(r'^COPY (\S+) (\S+)\s*$',
                                             dockerfile.read_text(), re.MULTILINE):
            source_path = ROOT/source
            for member in members:
                build = ROOT/member/'build.gradle.kts'
                if source_path.is_dir() and build.is_relative_to(source_path):
                    copied.add(PurePosixPath(destination)/build.relative_to(source_path).as_posix())
                elif source_path == build:
                    copied.add(PurePosixPath(destination))
        for member in members:
            with self.subTest(member=member):
                self.assertIn(PurePosixPath(member)/'build.gradle.kts', copied)


if __name__ == '__main__':
    unittest.main()
