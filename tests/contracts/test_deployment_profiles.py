# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Shipped deployment profiles validate against the v2 contract and pin only what the build inventory produces."""
import ast
import json
import re
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
V2 = ROOT/'packages/contracts/schemas/v2'
PROFILES = sorted((ROOT/'deploy/profiles').glob('*.json'))


def validator(name):
    registry = Registry()
    for path in V2.glob('*.schema.json'):
        schema = json.loads(path.read_text(encoding='utf-8'))
        registry = registry.with_resource(schema['$id'], Resource.from_contents(schema))
    return Draft202012Validator({'$ref': 'https://schemas.ololabs.io/toolgate/v2/profile.schema.json#/$defs/' + name}, registry=registry)


def core_exports():
    """Package id -> (version, declared tool local names) from the inventory's SDK sources."""
    inventory = json.loads((ROOT/'core-mcp/core-packages.build.json').read_text(encoding='utf-8'))
    exports = {}
    for entry in inventory['packages']:
        project = ROOT/'core-mcp'/entry['project']
        if entry['sdk'] == 'python':
            names = set()
            for path in (project/'src').rglob('*.py'):
                for node in ast.walk(ast.parse(path.read_text(encoding='utf-8'))):
                    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                        for decorator in node.decorator_list:
                            if isinstance(decorator, ast.Call) and isinstance(decorator.func, ast.Name) and decorator.func.id in {'client_tool', 'server_tool'}:
                                names.update(keyword.value.value for keyword in decorator.keywords
                                             if keyword.arg == 'name' and isinstance(keyword.value, ast.Constant))
        else:
            sources = (project/'src/main/java').rglob('*.java')
            names = {m for path in sources for m in re.findall(r'@(?:Client|Server)Tool\(name = "([a-z0-9_.-]+)"', path.read_text(encoding='utf-8'))}
        exports[entry['id']] = (entry['version'], names)
    return exports


class DeploymentProfileTests(unittest.TestCase):
    def test_a_default_profile_ships(self):
        self.assertIn('fresh.json', [p.name for p in PROFILES])

    def test_profiles_validate_and_pin_core_packages_from_the_inventory(self):
        profile_schema, exports = validator('DeploymentProfile'), core_exports()
        for path in PROFILES:
            with self.subTest(profile=path.name):
                profile = json.loads(path.read_text(encoding='utf-8'))
                self.assertEqual([], [e.message for e in profile_schema.iter_errors(profile)])
                self.assertEqual(path.stem, profile['profileId'])
                for package in profile['packages']:
                    tools = [t['toolId'] for t in package.get('tools', [])]
                    self.assertTrue(all(t.startswith(package['packageId'] + '/') for t in tools))
                    if package['source'] == 'PREBUILT_CORE':
                        version, names = exports[package['packageId']]
                        self.assertEqual(version, package['version'])
                        self.assertTrue({t.split('/', 1)[1] for t in tools} <= names, tools)
                    else:
                        self.assertRegex(package.get('packageDigest', ''), r'^sha256:[a-f0-9]{64}$')

    def test_the_contract_refuses_floating_versions_and_executable_definitions(self):
        profile_schema = validator('DeploymentProfile')
        base = {'profileVersion': 1, 'profileId': 'x', 'packages': []}
        self.assertFalse(profile_schema.is_valid(dict(base, packages=[{'packageId': 'examples.loans', 'source': 'MARKETPLACE', 'version': 'latest', 'required': True}])))
        self.assertFalse(profile_schema.is_valid(dict(base, tools=[{'id': 'x', 'command': 'sh'}])))
        self.assertFalse(profile_schema.is_valid(dict(base, packages=[{'packageId': 'examples.loans', 'source': 'FILE', 'version': '0.1.0', 'required': True}])))


if __name__ == '__main__':
    unittest.main()
