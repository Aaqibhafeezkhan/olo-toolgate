# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Canonical operation coverage/drift without copying wire models into UI."""
import importlib.util
from pathlib import Path
import tempfile
import re
import unittest
import yaml
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('ui_generate',ROOT/'tools/ui/generate.py')
generator=importlib.util.module_from_spec(spec);spec.loader.exec_module(generator)


class UiContractTest(unittest.TestCase):
    def test_image_sbom_merges_runtime_graph_without_losing_existing_dependencies(self):
        sbom_spec=importlib.util.spec_from_file_location('ui_sbom',ROOT/'tools/ui/merge_sbom.py')
        module=importlib.util.module_from_spec(sbom_spec);sbom_spec.loader.exec_module(module)
        image={'metadata':{'component':{'bom-ref':'image'}},'components':[{'bom-ref':'java','purl':'pkg:maven/example@1'}], 'dependencies':[{'ref':'image','dependsOn':['java']}]}
        ui={'metadata':{'component':{'bom-ref':'workspace'}},'components':[{'bom-ref':'react','purl':'pkg:npm/react@19.3.0'},{'bom-ref':'ui','purl':'pkg:npm/example-ui@1'}], 'dependencies':[{'ref':'workspace','dependsOn':['ui']},{'ref':'ui','dependsOn':['react']},{'ref':'react','dependsOn':[]}]}
        merged=module.merge(image,ui)
        self.assertEqual(3,len(merged['components']))
        refs={component['bom-ref'] for component in merged['components']}|{'image'}
        for dependency in merged['dependencies']:
            self.assertIn(dependency['ref'],refs)
            self.assertTrue(set(dependency['dependsOn'])<=refs)
        self.assertEqual(['java','toolgate-ui:ui'],merged['dependencies'][0]['dependsOn'])
        self.assertEqual(3,len(module.merge(merged,ui)['components']))

    def test_checked_in_operations_match_canonical_openapi(self):
        self.assertEqual(generator.TARGET.read_text(encoding='utf-8'),generator.output())
        self.assertIn("from '@olo-labs/toolgate-contracts'",generator.output())
        api=yaml.safe_load((ROOT/'packages/contracts/openapi/control-v1.yaml').read_text())
        operations=[operation['operationId'] for methods in api['paths'].values() for method,operation in methods.items() if method in ('get','post','put','patch','delete')]
        self.assertEqual(generator.output().count('method:'),len(operations))
        for operation in operations:self.assertEqual(generator.output().count(operation+': {'),1)

    def test_drift_gate_rejects_stale_generated_client(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'operations.ts';path.write_text('stale')
            with patch.object(generator,'TARGET',path),patch('sys.argv',['generate.py','--check']):
                with self.assertRaises(SystemExit):generator.main()

    def test_console_documentation_links_exist(self):
        for name in ('apps/admin-ui/README.md','docs/control-plane/admin-ui.md','docs/adr/004-embedded-admin-console.md','docs/testing/README.md'):
            path=ROOT/name
            for target in re.findall(r'\]\(([^)]+)\)',path.read_text(encoding='utf-8')):
                if '://' in target or target.startswith('#'):continue
                self.assertTrue((path.parent/target.split('#')[0]).exists(),target)


if __name__=='__main__':unittest.main()
