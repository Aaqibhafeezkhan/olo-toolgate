# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Every example tool folder with a build.gradle.kts has its sources in Git.

The log-insights example was once committed without src/: the root .gitignore rule `logs/` matched its Java package
`io/ololabs/examples/logs`. This check fails if an ignore rule ever hides an example's sources again."""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ExampleSourceTests(unittest.TestCase):
    def test_every_example_build_has_tracked_main_sources(self):
        tracked = set(subprocess.run(['git', 'ls-files', 'examples'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.splitlines())
        builds = sorted(Path(p).parent for p in tracked
                        if p.endswith('/build.gradle.kts') and len(Path(p).parts) == 4
                        and Path(p).parts[1] in ('java-tools', 'mocked-tools'))
        self.assertGreater(len(builds), 10)
        for folder in builds:
            with self.subTest(example=folder.as_posix()):
                sources = [p for p in tracked if p.startswith(f'{folder.as_posix()}/src/main/') and p.endswith('.java')]
                self.assertTrue(sources, f'{folder}/src/main has no tracked Java sources')

    def test_the_logs_ignore_rule_does_not_hide_java_packages(self):
        path = 'examples/java-tools/sample/src/main/java/io/ololabs/examples/logs/Tool.java'
        ignored = subprocess.run(['git', 'check-ignore', '-q', '--no-index', path], cwd=ROOT).returncode == 0
        self.assertFalse(ignored, f'{path} is ignored')

if __name__ == '__main__':
    unittest.main()
