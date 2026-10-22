# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Signed offline SQLite restore into quarantine. Trust and live floors come from outside the backup."""
from contextlib import closing
import argparse
import base64
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import sqlite3
import time
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa

DOMAIN=b'OLO ToolGate quarantined restore v1\n'
LIMIT=9007199254740991

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def read(path):
    path=Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>2097152:raise ValueError('Bounded regular review file required')
    def pairs(items):
        out={}
        for k,v in items:
            if k in out:raise ValueError('Duplicate field')
            out[k]=v
        return out
    return json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=pairs)
def write(path,value):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),'wb') as stream:stream.write(canonical(value)+b'\n');stream.flush();os.fsync(stream.fileno())
def decode(value):return base64.urlsafe_b64decode(value+'='*(-len(value)%4))
def validate(review,trust,manifest,now):
    a=review['authorization'];required={'formatVersion','backupManifestDigest','tenantId','directoryRevisionFloor','authorizationEpochFloor','snapshotSequenceFloor','sessionEpochFloor','credentialEpochFloor','retiredCredentialDigests','retiredRecordIds','issuedAtUnixMs','expiresAtUnixMs','reasonDigest'}
    if set(a)!=required or a['formatVersion']!=1 or a['backupManifestDigest']!=hashlib.sha256(canonical(manifest)).hexdigest():raise ValueError('Exact reviewed backup required')
    if not a['issuedAtUnixMs']<=now<a['expiresAtUnixMs'] or not 0<a['expiresAtUnixMs']-a['issuedAtUnixMs']<=900000:raise ValueError('Restore review expired')
    for field in ('directoryRevisionFloor','authorizationEpochFloor','snapshotSequenceFloor','sessionEpochFloor','credentialEpochFloor'):
        if type(a[field]) is not int or not 0<=a[field]<LIMIT:raise ValueError('Explicit bounded externally observed floors required')
    digests=a['retiredCredentialDigests'];retired=a['retiredRecordIds']
    if not isinstance(digests,list) or len(digests)>100000 or any(not isinstance(d,str) or not re.fullmatch('[a-f0-9]{64}',d) for d in digests) or len(set(digests))!=len(digests):raise ValueError('External credential retirement ledger required')
    kinds={'USER','TEAM','AGENT','TOOL','POLICY','DEVICE','ROLE','DEVICE_GROUP','AGENT_GROUP','TOOL_GROUP','GRANT','DELEGATION','AGENT_DELEGATION','BINDING','EXTRACTOR','WORKLOAD_BINDING','IDENTITY_BINDING','DEVICE_EVIDENCE'}
    if not isinstance(retired,list) or len(retired)>100000:raise ValueError('External identifier retirement ledger required')
    ids=set()
    for r in retired:
        if not isinstance(r,dict) or set(r)!={'kind','id'} or r['kind'] not in kinds or not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{0,127}',r['id']):raise ValueError('Invalid retired identifier')
        key=(r['kind'],r['id'])
        if key in ids or key in {('TEAM','team-default'),('AGENT_GROUP','default-agents'),('TOOL_GROUP','default-tools'),('DEVICE_GROUP','default-devices')}:raise ValueError('Duplicate or protected retired identifier')
        ids.add(key)
    keys={k['kid']:k for k in trust['keys']}
    if len(keys)!=len(trust['keys']) or not 2<=len(keys)<=4:raise ValueError('Two to four external pinned reviewers required')
    reviewers=set();moduli=set()
    for proof in review['proofs']:
        k=keys[proof['keyId']];n=int.from_bytes(decode(k['n']),'big');e=int.from_bytes(decode(k['e']),'big')
        if proof['keyId'] in reviewers or n in moduli or not 2048<=n.bit_length()<=8192:raise ValueError('Independent RSA proofs required')
        rsa.RSAPublicNumbers(e,n).public_key().verify(decode(proof['signature']),DOMAIN+canonical(a),padding.PKCS1v15(),hashes.SHA256());reviewers.add(proof['keyId']);moduli.add(n)
    if not 2<=len(reviewers)<=4:raise ValueError('Two independent reviewers required')
    return a

def retire_records(db,tenant,a):
    """Apply post-backup retirements before any restored record can be re-enabled."""
    records={(kind,rid):json.loads(raw) for kind,rid,raw in db.execute('SELECT kind,record_id,document FROM control_records WHERE tenant_id=?',(tenant,)).fetchall()}
    retired={(r['kind'],r['id']) for r in a['retiredRecordIds']}
    credentials=set(a['retiredCredentialDigests'])
    retired.update(key for key,doc in records.items() if key[0]=='WORKLOAD_BINDING' and doc.get('credentialSha256') in credentials)
    refs={'ownerUserId':'USER','userId':'USER','delegatedUserId':'USER','agentId':'AGENT','deviceId':'DEVICE','teamId':'TEAM','agentGroupId':'AGENT_GROUP','fromAgentGroupId':'AGENT_GROUP','toAgentGroupId':'AGENT_GROUP','toolGroupId':'TOOL_GROUP','deviceGroupId':'DEVICE_GROUP','extractorId':'EXTRACTOR','parentBindingId':'WORKLOAD_BINDING'}
    # Retire dependent identity bindings and definitions too, rather than re-pointing facts.
    while True:
        more={key for key,doc in records.items() if key not in retired and key[0] not in ('TEAM','AGENT_GROUP','TOOL_GROUP','DEVICE_GROUP') and any(doc.get(field) is not None and (kind,doc[field]) in retired for field,kind in refs.items())}
        more.update(key for key,doc in records.items() if key[0]=='GRANT' and (doc.get('sourceType'),doc.get('sourceId')) in retired)
        if not more-retired:break
        retired.update(more)
    for kind,rid in sorted(retired):
        doc=records.pop((kind,rid),None)
        if doc and kind=='WORKLOAD_BINDING' and 'credentialSha256' in doc:credentials.add(doc['credentialSha256'])
        db.execute('INSERT INTO control_record_ids VALUES(?,?,?) ON CONFLICT DO NOTHING',(tenant,kind,rid))
        db.execute('DELETE FROM control_records WHERE tenant_id=? AND kind=? AND record_id=?',(tenant,kind,rid))
    for digest in sorted(credentials):db.execute('INSERT INTO control_credential_history VALUES(?,?) ON CONFLICT DO NOTHING',(tenant,digest))
    groups={'TEAM':('USER','userIds','team-default'),'AGENT_GROUP':('AGENT','agentIds','default-agents'),'TOOL_GROUP':('TOOL','toolIds','default-tools'),'DEVICE_GROUP':('DEVICE','deviceIds','default-devices')}
    for group,(entity,field,default) in groups.items():
        for (kind,rid),doc in records.items():
            if kind==group:doc[field]=[i for i in doc.get(field,[]) if (entity,i) in records]
        members={i for (kind,rid),doc in records.items() if kind==group for i in doc[field]}
        orphans=sorted(rid for kind,rid in records if kind==entity and rid not in members)
        if orphans:records[(group,default)][field].extend(orphans)
    selections={'teams':'TEAM','agentGroups':'AGENT_GROUP','toolGroups':'TOOL_GROUP','deviceGroups':'DEVICE_GROUP','approverTeams':'TEAM'}
    def prune(value):
        if isinstance(value,list):
            for item in value:prune(item)
        elif isinstance(value,dict):
            for field,item in value.items():
                if field in selections and isinstance(item,dict) and isinstance(item.get('ids'),list):item['ids']=[i for i in item['ids'] if (selections[field],i) not in retired]
                prune(item)
    for (kind,rid),doc in records.items():
        prune(doc)
        if kind=='POLICY' and doc.get('decision')=='ASK' and not doc['approverTeams']['all'] and not doc['approverTeams']['ids']:
            db.execute('DELETE FROM control_records WHERE tenant_id=? AND kind=? AND record_id=?',(tenant,kind,rid))
        else:db.execute('UPDATE control_records SET document=? WHERE tenant_id=? AND kind=? AND record_id=?',(canonical(doc).decode(),tenant,kind,rid))

def validate_key_inventory(inventory):
    for key in ('cacheGenerationFloor','approvalClockFloor'):
        if type(inventory[key]) is not int or not 0<=inventory[key]<LIMIT-1:raise ValueError('Bounded external cache/approval floor required')
    identities=inventory['identityKeys'];vault=inventory['vaultKeys']
    if not isinstance(identities,list) or len(identities)>1024 or not isinstance(vault,list) or len(vault)>1024:raise ValueError('Bounded key inventory required')
    seen=set()
    for row in identities:
        if set(row)!={'purpose','key_id','state','provider_reference','public_material','key_fingerprint','generation'} or row['purpose'] not in ('DISCOVERY','DEVICE_CA') or row['state'] not in ('ACTIVE','READ_ONLY','RETIRED'):raise ValueError('Exact identity key row required')
        if type(row['generation']) is not int or not 1<=row['generation']<LIMIT-1 or not re.fullmatch('[a-f0-9]{64}',row['key_fingerprint']):raise ValueError('Identity floor required')
        key=(row['purpose'],row['key_id'])
        if key in seen:raise ValueError('Duplicate identity key')
        seen.add(key)
    versions=set()
    for row in vault:
        if set(row)!={'version','state','provider_reference','wrapped_encryption_key','wrapped_retry_key'} or type(row['version']) is not int or not 1<=row['version']<=1024 or row['state'] not in ('ACTIVE','READ_ONLY','RETIRED') or row['version'] in versions:raise ValueError('Exact vault key inventory required')
        versions.add(row['version'])
    if sum(row['state']=='ACTIVE' for row in vault)>1:raise ValueError('Multiple active vault keys')


def quarantine_keys(connection,tenant,inventory):
    identities=inventory['identityKeys'];by_id={(r['purpose'],r['key_id']):r for r in identities}
    old=connection.execute('SELECT purpose,key_id,state,provider_reference,public_material,key_fingerprint,generation FROM control_tenant_identity_keys WHERE tenant_id=?',(tenant,)).fetchall()
    for purpose,kid,state,provider,public,fingerprint,generation in old:
        row=by_id.get((purpose,kid))
        if row is None or (provider,public,fingerprint)!=(row['provider_reference'],row['public_material'],row['key_fingerprint']) or generation>row['generation'] or state=='RETIRED' and row['state']!='RETIRED':raise ValueError('Live identity inventory cannot omit or roll back backup keys')
    generation=max([r['generation'] for r in identities]+[0])+1
    # Restored CAs/signers never become active merely by resuming the tenant.
    for row in identities:
        connection.execute("INSERT INTO control_tenant_identity_keys(tenant_id,purpose,key_id,state,provider_reference,public_material,key_fingerprint,generation) VALUES(?,?,?,'RETIRED',?,?,?,?) ON CONFLICT(tenant_id,purpose,key_id) DO UPDATE SET state='RETIRED',generation=excluded.generation",(tenant,row['purpose'],row['key_id'],row['provider_reference'],row['public_material'],row['key_fingerprint'],generation))
    vault={r['version']:r for r in inventory['vaultKeys']}
    old=connection.execute('SELECT version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key FROM control_tenant_vault_keys WHERE tenant_id=? ORDER BY version',(tenant,)).fetchall()
    for version,state,provider,encryption,retry in old:
        row=vault.get(version)
        if row is None or state=='RETIRED' and (row['state']!='RETIRED' or (provider,encryption,retry)!=(row['provider_reference'],row['wrapped_encryption_key'],row['wrapped_retry_key'])) or state=='READ_ONLY' and row['state']=='ACTIVE':raise ValueError('Live vault inventory cannot omit or revive backup keys')
        if state=='ACTIVE' and row['state']!='ACTIVE':connection.execute("UPDATE control_tenant_vault_keys SET state='READ_ONLY' WHERE tenant_id=? AND version=?",(tenant,version))
    for row in vault.values():
        if row['state']=='RETIRED' and connection.execute('SELECT 1 FROM control_secrets WHERE tenant_id=? AND key_version=? LIMIT 1',(tenant,row['version'])).fetchone():raise ValueError('Backup ciphertext uses a retired key; obtain a reviewed re-encrypted backup')
        existing=next((r for r in old if r[0]==row['version']),None)
        if existing and existing[1]=='RETIRED':continue
        connection.execute('INSERT INTO control_tenant_vault_keys(tenant_id,version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key) VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,version) DO UPDATE SET state=excluded.state,provider_reference=excluded.provider_reference,wrapped_encryption_key=excluded.wrapped_encryption_key,wrapped_retry_key=excluded.wrapped_retry_key',(tenant,row['version'],row['state'],row['provider_reference'],row['wrapped_encryption_key'],row['wrapped_retry_key']))



def quarantine(database,a,now,inventory):
    with closing(sqlite3.connect(database)) as db,db:
        db.execute('BEGIN IMMEDIATE')
        if inventory['tenantId']!=a['tenantId']:raise ValueError('Recovery inventory tenant mismatch')
        quarantine_keys(db,a['tenantId'],inventory)
        quarantine_connection(db,a,now)
        tenant=a['tenantId']
        db.execute('UPDATE control_tenant_authority SET directory_cache_generation=max(directory_cache_generation,?)+1 WHERE tenant_id=?',(inventory['cacheGenerationFloor'],tenant))
        db.execute('INSERT INTO control_approval_clocks(tenant_id,observed_at) VALUES(?,?) ON CONFLICT(tenant_id) DO UPDATE SET observed_at=max(control_approval_clocks.observed_at,excluded.observed_at)',(tenant,inventory['approvalClockFloor']+1))
        db.execute("UPDATE control_tenants SET status='SUSPENDED',updated_at=max(updated_at,?) WHERE tenant_id=?",(now,tenant))


def quarantine_connection(db,a,now,multiple=False):
    if not multiple:
        declared=db.execute('SELECT tenant_id FROM control_tenants').fetchall()
        if len(declared)!=1 or declared[0][0]!=a['tenantId']:raise ValueError('Shared databases require complete fleet recovery reviews')
    tenants=db.execute('SELECT tenant_id,revision,authorization_epoch FROM control_tenant_authority'+(' WHERE tenant_id=?' if multiple else ''),(a['tenantId'],) if multiple else ()).fetchall()
    if len(tenants)!=1 or tenants[0][0]!=a['tenantId']:raise ValueError('Single declared enterprise tenant required')
    tenant,revision,epoch=tenants[0];revision=max(revision,a['directoryRevisionFloor'])+1;epoch=max(epoch,a['authorizationEpochFloor'])+1
    if max(revision,epoch)>=LIMIT:raise ValueError('Authority exhausted')
    retire_records(db,tenant,a)
    for kind,rid,old,raw in db.execute('SELECT kind,record_id,revision,document FROM control_records WHERE tenant_id=?',(tenant,)).fetchall():
        doc=json.loads(raw)
        if kind=='USER':pass
        elif kind in ('TEAM','AGENT_GROUP','TOOL_GROUP','DEVICE_GROUP'):
            doc['enabled']=rid in ('team-default','default-agents','default-tools','default-devices')
            if 'roleIds' in doc:doc['roleIds']=[]
        elif kind=='IDENTITY_BINDING':
            doc['sessionEpoch']=max(doc['sessionEpoch'],a['sessionEpochFloor'])+1
            doc['sessionsValidAfterUnixMs']=max(doc.get('sessionsValidAfterUnixMs',0),now//1000*1000+1000)
        else:
            doc['enabled']=False
            if kind=='WORKLOAD_BINDING':doc['credentialEpoch']=max(doc['credentialEpoch'],a['credentialEpochFloor'])+1
        next_revision=max(old,a['directoryRevisionFloor'])+1
        doc['revision']=next_revision;db.execute('UPDATE control_records SET revision=?,document=? WHERE tenant_id=? AND kind=? AND record_id=?',(next_revision,canonical(doc).decode(),tenant,kind,rid))
    for rid,raw in db.execute('SELECT device_id,document FROM control_endpoints WHERE tenant_id=?',(tenant,)).fetchall():
        doc=json.loads(raw);doc['connectionApproved']=False;doc['approvalRevision']=max(doc.get('approvalRevision',1),a['directoryRevisionFloor'])+1;doc['revision']=max(doc['revision'],a['directoryRevisionFloor'])+1
        db.execute('UPDATE control_endpoints SET document=? WHERE tenant_id=? AND device_id=?',(canonical(doc).decode(),tenant,rid))
    # Old consumed nonces, retired identifiers, credential history and audit remain intact.
    for table,key in [('control_enterprise_invocations','invocation_id'),('control_enterprise_approvals','approval_id'),('control_configuration_changes','change_id')]:
        for rid,raw in db.execute(f'SELECT {key},document FROM {table} WHERE tenant_id=?',(tenant,)).fetchall():
            doc=json.loads(raw)
            if doc['state'] in ('PENDING','PENDING_APPROVAL','DRAFT','APPROVED','QUEUED','RESERVED','DISPATCHED','EXECUTING'):
                doc['state']='OUTCOME_UNKNOWN' if doc['state'] in ('DISPATCHED','EXECUTING') else ('REVOKED' if table!='control_enterprise_invocations' else 'EXPIRED');doc['revision']+=1
                if table=='control_enterprise_invocations':
                    task=db.execute('SELECT task_id,task_document FROM control_enterprise_invocations WHERE tenant_id=? AND invocation_id=?',(tenant,rid)).fetchone()
                    task_state=doc['state'] if task[0] is not None else None
                    task_document=task[1]
                    if task_document is not None:
                        value=json.loads(task_document);value['state']=task_state;task_document=canonical(value).decode()
                    db.execute('UPDATE control_enterprise_invocations SET revision=?,document=?,state=?,task_state=?,task_document=?,lease_holder=NULL,lease_expires_at=NULL WHERE tenant_id=? AND invocation_id=?',(doc['revision'],canonical(doc).decode(),doc['state'],task_state,task_document,tenant,rid))
                else:db.execute(f'UPDATE {table} SET revision=?,document=? WHERE tenant_id=? AND {key}=?',(doc['revision'],canonical(doc).decode(),tenant,rid))
    # Tool Host dispatches follow their invocations: an open one is expired, never run after restore; a finished one keeps
    # the result a repeated call returns. Packages with their bytes, deployments, deployment profile status and fleet
    # releases are restored exactly; Control's provisioning service reconciles them, installing nothing anew.
    for rid,raw in db.execute("SELECT invocation_id,document FROM control_tool_host_dispatches WHERE tenant_id=? AND state IN ('QUEUED','CLAIMED','ADMITTED')",(tenant,)).fetchall():
        doc=json.loads(raw);doc['status']['state']='EXPIRED'
        db.execute("UPDATE control_tool_host_dispatches SET state='EXPIRED',document=? WHERE tenant_id=? AND invocation_id=?",(canonical(doc).decode(),tenant,rid))
    for rid,old,raw in db.execute("SELECT record_id,revision,document FROM control_builder WHERE tenant_id=? AND kind='TEST'",(tenant,)).fetchall():
        doc=json.loads(raw)
        if doc.get('state') in ('QUEUED','RUNNING'):
            doc.update(state='FAILED',revision=old+1,error='EXPIRED')
            db.execute("UPDATE control_builder SET revision=?,document=? WHERE tenant_id=? AND kind='TEST' AND record_id=?",(old+1,canonical(doc).decode(),tenant,rid))
    for rid,raw in db.execute("SELECT request_id,record FROM control_mcp_requests WHERE tenant_id=? AND state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED')",(tenant,)).fetchall():
        doc=json.loads(raw);doc.update(state='EXPIRED',completedAtUnixMs=now)
        db.execute("UPDATE control_mcp_requests SET state='EXPIRED',record=? WHERE tenant_id=? AND request_id=?",(canonical(doc).decode(),tenant,rid))
    db.execute('UPDATE control_tenant_authority SET revision=?,authorization_epoch=? WHERE tenant_id=?',(revision,epoch,tenant))
    db.execute('INSERT INTO control_recovery_floors VALUES(?,?) ON CONFLICT(tenant_id) DO UPDATE SET snapshot_sequence=max(snapshot_sequence,excluded.snapshot_sequence)',(tenant,a['snapshotSequenceFloor']))
    db.execute('INSERT INTO control_authorization_outbox VALUES(?,?,?,?,NULL)',(tenant,revision,epoch,now))
    digest=hashlib.sha256(canonical(a)).hexdigest()
    db.execute('INSERT INTO control_audit(tenant_id,actor_id,operation,target,revision,request_id,request_digest) VALUES(?,?,?,?,?,?,?)',(tenant,hashlib.sha256(b'external-restore-review').hexdigest(),'RESTORE_QUARANTINE','directory',revision,'restore-'+digest[:32],digest))
    db.execute('DELETE FROM control_idempotency WHERE tenant_id=?',(tenant,))

def restore(source,target,review_path,trust_path,now=None):
    source=Path(source).resolve();target=Path(target).resolve();now=int(time.time()*1000) if now is None else now
    if target==source or target.is_relative_to(source) or any(p.name!='service.lock' for p in target.iterdir()):raise ValueError('Empty independent restore destination required')
    # Reviewed public trust must be separately mounted, not an authority selected by the archived backup.
    trust_file=Path(trust_path).resolve();review_file=Path(review_path).resolve()
    if trust_file.is_relative_to(source) or trust_file.is_relative_to(target):raise ValueError('External pinned restore trust required')
    manifest=read(source/'manifest.json');a=validate(read(review_file),read(trust_file),manifest,now)
    if manifest['layoutVersion']!=1 or set(manifest)!={'layoutVersion','files','tenantRecovery'}:raise ValueError('Complete current SQLite backup and external key inventory required')
    inventory=manifest['tenantRecovery']
    if set(inventory)!={'tenantId','identityKeys','vaultKeys','cacheGenerationFloor','approvalClockFloor'}:raise ValueError('Complete SQLite recovery key inventory required')
    if inventory['tenantId']!=a['tenantId'] or any(type(inventory[key]) is not int or not 0<=inventory[key]<LIMIT-1 for key in ('cacheGenerationFloor','approvalClockFloor')):raise ValueError('Explicit bounded tenant recovery floors required')
    validate_key_inventory(inventory)
    if any(p.is_symlink() for p in (source/'data').rglob('*')):raise ValueError('Unsafe backup link')
    actual={p.relative_to(source/'data').as_posix() for p in (source/'data').rglob('*') if p.is_file()}
    if actual!=set(manifest['files']):raise ValueError('Backup inventory mismatch')
    for name,digest in manifest['files'].items():
        path=source/'data'/name
        if Path(name).is_absolute() or '..' in Path(name).parts or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise ValueError('Backup integrity failure')
    # Stage on the writable destination volume: its parent is read-only in the production image.
    # The marker prevents a partial file publication from being mistaken for a fresh installation.
    stage=target/('.quarantine-'+os.urandom(8).hex());stage.mkdir(mode=0o700)
    try:
        shutil.copytree(source/'data',stage,dirs_exist_ok=True);quarantine(stage/'state/control.sqlite',a,now,inventory)
        for filename in ('identity.json','identity-reviewer-1.json','identity-reviewer-2.json'):
            path=stage/filename
            if path.exists():doc=read(path);doc['generation']=doc.get('generation',1)+1;path.write_bytes(canonical(doc)+b'\n')
        for path in stage.rglob('*'):path.chmod(0o700 if path.is_dir() else 0o600)
        marker=target/'restore.pending';write(marker,dict(formatVersion=1,backupManifestDigest=a['backupManifestDigest']))
        for child in stage.iterdir():shutil.move(str(child),target/child.name)
        marker.unlink()
    finally:shutil.rmtree(stage)
    return a

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('prepare');p.add_argument('--manifest',required=True);p.add_argument('--floors',required=True);p.add_argument('--reason-file',required=True);p.add_argument('--output',required=True)
    p=sub.add_parser('sign');p.add_argument('--authorization',required=True);p.add_argument('--private-key',required=True);p.add_argument('--key-id',required=True);p.add_argument('--output',required=True)
    p=sub.add_parser('assemble');p.add_argument('--authorization',required=True);p.add_argument('--proofs',nargs='+',required=True);p.add_argument('--output',required=True)
    p=sub.add_parser('bundle');p.add_argument('--reviews',nargs='+',required=True);p.add_argument('--output',required=True)
    args=parser.parse_args()
    if args.command=='prepare':
        a=read(args.floors);now=int(time.time()*1000);a.update(formatVersion=1,backupManifestDigest=hashlib.sha256(canonical(read(args.manifest))).hexdigest(),reasonDigest=hashlib.sha256(Path(args.reason_file).read_bytes()).hexdigest(),issuedAtUnixMs=now,expiresAtUnixMs=now+900000);write(args.output,a)
    elif args.command=='sign':
        path=Path(args.private_key)
        if path.is_symlink() or path.stat().st_size>16384 or os.name!='nt' and (path.stat().st_mode&0o077 or path.stat().st_uid not in (0,os.geteuid())):raise ValueError('Protected independent RSA key required')
        key=serialization.load_pem_private_key(path.read_bytes(),None)
        if not isinstance(key,rsa.RSAPrivateKey) or not 2048<=key.key_size<=8192:raise ValueError('RSA reviewer required')
        proof=key.sign(DOMAIN+canonical(read(args.authorization)),padding.PKCS1v15(),hashes.SHA256());write(args.output,dict(keyId=args.key_id,signature=base64.urlsafe_b64encode(proof).rstrip(b'=').decode()))
    elif args.command=='assemble':write(args.output,dict(authorization=read(args.authorization),proofs=[read(p) for p in args.proofs]))
    else:write(args.output,dict(reviews=[read(p) for p in args.reviews]))
    print('Restore review written; offline restore validates external trust and quarantines all prior authority.')
if __name__=='__main__':main()
