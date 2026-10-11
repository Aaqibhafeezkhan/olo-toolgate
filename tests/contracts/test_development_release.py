# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Development publishing must reject unverified artifacts and failed CI."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from tools.release import development
from tools.release import manual_development


class DevelopmentReleaseTests(unittest.TestCase):
    def test_device_is_published_by_both_existing_release_paths(self):
        self.assertEqual(development.IMAGES['device'],('olo-toolgate-device:module11','olo-toolgate-device'))
        self.assertEqual(manual_development.IMAGES['device'],'olo-toolgate-device')

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
        with patch.object(development,'coordinates',return_value=('olo-labs/olo-toolgate',sha,'olo')), \
             patch.object(development,'run') as run, \
             patch.object(development,'api',return_value={'workflow_runs':[
                 {'head_sha':sha,'id':1,'status':'completed','conclusion':'failure'}]}):
            run.return_value.stdout=sha+'\n'
            with self.assertRaisesRegex(ValueError,'CI gate failed'):development.prepare()
            run.assert_called_once_with('git','rev-parse','HEAD',capture=True)
