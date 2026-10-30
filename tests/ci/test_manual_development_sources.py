# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Manual releases pin any branch, stamp one version, and isolate publishing aliases."""
import base64
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import yaml
from tools.release import development_source as source, images, manual_development as manual

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a'*40


class ManualSourceTests(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, GITHUB_RUN_ID='123456', GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_NUMBER='42')
        env.start()
        self.addCleanup(env.stop)

    def select(self, branch='feature/test', commit='latest', status='ahead'):
        def api(path, **kwargs):
            if path.startswith('contents/version.json'):
                return {'content':base64.b64encode(b'{"version":"1.0.0","tag":"dev"}').decode()}
            if path.startswith('git/ref/'):
                return {'object':{'sha':SHA}}
            if path.startswith('compare/'):
                return {'status':status}
            return {'content':base64.b64encode(b'1.0.0-beta.7\n').decode()}
        with patch.object(source.prepare, 'api', side_effect=api):
            return source.select(branch, commit)

    def test_any_valid_branch_gets_one_pinned_version_without_product_allocation(self):
        for branch, version in [('main', '1.0.0-dev.42'), ('release/1.0.0-beta.7', '1.0.0-dev.42'),
                                ('feature/test', '1.0.0-dev-branch-feature-test.42'),
                                ('Bugfix/Customer_123', '1.0.0-dev-branch-bugfix-customer-123.42')]:
            with self.subTest(branch=branch), patch.object(source.prepare, 'create_tag') as reserve:
                info = self.select(branch)
            self.assertEqual(info['sourceCommit'], SHA)
            self.assertEqual({info[k] for k in ('version','displayVersion','libraryVersion','binaryVersion','dockerTag')},
                             {version})
            self.assertEqual(info['releaseTag'], 'v'+version)
            self.assertEqual(info['numericVersion'], '1.0.0.42')
            reserve.assert_not_called()

    def test_explicit_ancestor_is_pinned_and_unrelated_commit_rejected(self):
        self.assertEqual(self.select(commit='b'*40)['sourceCommit'], 'b'*40)
        for status in ('behind', 'diverged'):
            with self.assertRaisesRegex(ValueError, 'history'):
                self.select(commit='b'*40, status=status)
        for commit in ('HEAD', 'abc', '--help', 'A'*40):
            with self.assertRaises(ValueError):
                self.select(commit=commit)

    def test_branch_aliases_are_safe_and_retries_reuse_the_build_version(self):
        self.assertNotEqual(source.branch_key('feature/a'), source.branch_key('feature-a'))
        self.assertRegex(source.branch_key('Feature/Hello.World'), r'^[a-z0-9-]+$')
        first = self.select()
        with patch.dict(os.environ, GITHUB_RUN_ATTEMPT='2'):
            second = self.select()
        self.assertEqual(first['version'], second['version'])
        self.assertEqual(first['releaseTag'], second['releaseTag'])
        with patch.dict(os.environ, GITHUB_RUN_NUMBER='65536'), self.assertRaises(ValueError):
            self.select()

    def test_publish_rejects_changed_branch_version_commit_or_foreign_run(self):
        info = self.select()
        with patch.object(source, 'load', return_value=info):
            source.verify(info)
            for key, value in [('sourceBranch','main'), ('commit','b'*40), ('version','9.0.0')]:
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'differs'):
                    source.verify({**info, key:value})
            # Re-running failed jobs publishes the identity the first attempt selected.
            with patch.dict(os.environ, GITHUB_RUN_ATTEMPT='2'):
                source.verify(info)
            with patch.dict(os.environ, GITHUB_RUN_ID='654321'), self.assertRaisesRegex(ValueError, 'another'):
                source.verify(info)
        with patch.object(source, 'load', return_value={**info, 'runAttempt':'2'}), self.assertRaisesRegex(ValueError, 'another'):
            source.verify({**info, 'runAttempt':'2'})

    def test_feature_sharing_main_sha_never_pushes_shared_dev_and_stale_head_has_no_branch_alias(self):
        for current in (True, False):
            calls = []
            def run(*args, **kwargs):
                calls.append(args)
                value = json.dumps({'object':{'sha':SHA if current else 'b'*40}}) if args[0] == 'gh' else 'image@sha256:fixture'
                return subprocess.CompletedProcess(args, 0, stdout=value)
            with patch.dict(os.environ, DOCKERHUB_USERNAME='fixture', DOCKERHUB_TOKEN='fixture'):
                images.publish(run, 'owner/repo', SHA, 'owner', {'gateway':('local','gateway')}, 'unique',
                               source_branch='feature/test', branch_alias='dev-feature-test')
            pushes = [args[2] for args in calls if args[:2] == ('docker','push')]
            self.assertEqual(pushes, ['owner/gateway:unique']+(['owner/gateway:dev-feature-test'] if current else [])+['owner/gateway:dev-branch'])
            self.assertTrue(any('heads/feature%2Ftest' in args[-1] for args in calls if args[0] == 'gh'))

    def test_npm_publishes_exact_prepared_version_and_restricts_dev_alias(self):
        # The last case is a rerun: the version an earlier attempt published is deleted, then published again.
        for branch, current, published in [('main',True,False), ('feature/test',True,False), ('main',False,False), ('main',True,True)]:
            with tempfile.TemporaryDirectory() as directory:
                assets = Path(directory)
                info = self.select(branch)
                (assets/'development-release.json').write_text(json.dumps(info))
                content = json.dumps({'name':'@olo-labs/toolgate-contracts','version':info['version']}).encode()
                with tarfile.open(assets/('olo-labs-toolgate-contracts-'+info['version']+'.tgz'), 'w:gz') as archive:
                    entry = tarfile.TarInfo('package/package.json'); entry.size = len(content)
                    archive.addfile(entry, io.BytesIO(content))
                with patch.object(manual, 'ASSETS', assets), patch.object(source, 'load', return_value=info), \
                     patch.object(images, 'current_head', return_value=current), patch.object(manual, 'run') as run, \
                     patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo'):
                    versions = [[{'id':7,'name':info['version']}]] if published else [[{'id':6,'name':'0.0.1'}]]
                    run.side_effect = lambda *args, **kwargs: subprocess.CompletedProcess(args, 0, stdout=json.dumps(versions))
                    manual.publish_typescript()
                commands = [c.args for c in run.call_args_list]
                deletes = [c for c in commands if c[:4] == ('gh','api','-X','DELETE')]
                self.assertEqual(deletes, [('gh','api','-X','DELETE','orgs/owner/packages/npm/toolgate-contracts/versions/7')] if published else [])
                publish = next(c for c in commands if c[:2] == ('npm','publish'))
                self.assertLess(commands.index(deletes[0]) if deletes else -1, commands.index(publish))
                self.assertIn('--ignore-scripts', publish)
                self.assertIn('--tag', publish)
                tags = [c[4] for c in commands if c[:3] == ('npm','dist-tag','add')]
                self.assertEqual('dev' in tags, branch == 'main' and current)
                self.assertEqual(len(tags), (2 if branch == 'main' else 1) if current else 0)

    def test_real_stamp_overlay_synchronizes_native_package_lock_and_platform_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/'source'; root.mkdir()
            exact = {'VERSION','LICENSE','NOTICE.md','CHANGELOG.md','RELEASE-NOTES.md','Cargo.toml','Cargo.lock',
                     'package.json','build.gradle.kts','package-lock.json','apps/admin-ui/package.json',
                     'apps/browser-extension/manifest.json','deploy/helm/olo-toolgate/Chart.yaml',
                     'tools/contracts/version.py','tools/contracts/generate.py'}
            names = subprocess.check_output(['git','ls-files','-z'], cwd=ROOT).decode().split('\0')
            for name in names:
                if name and (name in exact or name.startswith(('packages/contracts/','config/initial/')) or name.endswith('/gradle-artifact.lockfile')):
                    path = root/name; path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT/name, path)
            for args in [('init','--quiet'), ('add','--all'), ('-c','user.name=Release test','-c','user.email=release@example.invalid','commit','--quiet','-m','fixture')]:
                subprocess.run(['git',*args], cwd=root, check=True, capture_output=True)
            commit = subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip()
            info = {**self.select(), 'sourceCommit':commit, 'commit':commit}
            destination = Path(temporary)/'overlay'
            source.overlay(root, destination, info)
            for name in ('VERSION','packages/contracts/VERSION'):
                self.assertEqual((destination/name).read_text().strip(), info['version'])
            lock = tomllib.loads((destination/'Cargo.lock').read_text())
            local = [p['version'] for p in lock['package'] if 'source' not in p]
            self.assertEqual(set(local), {info['version']})
            original = tomllib.loads((ROOT/'Cargo.lock').read_text())
            self.assertEqual([p for p in lock['package'] if 'source' in p],
                             [p for p in original['package'] if 'source' in p])
            npm = json.loads((destination/'package-lock.json').read_text())
            self.assertEqual({npm['packages'][p]['version'] for p in ('','apps/admin-ui','packages/contracts/typescript')}, {info['version']})
            extension = json.loads((destination/'apps/browser-extension/manifest.json').read_text())
            self.assertEqual(extension['version_name'], info['version'])
            self.assertEqual(extension['version'], '1.0.0.42')
            self.assertEqual(json.loads((destination/'development-source.json').read_text()), info)
            self.assertTrue((destination/'tools/release/manual_development.py').is_file())

    def test_manual_workflow_accepts_only_branch_and_stamps_every_builder(self):
        def workflow(name):
            return yaml.safe_load((ROOT/'.github/workflows'/f'{name}.yml').read_text())
        ci = workflow('ci')
        self.assertNotIn('release', ci['jobs'])
        self.assertTrue(all(level == 'read' for level in ci['permissions'].values()))
        manual_flow = workflow('manual-development-release')
        self.assertEqual(set(manual_flow['on']['workflow_dispatch']['inputs']), {'source_branch'})
        for job in manual_flow['jobs'].values():
            self.assertEqual(job['if'], "github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/heads/')")
        source_step=next(s for s in manual_flow['jobs']['select']['steps'] if s.get('id')=='source')
        self.assertEqual(source_step['env']['SOURCE_COMMIT'],'latest')
        self.assertEqual(source_step['env']['SOURCE_BRANCH'],'${{ inputs.source_branch }}')
        image_condition=workflow('release-images')['jobs']['publish']['if']
        self.assertIn("inputs.manual && github.event_name == 'workflow_dispatch'",image_condition)
        self.assertIn("!inputs.manual && (github.ref == 'refs/heads/main'",image_condition)
        self.assertEqual(workflow('release-typescript')['jobs']['publish']['if'],
                         "github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/heads/')")
        self.assertNotIn('secrets', manual_flow['jobs']['native'])
        for name in ('client','images'):
            for job_name, job in workflow(name)['jobs'].items():
                steps = job['steps']
                checkout = next((s for s in steps if s.get('uses','').startswith('actions/checkout@')), None)
                if checkout is None:
                    # The images release gate only reads stage results; it never builds source.
                    self.assertEqual((name, job_name), ('images', 'verify'))
                    continue
                self.assertIn('inputs.source_sha', checkout['with']['ref'])
                overlay = next(s for s in steps if s.get('with',{}).get('name') == 'manual-development-source')
                self.assertEqual(overlay['if'], '${{ inputs.development_source }}')
                self.assertFalse(checkout['with']['persist-credentials'])
        for name in ('release-images','release-typescript'):
            steps = workflow(name)['jobs']['publish']['steps']
            checkout = next(s for s in steps if s.get('uses','').startswith('actions/checkout@'))
            self.assertEqual(checkout['with']['ref'], '${{ github.sha }}')
            self.assertTrue(any(s.get('with',{}).get('artifact-ids') == '${{ inputs.selection_artifact_id }}' for s in steps))


if __name__ == '__main__':
    unittest.main()
