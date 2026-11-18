# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Local installation custody and supervised service entrypoints, never profile logic."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import sys
import time
import signal
import subprocess
import threading


def private(path,data,uid=65532):
    path=Path(path)
    path.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
    if path.parent!=Path('/custody'):
        os.chmod(path.parent,0o700);os.chown(path.parent,uid,uid)
    temporary=path.with_name(path.name+'.'+secrets.token_hex(6))
    with open(temporary,'xb',opener=lambda p,f:os.open(p,f,0o600)) as stream:
        stream.write(data.encode() if isinstance(data,str) else data);stream.flush();os.fsync(stream.fileno())
    os.chown(temporary,uid,uid);os.replace(temporary,path)


def initialize():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    root=Path('/custody')
    marker=root/'installation.json'
    if marker.exists():
        if json.loads(marker.read_text())!={'formatVersion':2}:raise ValueError('Unsupported installation custody')
        for name in ('langgraph/ca.crt','langgraph/server.crt','langgraph/server.key','control/identity.p12','control/password'):
            if not (root/name).is_file():raise ValueError('Incomplete installation custody; preserve and repair explicitly')
        return
    if any(path.name not in ('langgraph','control') or not path.is_dir() or any(path.iterdir()) for path in root.iterdir()):
        raise ValueError('Unrecognized existing installation custody; refusing adoption')
    os.chmod(root,0o755)
    (root/'device').mkdir(mode=0o700,exist_ok=True)
    key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
    name=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'Local workflow graph CA')])
    now=datetime.now(timezone.utc)
    ca=(x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=1))
        .not_valid_after(now+timedelta(days=365)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(key,hashes.SHA256()))
    def identity(common,server):
        leaf_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        builder=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,common)]))
            .issuer_name(name).public_key(leaf_key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-timedelta(minutes=1)).not_valid_after(now+timedelta(days=90)))
        if server:builder=builder.add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')]),False)
        builder=builder.add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH if server else x509.oid.ExtendedKeyUsageOID.CLIENT_AUTH]),False)
        return leaf_key,builder.sign(key,hashes.SHA256())
    server_key,server=identity('localhost',True);client_key,client=identity('workflow-control',False)
    password=secrets.token_urlsafe(32)
    private(root/'langgraph/ca.crt',ca.public_bytes(serialization.Encoding.PEM))
    private(root/'langgraph/server.crt',server.public_bytes(serialization.Encoding.PEM))
    private(root/'langgraph/server.key',server_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    private(root/'control/ca.crt',ca.public_bytes(serialization.Encoding.PEM))
    private(root/'control/password',password)
    private(root/'control/identity.p12',pkcs12.serialize_key_and_certificates(b'control',client_key,client,[ca],serialization.BestAvailableEncryption(password.encode())))
    private(marker,json.dumps(dict(formatVersion=2)))


def provision(payload):
    if set(payload)!={'accessToken','tenantId','expiresAtUnixMs'} or not isinstance(payload['accessToken'],str) or not 1<=len(payload['accessToken'])<=8192:
        raise ValueError('Invalid runtime credential response')
    import re
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}',payload['tenantId']):raise ValueError('Invalid tenant')
    root=Path('/custody');runtime=root/'langgraph'
    ca=Path('/data/keys/device-ca.crt').read_bytes()
    private(runtime/'gateway-ca.crt',ca)
    private(runtime/'worker.token',payload['accessToken'])
    private(runtime/'credential-status.json',json.dumps({'expiresAtUnixMs':payload['expiresAtUnixMs']}))
    graph=dict(controlOrigin='https://localhost:18450',controlCaFile='/installation/gateway-ca.crt',
        readers={payload['tenantId']:'/installation/worker.token'},host='0.0.0.0',port=19460,
        certificate='/installation/server.crt',key='/installation/server.key',
        clientCa='/installation/ca.crt',clientNames=['workflow-control'])
    private(runtime/'graph.json',json.dumps(graph))
    private(runtime/'service.json',json.dumps(dict(graphConfiguration='/installation/graph.json',
        controlOrigin='https://localhost:18450',gatewayOrigin='https://localhost:18450',
        tokenFile='/installation/worker.token',caFile='/installation/gateway-ca.crt')))
    public=dict(releaseKeys=json.loads(Path('/data/keys/release-keys.json').read_text()),
        organizationKeys=json.loads(Path('/data/keys/organization-keys.json').read_text()))
    private(root/'device/trust.json',json.dumps(public),uid=0)


def main():
    if len(sys.argv)>1 and sys.argv[1]=='base':
        import re
        value=sys.stdin.read(256)
        if not re.fullmatch(r'toolgate/java-base@sha256:[a-f0-9]{64}',value):raise ValueError('Invalid base image')
        target=Path('/data/java-base.json')
        if target.exists() and json.loads(target.read_text())!=value:raise ValueError('Installation base differs; explicit fresh installation required')
        private(target,json.dumps(value));return
    if len(sys.argv)>1 and sys.argv[1]=='provision':
        provision(json.loads(sys.stdin.read(16385)));return
    initialize()
    for directory in ('/data','/registry'):
        path=Path(directory);os.chown(path,65532,65532);os.chmod(path,0o700)
    # Own only installation subdirectories, never device contents or Admin identity.
    private('/data/registry-config.yml',
        'version: 0.1\nlog:\n  level: error\nstorage:\n  filesystem:\n    rootdirectory: /registry\nhttp:\n  addr: 0.0.0.0:18552\n')
    os.setgroups([]);os.setgid(65532);os.setuid(65532)
    stop=threading.Event()
    signal.signal(signal.SIGTERM,lambda *_:stop.set());signal.signal(signal.SIGINT,lambda *_:stop.set())
    children=[]
    try:
        children.append(subprocess.Popen(['/usr/local/bin/distribution','serve','/data/registry-config.yml']))
        deadline=time.monotonic()+300
        while not Path('/data/java-base.json').exists():
            if children[0].poll() is not None:raise RuntimeError('Registry stopped')
            if stop.wait(.2):return
            if time.monotonic()>deadline:raise RuntimeError('Operator base-image provisioning required')
        env=dict(os.environ,TOOLGATE_CONTROL_CLIENT_IMAGES_BASE=json.loads(Path('/data/java-base.json').read_text()))
        children.append(subprocess.Popen(['/opt/quickstart-python/bin/python','/opt/quickstart/supervisor.py'],env=env))
        while not stop.wait(.2):
            if any(child.poll() is not None for child in children):raise RuntimeError('Quickstart component stopped')
    finally:
        for child in reversed(children):
            if child.poll() is None:child.terminate()
        deadline=time.monotonic()+30
        for child in reversed(children):
            try:child.wait(timeout=max(0,deadline-time.monotonic()))
            except subprocess.TimeoutExpired:child.kill();child.wait()


if __name__=='__main__':main()
