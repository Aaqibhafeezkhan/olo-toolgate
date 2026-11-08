# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for the repository Markdown link checker."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


CHECKER_PATH = Path(__file__).resolve().parents[2] / "tools/docs/check_links.py"
SPEC = importlib.util.spec_from_file_location("check_links", CHECKER_PATH)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class MarkdownLinkCheckerTests(unittest.TestCase):
    """Exercise Markdown discovery, anchors, and filesystem link resolution."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "docs").mkdir()

    def write(self, path, content=""):
        """Write a UTF-8 fixture beneath the temporary repository root."""
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return target

    def run_checker(self):
        """Run the checker against this test's isolated repository fixture."""
        with patch.object(CHECKER, "ROOT", self.root):
            return CHECKER.main()

    def test_discovers_component_markdown_and_skips_generated_or_vendored_trees(self):
        self.write("README.md")
        self.write("apps/admin-ui/README.md")
        self.write("docs/guide.md")
        self.write("node_modules/package/README.md")
        self.write("vendor/package/README.md")
        self.write("dist/generated.md")
        self.write("apps/admin-ui/build/generated.md")
        with patch.object(CHECKER, "ROOT", self.root):
            discovered = {path.relative_to(self.root).as_posix() for path in CHECKER.markdown_files()}
        self.assertEqual(discovered, {"README.md", "apps/admin-ui/README.md", "docs/guide.md"})

    def test_collects_id_and_name_anchors_without_matching_data_id(self):
        target = self.write(
            "docs/anchors.md",
            '<div data-id="not-an-anchor"></div>\n'
            '<a name="legacy-anchor"></a>\n'
            '<section id="modern-anchor"></section>\n',
        )
        identifiers = CHECKER.heading_ids(target)
        self.assertIn("legacy-anchor", identifiers)
        self.assertIn("modern-anchor", identifiers)
        self.assertNotIn("not-an-anchor", identifiers)

    def test_existing_directory_without_index_is_a_valid_link_target(self):
        self.write("docs/index.md", "[examples](../examples/)\n")
        (self.root / "examples").mkdir()
        self.write("examples/sample.txt", "sample")
        self.assertEqual(self.run_checker(), 0)

    def test_nonexistent_directory_remains_a_broken_target(self):
        self.write("docs/index.md", "[missing](../missing/)\n")
        self.assertEqual(self.run_checker(), 1)

    def test_directory_anchor_uses_readme_or_index_file(self):
        self.write("docs/index.md", "[guide](../guide/#installation)\n")
        self.write("guide/README.md", "Installation\n============\n")
        self.assertEqual(self.run_checker(), 0)

    def test_template_placeholder_destinations_are_not_repository_paths(self):
        self.write("tools/release/page-template.md",
                   "[guide]($repo/blob/$tag/docs/README.md)\n[log](${repo}/CHANGELOG.md)\n")
        self.assertEqual(self.run_checker(), 0)

    def test_dollar_without_placeholder_name_is_still_checked(self):
        self.write("docs/index.md", "[price]($5-plan.md)\n")
        self.assertEqual(self.run_checker(), 1)

    def test_balanced_parentheses_in_inline_link_destinations_are_preserved(self):
        targets = list(CHECKER.inline_link_targets("[guide](guide(v2).md)"))
        self.assertEqual(targets[0][0], "guide(v2).md")

    def test_setext_and_inline_code_headings_generate_expected_slugs(self):
        target = self.write(
            "docs/headings.md",
            "Installation\n============\n\n## " + "`" + "make docs" + "`" + "\n\n## Audio & Video\n",
        )
        identifiers = CHECKER.heading_ids(target)
        self.assertIn("installation", identifiers)
        self.assertIn("make-docs", identifiers)
        self.assertIn("audio--video", identifiers)


if __name__ == "__main__":
    unittest.main()
