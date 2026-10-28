// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conformance: dev-server/tool-harness, and test mode as runtime-modes.md §4 describes it. */
class ToolHarnessTest {
    private static ToolHarness.Builder harness() {
        return ToolHarness.builder().secret("ApiKey", "test-key").claim("sub", "user-1");
    }

    @Test
    void routeCallsAreAnsweredFromRecordedExchangesAndRecordedByRouteAndVariables() {
        try (ToolHarness h = harness()
                .exchange(RecordedExchange.on("orders.get").variable("orderId", "42").replyJson(200, Map.of("id", "42", "status", "SHIPPED")))
                .build()) {
            ToolResult r = h.call("orders.get", Map.of("orderId", "42"));
            assertEquals("SHIPPED", r.output().path("status").asText(), r.toString());
            assertEquals(3, r.output().path("limit").asInt(), "the global default");
            List<RouteCallRecord> calls = h.routeCalls();
            assertEquals(1, calls.size());
            assertEquals("orders.get", calls.get(0).route());
            assertEquals(Map.of("orderId", "42"), calls.get(0).variables());
            assertEquals("orders-key", calls.get(0).auth());
            assertFalse(calls.get(0).headers().containsKey("X-Api-Key"), "credentials are never resolved in test mode");
            assertEquals("fixture", r.json().path("_meta").path("io.ololabs.toolgate/identity").asText());
        }
    }

    @Test
    void fixturesSetGlobalsAndTheToolIsFoundByAnyOfItsNames() {
        try (ToolHarness h = harness().global("Limit", 10)
                .exchange(RecordedExchange.on("orders.get").replyJson(200, Map.of("id", "1", "status", "OPEN"))).build()) {
            assertEquals(10, h.call("orders.get", "{\"orderId\":\"1\"}").output().path("limit").asInt());
            assertEquals(10, h.call("acme.orders/orders.get", "{\"orderId\":\"1\"}").output().path("limit").asInt());
            assertThrows(IllegalArgumentException.class, () -> h.call("orders.missing", "{}"));
        }
    }

    @Test
    void anUnrecordedCallFailsAndIsStillRecorded() {
        try (ToolHarness h = harness().build()) {
            ToolResult r = h.call("orders.get", Map.of("orderId", "5"));
            assertEquals("ROUTE_REFUSED", r.errorCode(), r.toString());
            assertFalse(h.routeCalls().get(0).answered());
        }
    }

    @Test
    void upstreamStatusesAndInjectedFailuresBecomeTheUsualErrors() {
        try (ToolHarness h = harness()
                .exchange(RecordedExchange.on("orders.get").variable("orderId", "1").replyText(503, "busy"))
                .exchange(RecordedExchange.on("orders.get").variable("orderId", "2").fail(RecordedExchange.Failure.TIMEOUT_AFTER_SEND))
                .exchange(RecordedExchange.on("orders.get").variable("orderId", "3").fail(RecordedExchange.Failure.CONNECTION_REFUSED))
                .build()) {
            assertEquals("UPSTREAM_5XX", h.call("orders.get", Map.of("orderId", "1")).errorCode());
            ToolResult timeout = h.call("orders.get", Map.of("orderId", "2"));
            assertEquals("UPSTREAM_TIMEOUT", timeout.errorCode());
            assertEquals("UPSTREAM_CONNECTION", h.call("orders.get", Map.of("orderId", "3")).errorCode());
        }
    }

    @Test
    void asyncToolsRunAsTasksWithFixtureClaimsAndJsonBodies() {
        try (ToolHarness h = harness()
                .exchange(RecordedExchange.on("orders.cancel").body(Map.of("by", "user-1")).reply(204))
                .build()) {
            ToolResult r = h.call("orders.cancel", Map.of("orderId", "77"));
            assertEquals("77", r.output().path("cancelled").asText(), r.toString());
            RouteCallRecord call = h.routeCalls().get(0);
            assertEquals("POST", call.method());
            assertEquals("user-1", call.body().path("by").asText());
            assertNull(call.auth());
        }
    }

    @Test
    void examplesRunWithTheirOwnRecordedExchanges() {
        try (ToolHarness h = harness().build()) {
            List<ExampleResult> results = h.runExamples();
            assertEquals(3, results.size(), results.toString());
            assertTrue(results.get(0).passed(), results.get(0).toString());
            assertTrue(results.get(1).passed(), results.get(1).toString());
            assertFalse(results.get(2).passed());
            assertTrue(results.get(2).problem().contains("without a recorded mock exchange"), results.get(2).toString());
            AssertionError e = assertThrows(AssertionError.class, h::assertExamples);
            assertTrue(e.getMessage().contains("1 example(s) failed"), e.getMessage());
        }
    }

    @Test
    void recordedExchangesLoadFromTheLanguageNeutralFormat() {
        String json = """
                [{"request": {"route": "orders.get", "variables": {"orderId": "1"}, "query": {"expand": ["lines"]}},
                  "response": {"status": 200, "body": {"id": "1"}}},
                 {"request": {"route": "orders.get"}, "response": {"failure": "RESET_AFTER_SEND"}}]
                """;
        List<RecordedExchange> loaded = RecordedExchange.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, loaded.size());
        assertEquals("orders.get", loaded.get(0).route());
        assertThrows(IllegalArgumentException.class, () -> RecordedExchange.load(new ByteArrayInputStream(
                "[{\"request\": {\"route\": \"x\", \"host\": \"evil\"}, \"response\": {}}]".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> RecordedExchange.load(new ByteArrayInputStream(
                "[{\"request\": {\"route\": \"x\"}, \"response\": {\"failure\": \"MELT\"}}]".getBytes(StandardCharsets.UTF_8))));
    }
}
