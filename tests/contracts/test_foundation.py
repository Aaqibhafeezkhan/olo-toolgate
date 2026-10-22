# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Offline schema, compatibility, generator and CI boundary tests."""
import copy
import importlib.util
import json
import re
import subprocess
import sys
import unittest
import tomllib
import tempfile
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, Draft202012Validator, ValidationError
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]


def schemas_at(directory):
    return {p.name: json.loads(p.read_text()) for p in directory.glob('*.schema.json')}


def registry(schemas):
    # v1 models reference v2 definitions (the request context), so v2 always resolves alongside.
    v2 = [json.loads(p.read_text(encoding='utf-8')) for p in sorted((ROOT/'packages/contracts/schemas/v2').glob('*.schema.json'))]
    resources = {s['$id']: s for s in [*v2, *schemas.values()]}
    return Registry().with_resources((i, Resource.from_contents(s)) for i, s in resources.items())


def validators(schemas):
    result = {}
    store = registry(schemas)
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
        for name in schema['$defs']:
            wrapper = {'$ref':schema['$id']+'#/$defs/'+name}
            result[name] = Draft202012Validator(wrapper, registry=store)
    return result


def compatible(old, new, path='root'):
    """Check the supported subset for narrowing of accepted old inputs.

    Closed objects make new fields a reader incompatibility even if optional;
    callers need a negotiated wire revision, rather than silent field dropping.
    """
    for key in ('type', '$ref', 'const', 'pattern', 'additionalProperties'):
        if key in old and new.get(key) != old[key]:
            raise ValueError(f'Compatibility changed: {path}/{key}')
    if ('enum' in old or 'enum' in new) and set(old.get('enum', [])) != set(new.get('enum', [])):
        # Writers must not introduce values that the frozen reader cannot decode.
        raise ValueError(f'Enum wire values changed: {path}')
    if not set(new.get('required', [])) <= set(old.get('required', [])):
        raise ValueError(f'Required fields added: {path}')
    for key in ('maxLength','maximum','maxItems'):
        if key in new and new[key] < old.get(key, float('inf')):
            raise ValueError(f'Upper bound narrowed: {path}/{key}')
    for key in ('minLength','minimum','minItems'):
        if key in new and new[key] > old.get(key, float('-inf')):
            raise ValueError(f'Lower bound narrowed: {path}/{key}')
    for key in ('$defs','properties'):
        if key in old:
            if key == 'properties' and old.get('additionalProperties') is False and set(new.get(key, {})) != set(old[key]):
                raise ValueError(f'Closed reader fields changed: {path}')
            for name, value in old[key].items():
                if name not in new.get(key, {}):
                    raise ValueError(f'Definition removed: {path}/{name}')
                compatible(value, new[key][name], f'{path}/{name}')
    if 'items' in old:
        compatible(old['items'], new['items'], path+'/items')


class FoundationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas = schemas_at(ROOT/'packages/contracts/schemas/v1')
        cls.validators = validators(cls.schemas)
        cls.fixtures = json.loads((ROOT/'tests/fixtures/contracts/v1/valid.json').read_text())

    def test_all_schemas_and_every_definition_have_valid_fixtures(self):
        self.assertEqual(set(self.validators), set(self.fixtures))
        self.assertEqual(18, len(self.schemas))
        for name, fixture in self.fixtures.items():
            with self.subTest(model=name):
                self.validators[name].validate(fixture)
                self.assertEqual(fixture, json.loads(json.dumps(fixture)))

    def test_every_object_rejects_unknown_and_missing_fields(self):
        for name, fixture in self.fixtures.items():
            if not isinstance(fixture, dict):
                continue
            with self.subTest(model=name):
                bad = dict(fixture, bypass=True)
                with self.assertRaises(ValidationError):
                    self.validators[name].validate(bad)
                for key in next(s['$defs'][name] for s in self.schemas.values() if name in s['$defs'])['required']:
                    bad = dict(fixture)
                    bad.pop(key)
                    with self.assertRaises(ValidationError):
                        self.validators[name].validate(bad)

    def test_negative_security_inputs(self):
        for name, bad in [('Identifier','../escape'),('Identifier',''),('Sha256','A'*64),('SemanticVersion','01.0.0'),('SecretReference','plaintext-token'),('Decision','UNKNOWN')]:
            with self.subTest(model=name), self.assertRaises(ValidationError):
                self.validators[name].validate(bad)
        for name, field, value in [('PackageManifest','credentialReferences',['literal-secret']),('EnterpriseDecision','decision',None),('DesiredState','revision',-1),('ErrorEnvelope','stackTrace','private exception')]:
            bad = dict(self.fixtures[name], **{field:value})
            with self.subTest(model=name), self.assertRaises(ValidationError):
                self.validators[name].validate(bad)

    def test_trust_domains_are_not_interchangeable(self):
        for source, target in [('MarketplaceRelease','DeploymentAssignment'),('DeploymentAssignment','EnterpriseDecision'),('EnterpriseDecision','MarketplaceRelease')]:
            with self.assertRaises(ValidationError):
                self.validators[target].validate(self.fixtures[source])

    def test_frozen_compatibility_corpus_and_schema(self):
        baseline = schemas_at(ROOT/'tests/fixtures/contracts/v1/schemas')
        corpus = json.loads((ROOT/'tests/fixtures/contracts/v1/compatibility.json').read_text())
        self.assertEqual(1, corpus['readerMajor'])
        archival=validators(baseline)
        for name,fixture in corpus['fixtures'].items():archival[name].validate(fixture)
        # Group-only runtime contracts deliberately reject the frozen principal shape.
        # The request context is defined in v2 now; it still rejects the frozen device-bound shape.
        with self.assertRaises(ValidationError):validators(schemas_at(ROOT/'packages/contracts/schemas/v2'))['RequestContext'].validate(corpus['fixtures']['RequestContext'])
        with self.assertRaises(ValueError):compatible(baseline['common.schema.json'],self.schemas['common.schema.json'])
        for name,schema in baseline.items():
            if name not in ('common.schema.json','policy.schema.json','runtime.schema.json'):
                current = self.schemas[name]
                if name == 'client.schema.json':
                    # The local power switch requires a current Control reader. Its
                    # precise extension is covered below; all other frozen fields
                    # retain the strict reader compatibility check.
                    current = copy.deepcopy(current)
                    del current['$defs']['ClientReport']['properties']['power']
                if name == 'error.schema.json':
                    # This unreleased tenant baseline requires matching current readers.
                    # Check the exact intentional enum extension separately, retaining
                    # compatibility checks for every other error-envelope field.
                    current = copy.deepcopy(current)
                    current['$defs']['ErrorCode']['enum'] = schema['$defs']['ErrorCode']['enum']
                compatible(schema,current,name)

    def test_tenant_error_codes_require_the_current_reader(self):
        baseline = schemas_at(ROOT/'tests/fixtures/contracts/v1/schemas')
        previous = baseline['error.schema.json']['$defs']['ErrorCode']['enum']
        added = {'TENANT_MISMATCH','TENANT_UNAVAILABLE','MISDIRECTED_REQUEST','INVALID_TENANT_ID'}
        self.assertEqual(set(previous) | added,set(self.schemas['error.schema.json']['$defs']['ErrorCode']['enum']))
        archived = validators(baseline)['ErrorEnvelope']
        for code in added:
            envelope = {'code':code,'requestId':'tenant-error','retryable':False}
            self.validators['ErrorEnvelope'].validate(envelope)
            with self.subTest(code=code),self.assertRaises(ValidationError):archived.validate(envelope)
        with self.assertRaises(ValueError):compatible(baseline['error.schema.json'],self.schemas['error.schema.json'])

    def test_device_power_report_requires_current_control_reader(self):
        baseline = schemas_at(ROOT/'tests/fixtures/contracts/v1/schemas')
        archived = validators(baseline)['ClientReport']
        current = self.validators['ClientReport']
        report = json.loads((ROOT/'tests/fixtures/contracts/v1/compatibility.json').read_text())['fixtures']['ClientReport']
        archived.validate(report)
        current.validate(report)  # Older clients remain accepted by current Control.
        power = self.schemas['client.schema.json']['$defs']['ClientReport']['properties']['power']
        self.assertEqual(power, {'$ref':'client.schema.json#/$defs/DevicePower'})
        self.assertEqual(self.schemas['client.schema.json']['$defs']['DevicePower']['enum'], ['ON','OFF'])
        for value in ('ON','OFF'):
            powered = dict(report, power=value)
            current.validate(powered)
            with self.assertRaises(ValidationError):archived.validate(powered)
        for value in ('UNKNOWN', None, True):
            with self.assertRaises(ValidationError):current.validate(dict(report, power=value))
        with self.assertRaisesRegex(ValueError, 'Closed reader fields changed'):
            compatible(baseline['client.schema.json'], self.schemas['client.schema.json'])

    def test_compatibility_checker_detects_breaks(self):
        old = {'$defs':{'Decision':self.schemas['policy.schema.json']['$defs']['Decision'],'EnterpriseDecision':self.schemas['enterprise.schema.json']['$defs']['EnterpriseDecision']}}
        for change in ('enum','enum-added','enum-removed','required','removed','bound','added'):
            new = copy.deepcopy(old)
            model = new['$defs']['EnterpriseDecision']
            if change == 'enum': new['$defs']['Decision']['enum'].remove('BLOCK')
            if change == 'enum-added': new['$defs']['Decision']['enum'].append('DEFER')
            if change == 'enum-removed': del new['$defs']['Decision']['enum']
            if change == 'required': model['required'].append('newField')
            if change == 'removed': del model['properties']['diagnosticId']
            if change == 'bound': model['properties']['newField'] = {'type':'string','maxLength':1}
            if change == 'added': model['properties']['newField'] = {'type':'string'}
            with self.subTest(change=change), self.assertRaises(ValueError): compatible(old, new)

    def test_restricted_enum_cannot_replace_unrestricted_string(self):
        with self.assertRaises(ValueError):
            compatible({'type':'string'}, {'type':'string','enum':['BLOCK']})

    def test_drift_detection_is_nonmutating_and_deterministic(self):
        spec = importlib.util.spec_from_file_location('generator', ROOT/'tools/contracts/generate.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        self.assertEqual(generator.render(), generator.render())
        for name, expected in generator.render().items():
            self.assertEqual(expected.encode(), (ROOT/name).read_bytes(), name)
        target = ROOT/'packages/contracts/typescript/src/index.ts'
        original = target.read_bytes()
        try:
            target.write_bytes(original+b'// drift\n')
            result = subprocess.run([sys.executable, str(ROOT/'tools/contracts/generate.py'), '--check'], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn(b'Generated code drift', result.stderr)
            self.assertEqual(original+b'// drift\n', target.read_bytes())
        finally:
            target.write_bytes(original)

    def test_chart_values_and_disabled_gateway_default(self):
        chart = ROOT/'deploy/helm/olo-toolgate'
        schema = json.loads((chart/'values.schema.json').read_text())
        Draft7Validator.check_schema(schema)
        values = yaml.safe_load((chart/'values.yaml').read_text())
        Draft7Validator(schema).validate(values)
        for bad in [dict(values, gateway={'enabled':True}), {'global':{'imageRegistry':'INVALID SPACE','imagePullSecrets':[]}}, {'global':{'imageRegistry':'ghcr.io/olo-labs','imagePullSecrets':[{'name':'x','password':'secret'}]}}]:
            with self.assertRaises(ValidationError): Draft7Validator(schema).validate(bad)
        self.assertFalse(values['gateway']['enabled'])
        self.assertIn('gateway.yaml', {p.name for p in (chart/'templates').iterdir()})

    def test_release_bundle_is_reproducible(self):
        spec = importlib.util.spec_from_file_location('bundle', ROOT/'tools/release/bundle.py')
        bundle = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bundle)
        self.assertEqual(bundle.bundle_bytes(), bundle.bundle_bytes())

    def test_version_sync_does_not_rewrite_dependencies(self):
        spec = importlib.util.spec_from_file_location('versioning', ROOT/'tools/contracts/version.py')
        versioning = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(versioning)
        expected = versioning.expected()
        current = tomllib.loads((ROOT/'Cargo.toml').read_text())
        synchronized = tomllib.loads(expected[ROOT/'Cargo.toml'])
        for name in ('serde','serde_json'):
            self.assertEqual(current['workspace']['dependencies'][name], synchronized['workspace']['dependencies'][name])

    def test_release_versions_reject_numeric_prerelease_leading_zeroes(self):
        spec = importlib.util.spec_from_file_location('versioning', ROOT/'tools/contracts/version.py')
        versioning = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(versioning)
        for version in ('0.0.0','1.2.3','1.2.3-dev','1.2.3-0','1.2.3-rc.1','1.2.3-01alpha','1.2.3-alpha-1'):
            with self.subTest(version=version): self.assertIsNotNone(versioning.SEMVER.fullmatch(version))
        for version in ('01.2.3','1.02.3','1.2.03','1.2.3-01','1.2.3-rc.01','1.2.3-','1.2.3-rc..1','1.2.3+build.1','1.2.3\n'):
            with self.subTest(version=version): self.assertIsNone(versioning.SEMVER.fullmatch(version))
        sources = [ROOT/'VERSION', ROOT/'packages/contracts/VERSION']
        originals = [path.read_bytes() for path in sources]
        result = subprocess.run([sys.executable, str(ROOT/'tools/contracts/version.py'), '--set', '1.2.3-01'], capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b'valid SemVer', result.stderr)
        self.assertEqual(originals, [path.read_bytes() for path in sources])

    def test_header_and_secret_gate_rejects_bad_sources(self):
        spec = importlib.util.spec_from_file_location('quality', ROOT/'tools/quality.py')
        quality = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(quality)
        self.assertIn('missing copyright/SPDX header', quality.violations(Path('bad.java'), 'public class Bad {}'))
        self.assertTrue(quality.violations(Path('bad.txt'), 'AK'+'IA'+'Z'*16))

    def test_generator_refuses_unsupported_composition(self):
        spec = importlib.util.spec_from_file_location('generator', ROOT/'tools/contracts/generate.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        with self.assertRaises(ValueError):
            generator.validate_shape({'type':'object','allOf':[{'type':'object'}]})

    def test_dependency_license_policy_respects_and_or(self):
        spec = importlib.util.spec_from_file_location('licenses', ROOT/'tools/dependency_licenses.py')
        licenses = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(licenses)
        self.assertTrue(licenses.accepted('MIT OR GPL-3.0-only'))
        self.assertFalse(licenses.accepted('MIT AND GPL-3.0-only'))
        self.assertFalse(licenses.accepted('GPL-3.0-only'))
        self.assertTrue(licenses.accepted('Apache-2.0 WITH LLVM-exception OR MIT'))
        self.assertFalse(licenses.accepted('Apache-2.0 WITH LLVM-exception'))
        self.assertTrue(licenses.accepted('(MIT OR Apache-2.0) AND Unicode-3.0'))
        self.assertTrue(licenses.accepted('MIT-0'))
        self.assertTrue(licenses.accepted('Zlib'))
        self.assertTrue(licenses.accepted(licenses.cargo_license('MIT/Apache-2.0')))
        self.assertTrue(licenses.accepted(licenses.cargo_license('Unlicense/MIT')))
        self.assertEqual(licenses.cargo_license('Unknown/MIT'), 'Unknown/MIT')
        with self.assertRaises(Exception): licenses.accepted(licenses.cargo_license('Unknown/MIT'))
        self.assertTrue(licenses.accepted('EPL-2.0', tooling=True))
        self.assertFalse(licenses.accepted('EPL-2.0'))
        with self.assertRaises(Exception): licenses.accepted('Unknown-Private-License')

    def test_foundation_documentation_local_links(self):
        paths = [ROOT/'docs/development/foundation.md', ROOT/'docs/adr/001-foundation-contract-generation.md', ROOT/'deploy/helm/olo-toolgate/README.md']
        workflow = yaml.safe_load((ROOT/'.github/workflows/foundation.yml').read_text(encoding='utf-8'))
        assets = next(step for step in workflow['jobs']['check']['steps']
                      if step.get('name') == 'Create raw contract release smoke assets')
        # Validate the guides actually shipped by CI, including renamed release assets.
        paths += [ROOT/name for name in re.findall(r'\bdocs/[\w./-]+\.md\b', assets['run'])]
        paths += [ROOT/f'packages/contracts/{language}/README.md' for language in ('java','rust','typescript','php','schemas')]
        for path in paths:
            text = path.read_text(encoding='utf-8')
            for target in re.findall(r'\]\(([^)]+)\)', text):
                if '://' in target or target.startswith('#'): continue
                self.assertTrue((path.parent/target.split('#')[0]).is_file(), f'{path}: {target}')




class GeneratorNewlineTests(unittest.TestCase):
    def test_source_hash_is_identical_for_lf_and_crlf_checkouts(self):
        spec = importlib.util.spec_from_file_location('generator', ROOT/'tools/contracts/generate.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'schema.json'
            path.write_bytes(b'{\n  "type": "object"\n}\n')
            expected = generator.source_sha256(path)
            path.write_bytes(path.read_bytes().replace(b'\n', b'\r\n'))
            self.assertEqual(generator.source_sha256(path), expected)

if __name__ == '__main__':
    unittest.main()
