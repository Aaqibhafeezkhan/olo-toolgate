# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
from contextlib import redirect_stdout
import io
import json
import os
import unittest
from pathlib import Path
import tempfile
from unittest.mock import patch
from urllib.error import HTTPError
import yaml
from tools.ci.admission import decision, trigger_message, merged_pr_title, main


REPOSITORY = 'olo-labs/olo-toolgate'
MERGE_SHA = '6dd6ee460fb440615e0ca70c9468e3988c3831f6'
RC_TITLE = 'RC: Tool SDK: MRTR requestState in the Java runtime (M0 part 6, adapter A3)'


class AdmissionTests(unittest.TestCase):
    def run_admission(self, enabled, payload=None, event_name='push', ref='refs/heads/main'):
        with tempfile.TemporaryDirectory() as directory:
            event=Path(directory)/'event.json'
            output=Path(directory)/'output.txt'
            if payload is None:
                payload={'after':MERGE_SHA}
            event.write_text(json.dumps(payload),encoding='utf-8')
            environment={'GITHUB_EVENT_PATH':str(event),'GITHUB_EVENT_NAME':event_name,
                         'GITHUB_OUTPUT':str(output),'GITHUB_REPOSITORY':REPOSITORY,
                         'GITHUB_SHA':MERGE_SHA,'GITHUB_REF':ref}
            if enabled is not None:
                environment['CI_ENABLED']=enabled
            # Isolate credentials and refs from the launching process.
            with patch.dict(os.environ,environment,clear=True), redirect_stdout(io.StringIO()):
                main()
            return output.read_text(encoding='utf-8')

    def test_every_external_workflow_is_behind_admission(self):
        root=Path(__file__).resolve().parents[2]
        for path in (root/'.github/workflows').glob('*.yml'):
            document=yaml.safe_load(path.read_text())
            events=document.get('on',document.get(True,{}))
            if not any(event in events for event in ('push','pull_request','workflow_dispatch','workflow_run','schedule','issue_comment')):
                continue
            jobs=document['jobs']
            if path.name=='manual-development-release.yml':
                # Explicit operator-only publishing is exempt; automatic workflows stay gated.
                self.assertEqual(set(events),{'workflow_dispatch'})
                for job in jobs.values():
                    self.assertEqual(job['if'],"github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main'")
                self.assertNotIn('admission',jobs)
                continue
            self.assertEqual(jobs['admission']['uses'],'./.github/workflows/admission.yml')
            def gated(name,seen=None):
                if name=='admission':return True
                seen=(seen or set())|{name}
                needs=jobs[name].get('needs',[])
                if isinstance(needs,str):needs=[needs]
                return any(gated(parent,seen) for parent in needs if parent not in seen)
            for name,job in jobs.items():
                if name=='admission':continue
                with self.subTest(workflow=path.name,job=name):
                    self.assertTrue(gated(name),'Ungated job may consume quota')
                    if job.get('needs')=='admission':
                        self.assertIn("needs.admission.outputs.allow == 'true'",job['if'])

    def test_enabled_rc_title_is_admitted(self):
        self.assertEqual(decision(RC_TITLE), (True, 'RC: PR title admitted'))

    def test_workflows_use_only_pr_title_for_rc_admission(self):
        root=Path(__file__).resolve().parents[2]
        admission=yaml.safe_load((root/'.github/workflows/admission.yml').read_text())
        condition=admission['jobs']['admit']['if']
        self.assertIn("vars.TOOLGATE_CI_ENABLED == 'true'",condition)
        self.assertNotIn('github.event.pull_request',condition)
        self.assertNotIn('head_commit.message',condition)
        self.assertNotIn('inputs.comment',condition)
        self.assertIn("github.ref == 'refs/heads/main'",condition)
        self.assertNotIn('github.base_ref',condition)
        self.assertIn("github.event.workflow_run.head_branch == 'main'",condition)
        self.assertNotIn('(!inputs.main_only ||',condition)
        for name in ('admission','foundation','client','control','gateway','quickstart',
                     'development-release','release-foundation'):
            document=yaml.safe_load((root/f'.github/workflows/{name}.yml').read_text())
            with self.subTest(workflow=name):
                self.assertEqual(document['permissions']['pull-requests'],'read')
                events=document.get('on',document.get(True,{}))
                for event in ('workflow_dispatch','workflow_call'):
                    self.assertNotIn('comment',(events.get(event) or {}).get('inputs',{}))

    def test_development_release_only_accepts_main_runs(self):
        root=Path(__file__).resolve().parents[2]
        document=yaml.safe_load((root/'.github/workflows/development-release.yml').read_text())
        events=document.get('on',document.get(True,{}))
        self.assertEqual(events['workflow_run']['branches'],['main'])
        self.assertTrue(document['jobs']['admission']['with']['main_only'])
        self.assertIn("github.ref == 'refs/heads/main'",document['jobs']['publish']['if'])

    def test_no_workflow_is_triggered_by_pull_requests(self):
        root=Path(__file__).resolve().parents[2]
        for path in (root/'.github/workflows').glob('*.yml'):
            document=yaml.safe_load(path.read_text())
            events=document.get('on',document.get(True,{}))
            with self.subTest(workflow=path.name):
                self.assertNotIn('pull_request',events)
                self.assertNotIn('pull_request_target',events)

    def test_five_ci_workflows_only_trigger_for_main_pushes(self):
        root=Path(__file__).resolve().parents[2]
        for name in ('foundation','client','control','gateway','quickstart'):
            document=yaml.safe_load((root/f'.github/workflows/{name}.yml').read_text())
            events=document.get('on',document.get(True,{}))
            with self.subTest(workflow=name):
                expected={'push','workflow_dispatch'}
                if name=='client':expected.add('workflow_call')
                self.assertEqual(set(events),expected)
                self.assertEqual(events['push'],{'branches':['main']})
                self.assertTrue(document['jobs']['admission']['with']['main_only'])
                self.assertEqual(document['jobs']['admission']['if'],"github.ref == 'refs/heads/main'")

    def test_main_only_admission_is_opt_in_for_separate_release_workflows(self):
        root=Path(__file__).resolve().parents[2]
        document=yaml.safe_load((root/'.github/workflows/admission.yml').read_text())
        events=document.get('on',document.get(True,{}))
        self.assertEqual(events['workflow_call']['inputs']['main_only']['type'],'boolean')
        self.assertIs(events['workflow_call']['inputs']['main_only']['default'],False)
        release=yaml.safe_load((root/'.github/workflows/release-foundation.yml').read_text())
        events=release.get('on',release.get(True,{}))
        self.assertEqual(events['push'],{'tags':['v*']})
        self.assertNotIn('main_only',release['jobs']['admission'].get('with',{}))

    def test_main_entrypoint_requires_exact_enabled_variable(self):
        for enabled in ('true','false','True','',None):
            with self.subTest(enabled=enabled), patch('tools.ci.admission.merged_pr_title',return_value=RC_TITLE):
                self.assertEqual(self.run_admission(enabled),'allow='+str(enabled=='true').lower()+'\n')

    def test_disabled_push_does_not_query_github(self):
        with patch('tools.ci.admission.merged_pr_title') as lookup:
            self.assertEqual(self.run_admission('false',{'after':MERGE_SHA},'push'),'allow=false\n')
        lookup.assert_not_called()

    def test_manual_development_release_has_no_test_or_ci_dependencies(self):
        root=Path(__file__).resolve().parents[2]
        document=yaml.safe_load((root/'.github/workflows/manual-development-release.yml').read_text(encoding='utf-8'))
        self.assertEqual(set(document['jobs']),{'native','publish'})
        self.assertNotIn('needs',document['jobs']['native'])
        self.assertEqual(document['jobs']['publish']['needs'],'native')
        commands='\n'.join(step.get('run','') for job in document['jobs'].values() for step in job['steps'])
        for command in ('cargo test','cargo clippy','cargo fmt','--check','make check','pytest','unittest','tools/check.py','development.py prepare'):
            self.assertNotIn(command,commands)
        self.assertIn('-x test',commands)
        script=(root/'tools/release/manual_development.py').read_text(encoding='utf-8')
        self.assertIn("'--no-verify'",script)
        self.assertNotIn('workflow_runs',script)

    def test_disabled_and_non_rc_triggers_cannot_start_work(self):
        self.assertFalse(decision('RC: build', enabled=False)[0])
        for message in ('fix: build', 'rc: build', ' RC: build', '', None):
            self.assertFalse(decision(message)[0])

    def test_default_merge_message_is_admitted_by_actual_pr_heading(self):
        event={'after':MERGE_SHA,'head_commit':{'message':'Merge pull request #64 from olo-labs/sdk/m0-java-mrtr'}}
        with patch('tools.ci.admission.merged_pr_title',return_value=RC_TITLE) as lookup:
            self.assertEqual(self.run_admission('true',event,'push'),'allow=true\n')
        lookup.assert_called_once_with(REPOSITORY,MERGE_SHA)

    def test_commit_message_and_manual_comment_cannot_admit_non_rc_pr(self):
        for event_name,event in (
                ('push',{'after':MERGE_SHA,'head_commit':{'message':'RC: commit'}}),
                ('workflow_dispatch',{'inputs':{'comment':'RC: manual'}})):
            with self.subTest(event=event_name), patch('tools.ci.admission.merged_pr_title',return_value='fix: PR'):
                self.assertEqual(self.run_admission('true',event,event_name),'allow=false\n')

    def test_manual_dispatch_uses_the_selected_commits_pr_title(self):
        with patch('tools.ci.admission.merged_pr_title',return_value=RC_TITLE) as lookup:
            self.assertEqual(self.run_admission('true',{},'workflow_dispatch'),'allow=true\n')
        lookup.assert_called_once_with(REPOSITORY,MERGE_SHA)

    def test_feature_branch_pull_requests_cannot_be_admitted_even_with_rc_titles(self):
        for action in ('opened','synchronize','reopened','edited'):
            for ref in ('refs/pull/67/merge','refs/heads/main'):
                event={'action':action,'pull_request':{'title':RC_TITLE,'base':{'ref':'main'},
                       'head':{'ref':'sdk/m0-java-business-keys','sha':'a'*40}}}
                with self.subTest(action=action,ref=ref), patch('tools.ci.admission.merged_pr_title') as lookup:
                    self.assertEqual(self.run_admission('true',event,'pull_request',ref),'allow=false\n')
                    lookup.assert_not_called()

    def test_feature_branch_pushes_and_dispatches_do_not_query_github(self):
        for event_name in ('push','workflow_dispatch'):
            for ref in ('refs/heads/sdk/m0-java-business-keys','refs/heads/develop','refs/pull/67/merge',''):
                with self.subTest(event_name=event_name,ref=ref), patch('tools.ci.admission.merged_pr_title') as lookup:
                    self.assertEqual(self.run_admission('true',event_name=event_name,ref=ref),'allow=false\n')
                    lookup.assert_not_called()

    def test_tag_release_lookup_retains_the_separate_tag_path(self):
        with patch('tools.ci.admission.merged_pr_title',return_value=RC_TITLE) as lookup:
            self.assertEqual(self.run_admission('true',ref='refs/tags/v1.0.0'),'allow=true\n')
        lookup.assert_called_once_with(REPOSITORY,MERGE_SHA)

    def test_release_uses_upstream_sha_and_merged_pr_title(self):
        event={'workflow_run':{'event':'push','conclusion':'success','head_sha':MERGE_SHA,'head_branch':'main',
                              'head_repository':{'full_name':REPOSITORY}}}
        with patch.dict(os.environ,{'GITHUB_SHA':'a'*40,'GITHUB_REPOSITORY':REPOSITORY}), \
             patch('tools.ci.admission.merged_pr_title',return_value=RC_TITLE) as lookup:
            self.assertEqual(trigger_message(event,'workflow_run'),RC_TITLE)
        lookup.assert_called_once_with(REPOSITORY,MERGE_SHA)

    def test_failed_or_foreign_upstream_run_cannot_publish(self):
        for changes in ({'conclusion':'failure'},{'conclusion':'cancelled'},
                        {'conclusion':'skipped'},{'event':'pull_request'},
                        {'head_branch':'feature/rc'},{'head_branch':'master'},
                        {'head_repository':{'full_name':'another/repository'}}):
            event={'workflow_run':{'event':'push','conclusion':'success','head_sha':MERGE_SHA,'head_branch':'main',
                                  'head_repository':{'full_name':REPOSITORY},**changes}}
            with self.subTest(changes=changes), patch.dict(os.environ,{'GITHUB_REPOSITORY':REPOSITORY}), \
                 patch('tools.ci.admission.merged_pr_title') as lookup:
                self.assertEqual(trigger_message(event,'workflow_run'),'')
                lookup.assert_not_called()

    def test_rc_prs_targeting_any_branch_are_rejected(self):
        for branch in ('main','master','develop','feature/rc',''):
            event={'pull_request':{'title':RC_TITLE,'base':{'ref':branch}}}
            with self.subTest(branch=branch), patch('tools.ci.admission.merged_pr_title') as lookup:
                self.assertEqual(self.run_admission('true',event,'pull_request'),'allow=false\n')
                lookup.assert_not_called()

    def pr_fixture(self, **changes):
        return {'title':RC_TITLE,'merged_at':'2026-10-11T18:16:27Z',
                'merge_commit_sha':MERGE_SHA,
                'base':{'ref':'main','repo':{'full_name':REPOSITORY}},**changes}

    def mock_pr_response(self, pages):
        return [io.BytesIO(json.dumps(page).encode('utf-8')) for page in pages]

    def test_merge_squash_and_rebase_commits_resolve_their_actual_pr(self):
        for sha in (MERGE_SHA,'a'*40,'b'*40):
            pr=self.pr_fixture(merge_commit_sha=sha)
            with self.subTest(sha=sha), patch.dict(os.environ,{'GH_TOKEN':'test-token'}), \
                 patch('tools.ci.admission.urlopen',side_effect=self.mock_pr_response([[pr]])) as fetch:
                self.assertEqual(merged_pr_title(REPOSITORY,sha),RC_TITLE)
                request=fetch.call_args.args[0]
                self.assertIn(f'/commits/{sha}/pulls?',request.full_url)
                self.assertEqual(request.get_header('Authorization'),'Bearer test-token')

    def test_open_unrelated_and_foreign_prs_cannot_admit_a_push(self):
        excluded=[self.pr_fixture(merged_at=None),
                  self.pr_fixture(merge_commit_sha='a'*40),
                  self.pr_fixture(base={'ref':'other','repo':{'full_name':REPOSITORY}}),
                  self.pr_fixture(base={'ref':'main','repo':{'full_name':'another/repository'}})]
        with patch('tools.ci.admission.urlopen',side_effect=self.mock_pr_response([excluded])):
            self.assertEqual(merged_pr_title(REPOSITORY,MERGE_SHA),'')

    def test_github_token_fallback_preserves_gh_token_precedence(self):
        for environment, expected in (
                ({'GITHUB_TOKEN':'github-token'}, 'Bearer github-token'),
                ({'GITHUB_TOKEN':' github-token '}, 'Bearer github-token'),
                ({'GH_TOKEN':'gh-token','GITHUB_TOKEN':'github-token'}, 'Bearer gh-token'),
                ({'GH_TOKEN':' ','GITHUB_TOKEN':'github-token'}, 'Bearer github-token'),
                ({}, None),
                ({'GITHUB_TOKEN':' '}, None)):
            with self.subTest(environment=environment), patch.dict(os.environ,environment,clear=True), \
                 patch('tools.ci.admission.urlopen',side_effect=self.mock_pr_response([[]])) as fetch:
                self.assertEqual(merged_pr_title(REPOSITORY,MERGE_SHA),'')
                self.assertEqual(fetch.call_args.args[0].get_header('Authorization'),expected)

    def test_direct_rc_commit_without_a_merged_pr_is_rejected(self):
        with patch('tools.ci.admission.urlopen',side_effect=self.mock_pr_response([[]])):
            self.assertFalse(decision(merged_pr_title(REPOSITORY,MERGE_SHA))[0])

    def test_associated_pr_lookup_follows_pagination(self):
        pages=[[self.pr_fixture(merged_at=None)]*100,[self.pr_fixture()]]
        with patch('tools.ci.admission.urlopen',side_effect=self.mock_pr_response(pages)) as fetch:
            self.assertEqual(merged_pr_title(REPOSITORY,MERGE_SHA),RC_TITLE)
        self.assertEqual(fetch.call_count,2)
        self.assertIn('page=2',fetch.call_args.args[0].full_url)

    def test_invalid_repository_or_sha_cannot_query_github(self):
        with patch('tools.ci.admission.urlopen') as fetch:
            for repository,sha in (('',MERGE_SHA),(REPOSITORY,'bad'),
                                   ('../elsewhere',MERGE_SHA),(REPOSITORY,None)):
                self.assertEqual(merged_pr_title(repository,sha),'')
        fetch.assert_not_called()

    def test_github_lookup_failure_cannot_admit_a_push(self):
        error=HTTPError('https://api.github.com/',403,'Forbidden',{},None)
        with patch('tools.ci.admission.urlopen',side_effect=error), \
             self.assertRaises(HTTPError):
            self.run_admission('true',{'after':MERGE_SHA},'push')
