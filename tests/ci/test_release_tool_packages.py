# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Standalone release packages must retain exact bytes and complete project coverage."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from bot.bundle import digest,pack,package
from tools.release import tool_packages as release

ROOT=Path(__file__).resolve().parents[2]


class ToolReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name);self.output=self.root/'assets';self.output.mkdir()
        self.raw=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olotool').read_bytes()
        self.metadata,_=package(self.raw)
        self.version='1.2.3';self.commit='a'*40
        self.project=self.root/'examples/java-tools/demo'
        (self.project/'build/toolgate').mkdir(parents=True)
        (self.project/'build.gradle.kts').write_text('// fixture',encoding='utf-8')
        (self.project/'build/toolgate/demo.olotool').write_bytes(self.raw)
        (self.root/'core-mcp').mkdir()
        self.core=dict(project='demo',sdk='python',id=self.metadata['packageId'],version=self.metadata['version'])
        (self.root/'core-mcp/core-packages.build.json').write_text(json.dumps(dict(packages=[self.core])),encoding='utf-8')
        folder=self.root/'build/core-mcp';folder.mkdir(parents=True)
        (folder/'core.olotool').write_bytes(self.raw)
        (folder/'core-packages.lock.json').write_text(json.dumps(dict(sdkVersion=self.version,packages=[dict(self.core,artifact='core.olotool',artifactSha256=digest(self.raw)[7:],packageDigest=self.metadata['packageDigest'])])),encoding='utf-8')
        self.index=self.output/'olo-tools-1.2.3.json'

    def prepare(self):release.prepare(self.root,self.output,self.version,self.commit)
    def validate(self):return release.validate(self.output,self.version,self.commit,self.root)

    def test_all_projects_are_published_and_identical_archives_are_shared(self):
        self.prepare()
        names=self.validate()
        self.assertEqual({release.asset_name(self.metadata)},names)
        self.assertEqual(self.raw,(self.output/next(iter(names))).read_bytes())
        row=json.loads(self.index.read_text(encoding='utf-8'))['packages'][0]
        self.assertEqual(['examples/java-tools/demo','core-mcp/demo'],row['projects'])
        self.assertEqual(self.metadata['version'],row['version'])
        self.prepare()  # Recollection preserves tested bytes.
        self.validate()

    def test_missing_or_ambiguous_built_packages_stop_collection(self):
        path=self.project/'build/toolgate/demo.olotool'
        path.unlink()
        with self.assertRaisesRegex(ValueError,'Exactly one'):self.prepare()
        path.write_bytes(self.raw)
        (path.parent/'duplicate.olotool').write_bytes(self.raw)
        with self.assertRaisesRegex(ValueError,'Exactly one'):self.prepare()

    def test_incomplete_core_inventory_stops_collection(self):
        path=self.root/'build/core-mcp/core-packages.lock.json'
        data=json.loads(path.read_text(encoding='utf-8'));data['packages']=[]
        path.write_text(json.dumps(data),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'core tool inventory'):self.prepare()

    def test_tampered_missing_extra_and_relabelled_assets_stop_promotion(self):
        self.prepare();name=release.asset_name(self.metadata);path=self.output/name
        path.write_bytes(self.raw+b'changed')
        with self.assertRaises(ValueError):self.validate()
        path.unlink()
        with self.assertRaises(FileNotFoundError):self.validate()
        path.write_bytes(self.raw)
        extra=self.output/'unexpected.olotool';extra.write_bytes(self.raw)
        with self.assertRaisesRegex(ValueError,'Unlisted'):self.validate()
        extra.unlink()
        index=json.loads(self.index.read_text(encoding='utf-8'))
        index['packages'][0]['projects'].pop()
        self.index.write_text(json.dumps(index),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'every first-party'):self.validate()

    def test_source_and_version_must_match_publication(self):
        self.prepare()
        with self.assertRaisesRegex(ValueError,'source/version'):release.validate(self.output,self.version,'b'*40,self.root)
        index=json.loads(self.index.read_text(encoding='utf-8'));index['version']='9.9.9'
        self.index.write_text(json.dumps(index),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'source/version'):self.validate()

    def test_inventory_cannot_turn_a_hash_into_a_filesystem_path(self):
        self.prepare()
        index=json.loads(self.index.read_text(encoding='utf-8'))
        index['packages'][0]['sha256']='sha256:../../private'
        self.index.write_text(json.dumps(index),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'archive hash'):self.validate()

    def test_exact_embedded_bot_archives_are_published_even_when_zip_bytes_differ(self):
        raw=self.raw+b'archive-trailer'
        metadata,_=package(raw)
        pin=dict(packageId=metadata['packageId'],version=metadata['version'])
        data,_=pack(dict(profileVersion=2,profileId='demo',packages=[pin],workflows={'workflows':[{'id':'demo'}]}),{},[],[raw],dict(version=self.version,sourceCommit=self.commit))
        (self.output/'demo.olo').write_bytes(data)
        self.prepare()
        self.assertEqual(2,len(self.validate()))
        self.assertEqual(raw,(self.output/release.asset_name(metadata)).read_bytes())
        index=json.loads(self.index.read_text(encoding='utf-8'))
        index['packages']=[row for row in index['packages'] if row['projects']]
        self.index.write_text(json.dumps(index),encoding='utf-8')
        (self.output/release.asset_name(metadata)).unlink()
        with self.assertRaisesRegex(ValueError,'Embedded bot tool missing'):self.validate()

    def test_manual_publication_refuses_missing_tools_before_registry_writes(self):
        from tools.release import manual_development as manual
        (self.output/'development-release.json').write_text(json.dumps(dict(binaryVersion=self.version,commit=self.commit)),encoding='utf-8')
        with patch.object(manual,'ASSETS',self.output), patch.object(manual.development_source,'verify'), patch.object(manual,'run') as run:
            with self.assertRaises(FileNotFoundError):manual.publish()
            run.assert_not_called()


if __name__=='__main__':unittest.main()
