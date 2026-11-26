# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Core MCP dependency reuse must preserve pinned builds and all qualification gates."""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class CoreMcpCacheTests(unittest.TestCase):
    def setUp(self):
        self.job = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text(encoding='utf-8'))['jobs']['core-mcp']
        self.steps = self.job['steps']
        self.restore = next(step for step in self.steps if step.get('id') == 'core-mcp-cache')
        self.save = next(step for step in self.steps if step.get('name') == 'Save verified Core MCP dependencies')

    def test_cache_contains_only_downloads_and_resolution_metadata(self):
        self.assertTrue(self.job['env']['GRADLE_USER_HOME'].endswith('/.dev/core-mcp-gradle'))
        paths = self.restore['with']['path'].splitlines()
        self.assertEqual(paths, [
            '${{ env.GRADLE_USER_HOME }}/caches/modules-2/files-2.1',
            '${{ env.GRADLE_USER_HOME }}/caches/modules-2/metadata-*'])
        self.assertEqual(self.restore['with']['path'], self.save['with']['path'])
        for step, action in ((self.restore, 'restore'), (self.save, 'save')):
            self.assertEqual(step['uses'], 'actions/cache/'+action+'@0057852bfaa89a56745cba8c7296529d2fc39830')

    def test_cache_key_tracks_pins_before_build_version_stamping(self):
        key = self.restore['with']['key']
        for value in ('runner.os', 'runner.arch', 'jdk21', '**/*.gradle.kts', '**/*.gradle',
                      '**/gradle.lockfile', '**/gradle-artifact.lockfile', 'gradle/libs.versions.toml',
                      'gradle/wrapper/gradle-wrapper.properties'):
            self.assertIn(value, key)
        for value in ('run_id', 'run_number', 'build_number', 'version.json', "'VERSION'"):
            self.assertNotIn(value, key)
        self.assertEqual(self.save['with']['key'], '${{ steps.core-mcp-cache.outputs.cache-primary-key }}')
        self.assertTrue(key.startswith(self.restore['with']['restore-keys']))
        stamp = next(step for step in self.steps if step.get('name') == 'Derive build versions from version.json')
        self.assertLess(self.steps.index(self.restore), self.steps.index(stamp))

    def test_cache_hit_and_miss_keep_checks_and_success_only_saving(self):
        build = next(step for step in self.steps if step.get('name') == 'Build configured core MCP packages')
        profiles = next(step for step in self.steps if step.get('name') == 'Build versioned profiles including their tool and trigger archives')
        for step in (build, profiles):
            self.assertNotIn('if', step)
            self.assertNotIn('continue-on-error', step)
        for gate in ('core-mcp/test_build.py', 'core-mcp/build.py --cli', ':control-plane:sdkWorkerTest',
                     'unittest discover -s tool-sdk/python-sdk/tests'):
            self.assertIn(gate, build['run'])
        self.assertNotIn('--without-tests', build['run'])
        self.assertIn('test_profiles.py', profiles['run'])
        self.assertIn('profilesBuiltAcceptance.test.ts', profiles['run'])
        self.assertLess(self.steps.index(profiles), self.steps.index(self.save))
        self.assertTrue(self.save['if'].startswith('success() &&'))
        self.assertIn("cache-hit != 'true'", self.save['if'])
        self.assertNotIn('always()', self.save['if'])


if __name__ == '__main__':
    unittest.main()
