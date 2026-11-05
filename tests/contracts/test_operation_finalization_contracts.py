# Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0
"""Derived outcomes are separate, bounded metadata with no capability-bearing history."""
import copy
import json
from pathlib import Path
import unittest
from test_foundation import compatible, schemas_at, validators

ROOT=Path(__file__).resolve().parents[2]


class OperationFinalizationContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schemas=schemas_at(ROOT/"packages/contracts/schemas/v1")
        cls.validators=validators(cls.schemas)
        cls.fixtures=json.loads((ROOT/"tests/fixtures/contracts/v1/valid.json").read_text())

    def test_legacy_state_and_upgraded_projection_are_distinct_closed_contracts(self):
        original=copy.deepcopy(self.fixtures["EnterpriseInvocation"])
        self.validators["EnterpriseInvocation"].validate(original)
        value=original|{"operationOutcome":self.fixtures["EnterpriseOperationOutcome"]}
        self.validators["EnterpriseInvocation"].validate(value)
        # Reconstruct the immediately preceding closed invocation reader, whose
        # sole shape difference is this new optional response projection.
        previous=copy.deepcopy(self.schemas)
        old=previous["enterprise.schema.json"]["$defs"]["EnterpriseInvocation"]
        del old["properties"]["operationOutcome"]
        archived=validators(previous)["EnterpriseInvocation"]
        archived.validate(original)
        self.assertFalse(archived.is_valid(value))
        with self.assertRaises(ValueError):
            compatible(old,self.schemas["enterprise.schema.json"]["$defs"]["EnterpriseInvocation"])

    def test_native_reported_result_carries_a_separate_optional_business_projection(self):
        original=copy.deepcopy(self.fixtures["RemoteToolResponse"])
        current=self.validators["RemoteToolResponse"]
        current.validate(original)
        value=original|{"operationOutcome":self.fixtures["EnterpriseOperationOutcome"]}
        current.validate(value)
        previous=copy.deepcopy(self.schemas)
        old=previous["endpoint.schema.json"]["$defs"]["RemoteToolResponse"]
        del old["properties"]["operationOutcome"]
        archived=validators(previous)["RemoteToolResponse"]
        archived.validate(original)
        self.assertFalse(archived.is_valid(value))
        self.assertFalse(current.is_valid(value|{"operationOutcome":{"state":"SUCCEEDED"}}))

    def test_human_scope_review_is_positive_exact_and_revision_bound(self):
        original=self.fixtures["EnterpriseAttemptSettlementReview"]
        validator=self.validators["EnterpriseAttemptSettlementReview"]
        validator.validate(original)
        for changed in ({"noFurtherEffects":False},{"expectedOutcomeRevision":0},{"attemptIds":[]},
                        {"attemptIds":["same","same"]},{"attemptIds":[f"attempt-{n}" for n in range(65)]}):
            self.assertFalse(validator.is_valid(original|changed))
        value=copy.deepcopy(self.fixtures["EnterpriseReconciliationRequest"])
        self.validators["EnterpriseReconciliationRequest"].validate(value|{"attemptSettlement":original})

    def test_unknown_and_reconciliation_metadata_cannot_grant_retry(self):
        original=self.fixtures["EnterpriseOperationOutcome"]
        validator=self.validators["EnterpriseOperationOutcome"]
        for state in ("RUNNING","OUTCOME_UNKNOWN","RECONCILIATION_REQUIRED"):
            value=original|{"state":state,"finalized":False,"retryable":False}
            validator.validate(value)
            self.assertFalse(validator.is_valid(value|{"retryable":True}))
            self.assertFalse(validator.is_valid(value|{"finalized":"true"}))

    def test_derived_states_are_not_raw_worker_outcomes_and_all_evidence_fields_are_required(self):
        original=self.fixtures["EnterpriseOperationOutcome"]
        validator=self.validators["EnterpriseOperationOutcome"]
        for state in ("SUCCEEDED","PARTIAL","FAILED"):
            validator.validate(original|{"state":state,"finalized":True,"journalComplete":True})
        for state in ("COMPLETED","RETRYABLE","CANCELLED","ERROR"):
            self.assertFalse(validator.is_valid(original|{"state":state}))
        for field in ("grade","journalComplete","unresolvedEffectCount","unsettledAttemptCount"):
            value=copy.deepcopy(original);del value[field]
            self.assertFalse(validator.is_valid(value),field)

    def test_history_does_not_accept_capabilities_payloads_or_target_claim_tokens(self):
        for model,fields in (
            ("EnterpriseAttemptHistory",("permit","rawArguments","fencingCapability")),
            ("EnterpriseEffectHistory",("authNonce","claimToken","requestBody","targetReceipt")),
            ("EnterpriseOperationHistoryPage",("dispatch","rawEvidence","secret"))):
            value=self.fixtures[model]
            self.validators[model].validate(value)
            for field in fields:
                self.assertFalse(self.validators[model].is_valid(value|{field:"untrusted"}),field)

    def test_history_pages_and_generation_identities_are_bounded(self):
        page=self.fixtures["EnterpriseOperationHistoryPage"]
        validator=self.validators["EnterpriseOperationHistoryPage"]
        effect=self.fixtures["EnterpriseEffectHistory"]
        attempt=self.fixtures["EnterpriseAttemptHistory"]
        for change in ({"effects":[effect]*101},{"attempts":[attempt]*65},{"next":"x"*513}):
            self.assertFalse(validator.is_valid(page|change))
        for change in ({"generation":0},{"requestDigest":"a"*64},{"state":"RETRYABLE"}):
            self.assertFalse(self.validators["EnterpriseEffectHistory"].is_valid(effect|change))


if __name__=="__main__":
    unittest.main()
