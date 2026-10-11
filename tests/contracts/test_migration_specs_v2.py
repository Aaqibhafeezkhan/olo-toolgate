# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Design gate D2: the migration specifications apply cleanly and enforce their invariants.

The specification files under docs/control-plane/migrations-v2/ ship unchanged in the
milestone that implements them. These checks apply them on top of the current
Quickstart tree (SQLite, always) and the current Flyway tree (PostgreSQL, when
TOOLGATE_D2_POSTGRES names a server as host:port), then run the same scenarios on
both, so the two dialects can't drift apart.
"""
import os
import re
import sqlite3
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT/'docs/control-plane/migrations-v2'
FLYWAY = ROOT/'apps/control-plane/src/main/resources/db/migration'
QUICKSTART = ROOT/'apps/control-plane/src/main/resources/db/quickstart'
D = 'sha256:' + 'a'*64
H = 'b'*64
T = '0123456789abcdefABCDEF_-'

INVOCATION = ("INSERT INTO control_enterprise_invocations(tenant_id,invocation_id,request_digest,state,"
              "authorization_epoch,created_at,expires_at,revision,document{cols}) VALUES('t1','{id}','" + 'c'*64 +
              "','{state}',1,1000,90000,1,'{{}}'{vals})")


def invocation(id, state='QUEUED', **v2):
    cols = ''.join(',' + k for k in v2)
    vals = ''.join(',' + (str(x).upper() if isinstance(x, bool) else str(x) if isinstance(x, int) else "'" + x + "'")
                   for x in v2.values())
    return INVOCATION.format(id=id, state=state, cols=cols, vals=vals)


def task(id, state, task_state, **extra):
    return invocation(id, state, tool_id='acme.orders-lookup', package_digest=D, effect_safety='NON_IDEMPOTENT',
                      requires_execution_ledger=True, task_id=id + T, task_state=task_state, task_owner_digest=H,
                      task_retain_until=99000, task_document='{}', **extra)


def key(identity='agent-1', namespace='AGENT', invocation_id='i2', source='HEADER', created=1000, expires=None):
    return ("INSERT INTO control_business_keys(tenant_id,tool_id,namespace,namespace_identity,business_key_hash,source,"
            "fingerprint,caller_agent_id,invocation_id,created_at,dedupe_expires_at,retain_until) VALUES("
            f"'t1','acme.orders-lookup','{namespace}','{identity}','{H}','{source}','{D}','agent-1','{invocation_id}',"
            f"{created},{'NULL' if expires is None else expires},2000000)")


KEY = "UPDATE control_business_keys SET {} WHERE business_key_hash='" + H + "' AND namespace='{}'"
RESULT = ("INSERT INTO control_invocation_results(tenant_id,invocation_id,fencing_nonce,result_digest,byte_length,payload,"
          "created_at,retain_until) VALUES('t1','i2','n1','" + D + "',2,{},1000,5000)")

# (statement, should it succeed?) in order; each failure must leave the database unchanged.
SCENARIOS = [
    (invocation('i1'), True),                                              # v1 rows are untouched
    (task('i2', 'QUEUED', 'QUEUED'), True),
    ("UPDATE control_enterprise_invocations SET state='EXECUTING' WHERE invocation_id='i2'", False),
    ("UPDATE control_enterprise_invocations SET state='EXECUTING',task_state='RUNNING',lease_holder='host-1',"
     "lease_expires_at=5000 WHERE invocation_id='i2'", True),
    ("UPDATE control_enterprise_invocations SET lease_expires_at=NULL WHERE invocation_id='i2'", False),
    ("UPDATE control_enterprise_invocations SET task_state='CANCEL_REQUESTED' WHERE invocation_id='i2'", False),
    ("UPDATE control_enterprise_invocations SET task_state='CANCEL_REQUESTED',cancel_requested_at=4000 "
     "WHERE invocation_id='i2'", True),
    ("UPDATE control_enterprise_invocations SET requires_execution_ledger=FALSE WHERE invocation_id='i2'", False),
    ("UPDATE control_enterprise_invocations SET not_started=TRUE WHERE invocation_id='i2'", False),
    ("UPDATE control_enterprise_invocations SET task_document=NULL WHERE invocation_id='i2'", False),
    (task('i3', 'QUEUED', 'QUEUED').replace("'i3" + T + "'", "'i2" + T + "'"), False),   # task id is unique
    (task('i4', 'PENDING_APPROVAL', 'QUEUED'), False),
    (task('i4', 'CANCELLED', 'REJECTED'), True),
    (task('i5', 'FAILED', 'FAILED', not_started=True), True),
    (key(), True),
    (key(), False),                                                        # one reservation per key
    (key(identity='agent-1', namespace='TENANT'), False),                  # TENANT has no identity
    (key(identity='', namespace='TENANT'), True),
    (key(identity='', namespace='AGENT'), False),
    (KEY.format("invocation_id='i9'", 'AGENT'), False),                     # bound while in flight
    (KEY.format("fingerprint='sha256:" + 'f'*64 + "'", 'AGENT'), False),
    (KEY.format("outcome='FAILED'", 'AGENT'), True),
    (KEY.format("invocation_id='i9',created_at=3000,outcome=NULL", 'AGENT'), True),   # never executed: rebind
    (KEY.format("outcome='OUTCOME_UNKNOWN'", 'AGENT'), True),
    (KEY.format("outcome='COMPLETED'", 'AGENT'), True),                     # late result settles it
    (KEY.format("outcome='FAILED'", 'AGENT'), False),                       # final
    (KEY.format("tombstoned_at=2100000", 'AGENT'), True),
    (KEY.format("retain_until=3000000", 'AGENT'), False),                   # tombstones are final
    ("DELETE FROM control_business_keys", False),
    (key(identity='agent-2', source='DEDUPE_WINDOW', expires=6000), True),
    (KEY.format("invocation_id='i8',created_at=5000,dedupe_expires_at=11000", 'AGENT') +
     " AND namespace_identity='agent-2'", False),                         # window still open
    (KEY.format("invocation_id='i8',created_at=6000,dedupe_expires_at=11000", 'AGENT') +
     " AND namespace_identity='agent-2'", True),
    (RESULT.format("X'7b7d'"), True),
    (RESULT.format("X'7b7d'").replace("'i2'", "'missing'"), False),        # result needs its invocation
    ("UPDATE control_invocation_results SET payload=X'5b5d'", False),
    ("UPDATE control_invocation_results SET payload=NULL,purged_at=6000", True),
    ("UPDATE control_invocation_results SET purged_at=7000", False),
    ("DELETE FROM control_invocation_results", False),
]

KILL = ("INSERT INTO control_kill_events(tenant_id,event_id,issuer,issuer_counter,event_kind,scope_kind,scope_value,"
        "mode,authorization_epoch,issued_at,document) VALUES('t1','{issuer}:{n}','{issuer}',{n},'{kind}',{scope},"
        "{value},{mode},{n},1000,'{{}}')")


def kill(n, issuer='control', kind='KILL', scope="'PACKAGE'", value="'acme'", mode="'TERMINATE'"):
    return KILL.format(n=n, issuer=issuer, kind=kind, scope=scope, value=value, mode=mode)


def recovery(n, issuer='control'):
    return kill(n, issuer, 'RECOVERY', 'NULL', 'NULL', 'NULL')


SUPERSEDE = "INSERT INTO control_kill_supersessions VALUES('t1','{}','{}')"
ACK = ("INSERT INTO control_kill_acks VALUES('t1','TOOL_HOST','host-1',{0},{0},'PUSH') "
       "ON CONFLICT(tenant_id,component_kind,component_id) DO UPDATE SET acked_epoch=excluded.acked_epoch,"
       "acked_at=excluded.acked_at,delivery_path=excluded.delivery_path")

KILL_SCENARIOS = [
    (kill(1), True),
    (kill(1, 'control-breakglass', mode="'STOP_NEW'"), True),
    (kill(1), False),                                                      # event ids are never reused
    (kill(2, scope="'TENANT'"), False),                                    # TENANT has no value
    (kill(2, scope="'TENANT'", value='NULL'), True),
    (kill(3, mode='NULL'), False),
    (recovery(2, 'control-breakglass'), False),                            # break-glass can't recover
    (recovery(4), True),
    (SUPERSEDE.format('control:4', 'control:1'), False),                   # a recovery isn't a kill
    (SUPERSEDE.format('control:1', 'control-breakglass:1'), False),        # a kill can't supersede
    (SUPERSEDE.format('control:1', 'control:4'), True),
    ("INSERT INTO control_kill_archive VALUES('t1','control:2',5000)", False),   # still effective
    ("INSERT INTO control_kill_archive VALUES('t1','control:1',5000)", True),
    ("UPDATE control_kill_events SET mode='STOP_NEW' WHERE event_id='control:2'", False),
    ("DELETE FROM control_kill_events WHERE event_id='control:2'", False),
    ("DELETE FROM control_kill_supersessions", False),
    (ACK.format(5), True),
    (ACK.format(4), False),
    (ACK.format(6), True),
]


def statements(path):
    return [s for s in re.split(r'(?m)^-- statement\s*$', path.read_text(encoding='utf-8')) if s.strip()]


def versions(directory, pattern):
    return sorted(directory.glob(pattern), key=lambda p: int(re.match(r'V(\d+)', p.name).group(1)))


class SqliteRun:
    def __init__(self, files):
        self.db = sqlite3.connect(':memory:', isolation_level=None)
        self.db.execute('PRAGMA foreign_keys=ON')
        for path in files:
            for statement in statements(path):
                self.db.execute(statement)

    def run(self, statement):
        try:
            self.db.execute(statement)
            return True
        except sqlite3.DatabaseError:
            return False


class PostgresRun:
    def __init__(self, server, files):
        host, port = server.rsplit(':', 1)
        self.base = ['psql', '-h', host, '-p', port, '-U', os.environ.get('TOOLGATE_D2_POSTGRES_USER', 'postgres'),
                     '-q', '-v', 'ON_ERROR_STOP=1']
        self.psql(['-c', 'DROP DATABASE IF EXISTS toolgate_d2', '-c', 'CREATE DATABASE toolgate_d2'])
        self.base += ['-d', 'toolgate_d2']
        self.psql(['-c', "DO $$BEGIN CREATE ROLE toolgate_control_runtime; "
                         "EXCEPTION WHEN duplicate_object THEN NULL; END$$"])
        for path in files:
            self.psql(['-1', '-f', str(path)])

    def psql(self, args):
        return subprocess.run(self.base + args, capture_output=True, text=True, check=True)

    def run(self, statement):
        try:
            self.psql(['-c', re.sub(r"X'([0-9a-f]*)'", r"decode('\1','hex')", statement)])
            return True
        except subprocess.CalledProcessError:
            return False


def scenarios(test, database, cases):
    for number, (statement, expected) in enumerate(cases, 1):
        with test.subTest(step=number, statement=statement[:90]):
            test.assertEqual(database.run(statement), expected)


class MigrationSpecificationTest(unittest.TestCase):
    def test_files_are_versioned_after_the_current_trees(self):
        flyway = [int(re.match(r'V(\d+)__', p.name).group(1)) for p in FLYWAY.glob('V*.sql')]
        quickstart = [int(re.match(r'V(\d+)\.sql', p.name).group(1)) for p in QUICKSTART.glob('V*.sql')]
        spec_flyway = [int(re.match(r'V(\d+)__', p.name).group(1)) for p in (SPEC/'flyway').glob('V*.sql')]
        spec_quickstart = [int(re.match(r'V(\d+)\.sql', p.name).group(1)) for p in (SPEC/'quickstart').glob('V*.sql')]
        self.assertEqual(sorted(spec_flyway), list(range(max(flyway) + 1, max(flyway) + 1 + len(spec_flyway))))
        self.assertEqual(sorted(spec_quickstart),
                         list(range(max(quickstart) + 1, max(quickstart) + 1 + len(spec_quickstart))))
        self.assertEqual(len(spec_flyway), len(spec_quickstart))

    def test_quickstart_specifications(self):
        files = versions(QUICKSTART, 'V*.sql') + versions(SPEC/'quickstart', 'V*.sql')
        scenarios(self, SqliteRun(files), SCENARIOS)
        scenarios(self, SqliteRun(files), KILL_SCENARIOS)

    @unittest.skipUnless(os.environ.get('TOOLGATE_D2_POSTGRES'), 'set TOOLGATE_D2_POSTGRES=host:port to run')
    def test_flyway_specifications(self):
        files = versions(FLYWAY, 'V*__*.sql') + versions(SPEC/'flyway', 'V*__*.sql')
        database = PostgresRun(os.environ['TOOLGATE_D2_POSTGRES'], files)
        scenarios(self, database, SCENARIOS)
        scenarios(self, database, KILL_SCENARIOS)


if __name__ == '__main__':
    unittest.main()
