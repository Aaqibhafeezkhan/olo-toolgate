# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Verify release documents are reproducible and travel inside deliverables."""
import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from tools.client import package
from tools.contracts import version
from tools.release import bundle


class ReleaseDocumentsTests(unittest.TestCase):
    def test_notes_match_versions_and_changelog(self):
        self.assertEqual(version.expected()[version.ROOT/'RELEASE-NOTES.md'].encode('utf-8'),
                         (version.ROOT/'RELEASE-NOTES.md').read_bytes())
        for language in ('java', 'rust', 'typescript', 'php'):
            self.assertEqual((version.ROOT/'RELEASE-NOTES.md').read_bytes(),
                (version.ROOT/'packages/contracts'/language/'RELEASE-NOTES.md').read_bytes())

    def test_raw_bundle_embeds_exact_documents(self):
        raw=bundle.bundle_bytes()
        self.assertEqual(raw,bundle.bundle_bytes())
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            for name in ('LICENSE','NOTICE.md','RELEASE-NOTES.md'):
                self.assertEqual(archive.extractfile(name).read(),(bundle.ROOT/name).read_bytes())

    def test_native_archives_embed_documents_for_all_platforms(self):
        metadata={'packages':[{'id':'client','name':'olo-toolgate-client','version':'0.10.0-dev',
                    'license':'Apache-2.0','source':None}],
                  'resolve':{'nodes':[{'id':'client','dependencies':[]}]}}
        with tempfile.TemporaryDirectory() as directory, patch.object(package.subprocess,'check_output',return_value=json.dumps(metadata).encode()):
            output=Path(directory)
            for target in sorted(package.TARGETS):
                with self.subTest(target=target):
                    arm=target.startswith('aarch64')
                    raw=bytearray(128)
                    if 'windows' in target:
                        raw[:2]=b'MZ';raw[60:64]=(64).to_bytes(4,'little')
                        raw[64:68]=b'PE\0\0';raw[68:70]=(0xaa64 if arm else 0x8664).to_bytes(2,'little')
                    elif 'linux' in target:
                        raw[:6]=b'\x7fELF\x02\x01';raw[18:20]=(183 if arm else 62).to_bytes(2,'little')
                    else:
                        raw[:4]=b'\xcf\xfa\xed\xfe';raw[4:8]=(0x100000c if arm else 0x1000007).to_bytes(4,'little')
                    binary=output/'binary';binary.write_bytes(raw)
                    if 'windows' in target:(output/'olo-toolgate-browser-host.exe').write_bytes(raw)
                    package.package(binary,target,output)
                    version=(package.ROOT/'VERSION').read_text().strip()
                    path=output/f'olo-toolgate-client-{version}-{target}.{"zip" if "windows" in target else "tar.gz"}'
                    archive=zipfile.ZipFile(path) if path.suffix=='.zip' else tarfile.open(path)
                    with archive:
                        for name in ('LICENSE','NOTICE.md','RELEASE-NOTES.md',
                                     'docs/client/local-runtimes.md','docs/planning/README.md'):
                            data=archive.read(name) if path.suffix=='.zip' else archive.extractfile(name).read()
                            self.assertEqual(data,(package.ROOT/name).read_bytes())
