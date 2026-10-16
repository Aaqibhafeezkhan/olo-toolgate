# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("core_build", Path(__file__).with_name("build.py"))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class InventoryTest(unittest.TestCase):
    def read(self, document):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "inventory.json"
            path.write_text(document if isinstance(document, str) else json.dumps(document), encoding="utf-8")
            return list(build.inventory(path))

    def test_initial_inventory_builds_one_package_through_java_sdk(self):
        packages = list(build.inventory(build.CORE / "core-packages.build.json"))
        self.assertEqual(1, len(packages))
        self.assertEqual("java-gradle", packages[0][0]["sdk"])
        self.assertEqual(build.CORE / "hello-world", packages[0][1])

    def test_rejects_duplicate_keys_unsupported_adapters_and_outside_projects(self):
        initial = json.loads((build.CORE / "core-packages.build.json").read_text())
        invalid = ['{"schemaVersion":1,"schemaVersion":1,"packages":[]}']
        for key, value in (("sdk", "python"), ("project", "../apps/control-plane"), ("project", str(build.ROOT))):
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
