# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Initialize file, trust volume, generated logins and agent tokens, visitor pool claims and reauthorization."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'apps/quickstart'))
spec=importlib.util.spec_from_file_location('quickstart_accounts',ROOT/'apps/quickstart/supervisor.py')
quickstart=importlib.util.module_from_spec(spec);spec.loader.exec_module(quickstart)
import enterprise_bootstrap as bootstrap  # noqa: E402

scope=dict(toolGroups=dict(ids=['MockToolGroup'],all=False),deviceGroups=dict(ids=['ToolHostGroup'],all=False))
group=lambda id,**extra:dict(id=id,name=id,enabled=True,revision=1,**extra)
FILE=dict(formatVersion=2,tenantId='quickstart',revision=0,
    settings=dict(gateway=dict(gatewayName='ToolGate Demo'),deviceApproval=dict(autoApprove=False,durationMs=2*86400000,ownerUserId='admin'),
                  configuration=dict(requiredReviews=0)),
    users=[dict(id='public',name='Public visitor',email='public@demo.invalid',enabled=True,revision=1,showOnLogin=True,claims={'employee_id':'E-101'}),
           dict(id='readiness',name='Readiness',enabled=True,revision=1),dict(id='admin',name='Replaced',enabled=False,revision=1)],
    teams=[group('VisitorTeam',userIds=[],roleIds=[]),group('team-default',userIds=['public'],roleIds=[]),group('installation-administrators',userIds=[],roleIds=[])],
    agents=[dict(id='public-agent',name='Public agent',enabled=True,revision=1,ownerUserId='admin')],
    agentGroups=[group('VisitorAgentGroup',agentIds=[],roleIds=[]),group('PublicAgentGroup',agentIds=['public-agent'],roleIds=[])],
    toolGroups=[group('MockToolGroup',toolIds=[])],deviceGroups=[group('ToolHostGroup',deviceIds=[])],
    bindings=[group('mock-binding',toolGroupId='MockToolGroup',deviceGroupId='ToolHostGroup',actions=[],allowedPackageDigests=[],requireOnline=True,ownerDependency=False)],
    grants=[group('visitor-capability',purpose='CAPABILITY',sourceType='AGENT_GROUP',sourceId='VisitorAgentGroup',scope=scope),
            group('public-capability',purpose='CAPABILITY',sourceType='AGENT_GROUP',sourceId='PublicAgentGroup',scope=scope)],
    delegations=[group('visitor-delegation',teamId='VisitorTeam',agentGroupId='VisitorAgentGroup',scope=scope)],
    workloadBindings=[group('public-workload',agentId='public-agent',mode='SERVICE',passClaims={'employee_id':['hr.*']})],
    requiredIdentities=[dict(id='alice',teamIds=['VisitorTeam'])],
    visitorPool=dict(prefix='visitor-',teamId='VisitorTeam',agentGroupId='VisitorAgentGroup',delegationId='visitor-delegation',
                     claims={'employee_id':['E-101','E-102']},passClaims={'employee_id':['hr.*']}),
    reservedRecords=10)
sha=lambda value:hashlib.sha256(value.encode() if isinstance(value,str) else value).hexdigest()


class Control:
    """Stands in for Control and the Gateway management listener, as the supervisor sees them over HTTP."""
    def __init__(self):self.calls=[];self.loaded=None;self.role='SUPER_ADMIN';self.permissions=['workload_binding.rotate_credential'];self.epochs={}
    def signal(self):self.loaded=sha((quickstart.DATA/'run/credentials.json').read_bytes())
    def __call__(self,path,token=None,body=None,port=8082,secure=False,method=None,extra_headers=None):
        self.calls.append((path,token,body))
        if port==9091:return 200,json.dumps({'sha256':self.loaded,'count':1}).encode(),{}
        if path=='/api/control/v1/admin-session':return 200,json.dumps({'role':self.role,'permissions':self.permissions}).encode(),{}
        if path.endswith('/enable'):return 200,b'{}',{}
        if path.endswith('/credential'):
            binding_id=path.split('/')[-2];snapshot=strict_packet()
            binding=dict(next(b for b in snapshot['workloadBindings'] if b['id']==binding_id))
            self.epochs[binding_id]=self.epochs.get(binding_id,binding['credentialEpoch'])+1
            binding.update(credentialSha256=body['credentialSha256'],credentialEpoch=self.epochs[binding_id],enabled=True)
            if binding['mode']=='DELEGATED':binding['delegatedSessionEpoch']=2  # Enabling the user moved its session epoch.
            return 200,json.dumps(binding).encode(),{}
        return 404,b'{}',{}


def installation_packet():
    return json.loads((quickstart.DATA/'installation-review.json').read_text())['authorization']['snapshot']


def strict_packet():
    """What Control holds after first start: the installation packet, then the initialize file's records over it."""
    snapshot = installation_packet()
    extra = quickstart.DATA/'state/initialize-snapshot.json'
    if extra.exists():
        for name, rows in json.loads(extra.read_text()).items():
            if isinstance(rows, list):
                merged = {row['id']: row for row in snapshot[name]}
                merged.update({row['id']: row for row in rows})
                snapshot[name] = list(merged.values())
    return snapshot


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        quickstart.DATA=self.root/'data';quickstart.TRUST=self.root/'trust'
        for name in ('','keys','state','run'):(quickstart.DATA/name).mkdir(mode=0o700,exist_ok=True)
        quickstart.TENANT=quickstart.tenant_identity()
        (self.root/'initialize.json').write_text(json.dumps({**FILE,'tenantId':quickstart.TENANT}))
        self.env=patch.dict(os.environ,{'TOOLGATE_INITIALIZE_FILE':str(self.root/'initialize.json'),'TOOLGATE_DEMO_ACCOUNT_POOL':'3',
                                         'TOOLGATE_DEMO_REFRESH_AT':'2026-10-15T03:00:00Z'});self.env.start()
    def tearDown(self):self.env.stop();self.temp.cleanup()
    def prepare(self):
        return bootstrap.prepare(quickstart.DATA,quickstart.TENANT,quickstart.ISSUER,quickstart.key,quickstart.atomic)
    def install(self):
        """A prepared installation with its directory identities, the admin login and generated accounts."""
        self.prepare()
        with sqlite3.connect(quickstart.DATA/'state/control.sqlite') as db:
            db.executescript((ROOT/'apps/control-plane/src/main/resources/db/quickstart/V1.sql').read_text(encoding='utf-8'))
            db.execute("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,1)",(quickstart.TENANT,'quickstart','Test tenant'))
            db.execute('INSERT INTO control_tenant_authority(tenant_id) VALUES(?)',(quickstart.TENANT,))
            for binding in strict_packet()['identityBindings']:
                db.execute('INSERT INTO control_records VALUES(?,?,?,?,?)',(quickstart.TENANT,'IDENTITY_BINDING',binding['id'],1,json.dumps(binding)))
        quickstart.atomic(quickstart.DATA/'identity.json',json.dumps(quickstart.password_record('admin-Initial-Password-2026')))
        quickstart.atomic(quickstart.DATA/'bootstrap-password','admin-Initial-Password-2026\n')
        quickstart.atomic(quickstart.DATA/'run/runtime-token','runtime-token-'+'r'*48)
        quickstart.accounts()
        return json.loads((quickstart.DATA/'credentials.json').read_text())
    def serve(self,control):
        ready=threading.Event();ready.set()
        server=quickstart.BoundedServer(('127.0.0.1',0),quickstart.Handler,ready);server.signal_gateway=control.signal
        thread=threading.Thread(target=server.serve_forever);thread.start()
        self.addCleanup(lambda:(server.shutdown(),thread.join(),server.server_close()))
        return 'http://127.0.0.1:'+str(server.server_port)
    def post(self,url,body,headers=None):
        request=urllib.request.Request(url,data=json.dumps(body).encode(),headers={'Content-Type':'application/json',**(headers or {})})
        try:
            with urllib.request.urlopen(request) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as error:return error.code,json.load(error)
    def gateway(self):
        return {c['context']['workloadBindingId']:c for c in json.loads((quickstart.DATA/'run/credentials.json').read_text())}

    def test_trust_volume_keeps_ca_and_tls_keys_while_data_is_regenerated(self):
        quickstart.TRUST.mkdir(mode=0o700)
        def keytool(arguments,**_):Path(arguments[arguments.index('-keystore')+1]).write_bytes(b'store')
        with patch.object(quickstart,'trust_mounted',return_value=True),patch.object(quickstart.subprocess,'run',side_effect=keytool) as run:
            for name in ('identity','device-ca','server-tls'):quickstart.key(name)
            quickstart.certificates()
            self.assertEqual(sorted(p.name for p in quickstart.TRUST.iterdir()),['device-ca.crt','device-ca.key','device-trust.p12','server.crt','server.key'])
            keys=sorted(p.name for p in (quickstart.DATA/'keys').iterdir())
            self.assertEqual(keys,['device-ca.crt','identity-public.pem','identity.pem','server.crt'])  # Only public copies of trust files.
            self.assertEqual((quickstart.DATA/'keys/device-ca.crt').read_bytes(),(quickstart.TRUST/'device-ca.crt').read_bytes())
            ca=(quickstart.TRUST/'device-ca.crt').read_bytes()
            # A refresh deletes /data only: the same CA and TLS keys come back and no certificate is reissued.
            import shutil;shutil.rmtree(quickstart.DATA);(quickstart.DATA/'keys').mkdir(parents=True)
            quickstart.certificates();self.assertEqual(run.call_count,1)
            self.assertEqual((quickstart.DATA/'keys/device-ca.crt').read_bytes(),ca)
            self.assertEqual(quickstart.trust('server.key'),quickstart.TRUST/'server.key')
        with patch.object(quickstart,'trust_mounted',return_value=False):
            self.assertEqual(quickstart.trust('server.key'),quickstart.DATA/'keys/server-tls.pem')
            self.assertEqual(quickstart.trust('device-ca.key'),quickstart.DATA/'keys/device-ca.pem')

    def test_initialize_file_merges_into_the_installation_snapshot(self):
        self.prepare()
        # The reviewed packet carries only installation records; the file follows as Control's first import.
        self.assertFalse(installation_packet()['grants']);self.assertNotIn('public',{row['id'] for row in installation_packet()['users']})
        snapshot=strict_packet();state=json.loads((quickstart.DATA/'state/installation.json').read_text())
        rows=lambda kind:{row['id']:row for row in snapshot[kind]}
        users,teams=rows('users'),rows('teams')
        # Profiles are no longer silently rewritten by a legacy reserved-record list.
        # Control's common authority guard rejects these proposed setup-login changes.
        self.assertFalse(users['admin']['enabled']);self.assertEqual(users['admin']['name'],'Replaced')
        self.assertTrue(next(u for u in installation_packet()['users'] if u['id']=='admin')['enabled'])
        self.assertEqual(teams['installation-administrators']['roleIds'],[])
        self.assertEqual(teams['team-default']['userIds'],['public'])
        self.assertEqual(users['public']['claims'],{'employee_id':'E-101'});self.assertTrue(users['public']['showOnLogin'])
        self.assertFalse(users['alice']['enabled']);self.assertIn('alice',teams['VisitorTeam']['userIds'])  # Placeholder.
        self.assertIn('installed-public',rows('identityBindings'));self.assertIn('installed-readiness',rows('identityBindings'))
        for name in ('settings','visitorPool','reservedRecords','requiredIdentities'):self.assertNotIn(name,snapshot)
        self.assertEqual(state['environment'],{'TOOLGATE_GATEWAY_NAME':'ToolGate Demo','TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES':'false',
            'TOOLGATE_SETTINGS_AUTO_APPROVE_DURATION_DAYS':'2','TOOLGATE_SETTINGS_AUTO_APPROVE_OWNER':'admin','TOOLGATE_CONFIG_REQUIRED_REVIEWS':'0'})
        self.assertEqual(state['users'],[dict(id='public',showOnLogin=True),dict(id='readiness',showOnLogin=False)])
        agent,=state['agents'];binding=rows('workloadBindings')['public-workload']
        self.assertEqual(binding['credentialSha256'],sha(agent['token']));self.assertEqual(binding['passClaims'],{'employee_id':['hr.*']})
        self.assertEqual(agent['context']['bindingId'],'mock-binding');self.assertEqual(agent['context']['mode'],'SERVICE')
        # Pool: disabled accounts with round-robin claims, an agent each and a disabled delegated binding nobody holds a token for.
        self.assertEqual([p['userId'] for p in state['pool']],['visitor-001','visitor-002','visitor-003'])
        self.assertEqual([users[u]['claims']['employee_id'] for u in ('visitor-001','visitor-002','visitor-003')],['E-101','E-102','E-101'])
        self.assertFalse(users['visitor-002']['enabled']);self.assertIn('visitor-002',teams['VisitorTeam']['userIds'])
        self.assertIn('visitor-002-agent',rows('agentGroups')['VisitorAgentGroup']['agentIds'])
        pooled=rows('workloadBindings')['visitor-002-workload']
        self.assertEqual((pooled['enabled'],pooled['mode'],pooled['delegatedUserId'],pooled['delegatedSessionEpoch']),(False,'DELEGATED','visitor-002',1))
        self.assertEqual(state['pool'][1]['context']['bindingId'],'mock-binding');self.assertIn('installed-visitor-002',rows('identityBindings'))
        self.assertEqual(state['fixedCredentials'],2)
        with patch.dict(os.environ,{'TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES':'true','TOOLGATE_GATEWAY_NAME':''}):
            chosen={n:v for n,v in state['environment'].items() if not os.environ.get(n)}
            self.assertNotIn('TOOLGATE_SETTINGS_AUTO_APPROVE_DEVICES',chosen);self.assertEqual(chosen['TOOLGATE_GATEWAY_NAME'],'ToolGate Demo')

    def test_pool_budget_refuses_to_start_naming_the_largest_pool(self):
        with patch.dict(os.environ,{'TOOLGATE_DEMO_ACCOUNT_POOL':'0'}):self.prepare()
        largest=json.loads((quickstart.DATA/'state/installation.json').read_text())['maxPool']
        self.assertGreater(largest,3)
        for name in ('installation-review.json','installation-trust.json','state/installation.json'):(quickstart.DATA/name).unlink()
        with patch.dict(os.environ,{'TOOLGATE_DEMO_ACCOUNT_POOL':str(largest+1)}):
            with self.assertRaises(bootstrap.Refused) as refused:self.prepare()
        self.assertIn('largest pool that fits is '+str(largest),str(refused.exception))
        self.assertFalse((quickstart.DATA/'installation-review.json').exists())
        with patch.dict(os.environ,{'TOOLGATE_DEMO_ACCOUNT_POOL':str(largest)}):self.prepare()
        records=sum(len(rows) for name,rows in strict_packet().items() if isinstance(rows,list))
        self.assertLessEqual(records+1+FILE['reservedRecords']+largest,512)  # Plus one owned device per account.
        state=json.loads((quickstart.DATA/'state/installation.json').read_text())
        self.assertEqual(state['maxPool'],largest)

    def test_layers_replace_arrays_and_never_move_omitted_group_members(self):
        snapshot={name:[] for name in bootstrap.COLLECTIONS}
        snapshot['users']=[group('admin')]
        snapshot['teams']=[group('team',userIds=['admin'],roleIds=['old'])]
        snapshot['agentGroups']=[group('original',agentIds=['agent'],roleIds=[])]
        document={'teams':[group('team',userIds=[],roleIds=[])],
                  'agentGroups':[group('new',agentIds=['agent'],roleIds=[])]}
        with patch.object(bootstrap,'read_initialize',return_value=document):
            bootstrap.initialize(snapshot,quickstart.TENANT,quickstart.ISSUER,1)
        self.assertEqual(snapshot['teams'][0]['userIds'],[])
        self.assertEqual(snapshot['teams'][0]['roleIds'],[])
        self.assertEqual(snapshot['agentGroups'][0]['agentIds'],['agent'])
        self.assertEqual(snapshot['agentGroups'][1]['agentIds'],['agent'])  # Control rejects the explicit exclusivity conflict.

    def test_local_database_enforces_registry_ownership_foreign_keys(self):
        self.install()
        with quickstart.Database() as db:
            self.assertEqual(db.execute('PRAGMA foreign_keys').fetchone()[0],1)
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("INSERT INTO control_secrets(tenant_id,name,tool_group_id,device_group_id,version,key_version,cipher) VALUES(?,?,?,?,1,1,?)",('00000000-0000-4000-8000-000000000099','test','tools','devices','opaque'))

    def test_credentials_file_logins_hints_and_no_forced_password_change(self):
        saved=self.install();path=quickstart.DATA/'credentials.json'
        self.assertEqual(path.stat().st_mode&0o777,0o600)
        users={u['username']:u for u in saved['users']}
        self.assertEqual(list(users),['admin','reviewer-1','reviewer-2','public','readiness'])
        self.assertEqual(users['admin']['password'],'admin-Initial-Password-2026')
        self.assertTrue(users['public']['showOnLogin']);self.assertFalse(users['readiness']['showOnLogin'])
        agent,=saved['agents'];self.assertEqual(set(agent),{'agentId','workloadBindingId','token','userId'})
        self.assertGreaterEqual(len(saved['claimToken']),32)
        self.assertNotIn('token',json.dumps(json.loads((quickstart.DATA/'state/installation.json').read_text())['agents']))
        self.assertEqual(quickstart.workload_credentials()['public-workload']['tokenSha256'],sha(agent['token']))
        quickstart.gateway_credentials()
        self.assertEqual(self.gateway()['public-workload']['tokenSha256'],sha(agent['token']))
        # A restart neither changes passwords nor tokens.
        quickstart.accounts();self.assertEqual(json.loads(path.read_text()),saved)
        self.assertIn('public',quickstart.local_users());self.assertNotIn('visitor-001',quickstart.local_users())
        identity=quickstart.LocalIdentity()
        token=identity.authenticate(users['public']['password'],None,'public')  # No newPassword demanded.
        self.assertEqual(quickstart.session('Bearer '+token)['sub'],'public')
        with self.assertRaises(quickstart.Forbidden):identity.authenticate(users['public']['password'],'replacement-Strong-Password-2026','public')
        identity.authenticate(users['readiness']['password'],'replacement-Strong-Password-2026','readiness')
        self.assertEqual(quickstart.login_hints(),{'accounts':[dict(username='public',password=users['public']['password'])],'refreshAt':'2026-10-15T03:00:00Z'})
        url=self.serve(Control())
        with urllib.request.urlopen(url+'/api/quickstart/v1/login-hints') as response:
            self.assertEqual(json.load(response)['accounts'][0]['username'],'public')
        # The shared agent's token is listed only when TOOLGATE_LOGIN_HINT_AGENT names an agent that has one.
        with patch.dict(os.environ,{'TOOLGATE_LOGIN_HINT_AGENT':'public-agent'}):
            self.assertEqual(quickstart.login_hints()['accounts'],[dict(username='public',password=users['public']['password'],agentToken=saved['agents'][0]['token'])])
        with patch.dict(os.environ,{'TOOLGATE_LOGIN_HINT_AGENT':'unknown-agent'}):
            self.assertNotIn('agentToken',quickstart.login_hints()['accounts'][0])
        status,_=self.post(url+'/api/quickstart/v1/login',dict(username='public',password=users['public']['password'],newPassword='replacement-Strong-Password-2026'))
        self.assertEqual(status,403)
        status,_=self.post(url+'/api/quickstart/v1/login',dict(username='public',password=users['public']['password']))
        self.assertEqual(status,200)

    def test_claim_issues_revokes_and_never_reuses_pool_accounts(self):
        saved=self.install();control=Control();url=self.serve(control)
        claim=lambda requester,token=saved['claimToken'],headers=None:self.post(url+quickstart.CLAIM_PATH,{'requester':requester},
            {'Authorization':'Bearer '+token,'Host':'toolgate-quickstart-demo3:8080',**(headers or {})})
        with patch.object(quickstart,'call',control),patch.object(quickstart,'admin_token',return_value='admin-session'):
            self.assertEqual(claim('marketplace-user-1',token='wrong-'+saved['claimToken'])[0],401)
            self.assertEqual(claim('marketplace-user-1',headers={'Origin':'https://evil.example'})[0],401)
            status,first=claim('marketplace-user-1')
            self.assertEqual(status,200)
            self.assertEqual(set(first),{'username','password','agentToken','consoleUrl','gatewayUrl','expiresAt'})
            self.assertEqual(first['username'],'visitor-001');self.assertEqual(first['expiresAt'],'2026-10-15T03:00:00Z')
            live=self.gateway()['visitor-001-workload']
            self.assertEqual(live['tokenSha256'],sha(first['agentToken']));self.assertEqual(control.loaded,sha((quickstart.DATA/'run/credentials.json').read_bytes()))
            self.assertEqual((live['context']['mode'],live['context']['userId'],live['context']['sessionEpoch'],live['context']['credentialEpoch'],live['context']['bindingId']),
                             ('DELEGATED','visitor-001',2,2,'mock-binding'))
            session=quickstart.LocalIdentity().authenticate(first['password'],None,'visitor-001')
            # The same requester gets the same account with fresh credentials; the previous ones stop working.
            status,again=claim('marketplace-user-1')
            self.assertEqual((status,again['username']),(200,'visitor-001'))
            self.assertNotEqual(again['password'],first['password']);self.assertNotEqual(again['agentToken'],first['agentToken'])
            tokens={c['tokenSha256'] for c in self.gateway().values()}
            self.assertIn(sha(again['agentToken']),tokens);self.assertNotIn(sha(first['agentToken']),tokens)
            with self.assertRaises(PermissionError):quickstart.LocalIdentity().authenticate(first['password'],None,'visitor-001')
            with self.assertRaises(PermissionError):quickstart.session('Bearer '+session)
            self.assertEqual(self.gateway()['visitor-001-workload']['context']['credentialEpoch'],3)
            self.assertEqual(claim('marketplace-user-2')[1]['username'],'visitor-002')
            self.assertEqual(claim('marketplace-user-3')[1]['username'],'visitor-003')
            self.assertEqual(claim('marketplace-user-4'),(409,{'error':'POOL_EMPTY','nextResetAt':'2026-10-15T03:00:00Z'}))
        enabled=[path for path,_,_ in control.calls if path.endswith('/enable')]
        self.assertEqual(enabled,['/api/control/v1/users/visitor-00'+str(n)+'/enable' for n in (1,2,3)])
        recorded=json.loads((quickstart.DATA/'state/demo-accounts.json').read_text())['accounts']
        self.assertEqual({u:a['requester'] for u,a in recorded.items()},{'visitor-001':'marketplace-user-1','visitor-002':'marketplace-user-2','visitor-003':'marketplace-user-3'})
        self.assertNotIn(first['password'],(quickstart.DATA/'state/demo-accounts.json').read_text())

    def test_feature_permission_reauthorizes_a_workload_with_a_new_token(self):
        saved=self.install();control=Control();url=self.serve(control)
        old=saved['agents'][0]['token']
        quickstart.gateway_credentials()
        admin=quickstart.jwt(quickstart.GROUPS,'admin',generation=1)
        with patch.object(quickstart,'call',control):
            control.role='USER';control.permissions=[]
            status,_=self.post(url+'/api/quickstart/v1/workloads/public-workload/reauthorize',{},{'Authorization':'Bearer '+admin})
            self.assertEqual(status,403);self.assertEqual(self.gateway()['public-workload']['tokenSha256'],sha(old))
            control.role='ADMINISTRATOR';control.permissions=['workload_binding.rotate_credential']
            status,body=self.post(url+'/api/quickstart/v1/workloads/public-workload/reauthorize',{},{'Authorization':'Bearer '+admin})
        self.assertEqual((status,set(body)),(200,{'token'}))
        live=self.gateway()['public-workload']
        self.assertEqual(live['tokenSha256'],sha(body['token']));self.assertEqual(live['context']['credentialEpoch'],2)
        self.assertEqual(live['context']['bindingId'],'mock-binding')
        self.assertNotIn(sha(old),{c['tokenSha256'] for c in self.gateway().values()})
        rotation=next(c for c in control.calls if c[0].endswith('/credential'))
        self.assertEqual((rotation[1],rotation[2]),(admin,{'credentialSha256':sha(body['token']),'enable':True}))
        self.assertEqual(json.loads((quickstart.DATA/'credentials.json').read_text())['agents'][0]['token'],old)  # Shown once only.

    def test_reauthorizing_the_login_hint_agent_keeps_its_listed_token_live(self):
        saved=self.install();control=Control();url=self.serve(control)
        quickstart.gateway_credentials()
        admin=quickstart.jwt(quickstart.GROUPS,'admin',generation=1)
        with patch.object(quickstart,'call',control),patch.dict(os.environ,{'TOOLGATE_LOGIN_HINT_AGENT':'public-agent'}):
            status,body=self.post(url+'/api/quickstart/v1/workloads/public-workload/reauthorize',{},{'Authorization':'Bearer '+admin})
            self.assertEqual(status,200);self.assertNotEqual(body['token'],saved['agents'][0]['token'])
            self.assertEqual(quickstart.login_hints()['accounts'][0]['agentToken'],body['token'])
        path=quickstart.DATA/'credentials.json'
        self.assertEqual(path.stat().st_mode&0o777,0o600);self.assertEqual(self.gateway()['public-workload']['tokenSha256'],sha(body['token']))


if __name__=='__main__':unittest.main()
