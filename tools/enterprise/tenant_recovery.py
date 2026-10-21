# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Reviewed fleet recovery helpers. Only invoked on a fresh database with CONNECT revoked."""
import hashlib
import json
import re
import uuid
from pathlib import Path
from psycopg import sql
from restore import LIMIT, canonical, validate, quarantine_connection, quarantine_keys, validate_key_inventory

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
        validate_key_inventory(inventory)
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
        quarantine_keys(adapter(connection),tenant,inventory)
        quarantine_connection(adapter(connection),approvals[tenant],now,multiple=True)
        connection.execute('UPDATE control_tenant_authority SET directory_cache_generation=greatest(directory_cache_generation,%s)+1 WHERE tenant_id=%s',(inventory['cacheGenerationFloor'],tenant))
        connection.execute('INSERT INTO control_approval_clocks(tenant_id,observed_at) VALUES(%s,%s) ON CONFLICT(tenant_id) DO UPDATE SET observed_at=greatest(control_approval_clocks.observed_at,excluded.observed_at)',(tenant,inventory['approvalClockFloor']+1))
        connection.execute("UPDATE control_tenants SET status='SUSPENDED',updated_at=greatest(updated_at,%s) WHERE tenant_id=%s AND status IN ('ACTIVE','SUSPENDED')",(now,tenant))
        if connection.execute('SELECT status FROM control_tenants WHERE tenant_id=%s',(tenant,)).fetchone()[0]!='SUSPENDED':raise ValueError('Only initialized tenants can be recovered')
    if {row[0] for row in connection.execute('SELECT tenant_id FROM control_tenants')}!=selected:raise ValueError('Unexpected tenant survived selection')
