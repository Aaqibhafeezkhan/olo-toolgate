# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Contributor validation, deterministic output and release artifact integration."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml
from tools.release import notes, development_source, pipeline
from tools.release.bundle import checksum_assets


class ReleaseNotesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.version = 'v0.1.0-beta.1'
        self.folder = self.root/'.changes'/self.version
        self.folder.mkdir(parents=True)
        (self.root/'.changes/config.json').write_text(json.dumps(dict(version=self.version)))
        (self.root/'RELEASE-NOTES.md').write_text('# Product notes\n\nExisting compatibility warnings.\n')
        self.output = self.root/'assets'
        self.document = dict(schemaVersion=1, contributor=dict(name='Contributor', github='contributor'),
                             changes=[dict(type='fixed', summary='Correct device startup.', pr=123)])
        self.write('1-contributor.yaml')

    def write(self, name, document=None):
        (self.folder/name).write_text(yaml.safe_dump(self.document if document is None else document), encoding='utf-8')

    def test_repository_collection_is_valid(self):
        version, fragments = notes.load(notes.ROOT)
        expected = 'v'+json.loads((notes.ROOT/'release.json').read_text())['version'] if (notes.ROOT/'release.json').is_file() else 'v1.0'
        self.assertEqual(version, expected)
        self.assertTrue(fragments)

    def test_series_contributions_follow_beta_rc_stable_and_patch_releases(self):
        series = self.root/'.changes/v1.0'
        series.mkdir()
        (series/'2-series.yaml').write_text(yaml.safe_dump(self.document))
        for version in ('v1.0.0-beta.1', 'v1.0.0-rc.1', 'v1.0.0', 'v1.0.1'):
            with self.subTest(version=version):
                selected, fragments = notes.load(self.root, version)
                self.assertEqual(selected, version)
                self.assertEqual(len(fragments), 1)
                self.assertIn('.changes/v1.0/', fragments[0]['file'])
        with self.assertRaises(ValueError):
            notes.load(self.root, 'v1.1.0')
        exact = self.root/'.changes/v1.0.0-beta.1'
        exact.mkdir()
        (exact/'3-exact.yaml').write_text(yaml.safe_dump(self.document))
        self.assertEqual(len(notes.load(self.root, 'v1.0.0-beta.1')[1]), 2)
        (self.root/'release.json').write_text(json.dumps(dict(version='1.0.0-beta.1')))
        notes.generate(self.root, self.output)
        manifest = json.loads((self.output/'contributors.json').read_text())
        self.assertEqual(manifest['version'], 'v1.0.0-beta.1')
        self.assertEqual(len(manifest['fragments']), 2)

    def test_accumulation_numeric_order_unicode_and_repeatable_bytes(self):
        self.write('10-last.yml')
        second = copy.deepcopy(self.document)
        second['contributor']['name'] = 'ZoÃ«'
        second['changes'].insert(0, dict(type='breaking', summary='Review compatibility.'))
        self.write('2-second.yaml', second)
        baseline = (self.root/'RELEASE-NOTES.md').read_bytes()
        notes.generate(self.root, self.output)
        first = {p.name:p.read_bytes() for p in self.output.iterdir()}
        notes.generate(self.root, self.output)
        self.assertEqual(first, {p.name:p.read_bytes() for p in self.output.iterdir()})
        manifest = json.loads(first['contributors.json'])
        self.assertEqual([Path(f['file']).name for f in manifest['fragments']],
                         ['1-contributor.yaml', '2-second.yaml', '10-last.yml'])
        rendered = first['RELEASE-NOTES.md'].decode()
        self.assertIn('Existing compatibility warnings.', rendered)
        self.assertIn('ZoÃ«', rendered)
        self.assertLess(rendered.index('### Breaking'), rendered.index('### Fixed'))
        self.assertEqual(baseline, (self.root/'RELEASE-NOTES.md').read_bytes())
        self.assertEqual(len(list(self.folder.iterdir())), 3)
        self.assertTrue(all(b'\r' not in value for value in first.values()))
        checksum_assets(self.output)
        self.assertIn('contributors.json', (self.output/'SHA256SUMS').read_text())
        self.assertIn('RELEASE-NOTES.generated.md', (self.output/'SHA256SUMS').read_text())

    def test_schema_rejects_missing_unknown_and_invalid_fields(self):
        invalid = []
        for key in ('schemaVersion', 'contributor', 'changes'):
            doc = copy.deepcopy(self.document)
            del doc[key]
            invalid.append(doc)
        for field, value in [('changes', []), ('schemaVersion', 2), ('extra', 'unknown'),
                             ('contributor', dict(name='  ')), ('contributor', dict(name='Name', github='@bad'))]:
            doc = copy.deepcopy(self.document)
            doc[field] = value
            invalid.append(doc)
        for change in [dict(type='typo', summary='Hi'), dict(type='fixed', summary=''),
                       dict(type='fixed', summary='Hi', pr=True), dict(type='fixed', summary='Hi', pr=0)]:
            doc = copy.deepcopy(self.document)
            doc['changes'] = [change]
            invalid.append(doc)
        for doc in invalid:
            with self.subTest(doc=doc):
                self.write('1-contributor.yaml', doc)
                with self.assertRaises(ValueError):
                    notes.generate(self.root, self.output)
                self.assertFalse(self.output.exists())

    def test_duplicate_keys_unsafe_yaml_and_multiple_documents_fail(self):
        for content in ['schemaVersion: 1\nschemaVersion: 1\n',
                        '!!python/object/apply:os.system [echo unsafe]', '---\n{}\n---\n{}',
                        'contributor: {name: First, name: Second}', 'changes: [']:
            with self.subTest(content=content):
                (self.folder/'1-contributor.yaml').write_text(content)
                with self.assertRaises(ValueError):
                    notes.load(self.root)

    def test_invalid_path_filename_missing_collection_and_empty_collection_fail(self):
        for version in ('../escape', '/absolute', 'v01.0.0', 'v0.1.0-beta.1/../other'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                notes.load(self.root, version)
        with self.assertRaises(ValueError):
            notes.load(self.root, 'v9.0.0')
        self.write('un-numbered.yaml')
        with self.assertRaises(ValueError):
            notes.load(self.root)
        for path in self.folder.iterdir():
            path.unlink()
        with self.assertRaises(ValueError):
            notes.load(self.root)

    def test_only_selected_collection_is_included_and_explicit_version_works(self):
        other = self.root/'.changes/v0.2.0'
        other.mkdir()
        (other/'1-other.yaml').write_text(yaml.safe_dump(dict(self.document,
            contributor=dict(name='Future contributor'))))
        notes.generate(self.root, self.output)
        self.assertNotIn('Future contributor', (self.output/'RELEASE-NOTES.md').read_text())
        notes.generate(self.root, self.output, 'v0.2.0')
        self.assertIn('Future contributor', (self.output/'RELEASE-NOTES.md').read_text())

    def test_historical_source_without_collection_retains_baseline(self):
        historical = self.root/'old'
        historical.mkdir()
        (historical/'RELEASE-NOTES.md').write_text('Old notes\n')
        notes.generate(historical, self.output)
        self.assertEqual((self.output/'RELEASE-NOTES.md').read_text(), 'Old notes\n')
        self.assertEqual(json.loads((self.output/'contributors.json').read_text())['fragments'], [])

    def test_markdown_and_html_in_contributor_text_are_literal(self):
        self.document['changes'][0]['summary'] = '<script>alert(1)</script> [click](https://bad.invalid)\n# heading'
        self.write('1-contributor.yaml')
        notes.generate(self.root, self.output)
        rendered = (self.output/'RELEASE-NOTES.generated.md').read_text()
        self.assertNotIn('<script>', rendered)
        self.assertNotIn('[click](', rendered)
        self.assertIn('\\# heading', rendered)

    def test_shared_work_ticket_owner_summary_and_professional_metadata_survive_generation(self):
        rahul = dict(name='Rahul Nigam', github='olo-labs')
        anupriya = dict(name='Anupriya Srivastava', github='olo-orgs')
        change = self.document['changes'][0]
        change.update(contributors=[self.document['contributor'], rahul, anupriya], owners=[rahul, anupriya],
                      pullRequests=[456, 123], impact='Operators can recover interrupted work.',
                      migration='Restart the worker after updating.', audience=['operators', 'developers'],
                      tickets=[dict(system='jira', id='TG-123', url='https://jira.example.com/browse/TG-123?q=(test)',
                                    summary='Owner-approved recovery summary.', owner=rahul, summaryAuthor=rahul,
                                    contributors=[rahul, anupriya])])
        self.write('1-contributor.yaml')
        notes.generate(self.root, self.output)
        manifest = json.loads((self.output/'contributors.json').read_text())
        self.assertEqual(manifest['fragments'][0]['changes'][0], change)
        rendered = (self.output/'RELEASE-NOTES.generated.md').read_text()
        self.assertIn('(#123, #456)', rendered)
        self.assertIn('Owners: Rahul Nigam (@olo-labs), Anupriya Srivastava (@olo-orgs)', rendered)
        self.assertIn('Summary by: Rahul Nigam (@olo-labs)', rendered)
        self.assertIn('TG-123?q=%28test%29)', rendered)
        self.assertIn('Impact: Operators', rendered)
        self.assertIn('Migration: Restart', rendered)
        credit = next(line for line in rendered.splitlines() if line.startswith('- '))
        self.assertEqual(credit.count('Contributor (@contributor)'), 1)

    def test_invalid_ticket_collaborator_and_pr_metadata_is_rejected(self):
        for field, value in [('pullRequests', [0]), ('pullRequests', [True]), ('pullRequests', [1, 1]),
                             ('owners', []), ('contributors', [dict(github='missing-name')]),
                             ('audience', ['unknown']), ('impact', ' '),
                             ('tickets', [dict(system='jira', id='TG-1')]),
                             ('tickets', [dict(system='jira', id='TG-1', url='javascript:alert(1)')]),
                             ('tickets', [dict(system='jira', id='TG-1', url='https://example.com', owner='someone')]),
                             ('contributors', [dict(name='Name', roles=['unknown'])]),
                             ('owners', [dict(name='Name', roles=[])]),
                             ('owners', [dict(name='Name', roles=['reviewer', 'reviewer'])]),
                             ('pullRequests', [dict(number=0)]),
                             ('pullRequests', [dict(number=1, owners=[])]),
                             ('pullRequests', [dict(number=1, reviewers=[dict(github='missing-name')])])]:
            with self.subTest(field=field, value=value):
                doc = copy.deepcopy(self.document)
                doc['changes'][0][field] = value
                self.write('1-contributor.yaml', doc)
                with self.assertRaises(ValueError):
                    notes.load(self.root)

    def test_individual_prs_and_features_support_multiple_owners_with_roles(self):
        owners = [dict(name='Rahul Nigam', github='olo-labs', roles=['core-contributor', 'author']),
                  dict(name='Anupriya Srivastava', github='olo-orgs', roles=['core-contributor', 'reviewer'])]
        reviewer = dict(name='Reviewer', roles=['core-maintainer', 'reviewer'])
        change = self.document['changes'][0]
        change.update(owners=owners, pullRequests=[456, dict(number=123, title='Shared feature',
                      url='https://github.com/olo-labs/olo-toolgate/pull/123', owners=owners,
                      contributors=owners, reviewers=[reviewer])])
        self.write('1-contributor.yaml')
        notes.generate(self.root, self.output)
        rendered = (self.output/'RELEASE-NOTES.generated.md').read_text()
        self.assertIn('(#123, #456)', rendered)
        self.assertIn('PR [#123](https://github.com/olo-labs/olo-toolgate/pull/123): Shared feature', rendered)
        self.assertEqual(rendered.count('Owners: Rahul Nigam'), 2)
        self.assertIn('Anupriya Srivastava (@olo-orgs) (core contributor, reviewer)', rendered)
        self.assertIn('Reviewers: Reviewer (core maintainer, reviewer)', rendered)
        manifest = json.loads((self.output/'contributors.json').read_text())
        self.assertEqual(manifest['fragments'][0]['changes'][0]['pullRequests'][1]['owners'], owners)

    def test_manual_overlay_renders_selected_source_notes(self):
        info = dict(sourceCommit='a'*40)
        destination = self.root/'overlay'
        with patch.object(development_source.prepare, 'run', side_effect=['a'*40, '', '']), \
                patch.object(development_source.prepare, 'stamp_versions', side_effect=lambda root, info: (root/'release.json').write_text(json.dumps(dict(version=self.version[1:], channel='Development')))):
            development_source.overlay(self.root, destination, info)
        self.assertTrue((destination/'release.json').is_file())
        self.assertIn('Contributor', (destination/'release-notes/RELEASE-NOTES.md').read_text())
        self.assertEqual(json.loads((destination/'release-notes/contributors.json').read_text())['version'], self.version)

    def test_prepared_collection_includes_contributors_before_bundle_checksums(self):
        info = dict(version='0.1.0-beta.42', commit='a'*40, sourceCommit='b'*40, buildRun='123')
        foundation = self.root/'build/release-evidence/foundation/build/release'
        foundation.mkdir(parents=True)
        version = info['version']
        names = {'release.json', 'sbom.cdx.json', 'compatibility.json', 'LICENSE', 'NOTICE.md', 'RELEASE-NOTES.md',
                 f'olo-toolgate-{version}.tgz', f'olo-toolgate-contracts-{version}.tar.gz',
                 f'olo-toolgate-contracts-{version}.crate', f'olo-toolgate-contracts-php-{version}.zip',
                 f'olo-labs-toolgate-contracts-{version}.tgz'}
        names.update(f'toolgate-contracts-{version}{s}' for s in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'))
        for name in names:
            (foundation/name).write_text('checked package fixture')
        client = self.root/'build/release-evidence/client'
        client.mkdir()
        (client/'fixture').write_bytes(b'checked client fixture')
        artifact = dict(filename='fixture', sha256=pipeline.digest(client/'fixture'), bytes=(client/'fixture').stat().st_size)
        for name in ('manifest.json', 'installers.json'):
            (client/name).write_text(json.dumps(dict(version=version, artifacts=[artifact] * 6)))
        output = self.root/'build/product-release'
        with patch.object(pipeline, 'ROOT', self.root), patch.object(pipeline, 'OUTPUT', output), \
                patch.object(pipeline, 'selection', return_value=info), patch.object(pipeline, 'verify_source'), \
                patch.object(pipeline, 'verify_run', return_value=dict(head_sha='c'*40)), \
                patch.object(pipeline, 'run'), patch.object(pipeline, 'verified_files', return_value=names), \
                patch.object(pipeline, 'verify_packages'), patch.object(pipeline, 'validate_bundle'), \
                patch.object(pipeline, 'IMAGES', {}), patch.dict('os.environ', GITHUB_REPOSITORY='olo-labs/olo-toolgate'):
            pipeline.collect(self.root)
        assets = output/'assets'
        self.assertIn('Contributor', (assets/'RELEASE-NOTES.md').read_text())
        self.assertEqual(json.loads((assets/'contributors.json').read_text())['version'], self.version)
        checksums = json.loads((output/'bundle-checksums.json').read_text())
        for name in ('RELEASE-NOTES.md', 'RELEASE-NOTES.generated.md', 'contributors.json'):
            self.assertEqual(checksums['assets/'+name], pipeline.digest(assets/name))


if __name__ == '__main__':
    unittest.main()
