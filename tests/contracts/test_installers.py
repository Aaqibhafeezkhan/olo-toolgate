# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Installer packaging integrity and bounded native payload handling."""
import hashlib
import io
import tarfile
import tempfile
import unittest
import subprocess
from unittest.mock import patch
import json
from pathlib import Path
from tools.client.extension import ROOT as PRODUCT_ROOT
from tools.client.installer import linux_bytes, payload, embed_chrome, create_dmg


class InstallerTests(unittest.TestCase):
    @patch('tools.client.installer.time.sleep')
    @patch('tools.client.installer.subprocess.run')
    def test_dmg_creation_retries_only_busy_service_with_identical_inputs(self,run,sleep):
        busy=subprocess.CompletedProcess([],1,'','hdiutil: create failed - Resource busy')
        run.side_effect=[busy,subprocess.CompletedProcess([],0,'','')]
        create_dmg(Path('/fixture/source'),Path('/fixture/output.dmg'))
        self.assertEqual(2,run.call_count)
        self.assertEqual(run.call_args_list[0],run.call_args_list[1])
        sleep.assert_called_once_with(1)

    @patch('tools.client.installer.time.sleep')
    @patch('tools.client.installer.subprocess.run')
    def test_dmg_creation_preserves_nonbusy_failure_without_retry(self,run,sleep):
        run.return_value=subprocess.CompletedProcess([],1,'','hdiutil: invalid source')
        with self.assertRaises(subprocess.CalledProcessError) as failed:
            create_dmg(Path('/fixture/source'),Path('/fixture/output.dmg'))
        self.assertIn('invalid source',failed.exception.stderr)
        run.assert_called_once();sleep.assert_not_called()

    @patch('tools.client.installer.time.sleep')
    @patch('tools.client.installer.subprocess.run')
    def test_dmg_creation_busy_retry_is_bounded_and_remains_a_failure(self,run,sleep):
        run.return_value=subprocess.CompletedProcess([],1,'','hdiutil: create failed - Resource busy')
        with self.assertRaises(subprocess.CalledProcessError):
            create_dmg(Path('/fixture/source'),Path('/fixture/output.dmg'))
        self.assertEqual(3,run.call_count)
        self.assertEqual([1,2],[call.args[0] for call in sleep.call_args_list])

    def test_windows_payload_contains_unpacked_chrome_extension(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)/'payload'
            embed_chrome(directory,42)
            manifest=json.loads((directory/'chrome-extension/manifest.json').read_text())
            identity = PRODUCT_ROOT/'release.json'
            build = json.loads(identity.read_text())['build'] if identity.is_file() else 42
            self.assertTrue(manifest['version'].endswith('.'+str(build)))
            self.assertEqual(manifest['version_name'], (PRODUCT_ROOT/'VERSION').read_text().strip())
            self.assertTrue((directory/'chrome-extension'/manifest['background']['service_worker']).is_file())
            self.assertTrue((directory/'chrome-extension/LICENSE').is_file())
            self.assertFalse(list(directory.rglob('*.zip')))

    def test_linux_payload_offset_hash_and_reproducibility(self):
        files={'olo-toolgate-client':b'fixture','LICENSE':b'license'}
        result=linux_bytes(files)
        self.assertEqual(result,linux_bytes(files))
        offset=result.index(b'\x1f\x8b')
        header=result[:offset].decode()
        self.assertIn('tail -c +'+str(offset+1),header)
        self.assertIn(hashlib.sha256(result[offset:]).hexdigest(),header)
        self.assertNotIn('@OFFSET@',header)
        with tarfile.open(fileobj=io.BytesIO(result[offset:])) as archive:
            self.assertEqual(archive.extractfile('olo-toolgate-client').read(),b'fixture')

    def test_payload_rejects_traversal_even_with_matching_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'payload.tar.gz'
            with tarfile.open(path,'w:gz') as archive:
                entry=tarfile.TarInfo('../escape');entry.size=1
                archive.addfile(entry,io.BytesIO(b'x'))
            path.with_name(path.name+'.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name)
            with self.assertRaises(ValueError):payload(path)

    def test_payload_requires_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'payload.tar.gz';path.write_bytes(b'changed')
            path.with_name(path.name+'.sha256').write_text('0'*64+'  '+path.name)
            with self.assertRaises(ValueError):payload(path)
