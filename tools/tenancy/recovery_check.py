# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Qualify actual PostgreSQL 17 shared and selected-tenant archives in disposable databases."""
import base64
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid

import psycopg
from psycopg import sql
from cryptography.hazmat.primitives import hashes
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric import padding,rsa

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/enterprise'))
import postgresql_restore as recovery
from restore import DOMAIN,canonical,write
from capacity_schema import TRACKED,expression

IMAGE='postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652'


def docker(*args,**kwargs):
    return subprocess.run(['docker',*args],check=True,capture_output=True,text=True,**kwargs).stdout.strip()


def check():
    container=None;password=secrets.token_hex(32)
    with tempfile.TemporaryDirectory(prefix='toolgate-recovery-') as temporary:
        root=Path(temporary);backup=root/'backup';backup.mkdir();external=root/'external';external.mkdir()
        try:
            env=dict(os.environ,POSTGRES_PASSWORD=password)
            container=docker('run','-d','--pull=never','--memory=512m','--cpus=2','-e','POSTGRES_PASSWORD','-p','127.0.0.1::5432',IMAGE,env=env)
            port=int(json.loads(docker('inspect',container))[0]['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort'])
            superuser=dict(host='127.0.0.1',port=port,user='postgres',password=password,dbname='postgres',sslmode='disable')
            deadline=time.monotonic()+60
            while True:
                try:
                    with psycopg.connect(**superuser,autocommit=True) as c:
                        c.execute('CREATE ROLE toolgate_control_runtime NOLOGIN;CREATE ROLE toolgate_control_platform NOLOGIN',prepare=False)
                        for role,parent in [('recovery_owner','toolgate_control_platform'),('recovery_app','toolgate_control_runtime')]:
                            c.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {} IN ROLE {}').format(sql.Identifier(role),sql.Literal(password),sql.Identifier(parent)))
                        c.execute('ALTER ROLE recovery_owner CREATEDB')
                        c.execute('CREATE DATABASE live OWNER recovery_owner')
                    break
                except psycopg.OperationalError:
                    if time.monotonic()>deadline:raise RuntimeError('Recovery database readiness timeout') from None
                    time.sleep(.25)
            source=dict(superuser,user='recovery_owner',dbname='live')
            a=str(uuid.uuid4());b=str(uuid.uuid4());tenants=[a,b];now=int(time.time()*1000)
            with psycopg.connect(**source,autocommit=True) as c:
                c.execute(recovery.tenant_recovery.SCHEMA.read_text(),prepare=False)
                c.execute('GRANT CONNECT ON DATABASE live TO recovery_app')
                for index,tenant in enumerate(tenants):
                    with c.transaction():
                        c.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
                        c.execute("INSERT INTO control_tenants VALUES(%s,%s,%s,'ACTIVE',1,1)",(tenant,'tenant-'+str(index),'Tenant '+str(index)))
                        c.execute('INSERT INTO control_tenant_authority(tenant_id,revision,authorization_epoch) VALUES(%s,7,9)',(tenant,))
                        for kind,rid,fields in [('USER','same-user',dict(enabled=True)),('TEAM','team-default',dict(userIds=['same-user'],roleIds=[])),('AGENT_GROUP','default-agents',dict(agentIds=[])),('TOOL_GROUP','default-tools',dict(toolIds=[])),('DEVICE_GROUP','default-devices',dict(deviceIds=[]))]:
                            c.execute('INSERT INTO control_records VALUES(%s,%s,%s,1,%s)',(tenant,kind,rid,json.dumps(dict(id=rid,revision=1,**fields))))
                        for kind,rid,fields in [('USER','post-backup-retired',dict(enabled=True)),('WORKLOAD_BINDING','old-workload',dict(enabled=True,credentialEpoch=3)),('IDENTITY_BINDING','old-session',dict(enabled=True,sessionEpoch=2,sessionsValidAfterUnixMs=0))]:
                            c.execute('INSERT INTO control_records VALUES(%s,%s,%s,1,%s)',(tenant,kind,rid,json.dumps(dict(id=rid,revision=1,**fields))))
                        c.execute('INSERT INTO control_server_documents VALUES(%s,%s,%s)',(tenant,'sha256:'+'a'*64,'{"owner":"'+tenant+'"}'))
                        c.execute("INSERT INTO control_builder VALUES(%s,'TEST','queued',1,%s)",(tenant,json.dumps(dict(state='QUEUED',expiresAtUnixMs=now+60000))))
                        for state,task_state in [('QUEUED','QUEUED'),('EXECUTING','RUNNING'),('DISPATCHED','DISPATCHED'),('SUCCEEDED','COMPLETED')]:
                            lease='old-worker' if state=='EXECUTING' else None
                            c.execute('INSERT INTO control_enterprise_invocations(tenant_id,invocation_id,request_digest,state,authorization_epoch,created_at,expires_at,revision,document,tool_id,package_digest,effect_safety,requires_execution_ledger,lease_holder,lease_expires_at,task_id,task_state,task_owner_digest,task_retain_until,task_document) VALUES(%s,%s,%s,%s,9,1,%s,1,%s,\'tool\',%s,\'NON_IDEMPOTENT\',true,%s,%s,%s,%s,%s,%s,%s)',(tenant,state,'a'*64,state,now+60000,json.dumps(dict(id=state,state=state,revision=1)),'sha256:'+'b'*64,lease,now+60000 if lease else None,state.lower()+'_'*22,task_state,'c'*64,now+60000,json.dumps(dict(state=task_state))))
                        c.execute("INSERT INTO control_invocation_results(tenant_id,invocation_id,fencing_nonce,result_digest,byte_length,payload,created_at,retain_until) VALUES(%s,'SUCCEEDED','nonce',%s,6,%s,1,2)",(tenant,'sha256:'+hashlib.sha256(b'result').hexdigest(),b'result'))
                        c.execute("INSERT INTO control_policy_bundles(tenant_id,sequence,directory_revision,document,policy) VALUES(%s,1,7,'{}','policy')",(tenant,))
                        for purpose in ('DISCOVERY','DEVICE_CA'):
                            fingerprint=hashlib.sha256((tenant+purpose+'1').encode()).hexdigest()
                            c.execute("INSERT INTO control_tenant_identity_keys VALUES(%s,%s,'key-1','ACTIVE','private/reference','public material',%s,1)",(tenant,purpose,fingerprint))
                        c.execute("INSERT INTO control_tenant_vault_keys VALUES(%s,1,'ACTIVE','kek','opaque-wrapped-dek','opaque-wrapped-retry')",(tenant,))
                        c.execute("INSERT INTO control_secrets VALUES(%s,'same-secret','default-tools','default-devices',1,1,'opaque-ciphertext')",(tenant,))
            # Real, consistent custom-format archive with the same PostgreSQL major client/server.
            docker('exec',container,'pg_dump','-U','postgres','-Fc','-f','/tmp/shared.dump','live')
            archive=backup/'database.dump';docker('cp',container+':/tmp/shared.dump',str(archive))
            inventory={}
            with psycopg.connect(**source,autocommit=True) as c:
                for tenant in tenants:
                    with c.transaction():
                        c.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
                        c.execute("UPDATE control_tenant_identity_keys SET state='RETIRED',generation=2 WHERE tenant_id=%s",(tenant,))
                        for purpose in ('DISCOVERY','DEVICE_CA'):
                            c.execute("INSERT INTO control_tenant_identity_keys VALUES(%s,%s,'key-2','ACTIVE','private/new-reference','new public material',%s,2)",(tenant,purpose,hashlib.sha256((tenant+purpose+'2').encode()).hexdigest()))
                        c.execute("UPDATE control_tenant_vault_keys SET state='READ_ONLY' WHERE tenant_id=%s",(tenant,))
                        c.execute("INSERT INTO control_tenant_vault_keys VALUES(%s,2,'ACTIVE','kek','new-wrapped-dek','new-wrapped-retry'),(%s,3,'RETIRED','kek','retired-wrap','retired-retry')",(tenant,tenant))
                        c.execute('UPDATE control_tenant_authority SET revision=50,authorization_epoch=70 WHERE tenant_id=%s',(tenant,))
                        c.execute("DELETE FROM control_records WHERE tenant_id=%s AND record_id='post-backup-retired'",(tenant,))
                        def rows(table,columns):
                            result=c.execute(sql.SQL('SELECT {} FROM {} WHERE tenant_id=%s ORDER BY 1,2').format(sql.SQL(',').join(map(sql.Identifier,columns)),sql.Identifier(table)),(tenant,))
                            return [dict(zip(columns,row)) for row in result]
                        inventory[tenant]=dict(identityKeys=rows('control_tenant_identity_keys',['purpose','key_id','state','provider_reference','public_material','key_fingerprint','generation']),vaultKeys=rows('control_tenant_vault_keys',['version','state','provider_reference','wrapped_encryption_key','wrapped_retry_key']),cacheGenerationFloor=90,approvalClockFloor=now)
            manifest=dict(layoutVersion=3,databaseSha256=hashlib.sha256(archive.read_bytes()).hexdigest(),schemaSha256=hashlib.sha256(recovery.tenant_recovery.SCHEMA.read_bytes()).hexdigest(),tenants=tenants,keyInventory=inventory)
            reviewers=[rsa.generate_private_key(public_exponent=65537,key_size=2048) for _ in range(2)]
            def encode(value):return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
            trust=dict(keys=[dict(kid='reviewer-'+str(index),n=encode(key.public_key().public_numbers().n.to_bytes(256,'big')),e='AQAB') for index,key in enumerate(reviewers)])
            write(external/'trust.json',trust)
            counter=0
            def signed(value,omit=None):
                reviews=[]
                for tenant in value.get('tenants',tenants[:1]):
                    if tenant==omit:continue
                    authority=dict(formatVersion=1,backupManifestDigest=hashlib.sha256(canonical(value)).hexdigest(),tenantId=tenant,directoryRevisionFloor=50,authorizationEpochFloor=70,snapshotSequenceFloor=80,sessionEpochFloor=90,credentialEpochFloor=100,retiredCredentialDigests=['e'*64],retiredRecordIds=[dict(kind='USER',id='post-backup-retired')],issuedAtUnixMs=now-1000,expiresAtUnixMs=now+600000,reasonDigest='a'*64)
                    proofs=[dict(keyId='reviewer-'+str(index),signature=encode(key.sign(DOMAIN+canonical(authority),padding.PKCS1v15(),hashes.SHA256()))) for index,key in enumerate(reviewers)]
                    reviews.append(dict(authorization=authority,proofs=proofs))
                return dict(reviews=reviews)
            def snapshot():
                result={}
                ownership=json.loads((recovery.tenant_recovery.SCHEMA.parent.parent/'schema-ownership.json').read_text())
                with psycopg.connect(**source,autocommit=True) as c:
                    for tenant in tenants:
                        with c.transaction():
                            c.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
                            result[tenant]={entry['name']:sorted(map(repr,c.execute(sql.SQL('SELECT * FROM {} WHERE tenant_id=%s').format(sql.Identifier(entry['name'])),(tenant,)).fetchall())) for entry in ownership['tables']}
                return result
            before=snapshot()
            def run(value,selection=None,bundle=None,denied=False,reuse=None):
                nonlocal counter
                counter+=1;name=reuse or 'recovered_'+str(counter);folder=external/str(counter);folder.mkdir()
                if reuse is None:
                    with psycopg.connect(**superuser,autocommit=True) as c:c.execute(sql.SQL('CREATE DATABASE {} OWNER recovery_owner').format(sql.Identifier(name)))
                target=dict(source,dbname=name);write(folder/'connection.json',target);write(folder/'manifest.json',value);write(folder/'reviews.json',signed(value) if bundle is None else bundle)
                def executor(argv,**kwargs):
                    environment=kwargs['env'];environment.update(PGHOST='127.0.0.1',PGPORT='5432')
                    args=['docker','exec']
                    for field in ('PGHOST','PGPORT','PGUSER','PGPASSWORD','PGDATABASE','PGSSLMODE'):args+=['-e',field]
                    args+=[container,*argv[:-1],'/tmp/shared.dump']
                    return subprocess.run(args,env=environment,capture_output=True)
                try:result=recovery.restore(archive,folder/'manifest.json',folder/'reviews.json',external/'trust.json',folder/'connection.json',now=now,development=True,executor=executor,tenants=selection)
                except (ValueError,psycopg.Error,InvalidSignature):
                    if not denied:raise
                    result=None
                else:
                    if denied:raise AssertionError('Unsafe recovery accepted')
                with psycopg.connect(**dict(target,user='postgres'),autocommit=True) as c:
                    if not denied or c.execute('SELECT count(*) FROM pg_class WHERE relnamespace=(SELECT oid FROM pg_namespace WHERE nspname=\'public\') AND relkind=\'r\'').fetchone()[0]:
                        assert not c.execute("SELECT has_database_privilege('recovery_app',%s,'CONNECT')",(name,)).fetchone()[0]
                if not denied:
                    try:psycopg.connect(**dict(target,user='recovery_app')).close()
                    except psycopg.OperationalError:pass
                    else:raise AssertionError('Runtime connected to quarantined recovery target')
                return target,result
            shared,result=run(manifest);assert result['tenantIds']==sorted(tenants)
            selected,result=run(manifest,[a]);assert result['tenantIds']==[a]
            for target,expected in [(shared,set(tenants)),(selected,{a})]:
                with psycopg.connect(**target,autocommit=True) as c:
                    assert {r[0] for r in c.execute('SELECT tenant_id FROM control_tenants')}==expected
                    assert all(r[0]=='SUSPENDED' for r in c.execute('SELECT status FROM control_tenants'))
                    for tenant in expected:
                        with c.transaction():
                            c.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
                            revision,epoch,generation=c.execute('SELECT revision,authorization_epoch,directory_cache_generation FROM control_tenant_authority WHERE tenant_id=%s',(tenant,)).fetchone()
                            assert revision>50 and epoch>70 and generation>90
                            assert c.execute('SELECT count(*) FROM control_tenant_identity_keys WHERE tenant_id=%s AND state=\'RETIRED\'',(tenant,)).fetchone()[0]==4
                            assert c.execute('SELECT count(*) FROM control_tenant_identity_keys WHERE tenant_id=%s AND generation>2',(tenant,)).fetchone()[0]==4
                            assert c.execute("SELECT state FROM control_tenant_vault_keys WHERE tenant_id=%s AND version=3",(tenant,)).fetchone()[0]=='RETIRED'
                            assert c.execute('SELECT cipher FROM control_secrets WHERE tenant_id=%s',(tenant,)).fetchone()[0]=='opaque-ciphertext'
                            assert c.execute('SELECT queued_rows FROM control_tenant_capacity WHERE tenant_id=%s',(tenant,)).fetchone()[0]==0
                            assert c.execute("SELECT count(*) FROM control_records WHERE tenant_id=%s AND record_id='post-backup-retired'",(tenant,)).fetchone()[0]==0
                            records={kind:document for kind,document in c.execute("SELECT kind,document FROM control_records WHERE tenant_id=%s AND kind IN ('WORKLOAD_BINDING','IDENTITY_BINDING')",(tenant,))}
                            assert records['WORKLOAD_BINDING']['credentialEpoch']>100 and not records['WORKLOAD_BINDING']['enabled']
                            assert records['IDENTITY_BINDING']['sessionEpoch']>90 and records['IDENTITY_BINDING']['sessionsValidAfterUnixMs']>now
                            invocations={rid:(state,task_state,lease,json.loads(task)['state']) for rid,state,task_state,lease,task in c.execute('SELECT invocation_id,state,task_state,lease_holder,task_document FROM control_enterprise_invocations WHERE tenant_id=%s',(tenant,))}
                            assert invocations==dict(QUEUED=('EXPIRED','EXPIRED',None,'EXPIRED'),EXECUTING=('OUTCOME_UNKNOWN','OUTCOME_UNKNOWN',None,'OUTCOME_UNKNOWN'),DISPATCHED=('OUTCOME_UNKNOWN','OUTCOME_UNKNOWN',None,'OUTCOME_UNKNOWN'),SUCCEEDED=('SUCCEEDED','COMPLETED',None,'COMPLETED'))
                            assert bytes(c.execute('SELECT payload FROM control_invocation_results WHERE tenant_id=%s',(tenant,)).fetchone()[0])==b'result'
                            usage=[0,0,0]
                            for table,(fields,queued) in TRACKED.items():
                                payload,counted=expression('owned',fields,queued,True)
                                measured=c.execute(sql.SQL('SELECT coalesce(sum({}),0),count(*),coalesce(sum({}),0) FROM {} owned WHERE tenant_id=%s').format(sql.SQL(payload),sql.SQL(counted),sql.Identifier(table)),(tenant,)).fetchone()
                                usage=[x+int(y) for x,y in zip(usage,measured)]
                            assert tuple(usage)==c.execute('SELECT stored_bytes,stored_rows,queued_rows FROM control_tenant_capacity WHERE tenant_id=%s',(tenant,)).fetchone()
                    assert c.execute("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgenabled='D'").fetchone()[0]==0
            run(manifest,[a],bundle=signed(manifest,omit=b),denied=True)
            one_reviewer=signed(manifest)
            for review in one_reviewer['reviews']:review['proofs']=review['proofs'][:1]
            run(manifest,[a],bundle=one_reviewer,denied=True)
            run(manifest,[str(uuid.uuid4())],denied=True)
            tampered=signed(manifest);tampered['reviews'][0]['authorization']['directoryRevisionFloor']=0
            run(manifest,[a],bundle=tampered,denied=True)
            wrong_schema=copy.deepcopy(manifest);wrong_schema['schemaSha256']='0'*64;run(wrong_schema,[a],denied=True)
            wrong_archive=copy.deepcopy(manifest);wrong_archive['databaseSha256']='0'*64;run(wrong_archive,[a],denied=True)
            missing_tenant=copy.deepcopy(manifest);missing_tenant['tenants']=[a];del missing_tenant['keyInventory'][b];run(missing_tenant,[a],denied=True)
            stale=copy.deepcopy(manifest);stale['keyInventory'][a]['identityKeys']=[];run(stale,[a],denied=True)
            retired=copy.deepcopy(manifest);retired['keyInventory'][a]['vaultKeys'][0]['state']='RETIRED';run(retired,[a],denied=True)
            run(manifest,[a],reuse=selected['dbname'],denied=True)
            single_manifest=dict(layoutVersion=2,databaseSha256=manifest['databaseSha256'])
            run(single_manifest,bundle=signed(single_manifest)['reviews'][0],denied=True)
            assert snapshot()==before,'Recovery changed a running source tenant'
            with psycopg.connect(**dict(source,user='recovery_app')) as c:
                c.execute("SELECT set_config('app.tenant_id',%s,true)",(b,))
                assert c.execute('SELECT count(*) FROM control_records').fetchone()[0]>0
            print('PostgreSQL 17 shared/selected recovery, complete reviews, live authority/key floors, ciphertext retention, quarantine and unchanged source tenants passed.')
            return dict(postgresqlMajor=17,realArchive=True,sharedRecovery=True,selectedTenantRecovery=True,completeIndependentReviews=True,retirementsPreserved=True,authorityAndCacheFloorsAdvanced=True,oldSessionsAndCredentialsFenced=True,uncertainExecutionsQuarantined=True,encryptedSecretsPreserved=True,runtimeConnectRevoked=True,sourceTenantsUnchanged=True,nonemptyTargetRejected=True)
        finally:
            if container:subprocess.run(['docker','rm','-fv',container],capture_output=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path);args=parser.parse_args();evidence=check()
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(evidence,indent=2)+'\n')
