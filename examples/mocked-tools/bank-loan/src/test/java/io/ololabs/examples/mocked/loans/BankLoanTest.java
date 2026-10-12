// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.loans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BankLoanTest {
    static JsonNode evaluate(ToolHarness tools, String applicant, double amount, int term) {
        return tools.call("evaluate", Map.of("applicantId", applicant, "amount", amount, "termMonths", term, "purpose", "PERSONAL")).output();
    }

    @Test
    void eachApplicantGetsTheirDecision() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode strong = evaluate(tools, "A-1001", 25000, 36);
            assertEquals("APPROVE", strong.path("decision").asText(), strong.toString());
            assertEquals(8.5, strong.path("annualRatePercent").asDouble());
            assertTrue(strong.path("maxApprovableAmount").asDouble() > 25000);

            JsonNode stretched = evaluate(tools, "A-1002", 8000, 36);
            assertEquals("REFER", stretched.path("decision").asText(), stretched.toString());

            assertEquals("DECLINE", evaluate(tools, "A-1003", 10000, 24).path("decision").asText());
            JsonNode newBusiness = evaluate(tools, "a-1004", 15000, 36);
            assertEquals("REFER", newBusiness.path("decision").asText());
            assertTrue(newBusiness.path("reasons").toString().contains("Self-employed"), newBusiness.toString());
        }
    }

    @Test
    void tooMuchDebtIsDeclined() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = evaluate(tools, "A-1001", 400000, 24);
            assertEquals("DECLINE", out.path("decision").asText(), out.toString());
            assertTrue(out.path("reasons").get(0).asText().contains("50% limit"));
        }
    }

    @Test
    void recordingTwiceWithOneReferenceRecordsOnce() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            Map<String, Object> args = Map.of("applicantId", "A-1002", "decision", "REFER", "amount", 20000, "reference", "case-9");
            JsonNode first = tools.call("record-decision", args).output();
            assertEquals(first, tools.call("record-decision", args).output());
            assertEquals("Case placed in the underwriting queue.", first.path("nextStep").asText());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
