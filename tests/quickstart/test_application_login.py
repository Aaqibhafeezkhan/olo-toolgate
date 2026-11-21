# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""External password login: real verifier and HTTP tenant/identity boundaries."""
import http.client
import json
import threading
import unittest
import uuid
from unittest.mock import patch
import test_security as security
quickstart = security.quickstart


class ApplicationLoginTests(unittest.TestCase):
    def test_password_change_revokes_before_custody_replace(self):
        path=quickstart.DATA/'identity.json'
        previous=path.read_bytes();calls=[]
        def revoke(user,generation):
            self.assertEqual(previous,path.read_bytes());calls.append((user,generation))
        quickstart.LocalIdentity(revoke).authenticate(self.password,'Changed-Application-Password-2026')
        self.assertEqual([('admin',1)],calls)
        self.assertEqual(2,json.loads(path.read_bytes())['generation'])

    def test_failed_revocation_preserves_password_custody(self):
        path=quickstart.DATA/'identity.json';previous=path.read_bytes()
        def unavailable(*_):raise OSError('Control unavailable')
        with self.assertRaises(OSError):
            quickstart.LocalIdentity(unavailable).authenticate(self.password,'Changed-Application-Password-2026')
        self.assertEqual(previous,path.read_bytes())

    def setUp(self):
        security.SecurityTests.setUp(self)
        self.password = 'Application-Login-Password-2026'
        quickstart.atomic(quickstart.DATA/'identity.json', json.dumps(quickstart.password_record(self.password, True)))
        with quickstart.Database() as db:
            db.execute('INSERT INTO control_records VALUES(?,?,?,?,?)',
                       (quickstart.TENANT, 'USER', 'admin', 1,
                        json.dumps(dict(id='admin', name='Admin', enabled=True, revision=1))))
        ready = threading.Event(); ready.set()
        self.server = quickstart.BoundedServer(('127.0.0.1', 0), quickstart.Handler, ready)
        self.thread = threading.Thread(target=self.server.serve_forever); self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.thread.join(); self.server.server_close()
        security.SecurityTests.tearDown(self)

    def login(self, headers=None, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        payload = json.dumps(body if body is not None else dict(username='admin', password=self.password))
        connection.putrequest('POST', '/api/public/v1/auth/login')
        connection.putheader('Content-Type', 'application/json')
        connection.putheader('Content-Length', str(len(payload.encode())))
        for name, value in headers if headers is not None else [('Toolgate-Tenant-ID', quickstart.TENANT)]:
            connection.putheader(name, value)
        connection.endheaders(payload.encode())
        response = connection.getresponse(); status = response.status
        cookie = response.getheader('Set-Cookie'); result = json.loads(response.read()); connection.close()
        return status, result, cookie

    def test_external_login_binds_existing_user_and_tenant_without_cookie(self):
        status, body, cookie = self.login()
        self.assertEqual(200, status); self.assertIsNone(cookie)
        self.assertEqual('Bearer', body['tokenType']); self.assertEqual(900, body['expiresIn'])
        claims = quickstart.session('Bearer '+body['accessToken'])
        self.assertEqual(quickstart.TENANT, claims['tenant_id']); self.assertEqual('admin', claims['user_id'])
        self.assertNotIn('password', claims); self.assertNotIn('roles', claims)

    def test_tenant_selection_precedes_password_verification(self):
        cases = [([], 400), ([('Toolgate-Tenant-ID', 'Quickstart')], 400),
                 ([('Toolgate-Tenant-ID', str(uuid.uuid4()))], 403),
                 ([('Toolgate-Tenant-ID', quickstart.TENANT)]*2, 400)]
        with patch.object(self.server.identity, 'authenticate') as authenticate:
            for headers, expected in cases:
                self.assertEqual(expected, self.login(headers)[0])
            authenticate.assert_not_called()

    def test_password_required_even_when_console_password_is_disabled(self):
        with patch.dict(quickstart.os.environ, {'TOOLGATE_DISABLE_ADMIN_PASSWORD': 'true'}):
            self.assertEqual(401, self.login(body=dict(username='admin', password='wrong'))[0])
            self.assertEqual(400, self.login(body=dict(username='admin'))[0])
            self.assertEqual(200, self.login()[0])

    def test_missing_disabled_or_unbound_directory_user_cannot_get_token(self):
        with quickstart.Database() as db:
            db.execute("UPDATE control_records SET document=? WHERE tenant_id=? AND kind='USER' AND record_id='admin'",
                       (json.dumps(dict(id='admin', name='Admin', enabled=False, revision=1)), quickstart.TENANT))
        self.assertEqual(401, self.login()[0])
        with quickstart.Database() as db:
            db.execute("DELETE FROM control_records WHERE tenant_id=? AND kind='USER' AND record_id='admin'", (quickstart.TENANT,))
        self.assertEqual(401, self.login()[0])

    def test_unknown_fields_and_login_attempts_are_bounded(self):
        self.assertEqual(400, self.login(body=dict(username='admin', password=self.password, roles=['SUPER_ADMIN']))[0])
        for _ in range(9):
            self.assertEqual(401, self.login(body=dict(username='missing', password='wrong'))[0])
        self.assertEqual(429, self.login()[0])
