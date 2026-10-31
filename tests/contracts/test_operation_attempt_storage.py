# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shipped V3 journal backfill and immutable retry authority, not a schema-text test."""
import json
import sqlite3
import unittest
from test_migration_specs_v2 import QUICKSTART, TENANT, SqliteRun, invocation, statements, versions


class OperationAttemptStorageTest(unittest.TestCase):
    def test_upgrade_retains_every_attempt_and_conservative_legacy_outcomes(self):
        db = SqliteRun(versions(QUICKSTART, 'V*.sql')[:2])
        self.assertTrue(db.run(invocation('historical', 'SUCCEEDED')))
        document = json.dumps({'id':'historical','reservedNonce':'new-fence','state':'SUCCEEDED','revision':1})
        db.db.execute('UPDATE control_enterprise_invocations SET document=? WHERE invocation_id=?', (document,'historical'))
        for fence,issued,expires,consumed in [('old-fence',1000,2000,1500),('new-fence',3000,4000,3500)]:
            self.assertTrue(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','{fence}','historical','{'a'*64}',1,{issued},{expires},{consumed})"))
        for sql in statements(QUICKSTART/'V3.sql'):
            db.db.execute(sql)
        operation = db.db.execute('SELECT state,legacy FROM control_operations').fetchone()
        self.assertEqual(('OUTCOME_UNKNOWN',1),operation)
        self.assertEqual([('new-fence','SUCCEEDED'),('old-fence','OUTCOME_UNKNOWN')],db.db.execute('SELECT attempt_id,state FROM control_attempt_snapshots ORDER BY attempt_id').fetchall())
        self.assertFalse(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','retry','historical','{'a'*64}',1,5000,6000,NULL,'EXECUTION')"))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET consumed_at=NULL"))
        self.assertFalse(db.run("UPDATE control_operation_attempts SET operation_id='other'"))
        self.assertFalse(db.run("UPDATE control_attempt_snapshots SET document='{}'"))
        self.assertFalse(db.run('DELETE FROM control_attempt_snapshots'))
        self.assertFalse(db.run('DELETE FROM control_operation_attempts'))
        self.assertFalse(db.run('DELETE FROM control_operations'))

    def test_live_or_unbound_predecessors_cannot_create_retry_authority(self):
        db = SqliteRun(versions(QUICKSTART,'V*.sql'))
        self.assertTrue(db.run(invocation('op')))
        self.assertTrue(db.run(f"INSERT INTO control_operations VALUES('{TENANT}','op','{'c'*64}','RUNNING',0,1,1000)"))
        self.assertTrue(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','first','op','{'a'*64}',1,1000,2000,NULL,'EXECUTION')"))
        # An absent binding is never evidence that nothing started, even after expiry.
        self.assertFalse(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','second','op','{'a'*64}',1,3000,4000,NULL,'EXECUTION')"))
        self.assertTrue(db.run(f"INSERT INTO control_operation_attempts VALUES('{TENANT}','first','op','{'a'*64}',1,1000,2000)"))
        self.assertFalse(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','live','op','{'a'*64}',1,1500,2500,NULL,'EXECUTION')"))
        self.assertTrue(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','second','op','{'a'*64}',1,3000,4000,NULL,'EXECUTION')"))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET expires_at=9999"))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET purpose='SERVICE' WHERE nonce='second'"))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET consumed_at=4000 WHERE nonce='second'"))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET consumed_at=3500 WHERE nonce='second'"))
        self.assertTrue(db.run(f"INSERT INTO control_operation_attempts VALUES('{TENANT}','second','op','{'a'*64}',1,3000,4000)"))
        self.assertTrue(db.run("UPDATE control_enterprise_nonces SET consumed_at=3500 WHERE nonce='second'"))
        self.assertFalse(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','third','op','{'a'*64}',1,5000,6000,NULL,'EXECUTION')"))
        snapshot = json.dumps({'id':'op','reservedNonce':'second','state':'OUTCOME_UNKNOWN','revision':1})
        db.db.execute('INSERT INTO control_attempt_snapshots VALUES(?,?,?,?,?,?)',(TENANT,'op','second',1,'OUTCOME_UNKNOWN',snapshot))
        self.assertFalse(db.run(f"INSERT INTO control_attempt_snapshots VALUES('{TENANT}','op','second',2,'QUEUED','{{}}')"))
        reopen = json.dumps({'id':'op','reservedNonce':'second','state':'EXECUTING','revision':2})
        with self.assertRaises(sqlite3.IntegrityError):
            db.db.execute('INSERT INTO control_attempt_snapshots VALUES(?,?,?,?,?,?)',(TENANT,'op','second',2,'EXECUTING',reopen))
        self.assertFalse(db.run("UPDATE control_enterprise_nonces SET consumed_at=NULL WHERE nonce='second'"))


if __name__ == '__main__':
    unittest.main()
