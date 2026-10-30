# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Single-node composition root. Child failure removes readiness and stops every process.

Only fixed local adapters run here: identity bootstrap, encrypted vault and HTTP
composition. Tools run only from verified packages: on the composed Tool Hosts, or on
enrolled client devices. No credential is emitted in logs.
"""
import argparse
import base64
from contextlib import closing
import datetime
import fcntl
import hashlib
import hmac
import http.client
import http.server
from http.cookies import SimpleCookie, CookieError
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import signal
import socket
import socketserver
import sqlite3
import ssl
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import uuid

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from enterprise_bootstrap import Refused, execution_binding, prepare, workload_context

DATA = Path('/data')
# Optional second volume: device CA and server TLS keys outlive a /data reset, so enrolled clients keep trusting it.
TRUST = Path('/trust')
TRUST_FILES = {'device-ca.crt': 'device-ca.crt', 'device-ca.key': 'device-ca.pem', 'server.crt': 'server.crt',
               'server.key': 'server-tls.pem', 'device-trust.p12': 'device-trust.p12'}
TENANT = None  # Set from protected, persisted UUID state before composition starts.
ISSUER = 'https://quickstart.local'
GROUPS = ()  # Human portal permissions derive only from the reviewed Team/Role graph.
# Standard installation logins; initialize-file users and claimed pool accounts are added by local_users().
BASE_USERS = ('admin', 'reviewer-1', 'reviewer-2', 'test-readonly', 'test-readwrite', 'test-admin')
CLAIM_PATH = '/api/quickstart/v1/demo-accounts/claim'
CREDENTIALS_LOCK = threading.RLock()
CLAIM_LOCK = threading.Lock()


class Forbidden(Exception):
    """Authenticated, but this account may not perform the operation."""


def trust_mounted():
    try: return any(line.split()[4] == str(TRUST) for line in Path('/proc/self/mountinfo').read_text().splitlines())
    except (OSError, IndexError): return False


def trust(name):
    """Path of a trust file: in /trust when that volume is mounted, otherwise in /data/keys as before."""
    return TRUST/name if trust_mounted() else DATA/'keys'/TRUST_FILES[name]


def installation():
    """Local installation state written by prepare(); installations made before it have none."""
    path = DATA/'state/installation.json'
    if not path.exists(): return dict(users=[], agents=[], pool=[], environment={}, maxPool=32, fixedCredentials=1)
    return strict(path.read_bytes())


def demo_accounts():
    path = DATA/'state/demo-accounts.json'
    return strict(path.read_bytes()) if path.exists() else {'accounts': {}}


def local_users():
    return BASE_USERS+tuple(user['id'] for user in installation()['users'])+tuple(demo_accounts()['accounts'])


def shown_on_login(user):
    return any(entry['id'] == user and entry['showOnLogin'] for entry in installation()['users'])


def password_disabled():
    value = os.environ.get('TOOLGATE_DISABLE_ADMIN_PASSWORD', 'false')
    if value not in ('true', 'false'): raise ValueError('Invalid password mode')
    return value == 'true'


def database_mode():
    mode = os.environ.get('TOOLGATE_QUICKSTART_DATABASE_MODE', 'sqlite')
    if mode not in ('sqlite', 'postgresql'): raise ValueError('Invalid database mode')
    return mode


class Database:
    """Same parameterized vault/audit operations with explicit external PG custody."""
    def __init__(self):
        self.pg = database_mode() == 'postgresql'
        if self.pg:
            import psycopg
            url = os.environ['QUARKUS_DATASOURCE_JDBC_URL']
            if not url.startswith('jdbc:postgresql://'): raise ValueError('PostgreSQL JDBC URL required')
            parsed = urllib.parse.urlsplit(url[5:])
            if parsed.username or parsed.password or parsed.fragment: raise ValueError('Separate DB credentials required')
            options = dict(urllib.parse.parse_qsl(parsed.query, strict_parsing=True))
            if set(options)-{'sslmode','sslrootcert'}: raise ValueError('Unsupported database option')
            if options.get('sslmode') not in ('verify-full','require','disable'): raise ValueError('Explicit database TLS mode required')
            self.connection = psycopg.connect(host=parsed.hostname, port=parsed.port or 5432,
                dbname=parsed.path.lstrip('/'), user=os.environ['QUARKUS_DATASOURCE_USERNAME'],
                password=os.environ['QUARKUS_DATASOURCE_PASSWORD'], connect_timeout=3,
                options='-c statement_timeout=3000', **options)
        else:
            self.connection = sqlite3.connect(DATA/'state/control.sqlite', timeout=3)
            self.connection.execute('PRAGMA foreign_keys=ON')
    def __enter__(self):
        # This helper owns one transaction for local identity reads/audit writes.
        if self.pg:
            try: self.connection.execute("SELECT set_config('app.tenant_id', %s, true)", (TENANT,))
            except Exception:
                self.connection.close()
                raise
        return self
    def __exit__(self, typ, value, trace):
        try:
            if typ: self.connection.rollback()
            else: self.connection.commit()
        finally: self.connection.close()
    def execute(self, sql, values=()):
        if self.pg:
            if sql == 'BEGIN IMMEDIATE':
                # Advisory transaction locks need no placeholder table and release on commit/rollback.
                return self.connection.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))',
                                               ('toolgate:quickstart-bootstrap:'+TENANT,))
            sql = sql.replace('?', '%s')
        return self.connection.execute(sql, values)
    def commit(self): self.connection.commit()


def strict(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value: raise ValueError('Duplicate field')
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))


def atomic(path, value):
    """Private files use exclusive temporary creation, fsync and an atomic rename."""
    path = Path(path)
    if path.is_symlink(): raise ValueError('Unsafe state path')
    temp = path.with_name(path.name + '.' + secrets.token_hex(8))
    try:
        with os.fdopen(os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
            output.write(value if isinstance(value, bytes) else value.encode())
            output.flush(); os.fsync(output.fileno())
        temp.replace(path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    finally: temp.unlink(missing_ok=True)


def password_valid(value):
    return isinstance(value, str) and 16 <= len(value) <= 128 and value.isascii() and all(32 < ord(c) < 127 for c in value) and len(set(value)) >= 8


def password_record(value, changed=True, generation=1):
    if not password_valid(value): raise ValueError('Password must be 16–128 printable ASCII characters with eight distinct characters')
    salt = secrets.token_bytes(32)
    return {'salt': salt.hex(), 'hash': hashlib.pbkdf2_hmac('sha256', value.encode(), salt, 600000).hex(), 'changed': changed, 'generation': generation}


def identity_path(user):
    if not isinstance(user, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', user) or user not in local_users(): raise PermissionError()
    return DATA/('identity.json' if user == 'admin' else 'identity-'+user+'.json')


def identity_audit(operation, generation, request_id, user='admin'):
    """Persist credential-change intent before changing custody; never audit verifier bytes."""
    path=DATA/'state/control.sqlite'
    if database_mode() == 'sqlite' and (not path.is_file() or path.is_symlink()): raise ValueError('Identity audit unavailable')
    with Database() as db:
        db.execute('INSERT INTO control_audit(tenant_id,actor_id,operation,target,revision,request_id,request_digest) VALUES(?,?,?,?,?,?,?)',
                   (TENANT,hashlib.sha256((ISSUER+'\n'+user).encode()).hexdigest(),operation,'local-identity:'+user,generation,request_id,
                    hashlib.sha256((operation+'\n'+str(generation)).encode()).hexdigest()))
        db.commit()


class LocalIdentity:
    """One composition-owned password writer; durable verifier/generation stay in custody."""
    def __init__(self): self.lock=threading.Lock()
    def authenticate(self, value, replacement=None, user='admin'):
        with self.lock:
            record = strict(identity_path(user).read_bytes())
            candidate = value if isinstance(value, str) and len(value) <= 128 else ''
            digest = hashlib.pbkdf2_hmac('sha256', candidate.encode(), bytes.fromhex(record['salt']), 600000).hex()
            if not hmac.compare_digest(digest, record['hash']): raise PermissionError()
            # No account must change its password at login. Shared accounts shown on the login page never may.
            if replacement is not None:
                if shown_on_login(user): raise Forbidden()
                if not password_valid(replacement) or replacement == value: raise ValueError('A different strong password is required')
                record = password_record(replacement, True, record['generation']+1)
                request_id=secrets.token_hex(16)
                identity_audit('PASSWORD_CHANGE_REQUESTED',record['generation'],request_id,user)
                atomic(identity_path(user), json.dumps(record))
                (DATA/('bootstrap-password' if user=='admin' else 'bootstrap-password-'+user)).unlink(missing_ok=True)
                identity_audit('PASSWORD_CHANGED',record['generation'],request_id,user)
            return jwt(GROUPS, user, generation=record['generation'])


# The Tool Host's device id: Control places server-tool calls on it, and the Gateway dispatches them there.
TOOL_HOST='tool-host-local'
# A second Tool Host the Gateway has no route to, like one on a laptop: it fetches its calls from Control. A deployment
# that names it runs there, so moving a tool between machines is a redeploy.
REMOTE_TOOL_HOST='tool-host-remote'


def key(name):
    kept = {'device-ca': 'device-ca.key', 'server-tls': 'server.key'}.get(name)
    path = trust(kept) if kept else DATA/'keys'/f'{name}.pem'
    if not path.exists():
        private = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        atomic(path, private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        if path.parent == DATA/'keys':
            atomic(path.with_name(name+'-public.pem'), private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    return serialization.load_pem_private_key(path.read_bytes(), password=None)


def jwt(groups, subject='admin', generation=None, directory_bound=True):
    global TENANT
    if TENANT is None: TENANT=tenant_identity()
    now = int(time.time())
    claims = {'iss': ISSUER, 'aud': 'toolgate-control', 'sub': subject, 'user_id': subject, 'tenant_id': TENANT, 'groups': groups, 'iat': now, 'exp': now+900}
    if not directory_bound: claims.pop('user_id')
    elif subject in local_users():
        with Database() as db:
            row=db.execute('SELECT CAST(document AS TEXT) FROM control_records WHERE tenant_id=? AND kind=? AND record_id=?',
                           (TENANT,'IDENTITY_BINDING','installed-'+subject)).fetchone()
        if not row: raise PermissionError()
        claims['session_epoch']=strict(row[0])['sessionEpoch']
    if generation is not None: claims['password_generation'] = generation
    encode = lambda value: base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    message = encode({'alg': 'RS256', 'typ': 'JWT'})+b'.'+encode(claims)
    signature = key('identity').sign(message, padding.PKCS1v15(), hashes.SHA256())
    return (message+b'.'+base64.urlsafe_b64encode(signature).rstrip(b'=')).decode()


def session(header):
    """Never accept machine tokens or stale password generations at the public proxy."""
    try:
        if not header.startswith('Bearer ') or len(header)>16384: raise ValueError()
        a, b, c = header[7:].split('.')
        decode = lambda text: base64.urlsafe_b64decode(text+'='*(-len(text)%4))
        if strict(decode(a)) != {'alg': 'RS256', 'typ': 'JWT'}: raise ValueError()
        key('identity').public_key().verify(decode(c), (a+'.'+b).encode(), padding.PKCS1v15(), hashes.SHA256())
        claims = strict(decode(b)); now = int(time.time())
        record = strict(identity_path(claims['sub']).read_bytes())
        if claims['iss'] != ISSUER or claims['aud'] != 'toolgate-control' or claims['tenant_id'] != TENANT or not claims['iat'] <= now < claims['exp'] <= claims['iat']+900 or claims.get('password_generation') != record['generation']: raise ValueError()
        return claims
    except Exception: raise PermissionError() from None


def certificates():
    """Device CA, server TLS certificate and trust store; with /trust mounted they survive a /data reset."""
    if not trust('device-trust.p12').exists():
        issue()
    if trust_mounted():  # Public copies keep /data/keys/device-ca.crt the one place local tools read the CA from.
        for name in ('device-ca.crt', 'server.crt'): atomic(DATA/'keys'/name, trust(name).read_bytes())


def tenant_certificates(tenant):
    """Independent discovery and device signing custody; the original root is TLS-only."""
    root=DATA/'tenant-identity'
    packet=root/'publication.json'
    if root.is_symlink(): raise ValueError('Unsafe tenant identity custody')
    root.mkdir(exist_ok=True,mode=0o700);root.chmod(0o700)
    if not packet.exists():
        # A partial set is refused, never silently replaced after a failed bring-up.
        if any(root.iterdir()): raise ValueError('Incomplete tenant identity custody')
        now=datetime.datetime.now(datetime.timezone.utc);references=[]
        for purpose in ('DISCOVERY','DEVICE_CA'):
            private=rsa.generate_private_key(public_exponent=65537,key_size=3072)
            reference=dict(purpose=purpose,keyId='discovery-1',state='ACTIVE')
            if purpose=='DEVICE_CA':
                name=x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME,'ToolGate tenant device CA'),x509.NameAttribute(x509.NameOID.ORGANIZATIONAL_UNIT_NAME,tenant)])
                certificate=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(private.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True).add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False),critical=True).sign(private,hashes.SHA256())
                reference.update(keyId=hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest(),certificatePem=certificate.public_bytes(serialization.Encoding.PEM).decode())
            directory=root/tenant/purpose;directory.mkdir(parents=True,mode=0o700)
            atomic(directory/(reference['keyId']+'.pk8.pem'),private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
            references.append(reference)
        atomic(packet,json.dumps(dict(formatVersion=1,publications=[dict(tenantId=tenant,generation=1,keys=references)])))
    value=strict(packet.read_bytes())
    publication=value['publications']
    if value['formatVersion']!=1 or len(publication)!=1 or publication[0]['tenantId']!=tenant: raise ValueError('Tenant identity publication mismatch')
    # Rebuild the public client trust store from retained tenant CAs before opening TLS.
    # No TLS issuer is trusted to authenticate a device.
    store=root/'device-trust.p12';partial=root/'device-trust.p12.partial';partial.unlink(missing_ok=True)
    for reference in publication[0]['keys']:
        if reference['purpose']!='DEVICE_CA' or reference['state'] not in ('ACTIVE','READ_ONLY'): continue
        certificate=root/(reference['keyId']+'.crt');atomic(certificate,reference['certificatePem'])
        subprocess.run(['keytool','-importcert','-noprompt','-alias',reference['keyId'],'-file',str(certificate),'-keystore',str(partial),'-storetype','PKCS12','-storepass','changeit'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if not partial.exists(): raise ValueError('Tenant client trust required')
    partial.replace(store)


def issue():
    now = datetime.datetime.now(datetime.timezone.utc)
    ca_key = key('device-ca'); server_key = key('server-tls')
    name = x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, 'ToolGate Quickstart Device CA')])
    ca = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True).add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True).add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False).sign(ca_key, hashes.SHA256())
    server = x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, 'localhost')])).issuer_name(name).public_key(server_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=365)).add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True).add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'), x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False).add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False).add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False).add_extension(x509.SubjectKeyIdentifier.from_public_key(server_key.public_key()), critical=False).sign(ca_key, hashes.SHA256())
    atomic(trust('device-ca.crt'), ca.public_bytes(serialization.Encoding.PEM))
    atomic(trust('server.crt'), server.public_bytes(serialization.Encoding.PEM))
    store = trust('device-trust.p12'); partial = store.with_name(store.name+'.partial'); partial.unlink(missing_ok=True)
    subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'device-ca', '-file', str(trust('device-ca.crt')), '-keystore', str(partial), '-storetype', 'PKCS12', '-storepass', 'changeit'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    partial.replace(store)  # The trust store is written last: it marks the set complete.


def initialize():
    if (DATA/'restore.pending').exists(): raise ValueError('Unfinished restore; recover into a new empty data volume')
    password_disabled(); mode = database_mode()
    os.umask(0o077)
    DATA.mkdir(exist_ok=True)
    if DATA.is_symlink() or DATA.stat().st_uid != os.getuid(): raise ValueError('Private service-owned /data required')
    DATA.chmod(0o700)
    if trust_mounted():
        if TRUST.is_symlink() or TRUST.stat().st_uid != os.getuid(): raise ValueError('Private service-owned /trust required')
        TRUST.chmod(0o700)
    for name in ('keys', 'run', 'state', 'artifacts'):
        path = DATA/name
        if path.is_symlink(): raise ValueError('Unsafe local layout')
        path.mkdir(exist_ok=True); path.chmod(0o700)
    layout = DATA/'layout.json'
    if layout.exists() and strict(layout.read_bytes()) != {'layoutVersion': 1}: raise ValueError('Unsupported local layout; restore or use a compatible image')
    if not layout.exists(): atomic(layout, '{"layoutVersion":1}')
    storage = DATA/'storage.json'
    previous = strict(storage.read_bytes())['mode'] if storage.exists() else ('sqlite' if (DATA/'state/control.sqlite').exists() else mode)
    if previous != mode: raise ValueError('Use separate /data when changing database backend; migration is explicit')
    if not storage.exists(): atomic(storage, json.dumps({'mode': mode}))
    if not (DATA/'identity.json').exists():
        supplied = os.environ.pop('TOOLGATE_BOOTSTRAP_PASSWORD', None)
        password = supplied if supplied is not None else secrets.token_urlsafe(32)
        atomic(DATA/'identity.json', json.dumps(password_record(password)))
        if supplied is None: atomic(DATA/'bootstrap-password', password+'\n')
    else: os.environ.pop('TOOLGATE_BOOTSTRAP_PASSWORD', None)  # Existing identity is never reset by startup settings.
    for user in BASE_USERS[1:]:
        if not identity_path(user).exists():
            password=secrets.token_urlsafe(32)
            atomic(identity_path(user),json.dumps(password_record(password)))
            atomic(DATA/('bootstrap-password-'+user),password+'\n')
    for name in ('identity', 'policy', 'permit', 'device-ca', 'server-tls', 'fleet', 'package-release'): key(name)
    if (DATA/'keys/vault.key').exists(): raise ValueError('Unsupported raw vault key; select a fresh empty data directory')
    if not (DATA/'keys/vault-wrapping.json').exists():
        atomic(DATA/'keys/vault-wrapping.json', json.dumps(dict(formatVersion=1,currentKeyId='local-1',keys={'local-1':base64.b64encode(secrets.token_bytes(32)).decode()})))
        (DATA/'keys/vault-wrapping.json').chmod(0o600)
    certificates()
    fleet_keys()


def dependencies_ready():
    # A live dependency may time out during an outage. Clear readiness while
    # retaining the processes and sockets so online authorization can deny and recover.
    try:
        return call('/q/health/ready',port=9092)[0]==200 and call('/v1/health/ready',port=9091)[0]==200
    except (OSError, http.client.HTTPException):
        return False


def origin_target(value):
    """Only origin-form targets may reach the fixed loopback upstream."""
    if not isinstance(value, str) or not value.startswith('/') or value.startswith('//') or any(ord(c)<32 or ord(c)==127 for c in value) or '\\' in value:
        raise ValueError('Invalid local request target')
    target = urllib.parse.urlsplit(value)
    if target.scheme or target.netloc or target.fragment:
        raise ValueError('Invalid local request target')
    return urllib.parse.urlunsplit(('', '', target.path, target.query, ''))


def call(path, token=None, body=None, port=8082, secure=False, method=None, extra_headers=None):
    client = http.client.HTTPSConnection('127.0.0.1', port, timeout=10, context=ssl.create_default_context(cafile=str(DATA/'keys/device-ca.crt'))) if secure else http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    headers = {'Content-Type': 'application/json', 'X-Request-ID': secrets.token_hex(16)}
    if token: headers['Authorization'] = 'Bearer '+token
    if body is not None: headers['Idempotency-Key'] = secrets.token_hex(16)
    if extra_headers: headers.update(extra_headers)
    try:
        client.request(method or ('POST' if body is not None else 'GET'), origin_target(path), json.dumps(body).encode() if body is not None else None, headers)
        response = client.getresponse(); raw = response.read(2*1024*1024+1)
        if len(raw)>2*1024*1024: raise ValueError()
        return response.status, raw, dict(response.getheaders())
    finally: client.close()


def client_credentials():
    """Administrator-installed workload facts; grants and credential epochs remain in Control."""
    if os.environ.get('TOOLGATE_QUICKSTART_CLIENT_CREDENTIALS', 'false') != 'true': return []
    path = DATA/'client-runtime-credentials.json'
    if not path.exists(): return []
    metadata = path.stat()
    if path.is_symlink() or not path.is_file() or metadata.st_size > 65536 or metadata.st_mode & 0o077 or metadata.st_uid not in (0, os.geteuid()): raise ValueError('Unsafe client credential file')
    values = strict(path.read_bytes())
    if not isinstance(values, list) or len(values)>credential_cap(): raise ValueError('Invalid workload credentials')
    now = int(time.time()*1000)
    for value in values:
        if not isinstance(value,dict) or set(value)!={'tokenSha256','context','expiresAtUnixMs'} or not isinstance(value['expiresAtUnixMs'],int) or isinstance(value['expiresAtUnixMs'],bool): raise ValueError('Invalid workload credentials')
        context=value['context']
        if not isinstance(context,dict) or context.get('tenantId')!=TENANT or context.get('mode') not in ('SERVICE','DELEGATED') or context.get('credentialSha256')!=value['tokenSha256']: raise ValueError('Invalid workload identity')
    return [value for value in values if value['expiresAtUnixMs']>now]


def credential_cap():
    """Largest pool the record budget allows plus every fixed credential; the Gateway accepts at most 256."""
    state = installation()
    return min(256, state['maxPool']+state['fixedCredentials'])


def workload_credentials():
    """Live agent token hashes and Gateway contexts by workload binding. Raw tokens are never kept here."""
    path = DATA/'state/workload-credentials.json'
    return strict(path.read_bytes()) if path.exists() else {}


def private(path, value):
    atomic(path, json.dumps(value)); Path(path).chmod(0o600)


def accounts():
    """Logins for initialize-file users and the one private credentials file. Idempotent on every start."""
    state = installation(); path = DATA/'credentials.json'
    saved = strict(path.read_bytes()) if path.exists() else None
    known = {user['username']: user['password'] for user in saved['users']} if saved else {}
    users, created = [], {}
    for user in BASE_USERS[:3]:
        bootstrap = DATA/('bootstrap-password' if user == 'admin' else 'bootstrap-password-'+user)
        users.append(dict(username=user, password=bootstrap.read_text().strip() if bootstrap.exists() else None, showOnLogin=False))
    for entry in state['users']:
        password = known.get(entry['id']) or secrets.token_urlsafe(32)
        users.append(dict(username=entry['id'], password=password, showOnLogin=entry['showOnLogin']))
        if not identity_path(entry['id']).exists(): created[entry['id']] = password
    # Written before the login records, so a restart after a crash reuses exactly these passwords.
    if saved is None:
        private(path, dict(users=users, agents=[dict(agentId=agent['agentId'], workloadBindingId=agent['workloadBindingId'],
                token=agent.get('token'), userId=agent['userId']) for agent in state['agents']], claimToken=secrets.token_urlsafe(32)))
    for user, password in created.items(): atomic(identity_path(user), json.dumps(password_record(password)))
    stored = workload_credentials()
    for agent in state['agents']:
        stored.setdefault(agent['workloadBindingId'], dict(tokenSha256=agent['context']['credentialSha256'], context=agent['context']))
    private(DATA/'state/workload-credentials.json', stored)
    if any('token' in agent for agent in state['agents']):
        for agent in state['agents']: agent.pop('token', None)
        private(DATA/'state/installation.json', state)


def gateway_credentials():
    """Write the Gateway's whole credential set atomically; returns the digest of the exact bytes written."""
    with CREDENTIALS_LOCK:
        digest = hashlib.sha256((DATA/'run/runtime-token').read_text().encode()).hexdigest()
        expires = int(time.time()*1000)+86400000
        # This disabled workload is an authentication identity only. Installation creates no workload grants.
        # No device in the context: Control places each call on a device of binding-default's group.
        context = dict(requestId='local-workload',tenantId=TENANT,mode='SERVICE',credentialKind='STATIC_TOKEN',agentId='agent-local',
                       workloadBindingId='workload-local',chain=[],credentialEpoch=1,bindingId='binding-default',
                       credentialSha256=digest)
        credentials = [dict(tokenSha256=digest,context=context,expiresAtUnixMs=expires)]
        credentials.extend(dict(tokenSha256=value['tokenSha256'],context=value['context'],expiresAtUnixMs=expires)
                           for value in workload_credentials().values())
        credentials.extend(client_credentials())
        if len(credentials) > credential_cap(): raise ValueError('Gateway credential cap exceeded')
        raw = json.dumps(credentials).encode()
        atomic(DATA/'run/credentials.json', raw)
        return hashlib.sha256(raw).hexdigest()


def install(signal_gateway, timeout=15):
    """Write the credential set, signal the Gateway and wait until its management listener reports that file."""
    with CREDENTIALS_LOCK:
        digest = gateway_credentials(); deadline = time.monotonic()+timeout; again = 0
        while time.monotonic() < deadline:
            try:
                status, raw, _ = call('/v1/credentials', port=9091)
                if status == 200 and strict(raw).get('sha256') == digest: return
                # A Gateway that answers has its SIGHUP handler installed, so the signal reloads and never stops it.
                if status == 200 and time.monotonic() >= again: signal_gateway(); again = time.monotonic()+1
            except (OSError, http.client.HTTPException, ValueError): pass
            time.sleep(.05)
        raise TimeoutError('Gateway did not confirm the credential reload')


def admin_token():
    """A short-lived session of the installation's Super Admin, for the supervisor's own Control calls."""
    return jwt(GROUPS, 'admin', generation=strict((DATA/'identity.json').read_bytes())['generation'])


def control(path, token, body=None):
    status, raw, _ = call(path, token, body)
    if status not in (200, 201): raise RuntimeError('Control refused the request')
    return strict(raw) if raw else {}


def rotate(binding_id, token, actor, execution=None):
    """Control stores the new token's hash and epoch and enables the binding; the Gateway context follows it."""
    digest = hashlib.sha256(token.encode()).hexdigest()
    reply = control('/api/control/v1/workload-bindings/'+urllib.parse.quote(binding_id, safe='')+'/credential', actor,
                    {'credentialSha256': digest, 'enable': True})
    binding = reply['binding'] if isinstance(reply.get('binding'), dict) else reply
    if binding.get('id') != binding_id or binding.get('credentialSha256') != digest: raise RuntimeError('Unexpected credential rotation')
    supplied = reply['context'] if isinstance(reply.get('context'), dict) else {}
    context = workload_context(TENANT, binding, supplied.get('bindingId') or execution)
    with CREDENTIALS_LOCK:
        stored = workload_credentials(); stored[binding_id] = dict(tokenSha256=digest, context=context)
        private(DATA/'state/workload-credentials.json', stored)


def refresh_at():
    return os.environ.get('TOOLGATE_DEMO_REFRESH_AT', '').strip() or None


def claim_account(requester, signal_gateway):
    """Hand one pool account to a requester with a fresh password and agent token; a repeat claim revokes the old ones."""
    pool = {entry['userId']: entry for entry in installation()['pool']}
    with CLAIM_LOCK:
        state = demo_accounts(); claimed = state['accounts']
        user = next((name for name, value in claimed.items() if hmac.compare_digest(value['requester'].encode(), requester.encode())), None)
        if user is None:
            user = next((name for name in pool if name not in claimed), None)
            if user is None: return None
            # Recorded before any credential exists: no account goes to a second requester in this volume's life.
            claimed[user] = dict(requester=requester, claimedAtUnixMs=int(time.time()*1000), enabled=False)
            private(DATA/'state/demo-accounts.json', state)
        admin = admin_token()
        if not claimed[user]['enabled']:
            status, _, _ = call('/api/control/v1/users/'+urllib.parse.quote(user, safe='')+'/enable', admin, {})
            if status not in (200, 204, 409): raise RuntimeError('Control refused to enable the account')
            claimed[user]['enabled'] = True; private(DATA/'state/demo-accounts.json', state)
        password, token = secrets.token_urlsafe(32), secrets.token_urlsafe(48)
        path = identity_path(user)
        generation = strict(path.read_bytes())['generation']+1 if path.exists() else 1
        identity_audit('PASSWORD_ISSUED', generation, secrets.token_hex(16), user)
        atomic(path, json.dumps(password_record(password, True, generation)))  # Ends every earlier session.
        rotate(pool[user]['workloadBindingId'], token, admin, pool[user]['context'].get('bindingId'))
        install(signal_gateway)  # The previous token leaves the Gateway in this same reload.
        gateway = os.environ.get('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL', 'https://localhost:8443').rstrip('/')+'/mcp'
        return dict(username=user, password=password, agentToken=token,
                    consoleUrl=os.environ.get('TOOLGATE_PUBLIC_CONSOLE_URL', '').strip() or 'http://localhost:8080/console/',
                    gatewayUrl=os.environ.get('TOOLGATE_PUBLIC_MCP_URL', '').strip() or gateway, expiresAt=refresh_at())


def reauthorize(binding_id, actor, signal_gateway):
    """An authorized administrator issues a fresh token for one workload binding; it is returned once and stored
    only for the agent the login page shows (login_hint_agent)."""
    status, raw, _ = call('/api/control/v1/admin-session', actor)
    if status != 200 or 'workload_binding.rotate_credential' not in strict(raw).get('permissions', []): raise Forbidden()
    stored = workload_credentials().get(binding_id)
    execution = stored['context'].get('bindingId') if stored else None
    if execution is None:
        snapshot = control('/api/control/v1/config/export', actor)
        snapshot = snapshot['snapshot'] if isinstance(snapshot.get('snapshot'), dict) else snapshot
        agent = next(row['agentId'] for row in snapshot.get('workloadBindings', []) if row['id'] == binding_id)
        execution = execution_binding(snapshot, agent)
    token = secrets.token_urlsafe(48)
    rotate(binding_id, token, actor, execution)
    install(signal_gateway)
    # The login page must never show a token the Gateway no longer accepts.
    path, hinted = DATA/'credentials.json', login_hint_agent()
    if hinted and path.exists():
        saved = strict(path.read_bytes())
        matches = [agent for agent in saved.get('agents', []) if agent['workloadBindingId'] == binding_id and agent['agentId'] == hinted]
        for agent in matches: agent['token'] = token
        if matches: private(path, saved)
    return token


def login_hint_agent():
    """The agent whose token the login page shows with the shared demo logins, or None for none."""
    return os.environ.get('TOOLGATE_LOGIN_HINT_AGENT', '').strip() or None


def login_hints():
    """Shared demo logins only (showOnLogin), each with the shared agent token when TOOLGATE_LOGIN_HINT_AGENT names one;
    administrator and reviewer passwords are never listed."""
    path = DATA/'credentials.json'
    saved = strict(path.read_bytes()) if path.exists() else {}
    hinted = login_hint_agent()
    token = next((agent.get('token') for agent in saved.get('agents', []) if hinted and agent['agentId'] == hinted), None)
    return {'accounts': [dict(username=user['username'], password=user['password'], **({'agentToken': token} if token else {}))
                         for user in saved.get('users', []) if user['showOnLogin']],
            'refreshAt': refresh_at()}


def tenant_identity():
    """A fresh composition allocates its UUID once; aliases never become identity claims."""
    path=DATA/'state/tenant.json'
    alias=os.environ.get('TOOLGATE_DEFAULT_TENANT_ALIAS','quickstart')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,62}',alias): raise ValueError('Invalid default tenant alias')
    if path.is_symlink(): raise ValueError('Unsafe tenant state')
    if path.exists():
        value=strict(path.read_bytes())
        if set(value)!={'formatVersion','alias','tenantId'} or value['formatVersion']!=1 or value['alias']!=alias: raise ValueError('Invalid or conflicting tenant state')
        if str(uuid.UUID(value['tenantId']))!=value['tenantId']: raise ValueError('Canonical UUID tenant state required')
        return value['tenantId']
    if (DATA/'state/control.sqlite').exists() or (DATA/'installation-review.json').exists():
        raise ValueError('Unsupported development state; use a new empty data directory')
    tenant=str(uuid.uuid4())
    atomic(path,json.dumps(dict(formatVersion=1,alias=alias,tenantId=tenant)))
    path.chmod(0o600)
    return tenant


def configure():
    global TENANT
    TENANT = tenant_identity()
    tenant_certificates(TENANT)
    runtime_path=DATA/'run/runtime-token'
    if not runtime_path.exists(): atomic(runtime_path,secrets.token_urlsafe(48))
    packet, review_trust = prepare(DATA,TENANT,ISSUER,key,atomic)
    accounts()
    gateway_credentials()
    config = {'listen':'127.0.0.1:8081','managementListen':'127.0.0.1:9091',
              'trustedTlsProxy':False,'allowedOrigins':[],'tenantHosts':['127.0.0.1:8081'],
              'publicDiscovery':dict(controlOrigin=os.environ.get('TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL','https://localhost:8443'),gatewayOrigin=os.environ.get('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL','https://localhost:8443')),
              'limits':{'maxBodyBytes':65536,'maxHeaderBytes':8192,'maxConcurrentRequests':128,
                        'maxConnections':256,'requestsPerSecond':1000,'requestTimeoutMs':20000,
                        'connectionTimeoutMs':35000,'shutdownTimeoutMs':35000,'auditQueueCapacity':256},
              'control':{'tenantId':TENANT,'url':'http://127.0.0.1:8082','caCertificatePath':str(DATA/'keys/device-ca.crt'),'tokenPath':'/data/run/machine-token',
                         'developmentLoopbackHttp':True},
              'toolHosts':[{'hostId':TOOL_HOST,'url':'http://127.0.0.1:8095'}]}
    atomic(DATA/'run/gateway.json',json.dumps(config))
    # The Tool Host enrolls as a device like any client; an administrator approves it before it runs packages.
    atomic(DATA/'run/tool-host.json',json.dumps(dict(hostId=TOOL_HOST,listen='127.0.0.1:8095',
           permitKeys=[dict(kid='effect-local',publicKeyPath=str(DATA/'keys/permit-public.pem'))],permitIssuer='control',
           storeDir=str(DATA/'tool-host/store'),workDir=str(DATA/'tool-host/work'),
           control=dict(tenantId=TENANT,url=tool_host_control_url(DATA/'state/tool-host'),caCertificatePath=str(DATA/'keys/device-ca.crt')),
           enrollment=dict(identityDir=str(DATA/'state/tool-host')),runtimes=dict(javaPath='java'))))
    atomic(DATA/'run/tool-host-remote.json',json.dumps(dict(hostId=REMOTE_TOOL_HOST,listen='127.0.0.1:8096',
           permitKeys=[dict(kid='effect-local',publicKeyPath=str(DATA/'keys/permit-public.pem'))],permitIssuer='control',
           storeDir=str(DATA/'tool-host-remote/store'),workDir=str(DATA/'tool-host-remote/work'),
           control=dict(tenantId=TENANT,url=tool_host_control_url(DATA/'state/tool-host-remote'),caCertificatePath=str(DATA/'keys/device-ca.crt')),
           enrollment=dict(identityDir=str(DATA/'state/tool-host-remote')),runtimes=dict(javaPath='java'))))
    machine()
    env = dict(os.environ)
    settings = {'TOOLGATE_QUICKSTART_ENABLED': 'true', 'TOOLGATE_QUICKSTART_DATABASE': '/data/state/control.sqlite',
                'QUARKUS_PROFILE':'quickstart', 'QUARKUS_FLYWAY_ACTIVE':'false', 'QUARKUS_DATASOURCE_ACTIVE': 'false', 'QUARKUS_DATASOURCE_HEALTH_ENABLED': 'false', 'QUARKUS_FLYWAY_MIGRATE_AT_START': 'false',
                'QUARKUS_HTTP_HOST': '0.0.0.0', 'QUARKUS_HTTP_PORT': '8082', 'QUARKUS_HTTP_SSL_PORT': '8443',
                'QUARKUS_HTTP_INSECURE_REQUESTS': 'enabled', 'QUARKUS_MANAGEMENT_HOST': '127.0.0.1',
                'QUARKUS_HTTP_SSL_CERTIFICATE_FILES': str(trust('server.crt')), 'QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES': str(trust('server.key')),
                'QUARKUS_HTTP_SSL_CERTIFICATE_TRUST_STORE_FILE': str(DATA/'tenant-identity/device-trust.p12'), 'QUARKUS_HTTP_SSL_CERTIFICATE_TRUST_STORE_PASSWORD': 'changeit',
                'TOOLGATE_CONTROL_IDENTITY_CUSTODY_ROOT': str(DATA/'tenant-identity'),
                'TOOLGATE_CONTROL_IDENTITY_PUBLICATION_PATH': str(DATA/'tenant-identity/publication.json'),
                'MP_JWT_VERIFY_ISSUER': ISSUER, 'MP_JWT_VERIFY_AUDIENCES': 'toolgate-control', 'MP_JWT_VERIFY_PUBLICKEY_LOCATION': '/data/keys/identity-public.pem',
                'TOOLGATE_CONTROL_BUNDLE_ENABLED': 'true', 'TOOLGATE_CONTROL_BUNDLE_KEY_ID': 'policy-local', 'TOOLGATE_CONTROL_BUNDLE_PRIVATE_KEY_PATH': '/data/keys/policy.pem',
                'TOOLGATE_QUICKSTART_ARTIFACT_DIRECTORY': '/data/artifacts',
                'TOOLGATE_CONTROL_FLEET_ENABLED': 'true', 'TOOLGATE_CONTROL_FLEET_KEY_ID': 'fleet-local',
                'TOOLGATE_CONTROL_FLEET_PRIVATE_KEY_PATH': '/data/keys/fleet.pem',
                'TOOLGATE_CONTROL_FLEET_RELEASE_KEYS_PATH': '/data/keys/release-keys.json',
                'TOOLGATE_CONTROL_FLEET_ORGANIZATION_KEYS_PATH': '/data/keys/organization-keys.json',
                # The package-release authority signs client releases (ADR 026); it is never the organization key.
                'TOOLGATE_CONTROL_PACKAGE_RELEASE_PRIVATE_KEY_PATH': '/data/keys/package-release.pem', 'TOOLGATE_CONTROL_PACKAGE_RELEASE_KEY_ID': 'package-local',
                'TOOLGATE_CONTROL_FLEET_ARTIFACT_ORIGIN': env.get('TOOLGATE_CONTROL_FLEET_ARTIFACT_ORIGIN', 'https://localhost:8443/artifacts'),
                'TOOLGATE_CONTROL_FLEET_ARTIFACT_CA_PATH': env.get('TOOLGATE_CONTROL_FLEET_ARTIFACT_CA_PATH', '/data/keys/device-ca.crt'),
                'TOOLGATE_CONTROL_EFFECT_ENABLED':'true', 'TOOLGATE_CONTROL_EFFECT_PRIVATE_KEY_PATH':'/data/keys/permit.pem',
                'TOOLGATE_CONTROL_EFFECT_KEY_ID':'effect-local','TOOLGATE_CONTROL_EFFECT_ISSUER':'control',
                'TOOLGATE_CONTROL_EFFECT_AUDIENCE':'toolgate-client',
                'TOOLGATE_CONTROL_VAULT_WRAPPING_KEY_RING_PATH':'/data/keys/vault-wrapping.json',
                'TOOLGATE_CONTROL_RECOVERY_PACKET_PATH':env.get('TOOLGATE_CONTROL_RECOVERY_PACKET_PATH',str(packet)),'TOOLGATE_CONTROL_RECOVERY_TRUST_PATH':env.get('TOOLGATE_CONTROL_RECOVERY_TRUST_PATH',str(review_trust)),
                'TOOLGATE_CONTROL_ENDPOINT_ENABLED': 'true',
                # Quickstart check-in defaults (platform-requirements.md §17.2); the enterprise defaults are 2000 ms and 20 % jitter.
                'TOOLGATE_CONTROL_CLIENT_CHECKIN_MS': env.get('TOOLGATE_CONTROL_CLIENT_CHECKIN_MS') or '2000',
                'TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT': env.get('TOOLGATE_CONTROL_CLIENT_CHECKIN_JITTER_PCT') or '0',
                'TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS': env.get('TOOLGATE_CONTROL_CLIENT_OFFLINE_AFTER_MS') or '120000',
                'TOOLGATE_CONTROL_ENDPOINT_PRIVATE_KEY_PATH': str(trust('device-ca.key')), 'TOOLGATE_CONTROL_ENDPOINT_CA_CERTIFICATE_PATH': str(trust('device-ca.crt')),
                'TOOLGATE_DEFAULT_TENANT_ALIAS': os.environ.get('TOOLGATE_DEFAULT_TENANT_ALIAS','quickstart'), 'TOOLGATE_CONTROL_ENDPOINT_SERVER_ID': 'quickstart-server',
                'TOOLGATE_CONTROL_ENDPOINT_ORGANIZATION': installation().get('tenantName', 'Quickstart'),
                'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL': env.get('TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL', 'https://localhost:8443'),
                'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL': env.get('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL', 'https://localhost:8443'),
                'TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY': env.get('TOOLGATE_CLIENT_DOWNLOADS_DIRECTORY','/opt/toolgate/client-downloads')}
    if (DATA/'state/initialize-snapshot.json').exists(): settings['TOOLGATE_CONTROL_INITIALIZE_PATH']=str(DATA/'state/initialize-snapshot.json')
    if database_mode() == 'postgresql':
        for name in ('QUARKUS_DATASOURCE_JDBC_URL','QUARKUS_DATASOURCE_USERNAME','QUARKUS_DATASOURCE_PASSWORD','QUARKUS_FLYWAY_USERNAME','QUARKUS_FLYWAY_PASSWORD'):
            if not env.get(name): raise ValueError('External database settings required')
        settings.update(TOOLGATE_QUICKSTART_STORAGE='postgresql', QUARKUS_FLYWAY_ACTIVE='true',
            QUARKUS_DATASOURCE_ACTIVE='true', QUARKUS_DATASOURCE_HEALTH_ENABLED='true', QUARKUS_FLYWAY_MIGRATE_AT_START='true')
    # Initialize-file server settings seed Control; an explicit container value always wins.
    settings.update({name: value for name, value in installation()['environment'].items() if not env.get(name)})
    env.update(settings)
    return env


def tool_host_control_url(identity_dir):
    """Use the signed published origin, including its port, for local Tool Host discovery."""
    url = os.environ.get('TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL', 'https://localhost:8443').rstrip('/')
    path = Path(identity_dir)/'device.json'
    if path.exists() and not path.is_symlink():
        state = strict(path.read_bytes())
        # Earlier compositions wrote the internal port before ever verifying discovery.
        # Only an unbound journal may adopt the operator-configured published origin;
        # established trust, pending approvals, keys and identity facts remain untouched.
        if (url != 'https://localhost:8443' and state.get('formatVersion') == 3
                and state.get('tenantId') == TENANT and state.get('serverUrl') == 'https://localhost:8443'
                and state.get('sequence') == 0
                and all(state.get(name) is None for name in ('manifest', 'discoveryTrust', 'identity', 'challenge'))):
            state['serverUrl'] = url
            private(path, state)
    return url

def fleet_keys():
    """Persistent disjoint package release and desired-state trust domains."""
    for name, kid, filename in (('fleet', 'fleet-local', 'organization-keys.json'),
                                ('package-release', 'package-local', 'release-keys.json')):
        numbers = key(name).public_key().public_numbers()
        encode = lambda value: base64.urlsafe_b64encode(value.to_bytes((value.bit_length()+7)//8, 'big')).rstrip(b'=').decode()
        atomic(DATA/'keys'/filename, json.dumps([{'kid': kid, 'n': encode(numbers.n), 'e': encode(numbers.e)}]))


def machine():
    atomic(DATA/'run/machine-token',jwt(['toolgate-relay-gateway'],'gateway',directory_bound=False))


class TlsLoopbackBridge(socketserver.ThreadingTCPServer):
    """Forward unchanged TLS bytes to the local listener, retaining the published TLS authority."""
    daemon_threads=True
    allow_reuse_address=True
    def __init__(self,port):
        if not 1024<=port<=65535 or port in (8080,8081,8082,8083,9091,9092): raise ValueError('Unsafe loopback TLS port')
        super().__init__(('127.0.0.1',port),TlsBridgeHandler)
        self.capacity=threading.BoundedSemaphore(32)
    def process_request(self,request,address):
        if not self.capacity.acquire(False): request.close();return
        try: super().process_request(request,address)
        except Exception: self.capacity.release();raise
    def process_request_thread(self,request,address):
        try: super().process_request_thread(request,address)
        finally: self.capacity.release()


class TlsBridgeHandler(socketserver.BaseRequestHandler):
    def handle(self):
        import select
        with socket.create_connection(('127.0.0.1',8443),timeout=5) as upstream:
            self.request.settimeout(15);upstream.settimeout(15)
            deadline=time.monotonic()+35
            while time.monotonic()<deadline:
                readable,_,_=select.select([self.request,upstream],[],[],1)
                for source in readable:
                    data=source.recv(32768)
                    if not data:return
                    (upstream if source is self.request else self.request).sendall(data)


def tls_bridges():
    bridges=[]
    origins={os.environ.get('TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL','https://localhost:8443'),
             os.environ.get('TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL','https://localhost:8443')}
    for origin in origins:
        parsed=urllib.parse.urlsplit(origin)
        if parsed.scheme!='https' or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/'): raise ValueError('Exact HTTPS origin required')
        if parsed.hostname in ('localhost','127.0.0.1') and parsed.port and parsed.port!=8443:
            if any(server.server_address[1]==parsed.port for server in bridges):continue
            server=TlsLoopbackBridge(parsed.port);threading.Thread(target=server.serve_forever,daemon=True).start();bridges.append(server)
    return bridges


class BoundedServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, handler, ready):
        super().__init__(address,handler)
        self.ready=ready
        self.capacity=threading.BoundedSemaphore(16)
        self.identity=LocalIdentity()
        self.login_lock=threading.Lock()
        self.login_times=[]
        self.signal_gateway=lambda: None  # main() sends SIGHUP to the running Gateway.
    def process_request(self, request, address):
        if not self.capacity.acquire(False): request.close(); return
        try: super().process_request(request, address)
        except Exception: self.capacity.release(); raise
    def process_request_thread(self, request, address):
        try: super().process_request_thread(request, address)
        finally: self.capacity.release()


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = 'ToolGate'
    def log_message(self, *_): pass
    def setup(self):
        super().setup(); self.connection.settimeout(10)
    def reply(self, status, body, kind='application/json', headers=None):
        request_id=(headers or {}).get('X-Request-ID') or (headers or {}).get('x-request-id') or self.headers.get('X-Request-ID','')
        if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}',request_id): request_id=secrets.token_hex(16)
        if status>=400:
            value=strict(body) if isinstance(body,bytes) else body
            if isinstance(value,dict) and 'code' in value:
                code={'INVALID_REQUEST':'VALIDATION','RATE_LIMITED':'DEPENDENCY_UNAVAILABLE'}.get(value['code'],value['code'])
                body={'code':code,'requestId':request_id,'retryable':status in (423,429,503)}
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status); self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(raw))); self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff'); self.send_header('X-Request-ID', request_id)
        for key_, value in (headers or {}).items():
            if key_.lower() in ('content-security-policy','referrer-policy','permissions-policy','content-disposition','etag','location','set-cookie'): self.send_header(key_, value)
        self.end_headers(); self.wfile.write(raw)
    def session_cookie(self, token=''):
        # Cookies are not port-scoped: isolate simultaneous local Quickstarts by
        # their validated public Host port. Never forward this cookie to Control.
        host = self.headers.get('Host', '')
        if not re.fullmatch(r'(localhost|127\.0\.0\.1)(:[0-9]{1,5})?', host): raise PermissionError()
        name = 'olo_toolgate_session_' + (host.rsplit(':', 1)[1] if ':' in host else '80')
        cookie = SimpleCookie()
        cookie[name] = token
        cookie[name]['path'] = '/api/quickstart/v1'
        cookie[name]['httponly'] = True
        cookie[name]['samesite'] = 'Strict'
        if isinstance(self.connection, ssl.SSLSocket): cookie[name]['secure'] = True
        if not token: cookie[name]['max-age'] = 0
        return name, cookie.output(header='').strip()
    def browser_token(self):
        name, _ = self.session_cookie()
        cookie = SimpleCookie()
        raw = self.headers.get('Cookie', '')
        if len(raw) > 32768: raise PermissionError()
        try:
            cookie.load(raw)
            token = cookie[name].value
            session('Bearer ' + token)
            return token
        except (KeyError, ValueError, CookieError): raise PermissionError() from None
    def dispatch(self):
        try:
            # The claim API is server to server on the container network: a bearer secret, no cookie, no browser Origin.
            claiming = urllib.parse.urlsplit(self.path).path==CLAIM_PATH and self.command=='POST'
            host = self.headers.get('Host', '')
            if not claiming and not re.fullmatch(r'(localhost|127\.0\.0\.1)(:[0-9]{1,5})?', host): raise PermissionError()
            origin = self.headers.get('Origin')
            if origin is not None and (claiming or origin != 'http://'+host): raise PermissionError()
            if self.headers.get('Transfer-Encoding') or len(self.path)>2048 or '\\' in self.path or '..' in urllib.parse.unquote(self.path): raise ValueError()
            length = int(self.headers.get('Content-Length','0'))
            # Packages and whole configuration snapshots are the large bodies; Control verifies and bounds them itself
            # (a snapshot is at most 1 MiB of records, and deployed tools carry their descriptors).
            target_path = urllib.parse.urlsplit(self.path).path if self.command=='POST' else ''
            limit = 64*1024*1024 if target_path=='/api/control/v1/packages/uploads' else 2*1024*1024 if target_path in ('/api/control/v1/config/import','/api/control/v1/access/shadow') else 65536
            if not 0<=length<=limit: raise ValueError()
            raw = self.rfile.read(length)
            if len(raw)!=length: raise ValueError()
            path = urllib.parse.urlsplit(self.path).path
            if path=='/api/quickstart/v1/status' and self.command=='GET':
                return self.reply(200, {'mode': 'Quickstart', 'tenant': TENANT, 'tenantId': TENANT, 'tenantName': installation().get('tenantName', 'Quickstart'), 'nonHa': True, 'ready': self.server.ready.is_set(), 'version': Path('/opt/quickstart/VERSION').read_text().strip(), 'passwordRequired': not password_disabled(), 'database': database_mode(), 'authority': 'online'})
            if path=='/api/quickstart/v1/login-hints' and self.command=='GET': return self.reply(200, login_hints())
            if claiming:
                saved = strict((DATA/'credentials.json').read_bytes()) if (DATA/'credentials.json').exists() else {}
                header = self.headers.get('Authorization', '')
                if not saved.get('claimToken') or not header.startswith('Bearer ') or not hmac.compare_digest(header[7:].encode(), saved['claimToken'].encode()): raise PermissionError()
                data = strict(raw)
                if not isinstance(data, dict) or set(data)!={'requester'} or not isinstance(data['requester'], str) or not re.fullmatch(r'[\x21-\x7e]{1,256}', data['requester']): raise ValueError()
                result = claim_account(data['requester'], self.server.signal_gateway)
                if result is None: return self.reply(409, {'error': 'POOL_EMPTY', 'nextResetAt': refresh_at()})
                return self.reply(200, result)
            if path=='/health/ready' and self.command=='GET': return self.reply(200 if self.server.ready.is_set() else 503, {'ready': self.server.ready.is_set()})
            if path=='/api/quickstart/v1/session' and self.command=='GET':
                try:
                    return self.reply(200, {'accessToken': self.browser_token()})
                except PermissionError:
                    return self.reply(401, {'code':'UNAUTHORIZED'}, headers={'Set-Cookie': self.session_cookie()[1]})
            if path=='/api/quickstart/v1/logout' and self.command=='POST':
                return self.reply(200, {}, headers={'Set-Cookie': self.session_cookie()[1]})
            if path=='/api/quickstart/v1/login' and self.command=='POST':
                with self.server.login_lock:
                    now = time.monotonic(); self.server.login_times[:] = [t for t in self.server.login_times if now-t<60]
                    if len(self.server.login_times)>=10: return self.reply(429, {'code':'RATE_LIMITED'})
                    self.server.login_times.append(now)
                data = strict(raw)
                if not isinstance(data, dict) or set(data)-{'username','password','newPassword'}: raise ValueError()
                user=data.get('username','admin')
                if user not in local_users(): raise PermissionError()
                if password_disabled() and user=='admin':
                    record = strict((DATA/'identity.json').read_bytes())
                    token = jwt(GROUPS, 'admin', generation=record['generation'])
                else:
                    token = self.server.identity.authenticate(data.get('password'), data.get('newPassword'),user)
                return self.reply(200, {'accessToken': token}, headers={'Set-Cookie': self.session_cookie(token)[1]})
            protected = path.startswith(('/api/control/', '/api/quickstart/'))
            if protected: session(self.headers.get('Authorization',''))
            workload = re.fullmatch(r'/api/quickstart/v1/workloads/([A-Za-z0-9][A-Za-z0-9._:-]{0,127})/reauthorize', path)
            if workload and self.command=='POST':
                return self.reply(200, {'token': reauthorize(workload.group(1), self.headers['Authorization'][7:], self.server.signal_gateway)})
            if path=='/api/quickstart/v1/vault' and self.command in ('GET','POST'):
                token_=self.headers['Authorization'][7:]
                if self.command=='GET':
                    status,response,upstream=call('/api/control/v1/vault',token_)
                    if status==200:response=json.dumps({'names':[item['name'] for item in strict(response)['items']]}).encode()
                else:
                    data=strict(raw)
                    if not isinstance(data,dict) or set(data)!={'name','value'}:raise ValueError()
                    data.update(toolGroupId='default-tools',deviceGroupId='default-devices')
                    key_=self.headers.get('Idempotency-Key','')
                    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}',key_):raise ValueError()
                    status,response,upstream=call('/api/control/v1/vault',token_,data,extra_headers={'Idempotency-Key':key_})
                return self.reply(status,response,headers=upstream)
            # Opt-in for deployments whose public HTTP port is bound to loopback.
            # Gateway still validates the opaque agent credential and every device scope.
            mcp_http = path=='/mcp' and self.command=='POST' and os.environ.get('TOOLGATE_ALLOW_LOOPBACK_MCP_HTTP')=='true'
            if mcp_http or path.startswith(('/console/', '/api/control/v1/', '/api/public/v1/clients', '/api/public/v1/installers')) or path in ('/','/console'):
                # Direct TLS adapter is used for enrollment administration; device peers use :8443 itself.
                # A Marketplace import fetches and verifies the package in Control (60 s per request), so it waits longer.
                secure = path.startswith(('/api/control/v1/endpoint/', '/api/control/v1/builder/', '/api/control/v1/fleet/'))
                client = http.client.HTTPSConnection('127.0.0.1',8443,context=ssl.create_default_context(cafile=str(DATA/'keys/device-ca.crt')),timeout=10) if secure else http.client.HTTPConnection('127.0.0.1',8081 if mcp_http else 8082,timeout=35 if mcp_http else 75 if path=='/api/control/v1/marketplace/imports' else 10)
                forwarded = {'authorization','content-type','idempotency-key','if-match','x-request-id','traceparent','toolgate-tenant-id'}
                if mcp_http: forwarded.update(('accept','mcp-protocol-version','mcp-method','mcp-name'))
                headers = {k:v for k,v in self.headers.items() if k.lower() in forwarded}
                try:
                    client.request(self.command, origin_target(self.path), raw or None, headers)
                    response = client.getresponse(); limit = 131072 if mcp_http else 64*1024*1024
                    body = response.read(limit+1)
                    if len(body)>limit: raise ValueError()
                    if path=='/api/control/v1/endpoint/devices' and self.command=='GET' and response.status==200:
                        page=strict(body)
                        if urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get('deployment')==['true']:
                            page=deployment_details(page)
                        body=json.dumps(page).encode()
                    if path in ('/console/','/console/index.html') and response.status==200 and b'toolgate-mode' not in body:
                        body = body.replace(b'<head>', b'<head><meta name="toolgate-mode" content="quickstart">', 1)
                    return self.reply(response.status, body, response.getheader('Content-Type','application/octet-stream'),dict(response.getheaders()))
                finally: client.close()
            return self.reply(404, {'code':'NOT_FOUND'})
        except Forbidden: self.reply(403, {'code':'FORBIDDEN'})
        except PermissionError: self.reply(401, {'code':'UNAUTHORIZED'})
        except (ValueError, KeyError, TypeError): self.reply(400, {'code':'INVALID_REQUEST'})
        except Exception: self.reply(503, {'code':'DEPENDENCY_UNAVAILABLE'})
    do_GET = dispatch
    do_POST = dispatch
    do_PUT = dispatch
    do_DELETE = dispatch


def deployment_details(page):
    """Attach bounded composition-owned display metadata without changing identity or observed peers."""
    path=DATA/'run/deployment-metadata.json'
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size>65536:return page
        document=strict(path.read_bytes())
        if set(document)!={'gatewaySystemName','devices'} or document['gatewaySystemName']!=socket.gethostname():return page
        records=document['devices']
        if not isinstance(records,dict) or len(records)>64:return page
        required={'executionKind','containerId','containerName','systemName','networkId','networkName','ipAddress'}
        for value in records.values():
            if not isinstance(value,dict) or set(value)!=required or not all(isinstance(v,str) for v in value.values()):return page
            if value['executionKind'] not in ('ENDPOINT_CLIENT','TOOL_HOST_DIRECT','TOOL_HOST_PULL'):return page
            if any(not re.fullmatch('[a-f0-9]{64}',value[k]) for k in ('containerId','networkId')):return page
            if any(not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}',value[k]) for k in ('containerName','networkName')):return page
            if not re.fullmatch('[a-zA-Z0-9._-]{1,255}',value['systemName']):return page
            if not re.fullmatch('[0-9a-fA-F:.]{2,45}',value['ipAddress']):return page
            ipaddress.ip_address(value['ipAddress'])
        page['deployments']=[dict(deviceId=row['deviceId'],deployment=records[row['deviceId']])
                             for row in page['items'] if row['deviceId'] in records]
    except (OSError,ValueError,KeyError,TypeError):pass  # Optional display evidence cannot break device administration.
    return page


def backup(destination):
    """Offline backup under the exclusive data lock; includes all trust/vault custody."""
    import shutil
    if (DATA/'restore.pending').exists(): raise ValueError('Unfinished restore cannot be backed up')
    if database_mode() != 'sqlite': raise ValueError('External PostgreSQL requires pg_dump plus matching custody backup')
    destination = Path(destination)
    if destination.exists() or not destination.is_absolute() or destination.is_relative_to(DATA): raise ValueError('Backup must be a new absolute path outside /data')
    destination.mkdir(mode=0o700)
    if any(p.is_symlink() for p in DATA.rglob('*')): raise ValueError('Unsafe backup source')
    shutil.copytree(DATA, destination/'data', ignore=shutil.ignore_patterns('run','service.lock','control.sqlite-wal','control.sqlite-shm'))
    if (DATA/'state/control.sqlite').exists():
        # sqlite3's transaction context does not close a connection. Close both
        # before hashing: a WAL checkpoint can remove sidecars at close time.
        with closing(sqlite3.connect(DATA/'state/control.sqlite')) as source, closing(sqlite3.connect(destination/'data/state/control.sqlite')) as target: source.backup(target)
    with closing(sqlite3.connect(destination/'data/state/control.sqlite')) as archived:
        tenants=archived.execute('SELECT tenant_id FROM control_tenants').fetchall()
        if len(tenants)!=1:raise ValueError('Quickstart backup requires one registered tenant')
        tenant=tenants[0][0]
        def rows(table,columns):
            return [dict(zip(columns,row)) for row in archived.execute('SELECT '+','.join(columns)+' FROM '+table+' WHERE tenant_id=?',(tenant,))]
        generation=archived.execute('SELECT directory_cache_generation FROM control_tenant_authority WHERE tenant_id=?',(tenant,)).fetchone()
        clock=archived.execute('SELECT observed_at FROM control_approval_clocks WHERE tenant_id=?',(tenant,)).fetchone()
        recovery=dict(tenantId=tenant,identityKeys=rows('control_tenant_identity_keys',['purpose','key_id','state','provider_reference','public_material','key_fingerprint','generation']),
            vaultKeys=rows('control_tenant_vault_keys',['version','state','provider_reference','wrapped_encryption_key','wrapped_retry_key']),cacheGenerationFloor=generation[0],approvalClockFloor=clock[0] if clock else 0)
    atomic(destination/'manifest.json', json.dumps({'layoutVersion':1,'tenantRecovery':recovery, 'files': {p.relative_to(destination/'data').as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in (destination/'data').rglob('*') if p.is_file()}}))


def restore(source,review=None,trust=None):
    if not review or not trust:raise ValueError('Signed external restore review and pinned trust required')
    import enterprise_restore
    enterprise_restore.restore(source,DATA,review,trust)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--backup'); parser.add_argument('--restore'); parser.add_argument('--restore-review'); parser.add_argument('--restore-trust'); parser.add_argument('--health',action='store_true'); args = parser.parse_args()
    if args.health:
        with urllib.request.urlopen('http://127.0.0.1:8080/health/ready',timeout=3) as reply: return 0 if reply.status==200 else 1
    os.umask(0o077); DATA.mkdir(exist_ok=True)
    with (DATA/'service.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if args.backup: backup(args.backup); return
        if args.restore: restore(args.restore,args.restore_review,args.restore_trust); return
        initialize(); env = configure(); children = []; stop=threading.Event(); ready_state=threading.Event()
        signal.signal(signal.SIGTERM, lambda *_: stop.set()); signal.signal(signal.SIGINT, lambda *_: stop.set())
        server = None; bridges=[]
        try:
            children.append(subprocess.Popen(['java','-jar','/app/quarkus-run.jar'],env=env))
            deadline = time.monotonic()+90
            while not stop.is_set():
                if children[0].poll() is not None: raise ValueError('Control failed')
                try:
                    if call('/q/health/ready',port=9092)[0]==200: break
                except Exception: pass
                if time.monotonic()>deadline: raise ValueError('Control readiness timeout')
                stop.wait(.2)
            bridges=tls_bridges()
            child_env = {name:value for name,value in os.environ.items() if not name.startswith(('QUARKUS_DATASOURCE_', 'QUARKUS_FLYWAY_', 'TOOLGATE_REDIS_', 'TOOLGATE_CONTROL_PLATFORM_', 'TOOLGATE_CONTROL_VAULT_', 'TOOLGATE_CONTROL_IDENTITY_'))}
            gateway_env = dict(child_env, TOOLGATE_GATEWAY_CONFIG='/data/run/gateway.json',TOOLGATE_GATEWAY_CREDENTIALS='/data/run/credentials.json')
            children.append(subprocess.Popen(['/usr/local/bin/olo-toolgate-gateway'],env=gateway_env))
            host_env = dict(child_env, TOOLGATE_TOOL_HOST_CONFIG='/data/run/tool-host.json')
            children.append(subprocess.Popen(['/usr/local/bin/olo-toolgate-tool-host'],env=host_env))
            remote_env = dict(child_env, TOOLGATE_TOOL_HOST_CONFIG='/data/run/tool-host-remote.json')
            children.append(subprocess.Popen(['/usr/local/bin/olo-toolgate-tool-host'],env=remote_env))
            server = BoundedServer(('0.0.0.0',8080),Handler,ready_state)
            server.signal_gateway = lambda: children[1].send_signal(signal.SIGHUP)
            threading.Thread(target=server.serve_forever,daemon=True).start()
            renewed = time.monotonic(); boot = renewed
            while not stop.wait(1):
                if any(child.poll() is not None for child in children): raise ValueError('Composed process failed')
                ready = dependencies_ready()
                if ready: ready_state.set()
                else: ready_state.clear()
                now = time.monotonic()
                if now-renewed>=600: machine(); renewed=now
                if now-boot>=82800:
                    ready_state.clear()
                    for child in children[1:]:
                        child.terminate(); child.wait(timeout=20)
                    configure()
                    children[1] = subprocess.Popen(['/usr/local/bin/olo-toolgate-gateway'],env=gateway_env)
                    children[2] = subprocess.Popen(['/usr/local/bin/olo-toolgate-tool-host'],env=host_env)
                    children[3] = subprocess.Popen(['/usr/local/bin/olo-toolgate-tool-host'],env=remote_env)
                    boot=now
        finally:
            ready_state.clear()
            if server: server.shutdown(); server.server_close()
            for bridge in bridges: bridge.shutdown();bridge.server_close()
            for child in reversed(children):
                if child.poll() is None: child.terminate()
            for child in children:
                try: child.wait(timeout=25)
                except subprocess.TimeoutExpired: child.kill(); child.wait()


if __name__=='__main__':
    try: main()
    except Refused as refused:  # A configuration the operator must change; the message names the fix.
        print(json.dumps({'service':'quickstart','event':'startup_refused','code':'CONFIGURATION_REFUSED','reason':str(refused)}),flush=True)
        raise SystemExit(1) from None
    except Exception:
        print(json.dumps({'service':'quickstart','event':'startup_or_runtime_failure','code':'DEPENDENCY_UNAVAILABLE'}),flush=True)
        raise SystemExit(1) from None
