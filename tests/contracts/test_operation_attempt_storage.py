# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Fresh current operation/attempt storage and immutable retry authority."""
import json
import sqlite3
import unittest
from test_current_schema_specs import QUICKSTART, TENANT, SqliteRun, invocation, statements, versions


class OperationAttemptStorageTest(unittest.TestCase):
    def test_fresh_schema_requires_explicit_current_attempt_records(self):
        self.assertEqual(['V1.sql'], [p.name for p in versions(QUICKSTART, 'V*.sql')])
        db = SqliteRun(versions(QUICKSTART, 'V*.sql'))
        self.assertTrue(db.run(invocation('current')))
        self.assertEqual(0, db.db.execute('SELECT count(*) FROM control_operations').fetchone()[0])
        self.assertEqual(0, db.db.execute('SELECT count(*) FROM control_operation_attempts').fetchone()[0])
        self.assertTrue(db.run(f"INSERT INTO control_operations VALUES('{TENANT}','current','{'c'*64}','RUNNING',0,1,1000)"))
        self.assertTrue(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','fence','current','{'a'*64}',1,1000,2000,NULL,'EXECUTION')"))
        self.assertTrue(db.run(f"INSERT INTO control_operation_attempts VALUES('{TENANT}','fence','current','{'a'*64}',1,1000,2000)"))
        snapshot = json.dumps({'id':'current','reservedNonce':'fence','state':'RESERVED','revision':1})
        db.db.execute('INSERT INTO control_attempt_snapshots VALUES(?,?,?,?,?,?)',(TENANT,'current','fence',1,'RESERVED',snapshot))
        self.assertFalse(db.run("UPDATE control_operation_attempts SET operation_id='other'"))
        self.assertFalse(db.run("UPDATE control_attempt_snapshots SET document='{}'"))
        for table in ('control_attempt_snapshots','control_operation_attempts','control_operations'):
            self.assertFalse(db.run('DELETE FROM '+table))

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
