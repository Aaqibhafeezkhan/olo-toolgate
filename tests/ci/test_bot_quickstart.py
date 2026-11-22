# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Demo selection, credential preservation and readiness across multiple bots."""
from copy import deepcopy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from bot import quickstart as setup
from tools.admin.control import Refused

ROOT = Path(__file__).resolve().parents[2]


class BotSelectionTests(unittest.TestCase):
    def test_catalog_contains_every_configuration_with_both_standalone_entrypoints(self):
        rows = setup.catalog()
        self.assertEqual({folder.name for folder in (ROOT/'bot/configurations').iterdir() if folder.is_dir()},
                         {row['id'] for row in rows})
        for row in rows:
            folder = ROOT/'bot/configurations'/row['id']
            profile = json.loads((folder/'bot.json').read_text())
            self.assertEqual(profile['workflows']['workflows'][0]['id'], row['workflowId'])
            for suffix in ('bat', 'sh'):
                self.assertIn(row['id'], (folder/'quickstart'/('configure.'+suffix)).read_text())

    def test_blank_and_zero_select_no_bots_and_comma_selection_preserves_both(self):
        rows = setup.catalog()
        for answer in ('', ' ', '0'): self.assertEqual([], setup.selection(answer, rows))
        self.assertEqual(['ollama-summary', 'multiagent-incident'],
                         [row['id'] for row in setup.selection('2,3', rows)])
        self.assertEqual(['ollama-summary', 'multiagent-incident'],
                         [row['id'] for row in setup.selection('2, 3,2', rows)])
        for answer in ('0,2', '11', '-1', '2;3', '2,,3', '9,10'):
            with self.subTest(answer=answer), self.assertRaises(Refused): setup.selection(answer, rows)

    def test_unattended_default_does_not_prompt(self):
        with patch.object(sys.stdin, 'isatty', return_value=False), patch('builtins.input') as prompt:
            self.assertEqual([], setup.choose())
            prompt.assert_not_called()

    def test_catalog_paths_cannot_escape_the_managed_file_root(self):
        for value in ('/etc/passwd', '../private', 'a/../private', 'C:/private', 'a\\private', 'a//private'):
            with self.subTest(value=value), self.assertRaises(Refused): setup.relative_path(value)

    def test_standalone_help_needs_no_docker_or_credentials(self):
        result = subprocess.run([sys.executable, str(ROOT/'bot/quickstart.py'), '--help'],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--token-file', result.stdout)

    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Windows PowerShell required')
    def test_windows_bot_wrapper_forwards_python_options_from_any_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            wrapper = ROOT/'bot/configurations/log-triage/quickstart/configure.bat'
            result = subprocess.run(['cmd', '/c', str(wrapper), '--help'], cwd=temporary,
                                    capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--token-file', result.stdout)

    def test_real_bundles_must_match_inventory_bytes_version_source_and_bot(self):
        from bot.build import build
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); profiles = []
            for row in setup.selection('2,3', setup.catalog()):
                profiles += build(ROOT/'bot/configurations'/row['id'], folder, '1.0.0-demo.test', 'a'*40, 'test', {}, None)
            index = dict(format='olo-profiles', version='1.0.0-demo.test', sourceCommit='a'*40, profiles=profiles)
            (folder/'olo-profiles-1.0.0-demo.test.json').write_text(json.dumps(index))
            for row in setup.selection('2,3', setup.catalog()):
                raw = setup.load_bundle(folder, row, version=index['version'], commit=index['sourceCommit'])
                self.assertEqual(row['workflowId'], setup.verify(raw)['workflowId'])
            with self.assertRaises(Refused): setup.load_bundle(folder, setup.catalog()[1], commit='b'*40)
            path = folder/profiles[0]['filename']; path.write_bytes(path.read_bytes()+b'tampered')
            with self.assertRaisesRegex(Refused, 'checksum'): setup.load_bundle(folder, setup.catalog()[1])


class CredentialTests(unittest.TestCase):
    def stack(self, folder):
        def private(path, data): path.write_text(data, encoding='utf-8')
        return SimpleNamespace(HERE=folder, private=private, secure_directory=lambda path: path.mkdir(parents=True, exist_ok=True))

    def test_empty_answers_leave_saved_credentials_byte_for_byte_and_never_prompt_unattended(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); path = folder/'credentials/observability.json'
            path.parent.mkdir(); original = '{"langsmith": {"apiKey": "saved-private", "project": "existing"}}\n'
            path.write_text(original)
            stack = self.stack(folder)
            with patch.object(setup.getpass, 'getpass', return_value=''), patch('builtins.input', return_value=''):
                updates, monitoring = setup.prompt_credentials(stack)
            self.assertEqual({}, updates); self.assertTrue(monitoring)
            self.assertEqual(original, path.read_text())
            with patch.object(setup.getpass, 'getpass') as prompt:
                self.assertEqual(({}, False), setup.prompt_credentials(stack, interactive=False))
                prompt.assert_not_called()

    def test_new_keys_replace_only_the_answered_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); path = folder/'credentials/observability.json'; path.parent.mkdir()
            path.write_text(json.dumps({'langsmith': {'apiKey': 'old-private', 'project': 'saved-project'}}))
            with patch.object(setup.getpass, 'getpass', side_effect=['new-router', 'new-smith', '', '', '']),\
                    patch('builtins.input', return_value=''):
                updates, monitoring = setup.prompt_credentials(self.stack(folder))
            self.assertEqual({'openrouter-key': 'new-router'}, updates); self.assertTrue(monitoring)
            self.assertEqual({'langsmith': {'apiKey': 'new-smith', 'project': 'saved-project'}}, json.loads(path.read_text()))
            self.assertNotIn('new-router', path.read_text())

    def test_incomplete_credentials_do_not_replace_saved_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary); path = folder/'credentials/observability.json'; path.parent.mkdir()
            path.write_text('{}\n')
            with patch.object(setup.getpass, 'getpass', side_effect=['', '', 'public-only', '', '']),\
                    patch('builtins.input', return_value=''), self.assertRaises(Refused):
                setup.prompt_credentials(self.stack(folder))
            self.assertEqual('{}\n', path.read_text())


class ReadinessTests(unittest.TestCase):
    def assignment(self):
        return dict(metadata={'generation': 3}, status=dict(observedGeneration=3,
            instances=dict(desired=1, running=1, pending=0, starting=0, failed=0, unsupported=0, stale=0),
            conditions=[dict(type='Available', status='True')]))

    def test_stale_failed_or_zero_target_watchers_are_not_ready(self):
        value = self.assignment(); self.assertTrue(setup.assignment_ready(value))
        for field, changed in [('failed', 1), ('running', 0), ('desired', 0), ('stale', 1)]:
            value = self.assignment(); value['status']['instances'][field] = changed
            self.assertFalse(setup.assignment_ready(value))
        value = self.assignment(); value['status']['observedGeneration'] = 2
        self.assertFalse(setup.assignment_ready(value))
        value = self.assignment(); value['status']['conditions'] = []
        self.assertFalse(setup.assignment_ready(value))

    def test_every_selected_bundle_is_validated_before_any_mutation_and_failures_stop_later_bots(self):
        rows = setup.selection('2,3', setup.catalog()); stack = Mock()
        with patch.object(setup, 'load_bundle', side_effect=[b'one', Refused('checksum')]),\
                patch.object(setup, 'configure_bot') as configure:
            with self.assertRaises(Refused): setup.configure_selected(stack, rows, bundles_dir=Path('bundles'))
            configure.assert_not_called(); stack.operator_client.assert_not_called()
        with patch.object(setup, 'load_bundle', side_effect=[b'one', b'two']),\
                patch.object(setup, 'setup_authority'),\
                patch.object(setup, 'configure_bot', side_effect=[Refused('unknown outcome')]) as configure:
            with self.assertRaises(Refused): setup.configure_selected(stack, rows, bundles_dir=Path('bundles'))
            self.assertEqual(1, configure.call_count)

    def test_two_selected_bots_each_execute_setup_and_publish_before_success(self):
        rows = setup.selection('2,3', setup.catalog()); client = object(); configured = []
        def deploy(_client, raw, *, after_references, after_provision):
            configured.append(raw)
            after_references({}); after_provision({})
            return {'state': 'PUBLISHED'}
        def files(raw, *_args):
            return {'workflow.json': json.dumps({'workflows': {'modelDeployments': [], 'workflows': []}, 'packages': []}).encode(),
                    'referenced-data.json': b'{"deviceGroups": []}'}
        manifests = [{'workflowId': row['workflowId'], 'connections': []} for row in rows]
        with patch.object(setup, 'load_bundle', side_effect=[b'one', b'two']),\
                patch.object(setup, 'setup_authority'),\
                patch.object(setup, 'verify', side_effect=manifests), patch.object(setup, 'inventory', side_effect=files),\
                patch.object(setup, 'call', return_value={'items': []}), patch.object(setup, 'deploy', side_effect=deploy):
            results = setup.configure_selected(Mock(), rows, bundles_dir=Path('bundles'), client=client)
        self.assertEqual([b'one', b'two'], configured)
        self.assertEqual(['PUBLISHED', 'PUBLISHED'], [value['state'] for value in results])

    def test_existing_model_secret_is_not_overwritten_on_blank_answer(self):
        row = setup.catalog()[0]
        scope = dict(secretName='ollama-local-key', toolGroupId='tools', deviceGroupId='devices')
        files = {'workflow.json': json.dumps(dict(workflows=dict(modelDeployments=[scope], workflows=[]), packages=[])).encode(),
                 'referenced-data.json': b'{"deviceGroups": []}'}
        def deploy(_client, _raw, **options): options['after_references']({}); return {'state': 'PUBLISHED'}
        existing = dict(name='ollama-local-key', toolGroupId='tools', deviceGroupId='devices')
        with patch.object(setup, 'verify', return_value=dict(workflowId=row['workflowId'], connections=[])),\
                patch.object(setup, 'inventory', return_value=files), patch.object(setup, 'deploy', side_effect=deploy),\
                patch.object(setup, 'call', return_value={'items': [existing]}) as call,\
                patch.object(setup, 'command') as command:
            setup.configure_bot(Mock(), object(), row, b'bundle', {})
        self.assertEqual(1, call.call_count); command.assert_not_called()

    def test_local_enrollment_key_change_stops_before_approval(self):
        current = dict(deviceId='device', state='PENDING', userCode='CODE123', keyFingerprint='original')
        changed = {**current, 'keyFingerprint': 'different'}
        with patch.object(setup, 'command', return_value=json.dumps(current)),\
                patch.object(setup, 'call', return_value=changed) as call:
            with self.assertRaisesRegex(Refused, 'identity changed'): setup.local_device(Mock(), object())
        self.assertEqual(1, call.call_count)

    def test_old_images_or_non_superuser_do_not_enter_configuration(self):
        with patch.object(setup, 'call', return_value={'role': 'BASIC'}) as call:
            with self.assertRaisesRegex(Refused, 'current tenant Super Admin'): setup.setup_authority(object())
            self.assertEqual(1, call.call_count)
        session = dict(role='SUPER_ADMIN', bootstrapAdminUserId='admin')
        with patch.object(setup, 'call', side_effect=[session, {'memberships': []}]):
            with self.assertRaisesRegex(Refused, 'publish matching current images'): setup.setup_authority(object())
        grants = [dict(id='super-admin-'+purpose.lower(), purpose=purpose, enabled=True, scope={'allActions': True})
                  for purpose in ('HUMAN', 'SECRET')]
        with patch.object(setup, 'call', side_effect=[session, {'memberships': [dict(enabled=True, grants=grants)]}]):
            setup.setup_authority(object())


class LauncherTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'nt' and shutil.which('powershell.exe'), 'Windows PowerShell required')
    def test_windows_startup_forwards_multi_selection_and_bundle_paths_with_spaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/'setup space'
            folder = root/'deploy/docker/quickstart/dev'; folder.mkdir(parents=True)
            helper = root/'debug/profiled'; helper.mkdir(parents=True)
            shutil.copyfile(ROOT/'deploy/docker/quickstart/dev/manage.ps1', folder/'manage.ps1')
            (helper/'python.ps1').write_text('function Find-DebugPython { return $env:TEST_DEBUG_PYTHON }', encoding='utf-8')
            (folder/'manage.py').write_text('import json,sys; print(json.dumps(sys.argv[1:]))', encoding='utf-8')
            bundles = root/'downloaded bots'
            result = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                str(folder/'manage.ps1'), 'start', '-Bots', '2,3', '-BundlesDir', str(bundles)],
                env=dict(os.environ, TEST_DEBUG_PYTHON=sys.executable), capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(['start', '--bots', '2,3', '--bundles-dir', str(bundles)], json.loads(result.stdout))

    def test_launcher_passes_both_selected_bots_after_starting_prerequisites(self):
        spec = importlib.util.spec_from_file_location('bot_launcher_test', ROOT/'deploy/docker/quickstart/dev/manage.py')
        launcher = importlib.util.module_from_spec(spec); spec.loader.exec_module(launcher)
        stack = Mock(); stack.images.return_value = {name: 'image-'+name for name in ('quickstart', 'device', 'runtime')}
        rows = setup.selection('2,3', setup.catalog())
        with tempfile.TemporaryDirectory() as temporary, patch.object(launcher, 'HERE', Path(temporary)),\
                patch.object(launcher, 'settings', return_value={}), patch.object(launcher, 'configure'),\
                patch.dict(sys.modules, {'stack': stack}), patch.object(sys, 'argv', ['manage.py', 'start', '--bots', '2,3', '--without-monitoring']),\
                patch.object(sys.stdin, 'isatty', return_value=False), patch.object(launcher.shutil, 'copyfile'),\
                patch.object(launcher.subprocess, 'run', return_value=SimpleNamespace(returncode=0)),\
                patch.object(setup, 'prompt_credentials', return_value=({}, False)),\
                patch.object(setup, 'configure_selected', return_value=[]) as configure:
            launcher.main()
        stack.main.assert_called_once()
        self.assertEqual(rows, configure.call_args.args[1])


if __name__ == '__main__': unittest.main()
