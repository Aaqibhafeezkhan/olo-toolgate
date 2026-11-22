# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Recover dependency lookups without masking compiler, test or publication failures."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from tools.ci import gradle_build as runner


class GradleBuildTests(unittest.TestCase):
    def test_throttling_preserves_cached_dependencies(self):
        calls=[]
        def execute(command,**options):
            calls.append(list(command))
            options['stdout'].write((('Could not resolve all files for configuration\n'+runner.CENTRAL+
                'example.pom Received status code 429 from server') if len(calls)==1 else 'BUILD SUCCESSFUL').encode())
            return subprocess.CompletedProcess(command,1 if len(calls)==1 else 0)
        with tempfile.TemporaryDirectory() as temporary,patch.object(runner.subprocess,'run',side_effect=execute):
            runner.build(['gradlew'],Path(temporary),sleep=lambda _:None)
        self.assertEqual(2,len(calls))
        self.assertNotIn('--refresh-dependencies',calls[1])

    def test_offline_cache_failure_never_probes_the_network_or_retries(self):
        def execute(command,**options):
            options['stdout'].write(self.missing().encode())
            return subprocess.CompletedProcess(command,1)
        with tempfile.TemporaryDirectory() as temporary,patch.object(runner.subprocess,'run',side_effect=execute) as run:
            with patch.object(runner.urllib.request,'build_opener') as network:
                with self.assertRaises(subprocess.CalledProcessError):
                    runner.build(['gradlew','--offline'],Path(temporary),sleep=lambda _:self.fail('offline retry'))
                network.assert_not_called()
            self.assertEqual(1,run.call_count)

    def missing(self,version='3.40.1'):
        coordinate=('io.quarkus','quarkus-bom',version)
        return "A problem occurred configuring project ':control-plane'.\nCould not resolve all artifacts for configuration 'classpath'.\nCould not find "+':'.join(coordinate)+'.\n'+runner.pom_url(coordinate)+'\n'
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
        for text,exists,attempts in [(self.missing(),False,1),(self.missing('0.0.0-invalid'),False,1),
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
        version='3.40.1'
        for raw,expected in [(f'<project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>{version}</version></project>'.encode(),True),
                (b'<html>temporary error</html>',False),(b'<project><artifactId>quarkus-bom</artifactId><version>bad</version></project>',False)]:
            with self.subTest(raw=raw):
                response=BytesIO(raw);response.status=200
                with patch.object(runner.urllib.request,'build_opener') as opener:
                    opener.return_value.open.return_value=response
                    self.assertEqual(expected,runner.published_dependency(('io.quarkus','quarkus-bom',version)))
                    self.assertIn('https://repo.maven.apache.org/maven2/io/quarkus/quarkus-bom/',opener.return_value.open.call_args.args[0])
    def test_image_build_uses_the_guarded_runner(self):
        docker=(runner.ROOT/'apps/control-plane/Dockerfile').read_text(encoding='utf-8')
        self.assertIn('python3 tools/ci/gradle_build.py --log-directory build/image-gradle/control -- ./gradlew',docker)
        self.assertIn('tools/ci/gradle_build.py',(runner.ROOT/'debug/profiled/stack.py').read_text(encoding='utf-8'))

    def test_package_classpath_misses_require_every_coordinate_to_be_published(self):
        coordinates=[('org.junit.jupiter','junit-jupiter','5.14.2'),
                     ('com.fasterxml.jackson.core','jackson-annotations','2.21'),
                     ('com.fasterxml.jackson.core','jackson-databind','2.21.7')]
        for task in ('compileTestJava','toolgateCheck','toolgateResolveDependencies'):
            text=f'> Task :example:{task} FAILED\nCould not resolve all files for configuration\n'
            text+='\n'.join('Could not find '+':'.join(item)+'.\n'+runner.pom_url(item) for item in coordinates)
            with self.subTest(task=task):
                self.assertIsNotNone(runner.retry_reason(text,published=lambda item:True))
                self.assertIsNone(runner.retry_reason(text,published=lambda item:item!=coordinates[0]))
                self.assertIsNone(runner.retry_reason(text+'\nThere were failing tests',published=lambda item:True))
                self.assertIsNone(runner.retry_reason(text.replace(runner.CENTRAL,'https://other.invalid/'),published=lambda item:True))

    def test_central_throttling_recovers_even_inside_configuration_cache_errors(self):
        text="Configuration cache state could not be cached\nCould not resolve all files for configuration ':contracts-java:compileClasspath'.\nCould not GET '"+runner.CENTRAL+"example.pom'. Received status code 429 from server: Too Many Requests"
        self.assertIsNotNone(runner.retry_reason(text))
        self.assertIsNone(runner.retry_reason(text+'\n> Task :example:test FAILED'))
        self.assertIsNone(runner.retry_reason(text.replace('429','503')))

    def test_example_sdk_bootstraps_refuse_unrelated_application_plugins(self):
        import shlex
        for script in ('tool-sdk/java-sdk/e2e/run.sh', 'examples/run-java-examples.sh'):
            with self.subTest(script=script):
                source = (runner.ROOT/script).read_text(encoding='utf-8')
                bootstrap = next(line for line in source.splitlines() if './gradlew' in line and 'publishJavaSdkLocally' in line)
                command = shlex.split(bootstrap.split('./gradlew', 1)[1].split('&&', 1)[0])
                self.assertIn('--configure-on-demand', command)
                self.assertEqual(':publishJavaSdkLocally', command[-1])
                self.assertIn('-I', command)
                self.assertEqual('tools/ci/sdk-isolation.init.gradle', command[command.index('-I') + 1])


if __name__=='__main__':unittest.main()
