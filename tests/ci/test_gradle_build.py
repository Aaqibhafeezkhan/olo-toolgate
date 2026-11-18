# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Recover only a verified false-negative plugin BOM lookup, never bad versions or failed tasks."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from tools.ci import gradle_build as runner


class GradleBuildTests(unittest.TestCase):
    def missing(self,version=None):return "A problem occurred configuring project ':control-plane'.\nCould not resolve all artifacts for configuration 'classpath'.\nCould not find io.quarkus:quarkus-bom:"+(version or runner.quarkus_version())+'.\n'
    def test_false_negative_is_retried_with_fresh_metadata_and_saved_logs(self):
        calls=[];delays=[]
        def execute(command,**options):
            calls.append(list(command));options['stdout'].write((self.missing() if len(calls)==1 else 'BUILD SUCCESSFUL').encode());return subprocess.CompletedProcess(command,1 if len(calls)==1 else 0)
        with tempfile.TemporaryDirectory() as temporary,patch.object(runner.subprocess,'run',side_effect=execute):
            output=Path(temporary)
            runner.build(['gradlew',':control-plane:quarkusBuild'],output,sleep=delays.append,published=lambda v:True)
            self.assertNotIn('--refresh-dependencies',calls[0]);self.assertIn('--refresh-dependencies',calls[1]);self.assertEqual([5],delays)
            self.assertIn('Could not find',(output/'gradle-1.log').read_text());self.assertIn('SUCCESSFUL',(output/'gradle-2.log').read_text())
    def test_genuine_missing_versions_tests_compilation_and_other_failures_stop(self):
        for text,exists,attempts in [(self.missing(),False,1),(self.missing('0.0.0-invalid'),True,1),
                (self.missing()+'\n> Task :control-plane:test FAILED',True,1),
                (self.missing()+'\nerror: compilation failed',True,1),
                ('> Task :control-plane:test FAILED\n'+'x'*70000+'\n'+self.missing(),True,1),
                (self.missing()+'\nThere were failing tests',True,1),('HTTP 500 unrelated failure',True,1),(self.missing(),True,3)]:
            with self.subTest(text=text),tempfile.TemporaryDirectory() as temporary:
                calls=[]
                def execute(command,**options):
                    calls.append(command);options['stdout'].write(text.encode());return subprocess.CompletedProcess(command,1)
                with patch.object(runner.subprocess,'run',side_effect=execute):
                    with self.assertRaises(subprocess.CalledProcessError):runner.build(['gradlew'],Path(temporary),sleep=lambda _:None,published=lambda v:exists)
                self.assertEqual(attempts,len(calls))
    def test_probe_validates_coordinate_and_does_not_accept_html_or_wrong_versions(self):
        from io import BytesIO
        version=runner.quarkus_version()
        for raw,expected in [(f'<project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>{version}</version></project>'.encode(),True),
                (b'<html>temporary error</html>',False),(b'<project><artifactId>quarkus-bom</artifactId><version>bad</version></project>',False)]:
            with self.subTest(raw=raw):
                response=BytesIO(raw);response.status=200
                with patch.object(runner.urllib.request,'build_opener') as opener:
                    opener.return_value.open.return_value=response
                    self.assertEqual(expected,runner.published_bom(version))
                    self.assertIn('https://repo.maven.apache.org/maven2/io/quarkus/quarkus-bom/',opener.return_value.open.call_args.args[0])
    def test_image_build_uses_the_guarded_runner_without_changing_the_pin(self):
        docker=(runner.ROOT/'apps/control-plane/Dockerfile').read_text(encoding='utf-8')
        self.assertIn('python3 tools/ci/gradle_build.py --log-directory build/image-gradle/control -- ./gradlew',docker)
        self.assertRegex(runner.quarkus_version(),r'^[0-9][A-Za-z0-9_.-]{0,63}$')
        self.assertIn('tools/ci/gradle_build.py',(runner.ROOT/'debug/profiled/stack.py').read_text(encoding='utf-8'))


if __name__=='__main__':unittest.main()
