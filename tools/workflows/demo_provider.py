# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""External deterministic OpenAI-compatible test provider. Never part of core Compose."""
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import os
from pathlib import Path
import secrets
import ssl
import sys
import time

ROOT=Path('/custody/provider')


def initialize():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes,serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    ROOT.mkdir(mode=0o755,parents=True,exist_ok=True)
    if (ROOT/'server.key').exists():return
    now=datetime.now(timezone.utc)
    ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    name=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'Demo provider CA')])
    def builder(subject,key):
        return (x509.CertificateBuilder().subject_name(subject).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=30)))
    ca=builder(name,ca_key).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(ca_key,hashes.SHA256())
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    cert=(builder(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'demo-provider')]),key)
        .add_extension(x509.SubjectAlternativeName([x509.DNSName('demo-provider')]),False)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),False).sign(ca_key,hashes.SHA256()))
    for filename,data in [('ca.crt',ca.public_bytes(serialization.Encoding.PEM)),('server.crt',cert.public_bytes(serialization.Encoding.PEM)),
        ('server.key',key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())),
        ('token',secrets.token_urlsafe(32).encode())]:
        path=ROOT/filename
        with open(path,'xb',opener=lambda p,f:os.open(p,f,0o600)) as stream:stream.write(data)
        os.chown(path,65532,65532)
    os.chmod(ROOT/'ca.crt',0o644)
    os.chown(ROOT,65532,65532);os.chmod(ROOT,0o700)


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass

    def do_GET(self):
        if self.path!='/health':self.send_error(404);return
        payload=b'{"status":"ready"}'
        self.send_response(200);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)

    def do_POST(self):
        if self.path!='/v1/chat/completions' or self.headers.get('Authorization')!='Bearer '+(ROOT/'token').read_text():
            self.send_error(403);return
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<=98304:self.send_error(413);return
        body=json.loads(self.rfile.read(length));model=body['model']
        text=''
        for message in body['messages'][1:]:
            value=json.loads(message['content'])
            content=value.get('content',{})
            if isinstance(content,dict) and 'text' in content:text=content['text']
        (ROOT/'inflight.json').write_text(json.dumps(dict(model=model,inputSha256=hashlib.sha256(text.encode()).hexdigest())),encoding='utf-8')
        # Leave time for the real swap while staying inside Gateway's 20 s budget.
        if 'slow demo' in text:time.sleep(12)
        summary=model+': '+text.strip()
        payload=json.dumps(dict(choices=[dict(message=dict(content=json.dumps(dict(kind='finish',result=dict(summary=summary)))))] )).encode()
        self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)


if __name__=='__main__':
    if sys.argv[1]=='initialize':initialize()
    else:
        server=ThreadingHTTPServer(('0.0.0.0',9443),Handler)
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(ROOT/'server.crt',ROOT/'server.key')
        server.socket=context.wrap_socket(server.socket,server_side=True);server.serve_forever()
