# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Current fresh-schema finalization constraints, key integrity and transaction rollback."""
from pathlib import Path
import sqlite3
import unittest

ROOT=Path(__file__).resolve().parents[2]
SQL=ROOT/"apps/control-plane/src/main/resources/db/quickstart"
TENANT="10000000-0000-4000-8000-000000000001"


class OperationFinalizationSchemaTest(unittest.TestCase):
    def database(self):
        db=sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("PRAGMA foreign_keys=ON")
        db.executescript((SQL/"V1.sql").read_text())
        db.execute("INSERT INTO control_tenants(tenant_id,alias,display_name,status,created_at,updated_at) VALUES(?,?,?,'PROVISIONING',0,0)",
                   (TENANT,"fresh","Fresh schema"))
        for n,outcome in enumerate((None,"COMPLETED","FAILED","EXPIRED","CANCELLED","REJECTED","OUTCOME_UNKNOWN")):
            db.execute("""INSERT INTO control_business_keys(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,
                source,fingerprint,caller_agent_id,caller_user_id,invocation_id,created_at,retain_until,outcome,tombstoned_at)
                VALUES(?,?,'AGENT','agent',?,'HEADER',?,'agent','alice',?,1,1000,?,?)""",
                (TENANT,"tool",f"{n:064x}","sha256:"+"a"*64,f"operation-{n}",outcome,1000 if n in (2,3) else None))
        db.commit()
        return db

    def rows(self,db):
        return list(db.execute("SELECT * FROM control_business_keys ORDER BY business_key_hash"))

    def test_current_schema_retains_indexes_and_guards(self):
        db=self.database()
        before=self.rows(db)
        names={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE tbl_name='control_business_keys'")}

        self.assertEqual(before,self.rows(db))
        self.assertTrue(names.issubset({row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE tbl_name='control_business_keys'")}))
        self.assertEqual([],list(db.execute("PRAGMA foreign_key_check")))
        self.assertEqual(0,db.execute("SELECT count(*) FROM sqlite_temp_master WHERE name='control_business_keys_v7_copy'").fetchone()[0])
        with self.assertRaisesRegex(sqlite3.IntegrityError,"tombstoned"):
            db.execute("DELETE FROM control_business_keys")
        with self.assertRaisesRegex(sqlite3.IntegrityError,"immutable"):
            db.execute("UPDATE control_business_keys SET caller_agent_id='another-agent'")

    def test_current_schema_accepts_partial(self):
        db=self.database()
        db.execute("""INSERT INTO control_business_keys(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,
            source,fingerprint,caller_agent_id,invocation_id,created_at,retain_until,outcome)
            VALUES(?,'tool','AGENT','agent',?,'HEADER',?,'agent','partial-operation',1,1000,'PARTIAL')""",
            (TENANT,"f"*64,"sha256:"+"a"*64))
        self.assertEqual("PARTIAL",db.execute("SELECT outcome FROM control_business_keys WHERE invocation_id='partial-operation'").fetchone()[0])
        # This is schema acceptance only; the Java runtime-role suite verifies derived proof and mapping.

    def test_failed_transaction_rolls_back_current_key_writes(self):
        db=self.database()
        before=self.rows(db)
        schema=list(db.execute("SELECT name,sql FROM sqlite_master WHERE tbl_name='control_business_keys' ORDER BY name"))
        with self.assertRaises(sqlite3.OperationalError):
            db.executescript("BEGIN IMMEDIATE;"+self.insert_extra()+";SELECT * FROM deliberately_missing_dependency;COMMIT;")
        db.rollback()
        self.assertEqual(before,self.rows(db))
        self.assertEqual(schema,list(db.execute("SELECT name,sql FROM sqlite_master WHERE tbl_name='control_business_keys' ORDER BY name")))

    def insert_extra(self):
        return ("INSERT INTO control_business_keys(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,"
                "source,fingerprint,caller_agent_id,invocation_id,created_at,retain_until,outcome) VALUES('"
                +TENANT+"','tool','AGENT','agent','"+"e"*64+"','HEADER','sha256:"+"a"*64
                +"','agent','current-extra',1,1000,'PARTIAL')")


if __name__=="__main__":
    unittest.main()
