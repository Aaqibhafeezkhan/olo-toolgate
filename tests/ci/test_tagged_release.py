# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Versioned publication boundaries, with no registry writes or full build replay."""
from contextlib import redirect_stderr
import io
import json
import os
import re
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml
from tools.ci import preflight
from tools.release import development as shared, tagged, notes

ROOT=Path(__file__).resolve().parents[2]
SHA='a'*40
REPO='olo-labs/olo-toolgate'


class TaggedReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name);self.output=self.root/'build/development'
        for path in ('VERSION','packages/contracts/VERSION'):
            target=self.root/path;target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text('1.2.3\n',encoding='utf-8')
        self.foundation=self.output/'foundation/build/release';self.foundation.mkdir(parents=True)
        self.assets=self.output/'assets';self.assets.mkdir()
        names=['LICENSE','NOTICE.md','RELEASE-NOTES.md','CHANGELOG.md','compatibility.json',
               'foundation-upgrades.md','gateway-upgrades.md','control-upgrades.md',
               'sbom.cdx.json','olo-toolgate-1.2.3.tgz','olo-toolgate-contracts-1.2.3.tar.gz']
        names += [f'toolgate-contracts-1.2.3{c}.jar' for c in ('','-sources','-javadoc')]
        for name in names:(self.foundation/name).write_bytes(('checked '+name).encode())
        (self.root/'RELEASE-NOTES.md').write_bytes((self.foundation/'RELEASE-NOTES.md').read_bytes())
        shutil.copytree(ROOT/'.changes', self.root/'.changes')
        (self.foundation/'toolgate-contracts-1.2.3.pom').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            '<groupId>io.github.olo-labs</groupId><artifactId>toolgate-contracts</artifactId>'
            '<version>1.2.3</version><dependencies><dependency><groupId>example</groupId>'
            '<artifactId>serializer</artifactId><version>1.0</version></dependency></dependencies></project>',encoding='utf-8')
        (self.foundation/'toolgate-contracts-1.2.3.module').write_text(json.dumps({
            'component':{'group':'io.github.olo-labs','module':'toolgate-contracts','version':'1.2.3'}}),encoding='utf-8')
        self.foundation_checksums()
        for attribute,value in [('ROOT',self.root),('OUTPUT',self.output)]:
            mock=patch.object(shared,attribute,value);mock.start();self.addCleanup(mock.stop)
        environment=patch.dict(os.environ,{'GITHUB_REF':'refs/tags/v1.2.3',
            'GITHUB_REPOSITORY':REPO,'RELEASE_SHA':SHA,'GH_TOKEN':'fixture-token',
            'GITHUB_ACTOR':'actor','MAVEN_REPOSITORY_URL':'https://maven.example.invalid',
            'GITHUB_OUTPUT':str(self.root/'outputs.txt')},clear=True)
        environment.start();self.addCleanup(environment.stop)
        tagged.prepare_assets(dict.fromkeys(shared.WORKFLOWS,123))
        for component in shared.IMAGES:
            folder=self.output/component;folder.mkdir()
            (folder/'image.tar').write_bytes(('tested '+component).encode())
            (folder/'SHA256SUMS').write_text(shared.digest(folder/'image.tar')+'  image.tar\n',encoding='utf-8')

    def test_contributor_assets_preserve_foundation_documents_and_are_checksummed(self):
        tagged.verified_assets()
        self.assertEqual((self.assets/'RELEASE-NOTES.md').read_bytes(), (self.foundation/'RELEASE-NOTES.md').read_bytes())
        combined = (self.assets/'RELEASE-NOTES.combined.md').read_text(encoding='utf-8')
        self.assertIn('Rahul Nigam', combined)
        self.assertIn('Anupriya Srivastava', combined)
        manifest = json.loads((self.assets/'contributors.json').read_text(encoding='utf-8'))
        self.assertEqual(manifest['fragments'], notes.load(ROOT)[1])
        self.assertIn('RELEASE-NOTES.combined.md', tagged.verified_files(self.assets))

    def foundation_checksums(self):
        (self.foundation/'SHA256SUMS').write_text(''.join(f'{shared.digest(p)}  {p.name}\n'
            for p in sorted(self.foundation.iterdir()) if p.name!='SHA256SUMS'),encoding='utf-8')

    def test_tag_requires_exact_checked_version_and_not_a_branch(self):
        self.assertEqual(shared.coordinates(),(REPO,SHA,'olo-labs'))
        for ref in ('refs/tags/v1.2.4','refs/tags/v../1.2.3','refs/heads/main'):
            with self.subTest(ref=ref),patch.dict(os.environ,{'GITHUB_REF':ref}):
                with self.assertRaises(ValueError):tagged.version_tag()
        (self.root/'VERSION').write_text('not-semver\n',encoding='utf-8')
        with self.assertRaises(ValueError):tagged.version_tag()

    def test_lightweight_and_annotated_tags_must_resolve_to_checked_commit(self):
        for targets in ([{'type':'commit','sha':SHA}],
                        [{'type':'tag','sha':'b'*40},{'type':'commit','sha':SHA}]):
            with patch.object(shared,'api',side_effect=[{'object':t} for t in targets]):
                tagged.verify_tag_commit(REPO,SHA)
        for targets in ([{'type':'commit','sha':'c'*40}], [{'type':'tree','sha':SHA}],
                        [{'type':'tag','sha':'b'*40},{'type':'tag','sha':'b'*40}]):
            with patch.object(shared,'api',side_effect=[{'object':t} for t in targets]):
                with self.assertRaises(ValueError):tagged.verify_tag_commit(REPO,SHA)

    def test_unpublished_lookup_allows_only_an_explicit_404(self):
        with patch.object(shared,'run',side_effect=subprocess.CalledProcessError(1,['gh'],stderr='gh: Not Found (HTTP 404)')):
            tagged.ensure_unpublished(REPO)
        for error in (subprocess.CalledProcessError(1,['gh'],stderr='HTTP 401'),
                      subprocess.CalledProcessError(1,['gh'],stderr='connection failed')):
            with patch.object(shared,'run',side_effect=error):
                with self.assertRaises(subprocess.CalledProcessError):tagged.ensure_unpublished(REPO)
        with patch.object(shared,'run'):
            with self.assertRaisesRegex(ValueError,'already exists'):tagged.ensure_unpublished(REPO)

    def test_all_foundation_outputs_and_checksums_are_required(self):
        for name in ('sbom.cdx.json','toolgate-contracts-1.2.3.module','olo-toolgate-1.2.3.tgz'):
            path=self.foundation/name;original=path.read_bytes();path.unlink();self.foundation_checksums()
            with self.subTest(name=name),self.assertRaisesRegex(ValueError,'missing required'):tagged.verified_foundation(self.foundation)
            path.write_bytes(original)
        self.foundation_checksums()
        (self.foundation/'toolgate-contracts-1.2.3.jar').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError,'checksum'):tagged.verified_foundation(self.foundation)

    def test_unlisted_duplicate_and_unsafe_checksum_entries_are_rejected(self):
        checksums=self.foundation/'SHA256SUMS';original=checksums.read_text()
        for body in (original+original.splitlines()[0]+'\n',original+'a'*64+'  ../outside\n',original.split('\n',1)[1]):
            checksums.write_text(body,encoding='utf-8')
            with self.assertRaises(ValueError):tagged.verified_foundation(self.foundation)
        checksums.write_text(original,encoding='utf-8')
        (self.foundation/'unlisted.txt').write_text('not checked',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'must be checksummed'):tagged.verified_foundation(self.foundation)

    def test_prepared_assets_copy_all_checked_bytes_and_version(self):
        metadata=tagged.verified_assets()
        self.assertEqual(metadata['commit'],SHA)
        self.assertEqual(metadata['ciRuns'],dict.fromkeys(shared.WORKFLOWS,123))
        self.assertEqual((self.output/'MAVEN_VERSION').read_text(),'1.2.3\n')
        for name in tagged.verified_foundation(self.foundation):
            self.assertEqual((self.assets/name).read_bytes(),(self.foundation/name).read_bytes())

    def test_maven_rejects_asset_tampering_before_any_remote_command(self):
        (self.assets/'toolgate-contracts-1.2.3.jar').write_bytes(b'tampered')
        with patch.object(shared,'run') as run:
            with self.assertRaises(ValueError):tagged.publish_maven()
        run.assert_not_called()

    def test_maven_rejects_a_checksums_valid_but_wrong_coordinate(self):
        for name in ('toolgate-contracts-1.2.3.pom','toolgate-contracts-1.2.3.module'):
            path=self.foundation/name;original=path.read_bytes()
            path.write_bytes(original.replace(b'io.github.olo-labs',b'wrong.group'))
            self.foundation_checksums();(self.assets/name).write_bytes(path.read_bytes());tagged.checksum_assets()
            with self.subTest(name=name),patch.object(shared,'run') as run:
                with self.assertRaisesRegex(ValueError,'identity mismatch'):tagged.publish_maven()
            run.assert_not_called();path.write_bytes(original)
            self.foundation_checksums();(self.assets/name).write_bytes(original);tagged.checksum_assets()

    def test_maven_publication_only_uploads_verified_files_without_building_java(self):
        with patch.object(shared,'run') as run:tagged.publish_maven()
        command=run.call_args.args
        self.assertEqual(command[-1],'publishVerifiedPublicationToRemoteRepository')
        script=(self.output/'maven-publication/build.gradle.kts').read_text()
        self.assertNotIn('java-library',script)
        self.assertIn('destination.writeBytes',script)
        self.assertIn('"module"',script)
        self.assertIn('MAVEN_PASSWORD',script)
        self.assertNotIn('fixture-token',script)

    def test_central_receives_verified_tagged_artifacts_without_gradle(self):
        from tools.release import central
        with patch.dict(os.environ,{'MAVEN_REPOSITORY_URL':central.UPLOAD_URL}), \
             patch.object(central,'publish') as publish,patch.object(shared,'run') as run:
            tagged.publish_maven()
        publish.assert_called_once_with(self.assets,'1.2.3')
        run.assert_not_called()

    def publication_commands(self):
        commands=[]
        def run(*args,**kwargs):
            commands.append((args,kwargs))
            if args[:2]==('docker','inspect'):
                image=args[2].rsplit(':',1)[0]
                return subprocess.CompletedProcess(args,0,stdout=image+'@sha256:'+'d'*64+'\n')
            return subprocess.CompletedProcess(args,0,stdout='')
        with patch.object(shared,'run',side_effect=run),patch.object(tagged,'verify_tag_commit'),patch.object(tagged,'ensure_unpublished'):
            tagged.publish()
        return commands

    def test_all_checked_images_and_helm_publish_before_release_finalization(self):
        commands=self.publication_commands();arguments=[args for args,_ in commands]
        self.assertEqual(len([a for a in arguments if a[:2]==('docker','load')]),len(shared.IMAGES))
        self.assertEqual({a[2] for a in arguments if a[:2]==('docker','push')},
            {f'ghcr.io/olo-labs/{name}:1.2.3' for _,name in shared.IMAGES.values()})
        self.assertIn(('helm','push',str(self.assets/'olo-toolgate-1.2.3.tgz'),'oci://ghcr.io/olo-labs/charts'),arguments)
        self.assertFalse(any(a[:3]==('gh','release','create') for a in arguments))
        self.assertEqual(len((self.root/'outputs.txt').read_text().splitlines()),2*len(shared.IMAGES))
        for args,kwargs in commands:
            if '--password-stdin' in args:self.assertEqual(kwargs['input'],'fixture-token')
        with patch.object(shared,'run') as run,patch.object(tagged,'verify_tag_commit'),patch.object(tagged,'ensure_unpublished'):
            tagged.finalize()
        self.assertEqual(run.call_args.args[:4],('gh','release','create','v1.2.3'))
        self.assertIn('--verify-tag',run.call_args.args)
        self.assertNotIn('--clobber',run.call_args.args)

    def test_publication_fails_closed_and_logs_out_after_invalid_image_digest(self):
        commands=[]
        def run(*args,**kwargs):
            commands.append(args)
            return subprocess.CompletedProcess(args,0,stdout='malformed-digest')
        with patch.object(shared,'run',side_effect=run),patch.object(tagged,'verify_tag_commit'),patch.object(tagged,'ensure_unpublished'):
            with self.assertRaisesRegex(ValueError,'image digest'):tagged.publish()
        self.assertEqual(commands[-1],('docker','logout','ghcr.io'))
        self.assertFalse(any(a[:2]==('helm','push') for a in commands))

    def test_finalize_requires_all_image_digests_and_untampered_assets(self):
        with self.assertRaisesRegex(ValueError,'missing published'),patch.object(tagged,'verify_tag_commit'):
            tagged.finalize()
        self.publication_commands()
        (self.assets/'olo-toolgate-1.2.3.tgz').write_bytes(b'tampered')
        with patch.object(shared,'run') as run,patch.object(tagged,'verify_tag_commit'):
            with self.assertRaises(ValueError):tagged.finalize()
        run.assert_not_called()

    def test_same_run_tag_gate_cannot_be_substituted_by_main_or_failed_native(self):
        targets=('x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu','x86_64-pc-windows-msvc',
                 'aarch64-pc-windows-msvc','x86_64-apple-darwin','aarch64-apple-darwin')
        run=dict(head_sha=SHA,head_branch='v1.2.3',path='.github/workflows/ci.yml',event='push')
        jobs=[dict(name=name,status='completed',conclusion='success') for name in
              ['admission / admit','preflight / metadata','foundation / check','clients / public-bundle','images / verify']+
              [f'clients / native ({target})' for target in targets]]
        def api(path):return {'jobs':jobs} if '/jobs?' in path else run
        with patch.object(shared,'api',side_effect=api),patch.object(tagged,'verify_tag_commit'),patch.object(tagged,'ensure_unpublished'):
            self.assertEqual(shared.unified_run(REPO,SHA,123),dict.fromkeys(shared.WORKFLOWS,123))
            run['head_branch']='main'
            with self.assertRaises(ValueError):shared.unified_run(REPO,SHA,123)
            run['head_branch']='v1.2.3';jobs[-1]['conclusion']='failure'
            with self.assertRaises(ValueError):shared.unified_run(REPO,SHA,123)

    def test_wrong_tag_stops_preflight_before_builds_or_downloads(self):
        with patch.object(preflight,'ROOT',self.root),patch.dict(os.environ,{'GITHUB_REF':'refs/tags/v1.2.4'}), \
                patch.object(preflight.subprocess,'run') as run,redirect_stderr(io.StringIO()):
            self.assertEqual(preflight.main(),1)
        run.assert_not_called()

    def test_tag_prepare_cannot_use_the_legacy_multiple_run_fallback(self):
        with patch.object(shared,'run',return_value=subprocess.CompletedProcess(['git'],0,stdout=SHA+'\n')) as run:
            with self.assertRaisesRegex(ValueError,'single verified CI run'):shared.prepare()
        run.assert_called_once_with('git','rev-parse','HEAD',capture=True)


class UnifiedPublicationWorkflowTests(unittest.TestCase):
    def test_portal_and_standard_urls_are_routed_but_invalid_urls_fail(self):
        for url in ('https://maven.pkg.github.com/olo-labs/olo-toolgate','https://maven.example.invalid/releases',
                    'https://central.sonatype.com/api/v1/publisher/upload'):
            with patch.dict(os.environ,{'MAVEN_REPOSITORY_URL':url}):shared.validate_maven_repository()
        for url in ('https://central.sonatype.com','https://central.sonatype.com/api/v1/publisher/status','https://s01.oss.sonatype.org',
                    'http://maven.example.invalid','https://user:password@maven.example.invalid','https://maven.example.invalid?token=fixture',''):
            with self.subTest(url=url),patch.dict(os.environ,{'MAVEN_REPOSITORY_URL':url}),patch.object(shared,'run') as run:
                with self.assertRaises(ValueError):shared.validate_maven_repository()
            run.assert_not_called()

    def test_one_tag_entry_and_one_environment_preserve_all_attestations(self):
        workflows={p.stem:yaml.safe_load(p.read_text()) for p in (ROOT/'.github/workflows').glob('*.yml')}
        automatic=[name for name,doc in workflows.items() if 'push' in doc.get('on',{})]
        self.assertEqual(automatic,['ci'])
        self.assertEqual(set(workflows['tenant-qualification']['on']),{'workflow_dispatch'})
        self.assertNotIn('release-foundation',workflows)
        for name in ('control','gateway','quickstart'):
            self.assertNotIn('publish',workflows[name]['jobs'])
        self.assertEqual(workflows['development-release']['jobs']['publish']['uses'],'./.github/workflows/release-images.yml')
        job=workflows['release-images']['jobs']['publish']
        self.assertEqual(job['environment'],'development-release')
        steps=job['steps'];attest=[s for s in steps if s.get('uses','').startswith('actions/attest-build-provenance@')]
        # Marketplace publication belongs to its own repository.
        self.assertNotIn('publish-marketplace',workflows['development-release']['jobs'])
        self.assertEqual(len(attest),len(shared.IMAGES)+1)
        self.assertEqual({a['with'].get('subject-name') for a in attest if a['with'].get('push-to-registry')},
            {'${{ steps.push.outputs.'+c+'_image }}' for c in shared.IMAGES})
        self.assertEqual(steps[-1]['run'],'python tools/release/development.py finalize')
        self.assertTrue(all("refs/tags/" in a['if'] for a in attest))

    def test_publishers_can_run_when_any_sibling_publisher_fails(self):
        for name,prerequisite,expected in (
                ('development-release','prepare',{'publish'}),
                ('manual-development-release','build',{'publish','publish-typescript'})):
            jobs=yaml.safe_load((ROOT/'.github/workflows'/f'{name}.yml').read_text())['jobs']
            publishers={key for key in jobs if key.startswith('publish')}
            self.assertEqual(publishers,expected)
            for failed in publishers:
                # GitHub only considers each job's needs when a sibling fails.
                outcomes={prerequisite:'success', 'select':'success', failed:'failure'}
                for sibling in publishers-{failed}:
                    with self.subTest(workflow=name,failed=failed,sibling=sibling):
                        needs=jobs[sibling]['needs']
                        if isinstance(needs,str):needs=[needs]
                        self.assertEqual(needs, ['select',prerequisite] if name == 'manual-development-release' else [prerequisite])
                        self.assertTrue(all(outcomes.get(parent)=='success' for parent in needs))
                        self.assertNotIn('needs.',jobs[sibling]['if'])
                        body=yaml.safe_load((ROOT/jobs[sibling]['uses'][2:]).read_text())['jobs']['publish']
                        self.assertEqual(body['environment'],'development-release')
                        self.assertNotIn('needs',body)
                        self.assertFalse(jobs[sibling].get('continue-on-error',False))
                        self.assertTrue(all(not s.get('continue-on-error',False) for s in body['steps']))

    def test_external_maven_configuration_and_upload_are_isolated_in_shared_publisher(self):
        shared_job=yaml.safe_load((ROOT/'.github/workflows/release-maven.yml').read_text())['jobs']['publish']
        step=shared_job['steps'][-1]
        self.assertNotIn('MAVEN_REPOSITORY_URL',shared_job.get('env',{}))
        self.assertIn('MAVEN_REPOSITORY_URL',step['env'])
        self.assertTrue(step['run'].startswith('python tools/release/development.py validate-maven\n'))
        self.assertLess(step['run'].index('validate-maven'),step['run'].index('export MAVEN_PASSWORD'))
        self.assertIn('tools/release/maven.py',step['run'])
        self.assertIn('maven_version="$(cat build/development/MAVEN_VERSION)"',step['run'])
        self.assertIn('"-PcontractsPublicationVersion=$maven_version"',step['run'])
        self.assertNotIn('version.py --set',step['run'])
        for name in ('development-release','manual-development-release'):
            jobs=yaml.safe_load((ROOT/'.github/workflows'/f'{name}.yml').read_text())['jobs']
            self.assertNotIn('MAVEN_REPOSITORY_URL',json.dumps(jobs))
        other=yaml.safe_load((ROOT/'.github/workflows/release-images.yml').read_text())['jobs']['publish']
        self.assertNotIn('MAVEN_REPOSITORY_URL',json.dumps(other))

    def test_gradle_failures_are_confined_to_shared_maven_publisher(self):
        images=yaml.safe_load((ROOT/'.github/workflows/release-images.yml').read_text())['jobs']['publish']
        self.assertNotIn('./gradlew',json.dumps(images))
        self.assertFalse(any(s.get('uses','').startswith('actions/setup-java@') for s in images['steps']))
        maven=yaml.safe_load((ROOT/'.github/workflows/release-maven.yml').read_text())['jobs']['publish']
        self.assertIn('./gradlew',json.dumps(maven))
        self.assertNotIn('needs',maven)
        for name,prerequisite in (('development-release','prepare'),('manual-development-release','build')):
            jobs=yaml.safe_load((ROOT/'.github/workflows'/f'{name}.yml').read_text())['jobs']
            self.assertEqual(jobs['publish']['needs'], ['select',prerequisite] if name == 'manual-development-release' else prerequisite)
            self.assertNotIn('publish-maven',jobs['publish']['if'])

    def test_retained_publishers_can_consume_prepared_packages_and_images(self):
        for workflow,preparation,prefix,output in (
                ('development-release','prepare','verified-release','build/development'),
                ('manual-development-release','build','manual-development','build/manual-development')):
            jobs=yaml.safe_load((ROOT/'.github/workflows'/f'{workflow}.yml').read_text())['jobs']
            prepared=jobs[preparation]
            if 'uses' in prepared:
                called=yaml.safe_load((ROOT/prepared['uses'][2:]).read_text())['jobs'].values()
                prepared={'steps':[step for job in called for step in job.get('steps',[])]}
            uploads={s['with']['name']:s['with'] for s in prepared['steps']
                     if s.get('uses','').startswith('actions/upload-artifact@')}
            for publisher in ('release-images','release-maven'):
                body=yaml.safe_load((ROOT/'.github/workflows'/f'{publisher}.yml').read_text())['jobs']['publish']
                for step in body['steps']:
                    if not step.get('uses','').startswith('actions/download-artifact@'):continue
                    if 'artifact-ids' in step.get('with',{}):continue
                    download=dict(step['with'])
                    for field in ('name','path'):
                        if download[field].startswith('${{ inputs.manual'):
                            choices=re.findall(r"'([^']+)'",download[field])
                            download[field]=choices[0 if workflow=='manual-development-release' else 1]
                    with self.subTest(workflow=workflow,publisher=publisher,artifact=download['name']):
                        self.assertIn(download['name'],uploads)
                        self.assertTrue(download['path'].startswith(output))
                        self.assertEqual(uploads[download['name']]['if-no-files-found'],'error')
            image_body=yaml.safe_load((ROOT/jobs['publish']['uses'][2:]).read_text())['jobs']['publish']
            maven_body=yaml.safe_load((ROOT/'.github/workflows/release-maven.yml').read_text())['jobs']['publish']
            self.assertIn(prefix+'-images',json.dumps(image_body))
            self.assertNotIn(prefix+'-images',json.dumps(maven_body))


if __name__=='__main__':unittest.main()
