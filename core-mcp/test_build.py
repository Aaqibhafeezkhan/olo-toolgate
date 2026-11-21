# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("core_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class InventoryTest(unittest.TestCase):
    def test_manual_packaging_can_omit_tests_but_retains_cli_validation_and_lock(self):
        raw=(build.ROOT/'tests/fixtures/packages/cases/valid-deflated.olotool').read_bytes()
        import io
        import zipfile
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            manifest=json.loads(archive.read('toolgate-package.json'))
        for flags in ([],['--without-tests']):
            with self.subTest(flags=flags),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);project=root/'project';artifacts=project/'build/toolgate';artifacts.mkdir(parents=True)
                (artifacts/'test.olotool').write_bytes(raw)
                (root/'VERSION').write_text(manifest['sdk']['version'],encoding='utf-8')
                cli=root/'toolgate';cli.write_bytes(b'fixture')
                output=root/'output'
                entry=dict(project='project',sdk='java-gradle',id=manifest['id'],version=manifest['version'])
                with patch.object(build,'ROOT',root),patch.object(build,'inventory',return_value=[(entry,project)]), \
                        patch.object(build.sys,'argv',['build.py','--cli',str(cli),'--output',str(output),*flags]),patch.object(build,'run') as run:
                    build.main()
                commands=[list(call.args[0]) for call in run.call_args_list]
                java=next(command for command in commands if 'toolgatePackage' in command)
                self.assertEqual(not flags,'check' in java)
                self.assertTrue(any('validate' in command for command in commands))
                lock=json.loads((output/'core-packages.lock.json').read_text(encoding='utf-8'))
                self.assertEqual(manifest['id'],lock['packages'][0]['id'])
                self.assertEqual(raw,(output/lock['packages'][0]['artifact']).read_bytes())

    def read(self, document):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.json"
            path.write_text(document if isinstance(document, str) else json.dumps(document), encoding="utf-8")
            return list(build.inventory(path))

    def test_inventory_builds_through_all_sdk_adapters(self):
        packages = list(build.inventory(build.CORE / "core-packages.build.json"))
        self.assertEqual([("ololabs.hello-world", "java-gradle", build.CORE / "hello-world"),
                          ("ololabs.hello-world-python", "python", build.CORE / "hello-world-python"),
                          ("ololabs.echo", "json", build.CORE / "echo")],
                         [(p["id"], p["sdk"], project) for p, project in packages])

    def test_json_projects_build_from_a_copy_pinned_to_the_build_sdk_version(self):
        with tempfile.TemporaryDirectory() as directory:
            staged = build.pinned_json_project(build.CORE / "echo", Path(directory) / "echo", "9.9.9-dev.1.2.gabc")
            self.assertEqual("9.9.9-dev.1.2.gabc", json.loads((staged / "toolgate.json").read_text())["sdkVersion"])
            source = json.loads((build.CORE / "echo" / "toolgate.json").read_text())
            self.assertEqual({k: v for k, v in source.items() if k != "sdkVersion"},
                             {k: v for k, v in json.loads((staged / "toolgate.json").read_text()).items() if k != "sdkVersion"})

    def test_rejects_duplicate_keys_unsupported_adapters_and_outside_projects(self):
        initial = json.loads((build.CORE / "core-packages.build.json").read_text())
        invalid = ['{"schemaVersion":1,"schemaVersion":1,"packages":[]}']
        for key, value in (("sdk", "unsupported"), ("project", "../apps/control-plane"), ("project", str(build.ROOT))):
            document = json.loads(json.dumps(initial))
            document["packages"][0][key] = value
            invalid.append(document)
        repeated = json.loads(json.dumps(initial))
        repeated["packages"].append(repeated["packages"][0].copy())
        invalid.append(repeated)
        invalid.append(dict(schemaVersion=1, packages=[initial["packages"][0]] * 33))
        for document in invalid:
            with self.subTest(document=document), self.assertRaises(ValueError):
                self.read(document)


if __name__ == "__main__":
    unittest.main()
