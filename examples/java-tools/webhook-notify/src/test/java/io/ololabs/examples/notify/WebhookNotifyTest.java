// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.RecordedExchange;
import io.ololabs.toolgate.testing.ToolHarness;
import io.ololabs.toolgate.testing.ToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookNotifyTest {
    static final Map<String, Object> ALERT = Map.of("channel", "ops-alerts", "title", "Disk almost full",
            "text", "/var is 93% used", "severity", "critical", "reference", "inc-42");

    @Test
    void postsTheMessageToTheChannel() {
        try (ToolHarness tools = ToolHarness.builder()
                .secret("WebhookApiKey", "test-key")
                .global("Source", "prod-eu")
                .exchange(RecordedExchange.on("hook.post").variable("channel", "ops-alerts")
                        .body(Map.of("title", "Disk almost full", "text", "/var is 93% used", "severity", "critical",
                                "source", "prod-eu", "reference", "inc-42"))
                        .replyJson(202, Map.of("id", "msg-9")))
                .build()) {
            ToolResult result = tools.call("send", ALERT);
            JsonNode out = result.output();
            assertEquals(202, out.path("httpStatus").asInt(), result.toString());
            assertEquals("msg-9", out.path("receiptId").asText());
            assertEquals(1, tools.routeCalls().size());
        }
    }

    @Test
    void sendsOncePerReference() {
        try (ToolHarness tools = ToolHarness.builder()
                .secret("WebhookApiKey", "test-key")
                .exchange(RecordedExchange.on("hook.post").variable("channel", "ops-alerts").replyJson(202, Map.of("id", "msg-1")))
                .build()) {
            ToolResult first = tools.call("send", ALERT);
            ToolResult again = tools.call("send", ALERT);
            assertEquals(first.output(), again.output(), again.toString());
            assertEquals(1, tools.routeCalls().size(), "the repeat was answered from the first result");
        }
    }

    @Test
    void refusesChannelsOutsideTheAllowlist() {
        try (ToolHarness tools = ToolHarness.builder().global("AllowedChannels", "ops-alerts, sre").build()) {
            Map<String, Object> other = new java.util.HashMap<>(ALERT);
            other.put("channel", "everyone");
            assertEquals("CHANNEL_NOT_ALLOWED", tools.call("send", other).errorCode());
            assertEquals(0, tools.routeCalls().size());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().secret("WebhookApiKey", "test-key").build()) {
            tools.assertExamples();
        }
    }
}
