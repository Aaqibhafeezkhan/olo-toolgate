# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real local identity primitives and offline custody/integrity tests; run in Linux image."""
import importlib.util
import http.cookiejar
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
import urllib.request
import urllib.error
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('quickstart_supervisor',ROOT/'apps/quickstart/supervisor.py')
quickstart=importlib.util.module_from_spec(spec);spec.loader.exec_module(quickstart)


class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.identity=quickstart.LocalIdentity()
        quickstart.DATA=self.root/'data';quickstart.DATA.mkdir(mode=0o700)
        (quickstart.DATA/'keys').mkdir(mode=0o700)
        (quickstart.DATA/'state').mkdir(mode=0o700)
        quickstart.TENANT=quickstart.tenant_identity()
        with sqlite3.connect(quickstart.DATA/'state/control.sqlite') as db:
            db.executescript((ROOT/'apps/control-plane/src/main/resources/db/quickstart/V1.sql').read_text(encoding='utf-8'))
            db.execute("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,1)",(quickstart.TENANT,'quickstart','Test tenant'))
            db.execute('INSERT INTO control_tenant_authority(tenant_id) VALUES(?)',(quickstart.TENANT,))
            binding={'id':'installed-admin','name':'Reviewed test identity','enabled':True,'revision':1,'userId':'admin','issuer':quickstart.ISSUER,'subject':'admin','sessionEpoch':1,'firstSeenUnixMs':1,'lastAttemptUnixMs':1,'attemptCount':1,'registrationReason':'REVIEWED_INSTALLATION','sessionsValidAfterUnixMs':0}
            db.execute('INSERT INTO control_records VALUES(?,?,?,?,?)',(quickstart.TENANT,'IDENTITY_BINDING','installed-admin',1,json.dumps(binding)))
    def tearDown(self):self.temp.cleanup()
    def test_tenant_uuid_persists_and_unsupported_state_is_rejected(self):
        import uuid
        current=quickstart.tenant_identity()
        self.assertEqual(current,str(uuid.UUID(current)))
        self.assertEqual(current,quickstart.tenant_identity())
        with patch.dict(os.environ,{'TOOLGATE_DEFAULT_TENANT_ALIAS':'different'}):
            with self.assertRaises(ValueError):quickstart.tenant_identity()
        (quickstart.DATA/'state/tenant.json').unlink()
        with self.assertRaises(ValueError):quickstart.tenant_identity()
    def test_unfinished_restore_cannot_initialize_or_create_an_archive(self):
        quickstart.atomic(quickstart.DATA/'restore.pending','{}')
        with self.assertRaisesRegex(ValueError,'Unfinished restore'):quickstart.initialize()
        archive=self.root/'incomplete'
        with self.assertRaisesRegex(ValueError,'Unfinished restore'):quickstart.backup(str(archive))
        self.assertFalse(archive.exists())
    def test_deployment_metadata_preserves_enrollment_and_observed_addresses(self):
        (quickstart.DATA/'run').mkdir()
        record=dict(executionKind='TOOL_HOST_DIRECT',containerId='a'*64,containerName='quickstart-1',
                    systemName=quickstart.socket.gethostname(),networkId='b'*64,networkName='example_default',ipAddress='172.18.0.2')
        document=dict(gatewaySystemName=quickstart.socket.gethostname(),devices={'tool-host-local':record,'invisible-device':record})
        path=quickstart.DATA/'run/deployment-metadata.json';path.write_text(json.dumps(document))
        original=dict(deviceId='tool-host-local',ipAddress='::1')
        page=quickstart.deployment_details(dict(items=[dict(original)]))
        self.assertEqual(page['items'],[original])
        self.assertEqual(page['deployments'],[dict(deviceId='tool-host-local',deployment=record)])
        document['gatewaySystemName']='previous-container';path.write_text(json.dumps(document))
        self.assertNotIn('deployments',quickstart.deployment_details(dict(items=[dict(original)])))
        document['gatewaySystemName']=quickstart.socket.gethostname();record['runtimeToken']='never-export'
        path.write_text(json.dumps(document))
        self.assertNotIn('deployments',quickstart.deployment_details(dict(items=[dict(original)])))
    def test_client_runtime_hashes_are_explicit_scoped_and_expire(self):
        value={'tokenSha256':'a'*64,'context':{'tenantId':quickstart.TENANT,'mode':'SERVICE','credentialSha256':'a'*64},'expiresAtUnixMs':int(quickstart.time.time()*1000)+60000}
        path=quickstart.DATA/'client-runtime-credentials.json'
        path.write_text(json.dumps([value]))
        path.chmod(0o600)
        with patch.dict(os.environ,{},clear=True):self.assertEqual(quickstart.client_credentials(),[])
        with patch.dict(os.environ,{'TOOLGATE_QUICKSTART_CLIENT_CREDENTIALS':'true'},clear=True):
            self.assertEqual(quickstart.client_credentials(),[value])
            path.write_text(json.dumps([{**value,'expiresAtUnixMs':0}]))
            self.assertEqual(quickstart.client_credentials(),[])
            path.write_text(json.dumps([{**value,'context':{**value['context'],'tenantId':'other'}}]))
            with self.assertRaises(ValueError):quickstart.client_credentials()
            path.write_text(json.dumps([{**value,'rawToken':'never'}]))
            with self.assertRaises(ValueError):quickstart.client_credentials()
    def test_external_endpoint_urls_follow_the_published_port_mapping(self):
        (quickstart.DATA/'run').mkdir(mode=0o700)
        control='https://localhost:18450'
        gateway='https://127.0.0.1:18450'
        with patch.dict(os.environ,{'TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL':control,
                                    'TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL':gateway},clear=True):
            with patch.object(quickstart,'prepare',return_value=(quickstart.DATA/'packet.json',quickstart.DATA/'trust.json')),patch.object(quickstart,'tenant_certificates'):
                settings=quickstart.configure()
            self.assertEqual(settings['TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL'],control)
            self.assertEqual(settings['TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL'],gateway)
            for name in ('tool-host.json','tool-host-remote.json'):
                self.assertEqual(json.loads((quickstart.DATA/'run'/name).read_text())['control']['url'],control)
        with patch.dict(os.environ,{},clear=True):
            with patch.object(quickstart,'prepare',return_value=(quickstart.DATA/'packet.json',quickstart.DATA/'trust.json')),patch.object(quickstart,'tenant_certificates'):
                settings=quickstart.configure()
            self.assertEqual(settings['TOOLGATE_CONTROL_ENDPOINT_CONTROL_URL'],'https://localhost:8443')
            self.assertEqual(settings['TOOLGATE_CONTROL_ENDPOINT_GATEWAY_URL'],'https://localhost:8443')
            for name in ('tool-host.json','tool-host-remote.json'):
                self.assertEqual(json.loads((quickstart.DATA/'run'/name).read_text())['control']['url'],'https://localhost:8443')
    def test_fleet_keys_are_persistent_and_disjoint(self):
        quickstart.fleet_keys()
        organization = json.loads((quickstart.DATA/'keys/organization-keys.json').read_text(encoding='utf-8'))[0]
        release = json.loads((quickstart.DATA/'keys/release-keys.json').read_text(encoding='utf-8'))[0]
        self.assertEqual(organization['kid'], 'fleet-local')
        self.assertEqual(release['kid'], 'package-local')
        self.assertEqual(organization['e'], 'AQAB')
        self.assertNotEqual(organization['n'], release['n'])
        original = (quickstart.DATA/'keys/fleet.pem').read_bytes()
        quickstart.fleet_keys()
        self.assertEqual(original, (quickstart.DATA/'keys/fleet.pem').read_bytes())
    def test_tenant_signing_keys_are_disjoint_persistent_and_publication_is_bound(self):
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        def keytool(arguments,**kwargs):
            Path(arguments[arguments.index('-keystore')+1]).write_bytes(b'public-store-test-double')
        with patch.object(quickstart.subprocess,'run',side_effect=keytool):
            quickstart.tenant_certificates(quickstart.TENANT)
            root=quickstart.DATA/'tenant-identity'
            before={str(path.relative_to(root)):path.read_bytes() for path in root.rglob('*.pk8.pem')}
            self.assertEqual(len(before),2)
            moduli=[serialization.load_pem_private_key(pem,password=None).public_key().public_numbers().n for pem in before.values()]
            self.assertNotEqual(*moduli)
            packet=json.loads((root/'publication.json').read_text())
            self.assertNotIn('PRIVATE KEY',json.dumps(packet))
            authority=next(row for row in packet['publications'][0]['keys'] if row['purpose']=='DEVICE_CA')
            certificate=x509.load_pem_x509_certificate(authority['certificatePem'].encode())
            self.assertEqual(certificate.subject.get_attributes_for_oid(x509.NameOID.ORGANIZATIONAL_UNIT_NAME)[0].value,quickstart.TENANT)
            quickstart.tenant_certificates(quickstart.TENANT)
            self.assertEqual(before,{str(path.relative_to(root)):path.read_bytes() for path in root.rglob('*.pk8.pem')})
            with self.assertRaises(ValueError):quickstart.tenant_certificates('10000000-0000-4000-8000-000000000001')

    def test_password_policy_rotation_signature_and_stale_generation(self):
        for password in ('','short','a'*32,'abc def ghi jkl mno','é'*32,123):
            self.assertFalse(quickstart.password_valid(password))
        bootstrap='initial-Strong-Password-2026'
        quickstart.atomic(quickstart.DATA/'identity.json',json.dumps(quickstart.password_record(bootstrap)))
        with self.assertRaises(PermissionError):self.identity.authenticate('wrong-password')
        # No forced change at first login; a change is still possible on request.
        self.assertEqual(quickstart.session('Bearer '+self.identity.authenticate(bootstrap))['password_generation'],1)
        token=self.identity.authenticate(bootstrap,'replacement-Strong-Password-2026')
        self.assertEqual(quickstart.session('Bearer '+token)['sub'],'admin')
        with self.assertRaises(PermissionError):quickstart.session('Bearer '+token[:-4]+'xxxx')
        with self.assertRaises(PermissionError):quickstart.session('Bearer '+quickstart.jwt(['toolgate-admin']))
        self.identity.authenticate('replacement-Strong-Password-2026','another-Strong-Password-2026')
        with self.assertRaises(PermissionError):quickstart.session('Bearer '+token)
        with sqlite3.connect(quickstart.DATA/'state/control.sqlite') as db:
            events=db.execute("SELECT operation,revision FROM control_audit WHERE target='local-identity:admin' ORDER BY sequence").fetchall()
            self.assertEqual(events,[('PASSWORD_CHANGE_REQUESTED',2),('PASSWORD_CHANGED',2),('PASSWORD_CHANGE_REQUESTED',3),('PASSWORD_CHANGED',3)])
            db.execute("CREATE TRIGGER reject_change BEFORE INSERT ON control_audit WHEN NEW.operation='PASSWORD_CHANGE_REQUESTED' BEGIN SELECT RAISE(ABORT,'audit unavailable'); END")
        before=(quickstart.DATA/'identity.json').read_bytes()
        with self.assertRaises(sqlite3.IntegrityError):self.identity.authenticate('another-Strong-Password-2026','final-Strong-Password-2026')
        self.assertEqual(before,(quickstart.DATA/'identity.json').read_bytes())
    def test_weak_first_boot_and_unknown_layout_fail_closed(self):
        with patch.dict(os.environ,{'TOOLGATE_BOOTSTRAP_PASSWORD':'short'}):
            with self.assertRaises(ValueError):quickstart.initialize()
        self.assertFalse((quickstart.DATA/'identity.json').exists())
        quickstart.atomic(quickstart.DATA/'layout.json','{"layoutVersion":2}')
        with self.assertRaises(ValueError):quickstart.initialize()
    def test_duplicate_json_and_nonfinite_values_are_rejected(self):
        for body in ('{"password":"one","password":"two"}','{"value":NaN}','{"value":Infinity}'):
            with self.assertRaises(ValueError):quickstart.strict(body)
    def test_passwordless_is_explicit_and_does_not_bypass_api_sessions_or_origin(self):
        quickstart.atomic(quickstart.DATA/'identity.json',json.dumps(quickstart.password_record('initial-Strong-Password-2026')))
        ready=threading.Event();ready.set()
        with patch.dict(os.environ,{'TOOLGATE_DISABLE_ADMIN_PASSWORD':'true'}):
            server=quickstart.BoundedServer(('127.0.0.1',0),quickstart.Handler,ready)
            thread=threading.Thread(target=server.serve_forever);thread.start()
            try:
                url='http://127.0.0.1:'+str(server.server_port)
                request=urllib.request.Request(url+'/api/quickstart/v1/login',data=b'{}',headers={'Content-Type':'application/json'})
                with urllib.request.urlopen(request) as response: token=json.load(response)['accessToken']
                self.assertEqual(quickstart.session('Bearer '+token)['sub'],'admin')
                with self.assertRaises(urllib.error.HTTPError) as denied:urllib.request.urlopen(url+'/api/quickstart/v1/vault')
                self.assertEqual(denied.exception.code,401)
                for path in ('/api/control/v1/builder/drafts','/api/control/v1/fleet/releases'):
                    with self.assertRaises(urllib.error.HTTPError) as denied: urllib.request.urlopen(url+path)
                    self.assertEqual(denied.exception.code,401)
                request.add_header('Origin','https://evil.example')
                with self.assertRaises(urllib.error.HTTPError) as denied:urllib.request.urlopen(request)
                self.assertEqual(denied.exception.code,401)
            finally:server.shutdown();thread.join();server.server_close()
        with patch.dict(os.environ,{'TOOLGATE_DISABLE_ADMIN_PASSWORD':'yes'}):
            with self.assertRaises(ValueError):quickstart.password_disabled()
    def test_machine_identity_cannot_be_used_as_a_human_session(self):
        token=quickstart.jwt(['toolgate-relay-gateway'],'runtime',directory_bound=False)
        with self.assertRaises(PermissionError):quickstart.session('Bearer '+token)
    def test_browser_session_survives_refresh_without_granting_cookie_api_access(self):
        password='initial-Strong-Password-2026'
        quickstart.atomic(quickstart.DATA/'identity.json',json.dumps(quickstart.password_record(password,True)))
        ready=threading.Event();ready.set()
        server=quickstart.BoundedServer(('127.0.0.1',0),quickstart.Handler,ready)
        thread=threading.Thread(target=server.serve_forever);thread.start()
        jar=http.cookiejar.CookieJar()
        opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        url='http://127.0.0.1:'+str(server.server_port)
        try:
            request=urllib.request.Request(url+'/api/quickstart/v1/login',data=json.dumps({'password':password}).encode(),headers={'Content-Type':'application/json'})
            with opener.open(request) as response:
                token=json.load(response)['accessToken'];header=response.headers['Set-Cookie']
            self.assertIn('HttpOnly',header);self.assertIn('SameSite=Strict',header)
            self.assertIn('Path=/api/quickstart/v1',header);self.assertNotIn('Domain=',header)
            self.assertNotIn('Max-Age=',header)  # Browser-session lifetime; token still expires in 15 minutes.
            with opener.open(url+'/api/quickstart/v1/session') as response:
                self.assertEqual(json.load(response)['accessToken'],token)
                self.assertEqual(response.headers['Cache-Control'],'no-store')
            # The cookie cannot authorize protected operations by itself.
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(url+'/api/quickstart/v1/vault')
            self.assertEqual(denied.exception.code,401)
            # Browsers share host cookies across ports; one stack must not restore another's session.
            wrong_port=urllib.request.Request(url+'/api/quickstart/v1/session',headers={'Host':'127.0.0.1:1'})
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(wrong_port)
            self.assertEqual(denied.exception.code,401)
            foreign=urllib.request.Request(url+'/api/quickstart/v1/session',headers={'Origin':'https://evil.example'})
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(foreign)
            self.assertEqual(denied.exception.code,401)
            with opener.open(urllib.request.Request(url+'/api/quickstart/v1/logout',data=b'')) as response:
                self.assertIn('Max-Age=0',response.headers['Set-Cookie'])
            self.assertEqual(len(jar),0)
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(url+'/api/quickstart/v1/session')
            self.assertEqual(denied.exception.code,401)
        finally:server.shutdown();thread.join();server.server_close()

    def test_browser_session_rejects_password_rotation_and_expiration(self):
        password='initial-Strong-Password-2026'
        quickstart.atomic(quickstart.DATA/'identity.json',json.dumps(quickstart.password_record(password,True)))
        ready=threading.Event();ready.set()
        server=quickstart.BoundedServer(('127.0.0.1',0),quickstart.Handler,ready)
        thread=threading.Thread(target=server.serve_forever);thread.start()
        jar=http.cookiejar.CookieJar()
        opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        url='http://127.0.0.1:'+str(server.server_port)
        def login(value):
            request=urllib.request.Request(url+'/api/quickstart/v1/login',data=json.dumps({'password':value}).encode(),headers={'Content-Type':'application/json'})
            with opener.open(request) as response:json.load(response)
        try:
            login(password)
            replacement='replacement-Strong-Password-2026'
            self.identity.authenticate(password,replacement)
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(url+'/api/quickstart/v1/session')
            self.assertEqual(denied.exception.code,401);self.assertEqual(len(jar),0)
            login(replacement)
            expired=quickstart.time.time()+901
            with patch.object(quickstart.time,'time',return_value=expired):
                with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(url+'/api/quickstart/v1/session')
            self.assertEqual(denied.exception.code,401);self.assertEqual(len(jar),0)
        finally:server.shutdown();thread.join();server.server_close()
    def test_external_backup_and_implicit_storage_switch_are_rejected(self):
        with patch.dict(os.environ,{'TOOLGATE_QUICKSTART_DATABASE_MODE':'postgresql'}):
            with self.assertRaises(ValueError):quickstart.backup(str(self.root/'external-backup'))
            with self.assertRaises(ValueError):quickstart.initialize()
    def test_atomic_state_rejects_symlink(self):
        outside=self.root/'outside';outside.write_text('unchanged')
        (quickstart.DATA/'identity.json').symlink_to(outside)
        with self.assertRaises(ValueError):quickstart.atomic(quickstart.DATA/'identity.json','tampered')
        self.assertEqual(outside.read_text(),'unchanged')
    def test_consistent_backup_integrity_and_empty_restore_boundary(self):
        quickstart.atomic(quickstart.DATA/'layout.json','{"layoutVersion":1}')
        with sqlite3.connect(quickstart.DATA/'state/control.sqlite') as db:
            db.execute('PRAGMA journal_mode=WAL')
            tenant=quickstart.TENANT
            db.execute('INSERT INTO control_server_documents VALUES(?,?,?)',(tenant,'sha256:'+'a'*64,'persistent'))
        backup=self.root/'snapshot';quickstart.backup(str(backup))
        target=self.root/'restored';target.mkdir();quickstart.DATA=target
        manifest=json.loads((backup/'manifest.json').read_text(encoding='utf-8'))
        import hashlib
        self.assertEqual(manifest['files']['state/control.sqlite'],hashlib.sha256((backup/'data/state/control.sqlite').read_bytes()).hexdigest())
        with sqlite3.connect(backup/'data/state/control.sqlite') as db:self.assertEqual(db.execute('SELECT document FROM control_server_documents').fetchone()[0],'persistent')
        self.assertEqual(manifest['tenantRecovery']['tenantId'],tenant)
        # A consistent archive never authorizes restoration of its own credentials.
        with self.assertRaises(ValueError):quickstart.restore(str(backup))
        self.assertEqual(list(target.iterdir()),[])



if __name__=='__main__':unittest.main()


class OriginTargetTests(unittest.TestCase):
    def test_origin_target_rejects_external_and_ambiguous_urls(self):
        for target in ('https://evil.example/a', '//evil.example/a', '/a\\b', '/a\r\nHost: evil', '/a#fragment'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                quickstart.origin_target(target)

    def test_origin_target_preserves_local_query(self):
        self.assertEqual(quickstart.origin_target('/api/control/v1/devices?limit=2'), '/api/control/v1/devices?limit=2')

