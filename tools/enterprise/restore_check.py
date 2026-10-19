# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Real PostgreSQL dump/restore quarantine with post-backup retirements and external reviewers."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding,rsa
import psycopg
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from control.container import fixture
from control.check import ROOT,run
from postgresql_restore import restore
from restore import DOMAIN,canonical,write


def main():
    with fixture('olo-toolgate-control:enterprise-production') as f,tempfile.TemporaryDirectory(prefix='toolgate-restore-') as temp:
        db=f['db'];root=Path(temp);backups=root/'backup';backups.mkdir();archive=backups/'state.dump'
        # Stop all authority before backup. The target is a different empty database.
        for container in f['containers']:run(['docker','stop','-t','30',container],capture_output=True)
        archive.write_bytes(run(['docker','exec',db['container'],'pg_dump','-U','postgres','-Fc','control'],capture_output=True).stdout)
        run(['docker','exec',db['container'],'createdb','-U','postgres','-O','control_migrator','quarantine'],capture_output=True)
        manifest=dict(layoutVersion=2,databaseSha256=hashlib.sha256(archive.read_bytes()).hexdigest());write(backups/'manifest.json',manifest)
        now=int(time.time()*1000)
        a=dict(formatVersion=1,backupManifestDigest=hashlib.sha256(canonical(manifest)).hexdigest(),tenantId='local',directoryRevisionFloor=1000,authorizationEpochFloor=1000,snapshotSequenceFloor=1000,sessionEpochFloor=1000,credentialEpochFloor=1000,retiredCredentialDigests=['e'*64],retiredRecordIds=[dict(kind='USER',id='admin')],issuedAtUnixMs=now-1000,expiresAtUnixMs=now+60000,reasonDigest='a'*64)
        # Actual tenant is installation-configured; do not assume its name.
        source=dict(host='127.0.0.1',port=int(db['port']),dbname='control',user='control_migrator',password=db['CONTROL_TEST_PASSWORD'],sslmode='disable')
        with psycopg.connect(**source) as connection:
            a['tenantId']=connection.execute('SELECT tenant_id FROM control_tenants').fetchone()[0]
            connection.execute("SELECT set_config('app.tenant_id', %s, true)",(a['tenantId'],))
            retired_user=connection.execute("SELECT document->>'userId' FROM control_records WHERE kind='IDENTITY_BINDING' AND document->>'subject'='admin'").fetchone()[0]
            a['retiredRecordIds']=[dict(kind='USER',id=retired_user)]
        encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=').decode()
        number=lambda n:encode(n.to_bytes((n.bit_length()+7)//8,'big'))
        keys=[rsa.generate_private_key(public_exponent=65537,key_size=2048) for _ in range(2)]
        trust=dict(keys=[dict(kid='reviewer-'+str(i),n=number(k.public_key().public_numbers().n),e='AQAB') for i,k in enumerate(keys)])
        proofs=[dict(keyId='reviewer-'+str(i),signature=encode(k.sign(DOMAIN+canonical(a),padding.PKCS1v15(),hashes.SHA256()))) for i,k in enumerate(keys)]
        write(root/'trust.json',trust);write(root/'review.json',dict(authorization=a,proofs=proofs));target={**source,'dbname':'quarantine'};write(root/'connection.json',target)
        def execute(args,env,capture_output):
            # The owned PostgreSQL fixture supplies pg_restore; secrets travel only in env/stdin.
            local_env=dict(os.environ,PGUSER=target['user'],PGPASSWORD=target['password'],PGDATABASE=target['dbname'])
            return subprocess.run(['docker','exec','-i','-e','PGUSER','-e','PGPASSWORD','-e','PGDATABASE',db['container'],'pg_restore','--exit-on-error','--no-owner','--dbname',target['dbname']],input=archive.read_bytes(),env=local_env,capture_output=True)
        result=restore(archive,backups/'manifest.json',root/'review.json',root/'trust.json',root/'connection.json',now=now,development=True,executor=execute)
        assert result['runtimeConnectEnabled'] is False
        with psycopg.connect(**target) as connection:
            connection.execute("SELECT set_config('app.tenant_id', %s, true)",(a['tenantId'],))
            revision,epoch=connection.execute('SELECT revision,authorization_epoch FROM control_tenants').fetchone();assert revision==1001 and epoch==1001
            assert connection.execute("SELECT count(*) FROM control_records WHERE kind='USER' AND record_id=%s",(retired_user,)).fetchone()[0]==0
            assert connection.execute("SELECT count(*) FROM control_record_ids WHERE kind='USER' AND record_id=%s",(retired_user,)).fetchone()[0]==1
            assert connection.execute("SELECT count(*) FROM control_records WHERE kind='ROLE' AND (document->>'enabled')::boolean").fetchone()[0]==0
            assert connection.execute("SELECT count(*) FROM control_records WHERE kind='IDENTITY_BINDING' AND (document->>'sessionEpoch')::bigint<=1000").fetchone()[0]==0
            assert connection.execute("SELECT has_database_privilege('control_app','quarantine','CONNECT')").fetchone()[0] is False
            assert connection.execute("SELECT operation FROM control_audit ORDER BY sequence DESC LIMIT 1").fetchone()[0]=='RESTORE_QUARANTINE'
        # Reuse against a nonempty database is rejected without resetting anything.
        try:restore(archive,backups/'manifest.json',root/'review.json',root/'trust.json',root/'connection.json',now=now,development=True,executor=execute)
        except ValueError:pass
        else:raise AssertionError('Nonempty restore accepted')
        output=ROOT/'build/enterprise';output.mkdir(parents=True,exist_ok=True)
        (output/'postgresql-restore.json').write_text(json.dumps(dict(realDumpRestore=True,twoExternalReviews=True,retirementsPreserved=True,sessionEpochsMonotonic=True,runtimeConnectRevoked=True,nonemptyTargetRejected=True,transport='isolated loopback fixture; production requires verify-full'),indent=2)+'\n',encoding='utf-8')
        print('Real PostgreSQL reviewed dump/restore/quarantine/retirement/epoch/runtime isolation passed')
if __name__=='__main__':main()
