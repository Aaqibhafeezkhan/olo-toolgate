# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Local operator authorization, scoped lifecycle and portable bundle regressions."""
from copy import deepcopy
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request
import zipfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'deploy/compose/WorkflowStack'
sys.path.insert(0, str(BUNDLE / 'scripts'))
import admin_server
import profile_bundle


class StackAdminTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(BUNDLE / 'configurations/native-hotfolder', self.root / 'configurations/first')
        (self.root / 'models').mkdir()
        (self.root / 'models/model.gguf').write_bytes(b'mocked model bytes')
        self.controller = admin_server.Controller(self.root)
        self.addCleanup(lambda: self.controller.executor.shutdown(wait=True))

    def image(self, *args):
        if args[:2] == ('image', 'save'):
            Path(args[3]).write_bytes(b'mocked Docker image archive')
        return 'sha256:' + 'a' * 64

    def test_authentication_rejects_wrong_password_and_throttles(self):
        with self.assertRaises(PermissionError):
            self.controller.login(dict(username='admin', password='incorrect'))
        password = (self.controller.home / 'credentials/admin/password.txt').read_text().strip()
        token, csrf = self.controller.login(dict(username='admin', password=password))
        self.assertIn(token, self.controller.sessions)
        self.assertEqual(csrf, self.controller.sessions[token][0])
        self.controller.attempts = [admin_server.time.monotonic()] * 10
        with self.assertRaisesRegex(PermissionError, 'Too many'):
            self.controller.login(dict(username='admin', password=password))

    def test_no_default_configuration_does_not_invent_one(self):
        spec = importlib.util.spec_from_file_location('blank_stack_manager', BUNDLE / 'manage.py')
        manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(manager)
        manager.HERE = self.root
        self.assertEqual('', manager.default_configuration())
        self.assertFalse((self.root / '.env').exists())
        (self.root / '.env').write_text('CONFIGURATION=\n')
        self.assertEqual('', manager.default_configuration())
        self.assertNotIn('common', [item['name'] for item in self.controller.status()['configurations']])

    def test_replacement_requires_exact_confirmation(self):
        with self.assertRaises(ValueError):
            self.controller.submit('replace', 'first', confirmation='REPLACE other')
        self.assertFalse(self.controller.busy)

    def test_http_requires_authentication_origin_host_and_csrf(self):
        server = admin_server.ThreadingHTTPServer(('127.0.0.1', 0), admin_server.Handler)
        server.controller = self.controller
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        port = server.server_address[1]
        origin = f'http://127.0.0.1:{port}'
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        with patch.object(admin_server, 'PORT', port), patch.object(admin_server, 'ORIGIN', origin):
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(origin + '/api/status')
            self.assertEqual(401, error.exception.code)
            password = (self.controller.home / 'credentials/admin/password.txt').read_text().strip()
            body = json.dumps(dict(username='admin', password=password)).encode()
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(urllib.request.Request(origin + '/api/login', body, {'Origin': 'https://evil.example'}))
            self.assertEqual(403, error.exception.code)
            with opener.open(urllib.request.Request(origin + '/api/login', body, {'Origin': origin})) as response:
                csrf = json.load(response)['csrf']
                self.assertIn('HttpOnly', response.headers['Set-Cookie'])
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(urllib.request.Request(origin + '/api/action', b'{"action":"reset"}', {'Origin': origin}))
            self.assertEqual(401, error.exception.code)
            with opener.open(origin + '/api/status') as response:
                self.assertEqual(csrf, json.load(response)['csrf'])

    def test_switch_stops_only_this_bundles_containers_and_retains_storage(self):
        (self.root / 'data/old').mkdir(parents=True)
        owned = {'com.docker.compose.project': 'toolgate-workflow-old',
                 'com.docker.compose.project.working_dir': str(self.root)}
        foreign = {**owned, 'com.docker.compose.project.working_dir': str(self.root / 'other')}
        with patch.object(admin_server.lifecycle, 'objects', return_value=[('owned', owned), ('foreign', foreign)]), \
                patch.object(admin_server.lifecycle, 'docker') as docker, \
                patch.object(self.controller, 'command') as command:
            self.controller.perform('switch', 'first', None)
        docker.assert_called_once_with('container', 'stop', '--time', '30', 'owned')
        command.assert_called_once_with('deploy', '--configuration', 'first')
        self.assertTrue((self.root / 'data/old').is_dir())
        self.assertEqual('first', self.controller.state['active'])
        self.assertEqual('CONFIGURATION=first\n', (self.root / '.env').read_text())

    def test_factory_reset_is_scoped_and_does_not_delete_profile_sources(self):
        self.controller.state['active'] = 'first'
        with patch.object(admin_server.lifecycle, 'objects', return_value=[]), \
                patch.object(admin_server.lifecycle, 'destroy') as destroy, \
                patch.object(self.controller, 'blank') as blank:
            self.controller.perform('reset', None, None)
        destroy.assert_called_once_with(self.root, 'first')
        blank.assert_called_once()
        self.assertTrue((self.root / 'configurations/first/workflow-profile.json').is_file())
        with self.assertRaises(ValueError):
            self.controller.submit('reset', confirmation='incorrect')

    def test_blank_control_reads_a_root_initialized_readonly_volume(self):
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(admin_server.subprocess, 'run', side_effect=[
                SimpleNamespace(returncode=0), SimpleNamespace(returncode=0),
                SimpleNamespace(stdout='initial-password')]), \
                patch.object(admin_server.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            self.controller.blank()
        document = json.loads((self.root / 'state/blank/compose.json').read_text())
        service = document['services']['quickstart']
        self.assertIn('blank-bootstrap:/bootstrap:ro', service['volumes'])
        self.assertEqual('/bootstrap/profile.json', service['environment']['TOOLGATE_CONTROL_PROFILE_PATH'])
        self.assertEqual('0:0', document['services']['bootstrap-init']['user'])
        self.assertEqual('service_completed_successfully', service['depends_on']['bootstrap-init']['condition'])
        self.assertEqual('ready', self.controller.state['state'])
        self.assertEqual('CONFIGURATION=\n', (self.root / '.env').read_text())

    def test_serialization_and_failed_start_are_visible(self):
        self.controller.busy = True
        with self.assertRaisesRegex(ValueError, 'running'):
            self.controller.submit('switch', 'first')
        with patch.object(admin_server.lifecycle, 'objects', return_value=[]), \
                patch.object(self.controller, 'command', side_effect=ValueError('Startup refused')):
            self.controller.perform('switch', 'first', None)
        self.assertFalse(self.controller.busy)
        self.assertIsNone(self.controller.state['active'])
        self.assertEqual(('failed', 'Startup refused'), (self.controller.state['state'], self.controller.state['error']))

    def export(self):
        output = self.root / 'portable.olo'
        with patch.object(profile_bundle, 'docker', side_effect=self.image):
            profile_bundle.export(self.root, 'first', output)
        return output

    def test_round_trip_preserves_profile_graph_assets_model_and_image_identities(self):
        private = self.root / 'configurations/first/credentials'
        private.mkdir()
        (private / 'password.txt').write_text('not exported')
        output = self.export()
        with zipfile.ZipFile(output) as archive:
            self.assertFalse(any('credentials' in name for name in archive.namelist()))
            self.assertIn('models/model.gguf', archive.namelist())
            self.assertIn('images.tar', archive.namelist())
            self.assertNotIn('README.md', archive.namelist())
            self.assertNotIn('configuration/README.md', archive.namelist())
            self.assertFalse((output.parent / 'README.md').exists())
            self.assertTrue(all(item.compress_type == zipfile.ZIP_DEFLATED for item in archive.infolist()))
        with patch.object(profile_bundle, 'docker', side_effect=self.image):
            profile_bundle.install(self.root, 'imported', output)
        for path in (self.root / 'configurations/first').rglob('*'):
            if path.is_file() and 'credentials' not in path.parts and path.name != 'README.md':
                self.assertEqual(path.read_bytes(), (self.root / 'configurations/imported' / path.relative_to(self.root / 'configurations/first')).read_bytes())
        self.assertIn('../../data/imported/inbox/', (self.root / 'configurations/imported/README.md').read_text())
        with self.assertRaises(ValueError):
            profile_bundle.install(self.root, 'first', output)

    def test_import_refuses_tampering_before_loading_images(self):
        output = self.export()
        rewritten = self.root / 'tampered.olo'
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(rewritten, 'w') as target:
            for info in source.infolist():
                target.writestr(info.filename, b'changed' if info.filename == 'models/model.gguf' else source.read(info.filename))
        with patch.object(profile_bundle, 'docker') as docker, self.assertRaisesRegex(ValueError, 'digest'):
            profile_bundle.install(self.root, 'tampered', rewritten)
        docker.assert_not_called()
        self.assertFalse((self.root / 'configurations/tampered').exists())

    def test_import_rejects_traversal_duplicate_and_symlink_entries(self):
        output = self.export()
        for bad in ('../outside', 'configuration/workflow-profile.json', 'configuration/link'):
            rewritten = self.root / 'bad.olo'
            with zipfile.ZipFile(output) as source, zipfile.ZipFile(rewritten, 'w') as target:
                for info in source.infolist():
                    target.writestr(info, source.read(info.filename))
                info = zipfile.ZipInfo(bad)
                if bad.endswith('/link'):
                    info.external_attr = 0o120777 << 16
                target.writestr(info, b'outside')
            with patch.object(profile_bundle, 'docker') as docker, self.assertRaises(ValueError):
                profile_bundle.install(self.root, 'rejected', rewritten)
            docker.assert_not_called()

    def test_import_refuses_conflicting_model_without_overwriting(self):
        output = self.export()
        (self.root / 'models/model.gguf').write_bytes(b'other model')
        with patch.object(profile_bundle, 'docker') as docker, self.assertRaisesRegex(ValueError, 'different content'):
            profile_bundle.install(self.root, 'different', output)
        docker.assert_not_called()
        self.assertEqual(b'other model', (self.root / 'models/model.gguf').read_bytes())

    def test_portable_operations_require_the_olo_extension(self):
        with patch.object(profile_bundle, 'docker') as docker:
            with self.assertRaisesRegex(ValueError, '.olo'):
                profile_bundle.export(self.root, 'first', self.root / 'profile.zip')
            with self.assertRaisesRegex(ValueError, '.olo'):
                profile_bundle.install(self.root, 'other', self.root / 'profile.zip')
        docker.assert_not_called()


if __name__ == '__main__':
    unittest.main()
