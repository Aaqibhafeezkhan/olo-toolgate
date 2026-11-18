# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Narrow Maven license declaration and scope regression tests."""
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

from license_expression import ExpressionError

from tools import dependency_licenses as licenses


class MavenLicenseTests(unittest.TestCase):
    def test_rate_limited_metadata_is_retried_without_waiving_license_evidence(self):
        failure=urllib.error.HTTPError('https://repo.maven.apache.org/metadata',429,'limited',{'Retry-After':'2'},io.BytesIO(b'private provider body'))
        with patch.object(licenses.urllib.request,'urlopen',side_effect=[failure,io.BytesIO(b'<project/>')]) as fetch,patch.object(licenses.time,'sleep') as sleep:
            self.assertEqual(b'<project/>',licenses.metadata_bytes('https://repo.maven.apache.org/metadata'))
            self.assertEqual(2,fetch.call_count);sleep.assert_called_once_with(2)

    def test_persistent_rate_limit_and_missing_metadata_still_fail(self):
        for code,count in ((429,5),(404,1)):
            failures=[urllib.error.HTTPError('https://repo.maven.apache.org/metadata',code,'refused',{},io.BytesIO()) for _ in range(count)]
            with self.subTest(code=code),patch.object(licenses.urllib.request,'urlopen',side_effect=failures) as fetch,patch.object(licenses.time,'sleep'),self.assertRaises(urllib.error.HTTPError):
                licenses.metadata_bytes('https://repo.maven.apache.org/metadata')
            self.assertEqual(count,fetch.call_count)

    def test_metadata_beyond_size_budget_or_server_retry_window_is_not_accepted(self):
        with patch.object(licenses.urllib.request,'urlopen',return_value=io.BytesIO(b'x'*(2*1024*1024+1))),self.assertRaises(ValueError):
            licenses.metadata_bytes('https://repo.maven.apache.org/metadata')
        failure=urllib.error.HTTPError('https://repo.maven.apache.org/metadata',429,'limited',{'Retry-After':'300'},io.BytesIO())
        with patch.object(licenses.urllib.request,'urlopen',side_effect=failure),patch.object(licenses.time,'sleep') as sleep,self.assertRaises(urllib.error.HTTPError):
            licenses.metadata_bytes('https://repo.maven.apache.org/metadata')
        sleep.assert_not_called()

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

    def test_re2j_go_license_maps_to_bsd_3_clause_only_with_upstream_url(self):
        coordinate = ('com.google.re2j', 're2j', '1.8')
        self.assertEqual(self.declared_license('Go License', *coordinate, url='https://golang.org/LICENSE'), 'BSD-3-Clause')
        with self.assertRaisesRegex(ValueError, 'Go license URL requires review'):
            self.declared_license('Go License', *coordinate, url='https://example.com/LICENSE')
        with self.assertRaisesRegex(ValueError, 'Maven license requires review'):
            self.declared_license('Go License', 'other', 're2j', '1.8', url='https://golang.org/LICENSE')

    def test_explicit_bsd_3_clause_name_maps_but_bare_bsd_does_not(self):
        self.assertEqual(self.declared_license('BSD 3-clause', group='org.threeten'), 'BSD-3-Clause')
        with self.assertRaisesRegex(ValueError, 'Maven license requires review'):
            self.declared_license('BSD', group='other')

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
