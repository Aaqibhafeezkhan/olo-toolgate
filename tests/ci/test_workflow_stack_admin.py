# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Local operator authorization, scoped lifecycle and portable bundle regressions."""
from copy import deepcopy
import http.cookiejar
import http.client
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
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = ROOT / 'deploy/compose/WorkflowStack'
sys.path.insert(0, str(BUNDLE / 'scripts'))
import admin_server


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
                patch.object(self.controller, 'blank') as blank, patch.object(self.controller,'preserve_admin') as preserve:
            self.controller.perform('reset', None, None)
        destroy.assert_called_once_with(self.root, 'first')
        blank.assert_called_once()
        preserve.assert_called_once_with('first')
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
        self.assertEqual('true', service['environment']['TOOLGATE_QUICKSTART_ADMIN_ONLY'])
        self.assertEqual('true', service['environment']['TOOLGATE_TRIGGERS_CONTROL_CORE_ENABLED'])
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

    def test_console_proxy_keeps_control_auth_and_does_not_forward_operator_credentials(self):
        observed=[]
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_GET(self):
                observed.append(dict(self.headers))
                self.send_response(200 if self.headers.get('Authorization')=='Bearer control-token' else 401)
                self.send_header('Content-Length','2');self.end_headers();self.wfile.write(b'{}')
            def do_POST(self):
                self.rfile.read(int(self.headers.get('Content-Length','0')))
                self.do_GET()
        upstream=ThreadingHTTPServer(('127.0.0.1',0),Upstream)
        threading.Thread(target=upstream.serve_forever,daemon=True).start()
        self.addCleanup(upstream.server_close);self.addCleanup(upstream.shutdown)
        self.controller.state['console']=f'http://127.0.0.1:{upstream.server_address[1]}/console/'
        server=ThreadingHTTPServer(('127.0.0.1',0),admin_server.Handler);server.controller=self.controller
        threading.Thread(target=server.serve_forever,daemon=True).start()
        self.addCleanup(server.server_close);self.addCleanup(server.shutdown)
        port=server.server_address[1];origin=f'http://127.0.0.1:{port}'
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with patch.object(admin_server,'PORT',port),patch.object(admin_server,'ORIGIN',origin):
            with self.assertRaises(urllib.error.HTTPError) as error:opener.open(origin+'/api/control/v1/admin-session')
            self.assertEqual(401,error.exception.code)
            request=urllib.request.Request(origin+'/api/control/v1/admin-session',headers={
                'Authorization':'Bearer control-token','Cookie':'workflow_stack=operator-secret; toolgate-session=control-cookie',
                'X-WorkflowStack-CSRF':'operator-csrf'})
            with opener.open(request) as response:self.assertEqual(200,response.status)
            self.assertNotIn('operator-secret',str(observed[-1]));self.assertNotIn('operator-csrf',str(observed[-1]))
            self.assertIn('control-cookie',str(observed[-1]))
            large=b'x'*(3*1024*1024)
            headers={'Authorization':'Bearer control-token','Origin':origin}
            with opener.open(urllib.request.Request(origin+'/api/control/v1/packages/uploads',large,headers)) as response:self.assertEqual(200,response.status)
            connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
            try:
                connection.putrequest('POST','/api/control/v1/workflow-profiles')
                connection.putheader('Origin',origin);connection.putheader('Content-Length',str(len(large)))
                connection.endheaders()
                self.assertEqual(413,connection.getresponse().status)
            finally:connection.close()
            with self.assertRaises(urllib.error.HTTPError) as error:
                opener.open(urllib.request.Request(origin+'/api/control/v1/workflow-profiles',b'{}',{'Origin':'https://evil.example'}))
            self.assertEqual(403,error.exception.code)

    def test_workflow_bundles_never_use_deployment_actions(self):
        for action in ('import','export','replace'):
            with self.subTest(action=action),self.assertRaisesRegex(ValueError,'Unsupported'):
                self.controller.submit(action,'first')

    def test_reset_preserves_the_actual_admin_verifier_without_logging_it(self):
        credential=self.root/'state/first/credentials/admin'
        credential.mkdir(parents=True)
        (credential/'username.txt').write_text('admin')
        (credential/'password.txt').write_text('existing-password')
        verifier=b'{"salt":"test-salt","hash":"test-verifier","mustChangePassword":false}'
        with patch.object(admin_server.subprocess,'run',return_value=SimpleNamespace(stdout=verifier)) as command:
            self.controller.preserve_admin('first')
        command.assert_called_once()
        self.assertEqual(verifier,(self.controller.home/'reset-admin/identity.json').read_bytes())
        self.assertEqual('existing-password',(self.controller.home/'reset-admin/password.txt').read_text())
        self.assertFalse((self.controller.home/'operation.log').exists())
        (credential/'password.txt').unlink()
        with patch.object(admin_server.subprocess,'run',return_value=SimpleNamespace(stdout=verifier)):
            self.controller.preserve_admin('first')
        self.assertFalse((self.controller.home/'reset-admin/password.txt').exists())


if __name__ == '__main__':
    unittest.main()
