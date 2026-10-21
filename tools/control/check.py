# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real PostgreSQL and signed-token HTTP gates. Owns and removes only its isolated containers."""
import argparse
import base64
import contextlib
import http.client
import http.server
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import threading
import urllib.error
import urllib.request
import urllib.parse
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ROOT = Path(__file__).resolve().parents[2]
POSTGRES = 'postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652'


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


@contextlib.contextmanager
def database():
    password = secrets.token_urlsafe(32)
    env = dict(os.environ, POSTGRES_PASSWORD=password)
    container = run(['docker', 'run', '-d', '--memory=512m', '--cpus=2', '-e', 'POSTGRES_PASSWORD',
                     '-p', '127.0.0.1::5432', '-p', '127.0.0.1::8082', '-p', '127.0.0.1::9092',
                     '-p', '127.0.0.1::8085', '-p', '127.0.0.1::9095', '-p', '127.0.0.1::8443', '-p', '127.0.0.1::8444',
                     '-p', '127.0.0.1::8081', '-p', '127.0.0.1::9091', POSTGRES], env=env, capture_output=True, text=True).stdout.strip()
    try:
        deadline = time.monotonic() + 60
        # The image's temporary init server accepts Unix sockets before initialization finishes.
        # TCP readiness observes only the final server, avoiding a startup race.
        while subprocess.run(['docker', 'exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], capture_output=True).returncode:
            if time.monotonic() > deadline: raise RuntimeError('Isolated PostgreSQL did not become ready')
            time.sleep(.2)
        info = json.loads(run(['docker', 'inspect', container], capture_output=True, text=True).stdout)[0]
        port = info['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
        # SQL goes through stdin; secrets never appear in command arguments or logs.
        sql = f"""CREATE ROLE toolgate_control_runtime NOLOGIN;
CREATE ROLE toolgate_control_platform NOLOGIN;
CREATE ROLE control_migrator LOGIN CREATEDB PASSWORD '{password}';
CREATE ROLE control_app LOGIN PASSWORD '{password}' IN ROLE toolgate_control_runtime;
CREATE ROLE control_provisioner LOGIN PASSWORD '{password}' IN ROLE toolgate_control_platform;
CREATE DATABASE control OWNER control_migrator;"""
        run(['docker', 'exec', '-i', container, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'], input=sql, capture_output=True, text=True)
        yield {'CONTROL_TEST_URL':f'jdbc:postgresql://127.0.0.1:{port}/control?sslmode=disable', 'CONTROL_TEST_PASSWORD':password,
               'container':container, 'port':port,
               'runtimePort':info['NetworkSettings']['Ports']['8082/tcp'][0]['HostPort'],
               'tlsPort':info['NetworkSettings']['Ports']['8443/tcp'][0]['HostPort'],
               'replicaTlsPort':info['NetworkSettings']['Ports']['8444/tcp'][0]['HostPort'],
               'managementPort':info['NetworkSettings']['Ports']['9092/tcp'][0]['HostPort'],
               'replicaRuntimePort':info['NetworkSettings']['Ports']['8085/tcp'][0]['HostPort'],
               'replicaManagementPort':info['NetworkSettings']['Ports']['9095/tcp'][0]['HostPort'],
               'gatewayPort':info['NetworkSettings']['Ports']['8081/tcp'][0]['HostPort'],
               'gatewayManagementPort':info['NetworkSettings']['Ports']['9091/tcp'][0]['HostPort']}
    finally:
        run(['docker', 'rm', '-f', container], capture_output=True)


def request(url, token=None, body=None, method='GET', headers=None, *, context=None):
    fields = {'Content-Type':'application/json', **(headers or {})}
    if token: fields['Authorization'] = 'Bearer ' + token
    data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=fields)
    try:
        with urllib.request.urlopen(req, timeout=20, context=context) as response: return response.status, response.read(), dict(response.headers)
    except urllib.error.HTTPError as error: return error.code, error.read(), dict(error.headers)


def oversized_request(url, token, size):
    """Require rejection of oversized Content-Length before uploading its body."""
    target = urllib.parse.urlsplit(url)
    connection_type = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
    connection = connection_type(target.hostname, target.port, timeout=20)
    try:
        connection.putrequest('POST', target.path + ('?' + target.query if target.query else ''))
        for name, value in {'Authorization':'Bearer ' + token, 'Content-Type':'application/json',
                            'Content-Length':str(size), 'Idempotency-Key':'oversize'}.items():
            connection.putheader(name, value)
        connection.endheaders()
        # Vert.x rejects the declared length before reading a body. Read that
        # response first: uploading concurrently can discard it in a TCP reset.
        # Missing responses/timeouts still fail; only HTTP 413 passes the gate.
        response = connection.getresponse()
        return response.status, response.read(), dict(response.headers)
    finally:
        connection.close()


def keypair(work):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    path = work/'jwt-public.pem'
    path.write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    path.chmod(0o644)
    return key, path


def token(key, **claims):
    now = int(time.time())
    data = {'iss':'https://identity.example.invalid', 'aud':'toolgate-control', 'sub':'test-admin',
            'iat':now, 'exp':now+300, 'tenant_id':'http-tenant', 'groups':['toolgate-admin'], **claims}
    def encode(value): return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    message = encode({'alg':'RS256', 'typ':'JWT'}) + b'.' + encode(data)
    signature = key.sign(message, padding.PKCS1v15(), hashes.SHA256())
    return (message + b'.' + base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()


def environment(db, public):
    return dict(os.environ, QUARKUS_DATASOURCE_JDBC_URL=db['CONTROL_TEST_URL'], QUARKUS_DATASOURCE_USERNAME='control_app',
                QUARKUS_DATASOURCE_PASSWORD=db['CONTROL_TEST_PASSWORD'], QUARKUS_FLYWAY_USERNAME='control_migrator',
                QUARKUS_FLYWAY_PASSWORD=db['CONTROL_TEST_PASSWORD'], MP_JWT_VERIFY_PUBLICKEY_LOCATION=public.as_posix(),
                TOOLGATE_CONTROL_DEVELOPMENT_MODE='true', MP_JWT_VERIFY_ISSUER='https://identity.example.invalid', MP_JWT_VERIFY_AUDIENCES='toolgate-control')


def free_port():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0)); return server.getsockname()[1]


def ready(management, process=None):
    deadline = time.monotonic() + 90
    while True:
        try:
            if request(management+'/q/health/ready')[0] == 200: return
        except (urllib.error.URLError, TimeoutError, ConnectionError): pass
        if process is not None and process.poll() is not None: raise RuntimeError('Control Plane failed startup; inspect private test log')
        if time.monotonic() > deadline: raise RuntimeError('Control Plane readiness deadline exceeded')
        time.sleep(.2)


def main():
    sys.path.insert(0,str(ROOT/'tools'))
    from enterprise.check import tests
    tests()


@contextlib.contextmanager
def collector():
    """Test-only receiver captures real OTLP protobuf requests from the production SDK/exporter."""
    spans=[]
    class Receiver(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            spans.append(self.rfile.read(int(self.headers['Content-Length'])))
            self.send_response(200);self.send_header('Content-Type','application/x-protobuf');self.send_header('Content-Length','0');self.end_headers()
        def log_message(self,*args): pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Receiver)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try: yield server,spans
    finally: server.shutdown();thread.join(timeout=5);server.server_close()


if __name__ == '__main__': main()
