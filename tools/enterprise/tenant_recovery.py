# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Reviewed fleet recovery helpers. Only invoked on a fresh database with CONNECT revoked."""
import hashlib
import json
import re
import uuid
from pathlib import Path
from psycopg import sql
from restore import LIMIT, canonical, validate, quarantine_connection

ROOT=Path(__file__).resolve().parents[2]
SCHEMA=ROOT/'apps/control-plane/src/main/resources/db/migration/V1__initial_schema.sql'


def reviewed(manifest,bundle,trust,now,selected):
    if set(manifest)!={'layoutVersion','databaseSha256','schemaSha256','tenants','keyInventory'} or manifest['layoutVersion']!=3:
        raise ValueError('Exact fleet recovery manifest required')
    if manifest['schemaSha256']!=hashlib.sha256(SCHEMA.read_bytes()).hexdigest():raise ValueError('Matching release schema required')
    tenants=manifest['tenants']
    if not isinstance(tenants,list) or not 1<=len(tenants)<=4096 or len(set(tenants))!=len(tenants):raise ValueError('Bounded unique tenant inventory required')
    if any(not isinstance(t,str) or str(uuid.UUID(t))!=t for t in tenants):raise ValueError('Canonical tenant UUID required')
    if set(manifest['keyInventory'])!=set(tenants):raise ValueError('Every tenant requires externally reviewed live key inventory')
    if set(bundle)!={'reviews'} or not isinstance(bundle['reviews'],list) or len(bundle['reviews'])!=len(tenants):raise ValueError('Every archived tenant needs an independent review')
    approvals={}
    for review in bundle['reviews']:
        approval=validate(review,trust,manifest,now);tenant=approval['tenantId']
        if tenant not in tenants or tenant in approvals:raise ValueError('Exact tenant review set required')
        approvals[tenant]=approval
    selected=set(tenants) if selected is None else set(selected)
    if not selected or not selected<=set(tenants):raise ValueError('Explicit archived tenant selection required')
    for tenant,inventory in manifest['keyInventory'].items():
        if set(inventory)!={'identityKeys','vaultKeys','cacheGenerationFloor','approvalClockFloor'}:raise ValueError('Complete live key and cache inventory required')
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
    return approvals,selected


def table_order(connection,names):
    """Dependents first, with identifiers taken only from the matching ownership manifest."""
    dependencies={name:set() for name in names}
    for child,parent in connection.execute("SELECT c.relname,p.relname FROM pg_constraint f JOIN pg_class c ON c.oid=f.conrelid JOIN pg_class p ON p.oid=f.confrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE f.contype='f' AND n.nspname='public'"):
        if child not in dependencies or parent not in dependencies:raise ValueError('Unclassified recovery dependency')
        if child!=parent:dependencies[child].add(parent)
    ordered=[]
    while dependencies:
        leaves=sorted(set(dependencies)-{parent for parents in dependencies.values() for parent in parents})
        if not leaves:raise ValueError('Cyclic recovery ownership requires review')
        ordered.extend(leaves)
        for leaf in leaves:del dependencies[leaf]
    return ordered


def keys(connection,tenant,inventory):
    identities=inventory['identityKeys'];by_id={(r['purpose'],r['key_id']):r for r in identities}
    old=connection.execute('SELECT purpose,key_id,state,provider_reference,public_material,key_fingerprint,generation FROM control_tenant_identity_keys WHERE tenant_id=%s',(tenant,)).fetchall()
    for purpose,kid,state,provider,public,fingerprint,generation in old:
        row=by_id.get((purpose,kid))
        if row is None or (provider,public,fingerprint)!=(row['provider_reference'],row['public_material'],row['key_fingerprint']) or generation>row['generation'] or state=='RETIRED' and row['state']!='RETIRED':raise ValueError('Live identity inventory cannot omit or roll back backup keys')
    generation=max([r['generation'] for r in identities]+[0])+1
    # Restored CAs/signers never become active merely by resuming the tenant.
    for row in identities:
        connection.execute("INSERT INTO control_tenant_identity_keys(tenant_id,purpose,key_id,state,provider_reference,public_material,key_fingerprint,generation) VALUES(%s,%s,%s,'RETIRED',%s,%s,%s,%s) ON CONFLICT(tenant_id,purpose,key_id) DO UPDATE SET state='RETIRED',generation=excluded.generation",(tenant,row['purpose'],row['key_id'],row['provider_reference'],row['public_material'],row['key_fingerprint'],generation))
    vault={r['version']:r for r in inventory['vaultKeys']}
    old=connection.execute('SELECT version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key FROM control_tenant_vault_keys WHERE tenant_id=%s ORDER BY version',(tenant,)).fetchall()
    for version,state,provider,encryption,retry in old:
        row=vault.get(version)
        if row is None or state=='RETIRED' and (row['state']!='RETIRED' or (provider,encryption,retry)!=(row['provider_reference'],row['wrapped_encryption_key'],row['wrapped_retry_key'])) or state=='READ_ONLY' and row['state']=='ACTIVE':raise ValueError('Live vault inventory cannot omit or revive backup keys')
        if state=='ACTIVE' and row['state']!='ACTIVE':connection.execute("UPDATE control_tenant_vault_keys SET state='READ_ONLY' WHERE tenant_id=%s AND version=%s",(tenant,version))
    for row in vault.values():
        if row['state']=='RETIRED' and connection.execute('SELECT 1 FROM control_secrets WHERE tenant_id=%s AND key_version=%s LIMIT 1',(tenant,row['version'])).fetchone():raise ValueError('Backup ciphertext uses a retired key; obtain a reviewed re-encrypted backup')
        existing=next((r for r in old if r[0]==row['version']),None)
        if existing and existing[1]=='RETIRED':continue
        connection.execute('INSERT INTO control_tenant_vault_keys(tenant_id,version,state,provider_reference,wrapped_encryption_key,wrapped_retry_key) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(tenant_id,version) DO UPDATE SET state=excluded.state,provider_reference=excluded.provider_reference,wrapped_encryption_key=excluded.wrapped_encryption_key,wrapped_retry_key=excluded.wrapped_retry_key',(tenant,row['version'],row['state'],row['provider_reference'],row['wrapped_encryption_key'],row['wrapped_retry_key']))


def quarantine(connection,manifest,approvals,selected,ownership,adapter,now):
    if not connection.execute("SELECT pg_has_role(session_user,'toolgate_control_platform','MEMBER')").fetchone()[0]:raise ValueError('Offline owner must also hold the platform role for forced RLS')
    names={entry['name'] for entry in ownership['tables']}
    actual={row[0] for row in connection.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'")}
    if actual!=names:raise ValueError('Unclassified or missing recovery tables')
    connection.execute(sql.SQL('LOCK TABLE {} IN ACCESS EXCLUSIVE MODE').format(sql.SQL(',').join(sql.Identifier('public',name) for name in sorted(names))))
    if connection.execute("SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgenabled NOT IN ('O','A')").fetchone()[0]:raise ValueError('Recovery requires enabled ownership, lifecycle and accounting triggers')
    tenants={row[0] for row in connection.execute('SELECT tenant_id FROM control_tenants')}
    if tenants!=set(manifest['tenants']):raise ValueError('Archive tenant set differs from signed inventory')
    for entry in ownership['tables']:
        if entry['classification']=='tenant-data':
            enabled,forced=connection.execute('SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid=%s::regclass',('public.'+entry['name'],)).fetchone()
            if not enabled or not forced:raise ValueError('Recovery requires enabled forced tenant RLS')
    # Remove unselected archived customers only inside this newly restored offline target.
    # Disabling owner retention triggers permits deliberate offboarding; FK and RLS remain enabled.
    if tenants-selected:
        for name in sorted(names):connection.execute(sql.SQL('ALTER TABLE {} DISABLE TRIGGER USER').format(sql.Identifier('public',name)))
        for tenant in sorted(tenants-selected):
            connection.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
            for name in table_order(connection,names):connection.execute(sql.SQL('DELETE FROM {} WHERE tenant_id=%s').format(sql.Identifier('public',name)),(tenant,))
        for name in sorted(names):connection.execute(sql.SQL('ALTER TABLE {} ENABLE TRIGGER USER').format(sql.Identifier('public',name)))
    for tenant in sorted(selected):
        connection.execute("SELECT set_config('app.tenant_id',%s,true)",(tenant,))
        inventory=manifest['keyInventory'][tenant]
        keys(connection,tenant,inventory)
        quarantine_connection(adapter(connection),approvals[tenant],now,multiple=True)
        for rid,revision,document in connection.execute("SELECT record_id,revision,document FROM control_builder WHERE tenant_id=%s AND kind='TEST' AND document::jsonb->>'state' IN ('QUEUED','RUNNING')",(tenant,)).fetchall():
            value=json.loads(document);value.update(state='FAILED',revision=revision+1,error='EXPIRED')
            connection.execute("UPDATE control_builder SET revision=revision+1,document=%s WHERE tenant_id=%s AND kind='TEST' AND record_id=%s",(canonical(value).decode(),tenant,rid))
        for rid,document in connection.execute("SELECT request_id,record FROM control_mcp_requests WHERE tenant_id=%s AND state IN ('RECEIVED','WAITING_FOR_POLL','SUBMITTED')",(tenant,)).fetchall():
            value=json.loads(document);value.update(state='EXPIRED',completedAtUnixMs=now)
            connection.execute("UPDATE control_mcp_requests SET state='EXPIRED',record=%s WHERE tenant_id=%s AND request_id=%s",(canonical(value).decode(),tenant,rid))
        connection.execute('UPDATE control_tenant_authority SET directory_cache_generation=greatest(directory_cache_generation,%s)+1 WHERE tenant_id=%s',(inventory['cacheGenerationFloor'],tenant))
        connection.execute('INSERT INTO control_approval_clocks(tenant_id,observed_at) VALUES(%s,%s) ON CONFLICT(tenant_id) DO UPDATE SET observed_at=greatest(control_approval_clocks.observed_at,excluded.observed_at)',(tenant,inventory['approvalClockFloor']+1))
        connection.execute("UPDATE control_tenants SET status='SUSPENDED',updated_at=greatest(updated_at,%s) WHERE tenant_id=%s AND status IN ('ACTIVE','SUSPENDED')",(now,tenant))
        if connection.execute('SELECT status FROM control_tenants WHERE tenant_id=%s',(tenant,)).fetchone()[0]!='SUSPENDED':raise ValueError('Only initialized tenants can be recovered')
    if {row[0] for row in connection.execute('SELECT tenant_id FROM control_tenants')}!=selected:raise ValueError('Unexpected tenant survived selection')
