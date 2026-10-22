# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Design gate D1: frozen v2 schemas, fixtures and proposed OpenAPI documents.

Nothing consumes v2 yet. These checks keep the frozen artifacts valid, inside the
structural subset the v2 generator design (docs/contracts/v2-generation.md) accepts,
and separate from the served v1 contract until milestone M1 implements them.
"""
import json
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
V2 = ROOT/'packages/contracts/schemas/v2'
FIXTURES = ROOT/'tests/fixtures/contracts/v2'
PROPOSED = ROOT/'packages/contracts/openapi/proposed'
VECTORS = ROOT/'tool-sdk/spec/descriptor/vectors'
UNSUPPORTED = {'oneOf', 'anyOf', 'allOf', 'if', 'then', 'else', 'not', 'patternProperties', 'dependentSchemas', 'unevaluatedProperties'}


def load_schemas():
    return {p.name: json.loads(p.read_text(encoding='utf-8')) for p in sorted(V2.glob('*.schema.json'))}


class ContractsV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = load_schemas()
        cls.v1 = {p.name: json.loads(p.read_text(encoding='utf-8')) for p in (V2.parent/'v1').glob('*.schema.json')}
        cls.registry = Registry().with_resources((s['$id'], Resource.from_contents(s)) for s in [*cls.v1.values(), *cls.schemas.values()])
        cls.owner = {name: schema['$id'] for schema in cls.schemas.values() for name in schema['$defs']}
        cls.valid = json.loads((FIXTURES/'valid.json').read_text(encoding='utf-8'))
        cls.invalid = json.loads((FIXTURES/'invalid.json').read_text(encoding='utf-8'))

    def validator(self, name):
        return Draft202012Validator({'$ref': self.owner[name]+'#/$defs/'+name}, registry=self.registry)

    def test_schema_files_are_valid_versioned_and_licensed(self):
        self.assertEqual(14, len(self.schemas))
        names = []
        for filename, schema in self.schemas.items():
            Draft202012Validator.check_schema(schema)
            self.assertEqual('https://schemas.ololabs.io/toolgate/v2/'+filename, schema['$id'])
            self.assertIn('SPDX-License-Identifier: Apache-2.0', schema['$comment'])
            names += list(schema['$defs'])
        self.assertEqual(len(names), len(set(names)), 'definition names must be unique across v2')

    def test_references_resolve_only_to_bundled_canonical_versions(self):
        def visit(value, filename):
            if isinstance(value, dict):
                if '$ref' in value:
                    file, _, fragment = value['$ref'].partition('#')
                    if file.startswith('../v1/'):
                        self.assertEqual('../v1/'+Path(file).name, file)
                        target = self.v1[Path(file).name]
                    else:
                        target = self.schemas[file or filename]
                    for part in fragment.split('/')[1:]:
                        self.assertIn(part, target, (filename, value['$ref']))
                        target = target[part]
                for child in value.values(): visit(child, filename)
            elif isinstance(value, list):
                for child in value: visit(child, filename)
        for filename, schema in self.schemas.items(): visit(schema, filename)

    def test_definitions_stay_in_the_generator_subset(self):
        def visit(schema, where, top):
            self.assertFalse(UNSUPPORTED & schema.keys(), where)
            if not top:
                self.assertFalse('enum' in schema, where+': inline enums must be named in $defs')
                self.assertFalse(schema.get('type') == 'object' and 'properties' in schema, where+': inline objects must be named in $defs')
            if schema.get('type') == 'object' and 'properties' in schema:
                self.assertIs(False, schema.get('additionalProperties'), where+': models are closed')
                self.assertTrue(set(schema['required']) <= set(schema['properties']), where)
            for name, child in schema.get('properties', {}).items(): visit(child, where+'/'+name, False)
            if 'items' in schema: visit(schema['items'], where+'/items', False)
        for filename, schema in self.schemas.items():
            for name, definition in schema['$defs'].items(): visit(definition, filename+'#'+name, True)

    def test_every_definition_has_a_valid_fixture(self):
        self.assertEqual(set(self.owner), set(self.valid))
        for name, value in self.valid.items():
            errors = [e.message for e in self.validator(name).iter_errors(value)]
            self.assertEqual([], errors, name)

    def test_invalid_fixtures_are_rejected(self):
        self.assertGreaterEqual(sum(len(cases) for cases in self.invalid.values()), 30)
        for name, cases in self.invalid.items():
            for case in cases:
                self.assertFalse(self.validator(name).is_valid(case['value']), f"{name}: {case['case']}")

    def test_descriptor_vectors_are_valid_tool_descriptors(self):
        expected = json.loads((VECTORS/'expected.json').read_text(encoding='utf-8'))['digests']
        for path in sorted(VECTORS.glob('tool-*.json')):
            self.assertTrue(self.validator('ToolDescriptor').is_valid(json.loads(path.read_text(encoding='utf-8'))), path.name)
        tool = self.valid['ToolDefinition']
        self.assertEqual(expected['tool-02-orders-lookup.json'], tool['toolDescriptorDigest'])

    def test_proposed_openapi_is_separate_from_the_served_contract(self):
        served = {}
        for path in (ROOT/'packages/contracts/openapi').glob('*-v1.yaml'):
            for route, methods in yaml.safe_load(path.read_text(encoding='utf-8'))['paths'].items():
                for method, operation in methods.items():
                    if isinstance(operation, dict) and 'operationId' in operation: served[(route, method)] = operation['operationId']
        documents = sorted(PROPOSED.glob('*.proposed.yaml'))
        self.assertEqual(3, len(documents))
        seen = set()
        for path in documents:
            api = yaml.safe_load(path.read_text(encoding='utf-8'))
            self.assertEqual('3.1.0', api['openapi'])
            self.assertEqual((ROOT/'VERSION').read_text().strip(), api['info']['version'])
            for route, methods in api['paths'].items():
                for method, operation in methods.items():
                    op = operation['operationId']
                    self.assertNotIn(op, seen); seen.add(op)
                    self.assertNotIn(op, served.values(), f'{op} is already served')
                    if (route, method) in served:
                        self.assertTrue(operation['description'].startswith('Extends the existing endpoint'), f'{method} {route}')
            def refs(value):
                if isinstance(value, dict):
                    if isinstance(value.get('$ref'), str) and '.schema.json' in value['$ref']:
                        file, _, fragment = value['$ref'].partition('#')
                        target = json.loads((path.parent/file).resolve().read_text(encoding='utf-8'))
                        for part in fragment.split('/')[1:]: target = target[part]
                    for child in value.values(): refs(child)
                elif isinstance(value, list):
                    for child in value: refs(child)
            refs(api)


if __name__ == '__main__':
    unittest.main()
