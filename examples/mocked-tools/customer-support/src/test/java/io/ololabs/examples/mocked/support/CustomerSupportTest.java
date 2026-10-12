// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomerSupportTest {
    @Test
    void tracksALateOrder() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("order", Map.of("orderId", "o-9002")).output();
            assertTrue(out.path("late").asBoolean(), out.toString());
            assertEquals(3, out.path("daysLate").asInt());
            assertFalse(tools.call("order", Map.of("orderId", "O-9001")).output().path("late").asBoolean());
        }
    }

    @Test
    void appliesTheRefundPolicy() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode damaged = tools.call("refund-eligibility", Map.of("orderId", "O-9004", "reason", "DAMAGED")).output();
            assertTrue(damaged.path("eligible").asBoolean());
            assertEquals(75.0, damaged.path("amount").asDouble());
            JsonNode late = tools.call("refund-eligibility", Map.of("orderId", "O-9002", "reason", "LATE")).output();
            assertTrue(late.path("eligible").asBoolean(), late.toString());
            JsonNode changed = tools.call("refund-eligibility", Map.of("orderId", "O-9001", "reason", "CHANGED_MIND")).output();
            assertTrue(changed.path("returnRequired").asBoolean(), changed.toString());
        }
    }

    @Test
    void unknownIds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("NOT_FOUND", tools.call("customer", Map.of("idOrEmail", "nobody@example.com")).errorCode());
            assertEquals("NOT_FOUND", tools.call("order", Map.of("orderId", "O-1")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
