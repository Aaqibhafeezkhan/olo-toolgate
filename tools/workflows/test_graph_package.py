# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Graph package bytes are portable while installed-content checks stay strict."""
import json
from pathlib import Path
import tempfile
import unittest

from graph_package import build

ROOT = Path(__file__).resolve().parents[2]


class GraphPackageTests(unittest.TestCase):
    def test_manifest_file_order_is_case_sensitive_on_every_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            selection = build(ROOT / "apps/langgraph-runtime/graphs-src/supervisor", destination)
            installed = destination / selection["digest"].removeprefix("sha256:")
            manifest = json.loads((installed / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(["README.md", "graph.py"], list(manifest["files"]))
            self.assertEqual(selection, build(ROOT / "apps/langgraph-runtime/graphs-src/supervisor",
                                              destination, check=True))
            (installed / "graph.py").write_text("# changed\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Installed graph content differs"):
                build(ROOT / "apps/langgraph-runtime/graphs-src/supervisor", destination, check=True)

    def test_checked_in_packages_match_rebuilt_bytes(self):
        for source, destination in (
            ("apps/langgraph-runtime/graphs-src/supervisor", "apps/langgraph-runtime/src/toolgate_runtime/graphs"),
            ("examples/native-graphs/summary", "deploy/compose/WorkflowStack/configurations/native-log/graphs"),
        ):
            with self.subTest(source=source):
                build(ROOT / source, ROOT / destination, check=True)


if __name__ == "__main__":
    unittest.main()
