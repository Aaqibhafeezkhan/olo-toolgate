# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""ADR 061 Quickstart/restore audit chain writer parity with Control's Java encoding."""
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'apps/quickstart'))
import audit_chain  # noqa: E402

TENANT = '11111111-1111-4111-8111-111111111111'


def fresh(path):
    """Initialize the current fresh SQLite schema exactly as SqliteState does: one statement per block."""
    db = sqlite3.connect(path, isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    script = (ROOT/'apps/control-plane/src/main/resources/db/quickstart/V1.sql').read_text(encoding='utf-8').replace('\r\n', '\n')
    for statement in script.split('\n-- statement\n'):
        if statement.strip(): db.execute(statement)
    db.execute("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',1,1)", (TENANT, 'tenant', 'tenant'))
    return db


class AuditChainTest(unittest.TestCase):
    def test_vectors_match_java(self):
        self.assertEqual('0bc0edd363a0ff0de494ae37349282c65e9330a035ad8a9b2690e6c7cfcdd16f',
                         audit_chain.digest(TENANT, 1, audit_chain.GENESIS, 'a'*64, 'CREATE', 'user:admin', 1, 'request-1', 'b'*64, None, 1762560000123))
        self.assertEqual('d9024af06ea7b3dd8900b93e2678761353f8bb760b00dae6ddb963f221ef3999',
                         audit_chain.digest(TENANT, 2, 'c'*64, 'a'*64, 'CREATE', 'user:admin', 2, 'request-2', 'b'*64, '{"k":"é"}', 1762560000123))
        self.assertEqual('2025-11-08T00:00:00.123Z', audit_chain.sqlite_time(1762560000123))

    def test_sqlite_appends_link_and_forks_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            db = fresh(Path(directory)/'control.sqlite')
            try:
                db.execute('BEGIN IMMEDIATE')
                first = audit_chain.append(db, TENANT, 'a'*64, 'PASSWORD_CHANGE_REQUESTED', 'local-identity:admin', 1, 'r1', 'b'*64, now_ms=1000)
                second = audit_chain.append(db, TENANT, 'a'*64, 'PASSWORD_CHANGED', 'local-identity:admin', 2, 'r2', 'b'*64, now_ms=2000)
                db.execute('COMMIT')
                self.assertEqual(1, first[0]); self.assertEqual(2, second[0])
                rows = db.execute('SELECT chain_index,previous_digest,row_digest,occurred_at FROM control_audit ORDER BY chain_index').fetchall()
                self.assertEqual([(1, audit_chain.GENESIS, first[1], '1970-01-01T00:00:01.000Z'), (2, first[1], second[1], '1970-01-01T00:00:02.000Z')], rows)
                # A stale writer cannot fork the chain, and stored rows are immutable.
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute('INSERT INTO control_audit(tenant_id,chain_index,previous_digest,actor_id,operation,target,revision,request_id,request_digest,occurred_at,row_digest) '
                               'VALUES(?,?,?,?,?,?,?,?,?,?,?)', (TENANT, 2, first[1], 'a'*64, 'FORK', 'x', 1, 'r', 'b'*64, audit_chain.sqlite_time(3000), 'd'*64))
                with self.assertRaises(sqlite3.IntegrityError): db.execute("UPDATE control_audit SET target='x'")
                with self.assertRaises(sqlite3.IntegrityError): db.execute('DELETE FROM control_audit')
            finally:
                db.close()


if __name__ == '__main__':
    unittest.main()
