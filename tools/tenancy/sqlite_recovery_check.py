# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Qualify offline recovery of a stopped, real Quickstart image into an independent volume."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/enterprise'))
from restore import DOMAIN,canonical
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding,rsa


def run(arguments,**options):
    return subprocess.run(['docker',*arguments],capture_output=True,text=True,check=True,**options).stdout


def qualify(image,source_volume,output=None):
    if run(['ps','-q','--filter','volume='+source_volume]).strip():raise ValueError('Stop the source composition before offline backup qualification')
    name='toolgate-sqlite-recovery-'+secrets.token_hex(6)
    volumes=[name+'-'+suffix for suffix in ('archive','reviews','restored')]
    archive,reviews,restored=volumes
    def job(mounts,arguments,payload=None,expected_success=True):
        command=['docker','run','--rm','--network','none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',
            '--memory=512m','--pids-limit=128','--tmpfs','/tmp:rw,noexec,nosuid,size=64m,uid=65532,gid=65532']
        for volume,path,mode in mounts:command+=['-v',volume+':'+path+':'+mode]
        result=subprocess.run([*command,'-i','--entrypoint','/opt/quickstart-python/bin/python',image,*arguments],input=payload,capture_output=True,text=True)
        if expected_success and result.returncode:raise RuntimeError('SQLite image recovery job failed: '+result.stderr[-2000:])
        if not expected_success and result.returncode==0:raise AssertionError('Incomplete recovery review was accepted')
        return result.stdout
    try:
        for volume in volumes:run(['volume','create',volume])
        run(['run','--rm','--network','none','--user','0','--cap-drop=ALL','--cap-add=CHOWN',
            '-v',archive+':/archive','-v',reviews+':/reviews','-v',restored+':/restored','--entrypoint','/opt/quickstart-python/bin/python',image,
            '-c',"import os;[os.chown(p,65532,65532) for p in ('/archive','/reviews','/restored')]"])
        job([(source_volume,'/data','rw'),(archive,'/archive','rw')],['/opt/quickstart/supervisor.py','--backup','/archive/snapshot'])
        captured=json.loads(job([(archive,'/archive','ro')],['-c',"""import json,sqlite3
from pathlib import Path
manifest=json.loads(Path('/archive/snapshot/manifest.json').read_text())
c=sqlite3.connect('file:/archive/snapshot/data/state/control.sqlite?mode=ro&immutable=1',uri=True)
tenant=manifest['tenantRecovery']['tenantId']
revision,epoch=c.execute('SELECT revision,authorization_epoch FROM control_tenant_authority').fetchone()
docs=[json.loads(d) for d, in c.execute('SELECT document FROM control_records')]
sequence=max(c.execute('SELECT coalesce(max(sequence),0) FROM control_policy_bundles').fetchone()[0],c.execute('SELECT coalesce(max(snapshot_sequence),0) FROM control_recovery_floors').fetchone()[0])
floors=dict(tenantId=tenant,directoryRevisionFloor=revision,authorizationEpochFloor=epoch,snapshotSequenceFloor=sequence,sessionEpochFloor=max([d.get('sessionEpoch',0) for d in docs]+[0]),credentialEpochFloor=max([d.get('credentialEpoch',0) for d in docs]+[0]),retiredCredentialDigests=[d for d, in c.execute('SELECT credential_sha256 FROM control_credential_history')],retiredRecordIds=[dict(kind=k,id=i) for k,i in c.execute('SELECT kind,record_id FROM control_record_ids r WHERE NOT EXISTS(SELECT 1 FROM control_records c WHERE c.tenant_id=r.tenant_id AND c.kind=r.kind AND c.record_id=r.record_id)')])
print(json.dumps(dict(manifest=manifest,floors=floors)))
"""]))
        manifest=captured['manifest'];floors=captured['floors'];now=int(time.time()*1000)
        authorization=dict(floors,formatVersion=1,backupManifestDigest=hashlib.sha256(canonical(manifest)).hexdigest(),issuedAtUnixMs=now-1000,expiresAtUnixMs=now+899000,reasonDigest=hashlib.sha256(b'disposable image recovery qualification').hexdigest())
        keys=[rsa.generate_private_key(public_exponent=65537,key_size=3072) for _ in range(2)]
        encode=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=').decode()
        number=lambda value:encode(value.to_bytes((value.bit_length()+7)//8,'big'))
        trust=dict(keys=[dict(kid='reviewer-'+str(i),n=number(key.public_key().public_numbers().n),e='AQAB') for i,key in enumerate(keys)])
        proofs=[dict(keyId='reviewer-'+str(i),signature=encode(key.sign(DOMAIN+canonical(authorization),padding.PKCS1v15(),hashes.SHA256()))) for i,key in enumerate(keys)]
        documents=dict(trust=trust,review=dict(authorization=authorization,proofs=proofs),incomplete=dict(authorization=authorization,proofs=proofs[:1]))
        job([(reviews,'/reviews','rw')],['-c',"import json,sys;from pathlib import Path;docs=json.load(sys.stdin);[(Path('/reviews')/(name+'.json')).write_text(json.dumps(value)) for name,value in docs.items()];[p.chmod(0o600) for p in Path('/reviews').iterdir()]"],json.dumps(documents))
        mounts=[(archive,'/archive','ro'),(reviews,'/reviews','ro'),(restored,'/data','rw')]
        # Invoke the packaged CLI main with its normal parser/volume lock, while allowing this
        # privileged test process to retain a traceback instead of the public startup error envelope.
        arguments=['-c',"import sys;sys.path.insert(0,'/opt/quickstart');import supervisor;sys.argv=sys.argv[1:];supervisor.main()",'/opt/quickstart/supervisor.py','--restore','/archive/snapshot','--restore-trust','/reviews/trust.json','--restore-review']
        job(mounts,[*arguments,'/reviews/incomplete.json'],expected_success=False)
        job(mounts,['-c',"from pathlib import Path;assert {p.name for p in Path('/data').iterdir()}<={'service.lock'}"])
        job(mounts,[*arguments,'/reviews/review.json'])
        job(mounts,['-c',"""import json,sqlite3
from pathlib import Path
a=json.loads(Path('/reviews/review.json').read_text())['authorization']
m=json.loads(Path('/archive/snapshot/manifest.json').read_text())['tenantRecovery']
c=sqlite3.connect('/data/state/control.sqlite');old=sqlite3.connect('file:/archive/snapshot/data/state/control.sqlite?mode=ro&immutable=1',uri=True)
assert c.execute('SELECT status FROM control_tenants').fetchone()[0]=='SUSPENDED'
revision,epoch,cache=c.execute('SELECT revision,authorization_epoch,directory_cache_generation FROM control_tenant_authority').fetchone()
assert revision>a['directoryRevisionFloor'] and epoch>a['authorizationEpochFloor'] and cache>m['cacheGenerationFloor']
assert not c.execute("SELECT 1 FROM control_tenant_identity_keys WHERE state<>'RETIRED'").fetchone()
if m['identityKeys']:assert c.execute('SELECT min(generation) FROM control_tenant_identity_keys').fetchone()[0]>max(r['generation'] for r in m['identityKeys'])
for table in ('control_secrets','control_server_packages','control_server_deployments','control_server_documents','control_package_provisioning','control_fleet'):
 assert sorted(c.execute('SELECT * FROM '+table).fetchall())==sorted(old.execute('SELECT * FROM '+table).fetchall()),table
assert not c.execute("SELECT 1 FROM control_enterprise_invocations WHERE state IN ('PREPARED','EXECUTING','DISPATCHED')").fetchone()
assert not c.execute("SELECT 1 FROM control_tool_host_dispatches WHERE state IN ('QUEUED','CLAIMED','ADMITTED')").fetchone()
assert not c.execute("SELECT 1 FROM control_mcp_requests WHERE state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED')").fetchone()
assert not any(json.loads(d).get('enabled') for d, in c.execute("SELECT document FROM control_records WHERE kind IN ('GRANT','BINDING','WORKLOAD_BINDING','POLICY')"))
assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
"""])
        evidence=dict(formatVersion=1,imageId=run(['image','inspect','--format','{{.Id}}',image]).strip(),tenantId=floors['tenantId'],backupManifestDigest=authorization['backupManifestDigest'],gates=['real-image-offline-backup','independent-empty-volume','two-external-reviewers','single-review-denied-before-write','tenant-suspended','live-authority-and-key-floors','retired-signers','encrypted-data-and-packages-retained','no-runtime-rights-or-replayed-work'])
        if output:Path(output).write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        print('PASS real image SQLite backup, independent signed restore, key quarantine and retained packages/ciphertext',flush=True)
        return evidence
    finally:
        for volume in reversed(volumes):subprocess.run(['docker','volume','rm','-f',volume],capture_output=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--image',required=True);parser.add_argument('--source-volume',required=True);parser.add_argument('--output');args=parser.parse_args()
    qualify(args.image,args.source_volume,args.output)
