# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Prepared artifacts preserve identity and bytes across Maven repository backends."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from tools.release import maven, central


class MavenPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        self.assets=self.root/'assets'
        self.assets.mkdir()
        self.version='1.0.0-dev.42'
        self.prefix='toolgate-contracts-'+self.version
        for suffix in ('.jar','-sources.jar','-javadoc.jar'):
            (self.assets/(self.prefix+suffix)).write_bytes(('checked '+suffix).encode())
        self.pom=self.assets/(self.prefix+'.pom')
        self.pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<groupId>io.github.olo-labs</groupId><artifactId>toolgate-contracts</artifactId>'
            '<version>'+self.version+'</version></project>',encoding='utf-8')
        env=patch.dict(os.environ,{'MAVEN_REPOSITORY_URL':'https://maven.example.invalid'},clear=True)
        env.start()
        self.addCleanup(env.stop)

    def test_standard_repository_publishes_exact_prepared_bytes_without_building_sources(self):
        run=Mock()
        maven.publish(self.assets,self.version,run=run)
        self.assertEqual(run.call_args.args[-1],'publishVerifiedPublicationToRemoteRepository')
        project=self.root/'maven-publication'
        script=(project/'build.gradle.kts').read_text()
        self.assertIn('io.github.olo-labs',script)
        self.assertNotIn('java-library',script)
        for path in self.assets.iterdir():
            self.assertEqual(path.read_bytes(),(project/'assets'/path.name).read_bytes())

    def test_central_uses_same_group_and_prepared_pom(self):
        with patch.dict(os.environ,{'MAVEN_REPOSITORY_URL':central.UPLOAD_URL}),patch.object(central,'publish') as publish:
            maven.publish(self.assets,self.version)
        publish.assert_called_once_with(self.assets,self.version,self.pom)

    def test_wrong_namespace_or_module_identity_never_invokes_remote_commands(self):
        run=Mock()
        original=self.pom.read_text()
        self.pom.write_text(original.replace('io.github.olo-labs','io.ololabs.toolgate'))
        with self.assertRaisesRegex(ValueError,'identity mismatch'):
            maven.publish(self.assets,self.version,run=run)
        self.pom.write_text(original)
        module=self.assets/(self.prefix+'.module')
        module.write_text(json.dumps({'component':{'group':'wrong','module':'toolgate-contracts','version':self.version}}))
        with self.assertRaisesRegex(ValueError,'identity mismatch'):
            maven.publish(self.assets,self.version,run=run)
        run.assert_not_called()

    def test_unsafe_version_never_creates_a_publication_project(self):
        run=Mock()
        with self.assertRaisesRegex(ValueError,'Unsafe'):
            maven.publish(self.assets,'../escape',run=run)
        run.assert_not_called()
        self.assertFalse((self.root/'maven-publication').exists())
