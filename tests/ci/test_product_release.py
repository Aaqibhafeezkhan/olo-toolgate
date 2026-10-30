# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Release allocation, source identity, version propagation and interrupted publishing."""
import base64
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tarfile
import tomllib
import unittest
import zipfile
from unittest.mock import patch

import yaml
from tools.release import identity, prepare, pipeline, central
from tools.release.bundle import checksum_assets

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a'*40
PREPARED = 'b'*40


def release(build=42, channel='Beta'):
    return dict(schemaVersion=1, **identity.versions('1.0.0', build, channel), sourceBranch='main',
                sourceCommit=SHA, requestedCommit='latest', prepareRun='100')


class ReleaseIdentityTests(unittest.TestCase):
    def test_one_semver_for_every_component_and_numeric_platform_derivations(self):
        info = release()
        self.assertEqual(info['version'], '1.0.0-beta.42')
        self.assertEqual(info['displayVersion'], info['version'])
        self.assertEqual(info['branch'], 'release/1.0.0-beta.42')
        self.assertEqual(info['tag'], 'v1.0.0-beta.42')
        self.assertEqual(info['numericVersion'], '1.0.0.42')
        self.assertEqual(info['macosBuild'], '1.0.42')
        self.assertEqual(identity.versions('1.0.0', 65535, 'RC')['macosBuild'], '7.55.35')
        self.assertEqual(release(channel='Stable')['version'], '1.0.0')

    def test_invalid_versions_counters_and_identity_drift_fail(self):
        for base, build, channel in [('01.0.0', 1, 'Beta'), ('1.0.0.1', 1, 'Beta'),
                                     ('1.0.0', 0, 'Beta'), ('1.0.0', 65536, 'Beta'),
                                     ('65536.0.0', 1, 'Beta'), ('1.0.0', True, 'Beta')]:
            with self.subTest(base=base, build=build), self.assertRaises(ValueError):
                identity.versions(base, build, channel)
        info = release()
        info['version'] = '1.0.0-beta.43'
        with self.assertRaises(ValueError):
            identity.validate(info)


class ReleaseAllocationTests(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, GITHUB_RUN_ID='100', GITHUB_REPOSITORY='olo-labs/olo-toolgate')
        env.start()
        self.addCleanup(env.stop)

    def test_first_release_resolves_latest_once_and_reserves_number_one(self):
        with patch.object(prepare, 'pages', return_value=[[]]), patch.object(prepare, 'api', return_value={'sha':SHA}) as api, \
                patch.object(prepare, 'create_tag') as create:
            info = prepare.allocate('main', 'latest', '1.0.0', 'Beta')
        self.assertEqual(info['version'], '1.0.0-beta.1')
        self.assertEqual(info['sourceCommit'], SHA)
        api.assert_called_once_with('commits/main')
        self.assertEqual(create.call_args.args[0], 'release-builds/1.0.0/1')

    def test_counter_is_shared_across_sources_and_channels(self):
        old = release(42)
        old['prepareRun'] = '99'
        reservation = dict(ref='refs/tags/release-builds/1.0.0/42', object={'sha':'c'*40})
        def pages(path):
            return [[reservation]] if 'release-builds/' in path else [[]]
        def api(path):
            return {'message':json.dumps(old)} if path.startswith('git/tags/') else {'sha':SHA}
        with patch.object(prepare, 'pages', side_effect=pages), patch.object(prepare, 'api', side_effect=api), patch.object(prepare, 'create_tag'):
            info = prepare.allocate('release/1.0.0-beta.42', 'latest', '1.0.0', 'RC')
        self.assertEqual(info['version'], '1.0.0-rc.43')

    def test_retry_keeps_original_source_when_latest_has_moved(self):
        info = release()
        reservation = dict(ref='refs/tags/release-builds/1.0.0/42', object={'sha':'c'*40})
        with patch.object(prepare, 'pages', return_value=[[reservation]]), \
                patch.object(prepare, 'api', return_value={'message':json.dumps(info)}) as api, patch.object(prepare, 'create_tag') as create:
            self.assertEqual(prepare.allocate('main', 'latest', '1.0.0', 'Beta'), info)
        self.assertTrue(all(call.args[0].startswith('git/tags/') for call in api.call_args_list))
        create.assert_not_called()

    def test_legacy_branches_tags_and_draft_releases_reserve_build_numbers(self):
        def pages(path):
            if 'heads/release/' in path:
                return [[{'ref':'refs/heads/release/1.0.0-beta.8'}]]
            if path.startswith('releases?'):
                return [[{'tag_name':'v1.0.0-rc.12', 'draft':True}]]
            return [[]]
        with patch.object(prepare, 'pages', side_effect=pages), patch.object(prepare, 'api', return_value={'sha':SHA}), patch.object(prepare, 'create_tag'):
            self.assertEqual(prepare.allocate('main', 'latest', '1.0.0', 'Beta')['build'], 13)

    def test_explicit_commit_must_belong_to_selected_branch(self):
        def api(path):
            return {'status':'diverged'} if path.startswith('compare/') else {'sha':PREPARED}
        with patch.object(prepare, 'pages', return_value=[[]]), patch.object(prepare, 'api', side_effect=api), patch.object(prepare, 'create_tag') as create:
            with self.assertRaisesRegex(ValueError, 'ancestor'):
                prepare.allocate('main', SHA, '1.0.0', 'Beta')
        create.assert_not_called()

    def test_invalid_source_or_commit_is_rejected_before_remote_access(self):
        for branch, commit in [('feature/unreviewed', 'latest'), ('main', 'abc123'), ('main', '--help')]:
            with self.subTest(branch=branch, commit=commit), patch.object(prepare, 'api') as api, patch.object(prepare, 'pages') as pages:
                with self.assertRaises(ValueError):
                    prepare.allocate(branch, commit, '1.0.0', 'Beta')
            api.assert_not_called()
            pages.assert_not_called()

    def test_moved_prepared_branch_is_rejected(self):
        with patch.object(prepare, 'tag', return_value=(PREPARED, release())), patch.object(prepare, 'ref', return_value={'object':{'sha':SHA}}):
            with self.assertRaisesRegex(ValueError, 'moved'):
                prepare.resolve('release/1.0.0-beta.42')

    def test_build_claim_prevents_rebuilding_existing_version_in_another_run(self):
        record = (PREPARED, dict(commit=PREPARED, buildRun='200'))
        with patch.object(prepare, 'tag', return_value=record), patch.object(prepare, 'create_tag') as create:
            with self.assertRaisesRegex(ValueError, 'already has a build'):
                prepare.claim(PREPARED, release(), '')
            self.assertEqual(prepare.claim(PREPARED, release(), '200'), '200')
            with self.assertRaises(ValueError):
                prepare.claim(PREPARED, release(), '201')
        create.assert_not_called()

    def test_conflicting_immutable_tag_never_gets_updated(self):
        with patch.object(prepare, 'tag', return_value=(SHA, {'build':1})), patch.object(prepare, 'api') as api:
            with self.assertRaisesRegex(ValueError, 'conflict'):
                prepare.create_tag('prepared/1.0.0-beta.42', PREPARED, release())
        api.assert_not_called()


class VersionPropagationTests(unittest.TestCase):
    def test_real_generators_stamp_all_release_components_without_changing_wire_schemas(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            exact = {'VERSION','LICENSE','NOTICE.md','CHANGELOG.md','RELEASE-NOTES.md','Cargo.toml','Cargo.lock','package.json','build.gradle.kts',
                     'package-lock.json','apps/admin-ui/package.json','apps/browser-extension/manifest.json','core-mcp/echo/toolgate.json','examples/tool-builder-json/toolgate.json',
                     'deploy/helm/olo-toolgate/Chart.yaml','tools/contracts/version.py','tools/contracts/generate.py'}
            names = subprocess.check_output(['git','ls-files','-z'], cwd=ROOT).decode().split('\0')
            for name in names:
                if name and (name in exact or name.startswith(('packages/contracts/', 'config/initial/')) or name.endswith('/gradle-artifact.lockfile')):
                    path = target/name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT/name, path)
            before = {p.relative_to(target).as_posix():p.read_bytes() for p in (target/'packages/contracts/schemas').rglob('*.json')}
            prepare.stamp(target, release())
            rendered = (target/'RELEASE-NOTES.md').read_text(encoding='utf-8')
            self.assertIn('# OLO ToolGate 1.0.0-beta.42 release notes', rendered)
            for language in ('java', 'rust', 'typescript', 'php'):
                self.assertEqual(rendered, (target/f'packages/contracts/{language}/RELEASE-NOTES.md').read_text(encoding='utf-8'))
            self.assertEqual((target/'VERSION').read_text().strip(), '1.0.0-beta.42')
            self.assertEqual(json.loads((target/'core-mcp/echo/toolgate.json').read_text())['sdkVersion'], '1.0.0-beta.42')
            self.assertEqual(json.loads((target/'examples/tool-builder-json/toolgate.json').read_text())['sdkVersion'], '1.0.0-beta.42')
            self.assertEqual((target/'packages/contracts/VERSION').read_text().strip(), '1.0.0-beta.42')
            self.assertEqual(tomllib.loads((target/'Cargo.toml').read_text())['workspace']['package']['version'], '1.0.0-beta.42')
            lock = tomllib.loads((target/'Cargo.lock').read_text())
            self.assertEqual({p['version'] for p in lock['package'] if 'source' not in p}, {'1.0.0-beta.42'})
            original = tomllib.loads((ROOT/'Cargo.lock').read_text())
            self.assertEqual([p for p in lock['package'] if 'source' in p],
                             [p for p in original['package'] if 'source' in p])
            npm = json.loads((target/'package-lock.json').read_text())
            self.assertEqual({npm['packages'][p]['version'] for p in ('','apps/admin-ui','packages/contracts/typescript')}, {'1.0.0-beta.42'})
            mapping = json.loads((target/'config/initial/releases.json').read_text())
            self.assertEqual(mapping['releases']['1.0.0-beta.42'], 'standard-v1')
            extension = json.loads((target/'apps/browser-extension/manifest.json').read_text())
            self.assertEqual(extension['version'], '1.0.0.42')
            self.assertEqual(extension['version_name'], '1.0.0-beta.42')
            self.assertEqual(before, {p.relative_to(target).as_posix():p.read_bytes() for p in (target/'packages/contracts/schemas').rglob('*.json')})


class ReleaseBundleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root/'bundle'
        self.assets = self.output/'assets'
        self.assets.mkdir(parents=True)
        self.info = dict(release(), commit=PREPARED, buildRun='200')
        prepare.write_json(self.root/'build-release-selection.json', self.info)
        prepare.write_json(self.assets/'release.json', release())
        prepare.write_json(self.assets/'build-manifest.json', {key:self.info[key] for key in ('commit','version','buildRun','sourceCommit')} | {'tests':'passed'})
        for component in pipeline.IMAGES:
            folder = self.output/'images'/component
            folder.mkdir(parents=True)
            (folder/'image.tar').write_bytes(component.encode())
        self.rechecksum()
        for name, value in [('ROOT', self.root), ('OUTPUT', self.output)]:
            mocked = patch.object(pipeline, name, value)
            mocked.start()
            self.addCleanup(mocked.stop)

    def rechecksum(self):
        checksum_assets(self.assets)
        prepare.write_json(self.output/'bundle-checksums.json', {p.relative_to(self.output).as_posix():pipeline.digest(p) for p in self.output.rglob('*') if p.is_file() and p.name != 'bundle-checksums.json'})

    def test_exact_bundle_is_bound_to_original_run_commit_and_version(self):
        self.assertEqual(pipeline.validate_bundle(), self.info)
        for key, wrong in [('commit',SHA), ('version','1.0.0-beta.43'), ('buildRun','201')]:
            original = self.info[key]
            self.info[key] = wrong
            prepare.write_json(self.root/'build-release-selection.json', self.info)
            with self.subTest(key=key), self.assertRaises(ValueError):
                pipeline.validate_bundle()
            self.info[key] = original

    def test_tampered_or_unlisted_image_bytes_are_rejected(self):
        (self.output/'images/control/image.tar').write_bytes(b'different')
        with self.assertRaisesRegex(ValueError, 'bytes have changed'):
            pipeline.validate_bundle()
        self.rechecksum()
        (self.output/'unexpected-file').write_bytes(b'not checked')
        with self.assertRaises(ValueError):
            pipeline.validate_bundle()

    def test_missing_platform_image_cannot_pass_even_with_recomputed_checksums(self):
        (self.output/'images/device/image.tar').unlink()
        self.rechecksum()
        with self.assertRaisesRegex(ValueError, 'missing a required image'):
            pipeline.validate_bundle()


class RegistryResumeTests(unittest.TestCase):
    def test_maven_partial_publication_uploads_only_missing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            assets = output/'assets'
            assets.mkdir()
            for suffix in ('.jar','-sources.jar','-javadoc.jar','.pom','.module'):
                (assets/('toolgate-contracts-1.0.0-beta.42'+suffix)).write_bytes(suffix.encode())
            base = 'https://maven.example.test/releases/io/github/olo-labs/toolgate-contracts/1.0.0-beta.42/'
            first = base+'toolgate-contracts-1.0.0-beta.42.jar'
            remote = {first:b'.jar'}
            writes = []
            def request(url, data=None, authenticated=True):
                if data is not None:
                    writes.append(url)
                    remote[url] = data
                return remote.get(url)
            with patch.object(pipeline, 'OUTPUT', output), patch.object(pipeline, 'maven_request', side_effect=request), \
                    patch.object(prepare, 'create_tag'), patch.dict(os.environ, MAVEN_REPOSITORY_URL='https://maven.example.test/releases',
                    MAVEN_USERNAME='user', MAVEN_PASSWORD='fixture', GITHUB_REPOSITORY='olo-labs/olo-toolgate'):
                pipeline.publish_maven(dict(release(), commit=PREPARED))
                self.assertNotIn(first, writes)
                writes.clear()
                pipeline.publish_maven(dict(release(), commit=PREPARED))
                self.assertFalse(writes)
                remote[base+'toolgate-contracts-1.0.0-beta.42.pom'] = b'conflict'
                with self.assertRaisesRegex(ValueError, 'different bytes'):
                    pipeline.publish_maven(dict(release(), commit=PREPARED))
                self.assertFalse(writes)

    def test_central_retry_polls_persisted_deployment_without_signing_or_uploading(self):
        deployment = '12345678-1234-1234-1234-123456789abc'
        with patch.dict(os.environ, MAVEN_REPOSITORY_URL=central.UPLOAD_URL, MAVEN_USERNAME='user', MAVEN_PASSWORD='fixture'), \
                patch.object(central, 'bundle') as bundle, patch.object(central, 'request', return_value='{"deploymentState":"PUBLISHED"}') as request:
            central.publish(Path('unused'), '1.0.0-beta.42', deployment_id=deployment)
        bundle.assert_not_called()
        self.assertEqual(request.call_args.args[0], 'status?id='+deployment)

    def test_alias_order_does_not_compare_build_numbers_as_strings(self):
        self.assertGreater(pipeline.version_order('1.0.0-beta.42'), pipeline.version_order('1.0.0-beta.9'))
        self.assertGreater(pipeline.version_order('1.0.0-rc.43'), pipeline.version_order('1.0.0-beta.42'))
        self.assertGreater(pipeline.version_order('1.0.0-beta.3'), pipeline.version_order('1.0.0-alpha.2'))
        self.assertGreater(pipeline.version_order('1.0.0-alpha.2'), pipeline.version_order('1.0.0-dev.1'))
        self.assertGreater(pipeline.version_order('1.0.0'), pipeline.version_order('1.0.0-rc.43'))


class PackageVersionTests(unittest.TestCase):
    def test_every_actual_package_contains_the_same_version_and_old_sdk_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            assets=Path(temporary)
            version='1.0.0-beta.42'
            def tar(name, files):
                with tarfile.open(assets/name,'w:gz') as archive:
                    for path,data in files.items():
                        raw=data.encode()
                        entry=tarfile.TarInfo(path)
                        entry.size=len(raw)
                        archive.addfile(entry,io.BytesIO(raw))
            def jar(name, version):
                with zipfile.ZipFile(assets/name,'w') as archive:
                    archive.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\r\nImplementation-Version: '+version+'\r\n')
            (assets/f'toolgate-contracts-{version}.pom').write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.olo-labs</groupId>'
                f'<artifactId>toolgate-contracts</artifactId><version>{version}</version></project>')
            prepare.write_json(assets/f'toolgate-contracts-{version}.module',dict(component=dict(group='io.github.olo-labs',module='toolgate-contracts',version=version)))
            jar(f'toolgate-contracts-{version}.jar',version)
            jar(f'toolgate-sdk-api-{version}.jar',version)
            tar(f'olo-labs-toolgate-contracts-{version}.tgz',{'package/package.json':json.dumps(dict(version=version))})
            tar(f'olo-toolgate-contracts-{version}.crate',{f'olo-toolgate-contracts-{version}/Cargo.toml':f'[package]\nversion = "{version}"\n'})
            tar(f'olo-toolgate-contracts-{version}.tar.gz',{'VERSION':version+'\n'})
            tar(f'olo-toolgate-{version}.tgz',{'olo-toolgate/Chart.yaml':f'version: {version}\nappVersion: "{version}"\n'})
            tar(f'olo-toolgate-admin-ui-{version}.tar',{'release.json':json.dumps(dict(version=version,contracts=version))})
            with zipfile.ZipFile(assets/f'olo-toolgate-contracts-php-{version}.zip','w') as archive:
                archive.writestr('src/ContractSet.php',f"public const VERSION = '{version}';")
            pipeline.verify_packages(release(), assets)
            jar(f'toolgate-sdk-api-{version}.jar','0.10.0-dev')
            with self.assertRaisesRegex(ValueError,'JAR embedded version'):
                pipeline.verify_packages(release(), assets)


class OriginalBuildGateTests(unittest.TestCase):
    def test_reuse_requires_successful_original_platform_gates_and_reserved_run(self):
        info=dict(release(),commit=PREPARED,buildRun='200')
        producer=dict(path='.github/workflows/build-release.yml',event='workflow_dispatch',
                      head_branch=info['branch'],head_sha=PREPARED,repository=dict(full_name='olo-labs/olo-toolgate'))
        names=['preflight / metadata','foundation / check','clients / public-bundle','images / verify','Collect immutable release bundle']
        names += ['clients / native ('+target+')' for target in (
            'x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu','x86_64-pc-windows-msvc',
            'aarch64-pc-windows-msvc','x86_64-apple-darwin','aarch64-apple-darwin')]
        jobs=[dict(name=name,status='completed',conclusion='success') for name in names]
        with patch.object(pipeline,'selection',return_value=info), patch.object(prepare,'api',return_value=producer), \
                patch.object(prepare,'pages',return_value=[dict(jobs=jobs)]), \
                patch.object(prepare,'tag',return_value=(PREPARED,dict(commit=PREPARED,buildRun='200'))), \
                patch.dict(os.environ,GITHUB_REPOSITORY='olo-labs/olo-toolgate'):
            pipeline.verify_run('200',collected=True)
            for conclusion in ('failure','skipped','cancelled',None):
                jobs[-1]['conclusion']=conclusion
                with self.subTest(conclusion=conclusion),self.assertRaises(ValueError):
                    pipeline.verify_run('200',collected=True)
            jobs[-1]['conclusion']='success'
            for field, wrong in (('head_branch', 'main'), ('head_sha', SHA)):
                original = producer[field]
                producer[field] = wrong
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'trusted'):
                    pipeline.verify_run('200', collected=True)
                producer[field] = original
            producer['event']='pull_request'
            with self.assertRaisesRegex(ValueError,'trusted'):
                pipeline.verify_run('200',collected=True)


class ImmutablePublicationTests(unittest.TestCase):
    def test_publish_uses_prepared_identity_after_branch_advances(self):
        info = dict(release(), commit=PREPARED)
        with patch.object(prepare, 'tag', return_value=(PREPARED, info)) as tag, \
                patch.object(prepare, 'resolve', side_effect=AssertionError('Moving branch must not be resolved')):
            pipeline.verify_prepared_release(info)
            tag.assert_called_once_with(info['preparedTag'])
        for record in (None, (SHA, info), (PREPARED, dict(info, version='1.0.0-beta.99')),
                       (PREPARED, dict(info, branch='release/2.x')), (PREPARED, dict(info, build=99))):
            with self.subTest(record=record), patch.object(prepare, 'tag', return_value=record):
                with self.assertRaisesRegex(ValueError, 'Immutable prepared tag'):
                    pipeline.verify_prepared_release(info)


class NpmChannelTests(unittest.TestCase):
    def test_query_uses_existing_version_without_relying_on_latest(self):
        info = release()
        coordinate = '@olo-labs/toolgate-contracts@'+info['version']
        for tags, writes in [('{}', True), ('{"beta":"1.0.0-beta.9"}', True),
                             ('{"beta":"1.0.0-beta.99"}', False)]:
            with self.subTest(tags=tags), patch.object(pipeline, 'run') as run:
                run.return_value.stdout = tags
                pipeline.advance_npm_channel(info, dict(npm=coordinate))
                self.assertEqual(run.call_args_list[0].args[:4], ('npm', 'view', coordinate, 'dist-tags'))
                self.assertEqual(run.call_count, 2 if writes else 1)


class DockerHubPublicationTests(unittest.TestCase):
    def test_resume_and_conflicting_images(self):
        info = dict(release(), commit=PREPARED)
        doc = dict(Id='sha256:checked', Config=dict(Labels={
            'org.opencontainers.image.version':info['version'],
            'org.opencontainers.image.revision':PREPARED}))
        remote = dict(Descriptor=dict(digest='sha256:remote'), SchemaV2Manifest=dict(config=dict(digest=doc['Id'])))
        with patch.dict(os.environ, DOCKERHUB_NAMESPACE='olo-labs', DOCKERHUB_USERNAME='publisher', DOCKERHUB_TOKEN='test'), \
                patch.object(pipeline, 'IMAGES', {'control':('local:tested','olo-toolgate-control')}), \
                patch.object(pipeline, 'run') as run, patch.object(pipeline, 'remote_manifest') as manifest, \
                patch.object(prepare, 'create_tag') as tag:
            run.return_value.stdout = json.dumps([doc])
            for responses, pushes in [([None, remote],1), ([remote],0)]:
                run.reset_mock()
                manifest.side_effect = responses
                result = pipeline.publish_dockerhub(info)
                self.assertEqual(result['control'], 'docker.io/olo-labs/olo-toolgate-control@sha256:remote')
                self.assertEqual(sum(c.args[:2] == ('docker','push') for c in run.call_args_list), pushes)
                self.assertEqual(run.call_args_list[-1].args, ('docker','logout','docker.io'))
            remote['SchemaV2Manifest']['config']['digest'] = 'sha256:wrong'
            manifest.side_effect = [remote]
            with self.assertRaisesRegex(ValueError, 'different image'):
                pipeline.publish_dockerhub(info)
            self.assertEqual(run.call_args_list[-1].args, ('docker','logout','docker.io'))


class ProductWorkflowTests(unittest.TestCase):
    def test_quality_workflow_supports_explicit_blocking_checks(self):
        workflow = yaml.safe_load((ROOT/'.github/workflows/quality-summary.yml').read_text())
        job = workflow['jobs']['reports']
        self.assertEqual(job['continue-on-error'], '${{ !inputs.required_checks }}')
        for step in job['steps']:
            if 'continue-on-error' in step:
                self.assertEqual(step['continue-on-error'], '${{ !inputs.required_checks }}')
        sonar = next(step for step in job['steps'] if step.get('id') == 'sonar')
        self.assertIn('-Dsonar.qualitygate.wait=${{ inputs.required_checks }}', sonar['run'])
        self.assertIn('then exit 1', sonar['run'])

    def test_two_manual_entrypoints_keep_build_credentials_separate(self):
        prepare_workflow = yaml.safe_load((ROOT/'.github/workflows/create-release-branch.yml').read_text())
        workflow = yaml.safe_load((ROOT/'.github/workflows/build-release.yml').read_text())
        for document in (prepare_workflow, workflow):
            self.assertEqual(set(document['on']), {'workflow_dispatch'})
        jobs = workflow['jobs']
        self.assertEqual(jobs['publish']['environment'], 'production-release')
        self.assertNotIn('coverage', jobs)
        self.assertEqual(set(jobs['collect']['needs']), {'select', 'preflight', 'foundation', 'clients', 'images'})
        self.assertNotIn('release_branch', workflow['on']['workflow_dispatch']['inputs'])
        channel = workflow['on']['workflow_dispatch']['inputs']['channel']
        self.assertEqual((channel['options'], channel['default']), (['dev', 'alpha', 'beta', 'rc', 'release'], 'dev'))
        self.assertNotIn('channel', prepare_workflow['on']['workflow_dispatch']['inputs'])
        self.assertEqual(jobs['select']['if'], "startsWith(github.ref, 'refs/heads/release/')")
        self.assertIn('needs.collect.result', jobs['publish']['if'])
        for name in ('foundation','clients','images'):
            self.assertEqual(jobs[name]['with']['source_sha'], '${{ needs.select.outputs.commit }}')
            self.assertNotIn('secrets', jobs[name])
        self.assertTrue(jobs['clients']['with']['run_tests'])
        self.assertTrue(jobs['images']['with']['run_tests'])
        self.assertNotIn('tags', yaml.safe_load((ROOT/'.github/workflows/ci.yml').read_text())['on']['push'])
        download = next(s for s in jobs['publish']['steps'] if s.get('with',{}).get('name') == 'release-bundle')
        self.assertEqual(download['with']['run-id'], '${{ needs.select.outputs.buildRun }}')
        steps = jobs['publish']['steps']
        names = [step.get('name') for step in steps]
        login = names.index('Log in to GHCR for image attestations')
        self.assertGreater(login, names.index('Validate original build evidence and resume registry publication'))
        self.assertLess(login, names.index('Attest Control image'))
        self.assertIn('--password-stdin', steps[login]['run'])
        self.assertEqual(steps[login]['env']['GH_TOKEN'], '${{ github.token }}')
        self.assertEqual(steps[-1]['if'], 'always()')
        self.assertEqual(steps[-1]['run'], 'docker logout ghcr.io')
        self.assertEqual(steps[-2]['run'], 'python tools/release/pipeline.py finalize')


if __name__ == '__main__':
    unittest.main()
