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
import yaml
from tools.ci.admission import decision, trigger_message, main


class AdmissionTests(unittest.TestCase):
    def run_pr_admission(self, enabled):
        with tempfile.TemporaryDirectory() as directory:
            event=Path(directory)/'event.json'
            output=Path(directory)/'output.txt'
            event.write_text(json.dumps({'pull_request':{'title':'RC: validate','head':{'sha':'a'*40}}}),encoding='utf-8')
            environment={'GITHUB_EVENT_PATH':str(event),'GITHUB_EVENT_NAME':'pull_request','GITHUB_OUTPUT':str(output)}
            if enabled is not None:
                environment['CI_ENABLED']=enabled
            # No GitHub API credentials or repository name: PR admission is local.
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

    def test_enabled_rc_trigger_is_admitted(self):
        self.assertEqual(decision('RC: build'), (True, 'RC: trigger admitted'))

    def test_pr_workflows_observe_title_edits(self):
        root=Path(__file__).resolve().parents[2]
        for path in (root/'.github/workflows').glob('*.yml'):
            document=yaml.safe_load(path.read_text())
            events=document.get('on',document.get(True,{}))
            if 'pull_request' in events:
                with self.subTest(workflow=path.name):
                    self.assertTrue({'opened','synchronize','reopened','edited'}.issubset(events['pull_request']['types']))

    def test_five_ci_workflows_only_trigger_for_main_pushes_and_pr_targets(self):
        root=Path(__file__).resolve().parents[2]
        for name in ('foundation','client','control','gateway','quickstart'):
            document=yaml.safe_load((root/f'.github/workflows/{name}.yml').read_text())
            events=document.get('on',document.get(True,{}))
            with self.subTest(workflow=name):
                expected={'push','pull_request','workflow_dispatch'}
                if name=='client':expected.add('workflow_call')
                self.assertEqual(set(events),expected)
                self.assertEqual(events['push'],{'branches':['main']})
                self.assertEqual(events['pull_request']['branches'],['main'])
                self.assertTrue(document['jobs']['admission']['with']['main_only'])

    def test_main_only_admission_is_opt_in_for_separate_release_workflows(self):
        root=Path(__file__).resolve().parents[2]
        document=yaml.safe_load((root/'.github/workflows/admission.yml').read_text())
        events=document.get('on',document.get(True,{}))
        self.assertEqual(events['workflow_call']['inputs']['main_only']['type'],'boolean')
        self.assertIs(events['workflow_call']['inputs']['main_only']['default'],False)
        release=yaml.safe_load((root/'.github/workflows/release-foundation.yml').read_text())
        events=release.get('on',release.get(True,{}))
        self.assertEqual(events['push'],{'tags':['v*']})
        self.assertNotIn('main_only',release['jobs']['admission']['with'])

    def test_pr_entrypoint_requires_exact_enabled_variable_without_api_credentials(self):
        for enabled in ('true','false','True','',None):
            with self.subTest(enabled=enabled):
                self.assertEqual(self.run_pr_admission(enabled),'allow='+str(enabled=='true').lower()+'\n')

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

    def test_push_uses_head_commit_not_an_older_commit(self):
        event={'head_commit':{'message':'fix: current'},'commits':[{'message':'RC: previous'}]}
        self.assertEqual(trigger_message(event,'push'),'fix: current')

    def test_pr_requires_only_rc_title_regardless_of_head_commit(self):
        for head in ({'sha':'a'*40, 'message':'fix: head'}, {}, None):
            with self.subTest(head=head):
                event={'pull_request':{'title':'RC: test','head':head}}
                message=trigger_message(event,'pull_request')
                self.assertEqual(message,'RC: test')
                self.assertTrue(decision(message)[0])

    def test_non_rc_pr_title_cannot_be_admitted_by_an_rc_commit(self):
        for title in ('fix: test','rc: test',' RC: test','',None,42):
            with self.subTest(title=title):
                event={'pull_request':{'title':title,'head':{'message':'RC: head'}},'head_commit':{'message':'RC: head'}}
                self.assertFalse(decision(trigger_message(event,'pull_request'))[0])
        self.assertFalse(decision(trigger_message({'pull_request':{}},'pull_request'))[0])

    def test_pr_title_edit_uses_current_title(self):
        event={'action':'edited','changes':{'title':{'from':'fix: test'}},'pull_request':{'title':'RC: test'}}
        self.assertTrue(decision(trigger_message(event,'pull_request'))[0])
        event['changes']['title']['from']='RC: test'
        event['pull_request']['title']='fix: test'
        self.assertFalse(decision(trigger_message(event,'pull_request'))[0])

    def test_manual_and_release_events_cannot_bypass_prefix(self):
        self.assertEqual(trigger_message({'inputs':{'comment':'RC: manual'}},'workflow_dispatch'),'RC: manual')
        event={'workflow_run':{'event':'push','conclusion':'success','head_commit':{'message':'RC: release'}}}
        self.assertEqual(trigger_message(event,'workflow_run'),'RC: release')
        event['workflow_run']['conclusion']='cancelled'
        self.assertEqual(trigger_message(event,'workflow_run'),'')
