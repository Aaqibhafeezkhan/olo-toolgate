# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Exercise the Quickstart database adapter without starting the supervisor."""
import ast
from pathlib import Path
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]


class QuickstartDatabaseLockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = ROOT / 'apps/quickstart/supervisor.py'
        tree = ast.parse(source.read_text(encoding='utf-8'), filename=str(source))
        adapter = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Database')
        cls.namespace = {'TENANT': 'tenant-one'}
        exec(compile(ast.Module(body=[adapter], type_ignores=[]), str(source), 'exec'), cls.namespace)

    def adapter(self, postgres):
        database = self.namespace['Database'].__new__(self.namespace['Database'])
        database.pg = postgres
        database.connection = Mock()
        return database

    def test_postgres_begin_locks_current_tenant_without_a_data_table(self):
        database = self.adapter(True)
        database.execute('BEGIN IMMEDIATE')
        database.connection.execute.assert_called_once_with(
            'SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))',
            ('toolgate:quickstart-bootstrap:tenant-one',))
        self.namespace['TENANT'] = 'tenant-two'
        try:
            database.execute('BEGIN IMMEDIATE')
            self.assertEqual(('toolgate:quickstart-bootstrap:tenant-two',), database.connection.execute.call_args.args[1])
        finally:
            self.namespace['TENANT'] = 'tenant-one'

    def test_sqlite_keeps_real_begin_immediate(self):
        database = self.adapter(False)
        database.execute('BEGIN IMMEDIATE')
        database.connection.execute.assert_called_once_with('BEGIN IMMEDIATE', ())

    def test_postgres_context_is_transaction_local_before_helper_sql(self):
        database = self.adapter(True)
        self.assertIs(database, database.__enter__())
        database.connection.execute.assert_called_once_with(
            "SELECT set_config('app.tenant_id', %s, true)", ('tenant-one',))
        local = self.adapter(False)
        self.assertIs(local, local.__enter__())
        local.connection.execute.assert_not_called()

    def test_context_setup_failure_closes_connection_before_any_helper_work(self):
        database = self.adapter(True)
        database.connection.execute.side_effect = RuntimeError('context unavailable')
        with self.assertRaises(RuntimeError):
            database.__enter__()
        database.connection.close.assert_called_once_with()

    def test_parameterized_postgres_queries_preserve_bound_values(self):
        database = self.adapter(True)
        database.execute('SELECT document FROM control_records WHERE tenant_id=?', ('tenant-one',))
        database.connection.execute.assert_called_once_with(
            'SELECT document FROM control_records WHERE tenant_id=%s', ('tenant-one',))

    def test_context_exit_commits_or_rolls_back_and_closes(self):
        for failure in (None, RuntimeError):
            with self.subTest(failure=failure):
                database = self.adapter(True)
                database.__exit__(failure, None, None)
                expected = ['commit', 'close'] if failure is None else ['rollback', 'close']
                self.assertEqual(expected, [call[0] for call in database.connection.mock_calls])


if __name__ == '__main__':
    unittest.main()
