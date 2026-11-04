# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Durable lease and evidence invariants on the shipped database migrations."""
import os
import unittest
from test_current_schema_specs import (
    FLYWAY, QUICKSTART, TENANT, PostgresRun, SqliteRun, invocation, versions,
)


class ExecutionLeaseStorageTest(unittest.TestCase):
    def exercise(self, db):
        self.assertTrue(db.run(invocation('lease-attempt', 'EXECUTING')))
        values = f"'{TENANT}','lease-attempt','fence-1','host-1',30000"
        self.assertTrue(db.run('INSERT INTO control_execution_leases VALUES(' + values + ')'))
        self.assertFalse(db.run('INSERT INTO control_execution_leases VALUES(' + values + ')'))
        self.assertFalse(db.run(f"INSERT INTO control_execution_leases VALUES('{TENANT}','missing','f','host',30000)"))
        self.assertFalse(db.run("UPDATE control_execution_leases SET expires_at=-1"))
        self.assertTrue(db.run("UPDATE control_execution_leases SET expires_at=40000"))
        evidence = f"'{TENANT}','lease-attempt','fence-1','{'a'*64}','{{}}',1000"
        self.assertTrue(db.run('INSERT INTO control_execution_evidence(tenant_id,invocation_id,fencing_token,report_digest,document,recorded_at) VALUES(' + evidence + ')'))
        self.assertFalse(db.run('INSERT INTO control_execution_evidence(tenant_id,invocation_id,fencing_token,report_digest,document,recorded_at) VALUES(' + evidence + ')'))

    def test_sqlite_lease_identity_and_evidence_are_immutable(self):
        db = SqliteRun(versions(QUICKSTART, 'V*.sql'))
        self.exercise(db)
        self.assertFalse(db.run("UPDATE control_execution_leases SET fencing_token='stale'"))
        self.assertFalse(db.run("UPDATE control_execution_leases SET device_id='other-host'"))
        self.assertFalse(db.run("UPDATE control_execution_evidence SET document='changed'"))
        self.assertFalse(db.run("DELETE FROM control_execution_evidence"))

    @unittest.skipUnless(os.environ.get('TOOLGATE_D2_POSTGRES'), 'PostgreSQL server required')
    def test_postgres_constraints(self):
        self.exercise(PostgresRun(os.environ['TOOLGATE_D2_POSTGRES'], versions(FLYWAY, 'V*__*.sql')))


if __name__ == '__main__':
    unittest.main()
