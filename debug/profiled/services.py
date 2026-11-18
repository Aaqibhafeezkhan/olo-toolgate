# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Local installation custody and supervised service entrypoints, never profile logic."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import sys
import time


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
        if json.loads(marker.read_text())!={'formatVersion':1}:raise ValueError('Unsupported installation custody')
        for name in ('graph/ca.crt','graph/server.crt','graph/server.key','control/identity.p12','control/password'):
            if not (root/name).is_file():raise ValueError('Incomplete installation custody; preserve and repair explicitly')
        return
    if any(path.name not in ('graph','control') or not path.is_dir() or any(path.iterdir()) for path in root.iterdir()):
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
    private(root/'graph/ca.crt',ca.public_bytes(serialization.Encoding.PEM))
    private(root/'graph/server.crt',server.public_bytes(serialization.Encoding.PEM))
    private(root/'graph/server.key',server_key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    private(root/'control/ca.crt',ca.public_bytes(serialization.Encoding.PEM))
    private(root/'control/password',password)
    private(root/'control/identity.p12',pkcs12.serialize_key_and_certificates(b'control',client_key,client,[ca],serialization.BestAvailableEncryption(password.encode())))
    private(marker,json.dumps(dict(formatVersion=1)),uid=0)


def identities():
    # Explicitly privileged local Quickstart issuer, not a production IdP.
    # Read-only source custody; no Docker socket or writes to Control's database.
    sys.path.insert(0,'/opt/quickstart')
    import supervisor as source
    root=Path('/custody')
    while True:
        try:
            tenant=source.tenant_identity()
            token=source.jwt(['toolgate-workflow-runtime'],'profiled-worker',directory_bound=False)
            operator=source.jwt(['toolgate-admin','toolgate-reader'],'admin')
            ca=(source.DATA/'keys/device-ca.crt').read_bytes()
            for directory in ('worker','graph','listener'):
                private(root/directory/'gateway-ca.crt',ca)
            private(root/'worker/worker.token',token)
            private(root/'graph/reader.token',token)
            private(root/'listener/listener.token',operator)
            public=dict(releaseKeys=json.loads((source.DATA/'keys/release-keys.json').read_text()),
                        organizationKeys=json.loads((source.DATA/'keys/organization-keys.json').read_text()))
            private(root/'device/trust.json',json.dumps(public),uid=0)
            config=dict(controlOrigin=os.environ['CONTROL_ORIGIN'],controlCaFile='/custody/graph/gateway-ca.crt',
                readers={tenant:'/custody/graph/reader.token'},host='0.0.0.0',port=19460,
                certificate='/custody/graph/server.crt',key='/custody/graph/server.key',
                clientCa='/custody/graph/ca.crt',clientNames=['workflow-control'])
            private(root/'graph/server.json',json.dumps(config))
        except Exception:
            print('Local workload credentials pending; no credentials logged',flush=True)
        time.sleep(60)


def serve(kind):
    required={'worker':'/custody/worker/worker.token','graph':'/custody/graph/server.json',
              'listener':'/custody/listener/listener.token'}[kind]
    deadline=time.monotonic()+180
    while not Path(required).exists():
        if time.monotonic()>deadline:raise ValueError('Installation credentials unavailable')
        time.sleep(1)
    python='/opt/toolgate-runtime/bin/python'
    commands={
        'graph':[python,'-m','toolgate_runtime.graph_server','--configuration',required],
        'worker':[python,'-m','toolgate_runtime.worker','--control-origin',os.environ['CONTROL_ORIGIN'],
            '--gateway-origin',os.environ['CONTROL_ORIGIN'],'--token-file',required,
            '--control-ca-file','/custody/worker/gateway-ca.crt','--gateway-ca-file','/custody/worker/gateway-ca.crt'],
        'listener':[python,'-m','toolgate_runtime.trigger_listener','--control-origin',os.environ['CONTROL_ORIGIN'],
            '--follow-subscriptions','--token-file',required,'--ca-file','/custody/listener/gateway-ca.crt']}
    os.execv(python,commands[kind])


if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='initialize':initialize()
    elif mode=='identities':identities()
    else:serve(mode)
