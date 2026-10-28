// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.examples.mocked.incidents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.ololabs.toolgate.testing.ToolHarness;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Walks the whole recovery an on-call agent would: incident, host, crash, runbook, remediation, verification, update. */
class ServerRecoveryTest {
    @Test
    void recoversTheCrashedService() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            JsonNode incident = tools.call("incidents", Map.of()).output().path("incidents").get(0);
            assertEquals("INC-5001", incident.path("incidentId").asText());

            JsonNode host = tools.call("host-status", Map.of("host", incident.path("host").asText())).output();
            assertEquals("CRASHED", host.path("services").get(0).path("state").asText(), host.toString());

            JsonNode crash = tools.call("crash-report", Map.of("host", "app-02")).output();
            assertEquals("OOM", crash.path("cause").asText());
            assertTrue(crash.path("relatedChange").asText().contains("2.14.0"));

            JsonNode runbook = tools.call("runbook", Map.of("cause", crash.path("cause").asText())).output();
            assertTrue(runbook.path("steps").get(1).asText().contains("ROLLBACK_DEPLOY"), runbook.toString());

            Map<String, Object> fix = Map.of("host", "app-02", "service", "payments-api", "action", "ROLLBACK_DEPLOY", "reference", "INC-5001-1");
            JsonNode done = tools.call("remediate", fix).output();
            assertEquals("SUCCEEDED", done.path("outcome").asText(), done.toString());
            assertEquals(done, tools.call("remediate", fix).output(), "a retry with the same reference acts once");

            JsonNode verified = tools.call("verify", Map.of("remediationId", done.path("remediationId").asText())).output();
            assertTrue(verified.path("recovered").asBoolean());

            JsonNode update = tools.call("update-incident", Map.of("incidentId", "INC-5001", "status", "RESOLVED",
                    "note", "Rolled back; heap steady", "reference", "u-2")).output();
            assertEquals("[\"#inc-5001\",\"payments-oncall\",\"status page\"]", update.path("notified").toString());
        }
    }

    @Test
    void unknownHostsAndServices() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            assertEquals("HOST_NOT_FOUND", tools.call("crash-report", Map.of("host", "web-03")).errorCode(), "web-03 never crashed");
            assertEquals("HOST_NOT_FOUND", tools.call("remediate", Map.of("host", "app-02", "service", "nginx",
                    "action", "RESTART_SERVICE", "reference", "x")).errorCode());
        }
    }

    @Test
    void everyExampleHolds() {
        try (ToolHarness tools = ToolHarness.builder().build()) {
            tools.assertExamples();
        }
    }
}
