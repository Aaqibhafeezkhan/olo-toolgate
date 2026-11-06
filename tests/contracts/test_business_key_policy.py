# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Admission key policy is reviewed tool metadata, never caller namespace authority."""
import copy
import json
from pathlib import Path
import unittest

from test_foundation import compatible, schemas_at, validators

ROOT = Path(__file__).resolve().parents[2]


class BusinessKeyPolicySchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validators = validators(schemas_at(ROOT / "packages/contracts/schemas/v1"))

    def test_current_tool_and_explicit_policy_are_valid(self):
        tool = json.loads((ROOT / "tests/fixtures/contracts/v1/valid.json").read_text())["ToolDefinition"]
        self.validators["ToolDefinition"].validate(tool)
        for namespace in ("AGENT", "DELEGATED_USER", "TENANT"):
            value = copy.deepcopy(tool)
            value["businessKeyPolicy"] = {"required": True, "namespace": namespace,
                                         "argument": "paymentKey", "retentionMs": 2592000000}
            self.validators["ToolDefinition"].validate(value)


    def test_policy_requires_current_readers(self):
        baseline = schemas_at(ROOT / "tests/fixtures/contracts/v1/schemas")
        current = schemas_at(ROOT / "packages/contracts/schemas/v1")
        tool = json.loads((ROOT / "tests/fixtures/contracts/v1/valid.json").read_text())["ToolDefinition"]
        tool.pop("reconciliation", None)
        tool.pop("businessKeyPolicy", None)
        archived = validators(baseline)["ToolDefinition"]
        archived.validate(tool)
        extension = {"required": True, "namespace": "DELEGATED_USER", "argument": "paymentKey"}
        value = tool | {"businessKeyPolicy": extension}
        self.validators["ToolDefinition"].validate(value)
        self.assertFalse(archived.is_valid(value))
        self.assertEqual({"$ref": "tool.schema.json#/$defs/BusinessKeyPolicy"},
                         current["tool.schema.json"]["$defs"]["ToolDefinition"]["properties"]["businessKeyPolicy"])
        with self.assertRaises(ValueError):
            compatible(baseline["tool.schema.json"], current["tool.schema.json"])

    def test_policy_cannot_assert_owner_or_enable_result_sharing(self):
        valid = {"required": True, "namespace": "TENANT"}
        for extra in ({"namespaceIdentity": "other-user"}, {"callerUserId": "alice"},
                      {"shareResultsWithinTenant": True}, {"source": "HEADER"}):
            with self.subTest(extra=extra):
                self.assertFalse(self.validators["BusinessKeyPolicy"].is_valid(valid | extra))

    def test_missing_namespace_and_invalid_limits_are_refused(self):
        validator = self.validators["BusinessKeyPolicy"]
        for value in (
            {"required": True}, {"namespace": "AGENT"},
            {"required": True, "namespace": "GLOBAL"},
            {"required": True, "namespace": "AGENT", "dedupeWindowMs": 999},
            {"required": True, "namespace": "AGENT", "retentionMs": 0},
            {"required": True, "namespace": "AGENT", "retentionMs": 31536000001},
            {"required": True, "namespace": "AGENT", "argument": "nested.key"},
            {"required": "true", "namespace": "AGENT"},
        ):
            with self.subTest(value=value):
                self.assertFalse(validator.is_valid(value))


if __name__ == "__main__":
    unittest.main()
