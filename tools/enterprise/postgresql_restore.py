# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Restore a reviewed pg_dump archive to an empty, offline database, then quarantine authority.

Requires psycopg 3 and the PostgreSQL pg_restore executable. Never reconnect runtime roles.
The database owner supplies separate externally pinned reviewers and observed live floors.
"""
import argparse
import hashlib
import os
import re
from pathlib import Path
import subprocess
import time
import psycopg
from psycopg import sql
from restore import canonical, read, validate, quarantine_connection
import tenant_recovery


class Connection:
    """Only the fixed quarantine statements are adapted; no operator SQL is accepted."""
    def __init__(self, connection):self.connection=connection
    def execute(self, statement, values=()):
        statement=statement.replace('?', '%s').replace('max(snapshot_sequence,excluded.snapshot_sequence)', 'greatest(control_recovery_floors.snapshot_sequence,excluded.snapshot_sequence)')
        if statement.startswith('SELECT '):statement=re.sub(r'\bdocument\b','CAST(document AS TEXT)',statement)
        return self.connection.execute(statement,values)


def restore(archive, manifest_path, review_path, trust_path, connection_path, pg_restore='pg_restore', now=None, development=False, executor=subprocess.run, tenants=None):
    live_clock=now is None
    archive=Path(archive);now=int(time.time()*1000) if live_clock else now
    if archive.is_symlink() or not archive.is_file():raise ValueError('Regular PostgreSQL archive required')
    manifest=read(manifest_path)
    fleet=manifest.get('layoutVersion')==3
    if not fleet and (set(manifest)!={'layoutVersion','databaseSha256'} or manifest['layoutVersion']!=2):raise ValueError('Exact PostgreSQL manifest required')
    if tenants is not None and not fleet:raise ValueError('Tenant selection requires a reviewed fleet manifest')
    with archive.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    if digest!=manifest['databaseSha256']:raise ValueError('Reviewed archive integrity failure')
    # Public trust and target credentials must be mounted separately from the archive directory.
    for external in (trust_path,connection_path):
        if Path(external).resolve().is_relative_to(archive.parent.resolve()):raise ValueError('Independent mounted trust and target configuration required')
    review=read(review_path);trust=read(trust_path)
    if fleet:approvals,selected=tenant_recovery.reviewed(manifest,review,trust,now,tenants)
    else:a=validate(review,trust,manifest,now)
    target=read(connection_path)
    if set(target)-{'host','port','dbname','user','password','sslmode','sslrootcert'} or not {'host','dbname','user','password','sslmode'}<=set(target):raise ValueError('Explicit protected target connection required')
    if target['sslmode']!='verify-full' and not (development and target['host'] in ('localhost','127.0.0.1') and target['sslmode']=='disable'):raise ValueError('Verified database TLS required')
    if not isinstance(target['dbname'],str) or not re.fullmatch('[A-Za-z_][A-Za-z0-9_-]{0,62}',target['dbname']):raise ValueError('Literal bounded target database name required')
    if os.name!='nt' and (Path(connection_path).stat().st_mode&0o077):raise ValueError('Private target credentials required')
    with psycopg.connect(**target,autocommit=True) as connection:
        # A separate fresh database is mandatory; never restore over a running tenant.
        owner=connection.execute('SELECT pg_get_userbyid(datdba),current_user FROM pg_database WHERE datname=current_database()').fetchone()
        if owner[0]!=owner[1]:raise ValueError('Isolated target database owner required')
        if connection.execute('SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user').fetchone()[0]:raise ValueError('Restore owner must not bypass tenant RLS')
        if fleet and not connection.execute("SELECT pg_has_role(session_user,'toolgate_control_platform','MEMBER')").fetchone()[0]:raise ValueError('Fleet restore owner requires the platform role')
        if connection.execute("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%'").fetchone()[0]:raise ValueError('Empty restore database required')
        database=sql.Identifier(target['dbname'])
        connection.execute(sql.SQL('REVOKE CONNECT ON DATABASE {} FROM PUBLIC').format(database))
        roles=connection.execute('SELECT rolname FROM pg_roles WHERE rolname<>current_user AND NOT rolsuper').fetchall()
        for (role,) in roles:connection.execute(sql.SQL('REVOKE CONNECT ON DATABASE {} FROM {}').format(database,sql.Identifier(role)))
        if connection.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()').fetchone()[0]:raise ValueError('Restore target must remain offline')
        env=dict(os.environ)
        for key in list(env):
            if key.startswith('PG'):del env[key]
        for key,value in target.items():env['PG'+key.upper() if key!='dbname' else 'PGDATABASE']=str(value)
        # No password/DSN in argv or diagnostics. Failure leaves CONNECT revoked and never starts Core.
        result=executor([pg_restore,'--exit-on-error','--no-owner','--dbname',target['dbname'],str(archive)],env=env,capture_output=True)
        if result.returncode:raise ValueError('Archive restore failed; keep target offline and inspect restricted database diagnostics')
        # Large imports must not carry an expired authorization into quarantine.
        now=int(time.time()*1000) if live_clock else now
        if fleet:approvals,selected=tenant_recovery.reviewed(manifest,review,trust,now,tenants)
        else:a=validate(review,trust,manifest,now)
        with connection.transaction():
            if fleet:
                ownership=read(tenant_recovery.SCHEMA.parent.parent/'schema-ownership.json')
                tenant_recovery.quarantine(connection,manifest,approvals,selected,ownership,Connection,now)
                return dict(tenantIds=sorted(selected),reviewDigest=hashlib.sha256(canonical(approvals)).hexdigest(),runtimeConnectEnabled=False)
            # Quarantine is a separate, offline owner boundary; FORCE RLS still requires scope.
            connection.execute("SELECT set_config('app.tenant_id', %s, true)",(a['tenantId'],))
            tables=('control_tenants','control_tenant_authority','control_tenant_initializations','control_tenant_hosts','control_records','control_endpoints','control_enterprise_invocations','control_enterprise_approvals','control_configuration_changes','control_recovery_floors','control_tool_host_dispatches')
            connection.execute(sql.SQL('LOCK TABLE {} IN ACCESS EXCLUSIVE MODE').format(sql.SQL(',').join(map(sql.Identifier,tables))))
            quarantine_connection(Connection(connection),a,now)
    return dict(tenantId=a['tenantId'],reviewDigest=hashlib.sha256(canonical(a)).hexdigest(),runtimeConnectEnabled=False)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('archive','manifest','review','trust','connection'):p.add_argument('--'+name,required=True)
    p.add_argument('--pg-restore',default='pg_restore');p.add_argument('--development-loopback',action='store_true');p.add_argument('--tenant',action='append',help='Fleet recovery: selected canonical UUID; omission recovers all reviewed tenants');args=p.parse_args()
    restore(args.archive,args.manifest,args.review,args.trust,args.connection,args.pg_restore,development=args.development_loopback,tenants=args.tenant)
    print('PostgreSQL restored into quarantine; runtime CONNECT remains revoked. Independently review recovery and rotate credentials before reconnecting Core.')
if __name__=='__main__':main()
