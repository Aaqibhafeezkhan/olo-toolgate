# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""ADR 061 per-tenant Control audit chain writer shared by Quickstart and offline restore.

The encoding must stay byte-identical to io.ololabs.toolgate.control.application.AuditChain.
"""
import datetime
import hashlib
import struct
import time

DOMAIN = b'toolgate/control-audit-chain/v1\n'
GENESIS = '0' * 64


def _text(value):
    if not isinstance(value, str): raise ValueError('Audit chain text field required')
    raw = value.encode('utf-8')
    return struct.pack('>I', len(raw)) + raw


def digest(tenant, index, previous, actor, operation, target, revision, request_id, request_digest, evidence, occurred_ms):
    if not isinstance(index, int) or index < 1 or len(previous) != 64: raise ValueError('Invalid audit chain link')
    body = [DOMAIN, _text(tenant), struct.pack('>q', index), _text(previous), _text(actor), _text(operation), _text(target),
            struct.pack('>q', revision), _text(request_id), _text(request_digest)]
    body.append(b'\x00' if evidence is None else b'\x01' + _text(evidence))
    body.append(struct.pack('>q', occurred_ms))
    return hashlib.sha256(b''.join(body)).hexdigest()


def utc(occurred_ms):
    return datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc) + datetime.timedelta(milliseconds=occurred_ms)


def sqlite_time(occurred_ms):
    moment = utc(occurred_ms)
    return moment.strftime('%Y-%m-%dT%H:%M:%S.') + '%03dZ' % (occurred_ms % 1000)


def is_postgres(db):
    target = getattr(db, 'connection', db)
    return type(target).__module__.split('.')[0] == 'psycopg'


def append(db, tenant, actor, operation, target, revision, request_id, request_digest, evidence=None, postgres=None, now_ms=None):
    """Append one chained row inside the caller's write transaction.

    `db.execute` takes '?' placeholders (Quickstart's Database adapts them for PostgreSQL).
    PostgreSQL callers take the same tenant audit-chain advisory lock as Control replicas;
    SQLite callers must already hold the IMMEDIATE write reservation (or be the sole writer).
    """
    mark = '?'
    postgres = is_postgres(db) if postgres is None else postgres
    if postgres:
        db.execute("SELECT pg_advisory_xact_lock(hashtextextended(?::text||'/audit-chain',0))", (tenant,))
    head = db.execute(f'SELECT chain_index,row_digest FROM control_audit WHERE tenant_id={mark} ORDER BY chain_index DESC LIMIT 1', (tenant,)).fetchone()
    index, previous = (int(head[0]) + 1, str(head[1])) if head else (1, GENESIS)
    occurred_ms = int(time.time() * 1000) if now_ms is None else int(now_ms)
    row = digest(tenant, index, previous, actor, operation, target, revision, request_id, request_digest, evidence, occurred_ms)
    occurred = utc(occurred_ms) if postgres else sqlite_time(occurred_ms)
    db.execute('INSERT INTO control_audit(tenant_id,chain_index,previous_digest,actor_id,operation,target,revision,request_id,request_digest,evidence,occurred_at,row_digest) '
               f'VALUES({",".join([mark] * 12)})',
               (tenant, index, previous, actor, operation, target, revision, request_id, request_digest, evidence, occurred, row))
    return index, row
