# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise interrupted draft uploads without replacing published release assets."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from tools.release import github_release


class GithubReleaseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.folder = Path(temporary.name)
        (self.folder/'package.zip').write_bytes(b'checked package')
        (self.folder/'SHA256SUMS').write_bytes(b'checksum manifest')
        self.current = None
        self.remote = {}
        self.calls = []
        self.fail_upload = False
        self.fail_create = False
        self.hidden_listings = 0

    def command(self, *args, capture=False):
        self.calls.append(args)
        result = ''
        if args[:2] == ('gh','api'):
            if '/releases/tags/' in args[2]:
                if self.current is None or self.current['draft']:
                    raise subprocess.CalledProcessError(1,args,output='{"status":"404"}')
                result = json.dumps(self.current)
            elif '/releases?' in args[2]:
                if self.current and self.hidden_listings:
                    self.hidden_listings -= 1
                    result = json.dumps([[]])
                else:
                    result = json.dumps([[self.current] if self.current else []])
            else:
                result = json.dumps([list(self.remote.values())])
        elif args[:3] == ('gh','release','create'):
            self.current = {'id':1,'tag_name':'dev-test','target_commitish':'a'*40,'draft':True}
            if self.fail_create:
                raise subprocess.CalledProcessError(1,args)
        elif args[:3] == ('gh','release','upload'):
            path = Path(args[4])
            self.remote[path.name] = {'name':path.name,'size':path.stat().st_size,
                'digest':'sha256:'+hashlib.sha256(path.read_bytes()).hexdigest(),'state':'uploaded'}
            if self.fail_upload:
                raise subprocess.CalledProcessError(1,args)
        elif args[:3] == ('gh','release','edit'):
            self.current['draft'] = False
        return subprocess.CompletedProcess(args,0,stdout=result)

    def publish(self):
        github_release.publish(self.command,'olo-labs/olo-toolgate','dev-test','a'*40,
                               self.folder,'development','notes.md')

    def test_create_draft_upload_each_file_verify_then_publish(self):
        self.publish()
        create = next(call for call in self.calls if call[:3] == ('gh','release','create'))
        self.assertIn('--draft',create)
        self.assertNotIn(str(self.folder/'package.zip'),create)
        uploads = [call for call in self.calls if call[:3] == ('gh','release','upload')]
        self.assertEqual(len(uploads),2)
        self.assertFalse(self.current['draft'])
        self.assertEqual(self.calls[-1][:3],('gh','release','edit'))

    def test_lost_create_or_upload_response_is_reconciled_without_duplicate_upload(self):
        self.fail_create = True
        self.fail_upload = True
        self.publish()
        self.assertEqual(sum(call[:3] == ('gh','release','upload') for call in self.calls),2)
        self.assertFalse(self.current['draft'])

    def test_resume_draft_skips_existing_identical_files(self):
        self.current = {'id':1,'tag_name':'dev-test','target_commitish':'a'*40,'draft':True}
        self.command('gh','release','upload','dev-test',str(self.folder/'package.zip'))
        self.calls.clear()
        self.publish()
        uploads = [call for call in self.calls if call[:3] == ('gh','release','upload')]
        self.assertEqual(len(uploads),1)
        self.assertEqual(Path(uploads[0][4]).name,'SHA256SUMS')

    def test_completed_retry_does_not_mutate_published_release(self):
        self.publish()
        self.calls.clear()
        self.publish()
        self.assertTrue(all(call[:2] == ('gh','api') for call in self.calls))

    def test_conflicting_asset_or_commit_stops_before_writes(self):
        self.publish()
        for change in ('digest','commit'):
            original = json.loads(json.dumps([self.current,self.remote]))
            if change == 'digest':self.remote['package.zip']['digest'] = 'sha256:wrong'
            else:self.current['target_commitish'] = 'b'*40
            self.calls.clear()
            with self.assertRaises(ValueError):self.publish()
            self.assertTrue(all(call[:2] == ('gh','api') for call in self.calls))
            self.current,self.remote = original

    def test_upload_that_never_reaches_server_is_retried_then_fails_as_draft(self):
        def command(*args,**kwargs):
            if args[:3] == ('gh','release','upload'):
                raise subprocess.CalledProcessError(1,args)
            return self.command(*args,**kwargs)
        with patch.object(github_release.time,'sleep') as sleep:
            with self.assertRaises(subprocess.CalledProcessError):
                github_release.publish(command,'olo-labs/olo-toolgate','dev-test','a'*40,
                                       self.folder,'development','notes.md')
        self.assertTrue(self.current['draft'])
        self.assertEqual(sleep.call_count,2)

    def test_forbidden_api_response_is_not_treated_as_absent_release(self):
        def denied(*args,**kwargs):
            raise subprocess.CalledProcessError(1,args,output='{"status":"403"}')
        with self.assertRaises(subprocess.CalledProcessError):
            github_release.publish(denied,'olo-labs/olo-toolgate','dev-test','a'*40,
                                   self.folder,'development','notes.md')

    def test_created_draft_visibility_is_polled_without_recreating(self):
        for lost_response in (False, True):
            with self.subTest(lost_response=lost_response):
                self.current = None
                self.remote.clear()
                self.calls.clear()
                self.fail_create = lost_response
                self.hidden_listings = 3
                with patch.object(github_release.time,'sleep') as sleep:
                    self.publish()
                self.assertFalse(self.current['draft'])
                self.assertEqual(sum(call[:3] == ('gh','release','create') for call in self.calls),1)
                self.assertEqual(sum(call[:3] == ('gh','release','upload') for call in self.calls),2)
                self.assertEqual([call.args[0] for call in sleep.call_args_list],[1,2,4])

    def test_created_draft_visibility_timeout_leaves_draft_unpublished(self):
        self.hidden_listings = 100
        with patch.object(github_release.time,'sleep') as sleep:
            with self.assertRaisesRegex(ValueError,'Created draft release did not become visible'):
                self.publish()
        self.assertTrue(self.current['draft'])
        self.assertFalse(self.remote)
        self.assertFalse(any(call[:3] == ('gh','release','edit') for call in self.calls))
        self.assertEqual([call.args[0] for call in sleep.call_args_list],[1,2,4,8,8])
        self.assertEqual(sum(call[:3] == ('gh','release','create') for call in self.calls),1)

    def test_failed_create_with_no_visible_draft_preserves_original_error(self):
        self.fail_create = True
        self.hidden_listings = 100
        with patch.object(github_release.time,'sleep'):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                self.publish()
        self.assertEqual(raised.exception.cmd[:3],('gh','release','create'))
        self.assertTrue(self.current['draft'])
        self.assertFalse(self.remote)
        self.assertEqual(sum(call[:3] == ('gh','release','create') for call in self.calls),1)

    def test_forbidden_post_create_lookup_is_not_retried(self):
        def command(*args,**kwargs):
            if self.current and args[:2] == ('gh','api'):
                raise subprocess.CalledProcessError(1,args,output='{"status":"403"}')
            return self.command(*args,**kwargs)
        with patch.object(github_release.time,'sleep') as sleep:
            with self.assertRaises(subprocess.CalledProcessError):
                github_release.publish(command,'olo-labs/olo-toolgate','dev-test','a'*40,
                                       self.folder,'development','notes.md')
        sleep.assert_not_called()
        self.assertTrue(self.current['draft'])
        self.assertFalse(self.remote)

    def test_conflicting_post_create_identity_is_rejected_without_retry(self):
        def command(*args,**kwargs):
            result = self.command(*args,**kwargs)
            if args[:3] == ('gh','release','create'):
                self.current['target_commitish'] = 'b'*40
            return result
        with patch.object(github_release.time,'sleep') as sleep:
            with self.assertRaisesRegex(ValueError,'commit or tag mismatch'):
                github_release.publish(command,'olo-labs/olo-toolgate','dev-test','a'*40,
                                       self.folder,'development','notes.md')
        sleep.assert_not_called()
        self.assertTrue(self.current['draft'])
        self.assertFalse(self.remote)
