# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Startup timing excludes input and survives deployment failures without hiding exit codes."""
import contextlib
import importlib.util
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[2]


class StartupTimingTests(unittest.TestCase):
    def test_published_start_counts_only_work_after_final_credentials_on_success_and_failure(self):
        for failure in (False,True):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as temporary:
                spec=importlib.util.spec_from_file_location('timed_published',ROOT/'deploy/docker/quickstart/dev/manage.py')
                module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
                home=Path(temporary);(home/'.env.example').write_text('fixture')
                now=[0.0]
                def credentials(*args,**kwargs):
                    now[0]+=100  # Time spent supplying final credentials must be excluded.
                    print('Final credentials supplied')
                    return {},False
                def images():
                    print('Deployment work')
                    now[0]+=5
                    if failure:raise ValueError('fixture deployment failure')
                    return dict(quickstart='q',device='d',runtime='r')
                stack=Mock(images=images)
                bots=Mock(choose=Mock(return_value=[dict(requiresOllama=False)]),prompt_credentials=credentials)
                output=io.StringIO()
                with patch.object(module,'HERE',home),patch.object(module,'settings',return_value={}), \
                        patch.object(module,'configure'),patch.object(module.sys,'argv',['manage.py','start','--without-ollama','--without-monitoring']), \
                        patch.object(module.sys.stdin,'isatty',return_value=False), \
                        patch.dict(sys.modules,{'stack':stack,'bot.quickstart':bots}), \
                        patch.object(module.time,'perf_counter',side_effect=lambda:now[0]), \
                        patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],0)), \
                        contextlib.redirect_stdout(output):
                    if failure:
                        with self.assertRaisesRegex(ValueError,'fixture deployment failure'):module.main()
                    else:module.main()
                text=output.getvalue()
                self.assertLess(text.index('Final credentials supplied'),text.index('Startup started (inputs finalized):'))
                self.assertLess(text.index('Startup started (inputs finalized):'),text.index('Deployment work'))
                self.assertIn('Startup ended:',text)
                self.assertIn('Total time after user input: 5.000 seconds',text)

    @unittest.skipUnless(shutil.which('powershell.exe'),'Windows PowerShell required for the batch entry point')
    def test_debug_start_reports_times_after_both_prompts_and_preserves_native_exit_code(self):
        for code in (0,7):
            with self.subTest(code=code),tempfile.TemporaryDirectory() as temporary:
                home=Path(temporary)
                shutil.copyfile(ROOT/'debug/profiled/manage.ps1',home/'manage.ps1')
                python=sys.executable.replace("'","''")
                (home/'python.ps1').write_text("function Find-DebugPython { param([switch]$InstallIfMissing) return '"+python+"' }\nfunction Read-Host { param([string]$Prompt) Write-Host 'Input finalized'; return '2' }\n",encoding='utf-8')
                (home/'stack.py').write_text(f'import sys\nsys.exit({code})\n',encoding='utf-8')
                result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(home/'manage.ps1'),'start'],capture_output=True,text=True,timeout=20)
                self.assertEqual(code,result.returncode,result.stdout+result.stderr)
                self.assertEqual(2,result.stdout.count('Input finalized'))
                self.assertLess(result.stdout.rindex('Input finalized'),result.stdout.index('Startup started (inputs finalized):'))
                self.assertIn('Startup ended:',result.stdout)
                self.assertRegex(result.stdout,r'Total time after user input: \d+[.,]\d{3} seconds')
