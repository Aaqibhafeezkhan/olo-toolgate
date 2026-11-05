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

    def test_current_namespace_identity_is_text_and_only_tenant_uses_empty_identity(self):
        for namespace, identity, accepted in [
            ('TENANT', '', True), ('AGENT', 'agent-a', True),
            ('DELEGATED_USER', 'user-a', True), ('AGENT', 42, True),
            ('TENANT', 'agent-a', False), ('AGENT', '', False),
            ('DELEGATED_USER', '', False), ('TENANT', b'', False),
            ('AGENT', b'agent-a', False), ('DELEGATED_USER', b'user-a', False),
            ('TENANT', None, False), ('AGENT', None, False),
            ('TENANT', '\0agent', False), ('AGENT', '\0agent', True),
            ('AGENT', 'é', True),
        ]:
            with self.subTest(namespace=namespace, identity=identity):
                db=SqliteRun(versions(QUICKSTART,'V*.sql'))
                self.assertTrue(db.run(invocation('op', state='FAILED')))
                db.db.execute("INSERT INTO control_operations VALUES(?,?,?,'FAILED',0,1,1000)",
                              (TENANT,'op','c'*64))
                insert="""INSERT INTO control_business_keys
                    (tenant_id,tool_id,namespace,namespace_identity,business_key_hash,source,
                     fingerprint,caller_agent_id,invocation_id,created_at,dedupe_expires_at,
                     retain_until,outcome)
                    VALUES(?,?,?,?,?,'DEDUPE_WINDOW',?,'agent-a','op',1000,2000,5000,'FAILED')"""
                values=(TENANT,'acme.orders-lookup',namespace,identity,'a'*64,'sha256:'+'b'*64)
                if not accepted:
                    with self.assertRaises(sqlite3.IntegrityError):
                        db.db.execute(insert,values)
                    self.assertEqual(0,db.db.execute('SELECT count(*) FROM control_business_keys').fetchone()[0])
                    db.db.close()
                    continue
                db.db.execute(insert,values)
                self.assertEqual('text',db.db.execute('SELECT typeof(namespace_identity) FROM control_business_keys').fetchone()[0])
                db.db.execute("""INSERT INTO control_business_key_history
                    SELECT tenant_id,tool_id,namespace,namespace_identity,business_key_hash,
                           invocation_id,fingerprint,caller_agent_id,caller_user_id,created_at,
                           dedupe_expires_at,retain_until,outcome,tombstoned_at,2000
                    FROM control_business_keys""")
                self.assertEqual(1,db.db.execute('SELECT count(*) FROM control_business_key_history').fetchone()[0])
                with self.assertRaises(sqlite3.IntegrityError):
                    db.db.execute("UPDATE control_business_key_history SET namespace_identity='other'")
                db.db.close()

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
