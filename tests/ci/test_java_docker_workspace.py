# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Gradle configures every included project even when only Control is built."""
from pathlib import Path, PurePosixPath
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class JavaDockerWorkspaceTests(unittest.TestCase):
    def test_control_build_copies_every_gradle_project(self):
        members = re.findall(r'project\("[^"]+"\)\.projectDir = file\("([^"]+)"\)',
                             (ROOT/'settings.gradle.kts').read_text())
        copied = set()
        for source, destination in re.findall(r'^COPY (\S+) (\S+)\s*$',
                                             (ROOT/'apps/control-plane/Dockerfile').read_text(), re.MULTILINE):
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
