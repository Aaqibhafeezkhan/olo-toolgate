// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HrHelpdeskTest {
    @Test
    void leaveIsTheCallersOwn() {
        try (ToolHarness tools = ToolHarness.builder().claim("employee_id", "E-101").build()) {
            assertEquals(6.5, tools.call("my-leave", Map.of()).output().path("annual").asDouble());
            // Mon 2026-11-02 to Fri 2026-11-06 is 5 working days; the weekend after is not counted.
            JsonNode req = tools.call("request-leave", Map.of("type", "ANNUAL", "from", "2026-11-02", "to", "2026-11-08",
                    "reference", "r1")).output();
            assertEquals(5, req.path("workingDays").asInt(), req.toString());
            assertEquals("E-100 (Fatima Rahman)", req.path("approver").asText());
            assertEquals(1.5, req.path("balanceAfter").asDouble());
            assertEquals("INSUFFICIENT_BALANCE", tools.call("request-leave", Map.of("type", "ANNUAL", "from", "2026-11-02",
                    "to", "2026-11-13", "reference", "r2")).errorCode());
        }
    }

    @Test
    void withoutAnIdentityThereIsNoLeave() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("CLAIMS_UNAVAILABLE", tools.call("my-leave", Map.of()).errorCode());
        }
    }

    @Test
    void answersFromPolicy() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode out = tools.call("policy", Map.of("question", "How much parental leave do I get?")).output();
            assertEquals("HR-5.3", out.path("sections").get(0).path("section").asText(), out.toString());
            assertEquals(0, tools.call("policy", Map.of("question", "What is the wifi password?")).output().path("sections").size());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
