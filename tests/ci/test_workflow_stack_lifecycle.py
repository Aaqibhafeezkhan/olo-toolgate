# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Interactive selection and scoped, destructive stack lifecycle checks."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock,patch

ROOT=Path(__file__).resolve().parents[2]
BUNDLE=ROOT/'deploy/compose/WorkflowStack'
sys.path.insert(0,str(BUNDLE/'scripts'))
import lifecycle


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name)/'bundle with spaces'
        self.root.mkdir()
        for name in ('z-summary','a-issue'):
            folder=self.root/'configurations'/name
            folder.mkdir(parents=True)
            (folder/'workflow-profile.json').write_text('{}')

    def test_numbered_selection_reprompts_and_uses_explicit_serial(self):
        output=[]
        read=Mock(side_effect=['no','0','3','2'])
        selected=lifecycle.choose(self.root,'a-issue',read,output.append)
        self.assertEqual('z-summary',selected)
        self.assertIn('  1. a-issue (default)',output)
        self.assertIn('  2. z-summary',output)
        self.assertFalse((self.root/'state').exists())

    def test_native_engine_scopes_are_stable_and_distinct_per_bundle_and_configuration(self):
        hostname=lifecycle.device_hostname(self.root,'a-issue')
        self.assertEqual(hostname,lifecycle.device_hostname(self.root/'.','a-issue'))
        self.assertNotEqual(hostname,lifecycle.device_hostname(self.root,'z-summary'))
        self.assertNotEqual(hostname,lifecycle.device_hostname(self.root/'other-clone','a-issue'))

    def test_enter_selects_displayed_default_and_eof_never_starts(self):
        self.assertEqual('z-summary',lifecycle.choose(self.root,'z-summary',lambda _: '',lambda _:None))
        with self.assertRaisesRegex(ValueError,'input is required'):
            lifecycle.choose(self.root,'z-summary',Mock(side_effect=EOFError),lambda _:None)

    def test_storage_cleanup_removes_state_and_outputs_preserving_source_and_models(self):
        for kind in ('state','data','models'):
            path=self.root/kind/'nested'
            path.mkdir(parents=True)
            (path/'fixture.txt').write_text('fixture')
        lifecycle.storage(self.root,None,remove=True)
        self.assertFalse((self.root/'state').exists())
        self.assertFalse((self.root/'data').exists())
        self.assertTrue((self.root/'models/nested/fixture.txt').is_file())
        self.assertTrue((self.root/'configurations/a-issue/workflow-profile.json').is_file())

    def test_cleanup_refuses_path_traversal_and_links_without_deleting_target(self):
        outside=Path(self.directory.name)/'outside'
        outside.mkdir()
        sentinel=outside/'sentinel'
        sentinel.write_text('keep')
        with self.assertRaises(ValueError):lifecycle.storage(self.root,'../outside',remove=True)
        try:(self.root/'state').symlink_to(outside,target_is_directory=True)
        except OSError:
            return  # Windows link privilege is not needed for the traversal negative above.
        self.addCleanup(lambda:(self.root/'state').unlink())
        with self.assertRaises(ValueError):lifecycle.storage(self.root,None,remove=True)
        self.assertEqual('keep',sentinel.read_text())

    def test_foreign_same_named_docker_project_is_refused_before_removal(self):
        project=lifecycle.PREFIX+'a-issue'
        labels={lifecycle.PROJECT:project,lifecycle.DIRECTORY:str(Path(self.directory.name)/'foreign')}
        with patch.object(lifecycle,'objects',side_effect=[[('a'*12,labels)],[],[]]),patch.object(lifecycle,'docker') as docker:
            with self.assertRaisesRegex(ValueError,'another directory'):lifecycle.destroy(self.root,'a-issue')
        docker.assert_not_called()

    def test_destroy_removes_owned_resources_and_storage_only(self):
        project=lifecycle.PREFIX+'a-issue'
        labels={lifecycle.PROJECT:project,lifecycle.DIRECTORY:str(self.root)}
        owner={lifecycle.PROJECT:project,lifecycle.ROOT_LABEL:str(self.root)}
        foreign={lifecycle.PROJECT:'unrelated',lifecycle.ROOT_LABEL:str(Path(self.directory.name)/'foreign')}
        for kind in ('state','data'):
            path=self.root/kind/'a-issue'
            path.mkdir(parents=True)
            (path/'fixture').write_text('generated')
        with patch.object(lifecycle,'objects',side_effect=[
                [('a'*12,labels)],[('owned-volume',owner),('foreign-volume',foreign)], [('b'*12,owner)]]),patch.object(lifecycle,'docker',return_value='') as docker:
            lifecycle.destroy(self.root,'a-issue')
        calls=[call.args for call in docker.call_args_list]
        self.assertIn(('container','rm','--force','--volumes','a'*12),calls)
        self.assertIn(('volume','rm','owned-volume'),calls)
        self.assertNotIn(('volume','rm','foreign-volume'),calls)
        self.assertFalse((self.root/'state/a-issue').exists())
        self.assertFalse((self.root/'data/a-issue').exists())

    def test_ambiguous_native_owner_is_refused_before_stopping_containers(self):
        own={lifecycle.PROJECT:lifecycle.PREFIX+'a-issue',lifecycle.DIRECTORY:str(self.root),
             'com.docker.compose.service':'linux-device'}
        foreign={lifecycle.PROJECT:'unrelated','com.docker.compose.service':'linux-device'}
        with patch.object(lifecycle,'objects',side_effect=[[('a'*12,own),('b'*12,foreign)],[],[]]),patch.object(lifecycle,'docker',return_value='same-hostname') as docker:
            with self.assertRaisesRegex(ValueError,'overlaps'):
                lifecycle.destroy(self.root,'a-issue')
        self.assertTrue(all(call.args[1]=='inspect' for call in docker.call_args_list))

    def test_orphan_storage_without_verified_ownership_is_refused(self):
        volume={lifecycle.PROJECT:lifecycle.PREFIX+'a-issue'}
        with patch.object(lifecycle,'objects',side_effect=[[],[('orphan-volume',volume)],[]]),patch.object(lifecycle,'docker') as docker:
            with self.assertRaisesRegex(ValueError,'cannot be verified'):
                lifecycle.destroy(self.root,'a-issue')
        docker.assert_not_called()

    @unittest.skipUnless(os.name=='nt','Windows batch execution')
    def test_batch_files_forward_arguments_from_another_directory_and_preserve_exit_code(self):
        scripts=self.root/'scripts with spaces'
        scripts.mkdir()
        (scripts/'manage.py').write_text('import json,sys\nprint(json.dumps(sys.argv[1:]))\nsys.exit(7)\n')
        for name,command in [('start.bat','start'),('stop.bat','destroy')]:
            shutil.copyfile(BUNDLE/name,scripts/name)
            result=subprocess.run(['cmd.exe','/d','/c','call',str(scripts/name),'--configuration','z-summary'],
                                  cwd=self.directory.name,capture_output=True,text=True)
            self.assertEqual(7,result.returncode,result.stderr)
            self.assertEqual([command,'--configuration','z-summary'],json.loads(result.stdout.strip()))

    def test_start_reuses_images_and_pulls_pinned_references_instead_of_building_them(self):
        spec=importlib.util.spec_from_file_location('lifecycle_manager',BUNDLE/'manage.py')
        manager=importlib.util.module_from_spec(spec);spec.loader.exec_module(manager)
        with patch.dict(os.environ,TOOLGATE_QUICKSTART_IMAGE='example/quickstart@sha256:'+'a'*64,
                        TOOLGATE_RUNTIME_IMAGE='local-runtime:test'),patch.object(manager.subprocess,'run',side_effect=[Mock(returncode=1),Mock(returncode=0)]),patch.object(manager,'run') as run:
            manager.ensure_start_images()
        run.assert_called_once_with(['docker','pull','example/quickstart@sha256:'+'a'*64])


if __name__=='__main__':unittest.main()
