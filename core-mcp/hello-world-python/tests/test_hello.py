# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
import unittest
from toolgate.testing import ToolHarness
import hello_world_python


class HelloWorldTest(unittest.TestCase):
    def test_both_declarations_share_defaults_output_and_validation(self):
        with ToolHarness([hello_world_python]) as harness:
            for name in ("client_greet", "server_greet"):
                with self.subTest(tool=name):
                    self.assertEqual({"message": "Hello World"}, harness.call(name, {}).output)
                    self.assertEqual({"message": "Hello Ada"}, harness.call(name, {"text": "Ada"}).output)
                    self.assertTrue(harness.call(name, {"text": "x" * 129}).is_error)
