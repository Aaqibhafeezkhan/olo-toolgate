# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""The Windows wrapper delegates only the selected operation to the stack manager."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('profiled_stack',ROOT/'debug/profiled/stack.py')
stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack)


class StackManagementTests(unittest.TestCase):
    def test_profile_edits_do_not_change_runtime_image_but_source_edits_do(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);runtime=root/'apps/langgraph-runtime/worker.py';profile=root/'bot/configurations/demo/bot.json'
            for path in (runtime,profile):path.parent.mkdir(parents=True,exist_ok=True);path.write_text('initial')
            with patch.object(stack,'ROOT',root):
                first=stack.image_tag('runtime');profile.write_text('another profile')
                self.assertEqual(first,stack.image_tag('runtime'))
                runtime.write_text('another worker')
                self.assertNotEqual(first,stack.image_tag('runtime'))

    def test_cached_images_are_reused_without_build(self):
        with patch.object(stack,'image_tag',side_effect=lambda m:'image-'+m),patch.object(stack,'image_exists',return_value=True),patch.object(stack,'run') as run:
            self.assertEqual({'quickstart':'image-quickstart','device':'image-device','runtime':'image-runtime'},stack.images())
            run.assert_not_called()

    def test_only_missing_module_is_built(self):
        with patch.object(stack,'image_tag',side_effect=lambda m:'image-'+m),patch.object(stack,'image_exists',side_effect=lambda i:i!='image-device'),patch.object(stack,'run') as run:
            stack.images()
            self.assertEqual(1,run.call_count)
            self.assertEqual(['docker','build','-f','debug/profiled/linux-device.Dockerfile','-t','image-device','.'],run.call_args.args[0])

    def test_foreign_storage_is_preserved_and_refused(self):
        outputs=[subprocess.CompletedProcess([],0,'toolgate-profiled_data\n'),subprocess.CompletedProcess([],0,json.dumps([{'Labels':{}}]))]
        with patch.object(stack,'run',side_effect=outputs) as run:
            with self.assertRaises(ValueError):stack.check_owned()
            self.assertEqual(2,run.call_count)
            self.assertTrue(all('rm' not in c.args[0] for c in run.call_args_list))

    def test_blank_composition_contains_no_model_or_workflow_seed(self):
        document=stack.definition({m:'image-'+m for m in ('quickstart','device','runtime')})
        self.assertEqual({'quickstart','device','langgraph'},set(document['services']))
        env=document['services']['quickstart']['environment']
        self.assertEqual('',env['TOOLGATE_CONTROL_PROFILE_PATH'])
        self.assertEqual('',env['TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH'])
        self.assertNotIn('/var/run/docker.sock:/var/run/docker.sock',document['services']['quickstart']['volumes'])
        self.assertEqual('langgraph',document['services']['langgraph']['volumes'][0]['volume']['subpath'])


@unittest.skipUnless(os.name=='nt' and shutil.which('powershell.exe'),'Windows PowerShell required')
class DebugManagementTests(unittest.TestCase):
    def test_corrupt_cached_bootstrap_is_refused_before_extraction_or_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'debug/profiled';folder.mkdir(parents=True)
            shutil.copyfile(ROOT/'debug/profiled/python.ps1',folder/'python.ps1')
            runtime=root/'.dev/profiled';runtime.mkdir(parents=True)
            for architecture in ('amd64','arm64'):
                (runtime/('python-3.14.10-embed-'+architecture+'.zip')).write_bytes(b'corrupt untrusted archive')
            command=". '"+str(folder/'python.ps1')+"'; Install-DebugPython"
            result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-Command',command],capture_output=True,text=True,timeout=30)
            self.assertNotEqual(0,result.returncode)
            self.assertIn('checksum mismatch',result.stderr)
            self.assertEqual([],list(runtime.glob('extract-*')))

    def test_wrapper_delegates_from_any_working_directory_and_preserves_exit_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'debug/profiled';folder.mkdir(parents=True)
            shutil.copyfile(ROOT/'debug/profiled/manage.ps1',folder/'manage.ps1')
            (folder/'python.ps1').write_text('function Find-DebugPython { return $env:TEST_DEBUG_PYTHON }',encoding='utf-8')
            (folder/'stack.py').write_text('import json,sys; print(json.dumps(sys.argv[1:])); sys.exit(7 if sys.argv[1]=="stop" else 0)',encoding='utf-8')
            for operation,extra,expected in [('start',[],'start'),('stop',[],'stop'),('restart',[],'restart'),('start',['-ValidateOnly'],'validate')]:
                with self.subTest(operation=operation,extra=extra):
                    result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(folder/'manage.ps1'),operation,*extra],cwd=root,env=dict(os.environ,TEST_DEBUG_PYTHON=sys.executable),capture_output=True,text=True,timeout=30)
                    self.assertEqual(7 if expected=='stop' else 0,result.returncode,result.stderr)
                    self.assertEqual([expected],json.loads(result.stdout))


if __name__=='__main__':unittest.main()
