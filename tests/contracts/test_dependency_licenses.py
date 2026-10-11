# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Narrow Maven license declaration and scope regression tests."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from license_expression import ExpressionError

from tools import dependency_licenses as licenses


class MavenLicenseTests(unittest.TestCase):
    def declared_license(self, name, group='aopalliance', artifact='aopalliance', version='1.0', url=''):
        pom = f'<project><licenses><license><name>{name}</name><url>{url}</url></license></licenses></project>'
        with patch.object(licenses.urllib.request, 'urlopen', return_value=io.BytesIO(pom.encode())):
            return licenses.maven_license(group, artifact, version, {})

    def test_aopalliance_preserves_its_public_domain_declaration(self):
        expression = self.declared_license('Public Domain')
        self.assertEqual(expression, 'LicenseRef-AOP-Alliance-Public-Domain')
        reviews = json.loads((licenses.ROOT/'tools/dependency-license-reviews.json').read_text())['reviews']
        review = reviews['aopalliance:aopalliance:1.0:build-test']
        self.assertEqual(review['expression'], expression)
        self.assertEqual(review['scope'], 'build-test')
        self.assertIn('https://aopalliance.sourceforge.net/', review['licenseUrls'])
        self.assertNotIn('aopalliance:aopalliance:1.0:runtime', reviews)
        self.assertNotIn('aopalliance:aopalliance:1.0', reviews)
        for tooling in (False, True):
            with self.subTest(tooling=tooling), self.assertRaises(ExpressionError):
                licenses.accepted(expression, tooling=tooling)

    def test_unreviewed_public_domain_coordinates_remain_rejected(self):
        for coordinate in [('aopalliance', 'aopalliance', '2.0'), ('other', 'aopalliance', '1.0')]:
            with self.subTest(coordinate=coordinate), self.assertRaisesRegex(ValueError, 'requires explicit CC0 evidence'):
                self.declared_license('Public Domain', *coordinate)

    def test_known_coordinate_does_not_override_changed_license_metadata(self):
        self.assertEqual(self.declared_license('GPL2 w/ CPE'), 'GPL-2.0-only WITH Classpath-exception-2.0')
        with self.assertRaisesRegex(ValueError, 'Maven license requires review'):
            self.declared_license('Unknown private license')

    def test_explicit_cc0_metadata_still_uses_cc0(self):
        self.assertEqual(self.declared_license('Public domain', group='other',
                                            url='https://creativecommons.org/publicdomain/zero/1.0/'), 'CC0-1.0')

    def test_audit_requires_the_exact_build_test_review(self):
        reviews = json.loads((licenses.ROOT/'tools/dependency-license-reviews.json').read_text())
        pom = b'<project><licenses><license><name>Public Domain</name></license></licenses></project>'
        for configuration, reviewed in [('testRuntimeClasspath', True), ('runtimeClasspath', True), ('testRuntimeClasspath', False)]:
            with self.subTest(configuration=configuration, reviewed=reviewed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root/'tools').mkdir()
                (root/'apps/quickstart').mkdir(parents=True)
                (root/'tools/requirements.txt').write_text('')
                (root/'apps/quickstart/requirements.txt').write_text('')
                (root/'package-lock.json').write_text('{"packages":{}}')
                (root/'gradle.lockfile').write_text('aopalliance:aopalliance:1.0='+configuration+'\n')
                (root/'tools/dependency-license-reviews.json').write_text(json.dumps(reviews if reviewed else {'reviews': {}}))
                with patch.object(licenses, 'ROOT', root), patch.object(licenses.urllib.request, 'urlopen', return_value=io.BytesIO(pom)):
                    if reviewed and configuration == 'testRuntimeClasspath':
                        licenses.audit({'packages': []})
                        inventory = json.loads((root/'build/release/dependency-licenses.json').read_text())['dependencies']
                        self.assertEqual(inventory[0]['license'], 'LicenseRef-AOP-Alliance-Public-Domain')
                        self.assertEqual(inventory[0]['scope'], 'build-test')
                        self.assertIn('review', inventory[0])
                    else:
                        with self.assertRaises(ExpressionError):
                            licenses.audit({'packages': []})
