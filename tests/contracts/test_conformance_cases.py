# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Design gate D3: conformance cases are authored for every suite in plan.md §21.

These checks keep the case files well formed and tie them to the designs they test:
every Tool Host threat names an existing case, every invocation transition and
persistence row has a case, and every referenced fixture exists. Runners for the
cases arrive with the milestones listed in suites.json.
"""
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
CONFORMANCE = ROOT/'tool-sdk/conformance'
THREAT_MODEL = ROOT/'docs/security/threat-models/tool-host.md'
COMPOSITION = ROOT/'tool-sdk/spec/composition.md'
PACKAGES = CONFORMANCE/'fixtures/packages'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


class ConformanceCaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = load(CONFORMANCE/'case.schema.json')
        cls.suites = load(CONFORMANCE/'suites.json')['suites']
        cls.files = {p.relative_to(CONFORMANCE).as_posix(): load(p) for p in sorted(CONFORMANCE.glob('*/*.json')) if p.parent.name != 'fixtures'}
        cls.cases = [(name, case) for name, doc in cls.files.items() for case in doc['cases']]
        cls.ids = {case['id'] for _, case in cls.cases}

    def test_case_schema_is_valid(self):
        Draft202012Validator.check_schema(self.schema)

    def test_every_file_validates_and_sits_in_its_suite_folder(self):
        validator = Draft202012Validator(self.schema)
        self.assertGreater(len(self.files), 0)
        for name, doc in self.files.items():
            with self.subTest(file=name):
                errors = [f'{"/".join(map(str, e.absolute_path))}: {e.message}' for e in validator.iter_errors(doc)]
                self.assertEqual(errors, [])
                self.assertEqual(name, f'{doc["suite"]}/{doc["area"]}.json')

    def test_case_ids_are_unique(self):
        seen = {}
        for name, case in self.cases:
            self.assertNotIn(case['id'], seen, f'{case["id"]} in {name} and {seen.get(case["id"])}')
            seen[case['id']] = name

    def test_suites_match_plan_and_each_has_cases(self):
        schema_ids = self.schema['$defs']['SuiteId']['enum']
        self.assertEqual([s['id'] for s in self.suites], schema_ids)
        used = {doc['suite'] for doc in self.files.values()}
        self.assertEqual(used, set(schema_ids))
        for suite in self.suites:
            self.assertTrue({'name', 'contents', 'runsWhen', 'blocks', 'runner', 'firstPassing'} <= suite.keys(), suite['id'])

    def test_status_rules(self):
        for name, case in self.cases:
            with self.subTest(case=case['id']):
                if case['status'] == 'passing':
                    self.assertIn('evidence', case, 'a passing case names its runner evidence')
                else:
                    self.assertNotIn('evidence', case)
                if case.get('perTarget'):
                    self.assertGreater(len(case['targets']), 1)

    def test_every_referenced_fixture_exists(self):
        for name, case in self.cases:
            for fixture in case.get('fixtures', []):
                with self.subTest(case=case['id'], fixture=fixture):
                    self.assertTrue((ROOT/fixture).exists())

    def test_every_threat_names_existing_cases(self):
        rows = [line for line in THREAT_MODEL.read_text(encoding='utf-8').splitlines() if re.match(r'^\| T-[A-Z]+-\d+ \|', line)]
        self.assertGreaterEqual(len(rows), 30)
        for row in rows:
            threat = row.split('|')[1].strip()
            named = re.findall(r'`([a-z][a-z0-9-]*/[a-z0-9/-]+)`', row.rstrip(' |').rsplit('|', 1)[1])
            with self.subTest(threat=threat):
                self.assertTrue(named, 'the D3 case column names at least one case')
                self.assertEqual([n for n in named if n not in self.ids], [])

    def test_every_invocation_transition_has_a_case(self):
        covered = {int(m.group(1)) for _, case in self.cases for r in case['requirements']
                   for m in [re.search(r'invocation-state-v2\.md §3 T(\d+)$', r)] if m}
        self.assertEqual(covered, set(range(1, 15)))

    def test_every_persistence_row_has_a_failure_case(self):
        covered = {int(m.group(1)) for _, case in self.cases for r in case['requirements']
                   for m in [re.search(r'plan\.md §12\.7 row (\d+)$', r)] if m}
        self.assertEqual(covered, set(range(1, 12)))

    def test_every_composition_error_has_a_case(self):
        codes = set(re.findall(r'`(COMPOSITION_[A-Z_]+)`', COMPOSITION.read_text(encoding='utf-8')))
        text = json.dumps(self.files['contract-descriptor/composition.json'])
        cases = load(ROOT/'tool-sdk/spec/composition/cases.json')['cases']
        from_vectors = {c['expect'].get('error') for c in cases}
        self.assertEqual({code for code in codes if code not in text and code not in from_vectors}, set())

    def test_every_package_fixture_has_a_case(self):
        expected = load(PACKAGES/'expected.json')['fixtures']
        referenced = {Path(f).stem for _, case in self.cases for f in case.get('fixtures', []) if f.endswith('.tgpkg')}
        self.assertEqual(set(expected), referenced)

    def test_package_fixtures_match_their_generator(self):
        result = subprocess.run([sys.executable, str(PACKAGES/'generate.py'), '--check'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
