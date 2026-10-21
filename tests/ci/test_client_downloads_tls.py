# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Download smoke requests verify a disposable HTTPS server's private CA."""
from contextlib import contextmanager, nullcontext
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import ssl
import sys
import tempfile
import threading
import unittest
import urllib.error
from unittest.mock import patch

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from client import downloads


@contextmanager
def https_fixture():
    with tempfile.TemporaryDirectory(prefix='downloads-tls-') as temporary:
        folder = Path(temporary)
        now = datetime.datetime.now(datetime.timezone.utc)
        def issue(key, name, issuer, signer, ca):
            builder = (x509.CertificateBuilder().subject_name(name).issuer_name(issuer)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now-datetime.timedelta(minutes=1))
                       .not_valid_after(now+datetime.timedelta(days=1))
                       .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True))
            if not ca:
                builder = builder.add_extension(x509.SubjectAlternativeName([
                    x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
            return builder.sign(signer, hashes.SHA256())
        ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Download test CA')])
        ca = issue(ca_key, ca_name, ca_name, ca_key, True)
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        leaf = issue(key, x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')]),
                     ca_name, ca_key, False)
        ca_file = folder/'ca.pem'; ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
        cert = folder/'server.pem'; cert.write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
        private = folder/'key.pem'
        private.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                            serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        payload = b'inert download regression fixture'
        manifest = {'version':'0.10.0-dev', 'artifacts':[
            {'platform':platform, 'filename':platform+'.archive', 'bytes':len(payload),
             'sha256':hashlib.sha256(payload).hexdigest()} for platform in ('WINDOWS','MACOS','LINUX')]}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                if self.path == '/api/public/v1/clients':
                    status, body = 200, json.dumps(manifest).encode()
                elif self.path.endswith(('/MACOS.archive','/LINUX.archive')):
                    status, body = 200, payload
                else:
                    status, body = (401 if self.path == '/api/control/v1/users' else 404), b''
                self.send_response(status); self.send_header('X-Content-Type-Options','nosniff')
                self.end_headers(); self.wfile.write(body)
        server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(cert, private)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            yield dict(origins=['https://127.0.0.1:'+str(server.server_port)], folder=folder,
                       sslContext=ssl.create_default_context(cafile=str(ca_file)),
                       caCertificatePath=ca_file, serverCertificatePath=cert)
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=5)


class DownloadTlsTests(unittest.TestCase):
    def test_all_download_checks_use_the_fixture_ca_and_pass_scoped_browser_trust(self):
        with https_fixture() as installed:
            def browser(*args, **kwargs):
                env = kwargs['env']
                self.assertEqual(env['UI_TEST_ORIGIN'], installed['origins'][0])
                self.assertEqual(Path(env['NODE_EXTRA_CA_CERTS']).read_bytes(), installed['caCertificatePath'].read_bytes())
                self.assertEqual(Path(env['UI_TEST_TLS_CERTIFICATE']).read_bytes(), installed['serverCertificatePath'].read_bytes())
                self.assertNotEqual(env.get('NODE_TLS_REJECT_UNAUTHORIZED'), '0')
            with patch.object(downloads, 'fixture', return_value=nullcontext(installed)), \
                 patch.object(downloads, 'ROOT', installed['folder']), \
                 patch.object(sys, 'argv', ['downloads.py']), \
                 patch.object(downloads.subprocess, 'run', side_effect=browser) as launched:
                downloads.main()
                launched.assert_called_once()
                self.assertTrue((installed['folder']/'build/client/public-downloads-proof.json').is_file())

    def test_an_untrusted_certificate_stops_the_smoke_before_browser_launch(self):
        with https_fixture() as installed:
            installed['sslContext'] = ssl.create_default_context()
            with patch.object(downloads, 'fixture', return_value=nullcontext(installed)), \
                 patch.object(sys, 'argv', ['downloads.py']), \
                 patch.object(downloads.subprocess, 'run') as launched:
                with self.assertRaises(urllib.error.URLError) as error:
                    downloads.main()
                self.assertIsInstance(error.exception.reason, ssl.SSLCertVerificationError)
                launched.assert_not_called()


if __name__ == '__main__':
    unittest.main()
