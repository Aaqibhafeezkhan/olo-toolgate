// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.fraud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FraudDetectionTest {
    @Test
    void explainsTheScore() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("risk-score", Map.of("transactionId", "t-2")).output();
            // 2399 / 64 is about 37x the average (30) + foreign country (25) + new device (20).
            assertEquals(75, out.path("score").asInt(), out.toString());
            assertEquals("HIGH", out.path("level").asText());
            assertEquals("HOLD", out.path("recommendedAction").asText());
            assertEquals("AMOUNT_SPIKE", out.path("signals").get(0).path("rule").asText());

            JsonNode giftCards = tools.call("risk-score", Map.of("transactionId", "T-3")).output();
            assertEquals(100, giftCards.path("score").asInt());
            assertEquals("FREEZE_CARD", giftCards.path("recommendedAction").asText());
            assertEquals(5, giftCards.path("signals").size());
        }
    }

    @Test
    void alertsAreRiskiestFirst() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode alerts = tools.call("alerts", Map.of()).output().path("alerts");
            assertEquals(2, alerts.size());
            assertEquals("T-3", alerts.get(0).path("transactionId").asText());
            assertEquals(3, tools.call("alerts", Map.of("minScore", 0)).output().path("alerts").size());
        }
    }

    @Test
    void aRepeatedFreezeReturnsTheFirstCase() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            Map<String, Object> args = Map.of("transactionId", "T-3", "reason", "Gift card burst", "reference", "case-9");
            JsonNode first = tools.call("freeze-card", args).output();
            assertEquals("FC-c1711472", first.path("caseId").asText());
            assertEquals("4242", first.path("cardLast4").asText());
            assertEquals(first, tools.call("freeze-card", args).output());
        }
    }

    @Test
    void unknownTransactionIsNotFound() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("NOT_FOUND", tools.call("transaction", Map.of("transactionId", "T-9")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
