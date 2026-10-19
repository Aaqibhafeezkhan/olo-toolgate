# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Restore authority cannot come from archived keys or revive retired runtime rights."""
from contextlib import closing
import base64,hashlib,importlib.util,json,sqlite3,tempfile,unittest
from pathlib import Path
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding,rsa
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('restore_gate',ROOT/'tools/enterprise/restore.py');restore=importlib.util.module_from_spec(spec);spec.loader.exec_module(restore)
class RestoreTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.source=self.root/'backup';self.data=self.source/'data';(self.data/'state').mkdir(parents=True);self.target=self.root/'restored';self.target.mkdir();self.now=1800000000000
  db=self.data/'state/control.sqlite'
  with closing(sqlite3.connect(db)) as c,c:
   # Complete first-release SQLite schema, executed by the Quickstart adapter.
   script=(ROOT/'apps/control-plane/src/main/resources/db/quickstart/V1.sql').read_text()
   c.executescript(script)
   c.execute("INSERT INTO control_tenants VALUES('00000000-0000-4000-8000-000000000001','test','Test','ACTIVE',1,1)")
   c.execute("INSERT INTO control_tenant_authority(tenant_id,revision,authorization_epoch) VALUES('00000000-0000-4000-8000-000000000001',7,9)")
   records=[('USER',dict(id='user',name='User',enabled=True,revision=1)),('TEAM',dict(id='team-default',name='Default',enabled=True,revision=1,userIds=['user'],roleIds=['management'])),('ROLE',dict(id='management',enabled=True,revision=1)),('WORKLOAD_BINDING',dict(id='workload',enabled=True,revision=1,credentialEpoch=3)),('IDENTITY_BINDING',dict(id='identity',enabled=True,revision=1,sessionEpoch=2,sessionsValidAfterUnixMs=0))]
   for kind,doc in records:c.execute('INSERT INTO control_records(tenant_id,kind,record_id,revision,document) VALUES(?,?,?,?,?)',('00000000-0000-4000-8000-000000000001',kind,doc['id'],1,json.dumps(doc)))
   c.execute("INSERT INTO control_enterprise_invocations(tenant_id,invocation_id,request_digest,state,authorization_epoch,created_at,expires_at,revision,document) VALUES('00000000-0000-4000-8000-000000000001','running',?,'EXECUTING',9,1,2,1,?)",('a'*64,json.dumps(dict(id='running',state='EXECUTING',revision=1))))
   c.execute("INSERT INTO control_enterprise_nonces(tenant_id,nonce,invocation_id,evaluation_digest,authorization_epoch,expires_at,consumed_at) VALUES('00000000-0000-4000-8000-000000000001','consumed','running',?,9,2,1)",('a'*64,))
   c.execute("INSERT INTO control_credential_history(tenant_id,credential_sha256) VALUES('00000000-0000-4000-8000-000000000001',?)",('f'*64,))
   # Package, deployment, profile and release state, which restore keeps exactly, and Tool Host dispatches.
   package='sha256:'+'b'*64
   c.execute("INSERT INTO control_server_packages(tenant_id,package_digest,package_id,version,uploaded_at,document,content) VALUES('00000000-0000-4000-8000-000000000001',?,'acme.orders','1.0.0',5,?,?)",(package,json.dumps(dict(packageDigest=package)),b'PK exact package bytes'))
   c.execute("INSERT INTO control_server_deployments(tenant_id,package_id,document) VALUES('00000000-0000-4000-8000-000000000001','acme.orders',?)",(json.dumps(dict(packageId='acme.orders',packageDigest=package,revision=3)),))
   c.execute("INSERT INTO control_server_documents(tenant_id,digest,document) VALUES('00000000-0000-4000-8000-000000000001',?,?)",('c'*64,json.dumps(dict(kind='globals'))))
   c.execute("INSERT INTO control_package_provisioning(tenant_id,profile_id,generation,profile_digest,document) VALUES('00000000-0000-4000-8000-000000000001','fresh',4,?,?)",('sha256:'+'d'*64,json.dumps(dict(profileId='fresh',generation=4,state='READY'))))
   c.execute("INSERT INTO control_fleet(tenant_id,kind,record_id,revision,document) VALUES('00000000-0000-4000-8000-000000000001','RELEASE','acme.orders@1.0.0',1,?)",(json.dumps(dict(packageId='acme.orders',version='1.0.0')),))
   for invocation,state in (('queued','QUEUED'),('claimed','CLAIMED'),('admitted','ADMITTED'),('finished','FINISHED')):
    c.execute("INSERT INTO control_tool_host_dispatches(tenant_id,invocation_id,host_id,state,expires_at,created_at,document) VALUES('00000000-0000-4000-8000-000000000001',?,'host',?,9,1,?)",
              (invocation,state,json.dumps(dict(status=dict(invocationId=invocation,hostId='host',state=state,**({'result':{'outcome':'COMPLETED'}} if state=='FINISHED' else {}))))))
  self.manifest=dict(layoutVersion=1,files={p.relative_to(self.data).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in self.data.rglob('*') if p.is_file()});restore.write(self.source/'manifest.json',self.manifest)
  self.keys=[rsa.generate_private_key(public_exponent=65537,key_size=2048) for _ in range(2)]
  encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=').decode()
  number=lambda n:encode(n.to_bytes((n.bit_length()+7)//8,'big'))
  self.trust=dict(keys=[dict(kid='reviewer-'+str(n),n=number(k.public_key().public_numbers().n),e='AQAB') for n,k in enumerate(self.keys)]);restore.write(self.root/'trust.json',self.trust)
  self.authorization=dict(formatVersion=1,backupManifestDigest=hashlib.sha256(restore.canonical(self.manifest)).hexdigest(),tenantId='00000000-0000-4000-8000-000000000001',directoryRevisionFloor=100,authorizationEpochFloor=200,snapshotSequenceFloor=20,sessionEpochFloor=30,credentialEpochFloor=40,retiredCredentialDigests=['e'*64],retiredRecordIds=[dict(kind='USER',id='post-backup-retired-user')],issuedAtUnixMs=self.now-1000,expiresAtUnixMs=self.now+60000,reasonDigest='a'*64)
  self.proofs=[dict(keyId='reviewer-'+str(n),signature=encode(k.sign(restore.DOMAIN+restore.canonical(self.authorization),padding.PKCS1v15(),hashes.SHA256()))) for n,k in enumerate(self.keys)]
  restore.write(self.root/'review.json',dict(authorization=self.authorization,proofs=self.proofs))
 def tearDown(self):self.temp.cleanup()
 def test_external_floors_quarantine_sessions_credentials_effects_and_management(self):
  restore.restore(self.source,self.target,self.root/'review.json',self.root/'trust.json',self.now)
  with closing(sqlite3.connect(self.target/'state/control.sqlite')) as c,c:
   self.assertEqual(c.execute('SELECT revision,authorization_epoch FROM control_tenant_authority').fetchone(),(101,201));self.assertEqual(c.execute('SELECT snapshot_sequence FROM control_recovery_floors').fetchone()[0],20)
   records={kind:json.loads(doc) for kind,doc in c.execute('SELECT kind,document FROM control_records')}
   self.assertTrue(records['USER']['enabled']);self.assertEqual(records['TEAM']['roleIds'],[]);self.assertFalse(records['ROLE']['enabled']);self.assertFalse(records['WORKLOAD_BINDING']['enabled']);self.assertEqual(records['WORKLOAD_BINDING']['credentialEpoch'],41);self.assertEqual(records['IDENTITY_BINDING']['sessionEpoch'],31)
   self.assertEqual(json.loads(c.execute('SELECT document FROM control_enterprise_invocations').fetchone()[0])['state'],'OUTCOME_UNKNOWN');self.assertEqual(c.execute('SELECT consumed_at FROM control_enterprise_nonces').fetchone()[0],1);self.assertEqual(c.execute('SELECT count(*) FROM control_credential_history').fetchone()[0],2);self.assertEqual(c.execute('SELECT operation FROM control_audit').fetchone()[0],'RESTORE_QUARANTINE')
   self.assertEqual(c.execute("SELECT count(*) FROM control_record_ids WHERE record_id='post-backup-retired-user'").fetchone()[0],1)
 def test_packages_deployments_profiles_and_releases_restore_exactly_and_open_dispatches_expire(self):
  tables=('control_server_packages','control_server_deployments','control_server_documents','control_package_provisioning','control_fleet')
  def rows(path):
   with closing(sqlite3.connect(path)) as c:return {t:sorted(c.execute(f'SELECT * FROM {t}').fetchall()) for t in tables}
  before=rows(self.data/'state/control.sqlite')
  restore.restore(self.source,self.target,self.root/'review.json',self.root/'trust.json',self.now)
  self.assertEqual(before,rows(self.target/'state/control.sqlite'))
  with closing(sqlite3.connect(self.target/'state/control.sqlite')) as c:
   dispatches={rid:(state,json.loads(doc)['status']) for rid,state,doc in c.execute('SELECT invocation_id,state,document FROM control_tool_host_dispatches')}
  for invocation in ('queued','claimed','admitted'):
   self.assertEqual(dispatches[invocation][0],'EXPIRED');self.assertEqual(dispatches[invocation][1]['state'],'EXPIRED')
  # A finished call keeps the result a repeated call returns.
  self.assertEqual(dispatches['finished'],('FINISHED',dict(invocationId='finished',hostId='host',state='FINISHED',result={'outcome':'COMPLETED'})))
 def test_one_reviewer_and_self_selected_archived_trust_are_rejected(self):
  with self.assertRaises(ValueError):restore.validate(dict(authorization=self.authorization,proofs=self.proofs[:1]),self.trust,self.manifest,self.now)
  restore.write(self.source/'trust.json',self.trust)
  with self.assertRaises(ValueError):restore.restore(self.source,self.target,self.root/'review.json',self.source/'trust.json',self.now)
  self.assertEqual(list(self.target.iterdir()),[])
 def test_post_backup_retired_active_user_and_credential_cannot_return(self):
  a={**self.authorization,'retiredRecordIds':[dict(kind='USER',id='user')],'retiredCredentialDigests':['a'*64]}
  with closing(sqlite3.connect(self.data/'state/control.sqlite')) as c,c:
   doc=json.loads(c.execute("SELECT document FROM control_records WHERE kind='WORKLOAD_BINDING'").fetchone()[0]);doc['credentialSha256']='a'*64;c.execute("UPDATE control_records SET document=? WHERE kind='WORKLOAD_BINDING'",(json.dumps(doc),))
   restore.quarantine_connection(c,a,self.now)
   self.assertEqual(c.execute("SELECT count(*) FROM control_records WHERE kind IN ('USER','WORKLOAD_BINDING')").fetchone()[0],0)
   self.assertEqual(json.loads(c.execute("SELECT document FROM control_records WHERE kind='TEAM'").fetchone()[0])['userIds'],[])
   self.assertEqual(c.execute("SELECT count(*) FROM control_credential_history WHERE credential_sha256=?",('a'*64,)).fetchone()[0],1)
   self.assertEqual(c.execute("SELECT count(*) FROM control_record_ids WHERE record_id IN ('user','workload')").fetchone()[0],2)
 def test_tamper_expiry_and_nonempty_restore_never_modify_destination(self):
  with self.assertRaises(ValueError):restore.restore(self.source,self.target,self.root/'review.json',self.root/'trust.json',self.now+60000)
  (self.data/'state/control.sqlite').write_bytes(b'tampered')
  with self.assertRaises(ValueError):restore.restore(self.source,self.target,self.root/'review.json',self.root/'trust.json',self.now)
  self.assertEqual(list(self.target.iterdir()),[])
if __name__=='__main__':unittest.main()
