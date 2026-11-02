# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Effect generation invariants on the shipped SQLite schema; PostgreSQL runs through the Java runtime role."""
import unittest
from test_migration_specs_v2 import QUICKSTART, TENANT, SqliteRun, invocation, versions


class EffectGenerationStorageTest(unittest.TestCase):
    def fixture(self):
        db=SqliteRun(versions(QUICKSTART,'V*.sql'))
        self.assertTrue(db.run("UPDATE control_tenant_authority SET authorization_epoch=1"))
        self.assertTrue(db.run(invocation('effect-op','EXECUTING').replace("'{}'", "'{\"reservedNonce\":\"attempt\"}'")))
        self.assertTrue(db.run(f"INSERT INTO control_operations VALUES('{TENANT}','effect-op','{'c'*64}','RUNNING',0,1,1000)"))
        self.assertTrue(db.run(f"INSERT INTO control_enterprise_nonces VALUES('{TENANT}','attempt','effect-op','{'a'*64}',1,1000,5000,NULL,'EXECUTION')"))
        self.assertTrue(db.run(f"INSERT INTO control_operation_attempts VALUES('{TENANT}','attempt','effect-op','{'a'*64}',1,1000,5000)"))
        self.assertTrue(db.run("UPDATE control_enterprise_nonces SET consumed_at=1000 WHERE nonce='attempt'"))
        self.assertTrue(db.run(f"INSERT INTO control_effects VALUES('{TENANT}','effect','effect-op','route','sha256:{'a'*64}',1)"))
        self.assertTrue(db.run(f"INSERT INTO control_effect_generations VALUES('{TENANT}','effect',1,'effect-op','attempt','auth-1','device','broker','handler','AUTHORIZED',1000,5000,60000,1,NULL,NULL)"))
        return db

    def test_only_release_can_create_a_new_generation_and_old_nonce_is_void(self):
        db=self.fixture()
        self.assertFalse(db.run("UPDATE control_effects SET current_generation=2"))
        self.assertFalse(db.run("UPDATE control_effects SET request_digest='other'"))
        self.assertTrue(db.run("UPDATE control_effect_generations SET state='RELEASED',revision=2"))
        self.assertTrue(db.run("UPDATE control_effects SET current_generation=2"))
        self.assertTrue(db.run(f"INSERT INTO control_effect_generations VALUES('{TENANT}','effect',2,'effect-op','attempt','auth-2','device','broker','handler','AUTHORIZED',2000,6000,70000,1,NULL,NULL)"))
        self.assertFalse(db.run("UPDATE control_effect_generations SET state='CONSUMED',consumer='target',claim_token='claim',revision=3 WHERE generation=1"))
        self.assertFalse(db.run('DELETE FROM control_effect_generations'))
        self.assertFalse(db.run('DELETE FROM control_effects'))

    def test_consumption_identity_and_outcome_evidence_are_immutable(self):
        db=self.fixture()
        self.assertTrue(db.run("UPDATE control_effect_generations SET state='CONSUMED',consumer='target',claim_token='claim',revision=2"))
        self.assertFalse(db.run("UPDATE control_effect_generations SET consumer='other',revision=3"))
        self.assertFalse(db.run("UPDATE control_effect_generations SET state='AUTHORIZED',revision=3"))
        self.assertFalse(db.run("UPDATE control_effects SET current_generation=2"))
        self.assertTrue(db.run("UPDATE control_effect_generations SET state='AMBIGUOUS',revision=3"))
        self.assertFalse(db.run("UPDATE control_effect_generations SET state='RELEASED',revision=4"))
        self.assertTrue(db.run("UPDATE control_effect_generations SET state='COMPLETED',revision=4"))
        self.assertFalse(db.run("UPDATE control_effect_generations SET state='FAILED',revision=5"))
        self.assertTrue(db.run("UPDATE control_effect_generations SET state='CONFLICT',revision=5"))
        self.assertTrue(db.run(f"INSERT INTO control_effect_evidence VALUES('{TENANT}','effect',1,'{'d'*64}','TARGET:target','COMPLETED','sha256:{'b'*64}',70000)"))
        self.assertFalse(db.run("UPDATE control_effect_evidence SET outcome='FAILED'"))
        self.assertFalse(db.run('DELETE FROM control_effect_evidence'))


if __name__=='__main__':unittest.main()
