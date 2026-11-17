# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Portable profile build/embedded package/release integrity, without live providers."""
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from profiles import bundle
from profiles import build as builder
from tools.release.profiles import collect

ROOT=Path(__file__).resolve().parents[2]


class ProfileTests(unittest.TestCase):
    def test_profile_artifact_names_are_platform_safe_and_do_not_alias_identifiers(self):
        self.assertNotEqual(builder.filename('workflow:one'),builder.filename('workflow-one'))
        self.assertNotIn(':',builder.filename('workflow:one'))
        self.assertLessEqual(len(builder.filename('workflow-'+'a'*128)),48)

    def test_file_recipe_builds_a_workflow_and_repins_its_descriptor_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'source';folder.mkdir();output=root/'bin';output.mkdir()
            raw=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olo').read_bytes();metadata,manifest=bundle.package(raw)
            (folder/'tool.olo').write_bytes(raw)
            config=deepcopy(json.loads((ROOT/'tests/fixtures/contracts/v2/valid.json').read_text())['WorkflowConfiguration'])
            config['dispatchBindings']=[]
            for agent in config['agentDefinitions']:agent['contextSources']=[]
            tool=manifest['tools'][0]
            config['agentDefinitions'][0]['contextSources']=[dict(toolId=tool['descriptor']['toolId'],arguments={})]
            profile=dict(profileVersion=2,profileId='file-source',packages=[],workflows=config)
            refs=dict(tools=[dict(id=tool['descriptor']['toolId'],packageDigest='a'*64)],bindings=[dict(allowedPackageDigests=['a'*64])])
            for name,data in [('workflow-profile.json',profile),('referenced-data.json',refs),('connections.json',[]),('build.json',dict(packages=[dict(packageId=metadata['packageId'],version=metadata['version'],file='tool.olo')]))]:
                (folder/name).write_text(json.dumps(data))
            rows=builder.build(folder,output,'1.2.3','b'*40,'test',{},None)
            self.assertEqual(1,len(rows))
            with zipfile.ZipFile(output/rows[0]['filename']) as archive:
                actual=json.loads(archive.read('referenced-data.json'))
                self.assertEqual(metadata['version'],actual['tools'][0]['version'])
                self.assertEqual([tool['toolDescriptorDigest'].removeprefix('sha256:')],actual['bindings'][0]['allowedPackageDigests'])
            with self.assertRaisesRegex(ValueError,'must use .olo'):
                builder.source_package(dict(packageId='x',version='1.0.0',file='obsolete.tgpkg'),folder,{},None)

    def test_embedded_package_bytes_round_trip_and_tampering_is_rejected(self):
        package=(ROOT/'tests/fixtures/packages/cases/valid-deflated.olo').read_bytes()
        item,manifest=bundle.package(package)
        profile=dict(profileVersion=2,profileId='test',packages=[],workflows={'workflows':[{'id':'workflow'}]})
        data,inventory=bundle.pack(profile,{},[],[package],dict(version='1.0.0',sourceCommit='a'*40,build='local'))
        self.assertEqual([item],inventory['packages'])
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(package,archive.read(item['path']))
            self.assertFalse(any('readme' in name.lower() for name in archive.namelist()))
            entries={name:archive.read(name) for name in archive.namelist()}
        entries[item['path']]=b'tampered'
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
            for name,value in entries.items():archive.writestr(name,value)
        with self.assertRaises((ValueError,zipfile.BadZipFile)):bundle.verify(output.getvalue())

    def test_release_collection_checks_version_source_hashes_and_embedded_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'bin';source.mkdir();output=root/'assets'
            data,manifest=bundle.pack(dict(workflows={'workflows':[{'id':'one'}]}),{},[],[],dict(version='1.2.3',sourceCommit='a'*40,build='1'))
            (source/'one-1.2.3.olo').write_bytes(data)
            inventory=dict(format='olo-profiles',version='1.2.3',sourceCommit='a'*40,profiles=[dict(filename='one-1.2.3.olo',sha256=bundle.digest(data),bytes=len(data),packages=[])])
            (source/'olo-profiles-1.2.3.json').write_text(json.dumps(inventory))
            self.assertEqual(2,len(collect(source,output,'1.2.3','a'*40)))
            self.assertEqual(data,(output/'one-1.2.3.olo').read_bytes())
            with self.assertRaisesRegex(ValueError,'source/version'):collect(source,output,'2.0.0')
            with self.assertRaisesRegex(ValueError,'source/version'):collect(source,output,commit='b'*40)
            (source/'one-1.2.3.olo').write_bytes(data+b'changed')
            with self.assertRaises(ValueError):collect(source,output)

    def test_source_configurations_are_complete_and_only_new_archive_extension_is_used(self):
        for folder in (ROOT/'profiles/configurations').iterdir():
            if not folder.is_dir():continue
            for name in ('workflow-profile.json','referenced-data.json','connections.json','build.json','README.md'):self.assertTrue((folder/name).is_file(),str(folder/name))
            refs=json.loads((folder/'referenced-data.json').read_text(encoding='utf-8'))
            self.assertNotIn('users',refs);self.assertNotIn('identityBindings',refs)
            self.assertTrue(json.loads((folder/'build.json').read_text())['packages'])
        workflow=(ROOT/'.github/workflows/foundation.yml').read_text()
        self.assertIn('python profiles/build.py',workflow)
        self.assertIn('foundation-stage-profiles',workflow)
        self.assertIn('tools/release/profiles.py',workflow)


if __name__=='__main__':unittest.main()
