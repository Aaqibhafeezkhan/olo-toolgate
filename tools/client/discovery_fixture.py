# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Loopback-only signed v2 discovery for disposable native installer qualification."""
import base64
from collections import namedtuple
from contextlib import contextmanager
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import tempfile
import threading
import time
import uuid
Site=namedtuple('Site','console origin tenant')


@contextmanager
def installation():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa
    from cryptography.x509.oid import NameOID
    import ipaddress
    encode=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=').decode()
    canonical=lambda value:json.dumps(value,sort_keys=True,separators=(',',':')).encode()
    tenant=str(uuid.uuid4());now=datetime.datetime.now(datetime.timezone.utc)
    def certificate(key,subject,issuer,signer,ca,san=None):
        builder=x509.CertificateBuilder().subject_name(subject).issuer_name(issuer).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).add_extension(x509.BasicConstraints(ca=ca,path_length=0 if ca else None),True).add_extension(x509.KeyUsage(not ca,False,not ca,False,False,ca,ca,False,False),True)
        if san is not None:builder=builder.add_extension(x509.SubjectAlternativeName(san),False)
        return builder.sign(signer,hashes.SHA256())
    tls_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    tls_name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Native qualification TLS root')])
    tls_ca=certificate(tls_key,tls_name,tls_name,tls_key,True)
    leaf_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    leaf=certificate(leaf_key,x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'localhost')]),tls_name,tls_key,False,[x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))])
    device_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    device_name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Tenant device CA'),x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME,tenant)])
    device_ca=certificate(device_key,device_name,device_name,device_key,True)
    signer=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    number=lambda value:encode(value.to_bytes((value.bit_length()+7)//8,'big'))
    public=signer.public_key().public_numbers();kid='native-qualification'
    authority=hashlib.sha256(device_ca.public_bytes(serialization.Encoding.DER)).hexdigest()
    origin=None
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if self.path=='/api/public/v1/clients/configuration':
                value=dict(serverUrl=origin)
            elif self.path=='/api/public/v1/clients/local-trust':
                value=dict(serverUrl=origin,caCertificatePem=tls_ca.public_bytes(serialization.Encoding.PEM).decode())
            elif self.path=='/.well-known/olo-toolgate-client':
                issued=int(time.time()*1000)-1000
                manifest=dict(protocolVersion=2,tenantId=tenant,issuer='urn:toolgate:tenant:'+tenant,audiences=[origin],serverId='native-qualification',organization='Native qualification',controlUrl=origin,gatewayUrl=origin,verificationUri=origin+'/console/#enroll',minimumClientVersion='0.1.0',issuedAtUnixMs=issued,expiresAtUnixMs=issued+300000,keySetVersion=1,discoveryKeys=dict(keys=[dict(kty='RSA',use='sig',alg='RS256',kid=kid,n=number(public.n),e=number(public.e))]),deviceIssuerKeyId=authority,deviceAuthorities=[dict(keyId=authority,state='ACTIVE',certificatePem=device_ca.public_bytes(serialization.Encoding.PEM).decode())])
                payload=encode(canonical(dict(alg='RS256',typ='toolgate-discovery+jws',kid=kid)))+'.'+encode(canonical(manifest))
                value=dict(jws=payload+'.'+encode(signer.sign(payload.encode(),padding.PKCS1v15(),hashes.SHA256())))
            else:
                # No enrollment is approved: the installed service must remain UNENROLLED.
                self.send_error(503);return
            body=canonical(value);self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def do_POST(self):self.send_error(503)
    servers=[];threads=[]
    with tempfile.TemporaryDirectory(prefix='toolgate-native-discovery-') as temporary:
        root=Path(temporary);cert=root/'tls.pem';key=root/'tls-key.pem'
        cert.write_bytes(leaf.public_bytes(serialization.Encoding.PEM)+tls_ca.public_bytes(serialization.Encoding.PEM));cert.chmod(0o600)
        key.write_bytes(leaf_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));key.chmod(0o600)
        try:
            https=ThreadingHTTPServer(('127.0.0.1',0),Handler);servers.append(https)
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2;context.load_cert_chain(cert,key)
            https.socket=context.wrap_socket(https.socket,server_side=True);origin='https://127.0.0.1:'+str(https.server_port)
            http=ThreadingHTTPServer(('127.0.0.1',0),Handler);servers.append(http)
            for server in servers:
                thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start();threads.append(thread)
            yield Site('http://127.0.0.1:'+str(http.server_port),origin,tenant)
        finally:
            for server,thread in zip(servers,threads):server.shutdown()
            for server in servers:server.server_close()
            for thread in threads:thread.join(timeout=5)
