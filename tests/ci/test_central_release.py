# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Central bundle contents, signing and publication outcomes without registry writes."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import yaml
from tools.release import central

ROOT = Path(__file__).resolve().parents[2]
VERSION = '1.2.3-dev.42'
DEPLOYMENT = '28570f16-da32-4c14-bd2e-c1acc0782365'


class CentralReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.assets = self.root/'assets'
        self.assets.mkdir()
        self.prefix = 'toolgate-contracts-'+VERSION
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar'):
            (self.assets/(self.prefix+suffix)).write_bytes(('checked '+suffix).encode())
        (self.assets/(self.prefix+'.pom')).write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<groupId>io.ololabs.toolgate</groupId><artifactId>toolgate-contracts</artifactId>'
            f'<version>{VERSION}</version><name>Contracts</name><description>Contracts</description>'
            '<url>https://github.com/olo-labs/olo-toolgate</url><licenses><license>'
            '<name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url>'
            '</license></licenses><developers><developer><name>OLO Labs</name></developer></developers>'
            '<scm><connection>scm:git:https://github.com/olo-labs/olo-toolgate.git</connection>'
            '<url>https://github.com/olo-labs/olo-toolgate</url></scm></project>', encoding='utf-8')
        env = patch.dict(os.environ, {'MAVEN_REPOSITORY_URL':central.UPLOAD_URL,
            'MAVEN_USERNAME':'fixture-user', 'MAVEN_PASSWORD':'fixture-password',
            'MAVEN_GPG_PRIVATE_KEY':'fixture-private-key'}, clear=True)
        env.start()
        self.addCleanup(env.stop)

    def fake_gpg(self, home, *args, input=None):
        if '--detach-sign' in args:
            Path(args[-1]+'.asc').write_bytes(b'fixture signature')

    def test_bundle_preserves_bytes_and_has_maven_layout_signatures_and_checksums(self):
        module = self.assets/(self.prefix+'.module')
        module.write_text(json.dumps({'component':{'group':'io.ololabs.toolgate',
            'module':'toolgate-contracts', 'version':VERSION}}), encoding='utf-8')
        folder = self.root/'bundle'
        folder.mkdir()
        with patch.object(central, 'gpg', side_effect=self.fake_gpg), patch.object(central.subprocess, 'run'):
            archive = central.bundle(self.assets, VERSION, folder)
        with zipfile.ZipFile(archive) as output:
            self.assertEqual(len(output.namelist()), 30)
            for source in self.assets.iterdir():
                name = 'io/ololabs/toolgate/toolgate-contracts/'+VERSION+'/'+source.name
                self.assertEqual(output.read(name), source.read_bytes())
                self.assertEqual(output.read(name+'.asc'), b'fixture signature')
                for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
                    self.assertEqual(output.read(name+'.'+algorithm).decode(),
                        hashlib.new(algorithm, source.read_bytes()).hexdigest())
            self.assertFalse(any('gnupg' in name for name in output.namelist()))

    def test_invalid_version_coordinate_missing_artifact_or_metadata_never_signs(self):
        with patch.object(central, 'gpg') as sign:
            for version in ('../escape', '1.2.3-SNAPSHOT'):
                with self.assertRaises(ValueError):
                    central.bundle(self.assets, version, self.root)
            pom = self.assets/(self.prefix+'.pom')
            original = pom.read_text()
            pom.write_text(original.replace('io.ololabs.toolgate', 'wrong.group'))
            with self.assertRaisesRegex(ValueError, 'identity mismatch'):
                central.bundle(self.assets, VERSION, self.root)
            pom.write_text(original.replace('<name>OLO Labs</name>', ''))
            with self.assertRaisesRegex(ValueError, 'developers'):
                central.bundle(self.assets, VERSION, self.root)
            pom.write_text(original)
            (self.assets/(self.prefix+'-sources.jar')).unlink()
            with self.assertRaisesRegex(ValueError, 'Missing Maven artifact'):
                central.bundle(self.assets, VERSION, self.root)
        sign.assert_not_called()

    def publish_with(self, states):
        archive = self.root/'archive.zip'
        archive.write_bytes(b'bundle bytes')
        responses = [DEPLOYMENT]+[json.dumps(state) for state in states]
        with patch.object(central, 'bundle', return_value=archive), \
             patch.object(central, 'request', side_effect=responses) as request, \
             patch.object(central.time, 'sleep'):
            central.publish(self.assets, VERSION)
        return request

    def test_automatic_multipart_upload_waits_until_published(self):
        request = self.publish_with([{'deploymentState':s} for s in ('PENDING','VALIDATING','PUBLISHING','PUBLISHED')])
        upload = request.call_args_list[0].args
        self.assertEqual(upload[0], 'upload?publishingType=AUTOMATIC')
        self.assertEqual(upload[1], 'Zml4dHVyZS11c2VyOmZpeHR1cmUtcGFzc3dvcmQ=')
        self.assertIn(b'name="bundle"', upload[2])
        self.assertIn(b'bundle bytes', upload[2])
        self.assertEqual(request.call_count, 5)

    def test_validation_failure_is_not_success(self):
        with self.assertRaisesRegex(ValueError, 'namespace'):
            self.publish_with([{'deploymentState':'FAILED', 'errors':{'namespace':'not verified'}}])

    def test_missing_credentials_prevent_signing_or_upload(self):
        with patch.dict(os.environ, {'MAVEN_PASSWORD':''}), patch.object(central, 'bundle') as bundle:
            with self.assertRaisesRegex(ValueError, 'Portal user token'):
                central.publish(self.assets, VERSION)
        bundle.assert_not_called()

    def test_central_secrets_and_routing_are_in_both_maven_jobs(self):
        for workflow in ('development-release', 'manual-development-release'):
            jobs = yaml.safe_load((ROOT/'.github/workflows'/f'{workflow}.yml').read_text())['jobs']
            step = jobs['publish-maven']['steps'][-1]
            self.assertEqual(step['env']['MAVEN_GPG_PRIVATE_KEY'], '${{ secrets.MAVEN_GPG_PRIVATE_KEY }}')
            self.assertIn('tools/release/central.py', step['run'])
            self.assertIn(central.UPLOAD_URL, step['run'])
            for name, job in jobs.items():
                if name != 'publish-maven':
                    self.assertNotIn('MAVEN_GPG_PRIVATE_KEY', json.dumps(job))


if __name__ == '__main__':
    unittest.main()
