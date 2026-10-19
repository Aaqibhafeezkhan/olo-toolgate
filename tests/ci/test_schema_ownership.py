# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Fail closed when a V1 table is added without an explicit ownership decision."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / 'apps/control-plane/src/main/resources/db'


class SchemaOwnershipTests(unittest.TestCase):
    def test_java_runtime_context_has_one_approved_transaction_boundary(self):
        sources = ROOT / 'apps/control-plane/src/main/java'
        setters = {path.relative_to(sources).as_posix() for path in sources.rglob('*.java')
                   if re.search(r"set_config\s*\(|SET\s+(?:LOCAL\s+)?app\.tenant_id", path.read_text(), re.IGNORECASE)}
        self.assertEqual({'io/ololabs/toolgate/control/adapter/PostgresStore.java'}, setters)

    def test_v1_tables_match_reviewed_inventory_in_both_storage_modes(self):
        manifest = json.loads((DB / 'schema-ownership.json').read_text())
        entries = manifest['tables']
        names = {entry['name'] for entry in entries}
        self.assertEqual(len(names), len(entries), 'Duplicate manifest entries')
        postgres = (DB / 'migration/V1__initial_schema.sql').read_text()
        self.assertEqual(names, set(re.findall(r'CREATE TABLE\s+(\w+)\s*\(', postgres)))
        # Interpret the actual SQLite V1, so omitted NOT NULL and ownership keys
        # cannot be concealed by formatting or a source-text match.
        with sqlite3.connect(':memory:') as local:
            local.executescript((DB / 'quickstart/V1.sql').read_text())
            tables = {row[0] for row in local.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertEqual(names | {'sqlite_sequence'}, tables)
            for entry in entries:
                with self.subTest(table=entry['name']):
                    columns = list(local.execute('PRAGMA table_info("' + entry['name'] + '")'))
                    tenant = next(column for column in columns if column[1] == entry['tenantColumn'])
                    self.assertTrue(tenant[3], 'SQLite TEXT PRIMARY KEY alone allows NULL')
                    self.assertEqual(entry['sqliteTenantType'], tenant[2].upper())
                    self.assertEqual(entry['primaryKey'], [col[1] for col in sorted(columns, key=lambda col: col[5]) if col[5]])
                    foreign_keys = {}
                    for row in local.execute('PRAGMA foreign_key_list("' + entry['name'] + '")'):
                        value = foreign_keys.setdefault(row[0], {'table': row[2], 'columns': [], 'referencedColumns': []})
                        value['columns'].append(row[3]); value['referencedColumns'].append(row[4])
                    self.assertCountEqual(entry['foreignKeys'], list(foreign_keys.values()))
        policy_block = postgres.split('DO $tenant_policies$', 1)[1]
        policies = set(re.findall(r"'(control_[a-z_]+)'", policy_block))
        self.assertEqual({entry['name'] for entry in entries if entry['rowSecurity'] == 'forced'}, policies)
        pending = [entry for entry in entries if entry['rowSecurity'] != 'forced']
        self.assertEqual(['control_tenants'], [entry['name'] for entry in pending])
        self.assertEqual('combined-registry-authority-pending-split', pending[0]['classification'])
        for entry in entries:
            self.assertTrue(entry['runtimePrivileges'])
            self.assertEqual('tenant_id', entry['tenantColumn'])
            for reference in entry['foreignKeys']:
                self.assertIn('tenant_id', reference['columns'])
                self.assertEqual(reference['columns'].index('tenant_id'), reference['referencedColumns'].index('tenant_id'))


if __name__ == '__main__':
    unittest.main()
