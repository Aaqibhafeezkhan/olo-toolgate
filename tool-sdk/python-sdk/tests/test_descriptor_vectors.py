# Copyright 2026 OLO Labs
# SPDX-License-Identifier: Apache-2.0
"""Conformance: descriptors/canonical-bytes, set-digest, excludes-deployment and reproducibility for Python.

The fixtures under tests/fixtures mirror the Java processor fixtures, so both SDKs must emit the vector bytes.
"""

import json
import re
import unittest

from support import FIXTURES, VECTORS, compile_package, fixture_modules

from toolgate import jcs
from toolgate.descriptor import build


def expected(file):
    data = json.loads((VECTORS / "expected.json").read_text(encoding="utf-8"))
    return data["digests"].get(file) or data["sets"][file]


class DescriptorVectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.files = build(fixture_modules("files"), 1)
        cls.orders = build(fixture_modules("orders"), 1)

    def assert_vector(self, result, tool, vector):
        emitted = result.bytes[tool]
        wanted = (VECTORS / vector.replace(".json", ".jcs")).read_bytes()
        self.assertEqual(wanted.decode("utf-8"), emitted.decode("utf-8"))
        self.assertEqual(wanted, emitted)
        self.assertEqual(expected(vector), jcs.sha256(emitted))

    def test_client_tool_matches_tool01(self):
        self.assert_vector(self.files, "files.checksum", "tool-01-checksum.json")

    def test_server_tool_matches_tool02(self):
        self.assert_vector(self.orders, "orders.lookup", "tool-02-orders-lookup.json")

    def test_task_tool_with_non_ascii_names_matches_tool03(self):
        self.assert_vector(self.orders, "orders.refund", "tool-03-refund.json")

    def test_set_digest_matches_set01(self):
        entries = [{"toolId": "acme.orders/" + t, "toolDescriptorDigest": jcs.sha256(self.orders.bytes[t])}
                   for t in ("orders.lookup", "orders.refund")]
        self.assertEqual(expected("set-01-orders.json"), jcs.sha256(jcs.canonical(entries)))

    def test_package_index_carries_digests_and_handlers(self):
        index = self.orders.index_bytes.decode("utf-8")
        self.assertIn('"handlerModule":"acme_orders.refund_tools","handlerQualname":"RefundTools.refund"', index)
        self.assertIn('"parameters":["orderId","reason","refundKey","$context"]', index)
        self.assertIn('"toolDescriptorDigest":"' + jcs.sha256(self.orders.bytes["orders.refund"]) + '"', index)
        self.assertTrue(index.startswith('{"descriptorGeneration":1,"id":"acme.orders"'), index)

    def test_same_source_gives_identical_bytes(self):
        again = build(fixture_modules("orders"), 1)
        for tool in ("orders.lookup", "orders.refund", "orders.refund-status"):
            self.assertEqual(self.orders.bytes[tool], again.bytes[tool], tool)
        self.assertEqual(self.orders.index_bytes, again.index_bytes)

    def test_deployment_values_and_examples_do_not_change_the_digest(self):
        source = FIXTURES / "orders" / "acme_orders"
        package = (source / "__init__.py").read_text(encoding="utf-8") \
            .replace('default_host="api.acme.com"', 'default_host="staging.acme.example"')
        lookup = (source / "lookup_tools.py").read_text(encoding="utf-8") \
            .replace("    def lookup(", '    @example(arguments={"orderId": "123456"})\n    def lookup(') \
            .replace("server_tool)", "server_tool, example)")
        refund = (source / "refund_tools.py").read_text(encoding="utf-8")
        self.assertIn("@example", lookup)
        changed = compile_package({"__init__": package, "lookup_tools": lookup, "refund_tools": refund})
        self.assertTrue(changed.success, changed.errors)
        self.assert_vector(changed.build, "orders.lookup", "tool-02-orders-lookup.json")
        self.assertIn('"examples":[{"arguments":"{\\"orderId\\":\\"123456\\"}"}]',
                      changed.build.index_bytes.decode("utf-8"))


class JcsTest(unittest.TestCase):
    """RFC 8785 serialisation details the vectors do not reach."""

    def test_numbers_use_ecmascript_formatting(self):
        self.assertEqual("1", jcs.number(1.0))
        self.assertEqual("0.1", jcs.number(0.1))
        self.assertEqual("-2.5", jcs.number(-2.5))
        self.assertEqual("0", jcs.number(-0.0))
        self.assertEqual("1e+21", jcs.number(1e21))
        self.assertEqual("123456789012345680000", jcs.number(123456789012345680000.0))
        self.assertEqual("0.000001", jcs.number(0.000001))
        self.assertEqual("1e-7", jcs.number(1e-7))
        self.assertEqual("5e-324", jcs.number(5e-324))
        self.assertEqual("1.7976931348623157e+308", jcs.number(1.7976931348623157e308))
        self.assertEqual("333333333.3333333", jcs.number(333333333.33333329))
        with self.assertRaises(ValueError):
            jcs.number(float("nan"))

    def test_strings_escape_only_what_json_requires(self):
        self.assertEqual('"\\u0001\\n\\"\\\\/ é"', jcs.canonical("\u0001\n\"\\/ é").decode("utf-8"))

    def test_keys_are_nfc_and_sorted_by_utf16(self):
        m = {"Ａlpha": 1, "😀note": 2, "état": []}
        self.assertEqual('{"état":[],"😀note":2,"Ａlpha":1}', jcs.canonical(m).decode("utf-8"))
        m["état"] = True
        with self.assertRaises(ValueError):
            jcs.canonical(m)

    def test_integers_beyond_the_safe_range_are_refused(self):
        self.assertEqual("9007199254740991", jcs.canonical(9007199254740991).decode())
        with self.assertRaises(ValueError):
            jcs.canonical(9007199254740992)


if __name__ == "__main__":
    unittest.main()
