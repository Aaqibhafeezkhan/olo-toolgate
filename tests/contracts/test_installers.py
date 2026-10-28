# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Installer packaging integrity and bounded native payload handling."""
import hashlib
import io
import tarfile
import tempfile
import unittest
import json
from pathlib import Path
from tools.client.extension import ROOT as PRODUCT_ROOT
from tools.client.installer import linux_bytes, payload, embed_chrome


class InstallerTests(unittest.TestCase):
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
