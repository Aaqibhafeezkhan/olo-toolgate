# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shared image publication preserves commit identity and the current-main alias."""
import json
import os
import subprocess
import unittest
from unittest.mock import Mock, patch
from tools.release import images


class ImagePublicationTests(unittest.TestCase):
    def publish(self,current=True,fail=False,source_branch=None):
        calls=[]
        def run(*args,**kwargs):
            calls.append((args,kwargs))
            if args[:2]==('gh','api'):
                return subprocess.CompletedProcess(args,0,stdout=json.dumps({'object':{'sha':'a'*40 if current else 'b'*40}}))
            if args[:2]==('docker','push') and fail:
                raise subprocess.CalledProcessError(1,args)
            return subprocess.CompletedProcess(args,0,stdout='namespace/gateway@sha256:checked')
        prepare=Mock()
        with patch.dict(os.environ,{'DOCKERHUB_USERNAME':'publisher','DOCKERHUB_TOKEN':'fixture-token'}):
            result=images.publish(run,'olo-labs/olo-toolgate','a'*40,'namespace',
                {'gateway':('gateway:prepared','gateway')},'dev-run',aliases=('dev-commit',),prepare=prepare,
                source_branch=source_branch)
        return result,calls,prepare

    def test_current_main_publishes_exact_image_and_both_development_aliases(self):
        result,calls,prepare=self.publish()
        self.assertEqual(result,{'gateway':'namespace/gateway@sha256:checked'})
        pushes=[args[2] for args,_ in calls if args[:2]==('docker','push')]
        self.assertEqual(pushes,['namespace/gateway:dev-run','namespace/gateway:dev-commit','namespace/gateway:dev'])
        prepare.assert_called_once_with('gateway')
        login=next((args,kwargs) for args,kwargs in calls if args[:2]==('docker','login'))
        self.assertNotIn('fixture-token',login[0])
        self.assertEqual(login[1]['input'],'fixture-token')
        self.assertEqual(calls[-1][0],('docker','logout'))

    def test_older_commit_does_not_overwrite_current_main_alias(self):
        _,calls,_=self.publish(current=False)
        self.assertFalse(any(args==('docker','push','namespace/gateway:dev') for args,_ in calls))

    def test_branch_channel_follows_completed_non_main_builds_even_after_branch_advances(self):
        for branch in ('main','feature/test','release/1.0.0-beta.1'):
            for current in (True,False):
                with self.subTest(branch=branch,current=current):
                    _,calls,_=self.publish(current=current,source_branch=branch)
                    pushes=[args[2] for args,_ in calls if args[:2]==('docker','push')]
                    self.assertEqual('namespace/gateway:dev-branch' in pushes,branch=='feature/test')
                    self.assertEqual('namespace/gateway:dev' in pushes,current and branch=='main')

    def test_push_failure_still_logs_out(self):
        run=Mock(side_effect=[subprocess.CompletedProcess([],0,stdout=json.dumps({'object':{'sha':'a'*40}})),
            subprocess.CompletedProcess([],0),subprocess.CompletedProcess([],0),subprocess.CalledProcessError(1,[]),
            subprocess.CompletedProcess([],0)])
        with patch.dict(os.environ,{'DOCKERHUB_USERNAME':'publisher','DOCKERHUB_TOKEN':'fixture-token'}):
            with self.assertRaises(subprocess.CalledProcessError):
                images.publish(run,'olo-labs/olo-toolgate','a'*40,'namespace',{'gateway':('local','gateway')},'dev-run')
        self.assertEqual(run.call_args.args,('docker','logout'))
