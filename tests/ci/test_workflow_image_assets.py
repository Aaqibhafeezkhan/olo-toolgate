# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Image-only deployment assets and custody rejection checks."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
BUNDLE=ROOT/'deploy/compose/WorkflowStack'
sys.path.insert(0,str(BUNDLE/'scripts'))
import image_assets
import bootstrap
import package_assets


class ImageAssetsTests(unittest.TestCase):
    def test_common_image_needs_no_bundled_watch(self):
        self.manifest['packages']=[]
        self.manifest_path.write_text(json.dumps(self.manifest))
        image_assets.validate(self.assets)

    def test_external_package_selection_requires_exact_profile_pin(self):
        directory=self.root/'configuration/packages';directory.mkdir(parents=True)
        artifact=directory/'unrelated-name.olotool'
        with zipfile.ZipFile(artifact,'w') as archive:
            archive.writestr('toolgate-package.json',json.dumps(dict(id='acme.new-watch',version='2.1.0',sdk={'version':'1'})))
            archive.writestr('CHECKSUMS.sha256','bounded fixture metadata')
        metadata=package_assets.describe(artifact)
        source={'packageId':'acme.new-watch','version':'2.1.0','packageDigest':metadata['packageDigest']}
        actual,path=package_assets.select(source,directory.parent,self.assets)
        self.assertEqual(metadata,actual);self.assertEqual(artifact,path)
        source['packageDigest']='sha256:'+'f'*64
        with self.assertRaisesRegex(ValueError,'exact profile-pinned'):
            package_assets.select(source,directory.parent,self.assets)

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.assets=self.root/'assets'
        (self.assets/'packages').mkdir(parents=True)
        packages=[]
        for name in ('hotfolder','hotfolder-watch'):
            data=name.encode()
            (self.assets/'packages'/(name+'.olotool')).write_bytes(data)
            packages.append(dict(id='examples.'+name,artifact=name+'.olotool',version='0.1.0',
                sdkVersion='1',packageDigest='sha256:'+'a'*64,artifactSha256=hashlib.sha256(data).hexdigest()))
        self.manifest=dict(formatVersion=2,packages=packages)
        self.manifest_path=self.assets/'packages/manifest.json'
        self.manifest_path.write_text(json.dumps(self.manifest))
        for relative in ('examples/langchain/manage.py','examples/langchain/packages.py',
                'debug/profiled/configure-debug-agent.py','debug/profiled/agent-mimic.py',
                'tools/access/presets.py','packages/contracts/management-permissions.json','VERSION'):
            path=self.assets/'operator'/relative
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text('fixture')

    def test_changed_artifacts_and_unknown_format_are_refused(self):
        image_assets.validate(self.assets)
        (self.assets/'packages/hotfolder.olotool').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'digest mismatch'):image_assets.validate(self.assets)
        self.manifest['formatVersion']=0
        self.manifest_path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError,'Unsupported'):image_assets.validate(self.assets)

    def test_fresh_deployment_copies_image_packages_without_package_build(self):
        spec=importlib.util.spec_from_file_location('image_stack_manager',BUNDLE/'manage.py')
        manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
        state=self.root/'state';state.mkdir()
        operator=Mock()
        settings={'id':'fixture','fileAgent':None,'deployment':{'source':{'packageId':'examples.hotfolder-watch','version':'0.1.0'}}}
        manager.prepare_packages(operator,settings,state,['compose'],self.assets)
        operator.packages.prepare.assert_not_called()
        operator.packages.registry.assert_called_once_with(['compose'],state,os.environ)
        self.assertEqual(b'hotfolder-watch',(state/'package/bootstrap/trigger.olotool').read_bytes())

    @unittest.skipUnless(hasattr(os,'getuid') and os.getuid()==0,'Linux root-owned volume test')
    def test_bootstrap_updates_current_format_but_refuses_unknown_storage(self):
        profile=self.root/'profile.json'
        package=self.manifest['packages'][1]
        profile.write_text(json.dumps(dict(profileVersion=2,profileId='test',packages=[dict(packageId=package['id'],version=package['version'])])))
        (self.assets/'packages/core-packages.lock.json').write_text(json.dumps(dict(schemaVersion=1,sdkVersion='1',packages=[package])))
        output=self.root/'bootstrap'
        bootstrap.initialize(self.assets/'packages',profile,output)
        bootstrap.initialize(self.assets/'packages',profile,output)
        self.assertEqual(b'hotfolder-watch',(output/'core/hotfolder-watch.olotool').read_bytes())
        self.assertEqual(0o644,(output/'profile/trigger.json').stat().st_mode&0o777)
        output.chmod(0o777)
        with self.assertRaisesRegex(ValueError,'root-owned'):
            bootstrap.initialize(self.assets/'packages',profile,output)
        output.chmod(0o755)
        linked=self.root/'linked';linked.mkdir()
        (linked/'format.json').write_text('{"formatVersion":2}')
        (linked/'core').symlink_to(output/'core',target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'Linked'):
            bootstrap.initialize(self.assets/'packages',profile,linked)
        foreign=self.root/'foreign';foreign.mkdir()
        (foreign/'old').write_text('preserve')
        with self.assertRaisesRegex(ValueError,'without format marker'):
            bootstrap.initialize(self.assets/'packages',profile,foreign)
        self.assertEqual('preserve',(foreign/'old').read_text())


if __name__=='__main__':unittest.main()
