// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.RecordedExchange;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EndpointCheckTest {
    @Test
    void upWhenTheServiceSaysSo() {
        try (ToolHarness tools = ToolHarness.builder()
                .exchange(RecordedExchange.on("health.two").variable("a", "actuator").variable("b", "health")
                        .replyJson(200, Map.of("status", "UP")))
                .build()) {
            ToolResult result = tools.call("check", Map.of("path", "/actuator/health", "expectStatus", "up"));
            JsonNode out = result.output();
            assertTrue(out.path("up").asBoolean(), result.toString());
            assertEquals("/actuator/health", out.path("path").asText());
            assertEquals(200, out.path("httpStatus").asInt());
            assertEquals("UP", out.path("reportedStatus").asText());
        }
    }

    @Test
    void downWhenTheStatusIsNotTheExpectedOne() {
        try (ToolHarness tools = ToolHarness.builder()
                .exchange(RecordedExchange.on("health.one").variable("a", "healthz").replyText(200, "degraded"))
                .build()) {
            JsonNode out = tools.call("check", Map.of("path", "healthz", "expectStatus", "ok")).output();
            assertFalse(out.path("up").asBoolean(), out.toString());
            assertEquals("degraded", out.path("reportedStatus").asText());
            assertEquals("reported degraded, expected ok", out.path("reason").asText());
        }
    }

    @Test
    void downOnAServerError() {
        try (ToolHarness tools = ToolHarness.builder()
                .exchange(RecordedExchange.on("health.one").variable("a", "health").replyJson(503, Map.of("status", "DOWN")))
                .build()) {
            JsonNode out = tools.call("check", Map.of()).output();
            assertFalse(out.path("up").asBoolean(), out.toString());
            assertEquals(503, out.path("httpStatus").asInt());
            assertEquals("HTTP 503", out.path("reason").asText());
        }
    }

    @Test
    void downWhenUnreachable() {
        try (ToolHarness tools = ToolHarness.builder()
                .exchange(RecordedExchange.on("health.one").variable("a", "health").fail(RecordedExchange.Failure.CONNECTION_REFUSED))
                .build()) {
            JsonNode out = tools.call("check", Map.of()).output();
            assertFalse(out.path("up").asBoolean(), out.toString());
            assertEquals(0, out.path("httpStatus").asInt());
            assertTrue(out.path("reason").asText().startsWith("UPSTREAM_"), out.toString());
        }
    }

    @Test
    void reachesOnlyTheDeclaredShapes() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("INVALID_ARGUMENT", tools.call("check", Map.of("path", "a/b/c")).errorCode());
            assertEquals("INVALID_ARGUMENT", tools.call("check", Map.of("path", "..")).errorCode());
            assertEquals(0, tools.routeCalls().size());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
