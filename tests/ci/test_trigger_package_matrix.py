# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Package discovery must not silently omit a source or enable live credentials in mock CI."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml
from tools.triggers import package_tests

ROOT = Path(__file__).resolve().parents[2]


class TriggerPackageMatrixTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.package = self.root/'packages/example'
        self.package.mkdir(parents=True)
        (self.package/'build.gradle.kts').write_text('// fixture')
        (self.package/'README.md').write_text('A deterministic test package, installed using a profile. It observes a mocked source, emits bounded events, and has no provider credentials.')
        self.data = dict(schemaVersion=1, providerMode='mock', packageId='acme.example',
                         buildRoot='packages', project='example', startConfig={'folder': 'inbox'})
        self.manifest = self.package/'trigger-tests.json'
        self.save()

    def save(self):
        self.manifest.write_text(json.dumps(self.data))

    def test_requires_an_explanatory_readme_in_each_package(self):
        self.assertEqual(package_tests.load(self.root, self.manifest)['directory'], 'packages/example')
        (self.package/'README.md').unlink()
        with self.assertRaisesRegex(ValueError, 'README'):
            package_tests.load(self.root, self.manifest)

    def test_refuses_live_provider_modes_and_unknown_manifest_formats(self):
        for field, value in [('providerMode', 'live'), ('schemaVersion', 2), ('unknown', True)]:
            original = dict(self.data)
            self.data[field] = value
            self.save()
            with self.assertRaises(ValueError):
                package_tests.load(self.root, self.manifest)
            self.data = original

    def test_cannot_choose_another_package_or_escape_the_checkout(self):
        for build in ['../outside', '/outside', 'packages/../packages', 'packages\\example']:
            self.data['buildRoot'] = build
            self.save()
            with self.assertRaises(ValueError):
                package_tests.load(self.root, self.manifest)
        self.data['buildRoot'] = 'packages'
        self.data['project'] = 'other'
        self.save()
        with self.assertRaisesRegex(ValueError, 'own Gradle package'):
            package_tests.load(self.root, self.manifest)

    def test_duplicate_source_identities_are_not_collapsed(self):
        other = self.root/'packages/other'
        other.mkdir()
        (other/'build.gradle.kts').write_text('// fixture')
        (other/'README.md').write_text((self.package/'README.md').read_text())
        (other/'trigger-tests.json').write_text(json.dumps(dict(self.data, project='other')))
        inventory = 'packages/example/trigger-tests.json\0packages/other/trigger-tests.json\0'
        with patch.object(package_tests.subprocess, 'check_output', return_value=inventory.encode()):
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                package_tests.discover(self.root)

    def test_new_trigger_without_matrix_registration_fails_discovery(self):
        missing = self.root/'packages/missing'
        missing.mkdir()
        (missing/'build.gradle.kts').write_text('// fixture')
        source = missing/'src/main/java/acme/Source.java'
        source.parent.mkdir(parents=True)
        source.write_text('@ClientTrigger(name="new-event") public class Source {}')
        inventory = 'packages/example/trigger-tests.json\0packages/missing/src/main/java/acme/Source.java\0'
        with patch.object(package_tests.subprocess, 'check_output', return_value=inventory.encode()):
            with self.assertRaisesRegex(ValueError, 'absent from mock-test matrix'):
                package_tests.discover(self.root)

    def test_current_packages_are_registered_and_pipeline_runs_each_entry_independently(self):
        entries = package_tests.discover()
        self.assertGreaterEqual(len(entries), 3)
        workflow = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text())
        jobs = workflow['jobs']
        matrix = jobs['trigger-package-tests']
        self.assertFalse(matrix['strategy']['fail-fast'])
        self.assertIn('fromJSON(needs.trigger-package-sdk.outputs.matrix)', matrix['strategy']['matrix'])
        self.assertIn('trigger-package-tests', jobs['check']['needs'])
        self.assertEqual(matrix['needs'], 'trigger-package-sdk')
        test_step = next(step for step in matrix['steps'] if 'package_tests.py' in step.get('run', ''))
        self.assertEqual(test_step['env']['TOOLGATE_TEST_PROVIDER_MODE'], 'mock')
        self.assertNotIn('secrets', matrix)
