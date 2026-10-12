// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InsuranceClaimsTest {
    @Test
    void exclusionsAndDatesDecideCover() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode flood = tools.call("assess", Map.of("policyId", "POL-2001", "peril", "flood", "lossAmount", 30000,
                    "incidentDate", "2026-10-01")).output();
            assertFalse(flood.path("covered").asBoolean());
            assertEquals("FLOOD is excluded by this policy.", flood.path("reason").asText());

            JsonNode lapsed = tools.call("assess", Map.of("policyId", "POL-2003", "peril", "FIRE", "lossAmount", 9000,
                    "incidentDate", "2026-09-01")).output();
            assertFalse(lapsed.path("covered").asBoolean());
            assertTrue(lapsed.path("reason").asText().contains("outside the policy's cover"), lapsed.toString());
        }
    }

    @Test
    void payoutIsCappedAtTheLimit() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("assess", Map.of("policyId", "POL-2002", "peril", "collision", "lossAmount", 52000,
                    "incidentDate", "2026-10-05")).output();
            assertEquals(39500.0, out.path("payout").asDouble(), out.toString());
            assertFalse(out.path("fastTrack").asBoolean());
        }
    }

    @Test
    void aRepeatedFilingReturnsTheFirstClaim() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            Map<String, Object> args = Map.of("policyId", "POL-2001", "peril", "STORM", "lossAmount", 4200,
                    "incidentDate", "2026-10-01", "description", "Roof tiles blown off", "reference", "storm-14");
            JsonNode first = tools.call("file-claim", args).output();
            assertEquals("CLM-d6b265b2", first.path("claimId").asText());
            assertTrue(first.path("fastTrack").asBoolean());
            assertEquals(first, tools.call("file-claim", args).output());
        }
    }

    @Test
    void badDateIsAnInputError() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("INVALID_ARGUMENT", tools.call("assess", Map.of("policyId", "POL-2001", "peril", "STORM",
                    "lossAmount", 100, "incidentDate", "last week")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
