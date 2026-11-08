# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Ephemeral external operator custody for the owned S22 runtime fixture.

Approval private keys never enter the Control container or its data volume.
"""
import base64
import datetime
import ipaddress
import json
from pathlib import Path
import secrets
import ssl
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def encoded(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def canonical(value):
    # Fixture claims contain ASCII identifiers and integer timestamps only.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


class OperatorFixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="s22-operators-")
        self.directory = Path(self.temporary.name)
        self.tenant = str(uuid.uuid4())
        self.approvers = [rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(3)]
        now = datetime.datetime.now(datetime.timezone.utc)
        ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Owned S22 operator CA")])
        ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
              .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
              .not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .sign(ca_key, hashes.SHA256()))

        def identity(name, purpose):
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            cert = (x509.CertificateBuilder()
                    .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)]))
                    .issuer_name(ca.subject).public_key(key.public_key())
                    .serial_number(x509.random_serial_number())
                    .not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1))
                    .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                    .add_extension(x509.ExtendedKeyUsage([purpose]), critical=True)
                    .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost"),
                        x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
                    .sign(ca_key, hashes.SHA256()))
            return (cert.public_bytes(serialization.Encoding.PEM).decode(),
                    key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()).decode())

        server_cert, server_key = identity("Owned operator listener", ExtendedKeyUsageOID.SERVER_AUTH)
        client_cert, client_key = identity("Owned operator peer", ExtendedKeyUsageOID.CLIENT_AUTH)
        wrong_cert, wrong_key = identity("Wrong purpose peer", ExtendedKeyUsageOID.SERVER_AUTH)
        self.public = []
        for index, key in enumerate(self.approvers):
            n = key.public_key().public_numbers().n
            self.public.append(dict(kid="operator-"+str(index),
                                    n=encoded(n.to_bytes((n.bit_length()+7)//8, "big")), e="AQAB"))
        self.material = {"server.pem":server_cert, "server-key.pem":server_key,
                         "ca.pem":ca.public_bytes(serialization.Encoding.PEM).decode(),
                         "operators.json":json.dumps(self.public)}
        for name, value in {"ca.pem":self.material["ca.pem"], "client.pem":client_cert,
                            "client-key.pem":client_key, "wrong.pem":wrong_cert, "wrong-key.pem":wrong_key}.items():
            path=self.directory/name
            path.write_text(value)
            path.chmod(0o600)
        self.context=ssl.create_default_context(cafile=str(self.directory/"ca.pem"))
        self.context.load_cert_chain(str(self.directory/"client.pem"),str(self.directory/"client-key.pem"))
        self.no_peer=ssl.create_default_context(cafile=str(self.directory/"ca.pem"))
        self.wrong_peer=ssl.create_default_context(cafile=str(self.directory/"ca.pem"))
        self.wrong_peer.load_cert_chain(str(self.directory/"wrong.pem"),str(self.directory/"wrong-key.pem"))

    def prepare(self, run, image, volume):
        # Fresh identity initialization is a supported bootstrap input. This owned
        # fixture sets it before the first database or installation is created.
        code = """import json,os,pathlib,sys
v=json.load(sys.stdin)
root=pathlib.Path('/data')
root.chmod(0o700);os.chown(root,65532,65532)
for name in ('state','s22-operator'):
 p=root/name;p.mkdir(mode=0o700);os.chown(p,65532,65532)
identity=root/'state/tenant.json'
identity.write_text(json.dumps(dict(formatVersion=1,alias='quickstart',tenantId=v['tenant'])))
identity.chmod(0o600);os.chown(identity,65532,65532)
for name,value in v['files'].items():
 p=root/'s22-operator'/name
 p.write_text(value);p.chmod(0o600);os.chown(p,65532,65532)
"""
        run(["docker","run","--rm","-i","--network","none","--user","0","-v",volume+":/data",
             "--entrypoint","/opt/quickstart-python/bin/python",image,"-c",code],
            input=json.dumps(dict(tenant=self.tenant,files=self.material)),text=True,capture_output=True)
        values=dict(PORT="9444",HOST="0.0.0.0",TENANT_ID=self.tenant,
                    CERTIFICATE_PATH="/data/s22-operator/server.pem",
                    PRIVATE_KEY_PATH="/data/s22-operator/server-key.pem",
                    CLIENT_CA_PATH="/data/s22-operator/ca.pem",
                    OPERATOR_KEYS_PATH="/data/s22-operator/operators.json")
        return [item for key,value in values.items() for item in ("-e","TOOLGATE_BREAKGLASS_"+key+"="+value)]+[
            "-p","127.0.0.1::9444"]

    def body(self, request_id, tenant=None, count=2):
        now=int(time.time()*1000)
        body=dict(scope=dict(kind="PACKAGE",value="acme.orders"),mode="TERMINATE",
                  reason="Owned S22 external operator qualification")
        claims=canonical(dict(**body,tenantId=tenant or self.tenant,requestId=request_id,
                              issuedAtUnixMs=now,notAfterUnixMs=now+60000))
        signatures=[]
        for index,key in enumerate(self.approvers[:count]):
            header=canonical(dict(alg="RS256",typ="toolgate-breakglass-approval+jws",kid="operator-"+str(index)))
            signed=encoded(header)+"."+encoded(claims)
            signatures.append(signed+"."+encoded(key.sign(signed.encode(),padding.PKCS1v15(),hashes.SHA256())))
        return dict(**body,operatorSignatures=signatures)

    def send(self, port, request_id, body, context=None):
        request=urllib.request.Request("https://127.0.0.1:"+str(port)+"/break-glass/kills",
            canonical(body),{"Content-Type":"application/json","Idempotency-Key":request_id},method="POST")
        try:
            with urllib.request.urlopen(request,context=context or self.context,timeout=10) as response:
                return response.status,json.loads(response.read())
        except urllib.error.HTTPError as response:
            raw=response.read()
            return response.code,json.loads(raw) if raw else None

    def close(self):
        self.temporary.cleanup()
