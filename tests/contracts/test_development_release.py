# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Development publishing must reject unverified artifacts and failed CI."""
import tempfile
import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch
from tools.release import development
from tools.release import manual_development


class DevelopmentReleaseTests(unittest.TestCase):
    def test_development_java_assets_reuse_checked_bytes_without_a_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);output=root/'build/development'
            source=output/'foundation/build/release';source.mkdir(parents=True)
            assets=output/'assets';assets.mkdir()
            contracts=root/'packages/contracts';contracts.mkdir(parents=True)
            (contracts/'VERSION').write_text('1.0.0\n',encoding='utf-8')
            (root/'VERSION').write_text('0.10.0-dev\n',encoding='utf-8')
            names=['toolgate-contracts-1.0.0'+suffix for suffix in ('.jar','-sources.jar','-javadoc.jar','.pom','.module')]
            for name in names:(source/name).write_bytes(('checked '+name).encode())
            (source/'SHA256SUMS').write_text(''.join(development.digest(source/name)+'  '+name+'\n' for name in names),encoding='utf-8')
            with patch.object(development,'ROOT',root),patch.object(development,'OUTPUT',output),patch.object(development,'run') as run:
                self.assertEqual(development.prepare_development_java_assets(),'1.0.0')
                run.assert_not_called()
                for name in names:self.assertEqual((assets/name).read_bytes(),(source/name).read_bytes())
                self.assertEqual((root/'VERSION').read_text(),'0.10.0-dev\n')
                self.assertEqual((contracts/'VERSION').read_text(),'1.0.0\n')
                (source/names[0]).write_bytes(b'tampered jar')
                with self.assertRaisesRegex(ValueError,'checksum'):
                    development.prepare_development_java_assets()
                run.assert_not_called()

    def test_manual_build_prepares_the_pom_for_another_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            output=root/'build/manual-development'
            assets=output/'assets'
            clients=root/'clients';clients.mkdir()
            contracts=root/'packages/contracts'
            typescript=contracts/'typescript';typescript.mkdir(parents=True)
            (typescript/'package.json').write_text('{"version":"0.0.0"}',encoding='utf-8')
            (contracts/'php').mkdir()
            jars=contracts/'java/build/libs';jars.mkdir(parents=True)
            (jars/'toolgate-contracts-fixture.jar').write_bytes(b'prepared jar')
            publication=contracts/'java/build/publications/mavenJava';publication.mkdir(parents=True)
            (publication/'pom-default.xml').write_bytes(b'prepared pom')
            (root/'target/package').mkdir(parents=True)
            (root/'RELEASE-NOTES.md').write_text('source notes',encoding='utf-8')
            from tools.release.notes import generate
            generate(root, root/'release-notes')
            chart=root/'deploy/helm/olo-toolgate';chart.mkdir(parents=True)
            (chart/'values.yaml').write_text('global:\n  imageRegistry: fixture\n',encoding='utf-8')
            info={'commit':'a'*40,'binaryVersion':'1.0.0','libraryVersion':'1.0.0-dev.fixture','dockerTag':'dev-fixture'}
            with patch.object(manual_development,'ROOT',root),patch.object(manual_development,'OUTPUT',output), \
                 patch.object(manual_development,'ASSETS',assets),patch.object(manual_development,'CLIENTS',clients), \
                 patch.object(manual_development,'metadata',return_value=info),patch.object(manual_development,'run'), \
                 patch.object(manual_development,'startup_gate'), \
                 patch.dict(os.environ,{'GITHUB_RUN_NUMBER':'1','DOCKERHUB_NAMESPACE':'fixture'}):
                manual_development.build()
            self.assertEqual((assets/'toolgate-contracts-1.0.0-dev.fixture.pom').read_bytes(),b'prepared pom')
            self.assertEqual((assets/'RELEASE-NOTES.md').read_text(), 'source notes\n')
            self.assertEqual(json.loads((assets/'contributors.json').read_text())['fragments'], [])

    def test_manual_publication_uses_prepared_assets_on_a_fresh_runner(self):
        sha='a'*40
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            output=root/'build/manual-development'
            assets=output/'assets';assets.mkdir(parents=True)
            (assets/'RELEASE-NOTES.md').write_text('release notes',encoding='utf-8')
            info={'commit':sha,'dockerTag':'dev-fixture','libraryVersion':'1.0.0-dev.fixture','releaseTag':'dev-fixture','sourceBranch':'main','branchKey':'main-fixture'}
            (assets/'development-release.json').write_text(json.dumps(info),encoding='utf-8')
            (assets/'compatibility.json').write_text('{}',encoding='utf-8')
            pom=assets/'toolgate-contracts-1.0.0-dev.fixture.pom';pom.write_bytes(b'prepared pom')
            def command(*args,**kwargs):
                stdout=''
                if args[:2]==('gh','api'):stdout=json.dumps({'object':{'sha':sha}})
                if args[:2]==('docker','inspect'):stdout=args[2].split(':')[0]+'@sha256:'+'b'*64
                return subprocess.CompletedProcess(args,0,stdout=stdout)
            with patch.object(manual_development,'ROOT',root),patch.object(manual_development,'OUTPUT',output), \
                 patch.object(manual_development,'ASSETS',assets),patch.object(manual_development,'run',side_effect=command) as run, \
                 patch.object(manual_development.github_release,'publish') as publish_release, \
                 patch.object(manual_development.development_source,'verify'), \
                 patch.dict(os.environ,{'GITHUB_REPOSITORY':'olo-labs/olo-toolgate','DOCKERHUB_NAMESPACE':'fixture',
                                        'DOCKERHUB_TOKEN':'fixture','DOCKERHUB_USERNAME':'fixture'}):
                manual_development.publish()
            self.assertFalse((root/'packages/contracts/java/build').exists())
            self.assertEqual(pom.read_bytes(),b'prepared pom')
            self.assertIn(pom.name,(assets/'SHA256SUMS').read_text())
            self.assertEqual(publish_release.call_args.args[4],assets)
            self.assertEqual(publish_release.call_args.args[2],info['releaseTag'])

    def test_device_is_published_by_both_existing_release_paths(self):
        self.assertEqual(development.IMAGES['device'],('olo-toolgate-device:module11','olo-toolgate-device'))
        self.assertEqual(manual_development.IMAGES['device'],'olo-toolgate-device')
        self.assertEqual(development.IMAGES['secured_device'],
                         ('olo-toolgate-linux-secured-device:module11','olo-toolgate-linux-secured-device'))
        self.assertEqual(manual_development.IMAGES['secured_device'],'olo-toolgate-linux-secured-device')

    def test_archive_requires_matching_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            archive=folder/'image.tar'
            archive.write_bytes(b'tested image')
            (folder/'SHA256SUMS').write_text(development.digest(archive)+'  image.tar\n')
            development.verify_checksums(folder)
            archive.write_bytes(b'changed image')
            with self.assertRaises(ValueError):development.verify_checksums(folder)

    def test_missing_archive_checksum_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'SHA256SUMS').write_text('')
            with self.assertRaises(ValueError):development.verify_checksums(folder)

    def test_checksum_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            (folder/'SHA256SUMS').write_text('0'*64+'  ../image.tar\n')
            with self.assertRaises(ValueError):development.verify_checksums(folder)

    def test_failed_gate_prevents_artifact_download(self):
        sha='a'*40
        with patch.dict(os.environ,{'GITHUB_REF':'refs/heads/main'}), \
             patch.object(development,'coordinates',return_value=('olo-labs/olo-toolgate',sha,'olo')), \
             patch.object(development,'run') as run, \
             patch.object(development,'api',return_value={'workflow_runs':[
                 {'head_sha':sha,'id':1,'status':'completed','conclusion':'failure'}]}):
            run.return_value.stdout=sha+'\n'
            with self.assertRaisesRegex(ValueError,'CI gate failed'):development.prepare()
            run.assert_called_once_with('git','rev-parse','HEAD',capture=True)
