# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Exercise the script transport against real TLS, denial and redirect responses."""
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timedelta, timezone
import http.server
import io
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from tools.admin import control


class AdminControlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=1))
                .not_valid_after(now + timedelta(hours=1))
                .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
                .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]), critical=False)
                .sign(key, hashes.SHA256()))
        cls.ca = cls.root / 'ca.crt'
        cls.ca.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        identity = cls.root / 'server.key'
        identity.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.NoEncryption()))
        cls.token = cls.root / 'operator.token'
        cls.token.write_text('private-test-bearer', encoding='ascii')
        cls.calls = []
        cls.reply_status = 200
        cls.reply_body = b'{"items":[]}'
        cls.location = ''

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                length = int(self.headers.get('Content-Length', 0))
                cls.calls.append((self.command, self.path, dict(self.headers), self.rfile.read(length)))
                self.send_response(cls.reply_status)
                if cls.location:
                    self.send_header('Location', cls.location)
                self.end_headers()
                self.wfile.write(cls.reply_body)

            do_POST = do_GET
            do_PUT = do_GET
            do_PATCH = do_GET

            def log_message(self, *args):
                pass

        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cls.ca, identity)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.origin = 'https://localhost:' + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.temp.cleanup()

    def setUp(self):
        type(self).calls.clear()
        type(self).reply_status = 200
        type(self).reply_body = b'{"items":[]}'
        type(self).location = ''

    def client(self):
        return control.Control(self.origin, self.token, self.ca)

    def test_verified_tls_carries_only_explicit_request_and_concurrency_headers(self):
        type(self).reply_status = 202
        type(self).reply_body = b'{"id":"review-required"}'
        result = self.client().request('PUT', '/api/control/v1/devices/device-1/groups',
                                       b'{"groupIds":["workers"]}', key='membership-1', revision=7)
        self.assertEqual((202, {'id': 'review-required'}), result)
        method, path, headers, data = self.calls[0]
        self.assertEqual(('PUT', '/api/control/v1/devices/device-1/groups'), (method, path))
        self.assertEqual('Bearer private-test-bearer', headers['Authorization'])
        self.assertEqual('membership-1', headers['Idempotency-Key'])
        self.assertEqual('"7"', headers['If-Match'])
        self.assertEqual(b'{"groupIds":["workers"]}', data)
        self.assertEqual(1, len(self.calls))

    def test_untrusted_ca_does_not_send_authenticated_http_request(self):
        with self.assertRaises(control.Refused):
            control.Control(self.origin, self.token).request('GET', '/api/control/v1/devices')
        self.assertEqual([], self.calls)

    def test_redirect_never_forwards_credentials_or_repeats_effect(self):
        type(self).reply_status = 307
        type(self).location = self.origin + '/api/control/v1/devices/elsewhere/enabled'
        with self.assertRaises(control.Refused):
            self.client().request('POST', '/api/control/v1/devices/device-1/enabled', b'{}')
        self.assertEqual(1, len(self.calls))

    def test_denial_and_dependency_failure_are_not_retried_or_echoed(self):
        for status in (401, 403, 409, 503):
            with self.subTest(status=status):
                type(self).reply_status = status
                type(self).reply_body = b'private-test-bearer reflected by server'
                with self.assertRaises(control.Refused) as error:
                    self.client().request('POST', '/api/control/v1/workflow-profiles', b'{}')
                self.assertNotIn('private-test-bearer', str(error.exception))
        self.assertEqual(4, len(self.calls))

    def test_null_invalid_and_oversized_responses_are_not_successes(self):
        for body in (b'null', b'not-json', b' ' * (control.MAX_JSON + 1)):
            with self.subTest(size=len(body)):
                type(self).reply_body = body
                with self.assertRaises(control.Refused):
                    self.client().request('GET', '/api/control/v1/devices')

    def test_origins_and_paths_cannot_redirect_a_token(self):
        for origin in ('http://localhost', 'https://user:password@example.org', 'https://example.org/path',
                       'https://example.org?secret=value', 'https://example.org/#x', 'https://exam\nple.org'):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                control.api_origin(origin)
        for path in ('https://example.org', '//example.org', '/api/control/v1/../login',
                     '/api/control/v1/%2e%2e/login', '/api/control/v1/devices\r\nX: token'):
            with self.subTest(path=path), self.assertRaises(control.Refused):
                self.client().request('GET', path)
        self.assertEqual([], self.calls)

    def test_cli_preserves_pending_status_and_keeps_private_input_out_of_errors(self):
        arguments = ['--origin', self.origin, '--token-file', str(self.token), '--ca-file', str(self.ca),
                     '--path', '/api/control/v1/devices']
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(0, control.main(arguments))
        self.assertEqual(200, json.loads(output.getvalue())['httpStatus'])
        private = self.root / 'private.json'
        private.write_text('private-test-bearer invalid JSON', encoding='utf-8')
        error = io.StringIO()
        with redirect_stderr(error):
            self.assertEqual(1, control.main(arguments + ['--method', 'POST', '--json-file', str(private)]))
        self.assertNotIn('private-test-bearer', error.getvalue())
        self.assertEqual(1, len(self.calls))

    def test_package_upload_uses_exact_archive_and_rejects_profile_or_obsolete_suffix(self):
        archive = self.root / 'package.olotool'
        archive.write_bytes(b'opaque exact archive bytes; Control verifies the package')
        args = ['--origin', self.origin, '--token-file', str(self.token), '--ca-file', str(self.ca),
                '--method', 'POST', '--path', '/api/control/v1/packages/uploads']
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, control.main(args + ['--package-file', str(archive)]))
        self.assertEqual(archive.read_bytes(), self.calls[0][3])
        for suffix in ('.olo', '.tgpkg'):
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, control.main(args + ['--package-file', str(archive.with_suffix(suffix))]))
        self.assertEqual(1, len(self.calls))

    def test_invalid_key_revision_or_get_body_is_refused_before_effect(self):
        for options in ({'key': 'invalid\r\nkey'}, {'revision': -1}, {'revision': 9007199254740992}, {'revision':True}, {'revision':'W/"1"\r\nInjected: value'}):
            with self.subTest(options=options), self.assertRaises(control.Refused):
                self.client().request('POST', '/api/control/v1/workflow-profiles', b'{}', **options)
        with self.assertRaises(control.Refused):
            self.client().request('GET', '/api/control/v1/devices', b'{}')
        self.assertEqual([], self.calls)

    def test_trigger_resource_version_is_preserved_exactly(self):
        self.client().request('POST','/api/control/v1/triggers/assignments/demo-a:action',b'{}',revision='W/"25"')
        self.assertEqual('W/"25"',self.calls[0][2]['If-Match'])

    def test_profiled_compose_initialization_is_blank_and_password_protected(self):
        import importlib.util
        root = Path(__file__).resolve().parents[2]
        spec=importlib.util.spec_from_file_location('profiled_stack',root/'debug/profiled/stack.py')
        stack=importlib.util.module_from_spec(spec);spec.loader.exec_module(stack)
        document = stack.definition({key:'test-'+key for key in ('quickstart','device','runtime')})
        env = document['services']['quickstart']['environment']
        self.assertEqual('true', env['TOOLGATE_QUICKSTART_ADMIN_ONLY'])
        self.assertEqual('false', env['TOOLGATE_DISABLE_ADMIN_PASSWORD'])
        self.assertEqual('', env['TOOLGATE_CONTROL_PROFILE_PATH'])
        self.assertEqual('', env['TOOLGATE_CONTROL_CORE_PACKAGES_LOCK_PATH'])


if __name__ == '__main__':
    unittest.main()
